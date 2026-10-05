/**
 * 前端单测的极简 harness（支持同步与 async 用例）。
 *
 * 本工程（vue-cli 4 + Vue2）**没有** jest / @vue/test-utils，package.json 里也没有 test 脚本。
 * 为 B2 的验收项（AC-62 / AC-64 / AC-66 / AC-67）补前端测试时有两个选择：
 *   ① 引入 jest + @vue/test-utils（要新增十来个依赖，还要改 babel/jest 配置）；
 *   ② 把纯逻辑抽成 CommonJS 模块，用 Node 自带的 `assert` 跑。
 * 选了 ②：这些验收点的核心都是**纯判断**（出不出签批栏、按什么纸算几页、留痕失败要不要打），
 * 抽出来的 `printLayout.js` / `printGate.js` 本来就是更好的结构，
 * 而且零新增依赖、跑得飞快、不依赖浏览器。
 *
 * 用法：`npm run test:unit`（= `node tests/run.js`）。
 */
'use strict'

var registered = []

/** 登记一条用例：test('名字', function () { ... })；返回 Promise 时会被等待 */
function test(name, fn) {
  registered.push({ name: name, fn: fn })
}

/** 顺序执行全部用例（逐个 await），返回 { total, passed, failed, failures } */
function runAll() {
  var failures = []
  var i = 0

  function step() {
    if (i >= registered.length) {
      return Promise.resolve({
        total: registered.length,
        passed: registered.length - failures.length,
        failed: failures.length,
        failures: failures
      })
    }
    var t = registered[i++]
    return Promise.resolve()
      .then(function () { return t.fn() })
      .then(function () {
        console.log('  \u2713 ' + t.name)
      }, function (e) {
        failures.push({ name: t.name, error: e })
        console.log('  \u2717 ' + t.name)
        console.log('      ' + (e && e.message ? e.message : String(e)))
      })
      .then(step)
  }

  return step()
}

module.exports = { test: test, runAll: runAll }
