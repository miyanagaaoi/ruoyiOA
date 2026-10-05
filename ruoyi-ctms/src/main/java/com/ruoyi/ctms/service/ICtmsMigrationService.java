package com.ruoyi.ctms.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsPartyDraft;
import com.ruoyi.ctms.domain.vo.CtmsMigrationClaimVo;

/**
 * <p> 历史甲乙方迁移服务（2.0 B3 任务 7.x）；规格真源
 * {@code specs/ctms/contract-migration}。 </p>
 *
 * <p> 交付边界（逐条对应任务号，便于按任务号验收）： </p>
 * <ul>
 *   <li> <b>7.1</b>：{@link #scanPartyDrafts()} 扫描 + {@link #selectDraftList(CtmsPartyDraft)} 草案读取； </li>
 *   <li> <b>7.2</b>：重复扫描的幂等与刷新（pending 刷计数 / claimed 回到待认领并清空 {@code matched_id} /
 *        ignored 不复活）、以及认领状态机入口 {@link #requireClaimable(String)}； </li>
 *   <li> 7.3：认领（新建档案后绑定 / 绑定已有档案）与按原始名称的批量回填。 </li>
 * </ul>
 *
 * <p> <b>为什么扫描不做数据范围裁剪</b>：扫描是运维/迁移动作，产物是「文本 → 档案」的<b>聚合</b>，
 * 不含任何可识别的合同明细；而唯一键、计数与后续批量回填都必须按"全库同一口径"计算 ——
 * 若按当前登录用户的数据范围裁剪，两个管理员点同一次扫描会得到不同的 {@code contract_count}，
 * 认领时的回填范围也会随"谁点的"而变，迁移结果将不可复现。
 * 这正是扫描要独立权限点（{@code ctms:migration:scan}）而不是复用合同列表权限的原因。 </p>
 *
 * @author 二开
 */
public interface ICtmsMigrationService
{
    /**
     * <p> 扫描历史合同的甲乙方文本，生成/刷新待认领草案（任务 7.1）。 </p>
     *
     * <p> 口径：按「合同类型 → 档案方向」映射（{@code MigrationRules.directionOf}）决定扫描哪一侧，
     * 只取「该方向档案引用为空 + 该方向文本非空」的合同，再按「档案方向 + 原始名称」聚合为一条草案
     * （{@code contract_count} = 命中的合同数，{@code status = pending}）。不参与映射的类型直接跳过。 </p>
     *
     * <p> <b>全程只读合同表</b>：不修改 {@code party_a}/{@code party_b}/{@code customer_id}/{@code supplier_id}
     * 中的任何一列（规格场景「扫描不改动合同」）。 </p>
     *
     * <p> <b>幂等</b>：同一「方向 + 原始文本」已存在草案时只刷新，不新建第二行
     * （唯一索引 {@code uk_party_draft} 是数据库层的兜底；正常路径是"先查后更新"，
     * 实现里没有捕获 1062 / {@code DuplicateKeyException} 的分支）。 </p>
     *
     * <p> <b>状态分档</b>（任务 7.2）：{@code pending} 只刷计数（计数没变则不写库）；
     * {@code claimed} 在仍有未绑定合同用同一文本时<b>回到 {@code pending} 并清空 {@code matched_id}</b>；
     * {@code ignored} <b>整行不动</b>（不复活、也不刷计数）；其它状态抛业务异常快速失败。 </p>
     *
     * @return 本次实际<b>新建或刷新</b>的草案集合（数据无变化时第二次扫描返回空集合；
     *         已忽略草案永远不会出现在返回值里）
     */
    List<CtmsPartyDraft> scanPartyDrafts();

    /**
     * 查询草案列表（方向 / 原始文本（模糊）/ 状态 / 已匹配档案，传了才过滤）。
     *
     * <p> {@code status} 传 {@code all}（或空串）表示全部状态，归一动作在服务层完成
     * （理由见实现类，涉及 MyBatis 的 OGNL 单引号字面量坑）。 </p>
     *
     * @param query 查询条件，可为 null
     * @return 草案集合（按创建时间倒序）
     */
    List<CtmsPartyDraft> selectDraftList(CtmsPartyDraft query);

    /**
     * <p> 认领状态机入口（任务 7.2）：按主键取草案并判定"是否可认领"。 </p>
     *
     * <p> 契约：草案不存在抛「草案不存在」；{@code claimed} 抛「该草案已认领，不能重复认领」
     * （文案含关键字<b>已认领</b>，是 7.3 的验收契约）；{@code pending}/{@code ignored} 放行并返回草案
     * （忽略是暂缓，不是永久否决）；其它状态抛「草案状态不合法」。 </p>
     *
     * <p> 7.3 的两条写入路径（新建档案后绑定 / 绑定已有档案）都必须先经过这里，
     * 不允许各自再写一份状态判定。 </p>
     *
     * @param draftId 草案主键
     * @return 可认领的草案（调用方可继续做批量绑定）
     */
    CtmsPartyDraft requireClaimable(String draftId);

    /**
     * <p> 认领一条草案并<b>按原始名称批量绑定</b>历史合同（任务 7.3）。 </p>
     *
     * <p> 两种认领方式（由 {@code request} 决定）：{@code request.partyId} 非空 = 绑定已有档案；
     * 否则 = 先按 {@code request} 的字段<b>新建档案</b>（复用 {@code ICtmsPartnerService} 与
     * {@code PartnerRules}，不另写一套档案写入）再绑定。 </p>
     *
     * <p> 绑定范围口径：只更新「该方向档案引用为空<b>且</b>该方向文本等于草案 {@code raw_name}」的合同
     * （不过滤 {@code del_flag}），<b>不动文本</b>、<b>不覆盖已绑定别的档案的合同</b>；
     * 每份被更新的合同写一条字段名为「客户档案」/「供应商档案」、来源 {@code auto}、
     * 含操作人的变更历史（字段值口径与整单编辑一致：值是档案ID，档案名称放在备注里便于人读）。 </p>
     *
     * <p> 状态机：{@code pending}/{@code ignored} 可认领（忽略是暂缓不是否决），
     * {@code claimed} 拒绝且提示含「已认领」；成功后草案 → {@code claimed}、写入 {@code matched_id}、
     * 并把 {@code contract_count} 刷新为本次实际绑定的合同数。 </p>
     *
     * <p> ⚠ 两个迁移期兜底：档案名称为空 → 用草案 {@code raw_name}；供应商简称为空 → 也用
     * {@code raw_name}（供应商简称在规格里必填，不允许因为"简称没填"卡住迁移）。档案<b>编码不兜底</b>
     * （编码是全局唯一键与第 3 组的既定口径，自动生成会引入第二套编码规则）。 </p>
     *
     * @param draftId 草案主键
     * @param request 认领请求（可为 null → 等价于"什么都不填的新建请求"，会因缺编码被拒）
     * @return 认领后的草案（{@code claimed} + {@code matched_id} + 刷新后的计数）
     */
    CtmsPartyDraft claimPartyDraft(String draftId, CtmsMigrationClaimVo request);

    /**
     * 忽略一条草案（任务 7.3 的状态机补全：规格要求"已忽略不被自动复活"，忽略动作本身由本入口提供）。
     *
     * <p> 口径：{@code pending} 可忽略、{@code ignored} 可重复忽略（幂等）；
     * {@code claimed} 拒绝（已认领的草案已经把引用写进合同，再"忽略"会让状态与事实矛盾）。 </p>
     *
     * @param draftId 草案主键
     * @param remark  忽略原因（写入草案 {@code remark}，可空）
     * @return 忽略后的草案
     */
    CtmsPartyDraft ignorePartyDraft(String draftId, String remark);
}
