package com.ruoyi.ctms.erp.base.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import com.ruoyi.ctms.erp.base.ErpAmounts;

/**
 * <p> <b>单据行项公共字段</b>（2.0 B4 任务 1.4；design D1）。 </p>
 *
 * <p> 字段级来源 = 参考仓库 `models_doc.py:100-118` 的 {@code DocItemMixin}
 * （外加各表内的 {@code doc_id}）。8 类单据的行项实体继承本类，只声明自己的特有字段
 * （如采购申请的 {@code orderedQty}、盘点的 {@code bookQty}）。 </p>
 *
 * <p> <b>快照列是"历史不可变"的实现方式</b>（AC-72）：{@code productCode}/{@code productName}/
 * {@code spec}/{@code uomName}/{@code uomDecimals} 在行项写入时从物料档案固化，
 * 之后物料改名/停用/换单位都不影响历史单据的展示。 </p>
 *
 * <p> <b>金额不在这里算</b>：{@link #recalcAmount()} 委托
 * {@link ErpAmounts#lineAmount(BigDecimal, BigDecimal)} —— 全模块只有这一个乘法与舍入实现点。 </p>
 *
 * <p> <b>字段可见性口径</b>：22 个公共字段是 {@code protected}（子类可直接读写），
 * 同时提供完整 getter/setter 供 MyBatis 与外部调用。
 * ⚠ 子类<b>不得</b>重复声明同名字段（会影子化基类字段），只声明专有字段
 * （如 {@code orderedQty} / {@code receivedQty} / {@code bookQty}）。 </p>
 *
 * @author 二开
 */
public abstract class ErpDocItem implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID）。 */
    protected String id;

    /** 表头ID（外键，级联删除）。 */
    protected String docId;

    /** 序号（按提交顺序从 1 连续重排）。 */
    protected Integer seq;

    /** 物料档案ID（DB 级 NOT NULL + 外键）。 */
    protected String productId;

    /** 物料编码快照。 */
    protected String productCode;

    /** 物料名称快照。 */
    protected String productName;

    /** 规格快照（可空）。 */
    protected String spec;

    /** 单位名快照（可空）。 */
    protected String uomName;

    /** 单位小数位快照（0~4；数量录入精度的校验依据）。 */
    protected Integer uomDecimals;

    /** 数量（定点 3 位）。 */
    protected BigDecimal qty;

    /** 单价（定点 4 位）。 */
    protected BigDecimal unitPrice;

    /** 行金额（先四舍五入到 2 位；由 {@link #recalcAmount()} 写入）。 */
    protected BigDecimal amount;

    /** 行级仓库ID（可空：未填时回落表头仓库；调拨单不使用）。 */
    protected String warehouseId;

    /** 行级仓库名快照（可空）。 */
    protected String warehouseName;

    /** 下推来源行项ID（可空、无外键）。 */
    protected String srcItemId;

    /** 行备注（可空）。 */
    protected String remark;

    /** 创建时间。 */
    protected Date createTime;

    /** 更新时间。 */
    protected Date updateTime;

    /** 创建人用户ID（可空：行项随表头写入）。 */
    protected String createId;

    /** 创建人登录名快照（可空）。 */
    protected String createBy;

    /** 更新人用户ID（可空）。 */
    protected String updateId;

    /** 更新人登录名快照（可空）。 */
    protected String updateBy;

    /**
     * 按"数量 × 单价 先舍入到 2 位"重算并写入 {@link #amount}。
     *
     * @return 写入的 2 位金额
     */
    public BigDecimal recalcAmount()
    {
        this.amount = ErpAmounts.lineAmount(qty, unitPrice);
        return this.amount;
    }

    /**
     * 金额（未显式写入时按 qty × unitPrice 现算，保证列表与表单同源）。
     *
     * @return 2 位金额
     */
    public BigDecimal amountOrCompute()
    {
        return amount == null ? ErpAmounts.lineAmount(qty, unitPrice) : amount;
    }

    /**
     * 把行项上的物料/单位快照批量写入（由公共层的物料守卫在校验通过后调用）。
     *
     * @param productId   物料ID
     * @param productCode 物料编码快照
     * @param productName 物料名称快照
     * @param spec        规格快照
     * @param uomName     单位名快照
     * @param uomDecimals 单位小数位快照
     */
    public void applyProductSnapshot(String productId, String productCode, String productName,
                                     String spec, String uomName, Integer uomDecimals)
    {
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.spec = spec;
        this.uomName = uomName;
        this.uomDecimals = uomDecimals;
    }

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getDocId()
    {
        return docId;
    }

    public void setDocId(String docId)
    {
        this.docId = docId;
    }

    public Integer getSeq()
    {
        return seq;
    }

    public void setSeq(Integer seq)
    {
        this.seq = seq;
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

    public String getUomName()
    {
        return uomName;
    }

    public void setUomName(String uomName)
    {
        this.uomName = uomName;
    }

    public Integer getUomDecimals()
    {
        return uomDecimals;
    }

    public void setUomDecimals(Integer uomDecimals)
    {
        this.uomDecimals = uomDecimals;
    }

    public BigDecimal getQty()
    {
        return qty;
    }

    public void setQty(BigDecimal qty)
    {
        this.qty = qty;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = amount;
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

    public String getSrcItemId()
    {
        return srcItemId;
    }

    public void setSrcItemId(String srcItemId)
    {
        this.srcItemId = srcItemId;
    }

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
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
