# 04b 采购线（下）：采购单 → 入库单下推 + 已入库量过账回写 + 采购线端到端

> 任务：`t7`（B4 §3.5、§3.7）｜实施与记录：backend-procurement
> 依赖：`t3-posting`（入库单/过账引擎/`IErpStockPostingListener`）、`t4a-procurement-core`（采购申请单/采购单）
> 真源：`specs/erp/procurement/spec.md`、`tasks.md` §3.5/§3.7、`doc/2.0/参考仓库-CTMS-移植清单.md` §3.9.4

---

## 1. 落地清单（本次新增/改动）

| 层 | 文件（`ruoyi-vue-oa-master/ruoyi-ctms`） | 说明 |
| --- | --- | --- |
| 规则 | `erp/procurement/ErpPurRules.java`（**追加**） | 剩余可入库量、全推/超量判定、入库侧文案（§5） |
| domain | `erp/procurement/domain/vo/ErpPushInResultVo.java`（新增） | 下推结果（草稿入库单 + 提示文案） |
| service | `erp/procurement/service/IErpPurchasePushService.java`（追加 `pushToStockIn`）、`service/impl/ErpPurchasePushServiceImpl.java`（同） | §3.5 下推实现；**不写** `received_qty` |
| 监听器 | `erp/procurement/support/ErpPurchaseReceiptListener.java`（新增） | 实现 T3 的 `IErpStockPostingListener`：过账累加 / 红冲回退 |
| controller | `erp/procurement/controller/ErpPurchaseOrderController.java`（追加） | `POST /erp/pur/order/{id}/push/stock-in`（权限 `pur:order:push`） |
| 单测 | `src/test/java/com/ruoyi/ctms/erp/procurement/ErpPurchasePushInTest.java`（新增，9 条） | 四条必需断言 + 边界 + §3.7 端到端（装配 **T3 真实**的入库单服务与过账引擎） |
| 未改动 | 采购侧 mapper/XML | 三处已就位：`increaseReceivedQty` / `decreaseReceivedQty`（`greatest(0, …)`）、行项 `selectItemById` |

**跨组消费（不复刻）**：入库单的创建/归一/单号走 `IErpStockInService.insertStockIn`；
过账与红冲走 T3 的 `ErpStockJournalServiceImpl`（唯一写入点）；
扩展点用 `IErpStockPostingListener`。本组**没有**写入库单表、**没有**写过账、**没有**第二套流水。

---

## 2. §3.5 下推口径（7 步）

```
① 取采购单（不可见 → 403）；状态必须 approved 或 completed，否则
   422「仅已审核或已完成的采购单可以下推入库单」
② 解析收货仓库：入参 warehouseId 优先 → 采购单 receipt_warehouse_id；
   两者都空 → 422「采购单未指定默认收货仓库：…」（理由见 §4 的 DDL 约束）
   仓库经 base 的 ErpMasterGuards.requireEnabledWarehouse 校验（存在 + 启用）并取名称快照
③ 解析下推计划（与申请→采购单同一套"行序对应"语义，只解释一次）：
     · lines 为空 → 全部行，各按剩余可入库量全推
     · srcItemId 为空 → 按行序对应到未匹配的采购单行
     · srcItemId 非空但不在采购单行里 → 422「下推行项不属于该采购单」
     · 同一行重复出现 → 合并为一条（取后者数量）
④ 逐行算量并校验（全部算完才落库 ⇒ 失败路径零中间写入）
     剩余可入库量 = qty − received_qty（不小于 0）
     · 剩余量 ≤ 0 → 422「已无可入库数量（剩余 0）」
     · 本次量 > 剩余量 → 422「采购单「<物料>」可入库数量不足（剩余 R，本次 Q）」
     · 不填数量 → 按剩余量全推
⑤ 生成**草稿**入库单（走 T3 的 insertStockIn）：
     · 表头：warehouse_id/warehouse_name、in_type=采购入库、
       supplier_id/supplier_name 快照、contract_id/contract_no、
       source_doc_type=purchase_order + source_doc_id/source_doc_no、doc_date 取采购单日期
     · 行项：product_id + qty=本次下推量 + unit_price=采购单行单价 +
       warehouse_id=采购单行级收货仓库（空则由 T3 回落表头）+ **src_item_id=采购单行项ID**
⑥ **不写** received_qty（这是本任务最关键的一条：必须等入库单过账）
⑦ 返回 ErpPushInResultVo：草稿入库单、sourceDocNo、pushedQty、
     remainQtySum（= 本次下推**前**的剩余合计 − 本次下推量，即"还有多少没安排"）、message
```

> ⚠ `remainQtySum` 的口径说明：`received_qty` **未回写**，所以库里的剩余量不会因为"下推"而变化。
> 为了给前端一个"还有多少可推"的有用数字，这里返回 **减去本次下推量后的余量**；
> 真正的库内剩余量要等过账回写后才对齐（§8 的断言清单里两条都有）。

---

## 3. 过账回写（`ErpPurchaseReceiptListener`）

| 路径 | 数据来源 | 定位方式 | 变动量 |
| --- | --- | --- | --- |
| `afterPosted`（审核过账） | `ErpPostedLine`（由入库单行构造） | **直接**用 `line.getSrcItemId()`（= 采购单行项ID，下推时写入） | `+qtyChange` |
| `afterReversed`（反审核红冲 / 级联红冲） | **入库单自己的行项**（`t_ctms_stock_in_item.src_item_id`） | ⚠ 红冲行**没有** `srcItemId`（见下），因此改读入库单行项 | `−item.qty` |

**为什么红冲不能用 `afterReversed` 的 `lines` 定位**：T3 的红冲行是**从历史流水反推**的
（`ErpStockJournalServiceImpl` 里 `line.setSrcItemId(null)`），而 `t_ctms_stock_ledger`
表上**没有** `src_item_id` 列（已核对 DDL 与 XML：流水只记物料/仓库/数量/单价/业务类型）。
所以红冲路径从入库单行项读来源标识 —— 入库单不支持部分红冲（T3 的 reverse 冲全部未冲销流水），
"入库单行 ↔ 采购单行"仍是 1:1，回退量就是该行数量。

**四条边界（都在实现里显式处理）**：

1. 手工建的入库单（行上没有 `src_item_id`）→ 跳过，不报错；
2. 找不到对应采购单行（单据被清理）→ 跳过，不让历史数据把整笔过账拖挂；
3. 回退用 `GREATEST(0, received_qty - qty)`（库侧语句），**结构上不可能为负**；
4. 引擎在"没有可冲销流水"时（重复反审核）**不回调**监听器，因此不会重复回退。

---

## 4. 一个必须记录的 DDL 约束（影响 t3 的口径声明）

核对 `sql/二开-进销存.sql` 与真库 `information_schema`：

```
t_ctms_stock_in .warehouse_id   varchar(64) NOT NULL + FK → t_ctms_warehouse(id)
t_ctms_stock_in .warehouse_name varchar(64) NOT NULL DEFAULT ''
（出库单同理；调拨/盘点也各自表头仓库 NOT NULL）
```

⇒ **"入库单草稿允许暂缺仓库、审核阶段再报行号"在真库上不可达**：没有表头仓库的入库单
在 `insert` 时就会被 MySQL 以 1048/1452 拒绝（不是 200 + 草稿）。
因此本组把"缺仓库"的拦截前移：

- 下推路径：解析不出仓库 → **拒绝下推**（可读文案，见 §5 最后一条）；
- 行级回落仍然成立：行项未填仓库 → 用表头仓库（T3 的 `normalizeItems` 实现）；
- T3 的"审核阶段 `行 N：缺少仓库`"守卫仍然存在且被本组用例覆盖（§6 的断言 ④，
  用内存桩直接种入一张缺仓库单据来验证这道守卫本身）。

> 这一条已在 `notes/03-posting.md` §8.2 第 8 条（"POST 无 warehouseId → 草稿成功、审核被拒"）里有冲突：
> 真库上该请求会在**创建**阶段就返回 1048。已报文 captain 裁定（本组不改他人文件）。

---

## 5. 入库侧提示文案（逐字，供 t10/前端断言）

| 场景 | 文案 |
| --- | --- |
| 采购单未审核/未完成即下推 | 仅已审核或已完成的采购单可以下推入库单 |
| 采购单没有收货仓库且下推时未指定 | 采购单未指定默认收货仓库：请先补充采购单的收货仓库，或在下推时指定仓库 |
| 该行已无可入库量 | 已无可入库数量（剩余 0） |
| 超量下推 | 采购单「球阀」可入库数量不足（剩余 10，本次 11） |
| 下推量 ≤ 0 | 下推数量必须大于 0 |
| 下推行项不属于该采购单 | 下推行项不属于该采购单 |
| 下推成功 | 已生成入库单 IN202610000100（草稿），本次合计下推 10 |
| 收货仓库不存在/停用 | 仓库不存在：<id> / 仓库「<名称>」已停用，不能用于新单据（base 守卫文案） |
| 审核时行项与表头都没仓库 | 行 N：缺少仓库：请填写表头仓库或在行项上指定仓库（T3 文案，仍保留） |

---

## 6. 端点与权限点（交 t10 / t8）

| 方法 | 路径 | 权限点 | 说明 |
| --- | --- | --- | --- |
| POST | `/erp/pur/order/{id}/push/stock-in?warehouseId=&remark=` | `pur:order:push` | 下推为草稿入库单；body 可空 `[{srcItemId,qty}]` |

> 入库单自身的 CRUD/审核/反审核端点属 T3：`/stk/in-order/**`（权限 `stk:in-order:*`）。

---

## 7. L1 / L2 证据（真实输出）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 `
  -LockName build -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"
```

| 命令 | 结果 |
| --- | --- |
| `mvn -B -pl ruoyi-ctms test -Dtest=ErpPurchasePushInTest` | **Tests run: 9, Failures: 0, Errors: 0 / BUILD SUCCESS** |
| `mvn -B -pl ruoyi-ctms test`（全模块，**最终证据**） | **Tests run: 510, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**；采购线 **55 条全绿**（`ErpPurchasePushInTest 9/9`、`ErpPurchaseServiceImplTest 25/25`、`ErpPurRulesTest 21/21`） |
| 过程记录 | 首轮 `491/2 失败`（2 条在 `erp.posting.ErpStockDocServiceTest`，盘盈/盘亏 biz_type，属 t3 施工中的文件）；中间几轮被 sales 侧在建文件（`ErpSalesPushServiceImpl`、`ErpSalesOutboundListener`、`ErpSalesPushOutTest`）挡住编译 —— 按简报 §3.1 不替他人改文件，重试到可编译即重跑，最终一轮全绿 |
| `node tools/audit/run-all.js` | `共 10 个审计；失败 0 个；未自证 0 个`（全部 自检 ✅） |
| `mvn -B -pl ruoyi-ctms compile` | BUILD SUCCESS |

**桩单测之外的两项"真库/真框架"校验**（本任务新增代码涉及的）：

| 校验 | 做法 | 结果 |
| --- | --- | --- |
| Mapper 接口 ↔ XML 绑定 | 逐个比对方法名与 statement id | 11/11、9/9、9/9、9/9，**无缺语句/缺方法** |
| DDL 约束（§4 的依据） | 查真库 `information_schema.columns` | `t_ctms_stock_in.warehouse_id` = **NOT NULL**（`is_nullable=NO`）、`warehouse_name` = NOT NULL；`source_doc_type/id/no` 可空 —— 与 §4 的结论一致 |

> L1 增量：t4a 的 46 条 → 本次 +9 条 = 采购线 55 条；全模块 471 → 510，**只增不减**。

单测用例与四条必需断言的对应：

| 用例 | 断言 |
| --- | --- |
| `下推生成草稿入库单且未过账不回写已入库量` | **断言① 未过账不回写**：草稿、来源三列、收货仓库、供应商快照、行 `src_item_id`；`received_qty=0`、结存 0、流水 0 |
| `入库单过账后累加已入库量且重复审核幂等` | **断言② 过账后回写**：`received_qty=10`、结存 10、流水 1 条 `+10`（biz_type=采购入库）、剩余归零；重复审核不重复回写/不重复流水 |
| `反审核红冲后回退已入库量且不为负` | **断言③ 红冲回退且不为负**：`received_qty` 回 0、结存回 0、流水 2 条、状态回待审核、posted 清 0；再显式回退一次仍为 0 |
| `入库单缺仓库时审核被拒且状态与结存均不变` | **断言④**：文案 `行 1：缺少仓库：…`；状态仍 `submitted`、无结存行、无流水 |
| `未审核的采购单不可下推入库单` | 前置状态守卫 |
| `采购单没有收货仓库时下推被拒` | DDL 约束下的正确拦截点（§4） |
| `超量下推入库被拒且已入库量不变` | 超量文案带"剩余 10/本次 11"；不生成入库单、不回写 |
| `部分下推后剩余可入库量按已回写值递减` | 推 4 → 过账 → 剩余 6 → 不填数量全推 6 → 过账后合计 10 |
| `采购线端到端从申请到下推入库单过账` | **§3.7**：申请(2行)→提交→审核→下推采购单(归零即完成)→采购单提交+审核→下推入库单(15)→提交→审核过账 → 结存 10/5、流水 2 条、`received_qty` 合计 15、来源链一致；再反审核 → 结存与已入库量一起回退、流水 4 条、**红冲后可再次全量下推** |

---

## 8. 接口断言清单（HTTP 层，交 T10 复核；L3 统一窗口执行）

前置：后端按 DEV-ENV 打包重启；`tools\oa-login.ps1` 取 token（superAdmin）；
`masterData`：启用物料 `P`（单位 3 位小数）、启用仓库 `W`、启用供应商 `S`。
变量：`R` = 采购申请单，`O` = 采购单，`I` = 入库单。

| # | 请求 | 期望 |
| --- | --- | --- |
| 1 | `POST /erp/pur/request` → submit → approve | 200；`status=approved` |
| 2 | `POST /erp/pur/request/{R}/push/purchase-order?supplierId=S`（body 空） | 200；`data.order.status=draft`、`sourceDocNo`=R 的单号、`autoCompleted=true`（单行推完） |
| 3 | `POST /erp/pur/order` 审核流程：`.../{O}/submit` → `.../{O}/approve`（审批动作用 `PUT /erp/pur/order/{O}/submit` 与 `/approve`） | 200；`status=approved` |
| 4 | `POST /erp/pur/order/{O}/push/stock-in?warehouseId=W` | 200；`data.stockIn.status=draft`、`sourceDocType=purchase_order`、`sourceDocNo`=O 的单号、`warehouseId=W`、`inType=采购入库`、行 `qty`=剩余可入库量、**行 `srcItemId` 非空** |
| 5 | `GET /erp/pur/order/{O}` | 200；`items[0].receivedQty=0`（**未过账不回写**） |
| 6 | `POST /stk/in-order/submit/{I}` → `POST /stk/in-order/approve/{I}` | 200；`status=approved`、`posted=1` |
| 7 | `GET /erp/pur/order/{O}` 与 `GET /stk/stock/...`（T7 的明细接口） | `items[0].receivedQty = 下推量`（**过账后回写**）；结存 = 下推量；流水 1 条 `+下推量`（`biz_type=采购入库`、`doc_type=stock_in`） |
| 8 | 再 `POST /stk/in-order/approve/{I}` | 200（幂等）；`receivedQty` 不变、流水条数不变 |
| 9 | `POST /stk/in-order/unapprove/{I}?reason=录错` | 200；`receivedQty` 回退到 **0**（**红冲回退且不为负**）；结存回 0；流水 2 条（+与−） |
| 10 | 重复 `POST /stk/in-order/unapprove/{I}?reason=重复` | 非 200（状态非已审核）；`receivedQty` 仍 0、流水仍 2 条 |
| 11 | 对没有默认收货仓库的采购单：`POST /erp/pur/order/{O2}/push/stock-in`（不带 warehouseId） | 非 200；`msg=采购单未指定默认收货仓库：请先补充采购单的收货仓库，或在下推时指定仓库` |
| 12 | `POST /erp/pur/order/{O}/push/stock-in` body `[{srcItemId:"行ID",qty:剩余+1}]` | 非 200；`msg` 含 `可入库数量不足（剩余 X，本次 X+1）`；`receivedQty` 不变、不新增入库单 |
| 13 | 未审核的采购单调 `POST .../push/stock-in` | 非 200；`msg=仅已审核或已完成的采购单可以下推入库单` |
| 14 | 无 `pur:order:push` 权限的账号调第 4 条 | 403（`authz-check.ps1` 的 IsForbidden 形态） |
| 15 | （如实现方调整了 T3 的仓库守卫）`POST /stk/in-order` 不带 `warehouseId` | **预期为 500/业务异常**（DB 1048），而不是"草稿成功"——见 §4；若 T3 改为应用层拦截，则以 T3 的新文案为准 |
| 16 | 全链路一致性（可选，配合 T7）：`received_qty` 合计 = 该采购单已过账入库单行数量之和 | 成立 |

---

## 9. §3.7 端到端（组内已跑通，与 T11 的浏览器用例同构）

```
新增采购申请单（两行） → 提交 → 审核
      ↓  push/purchase-order（选供应商）
采购单（草稿，来源=申请单，自动带过合同/部门/需求日期） → 提交 → 审核
      ↓  push/stock-in（带收货仓库）
入库单（草稿，来源=采购单，行带 src_item_id）
      ↓  提交 → 审核（审核即过账）
结存 +Σqty、流水 N 条、采购单行 received_qty 回写      ← 组内单测断言点
      ↓  反审核（红冲）
结存回退、received_qty 回退（≥0）、流水 +N 条（红冲）
```

组内以内存桩跑通（`ErpPurchasePushInTest.采购线端到端从申请到下推入库单过账`）；
浏览器 E2E（T11）按 §8 的 1~10 条逐步操作即可，页面上要能观察到：
申请单状态、采购单的"已入库量/剩余可入库量"、入库单的"已过账"标记与来源单号。

---

## 10. 已知边界与待复核项

1. **`remainQtySum` 语义**（§2 第 7 步）：下推不写 `received_qty`，所以返回的是"减去本次下推后的余量"，
   与库内剩余量在过账前不一致。若前端希望"库内剩余量"本身，改一行即可（报文我）。
2. **红冲的定位方式**（§3）：依赖"入库单不支持部分红冲"这一前提（T3 的实现如此）。
   若将来支持部分红冲，红冲回写必须改为"按流水行携带的 `src_item_id`"，
   前提是 `t_ctms_stock_ledger` 加一列 `src_item_id`（DDL 变更，需 captain 裁定）。
3. **§4 的 DDL 冲突**：T3 的 `notes/03-posting.md` §8.2 第 8 条在真库上走不到它写的期望路径，
   已报文 captain；本组不改他人文件与 DDL。
4. **采购单状态与"可下推"的关系**：已完成的采购单也可下推入库单（规格口径）。
   列表返回的 `canReceive`（t4a 已实现）已表达"还有剩余可入库量"。
5. **不做的事**：不新增 `tools/*` 脚本（简报 §6：改共享文件先报文 captain）；
   T10 可按 §8 落成 `tools/erp-procurement-check.ps1`。
6. **测试夹具重复**：`ErpPurchasePushInTest.PurStub` 与 `ErpPurchaseServiceImplTest` 内的桩同源，
   为避免大改已绿用例而各自保留一份；建议后续由 base/t3 统一提供测试夹具（已记入本文件，供 captain 决策）。
7. **同一采购单行被下推两次（两张草稿入库单）**：两张草稿都可以创建（下推只校验"当前剩余"），
   但**先过账的那张**会把 `received_qty` 回写掉，第二张过账时会被
   `applyReceivedQtyChange` 的"不得超过剩余可入库量"拦下（文案：「已入库数量超出剩余可入库量（剩余 X，本次 Y）」）。
   这是刻意的**防超收**兜底，不是缺陷；若前端希望"下推时就把未过账的草稿量算进剩余"，
   属于新的口径（需要把"草稿占用量"纳入计算），请报文 captain 再定。
8. **前端下推对话框必须让用户选收货仓库**：申请→采购单不会带仓库，因此由申请单链条生成的采购单
   通常没有默认收货仓库，`push/stock-in` 不带 `warehouseId` 会被拒绝（§4）。T11/T12 请把
   仓库下拉放进下推对话框（或先在采购单里设置默认收货仓库）。
