package com.ruoyi.ctms.erp.sales.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;

/**
 * <p> 销售申请单表头的数据访问接口，对应 XML
 * {@code resources/mapper/erp/ErpSalRequestMapper.xml}。 </p>
 *
 * <p> 本包由全局 {@code @MapperScan("com.ruoyi.**.mapper")} 自动注册；
 * XML 文件名必须以 {@code Mapper.xml} 结尾（见 {@code mapper/ctms/README.md}）。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> {@link #selectSalesRequestList} 承担"多维筛选 + 默认排除已作废 + 数据范围片段"；
 *       数据范围由服务层拼成 SQL 片段放在 {@code query.dataScopeSql}，
 *       本层用原样拼接执行（<b>绝不能</b>把请求参数直接塞进该字段）； </li>
 *   <li> {@link #updateSalesRequest} <b>不改</b> {@code doc_no} / {@code del_flag} /
 *       {@code create_*}，其余业务列全量覆盖； </li>
 *   <li> {@link #selectMaxDocNoByPrefix} 是单号取号的"库内最大号"来源（月内前 6 位零填充，
 *       因此字典序最大即数值最大）； </li>
 *   <li> {@link #updateSalesRequestStatus} 只更新状态与痕迹列，供下推的"归零即完成"使用。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface ErpSalesRequestMapper
{
    /**
     * 销售申请单列表（状态精确、关键字模糊、单据日期区间、是否含已作废、数据范围片段）。
     *
     * @param query 查询条件
     * @return 单据集合（按单据日期倒序、创建时间倒序）
     */
    List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query);

    /**
     * 按主键查销售申请单（含已作废行，是否放行由服务层判定）。
     *
     * @param id 主键
     * @return 单据；不存在返回 null
     */
    ErpSalesRequest selectSalesRequestById(@Param("id") String id);

    /**
     * 新增销售申请单（{@code id} 由服务层生成）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertSalesRequest(ErpSalesRequest doc);

    /**
     * 修改销售申请单（不修改 {@code doc_no} / {@code del_flag} / {@code posted} / 创建者列）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateSalesRequest(ErpSalesRequest doc);

    /**
     * 只更新状态与状态痕迹列（"归零即完成"的落库口径；其余业务列不覆盖）。
     *
     * @param doc 单据（须含 id、status、updateId、updateBy）
     * @return 影响行数
     */
    int updateSalesRequestStatus(ErpSalesRequest doc);

    /**
     * 按主键物理删除（仅草稿可删；服务层已做状态守卫）。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteSalesRequestById(@Param("id") String id);

    /**
     * <p> 按单号前缀 + 年月取库内最大单号（单号取号的唯一来源）。 </p>
     *
     * @param prefix 单号前缀（如 {@code SR202610}，含年月；由服务端拼接，非请求参数）
     * @return 最大单号；无记录返回 null
     */
    String selectMaxDocNoByPrefix(@Param("prefix") String prefix);

    /**
     * 统计该单据的行项数量（提交守卫用；返回 0 表示没有行项）。
     *
     * @param docId 单据ID
     * @return 行项数量
     */
    int countRequestItems(@Param("docId") String docId);
}
