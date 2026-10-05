package com.ruoyi.ctms.erp.stockops.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocAction;
import com.ruoyi.ctms.erp.base.ErpDocNoGenerator;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocStateMachine;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;
import com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess;
import com.ruoyi.ctms.erp.posting.service.IErpStockJournalService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;
import com.ruoyi.ctms.erp.posting.support.ErpStockChangeLogWriter;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsRules;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransferItem;
import com.ruoyi.ctms.erp.stockops.mapper.ErpTransferItemMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpTransferMapper;
import com.ruoyi.ctms.erp.stockops.service.IErpTransferService;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>调拨单服务实现</b>（2.0 B4 任务 6.1/6.2）。 </p>
 *
 * <p> <b>为什么调拨过账不由本类自己写结存</b>：结存与流水的唯一写入点是
 * {@link IErpStockJournalService}（T3 交付）。本类只把每行展开成<b>两条行指令</b>：
 * 调出仓 {@code -qty}（业务类型 {@code 调拨出库}）、调入仓 {@code +qty}（{@code 调拨入库}），
 * 于是"行锁、按（物料, 仓库）排序取锁、负库存校验、变动后结存快照、重复键重试"
 * 全部复用同一份实现 —— 调拨不会成为第二个可能写错库存的地方。 </p>
 *
 * <p> <b>两阶段的落点</b>： </p>
 * <ul>
 *   <li> 阶段一（校验，不写库）：{@link #insertTransfer}/{@link #updateTransfer} 与
 *        {@link #approveTransfer} 都先做单据级校验（两仓非空且不同、至少一行、每行数量 &gt; 0）；
 *        仓库可用量由引擎在<b>锁内</b>校验（"调出仓不足 ⇒ 整单不写入"由引擎的两阶段保证）； </li>
 *   <li> 阶段二（写入）：一次性提交全部行指令，任一失败整体回滚。 </li>
 * </ul>
 *
 * <p> <b>行项不使用行级仓库</b>（tasks.md §6.1）：归一化时把 {@code warehouse_id/name} 显式清空，
 * 即使请求里塞了行级仓库也不会落库（避免"以哪个仓为准"的第二套语义）。 </p>
 *
 * <p> <b>单号</b>：走平台编号服务（{@link ErpDocNoGenerator#nextDocNo(ErpDocType, Date)}，
 * 前缀 {@code DB}），只在创建时取一次；Bean 未装配时明确报错，不做本地兜底。 </p>
 *
 * @author 二开
 */
@Service
public class ErpTransferServiceImpl implements IErpTransferService
{
    /** 本服务负责的单据类型。 */
    private static final ErpDocType DOC_TYPE = ErpDocType.STOCK_TRANSFER;

    /** 调拨不产生金额：单价与金额恒为 0（流水单价也写 0）。 */
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.0000");

    /** 调拨单表头 Mapper。 */
    @Autowired
    private ErpTransferMapper transferMapper;

    /** 行项 Mapper。 */
    @Autowired
    private ErpTransferItemMapper transferItemMapper;

    /** 变更历史 Mapper（B3 交付的 t_ctms_change_log）。 */
    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /** 过账引擎（结存与流水的唯一写入服务）。 */
    @Autowired
    private IErpStockJournalService journalService;

    /** 单据对象访问校验（存在性 + 四档数据范围）。 */
    @Autowired
    private IErpDocObjectAccess docObjectAccess;

    /** 主数据守卫的回调查找（仓库存在性/启用状态与快照）；可空以便脱库单测。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 平台编号服务（base 包；可空时取号会明确报错）。 */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpTransfer> selectTransferList(ErpTransfer query)
    {
        ErpTransfer effective = query == null ? new ErpTransfer() : query;
        // 数据范围由服务端强制：片段只由 ErpDocScope 依登录上下文拼出
        effective.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpTransfer> list = transferMapper.selectTransferList(effective);
        return list == null ? new ArrayList<ErpTransfer>() : list;
    }

    @Override
    public ErpTransfer selectTransferDetail(String id)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        doc.setItems(loadItems(id));
        return doc;
    }

    @Override
    public List<CtmsChangeLog> selectChangeLogList(String id)
    {
        requireExisting(id);
        requireAccessible(id);
        return changeLog().listOf(DOC_TYPE, id, null);
    }

    /* ==================== 编辑 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer insertTransfer(ErpTransfer doc)
    {
        return insert(doc, ErpDocStatus.DRAFT, ErpStockOpsRules.LOG_SOURCE_MANUAL, "新建调拨单");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer updateTransfer(ErpTransfer doc)
    {
        if (doc == null || ErpStockOpsRules.isBlank(doc.getId()))
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NOT_FOUND);
        }
        ErpTransfer exist = requireExisting(doc.getId());
        requireAccessible(doc.getId());
        ErpDocStateMachine.checkEditable(exist.getStatus(), DOC_TYPE);

        // 不可改字段一律以库内为准（单号、快照三列、状态与痕迹列、过账标记）
        doc.setDocNo(exist.getDocNo());
        doc.setStatus(exist.getStatus());
        doc.setPosted(exist.getPosted());
        doc.setDelFlag(exist.getDelFlag());
        doc.setDeptId(exist.getDeptId());
        doc.setCreateId(exist.getCreateId());
        doc.setCreateBy(exist.getCreateBy());
        doc.setSubmittedBy(exist.getSubmittedBy());
        doc.setSubmittedAt(exist.getSubmittedAt());
        doc.setApprovedBy(exist.getApprovedBy());
        doc.setApprovedAt(exist.getApprovedAt());
        doc.setVoidedBy(exist.getVoidedBy());
        doc.setVoidedAt(exist.getVoidedAt());
        doc.setVoidReason(exist.getVoidReason());
        if (doc.getDocDate() == null)
        {
            doc.setDocDate(exist.getDocDate());
        }
        if (ErpStockOpsRules.isBlank(doc.getFromWarehouseId()))
        {
            doc.setFromWarehouseId(exist.getFromWarehouseId());
        }
        if (ErpStockOpsRules.isBlank(doc.getToWarehouseId()))
        {
            doc.setToWarehouseId(exist.getToWarehouseId());
        }
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());

        // 两仓校验 + 名称快照（"两仓相同"在保存处就被拒，不等到审核）
        applyWarehouseSnapshots(doc);
        List<ErpTransferItem> items = normalizeItems(doc);
        transferMapper.updateTransfer(doc);
        // 行项全量替换（先删后插，同一事务）
        transferItemMapper.deleteItemsByDocId(doc.getId());
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_ITEMS, null,
                "共 " + items.size() + " 行（" + doc.getFromWarehouseName() + " → " + doc.getToWarehouseName() + "）",
                "编辑调拨单", ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTransferByIds(String[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (String id : ids)
        {
            ErpTransfer doc = requireExisting(id);
            requireAccessible(id);
            // 只有草稿可删：已提交/已审核必须走驳回/反审核
            ErpDocStateMachine.checkEditable(doc.getStatus(), DOC_TYPE);
        }
        return transferMapper.deleteTransferByIds(ids);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer submitTransfer(String id)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        List<ErpTransferItem> items = loadItems(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, oldStatus, DOC_TYPE,
                items != null && !items.isEmpty(), null);
        // 提交前再校验一次两仓：草稿期被改脏的数据不允许进入待审核
        ErpStockOpsRules.checkTransferWarehouses(doc.getFromWarehouseId(), doc.getToWarehouseId());
        doc.setStatus(target);
        doc.setSubmittedBy(currentUserId());
        doc.setSubmittedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        transferMapper.updateTransfer(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "提交单据",
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer approveTransfer(String id)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        // 幂等短路：已过账的重复审核不产生流水、不改结存，也不报错
        if (doc.isPostedFlag())
        {
            return doc;
        }
        String oldStatus = doc.getStatus();
        // 状态下限校验（只有待审核可审核）—— 先校验、后过账，避免"不该过账的状态过了账"
        ErpDocStateMachine.check(ErpDocAction.APPROVE, oldStatus, DOC_TYPE, true, null);
        // 阶段一：单据级校验（两仓非空且不同；行项数量 > 0）
        ErpStockOpsRules.checkTransferWarehouses(doc.getFromWarehouseId(), doc.getToWarehouseId());
        List<ErpTransferItem> items = loadItems(id);
        if (items.isEmpty())
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NO_ITEMS);
        }
        List<ErpPostingLine> lines = buildPostingLines(doc, items);
        // 阶段二：一次性交引擎（引擎内部再按（物料, 仓库）排序加锁校验可用量）
        journalService.post(new ErpPostingRequest()
                .setDocType(DOC_TYPE)
                .setDocId(doc.getId())
                .setDocNo(doc.getDocNo())
                .setSrcDocNo(doc.getSourceDocNo())
                .setBizType(ErpStockOpsRules.BIZ_TRANSFER)
                .setDeptId(doc.getDeptId())
                .setOperatorId(currentUserId())
                .setOperatorName(currentUsername())
                .setOptions(ErpPostingOptions.defaults())
                .setRemark("调拨审核过账")
                .setLines(lines));
        return markApproved(doc, oldStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer rejectTransfer(String id, String reason)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.REJECT, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        transferMapper.updateTransfer(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "驳回：" + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer voidTransfer(String id, String reason)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.VOID, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidedBy(currentUserId());
        doc.setVoidedAt(now());
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        transferMapper.updateTransfer(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "作废：" + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpTransfer unapproveTransfer(String id, String reason)
    {
        ErpTransfer doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String oldPosted = doc.getPosted();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, oldStatus, DOC_TYPE, false, reason);
        boolean posted = doc.isPostedFlag();
        if (posted)
        {
            // 红冲不受负库存校验拦截；两条原流水（调出/调入）各自追加相反数
            journalService.reverse(DOC_TYPE, id, reason, currentUserId(), currentUsername());
        }
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(null);
        doc.setApprovedAt(null);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        transferMapper.updateTransfer(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "反审核：" + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        if (posted)
        {
            changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_POSTED,
                    ErpStockOpsRules.flagOr(oldPosted, ErpDocHeader.POSTED_YES), ErpDocHeader.POSTED_NO,
                    "反审核红冲：" + reason, ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        }
        return doc;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 新建单据的共用实现。
     *
     * @param doc    单据
     * @param status 初始状态
     * @param source 留痕来源（manual / auto）
     * @param note   留痕备注
     * @return 落库后的单据
     */
    private ErpTransfer insert(ErpTransfer doc, String status, String source, String note)
    {
        if (doc == null)
        {
            throw new ServiceException("单据不能为空");
        }
        doc.setId(IdUtils.fastSimpleUUID());
        if (doc.getDocDate() == null)
        {
            doc.setDocDate(DateUtils.getNowDate());
        }
        if (ErpStockOpsRules.isBlank(doc.getDocNo()))
        {
            doc.setDocNo(nextDocNo(doc.getDocDate()));
        }
        // 两仓校验 + 名称快照（先于任何落库动作）
        applyWarehouseSnapshots(doc);
        // 创建人/归属部门快照（服务端上下文；取不到直接报错，见 ErpDocScope）
        ErpDocScope.applyCreateSnapshot(doc, currentUserId(), currentDeptId(), currentUsername());
        doc.setStatus(status);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        List<ErpTransferItem> items = normalizeItems(doc);
        transferMapper.insertTransfer(doc);
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_STATUS, null, status, note, source,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 两仓校验 + 仓库名快照（保存与编辑共用）。
     *
     * @param doc 单据
     * @throws ServiceException 未选 / 同仓 / 仓库不存在或已停用
     */
    private void applyWarehouseSnapshots(ErpTransfer doc)
    {
        ErpStockOpsRules.checkTransferWarehouses(doc.getFromWarehouseId(), doc.getToWarehouseId());
        ErpMasterGuards.MasterRecord from =
                ErpMasterGuards.requireEnabledWarehouse(masterLookup, doc.getFromWarehouseId(), "调出仓：");
        ErpMasterGuards.MasterRecord to =
                ErpMasterGuards.requireEnabledWarehouse(masterLookup, doc.getToWarehouseId(), "调入仓：");
        doc.setFromWarehouseId(from.getId());
        doc.setFromWarehouseName(from.getName());
        doc.setToWarehouseId(to.getId());
        doc.setToWarehouseName(to.getName());
    }

    /**
     * 行项归一：生成 id、重排序号、写物料/单位快照、清空行级仓库、单价与金额归 0。
     *
     * @param doc 单据（写 items）
     * @return 归一后的行项
     */
    private List<ErpTransferItem> normalizeItems(ErpTransfer doc)
    {
        List<ErpTransferItem> items = doc.getItems();
        if (items == null || items.isEmpty())
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NO_ITEMS);
        }
        int rowNo = 0;
        for (ErpTransferItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException(ErpStockOpsRules.rowPrefix(rowNo) + "行项不能为空");
            }
            item.setId(IdUtils.fastSimpleUUID());
            item.setDocId(doc.getId());
            item.setSeq(rowNo);
            // 调拨不产生金额：单价/金额恒 0（快照守卫会按 0 单价重算行金额）
            item.setUnitPrice(ZERO_PRICE);
            // 行项不使用行级仓库（tasks.md §6.1）：请求里塞了也清掉
            item.setWarehouseId(null);
            item.setWarehouseName(null);
            ErpStockOpsRules.checkQtyPositive(item.getQty(), rowNo);
            // 物料/单位快照 + 数量精度 + 行金额（base 公共层唯一实现点）
            ErpMasterGuards.applyItemSnapshot(masterLookup, item, rowNo);
            item.setCreateId(currentUserId());
            item.setCreateBy(currentUsername());
        }
        return items;
    }

    /**
     * 行项落库（全量替换的第二步）。
     *
     * @param items 行项
     */
    private void persistItems(List<ErpTransferItem> items)
    {
        if (items != null && !items.isEmpty())
        {
            transferItemMapper.batchInsertItems(items);
        }
    }

    /**
     * 把行项展开成过账行指令：<b>每行两条</b>（调出仓负数 + 调入仓正数，同单据号、单价 0）。
     *
     * @param doc   单据（读两仓）
     * @param items 行项
     * @return 行指令（条数 = 行数 × 2）
     * @throws ServiceException 某行数量 ≤ 0
     */
    private List<ErpPostingLine> buildPostingLines(ErpTransfer doc, List<ErpTransferItem> items)
    {
        List<ErpPostingLine> lines = new ArrayList<>(items.size() * 2);
        int rowNo = 0;
        for (ErpTransferItem item : items)
        {
            rowNo++;
            ErpStockOpsRules.checkQtyPositive(item.getQty(), rowNo);
            // 调出仓：负数量（走引擎的负库存校验 —— 调出仓可用量不足时整单不写入）
            ErpPostingLine outLine = ErpPostingLine.of(rowNo, item.getProductId(), doc.getFromWarehouseId(),
                    item.getQty().negate(), ZERO_PRICE);
            outLine.setProductName(item.getProductName());
            outLine.setWarehouseName(doc.getFromWarehouseName());
            outLine.setBizType(ErpStockOpsRules.BIZ_TRANSFER_OUT);
            outLine.setSrcItemId(item.getId());
            outLine.setRemark(item.getRemark());
            lines.add(outLine);
            // 调入仓：正数量（同一单据号、同一行号）
            ErpPostingLine inLine = ErpPostingLine.of(rowNo, item.getProductId(), doc.getToWarehouseId(),
                    item.getQty(), ZERO_PRICE);
            inLine.setProductName(item.getProductName());
            inLine.setWarehouseName(doc.getToWarehouseName());
            inLine.setBizType(ErpStockOpsRules.BIZ_TRANSFER_IN);
            inLine.setSrcItemId(item.getId());
            inLine.setRemark(item.getRemark());
            lines.add(inLine);
        }
        return lines;
    }

    /**
     * 写状态与过账标记（审核路径；先过账成功再调用）。
     *
     * @param doc        单据
     * @param fromStatus 来源状态（留痕旧值）
     * @return 落库后的单据
     */
    private ErpTransfer markApproved(ErpTransfer doc, String fromStatus)
    {
        String oldStatus = ErpStockOpsRules.isBlank(doc.getStatus()) ? fromStatus : doc.getStatus();
        doc.setStatus(ErpDocStatus.APPROVED);
        doc.setPosted(ErpDocHeader.POSTED_YES);
        doc.setApprovedBy(currentUserId());
        doc.setApprovedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        transferMapper.updateTransfer(doc);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus,
                ErpDocStatus.APPROVED, "审核即调拨过账（" + doc.getFromWarehouseName() + " → "
                        + doc.getToWarehouseName() + "）", ErpStockOpsRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                ErpDocHeader.POSTED_YES, "调拨两仓各写一条流水", ErpStockOpsRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 取单据（不存在直接报错）。
     *
     * @param id 单据ID
     * @return 单据
     */
    private ErpTransfer requireExisting(String id)
    {
        String docId = ErpStockOpsRules.trimToNull(id);
        if (docId == null)
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NOT_FOUND);
        }
        ErpTransfer doc = transferMapper.selectTransferById(docId);
        if (doc == null || !ErpDocHeader.DEL_FLAG_NORMAL.equals(doc.getDelFlag()))
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NOT_FOUND);
        }
        return doc;
    }

    /**
     * 数据范围校验（base 包唯一实现点；范围外业务码 403）。
     *
     * @param id 单据ID
     */
    private void requireAccessible(String id)
    {
        docObjectAccess.checkObjectAccess(DOC_TYPE.getCode(), id);
    }

    /**
     * 取行项（不存在时返回空集合）。
     *
     * @param id 单据ID
     * @return 行项集合
     */
    private List<ErpTransferItem> loadItems(String id)
    {
        List<ErpTransferItem> items = transferItemMapper.selectItemsByDocId(id);
        return items == null ? new ArrayList<ErpTransferItem>() : items;
    }

    /**
     * 变更历史写入器（无状态；每次现取，便于单测直接注入 Mapper）。
     *
     * @return 写入器
     */
    private ErpStockChangeLogWriter changeLog()
    {
        return new ErpStockChangeLogWriter(changeLogMapper);
    }

    /**
     * 取下一个单号（平台编号服务；前缀 DB）。
     *
     * @param docDate 单据日期（决定年月号段）
     * @return 单号
     */
    protected String nextDocNo(Date docDate)
    {
        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为调拨单取号");
        }
        return docNoGenerator.nextDocNo(DOC_TYPE, docDate);
    }

    /**
     * 当前用户ID（脱库单测可覆盖）。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        String userId = ErpDocScope.currentUserId();
        return userId == null ? ErpStockOpsRules.DEFAULT_USER_ID : userId;
    }

    /**
     * 当前用户部门ID（脱库单测可覆盖）。
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        String deptId = ErpDocScope.currentDeptId();
        return deptId == null ? ErpStockOpsRules.DEFAULT_DEPT_ID : deptId;
    }

    /**
     * 当前登录名（脱库单测可覆盖）。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        return ErpDocScope.currentUsername();
    }

    /**
     * 当前时间（脱库单测可覆盖）。
     *
     * @return 时间
     */
    protected Date now()
    {
        return new Date();
    }
}
