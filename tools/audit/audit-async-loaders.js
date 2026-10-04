/**
 * 补审计：`async` 方法里 await 取数、失败会把 loading 永久留在 true。
 *
 * 前一版审计只认 `.then(...)` 链，会漏掉这一形态：
 *   async getList() {
 *     this.loading = true;
 *     const res = await listXxx(this.queryParams);
 *     this.list = res.rows;
 *     this.loading = false;      // ← await 抛错就到这里为止
 *   }                            // ← 没有 try/catch
 *
 * 判据：方法体含 `this.loading = true` + 含 `await` + **不含 `try`**
 */
const fs = require('fs')
const path = require('path')

const SRC = process.argv[2] || 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
const files = []
;(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'vform' || e.name === 'node_modules') continue
      walk(p)
    } else if (/\.vue$/.test(e.name)) files.push(p)
  }
})(SRC)

const hits = []
for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8')
  const lines = raw.split(/\r?\n/)
  const rel = path.relative(SRC, f).replace(/\\/g, '/')

  const defs = []
  lines.forEach((l, i) => {
    const m = l.match(/^\s{2,6}(async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{\s*$/)
    if (m) defs.push({ isAsync: !!m[1], name: m[2], line: i })
  })

  defs.forEach((d, k) => {
    const end = k + 1 < defs.length ? defs[k + 1].line : lines.length
    const body = lines.slice(d.line, end).join('\n')
    if (!/this\.loading\s*=\s*true/.test(body)) return
    if (!/\bawait\b/.test(body)) return
    if (/\btry\s*\{/.test(body)) return
    hits.push({ file: rel, line: d.line + 1, method: d.name, isAsync: d.isAsync })
  })
}

hits.sort((a, b) => a.file.localeCompare(b.file))
console.log(`async/await 取数无 try/catch（loading 会永久为 true）: ${hits.length} 处\n`)
hits.forEach(h => console.log(`  ${h.file}:${h.line}   ${h.method}()${h.isAsync ? '' : '   [注意：方法本身未标 async]'}`))
fs.writeFileSync(process.argv[3] || 'H:/dsh/ruoyiOA/.cache/audit-out.json', JSON.stringify(hits, null, 2), 'utf8')
