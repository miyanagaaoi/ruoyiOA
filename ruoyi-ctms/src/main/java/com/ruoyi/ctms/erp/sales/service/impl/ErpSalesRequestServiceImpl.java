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
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestMapper;
import com.ruoyi.ctms.erp.sales.service.IErpSalesRequestService;
import com.ruoyi.ctms.erp.sales.support.ErpSalChangeLogWriter;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * <p> <b>销售申请单服务实现</b>（2.0 B4 任务 4.1 / 4.4；规格 {@code erp/sales}）。 </p>
 *
 * <h3>口径收敛点（逐条对应规格场景，改前先看这里）</h3>
 * <ol>
 *   <li> <b>状态机唯一实现点</b>：所有动作都走
 *        {@link ErpDocStateMachine#checkAndNext(ErpDocAction, String, ErpDocType, boolean, String)}
 *        与 {@link ErpDocStateMachine#checkEditable(String, ErpDocType)}，
 *        本类<b>不</b>写 {@code if (status.equals(...))}； </li>
 *   <li> <b>金额口径唯一实现点</b>：行金额与合计都经 {@code ErpAmounts}
 *        （申请单<b>没有</b> {@code total_amount} 列，合计按需现算）； </li>
 *   <li> <b>行项快照与数量精度</b>：交给公共层 {@link ErpMasterGuards#applyItemSnapshot}，
 *        因此"停用物料被拒 + 数量超出单位小数位被拒 + 快照固化"三件事各只有一份实现； </li>
 *   <li> <b>编辑不重建行ID</b>：走 upsert（{@code batchUpsertItems}），
 *        否则下推累加的 {@code ordered_qty} 会被清零，等于放开超量下推； </li>
 *   <li> <b>客户引用可空</b>（申请单与销售订单刻意不对称，移植清单 §2.8 #17），
 *        但传了就校验存在/未停用/不是供应商误传； </li>
 *   <li> <b>关联合同仅销售方向</b>，并保存合同编号快照，<b>不回写合同任何字段</b>。 </li>
 * </ol>
 *
 * <p> <b>单号取号</b>：按 §9.1 的冻结口径，单号必须走平台编号服务
 * （{@code ruoyi-serial} 的 {@code ICodeGenService} + {@code t_code_config} 种子，
 * 由 backend-base 在 {@code base.ErpDocNoGenerator} 里提供公共实现）。
 * 本类的 {@link #nextRequestDocNo()} 就是那个<b>唯一注入点</b>：base 的公共实现就绪后
 * 指向它即可，<b>不做"前缀 + 库内最大号 +1"的自算分支</b>（两套号源是最难查的一类不一致）。 </p>
 *
 * <p> <b>当前用户/时间的取值</b>：收敛到 {@link #currentUserId()} 等 {@code protected} 方法，
 * 单测用子类替换后即可固定时间与操作人（不必启动 Spring，也不受真实时钟影响）。 </p>
 *
 * @author 二开
 */
@Service
public class ErpSalesRequestServiceImpl implements IErpSalesRequestService
{
    /** 合同类型字典类型（方向判定的真源）。 */
    private static final String DICT_CONTRACT_TYPES = "contract_types";

    @Autowired(required = false)
    private ErpSalesRequestMapper requestMapper;

    @Autowired(required = false)
    private ErpSalesRequestItemMapper requestItemMapper;

    /**
     * 下游销售订单 Mapper（只读；上游守卫"存在未作废下游时禁止反审核"用）。
     *
     * <p> 只读<b>本包自己的表</b>（{@code t_ctms_sales_order}），不跨包读别人的表。 </p>
     */
    @Autowired(required = false)
    private ErpSalesOrderMapper orderMapper;

    @Autowired(required = false)
    private CtmsPartnerMapper partnerMapper;

    @Autowired(required = false)
    private CtmsContractMapper contractMapper;

    @Autowired(required = false)
    private CtmsChangeLogMapper changeLogMapper;

    @Autowired(required = false)
    private ISysDictTypeService dictTypeService;

    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /**
     * 平台编号服务（简报 §9.1 冻结口径：8 类单据单号只允许通过它获取）。
     *
     * <p> {@code required = false} 只为"内存桩单测不起 Spring"的便利；
     * 生产环境由容器注入。取号入口见 {@link #nextRequestDocNo(ErpSalesRequest)}。 </p>
     */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpSalesRequest> selectSalesRequestList(ErpSalesRequest query)
    {
        ErpSalesRequest condition = query == null ? new ErpSalesRequest() : query;
        // 数据范围片段由服务端白名单拼出（ErpDocScope），绝不接受请求参数
        condition.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpSalesRequest> list = mapper().selectSalesRequestList(condition);
        List<ErpSalesRequest> rows = list == null ? new ArrayList<ErpSalesRequest>() : list;
        // 列表派生列（不落库，返回前现算）：remainQtySum / canPush / totalAmount
        applyDerivedColumns(rows);
        return rows;
    }

    /**
     * <p> <b>列表派生列的装配</b>（tasks.md §3.4 的销售侧镜像、§4.2 的"下推按钮显隐"）。 </p>
     *
     * <ul>
     *   <li> {@code remainQtySum}：各行剩余可下推量（{@code qty − ordered_qty}，负值按 0）之和； </li>
     *   <li> {@code canPush}：{@code remainQtySum > 0}。⚠ 只看"还有没有量可推"，
     *        <b>不</b>表达状态门槛（"仅已审核可推"由下推接口再校验一次）——
     *        因为"归零即完成"后状态是 {@code completed} 而剩余量同样为 0； </li>
     *   <li> {@code totalAmount}：申请单表<b>无</b>该列（与采购申请单一致），
     *        由行项"先舍入再汇总"现算。 </li>
     * </ul>
     *
     * <p> 只对每页返回的 N 条做<b>一次</b>批量取行项（按 doc_id 分组），不做逐条查询。 </p>
     *
     * @param rows 当前页的申请单
     */
    protected void applyDerivedColumns(List<ErpSalesRequest> rows)
    {
        if (rows == null || rows.isEmpty() || requestItemMapper == null)
        {
            return;
        }
        List<String> docIds = new ArrayList<>(rows.size());
        for (ErpSalesRequest row : rows)
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
        Map<String, List<ErpSalesRequestItem>> grouped = groupRequestItems(requestItemMapper.selectItemsByDocIds(docIds));
        for (ErpSalesRequest row : rows)
        {
            if (row == null)
            {
                continue;
            }
            List<ErpSalesRequestItem> items = grouped == null ? null : grouped.get(row.getId());
            row.setRemainQtySum(ErpSalRules.remainingQtySum(items));
            row.setCanPush(Boolean.valueOf(row.computeCanPush()));
            if (items != null && !items.isEmpty())
            {
                row.setTotalAmount(ErpAmounts.totalOf(items));
            }
        }
    }

    @Override
    public ErpSalesRequest selectSalesRequestById(String id)
    {
        ErpSalesRequest doc = requireById(id);
        doc.setItems(selectSalesRequestItems(id));
        if (changeLogMapper != null)
        {
            doc.setChangeLogs(new ErpSalChangeLogWriter(changeLogMapper)
                    .listOf(ErpDocType.SALES_REQUEST, id, null));
        }
        return doc;
    }

    @Override
    public List<ErpSalesRequestItem> selectSalesRequestItems(String docId)
    {
        if (requestItemMapper == null)
        {
            return new ArrayList<>();
        }
        List<ErpSalesRequestItem> items = requestItemMapper.selectItemsByDocId(docId);
        return items == null ? new ArrayList<ErpSalesRequestItem>() : items;
    }

    @Override
    public BigDecimal remainingQtySum(String docId)
    {
        return ErpSalRules.remainingQtySum(selectSalesRequestItems(docId));
    }

    @Override
    public Map<String, List<ErpSalesRequestItem>> selectSalesRequestItemsByDocIds(List<String> docIds)
    {
        if (requestItemMapper == null || docIds == null || docIds.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        Map<String, List<ErpSalesRequestItem>> grouped =
                groupRequestItems(requestItemMapper.selectItemsByDocIds(docIds));
        return grouped;
    }

    /**
     * <p> <b>把平铺的行项按 {@code docId} 分组</b>（D-1 修复：分组从 MyBatis 的 {@code @MapKey}
     * 挪到服务层）。 </p>
     *
     * <p> 用 {@code LinkedHashMap} + {@code computeIfAbsent} 保持<b>稳定顺序</b>：
     * Mapper 语句是 {@code order by doc_id asc, seq asc}，所以键顺序 = 查询返回顺序、
     * 每个单据内的行项仍按 {@code seq} 升序（与修复前的语义一致）。 </p>
     *
     * @param items 平铺的行项（可空）
     * @return {@code docId → 行项列表}（非 null；无行项的单据不出现键）
     */
    private static Map<String, List<ErpSalesRequestItem>> groupRequestItems(List<ErpSalesRequestItem> items)
    {
        Map<String, List<ErpSalesRequestItem>> grouped = new LinkedHashMap<>();
        if (items == null)
        {
            return grouped;
        }
        for (ErpSalesRequestItem item : items)
        {
            if (item == null || item.getDocId() == null)
            {
                continue;
            }
            grouped.computeIfAbsent(item.getDocId(), key -> new ArrayList<ErpSalesRequestItem>()).add(item);
        }
        return grouped;
    }

    /* ==================== 新增 / 修改 / 删除 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSalesRequest insertSalesRequest(ErpSalesRequest doc)
    {
        ErpSalRules.checkSalesRequestHeader(doc);
        List<ErpSalesRequestItem> items = doc.getItems();
        // ① 行项守卫（数量/单价/序号/金额），先做以免非法数据落库
        ErpSalRules.normalizeItems(items);
        // ② 客户引用（可空；传了就校验存在/未停用/不是供应商误传）
        checkCustomerReference(doc.getCustomerId(), doc.getCustomerNameText());
        // ③ 创建快照（创建人 + 归属部门，服务端取值；DDL 两列都 NOT NULL + 外键）
        applyCreateSnapshot(doc);
        // ③ 表头字段
        doc.setId(newId());
        doc.setDocNo(nextRequestDocNo(doc));
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setDelFlag(ErpDocHeaderDefaults.DEL_FLAG_NORMAL);
        doc.setPosted(ErpDocHeaderDefaults.POSTED_NO);
        applyOwner(doc, true);
        mapper().insertSalesRequest(doc);
        // ④ 行项（新增走纯插入）
        writeItems(doc.getId(), items, true);
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpSalesRequest updateSalesRequest(ErpSalesRequest doc)
    {
        if (doc == null)
        {
            throw new ServiceException("销售申请单不能为空");
        }
        ErpSalesRequest exist = requireById(doc.getId());
        // 只有草稿可编辑（文案由状态机统一给出）
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.SALES_REQUEST);
        ErpSalRules.checkSalesRequestHeader(doc);
        List<ErpSalesRequestItem> items = doc.getItems();
        ErpSalRules.normalizeItems(items);
        checkCustomerReference(doc.getCustomerId(), doc.getCustomerNameText());

        // 状态与单号不可通过编辑改写：以库内值为准
        doc.setDocNo(exist.getDocNo());
        doc.setStatus(exist.getStatus());
        doc.setDelFlag(exist.getDelFlag());
        doc.setPosted(exist.getPosted());
        applyOwner(doc, false);
        mapper().updateSalesRequest(doc);
        // 编辑走 upsert：保留行ID，从而保留已下推量（buildItems 已按行ID查库复核）
        writeItems(doc.getId(), items, false);
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSalesRequestById(String id)
    {
        ErpSalesRequest exist = requireById(id);
        // 参考仓库口径：草稿可直接删除；已提交/已审核只能作废
        if (!ErpDocStatus.isEditable(exist.getStatus()))
        {
            throw new ServiceException("当前状态（" + ErpDocStatus.labelOf(exist.getStatus())
                    + "）不可删除销售申请单，请先作废");
        }
        if (requestItemMapper != null)
        {
            requestItemMapper.deleteItemsByDocId(id);
        }
        mapper().deleteSalesRequestById(id);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitSalesRequest(String id, String remark)
    {
        ErpSalesRequest doc = requireById(id);
        boolean hasItems = mapper().countRequestItems(id) > 0;
        ErpDocStateMachine.check(ErpDocAction.SUBMIT, doc.getStatus(), ErpDocType.SALES_REQUEST, hasItems, remark);
        applyAction(doc, ErpDocAction.SUBMIT, remark);
        mapper().updateSalesRequest(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveSalesRequest(String id)
    {
        ErpSalesRequest doc = requireById(id);
        applyAction(doc, ErpDocAction.APPROVE, null);
        mapper().updateSalesRequest(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectSalesRequest(String id, String reason)
    {
        ErpSalesRequest doc = requireById(id);
        applyAction(doc, ErpDocAction.REJECT, reason);
        mapper().updateSalesRequest(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidSalesRequest(String id, String reason)
    {
        ErpSalesRequest doc = requireById(id);
        applyAction(doc, ErpDocAction.VOID, reason);
        mapper().updateSalesRequest(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unapproveSalesRequest(String id, String reason)
    {
        ErpSalesRequest doc = requireById(id);
        // 上游守卫（与采购线同口径）**先于**状态变更：存在未作废的下游销售订单时拒绝反审核。
        // 顺序很重要——先行校验再改写，失败路径上单据对象不被改脏（真库靠事务，
        // 内存桩没有回滚，这个顺序让桩也能如实反映"被拒时状态不变"）。
        checkNoDownstreamOrders(doc);
        applyAction(doc, ErpDocAction.UNAPPROVE, reason);
        mapper().updateSalesRequest(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeSalesRequest(String id)
    {
        ErpSalesRequest doc = requireById(id);
        applyAction(doc, ErpDocAction.COMPLETE, null);
        mapper().updateSalesRequest(doc);
    }

    /* ==================== 关联合同（仅销售方向） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String linkContract(String id, String contractId)
    {
        ErpSalesRequest doc = requireById(id);
        ErpDocStateMachine.checkEditable(doc.getStatus(), ErpDocType.SALES_REQUEST);
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
        mapper().updateSalesRequest(doc);
        // 关联留痕（合同侧不回写任何字段：本方法只写本单据）
        if (changeLogMapper != null)
        {
            new ErpSalChangeLogWriter(changeLogMapper).writeContractChange(ErpDocType.SALES_REQUEST, id,
                    oldNo, newNo, operatorId(), operatorName());
        }
        return newNo;
    }

    @Override
    public Map<String, Object> contractRefOf(String id)
    {
        ErpSalesRequest doc = requireById(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("docId", doc.getId());
        view.put("docNo", doc.getDocNo());
        view.put("contractId", doc.getContractId());
        view.put("contractNo", doc.getContractNo());
        return view;
    }

    /**
     * <p> <b>合同方向守卫</b>（任务 4.4 的服务端部分）。 </p>
     *
     * <p> 三段校验：合同存在 → 未停用 → 类型是销售方向。方向判定用
     * {@code contract_types} 字典的<b>标签</b>（合同表存中文类型名），
     * 具体规则在 {@link ErpSalRules#checkContractDirection(String)}。 </p>
     *
     * @param contractId 合同ID
     * @return 合同实体（供调用方取编号快照）
     * @throws ServiceException 合同不存在 / 已停用 / 非销售方向
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
     * 合同类型标签归一（合同表存的是中文类型名；兼容直接传 {@code SAL} 的情形）。
     *
     * @param type 合同类型（{@code CtmsContract.type}）
     * @return 标签；为空返回 null
     */
    protected String contractTypeLabelOf(String type)
    {
        String value = ErpSalRules.trim(type);
        if (value == null)
        {
            return null;
        }
        if (ErpSalRules.CONTRACT_TYPE_SALE.equalsIgnoreCase(value))
        {
            return "销售";
        }
        if (dictTypeService == null)
        {
            return value;
        }
        try
        {
            List<SysDictData> dicts = dictTypeService.selectDictDataByType(DICT_CONTRACT_TYPES);
            if (dicts != null)
            {
                for (SysDictData data : dicts)
                {
                    if (data != null && value.equalsIgnoreCase(ErpSalRules.trim(data.getDictValue()))
                            && ErpSalRules.trim(data.getDictLabel()) != null)
                    {
                        return data.getDictLabel();
                    }
                }
            }
        }
        catch (Exception e)
        {
            // 字典不可用时按原值判定：宁可报"只能关联销售方向的合同"，也不静默放行
            return value;
        }
        return value;
    }

    /**
     * <p> <b>上游守卫：存在未作废的下游销售订单时禁止反审核</b>（2.0 B4 任务 4.4；
     * 规格 {@code erp/procurement} 的「下游存在时禁止反审核」在销售线的对称实现，
     * 参考仓库 {@code push_service.assert_no_downstream}）。 </p>
     *
     * <p> 口径与采购线（{@code ErpPurchaseRequestServiceImpl} 的反审核分支）<b>逐字同构</b>：
     * 按 {@code source_doc_id} 找出全部下游销售订单，只要有一张<b>未作废</b>就拒绝，
     * 文案固定为 {@link ErpSalRules#MSG_DOWNSTREAM_BLOCK}。 </p>
     *
     * <p> <b>刻意不级联作废下游</b>：静默把下游单据改成已作废是"不可逆地替用户做了决定"，
     * 而且会绕过下游自身的守卫（例如已过账的出库单）；参考仓库与采购线都选择"拦住并让用户处理"。
     * 下游全部作废或反审核之后，本守卫自然放行。 </p>
     *
     * @param request 待反审核的销售申请单
     * @throws ServiceException 存在未作废的下游销售订单
     */
    protected void checkNoDownstreamOrders(ErpSalesRequest request)
    {
        if (request == null || orderMapper == null)
        {
            return;
        }
        List<ErpSalesOrder> downstream = orderMapper.selectOrdersBySourceDocId(request.getId());
        if (downstream == null || downstream.isEmpty())
        {
            return;
        }
        for (ErpSalesOrder order : downstream)
        {
            if (order != null && !ErpDocStatus.VOIDED.equals(ErpSalRules.trim(order.getStatus())))
            {
                throw new ServiceException(ErpSalRules.MSG_DOWNSTREAM_BLOCK);
            }
        }
    }

    /* ==================== 内部方法 ==================== */

    /** 表头默认标志位（与 DDL 的 DEFAULT 一致）。 */
    private static final class ErpDocHeaderDefaults
    {
        private static final String POSTED_NO = "0";

        private static final String DEL_FLAG_NORMAL = "0";

        private ErpDocHeaderDefaults()
        {
        }
    }

    /**
     * 取单据（不存在直接报错；文案与 8 类单据一致）。
     *
     * @param id 主键
     * @return 单据
     * @throws ServiceException 单据不存在
     */
    protected ErpSalesRequest requireById(String id)
    {
        String docId = ErpSalRules.trim(id);
        if (docId == null)
        {
            throw new ServiceException("销售申请单ID不能为空");
        }
        ErpSalesRequest doc = mapper().selectSalesRequestById(docId);
        if (doc == null)
        {
            throw new ServiceException("销售申请单不存在：" + docId);
        }
        return doc;
    }

    /**
     * 客户引用守卫（可空；传了就校验）。与销售订单共用同一段文案来源。
     *
     * @param customerId   客户ID（可空）
     * @param customerName 客户名称文本（文案用，可空）
     */
    protected void checkCustomerReference(String customerId, String customerName)
    {
        String id = ErpSalRules.trim(customerId);
        if (id == null)
        {
            return;
        }
        String enableFlag = loadCustomerEnableFlag(id);
        if (enableFlag == null && isSupplier(id))
        {
            // 把供应商ID传到客户字段：规格场景要求"提示客户档案不存在"
            ErpSalRules.rejectSupplierAsCustomer(id);
        }
        ErpSalRules.checkCustomerUsable(id, customerName, enableFlag);
    }

    /**
     * 取客户档案的启用标志（{@code null} = 档案不存在）。
     *
     * @param customerId 客户ID
     * @return 启用标志；不存在返回 null
     */
    protected String loadCustomerEnableFlag(String customerId)
    {
        if (partnerMapper == null)
        {
            return null;
        }
        com.ruoyi.ctms.domain.CtmsCustomer customer = partnerMapper.selectCustomerById(customerId);
        return customer == null ? null : customer.getEnableFlag();
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
     * 写行项（新增走 insert；编辑走 upsert 以保留行ID与已下推量）。
     *
     * @param docId   单据ID
     * @param items   行项（可空或空 = 不改行项）
     * @param isNew   是否新增路径
     */
    protected void writeItems(String docId, List<ErpSalesRequestItem> items, boolean isNew)
    {
        if (items == null || items.isEmpty() || requestItemMapper == null)
        {
            return;
        }
        List<ErpSalesRequestItem> rows = buildItems(docId, items, isNew);
        if (rows.isEmpty())
        {
            return;
        }
        if (isNew)
        {
            requestItemMapper.batchInsertItems(rows);
        }
        else
        {
            requestItemMapper.batchUpsertItems(rows);
        }
    }

    /**
     * 行项装配：主数据守卫 + 快照 + 行ID + 审计列。
     *
     * @param docId 单据ID
     * @param items 入参行项（有序）
     * @param isNew 是否新增路径（新增时行ID一律新生成）
     * @return 待写入的行项
     */
    protected List<ErpSalesRequestItem> buildItems(String docId, List<ErpSalesRequestItem> items, boolean isNew)
    {
        List<ErpSalesRequestItem> rows = new ArrayList<>(items.size());
        int rowNo = 0;
        for (ErpSalesRequestItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException("行 " + rowNo + "：行项不能为空");
            }
            // 公共层守卫：物料/单位必须启用、写快照、校验数量精度、重算行金额
            ErpMasterGuards.applyItemSnapshot(masterLookup, item, rowNo);
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
                // 编辑：保留库内行ID与已下推量（已下推量不参与更新列，见 XML）
                String existingId = ErpSalRules.trim(item.getId());
                if (existingId != null && requestItemMapper != null)
                {
                    ErpSalesRequestItem dbRow = requestItemMapper.selectItemById(existingId);
                    if (dbRow != null && docId.equals(dbRow.getDocId()))
                    {
                        item.setOrderedQty(dbRow.getOrderedQty());
                    }
                    else
                    {
                        // 行ID不属于本单据：按新增处理，避免跨单据串行
                        item.setId(newId());
                        item.setOrderedQty(BigDecimal.ZERO);
                    }
                }
                else
                {
                    item.setId(newId());
                    item.setOrderedQty(BigDecimal.ZERO);
                }
            }
            else
            {
                item.setId(newId());
                item.setOrderedQty(BigDecimal.ZERO);
            }
            rows.add(item);
        }
        return rows;
    }

    /**
     * 状态动作的统一落点：状态机校验 → 状态与痕迹列写入。
     *
     * @param doc    单据
     * @param action 动作
     * @param reason 原因（驳回/作废/反审核必填）
     */
    protected void applyAction(ErpSalesRequest doc, ErpDocAction action, String reason)
    {
        boolean hasItems = mapper().countRequestItems(doc.getId()) > 0;
        String next = ErpDocStateMachine.checkAndNext(action, doc.getStatus(),
                ErpDocType.SALES_REQUEST, hasItems, reason);
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
     * 创建/更新审计列（归属部门只在创建时快照，之后不重算 —— design D10）。
     *
     * @param doc   单据
     * @param isNew 是否创建路径
     */
    protected void applyOwner(ErpSalesRequest doc, boolean isNew)
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
     * （{@code t_ctms_sales_request.dept_id → sys_dept}、{@code create_id → sys_user}），
     * 静默写空只会在 insert 时变成一个难懂的约束错误；而且数据范围的
     * SELF/DEPT 两档都依赖这两列（design D10）。 </p>
     *
     * @param doc 单据
     * @throws ServiceException 无登录上下文 / 未分配部门
     */
    protected void applyCreateSnapshot(ErpSalesRequest doc)
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
     * <p> <b>单号取号的注入端口</b>（§9.1 的冻结口径）。 </p>     * <p> 定义成本类的嵌套接口而不是直接依赖 {@code base.ErpDocNoGenerator}，
     * 是为了在公共实现就绪前保持"单一注入点、零自算分支"： </p>
     * <ul>
     *   <li> 生产：base 的 {@code ErpDocNoGenerator} 就绪后，在这里调它的
     *        {@code nextDocNo(ErpDocType.SALES_REQUEST)}（把实现换成委托即可，本类其余代码不动）； </li>
     *   <li> 单测：子类 {@code setDocNoPort} 给一个确定性实现（不起 Spring、不连库）。 </li>
     * </ul>
     *
     * <p> 刻意<b>不做</b>"前缀 + 库内最大号 +1"的兜底：两套号源并存会产生最难查的那类不一致。 </p>
     */
    public interface DocNoPort
    {
        /**
         * @param docType 单据类型
         * @return 新单号
         */
        String nextDocNo(ErpDocType docType);
    }

    /** 单号端口（默认未接入平台编号服务；见 {@link DocNoPort}）。 */
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
     * <p> 优先级：单测的 {@link DocNoPort} → 平台公共实现 {@link ErpDocNoGenerator}。
     * 两者都没有时明确报错，<b>不做</b>"前缀 + 库内最大号 +1"的自算兜底
     * （两套号源并存会产生最难查的那类不一致，且与简报 §9.1 的冻结口径冲突）。 </p>
     *
     * <p> 参考日期传<b>单据日期</b>：补录上月单据会落在上月的号段里，不提前消耗当月序号
     * （与 {@code ErpDocNoGenerator.nextDocNo(ErpDocType, Date)} 的注释一致）。 </p>
     *
     * @return 单号
     * @throws ServiceException 编号服务未装配
     */
    protected String nextRequestDocNo(ErpSalesRequest doc)
    {
        if (docNoPort != null)
        {
            return docNoPort.nextDocNo(ErpDocType.SALES_REQUEST);
        }        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为销售申请单取号"
                    + "（base.ErpDocNoGenerator / t_code_config 9F2C…C103）");
        }
        return docNoGenerator.nextDocNo(ErpDocType.SALES_REQUEST, doc == null ? null : doc.getDocDate());
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
     * 当前用户部门ID（创建时快照；数据范围判定依据）。
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        return ErpDocScope.currentDeptId();
    }

    /**
     * 操作人ID（写留痕用；无登录上下文给 {@code 1}）。
     *
     * @return 用户ID
     */
    protected String operatorId()
    {
        return ErpSalChangeLogWriter.operatorId(currentUserId());
    }

    /**
     * 操作人登录名（写留痕用；无登录上下文给 {@code system}）。
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
     * @return 32 位十六进制 UUID（大写，落在接口路径变量的字符白名单内）
     */
    protected String newId()
    {
        return IdUtils.fastSimpleUUID();
    }

    /**
     * 表头 Mapper（缺失即报错，而不是静默 NPE）。
     *
     * @return Mapper
     */
    private ErpSalesRequestMapper mapper()
    {
        if (requestMapper == null)
        {
            throw new ServiceException("销售申请单数据访问层不可用（ErpSalesRequestMapper 未注入）");
        }
        return requestMapper;
    }

    /* ==================== 依赖注入点（生产由 Spring 注入；单测用子类替换为内存桩） ==================== */

    /**
     * 设置表头 Mapper（单测用）。
     *
     * @param requestMapper 表头 Mapper
     */
    public void setRequestMapper(ErpSalesRequestMapper requestMapper)
    {
        this.requestMapper = requestMapper;
    }

    /**
     * 设置行项 Mapper（单测用）。
     *
     * @param requestItemMapper 行项 Mapper
     */
    public void setRequestItemMapper(ErpSalesRequestItemMapper requestItemMapper)
    {
        this.requestItemMapper = requestItemMapper;
    }

    /**
     * 设置下游销售订单 Mapper（单测用；上游守卫）。
     *
     * @param orderMapper 销售订单 Mapper
     */
    public void setOrderMapper(ErpSalesOrderMapper orderMapper)
    {
        this.orderMapper = orderMapper;
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
