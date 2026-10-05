package com.ruoyi.ctms.erp.ledger.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;
import com.ruoyi.ctms.erp.ledger.service.IErpStockLedgerQueryService;

/**
 * <p> <b>库存流水控制器</b>（B4 任务 7.3；URL 前缀 {@code /stk/ledger}）。 </p>
 *
 * <p> <b>权限点</b>（captain §9.2 冻结，已由 {@code sql/二开-进销存-菜单.sql} 落库）： </p>
 * <ul>
 *   <li> {@code GET  /stk/ledger/list}   —— {@code stk:ledger:list}；
 *        带 {@code productId + warehouseId} 即为"明细页的下钻抽屉"（同一处实现）； </li>
 *   <li> {@code GET  /stk/ledger/detail} —— {@code stk:ledger:query}（按流水ID取单行）； </li>
 *   <li> {@code GET|POST /stk/ledger/export} —— {@code stk:ledger:export}（xlsx）。 </li>
 * </ul>
 *
 * <p> <b>没有写入口</b>：流水的写入侧只有过账/红冲（T3），本控制器只读 ——
 * "尝试修改或删除流水"在控制层 / 服务层 / 数据访问层都不存在入口（AC-73）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/ledger")
public class ErpStockLedgerController extends BaseController
{
    /** 只读查询服务。 */
    @Autowired
    private IErpStockLedgerQueryService ledgerQueryService;

    /**
     * 库存流水列表 / 下钻（（物料, 仓库）+ 时间区间；记账时间倒序）。
     *
     * @param query 查询条件（productId / warehouseId / beginTime / endTime / docTypeFilter / keyword）
     * @return 分页结果（含业务类型 / 单据号 / 来源单号 / 数量变动 / 变动后结存 / 单价 / 操作人 / 时间）
     */
    @PreAuthorize("@ss.hasPermi('stk:ledger:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpStockLedgerRow query)
    {
        startPage();
        List<ErpStockLedgerRow> list = ledgerQueryService.selectLedgerRows(query);
        return getDataTable(list);
    }

    /**
     * 单行流水详情（范围外 403、不存在 404）。
     *
     * @param id 流水ID
     * @return 流水行
     */
    @PreAuthorize("@ss.hasPermi('stk:ledger:query')")
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("id") String id)
    {
        return success(ledgerQueryService.selectLedgerRow(id));
    }

    /**
     * 导出库存流水（xlsx；与列表同一处服务调用）。
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:ledger:export')")
    @Log(title = "库存流水", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpStockLedgerRow query)
    {
        ExcelUtil<ErpStockLedgerExportRow> util = new ExcelUtil<>(ErpStockLedgerExportRow.class);
        util.exportExcel(response, exportRows(query), "库存流水");
    }

    /**
     * 装配导出行（与列表同一处服务调用）。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpStockLedgerExportRow> exportRows(ErpStockLedgerRow query)
    {
        List<ErpStockLedgerRow> list = ledgerQueryService.selectLedgerRows(query);
        List<ErpStockLedgerExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpStockLedgerRow ledger : list)
            {
                if (ledger != null)
                {
                    rows.add(ErpStockLedgerExportRow.of(ledger));
                }
            }
        }
        return rows;
    }

    /**
     * 导出用行对象（列定义集中在这里，不给领域对象挂表现层注解）。
     */
    public static class ErpStockLedgerExportRow
    {
        /** 记账时间。 */
        @Excel(name = "记账时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
        private Date ledgerTime;

        /** 仓库。 */
        @Excel(name = "仓库")
        private String warehouseName;

        /** 物料编码。 */
        @Excel(name = "物料编码")
        private String productCode;

        /** 「商品类型名称-物料名称」。 */
        @Excel(name = "商品类型-物料名称")
        private String displayName;

        /** 业务类型（红冲带前缀）。 */
        @Excel(name = "业务类型")
        private String bizType;

        /** 单据类型。 */
        @Excel(name = "单据类型")
        private String docType;

        /** 单据号。 */
        @Excel(name = "单据号")
        private String docNo;

        /** 来源单号。 */
        @Excel(name = "来源单号")
        private String srcDocNo;

        /** 数量变动（正入负出）。 */
        @Excel(name = "数量变动")
        private BigDecimal qtyChange;

        /** 变动后结存。 */
        @Excel(name = "变动后结存")
        private BigDecimal qtyAfter;

        /** 单价（记录性；调拨为 0）。 */
        @Excel(name = "单价")
        private BigDecimal unitPrice;

        /** 操作人（登录名快照）。 */
        @Excel(name = "操作人")
        private String operator;

        /**
         * 从流水行装配一行。
         *
         * @param ledger 流水行
         * @return 导出行
         */
        static ErpStockLedgerExportRow of(ErpStockLedgerRow ledger)
        {
            ErpStockLedgerExportRow row = new ErpStockLedgerExportRow();
            row.ledgerTime = ledger.getLedgerTime();
            row.warehouseName = ledger.getWarehouseName();
            row.productCode = ledger.getProductCode();
            row.displayName = ledger.getDisplayName();
            row.bizType = ledger.getBizType();
            row.docType = ledger.getDocType();
            row.docNo = ledger.getDocNo();
            row.srcDocNo = ledger.getSrcDocNo();
            row.qtyChange = ledger.getQtyChange();
            row.qtyAfter = ledger.getQtyAfter();
            row.unitPrice = ledger.getUnitPrice();
            row.operator = ledger.getCreateBy();
            return row;
        }

        public Date getLedgerTime()
        {
            return ledgerTime;
        }

        public String getWarehouseName()
        {
            return warehouseName;
        }

        public String getProductCode()
        {
            return productCode;
        }

        public String getDisplayName()
        {
            return displayName;
        }

        public String getBizType()
        {
            return bizType;
        }

        public String getDocType()
        {
            return docType;
        }

        public String getDocNo()
        {
            return docNo;
        }

        public String getSrcDocNo()
        {
            return srcDocNo;
        }

        public BigDecimal getQtyChange()
        {
            return qtyChange;
        }

        public BigDecimal getQtyAfter()
        {
            return qtyAfter;
        }

        public BigDecimal getUnitPrice()
        {
            return unitPrice;
        }

        public String getOperator()
        {
            return operator;
        }
    }
}
