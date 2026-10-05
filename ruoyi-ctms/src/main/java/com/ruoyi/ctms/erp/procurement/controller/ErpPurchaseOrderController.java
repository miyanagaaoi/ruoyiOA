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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseOrderService;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchasePushService;
import com.ruoyi.ctms.service.ICtmsContractService;

/**
 * <p> 采购单接口（2.0 B4 任务 3.1、3.2、3.5、3.6）。 </p>
 *
 * <p> 权限点（简报 §9 冻结）：{@code pur:order:<动作>}，动作集与采购申请单一致。
 * 采购单 → 入库单的下推在 {@code POST /erp/pur/order/{id}/push/stock-in}（任务 3.5）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/erp/pur/order")
public class ErpPurchaseOrderController extends BaseController
{
    @Autowired
    private IErpPurchaseOrderService orderService;

    /** 下推服务（采购单 → 入库单；任务 3.5）。 */
    @Autowired
    private IErpPurchasePushService pushService;

    /** 合同台账服务（只读：任务 3.6 的下拉）。 */
    @Autowired(required = false)
    private ICtmsContractService contractService;

    /* ==================== 查询 ==================== */

    /**
     * 采购单列表（状态筛选、默认排除已作废、关键词与日期筛选、供应商筛选、数据范围）。
     *
     * @param query 查询条件
     * @return 分页列表（含 {@code remainQtySum} / {@code canReceive} 派生列）
     */
    @PreAuthorize("@ss.hasPermi('pur:order:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpPurchaseOrder query)
    {
        startPage();
        List<ErpPurchaseOrder> list = orderService.selectOrderList(query);
        return getDataTable(list);
    }

    /**
     * <p> 导出采购单（与列表<b>同一处取数与同筛选同数据范围</b>）。 </p>
     *
     * <p> <b>响应形态＝Excel 二进制流</b>（xlsx）：与前端 {@code utils/request.js#download}
     * 的约定一致（前端 <b>POST</b> + form-urlencoded + {@code responseType:'blob'} 并
     * {@code saveAs(blob)}；返回 JSON 会被当成错误文本）。因此本端点同时接受
     * <b>GET 与 POST</b>。口径与采购申请单导出完全同构（见 {@code ErpPurchaseRequestController}）。 </p>
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件（可选 {@code id} 导出单张；范围外 403）
     */
    @PreAuthorize("@ss.hasPermi('pur:order:export')")
    @Log(title = "采购单", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpPurchaseOrder query)
    {
        ExcelUtil<ErpPurOrderExportRow> util = new ExcelUtil<>(ErpPurOrderExportRow.class);
        util.exportExcel(response, exportRows(query), "采购单");
    }

    /**
     * 装配导出行（一行 = 一个行项；无行项的单据也占一行）。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpPurOrderExportRow> exportRows(ErpPurchaseOrder query)
    {
        List<ErpPurchaseOrder> docs;
        if (query != null && query.getId() != null && !query.getId().trim().isEmpty())
        {
            docs = new ArrayList<>(1);
            docs.add(orderService.selectOrderDetail(query.getId().trim()));
        }
        else
        {
            docs = orderService.selectOrderList(query);
        }
        List<ErpPurOrderExportRow> rows = new ArrayList<>();
        if (docs != null)
        {
            for (ErpPurchaseOrder doc : docs)
            {
                rows.addAll(ErpPurOrderExportRow.of(doc));
            }
        }
        return rows;
    }

    /**
     * 导出用行对象（采购单版；列定义即导出契约，前端不自造表头）。
     */
    public static class ErpPurOrderExportRow
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

        /** 供应商（名称快照）。 */
        @Excel(name = "供应商")
        private String supplierName;

        /** 采购部门ID（仅存 id，列名标注）。 */
        @Excel(name = "采购部门(ID)")
        private String purchaseDeptId;

        /** 预计到货日期。 */
        @Excel(name = "预计到货日期", dateFormat = "yyyy-MM-dd")
        private Date expectedArrivalDate;

        /** 结算方式。 */
        @Excel(name = "结算方式")
        private String settleType;

        /** 币种。 */
        @Excel(name = "币种")
        private String currency;

        /** 默认收货仓库（名称快照）。 */
        @Excel(name = "默认收货仓库")
        private String receiptWarehouseName;

        /** 合同编号快照。 */
        @Excel(name = "合同编号")
        private String contractNo;

        /** 来源申请单号。 */
        @Excel(name = "来源单号")
        private String sourceDocNo;

        /** 单据金额合计（落库列；由行项"先舍入再汇总"写入，与列表同值）。 */
        @Excel(name = "金额合计")
        private java.math.BigDecimal totalAmount;

        /** 剩余可入库量合计（列表派生列，导出同值）。 */
        @Excel(name = "剩余可入库量合计")
        private java.math.BigDecimal remainQtySum;

        /** 备注。 */
        @Excel(name = "备注")
        private String remark;

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

        /** 行金额（先 HALF_UP 到 2 位；与列表同一实现点）。 */
        @Excel(name = "行金额")
        private java.math.BigDecimal amount;

        /** 已入库数量。 */
        @Excel(name = "已入库数量")
        private java.math.BigDecimal receivedQty;

        /** 剩余可入库量。 */
        @Excel(name = "剩余可入库量")
        private java.math.BigDecimal remainQty;

        /**
         * 把一张采购单摊成"一行一个行项"（无行项时输出一行纯表头字段）。
         *
         * @param doc 采购单
         * @return 导出行集合（非 null）
         */
        static List<ErpPurOrderExportRow> of(ErpPurchaseOrder doc)
        {
            List<ErpPurOrderExportRow> rows = new ArrayList<>();
            if (doc == null)
            {
                return rows;
            }
            List<ErpPurchaseOrderItem> items = doc.getItems();
            if (items == null || items.isEmpty())
            {
                rows.add(headerOf(doc));
                return rows;
            }
            for (ErpPurchaseOrderItem item : items)
            {
                ErpPurOrderExportRow row = headerOf(doc);
                row.seq = item.getSeq();
                row.productCode = item.getProductCode();
                row.productName = item.getProductName();
                row.spec = item.getSpec();
                row.uomName = item.getUomName();
                row.qty = item.getQty();
                row.unitPrice = item.getUnitPrice();
                row.amount = ErpAmounts.lineAmount(item.getQty(), item.getUnitPrice());
                row.receivedQty = item.getReceivedQty();
                row.remainQty = ErpPurRules.remainingReceiveQtyOf(item);
                rows.add(row);
            }
            return rows;
        }

        /**
         * 表头字段部分（行项列为空）。
         *
         * @param doc 采购单
         * @return 导出行
         */
        private static ErpPurOrderExportRow headerOf(ErpPurchaseOrder doc)
        {
            ErpPurOrderExportRow row = new ErpPurOrderExportRow();
            row.docNo = doc.getDocNo();
            row.docDate = doc.getDocDate();
            row.status = ErpDocStatus.labelOf(doc.getStatus());
            row.supplierName = doc.getSupplierName();
            row.purchaseDeptId = doc.getPurchaseDeptId();
            row.expectedArrivalDate = doc.getExpectedArrivalDate();
            row.settleType = doc.getSettleType();
            row.currency = doc.getCurrency();
            row.receiptWarehouseName = doc.getReceiptWarehouseName();
            row.contractNo = doc.getContractNo();
            row.sourceDocNo = doc.getSourceDocNo();
            row.totalAmount = doc.getTotalAmount() == null
                    ? ErpPurRules.totalAmountOf(doc.getItems()) : doc.getTotalAmount();
            row.remainQtySum = doc.getRemainQtySum();
            row.remark = doc.getRemark();
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

        public String getSupplierName()
        {
            return supplierName;
        }

        public String getPurchaseDeptId()
        {
            return purchaseDeptId;
        }

        public Date getExpectedArrivalDate()
        {
            return expectedArrivalDate;
        }

        public String getSettleType()
        {
            return settleType;
        }

        public String getCurrency()
        {
            return currency;
        }

        public String getReceiptWarehouseName()
        {
            return receiptWarehouseName;
        }

        public String getContractNo()
        {
            return contractNo;
        }

        public String getSourceDocNo()
        {
            return sourceDocNo;
        }

        public java.math.BigDecimal getTotalAmount()
        {
            return totalAmount;
        }

        public java.math.BigDecimal getRemainQtySum()
        {
            return remainQtySum;
        }

        public String getRemark()
        {
            return remark;
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

        public java.math.BigDecimal getReceivedQty()
        {
            return receivedQty;
        }

        public java.math.BigDecimal getRemainQty()
        {
            return remainQty;
        }
    }

    /**
     * 采购单详情（含行项与变更历史；带默认收货仓库名快照）。
     *
     * @param id 主键
     * @return 采购单
     */
    @PreAuthorize("@ss.hasPermi('pur:order:query')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable("id") String id)
    {
        return success(orderService.selectOrderDetail(id));
    }

    /**
     * 行项列表。
     *
     * @param docId 表头ID
     * @return 行项集合
     */
    @PreAuthorize("@ss.hasPermi('pur:order:query')")
    @GetMapping("/{docId}/items")
    public AjaxResult items(@PathVariable("docId") String docId)
    {
        return success(orderService.selectItemsByDocId(docId));
    }

    /**
     * 变更历史。
     *
     * @param docId 采购单ID
     * @return 日志集合
     */
    @PreAuthorize("@ss.hasPermi('pur:order:query')")
    @GetMapping("/{docId}/change-logs")
    public AjaxResult changeLogs(@PathVariable("docId") String docId)
    {
        return success(orderService.selectOrderChangeLogs(docId));
    }

    /**
     * 采购方向的可用合同下拉（口径与采购申请单一致）。
     *
     * @return 合同集合
     */
    @PreAuthorize("@ss.hasPermi('pur:order:query')")
    @GetMapping("/contract-options")
    public AjaxResult contractOptions()
    {
        List<CtmsContract> options = new ArrayList<>();
        if (contractService == null)
        {
            return success(options);
        }
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
            catch (ServiceException ignored)
            {
                // 方向不符的合同不进下拉
            }
        }
        return success(options);
    }

    /* ==================== 新增 / 编辑 / 删除 ==================== */

    /**
     * 新增采购单（草稿；供应商必填且必须启用）。
     *
     * @param order 采购单（行项走 {@code items}）
     * @return 主键
     */
    @PreAuthorize("@ss.hasPermi('pur:order:add')")
    @Log(title = "采购单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ErpPurchaseOrder order)
    {
        return success(orderService.insertOrder(order));
    }

    /**
     * 编辑采购单（仅草稿可编辑；行项全量替换）。
     *
     * @param order 采购单
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:edit')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ErpPurchaseOrder order)
    {
        orderService.updateOrder(order);
        return success();
    }

    /**
     * 删除采购单（仅草稿；业务侧走作废）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:remove')")
    @Log(title = "采购单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") String id)
    {
        orderService.deleteOrder(id);
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
    @PreAuthorize("@ss.hasPermi('pur:order:edit')")
    @Log(title = "采购单行项", businessType = BusinessType.INSERT)
    @PostMapping("/{docId}/items")
    public AjaxResult addItem(@PathVariable("docId") String docId, @RequestBody ErpPurchaseOrderItem item)
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
    @PreAuthorize("@ss.hasPermi('pur:order:edit')")
    @Log(title = "采购单行项", businessType = BusinessType.UPDATE)
    @PutMapping("/{docId}/items")
    public AjaxResult editItem(@PathVariable("docId") String docId, @RequestBody ErpPurchaseOrderItem item)
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
    @PreAuthorize("@ss.hasPermi('pur:order:edit')")
    @Log(title = "采购单行项", businessType = BusinessType.DELETE)
    @DeleteMapping("/{docId}/items/{itemId}")
    public AjaxResult removeItem(@PathVariable("docId") String docId, @PathVariable("itemId") String itemId)
    {
        itemWriter().deleteItem(docId, itemId);
        return success();
    }

    /**
     * 行项维护入口（服务实现同时实现了 {@code ItemWriter}）。
     *
     * @return 行项维护入口
     */
    protected IErpPurchaseOrderService.ItemWriter itemWriter()
    {
        if (orderService instanceof IErpPurchaseOrderService.ItemWriter)
        {
            return (IErpPurchaseOrderService.ItemWriter) orderService;
        }
        throw new ServiceException("行项维护未实现");
    }

    /* ==================== 状态流转 ==================== */

    /**
     * 提交（草稿 → 待审核）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:submit')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/submit")
    public AjaxResult submit(@PathVariable("id") String id)
    {
        orderService.changeStatus(id, "submit", null);
        return success();
    }

    /**
     * 审核（待审核 → 已审核）。驳回共用本权限点，用 {@code action=reject} 区分。
     *
     * @param id     主键
     * @param action 动作码（缺省 {@code approve}）
     * @param reason 原因（驳回必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:approve')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/approve")
    public AjaxResult approve(@PathVariable("id") String id,
                             @RequestParam(value = "action", required = false) String action,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        orderService.changeStatus(id, action == null ? "approve" : action, reason);
        return success();
    }

    /**
     * 反审核（已审核 / 已完成 → 待审核 / 草稿）。
     *
     * @param id     主键
     * @param reason 原因（必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:unapprove')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/unapprove")
    public AjaxResult unapprove(@PathVariable("id") String id,
                               @RequestParam(value = "reason", required = false) String reason)
    {
        orderService.changeStatus(id, "unapprove", reason);
        return success();
    }

    /**
     * 作废（草稿 / 待审核 → 已作废）。
     *
     * @param id     主键
     * @param reason 原因（必填）
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:void')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/void")
    public AjaxResult voidDoc(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason)
    {
        orderService.changeStatus(id, "void", reason);
        return success();
    }

    /**
     * 置为已完成（已审核 → 已完成；采购单不是库存类单据，提供该动作）。
     *
     * @param id 主键
     * @return 成功
     */
    @PreAuthorize("@ss.hasPermi('pur:order:status')")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/complete")
    public AjaxResult complete(@PathVariable("id") String id)
    {
        orderService.changeStatus(id, "complete", null);
        return success();
    }

    /* ==================== 下推入库单（任务 3.5） ==================== */

    /**
     * <p> 采购单 → 入库单下推（任务 3.5）。 </p>
     *
     * <p> 仅已审核 / 已完成的采购单可推；{@code lines} 为空或不传表示按全部行（各按剩余可入库量）；
     * {@code warehouseId} 不传时回落采购单的默认收货仓库，两者都空则<b>拒绝下推</b>
     * （{@code t_ctms_stock_in.warehouse_id} 是 NOT NULL + FK，没有仓库的草稿在真库上根本插不进去）。 </p>
     *
     * <p> 生成的入库单是<b>草稿</b>：{@code received_qty} 只在入库单<b>过账</b>时回写、
     * 红冲时回退（{@code ErpPurchaseReceiptListener}）。 </p>
     *
     * @param id          采购单ID
     * @param warehouseId 收货仓库ID（可空）
     * @param remark      备注（可空）
     * @param lines       下推行（可空；{@code srcItemId} = 采购单行项ID）
     * @return 下推结果（含草稿入库单与提示文案）
     */
    @PreAuthorize("@ss.hasPermi('pur:order:push')")
    @Log(title = "采购单下推入库单", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/push/stock-in")
    public AjaxResult pushToStockIn(@PathVariable("id") String id,
                                    @RequestParam(value = "warehouseId", required = false) String warehouseId,
                                    @RequestParam(value = "remark", required = false) String remark,
                                    @RequestBody(required = false) List<ErpPushLine> lines)
    {
        return success(pushService.pushToStockIn(id, lines, warehouseId, remark));
    }
}
