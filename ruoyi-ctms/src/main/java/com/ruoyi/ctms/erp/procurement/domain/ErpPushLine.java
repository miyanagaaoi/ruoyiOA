package com.ruoyi.ctms.erp.procurement.domain;

import java.math.BigDecimal;

/**
 * <p> <b>下推行项</b>（采购申请 → 采购单 / 销售申请 → 销售订单的入参单元）。 </p>
 *
 * <p> 语义三条（规格 {@code specs/erp/procurement/spec.md} 的「按剩余量全推」）： </p>
 * <ol>
 *   <li> {@link #srcItemId} 为空 → 按行序对应来源行；</li>
 *   <li> {@link #qty} 为空或 ≤ 0 → 该行按剩余量<b>全推</b>；</li>
 *   <li> {@code srcItemId} 与 {@code qty} 都给 → 按指定数量推，超量被拒。</li>
 * </ol>
 *
 * @author 二开
 */
public class ErpPushLine
{
    /** 来源行项ID（可空：空表示按行序对应）。 */
    private String srcItemId;

    /** 本次下推数量（可空 / ≤0 表示按剩余量全推）。 */
    private BigDecimal qty;

    public String getSrcItemId()
    {
        return srcItemId;
    }

    public void setSrcItemId(String srcItemId)
    {
        this.srcItemId = srcItemId;
    }

    public BigDecimal getQty()
    {
        return qty;
    }

    public void setQty(BigDecimal qty)
    {
        this.qty = qty;
    }
}
