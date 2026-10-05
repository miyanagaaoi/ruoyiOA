package com.ruoyi.ctms.erp.procurement.service;

import java.util.List;

import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushInResultVo;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushResultVo;

/**
 * <p> 采购申请单 → 采购单 下推服务（2.0 B4 任务 3.3、3.4）。 </p>
 *
 * <p> <b>下推的六个冻结口径</b>（规格 {@code specs/erp/procurement/spec.md}）： </p>
 * <ol>
 *   <li> 仅<b>已审核</b>的申请单可推；</li>
 *   <li> 下推量不得超过该行剩余可下推量（原始 − 已下单），超量整单拒绝且已下单量不变；</li>
 *   <li> 未指定数量（{@code null} 或 ≤ 0）按剩余量<b>全推</b>；</li>
 *   <li> 下推<b>立即</b>累加申请行已下单量（不等过账）；</li>
 *   <li> 生成<b>草稿</b>采购单，写来源单号与来源行标识（{@code source_doc_*} / {@code src_item_id}）；</li>
 *   <li> 默认带过合同（{@code contract_id/contract_no}）、采购部门与预计到货日期
 *        （来自申请单的 {@code request_dept_id} / {@code need_date}）。</li>
 * </ol>
 *
 * <p> 下推成功后接"归零即完成"：全部行项剩余量为 0 时申请单自动置为已完成并留痕（幂等）。 </p>
 *
 * @author 二开
 */
public interface IErpPurchasePushService
{
    /**
     * 把采购申请单下推为一张草稿采购单。
     *
     * @param requestId  申请单ID
     * @param lines      下推行集合；<b>null / 空</b> 表示按全部行项（各按剩余量全推）
     * @param supplierId 目标采购单供应商（任务 3.2 的口径一并校验；为空则拒绝）
     * @param remark     采购单备注（可空）
     * @return 下推结果（含草稿采购单、来源信息、是否触发自动完成）
     */
    ErpPushResultVo pushToPurchaseOrder(String requestId, List<ErpPushLine> lines,
                                       String supplierId, String remark);

    /**
     * <p> 把采购单下推为一张草稿入库单（2.0 B4 任务 3.5）。 </p>
     *
     * <p> 冻结口径（规格 {@code specs/erp/procurement/spec.md} 的「采购单 → 入库单下推」）： </p>
     * <ol>
     *   <li> 仅<b>已审核或已完成</b>的采购单可推；</li>
     *   <li> 下推量不得超过该行的剩余可入库量（原始 − 已入库），超量整单拒绝且零中间写入；</li>
     *   <li> 未指定数量（{@code null} 或 ≤ 0）按剩余量<b>全推</b>；</li>
     *   <li> 生成<b>草稿</b>入库单，带收货仓库与来源单号：表头 {@code warehouse_id} 取
     *        {@code warehouseId} 入参（不传时回落采购单的默认收货仓库）、
     *        {@code source_doc_type=purchase_order} + 来源单号；行项 {@code src_item_id} = 采购单行项ID；</li>
     *   <li> 入库类型默认 {@code 采购入库}（流水 {@code biz_type} 的真源）；</li>
     *   <li> <b>下推本身不写 {@code received_qty}</b>：该数量由入库单<b>过账</b>时的监听回写
     *        （见 {@code ErpPurchaseReceiptListener}），红冲时回退且不小于 0。</li>
     * </ol>
     *
     * @param orderId     采购单ID
     * @param lines       下推行集合（{@code srcItemId} = 采购单行项ID）；<b>null / 空</b> 表示全部行按剩余量全推
     * @param warehouseId 收货仓库ID（可空：空则回落采购单的默认收货仓库；两者都空时草稿允许暂缺，
     *                    审核阶段按行报"缺少仓库"）
     * @param remark      入库单备注（可空）
     * @return 下推结果（含草稿入库单与提示文案）
     */
    ErpPushInResultVo pushToStockIn(String orderId, List<ErpPushLine> lines,
                                    String warehouseId, String remark);
}
