package com.ruoyi.ctms.erp.sales.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushLine;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesPushRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestMapper;
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;
import com.ruoyi.ctms.erp.sales.service.IErpSalesPushService;
import com.ruoyi.ctms.erp.sales.support.ErpSalChangeLogWriter;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>销售下推服务实现</b>（2.0 B4 任务 4.2：销售申请 → 销售订单；规格 {@code erp/sales}）。 </p>
 *
 * <h3>三段式（同一个事务，任一段失败整体不写入）</h3>
 * <ol>
 *   <li> <b>先规划再落库</b>：加载来源申请与行项 → 守卫（<b>仅已审核</b>可推）→
 *        逐行解析本次下推量与目标来源行 → 校验数量与剩余量。
 *        规划阶段<b>不</b>产生任何写入； </li>
 *   <li> <b>生成草稿销售订单</b>：委托 {@link IErpSalesOrderService#insertSalesOrder(ErpSalesOrder)}
 *        （单号取号、客户守卫、行项快照、金额口径都复用订单服务，不另写一份）；
 *        写 {@code source_doc_type/id/no} 与行项 {@code src_item_id}； </li>
 *   <li> <b>即时累加申请行已下推量</b>（{@code ordered_qty + 本次量}）并调
 *        {@link #checkAutoComplete(String)} 做"归零即完成"判定。 </li>
 * </ol>
 *
 * <p> <b>为什么超量校验必须在规划阶段完成</b>：tasks.md §4.2 的断言要求
 * "本次 60 被拒且已下量仍为 30"—— 一旦边校验边累加，被拒时已下量就已经被改脏了。 </p>
 *
 * <p> <b>与采购线（t4a）对称</b>：两边的段数、文案与留痕口径一致；</p>
 *
 * @author 二开
 */
@Service
public class ErpSalesPushServiceImpl implements IErpSalesPushService
{
    @Autowired(required = false)
    private ErpSalesRequestMapper requestMapper;

    @Autowired(required = false)
    private ErpSalesRequestItemMapper requestItemMapper;

    @Autowired(required = false)
    private CtmsChangeLogMapper changeLogMapper;

    /**
     * 出库单服务（T3 交付；任务 4.3 的目标单据服务）。
     *
     * <p> 只消费它的 {@code insertStockOut}：单号、行项快照归一、行级仓库回落表头、
     * 金额口径、变更历史都在那里，本组<b>不复刻</b>（跨包直写 {@code t_ctms_stock_out} 是禁忌）。 </p>
     */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.posting.service.IErpStockOutService stockOutService;

    /**
     * 主数据查询（解析出库仓库的"存在 + 启用"守卫用；复用 base 的公共实现）。
     */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterLookup masterLookup;

    /**
     * 目标单据服务的注入点。
     *
     * <p> 用 {@code @Autowired(required = false)} 是为了让单测能通过 {@link #setOrderService} 注入
     * 内存桩（不起 Spring）；生产环境由容器注入真实实现。 </p>
     */
    @Autowired(required = false)
    private IErpSalesOrderService orderService;

    /**
     * 单测用构造器（不起 Spring）。
     *
     * <p> 生产环境走默认构造器 + 字段注入；本构造器只在内存桩单测里被调用。 </p>
     *
     * @param orderService 销售订单服务（真实实现或桩）
     */
    public ErpSalesPushServiceImpl(IErpSalesOrderService orderService)
    {
        this.orderService = orderService;
    }

    /**
     * 默认构造器（Spring 用）。
     */
    public ErpSalesPushServiceImpl()
    {
    }

    /* ==================== 下推主流程 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pushSalesRequestToOrder(String requestDocId, ErpSalesPushRequest request)
    {
        // ===== 阶段一：加载 + 守卫 + 规划（零写入）=====
        ErpSalesRequest source = requireById(requestDocId);
        ErpSalRules.checkPusheable(source.getStatus());
        List<ErpSalesRequestItem> sourceItems = requireItems(requestDocId);
        List<Plan> plans = buildPlans(sourceItems, request, source);
        // ⚠ 刻意**不**在这里改内存里的 orderedQty：累加的统一落点是阶段三的
        //    requestItemMapper.updateOrderedQty（ordered_qty + 本次量，SQL 累加）。
        //    两处都加会变成 +2×本次量（单测已锁死这个数值）。

        // ===== 阶段二：草稿订单（复用订单服务：取号/客户守卫/快照/金额口径）=====
        ErpSalesOrder order = new ErpSalesOrder();
        order.setDocDate(request == null || request.getDocDate() == null ? source.getDocDate() : request.getDocDate());
        order.setStatus(ErpDocStatus.DRAFT);
        order.setCustomerId(source.getCustomerId());
        order.setSalesDeptId(source.getSalesDeptId());
        order.setContractId(source.getContractId());
        order.setContractNo(source.getContractNo());
        order.setSourceDocType(ErpDocType.SALES_REQUEST.getCode());
        order.setSourceDocId(source.getId());
        order.setSourceDocNo(source.getDocNo());
        order.setHandlerUserId(source.getHandlerUserId());
        order.setHandlerName(source.getHandlerName());
        if (request != null)
        {
            order.setRemark(request.getRemark());
            order.setShipWarehouseId(request.getShipWarehouseId());
            order.setDeliveryAddress(request.getDeliveryAddress());
            order.setContactName(request.getContactName());
            order.setContactPhone(request.getContactPhone());
            order.setDeliveryDate(request.getDeliveryDate() != null
                    ? request.getDeliveryDate() : source.getExpectDeliveryDate());
        }
        else
        {
            order.setDeliveryDate(source.getExpectDeliveryDate());
        }
        if (ErpSalRules.trim(order.getRemark()) == null)
        {
            order.setRemark("由销售申请单 " + source.getDocNo() + " 下推生成");
        }

        List<ErpSalesOrderItem> orderItems = new ArrayList<>(plans.size());
        for (Plan plan : plans)
        {
            ErpSalesOrderItem row = new ErpSalesOrderItem();
            row.setProductId(plan.sourceItem.getProductId());
            row.setProductCode(plan.sourceItem.getProductCode());
            row.setProductName(plan.sourceItem.getProductName());
            row.setSpec(plan.sourceItem.getSpec());
            row.setUomName(plan.sourceItem.getUomName());
            row.setUomDecimals(plan.sourceItem.getUomDecimals());
            row.setQty(plan.qty);
            row.setUnitPrice(plan.unitPrice);
            row.setWarehouseId(plan.sourceItem.getWarehouseId());
            row.setWarehouseName(plan.sourceItem.getWarehouseName());
            row.setSrcItemId(plan.sourceItem.getId());
            row.setRemark(plan.line.getRemark() == null ? plan.sourceItem.getRemark() : plan.line.getRemark());
            orderItems.add(row);
        }
        order.setItems(orderItems);
        ErpSalesOrder created = orderService().insertSalesOrder(order);
        if (created == null || ErpSalRules.trim(created.getId()) == null)
        {
            throw new ServiceException("下推销售订单失败：订单未落库");
        }

        // ===== 阶段三：即时累加已下推量 + 归零即完成 =====
        for (Plan plan : plans)
        {
            if (requestItemMapper != null)
            {
                requestItemMapper.updateOrderedQty(plan.sourceItem.getId(), plan.qty);
            }
        }
        boolean completed = checkAutoComplete(requestDocId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", created.getId());
        result.put("orderNo", created.getDocNo());
        result.put("sourceDocId", source.getId());
        result.put("sourceDocNo", source.getDocNo());
        result.put("pushedQtySum", ErpAmounts.roundQty(sumQty(plans)));
        result.put("requestCompleted", Boolean.valueOf(completed));
        List<Map<String, Object>> lines = new ArrayList<>(plans.size());
        for (Plan plan : plans)
        {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("srcItemId", plan.sourceItem.getId());
            line.put("productId", plan.sourceItem.getProductId());
            line.put("productName", plan.sourceItem.getProductName());
            line.put("qty", plan.qty);
            line.put("remainingQty", plan.sourceItem.remainingQty());
            lines.add(line);
        }
        result.put("lines", lines);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean checkAutoComplete(String requestDocId)
    {
        ErpSalesRequest request = requireById(requestDocId);
        // 幂等：已经是完成态就直接返回，不重复留痕（tasks.md §4.2 的"重复判定不重复留痕"）
        if (ErpDocStatus.COMPLETED.equals(ErpSalRules.trim(request.getStatus())))
        {
            return false;
        }
        List<ErpSalesRequestItem> items = requestItemMapper == null
                ? new ArrayList<ErpSalesRequestItem>()
                : requestItemMapper.selectItemsByDocId(requestDocId);
        if (!ErpSalRules.isFullyOrdered(items))
        {
            return false;
        }
        String oldStatus = request.getStatus();
        request.setStatus(ErpDocStatus.COMPLETED);
        updateStatus(request);
        writeAutoCompleteLog(request, oldStatus);
        return true;
    }

    /* ==================== 销售订单 → 出库单（任务 4.3） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pushSalesOrderToStockOut(String orderDocId, ErpSalesPushRequest request)
    {
        if (stockOutService == null)
        {
            // 未装配出库单服务时不能"放行"：宁可拒绝，也不自己写出库单表（跨包直写是禁忌）
            throw new ServiceException("出库单服务未装配，无法下推出库单");
        }
        // ① 来源必须是"已审核或已完成"的销售订单（范围外由订单服务抛 403）
        ErpSalesOrder order = orderService().selectSalesOrderById(orderDocId);
        String status = ErpSalRules.trim(order.getStatus());
        if (!ErpDocStatus.APPROVED.equals(status) && !ErpDocStatus.COMPLETED.equals(status))
        {
            throw new ServiceException(ErpSalRules.MSG_ORDER_NOT_PUSHABLE_OUT);
        }
        List<ErpSalesOrderItem> sourceItems = order.getItems() == null
                ? new ArrayList<ErpSalesOrderItem>() : order.getItems();
        if (sourceItems.isEmpty())
        {
            throw new ServiceException("单据没有行项");
        }
        // ② 出库仓库必须在**下推时**就解析出来：t_ctms_stock_out.warehouse_id 是
        //    NOT NULL + FK（DDL 冻结件），缺仓库的草稿在真库上是 1048，而不是"审核时才被拒"
        com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord warehouse =
                resolveShipWarehouse(order, request == null ? null : request.getShipWarehouseId());
        // ③ 规划（全部算完才落库：失败路径零中间写入）
        List<ShipPlan> plans = buildShipPlans(sourceItems, request == null ? null : request.getLines());
        List<com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem> outItems = new ArrayList<>(plans.size());
        BigDecimal pushedTotal = BigDecimal.ZERO;
        BigDecimal remainingAfter = BigDecimal.ZERO;
        int seq = 0;
        for (ShipPlan plan : plans)
        {
            seq++;
            outItems.add(buildStockOutItem(plan, seq));
            pushedTotal = pushedTotal.add(plan.qty);
            remainingAfter = remainingAfter.add(plan.remaining.subtract(plan.qty));
        }
        // ④ 生成草稿出库单（单号/快照/仓库回落/金额都在 T3 的 insertStockOut 里，本组不复刻）
        com.ruoyi.ctms.erp.posting.domain.ErpStockOut saved = stockOutService.insertStockOut(
                buildStockOut(order, outItems, warehouse, request == null ? null : request.getRemark()));
        // ⑤ 刻意**不写** shipped_qty：已出库量只在出库单"过账成功"时由
        //    ErpSalesOutboundListener 回写、红冲时回退（规格 §4.3 的冻结口径）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockOutId", saved.getId());
        result.put("stockOutNo", saved.getDocNo());
        result.put("sourceDocId", order.getId());
        result.put("sourceDocNo", order.getDocNo());
        result.put("pushedQtySum", ErpAmounts.roundQty(pushedTotal));
        result.put("remainQtySum", ErpAmounts.roundQty(remainingAfter));
        List<Map<String, Object>> lines = new ArrayList<>(plans.size());
        for (ShipPlan plan : plans)
        {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("srcItemId", plan.source.getId());
            line.put("productId", plan.source.getProductId());
            line.put("productName", plan.source.getProductName());
            line.put("qty", plan.qty);
            line.put("remainingQty", plan.remaining.subtract(plan.qty));
            lines.add(line);
        }
        result.put("lines", lines);
        return result;
    }

    /**
     * 本次下推出库单的一行计划（来源订单行 + 本次数量 + 单价 + 用前剩余量）。
     */
    private static final class ShipPlan
    {
        private ErpSalesOrderItem source;

        private BigDecimal qty;

        private BigDecimal unitPrice;

        private BigDecimal remaining;
    }

    /**
     * 解析本次下推的每一行（匹配来源行 → 定数量 → 校验剩余量；不写入）。
     *
     * <p> 匹配规则与申请→订单那条链<b>完全同构</b>（只解释一次）：
     * {@code srcItemId}/{@code docItemId} → {@code productId} → {@code index} → 数组顺序。 </p>
     *
     * @param sourceItems 订单行（按序号升序）
     * @param lines       下推行（可空 = 全部行按剩余量全推）
     * @return 计划（保持行序、按订单行去重）
     */
    protected List<ShipPlan> buildShipPlans(List<ErpSalesOrderItem> sourceItems, List<ErpSalesPushLine> lines)
    {
        if (lines == null || lines.isEmpty())
        {
            List<ShipPlan> plans = new ArrayList<>();
            for (ErpSalesOrderItem item : sourceItems)
            {
                if (item == null)
                {
                    continue;
                }
                BigDecimal remaining = item.remainingQty();
                if (remaining.compareTo(BigDecimal.ZERO) <= 0)
                {
                    continue;
                }
                plans.add(newShipPlan(item, ErpAmounts.roundQty(remaining), item.getUnitPrice(), remaining));
            }
            if (plans.isEmpty())
            {
                throw new ServiceException(ErpSalRules.MSG_NO_REMAINING_SHIPPABLE);
            }
            return plans;
        }
        if (lines.size() > sourceItems.size())
        {
            throw new ServiceException("下推行数（" + lines.size() + "）超过销售订单行数（"
                    + sourceItems.size() + "）");
        }
        List<ShipPlan> plans = new ArrayList<>(lines.size());
        java.util.Set<String> used = new java.util.HashSet<>();
        int index = 0;
        for (ErpSalesPushLine line : lines)
        {
            if (line == null)
            {
                throw new ServiceException("行 " + (index + 1) + "：下推行不能为空");
            }
            ErpSalesOrderItem source = matchOrderSourceItem(sourceItems, line, index);
            if (source == null)
            {
                throw new ServiceException("行 " + (index + 1) + "：找不到对应的销售订单行");
            }
            if (!used.add(source.getId()))
            {
                throw new ServiceException("行 " + (index + 1) + "：「" + source.getProductName() + "」被重复下推");
            }
            BigDecimal remaining = source.remainingQty();
            BigDecimal qty = ErpSalRules.resolvePushQty(line.getQty(), remaining);
            ErpSalRules.checkPushQty(source.getProductName(), remaining, qty);
            BigDecimal unitPrice = line.getUnitPrice() == null ? source.getUnitPrice() : line.getUnitPrice();
            plans.add(newShipPlan(source, qty, unitPrice, remaining));
            index++;
        }
        return plans;
    }

    private ShipPlan newShipPlan(ErpSalesOrderItem source, BigDecimal qty, BigDecimal unitPrice,
                                 BigDecimal remaining)
    {
        ShipPlan plan = new ShipPlan();
        plan.source = source;
        plan.qty = qty;
        plan.unitPrice = unitPrice;
        plan.remaining = remaining;
        return plan;
    }

    /**
     * 按来源行ID → 物料ID → 行序号 → 数组顺序匹配<b>订单</b>行（与申请侧同款）。
     *
     * <p> 刻意不与申请侧的 {@code matchSourceItem} 同名重载：两个方法的参数只差泛型，
     * 在推断不出泛型的调用点上会变成"引用不明确"（本模块实测踩过一次），
     * 因此用不同的方法名消除歧义。 </p>
     *
     * @param sourceItems 订单行
     * @param line        下推行
     * @param index       该下推行在数组里的下标（0 起）
     * @return 命中的订单行；未命中返回 null
     */
    protected ErpSalesOrderItem matchOrderSourceItem(List<ErpSalesOrderItem> sourceItems, ErpSalesPushLine line,
                                                    int index)
    {
        String docItemId = line.effectiveDocItemId();
        if (docItemId != null)
        {
            for (ErpSalesOrderItem item : sourceItems)
            {
                if (item != null && docItemId.equals(ErpSalRules.trim(item.getId())))
                {
                    return item;
                }
            }
            return null;
        }
        String productId = ErpSalRules.trim(line.getProductId());
        if (productId != null)
        {
            for (ErpSalesOrderItem item : sourceItems)
            {
                if (item != null && productId.equals(ErpSalRules.trim(item.getProductId())))
                {
                    return item;
                }
            }
            return null;
        }
        Integer explicitIndex = line.getIndex();
        int target = explicitIndex == null ? index : explicitIndex.intValue();
        if (target < 0 || target >= sourceItems.size())
        {
            return null;
        }
        return sourceItems.get(target);
    }

    /**
     * 出库行装配：物料/单位快照沿用订单行（历史一致性），数量用本次下推量。
     *
     * <p> 刻意<b>不</b>重新查物料档案：下推是"把已审核订单的行搬过去"，
     * 用订单行的快照才能保证同一批货在订单与出库单上同名同规格（AC-72 的行项快照口径）；
     * 物料此后改名/停用也不影响这套单据。行级仓库留空 ⇒ T3 的
     * {@code insertStockOut} 会回落到表头仓库。 </p>
     *
     * @param plan 计划
     * @param seq  序号（1 起）
     * @return 出库单行
     */
    protected com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem buildStockOutItem(ShipPlan plan, int seq)
    {
        com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem item =
                new com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem();
        item.setProductId(plan.source.getProductId());
        item.setProductCode(plan.source.getProductCode());
        item.setProductName(plan.source.getProductName());
        item.setSpec(plan.source.getSpec());
        item.setUomName(plan.source.getUomName());
        item.setUomDecimals(plan.source.getUomDecimals());
        item.setQty(plan.qty);
        item.setUnitPrice(plan.unitPrice);
        item.setSrcItemId(plan.source.getId());
        item.setRemark(plan.source.getRemark());
        item.setSeq(seq);
        return item;
    }

    /**
     * 组装草稿出库单表头（带发货仓库、客户快照、来源单号）。
     *
     * @param order     来源销售订单
     * @param items     出库行
     * @param warehouse 已解析的出库仓库（含名称快照）
     * @param remark    备注（可空：空则写"由销售订单 X 下推生成"）
     * @return 出库单
     */
    protected com.ruoyi.ctms.erp.posting.domain.ErpStockOut buildStockOut(
            ErpSalesOrder order, List<com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem> items,
            com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord warehouse, String remark)
    {
        com.ruoyi.ctms.erp.posting.domain.ErpStockOut doc = new com.ruoyi.ctms.erp.posting.domain.ErpStockOut();
        doc.setDocDate(order.getDocDate() == null ? now() : order.getDocDate());
        doc.setWarehouseId(warehouse.getId());
        doc.setWarehouseName(warehouse.getName());
        doc.setOutType(ErpSalRules.OUT_TYPE_SALE);
        doc.setCustomerId(order.getCustomerId());
        doc.setCustomerName(order.getCustomerName());
        doc.setContractId(order.getContractId());
        doc.setContractNo(order.getContractNo());
        doc.setHandlerUserId(order.getHandlerUserId());
        doc.setHandlerName(order.getHandlerName());
        doc.setSourceDocType(ErpDocType.SALES_ORDER.getCode());
        doc.setSourceDocId(order.getId());
        doc.setSourceDocNo(order.getDocNo());
        doc.setRemark(ErpSalRules.trim(remark) == null
                ? ("由销售订单 " + order.getDocNo() + " 下推生成") : remark);
        doc.setItems(items);
        return doc;
    }

    /**
     * 解析出库仓库：入参优先，其次销售订单的默认发货仓库；都没有时<b>拒绝下推</b>。
     *
     * @param order       销售订单
     * @param warehouseId 入参指定的出库仓库（可空）
     * @return 仓库摘要（含名称快照）
     * @throws ServiceException 两者都空 / 仓库不存在或已停用
     */
    protected com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterRecord resolveShipWarehouse(
            ErpSalesOrder order, String warehouseId)
    {
        String target = ErpSalRules.trim(warehouseId);
        if (target == null)
        {
            target = ErpSalRules.trim(order.getShipWarehouseId());
        }
        if (target == null)
        {
            throw new ServiceException(ErpSalRules.MSG_OUT_WAREHOUSE_REQUIRED);
        }
        return com.ruoyi.ctms.erp.base.ErpMasterGuards.requireEnabledWarehouse(masterLookup, target);
    }

    /* ==================== 规划（纯计算，无写入） ==================== */

    /**
     * 本次下推的一行计划（来源行 + 本次数量 + 单价）。
     */
    private static final class Plan
    {
        private ErpSalesRequestItem sourceItem;

        private ErpSalesPushLine line;

        private BigDecimal qty;

        private BigDecimal unitPrice;
    }

    /**
     * 解析本次下推的每一行：匹配来源行 → 定数量 → 校验剩余量（不写入）。
     *
     * @param sourceItems 来源申请的全部行项
     * @param request     下推参数（{@code null} = 全推）
     * @param source      来源申请单（用于文案）
     * @return 计划清单（非空、每行数量 &gt; 0 且 ≤ 剩余量）
     * @throws ServiceException 来源行无法匹配 / 超量 / 重复下推同一行
     */
    protected List<Plan> buildPlans(List<ErpSalesRequestItem> sourceItems, ErpSalesPushRequest request,
                                    ErpSalesRequest source)
    {
        List<ErpSalesPushLine> lines = request == null ? null : request.getLines();
        if (lines == null || lines.isEmpty())
        {
            // 不填行 = 全部行项按剩余量全推
            return planAll(sourceItems);
        }
        if (lines.size() > sourceItems.size())
        {
            throw new ServiceException("下推行数（" + lines.size() + "）超过申请单行数（"
                    + sourceItems.size() + "）");
        }
        List<Plan> plans = new ArrayList<>(lines.size());
        java.util.Set<String> used = new java.util.HashSet<>();
        int index = 0;
        for (ErpSalesPushLine line : lines)
        {
            if (line == null)
            {
                throw new ServiceException("行 " + (index + 1) + "：下推行不能为空");
            }
            ErpSalesRequestItem sourceItem = matchSourceItem(sourceItems, line, index);
            if (sourceItem == null)
            {
                throw new ServiceException("行 " + (index + 1) + "：找不到对应的销售申请行"
                        + (line.getDocItemId() == null ? "" : "（" + line.getDocItemId() + "）"));
            }
            if (!used.add(sourceItem.getId()))
            {
                throw new ServiceException("行 " + (index + 1) + "：「" + sourceItem.getProductName()
                        + "」被重复下推");
            }
            BigDecimal qty = ErpSalRules.resolvePushQty(line.getQty(), sourceItem.remainingQty());
            ErpSalRules.checkPushQty(sourceItem.getProductName(), sourceItem.remainingQty(), qty);
            Plan plan = new Plan();
            plan.sourceItem = sourceItem;
            plan.line = line;
            plan.qty = qty;
            plan.unitPrice = line.getUnitPrice() == null ? sourceItem.getUnitPrice() : line.getUnitPrice();
            plans.add(plan);
            index++;
        }
        return plans;
    }

    /**
     * 全推：每行按剩余量下推（剩余量为 0 的行跳过；全为 0 时报错）。
     *
     * @param sourceItems 来源行项
     * @return 计划清单
     * @throws ServiceException 已无可下推数量
     */
    protected List<Plan> planAll(List<ErpSalesRequestItem> sourceItems)
    {
        List<Plan> plans = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ErpSalesRequestItem item : sourceItems)
        {
            if (item == null)
            {
                continue;
            }
            BigDecimal remaining = item.remainingQty();
            if (remaining.compareTo(BigDecimal.ZERO) <= 0)
            {
                continue;
            }
            Plan plan = new Plan();
            plan.sourceItem = item;
            plan.line = new ErpSalesPushLine();
            plan.qty = ErpAmounts.roundQty(remaining);
            plan.unitPrice = item.getUnitPrice();
            plans.add(plan);
            total = total.add(plan.qty);
        }
        if (plans.isEmpty())
        {
            throw new ServiceException("已无可下推数量（全部行项剩余量为 0）");
        }
        return plans;
    }

    /**
     * 按 {@code docItemId} → {@code productId} → {@code index} → 数组顺序匹配来源行。
     *
     * @param sourceItems 来源行项
     * @param line        下推行
     * @param index       该下推行在数组里的下标（0 起）
     * @return 命中的来源行；未命中返回 null
     */
    protected ErpSalesRequestItem matchSourceItem(List<ErpSalesRequestItem> sourceItems,
                                                  ErpSalesPushLine line, int index)
    {
        String docItemId = ErpSalRules.trim(line.getDocItemId());
        if (docItemId != null)
        {
            for (ErpSalesRequestItem item : sourceItems)
            {
                if (item != null && docItemId.equals(ErpSalRules.trim(item.getId())))
                {
                    return item;
                }
            }
            return null;
        }
        String productId = ErpSalRules.trim(line.getProductId());
        if (productId != null)
        {
            for (ErpSalesRequestItem item : sourceItems)
            {
                if (item != null && productId.equals(ErpSalRules.trim(item.getProductId())))
                {
                    return item;
                }
            }
            return null;
        }
        Integer explicitIndex = line.getIndex();
        int target = explicitIndex == null ? index : explicitIndex.intValue();
        if (target < 0 || target >= sourceItems.size())
        {
            return null;
        }
        return sourceItems.get(target);
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 取来源申请单。
     *
     * @param id 主键
     * @return 单据
     * @throws ServiceException 不存在
     */
    protected ErpSalesRequest requireById(String id)
    {
        String docId = ErpSalRules.trim(id);
        if (docId == null)
        {
            throw new ServiceException("销售申请单ID不能为空");
        }
        if (requestMapper == null)
        {
            throw new ServiceException("销售申请单数据访问层不可用（ErpSalesRequestMapper 未注入）");
        }
        ErpSalesRequest doc = requestMapper.selectSalesRequestById(docId);
        if (doc == null)
        {
            throw new ServiceException("销售申请单不存在：" + docId);
        }
        return doc;
    }

    /**
     * 取来源行项（空集合直接报错：没有行项的单据不可能通过提交守卫，属于脏数据）。
     *
     * @param docId 单据ID
     * @return 行项集合（非空）
     * @throws ServiceException 没有行项
     */
    protected List<ErpSalesRequestItem> requireItems(String docId)
    {
        List<ErpSalesRequestItem> items = requestItemMapper == null
                ? new ArrayList<ErpSalesRequestItem>() : requestItemMapper.selectItemsByDocId(docId);
        if (items == null || items.isEmpty())
        {
            throw new ServiceException("销售申请单没有行项，无法下推");
        }
        return items;
    }

    /**
     * 只更新状态（"归零即完成"的落库口径：不动其余业务列）。
     *
     * @param request 单据（含 id 与新状态）
     */
    protected void updateStatus(ErpSalesRequest request)
    {
        if (requestMapper == null)
        {
            throw new ServiceException("销售申请单数据访问层不可用（ErpSalesRequestMapper 未注入）");
        }
        request.setUpdateId(currentUserId());
        request.setUpdateBy(currentUsername());
        request.setUpdateTime(now());
        requestMapper.updateSalesRequestStatus(request);
    }

    /**
     * 写"自动置为已完成"的变更历史（同事务；幂等由 {@link #checkAutoComplete} 保证）。
     *
     * @param request   单据
     * @param oldStatus 旧状态
     */
    protected void writeAutoCompleteLog(ErpSalesRequest request, String oldStatus)
    {
        if (changeLogMapper == null)
        {
            return;
        }
        new ErpSalChangeLogWriter(changeLogMapper).writeStatusChange(ErpDocType.SALES_REQUEST,
                request.getId(), oldStatus, ErpDocStatus.COMPLETED, ErpSalRules.NOTE_AUTO_COMPLETE,
                ErpSalRules.LOG_SOURCE_AUTO, operatorId(), operatorName());
    }

    /**
     * 本次下推数量合计。
     *
     * @param plans 计划
     * @return 3 位合计
     */
    private BigDecimal sumQty(List<Plan> plans)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (Plan plan : plans)
        {
            sum = sum.add(plan.qty);
        }
        return sum;
    }

    private BigDecimal nz(BigDecimal value)
    {
        return value == null ? BigDecimal.ZERO : value;
    }
    /**
     * 目标订单服务（缺失即报错）。
     *
     * @return 服务
     */
    private IErpSalesOrderService orderService()
    {
        if (orderService == null)
        {
            throw new ServiceException("销售订单服务不可用（IErpSalesOrderService 未注入）");
        }
        return orderService;
    }

    /* ==================== 可替换的服务端上下文（单测用子类固定） ==================== */

    /**
     * 设置目标订单服务（单测注入内存桩用；生产由 Spring 注入）。
     *
     * @param orderService 订单服务
     */
    public void setOrderService(IErpSalesOrderService orderService)
    {
        this.orderService = orderService;
    }

    /**
     * 设置销售申请单 Mapper（单测注入内存桩用；生产由 Spring 注入）。
     *
     * @param requestMapper 表头 Mapper
     */
    public void setRequestMapper(ErpSalesRequestMapper requestMapper)
    {
        this.requestMapper = requestMapper;
    }

    /**
     * 设置销售申请行项 Mapper（单测注入内存桩用；生产由 Spring 注入）。
     *
     * @param requestItemMapper 行项 Mapper
     */
    public void setRequestItemMapper(ErpSalesRequestItemMapper requestItemMapper)
    {
        this.requestItemMapper = requestItemMapper;
    }

    /**
     * 设置变更历史 Mapper（单测注入内存桩用；生产由 Spring 注入）。
     *
     * @param changeLogMapper 变更历史 Mapper
     */
    public void setChangeLogMapper(CtmsChangeLogMapper changeLogMapper)
    {
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 设置出库单服务（单测注入内存桩用；生产由 Spring 注入 T3 的实现）。
     *
     * @param stockOutService 出库单服务
     */
    public void setStockOutService(com.ruoyi.ctms.erp.posting.service.IErpStockOutService stockOutService)
    {
        this.stockOutService = stockOutService;
    }

    /**
     * 设置主数据查询（单测注入内存桩用；生产由 Spring 注入）。
     *
     * @param masterLookup 主数据查询
     */
    public void setMasterLookup(com.ruoyi.ctms.erp.base.ErpMasterGuards.MasterLookup masterLookup)
    {
        this.masterLookup = masterLookup;
    }

    /**
     * 当前用户ID。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        return ErpDocScope.currentUserId();
    }

    /**
     * 当前登录名。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        return ErpDocScope.currentUsername();
    }

    /**
     * 操作人ID。
     *
     * @return 用户ID
     */
    protected String operatorId()
    {
        return ErpSalChangeLogWriter.operatorId(currentUserId());
    }

    /**
     * 操作人登录名。
     *
     * @return 登录名
     */
    protected String operatorName()
    {
        return ErpSalChangeLogWriter.operatorName(currentUsername());
    }

    /**
     * 当前时间。
     *
     * @return 时间
     */
    protected Date now()
    {
        return new Date();
    }
}
