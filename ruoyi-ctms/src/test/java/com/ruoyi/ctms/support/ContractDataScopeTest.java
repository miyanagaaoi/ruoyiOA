package com.ruoyi.ctms.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <p> {@link ContractDataScope} 的纯函数单测（2.0 B3 任务 4.8）。 </p>
 *
 * <p> 这里只测<b>片段拼接</b>（{@code buildDataScopeSql(scopes, userId, deptId)} 这个重载）
 * 与<b>等价 Java 判定</b>（{@code matches(...)}）：无参版本只负责从
 * {@code SecurityUtils} 取值后委托给它们，因此这些用例不需要 Spring、数据库或登录上下文。 </p>
 *
 * <p> 断言的要点是规格里的四条场景（范围外 403 的判定依据、多角色并集、含下级为默认、超管放行）
 * 以及"D-4 明确的 SELF 口径"（本人创建 <b>或</b> 本人所属部门及其下级）。 </p>
 *
 * @author 二开
 */
public class ContractDataScopeTest
{
    /* ==================== SQL 片段：档位与并集 ==================== */

    @Test
    public void 全部档位不加任何条件()
    {
        assertNull("超管/全部档位必须返回 null（调用方不加条件）",
                ContractDataScope.buildDataScopeSql(scopes("1"), "100", "103"));
    }

    @Test
    public void 多角色含全部档位时并集就是全部()
    {
        assertNull("任一角色为全部，并集即全部",
                ContractDataScope.buildDataScopeSql(scopes("5", "1"), "100", "103"));
    }

    @Test
    public void 无档位时不可见任何行()
    {
        assertEquals("一个档位都没有 → 并集为空 → 不可见任何行", "1=0",
                ContractDataScope.buildDataScopeSql(new ArrayList<String>(), "100", "103"));
        assertEquals("档位集合为 null 同样不可见", "1=0",
                ContractDataScope.buildDataScopeSql(null, "100", "103"));
    }

    @Test
    public void 仅本人档位只按创建人过滤()
    {
        String sql = ContractDataScope.buildDataScopeSql(scopes("5"), "100", "103");
        assertEquals("(c.create_id = '100')", sql);
        assertFalse("只按本人过滤，不应引入部门条件", sql.contains("sys_dept"));
    }

    @Test
    public void 仅本人档位包含本人所属部门及其下级()
    {
        String sql = ContractDataScope.buildDataScopeSql(scopes("4"), "100", "103");
        assertTrue("SELF 必须含创建人分支：" + sql, sql.contains("c.create_id = '100'"));
        assertTrue("SELF 必须含本人所属部门及其下级分支：" + sql, sql.contains("c.dept_id in (select d.dept_id from sys_dept d"));
        assertTrue("含下级靠 find_in_set 表达：" + sql, sql.contains("find_in_set('103', d.ancestors)"));
    }

    @Test
    public void 本部门档位默认含下级()
    {
        String sql = ContractDataScope.buildDataScopeSql(scopes("3"), "100", "103");
        assertTrue("本部门分支：" + sql, sql.contains("c.dept_id = '103'"));
        assertTrue("含下级分支：" + sql,
                sql.contains("find_in_set('103', (select d.ancestors from sys_dept d where d.dept_id = c.dept_id))"));
    }

    @Test
    public void 自定义部门档位与本部同口径()
    {
        assertEquals("B3 不展开 sys_role_dept，档位 2 与 3 同口径",
                ContractDataScope.buildDataScopeSql(scopes("3"), "100", "103"),
                ContractDataScope.buildDataScopeSql(scopes("2"), "100", "103"));
    }

    @Test
    public void 多角色片段用or连接取并集()
    {
        String sql = ContractDataScope.buildDataScopeSql(scopes("5", "3"), "100", "103");
        assertTrue("两条路径必须都在：" + sql, sql.contains("c.create_id = '100'"));
        assertTrue("两条路径必须用 or 连接：" + sql, sql.contains(" or "));
        assertTrue("本部门路径也要在：" + sql, sql.contains("c.dept_id = '103'"));
        assertEquals("必须是「(路径) or (路径)」的形态，多角色才不会被 or 的优先级串味",
                "(c.create_id = '100') or ((c.dept_id = '103')"
                        + " or (find_in_set('103', (select d.ancestors from sys_dept d where d.dept_id = c.dept_id))))",
                sql);
    }

    @Test
    public void 非法字面量不会进入SQL()
    {
        // 白名单拼接：只有数字/字母/下划线/连字符能进 SQL，其它一律丢弃该路径
        String sql = ContractDataScope.buildDataScopeSql(scopes("5"), "1' or '1'='1", "103");
        assertEquals("注入形态的用户ID必须被挡掉，该路径退化为不可见", "1=0", sql);
        String sql2 = ContractDataScope.buildDataScopeSql(scopes("3"), "100", "103) or 1=1 --");
        assertEquals("注入形态的部门ID同样被挡掉", "1=0", sql2);
    }

    /* ==================== 等价 Java 判定（按标识直查） ==================== */

    @Test
    public void 超管放行任何行()
    {
        assertTrue(ContractDataScope.matches(scopes("1"), "100", "103", "999", "888", null));
    }

    @Test
    public void 范围外返回false()
    {
        // 仅本人（无部门）：别人创建、且不属于本人部门 → 不可见
        assertFalse(ContractDataScope.matches(scopes("5"), "100", "103", "999", "888", null));
        assertTrue("本人创建 → 命中", ContractDataScope.matches(scopes("5"), "100", "103", "100", "888", null));
    }

    @Test
    public void 多角色取并集()
    {
        // 角色 A = 本部门、角色 B = 仅本人：合同属本部门但由他人创建 → 并集可见
        List<String> both = scopes("3", "5");
        assertTrue("本部门命中即可见（并集）",
                ContractDataScope.matches(both, "100", "103", "999", "103", null));
        assertFalse("两个档位都不命中才不可见",
                ContractDataScope.matches(both, "100", "103", "999", "888", null));
    }

    @Test
    public void 本部门范围默认含下级()
    {
        // 合同归属子部门 104，其祖先链含 103（当前用户部门）→ 可见
        ContractDataScope.DeptAncestorsLookup lookup = new ContractDataScope.DeptAncestorsLookup()
        {
            @Override
            public String ancestorsOf(String deptId)
            {
                if ("104".equals(deptId))
                {
                    return "0,100,103";
                }
                if ("201".equals(deptId))
                {
                    return "0,200";
                }
                return null;
            }
        };
        assertTrue("含下级默认：子部门的合同对本部门可见",
                ContractDataScope.matches(scopes("3"), "100", "103", "999", "104", lookup));
        assertFalse("不在子树内则不可见",
                ContractDataScope.matches(scopes("3"), "100", "103", "999", "201", lookup));
        assertFalse("没有祖先链回调时退化为精确匹配",
                ContractDataScope.matches(scopes("3"), "100", "103", "999", "104", null));
    }

    @Test
    public void 仅本人档位同样包含本人所属部门及其下级()
    {
        ContractDataScope.DeptAncestorsLookup lookup = new ContractDataScope.DeptAncestorsLookup()
        {
            @Override
            public String ancestorsOf(String deptId)
            {
                return "104".equals(deptId) ? "0,103" : null;
            }
        };
        assertTrue("D-4 的 SELF 口径 = 本人创建 或 本人所属部门及其下级",
                ContractDataScope.matches(scopes("4"), "100", "103", "999", "104", lookup));
    }

    /* ==================== 小工具 ==================== */

    /**
     * 构造档位集合。
     *
     * @param values 档位
     * @return 集合
     */
    private static List<String> scopes(String... values)
    {
        return new ArrayList<>(Arrays.asList(values));
    }
}
