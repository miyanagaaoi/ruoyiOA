/**
 * **选项需求推导**（纯函数，CommonJS，单测可跑）：按单据配置算出"要加载哪些下拉"。
 *
 * 为什么单独一个模块：这是"要不要发请求"的唯一判据，必须能被单测**直接调用**（不能只靠读源码），
 * 而且它出过一回 P-⑤ 事故（见下），所以从 `doc-options.js`（ESM，带 api 依赖）里抽出来。
 *
 * ⚠⚠ **P-⑤ 事故（2026-10-06）**：曾把"需要物料 ⇒ 需要单位"的联动判定写在
 *   `filters` 扫描**之后**、`headerFields`/`itemColumns` 扫描**之前**：
 *     ```
 *     filters.forEach(scan)
 *     if (need.products) need.uoms = true      ← 此刻 products 还可能没被置位
 *     headerFields.forEach(scan)
 *     itemColumns.forEach(...)                 ← 8 类单据的 products 正是**在这里**置位的
 *     ```
 *   8 类单据的物料选择器来自**行项列**（`itemColumns[col.kind === 'product']`），
 *   于是 `need.uoms` **恒 false** ⇒ **单位列表从未请求** ⇒ 行项「单位」列空白、
 *   行项快照的 `uomDecimals` 为 `null` ⇒ **数量精度静默退回默认 3 位**（用户可感知的精度错误）。
 *   ⇒ 修法与纪律：**所有"A 需要 ⇒ B 需要"的联动判定必须写在三个 scan 全部跑完之后**；
 *     本文件里 `if (need.products) need.uoms = true` 刻意放在最后一行（有用例钉住位置）。
 *
 * 三类扫描来源（与 `doc-kinds` 的字段声明一一对应）：
 *   · `filters`      —— 列表筛选（`options: 'warehouses'|'suppliers'|'customers'|'products'|'uoms'` 或 `dict`）；
 *   · `headerFields` —— 表头字段（`kind: 'warehouse'|'supplier'|'customer'|'contract'`）；
 *   · `itemColumns`  —— 行项列（`kind: 'product'`、`kind: 'warehouse'` 且单据 `showWarehouse`）。
 *
 * @param {object} kind 单据类型配置（doc-kinds 的一项）
 * @returns {{products:boolean,warehouses:boolean,uoms:boolean,suppliers:boolean,customers:boolean,purchaseContracts:boolean,saleContracts:boolean}}
 */

'use strict'

/** 该单据配置声明要用到哪些下拉（看 filters 的 `options` 与 headerFields/itemColumns 的 `kind`）。 */
function neededOptions(kind) {
  var need = {
    products: false,
    warehouses: false,
    uoms: false,
    suppliers: false,
    customers: false,
    purchaseContracts: false,
    saleContracts: false
  }

  function scan(field) {
    if (!field) return
    if (field.options === 'products') need.products = true
    if (field.options === 'warehouses') need.warehouses = true
    if (field.options === 'uoms') need.uoms = true
    if (field.options === 'suppliers') need.suppliers = true
    if (field.options === 'customers') need.customers = true
    if (field.kind === 'product') need.products = true
    if (field.kind === 'warehouse') need.warehouses = true
    if (field.kind === 'supplier') need.suppliers = true
    if (field.kind === 'customer') need.customers = true
    if (field.kind === 'contract') {
      if (field.contractDirection === 'sale') need.saleContracts = true
      else need.purchaseContracts = true
    }
  }

  // ---- 三个 scan：只负责"声明了就直接置位"，彼此无先后依赖 ----
  ;(kind && kind.filters ? kind.filters : []).forEach(scan)
  ;(kind && kind.headerFields ? kind.headerFields : []).forEach(scan)
  // 行项物料选择器与仓库列（8 类单据的 products 主要来自这里）
  if (kind && kind.itemColumns) {
    kind.itemColumns.forEach(function (col) {
      if (col.kind === 'product') need.products = true
      if (col.kind === 'warehouse' && kind.showWarehouse) need.warehouses = true
    })
  }

  // ---- 联动推导：**必须**在三个 scan 之后（P-⑤；有用例钉住这一行在函数末尾）----
  // 行项快照要写单位名与单位小数位（`uomDecimals`），而物料档案本身不含这两列 ⇒ 选物料即需要单位选项。
  if (need.products) need.uoms = true

  return need
}

module.exports = {
  neededOptions: neededOptions
}
