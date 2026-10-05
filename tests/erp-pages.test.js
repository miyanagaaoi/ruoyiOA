/**
 * 进销存**页面层**用例（B4 §8.4/§8.5）：16 个薄页面 + 盘点独立交互 + 库存账两页。
 *
 * 覆盖两类"看代码看不出来"的东西：
 *   ① **页面落点**：菜单 SQL 里的 `component` 必须与仓库里的文件逐个对得上
 *      （少一个 → 该菜单点开是空白，而且是运行期才暴露）；
 *   ② **页面口径**：库存账/盘点单的查询与载荷形状（纯函数在
 *      `stock-balance-rules.js` / `stocktake-rules.js` 里），以及"默认只校验、修复需二次确认"这条语义。
 *
 * 本文件**不做接口实测**（t25 的边界：接口实测留给 t12，等 t20 打包重启之后）。
 *
 * 跑法：`npm run test:unit`（= `node tests/run.js`）。
 *
 * @author 二开
 */
'use strict'

var assert = require('assert')
var fs = require('fs')
var path = require('path')
var h = require('./harness')
var test = h.test

var BAL = require('../src/views/erp/stock-balance/stock-balance-rules.js')
var TAKE = require('../src/views/erp/stock-take/stocktake-rules.js')
var KINDS = require('../src/views/erp/doc/doc-kinds.js')
var RESP = require('../src/views/erp/doc/erp-response.js')
var SHAPE = require('../src/views/erp/doc/doc-select-shape.js')
var NEED = require('../src/views/erp/doc/doc-options-need.js')

var ERP_DIR = path.join(__dirname, '..', 'src', 'views', 'erp')
var API_DIR = path.join(__dirname, '..', 'src', 'api', 'erp')

function read(rel) {
  return fs.readFileSync(path.join(ERP_DIR, rel), 'utf8')
}

/* ============================================================================
 * 一、16 个单据薄页面 + 库存账两页（落点 = 菜单 component）
 * ========================================================================== */

/** 8 类单据的目录名 → 单据码（与 doc-kinds 的 listPath/formPath 一致）。 */
var DOC_DIRS = [
  { dir: 'purchase-request', code: 'purchase_request' },
  { dir: 'purchase-order', code: 'purchase_order' },
  { dir: 'sales-request', code: 'sales_request' },
  { dir: 'sales-order', code: 'sales_order' },
  { dir: 'stock-in', code: 'stock_in' },
  { dir: 'stock-out', code: 'stock_out' },
  { dir: 'stock-take', code: 'stock_take' },
  { dir: 'stock-transfer', code: 'stock_transfer' }
]

test('页面落点：8 类单据各有 index.vue 与 form.vue（菜单 component 指向它们）', function () {
  DOC_DIRS.forEach(function (d) {
    assert.strictEqual(fs.existsSync(path.join(ERP_DIR, d.dir, 'index.vue')), true, d.dir + '/index.vue 缺失')
    assert.strictEqual(fs.existsSync(path.join(ERP_DIR, d.dir, 'form.vue')), true, d.dir + '/form.vue 缺失')
  })
})

test('薄页约束：列表页只渲染壳 + 按 code 取 kind（不复制壳逻辑）', function () {
  DOC_DIRS.forEach(function (d) {
    var text = read(path.join(d.dir, 'index.vue'))
    assert.ok(text.indexOf('<doc-list-shell') >= 0, d.dir + '/index.vue 必须渲染 doc-list-shell')
    assert.ok(text.indexOf('DocListShell') >= 0, d.dir + '/index.vue 必须引入壳组件')
    assert.ok(
      text.indexOf("getKind('" + d.code + "')") >= 0,
      d.dir + '/index.vue 必须按 ' + d.code + ' 取 kind'
    )
    // 薄页不得内联列定义/行项表/状态机分支（那说明它在复制壳）
    assert.strictEqual(/itemColumns\s*:/.test(text), false, d.dir + '/index.vue 不应内联 itemColumns')
    assert.strictEqual(/visibleActions\(/.test(text), false, d.dir + '/index.vue 不应自己算动作显隐')
  })
})

test('薄页约束：表单页渲染 doc-form-shell，且从 query 读 id/mode', function () {
  DOC_DIRS.forEach(function (d) {
    var text = read(path.join(d.dir, 'form.vue'))
    assert.ok(text.indexOf('<doc-form-shell') >= 0, d.dir + '/form.vue 必须渲染 doc-form-shell')
    assert.ok(text.indexOf('DocFormShell') >= 0, d.dir + '/form.vue 必须引入壳组件')
    assert.ok(text.indexOf('$route.query.id') >= 0, d.dir + '/form.vue 必须读 query.id')
    assert.ok(text.indexOf('$route.query') >= 0, d.dir + '/form.vue 必须读 query.mode')
  })
})

test('页面落点：库存明细 / 库存流水两页存在且分别渲染各自的页面组件', function () {
  assert.strictEqual(fs.existsSync(path.join(ERP_DIR, 'stock-balance', 'index.vue')), true)
  assert.strictEqual(fs.existsSync(path.join(ERP_DIR, 'stock-ledger', 'index.vue')), true)
  assert.strictEqual(fs.existsSync(path.join(ERP_DIR, 'stock-ledger', 'components', 'LedgerTable.vue')), true)
  var balance = read(path.join('stock-balance', 'index.vue'))
  assert.ok(balance.indexOf('LedgerTable') >= 0, '库存明细页的流水下钻必须复用 LedgerTable')
  assert.ok(balance.indexOf('recalcStock') >= 0, '库存明细页必须有库存重算入口')
  assert.ok(balance.indexOf('repairWarningText') >= 0, '修复必须展示风险提示')
})

test('页面落点：api/erp 下有 stock.js（库存账接口），单据接口仍由 doc.js 承担', function () {
  assert.strictEqual(fs.existsSync(path.join(API_DIR, 'stock.js')), true)
  var stock = fs.readFileSync(path.join(API_DIR, 'stock.js'), 'utf8')
  assert.ok(stock.indexOf('rules.BALANCE_LIST_URL') >= 0, 'stock.js 的 URL 必须取自纯函数模块')
  // 注释里会列出端点清单（那是给维护者看的），所以剥掉注释再断言"代码里没有硬编码 URL"
  var codeOnly = stock
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
  assert.strictEqual(codeOnly.indexOf('/stk/stock/recalc'), -1, 'stock.js 不应自己拼 recalc URL')
  assert.strictEqual(codeOnly.indexOf('/stk/ledger/list'), -1, 'stock.js 不应自己拼流水 URL')
  assert.strictEqual(fs.existsSync(path.join(API_DIR, 'doc.js')), true)
})

test('盘点单页：生成行项 / 保存实盘 / 审核三段都在页面上，且复用壳与动作弹窗', function () {
  var text = read(path.join('stock-take', 'form.vue'))
  assert.ok(text.indexOf('generateStocktakeItems') >= 0, '缺少「生成行项」调用')
  assert.ok(text.indexOf('saveStocktakeCount') >= 0, '缺少「保存实盘数量」调用')
  assert.ok(text.indexOf('DocActionDialog') >= 0, '审核动作必须复用动作弹窗组件')
  assert.ok(text.indexOf('applyServerDoc') >= 0, '生成/录入后必须把服务端返回回填到壳')
  assert.ok(text.indexOf('generatedDocText') >= 0, '必须展示生成的盘盈/盘亏单号')
  assert.ok(text.indexOf('header-extra') >= 0, '盘点专有交互应放在壳的 header-extra 插槽')
})

test('api 导入形状：api/erp/* 全是命名导出，页面不得 default import（会静默不请求）', function () {
  // 真缺陷（t12 浏览器走通时抓到）：`import api from '@/api/erp/doc'` 在"只有命名导出"的模块上
  // 得到 undefined ⇒ `api.listDocs(...)` 同步抛错 ⇒ 请求根本没发出去、页面只是空表（连错误提示都没有）。
  // 静态门禁：api/erp 下没有 export default 的模块，任何文件都不得默认导入它。
  var modules = fs.readdirSync(API_DIR).filter(function (f) { return /\.js$/.test(f) })
  var noDefault = []
  modules.forEach(function (f) {
    var text = fs.readFileSync(path.join(API_DIR, f), 'utf8')
    if (!/export\s+default\b/.test(text)) {
      noDefault.push(f.replace(/\.js$/, ''))
    }
  })
  assert.ok(noDefault.length > 0, '预期 api/erp 下存在命名导出模块（否则本门禁失效）')

  var offenders = []
  function walk(dir) {
    fs.readdirSync(dir, { withFileTypes: true }).forEach(function (e) {
      var p = path.join(dir, e.name)
      if (e.isDirectory()) {
        walk(p)
        return
      }
      if (!/\.(vue|js)$/.test(e.name)) {
        return
      }
      var text = fs.readFileSync(p, 'utf8')
      noDefault.forEach(function (mod) {
        var re = new RegExp('import\\s+[A-Za-z_$][\\w$]*\\s+from\\s+[\'"]@/api/erp/' + mod + '[\'"]')
        if (re.test(text)) {
          offenders.push(path.relative(path.join(__dirname, '..'), p).replace(/\\/g, '/') + ' -> ' + mod)
        }
      })
    })
  }
  walk(path.join(__dirname, '..', 'src'))
  assert.deepStrictEqual(offenders, [], '这些文件默认导入了"只有命名导出"的 api 模块（应用 namespace/具名导入）')
})

/* ============================================================================
 * 一之二、取数层级（P-② 根因：列表读 rows、/options 读 data）
 * ========================================================================== */

test('erp-response：rowsOf 只认 rows、dataOf 只认 data，缺失/非数组一律 []', function () {
  // 真实响应体形状（captain 实测）：/{product,warehouse,uom}/list → {total,rows,code,msg}
  assert.deepStrictEqual(RESP.rowsOf({ code: 200, msg: '操作成功', total: 2, rows: [{ id: 1 }, { id: 2 }] }), [{ id: 1 }, { id: 2 }])
  // /ctms/partner/{supplier,customer}/options → {msg,code,data:[…]}
  assert.deepStrictEqual(RESP.dataOf({ code: 200, msg: '操作成功', data: [{ id: 9 }] }), [{ id: 9 }])
  // ★ 这就是 P-② 的形状：列表体里没有 data 键 ⇒ 读 data 恒得空数组
  assert.deepStrictEqual(RESP.dataOf({ code: 200, total: 2, rows: [{ id: 1 }, { id: 2 }] }), [], '列表体没有 data ⇒ 必须空')
  assert.deepStrictEqual(RESP.rowsOf({ code: 200, data: [{ id: 1 }] }), [], 'options 体没有 rows ⇒ 必须空')
  // 空值与异常路径
  assert.deepStrictEqual(RESP.rowsOf(null), [])
  assert.deepStrictEqual(RESP.rowsOf(undefined), [])
  assert.deepStrictEqual(RESP.rowsOf({ rows: null }), [])
  assert.deepStrictEqual(RESP.rowsOf({ rows: {} }), [], '非数组不算列表')
  assert.deepStrictEqual(RESP.dataOf({ data: 'x' }), [])
})

test('doc-options：逐个装载点声明取数层级（列表 5 处 rowsOf、/options 2 处 dataOf）', function () {
  var text = read(path.join('doc', 'doc-options.js'))
  var listKeys = ['products', 'warehouses', 'uoms', 'purchaseContracts', 'saleContracts']
  var optionsKeys = ['suppliers', 'customers']
  listKeys.concat(optionsKeys).forEach(function (key) {
    assert.ok(text.indexOf("collect('" + key + "'") >= 0, '缺少装载点 ' + key)
  })
  // 每一行的 collect(...) 后面必须跟对取数函数（同一行内断言，避免"改了一处漏一处"）
  function lineOf(key) {
    var hit = text.split('\n').filter(function (l) { return l.indexOf("collect('" + key + "'") >= 0 })
    assert.strictEqual(hit.length, 1, key + ' 应恰好有一个装载点')
    return hit[0]
  }
  listKeys.forEach(function (key) {
    assert.ok(lineOf(key).indexOf('rowsOf)') >= 0, key + ' 是列表接口（{rows,total}）⇒ 必须用 rowsOf')
    assert.strictEqual(lineOf(key).indexOf('dataOf'), -1, key + ' 不得用 dataOf')
  })
  optionsKeys.forEach(function (key) {
    assert.ok(lineOf(key).indexOf('dataOf)') >= 0, key + ' 是 /options 接口（{data:[…]}）⇒ 必须用 dataOf')
    assert.strictEqual(lineOf(key).indexOf('rowsOf'), -1, key + ' 不得用 rowsOf')
  })
})

test('判别：erp 页面/api 里"列表类接口"的结果不得读 res.data（P-② 门禁）', function () {
  // 判据是**调用点与取数的邻近关系**：list* 类接口（列表体）拿到 res 后 300 字符内读 res.data 即违规。
  // 扫描范围 = 本任务 inScope 的两处（`views/erp/doc/**` 与 `api/erp/**`）。
  // ⚠ 已知 inScope 之外的同类残留（未修，交 follow-up 派单）：
  //   `views/erp/stock-balance/index.vue` 与 `views/erp/stock-ledger/index.vue` 的**仓库筛选**读 res.data
  //   （列表接口 ⇒ 恒空）、`stock-ledger` 业务类型筛选循环原始字典行（⇒ 空条目）。
  var listApis = /\blist(Enabled(Products|Warehouses|Uoms)|Contract|Product|Warehouse|Uom|Supplier|Customer)\s*\(/
  var offenders = []
  function walk(dir) {
    fs.readdirSync(dir, { withFileTypes: true }).forEach(function (e) {
      var p = path.join(dir, e.name)
      if (e.isDirectory()) {
        walk(p)
        return
      }
      if (!/\.(vue|js)$/.test(e.name)) {
        return
      }
      var raw = fs.readFileSync(p, 'utf8')
      // 注释里说明"读 res.data 会恒空"是刻意写的，不能算违规 ⇒ 先剥注释再扫
      var text = raw
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
      var idx = text.search(listApis)
      while (idx >= 0) {
        var window = text.slice(idx, idx + 300)
        if (/res\.data|r?es && res\.data/.test(window)) {
          offenders.push(path.relative(path.join(__dirname, '..'), p).replace(/\\/g, '/'))
          break
        }
        var next = text.slice(idx + 1).search(listApis)
        idx = next < 0 ? -1 : idx + 1 + next
      }
    })
  }
  // 扫描范围 = **整个 `views/erp/**`**（t51 扩围：原来只扫 doc/**，于是 stock-balance/stock-ledger 的
  // 同源残留逃逸了整整一轮）+ `api/erp/**`。
  walk(ERP_DIR)
  walk(API_DIR)
  assert.deepStrictEqual(offenders, [], '这些文件对列表类接口读了 res.data（必须用 rowsOf：列表体是 {rows,total}）')
})

test('doc-select-shape：字典行/状态/主数据都必须转成 {value,label}（否则选项是空条目）', function () {
  // 字典行的字段名是 dictLabel/dictValue（真实接口 /system/dict/data/type/{t} 的 data）
  var dictRows = [
    { dictValue: '采购入库', dictLabel: '采购入库' },
    { dictValue: '退货入库', dictLabel: '退货入库' }
  ]
  assert.deepStrictEqual(SHAPE.dictRowsToOptions(dictRows), [
    { value: '采购入库', label: '采购入库' },
    { value: '退货入库', label: '退货入库' }
  ])
  // ★ 反例：若把原始字典行直接当选项用，label/value 会是 undefined ⇒ 渲染成空条目
  assert.strictEqual(dictRows[0].label, undefined, '字典行没有 label 字段（模板写 o.label 就是空条目）')
  assert.strictEqual(dictRows[0].value, undefined, '字典行没有 value 字段（模板写 o.value 就是空条目）')

  assert.deepStrictEqual(SHAPE.statusOptionsOf([{ value: 'draft', label: '草稿' }]), [{ value: 'draft', label: '草稿' }])
  assert.deepStrictEqual(
    SHAPE.masterOptionsOf([{ id: 'w1', name: '一号仓' }, { id: 's1', shortName: '甲乙', name: '甲乙供应商' }]),
    [{ value: 'w1', label: '一号仓' }, { value: 's1', label: '甲乙' }]
  )
  // 空值与异常路径
  assert.deepStrictEqual(SHAPE.dictRowsToOptions(null), [])
  assert.deepStrictEqual(SHAPE.dictRowsToOptions(undefined), [])
  assert.deepStrictEqual(SHAPE.dictRowsToOptions([null, { dictValue: 'x' }]), [{ value: 'x', label: undefined }])
  assert.deepStrictEqual(SHAPE.statusOptionsOf(), [])
})

test('状态筛选：doc-kinds 声明 options=docStatuses，且 doc-options 提供该键（前端自带真源）', function () {
  KINDS.KINDS.forEach(function (kind) {
    var status = kind.filters.filter(function (f) { return f.prop === 'status' })[0]
    assert.ok(status, kind.code + ' 缺少状态筛选')
    assert.strictEqual(status.options, 'docStatuses', kind.code + ' 的状态筛选应声明 docStatuses')
    assert.strictEqual(status.dict, undefined, kind.code + ' 的状态筛选不该依赖后端字典')
  })
  var text = fs.readFileSync(path.join(ERP_DIR, 'doc', 'doc-options.js'), 'utf8')
  assert.ok(text.indexOf('docStatuses: statusOptionsOf(rules.STATUSSES)') > 0, 'doc-options 必须提供 docStatuses（否则状态下拉恒空）')
})

test('判别：erp 的 el-select 不得直接循环原始字典行（必须经 dictSelectOptions/filterOptions）', function () {
  // 真实缺陷（2026-10-06，qa-hardening 的 UI 写路径用例在「入库类型」处红）：
  // `<el-option v-for="o in dictOptions('stock_in_types')" :label="o.label">` —— 字典行只有
  // dictLabel/dictValue ⇒ 选项渲染成**空条目**（DOM 里有 item、文字为空）。
  var offenders = []
  function walk(dir) {
    fs.readdirSync(dir, { withFileTypes: true }).forEach(function (e) {
      var p = path.join(dir, e.name)
      if (e.isDirectory()) {
        walk(p)
        return
      }
      if (!/\.vue$/.test(e.name)) {
        return
      }
      var text = fs.readFileSync(p, 'utf8')
      var re = /<el-option[^>]*v-for="[^"]*in\s+(dictOptions|dictRows)\(/g
      if (re.test(text)) {
        offenders.push(path.relative(path.join(__dirname, '..'), p).replace(/\\/g, '/'))
      }
    })
  }
  // 扫描范围 = **整个 `views/erp/**`**（t51 扩围：原来只扫 doc/**，stock-ledger 的业务类型筛选因此逃逸）
  walk(ERP_DIR)
  assert.deepStrictEqual(offenders, [], '这些文件的 el-option 循环了原始字典行（label/value 为 undefined ⇒ 空条目）')
  // 正面断言：三处必须用转换后的选项集合
  var form = fs.readFileSync(path.join(ERP_DIR, 'doc', 'DocFormShell.vue'), 'utf8')
  assert.ok(form.indexOf('dictSelectOptions(f.dict)') > 0, '表单壳的字典下拉必须用 dictSelectOptions')
  var list = fs.readFileSync(path.join(ERP_DIR, 'doc', 'DocListShell.vue'), 'utf8')
  assert.ok(list.indexOf('filterOptions(f)') > 0, '列表壳的筛选下拉必须用 filterOptions')
  var ledger = fs.readFileSync(path.join(ERP_DIR, 'stock-ledger', 'index.vue'), 'utf8')
  assert.ok(ledger.indexOf("dictSelectOptions('stock_biz_types')") > 0, '流水页的业务类型筛选必须用 dictSelectOptions')
})

test('t51：库存两页的仓库筛选必须用 rowsOf（列表接口），且两页都要有该断言', function () {
  ;['stock-balance', 'stock-ledger'].forEach(function (page) {
    var text = fs.readFileSync(path.join(ERP_DIR, page, 'index.vue'), 'utf8')
    assert.ok(text.indexOf('rowsOf(res)') > 0, page + ' 的仓库筛选必须用 rowsOf')
    assert.ok(
      text.indexOf("from '../doc/erp-response'") > 0,
      page + ' 必须复用已就位的 erp-response.js（不要另写一份取数口径）'
    )
  })
})

test('P-⑤ 门禁：声明了 products 的 kind 必须产生 uoms 装载（8 类单据一个不落）', function () {
  // 真实缺陷（2026-10-06，P-⑤）：`neededOptions()` 把"需要物料 ⇒ 需要单位"的联动写在
  // filters 扫描之后、itemColumns 扫描之前，而 8 类单据的 products 来自**行项列** ⇒ `need.uoms` 恒 false
  // ⇒ 单位列表**从未请求** ⇒ 行项「单位」空白、`uomDecimals=null` ⇒ 数量精度静默退回默认 3 位。
  var checked = 0
  KINDS.KINDS.forEach(function (kind) {
    var need = NEED.neededOptions(kind)
    if (need.products) {
      checked++
      assert.strictEqual(
        need.uoms,
        true,
        'src/views/erp/doc/doc-options-need.js：' + kind.code
          + ' 需要物料 ⇒ 必须同时装载单位（否则行项单位空白、uomDecimals 为 null）'
      )
    }
    // 反向：不涉及物料时不该白拉单位表（避免为了"保险"一律加载）
    if (!need.products) {
      assert.strictEqual(need.uoms, false, kind.code + ' 不需要物料时不该装载单位')
    }
  })
  assert.ok(checked >= 8, '8 类单据都应声明物料（实际 ' + checked + ' 类）')
})

test('P-⑤ 门禁（结构）：联动推导必须写在三个 scan 之后', function () {
  // 行为用例已能抓住回归；这条再钉住"位置"这一读代码时最容易看漏的点：
  // `if (need.products) need.uoms = true` 必须出现在最后一个扫描（itemColumns 段）之后。
  var text = fs.readFileSync(path.join(ERP_DIR, 'doc', 'doc-options-need.js'), 'utf8')
  var code = text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
  var coupling = code.indexOf('if (need.products) need.uoms = true')
  var lastScan = Math.max(
    code.indexOf('kind.filters'),
    code.indexOf('kind.headerFields'),
    code.indexOf('kind.itemColumns')
  )
  assert.ok(coupling > 0, 'src/views/erp/doc/doc-options-need.js：找不到联动推导语句（是否被改名/删除？）')
  assert.ok(lastScan > 0, 'src/views/erp/doc/doc-options-need.js：找不到三个 scan')
  assert.ok(
    coupling > lastScan,
    'src/views/erp/doc/doc-options-need.js：联动推导必须在三个 scan 之后'
      + '（P-⑤：写在中间会让 8 类单据的 uoms 恒 false）'
  )
})

/* ============================================================================
 * 二、库存明细 / 流水的查询与重算语义
 * ========================================================================== */

test('balanceQuery：筛选归一（类型数组、布尔开关只在 true 时下发、空串不下发）', function () {
  var q = BAL.balanceQuery(
    { keyword: '  螺丝  ', warehouseId: 'w1', productTypeIds: ['t1', 't2'], beginQty: 1, endQty: '', belowSafetyOnly: true, hideZero: false },
    { pageNum: 2, pageSize: 20 }
  )
  assert.strictEqual(q.keyword, '螺丝')
  assert.strictEqual(q.warehouseId, 'w1')
  assert.deepStrictEqual(q.productTypeIds, ['t1', 't2'])
  assert.strictEqual(q.beginQty, 1)
  assert.strictEqual(q.endQty, undefined, '空串不下发')
  assert.strictEqual(q.belowSafetyOnly, true)
  assert.strictEqual(q.hideZero, undefined, 'false 不下发（避免被当成显式条件）')
  assert.strictEqual(q.pageNum, 2)
  assert.strictEqual(q.pageSize, 20)
})

test('ledgerQuery：下钻按 (物料, 仓库) + 时间区间（时间用日期时间串）', function () {
  var q = BAL.ledgerQuery(
    { productId: 'p1', warehouseId: 'w1' },
    ['2026-10-01 00:00:00', '2026-10-05 23:59:59']
  )
  assert.deepStrictEqual(q, {
    pageNum: 1,
    pageSize: 50,
    productId: 'p1',
    warehouseId: 'w1',
    beginTime: '2026-10-01 00:00:00',
    endTime: '2026-10-05 23:59:59'
  })
  var noRange = BAL.ledgerQuery({ productId: 'p1' }, [])
  assert.strictEqual(noRange.beginTime, undefined)
  assert.strictEqual(noRange.endTime, undefined)
})

test('recalcRequest：默认只校验（repair=false），修复才是 true', function () {
  assert.deepStrictEqual(BAL.recalcRequest(false), {
    method: 'post',
    url: '/stk/stock/recalc',
    params: { repair: false },
    data: null
  })
  assert.strictEqual(BAL.recalcRequest(undefined).params.repair, false, '缺参必须落到"只校验"')
  assert.strictEqual(BAL.recalcRequest(true).params.repair, true)
})

test('修复风险提示：明确"不可回滚"，且修复前要留档', function () {
  var text = BAL.repairWarningText()
  assert.ok(text.indexOf('不可回滚') >= 0)
  assert.ok(text.indexOf('导出') >= 0)
})

test('recalcSummary：校验/修复两种模式的文案（含"未做任何改动"）', function () {
  assert.strictEqual(BAL.recalcSummary({ checkedRows: 12, consistent: true, inconsistentCount: 0 }), '检查 12 行；全部一致')
  assert.ok(BAL.recalcSummary({ checkedRows: 12, consistent: false, inconsistentCount: 2 }).indexOf('不一致 2 条') >= 0)
  assert.ok(
    BAL.recalcSummary({ checkedRows: 12, inconsistentCount: 2, repair: false }).indexOf('未做任何改动') >= 0,
    '校验模式必须明说没改动'
  )
  assert.ok(
    BAL.recalcSummary({ checkedRows: 12, inconsistentCount: 2, repair: true, repairedCount: 2 }).indexOf('已修复 2 条') >= 0
  )
})

test('displayNameOf：优先用服务端 displayName，缺失时兜底「类型-名称」', function () {
  assert.strictEqual(BAL.displayNameOf({ displayName: '五金-螺丝' }), '五金-螺丝')
  assert.strictEqual(BAL.displayNameOf({ productTypeName: '五金', productName: '螺丝' }), '五金-螺丝')
  assert.strictEqual(BAL.displayNameOf({ productName: '螺丝' }), '螺丝')
  assert.strictEqual(BAL.displayNameOf({ productCode: 'P001' }), 'P001')
})

test('isBelowSafety：优先用服务端派生列，缺失时用 qty < safetyStock 兜底', function () {
  assert.strictEqual(BAL.isBelowSafety({ belowSafetyStock: true, qty: 10, safetyStock: 5 }), true)
  assert.strictEqual(BAL.isBelowSafety({ belowSafetyStock: false, qty: 1, safetyStock: 5 }), false)
  assert.strictEqual(BAL.isBelowSafety({ qty: 2, safetyStock: 5 }), true)
  assert.strictEqual(BAL.isBelowSafety({ qty: 6, safetyStock: 5 }), false)
  assert.strictEqual(BAL.isBelowSafety({ qty: 0, safetyStock: 0 }), false, '安全库存为 0 不算"低于"')
})

test('mismatchText：不一致明细给出结存 / 流水累计 / 差值', function () {
  var text = BAL.mismatchText({ stockQty: 10, ledgerQty: 8, diff: 2 })
  assert.ok(text.indexOf('结存 10') >= 0)
  assert.ok(text.indexOf('流水累计 8') >= 0)
  assert.ok(text.indexOf('差值 2') >= 0)
})

/* ============================================================================
 * 三、盘点独立交互（生成条件 / 实盘载荷 / 结果展示）
 * ========================================================================== */

test('generateRequest / countRequest：URL 与落地控制器逐字一致', function () {
  var g = TAKE.generateRequest('doc1', { takeType: 'full' })
  assert.deepStrictEqual(g, { method: 'post', url: '/stk/take/doc1/generate', data: { takeType: 'full' } })
  var c = TAKE.countRequest('doc1', [{ id: 'i1', actualQty: 7, diffReason: '破损' }])
  assert.strictEqual(c.method, 'put')
  assert.strictEqual(c.url, '/stk/take/doc1/count')
  assert.deepStrictEqual(c.data, { id: 'doc1', items: [{ id: 'i1', actualQty: 7, diffReason: '破损' }] })
})

test('buildCriteria：全盘只传 takeType；抽盘按物料（items）或按类型（productTypeIds）', function () {
  assert.deepStrictEqual(TAKE.buildCriteria({ takeType: 'full' }), { takeType: 'full' })
  assert.deepStrictEqual(TAKE.buildCriteria({ takeType: 'partial', productIds: ['p1', 'p2'] }), {
    takeType: 'partial',
    items: [{ productId: 'p1' }, { productId: 'p2' }]
  })
  assert.deepStrictEqual(TAKE.buildCriteria({ takeType: 'partial', productTypeIds: ['t1'] }), {
    takeType: 'partial',
    productTypeIds: ['t1']
  })
  assert.deepStrictEqual(TAKE.buildCriteria({ takeType: 'FULL' }), { takeType: 'full' }, '大小写归一')
})

test('validateGenerate：抽盘至少要选一个物料或一个类型（否则服务端只能生成 0 行）', function () {
  assert.strictEqual(TAKE.validateGenerate({ takeType: 'full' }).ok, true)
  var bad = TAKE.validateGenerate({ takeType: 'partial' })
  assert.strictEqual(bad.ok, false)
  assert.ok(bad.message.indexOf('抽盘') >= 0)
  assert.strictEqual(TAKE.validateGenerate({ takeType: 'partial', productIds: ['p1'] }).ok, true)
  assert.strictEqual(TAKE.validateGenerate({ takeType: 'partial', productTypeIds: ['t1'] }).ok, true)
})

test('buildCountItems：只带服务端要的键（id 或 productId + 实盘 + 原因），实盘缺省为 0', function () {
  var items = TAKE.buildCountItems([
    { id: 'i1', productId: 'p1', bookQty: 10, actualQty: 7, diffQty: -3, diffReason: '破损', qty: 0 },
    { productId: 'p2' }
  ])
  assert.deepStrictEqual(items[0], { actualQty: 7, diffReason: '破损', id: 'i1', productId: 'p1' })
  assert.deepStrictEqual(items[1], { actualQty: 0, diffReason: null, productId: 'p2' })
})

test('validateCount：无行项与负实盘被拒并指明行号', function () {
  assert.strictEqual(TAKE.validateCount([]).ok, false)
  var bad = TAKE.validateCount([{ actualQty: 1 }, { actualQty: -1 }])
  assert.strictEqual(bad.ok, false)
  assert.strictEqual(bad.message, '第 2 行实盘数量不能为负数')
  assert.strictEqual(TAKE.validateCount([{ actualQty: 0 }]).ok, true)
})

test('generatedDocText：盘盈/盘亏单号展示（无差异时明说未生成）', function () {
  assert.strictEqual(TAKE.generatedDocText({ generatedInNo: 'RK001' }), '已生成盘盈入库单 RK001')
  assert.strictEqual(
    TAKE.generatedDocText({ generatedInNo: 'RK001', generatedOutNo: 'CK002' }),
    '已生成盘盈入库单 RK001、盘亏出库单 CK002'
  )
  assert.strictEqual(TAKE.generatedDocText({}), '本次无差异，未生成调整单')
})

test('takeTypeLabel / normalizeTakeType：范围文案与归一（未知值当全盘）', function () {
  assert.strictEqual(TAKE.takeTypeLabel('full'), '全盘')
  assert.strictEqual(TAKE.takeTypeLabel('partial'), '抽盘')
  assert.strictEqual(TAKE.takeTypeLabel(''), '全盘')
  assert.strictEqual(TAKE.normalizeTakeType('PARTIAL'), 'partial')
})

/* ============================================================================
 * 四、与 doc-kinds 的一致性（页面口径不许漂移）
 * ========================================================================== */

test('盘点/调拨的删除形状与已落地控制器一致（DELETE /{ids}）', function () {
  assert.strictEqual(KINDS.getKind('stock_take').deleteMode, 'ids')
  assert.strictEqual(KINDS.getKind('stock_transfer').deleteMode, 'ids')
})

test('盘点单的 kind 标记 specialForm=stocktake，且列表页存在（菜单可点开）', function () {
  assert.strictEqual(KINDS.getKind('stock_take').specialForm, 'stocktake')
  assert.strictEqual(fs.existsSync(path.join(ERP_DIR, 'stock-take', 'index.vue')), true)
})

test('库存账权限点：与 Controller 注解逐字一致', function () {
  assert.strictEqual(BAL.PERMS.balanceList, 'stk:stock:list')
  assert.strictEqual(BAL.PERMS.balanceExport, 'stk:stock:export')
  assert.strictEqual(BAL.PERMS.balanceRecalc, 'stk:stock:recalc')
  assert.strictEqual(BAL.PERMS.ledgerList, 'stk:ledger:list')
  assert.strictEqual(BAL.PERMS.ledgerExport, 'stk:ledger:export')
})
