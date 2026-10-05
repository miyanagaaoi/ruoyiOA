/**
 * 进销存前端（B4 §8.3/§8.4）的口径用例。
 *
 * 覆盖三件事（都是"前后端口径漂移就会出事故、但看代码看不出来"的地方）：
 *   ① **状态机镜像**：前端"操作列显隐"必须与后端 `ErpDocStateMachine` 逐格一致
 *      （5 状态 × 6 动作全矩阵；库存类不提供「置为已完成」）；
 *   ② **权限点 / URL / 下推链**：PRD §7.5 与 §9.4 的冻结值逐条钉死，
 *      菜单 SQL（任务 8.1）与控制器必须用同一批；
 *   ③ **Element Plus → Element UI 的静默改写清单**：日期格式串小写、消息 API 用 `this.$modal`、
 *      不写裸色值、`var(--oa-*)` 引用的令牌必须有声明（这四条漏了都不报错）。
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

var R = require('../src/views/erp/doc/doc-rules.js')
var KINDS = require('../src/views/erp/doc/doc-kinds.js')
var MASTERS = require('../src/views/erp/master/master-page.js')
var CONST = require('../src/views/erp/erp-const.js')

var ERP_DIR = path.join(__dirname, '..', 'src', 'views', 'erp')
var TOKENS_FILE = path.join(__dirname, '..', 'src', 'assets', 'styles', 'oa-tokens.scss')
// 后端源码根（**只读**用于"pending 与后端落地状态一致"的判别用例；前端测试读后端源码属刻意为之）
var BACKEND_CTMS = path.join(__dirname, '..', '..', 'ruoyi-vue-oa-master', 'ruoyi-ctms', 'src', 'main', 'java', 'com', 'ruoyi', 'ctms')

function walk(dir, out) {
  var result = out || []
  fs.readdirSync(dir, { withFileTypes: true }).forEach(function (e) {
    var p = path.join(dir, e.name)
    if (e.isDirectory()) {
      walk(p, result)
    } else {
      result.push(p)
    }
  })
  return result
}

function erpFiles(ext) {
  return walk(ERP_DIR).filter(function (f) {
    return !ext || f.slice(-ext.length) === ext
  })
}

/**
 * 只扫代码文件（.js/.vue）。
 *
 * 为什么消息 API 那条门禁不扫 `.md`：目录里的 README 需要能写"不要用 Element Plus 的 X"
 * 这类说明文字，把文档也纳入会逼着文档不能提这些名字（B3 的日期门禁就踩过同源问题，
 * 所以那条是"刻意不写出该字面量"，而这条改用"只扫代码"更直白）。
 */
function erpCodeFiles() {
  return erpFiles().filter(function (f) {
    const lower = f.toLowerCase()
    return lower.endsWith('.js') || lower.endsWith('.vue')
  })
}

/* ============================================================================
 * 一、状态机镜像（与后端 ErpDocStateMachine 逐格对照）
 * ========================================================================== */

/** 冻结矩阵：动作 → 允许的来源状态（与后端 ALLOWED 表逐条一致）。 */
var EXPECTED_ALLOWED = {
  submit: ['draft'],
  approve: ['submitted'],
  reject: ['submitted'],
  void: ['draft', 'submitted'],
  unapprove: ['approved', 'completed'],
  complete: ['approved']
}

test('状态集合与后端一致（5 个：draft/submitted/approved/completed/voided）', function () {
  assert.deepStrictEqual(R.STATUS_VALUES, ['draft', 'submitted', 'approved', 'completed', 'voided'])
  assert.deepStrictEqual(
    R.STATUSES.map(function (s) { return s.label }),
    ['草稿', '待审核', '已审核', '已完成', '已作废']
  )
})

test('动作集合与后端一致（6 个：submit/approve/reject/void/unapprove/complete）', function () {
  assert.deepStrictEqual(
    R.ACTIONS.map(function (a) { return a.code }),
    ['submit', 'approve', 'reject', 'void', 'unapprove', 'complete']
  )
})

test('逐动作白名单矩阵：5 状态 × 6 动作 全格与后端一致', function () {
  R.ACTIONS.forEach(function (action) {
    assert.deepStrictEqual(
      R.allowedFrom(action.code),
      EXPECTED_ALLOWED[action.code],
      action.code + ' 的白名单与后端不一致'
    )
  })
})

test('canRun：合法流转通过、非法流转被拒（报错含当前状态与动作名）', function () {
  var kind = KINDS.getKind('purchase_order')
  assert.strictEqual(R.canRun('submit', 'draft', kind).ok, true)
  assert.strictEqual(R.canRun('approve', 'submitted', kind).ok, true)
  var bad = R.canRun('approve', 'draft', kind)
  assert.strictEqual(bad.ok, false)
  assert.strictEqual(bad.message, '当前状态（草稿）不可执行「审核」')
  assert.strictEqual(R.canRun('void', 'approved', kind).ok, false)
  assert.strictEqual(R.canRun('unapprove', 'approved', kind).ok, true)
  assert.strictEqual(R.canRun('unapprove', 'completed', kind).ok, true)
})

test('库存类单据不提供「置为已完成」（先于白名单判定，文案与后端同款）', function () {
  var stockIn = KINDS.getKind('stock_in')
  var judged = R.canRun('complete', 'approved', stockIn)
  assert.strictEqual(judged.ok, false)
  assert.strictEqual(judged.message, '入库单为库存类单据，不支持「置为已完成」')
  assert.strictEqual(R.actionExistsForKind('complete', stockIn), false)
  assert.strictEqual(R.actionExistsForKind('complete', KINDS.getKind('purchase_order')), true)
})

test('原因必填的三个动作与后端文案逐字一致（驳回/作废/反审核）', function () {
  assert.strictEqual(R.isReasonRequired('reject'), true)
  assert.strictEqual(R.isReasonRequired('void'), true)
  assert.strictEqual(R.isReasonRequired('unapprove'), true)
  assert.strictEqual(R.isReasonRequired('submit'), false)
  assert.strictEqual(R.isReasonRequired('approve'), false)
  assert.strictEqual(R.isReasonRequired('complete'), false)
  assert.strictEqual(R.reasonRequiredMessage('reject'), '驳回原因必填')
  assert.strictEqual(R.reasonRequiredMessage('void'), '作废原因必填')
  assert.strictEqual(R.reasonRequiredMessage('unapprove'), '反审核原因必填')
})

test('提交必须有行项（无行项时「提交」被拒且文案与服务端一致）', function () {
  var kind = KINDS.getKind('purchase_request')
  var judged = R.canRun('submit', 'draft', kind, false)
  assert.strictEqual(judged.ok, false)
  assert.strictEqual(judged.message, '单据没有行项，不能提交')
  assert.strictEqual(R.canRun('submit', 'draft', kind, true).ok, true)
})

test('nextStatus：unapprove 的目标状态依赖来源（approved→submitted，completed→draft）', function () {
  assert.strictEqual(R.nextStatus('submit', 'draft'), 'submitted')
  assert.strictEqual(R.nextStatus('approve', 'submitted'), 'approved')
  assert.strictEqual(R.nextStatus('reject', 'submitted'), 'draft')
  assert.strictEqual(R.nextStatus('void', 'submitted'), 'voided')
  assert.strictEqual(R.nextStatus('complete', 'approved'), 'completed')
  assert.strictEqual(R.nextStatus('unapprove', 'approved'), 'submitted')
  assert.strictEqual(R.nextStatus('unapprove', 'completed'), 'draft')
})

test('只有草稿可编辑（列表「修改」入口与表单只读判定同源）', function () {
  assert.strictEqual(R.isEditable('draft'), true)
  assert.strictEqual(R.isEditable('submitted'), false)
  assert.strictEqual(R.isEditable('approved'), false)
  assert.strictEqual(R.isEditable('completed'), false)
  assert.strictEqual(R.isEditable('voided'), false)
})

test('visibleActions：各状态应出现的动作集合（操作列显隐的唯一依据）', function () {
  var order = KINDS.getKind('purchase_order')
  var stockIn = KINDS.getKind('stock_in')
  var codes = function (kind, status) {
    return R.visibleActions(kind, status).map(function (a) { return a.code })
  }
  assert.deepStrictEqual(codes(order, 'draft'), ['submit', 'void'])
  assert.deepStrictEqual(codes(order, 'submitted'), ['approve', 'reject', 'void'])
  assert.deepStrictEqual(codes(order, 'approved'), ['unapprove', 'complete'])
  assert.deepStrictEqual(codes(order, 'completed'), ['unapprove'])
  assert.deepStrictEqual(codes(order, 'voided'), [])
  // 库存类：已审核状态**没有**「置为已完成」
  assert.deepStrictEqual(codes(stockIn, 'approved'), ['unapprove'])
  // 矩阵按后端逐格镜像（unapprove 的白名单含 completed），前端不额外造"库存类 completed"特例；
  // 现实中库存类单据 approved 即终态，因此该行不会存在（design D4 的兜底由服务端拒绝保证）。
  assert.deepStrictEqual(codes(stockIn, 'completed'), ['unapprove'])
})

test('visibleActions 每项都带权限点（驳回复用 approve；完成用 status）', function () {
  var kind = KINDS.getKind('purchase_order')
  var submitted = R.visibleActions(kind, 'submitted')
  var reject = submitted.filter(function (a) { return a.code === 'reject' })[0]
  var approve = submitted.filter(function (a) { return a.code === 'approve' })[0]
  assert.strictEqual(reject.perm, 'pur:order:approve')
  assert.strictEqual(approve.perm, 'pur:order:approve')
  var approved = R.visibleActions(kind, 'approved')
  var complete = approved.filter(function (a) { return a.code === 'complete' })[0]
  var unapprove = approved.filter(function (a) { return a.code === 'unapprove' })[0]
  // 队长裁决 D3：complete 用 :status（与落地控制器一致），不是 :approve
  assert.strictEqual(complete.perm, 'pur:order:status')
  assert.strictEqual(unapprove.perm, 'pur:order:unapprove')
})

/* ============================================================================
 * 二、权限点 / URL / 下推链（PRD §7.5、§9.4 冻结值）
 * ========================================================================== */

test('8 类单据的权限点前缀与 URL 前缀与落地控制器一致', function () {
  var expected = {
    purchase_request: 'pur:request',
    purchase_order: 'pur:order',
    sales_request: 'sal:request',
    sales_order: 'sal:order',
    stock_in: 'stk:in-order',
    stock_out: 'stk:out-order',
    stock_take: 'stk:take',
    stock_transfer: 'stk:transfer'
  }
  // 队长裁决 D1：采购 base 保留 `/erp/pur/*`（有意偏离 PRD §9.4 的示例路径，已记账）
  var expectedRest = {
    purchase_request: '/erp/pur/request',
    purchase_order: '/erp/pur/order',
    sales_request: '/sal/request',
    sales_order: '/sal/order',
    stock_in: '/stk/in-order',
    stock_out: '/stk/out-order',
    stock_take: '/stk/take',
    stock_transfer: '/stk/transfer'
  }
  KINDS.KINDS.forEach(function (kind) {
    assert.strictEqual(kind.permPrefix, expected[kind.code], kind.code + ' 的权限点前缀')
    assert.strictEqual(kind.restBase, expectedRest[kind.code], kind.code + ' 的 URL 前缀')
  })
})

test('permOf：动作段映射（reject 用 approve，complete 用 status）', function () {
  var kind = KINDS.getKind('stock_in')
  var expected = {
    list: 'stk:in-order:list',
    query: 'stk:in-order:query',
    add: 'stk:in-order:add',
    edit: 'stk:in-order:edit',
    remove: 'stk:in-order:remove',
    export: 'stk:in-order:export',
    print: 'stk:in-order:print',
    submit: 'stk:in-order:submit',
    approve: 'stk:in-order:approve',
    reject: 'stk:in-order:approve',
    complete: 'stk:in-order:status',
    void: 'stk:in-order:void',
    unapprove: 'stk:in-order:unapprove'
  }
  Object.keys(expected).forEach(function (action) {
    assert.strictEqual(R.permOf(kind, action), expected[action], action + ' 的权限点')
  })
  assert.strictEqual(R.permOf(kind, 'unknown'), '')
})

test('下推链与权限点：一律挂**来源单据** `:push`（队长裁决 D4）', function () {
  assert.strictEqual(R.PUSH_CHAINS.purchase_request.to, 'purchase_order')
  assert.strictEqual(R.PUSH_CHAINS.purchase_request.perm, 'pur:request:push')
  assert.strictEqual(R.PUSH_CHAINS.purchase_order.to, 'stock_in')
  assert.strictEqual(R.PUSH_CHAINS.purchase_order.perm, 'pur:order:push')
  assert.strictEqual(R.PUSH_CHAINS.sales_request.to, 'sales_order')
  assert.strictEqual(R.PUSH_CHAINS.sales_request.perm, 'sal:request:push')
  assert.strictEqual(R.PUSH_CHAINS.sales_order.to, 'stock_out')
  assert.strictEqual(R.PUSH_CHAINS.sales_order.perm, 'sal:order:push')
  // 库存类单据没有下推链
  assert.strictEqual(R.hasPush(KINDS.getKind('stock_out')), false)
})

test('下推端点落地状态：**四条链的后端都已落地** ⇒ 全部不得 pending（t32 repair）', function () {
  assert.strictEqual(!!R.PUSH_CHAINS.purchase_request.push.pending, false)
  assert.strictEqual(!!R.PUSH_CHAINS.sales_request.push.pending, false)
  // t7（t4b）已交付 `POST /erp/pur/order/{id}/push/stock-in` ⇒ 采购单→入库单必须放行
  assert.strictEqual(!!R.PUSH_CHAINS.purchase_order.push.pending, false)
  // t8（t5b）已交付 `POST /sal/order/{id}/push` ⇒ 销售订单→出库单必须放行
  assert.strictEqual(!!R.PUSH_CHAINS.sales_order.push.pending, false)
})

test('下推端点形状：按已落地 Controller 的签名（path/query/body 逐条对照）', function () {
  // 采购单 → 入库单：`/push/stock-in` + query(warehouseId, remark) + 裸数组 body
  var po = R.PUSH_CHAINS.purchase_order.push
  assert.strictEqual(po.pathSuffix, '/push/stock-in')
  assert.strictEqual(po.bodyShape, 'lines-array')
  assert.deepStrictEqual(po.extraParams, ['warehouseId', 'remark'])
  assert.strictEqual(po.requiresWarehouse, true, '入库单 warehouse_id 是 NOT NULL ⇒ 前端必须要求收货仓库')
  // 销售订单 → 出库单：`/push` + {lines:[…]} body（服务端兼收裸数组与包装对象，前端用包装形状）
  var so = R.PUSH_CHAINS.sales_order.push
  assert.strictEqual(so.pathSuffix, '/push')
  assert.strictEqual(so.bodyShape, 'sales-request')
  assert.deepStrictEqual(so.lineKeys, { srcItemId: 'docItemId', qty: 'qty', productId: 'productId' })
})

/**
 * **判别用例**（t32 新增）：`pending` 必须与"后端 Controller 里到底有没有这个端点"一致。
 *
 * 判据是**源码**：直接读 `ruoyi-vue-oa-master` 的 Controller 文本，找 push 端点的映射与权限点。
 * 这样"后端好了、前端还藏着"（或反过来"后端没做、前端却放行 ⇒ 必然 404"）都会立刻变红。
 * 以前这条只能靠人工核对，于是 t24 才发现采购单那一侧漏翻了。
 */
test('判别：每条下推链的 pending 与后端 Controller 源码一致（防"后端好了、前端还藏着"）', function () {
  var backendErp = path.join(BACKEND_CTMS, 'erp')
  var procurement = path.join(backendErp, 'procurement', 'controller', 'ErpPurchaseOrderController.java')
  var procurementReq = path.join(backendErp, 'procurement', 'controller', 'ErpPurchaseRequestController.java')
  var sales = path.join(backendErp, 'sales', 'controller', 'ErpSalesController.java')

  // 先证明"源码可达"：读不到就说明判据失效，必须红（不能静默跳过）
  ;[procurement, procurementReq, sales].forEach(function (f) {
    assert.strictEqual(fs.existsSync(f), true, '读取后端源码失败（判据失效）：' + f)
  })

  var checks = [
    {
      code: 'purchase_request',
      file: procurementReq,
      // `@PostMapping("/{id}/push/purchase-order")`
      mapping: /@PostMapping\([^)]*\{id\}[^)]*\/push\/purchase-order/,
      perm: 'pur:request:push'
    },
    {
      code: 'purchase_order',
      file: procurement,
      mapping: /@PostMapping\([^)]*\{id\}[^)]*\/push\/stock-in/,
      perm: 'pur:order:push'
    },
    {
      code: 'sales_request',
      file: sales,
      mapping: /@PostMapping\([^)]*\/request\/\{id[^)]*\/push"/,
      perm: 'sal:request:push'
    },
    {
      code: 'sales_order',
      file: sales,
      mapping: /@PostMapping\([^)]*\/order\/\{id[^)]*\/push"/,
      perm: 'sal:order:push'
    }
  ]

  checks.forEach(function (c) {
    var text = fs.readFileSync(c.file, 'utf8')
    var landed = c.mapping.test(text)
    var permInSource = text.indexOf("hasPermi('" + c.perm + "')") >= 0
    var chain = R.PUSH_CHAINS[c.code]
    var pending = !!(chain && chain.push && chain.push.pending)
    assert.strictEqual(
      pending,
      !landed,
      c.code + '：后端端点' + (landed ? '已落地' : '未落地') + '，前端 pending 必须为 ' + (!landed) + '（实际 ' + pending + '）'
    )
    assert.strictEqual(permInSource, true, c.code + '：Controller 里找不到权限点 ' + c.perm)
    assert.strictEqual(chain.perm, c.perm, c.code + '：前端权限点必须与 Controller 逐字一致')
  })
})

test('下推可推状态：申请仅已审核；订单已审核或已完成（tasks.md 3.3/3.5/4.2/4.3）', function () {
  assert.deepStrictEqual(R.PUSH_CHAINS.purchase_request.sourceStatuses, ['approved'])
  assert.deepStrictEqual(R.PUSH_CHAINS.sales_request.sourceStatuses, ['approved'])
  assert.deepStrictEqual(R.PUSH_CHAINS.purchase_order.sourceStatuses, ['approved', 'completed'])
  assert.deepStrictEqual(R.PUSH_CHAINS.sales_order.sourceStatuses, ['approved', 'completed'])
})

test('剩余量 = 数量 − 已下推/已入库/已出库（DDL 快照列口径）', function () {
  var req = KINDS.getKind('purchase_request')
  var order = KINDS.getKind('purchase_order')
  assert.strictEqual(R.remainingQty(req, { qty: 100, orderedQty: 60 }), 40)
  assert.strictEqual(R.remainingQty(order, { qty: 10, receivedQty: 4 }), 6)
  assert.strictEqual(R.remainingQty(KINDS.getKind('sales_order'), { qty: 8, shippedQty: 8 }), 0)
})

test('pushRows：未填数量时按剩余量全推；剩余量为 0 的行预填 0', function () {
  var req = KINDS.getKind('purchase_request')
  var rows = R.pushRows(req, [
    { id: 'i1', qty: 100, orderedQty: 60, productName: '螺丝' },
    { id: 'i2', qty: 5, orderedQty: 5, productName: '螺母' }
  ])
  assert.strictEqual(rows.length, 2)
  assert.strictEqual(rows[0].pushQty, 40)
  assert.strictEqual(rows[0].remainQty, 40)
  assert.strictEqual(rows[1].pushQty, 0)
  assert.strictEqual(rows[0].srcItemId, 'i1')
})

test('validatePush：超量、负数、合计为 0、状态不符都被拒（tasks.md 3.3 的例子）', function () {
  var req = KINDS.getKind('purchase_request')
  // 申请行数量 100、已下单 60，本次下推 50 → 被拒（示例来自移植清单 §3.9.4）
  var over = R.validatePush(req, 'approved', [{ remainQty: 40, pushQty: 50 }])
  assert.strictEqual(over.ok, false)
  assert.ok(over.message.indexOf('超过剩余量') >= 0)
  // 不填数量下推剩余 40 → 通过
  var ok = R.validatePush(req, 'approved', [{ remainQty: 40, pushQty: 40 }])
  assert.strictEqual(ok.ok, true)
  assert.strictEqual(ok.total, 40)
  assert.strictEqual(R.validatePush(req, 'approved', [{ remainQty: 40, pushQty: -1 }]).ok, false)
  assert.strictEqual(R.validatePush(req, 'approved', [{ remainQty: 40, pushQty: 0 }]).ok, false)
  assert.strictEqual(R.validatePush(req, 'draft', [{ remainQty: 40, pushQty: 10 }]).ok, false)
  assert.strictEqual(R.validatePush(req, 'approved', []).ok, false)
})

test('canPush：申请单看剩余可下推量合计；服务端派生列优先；pending 链不给入口', function () {
  var req = KINDS.getKind('purchase_request')
  assert.strictEqual(R.canPush(req, { status: 'approved', remainQtySum: 10 }).ok, true)
  assert.strictEqual(R.canPush(req, { status: 'approved', remainQtySum: 0 }).ok, false)
  assert.strictEqual(R.canPush(req, { status: 'submitted', remainQtySum: 10 }).ok, false)
  // 服务端派生列（采购申请 canPush / 采购单 canReceive）优先于前端兜底
  assert.strictEqual(R.canPush(req, { status: 'approved', remainQtySum: 10, canPush: false }).ok, false)
  var order = KINDS.getKind('purchase_order')
  // t32 repair：t7 已交付 `/push/stock-in` ⇒ 已审核且服务端 canReceive 为真时**必须放行**（原来被 pending 挡住）
  assert.strictEqual(R.canPush(order, { status: 'approved', canReceive: true }).ok, true)
  assert.strictEqual(R.canPush(order, { status: 'approved', canReceive: false }).ok, false, '服务端说不可收货时仍不给入口')
  assert.strictEqual(R.canPush(order, { status: 'draft' }).ok, false)
  // 库存类无下推链
  assert.strictEqual(R.canPush(KINDS.getKind('stock_in'), { status: 'approved' }).ok, false)
})

test('validatePushExtra：采购单→入库单必须有收货仓库（否则服务端会拒绝）', function () {
  var order = KINDS.getKind('purchase_order')
  var bad = R.validatePushExtra(order, {})
  assert.strictEqual(bad.ok, false)
  assert.ok(bad.message.indexOf('收货仓库') >= 0)
  assert.strictEqual(R.validatePushExtra(order, { warehouseId: 'w1' }).ok, true)
  // 不要求仓库的链不受影响
  assert.strictEqual(R.validatePushExtra(KINDS.getKind('purchase_request'), {}).ok, true)
  assert.strictEqual(R.validatePushExtra(KINDS.getKind('sales_order'), {}).ok, true)
  // 无下推链的单据类型直接拒绝
  assert.strictEqual(R.validatePushExtra(KINDS.getKind('stock_in'), {}).ok, false)
})

/* ============================================================================
 * 二之二、请求构造（三族形状不同；**一处按 kind 派发**的实现单测）
 * ========================================================================== */

test('actionRequest：入库/出库是 POST {base}/{action}/{id}，reason 走 query', function () {
  var kind = KINDS.getKind('stock_in')
  var req = R.actionRequest(kind, 'approve', 'doc1')
  assert.deepStrictEqual(req, { method: 'post', url: '/stk/in-order/approve/doc1', params: {}, data: null })
  var reject = R.actionRequest(kind, 'reject', 'doc1', '不合格')
  assert.strictEqual(reject.method, 'post')
  assert.strictEqual(reject.url, '/stk/in-order/reject/doc1')
  assert.deepStrictEqual(reject.params, { reason: '不合格' })
})

test('actionRequest：采购是 PUT {base}/{id}/{action}，驳回折叠为 approve?action=reject', function () {
  var kind = KINDS.getKind('purchase_request')
  var approve = R.actionRequest(kind, 'approve', 'doc1', '同意')
  assert.strictEqual(approve.method, 'put')
  assert.strictEqual(approve.url, '/erp/pur/request/doc1/approve')
  assert.deepStrictEqual(approve.params, { reason: '同意' })
  var reject = R.actionRequest(kind, 'reject', 'doc1', '数量不符')
  assert.strictEqual(reject.method, 'put')
  assert.strictEqual(reject.url, '/erp/pur/request/doc1/approve')
  assert.deepStrictEqual(reject.params, { action: 'reject', reason: '数量不符' })
})

test('actionRequest：销售是 PUT {base}/{id}/{action}，reason 走 body；驳回是独立端点', function () {
  var kind = KINDS.getKind('sales_request')
  var reject = R.actionRequest(kind, 'reject', 'doc1', '客户取消')
  assert.strictEqual(reject.method, 'put')
  assert.strictEqual(reject.url, '/sal/request/doc1/reject')
  assert.deepStrictEqual(reject.data, { reason: '客户取消' })
  assert.deepStrictEqual(reject.params, {})
  var complete = R.actionRequest(kind, 'complete', 'doc1')
  assert.strictEqual(complete.url, '/sal/request/doc1/complete')
})

test('actionRequest：盘点/调拨（端点未落地）沿用 t9a 冻结形状 POST {base}/{id}/{action}', function () {
  var req = R.actionRequest(KINDS.getKind('stock_take'), 'submit', 'doc1')
  assert.strictEqual(req.method, 'post')
  assert.strictEqual(req.url, '/stk/take/doc1/submit')
  assert.strictEqual(req.data.id, 'doc1')
})

test('deleteRequest：入库/出库是 DELETE /{ids}（逗号分隔），其余是 DELETE /{id}', function () {
  assert.strictEqual(R.deleteRequest(KINDS.getKind('stock_in'), 'a').url, '/stk/in-order/a')
  assert.strictEqual(R.deleteRequest(KINDS.getKind('stock_in'), ['a', 'b']).url, '/stk/in-order/a,b')
  assert.strictEqual(R.deleteRequest(KINDS.getKind('purchase_order'), ['a', 'b']).url, '/erp/pur/order/a')
})

test('buildPushRequest：四条链的 URL/body 形状（采购裸数组、销售包装对象）', function () {
  var rows = [{ srcItemId: 'i1', productId: 'p1', qty: 10, remainQty: 10, pushQty: 4 }]
  var pr = R.buildPushRequest(KINDS.getKind('purchase_request'), 'doc1', rows, { supplierId: 's1' })
  assert.strictEqual(pr.method, 'post')
  assert.strictEqual(pr.url, '/erp/pur/request/doc1/push/purchase-order')
  assert.deepStrictEqual(pr.params, { supplierId: 's1' })
  assert.deepStrictEqual(pr.data, [{ srcItemId: 'i1', qty: 4 }])

  // t32 repair：采购单 → 入库单已落地（`/push/stock-in` + query warehouseId/remark + 裸数组 body）
  var po = R.buildPushRequest(KINDS.getKind('purchase_order'), 'doc1', rows, { warehouseId: 'w1', remark: '整单入库' })
  assert.strictEqual(po.method, 'post')
  assert.strictEqual(po.url, '/erp/pur/order/doc1/push/stock-in')
  assert.deepStrictEqual(po.params, { warehouseId: 'w1', remark: '整单入库' })
  assert.deepStrictEqual(po.data, [{ srcItemId: 'i1', qty: 4 }])

  var sr = R.buildPushRequest(KINDS.getKind('sales_request'), 'doc1', rows, { remark: '整单推' })
  assert.strictEqual(sr.url, '/sal/request/doc1/push')
  assert.deepStrictEqual(sr.data.lines, [{ docItemId: 'i1', qty: 4, productId: 'p1' }])
  assert.strictEqual(sr.data.remark, '整单推')

  // t32 repair：销售订单 → 出库单已落地（`/push` + 包装 body，服务端兼收裸数组）
  var so = R.buildPushRequest(KINDS.getKind('sales_order'), 'doc1', rows, {})
  assert.strictEqual(so.url, '/sal/order/doc1/push')
  assert.deepStrictEqual(so.data.lines, [{ docItemId: 'i1', qty: 4, productId: 'p1' }])
})

test('doc-kinds：筛选按 mapper 真源（公共关键字；经办人只在盘点/调拨）', function () {
  KINDS.KINDS.forEach(function (kind) {
    var props = kind.filters.map(function (f) { return f.prop })
    assert.ok(props.indexOf('keyword') >= 0, kind.code + ' 缺少 keyword 筛选')
    assert.ok(props.indexOf('docDateRange') >= 0, kind.code + ' 缺少日期区间筛选')
    assert.strictEqual(props.indexOf('docNo'), -1, kind.code + ' 不应再出现单号专有筛选（已并入 keyword）')
    var hasHandler = props.indexOf('handlerName') >= 0
    if (kind.code === 'stock_take' || kind.code === 'stock_transfer') {
      assert.strictEqual(hasHandler, true, kind.code + ' 的 mapper 支持经办人筛选')
    } else {
      assert.strictEqual(hasHandler, false, kind.code + ' 的 mapper 没有经办人筛选')
    }
  })
})

test('doc-kinds：请求形状/删除形状/打印开关逐类声明（t12 起的必填项）', function () {
  var expectedMode = {
    purchase_request: 'put-id-action',
    purchase_order: 'put-id-action',
    sales_request: 'put-id-action',
    sales_order: 'put-id-action',
    stock_in: 'post-action-id',
    stock_out: 'post-action-id',
    stock_take: 'post-id-action',
    stock_transfer: 'post-id-action'
  }
  var expectedDelete = {
    stock_in: 'ids',
    stock_out: 'ids',
    // t9 的盘点/调拨 Controller 也已落地，删除同样是 `DELETE /{ids}`（逗号分隔）
    stock_take: 'ids',
    stock_transfer: 'ids'
  }
  KINDS.KINDS.forEach(function (kind) {
    assert.strictEqual(kind.actionRequest.mode, expectedMode[kind.code], kind.code + ' 的动作形状')
    assert.strictEqual(kind.deleteMode, expectedDelete[kind.code] || 'id', kind.code + ' 的删除形状')
    assert.strictEqual(kind.printReady, false, kind.code + ' 的打印开关（B\'：本期静态未接入）')
  })
  // 销售没有变更历史端点 ⇒ 隐藏入口
  assert.strictEqual(KINDS.getKind('sales_request').changeLogs, false)
  assert.strictEqual(KINDS.getKind('sales_order').changeLogs, false)
  assert.strictEqual(KINDS.getKind('stock_in').changeLogs, true)
})

/* ============================================================================
 * 三、表单前置校验（与服务端口径一致）
 * ========================================================================== */

test('validateTransfer：两仓为空或相同被拒（tasks.md 6.1，前端也拦）', function () {
  var transfer = KINDS.getKind('stock_transfer')
  assert.strictEqual(R.validateTransfer(transfer, { fromWarehouseId: 'w1', toWarehouseId: 'w1' }).ok, false)
  assert.strictEqual(
    R.validateTransfer(transfer, { fromWarehouseId: 'w1', toWarehouseId: 'w1' }).message,
    '调出仓库与调入仓库不能相同'
  )
  assert.strictEqual(R.validateTransfer(transfer, { fromWarehouseId: '', toWarehouseId: 'w1' }).ok, false)
  assert.strictEqual(R.validateTransfer(transfer, { fromWarehouseId: 'w1', toWarehouseId: 'w2' }).ok, true)
  // 非调拨单据不触发该规则
  assert.strictEqual(R.validateTransfer(KINDS.getKind('stock_in'), {}).ok, true)
})

test('validateItems：无行项 / 数量为 0 / 负单价 / 精度超位都被拒并指明行号', function () {
  var kind = KINDS.getKind('purchase_order')
  assert.strictEqual(R.validateItems(kind, []).message, '单据没有行项，不能提交')
  assert.strictEqual(R.validateItems(kind, [{ productId: 'p1', qty: 0 }]).message, '第 1 行数量必须大于 0')
  assert.strictEqual(R.validateItems(kind, [{ productId: '', qty: 1 }]).message, '第 1 行未选择物料')
  assert.strictEqual(
    R.validateItems(kind, [{ productId: 'p1', qty: 1, unitPrice: -1 }]).message,
    '第 1 行单价不能为负数'
  )
  // 单位 0 位小数时填 1.5 被拒
  var decimals0 = R.validateItems(kind, [{ productId: 'p1', qty: '1.5', uomDecimals: 0, unitPrice: 1 }])
  assert.strictEqual(decimals0.ok, false)
  assert.ok(decimals0.message.indexOf('超出单位小数位') >= 0)
  // 单位 1 位小数填 1.5 通过；3 位小数填 1.234 通过
  assert.strictEqual(R.validateItems(kind, [{ productId: 'p1', qty: '1.5', uomDecimals: 1, unitPrice: 1 }]).ok, true)
  assert.strictEqual(R.validateItems(kind, [{ productId: 'p1', qty: '1.234', uomDecimals: 3, unitPrice: 1 }]).ok, true)
})

test('countDecimals：按文本判定小数位（不被浮点误差影响）', function () {
  assert.strictEqual(R.countDecimals('1.005'), 3)
  assert.strictEqual(R.countDecimals(1.5), 1)
  assert.strictEqual(R.countDecimals(2), 0)
  assert.strictEqual(R.countDecimals(''), 0)
})

test('formatQty：去掉无意义尾随 0（列表与对话框架显示同一口径）', function () {
  assert.strictEqual(R.formatQty(1.5), '1.5')
  assert.strictEqual(R.formatQty('2.000'), '2')
  assert.strictEqual(R.formatQty(0), '0')
})

/* ============================================================================
 * 四、单据类型配置完整性（8 类齐备、配置与状态机自洽）
 * ========================================================================== */

test('doc-kinds：8 类单据配置齐备且自洽（validateKinds 无问题）', function () {
  var result = R.validateKinds(KINDS.KINDS)
  assert.deepStrictEqual(result.problems, [])
  assert.strictEqual(result.ok, true)
})

test('doc-kinds：库存类 = 入库/出库/盘点/调拨（用 approved 作终态）', function () {
  var stock = KINDS.KINDS.filter(function (k) { return k.stockDoc }).map(function (k) { return k.code })
  assert.deepStrictEqual(stock, ['stock_in', 'stock_out', 'stock_take', 'stock_transfer'])
})

test('doc-kinds：金额/单价列的显隐按 DDL 与业务口径（调拨无金额、申请单无单据金额列）', function () {
  var transfer = KINDS.getKind('stock_transfer')
  assert.strictEqual(transfer.showPrice, false)
  assert.strictEqual(transfer.showAmount, false)
  assert.strictEqual(
    transfer.itemColumns.filter(function (c) { return c.kind === 'price' || c.kind === 'money' }).length,
    0
  )
  assert.strictEqual(KINDS.getKind('purchase_request').totalAmountColumn, false)
  assert.strictEqual(KINDS.getKind('purchase_order').totalAmountColumn, true)
  assert.strictEqual(KINDS.getKind('stock_take').totalAmountColumn, false)
})

test('doc-kinds：盘点行项有账面/实盘/差异列，实盘可编辑、账面只读', function () {
  var take = KINDS.getKind('stock_take')
  var byProp = {}
  take.itemColumns.forEach(function (c) { byProp[c.prop] = c })
  assert.strictEqual(byProp.bookQty.editable, false)
  assert.strictEqual(byProp.actualQty.editable, true)
  assert.strictEqual(byProp.actualQty.kind, 'actualQty')
  assert.strictEqual(byProp.diffQty.kind, 'diffQty')
  assert.strictEqual(take.specialForm, 'stocktake')
})

test('doc-kinds：出入库行项有行级仓库列，调拨行项刻意没有', function () {
  var hasWarehouse = function (code) {
    return KINDS.getKind(code).itemColumns.some(function (c) { return c.kind === 'warehouse' })
  }
  assert.strictEqual(hasWarehouse('stock_in'), true)
  assert.strictEqual(hasWarehouse('stock_out'), true)
  assert.strictEqual(hasWarehouse('purchase_order'), true)
  assert.strictEqual(hasWarehouse('stock_transfer'), false)
})

test('doc-kinds：申请单列表带剩余可下推量字段（tasks.md 3.4 的服务端字段）', function () {
  KINDS.KINDS_WITH_REMAIN_SUM.forEach(function (code) {
    var hasColumn = KINDS.getKind(code).columns.some(function (c) { return c.prop === 'remainQtySum' })
    assert.strictEqual(hasColumn, true, code + ' 缺少剩余可下推列')
  })
  assert.deepStrictEqual(KINDS.KINDS_WITH_REMAIN_SUM, ['purchase_request', 'sales_request'])
})

test('doc-kinds：附件对象类型码与单据码一致（挂附件不做二次映射）', function () {
  KINDS.KINDS.forEach(function (kind) {
    assert.strictEqual(kind.attachmentObjectType, kind.code)
  })
})

/* ============================================================================
 * 五、主数据四页配置
 * ========================================================================== */

test('master-page：四个主数据页面配置齐备且自洽（validateMasters 无问题）', function () {
  var result = MASTERS.validateMasters()
  assert.deepStrictEqual(result.problems, [])
  assert.strictEqual(result.ok, true)
})

test('master-page：权限点沿用 B3 控制器注解 ctms:partner:*（含启停用走 edit）', function () {
  assert.strictEqual(CONST.MASTER_PERM_PREFIX, 'ctms:partner')
  assert.strictEqual(MASTERS.perm('list'), 'ctms:partner:list')
  assert.strictEqual(MASTERS.perm('edit'), 'ctms:partner:edit')
  MASTERS.MASTERS.forEach(function (cfg) {
    assert.strictEqual(cfg.permPrefix, 'ctms:partner')
  })
})

test('master-page：计量单位小数位限定 0~4、物料编码可留空由服务端生成', function () {
  var uom = MASTERS.getMaster('uom')
  var decimals = uom.fields.filter(function (f) { return f.prop === 'decimals' })[0]
  assert.strictEqual(decimals.min, 0)
  assert.strictEqual(decimals.max, 4)
  var product = MASTERS.getMaster('product')
  var code = product.fields.filter(function (f) { return f.prop === 'code' })[0]
  assert.strictEqual(!!code.required, false)
  var type = product.fields.filter(function (f) { return f.prop === 'productTypeId' })[0]
  assert.strictEqual(type.kind, 'productType')
  assert.strictEqual(type.required, true)
})

test('master-page：物料类型页是树形（tree=true），其余三页是列表', function () {
  assert.strictEqual(MASTERS.getMaster('product-type').tree, true)
  assert.strictEqual(MASTERS.getMaster('product').tree, false)
  assert.strictEqual(MASTERS.getMaster('uom').tree, false)
  assert.strictEqual(MASTERS.getMaster('warehouse').tree, false)
})

/* ============================================================================
 * 六、Element Plus → Element UI 的静默改写清单（落地为可执行的门禁）
 * ========================================================================== */

test('常量：日期格式串是小写的 Element UI 记号（全大写年份记号会静默拿到空值）', function () {
  assert.strictEqual(CONST.DATE_FORMAT, 'yyyy-MM-dd')
  assert.strictEqual(CONST.DATETIME_FORMAT, 'yyyy-MM-dd HH:mm:ss')
})

test('门禁：进销存源码里不出现全大写年份日期记号（含注释）', function () {
  var bad = []
  var upper = ['Y', 'Y', 'Y', 'Y'].join('') + '-M' // 拼出来是为了不让本文件自己命中该门禁
  erpFiles().forEach(function (f) {
    var text = fs.readFileSync(f, 'utf8')
    if (text.indexOf(upper) >= 0) {
      bad.push(path.relative(ERP_DIR, f))
    }
  })
  assert.deepStrictEqual(bad, [])
})

test('门禁：进销存源码用 this.$modal / this.$message，不引入 element-plus', function () {
  var bad = []
  erpCodeFiles().forEach(function (f) {
    var text = fs.readFileSync(f, 'utf8')
    if (text.indexOf('element-plus') >= 0) {
      bad.push(path.relative(ERP_DIR, f) + ' 引用了 element-plus')
    }
    if (/import\s*\{[^}]*ElMessage/.test(text)) {
      bad.push(path.relative(ERP_DIR, f) + ' 使用了 ElMessage')
    }
  })
  assert.deepStrictEqual(bad, [])
})

test('门禁：进销存页面的 <style> 块里不写裸十六进制色值（只写主题令牌）', function () {
  var bad = []
  erpFiles('.vue').forEach(function (f) {
    var text = fs.readFileSync(f, 'utf8')
    var blocks = text.match(/<style[\s\S]*?<\/style>/g) || []
    blocks.forEach(function (block) {
      var hit = block.match(/#[0-9a-fA-F]{3,8}\b/)
      if (hit) {
        bad.push(path.relative(ERP_DIR, f) + ' → ' + hit[0])
      }
    })
  })
  assert.deepStrictEqual(bad, [])
})

test('门禁：进销存源码里 var(--oa-*) 引用的令牌都已在 oa-tokens.scss 声明', function () {
  var declared = {}
  var tokenText = fs.readFileSync(TOKENS_FILE, 'utf8')
  var declRe = /(--oa-[a-z0-9-]+)\s*:/g
  var m
  while ((m = declRe.exec(tokenText))) {
    declared[m[1]] = true
  }
  var orphans = {}
  erpFiles().forEach(function (f) {
    var text = fs.readFileSync(f, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
    var useRe = /var\(\s*(--oa-[a-z0-9-]+)/g
    var mm
    while ((mm = useRe.exec(text))) {
      if (!declared[mm[1]]) {
        orphans[mm[1]] = path.relative(ERP_DIR, f)
      }
    }
  })
  assert.deepStrictEqual(Object.keys(orphans), [])
})

/* ============================================================================
 * 七、文件落点（菜单 component 字符串的契约）
 * ========================================================================== */

test('文件落点：壳页面与四页主数据都在 src/views/erp/**（菜单 component 与之一一对应）', function () {
  var must = [
    'doc/list.vue',
    'doc/form.vue',
    'doc/DocListShell.vue',
    'doc/DocFormShell.vue',
    'doc/components/DocStatusTag.vue',
    'doc/components/DocItemsTable.vue',
    'doc/components/DocPushDialog.vue',
    'doc/components/DocActionDialog.vue',
    'doc/components/DocAttachmentPanel.vue',
    'master/product/index.vue',
    'master/product-type/index.vue',
    'master/uom/index.vue',
    'master/warehouse/index.vue'
  ]
  must.forEach(function (rel) {
    assert.strictEqual(fs.existsSync(path.join(ERP_DIR, rel)), true, '缺少文件：' + rel)
  })
})

test('菜单契约：8 类单据的 listPath/formPath 与主数据四页路径均为 /erp/**', function () {
  KINDS.KINDS.forEach(function (kind) {
    assert.strictEqual(kind.listPath.indexOf('/erp/'), 0, kind.code + ' 的 listPath')
    assert.strictEqual(kind.formPath.indexOf('/erp/'), 0, kind.code + ' 的 formPath')
  })
  MASTERS.MASTERS.forEach(function (cfg) {
    assert.strictEqual(cfg.listPath.indexOf('/erp/master/'), 0, cfg.key + ' 的 listPath')
  })
  var paths = KINDS.KINDS.map(function (k) { return k.listPath })
  assert.strictEqual(new Set(paths).size, 8, '8 类单据的列表路径必须互不相同')
})
