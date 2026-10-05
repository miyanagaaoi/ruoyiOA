package com.ruoyi.ctms.erp.procurement.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;

/**
 * <p> 采购申请单对象，对应表 {@code t_ctms_purchase_request}（DDL 见
 * {@code sql/二开-进销存.sql} 1/18）。 </p>
 *
 * <p> 公共列（单号/日期/状态/归属部门/痕迹列/来源三列/合同引用/过账与软删除标志）继承
 * {@link ErpDocHeader}，本类只声明<b>采购申请单特有四列</b> + 查询条件 + 列表派生字段。 </p>
 *
 * <p> <b>本表刻意没有 {@code total_amount} 列</b>（与采购单不对称，见移植清单 §2 的字段清单）：
 * 列表金额由行项现算 —— 因此 {@link #totalAmount} 是<b>非持久化</b>字段，
 * <b>绝不能</b>出现在 Mapper XML 的 insert / update 列清单里。 </p>
 *
 * <p> <b>派生字段与查询条件同样非持久化</b>：{@code items} / {@code changeLogs} /
 * {@code remainQtySum} / {@code canPush} / {@code keyword} / 日期区间 / {@code includeVoided} /
 * {@code dataScopeSql} / {@code srcItemIds}。 </p>
 *
 * <p> ⚠ 金额与数量一律 {@code BigDecimal}（{@code REQ-NFR-007}：模块内不得出现二进制浮点）。 </p>
 *
 * @author 二开
 */
public class ErpPurchaseRequest extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（本表特有列） ==================== */

    /** 需求部门ID（可空；仅反查名称，无外键）。 */
    private String requestDeptId;

    /** 需求日期（可空；下推时带成采购单的预计到货日期）。 */
    private Date needDate;

    /** 建议供应商ID（可空、无外键；仅提示用，不参与校验）。 */
    private String suggestSupplierId;

    /** 用途说明（可空）。 */
    private String purpose;

    /* ==================== 非持久化：查询条件 ==================== */

    /** 列表关键字：模糊匹配单号 / 用途 / 备注。 */
    private String keyword;

    /** 单据日期区间起（{@code yyyy-MM-dd}）。 */
    private String beginDocDate;

    /** 单据日期区间止（{@code yyyy-MM-dd}）。 */
    private String endDocDate;

    /**
     * 是否包含已作废："1" 才包含；其它值（含 null）默认排除。
     *
     * <p> 与 {@code status='voided'} 的关系：显式筛 {@code voided} 时即使不传本字段也应能查到
     * （AC-V2-17 的口径），判定写在 {@link #includeVoidedForQuery()}。 </p>
     */
    private String includeVoided;

    /** 数据范围 SQL 片段（服务端白名单拼出，Mapper 原样拼接；绝不来自请求参数）。 */
    private String dataScopeSql;

    /* ==================== 非持久化：装配与派生 ==================== */

    /** 行项集合（详情返回 / 新增编辑写入用）。 */
    private List<ErpPurchaseRequestItem> items;

    /** 变更历史（详情返回）。 */
    private List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs;

    /** 下推用：本次要下推的来源行项ID集合（空表示按行序/全部剩余量全推）。 */
    private List<String> srcItemIds;

    /** 列表派生：剩余可下推量合计（不落库，列表返回前由服务层现算）。 */
    private BigDecimal remainQtySum;

    /** 列表派生：是否还有可下推量（前端下推按钮显隐）。 */
    private Boolean canPush;

    /** 列表派生：单据金额合计（本表无该列，由行项先舍入再汇总现算）。 */
    private BigDecimal totalAmount;

    /**
     * 列表派生：归属部门名称（由 Mapper 关联 {@code sys_dept} 带出；<b>非持久化</b>）。
     *
     * <p> ⚠ 刻意不落库：部门改名后列表要显示新名，快照只用于数据范围判定（{@code dept_id}）。 </p>
     */
    private String deptName;

    /**
     * 查询侧"包含已作废"的最终判定（供 Mapper 的 {@code <if>} 与单测共用一套口径）。
     *
     * <p> 规则：{@code includeVoided = "1"} 或 {@code status = "voided"} 时包含。 </p>
     *
     * @return 包含已作废返回 true
     */
    public boolean includeVoidedForQuery()
    {
        return ErpPurRules.FLAG_YES.equals(includeVoided)
                || ErpDocStatus.VOIDED.equals(ErpPurRules.trimToNull(status));
    }

    /**
     * 是否已作废（列表操作列显隐用）。
     *
     * @return 已作废返回 true
     */
    public boolean isVoided()
    {
        return ErpDocStatus.VOIDED.equals(ErpPurRules.trimToNull(status));
    }

    /**
     * 是否可下推（列表派生字段 {@code canPush} 的计算口径）：剩余量 &gt; 0。
     *
     * <p> 状态门槛（已审核）由前端按状态标签决定、由下推接口再校验一次；
     * 这里只表达"还有没有量可推"，因为"归零即完成"后状态是 {@code completed} 而剩余量同样为 0。 </p>
     *
     * @return 剩余量大于 0 返回 true
     */
    public boolean computeCanPush()
    {
        return remainQtySum != null && remainQtySum.signum() > 0;
    }

    /**
     * 保证 {@link #items} 非 null（服务层与 Mapper 的 {@code <foreach>} 都直接遍历它）。
     *
     * @return 行项集合（非 null）
     */
    public List<ErpPurchaseRequestItem> itemsOrEmpty()
    {
        if (items == null)
        {
            items = new ArrayList<>();
        }
        return items;
    }

    public String getRequestDeptId()
    {
        return requestDeptId;
    }

    public void setRequestDeptId(String requestDeptId)
    {
        this.requestDeptId = requestDeptId;
    }

    public Date getNeedDate()
    {
        return needDate;
    }

    public void setNeedDate(Date needDate)
    {
        this.needDate = needDate;
    }

    public String getSuggestSupplierId()
    {
        return suggestSupplierId;
    }

    public void setSuggestSupplierId(String suggestSupplierId)
    {
        this.suggestSupplierId = suggestSupplierId;
    }

    public String getPurpose()
    {
        return purpose;
    }

    public void setPurpose(String purpose)
    {
        this.purpose = purpose;
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

    public List<ErpPurchaseRequestItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpPurchaseRequestItem> items)
    {
        this.items = items;
    }

    public List<com.ruoyi.ctms.domain.CtmsChangeLog> getChangeLogs()
    {
        return changeLogs;
    }

    public void setChangeLogs(List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs)
    {
        this.changeLogs = changeLogs;
    }

    public List<String> getSrcItemIds()
    {
        return srcItemIds;
    }

    public void setSrcItemIds(List<String> srcItemIds)
    {
        this.srcItemIds = srcItemIds;
    }

    public BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(BigDecimal remainQtySum)
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

    public BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }

    public String getDeptName()
    {
        return deptName;
    }

    public void setDeptName(String deptName)
    {
        this.deptName = deptName;
    }
}
