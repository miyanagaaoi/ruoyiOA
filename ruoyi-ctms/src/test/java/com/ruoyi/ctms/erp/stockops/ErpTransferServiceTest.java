package com.ruoyi.ctms.erp.stockops;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.support.CtmsAttachmentObjectTypes;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStockOpsMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStocktakeItemMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubStocktakeMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubTransferItemMapper;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsTestSupport.StubTransferMapper;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransferItem;
import com.ruoyi.ctms.erp.stockops.service.impl.ErpTransferServiceImpl;

import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubChangeLogMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubDocObjectAccess;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubMasterLookup;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper;
import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>调拨单服务的口径单测</b>（2.0 B4 任务 6.1/6.2）。 </p>
 *
 * <p> 断言逐条对应 tasks.md §6.1/§6.2： </p>
 * <ul>
 *   <li> 两仓相同被拒且<b>不写任何流水</b>（保存与审核两处）； </li>
 *   <li> 一张调拨单产生<b>两条同单据号</b>流水（调出仓负、调入仓正），<b>单价为 0</b>； </li>
 *   <li> 调出仓可用量不足时<b>第一行也未写入</b>（引擎两阶段）； </li>
 *   <li> 行项不使用行级仓库（请求里塞了也落 null）； </li>
 *   <li> 反审核红冲后两仓结存归位。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpTransferServiceTest
{
    private static final String PRODUCT = "P-VALVE";

    private static final String UOM = "UOM-1";

    private static final String FROM = "W-1";

    private static final String TO = "W-2";

    private final StubStockMapper stockMapper = new StubStockMapper();

    private final StubLedgerMapper ledgerMapper = new StubLedgerMapper();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubDocObjectAccess docObjectAccess = new StubDocObjectAccess();

    private final StubMasterLookup masterLookup = new StubMasterLookup();

    private final StubTransferMapper transferMapper = new StubTransferMapper();

    private final StubTransferItemMapper transferItemMapper = new StubTransferItemMapper();

    private final ErpStockOpsTestSupport.TestJournalService journal =
            new ErpStockOpsTestSupport.TestJournalService();

    private final TestTransferService service = new TestTransferService();

    @Before
    public void setUp()
    {
        masterLookup.addUom(UOM, "个", Integer.valueOf(3));
        masterLookup.addWarehouse(FROM, "一号仓");
        masterLookup.addWarehouse(TO, "二号仓");
        masterLookup.addProduct(PRODUCT, "LS-0001", "螺丝", UOM);

        inject(journal, "stockMapper", stockMapper);
        inject(journal, "stockLedgerMapper", ledgerMapper);

        inject(service, "transferMapper", transferMapper);
        inject(service, "transferItemMapper", transferItemMapper);
        inject(service, "changeLogMapper", changeLogMapper);
        inject(service, "journalService", journal);
        inject(service, "docObjectAccess", docObjectAccess);
        inject(service, "masterLookup", masterLookup);
    }

    /**
     * 测试用调拨单服务：固定单号与操作人（生产实现走平台编号服务与登录上下文）。
     */
    static class TestTransferService extends ErpTransferServiceImpl
    {
        private int seq = 100;

        @Override
        protected String nextDocNo(Date docDate)
        {
            return "DB202610" + (seq++);
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

    /* ==================== 6.1 两仓校验 ==================== */

    @Test
    public void sameWarehouseIsRejectedOnSaveAndNothingIsWritten()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = newTransfer(FROM, FROM, "4");

        try
        {
            service.insertTransfer(doc);
            fail("调出仓与调入仓相同时保存应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("调出仓库与调入仓库不能相同"));
        }
        assertEquals(0, transferMapper.docs.size());
        assertEquals(0, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, FROM)));
    }

    @Test
    public void missingWarehouseIsRejected()
    {
        ErpTransfer doc = newTransfer(FROM, null, "4");
        try
        {
            service.insertTransfer(doc);
            fail("缺调入仓应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("必须选择调入仓库"));
        }
    }

    @Test
    public void rowWarehouseIsClearedBecauseTransferUsesHeaderWarehouses()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = newTransfer(FROM, TO, "4");
        // 请求里刻意塞一个行级仓库：调拨不使用它，落库必须为 null
        doc.getItems().get(0).setWarehouseId("W-9");

        ErpTransfer saved = service.insertTransfer(doc);

        assertNull(saved.getItems().get(0).getWarehouseId());
        assertNull(saved.getItems().get(0).getWarehouseName());
        assertEquals("一号仓", saved.getFromWarehouseName());
        assertEquals("二号仓", saved.getToWarehouseName());
        assertEquals(ErpDocStatus.DRAFT, saved.getStatus());
        assertEquals("0", saved.getPosted());
        assertTrue(saved.getDocNo().startsWith("DB"));
    }

    /* ==================== 6.2 两阶段过账 + 两条流水 ==================== */

    @Test
    public void approveWritesTwoLedgerRowsWithSameDocNoAndZeroPrice()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = newTransfer(FROM, TO, "4");
        service.insertTransfer(doc);
        service.submitTransfer(doc.getId());

        ErpTransfer approved = service.approveTransfer(doc.getId());

        assertEquals(ErpDocStatus.APPROVED, approved.getStatus());
        assertEquals("1", approved.getPosted());
        assertEquals(0, new BigDecimal("6").compareTo(stockMapper.qty(PRODUCT, FROM)));
        assertEquals(0, new BigDecimal("4").compareTo(stockMapper.qty(PRODUCT, TO)));

        // 两条流水：同一单据号、方向相反、单价 0、业务类型分别为调拨出库/调拨入库
        assertEquals(2, ledgerMapper.all.size());
        ErpStockLedger out = ledgerMapper.all.get(0);
        ErpStockLedger in = ledgerMapper.all.get(1);
        assertEquals(doc.getDocNo(), out.getDocNo());
        assertEquals(doc.getDocNo(), in.getDocNo());
        assertEquals("stock_transfer", out.getDocType());
        assertEquals(ErpStockOpsRules.BIZ_TRANSFER_OUT, out.getBizType());
        assertEquals(ErpStockOpsRules.BIZ_TRANSFER_IN, in.getBizType());
        assertEquals(FROM, out.getWarehouseId());
        assertEquals(TO, in.getWarehouseId());
        assertEquals(0, new BigDecimal("-4").compareTo(out.getQtyChange()));
        assertEquals(0, new BigDecimal("4").compareTo(in.getQtyChange()));
        assertEquals(0, BigDecimal.ZERO.compareTo(out.getUnitPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(in.getUnitPrice()));

        // 幂等：重复审核不再产生流水
        service.approveTransfer(doc.getId());
        assertEquals(2, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("6").compareTo(stockMapper.qty(PRODUCT, FROM)));
    }

    @Test
    public void shortageOnLaterRowWritesNothingAtAll()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = new ErpTransfer();
        doc.setFromWarehouseId(FROM);
        doc.setToWarehouseId(TO);
        doc.setDocDate(new Date());
        List<ErpTransferItem> items = new ArrayList<>();
        items.add(item("3"));
        items.add(item("20"));
        doc.setItems(items);
        service.insertTransfer(doc);
        service.submitTransfer(doc.getId());

        try
        {
            service.approveTransfer(doc.getId());
            fail("调出仓可用量不足时应被拒绝");
        }
        catch (ServiceException e)
        {
            // 引擎按（物料, 仓库）分组后逐行累加：第 1 行 -3 扣完剩 7，第 2 行要 20 ⇒ 报到第 2 行
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 2："));
            assertTrue(e.getMessage(), e.getMessage().contains("需求量 20"));
        }
        // 第一行也没有写入（引擎两阶段：校验不通过整单不写）
        assertEquals(0, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, FROM)));
        assertNull(stockMapper.qty(PRODUCT, TO));
        ErpTransfer reloaded = service.selectTransferDetail(doc.getId());
        assertEquals(ErpDocStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("0", reloaded.getPosted());
    }

    @Test
    public void unapproveReversesBothRowsAndRestoresStock()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = newTransfer(FROM, TO, "4");
        service.insertTransfer(doc);
        service.submitTransfer(doc.getId());
        service.approveTransfer(doc.getId());

        service.unapproveTransfer(doc.getId(), "调错仓库");

        ErpTransfer reloaded = service.selectTransferDetail(doc.getId());
        assertEquals(ErpDocStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("0", reloaded.getPosted());
        assertNull(reloaded.getApprovedBy());
        assertEquals(4, ledgerMapper.all.size());
        assertEquals("红冲-调拨出库", ledgerMapper.all.get(2).getBizType());
        assertEquals("红冲-调拨入库", ledgerMapper.all.get(3).getBizType());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, FROM)));
        assertEquals(0, BigDecimal.ZERO.compareTo(stockMapper.qty(PRODUCT, TO)));
    }

    /* ==================== 6.6 附件对象登记与业务对象鉴权 ==================== */

    @Test
    public void transferAndStocktakeObjectTypesAreRegisteredForAttachments()
    {
        // 6.6：调拨单的附件对象类型由 T1 的附件注册表登记（本组不新增附件端点，只断言注册表）
        assertTrue("stock_transfer 未登记进附件对象类型注册表",
                CtmsAttachmentObjectTypes.registered().contains(ErpDocType.STOCK_TRANSFER.getCode()));
        assertTrue("stock_take 未登记进附件对象类型注册表",
                CtmsAttachmentObjectTypes.registered().contains(ErpDocType.STOCK_TAKE.getCode()));
    }

    @Test
    public void outOfScopeTransferDetailIsRejectedWith403()
    {
        stockMapper.seed(PRODUCT, FROM, new BigDecimal("10"));
        ErpTransfer doc = newTransfer(FROM, TO, "4");
        service.insertTransfer(doc);

        docObjectAccess.forbidden = true;
        try
        {
            service.selectTransferDetail(doc.getId());
            fail("范围外读详情应按 403 拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("无权访问"));
        }
        finally
        {
            docObjectAccess.forbidden = false;
        }
    }

    /* ==================== 夹具 ==================== */

    private ErpTransferItem item(String qty)
    {
        ErpTransferItem item = new ErpTransferItem();
        item.setProductId(PRODUCT);
        item.setQty(new BigDecimal(qty));
        return item;
    }

    private ErpTransfer newTransfer(String from, String to, String qty)
    {
        ErpTransfer doc = new ErpTransfer();
        doc.setFromWarehouseId(from);
        doc.setToWarehouseId(to);
        doc.setDocDate(new Date());
        doc.setItems(new ArrayList<>(Collections.singletonList(item(qty))));
        return doc;
    }
}
