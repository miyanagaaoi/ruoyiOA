package com.ruoyi.ctms.erp.posting.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;

/**
 * <p> <b>入库单行项 Mapper</b>（表 {@code t_ctms_stock_in_item}）。 </p>
 *
 * <p> 行项采用"<b>随表头全量替换</b>"的写法（B3 合同行项同款）：编辑时先删后插，
 * 序号在服务层从 1 连续重排。这样不需要逐行 diff，也不会留下"删了一半"的中间态
 * （整体在同一事务内）。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockInItemMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockInItemMapper
{
    /**
     * 取某入库单的全部行项（按 seq 升序）。
     *
     * @param docId 单据ID
     * @return 行项集合（无则空集合）
     */
    List<ErpStockInItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项。
     *
     * @param items 行项集合（服务层保证非空且已归一）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpStockInItem> items);

    /**
     * 删除某入库单的全部行项（编辑全量替换的第一步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
