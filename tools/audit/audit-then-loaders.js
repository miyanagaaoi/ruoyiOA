/**
 * 瀹¤"涓绘暟鎹姞杞芥病鏈?catch"鈥斺€旂湡姝ｇ殑鏃犻檺杞湀缂洪櫡銆? *
 * 鍒ゆ嵁锛氫竴涓柟娉曚綋鍐呮弧瓒冲叏閮ㄤ笁鏉? *   1. 鍑虹幇 `this.loading = true`
 *   2. 鏈?`.then(` 涓斿叾鍚庤窡浜?`this.loading = false`锛堣鏄庡畠渚濊禆鎴愬姛璺緞澶嶄綅锛? *   3. **鏁翠釜鏂规硶浣撻噷娌℃湁 `.catch(`**
 *
 * 杩欑被鍦ㄨ姹傚け璐ユ椂 loading 姘歌繙涓?true -> 琛ㄦ牸姘镐箙杞湀涓旀畫鐣欐棫鏁版嵁銆? */
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

  // 鎵惧嚭鎵€鏈夋柟娉曞畾涔夎
  const defs = []
  lines.forEach((l, i) => {
    const m = l.match(/^\s{2,6}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{\s*$/)
    if (m) defs.push({ name: m[1], line: i, indent: l.match(/^\s*/)[0].length })
  })

  defs.forEach((d, k) => {
    const end = k + 1 < defs.length ? defs[k + 1].line : lines.length
    const body = lines.slice(d.line, end).join('\n')
    if (!/this\.loading\s*=\s*true/.test(body)) return
    if (!/\.then\s*\(/.test(body)) return
    if (/\.catch\s*\(/.test(body)) return
    // 纭瀹冪‘瀹炰緷璧栨垚鍔熻矾寰勫浣?    const resetsInThen = /\.then\s*\([\s\S]*?this\.loading\s*=\s*false/.test(body)
    hits.push({ file: rel, line: d.line + 1, method: d.name, resetsInThen })
  })
}

hits.sort((a, b) => a.file.localeCompare(b.file))
console.log(`涓绘暟鎹姞杞?鏃?catch 鈫?姘镐箙杞湀"鍏?${hits.length} 澶勶紝鍒嗗竷鍦?${new Set(hits.map(h => h.file)).size} 涓枃浠禱n`)
hits.forEach(h => console.log(`  ${h.file}:${h.line}   ${h.method}()`))
fs.writeFileSync('H:/dsh/ruoyiOA/.cache/no-catch-loaders.json', JSON.stringify(hits, null, 2), 'utf8')
