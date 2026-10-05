package com.ruoyi.ctms.erp.posting.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p> <b>库存流水</b>（表 {@code t_ctms_stock_ledger}，2.0 B4 任务 5.2/5.4；design D2/D5）。 </p>
 *
 * <p> <b>只增不改</b>：DDL 里刻意没有 {@code update_time}/{@code update_id}/{@code update_by} 列，
 * 也没有修改/删除入口 —— 红冲是"追加一条负数流水"，原流水保留可查（AC-73）。
 * 因此本类<b>不提供</b> setter 之外的服务侧更新方法，Mapper 也只有 insert 与按条件查询。 </p>
 *
 * <p> 三个口径记在字段注释里（这是评审最容易看错的地方，不要"顺手统一"）： </p>
 * <ul>
 *   <li> {@code qtyChange} <b>正入负出</b>；红冲写反向（入库的红冲是负数）； </li>
 *   <li> {@code qtyAfter} 是<b>变动后结存快照</b>，在同一次过账里逐行累加算出（调拨两行也是各自仓库的快照）； </li>
 *   <li> {@code unitPrice} 是<b>记录性</b>字段：只用于「货品总额度」统计与单据展示，
 *        不参与结存计算（design D5），调拨写 0。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStockLedger implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 红冲流水的业务类型前缀（{@code 采购入库} → {@code 红冲-采购入库}）。 */
    public static final String REVERSAL_PREFIX = "红冲-";

    /** 红冲流水的固定备注（反审核红冲留痕口径）。 */
    public static final String REVERSAL_REMARK = "反审核红冲";

    /** 主键（应用侧 UUID）。 */
    private String id;

    /** 物料档案ID。 */
    private String productId;

    /** 仓库ID。 */
    private String warehouseId;

    /** 业务类型（如 {@code 采购入库} / {@code 销售出库} / {@code 调拨入库}；红冲带 {@value #REVERSAL_PREFIX} 前缀）。 */
    private String bizType;

    /** 单据类型（{@code stock_in} / {@code stock_out} / {@code stock_transfer} / {@code stock_take}）。 */
    private String docType;

    /** 单据ID（多态，无外键；与 {@link #docType} 成对）。 */
    private String docId;

    /** 单据号（调拨的两条流水共用同一单号）。 */
    private String docNo;

    /** 来源单号快照（下推链路的源单，可空）。 */
    private String srcDocNo;

    /** 数量变动（正入负出）。 */
    private BigDecimal qtyChange;

    /** 变动后结存快照。 */
    private BigDecimal qtyAfter;

    /** 单价（记录性；调拨为 0）。 */
    private BigDecimal unitPrice;

    /** 归属部门快照（过账时取单据 dept_id；数据范围判定用，可空）。 */
    private String deptId;

    /** 操作人用户ID（可空，无外键）。 */
    private String createId;

    /** 操作人登录名快照（可空）。 */
    private String createBy;

    /** 备注（红冲写「反审核红冲」+ 反审核原因）。 */
    private String remark;

    /** 记账时间。 */
    private Date createTime;

    /**
     * 是否为红冲流水（业务类型带红冲前缀）。
     *
     * @return 红冲返回 true
     */
    public boolean isReversal()
    {
        return bizType != null && bizType.startsWith(REVERSAL_PREFIX);
    }

    /**
     * 取原业务类型（红冲去掉前缀；非红冲原样返回）。
     *
     * @return 业务类型
     */
    public String baseBizType()
    {
        return isReversal() ? bizType.substring(REVERSAL_PREFIX.length()) : bizType;
    }

    /**
     * 生成红冲业务类型（幂等：已带前缀不再叠加）。
     *
     * @param bizType 原业务类型
     * @return 形如 {@code 红冲-采购入库}
     */
    public static String reversalBizType(String bizType)
    {
        if (bizType == null || bizType.trim().isEmpty())
        {
            return REVERSAL_PREFIX;
        }
        String value = bizType.trim();
        return value.startsWith(REVERSAL_PREFIX) ? value : REVERSAL_PREFIX + value;
    }

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

    public String getWarehouseId()
    {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId)
    {
        this.warehouseId = warehouseId;
    }

    public String getBizType()
    {
        return bizType;
    }

    public void setBizType(String bizType)
    {
        this.bizType = bizType;
    }

    public String getDocType()
    {
        return docType;
    }

    public void setDocType(String docType)
    {
        this.docType = docType;
    }

    public String getDocId()
    {
        return docId;
    }

    public void setDocId(String docId)
    {
        this.docId = docId;
    }

    public String getDocNo()
    {
        return docNo;
    }

    public void setDocNo(String docNo)
    {
        this.docNo = docNo;
    }

    public String getSrcDocNo()
    {
        return srcDocNo;
    }

    public void setSrcDocNo(String srcDocNo)
    {
        this.srcDocNo = srcDocNo;
    }

    public BigDecimal getQtyChange()
    {
        return qtyChange;
    }

    public void setQtyChange(BigDecimal qtyChange)
    {
        this.qtyChange = qtyChange;
    }

    public BigDecimal getQtyAfter()
    {
        return qtyAfter;
    }

    public void setQtyAfter(BigDecimal qtyAfter)
    {
        this.qtyAfter = qtyAfter;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public String getDeptId()
    {
        return deptId;
    }

    public void setDeptId(String deptId)
    {
        this.deptId = deptId;
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

    @Override
    public String toString()
    {
        return "ErpStockLedger{productId='" + productId + "', warehouseId='" + warehouseId + "', bizType='"
                + bizType + "', docType='" + docType + "', docNo='" + docNo + "', qtyChange=" + qtyChange
                + ", qtyAfter=" + qtyAfter + '}';
    }
}
