/**
 * t52 反向对照工具：把 doc-options-need.js 的"联动推导"从函数末尾**移动**到
 * filters 与 headerFields 扫描之间（精确复现 P-⑤），跑用例应见红；用完立刻还原。
 *
 * 用法：node mutate-p5.js <path-to-doc-options-need.js> <restore|mutate>
 */
'use strict'
const fs = require('fs')
const p = process.argv[2]
const mode = process.argv[3]
const COUPLE = 'if (need.products) need.uoms = true'
const ANCHOR = ';(kind && kind.headerFields'

if (mode === 'mutate') {
  let t = fs.readFileSync(p, 'utf8')
  const i = t.lastIndexOf(COUPLE) // 代码里唯一一处（前面注释里也提过，故取最后一处）
  const lineStart = t.lastIndexOf('\n', i) + 1
  let lineEnd = t.indexOf('\n', i)
  if (lineEnd < 0) lineEnd = t.length
  const line = t.slice(lineStart, lineEnd)
  t = t.slice(0, lineStart) + t.slice(lineEnd + 1) // 删掉这一行（连同换行）
  const a = t.indexOf(ANCHOR)
  const aStart = t.lastIndexOf('\n', a) + 1
  t = t.slice(0, aStart) + line.replace(/^\s*/, '  ') + '\n' + t.slice(aStart)
  fs.writeFileSync(p, t)
  console.log('MUTATED：已把 "' + line.trim() + '" 移到 filters 与 headerFields 之间')
} else {
  console.log('no-op')
}
