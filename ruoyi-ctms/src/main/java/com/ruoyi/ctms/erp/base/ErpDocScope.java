package com.ruoyi.ctms.erp.base;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

/**
 * <p> <b>库存域数据范围</b>（2.0 B4 任务 1.4；design D10、tasks.md §8.2 的机制部分、
 * AC-79、REQ-STK-006 的前置）。 </p>
 *
 * <p> <b>四档口径</b>（设计 D10 的"显式映射"，不套 {@code @DataScope} 的 AOP）： </p>
 * <table border="1">
 *   <caption>档位映射</caption>
 *   <tr><th>语义</th><th>{@code sys_role.data_scope}</th><th>条件</th></tr>
 *   <tr><td>本人</td><td>{@code 5}</td><td>{@code create_id = 本人}（"本人"按**创建人**而不是负责人）</td></tr>
 *   <tr><td>本部门</td><td>{@code 3}</td><td>{@code dept_id = 本部门}</td></tr>
 *   <tr><td>本部门及下级</td><td>{@code 4}（{@code 2} 自定义部门与此同口径）</td>
 *       <td>{@code dept_id = 本部门} 或 本部门在该行部门的 {@code ancestors} 里</td></tr>
 *   <tr><td>全部</td><td>{@code 1}（超管亦同）</td><td>不加条件</td></tr>
 * </table>
 *
 * <p> <b>为什么与 {@code ContractDataScope}（B3）分成两个类</b>：B3 把 {@code '3'/'2'} 也按
 * "含下级"处理（它需要在 4 档里挤出空间给"仅本人"），而 B4 的 tasks.md §8.2 明确要求
 * **四档各自成立**（本部门 ≠ 本部门及下级）。两套口径不同就不能共用一份实现 ——
 * 但**多角色并集**、**归属部门创建时快照**、**导出与列表同范围**三条是共同要求，
 * 两边的写法与注释保持同构，便于评审对照。 </p>
 *
 * <p> <b>多角色取并集</b>：把所有角色档位的条件 OR 起来（任一档位命中即可见）；
 * 只要有一个角色是"全部"，就不加条件。 </p>
 *
 * <p> <b>片段是白名单拼接</b>：拼进 SQL 的只有服务端上下文里的 userId / deptId
 * （{@code SecurityUtils}），并各自过 {@link #safeLiteral(String)} 的字符白名单
 * （数字/字母/下划线/连字符，长度 ≤64）。请求参数<b>永远不</b>进入片段。 </p>
 *
 * <p> <b>无登录上下文（{@code SecurityUtils} 抛异常）→ {@link #buildDataScopeSql()}
 * 返回 {@code null}</b>（不加条件）：单测、数据迁移脚本、定时任务都没有登录上下文；
 * 真实请求一律有登录上下文，因此这不是一个越权入口（与 B3 同款兜底并同样在
 * {@link #hasAllScope()} 上保持一致）。 </p>
 *
 * @author 二开
 */
public final class ErpDocScope
{
    /** 档位：全部数据。 */
    public static final String SCOPE_ALL = "1";

    /** 档位：自定义部门（B4 与"本部门及下级"同口径，不展开 {@code sys_role_dept}）。 */
    public static final String SCOPE_CUSTOM_DEPT = "2";

    /** 档位：本部门（**不含下级**）。 */
    public static final String SCOPE_DEPT = "3";

    /** 档位：本部门及下级。 */
    public static final String SCOPE_DEPT_AND_CHILD = "4";

    /** 档位：仅本人（按创建人）。 */
    public static final String SCOPE_SELF = "5";

    /** 默认表别名（单据/流水查询里的固定别名）。 */
    public static final String DEFAULT_ALIAS = "d";

    /**
     * 数据范围片段里 {@code sys_dept} 的内层别名。
     *
     * <p> 调用方的单据/流水别名<b>不得</b>取这个值（默认别名 {@code d} 与它不同）：
     * 同名会让 {@code where sd.dept_id = sd.dept_id} 退化成恒真子查询。 </p>
     */
    public static final String DEPT_INNER_ALIAS = "sd";

    /** 创建人列名（DDL 冻结）。 */
    public static final String COL_CREATE_ID = "create_id";

    /** 归属部门列名（DDL 冻结）。 */
    public static final String COL_DEPT_ID = "dept_id";

    /** 组织表名（复用平台部门树，物化路径列 {@code ancestors}）。 */
    private static final String DEPT_TABLE = "sys_dept";

    /** 白名单字面量最大长度（与 {@code varchar(64)} 的标识列对齐）。 */
    private static final int MAX_LITERAL_LENGTH = 64;

    private ErpDocScope()
    {
    }

    /**
     * 部门祖先链查询回调（{@code sys_dept.ancestors}，形如 {@code "0,100,101"}）。
     *
     * <p> 抽成回调是为了让范围判定能脱离 Spring/数据库单测：生产实现读 {@code sys_dept}，
     * 单测给一张内存表。 </p>
     */
    public interface DeptAncestorsLookup
    {
        /**
         * @param deptId 部门ID
         * @return 祖先链（逗号分隔）；查不到返回 null
         */
        String ancestorsOf(String deptId);
    }

    /**
     * 依据<b>当前登录用户</b>的角色档位生成 SQL 片段（命中即放行）。
     *
     * @return SQL 片段；无需加条件（含"全部"档）时返回 {@code null}
     */
    public static String buildDataScopeSql()
    {
        return buildDataScopeSql(DEFAULT_ALIAS);
    }

    /**
     * 依据当前登录用户生成带表别名的 SQL 片段。
     *
     * @param alias 表别名（如 {@code d} / {@code s}）；空白时回落 {@link #DEFAULT_ALIAS}
     * @return SQL 片段；无需加条件时返回 {@code null}
     */
    public static String buildDataScopeSql(String alias)
    {
        try
        {
            String userId = currentUserId();
            String deptId = currentDeptId();
            if (isAdmin(userId))
            {
                return null;
            }
            return buildDataScopeSql(currentDataScopes(), userId, deptId, alias);
        }
        catch (Exception e)
        {
            // 无登录上下文（单测/系统任务/迁移脚本）：不加条件，见类注释
            return null;
        }
    }

    /**
     * <p> 纯函数版本：把给定档位集合拼成 SQL 片段（可脱离 Spring 单测）。 </p>
     *
     * @param scopes 当前用户的角色档位集合（多角色取并集）
     * @param userId 当前用户ID（服务端取值）
     * @param deptId 当前用户部门ID（服务端取值）
     * @param alias  表别名
     * @return SQL 片段；含"全部"档时返回 {@code null}（不加条件）
     */
    public static String buildDataScopeSql(List<String> scopes, String userId, String deptId, String alias)
    {
        String table = isBlank(alias) ? DEFAULT_ALIAS : alias.trim();
        if (scopes == null || scopes.isEmpty())
        {
            // 一个档位都没有：并集为空 → 不可见任何行。
            // 刻意**不**返回 null（null 会被读成"全部可见"，那是越权）
            return "1=0";
        }
        List<String> routes = new ArrayList<>();
        for (String scope : scopes)
        {
            String normalized = normalizeScope(scope);
            if (SCOPE_ALL.equals(normalized))
            {
                return null;
            }
            String route = routeSql(normalized, userId, deptId, table);
            if (route != null)
            {
                routes.add(route);
            }
        }
        if (routes.isEmpty())
        {
            return "1=0";
        }
        return joinOr(routes);
    }

    /**
     * 当前用户是否具备"全部数据"（超管，或任一角色 {@code data_scope='1'}）。
     *
     * @return 具备返回 true
     */
    public static boolean hasAllScope()
    {
        try
        {
            String userId = currentUserId();
            if (isAdmin(userId))
            {
                return true;
            }
            return currentDataScopes().contains(SCOPE_ALL);
        }
        catch (Exception e)
        {
            // 与 buildDataScopeSql 的兜底一致：无登录上下文视为不受限
            return true;
        }
    }

    /**
     * 当前登录用户的角色档位集合（去重、保持角色顺序）。
     *
     * <p> 档位取自 {@code LoginUser.user.roles[*].dataScope}（登录时随 {@code sys_role} 缓存的
     * 那份快照），不需要额外查库，也不会把请求参数当档位。 </p>
     *
     * @return 档位集合；无登录上下文时为空集合
     */
    public static List<String> currentDataScopes()
    {
        Set<String> scopes = new LinkedHashSet<>();
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getUser() == null)
            {
                return new ArrayList<>(scopes);
            }
            List<SysRole> roles = loginUser.getUser().getRoles();
            if (roles == null)
            {
                return new ArrayList<>(scopes);
            }
            for (SysRole role : roles)
            {
                if (role == null)
                {
                    continue;
                }
                String scope = normalizeScope(role.getDataScope());
                if (scope != null)
                {
                    scopes.add(scope);
                }
            }
        }
        catch (Exception e)
        {
            return new ArrayList<>(scopes);
        }
        return new ArrayList<>(scopes);
    }

    /**
     * <p> SQL 片段的<b>等价 Java 判定</b>（"按标识直查"详情/附件/导出时用）。 </p>
     *
     * <p> 分支与 {@link #buildDataScopeSql(List, String, String, String)} 一一对应：
     * 改档位口径必须同时改这两个方法。 </p>
     *
     * @param scopes       当前用户档位集合（多角色并集）
     * @param userId       当前用户ID
     * @param userDeptId   当前用户部门ID
     * @param rowCreateId  目标行的 {@code create_id}
     * @param rowDeptId    目标行的 {@code dept_id}
     * @param lookup       部门祖先链查询（仅"含下级"判定需要，可为 null）
     * @return 命中即 true
     */
    public static boolean matches(List<String> scopes, String userId, String userDeptId,
                                  String rowCreateId, String rowDeptId, DeptAncestorsLookup lookup)
    {
        if (scopes == null || scopes.isEmpty())
        {
            return false;
        }
        for (String scope : scopes)
        {
            String normalized = normalizeScope(scope);
            if (SCOPE_ALL.equals(normalized))
            {
                return true;
            }
            if (SCOPE_SELF.equals(normalized))
            {
                if (same(userId, rowCreateId))
                {
                    return true;
                }
                continue;
            }
            if (SCOPE_DEPT.equals(normalized))
            {
                if (same(userDeptId, rowDeptId))
                {
                    return true;
                }
                continue;
            }
            if (SCOPE_DEPT_AND_CHILD.equals(normalized) || SCOPE_CUSTOM_DEPT.equals(normalized))
            {
                if (same(userDeptId, rowDeptId) || isDeptInSubtree(rowDeptId, userDeptId, lookup))
                {
                    return true;
                }
                continue;
            }
            // 未知档位：该路不产生可见行（与 routeSql 返回 null 一致）
        }
        return false;
    }

    /* ==================== 服务端上下文取值 ==================== */

    /**
     * 当前用户ID（服务端取值；无登录上下文返回 null，不回落到伪造值）。
     *
     * @return 用户ID
     */
    public static String currentUserId()
    {
        try
        {
            return trimToNull(SecurityUtils.getUserId());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 当前用户部门ID（服务端取值；无登录上下文返回 null）。
     *
     * @return 部门ID
     */
    public static String currentDeptId()
    {
        try
        {
            return trimToNull(SecurityUtils.getDeptId());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 当前登录名（写入 {@code create_by} 快照）。
     *
     * @return 登录名；取不到返回 {@code system}
     */
    public static String currentUsername()
    {
        try
        {
            String username = trimToNull(SecurityUtils.getUsername());
            return username == null ? "system" : username;
        }
        catch (Exception e)
        {
            return "system";
        }
    }

    /**
     * 当前用户姓名（写入经办人/操作人姓名快照；取不到返回 null，**不伪造**）。
     *
     * @return 姓名；无登录上下文返回 null
     */
    public static String currentNickName()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getUser() == null)
            {
                return null;
            }
            return trimToNull(loginUser.getUser().getNickName());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * <p> 从登录上下文取创建人/归属部门并写入表头快照（**创建时唯一一次**）。 </p>
     *
     * <p> 归属部门按创建人当时的部门快照；之后编辑/流转都不得重算（design D10 的实现注意：
     * 人员调岗不回溯历史单据的可见范围）。因此本方法只在 insert 路径调用。 </p>
     *
     * <p> 取不到创建人或部门时<b>直接报错</b>而不是写空值：DDL 把两列都约束为
     * NOT NULL + 外键（F-3），静默写空只会在 insert 时变成一个难懂的约束错误。 </p>
     *
     * @param header 单据表头
     * @throws ServiceException 无登录上下文 / 未分配部门 / 表头为空
     */
    public static void applyCreateSnapshot(ErpDocHeader header)
    {
        if (header == null)
        {
            throw new ServiceException("单据表头不能为空");
        }
        applyCreateSnapshot(header, currentUserId(), currentDeptId(), currentUsername());
    }

    /**
     * 创建快照的<b>纯函数版本</b>（可脱 Spring 单测）。
     *
     * @param header   单据表头
     * @param userId   创建人用户ID
     * @param deptId   归属部门ID
     * @param username 登录名
     * @throws ServiceException 取值缺失
     */
    public static void applyCreateSnapshot(ErpDocHeader header, String userId, String deptId, String username)
    {
        if (header == null)
        {
            throw new ServiceException("单据表头不能为空");
        }
        String uid = trimToNull(userId);
        if (uid == null)
        {
            throw new ServiceException("无法确定创建人：请先登录后再创建单据");
        }
        String did = trimToNull(deptId);
        if (did == null)
        {
            throw new ServiceException("当前账号未分配部门，无法创建单据（归属部门在创建时快照，数据范围判定依赖它）");
        }
        header.applyCreateSnapshot(uid, did, trimToNull(username));
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 单个档位的 SQL 片段（不含最外层括号）。
     *
     * @param scope  归一后的档位
     * @param userId 当前用户ID
     * @param deptId 当前用户部门ID
     * @param alias  表别名
     * @return 片段；该档位无可用取值时返回 null
     */
    private static String routeSql(String scope, String userId, String deptId, String alias)
    {
        if (scope == null)
        {
            return null;
        }
        String uid = safeLiteral(userId);
        String did = safeLiteral(deptId);
        if (SCOPE_ALL.equals(scope))
        {
            return "1=1";
        }
        if (SCOPE_SELF.equals(scope))
        {
            return uid == null ? null : alias + "." + COL_CREATE_ID + " = '" + uid + "'";
        }
        if (SCOPE_DEPT.equals(scope))
        {
            return did == null ? null : alias + "." + COL_DEPT_ID + " = '" + did + "'";
        }
        if (SCOPE_DEPT_AND_CHILD.equals(scope) || SCOPE_CUSTOM_DEPT.equals(scope))
        {
            if (did == null)
            {
                return null;
            }
            List<String> parts = new ArrayList<>();
            parts.add(alias + "." + COL_DEPT_ID + " = '" + did + "'");
            // 本部门在该行部门的祖先链里 → 该行属于本部门的下级。
            // ⚠ 内层子查询的 sys_dept 别名固定用 `sd`，与调用方传入的单据别名（默认 `d`）
            //   刻意区分开：若两者同名，`where d.dept_id = d.dept_id` 会变成恒真子查询
            //   （单列多行 → 报 1242），这是实打实踩过的一类"片段看着对、SQL 跑不过"。
            parts.add("find_in_set('" + did + "', (select " + DEPT_INNER_ALIAS + ".ancestors from "
                    + DEPT_TABLE + " " + DEPT_INNER_ALIAS
                    + " where " + DEPT_INNER_ALIAS + ".dept_id = " + alias + "." + COL_DEPT_ID + "))");
            return joinOr(parts);
        }
        return null;
    }

    private static String joinOr(List<String> parts)
    {
        if (parts == null || parts.isEmpty())
        {
            return null;
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < parts.size(); i++)
        {
            if (i > 0)
            {
                sql.append(" or ");
            }
            sql.append('(').append(parts.get(i)).append(')');
        }
        return sql.toString();
    }

    private static boolean isDeptInSubtree(String rowDeptId, String userDeptId, DeptAncestorsLookup lookup)
    {
        if (isBlank(rowDeptId) || isBlank(userDeptId))
        {
            return false;
        }
        if (rowDeptId.trim().equals(userDeptId.trim()))
        {
            return true;
        }
        if (lookup == null)
        {
            return false;
        }
        String ancestors = lookup.ancestorsOf(rowDeptId.trim());
        if (isBlank(ancestors))
        {
            return false;
        }
        String[] parts = ancestors.split(",");
        for (String part : parts)
        {
            if (userDeptId.trim().equals(part.trim()))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdmin(String userId)
    {
        try
        {
            return SecurityUtils.isAdmin(userId);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static String normalizeScope(String scope)
    {
        return isBlank(scope) ? null : scope.trim();
    }

    /**
     * SQL 字面量白名单：只放行数字/字母/下划线/连字符，且长度不超过 64。
     *
     * @param value 原始值
     * @return 可安全拼接的字面量；不合法返回 null
     */
    private static String safeLiteral(String value)
    {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() > MAX_LITERAL_LENGTH)
        {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++)
        {
            char ch = trimmed.charAt(i);
            boolean allowed = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z') || ch == '_' || ch == '-';
            if (!allowed)
            {
                return null;
            }
        }
        return trimmed;
    }

    private static boolean same(String left, String right)
    {
        if (isBlank(left) || isBlank(right))
        {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
