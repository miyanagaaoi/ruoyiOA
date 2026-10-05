package com.ruoyi.ctms.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * <p> 合同台账的<b>数据范围</b>（2.0 B3 任务 4.8，对应 design.md D-4、
 * 规格 {@code specs/ctms/contract-ledger} 的「合同台账数据范围服务端强制」）。 </p>
 *
 * <p> <b>为什么不复用 {@code @DataScope}</b>：{@code @DataScope} 只覆盖系统模块的 5 处 Mapper，
 * 它拼的是 {@code sys_dept} 别名的固定片段，直接套到业务表上会同时产生"越权"与
 * "该看的看不到"；而且 SELF 在 RuoYi 里是 {@code user_id}、在参考侧是创建人，
 * DEPT 在 RuoYi 里是 {@code dept_id}、在参考侧是组织，两套语义不等价（D-4 已记录）。 </p>
 *
 * <p> <b>口径（命中即放行，多角色取并集）</b>： </p>
 * <ul>
 *   <li> 超管（{@code SecurityUtils.isAdmin}）或任一角色 {@code sys_role.data_scope='1'} → 不加条件； </li>
 *   <li> {@code '1'} 全部 → 该路恒真（{@code 1=1}）； </li>
 *   <li> {@code '3'} 本部门 → {@code c.dept_id = 本部门 or 本部门 in 该行部门的 ancestors}（<b>默认含下级</b>）； </li>
 *   <li> {@code '2'} 自定义部门 → <b>与 '3' 同口径</b>。B3 不展开 {@code sys_role_dept} 明细
 *        （角色-部门授权表在 B3 没有维护入口，展开会得到一份永远为空的清单，
 *        比"按本部门处理"更容易误导使用者）；B4/后续接入角色-部门授权后再拆档； </li>
 *   <li> {@code '4'} 仅本人 → {@code c.create_id = 本人 or 该行部门在本部门及其下级内}（D-4 明确的 SELF 口径）； </li>
 *   <li> {@code '5'} 仅本人（无部门）→ {@code c.create_id = 本人}。 </li>
 * </ul>
 *
 * <p> <b>片段是白名单拼接，不含任何用户输入</b>：拼进 SQL 的只有服务端上下文里的
 * {@code userId}/{@code deptId}（{@code SecurityUtils}），且这两个值都要先过
 * {@link #safeLiteral(String)} 的字符白名单（数字/字母/下划线/连字符，长度 ≤64），
 * 不通过的一律不进入 SQL。请求参数里的任何字段都<b>不会</b>出现在片段里。 </p>
 *
 * <p> <b>无登录上下文（{@code SecurityUtils} 抛异常）→ {@link #buildDataScopeSql()} 返回
 * {@code null}（不加条件）</b>：内存桩单测、数据迁移脚本、定时任务都没有登录上下文，
 * 不加条件才能让它们正常工作；真实请求一律有登录上下文，所以这不构成越权入口。
 * {@link #hasAllScope()} 在同样的兜底分支返回 {@code true}，与"不加条件"保持一致。 </p>
 *
 * <p> <b>列表与"按标识直查"必须同口径</b>：列表把片段写进
 * {@code CtmsContract.dataScopeSql} 交给 Mapper；详情/编辑/删除/恢复走
 * {@link #matches(List, String, String, String, String, DeptAncestorsLookup)} 的
 * <b>等价 Java 判定</b>（因为按标识取单行后已经没有 SQL 片段的落点）。
 * 两处的分支一一对应（'3'/'2' 与 '4' 的部门分支在 SQL 与 Java 里都归约为
 * 「本行部门落在本部门子树内」），<b>改档位口径时必须同时改这两个方法</b>。 </p>
 *
 * @author 二开
 */
public final class ContractDataScope
{
    /** 档位：全部数据。 */
    public static final String SCOPE_ALL = "1";

    /** 档位：自定义部门（B3 与 {@link #SCOPE_DEPT} 同口径）。 */
    public static final String SCOPE_CUSTOM_DEPT = "2";

    /** 档位：本部门（默认含下级）。 */
    public static final String SCOPE_DEPT = "3";

    /** 档位：仅本人（本人创建 或 本人所属部门及其下级）。 */
    public static final String SCOPE_SELF = "4";

    /** 档位：仅本人（无部门）。 */
    public static final String SCOPE_SELF_ONLY = "5";

    /** 合同表在合同查询里的固定别名（Mapper XML 用 {@code c} 指代 {@code t_ctms_contract}）。 */
    public static final String CONTRACT_ALIAS = "c";

    /** 组织表名。 */
    private static final String DEPT_TABLE = "sys_dept";

    /** 白名单字面量的最大长度（与 {@code varchar(64)} 的标识列对齐）。 */
    private static final int MAX_LITERAL_LENGTH = 64;

    private ContractDataScope()
    {
    }

    /**
     * <p> 部门祖先链查询回调（{@code sys_dept.ancestors}，形如 {@code "0,100,101"}）。 </p>
     *
     * <p> 抽成回调是为了让范围判定可以脱离 Spring/数据库单测：生产实现读
     * {@code sys_dept}，单测实现直接给一张内存表。 </p>
     */
    public interface DeptAncestorsLookup
    {
        /**
         * @param deptId 部门ID
         * @return 该部门的祖先链（逗号分隔）；查不到返回 null
         */
        String ancestorsOf(String deptId);
    }

    /**
     * <p> 依据<b>当前登录用户</b>的角色 {@code data_scope} 生成 SQL 片段（命中即放行）。 </p>
     *
     * <p> 超管或任一角色为"全部"时返回 {@code null}，表示调用方不加任何条件。 </p>
     *
     * @return SQL 片段（形如 {@code (c.create_id = '2') or (c.dept_id = '103')}）；
     *         无需加条件时返回 {@code null}
     */
    public static String buildDataScopeSql()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            String deptId = SecurityUtils.getDeptId();
            if (SecurityUtils.isAdmin(userId))
            {
                return null;
            }
            return buildDataScopeSql(currentDataScopes(), userId, deptId);
        }
        catch (Exception e)
        {
            // 无登录上下文（单测/系统任务/迁移脚本）：不加条件，见类注释的兜底说明
            return null;
        }
    }

    /**
     * <p> 纯函数版本：把给定的档位集合拼成 SQL 片段。 </p>
     *
     * <p> 无参版本负责从 {@code SecurityUtils} 取上下文后委托到这里，因此本方法
     * 可以脱离 Spring 上下文单测（见 {@code ContractDataScopeTest}）。 </p>
     *
     * @param scopes 当前用户的角色档位集合（多角色取并集）
     * @param userId 当前用户ID（服务端取值）
     * @param deptId 当前用户部门ID（服务端取值）
     * @return SQL 片段；包含"全部"档位时返回 {@code null}（不加条件）
     */
    public static String buildDataScopeSql(List<String> scopes, String userId, String deptId)
    {
        if (scopes == null || scopes.isEmpty())
        {
            // 一个档位都没有：并集为空 → 不可见任何行。
            // 这里刻意**不**返回 null（不返回 null 才不会被误读成"全部可见"）。
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
            String route = routeSql(normalized, userId, deptId);
            if (route != null)
            {
                routes.add(route);
            }
        }
        if (routes.isEmpty())
        {
            return "1=0";
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < routes.size(); i++)
        {
            if (i > 0)
            {
                sql.append(" or ");
            }
            sql.append('(').append(routes.get(i)).append(')');
        }
        return sql.toString();
    }

    /**
     * 当前用户是否具备"全部数据"权限（超管，或任一角色的 {@code data_scope='1'}）。
     *
     * @return 具备全部数据权限返回 true
     */
    public static boolean hasAllScope()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            if (SecurityUtils.isAdmin(userId))
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
     * <p> 读取当前登录用户的角色档位集合（去重、保持角色顺序）。 </p>
     *
     * <p> 档位来自 {@code LoginUser.user.roles[*].dataScope}，即登录时随
     * {@code sys_role} 一起缓存的那份快照 —— 不需要额外查库，也不会把请求参数当档位。 </p>
     *
     * @return 档位集合（无登录上下文或角色为空时返回空集合）
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
     * <p> SQL 片段的<b>等价 Java 判定</b>（详情/编辑/删除/恢复按标识直查时使用）。 </p>
     *
     * <p> 分支与 {@link #buildDataScopeSql(List, String, String)} 一一对应；
     * '3'/'2' 与 '4' 的部门分支都归约为「本行部门落在本部门及其下级内」。 </p>
     *
     * @param scopes       当前用户的角色档位集合（多角色取并集）
     * @param userId       当前用户ID（服务端取值）
     * @param userDeptId   当前用户部门ID（服务端取值）
     * @param rowCreateId  目标行的 {@code create_id}
     * @param rowDeptId    目标行的 {@code dept_id}
     * @param lookup       部门祖先链查询回调（仅"含下级"判定需要，可为 null）
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
            if (SCOPE_SELF_ONLY.equals(normalized))
            {
                if (same(userId, rowCreateId))
                {
                    return true;
                }
                continue;
            }
            if (SCOPE_SELF.equals(normalized))
            {
                if (same(userId, rowCreateId) || isDeptInSubtree(rowDeptId, userDeptId, lookup))
                {
                    return true;
                }
                continue;
            }
            if (SCOPE_DEPT.equals(normalized) || SCOPE_CUSTOM_DEPT.equals(normalized))
            {
                if (isDeptInSubtree(rowDeptId, userDeptId, lookup))
                {
                    return true;
                }
                continue;
            }
            // 未知档位：该路不产生可见行（与 routeSql 返回 null 一致）
        }
        return false;
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 单个档位对应的 SQL 片段（不含最外层括号）。
     *
     * @param scope  归一后的档位
     * @param userId 当前用户ID
     * @param deptId 当前用户部门ID
     * @return SQL 片段；该档位无可用取值时返回 null（调用方据此产出 {@code 1=0}）
     */
    private static String routeSql(String scope, String userId, String deptId)
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
        if (SCOPE_SELF_ONLY.equals(scope))
        {
            return uid == null ? null : CONTRACT_ALIAS + ".create_id = '" + uid + "'";
        }
        if (SCOPE_SELF.equals(scope))
        {
            List<String> parts = new ArrayList<>();
            if (uid != null)
            {
                parts.add(CONTRACT_ALIAS + ".create_id = '" + uid + "'");
            }
            if (did != null)
            {
                // 本人所属部门及其下级（与 '3' 的部门分支等价，只是入口不同）
                parts.add(CONTRACT_ALIAS + ".dept_id in (select d.dept_id from " + DEPT_TABLE + " d"
                        + " where d.dept_id = '" + did + "' or find_in_set('" + did + "', d.ancestors))");
            }
            return joinOr(parts);
        }
        if (SCOPE_DEPT.equals(scope) || SCOPE_CUSTOM_DEPT.equals(scope))
        {
            List<String> parts = new ArrayList<>();
            if (did != null)
            {
                parts.add(CONTRACT_ALIAS + ".dept_id = '" + did + "'");
                parts.add("find_in_set('" + did + "', (select d.ancestors from " + DEPT_TABLE + " d"
                        + " where d.dept_id = " + CONTRACT_ALIAS + ".dept_id))");
            }
            return joinOr(parts);
        }
        return null;
    }

    /**
     * 把若干"或"分支用 {@code or} 连接，每个分支各加一层括号。
     *
     * @param parts 分支
     * @return 片段；无可用分支返回 null
     */
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

    /**
     * 合同所属部门是否落在「当前用户部门及其下级」内。
     *
     * @param rowDeptId  合同行的 dept_id
     * @param userDeptId 当前用户部门ID
     * @param lookup     祖先链查询
     * @return 命中返回 true
     */
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

    /**
     * 归一档位：去空格、空串与未知值不参与。
     *
     * @param scope 原始档位
     * @return 归一后的档位；空白返回 null
     */
    private static String normalizeScope(String scope)
    {
        return isBlank(scope) ? null : scope.trim();
    }

    /**
     * SQL 字面量白名单：只放行数字/字母/下划线/连字符，且长度不超过 64。
     *
     * <p> 取值本身就是服务端上下文里的用户ID/部门ID，这一步是深度防御：
     * 万一将来有人把请求参数接到这里，非法字符会被拦下而不是拼进 SQL。 </p>
     *
     * @param value 原始值
     * @return 可安全拼接的字面量；不合法返回 null
     */
    private static String safeLiteral(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LITERAL_LENGTH)
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

    /**
     * 两个字符串是否相等（null 与空白视为同一"空"语义）。
     *
     * @param left  左值
     * @param right 右值
     * @return 相等返回 true
     */
    private static boolean same(String left, String right)
    {
        if (isBlank(left) || isBlank(right))
        {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    /**
     * 空白判定（本类刻意自带，保持纯函数类不依赖其它业务类）。
     *
     * @param value 值
     * @return 为 null 或全空白返回 true
     */
    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
