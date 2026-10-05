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
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestItemMapper;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseRequestMapper;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseRequestService;
import com.ruoyi.ctms.erp.procurement.support.ErpPurChangeLogWriter;
import com.ruoyi.ctms.erp.procurement.support.ErpPurDocNoGenerator;
import com.ruoyi.ctms.erp.procurement.support.ErpPurServiceSupport;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.service.ICtmsPartnerService;

/**
 * <p> 采购申请单服务实现（2.0 B4 任务 3.1~3.4、3.6）。 </p>
 *
 * <p> <b>本类刻意"薄"</b>：判定与文案在 {@link ErpPurRules}，状态流转在
 * {@link ErpDocStateMachine}，金额与精度在 {@link ErpAmounts}，主数据守卫与行项快照在
 * {@link ErpMasterGuards}，数据范围在 {@link ErpDocScope}，单号在
 * {@link ErpPurDocNoGenerator}。本类只做"取数 → 调用 → 落库 → 留痕"。 </p>
 *
 * <p> <b>四处容易写错的地方（都在方法注释里给了理由）</b>： </p>
 * <ol>
 *   <li> 行项必须<b>在主表之后</b>插：{@code t_ctms_purchase_request_item.doc_id} 是非空外键，
 *        顺序反了会得到数据库 1452（内存桩单测抓不到，只有真库能抓）；</li>
 *   <li> 归属部门/创建人是<b>服务端列</b>：新增取当前用户，编辑保持库内原值（越权防护）；</li>
 *   <li> 已下单量 {@code ordered_qty} <b>只能</b>由下推服务写：编辑时传入的值一律忽略，
 *        否则可以先改小已下单量再超量下推；</li>
 *   <li> 数量精度校验必须在数量被 {@code setScale(3)} 归整<b>之前</b>做，
 *        否则 {@code 1.5000}（单位 0 位小数）会被洗成 {@code 1.500} 而蒙混过关。</li>
 * </ol>
 *
 * @author 二开
 */
@Service
public class ErpPurchaseRequestServiceImpl extends ErpPurServiceSupport
        implements IErpPurchaseRequestService, IErpPurchaseRequestService.ItemWriter
{
    /** 详情返回的变更历史上限。 */
    private static final int CHANGE_LOG_LIMIT = 200;

    /* ==================== 依赖 ==================== */

    @Autowired
    private ErpPurchaseRequestMapper requestMapper;

    @Autowired
    private ErpPurchaseRequestItemMapper itemMapper;

    @Autowired
    private ErpPurchaseOrderMapper orderMapper;

    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /**
     * 主数据守卫查询回调（base 交付的 {@code ErpMasterLookupImpl}；单测用内存桩替换）。
     */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 部门祖先链查询（数据范围"本部门及下级"档）；单测可替换。 */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.base.mapper.ErpDocLookupMapper docLookupMapper;

    /** 往来单位服务（B3 交付，只消费；任务 3.2）。 */
    @Autowired(required = false)
    private ICtmsPartnerService partnerService;

    /** 合同台账服务（B3 交付；任务 3.6 复用它的方向与停用校验）。 */
    @Autowired(required = false)
    private ICtmsContractService contractService;

    /** base 的统一取号器（单据单号只能走它；撞号重试由 {@link ErpPurDocNoGenerator} 承担）。 */
    @Autowired(required = false)
    private com.ruoyi.ctms.erp.base.ErpDocNoGenerator erpDocNoGenerator;

    /** 变更历史写入点（懒建）。 */
    private ErpPurChangeLogWriter changeLogWriter;

    /** 单号生成器（懒建）。 */
    private ErpPurDocNoGenerator docNoGenerator;

    /* ==================== 协作对象（懒建 + 测试刷新） ==================== */

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
     * 主数据守卫回调（未装配时显式失败，而不是静默跳过校验）。
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
    public List<ErpPurchaseRequest> selectRequestList(ErpPurchaseRequest query)
    {
        ErpPurchaseRequest effective = query == null ? new ErpPurchaseRequest() : query;
        // 数据范围由服务端强制：片段只由服务端上下文拼出，绝不接受请求参数
        effective.setDataScopeSql(dataScopeSql());
        List<ErpPurchaseRequest> list = requestMapper.selectRequestList(effective);
        if (list == null || list.isEmpty())
        {
            return list == null ? new ArrayList<ErpPurchaseRequest>() : list;
        }
        // 派生列一次批量算完：逐行查行项会在列表页产生 N+1 次查询
        List<String> ids = new ArrayList<>(list.size());
        for (ErpPurchaseRequest row : list)
        {
            ids.add(row.getId());
        }
        Map<String, List<ErpPurchaseRequestItem>> itemsByDoc = groupItems(itemMapper.selectItemsByDocIds(ids));
        for (ErpPurchaseRequest row : list)
        {
            List<ErpPurchaseRequestItem> items = itemsByDoc.get(row.getId());
            row.setItems(items == null ? new ArrayList<ErpPurchaseRequestItem>() : items);
            fillDerived(row);
        }
        return list;
    }

    /**
     * 装配派生列（剩余可下推量合计、可下推标志、金额合计）。
     *
     * @param request 单据（就地填充）
     */
    protected void fillDerived(ErpPurchaseRequest request)
    {
        if (request == null)
        {
            return;
        }
        List<ErpPurchaseRequestItem> items = request.getItems();
        request.setRemainQtySum(ErpPurRules.remainingSum(items));
        request.setCanPush(Boolean.valueOf(request.computeCanPush()));
        request.setTotalAmount(ErpPurRules.totalAmountOf(items));
    }

    /**
     * 按 doc_id 分组（列表批量装配用）。
     *
     * @param items 行项集合
     * @return doc_id → 行项
     */
    protected Map<String, List<ErpPurchaseRequestItem>> groupItems(List<ErpPurchaseRequestItem> items)
    {
        Map<String, List<ErpPurchaseRequestItem>> grouped = new LinkedHashMap<>();
        if (items == null)
        {
            return grouped;
        }
        for (ErpPurchaseRequestItem item : items)
        {
            if (item == null || ErpPurRules.isBlank(item.getDocId()))
            {
                continue;
            }
            List<ErpPurchaseRequestItem> bucket = grouped.get(item.getDocId());
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
    public ErpPurchaseRequest selectRequestDetail(String id)
    {
        ErpPurchaseRequest request = requireAccessible(id);
        request.setItems(itemMapper.selectItemsByDocId(id));
        fillDerived(request);
        request.setChangeLogs(changeLogWriter().listOf(ErpDocType.PURCHASE_REQUEST, id,
                Integer.valueOf(CHANGE_LOG_LIMIT)));
        return request;
    }

    @Override
    public List<ErpPurchaseRequestItem> selectItemsByDocId(String docId)
    {
        requireAccessible(docId);
        List<ErpPurchaseRequestItem> items = itemMapper.selectItemsByDocId(docId);
        if (items == null)
        {
            return new ArrayList<>();
        }
        for (ErpPurchaseRequestItem item : items)
        {
            item.setRemainingQty(ErpPurRules.remainingQtyOf(item));
        }
        return items;
    }

    @Override
    public List<CtmsChangeLog> selectRequestChangeLogs(String docId)
    {
        requireAccessible(docId);
        return changeLogWriter().listOf(ErpDocType.PURCHASE_REQUEST, docId, null);
    }

    /* ==================== 新增 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String insertRequest(ErpPurchaseRequest request)
    {
        if (request == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        // 主键由服务端生成：请求体里的 id 一律忽略（与 B3 合同同款）
        request.setId(IdUtils.fastSimpleUUID());
        request.setStatus(ErpDocStatus.DRAFT);
        request.setPosted(ErpDocHeader.POSTED_NO);
        request.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        // 来源列：申请单是链路源头，恒为空（请求体传入的值一律忽略）
        request.setSourceDocType(null);
        request.setSourceDocId(null);
        request.setSourceDocNo(null);
        request.setDocNo(resolveDocNo(request.getDocDate()));
        request.setCreateTime(now());
        request.setUpdateTime(now());
        // 创建人 / 归属部门 / 登录名快照：由 base 统一写入并校验（取不到就报错，不写空值）
        applyCreateSnapshot(request);
        applyCommonFields(request);
        applyItems(request);
        bindContract(request, request.getContractId());
        // ⚠ 顺序不能反：行项表对 purchase_request 有非空外键，必须**先插主表**再插行项
        requestMapper.insertRequest(request);
        persistItems(request);
        return request.getId();
    }

    /* ==================== 编辑 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRequest(ErpPurchaseRequest request)
    {
        if (request == null || ErpPurRules.isBlank(request.getId()))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseRequest exist = requireAccessible(request.getId());
        // 只有草稿可编辑（规格场景「非草稿不可编辑」）
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_REQUEST);
        ErpPurchaseRequest target = mergeForUpdate(exist, request);
        applyCommonFields(target);
        applyItems(target);
        // 合同：入参传了才重新绑定（传空表示"这次编辑没动合同"，不是"解绑"）
        if (!ErpPurRules.isBlank(request.getContractId()))
        {
            bindContract(target, request.getContractId());
        }
        target.setUpdateId(currentUserId());
        target.setUpdateBy(currentUsername());
        target.setUpdateTime(now());
        requestMapper.updateRequest(target);
        // 行项全量替换：先删后插保证序号连续（与 B3 合同同款）
        itemMapper.deleteItemsByDocId(target.getId());
        persistItems(target);
    }

    /**
     * 编辑合并：只接受"用户可编辑"的列，其余一律回落库内原值。
     *
     * <p> 服务端列（归属部门/创建人/创建时间/单号/状态/痕迹列/来源列/过账与软删除标志/合同）
     * <b>不允许</b>被请求体改写。 </p>
     *
     * @param exist 库内行
     * @param patch 请求体
     * @return 合并后的对象
     */
    protected ErpPurchaseRequest mergeForUpdate(ErpPurchaseRequest exist, ErpPurchaseRequest patch)
    {
        ErpPurchaseRequest target = new ErpPurchaseRequest();
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
        if (patch.getRequestDeptId() != null)
        {
            target.setRequestDeptId(patch.getRequestDeptId());
        }
        if (patch.getNeedDate() != null)
        {
            target.setNeedDate(patch.getNeedDate());
        }
        if (patch.getSuggestSupplierId() != null)
        {
            target.setSuggestSupplierId(patch.getSuggestSupplierId());
        }
        if (patch.getPurpose() != null)
        {
            target.setPurpose(patch.getPurpose());
        }
        if (patch.getItems() != null)
        {
            target.setItems(patch.getItems());
        }
        // 明确回落：即使请求体里带了这些值也不生效
        target.setId(exist.getId());
        target.setDocNo(exist.getDocNo());
        target.setStatus(exist.getStatus());
        target.setDeptId(exist.getDeptId());
        target.setCreateId(exist.getCreateId());
        target.setCreateBy(exist.getCreateBy());
        target.setCreateTime(exist.getCreateTime());
        target.setPosted(exist.getPosted());
        target.setDelFlag(exist.getDelFlag());
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
    public void deleteRequest(String id)
    {
        if (ErpPurRules.isBlank(id))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseRequest exist = requireAccessible(id);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_REQUEST);
        // 物理删除仅供夹具清理：业务侧一律走 void。行项由 DB 级联删除，
        // 这里显式先删，保证在没有 FK 级联的环境（内存桩）里语义一致
        itemMapper.deleteItemsByDocId(id);
        requestMapper.deleteRequestById(id);
    }

    /* ==================== 状态流转 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(String id, String action, String reason)
    {
        ErpPurchaseRequest exist = requireAccessible(id);
        ErpDocAction docAction = resolveAction(action);
        // ⚠ 流转前的旧状态先取出来：下面 updateRequestStatus 之后不能再从 exist 读
        //   （生产里 exist 是查询出来的独立对象，但"先取旧值"是不依赖该假设的写法）
        String oldStatus = exist.getStatus();
        String next = ErpDocStateMachine.checkAndNext(docAction, oldStatus,
                ErpDocType.PURCHASE_REQUEST, hasItems(id), reason);
        // 上游守卫：存在未作废的下游采购单时禁止反审核（规格「下游存在时禁止反审核」）
        if (docAction == ErpDocAction.UNAPPROVE)
        {
            List<ErpPurchaseOrder> downstream = orderMapper.selectBySourceDocId(id);
            for (ErpPurchaseOrder order : downstream == null ? new ArrayList<ErpPurchaseOrder>() : downstream)
            {
                if (order != null && !ErpDocStatus.VOIDED.equals(order.getStatus()))
                {
                    throw new ServiceException(ErpPurRules.MSG_DOWNSTREAM_BLOCK);
                }
            }
        }
        ErpPurchaseRequest patch = new ErpPurchaseRequest();
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
            // 反审核清掉审核痕迹（DDL 注释：反审核时置空）——用专用语句，
            // 因为"置空"没法用 <if test="x != null"> 表达（null 会被跳过，痕迹留在库里）
            patch.setVoidReason(ErpPurRules.trimToNull(reason));
        }
        requestMapper.updateRequestStatus(patch);
        if (docAction == ErpDocAction.UNAPPROVE)
        {
            requestMapper.clearApprovalTrace(id, currentUserId(), currentUsername());
        }
        changeLogWriter().writeStatusChange(ErpDocType.PURCHASE_REQUEST, id, oldStatus, next,
                ErpPurRules.trimToNull(reason), ErpPurRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
    }

    /**
     * 动作码 → 枚举（未知动作显式拒绝，不用 {@code valueOf} 抛 {@code IllegalArgumentException}）。
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
     * 单据是否至少有一行行项（提交动作的前置条件）。
     *
     * @param docId 单据ID
     * @return 有行项返回 true
     */
    protected boolean hasItems(String docId)
    {
        List<ErpPurchaseRequestItem> items = itemMapper.selectItemsByDocId(docId);
        return items != null && !items.isEmpty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean checkAutoComplete(String id)
    {
        ErpPurchaseRequest exist = requireAccessible(id);
        String oldStatus = exist.getStatus();
        // 幂等第一层：已经是 completed 直接返回（不重复写日志）
        if (ErpDocStatus.COMPLETED.equals(oldStatus))
        {
            return false;
        }
        // 只有"已审核"才自动完成：草稿/待审核的归零不该发生（未审核不能下推），
        // 真发生了说明数据异常，宁可不动状态也不把脏数据洗成"已完成"
        if (!ErpDocStatus.APPROVED.equals(oldStatus))
        {
            return false;
        }
        List<ErpPurchaseRequestItem> items = itemMapper.selectItemsByDocId(id);
        if (!ErpPurRules.isFullyOrdered(items))
        {
            return false;
        }
        ErpPurchaseRequest patch = new ErpPurchaseRequest();
        patch.setId(id);
        patch.setStatus(ErpDocStatus.COMPLETED);
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        requestMapper.updateRequestStatus(patch);
        changeLogWriter().writeStatusChange(ErpDocType.PURCHASE_REQUEST, id, oldStatus,
                ErpDocStatus.COMPLETED, ErpPurRules.LOG_NOTE_AUTO_COMPLETE, ErpPurRules.LOG_SOURCE_AUTO,
                currentUserId(), currentUsername());
        return true;
    }

    /* ==================== 供下推服务调用的写入口 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal increaseOrderedQty(String docId, Map<String, BigDecimal> rows)
    {
        requireAccessible(docId);
        if (rows != null)
        {
            for (Map.Entry<String, BigDecimal> entry : rows.entrySet())
            {
                if (ErpPurRules.isBlank(entry.getKey()) || entry.getValue() == null
                        || entry.getValue().signum() <= 0)
                {
                    continue;
                }
                // 库内自增（并发安全）：两个下推请求不会互相覆盖
                itemMapper.increaseOrderedQty(entry.getKey(), ErpAmounts.roundQty(entry.getValue()));
            }
        }
        List<ErpPurchaseRequestItem> items = itemMapper.selectItemsByDocId(docId);
        return ErpPurRules.remainingSum(items);
    }

    /* ==================== 行项独立维护 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String insertItem(String docId, ErpPurchaseRequestItem item)
    {
        ErpPurchaseRequest exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_REQUEST);
        if (item == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        List<ErpPurchaseRequestItem> current = itemMapper.selectItemsByDocId(docId);
        int rowNo = (current == null ? 0 : current.size()) + 1;
        ErpPurchaseRequestItem normalized = normalizeItem(item, rowNo, null);
        normalized.setId(IdUtils.fastSimpleUUID());
        normalized.setDocId(docId);
        List<ErpPurchaseRequestItem> single = new ArrayList<>(1);
        single.add(normalized);
        itemMapper.batchInsertItems(single);
        return normalized.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(String docId, ErpPurchaseRequestItem item)
    {
        ErpPurchaseRequest exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_REQUEST);
        if (item == null || ErpPurRules.isBlank(item.getId()))
        {
            throw new ServiceException("行项不存在");
        }
        ErpPurchaseRequestItem stored = itemMapper.selectItemById(item.getId());
        if (stored == null || !docId.equals(stored.getDocId()))
        {
            throw new ServiceException("行项不属于该采购申请单");
        }
        int rowNo = stored.getSeq() == null ? 1 : stored.getSeq().intValue();
        ErpPurchaseRequestItem normalized = normalizeItem(item, rowNo, stored);
        normalized.setId(stored.getId());
        normalized.setDocId(docId);
        normalized.setSeq(stored.getSeq());
        // 全量替换单行：删旧插新，避免 update 语句漏列（行项列多且多数 NOT NULL）
        itemMapper.deleteItemById(stored.getId());
        List<ErpPurchaseRequestItem> single = new ArrayList<>(1);
        single.add(normalized);
        itemMapper.batchInsertItems(single);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(String docId, String itemId)
    {
        ErpPurchaseRequest exist = requireAccessible(docId);
        ErpDocStateMachine.checkEditable(exist.getStatus(), ErpDocType.PURCHASE_REQUEST);
        ErpPurchaseRequestItem stored = itemMapper.selectItemById(itemId);
        if (stored == null || !docId.equals(stored.getDocId()))
        {
            throw new ServiceException("行项不属于该采购申请单");
        }
        itemMapper.deleteItemById(itemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ErpPurchaseRequestItem> resequence(String docId)
    {
        requireAccessible(docId);
        List<ErpPurchaseRequestItem> items = itemMapper.selectItemsByDocId(docId);
        if (items == null || items.isEmpty())
        {
            return new ArrayList<>();
        }
        int seq = 0;
        for (ErpPurchaseRequestItem item : items)
        {
            seq++;
            item.setSeq(Integer.valueOf(seq));
            itemMapper.updateItemSeq(item.getId(), Integer.valueOf(seq));
        }
        return items;
    }

    /* ==================== 公共校验与落库 ==================== */

    /**
     * 公共字段归一（单据日期、NOT NULL 文本、过账与软删除标志兜底）。
     *
     * @param request 单据（就地归一）
     */
    protected void applyCommonFields(ErpPurchaseRequest request)
    {
        if (request.getDocDate() == null)
        {
            request.setDocDate(now());
        }
        if (request.getPosted() == null)
        {
            request.setPosted(ErpDocHeader.POSTED_NO);
        }
        if (request.getDelFlag() == null)
        {
            request.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        }
        if (request.getPurpose() == null)
        {
            request.setPurpose("");
        }
    }

    /**
     * 行项归一 + 快照装配（{@code request.items} 就地替换）。
     *
     * @param request 单据
     */
    protected void applyItems(ErpPurchaseRequest request)
    {
        List<ErpPurchaseRequestItem> items = request.getItems();
        if (items == null || items.isEmpty())
        {
            throw new ServiceException(ErpPurRules.MSG_NO_ITEMS);
        }
        List<ErpPurchaseRequestItem> normalized = new ArrayList<>(items.size());
        int rowNo = 0;
        for (ErpPurchaseRequestItem item : items)
        {
            rowNo++;
            normalized.add(normalizeItem(item, rowNo, null));
        }
        request.setItems(normalized);
    }

    /**
     * 单行归一 + 物料/单位快照 + 数量精度与金额（新增/编辑/行项独立维护三处共用同一口径）。
     *
     * @param item   入参行项
     * @param rowNo  行号（从 1 开始）
     * @param stored 库内行（编辑/单行维护时用于保留 id/seq/已下单量；新增传 null）
     * @return 归一后的行项
     */
    protected ErpPurchaseRequestItem normalizeItem(ErpPurchaseRequestItem item, int rowNo,
                                                   ErpPurchaseRequestItem stored)
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
        // ① 物料/单位守卫 + 快照 + 数量精度 + 行金额（base 的唯一实现点；数量此时仍是入参原值）
        applyItemSnapshot(requireMasterLookup(), item, rowNo);
        // ② 数量与单价的定点规整（校验已在①里完成，这里只是落库前的 scale 对齐）
        item.setQty(ErpAmounts.roundQty(item.getQty()));
        item.setUnitPrice(ErpAmounts.roundPrice(
                item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice()));
        // ③ 数量 > 0 / 单价非负 / 精度（再断言一次口径，文案与 tasks §3.1 逐字一致）
        ErpPurRules.checkItemsBasic(single(item));
        // ④ 已下单量只能由下推服务写：对请求传入的值一律忽略
        item.setOrderedQty(stored == null ? BigDecimal.ZERO : stored.getOrderedQty());
        item.setRemainingQty(ErpPurRules.remainingQtyOf(item));
        return item;
    }

    /**
     * 单元素列表（复用 {@code checkItemsBasic} 的逐行口径，避免为单行再写一套校验）。
     *
     * @param item 行项
     * @return 只有一个元素的列表
     */
    protected List<ErpPurchaseRequestItem> single(ErpPurchaseRequestItem item)
    {
        List<ErpPurchaseRequestItem> list = new ArrayList<>(1);
        list.add(item);
        return list;
    }

    /**
     * 行项落库（主表必须先落库：{@code doc_id} 非空外键）。
     *
     * @param request 单据
     */
    protected void persistItems(ErpPurchaseRequest request)
    {
        List<ErpPurchaseRequestItem> items = request.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }
        for (ErpPurchaseRequestItem item : items)
        {
            item.setDocId(request.getId());
            if (item.getId() == null)
            {
                item.setId(IdUtils.fastSimpleUUID());
            }
            if (item.getOrderedQty() == null)
            {
                item.setOrderedQty(BigDecimal.ZERO);
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
     * 合同绑定（任务 3.6）：仅采购方向、合同存在且未停用、写合同编号快照、<b>不回写合同</b>。
     *
     * <p> 校验复用 B3 的 {@code selectContractList}（与合同模块同一数据范围口径），
     * 判定与文案在 {@link ErpPurRules}。本方法<b>只读</b>合同：没有任何合同写入口被调用，
     * 所以"关联不回写合同"是结构性成立的（规格场景「关联不回写合同」）。 </p>
     *
     * @param target     单据（就地写入 {@code contractId} / {@code contractNo}）
     * @param contractId 合同ID（空 → 解绑，两个字段一起清空）
     */
    protected void bindContract(ErpPurchaseRequest target, String contractId)
    {
        if (ErpPurRules.isBlank(contractId))
        {
            target.setContractId(null);
            target.setContractNo(null);
            return;
        }
        CtmsContract contract = loadContract(contractId);
        ErpPurRules.checkContractUsable(contract != null, contract == null ? null : contract.getDelFlag());
        ErpPurRules.checkContractDirection(contract.getSupplierId(), contract.getCustomerId(),
                ErpPurRules.DIRECTION_PURCHASE);
        target.setContractId(contract.getId());
        // 只写编号快照；本方法不调用合同的任何写入口
        target.setContractNo(contract.getContractNo());
    }

    /**
     * 取合同（含已停用行：停用与否由调用方判定，否则报不出"已停用"这句文案）。
     *
     * @param contractId 合同ID
     * @return 合同；查不到返回 null
     */
    protected CtmsContract loadContract(String contractId)
    {
        if (contractService == null)
        {
            // 未装配合同服务时不能"放行"：宁可拒绝关联，也不让未校验的合同落库
            throw new ServiceException(ErpPurRules.MSG_CONTRACT_DIRECTION);
        }
        CtmsContract query = new CtmsContract();
        query.setId(contractId);
        // includeDeleted="1"：刻意放开停用过滤，让"已停用"能报出它自己的文案
        query.setIncludeDeleted(ErpPurRules.FLAG_YES);
        List<CtmsContract> rows = contractService.selectContractList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /* ==================== 单号 ==================== */

    /**
     * 生成申请单单号（统一走平台编号服务；撞号自动重试）。
     *
     * @param day 单据日期
     * @return 单号
     */
    protected String resolveDocNo(Date day)
    {
        return docNoGenerator().nextDocNo(ErpDocType.PURCHASE_REQUEST, day,
                new ErpPurDocNoGenerator.DocNoTaken()
                {
                    @Override
                    public boolean isTaken(String docNo)
                    {
                        return requestMapper.selectDocNoByNo(docNo) != null;
                    }
                });
    }

    /* ==================== 访问与守卫 ==================== */

    /**
     * 按标识取行 + 数据范围校验（详情/编辑/删除/流转/行项维护的统一入口）。
     *
     * <p> 范围外抛业务码 403：{@code GlobalExceptionHandler} 对带 code 的
     * {@code ServiceException} 返回 {@code AjaxResult.error(code, msg)}（HTTP 仍 200、按响应体业务码判定），
     * 与 B3 合同模块的越权形态一致，也正是 {@code tools/authz-check.ps1} 的 {@code IsForbidden} 所检查的形态。 </p>
     *
     * @param id 单据ID
     * @return 单据（含已作废与已软删除行）
     */
    protected ErpPurchaseRequest requireAccessible(String id)
    {
        if (ErpPurRules.isBlank(id))
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        ErpPurchaseRequest request = requestMapper.selectRequestById(id);
        if (request == null)
        {
            throw new ServiceException(ErpPurRules.MSG_NOT_FOUND);
        }
        if (!canAccess(request.getCreateId(), request.getDeptId()))
        {
            throw new ServiceException("无权访问该" + ErpDocType.PURCHASE_REQUEST.getLabel(),
                    HttpStatus.FORBIDDEN);
        }
        return request;
    }

    /**
     * 数据范围判定（与列表用的 SQL 片段同口径：改一处必须改另一处）。
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
     * 部门祖先链（"本部门及下级"档判定用）。
     *
     * @param deptId 部门ID
     * @return 形如 {@code "0,100,101"} 的祖先链；查不到返回 null
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
     * 供应商引用（任务 3.2）：存在 + 启用 + 方向（传客户ID 必然命不中供应商档案）。
     *
     * @param supplierId 供应商ID
     * @return 供应商（已确认可用）
     */
    protected CtmsSupplier requireSupplier(String supplierId)
    {
        CtmsSupplier supplier = ErpPurRules.isBlank(supplierId) || partnerService == null
                ? null : partnerService.selectSupplierById(supplierId);
        ErpPurRules.checkSupplierDirection(supplierId, supplier != null,
                supplier != null && ErpPurRules.FLAG_YES.equals(supplier.getEnableFlag()));
        return supplier;
    }
}
