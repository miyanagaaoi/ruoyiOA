package com.ruoyi.ctms.erp.stockops.domain;

import java.util.Date;
import java.util.List;

import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>盘点单表头</b>（表 {@code t_ctms_stocktake}，2.0 B4 任务 6.3/6.4/6.5；
 * DDL 见 {@code sql/二开-进销存.sql} 第 417~466 行）。 </p>
 *
 * <p> <b>盘点单自己不写结存</b>：审核时按行项差异生成盘盈入库单 / 盘亏出库单并过账，
 * 因此本单据的 {@code posted} 语义是"其生成单已过账"（DDL 列注释）。 </p>
 *
 * <p> <b>五条口径</b>： </p>
 * <ol>
 *   <li> {@code takeType} = {@code full}（全盘，取该仓库结存非零物料）/
 *        {@code partial}（抽盘，按指定物料或按商品类型<b>含整棵子树</b>）； </li>
 *   <li> 账面数量由服务端在<b>生成行项时</b>固化，客户端传来的账面一律被忽略（界面只读）； </li>
 *   <li> 审核：正差异行合并成一张盘盈入库单、负差异行合并成一张盘亏出库单，
 *        生成单状态直接"已审核"、金额 0、数量为差异绝对值，并回填生成单号；<b>无差异不生成</b>； </li>
 *   <li> 盘亏单过账<b>显式豁免负库存校验</b>（{@code ErpPostingOptions.skipNegativeCheck()}），
 *        不引入任何全局开关； </li>
 *   <li> 反审核级联：先红冲并作废其生成单，再让盘点单回到"待审核"（Q-B10）。 </li>
 * </ol>
 *
 * <p> {@code items} 装配详情/编辑；{@code productTypeIds} 是<b>抽盘生成参数</b>（非持久化列）；
 * {@code dataScopeSql} 由服务层拼出（服务端白名单）。 </p>
 *
 * @author 二开
 */
public class ErpStocktake extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（盘点单特有列） ==================== */

    /** 盘点仓库ID（必填；DB 级 NOT NULL + 外键）。 */
    private String warehouseId;

    /** 仓库名快照。 */
    private String warehouseName;

    /** 盘点范围：{@code full} 全盘 / {@code partial} 抽盘。 */
    private String takeType;

    /** 抽盘范围说明（可空）。 */
    private String scopeNote;

    /** 生成的盘盈入库单ID（可空、无外键）。 */
    private String generatedInId;

    /** 生成的盘盈入库单号（与 id 成对写入）。 */
    private String generatedInNo;

    /** 生成的盘亏出库单ID（可空、无外键）。 */
    private String generatedOutId;

    /** 生成的盘亏出库单号（与 id 成对写入）。 */
    private String generatedOutNo;

    /* ==================== 非持久化字段（装配、生成参数与查询条件） ==================== */

    /** 行项集合（详情/编辑/生成装配）。 */
    private List<ErpStocktakeItem> items;

    /** 抽盘生成参数：商品类型ID集合（含子树；服务端展开，非持久化列）。 */
    private List<String> productTypeIds;

    /** 数据范围片段（服务端拼出）。 */
    private String dataScopeSql;

    /** 查询条件：单据日期起（含）。 */
    private Date beginDocDate;

    /** 查询条件：单据日期止（含）。 */
    private Date endDocDate;

    /** 查询条件：单号/备注/范围说明关键字。 */
    private String keyword;

    /** 查询条件：是否包含已作废（{@code Boolean.TRUE} 才包含；默认排除）。 */
    private Boolean includeVoided;

    /** 是否有生成单（盘盈或盘亏任一）。 */
    public boolean hasGeneratedDoc()
    {
        return generatedInId != null || generatedOutId != null;
    }

    public String getWarehouseId()
    {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId)
    {
        this.warehouseId = warehouseId;
    }

    public String getWarehouseName()
    {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName)
    {
        this.warehouseName = warehouseName;
    }

    public String getTakeType()
    {
        return takeType;
    }

    public void setTakeType(String takeType)
    {
        this.takeType = takeType;
    }

    public String getScopeNote()
    {
        return scopeNote;
    }

    public void setScopeNote(String scopeNote)
    {
        this.scopeNote = scopeNote;
    }

    public String getGeneratedInId()
    {
        return generatedInId;
    }

    public void setGeneratedInId(String generatedInId)
    {
        this.generatedInId = generatedInId;
    }

    public String getGeneratedInNo()
    {
        return generatedInNo;
    }

    public void setGeneratedInNo(String generatedInNo)
    {
        this.generatedInNo = generatedInNo;
    }

    public String getGeneratedOutId()
    {
        return generatedOutId;
    }

    public void setGeneratedOutId(String generatedOutId)
    {
        this.generatedOutId = generatedOutId;
    }

    public String getGeneratedOutNo()
    {
        return generatedOutNo;
    }

    public void setGeneratedOutNo(String generatedOutNo)
    {
        this.generatedOutNo = generatedOutNo;
    }

    public List<ErpStocktakeItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpStocktakeItem> items)
    {
        this.items = items;
    }

    public List<String> getProductTypeIds()
    {
        return productTypeIds;
    }

    public void setProductTypeIds(List<String> productTypeIds)
    {
        this.productTypeIds = productTypeIds;
    }

    public String getDataScopeSql()
    {
        return dataScopeSql;
    }

    public void setDataScopeSql(String dataScopeSql)
    {
        this.dataScopeSql = dataScopeSql;
    }

    public Date getBeginDocDate()
    {
        return beginDocDate;
    }

    public void setBeginDocDate(Date beginDocDate)
    {
        this.beginDocDate = beginDocDate;
    }

    public Date getEndDocDate()
    {
        return endDocDate;
    }

    public void setEndDocDate(Date endDocDate)
    {
        this.endDocDate = endDocDate;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public Boolean getIncludeVoided()
    {
        return includeVoided;
    }

    public void setIncludeVoided(Boolean includeVoided)
    {
        this.includeVoided = includeVoided;
    }

    @Override
    public String toString()
    {
        return "ErpStocktake{id='" + getId() + "', docNo='" + getDocNo() + "', status='" + getStatus()
                + "', warehouseId='" + warehouseId + "', takeType='" + takeType + "', generatedInNo='"
                + generatedInNo + "', generatedOutNo='" + generatedOutNo + "', posted='" + getPosted() + "'}";
    }
}
