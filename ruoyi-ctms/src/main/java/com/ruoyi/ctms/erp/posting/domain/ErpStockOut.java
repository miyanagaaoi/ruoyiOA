package com.ruoyi.ctms.erp.posting.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>出库单表头</b>（表 {@code t_ctms_stock_out}，2.0 B4 任务 5.1；DDL 见
 * {@code sql/二开-进销存.sql} 第 365~414 行）。 </p>
 *
 * <p> 与 {@link ErpStockIn} 严格对称：公共列来自 {@link ErpDocHeader}，
 * 特有列是"表头仓库 + 出库类型 + 客户 + 金额合计"。与入库单的两处差异都<b>只在数据</b>上： </p>
 * <ul>
 *   <li> 过账方向是 {@code -1}（出库扣减结存）； </li>
 *   <li> 受"是否允许负库存"参数约束（默认关闭时会使结存小于 0 的出库审核被整体回滚）。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStockOut extends ErpDocHeader
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（出库单特有列） ==================== */

    /** 表头仓库ID（行项未填仓库时回落本列）。 */
    private String warehouseId;

    /** 仓库名快照。 */
    private String warehouseName;

    /** 出库类型（{@code 销售出库} / {@code 领用出库} / {@code 其他出库}；{@code 盘亏出库} 由盘点审核生成）。 */
    private String outType;

    /** 客户ID（可空，无外键）。 */
    private String customerId;

    /** 客户名称快照（可空）。 */
    private String customerName;

    /** 单据金额合计 = 各行先舍入到 2 位后之和。 */
    private BigDecimal totalAmount;

    /* ==================== 非持久化字段（装配与查询条件） ==================== */

    /** 行项集合（详情/编辑装配）。 */
    private List<ErpStockOutItem> items;

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

    public String getOutType()
    {
        return outType;
    }

    public void setOutType(String outType)
    {
        this.outType = outType;
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

    public BigDecimal getTotalAmount()
    {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount)
    {
        this.totalAmount = totalAmount;
    }

    public List<ErpStockOutItem> getItems()
    {
        return items;
    }

    public void setItems(List<ErpStockOutItem> items)
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
        return "ErpStockOut{id='" + getId() + "', docNo='" + getDocNo() + "', status='" + getStatus()
                + "', warehouseId='" + warehouseId + "', outType='" + outType + "', posted='" + getPosted()
                + "', totalAmount=" + totalAmount + '}';
    }
}
