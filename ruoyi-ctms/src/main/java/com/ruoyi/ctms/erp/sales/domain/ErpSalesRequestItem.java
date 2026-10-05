package com.ruoyi.ctms.erp.sales.domain;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>销售申请单行项</b>（表 {@code t_ctms_sales_request_item}，DDL 见
 * {@code sql/二开-进销存.sql} 第 599~630 行）。 </p>
 *
 * <p> 公共列来自 {@link ErpDocItem}（物料与单位快照、数量/单价/金额、行备注），
 * 本类只声明销售申请特有的累计列： </p>
 * <ul>
 *   <li> {@code ordered_qty} —— <b>已下推数量</b>。销售申请 → 销售订单阶段
 *        <b>下推时即时累加</b>（不等过账，design D7），因此"剩余可下推量 = 数量 − 已下推量"
 *        （{@link #remainingQty()}）而不会出现同一行被重复下推。 </li>
 * </ul>
 *
 * <p> <b>为什么剩余量算在这里</b>：它是行项自己的两个列之差，任何一处调用都同源；
 * 若放在下推服务里各算一遍，8 类单据会出现第二份口径（任务 1.4 的"单一实现点"要求）。 </p>
 *
 * <p> {@code uomDecimals} 参与数量精度校验（{@link ErpAmounts#checkQuantityScale}）。 </p>
 *
 * @author 二开
 */
public class ErpSalesRequestItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;

    /** 已下推数量（销售申请 → 销售订单：下推时即时累加；DB 列 {@code decimal(16,3) NOT NULL DEFAULT 0}）。 */
    private BigDecimal orderedQty;

    /**
     * 剩余可下推量 = 数量 − 已下推数量（3 位定点，不做负数兜底：超量的拦截在下推服务里）。
     *
     * @return 剩余可下推量
     */
    public BigDecimal remainingQty()
    {
        return ErpAmounts.roundQty(qtyOrZero().subtract(orderedQtyOrZero()));
    }

    /**
     * 是否已推完（剩余可下推量为 0；"归零即完成"的逐行判据）。
     *
     * @return 剩余量 &le; 0 返回 true
     */
    public boolean isFullyOrdered()
    {
        return remainingQty().compareTo(BigDecimal.ZERO) <= 0;
    }

    /**
     * 累加已下推数量（下推成功后的即时回写；null 视为 0）。
     *
     * @param delta 本次下推数量
     */
    public void addOrderedQty(BigDecimal delta)
    {
        this.orderedQty = ErpAmounts.roundQty(orderedQtyOrZero().add(delta == null ? BigDecimal.ZERO : delta));
    }

    /** 数量兜底为 0（新建行项还没填数量时不至于 NPE）。 */
    private BigDecimal qtyOrZero()
    {
        return getQty() == null ? BigDecimal.ZERO : getQty();
    }

    /** 已下推量兜底为 0（DDL 默认 0，但内存对象可能未赋值）。 */
    private BigDecimal orderedQtyOrZero()
    {
        return orderedQty == null ? BigDecimal.ZERO : orderedQty;
    }

    public BigDecimal getOrderedQty()
    {
        return orderedQty;
    }

    public void setOrderedQty(BigDecimal orderedQty)
    {
        this.orderedQty = orderedQty;
    }
}
