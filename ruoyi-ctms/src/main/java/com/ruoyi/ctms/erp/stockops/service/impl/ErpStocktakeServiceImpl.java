package com.ruoyi.ctms.erp.stockops.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
import com.ruoyi.ctms.erp.posting.domain.ErpStock;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockInMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper;
import com.ruoyi.ctms.erp.posting.service.IErpStockInService;
import com.ruoyi.ctms.erp.posting.service.IErpStockOutService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpStockChangeLogWriter;
import com.ruoyi.ctms.erp.stockops.ErpStockOpsRules;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStockOpsMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStocktakeItemMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStocktakeMapper;
import com.ruoyi.ctms.erp.stockops.service.IErpStocktakeService;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>盘点单服务实现</b>（2.0 B4 任务 6.3/6.4/6.5）。 </p>
 *
 * <p> <b>1. 行项生成</b>（tasks.md §6.3）： </p>
 * <ul>
 *   <li> 全盘（{@code full}）：取该仓库结存<b>非零</b>的物料（结存表是唯一依据）； </li>
 *   <li> 抽盘（{@code partial}）：按请求里的物料（{@code items}）或商品类型
 *        （{@code productTypeIds}，递归含<b>整棵子树</b>）；按类型时只取该仓库<b>有结存行</b>的启用物料
 *        （没有结存行就没有"账面"可比对）； </li>
 *   <li> <b>账面数量在这一步固化</b>（{@code book_qty}）：此后结存再怎么变，本次盘点的账面都不变；
 *        实盘数量默认等于账面（避免"还没录入就被算成全亏"），差异初始为 0。 </li>
 * </ul>
 *
 * <p> <b>2. 审核即生成调整单并过账</b>（tasks.md §6.4）：正差异行合并成一张
 * {@code 盘盈入库}单、负差异行合并成一张 {@code 盘亏出库}单；生成单由 T3 的
 * {@code insertGeneratedStockIn/Out} 写成"直接已审核、未过账"，随即用
 * {@code postApprovedStockIn/Out} 过账；<b>盘亏单显式传 {@code skipNegativeCheck()}</b>
 * （账实不符本身就是差异证据，被负库存挡住则永远平不了账），没有引入任何全局开关。
 * 生成单号回填到 {@code generated_in_no}/{@code generated_out_no}；<b>无差异不生成</b>。 </p>
 *
 * <p> <b>3. 反审核级联</b>（tasks.md §6.5 / Q-B10）：对每张生成单，先红冲（幂等）→ 已审核则
 * 反审核回待审核 → 作废；生成单已作废时直接跳过（幂等）。随后盘点单回待审核、过账标记清 0。
 * 因为生成单的红冲是"追加相反数流水"，级联完成后相关（物料, 仓库）结存必然回到盘点审核前。 </p>
 *
 * <p> <b>4. 重复反审核不报错</b>：盘点反审核是多单据级联，中途失败需要能安全重放；
 * 因此当盘点单已经是"待审核"时，本方法只重跑一遍幂等清理（不动状态、不报错），
 * 而不是像单张单据那样报"当前状态不可执行反审核"。其余状态的流转仍严格走状态机。 </p>
 *
 * @author 二开
 */
@Service
public class ErpStocktakeServiceImpl implements IErpStocktakeService
{
    /** 本服务负责的单据类型。 */
    private static final ErpDocType DOC_TYPE = ErpDocType.STOCK_TAKE;

    /** 盘点不使用单价/金额（列保留，恒 0）。 */
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.0000");

    /** 盘点单表头 Mapper。 */
    @Autowired
    private ErpStocktakeMapper stocktakeMapper;

    /** 盘点单行项 Mapper。 */
    @Autowired
    private ErpStocktakeItemMapper stocktakeItemMapper;

    /** 变更历史 Mapper（B3 交付的 t_ctms_change_log）。 */
    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /** 结存只读查询（写入结存的唯一路径仍是过账引擎）。 */
    @Autowired
    private ErpStockMapper stockMapper;

    /** 只读辅助查询（商品类型子树 / 按类型取启用物料）。 */
    @Autowired
    private ErpStockOpsMapper stockOpsMapper;

    /** 入库单服务（生成盘盈单 + 过账 + 级联红冲）。 */
    @Autowired
    private IErpStockInService stockInService;

    /** 出库单服务（生成盘亏单 + 过账 + 级联红冲）。 */
    @Autowired
    private IErpStockOutService stockOutService;

    /** 入库单表头只读（级联时判断生成单是否已作废；避免走详情接口触发数据范围异常）。 */
    @Autowired
    private ErpStockInMapper stockInMapper;

    /** 出库单表头只读（同上）。 */
    @Autowired
    private ErpStockOutMapper stockOutMapper;

    /** 单据对象访问校验（存在性 + 四档数据范围）。 */
    @Autowired
    private IErpDocObjectAccess docObjectAccess;

    /** 主数据守卫的回调查找；可空以便脱库单测。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 平台编号服务（base 包；可空时取号会明确报错）。 */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpStocktake> selectStocktakeList(ErpStocktake query)
    {
        ErpStocktake effective = query == null ? new ErpStocktake() : query;
        effective.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpStocktake> list = stocktakeMapper.selectStocktakeList(effective);
        return list == null ? new ArrayList<ErpStocktake>() : list;
    }

    @Override
    public ErpStocktake selectStocktakeDetail(String id)
    {
        ErpStocktake doc = requireExisting(id);
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

    /* ==================== 编辑与行项生成 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake insertStocktake(ErpStocktake doc)
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
        // 盘点仓库校验 + 名称快照（两仓/无仓都在这里被拒）
        applyWarehouseSnapshot(doc);
        doc.setTakeType(ErpStockOpsRules.normalizeTakeType(doc.getTakeType()));
        ErpDocScope.applyCreateSnapshot(doc, currentUserId(), currentDeptId(), currentUsername());
        doc.setStatus(ErpDocStatus.DRAFT);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        List<ErpStocktakeItem> items = generateItemsFor(doc);
        doc.setItems(items);
        stocktakeMapper.insertStocktake(doc);
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_STATUS, null, ErpDocStatus.DRAFT,
                "新建盘点单", ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_ITEMS, null,
                "生成行项：共 " + items.size() + " 行（" + describeRange(doc) + "）", "生成盘点行项",
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake updateStocktake(ErpStocktake doc)
    {
        if (doc == null || ErpStockOpsRules.isBlank(doc.getId()))
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NOT_FOUND);
        }
        ErpStocktake exist = requireExisting(doc.getId());
        requireAccessible(doc.getId());
        ErpDocStateMachine.checkEditable(exist.getStatus(), DOC_TYPE);
        // 盘点仓库不可改（账面按生成时结存固化；改仓库会让固化的账面失去意义）
        if (!ErpStockOpsRules.isBlank(doc.getWarehouseId())
                && !doc.getWarehouseId().trim().equals(exist.getWarehouseId()))
        {
            throw new ServiceException(ErpStockOpsRules.MSG_TAKE_WAREHOUSE_IMMUTABLE);
        }
        // 不可改字段一律以库内为准
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
        doc.setGeneratedInId(exist.getGeneratedInId());
        doc.setGeneratedInNo(exist.getGeneratedInNo());
        doc.setGeneratedOutId(exist.getGeneratedOutId());
        doc.setGeneratedOutNo(exist.getGeneratedOutNo());
        doc.setWarehouseId(exist.getWarehouseId());
        doc.setWarehouseName(exist.getWarehouseName());
        if (doc.getDocDate() == null)
        {
            doc.setDocDate(exist.getDocDate());
        }
        doc.setTakeType(ErpStockOpsRules.isBlank(doc.getTakeType())
                ? exist.getTakeType() : ErpStockOpsRules.normalizeTakeType(doc.getTakeType()));
        if (doc.getScopeNote() == null)
        {
            doc.setScopeNote(exist.getScopeNote());
        }
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());

        List<ErpStocktakeItem> stored = loadItems(doc.getId());
        if (stored.isEmpty())
        {
            throw new ServiceException("盘点单还没有行项：请先按 id 生成行项（/generate）或重建单据");
        }
        List<ErpStocktakeItem> merged = mergeCountItems(doc, stored);
        doc.setItems(merged);
        stocktakeMapper.updateStocktake(doc);
        stocktakeItemMapper.deleteItemsByDocId(doc.getId());
        persistItems(merged);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockOpsRules.LOG_FIELD_ITEMS, null,
                "录入实盘：共 " + merged.size() + " 行，差异合计 " + diffSumText(merged),
                "编辑盘点单", ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake generateItems(String id, ErpStocktake criteria)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        ErpDocStateMachine.checkEditable(doc.getStatus(), DOC_TYPE);
        if (criteria != null)
        {
            if (!ErpStockOpsRules.isBlank(criteria.getTakeType()))
            {
                doc.setTakeType(ErpStockOpsRules.normalizeTakeType(criteria.getTakeType()));
            }
            if (criteria.getProductTypeIds() != null && !criteria.getProductTypeIds().isEmpty())
            {
                doc.setProductTypeIds(criteria.getProductTypeIds());
            }
            if (criteria.getItems() != null && !criteria.getItems().isEmpty())
            {
                doc.setItems(criteria.getItems());
            }
            if (criteria.getScopeNote() != null)
            {
                doc.setScopeNote(criteria.getScopeNote());
            }
        }
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        List<ErpStocktakeItem> items = generateItemsFor(doc);
        stocktakeMapper.updateStocktake(doc);
        stocktakeItemMapper.deleteItemsByDocId(id);
        persistItems(items);
        doc.setItems(items);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_ITEMS, null,
                "重新生成行项：共 " + items.size() + " 行（" + describeRange(doc) + "）", "生成盘点行项",
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake saveCount(String id, List<ErpStocktakeItem> items)
    {
        ErpStocktake doc = new ErpStocktake();
        doc.setId(id);
        doc.setItems(items);
        return updateStocktake(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteStocktakeByIds(String[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (String id : ids)
        {
            ErpStocktake doc = requireExisting(id);
            requireAccessible(id);
            ErpDocStateMachine.checkEditable(doc.getStatus(), DOC_TYPE);
        }
        return stocktakeMapper.deleteStocktakeByIds(ids);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake submitStocktake(String id)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        List<ErpStocktakeItem> items = loadItems(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, oldStatus, DOC_TYPE,
                items != null && !items.isEmpty(), null);
        doc.setStatus(target);
        doc.setSubmittedBy(currentUserId());
        doc.setSubmittedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stocktakeMapper.updateStocktake(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "提交单据",
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake approveStocktake(String id)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        // 幂等短路：已审核且生成单已过账时的重复审核不重复生成、不重复过账
        if (doc.isPostedFlag())
        {
            return doc;
        }
        String oldStatus = doc.getStatus();
        ErpDocStateMachine.check(ErpDocAction.APPROVE, oldStatus, DOC_TYPE, true, null);
        List<ErpStocktakeItem> items = loadItems(id);
        if (items.isEmpty())
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NO_ITEMS);
        }
        // 本次审核的生成单号一律重算（反审核保留的旧号只是历史线索）
        doc.setGeneratedInId(null);
        doc.setGeneratedInNo(null);
        doc.setGeneratedOutId(null);
        doc.setGeneratedOutNo(null);

        List<ErpStocktakeItem> gainItems = filterByDiff(items, true);
        List<ErpStocktakeItem> lossItems = filterByDiff(items, false);

        // ① 盘盈：正差异行合并生成一张"已审核未过账"的盘盈入库单，随即过账
        if (!gainItems.isEmpty())
        {
            ErpStockIn generatedIn = stockInService.insertGeneratedStockIn(buildGeneratedIn(doc, gainItems));
            stockInService.postApprovedStockIn(generatedIn.getId(), ErpPostingOptions.defaults());
            doc.setGeneratedInId(generatedIn.getId());
            doc.setGeneratedInNo(generatedIn.getDocNo());
        }
        // ② 盘亏：负差异行合并生成盘亏出库单，过账时**显式豁免负库存校验**
        if (!lossItems.isEmpty())
        {
            ErpStockOut generatedOut = stockOutService.insertGeneratedStockOut(buildGeneratedOut(doc, lossItems));
            stockOutService.postApprovedStockOut(generatedOut.getId(), ErpPostingOptions.skipNegativeCheck());
            doc.setGeneratedOutId(generatedOut.getId());
            doc.setGeneratedOutNo(generatedOut.getDocNo());
        }
        boolean posted = !gainItems.isEmpty() || !lossItems.isEmpty();
        doc.setStatus(ErpDocStatus.APPROVED);
        // 盘点单自身不写结存：posted 表示"其生成单已过账"；无差异时保持未过账
        doc.setPosted(posted ? ErpDocHeader.POSTED_YES : ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(currentUserId());
        doc.setApprovedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stocktakeMapper.updateStocktake(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, ErpDocStatus.APPROVED,
                "审核：盘盈 " + gainItems.size() + " 行 / 盘亏 " + lossItems.size() + " 行",
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        if (posted)
        {
            changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                    ErpDocHeader.POSTED_YES, "生成调整单并过账", ErpStockOpsRules.LOG_SOURCE_MANUAL,
                    currentUserId(), currentUsername());
        }
        if (doc.getGeneratedInId() != null)
        {
            changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_GENERATED_IN_NO, null,
                    doc.getGeneratedInNo(), "盘点审核自动生成盘盈入库单", ErpStockOpsRules.LOG_SOURCE_AUTO,
                    currentUserId(), currentUsername());
        }
        if (doc.getGeneratedOutId() != null)
        {
            changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_GENERATED_OUT_NO, null,
                    doc.getGeneratedOutNo(), "盘点审核自动生成盘亏出库单（豁免负库存校验）",
                    ErpStockOpsRules.LOG_SOURCE_AUTO, currentUserId(), currentUsername());
        }
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake rejectStocktake(String id, String reason)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.REJECT, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stocktakeMapper.updateStocktake(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "驳回：" + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake voidStocktake(String id, String reason)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.VOID, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidedBy(currentUserId());
        doc.setVoidedAt(now());
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stocktakeMapper.updateStocktake(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target, "作废：" + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStocktake unapproveStocktake(String id, String reason)
    {
        ErpStocktake doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String oldPosted = doc.getPosted();
        // 幂等重放：已经是"待审核"说明级联已跑过一次（可能中途失败重试），只重跑清理、不报错
        boolean replay = ErpDocStatus.SUBMITTED.equals(oldStatus);
        String target;
        if (replay)
        {
            if (ErpStockOpsRules.isBlank(reason))
            {
                throw new ServiceException(ErpDocAction.UNAPPROVE.reasonRequiredMessage());
            }
            target = ErpDocStatus.SUBMITTED;
        }
        else
        {
            target = ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, oldStatus, DOC_TYPE, false, reason);
        }
        // 级联：先红冲并作废生成单（生成单已作废时幂等跳过）
        cascadeVoidGeneratedIn(doc.getGeneratedInId(), reason);
        cascadeVoidGeneratedOut(doc.getGeneratedOutId(), reason);
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(null);
        doc.setApprovedAt(null);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stocktakeMapper.updateStocktake(doc);
        changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_STATUS, oldStatus, target,
                (replay ? "反审核重放（幂等）：" : "反审核级联作废生成单：") + reason,
                ErpStockOpsRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        if (ErpDocHeader.POSTED_YES.equals(ErpStockOpsRules.flagOr(oldPosted, null)))
        {
            changeLog().write(DOC_TYPE, id, ErpStockOpsRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_YES,
                    ErpDocHeader.POSTED_NO, "反审核红冲生成单：" + reason, ErpStockOpsRules.LOG_SOURCE_MANUAL,
                    currentUserId(), currentUsername());
        }
        return doc;
    }

    /* ==================== 级联 ==================== */

    /**
     * 级联作废生成的盘盈入库单：红冲 → 反审核 → 作废；已作废时跳过（幂等）。
     *
     * @param generatedInId 生成单ID（可空）
     * @param reason        反审核原因
     */
    private void cascadeVoidGeneratedIn(String generatedInId, String reason)
    {
        if (ErpStockOpsRules.isBlank(generatedInId))
        {
            return;
        }
        ErpStockIn current = stockInMapper.selectStockInById(generatedInId);
        if (current == null || ErpDocStatus.VOIDED.equals(current.getStatus()))
        {
            // 幂等跳过：生成单已不存在或已作废
            return;
        }
        if (current.isPostedFlag())
        {
            // 红冲 = 追加相反数流水；幂等（没有可冲销的流水时返回空结果）
            stockInService.reversePostedStockIn(generatedInId, reason);
        }
        ErpStockIn afterReverse = stockInMapper.selectStockInById(generatedInId);
        if (afterReverse != null && ErpDocStatus.APPROVED.equals(afterReverse.getStatus()))
        {
            // 生成单是"直接已审核"的：先反审核回待审核，才能作废（状态机白名单）
            stockInService.unapproveStockIn(generatedInId, reason);
        }
        stockInService.voidStockIn(generatedInId, reason);
    }

    /**
     * 级联作废生成的盘亏出库单（同入库侧）。
     *
     * @param generatedOutId 生成单ID（可空）
     * @param reason         反审核原因
     */
    private void cascadeVoidGeneratedOut(String generatedOutId, String reason)
    {
        if (ErpStockOpsRules.isBlank(generatedOutId))
        {
            return;
        }
        ErpStockOut current = stockOutMapper.selectStockOutById(generatedOutId);
        if (current == null || ErpDocStatus.VOIDED.equals(current.getStatus()))
        {
            return;
        }
        if (current.isPostedFlag())
        {
            stockOutService.reversePostedStockOut(generatedOutId, reason);
        }
        ErpStockOut afterReverse = stockOutMapper.selectStockOutById(generatedOutId);
        if (afterReverse != null && ErpDocStatus.APPROVED.equals(afterReverse.getStatus()))
        {
            stockOutService.unapproveStockOut(generatedOutId, reason);
        }
        stockOutService.voidStockOut(generatedOutId, reason);
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 盘点仓库校验 + 名称快照。
     *
     * @param doc 单据
     */
    private void applyWarehouseSnapshot(ErpStocktake doc)
    {
        if (ErpStockOpsRules.isBlank(doc.getWarehouseId()))
        {
            throw new ServiceException(ErpStockOpsRules.MSG_TAKE_WAREHOUSE_REQUIRED);
        }
        ErpMasterGuards.MasterRecord warehouse =
                ErpMasterGuards.requireEnabledWarehouse(masterLookup, doc.getWarehouseId());
        doc.setWarehouseId(warehouse.getId());
        doc.setWarehouseName(warehouse.getName());
    }

    /**
     * <p> 按单据口径生成行项（全盘 / 抽盘）。 </p>
     *
     * @param doc 单据（读仓库、盘点范围、{@code productTypeIds} 与请求行项）
     * @return 生成的行项（已固账面、默认实盘=账面、差异 0）
     * @throws ServiceException 范围内没有可盘点物料 / 抽盘未指定范围 / 物料或单位非法
     */
    private List<ErpStocktakeItem> generateItemsFor(ErpStocktake doc)
    {
        String warehouseId = doc.getWarehouseId();
        String takeType = ErpStockOpsRules.normalizeTakeType(doc.getTakeType());
        doc.setTakeType(takeType);
        Map<String, BigDecimal> stockOfWarehouse = stockOf(warehouseId);
        List<String> productIds = new ArrayList<>();
        if (ErpStockOpsRules.TAKE_TYPE_FULL.equals(takeType))
        {
            // 全盘：该仓库结存非零的物料（按物料编码顺序稳定输出）
            List<ErpStock> rows = stockRows(warehouseId);
            for (ErpStock row : rows)
            {
                if (row.qtyOrZero().signum() != 0)
                {
                    productIds.add(row.getProductId());
                }
            }
        }
        else
        {
            List<ErpStocktakeItem> requested = doc.getItems();
            if (requested != null && !requested.isEmpty())
            {
                // 抽盘按物料：请求里指定的物料（去重、保序）
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                for (ErpStocktakeItem item : requested)
                {
                    if (item != null && !ErpStockOpsRules.isBlank(item.getProductId()))
                    {
                        unique.add(item.getProductId().trim());
                    }
                }
                productIds.addAll(unique);
            }
            else if (doc.getProductTypeIds() != null && !doc.getProductTypeIds().isEmpty())
            {
                // 抽盘按商品类型：递归展开整棵子树，再取该仓库有结存行的启用物料
                LinkedHashSet<String> typeIds = new LinkedHashSet<>();
                for (String typeId : doc.getProductTypeIds())
                {
                    if (ErpStockOpsRules.isBlank(typeId))
                    {
                        continue;
                    }
                    List<String> subtree = stockOpsMapper.selectTypeSubtreeIds(typeId.trim());
                    if (subtree != null)
                    {
                        typeIds.addAll(subtree);
                    }
                    else
                    {
                        typeIds.add(typeId.trim());
                    }
                }
                if (!typeIds.isEmpty())
                {
                    List<String> productsOfTypes =
                            stockOpsMapper.selectEnabledProductIdsByTypeIds(new ArrayList<>(typeIds));
                    if (productsOfTypes != null)
                    {
                        for (String productId : productsOfTypes)
                        {
                            if (stockOfWarehouse.containsKey(productId))
                            {
                                productIds.add(productId);
                            }
                        }
                    }
                }
            }
            else
            {
                throw new ServiceException(ErpStockOpsRules.MSG_TAKE_PARTIAL_SCOPE);
            }
        }
        if (productIds.isEmpty())
        {
            throw new ServiceException("盘点范围内没有可盘点的物料（该仓库结存为空或指定范围内没有结存行）");
        }
        List<ErpStocktakeItem> items = new ArrayList<>(productIds.size());
        int rowNo = 0;
        for (String productId : productIds)
        {
            rowNo++;
            ErpStocktakeItem item = new ErpStocktakeItem();
            item.setId(IdUtils.fastSimpleUUID());
            item.setDocId(doc.getId());
            item.setSeq(rowNo);
            item.setProductId(productId);
            // 账面 = 生成时的结存（固化）；实盘默认等于账面（避免"未录入即全亏"）
            BigDecimal bookQty = stockOfWarehouse.containsKey(productId)
                    ? stockOfWarehouse.get(productId) : BigDecimal.ZERO;
            item.setBookQty(bookQty);
            item.setActualQty(bookQty);
            item.setDiffQty(BigDecimal.ZERO);
            // 盘点不使用数量/单价/金额三列
            item.setQty(BigDecimal.ZERO);
            item.setUnitPrice(ZERO_PRICE);
            item.setAmount(BigDecimal.ZERO);
            item.setWarehouseId(null);
            item.setWarehouseName(null);
            item.setCreateId(currentUserId());
            item.setCreateBy(currentUsername());
            items.add(item);
        }
        // 统一走快照守卫（物料/单位存在性 + 快照六列 + 实盘精度），再重算差异
        for (ErpStocktakeItem item : items)
        {
            ErpStockOpsRules.applyStocktakeItemSnapshot(masterLookup, item, item.getSeq());
            item.recalcDiffQty();
        }
        return items;
    }

    /**
     * 实盘录入合并：<b>账面只读</b>（永远取库内固化值），实盘与差异原因取请求，差异服务端现算。
     *
     * @param doc     请求单据（{@code items} 为录入内容）
     * @param stored  库内已生成的行项
     * @return 合并后的行项（顺序与库内一致）
     * @throws ServiceException 请求里出现不在本次盘点范围内的物料 / 实盘非法
     */
    private List<ErpStocktakeItem> mergeCountItems(ErpStocktake doc, List<ErpStocktakeItem> stored)
    {
        Map<String, ErpStocktakeItem> byId = new LinkedHashMap<>();
        Map<String, ErpStocktakeItem> byProduct = new LinkedHashMap<>();
        for (ErpStocktakeItem item : stored)
        {
            byId.put(item.getId(), item);
            byProduct.put(item.getProductId(), item);
        }
        List<ErpStocktakeItem> requested = doc.getItems();
        if (requested == null || requested.isEmpty())
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NO_ITEMS);
        }
        Map<String, ErpStocktakeItem> incoming = new LinkedHashMap<>();
        for (ErpStocktakeItem item : requested)
        {
            if (item == null)
            {
                continue;
            }
            ErpStocktakeItem target = null;
            if (!ErpStockOpsRules.isBlank(item.getId()))
            {
                target = byId.get(item.getId().trim());
            }
            if (target == null && !ErpStockOpsRules.isBlank(item.getProductId()))
            {
                target = byProduct.get(item.getProductId().trim());
            }
            if (target == null)
            {
                throw new ServiceException(ErpStockOpsRules.MSG_TAKE_ITEM_OUT_OF_SCOPE
                        + ErpStockOpsRules.trimToNull(item.getProductId()));
            }
            incoming.put(target.getId(), item);
        }
        int rowNo = 0;
        for (ErpStocktakeItem row : stored)
        {
            rowNo++;
            ErpStocktakeItem request = incoming.get(row.getId());
            // 账面固定只读：即使请求带了 bookQty 也一律以库内为准
            row.setBookQty(row.bookQtyOrZero());
            BigDecimal actualQty = request == null || request.getActualQty() == null
                    ? row.actualQtyOrZero() : request.getActualQty();
            row.setActualQty(actualQty);
            if (request != null && request.getDiffReason() != null)
            {
                row.setDiffReason(request.getDiffReason());
            }
            row.setSeq(rowNo);
            row.setDocId(doc.getId());
            row.setQty(BigDecimal.ZERO);
            row.setUnitPrice(ZERO_PRICE);
            row.setAmount(BigDecimal.ZERO);
            row.setWarehouseId(null);
            row.setWarehouseName(null);
            row.setUpdateId(currentUserId());
            row.setUpdateBy(currentUsername());
            // 实盘非负 + 精度校验（同时刷新物料/单位快照）
            ErpStockOpsRules.applyStocktakeItemSnapshot(masterLookup, row, rowNo);
            row.recalcDiffQty();
        }
        return stored;
    }

    /**
     * 按差异正负筛行。
     *
     * @param items  行项
     * @param gain   {@code true} 取正差异（盘盈），{@code false} 取负差异（盘亏）
     * @return 命中行项（保持原顺序）
     */
    private List<ErpStocktakeItem> filterByDiff(List<ErpStocktakeItem> items, boolean gain)
    {
        List<ErpStocktakeItem> result = new ArrayList<>();
        for (ErpStocktakeItem item : items)
        {
            item.recalcDiffQty();
            BigDecimal diff = item.diffQtyOrZero();
            if (gain ? diff.signum() > 0 : diff.signum() < 0)
            {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 装配一张"盘盈入库单"（已审核、金额 0、数量为差异绝对值、记录来源盘点单）。
     *
     * @param take      盘点单
     * @param gainItems 正差异行
     * @return 入库单（待 insertGeneratedStockIn 落库）
     */
    private ErpStockIn buildGeneratedIn(ErpStocktake take, List<ErpStocktakeItem> gainItems)
    {
        ErpStockIn doc = new ErpStockIn();
        doc.setWarehouseId(take.getWarehouseId());
        doc.setWarehouseName(take.getWarehouseName());
        doc.setInType(ErpStockOpsRules.BIZ_TAKE_GAIN);
        doc.setDocDate(take.getDocDate() == null ? new Date() : take.getDocDate());
        doc.setSourceDocType(DOC_TYPE.getCode());
        doc.setSourceDocId(take.getId());
        doc.setSourceDocNo(take.getDocNo());
        doc.setHandlerUserId(take.getHandlerUserId());
        doc.setHandlerName(take.getHandlerName());
        doc.setRemark("盘点单 " + take.getDocNo() + " 审核自动生成（盘盈）");
        List<ErpStockInItem> items = new ArrayList<>(gainItems.size());
        for (ErpStocktakeItem row : gainItems)
        {
            ErpStockInItem item = new ErpStockInItem();
            item.setProductId(row.getProductId());
            item.setQty(row.diffQtyOrZero().abs());
            item.setUnitPrice(ZERO_PRICE);
            item.setSrcItemId(row.getId());
            item.setRemark("盘盈（账面 " + ErpStockOpsRules.trimToNull(String.valueOf(row.bookQtyOrZero()))
                    + " → 实盘 " + row.actualQtyOrZero() + "）");
            items.add(item);
        }
        doc.setItems(items);
        return doc;
    }

    /**
     * 装配一张"盘亏出库单"（已审核、金额 0、数量为差异绝对值、记录来源盘点单）。
     *
     * @param take      盘点单
     * @param lossItems 负差异行
     * @return 出库单（待 insertGeneratedStockOut 落库）
     */
    private ErpStockOut buildGeneratedOut(ErpStocktake take, List<ErpStocktakeItem> lossItems)
    {
        ErpStockOut doc = new ErpStockOut();
        doc.setWarehouseId(take.getWarehouseId());
        doc.setWarehouseName(take.getWarehouseName());
        doc.setOutType(ErpStockOpsRules.BIZ_TAKE_LOSS);
        doc.setDocDate(take.getDocDate() == null ? new Date() : take.getDocDate());
        doc.setSourceDocType(DOC_TYPE.getCode());
        doc.setSourceDocId(take.getId());
        doc.setSourceDocNo(take.getDocNo());
        doc.setHandlerUserId(take.getHandlerUserId());
        doc.setHandlerName(take.getHandlerName());
        doc.setRemark("盘点单 " + take.getDocNo() + " 审核自动生成（盘亏；过账豁免负库存校验）");
        List<ErpStockOutItem> items = new ArrayList<>(lossItems.size());
        for (ErpStocktakeItem row : lossItems)
        {
            ErpStockOutItem item = new ErpStockOutItem();
            item.setProductId(row.getProductId());
            item.setQty(row.diffQtyOrZero().abs());
            item.setUnitPrice(ZERO_PRICE);
            item.setSrcItemId(row.getId());
            item.setRemark("盘亏（账面 " + row.bookQtyOrZero() + " → 实盘 " + row.actualQtyOrZero() + "）");
            items.add(item);
        }
        doc.setItems(items);
        return doc;
    }

    /**
     * 该仓库的结存行（只读）。
     *
     * @param warehouseId 仓库ID
     * @return 结存行（非 null）
     */
    private List<ErpStock> stockRows(String warehouseId)
    {
        ErpStock query = new ErpStock();
        query.setWarehouseId(warehouseId);
        List<ErpStock> rows = stockMapper.selectStockList(query);
        return rows == null ? new ArrayList<ErpStock>() : rows;
    }

    /**
     * 该仓库的（物料 → 结存数量）映射（只读）。
     *
     * @param warehouseId 仓库ID
     * @return 映射（含 0 数量行）
     */
    private Map<String, BigDecimal> stockOf(String warehouseId)
    {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (ErpStock row : stockRows(warehouseId))
        {
            if (!ErpStockOpsRules.isBlank(row.getProductId()))
            {
                map.put(row.getProductId(), row.qtyOrZero());
            }
        }
        return map;
    }

    /**
     * 生成行项的来源描述（留痕用）。
     *
     * @param doc 单据
     * @return 形如"全盘" / "抽盘：按商品类型 2 个"
     */
    private String describeRange(ErpStocktake doc)
    {
        if (ErpStockOpsRules.TAKE_TYPE_PARTIAL.equals(doc.getTakeType()))
        {
            int typeCount = doc.getProductTypeIds() == null ? 0 : doc.getProductTypeIds().size();
            return "抽盘：按商品类型 " + typeCount + " 个";
        }
        return "全盘";
    }

    /**
     * 差异合计文案（留痕用）。
     *
     * @param items 行项
     * @return 形如 {@code +2.000 / -3.000}
     */
    private String diffSumText(List<ErpStocktakeItem> items)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpStocktakeItem item : items)
        {
            sum = sum.add(item.diffQtyOrZero());
        }
        return sum.toPlainString();
    }

    /**
     * 行项落库。
     *
     * @param items 行项
     */
    private void persistItems(List<ErpStocktakeItem> items)
    {
        if (items != null && !items.isEmpty())
        {
            stocktakeItemMapper.batchInsertItems(items);
        }
    }

    /**
     * 取单据（不存在直接报错）。
     *
     * @param id 单据ID
     * @return 单据
     */
    private ErpStocktake requireExisting(String id)
    {
        String docId = ErpStockOpsRules.trimToNull(id);
        if (docId == null)
        {
            throw new ServiceException(ErpStockOpsRules.MSG_NOT_FOUND);
        }
        ErpStocktake doc = stocktakeMapper.selectStocktakeById(docId);
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
    private List<ErpStocktakeItem> loadItems(String id)
    {
        List<ErpStocktakeItem> items = stocktakeItemMapper.selectItemsByDocId(id);
        return items == null ? new ArrayList<ErpStocktakeItem>() : items;
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
     * 取下一个单号（平台编号服务；前缀 ST）。
     *
     * @param docDate 单据日期
     * @return 单号
     */
    protected String nextDocNo(Date docDate)
    {
        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为盘点单取号");
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
