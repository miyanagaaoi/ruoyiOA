/**
 * AST 版：每个 `loading` 类标志都必须有**配对的失败复位**，且成功路径的复位必须**无条件**。
 *
 * 为什么必须上 AST：手写括号栈解析器会错判 RuoYi 的链式写法 ——
 *   listXxx(...).then((r) => { ... }).catch((e) => { ... })
 * `}).catch((e) => {` 这一行净开括号数为 0，catch 帧根本入不了栈，
 * catch 里的复位会被算成 then 的，于是刚修好的文件也被报成缺陷（实测 78 条里绝大多数是误报）。
 *
 * 判据（对每个 `this.<flag> = true`，flag 匹配 /loading/i）：
 *   failOk    = 存在复位位于 `finally`，或位于 `catch` 子句且其内无 if/循环包裹
 *   successOk = 存在复位位于 `finally`、`.then(` 回调、`try` 块或普通函数体，
 *               且其内无 if/循环包裹；
 *               但 **async 函数里出现在 `await` 之后的普通复位不算数**（await 抛错就到不了）
 *   另：`if (A) { 复位 } else { 复位 }` 两侧都有复位 -> 视为无条件
 *
 * 用法：node audit-loading-pairs.js [srcRoot]
 */
const fs = require('fs')
const path = require('path')

const SRC = process.argv[2] || 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'

/**
 * 本脚本可能放在仓库之外（例如 tools/audit/），那时 require('@babel/parser') 解析不到
 * —— 模块解析是从脚本所在目录逐级向上找 node_modules。所以从 SRC 向上定位可用的
 * node_modules，再按绝对路径 require。
 */
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
// 依赖定位的兜底顺序：环境变量 -> 从 SRC 向上找 -> 默认仓库路径。
// 自检时 SRC 指向临时目录（那里没有 node_modules），所以必须有后两级兜底。
const DEFAULT_REPO_SRC = 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
const NM = process.env.DSH_AUDIT_NM || findNodeModules(SRC) || findNodeModules(DEFAULT_REPO_SRC)
if (!NM) {
  console.error('找不到可用的 node_modules/@babel/parser（可用 DSH_AUDIT_NM 指定路径）')
  process.exit(1)
}
const parser = require(path.join(NM, '@babel', 'parser'))
const traverse = require(path.join(NM, '@babel', 'traverse')).default

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')
  process.env.DSH_AUDIT_NM = NM // 子进程在临时目录里跑，要把依赖位置传下去
  const dir = makeFixtureDir({
    // 阳性：成功路径的复位移在 if (res.code === 200) 内 -> code 非 200 时永久转圈
    'bad-conditional.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX().then((res) => {
        if (res.code === 200) { this.list = res.rows; this.loading = false; }
      }).catch((err) => { this.loading = false; });
    }
  }
}
</script>`,
    // 阳性2：失败路径根本没有复位（没有 catch）
    'bad-nocatch.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX().then((res) => { this.list = res.rows; this.loading = false; });
    }
  }
}
</script>`,
    // 阴性：finally 复位，天然覆盖两条路径
    'good-finally.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX().then((res) => { this.list = res.rows; }).catch((err) => { this.list = []; })
        .finally(() => { this.loading = false; });
    }
  }
}
</script>`,
    // 阴性：then 与 catch 各自无条件复位
    'good-both.vue': `<script>
export default {
  data() { return { loading: false } },
  methods: {
    getList() {
      this.loading = true;
      listX().then((res) => { this.list = res.rows; this.loading = false; })
        .catch((err) => { this.list = []; this.loading = false; });
    }
  }
}
</script>`
  })
  const r = runSelfOn(__filename, dir)
  const hits = Array.isArray(r.json) ? r.json : []
  const files = hits.map(h => h.file)
  finish('audit-loading-pairs', dir, [
    { label: '脚本能跑通', pass: r.code === 0, detail: r.code === 0 ? '' : r.out.slice(0, 300) },
    { label: '抓到 bad-conditional.vue', pass: files.includes('bad-conditional.vue'), detail: '实际：' + (files.join(', ') || '(空)') },
    { label: '抓到 bad-nocatch.vue', pass: files.includes('bad-nocatch.vue') },
    { label: '没有误报 good-finally.vue', pass: !files.includes('good-finally.vue') },
    { label: '没有误报 good-both.vue', pass: !files.includes('good-both.vue') },
    { label: '命中数正好 2', pass: hits.length === 2, detail: '实际 ' + hits.length }
  ])
}

const PARSER_OPTS = {
  sourceType: 'module',
  errorRecovery: true,
  plugins: ['optionalChaining', 'nullishCoalescingOperator', 'objectRestSpread',
    'classProperties', 'classPrivateProperties', 'dynamicImport', 'asyncGenerators', 'topLevelAwait']
}

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

function extractScript(raw, ext) {
  if (ext === '.js') return raw
  const m = raw.match(/<script[^>]*>([\s\S]*?)<\/script>/)
  return m ? m[1] : null
}

/** 从 p 向上走到 stopPath（不含）为止，返回第一个满足 pred 的**路径** */
function walkUp(p, stopPath, pred) {
  let cur = p
  while (cur && cur !== stopPath) {
    if (pred(cur)) return cur
    cur = cur.parentPath
  }
  return null
}

/** 同上，但返回谓词的**返回值**（用于取上下文名字这类带值判定） */
function walkUpValue(p, stopPath, pred) {
  let cur = p
  while (cur && cur !== stopPath) {
    const v = pred(cur)
    if (v) return v
    cur = cur.parentPath
  }
  return null
}

/** 列出 p 到 stopPath 之间所有的条件块（if/循环/switch/三元），返回节点数组 */
function conditionalAncestors(p, stopPath) {
  const out = []
  let cur = p
  while (cur && cur !== stopPath) {
    const n = cur.node
    if (cur.isIfStatement() || cur.isForStatement() || cur.isForInStatement() || cur.isForOfStatement() ||
      cur.isWhileStatement() || cur.isDoWhileStatement() || cur.isSwitchCase() || cur.isConditionalExpression()) {
      out.push(n)
    }
    cur = cur.parentPath
  }
  return out
}

/**
 * 复位是否“有条件”。
 * ⚠ 必须排除**同时包住置位与复位**的条件块：形如
 *     if (userId) { this.loading = true; getX().then(...).catch(...) }
 * 这里的 if 与“复位是否有保证”无关，若算进去会把正确的代码误报成缺陷。
 */
function isConditionalReset(resetPath, setPath, fnPath) {
  const condSet = conditionalAncestors(setPath, fnPath)
  const condReset = conditionalAncestors(resetPath, fnPath)
  return condReset.some(n => !condSet.includes(n))
}

/**
 * 复位点所处的“上下文体”。
 * ⚠ 边界必须是被分析的**方法**，不能用 getFunctionParent() ——
 * 后者返回最近的函数，而 .then/.catch 的回调本身就是函数，
 * 这样会在回调处就停下，永远走不到 CallExpression，于是所有复位都被误判成 'body'。
 */
function contextOf(p, fnPath) {
  const ctx = walkUpValue(p, fnPath, cur => {
    const parent = cur.parentPath
    if (!parent) return false
    // try 的 finalizer / block
    if (parent.isTryStatement()) {
      const n = parent.node
      if (n.finalizer && n.finalizer === cur.node) return 'finally'
      if (n.block && n.block === cur.node) return 'tryBlock'
    }
    if (parent.isCatchClause()) return 'catchClause'
    // .then / .catch / .finally 回调
    if (parent.isCallExpression()) {
      const callee = parent.node.callee
      const prop = callee && callee.type === 'MemberExpression' && callee.property && callee.property.name
      if ((prop === 'then' || prop === 'catch' || prop === 'finally') &&
        parent.node.arguments.some(a => a === cur.node)) {
        return prop === 'catch' ? 'catchCb' : prop === 'then' ? 'thenCb' : 'finally'
      }
    }
    // try 块内部（再往上一层是 TryStatement）
    if (cur.isBlockStatement() && parent.isTryStatement()) {
      const n = parent.node
      if (n.block === cur.node) return 'tryBlock'
      if (n.finalizer === cur.node) return 'finally'
    }
    return false
  })
  return ctx || 'body'
}

/** 若复位在 if 的某个分支里，检查对侧分支是否也有复位（两侧都有 -> 无条件） */
function bothBranchesReset(p, flag, stopPath) {
  const ifPath = walkUp(p, stopPath, q => q.isIfStatement())
  if (!ifPath) return false
  const n = ifPath.node
  const contains = (block, node) => !!block && block.body &&
    block.body.some(s => s === node || (s.start <= node.start && s.end >= node.end))
  const inConsequent = contains(n.consequent, p.node)
  const other = inConsequent ? n.alternate : n.consequent
  if (!other) return false
  let found = false
  const check = node => {
    if (!node || found || typeof node !== 'object') return
    if (node.type === 'AssignmentExpression' &&
      node.left.type === 'MemberExpression' && node.left.object.type === 'ThisExpression' &&
      node.left.property && node.left.property.name === flag &&
      node.right.type === 'BooleanLiteral' && node.right.value === false) { found = true; return }
    for (const k of Object.keys(node)) {
      const v = node[k]
      if (Array.isArray(v)) v.forEach(check)
      else if (v && typeof v === 'object' && v.type) check(v)
    }
  }
  check(other)
  return found
}

const hits = []
let parsed = 0, parseFail = 0

for (const f of files) {
  const ext = path.extname(f).toLowerCase()
  const raw = fs.readFileSync(f, 'utf8')
  const code = extractScript(raw, ext)
  if (!code || !/loading/i.test(code)) continue

  let ast
  try { ast = parser.parse(code, PARSER_OPTS) } catch (e) { parseFail++; continue }
  parsed++
  const rel = path.relative(SRC, f).replace(/\\/g, '/')

  const handleFn = (fnPath) => {
    const flagSets = new Map() // flag -> [nodePath]
    const flagResets = new Map()
    fnPath.traverse({
      AssignmentExpression: ap => {
        const n = ap.node
        if (n.left.type !== 'MemberExpression' || n.left.object.type !== 'ThisExpression') return
        const flag = n.left.property && n.left.property.name
        if (!flag || !/loading/i.test(flag)) return
        if (n.right.type !== 'BooleanLiteral') return
        const bucket = n.right.value ? flagSets : flagResets
        if (!bucket.has(flag)) bucket.set(flag, [])
        bucket.get(flag).push(ap)
      }
    })
    if (!flagSets.size) return

    const fnNode = fnPath.node
    const isAsync = fnNode.async || (fnNode.body && fnNode.body.type === 'BlockStatement' &&
      /await/.test(code.slice(fnNode.body.start, fnNode.body.end)))
    const firstAwait = (() => {
      let at = Infinity
      fnPath.traverse({ AwaitExpression: ap => { at = Math.min(at, ap.node.start) } })
      return at
    })()

    const name = fnNode.key ? (fnNode.key.name || fnNode.key.value) : (fnNode.id && fnNode.id.name) || '(anonymous)'

    for (const [flag, sets] of flagSets) {
      const resets = flagResets.get(flag) || []
      const setPath = sets[0]
      const judged = resets.map(rp => {
        const ctx = contextOf(rp, fnPath)
        const cond = isConditionalReset(rp, setPath, fnPath)
        const both = cond ? bothBranchesReset(rp, flag, fnPath) : false
        const afterAwait = rp.node.start > firstAwait
        return { ctx, unconditional: !cond || both, afterAwait }
      })
      const finallyOk = judged.some(j => j.ctx === 'finally')
      const failOk = finallyOk || judged.some(j => (j.ctx === 'catchCb' || j.ctx === 'catchClause') && j.unconditional)
      const successOk = finallyOk || judged.some(j => {
        if (!j.unconditional) return false
        if (j.ctx === 'thenCb' || j.ctx === 'tryBlock' || j.ctx === 'body' || j.ctx === 'catchClause') {
          // async 里 await 之后的普通复位不算数
          if (isAsync && j.afterAwait && j.ctx === 'body') return false
          return true
        }
        return false
      })
      if (!failOk || !successOk) {
        hits.push({
          file: rel, line: fnNode.loc.start.line, method: name, flag, isAsync,
          resets: resets.length,
          ctxs: [...new Set(judged.map(j => j.ctx))].join(','),
          why: [!failOk ? '失败路径无复位' : null, !successOk ? '成功路径复位不保证' : null].filter(Boolean).join(' + ')
        })
      }
    }
  }

  traverse(ast, {
    ObjectMethod: handleFn,
    ClassMethod: handleFn,
    ObjectProperty: p => { if (p.get('value').isFunction()) handleFn(p.get('value')) }
  })
}

hits.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)
console.log(`解析成功 ${parsed} 个文件（失败 ${parseFail}），命中 ${hits.length} 处\n`)
hits.forEach(h => console.log(`  ${h.file}:${h.line}  ${h.method}()  [${h.flag}]  ${h.why}   (复位 ${h.resets} 个 @ ${h.ctxs}${h.isAsync ? '，async' : ''})`))
fs.writeFileSync(process.argv[3] || 'H:/dsh/ruoyiOA/.cache/audit-out.json', JSON.stringify(hits, null, 2), 'utf8')
