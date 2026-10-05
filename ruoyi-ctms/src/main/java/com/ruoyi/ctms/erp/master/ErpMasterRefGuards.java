package com.ruoyi.ctms.erp.master;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> <b>主数据的"引用守卫"纯规则</b>（2.0 B4 §2.3/§2.4；B3 刻意留给 B4 的部分）。 </p>
 *
 * <p> <b>为什么需要它</b>：B3 交付的四张档案表（商品类型 / 计量单位 / 仓库 / 物料）只做了
 * "被物料引用禁止删除"这一条（B3 阶段确实还没有单据与结存表，见
 * {@code oa-contract-ledger/notes/master-data-notes.md} §1.3 的边界说明：
 * "仓库删除的引用保护属 B4"）。B4 的 18 张单据/结存表落地后，物理删除一个已被引用的仓库或物料
 * 会直接撞数据库外键（1451），前端看到的是一个 500 —— 本类把这些引用**提前**变成可读的中文拒绝。 </p>
 *
 * <p> <b>纯规则、不查库</b>：引用计数由 {@link RefCounter} 回调提供，
 * 于是"有引用就拒绝、没引用就放行、以及各自的文案"可以表驱动单测（{@code ErpMasterRefGuardsTest}）。 </p>
 *
 * <p> <b>三档引用与顺序</b>：结存（{@code t_ctms_stock}）→ 流水（{@code t_ctms_stock_ledger}）
 * → 单据（4 张表头 + 8 张行项）。顺序即报错优先级：先报"有结存"最能解释"为什么不能删"。 </p>
 *
 * <p> ⚠ <b>保护性拒绝</b>：{@code counter} 为 null（引用统计未装配）时**拒绝删除**而不是放行 ——
 * 宁可让人看到一句明确的错误，也不要出现"守卫没生效、悄悄物理删掉了一张有单据的仓库"。 </p>
 *
 * @author 二开
 */
public final class ErpMasterRefGuards
{
    /** 仓库：被库存结存引用。 */
    public static final String WAREHOUSE_STOCK_MESSAGE = "仓库已有库存结存，无法删除";

    /** 仓库：被库存流水引用。 */
    public static final String WAREHOUSE_LEDGER_MESSAGE = "仓库已有库存流水，无法删除";

    /** 仓库：被单据（表头或行项）引用。 */
    public static final String WAREHOUSE_DOC_MESSAGE = "仓库已被单据引用，无法删除";

    /** 物料：被库存结存引用。 */
    public static final String PRODUCT_STOCK_MESSAGE = "物料已有库存结存，无法删除";

    /** 物料：被库存流水引用。 */
    public static final String PRODUCT_LEDGER_MESSAGE = "物料已有库存流水，无法删除";

    /** 物料：被单据行项引用。 */
    public static final String PRODUCT_DOC_MESSAGE = "物料已被单据行项引用，无法删除";

    /** 引用统计未装配时的保护性拒绝文案。 */
    public static final String COUNTER_MISSING_MESSAGE = "引用校验未装配，拒绝删除（保护性拒绝）";

    private ErpMasterRefGuards()
    {
    }

    /**
     * 引用统计回调（生产实现由 {@code CtmsProductMasterServiceImpl} 用
     * {@code ErpMasterRefMapper} 提供；单测给内存桩）。
     */
    public interface RefCounter
    {
        /**
         * 结存引用数。
         *
         * @param warehouseId 仓库ID（传 null 表示不按仓库统计）
         * @param productId   物料ID（传 null 表示不按物料统计）
         * @return 命中行数
         */
        int countStockRefs(String warehouseId, String productId);

        /**
         * 库存流水引用数。
         *
         * @param warehouseId 仓库ID（传 null 表示不按仓库统计）
         * @param productId   物料ID（传 null 表示不按物料统计）
         * @return 命中行数
         */
        int countLedgerRefs(String warehouseId, String productId);

        /**
         * 单据（4 张表头 + 8 张行项的仓库列）对仓库的引用数。
         *
         * @param warehouseId 仓库ID
         * @return 命中行数
         */
        int countDocRefsByWarehouse(String warehouseId);

        /**
         * 单据行项对物料的引用数。
         *
         * @param productId 物料ID
         * @return 命中行数
         */
        int countDocRefsByProduct(String productId);
    }

    /**
     * <p> 校验仓库可删除（B4 规格：{@code 若该仓库已有库存结存或已被任一单据引用，删除请求 MUST 被拒绝}）。 </p>
     *
     * @param counter     引用统计（为 null 时保护性拒绝）
     * @param warehouseId 仓库ID
     * @throws ServiceException 有任一档引用 / 统计未装配
     */
    public static void checkWarehouseDeletable(RefCounter counter, String warehouseId)
    {
        requireCounter(counter);
        if (isBlank(warehouseId))
        {
            throw new ServiceException("仓库不存在");
        }
        if (counter.countStockRefs(warehouseId, null) > 0)
        {
            throw new ServiceException(WAREHOUSE_STOCK_MESSAGE);
        }
        if (counter.countLedgerRefs(warehouseId, null) > 0)
        {
            throw new ServiceException(WAREHOUSE_LEDGER_MESSAGE);
        }
        if (counter.countDocRefsByWarehouse(warehouseId) > 0)
        {
            throw new ServiceException(WAREHOUSE_DOC_MESSAGE);
        }
    }

    /**
     * <p> 校验物料可删除（B4 表头/行项都指向物料；物料行项的 {@code product_id} 是 DB 级外键，
     * 不提前拦就会变成 1451 的 500）。 </p>
     *
     * @param counter   引用统计（为 null 时保护性拒绝）
     * @param productId 物料ID
     * @throws ServiceException 有任一档引用 / 统计未装配
     */
    public static void checkProductDeletable(RefCounter counter, String productId)
    {
        requireCounter(counter);
        if (isBlank(productId))
        {
            throw new ServiceException("物料不存在");
        }
        if (counter.countStockRefs(null, productId) > 0)
        {
            throw new ServiceException(PRODUCT_STOCK_MESSAGE);
        }
        if (counter.countLedgerRefs(null, productId) > 0)
        {
            throw new ServiceException(PRODUCT_LEDGER_MESSAGE);
        }
        if (counter.countDocRefsByProduct(productId) > 0)
        {
            throw new ServiceException(PRODUCT_DOC_MESSAGE);
        }
    }

    /**
     * 仓库是否可删除（只判定不抛，供"前端按钮显隐/提示"这类场景复用）。
     *
     * @param counter     引用统计
     * @param warehouseId 仓库ID
     * @return 可删除返回 true
     */
    public static boolean isWarehouseDeletable(RefCounter counter, String warehouseId)
    {
        return !hasWarehouseRef(counter, warehouseId);
    }

    /**
     * 物料是否可删除（只判定不抛）。
     *
     * @param counter   引用统计
     * @param productId 物料ID
     * @return 可删除返回 true
     */
    public static boolean isProductDeletable(RefCounter counter, String productId)
    {
        return !hasProductRef(counter, productId);
    }

    private static boolean hasWarehouseRef(RefCounter counter, String warehouseId)
    {
        if (counter == null || isBlank(warehouseId))
        {
            // 无统计能力时按"有引用"处理：与 check* 的保护性拒绝保持一致
            return true;
        }
        return counter.countStockRefs(warehouseId, null) > 0
                || counter.countLedgerRefs(warehouseId, null) > 0
                || counter.countDocRefsByWarehouse(warehouseId) > 0;
    }

    private static boolean hasProductRef(RefCounter counter, String productId)
    {
        if (counter == null || isBlank(productId))
        {
            return true;
        }
        return counter.countStockRefs(null, productId) > 0
                || counter.countLedgerRefs(null, productId) > 0
                || counter.countDocRefsByProduct(productId) > 0;
    }

    private static void requireCounter(RefCounter counter)
    {
        if (counter == null)
        {
            throw new ServiceException(COUNTER_MISSING_MESSAGE);
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
