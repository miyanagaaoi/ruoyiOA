package com.ruoyi.ctms.erp.base;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 精度口径单测（2.0 B4 任务 1.4；design D9、REQ-NFR-007、AC-78）。 </p>
 *
 * <p> 核心断言是任务书点名的那条：<b>三行 {@code 1 × 0.125} 时每行金额 0.13、合计 0.39</b>
 * （而不是"先高精度求和再舍入"得到的 0.38）。本类同时把 0.38 这条<b>错误口径</b>算出来做对照，
 * 说明两种做法确实差 1 分钱 —— 这样将来有人"顺手优化成先汇总"时会立刻红。 </p>
 *
 * @author 二开
 */
public class ErpAmountsTest
{
    /** 测试用行项（只实现公共基类的字段即可）。 */
    private static class Item extends ErpDocItem
    {
        private static final long serialVersionUID = 1L;

        Item(String qty, String price)
        {
            setQty(new BigDecimal(qty));
            setUnitPrice(new BigDecimal(price));
        }
    }

    @Test
    public void 三行0点125每行0点13合计0点39()
    {
        List<ErpDocItem> items = new ArrayList<>();
        items.add(new Item("1", "0.125"));
        items.add(new Item("1", "0.125"));
        items.add(new Item("1", "0.125"));

        for (ErpDocItem item : items)
        {
            BigDecimal amount = item.recalcAmount();
            assertEquals("每行金额必须 HALF_UP 到 2 位", "0.13", amount.toPlainString());
        }
        BigDecimal total = ErpAmounts.totalOf(items);
        assertEquals("合计 = 各行舍入后金额之和", "0.39", total.toPlainString());

        // 反面对照：先按高精度求和再统一舍入 → 0.375 → 0.38（差 1 分钱，被 AC-78 明确否决）
        BigDecimal naive = new BigDecimal("0.375").setScale(2, RoundingMode.HALF_UP);
        assertEquals("错误口径会得到 0.38", "0.38", naive.toPlainString());
        assertNotEquals("两种口径必须不同（否则本用例失去判别力）", naive, total);
    }

    @Test
    public void 行金额与合计都是定点两位()
    {
        assertEquals("1×0.125", "0.13", ErpAmounts.lineAmount(new BigDecimal("1"), new BigDecimal("0.125")).toPlainString());
        assertEquals("2×0.125", "0.25", ErpAmounts.lineAmount(new BigDecimal("2"), new BigDecimal("0.125")).toPlainString());
        assertEquals("1×0.005", "0.01", ErpAmounts.lineAmount(new BigDecimal("1"), new BigDecimal("0.005")).toPlainString());
        assertEquals("1×0.004", "0.00", ErpAmounts.lineAmount(new BigDecimal("1"), new BigDecimal("0.004")).toPlainString());
        assertEquals("3×1.005", "3.02", ErpAmounts.lineAmount(new BigDecimal("3"), new BigDecimal("1.005")).toPlainString());
        assertEquals("数量 0.333 × 单价 3", "1.00", ErpAmounts.lineAmount(new BigDecimal("0.333"), new BigDecimal("3")).toPlainString());
    }

    @Test
    public void 空值与null一律按0处理()
    {
        assertEquals("0.00", ErpAmounts.lineAmount(null, null).toPlainString());
        assertEquals("0.00", ErpAmounts.lineAmount(new BigDecimal("5"), null).toPlainString());
        assertEquals("0.00", ErpAmounts.lineAmount(null, new BigDecimal("5")).toPlainString());
        assertEquals("空集合合计为 0.00", "0.00", ErpAmounts.totalOf(null).toPlainString());
        assertEquals("空列表合计为 0.00", "0.00", ErpAmounts.totalOf(new ArrayList<ErpDocItem>()).toPlainString());
        assertEquals("0.00", ErpAmounts.amountTextOf(null));
    }

    @Test
    public void 小数位口径被冻结()
    {
        assertEquals(2, ErpAmounts.AMOUNT_SCALE);
        assertEquals(4, ErpAmounts.PRICE_SCALE);
        assertEquals(3, ErpAmounts.QTY_SCALE);
        assertEquals("四舍五入而不是银行家舍入", RoundingMode.HALF_UP, ErpAmounts.ROUNDING);
        assertEquals("2.5 → 3（HALF_UP 到整数）", "3", ErpAmounts.round(new BigDecimal("2.5"), 0).toPlainString());
        assertEquals("1.005 → 1.01（HALF_UP，而不是银行家舍入会给出的 1.00）",
                "1.01", ErpAmounts.roundAmount(new BigDecimal("1.005")).toPlainString());
        assertEquals("单价 4 位", "1.2345", ErpAmounts.roundPrice(new BigDecimal("1.23445")).toPlainString());
        assertEquals("数量 3 位", "1.235", ErpAmounts.roundQty(new BigDecimal("1.2345")).toPlainString());
    }

    @Test
    public void 数量精度按单位小数位()
    {
        // 单位为「个」(0 位)：1.5 被拒，且文案带行号
        try
        {
            ErpAmounts.checkQuantityScale(new BigDecimal("1.5"), Integer.valueOf(0), 3);
            fail("单位 0 位小数时填 1.5 应被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案必须带行号：" + e.getMessage(), e.getMessage().contains("行 3"));
            assertTrue("文案必须说明位数：" + e.getMessage(), e.getMessage().contains("0 位小数"));
        }
        // 单位 3 位：1.234 通过；1.2345 被拒
        ErpAmounts.checkQuantityScale(new BigDecimal("1.234"), Integer.valueOf(3), 1);
        ErpAmounts.checkQuantityScale(new BigDecimal("2.000"), Integer.valueOf(0), 1);
        ErpAmounts.checkQuantityScale(null, Integer.valueOf(0), 1);
        try
        {
            ErpAmounts.checkQuantityScale(new BigDecimal("1.2345"), Integer.valueOf(3), 1);
            fail("超出单位小数位应被拒");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("行 1"));
        }
    }

    @Test
    public void 盘点差异数量口径()
    {
        assertEquals("账面 10、实盘 7 → -3", "-3.000",
                ErpAmounts.diffQty(new BigDecimal("7"), new BigDecimal("10")).toPlainString());
        assertEquals("账面 10、实盘 12 → 2", "2.000",
                ErpAmounts.diffQty(new BigDecimal("12"), new BigDecimal("10")).toPlainString());
        assertEquals("缺值按 0", "0.000", ErpAmounts.diffQty(null, null).toPlainString());
    }

    @Test
    public void 金额集合求和与展示文本()
    {
        assertEquals("0.39", ErpAmounts.sum(ErpAmounts.asList(
                new BigDecimal("0.125"), new BigDecimal("0.125"), new BigDecimal("0.125"))).toPlainString());
        assertEquals("0.30", ErpAmounts.sum(ErpAmounts.asList(new BigDecimal("0.1"), new BigDecimal("0.2"))).toPlainString());
        assertEquals("0.00", ErpAmounts.sum(null).toPlainString());
        assertEquals("0.39", ErpAmounts.amountTextOf(new BigDecimal("0.39")));
        assertEquals("数量去掉尾随 0", "1.5", ErpAmounts.textOf(new BigDecimal("1.500")));
        assertEquals("0", ErpAmounts.textOf(null));
    }

    @Test
    public void 行项金额可现算也可写回()
    {
        Item item = new Item("1", "0.125");
        assertEquals("未写回时按 qty×unitPrice 现算", "0.13", item.amountOrCompute().toPlainString());
        assertEquals("applyLineAmount 写回的值一致", "0.13",
                ErpAmounts.applyLineAmount(item).toPlainString());
        assertEquals("0.13", item.getAmount().toPlainString());
    }
}
