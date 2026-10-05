package com.ruoyi.ctms.erp.sales.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;

/**
 * <p> <b>销售订单服务</b>（2.0 B4 任务 4.1；规格 {@code erp/sales}）。 </p>
 *
 * <p> 与采购单对称：客户引用 NOT NULL + 外键（禁止引用供应商）、停用客户拒存、
 * 发货信息（交货日期/收货地址/联系人/电话/默认发货仓库）、
 * 金额合计 = 各行先舍入到 2 位后之和。 </p>
 *
 * @author 二开
 */
public interface IErpSalesOrderService
{
    /**
     * 销售订单列表（状态、关键字、单据日期区间、是否含已作废、数据范围）。
     *
     * @param query 查询条件
     * @return 单据集合
     */
    List<ErpSalesOrder> selectSalesOrderList(ErpSalesOrder query);

    /**
     * 按主键查销售订单（附行项与变更历史）。
     *
     * @param id 主键
     * @return 单据；不存在返回 null
     */
    ErpSalesOrder selectSalesOrderById(String id);

    /**
     * 按单据ID取行项。
     *
     * @param docId 单据ID
     * @return 行项集合（非 null）
     */
    List<ErpSalesOrderItem> selectSalesOrderItems(String docId);

    /**
     * <p> 按<b>单据ID集合</b>批量取行项（<b>导出用</b>，避免逐条查询的 N+1）。 </p>
     *
     * @param docIds 单据ID集合（可空/空 → 返回空 Map）
     * @return {@code docId → 行项列表}（非 null；无行项的单据不出现键）
     */
    java.util.Map<String, List<ErpSalesOrderItem>> selectSalesOrderItemsByDocIds(List<String> docIds);

    /**
     * 新增销售订单（草稿）。客户必须存在且启用，且不得是供应商档案。
     *
     * @param doc 单据（含行项）
     * @return 落库后的单据（含生成的 id 与单号）
     */
    ErpSalesOrder insertSalesOrder(ErpSalesOrder doc);

    /**
     * 修改销售订单（仅草稿）。
     *
     * @param doc 单据（含行项）
     * @return 落库后的单据
     */
    ErpSalesOrder updateSalesOrder(ErpSalesOrder doc);

    /**
     * 删除销售订单（仅草稿）。
     *
     * @param id 主键
     */
    void deleteSalesOrderById(String id);

    /**
     * 提交（草稿 → 待审核；要求至少一行行项）。
     *
     * @param id     主键
     * @param remark 提交备注（可空）
     */
    void submitSalesOrder(String id, String remark);

    /**
     * 审核（待审核 → 已审核）。
     *
     * @param id 主键
     */
    void approveSalesOrder(String id);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     主键
     * @param reason 原因
     */
    void rejectSalesOrder(String id, String reason);

    /**
     * 作废（草稿/待审核 → 已作废；原因必填）。
     *
     * @param id     主键
     * @param reason 原因
     */
    void voidSalesOrder(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填）。
     *
     * @param id     主键
     * @param reason 原因
     */
    void unapproveSalesOrder(String id, String reason);

    /**
     * 手工置为已完成（已审核 → 已完成）。
     *
     * @param id 主键
     */
    void completeSalesOrder(String id);

    /**
     * <p> <b>关联合同（仅销售方向）</b>（任务 4.4）。 </p>
     *
     * @param id         单据ID
     * @param contractId 合同ID（可空：解除关联）
     * @return 保存后的合同编号快照
     */
    String linkContract(String id, String contractId);

    /**
     * 按单据ID取合同关联信息（只读）。
     *
     * @param id 单据ID
     * @return 形如 {@code {contractId, contractNo}}；单据不存在返回 null
     */
    Map<String, Object> contractRefOf(String id);

    /**
     * <p> <b>回写某行的已出库量</b>（出库单"过账成功"与"反审核红冲"的唯一写入口）。 </p>
     *
     * <p> 由 {@code ErpSalesOutboundListener} 在<b>同一事务</b>内调用： </p>
     * <ul>
     *   <li> {@code deltaQty > 0}：累加（{@code shipped_qty + delta}）； </li>
     *   <li> {@code deltaQty < 0}：回退，落库为 {@code GREATEST(0, shipped_qty - |delta|)}
     *        —— <b>回退后不小于 0</b>（tasks.md §4.3 的冻结口径）； </li>
     *   <li> 行ID不属于该单据时<b>静默跳过</b>（历史/脏数据不应把过账整体拖挂）。 </li>
     * </ul>
     *
     * @param orderDocId  销售订单ID
     * @param orderItemId 销售订单行项ID
     * @param deltaQty    变动量（正 = 过账累加，负 = 红冲回退）
     */
    void applyShippedQtyChange(String orderDocId, String orderItemId, java.math.BigDecimal deltaQty);
}
