package com.ruoyi.ctms.erp.posting.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStock;

/**
 * <p> <b>库存结存读写 Mapper</b>（表 {@code t_ctms_stock}，2.0 B4 任务 5.2/5.5）。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockMapper.xml}（文件名必须以 {@code Mapper.xml} 结尾，
 * namespace 与接口全限定名一致，否则扫不到）。 </p>
 *
 * <p> <b>两条方法的分工是并发正确性的关键，不要互相替代</b>： </p>
 * <ul>
 *   <li> {@link #selectStockByKey} —— 普通读（校验、展示、一致性核对），不加锁； </li>
 *   <li> {@link #selectStockForUpdate} —— {@code SELECT ... FOR UPDATE} 行锁读，
 *        只能由过账引擎在<b>显式事务内</b>调用（design D2）：读到旧值后到写回之间
 *        任何并发写都必须被挡住，否则"负库存校验读到的值"会过期（丢更新）。 </li>
 * </ul>
 *
 * <p> 结存表<b>没有</b>金额列（design D5），所以不存在"金额口径"的读法问题；
 * 它也没有 dept_id/create_id 列，数据范围对结存的约束由流水侧（T7 的库存账查询）承担。 </p>
 *
 * @author 二开
 */
public interface ErpStockMapper
{
    /**
     * 按（物料, 仓库）查结存（不加锁）。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 结存行；不存在返回 null
     */
    ErpStock selectStockByKey(@Param("productId") String productId, @Param("warehouseId") String warehouseId);

    /**
     * 按（物料, 仓库）<b>加行锁</b>读取结存（{@code SELECT ... FOR UPDATE}）。
     *
     * <p> 必须在事务内调用；返回 null 表示该行尚不存在（由引擎插入并捕获重复键重试，design D3）。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 结存行；不存在返回 null
     */
    ErpStock selectStockForUpdate(@Param("productId") String productId, @Param("warehouseId") String warehouseId);

    /**
     * 结存列表（物料/仓库精确筛选；供运维核对与单测）。
     *
     * @param query 查询条件（productId / warehouseId 传了才过滤）
     * @return 结存集合
     */
    List<ErpStock> selectStockList(ErpStock query);

    /**
     * 首次过账建结存行（唯一键 {@code uk_stock_product_warehouse} 保证并发下只留一行）。
     *
     * @param stock 结存行（id 由服务层生成）
     * @return 影响行数
     */
    int insertStock(ErpStock stock);

    /**
     * 把结存数量改写为给定绝对值（<b>仅在持有该行锁时调用</b>）。
     *
     * <p> 用绝对值而不是 {@code qty = qty + #{delta}}：负库存校验与"变动后结存快照"
     * 都基于锁内读到的值计算，写绝对值能让"流水快照"与"结存列"永远一致。 </p>
     *
     * @param id  结存行ID
     * @param qty 目标数量
     * @return 影响行数
     */
    int updateStockQty(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 结存行数（自检与运维核对用）。
     *
     * @return 行数
     */
    int countStock();
}
