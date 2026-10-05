package com.ruoyi.ctms.erp.stockops.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocAction;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsRules;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;
import com.ruoyi.ctms.erp.stockops.service.IErpStocktakeService;

/**
 * <p> <b>盘点单控制器</b>（2.0 B4 任务 6.3~6.5；URL 前缀 {@code /stk/take}，
 * 权限点前缀 {@code stk:take}，均为 captain §9.2 冻结口径）。 </p>
 *
 * <p> <b>盘点特有的两个窄入口</b>（前端 {@code src/api/erp/doc.js} 已在用）： </p>
 * <ul>
 *   <li> {@code POST /stk/take/{id}/generate} —— 重新生成行项，权限点仍为 {@code stk:take:add}； </li>
 *   <li> {@code PUT /stk/take/{id}/count} —— 录入实盘数量，权限点仍为 {@code stk:take:edit}。 </li>
 * </ul>
 * <p> 换句话说：<b>"生成行项"并入 add、"录入实盘"并入 edit，没有新增任何动作名与权限点</b>；
 * 新增单据时（{@code POST /stk/take}）服务端已经按盘点范围自动生成行项，
 * 这两个端点只是"对已有草稿按 id 操作"的窄入口。 </p>
 *
 * <p> 动作路由与调拨单同款：既支持前端壳的 {@code POST /{id}/{action}}，也支持
 * T3 形态的 {@code POST /{action}/{id}} 与 {@code PUT /status}。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/take")
public class ErpStocktakeController extends BaseController
{
    @Autowired
    private IErpStocktakeService stocktakeService;

    /**
     * 盘点单列表（关键字 / 状态 / 仓库 / 盘点范围 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpStocktake query)
    {
        startPage();
        List<ErpStocktake> list = stocktakeService.selectStocktakeList(query);
        return getDataTable(list);
    }

    /**
     * 导出盘点单（与列表同一处数据范围与筛选）。
     *
     * @param response 响应
     * @param query    筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:take:export')")
    @Log(title = "盘点单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpStocktake query)
    {
        ExcelUtil<ErpStocktakeExportRow> util = new ExcelUtil<>(ErpStocktakeExportRow.class);
        util.exportExcel(response, exportRows(query), "盘点单");
    }

    /**
     * 装配导出行。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpStocktakeExportRow> exportRows(ErpStocktake query)
    {
        List<ErpStocktake> list = stocktakeService.selectStocktakeList(query);
        List<ErpStocktakeExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpStocktake doc : list)
            {
                if (doc != null)
                {
                    rows.add(ErpStocktakeExportRow.of(doc));
                }
            }
        }
        return rows;
    }

    /**
     * 盘点单详情（含行项）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('stk:take:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(stocktakeService.selectStocktakeDetail(id));
    }

    /**
     * 变更历史（分页）。
     *
     * @param id 单据ID
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}/change-logs")
    public TableDataInfo changeLogs(@PathVariable("id") String id)
    {
        startPage();
        List<CtmsChangeLog> list = stocktakeService.selectChangeLogList(id);
        return getDataTable(list);
    }

    /**
     * 新增盘点单（草稿）并<b>生成行项</b>：全盘取该仓库结存非零物料；
     * 抽盘取指定物料或商品类型（含整棵子树）。账面数量在此时固化。
     *
     * @param doc 单据（{@code items} 可空；抽盘可用 {@code productTypeIds}）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:add')")
    @Log(title = "盘点单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpStocktake doc)
    {
        return success(stocktakeService.insertStocktake(doc));
    }

    /**
     * 编辑盘点单（仅草稿）：录入实盘数量（账面只读、服务端从已生成行项取）。
     *
     * @param doc 单据（{@code items} 携带实盘数量）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:edit')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpStocktake doc)
    {
        return success(stocktakeService.updateStocktake(doc));
    }

    /**
     * 生成行项（窄入口；权限点沿用 {@code stk:take:add}）。
     *
     * @param id       单据ID
     * @param criteria 生成参数（{@code takeType} / {@code productTypeIds} / {@code items}；可空）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:add')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/{id:[A-Za-z0-9]+}/generate")
    public AjaxResult generate(@PathVariable("id") String id,
                               @RequestBody(required = false) ErpStocktake criteria)
    {
        return success(stocktakeService.generateItems(id, criteria));
    }

    /**
     * 录入实盘数量（窄入口；权限点沿用 {@code stk:take:edit}）。
     *
     * @param id   单据ID
     * @param body 录入内容（{@code items}：行 id 或物料 id + 实盘数量 + 差异原因）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:edit')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PutMapping(value = "/{id:[A-Za-z0-9]+}/count")
    public AjaxResult saveCount(@PathVariable("id") String id, @RequestBody CountRequest body)
    {
        List<ErpStocktakeItem> items = body == null ? null : body.getItems();
        return success(stocktakeService.saveCount(id, items));
    }

    /**
     * 批量删除盘点单（仅草稿）。
     *
     * @param ids 单据ID数组
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:remove')")
    @Log(title = "盘点单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids:[A-Za-z0-9,]+}")
    public AjaxResult remove(@PathVariable("ids") String[] ids)
    {
        return toAjax(stocktakeService.deleteStocktakeByIds(ids));
    }

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:submit')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/submit/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/submit" })
    public AjaxResult submit(@PathVariable("id") String id)
    {
        return success(stocktakeService.submitStocktake(id));
    }

    /**
     * 审核（待审核 → 已审核）：按差异生成盘盈/盘亏单并过账（同一事务）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:approve')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/approve/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/approve" })
    public AjaxResult approve(@PathVariable("id") String id)
    {
        return success(stocktakeService.approveStocktake(id));
    }

    /**
     * 驳回（待审核 → 草稿；原因必填）。权限点与审核共用 {@code approve}。
     *
     * @param id     单据ID
     * @param body   动作请求（前端把原因放请求体）
     * @param reason URL 参数形态
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:approve')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/reject/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/reject" })
    public AjaxResult reject(@PathVariable("id") String id,
                             @RequestBody(required = false) StatusAction body,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stocktakeService.rejectStocktake(id, resolveReason(body, reason)));
    }

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param body   动作请求
     * @param reason URL 参数形态
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:void')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/void/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/void" })
    public AjaxResult voidDoc(@PathVariable("id") String id,
                              @RequestBody(required = false) StatusAction body,
                              @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stocktakeService.voidStocktake(id, resolveReason(body, reason)));
    }

    /**
     * 反审核（已审核 → 待审核；原因必填）：级联红冲并作废其生成的盘盈/盘亏单。
     *
     * @param id     单据ID
     * @param body   动作请求
     * @param reason URL 参数形态
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:unapprove')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/unapprove/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/unapprove" })
    public AjaxResult unapprove(@PathVariable("id") String id,
                                @RequestBody(required = false) StatusAction body,
                                @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stocktakeService.unapproveStocktake(id, resolveReason(body, reason)));
    }

    /**
     * 通用状态动作入口（单据壳用）。
     *
     * @param body 动作请求（id / action / reason）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:take:status')")
    @Log(title = "盘点单", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody StatusAction body)
    {
        if (body == null || ErpStockOpsRules.isBlank(body.getId()))
        {
            throw new ServiceException("单据ID不能为空");
        }
        String action = body.getAction() == null ? null : body.getAction().trim().toLowerCase();
        if (ErpDocAction.SUBMIT.getActionCode().equals(action))
        {
            return success(stocktakeService.submitStocktake(body.getId()));
        }
        if (ErpDocAction.APPROVE.getActionCode().equals(action))
        {
            return success(stocktakeService.approveStocktake(body.getId()));
        }
        if (ErpDocAction.REJECT.getActionCode().equals(action))
        {
            return success(stocktakeService.rejectStocktake(body.getId(), body.getReason()));
        }
        if (ErpDocAction.VOID.getActionCode().equals(action))
        {
            return success(stocktakeService.voidStocktake(body.getId(), body.getReason()));
        }
        if (ErpDocAction.UNAPPROVE.getActionCode().equals(action))
        {
            return success(stocktakeService.unapproveStocktake(body.getId(), body.getReason()));
        }
        if (ErpDocAction.COMPLETE.getActionCode().equals(action))
        {
            throw new ServiceException(ErpDocType.STOCK_TAKE.getLabel()
                    + "为库存类单据，不支持「置为已完成」");
        }
        throw new ServiceException("不支持的单据动作：" + body.getAction());
    }

    /**
     * 原因取值：请求体优先（前端契约），其次 URL 参数。
     *
     * @param body   动作请求
     * @param reason URL 参数
     * @return 原因
     */
    private String resolveReason(StatusAction body, String reason)
    {
        if (body != null && body.getReason() != null)
        {
            return body.getReason();
        }
        return reason;
    }

    /**
     * 通用状态动作请求体（非持久化）。
     */
    public static class StatusAction
    {
        /** 单据ID。 */
        private String id;

        /** 动作码。 */
        private String action;

        /** 原因（驳回/作废/反审核必填）。 */
        private String reason;

        public String getId()
        {
            return id;
        }

        public void setId(String id)
        {
            this.id = id;
        }

        public String getAction()
        {
            return action;
        }

        public void setAction(String action)
        {
            this.action = action;
        }

        public String getReason()
        {
            return reason;
        }

        public void setReason(String reason)
        {
            this.reason = reason;
        }
    }

    /**
     * 实盘录入请求体（{@code PUT /stk/take/{id}/count}）。
     */
    public static class CountRequest
    {
        /** 单据ID（路径参数已在路径上；这里兼容前端把 id 也放在体里）。 */
        private String id;

        /** 行项（行 id 或物料 id + 实盘数量 + 差异原因）。 */
        private List<ErpStocktakeItem> items;

        public String getId()
        {
            return id;
        }

        public void setId(String id)
        {
            this.id = id;
        }

        public List<ErpStocktakeItem> getItems()
        {
            return items;
        }

        public void setItems(List<ErpStocktakeItem> items)
        {
            this.items = items;
        }
    }

    /**
     * 导出用行对象。
     */
    public static class ErpStocktakeExportRow
    {
        /** 单号。 */
        @Excel(name = "单号")
        private String docNo;

        /** 单据日期。 */
        @Excel(name = "单据日期", dateFormat = "yyyy-MM-dd")
        private Date docDate;

        /** 状态（中文名）。 */
        @Excel(name = "状态")
        private String status;

        /** 盘点仓库。 */
        @Excel(name = "盘点仓库")
        private String warehouse;

        /** 盘点范围。 */
        @Excel(name = "盘点范围")
        private String takeType;

        /** 范围说明。 */
        @Excel(name = "范围说明")
        private String scopeNote;

        /** 生成的盘盈入库单号。 */
        @Excel(name = "盘盈入库单")
        private String generatedInNo;

        /** 生成的盘亏出库单号。 */
        @Excel(name = "盘亏出库单")
        private String generatedOutNo;

        /** 过账标记。 */
        @Excel(name = "过账")
        private String posted;

        /**
         * 从单据装配一行。
         *
         * @param doc 单据
         * @return 导出行
         */
        static ErpStocktakeExportRow of(ErpStocktake doc)
        {
            ErpStocktakeExportRow row = new ErpStocktakeExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = ErpDocStatus.labelOf(doc.getStatus());
            row.warehouse = doc.getWarehouseName();
            row.takeType = ErpStockOpsRules.TAKE_TYPE_PARTIAL.equals(doc.getTakeType()) ? "抽盘" : "全盘";
            row.scopeNote = doc.getScopeNote();
            row.generatedInNo = doc.getGeneratedInNo();
            row.generatedOutNo = doc.getGeneratedOutNo();
            row.posted = doc.isPostedFlag() ? "已过账" : "未过账";
            return row;
        }

        public String getDocNo()
        {
            return docNo;
        }

        public Date getDocDate()
        {
            return docDate;
        }

        public String getStatus()
        {
            return status;
        }

        public String getWarehouse()
        {
            return warehouse;
        }

        public String getTakeType()
        {
            return takeType;
        }

        public String getScopeNote()
        {
            return scopeNote;
        }

        public String getGeneratedInNo()
        {
            return generatedInNo;
        }

        public String getGeneratedOutNo()
        {
            return generatedOutNo;
        }

        public String getPosted()
        {
            return posted;
        }
    }
}
