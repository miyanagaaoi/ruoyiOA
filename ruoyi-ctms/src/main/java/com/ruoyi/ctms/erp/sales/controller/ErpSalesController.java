package com.ruoyi.ctms.erp.sales.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;
import com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.domain.vo.ErpSalesExportRow;
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;
import com.ruoyi.ctms.erp.sales.service.IErpSalesPushService;
import com.ruoyi.ctms.erp.sales.service.IErpSalesRequestService;

/**
 * <p> <b>销售线控制器</b>（2.0 B4 任务 4.1 / 4.2 / 4.4）。 </p>
 *
 * <p> 路径按 PRD §9.4 的"资源名单数"重排：{@code /api/sales/requests} → <b>{@code /sal/request}</b>、
 * {@code /api/sales/orders} → <b>{@code /sal/order}</b>
 * （与 {@code CtmsContractController} 的 {@code /ctms/contract} 同构）。 </p>
 *
 * <p> 权限点按简报 §9.2 的冻结表：{@code sal:request:<动作>} / {@code sal:order:<动作>}，
 * 动作集 {@code list/query/add/edit/remove/status/submit/approve/unapprove/void/push/export/print}
 * ——<b>驳回与审核共用 {@code approve}</b>。 </p>
 *
 * <p> <b>为什么路径变量收窄成 {@code {id:[A-Za-z0-9]+}}</b>：id 由 {@code IdUtils.fastSimpleUUID()}
 * 产生（32 位十六进制）；不收窄会让 {@code /push} 之类的动作路径被 {@code /{id}} 抢先匹配
 * （B3 的 {@code CtmsProductMasterController} 已踩过这个坑）。 </p>
 *
 * <p> <b>动作接口一律走 PUT + 路径段</b>（{@code /{id}/submit} 等），而不是在请求体里传动作：
 * 让 URL 本身就是审计线索（{@code @Log} 的 title 会把动作名记进操作日志）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/sal")
public class ErpSalesController extends BaseController
{
    @Autowired(required = false)
    private IErpSalesRequestService salesRequestService;

    @Autowired(required = false)
    private IErpSalesOrderService salesOrderService;

    @Autowired(required = false)
    private IErpSalesPushService salesPushService;

    /**
     * <p> 单据对象的"存在性 + 数据范围"校验器（公共层，由 backend-base 交付）。 </p>
     *
     * <p> 只用于<b>按标识导出</b>（{@code ?ids=…}）：范围外抛业务码 403。
     * 列表导出不需要它——范围已经由 {@code ErpDocScope} 拼进列表 SQL。 </p>
     */
    @Autowired(required = false)
    private IErpDocObjectAccess docObjectAccess;

    /* ==================== 销售申请单 ==================== */

    /**
     * 查询销售申请单列表（状态、关键字、单据日期区间、是否含已作废；默认排除已作废）。
     *
     * @param query 查询条件
     * @return 分页后的单据集合
     */
    @PreAuthorize("@ss.hasPermi('sal:request:list')")
    @GetMapping("/request/list")
    public TableDataInfo listRequest(ErpSalesRequest query)
    {
        startPage();
        List<ErpSalesRequest> list = salesRequestService.selectSalesRequestList(query);
        return getDataTable(list);
    }

    /**
     * 查询销售申请单详细（含行项与变更历史）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('sal:request:query')")
    @GetMapping("/request/{id:[A-Za-z0-9]+}")
    public AjaxResult getRequest(@PathVariable("id") String id)
    {
        return success(salesRequestService.selectSalesRequestById(id));
    }

    /**
     * 查询销售申请单行项（下推对话框取"剩余可下推量"）。
     *
     * @param id 单据ID
     * @return 行项集合
     */
    @PreAuthorize("@ss.hasPermi('sal:request:query')")
    @GetMapping("/request/{id:[A-Za-z0-9]+}/items")
    public AjaxResult listRequestItems(@PathVariable("id") String id)
    {
        List<ErpSalesRequestItem> items = salesRequestService.selectSalesRequestItems(id);
        return success(items);
    }

    /**
     * 新增销售申请单（草稿）。
     *
     * @param doc 单据（含行项）
     * @return 结果（含服务端生成的 id 与单号）
     */
    @PreAuthorize("@ss.hasPermi('sal:request:add')")
    @Log(title = "销售申请单", businessType = BusinessType.INSERT)
    @PostMapping("/request")
    public AjaxResult addRequest(@RequestBody ErpSalesRequest doc)
    {
        return success(salesRequestService.insertSalesRequest(doc));
    }

    /**
     * 修改销售申请单（仅草稿）。
     *
     * @param doc 单据（含行项）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:edit')")
    @Log(title = "销售申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/request")
    public AjaxResult editRequest(@RequestBody ErpSalesRequest doc)
    {
        return success(salesRequestService.updateSalesRequest(doc));
    }

    /**
     * 删除销售申请单（仅草稿）。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:remove')")
    @Log(title = "销售申请单", businessType = BusinessType.DELETE)
    @DeleteMapping("/request/{id:[A-Za-z0-9]+}")
    public AjaxResult removeRequest(@PathVariable("id") String id)
    {
        salesRequestService.deleteSalesRequestById(id);
        return success();
    }

    /**
     * 提交销售申请单（草稿 → 待审核；要求至少一行行项）。
     *
     * @param id   单据ID
     * @param body 请求体（可含 {@code remark}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:submit')")
    @Log(title = "销售申请单提交", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/submit")
    public AjaxResult submitRequest(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesRequestService.submitSalesRequest(id, text(body, "remark"));
        return success();
    }

    /**
     * 审核销售申请单（待审核 → 已审核）。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:approve')")
    @Log(title = "销售申请单审核", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/approve")
    public AjaxResult approveRequest(@PathVariable("id") String id)
    {
        salesRequestService.approveSalesRequest(id);
        return success();
    }

    /**
     * 驳回销售申请单（待审核 → 草稿；原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:approve')")
    @Log(title = "销售申请单驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/reject")
    public AjaxResult rejectRequest(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesRequestService.rejectSalesRequest(id, text(body, "reason"));
        return success();
    }

    /**
     * 作废销售申请单（草稿/待审核 → 已作废；原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:void')")
    @Log(title = "销售申请单作废", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/void")
    public AjaxResult voidRequest(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesRequestService.voidSalesRequest(id, text(body, "reason"));
        return success();
    }

    /**
     * 反审核销售申请单（已审核 → 待审核；原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:unapprove')")
    @Log(title = "销售申请单反审核", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/unapprove")
    public AjaxResult unapproveRequest(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesRequestService.unapproveSalesRequest(id, text(body, "reason"));
        return success();
    }

    /**
     * 手工置为已完成（已审核 → 已完成）。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:request:status')")
    @Log(title = "销售申请单置完成", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/complete")
    public AjaxResult completeRequest(@PathVariable("id") String id)
    {
        salesRequestService.completeSalesRequest(id);
        return success();
    }

    /**
     * 关联合同（仅销售方向；传空表示解除关联）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code contractId}）
     * @return 结果（含保存后的合同编号快照）
     */
    @PreAuthorize("@ss.hasPermi('sal:request:edit')")
    @Log(title = "销售申请单关联合同", businessType = BusinessType.UPDATE)
    @PutMapping("/request/{id:[A-Za-z0-9]+}/contract")
    public AjaxResult linkRequestContract(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("contractNo", salesRequestService.linkContract(id, text(body, "contractId")));
        return success(result);
    }

    /**
     * 下推销售订单（仅已审核可推；不填行表示按剩余量全推）。
     *
     * @param id      销售申请单ID
     * @param request 下推参数（可空）
     * @return 结果（新订单 id/单号、本次下推量与申请单是否已归零完成）
     */
    @PreAuthorize("@ss.hasPermi('sal:request:push')")
    @Log(title = "销售申请单下推销售订单", businessType = BusinessType.INSERT)
    @PostMapping("/request/{id:[A-Za-z0-9]+}/push")
    public AjaxResult pushRequest(@PathVariable("id") String id, @RequestBody(required = false) ErpSalesPushRequest request)
    {
        return success(salesPushService.pushSalesRequestToOrder(id, request));
    }

    /* ==================== 销售订单 ==================== */

    /**
     * 查询销售订单列表。
     *
     * @param query 查询条件
     * @return 分页后的单据集合
     */
    @PreAuthorize("@ss.hasPermi('sal:order:list')")
    @GetMapping("/order/list")
    public TableDataInfo listOrder(ErpSalesOrder query)
    {
        startPage();
        List<ErpSalesOrder> list = salesOrderService.selectSalesOrderList(query);
        return getDataTable(list);
    }

    /**
     * 查询销售订单详细（含行项与变更历史）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('sal:order:query')")
    @GetMapping("/order/{id:[A-Za-z0-9]+}")
    public AjaxResult getOrder(@PathVariable("id") String id)
    {
        return success(salesOrderService.selectSalesOrderById(id));
    }

    /**
     * 查询销售订单行项。
     *
     * @param id 单据ID
     * @return 行项集合
     */
    @PreAuthorize("@ss.hasPermi('sal:order:query')")
    @GetMapping("/order/{id:[A-Za-z0-9]+}/items")
    public AjaxResult listOrderItems(@PathVariable("id") String id)
    {
        List<ErpSalesOrderItem> items = salesOrderService.selectSalesOrderItems(id);
        return success(items);
    }

    /**
     * 新增销售订单（客户必填且启用；禁止引用供应商）。
     *
     * @param doc 单据（含行项）
     * @return 结果（含服务端生成的 id 与单号）
     */
    @PreAuthorize("@ss.hasPermi('sal:order:add')")
    @Log(title = "销售订单", businessType = BusinessType.INSERT)
    @PostMapping("/order")
    public AjaxResult addOrder(@RequestBody ErpSalesOrder doc)
    {
        return success(salesOrderService.insertSalesOrder(doc));
    }

    /**
     * 修改销售订单（仅草稿）。
     *
     * @param doc 单据（含行项）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:edit')")
    @Log(title = "销售订单", businessType = BusinessType.UPDATE)
    @PutMapping("/order")
    public AjaxResult editOrder(@RequestBody ErpSalesOrder doc)
    {
        return success(salesOrderService.updateSalesOrder(doc));
    }

    /**
     * 删除销售订单（仅草稿）。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:remove')")
    @Log(title = "销售订单", businessType = BusinessType.DELETE)
    @DeleteMapping("/order/{id:[A-Za-z0-9]+}")
    public AjaxResult removeOrder(@PathVariable("id") String id)
    {
        salesOrderService.deleteSalesOrderById(id);
        return success();
    }

    /**
     * 提交销售订单。
     *
     * @param id   单据ID
     * @param body 请求体（可含 {@code remark}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:submit')")
    @Log(title = "销售订单提交", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/submit")
    public AjaxResult submitOrder(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesOrderService.submitSalesOrder(id, text(body, "remark"));
        return success();
    }

    /**
     * 审核销售订单。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:approve')")
    @Log(title = "销售订单审核", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/approve")
    public AjaxResult approveOrder(@PathVariable("id") String id)
    {
        salesOrderService.approveSalesOrder(id);
        return success();
    }

    /**
     * 驳回销售订单（原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:approve')")
    @Log(title = "销售订单驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/reject")
    public AjaxResult rejectOrder(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesOrderService.rejectSalesOrder(id, text(body, "reason"));
        return success();
    }

    /**
     * 作废销售订单（原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:void')")
    @Log(title = "销售订单作废", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/void")
    public AjaxResult voidOrder(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesOrderService.voidSalesOrder(id, text(body, "reason"));
        return success();
    }

    /**
     * 反审核销售订单（原因必填）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code reason}）
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:unapprove')")
    @Log(title = "销售订单反审核", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/unapprove")
    public AjaxResult unapproveOrder(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        salesOrderService.unapproveSalesOrder(id, text(body, "reason"));
        return success();
    }

    /**
     * 手工置为已完成。
     *
     * @param id 单据ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('sal:order:status')")
    @Log(title = "销售订单置完成", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/complete")
    public AjaxResult completeOrder(@PathVariable("id") String id)
    {
        salesOrderService.completeSalesOrder(id);
        return success();
    }

    /**
     * 关联合同（仅销售方向；传空表示解除关联）。
     *
     * @param id   单据ID
     * @param body 请求体（含 {@code contractId}）
     * @return 结果（含保存后的合同编号快照）
     */
    @PreAuthorize("@ss.hasPermi('sal:order:edit')")
    @Log(title = "销售订单关联合同", businessType = BusinessType.UPDATE)
    @PutMapping("/order/{id:[A-Za-z0-9]+}/contract")
    public AjaxResult linkOrderContract(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("contractNo", salesOrderService.linkContract(id, text(body, "contractId")));
        return success(result);
    }

    /**
     * <p> <b>销售订单下推出库单</b>（2.0 B4 任务 4.3）。 </p>
     *
     * <p> <b>请求体两种形状都收</b>（前端两条下推链的 {@code bodyShape} 不同，见 {@code doc-rules.js}）： </p>
     * <ul>
     *   <li> <b>裸数组</b>（{@code lines-array}，前端 {@code sales_order} 链发的形状）：
     *        {@code [{srcItemId, qty}, …]}； </li>
     *   <li> <b>包装对象</b>：{@code {lines:[…], remark, shipWarehouseId, docDate}}。 </li>
     * </ul>
     *
     * <p> 两种都支持是刻意的：后端只有一个入口，形状差异不该让前端"因为链不同而记两个 URL"。 </p>
     *
     * @param id   销售订单ID
     * @param body 请求体（可空 = 全部行按剩余量全推）
     * @return 结果（新出库单 id/单号、本次下推量与剩余量）
     */
    @PreAuthorize("@ss.hasPermi('sal:order:push')")
    @Log(title = "销售订单下推出库单", businessType = BusinessType.INSERT)
    @PostMapping("/order/{id:[A-Za-z0-9]+}/push")
    public AjaxResult pushOrder(@PathVariable("id") String id, @RequestBody(required = false) Object body)
    {
        return success(salesPushService.pushSalesOrderToStockOut(id, pushRequestOf(body)));
    }

    /**
     * 把两种请求体形状归一成 {@link ErpSalesPushRequest}。
     *
     * @param body 请求体（裸数组 / 包装对象 / null）
     * @return 归一后的下推参数；无法识别时返回 null（服务层按"全部行全推"处理）
     */
    private static ErpSalesPushRequest pushRequestOf(Object body)
    {
        if (body == null)
        {
            return null;
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (body instanceof java.util.List)
        {
            ErpSalesPushRequest request = new ErpSalesPushRequest();
            request.setLines(mapper.convertValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.ruoyi.ctms.erp.sales.domain.ErpSalesPushLine>>()
                    {
                    }));
            return request;
        }
        if (body instanceof java.util.Map)
        {
            return mapper.convertValue(body, ErpSalesPushRequest.class);
        }
        return null;
    }

    /**
     * <p> <b>导出销售申请单</b>（t28；审计 F1 的 blocker：前端已有导出按钮、菜单已有
     * {@code sal:request:export}，此前后端缺端点）。 </p>
     *
     * <p> <b>三条口径</b>（与 B3 的 {@code CtmsContractController#export}、
     * T3 的 {@code ErpStockInController#export} 同款）： </p>
     * <ol>
     *   <li> 取数<b>只走</b> {@link IErpSalesRequestService#selectSalesRequestList}：
     *        与列表同一处数据范围判定（{@code ErpDocScope} 拼的白名单片段），
     *        不存在"列表看不到、导出全拿到"的越权口子； </li>
     *   <li> 列定义来自 {@link ErpSalesExportRow} 的 {@code @Excel} 注解； </li>
     *   <li> 传了 {@code ids} 时按标识导出（<b>逐个过 {@code IErpDocObjectAccess} 的存在性 +
     *        数据范围校验</b>），范围外抛业务码 <b>403</b> —— 既不是 500，也不返回数据。 </li>
     * </ol>
     *
     * <p> <b>方法同时接 GET 与 POST</b>：前端 {@code utils/request.js} 的 {@code download()}
     * 用的是 <b>POST + form-urlencoded</b>（{@code responseType:'blob'}），
     * 所以参数按普通命令对象绑定（<b>不是</b> {@code @RequestBody}），
     * 这样 GET 的 query string 与 POST 的表单体都能读到同一组筛选。 </p>
     *
     * @param response 响应（Excel 直接写流；{@code Content-Type} 非 {@code application/json}，
     *                 前端 {@code blobValidate} 据此判定"这是文件"）
     * @param query    与列表一致的筛选条件
     * @param ids      可选：指定单据ID（逗号分隔或多个同名参数）；给了就按标识导出
     */
    @PreAuthorize("@ss.hasPermi('sal:request:export')")
    @Log(title = "销售申请单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/request/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void exportRequest(HttpServletResponse response, ErpSalesRequest query,
                              @RequestParam(value = "ids", required = false) String[] ids)
    {
        ExcelUtil<ErpSalesExportRow> util = new ExcelUtil<>(ErpSalesExportRow.class);
        util.exportExcel(response, exportRequestRows(query, ids), "销售申请单");
    }

    /**
     * 装配销售申请单的导出行（{@code public} 以便单测直接核对行内容与范围口径，不必伪造 HTTP 响应体）。
     *
     * @param query 筛选条件（可空）
     * @param ids   指定单据ID（可空：为空则按筛选 + 数据范围导出全部可见单据）
     * @return 导出行集合（一文档 × 一行项）
     */
    public List<ErpSalesExportRow> exportRequestRows(ErpSalesRequest query, String[] ids)
    {
        if (ids != null && ids.length > 0)
        {
            List<ErpSalesExportRow> rows = new ArrayList<>();
            for (String id : ids)
            {
                String docId = trim(id);
                if (docId == null)
                {
                    continue;
                }
                requireAccessible(ErpDocType.SALES_REQUEST, docId);
                ErpSalesRequest doc = salesRequestService.selectSalesRequestById(docId);
                if (doc != null)
                {
                    addRequestRows(rows, doc, doc.getItems());
                }
            }
            return rows;
        }
        List<ErpSalesRequest> docs = salesRequestService.selectSalesRequestList(query);
        if (docs == null || docs.isEmpty())
        {
            return new ArrayList<>();
        }
        List<String> docIds = new ArrayList<>(docs.size());
        for (ErpSalesRequest doc : docs)
        {
            if (doc != null && trim(doc.getId()) != null)
            {
                docIds.add(doc.getId());
            }
        }
        Map<String, List<ErpSalesRequestItem>> grouped =
                salesRequestService.selectSalesRequestItemsByDocIds(docIds);
        List<ErpSalesExportRow> rows = new ArrayList<>();
        for (ErpSalesRequest doc : docs)
        {
            if (doc == null)
            {
                continue;
            }
            addRequestRows(rows, doc, grouped == null ? null : grouped.get(doc.getId()));
        }
        return rows;
    }

    /**
     * <p> <b>导出销售订单</b>（t28；{@code sal:order:export}）。 </p>
     *
     * <p> 口径与 {@link #exportRequest} 完全同款（同一处数据范围、同一套请求形态、
     * 同一个 403 语义），差异只在列内容来自订单（客户名称<b>快照</b>与发货信息）。 </p>
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     * @param ids      可选：指定单据ID
     */
    @PreAuthorize("@ss.hasPermi('sal:order:export')")
    @Log(title = "销售订单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/order/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void exportOrder(HttpServletResponse response, ErpSalesOrder query,
                            @RequestParam(value = "ids", required = false) String[] ids)
    {
        ExcelUtil<ErpSalesExportRow> util = new ExcelUtil<>(ErpSalesExportRow.class);
        util.exportExcel(response, exportOrderRows(query, ids), "销售订单");
    }

    /**
     * 装配销售订单的导出行（{@code public} 理由同 {@link #exportRequestRows}）。
     *
     * @param query 筛选条件（可空）
     * @param ids   指定单据ID（可空）
     * @return 导出行集合（一文档 × 一行项）
     */
    public List<ErpSalesExportRow> exportOrderRows(ErpSalesOrder query, String[] ids)
    {
        if (ids != null && ids.length > 0)
        {
            List<ErpSalesExportRow> rows = new ArrayList<>();
            for (String id : ids)
            {
                String docId = trim(id);
                if (docId == null)
                {
                    continue;
                }
                requireAccessible(ErpDocType.SALES_ORDER, docId);
                ErpSalesOrder doc = salesOrderService.selectSalesOrderById(docId);
                if (doc != null)
                {
                    addOrderRows(rows, doc, doc.getItems());
                }
            }
            return rows;
        }
        List<ErpSalesOrder> docs = salesOrderService.selectSalesOrderList(query);
        if (docs == null || docs.isEmpty())
        {
            return new ArrayList<>();
        }
        List<String> docIds = new ArrayList<>(docs.size());
        for (ErpSalesOrder doc : docs)
        {
            if (doc != null && trim(doc.getId()) != null)
            {
                docIds.add(doc.getId());
            }
        }
        Map<String, List<ErpSalesOrderItem>> grouped = salesOrderService.selectSalesOrderItemsByDocIds(docIds);
        List<ErpSalesExportRow> rows = new ArrayList<>();
        for (ErpSalesOrder doc : docs)
        {
            if (doc == null)
            {
                continue;
            }
            addOrderRows(rows, doc, grouped == null ? null : grouped.get(doc.getId()));
        }
        return rows;
    }

    /**
     * <p> <b>按标识导出前的"存在性 + 数据范围"校验</b>（范围外 → 业务码 403）。 </p>
     *
     * <p> 复用公共层 {@code IErpDocObjectAccess}（与附件、单据详情同一处判定），
     * <b>不另写一套范围逻辑</b>。校验器缺失时<b>拒绝导出</b>（fail closed）：
     * 少了校验就等于把范围外数据导出去，宁可报错也不能静默放行。 </p>
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @throws ServiceException 未装配校验器 / 单据不存在 / 超出数据范围（403）
     */
    private void requireAccessible(ErpDocType docType, String docId)
    {
        if (docObjectAccess == null)
        {
            throw new ServiceException("单据对象访问校验器未装配，拒绝按标识导出（避免绕过数据范围）");
        }
        docObjectAccess.checkObjectAccess(docType.getCode(), docId);
    }

    /**
     * 把一张销售申请单摊平成若干导出行（一文档 × 一行项；无行项时仍出一行表头行）。
     *
     * @param rows  输出集合
     * @param doc   单据
     * @param items 行项（可空）
     */
    private void addRequestRows(List<ErpSalesExportRow> rows, ErpSalesRequest doc,
                               List<ErpSalesRequestItem> items)
    {
        ErpSalesExportRow head = new ErpSalesExportRow();
        head.setDocTypeLabel(ErpDocType.SALES_REQUEST.getLabel());
        head.setDocNo(doc.getDocNo());
        head.setDocDate(doc.getDocDate());
        head.setStatus(ErpDocStatus.labelOf(doc.getStatus()));
        head.setCustomerId(doc.getCustomerId());
        // 申请单的客户允许只填文本（无档案引用）：名称列取文本兜底（list/detail 未 join 档案名）
        head.setCustomerName(ErpSalRules.trim(doc.getCustomerNameText()));
        head.setHandlerName(doc.getHandlerName());
        head.setDeptId(doc.getDeptId());
        head.setContractNo(doc.getContractNo());
        head.setSourceDocNo(doc.getSourceDocNo());
        head.setDeliveryDate(doc.getExpectDeliveryDate());
        head.setRemainQtySum(doc.getRemainQtySum());
        head.setTotalAmount(doc.getTotalAmount());
        head.setRemark(doc.getRemark());
        if (items == null || items.isEmpty())
        {
            rows.add(head);
            return;
        }
        for (ErpSalesRequestItem item : items)
        {
            ErpSalesExportRow row = copyHead(head);
            fillItem(row, item);
            rows.add(row);
        }
    }

    /**
     * 把一张销售订单摊平成若干导出行（一文档 × 一行项）。
     *
     * @param rows  输出集合
     * @param doc   单据
     * @param items 行项（可空）
     */
    private void addOrderRows(List<ErpSalesExportRow> rows, ErpSalesOrder doc, List<ErpSalesOrderItem> items)
    {
        ErpSalesExportRow head = new ErpSalesExportRow();
        head.setDocTypeLabel(ErpDocType.SALES_ORDER.getLabel());
        head.setDocNo(doc.getDocNo());
        head.setDocDate(doc.getDocDate());
        head.setStatus(ErpDocStatus.labelOf(doc.getStatus()));
        head.setCustomerId(doc.getCustomerId());
        head.setCustomerName(doc.getCustomerName());
        head.setHandlerName(doc.getHandlerName());
        head.setDeptId(doc.getDeptId());
        head.setContractNo(doc.getContractNo());
        head.setSourceDocNo(doc.getSourceDocNo());
        head.setDeliveryDate(doc.getDeliveryDate());
        head.setDeliveryAddress(doc.getDeliveryAddress());
        head.setContactName(doc.getContactName());
        head.setContactPhone(doc.getContactPhone());
        head.setShipWarehouseId(doc.getShipWarehouseId());
        head.setRemainQtySum(doc.getRemainQtySum());
        head.setTotalAmount(doc.getTotalAmount());
        head.setRemark(doc.getRemark());
        if (items == null || items.isEmpty())
        {
            rows.add(head);
            return;
        }
        for (ErpSalesOrderItem item : items)
        {
            ErpSalesExportRow row = copyHead(head);
            fillItem(row, item);
            rows.add(row);
        }
    }

    /**
     * 复制表头列（每个行项一行，表头字段重复出现）。
     *
     * @param head 表头行
     * @return 新行
     */
    private ErpSalesExportRow copyHead(ErpSalesExportRow head)
    {
        ErpSalesExportRow row = new ErpSalesExportRow();
        row.setDocTypeLabel(head.getDocTypeLabel());
        row.setDocNo(head.getDocNo());
        row.setDocDate(head.getDocDate());
        row.setStatus(head.getStatus());
        row.setCustomerId(head.getCustomerId());
        row.setCustomerName(head.getCustomerName());
        row.setHandlerName(head.getHandlerName());
        row.setDeptId(head.getDeptId());
        row.setContractNo(head.getContractNo());
        row.setSourceDocNo(head.getSourceDocNo());
        row.setDeliveryDate(head.getDeliveryDate());
        row.setDeliveryAddress(head.getDeliveryAddress());
        row.setContactName(head.getContactName());
        row.setContactPhone(head.getContactPhone());
        row.setShipWarehouseId(head.getShipWarehouseId());
        row.setRemainQtySum(head.getRemainQtySum());
        row.setTotalAmount(head.getTotalAmount());
        row.setRemark(head.getRemark());
        return row;
    }

    /**
     * 填充行项关键列（金额取行项已固化的 {@code amount}，本层不做任何计算）。
     *
     * @param row  导出行
     * @param item 行项
     */
    private void fillItem(ErpSalesExportRow row, ErpDocItem item)
    {
        if (item == null)
        {
            return;
        }
        row.setSeq(item.getSeq());
        row.setProductCode(item.getProductCode());
        row.setProductName(item.getProductName());
        row.setSpec(item.getSpec());
        row.setUomName(item.getUomName());
        row.setQty(item.getQty());
        row.setUnitPrice(item.getUnitPrice());
        row.setLineAmount(item.getAmount());
        row.setItemRemark(item.getRemark());
    }

    /**
     * 去空白归一（空返回 null）。
     *
     * @param value 原始值
     * @return 归一后的值或 null
     */
    private static String trim(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 从请求体里取文本字段（缺字段/非文本一律返回 null，交给服务层报"必填"）。
     *
     * @param body 请求体（可空）
     * @param key  字段名
     * @return 文本；无值返回 null
     */
    private static String text(Map<String, Object> body, String key)
    {
        if (body == null)
        {
            return null;
        }
        Object value = body.get(key);
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text.trim();
    }
}
