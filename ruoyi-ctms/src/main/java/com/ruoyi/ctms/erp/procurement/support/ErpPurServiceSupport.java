package com.ruoyi.ctms.erp.procurement.support;

import java.math.BigDecimal;
import java.util.Date;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>采购线服务层的公共基类</b>：登录上下文、数据范围片段、主数据守卫、金额合计的单一出口。 </p>
 *
 * <p> <b>为什么用"继承"而不是静态工具</b>：每一处都需要"当前时间 / 当前用户 / 当前部门"，
 * 而单测要求<b>不启动 Spring 且能固定时钟与用户</b>。做成 {@code protected} 方法后，
 * 测试子类覆盖它们即可精确断言（与 B3 的 {@code CtmsContractServiceImpl} 同款做法）。 </p>
 *
 * <p> <b>与 base 的分工</b>（简报 §3.8：base 由 backend-base 维护，本组只消费）： </p>
 * <ul>
 *   <li> 数据范围 → {@link ErpDocScope}（<b>唯一实现点</b>，四档语义与 B3 的
 *        {@code ContractDataScope} 刻意不同：B4 的"本部门"不含下级）；</li>
 *   <li> 行项主数据守卫与快照 → {@link ErpMasterGuards#applyItemSnapshot}；</li>
 *   <li> 本类不再自己拼 SQL 片段、不再自己判停用 —— 那样会与 base 漂移。</li>
 * </ul>
 *
 * @author 二开
 */
public abstract class ErpPurServiceSupport
{
    /**
     * 单据表在 Mapper XML 里的固定别名。
     *
     * <p> {@link ErpDocScope} 的片段默认用 {@code d}，本组的 XML 也一律用 {@code d}，
     * 这样片段可以直接用 {@code ErpDocScope.buildDataScopeSql()} 的默认别名，
     * 不必在每处调用点传 alias（少一个能写错的地方）。 </p>
     */
    public static final String DOC_ALIAS = "d";

    /** 无登录上下文时的用户ID兜底。 */
    protected static final String DEFAULT_USER_ID = com.ruoyi.ctms.erp.procurement.ErpPurRules.DEFAULT_USER_ID;

    /** 无登录上下文时的登录名兜底。 */
    protected static final String DEFAULT_USERNAME = com.ruoyi.ctms.erp.procurement.ErpPurRules.DEFAULT_USERNAME;

    /** 无登录上下文时的部门ID兜底。 */
    protected static final String DEFAULT_DEPT_ID = "0";

    /**
     * 当前时间（单测覆盖以固定时钟）。
     *
     * @return 当前时间
     */
    protected Date now()
    {
        return DateUtils.getNowDate();
    }

    /**
     * 当前用户ID（无登录上下文时兜底 {@code "1"}）。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        String userId = ErpDocScope.currentUserId();
        return userId == null ? DEFAULT_USER_ID : userId;
    }

    /**
     * 当前用户登录名（无登录上下文时兜底 {@code "system"}）。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        String username = ErpDocScope.currentUsername();
        return username == null ? DEFAULT_USERNAME : username;
    }

    /**
     * 当前用户部门ID（无登录上下文时兜底 {@code "0"}）。
     *
     * <p> 归属部门是<b>服务端列</b>：新增时取这里，编辑时保持库内原值 ——
     * 请求体里的 {@code deptId} 一律忽略（否则"把单据搬进自己部门"会变成一次普通编辑）。 </p>
     *
     * @return 部门ID
     */
    protected String currentDeptId()
    {
        String deptId = ErpDocScope.currentDeptId();
        return deptId == null ? DEFAULT_DEPT_ID : deptId;
    }

    /**
     * 数据范围 SQL 片段（唯一实现点是 {@link ErpDocScope}；{@code null} 表示不加条件）。
     *
     * @return 片段或 null
     */
    protected String dataScopeSql()
    {
        return ErpDocScope.buildDataScopeSql(DOC_ALIAS);
    }

    /**
     * 数据范围 Java 判定（"按标识直查"与列表片段同口径）。
     *
     * @param rowCreateId 行的创建人ID
     * @param rowDeptId   行的归属部门ID
     * @param lookup      部门祖先链查询（"本部门及下级"档需要；可为 null）
     * @return 可见返回 true
     */
    protected boolean matchesScope(String rowCreateId, String rowDeptId, ErpDocScope.DeptAncestorsLookup lookup)
    {
        return ErpDocScope.matches(ErpDocScope.currentDataScopes(), currentUserId(), currentDeptId(),
                rowCreateId, rowDeptId, lookup);
    }

    /**
     * 是否具备"全部数据"档（超管或任一角色 {@code data_scope='1'}）。
     *
     * @return 具备返回 true
     */
    protected boolean hasAllScope()
    {
        return ErpDocScope.hasAllScope();
    }

    /**
     * 金额合计（"先舍入再汇总"的唯一实现点在 {@code ErpAmounts}）。
     *
     * @param items 行项集合
     * @return 2 位合计
     */
    protected BigDecimal totalAmountOf(java.util.Collection<? extends ErpDocItem> items)
    {
        return com.ruoyi.ctms.erp.base.ErpAmounts.totalOf(items);
    }

    /**
     * 创建快照（创建人 / 归属部门 / 登录名；**只在 insert 路径调用**）。
     *
     * <p> 取不到创建人或部门时 base 会直接报错：DDL 把两列约束为 NOT NULL + 外键（F-3），
     * 静默写空只会在 insert 时变成一个难懂的约束错误。 </p>
     *
     * @param header 单据表头
     */
    protected void applyCreateSnapshot(ErpDocHeader header)
    {
        ErpDocScope.applyCreateSnapshot(header);
    }

    /**
     * 行项写入前的统一守卫 + 快照（物料必须存在且启用、单位必须启用、
     * 写编码/名称/规格/单位名/单位小数位、数量精度校验、行金额先舍入到 2 位）。
     *
     * @param lookup 主数据查询（生产实现 {@code ErpMasterLookupImpl}；单测给内存桩）
     * @param item   行项
     * @param rowNo  行号（1 起）
     * @return 写入的快照（便于调用方再写历史/日志）
     * @throws ServiceException 任一步校验失败
     */
    protected ErpMasterGuards.Snapshot applyItemSnapshot(ErpMasterGuards.MasterLookup lookup, ErpDocItem item,
                                                        int rowNo)
    {
        return ErpMasterGuards.applyItemSnapshot(lookup, item, rowNo);
    }

    /**
     * 抛业务异常（把 {@code ServiceException} 的构造集中一处，便于将来统一错误码）。
     *
     * @param message 文案
     * @return 异常（调用方 {@code throw}）
     */
    protected ServiceException reject(String message)
    {
        return new ServiceException(message);
    }
}
