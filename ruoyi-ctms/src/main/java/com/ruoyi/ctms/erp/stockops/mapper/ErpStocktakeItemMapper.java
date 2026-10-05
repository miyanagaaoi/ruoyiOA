package com.ruoyi.ctms.erp.stockops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;

/**
 * <p> <b>盘点单行项 Mapper</b>（表 {@code t_ctms_stocktake_item}，2.0 B4 任务 6.3/6.4）。 </p>
 *
 * <p> 行项随表头全量替换；账面/实盘/差异三列由服务层固化（账面只在生成时写、
 * 实盘由录入路径写、差异处处由 {@code ErpAmounts.diffQty} 现算）。 </p>
 *
 * @author 二开
 */
public interface ErpStocktakeItemMapper
{
    /**
     * 取某盘点单的行项（按序号）。
     *
     * @param docId 单据ID
     * @return 行项集合（无则空集合）
     */
    List<ErpStocktakeItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项。
     *
     * @param items 行项（非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpStocktakeItem> items);

    /**
     * 删除某盘点单的全部行项（全量替换的第一步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
