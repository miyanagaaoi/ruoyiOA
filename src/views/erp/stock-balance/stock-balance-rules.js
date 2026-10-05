/**
 * 库存明细 / 流水的**前端口径单一实现点**（B4 §8.5、tasks.md 7.1/7.3/7.4）。
 *
 * 为什么单独一个纯函数模块（与 `doc-rules.js` 同一套路）：
 *   · 库存明细页有三处"只有服务端才知道、但前端必须正确拼"的东西：筛选参数名（`productTypeIds` 数组、
 *     `belowSafetyOnly`/`hideZero` 布尔）、流水下钻要按 (物料, 仓库) + 时间区间、重算按钮的
 *     "默认只校验、修复要二次确认" 语义（tasks.md 7.4 + AC-76）；
 *   · 把这些写成纯函数就能被 `tests/*.test.js` 钉住字符串与语义，不用起浏览器。
 *
 * 真源（已落地源码）：
 *   · `ErpStockBalanceController`（`/stk/stock`）：`GET /list`（`stk:stock:list`）、
 *     `GET /detail?productId=&warehouseId=`（`stk:stock:query`）、`GET|POST /export`（`stk:stock:export`）、
 *     `POST /recalc?repair=false|true`（`stk:stock:recalc`）；返回体含派生列 `displayName` / `belowSafetyStock` / `goodsQuota`；
 *   · `ErpStockLedgerController`（`/stk/ledger`）：`GET /list`（`stk:ledger:list`）筛选
 *     `productId/warehouseId/bizTypeFilter/docTypeFilter/beginTime/endTime/keyword`；`GET /detail?id=`、`GET|POST /export`；
 *     返回体含 `qtyChange` / `qtyAfter` / `unitPrice` / `ledgerTime` / `displayName` / `reversal`。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

/** 库存明细列表端点（供 api 层与用例共用）。 */
var BALANCE_LIST_URL = '/stk/stock/list'
var BALANCE_DETAIL_URL = '/stk/stock/detail'
var BALANCE_EXPORT_URL = '/stk/stock/export'
var BALANCE_RECALC_URL = '/stk/stock/recalc'
var LEDGER_LIST_URL = '/stk/ledger/list'
var LEDGER_DETAIL_URL = '/stk/ledger/detail'
var LEDGER_EXPORT_URL = '/stk/ledger/export'

/** 库存账的权限点（真源 = 已落地 Controller 的 `@PreAuthorize`）。 */
var PERMS = {
  balanceList: 'stk:stock:list',
  balanceQuery: 'stk:stock:query',
  balanceExport: 'stk:stock:export',
  balanceRecalc: 'stk:stock:recalc',
  ledgerList: 'stk:ledger:list',
  ledgerQuery: 'stk:ledger:query',
  ledgerExport: 'stk:ledger:export'
}

/**
 * 库存明细列表查询参数（把页面表单归一成后端要的形状）。
 *
 * @param {object} filters 页面筛选（keyword/warehouseId/productTypeIds/beginQty/endQty/belowSafetyOnly/hideZero）
 * @param {object} [page]  { pageNum, pageSize }
 */
function balanceQuery(filters, page) {
  var f = filters || {}
  var p = page || {}
  return {
    pageNum: p.pageNum || 1,
    pageSize: p.pageSize || 10,
    keyword: blankToUndefined(f.keyword),
    warehouseId: blankToUndefined(f.warehouseId),
    productTypeIds: asArray(f.productTypeIds),
    beginQty: blankToUndefined(f.beginQty),
    endQty: blankToUndefined(f.endQty),
    // 开关：只传 true（不传 false 也能表达"不限"，避免服务端把 false 当成显式条件）
    belowSafetyOnly: f.belowSafetyOnly === true ? true : undefined,
    hideZero: f.hideZero === true ? true : undefined
  }
}

/**
 * 流水下钻查询参数（tasks.md 7.3：按 (物料, 仓库) 与日期区间下钻、按时间倒序）。
 *
 * ⚠ 时间用**日期时间串**（`yyyy-MM-dd HH:mm:ss`）：`beginTime/endTime` 绑的是 `Date`，
 *   而流水的时间是 `datetime`；用只有日期的串会丢掉当天的时间段（详见 notes/09b §4-H 的实测前置）。
 */
function ledgerQuery(row, range, page) {
  var r = row || {}
  var p = page || {}
  var timeRange = range || []
  return {
    pageNum: p.pageNum || 1,
    pageSize: p.pageSize || 50,
    productId: blankToUndefined(r.productId),
    warehouseId: blankToUndefined(r.warehouseId),
    beginTime: timeRange.length ? blankToUndefined(timeRange[0]) : undefined,
    endTime: timeRange.length ? blankToUndefined(timeRange[1]) : undefined
  }
}

/** 流水下钻的导出参数（与下钻同筛选，服务端负责范围裁剪）。 */
function ledgerExportQuery(row, range, extra) {
  var base = ledgerQuery(row, range, { pageNum: 1, pageSize: 1 })
  delete base.pageNum
  delete base.pageSize
  return Object.assign(base, extra || {})
}

/**
 * 库存重算请求（tasks.md 7.4）：
 *   `repair=false`（默认）**只校验**；`repair=true` 才把结存改写为流水累计 —— 后者必须由页面二次确认。
 *
 * @param {boolean} repair 是否修复
 */
function recalcRequest(repair) {
  return {
    method: 'post',
    url: BALANCE_RECALC_URL,
    params: { repair: repair === true },
    data: null
  }
}

/** 修复动作的**风险提示**（页面在二次确认里逐字展示，别写成两套文案）。 */
function repairWarningText() {
  return '「修复」会把结存数量直接改写为流水累计和，属不可回滚的运维动作：'
    + '请先确认差异原因（例如未过账的单据），并在修复前导出结存与流水留档。'
}

/** 一次重算结果的摘要文案（校验与修复共用，避免两处措辞漂移）。 */
function recalcSummary(result) {
  var r = result || {}
  var checked = toNumber(r.checkedRows)
  var inconsistent = toNumber(r.inconsistentCount)
  var parts = ['检查 ' + checked + ' 行']
  if (inconsistent === 0 && r.consistent !== false) {
    parts.push('全部一致')
  } else {
    parts.push('不一致 ' + inconsistent + ' 条')
  }
  if (r.repair === true) {
    parts.push('已修复 ' + toNumber(r.repairedCount) + ' 条')
  } else if (inconsistent > 0) {
    parts.push('未做任何改动（当前是校验模式）')
  }
  return parts.join('；')
}

/** 不一致明细行的展示文本（结存 / 流水累计 / 差值）。 */
function mismatchText(m) {
  var row = m || {}
  return '结存 ' + formatQty(row.stockQty) + ' / 流水累计 ' + formatQty(row.ledgerQty)
    + ' / 差值 ' + formatQty(row.diff)
}

/**
 * 「商品类型名称-物料名称」显示名（tasks.md 7.1 / REQ-STK-003）。
 *
 * 服务端 `ErpStockBalance.getDisplayName()` 已经算好（`ErpLedgerRules.displayName`），
 * 这里只在**没有**该字段时兜底拼一次（例如导出前的本地预览），保证前端不出现空白名称列。
 */
function displayNameOf(row) {
  var r = row || {}
  if (r.displayName) {
    return r.displayName
  }
  var type = r.productTypeName ? String(r.productTypeName) : ''
  var name = r.productName ? String(r.productName) : ''
  if (type && name) {
    return type + '-' + name
  }
  return name || r.productCode || ''
}

/** 是否低于安全库存（优先用服务端派生列，服务端没给时用 `qty < safetyStock` 兜底）。 */
function isBelowSafety(row) {
  var r = row || {}
  if (typeof r.belowSafetyStock === 'boolean') {
    return r.belowSafetyStock
  }
  var qty = toNumber(r.qty)
  var safety = toNumber(r.safetyStock)
  return safety > 0 && qty < safety
}

/* ---------------------------------------------------------------- 小工具 */

function blankToUndefined(v) {
  if (v === null || v === undefined) {
    return undefined
  }
  if (typeof v === 'string') {
    var t = v.trim()
    return t === '' ? undefined : t
  }
  return v
}

function asArray(v) {
  if (v === null || v === undefined || v === '') {
    return undefined
  }
  if (Array.isArray(v)) {
    return v.length ? v : undefined
  }
  return [v]
}

function toNumber(v) {
  var n = Number(v)
  return isFinite(n) ? n : 0
}

function formatQty(v) {
  if (v === null || v === undefined || v === '') {
    return '0'
  }
  var n = Number(v)
  if (!isFinite(n)) {
    return String(v)
  }
  var text = n.toFixed(3)
  return text.replace(/\.?0+$/, '') || '0'
}

module.exports = {
  BALANCE_LIST_URL: BALANCE_LIST_URL,
  BALANCE_DETAIL_URL: BALANCE_DETAIL_URL,
  BALANCE_EXPORT_URL: BALANCE_EXPORT_URL,
  BALANCE_RECALC_URL: BALANCE_RECALC_URL,
  LEDGER_LIST_URL: LEDGER_LIST_URL,
  LEDGER_DETAIL_URL: LEDGER_DETAIL_URL,
  LEDGER_EXPORT_URL: LEDGER_EXPORT_URL,
  PERMS: PERMS,
  balanceQuery: balanceQuery,
  ledgerQuery: ledgerQuery,
  ledgerExportQuery: ledgerExportQuery,
  recalcRequest: recalcRequest,
  repairWarningText: repairWarningText,
  recalcSummary: recalcSummary,
  mismatchText: mismatchText,
  displayNameOf: displayNameOf,
  isBelowSafety: isBelowSafety,
  formatQty: formatQty
}
