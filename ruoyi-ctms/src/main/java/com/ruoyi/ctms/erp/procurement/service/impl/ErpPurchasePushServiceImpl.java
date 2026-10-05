package com.ruoyi.ctms.erp.procurement.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.service.IErpStockInService;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPushLine;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushInResultVo;
import com.ruoyi.ctms.erp.procurement.domain.vo.ErpPushResultVo;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseOrderService;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchasePushService;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseRequestService;

/**
 * <p> 采购线下推服务实现：<b>申请 → 采购单</b>（任务 3.3/3.4）与 <b>采购单 → 入库单</b>（任务 3.5）。 </p>
 *
 * <p> <b>事务边界</b>：每段在<b>一个事务</b>里。超量时<b>在写任何东西之前</b>就拒绝，
 * 因此"被拒后已下单量/已入库量不变、下游单据表不变"是结构性成立的（不是靠回滚兜底）。 </p>
 *
 * <p> <b>为什么"预校验一遍再落库"</b>：参考实现的顺序是"逐行校验并立即累加"，
 * 那样第 2 行超量时第 1 行已经写了（靠事务回滚）。本实现先把全部行算完、
 * 校验通过，再统一落库 —— 失败路径上没有任何中间写入。 </p>
 *
 * <p> <b>两段下推的关键差别（任务 3.5）</b>：申请→采购单是<b>即时</b>累加 {@code ordered_qty}；
 * 采购单→入库单<b>不写</b> {@code received_qty} —— 那个数量只在入库单<b>过账</b>时由
 * {@code ErpPurchaseReceiptListener} 回写，红冲时回退。因此本类的 {@code pushToStockIn}
 * 只创建草稿入库单（含行 {@code src_item_id}），不碰采购单的任何列。 </p>
 *
 * <p> <b>与状态机的关系</b>：本服务不改申请单的"已审核"，只在归零时把 {@code approved}
 * 置为 {@code completed}（规格「归零即完成」）；该判定与留痕委托
 * {@code IErpPurchaseRequestService.checkAutoComplete}（幂等、单独一处实现）。 </p>
 *
 * @author 二开
 */
@Service
public class ErpPurchasePushServiceImpl implements IErpPurchasePushService
{
    /** 申请单服务（取详情 + 下推写入口）。 */
    @Autowired
    private IErpPurchaseRequestService requestService;

    /** 采购单服务（落草稿采购单；供应商/合同的校验都在它的 insert 路径上）。 */
    @Autowired
    private IErpPurchaseOrderService orderService;

    /**
     * 入库单服务（T3 交付；<b>只消费、不复刻</b>）：草稿入库单由它创建（单号、快照、行项归一
     * 都在那边实现）。为 null（未装配）时下推显式失败，不自己写入库单表。
     */
    @Autowired(required = false)
    private IErpStockInService stockInService;

    /** 主数据守卫（解析收货仓库的名称；可空以便脱库单测）。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPushResultVo pushToPurchaseOrder(String requestId, List<ErpPushLine> lines,
                                              String supplierId, String remark)
    {
        // 申请单服务内部会做"存在 + 数据范围"校验（范围外抛 403）
        ErpPurchaseRequest request = requestService.selectRequestDetail(requestId);
        // ① 仅有"已审核"可推（规格场景「未审核的申请单不可下推」）
        if (!ErpDocStatus.APPROVED.equals(request.getStatus()))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_APPROVED_PUSH);
        }
        List<ErpPurchaseRequestItem> sourceItems = request.getItems() == null
                ? new ArrayList<ErpPurchaseRequestItem>() : request.getItems();
        if (sourceItems.isEmpty())
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        // ② 选定要下推的行并算出本次数量（"行序对应"只在这里解释一次，
        //    下面的落库阶段只消费 plan，不再二次匹配 —— 两处各匹配一次必然漂移）
        List<PushPlan> plan = buildPlan(sourceItems, lines);
        if (plan.isEmpty())
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        // ③ 逐行算量并校验（全部算完再落库：失败路径零中间写入）
        List<ErpPurchaseOrderItem> orderItems = new ArrayList<>(plan.size());
        Map<String, BigDecimal> orderedDelta = new LinkedHashMap<>();
        BigDecimal pushedTotal = BigDecimal.ZERO;
        int seq = 0;
        for (PushPlan row : plan)
        {
            seq++;
            ErpPurchaseRequestItem source = row.source;
            BigDecimal remaining = ErpPurRules.remainingQtyOf(source);
            // 本次数量（口径与销售线同构）：剩余量为 0 → 明确拒绝；未填/≤0 → 按剩余量全推
            BigDecimal pushQty = ErpPurRules.resolvePushQty(row.requestedQty, remaining);
            // 超量整单拒绝：提示里带剩余量与本次量（规格场景逐字口径）
            ErpPurRules.checkPushQty(remaining, pushQty, ErpDocType.PURCHASE_REQUEST.getLabel(),
                    source.getProductName());
            ErpPurchaseOrderItem line = buildOrderItem(source, pushQty, seq);
            orderItems.add(line);
            orderedDelta.put(source.getId(), pushQty);
            pushedTotal = pushedTotal.add(pushQty);
        }
        if (pushedTotal.signum() <= 0)
        {
            throw new ServiceException(ErpPurRules.MSG_PUSH_QTY_POSITIVE);
        }
        // ④ 生成草稿采购单：来源单号 + 来源行标识 + 默认带过合同/部门/需求日期
        ErpPurchaseOrder order = buildOrder(request, orderItems, supplierId, remark);
        orderService.insertOrder(order);
        // ⑤ 立即累加已下单量（不等过账；库内自增）
        requestService.increaseOrderedQty(request.getId(), orderedDelta);
        // ⑥ 归零即完成（幂等）
        boolean autoCompleted = requestService.checkAutoComplete(request.getId());
        ErpPurchaseRequest after = requestService.selectRequestDetail(request.getId());
        BigDecimal remainSum = after.getRemainQtySum() == null ? BigDecimal.ZERO : after.getRemainQtySum();
        ErpPushResultVo vo = new ErpPushResultVo();
        vo.setOrder(order);
        vo.setSourceDocId(request.getId());
        vo.setSourceDocNo(request.getDocNo());
        vo.setAutoCompleted(autoCompleted);
        vo.setSourceStatus(after.getStatus());
        vo.setRemainQtySum(remainSum);
        vo.setPushedQty(ErpAmounts.roundQty(pushedTotal));
        vo.setMessage(String.format(java.util.Locale.ROOT,
                autoCompleted ? ErpPurRules.MSG_PUSH_AUTO_COMPLETE_TEMPLATE : ErpPurRules.MSG_PUSH_OK_TEMPLATE,
                order.getDocNo(), ErpAmounts.textOf(ErpAmounts.roundQty(pushedTotal))));
        return vo;
    }

    /* ==================== 采购单 → 入库单（任务 3.5） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPushInResultVo pushToStockIn(String orderId, List<ErpPushLine> lines,
                                          String warehouseId, String remark)
    {
        if (stockInService == null)
        {
            // 未装配入库单服务时不能"放行"：宁可拒绝，也不自己写入库单表（跨包直写是禁忌）
            throw new ServiceException("入库单服务未装配，无法下推入库单");
        }
        // 采购单服务内部做"存在 + 数据范围"校验（范围外抛 403）
        ErpPurchaseOrder order = orderService.selectOrderDetail(orderId);
        // ① 仅"已审核或已完成"可推（规格：采购单 → 入库单下推的前置）
        String status = ErpPurRules.trimToNull(order.getStatus());
        if (!ErpDocStatus.APPROVED.equals(status) && !ErpDocStatus.COMPLETED.equals(status))
        {
            throw new ServiceException(ErpPurRules.MSG_ORDER_NOT_PUSHABLE_IN);
        }
        List<ErpPurchaseOrderItem> sourceItems = order.getItems() == null
                ? new ArrayList<ErpPurchaseOrderItem>() : order.getItems();
        if (sourceItems.isEmpty())
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        // ② 收货仓库必须在**下推时**就解析出来：t_ctms_stock_in.warehouse_id 是
        //    NOT NULL + FK（DDL 冻结件），草稿也不能缺仓库——缺仓库在真库上是 1048，
        //    而不是"审核时才被拒"（详见 notes/04b 的 DDL 约束小节）
        ErpMasterGuards.MasterRecord warehouse = resolveReceiptWarehouse(order, warehouseId);
        // ③ 解析下推计划并算量（全部算完才落库：失败路径零中间写入）
        List<ReceivePlan> plan = buildReceivePlan(sourceItems, lines);
        List<ErpStockInItem> stockInItems = new ArrayList<>(plan.size());
        BigDecimal pushedTotal = BigDecimal.ZERO;
        BigDecimal remainingAfter = BigDecimal.ZERO;
        int seq = 0;
        for (ReceivePlan row : plan)
        {
            seq++;
            ErpPurchaseOrderItem source = row.source;
            BigDecimal remaining = ErpPurRules.remainingReceiveQtyOf(source);
            BigDecimal pushQty = ErpPurRules.resolveReceiveQty(row.requestedQty, remaining);
            ErpPurRules.checkReceiveQty(remaining, pushQty, ErpDocType.PURCHASE_ORDER.getLabel(),
                    source.getProductName());
            stockInItems.add(buildStockInItem(source, pushQty, seq));
            pushedTotal = pushedTotal.add(pushQty);
            remainingAfter = remainingAfter.add(remaining.subtract(pushQty));
        }
        if (pushedTotal.signum() <= 0)
        {
            throw new ServiceException(ErpPurRules.MSG_IN_QTY_POSITIVE);
        }
        // ④ 生成草稿入库单（单号/快照/行项归一都在 T3 的 insertStockIn 里，本组不复刻）
        ErpStockIn stockIn = buildStockIn(order, stockInItems, warehouse, remark);
        ErpStockIn saved = stockInService.insertStockIn(stockIn);
        // ⑤ 注意：**不写** received_qty。已入库量只在入库单"过账"时由
        //    ErpPurchaseReceiptListener 回写（规格：过账时累加、红冲回退）
        ErpPushInResultVo vo = new ErpPushInResultVo();
        vo.setStockIn(saved);
        vo.setSourceDocId(order.getId());
        vo.setSourceDocNo(order.getDocNo());
        vo.setPushedQty(ErpAmounts.roundQty(pushedTotal));
        vo.setRemainQtySum(ErpAmounts.roundQty(remainingAfter));
        vo.setMessage(String.format(java.util.Locale.ROOT, ErpPurRules.MSG_PUSH_IN_OK_TEMPLATE,
                saved.getDocNo(), ErpAmounts.textOf(ErpAmounts.roundQty(pushedTotal))));
        return vo;
    }

    /**
     * 解析收货仓库：入参优先，其次采购单的默认收货仓库；都没有时<b>拒绝下推</b>。
     *
     * <p> 为什么不是"先建草稿、审核时再报缺仓库"：{@code t_ctms_stock_in.warehouse_id} 是
     * NOT NULL + FK（DDL 冻结件），没有仓库的草稿在真库上根本插不进去（1048/1452），
     * 报错形态会变成一个难懂的数据库异常。 </p>
     *
     * @param order       采购单
     * @param warehouseId 入参指定的仓库ID（可空）
     * @return 仓库摘要（含名称快照）
     * @throws ServiceException 两者都空 / 仓库不存在或已停用
     */
    protected ErpMasterGuards.MasterRecord resolveReceiptWarehouse(ErpPurchaseOrder order, String warehouseId)
    {
        String target = ErpPurRules.trimToNull(warehouseId);
        if (target == null)
        {
            target = ErpPurRules.trimToNull(order.getReceiptWarehouseId());
        }
        if (target == null)
        {
            throw new ServiceException(ErpPurRules.MSG_RECEIPT_WAREHOUSE_REQUIRED);
        }
        // 复用 base 的守卫：存在 + 启用（文案与其它单据一致）
        return ErpMasterGuards.requireEnabledWarehouse(masterLookup, target);
    }

    /**
     * <p> 解析采购单 → 入库单的下推计划（来源行 + 本次请求数量）。 </p>
     *
     * <p> 三条语义与申请→采购单<b>完全同构</b>（只解释一次，落库阶段只消费计划）： </p>
     * <ol>
     *   <li> {@code lines} 为空 → 全部行，各按剩余可入库量全推；</li>
     *   <li> 某条的 {@code srcItemId} 为空 → 按<b>行序</b>对应到下一条未匹配的采购单行；</li>
     *   <li> {@code srcItemId} 非空但不在采购单行里 → 明确拒绝（不静默跳过）。 </li>
     * </ol>
     *
     * @param sourceItems 采购单行（按序号升序）
     * @param lines       下推行（可空）
     * @return 计划（保持行序、按采购单行去重）
     */
    protected List<ReceivePlan> buildReceivePlan(List<ErpPurchaseOrderItem> sourceItems, List<ErpPushLine> lines)
    {
        List<ReceivePlan> plan = new ArrayList<>();
        if (lines == null || lines.isEmpty())
        {
            for (ErpPurchaseOrderItem item : sourceItems)
            {
                plan.add(new ReceivePlan(item, null));
            }
            return plan;
        }
        Map<String, ErpPurchaseOrderItem> byId = new LinkedHashMap<>();
        for (ErpPurchaseOrderItem item : sourceItems)
        {
            byId.put(item.getId(), item);
        }
        int cursor = 0;
        for (ErpPushLine line : lines)
        {
            String srcId = line == null ? null : ErpPurRules.trimToNull(line.getSrcItemId());
            ErpPurchaseOrderItem matched;
            if (srcId == null)
            {
                matched = cursor < sourceItems.size() ? sourceItems.get(cursor) : null;
                cursor++;
            }
            else
            {
                matched = byId.get(srcId);
            }
            if (matched == null)
            {
                throw new ServiceException(ErpPurRules.MSG_IN_ITEM_NOT_FOUND);
            }
            ReceivePlan exist = findReceivePlan(plan, matched);
            BigDecimal requested = line == null ? null : line.getQty();
            if (exist == null)
            {
                plan.add(new ReceivePlan(matched, requested));
            }
            else
            {
                exist.requestedQty = requested;
            }
        }
        return plan;
    }

    /**
     * 在计划里查找某个采购单行。
     *
     * @param plan   计划
     * @param source 采购单行
     * @return 已存在的计划项；没有返回 null
     */
    protected ReceivePlan findReceivePlan(List<ReceivePlan> plan, ErpPurchaseOrderItem source)
    {
        for (ReceivePlan row : plan)
        {
            if (row.source == source || (row.source != null && row.source.getId() != null
                    && row.source.getId().equals(source.getId())))
            {
                return row;
            }
        }
        return null;
    }

    /**
     * 采购单 → 入库单的下推计划项。
     */
    protected static class ReceivePlan
    {
        /** 来源采购单行。 */
        private final ErpPurchaseOrderItem source;

        /** 本次请求数量（可空 / ≤0 表示按剩余量全推）。 */
        private BigDecimal requestedQty;

        ReceivePlan(ErpPurchaseOrderItem source, BigDecimal requestedQty)
        {
            this.source = source;
            this.requestedQty = requestedQty;
        }
    }

    /**
     * 由采购单行构造入库单行：数量换成下推量、行金额按"先舍入到 2 位"重算，
     * 并写<b>来源行标识</b>（{@code src_item_id} = 采购单行项ID）。
     *
     * <p> 物料/单位/仓库快照由 T3 的 {@code insertStockIn} 再解析一次（它的
     * {@code normalizeItems} 调 {@code ErpMasterGuards.applyItemSnapshot}），
     * 所以这里只带"业务语义"字段：物料、数量、单价、行级收货仓库、来源行、备注。 </p>
     *
     * @param source  采购单行
     * @param pushQty 本次下推量
     * @param seq     序号（从 1 连续）
     * @return 入库单行
     */
    protected ErpStockInItem buildStockInItem(ErpPurchaseOrderItem source, BigDecimal pushQty, int seq)
    {
        ErpStockInItem line = new ErpStockInItem();
        line.setProductId(source.getProductId());
        line.setQty(ErpAmounts.roundQty(pushQty));
        line.setUnitPrice(ErpAmounts.roundPrice(source.getUnitPrice()));
        // 行级收货仓库：采购单行上有就带过（否则留空，由 T3 回落表头）
        line.setWarehouseId(ErpPurRules.trimToNull(source.getWarehouseId()));
        // 来源行标识：入库单过账/红冲时据此回写采购单行的 received_qty
        line.setSrcItemId(source.getId());
        line.setRemark(source.getRemark());
        line.setSeq(Integer.valueOf(seq));
        ErpAmounts.applyLineAmount(line);
        return line;
    }

    /**
     * 由采购单构造草稿入库单（带收货仓库与来源单号）。
     *
     * @param order       来源采购单
     * @param items       入库单行
     * @param warehouse   收货仓库（已解析）
     * @param remark      备注（可空；空则沿用采购单备注）
     * @return 草稿入库单
     */
    protected ErpStockIn buildStockIn(ErpPurchaseOrder order, List<ErpStockInItem> items,
                                      ErpMasterGuards.MasterRecord warehouse, String remark)
    {
        ErpStockIn stockIn = new ErpStockIn();
        stockIn.setDocDate(order.getDocDate());
        stockIn.setWarehouseId(warehouse.getId());
        stockIn.setWarehouseName(warehouse.getName() == null ? "" : warehouse.getName());
        stockIn.setInType(ErpPurRules.DEFAULT_IN_TYPE);
        // 供应商快照从采购单带过（入库单本身也存供应商引用与名称）
        stockIn.setSupplierId(order.getSupplierId());
        stockIn.setSupplierName(order.getSupplierName());
        // 来源三列：入库单的"从哪来"（单号 + 行标识是端到端追溯的唯一依据）
        stockIn.setSourceDocType(ErpDocType.PURCHASE_ORDER.getCode());
        stockIn.setSourceDocId(order.getId());
        stockIn.setSourceDocNo(order.getDocNo());
        // 合同带过（非强制，仅追溯）
        stockIn.setContractId(order.getContractId());
        stockIn.setContractNo(order.getContractNo());
        stockIn.setHandlerUserId(order.getHandlerUserId());
        stockIn.setHandlerName(order.getHandlerName());
        stockIn.setRemark(ErpPurRules.trimToNull(remark) == null ? order.getRemark() : remark);
        stockIn.setItems(items);
        return stockIn;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * <p> 解析本次下推计划（来源行 + 本次请求数量）。 </p>
     *
     * <p> 三条语义（与 {@code push_service.py} 的 {@code rows=None} 前推口径一致）： </p>
     * <ol>
     *   <li> {@code lines} 为空 → <b>全部行项</b>，各自按剩余量全推；</li>
     *   <li> 某条的 {@code srcItemId} 为空 → 按<b>行序</b>对应到未匹配的来源行；</li>
     *   <li> {@code srcItemId} 非空但不在来源行里 → 明确拒绝（不静默跳过：跳过会让
     *        "本次下推 1 行、结果生成 0 行"变成一个难查的静默失败）。 </li>
     * </ol>
     *
     * <p> <b>只在这里解释一次"行序对应"</b>：落库阶段只消费本方法的结果，
     * 不再做第二次匹配（两处各匹配一次必然漂移）。 </p>
     *
     * @param sourceItems 来源行项（按序号升序）
     * @param lines       下推行集合（可空）
     * @return 计划（保持行序、按来源行去重）
     */
    protected List<PushPlan> buildPlan(List<ErpPurchaseRequestItem> sourceItems, List<ErpPushLine> lines)
    {
        List<PushPlan> plan = new ArrayList<>();
        if (lines == null || lines.isEmpty())
        {
            for (ErpPurchaseRequestItem item : sourceItems)
            {
                plan.add(new PushPlan(item, null));
            }
            return plan;
        }
        Map<String, ErpPurchaseRequestItem> byId = new LinkedHashMap<>();
        for (ErpPurchaseRequestItem item : sourceItems)
        {
            byId.put(item.getId(), item);
        }
        int cursor = 0;
        for (ErpPushLine line : lines)
        {
            String srcId = line == null ? null : ErpPurRules.trimToNull(line.getSrcItemId());
            ErpPurchaseRequestItem matched;
            if (srcId == null)
            {
                // 按行序对应：取下一个尚未被选中的来源行
                matched = cursor < sourceItems.size() ? sourceItems.get(cursor) : null;
                cursor++;
            }
            else
            {
                matched = byId.get(srcId);
            }
            if (matched == null)
            {
                throw new ServiceException(ErpPurRules.MSG_PUSH_ITEM_NOT_FOUND);
            }
            // 同一来源行在入参里出现两次：合并为一条（取后者的数量），避免"同一行推两次"绕过剩余量
            PushPlan exist = findPlan(plan, matched);
            BigDecimal requested = line == null ? null : line.getQty();
            if (exist == null)
            {
                plan.add(new PushPlan(matched, requested));
            }
            else
            {
                exist.requestedQty = requested;
            }
        }
        return plan;
    }

    /**
     * 在计划里查找某个来源行。
     *
     * @param plan   计划
     * @param source 来源行
     * @return 已存在的计划项；没有返回 null
     */
    protected PushPlan findPlan(List<PushPlan> plan, ErpPurchaseRequestItem source)
    {
        for (PushPlan row : plan)
        {
            if (row.source == source || (row.source != null && row.source.getId() != null
                    && row.source.getId().equals(source.getId())))
            {
                return row;
            }
        }
        return null;
    }

    /**
     * 下推计划项（来源行 + 本次请求数量；数量 {@code null} / ≤0 表示按剩余量全推）。
     */
    protected static class PushPlan
    {
        /** 来源行。 */
        private final ErpPurchaseRequestItem source;

        /** 本次请求数量（可空）。 */
        private BigDecimal requestedQty;

        PushPlan(ErpPurchaseRequestItem source, BigDecimal requestedQty)
        {
            this.source = source;
            this.requestedQty = requestedQty;
        }
    }

    /**
     * 由来源行构造采购单行：复制物料快照，数量换成下推量，行金额按"先舍入到 2 位"重算，
     * 并写来源行标识 {@code src_item_id}。
     *
     * @param source  来源行
     * @param pushQty 本次下推量
     * @param seq     序号（从 1 连续）
     * @return 采购单行
     */
    protected ErpPurchaseOrderItem buildOrderItem(ErpPurchaseRequestItem source, BigDecimal pushQty, int seq)
    {
        ErpPurchaseOrderItem line = new ErpPurchaseOrderItem();
        // 快照整段照抄：历史单据按快照展示，下推不重新查物料档案
        line.setProductId(source.getProductId());
        line.setProductCode(source.getProductCode());
        line.setProductName(source.getProductName());
        line.setSpec(source.getSpec());
        line.setUomName(source.getUomName());
        line.setUomDecimals(source.getUomDecimals());
        line.setWarehouseId(source.getWarehouseId());
        line.setWarehouseName(source.getWarehouseName());
        line.setQty(ErpAmounts.roundQty(pushQty));
        line.setUnitPrice(ErpAmounts.roundPrice(source.getUnitPrice()));
        line.setRemark(source.getRemark());
        line.setSeq(Integer.valueOf(seq));
        // 来源行标识：任务 3.3 的"写来源行标识"（无外键，供"已下推量核对"与追溯用）
        line.setSrcItemId(source.getId());
        // 行金额：唯一实现点（先舍入到 2 位）
        ErpAmounts.applyLineAmount(line);
        return line;
    }

    /**
     * 由申请单构造草稿采购单（默认带过合同、采购部门、预计到货日期）。
     *
     * @param request    来源申请单
     * @param orderItems 采购单行
     * @param supplierId 供应商ID
     * @param remark     备注（可空）
     * @return 草稿采购单
     */
    protected ErpPurchaseOrder buildOrder(ErpPurchaseRequest request, List<ErpPurchaseOrderItem> orderItems,
                                         String supplierId, String remark)
    {
        ErpPurchaseOrder order = new ErpPurchaseOrder();
        order.setDocDate(request.getDocDate() == null ? null : request.getDocDate());
        order.setSupplierId(supplierId);
        order.setRemark(ErpPurRules.trimToNull(remark) == null ? request.getRemark() : remark);
        // 来源三列：草稿采购单的"从哪来"
        order.setSourceDocType(ErpDocType.PURCHASE_REQUEST.getCode());
        order.setSourceDocId(request.getId());
        order.setSourceDocNo(request.getDocNo());
        // 默认带过（规格/tasks 3.3）：合同快照、采购部门 = 申请的需求部门、预计到货 = 需求日期
        order.setContractId(request.getContractId());
        order.setContractNo(request.getContractNo());
        order.setPurchaseDeptId(request.getRequestDeptId());
        order.setExpectedArrivalDate(request.getNeedDate());
        order.setHandlerUserId(request.getHandlerUserId());
        order.setHandlerName(request.getHandlerName());
        order.setItems(orderItems);
        // 金额合计：先舍入再汇总（采购单有 total_amount 列）
        order.setTotalAmount(ErpPurRules.totalAmountOf(orderItems));
        return order;
    }
}
