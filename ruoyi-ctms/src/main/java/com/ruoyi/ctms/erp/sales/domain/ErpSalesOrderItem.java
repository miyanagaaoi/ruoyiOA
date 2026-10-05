package com.ruoyi.ctms.erp.sales.domain;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>销售订单行项</b>（表 {@code t_ctms_sales_order_item}，DDL 见
 * {@code sql/二开-进销存.sql} 第 633~664 行）。 </p>
 *
 * <p> 公共列来自 {@link ErpDocItem}（物料与单位快照、数量/单价/金额、<b>行级发货仓库</b>
 * —— 未填时回落表头的 {@code ship_warehouse_id}），本类只声明销售订单特有的累计列： </p>
 * <ul>
 *   <li> {@code shipped_qty} —— <b>已出库数量</b>。销售订单 → 出库单阶段
 *       <b>在出库单过账成功时累加</b>、红冲时回退且不小于 0（design D7 / tasks.md §4.3）。 </li>
 * </ul>
 *
 * <p> 与销售申请行项的差别刻意保留：申请行是"下推即累加"、订单行是"过账才累加"，
 * 因此两个类各自持有自己的累计列，不合并成一个 {@code usedQty}（design D7 的备选否决）。 </p>
 *
 * @author 二开
 */
public class ErpSalesOrderItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;

    /** 已出库数量（订单 → 出库单：过账时累加、红冲回退且不小于 0；DB 列 {@code decimal(16,3) NOT NULL DEFAULT 0}）。 */
    private BigDecimal shippedQty;

    /**
     * 剩余可出库量 = 数量 − 已出库数量（3 位定点）。
     *
     * @return 剩余可出库量
     */
    public BigDecimal remainingQty()
    {
        return ErpAmounts.roundQty(qtyOrZero().subtract(shippedQtyOrZero()));
    }

    /**
     * 累加已出库数量（过账成功时调用；null 视为 0）。
     *
     * @param delta 本次出库数量
     */
    public void addShippedQty(BigDecimal delta)
    {
        this.shippedQty = ErpAmounts.roundQty(shippedQtyOrZero().add(delta == null ? BigDecimal.ZERO : delta));
    }

    /**
     * 回退已出库数量（红冲时调用）。
     *
     * <p> 口径与参考仓库一致：回退后<b>不小于 0</b>（{@code max(0, shipped − qty)}）——
     * 历史数据被人为改动过时也不产生负数，避免"负数已出库量"污染剩余量计算。 </p>
     *
     * @param delta 本次出库数量
     */
    public void subtractShippedQty(BigDecimal delta)
    {
        BigDecimal next = shippedQtyOrZero().subtract(delta == null ? BigDecimal.ZERO : delta);
        if (next.compareTo(BigDecimal.ZERO) < 0)
        {
            next = BigDecimal.ZERO;
        }
        this.shippedQty = ErpAmounts.roundQty(next);
    }

    /** 数量兜底为 0。 */
    private BigDecimal qtyOrZero()
    {
        return getQty() == null ? BigDecimal.ZERO : getQty();
    }

    /** 已出库量兜底为 0。 */
    private BigDecimal shippedQtyOrZero()
    {
        return shippedQty == null ? BigDecimal.ZERO : shippedQty;
    }

    public BigDecimal getShippedQty()
    {
        return shippedQty;
    }

    public void setShippedQty(BigDecimal shippedQty)
    {
        this.shippedQty = shippedQty;
    }
}
