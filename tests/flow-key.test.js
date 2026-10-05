/**
 * 「流程标识（def_key）」建议生成 + 格式校验的前端用例（2026-10-05 第四轮追加）。
 *
 * 背景：`def_key` 是**写进 BPMN 的 Flowable 流程定义 key**，有三条硬约束
 *   （格式 `[A-Za-z_][A-Za-z0-9_-]*` / 全局唯一 / 发布后不可改），
 *   而"新建流程"弹窗原来要求用户手敲 —— 实测库里 `authzPublishGuard` 被 31 条流程共用（版本到 v62）。
 *   本文件锁定 `flowKey.js` 的口径，防止"生成规则"被后来者悄悄改宽或改窄。
 *
 * 唯一的**权威**在服务端（`SimpleFlowValidator` V-0）；这里只用例钉住前端镜像口径。
 *
 * 跑法：`npm run test:unit`（= `node tests/run.js`）。
 */
'use strict'

var assert = require('assert')
var h = require('./harness')
var test = h.test
var K = require('../src/views/workflow/simple-flow/flowKey.js')

/* ==================== 建议生成：正常路径 ==================== */

test('业务词表：合同审批 → htsp（可读，不是随机码）', function () {
  assert.strictEqual(K.suggestFlowKey('合同审批'), 'htsp')
})

test('混合括号/顿号等标点直接忽略：合同审批（含用印）→ htspyyh', function () {
  // 标点若被当成"未命中"，整串会退化成 htsp_<短码> —— 实测踩过
  assert.strictEqual(K.suggestFlowKey('合同审批（含用印）'), 'htspyyh')
  assert.strictEqual(K.suggestFlowKey('合同审批(含用印)'), 'htspyyh')
})

test('英文名称原样保留并小写：Contract Approval → contractapproval', function () {
  assert.strictEqual(K.suggestFlowKey('Contract Approval'), 'contractapproval')
})

test('末尾数字用下划线隔开：合同审批2024 → htsp_2024', function () {
  assert.strictEqual(K.suggestFlowKey('合同审批2024'), 'htsp_2024')
})

test('单字母+数字的独立段不拆：B1回写链路回归 → b1hghxll', function () {
  assert.strictEqual(K.suggestFlowKey('B1回写链路回归'), 'b1hghxll')
})

test('常用业务词都能命中：付款申请 / 报销审批 / 事项审批', function () {
  assert.strictEqual(K.suggestFlowKey('付款申请'), 'sqfk')
  assert.strictEqual(K.suggestFlowKey('报销审批'), 'spbx')
  assert.strictEqual(K.suggestFlowKey('事项审批'), 'spsx')
})

test('最长优先：分管领导审批 → spfgld（"分管"整词命中，没被"领导"截胡）', function () {
  // 词表按词长倒序匹配，所以"分管"(2字) 先于"领导"(2字) 的**位置**先被占用；
  // 这里钉住的是"整词命中"这个性质：hg 型碎片不会出现
  assert.strictEqual(K.suggestFlowKey('分管领导审批'), 'spfgld')
})

/* ==================== 建议生成：兜底与边界 ==================== */

test('词表外的汉字走 flow_ + 确定性短码兜底（同名同码，可复现）', function () {
  // "栅"既不在多字词表、也不在单字表 ⇒ 整串退化为 flow_ + 短码，
  // 否则"只取命中部分"会让两个不同流程得到同一个标识。
  // （刻意不拼"命中片段 + 短码"：实测那样会得到 flow_-vcsbcu4qaxj5 这种半截乱码）
  var withUnmapped = K.suggestFlowKey('桥接验收栅栏顺序')
  var withoutUnmapped = K.suggestFlowKey('桥接验收栏顺序')
  assert.notStrictEqual(withUnmapped, withoutUnmapped, '未命中的汉字必须影响结果（否则会重名）')
  assert.strictEqual(withUnmapped, K.suggestFlowKey('桥接验收栅栏顺序'), '同名必须同码（不能是随机数）')
  assert.ok(/^flow_[a-z0-9]+$/.test(withUnmapped), '应是干净的 flow_ + 短码：' + withUnmapped)
  assert.strictEqual(K.validateFlowKey(withUnmapped), '', '兜底结果仍须是合法标识')
})

test('兜底短码里绝不能出现中划线（FNV-1a 对负数会输出 "-"，必须滤掉）', function () {
  // 实测过 `复现验证-无需高级选项` → `flow_-4qaxj5`：短码带 `-` 会让人以为是出错
  var k = K.suggestFlowKey('复现验证-无需高级选项')
  assert.strictEqual(k.indexOf('-'), -1, '短码里不该有中划线：' + k)
  assert.ok(/^flow_[a-z0-9]+$/.test(k), k)
})

test('全数字开头 + 汉字 → 以字母开头（补 flow_ 前缀）', function () {
  var k = K.suggestFlowKey('2024年度合同')
  assert.ok(/^[a-z_]/.test(k), '不能以数字开头：' + k)
})

test('空名称 / null / undefined 也能给出可用标识', function () {
  ;['', '   ', null, undefined].forEach(function (n) {
    var k = K.suggestFlowKey(n)
    assert.strictEqual(K.validateFlowKey(k), '', '空名称也要产出合法标识：' + JSON.stringify(n) + ' → ' + k)
  })
})

test('建议标识一定满足服务端格式（批量喂真实流程名）', function () {
  var names = [
    '合同审批（含用印）', '测试02-条件分支（排他路由）', '测试01-串行审批（会签/或签）',
    'RA-E2E闭环流程', '桥接验收-签批栏顺序', '合同会审', 'AUTHZ-越权发布验收',
    '基础测试模板', '2.0-测试-203558', '-以中划线开头', '_下划线开头', 'a'.repeat(80)
  ]
  names.forEach(function (n) {
    var k = K.suggestFlowKey(n)
    assert.strictEqual(K.validateFlowKey(k), '', n + ' → ' + k)
    assert.ok(k.length <= K.SUGGEST_MAX_LENGTH, '建议标识不应过长：' + k)
  })
})

/* ==================== 格式校验（与后端 V-0 同口径） ==================== */

test('合法：字母 / 下划线开头，含数字、下划线、中划线', function () {
  ;['contractApproval', '_x', 'a-b_c9', 'A1', 'flow_6u9vqm'].forEach(function (k) {
    assert.strictEqual(K.validateFlowKey(k), '', k + ' 应判为合法')
  })
})

test('非法：数字开头（BPMN 的 process id 不接受）', function () {
  assert.ok(K.validateFlowKey('1abc').indexOf('必须以字母或下划线开头') >= 0)
})

test('非法：中文 / 空格 / 括号 / 点', function () {
  ;['合同审批', 'a b', 'ht sp', '合同(1)', 'a.b'].forEach(function (k) {
    assert.ok(K.validateFlowKey(k), k + ' 应判为非法')
  })
})

test('空值：空串 / 空白 / null 都提示"不能为空"', function () {
  ;['', '   ', null, undefined].forEach(function (k) {
    assert.strictEqual(K.validateFlowKey(k), '流程标识不能为空')
  })
})

test('超长：超过列宽（varchar(100)）要拦下并说明当前长度', function () {
  var msg = K.validateFlowKey('a'.repeat(K.MAX_KEY_LENGTH + 1))
  assert.ok(msg.indexOf('不能超过 ' + K.MAX_KEY_LENGTH) >= 0, msg)
  assert.strictEqual(K.validateFlowKey('a'.repeat(K.MAX_KEY_LENGTH)), '', '正好 100 位应合法')
})

/* ==================== 撞名备选 ==================== */

test('撞名备选：base → base_2；已被占用则顺延', function () {
  assert.strictEqual(K.suggestAlternativeKey('htsp', []), 'htsp_2')
  assert.strictEqual(K.suggestAlternativeKey('htsp', ['htsp_2', 'htsp_3']), 'htsp_4')
})

test('撞名备选：结果同样满足格式（可作为流程标识直接提交）', function () {
  var alt = K.suggestAlternativeKey('contractApproval', ['contractApproval_2'])
  assert.strictEqual(K.validateFlowKey(alt), '')
  assert.strictEqual(alt, 'contractApproval_3')
})

/* ==================== 词表自身的约束 ==================== */

test('词表：缩写一律是小写字母/数字，且不含格式非法字符', function () {
  Object.keys(K.WORD_MAP).forEach(function (word) {
    var abbr = K.WORD_MAP[word]
    assert.ok(/^[a-z0-9]+$/.test(abbr), word + ' → ' + abbr + ' 不是合法缩写')
  })
})

test('词表：不出现"同一缩写映射到不相干业务"的明显冲突（抽查高风险词）', function () {
  // 这几组如果撞了，会让标识难以区分业务，属于必须人工确认的改动
  assert.notStrictEqual(K.WORD_MAP['合同'], K.WORD_MAP['会签'])
  assert.notStrictEqual(K.WORD_MAP['审批'], K.WORD_MAP['会审'])
  assert.notStrictEqual(K.WORD_MAP['财务'], K.WORD_MAP['法务'])
})
