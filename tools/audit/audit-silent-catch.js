/**
 * 闈欓粯 catch 鍏ㄩ噺娓呯偣锛氫负姣忎竴澶勮緭鍑鸿冻澶熶笂涓嬫枃锛屼緵浜哄伐鍒嗙被銆? *
 * 闇€瑕佸尯鍒嗙殑涓夌被锛堜笂涓€杞凡韪╄繃锛夛細
 *   A 涓绘暟鎹姞杞?  -> 澶辫触蹇呴』鍙锛堥敊璇€侊級锛屽惁鍒?澶辫触"鍜?娌℃暟鎹?闀垮緱涓€鏍? *   B 娆¤/闄勫甫鍔犺浇 -> 涓嬫媺瀛楀吀銆侀€夐」鍗℃暟鎹紱鍙鎬ц鏈夛紝浣嗕笉蹇呬笂鏁村潡閿欒鎬? *   C $confirm 鍙栨秷 -> 姝ｅ父鎺у埗娴侊紝**涓嶈鍔?*
 *   D confirm+璇锋眰鍚屼竴閾?-> 涓€涓?catch 鍚屾椂鍚炰簡"鍙栨秷"鍜?璇锋眰澶辫触"锛屽繀椤绘媶
 *   E 鏃?catch      -> 鏈鐞嗙殑 Promise 鎷掔粷锛岀晫闈㈡鏃犲弽棣? */
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
    } else if (/\.(vue|js)$/.test(e.name)) files.push(p)
  }
})(SRC)

// 闈欓粯 catch锛氬嚱鏁颁綋涓虹┖锛堝厑璁哥┖鐧?娉ㄩ噴锛?const SILENT = /\.catch\(\s*(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>\s*\{\s*(?:\/\*[\s\S]*?\*\/|\/\/[^\n]*)?\s*\}\s*\)/g

const sites = []
for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8')
  const lines = raw.split(/\r?\n/)
  let m
  SILENT.lastIndex = 0
  while ((m = SILENT.exec(raw))) {
    const upto = raw.slice(0, m.index)
    const line = upto.split('\n').length
    // 鍚戜笂鎵捐繖鏉¤鍙ラ摼鐨勮捣鐐癸細鏈€杩戠殑绌鸿鎴栦笂涓€鍙ョ粨灏?    const before = lines.slice(Math.max(0, line - 12), line)
    const chain = before.join('\n')
    const isConfirm = /\$confirm\s*\(/.test(chain)
    const hasThen = /\.then\s*\(/.test(chain)
    // 鏄惁鍦?confirm 涔嬪悗杩樻帴浜嗚姹傦紙鍚屼竴閾撅級
    const confirmThenRequest = isConfirm && hasThen
    // 鐚滄祴鎵€灞炴柟娉?    let method = ''
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

// 鍚岀被褰掑苟缁熻
const byFile = {}
sites.forEach(s => { (byFile[s.file] = byFile[s.file] || []).push(s) })

console.log(`闈欓粯 catch 鍏?${sites.length} 澶勶紝鍒嗗竷鍦?${Object.keys(byFile).length} 涓枃浠禱n`)
console.log(`鍏朵腑锛氱枒浼?$confirm 閾?${sites.filter(s => s.isConfirm).length} 澶刞)
console.log(`      confirm+璇锋眰鍚岄摼锛堥渶鎷嗭級 ${sites.filter(s => s.confirmThenRequest).length} 澶刓n`)

const onlyConfirm = sites.filter(s => s.isConfirm)
const others = sites.filter(s => !s.isConfirm)
console.log('########## 鐤戜技"鐢ㄦ埛鍙栨秷"锛堝厛鍋囧畾涓嶅姩锛岀◢鍚庨€愪釜浜哄伐纭锛?##########')
onlyConfirm.forEach(s => console.log(`  ${s.file}:${s.line}  ${s.method}()`))
console.log('\n########## 闈?confirm 鐨勶紙涓昏宸ヤ綔瀵硅薄锛?##########')
others.forEach(s => {
  console.log(`鈹€ ${s.file}:${s.line}   鏂规硶 ${s.method}()`)
  s.ctx.forEach(c => console.log(`      ${c}`))
})

fs.writeFileSync((process.argv[3] || 'H:/dsh/ruoyiOA/.cache/silent-catch.json'), JSON.stringify(sites, null, 2), 'utf8')
console.log('\n瀹屾暣娓呭崟宸插啓鍏?.cache/silent-catch.json')
