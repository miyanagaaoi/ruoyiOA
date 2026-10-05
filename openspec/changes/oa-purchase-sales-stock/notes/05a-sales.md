# 05a · B4 销售线（上）交付记录

> 任务：`t5 [t5a-sales-core]`（tasks.md §4.1 / §4.2 / §4.4 的销售线部分）
> 追加：`t28`（repair：补齐 `:export` 端点，审计 F1 blocker）—— 见文末 §10
> 交付人：backend-sales｜更新时间：2026-10-05
> 范围：销售申请单 / 销售订单 CRUD + 申请→订单下推 + 归零即完成 + 关联合同。
> **不含**：销售订单 → 出库单下推与 `shipped_qty` 回写（tasks.md §4.3，属 `t5b-sales-push-out`）。
> 真源：`openspec/changes/oa-purchase-sales-stock/specs/erp/sales/spec.md`、`tasks.md` §4、简报 §9 冻结表。

---

## 1. 落点（文件清单）

### 1.1 后端（`ruoyi-vue-oa-master/ruoyi-ctms`）

| 文件 | 承载 |
| --- | --- |
| `src/main/java/com/ruoyi/ctms/erp/sales/ErpSalRules.java` | **纯规则**（不依赖 Spring/DB/登录上下文）：行项守卫、剩余量、超量文案、归零判据、合同方向、客户启停用、排他性 |
| `.../erp/sales/domain/ErpSalesRequest.java` | 销售申请单表头（32 列，含 `customer_id` 可空 + `customer_name_text` 文本兜底） |
| `.../erp/sales/domain/ErpSalesRequestItem.java` | 申请行项（`ErpDocItem` + `ordered_qty`，含 `remainingQty()` / `isFullyOrdered()`） |
| `.../erp/sales/domain/ErpSalesOrder.java` | 销售订单表头（38 列，含 `total_amount` 与发货信息 5 列） |
| `.../erp/sales/domain/ErpSalesOrderItem.java` | 订单行项（`ErpDocItem` + `shipped_qty`，含 `addShippedQty()` / `subtractShippedQty()`） |
| `.../erp/sales/domain/ErpSalesPushLine.java` / `ErpSalesPushRequest.java` | 下推入参（按 `docItemId` → `productId` → `index` → 数组顺序匹配来源行） |
| `.../erp/sales/mapper/ErpSalesRequestMapper.java` + `.../mapper/erp/ErpSalRequestMapper.xml` | 申请单表头（列表筛选、默认排除已作废、取最大单号、行项计数） |
| `.../erp/sales/mapper/ErpSalesRequestItemMapper.java` + `ErpSalRequestItemMapper.xml` | 申请行项（`updateOrderedQty` 累加、`batchUpsertItems` 编辑不重建行ID） |
| `.../erp/sales/mapper/ErpSalesOrderMapper.java` + `ErpSalOrderMapper.xml` | 订单表头（含 `selectOrdersBySourceDocId` 供 t5b 用） |
| `.../erp/sales/mapper/ErpSalesOrderItemMapper.java` + `ErpSalOrderItemMapper.xml` | 订单行项（`addShippedQty` / `subtractShippedQty` 供 t5b 用，红冲 `greatest(0, …)`） |
| `.../erp/sales/service/IErpSalesRequestService.java` / `IErpSalesOrderService.java` / `IErpSalesPushService.java` | 服务契约 |
| `.../erp/sales/service/impl/ErpSalesRequestServiceImpl.java` | 申请单 CRUD + 6 个状态动作 + 关联合同 |
| `.../erp/sales/service/impl/ErpSalesOrderServiceImpl.java` | 订单 CRUD + 6 个状态动作 + 关联合同 + 客户/发货信息 |
| `.../erp/sales/service/impl/ErpSalesPushServiceImpl.java` | **三段式下推** + 归零即完成 |
| `.../erp/sales/support/ErpSalChangeLogWriter.java` | 变更历史写入（复用 B3 的 `t_ctms_change_log`，与采购线同构） |
| `.../erp/sales/controller/ErpSalesController.java` | `/sal/request/**`、`/sal/order/**` |

### 1.2 单测

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `src/test/java/com/ruoyi/ctms/erp/sales/ErpSalRulesTest.java` | 20 | 纯规则：剩余量、超量文案、归零判据、合同方向、客户启停用、行项守卫、已出库回退 |
| `.../erp/sales/service/impl/ErpSalesServiceImplTest.java` | 21 | 服务：CRUD 守卫、客户引用、下推与归零、关联合同（**内存桩 Mapper，不起 Spring**） |

### 1.3 复用的公共层（**只消费，不复刻** —— 简报 §3.8）

| 公共层 | 本线用它做什么 |
| --- | --- |
| `ErpDocStateMachine.check / checkAndNext / checkEditable` | 6 个状态动作的**唯一**判定点；服务里没有 `if (status.equals(...))` |
| `ErpDocStatus` / `ErpDocType` / `ErpDocAction` | 状态常量、单据码 `sales_request`/`sales_order`、动作枚举 |
| `ErpAmounts`（`lineAmount`/`totalOf`/`roundQty`/`checkQuantityScale`） | 行金额与合计的**唯一**乘法与舍入口径（先 HALF_UP 到 2 位再求和） |
| `ErpMasterGuards.applyItemSnapshot` | 行项守卫（物料/单位必须启用）+ 快照写入 + 数量精度校验 |
| `ErpDocScope.buildDataScopeSql` | 列表的数据范围片段（服务端白名单拼接，别名固定 `d`） |
| `CtmsChangeLogMapper`（B3 交付） | 变更历史（本线**不新增表、不改 base 包**） |

---

## 2. 状态流转（与采购线、8 类单据完全一致）

```
[*] ──新增──▶ draft(草稿)
draft      ──提交(要求至少 1 行行项)──▶ submitted(待审核)
submitted  ──驳回(原因必填)──────────▶ draft
submitted  ──审核────────────────────▶ approved(已审核)
draft      ──作废(原因必填)──────────▶ voided(已作废)   ← 此后列表默认不显示
submitted  ──作废(原因必填)──────────▶ voided
approved   ──反审核(原因必填)────────▶ submitted
completed  ──反审核(原因必填)────────▶ draft
approved   ──置为已完成(可手工)──────▶ completed
approved   ──下推后全部行项剩余量为 0─▶ completed（**自动**，见 §4）
```

**不可编辑/不可删除的边界**：只有 `draft` 可编辑与可删除；其它状态返回业务异常
（"当前状态（待审核）不可编辑销售申请单，请先反审核或作废" / "…不可删除…"）。

**销售申请单与销售订单都是非库存类**，因此 `approved` 不是终态，`completed` 可用
（库存类单据不提供"置为已完成"，由公共状态机显式拒绝）。

---

## 3. 提示文案（接口契约的一部分，改动即破坏性变更）

| 场景 | 提示（`e.getMessage()` 逐字） |
| --- | --- |
| 提交时没有行项 | `单据没有行项，不能提交` |
| 行项数量 ≤ 0 | `行 1：数量必须大于 0` |
| 行项单价为负 | `行 1：单价不能为负数` |
| 数量超出单位小数位 | `行 1：数量的最小单位是 0 位小数，当前为 1 位` |
| 物料已停用 | `行 1：物料「螺丝」已停用，不能用于新单据` |
| 未选客户 | `销售订单必须选择客户档案` |
| 客户档案不存在 | `客户档案不存在：CUST-X` |
| 供应商ID传到客户字段 | `客户档案不存在：SUP-1（该标识属于供应商档案，销售订单只能引用客户）` |
| 客户已停用 | `客户「停用客户」已停用，不能用于新销售订单` |
| 未审核就下推 | `仅已审核的销售申请单可以下推销售订单` |
| 下推数量 ≤ 0 | `下推数量必须大于 0` / `行 1：下推数量必须大于 0` |
| **超量下推** | `「螺丝」可下推数量不足（剩余 50，本次 60）` |
| 剩余量为 0 还下推 | `已无可下推数量（剩余 0）` / `已无可下推数量（全部行项剩余量为 0）` |
| 同一来源行被重复下推 | `行 2：「螺丝」被重复下推` |
| 找不到对应的申请行 | `行 1：找不到对应的销售申请行` |
| 关联不存在的合同 | `关联合同不存在或已停用` |
| **关联采购方向合同** | `只能关联销售方向的合同（当前合同方向：采购/支出）` |
| 无登录上下文建单 | `无法确定创建人：请先登录后再创建单据` |
| 未分配部门建单 | `当前账号未分配部门，无法创建单据（归属部门在创建时快照，数据范围判定依赖它）` |
| 驳回/作废/反审核未填原因 | `驳回原因必填` / `作废原因必填` / `反审核原因必填` |
| **单号服务未接入**（见 §7 已知边界） | `销售申请单号生成器尚未接入平台编号服务（base.ErpDocNoGenerator，由 backend-base 提供；t_code_config 种子 9F2C…C103）` |

---

## 4. 下推步骤（销售申请 → 销售订单，tasks.md §4.2）

**三段式，全在一个事务里；任一段失败整体不写入。**

1. **加载 + 守卫 + 规划（零写入）**
   - 来源单必须 `status = approved`，否则 `仅已审核的销售申请单可以下推销售订单`；
   - 取来源行项（为空 →`销售申请单没有行项，无法下推`）；
   - 逐行匹配来源行（`docItemId` → `productId` → `index` → 数组顺序），
     解析本次量（**不填 = 按剩余量全推**），校验 `0 < 本次量 ≤ 剩余量`；
   - 剩余量 = `qty − ordered_qty`（行项自己的两个列之差，唯一实现点）。
2. **生成草稿销售订单**
   - 委托 `IErpSalesOrderService.insertSalesOrder`（单号、客户守卫、行项快照、金额口径都复用，
     不另写一份）；
   - 写 `source_doc_type=sales_request` / `source_doc_id` / `source_doc_no`；
   - 行项写 `src_item_id`（来源申请行ID），并带过合同、销售部门、经办人、
     期望交货日期 → `delivery_date`；
   - 默认备注 `由销售申请单 SR202610000001 下推生成`。
3. **即时累加 + 归零即完成**
   - `update t_ctms_sales_request_item set ordered_qty = ordered_qty + 本次量`
     （SQL 累加，并发下推不丢累加；**注意：不要在 Java 内存对象上再累加一次，否则变成 +2×本次量**）；
   - 调 `checkAutoComplete`：全部行项剩余量为 0 → 状态置 `completed`，
     并写一条 `field_name=status`、`source=auto`、
     note=`全部行项剩余可下推量为 0，自动置为已完成` 的变更历史；
   - **幂等**：已经是 `completed` 直接返回 `false`，不重复留痕。

**接口**：`POST /sal/request/{id}/push`（权限 `sal:request:push`）
返回 `{orderId, orderNo, sourceDocId, sourceDocNo, pushedQtySum, requestCompleted, lines[]}`，
`lines[]` 每项含 `srcItemId`/`productId`/`productName`/`qty`/`remainingQty`。

---

## 5. 接口断言清单（交 t10 复核；L3 统一窗口）

> 全部按 `POST/PUT/GET /sal/...`，配额见 §6；错误一律为业务异常（响应体 `msg` 即上表文案）。
> **前置数据**：一个启用客户、一个停用客户、一个供应商、一份销售方向合同、一份采购方向合同、
> 一个启用物料（单位小数位 0）。若库里没有可用客户档案，先走 `/ctms/customer` 建一个。

### 5.1 销售申请单（tasks.md §4.1）

| # | 请求 | 期望 |
| --- | --- | --- |
| A1 | `POST /sal/request`（无 items） | 200 但 `msg=单据没有行项，不能提交`（提交阶段）或直接保存草稿成功 → 再 `PUT /{id}/submit` 报该 msg |
| A2 | `POST /sal/request` 行数量 `0` | 拒绝，`msg` 含 `数量必须大于 0` |
| A3 | `POST /sal/request` 单位 0 位小数的物料 + 数量 `1.5` | 拒绝，`msg` 含 `行 1：` 与 `0 位小数` |
| A4 | `POST /sal/request` 行数量 `100`、单价 `0.125` ×3 行（改 3 行同款） | 各行 `amount=0.13`；申请单**无** `total_amount` 列（合计由订单侧验） |
| A5 | `POST /sal/request` 用已停用物料 | 拒绝，`msg` 含 `已停用` 与行号 |
| A6 | `GET /sal/request/list` | 分页；默认不含 `voided`；`includeVoided=1` 才含 |
| A7 | `PUT /sal/request` 对非草稿单据 | 拒绝，`msg` 含 `不可编辑` |

### 5.2 销售订单（tasks.md §4.1，采购线 §3.2 的镜像）

| # | 请求 | 期望 |
| --- | --- | --- |
| B1 | `POST /sal/order` 把**供应商 id** 传给 `customerId` | 拒绝，`msg` 含 `客户档案不存在` 与 `供应商档案` |
| B2 | `POST /sal/order` 引用**停用客户** | 拒绝，`msg` 含 `已停用`；`SELECT count(*)` 确认未落库 |
| B3 | `POST /sal/order` 三行 `数量 1 × 单价 0.125` | 各行 `amount=0.13`、`totalAmount=0.39`（**不是 0.38**） |
| B4 | `POST /sal/order` 带 `deliveryDate/deliveryAddress/contactName/contactPhone/shipWarehouseId` | 全部落库；`customerName` 为名称快照 |
| B5 | `GET /sal/order/{id}` | 返回行项与变更历史；物料改名后仍显示**旧名**（快照） |

### 5.3 下推与归零（tasks.md §4.2）

| # | 请求 | 期望 |
| --- | --- | --- |
| C1 | 对 `draft` 申请单 `POST /sal/request/{id}/push` | 拒绝，`msg=仅已审核的销售申请单可以下推销售订单` |
| C2 | 申请行 `qty=80`、`ordered_qty=30`，本次推 `60` | 拒绝，`msg` 含 `剩余 50` 与 `本次 60`；**再查 `ordered_qty` 仍为 30** |
| C3 | 同上，本次不填数量（全推） | 成功；新订单为 `draft`、`sourceDocNo` 正确、行 `src_item_id` 非空；`ordered_qty=80` |
| C4 | 申请单两行，只推完一行 | 状态仍为 `approved`，变更历史 0 条 |
| C5 | 两行都推完 | 状态 `completed`，变更历史 +1 条（`status` / `approved→completed` / `auto`） |
| C6 | 对已 `completed` 的申请单再 `POST push` | 拒绝（`仅已审核的销售申请单可以下推销售订单`），变更历史条数不变 |
| C7 | 直接重放 C5 的 `checkAutoComplete` 语义（再触发一次归零判定） | 状态与变更历史都不变（幂等） |

### 5.4 关联合同（tasks.md §4.4）

| # | 请求 | 期望 |
| --- | --- | --- |
| D1 | `PUT /sal/order/{id}/contract` 传**采购方向**合同 id | 拒绝，`msg` 含 `只能关联销售方向的合同`；单据 `contractId` 仍为空 |
| D2 | 传**销售方向**合同 id | 成功；`contractNo` 写入快照；**再查合同表，`update_time`/字段全未变**（不回写） |
| D3 | 传已停用合同 id | 拒绝，`msg=关联合同不存在或已停用` |
| D4 | 传空 `contractId` | 成功；`contractId`/`contractNo` 清空，变更历史 +1 条（`关联合同`） |
| D5 | 对非草稿单据关联 | 拒绝，`msg` 含 `不可编辑` |

---

## 6. 权限点（简报 §9.2 冻结表，**不得自行改名**）

| 资源 | 权限点 |
| --- | --- |
| 销售申请单 | `sal:request:list` / `:query` / `:add` / `:edit` / `:remove` / `:status` / `:submit` / `:approve` / `:unapprove` / `:void` / `:push` / `:export` / `:print` |
| 销售订单 | `sal:order:<同上动作集>` |

- **驳回与审核共用 `approve`**（冻结表口径）；
- 置为已完成用 `status`（与 B3 合同的 `ctms:contract:status` 同款动作名）；
- 下推用 `push`、导出用 `export`、打印用 `print`；
- `sys_menu` 增量由 `t8-perm-scope`（backend-ledger）按冻结表生成，本线**不写**菜单 SQL。
- ⚠ 铁律：**改过 `@PreAuthorize` 后必须重新打包**（本线新增了 26 个 `@PreAuthorize`）。

---

## 7. 已知边界与待办（不静默降级）

1. **单号取号（§9.1）**：本线严格不留自算分支。取号入口是
   `ErpSalesRequestServiceImpl.nextRequestDocNo(ErpSalesRequest)` /
   `ErpSalesOrderServiceImpl.nextOrderDocNo(ErpSalesOrder)`，优先级：
   ① 单测的 `DocNoPort` 嵌套接口（不起 Spring、不连库）→
   ② 平台公共实现 `com.ruoyi.ctms.erp.base.ErpDocNoGenerator.nextDocNo(ErpDocType, 单据日期)`
   （captain 2026-10-05 裁决：接线归本线；参考日期传**单据日期**，补录上月单落上月号段）→
   ③ 两者都没有时**明确报错**（`编号服务未装配，无法为销售申请单取号`）。
   配置 id：销售申请单 `9F2C…C103`（前缀 `SR`）、销售订单 `9F2C…C104`（前缀 `SO`），
   形态 `{前缀}{yyyyMM}{6 位序号}`、按月重置（`sql/二开-进销存.sql` 的 `t_code_config` 8 行种子）。
   **没有任何"前缀 + 库内最大号 +1"的兜底分支。**
2. **不可删行项**：编辑（`PUT`）用于 upsert（按行ID命中则更新、未命中则插入），
   **不删**未提交的行项。刻意如此：删除重建会把下推累加过的 `ordered_qty`
   （以及 t5b 的 `shipped_qty`）清零，等于放开超量下推。前端如需删行，
   走"该行数量置 0"会被守卫拒绝 —— 需在 t5b 或前端任务里补一个显式删行端点（已登记，属待办）。
3. **上游守卫**：`unapproveSalesRequest` 目前**只改本单据状态**（见
   `autoVoidDownstreamOrders` 的空实现与注释）。"存在未作废下游销售订单时拒绝反审核"
   （参考仓库 `assert_no_downstream`）依赖 t5b 的下游链路，**在 t5b 落地**。
4. **不接 Flowable**：销售单据是单级状态机（design D4 / Q-B7），不进 OA 待办。
5. **行级发货仓库**：订单行可填 `warehouseId`（写仓库名快照）；未填时由 t5b 的出库单
   回落表头 `ship_warehouse_id`（`ErpMasterGuards.resolveWarehouseId`）。
6. **数据范围**：列表已接入 `ErpDocScope.buildDataScopeSql`（别名 `d`）；
   按标识直查的 403 判定与四档实测属 `t8-perm-scope`，本线不重复实现。
7. **列表派生列（与采购侧对称，已落地）**：`remainQtySum` / `canPush`（申请单另有 `totalAmount`）。
   口径：`remainQtySum = Σ max(0, 剩余量)`（申请侧 `qty − ordered_qty`，订单侧 `qty − shipped_qty`），
   `canPush = remainQtySum > 0`（**不含状态门槛** —— "仅已审核可推"由下推接口再校验一次；
   否则"归零即完成"后剩余量为 0 与状态 `completed` 会互相掩盖）。
   实现：列表返回前现算，每页**只查一次**行项（`selectItemsByDocIds` + `@MapKey("docId")`，避免 N+1）。
   前端 `doc-kinds.js` 已按 `remainQtySum` 控制销售侧"下推"显隐。

### 7.1 前端契约对照（已核对，本次不改前端）

- 端点前缀一致：前端 `doc-kinds.js` 的 `restBase` 是 `/sal/request` 与 `/sal/order`，
  与本线控制器 `@RequestMapping("/sal")` + `/request/**`、`/order/**` 逐字对齐。
- 动作端点形状一致：销售侧是 `put-id-action`（`PUT {base}/{id}/{action}`，原因放**请求体** `{reason}`），
  与本线 `submit/approve/reject/void/unapprove/complete` 逐条对齐（`reject` 无 query 覆盖）。
- 下推端点一致：`POST /sal/request/{id}/push`，body `{lines:[{docItemId, qty, unitPrice, remark}], …}`。
- `changeLogs: false`（前端已隐藏销售侧变更历史入口）⇒ 本线详情仍返回 `changeLogs`，
  属"多返回不报错"，无需改前端。

---

## 8. 验证记录（L1 / L2，真实输出）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| L1（本线） | `mvn -B -pl ruoyi-ctms test -Dtest=ErpSal*Test`（`-LockName build`） | **`Tests run: 42, Failures: 0, Errors: 0`**（`ErpSalRulesTest` 20 + `ErpSalesServiceImplTest` 22） |
| L1（全模块回归） | `mvn -B -pl ruoyi-ctms test`（`-LockName build`） | 本线交付时刻：**`Tests run: 482, Failures: 5, Errors: 1`**，其中 5F/1E **全部集中在 `erp.procurement.ErpPurchasePushInTest`（t7b 在飞文件，非本线）**；本线两个类 `Failures: 0, Errors: 0`。此前一次性全绿快照亦见过 `BUILD SUCCESS`（共 24 个测试类逐类 0F/0E）。 |
| L2 | `node tools/audit/run-all.js` | **10 个审计全 OK，失败 0，未自证 0** |
| 精度自检 | `grep -rn "\bdouble\b\|\bfloat\b" erp/sales` | **0 命中**（金额/数量全部 `BigDecimal`，符合 `REQ-NFR-007`） |

### 8.1 本次修复的真实缺陷（不粉饰）

**缺陷 1（本次唯一的功能性 bug，已修）**：`requireSaleContract`（销售申请/销售订单两处）把
`t_ctms_contract.del_flag` 判成"未删除 = 已停用"：
```java
// 错：ENABLE_NO = "0"，而 del_flag='0' 表示**未删除**
if (ErpSalRules.ENABLE_NO.equals(ErpSalRules.trim(contract.getDelFlag()))) { throw ... }
```
后果：**未停用的合同也被判成"已停用"**，关联合同整条链不可用（4 条用例红）。
修法：`ErpSalRules` 新增 `DEL_FLAG_DELETED = "1"`，两处改为 `DEL_FLAG_DELETED.equals(delFlag)`；
`disabledContractIsRejected`（真 `del_flag='1'`）仍按预期被拒 —— **不是放宽断言，是把判定写对**。

**缺陷 2（已修）**：`contractTypeLabelOf` 对 `dict_value='SAL'` 直接硬编码返回 `"销售"`，
丢失了字典标签的完整形态。改为统一走 `lookupContractLabel(dictValue)`（`dict_value → dict_label`），
未命中时回落"原值当标签"，字典不可用时**不静默放行**。

**过程事故（如实记录）**：改多文件时把中间态挂在共享树上，导致全模块 `main`/`test-compile`
一度红（`ErpSalesRequest.java` 括号结构、`ErpSalesRequestServiceImpl.java` 缺 `ErpAmounts` 导入），
被 captain 与 stockops-integrator 先后抓到；已改为"改一批 → `test-compile` → 再下一批"。

---

## 10. 追加：`:export` 端点（`t28` repair，审计 F1 blocker）

> 背景：前端 `DocListShell.vue` 已有「导出」按钮、菜单 SQL 已有 `sal:request:export` /
> `sal:order:export` 两行 F 权限，但后端两类单据**没有导出端点** ⇒ AC-78（金额四处一致）
> 与 AC-80（权限点与接口一一对应）的核对必然红。

### 10.1 端点的最终形态（**先读前端再定**，与 `src/utils/request.js` 的 `download()` 对齐）

前端 `doc.js` 的 `exportDocs()` → `download(kind.restBase + '/export', query, filename)`，而
`download()` 的实现是：

```js
service.post(url, params, {                       // ① POST
  transformRequest: [(p) => tansParams(p)],       // ② 表单体（x-www-form-urlencoded）
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  responseType: 'blob'                            // ③ 期望二进制
}).then(async (data) => {
  const isBlob = blobValidate(data)               // ④ data.type !== 'application/json'
  if (isBlob) { saveAs(new Blob([data]), filename) }        // 成功：存文件
  else { const rspObj = JSON.parse(await data.text()); Message.error(rspObj.msg || ...) }  // 失败：弹 msg
})
```

据此定下端点形态（**与 B3 `CtmsContractController#export`、T3 `ErpStockInController#export` 逐条同款**）：

| 维度 | 取值 | 理由 |
| --- | --- | --- |
| 方法 | `@RequestMapping(value="…/export", method={GET, POST})` | 前端走 POST，同时兼容 GET 便于接口脚本/运维直取 |
| 参数绑定 | `HttpServletResponse response, <Query> query`（**普通命令对象**，不是 `@RequestBody`） | POST 表单体与 GET query string 都能绑定同一组筛选（`tansParams` 就是把它拍平成键值对） |
| 成功响应 | `ExcelUtil<ErpSalesExportRow>.exportExcel(response, rows, sheetName)` | 真实 xlsx 流；`Content-Type` 非 `application/json` ⇒ 前端 `blobValidate` 判为文件并 `saveAs` |
| 失败响应 | 服务层/校验器抛 `ServiceException(msg, 403)` → 全局处理器返回 `{"code":403,…}`（**`Content-Type: application/json`**） | 前端走 `else` 分支弹 `msg`，不下载空文件；HTTP 仍 200（与 B3 `authz-check.ps1` 的 `IsForbidden` 同形态） |
| 权限点 | `sal:request:export` / `sal:order:export` | 与 `sql/二开-进销存-菜单.sql:339` / `:363` 的 F 行**逐字一致**（已 grep 核对） |

> ⚠ **交 frontend-erp（请 captain 转告）**：**前端不需要改任何代码**。
> `exportDocs()` 已经发 `POST {restBase}/export` + form 体 + `responseType:'blob'`，
> 这正是本端点的形态；`doc.js` 头部注释里写的 `POST {restBase}/export 导出当前筛选（ExcelUtil 写二进制流）`
> 也已经与实现一致。**唯一要注意的**：不要改成 `GET` 或 JSON（`.rows`）解析 ——
> 后端返回的是 xlsx 流，按 JSON 解析会得到"下载文件出现错误"（B3 有同类前车之鉴）。
> 传参：`query` 里放与列表相同的筛选字段即可；另有可选的 `ids`（逗号分隔）用于"导出选中"。

### 10.2 导出列清单（`ErpSalesExportRow`，一文档 × 一行项）

**为什么是扁平行**：验收要求列里同时有"表头业务字段 + 客户快照 + **行项关键列**"，
而 `ExcelUtil` 是单表模型（一个 `@Excel` 类 = 一张 sheet），所以表头列在每行重复、行项列平行展开
（ERP 里"订单 + 明细"导出的通行形态）；单据级合计列每行重复，便于在 Excel 里直接对列求和。
**行金额取行项已固化的 `amount`，本层不做任何计算** —— 这样"导出金额"与"列表金额"不可能分叉。

| 分组 | 列 |
| --- | --- |
| 表头 | 单据类型 / 单号 / 单据日期(`yyyy-MM-dd`) / 状态(中文) / 经办人 / 归属部门ID / 关联合同 / 来源单号 / 交货日期 / 备注 |
| 客户 | 客户ID / 客户（订单取 `customer_name` **快照**；申请单取 `customer_name_text` 文本兜底） |
| 发货（订单专有） | 收货地址 / 联系人 / 联系电话 / 发货仓库ID |
| 派生 | 剩余可下推量（申请侧 `Σ max(0, qty−ordered_qty)`；订单侧 `Σ max(0, qty−shipped_qty)`）/ 单据金额合计 |
| 行项 | 行号 / 物料编码 / 物料名称 / 规格 / 单位 / 数量 / 单价 / 行金额 / 行备注 |

无行项的单据仍出一行（只填表头列），避免"草稿单据在导出里凭空消失"。

### 10.3 三条口径怎么证的（`ErpSalesExportTest` 14 条）

> **repair-round-2（t40）追加两条**（见 §11），本表同步为 14 条。

| 口径 | 用例 | 证法 |
| --- | --- | --- |
| **筛选传递** | `requestExportPassesListFiltersThrough` / `orderExportPassesListFiltersThrough` / `exportWithNullQueryStillGoesThroughTheListPath` | `assertSame(query, capturedRequestQuery)`：服务收到的**就是调用方给的那个查询对象**，且 `status/keyword/beginDocDate/endDocDate/includeVoided/customerId` 逐字段原样可见；同时"服务方法被调用过"即证明导出走的是**列表那条取数路径**，控制器不做第二次过滤 |
| **范围裁剪** | `exportScopeConditionEqualsListScopeCondition` / `exportDoesNotOverwriteScopeConditionPassedIn` | 用**真实** `ErpSalesRequestServiceImpl`（只有它会调 `ErpDocScope`）+ 捕获 `dataScopeSql` 的桩 Mapper：列表调用与导出调用写进查询的片段**逐字相同**，且等于 `ErpDocScope.buildDataScopeSql(DEFAULT_ALIAS)` 在当前上下文的结果（单测无登录上下文 ⇒ 约定为"不加条件"即 null，两者仍相等；真实请求下两者会是非空片段，同源）；另一条断言控制器**不改写**调用方传入的片段 |
| **金额一致** | `amountRoundingIsIdenticalToTheList` | 3 行 `1 × 0.125`：每行 `0.13`、合计 `0.39`（并断言**不等于**先汇总再舍入的 `0.38`）；导出合计列 == 列表的服务层 `totalAmount` |
| **403** | `exportRequestByIdRejectsOutOfScopeDocumentWith403` / `exportOrderByIdRejectsOutOfScopeDocumentWith403` | 范围外 id → `ServiceException.getCode()==403` 且**未读取单据详情**（不返回数据）；校验走公共层 `IErpDocObjectAccess.checkObjectAccess`（不另写一套范围） |
| **fail closed** | `exportByIdFailsClosedWhenAccessCheckerMissing` | 校验器未装配时**拒绝导出**，绝不静默放行 |
| **列清单** | `orderExportCarriesHeaderCustomerSnapshotAndItemColumns` / `requestExportKeepsOneRowPerItemAndFallsBackToCustomerText` | 逐列断言表头业务字段、客户快照、行项关键列；并断言"一文档 × 一行项"与表头列重复 |

### 10.4 本组验证（真实输出）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| L1（本组，t40 后） | `mvn -B -pl ruoyi-ctms test -Dtest=ErpSalesExportTest` | **`Tests run: 14, Failures: 0, Errors: 0`**（12 + t40 新增 2） |
| L1（全模块，只增不减） | `mvn -B -pl ruoyi-ctms test` | **`Tests run: 541, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`**（t8 交付时 510 → t28 时 531 → t40 时 541） |
| L2 | `node tools/audit/run-all.js` | **10 个审计失败 0、未自证 0** |
| 精度 | `ErpPrecisionTest`（T1 的源码级断言，会遍历 `erp` 下所有类） | **4/4 绿**（新增 `ErpSalesExportRow` 被正确加载） |

### 10.5 过程记录（如实）

本轮我先把"接口先加、实现后补"的中间态挂到了共享树上（`IErpSalesOrderService.selectSalesOrderItemsByDocIds`
与控制器新类型的 import 未齐），导致 backend-posting 第三次抓到模块级编译失败；每次都在 10~20 秒内
`-DskipTests compile` 自证并回消息。**根因是我写完新类型后没有立刻 compile**（漏了 12 个 import）。
已按"每落一批即 compile"执行，本条同时说明：`ErpPrecisionTest` 报的
`ClassNotFoundException: …ErpSalesExportRow` 是**编译失败的连带现象**（该测试会 `Class.forName` 遍历
`erp` 下所有类），编译恢复后即绿，不是类名/filename 不匹配。

---

## 11. 追加：t40（repair-round-2）—— 把"真 xlsx 读回"与"端点契约"补进回归

> 背景：t36 的观察项 L1 指出 t28 的导出证据只有"导出行对象"层，缺两样：
> ① **真产物的读回**（`@Excel` 注解写错时行对象仍是对的，只有 xlsx 会露馅）；
> ② **端点声明的逐字断言**（权限点/方法/路径/参数绑定一旦漂移，接口脚本与前端会静默错位）。
> 本轮照采购侧 `ErpPurExportTest` 的同款手法补齐，并额外覆盖两条**授权跨包**测试改动（D-19 / O1，见 §11.5）；
> **产品代码零净改动**：反向对照期间**临时触碰**的产品文件共两处
> （`erp/sales/domain/vo/ErpSalesExportRow.java` 的 `@Excel` 列名、`resources/mapper/erp/ErpStockOutMapper.xml`
> 的状态过滤），两处均已还原并逐条 grep 核对（见 §11.4 与 §11.7 的"临时触碰清单 + 还原验证"）。

### 11.1 `amountRoundingIsIdenticalInTheRealXlsx`（真 xlsx 读回）

链路：`controller.exportOrderRows(...)` 取 3 行 `1 × 0.125` 的导出行 →
`new ExcelUtil<>(ErpSalesExportRow.class).exportExcel(fakeResponse(buffer), rows, "销售申请单")`
（`fakeResponse` 用 JDK 动态代理伪造只支持 `getOutputStream()` 的响应，把产物接进 `ByteArrayOutputStream`）→
`new XSSFWorkbook(new ByteArrayInputStream(buffer.toByteArray()))` 读回逐格断言：

| 断言 | 值 |
| --- | --- |
| 表头 + 3 行 | `sheet.getLastRowNum() + 1 == 4` |
| `行金额` 列三行 | 每行 `0.13` |
| `单据金额合计` 列三行 | 每行 `0.39`，且**逐行等于**列表的 `doc.getTotalAmount()` |
| 三行 `行金额` 单元格之和 | `0.39`（先舍入再汇总，不是 `0.375 → 0.38`） |
| 表头列名 | 含 27 个关键列（单据类型/单号/…/单据金额合计/…/行金额/行备注），防 `@Excel` 注解回归 |
| 字节流 | 非空（真的产出了 xlsx） |

**与 t13 的交叉引用**：`tools/erp-check.ps1` 里 `SkipCase 'P-23'`（`:677`）与
`SkipCase 'L-12'`（`:942`）将由 t13 用 `Download-Excel` 辅助函数在**真后端**上覆盖同一口径
（HTTP → 落盘 xlsx → 读回）；本条是它们的单测层前置防线（不启服务、CI 可比对）。

### 11.2 `exportEndpointsMatchFrontendAndMenuContract`（端点契约逐字断言）

用反射读注解，四条一次锁住（申请单与订单各一遍）：

| 断言 | 申请单 | 订单 |
| --- | --- | --- |
| 类级 `@RequestMapping` | `/sal` | `/sal` |
| 方法级 `value` | `/request/export` | `/order/export` |
| `methods` | **同时含 GET 与 POST** | 同 |
| `@PreAuthorize` 逐字 | `@ss.hasPermi('sal:request:export')` | `@ss.hasPermi('sal:order:export')` |
| `query` 参数 | **不得**带 `@RequestBody`（前端发 form-urlencoded，带了会 415） | 同 |
| `ids` 参数 | 必须带 `@RequestParam` 且参数名 = `ids` | 同 |

**本轮实测踩到的坑（已写进用例注释）**：`@RequestParam` 的 `value()/name()` 在 Spring 各版本里
返回类型不同，且 `@AliasFor` 的别名解析**只在** `AnnotationUtils` 里生效——裸反射读 `name()`
会拿到空串（我第一版就是这么写的，报 `expected:<ids> but was:<>`）。改用
`AnnotationUtils.getValue(annotation, "value"|"name")` 后版本无关地取到 `ids`。

### 11.3 逐列列名清单（`ErpSalesExportRow` 的 27 列，`@Excel` 出现顺序即 Excel 列序）

```
单据类型 / 单号 / 单据日期 / 状态 / 客户ID / 客户 / 经办人 / 归属部门ID / 关联合同 / 来源单号 /
交货日期 / 收货地址 / 联系人 / 联系电话 / 发货仓库ID / 剩余可下推量 / 单据金额合计 / 备注 /
行号 / 物料编码 / 物料名称 / 规格 / 单位 / 数量 / 单价 / 行金额 / 行备注
```
断言用例：`exportRowExcelColumnsAreExactAndOrdered`（反射枚举 `@Excel`，与上面 27 项**逐列且按序**比对，
并逐列断言 `name` 非空）；真产物侧由 `amountRoundingIsIdenticalInTheRealXlsx` 的表头断言复核。

### 11.4 两处**反向对照**的真实输出（证明这两条测试真在测序列化形态）

**对照 ①（销售侧，`@Excel` 列名）**：把 `ErpSalesExportRow.lineAmount` 的
`@Excel(name = "行金额")` 临时改成 `"行金额XX"` 后跑 `-Dtest=ErpSalesExportTest`：

```
Tests run: 15, Failures: 2, Errors: 0, Skipped: 0   → BUILD FAILURE
[ERROR] ErpSalesExportTest.exportRowExcelColumnsAreExactAndOrdered:703
        导出列必须逐列存在且顺序不变… expected:<[… 行金额, 行备注]> but was:<[… 行金额XX, 行备注]>
[ERROR] ErpSalesExportTest.amountRoundingIsIdenticalInTheRealXlsx:520
        导出表头必须包含「行金额」列，实际=[… 行金额XX, 行备注]
```
⇒ 改回 `"行金额"` 后 `Tests run: 15, Failures: 0` / BUILD SUCCESS。
**结论**：列名/漏列一旦漂移，两条用例都会红，不是空断言。

**对照 ②（跨包：posting 的 `ErpStockOutMapper.xml` 状态过滤）**：往
`selectBySourceDocId` 的 where 里临时加 `and d.status != 'voided'` 后跑
`-Dtest=ErpStockOutSourceDocQueryTest`：

```
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0   → BUILD FAILURE
[ERROR] ErpStockOutSourceDocQueryTest.xmlStatementUsesSourceDocIdAndDelFlagAndReusesFragments:105
        不得按状态过滤（状态口径留调用方）：发现 status 条件 →
        <select id="selectBySourceDocId" resultMap="ErpStockOutResult">
           and d.status != 'voided'
```
⇒ 删掉那句后 `Tests run: 7, Failures: 0` / BUILD SUCCESS（已 grep 确认 XML 里 `status != 'voided'` 出现次数为 **0**）。

> ⚠ **对照 ② 第一次没有变红——这是一个真实发现，已修**：我按验收描述先加了一句
> `and d.status != 'voided'` 就跑，结果是 `Tests run: 7, Failures: 0`。根因有两条：
> ① 语义用例用的是 `ErpPostingTestSupport.StubStockOutMapper`（**Java 内存桩**），它根本不读 XML；
> ② 源码级用例只断言了 XML **含** `source_doc_id` / `del_flag='0'` / 稳定排序，
> **没有**断言"不得出现 status 条件"——于是"XML 被单方面加上状态过滤"这个改动**当年没有任何用例能拦住**。
> 修法：在 `xmlStatementUsesSourceDocIdAndDelFlagAndReusesFragments` 里补
> `assertFalse(block.contains("status"))`（并说明 `<include>` 不展开、列片段里的 `d.status` 不会误伤），
> 之后反向对照如实变红。**这正是"反向对照"的价值：它证明的不是"我写了断言"，而是"断言真的守住了这条口径"。**

### 11.5 两处**授权跨包改动**（单列，便于 captain 核对边界）

| # | 文件 | 改动 | 授权来源 |
| --- | --- | --- | --- |
| D-19 | `src/test/java/com/ruoyi/ctms/erp/procurement/ErpPurExportTest.java` | 在 `assertExportMapping` 里补一条"`query` 参数上没有 `@RequestBody`"的断言（新增 `assertFalse` + `RequestBody` 导入；两个导出方法签名都是 `(HttpServletResponse, <Query>)`，故断言固定看参数下标 1） | t40 验收第 4 条（"captain 授权跨包改测试文件"） |
| O1 | `src/test/java/com/ruoyi/ctms/erp/posting/ErpStockOutSourceDocQueryTest.java` | 新增 `voidedRowsAreStillReturnedBecauseStatusIsTheCallersBusiness`（作废行种子 + "仍被返回"断言 + 已删除仍排除）；并在 XML 源码级用例里补 `assertFalse(block.contains("status"))` 把"状态口径留调用方"钉住 | t40 验收第 5 条（"t38 的 low 观察，captain 授权再扩一个跨包测试文件"） |

两处都**只改测试文件**；`ErpStockOutMapper.xml` 与 `ErpSalesExportRow.java` 仅在反向对照期间被**临时**
修改并已还原（§11.7.4 给出"临时触碰清单 + 还原验证"两段与逐条 grep 输出）。

### 11.6 验证（真实输出）

```
# 定向（t40 验收第 6 条要求的三类）
mvn -B -pl ruoyi-ctms test '-Dtest=ErpSalesExportTest,ErpPurExportTest,ErpStockOutSourceDocQueryTest'
  ErpStockOutSourceDocQueryTest  Tests run: 7,  Failures: 0, Errors: 0
  ErpPurExportTest               Tests run: 9,  Failures: 0, Errors: 0
  ErpSalesExportTest             Tests run: 15, Failures: 0, Errors: 0
  合计                            Tests run: 31, Failures: 0, Errors: 0 / BUILD SUCCESS

# 全模块（基线 539，只增不减）
mvn -B -pl ruoyi-ctms test
  Tests run: 543, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```
（t8 交付时 510 → t28 时 531 → **t40 后 543**；本组 `ErpSalesExportTest` 12 → **15**：
真 xlsx 读回、端点契约、`@Excel` 逐列各 +1；跨包两处 +1 +1。产品代码零改动。）

---

## 11.7 反向对照补记：F2 / D-19 的两条 `@RequestBody` 反向对照（t43）

> 背景：t40 的反向对照只覆盖了"**列名**"（销售侧 `@Excel`）与"**XML 状态过滤**"（跨包 posting），
> 漏了 t40 新加的那条**参数注解断言**本身——也就是"`query` 参数不得带 `@RequestBody`"这条：
> 它在销售侧与采购侧各有一份，两处都**只被正向断言**过，没有反向对照证明它真的能拦住改动。
> 本轮补齐这两条反向对照，并更正 §11 顶部一句不准确的措辞。
> **本次是文档侧修复：不改任何 `src/main/**` 与 `src/test/**` 代码，只改本 notes。**
> 两段红证据**由 t41 独立复现**（日志 `.cache\t41\contrast3a.log` / `.cache\t41\contrast3b.log`），
> 我（backend-sales）也用同样手法自跑过一遍，两处结论一致（差异说明见 §11.7.3）。

### 11.7.1 ③a 采购侧（D-19 断言）：给 `export` 的 query 参数加 `@RequestBody`

临时改动（**只在 t41 的临时副本里做**）：`ErpPurchaseRequestController.export` 的
`ErpPurchaseRequest query` → `@RequestBody ErpPurchaseRequest query`。真实失败输出
（来源：**t41 独立复现**，`.cache\t41\contrast3a.log`）：

```
There was 1 failure:
java.lang.AssertionError: 采购申请单export 的 query 参数不得带 @RequestBody（前端发 form-urlencoded，会 415）
FAILURES!!!
Tests run: 9,  Failures: 1
```
⇒ 期望的 `9 run / 1 fail` 命中，失败点就是 **D-19 新增的那条断言**
（`ErpPurExportTest.导出端点的权限点方法与路径与前端及菜单约定逐字一致 → assertExportMapping`）。
我自跑同款实验的输出与之一致：`Tests run: 9, Failures: 1`，失败用例
`ErpPurExportTest.导出端点的权限点方法与路径与前端及菜单约定逐字一致:331->assertExportMapping:365`。

### 11.7.2 ③b 销售侧（F2 断言）：给 `exportRequest` 的 query 参数加 `@RequestBody`

临时改动：`ErpSalesController.exportRequest` 的 `ErpSalesRequest query` → 加 `@RequestBody`。真实失败输出
（来源：**t41 独立复现**，`.cache\t41\contrast3b.log`）：

```
There was 1 failure:
java.lang.AssertionError: 销售申请单export 的 query 参数不得带 @RequestBody（前端发 form-urlencoded，会 415） expected null, but was:<带有 @RequestBody>
FAILURES!!!
Tests run: 15,  Failures: 1
```
⇒ 期望的 `15 run / 1 fail` 与失败用例 **`exportEndpointsMatchFrontendAndMenuContract`** 命中，
消息形态 `expected null, but was:<…@RequestBody>`。

### 11.7.3 两段输出的来源与一处措辞差异（如实说明）

| 项 | 来源 | `Tests run` | 失败用例 | 消息 |
| --- | --- | --- | --- | --- |
| ③a 采购侧 | **t41 独立复现**（`.cache\t41\contrast3a.log`） | 9 / 1 fail | `…导出端点的权限点方法与路径与前端及菜单约定逐字一致` | `采购申请单export 的 query 参数不得带 @RequestBody（…会 415）` |
| ③b 销售侧 | **t41 独立复现**（`.cache\t41\contrast3b.log`） | 15 / 1 fail | `exportEndpointsMatchFrontendAndMenuContract` | `… expected null, but was:<带有 @RequestBody>` |
| ③a 采购侧 | 我自跑（同款实验） | 9 / 1 fail | 同上（行号 331→365） | 同上 |
| ③b 销售侧 | 我自跑（同款实验） | 15 / 1 fail | 同上（行号 580→626） | `… expected null, but was:<存在 @RequestBody>` |

> **措辞差异说明**：我自跑时断言里"实际值"的字符串是 `存在 @RequestBody`（t40 原样），
> 与 t41 日志里的 `带有 @RequestBody` 差一个词。我一度把它改成 `带有` 以求与预期逐字一致，
> 但本任务验收第 3 条要求**不改任何 `src/test/**` 代码**，故已**改回 `存在 @RequestBody`**。
> 也就是说：**仓库里没有任何测试代码变更**，两段红证据以 t41 的独立复现日志为准；
> 差异只在"断言失败消息里的一个词"，判定逻辑与失败/通过的结论完全一致。

### 11.7.4 临时触碰清单 + 还原验证（后续同类操作统一按这两段写）

**暂时触碰两处产品文件——均已还原**

| # | 文件 | 对照 | 临时改动 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | `erp/sales/domain/vo/ErpSalesExportRow.java` | 对照① | `@Excel(name = "行金额", width = 12)` → `"行金额XX"` | **已还原** |
| 2 | `resources/mapper/erp/ErpStockOutMapper.xml` | 对照② | `selectBySourceDocId` 的 where 插入 `and d.status != 'voided'` | **已还原** |

（对照③a/③b 临时触碰的是生产控制器方法签名，t41 在 `.cache\t41\mut3a|mut3b\` 的**临时副本**里做，
仓库文件未被改动；我自跑时改的仓库文件也已还原。）

**还原核对（本轮实测 grep 输出，全部通过）**

```
① ErpSalesExportRow.java
   grep '行金额XX'                                    → 0 命中
   grep '@Excel(name = "行金额", width = 12)'          → 1 命中（原列名仍在）
   grep '@Excel'（总出现次数）                          → 28 = 27 列 + 类注释里的 1 处
                                                       （L12 `{@code @Excel}`，非注解）
② ErpStockOutMapper.xml
   grep "status != 'voided'"                          → 0 命中
   grep 'status != '                                  → 1 命中，位于 行 72
                                                       （`<if test="status != null and status != ''">`，
                                                        属**另一个语句** = 列表查询 selectStockOutList 的
                                                        status 筛选；selectBySourceDocId 块内 status 命中 0，
                                                        由 ErpStockOutSourceDocQueryTest 的 assertFalse 守护）
③a ErpPurchaseRequestController.java
   grep 'export(HttpServletResponse response, @RequestBody'                  → 0 命中
   grep 'export(HttpServletResponse response, ErpPurchaseRequest query)'     → 1 命中
③b ErpSalesController.java
   grep 'exportRequest(HttpServletResponse response, @RequestBody'            → 0 命中
   grep 'exportRequest(HttpServletResponse response, ErpSalesRequest query,'  → 1 命中
```
⇒ 全部还原，产品代码零净改动、测试代码零改动。

### 11.7.5 验证

```
mvn -B -pl ruoyi-ctms test
  Tests run: 543, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```
（与 t40 交付持平：本轮只加证据与文档，未增减用例 ⇒ ≥543 条 / 0 失败。）

---

## 12. D-1（blocker）：`@MapKey` 配 `Map<String,List<…>>` ⇒ 销售列表 500 —— 根因、修复与门禁

> 任务：`t45`（repair）｜影响：`GET /sal/request/list` 业务 `code=500`，`erp-smoke S03`、
> `erp-check S-A6/S-B3/S-B4/S-B5` 转红。**修复只动 `erp/sales` 的 mapper 与服务实现 + 一条新单测**，
> 未改 `tools/**`、`sql/**`、前端、别人的 notes。

### 12.1 根因**确认**（captain 的定位成立，机制补精确）

生产环境原始栈（`logs/backend-run.log`，修复前）：

```
00:38:10.807 DEBUG c.r.c.e.s.m.E.selectItemsByDocIds - [debug,137] - <==      Total: 2
java.lang.ClassCastException: class com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem
    cannot be cast to class java.util.List
  at com.ruoyi.ctms.erp.sales.service.impl.ErpSalesRequestServiceImpl.applyDerivedColumns(ErpSalesRequestServiceImpl.java:170)
  at com.ruoyi.ctms.erp.sales.service.impl.ErpSalesRequestServiceImpl.selectSalesRequestList(ErpSalesRequestServiceImpl.java:125)
  at com.ruoyi.ctms.erp.sales.controller.ErpSalesController.listRequest(ErpSalesController.java:99)
```

**判定依据（三条，互相独立）**：

1. **栈顶就在"泛型读"那一行**：`applyDerivedColumns:170` 是
   `List<ErpSalesRequestItem> items = grouped.get(row.getId());` —— SQL 已经成功（上一行日志
   `<== Total: 2`），炸在**取 Map 的值**处。
2. **机制**：MyBatis 的 `@MapKey` 契约是「**一行 → 一个值**」，`MapResultHandler.handleResult`
   就是 `map.put(mapKey, 当前行对象)`（泛型擦除后 `put(Object,Object)`，**插入时不抛**）。
   于是 `@MapKey("docId") Map<String, List<ErpSalesRequestItem>>` 里"值"被声明成 `List`，
   运行时塞进去的却是 `ErpSalesRequestItem` ⇒ 调用方那次读取被编译器插入
   `checkcast java.util.List` ⇒ ClassCastException。
   **不是"MyBatis 自己把行 cast 成 List"** —— 这个区别很重要：签名**永远不可能被填满**，
   与"某一行数据特殊"无关，只要页内有行就必炸。
3. **可执行复现**：新增单测 `ErpSalesItemBatchQueryContractTest.mapKeyShapeReproducesTheReportedClassCast`
   用"擦除后的 put + 生产同款泛型读"原样抛出**逐字相同**的消息
   （`cannot be cast to class java.util.List`），把机制从论断变成可跑证据。

**为什么 smoke 与 erp-check 结论不一致**（同一时间、同一份代码）：
`applyDerivedColumns` 在**当前页没有行**时直接 return（不查行项）⇒ 表空时不触发。
`erp-smoke S03` 跑在最前面（当时 `total=0`）⇒ 绿；`erp-check S-A6` 跑在 `S-A1 建单成功`之后
（`total≥1`）⇒ 红。**这正是"空表掩盖缺陷"的典型形态**，也是订单侧（`/sal/order/list` 当时 `total=0`）
表面返 200 的原因 —— 它其实带着**同一个**缺陷。

### 12.2 修复：选 (a)「mapper 返回平铺 List + 服务层 Java 分组」

| 方案 | 取舍 |
| --- | --- |
| **(a) mapper 返回 `List<行项>`，服务层分组（采用）** | 与 MyBatis 契约一致（一条 `IN` 查询仍返回多行）；**N+1 优化保留**（每页仍只查一次）；分组是纯内存一趟；`LinkedHashMap` + `computeIfAbsent` 保持稳定顺序；改动面最小、可读 |
| (b) 保留 `@MapKey` 改成"一行一值"的类型（`Map<docId, 行项>`） | 可用但**会丢行**（同一单据多行时后行覆盖前行），必须再改服务层语义；且"一行项一 Map 项"本身就是误导后来人 |
| (c) `<resultMap>` + `<collection>` 嵌套映射 | 能表达嵌套，但要新增嵌套 resultMap、改变行对象形状，为一个纯"结果形状"问题引入更重的映射，收益不成比例 |

**落点（4 个产品文件）**：
- `ErpSalesRequestItemMapper#selectItemsByDocIds` / `ErpSalesOrderItemMapper#selectItemsByDocIds`
  → `List<…>`（**去掉 `@MapKey`**），javadoc 写明"为什么不能写 `Map<String,List<…>>`"。
- `ErpSalesRequestServiceImpl` / `ErpSalesOrderServiceImpl` → 新增私有
  `groupRequestItems/groupOrderItems`（`LinkedHashMap` + `computeIfAbsent`），两处调用点改为"先平铺、再分组"。
- 3 个测试文件的 4 个桩同步改为返回平铺 List。

**不变量**（都保持）：① 每页只批查一次（SQL 仍是
`where doc_id in (…) order by doc_id asc, seq asc`）；② 键顺序 = 查询顺序、单据内按 `seq` 升序；
③ **空集合语义不变**：无命中 → 空 Map（不是 null）、没有行项的单据不出键、`docIds` 空/为 null
直接返回空 Map（既有守卫 `if (docIds == null || docIds.isEmpty())` 保留）。

### 12.3 实时验证：修复前后**原始 body 对照**

| 探针 | 修复前（HTTP 200，业务码在 body 里） | 修复后 |
| --- | --- | --- |
| `GET /sal/request/list?pageNum=1&pageSize=1` | `{"msg":"class com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem cannot be cast to class java.util.List (… is in unnamed module of loader org.springframework.boot.loader.LaunchedURLClassLoader @29774679; java.util.List is in module java.base of loader 'bootstrap')","code":500}` | `{"total":0,"rows":[],"code":200,"msg":"查询成功"}` |
| `GET /sal/order/list?pageNum=1&pageSize=1` | `{"total":0,"rows":[],"code":200,"msg":"查询成功"}`（**表空 ⇒ 掩盖了同一个缺陷**） | `{"total":0,"rows":[],"code":200,"msg":"查询成功"}` |

**有数据的实证**（空表不算证明 —— 只有页内有行才走批量查询与分组）：

```
# 订单侧：先建一张 3 行 1×0.125 的草稿订单
GET /sal/order/list?pageNum=1&pageSize=5
  → HTTP 200  code=200  total=1  rows=1
     首行 remainQtySum=3.000  canPush=True  totalAmount=0.39     ← 分组与明细金额都读出来了
# 申请侧：建一张两行（2×3.5 + 1×0.125）的草稿申请单
GET /sal/request/list?pageNum=1&pageSize=5
  → HTTP 200  code=200  total=1  rows=1
     命中行 remainQtySum=3.000 canPush=True totalAmount=7.13      ← 7.00+0.13，先舍入再汇总
```
（两张草稿随后已 DELETE 清理，列表回到 `total=0`；残留核查见 §12.6。）

### 12.4 门禁复跑

| 步骤 | 真实输出 |
| --- | --- |
| `mvn -B -pl ruoyi-ctms clean install` | **`Tests run: 547, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（基线 543 ⇒ 547，只增不减：新增 4 条契约回归） |
| `mvn -B -DskipTests -pl ruoyi-admin clean package` | **jar 时间戳 before=2026/10/6 0:31:31 → after=2026/10/6 0:43:33**，size=176.1 MB（确认不是旧包） |
| fat jar 嵌套包核对 | `BOOT-INF/lib/ruoyi-ctms-1.0.1.jar` 内 `ErpSalesRequestItemMapper.class` / `ErpSalesOrderItemMapper.class` 常量池**不含 `MapKey`**（字节级判据）⇒ 修复真进了运行态制品 |
| 重启 | `start-env.ps1 -Only Backend`；8080 监听；`oa-login` 抽验 `/workflow/simple-flow/list` = `code=200 total=2` |
| **`tools\erp-smoke.ps1 -Strict`** | **探针 27 条：通过 27 / 失败 0 / 跳过 0**（退出码 0）；其中 **`S03 GET /sal/request/list?pageNum=1&pageSize=1 → HTTP 200 / code=200 total=0`（修复前为 `code=500`）** |

### 12.5 `erp-check -Only S-`：逐条前后对照与**重新定性**

| 用例 | 修复前 | 修复后 | 结论 |
| --- | --- | --- | --- |
| **S-A6** 销售申请列表分页可读 | `.cache/t13/erp-gates.log`：`[FAIL] … 实际：code=500 total=` | **`[PASS] … 实际：code=200 total=1`**（两次 S-段跑都是 `total=1`，即**有数据**下的绿） | **D-1 的受害者，已修复** |
| S-A1 销售申请单新增成功 | `code=200 status=draft` | 同 | 一直绿（它先建单，S-A6 才拿到 `total=1`） |
| **S-B3** 三行 1×0.125 → totalAmount=0.39 | `code=500 total= customerName=` | **仍 `code=500`** | **与 D-1 无因果**（见下） |
| **S-B4** 交货日期/地址/联系人/发货仓落库 | `deliveryDate= addr= contact=` | 同上 | 同上 |
| **S-B5** 详情 items 非空 | 依赖 S-B3 的同一响应 | 同上 | 同上 |

**S-B3/S-B4/S-B5 的重新定性（证据链完整）**：它们**不是**销售代码缺陷，也**不是** D-1 的后果 ——
根因是 **`tools/erp-check.ps1` 的 S-B3 载荷漏了 `docDate`**（L892-894 只给了
`customerId/deliveryDate/deliveryAddress/contactName/contactPhone/shipWarehouseId/items`）：

1. **同一份代码、同一份夹具，只补一个字段就全绿**（在 `tools/erp-check.ps1` 的**副本**上插桩，
   用脚本**自己**解析出的夹具 id 做 A/B 对照）：
   ```
   DEBUG-FIX10 CustCode=[CUST26004935] CustId=[98E5A10814C44A8199A85CB802FAFEA8] httpCode=200 msg=操作成功
   DEBUG-SB3    CustId=[98E5…] ProdId=[56CA…] WhB=[BE7E…] code=500 msg=单据日期不能为空 data.id=[]
   DEBUG-SB3FIX code=200 msg=操作成功 totalAmount=0.39 customerName=[验收客户-T26004935]
                addr=[验收地址] contact=[联系人] items=3 id=[A246…]
   ```
   `DEBUG-SB3FIX` = 脚本原载荷 + `docDate`，其余一字不改 ⇒
   **S-B3 的 `totalAmount=0.39`/`customerName` 有值、S-B4 的地址与联系人有值、S-B5 的 `items≥1` 全部成立**。
2. **夹具不是原因**：`CustId/ProdId/WhB` 三个 id 都解析成功（`DEBUG-FIX10` 建客户 `httpCode=200`）。
   我一开始的假设"脚本没建客户夹具"是**错的**，已用插桩推翻（如实记录）。
3. **`单据日期` 是必填契约**：`POST /sal/order` 不带 `docDate` ⇒ `code=500 msg=单据日期不能为空`；
   同一脚本里 `S-A1`（`POST /sal/request` **带** `docDate`）一路绿。
4. **历史一致**：`S-B3` 在修复前（t13 / t26×2）与修复后（我的两次跑 + 插桩跑）输出**逐字相同**
   （`code=500 total= customerName=`）⇒ 与 D-1 的引入或修复都无关。

**交下游（不在我 scope，需 captain 派 t13）**：给 `tools/erp-check.ps1` 的 S-B3 载荷补
`docDate = (Get-Date -Format 'yyyy-MM-dd')`（与 S-A1 同款）⇒ S-B3/S-B4/S-B5 会一起转绿；
建议顺检 B-*/P-* 段"创建类载荷是否都带单据日期"。**我没有改 `tools/`**（t45 明确 out of scope）。

### 12.6 反向对照（D-15 纪律）与影响面

**反向对照 ①（只加回注解）**：给 `ErpSalesRequestItemMapper#selectItemsByDocIds` 临时加回
`@MapKey("docId")` ⇒ `ErpSalesItemBatchQueryContractTest`：

```
Tests run: 4, Failures: 1   → BUILD FAILURE
[ERROR] …salesBatchItemMappersMustNotUseMapKeyWithCollectionValue:81->assertFlatBatchSignature:131
        ErpSalesRequestItemMapper#selectItemsByDocIds 不得再标注 @MapKey（@MapKey 是一行一值，配不上 List 值）
        expected null, but was:<@org.apache.ibatis.annotations.MapKey(value="docId")>
```

**反向对照 ②（连类型一起改回旧形态）**：把返回类型改回 `Map<String, List<…>>` ⇒ `test-compile` 直接失败，
两处调用点正是"旧签名必炸"的位置：

```
ErpSalesRequestServiceImpl.java:[163] 不兼容的类型: Map<String,List<ErpSalesRequestItem>> 无法转换为 List<ErpSalesRequestItem>
ErpSalesRequestServiceImpl.java:[218] 同上
```
⇒ 无论"只加注解"还是"整段回退"，都**不可能再静默溜过去**（前者单测红、后者编译红）。

**新增回归**（`ErpSalesItemBatchQueryContractTest`，4 条）：签名契约（返回平铺 List + 无 `@MapKey`）＋
**通用规则**（sales 全部 mapper：`@MapKey` 的值类型不得是集合/数组/Map —— 把这一类缺陷在包内一次堵死）＋
机制复现（§12.1-3）＋服务层分组行为（键序 / seq 序 / 空集合语义 / 孤儿行跳过）。

**影响面**：只影响 `erp/sales` 的 4 个产品文件与 3 个测试文件（桩签名对齐）；表结构与 SQL 未改
（`order by doc_id asc, seq asc` 原样复用）；未改 `tools/**`、`sql/**`、前端、别人的 notes。
**残留核查**（清理后）：`t_ctms_customer/product/warehouse` 前缀命中 **0**；
`t_ctms_sales_request/sales_order/sales_request_item/sales_order_item` 行数 **0**；
`erp-check` 的 `CLEAN-01 夹具零残留` = PASS（残留=0）。
