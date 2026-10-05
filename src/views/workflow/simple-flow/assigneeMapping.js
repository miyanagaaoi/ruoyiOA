/**
 * 选人 / 选角色 与"流程定义里存的 id 数组"之间的**纯转换**。
 *
 * 为什么要单独抽出来：
 *   流程定义（`t_flow_simple.content`）与流程引擎里存的是 **`user_id` / `role_id`**
 *   （实测 `ACT_RU_TASK.ASSIGNEE_` = `192F6061…` / `superAdmin`），而平台选人组件
 *   （`form/FormUserSelect`、`Process/panel/roleSelect`）的 v-model 是**对象数组**
 *   （靠 `nickName` / `roleName` 渲染 chip）。两边口径不同，转换必须可测，
 *   否则"选完人存错值"这类问题只会在运行期才暴露。
 *
 * 约定：
 *   - 存 id，不存 name（引擎只认 id）；
 *   - id 为空的对象**过滤掉**（避免脏值写进流程定义）；
 *   - 还原时查不到的对象**不隐藏**，兜底成"（用户不存在）"占位 —— 让用户看得见，
 *     而不是以为"我选的人丢了"。
 *
 * 接入方（2026-10-05 第二次尝试，§3.3 方案 A）：
 *   `components/FlowDesigner.vue` 的节点配置里，「指定人员」= 自绘 chip + 受控选人弹窗
 *   （`components/org/UserAllSelect`），「角色」= 角色列表多选；
 *   两处**存进流程定义的都只有 id**，界面显示靠本模块的 toXxxObjects / summarizeNames 还原。
 *
 * @author 二开
 */

/** 选人组件回传的对象数组 → 流程里要存的 userId 数组 */
function toUserIds(users) {
  return (users || []).map(function (u) { return u && u.userId }).filter(function (id) { return !!id })
}

/** 选角色组件回传的对象数组 → 流程里要存的 roleId 数组 */
function toRoleIds(roles) {
  return (roles || []).map(function (r) { return r && r.roleId }).filter(function (id) { return !!id })
}

/**
 * userId 数组 → 选人组件要的对象数组（按 id 在用户清单里补全 nickName/avatar）。
 *
 * @param {string[]} ids
 * @param {Array} allUsers 系统用户清单（`/system/user/getUsers`）
 */
function toUserObjects(ids, allUsers) {
  var list = allUsers || []
  return (ids || []).map(function (id) {
    var found = null
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].userId === id) {
        found = list[i]
        break
      }
    }
    // 查不到也要显示出来（否则用户以为没人），但明确标注，避免误判成"选的人丢了"
    return found || { userId: id, nickName: id + '（用户不存在）', avatar: '' }
  })
}

/** roleId 数组 → 选角色组件要的对象数组 */
function toRoleObjects(ids, allRoles) {
  var list = allRoles || []
  return (ids || []).map(function (id) {
    var found = null
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].roleId === id) {
        found = list[i]
        break
      }
    }
    return found || { roleId: id, roleName: id + '（角色不存在）' }
  })
}

/**
 * 「多人方式 = 单人（SINGLE）」时的**指定人员**收敛：最多保留 1 个人，保序取第一个。
 *
 * 为什么必须收敛（不是纯界面约束）：
 *   编译期 `SimpleFlowCompiler#renderUserTask` 对「指定人员」是按**人数**分支的 ——
 *   1 人 → `flowable:assignee="<id>"`（任务直接派给他）；>1 人 → `flowable:candidateUsers="a,b"`
 *   （变成"候选人抢单"，`ASSIGNEE_` 恒为 NULL、多人待办）。也就是说
 *   多人方式选「单人」却存了 2 个人时，**落库是"单人"、编译出来却是候选人任务**，
 *   两人都能看到待办 —— 与用户在界面上选的语义不一致。
 *   所以"单人 ⇒ 只能一个人"必须在写入口径上成立，而不只是界面提示一下。
 *
 * 非 SINGLE（AND/OR/SEQ）原样返回；未设置按 `SINGLE` 默认处理（与编译器的
 * `StringUtils.defaultIfBlank(multiMode, M_SINGLE)` 同一口径）。
 *
 * @param {string} multiMode 节点多人方式（'SINGLE' / 'AND' / 'OR' / 'SEQ'；空 = 单人）
 * @param {string[]} ids userId 数组（内部先过 cleanIds）
 */
function singleModeUserIds(multiMode, ids) {
  var list = cleanIds(ids)
  if (multiMode && multiMode !== 'SINGLE') return list
  return list.slice(0, 1)
}

/**
 * 把任意输入收敛成"干净的 id 数组"：去 null/空白、去重、保序。
 * 流程定义里的 userIds/roleIds 是**引擎直接消费**的数组（`String.join(",")` 后进 BPMN），
 * 混进空串会编译成 `candidateUsers="a,,b"`，必须在这里挡住。
 */
function cleanIds(ids) {
  var out = []
  ;(ids || []).forEach(function (id) {
    if (id === null || id === undefined) return
    var s = String(id).trim()
    if (!s) return
    if (out.indexOf(s) < 0) out.push(s)
  })
  return out
}

/** 用户清单里查不到的 userId（需要按 id 逐个补查，或显式提示"这个人查不到"） */
function missingUserIds(ids, allUsers) {
  var list = allUsers || []
  return cleanIds(ids).filter(function (id) {
    return !list.some(function (u) { return u && u.userId === id })
  })
}

/** 角色清单里查不到的 roleId（保留原值不丢，但要能提示出来） */
function missingRoleIds(ids, allRoles) {
  var list = allRoles || []
  return cleanIds(ids).filter(function (id) {
    return !list.some(function (r) { return r && r.roleId === id })
  })
}

/**
 * 计算"把 assignee 补齐成规范形状"需要写的字段。
 *
 * 为什么是"补丁"而不是直接改：本模块要**保持纯函数**（可被 `node tests/run.js` 单测），
 * 而 Vue2 对"新增属性"不做响应式，补字段必须由调用方用 `$set` 完成。
 * 整个 assignee 都缺失时返回 `{ assignee: {...} }`，其余情况返回 `{ 字段名: 默认值 }`。
 */
function assigneePatch(assignee) {
  if (!assignee || typeof assignee !== 'object') {
    return { assignee: { source: 'USER', userIds: [], roleIds: [], level: 1 } }
  }
  var patch = {}
  if (!assignee.source) patch.source = 'USER'
  if (!Array.isArray(assignee.userIds)) patch.userIds = []
  if (!Array.isArray(assignee.roleIds)) patch.roleIds = []
  return patch
}

/**
 * 对象数组 → 一行显示用文字（画布节点摘要）。
 * 查不到的对象已经有"（用户不存在）"占位，这里只管拼接与折叠，不隐藏任何一项。
 */
function summarizeNames(objects, limit) {
  var cap = limit || 3
  var names = (objects || [])
    .map(function (o) { return (o && (o.nickName || o.roleName)) || '' })
    .filter(function (n) { return !!n })
  if (!names.length) return ''
  if (names.length <= cap) return names.join('、')
  return names.slice(0, cap).join('、') + ' 等 ' + names.length + ' 人'
}

module.exports = {
  toUserIds: toUserIds,
  toRoleIds: toRoleIds,
  toUserObjects: toUserObjects,
  toRoleObjects: toRoleObjects,
  cleanIds: cleanIds,
  singleModeUserIds: singleModeUserIds,
  missingUserIds: missingUserIds,
  missingRoleIds: missingRoleIds,
  assigneePatch: assigneePatch,
  summarizeNames: summarizeNames
}
