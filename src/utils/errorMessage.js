/**
 * 把 axios / 后端返回的错误翻译成「发生了什么 + 为什么」。
 *
 * 为什么要有这个文件：项目里大量 `catch(() => {})` 把失败静默吞掉，界面表现为
 * 「空表格」「空下拉」——和"确实没有数据"完全无法区分。补错误态时，如果文案只写
 * "加载失败"，用户仍然不知道下一步该做什么。open-design/craft/state-coverage.md 要求
 * 错误必须回答三问：发生了什么 / 为什么 / 怎么办；"怎么办"由 UI 的重试按钮承担，
 * 这里负责前两问。
 *
 * ⚠ 必须知道 `src/utils/request.js` 的行为，否则这个函数会写错：
 *   1. **HTTP 层错误**（网络不可达 / 超时 / 4xx / 5xx）走 error 拦截器，它会把
 *      `error.message` **改写成中文**：
 *        "Network Error"                      -> "后端接口连接异常"
 *        message 含 "timeout"                 -> "系统接口请求超时"
 *        "Request failed with status code 404" -> "系统接口404异常"
 *      所以**不能**再用 `/timeout/i` 或 `message === 'Network Error'` 去判定，
 *      那些英文串到这里已经不存在了；4xx/5xx 则仍有 `error.response.status` 可用。
 *   2. **业务码错误**（HTTP 200 但 code 非 200）走 response 拦截器的 reject：
 *        code 401 -> reject 一个**可读中文串**（"无效的会话，或者会话已过期，请重新登录。"）
 *        code 500 -> reject `new Error(msg)`
 *        code 601 -> reject 字符串 'error'   ← 不含任何信息，直接显示给用户毫无意义
 *        其它非 200 -> reject 字符串 'error'
 *      即：reject 的值**可能是字符串**，别假设它一定是 Error。
 *
 * @param {*} err axios 错误对象，或 request.js reject 出来的字符串
 * @returns {{text: string, cause: string}} text = 发生了什么；cause = 为什么（可为空串）
 */

/** HTTP 状态下「发生了什么」的兜底文案（服务端没给 msg 时用） */
const HTTP_TEXT = {
  400: '请求参数不合法。',
  401: '登录状态已失效。',
  403: '没有访问该功能的权限。',
  404: '接口不存在（404）。',
  405: '请求方法不被允许。',
  408: '请求超时。',
  429: '请求过于频繁。',
  500: '服务端内部错误。',
  502: '网关无法连接到后端服务。',
  503: '后端服务暂时不可用。',
  504: '网关等待后端响应超时。'
}

/** HTTP 状态下「为什么」 */
const HTTP_CAUSE = {
  400: '提交的数据没通过后端校验。',
  401: '会话过期，需要重新登录。',
  403: '当前账号缺少对应角色或权限点。',
  404: '前端与后端版本不一致，或服务未部署该接口。',
  408: '服务端等待请求数据超时。',
  429: '短时间内请求过多，被服务端限流。',
  500: '后端抛异常了，请查看服务日志。',
  502: '后端服务未启动或正在重启。',
  503: '后端服务过载或正在重启。',
  504: '后端处理过慢，超过了网关的等待上限。'
}

/**
 * request.js 在业务码非 200 时 reject 出来的"哨兵"值：本身不含任何可读信息。
 * 真正的错误原因此时已经由拦截器用 Message/Notification 弹出来了。
 */
const OPAQUE_SENTINELS = ['error', 'undefined', 'null', '[object object]']

export function describeError(err) {
  /* ---------- 1) 字符串拒绝（业务码 401 / 601 / 其它非 200） ---------- */
  if (typeof err === 'string') {
    const s = err.trim()
    if (!s || OPAQUE_SENTINELS.indexOf(s.toLowerCase()) >= 0) {
      return {
        text: '接口返回了非 200 业务状态码，操作未生效。',
        cause: '具体原因见页面右上角的提示；若反复出现，请把操作时间提供给管理员。'
      }
    }
    // code 401 那条可读中文串走这里，不能被丢掉
    return { text: s, cause: /会话|登录|过期/.test(s) ? '会话过期，需要重新登录。' : '' }
  }

  if (!err) return { text: '请求没有成功返回。', cause: '' }

  /* ---------- 2) 有 HTTP 响应（4xx / 5xx） ---------- */
  const status = err.response && err.response.status
  if (status) {
    const data = err.response.data
    const serverMsg = (data && (data.msg || data.message)) || ''
    return {
      text: serverMsg || HTTP_TEXT[status] || `接口返回 HTTP ${status}。`,
      cause: HTTP_CAUSE[status] || ''
    }
  }

  /* ---------- 3) 无响应：网络不可达 / 超时（message 已被拦截器改成中文） ---------- */
  const msg = err.message || err.msg || ''
  if (/超时|timeout/i.test(msg)) {
    return { text: msg || '请求超时。', cause: '后端响应过慢或网络不通。' }
  }
  if (/连接异常|不可达|断网|Network Error/i.test(msg)) {
    return { text: msg, cause: '后端服务未启动、正在重启，或网络被拦截。' }
  }
  if (/异常/.test(msg)) {
    // 形如"系统接口404异常"：状态码被拼在中文里，没有 response 对象可取
    const m = msg.match(/(\d{3})/)
    return { text: msg, cause: (m && HTTP_CAUSE[Number(m[1])]) || '' }
  }

  return { text: msg || '请求失败。', cause: '' }
}

export default describeError
