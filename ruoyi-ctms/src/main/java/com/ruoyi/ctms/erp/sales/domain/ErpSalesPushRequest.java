package com.ruoyi.ctms.erp.sales.domain;

import java.util.List;

/**
 * <p> <b>下推请求体</b>（销售申请 → 销售订单，tasks.md §4.2）。 </p>
 *
 * <p> {@code lines} 为空/未给时表示"<b>全部行项按剩余量全推</b>"（参考仓库 {@code rows=None} 的语义）；
 * 给了就按 {@link ErpSalesPushLine} 的匹配规则落到具体来源行，且本次下推只覆盖给到的行。 </p>
 *
 * <p> 生成的下游单据是<b>草稿</b>销售订单：来源单号写进 {@code source_doc_type/id/no}，
 * 行项写 {@code src_item_id}（design D7）。 </p>
 *
 * @author 二开
 */
public class ErpSalesPushRequest
{
    /** 目标单据（表头）字段：单据日期（可空：为空时沿用来源申请单的日期）。 */
    private java.util.Date docDate;

    /** 目标单据：备注（可空：默认写"由销售申请单 xxx 下推生成"）。 */
    private String remark;

    /** 目标单据：默认发货仓库ID（可空）。 */
    private String shipWarehouseId;

    /** 目标单据：交货日期（可空；默认带过来源申请单的"期望交货日期"）。 */
    private java.util.Date deliveryDate;

    /** 目标单据：收货地址（可空）。 */
    private String deliveryAddress;

    /** 目标单据：联系人（可空）。 */
    private String contactName;

    /** 目标单据：联系电话（可空）。 */
    private String contactPhone;

    /** 下推行集合（空表示按剩余量全推）。 */
    private List<ErpSalesPushLine> lines;

    public java.util.Date getDocDate()
    {
        return docDate;
    }

    public void setDocDate(java.util.Date docDate)
    {
        this.docDate = docDate;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }

    public String getShipWarehouseId()
    {
        return shipWarehouseId;
    }

    public void setShipWarehouseId(String shipWarehouseId)
    {
        this.shipWarehouseId = shipWarehouseId;
    }

    public java.util.Date getDeliveryDate()
    {
        return deliveryDate;
    }

    public void setDeliveryDate(java.util.Date deliveryDate)
    {
        this.deliveryDate = deliveryDate;
    }

    public String getDeliveryAddress()
    {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress)
    {
        this.deliveryAddress = deliveryAddress;
    }

    public String getContactName()
    {
        return contactName;
    }

    public void setContactName(String contactName)
    {
        this.contactName = contactName;
    }

    public String getContactPhone()
    {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone)
    {
        this.contactPhone = contactPhone;
    }

    public List<ErpSalesPushLine> getLines()
    {
        return lines;
    }

    public void setLines(List<ErpSalesPushLine> lines)
    {
        this.lines = lines;
    }
}
