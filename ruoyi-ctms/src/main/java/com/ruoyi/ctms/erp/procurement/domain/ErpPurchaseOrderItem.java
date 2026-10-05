package com.ruoyi.ctms.erp.procurement.domain;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> 采购单行项对象，对应表 {@code t_ctms_purchase_order_item}（DDL 10/18）。 </p>
 *
 * <p> 公共列继承 {@link ErpDocItem}；本类只加一列 {@link #receivedQty}（已入库数量）。 </p>
 *
 * <p> ⚠ {@link #receivedQty} 的累加时机是<b>入库单过账时</b>（B4 任务 3.5，由 t4b 与本组后续补），
 * 下推入库单本身<b>不</b>回写它。本类只提供读取与"剩余可入库量"的派生口径，
 * 不提供任何写入入口 —— 回写唯一落点在过账服务。 </p>
 *
 * @author 二开
 */
public class ErpPurchaseOrderItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;

    /** 已入库数量（过账时累加、红冲时回退且不小于 0）。 */
    private BigDecimal receivedQty;

    /** 派生：剩余可入库量（不落库）。 */
    private BigDecimal remainingQty;

    /**
     * 剩余可入库量 = 数量 − 已入库（不小于 0）。
     *
     * @return 3 位小数剩余量
     */
    public BigDecimal remainingQty()
    {
        if (remainingQty != null)
        {
            return remainingQty;
        }
        BigDecimal total = getQty() == null ? BigDecimal.ZERO : getQty();
        BigDecimal received = getReceivedQty() == null ? BigDecimal.ZERO : getReceivedQty();
        BigDecimal remaining = total.subtract(received);
        return remaining.signum() < 0 ? BigDecimal.ZERO.setScale(ErpAmounts.QTY_SCALE) : remaining;
    }

    public BigDecimal getReceivedQty()
    {
        return receivedQty;
    }

    public void setReceivedQty(BigDecimal receivedQty)
    {
        this.receivedQty = receivedQty;
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
