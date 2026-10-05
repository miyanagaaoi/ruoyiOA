/**
 * 进销存单据的**前端口径单一实现点**（B4 §8.3）：状态机 / 权限点 / 下推链 / 剩余量。
 *
 * 为什么必须有这个文件（D12 的落地方式）：
 *   参考仓库用 `DocListPage`（33KB 一个组件）撑起 8 个列表页。若逐页各写一遍
 *   "按钮显隐按状态机 + 按权限"，就会把同一套判断抄 8 遍，且必然漂移。
 *   这里把判断抽成**纯函数**（零依赖、可单测），壳组件只负责渲染。
 *
 * 真源与镜像关系（**不得单方面改本文件**）：
 *   · 状态集合 / 动作集合 / 逐动作白名单 / 原因必填 / 库存类不提供「置为已完成」
 *     ← 后端 `com.ruoyi.ctms.erp.base.ErpDocStatus`、`ErpDocAction`、`ErpDocStateMachine`
 *       （本文件逐条镜像；`tests/erp-doc-rules.test.js` 用 5 状态 × 6 动作全矩阵钉住）。
 *   · 权限点动作段 ← 移植清单 §4.15 的 `perm_prefix.{action}` 表（见 `erp-const.ACTION_PERM_SEGMENT`）。
 *   · 下推链 ← tasks.md 3.3/3.5/4.2/4.3（申请→订单 仅「已审核」可推；订单→出入库 仅「已审核/已完成」可推）。
 *   ⚠ 前端判定只改善体验；**服务端才是真防线**（AC-79/AC-80）。这里判"能不能点"，
 *     点了之后接口仍会再校验一次。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

var CONST = require('../erp-const')

/* ============================================================================
 * 一、状态集合（镜像 ErpDocStatus）
 * ========================================================================== */

/** 五个状态；`tag` 是 element-ui `<el-tag type>` 的取值，`label` 与后端 `labelOf` 逐字一致。 */
var STATUSES = [
  { value: 'draft', label: '草稿', tag: 'info' },
  { value: 'submitted', label: '待审核', tag: 'warning' },
  { value: 'approved', label: '已审核', tag: 'success' },
  { value: 'completed', label: '已完成', tag: 'primary' },
  { value: 'voided', label: '已作废', tag: 'danger' }
]

var STATUS_VALUES = STATUSES.map(function (s) { return s.value })

/** 唯一可编辑状态（ErpDocStatus.isEditable：只有 draft）。 */
var EDITABLE_STATUS = 'draft'

/* ============================================================================
 * 二、动作集合与逐动作白名单（镜像 ErpDocAction / ErpDocStateMachine）
 * ========================================================================== */

/**
 * 六个动作。字段含义：
 *   code           动作码（请求路径与变更历史的 action）
 *   label          中文名（与后端 `ErpDocAction.getLabel()` 逐字一致，错误文案要拼它）
 *   reasonRequired 原因必填（驳回/作废/反审核）
 *   danger         是否危险动作（界面上加二次确认与红色按钮）
 *   permAction     权限点动作段（见 erp-const.ACTION_PERM_SEGMENT 的说明）
 */
var ACTIONS = [
  { code: 'submit', label: '提交', reasonRequired: false, danger: false },
  { code: 'approve', label: '审核', reasonRequired: false, danger: false },
  { code: 'reject', label: '驳回', reasonRequired: true, danger: true },
  { code: 'void', label: '作废', reasonRequired: true, danger: true },
  { code: 'unapprove', label: '反审核', reasonRequired: true, danger: true },
  { code: 'complete', label: '置为已完成', reasonRequired: false, danger: false }
]

/** 动作 → 允许的来源状态（ErpDocStateMachine.ALLOWED 的逐条镜像）。 */
var ALLOWED_FROM = {
  submit: ['draft'],
  approve: ['submitted'],
  reject: ['submitted'],
  void: ['draft', 'submitted'],
  unapprove: ['approved', 'completed'],
  complete: ['approved']
}

/** 动作 → 目标状态（unapprove 依赖来源状态，单独判定）。 */
var NEXT_STATUS = {
  submit: 'submitted',
  approve: 'approved',
  reject: 'draft',
  void: 'voided',
  complete: 'completed'
}

/* ============================================================================
 * 三、下推链（tasks.md 3.3 / 3.5 / 4.2 / 4.3）
 * ========================================================================== */

/**
 * 下推链配置（真源 = 已落地 Controller；2026-10-05 队长裁决 D4：权限点一律挂**来源单据** `:push`）。
 *
 * `remainField` 是该单据行项的"已下推/已回写数量"列（DDL 里的真实列）：
 *   · 申请单：`ordered_qty`（申请→订单 **即时**累加）
 *   · 采购单：`received_qty`（订单→入库 **过账时**累加）
 *   · 销售订单：`shipped_qty`（订单→出库 **过账时**累加）
 *
 * `push` 描述**已落地端点**的形态（四条链的形状不一样，故按链配置而不是按页面写）——
 * 全部以 Controller 源码为判据（t32 repair：**四条链的后端现在都已落地**）：
 *   · 采购申请→采购单：`POST /erp/pur/request/{id}/push/purchase-order?supplierId=&remark=`，
 *     body 是**裸数组** `[{srcItemId, qty}]`（`ErpPurchaseRequestController:385`，权限 `pur:request:push`）；
 *   · 采购单→入库单：`POST /erp/pur/order/{id}/push/stock-in?warehouseId=&remark=`，
 *     body 同样是裸数组；`warehouseId` 不传会回落采购单默认收货仓库，**两者都空则服务端拒绝**
 *     ⇒ 前端用 `requiresWarehouse` 在提交前拦住（`ErpPurchaseOrderController:363-371`，权限 `pur:order:push`）；
 *   · 销售申请→销售订单：`POST /sal/request/{id}/push`，body 是 `ErpSalesPushRequest`
 *     （`{remark, shipWarehouseId, …, lines:[{docItemId,qty,…}]}`，`ErpSalesController:269`，权限 `sal:request:push`）；
 *   · 销售订单→出库单：`POST /sal/order/{id}/push`（`ErpSalesController:491-496`，权限 `sal:order:push`），
 *     服务端 `pushRequestOf()` 同时接受"裸数组"与"包装对象"两种 body；前端与销售申请**共用同一种**包装形状。
 *
 * `pending` 只表示"后端端点未落地"（前端不给入口、不发必然 404 的请求）。
 * ⚠ 翻 `pending` 必须有源码证据：`tests/erp-shell.test.js` 有一条用例**直接扫 Controller 源码**，
 *   断言 `pending` 与"端点是否真的存在"一致（防止"后端好了、前端还藏着"再次发生）。
 */
var PUSH_CHAINS = {
  purchase_request: {
    to: 'purchase_order',
    toLabel: '采购单',
    sourceStatuses: ['approved'],
    remainField: 'orderedQty',
    perm: 'pur:request:push',
    qtyField: 'qty',
    push: {
      pathSuffix: '/push/purchase-order',
      bodyShape: 'lines-array',
      lineKeys: { srcItemId: 'srcItemId', qty: 'qty' },
      extraParams: ['supplierId', 'remark']
    }
  },
  purchase_order: {
    to: 'stock_in',
    toLabel: '入库单',
    sourceStatuses: ['approved', 'completed'],
    remainField: 'receivedQty',
    perm: 'pur:order:push',
    qtyField: 'qty',
    // 已落地（t7/t4b）：`ErpPurchaseOrderController:363-371` `POST /{id}/push/stock-in`
    push: {
      pathSuffix: '/push/stock-in',
      bodyShape: 'lines-array',
      lineKeys: { srcItemId: 'srcItemId', qty: 'qty' },
      extraParams: ['warehouseId', 'remark'],
      // 入库单的 warehouse_id 是 NOT NULL：没有收货仓库时服务端会拒绝，前端先拦
      requiresWarehouse: true
    }
  },
  sales_request: {
    to: 'sales_order',
    toLabel: '销售订单',
    sourceStatuses: ['approved'],
    remainField: 'orderedQty',
    perm: 'sal:request:push',
    qtyField: 'qty',
    push: {
      pathSuffix: '/push',
      bodyShape: 'sales-request',
      linesKey: 'lines',
      lineKeys: { srcItemId: 'docItemId', qty: 'qty', productId: 'productId' },
      extraParams: ['remark', 'shipWarehouseId']
    }
  },
  sales_order: {
    to: 'stock_out',
    toLabel: '出库单',
    sourceStatuses: ['approved', 'completed'],
    remainField: 'shippedQty',
    perm: 'sal:order:push',
    qtyField: 'qty',
    // 已落地（t8/t5b）：`ErpSalesController:491-496` `POST /order/{id}/push`（服务端兼收裸数组与包装对象）
    push: {
      pathSuffix: '/push',
      bodyShape: 'sales-request',
      linesKey: 'lines',
      lineKeys: { srcItemId: 'docItemId', qty: 'qty', productId: 'productId' },
      extraParams: ['remark']
    }
  }
}

/* ============================================================================
 * 四、纯函数
 * ========================================================================== */

/** 状态中文名；未知状态原样返回（不伪装成合法状态，与后端 labelOf 同口径）。 */
function statusLabel(status) {
  var hit = findBy(STATUSES, 'value', status)
  return hit ? hit.label : (status == null ? '' : String(status))
}

/** 状态标签配色（未知状态用 info）。 */
function statusTagType(status) {
  var hit = findBy(STATUSES, 'value', status)
  return hit ? hit.tag : 'info'
}

/** 状态是否合法。 */
function isValidStatus(status) {
  return STATUS_VALUES.indexOf(status) >= 0
}

/** 只有草稿可编辑（ErpDocStatus.isEditable 的镜像）。 */
function isEditable(status) {
  return status === EDITABLE_STATUS
}

/** 动作定义；未知动作返回 null。 */
function actionOf(code) {
  return findBy(ACTIONS, 'code', code) || null
}

/** 动作中文名。 */
function actionLabel(code) {
  var a = actionOf(code)
  return a ? a.label : (code == null ? '' : String(code))
}

/** 该动作是否原因必填。 */
function isReasonRequired(code) {
  var a = actionOf(code)
  return !!(a && a.reasonRequired)
}

/** 原因缺失时的文案（与后端 `ErpDocAction.reasonRequiredMessage()` 逐字一致）。 */
function reasonRequiredMessage(code) {
  return actionLabel(code) + '原因必填'
}

/** 某动作允许的来源状态清单（只读副本）。 */
function allowedFrom(code) {
  return (ALLOWED_FROM[code] || []).slice()
}

/** 该动作在给定单据类型下是否**存在**（库存类不提供「置为已完成」）。 */
function actionExistsForKind(code, kind) {
  if (code === 'complete' && kind && kind.stockDoc) {
    return false
  }
  return !!actionOf(code)
}

/** 目标状态（与 ErpDocStateMachine.nextStatus 同口径；unapprove 依赖来源）。 */
function nextStatus(code, current) {
  if (code === 'unapprove') {
    return current === 'completed' ? 'draft' : 'submitted'
  }
  return NEXT_STATUS[code] || null
}

/**
 * 能否执行某动作（镜像 ErpDocStateMachine.check，**只做体验层判定**）。
 *
 * @param {string} code   动作码
 * @param {string} status 当前状态
 * @param {object} kind   单据类型配置（`doc-kinds.js` 的一项；用 `stockDoc`/`label`）
 * @param {boolean} [hasItems] 是否至少一行行项（仅 submit 需要）
 * @returns {{ok: boolean, message: string}}
 */
function canRun(code, status, kind, hasItems) {
  var action = actionOf(code)
  if (!action) {
    return { ok: false, message: '未知的单据动作：' + code }
  }
  if (!isValidStatus(status)) {
    return { ok: false, message: '无效的单据状态：' + status }
  }
  // ① 库存类不提供「置为已完成」（与后端同顺序：先判这条，报错更准确）
  if (code === 'complete' && kind && kind.stockDoc) {
    return { ok: false, message: (kind.label || '单据') + '为库存类单据，不支持「置为已完成」' }
  }
  // ② 逐动作白名单
  if (allowedFrom(code).indexOf(status) < 0) {
    return { ok: false, message: '当前状态（' + statusLabel(status) + '）不可执行「' + actionLabel(code) + '」' }
  }
  // ③ 提交必须有行项
  if (code === 'submit' && hasItems === false) {
    return { ok: false, message: '单据没有行项，不能提交' }
  }
  return { ok: true, message: '' }
}

/**
 * 当前状态下**应该显示**的动作清单（壳的操作列用它）。
 *
 * @param {object} kind   单据类型配置
 * @param {string} status 当前状态
 * @param {boolean} [hasItems] 是否至少一行行项（决定「提交」是否可点；不传则视为有）
 * @returns {Array<{code:string,label:string,reasonRequired:boolean,perm:string,disabled:boolean,disabledReason:string}>}
 */
function visibleActions(kind, status, hasItems) {
  var out = []
  ACTIONS.forEach(function (action) {
    if (!actionExistsForKind(action.code, kind)) {
      return
    }
    var allowed = allowedFrom(action.code).indexOf(status) >= 0
    if (!allowed) {
      return
    }
    var judged = canRun(action.code, status, kind, hasItems)
    out.push({
      code: action.code,
      label: action.label,
      reasonRequired: action.reasonRequired,
      danger: action.danger,
      perm: permOf(kind, action.code),
      disabled: !judged.ok,
      disabledReason: judged.ok ? '' : judged.message
    })
  })
  return out
}

/** 权限点：`<域>:<资源>:<动作段>`（动作段映射见 erp-const.ACTION_PERM_SEGMENT）。 */
function permOf(kind, action) {
  var segment = CONST.ACTION_PERM_SEGMENT[action]
  if (!segment) {
    return ''
  }
  return (kind && kind.permPrefix ? kind.permPrefix : '') + ':' + segment
}

/** 单据可编辑（列表「修改」入口显隐 + 表单只读判定共用）。 */
function canEdit(kind, doc) {
  return !!doc && isEditable(doc.status)
}

/** 该单据类型是否有下推链。 */
function hasPush(kind) {
  return !!(kind && PUSH_CHAINS[kind.code])
}

/** 下推目标配置；无链路返回 null。 */
function pushChainOf(kind) {
  return (kind && PUSH_CHAINS[kind.code]) || null
}

/**
 * 下推按钮是否显示。
 *
 * 判定顺序（队长裁决 D5 + 已落地派生列）：
 *   ① 端点未落地（`push.pending`）⇒ **不显示**（宁可不给入口，也不发必然 404 的请求）；
 *   ② 服务端已给派生布尔列（采购申请 `canPush`、采购单 `canReceive`）⇒ 以它为准；
 *   ③ 状态必须在下推白名单；
 *   ④ 申请单兜底看 `remainQtySum > 0`（tasks.md 3.4：列表返回剩余可下推量合计；
 *      销售侧的该列由 t5 补，前端**不**退化成"只看状态"）。
 *
 * @param {object} kind 单据类型配置
 * @param {object} row  列表行
 * @returns {{ok:boolean, message:string}}
 */
function canPush(kind, row) {
  var chain = pushChainOf(kind)
  if (!chain) {
    return { ok: false, message: '' }
  }
  if (chain.push && chain.push.pending) {
    return { ok: false, message: '下推端点未落地（待后端交付）' }
  }
  if (row && typeof row.canPush === 'boolean' && !row.canPush) {
    return { ok: false, message: '' }
  }
  if (row && typeof row.canReceive === 'boolean' && !row.canReceive) {
    return { ok: false, message: '' }
  }
  if (chain.sourceStatuses.indexOf(row && row.status) < 0) {
    return { ok: false, message: '' }
  }
  if (kind.code === 'purchase_request' || kind.code === 'sales_request') {
    var remain = toNumber(row.remainQtySum)
    if (remain <= 0) {
      return { ok: false, message: '' }
    }
  }
  return { ok: true, message: '' }
}

/** 行项剩余可下推量 = 数量 − 已下推/已回写数量（浮点只用于显示，精度由服务端把关）。 */
function remainingQty(kind, item) {
  var chain = pushChainOf(kind)
  if (!chain) {
    return 0
  }
  return round(toNumber(item && item.qty) - toNumber(item && item[chain.remainField]), 3)
}

/**
 * 下推对话框的预填行：未填数量时按剩余量**全推**（tasks.md 3.3）。
 *
 * @param {object} kind  单据类型配置
 * @param {Array}  items 来源单据行项
 * @returns {Array<{itemId:string, productId:string, productName:string, spec:string, uomName:string,
 *                  qty:number, remainQty:number, pushQty:number, srcItemId:string}>}
 */
function pushRows(kind, items) {
  var chain = pushChainOf(kind)
  if (!chain) {
    return []
  }
  return (items || []).map(function (item) {
    var remain = remainingQty(kind, item)
    return {
      itemId: item.id,
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      spec: item.spec,
      uomName: item.uomName,
      qty: toNumber(item.qty),
      remainQty: remain,
      pushQty: remain,
      srcItemId: item.id
    }
  })
}

/**
 * 校验下推行（镜像 tasks.md 3.3：仅已审核可推、下推量不超过剩余量、至少一行且数量 > 0）。
 *
 * @param {object} kind   单据类型配置
 * @param {string} status 来源单据状态
 * @param {Array}  rows   `pushRows` 的产物（用户可能改过 pushQty）
 * @returns {{ok:boolean, message:string, total:number}}
 */
function validatePush(kind, status, rows) {
  var chain = pushChainOf(kind)
  if (!chain) {
    return { ok: false, message: '该单据类型没有下推链', total: 0 }
  }
  if (chain.sourceStatuses.indexOf(status) < 0) {
    return { ok: false, message: '当前状态（' + statusLabel(status) + '）不可下推', total: 0 }
  }
  var total = 0
  var list = rows || []
  for (var i = 0; i < list.length; i++) {
    var row = list[i]
    var qty = toNumber(row.pushQty)
    if (qty < 0) {
      return { ok: false, message: '第 ' + (i + 1) + ' 行下推数量不能为负数', total: 0 }
    }
    if (qty > round(toNumber(row.remainQty), 3) + 1e-9) {
      return {
        ok: false,
        message: '第 ' + (i + 1) + ' 行下推数量超过剩余量（剩余 ' + formatQty(row.remainQty) + '）',
        total: 0
      }
    }
    total = round(total + qty, 3)
  }
  if (total <= 0) {
    return { ok: false, message: '下推数量合计必须大于 0', total: 0 }
  }
  return { ok: true, message: '', total: total }
}

/**
 * 下推**额外入参**的前端校验（t32：采购单→入库单需要收货仓库）。
 *
 * 为什么要有这条：`ErpPurchaseOrderController:350-352` 明确写着"`warehouseId` 不传时回落采购单的默认收货
 * 仓库，两者都空则**拒绝下推**"（`t_ctms_stock_in.warehouse_id` 是 NOT NULL）。前端如果在没有仓库时
 * 照样发请求，用户会看到后端拒绝但不知道要选仓库 —— 这里提前拦住并给出可操作的原因。
 *
 * @param {object} kind  单据类型配置
 * @param {object} [extra] 端点额外入参（采购单：`warehouseId`）
 * @returns {{ok:boolean, message:string}}
 */
function validatePushExtra(kind, extra) {
  var chain = pushChainOf(kind)
  if (!chain || !chain.push) {
    return { ok: false, message: '该单据类型没有下推链' }
  }
  if (chain.push.pending) {
    return { ok: false, message: '下推端点未落地（待后端交付）' }
  }
  if (chain.push.requiresWarehouse && isBlank(extra && extra.warehouseId)) {
    return { ok: false, message: '请选择收货仓库（入库单必须有仓库，采购单未设默认收货仓库时必须在这里选）' }
  }
  return { ok: true, message: '' }
}

/* ============================================================================
 * 四之二、请求构造（**一处按 kind 派发的映射**；api 层只负责发出去）
 * ----------------------------------------------------------------------------
 * 为什么放在这里而不是在 api 层写 switch：已落地的三族控制器**动作 URL 形状各不相同**
 * （2026-10-05 逐个 grep 的结论，见 notes/09b §1/§2-D2）：
 *   · 入库/出库（posting）：`POST {base}/{action}/{id}`，`reason` 走 **query**
 *   · 采购/销售（procurement/sales）：`PUT {base}/{id}/{action}`，`reason` 走 **query**（销售走 body）
 *   · 盘点/调拨（stockops，端点未落地）：暂按 t9a 冻结的 `POST {base}/{id}/{action}` + body
 * 放在纯函数里还有个好处：URL/方法/参数可以**单测钉住**，不用起浏览器。
 * ========================================================================== */

/**
 * 构造"单据动作"请求。
 *
 * @param {object} kind   单据类型配置（`doc-kinds.js` 的一项，含 `restBase` / `actionRequest`）
 * @param {string} action 动作码（submit/approve/reject/void/unapprove/complete）
 * @param {string} id     单据 ID
 * @param {string} [reason] 原因（驳回/作废/反审核必填）
 * @returns {{method:string, url:string, params:object, data:(object|null)}}
 */
function actionRequest(kind, action, id, reason) {
  var conf = (kind && kind.actionRequest) || {}
  var mode = conf.mode || 'post-id-action'
  var overrides = conf.overrides || {}
  var override = overrides[action] || {}
  var segment = override.path || action
  var base = (kind && kind.restBase) || ''
  var params = {}
  var data = null

  // 采购的"驳回"折叠在 approve 端点上，用 action=reject 区分（Controller 的 @RequestParam action）
  if (override.query) {
    Object.keys(override.query).forEach(function (k) {
      params[k] = override.query[k]
    })
  }
  if (reason !== undefined && reason !== null && reason !== '') {
    if (conf.reasonPlacement === 'body') {
      data = { reason: reason }
    } else {
      params.reason = reason
    }
  }
  if (mode === 'post-action-id') {
    return { method: 'post', url: base + '/' + segment + '/' + id, params: params, data: data }
  }
  if (mode === 'put-id-action') {
    return { method: 'put', url: base + '/' + id + '/' + segment, params: params, data: data }
  }
  // 冻结口径（盘点/调拨端点未落地）：POST {base}/{id}/{action}，body 带 id + reason
  return {
    method: 'post',
    url: base + '/' + id + '/' + segment,
    params: params,
    data: { id: id, reason: reason || undefined }
  }
}

/**
 * 构造"删除"请求。
 *
 * ⚠ 入库/出库控制器的删除是 `DELETE /{ids}`（**逗号分隔多 id**，`ErpStockInController:179`），
 *   采购/销售是 `DELETE /{id}`（单 id）。所以按 kind 的 `deleteMode` 派发。
 */
function deleteRequest(kind, ids) {
  var list = Array.isArray(ids) ? ids : [ids]
  var base = (kind && kind.restBase) || ''
  var mode = (kind && kind.deleteMode) || 'id'
  var tail = mode === 'ids' ? list.join(',') : list[0]
  return { method: 'delete', url: base + '/' + tail, params: {}, data: null }
}

/**
 * 构造"下推"请求（三种 body 形状，见 `PUSH_CHAINS[*].push`）。
 *
 * @param {object} kind 单据类型配置
 * @param {string} id   来源单据 ID
 * @param {Array}  rows `pushRows` 的产物（含 pushQty / srcItemId）
 * @param {object} [extra] 端点额外 query（采购：supplierId / remark；销售：remark / shipWarehouseId）
 * @returns {{method:string, url:string, params:object, data:*} | null} 端点未落地时返回 null
 */
function buildPushRequest(kind, id, rows, extra) {
  var chain = pushChainOf(kind)
  if (!chain || !chain.push || chain.push.pending) {
    return null
  }
  var conf = chain.push
  var base = (kind && kind.restBase) || ''
  var params = {}
  ;(conf.extraParams || []).forEach(function (key) {
    if (extra && extra[key] !== undefined && extra[key] !== null && extra[key] !== '') {
      params[key] = extra[key]
    }
  })
  var picked = (rows || [])
    .filter(function (r) {
      return Number(r.pushQty) > 0
    })
    .map(function (r) {
      var line = {}
      Object.keys(conf.lineKeys || {}).forEach(function (from) {
        var to = conf.lineKeys[from]
        if (from === 'srcItemId') {
          line[to] = r.srcItemId
        } else if (from === 'productId') {
          line[to] = r.productId
        } else {
          line[to] = r[from]
        }
      })
      line[conf.lineKeys && conf.lineKeys.qty ? conf.lineKeys.qty : 'qty'] = r.pushQty
      return line
    })
  if (conf.bodyShape === 'sales-request') {
    var body = { lines: picked }
    ;(conf.extraParams || []).forEach(function (key) {
      if (params[key] !== undefined) {
        body[key] = params[key]
      }
    })
    return { method: 'post', url: base + '/' + id + conf.pathSuffix, params: {}, data: body }
  }
  // 采购：body 是裸数组 [{srcItemId, qty}]，其余入参走 query
  return { method: 'post', url: base + '/' + id + conf.pathSuffix, params: params, data: picked }
}

/* ============================================================================
 * 五、表单前置校验（镜像服务端口径；前端只为了"立刻看到原因"）
 * ========================================================================== */

/** 调拨两仓校验（tasks.md 6.1：两仓非空且不同，**前端也拦截**）。 */
function validateTransfer(kind, form) {
  if (!kind || kind.code !== 'stock_transfer') {
    return { ok: true, message: '' }
  }
  var from = form && form.fromWarehouseId
  var to = form && form.toWarehouseId
  if (isBlank(from) || isBlank(to)) {
    return { ok: false, message: '调出仓库与调入仓库不能为空' }
  }
  if (from === to) {
    return { ok: false, message: '调出仓库与调入仓库不能相同' }
  }
  return { ok: true, message: '' }
}

/**
 * 行项校验（镜像 tasks.md 3.1：至少一行、数量大于 0、单价非负、数量精度按单位小数位）。
 *
 * @param {object} kind  单据类型配置
 * @param {Array}  items 行项数组
 * @returns {{ok:boolean, message:string}}
 */
function validateItems(kind, items) {
  var list = items || []
  if (!list.length) {
    return { ok: false, message: '单据没有行项，不能提交' }
  }
  var amountDisabled = kind && kind.showAmount === false
  for (var i = 0; i < list.length; i++) {
    var row = list[i]
    var no = i + 1
    if (isBlank(row.productId)) {
      return { ok: false, message: '第 ' + no + ' 行未选择物料' }
    }
    var qty = toNumber(row.qty)
    if (qty <= 0) {
      return { ok: false, message: '第 ' + no + ' 行数量必须大于 0' }
    }
    var decimals = toNumber(row.uomDecimals)
    var allowed = row.uomDecimals === undefined || row.uomDecimals === null ? 3 : decimals
    if (countDecimals(row.qty) > allowed) {
      return {
        ok: false,
        message: '第 ' + no + ' 行数量精度超出单位小数位（最多 ' + allowed + ' 位）'
      }
    }
    if (!amountDisabled && toNumber(row.unitPrice) < 0) {
      return { ok: false, message: '第 ' + no + ' 行单价不能为负数' }
    }
  }
  return { ok: true, message: '' }
}

/** 小数位数（按文本判定，避免浮点误差把 1.005 判成 3 位以外）。 */
function countDecimals(value) {
  if (value === null || value === undefined || value === '') {
    return 0
  }
  var text = String(value)
  var dot = text.indexOf('.')
  if (dot < 0) {
    return 0
  }
  return text.length - dot - 1
}

function isBlank(v) {
  return v === null || v === undefined || String(v).trim() === ''
}

/* ============================================================================
 * 六、单据类型配置的完整性校验（供单测用；8 类单据的配置必须齐备且与状态机自洽）
 * ========================================================================== */

/** 单据类型配置里**必须**出现的键（少一个就说明"壳 + 专有字段"没写全）。 */
var REQUIRED_KIND_KEYS = [
  'code', 'label', 'restBase', 'permPrefix', 'listPath', 'formPath',
  'stockDoc', 'columns', 'filters', 'headerFields', 'itemColumns',
  // 请求形状与打印开关（t12 起为必填：三族动作形状不同，必须逐类声明）
  'actionRequest', 'deleteMode', 'printReady'
]

/**
 * 校验单据类型配置清单。
 *
 * @param {Array} kinds `doc-kinds.js` 的 KINDS
 * @returns {{ok:boolean, problems:string[]}}
 */
function validateKinds(kinds) {
  var problems = []
  var seen = {}
  var list = kinds || []
  if (list.length !== 8) {
    problems.push('单据类型数量应为 8，实际 ' + list.length)
  }
  list.forEach(function (kind) {
    var code = kind && kind.code
    if (!code) {
      problems.push('存在没有 code 的单据类型配置')
      return
    }
    if (seen[code]) {
      problems.push('单据类型重复：' + code)
    }
    seen[code] = true
    REQUIRED_KIND_KEYS.forEach(function (key) {
      var v = kind[key]
      if (v === undefined || v === null || v === '') {
        problems.push(code + ' 缺少配置项 ' + key)
      }
    })
    if (kind.permPrefix && kind.permPrefix.indexOf(':') < 0) {
      problems.push(code + ' 的 permPrefix 必须是「域:资源」形态，实际 ' + kind.permPrefix)
    }
    if (kind.restBase && kind.restBase.charAt(0) !== '/') {
      problems.push(code + ' 的 restBase 必须以 / 开头，实际 ' + kind.restBase)
    }
    if (kind.stockDoc && PUSH_CHAINS[code] && !kind.pushDisabled) {
      problems.push(code + ' 是库存类单据，不应有下推链配置')
    }
    // 库存类单据不得出现「已完成」相关列（design D4 的风险条目）
    if (kind.stockDoc && (kind.filters || []).some(function (f) { return f.dict === 'completed' })) {
      problems.push(code + ' 为库存类单据，不应提供「已完成」筛选')
    }
  })
  ;Object.keys(PUSH_CHAINS).forEach(function (code) {
    if (!seen[code]) {
      problems.push('下推链的来源单据类型缺少配置：' + code)
    }
    var chain = PUSH_CHAINS[code]
    if (!seen[chain.to]) {
      problems.push('下推链的目标单据类型缺少配置：' + chain.to)
    }
  })
  return { ok: problems.length === 0, problems: problems }
}

/* ============================================================================
 * 七、小工具
 * ========================================================================== */

function findBy(list, key, value) {
  for (var i = 0; i < list.length; i++) {
    if (list[i][key] === value) {
      return list[i]
    }
  }
  return null
}

function toNumber(v) {
  var n = Number(v)
  return isFinite(n) ? n : 0
}

function round(n, scale) {
  var p = Math.pow(10, scale)
  return Math.round(n * p) / p
}

/** 数量显示（去掉无意义的尾随 0；不做金额四舍五入，那是 `@/utils/money` 的职责）。 */
function formatQty(v) {
  var n = toNumber(v)
  var text = n.toFixed(3)
  return text.replace(/\.?0+$/, '') || '0'
}

module.exports = {
  STATUSES: STATUSES,
  STATUS_VALUES: STATUS_VALUES,
  ACTIONS: ACTIONS,
  ALLOWED_FROM: ALLOWED_FROM,
  PUSH_CHAINS: PUSH_CHAINS,
  REQUIRED_KIND_KEYS: REQUIRED_KIND_KEYS,
  EDITABLE_STATUS: EDITABLE_STATUS,
  statusLabel: statusLabel,
  statusTagType: statusTagType,
  isValidStatus: isValidStatus,
  isEditable: isEditable,
  actionOf: actionOf,
  actionLabel: actionLabel,
  isReasonRequired: isReasonRequired,
  reasonRequiredMessage: reasonRequiredMessage,
  allowedFrom: allowedFrom,
  actionExistsForKind: actionExistsForKind,
  nextStatus: nextStatus,
  canRun: canRun,
  visibleActions: visibleActions,
  permOf: permOf,
  canEdit: canEdit,
  hasPush: hasPush,
  pushChainOf: pushChainOf,
  canPush: canPush,
  remainingQty: remainingQty,
  pushRows: pushRows,
  validatePush: validatePush,
  validatePushExtra: validatePushExtra,
  actionRequest: actionRequest,
  deleteRequest: deleteRequest,
  buildPushRequest: buildPushRequest,
  validateTransfer: validateTransfer,
  validateItems: validateItems,
  countDecimals: countDecimals,
  validateKinds: validateKinds,
  formatQty: formatQty
}
