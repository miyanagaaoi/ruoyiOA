/**
 * 打印件「版式与生效项」的前端用例（2.0 B2 §3.2 / §4.2 / §5.1 / §5.4）。
 *
 * 覆盖的验收：
 *   AC-62 内置模板不再强制关签批栏（内置 + 开启 → 出签名区；内置 + 关闭 → 不出）
 *   AC-64 纸张与方向生效（A4 纵向 / A3 横向 / 未配置 → A4 纵向），页数测算随之参数化
 *   AC-66 四处打印入口口径一致（静态断言，见 print-entries.test.js）
 *   AC-67 留痕先写后打；留痕失败**不调起打印**
 */
'use strict'

var assert = require('assert')
var h = require('./harness')
var test = h.test
var L = require('../src/views/workflow/print/printLayout.js')
var G = require('../src/views/workflow/print/printGate.js')

/** 模拟后端返回的内置模板：id 为空 = 内置 */
function builtin(key, overrides) {
  return Object.assign({ id: null, builtinKey: key, paper: 'A4', orientation: 'portrait' }, overrides || {})
}
/** 自定义模板：id 有值 */
function custom(overrides) {
  return Object.assign({ id: 'PT-1', paper: 'A4', orientation: 'portrait' }, overrides || {})
}

/* ==================== AC-62 签批栏与"是否内置"解耦 ==================== */

test('AC-62 内置 fund 版式 + 签批栏开启 → 输出签名区', function () {
  // 升级前这里恒为 false（isBuiltinTpl 把内置模板的签批栏强制关掉），
  // 于是《集团资金审批单》"看着配好了、打出来没有签名区"
  assert.strictEqual(L.shouldShowSignature(builtin('fund', { showSignature: '1' })), true)
})

test('AC-62 内置模板 + 签批栏关闭 → 不输出签名区', function () {
  assert.strictEqual(L.shouldShowSignature(builtin('contract', { showSignature: '0' })), false)
  assert.strictEqual(L.shouldShowSignature(builtin('contract', {})), false)
})

test('AC-62 显式关闭签批栏对自定义模板同样生效（不让"是否内置"参与判定）', function () {
  assert.strictEqual(L.shouldShowSignature(custom({ showSignature: '1' })), true)
  assert.strictEqual(L.shouldShowSignature(custom({ showSignature: '0' })), false)
  assert.strictEqual(L.shouldShowSignature(null), false)
})

test('AC-62 附件清单同样只看配置值', function () {
  assert.strictEqual(L.shouldShowAttachment(builtin('fund', { showAttachment: '1' })), true)
  assert.strictEqual(L.shouldShowAttachment(builtin('fund', { showAttachment: '0' })), false)
  assert.strictEqual(L.shouldShowAttachment(custom({ showAttachment: '1' })), true)
})

test('AC-62 fund 版式带出「集团职能部门/集团分管领导/集团董事长」三栏签名区', function () {
  var cols = L.signColumns(FUND_MAP)
  assert.deepStrictEqual(cols.map(function (c) { return c.label }),
    ['集团职能部门', '集团分管领导', '集团董事长'])
})

test('AC-62 contract 版式不带固定签名栏（保持升级前的按节点动态出栏）', function () {
  assert.deepStrictEqual(L.signColumns(CONTRACT_MAP), [])
  assert.ok(L.signSection(CONTRACT_MAP), '签批栏区本身仍应存在')
})

test('AC-62 payment 版式的「制单人」由内置变量取发起人', function () {
  var cols = L.signColumns(PAYMENT_MAP)
  assert.deepStrictEqual(cols.map(function (c) { return c.label }),
    ['经理', '财务', '部门负责人', '财务负责人', '制单人', '领款人'])
  var maker = cols.filter(function (c) { return c.label === '制单人' })[0]
  assert.strictEqual(maker.field, '$submitter')
})

test('AC-62 固定签名栏按节点名匹配；匹配不到时也要出空栏（供手写）', function () {
  var cols = L.signColumns(FUND_MAP)
  var nodes = [
    { nodeName: '集团职能部门', assigneeName: '张伟' },
    { nodeName: '集团分管领导', assigneeName: '李娜' },
    { nodeName: '别的节点', assigneeName: '赵敏' }
  ]
  var matched = L.matchNodesForColumn(nodes, cols[0])
  assert.strictEqual(matched.length, 1)
  assert.strictEqual(matched[0].assigneeName, '张伟')
  // 第三栏（集团董事长）没有对应节点 → 匹配结果为空，但栏目本身仍然渲染
  assert.deepStrictEqual(L.matchNodesForColumn(nodes, cols[2]), [])
})

test('AC-62 抄送栏按 showCcNode 出栏', function () {
  assert.strictEqual(L.shouldShowCcNode({ showCcNode: '1' }), true)
  assert.strictEqual(L.shouldShowCcNode({ showCcNode: '0' }), false)
  assert.strictEqual(L.shouldShowCcNode({}), false)
})

/* ==================== AC-61 / AC-63 版式解析 ==================== */

test('AC-63 版式常量由后端返回，前端只解析不持有副本', function () {
  var secs = L.fieldSections(FUND_MAP)
  assert.strictEqual(secs.length, 1)
  assert.deepStrictEqual(labelsOf(secs[0]),
    ['审批单位', '事项分类', '计划类别', '付款归属期', '资金审批内容'])
})

test('AC-63 payment 版式支持多个字段表区（抬头 / 收款信息 / 付款信息）与标题', function () {
  var secs = L.fieldSections(PAYMENT_MAP)
  assert.deepStrictEqual(secs.map(function (s) { return s.id }), ['head', 'payee', 'pay'])
  assert.strictEqual(secs[1].title, '收款信息')
  assert.strictEqual(secs[2].title, '付款信息')
  assert.ok(labelsOf(secs[2]).indexOf('金额大写') >= 0)
})

test('AC-63 非法 JSON / 空版式不抛异常，退回空结果', function () {
  assert.deepStrictEqual(L.fieldSections('{不是 JSON'), [])
  assert.deepStrictEqual(L.fieldSections(null), [])
  assert.strictEqual(L.signSection('{不是 JSON'), null)
  assert.deepStrictEqual(L.signColumns('{不是 JSON'), [])
})

test('AC-61 资金版式「审批单位」取发起人所属公司（新增内置变量）', function () {
  var data = { submitter: '张伟', submitterDept: '行政部', submitterCompany: '测试科技有限公司' }
  assert.strictEqual(L.builtinValue('$submitterCompany', data), '测试科技有限公司')
  assert.strictEqual(L.builtinValue('$submitterDept', data), '行政部')
  // 没有公司值时退回部门值，不让栏目空着
  assert.strictEqual(L.builtinValue('$submitterCompany', { submitterDept: '行政部' }), '行政部')
  assert.strictEqual(L.builtinValue('$submitterCompany', {}), '')
})

test('AC-61 内置变量仍取得到既有值（$submitter / $submitTime / $instanceStatus）', function () {
  var data = { submitter: '李娜', submitTime: '2026-10-05 07:00', instanceStatus: '已结束', businessNo: 'B-1' }
  var fmt = function (v) { return v ? String(v) : '' }
  assert.strictEqual(L.builtinValue('$submitter', data, fmt), '李娜')
  assert.strictEqual(L.builtinValue('$submitTime', data, fmt), '2026-10-05 07:00')
  assert.strictEqual(L.builtinValue('$instanceStatus', data, fmt), '已结束')
  assert.strictEqual(L.builtinValue('$businessNo', data, fmt), 'B-1')
  assert.strictEqual(L.builtinValue('$不存在的变量', data, fmt), '')
})

/* ==================== AC-61 零回归：内置 contract 仍按表单自动排版 ==================== */

test('AC-61 内置 contract 版式仍走"按表单排版"（存量单据零回归）', function () {
  // 存量模板 builtin_print_key 取默认值 contract：若改成按 13 行合同映射排版，
  // 那些自定义表单字段（金额/说明…）会从打印件上整片消失
  assert.strictEqual(L.useBuiltinLabelLayout(builtin('contract')), false)
})

test('AC-61 新增的三套内置版式以版式常量为准', function () {
  assert.strictEqual(L.useBuiltinLabelLayout(builtin('fund')), true)
  assert.strictEqual(L.useBuiltinLabelLayout(builtin('matter')), true)
  assert.strictEqual(L.useBuiltinLabelLayout(builtin('payment')), true)
})

test('AC-61 自定义模板不受内置版式判定影响', function () {
  assert.strictEqual(L.useBuiltinLabelLayout(custom({ builtinKey: 'fund' })), false)
  assert.strictEqual(L.useBuiltinLabelLayout(null), false)
})

/* ==================== AC-64 纸张与方向 ==================== */

test('AC-64 未配置纸张或方向时按 A4 纵向渲染', function () {
  var m = L.layoutMetrics({})
  assert.strictEqual(m.paper, 'A4')
  assert.strictEqual(m.orientation, 'portrait')
  assert.strictEqual(m.landscape, false)
  assert.strictEqual(m.pageWidthMm, 210)
  assert.strictEqual(m.pageHeightMm, 297)
  // 版心高度 = 297 - 12 - 12 = 273mm
  assert.ok(Math.abs(m.contentHeightPx - 273 * (96 / 25.4)) < 1e-6)
})

test('AC-64 脏值纸张/方向一律退回 A4 纵向（不抛异常）', function () {
  assert.strictEqual(L.layoutMetrics({ paper: 'B5' }).paper, 'A4')
  assert.strictEqual(L.layoutMetrics({ paper: null }).paper, 'A4')
  assert.strictEqual(L.layoutMetrics({ orientation: 'diagonal' }).orientation, 'portrait')
  assert.strictEqual(L.layoutMetrics({ orientation: 'LANDSCAPE' }).orientation, 'landscape')
  assert.strictEqual(L.layoutMetrics({ paper: 'a4' }).paper, 'A4')
})

test('AC-64 A4 纵向：版心 273mm，一页内容算 1 页、超出算 2 页', function () {
  var tpl = { paper: 'A4', orientation: 'portrait' }
  var one = L.layoutMetrics(tpl).contentHeightPx
  assert.strictEqual(L.measurePageCount(one - 1, tpl), 1)
  assert.strictEqual(L.measurePageCount(one, tpl), 1)
  assert.strictEqual(L.measurePageCount(one + 1, tpl), 2)
  assert.strictEqual(L.measurePageCount(0, tpl), 1)
  assert.strictEqual(L.measurePageCount(-5, tpl), 1)
})

test('AC-64 A3 横向：宽高互换，版心随纸张与方向变化', function () {
  var m = L.layoutMetrics({ paper: 'A3', orientation: 'landscape' })
  assert.strictEqual(m.landscape, true)
  assert.strictEqual(m.pageWidthMm, 420)
  assert.strictEqual(m.pageHeightMm, 297)
  // 版心高度 = 297 - 24 = 273mm —— 与 A4 纵向相同（A3 横过来正好是 A4 的高）
  assert.ok(Math.abs(m.contentHeightPx - 273 * (96 / 25.4)) < 1e-6)
  // 但横向的分栏/分页参数不同：页宽从 210 变成 420
  assert.notStrictEqual(m.pageWidthMm, L.layoutMetrics({ paper: 'A4' }).pageWidthMm)
})

test('AC-64 A3 纵向与 A4 纵向的版心不同（页数测算因此参数化）', function () {
  var a4 = L.layoutMetrics({ paper: 'A4', orientation: 'portrait' }).contentHeightPx
  var a3 = L.layoutMetrics({ paper: 'A3', orientation: 'portrait' }).contentHeightPx
  assert.ok(a3 > a4, 'A3 纵向的版心应高于 A4 纵向')
  // 同一段内容在 A3 上更少页
  assert.ok(L.measurePageCount(a4 * 2 + 1, { paper: 'A3' }) < L.measurePageCount(a4 * 2 + 1, { paper: 'A4' }))
})

test('AC-64 @page 规则跟着纸张与方向走（否则配了 A3 横向也被裁成 A4）', function () {
  assert.strictEqual(L.pageRuleCss({ paper: 'A4', orientation: 'portrait' }),
    '@page { size: A4 portrait; margin: 12mm 10mm; }')
  assert.strictEqual(L.pageRuleCss({ paper: 'A3', orientation: 'landscape' }),
    '@page { size: A3 landscape; margin: 12mm 10mm; }')
  // 未配置/脏值 → A4 纵向
  assert.strictEqual(L.pageRuleCss({}), '@page { size: A4 portrait; margin: 12mm 10mm; }')
  assert.strictEqual(L.pageRuleCss({ paper: 'B5', orientation: 'diagonal' }),
    '@page { size: A4 portrait; margin: 12mm 10mm; }')
})

test('AC-64 屏幕纸样尺寸随纸张与方向变化', function () {
  assert.deepStrictEqual(L.paperBoxStyle({ paper: 'A4' }), { width: '210mm', minHeight: '297mm' })
  assert.deepStrictEqual(L.paperBoxStyle({ paper: 'A3', orientation: 'landscape' }),
    { width: '420mm', minHeight: '297mm' })
  assert.deepStrictEqual(L.paperBoxStyle({ paper: 'A3' }), { width: '297mm', minHeight: '420mm' })
  assert.deepStrictEqual(L.paperBoxStyle({}), { width: '210mm', minHeight: '297mm' })
})

/* ==================== AC-65 Logo ==================== */

test('AC-65 未配置 Logo 时不出图（不给空 src）', function () {
  assert.strictEqual(L.logoUrl(null, '/dev-api'), '')
  assert.strictEqual(L.logoUrl('', '/dev-api'), '')
  assert.strictEqual(L.logoUrl('   ', '/dev-api'), '')
})

test('AC-65 相对路径要加 API 前缀（不加会拿到 SPA 的 HTML）', function () {
  assert.strictEqual(L.logoUrl('/profile/upload/2026/10/05/logo.png', '/dev-api'),
    '/dev-api/profile/upload/2026/10/05/logo.png')
  assert.strictEqual(L.logoUrl('profile/upload/logo.png', '/dev-api'),
    '/dev-api/profile/upload/logo.png')
  // 已是绝对地址或 data: 时不再拼接
  assert.strictEqual(L.logoUrl('http://cdn/logo.png', '/dev-api'), 'http://cdn/logo.png')
  assert.strictEqual(L.logoUrl('data:image/png;base64,AAA', '/dev-api'), 'data:image/png;base64,AAA')
})

/* ==================== AC-67 留痕硬门禁 ==================== */

test('AC-67 留痕成功后才调起打印', function () {
  var calls = []
  return G.printAfterLogging({
    writeLog: function () { calls.push('log'); return Promise.resolve() },
    print: function () { calls.push('print') }
  }).then(function (r) {
    assert.deepStrictEqual(calls, ['log', 'print'])
    assert.strictEqual(r.printed, true)
    assert.strictEqual(r.error, null)
  })
})

test('AC-67 留痕失败时**不调起打印**并给出失败结论', function () {
  var printed = false
  return G.printAfterLogging({
    writeLog: function () { return Promise.reject(new Error('500')) },
    print: function () { printed = true }
  }).then(function (r) {
    assert.strictEqual(printed, false, '留痕失败绝不能调起打印（打印动作不可回滚）')
    assert.strictEqual(r.printed, false)
    assert.ok(r.error, '应带回失败原因供提示使用')
  })
})

test('AC-67 留痕同步抛异常同样阻断打印', function () {
  var printed = false
  return G.printAfterLogging({
    writeLog: function () { throw new Error('boom') },
    print: function () { printed = true }
  }).then(function (r) {
    assert.strictEqual(printed, false)
    assert.strictEqual(r.printed, false)
  })
})

test('AC-67 失败提示文案明确要求"联系管理员"', function () {
  assert.ok(G.logFailureMessage().indexOf('联系管理员') >= 0, G.logFailureMessage())
  assert.strictEqual(G.PRINT_LOG_FAILED_MESSAGE, '打印留痕写入失败，请联系管理员')
})

/* ==================== 测试夹具 ==================== */

var CONTRACT_MAP = JSON.stringify({
  sections: [
    { id: 'base', rows: [{ cells: [{ label: '合同编号', field: 'contractNo', span: 3 }] }] },
    { id: 'sign', type: 'dynamic', source: 'flowNodes' },
    { id: 'attach', type: 'attachmentList' }
  ]
})

var FUND_MAP = JSON.stringify({
  sections: [
    {
      id: 'base',
      rows: [
        { cells: [{ label: '审批单位', field: '$submitterCompany', span: 3 }] },
        { cells: [{ label: '事项分类', field: 'matterCategory', span: 1 },
                  { label: '计划类别', field: 'planType', span: 1 },
                  { label: '付款归属期', field: 'paymentPeriod', span: 1 }] },
        { cells: [{ label: '资金审批内容', field: 'fundContent', span: 3 }] }
      ]
    },
    {
      id: 'sign',
      type: 'dynamic',
      source: 'flowNodes',
      title: '签批栏',
      columns: [{ label: '集团职能部门' }, { label: '集团分管领导' }, { label: '集团董事长' }]
    },
    { id: 'attach', type: 'attachmentList' }
  ]
})

var PAYMENT_MAP = JSON.stringify({
  sections: [
    { id: 'head', rows: [{ cells: [{ label: '日期', field: '$submitTime', span: 1 },
                                  { label: '计划内/计划外', field: 'planType', span: 2 }] }] },
    { id: 'payee', title: '收款信息', rows: [{ cells: [{ label: '收款单位名称', field: 'payeeName', span: 3 }] }] },
    {
      id: 'pay',
      title: '付款信息',
      rows: [
        { cells: [{ label: '金额', field: 'amount', span: 3 }] },
        { cells: [{ label: '金额大写', field: 'amount', span: 3, format: 'amountUpper' }] }
      ]
    },
    {
      id: 'sign',
      type: 'dynamic',
      source: 'flowNodes',
      columns: [{ label: '经理' }, { label: '财务' }, { label: '部门负责人' },
                { label: '财务负责人' }, { label: '制单人', field: '$submitter' }, { label: '领款人' }]
    }
  ]
})

function labelsOf(section) {
  var out = []
  ;(section.rows || []).forEach(function (r) {
    ;(r.cells || []).forEach(function (c) { out.push(c.label) })
  })
  return out
}
