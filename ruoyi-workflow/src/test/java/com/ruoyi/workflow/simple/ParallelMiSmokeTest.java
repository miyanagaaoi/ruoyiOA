package com.ruoyi.workflow.simple;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 并行会审（多实例）引擎语义实测 </p>
 *
 * <p> 目的：在写编译器之前，先确认 Flowable 6.7.2 在<b>本项目约定</b>下的真实行为，
 * 因为编译方式取决于这些行为： </p>
 * <ol>
 *   <li> 集合有 2 个元素时，是否并行产生 2 个待办； </li>
 *   <li> 全部完成后是否自动往下走（合流 = 多实例结束，不需要并行网关）； </li>
 *   <li> <b>集合为空 / 变量不存在时会不会自动跳过</b>（决定"会审一个部门都不勾"要不要额外兜底）； </li>
 *   <li> 或签完成条件 {@code ${nrOfCompletedInstances >= 1}} 是否"一人完成即推进"。 </li>
 * </ol>
 *
 * <p> 用法：{@code java -cp <cp> com.ruoyi.workflow.simple.ParallelMiSmokeTest} </p>
 *
 * @author 二开
 */
public class ParallelMiSmokeTest {

    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/flow_smoke"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false&nullCatalogMeansCurrent=true";

    /**
     * 多实例会审流程
     *
     * @param completion 完成条件：全部完成 / 任一完成
     */
    private static String miXml(String processId, String name, String completion) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n"
                + "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "  xmlns:flowable=\"http://flowable.org/bpmn\"\n"
                + "  targetNamespace=\"http://www.flowable.org/processdef\">\n"
                + "  <process id=\"" + processId + "\" name=\"" + name + "\">\n"
                + "    <startEvent id=\"start\" name=\"发起\"><outgoing>f1</outgoing></startEvent>\n"
                + "    <userTask id=\"n_dept\" name=\"责任部门审批\" flowable:assignee=\"u1\""
                + " flowable:userType=\"assignee\" flowable:dataType=\"fixed\">\n"
                + "      <incoming>f1</incoming><outgoing>f2</outgoing>\n"
                + "    </userTask>\n"
                + "    <userTask id=\"n_joint\" name=\"会审部门（并行）\" flowable:assignee=\"${dept}\""
                + " flowable:userType=\"assignee\" flowable:dataType=\"dynamic\">\n"
                + "      <incoming>f2</incoming><outgoing>f3</outgoing>\n"
                + "      <multiInstanceLoopCharacteristics isSequential=\"false\""
                + " flowable:collection=\"jointDepts\" flowable:elementVariable=\"dept\">\n"
                + "        <completionCondition xsi:type=\"tFormalExpression\"><![CDATA[" + completion + "]]></completionCondition>\n"
                + "      </multiInstanceLoopCharacteristics>\n"
                + "    </userTask>\n"
                + "    <userTask id=\"n_legal\" name=\"法务审批\" flowable:assignee=\"u1\""
                + " flowable:userType=\"assignee\" flowable:dataType=\"fixed\">\n"
                + "      <incoming>f3</incoming><outgoing>f4</outgoing>\n"
                + "    </userTask>\n"
                + "    <endEvent id=\"end\" name=\"结束\"><incoming>f4</incoming></endEvent>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"n_dept\" />\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"n_dept\" targetRef=\"n_joint\" />\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"n_joint\" targetRef=\"n_legal\" />\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"n_legal\" targetRef=\"end\" />\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    public static void main(String[] args) {
        ProcessEngine engine = ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
                .setJdbcUrl(JDBC_URL)
                .setJdbcDriver("com.mysql.cj.jdbc.Driver")
                .setJdbcUsername("root")
                .setJdbcPassword("")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .buildProcessEngine();

        RepositoryService rs = engine.getRepositoryService();
        RuntimeService rt = engine.getRuntimeService();
        TaskService ts = engine.getTaskService();

        Deployment depAll = deploy(rs, "miAll.bpmn", miXml("miAll", "并行会审-全部完成", "${nrOfCompletedInstances == nrOfInstances}"));
        Deployment depAny = deploy(rs, "miAny.bpmn", miXml("miAny", "并行会审-任一完成", "${nrOfCompletedInstances >= 1}"));
        ProcessDefinition pdAll = rs.createProcessDefinitionQuery().deploymentId(depAll.getId()).singleResult();
        ProcessDefinition pdAny = rs.createProcessDefinitionQuery().deploymentId(depAny.getId()).singleResult();
        System.out.println("[部署] 全部完成版 = " + pdAll.getId());
        System.out.println("[部署] 任一完成版 = " + pdAny.getId());

        int failed = 0;

        /* ---------------- 用例 1：2 个部门 → 2 个并行待办 ---------------- */
        System.out.println();
        System.out.println("======== 用例1：集合=[财务部, 行政部]，全部完成版 ========");
        Map<String, Object> v1 = new HashMap<>();
        v1.put("jointDepts", Arrays.asList("财务部", "行政部"));
        ProcessInstance p1 = rt.startProcessInstanceById(pdAll.getId(), v1);
        completeFirst(ts, p1);
        List<Task> t1 = tasks(ts, p1);
        System.out.println("[用例1] 会审节点任务 = " + names(t1));
        if (t1.size() == 2) {
            System.out.println("[用例1] 通过：2 个部门产生 2 个并行待办");
        } else {
            System.out.println("[用例1] **失败**：期望 2 个待办，实际 " + t1.size());
            failed++;
        }

        /* ---------------- 用例 2：全部完成才推进 ---------------- */
        if (t1.size() == 2) {
            ts.complete(t1.get(0).getId());
            List<Task> remain = tasks(ts, p1);
            System.out.println("[用例2] 完成 1 个部门后 任务 = " + names(remain) + "（应仍停在会审节点）");
            if (remain.size() != 1 || !"n_joint".equals(remain.get(0).getTaskDefinitionKey())) {
                System.out.println("[用例2] **失败**：未等待全部完成");
                failed++;
            } else {
                ts.complete(remain.get(0).getId());
                List<Task> after = tasks(ts, p1);
                System.out.println("[用例2] 再完成最后 1 个 → 任务 = " + names(after));
                if (after.size() == 1 && "n_legal".equals(after.get(0).getTaskDefinitionKey())) {
                    System.out.println("[用例2] 通过：全部完成后自动合流进入下一节点（无需并行网关）");
                } else {
                    System.out.println("[用例2] **失败**：未正确合流");
                    failed++;
                }
            }
        }

        /* ---------------- 用例 3：集合为空 → 观察行为 ---------------- */
        System.out.println();
        System.out.println("======== 用例3：集合=[]（一个部门都不勾）========");
        Map<String, Object> v3 = new HashMap<>();
        v3.put("jointDepts", new ArrayList<String>());
        try {
            ProcessInstance p3 = rt.startProcessInstanceById(pdAll.getId(), v3);
            completeFirst(ts, p3);
            List<Task> t3 = tasks(ts, p3);
            System.out.println("[用例3] 任务 = " + names(t3));
            if (t3.size() == 1 && "n_legal".equals(t3.get(0).getTaskDefinitionKey())) {
                System.out.println("[用例3] 结论：**空集合自动跳过**（无需额外兜底）");
            } else {
                System.out.println("[用例3] 结论：**空集合不会自动跳过**（需要 skipExpression 或前置网关兜底）");
            }
        } catch (Exception e) {
            System.out.println("[用例3] 结论：**空集合直接报错** -> " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }

        /* ---------------- 用例 4：变量不存在 → 观察行为 ---------------- */
        System.out.println();
        System.out.println("======== 用例4：jointDepts 变量根本不存在 ========");
        try {
            ProcessInstance p4 = rt.startProcessInstanceById(pdAll.getId(), new HashMap<String, Object>());
            completeFirst(ts, p4);
            List<Task> t4 = tasks(ts, p4);
            System.out.println("[用例4] 任务 = " + names(t4));
            System.out.println("[用例4] 结论：变量缺失时" + (t4.size() == 1 && "n_legal".equals(t4.get(0).getTaskDefinitionKey()) ? "**自动跳过**" : "**未跳过**"));
        } catch (Exception e) {
            System.out.println("[用例4] 结论：**直接报错** -> " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }

        /* ---------------- 用例 5：或签（任一完成即推进） ---------------- */
        System.out.println();
        System.out.println("======== 用例5：任一完成版（或签）========");
        Map<String, Object> v5 = new HashMap<>();
        v5.put("jointDepts", Arrays.asList("财务部", "行政部"));
        ProcessInstance p5 = rt.startProcessInstanceById(pdAny.getId(), v5);
        completeFirst(ts, p5);
        List<Task> t5 = tasks(ts, p5);
        System.out.println("[用例5] 会审节点任务 = " + names(t5));
        if (t5.size() == 2) {
            ts.complete(t5.get(0).getId());
            List<Task> after = tasks(ts, p5);
            System.out.println("[用例5] 完成其中 1 个后 → 任务 = " + names(after));
            boolean advanced = after.size() == 1 && "n_legal".equals(after.get(0).getTaskDefinitionKey());
            System.out.println(advanced
                    ? "[用例5] 通过：一人完成即推进（其余分支已作废）"
                    : "[用例5] **注意**：未按或签推进，剩余 " + after.size() + " 个任务");
            if (!advanced) {
                failed++;
            }
        } else {
            System.out.println("[用例5] **失败**：未产生 2 个并行待办");
            failed++;
        }

        System.out.println();
        System.out.println("========");
        System.out.println(failed == 0 ? "全部用例通过" : ("失败用例数 = " + failed));

        engine.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    private static Deployment deploy(RepositoryService rs, String resource, String xml) {
        return rs.createDeployment()
                .addInputStream(resource, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .name(resource)
                .category("smoke")
                .deploy();
    }

    /** 完成流程首个节点（责任部门审批），让流程推进到会审节点 */
    private static void completeFirst(TaskService ts, ProcessInstance pi) {
        List<Task> first = ts.createTaskQuery().processInstanceId(pi.getId()).list();
        if (first.isEmpty() || !"n_dept".equals(first.get(0).getTaskDefinitionKey())) {
            throw new IllegalStateException("首个节点不是责任部门审批：" + names(first));
        }
        ts.complete(first.get(0).getId());
    }

    private static List<Task> tasks(TaskService ts, ProcessInstance pi) {
        return ts.createTaskQuery().processInstanceId(pi.getId()).list();
    }

    private static String names(List<Task> tasks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tasks.get(i).getTaskDefinitionKey())
                    .append("(").append(tasks.get(i).getName())
                    .append("/").append(tasks.get(i).getAssignee()).append(")");
        }
        return sb.append("]").toString();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
