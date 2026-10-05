package com.ruoyi.ctms.erp.posting.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOptions;
import com.ruoyi.ctms.erp.posting.support.ErpPostingOutcome;

/**
 * <p> <b>入库单服务</b>（2.0 B4 任务 5.1~5.6；design D1/D2/D10）。 </p>
 *
 * <p> 状态动作与过账的关系（本接口的核心约定）： </p>
 * <ul>
 *   <li> {@link #approveStockIn(String, ErpPostingOptions)}：<b>审核即过账</b>
 *        —— 状态流转（submitted → approved）与结存更新/流水追加在<b>同一事务</b>内；
 *        已过账时幂等短路（不产生流水、不改结存）； </li>
 *   <li> {@link #unapproveStockIn(String, String)}：<b>反审核即红冲</b>
 *        —— 追加负数流水、清除过账标记、单据回到待审核； </li>
 *   <li> {@link #postApprovedStockIn(String, ErpPostingOptions)} 与
 *        {@link #reversePostedStockIn(String, String)}：给"盘点审核自动生成盘盈单"
 *        （tasks.md §6.4，生成单状态直接为已审核）与其反审核级联用的窄接口，
 *        两者都幂等。 </li>
 * </ul>
 *
 * <p> 数据范围：列表由服务层写入 {@code dataScopeSql}（{@code ErpDocScope}），
 * 详情/编辑/删除/动作一律先过 {@code IErpDocObjectAccess}（存在性 + 四档范围，
 * 范围外返回业务码 403），因此不存在"列表看不到、按 id 直查能改"的旁路。 </p>
 *
 * @author 二开
 */
public interface IErpStockInService
{
    /**
     * 入库单列表（关键字 / 入库类型 / 仓库 / 状态 / 单据日期区间；默认排除已作废）。
     *
     * @param query 查询条件（可空）
     * @return 单据集合
     */
    List<ErpStockIn> selectStockInList(ErpStockIn query);

    /**
     * 入库单详情（含行项；范围外抛 403）。
     *
     * @param id 单据ID
     * @return 单据
     */
    ErpStockIn selectStockInDetail(String id);

    /**
     * 新建入库单（<b>强制草稿</b>；单号由平台编号服务生成；写创建人/归属部门快照与行项快照）。
     *
     * @param doc 单据（行项在 {@code items}）
     * @return 落库后的单据（含生成的 id 与单号）
     */
    ErpStockIn insertStockIn(ErpStockIn doc);

    /**
     * 新建<b>已审核</b>的入库单（仅供盘点审核生成盘盈单使用；状态直接 approved、不写提交痕迹、
     * 留痕来源为 {@code auto}）。过账由调用方随后用
     * {@link #postApprovedStockIn(String, ErpPostingOptions)} 触发。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpStockIn insertGeneratedStockIn(ErpStockIn doc);

    /**
     * 编辑入库单（仅草稿；行项全量替换、序号重排、金额合计重算）。
     *
     * @param doc 单据
     * @return 落库后的单据
     */
    ErpStockIn updateStockIn(ErpStockIn doc);

    /**
     * 删除入库单（仅草稿；物理删除，行项随外键级联删除）。
     *
     * @param ids 单据ID数组
     * @return 删除条数
     */
    int deleteStockInByIds(String[] ids);

    /**
     * 提交（草稿 → 待审核；至少一行行项）。
     *
     * @param id 单据ID
     * @return 落库后的单据
     */
    ErpStockIn submitStockIn(String id);

    /**
     * 审核（待审核 → 已审核）并在同一事务内过账。
     *
     * @param id      单据ID
     * @param options 过账选项（一般为默认；盘亏豁免等由对应单据类型使用）
     * @return 落库后的单据
     */
    ErpStockIn approveStockIn(String id, ErpPostingOptions options);

    /**
     * 驳回（待审核 → 草稿；原因必填）。
     *
     * @param id     单据ID
     * @param reason 驳回原因
     * @return 落库后的单据
     */
    ErpStockIn rejectStockIn(String id, String reason);

    /**
     * 作废（草稿或待审核 → 已作废；原因必填）。
     *
     * @param id     单据ID
     * @param reason 作废原因
     * @return 落库后的单据
     */
    ErpStockIn voidStockIn(String id, String reason);

    /**
     * 反审核（已审核 → 待审核；原因必填；已过账则红冲并清除过账标记）。
     *
     * @param id     单据ID
     * @param reason 反审核原因
     * @return 落库后的单据
     */
    ErpStockIn unapproveStockIn(String id, String reason);

    /**
     * 对"已审核但未过账"的单据执行过账（幂等：已过账返回空结果）。
     *
     * @param id      单据ID
     * @param options 过账选项
     * @return 过账结果
     */
    ErpPostingOutcome postApprovedStockIn(String id, ErpPostingOptions options);

    /**
     * 对"已过账"的单据执行红冲并清除过账标记（幂等：未过账返回空结果）。
     *
     * @param id     单据ID
     * @param reason 反审核原因（写入红冲流水备注）
     * @return 红冲结果
     */
    ErpPostingOutcome reversePostedStockIn(String id, String reason);

    /**
     * 某入库单的变更历史（按创建时间倒序）。
     *
     * @param id 单据ID
     * @return 日志集合
     */
    List<CtmsChangeLog> selectChangeLogList(String id);
}
