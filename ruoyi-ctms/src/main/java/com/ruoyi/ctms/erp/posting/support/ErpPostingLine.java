package com.ruoyi.ctms.erp.posting.support;

import java.math.BigDecimal;

/**
 * <p> <b>一条待过账的行指令</b>（2.0 B4 任务 5.2/5.4；design D2/D8）。 </p>
 *
 * <p> 过账引擎（{@code IErpStockJournalService}）只认这个对象，不认实体：
 * 入库/出库单服务、调拨（两仓两条）、盘点生成的盘盈盘亏单都归一到它，
 * 于是"行锁 + 负库存校验 + 追加流水 + 变动后结存快照"只有一份实现。 </p>
 *
 * <p> <b>{@code qtyChange} 是已带符号的数量变动</b>（正入负出）：入库单传正数，
 * 出库单传负数，调拨的两个方向分别传 -qty 与 +qty。引擎不再看单据类型来决定方向，
 * 避免"方向由三处推断"（单据类型 / 业务类型字符串 / 数量符号）而互相矛盾。 </p>
 *
 * <p> {@code productName}/{@code warehouseName} 只用于<b>错误文案</b>（"可用量 3，需求量 5"
 * 要让用户知道是哪个物料哪个仓库），缺失不影响过账。 </p>
 *
 * @author 二开
 */
public class ErpPostingLine
{
    /** 行号（1 起；错误文案用）。 */
    private int rowNo;

    /** 物料ID。 */
    private String productId;

    /** 物料名称（仅文案用，可空）。 */
    private String productName;

    /** 仓库ID。 */
    private String warehouseId;

    /** 仓库名称（仅文案用，可空）。 */
    private String warehouseName;

    /** 数量变动（正入负出；定点 3 位）。 */
    private BigDecimal qtyChange;

    /** 单价（定点 4 位；记录性字段）。 */
    private BigDecimal unitPrice;

    /** 来源行项ID（下推回写用，可空，无外键）。 */
    private String srcItemId;

    /** 行备注（写入流水的 remark，可空）。 */
    private String remark;

    /**
     * 行级业务类型覆盖（可空）。
     *
     * <p> 为什么需要它：反审核红冲要把<b>每条原流水的业务类型</b>各自加上 {@code 红冲-} 前缀，
     * 而一张调拨单的两条流水业务类型不同（{@code 调拨出库} / {@code 调拨入库}），
     * 用"请求级单一业务类型"无法表达。为空时取 {@code ErpPostingRequest.bizType}。 </p>
     */
    private String bizType;

    public ErpPostingLine()
    {
    }

    /**
     * 构造一条行指令。
     *
     * @param rowNo       行号（1 起）
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @param qtyChange   带符号数量变动
     * @param unitPrice   单价
     * @return 行指令
     */
    public static ErpPostingLine of(int rowNo, String productId, String warehouseId,
                                    BigDecimal qtyChange, BigDecimal unitPrice)
    {
        ErpPostingLine line = new ErpPostingLine();
        line.rowNo = rowNo;
        line.productId = productId;
        line.warehouseId = warehouseId;
        line.qtyChange = qtyChange;
        line.unitPrice = unitPrice;
        return line;
    }

    public int getRowNo()
    {
        return rowNo;
    }

    public ErpPostingLine setRowNo(int rowNo)
    {
        this.rowNo = rowNo;
        return this;
    }

    public String getProductId()
    {
        return productId;
    }

    public ErpPostingLine setProductId(String productId)
    {
        this.productId = productId;
        return this;
    }

    public String getProductName()
    {
        return productName;
    }

    public ErpPostingLine setProductName(String productName)
    {
        this.productName = productName;
        return this;
    }

    public String getWarehouseId()
    {
        return warehouseId;
    }

    public ErpPostingLine setWarehouseId(String warehouseId)
    {
        this.warehouseId = warehouseId;
        return this;
    }

    public String getWarehouseName()
    {
        return warehouseName;
    }

    public ErpPostingLine setWarehouseName(String warehouseName)
    {
        this.warehouseName = warehouseName;
        return this;
    }

    public BigDecimal getQtyChange()
    {
        return qtyChange;
    }

    public ErpPostingLine setQtyChange(BigDecimal qtyChange)
    {
        this.qtyChange = qtyChange;
        return this;
    }

    public BigDecimal getUnitPrice()
    {
        return unitPrice;
    }

    public ErpPostingLine setUnitPrice(BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
        return this;
    }

    public String getSrcItemId()
    {
        return srcItemId;
    }

    public ErpPostingLine setSrcItemId(String srcItemId)
    {
        this.srcItemId = srcItemId;
        return this;
    }

    public String getRemark()
    {
        return remark;
    }

    public ErpPostingLine setRemark(String remark)
    {
        this.remark = remark;
        return this;
    }

    public String getBizType()
    {
        return bizType;
    }

    public ErpPostingLine setBizType(String bizType)
    {
        this.bizType = bizType;
        return this;
    }

    /**
     * 展示用的物料名（缺失时回落 ID，保证文案里永远有可辨识的标识）。
     *
     * @return 物料名
     */
    public String productLabel()
    {
        return productName == null || productName.trim().isEmpty() ? productId : productName;
    }

    /**
     * 展示用的仓库名。
     *
     * @return 仓库名
     */
    public String warehouseLabel()
    {
        return warehouseName == null || warehouseName.trim().isEmpty() ? warehouseId : warehouseName;
    }

    @Override
    public String toString()
    {
        return "ErpPostingLine{rowNo=" + rowNo + ", productId='" + productId + "', warehouseId='"
                + warehouseId + "', qtyChange=" + qtyChange + '}';
    }
}
