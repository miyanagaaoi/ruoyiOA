package com.ruoyi.ctms.erp.ledger.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.service.IErpStockLedgerQueryService;
import com.ruoyi.ctms.erp.ledger.service.IErpStockRecalcService;

/**
 * <p> <b>库存明细控制器</b>（B4 任务 7.1/7.2/7.4；URL 前缀 {@code /stk/stock}，
 * 按 PRD §9.4 的单数资源口径）。 </p>
 *
 * <p> <b>权限点</b>（captain §9.2 冻结，已由 {@code sql/二开-进销存-菜单.sql} 落库）： </p>
 * <ul>
 *   <li> {@code GET  /stk/stock/list}   —— {@code stk:stock:list}； </li>
 *   <li> {@code GET  /stk/stock/detail} —— {@code stk:stock:query}（按（物料, 仓库）取单行）； </li>
 *   <li> {@code GET|POST /stk/stock/export} —— {@code stk:stock:export}（xlsx，与列表同筛选同范围）； </li>
 *   <li> {@code POST /stk/stock/recalc} —— {@code stk:stock:recalc}（运维：一致性校验 / 修复）。 </li>
 * </ul>
 *
 * <p> <b>没有写入口</b>：结存数量只能由过账/红冲改写（design D2）；
 * 本控制器唯一的写动作是 {@code recalc?repair=true}，它由 {@code stk:stock:recalc} 单独授权
 * （建议只给运维角色）。 </p>
 *
 * <p> <b>打印不做进销存专用端点</b>：明细/流水是"账"，没有单据打印版式；
 * 单据打印走平台打印模块（B2 已交付）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/stk/stock")
public class ErpStockBalanceController extends BaseController
{
    /** 只读查询服务。 */
    @Autowired
    private IErpStockLedgerQueryService ledgerQueryService;

    /** 一致性校验 / 修复服务。 */
    @Autowired
    private IErpStockRecalcService stockRecalcService;

    /**
     * 库存明细列表（物料关键字 / 仓库 / 商品类型子树 / 数量区间 / 低于安全库存 / 数据范围）。
     *
     * @param query 查询条件
     * @return 分页结果（含「商品类型名称-物料名称」与货品总额度）
     */
    @PreAuthorize("@ss.hasPermi('stk:stock:list')")
    @GetMapping("/list")
    public TableDataInfo list(ErpStockBalance query)
    {
        startPage();
        List<ErpStockBalance> list = ledgerQueryService.selectBalanceList(query);
        return getDataTable(list);
    }

    /**
     * 单行库存明细（按（物料, 仓库））。
     *
     * <p> 范围外返回业务码 403、不存在返回 404 —— 接口脚本据此分辨"越权"与"没有这条数据"。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 明细行
     */
    @PreAuthorize("@ss.hasPermi('stk:stock:query')")
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("productId") String productId,
                            @RequestParam("warehouseId") String warehouseId)
    {
        return success(ledgerQueryService.selectBalanceByKey(productId, warehouseId));
    }

    /**
     * 导出库存明细（xlsx；与列表同一处服务调用，保证"范围与筛选一致"）。
     *
     * @param response 响应（Excel 直接写流）
     * @param query    与列表一致的筛选条件
     */
    @PreAuthorize("@ss.hasPermi('stk:stock:export')")
    @Log(title = "库存明细", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/export", method = { RequestMethod.GET, RequestMethod.POST })
    public void export(HttpServletResponse response, ErpStockBalance query)
    {
        ExcelUtil<ErpStockBalanceExportRow> util = new ExcelUtil<>(ErpStockBalanceExportRow.class);
        util.exportExcel(response, exportRows(query), "库存明细");
    }

    /**
     * 装配导出行（与列表同一处服务调用；接口脚本可复用本方法核对"行数 == 列表 total"）。
     *
     * @param query 筛选条件
     * @return 导出行集合
     */
    public List<ErpStockBalanceExportRow> exportRows(ErpStockBalance query)
    {
        List<ErpStockBalance> list = ledgerQueryService.selectBalanceList(query);
        List<ErpStockBalanceExportRow> rows = new ArrayList<>();
        if (list != null)
        {
            for (ErpStockBalance balance : list)
            {
                if (balance != null)
                {
                    rows.add(ErpStockBalanceExportRow.of(balance));
                }
            }
        }
        return rows;
    }

    /**
     * 结存一致性校验（{@code repair=false}，默认只读）/ 修复（{@code repair=true}）。
     *
     * <p> 返回：被检查行数 / 是否一致 / 不一致条数与明细（结存 vs 流水累计）/ 实际修复行数。 </p>
     *
     * @param repair 是否把不一致行改写成流水累计
     * @return 校验结果
     */
    @PreAuthorize("@ss.hasPermi('stk:stock:recalc')")
    @Log(title = "库存重算", businessType = BusinessType.UPDATE)
    @PostMapping("/recalc")
    public AjaxResult recalc(@RequestParam(value = "repair", required = false, defaultValue = "false") boolean repair)
    {
        return success(stockRecalcService.recalc(repair));
    }

    /**
     * <p> 导出用行对象：{@code ExcelUtil} 按 {@code @Excel} 注解取列，
     * 所以导出列显式写在这里，而不是给领域对象挂表现层注解（领域对象同时是接口 JSON 契约）。 </p>
     */
    public static class ErpStockBalanceExportRow
    {
        /** 仓库。 */
        @Excel(name = "仓库")
        private String warehouseName;

        /** 物料编码。 */
        @Excel(name = "物料编码")
        private String productCode;

        /** 「商品类型名称-物料名称」。 */
        @Excel(name = "商品类型-物料名称")
        private String displayName;

        /** 规格。 */
        @Excel(name = "规格")
        private String spec;

        /** 计量单位。 */
        @Excel(name = "单位")
        private String uomName;

        /** 结存数量。 */
        @Excel(name = "结存数量")
        private BigDecimal qty;

        /** 安全库存。 */
        @Excel(name = "安全库存")
        private BigDecimal safetyStock;

        /** 货品总额度。 */
        @Excel(name = "货品总额度")
        private BigDecimal goodsQuota;

        /** 是否低于安全库存。 */
        @Excel(name = "低于安全库存")
        private String belowSafety;

        /** 最后过账时间。 */
        @Excel(name = "最后过账时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;

        /**
         * 从明细行装配一行。
         *
         * @param balance 明细行
         * @return 导出行
         */
        static ErpStockBalanceExportRow of(ErpStockBalance balance)
        {
            ErpStockBalanceExportRow row = new ErpStockBalanceExportRow();
            row.warehouseName = balance.getWarehouseName();
            row.productCode = balance.getProductCode();
            row.displayName = balance.getDisplayName();
            row.spec = balance.getSpec();
            row.uomName = balance.getUomName();
            row.qty = balance.getQty();
            row.safetyStock = balance.getSafetyStock();
            row.goodsQuota = balance.getGoodsQuota();
            row.belowSafety = balance.isBelowSafetyStock() ? "是" : "否";
            row.updateTime = balance.getUpdateTime();
            return row;
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

        public String getSpec()
        {
            return spec;
        }

        public String getUomName()
        {
            return uomName;
        }

        public BigDecimal getQty()
        {
            return qty;
        }

        public BigDecimal getSafetyStock()
        {
            return safetyStock;
        }

        public BigDecimal getGoodsQuota()
        {
            return goodsQuota;
        }

        public String getBelowSafety()
        {
            return belowSafety;
        }

        public Date getUpdateTime()
        {
            return updateTime;
        }
    }
}
