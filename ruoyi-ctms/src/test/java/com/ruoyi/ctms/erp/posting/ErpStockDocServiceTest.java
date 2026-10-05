package com.ruoyi.ctms.erp.posting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockInServiceImpl;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockOutServiceImpl;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 入库单 / 出库单服务的口径单测（2.0 B4 任务 5.1~5.6）。 </p>
 *
 * <p> 断言逐条对应 tasks.md §5 的五条场景： </p>
 * <ul>
 *   <li> 5.1：行项未填仓库时回落表头仓库；两者都空时<b>审核</b>被拒并指明行号（草稿可暂缺）； </li>
 *   <li> 5.2：结存 10 + 入库 5 → 15 且新增一条 {@code +5} 流水；重复审核后仍 15 且流水条数不变； </li>
 *   <li> 5.3：结存 3 + 出库 5 被拒（结存 3、状态待审核、无流水）；参数开启后审核成功且结存 -2； </li>
 *   <li> 5.4：入库 5 后反审核 → 两条流水（{@code +5} / {@code -5}）、结存回 10、过账标记清除； </li>
 *   <li> 5.6：每次审核/反审核新增变更历史（{@code status} 与 {@code posted} 各一条；
 *        操作日志由 Controller 的 {@code @Log} 承担，属接口级断言，见 notes/03-posting.md）。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStockDocServiceTest
{
    private static final String PRODUCT = "P-VALVE";

    private static final String WAREHOUSE = "W-1";

    private static final String UOM = "UOM-1";

    private final StubStockMapper stockMapper = new StubStockMapper();

    private final StubLedgerMapper ledgerMapper = new StubLedgerMapper();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubDocObjectAccess docObjectAccess = new StubDocObjectAccess();

    private final StubMasterLookup masterLookup = new StubMasterLookup();

    private final StubStockInMapper stockInMapper = new StubStockInMapper();

    private final StubStockInItemMapper stockInItemMapper = new StubStockInItemMapper();

    private final StubStockOutMapper stockOutMapper = new StubStockOutMapper();

    private final StubStockOutItemMapper stockOutItemMapper = new StubStockOutItemMapper();

    private final ErpStockJournalServiceTest.TestJournalService journal =
            new ErpStockJournalServiceTest.TestJournalService();

    private final TestStockInService inService = new TestStockInService();

    private final TestStockOutService outService = new TestStockOutService();

    @Before
    public void setUp()
    {
        // 主数据：一个单位（3 位小数）、一个仓库、一个物料
        masterLookup.addUom(UOM, "个", Integer.valueOf(3));
        masterLookup.addWarehouse(WAREHOUSE, "一号仓");
        masterLookup.addProduct(PRODUCT, "LS-0001", "螺丝", UOM);

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
    }

    /**
     * 测试用入库单服务：固定单号/操作人（生产实现分别走平台编号服务与登录上下文）。
     */
    static class TestStockInService extends ErpStockInServiceImpl
    {
        private int seq = 100;

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

    /**
     * 测试用出库单服务（同入库单服务）。
     */
    static class TestStockOutService extends ErpStockOutServiceImpl
    {
        private int seq = 200;

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

    /* ==================== 5.1 仓库回落 ==================== */

    @Test
    public void rowWarehouseFallsBackToHeaderAndSnapshotIsWritten()
    {
        ErpStockIn doc = newIn("5", "2", WAREHOUSE);

        ErpStockIn saved = inService.insertStockIn(doc);

        ErpStockInItem item = saved.getItems().get(0);
        assertEquals(WAREHOUSE, item.getWarehouseId());
        assertEquals("一号仓", item.getWarehouseName());
        // 快照来自 base 的守卫（物料/单位），历史展示不依赖档案现值
        assertEquals("LS-0001", item.getProductCode());
        assertEquals("螺丝", item.getProductName());
        assertEquals("个", item.getUomName());
        assertEquals(Integer.valueOf(3), item.getUomDecimals());
        assertEquals(0, new BigDecimal("10.00").compareTo(item.getAmount()));
        // 表头：草稿、未过账、单号与创建快照齐备
        assertEquals(ErpDocStatus.DRAFT, saved.getStatus());
        assertEquals("0", saved.getPosted());
        assertTrue(saved.getDocNo().startsWith("IN"));
        assertEquals("U-1", saved.getCreateId());
        assertEquals("D-1", saved.getDeptId());
        assertEquals(0, new BigDecimal("10.00").compareTo(saved.getTotalAmount()));
    }

    @Test
    public void approveGuardStillRejectsMissingWarehouseAsDefensivePath()
    {
        // t33 口径更正：缺表头仓库的手动创建现在会在**创建期**被应用层拒绝（见下面的用例），
        // 因此"审核期报 行 N：缺少仓库"在真库路径上不可达 —— 但它是**防御守卫**，必须保留。
        // 这里直接把"表头与行项都没有仓库"的单据塞进内存桩（模拟历史脏数据/其它写入路径），
        // 证明审核期守卫仍然有效。
        ErpStockIn seeded = newIn("5", "2", null);
        seeded.setId("IN-DEF-00000000000000000000000001");
        seeded.setDocNo(seeded.getId());
        seeded.setStatus(ErpDocStatus.SUBMITTED);
        seeded.setPosted("0");
        seeded.setDelFlag("0");
        seeded.setCreateId("U-1");
        seeded.setDeptId("D-1");
        stockInMapper.seed(seeded);
        ErpStockInItem item = new ErpStockInItem();
        item.setId("IN-DEF-ITEM-1");
        item.setDocId(seeded.getId());
        item.setSeq(Integer.valueOf(1));
        item.setProductId(PRODUCT);
        item.setQty(new BigDecimal("5"));
        item.setUnitPrice(new BigDecimal("2"));
        item.setWarehouseId(null);
        stockInItemMapper.items.put(seeded.getId(), new ArrayList<>(Collections.singletonList(item)));

        try
        {
            inService.approveStockIn(seeded.getId(), ErpPostingOptions.defaults());
            fail("防御守卫：表头与行项都没有仓库时审核应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("缺少仓库"));
        }
        ErpStockIn reloaded = inService.selectStockInDetail(seeded.getId());
        assertEquals(ErpDocStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("0", reloaded.getPosted());
        assertEquals(0, ledgerMapper.all.size());
        assertNull(stockMapper.qty(PRODUCT, WAREHOUSE));
    }

    /* ==================== t33：表头仓库必填（应用层，别让用户看到 1048） ==================== */

    @Test
    public void createWithoutHeaderWarehouseIsRejectedWithReadableMessage()
    {
        // 入库单：不传表头仓库 → 可读中文业务异常（而不是数据库原生 1048/FK）
        try
        {
            inService.insertStockIn(newIn("5", "2", null));
            fail("缺表头仓库的入库单创建应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("表头仓库不能为空：请选择入库仓库", e.getMessage());
            assertNull("业务拒绝不应带数据库错误码", e.getCode());
        }
        assertEquals("被拒时不得落库（表头）", 0, stockInMapper.docs.size());
        assertEquals("被拒时不得落库（行项）", 0, stockInItemMapper.items.size());
        assertEquals("被拒时不得写流水", 0, ledgerMapper.all.size());

        // 出库单：同构
        try
        {
            outService.insertStockOut(newOut("5", "2", null));
            fail("缺表头仓库的出库单创建应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("表头仓库不能为空：请选择出库仓库", e.getMessage());
        }
        assertEquals(0, stockOutMapper.docs.size());
        assertEquals(0, stockOutItemMapper.items.size());

        // 生成路径（盘点差异生成的调整单）同样给出可读文案，而不是 1048
        try
        {
            inService.insertGeneratedStockIn(newInFor(PRODUCT, "2", "3", null));
            fail("缺表头仓库的生成单也应被可读拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("表头仓库不能为空"));
        }
        assertEquals(0, stockInMapper.docs.size());
    }

    @Test
    public void editWithoutHeaderWarehouseIsRejectedAndKeepsOriginalWarehouse()
    {
        ErpStockIn draft = newIn("5", "2", WAREHOUSE);
        inService.insertStockIn(draft);
        assertEquals(WAREHOUSE, inService.selectStockInDetail(draft.getId()).getWarehouseId());

        ErpStockIn edit = newIn("5", "2", null);
        edit.setId(draft.getId());
        try
        {
            inService.updateStockIn(edit);
            fail("编辑时清空表头仓库应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("表头仓库不能为空：请选择入库仓库", e.getMessage());
        }
        assertEquals("被拒的编辑不得改动表头仓库", WAREHOUSE,
                inService.selectStockInDetail(draft.getId()).getWarehouseId());
    }

    @Test
    public void createWithOnlyWarehouseIdFillsNameSnapshotFromArchive()
    {
        // t33-r1：调用方**只给 warehouseId**（不传显示名）⇒ 创建必须成功，
        // 且 warehouse_name 由服务端从仓库档案回填（与行项快照同一个主数据真源）。
        ErpStockIn inbound = newIn("5", "2", WAREHOUSE);
        inbound.setWarehouseName(null);
        ErpStockIn savedIn = inService.insertStockIn(inbound);
        assertEquals("入库单：名称快照应从档案回填", "一号仓", savedIn.getWarehouseName());
        assertEquals("一号仓", inService.selectStockInDetail(savedIn.getId()).getWarehouseName());

        ErpStockOut outbound = newOut("5", "2", WAREHOUSE);
        outbound.setWarehouseName(null);
        ErpStockOut savedOut = outService.insertStockOut(outbound);
        assertEquals("出库单：名称快照应从档案回填", "一号仓", savedOut.getWarehouseName());

        // 档案是快照真源：调用方给了**过期**显示名时也以档案为准（仓库改名后新单按新名）
        ErpStockIn stale = newIn("5", "2", WAREHOUSE);
        stale.setWarehouseName("旧仓库名");
        assertEquals("档案名优先于调用方传入的过期名字", "一号仓",
                inService.insertStockIn(stale).getWarehouseName());
    }

    @Test
    public void headerWarehouseNameIsNeverNullEvenWhenArchiveIsUnavailable()
    {
        // 装配边界：主数据档案查不到该仓库且调用方也没给名字时，服务端回落 DDL 默认值（空串），
        // **绝不写 null**（否则真库报 `Column 'warehouse_name' cannot be null` —— t34 抓到的那个 500）。
        // 真实运行期 ErpMasterLookupImpl 查的是真表，正常路径总能拿到档案名；伪造 id 由 FK 挡下。
        ErpStockIn doc = newIn("5", "2", "W-GHOST");
        doc.setWarehouseName(null);

        ErpStockIn saved = inService.insertStockIn(doc);

        assertNotNull("warehouse_name 不得为 null", saved.getWarehouseName());
        assertEquals("档案不可用时回落空串（DDL DEFAULT ''）", "", saved.getWarehouseName());
        assertEquals("W-GHOST", saved.getWarehouseId());
    }

    /* ==================== 5.2 审核即过账 + 幂等 ==================== */

    @Test
    public void approvePostsStockAndRepeatedApproveIsIdempotent()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("10"));
        ErpStockIn doc = newIn("5", "2", WAREHOUSE);
        inService.insertStockIn(doc);
        inService.submitStockIn(doc.getId());

        ErpStockIn approved = inService.approveStockIn(doc.getId(), ErpPostingOptions.defaults());

        assertEquals(ErpDocStatus.APPROVED, approved.getStatus());
        assertEquals("1", approved.getPosted());
        assertEquals(0, new BigDecimal("15").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("5").compareTo(ledgerMapper.all.get(0).getQtyChange()));
        assertEquals("stock_in", ledgerMapper.all.get(0).getDocType());
        assertEquals(0, new BigDecimal("15").compareTo(ledgerMapper.all.get(0).getQtyAfter()));

        // 重复审核：幂等（不产生流水、不改结存、不报错）
        ErpStockIn again = inService.approveStockIn(doc.getId(), ErpPostingOptions.defaults());
        assertEquals(ErpDocStatus.APPROVED, again.getStatus());
        assertEquals(0, new BigDecimal("15").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
    }

    /* ==================== 5.3 负库存 ==================== */

    @Test
    public void outboundBeyondStockKeepsStatusStockAndLedger()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("3"));
        ErpStockOut doc = newOut("5", "2", WAREHOUSE);
        outService.insertStockOut(doc);
        outService.submitStockOut(doc.getId());

        try
        {
            outService.approveStockOut(doc.getId(), ErpPostingOptions.defaults());
            fail("结存不足时应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("可用量 3"));
            assertTrue(e.getMessage(), e.getMessage().contains("需求量 5"));
        }
        ErpStockOut reloaded = outService.selectStockOutDetail(doc.getId());
        assertEquals(ErpDocStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("0", reloaded.getPosted());
        assertEquals(0, new BigDecimal("3").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(0, ledgerMapper.all.size());

        // 参数开启后同一单据过账成功，结存 -2
        stockMapper.releaseAllHeldLocks();
        journal.negativeParam = "true";
        outService.approveStockOut(doc.getId(), ErpPostingOptions.defaults());
        ErpStockOut posted = outService.selectStockOutDetail(doc.getId());
        assertEquals(ErpDocStatus.APPROVED, posted.getStatus());
        assertEquals("1", posted.getPosted());
        assertEquals(0, new BigDecimal("-2").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        assertEquals("销售出库", ledgerMapper.all.get(0).getBizType());
        assertEquals(0, new BigDecimal("-5").compareTo(ledgerMapper.all.get(0).getQtyChange()));
    }

    /* ==================== 5.4 反审核红冲 + 5.6 留痕 ==================== */

    @Test
    public void unapproveReversesLedgerClearsPostedAndWritesHistory()
    {
        stockMapper.seed(PRODUCT, WAREHOUSE, new BigDecimal("10"));
        ErpStockIn doc = newIn("5", "2", WAREHOUSE);
        inService.insertStockIn(doc);
        inService.submitStockIn(doc.getId());
        inService.approveStockIn(doc.getId(), ErpPostingOptions.defaults());

        String id = doc.getId();
        // 留痕逐条可数：新建（status）、提交（status）、审核（status + posted）
        assertEquals(3, changeLogMapper.countOfField(id, "status"));
        assertEquals(1, changeLogMapper.countOfField(id, "posted"));
        assertEquals(0, new BigDecimal("15").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));

        inService.unapproveStockIn(id, "录错数量");

        ErpStockIn reloaded = inService.selectStockInDetail(id);
        assertEquals(ErpDocStatus.SUBMITTED, reloaded.getStatus());
        assertEquals("0", reloaded.getPosted());
        assertNull(reloaded.getApprovedBy());
        assertEquals(0, new BigDecimal("10").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals(2, ledgerMapper.all.size());
        assertEquals(0, new BigDecimal("-5").compareTo(ledgerMapper.all.get(1).getQtyChange()));
        assertEquals("红冲-采购入库", ledgerMapper.all.get(1).getBizType());
        // 反审核留痕：状态 + 过账标记各一条
        assertEquals(4, changeLogMapper.countOfField(id, "status"));
        assertEquals(2, changeLogMapper.countOfField(id, "posted"));
    }

    /* ==================== 金额口径（走 base 的"先舍入再汇总"） ==================== */

    @Test
    public void totalAmountRoundsEachLineBeforeSumming()
    {
        ErpStockIn doc = new ErpStockIn();
        doc.setWarehouseId(WAREHOUSE);
        doc.setWarehouseName("一号仓");
        doc.setInType("采购入库");
        List<ErpStockInItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            ErpStockInItem item = new ErpStockInItem();
            item.setProductId(PRODUCT);
            item.setQty(new BigDecimal("1"));
            item.setUnitPrice(new BigDecimal("0.125"));
            items.add(item);
        }
        doc.setItems(items);

        ErpStockIn saved = inService.insertStockIn(doc);

        for (ErpStockInItem item : saved.getItems())
        {
            assertEquals(0, new BigDecimal("0.13").compareTo(item.getAmount()));
        }
        // 0.13 × 3 = 0.39（不是先求和再舍入的 0.38）
        assertEquals(0, new BigDecimal("0.39").compareTo(saved.getTotalAmount()));
    }

    /* ==================== 公共层守卫的接线 ==================== */

    @Test
    public void quantityBeyondUomDecimalsIsRejectedWithRowNo()
    {
        StubMasterLookup zeroDecimals = new StubMasterLookup();
        zeroDecimals.addUom("UOM-0", "件", Integer.valueOf(0));
        zeroDecimals.addWarehouse(WAREHOUSE, "一号仓");
        zeroDecimals.addProduct(PRODUCT, "LS-0001", "螺丝", "UOM-0");
        inject(inService, "masterLookup", zeroDecimals);

        ErpStockIn doc = newIn("1.5", "2", WAREHOUSE);
        try
        {
            inService.insertStockIn(doc);
            fail("单位 0 位小数时录入 1.5 应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("数量的最小单位是 0 位小数"));
        }
    }

    @Test
    public void outOfScopeDetailIsRejectedWith403()
    {
        ErpStockIn doc = newIn("5", "2", WAREHOUSE);
        inService.insertStockIn(doc);

        docObjectAccess.forbidden = true;
        try
        {
            inService.selectStockInDetail(doc.getId());
            fail("范围外应按 403 拒绝");
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

    /* ==================== 已审核生成单（盘点 §6.4 的入口） ==================== */

    @Test
    public void generatedInboundIsApprovedAndPostsOnDemand()
    {
        ErpStockIn doc = newIn("2", "3", WAREHOUSE);
        doc.setSourceDocType("stock_take");
        doc.setSourceDocNo("ST202610000001");

        ErpStockIn generated = inService.insertGeneratedStockIn(doc);
        assertEquals(ErpDocStatus.APPROVED, generated.getStatus());
        assertEquals("0", generated.getPosted());
        assertNull(stockMapper.qty(PRODUCT, WAREHOUSE));

        inService.postApprovedStockIn(generated.getId(), ErpPostingOptions.defaults());
        ErpStockIn posted = inService.selectStockInDetail(generated.getId());
        assertEquals("1", posted.getPosted());
        assertEquals(0, new BigDecimal("2").compareTo(stockMapper.qty(PRODUCT, WAREHOUSE)));
        assertEquals("stock_in", ledgerMapper.all.get(0).getDocType());
        // 幂等：再调一次不追加流水
        inService.postApprovedStockIn(generated.getId(), ErpPostingOptions.defaults());
        assertEquals(1, ledgerMapper.all.size());
        assertFalse(posted.getItems().isEmpty());
        assertNotNull(posted.getDocNo());
    }

    /* ==================== t23：生成路径豁免"停用物料"，用户路径不放宽 ==================== */

    /** 停用物料：结存建立之后被停用，盘点差异生成的调整单仍必须能过账（t23 的缺陷场景）。 */
    private static final String DISABLED_PRODUCT = "P-DISABLED";

    @Test
    public void generatedInboundAllowsDisabledProductAndPostsAndChangesStock()
    {
        // 结存 8（该物料已被停用）→ 盘盈 2 → 生成盘盈入库单并过账 → 结存 10
        masterLookup.addDisabledProduct(DISABLED_PRODUCT, "LS-9999", "停用螺丝", UOM);
        stockMapper.seed(DISABLED_PRODUCT, WAREHOUSE, new BigDecimal("8"));
        ErpStockIn doc = newInFor(DISABLED_PRODUCT, "2", "3", WAREHOUSE);
        doc.setInType("盘盈入库");
        doc.setSourceDocType("stock_take");
        doc.setSourceDocNo("ST202610000009");

        ErpStockIn generated = inService.insertGeneratedStockIn(doc);

        assertEquals("生成单状态直接已审核", ErpDocStatus.APPROVED, generated.getStatus());
        assertEquals("生成单未过账（过账由 postApproved* 显式触发）", "0", generated.getPosted());
        // 豁免只针对"停用"这一条：快照与其它校验照旧生效
        assertEquals("停用物料的快照仍要写入", "停用螺丝", generated.getItems().get(0).getProductName());
        assertEquals("LS-9999", generated.getItems().get(0).getProductCode());
        assertEquals("个", generated.getItems().get(0).getUomName());

        inService.postApprovedStockIn(generated.getId(), ErpPostingOptions.defaults());
        ErpStockIn posted = inService.selectStockInDetail(generated.getId());
        assertEquals("1", posted.getPosted());
        assertEquals("结存按差异增加", 0, new BigDecimal("10").compareTo(stockMapper.qty(DISABLED_PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        assertEquals("盘盈入库", ledgerMapper.all.get(0).getBizType());
        assertEquals(0, new BigDecimal("2").compareTo(ledgerMapper.all.get(0).getQtyChange()));
        assertEquals(0, new BigDecimal("10").compareTo(ledgerMapper.all.get(0).getQtyAfter()));
    }

    @Test
    public void generatedOutboundAllowsDisabledProductEvenWhenAvailableIsInsufficient()
    {
        // AC-75 的盘亏同族路径：账面 3 实盘 1 → 差异 -2，而此刻可用量只有 1（不足 2）
        masterLookup.addDisabledProduct(DISABLED_PRODUCT, "LS-9999", "停用螺丝", UOM);
        stockMapper.seed(DISABLED_PRODUCT, WAREHOUSE, new BigDecimal("1"));
        ErpStockOut doc = newOutFor(DISABLED_PRODUCT, "2", "0", WAREHOUSE);
        doc.setOutType("盘亏出库");
        doc.setSourceDocType("stock_take");
        doc.setSourceDocNo("ST202610000010");

        ErpStockOut generated = outService.insertGeneratedStockOut(doc);
        assertEquals(ErpDocStatus.APPROVED, generated.getStatus());
        assertEquals("0", generated.getPosted());

        // 盘亏单过账时显式豁免负库存（stockops 的既有口径）→ 即使可用量不足也必须成功
        outService.postApprovedStockOut(generated.getId(), ErpPostingOptions.skipNegativeCheck());

        assertEquals("盘亏后结存按差异扣减（允许转负）", 0,
                new BigDecimal("-1").compareTo(stockMapper.qty(DISABLED_PRODUCT, WAREHOUSE)));
        assertEquals(1, ledgerMapper.all.size());
        assertEquals("盘亏出库", ledgerMapper.all.get(0).getBizType());
        assertEquals(0, new BigDecimal("-2").compareTo(ledgerMapper.all.get(0).getQtyChange()));
    }

    @Test
    public void userCreateStillRejectsDisabledProductWithRowNo()
    {
        masterLookup.addDisabledProduct(DISABLED_PRODUCT, "LS-9999", "停用螺丝", UOM);

        // 新建（草稿）路径：仍然拒绝，且文案带行号 —— 证明豁免只作用于生成路径
        try
        {
            inService.insertStockIn(newInFor(DISABLED_PRODUCT, "2", "3", WAREHOUSE));
            fail("用户新建入库单使用停用物料应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
            assertTrue(e.getMessage(), e.getMessage().contains("不能用于新单据"));
        }
        try
        {
            outService.insertStockOut(newOutFor(DISABLED_PRODUCT, "2", "3", WAREHOUSE));
            fail("用户新建出库单使用停用物料应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
        }
        assertEquals("被拒时不得落库", 0, stockInMapper.docs.size());
        assertEquals("被拒时不得落库", 0, stockOutMapper.docs.size());
    }

    @Test
    public void userEditStillRejectsDisabledProductWithRowNo()
    {
        masterLookup.addDisabledProduct(DISABLED_PRODUCT, "LS-9999", "停用螺丝", UOM);
        ErpStockIn draft = newIn("5", "2", WAREHOUSE);
        inService.insertStockIn(draft);
        assertEquals("启用物料建草稿成功", 1, stockInMapper.docs.size());

        // 编辑：把行项换成停用物料 → 拒绝，且原行项不得被改写
        ErpStockIn edit = newInFor(DISABLED_PRODUCT, "5", "2", WAREHOUSE);
        edit.setId(draft.getId());
        try
        {
            inService.updateStockIn(edit);
            fail("用户编辑入库单改用停用物料应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
        }
        assertEquals("被拒的编辑不得改动行项", PRODUCT,
                inService.selectStockInDetail(draft.getId()).getItems().get(0).getProductId());
    }

    @Test
    public void generatedPathStillEnforcesMissingProductMissingUomAndQtyPrecision()
    {
        // ① 物料不存在：生成路径同样拒绝（豁免只针对"停用"）
        try
        {
            inService.insertGeneratedStockIn(newInFor("P-NOT-EXIST", "2", "3", WAREHOUSE));
            fail("生成路径遇到不存在的物料应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("物料档案不存在"));
        }
        // ② 单位不存在：生成路径同样拒绝
        masterLookup.addProductWithMissingUom("P-NO-UOM", "LS-8888", "无单位物料", "UOM-MISSING");
        try
        {
            inService.insertGeneratedStockIn(newInFor("P-NO-UOM", "2", "3", WAREHOUSE));
            fail("生成路径遇到不存在的单位应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("计量单位不存在"));
        }
        // ③ 停用单位：生成路径只豁免物料，不豁免单位
        StubMasterLookup disabledUomLookup = new StubMasterLookup();
        disabledUomLookup.addDisabledUom("UOM-OFF", "停用单位", Integer.valueOf(3));
        disabledUomLookup.addWarehouse(WAREHOUSE, "一号仓");
        disabledUomLookup.addProduct("P-OFF-UOM", "LS-7777", "单位停用物料", "UOM-OFF");
        inject(inService, "masterLookup", disabledUomLookup);
        try
        {
            inService.insertGeneratedStockIn(newInFor("P-OFF-UOM", "2", "3", WAREHOUSE));
            fail("生成路径遇到停用单位应被拒绝（豁免只针对物料停用）");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
        }
        // ④ 数量精度：生成路径同样拒绝
        StubMasterLookup zeroDecimals = new StubMasterLookup();
        zeroDecimals.addUom("UOM-0", "件", Integer.valueOf(0));
        zeroDecimals.addWarehouse(WAREHOUSE, "一号仓");
        zeroDecimals.addDisabledProduct("P-ZERO-UOM", "LS-6666", "零位物料", "UOM-0");
        inject(inService, "masterLookup", zeroDecimals);
        try
        {
            inService.insertGeneratedStockIn(newInFor("P-ZERO-UOM", "1.5", "3", WAREHOUSE));
            fail("生成路径的数量精度超限应被拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("行 1："));
            assertTrue(e.getMessage(), e.getMessage().contains("数量的最小单位是 0 位小数"));
        }
        assertEquals("全部被拒时不得落库", 0, stockInMapper.docs.size());
        assertEquals("全部被拒时不得写流水", 0, ledgerMapper.all.size());
    }

    /* ==================== 夹具 ==================== */

    private ErpStockIn newIn(String qty, String price, String headerWarehouse)
    {
        return newInFor(PRODUCT, qty, price, headerWarehouse);
    }

    private ErpStockOut newOut(String qty, String price, String headerWarehouse)
    {
        return newOutFor(PRODUCT, qty, price, headerWarehouse);
    }

    /**
     * 指定物料构造一张入库单（t23：停用物料等场景需要换物料）。
     *
     * @param productId       物料ID
     * @param qty             数量
     * @param price           单价
     * @param headerWarehouse 表头仓库
     * @return 入库单
     */
    private ErpStockIn newInFor(String productId, String qty, String price, String headerWarehouse)
    {
        ErpStockIn doc = new ErpStockIn();
        doc.setWarehouseId(headerWarehouse);
        doc.setWarehouseName(headerWarehouse == null ? null : "一号仓");
        doc.setInType("采购入库");
        doc.setDocDate(new Date());
        ErpStockInItem item = new ErpStockInItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        return doc;
    }

    /**
     * 指定物料构造一张出库单。
     *
     * @param productId       物料ID
     * @param qty             数量
     * @param price           单价
     * @param headerWarehouse 表头仓库
     * @return 出库单
     */
    private ErpStockOut newOutFor(String productId, String qty, String price, String headerWarehouse)
    {
        ErpStockOut doc = new ErpStockOut();
        doc.setWarehouseId(headerWarehouse);
        doc.setWarehouseName(headerWarehouse == null ? null : "一号仓");
        doc.setOutType("销售出库");
        doc.setDocDate(new Date());
        ErpStockOutItem item = new ErpStockOutItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        return doc;
    }
}
