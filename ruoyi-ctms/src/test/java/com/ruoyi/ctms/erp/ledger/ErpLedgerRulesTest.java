package com.ruoyi.ctms.erp.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>库存账口径单测</b>（B4 任务 7.2 的「货品总额度」+ 7.1 的展示与安全库存口径）。 </p>
 *
 * <p> 重点是把<b>反直觉的那条口径</b>锁死：额度按"业务类型含入库且不含调拨入库"筛，
 * 红冲（负数）也计入 ⇒ "入库 10×2 后被红冲"的额度必须是 0；
 * 若有人按"数量变动 &gt; 0"筛就会得到虚高的 20，本测试把那个反例也写成断言。 </p>
 *
 * @author 二开
 */
public class ErpLedgerRulesTest
{
    /**
     * 造一条流水（只填口径相关字段）。
     *
     * @param bizType   业务类型
     * @param qtyChange 数量变动
     * @param unitPrice 单价
     * @return 流水
     */
    private static ErpStockLedger ledger(String bizType, String qtyChange, String unitPrice)
    {
        ErpStockLedger row = new ErpStockLedger();
        row.setBizType(bizType);
        row.setQtyChange(new BigDecimal(qtyChange));
        row.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return row;
    }

    @Test
    public void 采购入库十件两元被红冲后额度为零()
    {
        List<ErpStockLedger> rows = new ArrayList<>();
        rows.add(ledger("采购入库", "10", "2"));
        rows.add(ledger("红冲-采购入库", "-10", "2"));

        assertEquals("入库 10×2 再红冲 -10×2 ⇒ 0.00", new BigDecimal("0.00"), ErpLedgerRules.quotaOf(rows));
    }

    @Test
    public void 反例_按数量变动大于零筛会虚高二十()
    {
        List<ErpStockLedger> rows = new ArrayList<>();
        rows.add(ledger("采购入库", "10", "2"));
        rows.add(ledger("红冲-采购入库", "-10", "2"));

        // 错误口径（只取 qty_change > 0）——故意写在测试里，任何"顺手改成这样"的实现都会被这条打红
        BigDecimal wrong = BigDecimal.ZERO;
        for (ErpStockLedger row : rows)
        {
            if (row.getQtyChange().signum() > 0)
            {
                wrong = wrong.add(ErpLedgerRules.quotaTerm(row.getQtyChange(), row.getUnitPrice()));
            }
        }
        assertEquals("反例口径会得到虚高的 20.00", new BigDecimal("20.00"), wrong);
        assertFalse("正口径不得等于反例值", wrong.compareTo(ErpLedgerRules.quotaOf(rows)) == 0);
    }

    @Test
    public void 调拨入库及其红冲都不计入额度()
    {
        assertFalse(ErpLedgerRules.countedInQuota("调拨入库"));
        assertFalse(ErpLedgerRules.countedInQuota("红冲-调拨入库"));
        assertFalse(ErpLedgerRules.countedInQuota("调拨出库"));
        assertFalse(ErpLedgerRules.countedInQuota("红冲-调拨出库"));
        assertTrue(ErpLedgerRules.countedInQuota("采购入库"));
        assertTrue(ErpLedgerRules.countedInQuota("红冲-采购入库"));
        assertTrue(ErpLedgerRules.countedInQuota("退货入库"));
        assertTrue(ErpLedgerRules.countedInQuota("盘盈入库"));
        assertFalse(ErpLedgerRules.countedInQuota("销售出库"));
        assertFalse(ErpLedgerRules.countedInQuota("盘亏出库"));
        assertFalse(ErpLedgerRules.countedInQuota(null));
        assertFalse(ErpLedgerRules.countedInQuota("   "));

        List<ErpStockLedger> rows = new ArrayList<>();
        rows.add(ledger("调拨入库", "5", "0"));
        rows.add(ledger("红冲-调拨入库", "-5", "0"));
        rows.add(ledger("调拨出库", "-5", "0"));
        assertEquals("仓库间搬运不产生货值", new BigDecimal("0.00"), ErpLedgerRules.quotaOf(rows));
    }

    @Test
    public void 额度逐行舍入后再求和()
    {
        List<ErpStockLedger> rows = new ArrayList<>();
        rows.add(ledger("其他入库", "1", "0.005"));
        rows.add(ledger("其他入库", "1", "0.005"));

        // 逐行 1×0.005 → 0.01（HALF_UP），两行合计 0.02；
        // 若先高精度求和再舍入会得到 0.01 —— 差 1 分钱，正是 AC-78 要锁死的行为
        assertEquals(new BigDecimal("0.02"), ErpLedgerRules.quotaOf(rows));
    }

    @Test
    public void 单价为空按零处理()
    {
        List<ErpStockLedger> rows = new ArrayList<>();
        rows.add(ledger("其他入库", "3", null));
        assertEquals(new BigDecimal("0.00"), ErpLedgerRules.quotaOf(rows));
        assertEquals(new BigDecimal("0.00"), ErpLedgerRules.quotaTerm(null, null));
    }

    @Test
    public void 商品类型名称与物料名称的展示口径()
    {
        assertEquals("五金-螺丝", ErpLedgerRules.displayName("五金", "螺丝"));
        assertEquals("螺丝", ErpLedgerRules.displayName(null, "螺丝"));
        assertEquals("螺丝", ErpLedgerRules.displayName("  ", "螺丝"));
        assertEquals("五金", ErpLedgerRules.displayName("五金", null));
        assertEquals("", ErpLedgerRules.displayName(null, null));
        assertEquals("五金-螺丝", ErpLedgerRules.displayName(" 五金 ", " 螺丝 "));
    }

    @Test
    public void 低于安全库存的口径()
    {
        assertTrue(ErpLedgerRules.belowSafetyStock(new BigDecimal("2"), new BigDecimal("5")));
        assertFalse(ErpLedgerRules.belowSafetyStock(new BigDecimal("5"), new BigDecimal("5")));
        assertFalse(ErpLedgerRules.belowSafetyStock(new BigDecimal("6"), new BigDecimal("5")));
        assertFalse(ErpLedgerRules.belowSafetyStock(null, null));
        assertTrue("数量为空按 0 处理，安全库存 5 时属于低于", ErpLedgerRules.belowSafetyStock(null, new BigDecimal("5")));
    }
}
