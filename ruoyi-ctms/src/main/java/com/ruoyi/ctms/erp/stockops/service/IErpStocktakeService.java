package com.ruoyi.ctms.erp.stockops.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;

/**
 * <p> <b>盘点单服务</b>（2.0 B4 任务 6.3/6.4/6.5；design D8/D11、Q-B10）。 </p>
 *
 * <p> <b>四个动作口径</b>： </p>
 * <ul>
 *   <li> {@link #insertStocktake(ErpStocktake)} / {@link #updateStocktake(ErpStocktake)}：
 *        行项生成（全盘取该仓库结存非零物料、抽盘按物料或按商品类型含整棵子树）与实盘录入都在
 *        这两个入口里完成 —— <b>不新造动作名</b>（"生成行项"并入 add、"录入实盘"并入 edit）；
 *        {@link #generateItems(String, ErpStocktake)} 与 {@link #saveCount(String, List)}
 *        只是"按 id 操作的窄入口"，权限点仍是 {@code stk:take:add} / {@code stk:take:edit}； </li>
 *   <li> {@link #approveStocktake(String)}：正差异行合并生成<b>盘盈入库单</b>、负差异行合并生成
 *        <b>盘亏出库单</b>，生成单状态直接"已审核"、金额 0、数量为差异绝对值，并回填生成单号；
 *        盘亏单过账<b>显式豁免负库存校验</b>（{@code ErpPostingOptions.skipNegativeCheck()}）；
 *        无差异不生成任何单据； </li>
 *   <li> {@link #unapproveStocktake(String, String)}：先红冲并作废其生成单，再让盘点单回到待审核
 *        （生成单已作废时幂等跳过；重复反审核不报错，见实现注释）； </li>
 *   <li> 盘点单自身<b>不写结存</b>，{@code posted} 表示"其生成单已过账"。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface IErpStocktakeService
{
    /**
     * 盘点单列表（关键字 / 状态 / 仓库 / 盘点范围 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件（可空）
     * @return 单据集合
     */
    List<ErpStocktake> selectStocktakeList(ErpStocktake query);

    /**
     * 盘点单详情（含行项；范围外抛 403）。
     *
     * @param id 单据ID
     * @return 单据
     */
    ErpStocktake selectStocktakeDetail(String id);

    /**
     * 新建盘点单（草稿）并<b>生成行项</b>：全盘取该仓库结存非零物料；
     * 抽盘取指定物料或指定商品类型（含整棵子树）在该仓库有结存的物料。
     * 账面数量在此时固化，实盘数量默认等于账面（避免"未录入即全亏"）。
     *
     * @param doc 单据（{@code items} 可空；抽盘可用 {@code productTypeIds}）
     * @return 落库后的单据（含生成的行项）
     */
    ErpStocktake insertStocktake(ErpStocktake doc);

    /**
     * 编辑盘点单（仅草稿）：录入实盘数量（账面只读、由服务端从已生成行项取）。
     *
     * @param doc 单据（{@code items} 携带实盘数量；不传的字段保持库内值）
     * @return 落库后的单据
     */
    ErpStocktake updateStocktake(ErpStocktake doc);

    /**
     * 按 id 重新生成行项（窄入口，权限点仍为 {@code stk:take:add}）。
     *
     * @param id       单据ID
     * @param criteria 生成参数（{@code takeType} / {@code productTypeIds} / {@code items}；可空）
     * @return 落库后的单据（含新生成的行项）
     */
    ErpStocktake generateItems(String id, ErpStocktake criteria);

    /**
     * 按 id 录入实盘数量（窄入口，权限点仍为 {@code stk:take:edit}）。
     *
     * @param id    单据ID
     * @param items 行项（按 id 或 productId 匹配已生成行项；只覆盖实盘与差异原因）
     * @return 落库后的单据
     */
    ErpStocktake saveCount(String id, List<ErpStocktakeItem> items);

    /**
     * 删除盘点单（仅草稿；物理删除，行项随外键级联）。
     *
     * @param ids 单据ID数组
     * @return 删除条数
     */
    int deleteStocktakeByIds(String[] ids);

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 落库后的单据
     */
    ErpStocktake submitStocktake(String id);

    /**
     * 审核（待审核 → 已审核）：按差异生成盘盈/盘亏单并过账（同一事务）。
     *
     * @param id 单据ID
     * @return 落库后的单据（含回填的生成单号）
     */
    ErpStocktake approveStocktake(String id);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 落库后的单据
     */
    ErpStocktake rejectStocktake(String id, String reason);

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 落库后的单据
     */
    ErpStocktake voidStocktake(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填）：级联红冲并作废其生成的盘盈/盘亏单。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 落库后的单据
     */
    ErpStocktake unapproveStocktake(String id, String reason);

    /**
     * 某盘点单的变更历史（按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 日志集合
     */
    List<CtmsChangeLog> selectChangeLogList(String id);
}
