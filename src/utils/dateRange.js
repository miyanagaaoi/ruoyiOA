/**
 * 日期区间计算（PRD 9.3「只读计算：日期区间 → 天数」/ AC-38）。
 *
 * 抽成纯函数而不是写在组件里：跨月、跨年、闰年这些边界必须有**可重复的证据**，
 * 而"在界面上点两个日期"既慢又没法覆盖闰年（node 直跑单测最省事）。
 *
 * 两个实现要点：
 *   1. 一律按 **UTC 零点** 计算。用本地时间做 (end - start)/86400000 时，
 *      夏令时切换那天会算出 23/25 小时，四舍五入后差一天（国内暂时用不到，
 *      但部署到有夏令时的环境就是静默算错）。
 *   2. 认三种输入：Date、时间戳数字、`yyyy-MM-dd` / `yyyy/MM/dd` 字符串。
 *      认不出就返回 null —— **不返回 0**，否则"还没选日期"和"时长为 0 天"分不清。
 */

/** 纯数字的毫秒时间戳下限（早于此的裸数字当作"不是时间戳"，避免把年份当时间戳） */
const MIN_TIMESTAMP = 100000000000

/**
 * 解析成 UTC 零点时刻。
 *
 * @param {Date|number|string} value
 * @returns {Date|null} 解析失败返回 null
 */
export function toUtcDate(value) {
  if (value === null || value === undefined || value === '') {
    return null
  }
  if (value instanceof Date) {
    if (isNaN(value.getTime())) {
      return null
    }
    return new Date(Date.UTC(value.getFullYear(), value.getMonth(), value.getDate()))
  }
  if (typeof value === 'number') {
    if (!isFinite(value)) {
      return null
    }
    // 10 位是秒、13 位是毫秒；两者都支持，其余当作非法（不要把 2026 当时间戳）
    const ms = value < MIN_TIMESTAMP && value > 0 ? value * 1000 : value
    const d = new Date(ms)
    return isNaN(d.getTime())
      ? null
      : new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  }
  const s = String(value).trim()
  const m = s.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/)
  if (m) {
    const y = Number(m[1])
    const mo = Number(m[2])
    const d = Number(m[3])
    if (mo < 1 || mo > 12 || d < 1 || d > 31) {
      return null
    }
    const dt = new Date(Date.UTC(y, mo - 1, d))
    // 反向校验：2 月 30 日会被 Date 顺延成 3 月 2 日，这里要认出来并拒绝
    if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) {
      return null
    }
    return dt
  }
  const fallback = new Date(s)
  if (isNaN(fallback.getTime())) {
    return null
  }
  return new Date(Date.UTC(fallback.getFullYear(), fallback.getMonth(), fallback.getDate()))
}

/**
 * 两个日期间的自然日差（结束 − 开始）。
 *
 * 例：同一天 = 0；`2026-01-31 → 2026-03-01` = 29（2026 非闰年）；
 *     `2024-02-28 → 2024-03-01` = 2（闰年）；`2026-12-20 → 2027-01-05` = 16（跨年）。
 *
 * @returns {number|null} 任一侧无法解析时返回 null（不返回 0）
 */
export function daysBetween(start, end) {
  const a = toUtcDate(start)
  const b = toUtcDate(end)
  if (!a || !b) {
    return null
  }
  return Math.round((b.getTime() - a.getTime()) / 86400000)
}

export default { toUtcDate, daysBetween }
