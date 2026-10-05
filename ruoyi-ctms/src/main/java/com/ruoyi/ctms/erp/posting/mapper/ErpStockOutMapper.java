package com.ruoyi.ctms.erp.posting.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;

/**
 * <p> <b>出库单表头 Mapper</b>（表 {@code t_ctms_stock_out}，2.0 B4 任务 5.1）。 </p>
 *
 * <p> 与 {@code ErpStockInMapper} 严格对称（列名一一对应），差异只在"出库类型 / 客户"两列。
 * 列表默认排除已作废与已删除；数据范围片段由服务层写入 {@code ${dataScopeSql}}。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpStockOutMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface ErpStockOutMapper
{
    /**
     * 出库单列表（单号关键字 / 出库类型 / 仓库 / 状态 / 单据日期区间 + 数据范围）。
     *
     * @param query 查询条件（{@code dataScopeSql} 由服务层写入）
     * @return 单据集合
     */
    List<ErpStockOut> selectStockOutList(ErpStockOut query);

    /**
     * 按主键取表头（不含行项）。
     *
     * @param id 单据ID
     * @return 单据；不存在返回 null
     */
    ErpStockOut selectStockOutById(@Param("id") String id);

    /**
     * <p> <b>按来源单据查下游出库单</b>（t29；销售订单反审核的"上游守卫"用）。 </p>
     *
     * <p> <b>为什么由本组（T3）提供</b>：销售侧需要判断"这张销售订单是否已经被下游出库单引用"，
     * 而 {@code t_ctms_stock_out} 是 T3 的表；让销售包跨包直读别人的表会破坏模块内的边界，
     * 因此这里给一个<b>只读</b>窄接口（签名固定，销售侧按此调用）。 </p>
     *
     * <p> <b>语义</b>：返回 {@code source_doc_id = 入参} 且 {@code del_flag = '0'} 的全部出库单，
     * <b>按 {@code create_time, id} 稳定排序</b>。 </p>
     *
     * <p> <b>刻意不过滤状态</b>：本方法是"原始事实查询"，"已作废的下游单算不算引用"
     * 由调用方判定（销售侧 `checkNoDownstreamStockOuts` 在 Java 侧过滤作废，与采购线的
     * {@code assert_no_downstream} 同口径）。若这里就把作废行滤掉，调用方将无法区分
     * "没有下游单"与"只有已作废的下游单"，口径就被焊死在本方法里了。 </p>
     *
     * <p> <b>只读</b>：本方法没有任何写入语义，也不参与过账/红冲。 </p>
     *
     * @param sourceDocId 来源单据ID（不区分单据类型：按列值匹配）
     * @return 出库单集合；<b>无命中返回空集合</b>（不是 null）
     */
    List<ErpStockOut> selectBySourceDocId(@Param("sourceDocId") String sourceDocId);

    /**
     * 新增出库单（id/doc_no 由服务层生成）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertStockOut(ErpStockOut doc);

    /**
     * 覆盖式更新（状态 / 痕迹列 / 过账标记 / 表头仓库 / 类型 / 金额合计 / 审计块）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateStockOut(ErpStockOut doc);

    /**
     * 按主键批量删除（业务侧只允许删除草稿，状态守卫在服务层）。
     *
     * @param ids 单据ID数组
     * @return 影响行数
     */
    int deleteStockOutByIds(@Param("ids") String[] ids);
}
