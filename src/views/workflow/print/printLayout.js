/**
 * 打印件的**版式与生效项**纯逻辑（2.0 B2 §3 / §4 / §5）。
 *
 * 为什么把这些判断从 `print/index.vue` 里搬出来：
 *   1. 它们是 AC-62（签批栏出栏）、AC-64（纸张方向与分页）、AC-67（留痕硬门禁）
 *      的落点，必须是**可断言的**——留在 SFC 的 computed 里只能靠人肉点页面；
 *   2. 本工程前端没有单测框架（vue-cli 4 项目，package.json 里没有 jest），
 *      而 Node 能直接 require CommonJS。所以这个文件**刻意写成 CommonJS**
 *      （`module.exports`），既能被 webpack 打进 SFC，也能被
 *      `npm run test:unit`（node tests/run.js）直接跑。
 *      不要改成 ESM —— 改了 Node 就 require 不了，测试会连带失效。
 *
 * 约定：本文件**不依赖 Vue、不碰 DOM、不发请求**，全是纯函数。
 */

/** 纸张尺寸（mm，纵向）。未登记的纸张一律按 A4 处理（"未配置时取默认纸向"）。 */
var PAPER_SIZES = {
  A4: { width: 210, height: 297 },
  A3: { width: 297, height: 420 }
}

/** 版心留白（mm）——与 `print-a4.scss` 的 `@page { margin: 12mm 10mm }` 保持一致 */
var PAGE_MARGIN = { top: 12, bottom: 12, left: 10, right: 10 }

/** mm → 96dpi 像素 */
var PX_PER_MM = 96 / 25.4

/** 默认纸向：未配置纸张或方向时按 A4 纵向渲染（REQ-PRINT-014 的 Scenario） */
var DEFAULT_PAPER = 'A4'
var DEFAULT_ORIENTATION = 'portrait'

function toTrimmedString(v) {
  return v === null || v === undefined ? '' : String(v).trim()
}

/** 纸张归一化：不认识的取值一律 A4 */
function normalizePaper(paper) {
  var p = toTrimmedString(paper).toUpperCase()
  return Object.prototype.hasOwnProperty.call(PAPER_SIZES, p) ? p : DEFAULT_PAPER
}

/** 方向归一化：只认 landscape，其余（含空值/脏值）都是 portrait */
function normalizeOrientation(orientation) {
  return toTrimmedString(orientation).toLowerCase() === 'landscape' ? 'landscape' : DEFAULT_ORIENTATION
}

/**
 * 一次打印的排版参数。
 *
 * @param {object} tpl 生效的打印模板（后端 `printTemplate`），可为空
 * @returns {{paper:string, orientation:string, landscape:boolean,
 *            pageWidthMm:number, pageHeightMm:number, contentHeightPx:number}}
 */
function layoutMetrics(tpl) {
  var t = tpl || {}
  var paper = normalizePaper(t.paper)
  var orientation = normalizeOrientation(t.orientation)
  var size = PAPER_SIZES[paper]
  var landscape = orientation === 'landscape'
  // 横向 = 把纸转 90°：宽高互换
  var pageWidthMm = landscape ? size.height : size.width
  var pageHeightMm = landscape ? size.width : size.height
  var contentHeightMm = pageHeightMm - PAGE_MARGIN.top - PAGE_MARGIN.bottom
  return {
    paper: paper,
    orientation: orientation,
    landscape: landscape,
    pageWidthMm: pageWidthMm,
    pageHeightMm: pageHeightMm,
    contentHeightPx: contentHeightMm * PX_PER_MM
  }
}

/**
 * 页数测算。
 *
 * 刻意保留"按像素测算"的既有路线（PRD 7.5：不依赖 CSS `counter(pages)`，避免浏览器差异），
 * 只是把原先硬编码的 A4 版心高度换成了按纸张与方向算出来的值。
 */
function measurePageCount(contentHeightPx, tpl) {
  var metrics = layoutMetrics(tpl)
  var h = Number(contentHeightPx)
  if (!isFinite(h) || h <= 0) return 1
  return Math.max(1, Math.ceil(h / metrics.contentHeightPx))
}

/**
 * 运行时 `@page` 规则：纸张与方向必须**真的交给浏览器**。
 *
 * `print-a4.scss` 里的 `@page { size: A4 portrait }` 是编译期的静态样式，
 * 而 `scoped` 样式无法按运行时取值改写 —— 所以打印页在 head 里维护一个专用 `<style>`，
 * 每次按生效模板重写它。缺了这一步，"配了 A3 横向"只会改页数测算，纸仍被裁成 A4。
 */
function pageRuleCss(tpl) {
  var m = layoutMetrics(tpl)
  return '@page { size: ' + m.paper + ' ' + m.orientation + '; margin: '
    + PAGE_MARGIN.top + 'mm ' + PAGE_MARGIN.right + 'mm; }'
}

/** 屏幕上的纸样尺寸（宽 × 最小高），跟着纸张与方向走 */
function paperBoxStyle(tpl) {
  var m = layoutMetrics(tpl)
  return { width: m.pageWidthMm + 'mm', minHeight: m.pageHeightMm + 'mm' }
}

/* ==================== 生效项（签批栏 / 附件清单 / 抄送栏） ==================== */

/**
 * 签批栏是否出栏。
 *
 * ⚠ 这里是 AC-62 的落点，也是本次变更**最易漏的一处**：
 *   升级前是 `!isBuiltinTpl && showSignature === '1'`——内置模板被**强制**关掉签批栏，
 *   而《集团资金审批单》的版式核心恰恰就是签批栏（"看着配好了、打出来没有签名区"）。
 *   现在一律只看生效模板的配置值，**与"是否内置"彻底解耦**。
 */
function shouldShowSignature(tpl) {
  return toTrimmedString((tpl || {}).showSignature) === '1'
}

/** 附件清单是否出栏（同上：只看配置值，不看是否内置） */
function shouldShowAttachment(tpl) {
  return toTrimmedString((tpl || {}).showAttachment) === '1'
}

/** 抄送栏是否出栏（REQ-PRINT-014） */
function shouldShowCcNode(tpl) {
  return toTrimmedString((tpl || {}).showCcNode) === '1'
}

/** 处理意见是否打印（既有口径：只有显式 '0' 才关） */
function shouldShowComment(tpl) {
  return toTrimmedString((tpl || {}).showComment) !== '0'
}

/**
 * 内置版式是否**以版式常量为准**排版。
 *
 * - 自定义模板（id 有值）：一律按它的 fieldMap（既有行为）；
 * - 内置版式 `contract`：保持升级前的"按表单 schema 自动排版"——**零回归锚点**。
 *   存量模板取默认值 contract，若改成按 13 行合同映射排版，
 *   那些表单字段（金额/说明…）会从打印件上整片消失；
 * - 内置版式 `fund` / `matter` / `payment`：按版式常量出栏（它们本就是为固定纸质表单定制的栏目）。
 */
function useBuiltinLabelLayout(tpl) {
  var t = tpl || {}
  if (t.id) return false
  var key = toTrimmedString(t.builtinKey)
  return !!key && key !== 'contract'
}

/* ==================== 版式（field_map）解析 ==================== */

function parseFieldMap(raw) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch (e) {
    return null
  }
}

/** 字段表区（有 rows 的 section，含 id/title），保持版式里的顺序 */
function fieldSections(raw) {
  var map = parseFieldMap(raw)
  if (!map || !Array.isArray(map.sections)) return []
  var out = []
  map.sections.forEach(function (sec) {
    if (sec && Array.isArray(sec.rows) && sec.rows.length) {
      out.push({ id: sec.id || '', title: sec.title || '', rows: sec.rows })
    }
  })
  return out
}

/** 版式里的签批栏区（没有就返回 null，由调用方退回"按流程节点动态出栏"） */
function signSection(raw) {
  var map = parseFieldMap(raw)
  if (!map || !Array.isArray(map.sections)) return null
  for (var i = 0; i < map.sections.length; i++) {
    var sec = map.sections[i]
    if (sec && sec.id === 'sign') return sec
  }
  return null
}

/**
 * 签批栏的**固定栏目**（`sign.columns`）。
 *
 * `contract` 没有 columns（保持升级前的动态出栏）；`fund` / `matter` / `payment`
 * 有固定栏目——纸质表单上那几栏是印好的，没匹配到流程节点也要出栏（留空供手写）。
 */
function signColumns(raw) {
  var sec = signSection(raw)
  if (!sec || !Array.isArray(sec.columns)) return []
  return sec.columns.filter(Boolean).map(function (c) {
    return {
      label: toTrimmedString(c.label),
      field: toTrimmedString(c.field),
      match: Array.isArray(c.match) && c.match.length ? c.match.map(toTrimmedString) : [toTrimmedString(c.label)]
    }
  })
}

/** 某一栏签名区应该展示哪些流程节点（按节点名匹配；匹配不到就是空栏） */
function matchNodesForColumn(nodes, column) {
  var list = Array.isArray(nodes) ? nodes : []
  var keys = (column && column.match) || []
  if (!keys.length) return []
  return list.filter(function (n) {
    if (!n) return false
    var name = toTrimmedString(n.nodeName)
    return keys.indexOf(name) >= 0
  })
}

/* ==================== 内置变量 ==================== */

var BUILTIN_FIELDS = [
  '$submitter',
  '$submitterDept',
  '$submitterCompany',
  '$submitTime',
  '$finishTime',
  '$businessNo',
  '$templateName',
  '$instanceStatus'
]

/**
 * 内置变量的取值（`$` 开头）。
 *
 * `$submitterCompany` 是本次新增的：资金/事项两套版式的「审批单位」「报送单位」
 * 按 PRD 附录 A 取**发起人所属公司**（不是所属部门）。
 */
function builtinValue(name, data, formatDate) {
  var d = data || {}
  var fmt = typeof formatDate === 'function' ? formatDate : function (v) { return v ? String(v) : '' }
  switch (name) {
    case '$submitter': return d.submitter || ''
    case '$submitterDept': return d.submitterDept || ''
    case '$submitterCompany': return d.submitterCompany || d.submitterDept || ''
    case '$submitTime': return fmt(d.submitTime)
    case '$finishTime': return fmt(d.finishTime)
    case '$businessNo': return d.businessNo || ''
    case '$templateName': return d.printTemplate ? (d.printTemplate.name || '') : ''
    case '$instanceStatus': return d.instanceStatus || ''
    default: return ''
  }
}

/**
 * Logo 的 `<img src>`。
 *
 * `/common/upload` 返回的是**相对路径**（`/profile/upload/...`），必须加
 * `VUE_APP_BASE_API` 前缀才能取到真实字节；不加前缀那个地址由前端 dev server 接管，
 * 返回的是 SPA 的 HTML（200 但不是图片）。与签名图片同一口径。
 */
function logoUrl(logoFileId, baseApi) {
  var id = toTrimmedString(logoFileId)
  if (!id) return ''
  if (/^https?:\/\//i.test(id) || id.indexOf('data:') === 0) return id
  var prefix = toTrimmedString(baseApi)
  return id.charAt(0) === '/' ? prefix + id : prefix + '/' + id
}

module.exports = {
  PAPER_SIZES: PAPER_SIZES,
  PAGE_MARGIN: PAGE_MARGIN,
  PX_PER_MM: PX_PER_MM,
  DEFAULT_PAPER: DEFAULT_PAPER,
  DEFAULT_ORIENTATION: DEFAULT_ORIENTATION,
  BUILTIN_FIELDS: BUILTIN_FIELDS,
  normalizePaper: normalizePaper,
  normalizeOrientation: normalizeOrientation,
  layoutMetrics: layoutMetrics,
  measurePageCount: measurePageCount,
  pageRuleCss: pageRuleCss,
  paperBoxStyle: paperBoxStyle,
  shouldShowSignature: shouldShowSignature,
  shouldShowAttachment: shouldShowAttachment,
  shouldShowCcNode: shouldShowCcNode,
  shouldShowComment: shouldShowComment,
  useBuiltinLabelLayout: useBuiltinLabelLayout,
  parseFieldMap: parseFieldMap,
  fieldSections: fieldSections,
  signSection: signSection,
  signColumns: signColumns,
  matchNodesForColumn: matchNodesForColumn,
  builtinValue: builtinValue,
  logoUrl: logoUrl
}
