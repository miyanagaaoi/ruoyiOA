/**
 * 动态表单保存提示文案的前端用例（2026-10-05，PRD V-8 反向校验配套）。
 *
 * 覆盖：
 *   1. 无流程告警 → success 文案（保持既有口径：'修改成功' / '…并已把 N 个模板指向新版本'）；
 *   2. 有流程告警 → **warning** 类型，文案里必须出现流程名与失效字段，且给出"去重新保存发布"的指引；
 *   3. 流程名缺失 → 回退 defKey → 回退 flowId（不能显示空白）；
 *   4. 告警超过 3 条 → 只列前 3 条并带"等"，不把弹窗撑爆；
 *   5. 响应缺字段 / 为 null → 不抛异常（保存成功不能被提示逻辑搞崩）。
 *
 * 跑法：`npm run test:unit`（= `node tests/run.js`）。
 */
'use strict'

var assert = require('assert')
var h = require('./harness')
var test = h.test
var M = require('../src/views/tool/build/saveResultText.js')

test('无告警 + 改指了 2 个模板 → success 且文案不变', function () {
  var r = M.buildFormSaveMessage({ code: 200, affectedTemplates: 2, formFlowWarnings: [] })
  assert.strictEqual(r.type, 'success')
  assert.strictEqual(r.message, '修改成功：表单已存为新版本，并已把 2 个模板指向新版本')
  assert.strictEqual(r.warningCount, 0)
})

test('无告警 + 没有模板被改指 → 只有「修改成功」', function () {
  var r = M.buildFormSaveMessage({ code: 200, affectedTemplates: 0 })
  assert.strictEqual(r.type, 'success')
  assert.strictEqual(r.message, '修改成功')
})

test('有流程告警 → warning，且带流程名 + 字段 + 处置指引', function () {
  var r = M.buildFormSaveMessage({
    code: 200,
    affectedTemplates: 1,
    formFlowWarnings: [
      { flowId: 'F1', defKey: 'htsp', flowName: '合同审批', published: true, fields: ['field101'], reason: 'x' }
    ]
  })
  assert.strictEqual(r.type, 'warning')
  assert.strictEqual(r.warningCount, 1)
  assert.ok(r.message.indexOf('合同审批') >= 0, '要说出是哪条流程：' + r.message)
  assert.ok(r.message.indexOf('field101') >= 0, '要说出哪个字段会失效：' + r.message)
  assert.ok(r.message.indexOf('重新保存并发布') >= 0, '要给出处置指引：' + r.message)
  assert.ok(r.message.indexOf('另有 1 个流程仍指向旧版本表单') >= 0, '要说明这是"错位"而不是保存失败：' + r.message)
})

test('告警项没有字段（字段仍有效、只是流程没跟随）→ 文案不出现空括号', function () {
  var r = M.buildFormSaveMessage({
    formFlowWarnings: [{ flowId: 'F1', defKey: 'htsp', flowName: '合同审批', fields: [] }]
  })
  assert.strictEqual(r.type, 'warning')
  assert.ok(r.message.indexOf('（）') < 0, '不能出现空括号：' + r.message)
})

test('流程名缺失 → 依次回退 defKey / flowId', function () {
  assert.strictEqual(M.flowLabel({ defKey: 'testParallel', flowId: 'X' }), 'testParallel')
  assert.strictEqual(M.flowLabel({ flowId: 'X' }), 'X')
  assert.strictEqual(M.flowLabel({}), '(未知流程)')
  assert.strictEqual(M.flowLabel(null), '(未知流程)')
})

test('告警超过 3 条 → 只列前 3 条并带「等」', function () {
  var warnings = []
  for (var i = 1; i <= 5; i++) {
    warnings.push({ flowId: 'F' + i, defKey: 'k' + i, flowName: '流程' + i, fields: [] })
  }
  var r = M.buildFormSaveMessage({ affectedTemplates: 0, formFlowWarnings: warnings })
  assert.strictEqual(r.warningCount, 5)
  assert.ok(r.message.indexOf('流程1') >= 0 && r.message.indexOf('流程3') >= 0)
  assert.ok(r.message.indexOf('流程4') < 0, '第 4 条不该出现在预览里：' + r.message)
  assert.ok(r.message.indexOf('等') >= 0, '要有「等」提示还有更多：' + r.message)
})

test('响应为空/畸形 → 不抛异常', function () {
  assert.strictEqual(M.buildFormSaveMessage(null).type, 'success')
  assert.strictEqual(M.buildFormSaveMessage(undefined).message, '修改成功')
  assert.strictEqual(M.buildFormSaveMessage({ affectedTemplates: 'x', formFlowWarnings: 'y' }).type, 'success')
})
