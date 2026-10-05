package com.ruoyi.ctms.domain.vo;

import java.math.BigDecimal;

/**
 * <p> 迁移草案认领的请求体（2.0 B3 任务 7.3；规格 {@code ctms/contract-migration} 的
 * 「草案认领与批量绑定」）。 </p>
 *
 * <p> 一次认领只有两种形态，由本对象的字段决定： </p>
 * <ul>
 *   <li> <b>绑定已有档案</b>：填 {@link #partyId}（客户或供应商的档案ID，按草案的
 *        {@code party_type} 决定指向哪张表）； </li>
 *   <li> <b>新建档案后绑定</b>：不填 {@code partyId}，填档案字段（{@link #code} 必填；
 *        其余按档案口径，见下）。 </li>
 * </ul>
 *
 * <p> <b>为什么用一个"扁平的并集"对象而不是直接收 {@code CtmsCustomer}/{@code CtmsSupplier}</b>：
 * 草案的方向（{@code party_type}）在服务端才知道，请求体里同时带两种类型会变成"哪个非空用哪个"的
 * 隐式约定；扁平对象让前端只提交它有的字段，服务端按方向映射（客户忽略 {@code supplyScope}/
 * {@code paymentDays}）。 </p>
 *
 * <p> <b>两个兜底口径</b>（迁移场景的顺手默认值，都写在服务实现的类注释里）： </p>
 * <ul>
 *   <li> {@link #name} 为空 → 用草案的 {@code raw_name}（档案名就是当初写的那段文本）； </li>
 *   <li> {@link #shortName} 为空且方向是供应商 → 用草案的 {@code raw_name}
 *        （供应商简称在规格里是必填，迁移期不允许因为"简称没填"卡住认领）。 </li>
 * </ul>
 *
 * <p> 档案编码 {@link #code} <b>不做兜底</b>：编码是全局唯一键、也是第 3 组的既定口径，
 * 自动生成会引入第二套编码规则；新建模式下缺编码直接报「新建档案必须提供编码」。 </p>
 *
 * @author 二开
 */
public class CtmsMigrationClaimVo
{
    /* ==================== 认领方式 ==================== */

    /** 绑定已有档案的档案ID（为空表示"新建档案后绑定"） */
    private String partyId;

    /* ==================== 新建档案字段（客户与供应商的并集） ==================== */

    /** 档案编码（新建时必填；不自动生成，避免第二套编码规则） */
    private String code;

    /** 档案名称（为空时以草案 raw_name 兜底） */
    private String name;

    /** 简称（客户可空；供应商在规格里必填，为空时以草案 raw_name 兜底） */
    private String shortName;

    /** 税号 */
    private String taxNo;

    /** 联系人 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 地址 */
    private String address;

    /** 开户银行 */
    private String bankName;

    /** 银行账号 */
    private String bankAccount;

    /** 授信额度 */
    private BigDecimal creditLimit;

    /** 档案等级 */
    private String level;

    /** 供货范围（仅供应商方向使用） */
    private String supplyScope;

    /** 账期天数（仅供应商方向使用；0 表示现结） */
    private Integer paymentDays;

    /* ==================== 认领备注 ==================== */

    /** 备注：写入草案 {@code remark}，用于记录这次认领的人工说明 */
    private String remark;

    public String getPartyId()
    {
        return partyId;
    }

    public void setPartyId(String partyId)
    {
        this.partyId = partyId;
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

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }

    @Override
    public String toString()
    {
        return "CtmsMigrationClaimVo{" +
                "partyId='" + partyId + '\'' +
                ", code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", shortName='" + shortName + '\'' +
                ", paymentDays=" + paymentDays +
                ", remark='" + remark + '\'' +
                '}';
    }
}
