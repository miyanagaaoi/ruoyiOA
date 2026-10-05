package com.ruoyi.ctms.domain;

import java.math.BigDecimal;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 物料档案 {@code t_ctms_product}（2.0 B3 §3.3 物料域主数据）。 </p>
 *
 * <p> 金额/数量一律 {@link BigDecimal}，禁止 {@code double}/{@code float}：
 * {@code defaultPrice} 对应 DDL {@code decimal(14,4)}，{@code safetyStock} 对应
 * {@code decimal(14,3)}。{@code safetyStock} 可空，「未设置」与「0」必须可区分。 </p>
 *
 * <p> {@code code} 全局唯一：留空时由服务端按「类型编码（或 PT+类型id）+ 4 位序号」生成；
 * 编辑路径不接受改码（见服务实现注释）。 </p>
 *
 * @author 二开
 */
public class CtmsProduct extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID） */
    private String id;

    /** 物料编码（全局唯一；留空时服务端自动生成） */
    private String code;

    /** 物料名称 */
    private String name;

    /** 规格（可空：行项名称/规格为空时回落本档案） */
    private String spec;

    /** 商品类型ID（必须是叶子类型） */
    private String productTypeId;

    /** 计量单位ID */
    private String uomId;

    /** 品牌（可空） */
    private String brand;

    /** 条码（可空） */
    private String barcode;

    /** 默认单价（不允许为负；null 视同 0） */
    private BigDecimal defaultPrice;

    /** 安全库存（可空；不允许为负） */
    private BigDecimal safetyStock;

    /** 启用标志：1-启用 0-停用 */
    private String enableFlag;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID */
    private String updateId;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getSpec()
    {
        return spec;
    }

    public void setSpec(String spec)
    {
        this.spec = spec;
    }

    public String getProductTypeId()
    {
        return productTypeId;
    }

    public void setProductTypeId(String productTypeId)
    {
        this.productTypeId = productTypeId;
    }

    public String getUomId()
    {
        return uomId;
    }

    public void setUomId(String uomId)
    {
        this.uomId = uomId;
    }

    public String getBrand()
    {
        return brand;
    }

    public void setBrand(String brand)
    {
        this.brand = brand;
    }

    public String getBarcode()
    {
        return barcode;
    }

    public void setBarcode(String barcode)
    {
        this.barcode = barcode;
    }

    public BigDecimal getDefaultPrice()
    {
        return defaultPrice;
    }

    public void setDefaultPrice(BigDecimal defaultPrice)
    {
        this.defaultPrice = defaultPrice;
    }

    public BigDecimal getSafetyStock()
    {
        return safetyStock;
    }

    public void setSafetyStock(BigDecimal safetyStock)
    {
        this.safetyStock = safetyStock;
    }

    public String getEnableFlag()
    {
        return enableFlag;
    }

    public void setEnableFlag(String enableFlag)
    {
        this.enableFlag = enableFlag;
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

    @Override
    public String toString()
    {
        return "CtmsProduct{id=" + id + ", code=" + code + ", name=" + name + ", spec=" + spec
                + ", productTypeId=" + productTypeId + ", uomId=" + uomId + ", brand=" + brand
                + ", barcode=" + barcode + ", defaultPrice=" + defaultPrice
                + ", safetyStock=" + safetyStock + ", enableFlag=" + enableFlag
                + ", createId=" + createId + ", updateId=" + updateId + "}";
    }
}
