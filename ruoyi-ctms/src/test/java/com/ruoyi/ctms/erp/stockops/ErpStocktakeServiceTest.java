package com.ruoyi.ctms.erp.stockops;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockInServiceImpl;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockOutServiceImpl;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStockOpsMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStocktakeItemMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStocktakeMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubTransferItemMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubTransferMapper;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;
import com.ruoyi.ctms.erp.stockops.service.impl.ErpStocktakeServiceImpl;

import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubChangeLogMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubDocObjectAccess;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubMasterLookup;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockInItemMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockInMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutItemMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>盘点单服务的口径单测</b>（2.0 B4 任务 6.3/6.4/6.5）。 </p>
 *
 * <p> 断言逐条对应 tasks.md §6.3~§6.5： </p>
 * <ul>
 *   <li> 全盘取该仓库结存非零物料；抽盘按商品类型<b>含整棵子树</b>； </li>
 *   <li> 账面数量生成时固化且只读（编辑传新账面被忽略）；差异 = 实盘 − 账面（3 位小数）； </li>
 *   <li> 实盘为负被拒； </li>
 *   <li> 盘盈：结存 10 → 12 且回填入库单号，生成单状态直接已审核并已过账； </li>
 *   <li> 盘亏：<b>即使可用量不足也过账成功</b>（就发生在"负库存参数仍是关闭"的前提下，
 *        证明豁免来自显式传参而非全局开关）； </li>
 *   <li> 无差异不生成任何单据； </li>
 *   <li> 反审核级联：生成单红冲并作废、结存回到审核前、盘点单回待审核；重复反审核不报错。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStocktakeServiceTest
{
    private static final String P1 = "P-1";

    private static final String P2 = "P-2";

    private static final String P3 = "P-3";

    private static final String UOM = "UOM-1";

    private static final String W = "W-1";

    private static final String TYPE_ROOT = "T-ROOT";

    private static final String TYPE_CHILD = "T-CHILD";

    private static final String TYPE_OTHER = "T-OTHER";

    private final StubStockMapper stockMapper = new StubStockMapper();

    private final StubLedgerMapper ledgerMapper = new StubLedgerMapper();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubDocObjectAccess docObjectAccess = new StubDocObjectAccess();

    private final StubMasterLookup masterLookup = new StubMasterLookup();

    private final StubStockInMapper stockInMapper = new StubStockInMapper();

    private final StubStockInItemMapper stockInItemMapper = new StubStockInItemMapper();

    private final StubStockOutMapper stockOutMapper = new StubStockOutMapper();

    private final StubStockOutItemMapper stockOutItemMapper = new StubStockOutItemMapper();

    private final StubStocktakeMapper stocktakeMapper = new StubStocktakeMapper();

    private final StubStocktakeItemMapper stocktakeItemMapper = new StubStocktakeItemMapper();

    private final StubStockOpsMapper stockOpsMapper = new StubStockOpsMapper();

    private final ErpStockOpsTestSupport.TestJournalService journal =
            new ErpStockOpsTestSupport.TestJournalService();

    private final TestStockInService inService = new TestStockInService();

    private final TestStockOutService outService = new TestStockOutService();

    private final TestStocktakeService service = new TestStocktakeService();

    @Before
    public void setUp()
    {
        masterLookup.addUom(UOM, "个", Integer.valueOf(3));
        masterLookup.addWarehouse(W, "一号仓");
        masterLookup.addProduct(P1, "LS-0001", "螺丝", UOM);
        masterLookup.addProduct(P2, "LS-0002", "螺母", UOM);
        masterLookup.addProduct(P3, "LS-0003", "垫片", UOM);

        inject(journal, "stockMapper", stockMapper);
        inject(journal, "stockLedgerMapper", ledgerMapper);

        inject(inService, "stockInMapper", stockInMapper);
        inject(inService, "stockInItemMapper", stockInItemMapper);
        inject(inService, "changeLogMapper", changeLogMapper);
        inject(inService, "journalService", journal);
        inject(inService, "docObjectAccess", docObjectAccess);
        inject(inService, "masterLookup", masterLookup);

        inject(outService, "stockOutMapper", stockOutMapper);
        inject(outService, "stockOutItemMapper", stockOutItemMapper);
        inject(outService, "changeLogMapper", changeLogMapper);
        inject(outService, "journalService", journal);
        inject(outService, "docObjectAccess", docObjectAccess);
        inject(outService, "masterLookup", masterLookup);

        inject(service, "stocktakeMapper", stocktakeMapper);
        inject(service, "stocktakeItemMapper", stocktakeItemMapper);
        inject(service, "changeLogMapper", changeLogMapper);
        inject(service, "stockMapper", stockMapper);
        inject(service, "stockOpsMapper", stockOpsMapper);
        inject(service, "stockInService", inService);
        inject(service, "stockOutService", outService);
        inject(service, "stockInMapper", stockInMapper);
        inject(service, "stockOutMapper", stockOutMapper);
        inject(service, "docObjectAccess", docObjectAccess);
        inject(service, "masterLookup", masterLookup);

        // 商品类型树：T-ROOT → T-CHILD（抽盘按类型必须展开整棵子树）
        stockOpsMapper.addSubtree(TYPE_ROOT, TYPE_ROOT, TYPE_CHILD);
        stockOpsMapper.addSubtree(TYPE_CHILD, TYPE_CHILD);
        stockOpsMapper.addProducts(TYPE_ROOT, P1);
        stockOpsMapper.addProducts(TYPE_CHILD, P2);
        stockOpsMapper.addProducts(TYPE_OTHER, P3);
    }

    /**
     * 测试用盘点单服务：固定单号与操作人。
     */
    static class TestStocktakeService extends ErpStocktakeServiceImpl
    {
        private int seq = 300;

        @Override
        protected String nextDocNo(Date docDate)
        {
            return "ST202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return "U-1";
        }

        @Override
        protected String currentDeptId()
        {
            return "D-1";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }
    }

    /** 测试用入库单服务（生成盘盈单用）。 */
    static class TestStockInService extends ErpStockInServiceImpl
    {
        private int seq = 400;

        @Override
        protected String nextDocNo(Date docDate)
        {
            return "IN202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return "U-1";
        }

        @Override
        protected String currentDeptId()
        {
            return "D-1";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }
    }

    /** 测试用出库单服务（生成盘亏单用）。 */
    static class TestStockOutService extends ErpStockOutServiceImpl
    {
        private int seq = 500;

        @Override
        protected String nextDocNo(Date docDate)
        {
            return "OUT202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return "U-1";
        }

        @Override
        protected String currentDeptId()
        {
            return "D-1";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }
    }

    /* ==================== 6.3 行项生成与实盘录入 ==================== */

    @Test
    public void fullCountTakesOnlyNonZeroStockOfTheWarehouse()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        stockMapper.seed(P2, W, BigDecimal.ZERO);
        stockMapper.seed(P3, W, new BigDecimal("5"));

        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));

        assertEquals(ErpDocStatus.DRAFT, doc.getStatus());
        assertTrue(doc.getDocNo().startsWith("ST"));
        assertEquals("一号仓", doc.getWarehouseName());
        assertEquals(2, doc.getItems().size());
        ErpStocktakeItem row1 = itemOf(doc, P1);
        ErpStocktakeItem row3 = itemOf(doc, P3);
        assertNotNull(row1);
        assertNotNull(row3);
        // 账面 = 生成时结存；实盘默认等于账面（避免"未录入即全亏"）
        assertEquals(0, new BigDecimal("10").compareTo(row1.getBookQty()));
        assertEquals(0, new BigDecimal("10").compareTo(row1.getActualQty()));
        assertEquals(0, new BigDecimal("5.000").compareTo(row3.getBookQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row1.getDiffQty()));
        assertNull(itemOf(doc, P2));
        // 快照来自公共层守卫
        assertEquals("LS-0001", row1.getProductCode());
        assertEquals("螺丝", row1.getProductName());
        assertEquals("个", row1.getUomName());
    }

    @Test
    public void partialCountByProductTypeExpandsWholeSubtree()
    {
        stockMapper.seed(P1, W, new BigDecimal("3"));
        stockMapper.seed(P2, W, new BigDecimal("4"));
        stockMapper.seed(P3, W, new BigDecimal("9"));
        ErpStocktake request = newTake(ErpStockOpsRules.TAKE_TYPE_PARTIAL, null);
        request.setProductTypeIds(new ArrayList<>(Arrays.asList(TYPE_ROOT)));

        ErpStocktake doc = service.insertStocktake(request);

        assertEquals(2, doc.getItems().size());
        assertEquals(0, new BigDecimal("3").compareTo(itemOf(doc, P1).getBookQty()));
        assertEquals(0, new BigDecimal("4").compareTo(itemOf(doc, P2).getBookQty()));
        assertNull(itemOf(doc, P3));
        // 整棵子树被展开：只向 DB 问了根类型一次（递归 CTE 在库内展开），
        // 子类型（T-CHILD）下的 P2 出现在行项里就是展开生效的直接证据
        assertTrue(stockOpsMapper.queriedTypes.contains(TYPE_ROOT));
        assertEquals(1, stockOpsMapper.queriedTypes.size());
    }

    @Test
    public void partialCountWithoutScopeIsRejected()
    {
        stockMapper.seed(P1, W, new BigDecimal("3"));
        try
        {
            service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_PARTIAL, null));
            fail("抽盘未指定物料或商品类型应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("抽盘必须指定物料或商品类型"));
        }
    }

    @Test
    public void bookQtyIsImmutableOnEditAndDiffIsActualMinusBook()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);

        // 请求里刻意篡改账面（999）——服务端必须忽略，仍按固化值 10 算差异
        ErpStocktakeItem change = new ErpStocktakeItem();
        change.setId(stored.getId());
        change.setBookQty(new BigDecimal("999"));
        change.setActualQty(new BigDecimal("7"));
        change.setDiffReason("缺损");
        ErpStocktake saved = service.saveCount(doc.getId(), new ArrayList<>(Arrays.asList(change)));

        ErpStocktakeItem row = itemOf(saved, P1);
        assertEquals(0, new BigDecimal("10").compareTo(row.getBookQty()));
        assertEquals(0, new BigDecimal("7").compareTo(row.getActualQty()));
        // 差异 = 实盘 − 账面 = -3（3 位小数）
        assertEquals(0, new BigDecimal("-3.000").compareTo(row.getDiffQty()));
        assertEquals("缺损", row.getDiffReason());
    }

    @Test
    public void negativeActualQtyIsRejectedWithRowNo()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);

        ErpStocktakeItem change = new ErpStocktakeItem();
        change.setId(stored.getId());
        change.setActualQty(new BigDecimal("-1"));
        try
        {
            service.saveCount(doc.getId(), new ArrayList<>(Arrays.asList(change)));
            fail("实盘为负应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("实盘数量不得为负数"));
        }
    }

    /* ==================== 6.4 审核自动生成调整单并过账 ==================== */

    @Test
    public void approvePostsGainAndFillsGeneratedInNo()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);
        service.saveCount(doc.getId(), countOf(stored, "12", "漏记入库"));
        service.submitStocktake(doc.getId());

        ErpStocktake approved = service.approveStocktake(doc.getId());

        assertEquals(ErpDocStatus.APPROVED, approved.getStatus());
        assertEquals("1", approved.getPosted());
        assertNotNull(approved.getGeneratedInId());
        assertNotNull(approved.getGeneratedInNo());
        assertNull(approved.getGeneratedOutId());
        // 结存 10 → 12
        assertEquals(0, new BigDecimal("12").compareTo(stockMapper.qty(P1, W)));
        // 生成单：状态直接已审核、已过账、金额 0、数量为差异绝对值、记录来源盘点单号
        ErpStockIn generated = inService.selectStockInDetail(approved.getGeneratedInId());
        assertEquals(ErpDocStatus.APPROVED, generated.getStatus());
        assertEquals("1", generated.getPosted());
        assertEquals(ErpStockOpsRules.BIZ_TAKE_GAIN, generated.getInType());
        assertEquals(0, BigDecimal.ZERO.compareTo(generated.getTotalAmount()));
        assertEquals(approved.getDocNo(), generated.getSourceDocNo());
        assertEquals("stock_take", generated.getSourceDocType());
        assertEquals(1, generated.getItems().size());
        assertEquals(0, new BigDecimal("2").compareTo(generated.getItems().get(0).getQty()));

        // 幂等：重复审核不重复生成、不重复过账
        service.approveStocktake(doc.getId());
        assertEquals(1, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("12").compareTo(stockMapper.qty(P1, W)));
    }

    @Test
    public void approvePostsLossEvenWhenStockIsInsufficient()
    {
        stockMapper.seed(P1, W, new BigDecimal("2"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);
        // 生成后再被别的单据领走 2：现在结存 0，而本次盘亏要扣 2 —— 正常出库会被负库存拦住
        stockMapper.seed(P1, W, BigDecimal.ZERO);
        service.saveCount(doc.getId(), countOf(stored, "0", "实物已丢失"));
        service.submitStocktake(doc.getId());

        assertEquals("false", journal.negativeParam);
        ErpStocktake approved = service.approveStocktake(doc.getId());

        assertEquals(ErpDocStatus.APPROVED, approved.getStatus());
        assertNotNull(approved.getGeneratedOutId());
        assertNotNull(approved.getGeneratedOutNo());
        // 盘亏豁免生效：结存 0 → -2（若按全局参数拦截，这里会抛"可用量不足"）
        assertEquals(0, new BigDecimal("-2").compareTo(stockMapper.qty(P1, W)));
        ErpStockOut generated = outService.selectStockOutDetail(approved.getGeneratedOutId());
        assertEquals(ErpDocStatus.APPROVED, generated.getStatus());
        assertEquals("1", generated.getPosted());
        assertEquals(ErpStockOpsRules.BIZ_TAKE_LOSS, generated.getOutType());
        assertEquals(0, new BigDecimal("2").compareTo(generated.getItems().get(0).getQty()));
    }

    @Test
    public void noDifferenceGeneratesNoDocumentAtAll()
    {
        stockMapper.seed(P1, W, new BigDecimal("5"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        service.submitStocktake(doc.getId());

        ErpStocktake approved = service.approveStocktake(doc.getId());

        assertEquals(ErpDocStatus.APPROVED, approved.getStatus());
        assertEquals("0", approved.getPosted());
        assertNull(approved.getGeneratedInId());
        assertNull(approved.getGeneratedOutId());
        assertEquals(0, stockInMapper.docs.size());
        assertEquals(0, stockOutMapper.docs.size());
        assertEquals(0, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("5").compareTo(stockMapper.qty(P1, W)));
    }

    /* ==================== 6.5 反审核级联 ==================== */

    @Test
    public void unapproveCascadeVoidsGeneratedDocAndRestoresStock()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);
        service.saveCount(doc.getId(), countOf(stored, "12", null));
        service.submitStocktake(doc.getId());
        ErpStocktake approved = service.approveStocktake(doc.getId());
        String generatedInId = approved.getGeneratedInId();
        assertEquals(0, new BigDecimal("12").compareTo(stockMapper.qty(P1, W)));

        ErpStocktake back = service.unapproveStocktake(doc.getId(), "实盘录错");

        // 盘点单回到待审核、过账标记清零
        assertEquals(ErpDocStatus.SUBMITTED, back.getStatus());
        assertEquals("0", back.getPosted());
        assertNull(back.getApprovedBy());
        // 生成单已作废、已红冲，结存回到审核前
        ErpStockIn generated = inService.selectStockInDetail(generatedInId);
        assertEquals(ErpDocStatus.VOIDED, generated.getStatus());
        assertEquals("0", generated.getPosted());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(P1, W)));
        assertEquals(2, ledgerMapper.all.size());
        assertEquals("红冲-盘盈入库", ledgerMapper.all.get(1).getBizType());
    }

    @Test
    public void repeatedUnapproveIsIdempotentAndDoesNotThrow()
    {
        stockMapper.seed(P1, W, new BigDecimal("10"));
        ErpStocktake doc = service.insertStocktake(newTake(ErpStockOpsRules.TAKE_TYPE_FULL, null));
        ErpStocktakeItem stored = itemOf(doc, P1);
        service.saveCount(doc.getId(), countOf(stored, "12", null));
        service.submitStocktake(doc.getId());
        ErpStocktake approved = service.approveStocktake(doc.getId());
        String generatedInId = approved.getGeneratedInId();
        service.unapproveStocktake(doc.getId(), "实盘录错");

        // 重复反审核：不报错、状态与结存不变、生成单仍只有一次作废
        ErpStocktake again = service.unapproveStocktake(doc.getId(), "实盘录错");

        assertEquals(ErpDocStatus.SUBMITTED, again.getStatus());
        assertEquals("0", again.getPosted());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(P1, W)));
        assertEquals(ErpDocStatus.VOIDED, inService.selectStockInDetail(generatedInId).getStatus());
        assertEquals(2, ledgerMapper.all.size());

        // 再次审核：重新生成一张新的盘盈单（旧生成单号只作为历史线索）
        service.approveStocktake(doc.getId());
        ErpStocktake reApproved = service.selectStocktakeDetail(doc.getId());
        assertEquals(ErpDocStatus.APPROVED, reApproved.getStatus());
        assertNotNull(reApproved.getGeneratedInNo());
        assertEquals(0, new BigDecimal("12").compareTo(stockMapper.qty(P1, W)));
    }

    /* ==================== 夹具 ==================== */

    private ErpStocktake newTake(String takeType, String scopeNote)
    {
        ErpStocktake doc = new ErpStocktake();
        doc.setWarehouseId(W);
        doc.setTakeType(takeType);
        doc.setScopeNote(scopeNote);
        doc.setDocDate(new Date());
        return doc;
    }

    private List<ErpStocktakeItem> countOf(ErpStocktakeItem stored, String actualQty, String reason)
    {
        ErpStocktakeItem change = new ErpStocktakeItem();
        change.setId(stored.getId());
        change.setActualQty(new BigDecimal(actualQty));
        change.setDiffReason(reason);
        return new ArrayList<>(Arrays.asList(change));
    }

    private ErpStocktakeItem itemOf(ErpStocktake doc, String productId)
    {
        if (doc == null || doc.getItems() == null)
        {
            return null;
        }
        for (ErpStocktakeItem item : doc.getItems())
        {
            if (productId.equals(item.getProductId()))
            {
                return item;
            }
        }
        return null;
    }
}
