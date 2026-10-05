package com.ruoyi.ctms.erp.procurement.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;

/**
 * <p> 采购单主体的数据访问接口（XML：{@code ErpPurOrderMapper.xml}）。 </p>
 *
 * <p> 与 {@code ErpPurchaseRequestMapper} 同构，两点差异： </p>
 * <ul>
 *   <li> 列表多了 {@code supplier_id} 筛选（供应商是采购单的必填引用）；</li>
 *   <li> 有 {@code total_amount} 列，所以新增/修改都要写它（值由服务层按
 *        「先舍入再汇总」算好，本层不做计算）。</li>
 * </ul>
 *
 * @author 二开
 */
public interface ErpPurchaseOrderMapper
{
    /**
     * 采购单列表（状态、关键词、单据日期区间、供应商、采购部门、创建人、包含已作废、数据范围片段）。
     *
     * @param query 查询条件
     * @return 单据集合（单据日期倒序、同日按单号倒序）
     */
    List<ErpPurchaseOrder> selectOrderList(ErpPurchaseOrder query);

    /**
     * 按主键查采购单（含已作废与已软删除行）。
     *
     * @param id 主键
     * @return 采购单；不存在返回 null
     */
    ErpPurchaseOrder selectOrderById(@Param("id") String id);

    /**
     * 按单号查采购单（查重；不过滤状态与 {@code del_flag}）。
     *
     * @param docNo 单号
     * @return 采购单；不存在返回 null
     */
    ErpPurchaseOrder selectOrderByNo(@Param("docNo") String docNo);

    /**
     * 取库内以给定前缀开头的全部采购单号（本地兜底单号的"同桶 max + 1"依据）。
     *
     * @param prefix 前缀（形如 {@code CGDD20261005}）
     * @return 单号集合
     */
    List<String> selectDocNosByPrefix(@Param("prefix") String prefix);

    /**
     * 按来源单据查下游采购单（反审核前的"下游守卫"与"已下推多少"核对用）。
     *
     * @param sourceDocId 来源单据ID
     * @return 采购单集合（含已作废行：是否拦截由服务层判定）
     */
    List<ErpPurchaseOrder> selectBySourceDocId(@Param("sourceDocId") String sourceDocId);

    /**
     * 新增采购单（{@code id} 与 {@code total_amount} 由服务层算好）。
     *
     * @param order 采购单
     * @return 影响行数
     */
    int insertOrder(ErpPurchaseOrder order);

    /**
     * 修改采购单（不覆盖 {@code doc_no} / {@code del_flag} / 痕迹列）。
     *
     * @param order 采购单
     * @return 影响行数
     */
    int updateOrder(ErpPurchaseOrder order);

    /**
     * 写状态与痕迹列（状态流转的唯一出口）。
     *
     * @param order 采购单
     * @return 影响行数
     */
    int updateOrderStatus(ErpPurchaseOrder order);

    /**
     * <p> 清空审核痕迹（{@code approved_by} / {@code approved_at} 置 NULL）。 </p>
     *
     * <p> 反审核专用（理由见 {@code ErpPurchaseRequestMapper#clearApprovalTrace}）：用
     * {@code <if test="x != null">} 写不出"置空"，痕迹会留在库里。 </p>
     *
     * @param id       单据ID
     * @param updateId 更新人用户ID
     * @param updateBy 更新人登录名快照
     * @return 影响行数
     */
    int clearApprovalTrace(@Param("id") String id, @Param("updateId") String updateId,
                           @Param("updateBy") String updateBy);

    /**
     * 只更新单据金额合计与审计列（行项单独维护后重算合计用；不覆盖其它业务列）。
     *
     * @param order 采购单（须含 id 与 totalAmount）
     * @return 影响行数
     */
    int updateOrderTotal(ErpPurchaseOrder order);

    /**
     * 物理删除采购单（仅回滚演练与夹具清理）。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteOrderById(@Param("id") String id);
}
