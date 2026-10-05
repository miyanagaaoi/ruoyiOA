package com.ruoyi.ctms.erp.posting.support;

/**
 * <p> <b>过账选项</b>（2.0 B4 任务 5.3/5.4 的两条例外口径；design D2 与 Risks 的"豁免面不能过大"）。 </p>
 *
 * <p> 默认行为是"按系统参数 {@code stock_allow_negative} 校验负库存"；
 * 只有两个明确场景会关闭校验，且都写在调用方（不是全局参数）： </p>
 * <ul>
 *   <li> <b>反审核红冲</b>：红冲不受负库存拦截（{@code 反审核红冲} 规格场景），
 *        由过账引擎内部固定使用 {@link #skipNegativeCheck()}； </li>
 *   <li> <b>盘亏出库单过账</b>（tasks.md §6.4）：账实不符本身即差异证据，
 *        被负库存拦截则盘点永远无法平账 —— 由盘点审核路径显式传入
 *        {@link #skipNegativeCheck()}，<b>不</b>改成全局参数。 </li>
 * </ul>
 *
 * <p> 该豁免面刻意做成"每次调用显式传参"：一旦变成系统参数，任何人都能通过改配置
 * 绕过全部出库校验（design.md Risks 的最后一条）。 </p>
 *
 * @author 二开
 */
public final class ErpPostingOptions
{
    private static final ErpPostingOptions DEFAULTS = new ErpPostingOptions(false);

    private static final ErpPostingOptions SKIP_NEGATIVE = new ErpPostingOptions(true);

    /** 是否跳过负库存校验。 */
    private final boolean skipNegativeCheck;

    private ErpPostingOptions(boolean skipNegativeCheck)
    {
        this.skipNegativeCheck = skipNegativeCheck;
    }

    /**
     * 默认选项：按系统参数校验负库存。
     *
     * @return 选项
     */
    public static ErpPostingOptions defaults()
    {
        return DEFAULTS;
    }

    /**
     * 跳过负库存校验（仅反审核红冲与盘亏豁免使用）。
     *
     * @return 选项
     */
    public static ErpPostingOptions skipNegativeCheck()
    {
        return SKIP_NEGATIVE;
    }

    /**
     * 是否跳过负库存校验。
     *
     * @return 跳过返回 true
     */
    public boolean isSkipNegativeCheck()
    {
        return skipNegativeCheck;
    }

    @Override
    public String toString()
    {
        return "ErpPostingOptions{skipNegativeCheck=" + skipNegativeCheck + '}';
    }
}
