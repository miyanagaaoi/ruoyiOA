package com.ruoyi.ctms.erp.procurement;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocNoGenerator;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubChangeLogMapper;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubDocObjectAccess;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubMasterLookup;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockInItemMapper;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockInMapper;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockInServiceImpl;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockJournalServiceImpl;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushInResultVo;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushResultVo;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestMapper;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseOrderServiceImpl;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchasePushServiceImpl;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseRequestServiceImpl;
import com.ruoyi.ctms.erp.procurement.support.ErpPurchaseReceiptListener;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.service.ICtmsPartnerService;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;

import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>采购线（下）单测</b>：采购单 → 入库单下推（tasks §3.5）与采购线端到端链路（§3.7）。 </p>
 *
 * <p> <b>为什么这条用例必须把 T3 的真实服务拉进来</b>：§3.5 的核心断言是
 * 「<b>过账时</b>才累加已入库量 / 红冲时回退」，而"过账"这件事由 T3 的过账引擎实现。
 * 只测采购侧的拼接（例如自己 mock 一个"过账回调"）会漏掉最关键的接线错误 ——
 * 监听器有没有被注册、{@code srcItemId} 有没有被带到流水行上、红冲行有没有来源标识。
 * 因此本类装配的是：<b>真实</b>的 {@link ErpStockInServiceImpl} +
 * <b>真实</b>的 {@link ErpStockJournalServiceImpl} + <b>真实</b>的
 * {@link ErpPurchaseReceiptListener}，只有 Mapper 与主数据是内存桩。 </p>
 *
 * <p> <b>桩的复用</b>：过账侧的桩（结存/流水/入库单 Mapper、主数据、变更历史、对象访问）
 * 直接复用 T3 公开的 {@code ErpPostingTestSupport}（<b>不复刻</b>）；
 * 采购侧四个 Mapper 的桩是本文件内的紧凑实现（与 t4a 单测的桩同源，但那份是本组私有的
 * 测试夹具，为避免大改已绿用例而单独保留）。 </p>
 *
 * @author 二开
 */
public class ErpPurchasePushInTest
{
    /** 物料（单位小数位 3）。 */
    private static final String PRODUCT = "P-VALVE";

    /** 第二个物料（端到端用例的两行）。 */
    private static final String PRODUCT2 = "P-FLANGE";

    /** 计量单位。 */
    private static final String UOM = "UOM-1";

    /** 收货仓库。 */
    private static final String WAREHOUSE = "W-1";

    /** 启用中的供应商。 */
    private static final String SUPPLIER = "S-001";

    /* ==================== 过账侧桩（复用 T3 的公开桩） ==================== */

    private final StubStockMapper stockMapper = new StubStockMapper();

    private final StubLedgerMapper ledgerMapper = new StubLedgerMapper();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubDocObjectAccess docObjectAccess = new StubDocObjectAccess();

    private final StubMasterLookup masterLookup = new StubMasterLookup();

    private final StubStockInMapper stockInMapper = new StubStockInMapper();

    private final StubStockInItemMapper stockInItemMapper = new StubStockInItemMapper();

    /** 过账引擎（真实实现，只把负库存参数固定为"不允许"）。 */
    private final TestJournalService journal = new TestJournalService();

    /** 入库单服务（真实实现）。 */
    private final TestStockInService stockInService = new TestStockInService();

    /* ==================== 采购侧桩与服务 ==================== */

    private final PurStub pur = new PurStub();

    private final StubPartnerService partnerService = new StubPartnerService();

    private final StubContractService contractService = new StubContractService();

    private final StubCodeGenService codeGenService = new StubCodeGenService();

    private final TestRequestService requestService = new TestRequestService();

    private final TestOrderService orderService = new TestOrderService();

    private final ErpPurchasePushServiceImpl pushService = new ErpPurchasePushServiceImpl();

    private final ErpPurchaseReceiptListener listener = new ErpPurchaseReceiptListener();

    @Before
    public void setUp()
    {
        masterLookup.addUom(UOM, "件", Integer.valueOf(3));
        masterLookup.addWarehouse(WAREHOUSE, "一号仓");
        masterLookup.addProduct(PRODUCT, "WL-0001", "球阀", UOM);
        masterLookup.addProduct(PRODUCT2, "WL-0002", "法兰", UOM);
        partnerService.suppliers.put(SUPPLIER, supplier(SUPPLIER, "云羲数字科技"));

        // 过账引擎：Mapper + 监听器（监听器就是被验证的接线本身）
        inject(journal, "stockMapper", stockMapper);
        inject(journal, "stockLedgerMapper", ledgerMapper);
        inject(journal, "postingListeners", Collections.singletonList(listener));

        // 入库单服务（T3）：结存/流水由引擎写
        inject(stockInService, "stockInMapper", stockInMapper);
        inject(stockInService, "stockInItemMapper", stockInItemMapper);
        inject(stockInService, "changeLogMapper", changeLogMapper);
        inject(stockInService, "journalService", journal);
        inject(stockInService, "docObjectAccess", docObjectAccess);
        inject(stockInService, "masterLookup", masterLookup);

        // 采购申请单服务
        inject(requestService, "requestMapper", pur.requestMapper);
        inject(requestService, "itemMapper", pur.requestItemMapper);
        inject(requestService, "orderMapper", pur.orderMapper);
        inject(requestService, "changeLogMapper", changeLogMapper);
        inject(requestService, "masterLookup", masterLookup);
        inject(requestService, "partnerService", partnerService);
        inject(requestService, "contractService", contractService);
        inject(requestService, "erpDocNoGenerator", new ErpDocNoGenerator(codeGenService));

        // 采购单服务
        inject(orderService, "orderMapper", pur.orderMapper);
        inject(orderService, "itemMapper", pur.orderItemMapper);
        inject(orderService, "changeLogMapper", changeLogMapper);
        inject(orderService, "masterLookup", masterLookup);
        inject(orderService, "partnerService", partnerService);
        inject(orderService, "contractService", contractService);
        inject(orderService, "erpDocNoGenerator", new ErpDocNoGenerator(codeGenService));

        // 下推服务（申请→采购单 与 采购单→入库单）
        inject(pushService, "requestService", requestService);
        inject(pushService, "orderService", orderService);
        inject(pushService, "stockInService", stockInService);
        inject(pushService, "masterLookup", masterLookup);

        // 监听器（过账回写已入库量）
        inject(listener, "orderService", orderService);
        inject(listener, "orderItemMapper", pur.orderItemMapper);
        inject(listener, "stockInService", stockInService);
    }

    /* ==================== §3.5 断言 ①：未过账不回写 ==================== */

    @Test
    public void 下推生成草稿入库单且未过账不回写已入库量()
    {
        ErpPurchaseOrder order = approvedOrder("10", WAREHOUSE);
        ErpPurchaseOrderItem orderLine = firstOrderItem(order.getId());

        ErpPushInResultVo result = pushService.pushToStockIn(order.getId(), null, null, null);

        ErpStockIn stockIn = result.getStockIn();
        assertEquals("生成的是草稿", ErpDocStatus.DRAFT, stockIn.getStatus());
        assertNotNull("单号由平台编号服务生成", stockIn.getDocNo());
        assertEquals("来源单据类型", "purchase_order", stockIn.getSourceDocType());
        assertEquals("来源单号", order.getDocNo(), stockIn.getSourceDocNo());
        assertEquals("来源单据ID", order.getId(), stockIn.getSourceDocId());
        assertEquals("带过收货仓库", WAREHOUSE, stockIn.getWarehouseId());
        assertEquals("带过收货仓库名快照", "一号仓", stockIn.getWarehouseName());
        assertEquals("默认入库类型", "采购入库", stockIn.getInType());
        assertEquals("带过供应商快照", "云羲数字科技", stockIn.getSupplierName());
        assertEquals("本次下推量 = 全量 10", 0, result.getPushedQty().compareTo(new BigDecimal("10")));
        assertEquals("入库单行数量", 0,
                stockIn.getItems().get(0).getQty().compareTo(new BigDecimal("10")));
        assertEquals("入库单行写来源行标识（过账回写的定位依据）",
                orderLine.getId(), stockIn.getItems().get(0).getSrcItemId());

        // ★ 断言 ①：下推本身**不**回写已入库量（只有过账才回写）
        assertEquals("未过账时 received_qty 仍为 0", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(BigDecimal.ZERO));
        assertEquals("结存仍为 0（未过账不过账）", 0,
                stockQty(PRODUCT, WAREHOUSE).compareTo(BigDecimal.ZERO));
        assertEquals("流水条数为 0", 0, ledgerMapper.countLedger());
    }

    /* ==================== §3.5 断言 ②：过账后回写 ==================== */

    @Test
    public void 入库单过账后累加已入库量且重复审核幂等()
    {
        ErpPurchaseOrder order = approvedOrder("10", WAREHOUSE);
        ErpPushInResultVo pushed = pushService.pushToStockIn(order.getId(), null, null, null);
        String stockInId = pushed.getStockIn().getId();

        stockInService.submitStockIn(stockInId);
        stockInService.approveStockIn(stockInId, ErpPostingOptions.defaults());

        // ★ 断言 ②：过账后回写
        assertEquals("过账后已入库量 = 10", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(new BigDecimal("10")));
        assertEquals("结存 = 10", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(new BigDecimal("10")));
        assertEquals("流水 1 条", 1, ledgerMapper.countLedger());
        assertEquals("流水为入库方向 +10", 0,
                ledgerMapper.all.get(0).getQtyChange().compareTo(new BigDecimal("10")));
        assertEquals("流水业务类型取入库类型", "采购入库", ledgerMapper.all.get(0).getBizType());
        assertEquals("单据已过账", "1", stockInService.selectStockInDetail(stockInId).getPosted());
        assertEquals("该采购单剩余可入库量归零", 0,
                ErpPurRules.remainingReceiveSum(orderService.selectOrderDetail(order.getId()).getItems())
                        .compareTo(BigDecimal.ZERO));

        // 重复审核：不产生流水、不改结存、不重复回写
        stockInService.approveStockIn(stockInId, ErpPostingOptions.defaults());
        assertEquals("重复审核后已入库量仍为 10", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(new BigDecimal("10")));
        assertEquals("重复审核后流水仍 1 条", 1, ledgerMapper.countLedger());
        assertEquals("重复审核后结存仍 10", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(new BigDecimal("10")));
    }

    /* ==================== §3.5 断言 ③：红冲后回退且不为负 ==================== */

    @Test
    public void 反审核红冲后回退已入库量且不为负()
    {
        ErpPurchaseOrder order = approvedOrder("10", WAREHOUSE);
        String orderItemId = firstOrderItem(order.getId()).getId();
        ErpPushInResultVo pushed = pushService.pushToStockIn(order.getId(), null, null, null);
        String stockInId = pushed.getStockIn().getId();
        stockInService.submitStockIn(stockInId);
        stockInService.approveStockIn(stockInId, ErpPostingOptions.defaults());
        assertEquals("先确认已回写", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(new BigDecimal("10")));

        stockInService.unapproveStockIn(stockInId, "录错数量");

        // ★ 断言 ③：红冲后回退，且不小于 0
        assertEquals("红冲后已入库量回退到 0", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(BigDecimal.ZERO));
        assertEquals("结存回退到 0", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(BigDecimal.ZERO));
        assertEquals("流水 2 条（+10 与 -10）", 2, ledgerMapper.countLedger());
        assertEquals("单据回到待审核", ErpDocStatus.SUBMITTED,
                stockInService.selectStockInDetail(stockInId).getStatus());
        assertEquals("过账标记已清除", "0", stockInService.selectStockInDetail(stockInId).getPosted());

        // 额外兜底：已入库量已经是 0 时再回退一次，不能变负（库侧 GREATEST(0, ...)）
        orderService.applyReceivedQtyChange(order.getId(), orderItemId, new BigDecimal("-5"));
        assertEquals("回退不小于 0", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(BigDecimal.ZERO));
    }

    /* ==================== §3.5 断言 ④：缺仓库审核被拒 ==================== */

    @Test
    public void 入库单缺仓库时审核被拒且状态与结存均不变()
    {
        // 说明：真库上 t_ctms_stock_in.warehouse_id 是 NOT NULL + FK，因此"没有仓库的入库单"
        // 不可能由接口创建（下推路径见下一条用例会在下推时就拒绝）。本用例用内存桩
        // **直接种入**一张缺仓库的草稿单，专门验证"审核阶段仍有一道行级守卫"这件事。
        ErpStockIn doc = new ErpStockIn();
        doc.setId("SI-NO-WH");
        doc.setDocNo("IN202610000099");
        doc.setDocDate(new Date());
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setPosted("0");
        doc.setDelFlag("0");
        doc.setDeptId("103");
        doc.setCreateId("2");
        doc.setInType("采购入库");
        stockInMapper.seed(doc);
        ErpStockInItem item = new ErpStockInItem();
        item.setId("SI-NO-WH-I1");
        item.setDocId("SI-NO-WH");
        item.setSeq(Integer.valueOf(1));
        item.setProductId(PRODUCT);
        item.setQty(new BigDecimal("3"));
        item.setUnitPrice(new BigDecimal("2"));
        item.setAmount(new BigDecimal("6"));
        // 行项与表头都没有仓库
        stockInItemMapper.items.put("SI-NO-WH", new ArrayList<>(Arrays.asList(item)));
        stockInService.submitStockIn("SI-NO-WH");

        try
        {
            stockInService.approveStockIn("SI-NO-WH", ErpPostingOptions.defaults());
            fail("缺仓库必须被审核拒绝");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应以行号开头并含「缺少仓库」，实际=" + e.getMessage(),
                    e.getMessage().startsWith("行 1：") && e.getMessage().contains("缺少仓库"));
        }
        // ★ 断言 ④：状态与结存都不变、无流水
        assertEquals("状态仍为待审核", ErpDocStatus.SUBMITTED,
                stockInService.selectStockInDetail("SI-NO-WH").getStatus());
        assertNull("该物料没有任何结存行（被拒的过账不写结存）",
                stockMapper.selectStockByKey(PRODUCT, WAREHOUSE));
        assertEquals("无流水", 0, ledgerMapper.countLedger());
    }

    /* ==================== §3.5 边界：下推前置、超量与"没有仓库" ==================== */

    @Test
    public void 未审核的采购单不可下推入库单()
    {
        ErpPurchaseOrder draft = orderPayload("5", WAREHOUSE);
        orderService.insertOrder(draft);
        assertRejected(ErpPurRules.MSG_ORDER_NOT_PUSHABLE_IN, new Runnable()
        {
            @Override
            public void run()
            {
                pushService.pushToStockIn(draft.getId(), null, null, null);
            }
        });
    }

    @Test
    public void 采购单没有收货仓库时下推被拒()
    {
        // 真库上 warehouse_id 是 NOT NULL + FK：没有仓库的入库单插不进去，
        // 所以"缺仓库"必须在**下推**这一步就给出可读原因，而不是等数据库报 1048。
        ErpPurchaseOrder order = approvedOrder("5", null);
        assertRejected(ErpPurRules.MSG_RECEIPT_WAREHOUSE_REQUIRED, new Runnable()
        {
            @Override
            public void run()
            {
                pushService.pushToStockIn(order.getId(), null, null, null);
            }
        });
    }

    @Test
    public void 超量下推入库被拒且已入库量不变()
    {
        ErpPurchaseOrder order = approvedOrder("10", WAREHOUSE);
        List<ErpPushLine> lines = new ArrayList<>();
        lines.add(pushLine(firstOrderItem(order.getId()).getId(), "11"));
        try
        {
            pushService.pushToStockIn(order.getId(), lines, null, null);
            fail("超量下推必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应带剩余 10 与本次 11，实际=" + e.getMessage(),
                    e.getMessage().contains("剩余 10") && e.getMessage().contains("本次 11"));
        }
        assertEquals("被拒后已入库量仍为 0", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(BigDecimal.ZERO));
        assertEquals("被拒后不生成入库单", 0, stockInMapper.docs.size());
    }

    @Test
    public void 部分下推后剩余可入库量按已回写值递减()
    {
        ErpPurchaseOrder order = approvedOrder("10", WAREHOUSE);
        String orderItemId = firstOrderItem(order.getId()).getId();

        // 第一次只推 4（显式数量）
        List<ErpPushLine> first = new ArrayList<>();
        first.add(pushLine(orderItemId, "4"));
        ErpPushInResultVo firstResult = pushService.pushToStockIn(order.getId(), first, null, null);
        assertEquals("本次推 4", 0, firstResult.getPushedQty().compareTo(new BigDecimal("4")));
        assertEquals("未过账时剩余仍是 10（received_qty 未回写）", 0,
                firstResult.getRemainQtySum().compareTo(new BigDecimal("6")));

        // 过账第一张：已入库量 4 → 剩余 6
        stockInService.submitStockIn(firstResult.getStockIn().getId());
        stockInService.approveStockIn(firstResult.getStockIn().getId(), ErpPostingOptions.defaults());
        assertEquals("过账后已入库量 4", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(new BigDecimal("4")));
        assertEquals("过账后剩余可入库量 6", 0,
                ErpPurRules.remainingReceiveSum(orderService.selectOrderDetail(order.getId()).getItems())
                        .compareTo(new BigDecimal("6")));

        // 第二次不填数量：按剩余 6 全推
        ErpPushInResultVo second = pushService.pushToStockIn(order.getId(), null, null, null);
        assertEquals("不填数量按剩余全推 6", 0, second.getPushedQty().compareTo(new BigDecimal("6")));
        stockInService.submitStockIn(second.getStockIn().getId());
        stockInService.approveStockIn(second.getStockIn().getId(), ErpPostingOptions.defaults());
        assertEquals("两次过账后已入库量 = 10", 0,
                firstOrderItem(order.getId()).getReceivedQty().compareTo(new BigDecimal("10")));
        assertEquals("结存 = 10", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(new BigDecimal("10")));
        assertTrue("已入库完毕", ErpPurRules.isFullyReceived(
                orderService.selectOrderDetail(order.getId()).getItems()));
    }

    /* ==================== §3.7 采购线端到端（申请→采购单→入库单→过账） ==================== */

    @Test
    public void 采购线端到端从申请到下推入库单过账()
    {
        // ① 建申请单（两行：球阀 10、法兰 5）
        ErpPurchaseRequest request = new ErpPurchaseRequest();
        request.setPurpose("端到端采购线");
        request.setRequestDeptId("103");
        List<ErpPurchaseRequestItem> requestItems = new ArrayList<>();
        requestItems.add(requestItem(PRODUCT, "10", "2"));
        requestItems.add(requestItem(PRODUCT2, "5", "3"));
        request.setItems(requestItems);
        String requestId = requestService.insertRequest(request);
        requestService.changeStatus(requestId, "submit", null);
        requestService.changeStatus(requestId, "approve", null);

        // ② 下推采购单（选供应商），再提交+审核
        ErpPushResultVo pushOrder = pushService.pushToPurchaseOrder(requestId, null, SUPPLIER, null);
        String orderId = pushOrder.getOrder().getId();
        assertTrue("申请单两行推完 → 归零即完成", pushOrder.isAutoCompleted());
        orderService.changeStatus(orderId, "submit", null);
        orderService.changeStatus(orderId, "approve", null);

        // ③ 下推入库单（带收货仓库）
        ErpPushInResultVo pushIn = pushService.pushToStockIn(orderId, null, WAREHOUSE, null);
        String stockInId = pushIn.getStockIn().getId();
        assertEquals("两行合计下推 15", 0, pushIn.getPushedQty().compareTo(new BigDecimal("15")));
        assertEquals("入库单 2 行", 2, pushIn.getStockIn().getItems().size());
        assertEquals("入库单是草稿", ErpDocStatus.DRAFT, pushIn.getStockIn().getStatus());
        assertEquals("未过账不回写", 0, receivedSum(orderId).compareTo(BigDecimal.ZERO));

        // ④ 提交 + 审核过账
        stockInService.submitStockIn(stockInId);
        stockInService.approveStockIn(stockInId, ErpPostingOptions.defaults());

        assertEquals("过账后两行都已回写（10 + 5）", 0, receivedSum(orderId).compareTo(new BigDecimal("15")));
        assertEquals("球阀结存 10", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(new BigDecimal("10")));
        assertEquals("法兰结存 5", 0, stockQty(PRODUCT2, WAREHOUSE).compareTo(new BigDecimal("5")));
        assertEquals("流水 2 条", 2, ledgerMapper.countLedger());
        assertEquals("入库单已过账", "1", stockInService.selectStockInDetail(stockInId).getPosted());
        assertEquals("来源链一致（入库单 → 采购单）", orderService.selectOrderDetail(orderId).getDocNo(),
                stockInService.selectStockInDetail(stockInId).getSourceDocNo());

        // ⑤ 反审核红冲：结存与已入库量一起回退
        stockInService.unapproveStockIn(stockInId, "端到端回退");
        assertEquals("已入库量回退到 0", 0, receivedSum(orderId).compareTo(BigDecimal.ZERO));
        assertEquals("球阀结存回 0", 0, stockQty(PRODUCT, WAREHOUSE).compareTo(BigDecimal.ZERO));
        assertEquals("法兰结存回 0", 0, stockQty(PRODUCT2, WAREHOUSE).compareTo(BigDecimal.ZERO));
        assertEquals("流水 4 条（2 正 + 2 冲）", 4, ledgerMapper.countLedger());

        // ⑥ 红冲后可以再次下推（剩余可入库量恢复）
        //    注意：本用例的采购单是由申请单下推而来的，申请单没有仓库，因此采购单也没有
        //    默认收货仓库 —— 再次下推时必须显式指定收货仓库（这正是 §4 的 DDL 约束要求的行为）
        ErpPushInResultVo again = pushService.pushToStockIn(orderId, null, WAREHOUSE, null);
        assertEquals("红冲后可再次全量下推 15", 0, again.getPushedQty().compareTo(new BigDecimal("15")));
    }

    /* ==================== 夹具 ==================== */

    private static CtmsSupplier supplier(String id, String name)
    {
        CtmsSupplier supplier = new CtmsSupplier();
        supplier.setId(id);
        supplier.setName(name);
        supplier.setEnableFlag("1");
        return supplier;
    }

    private static ErpPushLine pushLine(String srcItemId, String qty)
    {
        ErpPushLine line = new ErpPushLine();
        line.setSrcItemId(srcItemId);
        line.setQty(qty == null ? null : new BigDecimal(qty));
        return line;
    }

    private static ErpPurchaseRequestItem requestItem(String productId, String qty, String price)
    {
        ErpPurchaseRequestItem item = new ErpPurchaseRequestItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    /** 建一张"已审核"的采购单（供应商 = 启用中的 S-001）。 */
    private ErpPurchaseOrder approvedOrder(String qty, String receiptWarehouseId)
    {
        ErpPurchaseOrder order = orderPayload(qty, receiptWarehouseId);
        String id = orderService.insertOrder(order);
        orderService.changeStatus(id, "submit", null);
        orderService.changeStatus(id, "approve", null);
        return orderService.selectOrderDetail(id);
    }

    private ErpPurchaseOrder orderPayload(String qty, String receiptWarehouseId)
    {
        ErpPurchaseOrder order = new ErpPurchaseOrder();
        order.setSupplierId(SUPPLIER);
        order.setReceiptWarehouseId(receiptWarehouseId);
        List<ErpPurchaseOrderItem> items = new ArrayList<>();
        ErpPurchaseOrderItem item = new ErpPurchaseOrderItem();
        item.setProductId(PRODUCT);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal("2"));
        items.add(item);
        order.setItems(items);
        return order;
    }

    private ErpPurchaseOrderItem firstOrderItem(String orderId)
    {
        List<ErpPurchaseOrderItem> items = pur.orderItemMapper.selectItemsByDocId(orderId);
        if (items == null || items.isEmpty())
        {
            throw new IllegalStateException("采购单没有行项：" + orderId);
        }
        return items.get(0);
    }

    /** 采购单已入库量合计（读库内行项）。 */
    private BigDecimal receivedSum(String orderId)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpPurchaseOrderItem item : pur.orderItemMapper.selectItemsByDocId(orderId))
        {
            sum = sum.add(item.getReceivedQty() == null ? BigDecimal.ZERO : item.getReceivedQty());
        }
        return sum;
    }

    /**
     * 结存数量（<b>无结存行按 0 处理</b>）。
     *
     * <p> 桩的 {@code qty()} 在无行时返回 {@code null}，直接 {@code compareTo} 会 NPE；
     * 而"未过账时不产生结存行"恰恰是要断言的事，所以这里统一按 0 比，
     * 需要断言"没有结存行"的用例单独用 {@code assertNull(selectStockByKey(...))}。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 结存数量（无行 0）
     */
    private BigDecimal stockQty(String productId, String warehouseId)
    {
        BigDecimal qty = stockMapper.qty(productId, warehouseId);
        return qty == null ? BigDecimal.ZERO : qty;
    }

    private static void assertRejected(final String expectedMessage, Runnable action)
    {
        try
        {
            action.run();
            fail("本应被拒绝，提示应为：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            assertEquals("提示文案是接口契约，必须逐字一致", expectedMessage, e.getMessage());
        }
    }

    /* ==================== 真实服务的测试子类（固定上下文） ==================== */

    /**
     * 过账引擎子类：把负库存参数固定为"不允许"（本用例只走入库方向，不依赖该参数）。
     */
    static class TestJournalService extends ErpStockJournalServiceImpl
    {
        @Override
        protected String readNegativeStockParam()
        {
            return "false";
        }
    }

    /**
     * 入库单服务子类：固定单号与操作人（生产走平台编号服务与登录上下文）。
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
            return "2";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return "103";
        }
    }

    /**
     * 采购申请单服务子类（固定上下文与数据范围）。
     */
    static class TestRequestService extends ErpPurchaseRequestServiceImpl
    {
        @Override
        protected Date now()
        {
            return new Date();
        }

        @Override
        protected String currentUserId()
        {
            return "2";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return "103";
        }

        @Override
        protected void applyCreateSnapshot(com.ruoyi.ctms.erp.base.domain.ErpDocHeader header)
        {
            header.applyCreateSnapshot(currentUserId(), currentDeptId(), currentUsername());
        }

        @Override
        protected String dataScopeSql()
        {
            return null;
        }

        @Override
        protected boolean canAccess(String rowCreateId, String rowDeptId)
        {
            return true;
        }
    }

    /**
     * 采购单服务子类（固定上下文与数据范围）。
     */
    static class TestOrderService extends ErpPurchaseOrderServiceImpl
    {
        @Override
        protected Date now()
        {
            return new Date();
        }

        @Override
        protected String currentUserId()
        {
            return "2";
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return "103";
        }

        @Override
        protected void applyCreateSnapshot(com.ruoyi.ctms.erp.base.domain.ErpDocHeader header)
        {
            header.applyCreateSnapshot(currentUserId(), currentDeptId(), currentUsername());
        }

        @Override
        protected String dataScopeSql()
        {
            return null;
        }

        @Override
        protected boolean canAccess(String rowCreateId, String rowDeptId)
        {
            return true;
        }
    }

    /* ==================== 采购侧 Mapper 桩 ==================== */

    /**
     * <p> 采购侧四个 Mapper 的内存桩（动态代理 + 一个方法分派器）。 </p>
     *
     * <p> 与 t4a 单测里的桩同源：那份是本组私有的测试夹具（私有内部类），
     * 本文件为独立夹具再实现一份紧凑版本，避免改动已绿的 t4a 用例。
     * 若后续 base/t3 统一提供测试夹具，两处可一并收口。 </p>
     */
    static class PurStub implements InvocationHandler
    {
        final Map<String, ErpPurchaseRequest> requestStore = new LinkedHashMap<>();

        final Map<String, ErpPurchaseRequestItem> requestItemStore = new LinkedHashMap<>();

        final Map<String, ErpPurchaseOrder> orderStore = new LinkedHashMap<>();

        final Map<String, ErpPurchaseOrderItem> orderItemStore = new LinkedHashMap<>();

        final ErpPurchaseRequestMapper requestMapper = proxy(ErpPurchaseRequestMapper.class);

        final ErpPurchaseRequestItemMapper requestItemMapper = proxy(ErpPurchaseRequestItemMapper.class);

        final ErpPurchaseOrderMapper orderMapper = proxy(ErpPurchaseOrderMapper.class);

        final ErpPurchaseOrderItemMapper orderItemMapper = proxy(ErpPurchaseOrderItemMapper.class);

        private <T> T proxy(Class<T> type)
        {
            return type.cast(Proxy.newProxyInstance(ErpPurchasePushInTest.class.getClassLoader(),
                    new Class<?>[] { type }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            String name = method.getName();
            if ("toString".equals(name))
            {
                return "pur-stub";
            }
            if ("hashCode".equals(name))
            {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name))
            {
                return Boolean.valueOf(proxy == args[0]);
            }
            /* ---------- 申请单表头 ---------- */
            if ("selectRequestList".equals(name))
            {
                return new ArrayList<>(requestStore.values());
            }
            if ("selectRequestById".equals(name))
            {
                return requestStore.get((String) args[0]);
            }
            if ("selectDocNoByNo".equals(name))
            {
                for (ErpPurchaseRequest request : requestStore.values())
                {
                    if (args[0] != null && args[0].equals(request.getDocNo()))
                    {
                        return request;
                    }
                }
                return null;
            }
            if ("insertRequest".equals(name))
            {
                ErpPurchaseRequest request = (ErpPurchaseRequest) args[0];
                requestStore.put(request.getId(), request);
                return 1;
            }
            if ("updateRequest".equals(name))
            {
                return 1;
            }
            if ("updateRequestStatus".equals(name))
            {
                ErpPurchaseRequest patch = (ErpPurchaseRequest) args[0];
                ErpPurchaseRequest stored = requestStore.get(patch.getId());
                if (stored == null)
                {
                    return 0;
                }
                if (patch.getStatus() != null)
                {
                    stored.setStatus(patch.getStatus());
                }
                if (patch.getApprovedBy() != null)
                {
                    stored.setApprovedBy(patch.getApprovedBy());
                }
                if (patch.getApprovedAt() != null)
                {
                    stored.setApprovedAt(patch.getApprovedAt());
                }
                return 1;
            }
            if ("clearApprovalTrace".equals(name))
            {
                ErpPurchaseRequest stored = requestStore.get((String) args[0]);
                if (stored != null)
                {
                    stored.setApprovedBy(null);
                    stored.setApprovedAt(null);
                    return 1;
                }
                return 0;
            }
            if ("selectItemsByDocId".equals(name))
            {
                String docId = (String) args[0];
                return orderStore.containsKey(docId) ? orderItemsOf(docId) : requestItemsOf(docId);
            }
            if ("selectItemsByDocIds".equals(name))
            {
                List<Object> result = new ArrayList<>();
                for (Object docId : (List<?>) args[0])
                {
                    String id = String.valueOf(docId);
                    if (orderStore.containsKey(id))
                    {
                        result.addAll(orderItemsOf(id));
                    }
                    else
                    {
                        result.addAll(requestItemsOf(id));
                    }
                }
                return result;
            }
            if ("selectItemById".equals(name))
            {
                String id = (String) args[0];
                ErpPurchaseRequestItem requestItem = requestItemStore.get(id);
                return requestItem == null ? orderItemStore.get(id) : requestItem;
            }
            if ("batchInsertItems".equals(name))
            {
                List<?> list = (List<?>) args[0];
                for (Object row : list)
                {
                    if (row instanceof ErpPurchaseRequestItem)
                    {
                        ErpPurchaseRequestItem item = (ErpPurchaseRequestItem) row;
                        requestItemStore.put(item.getId(), item);
                    }
                    else if (row instanceof ErpPurchaseOrderItem)
                    {
                        ErpPurchaseOrderItem item = (ErpPurchaseOrderItem) row;
                        // 只按真实主键存：id 为 null 的行项不入表（见 insertOrder 的说明）
                        if (item.getId() != null)
                        {
                            orderItemStore.put(item.getId(), item);
                        }
                    }
                }
                return list.size();
            }
            if ("increaseOrderedQty".equals(name))
            {
                ErpPurchaseRequestItem item = requestItemStore.get((String) args[0]);
                if (item == null)
                {
                    return 0;
                }
                BigDecimal current = item.getOrderedQty() == null ? BigDecimal.ZERO : item.getOrderedQty();
                item.setOrderedQty(current.add((BigDecimal) args[1]));
                return 1;
            }
            if ("increaseReceivedQty".equals(name))
            {
                ErpPurchaseOrderItem item = orderItemStore.get((String) args[0]);
                if (item == null)
                {
                    return 0;
                }
                BigDecimal current = item.getReceivedQty() == null ? BigDecimal.ZERO : item.getReceivedQty();
                item.setReceivedQty(current.add((BigDecimal) args[1]));
                return 1;
            }
            if ("decreaseReceivedQty".equals(name))
            {
                ErpPurchaseOrderItem item = orderItemStore.get((String) args[0]);
                if (item == null)
                {
                    return 0;
                }
                BigDecimal current = item.getReceivedQty() == null ? BigDecimal.ZERO : item.getReceivedQty();
                BigDecimal next = current.subtract((BigDecimal) args[1]);
                item.setReceivedQty(next.signum() < 0 ? BigDecimal.ZERO : next);
                return 1;
            }
            if ("updateItemSeq".equals(name))
            {
                return 1;
            }
            if ("deleteItemById".equals(name))
            {
                return 0;
            }
            if ("deleteItemsByDocId".equals(name))
            {
                String docId = (String) args[0];
                requestItemStore.values().removeIf(item -> docId.equals(item.getDocId()));
                orderItemStore.values().removeIf(item -> docId.equals(item.getDocId()));
                return 1;
            }
            /* ---------- 采购单表头 ---------- */
            if ("selectOrderList".equals(name))
            {
                return new ArrayList<>(orderStore.values());
            }
            if ("selectOrderById".equals(name))
            {
                return orderStore.get((String) args[0]);
            }
            if ("selectOrderByNo".equals(name))
            {
                for (ErpPurchaseOrder order : orderStore.values())
                {
                    if (args[0] != null && args[0].equals(order.getDocNo()))
                    {
                        return order;
                    }
                }
                return null;
            }
            if ("selectBySourceDocId".equals(name))
            {
                List<ErpPurchaseOrder> result = new ArrayList<>();
                for (ErpPurchaseOrder order : orderStore.values())
                {
                    if (args[0] != null && args[0].equals(order.getSourceDocId()))
                    {
                        result.add(order);
                    }
                }
                return result;
            }
            if ("insertOrder".equals(name))
            {
                ErpPurchaseOrder order = (ErpPurchaseOrder) args[0];
                orderStore.put(order.getId(), order);
                // ⚠ 刻意不在这里存行项：insert 路径上服务是先插主表、再给行项分配 id 后批量插入。
                //   若此处按"当时的 id（还是 null）"先存一份，行项就会以 null 与真实 id 两个键
                //   同时存在，读取时同一行被返回两次（曾把本文件的断言全部带偏）。
                return 1;
            }
            if ("updateOrder".equals(name) || "updateOrderStatus".equals(name)
                    || "updateOrderTotal".equals(name) || "clearApprovalTrace".equals(name))
            {
                ErpPurchaseOrder patch = (ErpPurchaseOrder) args[0];
                ErpPurchaseOrder stored = orderStore.get(patch.getId());
                if (stored == null)
                {
                    return 0;
                }
                if ("clearApprovalTrace".equals(name))
                {
                    stored.setApprovedBy(null);
                    stored.setApprovedAt(null);
                    return 1;
                }
                if (patch.getStatus() != null)
                {
                    stored.setStatus(patch.getStatus());
                }
                if (patch.getTotalAmount() != null)
                {
                    stored.setTotalAmount(patch.getTotalAmount());
                }
                if (patch.getApprovedBy() != null)
                {
                    stored.setApprovedBy(patch.getApprovedBy());
                }
                if (patch.getApprovedAt() != null)
                {
                    stored.setApprovedAt(patch.getApprovedAt());
                }
                return 1;
            }
            if ("deleteOrderById".equals(name))
            {
                return orderStore.remove((String) args[0]) == null ? 0 : 1;
            }
            throw new UnsupportedOperationException("桩未实现：" + method.getDeclaringClass().getSimpleName()
                    + "#" + name);
        }

        private List<ErpPurchaseRequestItem> requestItemsOf(String docId)
        {
            List<ErpPurchaseRequestItem> result = new ArrayList<>();
            for (ErpPurchaseRequestItem item : requestItemStore.values())
            {
                if (docId != null && docId.equals(item.getDocId()))
                {
                    result.add(item);
                }
            }
            return result;
        }

        private List<ErpPurchaseOrderItem> orderItemsOf(String docId)
        {
            List<ErpPurchaseOrderItem> result = new ArrayList<>();
            for (ErpPurchaseOrderItem item : orderItemStore.values())
            {
                if (docId != null && docId.equals(item.getDocId()))
                {
                    result.add(item);
                }
            }
            result.sort((left, right) -> Integer.compare(
                    left.getSeq() == null ? 0 : left.getSeq().intValue(),
                    right.getSeq() == null ? 0 : right.getSeq().intValue()));
            return result;
        }
    }

    /* ==================== 其余协作桩 ==================== */

    /**
     * 往来单位服务桩（只用供应商查找）。
     */
    static class StubPartnerService implements ICtmsPartnerService
    {
        final Map<String, CtmsSupplier> suppliers = new LinkedHashMap<>();

        @Override
        public CtmsSupplier selectSupplierById(String id)
        {
            return suppliers.get(id);
        }

        @Override
        public CtmsCustomer selectCustomerById(String id)
        {
            return null;
        }

        @Override
        public List<CtmsSupplier> selectSupplierOptions()
        {
            return new ArrayList<>(suppliers.values());
        }

        @Override
        public List<CtmsCustomer> selectCustomerList(CtmsCustomer query)
        {
            return new ArrayList<>();
        }

        @Override
        public List<CtmsCustomer> selectCustomerOptions()
        {
            return new ArrayList<>();
        }

        @Override
        public void insertCustomer(CtmsCustomer c)
        {
        }

        @Override
        public void updateCustomer(CtmsCustomer c)
        {
        }

        @Override
        public void deleteCustomerById(String id)
        {
        }

        @Override
        public int changeCustomerStatus(String id, String enableFlag)
        {
            return 0;
        }

        @Override
        public List<CtmsSupplier> selectSupplierList(CtmsSupplier query)
        {
            return new ArrayList<>(suppliers.values());
        }

        @Override
        public void insertSupplier(CtmsSupplier s)
        {
        }

        @Override
        public void updateSupplier(CtmsSupplier s)
        {
        }

        @Override
        public void deleteSupplierById(String id)
        {
        }

        @Override
        public int changeSupplierStatus(String id, String enableFlag)
        {
            return 0;
        }
    }

    /**
     * 合同服务桩（本用例不下推合同，只保证服务装配完整）。
     */
    static class StubContractService implements ICtmsContractService
    {
        @Override
        public List<CtmsContract> selectContractList(CtmsContract query)
        {
            return new ArrayList<>();
        }

        @Override
        public void checkContractAccess(String id)
        {
        }

        @Override
        public CtmsContract selectContractDetail(String id)
        {
            return null;
        }

        @Override
        public List<com.ruoyi.ctms.domain.CtmsChangeLog> selectChangeLogList(com.ruoyi.ctms.domain.CtmsChangeLog query)
        {
            return new ArrayList<>();
        }

        @Override
        public void insertContract(CtmsContract contract)
        {
        }

        @Override
        public void updateContract(CtmsContract contract)
        {
        }

        @Override
        public int changeStatus(CtmsContract contract)
        {
            return 0;
        }

        @Override
        public void softDelete(String id, String reason)
        {
        }

        @Override
        public void restore(String id)
        {
        }

        @Override
        public int releaseWarranty(String id)
        {
            return 0;
        }

        @Override
        public CtmsContract selectFrameworkDetail(String id)
        {
            return null;
        }

        @Override
        public void deleteContract(String id)
        {
        }

        @Override
        public String previewContractNo(String type, String subjectCode, String referenceDay)
        {
            return null;
        }

        @Override
        public com.ruoyi.ctms.domain.vo.CtmsWarrantyReminderVo selectWarrantyReminders()
        {
            return null;
        }
    }

    /**
     * 编号服务桩：按 base 的配置 id 返回 {@code PR/PO + yyyyMM + 序号}。
     */
    static class StubCodeGenService implements ICodeGenService
    {
        private int seq = 0;

        @Override
        public String getNextCode(String confId)
        {
            return getNextCode(confId, null);
        }

        @Override
        public String getNextCode(String confId, CodeGenContext context)
        {
            seq++;
            String prefix = ErpDocNoGenerator.CONF_ID_PURCHASE_ORDER.equals(confId) ? "PO" : "PR";
            return prefix + "202610" + String.format(java.util.Locale.ROOT, "%06d", Integer.valueOf(seq));
        }

        @Override
        public String previewNextCode(String confId, CodeGenContext context)
        {
            return getNextCode(confId, context);
        }
    }
}
