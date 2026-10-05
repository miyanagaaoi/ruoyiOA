package com.ruoyi.ctms.erp.stockops.domain;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>盘点单行项</b>（表 {@code t_ctms_stocktake_item}，2.0 B4 任务 6.3/6.4）。 </p>
 *
 * <p> 在公共行项字段之外多四列（DDL 第 758~761 行）： </p>
 * <ul>
 *   <li> {@code bookQty} —— <b>账面数量</b>：生成行项时按该（物料, 仓库）结存<b>固化</b>，
 *        界面只读；此后无论结存怎么变，本次盘点的账面都不变（否则差异会随并发过账漂移）； </li>
 *   <li> {@code actualQty} —— 实盘数量：非负、精度受单位小数位约束； </li>
 *   <li> {@code diffQty} —— 差异 = 实盘 − 账面（定点 3 位，走 {@link ErpAmounts#diffQty}）； </li>
 *   <li> {@code diffReason} —— 差异原因（可空）。 </li>
 * </ul>
 *
 * <p> 公共列 {@code qty} / {@code unitPrice} / {@code amount} 在盘点里不使用（恒 0），
 * 保留是因为它们来自公共行项字段（见 DDL 的列注释）。 </p>
 *
 * @author 二开
 */
public class ErpStocktakeItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;

    /** 账面数量（生成行项时固化；界面只读）。 */
    private BigDecimal bookQty;

    /** 实盘数量（非负）。 */
    private BigDecimal actualQty;

    /** 差异 = 实盘 − 账面（定点 3 位；正=盘盈、负=盘亏）。 */
    private BigDecimal diffQty;

    /** 差异原因（可空）。 */
    private String diffReason;

    /**
     * 账面数量（{@code null} 视为 0）。
     *
     * @return 非 null 账面数量
     */
    public BigDecimal bookQtyOrZero()
    {
        return bookQty == null ? BigDecimal.ZERO : bookQty;
    }

    /**
     * 实盘数量（{@code null} 视为 0）。
     *
     * @return 非 null 实盘数量
     */
    public BigDecimal actualQtyOrZero()
    {
        return actualQty == null ? BigDecimal.ZERO : actualQty;
    }

    /**
     * 差异数量（{@code null} 视为 0）。
     *
     * @return 非 null 差异数量
     */
    public BigDecimal diffQtyOrZero()
    {
        return diffQty == null ? BigDecimal.ZERO : diffQty;
    }

    /**
     * 按"实盘 − 账面"重算并写入差异（全模块唯一的数量差异实现点）。
     *
     * @return 写入的 3 位差异
     */
    public BigDecimal recalcDiffQty()
    {
        this.diffQty = ErpAmounts.diffQty(actualQtyOrZero(), bookQtyOrZero());
        return this.diffQty;
    }

    public BigDecimal getBookQty()
    {
        return bookQty;
    }

    public void setBookQty(BigDecimal bookQty)
    {
        this.bookQty = bookQty;
    }

    public BigDecimal getActualQty()
    {
        return actualQty;
    }

    public void setActualQty(BigDecimal actualQty)
    {
        this.actualQty = actualQty;
    }

    public BigDecimal getDiffQty()
    {
        return diffQty;
    }

    public void setDiffQty(BigDecimal diffQty)
    {
        this.diffQty = diffQty;
    }

    public String getDiffReason()
    {
        return diffReason;
    }

    public void setDiffReason(String diffReason)
    {
        this.diffReason = diffReason;
    }

    @Override
    public String toString()
    {
        return "ErpStocktakeItem{productId='" + getProductId() + "', bookQty=" + bookQty
                + ", actualQty=" + actualQty + ", diffQty=" + diffQty + '}';
    }
}
