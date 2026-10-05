package com.ruoyi.ctms.erp.procurement.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;

/**
 * <p> 采购单对象，对应表 {@code t_ctms_purchase_order}（DDL 2/18）。 </p>
 *
 * <p> 公共列继承 {@link ErpDocHeader}；本类声明<b>采购单特有八列</b>
 * （供应商引用与快照、采购部门、预计到货、结算方式、币种、金额合计、默认收货仓库）。 </p>
 *
 * <p> <b>与采购申请单的不对称是刻意的</b>（移植清单 §2 的字段清单）：采购单<b>有</b>
 * {@code total_amount}、<b>有</b> DB 级非空的 {@code supplier_id}；申请单两者都没有。
 * 因此 {@link #totalAmount} 在本类是<b>持久化</b>字段（"先舍入再汇总"的结果落库，
 * 供列表/打印/导出四处取同一份值，避免各处现算产生 1 分钱差异）。 </p>
 *
 * @author 二开
 */
public class ErpPurchaseOrder extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（本表特有列） ==================== */

    /** 供应商档案ID（DB 级 NOT NULL + 外键；禁止引用客户档案）。 */
    private String supplierId;

    /** 供应商名称快照（改名/停用后历史单据仍显示旧名）。 */
    private String supplierName;

    /** 采购部门ID（可空；下推时来自申请单的需求部门，仅反查名称）。 */
    private String purchaseDeptId;

    /** 预计到货日期（可空；下推时来自申请单的需求日期）。 */
    private Date expectedArrivalDate;

    /** 结算方式（可空）。 */
    private String settleType;

    /** 币种（仅记录不换算，默认 CNY）。 */
    private String currency;

    /** 单据金额合计 = 各行<b>先 HALF_UP 到 2 位</b>后之和（{@code ErpAmounts} 是唯一实现点）。 */
    private BigDecimal totalAmount;

    /** 默认收货仓库ID（可空、无外键）。 */
    private String receiptWarehouseId;

    /* ==================== 非持久化：查询条件 ==================== */

    /** 列表关键字：模糊匹配单号 / 供应商名称 / 备注。 */
    private String keyword;

    /** 单据日期区间起（{@code yyyy-MM-dd}）。 */
    private String beginDocDate;

    /** 单据日期区间止（{@code yyyy-MM-dd}）。 */
    private String endDocDate;

    /** 是否包含已作废："1" 才包含（与 {@code status='voided'} 二选一即可）。 */
    private String includeVoided;

    /** 数据范围 SQL 片段（服务端拼出；绝不来自请求参数）。 */
    private String dataScopeSql;

    /* ==================== 非持久化：装配与派生 ==================== */

    /** 行项集合（详情返回 / 新增编辑写入用）。 */
    private List<ErpPurchaseOrderItem> items;

    /** 变更历史（详情返回）。 */
    private List<com.ruoyi.ctms.domain.CtmsChangeLog> changeLogs;

    /** 默认收货仓库名称快照（详情展示用，由服务层反查后填充；不落库）。 */
    private String receiptWarehouseName;

    /** 列表派生：剩余可入库量合计（任务 3.5 的列表提示；本任务只算不推）。 */
    private BigDecimal remainQtySum;

    /** 列表派生：是否还有可入库量。 */
    private Boolean canReceive;

    /**
     * 列表派生：归属部门名称（由 Mapper 关联 {@code sys_dept} 带出；<b>非持久化</b>）。
     */
    private String deptName;

    /**
     * 查询侧"包含已作废"的最终判定。
     *
     * @return 包含已作废返回 true
     */
    public boolean includeVoidedForQuery()
    {
        return ErpPurRules.FLAG_YES.equals(includeVoided)
                || ErpDocStatus.VOIDED.equals(ErpPurRules.trimToNull(status));
    }

    /**
     * 是否已作废。
     *
     * @return 已作废返回 true
     */
    public boolean isVoided()
    {
        return ErpDocStatus.VOIDED.equals(ErpPurRules.trimToNull(status));
    }

    /**
     * 是否可下推入库单（任务 3.5 的口径：已审核或已完成，且还有剩余可入库量）。
     *
     * @return 可推返回 true
     */
    public boolean computeCanReceive()
    {
        boolean approved = ErpDocStatus.APPROVED.equals(ErpPurRules.trimToNull(status))
                || ErpDocStatus.COMPLETED.equals(ErpPurRules.trimToNull(status));
        return approved && remainQtySum != null && remainQtySum.signum() > 0;
    }

    /**
     * 保证 {@link #items} 非 null。
     *
     * @return 行项集合
     */
    public List<ErpPurchaseOrderItem> itemsOrEmpty()
    {
        if (items == null)
        {
            items = new ArrayList<>();
        }
        return items;
    }

    public String getSupplierId()
    {
        return supplierId;
    }

    public void setSupplierId(String supplierId)
    {
        this.supplierId = supplierId;
    }

    public String getSupplierName()
    {
        return supplierName;
    }

    public void setSupplierName(String supplierName)
    {
        this.supplierName = supplierName;
    }

    public String getPurchaseDeptId()
    {
        return purchaseDeptId;
    }

    public void setPurchaseDeptId(String purchaseDeptId)
    {
        this.purchaseDeptId = purchaseDeptId;
    }

    public Date getExpectedArrivalDate()
    {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(Date expectedArrivalDate)
    {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getSettleType()
    {
        return settleType;
    }

    public void setSettleType(String settleType)
    {
        this.settleType = settleType;
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

    public String getReceiptWarehouseId()
    {
        return receiptWarehouseId;
    }

    public void setReceiptWarehouseId(String receiptWarehouseId)
    {
        this.receiptWarehouseId = receiptWarehouseId;
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

    public List<ErpPurchaseOrderItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpPurchaseOrderItem> items)
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

    public String getReceiptWarehouseName()
    {
        return receiptWarehouseName;
    }

    public void setReceiptWarehouseName(String receiptWarehouseName)
    {
        this.receiptWarehouseName = receiptWarehouseName;
    }

    public BigDecimal getRemainQtySum()
    {
        return remainQtySum;
    }

    public void setRemainQtySum(BigDecimal remainQtySum)
    {
        this.remainQtySum = remainQtySum;
    }

    public Boolean getCanReceive()
    {
        return canReceive;
    }

    public void setCanReceive(Boolean canReceive)
    {
        this.canReceive = canReceive;
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
