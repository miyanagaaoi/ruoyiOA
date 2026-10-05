package com.ruoyi.ctms.erp.sales.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.ruoyi.common.annotation.Excel;

/**
 * <p> <b>销售线导出的一行</b>（2.0 B4 任务 t28；销售申请单与销售订单共用一份列定义）。 </p>
 *
 * <p> <b>为什么是"一文档 × 一行项"的扁平行</b>：验收要求导出列"含<b>表头业务字段、
 * 客户快照与行项关键列</b>"，而 {@code ExcelUtil} 是单表模型（一个 {@code @Excel} 类 =
 * 一张 sheet），因此表头字段每行重复、行项字段平行展开——这也是 ERP 里
 * "订单 + 明细"导出的通行形态。单据级合计列（{@code totalAmount} / {@code remainQtySum}）
 * 在每行重复出现，便于在 Excel 里直接对列求和核对。 </p>
 *
 * <p> <b>金额口径与列表逐行一致</b>：行金额 = {@code qty × unitPrice} <b>先</b>按
 * {@code HALF_UP} 舍入到 2 位（{@code ErpAmounts.lineAmount}，写入行项时已固化），
 * 单据合计 = 各行舍入后金额之和（{@code ErpAmounts.totalOf}）。本类<b>不做</b>任何金额计算，
 * 只搬运已算好的值 —— 这样"导出金额"与"列表金额"不可能分叉。 </p>
 *
 * <p> <b>不复用实体上的注解</b>：列定义放在这个显式的导出行对象里（与 B3 的
 * {@code ContractExportRow}、T3 的 {@code ErpStockInExportRow} 同款），
 * 不给 domain 实体挂表现层注解。 </p>
 *
 * @author 二开
 */
public class ErpSalesExportRow
{
    /* ==================== 表头业务字段 ==================== */

    /** 单据类型（销售申请单 / 销售订单）。 */
    @Excel(name = "单据类型")
    private String docTypeLabel;

    /** 单号。 */
    @Excel(name = "单号", width = 22)
    private String docNo;

    /** 单据日期。 */
    @Excel(name = "单据日期", dateFormat = "yyyy-MM-dd", width = 14)
    private Date docDate;

    /** 状态（中文名，由 {@code ErpDocStatus.labelOf} 归一）。 */
    @Excel(name = "状态", width = 10)
    private String status;

    /** 客户档案ID（申请单可空：它允许只填文本）。 */
    @Excel(name = "客户ID", width = 18)
    private String customerId;

    /** 客户名称（订单是名称快照；申请单是文本兜底 {@code customer_name_text}）。 */
    @Excel(name = "客户", width = 20)
    private String customerName;

    /** 经办人姓名快照。 */
    @Excel(name = "经办人", width = 12)
    private String handlerName;

    /** 归属部门ID（创建时快照）。 */
    @Excel(name = "归属部门ID", width = 16)
    private String deptId;

    /** 关联合同编号快照。 */
    @Excel(name = "关联合同", width = 20)
    private String contractNo;

    /** 来源单号（下推链路，可空）。 */
    @Excel(name = "来源单号", width = 22)
    private String sourceDocNo;

    /** 交货日期（申请单取"期望交货日期"，订单取"交货日期"）。 */
    @Excel(name = "交货日期", dateFormat = "yyyy-MM-dd", width = 14)
    private Date deliveryDate;

    /** 收货地址（订单专有，可空）。 */
    @Excel(name = "收货地址", width = 26)
    private String deliveryAddress;

    /** 联系人（订单专有，可空）。 */
    @Excel(name = "联系人", width = 12)
    private String contactName;

    /** 联系电话（订单专有，可空）。 */
    @Excel(name = "联系电话", width = 16)
    private String contactPhone;

    /** 默认发货仓库ID（订单专有，可空）。 */
    @Excel(name = "发货仓库ID", width = 16)
    private String shipWarehouseId;

    /** 剩余可下推/可出库量合计（申请侧 = Σ max(0, qty − ordered_qty)；订单侧 = Σ max(0, qty − shipped_qty)）。 */
    @Excel(name = "剩余可下推量", width = 14)
    private BigDecimal remainQtySum;

    /** 单据金额合计（各行先 HALF_UP 到 2 位后之和）。 */
    @Excel(name = "单据金额合计", width = 14)
    private BigDecimal totalAmount;

    /** 备注。 */
    @Excel(name = "备注", width = 30)
    private String remark;

    /* ==================== 行项关键列 ==================== */

    /** 行序号（1 起）。 */
    @Excel(name = "行号", width = 8)
    private Integer seq;

    /** 物料编码快照。 */
    @Excel(name = "物料编码", width = 16)
    private String productCode;

    /** 物料名称快照。 */
    @Excel(name = "物料名称", width = 20)
    private String productName;

    /** 规格快照。 */
    @Excel(name = "规格", width = 14)
    private String spec;

    /** 单位名快照。 */
    @Excel(name = "单位", width = 10)
    private String uomName;

    /** 数量（定点 3 位）。 */
    @Excel(name = "数量", width = 12)
    private BigDecimal qty;

    /** 单价（定点 4 位）。 */
    @Excel(name = "单价", width = 12)
    private BigDecimal unitPrice;

    /** 行金额（先舍入到 2 位）。 */
    @Excel(name = "行金额", width = 12)
    private BigDecimal lineAmount;

    /** 行备注。 */
    @Excel(name = "行备注", width = 24)
    private String itemRemark;

    public String getDocTypeLabel()
    {
        return docTypeLabel;
    }

    public void setDocTypeLabel(String docTypeLabel)
    {
        this.docTypeLabel = docTypeLabel;
    }

    public String getDocNo()
    {
        return docNo;
    }

    public void setDocNo(String docNo)
    {
        this.docNo = docNo;
    }

    public Date getDocDate()
    {
        return docDate;
    }

    public void setDocDate(Date docDate)
    {
        this.docDate = docDate;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getCustomerId()
    {
        return customerId;
    }

    public void setCustomerId(String customerId)
    {
        this.customerId = customerId;
    }

    public String getCustomerName()
    {
        return customerName;
    }

    public void setCustomerName(String customerName)
    {
        this.customerName = customerName;
    }

    public String getHandlerName()
    {
        return handlerName;
    }

    public void setHandlerName(String handlerName)
    {
        this.handlerName = handlerName;
    }

    public String getDeptId()
    {
        return deptId;
    }

    public void setDeptId(String deptId)
    {
        this.deptId = deptId;
    }

    public String getContractNo()
    {
        return contractNo;
    }

    public void setContractNo(String contractNo)
    {
        this.contractNo = contractNo;
    }

    public String getSourceDocNo()
    {
        return sourceDocNo;
    }

    public void setSourceDocNo(String sourceDocNo)
    {
        this.sourceDocNo = sourceDocNo;
    }

    public Date getDeliveryDate()
    {
        return deliveryDate;
    }

    public void setDeliveryDate(Date deliveryDate)
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

    public String getShipWarehouseId()
    {
        return shipWarehouseId;
    }

    public void setShipWarehouseId(String shipWarehouseId)
    {
        this.shipWarehouseId = shipWarehouseId;
    }

    public BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(BigDecimal remainQtySum)
    {
        this.remainQtySum = remainQtySum;
    }

    public BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }

    public Integer getSeq()
    {
        return seq;
    }

    public void setSeq(Integer seq)
    {
        this.seq = seq;
    }

    public String getProductCode()
    {
        return productCode;
    }

    public void setProductCode(String productCode)
    {
        this.productCode = productCode;
    }

    public String getProductName()
    {
        return productName;
    }

    public void setProductName(String productName)
    {
        this.productName = productName;
    }

    public String getSpec()
    {
        return spec;
    }

    public void setSpec(String spec)
    {
        this.spec = spec;
    }

    public String getUomName()
    {
        return uomName;
    }

    public void setUomName(String uomName)
    {
        this.uomName = uomName;
    }

    public BigDecimal getQty()
    {
        return qty;
    }

    public void setQty(BigDecimal qty)
    {
        this.qty = qty;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getLineAmount()
    {
        return lineAmount;
    }

    public void setLineAmount(BigDecimal lineAmount)
    {
        this.lineAmount = lineAmount;
    }

    public String getItemRemark()
    {
        return itemRemark;
    }

    public void setItemRemark(String itemRemark)
    {
        this.itemRemark = itemRemark;
    }
}
