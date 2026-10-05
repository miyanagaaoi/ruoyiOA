package com.ruoyi.ctms.erp.sales.domain;

import java.util.Date;

import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>销售申请单表头</b>（表 {@code t_ctms_sales_request}，DDL 见
 * {@code sql/二开-进销存.sql} 第 209~254 行）。 </p>
 *
 * <p> 公共列（单号/单据日期/状态/归属部门/创建人快照/经办人/关联合同/来源三列/痕迹列/过账标记/
 * 软删除）全部来自 {@link ErpDocHeader}；本类只声明销售申请特有的列与查询条件。 </p>
 *
 * <p> <b>与采购申请单的三处刻意不对称</b>（参考仓库 `models_doc.py:195-210`，不得"顺手统一"）： </p>
 * <ol>
 *   <li> <b>无 {@code total_amount} 列</b>：销售申请单的金额由行项金额现算，表上不落合计列；
 *        销售订单表才有 {@code total_amount}（与采购单对称）； </li>
 *   <li> {@code customer_id} <b>可空、无外键</b>（移植清单 §2.8 #17）：未绑定客户档案时用
 *        {@code customer_name_text} 文本兜底展示； </li>
 *   <li> 特有列是"销售部门 + 期望交货日期"，采购申请是"需求部门 + 需求日期"。 </li>
 * </ol>
 *
 * <p> {@code items} 是详情/编辑的装配字段（非持久化列）：新增与修改时承载待写入的行项，
 * 详情时承载已落库的行项。 </p>
 *
 * <p> <b>为什么继承 {@link BaseEntity} 而不是 {@link ErpDocHeader}</b>：本类需要
 * {@code params}（列表的日期区间条件）与分页入参 {@code pageNum}/{@code pageSize}
 * （由 RuoYi 的分页拦截器从 {@code startPage()} 读取）。{@link ErpDocHeader} 已经提供
 * 了全部单据公共列，而 {@link BaseEntity} 提供查询上下文，两者不可同时继承，
 * 因此本类<b>镜像</b>地继承 {@code BaseEntity} 并按 {@link ErpDocHeader} 的列名取值。
 * 单据的公共<b>行为</b>（状态机、金额口径、快照写入、数据范围）仍全部来自 {@code base} 包，
 * 不在这里另写一份。 </p>
 *
 * @author 二开
 */
public class ErpSalesRequest extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID）。 */
    private String id;

    /** 单号（唯一；作废/删除占号不复用）。 */
    private String docNo;

    /** 单据日期。 */
    private Date docDate;

    /** 状态（{@code ErpDocStatus} 取值之一）。 */
    private String status;

    /** 归属部门ID（创建时快照；数据范围 DEPT 判定依据）。 */
    private String deptId;

    /** 创建人用户ID（创建时快照；数据范围 SELF 判定依据）。 */
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

    /** 合同编号快照（关联时写入；不回写合同）。 */
    private String contractNo;

    /** 下推来源单据类型（本表恒为空：申请单是链路源头）。 */
    private String sourceDocType;

    /** 下推来源单据ID（可空）。 */
    private String sourceDocId;

    /** 下推来源单号快照（可空）。 */
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

    /** 过账标记：{@code '0'} / {@code '1'}（申请单无库存动作，恒为 '0'）。 */
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

    /** 客户档案ID（<b>可空、无外键</b>：未绑定档案时用文本兜底）。 */
    private String customerId;

    /** 客户名称文本（未绑定档案时的兜底展示，可空）。 */
    private String customerNameText;

    /** 销售部门ID（可空，仅反查名称）。 */
    private String salesDeptId;

    /** 期望交货日期（可空）。 */
    private Date expectDeliveryDate;

    /* ==================== 非持久化字段（详情装配 / 查询条件） ==================== */

    /** 行项明细（新增/修改时承载待写入的行项；详情时承载已落库的行项）。 */
    private java.util.List<ErpSalesRequestItem> items;

    /** 字段级变更历史（详情返回）。 */
    private java.util.List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs;

    /** 列表关键字：模糊匹配单号 / 客户名称 / 客户文本 / 备注。 */
    private String keyword;

    /** 单据日期区间起（{@code yyyy-MM-dd} 字符串）。 */
    private String beginDocDate;

    /** 单据日期区间止（{@code yyyy-MM-dd} 字符串）。 */
    private String endDocDate;

    /** 是否包含已作废："1" 才包含；其它值（含 null）都只返回未作废（规格默认口径）。 */
    private String includeVoided;

    /** 数据范围 SQL 片段：由服务层按白名单拼好后传入，Mapper 原样拼接（绝不来自请求参数）。 */
    private String dataScopeSql;

    /* ==================== 列表派生列（不落库；服务层返回前现算） ==================== */

    /** 列表派生：剩余可下推量合计（= Σ max(0, qty − ordered_qty)；供前端控制"下推"按钮显隐）。 */
    private java.math.BigDecimal remainQtySum;

    /** 列表派生：是否还有可下推量（= remainQtySum &gt; 0）。 */
    private Boolean canPush;

    /** 列表派生：单据金额合计（本表<b>无</b>该列，由行项"先舍入再汇总"现算）。 */
    private java.math.BigDecimal totalAmount;

    /**
     * 是否可下推（{@code canPush} 的唯一计算口径：剩余量 &gt; 0）。
     *
     * <p> 刻意<b>不</b>包含状态门槛："仅已审核可推"由下推接口再校验一次；
     * 因为"归零即完成"后状态是 {@code completed} 而剩余量同样为 0，
     * 用状态做显隐会让"已完成但还有余量"的脏数据永远推不出去。 </p>
     *
     * @return 剩余量大于 0 返回 true
     */
    public boolean computeCanPush()
    {
        return remainQtySum != null && remainQtySum.signum() > 0;
    }

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

    public String getCustomerNameText()
    {
        return customerNameText;
    }

    public void setCustomerNameText(String customerNameText)
    {
        this.customerNameText = customerNameText;
    }

    public String getSalesDeptId()
    {
        return salesDeptId;
    }

    public void setSalesDeptId(String salesDeptId)
    {
        this.salesDeptId = salesDeptId;
    }

    public Date getExpectDeliveryDate()
    {
        return expectDeliveryDate;
    }

    public void setExpectDeliveryDate(Date expectDeliveryDate)
    {
        this.expectDeliveryDate = expectDeliveryDate;
    }

    public java.util.List<ErpSalesRequestItem> getItems()
    {
        return items;
    }

    public void setItems(java.util.List<ErpSalesRequestItem> items)
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

    public java.math.BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(java.math.BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }
}
