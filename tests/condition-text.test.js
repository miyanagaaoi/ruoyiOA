/**
 * 分支条件文字与表单字段提取的前端用例（2026-10-05 缺陷修复）。
 *
 * 覆盖：
 *   1. 条件文字**只显示字段 label**（不显示 vModel）—— 修复前显示 `field101 = 经营`，
 *      而字段名称在设计器里叫"合同类型"；表单没设业务名时（label 仍是控件类型名）也要如实显示；
 *   2. 比较值：按选项 label 显示（落库写的是 label，见 DynamicFormDataImpl）；
 *   3. 字段查不到时**回退显示 vModel**（绝不显示空白，否则用户不知道条件引用了什么）；
 *   4. 「且 / 或 / 兜底」语义与 EMPTY / NOT_EMPTY 不接值；
 *   5. `extractFormFields` / `extractFieldOptions` 对行容器递归、排除不可作条件控件。
 *
 * 跑法：`npm run test:unit`（= `node tests/run.js`）。
 */
'use strict'

var assert = require('assert')
var h = require('./harness')
var test = h.test
var C = require('../src/views/workflow/simple-flow/conditionText.js')
var S = require('../src/utils/formSchema.js')

/* 夹具：与真库「合同类审批单」同形（field101 曾把 label 留成控件类型名「单选框组」） */
var FIELDS = [
  { vModel: 'title', label: '合同标题', tag: 'el-input', required: true, multi: false, conditionable: true },
  { vModel: 'field101', label: '合同类型', tag: 'el-radio-group', required: true, multi: false, conditionable: true },
  { vModel: 'field103', label: '关联审批', tag: 'design-related-approval', required: false, multi: false, conditionable: false },
  { vModel: 'field104', label: '金额', tag: 'design-amount', required: false, multi: false, conditionable: true }
]
var OPTIONS = { field101: [{ label: '经营', value: 1 }, { label: '经济', value: 2 }] }
var LABELS = C.fieldLabelMap(FIELDS)

function branch(rows, extra) {
  return Object.assign({ name: '分支1', defaultBranch: false, groups: [{ logic: 'AND', rows: rows }] }, extra || {})
}

/* ==================== 1. 显示 label，不显示 vModel ==================== */

test('只显示 label：字段 field101 + label 合同类型 → 「当 合同类型 = 经营 时」', function () {
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 合同类型 = 经营 时')
})

test('不出现 vModel 字面量（修复前是「当 field101 = 经营」）', function () {
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  var text = C.formatConditionText(b, LABELS, OPTIONS)
  assert.strictEqual(text.indexOf('field101'), -1, '条件文字里不应出现 vModel')
})

test('字段没填业务名时如实显示 label（label 仍是控件类型名「单选框组」）', function () {
  var labels = C.fieldLabelMap([{ vModel: 'field101', label: '单选框组' }])
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  assert.strictEqual(C.formatConditionText(b, labels, OPTIONS), '当 单选框组 = 经营 时')
})

test('字段查不到 label → 回退显示 vModel（不能显示空白）', function () {
  var b = branch([{ field: 'ghostField', op: 'EQ', value: 'X' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 ghostField = X 时')
})

test('未传 labelMap（未关联表单）→ 全部回退 vModel，行为与升级前一致', function () {
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  assert.strictEqual(C.formatConditionText(b, null, null), '当 field101 = 经营 时')
})

/* ==================== 2. 比较值按选项 label 显示 ==================== */

test('值按选项 label 显示：存 1（选项 value）也显示成「经营」', function () {
  var b = branch([{ field: 'field101', op: 'EQ', value: '1' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 合同类型 = 经营 时')
})

test('没有选项表的字段：值原样显示', function () {
  var b = branch([{ field: 'title', op: 'CONTAINS', value: '框架' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 合同标题 包含 框架 时')
})

test('未填值 → 显示「？」而不是空白', function () {
  var b = branch([{ field: 'title', op: 'EQ', value: '' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 合同标题 = ？ 时')
})

/* ==================== 3. 且 / 或 / 兜底 / 无值比较符 ==================== */

test('组内为「且」，组间为「或」', function () {
  var b = {
    groups: [
      { logic: 'AND', rows: [{ field: 'title', op: 'EQ', value: 'A' }, { field: 'field101', op: 'EQ', value: '经营' }] },
      { logic: 'AND', rows: [{ field: 'field104', op: 'GT', value: 10000 }] }
    ]
  }
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS),
    '当 合同标题 = A 且 合同类型 = 经营 或 金额 > 10000 时')
})

test('EMPTY / NOT_EMPTY 不接值', function () {
  var b = branch([{ field: 'title', op: 'NOT_EMPTY', value: '' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '当 合同标题 不为空 时')
})

test('空行被忽略；全空 → 「未设置条件」', function () {
  var b = branch([{ field: '', op: 'EQ', value: '' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS), '未设置条件')
})

test('inline 模式不带「当…时」，供嵌在别的句子里', function () {
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, OPTIONS, { inline: true }), '合同类型 = 经营')
})

/* ==================== 5. 值控件类型：枚举字段必须给选择器（2026-10-05 追加） ==================== */

/* 夹具：单选组 + 下拉 + 自由输入 + 复选组（多选不算枚举单值） */
var PICKER_FIELDS = [
  { vModel: 'field101', label: '合同类型', tag: 'el-radio-group', required: true, multi: false, conditionable: true },
  { vModel: 'contractType', label: '合同类型', tag: 'el-select', required: true, multi: false, conditionable: true },
  { vModel: 'title', label: '合同标题', tag: 'el-input', required: true, multi: false, conditionable: true },
  { vModel: 'jointDepts', label: '会审部门', tag: 'el-checkbox-group', required: false, multi: true, conditionable: true }
]
var PICKER_OPTIONS = {
  field101: [{ label: '经营', value: 1 }, { label: '经济', value: 2 }],
  contractType: [{ label: '经营', value: '1' }, { label: '经济', value: '2' }],
  jointDepts: [{ label: '李娜 · 财务部', value: 'uuid-1' }]
}

test('单选组字段 → 用选择器', function () {
  assert.strictEqual(C.useValuePicker('field101', PICKER_FIELDS, PICKER_OPTIONS), true)
})

test('下拉字段 → 用选择器', function () {
  assert.strictEqual(C.useValuePicker('contractType', PICKER_FIELDS, PICKER_OPTIONS), true)
})

test('自由输入/金额类字段 → 仍是输入框（无候选值）', function () {
  assert.strictEqual(C.useValuePicker('title', PICKER_FIELDS, PICKER_OPTIONS), false)
})

test('复选组（多选）→ 不用单值选择器（条件无法表达"等于某一个"）', function () {
  assert.strictEqual(C.useValuePicker('jointDepts', PICKER_FIELDS, PICKER_OPTIONS), false)
})

test('未关联表单 / 无选项表 → 一律回退输入框（不能把值控件变没）', function () {
  assert.strictEqual(C.useValuePicker('field101', PICKER_FIELDS, {}), false)
  assert.strictEqual(C.useValuePicker('field101', [], PICKER_OPTIONS), false)
  assert.strictEqual(C.useValuePicker('', PICKER_FIELDS, PICKER_OPTIONS), false)
})

test('选择器候选 = 选项的 label（**写进条件的值是 label**）', function () {
  var opts = C.pickerOptions('field101', PICKER_FIELDS, PICKER_OPTIONS)
  assert.deepStrictEqual(opts.map(function (o) { return o.label }), ['经营', '经济'])
})

test('口径锁定：条件值写 label 而非选项 value —— 与后端 convertValueToLabel 一致', function () {
  // 后端在提交时把 1 → 经营 之后才写进流程变量，所以条件里存 label 才命中；
  // 若写成选项 value（1），运行期恒不成立。
  var b = branch([{ field: 'field101', op: 'EQ', value: '经营' }])
  assert.strictEqual(C.formatConditionText(b, LABELS, PICKER_OPTIONS), '当 合同类型 = 经营 时')
  var wrong = branch([{ field: 'field101', op: 'EQ', value: '1' }])
  assert.strictEqual(C.formatConditionText(wrong, LABELS, PICKER_OPTIONS), '当 合同类型 = 经营 时',
    '库里万一是 value 也要显示成 label')
})

test('needsValue：EMPTY / NOT_EMPTY 不需要值控件', function () {
  assert.strictEqual(C.needsValue('EQ'), true)
  assert.strictEqual(C.needsValue('EMPTY'), false)
  assert.strictEqual(C.needsValue('NOT_EMPTY'), false)
})

/* ==================== 4. 提取字段 / 选项 ==================== */

test('extractFormFields：行容器 children 递归，纯排版控件标记为不可作条件', function () {
  var content = JSON.stringify({
    fields: [
      {
        __config__: { layout: 'rowFormItem', children: [
          { __vModel__: 'a', __config__: { tag: 'el-input', label: '甲', required: true } },
          { __vModel__: 'b', __config__: { tag: 'design-section', label: '分组标题' } }
        ] }
      },
      { __vModel__: 'c', __config__: { tag: 'el-checkbox-group', label: '会审部门', required: false } }
    ]
  })
  var fs = S.extractFormFields(content)
  assert.strictEqual(fs.length, 3)                     // a / b / c 都被提取（递归生效）
  assert.strictEqual(fs[0].vModel, 'a')
  assert.strictEqual(fs[0].conditionable, true)
  assert.strictEqual(fs[1].vModel, 'b')
  assert.strictEqual(fs[1].conditionable, false)       // 排版控件不可作条件
  assert.strictEqual(fs[2].multi, true)
  assert.deepStrictEqual(S.conditionableFields(fs).map(function (f) { return f.vModel }), ['a', 'c'])
})

test('extractFormFields：坏 JSON 返回空数组（不抛）', function () {
  assert.deepStrictEqual(S.extractFormFields('{ not json'), [])
})

test('extractFieldOptions：取出选项 label/value', function () {
  var content = JSON.stringify({
    fields: [
      { __vModel__: 'field101', __config__: { tag: 'el-radio-group', label: '合同类型' },
        __slot__: { options: [{ label: '经营', value: 1 }, { label: '经济', value: 2 }] } },
      { __vModel__: 'title', __config__: { tag: 'el-input', label: '合同标题' } }
    ]
  })
  var opts = S.extractFieldOptions(content)
  assert.deepStrictEqual(Object.keys(opts), ['field101'])
  assert.strictEqual(opts.field101.length, 2)
  assert.strictEqual(opts.field101[0].label, '经营')
})

test('requiredFieldNames / multiFieldNames 口径不变', function () {
  assert.deepStrictEqual(S.requiredFieldNames(FIELDS), ['title', 'field101'])
  assert.deepStrictEqual(S.multiFieldNames(FIELDS), [])
})
