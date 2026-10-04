/**
 * 琛ュ璁★細`async` 鏂规硶閲?await 鍙栨暟銆佸け璐ヤ細鎶?loading 姘镐箙鐣欏湪 true銆? *
 * 鍓嶄竴鐗堝璁″彧璁?`.then(...)` 閾撅紝浼氭紡鎺夎繖涓€褰㈡€侊細
 *   async getList() {
 *     this.loading = true;
 *     const res = await listXxx(this.queryParams);
 *     this.list = res.rows;
 *     this.loading = false;      // 鈫?await 鎶涢敊灏卞埌杩欓噷涓烘
 *   }                            // 鈫?娌℃湁 try/catch
 *
 * 鍒ゆ嵁锛氭柟娉曚綋鍚?`this.loading = true` + 鍚?`await` + **涓嶅惈 `try`**
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
console.log(`async/await 鍙栨暟鏃?try/catch锛坙oading 浼氭案涔呬负 true锛? ${hits.length} 澶刓n`)
hits.forEach(h => console.log(`  ${h.file}:${h.line}   ${h.method}()${h.isAsync ? '' : '   [娉ㄦ剰锛氭柟娉曟湰韬湭鏍?async]'}`))
fs.writeFileSync('H:/dsh/ruoyiOA/.cache/async-loaders.json', JSON.stringify(hits, null, 2), 'utf8')
