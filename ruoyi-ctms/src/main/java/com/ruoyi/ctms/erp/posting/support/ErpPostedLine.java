package com.ruoyi.ctms.erp.posting.support;

import java.math.BigDecimal;

/**
 * <p> <b>一条已落库的过账结果行</b>（2.0 B4 任务 5.2/5.4）。 </p>
 *
 * <p> 三个字段容易被混用，这里一次说清： </p>
 * <ul>
 *   <li> {@code qtyChange} = <b>本次写进流水</b>的数量变动（红冲时是反向值）； </li>
 *   <li> {@code originalQtyChange} = <b>被冲销的原变动</b>（正常过账时与 {@code qtyChange} 相同；
 *        红冲时保留原符号，供采购/销售线把"已入库量/已出库量"回退）； </li>
 *   <li> {@code qtyAfter} = 该行写完后的结存快照（同一单据同一（物料, 仓库）的多行是逐行累加的）。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpPostedLine
{
    /** 行号（1 起）。 */
    private int rowNo;

    /** 物料ID。 */
    private String productId;

    /** 仓库ID。 */
    private String warehouseId;

    /** 本次写入流水的数量变动（正入负出；红冲为反向）。 */
    private BigDecimal qtyChange;

    /** 被冲销的原变动（正常过账 = qtyChange；红冲 = 原符号值）。 */
    private BigDecimal originalQtyChange;

    /** 单价（记录性）。 */
    private BigDecimal unitPrice;

    /** 变动后结存快照。 */
    private BigDecimal qtyAfter;

    /** 来源行项ID（可空）。 */
    private String srcItemId;

    /** 流水ID（刚写入的那条）。 */
    private String ledgerId;

    /** 是否红冲行。 */
    private boolean reversal;

    public int getRowNo()
    {
        return rowNo;
    }

    public void setRowNo(int rowNo)
    {
        this.rowNo = rowNo;
    }

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

    public BigDecimal getQtyChange()
    {
        return qtyChange;
    }

    public void setQtyChange(BigDecimal qtyChange)
    {
        this.qtyChange = qtyChange;
    }

    public BigDecimal getOriginalQtyChange()
    {
        return originalQtyChange;
    }

    public void setOriginalQtyChange(BigDecimal originalQtyChange)
    {
        this.originalQtyChange = originalQtyChange;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getQtyAfter()
    {
        return qtyAfter;
    }

    public void setQtyAfter(BigDecimal qtyAfter)
    {
        this.qtyAfter = qtyAfter;
    }

    public String getSrcItemId()
    {
        return srcItemId;
    }

    public void setSrcItemId(String srcItemId)
    {
        this.srcItemId = srcItemId;
    }

    public String getLedgerId()
    {
        return ledgerId;
    }

    public void setLedgerId(String ledgerId)
    {
        this.ledgerId = ledgerId;
    }

    public boolean isReversal()
    {
        return reversal;
    }

    public void setReversal(boolean reversal)
    {
        this.reversal = reversal;
    }

    @Override
    public String toString()
    {
        return "ErpPostedLine{productId='" + productId + "', warehouseId='" + warehouseId + "', qtyChange="
                + qtyChange + ", qtyAfter=" + qtyAfter + ", reversal=" + reversal + '}';
    }
}
