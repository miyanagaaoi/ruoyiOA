package com.ruoyi.ctms.erp.procurement.controller;

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
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushResultVo;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchasePushService;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseRequestService;
import com.ruoyi.ctms.service.ICtmsContractService;

/**
 * <p> 采购申请单接口（2.0 B4 任务 3.1~3.6）。 </p>
 *
 * <p> <b>权限点命名已冻结</b>（简报 §9）：{@code pur:request:<动作>}，
 * 动作集 {@code list/query/add/edit/remove/status/submit/approve/unapprove/void/push/export/print}；
 * <b>驳回与审核共用 {@code approve}</b>（状态机里是两个动作，权限上是一件事）。 </p>
 *
 * <p> <b>为什么状态动作做成"一个入口 + 动作码"</b>：状态机白名单只有一处实现
 * （{@code ErpDocStateMachine}）；接口层若为 6 个动作各写一套，会出现
 * "接口暴露了但状态机不允许 / 状态机允许但接口没暴露"的两边漂移。
 * 权限仍在方法级（{@code submit} / {@code approve} / {@code unapprove} / {@code void} / {@code status}）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/erp/pur/request")
public class ErpPurchaseRequestController extends BaseController
{
    @Autowired
    private IErpPurchaseRequestService requestService;

    @Autowired
    private IErpPurchasePushService pushService;

    /** 合同台账服务（只读：任务 3.6 的下拉）。 */
    @Autowired(required = false)
    private ICtmsContractService contractService;

    /* ==================== 查询 ==================== */

    /**
     * 采购申请单列表（状态筛选、默认排除已作废、关键词与日期筛选、数据范围）。
     *
     * @param query 查询条件
     * @return 分页列表（含 {@code remainQtySum} / {@code canPush} / {@code totalAmount} 派生列）
     */
    @PreAuthorize("@ss.hasPermi('pur:request:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpPurchaseRequest query)
    {
        startPage();
        List<ErpPurchaseRequest> list = requestService.selectRequestList(query);
        return getDataTable(list);
    }

    /**
     * <p> 导出采购申请单（与列表<b>同一处取数与同筛选同数据范围</b>）。 </p>
     *
     * <p> <b>响应形态＝Excel 二进制流</b>（xlsx），这是与前端对接的硬约束：前端
     * {@code src/api/erp/doc.js#exportDocs} 走的是 {@code utils/request.js#download}，
     * 它 <b>POST</b> + {@code Content-Type: application/x-www-form-urlencoded} +
     * {@code responseType: 'blob'}，并直接 {@code saveAs(blob)}；若这里返回 JSON（哪怕
     * 带上 {@code .rows}）前端只会把它当错误文本弹提示 —— B3 踩过这个坑（见本文件顶部注释与
     * {@code notes/04a-procurement.md} 的"导出"小节）。因此本端点同时接受
     * <b>GET 与 POST</b>：GET 供脚本/人工复跑，POST 供前端实际调用。 </p>
     *
     * <p> <b>筛选与范围</b>：直接把列表用的 {@code query} 交给与列表相同的服务方法，
     * 数据范围片段由服务里的 {@code ErpDocScope} 生成（本层不另写一套范围判定）。
     * 传了 {@code id} 时按"单张导出"处理并走详情入口 —— 范围外会抛业务码 403（不返回数据）。 </p>
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件（可选 {@code id} 导出单张）
     */
    @PreAuthorize("@ss.hasPermi('pur:request:export')")
    @Log(title = "采购申请单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpPurchaseRequest query)
    {
        ExcelUtil<ErpPurRequestExportRow> util = new ExcelUtil<>(ErpPurRequestExportRow.class);
        util.exportExcel(response, exportRows(query), "采购申请单");
    }

    /**
     * 装配导出行（<b>一行 = 一个行项</b>；没有行项的单据也占一行，保证导出不漏单）。
     *
     * <p> 取数入口与列表完全相同：{@link IErpPurchaseRequestService#selectRequestList}
     * （筛选 + {@code ErpDocScope} 数据范围都在服务里），因此"导出即当前筛选"。
     * 传了 {@code id} 时改用详情入口（范围外抛 403）。 </p>
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpPurRequestExportRow> exportRows(ErpPurchaseRequest query)
    {
        List<ErpPurchaseRequest> docs;
        if (query != null && query.getId() != null && !query.getId().trim().isEmpty())
        {
            // 单张导出：详情入口带"存在性 + 四档数据范围"，范围外是业务码 403（不是 500、也不返回数据）
            docs = new ArrayList<>(1);
            docs.add(requestService.selectRequestDetail(query.getId().trim()));
        }
        else
        {
            docs = requestService.selectRequestList(query);
        }
        List<ErpPurRequestExportRow> rows = new ArrayList<>();
        if (docs != null)
        {
            for (ErpPurchaseRequest doc : docs)
            {
                rows.addAll(ErpPurRequestExportRow.of(doc));
            }
        }
        return rows;
    }

    /**
     * <p> 导出用行对象：{@code ExcelUtil} 按 {@code @Excel} 注解取列，所以"导出列"显式写在这里，
     * 而不是给实体挂表现层注解（实体同时是接口 JSON 契约）。 </p>
     *
     * <p> 一行 = 一个行项；行项列为空时该单据仍占一行（表头列照常输出）。 </p>
     */
    public static class ErpPurRequestExportRow
    {
        /* ---------- 表头业务字段 ---------- */

        /** 单号。 */
        @Excel(name = "单号")
        private String docNo;

        /** 单据日期。 */
        @Excel(name = "单据日期", dateFormat = "yyyy-MM-dd")
        private Date docDate;

        /** 状态（中文名）。 */
        @Excel(name = "状态")
        private String status;

        /** 归属部门。 */
        @Excel(name = "归属部门")
        private String deptName;

        /** 需求部门ID（B3 侧只存 id，名称未反查，列名标注 ID 以免误读）。 */
        @Excel(name = "需求部门(ID)")
        private String requestDeptId;

        /** 需求日期。 */
        @Excel(name = "需求日期", dateFormat = "yyyy-MM-dd")
        private Date needDate;

        /** 建议供应商ID（仅提示用，未反查名称）。 */
        @Excel(name = "建议供应商(ID)")
        private String suggestSupplierId;

        /** 合同编号快照。 */
        @Excel(name = "合同编号")
        private String contractNo;

        /** 用途说明。 */
        @Excel(name = "用途")
        private String purpose;

        /** 剩余可下推量合计（列表派生列，导出同值）。 */
        @Excel(name = "剩余可下推量合计")
        private java.math.BigDecimal remainQtySum;

        /** 单据金额合计（本表无该列，由行项"先舍入再汇总"现算，与列表同值）。 */
        @Excel(name = "金额合计")
        private java.math.BigDecimal totalAmount;

        /** 备注。 */
        @Excel(name = "备注")
        private String remark;

        /** 创建人。 */
        @Excel(name = "创建人")
        private String createBy;

        /* ---------- 行项关键列 ---------- */

        /** 行号。 */
        @Excel(name = "行号")
        private Integer seq;

        /** 物料编码快照。 */
        @Excel(name = "物料编码")
        private String productCode;

        /** 物料名称快照。 */
        @Excel(name = "物料名称")
        private String productName;

        /** 规格快照。 */
        @Excel(name = "规格")
        private String spec;

        /** 单位名快照。 */
        @Excel(name = "单位")
        private String uomName;

        /** 数量。 */
        @Excel(name = "数量")
        private java.math.BigDecimal qty;

        /** 单价。 */
        @Excel(name = "单价")
        private java.math.BigDecimal unitPrice;

        /** 行金额（先 HALF_UP 到 2 位；与列表同一实现点 {@code ErpAmounts.lineAmount}）。 */
        @Excel(name = "行金额")
        private java.math.BigDecimal amount;

        /** 已下推数量。 */
        @Excel(name = "已下推数量")
        private java.math.BigDecimal orderedQty;

        /** 剩余可下推量。 */
        @Excel(name = "剩余可下推量")
        private java.math.BigDecimal remainQty;

        /**
         * 把一张单据摊成"一行一个行项"（无行项时输出一行纯表头字段）。
         *
         * @param doc 单据
         * @return 导出行集合（非 null）
         */
        static List<ErpPurRequestExportRow> of(ErpPurchaseRequest doc)
        {
            List<ErpPurRequestExportRow> rows = new ArrayList<>();
            if (doc == null)
            {
                return rows;
            }
            List<ErpPurchaseRequestItem> items = doc.getItems();
            if (items == null || items.isEmpty())
            {
                rows.add(headerOf(doc));
                return rows;
            }
            for (ErpPurchaseRequestItem item : items)
            {
                ErpPurRequestExportRow row = headerOf(doc);
                row.seq = item.getSeq();
                row.productCode = item.getProductCode();
                row.productName = item.getProductName();
                row.spec = item.getSpec();
                row.uomName = item.getUomName();
                row.qty = item.getQty();
                row.unitPrice = item.getUnitPrice();
                // 行金额：与列表同一实现点（qty × unitPrice 先 HALF_UP 到 2 位）
                row.amount = ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice());
                row.orderedQty = item.getOrderedQty();
                row.remainQty = ErpPurRules.remainingQtyOf(item);
                rows.add(row);
            }
            return rows;
        }

        /**
         * 表头字段部分（行项列为空）。
         *
         * @param doc 单据
         * @return 导出行
         */
        private static ErpPurRequestExportRow headerOf(ErpPurchaseRequest doc)
        {
            ErpPurRequestExportRow row = new ErpPurRequestExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = ErpDocStatus.labelOf(doc.getStatus());
            row.deptName = doc.getDeptName();
            row.requestDeptId = doc.getRequestDeptId();
            row.needDate = doc.getNeedDate();
            row.suggestSupplierId = doc.getSuggestSupplierId();
            row.contractNo = doc.getContractNo();
            row.purpose = doc.getPurpose();
            row.remainQtySum = doc.getRemainQtySum();
            // 金额合计：列表与详情都是服务层按"先舍入再汇总"算好的同一个值（本表没有该列）
            row.totalAmount = doc.getTotalAmount() == null
                    ? ErpPurRules.totalAmountOf(doc.getItems()) : doc.getTotalAmount();
            row.remark = doc.getRemark();
            row.createBy = doc.getCreateBy();
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

        public String getDeptName()
        {
            return deptName;
        }

        public String getRequestDeptId()
        {
            return requestDeptId;
        }

        public Date getNeedDate()
        {
            return needDate;
        }

        public String getSuggestSupplierId()
        {
            return suggestSupplierId;
        }

        public String getContractNo()
        {
            return contractNo;
        }

        public String getPurpose()
        {
            return purpose;
        }

        public java.math.BigDecimal getRemainQtySum()
        {
            return remainQtySum;
        }

        public java.math.BigDecimal getTotalAmount()
        {
            return totalAmount;
        }

        public String getRemark()
        {
            return remark;
        }

        public String getCreateBy()
        {
            return createBy;
        }

        public Integer getSeq()
        {
            return seq;
        }

        public String getProductCode()
        {
            return productCode;
        }

        public String getProductName()
        {
            return productName;
        }

        public String getSpec()
        {
            return spec;
        }

        public String getUomName()
        {
            return uomName;
        }

        public java.math.BigDecimal getQty()
        {
            return qty;
        }

        public java.math.BigDecimal getUnitPrice()
        {
            return unitPrice;
        }

        public java.math.BigDecimal getAmount()
        {
            return amount;
        }

        public java.math.BigDecimal getOrderedQty()
        {
            return orderedQty;
        }

        public java.math.BigDecimal getRemainQty()
        {
            return remainQty;
        }
    }

    /**
     * 采购申请单详情（含行项与变更历史）。
     *
     * @param id 主键
     * @return 单据
     */
    @PreAuthorize("@ss.hasPermi('pur:request:query')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable("id") String id)
    {
        return success(requestService.selectRequestDetail(id));
    }

    /**
     * 行项列表（前端行项表单独刷新）。
     *
     * @param docId 表头ID
     * @return 行项集合
     */
    @PreAuthorize("@ss.hasPermi('pur:request:query')")
    @GetMapping("/{docId}/items")
    public AjaxResult items(@PathVariable("docId") String docId)
    {
        return success(requestService.selectItemsByDocId(docId));
    }

    /**
     * 变更历史（详情页时间线）。
     *
     * @param docId 单据ID
     * @return 日志集合
     */
    @PreAuthorize("@ss.hasPermi('pur:request:query')")
    @GetMapping("/{docId}/change-logs")
    public AjaxResult changeLogs(@PathVariable("docId") String docId)
    {
        return success(requestService.selectRequestChangeLogs(docId));
    }

    /**
     * 采购方向的可用合同下拉（任务 3.6；仅登录可用）。
     *
     * <p> 只返回"未停用且乙方为供应商"的合同：方向判定与保存时同一口径
     * （{@code ErpPurRules.checkContractDirection}）。下拉只是选择便利，
     * 真正的强制校验仍在保存/下推路径上。 </p>
     *
     * @return 合同集合（含数据范围；合同列表服务自带 200 条上限策略时按它的口径）
     */
    @PreAuthorize("@ss.hasPermi('pur:request:query')")
    @GetMapping("/contract-options")
    public AjaxResult contractOptions()
    {
        List<CtmsContract> options = new ArrayList<>();
        if (contractService == null)
        {
            return success(options);
        }
        // 一次取一批未停用合同，再在服务端按**与保存时同一口径**过滤方向：
        // 这样两处口径天然一致，也不依赖 Mapper 是否实现"某列为空/某列非空"的筛选项
        List<CtmsContract> rows = contractService.selectContractList(new CtmsContract());
        if (rows == null)
        {
            return success(options);
        }
        for (CtmsContract row : rows)
        {
            if (row == null || ErpPurRules.isBlank(row.getSupplierId()))
            {
                continue;
            }
            try
            {
                ErpPurRules.checkContractDirection(row.getSupplierId(), row.getCustomerId(),
                        ErpPurRules.DIRECTION_PURCHASE);
                options.add(row);
            }
            catch (com.ruoyi.common.exception.ServiceException ignored)
            {
                // 方向不符的合同不进下拉（这里是"筛掉"而不是"报错"）
            }
        }
        return success(options);
    }

    /* ==================== 新增 / 编辑 / 删除 ==================== */

    /**
     * 新增采购申请单（草稿）。
     *
     * @param request 单据（行项走 {@code items}）
     * @return 主键
     */
    @PreAuthorize("@ss.hasPermi('pur:request:add')")
    @Log(title = "采购申请单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpPurchaseRequest request)
    {
        return success(requestService.insertRequest(request));
    }

    /**
     * 编辑采购申请单（仅草稿可编辑；行项全量替换）。
     *
     * @param request 单据
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:edit')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpPurchaseRequest request)
    {
        requestService.updateRequest(request);
        return success();
    }

    /**
     * 删除采购申请单（仅草稿，仅用于回滚演练与夹具清理；业务侧走作废）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:remove')")
    @Log(title = "采购申请单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") String id)
    {
        requestService.deleteRequest(id);
        return success();
    }

    /* ==================== 行项独立维护 ==================== */

    /**
     * 新增一行行项。
     *
     * @param docId 表头ID
     * @param item  行项
     * @return 行项主键
     */
    @PreAuthorize("@ss.hasPermi('pur:request:edit')")
    @Log(title = "采购申请单行项", businessType = BusinessType.INSERT)
    @PostMapping("/{docId}/items")
    public AjaxResult addItem(@PathVariable("docId") String docId, @RequestBody ErpPurchaseRequestItem item)
    {
        return success(itemWriter().insertItem(docId, item));
    }

    /**
     * 修改一行行项。
     *
     * @param docId 表头ID
     * @param item  行项
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:edit')")
    @Log(title = "采购申请单行项", businessType = BusinessType.UPDATE)
    @PutMapping("/{docId}/items")
    public AjaxResult editItem(@PathVariable("docId") String docId, @RequestBody ErpPurchaseRequestItem item)
    {
        itemWriter().updateItem(docId, item);
        return success();
    }

    /**
     * 删除一行行项。
     *
     * @param docId  表头ID
     * @param itemId 行项主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:edit')")
    @Log(title = "采购申请单行项", businessType = BusinessType.DELETE)
    @DeleteMapping("/{docId}/items/{itemId}")
    public AjaxResult removeItem(@PathVariable("docId") String docId, @PathVariable("itemId") String itemId)
    {
        itemWriter().deleteItem(docId, itemId);
        return success();
    }

    /**
     * 行项维护入口（服务实现同时实现了 {@code ItemWriter}；用一次类型判定代替再注入一个 Bean）。
     *
     * @return 行项维护入口
     */
    protected IErpPurchaseRequestService.ItemWriter itemWriter()
    {
        if (requestService instanceof IErpPurchaseRequestService.ItemWriter)
        {
            return (IErpPurchaseRequestService.ItemWriter) requestService;
        }
        throw new com.ruoyi.common.exception.ServiceException("行项维护未实现");
    }

    /* ==================== 状态流转 ==================== */

    /**
     * 提交（草稿 → 待审核）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:submit')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/submit")
    public AjaxResult submit(@PathVariable("id") String id)
    {
        requestService.changeStatus(id, "submit", null);
        return success();
    }

    /**
     * 审核（待审核 → 已审核）。驳回共用本权限点，用 {@code action=reject} 区分。
     *
     * @param id     主键
     * @param action 动作码（{@code approve} / {@code reject}；缺省 {@code approve}）
     * @param reason 原因（驳回必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:approve')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/approve")
    public AjaxResult approve(@PathVariable("id") String id,
                             @RequestParam(value = "action", required = false) String action,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        requestService.changeStatus(id, action == null ? "approve" : action, reason);
        return success();
    }

    /**
     * 反审核（已审核 / 已完成 → 待审核 / 草稿）；存在未作废下游时被拒。
     *
     * @param id     主键
     * @param reason 原因（必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:unapprove')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/unapprove")
    public AjaxResult unapprove(@PathVariable("id") String id,
                               @RequestParam(value = "reason", required = false) String reason)
    {
        requestService.changeStatus(id, "unapprove", reason);
        return success();
    }

    /**
     * 作废（草稿 / 待审核 → 已作废）。
     *
     * @param id     主键
     * @param reason 原因（必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:void')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/void")
    public AjaxResult voidDoc(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        requestService.changeStatus(id, "void", reason);
        return success();
    }

    /**
     * 置为已完成（已审核 → 已完成；申请单不是库存类单据，提供该动作）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:request:status')")
    @Log(title = "采购申请单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/complete")
    public AjaxResult complete(@PathVariable("id") String id)
    {
        requestService.changeStatus(id, "complete", null);
        return success();
    }

    /**
     * 归零判定（幂等；重复调用不产生第二条留痕）。
     *
     * @param id 主键
     * @return 本次是否真的发生了状态变更
     */
    @PreAuthorize("@ss.hasPermi('pur:request:status')")
    @PutMapping("/{id}/check-auto-complete")
    public AjaxResult checkAutoComplete(@PathVariable("id") String id)
    {
        return success(Boolean.valueOf(requestService.checkAutoComplete(id)));
    }

    /* ==================== 下推 ==================== */

    /**
     * <p> 采购申请 → 采购单下推（任务 3.3 / 3.4）。 </p>
     *
     * <p> {@code lines} 为空 / 不传表示按<b>全部行项</b>（各按剩余量全推）；{@code supplierId} 必填
     * （采购单的供应商是必填引用，下推时就校验，避免生成一张存不下去的草稿）。 </p>
     *
     * @param id         申请单ID
     * @param supplierId 供应商ID
     * @param remark     备注（可空）
     * @param lines      下推行（可空）
     * @return 下推结果（含草稿采购单、来源信息、{@code autoCompleted} 与提示文案）
     */
    @PreAuthorize("@ss.hasPermi('pur:request:push')")
    @Log(title = "采购申请单下推", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/push/purchase-order")
    public AjaxResult pushToPurchaseOrder(@PathVariable("id") String id,
                                         @RequestParam(value = "supplierId", required = false) String supplierId,
                                         @RequestParam(value = "remark", required = false) String remark,
                                         @RequestBody(required = false) List<ErpPushLine> lines)
    {
        ErpPushResultVo result = pushService.pushToPurchaseOrder(id, lines, supplierId, remark);
        // 返回值带 sourceStatus / remainQtySum：前端据此刷新申请单状态与下推按钮显隐
        return success(result);
    }
}
