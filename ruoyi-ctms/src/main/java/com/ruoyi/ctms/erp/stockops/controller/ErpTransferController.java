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
import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;
import com.ruoyi.ctms.erp.stockops.service.IErpTransferService;

/**
 * <p> <b>调拨单控制器</b>（2.0 B4 任务 6.1/6.2；URL 前缀 {@code /stk/transfer}，
 * 权限点前缀 {@code stk:transfer}，均为 captain §9.2 冻结口径）。 </p>
 *
 * <p> <b>两套动作路由都被支持</b>（同一批服务方法，状态机只有一份）： </p>
 * <ul>
 *   <li> {@code POST /stk/transfer/{id}/{action}} —— 前端单据壳的通用动作入口
 *        （{@code src/api/erp/doc.js#docAction}，原因放请求体）； </li>
 *   <li> {@code POST /stk/transfer/{action}/{id}} 与 {@code PUT /stk/transfer/status}
 *        —— 与 T3 的入库/出库单同形态，便于接口脚本逐条断言与权限点逐条核对。 </li>
 * </ul>
 *
 * <p> 权限点动作集为 {@code list/query/add/edit/remove/status/submit/approve/unapprove/void/export/print}，
 * <b>驳回与审核共用 {@code approve}</b>；{@code print} 不在这里开端点（打印走平台打印模块）。 </p>
 *
 * <p> <b>调拨的仓库口径</b>：行项不使用行级仓库，仓库由表头"调出仓 / 调入仓"决定；
 * 两仓相同在保存与审核两处都被服务端拒绝（前端拦截只是体验）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/transfer")
public class ErpTransferController extends BaseController
{
    @Autowired
    private IErpTransferService transferService;

    /**
     * 调拨单列表（关键字 / 状态 / 两仓 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpTransfer query)
    {
        startPage();
        List<ErpTransfer> list = transferService.selectTransferList(query);
        return getDataTable(list);
    }

    /**
     * 导出调拨单（与列表同一处数据范围与筛选）。
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:export')")
    @Log(title = "调拨单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpTransfer query)
    {
        ExcelUtil<ErpTransferExportRow> util = new ExcelUtil<>(ErpTransferExportRow.class);
        util.exportExcel(response, exportRows(query), "调拨单");
    }

    /**
     * 装配导出行（与列表同一处服务调用，保证范围与筛选一致）。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpTransferExportRow> exportRows(ErpTransfer query)
    {
        List<ErpTransfer> list = transferService.selectTransferList(query);
        List<ErpTransferExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpTransfer doc : list)
            {
                if (doc != null)
                {
                    rows.add(ErpTransferExportRow.of(doc));
                }
            }
        }
        return rows;
    }

    /**
     * 调拨单详情（含行项）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(transferService.selectTransferDetail(id));
    }

    /**
     * 变更历史（分页；按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}/change-logs")
    public TableDataInfo changeLogs(@PathVariable("id") String id)
    {
        startPage();
        List<CtmsChangeLog> list = transferService.selectChangeLogList(id);
        return getDataTable(list);
    }

    /**
     * 新增调拨单（草稿；单号服务端取）。
     *
     * @param doc 单据（含行项）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:add')")
    @Log(title = "调拨单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpTransfer doc)
    {
        return success(transferService.insertTransfer(doc));
    }

    /**
     * 编辑调拨单（仅草稿；行项全量替换；两仓相同被拒）。
     *
     * @param doc 单据
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:edit')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpTransfer doc)
    {
        return success(transferService.updateTransfer(doc));
    }

    /**
     * 批量删除调拨单（仅草稿）。
     *
     * @param ids 单据ID数组
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:remove')")
    @Log(title = "调拨单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids:[A-Za-z0-9,]+}")
    public AjaxResult remove(@PathVariable("ids") String[] ids)
    {
        return toAjax(transferService.deleteTransferByIds(ids));
    }

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:submit')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/submit/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/submit" })
    public AjaxResult submit(@PathVariable("id") String id)
    {
        return success(transferService.submitTransfer(id));
    }

    /**
     * 审核（待审核 → 已审核）并在同一事务内两阶段过账；已过账时幂等返回。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:approve')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/approve/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/approve" })
    public AjaxResult approve(@PathVariable("id") String id)
    {
        return success(transferService.approveTransfer(id));
    }

    /**
     * 驳回（待审核 → 草稿；原因必填）。权限点与审核共用 {@code approve}。
     *
     * @param id     单据ID
     * @param body   动作请求（前端把原因放请求体）
     * @param reason URL 参数形态（与 T3 的入库/出库单一致）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:approve')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/reject/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/reject" })
    public AjaxResult reject(@PathVariable("id") String id,
                             @RequestBody(required = false) StatusAction body,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        return success(transferService.rejectTransfer(id, resolveReason(body, reason)));
    }

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param body   动作请求
     * @param reason URL 参数形态
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:void')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/void/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/void" })
    public AjaxResult voidDoc(@PathVariable("id") String id,
                              @RequestBody(required = false) StatusAction body,
                              @RequestParam(value = "reason", required = false) String reason)
    {
        return success(transferService.voidTransfer(id, resolveReason(body, reason)));
    }

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲两条流水）。
     *
     * @param id     单据ID
     * @param body   动作请求
     * @param reason URL 参数形态
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:unapprove')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
    @PostMapping(value = { "/unapprove/{id:[A-Za-z0-9]+}", "/{id:[A-Za-z0-9]+}/unapprove" })
    public AjaxResult unapprove(@PathVariable("id") String id,
                                @RequestBody(required = false) StatusAction body,
                                @RequestParam(value = "reason", required = false) String reason)
    {
        return success(transferService.unapproveTransfer(id, resolveReason(body, reason)));
    }

    /**
     * 通用状态动作入口（单据壳用）：{@code action} 取 {@code ErpDocAction} 的动作码；
     * {@code complete} 由状态机显式拒绝（库存类单据不存在"已完成"）。
     *
     * @param body 动作请求（id / action / reason）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:transfer:status')")
    @Log(title = "调拨单", businessType = BusinessType.UPDATE)
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
            return success(transferService.submitTransfer(body.getId()));
        }
        if (ErpDocAction.APPROVE.getActionCode().equals(action))
        {
            return success(transferService.approveTransfer(body.getId()));
        }
        if (ErpDocAction.REJECT.getActionCode().equals(action))
        {
            return success(transferService.rejectTransfer(body.getId(), body.getReason()));
        }
        if (ErpDocAction.VOID.getActionCode().equals(action))
        {
            return success(transferService.voidTransfer(body.getId(), body.getReason()));
        }
        if (ErpDocAction.UNAPPROVE.getActionCode().equals(action))
        {
            return success(transferService.unapproveTransfer(body.getId(), body.getReason()));
        }
        if (ErpDocAction.COMPLETE.getActionCode().equals(action))
        {
            throw new ServiceException(ErpDocType.STOCK_TRANSFER.getLabel()
                    + "为库存类单据，不支持「置为已完成」");
        }
        throw new ServiceException("不支持的单据动作：" + body.getAction());
    }

    /**
     * 原因取值：请求体优先（前端契约），其次 URL 参数（T3 形态）。
     *
     * @param body   动作请求
     * @param reason URL 参数
     * @return 原因（可能为空，由状态机报"原因必填"）
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

        /** 动作码（{@code ErpDocAction#getActionCode()}）。 */
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
     * 导出用行对象（列定义即导出契约；实体本身是接口 JSON 契约，不挂表现层注解）。
     */
    public static class ErpTransferExportRow
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

        /** 调出仓。 */
        @Excel(name = "调出仓")
        private String fromWarehouse;

        /** 调入仓。 */
        @Excel(name = "调入仓")
        private String toWarehouse;

        /** 过账标记。 */
        @Excel(name = "过账")
        private String posted;

        /**
         * 从单据装配一行。
         *
         * @param doc 单据
         * @return 导出行
         */
        static ErpTransferExportRow of(ErpTransfer doc)
        {
            ErpTransferExportRow row = new ErpTransferExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = ErpDocStatus.labelOf(doc.getStatus());
            row.fromWarehouse = doc.getFromWarehouseName();
            row.toWarehouse = doc.getToWarehouseName();
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

        public String getFromWarehouse()
        {
            return fromWarehouse;
        }

        public String getToWarehouse()
        {
            return toWarehouse;
        }

        public String getPosted()
        {
            return posted;
        }
    }
}
