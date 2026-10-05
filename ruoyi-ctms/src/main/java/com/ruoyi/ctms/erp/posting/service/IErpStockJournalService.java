package com.ruoyi.ctms.erp.posting.service;

import java.math.BigDecimal;
import java.util.List;

import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.domain.ErpStockLedger;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;
import com.ruoyi.ctms.erp.posting.support.ErpPostingRequest;

/**
 * <p> <b>库存过账引擎</b>（2.0 B4 任务 5.2/5.3/5.4/5.5）。 </p>
 *
 * <p> 结存与流水的<b>唯一写入服务</b>：入库单/出库单的审核与反审核、调拨的两仓双向写入、
 * 盘点生成的盘盈/盘亏单过账，全部走这里。其他组（调拨盘点、采购/销售回写）
 * <b>只调用、不复刻</b>行锁与流水写法。 </p>
 *
 * <p> <b>四条不可协商的口径</b>： </p>
 * <ol>
 *   <li> <b>显式事务 + 行锁</b>：{@code post}/{@code reverse} 都是
 *        {@code @Transactional(rollbackFor = Exception.class)}，并且对每个
 *        （物料, 仓库）先 {@code SELECT ... FOR UPDATE} 再读值
 *        —— 否则并发审核会丢更新（design D2）； </li>
 *   <li> <b>幂等由调用方短路</b>：{@code post} 本身不做去重（同一单据第二次过账会再写一批流水），
 *        由调用方按 {@code posted} 标记短路；{@code reverse} 则自带一层"只冲销尚未被冲销的
 *        过账流水"的计数兜底（见其注释），因此重复反审核不会把账冲两遍； </li>
 *   <li> <b>负库存校验</b>：默认按系统参数 {@code stock_allow_negative}；
 *        仅"红冲"与"盘亏豁免"两处显式跳过（{@code ErpPostingOptions}）； </li>
 *   <li> <b>红冲 = 追加负数流水</b>：原流水保留可查不可改，红冲行的业务类型加
 *        {@code 红冲-} 前缀，并写备注（默认"反审核红冲"，可附反审核原因）。 </li>
 * </ol>
 *
 * <p> <b>调用方必须自己保证的</b>：行项仓库已在过账前解析（行级仓库回落表头，
 * 两者都空时给出行号并拒绝）；单据状态已按状态机校验通过。 </p>
 *
 * @author 二开
 */
public interface IErpStockJournalService
{
    /**
     * 过账一批行项：按（物料, 仓库）加锁 → 校验负库存 → 更新结存 → 逐行追加流水。
     *
     * <p> 任一环节失败整体回滚（含"某一行库存不足"），调用方据此保证
     * "结存与单据状态都不变、不产生流水"。 </p>
     *
     * @param request 过账入参（不得为空；行为空、单据标识缺失、仓库缺失都会被拒）
     * @return 已落库的行结果（含每行的变动后结存快照）
     */
    ErpPostingOutcome post(ErpPostingRequest request);

    /**
     * <p> 按单据红冲：取该单据<b>非红冲</b>的历史流水，逐条追加其相反数。 </p>
     *
     * <p> 三条语义： </p>
     * <ul>
     *   <li> <b>幂等</b>：没有可冲销的原流水（未过账 / 已冲销过）时返回空结果、不报错； </li>
     *   <li> <b>不受负库存校验拦截</b>（红冲允许结存转负）； </li>
     *   <li> 红冲行的业务类型 = {@code 红冲-} + 原业务类型，备注 = "反审核红冲" + 反审核原因。 </li>
     * </ul>
     *
     * @param docType      单据类型
     * @param docId        单据ID
     * @param reason       反审核原因（可为空；写入流水备注便于追溯）
     * @param operatorId   操作人用户ID
     * @param operatorName 操作人登录名
     * @return 已落库的红冲行结果
     */
    ErpPostingOutcome reverse(ErpDocType docType, String docId, String reason,
                              String operatorId, String operatorName);

    /**
     * 取某单据的全部流水（含红冲行，按 id 升序）。
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @return 流水集合（无则空集合）
     */
    List<ErpStockLedger> ledgerOf(ErpDocType docType, String docId);

    /**
     * 某（物料, 仓库）全部流水的数量变动累计和（一致性核对用）。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 累计和（无流水返回 0）
     */
    BigDecimal ledgerSum(String productId, String warehouseId);

    /**
     * 当前是否允许负库存（读系统参数 {@code stock_allow_negative}；默认关闭）。
     *
     * @return 允许返回 true
     */
    boolean negativeStockAllowed();
}
