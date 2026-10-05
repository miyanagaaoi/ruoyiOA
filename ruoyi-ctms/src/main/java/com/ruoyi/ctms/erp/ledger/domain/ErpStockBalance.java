package com.ruoyi.ctms.erp.ledger.domain;

import java.math.BigDecimal;

import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.ctms.erp.ledger.ErpLedgerRules;

/**
 * <p> <b>库存明细行</b>（B4 任务 7.1/7.2；{@code t_ctms_stock} 只读投影 + 展示字段）。 </p>
 *
 * <p> <b>本对象同时是"查询条件"与"结果行"</b>（RuoYi 惯例）：查询侧字段
 * （{@code keyword} / {@code productTypeId} / {@code beginQty} / {@code endQty} /
 * {@code belowSafetyOnly} / {@code hideZero} / {@code dataScopeSql}）不落库，
 * 结果侧字段由 Mapper 联表带出。 </p>
 *
 * <p> <b>两个派生字段刻意不做成列</b>（由 Java 计算，可脱库单测）： </p>
 * <ul>
 *   <li> {@link #getDisplayName()} = 「商品类型名称-物料名称」（{@link ErpLedgerRules#displayName}）； </li>
 *   <li> {@link #isBelowSafetyStock()} = {@code qty < safety_stock}
 *        （{@link ErpLedgerRules#belowSafetyStock}）。 </li>
 * </ul>
 *
 * <p> <b>无写入口</b>：结存只能由过账服务维护（{@code ErpStockJournalServiceImpl}），
 * 本对象只被查询与导出使用。 </p>
 *
 * @author 二开
 */
public class ErpStockBalance extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 结果列（结存 + 联表展示字段） ==================== */

    /** 结存行ID（{@code t_ctms_stock.id}）。 */
    private String id;

    /** 物料档案ID。 */
    private String productId;

    /** 物料编码（{@code t_ctms_product.code}）。 */
    private String productCode;

    /** 物料名称。 */
    private String productName;

    /** 规格。 */
    private String spec;

    /** 计量单位ID。 */
    private String uomId;

    /** 计量单位名称。 */
    private String uomName;

    /**
     * 商品类型ID（结果列）。
     *
     * <p> 它还兼作<b>筛选条件的根节点</b>：请求带 {@code productTypeId} 时，服务层把它展开成
     * 整棵子树塞进 {@link #productTypeIds}（"选父带子"：五金下挂的螺丝也应当被筛出来），
     * Mapper 只按 {@link #productTypeIds} 过滤。 </p>
     *
     * <p> ⚠ 两者刻意<b>共用一个字段名</b>（同一列既是筛选入参又是结果列，RuoYi 惯例）：
     * 不要再另开一个"筛选根节点"字段 —— 那会与本行重复声明，直接挡住全模块编译
     * （2026-10-05 实际发生过一次）。 </p>
     */
    private String productTypeId;

    /** 商品类型名称。 */
    private String productTypeName;

    /** 安全库存（低于它要在界面上预警，并支持"低于安全库存"筛选）。 */
    private BigDecimal safetyStock;

    /** 仓库ID。 */
    private String warehouseId;

    /** 仓库编码。 */
    private String warehouseCode;

    /** 仓库名称。 */
    private String warehouseName;

    /** 结存数量（唯一写路径是过账服务）。 */
    private BigDecimal qty;

    /** 货品总额度（口径见 {@link ErpLedgerRules} 的类注释；由 SQL 聚合带出）。 */
    private BigDecimal goodsQuota;

    /* ==================== 查询条件（不落库） ==================== */

    /** 物料关键字（编码或名称模糊）。 */
    private String keyword;

    /** 商品类型ID集合（服务层展开后的结果；Mapper 用它做 {@code in} 过滤）。 */
    private java.util.List<String> productTypeIds;

    /** 数量区间下界（含）。 */
    private BigDecimal beginQty;

    /** 数量区间上界（含）。 */
    private BigDecimal endQty;

    /** 只看低于安全库存的行（{@code qty < safety_stock}）。 */
    private Boolean belowSafetyOnly;

    /** 只看非零结存（默认 false = 零结存也列出）。 */
    private Boolean hideZero;

    /**
     * 数据范围 SQL 片段（服务层用 {@code ErpDocScope.buildDataScopeSql("l")} 拼出）。
     *
     * <p> ⚠ 别名必须是 {@code l}：片段被注入到"按流水判定可见性"的子查询里
     * （结存表没有 {@code dept_id}/{@code create_id} 列），见
     * {@link ErpLedgerRules#ALIAS_LEDGER} 的注释。请求参数<b>永远不</b>进这个字段。 </p>
     */
    private String dataScopeSql;

    /* ==================== 派生字段 ==================== */

    /**
     * 「商品类型名称-物料名称」（AC-73 的展示口径）。
     *
     * @return 形如 {@code 五金-螺丝}
     */
    public String getDisplayName()
    {
        return ErpLedgerRules.displayName(productTypeName, productName);
    }

    /**
     * 是否低于安全库存。
     *
     * @return 低于返回 true
     */
    public boolean isBelowSafetyStock()
    {
        return ErpLedgerRules.belowSafetyStock(qty, safetyStock);
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

    public String getUomId()
    {
        return uomId;
    }

    public void setUomId(String uomId)
    {
        this.uomId = uomId;
    }

    public String getUomName()
    {
        return uomName;
    }

    public void setUomName(String uomName)
    {
        this.uomName = uomName;
    }

    public String getProductTypeId()
    {
        return productTypeId;
    }

    public void setProductTypeId(String productTypeId)
    {
        this.productTypeId = productTypeId;
    }

    public String getProductTypeName()
    {
        return productTypeName;
    }

    public void setProductTypeName(String productTypeName)
    {
        this.productTypeName = productTypeName;
    }

    public BigDecimal getSafetyStock()
    {
        return safetyStock;
    }

    public void setSafetyStock(BigDecimal safetyStock)
    {
        this.safetyStock = safetyStock;
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

    public BigDecimal getQty()
    {
        return qty;
    }

    public void setQty(BigDecimal qty)
    {
        this.qty = qty;
    }

    public BigDecimal getGoodsQuota()
    {
        return goodsQuota;
    }

    public void setGoodsQuota(BigDecimal goodsQuota)
    {
        this.goodsQuota = goodsQuota;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public java.util.List<String> getProductTypeIds()
    {
        return productTypeIds;
    }

    public void setProductTypeIds(java.util.List<String> productTypeIds)
    {
        this.productTypeIds = productTypeIds;
    }

    public BigDecimal getBeginQty()
    {
        return beginQty;
    }

    public void setBeginQty(BigDecimal beginQty)
    {
        this.beginQty = beginQty;
    }

    public BigDecimal getEndQty()
    {
        return endQty;
    }

    public void setEndQty(BigDecimal endQty)
    {
        this.endQty = endQty;
    }

    public Boolean getBelowSafetyOnly()
    {
        return belowSafetyOnly;
    }

    public void setBelowSafetyOnly(Boolean belowSafetyOnly)
    {
        this.belowSafetyOnly = belowSafetyOnly;
    }

    public Boolean getHideZero()
    {
        return hideZero;
    }

    public void setHideZero(Boolean hideZero)
    {
        this.hideZero = hideZero;
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
