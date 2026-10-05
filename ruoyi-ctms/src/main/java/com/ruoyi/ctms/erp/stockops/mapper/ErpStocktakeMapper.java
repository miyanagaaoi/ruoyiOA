package com.ruoyi.ctms.erp.stockops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;

/**
 * <p> <b>盘点单表头 Mapper</b>（表 {@code t_ctms_stocktake}，2.0 B4 任务 6.3~6.5）。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStocktakeMapper.xml}。与调拨/出入库同口径；
 * 额外覆盖生成单回填两列（{@code generated_in_id/no}、{@code generated_out_id/no}）。 </p>
 *
 * @author 二开
 */
public interface ErpStocktakeMapper
{
    /**
     * 盘点单列表（单号关键字 / 状态 / 仓库 / 盘点范围 / 单据日期区间 + 数据范围）。
     *
     * @param query 查询条件（{@code dataScopeSql} 由服务层写入）
     * @return 单据集合
     */
    List<ErpStocktake> selectStocktakeList(ErpStocktake query);

    /**
     * 按主键取表头（不含行项）。
     *
     * @param id 单据ID
     * @return 单据；不存在返回 null
     */
    ErpStocktake selectStocktakeById(@Param("id") String id);

    /**
     * 新增盘点单（id/doc_no 由服务层生成；行项随后批量插入）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertStocktake(ErpStocktake doc);

    /**
     * 覆盖式更新（状态 / 痕迹列 / 过账标记 / 生成单回填 / 审计块）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateStocktake(ErpStocktake doc);

    /**
     * 按主键批量删除（物理删除；业务侧只允许删除草稿）。
     *
     * @param ids 单据ID数组
     * @return 影响行数
     */
    int deleteStocktakeByIds(@Param("ids") String[] ids);
}
