# 04a 采购线（上）：采购申请单 / 采购单 / 申请→采购单下推 / 归零即完成 / 关联合同

> 任务：`t4`（B4 §3.1、§3.2、§3.3、§3.4、§3.6）｜实施与记录：backend-procurement
> 真源：`openspec/changes/oa-purchase-sales-stock/specs/erp/procurement/spec.md`、`tasks.md` §3、
> `doc/2.0/参考仓库-CTMS-移植清单.md` §3.9.4（下推/归零/上游守卫）、§2（字段级清单）
> 范围外（交 t4b）：§3.5 采购单→入库单下推与已入库量回写、§3.7 采购线端到端用例

---

## 1. 落地清单（改了什么文件）

| 层 | 文件（`ruoyi-vue-oa-master/ruoyi-ctms`） | 说明 |
| --- | --- | --- |
| 规则 | `src/main/java/com/ruoyi/ctms/erp/procurement/ErpPurRules.java` | 剩余量/归零/下推量、供应商与合同方向、行项校验、**全部中文提示文案**的唯一实现点 |
| domain | `erp/procurement/domain/ErpPurchaseRequest.java`、`ErpPurchaseRequestItem.java`、`ErpPurchaseOrder.java`、`ErpPurchaseOrderItem.java`、`ErpPushLine.java`、`domain/vo/ErpPushResultVo.java` | 继承 base 的 `ErpDocHeader`/`ErpDocItem`，只声明特有列 |
| mapper | `erp/procurement/mapper/ErpPurchaseRequestMapper.java`、`ErpPurchaseRequestItemMapper.java`、`ErpPurchaseOrderMapper.java`、`ErpPurchaseOrderItemMapper.java` | 含 `increaseOrderedQty`（库内自增）、`increaseReceivedQty`/`decreaseReceivedQty`（t4b 用） |
| XML | `src/main/resources/mapper/erp/ErpPurRequestMapper.xml`、`ErpPurRequestItemMapper.xml`、`ErpPurOrderMapper.xml`、`ErpPurOrderItemMapper.xml` | 单据表别名统一 `d`（与 base `ErpDocScope` 默认别名一致，见 §7 踩坑 ③） |
| service | `erp/procurement/service/IErpPurchaseRequestService.java`、`IErpPurchaseOrderService.java`、`IErpPurchasePushService.java` + `service/impl/ErpPurchaseRequestServiceImpl.java`、`ErpPurchaseOrderServiceImpl.java`、`ErpPurchasePushServiceImpl.java` | 状态流转、编辑、行项维护、下推、归零判定 |
| support | `erp/procurement/support/ErpPurServiceSupport.java`（基类：上下文/数据范围/守卫）、`ErpPurDocNoGenerator.java`（base 取号 + 撞号重试）、`ErpPurChangeLogWriter.java`（`t_ctms_change_log` 写入点） | 只消费 base / B3 的既有能力 |
| controller | `erp/procurement/controller/ErpPurchaseRequestController.java`、`ErpPurchaseOrderController.java` | 端点与权限点见 §5 |
| 单测 | `src/test/java/com/ruoyi/ctms/erp/procurement/ErpPurRulesTest.java`（21 条）、`ErpPurchaseServiceImplTest.java`（24 条） | 内存桩 Mapper + 内存桩主数据/供应商/合同/编号，**不起 Spring** |
| 临时文件 | 无（探针 `TmpProbeTest.java` 已删除，class 也已从 `target/test-classes` 清掉） | 简报 §3.6：`.java/.xml/.md/.sql` 一律无 BOM |

**公共层消费方式（简报 §3.8：base 只消费、不复刻）**：状态机 `ErpDocStateMachine`、
状态枚举 `ErpDocStatus`、单据类型 `ErpDocType`、金额精度 `ErpAmounts`、
数据范围 `ErpDocScope`（四档语义与 B3 的 `ContractDataScope` **刻意不同**，B4 用 base 这份）、
行项守卫与快照 `ErpMasterGuards.applyItemSnapshot`、取号 `ErpDocNoGenerator`（配置 id 与 PR/PO 前缀在那里冻结）。
本组**没有**自写状态机 / 金额口径 / 数据范围 / 编号。

---

## 2. 状态流转图（与 base 冻结矩阵逐字一致）

```
                    ┌──────────────── 驳回（原因必填） ────────────────┐
                    ▼                                                │
   draft ──submit──► submitted ──approve──► approved ──complete──► completed
     │  （必须有行项）   │                      │                         │
     │                 │                      │                         │
     └──void──┐        └──void──┐             └──unapprove──► submitted   │
   （原因必填）▼      （原因必填）▼                              ▲          │
              voided            voided                          └──unapprove──┘
                                                                 （原因必填，回到 draft）
```

- 采购申请单与采购单**同构**（都不是库存类单据，都提供「置为已完成」）。
- 流转判定只有一处：`ErpDocStateMachine.checkAndNext(action, status, docType, hasItems, reason)`；
  服务层不写 `if (status.equals(...))`。
- 采购线的**例外**（本次新增，仅 t4 范围内）：`unapprove` 前先查"下游采购单是否存在未作废的"，
  存在则拒绝（文案见 §4），这是规格「下游存在时禁止反审核」。
- `unapprove` 会清空 `approved_by/approved_at`（DDL 注释口径）。

**审核 / 反审核前置条件（交付要求之一）**

| 动作 | 前置条件 | 被拒文案（逐字） |
| --- | --- | --- |
| 提交 | 状态=草稿 **且至少一行行项** | `单据没有行项，不能提交` |
| 审核 | 状态=待审核 | `当前状态（X）不可执行「审核」` |
| 驳回 | 状态=待审核 **且原因非空** | `驳回原因必填` |
| 作废 | 状态=草稿或待审核 **且原因非空** | `作废原因必填` |
| 反审核 | 状态=已审核或已完成 **且原因非空** **且无未作废下游** | `反审核原因必填` / `已存在下游采购单，请先处理下游单据（反审核或作废）后再操作` |
| 置为已完成 | 状态=已审核 | `当前状态（X）不可执行「置为已完成」` |
| 编辑 | 状态=草稿 | `当前状态（X）不可编辑，请先反审核或作废` |
| 归零即完成 | 状态=已审核 **且全部行项剩余量=0** | 自动置 `completed`，不报错 |

---

## 3. 下推步骤（申请 → 采购单）

```
① 取申请单（不可见 → 业务码 403）；状态必须 approved，否则 422「仅已审核的采购申请单可以下推采购单」
② 解析下推计划（"行序对应"只在这一步解释一次，落库阶段只消费计划）
     · lines 为空/未传 → 全部行项，各按剩余量全推
     · 某项 srcItemId 为空 → 按行序对应到下一条未匹配的来源行
     · srcItemId 非空但不在来源行里 → 拒绝「下推行项不属于该采购申请单」（不静默跳过）
     · 同一来源行重复出现 → 合并为一条（取后者的数量），防"同一行推两次"绕过剩余量
③ 逐行算量并校验（全部算完才落库 ⇒ 失败路径零中间写入）
     剩余量 = qty − ordered_qty（不小于 0）
     · 剩余量 ≤ 0 → 422「已无可下推数量（剩余 0）」（与销售线同文案）
     · 本次量 > 剩余量 → 422「采购申请单「<物料>」可下推数量不足（剩余 R，本次 Q）」
     · 不填数量 → 按剩余量全推
④ 生成草稿采购单：status=draft、source_doc_type=purchase_request、source_doc_id/no 写来源单号、
     每行 src_item_id = 来源行项ID；默认带过 contract_id/contract_no、purchase_dept_id=申请的需求部门、
     expected_arrival_date=申请的需求日期、handler 快照；total_amount=各行"先舍入再汇总"
     同时校验供应商（必填/存在/启用）与合同（采购方向、未停用）——供应商或合同不合格则整单不下推
⑤ 立即累加申请行 ordered_qty（库内自增 `ordered_qty = ordered_qty + #{qty}`，并发安全）
⑥ 归零判定：全部行项剩余量=0 → 申请单置 completed + 一条变更历史（幂等）
⑦ 返回 ErpPushResultVo：草稿采购单、sourceDocNo、autoCompleted、sourceStatus、remainQtySum、
     pushedQty、可直接展示的 message
```

**事务边界**：①~⑦ 在**一个** `@Transactional` 里；超量时在写任何数据**之前**就抛错，
所以"被拒后已下单量仍为 60、采购单表不变"是结构性成立的（不是靠回滚兜底）。

**归零语义边界**：空行项集合**不算**归零（没有行项的单据连提交都不合法）；只有
`status=approved` 且全部剩余量为 0 才自动完成。已完成再判定直接返回 `false`（幂等，不重复留痕）。

---

## 4. 提示文案（前端 / 验收脚本按这些字符串断言）

| 场景 | 文案（逐字） |
| --- | --- |
| 超量下推 | 采购申请单「球阀」可下推数量不足（剩余 40，本次 50） |
| 未填数量但已无剩余 | 已无可下推数量（剩余 0） |
| 下推量 ≤ 0 | 下推数量必须大于 0 |
| 申请单未审核即下推 | 仅已审核的采购申请单可以下推采购单 |
| 下推行项不属于该申请单 | 下推行项不属于该采购申请单 |
| 归零留痕备注（变更历史 time-line 直接展示） | 全部行项剩余可下推量为 0，自动置为已完成 |
| 下推成功 | 已生成采购单 PO202610000001（草稿），本次合计下推 40 |
| 下推并触发归零 | 已生成采购单 PO202610000001（草稿），本次合计下推 40；申请单已全部下推完毕，自动置为已完成 |
| 传客户 ID 到供应商字段 / 供应商不存在 | 供应商档案不存在 |
| 停用供应商 | 供应商已停用，不能用于新单据 |
| 采购单未选供应商 | 采购单必须选择供应商 |
| 关联销售方向合同（或合同不存在） | 关联合同不存在或方向不符 |
| 关联已停用合同 | 关联合同已停用 |
| 存在下游时反审核 | 已存在下游采购单，请先处理下游单据（反审核或作废）后再操作 |
| 无行项 / 数量 0 / 单价负 / 精度超 | 单据至少需要一行行项 / 行 N：数量必须大于 0 / 行 N：单价不得为负数 / 行 N：数量的最小单位是 X 位小数，当前为 Y 位 |
| 停用物料 | 行 N：物料「螺丝」已停用，不能用于新单据（文案由 base `ErpMasterGuards` 提供） |

---

## 5. 端点与权限点（交 t10 复核 / t8 建菜单）

权限点命名按简报 §9 冻结：`pur:request:<动作>` / `pur:order:<动作>`；
动作集 `list/query/add/edit/remove/status/submit/approve/unapprove/void/push`（驳回与审核共用 `approve`）。

| # | 方法 | 路径 | 权限点 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/erp/pur/request/list` | `pur:request:list` | 分页列表（状态/关键词/日期/部门/创建人筛选；数据范围） |
| 2 | GET | `/erp/pur/request/{id}` | `pur:request:query` | 详情（含行项 + 变更历史 + 派生列） |
| 3 | GET | `/erp/pur/request/{docId}/items` | `pur:request:query` | 行项列表（含剩余量） |
| 4 | GET | `/erp/pur/request/{docId}/change-logs` | `pur:request:query` | 变更历史 |
| 5 | GET | `/erp/pur/request/contract-options` | `pur:request:query` | 采购方向可用合同下拉 |
| 6 | POST | `/erp/pur/request` | `pur:request:add` | 新增（草稿） |
| 7 | PUT | `/erp/pur/request` | `pur:request:edit` | 编辑（仅草稿） |
| 8 | DELETE | `/erp/pur/request/{id}` | `pur:request:remove` | 删除（仅草稿；夹具/回滚用） |
| 9 | POST | `/erp/pur/request/{docId}/items` | `pur:request:edit` | 行项新增 |
| 10 | PUT | `/erp/pur/request/{docId}/items` | `pur:request:edit` | 行项修改 |
| 11 | DELETE | `/erp/pur/request/{docId}/items/{itemId}` | `pur:request:edit` | 行项删除 |
| 12 | PUT | `/erp/pur/request/{id}/submit` | `pur:request:submit` | 提交 |
| 13 | PUT | `/erp/pur/request/{id}/approve?action=approve\|reject&reason=` | `pur:request:approve` | 审核 / 驳回（`reject` 必填 reason） |
| 14 | PUT | `/erp/pur/request/{id}/unapprove?reason=` | `pur:request:unapprove` | 反审核（reason 必填；有下游则 422） |
| 15 | PUT | `/erp/pur/request/{id}/void?reason=` | `pur:request:void` | 作废（reason 必填） |
| 16 | PUT | `/erp/pur/request/{id}/complete` | `pur:request:status` | 置为已完成 |
| 17 | PUT | `/erp/pur/request/{id}/check-auto-complete` | `pur:request:status` | 归零判定（幂等） |
| 18 | POST | `/erp/pur/request/{id}/push/purchase-order?supplierId=&remark=` | `pur:request:push` | 下推（body 为可空的 `[{srcItemId,qty}]`） |
| 19 | GET | `/erp/pur/order/list` | `pur:order:list` | 采购单列表 |
| 20 | GET | `/erp/pur/order/{id}` | `pur:order:query` | 采购单详情 |
| 21 | GET | `/erp/pur/order/{docId}/items` | `pur:order:query` | 采购单行项 |
| 22 | GET | `/erp/pur/order/{docId}/change-logs` | `pur:order:query` | 采购单变更历史 |
| 23 | GET | `/erp/pur/order/contract-options` | `pur:order:query` | 合同下拉 |
| 24 | POST | `/erp/pur/order` | `pur:order:add` | 新增采购单 |
| 25 | PUT | `/erp/pur/order` | `pur:order:edit` | 编辑采购单（仅草稿） |
| 26 | DELETE | `/erp/pur/order/{id}` | `pur:order:remove` | 删除（仅草稿） |
| 27 | POST/PUT/DELETE | `/erp/pur/order/{docId}/items[...]` | `pur:order:edit` | 采购单行项维护（与申请单同构） |
| 28 | PUT | `/erp/pur/order/{id}/submit` | `pur:order:submit` | 提交 |
| 29 | PUT | `/erp/pur/order/{id}/approve?action=&reason=` | `pur:order:approve` | 审核 / 驳回 |
| 30 | PUT | `/erp/pur/order/{id}/unapprove?reason=` | `pur:order:unapprove` | 反审核 |
| 31 | PUT | `/erp/pur/order/{id}/void?reason=` | `pur:order:void` | 作废 |
| 32 | PUT | `/erp/pur/order/{id}/complete` | `pur:order:status` | 置为已完成 |
| 33 | **GET/POST** | `/erp/pur/request/export` | `pur:request:export` | 导出（**Excel 二进制流**；与列表同筛选同范围；见 §5.2） |
| 34 | **GET/POST** | `/erp/pur/order/export` | `pur:order:export` | 导出（同上） |
| 35 | POST | `/erp/pur/order/{id}/push/stock-in?warehouseId=&remark=` | `pur:order:push` | 采购单 → 入库单下推（t7/§3.5；见 `notes/04b-procurement-push-in.md`） |

> 说明：`print` 权限点本组不建端点（打印走平台 B2 既有打印入口）；`export` 的两个端点由审计 F1 补齐（t27），
> 形态与前端 `src/api/erp/doc.js#exportDocs` 的调用方式对齐（见 §5.2）。

### 5.2 导出端点（t27 / 审计 F1）：响应形态、列清单、前端对接点

**响应形态＝`ExcelUtil` 写出的 xlsx 二进制流**（不是 JSON、不是 `.rows`）。依据（**先读前端再定形态**）：

| 环节 | 事实 | 出处 |
| --- | --- | --- |
| 前端 API | `exportDocs(kind, query, filename)` → `download(kind.restBase + '/export', query, filename)` | `ruoyi-vue-oa-ui-master/src/api/erp/doc.js:120` |
| 前端下载器 | `service.post(url, params, { transformRequest: tansParams, headers: {'Content-Type':'application/x-www-form-urlencoded'}, responseType: 'blob' })`，拿到 blob 直接 `saveAs(blob, filename)`；**不是 blob**（例如 JSON）时读文本、按 `rspObj.code` 弹错误提示 | `ruoyi-vue-oa-ui-master/src/utils/request.js:134` |
| 前端页面 | 导出按钮 `v-hasPermi="[permOf('export')]"`；`handleExport()` 传**当前筛选**（`queryParams` + `beginDocDate`/`endDocDate`） | `src/views/erp/doc/DocListShell.vue:64,479` |
| 同族先例 | T3 的入库/出库导出同样用 `ExcelUtil` + `@RequestMapping(method={GET,POST})`（列定义在 `@Excel` 注解里） | `erp/posting/controller/ErpStockInController.java:83` |

⇒ 因此本组两个导出端点：**`@RequestMapping(value="/export", method={GET, POST})`** ——
**POST 是前端真正会用的那个**（form-urlencoded + blob），GET 保留给脚本/人工复跑与验收脚本；
返回体是 xlsx 流。**若哪天要改成 JSON `.rows`，必须同时改前端 `utils/request.js#download` 的调用方式**，
否则就是 B3 踩过的"点导出报错"。这条已报文 captain 转告 frontend-erp。

**取数与范围**：`exportRows(query)` 直接把列表用的 `query` 交给**与列表相同的服务方法**
（`selectRequestList` / `selectOrderList`），范围片段由服务内的 `ErpDocScope` 生成 —— 本层**不**
另写范围判定。传了 `query.id` 时走**详情入口**（`selectRequestDetail` / `selectOrderDetail`）：
存在性 + 四档范围，范围外抛业务码 **403**（响应体 `{"code":403,...}`，前端会按错误提示处理，不返回数据）。

**列清单**（真源＝两个控制器内嵌的 `ErpPur*ExportRow` 的 `@Excel` 注解；**一行 = 一个行项**，
没有行项的单据也占一行以保证不漏单）：

- 采购申请单：单号、单据日期、状态、归属部门、需求部门(ID)、需求日期、建议供应商(ID)、合同编号、
  用途、剩余可下推量合计、金额合计、备注、创建人 ｜ 行号、物料编码、物料名称、规格、单位、数量、单价、
  行金额、已下推数量、剩余可下推量
- 采购单：单号、单据日期、状态、供应商、采购部门(ID)、预计到货日期、结算方式、币种、默认收货仓库、
  合同编号、来源单号、金额合计、剩余可入库量合计、备注 ｜ 行号、物料编码、物料名称、规格、单位、数量、
  单价、行金额、已入库数量、剩余可入库量

**金额一致性**：行金额 = `ErpAmounts.lineAmount(qty, unitPrice)`（先 HALF_UP 到 2 位），
合计列与列表返回的 `totalAmount` **同源**（申请单＝服务现算、采购单＝落库列）；
三行 `1×0.125` ⇒ 行 0.13、合计 0.39（AC-78 的"导出"一方与列表一致）。

### 5.1 接口断言清单（请求 + 期望）——交 t10 复核用

> 形态：`{方法 路径 | 前置 | 请求 | 期望}`。全部可用 `superAdmin` 直接跑（E2E 走超管）。
> 变量：`P` = 一条启用物料（单位小数位 3，如「球阀」）、`P0` = 小数位 0 的物料（如「螺栓」）、
> `S` = 启用供应商、`SX` = 停用供应商、`C` = 客户、`K` = 采购方向合同、`KS` = 销售方向合同、
> `KX` = 已停用采购方向合同。

**A. §3.1 CRUD 与行项口径**

| # | 请求 | 期望 |
| --- | --- | --- |
| A1 | `POST /erp/pur/request` body `{docDate:"2026-10-05", purpose:"阀门采购", items:[{productId:P,qty:10,unitPrice:2.5}]}` | `code=200`，`data` 为主键；列表能按 `keyword=阀门` 查到；`totalAmount=25.00`、`remainQtySum=10`、`canPush=true`、`status=draft` |
| A2 | `POST /erp/pur/request` body `{items:[]}` | `code=500`（业务异常），`msg="单据至少需要一行行项"`，未落库 |
| A3 | `POST /erp/pur/request` body `{items:[{productId:P0,qty:1.5,unitPrice:1}]}` | 被拒，`msg` 含 `行 1：数量的最小单位是 0 位小数` |
| A4 | `POST /erp/pur/request` body `{items:[{productId:P,qty:0,unitPrice:1}]}` | 被拒，`msg="行 1：数量必须大于 0"` |
| A5 | `POST /erp/pur/request` body `{items:[{productId:P,qty:1,unitPrice:-1}]}` | 被拒，`msg="行 1：单价不得为负数"` |
| A6 | 三行 `{qty:1,unitPrice:0.125}` | 详情 `items[*].amount=0.13`、`totalAmount=0.39`（不是 0.38） |
| A7 | `POST` 行项 `productId=停用物料` | 被拒，`msg` 含 `行 1` 与 `已停用` |
| A8 | `GET /erp/pur/request/list`（已作废单据存在） | 默认不含已作废；`?status=voided` 能查到；`?includeVoided=1` 也能查到 |
| A9 | `PUT /erp/pur/request` 改已审核单据的行项 | 被拒，`msg` 含 `不可编辑` |
| A10 | `POST /erp/pur/request` 后改物料名，再查该单据详情 | 行项仍显示**旧**物料名/编码/单位快照 |

**B. §3.2 供应商与收货信息**

| # | 请求 | 期望 |
| --- | --- | --- |
| B1 | `POST /erp/pur/order` body `{supplierId:C, items:[{productId:P,qty:1,unitPrice:1}]}` | 被拒，`msg="供应商档案不存在"` |
| B2 | `POST /erp/pur/order` body `{supplierId:SX, ...}` | 被拒，`msg="供应商已停用，不能用于新单据"`；库里没有新采购单 |
| B3 | `POST /erp/pur/order` body `{supplierId:S, receiptWarehouseId:W, expectedArrivalDate:"2026-10-20", settleType:"月结30天", items:[...]}` | 成功；详情 `supplierName` = 供应商名快照、`receiptWarehouseName` 有值、`currency=CNY`、`totalAmount` = 各行舍入后之和 |
| B4 | `POST /erp/pur/order` 未传 `supplierId` | 被拒，`msg="采购单必须选择供应商"` |

**C. §3.3 下推**

| # | 请求 | 期望 |
| --- | --- | --- |
| C1 | 对 `draft` 申请单 `POST .../{id}/push/purchase-order?supplierId=S` | 被拒，`msg="仅已审核的采购申请单可以下推采购单"` |
| C2 | 申请行 `qty=100`、已下单 60；`POST .../push/purchase-order?supplierId=S` body `[{srcItemId:i1,qty:50}]` | 被拒，`msg` 含 `剩余 40` 与 `本次 50`；申请行 `orderedQty` 仍 60；采购单表未新增 |
| C3 | 承 C2，`POST .../push/purchase-order?supplierId=S`（body 空/不传） | 成功；`pushedQty=40`、生成草稿采购单、`sourceDocNo`=申请单号、`sourceDocType=purchase_request`、行 `qty=40`、`srcItemId`=申请行ID；申请行 `orderedQty=100` |
| C4 | 承 C3，`GET` 申请单详情 | `status=completed`（归零即完成）、`remainQtySum=0`、`canPush=false`；变更历史恰有 1 条 note=`全部行项剩余可下推量为 0，自动置为已完成`（source=auto，old=approved，new=completed） |
| C5 | 再调 `PUT .../check-auto-complete` | `code=200`、`data=false`；变更历史仍只有 1 条（幂等） |
| C6 | 申请单带 `contractId=K`、`requestDeptId`、`needDate`，下推 | 采购单 `contractId/contractNo` 与申请一致、`purchaseDeptId`=申请需求部门、`expectedArrivalDate`=需求日期 |
| C7 | 两行申请单，只推完第一行 | `autoCompleted=false`；申请单状态仍 `approved`；`remainQtySum>0` |
| C8 | `POST` 下推 body `[{srcItemId:"不存在的ID"}]` | 被拒，`msg="下推行项不属于该采购申请单"` |

**D. §3.6 关联合同**

| # | 请求 | 期望 |
| --- | --- | --- |
| D1 | `POST /erp/pur/request` body `{contractId:KS, items:[...]}` | 被拒，`msg="关联合同不存在或方向不符"` |
| D2 | 同上但 `contractId=KX`（已停用） | 被拒，`msg="关联合同已停用"` |
| D3 | 同上但 `contractId=K` | 成功；详情 `contractNo` = 合同编号快照；`GET /ctms/contract/{K}` 显示合同任何字段**未被修改** |
| D4 | `GET /erp/pur/request/contract-options` | 只返回"未停用且乙方为供应商"的合同，不含销售方向合同 |

**E. 状态流转与下游守卫**

| # | 请求 | 期望 |
| --- | --- | --- |
| E1 | `PUT .../{id}/submit`（无行项单据） | 被拒，`msg="单据没有行项，不能提交"` |
| E2 | `PUT .../{id}/approve?action=reject`（无 reason） | 被拒，`msg="驳回原因必填"`；补齐 reason 后回到 `draft` |
| E3 | 已下推且下游采购单为 `approved` 时 `PUT .../{id}/unapprove?reason=x` | 被拒，`msg="已存在下游采购单，请先处理下游单据（反审核或作废）后再操作"` |
| E4 | 把下游采购单作废后再 `unapprove` | 成功，申请单回到 `submitted` |
| E5 | 未登录 / 无 `pur:request:list` 权限访问列表 | 401 / `code=403`（权限点由 t8 建菜单后生效） |
| E6 | 用范围外账号 `GET /erp/pur/request/{id}` | 响应体业务码 `403`（`AjaxResult.error(403,...)`，HTTP 仍 200） |

---

## 6. L1 证据（真实输出）

命令（一律走构建锁；本机 `powershell` = 5.1）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 `
  -LockName build -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"
```

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -B -pl ruoyi-ctms test -Dtest='ErpPurRulesTest,ErpPurchaseServiceImplTest'` | 两轮修完用例后 `Tests run: 45, Failures: 0, Errors: 0` / BUILD SUCCESS |
| 2 | 同上（取号改为消费 base `ErpDocNoGenerator` 之后） | `ErpPurchaseServiceImplTest 24/24`、`ErpPurRulesTest 21/21` / BUILD SUCCESS |
| 3 | `mvn -B -pl ruoyi-ctms test`（**全模块**） | `Tests run: 471, Failures: 2, Errors: 0`；**采购线 46 条全绿**（`ErpPurchaseServiceImplTest 25/25`、`ErpPurRulesTest 21/21`）；当时仅剩 2 条失败在 **stockops**（t9 范围，已报文 captain） |
| 4 | `mvn -B -pl ruoyi-ctms test`（**收尾复跑，最终证据**） | **`Tests run: 471, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**；其中 `ErpPurchaseServiceImplTest` **25/25**、`ErpPurRulesTest` **21/21**（stockops 的 2 条也已由 t9 修绿） |
| 5 | 期间被他人在建文件挡住的重试 | 先后撞上 `CtmsProductMasterServiceImpl`、`ErpSalesServiceImplTest`、`CtmsAttachmentServiceImplTest`、`ErpLedgerPrecisionTest`、`ErpStocktakeServiceImpl`、`ErpSalesRequest`、`ErpSalesRequestServiceImpl` 的编译错误；按简报 §3.1 不替他人改文件，重试到可编译即重跑，**最终一轮全绿** |

> L1 增量：B3 基线 217 条 → 全模块 471 条，其中采购线新增 **46 条**（21 + 25），**只增不减**。
> 归零/下推/超量/幂等/方向/快照/清痕这些断言全部在内存桩单测里，不依赖数据库与 Spring。

> L1 增量：B3 基线 217 条 → 本轮采购线新增 **46 条**（21 + 25），**只增不减**。
> 归零/下推/超量/幂等/方向/快照/清痕这些断言全部在内存桩单测里，不依赖数据库与 Spring。
>
> **t27（导出端点）追加**：`ErpPurExportTest` **+9 条**（54 → 见下），
> 全模块 `mvn -B -pl ruoyi-ctms test` = **Tests run: 531, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**；
> 采购线 64 条全绿（`ErpPurExportTest 9`、`ErpPurchasePushInTest 9`、`ErpPurchaseServiceImplTest 25`、
> `ErpPurRulesTest 21`）。t27 的 9 条覆盖：
> ① 导出与列表**同一取数入口**（`assertSame(query, mapper.lastQuery)`）且服务把 `ErpDocScope`
> 片段写进导出 query；② 关键词/日期区间/状态筛选透传；③ 范围外单据不进导出；
> ④ 按 `id` 导出范围外单据 ⇒ 业务码 **403**；⑤ 申请单 xlsx 表头含全部表头+行项列；
> ⑥ 采购单 xlsx 表头含供应商/收货信息/已入库列且值正确；⑦ 三行 `1×0.125` 的 xlsx 单元格
> 行金额 `0.13`、合计 `0.39`、行和 = 合计（**真实 ExcelUtil + POI 读回**）；
> ⑧ 采购单导出合计 = 列表合计；⑨ **反射**断言导出方法声明为 `GET+POST /export` 且
> `@PreAuthorize` 逐字为 `@ss.hasPermi('pur:request:export')` / `@ss.hasPermi('pur:order:export')`（前端 POST 契约 + 菜单权限点）。

### 6.1 单测抓不到、本组另外补做的三项"真库/真框架"校验

桩单测不解析 SQL、不校验外键、不执行 OGNL，所以下面三项是**额外**做的：

| 校验 | 做法 | 结果 |
| --- | --- | --- |
| XML 列名对真库 | 把 4 个 XML 的 `<sql id="*Vo">` 语句抽出来，对 `rad_oa` 执行 `select ... where 1 = 0` | 4/4 `exit 0`（含 `left join sys_dept` 与 `greatest(0, qty - ordered_qty)`） |
| 列名逐列比对 | 用 `information_schema.columns` 逐个核对 XML 里的 insert 列清单、update 赋值列、where/order 条件列 | 4 个表 **全 OK**（无一处拼错列名） |
| 接口 ↔ 语句绑定 | 逐个比对 Mapper 接口方法名与 XML statement id | 8/8、9/9、10/10、9/9，**无缺语句/缺方法**（排除 `Invalid bound statement`） |
| 动态 SQL 的 OGNL | 用 MyBatis 自带的 `ExpressionEvaluator` 直接跑 `!(status != null and status == "voided") and (includeVoided == null or includeVoided != "1")` | 四种取值全部符合预期（默认排除作废 / `status=voided` 放开 / `includeVoided=1` 放开） |
| XML 结构 | `XmlDocument.Load` 解析 4 个 XML（含 DTD 声明） | 全部合法，语句数 8/9/10/9 与接口一致 |
| 导出列（t27） | 用真实 `ExcelUtil` 把导出行写成 xlsx，再用 POI `XSSFWorkbook` 读回表头与单元格 | 表头列名与 `@Excel` 声明一致、三行 `0.13`/合计 `0.39` 逐格核对（见 §6 的 t27 条目） |

L2（静态审计，只读，可并发）：

```powershell
cd F:\dsh\ruoyiOA; node tools\audit\run-all.js
```

实测输出（本任务期间）：`共 10 个审计；失败 0 个；未自证 0 个`（含 `then-loaders` / `async-loaders` 的 `zero` 门禁）。

---

## 7. 踩坑与口径（评审与后续接力必读）

1. **`checkAutoComplete` 的留痕旧值**（真实缺陷写法，已修）：
   最初写成"先 `updateRequestStatus` 落状态，再用 `exist.getStatus()` 写变更历史的旧值"。
   生产里 `exist` 是单独查出来的对象，看不出问题；**内存桩里 `exist` 与落库行是同一引用**，
   于是留痕写成 `old=completed` —— 单测立刻抓到了。
   修法：**流转前先把 `oldStatus` 取到局部变量**再写留痕（两个服务的 `changeStatus` 也一并改了）。
   教训：不要依赖"查出来的对象与落库行不是同一个引用"这种隐含假设。
2. **行项必须写在主表之后**：`t_ctms_purchase_request_item.doc_id`／`purchase_order_item.doc_id`
   都是非空外键，顺序反了在真库上是 1452；内存桩单测**抓不到**（桩不校验外键）。
   因此两个 service 的 insert 都是 `insertXxx` → `persistItems`，注释里都写了理由。
3. **数据范围片段的表别名**：`ErpDocScope` 默认别名 `d`，XML 里单据表别名**必须**也是 `d`，
   否则真库上报 `Unknown column 'd.create_id'`，而桩单测同样发现不了（不解析 SQL）。
4. **金额"先舍入再汇总"**：只有 `ErpAmounts` 一处实现；三行 `1 × 0.125` ⇒ 行 `0.13`、合计 `0.39`。
   采购单的 `total_amount` 落库（列表/打印/导出共用一份值）；采购申请单**没有该列**，
   `totalAmount` 由行项现算（与列表一致）。
5. **数量精度校验的时机**：必须在数量被 `setScale(3)` 归整**之前**做，
   否则 `1.5000`（单位 0 位小数）会被洗成 `1.500` 而蒙混过关。
   实现上把"精度校验"放进 base 的 `ErpMasterGuards.applyItemSnapshot`（它拿的是入参原值），
   规整放在其后。
6. **已下单量只能由下推写**：编辑/行项维护传入的 `orderedQty` 一律忽略（否则可以改小再超推）；
   累加用库内自增 `ordered_qty = ordered_qty + #{qty}`（并发下"读出来再写"会少累加）。
7. **单位小数位来源**：`t_ctms_product.uom_id → t_ctms_uom.decimals`，由 base 的
   `ErpMasterLookupImpl` 提供；本组不改 B3 的 Mapper/接口。
8. **取号**：只走 base `ErpDocNoGenerator`（配置 id `…C101`/`…C102`，前缀 `PR`/`PO`）；
   本组只加"撞号重试"（单号列唯一索引 + 编号计数器落后于库内水位时避免 1064/1062 形态的 500）。
9. **权限点不含"驳回"**：状态机里 `reject` 与 `approve` 是两个动作，权限上是同一个 `approve`，
   用 `?action=reject` 区分（简报 §9 冻结）。
10. **空行项不算归零**：`isFullyOrdered(null/空)` 返回 `false`；否则"没有行项的草稿"会被自动完成而不再可编辑。

---

## 8. 交后续（t4b / t10 / t11）

- **t4b（§3.5）**：本任务已把口径钉在
  `IErpPurchaseOrderService.applyReceivedQtyChange(orderId, itemId, deltaQty)`（唯一写入口，
  正=累加、负=回退且不小于 0，并自带"不得超过剩余可入库量"的兜底），
  XML 侧对应 `increaseReceivedQty` / `decreaseReceivedQty`（后者用 `greatest(0, ...)`）。
  **接线方式按 captain 通报**：实现 `IErpStockPostingListener`（`supports(STOCK_IN)`，
  `afterPosted` → `applyReceivedQtyChange(+qtyChange)`，
  `afterReversed` → `applyReceivedQtyChange(-originalQtyChange)`），
  在同一事务内被回调；**不要**自己写过账逻辑或第二套监听。
  下推入库单时（§3.5）需要：仅 `approved`/`completed` 采购单可推、剩余可入库量校验、
  生成草稿入库单并带收货仓库与来源单号（`source_doc_type=purchase_order`、行 `src_item_id`）、
  **下推本身不写 `received_qty`**。

  接线草稿（形状，未落盘；落盘时放在 `erp/procurement/support/` 下）：

  ```java
  @Component
  public class ErpPurchaseReceiptListener implements IErpStockPostingListener {
      @Autowired private IErpPurchaseOrderService orderService;
      @Autowired private ErpPurchaseOrderItemMapper itemMapper;   // 由 srcItemId → (orderId, itemId)

      @Override public boolean supports(ErpDocType t) { return t == ErpDocType.STOCK_IN; }

      @Override public void afterPosted(ErpDocType t, String docId, List<ErpPostedLine> lines) {
          for (ErpPostedLine line : lines) {          // srcItemId = 采购单行项ID（下推时写入）
              OrderItemRef ref = itemMapper.selectRefByItemId(line.getSrcItemId());
              if (ref != null) { orderService.applyReceivedQtyChange(ref.orderId, ref.itemId, line.getQtyChange()); }
          }
      }
      @Override public void afterReversed(ErpDocType t, String docId, List<ErpPostedLine> lines) {
          for (ErpPostedLine line : lines) {          // 红冲回退用原变动（正数），本入口内部取负
              OrderItemRef ref = itemMapper.selectRefByItemId(line.getSrcItemId());
              if (ref != null) { orderService.applyReceivedQtyChange(ref.orderId, ref.itemId,
                      line.getOriginalQtyChange().negate()); }
          }
      }
  }
  ```

  三个要点：① 监听器内**不复核剩余量**（`applyReceivedQtyChange` 已兜底，重复校验会两套口径）；
  ② `srcItemId` 为空的流水（手工建的入库单）**跳过**，不报错；
  ③ 抛异常 → 整个过账回滚（这正是我们要的：回写失败不能让"过了账但采购单没记量"的中间态落库）。
- **t10（集成断言脚本）**：§5.1 的 A~E 清单可直接落成 `tools/erp-procurement-check.ps1`
  的用例；断言字符串取 §4 的表（逐字）。**导出**另有 3 条可复跑的断言：
  ① `POST {base}/export` 带筛选（form-urlencoded）→ 响应 `Content-Type` 为 xlsx 且字节流非空（`file` 魔数 `PK`）；
  ② 同一筛选下"列表条数 × 行项数 = 导出数据行数"；③ 用范围外账号 / 范围外 `id=` 调导出 ⇒ 响应体 `{"code":403,...}` 且无数据。
- **t11（E2E）**：主链路为
  `新增申请单 → 提交 → 审核 → 下推（选供应商）→ 采购单提交 → 审核 →（t4b）下推入库单 → 审核过账`
  → 库存明细出现该物料行。前端注意：`canPush=false` 或剩余量 0 时隐藏下推入口；
  `autoCompleted=true` 时展示 `message` 并刷新申请单状态；**导出按钮**已可用（§5.2 的响应形态）。
- **已知边界（未做，非缺陷）**：`print` 端点未建（打印走平台 B2 既有打印入口）；
  行项"提取合同全部行项"（BR-V2.1-03）不在 3.x 任务内。
  ~~导出端点未建~~ —— **已由 t27 补齐**（`GET/POST /erp/pur/{request,order}/export`，见 §5.2）。
