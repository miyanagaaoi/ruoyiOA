package com.ruoyi.ctms.erp.sales.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;

/**
 * <p> <b>销售申请单服务</b>（2.0 B4 任务 4.1；规格 {@code erp/sales}）。 </p>
 *
 * <p> 状态动作的口径与 8 类单据完全一致（{@code base} 包的 {@code ErpDocStateMachine}）：
 * 提交仅草稿且必须有行项、审核仅待审核、驳回仅待审核（原因必填）、作废仅草稿或待审核
 * （原因必填）、反审核仅已审核或已完成（原因必填）、置完成仅已审核。 </p>
 *
 * <p> 只有草稿可编辑（{@code ErpDocStateMachine.checkEditable}）；
 * 列表默认排除已作废。 </p>
 *
 * @author 二开
 */
public interface IErpSalesRequestService
{
    /**
     * 销售申请单列表（状态、关键字、单据日期区间、是否含已作废、数据范围）。
     *
     * @param query 查询条件
     * @return 单据集合（每条附 {@code remainingQtySum} 供下推按钮显隐）
     */
    List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query);

    /**
     * 按主键查销售申请单（附行项与变更历史）。
     *
     * @param id 主键
     * @return 单据；不存在返回 null
     */
    ErpSalesRequest selectSalesRequestById(String id);

    /**
     * 按单据ID取行项（下推对话框与断言清单用；不查表头）。
     *
     * @param docId 单据ID
     * @return 行项集合（非 null）
     */
    List<ErpSalesRequestItem> selectSalesRequestItems(String docId);

    /**
     * <p> 按<b>单据ID集合</b>批量取行项（<b>导出用</b>，避免逐条查询的 N+1）。 </p>
     *
     * @param docIds 单据ID集合（可空/空 → 返回空 Map）
     * @return {@code docId → 行项列表}（非 null；无行项的单据不出现键）
     */
    java.util.Map<String, List<ErpSalesRequestItem>> selectSalesRequestItemsByDocIds(List<String> docIds);

    /**
     * 新增销售申请单（草稿）。
     *
     * @param doc 单据（含行项）
     * @return 落库后的单据（含生成的 id 与单号）
     */
    ErpSalesRequest insertSalesRequest(ErpSalesRequest doc);

    /**
     * 修改销售申请单（仅草稿）。
     *
     * @param doc 单据（含行项）
     * @return 落库后的单据
     */
    ErpSalesRequest updateSalesRequest(ErpSalesRequest doc);

    /**
     * 删除销售申请单（仅草稿；草稿可直接物理删除，已提交/已审核只能作废）。
     *
     * @param id 主键
     */
    void deleteSalesRequestById(String id);

    /**
     * 提交（草稿 → 待审核；要求至少一行行项）。
     *
     * @param id     主键
     * @param remark 提交备注（可空）
     */
    void submitSalesRequest(String id, String remark);

    /**
     * 审核（待审核 → 已审核）。
     *
     * @param id 主键
     */
    void approveSalesRequest(String id);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     主键
     * @param reason 原因
     */
    void rejectSalesRequest(String id, String reason);

    /**
     * 作废（草稿/待审核 → 已作废；原因必填）。
     *
     * @param id     主键
     * @param reason 原因
     */
    void voidSalesRequest(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填）。与采购线一致：申请单不产生库存动作，无需红冲。
     *
     * @param id     主键
     * @param reason 原因
     */
    void unapproveSalesRequest(String id, String reason);

    /**
     * <p> 手工置为已完成（已审核 → 已完成）。 </p>
     *
     * <p> 与"归零即完成"并存：手工置位用于业务上提前关闭申请单；
     * 自动置位见 {@code IErpSalesPushService.checkAutoComplete}。 </p>
     *
     * @param id 主键
     */
    void completeSalesRequest(String id);

    /**
     * 列表用的剩余可下推量合计（前端下推按钮显隐）。
     *
     * @param docId 单据ID
     * @return 3 位合计
     */
    java.math.BigDecimal remainingQtySum(String docId);

    /**
     * <p> <b>关联合同（仅销售方向）</b>（任务 4.4）。 </p>
     *
     * <p> 校验：合同存在且未停用、方向必须是销售方向（采购方向被拒）、
     * 保存合同编号快照、<b>不回写合同任何字段</b>；传 {@code null} 表示解除关联。 </p>
     *
     * @param id         单据ID
     * @param contractId 合同ID（可空：解除关联）
     * @return 保存后的合同编号快照（解除关联时返回 null）
     */
    String linkContract(String id, String contractId);

    /**
     * <p> 按单据ID取合同关联信息（{@code contractId} / {@code contractNo} 快照）。 </p>
     *
     * @param id 单据ID
     * @return 形如 {@code {contractId, contractNo}} 的只读视图；单据不存在返回 null
     */
    Map<String, Object> contractRefOf(String id);
}
