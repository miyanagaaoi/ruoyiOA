package com.ruoyi.ctms.erp.procurement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.procurement.controller.ErpPurchaseOrderController;
import com.ruoyi.ctms.erp.procurement.controller.ErpPurchaseRequestController;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestMapper;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseOrderServiceImpl;
import com.ruoyi.ctms.erp.procurement.service.impl.ErpPurchaseRequestServiceImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>采购申请单 / 采购单导出端点单测</b>（审计 F1 补齐的 {@code :export}；覆盖 AC-78 的"导出"一方）。 </p>
 *
 * <p> 锁住的四件事： </p>
 * <ol>
 *   <li> <b>与列表同筛选同范围</b>：导出必须把<b>同一个 query 对象</b>交给与列表相同的服务方法，
 *        且该对象上的 {@code dataScopeSql} 必须是服务写入的范围片段 —— 不是"另写一套范围判定"；</li>
 *   <li> <b>响应形态＝xlsx 二进制流</b>：走真实 {@link ExcelUtil} 生成字节流再用 POI 读回，
 *        顺带证明"列定义即导出契约"（前端 {@code exportDocs} 会直接 {@code saveAs(blob)}）；</li>
 *   <li> <b>金额逐行一致</b>：行金额 = {@code ErpAmounts.lineAmount}（先 HALF_UP 到 2 位），
 *        合计 = 各行舍入后之和（三行 {@code 1×0.125} ⇒ 0.13 / 0.39），与列表返回的值相同；</li>
 *   <li> <b>范围外 403</b>：按 {@code id} 导出范围外单据时抛业务码 403（不是 500，也不返回数据）。 </li>
 * </ol>
 *
 * <p> 端点方法上的 {@code @PreAuthorize('@ss.hasPermi(...)')} 与 {@code GET/POST} 声明也用反射断言，
 * 这样"权限点与菜单 F 行逐字一致""前端用 POST 也能调通"两条约定都进了回归。 </p>
 *
 * @author 二开
 */
public class ErpPurExportTest
{
    /** 可见单据的创建人（范围片段 {@code d.create_id = '2'} 命中的那个）。 */
    private static final String VISIBLE_USER = "2";

    /** 范围外单据的创建人。 */
    private static final String OTHER_USER = "9";

    /** 服务写入的范围片段（与真实 {@code ErpDocScope} 的输出同形状）。 */
    private static final String SCOPE_SQL = "d.create_id = '" + VISIBLE_USER + "'";

    private final ExportStub stub = new ExportStub();

    private final ScopeRequestService requestService = new ScopeRequestService();

    private final ScopeOrderService orderService = new ScopeOrderService();

    private final ErpPurchaseRequestController requestController = new ErpPurchaseRequestController();

    private final ErpPurchaseOrderController orderController = new ErpPurchaseOrderController();

    @Before
    public void setUp()
    {
        stub.wire();
        inject(requestService, "requestMapper", stub.requestMapper);
        inject(requestService, "itemMapper", stub.requestItemMapper);
        inject(orderService, "orderMapper", stub.orderMapper);
        inject(orderService, "itemMapper", stub.orderItemMapper);

        inject(requestController, "requestService", requestService);
        inject(orderController, "orderService", orderService);
    }

    /* ==================== ① 同筛选同范围 ==================== */

    @Test
    public void 导出与列表同一取数入口且携带同一筛选与数据范围片段()
    {
        seedRequest("PR202610000001", VISIBLE_USER, "阀门采购", "2026-10-01");
        seedRequest("PR202610000002", VISIBLE_USER, "法兰采购", "2026-10-02");
        seedRequest("PR202610000009", OTHER_USER, "别人的采购", "2026-10-03");

        ErpPurchaseRequest query = new ErpPurchaseRequest();
        query.setKeyword("阀门");
        List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows = requestController.exportRows(query);

        assertSame("导出必须把同一个 query 交给列表入口（同筛选、同范围）", query, stub.lastRequestQuery);
        assertEquals("服务必须把 ErpDocScope 的范围片段写进导出用的 query", SCOPE_SQL,
                query.getDataScopeSql());
        assertEquals("关键字命中 1 张、且范围内 1 张 ⇒ 1 行", 1, rows.size());
        assertEquals("导出的是关键字命中的那张", "PR202610000001", rows.get(0).getDocNo());
    }

    @Test
    public void 导出按日期区间与状态筛选与列表一致()
    {
        seedRequest("PR202610000011", VISIBLE_USER, "早单", "2026-10-01");
        seedRequest("PR202610000012", VISIBLE_USER, "晚单", "2026-10-20");

        ErpPurchaseRequest query = new ErpPurchaseRequest();
        query.setBeginDocDate("2026-10-10");
        query.setEndDocDate("2026-10-31");
        List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows = requestController.exportRows(query);
        assertEquals("只导出区间内的单据", 1, rows.size());
        assertEquals("PR202610000012", rows.get(0).getDocNo());

        // 状态筛选同样透传（与列表同一条件）
        ErpPurchaseRequest voidQuery = new ErpPurchaseRequest();
        voidQuery.setIncludeVoided("1");
        voidQuery.setStatus(ErpDocStatus.VOIDED);
        assertEquals("已作废筛选透传到取数层", 0,
                requestController.exportRows(voidQuery).size());
    }

    @Test
    public void 范围外的单据不会出现在导出里()
    {
        seedRequest("PR202610000021", VISIBLE_USER, "我的", "2026-10-01");
        seedRequest("PR202610000022", OTHER_USER, "别人的", "2026-10-01");

        List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows =
                requestController.exportRows(new ErpPurchaseRequest());
        assertEquals("只导出范围内的 1 张（范围外那张不出现）", 1, rows.size());
        assertEquals("PR202610000021", rows.get(0).getDocNo());
    }

    @Test
    public void 按id导出范围外单据返回403()
    {
        seedRequest("PR202610000031", OTHER_USER, "别人的单据", "2026-10-01");
        ErpPurchaseRequest query = new ErpPurchaseRequest();
        query.setId("PR202610000031");
        try
        {
            requestController.exportRows(query);
            fail("范围外单据按 id 导出必须被拒");
        }
        catch (ServiceException e)
        {
            assertNotNull("范围外必须带业务码", e.getCode());
            assertEquals("必须是业务码 403（不是 500）", HttpStatus.FORBIDDEN, e.getCode().intValue());
        }
    }

    /* ==================== ② xlsx 字节流 + 列清单 ==================== */

    @Test
    public void 采购申请单导出产出xlsx且列含表头业务字段与行项关键列()
    {
        seedRequest("PR202610000041", VISIBLE_USER, "列清单", "2026-10-01");
        List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows =
                requestController.exportRows(new ErpPurchaseRequest());

        List<String> headers = headersOf(new ExcelUtil<>(
                ErpPurchaseRequestController.ErpPurRequestExportRow.class), rows, "采购申请单");
        for (String expected : new String[] { "单号", "单据日期", "状态", "归属部门", "需求部门(ID)",
                "需求日期", "合同编号", "用途", "剩余可下推量合计", "金额合计", "行号", "物料编码",
                "物料名称", "规格", "单位", "数量", "单价", "行金额", "已下推数量", "剩余可下推量" })
        {
            assertTrue("导出表头必须包含「" + expected + "」列，实际=" + headers, headers.contains(expected));
        }
    }

    @Test
    public void 采购单导出产出xlsx且列含供应商收货信息与已入库列()
    {
        ErpPurchaseOrder doc = seedOrder("PO202610000051", VISIBLE_USER, "2026-10-01");
        doc.setSupplierName("云羲数字科技");
        doc.setReceiptWarehouseName("一号仓");
        doc.setSettleType("月结30天");
        doc.setCurrency("CNY");
        doc.setPurchaseDeptId("103");
        ErpPurchaseOrderItem item = firstOrderItem("PO202610000051");
        item.setReceivedQty(new BigDecimal("4"));
        item.setSrcItemId("POI-1");

        List<ErpPurchaseOrderController.ErpPurOrderExportRow> rows =
                orderController.exportRows(new ErpPurchaseOrder());
        List<String> headers = headersOf(new ExcelUtil<>(
                ErpPurchaseOrderController.ErpPurOrderExportRow.class), rows, "采购单");
        for (String expected : new String[] { "单号", "单据日期", "状态", "供应商", "采购部门(ID)",
                "预计到货日期", "结算方式", "币种", "默认收货仓库", "合同编号", "来源单号", "金额合计",
                "剩余可入库量合计", "行号", "物料编码", "物料名称", "规格", "单位", "数量", "单价",
                "行金额", "已入库数量", "剩余可入库量" })
        {
            assertTrue("导出表头必须包含「" + expected + "」列，实际=" + headers, headers.contains(expected));
        }
        assertEquals("采购单行：已入库 4、数量 10 ⇒ 剩余 6", 0,
                rows.get(0).getRemainQty().compareTo(new BigDecimal("6")));
        assertEquals("已入库列输出快照值", 0, rows.get(0).getReceivedQty().compareTo(new BigDecimal("4")));
        assertEquals("供应商列输出名称快照", "云羲数字科技", rows.get(0).getSupplierName());
    }

    /* ==================== ③ 金额逐行一致（先舍入再汇总） ==================== */

    @Test
    public void 导出行金额与列表逐行一致且合计为先舍入再汇总()
    {
        // 三行 1 × 0.125：每行先四舍五入到 0.13，合计 0.39（不是 0.375 → 0.38）
        String docNo = "PR202610000061";
        ErpPurchaseRequest doc = seedRequest(docNo, VISIBLE_USER, "金额一致", "2026-10-01");
        List<ErpPurchaseRequestItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            items.add(requestItem(docNo, i + 1, "P-VALVE", "球阀", "1", "0.125"));
        }
        stub.requestItems.put(docNo, items);

        // 列表返回的值（导出必须与它一致）
        List<ErpPurchaseRequest> list = requestService.selectRequestList(new ErpPurchaseRequest());
        assertEquals("列表金额合计", 0, list.get(0).getTotalAmount().compareTo(new BigDecimal("0.39")));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows =
                requestController.exportRows(new ErpPurchaseRequest());
        new ExcelUtil<>(ErpPurchaseRequestController.ErpPurRequestExportRow.class)
                .exportExcel(fakeResponse(buffer), rows, "采购申请单");

        assertEquals("导出行数 = 行项数", 3, rows.size());
        for (ErpPurchaseRequestController.ErpPurRequestExportRow row : rows)
        {
            assertEquals("每行金额先舍入为 0.13", 0, row.getAmount().compareTo(new BigDecimal("0.13")));
            assertEquals("每行的合计列 = 列表合计", 0,
                    row.getTotalAmount().compareTo(list.get(0).getTotalAmount()));
        }
        assertEquals("导出行金额之和 = 0.39", 0, sumOf(rows).compareTo(new BigDecimal("0.39")));

        // 再从真 xlsx 里读回：行金额列三行都是 0.13、金额合计列都是 0.39
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(buffer.toByteArray())))
        {
            Sheet sheet = workbook.getSheetAt(0);
            int amountCol = columnOf(sheet, "行金额");
            int totalCol = columnOf(sheet, "金额合计");
            assertTrue("必须有行金额列", amountCol >= 0);
            assertTrue("必须有金额合计列", totalCol >= 0);
            assertEquals("表头 + 3 行", 4, sheet.getLastRowNum() + 1);
            BigDecimal cellSum = BigDecimal.ZERO;
            for (int r = 1; r <= 3; r++)
            {
                Row row = sheet.getRow(r);
                BigDecimal amount = new BigDecimal(row.getCell(amountCol).toString());
                assertEquals("xlsx 行金额 = 0.13", 0, amount.compareTo(new BigDecimal("0.13")));
                assertEquals("xlsx 合计列 = 0.39", 0,
                        new BigDecimal(row.getCell(totalCol).toString()).compareTo(new BigDecimal("0.39")));
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

    @Test
    public void 采购单导出金额取落库合计且与列表一致()
    {
        ErpPurchaseOrder doc = seedOrder("PO202610000071", VISIBLE_USER, "2026-10-01");
        // 落库合计由服务写入（先舍入再汇总）；这里给一个"多行小数单价"的场景
        List<ErpPurchaseOrderItem> items = stub.orderItems.get("PO202610000071");
        items.clear();
        for (int i = 0; i < 3; i++)
        {
            items.add(orderItem("PO202610000071", i + 1, "P-VALVE", "球阀", "1", "0.125"));
        }
        doc.setTotalAmount(ErpPurRules.totalAmountOf(items));
        assertEquals("落库合计 = 0.39", 0, doc.getTotalAmount().compareTo(new BigDecimal("0.39")));

        List<ErpPurchaseOrder> list = orderService.selectOrderList(new ErpPurchaseOrder());
        List<ErpPurchaseOrderController.ErpPurOrderExportRow> rows =
                orderController.exportRows(new ErpPurchaseOrder());
        assertEquals("导出行数 = 行项数", 3, rows.size());
        for (ErpPurchaseOrderController.ErpPurOrderExportRow row : rows)
        {
            assertEquals("导出合计列 = 列表合计", 0,
                    row.getTotalAmount().compareTo(list.get(0).getTotalAmount()));
            assertEquals("导出金额合计列 = 0.39", 0,
                    row.getTotalAmount().compareTo(new BigDecimal("0.39")));
        }
        BigDecimal lineSum = BigDecimal.ZERO;
        for (ErpPurchaseOrderController.ErpPurOrderExportRow row : rows)
        {
            lineSum = lineSum.add(row.getAmount());
        }
        assertEquals("行金额之和 = 合计", 0, lineSum.compareTo(new BigDecimal("0.39")));
    }

    /* ==================== ④ 端点契约（权限点 + 方法 + 路径） ==================== */

    @Test
    public void 导出端点的权限点方法与路径与前端及菜单约定逐字一致() throws Exception
    {
        Method requestExport = ErpPurchaseRequestController.class
                .getMethod("export", HttpServletResponse.class, ErpPurchaseRequest.class);
        assertEquals("采购申请单导出权限点", "@ss.hasPermi('pur:request:export')",
                requestExport.getAnnotation(PreAuthorize.class).value());
        assertExportMapping(requestExport, "采购申请单");

        Method orderExport = ErpPurchaseOrderController.class
                .getMethod("export", HttpServletResponse.class, ErpPurchaseOrder.class);
        assertEquals("采购单导出权限点", "@ss.hasPermi('pur:order:export')",
                orderExport.getAnnotation(PreAuthorize.class).value());
        assertExportMapping(orderExport, "采购单");
    }

    /**
     * 断言导出方法声明为 {@code GET + POST /export}：前端 {@code utils/request.js#download} 用的是
     * <b>POST</b>（form-urlencoded + blob），GET 供脚本/人工复跑 —— 两者都必须成立。
     *
     * @param method 导出方法
     * @param label  单据标签（断言消息用）
     */
    private static void assertExportMapping(Method method, String label)
    {
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        assertNotNull(label + "导出必须有 @RequestMapping（同时接受 GET 与 POST）", mapping);
        assertEquals(label + "导出路径", "/export", mapping.value()[0]);
        List<RequestMethod> methods = Arrays.asList(mapping.method());
        assertTrue(label + "导出必须接受 POST（前端 download 用 POST），实际=" + methods,
                methods.contains(RequestMethod.POST));
        assertTrue(label + "导出必须接受 GET（便于脚本复跑），实际=" + methods,
                methods.contains(RequestMethod.GET));

        // D-19（t40 授权跨包改动）：query 参数上**不得**有 @RequestBody。
        // 前端 download() 发的是 application/x-www-form-urlencoded，带 @RequestBody 会 415；
        // 这条与销售侧 ErpSalesExportTest.assertExportParameters 同款，两边一起把该缺口钉住。
        java.lang.annotation.Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        assertTrue(label + "导出方法应至少有 2 个参数（response + query）", parameterAnnotations.length >= 2);
        for (java.lang.annotation.Annotation annotation : parameterAnnotations[1])
        {
            assertFalse(label + "export 的 query 参数不得带 @RequestBody（前端发 form-urlencoded，会 415）",
                    annotation instanceof RequestBody);
        }
    }

    /* ==================== 夹具 ==================== */

    private static ErpPurchaseRequestItem requestItem(String docId, int seq, String productId,
                                                     String productName, String qty, String price)
    {
        ErpPurchaseRequestItem item = new ErpPurchaseRequestItem();
        item.setId(docId + "-I" + seq);
        item.setDocId(docId);
        item.setSeq(Integer.valueOf(seq));
        item.setProductId(productId);
        item.setProductCode("WL-0001");
        item.setProductName(productName);
        item.setSpec("DN50");
        item.setUomName("件");
        item.setUomDecimals(Integer.valueOf(3));
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setOrderedQty(BigDecimal.ZERO);
        return item;
    }

    private static ErpPurchaseOrderItem orderItem(String docId, int seq, String productId,
                                                 String productName, String qty, String price)
    {
        ErpPurchaseOrderItem item = new ErpPurchaseOrderItem();
        item.setId(docId + "-I" + seq);
        item.setDocId(docId);
        item.setSeq(Integer.valueOf(seq));
        item.setProductId(productId);
        item.setProductCode("WL-0001");
        item.setProductName(productName);
        item.setSpec("DN50");
        item.setUomName("件");
        item.setUomDecimals(Integer.valueOf(3));
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setReceivedQty(BigDecimal.ZERO);
        return item;
    }

    /**
     * 造一张采购申请单（默认 1 行）。
     */
    private ErpPurchaseRequest seedRequest(String docNo, String createId, String purpose, String docDate)
    {
        ErpPurchaseRequest doc = new ErpPurchaseRequest();
        doc.setId(docNo);
        doc.setDocNo(docNo);
        doc.setDocDate(parseDay(docDate));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setCreateId(createId);
        doc.setCreateBy("u" + createId);
        doc.setDeptId("103");
        doc.setPurpose(purpose);
        doc.setDelFlag("0");
        doc.setPosted("0");
        stub.requests.put(docNo, doc);
        stub.requestItems.put(docNo, new ArrayList<>(
                Arrays.asList(requestItem(docNo, 1, "P-VALVE", "球阀", "10", "2"))));
        return doc;
    }

    /**
     * 造一张采购单（默认 1 行，落库合计 20.00）。
     */
    private ErpPurchaseOrder seedOrder(String docNo, String createId, String docDate)
    {
        ErpPurchaseOrder doc = new ErpPurchaseOrder();
        doc.setId(docNo);
        doc.setDocNo(docNo);
        doc.setDocDate(parseDay(docDate));
        doc.setStatus(ErpDocStatus.APPROVED);
        doc.setCreateId(createId);
        doc.setCreateBy("u" + createId);
        doc.setDeptId("103");
        doc.setSupplierId("S-001");
        doc.setSupplierName("供应商快照");
        doc.setDelFlag("0");
        doc.setPosted("0");
        List<ErpPurchaseOrderItem> items = new ArrayList<>(
                Arrays.asList(orderItem(docNo, 1, "P-VALVE", "球阀", "10", "2")));
        doc.setTotalAmount(ErpPurRules.totalAmountOf(items));
        stub.orders.put(docNo, doc);
        stub.orderItems.put(docNo, items);
        return doc;
    }

    private ErpPurchaseOrderItem firstOrderItem(String docId)
    {
        return stub.orderItems.get(docId).get(0);
    }

    private static Date parseDay(String text)
    {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
        format.setLenient(false);
        try
        {
            return format.parse(text);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("夹具日期不合法：" + text, e);
        }
    }

    private static BigDecimal sumOf(List<ErpPurchaseRequestController.ErpPurRequestExportRow> rows)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpPurchaseRequestController.ErpPurRequestExportRow row : rows)
        {
            sum = sum.add(row.getAmount());
        }
        return sum;
    }

    /**
     * 用真实 {@link ExcelUtil} 生成 xlsx 并返回表头清单（证明"列定义即导出契约"）。
     *
     * @param util       ExcelUtil 实例（列定义来自行的 {@code @Excel} 注解）
     * @param rows       导出行
     * @param sheetName  工作表名
     * @param <T>        行类型
     * @return 表头文本列表
     */
    private static <T> List<String> headersOf(ExcelUtil<T> util, List<T> rows, String sheetName)
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        util.exportExcel(fakeResponse(buffer), rows, sheetName);
        assertTrue("必须真的产出非空 xlsx 字节流", buffer.size() > 0);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(buffer.toByteArray())))
        {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++)
            {
                headers.add(header.getCell(c) == null ? "" : header.getCell(c).toString());
            }
            return headers;
        }
        catch (Exception e)
        {
            fail("读回导出的 xlsx 表头失败：" + e);
            return new ArrayList<>();
        }
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

    /**
     * 伪造只支持 {@code getOutputStream()} 的响应，把 {@code ExcelUtil} 的产物接进内存流
     * （与 B3 合同导出用例同一手法：核对的是"控制器真正走的导出通路"）。
     *
     * @param buffer 输出缓冲
     * @return 伪造响应
     */
    private static HttpServletResponse fakeResponse(final ByteArrayOutputStream buffer)
    {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ErpPurExportTest.class.getClassLoader(),
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

    /* ==================== 业务子类（固定范围与上下文） ==================== */

    /**
     * 采购申请单服务子类：范围片段固定为 {@code d.create_id = '2'}，且非超管。
     */
    static class ScopeRequestService extends ErpPurchasePushInTest.TestRequestService
    {
        @Override
        protected String dataScopeSql()
        {
            return SCOPE_SQL;
        }

        @Override
        protected boolean hasAllScope()
        {
            return false;
        }

        @Override
        protected boolean canAccess(String rowCreateId, String rowDeptId)
        {
            return VISIBLE_USER.equals(rowCreateId);
        }
    }

    /**
     * 采购单服务子类：同上。
     */
    static class ScopeOrderService extends ErpPurchasePushInTest.TestOrderService
    {
        @Override
        protected String dataScopeSql()
        {
            return SCOPE_SQL;
        }

        @Override
        protected boolean hasAllScope()
        {
            return false;
        }

        @Override
        protected boolean canAccess(String rowCreateId, String rowDeptId)
        {
            return VISIBLE_USER.equals(rowCreateId);
        }
    }

    /* ==================== Mapper 桩（记录 query + 按筛选/范围裁剪） ==================== */

    /**
     * <p> 采购侧四个 Mapper 的内存桩（同一份内存表 + 一个方法分派器，四个接口共用）。 </p>
     *
     * <p> 与别的采购用例不同，这里的两件事是<b>刻意</b>做出来的： </p>
     * <ol>
     *   <li> 记录 {@code lastRequestQuery} / {@code lastOrderQuery} —— 导出必须把同一个 query
     *        对象交给取数层，才能证明"同筛选同范围"；</li>
     *   <li> 按 {@code dataScopeSql} 里的 {@code create_id} 片段裁剪行 —— 内存桩没有 SQL 引擎，
     *        只能对"服务写入的片段"做最小解释；真实 SQL 的语义由真库与 base 的
     *        {@code ErpDocScopeTest}（13 条）负责。 </li>
     * </ol>
     *
     * <p> ⚠ 分派器是 <b>static</b> 且四个代理<b>共用同一个实例</b>：若让每个代理各 new 一个
     * "带 kind 的处理器"，处理器构造时会再创建代理 → 无限递归（StackOverflowError）。 </p>
     */
    static class ExportStub implements InvocationHandler
    {
        final Map<String, ErpPurchaseRequest> requests = new LinkedHashMap<>();

        final Map<String, List<ErpPurchaseRequestItem>> requestItems = new LinkedHashMap<>();

        final Map<String, ErpPurchaseOrder> orders = new LinkedHashMap<>();

        final Map<String, List<ErpPurchaseOrderItem>> orderItems = new LinkedHashMap<>();

        /** 最近一次列表取数的 query（导出同一性断言用）。 */
        ErpPurchaseRequest lastRequestQuery;

        /** 最近一次采购单列表取数的 query。 */
        ErpPurchaseOrder lastOrderQuery;

        ErpPurchaseRequestMapper requestMapper;

        ErpPurchaseRequestItemMapper requestItemMapper;

        ErpPurchaseOrderMapper orderMapper;

        ErpPurchaseOrderItemMapper orderItemMapper;

        /**
         * 创建四个 Mapper 代理（都指向本处理器实例）。
         */
        void wire()
        {
            requestMapper = proxy(ErpPurchaseRequestMapper.class);
            requestItemMapper = proxy(ErpPurchaseRequestItemMapper.class);
            orderMapper = proxy(ErpPurchaseOrderMapper.class);
            orderItemMapper = proxy(ErpPurchaseOrderItemMapper.class);
        }

        private <T> T proxy(Class<T> type)
        {
            return type.cast(Proxy.newProxyInstance(ErpPurExportTest.class.getClassLoader(),
                    new Class<?>[] { type }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            String name = method.getName();
            if ("toString".equals(name))
            {
                return "export-stub";
            }
            if ("hashCode".equals(name))
            {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name))
            {
                return Boolean.valueOf(proxy == args[0]);
            }
            /* ---------- 采购申请单 ---------- */
            if ("selectRequestList".equals(name))
            {
                ErpPurchaseRequest query = (ErpPurchaseRequest) args[0];
                lastRequestQuery = query;
                List<ErpPurchaseRequest> result = new ArrayList<>();
                for (ErpPurchaseRequest doc : requests.values())
                {
                    if (matchesRequestQuery(doc, query))
                    {
                        result.add(doc);
                    }
                }
                return result;
            }
            if ("selectRequestById".equals(name))
            {
                return requests.get((String) args[0]);
            }
            /* ---------- 行项（按"哪个内存表含该单据"分派） ---------- */
            if ("selectItemsByDocId".equals(name))
            {
                String docId = (String) args[0];
                if (requestItems.containsKey(docId))
                {
                    return requestItems.get(docId);
                }
                return orderItemsOf(docId);
            }
            if ("selectItemsByDocIds".equals(name))
            {
                List<Object> result = new ArrayList<>();
                for (Object docId : (List<?>) args[0])
                {
                    String id = String.valueOf(docId);
                    if (requestItems.containsKey(id))
                    {
                        result.addAll(requestItems.get(id));
                    }
                    else if (orderItems.containsKey(id))
                    {
                        result.addAll(orderItems.get(id));
                    }
                }
                return result;
            }
            if ("selectItemById".equals(name))
            {
                String id = (String) args[0];
                for (List<ErpPurchaseRequestItem> list : requestItems.values())
                {
                    for (ErpPurchaseRequestItem item : list)
                    {
                        if (id.equals(item.getId()))
                        {
                            return item;
                        }
                    }
                }
                for (List<ErpPurchaseOrderItem> list : orderItems.values())
                {
                    for (ErpPurchaseOrderItem item : list)
                    {
                        if (id.equals(item.getId()))
                        {
                            return item;
                        }
                    }
                }
                return null;
            }
            /* ---------- 采购单 ---------- */
            if ("selectOrderList".equals(name))
            {
                ErpPurchaseOrder query = (ErpPurchaseOrder) args[0];
                lastOrderQuery = query;
                List<ErpPurchaseOrder> result = new ArrayList<>();
                for (ErpPurchaseOrder doc : orders.values())
                {
                    if (matchesOrderQuery(doc, query))
                    {
                        result.add(doc);
                    }
                }
                return result;
            }
            if ("selectOrderById".equals(name))
            {
                return orders.get((String) args[0]);
            }
            /* ---------- 其余在导出路径上用不到：返回中性值 ---------- */
            if (method.getReturnType() == boolean.class)
            {
                return Boolean.FALSE;
            }
            if (method.getReturnType() == int.class)
            {
                return Integer.valueOf(0);
            }
            return null;
        }

        private List<ErpPurchaseOrderItem> orderItemsOf(String docId)
        {
            List<ErpPurchaseOrderItem> list = orderItems.get(docId);
            return list == null ? new ArrayList<ErpPurchaseOrderItem>() : list;
        }

        /**
         * 申请单的筛选判定（与 XML 的同名条件对齐：默认排除已作废、关键字、日期、状态、范围片段）。
         */
        private boolean matchesRequestQuery(ErpPurchaseRequest doc, ErpPurchaseRequest query)
        {
            if (query == null)
            {
                return true;
            }
            if (query.getId() != null && !query.getId().isEmpty() && !query.getId().equals(doc.getId()))
            {
                return false;
            }
            if (!query.includeVoidedForQuery() && ErpDocStatus.VOIDED.equals(doc.getStatus()))
            {
                return false;
            }
            if (query.getStatus() != null && !query.getStatus().equals(doc.getStatus()))
            {
                return false;
            }
            if (query.getKeyword() != null && !query.getKeyword().isEmpty())
            {
                String keyword = query.getKeyword();
                boolean hit = (doc.getPurpose() != null && doc.getPurpose().contains(keyword))
                        || (doc.getDocNo() != null && doc.getDocNo().contains(keyword))
                        || (doc.getRemark() != null && doc.getRemark().contains(keyword));
                if (!hit)
                {
                    return false;
                }
            }
            if (query.getBeginDocDate() != null && !query.getBeginDocDate().isEmpty()
                    && dayText(doc.getDocDate()).compareTo(query.getBeginDocDate()) < 0)
            {
                return false;
            }
            if (query.getEndDocDate() != null && !query.getEndDocDate().isEmpty()
                    && dayText(doc.getDocDate()).compareTo(query.getEndDocDate()) > 0)
            {
                return false;
            }
            return matchesScope(query.getDataScopeSql(), doc.getCreateId());
        }

        private boolean matchesOrderQuery(ErpPurchaseOrder doc, ErpPurchaseOrder query)
        {
            if (query == null)
            {
                return true;
            }
            if (query.getId() != null && !query.getId().isEmpty() && !query.getId().equals(doc.getId()))
            {
                return false;
            }
            if (!query.includeVoidedForQuery() && ErpDocStatus.VOIDED.equals(doc.getStatus()))
            {
                return false;
            }
            if (query.getStatus() != null && !query.getStatus().equals(doc.getStatus()))
            {
                return false;
            }
            if (query.getSupplierId() != null && !query.getSupplierId().isEmpty()
                    && !query.getSupplierId().equals(doc.getSupplierId()))
            {
                return false;
            }
            return matchesScope(query.getDataScopeSql(), doc.getCreateId());
        }

        /**
         * 对"服务写入的范围片段"做最小解释：只认 {@code d.create_id = '<uid>'} 形态
         * （真实片段的生成与语义在 base 的 {@code ErpDocScope} 与其 13 条单测）。
         */
        private boolean matchesScope(String dataScopeSql, String createId)
        {
            if (dataScopeSql == null || dataScopeSql.trim().isEmpty())
            {
                return true;
            }
            String fragment = dataScopeSql.trim();
            String marker = "d.create_id = '";
            if (fragment.startsWith(marker) && fragment.endsWith("'"))
            {
                String uid = fragment.substring(marker.length(), fragment.length() - 1);
                return uid.equals(createId);
            }
            return true;
        }

        private String dayText(Date date)
        {
            return date == null ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd").format(date);
        }
    }
}
