package com.ruoyi.ctms.erp.sales.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
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
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;
import com.ruoyi.ctms.erp.sales.service.IErpSalesRequestService;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;
import com.ruoyi.system.service.ISysDictTypeService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> B4 销售线（t5a）的单测：<b>内存桩 Mapper + 不起 Spring</b>。 </p>
 *
 * <p> 逐条对应 `tasks.md` §4.1 / §4.2 / §4.4 的验证方式与规格 {@code erp/sales} 的场景： </p>
 * <ul>
 *   <li> §4.1：无行项提交被拒、数量为 0 被拒、单位 0 位小数填 1.5 被拒、三行 0.125 合计 0.39、
 *        停用物料拦截、行项快照落库、传供应商 id 到客户字段被拒、停用客户拒存、发货信息保存； </li>
 *   <li> §4.2：未审核不可推、超量下推被拒且已下量不变、不填数量按剩余量全推、写来源与来源行、
 *        归零即完成且幂等（重复判定不重复留痕）、只推完一行不置完成； </li>
 *   <li> §4.4：关联采购方向合同被拒、关联销售方向合同成功且写编号快照、合同记录不被修改。 </li>
 * </ul>
 *
 * <p> <b>为什么用真实规则而不是 Mockito</b>：本线的核心口径（金额先舍入再汇总、剩余量、关守卫）
 * 全在纯规则与公共层里，桩只负责"存取"；这样断言的是真实计算路径，而不是被 mock 掉的口径。 </p>
 *
 * @author 二开
 */
public class ErpSalesServiceImplTest
{
    /* ==================== 夹具 ==================== */

    private static final String CUSTOMER_OK = "CUST-OK";

    private static final String CUSTOMER_DISABLED = "CUST-DIS";

    private static final String SUPPLIER_ID = "SUP-1";

    private static final String SALE_CONTRACT = "CON-SALE";

    private static final String PURCHASE_CONTRACT = "CON-PUR";

    private static final String DEPT_ID = "100";

    private static final String USER_ID = "9";

    /** 请求单表（内存）。 */
    private final Map<String, ErpSalesRequest> requests = new LinkedHashMap<>();

    /** 请求行项（按单据ID分组，内存）。 */
    private final Map<String, List<ErpSalesRequestItem>> requestItems = new LinkedHashMap<>();

    /** 订单表（内存）。 */
    private final Map<String, ErpSalesOrder> orders = new LinkedHashMap<>();

    /** 订单行项（内存）。 */
    private final Map<String, List<ErpSalesOrderItem>> orderItems = new LinkedHashMap<>();

    /** 变更历史（内存，只追加）。 */
    private final List<CtmsChangeLog> changeLogs = new ArrayList<>();

    /** 客户档案（内存）。 */
    private final Map<String, CtmsCustomer> customers = new HashMap<>();

    /** 供应商档案（内存）。 */
    private final Map<String, CtmsSupplier> suppliers = new HashMap<>();

    /** 合同档案（内存）。 */
    private final Map<String, CtmsContract> contracts = new HashMap<>();

    /** 物料档案（内存）。 */
    private final Map<String, CtmsProduct> products = new HashMap<>();

    /** 计量单位（内存）。 */
    private final Map<String, CtmsUom> uoms = new HashMap<>();

    /** 字典：contract_types（内存）。 */
    private final List<SysDictData> contractTypes = new ArrayList<>();

    private TestRequestService requestService;

    private TestOrderService orderService;

    private ErpSalesPushServiceImpl pushService;

    /* ==================== 每个用例重建夹具（互不污染） ==================== */

    @Before
    public void setUp()
    {
        requests.clear();
        requestItems.clear();
        orders.clear();
        orderItems.clear();
        changeLogs.clear();
        customers.clear();
        suppliers.clear();
        contracts.clear();
        products.clear();
        uoms.clear();
        contractTypes.clear();

        // 客户：一个启用、一个停用
        customers.put(CUSTOMER_OK, customer("CUST-OK", "启用客户", "1"));
        customers.put(CUSTOMER_DISABLED, customer("CUST-DIS", "停用客户", "0"));
        // 供应商：仅用于"客户字段误传供应商"的排他性用例
        CtmsSupplier supplier = new CtmsSupplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setCode("SUP-1");
        supplier.setName("某供应商");
        supplier.setShortName("某供");
        supplier.setEnableFlag("1");
        suppliers.put(SUPPLIER_ID, supplier);

        // 合同：销售方向 + 采购方向
        contracts.put(SALE_CONTRACT, contract(SALE_CONTRACT, "SAL-2026-001", "销售/收入"));
        contracts.put(PURCHASE_CONTRACT, contract(PURCHASE_CONTRACT, "PUR-2026-001", "采购/支出"));

        // 字典：contract_types（value → label）
        contractTypes.add(dict("销售/收入", "SAL"));
        contractTypes.add(dict("采购/支出", "PUR"));

        // 物料与计量单位（见 seedProducts 的说明）
        seedProducts();

        requestService = new TestRequestService();
        requestService.setDocNoPort(new ErpSalesRequestServiceImpl.DocNoPort()
        {
            private int seq = 0;

            @Override
            public String nextDocNo(ErpDocType docType)
            {
                seq++;
                return "SR20261000000" + seq;
            }
        });
        orderService = new TestOrderService();
        orderService.setDocNoPort(new ErpSalesOrderServiceImpl.DocNoPort()
        {
            private int seq = 0;

            @Override
            public String nextDocNo(ErpDocType docType)
            {
                seq++;
                return "SO20261000000" + seq;
            }
        });
        pushService = new ErpSalesPushServiceImpl(orderService);
        pushService.setRequestMapper(requestMapperStub());
        pushService.setRequestItemMapper(requestItemMapperStub());
        pushService.setChangeLogMapper(changeLogMapperStub());
    }

    /* ==================== §4.1 行项与金额口径 ==================== */

    @Test
    public void submitWithoutItemsIsRejected()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "1", "1"));
        requestService.insertSalesRequest(doc);
        // 把行项清掉：模拟"单据没有行项"（提交守卫的唯一判据是 countRequestItems）
        requestItems.get(doc.getId()).clear();

        try
        {
            requestService.submitSalesRequest(doc.getId(), null);
            fail("没有行项的销售申请单不应可提交");
        }
        catch (ServiceException e)
        {
            assertEquals("单据没有行项，不能提交", e.getMessage());
        }
        assertEquals(statusDraft(), requests.get(doc.getId()).getStatus());
    }

    @Test
    public void zeroQuantityIsRejected()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "0", "1"));
        try
        {
            requestService.insertSalesRequest(doc);
            fail("数量为 0 的行项不应可保存");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("数量必须大于 0"));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    public void quantityScaleBeyondUomDecimalsIsRejected()
    {
        // 单位"个"0 位小数：填 1.5 必须被拒（提示带行号）
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "1.5", "1"));
        try
        {
            requestService.insertSalesRequest(doc);
            fail("单位 0 位小数时填 1.5 不应可保存");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("行 1"));
            assertTrue(e.getMessage(), e.getMessage().contains("0 位小数"));
        }
    }

    @Test
    public void threeLineRoundThenSumMatchesProcurement()
    {
        // 规格场景：3 行「1 × 0.125」→ 各行 0.13、合计 0.39（不是 0.38）
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "0.125"),
                orderItem("P2", "1", "0.125"), orderItem("P3", "1", "0.125"));
        ErpSalesOrder saved = orderService.insertSalesOrder(doc);

        List<ErpSalesOrderItem> items = orderItems.get(saved.getId());
        assertEquals(3, items.size());
        for (ErpSalesOrderItem item : items)
        {
            assertEquals(new BigDecimal("0.13"), item.getAmount());
        }
        // 正确口径：0.13 × 3 = 0.39
        assertEquals(new BigDecimal("0.39"), saved.getTotalAmount());
        // 反例（锁死口径）：3 × 0.125 = 0.375 先汇总再舍入会得到 0.38
        assertEquals(0, new BigDecimal("0.38")
                .compareTo(new BigDecimal("0.375").setScale(2, java.math.RoundingMode.HALF_UP)));
        assertTrue("合计不得等于先汇总再舍入的 0.38",
                saved.getTotalAmount().compareTo(new BigDecimal("0.38")) != 0);
    }

    @Test
    public void disabledProductIsRejectedWithRowNo()
    {
        products.get("P1").setEnableFlag("0");
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "1", "1"));
        try
        {
            requestService.insertSalesRequest(doc);
            fail("停用物料不应可用于新行项");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("行 1"));
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
        }
    }

    @Test
    public void itemSnapshotIsWrittenAtSave()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "2", "3"));
        ErpSalesRequest saved = requestService.insertSalesRequest(doc);

        ErpSalesRequestItem row = requestItems.get(saved.getId()).get(0);
        assertEquals("P001", row.getProductCode());
        assertEquals("螺丝", row.getProductName());
        assertEquals("M6", row.getSpec());
        assertEquals("个", row.getUomName());
        assertEquals(Integer.valueOf(0), row.getUomDecimals());
        assertEquals(new BigDecimal("6.00"), row.getAmount());
        assertEquals(Integer.valueOf(1), row.getSeq());
    }

    /* ==================== §4.1 客户引用与发货信息 ==================== */

    @Test
    public void supplierIdInCustomerFieldIsRejected()
    {
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        doc.setCustomerId(SUPPLIER_ID);
        try
        {
            orderService.insertSalesOrder(doc);
            fail("把供应商 id 传到客户字段不应可保存");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("客户档案不存在"));
            assertTrue(e.getMessage(), e.getMessage().contains("供应商档案"));
        }
        assertTrue(orders.isEmpty());
    }

    @Test
    public void disabledCustomerIsRejected()
    {
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        doc.setCustomerId(CUSTOMER_DISABLED);
        try
        {
            orderService.insertSalesOrder(doc);
            fail("停用客户不应可用于新销售订单");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
            assertTrue(e.getMessage(), e.getMessage().contains("停用客户"));
        }
        assertTrue(orders.isEmpty());
    }

    @Test
    public void shippingInfoAndCustomerSnapshotAreSaved()
    {
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        doc.setDeliveryAddress("上海市浦东新区 1 号");
        doc.setContactName("张三");
        doc.setContactPhone("13800000000");
        doc.setShipWarehouseId("WH-1");
        doc.setDeliveryDate(new Date(1893456000000L));
        ErpSalesOrder saved = orderService.insertSalesOrder(doc);

        ErpSalesOrder stored = orders.get(saved.getId());
        assertEquals("启用客户", stored.getCustomerName());
        assertEquals("上海市浦东新区 1 号", stored.getDeliveryAddress());
        assertEquals("张三", stored.getContactName());
        assertEquals("13800000000", stored.getContactPhone());
        assertEquals("WH-1", stored.getShipWarehouseId());
        assertNotNull(stored.getDeliveryDate());
        // 草稿状态 + 单号由编号端口生成
        assertEquals(ErpDocStatus.DRAFT, stored.getStatus());
        assertTrue(stored.getDocNo().startsWith("SO202610"));
    }

    /* ==================== §4.2 下推：仅已审核可推 ==================== */

    @Test
    public void unauditedRequestCannotBePushed()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "80", "1"));
        requestService.insertSalesRequest(doc);
        try
        {
            pushService.pushSalesRequestToOrder(doc.getId(), null);
            fail("草稿状态的销售申请单不应可下推");
        }
        catch (ServiceException e)
        {
            assertEquals("仅已审核的销售申请单可以下推销售订单", e.getMessage());
        }
        assertTrue(orders.isEmpty());
    }

    @Test
    public void overPushIsRejectedAndOrderedQtyUnchanged()
    {
        // tasks.md §4.2 的判别用例：原始 80、已下单 30、本次 60 → 被拒且已下量仍为 30
        ErpSalesRequest doc = request(DEPT_ID, ErpDocStatus.APPROVED, item("P1", "80", "1"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);
        requestItems.get(doc.getId()).get(0).setOrderedQty(new BigDecimal("30"));

        ErpSalesPushRequest payload = new ErpSalesPushRequest();
        payload.setLines(singleLine(requestItems.get(doc.getId()).get(0).getId(), new BigDecimal("60")));
        try
        {
            pushService.pushSalesRequestToOrder(doc.getId(), payload);
            fail("本次下推 60 超过剩余 50，不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("可下推数量不足"));
            assertTrue(e.getMessage(), e.getMessage().contains("剩余 50"));
            assertTrue(e.getMessage(), e.getMessage().contains("本次 60"));
        }
        assertEquals(0, new BigDecimal("30").compareTo(requestItems.get(doc.getId()).get(0).getOrderedQty()));
        assertTrue(orders.isEmpty());
        assertTrue(changeLogs.isEmpty());
    }

    @Test
    public void pushWithoutQtyPushesRemainingAmount()
    {
        // 不填数量 = 按剩余量全推：原始 100、已下单 60 → 推 40 后已下量为 100（与采购线 §3.3 同款）
        ErpSalesRequest doc = request(DEPT_ID, ErpDocStatus.APPROVED, item("P1", "100", "2"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);
        requestItems.get(doc.getId()).get(0).setOrderedQty(new BigDecimal("60"));

        Map<String, Object> result = pushService.pushSalesRequestToOrder(doc.getId(), null);

        assertNotNull(result.get("orderId"));
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) result.get("pushedQtySum")));
        assertEquals("pushedSum=" + result.get("pushedQtySum") + " ordered="
                        + requestItems.get(doc.getId()).get(0).getOrderedQty()
                        + " qty=" + requestItems.get(doc.getId()).get(0).getQty(),
                0, new BigDecimal("100").compareTo(requestItems.get(doc.getId()).get(0).getOrderedQty()));
        assertEquals(Boolean.TRUE, result.get("requestCompleted"));
    }

    @Test
    public void pushWritesSourceAndSrcItemId()
    {
        ErpSalesRequest doc = request(DEPT_ID, ErpDocStatus.APPROVED, item("P1", "10", "2"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);
        String srcItemId = requestItems.get(doc.getId()).get(0).getId();

        Map<String, Object> result = pushService.pushSalesRequestToOrder(doc.getId(), null);
        String orderId = (String) result.get("orderId");

        ErpSalesOrder created = orders.get(orderId);
        assertEquals(ErpDocStatus.DRAFT, created.getStatus());
        assertEquals(ErpDocType.SALES_REQUEST.getCode(), created.getSourceDocType());
        assertEquals(doc.getId(), created.getSourceDocId());
        assertEquals(doc.getDocNo(), created.getSourceDocNo());
        assertEquals(CUSTOMER_OK, created.getCustomerId());
        assertEquals("启用客户", created.getCustomerName());
        List<ErpSalesOrderItem> rows = orderItems.get(orderId);
        assertEquals(1, rows.size());
        assertEquals(srcItemId, rows.get(0).getSrcItemId());
        assertEquals(0, new BigDecimal("10").compareTo(rows.get(0).getQty()));
        assertEquals("P001", rows.get(0).getProductCode());
    }

    /* ==================== §4.2 归零即完成 ==================== */

    @Test
    public void pushingOneOfTwoLinesDoesNotComplete()
    {
        ErpSalesRequest doc = request(DEPT_ID, ErpDocStatus.APPROVED, item("P1", "10", "1"), item("P2", "5", "1"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);
        List<ErpSalesRequestItem> items = requestItems.get(doc.getId());

        ErpSalesPushRequest payload = new ErpSalesPushRequest();
        payload.setLines(singleLine(items.get(0).getId(), new BigDecimal("10")));
        Map<String, Object> result = pushService.pushSalesRequestToOrder(doc.getId(), payload);

        assertEquals(Boolean.FALSE, result.get("requestCompleted"));
        assertEquals(ErpDocStatus.APPROVED, requests.get(doc.getId()).getStatus());
        assertTrue(changeLogs.isEmpty());
    }

    @Test
    public void allLinesPushedCompletesRequestAndLogsOnce()
    {
        ErpSalesRequest doc = request(DEPT_ID, ErpDocStatus.APPROVED, item("P1", "10", "1"), item("P2", "5", "1"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);

        Map<String, Object> result = pushService.pushSalesRequestToOrder(doc.getId(), null);
        assertEquals(Boolean.TRUE, result.get("requestCompleted"));
        assertEquals(ErpDocStatus.COMPLETED, requests.get(doc.getId()).getStatus());
        assertEquals(1, changeLogs.size());
        CtmsChangeLog log = changeLogs.get(0);
        assertEquals("status", log.getFieldName());
        assertEquals(ErpDocStatus.APPROVED, log.getOldValue());
        assertEquals(ErpDocStatus.COMPLETED, log.getNewValue());
        assertEquals("auto", log.getSource());
        assertEquals(ErpDocType.SALES_REQUEST.getCode(), log.getObjectType());
        assertEquals(doc.getId(), log.getObjectId());

        // 幂等：重复判定不再留痕、不再改状态
        assertFalse(pushService.checkAutoComplete(doc.getId()));
        assertEquals(1, changeLogs.size());
        assertEquals(ErpDocStatus.COMPLETED, requests.get(doc.getId()).getStatus());
    }

    @Test
    public void contractDirectionResolvesSaleAndPurchase()
    {
        // ① 桩自身：确认 map 与 mapper 口径一致（若这里就红，问题在夹具而非服务）
        CtmsContract direct = contractMapperStub().selectContractById(SALE_CONTRACT);
        assertNotNull("stub returned null for " + SALE_CONTRACT, direct);
        assertEquals("销售/收入", direct.getType());

        // ② 类型 → 方向标签（合同表存中文类型名；SAL 这类 dict_value 也要认）
        assertEquals("销售/收入", orderService.contractTypeLabelOf("销售/收入"));
        assertEquals("销售/收入", orderService.contractTypeLabelOf("SAL"));
        assertEquals("采购/支出", orderService.contractTypeLabelOf("采购/支出"));

        CtmsContract sale = orderService.requireSaleContract(SALE_CONTRACT);
        assertEquals("SAL-2026-001", sale.getContractNo());

        try
        {
            orderService.requireSaleContract(PURCHASE_CONTRACT);
            fail("采购方向合同不应通过 requireSaleContract");
        }
        catch (ServiceException e)
        {
            assertTrue("actual=" + e.getMessage(), e.getMessage().contains("只能关联销售方向的合同"));
        }
    }

    /* ==================== §4.4 关联合同 ==================== */

    @Test
    public void purchaseDirectionContractIsRejected()
    {
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        ErpSalesOrder saved = orderService.insertSalesOrder(doc);
        CtmsContract before = contracts.get(PURCHASE_CONTRACT);
        String beforeType = before.getType();
        String beforeNo = before.getContractNo();

        try
        {
            orderService.linkContract(saved.getId(), PURCHASE_CONTRACT);
            fail("销售订单不应可关联采购方向合同");
        }
        catch (ServiceException e)
        {
            assertTrue("actual=" + e.getMessage() + " found="
                            + (contracts.get(PURCHASE_CONTRACT) == null ? "NULL"
                                    : contracts.get(PURCHASE_CONTRACT).getType()),
                    e.getMessage().contains("只能关联销售方向的合同"));
        }
        assertNull(orders.get(saved.getId()).getContractId());
        assertNull(orders.get(saved.getId()).getContractNo());
        // 合同记录未被修改
        assertEquals(beforeType, contracts.get(PURCHASE_CONTRACT).getType());
        assertEquals(beforeNo, contracts.get(PURCHASE_CONTRACT).getContractNo());
    }

    @Test
    public void saleDirectionContractIsLinkedWithNoSnapshotBackWrite()
    {
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        ErpSalesOrder saved = orderService.insertSalesOrder(doc);
        String beforeType = contracts.get(SALE_CONTRACT).getType();

        String contractNo = orderService.linkContract(saved.getId(), SALE_CONTRACT);

        assertEquals("SAL-2026-001", contractNo);
        assertEquals(SALE_CONTRACT, orders.get(saved.getId()).getContractId());
        assertEquals("SAL-2026-001", orders.get(saved.getId()).getContractNo());
        // 合同记录未被修改（"关联不回写合同"）
        assertEquals(beforeType, contracts.get(SALE_CONTRACT).getType());
        assertEquals(1, changeLogs.size());
        assertEquals("关联合同", changeLogs.get(0).getFieldName());
        assertEquals("SAL-2026-001", changeLogs.get(0).getNewValue());
    }

    @Test
    public void disabledContractIsRejected()
    {
        contracts.get(SALE_CONTRACT).setDelFlag("1");
        ErpSalesOrder doc = order(DEPT_ID, orderItem("P1", "1", "1"));
        ErpSalesOrder saved = orderService.insertSalesOrder(doc);
        try
        {
            orderService.linkContract(saved.getId(), SALE_CONTRACT);
            fail("已停用的合同不应可关联");
        }
        catch (ServiceException e)
        {
            assertEquals("关联合同不存在或已停用", e.getMessage());
        }
    }

    /* ==================== 表头与状态机（补充断言） ==================== */

    @Test
    public void missingCreateSnapshotIsRejected()
    {
        requestService.setContext(null, null);
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "1", "1"));
        try
        {
            requestService.insertSalesRequest(doc);
            fail("无登录上下文（取不到创建人/部门）时不应可创建单据");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("创建人"));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    public void draftOnlyIsEditable()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "5", "1"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.SUBMITTED);

        ErpSalesRequest edit = request(DEPT_ID, statusDraft(), item("P1", "7", "1"));
        edit.setId(doc.getId());
        try
        {
            requestService.updateSalesRequest(edit);
            fail("非草稿状态不应可编辑");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("不可编辑"));
        }
    }

    @Test
    public void unapproveRequiresReason()
    {
        ErpSalesRequest doc = request(DEPT_ID, statusDraft(), item("P1", "5", "1"));
        requestService.insertSalesRequest(doc);
        requests.get(doc.getId()).setStatus(ErpDocStatus.APPROVED);
        try
        {
            requestService.unapproveSalesRequest(doc.getId(), null);
            fail("反审核未填原因不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("反审核原因必填", e.getMessage());
        }
        assertEquals(ErpDocStatus.APPROVED, requests.get(doc.getId()).getStatus());
    }

    /* ==================== 桩与夹具 ==================== */

    private static String statusDraft()
    {
        return ErpDocStatus.DRAFT;
    }

    private ErpSalesRequest request(String deptId, String status, ErpSalesRequestItem... items)
    {
        ErpSalesRequest doc = new ErpSalesRequest();
        doc.setDocDate(new Date(1893456000000L));
        doc.setStatus(status);
        doc.setDeptId(deptId);
        doc.setCustomerId(CUSTOMER_OK);
        doc.setItems(new ArrayList<>(java.util.Arrays.asList(items)));
        return doc;
    }

    private ErpSalesOrder order(String deptId, ErpSalesOrderItem... items)
    {
        ErpSalesOrder doc = new ErpSalesOrder();
        doc.setDocDate(new Date(1893456000000L));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDeptId(deptId);
        doc.setCustomerId(CUSTOMER_OK);
        doc.setItems(new ArrayList<>(java.util.Arrays.asList(items)));
        return doc;
    }

    private ErpSalesRequestItem item(String productId, String qty, String price)
    {
        ErpSalesRequestItem row = new ErpSalesRequestItem();
        row.setProductId(productId);
        row.setQty(new BigDecimal(qty));
        row.setUnitPrice(new BigDecimal(price));
        return row;
    }

    private ErpSalesOrderItem orderItem(String productId, String qty, String price)
    {
        ErpSalesOrderItem row = new ErpSalesOrderItem();
        row.setProductId(productId);
        row.setQty(new BigDecimal(qty));
        row.setUnitPrice(new BigDecimal(price));
        return row;
    }

    private List<ErpSalesPushLine> singleLine(String docItemId, BigDecimal qty)
    {
        ErpSalesPushLine line = new ErpSalesPushLine();
        line.setDocItemId(docItemId);
        line.setQty(qty);
        List<ErpSalesPushLine> lines = new ArrayList<>();
        lines.add(line);
        return lines;
    }

    private static CtmsCustomer customer(String id, String name, String enableFlag)
    {
        CtmsCustomer c = new CtmsCustomer();
        c.setId(id);
        c.setCode(id);
        c.setName(name);
        c.setEnableFlag(enableFlag);
        return c;
    }

    private static CtmsContract contract(String id, String no, String type)
    {
        CtmsContract c = new CtmsContract();
        c.setId(id);
        c.setContractNo(no);
        c.setType(type);
        c.setDelFlag("0");
        return c;
    }

    private static SysDictData dict(String label, String value)
    {
        SysDictData data = new SysDictData();
        data.setDictLabel(label);
        data.setDictValue(value);
        data.setDictType("contract_types");
        return data;
    }

    /* ==================== 测试子类（固定上下文，绕开 Spring 与库） ==================== */

    /**
     * 销售申请服务的测试子类：注入内存桩 Mapper、固定时间与登录上下文。
     */
    private class TestRequestService extends ErpSalesRequestServiceImpl
    {
        TestRequestService()
        {
            setRequestMapper(requestMapperStub());
            setRequestItemMapper(requestItemMapperStub());
            setPartnerMapper(partnerMapperStub());
            setContractMapper(contractMapperStub());
            setChangeLogMapper(changeLogMapperStub());
            setDictTypeService(dictServiceStub());
            setMasterLookup(masterLookupStub());
        }

        void setContext(String userId, String deptId)
        {
            this.userId = userId;
            this.deptId = deptId;
        }

        private String userId = USER_ID;

        private String deptId = DEPT_ID;

        @Override
        protected String currentUserId()
        {
            return userId;
        }

        @Override
        protected String currentUsername()
        {
            return "tester";
        }

        @Override
        protected String currentDeptId()
        {
            return deptId;
        }

        @Override
        protected Date now()
        {
            return new Date(1893456000000L);
        }

        @Override
        protected String newId()
        {
            return "ID-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    /**
     * 销售订单服务的测试子类。
     */
    private class TestOrderService extends ErpSalesOrderServiceImpl
    {
        TestOrderService()
        {
            setOrderMapper(orderMapperStub());
            setOrderItemMapper(orderItemMapperStub());
            setPartnerMapper(partnerMapperStub());
            setContractMapper(contractMapperStub());
            setChangeLogMapper(changeLogMapperStub());
            setDictTypeService(dictServiceStub());
            setMasterLookup(masterLookupStub());
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
                    stored.setUpdateId(doc.getUpdateId());
                    stored.setUpdateBy(doc.getUpdateBy());
                }
                return 1;
            }

            @Override
            public int deleteSalesRequestById(String id)
            {
                requests.remove(id);
                requestItems.remove(id);
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
                    List<ErpSalesRequestItem> items = requestItems.get(docId);
                    if (items != null)
                    {
                        flat.addAll(items);
                    }
                }
                return flat;
            }

            @Override
            public List<ErpSalesRequestItem> selectItemsBySrcItemIds(List<String> srcItemIds)
            {
                List<ErpSalesRequestItem> hit = new ArrayList<>();
                for (List<ErpSalesRequestItem> rows : requestItems.values())
                {
                    for (ErpSalesRequestItem row : rows)
                    {
                        if (srcItemIds.contains(row.getId()))
                        {
                            hit.add(row);
                        }
                    }
                }
                return hit;
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
                for (ErpSalesRequestItem item : items)
                {
                    List<ErpSalesRequestItem> rows = requestItems
                            .computeIfAbsent(item.getDocId(), k -> new ArrayList<>());
                    int index = indexOf(rows, item.getId());
                    if (index >= 0)
                    {
                        // 与 XML 的 on duplicate key update 同口径：ordered_qty 不被覆盖
                        ErpSalesRequestItem old = rows.get(index);
                        item.setOrderedQty(old.getOrderedQty());
                        rows.set(index, item);
                    }
                    else
                    {
                        rows.add(item);
                    }
                }
                return items.size();
            }

            @Override
            public int updateOrderedQty(String itemId, BigDecimal delta)
            {
                for (List<ErpSalesRequestItem> rows : requestItems.values())
                {
                    int index = indexOf(rows, itemId);
                    if (index >= 0)
                    {
                        rows.get(index).addOrderedQty(delta);
                        return 1;
                    }
                }
                return 0;
            }

            @Override
            public ErpSalesRequestItem selectItemById(String id)
            {
                for (List<ErpSalesRequestItem> rows : requestItems.values())
                {
                    int index = indexOf(rows, id);
                    if (index >= 0)
                    {
                        return rows.get(index);
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
                return orders.get(id);
            }

            @Override
            public int insertSalesOrder(ErpSalesOrder doc)
            {
                orders.put(doc.getId(), doc);
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
                    List<ErpSalesOrderItem> items = orderItems.get(docId);
                    if (items != null)
                    {
                        flat.addAll(items);
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
                for (ErpSalesOrderItem item : items)
                {
                    List<ErpSalesOrderItem> rows = orderItems
                            .computeIfAbsent(item.getDocId(), k -> new ArrayList<>());
                    int index = indexOfOrder(rows, item.getId());
                    if (index >= 0)
                    {
                        // 与 XML 同口径：shipped_qty 不被覆盖
                        ErpSalesOrderItem old = rows.get(index);
                        item.setShippedQty(old.getShippedQty());
                        rows.set(index, item);
                    }
                    else
                    {
                        rows.add(item);
                    }
                }
                return items.size();
            }

            @Override
            public ErpSalesOrderItem selectItemById(String id)
            {
                for (List<ErpSalesOrderItem> rows : orderItems.values())
                {
                    int index = indexOfOrder(rows, id);
                    if (index >= 0)
                    {
                        return rows.get(index);
                    }
                }
                return null;
            }

            @Override
            public int addShippedQty(String itemId, BigDecimal delta)
            {
                return 0;
            }

            @Override
            public int subtractShippedQty(String itemId, BigDecimal delta)
            {
                return 0;
            }
        };
    }

    private CtmsPartnerMapper partnerMapperStub()
    {
        return new CtmsPartnerMapper()
        {
            @Override
            public List<CtmsCustomer> selectCustomerList(CtmsCustomer q)
            {
                return new ArrayList<>(customers.values());
            }

            @Override
            public CtmsCustomer selectCustomerById(String id)
            {
                return customers.get(id);
            }

            @Override
            public CtmsCustomer selectCustomerByCode(String code)
            {
                return null;
            }

            @Override
            public CtmsCustomer selectCustomerByName(String name)
            {
                return null;
            }

            @Override
            public int insertCustomer(CtmsCustomer c)
            {
                return 0;
            }

            @Override
            public int updateCustomer(CtmsCustomer c)
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
            public List<CtmsSupplier> selectSupplierList(CtmsSupplier q)
            {
                return new ArrayList<>(suppliers.values());
            }

            @Override
            public CtmsSupplier selectSupplierById(String id)
            {
                return suppliers.get(id);
            }

            @Override
            public CtmsSupplier selectSupplierByCode(String code)
            {
                return null;
            }

            @Override
            public CtmsSupplier selectSupplierByName(String name)
            {
                return null;
            }

            @Override
            public int insertSupplier(CtmsSupplier s)
            {
                return 0;
            }

            @Override
            public int updateSupplier(CtmsSupplier s)
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

    /**
     * 合同 Mapper 桩：只实现被本线用到的两个方法（读单一合同 / 不提供任何回写能力）。
     */
    private CtmsContractMapper contractMapperStub()
    {
        return new CtmsContractMapper()
        {
            @Override
            public List<CtmsContract> selectContractList(CtmsContract query)
            {
                return new ArrayList<>(contracts.values());
            }

            @Override
            public CtmsContract selectContractById(String id)
            {
                return contracts.get(id);
            }

            @Override
            public CtmsContract selectContractByNo(String contractNo)
            {
                return null;
            }

            @Override
            public int insertContract(CtmsContract contract)
            {
                throw new UnsupportedOperationException("销售线不得写入合同");
            }

            @Override
            public int updateContract(CtmsContract contract)
            {
                throw new UnsupportedOperationException("销售线不得回写合同");
            }

            @Override
            public int updateContractDelFlag(CtmsContract contract)
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
            public List<CtmsContract> selectChildren(String parentId)
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
            public List<CtmsContract> selectWarrantyReminderCandidates(Date windowEnd, String dataScopeSql, int limit)
            {
                return new ArrayList<>();
            }

            @Override
            public List<String> selectAllContractNos()
            {
                return new ArrayList<>();
            }

            @Override
            public List<CtmsContract> selectUnboundPartyCandidates(String partyType, String rawName)
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

    private ISysDictTypeService dictServiceStub()
    {
        return new ISysDictTypeService()
        {
            @Override
            public List<SysDictData> selectDictDataByType(String dictType)
            {
                return new ArrayList<>(contractTypes);
            }

            @Override
            public List<com.ruoyi.common.core.domain.entity.SysDictType> selectDictTypeList(
                    com.ruoyi.common.core.domain.entity.SysDictType dictType)
            {
                return new ArrayList<>();
            }

            @Override
            public List<com.ruoyi.common.core.domain.entity.SysDictType> selectDictTypeAll()
            {
                return new ArrayList<>();
            }

            @Override
            public com.ruoyi.common.core.domain.entity.SysDictType selectDictTypeById(String dictId)
            {
                return null;
            }

            @Override
            public com.ruoyi.common.core.domain.entity.SysDictType selectDictTypeByType(String dictType)
            {
                return null;
            }

            @Override
            public void deleteDictTypeByIds(String[] dictIds)
            {
                // 测试不需要
            }

            @Override
            public void loadingDictCache()
            {
                // 测试不需要
            }

            @Override
            public void clearDictCache()
            {
                // 测试不需要
            }

            @Override
            public void resetDictCache()
            {
                // 测试不需要
            }

            @Override
            public int insertDictType(com.ruoyi.common.core.domain.entity.SysDictType dictType)
            {
                return 0;
            }

            @Override
            public int updateDictType(com.ruoyi.common.core.domain.entity.SysDictType dictType)
            {
                return 0;
            }

            @Override
            public boolean checkDictTypeUnique(com.ruoyi.common.core.domain.entity.SysDictType dictType)
            {
                return true;
            }
        };
    }

    private com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterLookup masterLookupStub()
    {
        return new com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterLookup()
        {
            @Override
            public com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord product(String productId)
            {
                CtmsProduct product = products.get(productId);
                if (product == null)
                {
                    return null;
                }
                com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord record =
                        new com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord(
                                product.getId(), product.getCode(), product.getName());
                record.setSpec(product.getSpec());
                record.setUomId(product.getUomId());
                record.setEnableFlag(product.getEnableFlag());
                return record;
            }

            @Override
            public com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord warehouse(String warehouseId)
            {
                com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord record =
                        new com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord(
                                warehouseId, "WH-1", "主仓");
                record.setEnableFlag("1");
                return record;
            }

            @Override
            public com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord uom(String uomId)
            {
                CtmsUom uom = uoms.get(uomId);
                if (uom == null)
                {
                    return null;
                }
                com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord record =
                        new com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord(
                                uom.getId(), uom.getCode(), uom.getName());
                record.setDecimals(uom.getDecimals());
                record.setEnableFlag(uom.getEnableFlag());
                return record;
            }
        };
    }

    private static int indexOf(List<ErpSalesRequestItem> rows, String id)
    {
        for (int i = 0; i < rows.size(); i++)
        {
            if (id != null && id.equals(rows.get(i).getId()))
            {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfOrder(List<ErpSalesOrderItem> rows, String id)
    {
        for (int i = 0; i < rows.size(); i++)
        {
            if (id != null && id.equals(rows.get(i).getId()))
            {
                return i;
            }
        }
        return -1;
    }

    /* ==================== 商品/单位夹具（每个用例 setUp 后按需注入） ==================== */

    /**
     * 夹具用的物料与单位：在 {@link #setUp()} 之后由 {@link #seedProducts()} 注入
     * （放在这里是为了让 setUp 保持可读）。
     */
    private void seedProducts()
    {
        products.put("P1", product("P1", "P001", "螺丝", "M6", "U-1", "1"));
        products.put("P2", product("P2", "P002", "垫片", "M6", "U-1", "1"));
        products.put("P3", product("P3", "P003", "螺母", "M6", "U-1", "1"));
        uoms.put("U-1", uom("U-1", "个", 0));
    }

    private static CtmsProduct product(String id, String code, String name, String spec, String uomId,
                                       String enableFlag)
    {
        CtmsProduct p = new CtmsProduct();
        p.setId(id);
        p.setCode(code);
        p.setName(name);
        p.setSpec(spec);
        p.setUomId(uomId);
        p.setEnableFlag(enableFlag);
        return p;
    }

    private static CtmsUom uom(String id, String name, int decimals)
    {
        CtmsUom u = new CtmsUom();
        u.setId(id);
        u.setCode("UOM-1");
        u.setName(name);
        u.setDecimals(Integer.valueOf(decimals));
        u.setEnableFlag("1");
        return u;
    }
}
