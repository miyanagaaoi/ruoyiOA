/**
 * 8 类进销存单据的**配置驱动清单**（B4 §8.3 的"8 类单据只写壳 + 专有字段"落地处）。
 *
 * 为什么是"配置"而不是 8 个页面：
 *   参考仓库用 `DocListPage` / `DocFormPage` 两个壳撑起 8 类单据的 16 个页面；本项目 D12
 *   沿用同一结构。8 类单据的差异**只有**：单号前缀文案、专有表头字段、专有行项列、
 *   是否显示仓库/单价/金额、下推目标。把这些写进本文件，页面侧就只剩"壳 + 插槽"。
 *
 * 字段名真源（**不得自己发明**）：
 *   `ruoyi-vue-oa-master/sql/二开-进销存.sql` 的 18 张建表列。本文件的每个 `prop`
 *   都能在那份 DDL 里找到同名列（camelCase ↔ snake_case），或来自 tasks.md 明确要求
 *   服务端在列表里补充的展示字段（`remainQtySum`，tasks.md 3.4）。
 *
 * 契约（t12 与后端各组对齐用，见 notes/09a-frontend-core.md §2）：
 *   · restBase  → PRD §9.4（`/pur/order`、`/stk/in-order` …）
 *   · permPrefix→ PRD §7.5（`pur:order`、`stk:in-order` …）
 *   · 列表查询参数 → docNo(模糊) / status / handlerName(模糊) / beginDocDate+endDocDate(yyyy-MM-dd)
 *     + 各单据专有筛选（supplierId / customerId / warehouseId / contractNo / takeType / inType / outType）
 *   · 动作端点 → POST {restBase}/{id}/{action}，原因放请求体 `{ reason }`
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

/* ============================================================================
 * 一、公共列/字段构造器（只在**本文件**复用，页面侧不得再抄一遍）
 * ========================================================================== */

/** 列表公共列：单号 / 日期 / 状态 / 经办人 / 关联合同 / 来源单号 / 创建人 / 创建时间。 */
function commonColumns() {
  return [
    { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
    { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
    { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
    { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
    { prop: 'contractNo', label: '关联合同', kind: 'text', width: 150, showOverflowTooltip: true },
    { prop: 'sourceDocNo', label: '来源单号', kind: 'text', width: 150, showOverflowTooltip: true },
    { prop: 'createBy', label: '创建人', kind: 'text', width: 110, align: 'center' },
    { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
  ]
}

/**
 * 列表公共筛选：关键字 / 状态 / 单据日期区间。
 *
 * 真源 = 各 mapper 的 `<if test>`（notes/09b §1）：三族都支持 `keyword`（搜 `doc_no` + 备注，
 * 出入库还搜 `source_doc_no`）而不是只搜单号；日期区间是 `beginDocDate`/`endDocDate`（闭区间）。
 * ⚠ 只有盘点/调拨的 mapper 有 `handlerName` 过滤，所以"经办人"筛选放在那两类上，不要放公共集。
 */
function commonFilters(extra) {
  return [
    { prop: 'keyword', label: '关键字', kind: 'input', placeholder: '单号 / 备注 / 来源单号（模糊）' },
    { prop: 'status', label: '状态', kind: 'select', options: 'docStatuses' },
    { prop: 'docDateRange', label: '单据日期', kind: 'date-range', beginProp: 'beginDocDate', endProp: 'endDocDate' }
  ].concat(extra || [])
}

/**
 * 动作请求形状（**三族不同**，2026-10-05 队长裁决 D2：集中按 kind 派发，禁止 8 份 if/else）。
 *   mode：`post-action-id`（入库/出库）| `put-id-action`（采购/销售）| `post-id-action`（盘点/调拨，端点未落地）
 *   reasonPlacement：`query`（入库/出库/采购）| `body`（销售，`{reason}`）
 *   overrides：动作段/额外 query 覆盖（采购的"驳回"折叠在 `approve?action=reject` 上）
 */
function actionShape(mode, reasonPlacement, overrides) {
  return { mode: mode, reasonPlacement: reasonPlacement, overrides: overrides || {} }
}

/** 采购/销售：`PUT {base}/{id}/{action}`，reason 走 query。 */
function putIdAction() {
  return actionShape('put-id-action', 'query', {})
}

/** 销售：`PUT {base}/{id}/{action}`，reason 走 **body**（`@RequestBody Map` 的 `reason` 键）。 */
function putIdActionBodyReason() {
  return actionShape('put-id-action', 'body', {})
}

/** 入库/出库：`POST {base}/{action}/{id}`，reason 走 query。 */
function postActionId() {
  return actionShape('post-action-id', 'query', {})
}

/** 盘点/调拨（Controller 未落地）：沿用 t9a 冻结的 `POST {base}/{id}/{action}` + body。 */
function postIdAction() {
  return actionShape('post-id-action', 'body', {})
}

/** 表单公共表头字段：单据日期 / 经办人 / 备注。 */
function commonHeaderFields() {
  return [
    { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
    { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填：经办人姓名快照' },
    { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000, placeholder: '选填' }
  ]
}

/**
 * 行项公共列。
 *
 * @param {object} o { warehouse:boolean 行级仓库列, price:boolean 单价列, amount:boolean 金额列,
 *                     extra:Array 专有列, readonly:boolean 行项只读（查看态） }
 */
function itemColumns(o) {
  var opts = o || {}
  var cols = [
    { prop: 'seq', label: '序号', kind: 'seq', width: 60, align: 'center', editable: false },
    { prop: 'productId', label: '物料', kind: 'product', minWidth: 200, editable: !opts.readonly },
    { prop: 'productCode', label: '物料编码', kind: 'text', width: 130, editable: false },
    { prop: 'spec', label: '规格', kind: 'text', width: 140, editable: false },
    { prop: 'uomName', label: '单位', kind: 'text', width: 80, editable: false },
    { prop: 'qty', label: '数量', kind: 'qty', width: 120, align: 'right', editable: !opts.readonly }
  ]
  if (opts.price !== false) {
    cols.push({ prop: 'unitPrice', label: '单价', kind: 'price', width: 110, align: 'right', editable: !opts.readonly })
  }
  if (opts.amount !== false) {
    cols.push({ prop: 'amount', label: '金额', kind: 'money', width: 120, align: 'right', editable: false })
  }
  if (opts.warehouse) {
    cols.push({ prop: 'warehouseId', label: '仓库', kind: 'warehouse', width: 160, editable: !opts.readonly })
  }
  ;(opts.extra || []).forEach(function (c) { cols.push(c) })
  if (opts.remark !== false) {
    cols.push({ prop: 'remark', label: '行备注', kind: 'input', minWidth: 140, editable: !opts.readonly })
  }
  return cols
}

/* ============================================================================
 * 二、8 类单据
 * ========================================================================== */

var KINDS = [
  /* ---------------------------------------------------------------- 采购申请单 */
  {
    code: 'purchase_request',
    label: '采购申请单',
    subtitle: '提交后进入待审核；审核通过才可下推采购单（列表按剩余可下推量控制下推入口）',
    restBase: '/erp/pur/request',
    permPrefix: 'pur:request',
    stockDoc: false,
    listPath: '/erp/purchase-request',
    formPath: '/erp/purchase-request/form',
    attachmentObjectType: 'purchase_request',
    showWarehouse: false,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: false,
    remainField: 'orderedQty',
    // 动作形状：采购控制器是 PUT {id}/{action}，reason 走 query；"驳回"折叠在 approve 端点（action=reject）
    actionRequest: actionShape('put-id-action', 'query', { reject: { path: 'approve', query: { action: 'reject' } } }),
    deleteMode: 'id',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'needDate', label: '需求日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'purpose', label: '用途', kind: 'text', minWidth: 160, showOverflowTooltip: true },
      { prop: 'remainQtySum', label: '剩余可下推', kind: 'qty', width: 120, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'contractNo', label: '关联合同', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createBy', label: '创建人', kind: 'text', width: 110, align: 'center' },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters(),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'needDate', label: '需求日期', kind: 'date', span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'suggestSupplierId', label: '建议供应商', kind: 'supplier', span: 8 },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'purchase' },
      { prop: 'purpose', label: '用途说明', kind: 'textarea', span: 24, maxlength: 1000 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: false, extra: [
      { prop: 'orderedQty', label: '已下推', kind: 'qty', width: 110, align: 'right', editable: false }
    ] })
  },

  /* ---------------------------------------------------------------- 采购单 */
  {
    code: 'purchase_order',
    label: '采购单',
    subtitle: '供应商必填；审核通过后可下推入库单，已入库量在过账时累加',
    restBase: '/erp/pur/order',
    permPrefix: 'pur:order',
    stockDoc: false,
    listPath: '/erp/purchase-order',
    formPath: '/erp/purchase-order/form',
    attachmentObjectType: 'purchase_order',
    showWarehouse: true,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: true,
    remainField: 'receivedQty',
    actionRequest: actionShape('put-id-action', 'query', { reject: { path: 'approve', query: { action: 'reject' } } }),
    deleteMode: 'id',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'supplierName', label: '供应商', kind: 'text', minWidth: 170, showOverflowTooltip: true },
      { prop: 'expectedArrivalDate', label: '预计到货', kind: 'date', width: 110, align: 'center' },
      { prop: 'settleType', label: '结算方式', kind: 'text', width: 110, align: 'center' },
      { prop: 'totalAmount', label: '单据金额', kind: 'money', width: 130, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'contractNo', label: '关联合同', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'sourceDocNo', label: '来源单号', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'supplierId', label: '供应商', kind: 'select', options: 'suppliers' },
      { prop: 'contractNo', label: '关联合同', kind: 'input', placeholder: '请输入合同编号' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'supplierId', label: '供应商', kind: 'supplier', required: true, span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'expectedArrivalDate', label: '预计到货日期', kind: 'date', span: 8 },
      { prop: 'settleType', label: '结算方式', kind: 'input', span: 8, placeholder: '如：月结 30 天' },
      { prop: 'currency', label: '币种', kind: 'input', span: 8, placeholder: '默认 CNY' },
      { prop: 'receiptWarehouseId', label: '默认收货仓库', kind: 'warehouse', span: 8 },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'purchase' },
      { prop: 'totalAmount', label: '单据金额', kind: 'readonly', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: true, extra: [
      { prop: 'receivedQty', label: '已入库', kind: 'qty', width: 110, align: 'right', editable: false }
    ] })
  },

  /* ---------------------------------------------------------------- 销售申请单 */
  {
    code: 'sales_request',
    label: '销售申请单',
    subtitle: '提交后进入待审核；审核通过才可下推销售订单',
    restBase: '/sal/request',
    permPrefix: 'sal:request',
    stockDoc: false,
    listPath: '/erp/sales-request',
    formPath: '/erp/sales-request/form',
    attachmentObjectType: 'sales_request',
    showWarehouse: false,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: false,
    remainField: 'orderedQty',
    // 销售控制器是 PUT {id}/{action}；reason 走 **body**（@RequestBody Map 的 reason 键）；驳回是独立端点
    actionRequest: putIdActionBodyReason(),
    deleteMode: 'id',
    changeLogs: false,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'customerNameText', label: '客户', kind: 'text', minWidth: 160, showOverflowTooltip: true },
      { prop: 'expectDeliveryDate', label: '期望交货', kind: 'date', width: 110, align: 'center' },
      { prop: 'remainQtySum', label: '剩余可下推', kind: 'qty', width: 120, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'contractNo', label: '关联合同', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'customerId', label: '客户', kind: 'select', options: 'customers' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'expectDeliveryDate', label: '期望交货日期', kind: 'date', span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'customerId', label: '客户', kind: 'customer', span: 8 },
      { prop: 'customerNameText', label: '客户名称（未绑档案时）', kind: 'input', span: 8 },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'sale' },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: false, extra: [
      { prop: 'orderedQty', label: '已下推', kind: 'qty', width: 110, align: 'right', editable: false }
    ] })
  },

  /* ---------------------------------------------------------------- 销售订单 */
  {
    code: 'sales_order',
    label: '销售订单',
    subtitle: '客户必填；审核通过后可下推出库单，已出库量在过账时累加',
    restBase: '/sal/order',
    permPrefix: 'sal:order',
    stockDoc: false,
    listPath: '/erp/sales-order',
    formPath: '/erp/sales-order/form',
    attachmentObjectType: 'sales_order',
    showWarehouse: true,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: true,
    remainField: 'shippedQty',
    actionRequest: putIdActionBodyReason(),
    deleteMode: 'id',
    changeLogs: false,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'customerName', label: '客户', kind: 'text', minWidth: 170, showOverflowTooltip: true },
      { prop: 'deliveryDate', label: '交货日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'contactName', label: '联系人', kind: 'text', width: 100, align: 'center' },
      { prop: 'totalAmount', label: '单据金额', kind: 'money', width: 130, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'sourceDocNo', label: '来源单号', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'customerId', label: '客户', kind: 'select', options: 'customers' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'customerId', label: '客户', kind: 'customer', required: true, span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'deliveryDate', label: '交货日期', kind: 'date', span: 8 },
      { prop: 'shipWarehouseId', label: '默认发货仓库', kind: 'warehouse', span: 8 },
      { prop: 'currency', label: '币种', kind: 'input', span: 8, placeholder: '默认 CNY' },
      { prop: 'deliveryAddress', label: '收货地址', kind: 'input', span: 12 },
      { prop: 'contactName', label: '联系人', kind: 'input', span: 6 },
      { prop: 'contactPhone', label: '联系电话', kind: 'input', span: 6 },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'sale' },
      { prop: 'totalAmount', label: '单据金额', kind: 'readonly', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: true, extra: [
      { prop: 'shippedQty', label: '已出库', kind: 'qty', width: 110, align: 'right', editable: false }
    ] })
  },

  /* ---------------------------------------------------------------- 入库单 */
  {
    code: 'stock_in',
    label: '入库单',
    subtitle: '审核即过账（写结存与流水）；反审核红冲。行项未填仓库时回落表头仓库',
    restBase: '/stk/in-order',
    permPrefix: 'stk:in-order',
    stockDoc: true,
    listPath: '/erp/stock-in',
    formPath: '/erp/stock-in/form',
    attachmentObjectType: 'stock_in',
    showWarehouse: true,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: true,
    // 入库/出库控制器：POST {action}/{id}（reason 走 query）；删除是 DELETE /{ids}（逗号分隔多 id）
    actionRequest: postActionId(),
    deleteMode: 'ids',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'warehouseName', label: '仓库', kind: 'text', width: 140, showOverflowTooltip: true },
      { prop: 'inType', label: '入库类型', kind: 'dict', dict: 'stock_in_types', width: 120, align: 'center' },
      { prop: 'supplierName', label: '供应商', kind: 'text', minWidth: 160, showOverflowTooltip: true },
      { prop: 'totalAmount', label: '单据金额', kind: 'money', width: 130, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'posted', label: '过账', kind: 'posted', width: 90, align: 'center' },
      { prop: 'sourceDocNo', label: '来源单号', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createBy', label: '创建人', kind: 'text', width: 110, align: 'center' },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'warehouseId', label: '仓库', kind: 'select', options: 'warehouses' },
      { prop: 'inType', label: '入库类型', kind: 'select', dict: 'stock_in_types' },
      { prop: 'supplierId', label: '供应商', kind: 'select', options: 'suppliers' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'warehouseId', label: '仓库', kind: 'warehouse', required: true, span: 8 },
      { prop: 'inType', label: '入库类型', kind: 'dict', dict: 'stock_in_types', required: true, span: 8 },
      { prop: 'supplierId', label: '供应商', kind: 'supplier', span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'purchase' },
      { prop: 'totalAmount', label: '单据金额', kind: 'readonly', span: 8 },
      { prop: 'posted', label: '过账标记', kind: 'readonlyPosted', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: true })
  },

  /* ---------------------------------------------------------------- 出库单 */
  {
    code: 'stock_out',
    label: '出库单',
    subtitle: '审核即过账；默认不允许负库存（可用量不足会被拦下且不留痕）',
    restBase: '/stk/out-order',
    permPrefix: 'stk:out-order',
    stockDoc: true,
    listPath: '/erp/stock-out',
    formPath: '/erp/stock-out/form',
    attachmentObjectType: 'stock_out',
    showWarehouse: true,
    showPrice: true,
    showAmount: true,
    totalAmountColumn: true,
    actionRequest: postActionId(),
    deleteMode: 'ids',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'warehouseName', label: '仓库', kind: 'text', width: 140, showOverflowTooltip: true },
      { prop: 'outType', label: '出库类型', kind: 'dict', dict: 'stock_out_types', width: 120, align: 'center' },
      { prop: 'customerName', label: '客户', kind: 'text', minWidth: 160, showOverflowTooltip: true },
      { prop: 'totalAmount', label: '单据金额', kind: 'money', width: 130, align: 'right' },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'posted', label: '过账', kind: 'posted', width: 90, align: 'center' },
      { prop: 'sourceDocNo', label: '来源单号', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'createBy', label: '创建人', kind: 'text', width: 110, align: 'center' },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'warehouseId', label: '仓库', kind: 'select', options: 'warehouses' },
      { prop: 'outType', label: '出库类型', kind: 'select', dict: 'stock_out_types' },
      { prop: 'customerId', label: '客户', kind: 'select', options: 'customers' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'warehouseId', label: '仓库', kind: 'warehouse', required: true, span: 8 },
      { prop: 'outType', label: '出库类型', kind: 'dict', dict: 'stock_out_types', required: true, span: 8 },
      { prop: 'customerId', label: '客户', kind: 'customer', span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'contractId', label: '关联合同', kind: 'contract', span: 8, contractDirection: 'sale' },
      { prop: 'totalAmount', label: '单据金额', kind: 'readonly', span: 8 },
      { prop: 'posted', label: '过账标记', kind: 'readonlyPosted', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: true, amount: true, warehouse: true })
  },

  /* ---------------------------------------------------------------- 盘点单 */
  {
    code: 'stock_take',
    label: '盘点单',
    subtitle: '生成行项 → 录入实盘 → 审核：审核自动生成盘盈入库单 / 盘亏出库单并过账',
    restBase: '/stk/take',
    permPrefix: 'stk:take',
    stockDoc: true,
    listPath: '/erp/stock-take',
    formPath: '/erp/stock-take/form',
    attachmentObjectType: 'stock_take',
    showWarehouse: false,
    showPrice: false,
    showAmount: false,
    totalAmountColumn: false,
    specialForm: 'stocktake',
    // 盘点单 Controller 已落地 `/stk/take`：动作两种形状都支持（`/submit/{id}` 与 `/{id}/submit`），
    // 删除是 `DELETE /{ids}`（逗号分隔多 id）；无下推链。
    actionRequest: postIdAction(),
    deleteMode: 'ids',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'warehouseName', label: '仓库', kind: 'text', width: 140, showOverflowTooltip: true },
      { prop: 'takeType', label: '盘点范围', kind: 'dict', dict: 'stock_take_types', width: 110, align: 'center' },
      { prop: 'scopeNote', label: '范围说明', kind: 'text', minWidth: 160, showOverflowTooltip: true },
      { prop: 'generatedInNo', label: '盘盈入库单', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'generatedOutNo', label: '盘亏出库单', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'warehouseId', label: '仓库', kind: 'select', options: 'warehouses' },
      { prop: 'takeType', label: '盘点范围', kind: 'select', dict: 'stock_take_types' },
      { prop: 'handlerName', label: '经办人', kind: 'input', placeholder: '请输入经办人' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'warehouseId', label: '仓库', kind: 'warehouse', required: true, span: 8 },
      { prop: 'takeType', label: '盘点范围', kind: 'dict', dict: 'stock_take_types', required: true, span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'scopeNote', label: '范围说明', kind: 'textarea', span: 24, maxlength: 1000, placeholder: '抽盘时填写范围说明' },
      { prop: 'generatedInNo', label: '生成的盘盈入库单', kind: 'readonly', span: 8 },
      { prop: 'generatedOutNo', label: '生成的盘亏出库单', kind: 'readonly', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: [
      { prop: 'seq', label: '序号', kind: 'seq', width: 60, align: 'center', editable: false },
      { prop: 'productId', label: '物料', kind: 'product', minWidth: 200, editable: false },
      { prop: 'productCode', label: '物料编码', kind: 'text', width: 130, editable: false },
      { prop: 'spec', label: '规格', kind: 'text', width: 140, editable: false },
      { prop: 'uomName', label: '单位', kind: 'text', width: 80, editable: false },
      { prop: 'bookQty', label: '账面数量', kind: 'qty', width: 120, align: 'right', editable: false },
      { prop: 'actualQty', label: '实盘数量', kind: 'actualQty', width: 130, align: 'right', editable: true },
      { prop: 'diffQty', label: '差异', kind: 'diffQty', width: 110, align: 'right', editable: false },
      { prop: 'diffReason', label: '差异原因', kind: 'input', minWidth: 160, editable: true }
    ]
  },

  /* ---------------------------------------------------------------- 调拨单 */
  {
    code: 'stock_transfer',
    label: '调拨单',
    subtitle: '同一事务内调出仓减少、调入仓增加（仓库间搬运，不产生金额）',
    restBase: '/stk/transfer',
    permPrefix: 'stk:transfer',
    stockDoc: true,
    listPath: '/erp/stock-transfer',
    formPath: '/erp/stock-transfer/form',
    attachmentObjectType: 'stock_transfer',
    showWarehouse: false,
    showPrice: false,
    showAmount: false,
    totalAmountColumn: false,
    // 调拨单 Controller 已落地 `/stk/transfer`：动作两种形状都支持；删除是 `DELETE /{ids}`；无下推链。
    actionRequest: postIdAction(),
    deleteMode: 'ids',
    changeLogs: true,
    printReady: false,
    columns: [
      { prop: 'docNo', label: '单号', kind: 'link', width: 170, showOverflowTooltip: true },
      { prop: 'docDate', label: '单据日期', kind: 'date', width: 110, align: 'center' },
      { prop: 'fromWarehouseName', label: '调出仓', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'toWarehouseName', label: '调入仓', kind: 'text', width: 150, showOverflowTooltip: true },
      { prop: 'status', label: '状态', kind: 'status', width: 100, align: 'center' },
      { prop: 'posted', label: '过账', kind: 'posted', width: 90, align: 'center' },
      { prop: 'handlerName', label: '经办人', kind: 'text', width: 100, align: 'center' },
      { prop: 'createBy', label: '创建人', kind: 'text', width: 110, align: 'center' },
      { prop: 'createTime', label: '创建时间', kind: 'datetime', width: 160, align: 'center' }
    ],
    filters: commonFilters([
      { prop: 'fromWarehouseId', label: '调出仓', kind: 'select', options: 'warehouses' },
      { prop: 'toWarehouseId', label: '调入仓', kind: 'select', options: 'warehouses' },
      { prop: 'handlerName', label: '经办人', kind: 'input', placeholder: '请输入经办人' }
    ]),
    headerFields: [
      { prop: 'docDate', label: '单据日期', kind: 'date', required: true, span: 8 },
      { prop: 'fromWarehouseId', label: '调出仓库', kind: 'warehouse', required: true, span: 8 },
      { prop: 'toWarehouseId', label: '调入仓库', kind: 'warehouse', required: true, span: 8 },
      { prop: 'handlerName', label: '经办人', kind: 'input', span: 8, placeholder: '选填' },
      { prop: 'posted', label: '过账标记', kind: 'readonlyPosted', span: 8 },
      { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
    ],
    itemColumns: itemColumns({ price: false, amount: false, warehouse: false })
  }
]

/** 按单据码取配置（大小写不敏感）；未匹配返回 null。 */
function getKind(code) {
  if (!code) {
    return null
  }
  var normalized = String(code).trim().toLowerCase()
  for (var i = 0; i < KINDS.length; i++) {
    if (KINDS[i].code === normalized) {
      return KINDS[i]
    }
  }
  return null
}

/** 单据码清单（8 类；与后端 `ErpDocType` 的 code 一一对应）。 */
var KIND_CODES = KINDS.map(function (k) { return k.code })

/** 这些单据的列表需要 `remainQtySum`（tasks.md 3.4：列表返回剩余可下推量合计）。 */
var KINDS_WITH_REMAIN_SUM = ['purchase_request', 'sales_request']

module.exports = {
  KINDS: KINDS,
  KIND_CODES: KIND_CODES,
  KINDS_WITH_REMAIN_SUM: KINDS_WITH_REMAIN_SUM,
  getKind: getKind,
  commonColumns: commonColumns,
  commonFilters: commonFilters,
  commonHeaderFields: commonHeaderFields,
  itemColumns: itemColumns
}
