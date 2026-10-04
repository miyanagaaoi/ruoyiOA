/**
 * 审计（镜像）：模板绑定里引用了一个**根本没定义**的名字。
 *
 * 上一条审计查的是「JS 给未声明的 this.X 赋值」；这条查反向：
 * 模板表达式里用到的标识符，在 data / props / computed / methods / inject / mixins /
 * Vue 原型注入 里都找不到 —— 渲染出来是 `undefined`，或者在渲染期直接抛错。
 *
 * 做法（不靠正则猜）：
 *   1. 用 `vue-template-compiler` 编译模板拿 AST，这样 `v-for` 别名、`slot-scope`、
 *      `v-slot` 解构这些**局部作用域**能按层级正确处理；
 *   2. 把每个绑定表达式（{{ }}、:x、v-x、@x）用 @babel/parser 解析成表达式，
 *      再收集其中的标识符引用（排除 `a.b` 的 b、对象字面量的键、函数参数、关键字）；
 *   3. 减去局部作用域、已声明名、Vue 内建与 JS 全局，剩下的就是"引用了不存在的东西"。
 *
 * 用法：node audit-template-refs.js [srcRoot] [outJson]
 */
const fs = require('fs')
const path = require('path')

const ARGS = process.argv.slice(2).filter(a => !a.startsWith('--'))
const SELFTEST = process.argv.includes('--selftest')
const SRC = ARGS[0] || 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
const OUT = ARGS[1] || 'H:/dsh/ruoyiOA/.cache/template-refs.json'

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
const NM = findNodeModules(SRC)
if (!NM) { console.error('找不到 node_modules/@babel/parser（从 ' + SRC + ' 向上查找失败）'); process.exit(1) }
const parser = require(path.join(NM, '@babel', 'parser'))
const traverse = require(path.join(NM, '@babel', 'traverse')).default
const compiler = require(path.join(NM, 'vue-template-compiler'))

const PARSER_OPTS = {
  sourceType: 'module', errorRecovery: true,
  plugins: ['optionalChaining', 'nullishCoalescingOperator', 'objectRestSpread', 'classProperties',
    'classPrivateProperties', 'dynamicImport', 'asyncGenerators', 'topLevelAwait', 'jsx']
}

const PROTOTYPE_NAMES = new Set(['getDicts', 'getConfigKey', 'parseTime', 'resetForm', 'addDateRange',
  'selectDictLabel', 'selectDictLabels', 'download', 'handleTree', 'modelerStore'])

/** 模板表达式里出现这些不算"引用了不存在的东西" */
const GLOBALS = new Set(['true', 'false', 'null', 'undefined', 'this', 'new', 'typeof', 'in',
  'instanceof', 'void', 'delete', 'return', 'if', 'else', 'await', 'yield',
  'Math', 'Date', 'JSON', 'Number', 'String', 'Boolean', 'Array', 'Object', 'RegExp', 'Error',
  'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURIComponent', 'decodeURIComponent',
  'console', 'window', 'document', 'NaN', 'Infinity', 'arguments'])

const isBuiltinName = n => n.startsWith('$') || n.startsWith('_')

const files = []
;(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'vform' || e.name === 'node_modules') continue
      walk(p)
    } else if (e.name.endsWith('.vue')) files.push(p)
  }
})(SRC)

function load(file) {
  const raw = fs.readFileSync(file, 'utf8')
  const m = raw.match(/<script[^>]*>([\s\S]*?)<\/script>/)
  const t = raw.match(/<template[^>]*>([\s\S]*)<\/template>/)
  // .vue 里没有 <script> 块（纯模板组件，如 ParentView）不是"解析失败"，是"没什么可审计"
  if (!m) return { noScript: true, template: t ? t[1] : '' }
  let ast = null
  try { ast = parser.parse(m[1], PARSER_OPTS) } catch (e) { ast = null }
  return { ast, template: t ? t[1] : '' }
}

/* ---------- 与 audit-undeclared-writes 相同的"已声明名"提取 ---------- */
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
    const k = pr.node.key
    if (!k) {
      // `...mapGetters([...])` / `...mapState([...])`：名字静态写在实参里，可以精确取出
      if (pr.isSpreadElement()) collectMappedNames(pr).forEach(x => out.add(x))
      return
    }
    if (pr.isObjectProperty() || pr.isObjectMethod()) {
      if (k.type === 'Identifier') out.add(k.name)
      else if (k.type === 'StringLiteral') out.add(k.value)
    }
  })
  return out
}
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
function dataKeys(found) {
  const out = new Set()
  if (!found) return out
  const collect = objPath => {
    if (!objPath || !objPath.node || objPath.node.type !== 'ObjectExpression') return
    objPath.get('properties').forEach(pr => {
      const k = pr.node.key
      if (!k) return
      if (pr.isObjectProperty() || pr.isObjectMethod()) {
        if (k.type === 'Identifier') out.add(k.name)
        else if (k.type === 'StringLiteral') out.add(k.value)
      }
    })
  }
  const n = found.path.node
  if (found.kind === 'method') {
    found.path.traverse({ ReturnStatement(rp) { const a = rp.node.argument; if (a && a.type === 'ObjectExpression') collect(rp.get('argument')) } })
    return out
  }
  if (n.type === 'ObjectExpression') { collect(found.path); return out }
  if (n.type === 'FunctionExpression' || n.type === 'ArrowFunctionExpression') {
    if (n.body.type === 'ObjectExpression') { collect(found.path.get('body')); return out }
    found.path.traverse({ ReturnStatement(rp) { const a = rp.node.argument; if (a && a.type === 'ObjectExpression') collect(rp.get('argument')) } })
  }
  return out
}
function defaultObject(ast) {
  let obj = null
  traverse(ast, {
    ExportDefaultDeclaration(p) {
      let n = p.node.declaration
      const dp = p.get('declaration')
      if (n.type === 'CallExpression' && n.arguments.length) {
        if (n.arguments[0].type === 'ObjectExpression') obj = dp.get('arguments')[0]
      } else if (n.type === 'ObjectExpression') obj = dp
    },
    CallExpression(p) {
      const c = p.node.callee
      if (c && c.type === 'MemberExpression' && c.object.name === 'Vue' && c.property.name === 'extend' &&
        p.node.arguments[0] && p.node.arguments[0].type === 'ObjectExpression') obj = p.get('arguments')[0]
    }
  })
  return obj
}
function declaredOf(objPath, file, ast, depth) {
  depth = depth || 0
  const declared = new Set(['dict'])
  if (!objPath || !objPath.node) return declared
  ;['props', 'computed', 'methods', 'inject'].forEach(k => {
    const f = findOption(objPath, k)
    if (f) objectKeys(f.path).forEach(x => declared.add(x))
  })
  const propsOpt = findOption(objPath, 'props')
  if (propsOpt && propsOpt.path.isArrayExpression()) objectKeys(propsOpt.path).forEach(x => declared.add(x))
  const dataOpt = findOption(objPath, 'data')
  if (dataOpt) dataKeys(dataOpt).forEach(x => declared.add(x))

  // mixins：把 `mixins: [ResizeMixin]` 里的标识符解析回文件，并入该 mixin 声明的名字
  if (depth < 2 && ast) {
    const mp = findOption(objPath, 'mixins')
    if (mp && mp.path.isArrayExpression()) {
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
        const base = importMap[el.node.name]
        if (!base) return
        const cand = [base, base + '.js', base + '.vue', path.join(base, 'index.js')]
          .find(p => { try { return fs.statSync(p).isFile() } catch { return false } })
        if (!cand) return
        const loaded = load(cand)
        if (!loaded || !loaded.ast) return
        const mo = defaultObject(loaded.ast)
        if (!mo) return
        declaredOf(mo, cand, loaded.ast, depth + 1).forEach(x => declared.add(x))
      })
    }
  }
  return declared
}

/* ---------- 模板表达式里的标识符引用 ---------- */
/** 从一个表达式字符串里收集"自由标识符" */
function freeIdentifiers(expr) {
  const out = new Set()
  let node
  try { node = parser.parseExpression(expr, PARSER_OPTS) } catch { return out }
  const walk = (n, locals) => {
    if (!n || typeof n !== 'object') return
    if (Array.isArray(n)) { n.forEach(x => walk(x, locals)); return }
    if (!n.type) return
    switch (n.type) {
      case 'Identifier':
        if (!locals.has(n.name)) out.add(n.name)
        return
      case 'MemberExpression':
        walk(n.object, locals)            // n.property 不是引用（除非计算访问）
        if (n.computed) walk(n.property, locals)
        return
      case 'ObjectProperty':
        if (n.computed) walk(n.key, locals)
        walk(n.value, locals)
        return
      case 'ObjectMethod':
        walk(n.key, n.computed ? locals : locals)
        return
      case 'ArrowFunctionExpression':
      case 'FunctionExpression': {
        const inner = new Set(locals)
        ;(n.params || []).forEach(pp => collectPatternNames(pp, inner))
        walk(n.body, inner)
        return
      }
      case 'ThisExpression':
        return
      case 'Literal':
      case 'StringLiteral':
      case 'NumericLiteral':
      case 'BooleanLiteral':
        return
      default:
        for (const k of Object.keys(n)) {
          if (k === 'loc' || k === 'start' || k === 'end' || k === 'type') continue
          walk(n[k], locals)
        }
    }
  }
  walk(node, new Set())
  return out
}
function collectPatternNames(pat, into) {
  if (!pat) return
  if (pat.type === 'Identifier') into.add(pat.name)
  else if (pat.type === 'AssignmentPattern') collectPatternNames(pat.left, into)
  else if (pat.type === 'ObjectPattern') pat.properties.forEach(pr => collectPatternNames(pr.value || pr.argument, into))
  else if (pat.type === 'ArrayPattern') pat.elements.forEach(e => collectPatternNames(e, into))
  else if (pat.type === 'RestElement') collectPatternNames(pat.argument, into)
  // ⚠ `slot-scope="{ row, $index }"` 用 parseExpression 解析出来的是 **ObjectExpression**
  // 而不是 ObjectPattern —— 只处理 ObjectPattern 会漏掉作用域名，
  // 于是 row / $index 被当成"自由标识符"误报（踩过）。
  else if (pat.type === 'ObjectExpression') {
    pat.properties.forEach(pr => {
      if (!pr) return
      if (pr.type === 'ObjectProperty') {
        if (pr.shorthand && pr.key && pr.key.type === 'Identifier') into.add(pr.key.name)
        else collectPatternNames(pr.value, into)
      } else if (pr.type === 'RestElement') collectPatternNames(pr.argument, into)
    })
  }
}

/** 收集 `...mapGetters(['a','b'])` / `...mapState({x: ...})` 这类 spread 里的名字 */
function collectMappedNames(spreadPath) {
  const out = new Set()
  const arg = spreadPath.node.argument
  if (!arg || arg.type !== 'CallExpression') return out
  const callee = arg.callee
  const name = callee && callee.type === 'Identifier' ? callee.name : null
  if (!name || !/^map(Getters|State|Mutations|Actions|Fields)$/.test(name)) return out
  const a0 = arg.arguments[0]
  if (!a0) return out
  if (a0.type === 'ArrayExpression') {
    a0.elements.forEach(e => { if (e && e.type === 'StringLiteral') out.add(e.value) })
  } else if (a0.type === 'ObjectExpression') {
    a0.properties.forEach(pr => {
      const k = pr.key
      if (k && k.type === 'Identifier') out.add(k.name)
      else if (k && k.type === 'StringLiteral') out.add(k.value)
    })
  }
  return out
}

/**
 * 把一个元素上所有**需要求值的表达式**收集齐。
 *
 * 形状是探针实测出来的，不能靠猜：
 *  · 普通元素（如 <span :title="x">）的动态绑定在 **attrsList**，不在 el.props
 *    —— 只用 el.props 会漏掉一大片（`:title` 就漏了）；
 *  · `:class` / `:style` 被编译器**抽走**到 el.classBinding / el.styleBinding，attrsList 里没有；
 *  · `v-if` / `v-else-if` 被编译掉，**只在 attrsMap 里**；
 *  · `v-show` / `v-model` 等是 el.directives[].value；
 *  · 事件是 **对象**（按事件名索引），值可能是 {value} 或 {handlers:[{value}]} 两种形状；
 *  · 插值是 type===2 的 el.expression。
 */
function exprsOf(el) {
  const out = []
  const push = v => { if (typeof v === 'string' && v.trim()) out.push(v) }
  const SKIP = /^(v-for|slot-scope|slot|v-slot|v-slot:.*)$/
  // ⚠ attrsList 里**同时包含静态属性**（id="app"、type="primary"、placeholder="可多选"）。
  // 静态属性的值是**字面量**不是表达式，拿去求值会把 "app" / "primary" / "可多选"
  // 全当成标识符引用（实测炸出 2733 条）。只取动态绑定。
  ;(el.attrsList || []).forEach(a => {
    if (!a || !a.name || typeof a.value !== 'string') return
    if (SKIP.test(a.name)) return
    if (!(a.name.startsWith(':') || a.name.startsWith('@') || a.name.startsWith('v-'))) return
    push(a.value)
  })
  ;['v-if', 'v-else-if', 'v-html', 'v-text', 'v-model'].forEach(k => {
    if (el.attrsMap && el.attrsMap[k]) push(el.attrsMap[k])
  })
  push(el.classBinding)
  push(el.styleBinding)
  push(el.if)
  push(el.elseif)
  push(el.for)
  push(el.component)
  ;(el.directives || []).forEach(d => { if (d && d.value) push(d.value) })
  ;(el.props || []).forEach(p => { if (p && p.value) push(p.value) })
  if (el.events && typeof el.events === 'object') {
    Object.keys(el.events).forEach(k => {
      const ev = el.events[k]
      if (!ev) return
      if (typeof ev.value === 'string') push(ev.value)
      if (Array.isArray(ev.handlers)) ev.handlers.forEach(h => { if (h && h.value) push(h.value) })
    })
  }
  if (el.type === 2) push(el.expression)
  return out
}

/** 遍历 vue-template-compiler 的 AST，带作用域栈收集可疑引用 */
function scanTemplate(ast, declared, file, hits) {
  const report = (expr, line, scope) => {
    if (!expr || typeof expr !== 'string') return
    freeIdentifiers(expr).forEach(n => {
      if (scope.has(n)) return
      if (isBuiltinName(n)) return
      if (GLOBALS.has(n)) return
      if (PROTOTYPE_NAMES.has(n)) return
      if (declared.has(n)) return
      hits.push({ file, line: line || 0, name: n, expr: expr.replace(/\s+/g, ' ').slice(0, 90) })
    })
  }
  const walk = (el, scope) => {
    if (!el) return
    const inner = new Set(scope)
    if (el.for) {
      collectPatternNames({ type: 'Identifier', name: el.alias }, inner)
      if (el.iterator1) inner.add(el.iterator1)
      if (el.iterator2) inner.add(el.iterator2)
      report(el.for, el.forStart || el.attrsMap && el.attrsMap['v-for'], inner)
    }
    if (el.slotScope) {
      // slot-scope="scope" 或 v-slot="{ a, b }"
      const s = String(el.slotScope).trim()
      if (/^[A-Za-z_$][\w$]*$/.test(s)) inner.add(s)
      else if (s.startsWith('{') || s.startsWith('[')) {
        try {
          const pat = parser.parseExpression('(' + s + ')', PARSER_OPTS)
          collectPatternNames(pat, inner)
        } catch { /* 解构解析失败就不加 */ }
      }
    }
    if (el.scopedSlots) {
      Object.keys(el.scopedSlots).forEach(k => {
        const s = el.scopedSlots[k]
        const sl = new Set(inner)
        if (s.slotScope) {
          // vue-template-compiler 把作用域名放在 slotScope 里
          String(s.slotScope).split(',').forEach(v => {
            const t = v.trim()
            if (/^[A-Za-z_$][\w$]*$/.test(t)) sl.add(t)
          })
        }
        walk(s, sl)
      })
    }
    // 该元素上所有需要求值的表达式（形状见 exprsOf 的注释）
    exprsOf(el).forEach(e => report(e, undefined, inner))
    ;(el.children || []).forEach(c => walk(c, inner))
  }
  walk(ast, new Set())
}

const hits = []
let parsed = 0
const failed = []
let noTemplate = 0
let noScript = 0

/**
 * 自检（--selftest）：用一个内置样本证明"这条审计真的在工作"。
 *
 * 为什么必须有：这类审计最危险的失败模式不是误报，而是**静默失效** ——
 * 判据写错了、解析器挂了，输出一个漂亮的"0 命中"，看起来像"代码没问题"。
 * 我自己在这条审计上就写坏过三次（把静态属性值当表达式，一次炸出 2733 条）。
 * 所以：阳性必须被抓住、阴性必须不被误报，两边都要断言。
 */
function runSelfTest() {
  const fixtureScript = `
import { mapGetters } from 'vuex'
const fakeMap = () => ({ mappedFromStore: () => 1 })
export default {
  computed: { ...fakeMap(), ...mapGetters(['realGetter']) },
  data() { return { list: [], rows: [] } },
  methods: { local() { return 1 } }
}`
  const fixtureTemplate = `
<div>
  <span>{{ definitelyNotDeclared }}</span>
  <span :title="anotherMissingOne">x</span>
  <span v-for="(it, i) in list" :key="i">{{ it.name }}-{{ i }}</span>
  <el-table :data="rows"><template slot-scope="{ row, $index }">{{ row.name }}-{{ $index }}</template></el-table>
  <span>{{ mappedFromStore }}</span>
  <my-comp><template v-slot:default="{ scoped }">{{ scoped.whatever }}</template></my-comp>
</div>`
  const ast = parser.parse(fixtureScript, PARSER_OPTS)
  const declared = declaredOf(defaultObject(ast), '(selftest)', ast, 0)
  const compiled = compiler.compile(fixtureTemplate, { comments: false })
  const collected = []
  scanTemplate(compiled.ast, declared, '(selftest)', collected)
  const got = new Set(collected.map(h => h.name))

  // 阳性：这三个必须被抓到
  const MUST_CATCH = ['definitelyNotDeclared', 'anotherMissingOne', 'mappedFromStore']
  // 阴性：这些是作用域变量 / map* spread，绝不该报
  const MUST_NOT_CATCH = ['it', 'i', 'row', '$index', 'scoped', 'realGetter', 'list', 'rows', 'local']
  const missed = MUST_CATCH.filter(n => !got.has(n))
  const wrong = MUST_NOT_CATCH.filter(n => got.has(n))

  console.log('=== audit-template-refs 自检 ===')
  console.log('  应当抓到：', MUST_CATCH.join(', '))
  console.log('  实际抓到：', [...got].sort().join(', ') || '(空)')
  if (missed.length) console.log('  ❌ 漏报（阳性没抓到）：', missed.join(', '))
  if (wrong.length) console.log('  ❌ 误报（阴性被报出）：', wrong.join(', '))
  const pass = !missed.length && !wrong.length
  console.log(pass ? '  ✅ PASS —— 审计在工作' : '  ❌ FAIL —— 判据有问题，别信它的输出')
  process.exit(pass ? 0 : 1)
}

if (SELFTEST) runSelfTest()

for (const file of files) {
  const { ast, template, noScript: ns } = load(file)
  if (ns) { noScript++; continue }
  if (!ast) { failed.push(path.relative(SRC, file).replace(/\\/g, '/') + '  (脚本解析失败)'); continue }
  parsed++
  const rel = path.relative(SRC, file).replace(/\\/g, '/')
  const objPath = defaultObject(ast)
  if (!objPath) { failed.push(rel + '  (无 export default)'); continue }
  const declared = declaredOf(objPath, file, ast, 0)
  let compiled
  if (!template) { noTemplate++; continue } // 纯 JSX render 组件：没有 <template>，不适用本审计
  try { compiled = compiler.compile(template, { comments: false }) } catch (e) { compiled = { ast: null } }
  if (!compiled || !compiled.ast) { failed.push(rel + '  (模板编译失败)'); continue }
  scanTemplate(compiled.ast, declared, rel, hits)
}

// 去重（同一文件同一名字只报一次）
const uniq = new Map()
hits.forEach(h => {
  const k = h.file + '|' + h.name
  if (!uniq.has(k)) uniq.set(k, h)
})
const list = [...uniq.values()].sort((a, b) => a.file.localeCompare(b.file) || a.name.localeCompare(b.name))

console.log(`解析 ${parsed} 个文件；命中 ${list.length} 处（文件+名字去重后）`)
console.log(`跳过：无 <template> 的 ${noTemplate} 个（纯 JSX render 组件，本审计不适用）；无 <script> 的 ${noScript} 个（纯模板组件）`)
if (failed.length) {
  console.log(`\n跳过/失败 ${failed.length} 个：`)
  failed.slice(0, 20).forEach(f => console.log('  ' + f))
  if (failed.length > 20) console.log(`  ...另有 ${failed.length - 20} 个`)
}
console.log('\n明细：')
list.forEach(h => console.log(`  ${h.file}  {{ ${h.name} }}   例：${h.expr}`))
fs.writeFileSync(process.argv[3] || 'H:/dsh/ruoyiOA/.cache/template-refs.json',
  JSON.stringify({ hits: list, failed }, null, 2), 'utf8')
