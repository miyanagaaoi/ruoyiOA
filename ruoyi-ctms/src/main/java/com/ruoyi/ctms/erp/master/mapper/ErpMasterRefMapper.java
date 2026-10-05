package com.ruoyi.ctms.erp.master.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * <p> 主数据 <b>引用统计</b> Mapper（2.0 B4 §2.3/§2.4；B3 刻意留给 B4）。 </p>
 *
 * <p> 只做 {@code count(*)}：给"仓库/物料能不能物理删除"提供可读的拒绝理由，
 * 而不是让前端看到一个数据库外键错误（1451）。 </p>
 *
 * <p> 覆盖的引用面（B4 的 18 张表里所有指向物料/仓库的列）： </p>
 * <ul>
 *   <li> 结存 {@code t_ctms_stock}、流水 {@code t_ctms_stock_ledger}（两表对
 *        {@code t_ctms_product} / {@code t_ctms_warehouse} 都有外键）； </li>
 *   <li> 单据表头对仓库的 4 处：入库单 / 出库单 / 盘点单 / 调拨单（调拨是调出+调入两列）； </li>
 *   <li> 行项对仓库的 4 处 + 行项对物料的 8 处。 </li>
 * </ul>
 *
 * <p> 条件列都用 {@code <if>} 保护：{@code warehouseId} 与 {@code productId} 至少传一个，
 * 两个都空时语句退化为 {@code where 1=1}（调用方不会这样用，注释在此说明，
 * 避免读的人以为漏了校验）。 </p>
 *
 * @author 二开
 */
public interface ErpMasterRefMapper
{
    /**
     * 结存引用数。
     *
     * @param warehouseId 仓库ID（可空）
     * @param productId   物料ID（可空）
     * @return 命中行数
     */
    int countStockRefs(@Param("warehouseId") String warehouseId, @Param("productId") String productId);

    /**
     * 库存流水引用数。
     *
     * @param warehouseId 仓库ID（可空）
     * @param productId   物料ID（可空）
     * @return 命中行数
     */
    int countLedgerRefs(@Param("warehouseId") String warehouseId, @Param("productId") String productId);

    /**
     * 单据（表头 4 表 + 行项 4 表）对仓库的引用数。
     *
     * @param warehouseId 仓库ID
     * @return 命中行数
     */
    int countDocRefsByWarehouse(@Param("warehouseId") String warehouseId);

    /**
     * 单据行项对物料的引用数（8 张行项表的 {@code product_id}）。
     *
     * @param productId 物料ID
     * @return 命中行数
     */
    int countDocRefsByProduct(@Param("productId") String productId);
}
