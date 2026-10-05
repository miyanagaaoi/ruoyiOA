/**
 * **下拉选项的形状转换**（纯函数，单测可跑）——单据页"下拉全空"的第二类根因（P-② 伴随缺陷）。
 *
 * 背景（2026-10-06 从 qa-hardening 的 Playwright trace 里定位）：
 *   修好"列表体读 `rows`"之后，仓库下拉有选项了，但**字典下拉（入库类型等）与状态筛选仍然空**，
 *   而且更阴：字典 `el-option` 循环的是**原始字典行**（`/system/dict/data/type/{t}` 的
 *   `{dictLabel, dictValue}`），模板却写 `:label="o.label" :value="o.value"` ⇒ 选项**渲染成了空条目**
 *   （DOM 里有 `.el-select-dropdown__item`，但文字为空，自动化用例与用户都"看不到选项"）。
 *
 * 三处真源与形状（必须分开，不能混用）：
 *   · 字典行 `{dictLabel, dictValue}` → el-select 选项 `{value,label}`（`dictRowsToOptions`）；
 *   · 状态列表（前端自带真源 `doc-rules.STATUSSES`）→ `{value,label}`（`statusOptionsOf`）；
 *   · 主数据档案行（仓库/物料/单位/往来单位：`{id,name,shortName?,code?}`）→ `{value,label}`（`masterOptionsOf`）。
 *
 * 注意：表格里的 `<dict-tag :options="…">` 需要的是**原始字典行**，不要用本模块的转换结果去喂它。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

/** 字典行 → el-select 选项。 */
function dictRowsToOptions(rows) {
  return (rows || [])
    .filter(function (row) {
      return !!row
    })
    .map(function (row) {
      return { value: row.dictValue, label: row.dictLabel }
    })
}

/** 状态列表（`doc-rules.STATUSSES`）→ el-select 选项。 */
function statusOptionsOf(statuses) {
  return (statuses || []).map(function (s) {
    return { value: s.value, label: s.label }
  })
}

/** 主数据档案行 → el-select 选项（显示名优先简称，再名称、编码）。 */
function masterOptionsOf(list) {
  return (list || [])
    .filter(function (item) {
      return !!item
    })
    .map(function (item) {
      return { value: item.id, label: item.shortName || item.name || item.code || item.id }
    })
}

module.exports = {
  dictRowsToOptions: dictRowsToOptions,
  statusOptionsOf: statusOptionsOf,
  masterOptionsOf: masterOptionsOf
}
