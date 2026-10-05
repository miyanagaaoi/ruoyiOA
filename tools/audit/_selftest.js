/**
 * 审计脚本的自检助手。
 *
 * 为什么需要：审计最危险的失败模式不是误报，而是**静默失效** ——
 * 判据写错、解析器挂掉，输出一个漂亮的"0 命中"，看起来像"代码没问题"。
 * 所以每个审计都必须能用一组**已知阳性/阴性样本**证明自己在工作。
 *
 * 做法：把样本写成临时目录里的文件，再把**本脚本自己**当子进程跑一遍
 * （SRC 指向那个临时目录），最后断言它的 JSON 输出。
 * 好处是不必把扫描逻辑重构成可调用函数 —— 测的就是真实的那条端到端路径。
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')

/** files: { '相对路径': '内容' } */
function makeFixtureDir(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-audit-self-'))
  for (const name of Object.keys(files)) {
    const p = path.join(dir, name)
    fs.mkdirSync(path.dirname(p), { recursive: true })
    fs.writeFileSync(p, files[name], 'utf8')
  }
  return dir
}

/** 把本脚本当子进程跑一遍（不带 --selftest），拿到它的 JSON 结果 */
function runSelfOn(scriptPath, dir) {
  const out = path.join(dir, '__out.json')
  const r = spawnSync(process.execPath, [scriptPath, dir, out], { encoding: 'utf8', timeout: 120000 })
  let json = null
  try { json = JSON.parse(fs.readFileSync(out, 'utf8')) } catch { /* 断言里会体现 */ }
  return { code: r.status, out: (r.stdout || '') + (r.stderr || ''), json }
}

/** checks: [{ label, pass, detail? }] —— 任一不通过即 exit 1 */
function finish(name, dir, checks, extraLines) {
  console.log(`=== ${name} 自检 ===`)
  let ok = true
  checks.forEach(c => {
    if (!c.pass) ok = false
    console.log(`  ${c.pass ? '✅' : '❌'} ${c.label}${c.detail ? '  —— ' + c.detail : ''}`)
  })
  if (extraLines) extraLines.forEach(l => console.log('     ' + l))
  console.log(ok ? '  ✅ PASS —— 审计在工作' : '  ❌ FAIL —— 判据有问题，别信它的输出')
  try { fs.rmSync(dir, { recursive: true, force: true }) } catch { /* 清不掉就算了 */ }
  process.exit(ok ? 0 : 1)
}

module.exports = { makeFixtureDir, runSelfOn, finish }
