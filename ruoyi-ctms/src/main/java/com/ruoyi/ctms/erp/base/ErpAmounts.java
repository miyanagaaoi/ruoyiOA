package com.ruoyi.ctms.erp.base;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>金额与数量的唯一精度实现点</b>（2.0 B4 任务 1.4；design D9、REQ-NFR-007、AC-78）。 </p>
 *
 * <p> 口径三条，逐条对应参考仓库与冻结件： </p>
 * <ol>
 *   <li> 金额 {@code decimal(16,2)}、单价 {@code decimal(14,4)}、数量 {@code decimal(16,3)}
 *        —— 列宽口径来自 `models_doc.py` 的 {@code Numeric(16,2)/(14,4)/(16,3)}，
 *        与 {@code sql/二开-进销存.sql} 的列定义逐列一致； </li>
 *   <li> 行金额 = {@code qty × unit_price} 之后<b>先四舍五入到 2 位</b>
 *        （{@code ROUND_HALF_UP}，即参考侧 `doc_service.py:135` 的
 *        {@code quantize(Decimal("0.01"), ROUND_HALF_UP)}）； </li>
 *   <li> 单据合计 = <b>各行舍入后金额之和</b>（不是"先高精度求和再统一舍入"——
 *        后者在多行小数单价场景会产生 1 分钱差异，正是 AC-78 要锁死的行为）。 </li>
 * </ol>
 *
 * <p> <b>为什么禁止浮点</b>：{@code 0.1} 这类十进制小数在二进制浮点里不可精确表示，
 * 舍入行为不确定，会让"未审核 → 已审核 → 打印 → 导出"四处金额不一致（AC-78）。
 * 因此本类（以及 ruoyi-ctms 的所有业务计算）一律用 {@link BigDecimal}，
 * 由 {@code ErpPrecisionTest} 的源码级断言把"出现浮点类型"变成构建失败。 </p>
 *
 * <p> <b>本类不依赖 Spring / 不查库</b>，可直接表驱动单测（见 {@code ErpAmountsTest}）。 </p>
 *
 * @author 二开
 */
public final class ErpAmounts
{
    /** 金额小数位（列 {@code decimal(16,2)}）。 */
    public static final int AMOUNT_SCALE = 2;

    /** 单价小数位（列 {@code decimal(14,4)}）。 */
    public static final int PRICE_SCALE = 4;

    /** 数量小数位（列 {@code decimal(16,3)}；**内部统一 3 位**，与单位小数位是两回事）。 */
    public static final int QTY_SCALE = 3;

    /** 舍入方式：四舍五入（参考侧 {@code ROUND_HALF_UP}，注意**不是**银行家舍入）。 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private ErpAmounts()
    {
    }

    /**
     * 按给定小数位四舍五入（{@code null} 视为 0）。
     *
     * @param value 值
     * @param scale 小数位
     * @return 舍入后的值（scale 与入参一致）
     */
    public static BigDecimal round(BigDecimal value, int scale)
    {
        BigDecimal actual = value == null ? BigDecimal.ZERO : value;
        return actual.setScale(scale, ROUNDING);
    }

    /**
     * 金额四舍五入到 2 位。
     *
     * @param value 金额
     * @return 2 位金额
     */
    public static BigDecimal roundAmount(BigDecimal value)
    {
        return round(value, AMOUNT_SCALE);
    }

    /**
     * 单价四舍五入到 4 位。
     *
     * @param value 单价
     * @return 4 位单价
     */
    public static BigDecimal roundPrice(BigDecimal value)
    {
        return round(value, PRICE_SCALE);
    }

    /**
     * 数量四舍五入到 3 位（内部统一口径）。
     *
     * @param value 数量
     * @return 3 位数量
     */
    public static BigDecimal roundQty(BigDecimal value)
    {
        return round(value, QTY_SCALE);
    }

    /**
     * <p> 行金额：{@code qty × unit_price} 先按 {@code HALF_UP} 舍入到 2 位。 </p>
     *
     * <p> 例：{@code lineAmount(1, 0.125)} = {@code 0.13}（不是 0.12、也不是 0.125）。 </p>
     *
     * @param qty       数量（null 视为 0）
     * @param unitPrice 单价（null 视为 0）
     * @return 2 位行金额
     */
    public static BigDecimal lineAmount(BigDecimal qty, BigDecimal unitPrice)
    {
        BigDecimal quantity = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        return quantity.multiply(price).setScale(AMOUNT_SCALE, ROUNDING);
    }

    /**
     * 行金额（取自行项自身的数量与单价；调用方不必自己乘）。
     *
     * @param item 行项
     * @return 2 位行金额；item 为 null 时返回 0
     */
    public static BigDecimal lineAmountOf(ErpDocItem item)
    {
        return item == null ? BigDecimal.ZERO : lineAmount(item.getQty(), item.getUnitPrice());
    }

    /**
     * 把行项金额重算并写回（避免各单据服务各写一次乘法）。
     *
     * @param item 行项
     * @return 写入的 2 位金额；item 为 null 时返回 0
     */
    public static BigDecimal applyLineAmount(ErpDocItem item)
    {
        if (item == null)
        {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = lineAmount(item.getQty(), item.getUnitPrice());
        item.setAmount(amount);
        return amount;
    }

    /**
     * <p> <b>单据合计 = 各行舍入后金额之和</b>（唯一实现点）。 </p>
     *
     * <p> 行金额先逐行经 {@link #lineAmount(BigDecimal, BigDecimal)} 舍入，再相加；
     * 因此三行 {@code 1 × 0.125} 的合计是 {@code 0.13 × 3 = 0.39}（不是 {@code 0.375 → 0.38}）。 </p>
     *
     * @param items 行项集合（null 或空 → 0）
     * @return 2 位合计
     */
    public static BigDecimal totalOf(Collection<? extends ErpDocItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, ROUNDING);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ErpDocItem item : items)
        {
            if (item == null)
            {
                continue;
            }
            // 行金额可能尚未写回（新建的单据草稿）：以 qty×unit_price 现算为准，
            // 保证"列表金额"与"保存后的合计"永远同源
            total = total.add(item.getAmount() == null ? lineAmountOf(item) : item.getAmount());
        }
        return total.setScale(AMOUNT_SCALE, ROUNDING);
    }

    /**
     * 金额集合求和（导出/打印的纵向合计用；每一项仍先按 2 位舍入）。
     *
     * @param amounts 金额集合
     * @return 2 位合计
     */
    public static BigDecimal sum(Collection<BigDecimal> amounts)
    {
        BigDecimal total = BigDecimal.ZERO;
        if (amounts != null)
        {
            for (BigDecimal amount : amounts)
            {
                total = total.add(amount == null ? BigDecimal.ZERO : roundAmount(amount));
            }
        }
        return total.setScale(AMOUNT_SCALE, ROUNDING);
    }

    /**
     * 差异数量 = 实盘 − 账面（盘点口径，3 位小数；参考侧 {@code round(actual - book, 3)}）。
     *
     * @param actualQty 实盘数量
     * @param bookQty   账面数量
     * @return 3 位差异（正=盘盈，负=盘亏）
     */
    public static BigDecimal diffQty(BigDecimal actualQty, BigDecimal bookQty)
    {
        BigDecimal actual = actualQty == null ? BigDecimal.ZERO : actualQty;
        BigDecimal book = bookQty == null ? BigDecimal.ZERO : bookQty;
        return actual.subtract(book).setScale(QTY_SCALE, ROUNDING);
    }

    /**
     * <p> 数量精度校验：小数位数不得超过计量单位的小数位。 </p>
     *
     * <p> 例：单位为「个」({@code uomDecimals = 0}) 时录入 {@code 1.5} 必须被拒
     * （tasks.md §3.1 的断言之一）；提示里带行号，前端可直接定位。 </p>
     *
     * @param qty         数量
     * @param uomDecimals 单位小数位（null 视为不校验）
     * @param rowNo       行号（从 1 开始；≤0 时文案不带行号）
     * @throws ServiceException 超出允许的小数位
     */
    public static void checkQuantityScale(BigDecimal qty, Integer uomDecimals, int rowNo)
    {
        if (qty == null || uomDecimals == null)
        {
            return;
        }
        int allowed = uomDecimals.intValue();
        int actual = qty.stripTrailingZeros().scale();
        if (actual > allowed)
        {
            String prefix = rowNo > 0 ? ("行 " + rowNo + "：") : "";
            throw new ServiceException(prefix + "数量的最小单位是 " + allowed + " 位小数，当前为 " + actual + " 位");
        }
    }

    /**
     * 数量的展示文本（去掉无意义的尾随 0，但至少保留 0 位小数）。
     *
     * @param qty 数量
     * @return 文本（null → {@code "0"}）
     */
    public static String textOf(BigDecimal qty)
    {
        if (qty == null)
        {
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    /**
     * 金额的展示文本（固定 2 位，绝不出现科学计数法）。
     *
     * @param amount 金额
     * @return 形如 {@code 0.39}
     */
    public static String amountTextOf(BigDecimal amount)
    {
        return roundAmount(amount).toPlainString();
    }

    /**
     * 把一批金额（可含 null）按行顺序包装成列表，便于 {@link #sum(Collection)} 复用。
     *
     * @param amounts 金额数组
     * @return 列表
     */
    public static List<BigDecimal> asList(BigDecimal... amounts)
    {
        List<BigDecimal> list = new ArrayList<>();
        if (amounts != null)
        {
            for (BigDecimal amount : amounts)
            {
                list.add(amount);
            }
        }
        return list;
    }
}
