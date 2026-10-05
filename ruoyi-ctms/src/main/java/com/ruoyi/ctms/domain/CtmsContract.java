package com.ruoyi.ctms.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 合同台账主表对象，对应表 {@code t_ctms_contract}（41 列，B3 第 4 组；DDL 见
 * {@code sql/二开-合同台账.sql} 第 288~344 行）。 </p>
 *
 * <p> 字段与 DDL <b>逐列对应</b>，类型口径见 {@code com.ruoyi.ctms.domain} 的 package-info：
 * {@code varchar/longtext → String}、{@code decimal → BigDecimal}、{@code int → Integer}、
 * {@code date/datetime → java.util.Date}、{@code char(1) → String}。 </p>
 *
 * <p> ⚠ {@code createBy} / {@code createTime} / {@code updateBy} / {@code updateTime} / {@code remark}
 * 由 {@link BaseEntity} 提供，本类<b>不重复声明</b>；{@code createId} / {@code updateId}
 * （创建人/更新人的用户ID快照）在基类里没有，因此单独声明。 </p>
 *
 * <p> <b>持久化字段</b>参与 Mapper XML 的 insert / update 列清单；
 * 本类下半部分的<b>非持久化字段</b>只服务于查询条件与详情装配（标签、行项、变更历史、框架子合同、
 * 关键字、日期区间、包含停用开关、数据范围片段），<b>绝不能</b>出现在 insert / update 的列里。 </p>
 *
 * <p> ⚠ 金额与数量一律 {@code BigDecimal}（规格 {@code REQ-NFR-007} / C-1 先舍入再汇总），
 * 本类不得出现二进制浮点类型。 </p>
 *
 * @author 二开
 */
public class CtmsContract extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID，由服务层生成后传入 Mapper，XML 里不写死） */
    private String id;

    /** 合同编号（全局唯一；停用占号不复用，靠软删除行仍占唯一索引实现） */
    private String contractNo;

    /** 合同名称 */
    private String name;

    /** 合同类型（中文类型名，取值集合落 {@code sys_dict_data.contract_types}） */
    private String type;

    /** 甲方文本（未绑定档案时以文本兜底展示） */
    private String partyA;

    /** 乙方文本（与甲方镜像） */
    private String partyB;

    /** 签订日期（可空；编号月份码为空时取当天） */
    private Date signDate;

    /** 生效日期（可空） */
    private Date effectiveDate;

    /** 标的物摘要（由行项覆盖落库，格式「名称(规格)×数量」分号连接） */
    private String subjectMatter;

    /** 合同金额（C-1 口径：各已舍入行总价之和） */
    private BigDecimal amount;

    /** 币种（仅记录不参与换算） */
    private String currency;

    /** 我方主体码（保持可空，非空校验在接口层） */
    private String subjectCode;

    /** 客户档案ID（仅销售方向填） */
    private String customerId;

    /** 供应商档案ID（仅采购方向填） */
    private String supplierId;

    /** 累计已付金额（允许超过合同金额） */
    private BigDecimal paidAmount;

    /** 是否启用质保：0-否 1-是 */
    private String hasWarranty;

    /** 质保金额（与 {@code warrantyRate} 二选一互换，可空） */
    private BigDecimal warrantyAmount;

    /** 质保比例%（与 {@code warrantyAmount} 二选一，可空） */
    private BigDecimal warrantyRate;

    /** 质保起始日（可空） */
    private Date warrantyStart;

    /** 质保月数（可空） */
    private Integer warrantyMonths;

    /** 质保到期日（可空；关闭质保必须清空） */
    private Date warrantyEnd;

    /** 质保是否已释放：0-否 1-是 */
    private String warrantyReleased;

    /** 质保释放日期（可空） */
    private Date warrantyReleaseDate;

    /** 质保备注（可空；关闭质保时清空的字段之一） */
    private String warrantyNote;

    /** 是否框架合同：0-否 1-是 */
    private String isFramework;

    /** 父框架合同ID（自引用，可空） */
    private String parentId;

    /** 到货状态（与进度状态互相独立、无联动） */
    private String arrivalStatus;

    /** 预计到货日期（可空） */
    private Date expectedArrivalDate;

    /** 进度状态（任意状态可互转、无顺序守卫） */
    private String status;

    /** 经办人（自由文本，可空） */
    private String ownerName;

    /** 所属部门ID（数据范围 DEPT 档命中该列） */
    private String deptId;

    /** 软删除标志：0-未删除 1-已停用（列表默认排除） */
    private String delFlag;

    /** 停用时间（可空；30 天恢复窗口的整数日差判定基准） */
    private Date deletedAt;

    /** 停用原因（可空；「原因必填」是接口层强制） */
    private String deletedReason;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID */
    private String updateId;

    /* ==================== 非持久化字段（查询条件 / 详情装配） ==================== */

    /** 标签ID集合：列表按标签<b>交集</b>筛选，编辑时承载待写入的标签 */
    private List<String> tagIds;

    /** 标签明细（详情返回；由服务层按 {@code selectTagsByContractId} 装配） */
    private List<CtmsTag> tags;

    /** 行项明细（详情返回 / 编辑写入用） */
    private List<CtmsContractItem> items;

    /** 字段级变更历史（详情返回） */
    private List<CtmsChangeLog> changeLogs;

    /** 框架合同的子合同清单（框架详情返回） */
    private List<CtmsContract> children;

    /** 框架合同的子合同数量（框架详情） */
    private Integer childrenCount;

    /** 框架合同的子合同金额合计（框架详情；与框架自身 {@code amount} 分开呈现，不要求相等） */
    private BigDecimal childrenAmountSum;

    /** 列表关键字：模糊匹配编号/名称/甲方/乙方 + 行项名称/规格 */
    private String keyword;

    /** 签订日期区间起（{@code yyyy-MM-dd} 字符串） */
    private String beginSignDate;

    /** 签订日期区间止（{@code yyyy-MM-dd} 字符串） */
    private String endSignDate;

    /** 是否包含已停用："1" 才包含；其它值（含 null）都只返回未停用 */
    private String includeDeleted;

    /** 数据范围 SQL 片段：由服务层按白名单拼好后传入，Mapper 用原样拼接进 where（绝不来自请求参数） */
    private String dataScopeSql;

    /**
     * <p> 付款比例%（<b>非持久化字段</b>，任务 5.7）：累计已付 ÷ 合同金额 × 100，4 位 HALF_UP。 </p>
     *
     * <p> 刻意<b>不落库</b>：它是两个已落库列（{@code amount} / {@code paid_amount}）的派生值，
     * 存一列会立刻产生"金额改了、比例忘了改"的不一致，也会让入账历史里出现没人改过的差异。
     * 由服务层在列表与详情返回前按 {@link com.ruoyi.ctms.support.ContractRules#paidRate} 现算。 </p>
     *
     * <p> ⚠ 因此本字段<b>绝不能</b>进入 Mapper XML 的 insert/update 列清单，
     * 也不能进 {@code CtmsContractServiceImpl} 的逐字段比对清单（{@code DIFF_FIELDS}）。 </p>
     *
     * <p> 合同金额为空或 0 时保持 {@code null}（规格：返回空值而非报错，前端展示为空）。 </p>
     */
    private BigDecimal paidRate;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getContractNo()
    {
        return contractNo;
    }

    public void setContractNo(String contractNo)
    {
        this.contractNo = contractNo;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getPartyA()
    {
        return partyA;
    }

    public void setPartyA(String partyA)
    {
        this.partyA = partyA;
    }

    public String getPartyB()
    {
        return partyB;
    }

    public void setPartyB(String partyB)
    {
        this.partyB = partyB;
    }

    public Date getSignDate()
    {
        return signDate;
    }

    public void setSignDate(Date signDate)
    {
        this.signDate = signDate;
    }

    public Date getEffectiveDate()
    {
        return effectiveDate;
    }

    public void setEffectiveDate(Date effectiveDate)
    {
        this.effectiveDate = effectiveDate;
    }

    public String getSubjectMatter()
    {
        return subjectMatter;
    }

    public void setSubjectMatter(String subjectMatter)
    {
        this.subjectMatter = subjectMatter;
    }

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = amount;
    }

    public String getCurrency()
    {
        return currency;
    }

    public void setCurrency(String currency)
    {
        this.currency = currency;
    }

    public String getSubjectCode()
    {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode)
    {
        this.subjectCode = subjectCode;
    }

    public String getCustomerId()
    {
        return customerId;
    }

    public void setCustomerId(String customerId)
    {
        this.customerId = customerId;
    }

    public String getSupplierId()
    {
        return supplierId;
    }

    public void setSupplierId(String supplierId)
    {
        this.supplierId = supplierId;
    }

    public BigDecimal getPaidAmount()
    {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount)
    {
        this.paidAmount = paidAmount;
    }

    public String getHasWarranty()
    {
        return hasWarranty;
    }

    public void setHasWarranty(String hasWarranty)
    {
        this.hasWarranty = hasWarranty;
    }

    public BigDecimal getWarrantyAmount()
    {
        return warrantyAmount;
    }

    public void setWarrantyAmount(BigDecimal warrantyAmount)
    {
        this.warrantyAmount = warrantyAmount;
    }

    public BigDecimal getWarrantyRate()
    {
        return warrantyRate;
    }

    public void setWarrantyRate(BigDecimal warrantyRate)
    {
        this.warrantyRate = warrantyRate;
    }

    public Date getWarrantyStart()
    {
        return warrantyStart;
    }

    public void setWarrantyStart(Date warrantyStart)
    {
        this.warrantyStart = warrantyStart;
    }

    public Integer getWarrantyMonths()
    {
        return warrantyMonths;
    }

    public void setWarrantyMonths(Integer warrantyMonths)
    {
        this.warrantyMonths = warrantyMonths;
    }

    public Date getWarrantyEnd()
    {
        return warrantyEnd;
    }

    public void setWarrantyEnd(Date warrantyEnd)
    {
        this.warrantyEnd = warrantyEnd;
    }

    public String getWarrantyReleased()
    {
        return warrantyReleased;
    }

    public void setWarrantyReleased(String warrantyReleased)
    {
        this.warrantyReleased = warrantyReleased;
    }

    public Date getWarrantyReleaseDate()
    {
        return warrantyReleaseDate;
    }

    public void setWarrantyReleaseDate(Date warrantyReleaseDate)
    {
        this.warrantyReleaseDate = warrantyReleaseDate;
    }

    public String getWarrantyNote()
    {
        return warrantyNote;
    }

    public void setWarrantyNote(String warrantyNote)
    {
        this.warrantyNote = warrantyNote;
    }

    public String getIsFramework()
    {
        return isFramework;
    }

    public void setIsFramework(String isFramework)
    {
        this.isFramework = isFramework;
    }

    public String getParentId()
    {
        return parentId;
    }

    public void setParentId(String parentId)
    {
        this.parentId = parentId;
    }

    public String getArrivalStatus()
    {
        return arrivalStatus;
    }

    public void setArrivalStatus(String arrivalStatus)
    {
        this.arrivalStatus = arrivalStatus;
    }

    public Date getExpectedArrivalDate()
    {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(Date expectedArrivalDate)
    {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getOwnerName()
    {
        return ownerName;
    }

    public void setOwnerName(String ownerName)
    {
        this.ownerName = ownerName;
    }

    public String getDeptId()
    {
        return deptId;
    }

    public void setDeptId(String deptId)
    {
        this.deptId = deptId;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public Date getDeletedAt()
    {
        return deletedAt;
    }

    public void setDeletedAt(Date deletedAt)
    {
        this.deletedAt = deletedAt;
    }

    public String getDeletedReason()
    {
        return deletedReason;
    }

    public void setDeletedReason(String deletedReason)
    {
        this.deletedReason = deletedReason;
    }

    public String getCreateId()
    {
        return createId;
    }

    public void setCreateId(String createId)
    {
        this.createId = createId;
    }

    public String getUpdateId()
    {
        return updateId;
    }

    public void setUpdateId(String updateId)
    {
        this.updateId = updateId;
    }

    public List<String> getTagIds()
    {
        return tagIds;
    }

    public void setTagIds(List<String> tagIds)
    {
        this.tagIds = tagIds;
    }

    public List<CtmsTag> getTags()
    {
        return tags;
    }

    public void setTags(List<CtmsTag> tags)
    {
        this.tags = tags;
    }

    public List<CtmsContractItem> getItems()
    {
        return items;
    }

    public void setItems(List<CtmsContractItem> items)
    {
        this.items = items;
    }

    public List<CtmsChangeLog> getChangeLogs()
    {
        return changeLogs;
    }

    public void setChangeLogs(List<CtmsChangeLog> changeLogs)
    {
        this.changeLogs = changeLogs;
    }

    public List<CtmsContract> getChildren()
    {
        return children;
    }

    public void setChildren(List<CtmsContract> children)
    {
        this.children = children;
    }

    public Integer getChildrenCount()
    {
        return childrenCount;
    }

    public void setChildrenCount(Integer childrenCount)
    {
        this.childrenCount = childrenCount;
    }

    public BigDecimal getChildrenAmountSum()
    {
        return childrenAmountSum;
    }

    public void setChildrenAmountSum(BigDecimal childrenAmountSum)
    {
        this.childrenAmountSum = childrenAmountSum;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public String getBeginSignDate()
    {
        return beginSignDate;
    }

    public void setBeginSignDate(String beginSignDate)
    {
        this.beginSignDate = beginSignDate;
    }

    public String getEndSignDate()
    {
        return endSignDate;
    }

    public void setEndSignDate(String endSignDate)
    {
        this.endSignDate = endSignDate;
    }

    public String getIncludeDeleted()
    {
        return includeDeleted;
    }

    public void setIncludeDeleted(String includeDeleted)
    {
        this.includeDeleted = includeDeleted;
    }

    public String getDataScopeSql()
    {
        return dataScopeSql;
    }

    public void setDataScopeSql(String dataScopeSql)
    {
        this.dataScopeSql = dataScopeSql;
    }

    public BigDecimal getPaidRate()
    {
        return paidRate;
    }

    public void setPaidRate(BigDecimal paidRate)
    {
        this.paidRate = paidRate;
    }

    @Override
    public String toString()
    {
        return "CtmsContract{" +
                "id='" + id + '\'' +
                ", contractNo='" + contractNo + '\'' +
                ", name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", partyA='" + partyA + '\'' +
                ", partyB='" + partyB + '\'' +
                ", signDate=" + signDate +
                ", effectiveDate=" + effectiveDate +
                ", subjectMatter='" + subjectMatter + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", subjectCode='" + subjectCode + '\'' +
                ", customerId='" + customerId + '\'' +
                ", supplierId='" + supplierId + '\'' +
                ", paidAmount=" + paidAmount +
                ", hasWarranty='" + hasWarranty + '\'' +
                ", warrantyAmount=" + warrantyAmount +
                ", warrantyRate=" + warrantyRate +
                ", warrantyStart=" + warrantyStart +
                ", warrantyMonths=" + warrantyMonths +
                ", warrantyEnd=" + warrantyEnd +
                ", warrantyReleased='" + warrantyReleased + '\'' +
                ", warrantyReleaseDate=" + warrantyReleaseDate +
                ", warrantyNote='" + warrantyNote + '\'' +
                ", isFramework='" + isFramework + '\'' +
                ", parentId='" + parentId + '\'' +
                ", arrivalStatus='" + arrivalStatus + '\'' +
                ", expectedArrivalDate=" + expectedArrivalDate +
                ", status='" + status + '\'' +
                ", ownerName='" + ownerName + '\'' +
                ", deptId='" + deptId + '\'' +
                ", delFlag='" + delFlag + '\'' +
                ", deletedAt=" + deletedAt +
                ", deletedReason='" + deletedReason + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", remark='" + getRemark() + '\'' +
                ", tagIds=" + tagIds +
                ", tags=" + tags +
                ", items=" + items +
                ", changeLogs=" + changeLogs +
                ", children=" + children +
                ", childrenCount=" + childrenCount +
                ", childrenAmountSum=" + childrenAmountSum +
                ", keyword='" + keyword + '\'' +
                ", beginSignDate='" + beginSignDate + '\'' +
                ", endSignDate='" + endSignDate + '\'' +
                ", includeDeleted='" + includeDeleted + '\'' +
                ", dataScopeSql='" + (dataScopeSql == null ? "" : "(已设置)") + '\'' +
                ", paidRate=" + paidRate +
                '}';
    }
}
