package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsPartyDraft;

/**
 * <p> 历史甲乙方迁移草案（{@code t_ctms_party_draft}）的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsPartyDraftMapper.xml}。 </p>
 *
 * <p> 本包由全局 {@code @MapperScan("com.ruoyi.**.mapper")} 自动注册；XML 文件名必须以
 * {@code Mapper.xml} 结尾，否则扫描不到（症状是 {@code Invalid bound statement (not found)}，
 * 见同目录 README）。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> 幂等键是唯一索引 {@code uk_party_draft(party_type, raw_name)}，所以
 *        {@link #selectDraftByTypeAndName} 是"扫描时先查后写"的唯一入口 —— 扫描不允许
 *        靠捕获 1062 来兜底； </li>
 *   <li> {@link #updateDraft} <b>不更新</b> {@code party_type}/{@code raw_name}：
 *        这两列是幂等键，改它们等于换一条草案； </li>
 *   <li> 本表没有 {@code del_flag}，也不提供物理删除（草案靠 {@code status} 流转；
 *        回滚演练由 SQL 脚本按表名处理，不走业务接口）。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface CtmsPartyDraftMapper
{
    /**
     * 草案列表（方向 / 原始文本（模糊）/ 状态 / 已匹配档案 传了才过滤）。
     *
     * @param query 查询条件（{@code status} 为空表示全部状态）
     * @return 草案集合（按创建时间倒序）
     */
    List<CtmsPartyDraft> selectDraftList(CtmsPartyDraft query);

    /**
     * 按幂等键查草案（扫描"先查后写"用）。
     *
     * <p> ⚠ 比较走数据库的排序规则（{@code utf8mb4_0900_ai_ci}，大小写/重音不敏感），
     * 与唯一索引的等价语义一致；Java 侧的分组键同口径（{@code MigrationRules.rawNameKey}）。 </p>
     *
     * @param partyType 档案方向
     * @param rawName   原始文本
     * @return 草案；不存在返回 null
     */
    CtmsPartyDraft selectDraftByTypeAndName(@Param("partyType") String partyType,
                                            @Param("rawName") String rawName);

    /**
     * 按主键查草案（认领 / 忽略入口用，状态机守卫的前一步）。
     *
     * @param id 主键
     * @return 草案；不存在返回 null
     */
    CtmsPartyDraft selectDraftById(@Param("id") String id);

    /**
     * 新增草案（{@code id} 由服务层生成；创建/更新时间走 {@code sysdate()}）。
     *
     * @param draft 草案（须含 id / partyType / rawName / contractCount / status）
     * @return 影响行数
     */
    int insertDraft(CtmsPartyDraft draft);

    /**
     * 修改草案（<b>不含</b> {@code party_type}/{@code raw_name} 这两列幂等键；
     * 覆盖 {@code contract_count}/{@code status}/{@code matched_id}/{@code remark} 与审计列）。
     *
     * @param draft 草案（须含 id；{@code updateId}/{@code updateBy} 由服务层写）
     * @return 影响行数
     */
    int updateDraft(CtmsPartyDraft draft);
}
