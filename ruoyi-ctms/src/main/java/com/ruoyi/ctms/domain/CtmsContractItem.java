package com.ruoyi.ctms.domain;

import java.math.BigDecimal;
import java.util.List;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 合同行项对象，对应表 {@code t_ctms_contract_item}（19 列，B3 第 4 组；DDL 见
 * {@code sql/二开-合同台账.sql} 第 348~372 行）。 </p>
 *
 * <p> <b>金额口径（C-1，规格 ctms/contract-commercials）</b>：行总价 {@code total} =
 * 数量 {@code qty} × 单价 {@code unitPrice} 后按 HALF_UP 舍入到 2 位，合同金额是各<b>已舍入</b>行总价
 * 之和；计算入口见 {@code com.ruoyi.ctms.support.ContractRules#lineTotal}。
 * 数量 scale=3、单价 scale=4、金额 scale=2，全程 {@code BigDecimal}，禁止二进制浮点类型。 </p>
 *
 * <p> {@code productCode} / {@code productName} 是<b>物料快照</b>，与 {@code productId} 同生同灭；
 * 行项名称/规格留空时由服务层回落到物料档案（回落规则见
 * {@code ContractRules#itemNameOf} / {@code ContractRules#itemSpecOf}）。 </p>
 *
 * <p> {@code remark} 由 {@link BaseEntity} 提供，本类不重复声明。 </p>
 *
 * @author 二开
 */
public class CtmsContractItem extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID，由服务层生成） */
    private String id;

    /** 合同ID（非空，随主表级联删除） */
    private String contractId;

    /** 序号（按提交顺序从 1 连续重排） */
    private Integer seq;

    /** 行项类型（取值集合落 {@code sys_dict_data.item_types}） */
    private String itemType;

    /** 行项名称（为空时回落物料档案名称） */
    private String name;

    /** 行项规格（为空时回落物料档案规格） */
    private String spec;

    /** 数量（decimal(12,3)） */
    private BigDecimal qty;

    /** 单价（scale=4，负数由应用层拒绝） */
    private BigDecimal unitPrice;

    /** 行总价（先按 HALF_UP 舍入到 2 位再汇总） */
    private BigDecimal total;

    /** 物料档案ID（未选物料被数据库外键拒绝） */
    private String productId;

    /** 物料编码快照 */
    private String productCode;

    /** 物料名称快照（行项名称留空时的回落来源） */
    private String productName;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID（只改物料绑定的编辑也要留痕） */
    private String updateId;

    /* ==================== 非持久化字段（查询条件） ==================== */

    /** 合同ID集合：批量按合同集合查行项（列表页一次取回多合同的摘要用） */
    private List<String> contractIds;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getContractId()
    {
        return contractId;
    }

    public void setContractId(String contractId)
    {
        this.contractId = contractId;
    }

    public Integer getSeq()
    {
        return seq;
    }

    public void setSeq(Integer seq)
    {
        this.seq = seq;
    }

    public String getItemType()
    {
        return itemType;
    }

    public void setItemType(String itemType)
    {
        this.itemType = itemType;
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

    public BigDecimal getTotal()
    {
        return total;
    }

    public void setTotal(BigDecimal total)
    {
        this.total = total;
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

    public List<String> getContractIds()
    {
        return contractIds;
    }

    public void setContractIds(List<String> contractIds)
    {
        this.contractIds = contractIds;
    }

    @Override
    public String toString()
    {
        return "CtmsContractItem{" +
                "id='" + id + '\'' +
                ", contractId='" + contractId + '\'' +
                ", seq=" + seq +
                ", itemType='" + itemType + '\'' +
                ", name='" + name + '\'' +
                ", spec='" + spec + '\'' +
                ", qty=" + qty +
                ", unitPrice=" + unitPrice +
                ", total=" + total +
                ", productId='" + productId + '\'' +
                ", productCode='" + productCode + '\'' +
                ", productName='" + productName + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", remark='" + getRemark() + '\'' +
                ", contractIds=" + contractIds +
                '}';
    }
}
