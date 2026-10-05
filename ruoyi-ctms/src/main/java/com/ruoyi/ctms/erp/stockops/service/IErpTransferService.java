package com.ruoyi.ctms.erp.stockops.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;

/**
 * <p> <b>调拨单服务</b>（2.0 B4 任务 6.1/6.2；design D8/D11）。 </p>
 *
 * <p> <b>两阶段过账</b>（本接口的核心约定）： </p>
 * <ol>
 *   <li> <b>阶段一（校验，不写库）</b>：两仓非空且不同、至少一行、每行数量 &gt; 0；
 *        随后由过账引擎在<b>锁内</b>按（物料, 仓库）排序校验"调出仓可用量足够"； </li>
 *   <li> <b>阶段二（写入）</b>：每行展开成<b>两条流水</b>（调出仓 {@code -qty} + 调入仓 {@code +qty}，
 *        同一单据号、业务类型分别为 {@code 调拨出库} / {@code 调拨入库}、单价 0），
 *        由引擎统一落库；任一校验失败<b>整体不写入</b>（含"第一行也不写"）。 </li>
 * </ol>
 *
 * <p> 幂等与红冲同 §5 口径：已过账再审核短路；反审核追加负数流水并让单据回到待审核。 </p>
 *
 * <p> 数据范围：列表由服务层写入 {@code dataScopeSql}，详情/编辑/删除/动作一律先过
 * {@code IErpDocObjectAccess}（范围外业务码 403）。 </p>
 *
 * @author 二开
 */
public interface IErpTransferService
{
    /**
     * 调拨单列表（关键字 / 状态 / 两仓 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件（可空）
     * @return 单据集合
     */
    List<ErpTransfer> selectTransferList(ErpTransfer query);

    /**
     * 调拨单详情（含行项；范围外抛 403）。
     *
     * @param id 单据ID
     * @return 单据
     */
    ErpTransfer selectTransferDetail(String id);

    /**
     * 新建调拨单（强制草稿；单号走平台编号服务，前缀 {@code DB}）。
     *
     * @param doc 单据（行项在 {@code items}）
     * @return 落库后的单据
     */
    ErpTransfer insertTransfer(ErpTransfer doc);

    /**
     * 编辑调拨单（仅草稿；行项全量替换；两仓相同被拒）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpTransfer updateTransfer(ErpTransfer doc);

    /**
     * 删除调拨单（仅草稿；物理删除，行项随外键级联）。
     *
     * @param ids 单据ID数组
     * @return 删除条数
     */
    int deleteTransferByIds(String[] ids);

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 落库后的单据
     */
    ErpTransfer submitTransfer(String id);

    /**
     * 审核（待审核 → 已审核）并在<b>同一事务内</b>两阶段过账；已过账时幂等返回。
     *
     * @param id 单据ID
     * @return 落库后的单据
     */
    ErpTransfer approveTransfer(String id);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 落库后的单据
     */
    ErpTransfer rejectTransfer(String id, String reason);

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 落库后的单据
     */
    ErpTransfer voidTransfer(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲两条流水）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 落库后的单据
     */
    ErpTransfer unapproveTransfer(String id, String reason);

    /**
     * 某调拨单的变更历史（按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 日志集合
     */
    List<CtmsChangeLog> selectChangeLogList(String id);
}
