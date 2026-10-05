# 09b · 进销存前端（下）· 接口契约对照表 **草稿**

> 任务：`t12 [t9b-fe-docs]`（tasks.md §8.4 的 8 类单据页 + §8.5 库存明细/流水 + §8.6 前端文档）
> 状态：**草稿（等 t12 解锁）**。本表由 t11 在等待期**逐个 grep 已落地源码**整理，**不凭猜**。
> 真源优先级：**已落地 Controller 签名 > t9a 冻结契约（`doc-kinds.js`）> 参考仓库移植清单**。
> 采集时间：2026-10-05 23:1x（`ruoyi-ctms` 工作区未提交状态）

---

## 0. 数据来源（逐文件，行号可复核）

| 单据/能力 | 已落地文件 | 备注 |
| --- | --- | --- |
| 入库单 | `erp/posting/controller/ErpStockInController.java` | 已落地（t3） |
| 出库单 | `erp/posting/controller/ErpStockOutController.java` | 已落地（t3） |
| 采购申请单 | `erp/procurement/controller/ErpPurchaseRequestController.java` | 已落地（t4a） |
| 采购单 | `erp/procurement/controller/ErpPurchaseOrderController.java` | 已落地（t4a）；**下推入库未落地**（t7 待做） |
| 销售申请/订单 | `erp/sales/controller/ErpSalesController.java` | 已落地（t5a）；**订单下推出库未落地**（t8 待做） |
| 库存结存/流水 | `erp/ledger/controller/ErpStockBalanceController.java`、`ErpStockLedgerController.java` | 已落地（t6） |
| 盘点/调拨 | `erp/stockops/{domain,mapper}` 只有 domain+mapper | **Controller 未落地**（t9 待做）⇒ 契约仍按 t9a 冻结 |
| 列表筛选真源 | `resources/mapper/erp/*.xml` 的 `<if test=...>` | 见 §2 各表 |
| 权限点真源 | 各 Controller 的 `@PreAuthorize`；菜单侧 `sql/二开-进销存-菜单.sql` | 见 §3 差异 |

---

## 1. 逐单据契约（**已落地签名**）

### 1.1 采购申请单 `purchase_request`

- **base**：`/erp/pur/request`（⚠ 不是 `/pur/request`）
- **权限点前缀**：`pur:request`

| 用途 | 方法/路径 | 权限点 | 请求/响应要点 |
| --- | --- | --- | --- |
| 列表 | `GET /list` | `pur:request:list` | 分页 `TableDataInfo`；**派生列 `remainQtySum` / `canPush` / `totalAmount`** |
| 详情 | `GET /{id}` | `pur:request:query` | `AjaxResult.data` = 单据 **含 `items`**（`ServiceImpl:247`） |
| 行项 | `GET /{docId}/items` | `pur:request:query` | — |
| 变更历史 | `GET /{docId}/change-logs` | `pur:request:query` | 返回 **`AjaxResult`（非分页）** |
| 合同下拉 | `GET /contract-options` | `pur:request:query` | 采购方向合同 |
| 新增 | `POST /` | `pur:request:add` | body = `ErpPurchaseRequest`（**含 `items[]`**） |
| 修改 | `PUT /` | `pur:request:edit` | 仅草稿 |
| 删除 | `DELETE /{id}` | `pur:request:remove` | 单 id（不是 `{ids}`） |
| 行项增/改/删 | `POST /{docId}/items`、`PUT /{docId}/items`、`DELETE /{docId}/items/{itemId}` | `pur:request:edit` | 可选路径，壳不用 |
| 提交 | `PUT /{id}/submit` | `pur:request:submit` | 无 body |
| 审核 / **驳回** | `PUT /{id}/approve?action=approve\|reject&reason=` | `pur:request:approve` | **驳回折叠在本端点**（`action=reject`） |
| 反审核 | `PUT /{id}/unapprove?reason=` | `pur:request:unapprove` | reason 为 **query 参数** |
| 作废 | `PUT /{id}/void?reason=` | `pur:request:void` | reason 为 query 参数 |
| 置为已完成 | `PUT /{id}/complete` | `pur:request:status` | ⚠ 权限点在菜单 SQL 里**不存在**（见 §3-D3） |
| 归零即完成判定 | `PUT /{id}/check-auto-complete` | `pur:request:status` | —— |
| **下推采购单** | `POST /{id}/push/purchase-order` | `pur:request:push` | 返回 `ErpPushResultVo`（**带 `sourceStatus` / `remainQtySum`**，前端据此刷新按钮显隐）；⚠ 权限点菜单里没有（见 §3-D4） |

列表筛选（`ErpPurRequestMapper.xml`）：`includeVoided`、`id`、`docNo`、`status`、`keyword`、`beginDocDate`、`endDocDate`、`requestDeptId`、`deptId`、`createId`、`posted`。

### 1.2 采购单 `purchase_order`

- **base**：`/erp/pur/order`｜**权限点前缀**：`pur:order`

| 用途 | 方法/路径 | 权限点 |
| --- | --- | --- |
| 列表 | `GET /list`（派生列 `remainQtySum` / `canReceive`） | `pur:order:list` |
| 详情 / 行项 / 变更历史 / 合同下拉 | `GET /{id}`、`GET /{docId}/items`、`GET /{docId}/change-logs`、`GET /contract-options` | `pur:order:query` |
| 新增 / 修改 / 删除 | `POST /`、`PUT /`、`DELETE /{id}` | `:add` / `:edit` / `:remove` |
| 行项 CRUD | `POST|PUT /{docId}/items`、`DELETE /{docId}/items/{itemId}` | `pur:order:edit` |
| 提交 / 审核(含驳回) / 反审核 / 作废 / 置完成 | `PUT /{id}/submit`、`PUT /{id}/approve?action=…&reason=`、`PUT /{id}/unapprove?reason=`、`PUT /{id}/void?reason=`、`PUT /{id}/complete` | `:submit`/`:approve`(reject 同)/`:unapprove`/`:void`/`:status` |
| **下推入库单** | **未落地**（t7）⇒ 仍按 t9a 冻结：`POST /{id}/push` + `pur:order:push` | — |

列表筛选（`ErpPurOrderMapper.xml`）：`includeVoided`、`id`、`docNo`、`status`、`keyword`、`beginDocDate`、`endDocDate`、`supplierId`、`purchaseDeptId`、`contractId`、`sourceDocId`、`deptId`、`createId`。

### 1.3 销售申请单 / 销售订单 `sales_request` / `sales_order`

- **base**：`/sal/request`、`/sal/order`｜**权限点前缀**：`sal:request`、`sal:order`

| 用途 | 方法/路径（`{r}` = request|order） | 权限点 |
| --- | --- | --- |
| 列表 | `GET /{r}/list` | `sal:request:list` / `sal:order:list` |
| 详情（含 items） | `GET /{r}/{id}` | `:query` |
| 行项 | `GET /{r}/{id}/items` | `:query` |
| 新增/修改/删除 | `POST /{r}`、`PUT /{r}`、`DELETE /{r}/{id}` | `:add` / `:edit` / `:remove` |
| 提交 | `PUT /{r}/{id}/submit` | `:submit` |
| 审核 | `PUT /{r}/{id}/approve` | `:approve` |
| **驳回** | `PUT /{r}/{id}/reject`（body 可选 `Map`） | `:approve` |
| 作废 / 反审核 | `PUT /{r}/{id}/void`、`PUT /{r}/{id}/unapprove`（reason 在可选 body） | `:void` / `:unapprove` |
| 置完成 | `PUT /{r}/{id}/complete` | `:status`（⚠ 菜单无此点） |
| 关联合同 | `PUT /{r}/{id}/contract`（body 可选） | `:edit` |
| **下推** | 申请→订单：`POST /request/{id}/push`（body `ErpSalesPushRequest`）；订单→出库：**未落地**（t8） | `sal:request:push`（菜单无此点） |
| 变更历史 | **无该端点** | — |

列表筛选（`ErpSalRequestMapper.xml` / `ErpSalOrderMapper.xml`）：`includeVoided`、`status`、`docNo`、`customerId`、`keyword`、`beginDocDate`、`endDocDate`（**没有 `handlerName`**）；
⚠ **列表无 `remainQtySum` / `canPush` 派生列**（见 §3-D5）。

### 1.4 入库单 / 出库单 `stock_in` / `stock_out`

- **base**：`/stk/in-order`、`/stk/out-order`（与 t9a 冻结一致 ✅）｜**权限点前缀**：`stk:in-order`、`stk:out-order`

| 用途 | 方法/路径 | 权限点 |
| --- | --- | --- |
| 列表 | `GET /list` | `:list` |
| 导出 | `GET|POST /export` | `:export` |
| 详情 / 变更历史 | `GET /{id}`、`GET /{id}/change-logs`（`AjaxResult`，非分页） | `:query` |
| 新增/修改 | `POST /`、`PUT /`（body 含 `items[]`） | `:add` / `:edit` |
| 删除 | `DELETE /{ids}`（**逗号分隔多 id**） | `:remove` |
| 提交 | `POST /submit/{id}` | `:submit` |
| 审核（即过账） | `POST /approve/{id}` | `:approve` |
| 驳回 | `POST /reject/{id}?reason=` | `:approve` |
| 作废 | `POST /void/{id}?reason=` | `:void` |
| 反审核（红冲） | `POST /unapprove/{id}?reason=` | `:unapprove` |
| 通用状态入口（为壳准备） | `PUT /status`，body `{id, action, reason}` | `:status`（⚠ 菜单无此点 ⇒ 壳应改用逐动作端点） |

列表筛选（`ErpStockInMapper.xml` / `ErpStockOutMapper.xml`）：`includeVoided`(boolean)、`status`、`docNo`、`inType`/`outType`、`warehouseId`、`supplierId`/`customerId`、`beginDocDate`、`endDocDate`、`keyword`。

### 1.5 盘点单 / 调拨单 `stock_take` / `stock_transfer`（**Controller 未落地**）

- 仍按 t9a 冻结契约（`doc-kinds.js`）：`/stk/take`、`/stk/transfer`，动作 `POST {base}/{id}/{action}`，盘点另有
  `POST /{id}/generate`、`PUT /{id}/count`。
- 列表筛选**已可从 mapper 预判**（t9 落地后以 Controller 为准）：
  - 盘点：`includeVoided`、`status`、`docNo`、`warehouseId`、`takeType`、`handlerName`、`beginDocDate`、`endDocDate`、`keyword`
  - 调拨：`includeVoided`、`status`、`docNo`、`fromWarehouseId`、`toWarehouseId`、`handlerName`、`beginDocDate`、`endDocDate`、`keyword`

### 1.6 库存明细 / 流水（§8.5，t12 用）

**结存 `/stk/stock`**（`ErpStockBalanceController`，权限 `stk:stock:*`）：
`GET /list`（筛选：`productId`、`warehouseId`、`productTypeIds[]`、`keyword`、`beginQty`、`endQty`、`belowSafetyOnly`、`hideZero`；返回含 `qty` / `safetyStock` / **`goodsQuota`（货品总额度）** / `updateTime`）
`GET /detail?productId=&warehouseId=`（`stk:stock:query`）、`GET|POST /export`（`stk:stock:export`）、`POST /recalc?repair=false|true`（`stk:stock:recalc`）。

**流水 `/stk/ledger`**（`ErpStockLedgerController`，权限 `stk:ledger:*`）：
`GET /list`（筛选：`ledgerId`、`productId`、`warehouseId`、`bizTypeFilter`、`docTypeFilter`、`beginTime`、`endTime`、`keyword`；返回含 `qtyChange` / **`qtyAfter`** / `unitPrice` / `ledgerTime`）
`GET /detail?id=`（`stk:ledger:query`）、`GET|POST /export`（`stk:ledger:export`）。
⚠ 货品总额度语义：`goodsQuota` 由服务端按"业务类型子串匹配「入库」且排除「调拨入库」"计算（t6 负责），前端**只显示**。

---

## 2. 与 t9a 冻结契约的差异（**t12 必须先改前端**）

| # | 差异 | t9a 现状 | 已落地实现 | 处理 |
| --- | --- | --- | --- | --- |
| D1 | 采购 base 多了 `/erp` | `/pur/request`、`/pur/order` | `/erp/pur/request`、`/erp/pur/order` | **队长裁决（2026-10-05 23:2x）：保留 `/erp/pur/*`**，不改控制器、不改前端 URL 结构 ⇒ 改 `doc-kinds.js` 的 `restBase`（2 处）。这是对 PRD §9.4 示例路径的**有意偏离**（PRD 的 URL 示例不是验收项），已记账 |
| D2 | **动作形状三种并存** | `POST {base}/{id}/{action}`，body `{id, reason}` | ① 入库/出库：`POST {base}/{action}/{id}`，reason 为 query；② 采购/销售：`PUT {base}/{id}/{action}`，reason 为 query（销售在可选 body） | **按 kind 配 `actionStyle`**（见 §3 方案），`api/erp/doc.js` 一处适配。**队长裁决：t12 第一步统一按已落地控制器对齐 api 层**（一个文件 + 就近补用例，只跑一轮三道门禁）；适配必须集中在**一处按 kind 派发的映射**，禁止 8 份 if/else |
| D3 | `complete` 权限点 | `:approve`（参考仓库口径） | 采购/销售：`:status`；菜单 SQL 原文写"不含 `:status`" | **队长裁决：保留 `:status`，改菜单不改编排** ⇒ `backend-ledger` 为 `pur:request`/`pur:order`/`sal:request`/`sal:order` 补 `:status` 的 F 行；前端 `ACTION_PERM_SEGMENT.complete = 'status'`（库存类无 complete，不显示该动作） |
| D4 | 下推权限点 | 申请→订单用目标单据 `:add` | 采购/销售落地用**来源单据** `:push`（`pur:request:push` / `sal:request:push`） | **队长裁决：以来源单据 `:push` 为准** ⇒ `PUSH_CHAINS` 改为 `pur:request:push`/`pur:order:push`/`sal:request:push`/`sal:order:push`；t8 补两个缺失 F 行。盘点单**无 push**；`stk:in-order/out-order` **无 push**（它们是下推目标） |
| D5 | 剩余量派生列 | 申请单列表依赖 `remainQtySum>0` 控制下推显隐 | 采购两页有 `remainQtySum`（+`canPush`/`canReceive`）；**销售没有** | **队长裁决：由 backend-sales 在 t5 补**；前端**继续用 `remainQtySum>0`**，不退化"只看状态" |
| D6 | 变更历史形态 | 赋 `res.rows` 分页 | 入库/出库/采购返回 `AjaxResult`（非分页）；**销售无该端点** | **队长裁决：前端隐藏入口 + 记账**（不改后端）⇒ `doc-kinds` 给销售两类加 `changeLogs: false`；其余兼容 `res.data ?? res.rows` |
| D7 | 行项是否内联 | body 带 `items[]` | ✅ 三种都是内联 `items`，且**详情会带出 items** | 无需改（采购另有分离 item 端点，壳不用） |
| D8 | 筛选参数 | `docNo` / `handlerName` / 各专有 | 通用 **`keyword`** + `includeVoided` + `beginDocDate/endDocDate`；专有见 §1 各表；**销售无 `handlerName`** | **队长裁决：前端按 kind 适配**；`beginDocDate/endDocDate` 是 `Date` 绑定，需实测一次（见 §4-H） |

---

## 3. 前端适配方案（**静态对齐已完成 · 2026-10-05 23:18**）

> 状态：按队长口径"静态对齐先做、接口实测放 t20 之后"，以下 1–7 已落地并跑过一轮三道门禁
> （`node tests/run.js` **168/168**（+16）、`node tools/audit/run-all.js` **10/10**、`npm.cmd run build:prod` **EXIT 0**，
> dist 23:18:49）。第 8 项（§8.5 两页）仍随 t12 的页面一起写。

1. `doc-kinds.js` 逐类声明请求形状（**一处派发**，见 `doc-rules.actionRequest`）：
   - `restBase`：按 §1 修正（D1 ✓ 采购 `/erp/pur/*`）；
   - `actionRequest.mode`：`post-action-id`（入库/出库）| `put-id-action`（采购/销售）| `post-id-action`（盘点/调拨，端点未落地）；
   - `actionRequest.reasonPlacement`：`query`（入库/出库/采购）| `body`（销售）；
   - `actionRequest.overrides`：采购的"驳回"折叠为 `approve?action=reject`（D2 ✓）。
2. `api/erp/doc.js` 的 `docAction()` **不再自己拼 URL**，一律走 `doc-rules.actionRequest()`；
   删除走 `doc-rules.deleteRequest()`（入库/出库 `DELETE /{ids}` 逗号分隔、其余 `DELETE /{id}`，D2/D6 ✓）。
3. `DocListShell.loadLogs()` 兼容 `res.rows` 与 `res.data` 两种返回；销售按 `kind.changeLogs === false` **隐藏**变更历史入口（D6 ✓）。
4. `DocItemsTable` **无需改**（items 内联 + 详情带出，已在 §1 逐个 ServiceImpl 核实）。
5. `canPush()`：优先服务端派生列 `canPush`/`canReceive`，其次状态白名单，再兜底 `remainQtySum>0`；
   **`push.pending` 的链直接不显示入口**（t32 时四条链都已落地 ⇒ 已全部翻真，见 §5.2/§9）（D5 ✓）。
6. 打印入口按裁决 **B'** 落地：`doc-kinds` 逐类 `printReady: false`，`DocListShell` 据此 `:disabled` + `title="打印未接入（待集成期接线）"`（t13 接线后翻 true）。
7. `PUSH_CHAINS` 权限点全改**来源单据** `:push`，并按已落地签名配置 `push` 形状：
   采购申请 `POST /{id}/push/purchase-order`（裸数组 body + `supplierId`/`remark` query）；
   销售申请 `POST /{id}/push`（`{lines:[{docItemId,qty,productId}], …}` body）（D4 ✓）。
8. §8.5 两页（库存明细/流水）按 §1.6 写：`goodsQuota` 只显示、`recalc` 需二次确认（维修开关）——**待 t12 页面阶段**。

> 附：筛选按 mapper 真源改为公共 `keyword`（+`status`+日期区间），`handlerName` 只留在盘点/调拨（D8 ✓）；
> 新增单测钉住三族动作 URL/verb/参数、删除形状、下推 body 形状与 `pending` 行为（16 条）。

---

## 4. 待裁决 / 待落地清单（t12 开工前的阻塞项）

**裁决状态（2026-10-05 23:2x 队长已逐条定稿，见 §2 的"处理"列）**：A/B/D 已裁决（D3 保留 `:status` 由 t8 补 F 行、D4 以来源 `:push` 为准由 t8 补两点、D1 保留 `/erp/pur/*`）；C 已派给 backend-sales（t5）；G 已定 B'；E/F 待后端组；H 待实测。

| # | 事项 | 归属 | 状态 |
| --- | --- | --- | --- |
| A | D3：`complete` 用 `:approve` 还是 `:status` | 队长裁决 → t8 补 F 行 | ✅ 裁决：保留 `:status` |
| B | D4：菜单补 `pur:request:push` / `sal:request:push` | 队长裁决 → t8 | ✅ 裁决：以来源 `:push` 为准 |
| C | D5：销售申请/订单列表补 `remainQtySum` | t5（backend-sales） | 已派（跑 t5 时补） |
| D | D1：`/erp/pur/*` 前缀 | 队长裁决 | ✅ 裁决：保留 `/erp/pur/*`（有意偏离 PRD 示例） |
| E | 采购单→入库单、销售订单→出库单下推端点（t7/t8） | 后端组 | 待落地（落地前前端不给"下推"按钮） |
| F | 盘点/调拨 Controller + `generate`/`count` 端点 | t9（stockops，队长已催） | 待落地（契约仍按 t9a 冻结） |
| G | 打印数据源（B'：本期静态置灰 + 明确提示） | 集成期 t13 | ✅ 裁决 B'，t12 落静态 flag |
| H | `beginDocDate/endDocDate` 的 `Date` 绑定是否接受 `yyyy-MM-dd` 字符串 | t12 第 1 步（需统一打包重启后才可测） | ✅ **已实测（§10.1）**：`?beginDocDate=2026-01-01&endDocDate=2026-12-31` → `code=200`，字符串可直接绑定 |

---

## 5. t12 开工顺序（队长 2026-10-05 23:3x 定稿的顺序）

**顺序：t20（后端统一打包重启 + B4 接口冒烟）→ t12 → t13 → t14。**

1. **（前置）等 t20 的统一打包重启**：当前 8080 跑的还是 B3 的 jar（`ruoyi-admin.jar` 2026-10-05 **17:04:10**，见 §6），
   所有 `/erp/*`、`/sal/*`、`/stk/*` 新端点现在都是 **404**，接口实测必须在新 jar 起来之后做；
2. 跑一次"每类单据 `GET /list`（含 `beginDocDate=yyyy-MM-dd` 与 `keyword`）+ `GET /{id}` + `GET /{id}/change-logs`"的**接口实测**，
   确认 §4-H 的日期绑定与 D6 的返回形态；同时核对 §1 的动作 URL/verb（t20 的契约不一致清单可能会点出参数名/权限点差异）；
3. 静态契约层已落地（§3，2026-10-05 23:18，含 168/168 门禁）——本步只需按 t20 的实测结论**微调配置**，
   仍保持"一处按 kind 派发"（`doc-rules.actionRequest/deleteRequest/buildPushRequest`），禁止 8 份分支；
4. **页面实现已落地**（t25，2026-10-05 23:29：18 个页面 + 1 个共用表；见 **§7 页面清单**）——
   本步只剩"按实测结论微调 + 浏览器走通"；
5. 交付 `notes/09b-frontend-docs.md` 终稿（本文件）+ 页面清单/权限点菜单对照/自检记录。

> **t12 执行结果（2026-10-05 23:47，见 §10）**：第 1 步（t20）已完成；第 2 步只读面**已实测**（§10.1）；
> 第 4 步浏览器走通**除写路径外全部完成**（§10.2），并抓到 2 个真实前端缺陷已修复（§10.3）；
> **写路径被后端 C-1 阻塞**（§10.4）⇒ 本任务以 `failed` 交回，附精确 finding，修好后只需重跑浏览器段。
> **证据分区**：`A1 静态对齐`（168/168）· `A2 页面实现`（192/192）· `A3 t32 repair`（195/195）· `A4 t12 联调修复`（196/196）已完成；
> **`B 写路径实测`未完成**（C-1 阻塞）。

### 5.1 t12 提交时的证据分段（队长要求，别让复核人误读）

提交说明里**分两段**写，不要混：

- **A. 静态对齐（已完成的证据，落在 t11/t12 之间的前置阶段）**：`node tests\run.js` **168/168**（152 → +16）、
  `node tools\audit\run-all.js` **10/10**、`npm.cmd run build:prod` **EXIT 0**（dist 23:18:49）；
  代码改动：`doc-rules.js`（三个纯函数 + `PUSH_CHAINS` 来源 `:push` + `canPush` 派生列/pending）、
  `erp-const.js`（`complete=:status`）、`doc-kinds.js`（`/erp/pur/*`、`actionRequest`、`deleteMode`、`changeLogs`、`printReady`、`keyword`）、
  `api/erp/doc.js`（不再拼 URL）、`DocListShell.vue`（打印置灰/销售隐藏变更历史/`loadLogs` 兼容）、`DocPushDialog.vue`。
- **B. 接口实测（t20 之后才有）**：每类 `/list`+`/{id}`+`/{id}/change-logs` 的真实返回、`beginDocDate/endDocDate` 绑定结论、
  动作端点 200/403 矩阵。**在 B 段证据出现前，不得声称"接口已验通"**。

### 5.2 开关翻转记录（**t32 已完成两条**；t13 会核这一条）

| 开关 | 位置 | 翻真条件 | 状态（2026-10-05 23:39） |
| --- | --- | --- | --- |
| `PUSH_CHAINS.purchase_order.push.pending` | `doc-rules.js` | t7「采购单→入库单」端点落地并核对签名（path/body/权限点 `pur:order:push`） | ✅ **已翻真**（t32）：`POST /erp/pur/order/{id}/push/stock-in`（`ErpPurchaseOrderController:363-371`）+ `/push/stock-in`、query `warehouseId/remark`、裸数组 body、`requiresWarehouse:true` |
| `PUSH_CHAINS.sales_order.push.pending` | `doc-rules.js` | t8「销售订单→出库单」端点落地并核对签名（`sal:order:push`） | ✅ **已翻真**（t32）：`POST /sal/order/{id}/push`（`ErpSalesController:491-496`）+ `{lines:[…]}` body（服务端 `pushRequestOf` 兼收裸数组） |
| `doc-kinds[*].printReady`（8 类） | `doc-kinds.js` | **属 t13**：ERP 单据 → workflow `businessId` 打印数据源接线完成 | ⏳ 仍为 `false`（打印未接入，静态置灰） |

> ⚠ 翻转开关时**只改这两处配置 + 用例断言**，不得改动 `DocListShell` 的显隐逻辑（显隐逻辑已经通用）。
> ⚠ 由 t32 起，`pending` 不再靠人工核对：`tests/erp-shell.test.js` 有一条用例**直接读后端 Controller 源码**，
> 断言"`pending` ⇔ 端点不存在"（见 §9）。

---

## 7. 页面清单与数据源（t25 交付 · 2026-10-05 23:29）

> **边界（本任务明确不做）**：t25 只做"静态实现 + 三道门禁"，**未做任何接口实测、未做浏览器走通**；
> 这两件事留给 t12（等 t20 统一打包重启后）。下面每页的端点是**已落地 Controller 的签名**（逐个 grep），
> 但"发出去能拿到 200"尚未验证。

### 7.1 16 个单据薄页面（菜单 component 逐个对应）

| 目录 | 列表页 component | 表单页 component | 读的 kind |
| --- | --- | --- | --- |
| `erp/purchase-request/` | `erp/purchase-request/index` | `erp/purchase-request/form` | `purchase_request` |
| `erp/purchase-order/` | `erp/purchase-order/index` | `erp/purchase-order/form` | `purchase_order` |
| `erp/sales-request/` | `erp/sales-request/index` | `erp/sales-request/form` | `sales_request` |
| `erp/sales-order/` | `erp/sales-order/index` | `erp/sales-order/form` | `sales_order` |
| `erp/stock-in/` | `erp/stock-in/index` | `erp/stock-in/form` | `stock_in` |
| `erp/stock-out/` | `erp/stock-out/index` | `erp/stock-out/form` | `stock_out` |
| `erp/stock-take/` | `erp/stock-take/index` | `erp/stock-take/form`（**专有交互**） | `stock_take` |
| `erp/stock-transfer/` | `erp/stock-transfer/index` | `erp/stock-transfer/form` | `stock_transfer` |

- 薄页只做两件事：`<doc-list-shell :kind="kind" />` / `<doc-form-shell :kind :id :mode />` +
  从 `$route.query` 读 `id`/`mode`；**没有**列定义、行项表、状态机分支（用例 `tests/erp-pages.test.js` 逐页断言）。
- 附件区与打印入口对 8 类生效（壳内建；打印按 B' 静态置灰 + `title="打印未接入（待集成期接线）"`）。
- 下推入口：**四条链的后端都已落地**（t32 核对并翻真）——采购申请→采购单、采购单→入库单、销售申请→销售订单、销售订单→出库单；`pending` 现全为 `false`（详见 §5.2 与 §9）。

### 7.2 盘点单独立交互（`erp/stock-take/form.vue`）

| 步骤 | 端点（已落地） | 权限点 | 页面行为 |
| --- | --- | --- | --- |
| ① 生成行项 | `POST /stk/take/{id}/generate` body `{takeType, productTypeIds?, items?, scopeNote?}` | `stk:take:add` | 全盘 / 抽盘（按物料优先、按类型含子树）；返回**更新后的单据**，用 `shell.applyServerDoc()` 回填行项（账面数量只读、差异自动算） |
| ② 录入实盘 | `PUT /stk/take/{id}/count` body `{id, items:[{id|productId, actualQty, diffReason}]}` | `stk:take:edit` | 行内编辑复用壳的 `DocItemsTable`（实盘可填、差异只读）；保存后回填 |
| ③ 审核 | `POST /stk/take/approve/{id}` | `stk:take:approve` | 复用 `DocActionDialog` + `rules.visibleActions`（槽里传的 `ctx.form.status` 是响应式的）；完成后 `reloadDoc()` 把 `generatedInNo`/`generatedOutNo` 显示出来（`el-alert` 文案由 `stocktake-rules.generatedDocText()` 生成，无差异时明说"未生成调整单"） |

纯函数口径（`src/views/erp/stock-take/stocktake-rules.js`，单测覆盖）：`buildCriteria`（全盘/抽盘/大小写归一）、
`validateGenerate`（抽盘必须选物料或类型）、`buildCountItems`（只带服务端要的键、实盘缺省 0）、
`validateCount`（负实盘被拒并指明行号）、`generatedDocText`。

### 7.3 库存明细页（`erp/stock-balance/index.vue`）与库存流水页（`erp/stock-ledger/index.vue`）

| 页面 | 端点 | 权限点 | 要点 |
| --- | --- | --- | --- |
| 库存明细 | `GET /stk/stock/list` | `stk:stock:list` | 列含 **`displayName`（商品类型-物料名称）**、`goodsQuota`（货品总额度，只显示）、`qty`、`safetyStock`、`warehouseName`、`updateTime`；筛选：`keyword`/`warehouseId`/`productTypeIds[]`/`beginQty`/`endQty`/`belowSafetyOnly`/`hideZero`（后两个开关**只在 true 时下发**） |
| 库存明细（导出） | `GET|POST /stk/stock/export` | `stk:stock:export` | 走 `download()`（服务端写二进制流），导出=当前筛选 |
| 库存明细（重算） | `POST /stk/stock/recalc?repair=false` | `stk:stock:recalc` | **默认只校验**；发现不一致时给出「修复结存…」→ 二次确认 + `repairWarningText()`（不可回滚 + 先留档）→ `repair=true`；结果展示 `checkedRows/consistent/inconsistentCount` + 不一致明细（`mismatchText`） |
| 流水下钻（抽屉） | `GET /stk/ledger/list` | `stk:ledger:list` | 抽屉内按 **(物料, 仓库) + 日期时间区间**；`LedgerTable` 复用 |
| 库存流水（独立页） | `GET /stk/ledger/list` + `GET|POST /stk/ledger/export` | `stk:ledger:list` / `stk:ledger:export` | 筛选：`keyword`（单号/来源单号）、`warehouseId`、`bizTypeFilter`（字典 `stock_biz_types`）、`docTypeFilter`（stock_in/out/transfer）、`beginTime`/`endTime`（**日期时间串**） |

- 共用件：`src/views/erp/stock-ledger/components/LedgerTable.vue`（列定义只有一份：记账时间/业务类型（红冲标红）/
  单据类型/单据号/来源单号/数量变动/变动后结存/单价/操作人/备注）——**只增不改，无编辑删除入口**。
- 纯函数口径（`src/views/erp/stock-balance/stock-balance-rules.js`，单测覆盖）：`balanceQuery`、`ledgerQuery`、
  `recalcRequest`（`repair` 缺参必须落 false）、`recalcSummary`、`repairWarningText`、`mismatchText`、
  `displayNameOf`（服务端优先 + 兜底拼接）、`isBelowSafety`（服务端派生列优先 + 兜底比较）。

### 7.4 api 层

| 文件 | 内容 |
| --- | --- |
| `src/api/erp/stock.js` | 库存账 7 个函数（list/detail/export/recalc × 结存、list/detail/export × 流水）；URL 全部取自 `stock-balance-rules.js`，**文件里不拼 URL**（用例断言剥注释后无字面量） |
| `src/api/erp/doc.js`（t11 已交付） | 单据 CRUD/动作/下推/导出/变更历史 + 盘点 `generate`/`count`（签名与已落地控制器一致） |
| `src/api/erp/masterdata.js`、`attachment.js`（t11 已交付） | 主数据与附件（页面用 `treeEnabledProductTypes`/`listEnabledWarehouses`/`listEnabledProducts` 取下拉） |

### 7.5 用例

`tests/erp-pages.test.js`（24 条）：页面落点与"薄页不复制壳"约束、库存账查询/重算语义（含"默认只校验 + 修复风险提示"）、
盘点三段交互的载荷形状与校验、与 `doc-kinds` 的一致性（盘点/调拨删除形状 = `DELETE /{ids}`）、库存账权限点逐字对齐。

---

## 6. 接口实测前置（实测记录 · 2026-10-05 23:2x）

用 `superAdmin` token（`.cache/token-superAdmin.txt`）对已落地端点做**只读探测**，结果：

```
OK   code=200 total=0 :: /ctms/warehouse/list?pageNum=1&pageSize=1      ← 对照组（B3 端点，在跑）
FAIL 404 :: /erp/pur/request/list?pageNum=1&pageSize=1
FAIL 404 :: /erp/pur/request/list?…&beginDocDate=2026-01-01&endDocDate=2026-12-31
FAIL 404 :: /stk/in-order/list?pageNum=1&pageSize=1
FAIL 404 :: /sal/request/list?pageNum=1&pageSize=1
FAIL 404 :: /stk/stock/list?pageNum=1&pageSize=1
FAIL 404 :: /stk/ledger/list?pageNum=1&pageSize=1
```

判断依据（**不是"端点不存在"**）：`ruoyi-admin\target\ruoyi-admin.jar` 的时间戳是 **2026-10-05 17:04:10**（B3 构建），
运行中的 java 进程也是 17:04 启动的那一个（另有 23:10 启动的进程与本次探测无关）；
B4 的 `ruoyi-ctms` 新增控制器尚未进 jar ⇒ 404 是**未打包**，不是路由缺失。
⇒ 结论：**§4-H 的日期绑定实测与 E2E 都必须在 t13 统一打包重启之后做**（这也解释了为什么现在不能"接口级验收 B4"）。

---

## 8. 三道门禁的真实输出（t25 · 2026-10-05 23:29）

```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js
──────────────────────────────────────────────
共 192 条；通过 192 条；失败 0 条          ← t11 后基线 168 → +24（tests/erp-pages.test.js）

cd F:\dsh\ruoyiOA
node tools\audit\run-all.js
● 类 1 .then 无 catch        OK（0）        自检 ✅
● 类 2 async 无 try/catch    OK（0）        自检 ✅
● 空 catch 总数              OK（71 = 基线） 自检 ✅
● loading 缺配对复位         OK（3 = 基线）  自检 ✅
● 非响应式赋值（真风险）      OK（0 = 基线）  自检 ✅
● 模板引用不存在的名字        OK（1 = 基线）  自检 ✅
● 流程↔模板绑定 / 模板四页签 / 内置打印版式  OK（0） 自检 ✅
● 主题令牌                   OK（0）        自检 ✅
共 10 个审计；失败 0 个；未自证 0 个

cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod        # 用 Start-Process 取真实退出码
EXIT=0；DONE Build complete；dist\index.html LastWriteTime = 2026-10-05 23:29:51

# 产物自证（三处新页面确实进了 bundle）：
Select-String -Path dist\static\js\*.js -Pattern '盘点操作（生成行项'  -List → chunk-31cf9af1.*.js
Select-String -Path dist\static\js\*.js -Pattern '商品类型-物料名称'  -List → chunk-4f953f16.*.js
Select-String -Path dist\static\js\*.js -Pattern '库存重算（仅校验）'  -List → chunk-4f953f16.*.js
Select-String -Path dist\static\js\*.js -Pattern '流水下钻'          -List → chunk-4f953f16.*.js
Select-String -Path dist\static\js\*.js -Pattern '未指定单据类型'     -List → 2 个 chunk（共用壳页面）
```

**未做（本任务边界）**：接口实测（每类 `/list`、`/{id}`、动作端点、`generate`/`count`、`recalc`、四条 `push`）与浏览器走通
——留待 **t12**（等 **t20** 打包重启之后）。

> 本文档**已按 t12 收尾**：§7 页面清单、§9 t32 repair、§10 联调与浏览器走通（含 C-1 阻塞与 8.6 四件套）均已落盘。
> 仍未收口的一处：**写路径浏览器走通**（等后端 C-1 修复，§10.4）。

---

## 9. t32 repair：下推 `pending` 与后端落地状态对齐（2026-10-05 23:39）

**问题**（qa-hardening 在 t24 只读核对中发现）：后端 t7 早已交付 `POST /erp/pur/order/{id}/push/stock-in`
（`ErpPurchaseOrderController:363-371`），但前端 `doc-rules.js` 仍把 `purchase_order.push.pending` 标为 `true`
⇒ `canPush()` 不放行 ⇒ 界面**不显示**「下推入库单」按钮（后端就绪、前端藏着入口）。

**逐 kind 源码核对（判据 = Controller 文本，不接受记忆/推测）**：

| 链 | 源码证据（file:line） | 端点 | 权限点 | 前端 `pending`（修复后） |
| --- | --- | --- | --- | --- |
| 采购申请 → 采购单 | `ErpPurchaseRequestController.java:383-391` | `POST /erp/pur/request/{id}/push/purchase-order` | `pur:request:push` | `false`（原本就是） |
| **采购单 → 入库单** | `ErpPurchaseOrderController.java:363-371` | `POST /erp/pur/order/{id}/push/stock-in`（query `warehouseId`/`remark`，body 裸数组） | `pur:order:push` | **`false`（本次翻真 ⬅️）** |
| 销售申请 → 销售订单 | `ErpSalesController.java:269-274` | `POST /sal/request/{id}/push` | `sal:request:push` | `false`（原本就是） |
| **销售订单 → 出库单** | `ErpSalesController.java:491-496` | `POST /sal/order/{id}/push`（服务端 `pushRequestOf` 兼收裸数组与包装对象） | `sal:order:push` | **`false`（本次翻真 ⬅️，t8 已落地）** |

**改动**（只在前端 `doc/**` + `tests/**`）：
1. `doc-rules.js`：两条链去掉 `pending:true`，并按签名补形状 —— 采购单 `pathSuffix='/push/stock-in'`、
   `extraParams:['warehouseId','remark']`、`requiresWarehouse:true`；销售订单 `bodyShape:'sales-request'`、`lineKeys:{srcItemId:'docItemId',qty,productId}`。
   新增纯函数 `validatePushExtra(kind, extra)`：`requiresWarehouse` 的链在提交前拦住"没选收货仓库"
   （依据 `ErpPurchaseOrderController:350-352` 的注释：`warehouseId` 不传回落采购单默认收货仓库，**两者都空则拒绝下推**）。
2. `DocPushDialog.vue`：只有"需要收货仓库"的链才去拉仓库下拉（其余链零额外请求）；默认取来源单的
   `receiptWarehouseId`；提交前先过 `validatePushExtra`，原因以提示给出（不静默失败）。
3. `tests/erp-shell.test.js`：**按新状态更新既有断言（未删除）**，并新增 3 条 ——
   ①「下推端点落地状态：四条链均不得 pending」；②「下推端点形状逐条对照签名」；
   ③**「判别：每条下推链的 pending 与后端 Controller 源码一致」**（直接读 `ruoyi-vue-oa-master` 的
   Controller 文本，匹配 `@PostMapping(.../push...)` 与 `hasPermi('<perm>')`，断言 `pending ⇔ 端点不存在`）。
   还有一条 `validatePushExtra` 用例。

**三道门禁（真实输出）**
```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js              → 共 195 条；通过 195 条；失败 0 条   （t25 基线 192 → +3）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js    → 共 10 个审计；失败 0 个；未自证 0 个
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod         → EXIT=0；dist\index.html LastWriteTime = 2026-10-05 23:39:40
```

**判别用例的"牙齿"（反向对照，证明它不是摆设）**：人为把采购单那条链再标回 `pending: true` 后跑用例 ——
```
✗ 判别：每条下推链的 pending 与后端 Controller 源码一致（防"后端好了、前端还藏着"）
共 195 条；通过 190 条；失败 5 条      ← 判别用例 + canPush/buildPushRequest/形状 等一并变红
```
还原后 **195/195 全绿**。⇒ 以后"后端好了、前端还藏着"（或反过来提前放行）会**立刻**在单测里变红。

> 仍未做（边界）：**接口实测**（四条 `push` 的真实 200/权限矩阵）与浏览器走通 —— 等 **t20** 打包重启后由 **t12** 做。
> 本 repair 只保证"前端不再藏着已就绪的入口"，并在前端把"没仓库会被拒"这一条提前拦住。

---

## 10. t12：真实接口联调 + 浏览器走通（2026-10-05 23:47）

> t20 已统一打包重启（jar `23:37:57`，后端 PID 26280/23:38:09），B4 端点在线。本节的每个结论都有**现场证据**。
> **写路径浏览器走通被 C-1 阻塞**（见 §10.4），其余全部完成。

### 10.1 只读接口实测（`superAdmin` token，直连 8080）

| 端点 | 结果 | 说明 |
| --- | --- | --- |
| `/erp/pur/order/list`、`/sal/request/list`、`/sal/order/list` | `code=200 total=0` | 列表可用（无数据） |
| `/stk/in-order/list`、`/stk/out-order/list`、`/stk/take/list`、`/stk/transfer/list` | `code=200 total=0` | 同上 |
| **`/erp/pur/request/list`** | **`code=500`** | **C-1**（`ErpDocHeader` 重载 getter `posted`），见 §10.4 |
| `/stk/in-order/list?beginDocDate=2026-01-01&endDocDate=2026-12-31` | `code=200` | **§4-H 结论**：`yyyy-MM-dd` 字符串能被 `Date` 绑定接受（不再需要 `Date` 对象） |
| `/erp/pur/order/list?keyword=测` | `code=200` | 公共关键字筛选可用 |
| `/stk/stock/list`、`/stk/ledger/list` | `code=200 total=0` | 库存账可用 |
| `POST /stk/stock/recalc?repair=false` | `code=200`，`data={checkedRows:0,consistent:true,inconsistentCount:0,repair:false,repairedCount:0,mismatches:[]}` | 与页面 `recalcSummary()` 的字段口径逐字一致 |
| `/stk/stock/detail?productId=NOPE&warehouseId=NOPE` | HTTP 200 + `{"code":404,"msg":"库存记录不存在"}` | **C-2 澄清**：路由存在，404 是**业务级**"记录不存在"（对照 `/stk/stock/nope` 才是真无映射）；参数确为 `productId+warehouseId` ✓ 前端已按此写 |
| `/stk/ledger/detail?id=NOPE` | 同上（业务 404） | 前端未使用该端点（页面不做单条流水详情） |
| `/ctms/{warehouse,product,uom}/options` | `code=200`（当前各 0 条） | 选择器端点在线；前端当前用 `.../list?enableFlag=1`（需要物料快照字段 spec/uomId/defaultPrice，`/options` 的精简投影无法满足）——**保留现状并在 §10.6 记录理由** |
| `/ctms/attachment/object-types` | `code=200`，`registered=["contract","purchase_request","purchase_order","sales_request","sales_order","stock_in","stock_out","stock_take","stock_transfer"]`，`plannedB4=[]`，`maxSizeMb=20` | **t11 遗留项已解决**：t1 已把 8 类单据登记齐（原 PLAN only），附件 422 隐患消除；**C-3 澄清**：`registered` 在响应**根**（不在 `data`），前端 `DocAttachmentPanel` 正是读根 ✓ |

### 10.2 浏览器走通（逐页，全量真实后端）

| 页面 | 结果 | 现场证据（`.app-main` 文本摘要） |
| --- | --- | --- |
| `/erp/purchase-order`、`/erp/sales-request`、`/erp/sales-order`、`/erp/stock-in`、`/erp/stock-out`、`/erp/stock-transfer`、`/erp/stock-take` | ✅ 渲染 + 请求 200（空表） | 各自的筛选/列/提示语与 `doc-kinds` 一一对应（例：入库单 = 仓库/入库类型/供应商 + 过账/来源单号；盘点单 = 仓库/盘点范围/经办人 + 盘盈入库单/盘亏出库单） |
| `/erp/purchase-request` | ⚠️ **渲染出后端错误**（不再静默空表） | 页面显示 C-1 原文 + 「重试」：`nested exception is ... Illegal overloaded getter method ... property 'posted' in class ...ErpDocHeader` |
| `/erp/stock-take/form` | ✅ | 盘点操作区（盘点范围 + 确认生成行项 / 保存实盘数量 / 刷新）+ 提示"请先「保存草稿」生成单据"；表头含 生成的盘盈入库单/盘亏出库单；行项列 账面数量（只读）/实盘数量/差异/差异原因 |
| `/erp/stock-balance` | ✅ **实点「库存重算（仅校验）」** | 出现结果条 `检查 0 行；全部一致`（= 真实 `recalc?repair=false` 返回） |
| `/erp/stock-ledger` | ✅ | 筛选（物料关键字/仓库/业务类型/单据类型/记账时间）+ 列（记账时间/业务类型/单据类型/单据号/来源单号/数量变动/变动后结存/单价/操作人/备注） |
| 后端日志佐证 | ✅ | `selectSalesRequestList_COUNT`、`selectSalesOrderList_COUNT`、`selectStocktakeList_COUNT`、`selectBalanceList_COUNT`、`selectLedgerRows_COUNT` 等**逐页出现**（证明前端请求真的发出去了） |

### 10.3 浏览器走通抓到的两个**真实前端缺陷**（已修复 + 加门禁）

**F-1（严重，静默不请求）**：8 个文件写了 `import api from '@/api/erp/doc'` / `'@/api/erp/stock'`，
而这两个 api 模块**只有命名导出** ⇒ `api` 是 `undefined` ⇒ `api.listDocs(...)` **同步抛错**，
请求根本没发出、页面只是空表（**连错误提示都没有**），最阴的结果是"看起来只是没数据"。
- 修复：8 处改为命名空间导入（`import * as api from ...`）：`DocListShell`、`DocFormShell`、`DocPushDialog`、`DocActionDialog`、
  `stock-take/form`、`stock-balance/index`、`stock-ledger/index`、`stock-ledger/components/LedgerTable`。
- 门禁：`tests/erp-pages.test.js` 新增「api 导入形状」用例（扫 `api/erp/*` 有无 `export default`，
  再全仓搜默认导入 ⇒ 必须为空）；**反向对照**：把 1 个文件改回默认导入 → 该用例立刻变红并点名文件，还原后全绿。

**F-2（观感）**：`doc-kinds` 里两条 `subtitle` 残留 Markdown 粗体 `**过账时**`，页面把星号原样显示。
已去掉 `**`（采购单/销售订单两处）。

### 10.4 阻塞 B-1（= t20 的 C-1，后端公共层，不在本任务范围）

**现象**：`POST /erp/pur/request`（用**真实主数据夹具**：类型/单位/仓库/物料全部 200 建好）返回
```
code=500 msg=nested exception is org.apache.ibatis.reflection.ReflectionException:
Illegal overloaded getter method with ambiguous type for property 'posted'
in class 'com.ruoyi.ctms.erp.base.domain.ErpDocHeader'
```
⇒ `ErpDocHeader` 同时有 `isPosted()`(boolean, 源码 :164) 与 `getPosted()`(String, :399)，被 `#{posted}` 反射到。
**影响面**：8 类单据的**全部写路径**（新增/编辑/提交/审核/红冲/下推的后置写库）都不可能与数据库交互；
另加**采购申请单列表**（其 Mapper 用 `posted` 做条件）读出 500。
**因此 t12 的"逐页走通 保存/提交/审核/红冲/下推"无法完成** —— 这是后端缺陷，不是前端问题（前端合同层已被 t20 核对为逐条一致）。
**夹具零残留**：物料/类型/单位/仓库 4 条已删除（均 200），`t_ctms_purchase_request` 等表仍为 0（写失败即回滚）。
**修复后的收尾**：C-1 修好并重新打包重启后，重跑本节的浏览器段即可（前端不需要再改代码）。

### 10.5 三道门禁（真实输出）

```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js              → 共 196 条；通过 196 条；失败 0 条   （t32 基线 195 → +1「api 导入形状」）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js    → 共 10 个审计；失败 0 个；未自证 0 个
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod         → EXIT=0；dist\index.html LastWriteTime = 2026-10-05 23:47:00
# 页面源码自查：无裸色值（#rrggbb）；无 YYYY-MM-DD 字面量（只有 erp-const 的格式串 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss）
```

### 10.6 §8.6 前端说明文档四件套

1. **页面清单**：见 §7.1（16 个薄页面 + 2 个库存账页 + 共用 `LedgerTable.vue`）与 §7.2/§7.3。
2. **权限点与菜单对应**（菜单由 t8 落库，页面只做 `v-hasPermi`，**不新增权限点**）：

| 页面 | 菜单 path / component | 权限点（列表 / 关键动作） |
| --- | --- | --- |
| 采购申请单 | `purchase-request` · `erp/purchase-request/index`（表单 `/form` 隐藏） | `pur:request:list` · `:add/:edit/:remove/:submit/:approve/:void/:unapprove/:export/:print` + `pur:request:push` |
| 采购单 | `purchase-order` · `erp/purchase-order/index` | `pur:order:*` + `pur:order:push` |
| 销售申请单 | `sales-request` · `erp/sales-request/index` | `sal:request:*` + `sal:request:push` |
| 销售订单 | `sales-order` · `erp/sales-order/index` | `sal:order:*` + `sal:order:push` |
| 入库单 | `stock-in` · `erp/stock-in/index` | `stk:in-order:*`（`complete` 用 `:status`） |
| 出库单 | `stock-out` · `erp/stock-out/index` | `stk:out-order:*` |
| 调拨单 | `stock-transfer` · `erp/stock-transfer/index` | `stk:transfer:*` |
| 盘点单 | `stock-take` · `erp/stock-take/index` | `stk:take:*`（生成行项用 `:add`，实盘用 `:edit`） |
| 库存明细 / 库存流水 | `stock-balance` / `stock-ledger` · `erp/stock-{balance,ledger}/index` | `stk:stock:list|query|export|recalc` / `stk:ledger:list|query|export` |
| 主数据四页 | `master/*` · `erp/master/{product,product-type,uom,warehouse}/index` | 沿用 B3 `ctms:partner:*`（未新增 `stk:product*`） |

3. **日期格式与消息 API 改写检查清单**（Element Plus → Element UI 2.x，全部由用例门禁）：
   - 日期/时间一律用**格式串**：`erp-const.DATE_FORMAT='yyyy-MM-dd'`、`DATETIME_FORMAT='yyyy-MM-dd HH:mm:ss'`（`value-format` 绑定）；**禁止**大写 `YYYY-MM-DD` 字面量（用例扫源码）；
   - 提示只用 `this.$modal.msgSuccess/msgWarning/msgError/confirm`，**不用** `ElMessage*`；删除确认走 `this.$modal.confirm`；
   - 列表容器只用全局 `right-toolbar` / `pagination` / `DataLoadError`（不引 Element Plus 组件）；
   - 表格/表单用 Element UI 2.15 语法（`slot-scope`、`:visible.sync`），不写 `#default` 插槽语法。
4. **主题令牌约定**：样式只用 `var(--oa-*)`（`audit-theme-tokens` 硬门禁=0）；间距/字号用 `--oa-space-*`、`--oa-font-*`，
   语义色用 `--oa-color-{success,warning,error,ink-subtle,ink-muted}`；**不写裸色值**（本次自查 0 命中）。

> 证据分区更新：**B 段（接口实测）已部分完成**——只读面（列表/库存账/重算/附件元数据/日期绑定）已实测并写入 §10.1；
> **写路径段**由 t14 的 `erp-ui-stock-in-write.spec.js` 承担（C-1 已由 t31 修好，§11 有 t46 的现场证据）。

---

## 11. t46 repair：单据下拉**全空**（P-②，P0）—— 两个根因 + 现场证据（2026-10-06 00:5x）

### 11.1 根因 1：取数层级读错（`res.data` vs `rows`）

**实测响应体形状**（superAdmin token，直连 8080，本轮现测）：

| 端点 | 顶层键 | 行数组在哪 |
| --- | --- | --- |
| `/ctms/warehouse/list?enableFlag=1` | `total,rows,code,msg` | **`rows`**（total=2） |
| `/ctms/product/list`、`/ctms/uom/list` | `total,rows,code,msg` | **`rows`** |
| `/ctms/contract/list?type=PUR` | `total,rows,code,msg` | **`rows`** |
| `/ctms/partner/supplier/options` | `msg,code,data` | **`data`**（5 条） |
| `/ctms/partner/customer/options` | `msg,code,data` | **`data`**（6 条） |
| `/system/dict/data/type/stock_in_types` | `msg,code,data` | **`data`**（3 条启用） |
| `/ctms/attachment/list`（`AjaxResult`） | `msg,code,data` | **`data`** |

⇒ 修复：新增 `src/views/erp/doc/erp-response.js`（CJS 纯函数 `rowsOf` / `dataOf` / `listOf`），
`doc-options.js` 的**7 个装载点逐点声明层级**（物料/仓库/单位/采购合同/销售合同 = `rowsOf`；
供应商/客户 = `dataOf`）。同类残留一并修掉：`DocPushDialog`（收货仓库）。

> **范围说明（t46 收口时按 inScope 收紧）**：`stock-balance/index.vue` 与 `stock-ledger/index.vue` 的
> **仓库筛选**也是同源缺陷（读 `res.data` ⇒ 恒空），但这两个文件**不在 t46 的 inScope**（`views/erp/doc/`、
> `api/erp/`、`tests/`、本 notes）；本轮已**回退**这两处的改动并作为 finding 交队长派单（见下）。
> 因此新增门禁的扫描范围锁在 `views/erp/doc/**` + `api/erp/**`，并在用例注释里写明这两处已知残留。

**inScope 外残留（未修，待派单）**：
- `src/views/erp/stock-balance/index.vue`（仓库筛选，`listEnabledWarehouses()` 后读 `res.data`）
- `src/views/erp/stock-ledger/index.vue`（仓库筛选同上；另一处：业务类型筛选 `<el-option>` 循环原始字典行 ⇒ 空条目）

### 11.2 根因 2：字典下拉**渲染成空条目** + 状态筛选**没有选项源**（由 t48 的真实运行暴露）

P-② 修好后，qa-hardening 的 `erp-ui-stock-in-write.spec.js` 复跑到了下一步，**在「入库类型」处红**：

```
Error: 下拉里应有选项「采购入库」  locator('.el-select-dropdown:visible').first()
       .locator('.el-select-dropdown__item').filter({ hasText: '采购入库' })  → element(s) not found
```

**trace 取证**（`tests/e2e/reports/artifacts/…/trace.zip`，只读解包）：

- 网络：`GET /dev-api/system/dict/data/type/stock_in_types` → **200 OK**（数据拿到了）；
- DOM：`el-select-dropdown__item` **存在**，但整个 trace 的任何快照里**都搜不到 `采购入库`** 字样
  ⇒ 选项渲染出来了、**文字是空的**。

根因：字典行是 `{dictLabel, dictValue}`，而模板写的是 `<el-option :label="o.label" :value="o.value">`
⇒ `label/value` 都是 `undefined` ⇒ **空条目**（DOM 有点选项、用户与自动化都"看不到"）。
另外 `status` 筛选声明 `options: 'docStatuses'`，但 `emptyOptions()` **从未提供该键** ⇒ 状态下拉也恒空。

修复（一处收敛）：
- 新增 `src/views/erp/doc/doc-select-shape.js`（CJS 纯函数）：`dictRowsToOptions` / `statusOptionsOf` / `masterOptionsOf`；
- `doc-selects.js` 增 `dictSelectOptions(vm,name)` 与 `filterSelectOptions(vm,field)`（字典 / 状态 / 主数据三类筛选收敛一处）；
- `DocFormShell` 字典下拉改 `dictSelectOptions(f.dict)`；`DocListShell` 筛选改 `filterOptions(f)`
  （原 `selectOptions` 只认 `field.options`，字典筛选与状态筛选都取不到值）；
- `doc-options.emptyOptions()` 增 `docStatuses: statusOptionsOf(rules.STATUSSES)`（前端自带真源，不发请求）。
- ⚠ inScope 外的同款字典残留（`stock-ledger` 业务类型筛选）**未改**，已列为待派单项（见 §11.1）。

### 11.3 浏览器现场证据（来自 t48 的真实 Playwright 运行，非我方自述）

`erp-ui-stock-in-write.spec.js` 失败时的 DOM 快照（trace 内）：

```
- text:  *仓库
- textbox "请选择仓库": E2E4UMUVHIK2I一号仓        ← 仓库下拉**有选项且已选中**（P-② 修复生效的直接证据）
- text:  *入库类型
- textbox "请选择入库类型"                          ← 当时在字典下拉处红（= §11.2，已修）
```

⇒ **P-② 的验收要求（"入库单表单的仓库下拉里有选项、至少能选中夹具仓库"）已由这条真实浏览器证据满足**；
字典/状态下拉的修复**尚未经浏览器复跑**（诚实标注）——需 qa-hardening 在 t48 里重跑该用例确认（前端改动走 HMR 即时生效）。

### 11.4 三道门禁 + 反向对照（真实输出）

```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js              → 共 202 条；通过 202 条；失败 0 条      （t46 起始基线 196 → +6，只增不减）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js    → 共 10 个审计；失败 0 个；未自证 0 个
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod         → EXIT=0（DONE Build complete）
```

**反向对照 1**（门禁「doc-options 逐个装载点」）：把仓库装载点从 `rowsOf` 改回 `dataOf` ⇒
`✗ doc-options：逐个装载点声明取数层级（列表 5 处 rowsOf、/options 2 处 dataOf）`（196→198 时 198 通过/1 失败）。
**反向对照 2**（门禁「列表类接口不得读 res.data」）：把 `DocPushDialog` 改回 `(res && res.data) || []` ⇒
`✗ 判别：erp 页面/api 里"列表类接口"的结果不得读 res.data（P-② 门禁）`，并点名 `src/views/erp/doc/components/DocPushDialog.vue`。
**反向对照 3**（门禁「el-select 不得循环原始字典行」）：把 `DocFormShell` 改回 `dictOptions(f.dict)` ⇒
`✗ 判别：erp 的 el-select 不得直接循环原始字典行（必须经 dictSelectOptions/filterOptions）`，并点名 `src/views/erp/doc/DocFormShell.vue`。
三次对照**还原后均全绿**（202/202）。

### 11.5 新增用例（6 条）

| 用例 | 钉住什么 |
| --- | --- |
| `erp-response：rowsOf 只认 rows、dataOf 只认 data` | 两种体形状 + 空值/非数组路径（含"列表体没有 data ⇒ 必须空"这条反例） |
| `doc-options：逐个装载点声明取数层级` | 7 个装载点逐个断言 rowsOf/dataOf（改一处即红） |
| `判别：erp 页面/api 里"列表类接口"的结果不得读 res.data` | 全 `src/views/erp/**` + `src/api/erp/**` 邻近窗口扫描（剥注释后判） |
| `doc-select-shape：字典行/状态/主数据都必须转成 {value,label}` | 转换正确 + "字典行没有 label/value"这条反例 |
| `状态筛选：doc-kinds 声明 options=docStatuses，且 doc-options 提供该键` | 8 类单据状态筛选的选项源存在 |
| `判别：erp 的 el-select 不得直接循环原始字典行` | 模板层门禁（3 处必须用转换后的集合） |

---

## 12. t51 repair：T46-1 同源残留清零 + 两条门禁扩围（2026-10-06 01:0x）

t46 交回时把两页同源缺陷列为 finding（T46-1）；t51 把它们修净，并**把门禁扫描范围扩到整个 `views/erp/**`**，
让这类残留不再有藏身处。

### 12.1 两处改动

| 文件 | 改动点 | 依据 |
| --- | --- | --- |
| `src/views/erp/stock-balance/index.vue` | `loadSelects()` 里的仓库装载 `(res && res.data) \|\| []` → **`rowsOf(res)`**（复用 `views/erp/doc/erp-response.js`） | `/ctms/warehouse/list?enableFlag=1` 是**列表类**接口，体为 `{total,rows,code,msg}`（无 `data`）⇒ 裸读 `res.data` 恒空 |
| `src/views/erp/stock-ledger/index.vue` | ① `loadWarehouses()` 同上改 `rowsOf(res)`；② **业务类型筛选**模板由 `dictOptions('stock_biz_types')` → **`dictSelectOptions('stock_biz_types')`**（新增该方法，内部走 `doc-select-shape.dictRowsToOptions`） | 字典行是 `{dictLabel,dictValue}`，直接循环会渲染成**空条目**（有点选行、无文字） |

> 两页都只改"取数/选项形状"，未动查询参数、列定义与布局；`erp-response.js` 与 `doc-select-shape.js` 是复用（不另写第二份口径）。

### 12.2 两条门禁扩围 + 反向对照

| 门禁 | 扩围前 | 扩围后 |
| --- | --- | --- |
| 「列表类接口不得裸读 `res.data`」 | 只扫 `views/erp/doc/**` + `api/erp/**` ⇒ 两页残留**逃逸一轮** | **`views/erp/**`（整树）+ `api/erp/**`** |
| 「`el-select` 不得循环原始字典行」 | 只扫 `views/erp/doc/**` ⇒ 流水页字典筛选逃逸 | **`views/erp/**`（整树）** |

**反向对照（真实输出，扩围后）**
```
A) stock-balance 仓库筛选改回 (res && res.data) || []：
   ✗ 判别：erp 页面/api 里"列表类接口"的结果不得读 res.data（P-② 门禁）
   +   'src/views/erp/stock-balance/index.vue'
   ✗ t51：库存两页的仓库筛选必须用 rowsOf（列表接口），且两页都要有该断言
   共 203 条；通过 201 条；失败 2 条

B) stock-ledger 业务类型筛选改回 dictOptions('stock_biz_types')：
   ✗ 判别：erp 的 el-select 不得直接循环原始字典行（必须经 dictSelectOptions/filterOptions）
   +   'src/views/erp/stock-ledger/index.vue'
   共 203 条；通过 202 条；失败 1 条

还原后：共 203 条；通过 203 条；失败 0 条
```

### 12.3 浏览器实测（真实页面 + DOM 证据，2026-10-06 01:0x）

> 用**已有**的 `E2E4%` 夹具（qa-hardening 的用例产物：10 个启用仓库 / 76 条流水），**未新建任何数据**，故无需清理。

**A. `/erp/stock-balance`（库存明细）——仓库筛选**
- 点开「全部仓库」后，下拉选项的 DOM 文本（10 个，**都有文字**）：
  `E2E4BMUVHNZ4S一号仓 / E2E4BMUVHU07O一号仓 / E2E4CMUVHNY1O一号仓 / E2E4CMUVHTZ7T一号仓 / E2E4KMUVHO1T9一号仓 / E2E4KMUVHO1T9二号仓 / E2E4KMUVHU2P7一号仓 / E2E4KMUVHU2P7二号仓 / E2E4SMUVHO0IG一号仓 / E2E4SMUVHU1FJ一号仓`
- 选中后输入框回显：`[全部仓库] value="E2E4BMUVHNZ4S一号仓"` ⇒ **有选项且能选中** ✓

**B. `/erp/stock-ledger`（库存流水）——仓库 + 业务类型**
- 业务类型下拉的 DOM 文本（10 个字典项，**都有文字**）：`采购入库 / 退货入库 / 盘盈入库 / 其他入库 / 销售出库 / 领用出库 / 盘亏出库 / 其他出库 / 调拨出库 / 调拨入库`
  —— 修复前这里是**空条目**（t46 的 trace 里整份快照搜不到「采购入库」）。
- 选中后回显：`[全部] value="采购入库"`、`[全部仓库] value="E2E4BMUVHNZ4S一号仓"`
- 点「搜索」⇒ 列表由 **共 76 条** 变为 **共 2 条**，且只剩 `采购入库`（`IN202610000118`，夹具仓库）⇒ 选项不仅"有文字"，
  还**真的驱动了查询**（筛选链路端到端可用）✓

### 12.4 门禁（真实输出）

```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js              → 共 203 条；通过 203 条；失败 0 条      （基线 202 → +1，只增不减）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js    → 共 10 个审计；失败 0 个；未自证 0 个
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod         → DONE Build complete（无 ERROR / Failed to compile）；dist\index.html 2026-10-06 01:00:03
```

### 12.5 新增用例（1 条）

`t51：库存两页的仓库筛选必须用 rowsOf（列表接口），且两页都要有该断言` —— 正面钉住两页的 `rowsOf(res)` 与
"必须复用 `erp-response.js`"（防止有人图省事各写一份取数口径）。

---

## 13. t52 repair：P-⑤「8 类单据单位列表从未加载」（2026-10-06 01:0x）

### 13.1 根因与改动

`doc-options.js#neededOptions()` 里"需要物料 ⇒ 需要单位"的联动判定被夹在 **filters 扫描之后、headerFields/itemColumns 扫描之前**：

```js
// ❌ 修复前（顺序陷阱）
;(kind.filters || []).forEach(scan)
if (need.products) need.uoms = true        // ← 此刻 products 还没被置位
;(kind.headerFields || []).forEach(scan)
if (kind.itemColumns) {                     // ← 8 类单据的 products 正是在这里置位的
  itemColumns.forEach((col) => { if (col.kind === 'product') need.products = true })
}
```
8 类单据的物料选择器来自**行项列**，因此 `need.uoms` **恒 false** ⇒ **单位列表从未发请求**。

修复：把该联动**移到三个 scan 全部跑完之后**（并把函数抽成可单测的 CJS 模块 `views/erp/doc/doc-options-need.js`，
`doc-options.js` 原样转出口，契约不变）：

```js
// ✅ 修复后（三个 scan 之后；末尾一行，有用例钉住位置）
;(kind.filters || []).forEach(scan)
;(kind.headerFields || []).forEach(scan)
if (kind.itemColumns) { /* …products / showWarehouse… */ }
if (need.products) need.uoms = true        // ← 现在一定在 products 置位之后
```
> 为什么抽模块：`neededOptions()` 是"要不要发请求"的唯一判据，必须能被**单测直接调用**（历史教训：只靠读源码
> 文本的门禁挡不住这种顺序错误）。模块头注释把 P-⑤ 的前因后果与"联动必须写在三个 scan 之后"的纪律写在最显眼处。

### 13.2 网络证据（before / after，同一页面）

复现工具：`tests/erp-p5-mutation-tool.js`（把联动判定**移动**回扫描中间，语法仍合法 ⇒ 精确复现 P-⑤；用完还原）。

| 状态 | 打开 `/erp/stock-in/form?mode=add` 后，本次页面加载的取数（后端日志归类） |
| --- | --- |
| **P-⑤ 复现**（联动移回中间） | `selectProductList` 12 行、`selectWarehouseList` 12 行、`selectSupplierList` 6 行、`selectContractList` 6 行、**`selectUomList` 0 行** ⇒ 单位表**根本没请求** |
| **修复后** | `selectUomList` **12 行**，参数 `(1(String), 500(Integer))` = `enableFlag=1&pageSize=500`，**`<== Total: 14`** ⇒ 真的取到 14 个单位 |

### 13.3 浏览器证据（真实页面，入库单表单；**未保存 ⇒ 零写入**）

- 行 1：选物料 `E2E4BMUVHNZ4S-P0 · E2E4BMUVHNZ4S螺栓` ⇒ 行内 DOM 文本出现
  **`E2E4BMUVHNZ4S计量单位0位`**（单位快照有值，不再空白）；数量输入框由默认 `1.000` 变为 **`1`**，
  输入 `1.6` 后立即变成 **`2`** ⇒ 精度 0 生效（0 位小数的单位）。
- 行 2：选物料 `E2E4BMUVHNZ4S-P3 · E2E4BMUVHNZ4S球阀` ⇒ 行内出现 **`E2E4BMUVHNZ4S计量单位3位`**；
  输入 `1.23456` 后变成 **`1.235`** ⇒ 精度 3 生效。
- ⇒ **`uomDecimals` 非 null 且按单位生效**：0 位单位把 `1.6` 压成 `2`，3 位单位把 `1.23456` 压成 `1.235`；
  修复前 `uomDecimals === null` 会走 `DocItemsTable.vue:197-202` 的兜底 `return 3`（**两行都会是 3 位**）
  —— 这正是"用户可感知的精度错误"，不只是单元格空白。

### 13.4 门禁 + 反向对照

| 门禁 | 内容 |
| --- | --- |
| 行为门禁 | `P-⑤ 门禁：声明了 products 的 kind 必须产生 uoms 装载（8 类单据一个不落）`（直接调用 `neededOptions`；并反向断言"不需要物料时不该白拉单位表"） |
| 结构门禁 | `P-⑤ 门禁（结构）：联动推导必须写在三个 scan 之后`（剥注释后比较位置） |

**反向对照（真实输出，用 §13.2 的移动工具）**
```
$ node tests/erp-p5-mutation-tool.js src/views/erp/doc/doc-options-need.js mutate
MUTATED：已把 "if (need.products) need.uoms = true" 移到 filters 与 headerFields 之间
$ node tests/run.js
共 205 条；通过 203 条；失败 2 条
  ✗ P-⑤ 门禁：声明了 products 的 kind 必须产生 uoms 装载（8 类单据一个不落）
    AssertionError: src/views/erp/doc/doc-options-need.js：purchase_request 需要物料 ⇒ 必须同时装载单位…
  ✗ P-⑤ 门禁（结构）：联动推导必须写在三个 scan 之后
    AssertionError: src/views/erp/doc/doc-options-need.js：联动推导必须在三个 scan 之后（P-⑤…）
还原后：共 205 条；通过 205 条；失败 0 条
```
⇒ 两条门禁都会变红并**点名文件** `src/views/erp/doc/doc-options-need.js`。

### 13.5 同源普查（`neededOptions()` 里其它"A 需要 ⇒ B 需要"的联动）

逐条核对，结论：**除 uoms 外没有第二处顺序陷阱**。

| 联动 | 形态 | 结论 |
| --- | --- | --- |
| `products ⇒ uoms` | 依赖 `need.products`（**派生位**） | ❌ 曾错位（本次修复）；现已放到三个 scan 之后，并有用例钉住 |
| `itemColumns[kind=warehouse] 且 kind.showWarehouse ⇒ warehouses` | 依赖 `kind.showWarehouse`（**静态配置**，不是派生位） | ✅ 无顺序依赖（`kind` 入参即完整） |
| `suppliers/customers/products/warehouses/uoms` 由 `field.options` 置位 | 依赖字段自身声明 | ✅ 无联动 |
| `contract ⇒ purchaseContracts/saleContracts` | 依赖 `field.contractDirection`（静态） | ✅ 无联动 |
| 状态筛选 `docStatuses` | 根本不在 `neededOptions`（前端自带真源、不发请求） | ✅ 无联动 |

> 纪律（已写进模块头）：**任何"派生位 ⇒ 派生位"的联动，必须写在三个 scan 之后**；新增联动时同步加一条行为用例。

### 13.6 门禁（真实输出）与新增用例

```
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js              → 共 205 条；通过 205 条；失败 0 条    （基线 203 → +2，只增不减）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js    → 共 10 个审计；失败 0 个；未自证 0 个
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run build:prod         → DONE Build complete（无 ERROR / Failed to compile）；dist\index.html 2026-10-06 01:07:07
```
新增用例 2 条（行为 + 结构，见 §13.4）；另新增可复现工具 `tests/erp-p5-mutation-tool.js`（仅供反向对照，不被 `run.js` 加载）。
