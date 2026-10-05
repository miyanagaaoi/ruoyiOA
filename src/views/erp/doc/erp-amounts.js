/**
 * 单据金额口径的**转出口**（不是新实现）。
 *
 * 为什么这样做（REQ-NFR-009 明确要"消除重复实现"）：
 *   "行金额 = 数量 × 单价 **先** HALF_UP 到 2 位；单据合计 = 各行已舍入金额之和" 这一条口径，
 *   在 B3 里已经由 `ctms/contract/contract-rules.js` 实现（与后端 `ContractRules` 逐条对齐、
 *   十进制定点、不用浮点）。B4 的进销存单据是**同一条口径**（design D9 / PRD §7.4 C-1），
 *   所以这里只做一次改名转出口，让 `src/views/erp/**` 依赖本模块，
 *   而不是把定点实现抄进 ERP（抄一份 = 两处口径，迟早差 1 分钱）。
 *
 * 例：3 行「数量 1 × 单价 1.665」→ 每行 1.67 → 合计 **5.01**（先汇总再舍入会得 5.00）。
 */

export {
  SCALE_AMOUNT,
  SCALE_QTY,
  SCALE_PRICE,
  lineTotalText,
  sumLineTotalsText,
  moneyText,
  roundToText,
  parseDayText
} from '@/views/ctms/contract/contract-rules'
