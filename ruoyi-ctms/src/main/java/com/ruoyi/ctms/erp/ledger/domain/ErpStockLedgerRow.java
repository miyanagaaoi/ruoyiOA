package com.ruoyi.ctms.erp.ledger.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> <b>库存流水行（含下钻条件）</b>（B4 任务 7.3；{@code t_ctms_stock_ledger} 只读投影）。 </p>
 *
 * <p> <b>只增不改</b>：本对象只用于查询与导出 —— 对应的 Mapper（{@code ErpStockLedgerQueryMapper}）
 * 里没有 update/delete 语句，T3 的 {@code ErpStockLedgerMapper} 同样只有 insert。
 * 规格里"尝试修改或删除任一已写入的库存流水 → 系统中不存在这样的入口"在
 * 控制层 / 服务层 / 数据访问层三层都没有入口，这一点由源码级单测
 * （{@code ErpLedgerAppendOnlyTest}）扫描锁定。 </p>
 *
 * <p> 字段口径（与 T3 的写入口径逐条一致，评审勿"顺手统一"）： </p>
 * <ul>
 *   <li> {@code qtyChange} 正入负出；红冲是同值反向； </li>
 *   <li> {@code qtyAfter} 是<b>变动后结存快照</b>（下钻页要展示，也是"结存 = 流水累计"的旁证）； </li>
 *   <li> {@code unitPrice} 是记录性字段（额度统计与展示用，不参与结存计算）；调拨恒 0； </li>
 *   <li> {@code createBy} 是<b>操作人登录名快照</b>（下钻页的"操作人"列），
 *        {@code createId} 是操作人用户ID（数据范围"仅本人"判定用）。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStockLedgerRow extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 结果列 ==================== */

    /** 流水ID。 */
    private String id;

    /** 物料档案ID。 */
    private String productId;

    /** 物料编码。 */
    private String productCode;

    /** 物料名称。 */
    private String productName;

    /** 规格。 */
    private String spec;

    /** 商品类型名称（下钻页展示「商品类型-物料名称」用）。 */
    private String productTypeName;

    /** 仓库ID。 */
    private String warehouseId;

    /** 仓库编码。 */
    private String warehouseCode;

    /** 仓库名称。 */
    private String warehouseName;

    /** 业务类型（红冲带 {@code 红冲-} 前缀）。 */
    private String bizType;

    /** 单据类型（{@code stock_in} / {@code stock_out} / {@code stock_transfer} / {@code stock_take}）。 */
    private String docType;

    /** 单据号。 */
    private String docNo;

    /** 来源单号快照（下推链路的源单）。 */
    private String srcDocNo;

    /** 数量变动（正入负出）。 */
    private BigDecimal qtyChange;

    /** 变动后结存快照。 */
    private BigDecimal qtyAfter;

    /** 单价（记录性；调拨为 0）。 */
    private BigDecimal unitPrice;

    /** 归属部门ID（数据范围判定用）。 */
    private String deptId;

    /** 操作人用户ID（数据范围"仅本人"判定用）。 */
    private String operatorId;

    /** 记账时间（下钻按此列倒序）。 */
    private Date ledgerTime;

    /* ==================== 查询条件（不落库） ==================== */

    /** 按流水ID精确查（详情用）。 */
    private String ledgerId;

    /** 业务类型精确筛选。 */
    private String bizTypeFilter;

    /** 单据类型精确筛选。 */
    private String docTypeFilter;

    /** 单据号 / 来源单号 / 物料关键字模糊筛选。 */
    private String keyword;

    /** 记账时间下界（含）。 */
    private Date beginTime;

    /** 记账时间上界（含）。 */
    private Date endTime;

    /**
     * 数据范围 SQL 片段（服务层用 {@code ErpDocScope.buildDataScopeSql("l")} 拼出）。
     *
     * <p> 流水查询的主表别名就是 {@code l}（{@code t_ctms_stock_ledger}），
     * 片段里的 {@code l.dept_id} / {@code l.create_id} 直接可用。请求参数永不进这个字段。 </p>
     */
    private String dataScopeSql;

    /* ==================== 派生字段 ==================== */

    /**
     * 「商品类型名称-物料名称」展示口径（与结存明细同一处实现）。
     *
     * @return 形如 {@code 五金-螺丝}
     */
    public String getDisplayName()
    {
        return com.ruoyi.ctms.erp.ledger.ErpLedgerRules.displayName(productTypeName, productName);
    }

    /**
     * 是否为红冲流水（业务类型带 {@code 红冲-} 前缀）。
     *
     * @return 红冲返回 true
     */
    public boolean isReversal()
    {
        return bizType != null && bizType.startsWith("红冲-");
    }

    /* ==================== getter / setter ==================== */

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getProductId()
    {
        return productId;
    }

    public void setProductId(String productId)
    {
        this.productId = productId;
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

    public String getProductTypeName()
    {
        return productTypeName;
    }

    public void setProductTypeName(String productTypeName)
    {
        this.productTypeName = productTypeName;
    }

    public String getWarehouseId()
    {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId)
    {
        this.warehouseId = warehouseId;
    }

    public String getWarehouseCode()
    {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode)
    {
        this.warehouseCode = warehouseCode;
    }

    public String getWarehouseName()
    {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName)
    {
        this.warehouseName = warehouseName;
    }

    public String getBizType()
    {
        return bizType;
    }

    public void setBizType(String bizType)
    {
        this.bizType = bizType;
    }

    public String getDocType()
    {
        return docType;
    }

    public void setDocType(String docType)
    {
        this.docType = docType;
    }

    public String getDocNo()
    {
        return docNo;
    }

    public void setDocNo(String docNo)
    {
        this.docNo = docNo;
    }

    public String getSrcDocNo()
    {
        return srcDocNo;
    }

    public void setSrcDocNo(String srcDocNo)
    {
        this.srcDocNo = srcDocNo;
    }

    public BigDecimal getQtyChange()
    {
        return qtyChange;
    }

    public void setQtyChange(BigDecimal qtyChange)
    {
        this.qtyChange = qtyChange;
    }

    public BigDecimal getQtyAfter()
    {
        return qtyAfter;
    }

    public void setQtyAfter(BigDecimal qtyAfter)
    {
        this.qtyAfter = qtyAfter;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public String getDeptId()
    {
        return deptId;
    }

    public void setDeptId(String deptId)
    {
        this.deptId = deptId;
    }

    public String getOperatorId()
    {
        return operatorId;
    }

    public void setOperatorId(String operatorId)
    {
        this.operatorId = operatorId;
    }

    public Date getLedgerTime()
    {
        return ledgerTime;
    }

    public void setLedgerTime(Date ledgerTime)
    {
        this.ledgerTime = ledgerTime;
    }

    public String getLedgerId()
    {
        return ledgerId;
    }

    public void setLedgerId(String ledgerId)
    {
        this.ledgerId = ledgerId;
    }

    public String getBizTypeFilter()
    {
        return bizTypeFilter;
    }

    public void setBizTypeFilter(String bizTypeFilter)
    {
        this.bizTypeFilter = bizTypeFilter;
    }

    public String getDocTypeFilter()
    {
        return docTypeFilter;
    }

    public void setDocTypeFilter(String docTypeFilter)
    {
        this.docTypeFilter = docTypeFilter;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public Date getBeginTime()
    {
        return beginTime;
    }

    public void setBeginTime(Date beginTime)
    {
        this.beginTime = beginTime;
    }

    public Date getEndTime()
    {
        return endTime;
    }

    public void setEndTime(Date endTime)
    {
        this.endTime = endTime;
    }

    public String getDataScopeSql()
    {
        return dataScopeSql;
    }

    public void setDataScopeSql(String dataScopeSql)
    {
        this.dataScopeSql = dataScopeSql;
    }
}
