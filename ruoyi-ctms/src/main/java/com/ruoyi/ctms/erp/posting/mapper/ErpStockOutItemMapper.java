package com.ruoyi.ctms.erp.posting.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;

/**
 * <p> <b>出库单行项 Mapper</b>（表 {@code t_ctms_stock_out_item}）。 </p>
 *
 * <p> 与 {@code ErpStockInItemMapper} 对称：随表头全量替换（先删后插），
 * 序号由服务层从 1 连续重排。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockOutItemMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockOutItemMapper
{
    /**
     * 取某出库单的全部行项（按 seq 升序）。
     *
     * @param docId 单据ID
     * @return 行项集合（无则空集合）
     */
    List<ErpStockOutItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项。
     *
     * @param items 行项集合（服务层保证非空且已归一）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpStockOutItem> items);

    /**
     * 删除某出库单的全部行项（编辑全量替换的第一步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
