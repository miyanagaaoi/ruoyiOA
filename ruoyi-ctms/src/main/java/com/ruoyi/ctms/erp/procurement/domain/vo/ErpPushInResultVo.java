package com.ruoyi.ctms.erp.procurement.domain.vo;

import java.math.BigDecimal;

import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;

/**
 * <p> 采购单 → 入库单下推结果（2.0 B4 任务 3.5）。 </p>
 *
 * <p> 与 {@link ErpPushResultVo}（申请→采购单）刻意分成两个类型：两者的"目标单据"
 * 类型不同（采购单 vs 入库单），塞进同一个 VO 会变成"一半字段永远为空"。 </p>
 *
 * @author 二开
 */
public class ErpPushInResultVo
{
    /** 生成（或命中的）草稿入库单。 */
    private ErpStockIn stockIn;

    /** 来源采购单ID。 */
    private String sourceDocId;

    /** 来源采购单号。 */
    private String sourceDocNo;

    /** 本次下推的总数量。 */
    private BigDecimal pushedQty;

    /** 来源采购单下推后的剩余可入库量合计。 */
    private BigDecimal remainQtySum;

    /** 提示文案（前端直接展示）。 */
    private String message;

    public ErpStockIn getStockIn()
    {
        return stockIn;
    }

    public void setStockIn(ErpStockIn stockIn)
    {
        this.stockIn = stockIn;
    }

    public String getSourceDocId()
    {
        return sourceDocId;
    }

    public void setSourceDocId(String sourceDocId)
    {
        this.sourceDocId = sourceDocId;
    }

    public String getSourceDocNo()
    {
        return sourceDocNo;
    }

    public void setSourceDocNo(String sourceDocNo)
    {
        this.sourceDocNo = sourceDocNo;
    }

    public BigDecimal getPushedQty()
    {
        return pushedQty;
    }

    public void setPushedQty(BigDecimal pushedQty)
    {
        this.pushedQty = pushedQty;
    }

    public BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(BigDecimal remainQtySum)
    {
        this.remainQtySum = remainQtySum;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }
}
