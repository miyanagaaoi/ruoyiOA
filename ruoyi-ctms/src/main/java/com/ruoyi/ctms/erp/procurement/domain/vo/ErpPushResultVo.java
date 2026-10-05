package com.ruoyi.ctms.erp.procurement.domain.vo;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;

/**
 * <p> 下推结果（任务 3.3 / 3.4 的返回值）。 </p>
 *
 * <p> <b>为什么返回一个 VO 而不是采购单本身</b>：接口层需要告诉前端两件事 ——
 * 生成了哪张草稿采购单，以及这次下推是否<b>触发了"归零即完成"</b>
 * （前端要据此刷新申请单状态并给出提示文案）。把后者塞进采购单对象会变成"下游带着上游的字段"，
 * 语义颠倒。 </p>
 *
 * @author 二开
 */
public class ErpPushResultVo
{
    /** 生成（或命中的）草稿采购单。 */
    private ErpPurchaseOrder order;

    /** 来源申请单ID。 */
    private String sourceDocId;

    /** 来源申请单号。 */
    private String sourceDocNo;

    /** 本次下推后，来源申请单是否已被自动置为已完成。 */
    private boolean autoCompleted;

    /** 来源申请单下推后的状态（{@code approved} / {@code completed}）。 */
    private String sourceStatus;

    /** 来源申请单下推后的剩余可下推量合计。 */
    private java.math.BigDecimal remainQtySum;

    /** 本次下推的总数量（供前端提示"已下推 X"）。 */
    private java.math.BigDecimal pushedQty;

    /** 提示文案（前端直接展示；为空表示无需提示）。 */
    private String message;

    public ErpPurchaseOrder getOrder()
    {
        return order;
    }

    public void setOrder(ErpPurchaseOrder order)
    {
        this.order = order;
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

    public boolean isAutoCompleted()
    {
        return autoCompleted;
    }

    public void setAutoCompleted(boolean autoCompleted)
    {
        this.autoCompleted = autoCompleted;
    }

    public String getSourceStatus()
    {
        return sourceStatus;
    }

    public void setSourceStatus(String sourceStatus)
    {
        this.sourceStatus = sourceStatus;
    }

    public java.math.BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(java.math.BigDecimal remainQtySum)
    {
        this.remainQtySum = remainQtySum;
    }

    public java.math.BigDecimal getPushedQty()
    {
        return pushedQty;
    }

    public void setPushedQty(java.math.BigDecimal pushedQty)
    {
        this.pushedQty = pushedQty;
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
