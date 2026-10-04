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

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 端到端：用<b>编译器自己产出</b>的 BPMN 跑完整条合同审批链 </p>
 *
 * <p> 链路：发起 → 责任部门审批 → 条件分支（合同类型路由）→ 经发负责人 → 法务（固定必经）
 * → 会审部门（并行多实例）→ 结束。 </p>
 *
 * <p> 验证点：编译产物可部署、排他网关只走一条、并行会审按表单多选生成 N 个待办、全部完成后自动结束。 </p>
 *
 * <p> 用法：{@code java -cp <cp> com.ruoyi.workflow.simple.CompiledFlowSmokeTest <bpmn文件>} </p>
 *
 * @author 二开
 */
public class CompiledFlowSmokeTest {

    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/flow_smoke"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false&nullCatalogMeansCurrent=true";

    public static void main(String[] args) throws Exception {
        String bpmn = args.length > 0 ? args[0] : "H:/dsh/ruoyiOA/.cache/contractApproval.bpmn";

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

        Deployment dep;
        try (FileInputStream in = new FileInputStream(bpmn)) {
            dep = rs.createDeployment().addInputStream("contractApproval.bpmn", in)
                    .name("contractApproval").category("contract").deploy();
        }
        ProcessDefinition pd = rs.createProcessDefinitionQuery().deploymentId(dep.getId()).singleResult();
        System.out.println("[部署] 编译器产物部署成功  " + pd.getId());
        System.out.println("[部署] 流程图资源 = " + pd.getDiagramResourceName());

        int failed = 0;

        Map<String, Object> vars = new HashMap<>();
        vars.put("contractType", "经营");
        vars.put("amount", 800000);
        vars.put("jointDepts", Arrays.asList("财务部", "行政部", "行政部B"));
        ProcessInstance pi = rt.startProcessInstanceById(pd.getId(), vars);

        // 1) 责任部门审批
        List<Task> t = tasks(ts, pi);
        System.out.println("[1] " + names(t));
        failed += expect(t, 1, "n_dept", "发起后进入责任部门审批");

        // 2) 条件分支：两条条件同时为真 → 只能一条
        ts.complete(t.get(0).getId());
        t = tasks(ts, pi);
        System.out.println("[2] " + names(t));
        failed += expect(t, 1, "n_jingfa", "两条分支条件同时为真，排他只走一条（分支1）");

        // 3) 合流到法务（固定必经）
        ts.complete(t.get(0).getId());
        t = tasks(ts, pi);
        System.out.println("[3] " + names(t));
        failed += expect(t, 1, "n_legal", "分支合流后进入法务固定节点");

        // 4) 并行会审：3 个部门 → 3 个待办
        ts.complete(t.get(0).getId());
        t = tasks(ts, pi);
        System.out.println("[4] " + names(t));
        failed += expect(t, 3, "n_joint", "3 个会审部门产生 3 个并行待办（多实例）");

        // 5) 全部完成后流程结束
        for (Task task : tasks(ts, pi)) {
            ts.complete(task.getId());
        }
        boolean ended = rt.createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult() == null;
        System.out.println("[5] 流程实例是否已结束 = " + ended);
        if (ended) {
            System.out.println("[5] 通过：全部会审完成后自动结束");
        } else {
            System.out.println("[5] **失败**：流程未结束，剩余任务 " + names(tasks(ts, pi)));
            failed++;
        }

        // 6) 空集合：一个部门都不勾 → 自动跳过会审节点
        Map<String, Object> vars2 = new HashMap<>();
        vars2.put("contractType", "行政");
        vars2.put("amount", 100);
        vars2.put("jointDepts", new java.util.ArrayList<String>());
        ProcessInstance p2 = rt.startProcessInstanceById(pd.getId(), vars2);
        ts.complete(tasks(ts, p2).get(0).getId());   // 责任部门
        ts.complete(tasks(ts, p2).get(0).getId());   // 其他情况 → 行政审批
        ts.complete(tasks(ts, p2).get(0).getId());   // 法务
        List<Task> t6 = tasks(ts, p2);
        System.out.println("[6] 空集合时 任务 = " + names(t6) + "  实例存活 = "
                + (rt.createProcessInstanceQuery().processInstanceId(p2.getId()).singleResult() != null));
        if (t6.isEmpty()) {
            System.out.println("[6] 通过：空集合自动跳过会审节点并结束");
        } else {
            System.out.println("[6] **失败**：空集合未跳过");
            failed++;
        }

        System.out.println("----");
        System.out.println(failed == 0 ? "全部用例通过" : ("失败用例数 = " + failed));
        engine.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int expect(List<Task> tasks, int size, String key, String desc) {
        if (tasks.size() == size && key.equals(tasks.get(0).getTaskDefinitionKey())) {
            System.out.println("    通过：" + desc);
            return 0;
        }
        System.out.println("    **失败**：" + desc + "，期望 " + size + " 个 " + key + "，实际 " + names(tasks));
        return 1;
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
}
