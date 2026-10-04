/**
 * 审计总闸门：一次跑完 tools/audit 下的全部审计，并给出可判定的结论。
 *
 * 设计要点（都是前面几轮踩出来的经验）：
 *  1. **每个审计先跑 --selftest**（若它支持）。审计最危险的失败模式不是误报，
 *     而是静默失效 —— 输出一个漂亮的"0 命中"看起来像"代码没问题"。
 *     不能自证其工作的审计，在这里会被**显式标为 untrusted**，而不是当成通过。
 *  2. **数量对比基线**（tools/audit/baseline.json）：任何数字变化都必须是有意识的
 *     （要么修好了去 --update，要么是回归）。这样"0"才有意义 —— 它证明的是
 *     "没有回归"，而不是"脚本今天恰好没输出"。
 *  3. 门禁语义分三种：
 *     zero      —— 必须为 0（真实缺陷数，涨了就是回归）
 *     baseline  —— 必须等于基线（已判定的误报数 / 信息性计数）
 *     info      —— 只打印不门禁
 *
 * 用法：
 *   node tools/audit/run-all.js              # 跑全部并对比基线
 *   node tools/audit/run-all.js --update     # 用当前结果重写基线（要有意识地做）
 *   node tools/audit/run-all.js --no-selftest
 *   node tools/audit/run-all.js --verbose
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')

const HERE = __dirname
const BASELINE_FILE = path.join(HERE, 'baseline.json')
const UI = 'H:/dsh/ruoyiOA/ruoyi-vue-oa-ui-master/src'

const argv = process.argv.slice(2)
const UPDATE = argv.includes('--update')
const NO_SELFTEST = argv.includes('--no-selftest')
const VERBOSE = argv.includes('--verbose')

/**
 * 每个审计的清单。
 *  gate: 'zero' | 'baseline' | 'info'
 *  pick: 从该审计输出的 JSON 里取出用于门禁的数字
 */
const AUDITS = [
  {
    file: 'audit-then-loaders.js',
    title: '类 1：.then 取数无 catch（loading 永久 true）',
    gate: 'zero',
    pick: j => (Array.isArray(j) ? j.length : (j.hits ? j.hits.length : 0))
  },
  {
    file: 'audit-async-loaders.js',
    title: '类 2：async/await 取数无 try/catch',
    gate: 'zero',
    pick: j => (Array.isArray(j) ? j.length : (j.hits ? j.hits.length : 0))
  },
  {
    file: 'audit-silent-catch.js',
    title: '信息：空 .catch(() => {}) 总数（多数是 $confirm 取消，属正常）',
    gate: 'baseline',
    pick: j => (Array.isArray(j) ? j.length : (j.hits ? j.hits.length : 0))
  },
  {
    file: 'audit-loading-pairs.js',
    title: '强判据：loading 标志缺配对复位（AST）',
    gate: 'baseline',
    pick: j => (Array.isArray(j) ? j.length : (j.hits ? j.hits.length : 0))
  },
  {
    file: 'audit-undeclared-writes.js',
    title: '非响应式赋值（只门禁"真风险"那一档）',
    gate: 'baseline',
    pick: j => (j.risky ? j.risky.length : 0)
  },
  {
    file: 'audit-template-refs.js',
    title: '模板引用了不存在的名字',
    gate: 'baseline',
    pick: j => (j.hits ? j.hits.length : 0)
  }
]

const supportsSelftest = file => {
  try { return fs.readFileSync(path.join(HERE, file), 'utf8').includes('--selftest') } catch { return false }
}

function run(script, args) {
  const r = spawnSync(process.execPath, [path.join(HERE, script), ...args], {
    encoding: 'utf8', timeout: 180000
  })
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') }
}

const tmpJson = path.join(os.tmpdir(), 'dsh-audit-' + process.pid + '.json')
const results = []

for (const a of AUDITS) {
  const row = { file: a.file, title: a.title, gate: a.gate }

  // ① 自检
  if (!NO_SELFTEST) {
    if (supportsSelftest(a.file)) {
      const r = run(a.file, ['--selftest'])
      row.selftest = r.code === 0 ? 'PASS' : 'FAIL'
      if (r.code !== 0) row.selftestOut = r.out.trim().split('\n').slice(-4).join('\n')
    } else {
      row.selftest = 'N/A（脚本未实现 --selftest，其输出未被独立验证）'
    }
  }

  // ② 正式跑
  try { fs.unlinkSync(tmpJson) } catch { /* 不存在就算了 */ }
  const r = run(a.file, [UI, tmpJson])
  row.exit = r.code
  if (r.code !== 0 && !fs.existsSync(tmpJson)) {
    row.count = null
    row.error = r.out.trim().split('\n').slice(0, 3).join(' | ')
  } else {
    try {
      const j = JSON.parse(fs.readFileSync(tmpJson, 'utf8'))
      row.count = a.pick(j)
    } catch (e) { row.count = null; row.error = '读不到 JSON 输出：' + e.message }
  }
  results.push(row)
}

/* ---------- 基线对比 ---------- */
/**
 * ⚠ 必须把"基线文件不存在"与"基线文件存在但读不出来"分开。
 * 前者是首次使用，可以放行；后者是**损坏**，必须报错 ——
 * 否则一个带 BOM 或被截断的 baseline.json 会让所有门禁静默退回"首次记录"，
 * 闸门就变成了一个永远通过的摆设（这个坑被负面对照实验当场抓到过）。
 */
let baseline = {}
let baselineError = null
if (fs.existsSync(BASELINE_FILE)) {
  try {
    baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8')).counts || {}
  } catch (e) {
    baselineError = e.message
  }
}

if (UPDATE) {
  const counts = {}
  results.forEach(r => { counts[r.file] = r.count })
  if (baselineError) {
    console.error('拒绝对损坏的基线做 --update：' + baselineError)
    console.error('请先确认 tools/audit/baseline.json 的内容（常见原因：被写成了带 BOM 的 UTF-8）。')
    process.exit(1)
  }
  fs.writeFileSync(BASELINE_FILE, JSON.stringify({ updatedAt: new Date().toISOString(), src: UI, counts }, null, 2), 'utf8')
  console.log('已更新基线：' + BASELINE_FILE)
}

let failed = 0
console.log('════════════════ tools/audit 审计闸门 ════════════════\n')
if (baselineError) {
  console.log(`❌ 基线文件存在但读不出来：${BASELINE_FILE}`)
  console.log(`   原因：${baselineError}`)
  console.log('   所有门禁都无法对比 —— 视为失败，不要当成"首次记录"。\n')
  failed++
}
for (const r of results) {
  let verdict
  if (r.count === null) { verdict = 'ERROR（脚本没跑出结果）'; failed++ }
  else if (r.gate === 'zero') {
    if (r.count === 0) verdict = 'OK（0）'
    else { verdict = `FAIL（${r.count} > 0：出现真实缺陷）`; failed++ }
  } else if (r.gate === 'baseline') {
    const b = baseline[r.file]
    if (b === undefined) verdict = `OK（${r.count}，首次记录）`
    else if (b === r.count) verdict = `OK（${r.count} = 基线）`
    else { verdict = `FAIL（${r.count} ≠ 基线 ${b}：数字变了，要么修好了去 --update，要么是回归）`; failed++ }
  } else verdict = `INFO（${r.count}）`

  const self = r.selftest ? `  自检 ${r.selftest.startsWith('PASS') ? '✅' : r.selftest.startsWith('N/A') ? '⚠️ ' : '❌'}` : ''
  console.log(`● ${r.title}`)
  console.log(`  ${r.file}`)
  console.log(`  结果：${verdict}${self}`)
  if (r.selftest && r.selftest.startsWith('N/A')) console.log(`  ⚠️  ${r.selftest}`)
  if (r.error) console.log(`  ⚠️  ${r.error}`)
  if (r.selftestOut) console.log(r.selftestOut.split('\n').map(l => '     ' + l).join('\n'))
  if (VERBOSE) console.log('  （--verbose）原始输出尾部：\n' + (r.out || '').split('\n').slice(-3).map(l => '     ' + l).join('\n'))
  console.log()
}

const untrusted = results.filter(r => r.selftest && r.selftest.startsWith('N/A')).length
console.log('──────────────────────────────────────────────────────')
console.log(`共 ${results.length} 个审计；失败 ${failed} 个；未自证 ${untrusted} 个`)
if (untrusted) {
  console.log(`⚠️  ${untrusted} 个审计没有 --selftest，它们的"0/基线一致"**不能**排除"脚本静默失效"。`)
  console.log(`    未自证的：${results.filter(r => r.selftest && r.selftest.startsWith('N/A')).map(r => r.file).join(', ')}`)
}
try { fs.unlinkSync(tmpJson) } catch { /* ignore */ }
process.exit(failed ? 1 : 0)
