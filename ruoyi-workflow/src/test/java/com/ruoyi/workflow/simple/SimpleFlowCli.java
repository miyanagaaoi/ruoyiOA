package com.ruoyi.workflow.simple;

import com.ruoyi.workflow.simple.compile.SimpleFlowCompiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * <p> 编译器命令行（开发自检用，不在生产链路里） </p>
 *
 * <p> 用法：{@code java -cp <classpath> com.ruoyi.workflow.simple.SimpleFlowCli <输出文件>} </p>
 *
 * <p> 用途：在没有单元测试基础设施的阶段，把编译器产物落盘，交给真实 Flowable 引擎部署验证。 </p>
 *
 * @author 二开
 */
public class SimpleFlowCli {

    /** 合同审批（含用印）示例：主链固定节点 + 条件分支（排他 + 兜底） */
    private static final String SAMPLE = "{"
            + "\"schemaVersion\":1,"
            + "\"key\":\"contractApproval\","
            + "\"name\":\"合同审批（含用印）\","
            + "\"category\":\"contract\","
            + "\"nodes\":["
            + "  {\"id\":\"start\",\"type\":\"start\",\"name\":\"发起\"},"
            + "  {\"id\":\"n_dept\",\"type\":\"approve\",\"name\":\"责任部门审批\","
            + "   \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]},"
            + "   \"multiMode\":\"SINGLE\",\"signMode\":\"REQUIRED\",\"signTypes\":[\"HANDWRITE\",\"PRESET\"],"
            + "   \"buttons\":[\"agree\",\"return\",\"copy\",\"print\"],\"timeoutHours\":24,\"sameUserPolicy\":\"EACH_TIME\"},"
            + "  {\"id\":\"gw_type\",\"type\":\"condition\",\"name\":\"合同类型路由\",\"branches\":["
            + "     {\"id\":\"b1\",\"name\":\"分支1\",\"groups\":[{\"logic\":\"AND\",\"rows\":["
            + "        {\"field\":\"contractType\",\"op\":\"EQ\",\"value\":\"经营\"}]}],"
            + "      \"nodes\":[{\"id\":\"n_jingfa\",\"type\":\"approve\",\"name\":\"经发负责人审批\","
            + "        \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]},"
            + "     {\"id\":\"b2\",\"name\":\"分支2\",\"groups\":[{\"logic\":\"AND\",\"rows\":["
            + "        {\"field\":\"amount\",\"op\":\"GT\",\"value\":500000}]}],"
            + "      \"nodes\":[{\"id\":\"n_finance\",\"type\":\"approve\",\"name\":\"财务复核\","
            + "        \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]},"
            + "     {\"id\":\"b_default\",\"name\":\"其他情况\",\"defaultBranch\":true,"
            + "      \"nodes\":[{\"id\":\"n_admin\",\"type\":\"approve\",\"name\":\"行政审批\","
            + "        \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]}}]}"
            + "  ]},"
            + "  {\"id\":\"n_legal\",\"type\":\"approve\",\"name\":\"法务审批（固定必经）\","
            + "   \"assignee\":{\"source\":\"USER\",\"userIds\":[\"1\"]},\"signMode\":\"REQUIRED\"},"
            + "  {\"id\":\"n_joint\",\"type\":\"parallel\",\"name\":\"会审部门（并行）\","
            + "   \"branchSource\":\"FORM_MULTI\",\"formField\":\"jointDepts\","
            + "   \"branchAssignee\":{\"source\":\"BRANCH_VALUE\"},\"joinMode\":\"ALL\","
            + "   \"signMode\":\"REQUIRED\"},"
            + "  {\"id\":\"end\",\"type\":\"end\",\"name\":\"结束\"}"
            + "]}";

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "target/contractApproval.bpmn";
        String xml = SimpleFlowCompiler.compileJson(SAMPLE);
        Files.write(Paths.get(out), xml.getBytes(StandardCharsets.UTF_8));
        System.out.println("OK -> " + Paths.get(out).toAbsolutePath());
        System.out.println("----");
        System.out.println(xml);
    }
}
