package com.ruoyi.ctms.erp.posting.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.ErpStockParams;
import com.ruoyi.ctms.erp.posting.ErpStockRules;
import com.ruoyi.ctms.erp.posting.domain.ErpStock;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockLedgerMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockMapper;
import com.ruoyi.ctms.erp.posting.service.IErpStockJournalService;
import com.ruoyi.ctms.erp.posting.service.IErpStockPostingListener;
import com.ruoyi.ctms.erp.posting.support.ErpPostedLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingLine;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;
import com.ruoyi.system.service.ISysConfigService;

/**
 * <p> <b>库存过账引擎实现</b>（2.0 B4 任务 5.2~5.5；design D2/D3/D5）。 </p>
 *
 * <p> 执行顺序（两个阶段，都在<b>同一个 {@code @Transactional}</b> 里）： </p>
 * <ol>
 *   <li> <b>阶段一（加锁 + 校验，不写库）</b>：按（物料, 仓库）<b>排序后逐个</b>
 *        {@code SELECT ... FOR UPDATE}，在锁内按行累计数量并做负库存校验。
 *        排序是为了让并发过账以一致的顺序取锁（避免死锁）；"校验不写库"是为了
 *        "某一行库存不足 → 整单不产生任何流水"这条断言在<b>没有事务回滚的内存桩</b>
 *        单测里也成立。 </li>
 *   <li> <b>阶段二（写入）</b>：对每个（物料, 仓库）逐行追加流水（含变动后结存快照），
 *        最后把结存列改写为累计值。结存行不存在时插入；并发插入撞唯一键
 *        {@code uk_stock_product_warehouse} 时捕获 {@link DuplicateKeyException} 并重试
 *        （有限次，design D3）。 </li>
 * </ol>
 *
 * <p> <b>为什么用绝对值写结存</b>（{@code updateStockQty}）：负库存校验与流水快照都基于
 * 锁内读到的值计算；写绝对值能让"结存列"与"最后一条流水的变动后结存"永远一致。 </p>
 *
 * <p> <b>负库存参数</b>：{@link #readNegativeStockParam()} 是唯一的参数读取点，
 * 键名冻结在 {@link ErpStockParams#KEY_ALLOW_NEGATIVE_STOCK}；读不到一律按"不允许"处理
 * （宁可拦住，也不因为读不到配置而放开校验）。子类可覆盖该方法以脱库单测。 </p>
 *
 * @author 二开
 */
@Service
public class ErpStockJournalServiceImpl implements IErpStockJournalService
{
    /** 结存行并发创建的插入重试上限（design D3 的"有限次"）。 */
    public static final int MAX_CREATE_RETRY = 3;

    /** 结存 Mapper。 */
    @Autowired
    private ErpStockMapper stockMapper;

    /** 流水 Mapper（只增不改）。 */
    @Autowired
    private ErpStockLedgerMapper stockLedgerMapper;

    /** 系统参数服务（"是否允许负库存"）；可空，缺失时按默认（不允许）处理。 */
    @Autowired(required = false)
    private ISysConfigService configService;

    /**
     * 过账/红冲的扩展点（采购线/销售线回写"已入库量/已出库量"用）。
     *
     * <p> 没有实现时为空集合：本引擎不依赖任何监听器即可工作。 </p>
     */
    @Autowired(required = false)
    private List<IErpStockPostingListener> postingListeners;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPostingOutcome post(ErpPostingRequest request)
    {
        ErpPostingOutcome outcome = apply(request, false);
        notifyListeners(request.getDocType(), request.getDocId(), outcome.getLines(), false);
        return outcome;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErpPostingOutcome reverse(ErpDocType docType, String docId, String reason,
                                     String operatorId, String operatorName)
    {
        if (docType == null)
        {
            throw new ServiceException("单据类型不能为空");
        }
        String id = ErpStockRules.trimToNull(docId);
        if (id == null)
        {
            throw new ServiceException("单据ID不能为空");
        }
        List<ErpStockLedger> originals = stockLedgerMapper.selectLedgerByDoc(docType.getCode(), id);
        // 只有"尚未被冲销的过账流水"才该被冲：条数 = 非红冲流水数 − 红冲流水数（下限 0）。
        // 这条规则让引擎自身幂等（重复反审核不会把原流水冲两遍），与调用方的 posted 标记互补：
        // 调用方短路是"快路径"，引擎的计数是"即便被绕过也不会把账冲坏"的兜底。
        List<ErpStockLedger> plain = new ArrayList<>();
        int reversalCount = 0;
        for (ErpStockLedger ledger : originals)
        {
            if (ledger == null || ledger.getQtyChange() == null || ledger.getQtyChange().signum() == 0)
            {
                continue;
            }
            if (ledger.isReversal())
            {
                reversalCount++;
            }
            else
            {
                plain.add(ledger);
            }
        }
        int remaining = plain.size() - reversalCount;
        if (remaining <= 0)
        {
            // 未过账 / 已全部冲销：幂等返回空结果（调用方据此把"红冲"当成已完成）
            return new ErpPostingOutcome();
        }
        ErpPostingRequest request = new ErpPostingRequest()
                .setDocType(docType)
                .setDocId(id)
                .setOperatorId(operatorId)
                .setOperatorName(operatorName)
                .setOptions(ErpPostingOptions.skipNegativeCheck())
                .setRemark(reversalRemark(reason));
        for (int i = 0; i < remaining; i++)
        {
            ErpStockLedger original = plain.get(i);
            int rowNo = i + 1;
            ErpPostingLine line = ErpPostingLine.of(rowNo, original.getProductId(), original.getWarehouseId(),
                    original.getQtyChange().negate(), original.getUnitPrice());
            line.setBizType(ErpStockLedger.reversalBizType(original.getBizType()));
            line.setSrcItemId(null);
            request.getLines().add(line);
            if (ErpStockRules.isBlank(request.getDocNo()))
            {
                request.setDocNo(original.getDocNo());
            }
            if (ErpStockRules.isBlank(request.getSrcDocNo()))
            {
                request.setSrcDocNo(original.getSrcDocNo());
            }
            if (ErpStockRules.isBlank(request.getDeptId()))
            {
                request.setDeptId(original.getDeptId());
            }
        }
        // 请求级业务类型只是兜底（每行都带自己的"红冲-原类型"）
        request.setBizType(ErpStockLedger.REVERSAL_PREFIX);
        ErpPostingOutcome outcome = apply(request, true);
        notifyListeners(docType, id, outcome.getLines(), true);
        return outcome;
    }

    @Override
    public List<ErpStockLedger> ledgerOf(ErpDocType docType, String docId)
    {
        if (docType == null || ErpStockRules.isBlank(docId))
        {
            return new ArrayList<>();
        }
        List<ErpStockLedger> list = stockLedgerMapper.selectLedgerByDoc(docType.getCode(), docId);
        return list == null ? new ArrayList<ErpStockLedger>() : list;
    }

    @Override
    public BigDecimal ledgerSum(String productId, String warehouseId)
    {
        BigDecimal sum = stockLedgerMapper.sumQtyChangeByKey(productId, warehouseId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Override
    public boolean negativeStockAllowed()
    {
        return ErpStockParams.negativeStockAllowed(readNegativeStockParam());
    }

    /**
     * 读系统参数 {@code stock_allow_negative}（唯一的读取点；子类可覆盖以脱库单测）。
     *
     * @return 参数值；读不到返回 {@code null}（按"不允许负库存"处理）
     */
    protected String readNegativeStockParam()
    {
        try
        {
            return configService == null
                    ? null
                    : configService.selectConfigByKey(ErpStockParams.KEY_ALLOW_NEGATIVE_STOCK);
        }
        catch (Exception e)
        {
            // 无登录上下文 / 缓存未加载：不让"读配置"这一步把过账链路打死，按默认（不允许）处理
            return null;
        }
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 过账/红冲的共有执行体（框架与顺序见类注释）。
     *
     * @param request  入参
     * @param reversal 是否红冲批次（红冲跳过负库存校验，且流水带红冲前缀）
     * @return 已落库的行结果
     */
    private ErpPostingOutcome apply(ErpPostingRequest request, boolean reversal)
    {
        validateRequest(request);
        boolean allowNegative = reversal || request.getOptions().isSkipNegativeCheck()
                || negativeStockAllowed();

        List<LineGroup> groups = groupLines(request.getLines());
        Map<String, ErpStock> locked = new LinkedHashMap<>();

        // ---------- 阶段一：加锁 + 负库存校验（不写库） ----------
        for (LineGroup group : groups)
        {
            ErpStock stock = stockMapper.selectStockForUpdate(group.productId, group.warehouseId);
            locked.put(group.key(), stock);
            BigDecimal running = stock == null ? BigDecimal.ZERO : stock.qtyOrZero();
            for (ErpPostingLine line : group.lines)
            {
                BigDecimal before = running;
                running = running.add(line.getQtyChange());
                if (!allowNegative && running.signum() < 0)
                {
                    throw shortage(before, line);
                }
            }
        }

        // ---------- 阶段二：写入流水 + 改写结存 ----------
        ErpPostingOutcome outcome = new ErpPostingOutcome();
        for (LineGroup group : groups)
        {
            ErpStock stock = locked.get(group.key());
            if (stock == null)
            {
                stock = createStockRow(group);
            }
            BigDecimal running = stock.qtyOrZero();
            for (ErpPostingLine line : group.lines)
            {
                running = ErpAmounts.roundQty(running.add(line.getQtyChange()));
                ErpStockLedger ledger = buildLedger(request, line, running, reversal);
                stockLedgerMapper.insertLedger(ledger);
                outcome.addLine(postedLine(line, ledger, reversal));
            }
            stockMapper.updateStockQty(stock.getId(), running);
        }
        return outcome;
    }

    /**
     * 入参校验（缺什么报什么，带行号）。
     *
     * @param request 入参
     * @throws ServiceException 单据标识、业务类型、行项或仓库缺失
     */
    private void validateRequest(ErpPostingRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("过账入参不能为空");
        }
        if (request.getDocType() == null)
        {
            throw new ServiceException("单据类型不能为空");
        }
        if (ErpStockRules.isBlank(request.getDocId()))
        {
            throw new ServiceException("单据ID不能为空");
        }
        List<ErpPostingLine> lines = request.getLines();
        if (lines == null || lines.isEmpty())
        {
            throw new ServiceException(ErpStockRules.MSG_NO_ITEMS);
        }
        boolean requestBizTypeUsable = !ErpStockRules.isBlank(request.getBizType());
        int rowNo = 0;
        for (ErpPostingLine line : lines)
        {
            rowNo++;
            if (line == null)
            {
                throw new ServiceException(ErpStockRules.rowPrefix(rowNo) + "行项不能为空");
            }
            if (line.getRowNo() <= 0)
            {
                line.setRowNo(rowNo);
            }
            if (ErpStockRules.isBlank(line.getProductId()))
            {
                throw new ServiceException(ErpStockRules.rowPrefix(line.getRowNo()) + "必须选择物料档案");
            }
            if (ErpStockRules.isBlank(line.getWarehouseId()))
            {
                throw new ServiceException(ErpStockRules.rowPrefix(line.getRowNo())
                        + ErpStockRules.MSG_WAREHOUSE_REQUIRED);
            }
            if (line.getQtyChange() == null)
            {
                throw new ServiceException(ErpStockRules.rowPrefix(line.getRowNo()) + "数量变动不能为空");
            }
            if (!requestBizTypeUsable && ErpStockRules.isBlank(line.getBizType()))
            {
                throw new ServiceException(ErpStockRules.rowPrefix(line.getRowNo()) + "业务类型不能为空");
            }
        }
    }

    /**
     * 按（物料, 仓库）分组，并按 key <b>排序</b>返回（并发下取锁顺序一致，避免死锁）。
     *
     * @param lines 行指令
     * @return 分组（同一组合的行保持原始行号顺序）
     */
    private List<LineGroup> groupLines(List<ErpPostingLine> lines)
    {
        Map<String, LineGroup> groups = new TreeMap<>();
        for (ErpPostingLine line : lines)
        {
            String key = line.getProductId() + "\u0001" + line.getWarehouseId();
            LineGroup group = groups.get(key);
            if (group == null)
            {
                group = new LineGroup(line.getProductId(), line.getWarehouseId());
                groups.put(key, group);
            }
            group.lines.add(line);
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * 结存行不存在时插入；并发插入撞唯一键时重试（design D3）。
     *
     * @param group 分组
     * @return 已加锁的结存行
     * @throws ServiceException 重试上限耗尽
     */
    private ErpStock createStockRow(LineGroup group)
    {
        for (int attempt = 1; attempt <= MAX_CREATE_RETRY; attempt++)
        {
            ErpStock created = new ErpStock();
            created.setId(IdUtils.fastSimpleUUID());
            created.setProductId(group.productId);
            created.setWarehouseId(group.warehouseId);
            created.setQty(BigDecimal.ZERO);
            try
            {
                stockMapper.insertStock(created);
                ErpStock locked = stockMapper.selectStockForUpdate(group.productId, group.warehouseId);
                return locked == null ? created : locked;
            }
            catch (DuplicateKeyException e)
            {
                // 另一并发事务已建行：重新加锁读取（唯一键保证只有一行，不会变成两行）
                ErpStock locked = stockMapper.selectStockForUpdate(group.productId, group.warehouseId);
                if (locked != null)
                {
                    return locked;
                }
                // 那一行又恰好被删/回滚：进入下一轮重试
            }
        }
        throw new ServiceException("结存行创建冲突：并发创建同一（物料, 仓库）超过 " + MAX_CREATE_RETRY + " 次");
    }

    /**
     * 组装一条流水（字段口径见 {@link ErpStockLedger}）。
     *
     * @param request  入参（提供单据身份与操作人）
     * @param line     行指令
     * @param qtyAfter 变动后结存快照
     * @param reversal 是否红冲批次
     * @return 流水
     */
    private ErpStockLedger buildLedger(ErpPostingRequest request, ErpPostingLine line,
                                      BigDecimal qtyAfter, boolean reversal)
    {
        ErpStockLedger ledger = new ErpStockLedger();
        ledger.setId(IdUtils.fastSimpleUUID());
        ledger.setProductId(line.getProductId());
        ledger.setWarehouseId(line.getWarehouseId());
        ledger.setBizType(resolveBizType(request, line, reversal));
        ledger.setDocType(request.getDocType().getCode());
        ledger.setDocId(request.getDocId());
        ledger.setDocNo(request.getDocNo());
        ledger.setSrcDocNo(request.getSrcDocNo());
        ledger.setQtyChange(ErpAmounts.roundQty(line.getQtyChange()));
        ledger.setQtyAfter(qtyAfter);
        ledger.setUnitPrice(line.getUnitPrice());
        ledger.setDeptId(request.getDeptId());
        ledger.setCreateId(request.getOperatorId());
        ledger.setCreateBy(request.getOperatorName());
        ledger.setRemark(ErpStockRules.isBlank(line.getRemark()) ? request.getRemark() : line.getRemark());
        return ledger;
    }

    /**
     * 业务类型：行级覆盖优先（红冲用它对每条原流水各自加前缀），否则用请求级。
     *
     * @param request  入参
     * @param line     行指令
     * @param reversal 是否红冲批次
     * @return 业务类型
     */
    private String resolveBizType(ErpPostingRequest request, ErpPostingLine line, boolean reversal)
    {
        if (!ErpStockRules.isBlank(line.getBizType()))
        {
            return line.getBizType();
        }
        return reversal ? ErpStockLedger.reversalBizType(request.getBizType()) : request.getBizType();
    }

    /**
     * 把落库结果转成对外结果行。
     *
     * @param line     行指令（本次写入值 = {@code qtyChange}）
     * @param ledger   刚落库的流水
     * @param reversal 是否红冲
     * @return 结果行
     */
    private ErpPostedLine postedLine(ErpPostingLine line, ErpStockLedger ledger, boolean reversal)
    {
        ErpPostedLine posted = new ErpPostedLine();
        posted.setRowNo(line.getRowNo());
        posted.setProductId(line.getProductId());
        posted.setWarehouseId(line.getWarehouseId());
        posted.setQtyChange(ledger.getQtyChange());
        posted.setOriginalQtyChange(reversal ? ledger.getQtyChange().negate() : ledger.getQtyChange());
        posted.setUnitPrice(line.getUnitPrice());
        posted.setQtyAfter(ledger.getQtyAfter());
        posted.setSrcItemId(line.getSrcItemId());
        posted.setLedgerId(ledger.getId());
        posted.setReversal(reversal);
        return posted;
    }

    /**
     * 库存不足的统一文案（返回可用量与需求量，前端与验收脚本按它断言）。
     *
     * @param available 该行扣减前的可用量
     * @param line      触发行
     * @return 业务异常
     */
    private ServiceException shortage(BigDecimal available, ErpPostingLine line)
    {
        BigDecimal demand = line.getQtyChange() == null ? BigDecimal.ZERO : line.getQtyChange().abs();
        return new ServiceException(ErpStockRules.rowPrefix(line.getRowNo())
                + "物料「" + line.productLabel() + "」在仓库「" + line.warehouseLabel()
                + "」库存不足，可用量 " + ErpAmounts.textOf(available)
                + "，需求量 " + ErpAmounts.textOf(demand));
    }

    /**
     * 红冲备注：固定前缀 + 反审核原因（原因必填在状态机侧，这里只做留痕）。
     *
     * @param reason 反审核原因（可空）
     * @return 备注
     */
    private String reversalRemark(String reason)
    {
        String trimmed = ErpStockRules.trimToNull(reason);
        return trimmed == null ? ErpStockLedger.REVERSAL_REMARK
                : ErpStockLedger.REVERSAL_REMARK + "：" + trimmed;
    }

    /**
     * 通知监听器（同一事务；监听器抛错 → 整个过账回滚）。
     *
     * @param docType  单据类型
     * @param docId    单据ID
     * @param lines    已落库的行
     * @param reversed 是否红冲
     */
    private void notifyListeners(ErpDocType docType, String docId, List<ErpPostedLine> lines, boolean reversed)
    {
        if (postingListeners == null || postingListeners.isEmpty())
        {
            return;
        }
        for (IErpStockPostingListener listener : postingListeners)
        {
            if (listener == null || !listener.supports(docType))
            {
                continue;
            }
            if (reversed)
            {
                listener.afterReversed(docType, docId, lines);
            }
            else
            {
                listener.afterPosted(docType, docId, lines);
            }
        }
    }

    /**
     * 同一（物料, 仓库）的行分组（内部结构）。
     */
    private static final class LineGroup
    {
        private final String productId;

        private final String warehouseId;

        private final List<ErpPostingLine> lines = new ArrayList<>();

        private LineGroup(String productId, String warehouseId)
        {
            this.productId = productId;
            this.warehouseId = warehouseId;
        }

        private String key()
        {
            return productId + "\u0001" + warehouseId;
        }
    }
}
