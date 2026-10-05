package com.ruoyi.ctms.domain;

import java.math.BigDecimal;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 供应商档案对象，对应表 {@code t_ctms_supplier}（B3 主数据域，DDL 见
 * {@code sql/二开-合同台账.sql} 第 138~167 行）。 </p>
 *
 * <p> 与 {@link CtmsCustomer} 的差异只有两列：{@code supplyScope}（供货范围）与
 * {@code paymentDays}（账期天数）；其余列一一对应。注意两点刻意的不对称： </p>
 * <ul>
 *   <li> {@code shortName} 在本表是<b>业务必填</b>（DDL 仍是可空，非空由应用层
 *        {@code PartnerRules.checkSupplierRequired} 守卫）； </li>
 *   <li> {@code paymentDays} 可空，且 {@code 0} 与 {@code null} 语义不同
 *        （0 = 现结/无账期，null = 未填），所以用 {@code Integer} 而非 {@code int}。 </li>
 * </ul>
 *
 * <p> ⚠ {@code createBy} / {@code createTime} / {@code updateBy} / {@code updateTime} / {@code remark}
 * 由 {@link BaseEntity} 提供，本类<b>不重复声明</b>。 </p>
 *
 * @author 二开
 */
public class CtmsSupplier extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID，32 位十六进制大写） */
    private String id;

    /** 供应商编码（全局唯一，停用项也占号；登记后不可修改） */
    private String code;

    /** 供应商名称（全局唯一） */
    private String name;

    /** 供应商简称（**必填**，与客户刻意不对称） */
    private String shortName;

    /** 纳税人识别号 */
    private String taxNo;

    /** 联系人 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 地址 */
    private String address;

    /** 开户行 */
    private String bankName;

    /** 银行账号 */
    private String bankAccount;

    /** 授信额度（非必填，可空） */
    private BigDecimal creditLimit;

    /** 供应商等级 A/B/C/D（可空，非强约束） */
    private String level;

    /** 供货范围（可空） */
    private String supplyScope;

    /** 账期天数（可空；0 合法，负数由应用层拒绝） */
    private Integer paymentDays;

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

    public String getShortName()
    {
        return shortName;
    }

    public void setShortName(String shortName)
    {
        this.shortName = shortName;
    }

    public String getTaxNo()
    {
        return taxNo;
    }

    public void setTaxNo(String taxNo)
    {
        this.taxNo = taxNo;
    }

    public String getContactName()
    {
        return contactName;
    }

    public void setContactName(String contactName)
    {
        this.contactName = contactName;
    }

    public String getContactPhone()
    {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone)
    {
        this.contactPhone = contactPhone;
    }

    public String getAddress()
    {
        return address;
    }

    public void setAddress(String address)
    {
        this.address = address;
    }

    public String getBankName()
    {
        return bankName;
    }

    public void setBankName(String bankName)
    {
        this.bankName = bankName;
    }

    public String getBankAccount()
    {
        return bankAccount;
    }

    public void setBankAccount(String bankAccount)
    {
        this.bankAccount = bankAccount;
    }

    public BigDecimal getCreditLimit()
    {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit)
    {
        this.creditLimit = creditLimit;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getSupplyScope()
    {
        return supplyScope;
    }

    public void setSupplyScope(String supplyScope)
    {
        this.supplyScope = supplyScope;
    }

    public Integer getPaymentDays()
    {
        return paymentDays;
    }

    public void setPaymentDays(Integer paymentDays)
    {
        this.paymentDays = paymentDays;
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
        return "CtmsSupplier{" +
                "id='" + id + '\'' +
                ", code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", shortName='" + shortName + '\'' +
                ", taxNo='" + taxNo + '\'' +
                ", contactName='" + contactName + '\'' +
                ", contactPhone='" + contactPhone + '\'' +
                ", address='" + address + '\'' +
                ", bankName='" + bankName + '\'' +
                ", bankAccount='" + bankAccount + '\'' +
                ", creditLimit=" + creditLimit +
                ", level='" + level + '\'' +
                ", supplyScope='" + supplyScope + '\'' +
                ", paymentDays=" + paymentDays +
                ", enableFlag='" + enableFlag + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", remark='" + getRemark() + '\'' +
                '}';
    }
}
