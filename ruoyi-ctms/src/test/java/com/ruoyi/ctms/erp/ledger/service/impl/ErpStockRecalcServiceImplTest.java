package com.ruoyi.ctms.erp.ledger.service.impl;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.ctms.erp.ledger.ErpLedgerTestSupport;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockRecalcResult;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>结存一致性校验 / 修复单测</b>（B4 任务 7.4；AC-76）。 </p>
 *
 * <p> 四条验收断言逐条对应：一致时不一致条数 0；人为改动结存后（不修复）报告 1 条不一致
 * 且给出"结存 vs 流水累计"、结存未被改动；开启修复后结存被改写且复检一致；
 * 重复调用不改变一致数据。 </p>
 *
 * <p> 结存桩复用 T3 的 {@code StubStockMapper}（它模拟行锁与唯一键），
 * 这里加一层计数子类用于断言"没开修复就不许写库"。 </p>
 *
 * @author 二开
 */
public class ErpStockRecalcServiceImplTest
{
    /**
     * 会数写入次数的结存桩（默认实现来自 T3 的桩）。
     */
    private static class CountingStockMapper extends ErpPostingTestSupport.StubStockMapper
    {
        /** {@code updateStockQty} 被调用次数。 */
        private int updates;

        @Override
        public int updateStockQty(String id, BigDecimal qty)
        {
            updates++;
            return super.updateStockQty(id, qty);
        }
    }

    /** 结存桩。 */
    private CountingStockMapper stockMapper;

    /** 流水桩。 */
    private ErpLedgerTestSupport.StubLedgerQueryMapper ledgerMapper;

    /** 被测服务。 */
    private ErpStockRecalcServiceImpl service;

    /**
     * 组装被测服务与两个桩。
     */
    @Before
    public void setUp()
    {
        stockMapper = new CountingStockMapper();
        ledgerMapper = new ErpLedgerTestSupport.StubLedgerQueryMapper();
        service = new ErpStockRecalcServiceImpl();
        ErpPostingTestSupport.inject(service, "stockMapper", stockMapper);
        ErpPostingTestSupport.inject(service, "stockLedgerQueryMapper", ledgerMapper);
    }

    /**
     * 造一个时间（当前减若干小时）。
     *
     * @param hoursAgo 小时数
     * @return 时间
     */
    private static Date hoursAgo(int hoursAgo)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, -hoursAgo);
        return calendar.getTime();
    }

    @Test
    public void 一致时不一致条数为零且不写库()
    {
        stockMapper.seed("p1", "w1", new BigDecimal("10"));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(1)));

        ErpStockRecalcResult result = service.recalc(false);

        assertEquals(1, result.getCheckedRows());
        assertTrue(result.isConsistent());
        assertEquals(0, result.getInconsistentCount());
        assertEquals(0, result.getRepairedCount());
        assertEquals("只读校验不得写结存", 0, stockMapper.updates);
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty("p1", "w1")));
    }

    @Test
    public void 人为改动结存后报告一条不一致且给出结存与流水累计()
    {
        stockMapper.seed("p1", "w1", new BigDecimal("12")); // 人为改成 12，流水累计仍是 10
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(1)));

        ErpStockRecalcResult result = service.recalc(false);

        assertFalse(result.isConsistent());
        assertEquals(1, result.getInconsistentCount());
        assertEquals(1, result.getMismatches().size());

        ErpStockRecalcResult.Mismatch mismatch = result.getMismatches().get(0);
        assertEquals("p1", mismatch.getProductId());
        assertEquals("w1", mismatch.getWarehouseId());
        assertEquals(0, new BigDecimal("12").compareTo(mismatch.getStockQty()));
        assertEquals(0, new BigDecimal("10").compareTo(mismatch.getLedgerQty()));
        assertEquals("差值 = 结存 − 流水累计", 0, new BigDecimal("2").compareTo(mismatch.getDiff()));

        assertEquals("不修复时结存不得被改动", 0, stockMapper.updates);
        assertEquals(0, new BigDecimal("12").compareTo(stockMapper.qty("p1", "w1")));
    }

    @Test
    public void 开启修复后结存被改写且复检一致()
    {
        stockMapper.seed("p1", "w1", new BigDecimal("12"));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(1)));

        ErpStockRecalcResult repaired = service.recalc(true);

        assertEquals(1, repaired.getRepairedCount());
        assertEquals(1, repaired.getInconsistentCount());
        assertTrue("本次仍然报出发现的不一致", !repaired.isConsistent());
        assertEquals(1, stockMapper.updates);
        assertEquals("结存被改写为流水累计", 0, new BigDecimal("10").compareTo(stockMapper.qty("p1", "w1")));

        ErpStockRecalcResult recheck = service.recalc(false);
        assertTrue("复检必须一致", recheck.isConsistent());
        assertEquals(0, recheck.getInconsistentCount());
        assertEquals("复检不得再写库", 1, stockMapper.updates);
    }

    @Test
    public void 重复调用不改变一致数据()
    {
        stockMapper.seed("p1", "w1", new BigDecimal("10"));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(1)));

        service.recalc(false);
        service.recalc(true);
        ErpStockRecalcResult third = service.recalc(false);

        assertTrue(third.isConsistent());
        assertEquals("一致的数据行永不被改写", 0, stockMapper.updates);
    }

    @Test
    public void 红冲流水参与不变式()
    {
        // 入库 10 后被红冲 -10 ⇒ 流水累计 0；结存也应是 0
        stockMapper.seed("p1", "w1", new BigDecimal("0"));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(2)));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L2", "p1", "w1", "红冲-采购入库", "-10", "2", hoursAgo(1)));

        assertTrue("红冲必须计入累计，否则结存会被误判", service.recalc(false).isConsistent());

        // 反过来：只把红冲漏计（累计 10）就会把 0 判成不一致 —— 这里用"结存 10"表达同一反例
        stockMapper.seed("p2", "w1", new BigDecimal("10"));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L3", "p2", "w1", "采购入库", "10", "2", hoursAgo(2)));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L4", "p2", "w1", "红冲-采购入库", "-10", "2", hoursAgo(1)));

        ErpStockRecalcResult result = service.recalc(false);
        assertEquals(2, result.getCheckedRows());
        assertEquals("只有 p2 不一致（结存 10 ≠ 累计 0）", 1, result.getInconsistentCount());
        assertEquals("p2", result.getMismatches().get(0).getProductId());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getMismatches().get(0).getLedgerQty()));
    }

    @Test
    public void 空库时被检查零行且判定一致()
    {
        ErpStockRecalcResult result = service.recalc(false);
        assertEquals(0, result.getCheckedRows());
        assertTrue(result.isConsistent());
        assertEquals(0, result.getInconsistentCount());
        assertEquals(0, stockMapper.updates);
    }

    @Test
    public void 结存数量为空按零处理()
    {
        stockMapper.seed("p1", "w1", null);
        ErpStockRecalcResult result = service.recalc(false);
        assertTrue("null 视为 0，与空流水累计一致", result.isConsistent());
    }
}
