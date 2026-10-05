/**
 * 审计：`this.X = ...` 里的 X 是否**声明过**（Vue 2 的非响应式赋值缺陷）。
 *
 * 为什么值得查：Vue 2 里给一个**未声明**的属性赋值不会报错，但它是**非响应式**的 ——
 * 模板永远不会因它更新。典型症状：`this.loading = true` 了、loading 遮罩却从不出现，
 * 或者数据加载完了列表不刷新（排查时极难定位，因为控制台一片安静）。
 * 实例：`src/views/schedule/components/type/index.vue` 的 `loading` 从未在 data 里声明。
 *
 * 判据：
 *   declared = data() 返回对象的顶层键 ∪ props ∪ computed ∪ methods ∪ inject
 *              ∪ 全局 mixin 注入的 `dict`
 *              ∪ 该文件 `mixins: [...]` 里各 mixin 文件声明的同名集合
 *              ∪ Vue 内建（$ 开头）与 main.js 里 Vue.prototype 注入的名字
 *   命中 = `this.<name> = ...` 或 `this.<name>++/--` 且 name 不在 declared 里
 *
 * 用法：node audit-undeclared-writes.js [srcRoot]
 */
const fs = require('fs')
const path = require('path')

const SRC = process.argv[2] || 'F:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'

function findNodeModules(start) {
  let dir = path.resolve(start)
  for (let i = 0; i < 8; i++) {
    const nm = path.join(dir, 'node_modules')
    if (fs.existsSync(path.join(nm, '@babel', 'parser'))) return nm
    const up = path.dirname(dir)
    if (up === dir) break
    dir = up
  }
  return null
}
const NM = process.env.DSH_AUDIT_NM || findNodeModules(SRC) || findNodeModules('F:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src')
if (!NM) { console.error('找不到 node_modules/@babel/parser（可用 DSH_AUDIT_NM 指定路径）'); process.exit(1) }
const parser = require(path.join(NM, '@babel', 'parser'))
const traverse = require(path.join(NM, '@babel', 'traverse')).default

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')
  process.env.DSH_AUDIT_NM = NM
  const dir = makeFixtureDir({
    // 真风险：模板绑定里在用，但没在 data 里声明 -> 模板永远不会更新
    'risky.vue': `<template>
  <div>{{ foo }}</div>
</template>
<script>
export default {
  data() { return { ok: 1 } },
  methods: { m() { this.foo = 'x'; } }
}
</script>`,
    // 无害：赋值了但名字**不在任何模板绑定里** -> 死代码/暂存
    'benign-dead.vue': `<template>
  <div>{{ ok }}</div>
</template>
<script>
export default {
  data() { return { ok: 1 } },
  methods: { m() { this.deadField = 'x'; } }
}
</script>`,
    // 阴性：已声明 -> 既不 risky 也不 benign
    'good-declared.vue': `<template>
  <div>{{ baz }}</div>
</template>
<script>
export default {
  data() { return { baz: 1 } },
  methods: { m() { this.baz = 2; } }
}
</script>`,
    // 回归断言（§10 踩过的坑）：静态 class 名 "json-editor" 不等于模板里绑定了 jsonEditor
    'static-class.vue': `<template>
  <div id="editorJson" class="json-editor">{{ ok }}</div>
</template>
<script>
export default {
  data() { return { ok: 1 } },
  methods: { m() { this.jsonEditor = 'x'; } }
}
</script>`,
    // 阴性：v-for 别名与 slot-scope 解构不是 this 上的属性
    'good-scope.vue': `<template>
  <div>
    <span v-for="(it, i) in list" :key="i">{{ it.name }}</span>
    <el-table :data="list"><template slot-scope="{ row }">{{ row.a }}</template></el-table>
  </div>
</template>
<script>
export default {
  data() { return { list: [] } },
  methods: { m() { this.list = [1]; this.keepOut = 2; } }
}
</script>`
  })
  const r = runSelfOn(__filename, dir)
  const j = r.json || {}
  const risky = (j.risky || []).map(h => h.file)
  const benign = (j.benign || []).map(h => h.file)
  finish('audit-undeclared-writes', dir, [
    { label: '脚本能跑通', pass: r.code === 0, detail: r.code === 0 ? '' : r.out.slice(0, 300) },
    { label: 'risky 抓到 risky.vue', pass: risky.includes('risky.vue'), detail: 'risky：' + (risky.join(', ') || '(空)') },
    { label: 'benign 含 benign-dead.vue', pass: benign.includes('benign-dead.vue') },
    { label: '静态 class 名不算模板引用（§10 的坑）', pass: !risky.includes('static-class.vue') },
    { label: '已声明的 good-declared.vue 两边都不报', pass: !risky.includes('good-declared.vue') && !benign.includes('good-declared.vue') },
    { label: 'v-for/slot-scope 解构不被误当属性', pass: !risky.includes('good-scope.vue') },
    { label: 'risky 总数正好 1', pass: risky.length === 1, detail: '实际 ' + risky.length }
  ])
}

const PARSER_OPTS = {
  sourceType: 'module', errorRecovery: true,
  plugins: ['optionalChaining', 'nullishCoalescingOperator', 'objectRestSpread', 'classProperties',
    'classPrivateProperties', 'dynamicImport', 'asyncGenerators', 'topLevelAwait', 'jsx']
}

/** main.js 里 Vue.prototype.X = ... 注入的名字（这些不是 data 属性，但合法） */
const PROTOTYPE_NAMES = new Set(['getDicts', 'getConfigKey', 'parseTime', 'resetForm', 'addDateRange',
  'selectDictLabel', 'selectDictLabels', 'download', 'handleTree', 'modelerStore'])

const isBuiltin = n => n.startsWith('$') || n.startsWith('_')
const ok = (n, declared) => isBuiltin(n) || PROTOTYPE_NAMES.has(n) || declared.has(n)

const files = []
;(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'vform' || e.name === 'node_modules') continue
      walk(p)
    } else if (/\.(vue|js)$/.test(e.name)) files.push(p)
  }
})(SRC)

function load(file) {
  const raw = fs.readFileSync(file, 'utf8')
  const ext = path.extname(file).toLowerCase()
  let code = raw
  let template = ''
  if (ext === '.vue') {
    const m = raw.match(/<script[^>]*>([\s\S]*?)<\/script>/)
    code = m ? m[1] : null
    const t = raw.match(/<template[^>]*>([\s\S]*)<\/template>/)
    template = t ? t[1] : ''
  }
  // .vue 里没有 <script> 块（纯模板组件，如 ParentView）不是"解析失败"，是"没什么可审计"
  if (!code) return { noScript: true }
  try { return { ast: parser.parse(code, PARSER_OPTS), code, template } } catch (e) { return null }
}

/** 取 `export default {...}` 或 `Vue.extend({...})` 的对象字面量路径 */
function defaultObject(ast) {
  let obj = null
  traverse(ast, {
    ExportDefaultDeclaration(p) {
      let n = p.node.declaration
      if (n.type === 'CallExpression' && n.arguments.length) n = n.arguments[0]
      if (n.type === 'ObjectExpression') obj = p.get('declaration').isCallExpression()
        ? p.get('declaration').get('arguments')[0] : p.get('declaration')
    },
    CallExpression(p) {
      const c = p.node.callee
      if (c && c.type === 'MemberExpression' && c.object.name === 'Vue' && c.property.name === 'extend' &&
        p.node.arguments[0] && p.node.arguments[0].type === 'ObjectExpression') {
        obj = p.get('arguments')[0]
      }
    }
  })
  return obj
}

/** 从一个对象字面量路径收集键名 */
function objectKeys(objPath) {
  const out = new Set()
  if (!objPath || !objPath.node) return out
  const n = objPath.node
  if (n.type === 'ArrayExpression') {
    n.elements.forEach(e => { if (e && e.type === 'StringLiteral') out.add(e.value) })
    return out
  }
  if (n.type !== 'ObjectExpression') return out
  objPath.get('properties').forEach(pr => {
    if (pr.isObjectProperty()) {
      const k = pr.node.key
      if (k.type === 'Identifier') out.add(k.name)
      else if (k.type === 'StringLiteral') out.add(k.value)
    }
  })
  return out
}

/**
 * 在对象字面量里找某个选项，返回 { kind, path }。
 * ⚠ 必须同时处理 ObjectProperty 与 **ObjectMethod**：
 * `data() { return {...} }` 是方法简写（ObjectMethod），不是 ObjectProperty。
 * 只认 ObjectProperty 会导致 data 找不到 -> data 里声明的属性全部被误报成"未声明"（踩过）。
 */
function findOption(objPath, name) {
  if (!objPath || !objPath.node || objPath.node.type !== 'ObjectExpression') return null
  for (const pr of objPath.get('properties')) {
    const key = pr.node.key
    const k = key && (key.type === 'Identifier' ? key.name : key.type === 'StringLiteral' ? key.value : null)
    if (k !== name) continue
    if (pr.isObjectMethod()) return { kind: 'method', path: pr }
    if (pr.isObjectProperty()) return { kind: 'value', path: pr.get('value') }
  }
  return null
}

/** data() 返回对象的顶层键；接受 ObjectMethod（data() {}）或 FunctionExpression/对象字面量 */
function dataKeys(found) {
  const out = new Set()
  let spread = false
  if (!found) return { keys: out, spread }
  const collect = objPath => {
    if (!objPath || !objPath.node || objPath.node.type !== 'ObjectExpression') { spread = true; return }
    objPath.get('properties').forEach(pr => {
      if (pr.isObjectProperty()) {
        const k = pr.node.key
        if (k.type === 'Identifier') out.add(k.name)
        else if (k.type === 'StringLiteral') out.add(k.value)
      } else if (pr.isSpreadElement()) spread = true
      else if (pr.isObjectMethod()) {
        const k = pr.node.key
        if (k.type === 'Identifier') out.add(k.name)
        else if (k.type === 'StringLiteral') out.add(k.value)
      }
    })
  }
  const n = found.path.node
  if (found.kind === 'method') {
    // data() { return {...} }
    found.path.traverse({
      ReturnStatement(rp) {
        const a = rp.node.argument
        if (a && a.type === 'ObjectExpression') collect(rp.get('argument'))
      }
    })
    return { keys: out, spread }
  }
  if (n.type === 'ObjectExpression') { collect(found.path); return { keys: out, spread } }
  if (n.type === 'FunctionExpression' || n.type === 'ArrowFunctionExpression') {
    if (n.body.type === 'ObjectExpression') { collect(found.path.get('body')); return { keys: out, spread } }
    found.path.traverse({
      ReturnStatement(rp) {
        const a = rp.node.argument
        if (a && a.type === 'ObjectExpression') collect(rp.get('argument'))
      }
    })
  }
  return { keys: out, spread }
}

function declaredOf(objPath, file) {
  const declared = new Set(['dict']) // 全局 mixin 在声明了 dicts 时注入 dict；一律放行
  let spread = false
  if (!objPath || !objPath.node) return { declared, spread }
  ;['props', 'computed', 'methods', 'inject'].forEach(k => {
    const f = findOption(objPath, k)
    if (f) objectKeys(f.path).forEach(x => declared.add(x))
  })
  const propsOpt = findOption(objPath, 'props')
  if (propsOpt && propsOpt.path.isArrayExpression()) {
    objectKeys(propsOpt.path).forEach(x => declared.add(x))
  }
  const dataOpt = findOption(objPath, 'data')
  if (dataOpt) {
    const r = dataKeys(dataOpt)
    r.keys.forEach(x => declared.add(x))
    spread = spread || r.spread
  }
  return { declared, spread }
}

/** 解析 mixins: [A, B] -> 各自文件里声明的名字并集 */
function mixinNames(objPath, file, ast) {
  const out = new Set()
  const mp = findOption(objPath, 'mixins')
  if (!mp || !mp.path.isArrayExpression()) return out
  // 先建 import 名 -> 绝对路径
  const importMap = {}
  traverse(ast, {
    ImportDeclaration(p) {
      const src = p.node.source.value
      if (!src.startsWith('.')) return
      const abs = path.resolve(path.dirname(file), src)
      p.node.specifiers.forEach(s => { importMap[s.local.name] = abs })
    }
  })
  mp.path.get('elements').forEach(el => {
    if (!el.isIdentifier()) return
    const abs = importMap[el.node.name]
    if (!abs) return
    const cand = [abs, abs + '.js', abs + '.vue', path.join(abs, 'index.js')]
      .find(p => { try { return fs.statSync(p).isFile() } catch { return false } })
    if (!cand) return
    const loaded = load(cand)
    if (!loaded) return
    const o = defaultObject(loaded.ast)
    const r = declaredOf(o, cand)
    r.declared.forEach(x => out.add(x))
  })
  return out
}

const hits = []
let parsed = 0
const failedFiles = []
let noScript = 0

for (const file of files) {
  const loaded = load(file)
  if (!loaded) { failedFiles.push(path.relative(SRC, file).replace(/\\/g, '/')); continue }
  if (loaded.noScript) { noScript++; continue }
  parsed++
  const objPath = defaultObject(loaded.ast)
  if (!objPath) continue
  const { declared, spread } = declaredOf(objPath, file)
  const mixNames = mixinNames(objPath, file, loaded.ast)
  mixNames.forEach(n => declared.add(n))
  const rel = path.relative(SRC, file).replace(/\\/g, '/')
  const tpl = loaded.template || ''
  /**
   * 该属性名是否**真的作为 Vue 绑定表达式**出现在 <template> 里。
   *
   * 这是把"真 bug"与"有意为之的非响应式暂存 / 死代码"分开的关键一刀：
   *  - 真出现在模板绑定里 + 非响应式赋值 = **模板永远不会更新**，是真 bug；
   *  - 不在绑定里（bpmn-js 模型对象、编辑器实例、遗留死字段）= 无害。
   *
   * 这一刀我自己踩了三次，边界一次比一次紧，记下来免得再犯：
   *  ① `\bloading\b` 会命中 `v-loading="sealLoading"`、`element-loading-text` 里的属性名；
   *  ② `\bzoom\b` 会命中 `icon="el-icon-zoom-in"`、`zoomViewport`；
   *  ③ 连"前后都不是 [-.\w]"也不够：`class="json-editor"` 会让 `jsonEditor` 误报
   *     （它的 kebab 形式恰好等于那个 CSS 类名）。
   * 所以正确做法是**只从绑定表达式里找**，不看 class/id/静态属性：
   * `{{ ... }}`、`:xxx="..."`、`v-xxx="..."`、`@xxx="..."` 的值。
   */
  const bindingText = (() => {
    const out = []
    for (const m of tpl.matchAll(/\{\{([\s\S]*?)\}\}/g)) out.push(m[1])
    for (const m of tpl.matchAll(/(?:^|\s)(?::|v-|@)[\w:.-]+\s*=\s*"([^"]*)"/g)) out.push(m[1])
    for (const m of tpl.matchAll(/(?:^|\s)(?::|v-|@)[\w:.-]+\s*=\s*'([^']*)'/g)) out.push(m[1])
    return out.join('\n')
  })()
  const inTpl = n => {
    const kebab = n.replace(/([A-Z])/g, '-$1').toLowerCase()
    const forms = n === kebab ? [n] : [n, kebab]
    try {
      return forms.some(f => new RegExp('(?<![-.\\w])' + f + '(?![-.\\w])').test(bindingText))
    } catch {
      return forms.some(f => new RegExp('\\b' + f + '\\b').test(bindingText))
    }
  }

  loaded.ast && traverse(loaded.ast, {
    AssignmentExpression(p) {
      const l = p.node.left
      if (l.type !== 'MemberExpression' || l.object.type !== 'ThisExpression') return
      // ⚠ 计算属性访问 `this[expr]` 的 property 只是个 Identifier 变量，
      // 不是属性名。不排除会把 `this[variable] = ...` 误报成 "this.variable"（踩过）。
      if (l.computed && l.property.type !== 'StringLiteral') return
      const k = l.property
      const name = k && (k.type === 'Identifier' ? k.name : k.type === 'StringLiteral' ? k.value : null)
      if (!name || ok(name, declared)) return
      hits.push({ file: rel, line: p.node.loc.start.line, name, kind: 'assign', spread, inTemplate: inTpl(name) })
    },
    UpdateExpression(p) {
      const a = p.node.argument
      if (a.type !== 'MemberExpression' || a.object.type !== 'ThisExpression') return
      if (a.computed && a.property.type !== 'StringLiteral') return
      const name = a.property && a.property.name
      if (!name || ok(name, declared)) return
      hits.push({ file: rel, line: p.node.loc.start.line, name, kind: 'update', spread, inTemplate: inTpl(name) })
    }
  })
}

hits.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)
const risky = hits.filter(h => h.inTemplate)
const benign = hits.filter(h => !h.inTemplate)

console.log(`解析 ${parsed} 个文件（跳过无 <script> 的 ${noScript} 个，解析失败 ${failedFiles.length} 个）；未声明就被赋值的属性共 ${hits.length} 处`)
console.log(`  其中【真风险】${risky.length} 处 —— 该名字出现在 <template> 里，模板不会更新`)
console.log(`  【无害暂存】${benign.length} 处 —— 名字不在模板里（bpmn-js 对象 / 编辑器实例等，本就不该响应式）\n`)

const dump = (title, arr) => {
  console.log(`########## ${title}  (${arr.length}) ##########`)
  arr.forEach(h => console.log(`  ${h.file}:${h.line}  this.${h.name}${h.kind === 'update' ? ' ++/--' : ' ='}${h.spread ? '   [data 含 spread，需人工确认]' : ''}`))
  console.log()
}
dump('真风险：模板绑定里在用，但赋值不会触发更新', risky)
dump('无害暂存 / 死代码：不在任何模板绑定里', benign)

if (failedFiles.length) {
  console.log(`########## 解析失败、未纳入审计的文件 (${failedFiles.length}) —— 不能假装它们没问题 ##########`)
  failedFiles.forEach(f => console.log('  ' + f))
  console.log()
}

fs.writeFileSync(process.argv[3] || 'F:/dsh/ruoyiOA/.cache/undeclared-writes.json',
  JSON.stringify({ risky, benign, failedFiles }, null, 2), 'utf8')
