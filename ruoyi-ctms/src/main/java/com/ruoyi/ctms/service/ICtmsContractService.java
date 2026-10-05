package com.ruoyi.ctms.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsContractItem;
import com.ruoyi.ctms.domain.vo.CtmsWarrantyReminderVo;

/**
 * <p> 合同台账主体服务（2.0 B3 任务 4.1~4.8）；规格真源
 * {@code specs/ctms/contract-ledger}、金额/质保口径见 {@code specs/ctms/contract-commercials}。 </p>
 *
 * <p> <b>口径集中在实现类，控制器只做参数透传</b>：编号唯一、状态字典校验、档案引用与文本兜底、
 * 行项与金额（先舍入再汇总）、标的物摘要落库、质保到期算法与开关清空、自动标签同步、
 * 框架绑定五条守卫、软删除与 30 天恢复、字段级变更历史、数据范围（含范围外 403），
 * 全部在 {@code CtmsContractServiceImpl} 里完成。 </p>
 *
 * <p> <b>数据范围是本接口所有"按标识取数"方法的隐含前置条件</b>：详情、编辑、删除、恢复、
 * 质保释放、框架详情都必须先做范围判定，范围外一律抛业务码 403（见实现类
 * {@code canAccess} 的说明）。 </p>
 *
 * @author 二开
 */
public interface ICtmsContractService
{
    /**
     * 查询合同列表（关键字 / 类型 / 进度状态 / 到货状态 / 是否框架 / 经办人 /
     * 签订日期区间 / 标签交集 / 是否含停用全部由 Mapper 完成）。
     *
     * <p> 服务层只做一件事：把当前用户的数据范围片段写进 {@code query.dataScopeSql}
     * （{@code includeDeleted != '1'} 时默认排除停用也由 Mapper 完成）。 </p>
     *
     * @param query 查询条件，可为 null
     * @return 合同集合
     */
    List<CtmsContract> selectContractList(CtmsContract query);

    /**
     * <p> 只做「对象存在 + 数据范围」校验，不返回任何业务数据（2.0 B3 任务 6.1）。 </p>
     *
     * <p> 用途是给<b>挂在合同上的下游资源</b>（附件）复用同一处判定：附件的数据范围
     * 就是它所属合同的数据范围，所以附件服务不允许自己再写一份范围判定
     * （{@code ContractDataScope} + 实现类的 {@code canAccess} 是唯一入口）。 </p>
     *
     * <p> 契约：合同不存在抛「合同不存在」；范围外抛业务码 403「无权访问该合同」；
     * 通过则静默返回（<b>刻意不返回合同对象</b>，避免调用方顺手把合同数据外泄）。 </p>
     *
     * @param id 合同ID
     */
    void checkContractAccess(String id);

    /**
     * 查询合同详情：合同 + 标签 + 行项 + 变更历史（最近 200 条）+ 只读关联单据。
     *
     * <p> <b>纯只读</b>：读取过程不回写合同的任何字段（规格明确要求）。 </p>
     *
     * @param id 合同ID
     * @return 详情对象（含 {@code relatedDocs}；B3 固定空列表）
     */
    CtmsContract selectContractDetail(String id);

    /**
     * 查询某合同的字段级变更历史（分页由控制器的 {@code startPage()} 完成）。
     *
     * <p> 与详情一样先做数据范围校验：范围外同样抛业务码 403。 </p>
     *
     * @param query 查询条件（只读 {@code contractId}）
     * @return 变更历史（按时间倒序，由 Mapper 保证）
     */
    List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query);

    /**
     * 登记合同：编号必填且唯一、名称/类型必填、状态与到货状态必须命中字典、
     * 档案引用校验与文本兜底、行项与金额汇总、标的物摘要落库、质保计算、自动标签同步。
     *
     * @param contract 合同（{@code items}/{@code tagIds} 为可选的子集合）
     */
    void insertContract(CtmsContract contract);

    /**
     * 编辑合同：已停用禁止编辑；其余校验同登记；逐字段比对写变更历史
     * （金额/标的物摘要/质保到期等自动字段的来源为 {@code auto}）。
     *
     * @param contract 合同（{@code items}/{@code tagIds} 为 null 表示"本次不提交该子集合"）
     */
    void updateContract(CtmsContract contract);

    /**
     * 进度状态 / 到货状态自由流转（允许任意状态互转、不做顺序守卫，
     * 两个状态字段之间无联动：只改一个字段就只写一个字段）。
     *
     * <p> 传到货状态时可一并更新 {@code expectedArrivalDate}。 </p>
     *
     * <p> 改为「已终止」时必须填写终止原因（由入参的 {@code deletedReason} 字段承载，
     * 该字段名仅为此用途复用，与软删除无关；详见实现类的 Javadoc）。 </p>
     *
     * @param contract 只读 {@code id}，可选 {@code status}/{@code arrivalStatus}/
     *                 {@code expectedArrivalDate}/{@code deletedReason}
     * @return 影响行数
     */
    int changeStatus(CtmsContract contract);

    /**
     * 软删除（停用）：原因必填；写停用标记/时间/原因并记一条 {@code deleted} 变更历史；
     * 已停用时幂等成功（不重复写历史）。
     *
     * @param id     合同ID
     * @param reason 停用原因
     */
    void softDelete(String id, String reason);

    /**
     * 恢复（30 天窗口内）：整数日差 ≤ 30 可恢复；超过则拒绝；
     * 未停用时幂等成功（由控制器返回「该合同未停用」提示）。
     *
     * @param id 合同ID
     */
    void restore(String id);

    /**
     * 释放质保：必须已启用质保；置 {@code warrantyReleased='1'} 与释放日期为当天并留痕。
     *
     * @param id 合同ID
     * @return 影响行数
     */
    int releaseWarranty(String id);

    /**
     * 框架详情：合同 + 子合同清单 + 子合同数量 + 子合同金额合计。
     *
     * <p> 子合同金额合计与框架自身 {@code amount} 分开呈现、不要求相等。 </p>
     *
     * @param id 框架合同ID
     * @return 框架合同（{@code children}/{@code childrenCount}/{@code childrenAmountSum} 已填充）
     */
    CtmsContract selectFrameworkDetail(String id);

    /**
     * 物理删除合同（含行项与标签关联）。
     *
     * <p> <b>仅供回滚演练与测试夹具使用，控制器不暴露该入口</b>
     * （对用户的"删除"是 {@link #softDelete(String, String)}）。 </p>
     *
     * @param id 合同ID
     */
    void deleteContract(String id);

    /**
     * <p> 合同编号预览（2.0 B3 任务 5.8）：<b>只算不占号</b>，连续两次调用返回同一编号。 </p>
     *
     * <p> 校验与登记路径一致：类型必须是启用且可映射为 3 位大写码的类型（否则
     * 「该类型暂不支持自动编号」），主体码必须在 {@code subjects} 字典内（否则「请选择有效主体」）。 </p>
     *
     * @param type         合同类型（类型码，如 {@code SAL}）
     * @param subjectCode  我方主体码（如 {@code ZC}）
     * @param referenceDay 参考日期（{@code yyyy-MM-dd}；空则取当天）。年份/月份码取它，且<b>不</b>参与序号
     * @return 预览编号（形如 {@code SALZC202609000001}）
     */
    String previewContractNo(String type, String subjectCode, String referenceDay);

    /**
     * <p> 质保到期提醒（2.0 B3 任务 5.6）：即将到期与已到期两个列表。 </p>
     *
     * <p> 两条口径：{@code expiring} = 到期日 ∈ [今天, 今天 + 窗口天数]；
     * {@code expired} = 到期日 &lt; 今天。两个列表都只覆盖「启用质保 + 未释放 + 到期日非空 +
     * 未停用」的合同，各最多 100 条、按到期日升序，并同样受数据范围限制。 </p>
     *
     * <p> 窗口天数取 {@code sys_config.warranty_window_days}（缺省 30）——<b>每次调用都重新读</b>，
     * 因此改参数后行为随之变化。 </p>
     *
     * @return 提醒对象（含 {@code expiring} / {@code expired} / {@code windowDays} / {@code today}）
     */
    CtmsWarrantyReminderVo selectWarrantyReminders();

    /* ==================== 行项单行写（任务 7.4 追加的 9.1 权限点闭合项） ==================== */

    /**
     * <p> <b>行项单行写的窄接口</b>（接口隔离）：新增 / 编辑 / 删除一行行项。 </p>
     *
     * <p> <b>它为什么存在</b>（三点，缺一不可，后人不要把它"合并回主接口"）： </p>
     * <ol>
     *   <li> <b>接口隔离</b>：{@link ICtmsContractService} 的接口面已经很宽
     *        （列表/详情/登记/编辑/状态/软删除/质保/编号预览/提醒），而"单行行项写"是一个独立且很窄的能力；
     *        需要它的调用方（合同控制器）只依赖它，其余调用方（附件、档案、迁移等）完全看不到它。 </li>
     *   <li> <b>不让下游的测试桩为主接口的扩张买单</b>：在主接口上直接加这三个方法，会强迫<b>所有</b>
     *        历史测试桩（例如附件用例里的 {@code StubContractService}）跟着实现三个与它们毫无关系的方法
     *        —— 那等于"让调用方的测试反过来决定接口形状"；而且任务 7.4 的 inScope 并不包含那个测试文件，
     *        强行改它会把一次纯新增能力变成跨文件的破坏性改动。 </li>
     *   <li> <b>这是一条独立的写入口，不是"第二套行项写入"</b>：它与"整单编辑"
     *        （{@link ICtmsContractService#updateContract(CtmsContract)} 带 {@code items} 提交）
     *        <b>并存且分工明确</b>——整单编辑用于"表单一次性提交合同+全部行项"，
     *        本接口用于"详情页对某一行行项做增/改/删"；两者最终都走同一条落库链路
     *        （归一/校验/物料快照/序号重排/先舍入再汇总/{@code _items} 变更历史/金额与摘要的自动留痕），
     *        所以看到两个入口时<b>不要删掉其中一个</b>：删掉它就少了一种调用形态，
     *        而不是消除重复实现（实现只有一份，在 {@code CtmsContractServiceImpl} 内部）。 </li>
     * </ol>
     *
     * <p> 实现类是 {@code CtmsContractServiceImpl}（同一个 Spring bean 同时满足主接口与这个窄接口，
     * 因此 Spring 按类型注入两个接口都能拿到同一个 bean）。 </p>
     */
    interface ItemWriter
    {
        /**
         * <p> 新增一行行项（权限点 {@code ctms:contract-item:add}）。 </p>
         *
         * <p> <b>复用既有「行项全量替换」口径</b>：读出现有行项 → 追加这一行 → 交给
         * {@link ICtmsContractService#updateContract(CtmsContract)} 的同一套归一/校验/汇总/落库/变更历史，
         * 因此<b>不产生第二套行项写入</b>，金额（先舍入再汇总）与标的物摘要也按同一处规则重算。 </p>
         *
         * <p> 前置校验复用合同侧的单一数据范围入口：合同不存在抛「合同不存在」、范围外抛业务码 403、
         * 已停用抛「合同已停用，请先恢复」（与编辑合同一致）。 </p>
         *
         * <p> ⚠ <b>行项主键会在写入时重新生成</b>（全量替换口径的直接后果，与"编辑整单"时的行为一致）：
         * 响应体里返回的就是新行项，前端应当在每次增/改/删之后刷新行项列表，再按新 id 做后续操作。 </p>
         *
         * @param item 行项（{@code contractId} 必填；请求体里的 {@code id} 一律忽略）
         * @return 归一后的行项（含服务端生成的 id、序号与物料快照）
         */
        CtmsContractItem insertContractItem(CtmsContractItem item);

        /**
         * 编辑一行行项（权限点 {@code ctms:contract-item:edit}）。
         *
         * <p> 先按 {@code id} 反查行项（不存在抛「行项不存在」），再用它的 {@code contractId} 走合同侧的
         * 数据范围判定；提交的 {@code contractId} 与库内不一致时抛「行项不属于该合同」
         * （不允许把行项搬到别的合同上，否则等于绕过两份合同各自的数据范围）。
         * 其余口径与 {@link #insertContractItem(CtmsContractItem)} 完全一致。 </p>
         *
         * @param item 行项（{@code id} 必填，其余字段按"提交了什么就覆盖什么"的整单编辑口径处理）
         * @return 归一后的行项
         */
        CtmsContractItem updateContractItem(CtmsContractItem item);

        /**
         * 删除一行行项（权限点 {@code ctms:contract-item:remove}）。
         *
         * <p> 先按 {@code id} 反查行项（不存在抛「行项不存在」）→ 合同侧数据范围判定（范围外 403）→
         * 从行项集合里剔除该行 → 走同一套全量替换 + 金额/摘要重算 + 变更历史。 </p>
         *
         * @param itemId 行项主键
         */
        void deleteContractItem(String itemId);
    }
}
