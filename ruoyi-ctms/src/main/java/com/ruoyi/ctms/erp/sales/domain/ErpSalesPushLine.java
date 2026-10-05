package com.ruoyi.ctms.erp.sales.domain;

import java.math.BigDecimal;

/**
 * <p> <b>下推行</b>（销售申请 → 销售订单的入参，tasks.md §4.2 / 移植清单 §3.9.4）。 </p>
 *
 * <p> 对应参考仓库 {@code push_service.py:69-101} 的 {@code rows} 元素：每行给"来源行 + 本次下推数量"。
 * 三个维度都可选，按优先级匹配来源行： </p>
 * <ol>
 *   <li> {@link #docItemId} —— 来源<b>行项ID</b>（最精确；下推对话框回传的 {@code srcItemId}）； </li>
 *   <li> {@link #productId} —— 物料ID； </li>
 *   <li> {@link #index} —— 行序号（0 起；都不给时按数组顺序与单据行顺序一一对应）。 </li>
 * </ol>
 *
 * <p> {@link #qty} 为 {@code null} 或 0 时表示"按剩余量全推"（参考侧 {@code rows=None} 的语义）；
 * 单价为 {@code null} 时沿用来源行的单价。 </p>
 *
 * @author 二开
 */
public class ErpSalesPushLine
{
    /** 来源行项ID（可空）。 */
    private String docItemId;

    /** 物料ID（可空；按物料匹配来源行）。 */
    private String productId;

    /**
     * 来源行项ID 的<b>别名键</b>（可空；与 {@link #docItemId} 同义，二者取先命中的那个）。
     *
     * <p> 存在理由是前端两条下推链用了不同的键名：销售申请 → 销售订单那条链发
     * {@code docItemId}（{@code doc-rules.js} 的 {@code lineKeys.srcItemId: 'docItemId'}），
     * 销售订单 → 出库单那条链发 {@code srcItemId}（同文件 {@code sales_order.push.lineKeys}，
     * 与采购线一致）。两个键都收，避免"同一条后端入口因前端链不同而改名"的无谓分叉。 </p>
     */
    private String srcItemId;

    /** 行序号（可空；0 起，按数组顺序对应单据行顺序）。 */
    private Integer index;

    /** 本次下推数量（可空或 0 表示"按剩余量全推"）。 */
    private BigDecimal qty;

    /** 下推单价（可空：沿用来源行单价）。 */
    private BigDecimal unitPrice;

    /** 行备注（可空）。 */
    private String remark;

    public String getDocItemId()
    {
        return docItemId;
    }

    public void setDocItemId(String docItemId)
    {
        this.docItemId = docItemId;
    }

    public String getSrcItemId()
    {
        return srcItemId;
    }

    public void setSrcItemId(String srcItemId)
    {
        this.srcItemId = srcItemId;
    }

    /**
     * 有效的来源行项ID：{@link #docItemId} 优先，其次 {@link #srcItemId}（两个键都空返回 null）。
     *
     * @return 来源行项ID
     */
    public String effectiveDocItemId()
    {
        if (docItemId != null && !docItemId.trim().isEmpty())
        {
            return docItemId.trim();
        }
        if (srcItemId != null && !srcItemId.trim().isEmpty())
        {
            return srcItemId.trim();
        }
        return null;
    }

    public String getProductId()
    {
        return productId;
    }

    public void setProductId(String productId)
    {
        this.productId = productId;
    }

    public Integer getIndex()
    {
        return index;
    }

    public void setIndex(Integer index)
    {
        this.index = index;
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

    public String getRemark()
    {
        return remark;
    }

    public void setRemark(String remark)
    {
        this.remark = remark;
    }
}
