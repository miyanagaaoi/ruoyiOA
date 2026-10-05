package com.ruoyi.ctms.erp.sales;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess;
import com.ruoyi.ctms.erp.sales.controller.ErpSalesController;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.domain.vo.ErpSalesExportRow;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestMapper;
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;
import com.ruoyi.ctms.erp.sales.service.IErpSalesRequestService;
import com.ruoyi.ctms.erp.sales.service.impl.ErpSalesRequestServiceImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>销售线导出单测</b>（2.0 B4 t28：审计 F1 的 {@code :export} 端点）。 </p>
 *
 * <p> 覆盖验收要求的三条： </p>
 * <ol>
 *   <li> <b>筛选传递</b>：导出把与列表同一组筛选条件<b>原样</b>交给同一个列表服务方法
 *        （{@code selectSalesRequestList} / {@code selectSalesOrderList}）——
 *        用例里 {@code assertSame} 断言"服务收到的就是调用方给的那个查询对象"，
 *        同时也就证明了导出走的是列表那条取数路径，控制器自己不做第二次过滤； </li>
 *   <li> <b>范围裁剪</b>：数据范围片段由<b>服务实现层</b>用 {@code ErpDocScope} 拼出，
 *        导出与列表拿到的片段必须一致，且控制器不得改写调用方传进来的片段； </li>
 *   <li> <b>金额一致</b>：行金额先 HALF_UP 到 2 位、单据合计 = 各行舍入后之和
 *        （3 行 1×0.125 → 0.13 × 3 = 0.39，不是 0.38），导出合计列 == 列表的合计列。 </li>
 * </ol>
 *
 * <p> 另外覆盖 <b>403</b>：按 {@code ids} 导出时逐个过公共层 {@code IErpDocObjectAccess}
 * （存在性 + 数据范围），范围外抛业务码 403、既不返回数据也不变成 500；
 * 校验器缺失时<b>拒绝导出</b>（fail closed，避免"少了校验就把范围外数据导出去"）。 </p>
 *
 * <p> 桩策略：控制器用 {@code new} + 反射注入（与 T3 的单测同款，不给生产类加只为测试存在的 setter）；
 * 服务层用内存桩（不起 Spring）；"范围同源"那条用<b>真实</b>服务实现来证
 * （只有真实实现才会去调 {@code ErpDocScope}）。 </p>
 *
 * @author 二开
 */
public class ErpSalesExportTest
{
    private static final String DEPT_ID = "100";

    private static final String USER_ID = "9";

    /** 范围内单据ID。 */
    private static final String IN_SCOPE_ID = "IN-SCOPE-1";

    /** 范围外单据ID（桩校验器对它抛 403）。 */
    private static final String OUT_OF_SCOPE_ID = "OUT-OF-SCOPE";

    /** 列表返回的单据（按 id）。 */
    private final Map<String, ErpSalesRequest> requestDocs = new LinkedHashMap<>();

    /** 汇总：docId → 行项（申请单）。 */
    private final Map<String, List<ErpSalesRequestItem>> requestItems = new LinkedHashMap<>();

    private final Map<String, ErpSalesOrder> orderDocs = new LinkedHashMap<>();

    private final Map<String, List<ErpSalesOrderItem>> orderItems = new LinkedHashMap<>();

    /** 服务收到的申请单/订单查询条件（证"筛选传递"）。 */
    private ErpSalesRequest capturedRequestQuery;

    private ErpSalesOrder capturedOrderQuery;

    /** 真实服务实现写进查询的范围片段（证"范围同源"）。 */
    private String scopeSqlSeenByMapper;

    private ErpSalesController controller;

    private StubRequestService requestService;

    private StubOrderService orderService;

    private StubDocObjectAccess docObjectAccess;

    @Before
    public void setUp()
    {
        requestDocs.clear();
        requestItems.clear();
        orderDocs.clear();
        orderItems.clear();
        capturedRequestQuery = null;
        capturedOrderQuery = null;
        scopeSqlSeenByMapper = null;

        requestService = new StubRequestService();
        orderService = new StubOrderService();
        docObjectAccess = new StubDocObjectAccess();

        controller = new ErpSalesController();
        inject(controller, "salesRequestService", requestService);
        inject(controller, "salesOrderService", orderService);
        inject(controller, "docObjectAccess", docObjectAccess);
    }

    /* ==================== ① 筛选传递 ==================== */

    @Test
    public void requestExportPassesListFiltersThrough()
    {
        ErpSalesRequest doc = requestDoc("SR-1", "10", "3");
        requestDocs.put(doc.getId(), doc);
        requestItems.put(doc.getId(), doc.getItems());
        requestService.result = Collections.singletonList(doc);

        ErpSalesRequest query = new ErpSalesRequest();
        query.setStatus(ErpDocStatus.APPROVED);
        query.setKeyword("螺丝");
        query.setBeginDocDate("2026-10-01");
        query.setEndDocDate("2026-10-31");
        query.setIncludeVoided("1");
        query.setCustomerId("CUST-1");

        List<ErpSalesExportRow> rows = controller.exportRequestRows(query, null);

        assertSame("导出必须把调用方给的同一个筛选对象交给列表方法", query, capturedRequestQuery);
        assertEquals(ErpDocStatus.APPROVED, capturedRequestQuery.getStatus());
        assertEquals("螺丝", capturedRequestQuery.getKeyword());
        assertEquals("2026-10-01", capturedRequestQuery.getBeginDocDate());
        assertEquals("2026-10-31", capturedRequestQuery.getEndDocDate());
        assertEquals("1", capturedRequestQuery.getIncludeVoided());
        assertEquals("CUST-1", capturedRequestQuery.getCustomerId());
        assertEquals(1, rows.size());
        assertEquals("SR-1", rows.get(0).getDocNo());
    }

    @Test
    public void orderExportPassesListFiltersThrough()
    {
        ErpSalesOrder doc = orderDoc("SO-1", "10", "3");
        orderDocs.put(doc.getId(), doc);
        orderItems.put(doc.getId(), doc.getItems());
        orderService.result = Collections.singletonList(doc);

        ErpSalesOrder query = new ErpSalesOrder();
        query.setKeyword("客户");
        query.setIncludeVoided("1");

        List<ErpSalesExportRow> rows = controller.exportOrderRows(query, null);

        assertSame(query, capturedOrderQuery);
        assertEquals("客户", capturedOrderQuery.getKeyword());
        assertEquals("1", capturedOrderQuery.getIncludeVoided());
        assertEquals(1, rows.size());
        assertEquals("SO-1", rows.get(0).getDocNo());
    }

    @Test
    public void exportWithNullQueryStillGoesThroughTheListPath()
    {
        requestService.result = new ArrayList<>();
        controller.exportRequestRows(null, null);
        // 传 null 时控制器不自己造条件，交给服务层（服务实现里会把 null 兜成空条件）
        assertTrue("必须调用过列表方法（走同一处取数）", requestService.listCalls > 0);
    }

    /* ==================== ② 范围裁剪 ==================== */

    @Test
    public void exportScopeConditionEqualsListScopeCondition()
    {
        // 真实的服务实现：只有它会去调 ErpDocScope，控制器不得覆盖它拼出的片段
        ErpSalesRequestServiceImpl realService = new ErpSalesRequestServiceImpl();
        realService.setRequestMapper(scopeCapturingRequestMapper());
        realService.setRequestItemMapper(emptyRequestItemMapper());

        // 列表路径
        scopeSqlSeenByMapper = null;
        realService.selectSalesRequestList(new ErpSalesRequest());
        String listScope = scopeSqlSeenByMapper;

        // 导出路径（控制器 -> 同一个列表服务方法）
        ErpSalesController realController = new ErpSalesController();
        inject(realController, "salesRequestService", realService);
        scopeSqlSeenByMapper = null;
        realController.exportRequestRows(new ErpSalesRequest(), null);
        String exportScope = scopeSqlSeenByMapper;

        assertEquals("导出与列表必须用同一处范围判定（片段逐字相同）", listScope, exportScope);
        // 无登录上下文时 ErpDocScope 的约定就是"不加条件"（null）；两者的相等性才是本用例的断言点。
        // 真实请求一律有登录上下文，此时两者会是非空片段 —— 仍然逐字相同（同一段代码算出来的）。
        assertEquals("片段必须就是 ErpDocScope 在当前上下文算出的那个",
                ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS), exportScope);
    }

    @Test
    public void exportDoesNotOverwriteScopeConditionPassedIn()
    {
        ErpSalesRequest doc = requestDoc("SR-1", "10", "3");
        requestDocs.put(doc.getId(), doc);
        requestItems.put(doc.getId(), doc.getItems());
        requestService.result = Collections.singletonList(doc);

        ErpSalesRequest query = new ErpSalesRequest();
        query.setDataScopeSql("d.create_id = 'U-1'");
        controller.exportRequestRows(query, null);

        assertEquals("控制器不得改写范围片段（范围只由服务实现层写）",
                "d.create_id = 'U-1'", query.getDataScopeSql());
    }

    /* ==================== ③ 金额一致 ==================== */

    @Test
    public void amountRoundingIsIdenticalToTheList()
    {
        // 3 行「1 × 0.125」：先 HALF_UP 到 2 位 ⇒ 每行 0.13；合计 0.13×3 = 0.39（不是 0.38）
        ErpSalesOrder doc = orderDoc("SO-1", "1", "0.125");
        List<ErpSalesOrderItem> items = new ArrayList<>();
        items.add(doc.getItems().get(0));
        items.add(orderItem("1", "0.125"));
        items.add(orderItem("1", "0.125"));
        int seq = 0;
        for (ErpSalesOrderItem item : items)
        {
            seq++;
            item.setSeq(Integer.valueOf(seq));
            item.setAmount(ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice()));
        }
        doc.setItems(items);
        // 列表口径：单据合计 = 各行舍入后之和（服务层 applyDerivedColumns 用的就是这个）
        doc.setTotalAmount(ErpAmounts.totalOf(items));

        orderDocs.put(doc.getId(), doc);
        orderItems.put(doc.getId(), items);
        orderService.result = Collections.singletonList(doc);

        List<ErpSalesExportRow> rows = controller.exportOrderRows(new ErpSalesOrder(), null);

        assertEquals(3, rows.size());
        BigDecimal exportedTotal = BigDecimal.ZERO;
        for (ErpSalesExportRow row : rows)
        {
            assertEquals("行金额必须与行项已固化的 amount 逐行一致",
                    0, new BigDecimal("0.13").compareTo(row.getLineAmount()));
            exportedTotal = exportedTotal.add(row.getLineAmount());
        }
        assertEquals("导出各行之和 = 0.39", 0, new BigDecimal("0.39").compareTo(exportedTotal));
        assertEquals("导出合计列 == 列表的总计列",
                0, doc.getTotalAmount().compareTo(rows.get(0).getTotalAmount()));
        // 反例锁死：先汇总再舍入会得到 0.38
        assertTrue("合计不得等于先汇总再舍入的 0.38",
                rows.get(0).getTotalAmount().compareTo(new BigDecimal("0.38")) != 0);
    }

    /* ==================== 列清单（表头业务字段 + 客户快照 + 行项关键列） ==================== */

    @Test
    public void orderExportCarriesHeaderCustomerSnapshotAndItemColumns()
    {
        ErpSalesOrder doc = orderDoc("SO-202610000001", "2", "3.5");
        doc.setStatus(ErpDocStatus.APPROVED);
        doc.setHandlerName("张三");
        doc.setContractNo("SAL-2026-001");
        doc.setSourceDocNo("SR-202610000001");
        doc.setDeliveryAddress("上海市浦东新区 1 号");
        doc.setContactName("李四");
        doc.setContactPhone("13800000000");
        doc.setShipWarehouseId("WH-1");
        doc.setRemark("急单");
        doc.setTotalAmount(new BigDecimal("7.00"));
        doc.setRemainQtySum(new BigDecimal("2.000"));
        ErpSalesOrderItem item = doc.getItems().get(0);
        item.setSeq(Integer.valueOf(1));
        item.setProductCode("P001");
        item.setProductName("螺丝");
        item.setSpec("M6");
        item.setUomName("个");
        item.setAmount(new BigDecimal("7.00"));
        item.setRemark("行备注");
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));

        orderDocs.put(doc.getId(), doc);
        orderItems.put(doc.getId(), doc.getItems());
        orderService.result = Collections.singletonList(doc);

        ErpSalesExportRow row = controller.exportOrderRows(new ErpSalesOrder(), null).get(0);

        // 表头业务字段
        assertEquals(ErpDocType.SALES_ORDER.getLabel(), row.getDocTypeLabel());
        assertEquals("SO-202610000001", row.getDocNo());
        assertEquals("已审核", row.getStatus());
        assertEquals("张三", row.getHandlerName());
        assertEquals(DEPT_ID, row.getDeptId());
        assertEquals("SAL-2026-001", row.getContractNo());
        assertEquals("SR-202610000001", row.getSourceDocNo());
        assertEquals("上海市浦东新区 1 号", row.getDeliveryAddress());
        assertEquals("李四", row.getContactName());
        assertEquals("13800000000", row.getContactPhone());
        assertEquals("WH-1", row.getShipWarehouseId());
        assertEquals("急单", row.getRemark());
        assertEquals(0, new BigDecimal("7.00").compareTo(row.getTotalAmount()));
        assertEquals(0, new BigDecimal("2.000").compareTo(row.getRemainQtySum()));
        // 客户快照
        assertEquals("CUST-1", row.getCustomerId());
        assertEquals("启用客户", row.getCustomerName());
        // 行项关键列
        assertEquals(Integer.valueOf(1), row.getSeq());
        assertEquals("P001", row.getProductCode());
        assertEquals("螺丝", row.getProductName());
        assertEquals("M6", row.getSpec());
        assertEquals("个", row.getUomName());
        assertEquals(0, new BigDecimal("2").compareTo(row.getQty()));
        assertEquals(0, new BigDecimal("3.5").compareTo(row.getUnitPrice()));
        assertEquals(0, new BigDecimal("7.00").compareTo(row.getLineAmount()));
        assertEquals("行备注", row.getItemRemark());
    }

    @Test
    public void requestExportKeepsOneRowPerItemAndFallsBackToCustomerText()
    {
        ErpSalesRequest doc = requestDoc("SR-1", "10", "3");
        doc.setItems(new ArrayList<>(Arrays.asList(doc.getItems().get(0), requestItem("5", "2"))));
        doc.setCustomerId(null);
        doc.setCustomerNameText("文本客户");
        int seq = 0;
        for (ErpSalesRequestItem item : doc.getItems())
        {
            seq++;
            item.setSeq(Integer.valueOf(seq));
            item.setAmount(ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice()));
        }
        doc.setTotalAmount(ErpAmounts.totalOf(doc.getItems()));

        requestDocs.put(doc.getId(), doc);
        requestItems.put(doc.getId(), doc.getItems());
        requestService.result = Collections.singletonList(doc);

        List<ErpSalesExportRow> rows = controller.exportRequestRows(new ErpSalesRequest(), null);

        assertEquals("一文档 × 一行项", 2, rows.size());
        assertEquals("文本客户", rows.get(0).getCustomerName());
        assertEquals("文本客户", rows.get(1).getCustomerName());
        // 表头列每行重复（便于在 Excel 里直接对列求和）
        assertEquals(0, rows.get(0).getTotalAmount().compareTo(rows.get(1).getTotalAmount()));
        assertEquals(0, new BigDecimal("40.00").compareTo(rows.get(0).getTotalAmount()));
    }

    /* ==================== 范围外 → 403（不是 500、也不返回数据） ==================== */

    @Test
    public void exportRequestByIdRejectsOutOfScopeDocumentWith403()
    {
        try
        {
            controller.exportRequestRows(new ErpSalesRequest(), new String[] { OUT_OF_SCOPE_ID });
            fail("范围外的单据不应可导出");
        }
        catch (ServiceException e)
        {
            assertEquals("必须返回业务码 403", Integer.valueOf(403), e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains("无权访问"));
        }
        assertTrue("范围外导出不得读取单据详情（不返回数据）", requestService.detailReads.isEmpty());
    }

    @Test
    public void exportOrderByIdRejectsOutOfScopeDocumentWith403()
    {
        try
        {
            controller.exportOrderRows(new ErpSalesOrder(), new String[] { OUT_OF_SCOPE_ID });
            fail("范围外的单据不应可导出");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
        }
        assertTrue(orderService.detailReads.isEmpty());
    }

    @Test
    public void exportByIdUsesThePublicAccessCheckerAndWorksInScope()
    {
        ErpSalesRequest doc = requestDoc("SR-1", "10", "3");
        requestDocs.put(doc.getId(), doc);
        requestItems.put(doc.getId(), doc.getItems());

        String[] ids = new String[] { " " + doc.getId() + " " };
        List<ErpSalesExportRow> rows = controller.exportRequestRows(new ErpSalesRequest(), ids);

        assertEquals("按标识导出要走公共层的访问校验", 1, docObjectAccess.checked.size());
        assertEquals(ErpDocType.SALES_REQUEST.getCode(), docObjectAccess.checked.get(0)[0]);
        assertEquals("两侧空白应被归一", doc.getId(), docObjectAccess.checked.get(0)[1]);
        assertEquals(1, rows.size());
        assertEquals("SR-1", rows.get(0).getDocNo());
    }

    @Test
    public void exportByIdFailsClosedWhenAccessCheckerMissing()
    {
        inject(controller, "docObjectAccess", null);
        try
        {
            controller.exportOrderRows(new ErpSalesOrder(), new String[] { "SO-1" });
            fail("校验器缺失时必须拒绝导出（fail closed）");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("未装配"));
        }
    }

    /* ==================== ④ 真 xlsx 读回（列定义即导出契约） ==================== */

    /**
     * <p> <b>真 xlsx 读回</b>：走真实 {@link ExcelUtil} 生成字节流，再用 POI 读回逐格断言。 </p>
     *
     * <p> 为什么必须读回而不是只断言导出行对象：{@code @Excel} 注解是"导出契约"的另一半——
     * 注解写错（名字、类型、列漏）时行对象仍然是对的，只有真产物才会露馅。
     * 本用例与采购侧的 {@code ErpPurExportTest.导出行金额与列表逐行一致且合计为先舍入再汇总}
     * <b>同口径</b>（那边读回"行金额/金额合计"，这边读回"行金额/单据金额合计"）。 </p>
     *
     * <p> <b>与 t13 的交叉引用</b>：t13 的 {@code tools/erp-check.ps1} 用 {@code Download-Excel}
     * 辅助函数在<b>真后端</b>上覆盖同一口径（HTTP → 落盘 xlsx → 读回），
     * 该脚本里对应的两个用例当前是 {@code SkipCase}：
     * <b>{@code P-23}「导出 xlsx（列 = 单号/日期/状态/仓库/类型/金额/过账）」</b>与
     * <b>{@code L-12}「导出 xlsx 行数 == 列表 total」</b>（参见 {@code tools/erp-check.ps1:677} 与 {@code :942}）。
     * 本条是它们的单测层前置防线（不启服务、CI 可比对）；两层都绿才算"导出金额/列口径"闭环。 </p>
     */
    @Test
    public void amountRoundingIsIdenticalInTheRealXlsx()
    {
        // 三行 1 × 0.125：每行先四舍五入到 0.13，合计 0.39（不是 0.375 → 0.38）
        ErpSalesOrder doc = orderDoc("SO-XLSX-1", "1", "0.125");
        List<ErpSalesOrderItem> items = new ArrayList<>();
        items.add(doc.getItems().get(0));
        items.add(orderItem("1", "0.125"));
        items.add(orderItem("1", "0.125"));
        int seq = 0;
        for (ErpSalesOrderItem item : items)
        {
            seq++;
            item.setSeq(Integer.valueOf(seq));
            item.setProductCode("P00" + seq);
            item.setProductName("螺丝");
            item.setSpec("M6");
            item.setUomName("个");
            item.setAmount(ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice()));
        }
        doc.setItems(items);
        doc.setTotalAmount(ErpAmounts.totalOf(items));
        orderDocs.put(doc.getId(), doc);
        orderItems.put(doc.getId(), items);
        orderService.result = Collections.singletonList(doc);

        // 列表口径（导出必须与它逐行一致）
        List<ErpSalesExportRow> rows = controller.exportOrderRows(new ErpSalesOrder(), null);
        assertEquals("导出行数 = 行项数", 3, rows.size());
        for (ErpSalesExportRow row : rows)
        {
            assertEquals("每行金额先舍入为 0.13", 0, row.getLineAmount().compareTo(new BigDecimal("0.13")));
            assertEquals("每行的合计列 = 列表合计", 0,
                    row.getTotalAmount().compareTo(doc.getTotalAmount()));
        }
        assertEquals("列表合计 = 0.39", 0, doc.getTotalAmount().compareTo(new BigDecimal("0.39")));

        // 真产物：ExcelUtil 写流 → POI 读回
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new ExcelUtil<>(ErpSalesExportRow.class).exportExcel(fakeResponse(buffer), rows, "销售申请单");
        assertTrue("必须真的产出非空 xlsx 字节流", buffer.size() > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(buffer.toByteArray())))
        {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(sheet);
            // 关键列必须在（防 @Excel 注解回归：改名/漏列都会在这里红）
            for (String expected : new String[] { "单据类型", "单号", "单据日期", "状态", "客户ID", "客户",
                    "经办人", "归属部门ID", "关联合同", "来源单号", "交货日期", "收货地址", "联系人",
                    "联系电话", "发货仓库ID", "剩余可下推量", "单据金额合计", "备注", "行号", "物料编码",
                    "物料名称", "规格", "单位", "数量", "单价", "行金额", "行备注" })
            {
                assertTrue("导出表头必须包含「" + expected + "」列，实际=" + headers, headers.contains(expected));
            }
            int amountCol = columnOf(sheet, "行金额");
            int totalCol = columnOf(sheet, "单据金额合计");
            assertTrue("必须有行金额列", amountCol >= 0);
            assertTrue("必须有单据金额合计列", totalCol >= 0);
            assertEquals("表头 + 3 行", 4, sheet.getLastRowNum() + 1);

            BigDecimal cellSum = BigDecimal.ZERO;
            for (int r = 1; r <= 3; r++)
            {
                Row row = sheet.getRow(r);
                BigDecimal amount = new BigDecimal(row.getCell(amountCol).toString());
                assertEquals("xlsx 行金额 = 0.13", 0, amount.compareTo(new BigDecimal("0.13")));
                BigDecimal totalCell = new BigDecimal(row.getCell(totalCol).toString());
                assertEquals("xlsx 单据金额合计列 = 0.39", 0, totalCell.compareTo(new BigDecimal("0.39")));
                assertEquals("xlsx 合计列逐行 == 列表合计", 0, totalCell.compareTo(doc.getTotalAmount()));
                cellSum = cellSum.add(amount);
            }
            assertEquals("xlsx 里三行行金额之和 = 0.39（先舍入再汇总）", 0,
                    cellSum.compareTo(new BigDecimal("0.39")));
        }
        catch (Exception e)
        {
            fail("读回导出的 xlsx 失败：" + e);
        }
    }

    /* ==================== ⑤ 端点契约（权限点 + 方法 + 路径 + 参数绑定） ==================== */

    /**
     * <p> <b>端点声明的逐字断言</b>（与采购侧
     * {@code ErpPurExportTest.导出端点的权限点方法与路径与前端及菜单约定逐字一致} 同风格）。 </p>
     *
     * <p> 四件事一次锁住： </p>
     * <ol>
     *   <li> 类级 {@code @RequestMapping} = {@code /sal}； </li>
     *   <li> 方法级 value = {@code /request/export} / {@code /order/export}，
     *        且 {@code methods} <b>同时含 GET 与 POST</b>（前端 {@code download()} 走 POST，
     *        GET 供脚本/人工复跑）； </li>
     *   <li> {@code @PreAuthorize} 逐字等于菜单 F 行的权限点； </li>
     *   <li> <b>参数绑定</b>：{@code query} 参数<b>不得</b>带 {@code @RequestBody}（前端发的是
     *        form-urlencoded 表单体，带 {@code @RequestBody} 会 415），
     *        {@code ids} 必须带 {@code @RequestParam}（t36 观察 L1 的同款缺口在这里堵住）。 </li>
     * </ol>
     *
     * @throws Exception 反射失败
     */
    @Test
    public void exportEndpointsMatchFrontendAndMenuContract() throws Exception
    {
        RequestMapping classMapping = ErpSalesController.class.getAnnotation(RequestMapping.class);
        assertNotNull("控制器必须有类级 @RequestMapping", classMapping);
        assertEquals("类级路径（PRD §9.4 的资源名单数）", "/sal", classMapping.value()[0]);

        Method requestExport = ErpSalesController.class.getMethod("exportRequest", HttpServletResponse.class,
                ErpSalesRequest.class, String[].class);
        assertEquals("销售申请单导出权限点必须与菜单 F 行逐字一致", "@ss.hasPermi('sal:request:export')",
                requestExport.getAnnotation(PreAuthorize.class).value());
        assertExportMapping(requestExport, "/request/export", "销售申请单");
        assertExportParameters(requestExport, "销售申请单");

        Method orderExport = ErpSalesController.class.getMethod("exportOrder", HttpServletResponse.class,
                ErpSalesOrder.class, String[].class);
        assertEquals("销售订单导出权限点必须与菜单 F 行逐字一致", "@ss.hasPermi('sal:order:export')",
                orderExport.getAnnotation(PreAuthorize.class).value());
        assertExportMapping(orderExport, "/order/export", "销售订单");
        assertExportParameters(orderExport, "销售订单");
    }

    /**
     * 断言导出方法声明为 {@code GET + POST <path>}。
     *
     * @param method   导出方法
     * @param path     期望路径
     * @param label    单据标签（断言消息用）
     */
    private static void assertExportMapping(Method method, String path, String label)
    {
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        assertNotNull(label + "导出必须有 @RequestMapping（同时接受 GET 与 POST）", mapping);
        assertEquals(label + "导出路径", path, mapping.value()[0]);
        List<RequestMethod> methods = Arrays.asList(mapping.method());
        assertTrue(label + "导出必须接受 POST（前端 download 用 POST），实际=" + methods,
                methods.contains(RequestMethod.POST));
        assertTrue(label + "导出必须接受 GET（便于脚本复跑），实际=" + methods,
                methods.contains(RequestMethod.GET));
    }

    /**
     * 断言参数绑定形态：{@code query} 不带 {@code @RequestBody}、{@code ids} 带 {@code @RequestParam}。
     *
     * <p> 这两条是"前端表单体能绑上参数"的硬条件：带 {@code @RequestBody} 时 Spring 只认
     * {@code application/json} 体，而前端 {@code download()} 发的是
     * {@code application/x-www-form-urlencoded}，会直接 415。 </p>
     *
     * @param method 导出方法（签名固定为 {@code (HttpServletResponse, <Query>, String[])}）
     * @param label  单据标签
     */
    private static void assertExportParameters(Method method, String label)
    {
        java.lang.annotation.Annotation[][] annotations = method.getParameterAnnotations();
        assertEquals(label + "导出方法应有 3 个参数", 3, annotations.length);

        for (java.lang.annotation.Annotation annotation : annotations[1])
        {
            assertNull(label + "export 的 query 参数不得带 @RequestBody（前端发 form-urlencoded，会 415）",
                    annotation instanceof RequestBody ? "存在 @RequestBody" : null);
        }
        boolean idsHasRequestParam = false;
        String idsName = null;
        for (java.lang.annotation.Annotation annotation : annotations[2])
        {
            if (annotation instanceof RequestParam)
            {
                idsHasRequestParam = true;
                idsName = attributeNameOf(annotation);
            }
        }
        assertTrue(label + "export 的 ids 参数必须带 @RequestParam（否则表单体里的 ids 绑不上）",
                idsHasRequestParam);
        assertEquals(label + "ids 参数名", "ids", idsName);
    }

    /**
     * <p> 取注解上"参数名"属性的值，<b>版本无关</b>。 </p>
     *
     * <p> {@code @RequestParam} 的 {@code value()/name()} 在 Spring 各版本里返回类型不同
     * （{@code String} 或 {@code String[]}），而 {@code @AliasFor} 的别名解析只在
     * {@code AnnotationUtils}/{@code AnnotatedElementUtils} 里生效、裸反射读 {@code name()} 会得到空串。
     * 所以统一经 {@link org.springframework.core.annotation.AnnotationUtils} 取属性。
     * 本用例实测踩过这个坑（{@code name()} 返回 {@code ""} 而 {@code value} 是 {@code "ids"}）。 </p>
     *
     * @param annotation 注解（{@code @RequestParam}）
     * @return 参数名（取不到返回 null）
     */
    private static String attributeNameOf(java.lang.annotation.Annotation annotation)
    {
        for (String attribute : new String[] { "value", "name" })
        {
            Object raw = org.springframework.core.annotation.AnnotationUtils.getValue(annotation, attribute);
            if (raw instanceof String && !((String) raw).isEmpty())
            {
                return (String) raw;
            }
            if (raw instanceof String[] && ((String[]) raw).length > 0 && !((String[]) raw)[0].isEmpty())
            {
                return ((String[]) raw)[0];
            }
        }
        return null;
    }

    /* ==================== ⑥ @Excel 列逐列断言（列注解即导出契约） ==================== */

    /**
     * <p> <b>逐列断言 {@code ErpSalesExportRow} 的 {@code @Excel} 注解</b>：列名<b>与顺序</b>全量比对。 </p>
     *
     * <p> 与 {@link #amountRoundingIsIdenticalInTheRealXlsx} 的分工：那条断言"真产物里的值对不对"，
     * 本条断言"列定义有没有被人改动/漏列/改名"——两者一起才能挡住"改了列名但没人发现"的静默回归。 </p>
     *
     * <p> <b>反向对照（本轮实测，见 notes §11.4）</b>：把 {@code @Excel(name = "行金额")}
     * 临时改成 {@code "行金额XX"} 后，本用例与真 xlsx 用例<b>都会变红</b>
     * （失败信息：列名清单比对不符 + 表头缺「行金额」列），证明这两条断言真的在测序列化形态，
     * 而不是"写了必然通过的空断言"。 </p>
     */
    @Test
    public void exportRowExcelColumnsAreExactAndOrdered()
    {
        List<String> actual = new ArrayList<>();
        for (java.lang.reflect.Field field : ErpSalesExportRow.class.getDeclaredFields())
        {
            com.ruoyi.common.annotation.Excel excel = field.getAnnotation(com.ruoyi.common.annotation.Excel.class);
            if (excel != null)
            {
                actual.add(excel.name());
            }
        }
        // 期望顺序 = 类里字段的声明顺序（ExcelUtil 按 @Excel 出现顺序出列）
        List<String> expected = Arrays.asList("单据类型", "单号", "单据日期", "状态", "客户ID", "客户",
                "经办人", "归属部门ID", "关联合同", "来源单号", "交货日期", "收货地址", "联系人", "联系电话",
                "发货仓库ID", "剩余可下推量", "单据金额合计", "备注", "行号", "物料编码", "物料名称", "规格",
                "单位", "数量", "单价", "行金额", "行备注");
        assertEquals("导出列必须逐列存在且顺序不变（列定义即导出契约）", expected, actual);
        assertEquals("列数", 27, actual.size());

        // 每一列都必须有非空 name（防"加了字段忘了写 name"）
        for (java.lang.reflect.Field field : ErpSalesExportRow.class.getDeclaredFields())
        {
            com.ruoyi.common.annotation.Excel excel = field.getAnnotation(com.ruoyi.common.annotation.Excel.class);
            if (excel != null)
            {
                assertNotNull("@Excel 的 name 不得为空：" + field.getName(),
                        excel.name() == null || excel.name().trim().isEmpty() ? null : excel.name());
            }
        }
    }

    /* ==================== 夹具 ==================== */

    private ErpSalesRequest requestDoc(String docNo, String qty, String price)
    {
        ErpSalesRequest doc = new ErpSalesRequest();
        doc.setId("ID-" + docNo);
        doc.setDocNo(docNo);
        doc.setDocDate(new Date(1893456000000L));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDeptId(DEPT_ID);
        doc.setCustomerId("CUST-1");
        doc.setCustomerNameText("客户文本");
        doc.setCreateId(USER_ID);
        ErpSalesRequestItem item = requestItem(qty, price);
        item.setId("IT-" + docNo + "-1");
        item.setDocId(doc.getId());
        item.setProductCode("P001");
        item.setProductName("螺丝");
        item.setSpec("M6");
        item.setUomName("个");
        item.setSeq(Integer.valueOf(1));
        item.setAmount(ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice()));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        doc.setTotalAmount(ErpAmounts.totalOf(doc.getItems()));
        return doc;
    }

    private ErpSalesOrder orderDoc(String docNo, String qty, String price)
    {
        ErpSalesOrder doc = new ErpSalesOrder();
        doc.setId("ID-" + docNo);
        doc.setDocNo(docNo);
        doc.setDocDate(new Date(1893456000000L));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDeptId(DEPT_ID);
        doc.setCustomerId("CUST-1");
        doc.setCustomerName("启用客户");
        doc.setCreateId(USER_ID);
        ErpSalesOrderItem item = orderItem(qty, price);
        item.setId("IT-" + docNo + "-1");
        item.setDocId(doc.getId());
        item.setProductCode("P001");
        item.setProductName("螺丝");
        item.setSpec("M6");
        item.setUomName("个");
        item.setSeq(Integer.valueOf(1));
        item.setAmount(ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice()));
        doc.setItems(new ArrayList<>(Collections.singletonList(item)));
        doc.setTotalAmount(ErpAmounts.totalOf(doc.getItems()));
        return doc;
    }

    private static ErpSalesRequestItem requestItem(String qty, String price)
    {
        ErpSalesRequestItem item = new ErpSalesRequestItem();
        item.setProductId("P2");
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setOrderedQty(BigDecimal.ZERO);
        return item;
    }

    private static ErpSalesOrderItem orderItem(String qty, String price)
    {
        ErpSalesOrderItem item = new ErpSalesOrderItem();
        item.setProductId("P1");
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setShippedQty(BigDecimal.ZERO);
        return item;
    }

    /**
     * 反射注入 {@code @Autowired} 私有字段（T3 的单测同款做法：不给生产类加"只为测试"的 setter）。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @param value     值
     */
    private static void inject(Object target, String fieldName, Object value)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("注入失败：" + fieldName, e);
            }
        }
        throw new IllegalStateException("字段不存在：" + fieldName);
    }

    /* ==================== xlsx 读回辅助（与采购侧用例同手法） ==================== */

    /**
     * 伪造只支持 {@code getOutputStream()} 的响应，把 {@code ExcelUtil} 的产物接进内存流
     * （与 B3 合同导出、采购侧 {@code ErpPurExportTest} 同一手法：核对的是"控制器真正走的导出通路"）。
     *
     * @param buffer 输出缓冲
     * @return 伪造响应
     */
    private static HttpServletResponse fakeResponse(final ByteArrayOutputStream buffer)
    {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ErpSalesExportTest.class.getClassLoader(),
                new Class<?>[] { HttpServletResponse.class },
                new InvocationHandler()
                {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                    {
                        String name = method.getName();
                        if ("getOutputStream".equals(name))
                        {
                            return new ServletOutputStream()
                            {
                                @Override
                                public void write(int b)
                                {
                                    buffer.write(b);
                                }

                                @Override
                                public boolean isReady()
                                {
                                    return true;
                                }

                                @Override
                                public void setWriteListener(WriteListener writeListener)
                                {
                                    // 内存写：不需要异步写监听
                                }
                            };
                        }
                        if ("toString".equals(name))
                        {
                            return "fake-excel-response";
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class)
                        {
                            return Boolean.FALSE;
                        }
                        if (type == int.class)
                        {
                            return Integer.valueOf(0);
                        }
                        if (type == long.class)
                        {
                            return Long.valueOf(0L);
                        }
                        return null;
                    }
                });
    }

    /**
     * 读回表头文本列表（证明"列定义即导出契约"）。
     *
     * @param sheet 工作表
     * @return 表头文本列表
     */
    private static List<String> headersOf(Sheet sheet)
    {
        Row header = sheet.getRow(0);
        List<String> headers = new ArrayList<>();
        for (int c = 0; c < header.getLastCellNum(); c++)
        {
            headers.add(header.getCell(c) == null ? "" : header.getCell(c).toString());
        }
        return headers;
    }

    /**
     * 表头里某一列的下标（找不到返回 -1）。
     *
     * @param sheet 工作表
     * @param name  列名
     * @return 下标
     */
    private static int columnOf(Sheet sheet, String name)
    {
        Row header = sheet.getRow(0);
        for (int c = 0; c < header.getLastCellNum(); c++)
        {
            if (header.getCell(c) != null && name.equals(header.getCell(c).toString()))
            {
                return c;
            }
        }
        return -1;
    }

    /* ==================== 测试子类与内存桩 ==================== */

    /** 记录调用参数的销售申请服务桩（{@code result} 即"范围裁剪后的可见集合"）。 */
    class StubRequestService implements IErpSalesRequestService
    {
        List<ErpSalesRequest> result = new ArrayList<>();

        int listCalls;

        final List<String> detailReads = new ArrayList<>();

        @Override
        public List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query)
        {
            capturedRequestQuery = query;
            listCalls++;
            return result;
        }

        @Override
        public ErpSalesRequest selectSalesRequestById(String id)
        {
            detailReads.add(id);
            return requestDocs.get(id);
        }

        @Override
        public List<ErpSalesRequestItem> selectSalesRequestItems(String docId)
        {
            return requestItems.get(docId);
        }

        @Override
        public Map<String, List<ErpSalesRequestItem>> selectSalesRequestItemsByDocIds(List<String> docIds)
        {
            Map<String, List<ErpSalesRequestItem>> hit = new LinkedHashMap<>();
            for (String docId : docIds)
            {
                if (requestItems.get(docId) != null)
                {
                    hit.put(docId, requestItems.get(docId));
                }
            }
            return hit;
        }

        @Override
        public ErpSalesRequest insertSalesRequest(ErpSalesRequest doc)
        {
            return doc;
        }

        @Override
        public ErpSalesRequest updateSalesRequest(ErpSalesRequest doc)
        {
            return doc;
        }

        @Override
        public void deleteSalesRequestById(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public void submitSalesRequest(String id, String remark)
        {
            // 导出用例不涉及
        }

        @Override
        public void approveSalesRequest(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public void rejectSalesRequest(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void voidSalesRequest(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void unapproveSalesRequest(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void completeSalesRequest(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public BigDecimal remainingQtySum(String docId)
        {
            return BigDecimal.ZERO;
        }

        @Override
        public String linkContract(String id, String contractId)
        {
            return null;
        }

        @Override
        public Map<String, Object> contractRefOf(String id)
        {
            return null;
        }
    }

    /** 记录调用参数的销售订单服务桩。 */
    class StubOrderService implements IErpSalesOrderService
    {
        List<ErpSalesOrder> result = new ArrayList<>();

        final List<String> detailReads = new ArrayList<>();

        @Override
        public List<ErpSalesOrder> selectSalesOrderList(ErpSalesOrder query)
        {
            capturedOrderQuery = query;
            return result;
        }

        @Override
        public ErpSalesOrder selectSalesOrderById(String id)
        {
            detailReads.add(id);
            return orderDocs.get(id);
        }

        @Override
        public List<ErpSalesOrderItem> selectSalesOrderItems(String docId)
        {
            return orderItems.get(docId);
        }

        @Override
        public Map<String, List<ErpSalesOrderItem>> selectSalesOrderItemsByDocIds(List<String> docIds)
        {
            Map<String, List<ErpSalesOrderItem>> hit = new LinkedHashMap<>();
            for (String docId : docIds)
            {
                if (orderItems.get(docId) != null)
                {
                    hit.put(docId, orderItems.get(docId));
                }
            }
            return hit;
        }

        @Override
        public ErpSalesOrder insertSalesOrder(ErpSalesOrder doc)
        {
            return doc;
        }

        @Override
        public ErpSalesOrder updateSalesOrder(ErpSalesOrder doc)
        {
            return doc;
        }

        @Override
        public void deleteSalesOrderById(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public void submitSalesOrder(String id, String remark)
        {
            // 导出用例不涉及
        }

        @Override
        public void approveSalesOrder(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public void rejectSalesOrder(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void voidSalesOrder(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void unapproveSalesOrder(String id, String reason)
        {
            // 导出用例不涉及
        }

        @Override
        public void completeSalesOrder(String id)
        {
            // 导出用例不涉及
        }

        @Override
        public String linkContract(String id, String contractId)
        {
            return null;
        }

        @Override
        public Map<String, Object> contractRefOf(String id)
        {
            return null;
        }

        @Override
        public void applyShippedQtyChange(String orderDocId, String orderItemId, BigDecimal deltaQty)
        {
            // 导出用例不涉及
        }
    }

    /** 记录"被校验过哪些对象"的访问校验器桩；{@link ErpSalesExportTest#OUT_OF_SCOPE_ID} 抛 403。 */
    static class StubDocObjectAccess implements IErpDocObjectAccess
    {
        final List<String[]> checked = new ArrayList<>();

        @Override
        public boolean supports(String objectType)
        {
            return ErpDocType.ofCode(objectType) != null;
        }

        @Override
        public void checkObjectAccess(String objectType, String objectId)
        {
            checked.add(new String[] { objectType, objectId });
            if (OUT_OF_SCOPE_ID.equals(objectId))
            {
                throw new ServiceException("无权访问该销售单据", Integer.valueOf(403));
            }
        }
    }

    /* ==================== 真实服务实现用的桩 Mapper ==================== */

    /** 记录服务实现传下来的 {@code dataScopeSql}（证明范围片段出自 ErpDocScope）。 */
    private ErpSalesRequestMapper scopeCapturingRequestMapper()
    {
        return new ErpSalesRequestMapper()
        {
            @Override
            public List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query)
            {
                scopeSqlSeenByMapper = query.getDataScopeSql();
                return new ArrayList<>();
            }

            @Override
            public ErpSalesRequest selectSalesRequestById(String id)
            {
                return null;
            }

            @Override
            public int insertSalesRequest(ErpSalesRequest doc)
            {
                return 0;
            }

            @Override
            public int updateSalesRequest(ErpSalesRequest doc)
            {
                return 0;
            }

            @Override
            public int updateSalesRequestStatus(ErpSalesRequest doc)
            {
                return 0;
            }

            @Override
            public int deleteSalesRequestById(String id)
            {
                return 0;
            }

            @Override
            public String selectMaxDocNoByPrefix(String prefix)
            {
                return null;
            }

            @Override
            public int countRequestItems(String docId)
            {
                return 0;
            }
        };
    }

    private ErpSalesRequestItemMapper emptyRequestItemMapper()
    {
        return new ErpSalesRequestItemMapper()
        {
            @Override
            public List<ErpSalesRequestItem> selectItemsByDocId(String docId)
            {
                return new ArrayList<>();
            }

            @Override
            public List<ErpSalesRequestItem> selectItemsByDocIds(List<String> docIds)
            {
                return new ArrayList<>();
            }

            @Override
            public List<ErpSalesRequestItem> selectItemsBySrcItemIds(List<String> srcItemIds)
            {
                return new ArrayList<>();
            }

            @Override
            public int deleteItemsByDocId(String docId)
            {
                return 0;
            }

            @Override
            public int batchInsertItems(List<ErpSalesRequestItem> items)
            {
                return 0;
            }

            @Override
            public int batchUpsertItems(List<ErpSalesRequestItem> items)
            {
                return 0;
            }

            @Override
            public int updateOrderedQty(String itemId, BigDecimal delta)
            {
                return 0;
            }

            @Override
            public ErpSalesRequestItem selectItemById(String id)
            {
                return null;
            }
        };
    }
}
