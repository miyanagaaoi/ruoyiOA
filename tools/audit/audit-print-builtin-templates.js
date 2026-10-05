/**
 * 审计「内置打印版式按单据类型选用 + 版式常量收口」（2.0 B2 §1/§2/§3/§4/§5，AC-61～AC-67）。
 *
 * 为什么需要它：这次变更的判据几乎全是**结构性**的，而且每一处漏掉都表现为
 * "能用但不对"，人眼极难发现：
 *   - `builtinTemplate` 不接版式 key → 又回到"全局唯一一套版式"；
 *   - 少了 `t_template.builtin_print_key` 的登记（实体/Mapper 任一处）→ 保存后**静默丢字段**；
 *   - 前端又复制一份内置版式常量 → 地雷 D-4 复活（REQ-PRINT-013 明令禁止）；
 *   - 客户端仍按"是否内置"强制关掉签批栏 → 资金审批单又没有签名区（AC-62）；
 *   - `doPrint()` 又变回"先打印后补留痕" → 留痕硬门禁失效（AC-67）；
 *   - `PrintData.formSchema` 死字段复活 → 契约噪声（REQ-PRINT-013）。
 *
 * 判据（每条命中即失败）：
 *   P1  内置版式真源存在且是 4 套（BuiltinPrintTemplates：keys/titleOf/fieldMapOf + 4 个 key）
 *   P2  contract 的标题与升级前逐字一致（零回归锚点）
 *   P3  `builtinTemplate` 接收版式 key；`getEffectiveTemplate` 读 `builtin_print_key`
 *   P4  `t_template.builtin_print_key` 的**五处同步**齐备（实体 + Mapper 的 resultMap/select/insert/update）
 *   P5  两个读接口存在且权限点复用 `workflow:print:template`，**未新增权限点**
 *   P6  前端**没有**内置版式常量副本（REQ-PRINT-013 / 地雷 D-4）
 *   P7  签批栏与"是否内置"解耦（不再有 `!isBuiltinTpl && showSignature`）
 *   P8  留痕硬门禁：`doPrint` 走 printAfterLogging，且 writeLog 不吞异常
 *   P9  `PrintData` 无死字段 formSchema
 *   P10 打印入口 4 个；纸张/方向/Logo/抄送栏链路都在
 *   P11 未新建任何打印模板表 / 打印日志表（platform/module-boundary）
 *
 * 用法：
 *   node tools/audit/audit-print-builtin-templates.js [仓库根] [输出JSON]
 *   node tools/audit/audit-print-builtin-templates.js --selftest
 */
const fs = require('fs')
const path = require('path')

const DEFAULT_ROOT = 'F:/dsh/ruoyiOA'

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')

  // 阳性样本：各处都按 2.0 B2 落地后的形态写（应 0 命中）
  const goodFiles = {
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/support/BuiltinPrintTemplates.java': `
      public static final String DEFAULT_KEY = "contract";
      titles.put("contract", "集团合同类文件流转审批单");
      titles.put("fund", "集团资金审批单");
      titles.put("matter", "集团事项类打印审批单");
      titles.put("payment", "付款申请单");
      SHOW_SIGNATURE.put("contract", "1");
      public static List<String> keys() { return null; }
      public static String titleOf(String key) { return null; }
      public static String fieldMapOf(String key) { return null; }
      public static String showSignatureOf(String key) { return null; }
      public static boolean isValidKey(String key) { return false; }
      `,
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/service/impl/PrintServiceImpl.java': `
      private PrintTemplate builtinTemplate(String templateId, String builtinKey) {
        BuiltinPrintTemplates.Resolution r = BuiltinPrintTemplates.resolve(builtinKey, log::warn);
        t.setBuiltinKey(r.getKey());
        t.setShowSignature(r.getShowSignature());
      }
      public PrintTemplate getEffectiveTemplate(String templateId, String printTplId) {
        if (StringUtils.isNotBlank(printTplId)) { printTemplateMapper.selectById(printTplId); }
        if (StringUtils.isNotBlank(templateId)) { printTemplateMapper.selectByTemplateId(templateId); }
        return builtinTemplate(templateId, lookupBuiltinKey(templateId));
      }
      private String lookupBuiltinKey(String templateId) {
        return printTemplateMapper.selectBuiltinPrintKeyByTemplateId(templateId);
      }
      `,
    'ruoyi-vue-oa-master/ruoyi-template/src/main/java/com/ruoyi/template/domain/Template.java': `
      private String builtinPrintKey;
      `,
    'ruoyi-vue-oa-master/ruoyi-template/src/main/resources/mapper/template/TemplateMapper.xml': `
      <result property="builtinPrintKey" column="builtin_print_key" />
      <sql id="selectTemplateVo">
        select icon, builtin_print_key, from t_template
      </sql>
      <if test="builtinPrintKey != null and builtinPrintKey != ''">builtin_print_key,</if>
      <if test="builtinPrintKey != null and builtinPrintKey != ''">#{builtinPrintKey},</if>
      <if test="builtinPrintKey != null and builtinPrintKey != ''">builtin_print_key = #{builtinPrintKey},</if>
      `,
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/controller/WorkflowPrintController.java': `
      @GetMapping("/builtinTemplates")
      @PreAuthorize("@ss.hasPermi('workflow:print:template')")
      public AjaxResult builtinTemplates() { return null; }
      @GetMapping("/defaultFieldMap/{builtinKey}")
      @PreAuthorize("@ss.hasPermi('workflow:print:template')")
      public AjaxResult defaultFieldMap() { return null; }
      `,
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/model/PrintData.java': `
      private Object formData;
      private String submitterCompany;
      private List<PrintCcNode> ccNodes;
      `,
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/service/impl/PrintServiceImpl.java.doPrint': '',
    'ruoyi-vue-oa-ui-master/src/views/workflow/print/index.vue': `
<template><div class="print-page"><div class="p-headline"><img v-if="logoUrl" :src="logoUrl" /></div>
<table class="p-base"><tr v-for="(sec, si) in fieldSections"><td :colspan="cellSpan(cell)">{{ cellValue(cell) }}</td></tr></table>
<div v-if="showCcNode && ccRows.length">抄送</div></div></template>
<script>
import printLayout from './printLayout'
import printGate from './printGate'
export default {
  computed: {
    pageHint() { return printLayout.layoutMetrics(this.tpl).paper },
    showSignature() { return printLayout.shouldShowSignature(this.tpl) },
    showAttachment() { return printLayout.shouldShowAttachment(this.tpl) },
    showCcNode() { return printLayout.shouldShowCcNode(this.tpl) },
    logoUrl() { return printLayout.logoUrl(this.tpl.logoFileId, process.env.VUE_APP_BASE_API) },
    fieldSections() { return printLayout.useBuiltinLabelLayout(this.tpl) },
    paperBoxStyle() { return printLayout.paperBoxStyle(this.tpl) }
  },
  methods: {
    doPrint() {
      printGate.printAfterLogging({ writeLog: () => this.writeLog(), print: () => window.print() })
    },
    writeLog() { return addPrintLog({ businessId: this.businessId }) },
    measurePages() { this.pageCount = printLayout.measurePageCount(paper.scrollHeight, this.tpl) },
    applyPageRule() { this.rule = printLayout.pageRuleCss(this.tpl) }
  }
}
</script>`,
    'ruoyi-vue-oa-ui-master/src/views/workflow/print/printLayout.js': `
      module.exports = { shouldShowSignature: function () {}, layoutMetrics: function () {},
        useBuiltinLabelLayout: function () {}, paperBoxStyle: function () {}, pageRuleCss: function () {} }
      `,
    'ruoyi-vue-oa-ui-master/src/views/workflow/print/printGate.js': `
      var PRINT_LOG_FAILED_MESSAGE = '打印留痕写入失败，请联系管理员'
      function printAfterLogging(opts) {}
      module.exports = { printAfterLogging: printAfterLogging, PRINT_LOG_FAILED_MESSAGE: PRINT_LOG_FAILED_MESSAGE }
      `,
    'ruoyi-vue-oa-ui-master/src/views/workflow/print-template/index.vue': `
<template><div><el-radio-group v-model="mode"><el-radio-button label="builtin" /><el-radio-button label="custom" /></el-radio-group>
<image-upload v-model="form.logoFileId" /></div></template>
<script>
import { listBuiltinPrintTemplates, getBuiltinFieldMap, saveBuiltinPrintKey } from '@/api/workflow/print'
import ImageUpload from '@/components/ImageUpload'
export default {
  data() { return { mode: 'builtin', builtinKey: 'contract', builtinTemplates: [], fillKey: 'contract' } },
  methods: {
    fillDefaultMap(key) { getBuiltinFieldMap(this.fillKey).then(res => { this.form.fieldMap = JSON.stringify(res.data) }) },
    previewBuiltin() { return getBuiltinFieldMap(this.builtinKey) },
    saveBuiltinKey() { return saveBuiltinPrintKey(this.queryTemplateId, this.builtinKey) }
  }
}
</script>`,
    'ruoyi-vue-oa-ui-master/src/api/workflow/print.js': `
export function listBuiltinPrintTemplates() { return request({ url: '/workflow/print/builtinTemplates', method: 'get' }) }
export function getBuiltinFieldMap(builtinKey) { return request({ url: '/workflow/print/defaultFieldMap/' + builtinKey, method: 'get' }) }
export function saveBuiltinPrintKey(templateId, builtinKey) { return request({ url: '/workflow/print/builtinKey', method: 'post' }) }
`,
    'ruoyi-vue-oa-ui-master/src/views/workflow/todo/group-table.vue': `
<el-button size="mini" type="text" icon="el-icon-printer" @click.stop="printRow(scope.row)">打印</el-button>
<script>export default { methods: { printRow(row) { this.$openPrintPreview(row.businessId) } } }</script>`,
    'ruoyi-vue-oa-ui-master/src/views/workflow/done/index.vue': `
<el-button @click.stop="printRow(scope.row)">打印</el-button>
<script>export default { methods: { printRow(row) { this.$openPrintPreview(row.businessId) } } }</script>`,
    'ruoyi-vue-oa-ui-master/src/views/workflow/my-draft/index.vue': `
<script>export default { methods: { printRow(row) { this.$openPrintPreview(row.bizId) } } }</script>`,
    'ruoyi-vue-oa-ui-master/src/views/workflow/flow-form/index.vue': `
<script>export default { methods: { printRow() { this.$openPrintPreview(this.businessId) } } }</script>`,
    'ruoyi-vue-oa-master/sql/二开-2.0-B2-打印模板内置版式.sql': `
-- 只改 t_template 加列与默认值，**不新建表**
ALTER TABLE \`t_template\` ADD COLUMN \`builtin_print_key\` varchar(32) DEFAULT 'contract';
ALTER TABLE \`t_template_print_template\` MODIFY COLUMN \`show_signature\` char(1) DEFAULT '0';
`,
    'ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/model/BuiltinTemplateOption.java': `
      private String key;
      private String name;
      private String showSignature;
      private String showAttachment;
      `
  }

  // 阴性样本：把每个判据要抓的形态都还原回去
  const badFiles = JSON.parse(JSON.stringify(goodFiles))
  badFiles['ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/support/BuiltinPrintTemplates.java'] = `
      private static final String DEFAULT_TITLE = "集团合同类文件流转审批单";
      private static final String DEFAULT_FIELD_MAP = buildDefaultFieldMap();
      `
  badFiles['ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/service/impl/PrintServiceImpl.java'] = `
      private PrintTemplate builtinTemplate(String templateId) { return null; }
      public PrintTemplate getEffectiveTemplate(String templateId, String printTplId) {
        return builtinTemplate(templateId);
      }
      `
  badFiles['ruoyi-vue-oa-master/ruoyi-template/src/main/resources/mapper/template/TemplateMapper.xml'] = `
      <result property="submitScopeType" column="submit_scope_type" />
      `
  badFiles['ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/controller/WorkflowPrintController.java'] = `
      @GetMapping("/template/{templateId}")
      @PreAuthorize("@ss.hasPermi('workflow:print:builtin:list')")
      public AjaxResult template() { return null; }
      `
  badFiles['ruoyi-vue-oa-master/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/model/PrintData.java'] = `
      private Object formData;
      private Object formSchema;
      `
  badFiles['ruoyi-vue-oa-ui-master/src/views/workflow/print/index.vue'] = `
<template><div class="print-page"><table class="p-base"></table></div></template>
<script>
const DEFAULT_FIELD_MAP = JSON.stringify({ sections: [{ id: 'base', rows: [
  { cells: [{ label: '合同编号', field: 'contractNo', span: 3 }] },
  { cells: [{ label: '合同全称', field: 'contractName', span: 3 }] }] }] })
const A4_CONTENT_PX = ((297 - 24) / 25.4) * 96
export default {
  computed: {
    isBuiltinTpl() { return !this.tpl.id },
    showSignature() { return !this.isBuiltinTpl && this.tpl.showSignature === '1' },
    showAttachment() { return !this.isBuiltinTpl && this.tpl.showAttachment === '1' }
  },
  methods: {
    doPrint() { this.measurePages(); window.print(); this.writeLog() },
    writeLog() { addPrintLog({}).catch(() => { this.$modal.msgWarning('打印留痕写入失败，请联系管理员') }) },
    measurePages() { this.pageCount = Math.max(1, Math.ceil(h / A4_CONTENT_PX)) }
  }
}
</script>`
  badFiles['ruoyi-vue-oa-ui-master/src/views/workflow/print-template/index.vue'] = `
<template><div><el-select v-model="form.paper" /></div></template>
<script>
const DEFAULT_FIELD_MAP = JSON.stringify({ sections: [] })
export default { methods: { fillDefaultMap() { this.form.fieldMap = DEFAULT_FIELD_MAP } } }
</script>`
  badFiles['ruoyi-vue-oa-ui-master/src/views/workflow/todo/group-table.vue'] = `
<el-table><el-table-column label="发送时间" /></el-table>
<script>export default { methods: { handleRowClick(row) {} } }</script>`
  badFiles['ruoyi-vue-oa-master/sql/二开-2.0-B2-打印模板内置版式.sql'] = `
CREATE TABLE \`t_print_builtin_template\` (\`id\` varchar(64) NOT NULL);
CREATE TABLE \`t_print_builtin_log\` (\`id\` varchar(64) NOT NULL);
`

  const goodDir = makeFixtureDir(goodFiles)
  const good = runSelfOn(__filename, goodDir)
  const goodHits = Array.isArray(good.json) ? good.json : []
  const badDir = makeFixtureDir(badFiles)
  const bad = runSelfOn(__filename, badDir)
  const badHits = Array.isArray(bad.json) ? bad.json : []
  const badRules = [...new Set(badHits.map(h => h.rule))]

  const want = ['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7', 'P8', 'P9', 'P10', 'P11']
  finish('audit-print-builtin-templates', badDir, [
    { label: '脚本能跑通（阳性样本）', pass: bad.code === 0, detail: bad.code === 0 ? '' : bad.out.slice(0, 300) },
    {
      label: '阴性样本 0 命中',
      pass: goodHits.length === 0,
      detail: '实际 ' + goodHits.length + '：' + goodHits.map(h => h.rule + '/' + h.detail).join(' , ')
    }
  ].concat(want.map(r => ({
    label: `抓到 ${r}`,
    pass: badRules.includes(r),
    detail: badRules.includes(r) ? '' : '命中规则：' + badRules.join(',')
  }))))
}

const ROOT = process.argv[2] || DEFAULT_ROOT
const OUT = process.argv[3] || 'F:/dsh/ruoyiOA/.cache/audit-print-builtin-templates.json'

const BE = path.join(ROOT, 'ruoyi-vue-oa-master')
const UI = path.join(ROOT, 'ruoyi-vue-oa-ui-master')
const hits = []

const read = f => { try { return fs.readFileSync(f, 'utf8') } catch { return '' } }
const rel = f => path.relative(ROOT, f).replace(/\\/g, '/')
const hit = (rule, file, detail) => hits.push({ rule, file: rel(file), detail })

/** 去掉注释，避免"注释里提到旧写法"被判成没删 */
function stripComments(src) {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/[^\n]*$/gm, '')
    .replace(/\/\/[^\n]*/g, '')
}

const builtinJava = path.join(BE, 'ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/support/BuiltinPrintTemplates.java')
const printImpl = path.join(BE, 'ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/service/impl/PrintServiceImpl.java')
const printData = path.join(BE, 'ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/model/PrintData.java')
const printCtrl = path.join(BE, 'ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/controller/WorkflowPrintController.java')
const templateJava = path.join(BE, 'ruoyi-template/src/main/java/com/ruoyi/template/domain/Template.java')
const templateMapperXml = path.join(BE, 'ruoyi-template/src/main/resources/mapper/template/TemplateMapper.xml')
const printPage = path.join(UI, 'src/views/workflow/print/index.vue')
const printLayoutJs = path.join(UI, 'src/views/workflow/print/printLayout.js')
const printGateJs = path.join(UI, 'src/views/workflow/print/printGate.js')
const configPage = path.join(UI, 'src/views/workflow/print-template/index.vue')
const printApi = path.join(UI, 'src/api/workflow/print.js')

/* ---------- P1 / P2 内置版式真源 ---------- */
const builtinSrc = stripComments(read(builtinJava))
if (!builtinSrc) {
  hit('P1', builtinJava, '找不到内置版式真源 BuiltinPrintTemplates.java')
} else {
  ;['contract', 'fund', 'matter', 'payment'].forEach(k => {
    if (!new RegExp('"' + k + '"').test(builtinSrc)) hit('P1', builtinJava, `缺少版式键 ${k}`)
  })
  if (!/public\s+static\s+List<String>\s+keys\s*\(/.test(builtinSrc)) hit('P1', builtinJava, '缺少 keys()')
  if (!/titleOf\s*\(/.test(builtinSrc)) hit('P1', builtinJava, '缺少 titleOf()')
  if (!/fieldMapOf\s*\(/.test(builtinSrc)) hit('P1', builtinJava, '缺少 fieldMapOf()')
  if (!/isValidKey\s*\(/.test(builtinSrc)) hit('P1', builtinJava, '缺少 isValidKey()（保存入口要靠它拒绝非法 key）')
  // P2：contract 的标题必须与升级前逐字一致（零回归锚点）
  if (!/titles\.put\("contract",\s*"集团合同类文件流转审批单"\)/.test(builtinSrc)) {
    hit('P2', builtinJava, 'contract 的标题不是升级前的「集团合同类文件流转审批单」（零回归锚点被破坏）')
  }
  // 四套版式都要自带签批栏推荐值（否则回退内置时又被 fillDefaults 填成关闭 → AC-62 失效）
  if (!/SHOW_SIGNATURE/.test(builtinSrc) || !/showSignatureOf\s*\(/.test(builtinSrc)) {
    hit('P1', builtinJava, '内置版式没有自带签批栏推荐取值（AC-62 会失效）')
  }
}

/* ---------- P3 有效模板判定读列并传 key ---------- */
const implSrc = stripComments(read(printImpl))
if (!implSrc) {
  hit('P3', printImpl, '找不到 PrintServiceImpl.java')
} else {
  if (!/builtinTemplate\s*\(\s*String\s+\w+\s*,\s*String\s+\w+\s*\)/.test(implSrc)) {
    hit('P3', printImpl, 'builtinTemplate 没有接收版式 key 入参（又回到"全局唯一一套版式"）')
  }
  if (/\bbuiltinTemplate\s*\(\s*templateId\s*\)/.test(implSrc)) {
    hit('P3', printImpl, '仍残留旧签名调用 builtinTemplate(templateId)')
  }
  if (!/selectBuiltinPrintKeyByTemplateId/.test(implSrc)) {
    hit('P3', printImpl, '有效模板判定没有读取 t_template.builtin_print_key')
  }
  if (/\bDEFAULT_TITLE\b/.test(implSrc)) hit('P3', printImpl, '旧的单一标题常量 DEFAULT_TITLE 仍在（应改为按 key 的注册表）')
  if (/\bDEFAULT_FIELD_MAP\b/.test(implSrc)) hit('P3', printImpl, '旧的单一版式常量 DEFAULT_FIELD_MAP 仍在（应移到 BuiltinPrintTemplates）')
  // 优先级链的字面顺序仍是 printTplId → 启用行 → 内置
  const order = ['selectById(printTplId)', 'selectByTemplateId(templateId)', 'builtinTemplate(']
  let cursor = -1
  let chainOk = true
  order.forEach(token => {
    const idx = implSrc.indexOf(token, cursor + 1)
    if (idx < 0 || idx < cursor) chainOk = false
    cursor = idx
  })
  if (!chainOk) hit('P3', printImpl, '优先级链的顺序被打乱（应为 显式 printTplId > 启用行 > 内置）')
  // 内置分支要先套装版式自带的推荐取值再 fillDefaults（AC-62 的服务端落点）
  if (!/setShowSignature\s*\(/.test(implSrc)) {
    hit('P3', printImpl, '内置版式没有套用自带的签批栏推荐取值（fund 会打不出签名区）')
  }
}

/* ---------- P4 五处同步 ---------- */
const tplJavaSrc = read(templateJava)
if (!/private\s+String\s+builtinPrintKey\s*;/.test(tplJavaSrc)) {
  hit('P4', templateJava, '实体缺少 builtinPrintKey 字段')
}
const tplMapperSrc = read(templateMapperXml)
if (!tplMapperSrc) {
  hit('P4', templateMapperXml, '找不到 TemplateMapper.xml')
} else {
  if (!/<result[^>]*property="builtinPrintKey"[^>]*column="builtin_print_key"/.test(tplMapperSrc)) {
    hit('P4', templateMapperXml, 'resultMap 未登记 builtin_print_key')
  }
  const selectHead = tplMapperSrc.match(/<sql[^>]*id="selectTemplateVo"[\s\S]*?<\/sql>/)
  if (!selectHead || !/builtin_print_key/.test(selectHead[0])) {
    hit('P4', templateMapperXml, 'select 列未包含 builtin_print_key（查出来恒为 null → 静默丢字段）')
  }
  if (!/<if[^>]*builtinPrintKey[^>]*>builtin_print_key,<\/if>/.test(tplMapperSrc)) {
    hit('P4', templateMapperXml, 'insert 列未登记 builtin_print_key')
  }
  if (!/<if[^>]*builtinPrintKey[^>]*>builtin_print_key\s*=\s*#\{builtinPrintKey\}/.test(tplMapperSrc)) {
    hit('P4', templateMapperXml, 'update 列未登记 builtin_print_key')
  }
}

/* ---------- P5 两个读接口 + 不新增权限点 ---------- */
const ctrlSrc = stripComments(read(printCtrl))
if (!ctrlSrc) {
  hit('P5', printCtrl, '找不到 WorkflowPrintController.java')
} else {
  if (!/GetMapping\("\/builtinTemplates"\)/.test(ctrlSrc)) hit('P5', printCtrl, '缺少 GET /workflow/print/builtinTemplates')
  if (!/GetMapping\("\/defaultFieldMap\/\{builtinKey\}"\)/.test(ctrlSrc)) {
    hit('P5', printCtrl, '缺少 GET /workflow/print/defaultFieldMap/{builtinKey}')
  }
  const points = (ctrlSrc.match(/hasPermi\('([^']+)'\)/g) || []).map(s => s.replace(/hasPermi\('|'\)/g, ''))
  const allowed = ['workflow:print:template', 'workflow:print:template:edit', 'workflow:print:template:remove']
  const extra = [...new Set(points.filter(p => allowed.indexOf(p) < 0))]
  if (extra.length) hit('P5', printCtrl, '新增了权限点（design D10 要求不新增）：' + extra.join('、'))
  // 两个新读接口必须复用已存在的读权限点
  const newReads = ctrlSrc.match(/GetMapping\("\/builtinTemplates"\)[\s\S]{0,200}?hasPermi\('([^']+)'\)/)
  if (!newReads || newReads[1] !== 'workflow:print:template') {
    hit('P5', printCtrl, 'builtinTemplates 没有复用权限点 workflow:print:template')
  }
}

/* ---------- P6 前端不得持有内置版式常量副本 ---------- */
const configSrc = stripComments(read(configPage))
const pageSrc = stripComments(read(printPage))
if (!configSrc) {
  hit('P6', configPage, '找不到 print-template/index.vue')
} else {
  if (/DEFAULT_FIELD_MAP/.test(configSrc)) hit('P6', configPage, '又出现客户端副本 DEFAULT_FIELD_MAP（地雷 D-4 复活）')
  if (/getBuiltinFieldMap/.test(configSrc) === false) {
    hit('P6', configPage, '"填入内置版式"没有改走后端接口')
  }
  // 客户端不得硬编码合同版式的栏目/字段配对
  if (/合同签订主体-甲方/.test(configSrc) && /ourCompany/.test(configSrc)) {
    hit('P6', configPage, '客户端仍硬编码合同版式的栏目与字段（应改为后端返回）')
  }
}
if (/DEFAULT_FIELD_MAP/.test(pageSrc)) hit('P6', printPage, '打印页出现内置版式常量副本')
const apiSrc = read(printApi)
if (!/defaultFieldMap/.test(apiSrc)) hit('P6', printApi, 'api/workflow/print.js 没有内置版式映射接口')

/* ---------- P7 签批栏与"是否内置"解耦 ---------- */
if (!pageSrc) {
  hit('P7', printPage, '找不到打印页 index.vue')
} else {
  if (/!\s*this\.isBuiltinTpl\s*&&/.test(pageSrc)) {
    hit('P7', printPage, '仍在用 isBuiltinTpl 参与判定（内置模板又会被强制关掉签批栏 → AC-62 失效）')
  }
  if (!/shouldShowSignature/.test(pageSrc)) hit('P7', printPage, '签批栏判定没有走统一的 printLayout.shouldShowSignature')
  if (!/shouldShowAttachment/.test(pageSrc)) hit('P7', printPage, '附件清单判定没有走统一的 printLayout.shouldShowAttachment')
  if (!/useBuiltinLabelLayout/.test(pageSrc)) hit('P7', printPage, '缺少内置版式的排版路径判定（useBuiltinLabelLayout）')
}

/* ---------- P8 留痕硬门禁 ---------- */
const gateSrc = read(printGateJs)
if (!gateSrc) {
  hit('P8', printGateJs, '找不到 printGate.js（留痕硬门禁被抽掉的逻辑）')
} else {
  if (!/printAfterLogging/.test(gateSrc)) hit('P8', printGateJs, '缺少先写留痕后打印的门禁实现')
  if (!/联系管理员/.test(gateSrc)) hit('P8', printGateJs, '失败提示没有说明"联系管理员"（AC-67）')
}
if (pageSrc) {
  if (!/printGate/.test(pageSrc) || !/printAfterLogging/.test(pageSrc)) {
    hit('P8', printPage, 'doPrint 没有走留痕硬门禁')
  }
  // 升级前的形态：window.print() 出现在写留痕之前
  const printIdx = pageSrc.indexOf('window.print()')
  const logIdx = pageSrc.indexOf('printAfterLogging')
  if (printIdx >= 0 && (logIdx < 0 || printIdx < logIdx)) {
    hit('P8', printPage, '仍是"先打印后写留痕"（window.print() 出现在门禁之前）')
  }
  // writeLog 里不能把失败吞掉：那等于退回"尽力而为"
  const writeLogBody = pageSrc.match(/writeLog\s*\(\s*\)\s*\{[\s\S]{0,600}?\n\s{4}\}/)
  if (writeLogBody && /\.catch\s*\(/.test(writeLogBody[0])) {
    hit('P8', printPage, 'writeLog 内部 catch 掉了失败（阻断打印的信号被吞掉）')
  }
  if (!/measurePages/.test(pageSrc)) hit('P10', printPage, '缺少页数测算')
  if (!/logoUrl/.test(pageSrc)) hit('P10', printPage, '缺少 Logo 输出（AC-65）')
  if (!/ccRows/.test(pageSrc)) hit('P10', printPage, '缺少抄送栏（AC-64）')
  // 纸张与方向要**真的生效**：屏幕纸样 + 运行时 @page 规则（只改页数测算是不够的）
  if (!/paperBoxStyle/.test(pageSrc)) hit('P10', printPage, '纸样尺寸没有跟着生效模板的纸张/方向走')
  if (!/pageRuleCss/.test(pageSrc)) hit('P10', printPage, '没有按生效模板重写 @page 规则（配了 A3 横向也会被裁成 A4）')
}

/* ---------- P9 死字段 ---------- */
const dataSrc = stripComments(read(printData))
if (!dataSrc) {
  hit('P9', printData, '找不到 PrintData.java')
} else if (/\bformSchema\b/.test(dataSrc)) {
  hit('P9', printData, '死字段 formSchema 仍在（REQ-PRINT-013 要求删除）')
}

/* ---------- P10 打印入口 4 个 ---------- */
const entries = [
  ['详情页', path.join(UI, 'src/views/workflow/flow-form/index.vue')],
  ['待办列表行', path.join(UI, 'src/views/workflow/todo/group-table.vue')],
  ['已办列表行', path.join(UI, 'src/views/workflow/done/index.vue')],
  ['我起草列表行', path.join(UI, 'src/views/workflow/my-draft/index.vue')]
]
entries.forEach(([name, file]) => {
  const src = read(file)
  if (!src) hit('P10', file, name + ' 的源文件不存在')
  else if (!/\$openPrintPreview\s*\(/.test(src)) hit('P10', file, name + ' 缺少打印入口')
})

/* ---------- P11 不得新建打印模板表 / 打印日志表 ---------- */
function sqlFiles(dir) {
  try {
    return fs.readdirSync(dir).filter(f => f.endsWith('.sql')).map(f => path.join(dir, f))
  } catch {
    return []
  }
}
sqlFiles(path.join(BE, 'sql')).forEach(f => {
  // ⚠ 必须先去 SQL 注释：编排文件里有一句"…CREATE TABLE IF NOT EXISTS，可反复执行"
  //    的中文说明，不剥注释会把它当建表语句（踩过：一条假阳性）。
  const src = read(f).replace(/^\s*--[^\n]*$/gm, '').replace(/--[^\n]*/g, '')
  const creates = src.match(/CREATE\s+TABLE[^(]*/gi) || []
  creates.forEach(c => {
    if (/print/i.test(c) && !/t_template_print_template|t_print_log/.test(c)) {
      hit('P11', f, '疑似新建了打印相关的表：' + c.trim())
    }
  })
})

console.log(`内置打印版式审计：共 ${hits.length} 处问题\n`)
hits.forEach(h => console.log(`  [${h.rule}] ${h.file}  ${h.detail}`))
fs.writeFileSync(OUT, JSON.stringify(hits, null, 2), 'utf8')
