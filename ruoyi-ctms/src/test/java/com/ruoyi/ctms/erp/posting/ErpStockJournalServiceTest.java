package com.ruoyi.ctms.erp.posting;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockJournalServiceImpl;
import com.ruoyi.ctms.erp.posting.support.ErpPostingLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;

import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link ErpStockJournalServiceImpl} 的口径单测（2.0 B4 任务 5.2/5.3/5.4/5.5）。 </p>
 *
 * <p> <b>断言与规格场景一一对应</b>： </p>
 * <ul>
 *   <li> 5.2：结存 10 + 入库 5 → 15，且新增一条 {@code +5} 流水（含变动后结存快照）； </li>
 *   <li> 5.3：结存 3 + 出库 5 被拒（可用量/需求量在文案里），结存仍 3、无流水；
 *        参数开启后同一单据成功且结存 -2； </li>
 *   <li> 5.4：入库 5 后红冲 → 流水两条（{@code +5} / {@code -5}）、后者业务类型带"红冲-"前缀、
 *        原流水内容不变、结存回 10；红冲允许结存转负；重复红冲幂等； </li>
 *   <li> 5.5：并发两单各 30 打结存 100 → 40（不是 70）且等于"初值 + 流水累计"；
 *        其中一张红冲 → 70 且仍等于"初值 + 流水累计"。 </li>
 * </ul>
 *
 * <p> <b>并发用例用的是多线程 + 内存桩</b>（不是真实库）：桩把
 * {@code selectStockForUpdate} → {@code updateStockQty} 之间的区间当作行锁持有区间
 * （每个（物料, 仓库）一把 {@link java.util.concurrent.locks.ReentrantLock}），
 * 因此"丢掉一次更新"会真实地表现为 70。真实 MySQL 的行锁与事务回滚由 T10/T13 的
 * 接口级并发用例覆盖（见 {@code notes/03-posting.md}）。 </p>
 *
 * @author 二开
 */
public class ErpStockJournalServiceTest
{
    private static final String PRODUCT = "P-VALVE";

    private static final String WAREHOUSE = "W-1";

    private final StubStockMapper stockMapper = new StubStockMapper();

    private final StubLedgerMapper ledgerMapper = new StubLedgerMapper();

    private final TestJournalService journal = new TestJournalService();

    private final TestJournalService journalService = journal;

    @Before
    public void setUp()
    {
        inject(journalService, "stockMapper", stockMapper);
        inject(journalService, "stockLedgerMapper", ledgerMapper);
    }

    /**
     * 测试用引擎：把"是否允许负库存"参数换成可写字段（生产实现在子类之外读 sys_config）。
     */
    static class TestJournalService extends ErpStockJournalServiceImpl
    {
        /** 参数值（默认关闭）。 */
        String negativeParam = "false";

        @Override
        protected String readNegativeStockParam()
        {
            return negativeParam;
        }
    }

    /* ==================== 5.2 审核即过账 ==================== */

    @Test
    public void inboundShouldIncreaseStockAndAppendOneLedgerRow()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("10"));

        ErpPostingOutcome outcome = journalService.post(inbound("D-IN-1", "5", "2"));

        assertEquals(0, new BigDecimal("15").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        ErpStockLedger ledger = ledgerMapper.all.get(0);
        assertEquals("采购入库", ledger.getBizType());
        assertEquals("stock_in", ledger.getDocType());
        assertEquals("IN202610000001", ledger.getDocNo());
        assertEquals(0, new BigDecimal("5").compareTo(ledger.getQtyChange()));
        assertEquals(0, new BigDecimal("15").compareTo(ledger.getQtyAfter()));
        assertEquals(0, new BigDecimal("2").compareTo(ledger.getUnitPrice()));
        assertEquals("U-1", ledger.getCreateId());
        assertEquals("tester", ledger.getCreateBy());
        // 返回值里的变动后结存与流水快照一致
        assertEquals(0, ledger.getQtyAfter().compareTo(outcome.getLines().get(0).getQtyAfter()));
        assertEquals(1, outcome.ledgerCount());
    }

    @Test
    public void missingWarehouseShouldBeRejectedWithRowNo()
    {
        ErpPostingRequest request = inbound("D-IN-2", "5", "2");
        request.getLines().get(0).setWarehouseId(null);
        try
        {
            journalService.post(request);
            fail("缺少仓库时应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("缺少仓库"));
        }
        assertEquals(0, ledgerMapper.all.size());
    }

    /* ==================== 5.3 负库存校验 ==================== */

    @Test
    public void outboundBeyondStockShouldBeRejectedAndLeaveNothing()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("3"));

        try
        {
            journalService.post(outbound("D-OUT-1", "5", "2"));
            fail("结存不足时应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("可用量 3"));
            assertTrue(e.getMessage(), e.getMessage().contains("需求量 5"));
        }
        assertEquals(0, new BigDecimal("3").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(0, ledgerMapper.all.size());

        // 模拟事务回滚释放行锁（内存桩没有事务，被拒的路径会留下未释放的锁）
        stockMapper.releaseAllHeldLocks();
        assertEquals(false, journalService.negativeStockAllowed());

        // 参数开启后同一单据过账成功，结存转负
        journalService.negativeParam = "true";
        assertTrue(journalService.negativeStockAllowed());
        journalService.post(outbound("D-OUT-1", "5", "2"));
        assertEquals(0, new BigDecimal("-2").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("-5").compareTo(ledgerMapper.all.get(0).getQtyChange()));
    }

    @Test
    public void negativeStockParamShouldOnlyAcceptTruthyValues()
    {
        journalService.negativeParam = "Y";
        assertTrue(journalService.negativeStockAllowed());
        journalService.negativeParam = "1";
        assertTrue(journalService.negativeStockAllowed());
        journalService.negativeParam = "false";
        assertEquals(false, journalService.negativeStockAllowed());
        journalService.negativeParam = null;
        assertEquals(false, journalService.negativeStockAllowed());
    }

    /* ==================== 5.4 反审核红冲 ==================== */

    @Test
    public void reversalShouldAppendNegativeLedgerAndKeepOriginal()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("10"));
        journalService.post(inbound("D-IN-3", "5", "2"));
        assertEquals(0, new BigDecimal("15").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));

        journalService.reverse(ErpDocType.STOCK_IN, "D-IN-3", "录错数量", "U-2", "auditor");

        assertEquals(2, ledgerMapper.all.size());
        ErpStockLedger original = ledgerMapper.all.get(0);
        ErpStockLedger reversal = ledgerMapper.all.get(1);
        // 原流水保留可查、内容不变
        assertEquals(0, new BigDecimal("5").compareTo(original.getQtyChange()));
        assertEquals("采购入库", original.getBizType());
        assertEquals(0, new BigDecimal("15").compareTo(original.getQtyAfter()));
        // 红冲是负数追加，业务类型带前缀，备注记录反审核原因
        assertEquals(0, new BigDecimal("-5").compareTo(reversal.getQtyChange()));
        assertEquals("红冲-采购入库", reversal.getBizType());
        assertTrue(reversal.isReversal());
        assertEquals("采购入库", reversal.baseBizType());
        assertTrue(reversal.getRemark().contains("反审核红冲"));
        assertTrue(reversal.getRemark().contains("录错数量"));
        assertEquals("U-2", reversal.getCreateId());
        // 结存归位，并仍等于"初值 + 流水累计"
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertConsistent(10);

        // 重复红冲幂等：不再追加流水
        journalService.reverse(ErpDocType.STOCK_IN, "D-IN-3", "重复反审核", "U-2", "auditor");
        assertEquals(2, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
    }

    @Test
    public void reversalShouldBeAllowedToDriveStockNegative()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("0"));
        journalService.post(inbound("D-IN-4", "5", "2"));
        journalService.post(outbound("D-OUT-4", "5", "2"));
        assertEquals(0, new BigDecimal("0").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));

        // 那 5 个已被出库占用，再红冲原入库单：不受负库存拦截，结存允许为负
        journalService.reverse(ErpDocType.STOCK_IN, "D-IN-4", "退回", "U-2", "auditor");
        assertEquals(0, new BigDecimal("-5").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertConsistent(0);
    }

    @Test
    public void reversalWithoutOriginalLedgerShouldBeIdempotentNoop()
    {
        ErpPostingOutcome outcome = journalService.reverse(ErpDocType.STOCK_OUT, "D-NONE", "无流水", "U-1", "t");
        assertEquals(0, outcome.ledgerCount());
        assertEquals(0, ledgerMapper.all.size());
    }

    /* ==================== 5.5 并发 ==================== */

    @Test
    public void concurrentOutboundShouldNotLoseUpdate() throws Exception
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("100"));
        BigDecimal seed = new BigDecimal("100");

        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Callable<Void> first = new Callable<Void>()
            {
                @Override
                public Void call()
                {
                    awaitQuietly(start);
                    journalService.post(outbound("D-C-1", "30", "2"));
                    return null;
                }
            };
            Callable<Void> second = new Callable<Void>()
            {
                @Override
                public Void call()
                {
                    awaitQuietly(start);
                    journalService.post(outbound("D-C-2", "30", "2"));
                    return null;
                }
            };
            Future<Void> one = pool.submit(first);
            Future<Void> two = pool.submit(second);
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }

        // 两次变动都落入结存：40，而不是只扣一次的 70
        assertEquals(0, new BigDecimal("40").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(2, ledgerMapper.all.size());
        // 结存 = 初值 + 流水累计和
        assertEquals(0, seed.add(ledgerMapper.sumQtyChangeByKey(PRODUCT, WAREHOUSE))
                .compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertConsistent(100);

        // 其中一张红冲 → 70，且仍等于"初值 + 流水累计和"
        journalService.reverse(ErpDocType.STOCK_OUT, "D-C-1", "并发用例红冲", "U-2", "auditor");
        assertEquals(0, new BigDecimal("70").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(3, ledgerMapper.all.size());
        assertEquals(0, seed.add(ledgerMapper.sumQtyChangeByKey(PRODUCT, WAREHOUSE))
                .compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertConsistent(100);
    }

    @Test
    public void concurrentFirstInboundShouldKeepSingleStockRow() throws Exception
    {
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<Void> one = pool.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                {
                    awaitQuietly(start);
                    journalService.post(inbound("D-F-1", "6", "1"));
                    return null;
                }
            });
            Future<Void> two = pool.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                {
                    awaitQuietly(start);
                    journalService.post(inbound("D-F-2", "4", "1"));
                    return null;
                }
            });
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }

        // 唯一约束保证只留一行结存，数量为两次变动之和
        assertEquals(1, stockMapper.countStock());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(2, ledgerMapper.all.size());
        assertConsistent(0);
    }

    /* ==================== 多行与整单一致性 ==================== */

    @Test
    public void shortageOnAnyRowShouldWriteNothingAtAll()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("10"));
        stockMapper.seed("P-FLANGE", WAREHOUSE, new BigDecimal("1"));

        ErpPostingRequest request = new ErpPostingRequest()
                .setDocType(ErpDocType.STOCK_OUT)
                .setDocId("D-MULTI")
                .setDocNo("OUT-1")
                .setBizType("销售出库")
                .setOperatorId("U-1")
                .setOperatorName("tester")
                .setLines(java.util.Arrays.asList(
                        ErpPostingLine.of(1, PRODUCT, WAREHOUSE, new BigDecimal("-2"), new BigDecimal("2"))
                                .setProductName("球阀"),
                        ErpPostingLine.of(2, "P-FLANGE", WAREHOUSE, new BigDecimal("-5"), new BigDecimal("2"))
                                .setProductName("法兰")));
        try
        {
            journalService.post(request);
            fail("第二行库存不足时应整单拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 2："));
        }
        // 第一行也没有写入（两阶段写法：校验阶段不写库）
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(0, new BigDecimal("1").compareTo(stockMapper.qty("P-FLANGE", WAREHOUSE)));
        assertEquals(0, ledgerMapper.all.size());
    }

    /* ==================== 夹具 ==================== */

    private ErpPostingRequest inbound(String docId, String qty, String price)
    {
        return new ErpPostingRequest()
                .setDocType(ErpDocType.STOCK_IN)
                .setDocId(docId)
                .setDocNo("IN202610000001")
                .setBizType("采购入库")
                .setDeptId("100")
                .setOperatorId("U-1")
                .setOperatorName("tester")
                .setLines(java.util.Collections.singletonList(
                        ErpPostingLine.of(1, PRODUCT, WAREHOUSE, new BigDecimal(qty), new BigDecimal(price))
                                .setProductName("球阀")
                                .setWarehouseName("一号仓")));
    }

    private ErpPostingRequest outbound(String docId, String qty, String price)
    {
        List<ErpPostingLine> lines = java.util.Collections.singletonList(
                ErpPostingLine.of(1, PRODUCT, WAREHOUSE, new BigDecimal(qty).negate(), new BigDecimal(price))
                        .setProductName("球阀")
                        .setWarehouseName("一号仓"));
        return new ErpPostingRequest()
                .setDocType(ErpDocType.STOCK_OUT)
                .setDocId(docId)
                .setDocNo("OUT202610000001")
                .setBizType("销售出库")
                .setDeptId("100")
                .setOperatorId("U-1")
                .setOperatorName("tester")
                .setLines(lines);
    }

    /**
     * 断言"结存 = 初值 + 流水累计和"（库存不变式在过账单测里的最小形态）。
     *
     * @param seed 初值
     */
    private void assertConsistent(long seed)
    {
        BigDecimal expected = new BigDecimal(seed).add(ledgerMapper.sumQtyChangeByKey(PRODUCT, WAREHOUSE));
        assertEquals(0, expected.compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
    }

    private static void awaitQuietly(CountDownLatch latch)
    {
        try
        {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
