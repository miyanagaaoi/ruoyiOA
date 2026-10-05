/**
 * 审计「流程 ↔ 模板绑定」链路是否完整（2.0 B1 §4）。
 *
 * 为什么需要它：这条链路横跨 5 个文件（Controller / ServiceImpl / ITemplateService /
 * TemplateServiceImpl / TemplateMapper + XML），任何一处缺失都是**静默**的：
 *   - 少 by-template 接口 → 前端"进页签取草稿"直接 404，看起来像网络问题；
 *   - 少发布回写 → 界面说"发布成功"，发起时却找不到流程（最贵的那种 bug）；
 *   - 回写用 insertTemplate 而不是 updateTemplate → 又变回"换 ID"，子表集体失联；
 *   - `tpl_` 前缀派生规则出现两处 → 前后端各算一套，标识对不上。
 *
 * 判据（每条命中即失败）：
 *   R1 SimpleFlowController 同时暴露 GET 与 POST `/by-template/{templateId}`
 *   R2 SimpleFlowServiceImpl#publish 调用了 ITemplateService#saveFlowBinding
 *   R3 ITemplateService 声明了 saveFlowBinding
 *   R4 TemplateServiceImpl#saveFlowBinding 用 **updateTemplate**（原地）回写，且不 insertTemplate
 *   R5 `tpl_` 前缀派生规则全仓**只有一处**定义（后端 + 前端）
 *   R6 TemplateMapper(+XML) 有 `selectTemplateIdBySimpleFlowId` 反查
 *
 * 用法：
 *   node tools/audit/audit-flow-template-binding.js [后端根目录] [输出JSON]
 *   node tools/audit/audit-flow-template-binding.js --selftest
 */
const fs = require('fs')
const path = require('path')

const DEFAULT_SRC = 'F:/dsh/ruoyiOA/ruoyi-vue-oa-master'

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')

  const goodFiles = {
    'ruoyi-workflow/src/main/java/com/ruoyi/workflow/simple/controller/SimpleFlowController.java': `
@RestController
public class SimpleFlowController {
    @GetMapping("/by-template/{templateId}")
    public AjaxResult getByTemplate(@PathVariable("templateId") String templateId) {
        return success(simpleFlowService.getOrCreateByTemplate(templateId));
    }
    @PostMapping("/by-template/{templateId}")
    public AjaxResult postByTemplate(@PathVariable("templateId") String templateId) {
        return success(simpleFlowService.getOrCreateByTemplate(templateId));
    }
}`,
    'ruoyi-workflow/src/main/java/com/ruoyi/workflow/simple/service/impl/SimpleFlowServiceImpl.java': `
@Service
public class SimpleFlowServiceImpl {
    public static final String TEMPLATE_DEF_KEY_PREFIX = "tpl_";
    public FlowSimple getOrCreateByTemplate(String templateId) {
        return flowSimpleMapper.selectById(templateId);
    }
    public FlowSimple publish(String id, String remark, String templateId) {
        templateService.saveFlowBinding(templateId, db.getId(), def.getKey(), FLOW_MODE_SIMPLE);
        return flowSimpleMapper.selectById(db.getId());
    }
}`,
    'ruoyi-template/src/main/java/com/ruoyi/template/service/ITemplateService.java': `
public interface ITemplateService {
    void saveFlowBinding(String templateId, String simpleFlowId, String defKey, String flowMode);
}`,
    'ruoyi-template/src/main/java/com/ruoyi/template/service/impl/TemplateServiceImpl.java': `
public class TemplateServiceImpl implements ITemplateService {
    public void saveFlowBinding(String templateId, String simpleFlowId, String defKey, String flowMode) {
        Template upd = new Template();
        upd.setId(templateId);
        templateMapper.updateTemplate(upd);
    }
}`,
    'ruoyi-template/src/main/java/com/ruoyi/template/mapper/TemplateMapper.java': `
public interface TemplateMapper {
    String selectTemplateIdBySimpleFlowId(String simpleFlowId);
}`,
    'ruoyi-template/src/main/resources/mapper/template/TemplateMapper.xml': `
<select id="selectTemplateIdBySimpleFlowId" resultType="java.lang.String">
    select id from t_template where simple_flow_id = #{simpleFlowId}
</select>`
  }

  const badFiles = {
    // R1 缺 POST；R2 缺 saveFlowBinding；R4 用 insertTemplate；R5 两处 tpl_
    'ruoyi-workflow/src/main/java/com/ruoyi/workflow/simple/controller/SimpleFlowController.java': `
public class SimpleFlowController {
    @GetMapping("/by-template/{templateId}")
    public AjaxResult getByTemplate(@PathVariable String templateId) { return null; }
}`,
    'ruoyi-workflow/src/main/java/com/ruoyi/workflow/simple/service/impl/SimpleFlowServiceImpl.java': `
public class SimpleFlowServiceImpl {
    public static final String TEMPLATE_DEF_KEY_PREFIX = "tpl_";
    public FlowSimple publish(String id, String remark, String templateId) {
        return flowSimpleMapper.selectById(id);
    }
}`,
    'ruoyi-template/src/main/java/com/ruoyi/template/service/impl/TemplateServiceImpl.java': `
public class TemplateServiceImpl implements ITemplateService {
    public void saveFlowBinding(String templateId, String simpleFlowId, String defKey, String flowMode) {
        Template row = new Template();
        templateMapper.insertTemplate(row);
    }
}`,
    'ruoyi-common/src/main/java/com/ruoyi/common/utils/TplKeyUtil.java': `
public class TplKeyUtil {
    public static String key(String templateId) { return "tpl_" + templateId.substring(0, 8); }
}`
  }

  const goodDir = makeFixtureDir(goodFiles)
  const good = runSelfOn(__filename, goodDir)
  const goodHits = Array.isArray(good.json) ? good.json : []
  const badDir = makeFixtureDir(badFiles)
  const bad = runSelfOn(__filename, badDir)
  const badHits = Array.isArray(bad.json) ? bad.json : []
  const badRules = badHits.map(h => h.rule)

  finish('audit-flow-template-binding', badDir, [
    { label: '脚本能跑通（阳性样本）', pass: bad.code === 0, detail: bad.code === 0 ? '' : bad.out.slice(0, 300) },
    { label: '阴性样本 0 命中', pass: goodHits.length === 0, detail: '实际 ' + goodHits.length + '：' + goodHits.map(h => h.rule).join(',') },
    { label: '抓到 R1（缺 POST by-template）', pass: badRules.includes('R1'), detail: badRules.join(',') },
    { label: '抓到 R2（发布未回写）', pass: badRules.includes('R2'), detail: badRules.join(',') },
    { label: '抓到 R4（用 insertTemplate 回写）', pass: badRules.includes('R4'), detail: badRules.join(',') },
    { label: '抓到 R5（tpl_ 前缀出现两处）', pass: badRules.includes('R5'), detail: badRules.join(',') }
  ])
}

const SRC = process.argv[2] || DEFAULT_SRC
const OUT = process.argv[3] || 'F:/dsh/ruoyiOA/.cache/audit-flow-template-binding.json'

function walk(dir, acc) {
  let entries = []
  try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { return acc }
  for (const e of entries) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'target' || e.name === '.git' || e.name === 'vform') continue
      walk(p, acc)
    } else acc.push(p)
  }
  return acc
}

const all = walk(SRC, []).filter(f => /\.(java|xml|js|vue)$/.test(f))
const byName = {}
for (const f of all) {
  const n = path.basename(f)
  ;(byName[n] = byName[n] || []).push(f)
}
const read = f => { try { return fs.readFileSync(f, 'utf8') } catch { return '' } }
const find = n => (byName[n] || [])[0] || null
const rel = f => (f ? path.relative(SRC, f).replace(/\\/g, '/') : '(未找到)')

const hits = []
const hit = (rule, file, detail, line) => hits.push({ rule, file: rel(file), line: line || 0, detail })

/* R1 by-template 端点 */
const ctrl = find('SimpleFlowController.java')
if (!ctrl) hit('R1', null, '找不到 SimpleFlowController.java')
else {
  const s = read(ctrl)
  if (!/@GetMapping\(\s*"\/by-template\//.test(s)) hit('R1', ctrl, '缺少 GET /by-template/{templateId}')
  if (!/@PostMapping\(\s*"\/by-template\//.test(s)) hit('R1', ctrl, '缺少 POST /by-template/{templateId}')
  if (!/getOrCreateByTemplate/.test(s)) hit('R1', ctrl, 'by-template 端点没有调用 getOrCreateByTemplate')
}

/* R2 发布回写 */
const impl = find('SimpleFlowServiceImpl.java')
if (!impl) hit('R2', null, '找不到 SimpleFlowServiceImpl.java')
else {
  const s = read(impl)
  if (!/saveFlowBinding\s*\(/.test(s)) hit('R2', impl, 'publish 链路没有调用 saveFlowBinding（发布后模板绑定不会回写）')
  if (!/getOrCreateByTemplate\s*\(/.test(s)) hit('R2', impl, '缺少 getOrCreateByTemplate（§4.1 取/建草稿）')
}

/* R3 接口声明 */
const itf = find('ITemplateService.java')
if (!itf) hit('R3', null, '找不到 ITemplateService.java')
else if (!/saveFlowBinding\s*\(/.test(read(itf))) hit('R3', itf, 'ITemplateService 未声明 saveFlowBinding')

/* R4 回写必须原地更新 */
const tplImpl = find('TemplateServiceImpl.java')
if (!tplImpl) hit('R4', null, '找不到 TemplateServiceImpl.java')
else {
  const s = read(tplImpl)
  const m = s.match(/public\s+void\s+saveFlowBinding\s*\([^)]*\)\s*\{[\s\S]*?\n    \}/)
  if (!m) hit('R4', tplImpl, '找不到 saveFlowBinding 实现')
  else {
    if (!/templateMapper\.updateTemplate\s*\(/.test(m[0])) hit('R4', tplImpl, 'saveFlowBinding 没有用 updateTemplate 回写（不可能落在启用行上）')
    if (/templateMapper\.insertTemplate\s*\(/.test(m[0])) hit('R4', tplImpl, 'saveFlowBinding 用了 insertTemplate（会重新引入"换 ID"语义）')
  }
}

/* R5 tpl_ 前缀只允许出现在"已知的两处"，且两处的取位长度必须一致
 *
 * 为什么不是"只有一处"：模块依赖方向是 ruoyi-workflow → ruoyi-template，
 * 而**新建模板时就要落一个非空的 defKey**（t_template.def_key 是 NOT NULL 无默认值），
 * 此刻流程侧还没参与；模板侧不能反向引用流程侧的静态工具，只能各留一份实现。
 * 所以规则改成：① 只允许这两个文件出现；② 两处的"取模板ID前 N 位"必须都是 8，
 * 一旦有人改了一边而忘了另一边，这条就会报出来（这才是当初写 R5 的目的）。
 */
const prefixRe = /["']tpl_["']/g
const prefixOwners = []
const scanRoots = [SRC]
const ui = path.resolve(SRC, '..', 'ruoyi-vue-oa-ui-master', 'src')
if (fs.existsSync(ui)) scanRoots.push(ui)
for (const root of scanRoots) {
  for (const f of walk(root, [])) {
    if (!/\.(java|js|vue|xml)$/.test(f)) continue
    const s = read(f)
    const n = (s.match(prefixRe) || []).length
    if (n > 0) prefixOwners.push({ f, n })
  }
}
const PREFIX_ALLOWED = ['SimpleFlowServiceImpl.java', 'TemplateServiceImpl.java']
const unexpected = prefixOwners.filter(o => !PREFIX_ALLOWED.some(a => o.f.endsWith(a)))
if (unexpected.length) {
  hit('R5', unexpected[0].f,
    '"tpl_" 前缀出现在未登记的文件里（派生规则只允许流程侧 + 模板侧各一处）：' +
    unexpected.map(o => rel(o.f)).join(', '))
}
const flowImpl = prefixOwners.find(o => o.f.endsWith('SimpleFlowServiceImpl.java'))
const tplOwner = prefixOwners.find(o => o.f.endsWith('TemplateServiceImpl.java'))
if (flowImpl && tplOwner) {
  const lens = [read(flowImpl.f), read(tplOwner.f)].map(s => {
    const m = s.match(/DEF_KEY_ID_LEN\s*=\s*(\d+)/)
    return m ? m[1] : '?'
  })
  if (lens[0] !== '8' || lens[1] !== '8') {
    hit('R5', tplOwner.f, `两侧的取位长度不一致或不是 8（流程侧=${lens[0]} 模板侧=${lens[1]}）：改了派生规则必须两边同时改`)
  }
}

/* R6 反查存在 */
const mapper = find('TemplateMapper.java')
const mapperXml = find('TemplateMapper.xml')
if (!mapper || !/selectTemplateIdBySimpleFlowId/.test(read(mapper))) hit('R6', mapper, 'TemplateMapper 缺少 selectTemplateIdBySimpleFlowId')
if (!mapperXml || !/selectTemplateIdBySimpleFlowId/.test(read(mapperXml))) hit('R6', mapperXml, 'TemplateMapper.xml 缺少 selectTemplateIdBySimpleFlowId 语句')

console.log(`流程↔模板绑定链路审计：共 ${hits.length} 处问题\n`)
hits.forEach(h => console.log(`  [${h.rule}] ${h.file}:${h.line}  ${h.detail}`))
fs.writeFileSync(OUT, JSON.stringify(hits, null, 2), 'utf8')
