package com.ruoyi.template;

import com.ruoyi.template.domain.TemplateSubmitScope;
import com.ruoyi.template.support.SubmitScopeMatcher;
import com.ruoyi.template.support.SubmitScopeMatcher.ScopeUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * <p> <b>B1 §3.2 单元自检：可发起范围过滤的六种情形</b>（REQ-PERM-001 / AC-48）。 </p>
 *
 * <p> 覆盖 delta spec {@code workflow/submit-scope} 的判定语义：
 * 全员可见 / 角色命中 / 部门命中 / 部门下级命中（默认含下级）/ 关掉下级后不命中 / 管理员全可见，
 * 另加"指定人员命中与不命中"、"指定类但明细为空不放行"、"部门 id 前缀不误命中"等边界。 </p>
 *
 * <p> 不连数据库、不起 Spring：{@link SubmitScopeMatcher} 是纯函数，
 * "人"的上下文由 {@link ScopeUser} 直接构造。 </p>
 *
 * <p> 运行方式（classpath 见 DEV-ENV.md §8.6）： </p>
 * <pre>
 *   mvn -B -DskipTests -pl ruoyi-template test-compile
 *   mvn -B -q -pl ruoyi-template dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "ruoyi-template\target\test-classes;ruoyi-template\target\classes;$(cat cp.txt)" `
 *        com.ruoyi.template.SubmitScopeMatcherCheck
 * </pre>
 *
 * @author 二开（2.0 B1）
 */
public class SubmitScopeMatcherCheck {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<String>();

    // 测试用的固定主键
    private static final String ME = "U-1";
    private static final String OTHER = "U-2";
    private static final String MY_DEPT = "D-2";
    private static final String ROOT_DEPT = "D-1";
    private static final String OTHER_DEPT = "D-3";
    private static final String MY_ROLE = "R-1";

    public static void main(String[] args) {
        // 我：属于 D-2，D-2 的物化路径是 "0,D-1"（即 D-1 是我的上级），角色 R-1
        ScopeUser me = new ScopeUser(ME, MY_DEPT, new HashSet<String>(Arrays.asList(MY_ROLE)), "0," + ROOT_DEPT, false);
        // 管理员
        ScopeUser admin = new ScopeUser(ME, MY_DEPT, new HashSet<String>(), "0," + ROOT_DEPT, true);
        // 与我同部门的另一个人（用于"指定人员不命中"）
        ScopeUser other = new ScopeUser(OTHER, OTHER_DEPT, new HashSet<String>(Arrays.asList("R-9")), "0," + ROOT_DEPT, false);

        // ---------- 情形 1：全员可见 ----------
        check("情形1 全员（scopeType=0）→ 任意登录用户可见",
                SubmitScopeMatcher.canStart("0", "1", null, other));

        // ---------- 情形 2：角色命中 ----------
        List<TemplateSubmitScope> byRole = scopes(scope("2", MY_ROLE));
        check("情形2 指定角色命中 → 可见",
                SubmitScopeMatcher.canStart("2", "1", byRole, me));
        check("情形2b 指定角色未命中 → 不可见",
                !SubmitScopeMatcher.canStart("2", "1", byRole, other));

        // ---------- 情形 3：部门命中（就是我自己的部门） ----------
        List<TemplateSubmitScope> byMyDept = scopes(scope("3", MY_DEPT));
        check("情形3 指定部门=我所在部门 → 可见",
                SubmitScopeMatcher.canStart("3", "1", byMyDept, me));

        // ---------- 情形 4：部门下级命中（默认含下级：指定 D-1，我在 D-2，D-1 是我的祖先） ----------
        List<TemplateSubmitScope> byRootDept = scopes(scope("3", ROOT_DEPT));
        check("情形4 指定上级部门 + 默认含下级 → 我可见（同一份明细也覆盖了 D-1 直属用户）",
                SubmitScopeMatcher.canStart("3", "1", byRootDept, me));

        // ---------- 情形 5：关掉"包含下级部门"后不命中 ----------
        check("情形5 同一明细 + includeChildDept=0 → 我不再可见",
                !SubmitScopeMatcher.canStart("3", "0", byRootDept, me));

        // ---------- 情形 6：管理员不受范围限制 ----------
        check("情形6 管理员对'指定人员'范围也可见",
                SubmitScopeMatcher.canStart("1", "0", scopes(scope("1", OTHER)), admin));
        check("情形6b 管理员对空明细的'指定'范围也可见",
                SubmitScopeMatcher.canStart("1", "0", Collections.<TemplateSubmitScope>emptyList(), admin));

        // ---------- 边界：指定人员 ----------
        List<TemplateSubmitScope> byUser = scopes(scope("1", ME));
        check("边界 指定人员=我 → 可见", SubmitScopeMatcher.canStart("1", "0", byUser, me));
        check("边界 指定人员=别人 → 我不可见", !SubmitScopeMatcher.canStart("1", "0", byUser, other));

        // ---------- 边界：多态明细"任一条命中即可见" ----------
        List<TemplateSubmitScope> mixed = scopes(scope("1", OTHER), scope("2", MY_ROLE));
        check("边界 明细里任一条命中即可见（人员那条不命中、角色那条命中）",
                SubmitScopeMatcher.canStart("2", "0", mixed, me));

        // ---------- 边界："指定"类但明细为空 → 除管理员外不放行 ----------
        check("边界 指定类 + 明细为空 → 普通用户不可见（配置不完整宁可不放行）",
                !SubmitScopeMatcher.canStart("1", "1", Collections.<TemplateSubmitScope>emptyList(), me));

        // ---------- 边界：存量模板 submit_scope_type 为空 = 全员（升级前行为不变） ----------
        check("边界 存量模板 scopeType=null → 全员可见（零回归）",
                SubmitScopeMatcher.canStart(null, null, null, other));
        check("边界 存量模板 scopeType='' → 全员可见（零回归）",
                SubmitScopeMatcher.canStart("", null, null, other));

        // ---------- 边界：部门 id 前缀不能误命中（D-100 不该被 D-1000 命中） ----------
        // 我的部门是 D-2000，祖先路径里是 "0,D-100"：目标 D-1000 既不是我的部门、也不在我的祖先里
        ScopeUser inDept2000 = new ScopeUser(ME, "D-2000", new HashSet<String>(), "0,D-100", false);
        check("边界 部门 id 做整段比较：我的祖先含 D-100 ≠ 目标 D-1000（不能前缀误命中）",
                !SubmitScopeMatcher.canStart("3", "1", scopes(scope("3", "D-1000")), inDept2000));
        check("边界 部门 id 做整段比较：目标 D-100 确实在我的祖先里 → 命中",
                SubmitScopeMatcher.canStart("3", "1", scopes(scope("3", "D-100")), inDept2000));
        check("边界 我自己的部门 D-2000 命中（不依赖含下级开关）",
                SubmitScopeMatcher.canStart("3", "0", scopes(scope("3", "D-2000")), inDept2000));

        System.out.println("──────────────────────────────────────────────");
        System.out.println("B1 §3.2 可发起范围判定自检（六种情形 + 边界）");
        System.out.println("通过 " + passed + " 项，失败 " + failures.size() + " 项");
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        System.out.println(failures.isEmpty() ? "结论：PASS" : "结论：FAIL");
        System.out.println("──────────────────────────────────────────────");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static TemplateSubmitScope scope(String type, String target) {
        TemplateSubmitScope s = new TemplateSubmitScope();
        s.setScopeType(type);
        s.setTargetId(target);
        return s;
    }

    private static List<TemplateSubmitScope> scopes(TemplateSubmitScope... items) {
        return new ArrayList<TemplateSubmitScope>(Arrays.asList(items));
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failures.add(name);
            System.out.println("  ✗ " + name);
        }
    }
}
