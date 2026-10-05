package com.ruoyi.ctms.erp.sales.service.impl;

import java.math.BigDecimal;
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
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockJournalServiceImpl;
import com.ruoyi.ctms.erp.posting.service.impl.ErpStockOutServiceImpl;
import com.ruoyi.ctms.erp.posting.support.ErpPostedLine;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushLine;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestMapper;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>t5b 销售线下半段单测</b>：销售订单 → 出库单下推、过账回写 {@code shipped_qty}、
 * 红冲回退、上游守卫、以及销售线端到端（tasks.md §4.3 / §4.4）。 </p>
 *
 * <p> <b>三条必测断言</b>（captain 的 t8 完成判据、t22 审计 F3 的硬要求）： </p>
 * <ol>
 *   <li> <b>未过账不回写、仍可再次下推</b>：下推出库单（草稿）后订单行 {@code shipped_qty} 仍为 0，
 *        且同一行还能再推一次剩余量； </li>
 *   <li> <b>过账后累加</b>：出库单过账成功 → 监听器把 {@code shipped_qty} 加上本次出库量； </li>
 *   <li> <b>红冲后回退不为负</b>：反审核红冲 → {@code shipped_qty} 回退，且<b>不小于 0</b>。 </li>
 * </ol>
 *
 * <p> <b>桩策略（与各组的"不起 Spring"口径一致）</b>： </p>
 * <ul>
 *   <li> 销售侧：内存桩 Mapper（自建，与本包 t5a 的单测同款）； </li>
 *   <li> 库存侧：<b>复用 T3 的真实实现</b>（{@link ErpStockOutServiceImpl} +
 *        {@link ErpStockJournalServiceImpl}）并注入 T3 提供的公共内存桩
 *        （{@code ErpPostingTestSupport} 的 {@code Stub*} 系列）——
 *        这样"下推出库单 → 审核过账 → 监听器回写"走的是真实代码路径，
 *        而不是把 T3 也 mock 掉。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpSalesPushOutTest
{
    private static final String CUSTOMER_ID = "CUST-1";

    private static final String CUSTOMER_NAME = "启用客户";

    private static final String PRODUCT_ID = "P1";

    private static final String UOM_ID = "U-1";

    private static final String WAREHOUSE_ID = "WH-1";

    private static final String DEPT_ID = "100";

    private static final String USER_ID = "9";

    /* ==================== 销售侧内存桩 ==================== */

    private final Map<String, ErpSalesRequest> requests = new LinkedHashMap<>();

    private final Map<String, List<ErpSalesRequestItem>> requestItems = new LinkedHashMap<>();

    private final Map<String, ErpSalesOrder> orders = new LinkedHashMap<>();

    private final Map<String, List<ErpSalesOrderItem>> orderItems = new LinkedHashMap<>();

    private final List<CtmsChangeLog> changeLogs = new ArrayList<>();

    /* ==================== 库存侧（T3 的公共桩 + 真实实现） ==================== */

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper stockMapper =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockMapper();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper ledgerMapper =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubLedgerMapper();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubChangeLogMapper stockChangeLogMapper =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubChangeLogMapper();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubDocObjectAccess docObjectAccess =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubDocObjectAccess();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutMapper stockOutMapper =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutMapper();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutItemMapper stockOutItemMapper =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutItemMapper();

    private final com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubMasterLookup masterLookup =
            new com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubMasterLookup();

    private TestJournalService journal;

    private TestStockOutService stockOutService;

    private TestOrderService orderService;

    private TestRequestService requestService;

    private ErpSalesPushServiceImpl pushService;

    private com.ruoyi.ctms.erp.sales.support.ErpSalesOutboundListener outboundListener;

    /* ==================== 夹具 ==================== */

    @Before
    public void setUp()
    {
        requests.clear();
        requestItems.clear();
        orders.clear();
        orderItems.clear();
        changeLogs.clear();

        masterLookup.addUom(UOM_ID, "个", Integer.valueOf(0));
        masterLookup.addWarehouse(WAREHOUSE_ID, "一号仓");
        masterLookup.addProduct(PRODUCT_ID, "P001", "螺丝", UOM_ID);
        // 过账需要结存足够（否则负库存校验会把出库审核拦掉）
        stockMapper.seed(PRODUCT_ID, WAREHOUSE_ID, new BigDecimal("100"));

        journal = new TestJournalService();
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(journal, "stockMapper", stockMapper);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(journal, "stockLedgerMapper", ledgerMapper);

        stockOutService = new TestStockOutService();
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "stockOutMapper", stockOutMapper);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "stockOutItemMapper",
                stockOutItemMapper);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "changeLogMapper",
                stockChangeLogMapper);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "journalService", journal);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "docObjectAccess",
                docObjectAccess);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(stockOutService, "masterLookup", masterLookup);

        orderService = new TestOrderService();
        orderService.setOrderMapper(orderMapperStub());
        orderService.setOrderItemMapper(orderItemMapperStub());
        orderService.setPartnerMapper(partnerMapperStub());
        orderService.setContractMapper(contractMapperStub());
        orderService.setChangeLogMapper(changeLogMapperStub());
        orderService.setMasterLookup(masterLookup);
        orderService.setStockOutMapper(stockOutMapper);

        requestService = new TestRequestService();
        requestService.setRequestMapper(requestMapperStub());
        requestService.setRequestItemMapper(requestItemMapperStub());
        requestService.setOrderMapper(orderMapperStub());
        requestService.setPartnerMapper(partnerMapperStub());
        requestService.setChangeLogMapper(changeLogMapperStub());
        requestService.setMasterLookup(masterLookup);

        pushService = new ErpSalesPushServiceImpl(orderService);
        pushService.setRequestMapper(requestMapperStub());
        pushService.setRequestItemMapper(requestItemMapperStub());
        pushService.setChangeLogMapper(changeLogMapperStub());
        pushService.setStockOutService(stockOutService);
        pushService.setMasterLookup(masterLookup);

        outboundListener = new com.ruoyi.ctms.erp.sales.support.ErpSalesOutboundListener();
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(outboundListener, "orderService", orderService);
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(outboundListener, "orderItemMapper",
                orderItemMapperStub());
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(outboundListener, "stockOutService",
                stockOutService);
        // 把销售侧监听器装进过账引擎（真实引擎会在同一事务内回调它）
        com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.inject(journal, "postingListeners",
                Collections.singletonList(outboundListener));
    }

    /* ==================== 断言 1：未过账不回写、仍可再次下推 ==================== */

    @Test
    public void pushOutDoesNotWriteShippedQtyAndAllowsSecondPush()
    {
        String orderId = approvedOrder("P1", "10", "3");
        String orderItemId = orderItems.get(orderId).get(0).getId();

        Map<String, Object> first = pushService.pushSalesOrderToStockOut(orderId, null);
        assertNotNull(first.get("stockOutId"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) first.get("pushedQtySum")));
        // 未过账 ⇒ 不回写
        assertEquals("未过账不得回写 shipped_qty",
                0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
        // 仍可再次下推（草稿出库单不占剩余量：剩余量只看 shipped_qty）
        Map<String, Object> second = pushService.pushSalesOrderToStockOut(orderId, null);
        assertNotNull(second.get("stockOutId"));
        assertFalse("两次下推必须生成两张不同的草稿出库单",
                first.get("stockOutId").equals(second.get("stockOutId")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) second.get("pushedQtySum")));
        assertEquals("仍未过账 ⇒ 仍不回写",
                0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
    }

    /* ==================== 断言 2：过账后累加（端到端，真实过账引擎） ==================== */

    @Test
    public void postingStockOutAccumulatesShippedQty()
    {
        String orderId = approvedOrder("P1", "10", "3");
        String orderItemId = orderItems.get(orderId).get(0).getId();
        String stockOutId = (String) pushService.pushSalesOrderToStockOut(orderId, null).get("stockOutId");

        // 审核即过账：真实引擎 + 真实监听器
        stockOutService.submitStockOut(stockOutId);
        stockOutService.approveStockOut(stockOutId, null);

        assertEquals("过账后应累加本次出库量",
                0, new BigDecimal("10").compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
        ErpStockOut posted = stockOutService.selectStockOutDetail(stockOutId);
        assertEquals(ErpDocStatus.APPROVED, posted.getStatus());
        assertTrue(posted.isPostedFlag());
    }

    /* ==================== 断言 3：红冲后回退不为负（端到端） ==================== */

    @Test
    public void reversalRollsBackShippedQtyAndNeverGoesNegative()
    {
        String orderId = approvedOrder("P1", "10", "3");
        String orderItemId = orderItems.get(orderId).get(0).getId();
        String stockOutId = (String) pushService.pushSalesOrderToStockOut(orderId, null).get("stockOutId");
        stockOutService.submitStockOut(stockOutId);
        stockOutService.approveStockOut(stockOutId, null);
        assertEquals(0, new BigDecimal("10").compareTo(orderItemById(orderId, orderItemId).getShippedQty()));

        // 红冲：走真实的红冲路径（红冲行没有 srcItemId ⇒ 监听器读出库单行项定位）
        stockOutService.unapproveStockOut(stockOutId, "客户取消");
        assertEquals("红冲后已出库量应回退到 0",
                0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));

        // 再冲一次也不为负（引擎无流水可冲时不回调；即便回调，SQL 也是 greatest(0, …)）
        outboundListener.afterReversed(ErpDocType.STOCK_OUT, stockOutId, reversalLines());
        assertFalse("已出库量不得为负",
                orderItemById(orderId, orderItemId).getShippedQty().signum() < 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));

        // 回退量大于已出库量时也必须夹在 0
        ErpSalesOrderItem item = orderItemById(orderId, orderItemId);
        item.setShippedQty(new BigDecimal("2"));
        outboundListener.afterReversed(ErpDocType.STOCK_OUT, stockOutId, reversalLines());
        assertEquals("回退超过已出库量时夹到 0",
                0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
    }

    /* ==================== 下推的其余冻结口径 ==================== */

    @Test
    public void onlyApprovedOrCompletedOrderCanPushOut()
    {
        String orderId = approvedOrder("P1", "10", "3");
        orders.get(orderId).setStatus(ErpDocStatus.DRAFT);
        try
        {
            pushService.pushSalesOrderToStockOut(orderId, null);
            fail("草稿销售订单不应可下推出库单");
        }
        catch (ServiceException e)
        {
            assertEquals(ErpSalRules.MSG_ORDER_NOT_PUSHABLE_OUT, e.getMessage());
        }
        orders.get(orderId).setStatus(ErpDocStatus.COMPLETED);
        // 已完成可推
        assertNotNull(pushService.pushSalesOrderToStockOut(orderId, null).get("stockOutId"));
    }

    @Test
    public void overPushOutIsRejectedAndWritesNothing()
    {
        String orderId = approvedOrder("P1", "10", "3");
        String orderItemId = orderItems.get(orderId).get(0).getId();
        orderItemById(orderId, orderItemId).setShippedQty(new BigDecimal("6"));

        ErpSalesPushRequest request = new ErpSalesPushRequest();
        request.setLines(lines(line(orderItemId, "5")));
        try
        {
            pushService.pushSalesOrderToStockOut(orderId, request);
            fail("本次 5 超过剩余 4，不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("可下推数量不足"));
            assertTrue(e.getMessage(), e.getMessage().contains("剩余 4"));
            assertTrue(e.getMessage(), e.getMessage().contains("本次 5"));
        }
        // 零中间写入：没有生成任何出库单，已出库量也没变
        assertEquals("超量下推不得留下任何出库单", 0, stockOutMapper.docs.size());
        assertEquals(0, new BigDecimal("6").compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
    }

    @Test
    public void pushOutCarriesWarehouseCustomerSnapshotAndSource()
    {
        ErpSalesOrder order = order("P1", "10", "3");
        order.setStatus(ErpDocStatus.APPROVED);
        order.setShipWarehouseId(WAREHOUSE_ID);
        order.setCustomerId(CUSTOMER_ID);
        order.setCustomerName(CUSTOMER_NAME);
        order.setDocNo("SO202610000001");
        String orderId = saveOrder(order);

        Map<String, Object> result = pushService.pushSalesOrderToStockOut(orderId, null);
        ErpStockOut out = stockOutMapper.selectStockOutById((String) result.get("stockOutId"));

        assertEquals(ErpDocStatus.DRAFT, out.getStatus());
        assertEquals(WAREHOUSE_ID, out.getWarehouseId());
        assertEquals("一号仓", out.getWarehouseName());
        assertEquals(ErpSalRules.OUT_TYPE_SALE, out.getOutType());
        assertEquals(CUSTOMER_ID, out.getCustomerId());
        assertEquals(CUSTOMER_NAME, out.getCustomerName());
        assertEquals(ErpDocType.SALES_ORDER.getCode(), out.getSourceDocType());
        assertEquals(orderId, out.getSourceDocId());
        assertEquals("SO202610000001", out.getSourceDocNo());
        // 行项带来源行标识（过账回写的匹配依据）
        List<ErpStockOutItem> items = out.getItems();
        assertEquals(1, items.size());
        assertEquals(orderItems.get(orderId).get(0).getId(), items.get(0).getSrcItemId());
        assertEquals(0, new BigDecimal("10").compareTo(items.get(0).getQty()));
    }

    @Test
    public void pushOutWithoutResolvableWarehouseIsRejected()
    {
        String orderId = approvedOrder("P1", "10", "3");
        orders.get(orderId).setShipWarehouseId(null);
        try
        {
            pushService.pushSalesOrderToStockOut(orderId, null);
            fail("解析不出出库仓库时应拒绝下推（下推时拒绝，而不是留一张插不进库的草稿）");
        }
        catch (ServiceException e)
        {
            assertEquals(ErpSalRules.MSG_OUT_WAREHOUSE_REQUIRED, e.getMessage());
        }
        // 显式指定仓库可下推
        ErpSalesPushRequest request = new ErpSalesPushRequest();
        request.setShipWarehouseId(WAREHOUSE_ID);
        assertNotNull(pushService.pushSalesOrderToStockOut(orderId, request).get("stockOutId"));
    }

    @Test
    public void pushOutAcceptsSrcItemIdAliasAndPartialQty()
    {
        String orderId = approvedOrder("P1", "10", "3");
        String orderItemId = orderItems.get(orderId).get(0).getId();
        // 前端 sales_order 链发的是 srcItemId 键
        ErpSalesPushLine line = new ErpSalesPushLine();
        line.setSrcItemId(orderItemId);
        line.setQty(new BigDecimal("4"));
        ErpSalesPushRequest request = new ErpSalesPushRequest();
        request.setLines(lines(line));

        Map<String, Object> result = pushService.pushSalesOrderToStockOut(orderId, request);
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) result.get("pushedQtySum")));
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) result.get("remainQtySum")));
    }

    /* ==================== 上游守卫（销售申请单 ↔ 下游销售订单） ==================== */

    @Test
    public void unapproveRequestIsBlockedByNonVoidedDownstreamOrder()
    {
        String requestId = approvedRequestWithOneLine();
        // 下游：一张已审核的订单（未作废）
        ErpSalesOrder downstream = order("P1", "5", "1");
        downstream.setStatus(ErpDocStatus.APPROVED);
        downstream.setSourceDocId(requestId);
        saveOrder(downstream);

        try
        {
            requestService.unapproveSalesRequest(requestId, "改单");
            fail("存在未作废下游销售订单时不应可反审核");
        }
        catch (ServiceException e)
        {
            assertEquals(ErpSalRules.MSG_DOWNSTREAM_BLOCK, e.getMessage());
        }
        assertEquals(ErpDocStatus.APPROVED, requests.get(requestId).getStatus());
    }

    @Test
    public void unapproveRequestIsAllowedAfterAllDownstreamVoided()
    {
        String requestId = approvedRequestWithOneLine();
        ErpSalesOrder downstream = order("P1", "5", "1");
        downstream.setStatus(ErpDocStatus.VOIDED);
        downstream.setSourceDocId(requestId);
        saveOrder(downstream);

        requestService.unapproveSalesRequest(requestId, "改单");
        assertEquals(ErpDocStatus.SUBMITTED, requests.get(requestId).getStatus());
    }

    /* ==================== §4.4 销售线端到端（真实服务链） ==================== */

    @Test
    public void salesLineEndToEndFromRequestToPostedStockOut()
    {
        // ① 申请（草稿 → 提交 → 审核）
        ErpSalesRequest request = request("100");
        requestService.insertSalesRequest(request);
        requestService.submitSalesRequest(request.getId(), null);
        requestService.approveSalesRequest(request.getId());
        assertEquals(ErpDocStatus.APPROVED, requests.get(request.getId()).getStatus());

        // ② 下推销售订单（草稿）→ 提交 → 审核
        Map<String, Object> pushResult = pushService.pushSalesRequestToOrder(request.getId(), null);
        String orderId = (String) pushResult.get("orderId");
        assertEquals(Boolean.TRUE, pushResult.get("requestCompleted"));
        assertEquals(ErpDocStatus.DRAFT, orders.get(orderId).getStatus());
        orderService.submitSalesOrder(orderId, null);
        orderService.approveSalesOrder(orderId);
        assertEquals(ErpDocStatus.APPROVED, orders.get(orderId).getStatus());
        String orderItemId = orderItems.get(orderId).get(0).getId();

        // ③ 下推出库单（草稿，带发货仓库与客户快照）
        orders.get(orderId).setShipWarehouseId(WAREHOUSE_ID);
        Map<String, Object> outResult = pushService.pushSalesOrderToStockOut(orderId, null);
        String stockOutId = (String) outResult.get("stockOutId");
        ErpStockOut out = stockOutService.selectStockOutDetail(stockOutId);
        assertEquals(ErpDocStatus.DRAFT, out.getStatus());
        assertEquals(WAREHOUSE_ID, out.getWarehouseId());
        assertEquals(CUSTOMER_NAME, out.getCustomerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));

        // ④ 审核过账 → 结存扣减（100 → 90） + 已出库量累加
        stockOutService.submitStockOut(stockOutId);
        stockOutService.approveStockOut(stockOutId, null);
        // 只推了一张出库单、且这张单只有一行 10 ⇒ 结存由 100 变 90，不是 80
        // （如果行项被记两遍，这里会是 80；这条断言同时锁住"表头插入 + 行项批量插入"的桩语义）
        assertEquals(1, stockOutMapper.docs.size());
        assertEquals(0, new BigDecimal("10").compareTo(out.getItems().get(0).getQty()));
        assertEquals(0, new BigDecimal("90").compareTo(stockMapper.qty(PRODUCT_ID, WAREHOUSE_ID)));
        assertEquals(0, new BigDecimal("10").compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
        assertEquals(0, new BigDecimal("10").compareTo(orderItemById(orderId, orderItemId).getShippedQty()));

        // ⑤ 反审核红冲 → 结存归位 + 已出库量回退
        stockOutService.unapproveStockOut(stockOutId, "客户取消");
        assertEquals(0, new BigDecimal("100").compareTo(stockMapper.qty(PRODUCT_ID, WAREHOUSE_ID)));
        assertEquals(0, BigDecimal.ZERO.compareTo(orderItemById(orderId, orderItemId).getShippedQty()));
    }

    /* ==================== 监听器（直接驱动，覆盖边界） ==================== */

    @Test
    public void listenerAccumulatesOnPostedAndIgnoresForeignDocs()
    {
        assertTrue(outboundListener.supports(ErpDocType.STOCK_OUT));
        assertFalse(outboundListener.supports(ErpDocType.STOCK_IN));

        String orderId = approvedOrder("P1", "10", "3");
        String itemId = orderItems.get(orderId).get(0).getId();

        // 出库方向为负 ⇒ 已出库量按绝对量累加
        outboundListener.afterPosted(ErpDocType.STOCK_OUT, "OUT-A", posted(itemId, "-4"));
        assertEquals(0, new BigDecimal("4").compareTo(orderItemById(orderId, itemId).getShippedQty()));

        // 手工建的出库单（行上没有来源）不报错也不写
        outboundListener.afterPosted(ErpDocType.STOCK_OUT, "OUT-MANUAL", posted(null, "-9"));
        assertEquals(0, new BigDecimal("4").compareTo(orderItemById(orderId, itemId).getShippedQty()));

        // 找不到对应订单行（历史数据）同样跳过
        outboundListener.afterPosted(ErpDocType.STOCK_OUT, "OUT-HISTORY", posted("NOT-EXIST", "-9"));
        assertEquals(0, new BigDecimal("4").compareTo(orderItemById(orderId, itemId).getShippedQty()));

        // 空集合/0 变动不动
        outboundListener.afterPosted(ErpDocType.STOCK_OUT, "OUT-ZERO", posted(itemId, "0"));
        assertEquals(0, new BigDecimal("4").compareTo(orderItemById(orderId, itemId).getShippedQty()));
    }

    /* ==================== 上游守卫（销售订单 ↔ 下游出库单） ==================== */

    @Test
    public void unapproveOrderIsBlockedByNonVoidedDownstreamStockOut()
    {
        String orderId = approvedOrderForGuard();
        // 下游：一张未作废的草稿出库单
        pushService.pushSalesOrderToStockOut(orderId, null);
        assertEquals(1, stockOutMapper.docs.size());

        try
        {
            orderService.unapproveSalesOrder(orderId, "改单");
            fail("存在未作废下游出库单时不应可反审核");
        }
        catch (ServiceException e)
        {
            assertEquals(ErpSalRules.MSG_DOWNSTREAM_STOCK_OUT_BLOCK, e.getMessage());
        }
        assertEquals(ErpDocStatus.APPROVED, orders.get(orderId).getStatus());

        // 下游作废后放行
        for (ErpStockOut out : stockOutMapper.docs.values())
        {
            out.setStatus(ErpDocStatus.VOIDED);
        }
        orderService.unapproveSalesOrder(orderId, "改单");
        assertEquals(ErpDocStatus.SUBMITTED, orders.get(orderId).getStatus());
    }

    /* ==================== 夹具工具 ==================== */

    private static ErpPostedLine postedLine(String srcItemId, String qtyChange)
    {
        ErpPostedLine line = new ErpPostedLine();
        line.setSrcItemId(srcItemId);
        line.setQtyChange(new BigDecimal(qtyChange));
        return line;
    }

    /** 手工构造一条过账结果行（模拟引擎回调的入参），用于直接驱动监听器。 */
    private static List<ErpPostedLine> postedLines(String srcItemId, String qtyChange)
    {
        return Collections.singletonList(postedLine(srcItemId, qtyChange));
    }

    private List<ErpPostedLine> reversalLines()
    {
        // 红冲行没有 srcItemId（T3 的红冲行由流水反推），数量为负
        ErpPostedLine line = new ErpPostedLine();
        line.setQtyChange(new BigDecimal("-10"));
        line.setReversal(true);
        return Collections.singletonList(line);
    }

    private ErpSalesOrderItem orderItemById(String orderId, String itemId)
    {
        for (ErpSalesOrderItem item : orderItems.get(orderId))
        {
            if (itemId.equals(item.getId()))
            {
                return item;
            }
        }
        throw new IllegalStateException("行项不存在：" + itemId);
    }

    /** 造一张"已审核 + 指定发货仓库"的销售订单，返回其 id。 */
    private String approvedOrder(String productId, String qty, String price)
    {
        ErpSalesOrder order = order(productId, qty, price);
        order.setStatus(ErpDocStatus.APPROVED);
        order.setShipWarehouseId(WAREHOUSE_ID);
        order.setCustomerId(CUSTOMER_ID);
        order.setCustomerName(CUSTOMER_NAME);
        return saveOrder(order);
    }

    /** 造一张"已审核"的销售申请单（一行），返回其 id。 */
    private String approvedRequestWithOneLine()
    {
        ErpSalesRequest request = request(DEPT_ID);
        requestService.insertSalesRequest(request);
        requests.get(request.getId()).setStatus(ErpDocStatus.APPROVED);
        return request.getId();
    }

    private ErpSalesRequest request(String deptId)
    {
        ErpSalesRequest doc = new ErpSalesRequest();
        doc.setDocDate(new Date(1893456000000L));
        doc.setDeptId(deptId);
        doc.setCustomerId(CUSTOMER_ID);
        ErpSalesRequestItem item = new ErpSalesRequestItem();
        item.setProductId(PRODUCT_ID);
        item.setQty(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("3"));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        return doc;
    }

    private ErpSalesOrder order(String productId, String qty, String price)
    {
        ErpSalesOrder doc = new ErpSalesOrder();
        doc.setDocDate(new Date(1893456000000L));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDeptId(DEPT_ID);
        doc.setCustomerId(CUSTOMER_ID);
        ErpSalesOrderItem item = new ErpSalesOrderItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        return doc;
    }

    /** 直接落一张订单（绕过服务校验，用于构造守卫/下推的输入）。 */
    private String saveOrder(ErpSalesOrder order)
    {
        order.setId("OID-" + (orders.size() + 1));
        if (order.getDocNo() == null)
        {
            order.setDocNo("SO20261000000" + (orders.size() + 1));
        }
        orders.put(order.getId(), order);
        List<ErpSalesOrderItem> rows = new ArrayList<>();
        int seq = 0;
        for (ErpSalesOrderItem item : order.getItems())
        {
            seq++;
            item.setId("OI-" + order.getId() + "-" + seq);
            item.setDocId(order.getId());
            item.setSeq(Integer.valueOf(seq));
            if (item.getShippedQty() == null)
            {
                item.setShippedQty(BigDecimal.ZERO);
            }
            rows.add(item);
        }
        orderItems.put(order.getId(), rows);
        order.setItems(rows);
        return order.getId();
    }

    private static List<ErpSalesPushLine> lines(ErpSalesPushLine... items)
    {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static ErpSalesPushLine line(String docItemId, String qty)
    {
        ErpSalesPushLine line = new ErpSalesPushLine();
        line.setDocItemId(docItemId);
        line.setQty(new BigDecimal(qty));
        return line;
    }

    /* ==================== 测试子类与桩 ==================== */

    /** 过账引擎的测试子类（无需覆盖任何 protected 缝隙；负库存参数读不到即"不允许"）。 */
    static class TestJournalService extends ErpStockJournalServiceImpl
    {
    }

    /** 出库单服务的测试子类：固定单号、固定操作人上下文。 */
    static class TestStockOutService extends ErpStockOutServiceImpl
    {
        private int seq = 300;

        @Override
        protected String nextDocNo(Date docDate)
        {
            return "OUT202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return USER_ID;
        }

        @Override
        protected String currentDeptId()
        {
            return DEPT_ID;
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }
    }

    /** 销售订单服务的测试子类：固定上下文与单号。 */
    static class TestOrderService extends ErpSalesOrderServiceImpl
    {
        private int seq = 100;

        @Override
        protected String nextOrderDocNo(ErpSalesOrder doc)
        {
            return "SO202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return USER_ID;
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return DEPT_ID;
        }

        @Override
        protected Date now()
        {
            return new Date(1893456000000L);
        }

        @Override
        protected String newId()
        {
            return "OID-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    /** 销售申请单服务的测试子类。 */
    static class TestRequestService extends ErpSalesRequestServiceImpl
    {
        private int seq = 50;

        @Override
        protected String nextRequestDocNo(ErpSalesRequest doc)
        {
            return "SR202610" + (seq++);
        }

        @Override
        protected String currentUserId()
        {
            return USER_ID;
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return DEPT_ID;
        }

        @Override
        protected Date now()
        {
            return new Date(1893456000000L);
        }

        @Override
        protected String newId()
        {
            return "RID-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    /* ==================== 内存桩 Mapper ==================== */

    private ErpSalesRequestMapper requestMapperStub()
    {
        return new ErpSalesRequestMapper()
        {
            @Override
            public List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query)
            {
                return new ArrayList<>(requests.values());
            }

            @Override
            public ErpSalesRequest selectSalesRequestById(String id)
            {
                return requests.get(id);
            }

            @Override
            public int insertSalesRequest(ErpSalesRequest doc)
            {
                requests.put(doc.getId(), doc);
                requestItems.computeIfAbsent(doc.getId(), k -> new ArrayList<>());
                return 1;
            }

            @Override
            public int updateSalesRequest(ErpSalesRequest doc)
            {
                requests.put(doc.getId(), doc);
                return 1;
            }

            @Override
            public int updateSalesRequestStatus(ErpSalesRequest doc)
            {
                ErpSalesRequest stored = requests.get(doc.getId());
                if (stored != null)
                {
                    stored.setStatus(doc.getStatus());
                }
                return 1;
            }

            @Override
            public int deleteSalesRequestById(String id)
            {
                requests.remove(id);
                return 1;
            }

            @Override
            public String selectMaxDocNoByPrefix(String prefix)
            {
                return null;
            }

            @Override
            public int countRequestItems(String docId)
            {
                List<ErpSalesRequestItem> items = requestItems.get(docId);
                return items == null ? 0 : items.size();
            }
        };
    }

    private ErpSalesRequestItemMapper requestItemMapperStub()
    {
        return new ErpSalesRequestItemMapper()
        {
            @Override
            public List<ErpSalesRequestItem> selectItemsByDocId(String docId)
            {
                List<ErpSalesRequestItem> items = requestItems.get(docId);
                return items == null ? new ArrayList<ErpSalesRequestItem>() : items;
            }

            @Override
            public List<ErpSalesRequestItem> selectItemsByDocIds(List<String> docIds)
            {
                List<ErpSalesRequestItem> flat = new ArrayList<>();
                for (String docId : docIds)
                {
                    if (requestItems.get(docId) != null)
                    {
                        flat.addAll(requestItems.get(docId));
                    }
                }
                return flat;
            }

            @Override
            public List<ErpSalesRequestItem> selectItemsBySrcItemIds(List<String> srcItemIds)
            {
                return new ArrayList<>();
            }

            @Override
            public int deleteItemsByDocId(String docId)
            {
                requestItems.remove(docId);
                return 1;
            }

            @Override
            public int batchInsertItems(List<ErpSalesRequestItem> items)
            {
                for (ErpSalesRequestItem item : items)
                {
                    requestItems.computeIfAbsent(item.getDocId(), k -> new ArrayList<>()).add(item);
                }
                return items.size();
            }

            @Override
            public int batchUpsertItems(List<ErpSalesRequestItem> items)
            {
                return batchInsertItems(items);
            }

            @Override
            public int updateOrderedQty(String itemId, BigDecimal delta)
            {
                for (List<ErpSalesRequestItem> rows : requestItems.values())
                {
                    for (ErpSalesRequestItem row : rows)
                    {
                        if (itemId.equals(row.getId()))
                        {
                            row.addOrderedQty(delta);
                            return 1;
                        }
                    }
                }
                return 0;
            }

            @Override
            public ErpSalesRequestItem selectItemById(String id)
            {
                for (List<ErpSalesRequestItem> rows : requestItems.values())
                {
                    for (ErpSalesRequestItem row : rows)
                    {
                        if (id.equals(row.getId()))
                        {
                            return row;
                        }
                    }
                }
                return null;
            }
        };
    }

    private ErpSalesOrderMapper orderMapperStub()
    {
        return new ErpSalesOrderMapper()
        {
            @Override
            public List<ErpSalesOrder> selectSalesOrderList(ErpSalesOrder query)
            {
                return new ArrayList<>(orders.values());
            }

            @Override
            public ErpSalesOrder selectSalesOrderById(String id)
            {
                ErpSalesOrder order = orders.get(id);
                if (order != null)
                {
                    order.setItems(orderItems.get(id));
                }
                return order;
            }

            @Override
            public int insertSalesOrder(ErpSalesOrder doc)
            {
                orders.put(doc.getId(), doc);
                // ⚠ 刻意**不**在此预置行项：服务在 insertSalesOrder 里紧接着会调
                //    batchInsertItems（真库语义也是"表头插入 + 行项批量插入"）。
                //    这里若再 put 一份，行项就会被记两遍 ⇒ 下推时同一行被推两次
                //    （本测试类实测踩过：pushed=20 而 outQty=10）。
                orderItems.computeIfAbsent(doc.getId(), k -> new ArrayList<>());
                return 1;
            }

            @Override
            public int updateSalesOrder(ErpSalesOrder doc)
            {
                orders.put(doc.getId(), doc);
                return 1;
            }

            @Override
            public int updateSalesOrderStatus(ErpSalesOrder doc)
            {
                ErpSalesOrder stored = orders.get(doc.getId());
                if (stored != null)
                {
                    stored.setStatus(doc.getStatus());
                }
                return 1;
            }

            @Override
            public int deleteSalesOrderById(String id)
            {
                orders.remove(id);
                orderItems.remove(id);
                return 1;
            }

            @Override
            public String selectMaxDocNoByPrefix(String prefix)
            {
                return null;
            }

            @Override
            public int countOrderItems(String docId)
            {
                List<ErpSalesOrderItem> items = orderItems.get(docId);
                return items == null ? 0 : items.size();
            }

            @Override
            public List<ErpSalesOrder> selectOrdersBySourceDocId(String sourceDocId)
            {
                List<ErpSalesOrder> hit = new ArrayList<>();
                for (ErpSalesOrder order : orders.values())
                {
                    if (sourceDocId != null && sourceDocId.equals(order.getSourceDocId()))
                    {
                        hit.add(order);
                    }
                }
                return hit;
            }
        };
    }

    /**
     * 订单行项桩：{@code addShippedQty} / {@code subtractShippedQty} 与 XML 同语义
     * （累加；回退用 {@code max(0, …)} 夹住）。
     */
    private ErpSalesOrderItemMapper orderItemMapperStub()
    {
        return new ErpSalesOrderItemMapper()
        {
            @Override
            public List<ErpSalesOrderItem> selectItemsByDocId(String docId)
            {
                List<ErpSalesOrderItem> items = orderItems.get(docId);
                return items == null ? new ArrayList<ErpSalesOrderItem>() : items;
            }

            @Override
            public List<ErpSalesOrderItem> selectItemsByDocIds(List<String> docIds)
            {
                List<ErpSalesOrderItem> flat = new ArrayList<>();
                for (String docId : docIds)
                {
                    if (orderItems.get(docId) != null)
                    {
                        flat.addAll(orderItems.get(docId));
                    }
                }
                return flat;
            }

            @Override
            public List<ErpSalesOrderItem> selectItemsBySrcItemIds(List<String> srcItemIds)
            {
                return new ArrayList<>();
            }

            @Override
            public int deleteItemsByDocId(String docId)
            {
                orderItems.remove(docId);
                return 1;
            }

            @Override
            public int batchInsertItems(List<ErpSalesOrderItem> items)
            {
                for (ErpSalesOrderItem item : items)
                {
                    orderItems.computeIfAbsent(item.getDocId(), k -> new ArrayList<>()).add(item);
                }
                return items.size();
            }

            @Override
            public int batchUpsertItems(List<ErpSalesOrderItem> items)
            {
                return batchInsertItems(items);
            }

            @Override
            public ErpSalesOrderItem selectItemById(String id)
            {
                for (List<ErpSalesOrderItem> rows : orderItems.values())
                {
                    for (ErpSalesOrderItem row : rows)
                    {
                        if (id.equals(row.getId()))
                        {
                            return row;
                        }
                    }
                }
                return null;
            }

            @Override
            public int addShippedQty(String itemId, BigDecimal delta)
            {
                ErpSalesOrderItem item = selectItemById(itemId);
                if (item == null)
                {
                    return 0;
                }
                item.addShippedQty(delta);
                return 1;
            }

            @Override
            public int subtractShippedQty(String itemId, BigDecimal delta)
            {
                ErpSalesOrderItem item = selectItemById(itemId);
                if (item == null)
                {
                    return 0;
                }
                // 与 XML 的 greatest(0, shipped_qty - delta) 同语义
                item.subtractShippedQty(delta);
                return 1;
            }
        };
    }

    private CtmsChangeLogMapper changeLogMapperStub()
    {
        return new CtmsChangeLogMapper()
        {
            @Override
            public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
            {
                List<CtmsChangeLog> hit = new ArrayList<>();
                for (CtmsChangeLog log : changeLogs)
                {
                    if (query.getObjectId() != null && query.getObjectId().equals(log.getObjectId()))
                    {
                        hit.add(log);
                    }
                }
                return hit;
            }

            @Override
            public int batchInsertChangeLogs(List<CtmsChangeLog> logs)
            {
                changeLogs.addAll(logs);
                return logs.size();
            }

            @Override
            public int insertChangeLog(CtmsChangeLog log)
            {
                changeLogs.add(log);
                return 1;
            }
        };
    }

    private CtmsPartnerMapper partnerMapperStub()
    {
        return new CtmsPartnerMapper()
        {
            @Override
            public List<com.ruoyi.ctms.domain.CtmsCustomer> selectCustomerList(com.ruoyi.ctms.domain.CtmsCustomer q)
            {
                return new ArrayList<>();
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsCustomer selectCustomerById(String id)
            {
                if (!CUSTOMER_ID.equals(id))
                {
                    return null;
                }
                com.ruoyi.ctms.domain.CtmsCustomer c = new com.ruoyi.ctms.domain.CtmsCustomer();
                c.setId(CUSTOMER_ID);
                c.setCode(CUSTOMER_ID);
                c.setName(CUSTOMER_NAME);
                c.setEnableFlag("1");
                return c;
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsCustomer selectCustomerByCode(String code)
            {
                return null;
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsCustomer selectCustomerByName(String name)
            {
                return null;
            }

            @Override
            public int insertCustomer(com.ruoyi.ctms.domain.CtmsCustomer c)
            {
                return 0;
            }

            @Override
            public int updateCustomer(com.ruoyi.ctms.domain.CtmsCustomer c)
            {
                return 0;
            }

            @Override
            public int deleteCustomerById(String id)
            {
                return 0;
            }

            @Override
            public int countCustomerReferences(String id)
            {
                return 0;
            }

            @Override
            public List<com.ruoyi.ctms.domain.CtmsSupplier> selectSupplierList(com.ruoyi.ctms.domain.CtmsSupplier q)
            {
                return new ArrayList<>();
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsSupplier selectSupplierById(String id)
            {
                return null;
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsSupplier selectSupplierByCode(String code)
            {
                return null;
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsSupplier selectSupplierByName(String name)
            {
                return null;
            }

            @Override
            public int insertSupplier(com.ruoyi.ctms.domain.CtmsSupplier s)
            {
                return 0;
            }

            @Override
            public int updateSupplier(com.ruoyi.ctms.domain.CtmsSupplier s)
            {
                return 0;
            }

            @Override
            public int deleteSupplierById(String id)
            {
                return 0;
            }

            @Override
            public int countSupplierReferences(String id)
            {
                return 0;
            }

            @Override
            public int existsContractTable()
            {
                return 1;
            }
        };
    }

    private CtmsContractMapper contractMapperStub()
    {
        return new CtmsContractMapper()
        {
            @Override
            public List<com.ruoyi.ctms.domain.CtmsContract> selectContractList(
                    com.ruoyi.ctms.domain.CtmsContract query)
            {
                return new ArrayList<>();
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsContract selectContractById(String id)
            {
                return null;
            }

            @Override
            public com.ruoyi.ctms.domain.CtmsContract selectContractByNo(String contractNo)
            {
                return null;
            }

            @Override
            public int insertContract(com.ruoyi.ctms.domain.CtmsContract contract)
            {
                throw new UnsupportedOperationException("销售线不得写入合同");
            }

            @Override
            public int updateContract(com.ruoyi.ctms.domain.CtmsContract contract)
            {
                throw new UnsupportedOperationException("销售线不得回写合同");
            }

            @Override
            public int updateContractDelFlag(com.ruoyi.ctms.domain.CtmsContract contract)
            {
                throw new UnsupportedOperationException("销售线不得停用合同");
            }

            @Override
            public int restoreContract(String id, String updateId, String updateBy)
            {
                throw new UnsupportedOperationException("销售线不得恢复合同");
            }

            @Override
            public int deleteContractById(String id)
            {
                throw new UnsupportedOperationException("销售线不得删除合同");
            }

            @Override
            public List<com.ruoyi.ctms.domain.CtmsContract> selectChildren(String parentId)
            {
                return new ArrayList<>();
            }

            @Override
            public int countActiveChildren(String parentId)
            {
                return 0;
            }

            @Override
            public int countContractReferences(String tagId)
            {
                return 0;
            }

            @Override
            public List<com.ruoyi.ctms.domain.CtmsContract> selectWarrantyReminderCandidates(Date windowEnd,
                                                                                             String dataScopeSql, int limit)
            {
                return new ArrayList<>();
            }

            @Override
            public List<String> selectAllContractNos()
            {
                return new ArrayList<>();
            }

            @Override
            public List<com.ruoyi.ctms.domain.CtmsContract> selectUnboundPartyCandidates(String partyType,
                                                                                          String rawName)
            {
                return new ArrayList<>();
            }

            @Override
            public int bindPartyRef(String id, String partyType, String partyId, String updateId, String updateBy)
            {
                throw new UnsupportedOperationException("销售线不得回写合同");
            }
        };
    }

    /**
     * 造一条"已审核 + 指定发货仓库"的销售订单用于下游守卫用例，返回其 id。
     *
     * @return 订单ID
     */
    private String approvedOrderForGuard()
    {
        ErpSalesOrder order = order("P1", "10", "3");
        order.setShipWarehouseId(WAREHOUSE_ID);
        order.setCustomerId(CUSTOMER_ID);
        order.setCustomerName(CUSTOMER_NAME);
        String orderId = saveOrder(order);
        orderService.submitSalesOrder(orderId, null);
        orderService.approveSalesOrder(orderId);
        return orderId;
    }

    /**
     * 造一条"直接回调监听器"用的过账行集合。
     *
     * @param srcItemId 来源订单行项ID
     * @param qtyChange 流水数量变动（出库为负）
     * @return 过账行集合
     */
    private static List<ErpPostedLine> posted(String srcItemId, String qtyChange)
    {
        ErpPostedLine line = new ErpPostedLine();
        line.setSrcItemId(srcItemId);
        line.setQtyChange(new BigDecimal(qtyChange));
        return Collections.singletonList(line);
    }
}