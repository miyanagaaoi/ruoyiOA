package com.ruoyi.ctms.erp.ledger.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.ledger.domain.ErpLedgerQtySum;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;

/**
 * <p> <b>库存流水只读 Mapper</b>（表 {@code t_ctms_stock_ledger}，2.0 B4 任务 7.3/7.4）。 </p>
 *
 * <p> <b>只有 select，没有 update / delete</b>：这是"流水只增不改"（AC-73）在数据访问层的
 * 直接体现。写入侧是 T3 的 {@code ErpStockLedgerMapper.insertLedger}，
 * 两者合起来才构成"只增不改"的完整证据。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockLedgerQueryMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockLedgerQueryMapper
{
    /**
     * 流水列表 / 下钻（按（物料, 仓库）与时间区间，时间倒序）。
     *
     * <p> 列表与下钻共用本方法：下钻只是多带 {@code productId + warehouseId} 两个条件，
     * 于是"下钻看到的行"与"列表里的行"永远同源、同一处数据范围。 </p>
     *
     * @param query 查询条件
     * @return 流水行集合（按记账时间倒序）
     */
    List<ErpStockLedgerRow> selectLedgerRows(ErpStockLedgerRow query);

    /**
     * 按主键取单行流水（含展示用的物料/仓库/类型名称）。
     *
     * <p> 不加数据范围条件，范围判定由服务层裁决（同结存明细）。 </p>
     *
     * @param id 流水ID
     * @return 流水行；不存在返回 null
     */
    ErpStockLedgerRow selectLedgerRowById(@Param("id") String id);

    /**
     * 全部（物料, 仓库）的流水数量变动累计和（一次性取回，供一致性校验用）。
     *
     * @return 聚合行集合
     */
    List<ErpLedgerQtySum> selectLedgerQtySums();
}
