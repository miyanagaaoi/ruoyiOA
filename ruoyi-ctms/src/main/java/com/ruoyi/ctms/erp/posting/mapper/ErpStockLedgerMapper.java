package com.ruoyi.ctms.erp.posting.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;

/**
 * <p> <b>库存流水 Mapper</b>（表 {@code t_ctms_stock_ledger}，2.0 B4 任务 5.2/5.4）。 </p>
 *
 * <p> <b>只增不改</b>：这里<b>没有</b> update / delete 方法 —— 这一点是刻意的，
 * 它让"流水只增不改"在数据访问层就成立（规格场景"尝试修改或删除任一已写入的库存流水
 * → 系统中不存在这样的入口"）。红冲 = 追加一条负数流水，原流水保留可查。 </p>
 *
 * <p> 查询方法只服务<b>写入侧</b>的需要（红冲要按单据反查原流水、单测要核对流水累计）；
 * 库存账的分页/下钻/额度统计等只读查询归 {@code ledger} 包（T7），不要在这里堆查询。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockLedgerMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockLedgerMapper
{
    /**
     * 追加一条流水（id 由服务层生成；记账时间由数据库 {@code sysdate()} 决定）。
     *
     * @param ledger 流水
     * @return 影响行数
     */
    int insertLedger(ErpStockLedger ledger);

    /**
     * 按（单据类型, 单据ID）取该单据的<b>全部</b>流水（含红冲行），按 id 升序。
     *
     * <p> 红冲时用它取"非红冲"的原流水；一致性核对用它算累计和。 </p>
     *
     * @param docType 单据类型码（{@code ErpDocType.getCode()}）
     * @param docId   单据ID
     * @return 流水集合（无则空集合）
     */
    List<ErpStockLedger> selectLedgerByDoc(@Param("docType") String docType, @Param("docId") String docId);

    /**
     * 某（物料, 仓库）全部流水的数量变动累计和。
     *
     * <p> 结存一致性不变式的右侧（"结存 = 流水累计和"），供过账单测与运维核对使用；
     * 正式的一致性校验接口归 T7（stk:stock:recalc）。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 累计和（无流水返回 0）
     */
    BigDecimal sumQtyChangeByKey(@Param("productId") String productId, @Param("warehouseId") String warehouseId);

    /**
     * 流水总条数（自检用）。
     *
     * @return 条数
     */
    int countLedger();
}
