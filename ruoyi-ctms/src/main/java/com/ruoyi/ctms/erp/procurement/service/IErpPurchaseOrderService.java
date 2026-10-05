package com.ruoyi.ctms.erp.procurement.service;

import java.math.BigDecimal;
import java.util.List;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;

/**
 * <p> 采购单服务（2.0 B4 任务 3.1、3.2、3.6；3.5 的过账回写入口在此声明，实现由 t4b 接）。 </p>
 *
 * <p> 与 {@link IErpPurchaseRequestService} 的分层约定完全一致；差异只有"供应商引用 + 收货信息"
 * 这一组校验（任务 3.2）与"金额合计落库"（采购单有 {@code total_amount} 列）。 </p>
 *
 * @author 二开
 */
public interface IErpPurchaseOrderService
{
    /* ==================== 查询 ==================== */

    /**
     * 采购单列表（状态筛选、默认排除已作废、关键词与日期筛选、供应商筛选、数据范围）。
     *
     * @param query 查询条件
     * @return 采购单集合（已装配 {@code remainQtySum} / {@code canReceive}）
     */
    List<ErpPurchaseOrder> selectOrderList(ErpPurchaseOrder query);

    /**
     * 采购单详情（含行项与变更历史）。
     *
     * @param id 主键
     * @return 采购单
     */
    ErpPurchaseOrder selectOrderDetail(String id);

    /**
     * 取某采购单的行项。
     *
     * @param docId 表头ID
     * @return 行项集合
     */
    List<ErpPurchaseOrderItem> selectItemsByDocId(String docId);

    /**
     * 采购单变更历史。
     *
     * @param docId 主键
     * @return 日志集合
     */
    List<com.ruoyi.ctms.domain.CtmsChangeLog> selectOrderChangeLogs(String docId);

    /* ==================== 新增 / 编辑 / 删除 ==================== */

    /**
     * 新增采购单（草稿）。
     *
     * @param order 采购单（行项走 {@code order.items}）
     * @return 新增后的主键
     */
    String insertOrder(ErpPurchaseOrder order);

    /**
     * 编辑采购单（仅草稿可编辑；行项全量替换）。
     *
     * @param order 采购单
     */
    void updateOrder(ErpPurchaseOrder order);

    /**
     * 物理删除采购单（仅草稿，且仅用于回滚演练与夹具清理）。
     *
     * @param id 主键
     */
    void deleteOrder(String id);

    /* ==================== 状态流转 ==================== */

    /**
     * 状态流转（提交 / 审核 / 驳回 / 作废 / 反审核 / 置为已完成）。
     *
     * @param id     主键
     * @param action 动作码
     * @param reason 原因（驳回 / 作废 / 反审核必填）
     */
    void changeStatus(String id, String action, String reason);

    /* ==================== 行项独立维护 ==================== */

    /**
     * 行项单独维护入口。
     */
    interface ItemWriter
    {
        /**
         * 新增一行行项。
         *
         * @param docId 表头ID
         * @param item  行项
         * @return 行项主键
         */
        String insertItem(String docId, ErpPurchaseOrderItem item);

        /**
         * 修改一行行项。
         *
         * @param docId 表头ID
         * @param item  行项（须含 id）
         */
        void updateItem(String docId, ErpPurchaseOrderItem item);

        /**
         * 删除一行行项。
         *
         * @param docId  表头ID
         * @param itemId 行项主键
         */
        void deleteItem(String docId, String itemId);

        /**
         * 重排序号。
         *
         * @param docId 表头ID
         * @return 重排后的行项集合
         */
        List<ErpPurchaseOrderItem> resequence(String docId);
    }

    /* ==================== 过账回写（任务 3.5；本任务只声明契约） ==================== */

    /**
     * <p> 已入库量的变动入口（任务 3.5 的"过账累加 / 红冲回退且不小于 0"）。 </p>
     *
     * <p> ⚠ 本任务（3.1~3.4、3.6）<b>不</b>实现"何时调用"，只把口径钉在这里：
     * 调用时机 = 入库单<b>过账</b>时；回退 = 入库单红冲时且回退后不得为负。
     * 具体落点由 t4b-procurement-push-in 在过账服务里接。 </p>
     *
     * @param orderId 采购单ID
     * @param itemId  采购单行项ID
     * @param deltaQty 变动数量（正=过账累加，负=红冲回退）
     */
    void applyReceivedQtyChange(String orderId, String itemId, BigDecimal deltaQty);
}
