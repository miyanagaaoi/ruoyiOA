package com.ruoyi.ctms.erp.posting.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;

/**
 * <p> <b>出库单服务</b>（2.0 B4 任务 5.1~5.6）。 </p>
 *
 * <p> 与 {@link IErpStockInService} 的入库侧严格对称，差异三条： </p>
 * <ol>
 *   <li> 过账方向是<b>扣减</b>结存； </li>
 *   <li> <b>负库存校验</b>：默认（系统参数 {@code stock_allow_negative} 关闭）会使结存小于 0
 *        的出库审核被整体拒绝，返回可用量与需求量，且结存与单据状态都不变、不产生流水；
 *        参数开启后放开（任务 5.3）； </li>
 *   <li> <b>盘亏豁免</b>：盘点审核生成的盘亏出库单过账时显式传入
 *        {@code ErpPostingOptions.skipNegativeCheck()}（tasks.md §6.4），
 *        而不是改系统参数（豁免面必须小且可审计）。 </li>
 * </ol>
 *
 * @author 二开
 */
public interface IErpStockOutService
{
    /**
     * 出库单列表（关键字 / 出库类型 / 仓库 / 客户 / 状态 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件（可空）
     * @return 单据集合
     */
    List<ErpStockOut> selectStockOutList(ErpStockOut query);

    /**
     * 出库单详情（含行项；范围外抛 403）。
     *
     * @param id 单据ID
     * @return 单据
     */
    ErpStockOut selectStockOutDetail(String id);

    /**
     * 新建出库单（强制草稿；单号走平台编号服务，前缀 {@code OUT}）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpStockOut insertStockOut(ErpStockOut doc);

    /**
     * 新建<b>已审核</b>的出库单（仅供盘点审核生成盘亏单使用）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpStockOut insertGeneratedStockOut(ErpStockOut doc);

    /**
     * 编辑出库单（仅草稿）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpStockOut updateStockOut(ErpStockOut doc);

    /**
     * 删除出库单（仅草稿）。
     *
     * @param ids 单据ID数组
     * @return 删除条数
     */
    int deleteStockOutByIds(String[] ids);

    /**
     * 提交（草稿 → 待审核）。
     *
     * @param id 单据ID
     * @return 落库后的单据
     */
    ErpStockOut submitStockOut(String id);

    /**
     * 审核（待审核 → 已审核）并在同一事务内过账（受负库存校验约束，除非传入豁免选项）。
     *
     * @param id      单据ID
     * @param options 过账选项（盘亏豁免传 {@code skipNegativeCheck()}）
     * @return 落库后的单据
     */
    ErpStockOut approveStockOut(String id, ErpPostingOptions options);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 落库后的单据
     */
    ErpStockOut rejectStockOut(String id, String reason);

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 落库后的单据
     */
    ErpStockOut voidStockOut(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲并清除过账标记）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 落库后的单据
     */
    ErpStockOut unapproveStockOut(String id, String reason);

    /**
     * 对"已审核但未过账"的单据执行过账（幂等）。
     *
     * @param id      单据ID
     * @param options 过账选项
     * @return 过账结果
     */
    ErpPostingOutcome postApprovedStockOut(String id, ErpPostingOptions options);

    /**
     * 对"已过账"的单据执行红冲并清除过账标记（幂等）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 红冲结果
     */
    ErpPostingOutcome reversePostedStockOut(String id, String reason);

    /**
     * 某出库单的变更历史（按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 日志集合
     */
    List<CtmsChangeLog> selectChangeLogList(String id);
}
