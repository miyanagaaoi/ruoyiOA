package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsContract;

/**
 * <p> 合同台账主体的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsContractMapper.xml}。 </p>
 *
 * <p> 本包由全局 {@code @MapperScan("com.ruoyi.**.mapper")} 自动注册；XML 文件名必须以
 * {@code Mapper.xml} 结尾，否则扫描不到（症状是 {@code Invalid bound statement (not found)}，
 * 见同目录 README）。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> {@link #selectContractList} 承担「多维筛选 + 标签交集 + 默认排除已停用」三件事，
 *        默认（{@code includeDeleted != "1"}）只返回未停用合同； </li>
 *   <li> 数据范围由服务层拼成 SQL 片段放在 {@code query.dataScopeSql}，本层用原样拼接执行
 *        （<b>绝不能</b>把请求参数直接塞进该字段，理由写在 XML 注释里）； </li>
 *   <li> {@code contractNo} 全局唯一（停用占号不复用），所以 {@link #selectContractByNo}
 *        <b>不排除</b>已停用行，查重口径由调用方决定； </li>
 *   <li> 软删除与恢复各有专用方法（{@link #updateContractDelFlag} / {@link #restoreContract}），
 *        因此 {@link #updateContract} 不覆盖 {@code del_flag} / {@code deleted_at} /
 *        {@code deleted_reason}，也不改 {@code contract_no}； </li>
 *   <li> {@link #deleteContractById} 是物理删除，仅供回滚演练与夹具清理，业务侧一律走软删除。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface CtmsContractMapper
{
    /**
     * 合同列表（关键字、类型、进度状态、到货状态、框架标志、经办人、客户/供应商、父合同、
     * 签订日期区间、标签交集、包含停用开关、数据范围片段；全部为「传了才过滤」）。
     *
     * @param query 查询条件
     * @return 合同集合（按创建时间倒序）
     */
    List<CtmsContract> selectContractList(CtmsContract query);

    /**
     * 按主键查合同（含已停用行，是否放行由服务层的数据范围与业务规则判定）
     *
     * @param id 主键
     * @return 合同；不存在返回 null
     */
    CtmsContract selectContractById(@Param("id") String id);

    /**
     * 按合同编号查合同（查重与占用判定用，<b>不排除</b>已停用行：停用占号不复用）
     *
     * @param contractNo 合同编号
     * @return 合同；不存在返回 null
     */
    CtmsContract selectContractByNo(@Param("contractNo") String contractNo);

    /**
     * 新增合同（id 由服务层生成；{@code del_flag} 等列走库默认值，不在此写入）
     *
     * @param contract 合同
     * @return 影响行数
     */
    int insertContract(CtmsContract contract);

    /**
     * 修改合同（不修改 {@code contract_no} / {@code del_flag} / {@code deleted_at} /
     * {@code deleted_reason}，其余业务列全量覆盖）
     *
     * @param contract 合同
     * @return 影响行数
     */
    int updateContract(CtmsContract contract);

    /**
     * 软删除合同（写 {@code del_flag} / {@code deleted_at} / {@code deleted_reason} 与审计列）
     *
     * <p> 停用原因是接口层必填校验，本层只管写库。 </p>
     *
     * @param contract 合同（须含 id、delFlag、deletedAt、deletedReason 与审计列）
     * @return 影响行数
     */
    int updateContractDelFlag(CtmsContract contract);

    /**
     * 恢复合同（清空停用标记、停用时间与停用原因，写审计列）
     *
     * <p> 30 天窗口的判定在服务层（见
     * {@code com.ruoyi.ctms.support.ContractRules#isWithinRestoreWindow}），本层不做校验。 </p>
     *
     * @param id 主键
     * @param updateId 更新人用户ID
     * @param updateBy 更新人登录名快照
     * @return 影响行数
     */
    int restoreContract(@Param("id") String id, @Param("updateId") String updateId,
                        @Param("updateBy") String updateBy);

    /**
     * 按主键物理删除合同（仅回滚演练 / 夹具清理用，业务侧不使用）
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteContractById(@Param("id") String id);

    /**
     * 查框架合同下的子合同（只取未停用，按创建时间倒序）
     *
     * @param parentId 父框架合同ID
     * @return 子合同集合
     */
    List<CtmsContract> selectChildren(@Param("parentId") String parentId);

    /**
     * 统计框架合同下未停用的子合同数量（取消框架标记前的守卫）
     *
     * @param parentId 父框架合同ID
     * @return 子合同数量
     */
    int countActiveChildren(@Param("parentId") String parentId);

    /**
     * 统计引用了该标签的未停用合同数量（删除标签前的守卫）
     *
     * @param tagId 标签ID
     * @return 合同数量
     */
    int countContractReferences(@Param("tagId") String tagId);

    /**
     * <p> 质保提醒的候选合同（2.0 B3 任务 5.6）。 </p>
     *
     * <p> 入参条件全部由服务层给出（<b>不使用</b>合同列表那一套筛选），返回的固定是
     * 「启用质保 + 未释放 + 到期日非空 + 未停用 + 到期日 ≤ {@code windowEnd}」的合同，
     * 按 {@code warranty_end} 升序、最多 {@code limit} 条。 </p>
     *
     * <p> 「即将到期」与「已到期」的<b>切分</b>刻意留在服务层（{@link com.ruoyi.ctms.support.ContractRules} 的
     * 日期口径），SQL 只负责"窗口内的候选集"这一件事：这样两个列表的边界口径只有一处实现，
     * 不会出现"SQL 里一套、Java 里一套"的分叉。 </p>
     *
     * @param windowEnd  窗口右端点（今天 + 窗口天数，含）
     * @param dataScopeSql 数据范围片段（服务端白名单拼出，原样拼接）
     * @param limit      最大返回条数
     * @return 候选合同（按到期日升序，已排除停用/未启用质保/已释放/到期日为空）
     */
    List<CtmsContract> selectWarrantyReminderCandidates(@Param("windowEnd") java.util.Date windowEnd,
                                                        @Param("dataScopeSql") String dataScopeSql,
                                                        @Param("limit") int limit);

    /**
     * <p> 全部已占用的合同编号（自动编号的"停用占号不复用"扫描用，2.0 B3 任务 5.8）。 </p>
     *
     * <p> <b>刻意不过滤 {@code del_flag}</b>：停用合同仍然占号，编号唯一索引也不排除停用行。 </p>
     *
     * @return 编号集合（可能为空，非 null）
     */
    List<String> selectAllContractNos();

    /* ==================== 迁移认领的批量绑定（任务 7.3） ==================== */

    /**
     * <p> 查「某方向档案引用为空、且该方向文本等于给定原始名称」的历史合同（迁移认领的绑定候选集合）。 </p>
     *
     * <p> 口径与扫描一致：客户方向看 {@code customer_id}/{@code party_a}，供应商方向看
     * {@code supplier_id}/{@code party_b}；文本比较走列的排序规则（{@code utf8mb4_0900_ai_ci}，
     * 大小写/重音不敏感），与草案幂等键同口径。<b>不过滤 {@code del_flag}</b>：停用合同同样是历史数据
     * （理由见 {@code CtmsMigrationServiceImpl} 的类注释）。 </p>
     *
     * <p> ⚠ <b>两侧都去首尾 ASCII 空格</b>（{@code trim(c.party_a/party_b) = trim(#{rawName})}）：
     * 扫描写进 {@code raw_name} 的是已去首尾空格的文本，候选比较必须同口径，否则"库内文本带空格"的合同
     * 会出现「扫描聚合成草案、认领实绑 0、再扫描又回 pending」的永久往复（t26 / t16 F1）。
     * 同口径表见 {@code MigrationRules.normalizeRawName}，**改任一侧都要同步改另一侧**。 </p>
     *
     * <p> 已绑定别的档案、或文本与原始名称不同的合同<b>不会</b>出现在结果里 —— 这是"批量绑定不覆盖
     * 已绑定引用"的落点。 </p>
     *
     * @param partyType 档案方向（{@code customer} / {@code supplier}，只接受这两个值）
     * @param rawName   草案原始文本
     * @return 合同集合（按创建时间升序，便于变更历史按同一顺序写入）
     */
    List<CtmsContract> selectUnboundPartyCandidates(@Param("partyType") String partyType,
                                                    @Param("rawName") String rawName);

    /**
     * <p> 按方向把某份合同的档案引用写成给定档案ID（迁移认领的批量回填，<b>只改引用列</b>）。 </p>
     *
     * <p> 刻意<b>不</b>改 {@code party_a}/{@code party_b} 文本：文本本来就等于草案的 {@code raw_name}，
     * 认领只是把"文本"升级为"档案引用"（规格：未认领不阻塞展示，认领不强制补齐文本）。 </p>
     *
     * @param id        合同ID
     * @param partyType 档案方向（只接受 {@code customer} / {@code supplier}）
     * @param partyId   要写入的档案ID
     * @param updateId  更新人用户ID
     * @param updateBy  更新人登录名快照
     * @return 影响行数
     */
    int bindPartyRef(@Param("id") String id, @Param("partyType") String partyType,
                     @Param("partyId") String partyId, @Param("updateId") String updateId,
                     @Param("updateBy") String updateBy);
}
