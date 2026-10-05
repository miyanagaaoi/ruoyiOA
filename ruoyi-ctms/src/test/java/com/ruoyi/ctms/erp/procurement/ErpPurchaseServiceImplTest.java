package com.ruoyi.ctms.erp.procurement;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocNoGenerator;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushResultVo;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestMapper;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseRequestService;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseOrderServiceImpl;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchasePushServiceImpl;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseRequestServiceImpl;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.service.ICtmsPartnerService;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 采购线服务的内存桩单测（2.0 B4 任务 3.1~3.4、3.6）。 </p>
 *
 * <p> <b>不依赖 Spring / 数据库 / Redis / 真实时钟</b>：四个 Mapper 与物料、供应商、合同、
 * 编号服务全部用本文件内的内存桩（{@link Proxy} 代理接口，只覆写用到的方法），
 * 服务子类 {@link TestRequestService} 固定时钟、当前用户与数据范围。 </p>
 *
 * <p> <b>为什么四个服务要串起来</b>：任务 3.3/3.4 的断言（超量下推被拒后已下单量仍为 60、
 * 不填数量推完 40 后已下量为 100、两行推完自动完成且只留一条痕）本质是
 * "申请单服务 + 采购单服务 + 下推服务"的<b>组合行为</b>；只测单服务会漏掉
 * "谁负责累加、谁负责落草稿"的分工错误。 </p>
 *
 * @author 二开
 */
public class ErpPurchaseServiceImplTest
{
    /* ==================== 夹具常量 ==================== */

    /** 小数位 0 的物料（用于"单位 0 位小数填 1.5 被拒"）。 */
    private static final String PRODUCT_ZERO_DECIMALS = "P-ZERO";

    /** 小数位 3 的物料。 */
    private static final String PRODUCT_THREE_DECIMALS = "P-THREE";

    /** 已停用物料。 */
    private static final String PRODUCT_DISABLED = "P-DISABLED";

    /** 启用中的供应商。 */
    private static final String SUPPLIER_ENABLED = "S-001";

    /** 已停用供应商。 */
    private static final String SUPPLIER_DISABLED = "S-002";

    /** 客户（用于"传客户ID到供应商字段"）。 */
    private static final String CUSTOMER = "C-001";

    /** 采购方向合同（乙方 = 供应商）。 */
    private static final String CONTRACT_PURCHASE = "CT-PUR";

    /** 销售方向合同（甲方 = 客户）。 */
    private static final String CONTRACT_SALES = "CT-SAL";

    /** 已停用的采购方向合同。 */
    private static final String CONTRACT_PURCHASE_DISABLED = "CT-PUR-OFF";

    /* ==================== 桩 ==================== */

    private final StubMasterLookup masterLookup = new StubMasterLookup();

    private final StubPartnerService partnerService = new StubPartnerService();

    private final StubContractService contractService = new StubContractService();

    private final StubCodeGenService codeGenService = new StubCodeGenService();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final ErpPurchaseRequestMapper requestMapper = stub(ErpPurchaseRequestMapper.class);

    private final ErpPurchaseRequestItemMapper requestItemMapper = stub(ErpPurchaseRequestItemMapper.class);

    private final ErpPurchaseOrderMapper orderMapper = stub(ErpPurchaseOrderMapper.class);

    private final ErpPurchaseOrderItemMapper orderItemMapper = stub(ErpPurchaseOrderItemMapper.class);

    private final TestRequestService requestService = new TestRequestService();

    private final TestOrderService orderService = new TestOrderService();

    private final ErpPurchasePushServiceImpl pushService = new ErpPurchasePushServiceImpl();

    private final Map<String, ErpPurchaseRequest> requestStore = new LinkedHashMap<>();

    private final Map<String, ErpPurchaseRequestItem> requestItemStore = new LinkedHashMap<>();

    private final Map<String, ErpPurchaseOrder> orderStore = new LinkedHashMap<>();

    private final Map<String, ErpPurchaseOrderItem> orderItemStore = new LinkedHashMap<>();

    /** 编号服务已发出的编号（撞号场景用）。 */
    private int codeSeq = 0;

    @Before
    public void setUp()
    {
        masterLookup.addProduct(PRODUCT_ZERO_DECIMALS, "WL-0001", "螺栓", "M12", "U-ZERO", false);
        masterLookup.addProduct(PRODUCT_THREE_DECIMALS, "WL-0002", "球阀", "DN50", "U-THREE", false);
        masterLookup.addProduct(PRODUCT_DISABLED, "WL-0003", "旧垫片", "DN50", "U-THREE", true);
        partnerService.suppliers.put(SUPPLIER_ENABLED, supplier(SUPPLIER_ENABLED, "云羲数字科技", "1"));
        partnerService.suppliers.put(SUPPLIER_DISABLED, supplier(SUPPLIER_DISABLED, "已停用供应商", "0"));
        CtmsCustomer customer = new CtmsCustomer();
        customer.setId(CUSTOMER);
        customer.setName("百脉泉阀门有限公司");
        partnerService.customers.put(CUSTOMER, customer);
        contractService.contracts.put(CONTRACT_PURCHASE,
                contract(CONTRACT_PURCHASE, "PURZC202610000001", SUPPLIER_ENABLED, null, "0"));
        contractService.contracts.put(CONTRACT_SALES,
                contract(CONTRACT_SALES, "SALZC202610000001", null, CUSTOMER, "0"));
        contractService.contracts.put(CONTRACT_PURCHASE_DISABLED,
                contract(CONTRACT_PURCHASE_DISABLED, "PURZC202510000009", SUPPLIER_ENABLED, null, "1"));

        // 服务装配（@Autowired 字段用反射注入；内存桩不起 Spring）
        inject(requestService, "requestMapper", requestMapper);
        inject(requestService, "itemMapper", requestItemMapper);
        inject(requestService, "orderMapper", orderMapper);
        inject(requestService, "changeLogMapper", changeLogMapper);
        inject(requestService, "masterLookup", masterLookup);
        inject(requestService, "partnerService", partnerService);
        inject(requestService, "contractService", contractService);
        inject(requestService, "erpDocNoGenerator", new ErpDocNoGenerator(codeGenService));

        inject(orderService, "orderMapper", orderMapper);
        inject(orderService, "itemMapper", orderItemMapper);
        inject(orderService, "changeLogMapper", changeLogMapper);
        inject(orderService, "masterLookup", masterLookup);
        inject(orderService, "partnerService", partnerService);
        inject(orderService, "contractService", contractService);
        inject(orderService, "erpDocNoGenerator", new ErpDocNoGenerator(codeGenService));

        inject(pushService, "requestService", requestService);
        inject(pushService, "orderService", orderService);
    }

    /* ==================== 3.1 单据 CRUD 与行项口径 ==================== */

    @Test
    public void 新增采购申请单后可按关键字检索且派生列正确()
    {
        String id = requestService.insertRequest(payload("阀门采购申请", "P-THREE", "10", "2.5"));
        assertNotNull("主键由服务端生成", id);
        ErpPurchaseRequest query = new ErpPurchaseRequest();
        query.setKeyword("阀门采购申请");
        List<ErpPurchaseRequest> list = requestService.selectRequestList(query);
        assertEquals("按关键字应命中 1 条", 1, list.size());
        assertEquals("剩余可下推量合计 = 10（还没下推过）", 0,
                list.get(0).getRemainQtySum().compareTo(new BigDecimal("10")));
        assertTrue("还有剩余量 → 可下推", list.get(0).getCanPush().booleanValue());
        assertEquals("金额合计 = 10 × 2.5 = 25.00", 0,
                list.get(0).getTotalAmount().compareTo(new BigDecimal("25")));
        assertEquals("状态初值为草稿", ErpDocStatus.DRAFT, list.get(0).getStatus());
        assertNotNull("列表要带出归属部门名（Mapper 关联 sys_dept）", list.get(0).getDeptId());
    }

    @Test
    public void 列表默认排除已作废且状态筛选可命中()
    {
        String id = requestService.insertRequest(payload("待作废申请", "P-THREE", "5", "1"));
        requestService.changeStatus(id, "submit", null);
        requestService.changeStatus(id, "void", "不需要了");
        assertEquals("默认列表不含已作废",
                0, requestService.selectRequestList(new ErpPurchaseRequest()).size());
        ErpPurchaseRequest voidQuery = new ErpPurchaseRequest();
        voidQuery.setStatus(ErpDocStatus.VOIDED);
        assertEquals("显式按 voided 筛选可以查到（AC-V2-17）",
                1, requestService.selectRequestList(voidQuery).size());
        ErpPurchaseRequest allQuery = new ErpPurchaseRequest();
        allQuery.setIncludeVoided("1");
        assertEquals("includeVoided=1 放开已作废",
                1, requestService.selectRequestList(allQuery).size());
    }

    @Test
    public void 无行项提交被拒()
    {
        ErpPurchaseRequest empty = new ErpPurchaseRequest();
        assertRejected(ErpPurRules.MSG_NO_ITEMS, new Runnable()
        {
            @Override
            public void run()
            {
                requestService.insertRequest(new ErpPurchaseRequest());
            }
        });
        assertNull("被拒后不得落库", empty.getId());
    }

    @Test
    public void 数量为零的行项被拒且提示带行号()
    {
        final ErpPurchaseRequest request = payload("数量为零", "P-THREE", "0", "1");
        assertRejected("行 1：数量必须大于 0", new Runnable()
        {
            @Override
            public void run()
            {
                requestService.insertRequest(request);
            }
        });
    }

    @Test
    public void 单位零位小数时填一点五被拒()
    {
        final ErpPurchaseRequest request = payload("小数数量", "P-ZERO", "1.5", "1");
        try
        {
            requestService.insertRequest(request);
            fail("单位 0 位小数时 1.5 必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应指出最小单位是 0 位小数，实际=" + e.getMessage(),
                    e.getMessage().contains("数量的最小单位是 0 位小数"));
        }
    }

    @Test
    public void 停用物料被拒且提示带行号与物料名()
    {
        final ErpPurchaseRequest request = payload("停用物料", "P-DISABLED", "1", "1");
        try
        {
            requestService.insertRequest(request);
            fail("停用物料必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应带行号与物料名，实际=" + e.getMessage(),
                    e.getMessage().contains("行 1") && e.getMessage().contains("旧垫片"));
        }
    }

    @Test
    public void 三行零点一二五合计为零点三九()
    {
        ErpPurchaseRequest request = new ErpPurchaseRequest();
        request.setPurpose("三行 0.125");
        List<ErpPurchaseRequestItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            ErpPurchaseRequestItem line = new ErpPurchaseRequestItem();
            line.setProductId(PRODUCT_THREE_DECIMALS);
            line.setQty(new BigDecimal("1"));
            line.setUnitPrice(new BigDecimal("0.125"));
            items.add(line);
        }
        request.setItems(items);
        requestService.insertRequest(request);
        Map<String, ErpPurchaseRequestItem> byId = requestItemStore;
        BigDecimal sum = BigDecimal.ZERO;
        int lines = 0;
        for (ErpPurchaseRequestItem stored : byId.values())
        {
            if (stored.getDocId().equals(request.getId()))
            {
                assertEquals("每行先舍入为 0.13", 0, stored.getAmount().compareTo(new BigDecimal("0.13")));
                sum = sum.add(stored.getAmount());
                lines++;
            }
        }
        assertEquals("应落 3 行", 3, lines);
        assertEquals("合计 = 0.13 × 3 = 0.39（不是 0.38）", 0, sum.compareTo(new BigDecimal("0.39")));
        assertEquals("详情返回的合计一致", 0,
                requestService.selectRequestDetail(request.getId()).getTotalAmount()
                        .compareTo(new BigDecimal("0.39")));
    }

    @Test
    public void 非草稿不可编辑()
    {
        final String id = requestService.insertRequest(payload("已审核不可改", "P-THREE", "3", "1"));
        requestService.changeStatus(id, "submit", null);
        requestService.changeStatus(id, "approve", null);
        final ErpPurchaseRequest patch = payload("已审核不可改", "P-THREE", "9", "1");
        patch.setId(id);
        try
        {
            requestService.updateRequest(patch);
            fail("已审核单据必须不可编辑");
        }
        catch (ServiceException e)
        {
            assertTrue("提示应说明当前状态不可编辑，实际=" + e.getMessage(),
                    e.getMessage().contains("不可编辑"));
        }
    }

    @Test
    public void 行项快照落库且物料改名不影响历史()
    {
        String id = requestService.insertRequest(payload("快照", "P-THREE", "2", "3"));
        ErpPurchaseRequestItem stored = firstItemOf(id);
        assertEquals("物料编码快照", "WL-0002", stored.getProductCode());
        assertEquals("物料名称快照", "球阀", stored.getProductName());
        assertEquals("规格快照", "DN50", stored.getSpec());
        assertEquals("单位名快照", "件", stored.getUomName());
        assertEquals("单位小数位快照", Integer.valueOf(3), stored.getUomDecimals());
        // 物料改名后回查历史单据：显示旧快照（不重算）
        masterLookup.addProduct(PRODUCT_THREE_DECIMALS, "WL-0002", "球阀（改名后）", "DN50", "U-THREE", false);
        assertEquals("历史行项仍显示旧名称",
                "球阀", firstItemOf(id).getProductName());
    }

    /* ==================== 3.2 供应商与收货信息 ==================== */

    @Test
    public void 采购单传客户ID到供应商字段被拒()
    {
        final ErpPurchaseOrder order = orderPayload(CUSTOMER, "P-THREE", "1", "1");
        assertRejected(ErpPurRules.MSG_SUPPLIER_NOT_FOUND, new Runnable()
        {
            @Override
            public void run()
            {
                orderService.insertOrder(order);
            }
        });
    }

    @Test
    public void 停用供应商保存被拒()
    {
        final ErpPurchaseOrder order = orderPayload(SUPPLIER_DISABLED, "P-THREE", "1", "1");
        assertRejected(ErpPurRules.MSG_SUPPLIER_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                orderService.insertOrder(order);
            }
        });
    }

    @Test
    public void 采购单保存供应商快照与收货信息()
    {
        ErpPurchaseOrder order = orderPayload(SUPPLIER_ENABLED, "P-THREE", "4", "2.5");
        order.setExpectedArrivalDate(today());
        order.setSettleType("月结30天");
        String id = orderService.insertOrder(order);
        ErpPurchaseOrder stored = orderService.selectOrderDetail(id);
        assertEquals("供应商名称快照", "云羲数字科技", stored.getSupplierName());
        assertEquals("结算方式", "月结30天", stored.getSettleType());
        assertNotNull("预计到货日期", stored.getExpectedArrivalDate());
        assertEquals("金额合计 = 4 × 2.5 = 10.00", 0,
                stored.getTotalAmount().compareTo(new BigDecimal("10")));
        assertEquals("币种默认 CNY", "CNY", stored.getCurrency());
    }

    /* ==================== 3.3 下推 ==================== */

    @Test
    public void 未审核申请单不可下推()
    {
        final String id = requestService.insertRequest(payload("草稿不可推", "P-THREE", "10", "1"));
        assertRejected(ErpPurRules.MSG_NOT_APPROVED_PUSH, new Runnable()
        {
            @Override
            public void run()
            {
                pushService.pushToPurchaseOrder(id, null, SUPPLIER_ENABLED, null);
            }
        });
    }

    @Test
    public void 超量下推被拒且已下单量不变()
    {
        String id = approvedRequest("超量下推", "P-THREE", "100", "1");
        // 先下推 60（不填数量会全推，所以显式给 60）
        List<ErpPushLine> first = new ArrayList<>();
        first.add(pushLine(firstItemOf(id).getId(), "60"));
        ErpPushResultVo firstResult = pushService.pushToPurchaseOrder(id, first, SUPPLIER_ENABLED, null);
        assertEquals("第一次下推 60", 0, firstResult.getPushedQty().compareTo(new BigDecimal("60")));
        assertEquals("已下单量累加到 60", 0,
                firstItemOf(id).getOrderedQty().compareTo(new BigDecimal("60")));

        // 再推 50 → 剩余只有 40，必须被拒
        final List<ErpPushLine> second = new ArrayList<>();
        second.add(pushLine(firstItemOf(id).getId(), "50"));
        try
        {
            pushService.pushToPurchaseOrder(id, second, SUPPLIER_ENABLED, null);
            fail("超量下推必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应带剩余 40 与本次 50，实际=" + e.getMessage(),
                    e.getMessage().contains("剩余 40") && e.getMessage().contains("本次 50"));
        }
        assertEquals("被拒后已下单量仍为 60", 0,
                firstItemOf(id).getOrderedQty().compareTo(new BigDecimal("60")));
        assertEquals("被拒后不得多生成采购单", 1, orderStore.size());
    }

    @Test
    public void 不填数量下推剩余量后已下量为原始数量()
    {
        String id = approvedRequest("按剩余全推", "P-THREE", "100", "1");
        List<ErpPushLine> first = new ArrayList<>();
        first.add(pushLine(firstItemOf(id).getId(), "60"));
        pushService.pushToPurchaseOrder(id, first, SUPPLIER_ENABLED, null);

        // 不填数量 → 按剩余 40 全推
        ErpPushResultVo result = pushService.pushToPurchaseOrder(id, null, SUPPLIER_ENABLED, null);
        assertEquals("本次按剩余量推 40", 0, result.getPushedQty().compareTo(new BigDecimal("40")));
        assertEquals("已下单量变为 100", 0,
                firstItemOf(id).getOrderedQty().compareTo(new BigDecimal("100")));
        ErpPurchaseOrder order = result.getOrder();
        assertEquals("生成的采购单是草稿", ErpDocStatus.DRAFT, order.getStatus());
        assertEquals("来源单号", requestService.selectRequestDetail(id).getDocNo(), order.getSourceDocNo());
        assertEquals("来源单据ID", id, order.getSourceDocId());
        assertEquals("来源单据类型", "purchase_request", order.getSourceDocType());
        assertEquals("行数量 = 40", 0,
                order.getItems().get(0).getQty().compareTo(new BigDecimal("40")));
        assertEquals("来源行标识", firstItemOf(id).getId(), order.getItems().get(0).getSrcItemId());
    }

    @Test
    public void 下推默认带过合同与申请部门需求日期()
    {
        // 合同只能在草稿态关联（非草稿不可编辑），所以先建草稿 → 关联 → 再提交审核
        String id = requestService.insertRequest(payload("带过合同", "P-THREE", "10", "1"));
        ErpPurchaseRequest draft = requestService.selectRequestDetail(id);
        draft.setContractId(CONTRACT_PURCHASE);
        requestService.updateRequest(draft);
        requestService.changeStatus(id, "submit", null);
        requestService.changeStatus(id, "approve", null);
        ErpPurchaseRequest withContract = requestService.selectRequestDetail(id);
        assertEquals("合同编号快照写入申请单", "PURZC202610000001", withContract.getContractNo());

        ErpPushResultVo result = pushService.pushToPurchaseOrder(id, null, SUPPLIER_ENABLED, null);
        ErpPurchaseOrder order = result.getOrder();
        assertEquals("合同带过", CONTRACT_PURCHASE, order.getContractId());
        assertEquals("合同编号快照带过", "PURZC202610000001", order.getContractNo());
        assertEquals("采购部门来自申请单需求部门",
                withContract.getRequestDeptId(), order.getPurchaseDeptId());
        assertEquals("预计到货来自需求日期（都为空则都为空）",
                withContract.getNeedDate(), order.getExpectedArrivalDate());
    }

    /* ==================== 3.4 归零即完成 ==================== */

    @Test
    public void 两行推完自动完成且只留一条变更历史()
    {
        ErpPurchaseRequest request = new ErpPurchaseRequest();
        request.setPurpose("两行归零");
        List<ErpPurchaseRequestItem> items = new ArrayList<>();
        ErpPurchaseRequestItem first = new ErpPurchaseRequestItem();
        first.setProductId(PRODUCT_THREE_DECIMALS);
        first.setQty(new BigDecimal("10"));
        first.setUnitPrice(new BigDecimal("1"));
        items.add(first);
        ErpPurchaseRequestItem second = new ErpPurchaseRequestItem();
        second.setProductId(PRODUCT_ZERO_DECIMALS);
        second.setQty(new BigDecimal("5"));
        second.setUnitPrice(new BigDecimal("1"));
        items.add(second);
        request.setItems(items);
        requestService.insertRequest(request);
        requestService.changeStatus(request.getId(), "submit", null);
        requestService.changeStatus(request.getId(), "approve", null);

        List<ErpPurchaseRequestItem> stored = requestService.selectItemsByDocId(request.getId());
        // 只推完第一行 → 不置完成
        List<ErpPushLine> firstLine = new ArrayList<>();
        firstLine.add(pushLine(stored.get(0).getId(), null));
        ErpPushResultVo firstPush = pushService.pushToPurchaseOrder(request.getId(), firstLine,
                SUPPLIER_ENABLED, null);
        assertFalse("只推完一行不置完成", firstPush.isAutoCompleted());
        assertEquals("状态仍是已审核", ErpDocStatus.APPROVED, firstPush.getSourceStatus());
        assertTrue("剩余量仍大于 0", firstPush.getRemainQtySum().signum() > 0);

        // 推完第二行 → 自动完成 + 留痕一条
        List<ErpPushLine> secondLine = new ArrayList<>();
        secondLine.add(pushLine(stored.get(1).getId(), null));
        ErpPushResultVo secondPush = pushService.pushToPurchaseOrder(request.getId(), secondLine,
                SUPPLIER_ENABLED, null);
        assertTrue("两行都推完 → 自动完成", secondPush.isAutoCompleted());
        assertEquals("状态变为已完成", ErpDocStatus.COMPLETED, secondPush.getSourceStatus());
        assertEquals("剩余可下推量合计为 0", 0, secondPush.getRemainQtySum().compareTo(BigDecimal.ZERO));
        List<CtmsChangeLog> logs = requestService.selectRequestChangeLogs(request.getId());
        int autoCompleteLogs = 0;
        for (CtmsChangeLog log : logs)
        {
            if (ErpPurRules.LOG_NOTE_AUTO_COMPLETE.equals(log.getNote()))
            {
                autoCompleteLogs++;
                assertEquals("留痕字段是 status", ErpPurRules.LOG_FIELD_STATUS, log.getFieldName());
                assertEquals("旧值是已审核", ErpDocStatus.APPROVED, log.getOldValue());
                assertEquals("新值是已完成", ErpDocStatus.COMPLETED, log.getNewValue());
                assertEquals("来源是 auto", ErpPurRules.LOG_SOURCE_AUTO, log.getSource());
                assertEquals("对象类型是单据码", "purchase_request", log.getObjectType());
            }
        }
        assertEquals("归零留痕恰好一条", 1, autoCompleteLogs);

        // 幂等：再判定一次不产生第二条留痕、状态不变
        assertFalse("已完成再判定返回 false（幂等）", requestService.checkAutoComplete(request.getId()));
        int after = 0;
        for (CtmsChangeLog log : requestService.selectRequestChangeLogs(request.getId()))
        {
            if (ErpPurRules.LOG_NOTE_AUTO_COMPLETE.equals(log.getNote()))
            {
                after++;
            }
        }
        assertEquals("重复判定不重复留痕", 1, after);
    }

    @Test
    public void 列表返回剩余可下推量合计供按钮显隐()
    {
        String id = approvedRequest("剩余量展示", "P-THREE", "8", "1");
        ErpPurchaseRequest query = new ErpPurchaseRequest();
        query.setId(id);
        List<ErpPurchaseRequest> list = requestService.selectRequestList(query);
        assertEquals(1, list.size());
        assertEquals("未下推时剩余 = 8", 0,
                list.get(0).getRemainQtySum().compareTo(new BigDecimal("8")));
        assertTrue(list.get(0).getCanPush().booleanValue());

        List<ErpPushLine> lines = new ArrayList<>();
        lines.add(pushLine(firstItemOf(id).getId(), null));
        pushService.pushToPurchaseOrder(id, lines, SUPPLIER_ENABLED, null);
        List<ErpPurchaseRequest> afterPush = requestService.selectRequestList(query);
        assertEquals("推完后剩余 = 0", 0,
                afterPush.get(0).getRemainQtySum().compareTo(BigDecimal.ZERO));
        assertFalse("剩余为 0 → canPush=false（前端隐藏下推入口）",
                afterPush.get(0).getCanPush().booleanValue());
    }

    /* ==================== 3.6 关联合同 ==================== */

    @Test
    public void 关联销售方向合同被拒()
    {
        ErpPurchaseRequest request = payload("方向不符", "P-THREE", "1", "1");
        request.setContractId(CONTRACT_SALES);
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                requestService.insertRequest(request);
            }
        });
    }

    @Test
    public void 关联已停用合同被拒()
    {
        ErpPurchaseRequest request = payload("停用合同", "P-THREE", "1", "1");
        request.setContractId(CONTRACT_PURCHASE_DISABLED);
        assertRejected(ErpPurRules.MSG_CONTRACT_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                requestService.insertRequest(request);
            }
        });
    }

    @Test
    public void 关联合同成功且不回写合同字段()
    {
        ErpPurchaseRequest request = payload("关联合同", "P-THREE", "1", "1");
        request.setContractId(CONTRACT_PURCHASE);
        String id = requestService.insertRequest(request);
        ErpPurchaseRequest stored = requestService.selectRequestDetail(id);
        assertEquals("合同ID落库", CONTRACT_PURCHASE, stored.getContractId());
        assertEquals("合同编号快照落库", "PURZC202610000001", stored.getContractNo());
        CtmsContract contract = contractService.contracts.get(CONTRACT_PURCHASE);
        assertEquals("合同编号未被回写", "PURZC202610000001", contract.getContractNo());
        assertEquals("合同金额未被回写", 0, contract.getAmount().compareTo(new BigDecimal("1000")));
        assertEquals("合同乙方未被改动", SUPPLIER_ENABLED, contract.getSupplierId());
        assertEquals("合同未被停用", "0", contract.getDelFlag());
        assertSame("合同对象仍是同一实例（没有被替换）",
                contractService.contracts.get(CONTRACT_PURCHASE), contract);
    }

    /* ==================== 状态流转与下游守卫 ==================== */

    @Test
    public void 驳回需原因且回到草稿()
    {
        String id = requestService.insertRequest(payload("驳回", "P-THREE", "1", "1"));
        requestService.changeStatus(id, "submit", null);
        assertRejected("驳回原因必填", new Runnable()
        {
            @Override
            public void run()
            {
                requestService.changeStatus(id, "reject", "  ");
            }
        });
        requestService.changeStatus(id, "reject", "数量不对");
        assertEquals("驳回后回到草稿", ErpDocStatus.DRAFT,
                requestService.selectRequestDetail(id).getStatus());
    }

    @Test
    public void 存在未作废下游时禁止反审核()
    {
        String id = approvedRequest("下游守卫", "P-THREE", "10", "1");
        // 只推 4（申请行还剩 6）：申请单保持"已审核"，便于验证下游守卫
        List<ErpPushLine> lines = new ArrayList<>();
        lines.add(pushLine(firstItemOf(id).getId(), "4"));
        pushService.pushToPurchaseOrder(id, lines, SUPPLIER_ENABLED, null);
        ErpPurchaseOrder downstream = orderStore.values().iterator().next();
        // 下游推到已审核（下推生成的是草稿）
        orderService.changeStatus(downstream.getId(), "submit", null);
        orderService.changeStatus(downstream.getId(), "approve", null);
        assertRejected(ErpPurRules.MSG_DOWNSTREAM_BLOCK, new Runnable()
        {
            @Override
            public void run()
            {
                requestService.changeStatus(id, "unapprove", "想改需求");
            }
        });
        // 下游"反审核"回到待审核仍不是已作废态 → 依旧拦（口径同移植清单 §3.9.4：
        // assert_no_downstream 判的是 status != voided）
        orderService.changeStatus(downstream.getId(), "unapprove", "改量");
        assertRejected(ErpPurRules.MSG_DOWNSTREAM_BLOCK, new Runnable()
        {
            @Override
            public void run()
            {
                requestService.changeStatus(id, "unapprove", "想改需求");
            }
        });
        // 下游作废后放行（规格场景「下游作废后放行」）
        orderService.changeStatus(downstream.getId(), "void", "不用了");
        requestService.changeStatus(id, "unapprove", "想改需求");
        assertEquals("反审核回到待审核", ErpDocStatus.SUBMITTED,
                requestService.selectRequestDetail(id).getStatus());
    }

    @Test
    public void 反审核清空审核痕迹()
    {
        String id = approvedRequest("反审核清痕", "P-THREE", "10", "1");
        ErpPurchaseRequest approved = requestService.selectRequestDetail(id);
        assertEquals("审核后记录审核人", "2", approved.getApprovedBy());
        assertNotNull("审核后记录审核时间", approved.getApprovedAt());

        requestService.changeStatus(id, "unapprove", "要改需求");
        ErpPurchaseRequest after = requestService.selectRequestDetail(id);
        assertEquals("反审核回到待审核", ErpDocStatus.SUBMITTED, after.getStatus());
        assertNull("反审核必须清空 approved_by", after.getApprovedBy());
        assertNull("反审核必须清空 approved_at", after.getApprovedAt());
    }

    @Test
    public void 单位小数位三的物料允许三位小数()
    {
        String id = requestService.insertRequest(payload("三位小数", "P-THREE", "1.125", "1"));
        ErpPurchaseRequestItem stored = firstItemOf(id);
        assertEquals("数量按单位小数位保留", 0, stored.getQty().compareTo(new BigDecimal("1.125")));
    }

    /* ==================== 夹具与工具 ==================== */

    private static CtmsSupplier supplier(String id, String name, String enableFlag)
    {
        CtmsSupplier supplier = new CtmsSupplier();
        supplier.setId(id);
        supplier.setName(name);
        supplier.setEnableFlag(enableFlag);
        return supplier;
    }

    private static CtmsContract contract(String id, String no, String supplierId, String customerId,
                                        String delFlag)
    {
        CtmsContract contract = new CtmsContract();
        contract.setId(id);
        contract.setContractNo(no);
        contract.setSupplierId(supplierId);
        contract.setCustomerId(customerId);
        contract.setDelFlag(delFlag);
        contract.setAmount(new BigDecimal("1000"));
        return contract;
    }

    private static ErpPushLine pushLine(String srcItemId, String qty)
    {
        ErpPushLine line = new ErpPushLine();
        line.setSrcItemId(srcItemId);
        line.setQty(qty == null ? null : new BigDecimal(qty));
        return line;
    }

    private static Date today()
    {
        return new Date();
    }

    /** 构造采购申请单（一行行项）。 */
    private ErpPurchaseRequest payload(String purpose, String productId, String qty, String price)
    {
        ErpPurchaseRequest request = new ErpPurchaseRequest();
        request.setPurpose(purpose);
        request.setRequestDeptId("103");
        List<ErpPurchaseRequestItem> items = new ArrayList<>();
        ErpPurchaseRequestItem line = new ErpPurchaseRequestItem();
        line.setProductId(productId);
        line.setQty(new BigDecimal(qty));
        line.setUnitPrice(new BigDecimal(price));
        items.add(line);
        request.setItems(items);
        return request;
    }

    /** 构造采购单（一行行项）。 */
    private ErpPurchaseOrder orderPayload(String supplierId, String productId, String qty, String price)
    {
        ErpPurchaseOrder order = new ErpPurchaseOrder();
        order.setSupplierId(supplierId);
        order.setReceiptWarehouseId(null);
        List<ErpPurchaseOrderItem> items = new ArrayList<>();
        ErpPurchaseOrderItem line = new ErpPurchaseOrderItem();
        line.setProductId(productId);
        line.setQty(new BigDecimal(qty));
        line.setUnitPrice(new BigDecimal(price));
        items.add(line);
        order.setItems(items);
        return order;
    }

    /** 建一张已审核的申请单（下推用例的前置）。 */
    private String approvedRequest(String purpose, String productId, String qty, String price)
    {
        String id = requestService.insertRequest(payload(purpose, productId, qty, price));
        requestService.changeStatus(id, "submit", null);
        requestService.changeStatus(id, "approve", null);
        return id;
    }

    /** 取某申请单的第一行行项（内存桩里的当前值）。 */
    private ErpPurchaseRequestItem firstItemOf(String docId)
    {
        for (ErpPurchaseRequestItem item : requestItemStore.values())
        {
            if (docId.equals(item.getDocId()))
            {
                return item;
            }
        }
        throw new IllegalStateException("该申请单没有行项：" + docId);
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

    private <T> T stub(Class<T> type)
    {
        return type.cast(Proxy.newProxyInstance(ErpPurchaseServiceImplTest.class.getClassLoader(),
                new Class<?>[] { type }, new Handler()));
    }

    private static void inject(Object target, String fieldName, Object value)
    {
        try
        {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("注入 " + fieldName + " 失败", e);
        }
    }

    private static Field findField(Class<?> type, String name)
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("找不到字段：" + name);
    }

    /**
     * 测试子类：固定时钟/用户/数据范围，并把四个 Mapper 的桩接到内存表上。
     */
    private class TestRequestService extends ErpPurchaseRequestServiceImpl
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
            // 单测没有登录上下文：固定创建快照（生产实现由 base 的 ErpDocScope 写入并校验）
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
     * 采购单服务子类（同样的固定上下文）。
     */
    private class TestOrderService extends ErpPurchaseOrderServiceImpl
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
     * 主数据守卫桩（物料 + 单位）。
     */
    private class StubMasterLookup implements ErpMasterGuards.MasterLookup
    {
        private final Map<String, CtmsProduct> products = new LinkedHashMap<>();

        private final Map<String, CtmsUom> uoms = new LinkedHashMap<>();

        void addProduct(String id, String code, String name, String spec, String uomId, boolean disabled)
        {
            CtmsProduct product = new CtmsProduct();
            product.setId(id);
            product.setCode(code);
            product.setName(name);
            product.setSpec(spec);
            product.setUomId(uomId);
            product.setProductTypeId("T-1");
            product.setEnableFlag(disabled ? "0" : "1");
            products.put(id, product);
            if (!uoms.containsKey(uomId))
            {
                CtmsUom uom = new CtmsUom();
                uom.setId(uomId);
                uom.setCode(uomId);
                uom.setName("件");
                uom.setDecimals(Integer.valueOf("U-ZERO".equals(uomId) ? 0 : 3));
                uom.setEnableFlag("1");
                uoms.put(uomId, uom);
            }
        }

        @Override
        public ErpMasterGuards.MasterRecord product(String productId)
        {
            CtmsProduct product = products.get(productId);
            if (product == null)
            {
                return null;
            }
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(
                    product.getId(), product.getCode(), product.getName());
            record.setSpec(product.getSpec());
            record.setUomId(product.getUomId());
            record.setEnableFlag(product.getEnableFlag());
            return record;
        }

        @Override
        public ErpMasterGuards.MasterRecord warehouse(String warehouseId)
        {
            return null;
        }

        @Override
        public ErpMasterGuards.MasterRecord uom(String uomId)
        {
            CtmsUom uom = uoms.get(uomId);
            if (uom == null)
            {
                return null;
            }
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(
                    uom.getId(), uom.getCode(), uom.getName());
            record.setDecimals(uom.getDecimals());
            record.setEnableFlag(uom.getEnableFlag());
            return record;
        }
    }

    /**
     * 往来单位服务桩。
     */
    private class StubPartnerService implements ICtmsPartnerService
    {
        private final Map<String, CtmsSupplier> suppliers = new LinkedHashMap<>();

        private final Map<String, CtmsCustomer> customers = new LinkedHashMap<>();

        @Override
        public CtmsSupplier selectSupplierById(String id)
        {
            return suppliers.get(id);
        }

        @Override
        public CtmsCustomer selectCustomerById(String id)
        {
            return customers.get(id);
        }

        @Override
        public List<CtmsSupplier> selectSupplierOptions()
        {
            return new ArrayList<>(suppliers.values());
        }

        /* ---- 其余方法本用例不用：给出显式空实现，避免 Proxy 误吞调用 ---- */

        @Override
        public List<CtmsCustomer> selectCustomerList(CtmsCustomer query)
        {
            return new ArrayList<>();
        }

        @Override
        public List<CtmsCustomer> selectCustomerOptions()
        {
            return new ArrayList<>(customers.values());
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
     * 合同服务桩（只实现本用例用到的"按条件查列表"）。
     */
    private class StubContractService implements ICtmsContractService
    {
        private final Map<String, CtmsContract> contracts = new LinkedHashMap<>();

        @Override
        public List<CtmsContract> selectContractList(CtmsContract query)
        {
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract contract : contracts.values())
            {
                if (query == null || query.getId() == null || query.getId().equals(contract.getId()))
                {
                    // includeDeleted="1" 时把已停用行也返回（服务层要看到它才能报"已停用"）
                    if ("1".equals(query == null ? null : query.getIncludeDeleted())
                            || !"1".equals(contract.getDelFlag()))
                    {
                        result.add(contract);
                    }
                }
            }
            return result;
        }

        /* ---- 其余方法本用例不用 ---- */

        @Override
        public void checkContractAccess(String id)
        {
        }

        @Override
        public CtmsContract selectContractDetail(String id)
        {
            return contracts.get(id);
        }

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            return new ArrayList<>();
        }

        @Override
        public void insertContract(CtmsContract contract)
        {
            contracts.put(contract.getId(), contract);
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
     * 编号服务桩：按 base 的编号配置 id 返回单调递增的 {@code PR/PO + yyyyMM + 序号}。
     */
    private class StubCodeGenService implements ICodeGenService
    {
        @Override
        public String getNextCode(String confId)
        {
            return getNextCode(confId, null);
        }

        @Override
        public String getNextCode(String confId, CodeGenContext context)
        {
            codeSeq++;
            String prefix = ErpDocNoGenerator.CONF_ID_PURCHASE_ORDER.equals(confId) ? "PO" : "PR";
            return prefix + "202610" + String.format(java.util.Locale.ROOT, "%06d", Integer.valueOf(codeSeq));
        }

        @Override
        public String previewNextCode(String confId, CodeGenContext context)
        {
            String prefix = ErpDocNoGenerator.CONF_ID_PURCHASE_ORDER.equals(confId) ? "PO" : "PR";
            return prefix + "202610" + String.format(java.util.Locale.ROOT, "%06d",
                    Integer.valueOf(codeSeq + 1));
        }
    }

    /**
     * 变更历史 Mapper 桩（只实现插入与查询）。
     */
    private class StubChangeLogMapper implements CtmsChangeLogMapper
    {
        private final List<CtmsChangeLog> logs = new ArrayList<>();

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            List<CtmsChangeLog> result = new ArrayList<>();
            for (CtmsChangeLog log : logs)
            {
                if (query == null)
                {
                    result.add(log);
                    continue;
                }
                boolean objectMatch = (query.getObjectId() == null
                        || query.getObjectId().equals(log.getObjectId()))
                        && (query.getObjectType() == null
                        || query.getObjectType().equals(log.getObjectType()));
                if (objectMatch)
                {
                    result.add(log);
                }
            }
            return result;
        }

        @Override
        public int insertChangeLog(CtmsChangeLog log)
        {
            logs.add(log);
            return 1;
        }

        @Override
        public int batchInsertChangeLogs(List<CtmsChangeLog> batch)
        {
            logs.addAll(batch);
            return batch.size();
        }
    }

    /**
     * 四个单据 Mapper 的内存实现（放在 {@link Handler} 里按方法名分派）。
     */
    private class Handler implements InvocationHandler
    {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            String name = method.getName();
            if ("toString".equals(name))
            {
                return "stub";
            }
            if ("hashCode".equals(name))
            {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name))
            {
                return Boolean.valueOf(proxy == args[0]);
            }
            /* ---------- 采购申请单表头 ---------- */
            if ("selectRequestList".equals(name))
            {
                return filterRequests((ErpPurchaseRequest) args[0]);
            }
            if ("selectRequestById".equals(name))
            {
                return requestStore.get((String) args[0]);
            }
            if ("selectDocNoByNo".equals(name))
            {
                for (ErpPurchaseRequest request : requestStore.values())
                {
                    if (request.getDocNo() != null && request.getDocNo().equals(args[0]))
                    {
                        return request;
                    }
                }
                return null;
            }
            if ("selectDocNosByPrefix".equals(name))
            {
                List<String> result = new ArrayList<>();
                for (ErpPurchaseRequest request : requestStore.values())
                {
                    if (request.getDocNo() != null && request.getDocNo().startsWith((String) args[0]))
                    {
                        result.add(request.getDocNo());
                    }
                }
                return result;
            }
            if ("insertRequest".equals(name))
            {
                ErpPurchaseRequest request = (ErpPurchaseRequest) args[0];
                requestStore.put(request.getId(), request);
                return 1;
            }
            if ("updateRequest".equals(name))
            {
                ErpPurchaseRequest patch = (ErpPurchaseRequest) args[0];
                ErpPurchaseRequest stored = requestStore.get(patch.getId());
                if (stored == null)
                {
                    return 0;
                }
                if (patch.getDocDate() != null)
                {
                    stored.setDocDate(patch.getDocDate());
                }
                if (patch.getRemark() != null)
                {
                    stored.setRemark(patch.getRemark());
                }
                stored.setContractId(patch.getContractId());
                stored.setContractNo(patch.getContractNo());
                stored.setRequestDeptId(patch.getRequestDeptId());
                stored.setNeedDate(patch.getNeedDate());
                stored.setPurpose(patch.getPurpose());
                stored.setHandlerUserId(patch.getHandlerUserId());
                stored.setHandlerName(patch.getHandlerName());
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
                // 与 XML 的 <if test="approvedAt != null"> 同构：null 不写库
                // （"置空"必须走 clearApprovalTrace，这条桩行为刻意保持与真语句一致）
                if (patch.getApprovedAt() != null)
                {
                    stored.setApprovedAt(patch.getApprovedAt());
                }
                if (patch.getSubmittedBy() != null)
                {
                    stored.setSubmittedBy(patch.getSubmittedBy());
                }
                if (patch.getVoidedBy() != null)
                {
                    stored.setVoidedBy(patch.getVoidedBy());
                }
                if (patch.getVoidReason() != null)
                {
                    stored.setVoidReason(patch.getVoidReason());
                }
                return 1;
            }
            if ("clearApprovalTrace".equals(name))
            {
                String id = (String) args[0];
                ErpPurchaseRequest requestStored = requestStore.get(id);
                if (requestStored != null)
                {
                    requestStored.setApprovedBy(null);
                    requestStored.setApprovedAt(null);
                    return 1;
                }
                ErpPurchaseOrder orderStored = orderStore.get(id);
                if (orderStored != null)
                {
                    orderStored.setApprovedBy(null);
                    orderStored.setApprovedAt(null);
                    return 1;
                }
                return 0;
            }
            if ("deleteRequestById".equals(name))
            {
                return requestStore.remove((String) args[0]) == null ? 0 : 1;
            }
            /* ---------- 采购申请行项 ---------- */
            if ("selectItemsByDocId".equals(name))
            {
                String docId = (String) args[0];
                // 同一个分派器服务四个 Mapper：按"表头在哪个内存表里"区分单据与采购单
                if (orderStore.containsKey(docId))
                {
                    return orderItemsOfDoc(docId);
                }
                return itemsOfDoc(docId);
            }
            if ("selectRemainItemsByDocId".equals(name))
            {
                List<ErpPurchaseRequestItem> items = itemsOfDoc((String) args[0]);
                for (ErpPurchaseRequestItem item : items)
                {
                    item.setRemainingQty(ErpPurRules.remainingQtyOf(item));
                }
                return items;
            }
            if ("selectItemsByDocIds".equals(name))
            {
                List<ErpPurchaseRequestItem> result = new ArrayList<>();
                for (String docId : asStringList(args[0]))
                {
                    result.addAll(itemsOfDoc(docId));
                }
                return result;
            }
            if ("selectItemById".equals(name))
            {
                return requestItemStore.get((String) args[0]);
            }
            if ("batchInsertItems".equals(name))
            {
                List<?> items = (List<?>) args[0];
                for (Object item : items)
                {
                    if (item instanceof ErpPurchaseRequestItem)
                    {
                        ErpPurchaseRequestItem line = (ErpPurchaseRequestItem) item;
                        requestItemStore.put(line.getId(), line);
                    }
                    else if (item instanceof ErpPurchaseOrderItem)
                    {
                        ErpPurchaseOrderItem line = (ErpPurchaseOrderItem) item;
                        orderItemStore.put(line.getId(), line);
                    }
                }
                return items.size();
            }
            if ("increaseOrderedQty".equals(name))
            {
                ErpPurchaseRequestItem item = requestItemStore.get((String) args[0]);
                if (item == null)
                {
                    return 0;
                }
                BigDecimal qty = (BigDecimal) args[1];
                BigDecimal current = item.getOrderedQty() == null ? BigDecimal.ZERO : item.getOrderedQty();
                item.setOrderedQty(current.add(qty));
                return 1;
            }
            if ("updateItemSeq".equals(name))
            {
                ErpPurchaseRequestItem item = requestItemStore.get((String) args[0]);
                if (item != null)
                {
                    item.setSeq((Integer) args[1]);
                }
                ErpPurchaseOrderItem orderItem = orderItemStore.get((String) args[0]);
                if (orderItem != null)
                {
                    orderItem.setSeq((Integer) args[1]);
                }
                return 1;
            }
            if ("deleteItemById".equals(name))
            {
                String id = (String) args[0];
                boolean removed = requestItemStore.remove(id) != null;
                removed = orderItemStore.remove(id) != null || removed;
                return removed ? 1 : 0;
            }
            if ("deleteItemsByDocId".equals(name))
            {
                String docId = (String) args[0];
                List<String> ids = new ArrayList<>();
                for (ErpPurchaseRequestItem item : requestItemStore.values())
                {
                    if (docId.equals(item.getDocId()))
                    {
                        ids.add(item.getId());
                    }
                }
                for (String id : ids)
                {
                    requestItemStore.remove(id);
                }
                List<String> orderIds = new ArrayList<>();
                for (ErpPurchaseOrderItem item : orderItemStore.values())
                {
                    if (docId.equals(item.getDocId()))
                    {
                        orderIds.add(item.getId());
                    }
                }
                for (String id : orderIds)
                {
                    orderItemStore.remove(id);
                }
                return ids.size() + orderIds.size();
            }
            /* ---------- 采购单表头 ---------- */
            if ("selectOrderList".equals(name))
            {
                return filterOrders((ErpPurchaseOrder) args[0]);
            }
            if ("selectOrderById".equals(name))
            {
                return orderStore.get((String) args[0]);
            }
            if ("selectOrderByNo".equals(name))
            {
                for (ErpPurchaseOrder order : orderStore.values())
                {
                    if (order.getDocNo() != null && order.getDocNo().equals(args[0]))
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
                if (order.getItems() != null)
                {
                    for (ErpPurchaseOrderItem item : order.getItems())
                    {
                        orderItemStore.put(item.getId(), item);
                    }
                }
                return 1;
            }
            if ("updateOrder".equals(name) || "updateOrderStatus".equals(name)
                    || "updateOrderTotal".equals(name))
            {
                ErpPurchaseOrder patch = (ErpPurchaseOrder) args[0];
                ErpPurchaseOrder stored = orderStore.get(patch.getId());
                if (stored == null)
                {
                    return 0;
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
                if (patch.getVoidReason() != null)
                {
                    stored.setVoidReason(patch.getVoidReason());
                }
                return 1;
            }
            if ("deleteOrderById".equals(name))
            {
                return orderStore.remove((String) args[0]) == null ? 0 : 1;
            }
            /* ---------- 采购单行项 ---------- */
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
            throw new UnsupportedOperationException("桩未实现：" + method.getDeclaringClass().getSimpleName()
                    + "#" + name);
        }

        private List<String> asStringList(Object arg)
        {
            List<String> result = new ArrayList<>();
            if (arg instanceof List)
            {
                for (Object value : (List<?>) arg)
                {
                    result.add(String.valueOf(value));
                }
            }
            return result;
        }

        private List<ErpPurchaseRequestItem> itemsOfDoc(String docId)
        {
            List<ErpPurchaseRequestItem> result = new ArrayList<>();
            for (ErpPurchaseRequestItem item : requestItemStore.values())
            {
                if (docId != null && docId.equals(item.getDocId()))
                {
                    result.add(item);
                }
            }
            result.sort(new java.util.Comparator<ErpPurchaseRequestItem>()
            {
                @Override
                public int compare(ErpPurchaseRequestItem left, ErpPurchaseRequestItem right)
                {
                    int l = left.getSeq() == null ? 0 : left.getSeq().intValue();
                    int r = right.getSeq() == null ? 0 : right.getSeq().intValue();
                    return Integer.compare(l, r);
                }
            });
            return result;
        }

        private List<ErpPurchaseOrderItem> orderItemsOfDoc(String docId)
        {
            List<ErpPurchaseOrderItem> result = new ArrayList<>();
            for (ErpPurchaseOrderItem item : orderItemStore.values())
            {
                if (docId != null && docId.equals(item.getDocId()))
                {
                    result.add(item);
                }
            }
            result.sort(new java.util.Comparator<ErpPurchaseOrderItem>()
            {
                @Override
                public int compare(ErpPurchaseOrderItem left, ErpPurchaseOrderItem right)
                {
                    int l = left.getSeq() == null ? 0 : left.getSeq().intValue();
                    int r = right.getSeq() == null ? 0 : right.getSeq().intValue();
                    return Integer.compare(l, r);
                }
            });
            return result;
        }

        private List<ErpPurchaseRequest> filterRequests(ErpPurchaseRequest query)
        {
            List<ErpPurchaseRequest> result = new ArrayList<>();
            for (ErpPurchaseRequest stored : requestStore.values())
            {
                if (query == null)
                {
                    result.add(stored);
                    continue;
                }
                if (query.getId() != null && !query.getId().equals(stored.getId()))
                {
                    continue;
                }
                if (query.getStatus() != null && !query.getStatus().equals(stored.getStatus()))
                {
                    continue;
                }
                if (!query.includeVoidedForQuery() && ErpDocStatus.VOIDED.equals(stored.getStatus()))
                {
                    continue;
                }
                if (query.getKeyword() != null && (stored.getPurpose() == null
                        || !stored.getPurpose().contains(query.getKeyword())))
                {
                    continue;
                }
                result.add(stored);
            }
            return result;
        }

        private List<ErpPurchaseOrder> filterOrders(ErpPurchaseOrder query)
        {
            List<ErpPurchaseOrder> result = new ArrayList<>();
            for (ErpPurchaseOrder stored : orderStore.values())
            {
                if (query == null)
                {
                    result.add(stored);
                    continue;
                }
                if (query.getId() != null && !query.getId().equals(stored.getId()))
                {
                    continue;
                }
                if (!query.includeVoidedForQuery() && ErpDocStatus.VOIDED.equals(stored.getStatus()))
                {
                    continue;
                }
                result.add(stored);
            }
            return result;
        }
    }
}
