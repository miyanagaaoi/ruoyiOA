package com.ruoyi.ctms.erp.posting.service.impl;

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
import com.ruoyi.ctms.erp.posting.ErpStockRules;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutItemMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper;
import com.ruoyi.ctms.erp.posting.service.IErpStockJournalService;
import com.ruoyi.ctms.erp.posting.service.IErpStockOutService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;
import com.ruoyi.ctms.erp.posting.support.ErpStockChangeLogWriter;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>出库单服务实现</b>（2.0 B4 任务 5.1~5.6）。 </p>
 *
 * <p> 与 {@link ErpStockInServiceImpl} 严格对称（快照、状态机、留痕、幂等口径全部相同），
 * 只有三处差异： </p>
 * <ol>
 *   <li> 过账行的数量变动取<b>负数</b>（{@code qty.negate()}）—— 引擎按符号决定方向，
 *        不再由单据类型推断； </li>
 *   <li> 负库存校验由引擎按系统参数执行：默认关闭时"结存 3 出库 5"会被拒并返回
 *        可用量/需求量，且状态与结存都不变（任务 5.3）； </li>
 *   <li> {@code approveStockOut(id, options)} 可显式传
 *        {@code ErpPostingOptions.skipNegativeCheck()}（盘亏单豁免，tasks.md §6.4）。 </li>
 * </ol>
 *
 * @author 二开
 */
@Service
public class ErpStockOutServiceImpl implements IErpStockOutService
{
    /** 本服务负责的单据类型。 */
    private static final ErpDocType DOC_TYPE = ErpDocType.STOCK_OUT;

    /** 出库类型默认值（字典 {@code stock_out_types} 的默认项）。 */
    private static final String DEFAULT_OUT_TYPE = "销售出库";

    /**
     * 表头仓库必填的文案前缀（t33；与入库侧的常量同值，双方文案由测试逐字锁定）。
     *
     * <p> {@code t_ctms_stock_out.warehouse_id} 是 {@code NOT NULL} + FK（冻结 DDL，不改表）⇒
     * 缺仓库在服务层给出可读中文，而不是数据库原生 1048。
     * 完整文案 = {@code 表头仓库不能为空：请选择出库仓库}。 </p>
     */
    private static final String MSG_HEADER_WAREHOUSE_REQUIRED = "表头仓库不能为空";

    /** 单据表头 Mapper。 */
    @Autowired
    private ErpStockOutMapper stockOutMapper;

    /** 行项 Mapper。 */
    @Autowired
    private ErpStockOutItemMapper stockOutItemMapper;

    /** 变更历史 Mapper（B3 交付）。 */
    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /** 过账引擎（结存与流水的唯一写入服务）。 */
    @Autowired
    private IErpStockJournalService journalService;

    /** 单据对象访问校验（存在性 + 四档数据范围）。 */
    @Autowired
    private IErpDocObjectAccess docObjectAccess;

    /** 主数据守卫的回调查找；可空以便脱库单测。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 平台编号服务（前缀 OUT）；可空时取号明确报错。 */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpStockOut> selectStockOutList(ErpStockOut query)
    {
        ErpStockOut effective = query == null ? new ErpStockOut() : query;
        effective.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpStockOut> list = stockOutMapper.selectStockOutList(effective);
        return list == null ? new ArrayList<ErpStockOut>() : list;
    }

    @Override
    public ErpStockOut selectStockOutDetail(String id)
    {
        ErpStockOut doc = requireExisting(id);
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
    public ErpStockOut insertStockOut(ErpStockOut doc)
    {
        return insert(doc, ErpDocStatus.DRAFT, ErpStockRules.LOG_SOURCE_MANUAL, "新建单据", false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut insertGeneratedStockOut(ErpStockOut doc)
    {
        // 生成路径：行项来自该仓库的已有结存（物料可能在建结存之后被停用），
        // 因此显式豁免"停用物料不可用于新单据"这一条面向用户录入的校验，其余校验照旧
        return insert(doc, ErpDocStatus.APPROVED, ErpStockRules.LOG_SOURCE_AUTO,
                "盘点审核自动生成盘亏出库单", true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut updateStockOut(ErpStockOut doc)
    {
        if (doc == null || ErpStockRules.isBlank(doc.getId()))
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        ErpStockOut exist = requireExisting(doc.getId());
        requireAccessible(doc.getId());
        ErpDocStateMachine.checkEditable(exist.getStatus(), DOC_TYPE);
        // t33：编辑同样要求表头仓库（DDL NOT NULL + FK；把 1048 换成可读中文），并回填名称快照
        applyHeaderWarehouse(doc);

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
        if (ErpStockRules.isBlank(doc.getOutType()))
        {
            doc.setOutType(exist.getOutType());
        }
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());

        List<ErpStockOutItem> items = normalizeItems(doc, false);
        BigDecimal total = ErpStockRules.totalAmountOf(items);
        doc.setTotalAmount(total);
        stockOutMapper.updateStockOut(doc);
        stockOutItemMapper.deleteItemsByDocId(doc.getId());
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_ITEMS, null,
                "共 " + items.size() + " 行，合计 " + total, "编辑单据",
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteStockOutByIds(String[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (String id : ids)
        {
            ErpStockOut doc = requireExisting(id);
            requireAccessible(id);
            ErpDocStateMachine.checkEditable(doc.getStatus(), DOC_TYPE);
        }
        return stockOutMapper.deleteStockOutByIds(ids);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut submitStockOut(String id)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        List<ErpStockOutItem> items = loadItems(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, oldStatus, DOC_TYPE,
                items != null && !items.isEmpty(), null);
        doc.setStatus(target);
        doc.setSubmittedBy(currentUserId());
        doc.setSubmittedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "提交单据",
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut approveStockOut(String id, ErpPostingOptions options)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        // 幂等短路：已过账的重复审核不产生流水、不改结存
        if (doc.isPostedFlag())
        {
            return doc;
        }
        List<ErpStockOutItem> items = loadItems(id);
        if (items.isEmpty())
        {
            throw new ServiceException(ErpStockRules.MSG_NO_ITEMS);
        }
        List<ErpPostingLine> lines = buildPostingLines(doc, items);
        ErpPostingRequest request = new ErpPostingRequest()
                .setDocType(DOC_TYPE)
                .setDocId(doc.getId())
                .setDocNo(doc.getDocNo())
                .setSrcDocNo(doc.getSourceDocNo())
                .setBizType(resolveBizType(doc))
                .setDeptId(doc.getDeptId())
                .setOperatorId(currentUserId())
                .setOperatorName(currentUsername())
                .setOptions(options == null ? ErpPostingOptions.defaults() : options)
                .setRemark("审核过账")
                .setLines(lines);
        // ① 先过账：库存不足被拒时状态与结存都不变、无流水（任务 5.3）
        journalService.post(request);
        // ② 再写状态与过账标记
        return markApproved(doc);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut rejectStockOut(String id, String reason)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.REJECT, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "驳回：" + reason,
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut voidStockOut(String id, String reason)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.VOID, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidedBy(currentUserId());
        doc.setVoidedAt(now());
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "作废：" + reason,
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockOut unapproveStockOut(String id, String reason)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String oldPosted = doc.getPosted();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, oldStatus, DOC_TYPE, false, reason);
        boolean posted = doc.isPostedFlag();
        if (posted)
        {
            // 红冲不受负库存校验拦截；允许结存因此转负（spec: 反审核红冲）
            journalService.reverse(DOC_TYPE, id, reason, currentUserId(), currentUsername());
        }
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(null);
        doc.setApprovedAt(null);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "反审核：" + reason,
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        if (posted)
        {
            changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_POSTED,
                    ErpStockRules.flagOr(oldPosted, ErpDocHeader.POSTED_YES), ErpDocHeader.POSTED_NO,
                    "反审核红冲：" + reason, ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        }
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPostingOutcome postApprovedStockOut(String id, ErpPostingOptions options)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        if (doc.isPostedFlag())
        {
            return new ErpPostingOutcome();
        }
        List<ErpStockOutItem> items = loadItems(id);
        if (items.isEmpty())
        {
            throw new ServiceException(ErpStockRules.MSG_NO_ITEMS);
        }
        ErpPostingOutcome outcome = journalService.post(new ErpPostingRequest()
                .setDocType(DOC_TYPE)
                .setDocId(doc.getId())
                .setDocNo(doc.getDocNo())
                .setSrcDocNo(doc.getSourceDocNo())
                .setBizType(resolveBizType(doc))
                .setDeptId(doc.getDeptId())
                .setOperatorId(currentUserId())
                .setOperatorName(currentUsername())
                .setOptions(options == null ? ErpPostingOptions.defaults() : options)
                .setRemark("审核过账")
                .setLines(buildPostingLines(doc, items)));
        markPosted(doc);
        return outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPostingOutcome reversePostedStockOut(String id, String reason)
    {
        ErpStockOut doc = requireExisting(id);
        requireAccessible(id);
        if (!doc.isPostedFlag())
        {
            return new ErpPostingOutcome();
        }
        ErpPostingOutcome outcome = journalService.reverse(DOC_TYPE, id, reason, currentUserId(), currentUsername());
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(null);
        doc.setApprovedAt(null);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_YES,
                ErpDocHeader.POSTED_NO, "反审核红冲：" + reason, ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        return outcome;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * <p> <b>表头仓库：必填校验 + 名称快照回填</b>（t33 / t33-r1，与入库侧同构）。 </p>
     *
     * <p> 出库单的 {@code warehouse_id} 是 {@code NOT NULL} + FK、{@code warehouse_name} 是
     * {@code NOT NULL DEFAULT ''}（MyBatis 显式写 null 时不走默认值）⇒ 缺 id 抛可读中文，
     * 缺名字从仓库档案回填（调用方只给 id 即可创建成功），并在档案不可用时回落空串而不是 null。 </p>
     *
     * @param doc 单据（读/写 {@code warehouseId}、{@code warehouseName}）
     */
    private void applyHeaderWarehouse(ErpStockOut doc)
    {
        if (ErpStockRules.isBlank(doc.getWarehouseId()))
        {
            throw new ServiceException(MSG_HEADER_WAREHOUSE_REQUIRED + "：请选择出库仓库");
        }
        ErpMasterGuards.MasterRecord warehouse = masterLookup == null
                ? null : masterLookup.warehouse(doc.getWarehouseId());
        if (warehouse != null && !ErpStockRules.isBlank(warehouse.getName()))
        {
            doc.setWarehouseName(warehouse.getName());
        }
        else if (ErpStockRules.isBlank(doc.getWarehouseName()))
        {
            doc.setWarehouseName("");
        }
    }

    /**
     * 新建单据的共用实现（草稿与"盘点生成的已审核单"只差初始状态、留痕来源与行项守卫的宽严）。
     *
     * @param doc       单据
     * @param status    初始状态
     * @param source    留痕来源
     * @param note      留痕备注
     * @param generated 是否系统生成路径（{@code true} 时豁免"停用物料不可用"，见
     *                  {@link ErpMasterGuards#applyGeneratedItemSnapshot}）
     * @return 落库后的单据
     */
    private ErpStockOut insert(ErpStockOut doc, String status, String source, String note, boolean generated)
    {
        if (doc == null)
        {
            throw new ServiceException("单据不能为空");
        }
        // t33：表头仓库必填（DDL 为 NOT NULL + FK；在服务层给出可读中文，而不是让用户看到 1048）
        applyHeaderWarehouse(doc);
        doc.setId(IdUtils.fastSimpleUUID());
        if (doc.getDocDate() == null)
        {
            doc.setDocDate(DateUtils.getNowDate());
        }
        if (ErpStockRules.isBlank(doc.getDocNo()))
        {
            doc.setDocNo(nextDocNo(doc.getDocDate()));
        }
        ErpDocScope.applyCreateSnapshot(doc, currentUserId(), currentDeptId(), currentUsername());
        doc.setStatus(status);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        if (ErpStockRules.isBlank(doc.getOutType()))
        {
            doc.setOutType(DEFAULT_OUT_TYPE);
        }
        List<ErpStockOutItem> items = normalizeItems(doc, generated);
        doc.setTotalAmount(ErpStockRules.totalAmountOf(items));
        stockOutMapper.insertStockOut(doc);
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_STATUS, null, status, note, source,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 行项归一（含行级仓库回落表头与快照写入）。
     *
     * <p> <b>守卫宽严由 {@code generated} 决定</b>（t23）：用户新建/编辑一律
     * {@link ErpMasterGuards#applyItemSnapshot}（停用物料被拒）；只有系统生成路径
     * （盘点差异生成的盘亏单）才走 {@link ErpMasterGuards#applyGeneratedItemSnapshot}。 </p>
     *
     * @param doc       单据
     * @param generated 是否系统生成路径（豁免"停用物料不可用"）
     * @return 归一后的行项
     */
    private List<ErpStockOutItem> normalizeItems(ErpStockOut doc, boolean generated)
    {
        List<ErpStockOutItem> items = doc.getItems();
        ErpStockRules.checkItemsBasic(items);
        int rowNo = 0;
        for (ErpStockOutItem item : items)
        {
            rowNo++;
            item.setId(IdUtils.fastSimpleUUID());
            item.setDocId(doc.getId());
            item.setSeq(rowNo);
            if (generated)
            {
                ErpMasterGuards.applyGeneratedItemSnapshot(masterLookup, item, rowNo);
            }
            else
            {
                ErpMasterGuards.applyItemSnapshot(masterLookup, item, rowNo);
            }
            if (ErpStockRules.isBlank(item.getWarehouseId()))
            {
                item.setWarehouseId(ErpStockRules.trimToNull(doc.getWarehouseId()));
                item.setWarehouseName(item.getWarehouseId() == null ? null
                        : ErpStockRules.trimToNull(doc.getWarehouseName()));
            }
            else
            {
                ErpMasterGuards.applyItemWarehouseSnapshot(masterLookup, item, rowNo);
            }
            item.setCreateId(currentUserId());
            item.setCreateBy(currentUsername());
        }
        return items;
    }

    /**
     * 行项落库。
     *
     * @param items 行项
     */
    private void persistItems(List<ErpStockOutItem> items)
    {
        if (items != null && !items.isEmpty())
        {
            stockOutItemMapper.batchInsertItems(items);
        }
    }

    /**
     * 行项 → 过账行指令（数量取负：出库扣减结存）。
     *
     * @param doc   单据
     * @param items 行项
     * @return 过账行指令
     */
    private List<ErpPostingLine> buildPostingLines(ErpStockOut doc, List<ErpStockOutItem> items)
    {
        List<ErpPostingLine> lines = new ArrayList<>();
        int rowNo = 0;
        for (ErpStockOutItem item : items)
        {
            rowNo++;
            String warehouseId = ErpMasterGuards.resolveWarehouseId(item, doc.getWarehouseId());
            if (warehouseId == null)
            {
                throw new ServiceException(ErpStockRules.rowPrefix(rowNo) + ErpStockRules.MSG_WAREHOUSE_REQUIRED);
            }
            ErpPostingLine line = ErpPostingLine.of(rowNo, item.getProductId(), warehouseId,
                    item.getQty().negate(), item.getUnitPrice());
            line.setProductName(item.getProductName());
            line.setWarehouseName(ErpStockRules.isBlank(item.getWarehouseId())
                    ? doc.getWarehouseName() : item.getWarehouseName());
            line.setSrcItemId(item.getSrcItemId());
            line.setRemark(item.getRemark());
            lines.add(line);
        }
        return lines;
    }

    /**
     * 写状态与过账标记（审核路径）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    private ErpStockOut markApproved(ErpStockOut doc)
    {
        String oldStatus = doc.getStatus();
        String target = ErpDocStatus.APPROVED;
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_YES);
        doc.setApprovedBy(currentUserId());
        doc.setApprovedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_STATUS, oldStatus, target,
                "审核即过账（" + resolveBizType(doc) + "）", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                ErpDocHeader.POSTED_YES, "审核过账", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 只写"已过账"标记（生成单路径）。
     *
     * @param doc 单据
     */
    private void markPosted(ErpStockOut doc)
    {
        doc.setPosted(ErpDocHeader.POSTED_YES);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockOutMapper.updateStockOut(doc);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                ErpDocHeader.POSTED_YES, "审核过账", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
    }

    /**
     * 业务类型取出库类型。
     *
     * @param doc 单据
     * @return 出库类型
     */
    private String resolveBizType(ErpStockOut doc)
    {
        return ErpStockRules.isBlank(doc.getOutType()) ? DEFAULT_OUT_TYPE : doc.getOutType().trim();
    }

    /**
     * 取单据（不存在直接报错）。
     *
     * @param id 单据ID
     * @return 单据
     */
    private ErpStockOut requireExisting(String id)
    {
        String docId = ErpStockRules.trimToNull(id);
        if (docId == null)
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        ErpStockOut doc = stockOutMapper.selectStockOutById(docId);
        if (doc == null || !ErpDocHeader.DEL_FLAG_NORMAL.equals(doc.getDelFlag()))
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        return doc;
    }

    /**
     * 数据范围校验（base 包唯一实现点）。
     *
     * @param id 单据ID
     */
    private void requireAccessible(String id)
    {
        docObjectAccess.checkObjectAccess(DOC_TYPE.getCode(), id);
    }

    /**
     * 取行项。
     *
     * @param id 单据ID
     * @return 行项集合
     */
    private List<ErpStockOutItem> loadItems(String id)
    {
        List<ErpStockOutItem> items = stockOutItemMapper.selectItemsByDocId(id);
        return items == null ? new ArrayList<ErpStockOutItem>() : items;
    }

    /**
     * 变更历史写入器。
     *
     * @return 写入器
     */
    private ErpStockChangeLogWriter changeLog()
    {
        return new ErpStockChangeLogWriter(changeLogMapper);
    }

    /**
     * 取下一个单号（平台编号服务；前缀 OUT）。
     *
     * @param docDate 单据日期
     * @return 单号
     */
    protected String nextDocNo(Date docDate)
    {
        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为出库单取号");
        }
        return docNoGenerator.nextDocNo(DOC_TYPE, docDate);
    }

    /**
     * 当前用户ID。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        String userId = ErpDocScope.currentUserId();
        return userId == null ? ErpStockRules.DEFAULT_USER_ID : userId;
    }

    /**
     * 当前用户部门ID。
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        String deptId = ErpDocScope.currentDeptId();
        return deptId == null ? ErpStockRules.DEFAULT_DEPT_ID : deptId;
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
     * 当前时间。
     *
     * @return 时间
     */
    protected Date now()
    {
        return new Date();
    }
}
