package com.ruoyi.ctms.erp.posting;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>入库单 / 出库单的业务口径常量与校验</b>（2.0 B4 任务 5.1）。 </p>
 *
 * <p> 与 {@code procurement.ErpPurRules} 的分工：本类只放<b>库存作业单据</b>的口径
 * （仓库回落、行项基本校验、单号格式、状态留痕字段名），不复制采购/销售的下推与
 * 供应商客户规则。金额与数量精度一律转发 {@link ErpAmounts}（唯一实现点）。 </p>
 *
 * <p> <b>错误文案是接口契约</b>：前端与 {@code tools/erp-*-check.ps1} 按这些字符串断言，
 * 因此统一写在这里，不在服务实现里各写一套。行号一律用"行 N："前缀（{@link #rowPrefix(int)}），
 * 与 {@code ErpMasterGuards} 的文案同构。 </p>
 *
 * <p> <b>为什么不做"入库类型必须在字典里"的校验</b>：字典 {@code stock_in_types} 里
 * {@code 盘盈入库} 是<b>停用项</b>（只由盘点审核生成，不出现在手工下拉），
 * 若服务端按"启用字典值"白名单校验，盘点自己生成的盘盈单会被自己的字典拦住。
 * 因此服务端只要求类型非空，取值白名单由前端下拉与生成路径共同约束
 * （参见 {@code sql/二开-进销存.sql} 第 894~905 行的注释）。 </p>
 *
 * @author 二开
 */
public final class ErpStockRules
{
    /* ==================== 标志位 / 留痕 ==================== */

    /** 是（{@code '1'}）。 */
    public static final String FLAG_YES = "1";

    /** 否（{@code '0'}）。 */
    public static final String FLAG_NO = "0";

    /** 变更历史的字段名：状态。 */
    public static final String LOG_FIELD_STATUS = "status";

    /** 变更历史的字段名：过账标记（过账/红冲留痕）。 */
    public static final String LOG_FIELD_POSTED = "posted";

    /** 变更历史的字段名：行项集合（编辑单据留痕）。 */
    public static final String LOG_FIELD_ITEMS = "_items";

    /** 变更历史来源：手工动作。 */
    public static final String LOG_SOURCE_MANUAL = "manual";

    /** 变更历史来源：系统动作（如盘点审核自动生成）。 */
    public static final String LOG_SOURCE_AUTO = "auto";

    /* ==================== 兜底值 ==================== */

    /** 无登录上下文时的登录名兜底（内存桩单测/系统任务）。 */
    public static final String DEFAULT_USERNAME = "system";

    /** 无登录上下文时的用户ID兜底。 */
    public static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的部门ID兜底（数据范围列 NOT NULL，不能写空）。 */
    public static final String DEFAULT_DEPT_ID = "100";

    /* ==================== 文案 ==================== */

    /** 单据不存在。 */
    public static final String MSG_NOT_FOUND = "单据不存在";

    /** 至少一行行项。 */
    public static final String MSG_NO_ITEMS = "单据至少需要一行行项";

    /** 数量必须大于 0。 */
    public static final String MSG_QTY_POSITIVE = "数量必须大于 0";

    /** 单价不得为负。 */
    public static final String MSG_PRICE_NEGATIVE = "单价不得为负数";

    /** 缺少仓库（审核阶段；文案带行号）。 */
    public static final String MSG_WAREHOUSE_REQUIRED = "缺少仓库：请填写表头仓库或在行项上指定仓库";

    /** 未过账。 */
    public static final String MSG_NOT_POSTED = "单据未过账，无需红冲";

    private ErpStockRules()
    {
    }

    /* ==================== 行项校验 ==================== */

    /**
     * <p> 行项基本校验（保存与审核共用；审核路径需要能定位到行号）。 </p>
     *
     * <p> 逐条：至少一行；每行必须有物料；数量必须大于 0；单价非负。
     * 物料/单位的存在性、启用状态与<b>快照写入</b>由
     * {@code ErpMasterGuards.applyItemSnapshot} 负责（不在这里重复查库）。 </p>
     *
     * @param items 行项集合（可空 → 视为 0 行，直接报错）
     * @throws ServiceException 任一条不满足
     */
    public static void checkItemsBasic(List<? extends ErpDocItem> items)
    {
        if (items == null || items.isEmpty())
        {
            throw new ServiceException(MSG_NO_ITEMS);
        }
        int rowNo = 0;
        for (ErpDocItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException(rowPrefix(rowNo) + "行项不能为空");
            }
            if (isBlank(item.getProductId()))
            {
                throw new ServiceException(rowPrefix(rowNo) + "必须选择物料档案");
            }
            checkQtyPositive(item.getQty(), rowNo);
            if (item.getUnitPrice() != null && item.getUnitPrice().signum() < 0)
            {
                throw new ServiceException(rowPrefix(rowNo) + MSG_PRICE_NEGATIVE);
            }
        }
    }

    /**
     * 数量必须大于 0（盘点等"数量可为 0"的路径不走本方法）。
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
     * 单据金额合计 = 各行先舍入到 2 位后的和（转发 {@link ErpAmounts#totalOf(Collection)}）。
     *
     * @param items 行项集合
     * @return 2 位合计
     */
    public static BigDecimal totalAmountOf(List<? extends ErpDocItem> items)
    {
        return ErpAmounts.totalOf(items);
    }

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

    /* ==================== 小工具 ==================== */

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
     * 标志位归一（{@code null} 用兜底值）。
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
