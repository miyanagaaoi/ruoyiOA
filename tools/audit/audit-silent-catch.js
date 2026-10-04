/**
 * 静默 catch 全量清点：为每一处输出足够上下文，供人工分类。
 *
 * 需要区分的三类（上一轮已踩过）：
 *   A 主数据加载   -> 失败必须可见（错误态），否则"失败"和"没数据"长得一样
 *   B 次要/附带加载 -> 下拉字典、选项卡数据；可见性要有，但不必上整块错误态
 *   C $confirm 取消 -> 正常控制流，**不要动**
 *   D confirm+请求同一链 -> 一个 catch 同时吞了"取消"和"请求失败"，必须拆
 *   E 无 catch      -> 未处理的 Promise 拒绝，界面毫无反馈
 */
const fs = require('fs')
const path = require('path')

if (process.argv.includes('--selftest')) {
  const { makeFixtureDir, runSelfOn, finish } = require('./_selftest')
  const dir = makeFixtureDir({
    // 3 处应当被清点出来：空 catch、只有空格的 catch、只有注释的 catch
    'three.vue': `<script>
export default {
  methods: {
    a() { delX().catch(() => {}); },
    b() { delY().catch(() => { }); },
    c() { delZ().catch(() => {
      // 取消
    }); }
  }
}
</script>`,
    // 阴性：catch 里有语句 -> 不算"静默"
    'none.vue': `<script>
export default {
  methods: {
    a() { delX().catch(() => { this.loading = false; }); },
    b() { delY().catch((e) => { console.warn(e); }); },
    c() { delZ().then(() => {}); }
  }
}
</script>`
  })
  const r = runSelfOn(__filename, dir)
  const hits = Array.isArray(r.json) ? r.json : []
  const byFile = {}
  hits.forEach(h => { byFile[h.file] = (byFile[h.file] || 0) + 1 })
  finish('audit-silent-catch', dir, [
    { label: '脚本能跑通', pass: r.code === 0, detail: r.code === 0 ? '' : r.out.slice(0, 200) },
    { label: 'three.vue 清点出 3 处', pass: byFile['three.vue'] === 3, detail: '实际 ' + (byFile['three.vue'] || 0) },
    { label: 'none.vue 一处都不报', pass: !byFile['none.vue'], detail: '实际 ' + (byFile['none.vue'] || 0) },
    { label: '总数正好 3', pass: hits.length === 3, detail: '实际 ' + hits.length }
  ])
}

const SRC = process.argv[2] || 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
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

// 静默 catch：函数体为空（允许空白/注释）
const SILENT = /\.catch\(\s*(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>\s*\{\s*(?:\/\*[\s\S]*?\*\/|\/\/[^\n]*)?\s*\}\s*\)/g

const sites = []
for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8')
  const lines = raw.split(/\r?\n/)
  let m
  SILENT.lastIndex = 0
  while ((m = SILENT.exec(raw))) {
    const upto = raw.slice(0, m.index)
    const line = upto.split('\n').length
    // 向上找这条语句链的起点：最近的空行或上一句结尾
    const before = lines.slice(Math.max(0, line - 12), line)
    const chain = before.join('\n')
    const isConfirm = /\$confirm\s*\(/.test(chain)
    const hasThen = /\.then\s*\(/.test(chain)
    // 是否在 confirm 之后还接了请求（同一链）
    const confirmThenRequest = isConfirm && hasThen
    // 猜测所属方法
    let method = ''
    for (let i = line - 1; i >= 0; i--) {
      const mm = lines[i].match(/^\s{2,6}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/)
      if (mm) { method = mm[1]; break }
    }
    sites.push({
      file: path.relative(SRC, f).replace(/\\/g, '/'),
      line, method,
      isConfirm, hasThen, confirmThenRequest,
      ctx: before.slice(-6).map(s => s.trim()).filter(Boolean)
    })
  }
}

// 同类归并统计
const byFile = {}
sites.forEach(s => { (byFile[s.file] = byFile[s.file] || []).push(s) })

console.log(`静默 catch 共 ${sites.length} 处，分布在 ${Object.keys(byFile).length} 个文件\n`)
console.log(`其中：疑似 $confirm 链 ${sites.filter(s => s.isConfirm).length} 处`)
console.log(`      confirm+请求同链（需拆） ${sites.filter(s => s.confirmThenRequest).length} 处\n`)

const onlyConfirm = sites.filter(s => s.isConfirm)
const others = sites.filter(s => !s.isConfirm)
console.log('########## 疑似"用户取消"（先假定不动，稍后逐个人工确认） ##########')
onlyConfirm.forEach(s => console.log(`  ${s.file}:${s.line}  ${s.method}()`))
console.log('\n########## 非 confirm 的（主要工作对象） ##########')
others.forEach(s => {
  console.log(`─ ${s.file}:${s.line}   方法 ${s.method}()`)
  s.ctx.forEach(c => console.log(`      ${c}`))
})

fs.writeFileSync(process.argv[3] || 'H:/dsh/ruoyiOA/.cache/audit-out.json', JSON.stringify(sites, null, 2), 'utf8')
console.log('\n完整清单已写入 .cache/silent-catch.json')
