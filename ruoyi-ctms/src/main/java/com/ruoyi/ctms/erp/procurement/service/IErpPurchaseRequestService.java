package com.ruoyi.ctms.erp.procurement.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;

/**
 * <p> 采购申请单服务（2.0 B4 任务 3.1~3.4、3.6）。 </p>
 *
 * <p> 分层约定（与 B3 的 {@code ICtmsContractService} 一致）： </p>
 * <ul>
 *   <li> 列表/详情是<b>只读</b>：列表额外现算"剩余可下推量合计"与"是否可下推"两个派生字段；</li>
 *   <li> 新增/编辑<b>只允许草稿</b>（{@code ErpDocStateMachine.checkEditable}）；</li>
 *   <li> 状态流转全部走一个入口 {@link #changeStatus(String, String, String)}，
 *        非法流转由 {@code ErpDocStateMachine} 拒绝，服务层<b>不写</b> {@code if(status.equals(...))}；</li>
 *   <li> 行项独立维护入口（{@link ItemWriter}）供前端行项表逐行增删改；
 *        单据保存时的行项集合走 {@code request.items}。</li>
 * </ul>
 *
 * @author 二开
 */
public interface IErpPurchaseRequestService
{
    /* ==================== 查询 ==================== */

    /**
     * 采购申请单列表（状态筛选、默认排除已作废、关键词与日期筛选、数据范围）。
     *
     * @param query 查询条件
     * @return 单据集合（已装配 {@code remainQtySum} / {@code canPush} / {@code totalAmount}）
     */
    List<ErpPurchaseRequest> selectRequestList(ErpPurchaseRequest query);

    /**
     * 采购申请单详情（含行项与变更历史）。
     *
     * @param id 主键
     * @return 单据
     */
    ErpPurchaseRequest selectRequestDetail(String id);

    /**
     * 取某申请单的行项（前端行项表单独刷新用）。
     *
     * @param docId 表头ID
     * @return 行项集合
     */
    List<ErpPurchaseRequestItem> selectItemsByDocId(String docId);

    /**
     * 单据变更历史（详情页时间线）。
     *
     * @param docId 主键
     * @return 日志集合
     */
    List<com.ruoyi.ctms.domain.CtmsChangeLog> selectRequestChangeLogs(String docId);

    /* ==================== 新增 / 编辑 / 删除 ==================== */

    /**
     * 新增采购申请单（草稿）。
     *
     * @param request 单据（行项走 {@code request.items}）
     * @return 新增后的主键
     */
    String insertRequest(ErpPurchaseRequest request);

    /**
     * 编辑采购申请单（仅草稿可编辑；行项全量替换）。
     *
     * @param request 单据
     */
    void updateRequest(ErpPurchaseRequest request);

    /**
     * 物理删除单据（仅草稿，且仅用于回滚演练与夹具清理；业务侧走 {@code void}）。
     *
     * @param id 主键
     */
    void deleteRequest(String id);

    /* ==================== 状态流转 ==================== */

    /**
     * 状态流转（提交 / 审核 / 驳回 / 作废 / 反审核 / 置为已完成）。
     *
     * @param id     主键
     * @param action 动作码（{@code ErpDocAction.getActionCode()}）
     * @param reason 原因（驳回 / 作废 / 反审核必填）
     */
    void changeStatus(String id, String action, String reason);

    /**
     * <p> <b>归零即完成</b>（任务 3.4）：全部行项剩余可下推量为 0 时把申请单置为已完成并留痕。 </p>
     *
     * <p> 幂等：只有"当前状态不是 completed 且确实归零"才写状态与日志；重复调用不产生第二条留痕。 </p>
     *
     * @param id 主键
     * @return 本次是否真的发生了状态变更（用于前端提示"该申请已全部下推完成"）
     */
    boolean checkAutoComplete(String id);

    /* ==================== 行项独立维护 ==================== */

    /**
     * 行项单独维护入口（前端行项表格的增/改/删；单据级保存走 {@code request.items}）。
     */
    interface ItemWriter
    {
        /**
         * 新增一行行项（校验与快照口径与单据保存完全一致）。
         *
         * @param docId 表头ID
         * @param item  行项
         * @return 行项主键
         */
        String insertItem(String docId, ErpPurchaseRequestItem item);

        /**
         * 修改一行行项。
         *
         * @param docId 表头ID
         * @param item  行项（须含 id）
         */
        void updateItem(String docId, ErpPurchaseRequestItem item);

        /**
         * 删除一行行项。
         *
         * @param docId  表头ID
         * @param itemId 行项主键
         */
        void deleteItem(String docId, String itemId);

        /**
         * 重排序号（删除后序号出现空洞时调用；从 1 连续）。
         *
         * @param docId 表头ID
         * @return 重排后的行项集合
         */
        List<ErpPurchaseRequestItem> resequence(String docId);
    }

    /* ==================== 供下推服务使用的写入口 ==================== */

    /**
     * <p> 下推即累加已下单量（任务 3.3）。 </p>
     *
     * <p> 由下推服务在同一事务内调用，按 {@code 行项ID → 本次下推数量} 逐行累加；
     * 累加用库内自增（并发安全），不是"读出来再写"。 </p>
     *
     * @param docId 申请单ID
     * @param rows  行项ID → 本次下推数量
     * @return 累加后各行项的剩余量合计（供"归零即完成"判定与列表提示）
     */
    BigDecimal increaseOrderedQty(String docId, Map<String, BigDecimal> rows);
}
