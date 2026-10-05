package com.ruoyi.ctms.erp.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;

/**
 * <p> <b>库存账（明细 / 流水 / 一致性校验）的口径常量与纯函数</b>（2.0 B4 任务 7.1~7.5；
 * design D5/D7、REQ-STK-003、AC-73、AC-78）。 </p>
 *
 * <p> 本类<b>不查库、不依赖 Spring</b>，所有口径都能表驱动单测（见 {@code ErpLedgerRulesTest}）。 </p>
 *
 * <h3>「货品总额度」口径（最容易看错的一处，先把反直觉之处写在最前面）</h3>
 * <p> 额度 = 对<b>业务类型子串匹配「入库」且排除「调拨入库」</b>的流水，求
 * {@code Σ round(qty_change × unit_price, 2)}。三条反直觉之处： </p>
 * <ol>
 *   <li> <b>不是"入库单据的金额"</b>：取的是<b>数量变动 × 流水单价</b>，即"记了多少货值进来"，
 *        与单据的 {@code total_amount}（含税/折扣后的口径）不是一回事； </li>
 *   <li> <b>红冲自动抵扣</b>：红冲流水是"负数数量 × 同单价"，业务类型仍是
 *        {@code 红冲-采购入库}（含"入库"二字）⇒ 计入且为负，所以"入库 10×2 后被红冲"的额度是
 *        {@code 20 + (-20) = 0}；若按"数量变动 &gt; 0"筛，会得到虚高的 {@code 20}
 *        —— 那条错误口径在 {@code ErpLedgerRulesTest} 里被显式写成反例锁死； </li>
 *   <li> <b>调拨不算货值</b>：调拨入库/调拨出库（含各自的红冲）单价恒为 0，且被显式排除，
 *        所以仓库间搬运不改变任何（物料, 仓库）的额度 —— 但没有这条排除时，
 *        {@code 红冲-调拨入库} 会带着负数量混进"入库"集合里，把额度算歪。 </li>
 * </ol>
 *
 * <p> 逐项先按 2 位四舍五入再求和（{@link ErpAmounts#lineAmount(BigDecimal, BigDecimal)}
 * → {@link ErpAmounts#sum}),与 AC-78 的"各行舍入后之和"同源，避免多行小数单价出现 1 分钱差异。 </p>
 *
 * @author 二开
 */
public final class ErpLedgerRules
{
    /** 结存表在查询里的固定别名（{@code t_ctms_stock}）。 */
    public static final String ALIAS_STOCK = "s";

    /**
     * 流水表在查询里的固定别名（{@code t_ctms_stock_ledger}）。
     *
     * <p> ⚠ 库存明细要按数据范围过滤时，范围片段是<b>放在流水的子查询里</b>执行的
     * （结存表没有 {@code dept_id}/{@code create_id} 列，见 {@code ErpStockMapper} 的类注释），
     * 该子查询里的流水别名同样取 {@value #ALIAS_LEDGER} —— 因此拼片段时必须用这个别名，
     * 而不是 {@link #ALIAS_STOCK}，否则片段里的 {@code l.dept_id} 会因别名不存在而报错。 </p>
     */
    public static final String ALIAS_LEDGER = "l";

    /** 额度口径：计入的业务类型关键字（子串匹配）。 */
    public static final String QUOTA_INCLUDE_KEYWORD = "入库";

    /** 额度口径：排除的业务类型关键字（子串匹配，覆盖其红冲 {@code 红冲-调拨入库}）。 */
    public static final String QUOTA_EXCLUDE_KEYWORD = "调拨入库";

    /** 「商品类型名称-物料名称」的连接符（展示口径，前端与导出共用）。 */
    public static final String DISPLAY_SEPARATOR = "-";

    /** 库存记录不存在（详情/下钻用）。 */
    public static final String MSG_STOCK_NOT_FOUND = "库存记录不存在";

    /** 库存流水不存在。 */
    public static final String MSG_LEDGER_NOT_FOUND = "库存流水不存在";

    /** 范围外访问（业务码 403，与 B3/单据侧同口径）。 */
    public static final String MSG_SCOPE_FORBIDDEN = "无权访问该库存数据";

    /** 业务码：未找到。 */
    public static final Integer CODE_NOT_FOUND = 404;

    /** 业务码：越权。 */
    public static final Integer CODE_FORBIDDEN = 403;

    private ErpLedgerRules()
    {
    }

    /**
     * 该业务类型是否计入「货品总额度」。
     *
     * <p> 子串匹配「入库」且排除「调拨入库」；空白返回 false（不猜）。 </p>
     *
     * @param bizType 业务类型（可带红冲前缀）
     * @return 计入返回 true
     */
    public static boolean countedInQuota(String bizType)
    {
        if (isBlank(bizType))
        {
            return false;
        }
        String value = bizType.trim();
        return value.contains(QUOTA_INCLUDE_KEYWORD) && !value.contains(QUOTA_EXCLUDE_KEYWORD);
    }

    /**
     * 单条流水的额度贡献（{@code qty_change × unit_price}，先舍入到 2 位）。
     *
     * <p> 无论是否计入额度都能算（便于单测逐条对照）；是否计入由
     * {@link #countedInQuota(String)} 判定。 </p>
     *
     * @param qtyChange 数量变动（正入负出）
     * @param unitPrice 单价（可空 → 0）
     * @return 2 位金额贡献
     */
    public static BigDecimal quotaTerm(BigDecimal qtyChange, BigDecimal unitPrice)
    {
        return ErpAmounts.lineAmount(qtyChange, unitPrice);
    }

    /**
     * 一批流水的「货品总额度」（只统计计入的，见类注释的口径）。
     *
     * @param ledgers 流水集合（可空）
     * @return 2 位额度合计
     */
    public static BigDecimal quotaOf(List<ErpStockLedger> ledgers)
    {
        List<BigDecimal> terms = new ArrayList<>();
        if (ledgers != null)
        {
            for (ErpStockLedger ledger : ledgers)
            {
                if (ledger == null || !countedInQuota(ledger.getBizType()))
                {
                    continue;
                }
                terms.add(quotaTerm(ledger.getQtyChange(), ledger.getUnitPrice()));
            }
        }
        return ErpAmounts.sum(terms);
    }

    /**
     * 「商品类型名称-物料名称」展示口径（AC-73）。
     *
     * <p> 任一侧缺失时只出另一侧（不产生孤零零的连接符）；两侧都缺返回空串。 </p>
     *
     * @param productTypeName 商品类型名称
     * @param productName     物料名称
     * @return 形如 {@code 五金-螺丝}
     */
    public static String displayName(String productTypeName, String productName)
    {
        String type = trimToNull(productTypeName);
        String name = trimToNull(productName);
        if (type == null)
        {
            return name == null ? "" : name;
        }
        if (name == null)
        {
            return type;
        }
        return type + DISPLAY_SEPARATOR + name;
    }

    /**
     * 是否处于「低于安全库存」状态（{@code qty < safety_stock}；两侧空值按 0 处理）。
     *
     * @param qty         结存数量
     * @param safetyStock 安全库存
     * @return 低于返回 true
     */
    public static boolean belowSafetyStock(BigDecimal qty, BigDecimal safetyStock)
    {
        BigDecimal actual = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal safety = safetyStock == null ? BigDecimal.ZERO : safetyStock;
        return actual.compareTo(safety) < 0;
    }

    /**
     * 空白判定（null / 空串 / 全空白）。
     *
     * @param value 值
     * @return 空白返回 true
     */
    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 去空白并归一（空 → null）。
     *
     * @param value 值
     * @return 归一值
     */
    public static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
