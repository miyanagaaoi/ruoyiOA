/**
 * 审计：`var(--oa-...)` 用到的主题令牌是否**真的声明过**。
 *
 * 为什么值得查（2026-10-05 由 8.4 顺手发现、随后全仓扫出 3 处）：
 * 写 `var(--oa-color-text-secondary, #909399)` 这种"带硬编码回退值"的样式时，
 * 如果令牌名**根本不存在**，CSS 会**静默**走回退值 —— 页面上看不出任何异常，
 * 但那处颜色就永远不跟随主题令牌（DEV-ENV §6.9/§6.11 要求的「不写裸色值」被绕过），
 * 将来换主题/换色时会出现"别的都变了、这几处没变"的串色，且极难定位。
 * 更坏的是：它看起来"用了令牌"，审计肉眼审不出来。
 *
 * 判据：
 *   declared = oa-tokens.scss 里 `--oa-xxx: ...;` 声明的名字集合
 *   used     = src 下 *.vue/*.scss/*.css/*.js 里出现的 `var(--oa-xxx` 名字集合
 *   命中(oranges) = used − declared   → 门禁目标，必须为 0
 *   另有 unused = declared − used     → 仅信息（不门禁：预留令牌是合理的）
 *
 * ⚠ 只扫**源码目录**（默认前端 src），不扫 public/ 下的第三方静态 CSS，
 *   也不扫 oa-tokens.scss 自身（它是声明的真源）。
 *
 * 用法：node audit-theme-tokens.js [srcRoot] [outJson]
 *       node audit-theme-tokens.js --selftest
 */
const fs = require('fs')
const path = require('path')

const SRC = process.argv.find(a => !a.startsWith('-') && a !== process.argv[0] && a !== process.argv[1]) || 'F:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'
const OUT = process.argv[3] && process.argv[3].endsWith('.json') ? process.argv[3] : null

const TOKENS_FILE = 'assets/styles/oa-tokens.scss'
const SCAN_EXT = new Set(['.vue', '.scss', '.css', '.js'])
const DECL_RE = /(--oa-[a-z0-9-]+)\s*:/g
const USE_RE = /var\(\s*(--oa-[a-z0-9-]+)/g

function walk(dir, out = []) {
  let entries = []
  try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { return out }
  for (const e of entries) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'dist') continue
      walk(p, out)
    } else if (SCAN_EXT.has(path.extname(e.name))) {
      out.push(p)
    }
  }
  return out
}

/** 收集声明（真源：oa-tokens.scss） */
function collectDeclared(srcRoot) {
  const f = path.join(srcRoot, TOKENS_FILE)
  if (!fs.existsSync(f)) { throw new Error(`找不到令牌真源：${f}`) }
  const text = fs.readFileSync(f, 'utf8')
  const set = new Set()
  let m
  while ((m = DECL_RE.exec(text))) set.add(m[1])
  return set
}

/**
 * 剥掉注释再找使用点。
 *
 * 为什么必要：本项目惯例是在样式块里**解释**某个令牌为什么被换掉
 * （例如 `src/views/ctms/partner/index.vue` 的样式注释里写「之前这里用的是
 * `var(--oa-xxx, …)`，该令牌并不存在」）。如果不过滤注释，这句**说明文字**会被
 * 当成真实使用点，审计就会因为"文档在描述这个坑"而永远红 —— 这与
 * `DEV-ENV.md` §6 第 50 条里「日期门禁被 README 的说明文字打红」是同一个坑，
 * 所以这里直接把注释剥掉，而不是让源码去迁就审计的文本匹配。
 *
 * 覆盖块注释、行注释与 HTML 注释三类；字符串字面量不处理（本审计只找 `var(--oa-`，
 * 出现在字符串里的概率与影响都可忽略，且那本来就该被当成使用点）。
 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
}

/** 收集使用点（排除令牌真源自身；**先剥注释**，避免注释里提到的令牌名被当成使用点） */
function collectUsed(srcRoot, declaredFile) {
  const used = new Map()
  for (const file of walk(srcRoot)) {
    if (path.resolve(file) === path.resolve(declaredFile)) continue
    const text = stripComments(fs.readFileSync(file, 'utf8'))
    let m
    while ((m = USE_RE.exec(text))) {
      const t = m[1]
      if (!used.has(t)) used.set(t, [])
      used.get(t).push(path.relative(srcRoot, file).split(path.sep).join('/'))
    }
  }
  return used
}

/* ------------------------------ 自检 ------------------------------ */
if (process.argv.includes('--selftest')) {
  const os = require('os')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oa-token-audit-'))
  const stylesDir = path.join(dir, 'assets', 'styles')
  fs.mkdirSync(stylesDir, { recursive: true })
  fs.writeFileSync(path.join(stylesDir, 'oa-tokens.scss'),
    ':root {\n  --oa-color-primary: #1f5ae0;\n  --oa-color-ink: #14181f;\n  --oa-color-border-ok: #e2e5ea;\n  --oa-color-text-secondary: #5f6b7a;\n}\n', 'utf8')
  // 阳性：用了**没声明**的令牌（本次真实踩到的那一类）——必须被抓到
  fs.writeFileSync(path.join(dir, 'risky.vue'),
    '<style>\n.a { color: var(--oa-color-fill-light, #f5f7fa); }\n.b { border: 1px solid var(--oa-color-hairline-strong); }\n</style>\n', 'utf8')
  // 阴性 1：只用声明过的令牌（不该被抓）
  fs.writeFileSync(path.join(dir, 'good.vue'),
    '<style>\n.c { color: var(--oa-color-ink, #303133); }\n</style>\n', 'utf8')
  // 阴性 2（回归守卫）：**注释里**提到"未声明的令牌名"不该被当成使用点 —— 这正是
  //   本仓库的真实场景（样式注释在解释某个令牌为什么被换掉），也是 DEV-ENV §6 第 50 条同源坑
  fs.writeFileSync(path.join(dir, 'comment-only.vue'),
    '<style>\n/* 之前这里用的是 var(--oa-color-text-secondary, …) —— 该令牌并不存在 */\n/* 行注释形状 var(--oa-color-not-declared-2) */\n.d { color: var(--oa-color-border-ok); }\n</style>\n', 'utf8')

  const declared = collectDeclared(dir)
  const used = collectUsed(dir, path.join(stylesDir, 'oa-tokens.scss'))
  const orphans = [...used.keys()].filter(t => !declared.has(t)).sort()
  const okDeclared = declared.has('--oa-color-primary') && declared.has('--oa-color-ink')
  const expected = '--oa-color-fill-light,--oa-color-hairline-strong'
  const okOrphans = orphans.join(',') === expected
  const okComment = !orphans.includes('--oa-color-text-secondary') && !orphans.includes('--oa-color-not-declared-2')
  console.log(`[selftest] declared 收集正确（--oa-color-primary/--oa-color-ink）: ${okDeclared ? 'PASS' : 'FAIL'}`)
  console.log(`[selftest] 阳性命中 2 个且与期望一致（${expected}）: ${okOrphans ? 'PASS' : 'FAIL'}（实际 ${orphans.join(',') || '空'}）`)
  console.log(`[selftest] 阴性：声明过的 --oa-color-ink 未被误报: ${orphans.includes('--oa-color-ink') ? 'FAIL' : 'PASS'}`)
  console.log(`[selftest] 回归守卫：注释里的令牌名不被当成使用点: ${okComment ? 'PASS' : 'FAIL'}`)
  fs.rmSync(dir, { recursive: true, force: true })
  const pass = okDeclared && okOrphans && okComment && !orphans.includes('--oa-color-ink')
  console.log(pass ? '[selftest] 全部通过' : '[selftest] 存在失败项')
  process.exit(pass ? 0 : 1)
}

/* ------------------------------ 正式跑 ------------------------------ */
let declared, used
try {
  declared = collectDeclared(SRC)
  used = collectUsed(SRC, path.join(SRC, TOKENS_FILE))
} catch (e) {
  console.error('审计无法完成：' + e.message)
  process.exit(1)
}

const orphans = [...used.keys()].filter(t => !declared.has(t))
  .sort()
  .map(token => ({ token, files: [...new Set(used.get(token))].sort() }))
const unused = [...declared].filter(t => !used.has(t)).sort()
const result = {
  src: SRC,
  declaredCount: declared.size,
  usedCount: used.size,
  orphans,
  unused
}

console.log(`扫描根：${SRC}`)
console.log(`声明令牌 ${declared.size} 个；被使用 ${used.size} 个`)
if (orphans.length) {
  console.log(`\n❌ 使用了未声明的令牌 ${orphans.length} 个（CSS 会静默走硬编码回退值，不跟随主题）：`)
  for (const o of orphans) console.log(`   ${o.token}  ← ${o.files.join(', ')}`)
} else {
  console.log('\n✅ 所有 var(--oa-*) 引用的令牌都已声明')
}
if (unused.length) console.log(`\n（信息）预留但未使用的令牌 ${unused.length} 个：${unused.join(', ')}`)

if (OUT) fs.writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8')
process.exit(orphans.length ? 1 : 0)
