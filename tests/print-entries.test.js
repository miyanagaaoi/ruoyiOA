/**
 * 打印入口覆盖与一致性（2.0 B2 §5.3 / §5.4；REQ-PRINT-016、AC-66）。
 *
 * 这是**源码级静态断言**，不是端到端点击：四处入口必须都走同一个浮层、
 * 同一个路由、同一套参数口径（只传单据ID），打印件的标题与栏目才不会因入口而异。
 *
 * 为什么静态断言在这里是足够的：打印件的内容完全由 `GET /workflow/print/data/{businessId}`
 * 决定，而该接口的入参只有 businessId（+ 可选的 printTplId）。
 * 只要四处入口都**只**传这同一个 businessId、都不传入口专属参数，
 * 四次得到的必然是同一份标题/版式/栏目。
 * 反过来，任何"某个入口偷偷多传了参数"的写法都会在这里被抓住。
 *
 * 动态部分（待办列表行点打印 → 浮层打开且不跳转）由 `tools/print-entries-check.ps1`
 * 在真实环境上验证（浏览器工具无法在 Node 里跑）。
 */
'use strict'

var assert = require('assert')
var fs = require('fs')
var path = require('path')
var h = require('./harness')
var test = h.test

var UI_SRC = path.join(__dirname, '..', 'src')
var PRINT_PAGE = path.join(UI_SRC, 'views', 'workflow', 'print', 'index.vue')
var PRINT_DIALOG = path.join(UI_SRC, 'views', 'workflow', 'print', 'PrintDialog.vue')
var PRINT_PLUGIN = path.join(UI_SRC, 'plugins', 'printPreview.js')

/**
 * 四处入口：详情页 / 待办列表行 / 已办列表行 / 我起草列表行。
 *
 * ⚠ 待办列表的**行**在 `todo/group-table.vue`（`todo-list.vue` 只是外壳，
 * 负责切换"列表模式/聚合模式"并转发给子组件）。计划里写的是 `todo-list.vue`，
 * 但打印按钮必须落在真正渲染行的地方，否则列表模式下看不到入口。
 */
var ENTRIES = [
  { name: '详情页', file: path.join(UI_SRC, 'views', 'workflow', 'flow-form', 'index.vue') },
  { name: '待办列表行', file: path.join(UI_SRC, 'views', 'workflow', 'todo', 'group-table.vue') },
  { name: '已办列表行', file: path.join(UI_SRC, 'views', 'workflow', 'done', 'index.vue') },
  { name: '我起草列表行', file: path.join(UI_SRC, 'views', 'workflow', 'my-draft', 'index.vue') }
]

function read(p) {
  return fs.readFileSync(p, 'utf8')
}

test('AC-66 四处打印入口的文件都存在', function () {
  ENTRIES.forEach(function (e) {
    assert.ok(fs.existsSync(e.file), e.name + ' 的源文件不存在：' + e.file)
  })
})

test('AC-66 四处入口都调用同一个打印浮层 $openPrintPreview', function () {
  ENTRIES.forEach(function (e) {
    var src = read(e.file)
    assert.ok(/\$openPrintPreview\s*\(/.test(src),
      e.name + ' 没有调用 $openPrintPreview（打印入口缺失）')
  })
})

test('AC-66 四处入口都只传单据ID、不传入口专属参数（否则四次打印件会不一致）', function () {
  ENTRIES.forEach(function (e) {
    var src = read(e.file)
    var calls = src.match(/\$openPrintPreview\s*\([^)]*\)/g) || []
    assert.ok(calls.length > 0, e.name + ' 找不到 $openPrintPreview 的调用')
    calls.forEach(function (c) {
      var args = c.replace(/^[^(]*\(/, '').replace(/\)$/, '').trim()
      assert.ok(args.length > 0, e.name + ' 的打印入口没有传单据ID：' + c)
      assert.strictEqual(args.indexOf(','), -1,
        e.name + ' 的打印入口多传了参数（应为"只传单据ID"）：' + c)
    })
  })
})

test('AC-66 打印浮层把所有入口归一化到同一个路由与参数', function () {
  var plugin = read(PRINT_PLUGIN)
  assert.ok(/\$openPrintPreview\s*=/.test(plugin), 'printPreview 插件没有注册 $openPrintPreview')
  var dialog = read(PRINT_DIALOG)
  assert.ok(/path:\s*'\/workflow\/print'/.test(dialog),
    '打印浮层没有打开 /workflow/print 路由')
  // 归一化的参数口径：只有 businessId（printTplId 可选），没有任何"入口来源"参数
  var queryBlock = dialog.match(/query[\s\S]{0,400}?\}/)
  assert.ok(queryBlock, '找不到打印浮层的 query 构造')
  assert.ok(/businessId/.test(queryBlock[0]), '打印浮层的 query 必须带 businessId')
  assert.ok(!/from|entry|source/i.test(queryBlock[0].replace(/businessId|printTplId|embedded/g, '')),
    '打印浮层的 query 不应带入口来源参数：' + queryBlock[0])
})

test('AC-66 打印页的标题与版式来自接口数据，而不是入口传参', function () {
  var src = read(PRINT_PAGE)
  // 标题取服务端聚合结果（含有内置版式按 key 取的标题）
  assert.ok(/this\.data\s*&&\s*this\.data\.title/.test(src),
    '打印页的标题没有优先取接口返回的 data.title')
  // 路由上只应有 businessId / printTplId / embedded 三个 query，没有"版式来源"这类参数
  var queries = src.match(/this\.\$route\.query\.\w+/g) || []
  var names = queries.map(function (q) { return q.split('.').pop() })
  var allowed = ['businessId', 'printTplId', 'embedded']
  names.forEach(function (n) {
    assert.ok(allowed.indexOf(n) >= 0,
      '打印页读了未约定的路由参数 ' + n + '（会让不同入口的打印件不一致）；允许的只有 ' + allowed.join('/'))
  })
})

test('AC-66 打印页的版式判定不依赖任何入口信息', function () {
  var src = read(PRINT_PAGE)
  assert.ok(!/fromEntry|entryName|\$route\.query\.from/.test(src),
    '打印页不应读取入口信息来决定版式')
  // 版式判定统一走抽出来的纯逻辑模块（可被前端用例覆盖）
  assert.ok(/printLayout/.test(src), '打印页应复用 printLayout 的版式判定')
  assert.ok(/useBuiltinLabelLayout/.test(src),
    '打印页应使用 useBuiltinLabelLayout 决定内置版式的排版路径')
})

test('AC-66 待办列表行的打印按钮写在行内（与已办列表行同款）', function () {
  var todoSrc = read(ENTRIES[1].file)
  var doneSrc = read(ENTRIES[2].file)
  // 已办列表行的行内按钮是 `<el-button ... @click.stop="printRow(scope.row)">`
  // ⚠ 修饰符（.stop）必须允许：整行有点击进详情的处理，不加 .stop 会连带跳走
  var btn = /@click(?:\.\w+)*\s*=\s*"printRow\(/
  assert.ok(btn.test(doneSrc), '已办列表行应有行内 printRow 按钮（对照基准）')
  assert.ok(btn.test(todoSrc), '待办列表行缺少行内 printRow 按钮')
  assert.ok(/printRow\s*\(/.test(todoSrc), '待办列表行缺少 printRow 方法')
})

test('AC-64 纸样尺寸只绑给纸面、绝不能绑给工具条（否则工具条撑成一张纸高并盖住纸面）', function () {
  var src = read(PRINT_PAGE)
  // 纸面：必须绑定纸样尺寸
  assert.ok(/class="print-paper"[^>]*:style="paperBoxStyle"/.test(src),
    '.print-paper 应绑定 paperBoxStyle（纸张/方向要真的生效）')
  // 工具条：**不得**带任何 :style 绑定
  // 回归背景（2026-10-05 实测）：paperBoxStyle 含 minHeight: 297mm（纸样高度），
  // 绑到 .print-toolbar 上会让它变成 297mm 高的白色 sticky 块（z-index:10）盖住纸面，
  // 现象是"打印预览只剩左边一条、大片空白、打印按钮被顶出视口"。
  var toolbar = src.match(/<div class="print-toolbar[^>]*>/)
  assert.ok(toolbar, '找不到 .print-toolbar 元素')
  assert.strictEqual(/:style/.test(toolbar[0]), false,
    '工具条不应带 :style（尤其不能绑 paperBoxStyle）：' + toolbar[0])
})

test('打印浮层标题不得写死纸张/方向/打印范围（会与实际打印件不一致）', function () {
  var dialog = read(PRINT_DIALOG)
  var hint = dialog.match(/<span class="pm-hint">([\s\S]*?)<\/span>/)
  assert.ok(hint, '找不到打印浮层的 pm-hint')
  var text = hint[1]
  assert.strictEqual(/A4|A3|纵向|横向|仅表单信息/.test(text), false,
    '浮层副标题写死了纸张/方向/范围：' + text.trim())
})
