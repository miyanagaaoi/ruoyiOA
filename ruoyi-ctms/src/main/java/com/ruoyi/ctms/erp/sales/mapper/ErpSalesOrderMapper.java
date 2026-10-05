package com.ruoyi.ctms.erp.sales.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;

/**
 * <p> 销售订单表头的数据访问接口，对应 XML
 * {@code resources/mapper/erp/ErpSalOrderMapper.xml}。 </p>
 *
 * <p> 与 {@link ErpSalesRequestMapper} 的差别：销售订单有 {@code total_amount} 列
 * （各行先舍入到 2 位后之和，由服务层用 {@code ErpAmounts} 算好再写入）与 5 列发货信息。 </p>
 *
 * @author 二开
 */
public interface ErpSalesOrderMapper
{
    /**
     * 销售订单列表（状态精确、关键字模糊、单据日期区间、是否含已作废、数据范围片段）。
     *
     * @param query 查询条件
     * @return 单据集合（按单据日期倒序、创建时间倒序）
     */
    List<ErpSalesOrder> selectSalesOrderList(ErpSalesOrder query);

    /**
     * 按主键查销售订单（含已作废行）。
     *
     * @param id 主键
     * @return 单据；不存在返回 null
     */
    ErpSalesOrder selectSalesOrderById(@Param("id") String id);

    /**
     * 新增销售订单（{@code id} 由服务层生成）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertSalesOrder(ErpSalesOrder doc);

    /**
     * 修改销售订单（不修改 {@code doc_no} / {@code del_flag} / {@code posted} / 创建者列）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateSalesOrder(ErpSalesOrder doc);

    /**
     * 只更新状态与状态痕迹列（与申请单同款，供后续动作复用）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateSalesOrderStatus(ErpSalesOrder doc);

    /**
     * 按主键物理删除（仅草稿可删；服务层已做状态守卫）。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteSalesOrderById(@Param("id") String id);

    /**
     * 按单号前缀 + 年月取库内最大单号（取号的唯一来源）。
     *
     * @param prefix 单号前缀（如 {@code SO202610}）
     * @return 最大单号；无记录返回 null
     */
    String selectMaxDocNoByPrefix(@Param("prefix") String prefix);

    /**
     * 统计该单据的行项数量（提交守卫用）。
     *
     * @param docId 单据ID
     * @return 行项数量
     */
    int countOrderItems(@Param("docId") String docId);

    /**
     * <p> 按来源申请单ID查已下推的销售订单（下推幂等提示与"已完成"判定用）。 </p>
     *
     * @param sourceDocId 来源销售申请单ID
     * @return 单据集合（按创建时间升序；非 null）
     */
    List<ErpSalesOrder> selectOrdersBySourceDocId(@Param("sourceDocId") String sourceDocId);
}
