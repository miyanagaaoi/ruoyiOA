/**
 * RuoYi 响应体的**取数口径**（纯函数，单测可跑）。
 *
 * 为什么必须有这个模块（P-② 根因，2026-10-06）：
 *   `src/utils/request.js` 的响应拦截器返回的是**整个响应体**（`request.js:82` 的 `return res.data`
 *   指的是 axios 的 `res.data`，也就是服务端的 JSON 体本身）。而两类接口的 JSON 体形状不同：
 *     · **分页列表**接口（`/ctms/product/list`、`/ctms/warehouse/list`、`/ctms/contract/list`…）
 *       体是 `{code, msg, rows, total}` —— 行数组在 **`rows`**，**根本没有 `data` 键**；
 *     · **`/options` / 树**接口（`/ctms/partner/supplier/options`、`/ctms/product-type/tree`…）
 *       体是 `{code, msg, data:[…]}` —— 行数组在 **`data`**。
 *   以前选项装载一律写 `(res && res.data) || []` ⇒ 列表类接口恒得 `undefined` ⇒ **下拉全空**
 *   （8 类单据表单的仓库/物料/单位/合同选择器全废，UI 写路径无法进行）。
 *
 * 用法：列表接口用 `rowsOf(res)`，`/options` 与树接口用 `dataOf(res)`；
 * 非数组/缺失一律返回 `[]`（调用方不需要再判空）。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测，与 `erp-const.js`/`doc-rules.js` 同套路）。
 *
 * @author 二开
 */

'use strict'

/** 分页列表接口的行数组（`{rows,total}`）；缺失或非数组返回 `[]`。 */
function rowsOf(res) {
  return res && Array.isArray(res.rows) ? res.rows : []
}

/** `/options`、树等 `AjaxResult.success(list)` 形态的行数组（`{data:[…]}`）；缺失或非数组返回 `[]`。 */
function dataOf(res) {
  return res && Array.isArray(res.data) ? res.data : []
}

/** 兼容取数（**只在确实两种形状都可能出现时**用；新代码应显式选 rowsOf/dataOf）。 */
function listOf(res) {
  var rows = rowsOf(res)
  return rows.length ? rows : dataOf(res)
}

module.exports = {
  rowsOf: rowsOf,
  dataOf: dataOf,
  listOf: listOf
}
