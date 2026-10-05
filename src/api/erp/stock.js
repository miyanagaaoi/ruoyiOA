import request, { download } from '@/utils/request'
import rules from '@/views/erp/stock-balance/stock-balance-rules'

/**
 * 库存账接口（B4 §8.5；tasks.md 7.1/7.3/7.4）。
 *
 * URL 与参数形状来自 `stock-balance-rules.js`（纯函数，单测钉住），本文件只负责"发出去"。
 *   · 结存：`GET /stk/stock/list`（`stk:stock:list`）、`GET /stk/stock/detail?productId=&warehouseId=`（`stk:stock:query`）、
 *     `GET|POST /stk/stock/export`（`stk:stock:export`）、`POST /stk/stock/recalc?repair=`（`stk:stock:recalc`）
 *   · 流水：`GET /stk/ledger/list`（`stk:ledger:list`）、`GET /stk/ledger/detail?id=`（`stk:ledger:query`）、
 *     `GET|POST /stk/ledger/export`（`stk:ledger:export`）
 *
 * ⚠ 结存**没有写入口**（设计 D2）：唯一的写动作是 `recalc?repair=true`，它由 `stk:stock:recalc`
 *   单独授权（页面必须二次确认 + 风险提示，见 `repairWarningText()`）。
 */

/** 库存明细（结存）列表。 */
export function listBalance(query) {
  return request({ url: rules.BALANCE_LIST_URL, method: 'get', params: query })
}

/** 结存明细（按物料 + 仓库下钻，返回同 (物料,仓库) 的流水累计等）。 */
export function getBalanceDetail(productId, warehouseId) {
  return request({
    url: rules.BALANCE_DETAIL_URL,
    method: 'get',
    params: { productId: productId, warehouseId: warehouseId }
  })
}

/** 导出当前筛选的结存（服务端写二进制流；列与顺序的真源是后端 `@Excel`）。 */
export function exportBalance(query, filename) {
  return download(rules.BALANCE_EXPORT_URL, query, filename || '库存明细.xlsx')
}

/**
 * 库存重算（tasks.md 7.4）。
 *
 * @param {boolean} repair `false`（默认）只校验；`true` 才把结存改写为流水累计
 */
export function recalcStock(repair) {
  const req = rules.recalcRequest(repair)
  return request({ url: req.url, method: req.method, params: req.params })
}

/** 库存流水列表（按 (物料, 仓库) + 时间区间下钻；只增不改，无写入口）。 */
export function listLedger(query) {
  return request({ url: rules.LEDGER_LIST_URL, method: 'get', params: query })
}

/** 单条流水（抽屉里点某行时取详情；`reversal`/`displayName` 由服务端算）。 */
export function getLedgerDetail(id) {
  return request({ url: rules.LEDGER_DETAIL_URL, method: 'get', params: { id: id } })
}

/** 导出当前筛选的流水。 */
export function exportLedger(query, filename) {
  return download(rules.LEDGER_EXPORT_URL, query, filename || '库存流水.xlsx')
}
