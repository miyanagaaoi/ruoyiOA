/**
 * 金额格式化与中文大写（PRD 9.3 / AC-36）—— **全项目唯一**的金额显示实现。
 *
 * 为什么另写一份而不复用 `utils/ruoyi.js` 里的 `moneySmallToBig`：
 *   那份是 RuoYi 上游带过来的，实测不能用于合同单据：
 *     · 把「元」「整」的输出注释掉了 → 1234.56 出来是「壹仟贰佰叁拾肆伍角陆分」，
 *       整数和小数之间没有「元」，读起来是错的，合同上不能这么写；
 *     · 10.05 出来是「壹拾伍分」（丢了「元零」）；
 *     · 零的合并规则只覆盖了"整段为零"，`100010000` 会读成「壹亿壹万」（应「壹亿零壹万」）。
 *   它在项目里**没有任何调用方**（只有定义处），所以这里重写一份正确的，
 *   并让金额控件、打印件都走这一份 —— AC-36 要求「大写与数字一致」，
 *   两个实现各算一遍是保证不了一致的。
 */

const CN_NUM = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖']
/** 组内单位：个、拾、佰、仟 */
const CN_RADICE = ['', '拾', '佰', '仟']
/** 组单位：个级、万级、亿级、万亿级 */
const CN_GROUP_UNIT = ['', '万', '亿', '万亿']

/** 允许的最大金额（再大就超出「万亿」级的表达能力，与上游保持同一上限） */
const MAX_AMOUNT = 999999999999999.9999

/**
 * 整数部分转中文（不含「元」）。
 *
 * 零的处理是这类算法最容易错的地方，规则就两条：
 *   1. 组内连续零只读一个「零」，且**末尾的零不读**（10 → 壹拾，不是壹拾零）；
 *   2. 组与组之间：整组为零、或该组不足千（说明高几位是零）时，补一个「零」
 *      （100010000 → 壹亿零壹万；100000005 → 壹亿零伍）。
 *
 * @param {string} intStr 仅数字字符的整数字符串
 * @returns {string}
 */
function integerToChinese(intStr) {
  let s = String(intStr == null ? '' : intStr).replace(/^0+/, '')
  if (!s) {
    return CN_NUM[0]
  }
  const groups = []
  while (s.length > 0) {
    groups.unshift(s.slice(-4))
    s = s.slice(0, -4)
  }

  let out = ''
  let pendingZero = false
  for (let gi = 0; gi < groups.length; gi++) {
    const g = groups[gi]
    const unitIndex = groups.length - 1 - gi
    const num = Number(g)
    if (num === 0) {
      // 整组为零：只记一个待补的零，不在这里输出（后面可能没有非零组了）
      if (out) pendingZero = true
      continue
    }
    // 组与组之间补零：整组为零待补，或本组不足千（千万/百万/十万位是零）
    if (out && (pendingZero || num < 1000)) {
      out += CN_NUM[0]
    }
    pendingZero = false

    const padded = g.padStart(4, '0')
    let seg = ''
    let zeroInGroup = false
    for (let i = 0; i < 4; i++) {
      const d = Number(padded.charAt(i))
      const posUnit = CN_RADICE[3 - i]
      if (d === 0) {
        if (seg) zeroInGroup = true
        continue
      }
      if (zeroInGroup) {
        seg += CN_NUM[0]
        zeroInGroup = false
      }
      seg += CN_NUM[d] + posUnit
    }
    out += seg + CN_GROUP_UNIT[unitIndex]
  }
  return out || CN_NUM[0]
}

/**
 * 数字金额 → 中文大写（不含「人民币」前缀，含「元」「角」「分」「整」）。
 *
 * 例：1234.56 → 壹仟贰佰叁拾肆元伍角陆分；1005 → 壹仟零伍元整；
 *     10.05 → 壹拾元零伍分；0.5 → 伍角整；0 → 零元整。
 *
 * @param {number|string} value
 * @returns {string} 非法/空值返回空串
 */
export function toChineseUpper(value) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const n = Number(value)
  if (!isFinite(n)) {
    return ''
  }
  if (Math.abs(n) >= MAX_AMOUNT) {
    return ''
  }
  const negative = n < 0
  // 先四舍五入到分，避免浮点误差把 0.145 变成 0.14/0.15 这种摇摆
  const cents = Math.round(Math.abs(n) * 100)
  const integerPart = Math.floor(cents / 100)
  const jiao = Math.floor((cents % 100) / 10)
  const fen = cents % 10

  let out = integerToChinese(String(integerPart)) + '元'
  if (jiao === 0 && fen === 0) {
    out += '整'
  } else if (jiao === 0) {
    // 有分无角：中间必须补「零」（10.05 → 壹拾元零伍分）
    out += CN_NUM[0] + CN_NUM[fen] + '分'
  } else {
    out += CN_NUM[jiao] + '角'
    out += fen === 0 ? '整' : CN_NUM[fen] + '分'
  }
  return negative ? '负' + out : out
}

/**
 * 千分位格式化。
 *
 * @param {number|string} value
 * @param {object} [opt] { decimals = 2, grouping = true }
 * @returns {string} 空值返回空串（**不返回 0**：空金额和 0 元在单据上是两件事）
 */
export function formatAmount(value, opt) {
  const options = opt || {}
  const decimals = options.decimals === undefined ? 2 : options.decimals
  const grouping = options.grouping === undefined ? true : options.grouping
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const n = Number(value)
  if (!isFinite(n)) {
    return ''
  }
  const fixed = n.toFixed(Math.max(0, Math.min(6, decimals)))
  if (!grouping) {
    return fixed
  }
  const parts = fixed.split('.')
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return parts.join('.')
}

/**
 * 打印件上的金额：`1,234.56（人民币壹仟贰佰叁拾肆元伍角陆分）`。
 *
 * @param {number|string} value
 * @param {number} [decimals=2]
 * @returns {string}
 */
export function amountWithUpper(value, decimals) {
  const plain = formatAmount(value, { decimals: decimals === undefined ? 2 : decimals })
  if (!plain) {
    return ''
  }
  const upper = toChineseUpper(value)
  return upper ? `${plain}（人民币${upper}）` : plain
}

export default { formatAmount, toChineseUpper, amountWithUpper }
