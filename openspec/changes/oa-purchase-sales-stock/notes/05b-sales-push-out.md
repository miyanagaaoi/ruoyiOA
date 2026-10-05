# 05b · B4 销售线（下）交付记录

> 任务：`t8 [t5b-sales-push-out]`（tasks.md §4.3 + §4.4 的销售线端到端）
> 依赖：`t3-posting`（出库单与过账引擎）、`t5a-sales-core`（t5 已交付）、`t29`（T3 的只读 `selectBySourceDocId`）
> 交付人：backend-sales｜更新时间：2026-10-05
> 真源：`openspec/changes/oa-purchase-sales-stock/specs/erp/sales/spec.md`（「销售订单下推出库单」）、`tasks.md` §4.3/§4.4、`notes/05a-sales.md` §7 的欠账清单、captain 2026-10-05 的并入裁决。

---

## 1. 本组交付清单

| 文件 | 承载 |
| --- | --- |
| `erp/sales/service/IErpSalesPushService.java`（改） | 新增 `pushSalesOrderToStockOut(orderDocId, request)` |
| `erp/sales/service/impl/ErpSalesPushServiceImpl.java`（改） | 4.3 下推实现：规划（零写入）→ 委托 T3 生成草稿出库单 → 不回写 |
| `erp/sales/support/ErpSalesOutboundListener.java`（新） | **销售侧唯一的 `IErpStockPostingListener` 实现**：过账累加 / 红冲回退 |
| `erp/sales/domain/ErpSalesPushLine.java`（改） | 新增 `srcItemId` 别名键 + `effectiveDocItemId()`（前端两条链键名不同） |
| `erp/sales/ErpSalRules.java`（改） | 4.3 的冻结文案 + 两条下游守卫文案 |
| `erp/sales/service/IErpSalesOrderService.java` / `Impl`（改） | `applyShippedQtyChange`（已出库量唯一写入口）+ `checkNoDownstreamStockOuts`（订单反审核守卫） |
| `erp/sales/service/impl/ErpSalesRequestServiceImpl.java`（改） | `checkNoDownstreamOrders`（申请单反审核守卫，替换 t5a 的空实现 `autoVoidDownstreamOrders`） |
| `erp/sales/controller/ErpSalesController.java`（改） | `POST /sal/order/{id}/push`（`sal:order:push`），**裸数组与包装对象两种请求体都收** |
| `src/test/.../erp/sales/service/impl/ErpSalesPushOutTest.java`（新） | 13 条单测（含三条必测断言 + 端到端） |

**未新增任何跨包写入**：出库单的插入一律委托 T3 的 `IErpStockOutService.insertStockOut`；唯一跨包调用是**只读** `ErpStockOutMapper.selectBySourceDocId`（t29 交付，签名冻结）。

---

## 2. §4.3 销售订单 → 出库单下推（七条冻结口径）

1. **仅"已审核或已完成"可推**：否则 `仅已审核或已完成的销售订单可以下推出库单`
   （文案常量 `ErpSalRules.MSG_ORDER_NOT_PUSHABLE_OUT`）。
2. **剩余可出库量校验**：剩余量 = `qty − shipped_qty`（行项自己的两列之差，唯一实现点）；
   本次量 `null`/≤0 表示按剩余量**全推**；超量**整单拒绝**且**零中间写入**：
   `「螺丝」可下推数量不足（剩余 4，本次 5）`（与采购线、申请→订单同款文案）。
3. **生成草稿出库单**（`status=draft`），表头带：
   - 发货仓库：入参 `shipWarehouseId` 优先 → 回落订单的默认发货仓库 → **都没有则拒绝下推**
     （因为 `t_ctms_stock_out.warehouse_id` 是 **NOT NULL + FK**，缺仓库的草稿在真库上根本插不进去）；
   - 出库类型 `销售出库`（流水 `biz_type` 的真源）；
   - **客户名称快照**（`customer_id` + `customer_name` 从订单带过）；
   - 来源三列：`source_doc_type=sales_order` / `source_doc_id` / `source_doc_no`；
   - 行项 `src_item_id` = **销售订单行项ID**（过账回写的匹配依据），物料/单位快照沿用订单行
     （AC-72 的行项快照口径：物料此后改名/停用不影响这批单据）。
4. **下推本身不写 `shipped_qty`**：只在出库单**过账成功**时回写（见 §3）。
5. **单号/行项归一/金额/变更历史**全部复用 T3 的 `insertStockOut`，本组不复刻。
6. 匹配规则：`srcItemId`/`docItemId` → `productId` → `index` → 数组顺序；同一来源行重复下推直接拒绝。
7. 幂等性：草稿出库单**不占**剩余量（剩余量只看 `shipped_qty`），因此"未过账仍可再次下推"
   是自然语义，不需要额外状态位。

**端点**：`POST /sal/order/{id}/push`（`sal:order:push`）
请求体两种形状都收：前端 `sales_order` 链发的**裸数组** `[{srcItemId, qty}]`（`bodyShape=lines-array`），
以及**包装对象** `{lines:[…], remark, shipWarehouseId, docDate}`。
返回：`{stockOutId, stockOutNo, sourceDocId, sourceDocNo, pushedQtySum, remainQtySum, lines[]}`。

> ⚠ **交 t12（前端）的一处动作**：`doc-rules.js` 的 `sales_order.push` 目前是
> `{ pathSuffix: '/push', bodyShape: 'lines-array', lineKeys: {srcItemId, qty}, extraParams: [], pending: true }`。
> 端点已落地，前端需把 `pending: true` **去掉**（`pathSuffix`/`lineKeys` 已经是正确形状，不用改）。
> 同理 `purchase_order.push` 也是 `pending: true`，归 t7b 的前端收尾。

---

## 3. 过账回写与红冲回退（本组的核心语义）

**实现**：`erp/sales/support/ErpSalesOutboundListener implements IErpStockPostingListener`（`@Component`），
只 `supports(STOCK_OUT)`；回调在过账**同一事务**内执行（回写失败 → 整个过账回滚）。

| 路径 | 定位方式 | 变动量 | 落库 |
| --- | --- | --- | --- |
| `afterPosted` | 过账行的 `srcItemId` 有值（行由出库单行直接构造） | `abs(qtyChange)`（出库方向为**负**，已出库量是正累计） | `shipped_qty + delta` |
| `afterReversed` | **红冲行的 `srcItemId` 为 null**（红冲行由历史流水反推，而 `t_ctms_stock_ledger` 没有 `src_item_id` 列）⇒ 改读**出库单自己的行项**定位 | `-item.qty` | `GREATEST(0, shipped_qty - \|delta\|)` |

**唯一写入口**：`IErpSalesOrderService.applyShippedQtyChange(orderDocId, orderItemId, deltaQty)`：
行必须属于该单据（否则静默跳过，历史/脏数据不拖挂过账）；正数走 `addShippedQty`、负数走 `subtractShippedQty`。

**边界（都有用例）**：手工建的出库单（行无 `src_item_id`）、找不到订单行的历史数据、0 变动、
空集合 —— 一律跳过不报错；引擎在"没有可冲销流水"时不会回调，所以重复反审核不会重复回退。

---

## 4. 上游守卫（存在未作废下游时禁止反审核）

| 上游 | 下游 | 数据来源 | 文案 |
| --- | --- | --- | --- |
| 销售**申请单** | 销售订单 | `ErpSalesOrderMapper.selectOrdersBySourceDocId`（本包自己的表） | `已存在下游销售订单，请先处理下游单据（反审核或作废）后再操作` |
| 销售**订单** | 出库单 | **只读** `ErpStockOutMapper.selectBySourceDocId`（t29） | `已存在下游出库单，请先处理下游单据（反审核或作废）后再操作` |

两条都**先校验再改状态**（失败路径上单据对象不被改脏；真库靠事务，但内存桩没有回滚，
这个顺序让桩也能如实反映"被拒时状态不变"）。
两条都**只把"未作废"算作引用**：`selectBySourceDocId` 的 SQL 不过滤状态（把作废单也返回），
因此 **Java 侧再过滤** `status == voided`，于是"下游全部作废后上游可反审核"成立
（与采购线 `assert_no_downstream` 的口径一致）。**刻意不静默级联作废下游**：
已过账的出库单必须先红冲，这是业务语义不是技术细节。

---

## 5. 三条必测断言的**真实输出**

命令：`powershell -NoProfile -ExecutionPolicy Bypass -File tools\locked-run.ps1 -LockName build -Command "cd ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test -Dtest=ErpSalesPushOutTest"`

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

| # | 断言 | 用例 | 怎么证的 |
| --- | --- | --- | --- |
| ① | **未过账不回写、仍可再次下推** | `pushOutDoesNotWriteShippedQtyAndAllowsSecondPush` | 下推后 `shipped_qty == 0`；**再推一次成功**且生成**第二张不同的**草稿出库单；两次之后 `shipped_qty` 仍为 0 |
| ② | **过账后累加** | `postingStockOutAccumulatesShippedQty` | 走**真实** T3 过账引擎（`approveStockOut`）→ `shipped_qty == 10`，单据 `approved` 且 `posted` |
| ③ | **红冲后回退不为负** | `reversalRollsBackShippedQtyAndNeverGoesNegative` | 真实红冲（`unapproveStockOut`）→ `shipped_qty` 回到 0；再手工回调一次 `afterReversed` → 仍为 0（不为负）；把 `shipped_qty` 置 2 后再回退 10 → 夹到 0 |

其余 9 条：仅已审核/已完成可推、已完成可推、超量被拒 + 零写入、仓库解析不出被拒 + 显式指定可推、
`srcItemId` 别名 + 部分量、载体（仓库/客户快照/来源三列/`out_type`/行 `src_item_id`）、
监听器的 5 个边界、两条上游守卫（各自"被拦"与"放行"）、§4.4 端到端。

**§4.4 端到端**（`salesLineEndToEndFromRequestToPostedStockOut`）：
申请（草稿→提交→审核）→ 下推订单（草稿，申请单**归零即完成**）→ 订单提交+审核 →
下推出库单（草稿，带仓库与客户快照，`shipped_qty` 仍 0）→ 出库单提交+审核过账
（**结存 100 → 90**、`shipped_qty` 0 → 10）→ 反审核红冲（**结存回到 100**、`shipped_qty` 回到 0）。

---

## 6. 判定记录：一条红是"桩缺陷"而不是"产品缺陷"（按铁律 8 先判定再动手）

**现象**：`salesLineEndToEndFromRequestToPostedStockOut` 断言 `结存 == 90`，实际 `80`。

**诊断输出**（我在断言里临时打印，判定后已换成更强的三条断言）：
`stock=80.000 ledger=2 docs=1 pushed=20.000 outQty=10.000 shipped=20.000`

**推理**：只建了 **1 张**出库单（`docs=1`）、该单**只有一行且 qty=10**（`outQty=10`），
但 `pushedQtySum=20`、流水 **2 条** ⇒ 下推**计划里有两行** ⇒ 来源订单报了**两行**。

**根因**：`ErpSalesPushOutTest` 的 `ErpSalesOrderMapper` 内存桩在 `insertSalesOrder` 里
既 `orderItems.put(一份拷贝)`，而服务紧接着的 `writeItems` 又会 `batchInsertItems` 再 `add` 一次
⇒ **同一行被记两遍**。真库语义是"表头 insert + 行项批量 insert"，只记一遍。

**判定：测试桩缺陷（断言期望没错，产品侧一行未改）**。
修法：桩里去掉预置 `put`，只 `computeIfAbsent` 建空表，行项交给 `batchInsertItems`
（与 T3 的 `StubStockInMapper`/`StubStockOutMapper` 同款写法）。
并把端到端断言**加强**为三条：只建 1 张单、该单行 `qty=10`、结存 `100→90`（不是 80）——
正是这三条把桩的重复计数暴露出来，属于"收紧断言"而不是放宽。

---

## 7. 前端契约与权限点

| 项 | 值 | 状态 |
| --- | --- | --- |
| 端点 | `POST /sal/order/{id}/push` | ✅ 已落地 |
| 请求体 | 裸数组 `[{srcItemId, qty}]`（前端 `sales_order` 链）或包装对象 | ✅ 两种都收 |
| 权限点 | `sal:order:push`（简报 §9.2 冻结表） | ✅ 与非 push 动作集一致 |
| 列表显隐字段 | `remainQtySum` / `canPush`（订单侧 = Σ max(0, `qty − shipped_qty`)） | ✅ t5a 已落地，t5b 复用 |
| `sourceStatuses` | `['approved','completed']` | ✅ 与实现一致 |
| `remainField` | `shippedQty`（前端按 `qty − shippedQty` 算剩余） | ✅ 域对象已序列化该字段 |

---

## 8. 验证记录（真实输出）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| L1（本线 t5b） | `mvn -B -pl ruoyi-ctms test -Dtest=ErpSalesPushOutTest` | **`Tests run: 13, Failures: 0, Errors: 0`** |
| L1（销售线全量） | `mvn -B -pl ruoyi-ctms test -Dtest=ErpSal*Test` | **`Tests run: 55, Failures: 0, Errors: 0`**（规则 20 + 服务 22 + 下推 13） |
| L1（全模块回归） | `mvn -B -pl ruoyi-ctms test` | **`Tests run: 510, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`** |
| L2 | `node tools/audit/run-all.js` | **10 个审计全 OK，失败 0，未自证 0** |
| 精度自检 | `grep` `double\|float` in `erp/sales` | 0 命中（金额/数量全 `BigDecimal`） |

---

## 9. 已知边界与交给下游的事项

1. **前端 `pending` 标记**：`doc-rules.js` 的 `sales_order.push.pending` 需去掉（见 §2 的 ⚠），属 t12。
2. **出库单红冲的既有口径**：本组只消费 T3 的 `unapproveStockOut`/`reversePostedStockOut`；
   "出库单作废是否要级联处理已过账"按 T3/业务既有口径，本组不改。
3. **部分红冲不支持**：T3 的 `reverse` 冲全部未冲销流水，因此监听器在红冲路径按"出库单行数量"
   整体回退（1:1）；若将来引入部分红冲，需改成按流水 `srcItemId` 回退（已在监听器注释里登记）。
4. **销售申请单 vs 订单两段的守卫对象不同**（申请单看订单、订单看出库单）：
   两段都不静默级联；`t_ctms_stock_out` 的只读查询是本组唯一跨包读，已用 t29 的冻结签名。
