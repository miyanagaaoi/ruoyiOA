package com.ruoyi.ctms.erp.posting.controller;

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
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.service.IErpStockInService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;

/**
 * <p> <b>入库单控制器</b>（2.0 B4 任务 5.1；URL 前缀按 PRD §9.4 的单数资源口径
 * {@code /stk/in-order}）。 </p>
 *
 * <p> <b>权限点</b>：由 captain §9.2 冻结，动作集
 * {@code list/query/add/edit/remove/status/submit/approve/unapprove/void/export/print}，
 * <b>驳回与审核共用 {@code approve}</b>（驳回是审核动作的一种结论）。
 * {@code print} 不在这里开端点：打印由平台打印模块按内置版式完成（B2 已交付）。 </p>
 *
 * <p> <b>为什么还留一个 {@code PUT /status}</b>：前端单据壳的"操作列"只需要一个入口
 * （动作码 + 原因），把分发放在 Controller 可以避免服务接口膨胀成 6 个同签名方法；
 * 同时逐动作端点也保留，便于接口脚本与权限点逐条断言。两者调用的是同一批服务方法，
 * 状态机校验也只有一份（{@code ErpDocStateMachine}）。 </p>
 *
 * <p> <b>数据范围</b>：列表与导出都走服务层的同一处范围判定；详情/动作/删除先过
 * {@code IErpDocObjectAccess}，范围外返回业务码 403。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/in-order")
public class ErpStockInController extends BaseController
{
    @Autowired
    private IErpStockInService stockInService;

    /**
     * 入库单列表（关键字 / 入库类型 / 仓库 / 状态 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpStockIn query)
    {
        startPage();
        List<ErpStockIn> list = stockInService.selectStockInList(query);
        return getDataTable(list);
    }

    /**
     * 导出入库单（与列表同一处数据范围与筛选；列定义见 {@link ErpStockInExportRow}）。
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:export')")
    @Log(title = "入库单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpStockIn query)
    {
        ExcelUtil<ErpStockInExportRow> util = new ExcelUtil<>(ErpStockInExportRow.class);
        util.exportExcel(response, exportRows(query), "入库单");
    }

    /**
     * 装配导出行（与列表同一处服务调用，保证范围与筛选一致）。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpStockInExportRow> exportRows(ErpStockIn query)
    {
        List<ErpStockIn> list = stockInService.selectStockInList(query);
        List<ErpStockInExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpStockIn doc : list)
            {
                if (doc != null)
                {
                    rows.add(ErpStockInExportRow.of(doc));
                }
            }
        }
        return rows;
    }

    /**
     * 入库单详情（含行项与变更历史入口）。
     *
     * @param id 单据ID
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(stockInService.selectStockInDetail(id));
    }

    /**
     * 变更历史（分页；按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 分页结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:query')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}/change-logs")
    public TableDataInfo changeLogs(@PathVariable("id") String id)
    {
        startPage();
        List<CtmsChangeLog> list = stockInService.selectChangeLogList(id);
        return getDataTable(list);
    }

    /**
     * 新增入库单（草稿；单号由服务端取）。
     *
     * @param doc 单据（含行项）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:add')")
    @Log(title = "入库单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpStockIn doc)
    {
        return success(stockInService.insertStockIn(doc));
    }

    /**
     * 编辑入库单（仅草稿；行项全量替换）。
     *
     * @param doc 单据
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:edit')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpStockIn doc)
    {
        return success(stockInService.updateStockIn(doc));
    }

    /**
     * 批量删除入库单（仅草稿）。
     *
     * @param ids 单据ID数组
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:remove')")
    @Log(title = "入库单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids:[A-Za-z0-9,]+}")
    public AjaxResult remove(@PathVariable("ids") String[] ids)
    {
        return toAjax(stockInService.deleteStockInByIds(ids));
    }

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:submit')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/submit/{id:[A-Za-z0-9]+}")
    public AjaxResult submit(@PathVariable("id") String id)
    {
        return success(stockInService.submitStockIn(id));
    }

    /**
     * 审核（待审核 → 已审核）并<b>在同一事务内过账</b>；已过账时幂等返回。
     *
     * @param id 单据ID
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:approve')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/approve/{id:[A-Za-z0-9]+}")
    public AjaxResult approve(@PathVariable("id") String id)
    {
        return success(stockInService.approveStockIn(id, ErpPostingOptions.defaults()));
    }

    /**
     * 驳回（待审核 → 草稿；原因必填）。权限点与审核共用 {@code approve}。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:approve')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/reject/{id:[A-Za-z0-9]+}")
    public AjaxResult reject(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockInService.rejectStockIn(id, reason));
    }

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:void')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/void/{id:[A-Za-z0-9]+}")
    public AjaxResult voidDoc(@PathVariable("id") String id,
                              @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockInService.voidStockIn(id, reason));
    }

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲并清除过账标记）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:unapprove')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/unapprove/{id:[A-Za-z0-9]+}")
    public AjaxResult unapprove(@PathVariable("id") String id,
                                @RequestParam(value = "reason", required = false) String reason)
    {
        return success(stockInService.unapproveStockIn(id, reason));
    }

    /**
     * <p> 通用状态动作入口（单据壳用）：{@code action} 取 {@code ErpDocAction} 的动作码。 </p>
     *
     * <p> {action} 的取值与端点一一对应：{@code submit} / {@code approve} / {@code reject} /
     * {@code void} / {@code unapprove}；{@code complete} 由状态机显式拒绝
     * （库存类单据不存在"已完成"）。<b>不接受</b>任何"跳过负库存校验"的请求参数
     * —— 豁免只能由服务端内部路径（盘亏单过账）触发。 </p>
     *
     * @param body 动作请求（id / action / reason）
     * @return 成功结果
     */
    @PreAuthorize("@ss.hasPermi('stk:in-order:status')")
    @Log(title = "入库单", businessType = BusinessType.UPDATE)
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
            return success(stockInService.submitStockIn(body.getId()));
        }
        if (ErpDocAction.APPROVE.getActionCode().equals(action))
        {
            return success(stockInService.approveStockIn(body.getId(), ErpPostingOptions.defaults()));
        }
        if (ErpDocAction.REJECT.getActionCode().equals(action))
        {
            return success(stockInService.rejectStockIn(body.getId(), body.getReason()));
        }
        if (ErpDocAction.VOID.getActionCode().equals(action))
        {
            return success(stockInService.voidStockIn(body.getId(), body.getReason()));
        }
        if (ErpDocAction.UNAPPROVE.getActionCode().equals(action))
        {
            return success(stockInService.unapproveStockIn(body.getId(), body.getReason()));
        }
        if (ErpDocAction.COMPLETE.getActionCode().equals(action))
        {
            // 库存类单据不存在"已完成"（状态机同款口径，两处文案一致）
            throw new ServiceException(com.ruoyi.ctms.erp.base.ErpDocType.STOCK_IN.getLabel()
                    + "为库存类单据，不支持「置为已完成」");
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
     * <p> 导出用行对象：{@code ExcelUtil} 按 {@code @Excel} 注解取列，
     * 所以把"导出列"显式写在这里，而不是给实体挂表现层注解（实体同时是接口 JSON 契约）。 </p>
     */
    public static class ErpStockInExportRow
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

        /** 入库类型。 */
        @Excel(name = "入库类型")
        private String inType;

        /** 金额合计。 */
        @Excel(name = "金额合计")
        private java.math.BigDecimal totalAmount;

        /** 过账标记。 */
        @Excel(name = "过账")
        private String posted;

        /**
         * 从单据装配一行。
         *
         * @param doc 单据
         * @return 导出行
         */
        static ErpStockInExportRow of(ErpStockIn doc)
        {
            ErpStockInExportRow row = new ErpStockInExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = com.ruoyi.ctms.erp.base.ErpDocStatus.labelOf(doc.getStatus());
            row.warehouse = doc.getWarehouseName();
            row.inType = doc.getInType();
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

        public String getInType()
        {
            return inType;
        }

        public java.math.BigDecimal getTotalAmount()
        {
            return totalAmount;
        }

        public String getPosted()
        {
            return posted;
        }
    }
}
