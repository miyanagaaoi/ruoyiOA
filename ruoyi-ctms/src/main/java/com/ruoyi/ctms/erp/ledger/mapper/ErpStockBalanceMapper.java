package com.ruoyi.ctms.erp.ledger.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;

/**
 * <p> <b>库存明细（结存）只读 Mapper</b>（表 {@code t_ctms_stock}，2.0 B4 任务 7.1/7.2）。 </p>
 *
 * <p> <b>本接口刻意只有 select</b>：结存数量的唯一写路径是 T3 的
 * {@code ErpStockJournalServiceImpl}（过账/红冲），运维修复走
 * {@code ErpStockMapper.updateStockQty}（T3 的写入侧 Mapper），
 * 这里再开一个 update 就等于开第二个写入口 —— 那正是 design D2 要禁止的。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockBalanceMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockBalanceMapper
{
    /**
     * 库存明细列表（物料关键字 / 仓库 / 商品类型子树 / 数量区间 / 低于安全库存 / 数据范围）。
     *
     * <p> 货品总额度在同一句 SQL 里按（物料, 仓库）聚合带出，避免列表 N+1。 </p>
     *
     * @param query 查询条件（服务层已把商品类型展开成 {@code productTypeIds}、填好 {@code dataScopeSql}）
     * @return 明细行集合
     */
    List<ErpStockBalance> selectBalanceList(ErpStockBalance query);

    /**
     * 按（物料, 仓库）取单行明细（含额度）。
     *
     * <p> <b>不加数据范围条件</b>：范围判定由服务层用同一处片段另查一次来裁决
     * （"范围外 403 / 不存在 404"要能区分，见 {@code ErpStockLedgerQueryServiceImpl}）。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 明细行；不存在返回 null
     */
    ErpStockBalance selectBalanceByKey(@Param("productId") String productId,
                                       @Param("warehouseId") String warehouseId);

    /**
     * 结存行数（一致性校验的"被检查行数"）。
     *
     * @return 行数
     */
    int countBalance();
}
