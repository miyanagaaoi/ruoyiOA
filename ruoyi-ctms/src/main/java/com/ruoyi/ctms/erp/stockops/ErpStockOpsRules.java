package com.ruoyi.ctms.erp.stockops;

import java.math.BigDecimal;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;

/**
 * <p> <b>调拨单 / 盘点单的业务口径常量与校验</b>（2.0 B4 任务 6.1~6.7）。 </p>
 *
 * <p> 与 {@code posting.ErpStockRules}（出入库）、{@code procurement.ErpPurRules}、
 * {@code sales.ErpSalRules} 的分工：本类只放<b>调拨与盘点</b>的口径
 * （两仓校验、调拨两仓的业务类型、盘点范围与差异口径、错误文案），
 * 金额/精度一律转发 {@link ErpAmounts}（唯一实现点）。 </p>
 *
 * <p> <b>错误文案是接口契约</b>：前端与 {@code tools/erp-*-check.ps1} 按这些字符串断言，
 * 因此统一写在这里，不在服务实现里各写一套；行号一律"行 N："前缀。 </p>
 *
 * @author 二开
 */
public final class ErpStockOpsRules
{
    /* ==================== 标志位 / 留痕 ==================== */

    /** 是（{@code '1'}）。 */
    public static final String FLAG_YES = "1";

    /** 否（{@code '0'}）。 */
    public static final String FLAG_NO = "0";

    /** 变更历史字段名：状态。 */
    public static final String LOG_FIELD_STATUS = "status";

    /** 变更历史字段名：过账标记。 */
    public static final String LOG_FIELD_POSTED = "posted";

    /** 变更历史字段名：行项集合（编辑/生成行项）。 */
    public static final String LOG_FIELD_ITEMS = "_items";

    /** 变更历史字段名：生成的盘盈入库单号。 */
    public static final String LOG_FIELD_GENERATED_IN_NO = "generated_in_no";

    /** 变更历史字段名：生成的盘亏出库单号。 */
    public static final String LOG_FIELD_GENERATED_OUT_NO = "generated_out_no";

    /** 变更历史来源：手工动作。 */
    public static final String LOG_SOURCE_MANUAL = "manual";

    /** 变更历史来源：系统动作（盘点审核自动生成）。 */
    public static final String LOG_SOURCE_AUTO = "auto";

    /* ==================== 兜底值 ==================== */

    /** 无登录上下文时的登录名兜底（内存桩单测/系统任务）。 */
    public static final String DEFAULT_USERNAME = "system";

    /** 无登录上下文时的用户ID兜底。 */
    public static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的部门ID兜底（数据范围列 NOT NULL）。 */
    public static final String DEFAULT_DEPT_ID = "100";

    /* ==================== 业务类型（落流水 biz_type，取值与 sys_dict_data.stock_biz_types 逐字一致） ==================== */

    /** 调拨出库（调出仓负数流水）。 */
    public static final String BIZ_TRANSFER_OUT = "调拨出库";

    /** 调拨入库（调入仓正数流水）。 */
    public static final String BIZ_TRANSFER_IN = "调拨入库";

    /** 盘盈入库（盘点审核自动生成的入库单类型；字典里 status='1' 停用，只由系统生成）。 */
    public static final String BIZ_TAKE_GAIN = "盘盈入库";

    /** 盘亏出库（盘点审核自动生成的出库单类型）。 */
    public static final String BIZ_TAKE_LOSS = "盘亏出库";

    /** 调拨请求级业务类型兜底（真正的 biz_type 由每条流水覆盖，见 ErpPostingLine#setBizType）。 */
    public static final String BIZ_TRANSFER = "调拨";

    /* ==================== 盘点范围（sys_dict_data.stock_take_types） ==================== */

    /** 全盘。 */
    public static final String TAKE_TYPE_FULL = "full";

    /** 抽盘。 */
    public static final String TAKE_TYPE_PARTIAL = "partial";

    /* ==================== 文案 ==================== */

    /** 单据不存在。 */
    public static final String MSG_NOT_FOUND = "单据不存在";

    /** 至少一行行项。 */
    public static final String MSG_NO_ITEMS = "单据至少需要一行行项";

    /** 数量必须大于 0。 */
    public static final String MSG_QTY_POSITIVE = "数量必须大于 0";

    /** 实盘数量不得为负。 */
    public static final String MSG_ACTUAL_NEGATIVE = "实盘数量不得为负数";

    /** 调出仓库必填。 */
    public static final String MSG_TRANSFER_FROM_REQUIRED = "必须选择调出仓库";

    /** 调入仓库必填。 */
    public static final String MSG_TRANSFER_TO_REQUIRED = "必须选择调入仓库";

    /** 两仓相同。 */
    public static final String MSG_TRANSFER_SAME_WAREHOUSE = "调出仓库与调入仓库不能相同";

    /** 调拨行项不使用行级仓库。 */
    public static final String MSG_TRANSFER_ROW_WAREHOUSE = "调拨单的仓库由表头两仓决定，行项不使用行级仓库";

    /** 盘点仓库必填。 */
    public static final String MSG_TAKE_WAREHOUSE_REQUIRED = "必须选择盘点仓库";

    /** 盘点仓库不可改。 */
    public static final String MSG_TAKE_WAREHOUSE_IMMUTABLE = "盘点仓库不可修改：账面数量按生成时的仓库结存固化，请作废后重新生成行项";

    /** 抽盘范围缺失。 */
    public static final String MSG_TAKE_PARTIAL_SCOPE = "抽盘必须指定物料或商品类型";

    /** 盘点范围取值非法。 */
    public static final String MSG_TAKE_TYPE_INVALID = "盘点范围只能是 full（全盘）或 partial（抽盘）";

    /** 行项不在盘点范围内。 */
    public static final String MSG_TAKE_ITEM_OUT_OF_SCOPE = "该物料不在本次盘点行项范围内：";

    private ErpStockOpsRules()
    {
    }

    /* ==================== 校验 ==================== */

    /**
     * 调拨两仓校验（保存与审核共用）：都必须非空、必须不同。
     *
     * @param fromWarehouseId 调出仓
     * @param toWarehouseId   调入仓
     * @throws ServiceException 任一条不满足
     */
    public static void checkTransferWarehouses(String fromWarehouseId, String toWarehouseId)
    {
        if (trimToNull(fromWarehouseId) == null)
        {
            throw new ServiceException(MSG_TRANSFER_FROM_REQUIRED);
        }
        if (trimToNull(toWarehouseId) == null)
        {
            throw new ServiceException(MSG_TRANSFER_TO_REQUIRED);
        }
        if (fromWarehouseId.trim().equals(toWarehouseId.trim()))
        {
            throw new ServiceException(MSG_TRANSFER_SAME_WAREHOUSE);
        }
    }

    /**
     * 盘点范围归一：空白按全盘；只接受 {@code full} / {@code partial}。
     *
     * @param takeType 盘点范围
     * @return 归一后的取值
     * @throws ServiceException 取值非法
     */
    public static String normalizeTakeType(String takeType)
    {
        String value = trimToNull(takeType);
        if (value == null)
        {
            return TAKE_TYPE_FULL;
        }
        String lowered = value.toLowerCase();
        if (!TAKE_TYPE_FULL.equals(lowered) && !TAKE_TYPE_PARTIAL.equals(lowered))
        {
            throw new ServiceException(MSG_TAKE_TYPE_INVALID);
        }
        return lowered;
    }

    /**
     * 数量必须大于 0（调拨与出入库同口径；盘点行项的"数量"不用本方法，它用账面/实盘两列）。
     *
     * @param qty   数量
     * @param rowNo 行号（1 起；≤0 时文案不带行号）
     * @throws ServiceException 数量为空或 ≤0
     */
    public static void checkQtyPositive(BigDecimal qty, int rowNo)
    {
        if (qty == null || qty.signum() <= 0)
        {
            throw new ServiceException(rowPrefix(rowNo) + MSG_QTY_POSITIVE);
        }
    }

    /**
     * 实盘数量校验：非负；小数位不得超过单位小数位。
     *
     * @param actualQty   实盘数量
     * @param uomDecimals 单位小数位（可空）
     * @param rowNo       行号（1 起；用于文案）
     * @throws ServiceException 为负或超出精度
     */
    public static void checkActualQty(BigDecimal actualQty, Integer uomDecimals, int rowNo)
    {
        if (actualQty == null)
        {
            throw new ServiceException(rowPrefix(rowNo) + MSG_ACTUAL_NEGATIVE);
        }
        if (actualQty.signum() < 0)
        {
            throw new ServiceException(rowPrefix(rowNo) + MSG_ACTUAL_NEGATIVE);
        }
        ErpAmounts.checkQuantityScale(actualQty, uomDecimals, rowNo);
    }

    /**
     * <p> <b>盘点行项的快照写入（与 {@code ErpMasterGuards.applyItemSnapshot} 的唯一刻意差异）</b>。 </p>
     *
     * <p> 差异点：盘点<b>不校验物料的启用状态</b>。理由：盘点的对象是"仓库里实际躺着的东西"，
     * 一个已被停用的物料只要还有结存，就必须能盘到 —— 用"停用物料不能用于新单据"的守卫会把
     * 盘点卡死在仓库有历史结存的那一刻，而盘点恰恰是清理这种历史的手段。
     * 手工新增行项（非盘点路径）仍然走 {@code applyItemSnapshot}，那份"启用"校验没有被绕过。 </p>
     *
     * <p> 除此之外全部与公共层一致：物料与单位必须存在、快照六列来自档案、实盘数量精度校验。 </p>
     *
     * @param lookup 主数据查询
     * @param item   盘点行项
     * @param rowNo  行号（1 起）
     * @throws ServiceException 物料/单位不存在或实盘数量非法
     */
    public static void applyStocktakeItemSnapshot(ErpMasterGuards.MasterLookup lookup, ErpStocktakeItem item,
                                                  int rowNo)
    {
        String prefix = rowPrefix(rowNo);
        String productId = trimToNull(item.getProductId());
        if (productId == null)
        {
            throw new ServiceException(prefix + "必须选择物料档案");
        }
        ErpMasterGuards.MasterRecord product = lookup == null ? null : lookup.product(productId);
        if (product == null)
        {
            throw new ServiceException(prefix + "物料档案不存在：" + productId);
        }
        ErpMasterGuards.MasterRecord uom = product.getUomId() == null ? null : lookup.uom(product.getUomId());
        if (uom == null)
        {
            throw new ServiceException(prefix + "物料「" + product.getName() + "」未配置计量单位，无法盘点");
        }
        item.applyProductSnapshot(product.getId(), product.getCode(), product.getName(), product.getSpec(),
                uom.getName(), uom.getDecimals());
        checkActualQty(item.getActualQty(), uom.getDecimals(), rowNo);
    }

    /* ==================== 小工具 ==================== */

    /**
     * 行号前缀（统一"行 N："形态）。
     *
     * @param rowNo 行号（1 起；≤0 返回空串）
     * @return 前缀
     */
    public static String rowPrefix(int rowNo)
    {
        return rowNo > 0 ? ("行 " + rowNo + "：") : "";
    }

    /**
     * 空白判定（null / 空串 / 全空白）。
     *
     * @param value 值
     * @return 空白返回 true
     */
    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 去空白并归一（空 → null）。
     *
     * @param value 值
     * @return 归一值
     */
    public static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 标志位归一（null/空白用兜底值）。
     *
     * @param value    值
     * @param fallback 兜底值
     * @return 非空标志位
     */
    public static String flagOr(String value, String fallback)
    {
        return isBlank(value) ? fallback : value.trim();
    }
}
