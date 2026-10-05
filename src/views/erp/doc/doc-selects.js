import { loadOptions, emptyOptions } from './doc-options'
import { getDicts } from '@/api/system/dict/data'
import { describeError } from '@/utils/errorMessage'
import { dictRowsToOptions, masterOptionsOf } from './doc-select-shape'

/**
 * 单据列表壳 / 表单壳共用的"下拉与字典"加载逻辑（**一份实现**，见 D12 的复用要求）。
 *
 * 为什么是"函数 + 传入 vm"而不是 `mixins: [x]`：
 *   静态审计 `tools/audit/audit-template-refs.js` 解析 mixin 时只认 `.vue`（它按
 *   `<script>` 块取 AST），**普通 `.js` mixin 里的 data 键不会被算作"已声明"** ——
 *   于是模板里的 `options` / `dictFailed` 会被判成"引用了不存在的名字"，
 *   把 `template-refs` 的基线门禁打红。改成普通函数后：
 *     · 逻辑仍然只有一份（本文件）；
 *     · 状态键由两个壳各自在 `data()` 里**显式声明**（审计看得见），不靠 mixin 推断。
 *
 * 用法：
 *   data() { return Object.assign({...}, {}) } —— 不要这么写（审计的 data 解析要求对象字面量），
 *   而是把 `options/dictData/dictFailed` 三个键写进 data 的对象字面量，
 *   再在 created 里调 `loadSelectsInto(this)` 与 `loadDictsInto(this, fields)`。
 */

/** 下拉选项的初始值。 */
export function emptySelectOptions() {
  return emptyOptions()
}

/** 字典选项（未加载/加载失败时返回空数组，不返回 undefined 以免模板报错）。 */
export function dictOptionsOf(vm, name) {
  return (vm.dictData && vm.dictData[name]) || []
}

/**
 * 字典选项 → **el-select 选项**（`{value,label}`）。
 *
 * ⚠ 与 `dictOptionsOf` 的区别不是风格问题：字典行是 `{dictLabel,dictValue}`，
 *   若直接喂给 `<el-option :label="o.label" :value="o.value">` 会渲染出**空条目**
 *   （DOM 里有点选行、却没有文字）—— 这正是"字典下拉看起来是空的"的原因（P-② 伴随缺陷）。
 *   表格里的 `<dict-tag>` 仍需**原始字典行**（`dictOptionsOf`）。
 */
export function dictSelectOptions(vm, name) {
  return dictRowsToOptions(dictOptionsOf(vm, name))
}

/**
 * 列表筛选项的选项集合（`{value,label}`）——把三类来源收敛到一处：
 *   · 字典筛选（`f.dict`）：入库类型/出库类型/盘点范围；
 *   · 状态筛选（`f.options === 'docStatuses'`）：前端自带真源；
 *   · 主数据筛选（`f.options === 'warehouses' | 'suppliers' | 'customers'`）。
 */
export function filterSelectOptions(vm, field) {
  const f = field || {}
  if (f.dict) {
    return dictSelectOptions(vm, f.dict)
  }
  const key = f.options
  if (key === 'docStatuses') {
    return (vm.options && vm.options.docStatuses) || []
  }
  return masterOptionsOf((vm.options && key && vm.options[key]) || [])
}

/** 收集 `fields` 里声明的字典类型并加载（每个类型一次请求）。 */
export function loadDictsInto(vm, fields) {
  const names = []
  ;(fields || []).forEach((f) => {
    if (f && f.dict && names.indexOf(f.dict) < 0) {
      names.push(f.dict)
    }
  })
  names.forEach((name) => {
    getDicts(name)
      .then((res) => {
        vm.$set(vm.dictData, name, (res && res.data) || [])
      })
      .catch((err) => {
        const d = describeError(err)
        vm.dictFailed.push(name + '：' + d.text)
      })
  })
}

/** 按单据配置加载下拉（单个失败只记一条提示，不拖垮整页）。 */
export function loadSelectsInto(vm) {
  loadOptions(vm.kind)
    .then((opts) => {
      vm.options = opts
    })
    .catch((err) => {
      const d = describeError(err)
      const empty = emptyOptions()
      empty.failed.push(d.text)
      vm.options = empty
    })
}
