/**
 * 进销存前端（B4 §8）的**冻结常量**——全仓只有这一份，页面/壳/接口层都从这里取。
 *
 * 为什么单独一个文件：
 *   ① 日期格式串与消息 API 是"Element Plus → Element UI"改写清单里最容易漏、且**漏了不报错**的两项
 *      （全大写的年份记号在 Element UI 里会被当成别的语义 → 日期静默为空；`ElMessage` 直接是 undefined）。
 *      把日期格式串收成一个常量，页面里只允许 `:value-format="DATE_FORMAT"`，
 *      再配一条门禁（`tools/audit` + `tests/erp-shell.test.js`）就再也漏不掉。
 *   ② 权限点前缀与 URL 前缀是"前端壳 / 后端控制器 / 菜单 SQL（任务 8.1）"三方的公共契约，
 *      写散必然漂移（DEV-ENV §6.50 同源教训）。
 *
 * 冻结出处：
 *   · 权限点命名 → PRD §7.5「格式 `<域>:<资源>:<动作>`，全小写、多词资源用连字符」，
 *     示例 `pur:order:approve`、`stk:in-order:unapprove`；新域前缀 `ctms:/pur:/sal:/stk:`。
 *   · URL 前缀 → PRD §9.4 第 2 条「`/api/purchase/orders` → `/pur/order`；`/api/stock/in-orders`
 *     → `/stk/in-order`；保持资源名单数」。
 *   · 状态/动作/状态机 → 后端 `com.ruoyi.ctms.erp.base.ErpDocStatus/ErpDocAction/ErpDocStateMachine`
 *     （逐字镜像，见 `doc-rules.js`）。
 *   · 主数据权限点 → **沿用 B3 已交付的控制器**（`CtmsProductMasterController` 的
 *     `@PreAuthorize("@ss.hasPermi('ctms:partner:*')")`）。真源是注解：菜单侧必须用同一批点，
 *     否则"按钮看得见、点了 403"。
 *
 * 写法：**CommonJS**（与 `src/utils/formSchema.js` / `src/views/workflow/simple-flow/flowKey.js` 同口径），
 * 这样纯逻辑能被 `tests/*.test.js` 用 Node 直接 require，不引入 jest。
 *
 * @author 二开
 */

/** Element UI 日期格式串（**必须小写**；全大写的日期记号是该版本不认的写法，会静默拿到空值）。 */
var DATE_FORMAT = 'yyyy-MM-dd'

/** Element UI 日期时间格式串（同上，小写）。 */
var DATETIME_FORMAT = 'yyyy-MM-dd HH:mm:ss'

/** 主数据档案的权限点前缀：与 `CtmsProductMasterController` 的注解逐字一致。 */
var MASTER_PERM_PREFIX = 'ctms:partner'

/**
 * 单据动作 → 权限点**动作段**的映射（真源 = 已落地 Controller 的 `@PreAuthorize`）。
 *
 * 与参考仓库的差异（2026-10-05 队长裁决，见 notes/09b §2-D3）：
 *   · `reject` 复用 `approve`（与参考仓库、落地实现一致：驳回没有独立权限点）；
 *   · **`complete` 用 `status`**（落地的采购/销售控制器就是 `:status`；菜单侧由 t8 补 F 行）。
 *     ⚠ 不要把 `complete` 改回 `approve`，否则与控制器逐字对不上（403）。
 *   · 库存类单据**没有** `complete`（状态机显式拒绝），所以不会用到 `:status`。
 */
var ACTION_PERM_SEGMENT = {
  list: 'list',
  query: 'query',
  add: 'add',
  edit: 'edit',
  remove: 'remove',
  export: 'export',
  print: 'print',
  submit: 'submit',
  approve: 'approve',
  reject: 'approve',
  complete: 'status',
  void: 'void',
  unapprove: 'unapprove'
}

/** 主数据的动作清单（启停用走 `edit`：B3 控制器没有 `/status` 端点，见 notes §主数据）。 */
var MASTER_ACTIONS = ['list', 'query', 'add', 'edit', 'remove']

/** 列表分页默认值（RuoYi `BaseController.startPage` 缺参时是 1/10，下拉要显式给大 pageSize）。 */
var PAGE_SIZE = 10

/** 下拉/选项类查询的 pageSize（一次取全启用项）。 */
var OPTION_PAGE_SIZE = 500

/** 启用标志（与 `t_ctms_*` 的 `enable_flag` 一致）。 */
var ENABLE_FLAG_ON = '1'
var ENABLE_FLAG_OFF = '0'

module.exports = {
  DATE_FORMAT: DATE_FORMAT,
  DATETIME_FORMAT: DATETIME_FORMAT,
  MASTER_PERM_PREFIX: MASTER_PERM_PREFIX,
  ACTION_PERM_SEGMENT: ACTION_PERM_SEGMENT,
  MASTER_ACTIONS: MASTER_ACTIONS,
  PAGE_SIZE: PAGE_SIZE,
  OPTION_PAGE_SIZE: OPTION_PAGE_SIZE,
  ENABLE_FLAG_ON: ENABLE_FLAG_ON,
  ENABLE_FLAG_OFF: ENABLE_FLAG_OFF
}
