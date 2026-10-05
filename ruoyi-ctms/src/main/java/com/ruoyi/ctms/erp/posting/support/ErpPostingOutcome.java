package com.ruoyi.ctms.erp.posting.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p> <b>过账/红冲结果</b>（2.0 B4 任务 5.2/5.4）。 </p>
 *
 * <p> 给调用方三样东西：写了几条流水（{@link #ledgerCount()}）、
 * 每条流水的变动后结存（{@link #getLines()}）、按（物料, 仓库）查到的最终结存
 * （{@link #qtyAfterOf(String, String)}）。采购/销售线的下单回写与盘点级联都靠它。 </p>
 *
 * @author 二开
 */
public class ErpPostingOutcome
{
    /** 已落库的行结果（按处理顺序）。 */
    private final List<ErpPostedLine> lines = new ArrayList<>();

    /**
     * 追加一条行结果。
     *
     * @param line 行结果
     */
    public void addLine(ErpPostedLine line)
    {
        if (line != null)
        {
            lines.add(line);
        }
    }

    /**
     * 全部行结果（只读）。
     *
     * @return 只读列表
     */
    public List<ErpPostedLine> getLines()
    {
        return Collections.unmodifiableList(lines);
    }

    /**
     * 写入的流水条数。
     *
     * @return 条数（<= 行指令数）
     */
    public int ledgerCount()
    {
        return lines.size();
    }

    /**
     * 本批次的净数量变动（各行相加；红冲批次为原变动的相反数）。
     *
     * @return 净变动（2 位以上精度，3 位小数）
     */
    public BigDecimal netChange()
    {
        BigDecimal total = BigDecimal.ZERO;
        for (ErpPostedLine line : lines)
        {
            total = total.add(line.getQtyChange() == null ? BigDecimal.ZERO : line.getQtyChange());
        }
        return total;
    }

    /**
     * 取某（物料, 仓库）本批次最后一条流水的变动后结存。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 变动后结存；本批次没有该组合时返回 {@code null}
     */
    public BigDecimal qtyAfterOf(String productId, String warehouseId)
    {
        BigDecimal result = null;
        for (ErpPostedLine line : lines)
        {
            if (eq(line.getProductId(), productId) && eq(line.getWarehouseId(), warehouseId))
            {
                result = line.getQtyAfter();
            }
        }
        return result;
    }

    /**
     * 被冲销的原变动合计（供回写"已入库量/已出库量"用；正常过账 = 净变动）。
     *
     * @return 原变动合计
     */
    public BigDecimal originalNetChange()
    {
        BigDecimal total = BigDecimal.ZERO;
        for (ErpPostedLine line : lines)
        {
            total = total.add(line.getOriginalQtyChange() == null ? BigDecimal.ZERO : line.getOriginalQtyChange());
        }
        return total;
    }

    private static boolean eq(String left, String right)
    {
        return left == null ? right == null : left.equals(right);
    }

    @Override
    public String toString()
    {
        return "ErpPostingOutcome{ledgerCount=" + ledgerCount() + ", netChange=" + netChange() + '}';
    }
}
