/**
 * 选人 / 选角色 ↔ 流程存储（id 数组）的前端用例（2026-10-05）。
 *
 * 覆盖：
 *   1. **存 id 不存 name**：选人/选角色组件给的是对象数组，写回流程必须是 userId / roleId
 *      （实测引擎 `ACT_RU_TASK.ASSIGNEE_` 用的就是 `192F6061…` / `superAdmin`）；
 *   2. 空对象 / 缺 id 被过滤，不把脏值写进流程定义；
 *   3. 还原时查不到的 id **不隐藏**（兜底"（用户不存在）"），否则用户以为选的人丢了；
 *   4. 编辑既有流程能正确回显（这是本次改动的关键回归点：旧数据里存的是 id）。
 *
 * 2026-10-05 第二次尝试（HANDOFF §3.3 方案 A）追加：id 清洗（cleanIds）、
 * "清单里没有 ⇒ 按 id 补查"（missingUserIds / missingRoleIds）、
 * assignee 归一化补丁（assigneePatch，配合调用方 `$set`）、画布摘要（summarizeNames）。
 *
 * 跑法：`npm run test:unit`（= `node tests/run.js`）。
 */
'use strict'

var assert = require('assert')
var h = require('./harness')
var test = h.test
var M = require('../src/views/workflow/simple-flow/assigneeMapping.js')

var USERS = [
  { userId: '192F606172CB4405AFD3FA1976CE4098', nickName: '李娜', avatar: '/a.png' },
  { userId: '58FE8668233B422FB69EE575F5F402A5', nickName: '张伟', avatar: '/b.png' },
  { userId: 'superAdmin', nickName: '超级管理员', avatar: '/c.png' }
]
var ROLES = [
  { roleId: '2', roleName: '普通角色' },
  { roleId: '100', roleName: '流程管理员' }
]

/* ==================== 写入：对象 → id 数组 ==================== */

test('选人确认只写 userId（不写 nickName）', function () {
  var selected = [{ userId: '58FE8668233B422FB69EE575F5F402A5', nickName: '张伟' }]
  assert.deepStrictEqual(M.toUserIds(selected), ['58FE8668233B422FB69EE575F5F402A5'])
})

test('多选人员按顺序全部保留', function () {
  var selected = [
    { userId: '192F606172CB4405AFD3FA1976CE4098', nickName: '李娜' },
    { userId: 'superAdmin', nickName: '超级管理员' }
  ]
  assert.deepStrictEqual(M.toUserIds(selected), ['192F606172CB4405AFD3FA1976CE4098', 'superAdmin'])
})

test('空选 / null / 缺 id 都被安全处理（不写脏值）', function () {
  assert.deepStrictEqual(M.toUserIds([]), [])
  assert.deepStrictEqual(M.toUserIds(null), [])
  assert.deepStrictEqual(M.toUserIds(undefined), [])
  assert.deepStrictEqual(M.toUserIds([{}, { nickName: '没id' }, null]), [])
})

test('选角色确认只写 roleId', function () {
  var selected = [{ roleId: '100', roleName: '流程管理员' }]
  assert.deepStrictEqual(M.toRoleIds(selected), ['100'])
})

/* ==================== 读回：id 数组 → 对象（编辑既有流程） ==================== */

test('编辑既有流程：user_id 回显成用户对象（带 nickName，chip 才能显示人名）', function () {
  var objs = M.toUserObjects(['58FE8668233B422FB69EE575F5F402A5'], USERS)
  assert.strictEqual(objs.length, 1)
  assert.strictEqual(objs[0].nickName, '张伟')
  assert.strictEqual(objs[0].userId, '58FE8668233B422FB69EE575F5F402A5')
})

test('既有流程里的 superAdmin（字面 userId）也能回显', function () {
  var objs = M.toUserObjects(['superAdmin'], USERS)
  assert.strictEqual(objs[0].nickName, '超级管理员')
})

test('用户清单里查不到的 id → 兜底占位且**不隐藏**', function () {
  var objs = M.toUserObjects(['ghost-id'], USERS)
  assert.strictEqual(objs.length, 1, '不能把查不到的已选人员静默丢掉')
  assert.strictEqual(objs[0].userId, 'ghost-id')
  assert.ok(objs[0].nickName.indexOf('用户不存在') >= 0)
})

test('用户清单加载失败（空清单）时，已选 id 仍全部可见并被标注', function () {
  var objs = M.toUserObjects(['superAdmin', '58FE8668233B422FB69EE575F5F402A5'], [])
  assert.strictEqual(objs.length, 2)
  assert.ok(objs.every(function (o) { return o.nickName.indexOf('用户不存在') >= 0 }))
})

test('角色回显：查到给 roleName，查不到兜底占位', function () {
  var ok = M.toRoleObjects(['2'], ROLES)
  assert.strictEqual(ok[0].roleName, '普通角色')
  var ghost = M.toRoleObjects(['999'], ROLES)
  assert.strictEqual(ghost[0].roleId, '999')
  assert.ok(ghost[0].roleName.indexOf('角色不存在') >= 0)
})

/* ==================== 往返一致性（改前改后不得丢人） ==================== */

test('往返一致：ids → 对象 → ids 不丢不改', function () {
  var ids = ['192F606172CB4405AFD3FA1976CE4098', 'superAdmin']
  assert.deepStrictEqual(M.toUserIds(M.toUserObjects(ids, USERS)), ids)
})

test('往返一致：用户清单为空时往返也不丢（兜底对象带得上 userId）', function () {
  var ids = ['192F606172CB4405AFD3FA1976CE4098']
  assert.deepStrictEqual(M.toUserIds(M.toUserObjects(ids, [])), ids)
})

test('往返一致：角色同理', function () {
  var ids = ['2', '100']
  assert.deepStrictEqual(M.toRoleIds(M.toRoleObjects(ids, ROLES)), ids)
})

/* ============ 2026-10-05 第二次尝试（§3.3 方案 A）新增：清洗 / 补查 / 归一化 / 摘要 ============ */

/** `/system/user/getUsers` 的真实形态：**不含超管**（实测只回 4 人） */
var GET_USERS = [
  { userId: '192F606172CB4405AFD3FA1976CE4098', nickName: '李娜' },
  { userId: '58FE8668233B422FB69EE575F5F402A5', nickName: '张伟' }
]

test('cleanIds：去空、去首尾空白、去重、保序（这些 id 会被 join 进 BPMN，不能有空洞）', function () {
  assert.deepStrictEqual(M.cleanIds(['a', '', null, ' a ', 'a', 'b', undefined]), ['a', 'b'])
  assert.deepStrictEqual(M.cleanIds(null), [])
  assert.deepStrictEqual(M.cleanIds([1, '2']), ['1', '2'])
})

test('missingUserIds：超管不在 getUsers 清单里 ⇒ 必须被识别为"要按 id 补查"', function () {
  assert.deepStrictEqual(
    M.missingUserIds(['superAdmin', '192F606172CB4405AFD3FA1976CE4098'], GET_USERS),
    ['superAdmin']
  )
})

test('missingUserIds：清单为空时全部都要补查（不是"没有缺人"）', function () {
  assert.deepStrictEqual(M.missingUserIds(['a', 'b'], []), ['a', 'b'])
  assert.deepStrictEqual(M.missingUserIds([], GET_USERS), [])
})

test('missingRoleIds：角色清单里查不到的 roleId 同样要能识别出来（原值不丢，但要提示）', function () {
  assert.deepStrictEqual(M.missingRoleIds(['2', '999'], ROLES), ['999'])
  assert.deepStrictEqual(M.missingRoleIds(['2'], []), ['2'])
})

test('assigneePatch：整个 assignee 缺失 ⇒ 给出完整默认对象（交由调用方 $set 写回）', function () {
  var p = M.assigneePatch(undefined)
  assert.deepStrictEqual(p.assignee, { source: 'USER', userIds: [], roleIds: [], level: 1 })
  assert.deepStrictEqual(M.assigneePatch(null).assignee, { source: 'USER', userIds: [], roleIds: [], level: 1 })
})

test('assigneePatch：只缺某个字段时只补那个字段（不整块覆盖用户已配好的内容）', function () {
  assert.deepStrictEqual(M.assigneePatch({ source: 'ROLE', roleIds: ['2'] }), { userIds: [] })
  assert.deepStrictEqual(M.assigneePatch({ source: 'USER', userIds: ['u1'] }), { roleIds: [] })
})

test('assigneePatch：形状齐全时是空补丁（幂等，不会无谓地写数据）', function () {
  assert.deepStrictEqual(M.assigneePatch({ source: 'USER', userIds: [], roleIds: [] }), {})
})

test('summarizeNames：不超过上限时全列，超过则折叠为"等 N 人"', function () {
  assert.strictEqual(M.summarizeNames(M.toUserObjects(['58FE8668233B422FB69EE575F5F402A5'], USERS)), '张伟')
  assert.strictEqual(M.summarizeNames([]), '')
  var four = M.toRoleObjects(['2', '100', 'x', 'y'], ROLES)
  assert.strictEqual(M.summarizeNames(four), '普通角色、流程管理员、x（角色不存在） 等 4 人')
  assert.strictEqual(M.summarizeNames(four, 4), '普通角色、流程管理员、x（角色不存在）、y（角色不存在）')
})

test('summarizeNames：查不到的 id 也出现在摘要里（画布上不隐藏"这个人查不到"）', function () {
  var s = M.summarizeNames(M.toUserObjects(['ghost'], []))
  assert.ok(s.indexOf('ghost') >= 0)
  assert.ok(s.indexOf('用户不存在') >= 0)
})

/* ====== 2026-10-05 修复：多人方式=单人 ⇒ 指定人员只能一个人（§缺陷：单人节点可勾多人） ====== */

test('singleModeUserIds：单人（SINGLE）⇒ 只留第 1 个人（保序）', function () {
  assert.deepStrictEqual(
    M.singleModeUserIds('SINGLE', ['192F606172CB4405AFD3FA1976CE4098', 'superAdmin']),
    ['192F606172CB4405AFD3FA1976CE4098']
  )
})

test('singleModeUserIds：多人方式未设置（旧数据）按单人处理 —— 与编译器 defaultIfBlank(M_SINGLE) 同口径', function () {
  assert.deepStrictEqual(M.singleModeUserIds(undefined, ['a', 'b']), ['a'])
  assert.deepStrictEqual(M.singleModeUserIds(null, ['a', 'b']), ['a'])
  assert.deepStrictEqual(M.singleModeUserIds('', ['a', 'b']), ['a'])
})

test('singleModeUserIds：会签 / 或签 / 依次 ⇒ 原样保留多人（多实例要的就是多人）', function () {
  assert.deepStrictEqual(M.singleModeUserIds('AND', ['a', 'b']), ['a', 'b'])
  assert.deepStrictEqual(M.singleModeUserIds('OR', ['a', 'b']), ['a', 'b'])
  assert.deepStrictEqual(M.singleModeUserIds('SEQ', ['a', 'b']), ['a', 'b'])
})

test('singleModeUserIds：单人下 0 / 1 人不变（不会凭空造人）', function () {
  assert.deepStrictEqual(M.singleModeUserIds('SINGLE', []), [])
  assert.deepStrictEqual(M.singleModeUserIds('SINGLE', null), [])
  assert.deepStrictEqual(M.singleModeUserIds('SINGLE', ['a']), ['a'])
})

test('singleModeUserIds：先清洗再截断（空串 / 空白 / 重复不能占掉"第 1 个"的位置）', function () {
  assert.deepStrictEqual(M.singleModeUserIds('SINGLE', ['', '  ', 'a', 'a', 'b']), ['a'])
  assert.deepStrictEqual(M.singleModeUserIds('AND', ['', 'a', 'a', 'b']), ['a', 'b'])
})

test('singleModeUserIds：收敛后必然 ≤1 人（编译期靠人数决定 assignee / candidateUsers）', function () {
  var many = ['u1', 'u2', 'u3', 'u4', 'u5']
  ;['SINGLE', '', undefined, null].forEach(function (mode) {
    assert.ok(M.singleModeUserIds(mode, many).length <= 1, '单人模式下仍可能写出多人：' + mode)
  })
  assert.strictEqual(M.singleModeUserIds('AND', many).length, 5)
})
