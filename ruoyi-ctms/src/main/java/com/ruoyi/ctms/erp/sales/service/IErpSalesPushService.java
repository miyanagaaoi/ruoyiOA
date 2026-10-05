package com.ruoyi.ctms.erp.sales.service;

import java.util.Map;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushRequest;

/**
 * <p> <b>销售下推服务</b>（2.0 B4 任务 4.2：销售申请 → 销售订单）。 </p>
 *
 * <p> <b>四步都在同一个事务里</b>（design D7、移植清单 §3.9.4）： </p>
 * <ol>
 *   <li> 前置校验：来源单必须是<b>已审核</b>的销售申请单，且每个下推行都不超剩余量
 *        （剩余量 = 行数量 − 已下推量）；任何一条不通过<b>整体不写入</b>； </li>
 *   <li> 生成<b>草稿</b>销售订单，写 {@code source_doc_type/id/no}，行项写 {@code src_item_id}； </li>
 *   <li> <b>立即累加</b>申请行项的 {@code ordered_qty}（不等过账：申请单没有过账时点）； </li>
 *   <li> 归零即完成：全部行项剩余量为 0 时把申请单置 {@code completed} 并留痕（幂等）。 </li>
 * </ol>
 *
 * <p> 采购线（{@code t4a}）的销售镜像实现，两边的步骤与文案保持同构。 </p>
 *
 * @author 二开
 */
public interface IErpSalesPushService
{
    /**
     * 销售申请 → 销售订单（生成草稿订单）。
     *
     * @param requestDocId 来源销售申请单ID
     * @param request      下推参数（{@code null} 表示全部行项按剩余量全推）
     * @return 结果视图：{@code orderId} / {@code orderNo} / {@code sourceDocNo} /
     *         {@code pushedQtySum} / {@code requestCompleted} / {@code lines}
     */
    Map<String, Object> pushSalesRequestToOrder(String requestDocId, ErpSalesPushRequest request);

    /**
     * <p> <b>归零即完成判定</b>（幂等）：全部行项剩余可下推量为 0 时把申请单置为已完成并留痕。 </p>
     *
     * <p> 幂等判据是"当前状态不是 completed 才写"，因此重复调用不产生第二条变更历史、
     * 也不改状态（tasks.md §4.2 的"重复判定不重复留痕"）。 </p>
     *
     * @param requestDocId 销售申请单ID
     * @return 本次是否发生了状态置位（true = 刚置为已完成）
     */
    boolean checkAutoComplete(String requestDocId);

    /**
     * <p> <b>销售订单 → 出库单下推</b>（2.0 B4 任务 4.3；规格 {@code erp/sales} 的
     * 「销售订单下推出库单」）。 </p>
     *
     * <p> <b>七条冻结口径</b>： </p>
     * <ol>
     *   <li> 仅<b>已审核或已完成</b>的销售订单可推； </li>
     *   <li> 下推量不得超过该行的剩余可出库量（{@code qty − shipped_qty}），
     *        超量<b>整单拒绝</b>且零中间写入； </li>
     *   <li> 未指定数量（{@code null} 或 ≤ 0）按剩余量<b>全推</b>； </li>
     *   <li> 生成<b>草稿</b>出库单：表头写发货仓库（入参优先 → 订单的默认发货仓库）、
     *        出库类型 {@code 销售出库}、客户名称<b>快照</b>、
     *        {@code source_doc_type=sales_order} + 来源单号；行项写 {@code src_item_id} = 销售订单行项ID； </li>
     *   <li> <b>下推本身不写 {@code shipped_qty}</b>：该数量只在出库单<b>过账成功</b>时由
     *        {@code ErpSalesOutboundListener} 回写、红冲时回退且不小于 0（design D7）； </li>
     *   <li> 单号/行项快照/金额口径全部复用 T3 的 {@code IErpStockOutService.insertStockOut}
     *        （本组不复刻）； </li>
     *   <li> <b>仓库必须在推的时刻就解析出来</b>：{@code t_ctms_stock_out.warehouse_id} 是
     *        NOT NULL + FK（DDL 冻结件），缺仓库的草稿在真库上根本插不进去（1048/1452），
     *        所以此处直接拒绝并给出中文原因，而不是留一张插不进去的草稿。 </li>
     * </ol>
     *
     * @param orderDocId 销售订单ID
     * @param request    下推参数（{@code lines} 空 = 全部行按剩余量全推；
     *                   {@code shipWarehouseId} 可作"本次出库仓库"覆盖订单的默认发货仓库；可空）
     * @return 结果视图：{@code stockOutId} / {@code stockOutNo} / {@code sourceDocId} /
     *         {@code sourceDocNo} / {@code pushedQtySum} / {@code remainQtySum} / {@code lines}
     */
    java.util.Map<String, Object> pushSalesOrderToStockOut(String orderDocId, ErpSalesPushRequest request);
}
