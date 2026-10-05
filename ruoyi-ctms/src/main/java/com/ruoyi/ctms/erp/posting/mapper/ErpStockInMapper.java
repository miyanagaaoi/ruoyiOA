package com.ruoyi.ctms.erp.posting.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;

/**
 * <p> <b>入库单表头 Mapper</b>（表 {@code t_ctms_stock_in}，2.0 B4 任务 5.1）。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockInMapper.xml}（文件名与 namespace 的硬规则同其它 Mapper）。 </p>
 *
 * <p> 三条口径： </p>
 * <ol>
 *   <li> 列表默认排除已作废（{@code status <> 'voided'}）与已删除（{@code del_flag = '0'}），
 *        只有显式传 {@code includeVoided = "1"} 才放开作废行； </li>
 *   <li> 数据范围片段 {@code dataScopeSql} 由服务层用 {@code ErpDocScope.buildDataScopeSql("d")} 拼出，
 *        本层用 {@code ${}} 原样拼接（<b>绝不</b>接受请求参数）； </li>
 *   <li> {@code updateStockIn} 是"全列覆盖"的状态与痕迹写回（状态机动作、审核/反审核痕迹、过账标记），
 *        便于"状态流转 + 过账"在同一方法里一次落库。 </li>
 * </ol>
 *
 * @author 二开
 */
public interface ErpStockInMapper
{
    /**
     * 入库单列表（单号关键字 / 入库类型 / 仓库 / 状态 / 单据日期区间 + 数据范围）。
     *
     * @param query 查询条件（{@code ErpStockIn}；{@code dataScopeSql} 由服务层写入）
     * @return 单据集合
     */
    List<ErpStockIn> selectStockInList(ErpStockIn query);

    /**
     * 按主键取表头（不含行项；行项由 {@code ErpStockInItemMapper} 装配）。
     *
     * @param id 单据ID
     * @return 单据；不存在返回 null
     */
    ErpStockIn selectStockInById(@Param("id") String id);

    /**
     * 新增入库单（id/doc_no 由服务层生成）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertStockIn(ErpStockIn doc);

    /**
     * 覆盖式更新（状态 / 痕迹列 / 过账标记 / 表头仓库 / 类型 / 金额合计 / 审计块）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateStockIn(ErpStockIn doc);

    /**
     * 按主键批量删除（物理删除；业务侧只允许删除<b>草稿</b>，状态守卫在服务层）。
     *
     * @param ids 单据ID数组
     * @return 影响行数
     */
    int deleteStockInByIds(@Param("ids") String[] ids);
}
