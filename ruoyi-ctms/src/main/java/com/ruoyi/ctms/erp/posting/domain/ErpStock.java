package com.ruoyi.ctms.erp.posting.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p> <b>库存结存</b>（表 {@code t_ctms_stock}，2.0 B4 任务 5.2；design D2/D3/D5）。 </p>
 *
 * <p> 一行 = 一个（物料 × 仓库）的当前数量。<b>本表只能由过账服务维护</b>：
 * 界面上没有任何写入口（tasks.md §7.1 的"界面不能直改结存"），
 * 唯一的写路径是 {@code ErpStockJournalServiceImpl} 的过账 / 红冲。 </p>
 *
 * <p> <b>只记数量不记成本</b>（design D5）：DDL 里没有金额/单价列，
 * 所以结存永远不会因为单价口径变化而需要重算。 </p>
 *
 * <p> {@code (product_id, warehouse_id)} 上有唯一约束
 * {@code uk_stock_product_warehouse}：过账取行时先 {@code SELECT ... FOR UPDATE} 加锁，
 * 不存在则插入；并发插入撞唯一键时由过账服务捕获重复键并在有限次内重试（D3）。 </p>
 *
 * @author 二开
 */
public class ErpStock implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID）。 */
    private String id;

    /** 物料档案ID（与 {@link #warehouseId} 构成唯一键）。 */
    private String productId;

    /** 仓库ID。 */
    private String warehouseId;

    /** 结存数量（定点 3 位；正负均可，负值只在"允许负库存"或红冲场景出现）。 */
    private BigDecimal qty;

    /** 创建时间（首次过账建行时间）。 */
    private Date createTime;

    /** 最后过账时间。 */
    private Date updateTime;

    /** 零数量（内部比较用；避免各调用方各写一份 {@code new BigDecimal("0")}）。 */
    public static final BigDecimal ZERO = BigDecimal.ZERO;

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

    public BigDecimal getQty()
    {
        return qty;
    }

    public void setQty(BigDecimal qty)
    {
        this.qty = qty;
    }

    /**
     * 数量（{@code null} 视为 0；过账路径一律走这里，避免 NPE）。
     *
     * @return 非 null 数量
     */
    public BigDecimal qtyOrZero()
    {
        return qty == null ? ZERO : qty;
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

    @Override
    public String toString()
    {
        return "ErpStock{id='" + id + "', productId='" + productId + "', warehouseId='" + warehouseId
                + "', qty=" + qty + '}';
    }
}
