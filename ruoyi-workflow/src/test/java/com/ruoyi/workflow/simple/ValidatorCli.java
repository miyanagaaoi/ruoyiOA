package com.ruoyi.workflow.simple;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.workflow.simple.model.SimpleFlowDef;
import com.ruoyi.workflow.simple.validate.SimpleFlowValidator;
import com.ruoyi.workflow.simple.validate.SimpleFlowValidator.Issue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p> 校验器自检（开发自检用）：好流程应 0 阻断，坏流程应逐条命中规则 </p>
 *
 * <p> 用法：{@code java -cp <cp> com.ruoyi.workflow.simple.ValidatorCli} </p>
 *
 * @author 二开
 */
public class ValidatorCli {

    /** 正常流程 */
    private static final String GOOD = "{"
            + "\"key\":\"contractApproval\",\"name\":\"合同审批（含用印）\",\"category\":\"contract\",\"nodes\":["
            + " {\"id\":\"start\",\"type\":\"start\",\"name\":\"发起\"},"
            + " {\"id\":\"n_dept\",\"type\":\"approve\",\"name\":\"责任部门审批\","
            + "  \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]},\"signMode\":\"REQUIRED\",\"signTypes\":[\"HANDWRITE\"]},"
            + " {\"id\":\"gw\",\"type\":\"condition\",\"name\":\"合同类型路由\",\"branches\":["
            + "   {\"id\":\"b1\",\"name\":\"分支1\",\"groups\":[{\"logic\":\"AND\",\"rows\":[{\"field\":\"contractType\",\"op\":\"EQ\",\"value\":\"经营\"}]}],"
            + "    \"nodes\":[{\"id\":\"n_jf\",\"type\":\"approve\",\"name\":\"经发负责人\",\"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]},"
            + "   {\"id\":\"bd\",\"name\":\"其他情况\",\"defaultBranch\":true,"
            + "    \"nodes\":[{\"id\":\"n_ad\",\"type\":\"approve\",\"name\":\"行政审批\",\"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]}]},"
            + " {\"id\":\"n_joint\",\"type\":\"parallel\",\"name\":\"会审部门\",\"branchSource\":\"FORM_MULTI\",\"formField\":\"jointDepts\","
            + "  \"branchAssignee\":{\"source\":\"BRANCH_VALUE\"},\"joinMode\":\"ALL\"},"
            + " {\"id\":\"end\",\"type\":\"end\",\"name\":\"结束\"}]}";

    /** 故意做坏的流程：命中 V-2 / V-3 / V-5 / V-8 / V-9 / V-12 */
    private static final String BAD = "{"
            + "\"key\":\"bad flow!\",\"name\":\"\",\"nodes\":["
            + " {\"id\":\"start\",\"type\":\"start\",\"name\":\"发起\"},"
            + " {\"id\":\"n1\",\"type\":\"approve\",\"name\":\"首个审批\",\"assignee\":{\"source\":\"ROLE\",\"roleIds\":[\"7\"]}},"
            + " {\"id\":\"n2\",\"type\":\"approve\",\"name\":\"没配参与人\"},"
            + " {\"id\":\"n3\",\"type\":\"approve\",\"name\":\"没配参与人\"},"
            + " {\"id\":\"gw\",\"type\":\"condition\",\"name\":\"缺兜底\",\"branches\":["
            + "   {\"id\":\"b1\",\"name\":\"无条件的分支\",\"nodes\":[{\"id\":\"n4\",\"type\":\"approve\",\"name\":\"分支内\",\"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]},"
            + "   {\"id\":\"b2\",\"name\":\"空分支\",\"groups\":[{\"logic\":\"AND\",\"rows\":[{\"field\":\"notRequired\",\"op\":\"EQ\",\"value\":\"x\"}]}],\"nodes\":[]}]},"
            + " {\"id\":\"p1\",\"type\":\"parallel\",\"name\":\"并行引用单值字段\",\"branchSource\":\"FORM_MULTI\",\"formField\":\"contractType\"},"
            + " {\"id\":\"end\",\"type\":\"end\",\"name\":\"结束\"}]}";

    public static void main(String[] args) {
        int failed = 0;

        Set<String> required = new HashSet<>(Arrays.asList("contractType", "amount", "jointDepts"));
        Set<String> multi = new HashSet<>(Arrays.asList("jointDepts"));

        System.out.println("======== 好流程（期望 0 阻断）========");
        List<Issue> good = SimpleFlowValidator.validate(JSON.parseObject(GOOD, SimpleFlowDef.class), required, multi);
        dump(good);
        if (SimpleFlowValidator.hasBlock(good)) {
            System.out.println("**失败**：好流程被误判");
            failed++;
        } else {
            System.out.println("通过：无阻断项");
        }

        System.out.println();
        System.out.println("======== 坏流程（期望命中多条规则）========");
        List<Issue> bad = SimpleFlowValidator.validate(JSON.parseObject(BAD, SimpleFlowDef.class), required, multi);
        dump(bad);
        String[] expectRules = {"V-0", "V-2", "V-3", "V-5", "V-8", "V-9", "V-12"};
        for (String rule : expectRules) {
            boolean hit = false;
            for (Issue i : bad) {
                if (rule.equals(i.getRule())) {
                    hit = true;
                    break;
                }
            }
            System.out.println("    规则 " + rule + (hit ? " 命中" : " **未命中**"));
            if (!hit) {
                failed++;
            }
        }

        System.out.println();
        System.out.println("========");
        System.out.println(failed == 0 ? "全部通过" : ("失败项 = " + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void dump(List<Issue> issues) {
        if (issues.isEmpty()) {
            System.out.println("    （无）");
            return;
        }
        for (Issue i : issues) {
            System.out.println("    " + i);
        }
    }
}
