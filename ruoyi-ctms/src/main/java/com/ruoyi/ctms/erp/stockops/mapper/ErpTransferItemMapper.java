package com.ruoyi.ctms.erp.stockops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.stockops.domain.ErpTransferItem;

/**
 * <p> <b>调拨单行项 Mapper</b>（表 {@code t_ctms_transfer_item}，2.0 B4 任务 6.1）。 </p>
 *
 * <p> 行项随表头<b>全量替换</b>（先 {@code deleteItemsByDocId} 再 {@code batchInsertItems}，同一事务）。 </p>
 *
 * @author 二开
 */
public interface ErpTransferItemMapper
{
    /**
     * 取某调拨单的行项（按序号）。
     *
     * @param docId 单据ID
     * @return 行项集合（无则空集合）
     */
    List<ErpTransferItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项。
     *
     * @param items 行项（非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpTransferItem> items);

    /**
     * 删除某调拨单的全部行项（全量替换的第一步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
