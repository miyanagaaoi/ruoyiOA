package com.ruoyi.ctms.domain;

import java.math.BigDecimal;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 客户档案对象，对应表 {@code t_ctms_customer}（B3 主数据域，DDL 见
 * {@code sql/二开-合同台账.sql} 第 110~136 行）。 </p>
 *
 * <p> 字段与 DDL <b>逐列对应</b>，类型口径见 {@code com.ruoyi.ctms.domain} 的 package-info：
 * {@code varchar → String}、{@code decimal → BigDecimal}、{@code int → Integer}、
 * {@code datetime → Date}、{@code char(1) → String}。 </p>
 *
 * <p> ⚠ {@code createBy} / {@code createTime} / {@code updateBy} / {@code updateTime} / {@code remark}
 * 由 {@link BaseEntity} 提供，本类<b>不重复声明</b>；{@code createId} / {@code updateId}
 * （创建人/更新人的用户ID快照）在基类里没有，因此单独声明。 </p>
 *
 * @author 二开
 */
public class CtmsCustomer extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID，32 位十六进制大写） */
    private String id;

    /** 客户编码（全局唯一，停用项也占号；登记后不可修改） */
    private String code;

    /** 客户名称（全局唯一） */
    private String name;

    /** 客户简称（可为空，与供应商刻意不对称） */
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

    /** 客户等级 A/B/C/D（可空，非强约束） */
    private String level;

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
        return "CtmsCustomer{" +
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
