package com.ruoyi.ctms.erp.posting.controller;

import java.math.BigDecimal;
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
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.service.IErpStockOutService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;

/**
 * <p> <b>出库单控制器</b>（2.0 B4 任务 5.1；URL 前缀 {@code /stk/out-order}）。 </p>
 *
 * <p> 与 {@link ErpStockInController} 对称（同一批端点、同一批权限动作、同一个通用
 * {@code /status} 入口），差异只在单据类型与"出库类型/客户"两列。 </p>
 *
 * <p> <b>负库存</b>：审核走服务层的默认过账选项（按系统参数 {@code stock_allow_negative} 校验），
 * <b>不接受</b>请求参数指定"跳过校验"—— 盘亏单的豁免只能由服务端内部路径触发。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/out-order")
public class ErpStockOutController extends BaseController
{
    @Autowired
    private IErpStockOutService stockOutService;

    /**
     * 出库单列表（关键字 / 出库类型 / 仓库 / 客户 / 状态 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpStockOut query)
    {
        startPage();
        List<ErpStockOut> list = stockOutService.selectStockOutList(query);
        return getDataTable(list);
    }

    /**
     * 导出出库单（与列表同一处范围与筛选）。
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:export')")
    @Log(title = "出库单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpStockOut query)
    {
        ExcelUtil<ErpStockOutExportRow> util = new ExcelUtil<>(ErpStockOutExportRow.class);
        util.exportExcel(response, exportRows(query), "出库单");
    }

    /**
     * 装配导出行。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpStockOutExportRow> exportRows(ErpStockOut query)
    {
        List<ErpStockOut> list = stockOutService.selectStockOutList(query);
        List<ErpStockOutExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpStockOut doc : list)
            {
                if (doc != null)
                {
                    rows.add(ErpStockOutExportRow.of(doc));
                }
            }
        }
        return rows;
    }

    /**
     * 出库单详情（含行项）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(stockOutService.selectStockOutDetail(id));
    }

    /**
     * 变更历史（分页）。
     *
     * @param id 单据ID
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}/change-logs")
    public TableDataInfo changeLogs(@PathVariable("id") String id)
    {
        startPage();
        List<CtmsChangeLog> list = stockOutService.selectChangeLogList(id);
        return getDataTable(list);
    }

    /**
     * 新增出库单（草稿）。
     *
     * @param doc 单据（含行项）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:add')")
    @Log(title = "出库单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpStockOut doc)
    {
        return success(stockOutService.insertStockOut(doc));
    }

    /**
     * 编辑出库单（仅草稿）。
     *
     * @param doc 单据
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:edit')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpStockOut doc)
    {
        return success(stockOutService.updateStockOut(doc));
    }

    /**
     * 批量删除出库单（仅草稿）。
     *
     * @param ids 单据ID数组
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:remove')")
    @Log(title = "出库单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids:[A-Za-z0-9,]+}")
    public AjaxResult remove(@PathVariable("ids") String[] ids)
    {
        return toAjax(stockOutService.deleteStockOutByIds(ids));
    }

    /**
     * 提交（草稿 → 待审核）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:submit')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/submit/{id:[A-Za-z0-9]+}")
    public AjaxResult submit(@PathVariable("id") String id)
    {
        return success(stockOutService.submitStockOut(id));
    }

    /**
     * 审核（待审核 → 已审核）并过账；库存不足时整体拒绝（结存、状态、流水都不变）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:approve')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/approve/{id:[A-Za-z0-9]+}")
    public AjaxResult approve(@PathVariable("id") String id)
    {
        return success(stockOutService.approveStockOut(id, ErpPostingOptions.defaults()));
    }

    /**
     * 驳回（待审核 → 草稿；原因必填）。权限点与审核共用 {@code approve}。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:approve')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/reject/{id:[A-Za-z0-9]+}")
    public AjaxResult reject(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockOutService.rejectStockOut(id, reason));
    }

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:void')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/void/{id:[A-Za-z0-9]+}")
    public AjaxResult voidDoc(@PathVariable("id") String id,
                              @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockOutService.voidStockOut(id, reason));
    }

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲，不受负库存拦截）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:unapprove')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/unapprove/{id:[A-Za-z0-9]+}")
    public AjaxResult unapprove(@PathVariable("id") String id,
                                @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockOutService.unapproveStockOut(id, reason));
    }

    /**
     * 通用状态动作入口（动作码分发；{@code complete} 被显式拒绝）。
     *
     * @param body 动作请求（id / action / reason）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:out-order:status')")
    @Log(title = "出库单", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody StatusAction body)
    {
        if (body == null || body.getId() == null)
        {
            throw new ServiceException("单据ID不能为空");
        }
        String action = body.getAction() == null ? null : body.getAction().trim().toLowerCase();
        if (ErpDocAction.SUBMIT.getActionCode().equals(action))
        {
            return success(stockOutService.submitStockOut(body.getId()));
        }
        if (ErpDocAction.APPROVE.getActionCode().equals(action))
        {
            return success(stockOutService.approveStockOut(body.getId(), ErpPostingOptions.defaults()));
        }
        if (ErpDocAction.REJECT.getActionCode().equals(action))
        {
            return success(stockOutService.rejectStockOut(body.getId(), body.getReason()));
        }
        if (ErpDocAction.VOID.getActionCode().equals(action))
        {
            return success(stockOutService.voidStockOut(body.getId(), body.getReason()));
        }
        if (ErpDocAction.UNAPPROVE.getActionCode().equals(action))
        {
            return success(stockOutService.unapproveStockOut(body.getId(), body.getReason()));
        }
        if (ErpDocAction.COMPLETE.getActionCode().equals(action))
        {
            throw new ServiceException(ErpDocType.STOCK_OUT.getLabel() + "为库存类单据，不支持「置为已完成」");
        }
        throw new ServiceException("不支持的单据动作：" + body.getAction());
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
     * 导出用行对象（列定义写在这里，不污染实体）。
     */
    public static class ErpStockOutExportRow
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

        /** 仓库。 */
        @Excel(name = "仓库")
        private String warehouse;

        /** 出库类型。 */
        @Excel(name = "出库类型")
        private String outType;

        /** 客户。 */
        @Excel(name = "客户")
        private String customerName;

        /** 金额合计。 */
        @Excel(name = "金额合计")
        private BigDecimal totalAmount;

        /** 过账标记。 */
        @Excel(name = "过账")
        private String posted;

        /**
         * 从单据装配一行。
         *
         * @param doc 单据
         * @return 导出行
         */
        static ErpStockOutExportRow of(ErpStockOut doc)
        {
            ErpStockOutExportRow row = new ErpStockOutExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = ErpDocStatus.labelOf(doc.getStatus());
            row.warehouse = doc.getWarehouseName();
            row.outType = doc.getOutType();
            row.customerName = doc.getCustomerName();
            row.totalAmount = doc.getTotalAmount();
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

        public String getOutType()
        {
            return outType;
        }

        public String getCustomerName()
        {
            return customerName;
        }

        public BigDecimal getTotalAmount()
        {
            return totalAmount;
        }

        public String getPosted()
        {
            return posted;
        }
    }
}
