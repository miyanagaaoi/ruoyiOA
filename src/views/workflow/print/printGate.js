/**
 * 打印留痕的**硬门禁**（2.0 B2 §5.1；REQ-PRINT-017、AC-67）。
 *
 * 升级前的 `doPrint()` 是"先 `window.print()` 再补写留痕，写失败只 warning 一下"：
 * 打印这个动作一旦发生就**物理上无法回滚**，审计要求的"必留痕"因此形同虚设。
 * 现在改成：先写留痕 → 成功才调起打印；失败**不打印**并给出明确提示。
 *
 * 与 `printLayout.js` 同样的理由写成 CommonJS：这样"失败路径不得调起打印"
 * 这条验收可以被真正的断言覆盖（见 tests/print-layout.test.js），
 * 而不是只能靠人去把留痕接口改坏再点一次。
 */
'use strict'

/** 留痕失败时给用户看的文案（AC-67 要求明确说明"联系管理员"） */
var PRINT_LOG_FAILED_MESSAGE = '打印留痕写入失败，请联系管理员'

/**
 * 先写留痕，成功后才调起打印。
 *
 * @param {object} opts
 * @param {function} opts.writeLog 写留痕（返回 Promise 或普通值；reject/throw 即失败）
 * @param {function} opts.print    真正调起打印（只在留痕成功后调用）
 * @returns {Promise<{printed:boolean, error:(Error|null)}>}
 *          `printed=false` 表示**没有**调起打印，调用方必须把失败提示给用户看。
 */
function printAfterLogging(opts) {
  var o = opts || {}
  var writeLog = o.writeLog
  var doPrint = o.print
  return Promise.resolve()
    .then(function () {
      if (typeof writeLog !== 'function') {
        throw new Error('缺少留痕写入实现')
      }
      return writeLog()
    })
    .then(function () {
      // 只有走到这里才允许打印
      if (typeof doPrint !== 'function') {
        throw new Error('缺少打印实现')
      }
      doPrint()
      return { printed: true, error: null }
    })
    .catch(function (err) {
      // 刻意**不**把错误再抛出去：调用方要的是"能不能打"的结论与提示文案，
      // 而不是一个需要额外 try/catch 的异常。
      return { printed: false, error: err || new Error('留痕写入失败') }
    })
}

/** 留痕失败时给用户的提示文案 */
function logFailureMessage() {
  return PRINT_LOG_FAILED_MESSAGE
}

module.exports = {
  PRINT_LOG_FAILED_MESSAGE: PRINT_LOG_FAILED_MESSAGE,
  printAfterLogging: printAfterLogging,
  logFailureMessage: logFailureMessage
}
