/**
 * 审计「模板四页签重构」（2.0 B1 §5/§6，AC-45..AC-47）。
 *
 * 为什么需要它：这次重构的核心判据都是**结构性**的，靠人眼漏一处就退化成升级前的形态：
 *   - 少了第 4 个页签 → 「更多设置」里的策略项没地方放；
 *   - 少了一次性 `Promise.all` 到**逐页签校验**的替换 → 隐藏页签里的错误又看不见了；
 *   - `activeNames` / `activeName` 死字段没删 → 代码里有第二个"当前页签"来源，切换会打架；
 *   - 设计器仍只挂在独立路由 → 页签 3 还是跳出去，绑定/回写链路就断了。
 *
 * 判据（每条命中即失败）：
 *   T1 add.vue 有 `el-tabs` 容器，且**恰好 4 个** `el-tab-pane`，label 是基础信息/表单设计/流程设计/更多设置
 *   T2 逐页签校验入口存在（validatableTabs + 逐个 validate + 失败切页签 + 定位首错）
 *   T3 死字段已删：add.vue 里不出现 activeNames / activeName
 *   T4 设计器已内嵌：add.vue 引用了 FlowDesigner 且带 `embedded`
 *   T5 内嵌组件隐藏了流程名称/关联表单输入（FlowDesigner.vue 里有 embedded 分支与只读展示）
 *   T6 可发起范围与流程管理员的字段在基础信息页签里（submitScopeType / submitScope / flowAdmins）
 *
 * 用法：
 *   node tools/audit/audit-template-tabs.js [前端 src] [输出JSON]
 *   node tools/audit/audit-template-tabs.js --selftest
 */
const fs = require('fs')
const path = require('path')

const DEFAULT_SRC = 'F:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')

  const goodFiles = {
    'views/workflow/template/add.vue': `
<template>
  <el-tabs v-model="activeTab">
    <el-tab-pane label="基础信息" name="basic"><BasicInfo ref="basicInfoForm" :info="baseInfo" /></el-tab-pane>
    <el-tab-pane label="表单设计" name="form"><BusinessFormInfo ref="businessForm" /></el-tab-pane>
    <el-tab-pane label="流程设计" name="flow"><FlowDesigner ref="flowDesigner" embedded :template-id="baseInfo.id" /></el-tab-pane>
    <el-tab-pane label="更多设置" name="more"><MainText ref="mainText" /></el-tab-pane>
  </el-tabs>
</template>
<script>
import FlowDesigner from "@/views/workflow/simple-flow/components/FlowDesigner";
export default {
  methods: {
    async submitForm() {
      for (const tab of this.validatableTabs()) {
        const valid = await this.validateForm(tab.forms()[0])
        if (!valid) { this.activeTab = tab.name; this.highlightFirstError(tab.forms()[0]); return }
      }
    },
    validatableTabs() { return [] },
    validateForm(f) { return Promise.resolve(true) },
    highlightFirstError(f) {}
  }
}
</script>`,
    'views/workflow/template/basic-info.vue': `
<template>
  <el-form ref="basicInfoForm" :model="info">
    <el-select v-model="info.icon" />
    <el-select v-model="info.submitScopeType" />
    <el-select v-model="userIds" />
  </el-form>
</template>
<script>
export default {
  data() {
    return { userIds: [], adminIds: [] }
  },
  methods: {
    syncScopeToModel() { this.$set(this.info, "submitScope", []) },
    onAdmins() { this.$set(this.info, "flowAdmins", []) }
  }
}
</script>`,
    'views/workflow/simple-flow/components/FlowDesigner.vue': `
<template>
  <div>
    <template v-if="embedded">
      <span class="ro-text">{{ flow.name }}</span>
      <span class="ro-text mono">{{ flow.defKey }}</span>
    </template>
    <template v-else>
      <el-input v-model="flow.name" />
      <el-select v-model="formDef.id" />
    </template>
  </div>
</template>
<script>
export default { props: { embedded: { type: Boolean, default: false }, templateId: { type: String } } }
</script>`
  }

  const badFiles = {
    'views/workflow/template/add.vue': `
<template>
  <div>
    <BasicInfo ref="basicInfoForm" />
    <BusinessFormInfo ref="businessForm" />
    <MainText ref="mainText" />
  </div>
</template>
<script>
export default {
  data() { return { activeNames: ["1", "2", "3"], activeName: "basic" } },
  methods: {
    submitForm() {
      Promise.all([1, 2].map(this.getFormPromise)).then(res => { this.$modal.msgError("请完善必填字段！") })
    }
  }
}
</script>`,
    'views/workflow/template/basic-info.vue': `
<template><el-form ref="basicInfoForm" :model="info"><el-select v-model="info.defKey" /></el-form></template>
<script>export default { data() { return {} } }</script>`
  }

  const goodDir = makeFixtureDir(goodFiles)
  const good = runSelfOn(__filename, goodDir)
  const goodHits = Array.isArray(good.json) ? good.json : []
  const badDir = makeFixtureDir(badFiles)
  const bad = runSelfOn(__filename, badDir)
  const badHits = Array.isArray(bad.json) ? bad.json : []
  const badRules = badHits.map(h => h.rule)

  finish('audit-template-tabs', badDir, [
    { label: '脚本能跑通（阳性样本）', pass: bad.code === 0, detail: bad.code === 0 ? '' : bad.out.slice(0, 300) },
    { label: '阴性样本 0 命中', pass: goodHits.length === 0, detail: '实际 ' + goodHits.length + '：' + goodHits.map(h => h.rule + '/' + h.detail).join(' , ') },
    { label: '抓到 T1（页签数量/名称不对）', pass: badRules.includes('T1'), detail: badRules.join(',') },
    { label: '抓到 T2（还是一次性校验）', pass: badRules.includes('T2'), detail: badRules.join(',') },
    { label: '抓到 T3（死字段未删）', pass: badRules.includes('T3'), detail: badRules.join(',') },
    { label: '抓到 T4（设计器未内嵌）', pass: badRules.includes('T4'), detail: badRules.join(',') }
  ])
}

const SRC = process.argv[2] || DEFAULT_SRC
const OUT = process.argv[3] || 'F:/dsh/ruoyiOA/.cache/audit-template-tabs.json'

const read = f => { try { return fs.readFileSync(f, 'utf8') } catch { return '' } }
const hit = (rule, file, detail) => hits.push({ rule, file: file ? path.relative(SRC, file).replace(/\\/g, '/') : '(未找到)', detail })

const hits = []
const addVue = path.join(SRC, 'views/workflow/template/add.vue')
const basicVue = path.join(SRC, 'views/workflow/template/basic-info.vue')
const designerVue = path.join(SRC, 'views/workflow/simple-flow/components/FlowDesigner.vue')

/* T1 四个页签 */
const addSrc = read(addVue)
// 判据看的是**代码**，不是注释：注释里提到"原 activeName 已删除"不该被判成没删
const addCode = addSrc
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/[^\n]*$/gm, '')
  .replace(/\/\/[^\n]*/g, '')
if (!addSrc) {
  hit('T1', addVue, '找不到 add.vue')
} else {
  if (!/<el-tabs[\s>]/.test(addSrc)) hit('T1', addVue, '没有 el-tabs 容器（还是纵向分段）')
  const panes = (addSrc.match(/<el-tab-pane[\s>]/g) || []).length
  if (panes !== 4) hit('T1', addVue, `el-tab-pane 数量=${panes}（应为 4）`)
  const labels = (addSrc.match(/<el-tab-pane[^>]*label="([^"]+)"/g) || []).map(s => (s.match(/label="([^"]+)"/) || [])[1])
  const want = ['基础信息', '表单设计', '流程设计', '更多设置']
  const missing = want.filter(w => !labels.includes(w))
  if (missing.length) hit('T1', addVue, '缺少页签：' + missing.join('、') + '（实际：' + labels.join('、') + '）')

  /* T2 逐页签校验 */
  if (!/validatableTabs\s*\(/.test(addCode)) hit('T2', addVue, '没有逐页签校验清单（validatableTabs）')
  if (!/validateForm\s*\(/.test(addCode)) hit('T2', addVue, '没有单表单校验入口（validateForm）')
  if (!/highlightFirstError\s*\(/.test(addCode)) hit('T2', addVue, '校验失败没有定位/高亮首个错误字段（highlightFirstError）')
  if (!/activeTab\s*=/.test(addCode)) hit('T2', addVue, '校验失败没有切换到出错页签（activeTab 赋值）')
  if (/Promise\.all\(\[[^\]]*getFormPromise/.test(addCode)) hit('T2', addVue, '仍保留升级前的一次性 Promise.all 校验')

  /* T3 死字段 */
  if (/\bactiveNames\b/.test(addCode)) hit('T3', addVue, '死字段 activeNames 未删除')
  if (/\bactiveName\b/.test(addCode)) hit('T3', addVue, '死字段 activeName 未删除（当前页签只应有一个来源）')

  /* T4 设计器内嵌 */
  if (!/FlowDesigner/.test(addCode)) hit('T4', addVue, '没有引用 FlowDesigner（设计器未内嵌）')
  if (!/<FlowDesigner[\s\S]{0,200}?embedded/.test(addCode)) hit('T4', addVue, 'FlowDesigner 没有以 embedded 方式内嵌')
}

/* T5 内嵌时隐藏两个输入 */
const designerSrc = read(designerVue)
if (!designerSrc) hit('T5', designerVue, '找不到内嵌设计器组件 FlowDesigner.vue')
else {
  if (!/v-if="embedded"/.test(designerSrc)) hit('T5', designerVue, '没有 embedded 分支（内嵌时无法隐藏流程名称/关联表单）')
  if (!/ro-text/.test(designerSrc)) hit('T5', designerVue, '内嵌时没有"只读展示"的容器（ro-text）')
  if (!/v-else/.test(designerSrc)) hit('T5', designerVue, '独立路由页的输入被一起删掉了（缺少非内嵌分支）')
}

/* T6 可发起范围 / 流程管理员字段 */
const basicSrc = read(basicVue)
if (!basicSrc) hit('T6', basicVue, '找不到 basic-info.vue')
else {
  if (!/submitScopeType/.test(basicSrc)) hit('T6', basicVue, '缺少「谁可以提交该审批」字段（submitScopeType）')
  if (!/submitScope/.test(basicSrc)) hit('T6', basicVue, '缺少可发起范围明细（submitScope）')
  if (!/flowAdmins/.test(basicSrc)) hit('T6', basicVue, '缺少流程管理员字段（flowAdmins）')
  if (!/icon/.test(basicSrc)) hit('T6', basicVue, '缺少图标选择器（icon）')
}

console.log(`模板四页签重构审计：共 ${hits.length} 处问题\n`)
hits.forEach(h => console.log(`  [${h.rule}] ${h.file}  ${h.detail}`))
fs.writeFileSync(OUT, JSON.stringify(hits, null, 2), 'utf8')
