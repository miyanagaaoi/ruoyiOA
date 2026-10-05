package com.ruoyi.ctms.erp.sales.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> <b>销售订单表头</b>（表 {@code t_ctms_sales_order}，DDL 见
 * {@code sql/二开-进销存.sql} 第 257~310 行）。 </p>
 *
 * <p> 与采购单对称：{@code customer_id} 是 <b>NOT NULL + 外键</b>（禁止引用供应商），
 * {@code customer_name} 是名称快照，另有 {@code total_amount}（= 各行先舍入到 2 位后之和，
 * 唯一实现点是 {@code com.ruoyi.ctms.erp.base.ErpAmounts#totalOf}）。 </p>
 *
 * <p> <b>发货信息 5 列</b>（tasks.md §4.1）：{@code delivery_date}（交货日期）、
 * {@code delivery_address}（收货地址）、{@code contact_name}（联系人）、
 * {@code contact_phone}（联系电话）、{@code ship_warehouse_id}（默认发货仓库，可空、无外键）。
 * 行项的 {@code warehouse_id} 未填时回落表头的这一列。 </p>
 *
 * <p> 继承 {@link BaseEntity} 的理由与 {@link ErpSalesRequest} 相同（需要 {@code params}
 * 与分页入参；单据公共行为仍全部来自 {@code erp.base} 包）。 </p>
 *
 * @author 二开
 */
public class ErpSalesOrder extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID）。 */
    private String id;

    /** 单号（唯一）。 */
    private String docNo;

    /** 单据日期。 */
    private Date docDate;

    /** 状态（{@code ErpDocStatus} 取值之一）。 */
    private String status;

    /** 归属部门ID（创建时快照）。 */
    private String deptId;

    /** 创建人用户ID（创建时快照）。 */
    private String createId;

    /** 创建人登录名快照。 */
    private String createBy;

    /** 经办人用户ID（可空）。 */
    private String handlerUserId;

    /** 经办人姓名快照（可空）。 */
    private String handlerName;

    /** 备注。 */
    private String remark;

    /** 关联合同ID（可空；仅销售方向）。 */
    private String contractId;

    /** 合同编号快照。 */
    private String contractNo;

    /** 下推来源单据类型（{@code sales_request}）。 */
    private String sourceDocType;

    /** 下推来源单据ID。 */
    private String sourceDocId;

    /** 下推来源单号快照。 */
    private String sourceDocNo;

    /** 提交人用户ID。 */
    private String submittedBy;

    /** 提交时间。 */
    private Date submittedAt;

    /** 审核人用户ID（反审核时置空）。 */
    private String approvedBy;

    /** 审核时间（反审核时置空）。 */
    private Date approvedAt;

    /** 作废人用户ID。 */
    private String voidedBy;

    /** 作废时间。 */
    private Date voidedAt;

    /** 作废/驳回原因。 */
    private String voidReason;

    /** 过账标记：{@code '0'} / {@code '1'}（订单无库存动作，恒为 '0'）。 */
    private String posted;

    /** 软删除标志：{@code '0'} 未删除 / {@code '1'} 已删除。 */
    private String delFlag;

    /** 创建时间。 */
    private Date createTime;

    /** 更新时间。 */
    private Date updateTime;

    /** 更新人用户ID。 */
    private String updateId;

    /** 更新人登录名快照。 */
    private String updateBy;

    /** 客户档案ID（DB 级 NOT NULL + FK：禁止引用供应商档案）。 */
    private String customerId;

    /** 客户名称快照（改名/停用后历史单据仍显示旧名）。 */
    private String customerName;

    /** 销售部门ID（可空，仅反查名称）。 */
    private String salesDeptId;

    /** 交货日期（可空）。 */
    private Date deliveryDate;

    /** 收货地址（可空）。 */
    private String deliveryAddress;

    /** 联系人（可空）。 */
    private String contactName;

    /** 联系电话（可空）。 */
    private String contactPhone;

    /** 默认发货仓库ID（可空、无外键；行项未填仓库时回落此列）。 */
    private String shipWarehouseId;

    /** 币种（仅记录不换算）。 */
    private String currency;

    /** 单据金额合计 = 各行先 HALF_UP 到 2 位后之和（{@code ErpAmounts.totalOf} 是唯一实现点）。 */
    private BigDecimal totalAmount;

    /* ==================== 非持久化字段（详情装配 / 查询条件） ==================== */

    /** 行项明细（新增/修改时承载待写入的行项；详情时承载已落库的行项）。 */
    private java.util.List<ErpSalesOrderItem> items;

    /** 字段级变更历史（详情返回）。 */
    private java.util.List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs;

    /** 列表关键字：模糊匹配单号 / 客户名称 / 收货地址 / 备注。 */
    private String keyword;

    /** 单据日期区间起（{@code yyyy-MM-dd} 字符串）。 */
    private String beginDocDate;

    /** 单据日期区间止（{@code yyyy-MM-dd} 字符串）。 */
    private String endDocDate;

    /** 是否包含已作废："1" 才包含；其它值（含 null）都只返回未作废。 */
    private String includeVoided;

    /** 数据范围 SQL 片段（服务层白名单拼出，Mapper 原样拼接）。 */
    private String dataScopeSql;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
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

    public String getDeptId()
    {
        return deptId;
    }

    public void setDeptId(String deptId)
    {
        this.deptId = deptId;
    }

    public String getCreateId()
    {
        return createId;
    }

    public void setCreateId(String createId)
    {
        this.createId = createId;
    }

    @Override
    public String getCreateBy()
    {
        return createBy;
    }

    @Override
    public void setCreateBy(String createBy)
    {
        this.createBy = createBy;
    }

    public String getHandlerUserId()
    {
        return handlerUserId;
    }

    public void setHandlerUserId(String handlerUserId)
    {
        this.handlerUserId = handlerUserId;
    }

    public String getHandlerName()
    {
        return handlerName;
    }

    public void setHandlerName(String handlerName)
    {
        this.handlerName = handlerName;
    }

    @Override
    public String getRemark()
    {
        return remark;
    }

    @Override
    public void setRemark(String remark)
    {
        this.remark = remark;
    }

    public String getContractId()
    {
        return contractId;
    }

    public void setContractId(String contractId)
    {
        this.contractId = contractId;
    }

    public String getContractNo()
    {
        return contractNo;
    }

    public void setContractNo(String contractNo)
    {
        this.contractNo = contractNo;
    }

    public String getSourceDocType()
    {
        return sourceDocType;
    }

    public void setSourceDocType(String sourceDocType)
    {
        this.sourceDocType = sourceDocType;
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

    public String getSubmittedBy()
    {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy)
    {
        this.submittedBy = submittedBy;
    }

    public Date getSubmittedAt()
    {
        return submittedAt;
    }

    public void setSubmittedAt(Date submittedAt)
    {
        this.submittedAt = submittedAt;
    }

    public String getApprovedBy()
    {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy)
    {
        this.approvedBy = approvedBy;
    }

    public Date getApprovedAt()
    {
        return approvedAt;
    }

    public void setApprovedAt(Date approvedAt)
    {
        this.approvedAt = approvedAt;
    }

    public String getVoidedBy()
    {
        return voidedBy;
    }

    public void setVoidedBy(String voidedBy)
    {
        this.voidedBy = voidedBy;
    }

    public Date getVoidedAt()
    {
        return voidedAt;
    }

    public void setVoidedAt(Date voidedAt)
    {
        this.voidedAt = voidedAt;
    }

    public String getVoidReason()
    {
        return voidReason;
    }

    public void setVoidReason(String voidReason)
    {
        this.voidReason = voidReason;
    }

    public String getPosted()
    {
        return posted;
    }

    public void setPosted(String posted)
    {
        this.posted = posted;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    @Override
    public Date getCreateTime()
    {
        return createTime;
    }

    @Override
    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    @Override
    public Date getUpdateTime()
    {
        return updateTime;
    }

    @Override
    public void setUpdateTime(Date updateTime)
    {
        this.updateTime = updateTime;
    }

    public String getUpdateId()
    {
        return updateId;
    }

    public void setUpdateId(String updateId)
    {
        this.updateId = updateId;
    }

    @Override
    public String getUpdateBy()
    {
        return updateBy;
    }

    @Override
    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
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

    public String getSalesDeptId()
    {
        return salesDeptId;
    }

    public void setSalesDeptId(String salesDeptId)
    {
        this.salesDeptId = salesDeptId;
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

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }

    public java.util.List<ErpSalesOrderItem> getItems()
    {
        return items;
    }

    public void setItems(java.util.List<ErpSalesOrderItem> items)
    {
        this.items = items;
    }

    public java.util.List<com.ruoyi.ctms.domain.CtmsChangeLog> getChangeLogs()
    {
        return changeLogs;
    }

    public void setChangeLogs(java.util.List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs)
    {
        this.changeLogs = changeLogs;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public String getBeginDocDate()
    {
        return beginDocDate;
    }

    public void setBeginDocDate(String beginDocDate)
    {
        this.beginDocDate = beginDocDate;
    }

    public String getEndDocDate()
    {
        return endDocDate;
    }

    public void setEndDocDate(String endDocDate)
    {
        this.endDocDate = endDocDate;
    }

    public String getIncludeVoided()
    {
        return includeVoided;
    }

    public void setIncludeVoided(String includeVoided)
    {
        this.includeVoided = includeVoided;
    }

    public String getDataScopeSql()
    {
        return dataScopeSql;
    }

    public void setDataScopeSql(String dataScopeSql)
    {
        this.dataScopeSql = dataScopeSql;
    }

    /* ==================== 列表派生列（不落库；服务层返回前现算） ==================== */

    /** 列表派生：剩余可出库量合计（= Σ max(0, qty − shipped_qty)）。t5b 的"下推出库单"入口用它显隐。 */
    private java.math.BigDecimal remainQtySum;

    /** 列表派生：是否还有可出库量（= remainQtySum &gt; 0）。 */
    private Boolean canPush;

    /**
     * 是否可下推（销售订单 → 出库单；t5b）。口径与申请单一致：只看剩余量 &gt; 0。
     *
     * @return 剩余可出库量大于 0 返回 true
     */
    public boolean computeCanPush()
    {
        return remainQtySum != null && remainQtySum.signum() > 0;
    }

    public java.math.BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(java.math.BigDecimal remainQtySum)
    {
        this.remainQtySum = remainQtySum;
    }

    public Boolean getCanPush()
    {
        return canPush;
    }

    public void setCanPush(Boolean canPush)
    {
        this.canPush = canPush;
    }
}
