package com.ruoyi.ctms.erp.ledger.domain;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * <p> <b>「（物料, 仓库）→ 流水累计数量」聚合行</b>（B4 任务 7.4 的一致性校验用）。 </p>
 *
 * <p> 一次查询取出全部 key 的累计和，避免按行逐个 {@code sumQtyChangeByKey} 造成 N+1
 * （结存行可能上千，运维接口不该打上千条 SQL）。 </p>
 *
 * @author 二开
 */
public class ErpLedgerQtySum implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 物料档案ID。 */
    private String productId;

    /** 仓库ID。 */
    private String warehouseId;

    /** 该 key 的流水数量变动累计和。 */
    private BigDecimal qtySum;

    public String getProductId()
    {
        return productId;
    }

    public void setProductId(String productId)
    {
        this.productId = productId;
    }

    public String getWarehouseId()
    {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId)
    {
        this.warehouseId = warehouseId;
    }

    public BigDecimal getQtySum()
    {
        return qtySum;
    }

    public void setQtySum(BigDecimal qtySum)
    {
        this.qtySum = qtySum;
    }

    @Override
    public String toString()
    {
        return "ErpLedgerQtySum{productId='" + productId + "', warehouseId='" + warehouseId
                + "', qtySum=" + qtySum + '}';
    }
}
