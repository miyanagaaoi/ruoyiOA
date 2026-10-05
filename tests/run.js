/**
 * 前端用例入口：`npm run test:unit`（= `node tests/run.js`）。
 *
 * 退出码：全绿 0，有失败 1 —— 可以直接进交付门禁。
 */
'use strict'

var h = require('./harness')

require('./print-layout.test.js')
require('./print-entries.test.js')

console.log('════════ 前端用例（tools 无关，纯 Node） ════════\n')

h.runAll().then(function (r) {
  console.log('\n──────────────────────────────────────────────')
  console.log('共 ' + r.total + ' 条；通过 ' + r.passed + ' 条；失败 ' + r.failed + ' 条')
  if (r.failed) {
    console.log('\n失败明细：')
    r.failures.forEach(function (f) {
      console.log('  ✗ ' + f.name)
      console.log('    ' + (f.error && f.error.stack ? f.error.stack.split('\n').slice(0, 4).join('\n    ') : f.error))
    })
  }
  process.exit(r.failed ? 1 : 0)
})
