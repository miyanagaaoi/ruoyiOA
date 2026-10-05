package com.ruoyi.ctms.erp.base.domain;

import java.io.Serializable;
import java.util.Date;

import com.ruoyi.ctms.erp.base.ErpDocStatus;

/**
 * <p> <b>单据表头公共字段</b>（2.0 B4 任务 1.4；design D1 的"泛型公共层 + 每表独立实体"）。 </p>
 *
 * <p> 字段级来源 = 参考仓库 `models_doc.py:62-97` 的 {@code DocMixin}，逐列对应关系见
 * {@code sql/二开-进销存.sql} 的建表注释与 {@code notes/01-base.md} §4 的对照表。
 * 8 类单据的实体（{@code CtmsPurchaseOrder} 等）继承本类，只声明自己的特有字段。 </p>
 *
 * <p> <b>与参考侧的两处刻意差异</b> </p>
 * <ul>
 *   <li> {@code org_id} → {@code deptId}、{@code created_by} → {@code createId}：
 *        RuoYi 的数据范围列名惯例（移植清单 §6.3 的注意第 ②③ 条），且按 F-3 收紧为
 *        NOT NULL + 外键； </li>
 *   <li> {@code deleted} → {@code delFlag char(1)}：与 B3 的软删除标志同款。 </li>
 * </ul>
 *
 * <p> <b>归属部门是创建时的快照</b>（design D10 的实现注意）：{@link #applyCreateSnapshot} 在
 * 创建时写一次，之后编辑/流转都<b>不</b>重算 —— 人员调岗不会回溯历史单据的可见范围。 </p>
 *
 * <p> <b>字段可见性口径</b>：28 个公共字段是 {@code protected}（子类可直接读写，避免 8 类单据
 * 各自绕过 getter 造成编译失败），同时提供完整 getter/setter 供 MyBatis 与外部调用。
 * ⚠ 子类<b>不得</b>重复声明同名字段 —— 那会影子化基类字段，让 MyBatis 映射分叉
 * （子类只声明自己的<b>专有</b>字段，例如 {@code supplierId}）。 </p>
 *
 * @author 二开
 */
public abstract class ErpDocHeader implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID）。 */
    protected String id;

    /** 单号（唯一）。 */
    protected String docNo;

    /** 单据日期。 */
    protected Date docDate;

    /** 状态（{@link ErpDocStatus} 的取值之一）。 */
    protected String status;

    /** 归属部门ID（创建时快照；数据范围 DEPT 判定依据）。 */
    protected String deptId;

    /** 创建人用户ID（创建时快照；数据范围 SELF 判定依据）。 */
    protected String createId;

    /** 创建人登录名快照。 */
    protected String createBy;

    /** 经办人用户ID（可空）。 */
    protected String handlerUserId;

    /** 经办人姓名快照（可空）。 */
    protected String handlerName;

    /** 备注。 */
    protected String remark;

    /** 关联合同ID（可空）。 */
    protected String contractId;

    /** 合同编号快照（关联时写入）。 */
    protected String contractNo;

    /** 下推来源单据类型（可空）。 */
    protected String sourceDocType;

    /** 下推来源单据ID（可空）。 */
    protected String sourceDocId;

    /** 下推来源单号快照（可空）。 */
    protected String sourceDocNo;

    /** 提交人用户ID。 */
    protected String submittedBy;

    /** 提交时间。 */
    protected Date submittedAt;

    /** 审核人用户ID（反审核时置空）。 */
    protected String approvedBy;

    /** 审核时间（反审核时置空）。 */
    protected Date approvedAt;

    /** 作废人用户ID。 */
    protected String voidedBy;

    /** 作废时间。 */
    protected Date voidedAt;

    /** 作废/驳回原因。 */
    protected String voidReason;

    /** 过账标记：{@code '0'} 未过账 / {@code '1'} 已过账（过账幂等依据）。 */
    protected String posted;

    /** 软删除标志：{@code '0'} 未删除 / {@code '1'} 已删除。 */
    protected String delFlag;

    /** 创建时间。 */
    protected Date createTime;

    /** 更新时间。 */
    protected Date updateTime;

    /** 更新人用户ID。 */
    protected String updateId;

    /** 更新人登录名快照。 */
    protected String updateBy;

    /** 未过账。 */
    public static final String POSTED_NO = "0";

    /** 已过账。 */
    public static final String POSTED_YES = "1";

    /** 未删除。 */
    public static final String DEL_FLAG_NORMAL = "0";

    /**
     * <p> 写入创建快照：创建人、归属部门、创建人姓名、初始状态、软删除标志。 </p>
     *
     * <p> 取值必须来自<b>服务端登录上下文</b>（{@code ErpDocScope.currentUserId()/currentDeptId()}），
     * 绝不接受请求参数 —— 否则数据范围可被伪造。 </p>
     *
     * @param userId   当前用户ID（服务端取值）
     * @param deptId   当前用户部门ID（服务端取值；为空时调用方必须先报错，见 ErpDocScope）
     * @param username 当前登录名（写入 create_by 快照）
     */
    public void applyCreateSnapshot(String userId, String deptId, String username)
    {
        this.createId = userId;
        this.deptId = deptId;
        this.createBy = username;
        if (this.status == null || this.status.trim().isEmpty())
        {
            this.status = ErpDocStatus.DRAFT;
        }
        if (this.posted == null || this.posted.trim().isEmpty())
        {
            this.posted = POSTED_NO;
        }
        if (this.delFlag == null || this.delFlag.trim().isEmpty())
        {
            this.delFlag = DEL_FLAG_NORMAL;
        }
    }

    /**
     * <p> 是否已过账（过账幂等短路与"反审核需红冲"的判定依据）。 </p>
     *
     * <p> <b>⚠ 方法名不能叫 {@code isPosted()}</b>（t31 事故）：{@code posted} 是
     * {@code String} 落库列（{@code '0'/'1'}，见 {@link #getPosted()}），若再有一个
     * {@code boolean isPosted()}，JavaBeans 口径下它们就是<b>同一属性 {@code posted} 的两个 getter</b>
     * 且类型互不兼容；MyBatis 会把这个属性包成 {@code AmbiguousMethodInvoker}，
     * 一旦 mapper XML 的 {@code <if test="posted != null">}（OGNL 动态 SQL）真的去读它，就抛
     * {@code Illegal overloaded getter method with ambiguous type for property 'posted'}
     * —— 8 类单据的查询/写路径整体 500，而纯单测（stub mapper、不走 XML/OGNL）全绿看不出来。
     * 因此便捷布尔判定固定使用<b>独立属性名</b> {@code postedFlag}，
     * 守卫见 {@code erp.base.ErpDomainReflectorTest}。 </p>
     *
     * @return 已过账返回 true
     */
    public boolean isPostedFlag()
    {
        return POSTED_YES.equals(posted);
    }

    /**
     * 是否处于可编辑状态（只有草稿可编辑）。
     *
     * @return 可编辑返回 true
     */
    public boolean isEditable()
    {
        return ErpDocStatus.isEditable(status);
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

    public String getCreateBy()
    {
        return createBy;
    }

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

    public String getRemark()
    {
        return remark;
    }

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

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public Date getUpdateTime()
    {
        return updateTime;
    }

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

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }
}
