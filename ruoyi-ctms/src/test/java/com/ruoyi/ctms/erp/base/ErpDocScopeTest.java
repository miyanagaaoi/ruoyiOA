package com.ruoyi.ctms.erp.base;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 数据范围单测（2.0 B4 任务 1.4 的机制部分；design D10、tasks.md §8.2、AC-79）。 </p>
 *
 * <p> 覆盖四档（本人 / 本部门 / 本部门及下级 / 全部）的<b>SQL 片段</b>与<b>等价 Java 判定</b>
 * 两条路径，以及"多角色取并集""无档位 = 不可见""归属部门创建时快照且编辑不重算"
 * "别名不污染子查询"这四件事。 </p>
 *
 * @author 二开
 */
public class ErpDocScopeTest
{
    /** 内存部门树：101 的父是 100，102 的父是 101（用于"含下级"判定）。 */
    private static final ErpDocScope.DeptAncestorsLookup TREE = new ErpDocScope.DeptAncestorsLookup()
    {
        @Override
        public String ancestorsOf(String deptId)
        {
            if ("100".equals(deptId))
            {
                return "0";
            }
            if ("101".equals(deptId))
            {
                return "0,100";
            }
            if ("102".equals(deptId))
            {
                return "0,100,101";
            }
            if ("200".equals(deptId))
            {
                return "0";
            }
            return null;
        }
    };

    private static List<String> scope(String... values)
    {
        return Arrays.asList(values);
    }

    /* ==================== 档位 → SQL 片段 ==================== */

    @Test
    public void 全部档不加条件()
    {
        assertNull("超管/全部档返回 null（调用方不加条件）",
                ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_ALL), "u1", "100", "d"));
    }

    @Test
    public void 本人档只按创建人过滤()
    {
        String sql = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_SELF), "u1", "100", "d");
        assertNotNull(sql);
        assertTrue("片段应含创建人条件：" + sql, sql.contains("d.create_id = 'u1'"));
        assertFalse("本人档不得含部门条件：" + sql, sql.contains("dept_id"));
    }

    @Test
    public void 本部门档不含下级()
    {
        String sql = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_DEPT), "u1", "101", "d");
        assertNotNull(sql);
        assertEquals("本部门档必须只有等值条件（这是与「本部门及下级」的关键差异）",
                "(d.dept_id = '101')", sql);
        assertFalse("本部门档不得出现 ancestors 子查询", sql.contains("ancestors"));
    }

    @Test
    public void 本部门及下级档用物化路径判子树()
    {
        String sql = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_DEPT_AND_CHILD), "u1", "101", "d");
        assertNotNull(sql);
        assertTrue("应含等值分支：" + sql, sql.contains("(d.dept_id = '101')"));
        assertTrue("应含 find_in_set 祖先判定：" + sql, sql.contains("find_in_set('101'"));
        // 内层 sys_dept 别名必须与外部单据别名区分（同名会让子查询恒真）
        assertTrue("内层别名应为 sd：" + sql, sql.contains("from sys_dept sd"));
        assertTrue("子查询必须按外部行的部门取值：" + sql,
                sql.contains("sd.dept_id = d.dept_id"));
        assertFalse("不得出现自比较 " + sql, sql.contains("sd.dept_id = sd.dept_id"));
    }

    @Test
    public void 自定义部门档与本部门及下级同口径()
    {
        String custom = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_CUSTOM_DEPT), "u1", "101", "d");
        String child = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_DEPT_AND_CHILD), "u1", "101", "d");
        assertEquals("'2'（自定义部门）与 '4'（本部门及下级）同口径（不展开 sys_role_dept）", child, custom);
    }

    @Test
    public void 多角色取并集()
    {
        String sql = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_SELF, ErpDocScope.SCOPE_DEPT), "u1", "101", "d");
        assertNotNull(sql);
        assertTrue("并集应含本人分支：" + sql, sql.contains("create_id = 'u1'"));
        assertTrue("并集应含部门分支：" + sql, sql.contains("dept_id = '101'"));
        assertTrue("两分支用 or 连接：" + sql, sql.contains(" or "));
        assertNull("只要有一个角色是全部档，就回到不加条件",
                ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_SELF, ErpDocScope.SCOPE_ALL), "u1", "101", "d"));
    }

    @Test
    public void 无档位时不可见任何行()
    {
        assertEquals("空档位集合必须产出 1=0（不能返回 null，否则会被读成「全部可见」）",
                "1=0", ErpDocScope.buildDataScopeSql(Collections.<String>emptyList(), "u1", "101", "d"));
        assertEquals("未知档位同样不可见", "1=0",
                ErpDocScope.buildDataScopeSql(scope("9"), "u1", "101", "d"));
    }

    @Test
    public void 非法字面量不进片段()
    {
        // 用户ID里带引号/分号（模拟"有人把请求参数接到这里"）：安全白名单必须拦下
        String sql = ErpDocScope.buildDataScopeSql(scope(ErpDocScope.SCOPE_SELF), "u1' or '1'='1", "101", "d");
        assertEquals("非法字面量的档位不产出任何条件 → 1=0", "1=0", sql);
        assertFalse("绝不能把原始值拼进 SQL", String.valueOf(sql).contains("or '1'='1"));
    }

    /* ==================== 档位 → Java 判定（与片段一一对应） ==================== */

    @Test
    public void 四档的等价Java判定()
    {
        // 全部
        assertTrue(ErpDocScope.matches(scope("1"), "u1", "101", "u9", "200", TREE));
        // 本人：只看创建人
        assertTrue(ErpDocScope.matches(scope("5"), "u1", "101", "u1", "200", TREE));
        assertFalse("本人档：别人建的部门内单据也不可见", ErpDocScope.matches(scope("5"), "u1", "101", "u2", "101", TREE));
        // 本部门：只等值，不含下级
        assertTrue(ErpDocScope.matches(scope("3"), "u1", "101", "u9", "101", TREE));
        assertFalse("本部门档看不到下级部门的单据", ErpDocScope.matches(scope("3"), "u1", "101", "u9", "102", TREE));
        assertFalse("本部门档看不到上级部门的单据", ErpDocScope.matches(scope("3"), "u1", "101", "u9", "100", TREE));
        // 本部门及下级：含自身与整棵子树
        assertTrue(ErpDocScope.matches(scope("4"), "u1", "101", "u9", "101", TREE));
        assertTrue(ErpDocScope.matches(scope("4"), "u1", "101", "u9", "102", TREE));
        assertFalse("下级档看不到平级部门的单据", ErpDocScope.matches(scope("4"), "u1", "101", "u9", "200", TREE));
        assertFalse("下级档看不到上级部门的单据", ErpDocScope.matches(scope("4"), "u1", "101", "u9", "100", TREE));
        // 自定义部门档同 '4'
        assertTrue(ErpDocScope.matches(scope("2"), "u1", "101", "u9", "102", TREE));
        // 多角色并集：任一命中即可
        assertTrue(ErpDocScope.matches(scope("5", "4"), "u1", "101", "u2", "102", TREE));
        // 空档位/未知档位不可见
        assertFalse(ErpDocScope.matches(Collections.<String>emptyList(), "u1", "101", "u1", "101", TREE));
        assertFalse(ErpDocScope.matches(scope("9"), "u1", "101", "u1", "101", TREE));
    }

    @Test
    public void 含下级判定在缺祖先链时保守拒绝()
    {
        assertFalse("拿不到祖先链不得放宽",
                ErpDocScope.matches(scope("4"), "u1", "101", "u9", "102", null));
        assertTrue("但同部门仍然命中",
                ErpDocScope.matches(scope("4"), "u1", "101", "u9", "101", null));
    }

    /* ==================== 归属部门创建时快照 ==================== */

    @Test
    public void 创建快照写入创建人与归属部门()
    {
        ErpDocHeaderStub header = new ErpDocHeaderStub();
        ErpDocScope.applyCreateSnapshot(header, "u1", "101", "zhangsan");
        assertEquals("u1", header.getCreateId());
        assertEquals("101", header.getDeptId());
        assertEquals("zhangsan", header.getCreateBy());
        assertEquals("初始状态必须是草稿", ErpDocStatus.DRAFT, header.getStatus());
        assertEquals("初始未过账", "0", header.getPosted());
        assertEquals("初始未删除", "0", header.getDelFlag());
    }

    @Test
    public void 缺创建人或缺部门时明确报错()
    {
        final ErpDocHeaderStub header = new ErpDocHeaderStub();
        assertRejected("无法确定创建人", new Runnable()
        {
            @Override
            public void run()
            {
                ErpDocScope.applyCreateSnapshot(header, "  ", "101", "zhangsan");
            }
        });
        assertRejected("未分配部门", new Runnable()
        {
            @Override
            public void run()
            {
                ErpDocScope.applyCreateSnapshot(header, "u1", null, "zhangsan");
            }
        });
        assertNull("报错时不得写入半截快照", header.getCreateId());
        assertNull(header.getDeptId());
    }

    @Test
    public void 编辑不重算归属部门()
    {
        ErpDocHeaderStub header = new ErpDocHeaderStub();
        ErpDocScope.applyCreateSnapshot(header, "u1", "101", "zhangsan");
        // 模拟"创建人后来被调岗"：编辑路径**不**调用 applyCreateSnapshot，因此部门保持 101
        header.setRemark("改了备注");
        assertEquals("归属部门是创建时快照，不随调岗回溯", "101", header.getDeptId());
        assertTrue("草稿可编辑（编辑权限的判定同样只看状态）", ErpDocStatus.isEditable(header.getStatus()));
        // 反证：只有"新建"才写当时的部门，已建单据不受影响
        ErpDocHeaderStub another = new ErpDocHeaderStub();
        ErpDocScope.applyCreateSnapshot(another, "u1", "200", "zhangsan");
        assertEquals("新单按创建当时部门快照", "200", another.getDeptId());
        assertEquals("旧单部门保持不变", "101", header.getDeptId());
    }

    /** 便于断言的最小表头实现。 */
    private static class ErpDocHeaderStub extends com.ruoyi.ctms.erp.base.domain.ErpDocHeader
    {
        private static final long serialVersionUID = 1L;
    }

    private static void assertRejected(String expectedMessagePart, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒但通过了（期望文案含：" + expectedMessagePart + "）");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应含「" + expectedMessagePart + "」，实际：" + e.getMessage(),
                    e.getMessage().contains(expectedMessagePart));
        }
    }
}
