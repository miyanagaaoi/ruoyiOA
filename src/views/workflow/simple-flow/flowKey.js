/**
 * 流程标识（`def_key`）的**建议生成**与**格式校验** —— 纯函数，零依赖、可单测。
 *
 * 为什么需要它（第四轮追加）：
 *   `def_key` 不是"给流程起个名字"，它是**写进 BPMN 的 Flowable 流程定义 key**
 *   （`SimpleFlowCompiler` 用它生成 `<process id="...">`，发布后引擎按它部署与查实例）。
 *   因此它有三条硬约束：
 *     ① 格式 `[A-Za-z_][A-Za-z0-9_-]*` —— 字母/数字/下划线/中划线，**不能数字开头、不能中文/空格**
 *        （后端 `SimpleFlowValidator` V-0 与新建弹窗的正则同口径）；
 *     ② **全局唯一**（未删除范围内，`SimpleFlowServiceImpl.saveDraft` 会挡）；
 *     ③ 列宽 `varchar(100)`；发布后**不可修改**。
 *   此前"新建流程"弹窗要求用户**手敲**这一格，敲错要到提交后才报后端错误 ——
 *   实测库里 `def_key='authzPublishGuard'` 有 31 条（版本高到 v62），就是这么撞出来的。
 *
 * 本模块只做**建议**与**校验**：是否采用、改成什么，由用户决定（服务端仍会再校验一次）。
 * ⚠ 唯一真源在服务端（`SimpleFlowValidator`）；这里改口径必须同步后端与用例。
 *
 * @author 二开
 */

/**
 * OA 业务词表：**词 → 缩写**。
 *
 * 只收录本系统里真实会出现的词（合同/审批/用印/报销…），不做通用拼音转换 ——
 * 工程里没有拼音库（离线环境，`package.json` 也没有），而完整拼音表的体积与维护成本
 * 远大于收益；"业务词表 + 首字母兜底"足以让标识**可读、可预期**。
 */
var WORD_MAP = {
  合同: 'ht',
  协议: 'xy',
  审批: 'sp',
  申请: 'sq',
  用印: 'yy',
  盖章: 'gz',
  报销: 'bx',
  付款: 'fk',
  收款: 'sk',
  借款: 'jk',
  采购: 'cg',
  销售: 'xs',
  请购: 'qg',
  入库: 'rk',
  出库: 'ck',
  库存: 'kc',
  盘点: 'pd',
  请假: 'qj',
  加班: 'jb',
  出差: 'cc',
  补卡: 'bk',
  离职: 'lz',
  入职: 'rz',
  转正: 'zz',
  调岗: 'dg',
  事项: 'sx',
  变更: 'bg',
  立项: 'lx',
  验收: 'ys',
  法务: 'fw',
  财务: 'cw',
  人事: 'rs',
  行政: 'xz',
  经营: 'jy',
  经济: 'jj',
  人力: 'rl',
  部门: 'bm',
  负责人: 'fzr',
  分管: 'fg',
  领导: 'ld',
  董事长: 'dsz',
  总经理: 'zjl',
  董事长审批: 'dszsp',
  归档: 'gd',
  台账: 'tz',
  模板: 'mb',
  流程: 'lc',
  回归: 'hg',
  测试: 'cs',
  会审: 'hs',
  会签: 'hq',
  评审: 'ps',
  审核: 'sh',
  复核: 'fh',
  受理: 'sl',
  办理: 'bl',
  签批: 'qp',
  批阅: 'py',
  提报: 'tb',
  报送: 'bs',
  抄送: 'csong',
  通知: 'tzhi',
  公告: 'gg',
  用款: 'yk',
  预算: 'ysuan',
  结算: 'js',
  开票: 'kp',
  发票: 'fp',
  保证金: 'bzj',
  质保: 'zb',
  维护: 'wh',
  续签: 'xq',
  解除: 'jc',
  终止: 'zzhi'
}

/** 词表按词长倒序，保证"最长优先"匹配（领导 → 分管领导 而不是 分管+领导） */
var WORDS = Object.keys(WORD_MAP).sort(function (a, b) { return b.length - a.length })

/** 标识格式（与后端 `SimpleFlowValidator` V-0 完全一致） */
var KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_-]*$/

/** 列宽上限（`t_flow_simple.def_key = varchar(100)`） */
var MAX_KEY_LENGTH = 100

/** 建议标识的最大长度（太长没人看得懂；够区分就行） */
var SUGGEST_MAX_LENGTH = 32

/** ASCII 关键字（字母 / 数字 / 下划线 / 中划线）—— 与 KEY_PATTERN 允许的字符一致 */
function isAsciiKeyChar(ch) {
  return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch === '_' || ch === '-'
}

/** 多字词表命中不了时，**逐字**再试一次的兜底表（长名几乎必然夹杂词表外的字，如"含""链路"） */
var CHAR_MAP = {
  含: 'h',
  及: 'j',
  和: 'h',
  与: 'y',
  的: 'd',
  年: 'n',
  月: 'y',
  日: 'r',
  回: 'h',
  写: 'x',
  链: 'l',
  路: 'l',
  栏: 'l',
  顺: 's',
  序: 'x'
}

/**
 * 名称 → **确定性短码**（不是随机数：同名必得同码，便于复现与排查）。
 * 用途：未命中汉字过多时兜底去重。FNV-1a 的乘法用移位实现，避免 JS 浮点精度丢失；
 * 结果**只保留字母数字** —— `toString(36)` 对负数会产生 `-`，不过滤会让标识里冒出 `-`。
 */
function shortHash(name) {
  var s = String(name || '')
  var h = 2166136261
  for (var i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    // FNV-1a：乘 16777619 用移位避免浮点误差
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0
  }
  return h.toString(36).replace(/[^a-z0-9]/g, '').slice(-6)
}

/** 是不是"需要翻译的汉字"（CJK 统一表意文字；标点/括号/箭头不算） */
function isCjk(ch) {
  var c = ch.charCodeAt(0)
  return c >= 0x4e00 && c <= 0x9fff
}

/**
 * 名称 → **确定性短码**（不是随机数：同名必得同码，便于复现与排查）。
 * 用途：未命中汉字过多时兜底去重。FNV-1a 的乘法用移位实现，避免 JS 浮点精度丢失；
 * 结果**只含字母数字**（`toString(36)` 对负数会产生 `-`，必须滤掉 —— 否则标识里会冒出 `-`）。
 */

/**
 * 把一段中文里的**词表词**拼成缩写（最长优先、不重叠），剩下的字再逐字查 `CHAR_MAP`。
 *
 * 两类"没命中"必须区别对待：
 *   · **汉字**没命中 → 记进 `unmatched`（调用方据此打短码，避免"只取命中部分"造成重名）；
 *   · **标点/括号/箭头** → 直接忽略 —— 它们不携带语义，
 *     若也算"没命中"，`合同审批（含用印）` 就会退化成 `htsp_<随机码>`（实测踩过）。
 *
 * @returns {{text: string, unmatched: string}}
 */
function translateChinese(seg) {
  var out = ''
  var unmatched = ''
  var taken = seg.split('').map(function () { return false })

  // 第一轮：多字词（WORDS 已按词长倒序 → 最长优先）
  for (var w = 0; w < WORDS.length; w++) {
    var word = WORDS[w]
    var from = 0
    while (true) {
      var at = seg.indexOf(word, from)
      if (at < 0) break
      var free = true
      for (var k = 0; k < word.length; k++) {
        if (taken[at + k]) { free = false; break }
      }
      if (free) {
        out += WORD_MAP[word]
        for (var j = 0; j < word.length; j++) taken[at + j] = true
      }
      from = at + 1
    }
  }

  // 第二轮：剩下的字按原顺序处理
  for (var i = 0; i < seg.length; i++) {
    if (taken[i]) continue
    var ch = seg.charAt(i)
    if (!isCjk(ch)) continue
    if (Object.prototype.hasOwnProperty.call(CHAR_MAP, ch)) {
      out += CHAR_MAP[ch]
    } else {
      unmatched += ch
    }
  }
  return { text: out, unmatched: unmatched }
}

/**
 * 流程名称 → **建议的流程标识**（用户可改；服务端仍会校验唯一性）。
 *
 * 组装规则（都是为了"可读 + 尽量不撞"）：
 *   1. 名称里的 ASCII 段（英文/数字）**原样小写**保留 —— "Contract Approval" → `contractapproval`；
 *      含数字时用 `_` 与前面的字母隔开（`合同审批2024` → `htsp_2024`，避免 `htsp2024` / `htsp2` 这类歧义）；
 *   2. 中文段按**词表最长优先**转缩写，剩余单字查单字表 —— "合同审批（含用印）" → `htsp` + `h` + `yy`；
 *      标点/括号/箭头**直接忽略**（不参与、也不触发兜底）；
 *   3. 仍有**汉字**没命中时，**整串退化为 `flow_` + 名称确定性短码** ——
 *      这类名字（如"复现验证"）取命中片段会与别的流程重名，而"半截缩写 + 半截乱码"更难懂；
 *      用户想要可读标识就在弹窗里改（提示已写明"可自行修改"）。
 *
 * 永不返回空串；返回值一定满足 `KEY_PATTERN`（首字符是字母或下划线）。
 *
 * @param {string} name 流程名称
 * @returns {string} 建议标识（≤ SUGGEST_MAX_LENGTH，且 ≤ MAX_KEY_LENGTH）
 */
function suggestFlowKey(name) {
  var raw = String(name === null || name === undefined ? '' : name).trim()
  if (!raw) return 'flow_' + shortHash('empty')

  var parts = []
  var hasLetters = false
  var hasDigits = false
  var unmatched = ''

  var i = 0
  while (i < raw.length) {
    var ch = raw.charAt(i)
    if (isAsciiKeyChar(ch)) {
      var ascii = ''
      while (i < raw.length && isAsciiKeyChar(raw.charAt(i))) {
        ascii += raw.charAt(i)
        i++
      }
      parts.push(ascii.toLowerCase())
      if (/[a-z]/.test(ascii.toLowerCase())) hasLetters = true
      if (/[0-9]/.test(ascii)) hasDigits = true
    } else {
      var seg = ''
      while (i < raw.length && !isAsciiKeyChar(raw.charAt(i))) {
        seg += raw.charAt(i)
        i++
      }
      var t = translateChinese(seg)
      if (t.text) {
        parts.push(t.text)
        hasLetters = true
      }
      unmatched += t.unmatched
    }
  }

  /*
   * 还有汉字没命中 —— 这类名字"取命中部分"会与别的流程重名，所以必须有兜底。
   * 兜底用**名称的确定性短码**，并且**整串走 `flow_` + 短码**这一条干净路径：
   *   · 不把 `htsp` 之类的命中片段与短码拼在一起 —— 实测那样会得到
   *     `flow_-vcsbcu4qaxj5` 这种半截可读、半截乱码的标识，比纯短码更难理解；
   *   · `flow_` + 6 位短码虽不好看，但**短、稳定、可预期**，且弹窗里明确写着"可自行修改"。
   */
  var out
  if (unmatched) {
    out = 'flow_' + shortHash(raw)
    return out.slice(0, MAX_KEY_LENGTH)
  }

  out = parts.join('')
  if (!out) out = 'flow_' + shortHash(raw)

  // 含数字时补分隔符：只处理"**多个字母**紧连数字"的那一处（`htsp2024` → `htsp_2024`、`test02` → `test_02`），
  // 单字母+数字（`b1`）不动 —— 补成 `b_1` 只会更难读
  if (hasDigits) {
    out = out.replace(/^([a-z][a-z-]+)([0-9])/, '$1_$2')
  }

  out = out.slice(0, SUGGEST_MAX_LENGTH)

  // 首字符必须是字母或下划线（KEY_PATTERN）；尾部的分隔符要去掉
  if (!/^[a-z_]/.test(out)) out = 'flow_' + out
  out = out.replace(/[_-]+$/, '')
  if (!out) out = 'flow_' + shortHash(raw)
  return out.slice(0, MAX_KEY_LENGTH)
}

/**
 * 格式校验（**只判格式，不判唯一**——唯一性必须问服务端）。
 *
 * @param {string} key
 * @returns {string} 空串 = 合法；否则是给用户看的中文原因
 */
function validateFlowKey(key) {
  var s = String(key === null || key === undefined ? '' : key).trim()
  if (!s) return '流程标识不能为空'
  if (s.length > MAX_KEY_LENGTH) return '流程标识不能超过 ' + MAX_KEY_LENGTH + ' 个字符（当前 ' + s.length + '）'
  if (!KEY_PATTERN.test(s)) {
    return '只能用小写/大写字母、数字、下划线、中划线，且必须以字母或下划线开头（不能有中文、空格、括号）'
  }
  return ''
}

/**
 * 撞名时的**备选标识**：`base` → `base_2` / `base_3` …
 * 与后端 `SimpleFlowServiceImpl.uniqueDefKey` 的后缀风格保持一致（同一种"看惯了"的形态）。
 *
 * @param {string} base 已被占用的标识
 * @param {string[]} taken 已确认占用的标识清单（服务端查回来的）
 * @returns {string} 第一个未占用的备选（taken 为空则返回 base_2）
 */
function suggestAlternativeKey(base, taken) {
  var takenList = taken || []
  var i = 2
  while (i <= 50) {
    var candidate = String(base) + '_' + i
    if (takenList.indexOf(candidate) < 0) return candidate
    i++
  }
  return String(base) + '_' + shortHash(String(Date.now()))
}

module.exports = {
  WORD_MAP: WORD_MAP,
  CHAR_MAP: CHAR_MAP,
  KEY_PATTERN: KEY_PATTERN,
  MAX_KEY_LENGTH: MAX_KEY_LENGTH,
  SUGGEST_MAX_LENGTH: SUGGEST_MAX_LENGTH,
  suggestFlowKey: suggestFlowKey,
  validateFlowKey: validateFlowKey,
  suggestAlternativeKey: suggestAlternativeKey,
  shortHash: shortHash
}
