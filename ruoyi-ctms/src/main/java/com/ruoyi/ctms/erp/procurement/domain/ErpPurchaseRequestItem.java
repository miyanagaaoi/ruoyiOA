package com.ruoyi.ctms.erp.procurement.domain;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.base.domain.ErpDocItem;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;

/**
 * <p> 采购申请单行项对象，对应表 {@code t_ctms_purchase_request_item}（DDL 9/18）。 </p>
 *
 * <p> 公共列（物料快照 / 数量 / 单价 / 行金额 / 来源行 / 行仓库 / 审计）继承
 * {@link ErpDocItem}；本类只加一列：{@link #orderedQty}（已下推数量）。 </p>
 *
 * <p> {@link #remainingQty} 是<b>派生值</b>（= 数量 − 已下推），不落库、不进 insert/update 列清单；
 * 前端与列表都用它决定"还能推多少"。 </p>
 *
 * @author 二开
 */
public class ErpPurchaseRequestItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;

    /** 已下推数量（申请 → 采购单<b>即时累加</b>；剩余量 = qty − ordered_qty）。 */
    private BigDecimal orderedQty;

    /** 派生：剩余可下推量（不落库）。 */
    private BigDecimal remainingQty;

    /**
     * 剩余可下推量（现算；{@link #remainingQty} 有值时优先用它，避免前端与后端两套口径）。
     *
     * @return 3 位小数、不小于 0 的剩余量
     */
    public BigDecimal remainingQty()
    {
        return remainingQty == null ? ErpPurRules.remainingQty(qty(), getOrderedQty()) : remainingQty;
    }

    /**
     * 数量（null 视为 0）。
     *
     * @return 数量
     */
    public BigDecimal qty()
    {
        return getQty() == null ? BigDecimal.ZERO : getQty();
    }

    public BigDecimal getOrderedQty()
    {
        return orderedQty;
    }

    public void setOrderedQty(BigDecimal orderedQty)
    {
        this.orderedQty = orderedQty;
    }

    public BigDecimal getRemainingQty()
    {
        return remainingQty;
    }

    public void setRemainingQty(BigDecimal remainingQty)
    {
        this.remainingQty = remainingQty;
    }
}
