/**
 * 合同口径的**前端纯函数**（金额 / 数量 / 质保到期）—— 与后端
 * `com.ruoyi.ctms.support.ContractRules` **逐条对齐**，是整个合同前端**唯一**的一份实现。
 *
 * ⚠ 为什么不能用 JS 浮点直接算（这不是洁癖，是会算错钱）：
 *   后端口径是「行总价 = 数量 × 单价，**先** HALF_UP 舍入到 2 位，合同金额是各**已舍入**
 *   行总价之和」。判别用例：3 行「数量 1 × 单价 1.665」→ 1.67 × 3 = **5.01**；
 *   先汇总再舍入会得到 5.00（少 1 分钱）—— 这一条证的是**舍入顺序**，不是浮点误差。
 *   浮点侧的**实测**反例（node v24 / IEEE754）：`Math.round(1.005 * 100) / 100` 得到 **1.00**
 *   （`1.005 * 100 === 100.49999999999999`），而 HALF_UP 要求 **1.01**；同理
 *   `8.575 * 100 === 857.4999999999999` → 8.57（应为 8.58）。
 *   ⚠ 别拿 `1.665` 当浮点反例：`1.665 * 100` **恰好等于 166.5**，`Math.round` 会给出正确的 1.67
 *   （`1.665` 的二进制逼近 1.6650000000000000355 是**略大**，不是略小），它证明不了浮点不可靠。
 *   所以这里一律走**十进制字符串 → BigInt 定点整数**运算，四舍五入用整数除法实现。
 *   自证：`moneyText('1.005') === '1.01'`、`moneyText('2.675') === '2.68'`、`moneyText('8.575') === '8.58'`。
 *
 * 口径对照（后端常量 → 本模块常量）：
 *   SCALE_AMOUNT=2（金额）/ SCALE_QTY=3（数量）/ SCALE_PRICE=4（单价）/ SCALE_RATE=4（比例）
 *   舍入模式一律 HALF_UP（RoundingMode.HALF_UP）。
 *
 * 内部统一把输入放大 10^6 倍存成 BigInt（数量 3 位、单价 4 位都落在 6 位之内，
 * 因此**不存在二次舍入**：输入侧只有超过 6 位小数的脏值才会被归一到 6 位）。
 */

/** 内部定点 scale：足够覆盖数量(3) + 单价(4)，且乘积不溢出 */
const IN_SCALE = 6

/** 金额小数位（后端 ContractRules.SCALE_AMOUNT） */
export const SCALE_AMOUNT = 2

/** 数量小数位（后端 ContractRules.SCALE_QTY） */
export const SCALE_QTY = 3

/** 单价小数位（后端 ContractRules.SCALE_PRICE） */
export const SCALE_PRICE = 4

/** 比例小数位（后端质保比例/付款比例统一 4 位） */
export const SCALE_RATE = 4

const ZERO = BigInt(0)
const ONE = BigInt(1)
const TWO = BigInt(2)

/** 10 的 exponent 次幂（BigInt）。刻意不用 `10n ** 3n` 字面量写法，兼容面更宽。 */
function pow10(exponent) {
  let value = ONE
  const ten = BigInt(10)
  for (let i = 0; i < exponent; i++) {
    value = value * ten
  }
  return value
}

/** 整数「四舍五入」（HALF_UP）除法：round(numer / denom)，denom > 0 */
export function divRoundHalfUp(numer, denom) {
  if (denom <= ZERO) {
    return ZERO
  }
  const negative = numer < ZERO
  const magnitude = negative ? -numer : numer
  const quotient = (magnitude * TWO + denom) / (TWO * denom)
  return negative ? -quotient : quotient
}

/**
 * 十进制文本 → 放大 10^6 倍的 BigInt；非法/空值视同 0。
 * 超过 6 位的小数按 HALF_UP 归一到 6 位（正常输入不会走到这里：库列就是 3/4 位）。
 */
export function toScaledInt(value) {
  if (value === null || value === undefined || value === '') {
    return ZERO
  }
  const text = String(value).trim()
  const matched = /^([+-]?)(\d*)(?:\.(\d*))?$/.exec(text)
  if (!matched) {
    return ZERO
  }
  const negative = matched[1] === '-'
  const intPart = matched[2] || '0'
  let fracPart = matched[3] || ''
  let carry = ZERO
  if (fracPart.length > IN_SCALE) {
    const nextDigit = fracPart.charCodeAt(IN_SCALE) - 48
    fracPart = fracPart.slice(0, IN_SCALE)
    if (nextDigit >= 5) {
      carry = ONE
    }
  }
  const scaled = BigInt(intPart + fracPart.padEnd(IN_SCALE, '0')) + carry
  return negative ? -scaled : scaled
}

/** 定点整数 → 指定小数位的文本（如 501 + scale 2 → "5.01"） */
export function formatFixed(scaledValue, scale) {
  const negative = scaledValue < ZERO
  const magnitude = negative ? -scaledValue : scaledValue
  const unit = pow10(scale)
  const intText = (magnitude / unit).toString()
  const sign = negative && magnitude !== ZERO ? '-' : ''
  if (scale <= 0) {
    return sign + intText
  }
  const fracText = (magnitude % unit).toString().padStart(scale, '0')
  return sign + intText + '.' + fracText
}

/**
 * 数值 → 指定小数位的定点文本（HALF_UP）；空值返回空串。
 * 用途：把 el-input-number 的 Number 归一成后端列宽（金额 2 位 / 比例 4 位 / 单价 4 位 …），
 * 提交时也用它 —— JSON 里给字符串，Jackson 按 BigDecimal 精确解析，全程没有二进制浮点。
 */
export function roundToText(value, scale) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  return formatFixed(divRoundHalfUp(toScaledInt(value), pow10(IN_SCALE - scale)), scale)
}

/** 金额显示（scale = 2）；空值返回空串（**不返回 0**：空金额和 0 元是两件事） */
export function moneyText(value) {
  return roundToText(value, SCALE_AMOUNT)
}

/** 行总价 = 数量 × 单价，先 HALF_UP 舍入到 2 位（后端 ContractRules.lineTotal 的等价实现） */
export function lineTotalText(qty, unitPrice) {
  const product = toScaledInt(qty) * toScaledInt(unitPrice)
  // 乘积的 scale = 2 × 6 = 12；要先舍到 2 位 → 除以 10^(12-2)
  return formatFixed(divRoundHalfUp(product, pow10(IN_SCALE * 2 - SCALE_AMOUNT)), SCALE_AMOUNT)
}

/**
 * 合同金额 = 各**已舍入**行总价之和（后端 ContractRules.sumLineTotals 的等价实现）。
 * 入参必须是 {@link lineTotalText} 的结果（scale = 2 的文本），本函数只做加法与 scale 归一
 * —— **不再**做"汇总后舍入"的补救（那正是少 1 分钱的那种算法）。
 *
 * ⚠ 注意 scale：内部累加是在 10^6 定点上做的，所以最后必须除回 10^(6-2) 再格式化；
 *   直接 `formatFixed(sum, 2)` 会把 `5010000` 打成 `50100.00`（这个错被自检脚本抓到过一次）。
 *   因为每个入参都是 scale=2，这次除法是**精确**的，不引入额外舍入。
 */
export function sumLineTotalsText(lineTotals) {
  const list = lineTotals || []
  let sum = ZERO
  list.forEach((line) => {
    sum = sum + toScaledInt(line)
  })
  return formatFixed(divRoundHalfUp(sum, pow10(IN_SCALE - SCALE_AMOUNT)), SCALE_AMOUNT)
}

/**
 * 质保比例 = 质保金额 ÷ 合同金额 × 100，4 位 HALF_UP（后端 warrantyRateOfAmount）。
 * 合同金额为空或 0 时返回空串（后端在该情形下抛业务异常，前端展示为空并拦在提交前）。
 */
export function rateOfAmountText(amount, warrantyAmount) {
  const base = toScaledInt(amount)
  if (base === ZERO) {
    return ''
  }
  const numerator = toScaledInt(warrantyAmount) * pow10(IN_SCALE)
  return formatFixed(divRoundHalfUp(numerator, base), SCALE_RATE)
}

/**
 * 质保金额 = 合同金额 × 比例 ÷ 100，2 位 HALF_UP（后端 warrantyAmountOfRate）。
 * 合同金额为空或 0 时返回空串（同上）。
 */
export function amountOfRateText(amount, rate) {
  const base = toScaledInt(amount)
  if (base === ZERO) {
    return ''
  }
  const numerator = base * toScaledInt(rate)
  // base×比例 的 scale = 12，再 ÷100 → 10^(12-2+2)
  return formatFixed(divRoundHalfUp(numerator, pow10(IN_SCALE * 2 - SCALE_AMOUNT + 2)), SCALE_AMOUNT)
}

/** yyyy-MM-dd 文本 → { year, month(1-12), day }；非法返回 null */
export function parseDayText(value) {
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value === null || value === undefined ? '' : value))
  if (!matched) {
    return null
  }
  return { year: Number(matched[1]), month: Number(matched[2]), day: Number(matched[3]) }
}

/**
 * 质保到期日 = 生效日 + (max(1, 月数) − 1) 个月，再取该月**最后一天**
 * （后端 ContractRules.computeWarrantyEnd 的等价实现，含跨年与闰年）。
 *
 * 例：2025-06-01 + 12 → 2026-05-31；2026-01-31 + 2 → 2026-02-28；2026-03-15 + 24 → 2028-02-29。
 *
 * ⚠ 不能写成"构造一个当月同日期的 Date 再取月末"：`new Date(2026, 1, 31)` 会被 JS 归一成
 *   2026-03-03，于是 2026-01-31 + 2 个月会算成 2026-03-31（后端是 2026-02-28）。
 *   这里先算**目标月**（年月不溢出），再取该月最后一天。
 *
 * @param {string} startText 生效日（yyyy-MM-dd）
 * @param {number|string} months 期限（月）；空或小于 1 按 1 处理
 * @returns {string} 到期日（yyyy-MM-dd）；入参不足时返回空串
 */
export function warrantyEndText(startText, months) {
  const start = parseDayText(startText)
  if (!start) {
    return ''
  }
  const raw = Number(months)
  const actual = !raw || raw < 1 || !isFinite(raw) ? 1 : Math.trunc(raw)
  const totalMonths = start.year * 12 + (start.month - 1) + (actual - 1)
  const targetYear = Math.floor(totalMonths / 12)
  const targetMonth = totalMonths - targetYear * 12
  const lastDay = new Date(targetYear, targetMonth + 1, 0)
  return (
    String(lastDay.getFullYear()) +
    '-' +
    String(lastDay.getMonth() + 1).padStart(2, '0') +
    '-' +
    String(lastDay.getDate()).padStart(2, '0')
  )
}
