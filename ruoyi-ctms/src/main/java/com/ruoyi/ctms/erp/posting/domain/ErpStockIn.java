package com.ruoyi.ctms.erp.posting.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>入库单表头</b>（表 {@code t_ctms_stock_in}，2.0 B4 任务 5.1；DDL 见
 * {@code sql/二开-进销存.sql} 第 313~362 行）。 </p>
 *
 * <p> 公共列（单号/单据日期/状态/归属部门/创建人快照/经办人/关联合同/来源三列/痕迹列/过账标记/软删除）
 * 全部来自 {@link ErpDocHeader}；本类只声明入库单特有的 4 组列与查询条件。 </p>
 *
 * <p> <b>三条口径</b>（评审时对照 AC-73）： </p>
 * <ol>
 *   <li> {@code posted} 是<b>过账幂等依据</b>：已过账再审核直接短路，不产生流水、不改结存； </li>
 *   <li> {@code warehouse_id} 是<b>表头仓库</b>，行项未填仓库时回落它；
 *        两者都空时审核阶段被拒并指明行号（{@code ErpMasterGuards.resolveWarehouseId}）； </li>
 *   <li> {@code total_amount} = 各行先四舍五入到 2 位后的和（{@code ErpAmounts.totalOf}）。 </li>
 * </ol>
 *
 * <p> {@code items} 是详情/编辑的装配字段（非持久化列）：新增与修改时承载待写入的行项，
 * 详情时承载已落库的行项。 </p>
 *
 * <p> {@code dataScopeSql} 与 {@code beginDocDate}/{@code endDocDate}/{@code keyword} 是<b>查询条件</b>：
 * 前者由服务层用 {@code ErpDocScope.buildDataScopeSql()} 拼出（服务端白名单，绝不接受请求参数），
 * 后者由列表页的筛选条件带入。 </p>
 *
 * @author 二开
 */
public class ErpStockIn extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（入库单特有列） ==================== */

    /** 表头仓库ID（行项未填仓库时回落本列）。 */
    private String warehouseId;

    /** 仓库名快照。 */
    private String warehouseName;

    /** 入库类型（{@code 采购入库} / {@code 退货入库} / {@code 其他入库}；{@code 盘盈入库} 由盘点审核生成）。 */
    private String inType;

    /** 供应商ID（可空，无外键）。 */
    private String supplierId;

    /** 供应商名称快照（可空）。 */
    private String supplierName;

    /** 单据金额合计 = 各行先舍入到 2 位后之和。 */
    private BigDecimal totalAmount;

    /* ==================== 非持久化字段（装配与查询条件） ==================== */

    /** 行项集合（详情/编辑装配）。 */
    private List<ErpStockInItem> items;

    /** 数据范围片段（服务端拼出的白名单，Mapper 用 {@code ${}} 原样拼接）。 */
    private String dataScopeSql;

    /** 查询条件：单据日期起（含）。 */
    private Date beginDocDate;

    /** 查询条件：单据日期止（含）。 */
    private Date endDocDate;

    /** 查询条件：单号/备注关键字。 */
    private String keyword;

    /** 查询条件：是否包含已作废（{@code Boolean.TRUE} 才包含；默认排除）。
     *  <p> 用 {@code Boolean} 而不是字符串："1" 这类字符串在 OGNL 里与字符字面量比较会踩
     *  B3 记录过的坑（{@code ctms} 的 Mapper 注释第 1 条），布尔值没有这个歧义。 </p> */
    private Boolean includeVoided;

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

    public String getInType()
    {
        return inType;
    }

    public void setInType(String inType)
    {
        this.inType = inType;
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

    public BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }

    public List<ErpStockInItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpStockInItem> items)
    {
        this.items = items;
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
        return "ErpStockIn{id='" + getId() + "', docNo='" + getDocNo() + "', status='" + getStatus()
                + "', warehouseId='" + warehouseId + "', inType='" + inType + "', posted='" + getPosted()
                + "', totalAmount=" + totalAmount + '}';
    }
}
