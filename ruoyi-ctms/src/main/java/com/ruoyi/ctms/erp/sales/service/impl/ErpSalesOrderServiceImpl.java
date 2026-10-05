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

import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocAction;
import com.ruoyi.ctms.erp.base.ErpDocNoGenerator;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocStateMachine;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderMapper;
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;
import com.ruoyi.ctms.erp.sales.support.ErpSalChangeLogWriter;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * <p> <b>销售订单服务实现</b>（2.0 B4 任务 4.1 / 4.4；规格 {@code erp/sales}）。 </p>
 *
 * <h3>与采购单对称的强约束（逐条对应 tasks.md §4.1 的断言）</h3>
 * <ol>
 *   <li> <b>只能引客户</b>：{@code customer_id} 必须存在且启用；把<b>供应商ID</b>传到客户字段时
 *        会先被识别出来（{@link #isSupplier(String)}）并报"客户档案不存在：…（该标识属于供应商档案…）"，
 *        绝不落库； </li>
 *   <li> <b>停用客户拒存</b>：{@code enable_flag='0'} 直接报错； </li>
 *   <li> <b>发货信息 5 列</b>随整单保存：交货日期/收货地址/联系人/联系电话/默认发货仓库； </li>
 *   <li> <b>金额先舍入再汇总</b>：行金额由 {@code ErpAmounts.lineAmount} 写入（HALF_UP 到 2 位），
 *        {@code total_amount} = 各行舍入后之和（{@code ErpAmounts.totalOf}），与采购线同口径； </li>
 *   <li> <b>编辑走 upsert</b>：保留行ID，避免把已出库量清零。 </li>
 * </ol>
 *
 * <p> 单号取号同样是"单一注入点"（{@link DocNoPort}），不做自算分支（§9.1）。 </p>
 *
 * @author 二开
 */
@Service
public class ErpSalesOrderServiceImpl implements IErpSalesOrderService
{
    /** 合同类型字典类型（方向判定的真源）。 */
    private static final String DICT_CONTRACT_TYPES = "contract_types";

    /** 币种兜底（与 DDL 的 DEFAULT 一致）。 */
    private static final String DEFAULT_CURRENCY = "CNY";

    /** 未过账标志（与 DDL 的 DEFAULT 一致）。 */
    private static final String POSTED_NO = "0";

    /** 未删除标志（与 DDL 的 DEFAULT 一致）。 */
    private static final String DEL_FLAG_NORMAL = "0";

    @Autowired(required = false)
    private ErpSalesOrderMapper orderMapper;

    @Autowired(required = false)
    private ErpSalesOrderItemMapper orderItemMapper;

    @Autowired(required = false)
    private CtmsPartnerMapper partnerMapper;

    @Autowired(required = false)
    private CtmsContractMapper contractMapper;

    /**
     * 出库单 Mapper（<b>只读</b>；T3 交付，因其 t29 提供
     * {@code selectBySourceDocId}）。仅用于"存在未作废下游出库单时禁止反审核"的守卫，
     * 销售线<b>不</b>写出库单表（写入一律委托 {@code IErpStockOutService}）。 </p>
     */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper stockOutMapper;

    @Autowired(required = false)
    private CtmsChangeLogMapper changeLogMapper;

    @Autowired(required = false)
    private ISysDictTypeService dictTypeService;

    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /**
     * 平台编号服务（简报 §9.1 冻结口径：8 类单据单号只允许通过它获取）。
     */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpSalesOrder> selectSalesOrderList(ErpSalesOrder query)
    {
        ErpSalesOrder condition = query == null ? new ErpSalesOrder() : query;
        // 数据范围片段由服务端白名单拼出（ErpDocScope），绝不接受请求参数
        condition.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpSalesOrder> list = mapper().selectSalesOrderList(condition);
        List<ErpSalesOrder> rows = list == null ? new ArrayList<ErpSalesOrder>() : list;
        // 列表派生列（不落库，返回前现算）：remainQtySum / canPush（t5b 的"下推出库单"入口显隐）
        applyDerivedColumns(rows);
        return rows;
    }

    /**
     * <p> <b>列表派生列的装配</b>（与采购线、销售申请单同款）。 </p>
     *
     * <ul>
     *   <li> {@code remainQtySum}：各行剩余可出库量（{@code qty − shipped_qty}，负值按 0）之和 ——
     *        t5b 的"下推出库单"按钮按它显隐； </li>
     *   <li> {@code canPush}：{@code remainQtySum > 0}（不含状态门槛，理由同申请单）。 </li>
     * </ul>
     *
     * <p> 每页只批量查一次行项（{@code selectItemsByDocIds} + 服务层 Java 分组，不 N+1）。 </p>
     *
     * @param rows 当前页的销售订单
     */
    protected void applyDerivedColumns(List<ErpSalesOrder> rows)
    {
        if (rows == null || rows.isEmpty() || orderItemMapper == null)
        {
            return;
        }
        List<String> docIds = new ArrayList<>(rows.size());
        for (ErpSalesOrder row : rows)
        {
            if (row != null && ErpSalRules.trim(row.getId()) != null)
            {
                docIds.add(row.getId());
            }
        }
        if (docIds.isEmpty())
        {
            return;
        }
        Map<String, List<ErpSalesOrderItem>> grouped = groupOrderItems(orderItemMapper.selectItemsByDocIds(docIds));
        for (ErpSalesOrder row : rows)
        {
            if (row == null)
            {
                continue;
            }
            List<ErpSalesOrderItem> items = grouped == null ? null : grouped.get(row.getId());
            row.setRemainQtySum(ErpSalRules.remainingShippableQtySum(items));
            row.setCanPush(Boolean.valueOf(row.computeCanPush()));
        }
    }

    @Override
    public ErpSalesOrder selectSalesOrderById(String id)
    {
        ErpSalesOrder doc = requireById(id);
        doc.setItems(selectSalesOrderItems(id));
        if (changeLogMapper != null)
        {
            doc.setChangeLogs(new ErpSalChangeLogWriter(changeLogMapper)
                    .listOf(ErpDocType.SALES_ORDER, id, null));
        }
        return doc;
    }

    @Override
    public List<ErpSalesOrderItem> selectSalesOrderItems(String docId)
    {
        if (orderItemMapper == null)
        {
            return new ArrayList<>();
        }
        List<ErpSalesOrderItem> items = orderItemMapper.selectItemsByDocId(docId);
        return items == null ? new ArrayList<ErpSalesOrderItem>() : items;
    }

    @Override
    public Map<String, List<ErpSalesOrderItem>> selectSalesOrderItemsByDocIds(List<String> docIds)
    {
        if (orderItemMapper == null || docIds == null || docIds.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        Map<String, List<ErpSalesOrderItem>> grouped =
                groupOrderItems(orderItemMapper.selectItemsByDocIds(docIds));
        return grouped;
    }

    /**
     * <p> <b>把平铺的行项按 {@code docId} 分组</b>（D-1 修复：分组从 MyBatis 的 {@code @MapKey}
     * 挪到服务层）。 </p>
     *
     * <p> 用 {@code LinkedHashMap} + {@code computeIfAbsent} 保持<b>稳定顺序</b>：
     * Mapper 语句是 {@code order by doc_id asc, seq asc}，键顺序 = 查询返回顺序、
     * 每个单据内仍按 {@code seq} 升序（与修复前的语义一致）。 </p>
     *
     * @param items 平铺的行项（可空）
     * @return {@code docId → 行项列表}（非 null；无行项的单据不出现键）
     */
    private static Map<String, List<ErpSalesOrderItem>> groupOrderItems(List<ErpSalesOrderItem> items)
    {
        Map<String, List<ErpSalesOrderItem>> grouped = new LinkedHashMap<>();
        if (items == null)
        {
            return grouped;
        }
        for (ErpSalesOrderItem item : items)
        {
            if (item == null || item.getDocId() == null)
            {
                continue;
            }
            grouped.computeIfAbsent(item.getDocId(), key -> new ArrayList<ErpSalesOrderItem>()).add(item);
        }
        return grouped;
    }

    /* ==================== 新增 / 修改 / 删除 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSalesOrder insertSalesOrder(ErpSalesOrder doc)
    {
        ErpSalRules.checkSalesOrderHeader(doc);
        List<ErpSalesOrderItem> items = doc.getItems();
        int rowCount = ErpSalRules.normalizeItems(items);
        ErpSalRules.requireAtLeastOneItem(rowCount);
        CtmsCustomer customer = requireUsableCustomer(doc.getCustomerId());
        doc.setCustomerName(customer.getName());
        // 创建快照（创建人 + 归属部门，服务端取值；DDL 两列都 NOT NULL + 外键）
        applyCreateSnapshot(doc);
        doc.setId(newId());
        doc.setDocNo(nextOrderDocNo(doc));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDelFlag(DEL_FLAG_NORMAL);
        doc.setPosted(POSTED_NO);
        if (ErpSalRules.trim(doc.getCurrency()) == null)
        {
            doc.setCurrency(DEFAULT_CURRENCY);
        }
        applyOwner(doc, true);
        doc.setTotalAmount(totalAmountOf(items));
        mapper().insertSalesOrder(doc);
        writeItems(doc.getId(), items, true);
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSalesOrder updateSalesOrder(ErpSalesOrder doc)
    {
        if (doc == null)
        {
            throw new ServiceException("销售订单不能为空");
        }
        ErpSalesOrder exist = requireById(doc.getId());
        // 只有草稿可编辑（文案由状态机统一给出）
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.SALES_ORDER);
        ErpSalRules.checkSalesOrderHeader(doc);
        List<ErpSalesOrderItem> items = doc.getItems();
        int rowCount = ErpSalRules.normalizeItems(items);
        ErpSalRules.requireAtLeastOneItem(rowCount);
        CtmsCustomer customer = requireUsableCustomer(doc.getCustomerId());
        doc.setCustomerName(customer.getName());

        // 状态、单号、标志位不可通过编辑改写：以库内值为准
        doc.setDocNo(exist.getDocNo());
        doc.setStatus(exist.getStatus());
        doc.setDelFlag(exist.getDelFlag());
        doc.setPosted(exist.getPosted());
        if (ErpSalRules.trim(doc.getCurrency()) == null)
        {
            doc.setCurrency(ErpSalRules.trim(exist.getCurrency()) == null ? DEFAULT_CURRENCY : exist.getCurrency());
        }
        applyOwner(doc, false);
        doc.setTotalAmount(totalAmountOf(items));
        mapper().updateSalesOrder(doc);
        // 编辑走 upsert：保留行ID，从而保留已出库量（buildItems 已按行ID查库复核）
        writeItems(doc.getId(), items, false);
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSalesOrderById(String id)
    {
        ErpSalesOrder exist = requireById(id);
        // 参考仓库口径：草稿可直接删除；已提交/已审核只能作废
        if (!ErpDocStatus.isEditable(exist.getStatus()))
        {
            throw new ServiceException("当前状态（" + ErpDocStatus.labelOf(exist.getStatus())
                    + "）不可删除销售订单，请先作废");
        }
        if (orderItemMapper != null)
        {
            orderItemMapper.deleteItemsByDocId(id);
        }
        mapper().deleteSalesOrderById(id);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitSalesOrder(String id, String remark)
    {
        ErpSalesOrder doc = requireById(id);
        boolean hasItems = mapper().countOrderItems(id) > 0;
        ErpDocStateMachine.check(ErpDocAction.SUBMIT, doc.getStatus(), ErpDocType.SALES_ORDER, hasItems, remark);
        applyAction(doc, ErpDocAction.SUBMIT, remark);
        mapper().updateSalesOrder(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveSalesOrder(String id)
    {
        ErpSalesOrder doc = requireById(id);
        applyAction(doc, ErpDocAction.APPROVE, null);
        mapper().updateSalesOrder(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectSalesOrder(String id, String reason)
    {
        ErpSalesOrder doc = requireById(id);
        applyAction(doc, ErpDocAction.REJECT, reason);
        mapper().updateSalesOrder(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidSalesOrder(String id, String reason)
    {
        ErpSalesOrder doc = requireById(id);
        applyAction(doc, ErpDocAction.VOID, reason);
        mapper().updateSalesOrder(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unapproveSalesOrder(String id, String reason)
    {
        ErpSalesOrder doc = requireById(id);
        // 上游守卫（与采购线同口径）**先于**状态变更：存在未作废的下游出库单时拒绝反审核。
        // 顺序同申请单：先行校验再改写，失败路径上单据对象不被改脏。
        checkNoDownstreamStockOuts(doc);
        applyAction(doc, ErpDocAction.UNAPPROVE, reason);
        mapper().updateSalesOrder(doc);
    }

    /**
     * <p> <b>上游守卫：存在未作废的下游出库单时禁止反审核</b>（2.0 B4 任务 4.3 / 4.4；
     * 规格 {@code erp/procurement} 的「下游存在时禁止反审核」在销售订单这一段的对称实现）。 </p>
     *
     * <p> 数据来源是 T3 交付的只读方法
     * {@code ErpStockOutMapper.selectBySourceDocId}（因 t29 而存在：语义为
     * {@code source_doc_id = 入参 and del_flag='0'}、无命中返回空集合），
     * 本组<b>只读</b>消费，不写别人的表。 </p>
     *
     * <p> 与申请单那一段同款：只要有一张<b>未作废</b>的下游出库单就拒绝，
     * <b>不静默级联作废</b>（已过账的出库单必须先红冲，这是业务语义而不是技术细节）。 </p>
     *
     * @param order 待反审核的销售订单
     * @throws ServiceException 存在未作废的下游出库单
     */
    protected void checkNoDownstreamStockOuts(ErpSalesOrder order)
    {
        if (order == null || stockOutMapper == null)
        {
            return;
        }
        List<com.ruoyi.ctms.erp.posting.domain.ErpStockOut> downstream =
                stockOutMapper.selectBySourceDocId(order.getId());
        if (downstream == null || downstream.isEmpty())
        {
            return;
        }
        for (com.ruoyi.ctms.erp.posting.domain.ErpStockOut out : downstream)
        {
            if (out != null && !ErpDocStatus.VOIDED.equals(ErpSalRules.trim(out.getStatus())))
            {
                throw new ServiceException(ErpSalRules.MSG_DOWNSTREAM_STOCK_OUT_BLOCK);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyShippedQtyChange(String orderDocId, String orderItemId, BigDecimal deltaQty)
    {
        String docId = ErpSalRules.trim(orderDocId);
        String itemId = ErpSalRules.trim(orderItemId);
        if (docId == null || itemId == null || deltaQty == null || deltaQty.signum() == 0)
        {
            return;
        }
        if (orderItemMapper == null)
        {
            throw new ServiceException("销售订单行项数据访问层不可用（ErpSalesOrderItemMapper 未注入）");
        }
        // 行必须属于该单据：否则不写（历史/脏数据不应把过账整体拖挂）
        ErpSalesOrderItem item = orderItemMapper.selectItemById(itemId);
        if (item == null || !docId.equals(ErpSalRules.trim(item.getDocId())))
        {
            return;
        }
        if (deltaQty.signum() > 0)
        {
            orderItemMapper.addShippedQty(itemId, ErpAmounts.roundQty(deltaQty));
        }
        else
        {
            // 负数走"回退"分支：SQL 用 greatest(0, shipped_qty - |delta|) 保证不小于 0
            orderItemMapper.subtractShippedQty(itemId, ErpAmounts.roundQty(deltaQty.abs()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeSalesOrder(String id)
    {
        ErpSalesOrder doc = requireById(id);
        applyAction(doc, ErpDocAction.COMPLETE, null);
        mapper().updateSalesOrder(doc);
    }

    /* ==================== 关联合同（仅销售方向） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String linkContract(String id, String contractId)
    {
        ErpSalesOrder doc = requireById(id);
        ErpDocStateMachine.checkEditable(doc.getStatus(), ErpDocType.SALES_ORDER);
        String oldNo = doc.getContractNo();
        String newNo = null;
        String normalized = ErpSalRules.trim(contractId);
        if (normalized != null)
        {
            CtmsContract contract = requireSaleContract(normalized);
            newNo = contract.getContractNo();
        }
        doc.setContractId(normalized);
        doc.setContractNo(newNo);
        applyOwner(doc, false);
        mapper().updateSalesOrder(doc);
        // 关联留痕（合同侧不回写任何字段：本方法只写本单据）
        if (changeLogMapper != null)
        {
            new ErpSalChangeLogWriter(changeLogMapper).writeContractChange(ErpDocType.SALES_ORDER, id,
                    oldNo, newNo, operatorId(), operatorName());
        }
        return newNo;
    }

    @Override
    public Map<String, Object> contractRefOf(String id)
    {
        ErpSalesOrder doc = requireById(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("docId", doc.getId());
        view.put("docNo", doc.getDocNo());
        view.put("contractId", doc.getContractId());
        view.put("contractNo", doc.getContractNo());
        return view;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 客户引用守卫：<b>存在 + 启用 + 不得是供应商</b>（任务 4.1 的两条断言在此收敛）。
     *
     * @param customerId 客户档案ID
     * @return 客户档案（用于写名称快照）
     * @throws ServiceException 未选 / 不存在 / 是供应商 / 已停用
     */
    protected CtmsCustomer requireUsableCustomer(String customerId)
    {
        String id = ErpSalRules.trim(customerId);
        if (id == null)
        {
            throw new ServiceException("销售订单必须选择客户档案");
        }
        CtmsCustomer customer = partnerMapper == null ? null : partnerMapper.selectCustomerById(id);
        if (customer == null && isSupplier(id))
        {
            // 供应商ID传到客户字段：规格场景要求"提示客户档案不存在"
            ErpSalRules.rejectSupplierAsCustomer(id);
        }
        ErpSalRules.checkCustomerUsable(id, customer == null ? null : customer.getName(),
                customer == null ? null : customer.getEnableFlag());
        return customer;
    }

    /**
     * 该标识是否属于<b>供应商</b>档案（客户字段误传供应商时的定位依据）。
     *
     * @param id 标识
     * @return 是供应商返回 true
     */
    protected boolean isSupplier(String id)
    {
        return partnerMapper != null && partnerMapper.selectSupplierById(id) != null;
    }

    /**
     * <p> <b>合同方向守卫</b>（任务 4.4）：存在 → 未停用 → 销售方向。 </p>
     *
     * <p> "采购方向的合同被拒"就发生在最后一段：类型标签不含"销售"即报
     * {@code 只能关联销售方向的合同（当前合同方向：采购/支出）}。 </p>
     *
     * @param contractId 合同ID
     * @return 合同实体（供调用方取编号快照）
     * @throws ServiceException 不存在 / 已停用 / 非销售方向
     */
    protected CtmsContract requireSaleContract(String contractId)
    {
        CtmsContract contract = contractMapper == null ? null : contractMapper.selectContractById(contractId);
        if (contract == null)
        {
            throw new ServiceException("关联合同不存在或已停用");
        }
        if (ErpSalRules.DEL_FLAG_DELETED.equals(ErpSalRules.trim(contract.getDelFlag())))
        {
            throw new ServiceException("关联合同不存在或已停用");
        }
        ErpSalRules.checkContractDirection(contractTypeLabelOf(contract.getType()));
        return contract;
    }

    /**
     * 合同类型标签归一（合同表存中文类型名；{@code SAL} 直接映射为"销售"）。
     *
     * @param type 合同类型
     * @return 标签；为空返回 null
     */
    protected String contractTypeLabelOf(String type)
    {
        String value = ErpSalRules.trim(type);
        if (value == null)
        {
            return null;
        }
        // ① 先用 dict_value 命中原样取值（"SAL" / "PUR" 这类代码值）
        String byCode = lookupContractLabel(value);
        if (byCode != null)
        {
            return byCode;
        }
        // ② 合同表存的是中文类型名，直接当标签用（订单/申请只做方向判定）
        return value;
    }

    /**
     * 按字典值查合同类型标签（{@code sys_dict_data.contract_types} 的 {@code dict_value→dict_label}）。
     *
     * <p> 字典不可用（未配置 / 服务异常）时返回 {@code null}：调用方回落成"原值当标签"，
     * 结果只是"可能更严格"（中文类型名不含"销售"会被拒），不会静默放行。 </p>
     *
     * @param dictValue 字典值（如 {@code SAL}）
     * @return 标签；未命中返回 null
     */
    protected String lookupContractLabel(String dictValue)
    {
        if (dictTypeService == null || dictValue == null)
        {
            return null;
        }
        try
        {
            List<SysDictData> dicts = dictTypeService.selectDictDataByType(DICT_CONTRACT_TYPES);
            if (dicts != null)
            {
                for (SysDictData data : dicts)
                {
                    if (data != null && dictValue.equalsIgnoreCase(ErpSalRules.trim(data.getDictValue()))
                            && ErpSalRules.trim(data.getDictLabel()) != null)
                    {
                        return data.getDictLabel();
                    }
                }
            }
        }
        catch (Exception e)
        {
            // 字典不可用：不静默放行，交给调用方按原值判定（见方法注释）
            return null;
        }
        return null;
    }

    /**
     * 取单据（不存在直接报错）。
     *
     * @param id 主键
     * @return 单据
     * @throws ServiceException 单据不存在
     */
    protected ErpSalesOrder requireById(String id)
    {
        String docId = ErpSalRules.trim(id);
        if (docId == null)
        {
            throw new ServiceException("销售订单ID不能为空");
        }
        ErpSalesOrder doc = mapper().selectSalesOrderById(docId);
        if (doc == null)
        {
            throw new ServiceException("销售订单不存在：" + docId);
        }
        return doc;
    }

    /**
     * 行项金额合计：先对每行经 {@code ErpAmounts.lineAmount} 舍入（{@code normalizeItems} 已写回
     * {@code amount}），再交给 {@code ErpAmounts.totalOf} 求和 —— <b>先舍入再汇总</b>，
     * 与采购线同口径（3 行 0.125 得 0.39 而不是 0.38）。
     *
     * @param items 行项
     * @return 合计（2 位）
     */
    protected BigDecimal totalAmountOf(List<ErpSalesOrderItem> items)
    {
        return ErpAmounts.totalOf(items);
    }

    /**
     * 写行项（新增走 insert；编辑走 upsert 以保留行ID与已出库量）。
     *
     * @param docId 单据ID
     * @param items 行项
     * @param isNew 是否新增路径
     */
    protected void writeItems(String docId, List<ErpSalesOrderItem> items, boolean isNew)
    {
        if (items == null || items.isEmpty() || orderItemMapper == null)
        {
            return;
        }
        List<ErpSalesOrderItem> rows = buildItems(docId, items, isNew);
        if (rows.isEmpty())
        {
            return;
        }
        if (isNew)
        {
            orderItemMapper.batchInsertItems(rows);
        }
        else
        {
            orderItemMapper.batchUpsertItems(rows);
        }
    }

    /**
     * 行项装配：主数据守卫 + 快照 + 行级发货仓库快照 + 行ID + 审计列。
     *
     * @param docId 单据ID
     * @param items 入参行项
     * @param isNew 是否新增路径
     * @return 待写入的行项
     */
    protected List<ErpSalesOrderItem> buildItems(String docId, List<ErpSalesOrderItem> items, boolean isNew)
    {
        List<ErpSalesOrderItem> rows = new ArrayList<>(items.size());
        int rowNo = 0;
        for (ErpSalesOrderItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException("行 " + rowNo + "：行项不能为空");
            }
            // 公共层守卫：物料/单位必须启用、写快照、校验数量精度、重算行金额
            ErpMasterGuards.applyItemSnapshot(masterLookup, item, rowNo);
            // 行级发货仓库（可空）：填了就写仓库名快照；未填时由出库单回落表头 ship_warehouse_id
            ErpMasterGuards.applyItemWarehouseSnapshot(masterLookup, item, rowNo);
            item.setDocId(docId);
            item.setSeq(rowNo);
            item.setCreateTime(now());
            item.setUpdateTime(now());
            item.setCreateId(currentUserId());
            item.setCreateBy(currentUsername());
            item.setUpdateId(currentUserId());
            item.setUpdateBy(currentUsername());
            if (!isNew)
            {
                // 编辑：保留库内行ID与已出库量（已出库量不参与更新列，见 XML）
                String existingId = ErpSalRules.trim(item.getId());
                if (existingId != null && orderItemMapper != null)
                {
                    ErpSalesOrderItem dbRow = orderItemMapper.selectItemById(existingId);
                    if (dbRow != null && docId.equals(dbRow.getDocId()))
                    {
                        item.setShippedQty(dbRow.getShippedQty());
                    }
                    else
                    {
                        // 行ID不属于本单据：按新增处理，避免跨单据串行
                        item.setId(newId());
                        item.setShippedQty(BigDecimal.ZERO);
                    }
                }
                else
                {
                    item.setId(newId());
                    item.setShippedQty(BigDecimal.ZERO);
                }
            }
            else
            {
                item.setId(newId());
                item.setShippedQty(BigDecimal.ZERO);
            }
            rows.add(item);
        }
        return rows;
    }

    /**
     * 状态动作的统一落点（状态机校验 + 痕迹列）。
     *
     * @param doc    单据
     * @param action 动作
     * @param reason 原因（驳回/作废/反审核必填）
     */
    protected void applyAction(ErpSalesOrder doc, ErpDocAction action, String reason)
    {
        boolean hasItems = mapper().countOrderItems(doc.getId()) > 0;
        String next = ErpDocStateMachine.checkAndNext(action, doc.getStatus(),
                ErpDocType.SALES_ORDER, hasItems, reason);
        doc.setStatus(next);
        if (action == ErpDocAction.SUBMIT)
        {
            doc.setSubmittedBy(currentUserId());
            doc.setSubmittedAt(now());
        }
        else if (action == ErpDocAction.APPROVE)
        {
            doc.setApprovedBy(currentUserId());
            doc.setApprovedAt(now());
        }
        else if (action == ErpDocAction.UNAPPROVE)
        {
            // 反审核置空审核痕迹（与采购线一致）
            doc.setApprovedBy(null);
            doc.setApprovedAt(null);
            doc.setVoidReason(reason);
        }
        else if (action == ErpDocAction.REJECT || action == ErpDocAction.VOID)
        {
            doc.setVoidReason(reason);
            if (action == ErpDocAction.VOID)
            {
                doc.setVoidedBy(currentUserId());
                doc.setVoidedAt(now());
            }
        }
        applyOwner(doc, false);
    }

    /**
     * 审计列（归属部门只在创建时快照，之后不重算 —— design D10）。
     *
     * @param doc   单据
     * @param isNew 是否创建路径
     */
    protected void applyOwner(ErpSalesOrder doc, boolean isNew)
    {
        Date now = now();
        doc.setUpdateTime(now);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        if (isNew)
        {
            doc.setCreateTime(now);
            doc.setCreateId(currentUserId());
            doc.setCreateBy(currentUsername());
            doc.setDeptId(currentDeptId());
        }
    }

    /**
     * <p> 创建快照：创建人 + 归属部门（<b>服务端取值</b>，绝不接受请求参数）。 </p>
     *
     * <p> 取不到就在落库前直接报错：DDL 把两列都约束为 NOT NULL + 外键
     * （{@code t_ctms_sales_order.dept_id → sys_dept}、{@code create_id → sys_user}），
     * 静默写空只会在 insert 时变成一个难懂的约束错误；而且数据范围的
     * SELF/DEPT 两档都依赖这两列（design D10）。 </p>
     *
     * @param doc 单据
     * @throws ServiceException 无登录上下文 / 未分配部门
     */
    protected void applyCreateSnapshot(ErpSalesOrder doc)
    {
        if (ErpSalRules.trim(currentUserId()) == null)
        {
            throw new ServiceException("无法确定创建人：请先登录后再创建单据");
        }
        if (ErpSalRules.trim(currentDeptId()) == null)
        {
            throw new ServiceException(
                    "当前账号未分配部门，无法创建单据（归属部门在创建时快照，数据范围判定依赖它）");
        }
    }

    /* ==================== 可替换的服务端上下文（单测用子类固定） ==================== */

    /**
     * 单号取号端口（§9.1 的单一注入点；与申请单的 {@code DocNoPort} 同款）。
     */
    public interface DocNoPort
    {
        /**
         * @param docType 单据类型
         * @return 新单号
         */
        String nextDocNo(ErpDocType docType);
    }

    /** 单号端口（默认未接入平台编号服务）。 */
    private DocNoPort docNoPort;

    /**
     * 设置单号端口（单测与 base 接线用）。
     *
     * @param docNoPort 端口实现（可空：恢复为"未接入"）
     */
    public void setDocNoPort(DocNoPort docNoPort)
    {
        this.docNoPort = docNoPort;
    }

    /**
     * 下一个单号（<b>编号服务的唯一注入点</b>，见类注释 §9.1）。
     *
     * <p> 优先级：单测的 {@link DocNoPort} → 平台公共实现 {@link ErpDocNoGenerator}；
     * 两者都没有时明确报错，<b>不做</b>自算兜底（简报 §9.1 冻结）。 </p>
     *
     * @param doc 单据（提供参考日期 = 单据日期）
     * @return 单号
     * @throws ServiceException 编号服务未装配
     */
    protected String nextOrderDocNo(ErpSalesOrder doc)
    {
        if (docNoPort != null)
        {
            return docNoPort.nextDocNo(ErpDocType.SALES_ORDER);
        }
        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为销售订单取号"
                    + "（base.ErpDocNoGenerator / t_code_config 9F2C…C104）");
        }
        return docNoGenerator.nextDocNo(ErpDocType.SALES_ORDER, doc == null ? null : doc.getDocDate());
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
     * 当前用户部门ID。
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        return ErpDocScope.currentDeptId();
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

    /**
     * 新主键。
     *
     * @return 32 位十六进制 UUID
     */
    protected String newId()
    {
        return IdUtils.fastSimpleUUID();
    }

    /**
     * 表头 Mapper（缺失即报错）。
     *
     * @return Mapper
     */
    private ErpSalesOrderMapper mapper()
    {
        if (orderMapper == null)
        {
            throw new ServiceException("销售订单数据访问层不可用（ErpSalesOrderMapper 未注入）");
        }
        return orderMapper;
    }

    /* ==================== 依赖注入点（生产由 Spring 注入；单测用子类替换为内存桩） ==================== */

    /**
     * 设置表头 Mapper（单测用）。
     *
     * @param orderMapper 表头 Mapper
     */
    public void setOrderMapper(ErpSalesOrderMapper orderMapper)
    {
        this.orderMapper = orderMapper;
    }

    /**
     * 设置行项 Mapper（单测用）。
     *
     * @param orderItemMapper 行项 Mapper
     */
    public void setOrderItemMapper(ErpSalesOrderItemMapper orderItemMapper)
    {
        this.orderItemMapper = orderItemMapper;
    }

    /**
     * 设置往来单位 Mapper（单测用）。
     *
     * @param partnerMapper 往来单位 Mapper
     */
    public void setPartnerMapper(CtmsPartnerMapper partnerMapper)
    {
        this.partnerMapper = partnerMapper;
    }

    /**
     * 设置合同 Mapper（单测用）。
     *
     * @param contractMapper 合同 Mapper
     */
    public void setContractMapper(CtmsContractMapper contractMapper)
    {
        this.contractMapper = contractMapper;
    }

    /**
     * 设置出库单 Mapper（单测用；只读，下游守卫）。
     *
     * @param stockOutMapper 出库单 Mapper
     */
    public void setStockOutMapper(com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper stockOutMapper)
    {
        this.stockOutMapper = stockOutMapper;
    }

    /**
     * 设置变更历史 Mapper（单测用）。
     *
     * @param changeLogMapper 变更历史 Mapper
     */
    public void setChangeLogMapper(CtmsChangeLogMapper changeLogMapper)
    {
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 设置字典服务（单测用）。
     *
     * @param dictTypeService 字典服务
     */
    public void setDictTypeService(ISysDictTypeService dictTypeService)
    {
        this.dictTypeService = dictTypeService;
    }

    /**
     * 设置主数据查询（单测用）。
     *
     * @param masterLookup 主数据查询
     */
    public void setMasterLookup(ErpMasterGuards.MasterLookup masterLookup)
    {
        this.masterLookup = masterLookup;
    }
}
