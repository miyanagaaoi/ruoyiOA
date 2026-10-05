package com.ruoyi.ctms.erp.procurement.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocAction;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocStateMachine;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderMapper;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseOrderService;
import com.ruoyi.ctms.erp.procurement.support.ErpPurChangeLogWriter;
import com.ruoyi.ctms.erp.procurement.support.ErpPurDocNoGenerator;
import com.ruoyi.ctms.erp.procurement.support.ErpPurServiceSupport;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.service.ICtmsPartnerService;

/**
 * <p> 采购单服务实现（2.0 B4 任务 3.1、3.2、3.6；任务 3.5 的过账回写入口在此实现）。 </p>
 *
 * <p> 与 {@link ErpPurchaseRequestServiceImpl} 的分层完全一致；差异只有三处： </p>
 * <ol>
 *   <li> <b>供应商引用与收货信息</b>（任务 3.2）：供应商必填、存在、启用；传客户ID 必然命不中
 *        供应商档案 → 报"供应商档案不存在"（规格场景的断言文案）；收货仓库要存在且启用；</li>
 *   <li> <b>金额合计落库</b>：采购单有 {@code total_amount} 列，值按"先舍入再汇总"算好后写库
 *        （列表/打印/导出四处取同一份值，避免各处现算产生 1 分钱差异）；</li>
 *   <li> <b>已入库量回写</b>：{@link #applyReceivedQtyChange} 是唯一入口（过账累加 / 红冲回退），
 *        "何时调用"由 t4b 在入库单过账/红冲里接（本任务只把口径钉死）。</li>
 * </ol>
 *
 * @author 二开
 */
@Service
public class ErpPurchaseOrderServiceImpl extends ErpPurServiceSupport
        implements IErpPurchaseOrderService, IErpPurchaseOrderService.ItemWriter
{
    /** 详情返回的变更历史上限。 */
    private static final int CHANGE_LOG_LIMIT = 200;

    /** 币种兜底（与 DDL 的 DEFAULT 'CNY' 一致）。 */
    private static final String DEFAULT_CURRENCY = "CNY";

    /* ==================== 依赖 ==================== */

    @Autowired
    private ErpPurchaseOrderMapper orderMapper;

    @Autowired
    private ErpPurchaseOrderItemMapper itemMapper;

    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /** 主数据守卫回调（base 交付的 {@code ErpMasterLookupImpl}；单测用内存桩替换）。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 部门祖先链查询（数据范围"本部门及下级"档）。 */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.base.mapper.ErpDocLookupMapper docLookupMapper;

    /** 往来单位服务（B3 交付，只消费）。 */
    @Autowired(required = false)
    private ICtmsPartnerService partnerService;

    /** 合同台账服务（B3 交付；任务 3.6）。 */
    @Autowired(required = false)
    private ICtmsContractService contractService;

    /** base 的统一取号器（单据单号只能走它；撞号重试由 {@link ErpPurDocNoGenerator} 承担）。 */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.base.ErpDocNoGenerator erpDocNoGenerator;

    /** 变更历史写入点（懒建）。 */
    private ErpPurChangeLogWriter changeLogWriter;

    /** 单号生成器（懒建）。 */
    private ErpPurDocNoGenerator docNoGenerator;

    /**
     * 变更历史写入点。
     *
     * @return 写入点
     */
    protected ErpPurChangeLogWriter changeLogWriter()
    {
        if (changeLogWriter == null)
        {
            changeLogWriter = new ErpPurChangeLogWriter(changeLogMapper);
        }
        return changeLogWriter;
    }

    /**
     * 单号生成器。
     *
     * @return 生成器
     */
    protected ErpPurDocNoGenerator docNoGenerator()
    {
        if (docNoGenerator == null)
        {
            docNoGenerator = new ErpPurDocNoGenerator(erpDocNoGenerator);
        }
        return docNoGenerator;
    }

    /**
     * 主数据守卫回调（未装配时显式失败）。
     *
     * @return 回调
     */
    protected ErpMasterGuards.MasterLookup requireMasterLookup()
    {
        if (masterLookup == null)
        {
            throw new ServiceException("主数据守卫未装配，无法校验行项物料");
        }
        return masterLookup;
    }

    /**
     * 装配变化后刷新懒建对象（测试注入桩后调用）。
     */
    public void refreshCollaborators()
    {
        this.changeLogWriter = null;
        this.docNoGenerator = null;
    }

    /* ==================== 列表与详情 ==================== */

    @Override
    public List<ErpPurchaseOrder> selectOrderList(ErpPurchaseOrder query)
    {
        ErpPurchaseOrder effective = query == null ? new ErpPurchaseOrder() : query;
        effective.setDataScopeSql(dataScopeSql());
        List<ErpPurchaseOrder> list = orderMapper.selectOrderList(effective);
        if (list == null || list.isEmpty())
        {
            return list == null ? new ArrayList<ErpPurchaseOrder>() : list;
        }
        List<String> ids = new ArrayList<>(list.size());
        for (ErpPurchaseOrder row : list)
        {
            ids.add(row.getId());
        }
        Map<String, List<ErpPurchaseOrderItem>> itemsByDoc = groupItems(itemMapper.selectItemsByDocIds(ids));
        for (ErpPurchaseOrder row : list)
        {
            List<ErpPurchaseOrderItem> items = itemsByDoc.get(row.getId());
            row.setItems(items == null ? new ArrayList<ErpPurchaseOrderItem>() : items);
            fillDerived(row);
        }
        return list;
    }

    /**
     * 装配派生列（剩余可入库量合计、可推入库标志、默认收货仓库名）。
     *
     * @param order 采购单（就地填充）
     */
    protected void fillDerived(ErpPurchaseOrder order)
    {
        if (order == null)
        {
            return;
        }
        BigDecimal remain = BigDecimal.ZERO;
        List<ErpPurchaseOrderItem> items = order.getItems();
        if (items != null)
        {
            for (ErpPurchaseOrderItem item : items)
            {
                item.setRemainingQty(item.remainingQty());
                remain = remain.add(item.getRemainingQty());
            }
        }
        order.setRemainQtySum(ErpAmounts.roundQty(remain));
        order.setCanReceive(Boolean.valueOf(order.computeCanReceive()));
    }

    /**
     * 按 doc_id 分组。
     *
     * @param items 行项集合
     * @return doc_id → 行项
     */
    protected Map<String, List<ErpPurchaseOrderItem>> groupItems(List<ErpPurchaseOrderItem> items)
    {
        Map<String, List<ErpPurchaseOrderItem>> grouped = new LinkedHashMap<>();
        if (items == null)
        {
            return grouped;
        }
        for (ErpPurchaseOrderItem item : items)
        {
            if (item == null || ErpPurRules.isBlank(item.getDocId()))
            {
                continue;
            }
            List<ErpPurchaseOrderItem> bucket = grouped.get(item.getDocId());
            if (bucket == null)
            {
                bucket = new ArrayList<>();
                grouped.put(item.getDocId(), bucket);
            }
            bucket.add(item);
        }
        return grouped;
    }

    @Override
    public ErpPurchaseOrder selectOrderDetail(String id)
    {
        ErpPurchaseOrder order = requireAccessible(id);
        order.setItems(itemMapper.selectItemsByDocId(id));
        fillDerived(order);
        String warehouseId = order.getReceiptWarehouseId();
        if (!ErpPurRules.isBlank(warehouseId) && masterLookup != null)
        {
            ErpMasterGuards.MasterRecord warehouse = masterLookup.warehouse(warehouseId);
            order.setReceiptWarehouseName(warehouse == null ? null : warehouse.getName());
        }
        order.setChangeLogs(changeLogWriter().listOf(ErpDocType.PURCHASE_ORDER, id,
                Integer.valueOf(CHANGE_LOG_LIMIT)));
        return order;
    }

    @Override
    public List<ErpPurchaseOrderItem> selectItemsByDocId(String docId)
    {
        requireAccessible(docId);
        List<ErpPurchaseOrderItem> items = itemMapper.selectItemsByDocId(docId);
        if (items == null)
        {
            return new ArrayList<>();
        }
        for (ErpPurchaseOrderItem item : items)
        {
            item.setRemainingQty(item.remainingQty());
        }
        return items;
    }

    @Override
    public List<CtmsChangeLog> selectOrderChangeLogs(String docId)
    {
        requireAccessible(docId);
        return changeLogWriter().listOf(ErpDocType.PURCHASE_ORDER, docId, null);
    }

    /* ==================== 新增 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String insertOrder(ErpPurchaseOrder order)
    {
        if (order == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        order.setId(IdUtils.fastSimpleUUID());
        order.setStatus(ErpDocStatus.DRAFT);
        order.setPosted(ErpDocHeader.POSTED_NO);
        order.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        order.setDocNo(resolveDocNo(order.getDocDate()));
        order.setCreateTime(now());
        order.setUpdateTime(now());
        applyCreateSnapshot(order);
        applyCommonFields(order);
        applyItems(order);
        // 供应商引用（任务 3.2）：必填 + 存在 + 启用；写名称快照
        bindSupplier(order, order.getSupplierId());
        // 收货信息：仓库存在且启用；预计到货与结算方式是可选业务字段
        bindReceiptWarehouse(order, order.getReceiptWarehouseId());
        bindContract(order, order.getContractId());
        // 金额合计：先舍入再汇总，结果落库（列表/打印/导出共用同一份值）
        order.setTotalAmount(ErpPurRules.totalAmountOf(order.getItems()));
        // ⚠ 先插主表再插行项（行项 doc_id 非空外键）
        orderMapper.insertOrder(order);
        persistItems(order);
        return order.getId();
    }

    /* ==================== 编辑 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOrder(ErpPurchaseOrder order)
    {
        if (order == null || ErpPurRules.isBlank(order.getId()))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseOrder exist = requireAccessible(order.getId());
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_ORDER);
        ErpPurchaseOrder target = mergeForUpdate(exist, order);
        applyCommonFields(target);
        applyItems(target);
        // 供应商：入参传了才重新绑定（空表示"没动"）；采购单供应商是必填列，不允许清空
        if (!ErpPurRules.isBlank(order.getSupplierId()))
        {
            bindSupplier(target, order.getSupplierId());
        }
        if (!ErpPurRules.isBlank(order.getReceiptWarehouseId()))
        {
            bindReceiptWarehouse(target, order.getReceiptWarehouseId());
        }
        if (!ErpPurRules.isBlank(order.getContractId()))
        {
            bindContract(target, order.getContractId());
        }
        target.setTotalAmount(ErpPurRules.totalAmountOf(target.getItems()));
        target.setUpdateId(currentUserId());
        target.setUpdateBy(currentUsername());
        target.setUpdateTime(now());
        orderMapper.updateOrder(target);
        itemMapper.deleteItemsByDocId(target.getId());
        persistItems(target);
    }

    /**
     * 编辑合并：只接受用户可编辑的列，其余回落库内原值。
     *
     * <p> ⚠ {@code receivedQty} 不在表头，行项上由过账服务写；编辑时行项全量替换，
     * 因此这里把"已入库量"从库内行项带回（防止编辑一张已部分入库的单据后回写量被清零）。 </p>
     *
     * @param exist 库内行
     * @param patch 请求体
     * @return 合并后的对象
     */
    protected ErpPurchaseOrder mergeForUpdate(ErpPurchaseOrder exist, ErpPurchaseOrder patch)
    {
        ErpPurchaseOrder target = new ErpPurchaseOrder();
        BeanUtils.copyProperties(exist, target);
        if (patch.getDocDate() != null)
        {
            target.setDocDate(patch.getDocDate());
        }
        if (patch.getRemark() != null)
        {
            target.setRemark(patch.getRemark());
        }
        if (patch.getHandlerUserId() != null)
        {
            target.setHandlerUserId(patch.getHandlerUserId());
        }
        if (patch.getHandlerName() != null)
        {
            target.setHandlerName(patch.getHandlerName());
        }
        if (patch.getPurchaseDeptId() != null)
        {
            target.setPurchaseDeptId(patch.getPurchaseDeptId());
        }
        if (patch.getExpectedArrivalDate() != null)
        {
            target.setExpectedArrivalDate(patch.getExpectedArrivalDate());
        }
        if (patch.getSettleType() != null)
        {
            target.setSettleType(patch.getSettleType());
        }
        if (patch.getCurrency() != null)
        {
            target.setCurrency(patch.getCurrency());
        }
        if (patch.getItems() != null)
        {
            target.setItems(patch.getItems());
        }
        target.setId(exist.getId());
        target.setDocNo(exist.getDocNo());
        target.setStatus(exist.getStatus());
        target.setDeptId(exist.getDeptId());
        target.setCreateId(exist.getCreateId());
        target.setCreateBy(exist.getCreateBy());
        target.setCreateTime(exist.getCreateTime());
        target.setPosted(exist.getPosted());
        target.setDelFlag(exist.getDelFlag());
        target.setSupplierId(exist.getSupplierId());
        target.setSupplierName(exist.getSupplierName());
        target.setContractId(exist.getContractId());
        target.setContractNo(exist.getContractNo());
        target.setSourceDocType(exist.getSourceDocType());
        target.setSourceDocId(exist.getSourceDocId());
        target.setSourceDocNo(exist.getSourceDocNo());
        target.setSubmittedBy(exist.getSubmittedBy());
        target.setSubmittedAt(exist.getSubmittedAt());
        target.setApprovedBy(exist.getApprovedBy());
        target.setApprovedAt(exist.getApprovedAt());
        target.setVoidedBy(exist.getVoidedBy());
        target.setVoidedAt(exist.getVoidedAt());
        target.setVoidReason(exist.getVoidReason());
        return target;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrder(String id)
    {
        if (ErpPurRules.isBlank(id))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseOrder exist = requireAccessible(id);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_ORDER);
        itemMapper.deleteItemsByDocId(id);
        orderMapper.deleteOrderById(id);
    }

    /* ==================== 状态流转 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(String id, String action, String reason)
    {
        ErpPurchaseOrder exist = requireAccessible(id);
        ErpDocAction docAction = resolveAction(action);
        // 流转前的旧状态先取出来（不依赖"exist 与落库行是两个对象"这一假设）
        String oldStatus = exist.getStatus();
        String next = ErpDocStateMachine.checkAndNext(docAction, oldStatus,
                ErpDocType.PURCHASE_ORDER, hasItems(id), reason);
        ErpPurchaseOrder patch = new ErpPurchaseOrder();
        patch.setId(id);
        patch.setStatus(next);
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        if (docAction == ErpDocAction.SUBMIT)
        {
            patch.setSubmittedBy(currentUserId());
            patch.setSubmittedAt(now());
        }
        else if (docAction == ErpDocAction.APPROVE)
        {
            patch.setApprovedBy(currentUserId());
            patch.setApprovedAt(now());
        }
        else if (docAction == ErpDocAction.VOID)
        {
            patch.setVoidedBy(currentUserId());
            patch.setVoidedAt(now());
            patch.setVoidReason(ErpPurRules.trimToNull(reason));
        }
        else if (docAction == ErpDocAction.REJECT)
        {
            patch.setVoidReason(ErpPurRules.trimToNull(reason));
        }
        else if (docAction == ErpDocAction.UNAPPROVE)
        {
            // 反审核清掉审核痕迹（DDL 注释：反审核时置空）——专用语句，理由见 request 服务
            patch.setVoidReason(ErpPurRules.trimToNull(reason));
        }
        orderMapper.updateOrderStatus(patch);
        if (docAction == ErpDocAction.UNAPPROVE)
        {
            orderMapper.clearApprovalTrace(id, currentUserId(), currentUsername());
        }
        changeLogWriter().writeStatusChange(ErpDocType.PURCHASE_ORDER, id, oldStatus, next,
                ErpPurRules.trimToNull(reason), ErpPurRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
    }

    /**
     * 动作码 → 枚举。
     *
     * @param action 动作码
     * @return 枚举
     */
    protected ErpDocAction resolveAction(String action)
    {
        String code = ErpPurRules.trimToNull(action);
        if (code == null)
        {
            throw new ServiceException("单据动作不能为空");
        }
        for (ErpDocAction candidate : ErpDocAction.values())
        {
            if (candidate.getActionCode().equalsIgnoreCase(code))
            {
                return candidate;
            }
        }
        throw new ServiceException("未实现的动作：" + action);
    }

    /**
     * 是否有行项（提交前置条件）。
     *
     * @param docId 单据ID
     * @return 有行项返回 true
     */
    protected boolean hasItems(String docId)
    {
        List<ErpPurchaseOrderItem> items = itemMapper.selectItemsByDocId(docId);
        return items != null && !items.isEmpty();
    }

    /* ==================== 行项独立维护 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String insertItem(String docId, ErpPurchaseOrderItem item)
    {
        ErpPurchaseOrder exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_ORDER);
        if (item == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        List<ErpPurchaseOrderItem> current = itemMapper.selectItemsByDocId(docId);
        int rowNo = (current == null ? 0 : current.size()) + 1;
        ErpPurchaseOrderItem normalized = normalizeItem(item, rowNo, null);
        normalized.setId(IdUtils.fastSimpleUUID());
        normalized.setDocId(docId);
        List<ErpPurchaseOrderItem> single = new ArrayList<>(1);
        single.add(normalized);
        itemMapper.batchInsertItems(single);
        refreshOrderTotal(docId);
        return normalized.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(String docId, ErpPurchaseOrderItem item)
    {
        ErpPurchaseOrder exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_ORDER);
        if (item == null || ErpPurRules.isBlank(item.getId()))
        {
            throw new ServiceException("行项不存在");
        }
        ErpPurchaseOrderItem stored = itemMapper.selectItemById(item.getId());
        if (stored == null || !docId.equals(stored.getDocId()))
        {
            throw new ServiceException("行项不属于该采购单");
        }
        int rowNo = stored.getSeq() == null ? 1 : stored.getSeq().intValue();
        ErpPurchaseOrderItem normalized = normalizeItem(item, rowNo, stored);
        normalized.setId(stored.getId());
        normalized.setDocId(docId);
        normalized.setSeq(stored.getSeq());
        itemMapper.deleteItemById(stored.getId());
        List<ErpPurchaseOrderItem> single = new ArrayList<>(1);
        single.add(normalized);
        itemMapper.batchInsertItems(single);
        refreshOrderTotal(docId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(String docId, String itemId)
    {
        ErpPurchaseOrder exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_ORDER);
        ErpPurchaseOrderItem stored = itemMapper.selectItemById(itemId);
        if (stored == null || !docId.equals(stored.getDocId()))
        {
            throw new ServiceException("行项不属于该采购单");
        }
        itemMapper.deleteItemById(itemId);
        refreshOrderTotal(docId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ErpPurchaseOrderItem> resequence(String docId)
    {
        requireAccessible(docId);
        List<ErpPurchaseOrderItem> items = itemMapper.selectItemsByDocId(docId);
        if (items == null || items.isEmpty())
        {
            return new ArrayList<>();
        }
        int seq = 0;
        for (ErpPurchaseOrderItem item : items)
        {
            seq++;
            item.setSeq(Integer.valueOf(seq));
            itemMapper.updateItemSeq(item.getId(), Integer.valueOf(seq));
        }
        return items;
    }

    /**
     * 行项变动后重算并落库单据金额合计（先舍入再汇总）。
     *
     * @param docId 采购单ID
     */
    protected void refreshOrderTotal(String docId)
    {
        List<ErpPurchaseOrderItem> items = itemMapper.selectItemsByDocId(docId);
        BigDecimal total = ErpPurRules.totalAmountOf(items);
        ErpPurchaseOrder patch = new ErpPurchaseOrder();
        patch.setId(docId);
        patch.setTotalAmount(total);
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        orderMapper.updateOrderTotal(patch);
    }

    /* ==================== 已入库量回写（任务 3.5 的口径入口） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyReceivedQtyChange(String orderId, String itemId, BigDecimal deltaQty)
    {
        if (ErpPurRules.isBlank(orderId) || ErpPurRules.isBlank(itemId))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        if (deltaQty == null || deltaQty.signum() == 0)
        {
            // 0 变动是合法的 no-op（例如入库单行数量为 0 的脏数据），不写库、不报错
            return;
        }
        ErpPurchaseOrderItem item = itemMapper.selectItemById(itemId);
        if (item == null || !orderId.equals(item.getDocId()))
        {
            throw new ServiceException("行项不属于该采购单");
        }
        if (deltaQty.signum() > 0)
        {
            // 过账累加：不得超过剩余可入库量（调用方已在过账校验里拦过，这里再兜一层，
            // 因为"回写口径只能有一个入口"意味着边界也必须由这个入口守住）
            BigDecimal remaining = item.remainingQty();
            if (ErpAmounts.roundQty(deltaQty).compareTo(remaining) > 0)
            {
                throw new ServiceException("已入库数量超出剩余可入库量（剩余 "
                        + ErpAmounts.textOf(remaining) + "，本次 " + ErpAmounts.textOf(deltaQty) + "）");
            }
            itemMapper.increaseReceivedQty(itemId, ErpAmounts.roundQty(deltaQty));
        }
        else
        {
            // 红冲回退：库侧用 GREATEST(0, ...) 保证不为负（规格：回退且不小于 0）
            itemMapper.decreaseReceivedQty(itemId, ErpAmounts.roundQty(deltaQty.abs()));
        }
    }

    /* ==================== 公共校验与落库 ==================== */

    /**
     * 公共字段归一。
     *
     * @param order 采购单（就地归一）
     */
    protected void applyCommonFields(ErpPurchaseOrder order)
    {
        if (order.getDocDate() == null)
        {
            order.setDocDate(now());
        }
        if (order.getPosted() == null)
        {
            order.setPosted(ErpDocHeader.POSTED_NO);
        }
        if (order.getDelFlag() == null)
        {
            order.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        }
        if (ErpPurRules.isBlank(order.getCurrency()))
        {
            order.setCurrency(DEFAULT_CURRENCY);
        }
        if (order.getTotalAmount() == null)
        {
            order.setTotalAmount(BigDecimal.ZERO);
        }
    }

    /**
     * 行项归一 + 快照装配。
     *
     * @param order 采购单
     */
    protected void applyItems(ErpPurchaseOrder order)
    {
        List<ErpPurchaseOrderItem> items = order.getItems();
        if (items == null || items.isEmpty())
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        List<ErpPurchaseOrderItem> normalized = new ArrayList<>(items.size());
        int rowNo = 0;
        for (ErpPurchaseOrderItem item : items)
        {
            rowNo++;
            normalized.add(normalizeItem(item, rowNo, null));
        }
        order.setItems(normalized);
    }

    /**
     * 单行归一 + 物料/单位快照 + 数量精度与金额。
     *
     * @param item   入参行项
     * @param rowNo  行号
     * @param stored 库内行（编辑时保留 id/seq/已入库量）
     * @return 归一后的行项
     */
    protected ErpPurchaseOrderItem normalizeItem(ErpPurchaseOrderItem item, int rowNo,
                                                 ErpPurchaseOrderItem stored)
    {
        if (item == null)
        {
            throw new ServiceException("行 " + rowNo + "：" + ErpPurRules.MSG_NO_ITEMS);
        }
        item.setSeq(Integer.valueOf(rowNo));
        if (item.getId() == null && stored != null)
        {
            item.setId(stored.getId());
        }
        // ① 物料/单位守卫 + 快照 + 数量精度 + 行金额（base 的唯一实现点）
        applyItemSnapshot(requireMasterLookup(), item, rowNo);
        // ② 定点规整
        item.setQty(ErpAmounts.roundQty(item.getQty()));
        item.setUnitPrice(ErpAmounts.roundPrice(
                item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice()));
        // ③ 数量 > 0 / 单价非负 / 精度（文案与 tasks §3.1 逐字一致）
        ErpPurRules.checkItemsBasic(single(item));
        // ④ 已入库量只能由过账回写：对请求传入的值一律忽略
        item.setReceivedQty(stored == null ? BigDecimal.ZERO : stored.getReceivedQty());
        item.setRemainingQty(item.remainingQty());
        return item;
    }

    /**
     * 单元素列表。
     *
     * @param item 行项
     * @return 只有一个元素的列表
     */
    protected List<ErpPurchaseOrderItem> single(ErpPurchaseOrderItem item)
    {
        List<ErpPurchaseOrderItem> list = new ArrayList<>(1);
        list.add(item);
        return list;
    }

    /**
     * 行项落库（主表必须先落库）。
     *
     * @param order 采购单
     */
    protected void persistItems(ErpPurchaseOrder order)
    {
        List<ErpPurchaseOrderItem> items = order.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }
        for (ErpPurchaseOrderItem item : items)
        {
            item.setDocId(order.getId());
            if (item.getId() == null)
            {
                item.setId(IdUtils.fastSimpleUUID());
            }
            if (item.getReceivedQty() == null)
            {
                item.setReceivedQty(BigDecimal.ZERO);
            }
            if (item.getCreateId() == null)
            {
                item.setCreateId(currentUserId());
                item.setCreateBy(currentUsername());
            }
        }
        itemMapper.batchInsertItems(items);
    }

    /**
     * 供应商绑定（任务 3.2）：必填 + 存在 + 启用 + 写名称快照。
     *
     * @param order      采购单（就地写入 supplierId / supplierName）
     * @param supplierId 供应商ID
     */
    protected void bindSupplier(ErpPurchaseOrder order, String supplierId)
    {
        CtmsSupplier supplier = requireSupplier(supplierId);
        order.setSupplierId(supplier.getId());
        // 名称快照：改名/停用后历史单据仍显示旧名
        order.setSupplierName(supplier.getName() == null ? "" : supplier.getName());
    }

    /**
     * 收货仓库绑定（任务 3.2）：存在 + 启用 + 写名称快照（不落库，详情展示用）。
     *
     * @param order       采购单
     * @param warehouseId 仓库ID（空 → 清空默认收货仓库）
     */
    protected void bindReceiptWarehouse(ErpPurchaseOrder order, String warehouseId)
    {
        if (ErpPurRules.isBlank(warehouseId))
        {
            order.setReceiptWarehouseId(null);
            order.setReceiptWarehouseName(null);
            return;
        }
        ErpMasterGuards.MasterRecord warehouse =
                ErpMasterGuards.requireEnabledWarehouse(requireMasterLookup(), warehouseId);
        order.setReceiptWarehouseId(warehouse.getId());
        order.setReceiptWarehouseName(warehouse.getName());
    }

    /**
     * 合同绑定（任务 3.6）：仅采购方向、存在且未停用、写编号快照、<b>不回写合同</b>。
     *
     * @param target     采购单（就地写入 contractId / contractNo）
     * @param contractId 合同ID（空 → 解绑）
     */
    protected void bindContract(ErpPurchaseOrder target, String contractId)
    {
        if (ErpPurRules.isBlank(contractId))
        {
            // 下推带过来的合同由下推服务写入；显式传空只表示"不改"，新增时为空则清空
            if (target.getContractId() == null)
            {
                target.setContractNo(null);
            }
            return;
        }
        CtmsContract contract = loadContract(contractId);
        ErpPurRules.checkContractUsable(contract != null, contract == null ? null : contract.getDelFlag());
        ErpPurRules.checkContractDirection(contract.getSupplierId(), contract.getCustomerId(),
                ErpPurRules.DIRECTION_PURCHASE);
        target.setContractId(contract.getId());
        target.setContractNo(contract.getContractNo());
    }

    /**
     * 取合同（含已停用行；停用与否由调用方判定）。
     *
     * @param contractId 合同ID
     * @return 合同；查不到返回 null
     */
    protected CtmsContract loadContract(String contractId)
    {
        if (contractService == null)
        {
            throw new ServiceException(ErpPurRules.MSG_CONTRACT_DIRECTION);
        }
        CtmsContract query = new CtmsContract();
        query.setId(contractId);
        query.setIncludeDeleted(ErpPurRules.FLAG_YES);
        List<CtmsContract> rows = contractService.selectContractList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /* ==================== 单号 ==================== */

    /**
     * 生成采购单号（统一走平台编号服务；撞号自动重试）。
     *
     * @param day 单据日期
     * @return 单号
     */
    protected String resolveDocNo(Date day)
    {
        return docNoGenerator().nextDocNo(ErpDocType.PURCHASE_ORDER, day,
                new ErpPurDocNoGenerator.DocNoTaken()
                {
                    @Override
                    public boolean isTaken(String docNo)
                    {
                        return orderMapper.selectOrderByNo(docNo) != null;
                    }
                });
    }

    /* ==================== 访问与守卫 ==================== */

    /**
     * 按标识取行 + 数据范围校验。
     *
     * @param id 采购单ID
     * @return 采购单
     */
    protected ErpPurchaseOrder requireAccessible(String id)
    {
        if (ErpPurRules.isBlank(id))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseOrder order = orderMapper.selectOrderById(id);
        if (order == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        if (!canAccess(order.getCreateId(), order.getDeptId()))
        {
            throw new ServiceException("无权访问该" + ErpDocType.PURCHASE_ORDER.getLabel(), HttpStatus.FORBIDDEN);
        }
        return order;
    }

    /**
     * 数据范围判定（与列表 SQL 片段同口径）。
     *
     * @param rowCreateId 行的创建人ID
     * @param rowDeptId   行的归属部门ID
     * @return 可见返回 true
     */
    protected boolean canAccess(String rowCreateId, String rowDeptId)
    {
        if (hasAllScope())
        {
            return true;
        }
        return matchesScope(rowCreateId, rowDeptId, new ErpDocScope.DeptAncestorsLookup()
        {
            @Override
            public String ancestorsOf(String deptId)
            {
                return deptAncestors(deptId);
            }
        });
    }

    /**
     * 部门祖先链。
     *
     * @param deptId 部门ID
     * @return 祖先链；查不到返回 null
     */
    protected String deptAncestors(String deptId)
    {
        if (docLookupMapper == null || ErpPurRules.isBlank(deptId))
        {
            return null;
        }
        try
        {
            return docLookupMapper.selectDeptAncestors(deptId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 供应商引用校验（存在 + 启用 + 方向）。
     *
     * @param supplierId 供应商ID
     * @return 供应商
     */
    protected CtmsSupplier requireSupplier(String supplierId)
    {
        CtmsSupplier supplier = ErpPurRules.isBlank(supplierId) || partnerService == null
                ? null : partnerService.selectSupplierById(supplierId);
        ErpPurRules.checkSupplierDirection(supplierId, supplier != null,
                supplier != null && ErpPurRules.FLAG_YES.equals(supplier.getEnableFlag()));
        return supplier;
    }

    /**
     * 未过账（与 base 的 {@code ErpDocHeader.POSTED_NO} 同源）。
     */
    protected static final String POSTED_NO = ErpDocHeader.POSTED_NO;
}
