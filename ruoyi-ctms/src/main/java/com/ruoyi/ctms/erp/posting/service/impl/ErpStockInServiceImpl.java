package com.ruoyi.ctms.erp.posting.service.impl;

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
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockInItemMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockInMapper;
import com.ruoyi.ctms.erp.posting.service.IErpStockInService;
import com.ruoyi.ctms.erp.posting.service.IErpStockJournalService;
import com.ruoyi.ctms.erp.posting.support.ErpPostingLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;
import com.ruoyi.ctms.erp.posting.support.ErpStockChangeLogWriter;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>入库单服务实现</b>（2.0 B4 任务 5.1~5.6）。 </p>
 *
 * <p> 五条实现口径（评审时逐条对照 AC-73）： </p>
 * <ol>
 *   <li> <b>审核即过账</b>：{@code approveStockIn} 是
 *        {@code @Transactional(rollbackFor = Exception.class)}，先过账、后写状态与过账标记；
 *        过账抛错时状态一个字节都不会变（任务 5.2）； </li>
 *   <li> <b>幂等</b>：已过账（{@code posted = '1'}）的单据再调审核<b>直接返回</b>，
 *        不产生流水、不改结存（任务 5.2 的"重复审核"场景）； </li>
 *   <li> <b>过账前解析仓库</b>：行项未填仓库时回落表头仓库，两者都空时按行号报错
 *        （任务 5.1；文案调用 {@link ErpMasterGuards#resolveWarehouseId}）； </li>
 *   <li> <b>反审核即红冲</b>：{@code unapproveStockIn} 调过账引擎的 {@code reverse}
 *        —— 追加负数流水（业务类型带"红冲-"前缀）、清除过账标记、单据回到待审核（任务 5.4）； </li>
 *   <li> <b>留痕</b>：每次审核/反审核写两条变更历史（{@code status} 与 {@code posted}），
 *        操作日志由 Controller 的 {@code @Log} 承担（任务 5.6）。 </li>
 * </ol>
 *
 * <p> <b>单号</b>：走平台编号服务（{@link ErpDocNoGenerator#nextDocNo(ErpDocType, Date)}，
 * 前缀 {@code IN}），只在创建时取一次；编辑/流转不重新取号（captain §9.1 冻结口径）。 </p>
 *
 * <p> <b>为什么先过账再改状态</b>：过账是唯一会动库存的一步。先过账能让
 * "库存不足被拒 → 结存与状态都不变"在<b>没有事务回滚能力的内存桩单测</b>里也成立；
 * 真实库里两步同事务，谁先谁后都能整体回滚。 </p>
 *
 * @author 二开
 */
@Service
public class ErpStockInServiceImpl implements IErpStockInService
{
    /** 本服务负责的单据类型。 */
    private static final ErpDocType DOC_TYPE = ErpDocType.STOCK_IN;

    /**
     * 表头仓库必填的文案前缀（t33）。
     *
     * <p> {@code t_ctms_stock_in.warehouse_id} 是 {@code NOT NULL} + FK（冻结 DDL，不改表），
     * 缺仓库的手动创建会撞数据库原生 1048（表现为 500）⇒ 服务层主动抛业务异常，
     * 完整文案 = {@code 表头仓库不能为空：请选择入库仓库}。 </p>
     *
     * <p> <b>为什么是私有常量而不是放进 {@code ErpStockRules}</b>：t33 的写入范围限定在
     * {@code posting/service/impl}（{@code ErpStockRules} 不在本次申报范围内），
     * 因此入库/出库两侧各持一份文案前缀，双方文案由
     * {@code ErpStockDocServiceTest} 的两条断言逐字锁定，不会漂移。 </p>
     */
    private static final String MSG_HEADER_WAREHOUSE_REQUIRED = "表头仓库不能为空";

    /** 单据表头 Mapper。 */
    @Autowired
    private ErpStockInMapper stockInMapper;

    /** 行项 Mapper。 */
    @Autowired
    private ErpStockInItemMapper stockInItemMapper;

    /** 变更历史 Mapper（B3 交付的 t_ctms_change_log）。 */
    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /** 过账引擎（结存与流水的唯一写入服务）。 */
    @Autowired
    private IErpStockJournalService journalService;

    /** 单据对象访问校验（存在性 + 四档数据范围；base 包的唯一实现点）。 */
    @Autowired
    private IErpDocObjectAccess docObjectAccess;

    /** 主数据守卫的回调查找（物料/仓库/单位的存在性、启用状态与快照）；可空以便脱库单测。 */
    @Autowired(required = false)
    private ErpMasterGuards.MasterLookup masterLookup;

    /** 平台编号服务（base 包；可空时取号会明确报错，不做本地兜底）。 */
    @Autowired(required = false)
    private ErpDocNoGenerator docNoGenerator;

    /* ==================== 查询 ==================== */

    @Override
    public List<ErpStockIn> selectStockInList(ErpStockIn query)
    {
        ErpStockIn effective = query == null ? new ErpStockIn() : query;
        // 数据范围由服务端强制：片段只由 ErpDocScope 依登录上下文拼出，请求参数永远进不去
        effective.setDataScopeSql(ErpDocScope.buildDataScopeSql(ErpDocScope.DEFAULT_ALIAS));
        List<ErpStockIn> list = stockInMapper.selectStockInList(effective);
        return list == null ? new ArrayList<ErpStockIn>() : list;
    }

    @Override
    public ErpStockIn selectStockInDetail(String id)
    {
        ErpStockIn doc = requireExisting(id);
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
    public ErpStockIn insertStockIn(ErpStockIn doc)
    {
        return insert(doc, ErpDocStatus.DRAFT, ErpStockRules.LOG_SOURCE_MANUAL, "新建单据", false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn insertGeneratedStockIn(ErpStockIn doc)
    {
        // 生成路径：行项来自该仓库的已有结存（物料可能在建结存之后被停用），
        // 因此显式豁免"停用物料不可用于新单据"这一条面向用户录入的校验，其余校验照旧
        return insert(doc, ErpDocStatus.APPROVED, ErpStockRules.LOG_SOURCE_AUTO,
                "盘点审核自动生成盘盈入库单", true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn updateStockIn(ErpStockIn doc)
    {
        if (doc == null || ErpStockRules.isBlank(doc.getId()))
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        ErpStockIn exist = requireExisting(doc.getId());
        requireAccessible(doc.getId());
        ErpDocStateMachine.checkEditable(exist.getStatus(), DOC_TYPE);
        // t33：编辑同样要求表头仓库（DDL NOT NULL + FK；把 1048 换成可读中文），并回填名称快照
        applyHeaderWarehouse(doc);

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
        if (ErpStockRules.isBlank(doc.getInType()))
        {
            doc.setInType(exist.getInType());
        }
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());

        List<ErpStockInItem> items = normalizeItems(doc, false);
        doc.setTotalAmount(ErpStockRules.totalAmountOf(items));
        stockInMapper.updateStockIn(doc);
        // 行项全量替换（先删后插，同一事务）
        stockInItemMapper.deleteItemsByDocId(doc.getId());
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_ITEMS, null,
                "共 " + items.size() + " 行，合计 " + ErpStockRules.totalAmountOf(items),
                "编辑单据", ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteStockInByIds(String[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (String id : ids)
        {
            ErpStockIn doc = requireExisting(id);
            requireAccessible(id);
            // 只有草稿可删：已提交/已审核的单据必须走驳回/反审核，不能"删掉当没发生"
            ErpDocStateMachine.checkEditable(doc.getStatus(), DOC_TYPE);
        }
        return stockInMapper.deleteStockInByIds(ids);
    }

    /* ==================== 状态动作 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn submitStockIn(String id)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        List<ErpStockInItem> items = loadItems(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.SUBMIT, oldStatus, DOC_TYPE,
                items != null && !items.isEmpty(), null);
        doc.setStatus(target);
        doc.setSubmittedBy(currentUserId());
        doc.setSubmittedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "提交单据",
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn approveStockIn(String id, ErpPostingOptions options)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        // 幂等短路：已过账的重复审核不产生流水、不改结存，也不报错（任务 5.2）
        if (doc.isPostedFlag())
        {
            return doc;
        }
        List<ErpStockInItem> items = loadItems(id);
        if (items.isEmpty())
        {
            throw new ServiceException(ErpStockRules.MSG_NO_ITEMS);
        }
        // 仓库回落与"两者都空时报行号"在这一步完成（任务 5.1）
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
        // ① 先过账：失败则状态与结存都不变
        journalService.post(request);
        // ② 再写状态与过账标记（同一事务）
        return markApproved(doc, ErpDocStatus.SUBMITTED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn rejectStockIn(String id, String reason)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.REJECT, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "驳回：" + reason,
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn voidStockIn(String id, String reason)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.VOID, oldStatus, DOC_TYPE, false, reason);
        doc.setStatus(target);
        doc.setVoidedBy(currentUserId());
        doc.setVoidedAt(now());
        doc.setVoidReason(reason);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_STATUS, oldStatus, target, "作废：" + reason,
                ErpStockRules.LOG_SOURCE_MANUAL, currentUserId(), currentUsername());
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpStockIn unapproveStockIn(String id, String reason)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        String oldStatus = doc.getStatus();
        String oldPosted = doc.getPosted();
        String target = ErpDocStateMachine.checkAndNext(ErpDocAction.UNAPPROVE, oldStatus, DOC_TYPE, false, reason);
        boolean posted = doc.isPostedFlag();
        if (posted)
        {
            // 红冲不受负库存校验拦截（spec: 反审核红冲）
            journalService.reverse(DOC_TYPE, id, reason, currentUserId(), currentUsername());
        }
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setApprovedBy(null);
        doc.setApprovedAt(null);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
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
    public ErpPostingOutcome postApprovedStockIn(String id, ErpPostingOptions options)
    {
        ErpStockIn doc = requireExisting(id);
        requireAccessible(id);
        if (doc.isPostedFlag())
        {
            return new ErpPostingOutcome();
        }
        List<ErpStockInItem> items = loadItems(id);
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
    public ErpPostingOutcome reversePostedStockIn(String id, String reason)
    {
        ErpStockIn doc = requireExisting(id);
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
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, id, ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_YES,
                ErpDocHeader.POSTED_NO, "反审核红冲：" + reason, ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        return outcome;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * <p> <b>表头仓库：必填校验 + 名称快照回填</b>（t33 / t33-r1）。 </p>
     *
     * <p> 两件事： </p>
     * <ol>
     *   <li> <b>必填</b>：{@code warehouse_id} 是 {@code NOT NULL} + FK ⇒ 缺则抛可读中文
     *        （{@link ErpStockRules#checkHeaderWarehouse}），不让用户看到数据库原生 1048； </li>
     *   <li> <b>名称快照回填</b>：{@code warehouse_name} 也是 {@code NOT NULL}
     *        （{@code DEFAULT ''}，但 MyBatis 显式写 {@code null} 时不会走默认值 ⇒ 会
     *        {@code Column 'warehouse_name' cannot be null}）。因此服务端**从仓库档案回填**
     *        显示名（与行项快照同一个 {@code MasterLookup} 真源），
     *        <b>不要求调用方传显示名</b>：调用方只给 {@code warehouseId} 即可创建成功。 </li>
     * </ol>
     *
     * <p> <b>取值优先级</b>：档案名（权威快照，仓库改名后新单按新名）→ 调用方传入的名字（档案未装配时）→
     * <b>空串</b>（DDL 的默认值，绝不写 {@code null}）。第三种只发生在"档案查询不可用"的装配场景；
     * 真实运行期 {@code ErpMasterLookupImpl} 查的是真表，因此正常路径总是拿到档案名；
     * 而伪造的 id 会被 FK 挡下（不依赖本方法兜底）。 </p>
     *
     * @param doc 单据（读/写 {@code warehouseId}、{@code warehouseName}）
     */
    private void applyHeaderWarehouse(ErpStockIn doc)
    {
        if (ErpStockRules.isBlank(doc.getWarehouseId()))
        {
            throw new ServiceException(MSG_HEADER_WAREHOUSE_REQUIRED + "：请选择入库仓库");
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
     * @param source    留痕来源（manual / auto）
     * @param note      留痕备注
     * @param generated 是否系统生成路径（{@code true} 时豁免"停用物料不可用"，见
     *                  {@link ErpMasterGuards#applyGeneratedItemSnapshot}）
     * @return 落库后的单据
     */
    private ErpStockIn insert(ErpStockIn doc, String status, String source, String note, boolean generated)
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
        // 创建人/归属部门快照（服务端上下文；取不到直接报错，见 ErpDocScope）
        ErpDocScope.applyCreateSnapshot(doc, currentUserId(), currentDeptId(), currentUsername());
        doc.setStatus(status);
        doc.setPosted(ErpDocHeader.POSTED_NO);
        doc.setDelFlag(ErpDocHeader.DEL_FLAG_NORMAL);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        if (ErpStockRules.isBlank(doc.getInType()))
        {
            doc.setInType("采购入库");
        }
        List<ErpStockInItem> items = normalizeItems(doc, generated);
        doc.setTotalAmount(ErpStockRules.totalAmountOf(items));
        stockInMapper.insertStockIn(doc);
        persistItems(items);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_STATUS, null, status, note, source,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 行项归一：生成 id、重排序号、写物料/单位/仓库快照、校验数量与单价、重算行金额。
     *
     * <p> <b>仓库回落表头在此完成</b>：行项未填仓库时写表头仓库（含仓库名快照）；
     * 两者都空时草稿允许暂缺，审核阶段由 {@link #buildPostingLines} 报行号拒绝。 </p>
     *
     * <p> <b>守卫宽严由 {@code generated} 决定</b>（t23）：用户新建/编辑一律
     * {@link ErpMasterGuards#applyItemSnapshot}（停用物料被拒）；只有系统生成路径
     * （盘点差异生成的盘盈单）才走 {@link ErpMasterGuards#applyGeneratedItemSnapshot}。 </p>
     *
     * @param doc       单据（读表头仓库，写 items）
     * @param generated 是否系统生成路径（豁免"停用物料不可用"）
     * @return 归一后的行项（与 {@code doc.getItems()} 同一批对象）
     */
    private List<ErpStockInItem> normalizeItems(ErpStockIn doc, boolean generated)
    {
        List<ErpStockInItem> items = doc.getItems();
        ErpStockRules.checkItemsBasic(items);
        int rowNo = 0;
        for (ErpStockInItem item : items)
        {
            rowNo++;
            item.setId(IdUtils.fastSimpleUUID());
            item.setDocId(doc.getId());
            item.setSeq(rowNo);
            // 物料/单位快照 + 数量精度 + 行金额（base 公共层唯一实现点）
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
                // 行级仓库回落表头仓库（表头也空时保持 null，草稿允许）
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
     * 行项落库（全量替换的第二步）。
     *
     * @param items 行项
     */
    private void persistItems(List<ErpStockInItem> items)
    {
        if (items != null && !items.isEmpty())
        {
            stockInItemMapper.batchInsertItems(items);
        }
    }

    /**
     * 把行项转成过账行指令（<b>仓库回落 + 行号报错</b>在这里）。
     *
     * @param doc   单据
     * @param items 行项
     * @return 过账行指令（入库方向为正）
     */
    private List<ErpPostingLine> buildPostingLines(ErpStockIn doc, List<ErpStockInItem> items)
    {
        List<ErpPostingLine> lines = new ArrayList<>();
        int rowNo = 0;
        for (ErpStockInItem item : items)
        {
            rowNo++;
            String warehouseId = ErpMasterGuards.resolveWarehouseId(item, doc.getWarehouseId());
            if (warehouseId == null)
            {
                throw new ServiceException(ErpStockRules.rowPrefix(rowNo) + ErpStockRules.MSG_WAREHOUSE_REQUIRED);
            }
            ErpPostingLine line = ErpPostingLine.of(rowNo, item.getProductId(), warehouseId,
                    item.getQty(), item.getUnitPrice());
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
     * 写状态与过账标记（审核路径；先过账成功再调用）。
     *
     * @param doc        单据
     * @param fromStatus 期望的来源状态（用于留痕的旧值）
     * @return 落库后的单据
     */
    private ErpStockIn markApproved(ErpStockIn doc, String fromStatus)
    {
        String oldStatus = ErpStockRules.isBlank(doc.getStatus()) ? fromStatus : doc.getStatus();
        String target = ErpDocStatus.APPROVED;
        doc.setStatus(target);
        doc.setPosted(ErpDocHeader.POSTED_YES);
        doc.setApprovedBy(currentUserId());
        doc.setApprovedAt(now());
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_STATUS, oldStatus, target,
                "审核即过账（" + resolveBizType(doc) + "）", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                ErpDocHeader.POSTED_YES, "审核过账", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
        return doc;
    }

    /**
     * 只写"已过账"标记（给"已审核未过账"的生成单用；状态保持 approved）。
     *
     * @param doc 单据
     */
    private void markPosted(ErpStockIn doc)
    {
        doc.setPosted(ErpDocHeader.POSTED_YES);
        doc.setUpdateId(currentUserId());
        doc.setUpdateBy(currentUsername());
        stockInMapper.updateStockIn(doc);
        changeLog().write(DOC_TYPE, doc.getId(), ErpStockRules.LOG_FIELD_POSTED, ErpDocHeader.POSTED_NO,
                ErpDocHeader.POSTED_YES, "审核过账", ErpStockRules.LOG_SOURCE_MANUAL,
                currentUserId(), currentUsername());
    }

    /**
     * 业务类型取入库类型（流水 biz_type 的真源）。
     *
     * @param doc 单据
     * @return 入库类型（缺失时回落默认值）
     */
    private String resolveBizType(ErpStockIn doc)
    {
        return ErpStockRules.isBlank(doc.getInType()) ? "采购入库" : doc.getInType().trim();
    }

    /**
     * 取单据（不存在直接报错）。
     *
     * @param id 单据ID
     * @return 单据
     */
    private ErpStockIn requireExisting(String id)
    {
        String docId = ErpStockRules.trimToNull(id);
        if (docId == null)
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        ErpStockIn doc = stockInMapper.selectStockInById(docId);
        if (doc == null)
        {
            throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
        }
        if (ErpDocHeader.DEL_FLAG_NORMAL.equals(doc.getDelFlag()))
        {
            return doc;
        }
        throw new ServiceException(ErpStockRules.MSG_NOT_FOUND);
    }

    /**
     * 数据范围校验（base 包的唯一实现点；范围外业务码 403）。
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
    private List<ErpStockInItem> loadItems(String id)
    {
        List<ErpStockInItem> items = stockInItemMapper.selectItemsByDocId(id);
        return items == null ? new ArrayList<ErpStockInItem>() : items;
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
     * 取下一个单号（平台编号服务；前缀 IN）。
     *
     * @param docDate 单据日期（决定年月号段）
     * @return 单号
     */
    protected String nextDocNo(Date docDate)
    {
        if (docNoGenerator == null)
        {
            throw new ServiceException("编号服务未装配，无法为入库单取号");
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
        return userId == null ? ErpStockRules.DEFAULT_USER_ID : userId;
    }

    /**
     * 当前用户部门ID（脱库单测可覆盖）。
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        String deptId = ErpDocScope.currentDeptId();
        return deptId == null ? ErpStockRules.DEFAULT_DEPT_ID : deptId;
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
     * 当前时间（脱库单测可覆盖，避免依赖真实时钟）。
     *
     * @return 时间
     */
    protected Date now()
    {
        return new Date();
    }
}
