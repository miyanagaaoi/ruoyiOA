package com.ruoyi.ctms.erp.ledger.domain;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * <p> <b>库存结存一致性校验结果</b>（B4 任务 7.4；{@code REQ-STK-006}、AC-76）。 </p>
 *
 * <p> 不变式只有一条：<b>对每个（物料, 仓库），{@code t_ctms_stock.qty == Σ t_ctms_stock_ledger.qty_change}</b>。 </p>
 *
 * <p> 返回体的四个计数刻意分开，便于运维一眼看出"查了多少 / 有多少不一致 / 修了几行"： </p>
 * <ul>
 *   <li> {@link #checkedRows} —— 被检查的结存行数； </li>
 *   <li> {@link #consistent} —— 是否全部一致（{@code inconsistentCount == 0}）； </li>
 *   <li> {@link #inconsistentCount} —— 不一致行数（= {@link #mismatches} 的大小）； </li>
 *   <li> {@link #repairedCount} —— 本次实际被改写的行数（未开修复时恒 0）。 </li>
 * </ul>
 *
 * <p> <b>修复是危险操作</b>：它把结存改写成"流水累计"，因此只在确认流水可信时使用
 * （例如曾经手工改过结存、或历史上的并发丢更新）。风险提示写在 {@code notes/07-ledger.md}。 </p>
 *
 * @author 二开
 */
public class ErpStockRecalcResult implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 被检查的结存行数。 */
    private int checkedRows;

    /** 是否全部一致。 */
    private boolean consistent;

    /** 不一致行数。 */
    private int inconsistentCount;

    /** 是否开启了修复。 */
    private boolean repair;

    /** 实际被改写的行数（未开修复恒 0）。 */
    private int repairedCount;

    /** 不一致明细（最多与 {@link #inconsistentCount} 等长）。 */
    private java.util.List<Mismatch> mismatches = new java.util.ArrayList<>();

    /**
     * 一行不一致的明细：结存 vs 流水累计。
     */
    public static class Mismatch implements Serializable
    {
        private static final long serialVersionUID = 1L;

        /** 物料档案ID。 */
        private String productId;

        /** 仓库ID。 */
        private String warehouseId;

        /** 结存列里的数量。 */
        private BigDecimal stockQty;

        /** 流水累计和。 */
        private BigDecimal ledgerQty;

        /** 差值 = 结存 − 流水累计（正=结存多记，负=结存少记）。 */
        private BigDecimal diff;

        /**
         * 构造一条不一致明细。
         *
         * @param productId   物料ID
         * @param warehouseId 仓库ID
         * @param stockQty    结存数量
         * @param ledgerQty   流水累计
         */
        public Mismatch(String productId, String warehouseId, BigDecimal stockQty, BigDecimal ledgerQty)
        {
            this.productId = productId;
            this.warehouseId = warehouseId;
            this.stockQty = stockQty;
            this.ledgerQty = ledgerQty;
            BigDecimal left = stockQty == null ? BigDecimal.ZERO : stockQty;
            BigDecimal right = ledgerQty == null ? BigDecimal.ZERO : ledgerQty;
            this.diff = left.subtract(right);
        }

        public String getProductId()
        {
            return productId;
        }

        public String getWarehouseId()
        {
            return warehouseId;
        }

        public BigDecimal getStockQty()
        {
            return stockQty;
        }

        public BigDecimal getLedgerQty()
        {
            return ledgerQty;
        }

        public BigDecimal getDiff()
        {
            return diff;
        }

        @Override
        public String toString()
        {
            return "Mismatch{productId='" + productId + "', warehouseId='" + warehouseId
                    + "', stockQty=" + stockQty + ", ledgerQty=" + ledgerQty + ", diff=" + diff + '}';
        }
    }

    public int getCheckedRows()
    {
        return checkedRows;
    }

    public void setCheckedRows(int checkedRows)
    {
        this.checkedRows = checkedRows;
    }

    public boolean isConsistent()
    {
        return consistent;
    }

    public void setConsistent(boolean consistent)
    {
        this.consistent = consistent;
    }

    public int getInconsistentCount()
    {
        return inconsistentCount;
    }

    public void setInconsistentCount(int inconsistentCount)
    {
        this.inconsistentCount = inconsistentCount;
    }

    public boolean isRepair()
    {
        return repair;
    }

    public void setRepair(boolean repair)
    {
        this.repair = repair;
    }

    public int getRepairedCount()
    {
        return repairedCount;
    }

    public void setRepairedCount(int repairedCount)
    {
        this.repairedCount = repairedCount;
    }

    public java.util.List<Mismatch> getMismatches()
    {
        return mismatches;
    }

    public void setMismatches(java.util.List<Mismatch> mismatches)
    {
        this.mismatches = mismatches == null ? new java.util.ArrayList<Mismatch>() : mismatches;
    }

    @Override
    public String toString()
    {
        return "ErpStockRecalcResult{checkedRows=" + checkedRows + ", consistent=" + consistent
                + ", inconsistentCount=" + inconsistentCount + ", repair=" + repair
                + ", repairedCount=" + repairedCount + '}';
    }
}
