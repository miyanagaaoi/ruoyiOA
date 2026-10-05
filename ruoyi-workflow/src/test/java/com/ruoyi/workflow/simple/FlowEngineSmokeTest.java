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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> Flowable 引擎语义冒烟验证（开发自检用，独立库，不触碰业务库 rad_oa） </p>
 *
 * <p> 验证 PRD 6.8 的三条硬约束里最容易出错的一条：
 * <b>条件分支必须是排他的</b>——两条分支条件同时为真时，只能产生一个待办。
 * 若这里出现 2 个任务，说明编译产物把条件挂错了位置（挂到了 userTask 出口），
 * 会静默退化成并行分支。 </p>
 *
 * <p> 用法：{@code java -cp <cp> com.ruoyi.workflow.simple.FlowEngineSmokeTest <bpmn文件>} </p>
 *
 * @author 二开
 */
public class FlowEngineSmokeTest {

    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/flow_smoke"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false&nullCatalogMeansCurrent=true";

    public static void main(String[] args) throws Exception {
        String bpmn = args.length > 0 ? args[0] : "F:/dsh/ruoyiOA/.cache/contractApproval.bpmn";

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
            dep = rs.createDeployment()
                    .addInputStream("contractApproval.bpmn", in)
                    .name("contractApproval")
                    .category("contract")
                    .deploy();
        }
        ProcessDefinition pd = rs.createProcessDefinitionQuery().deploymentId(dep.getId()).singleResult();
        System.out.println("[部署] 成功  deploymentId=" + dep.getId() + "  processDefinitionId=" + pd.getId());
        System.out.println("[部署] 流程图资源 = " + pd.getDiagramResourceName());

        int failed = 0;

        // 说明：流程首个节点是"责任部门审批"，网关要等它完成后才求值，
        // 所以每个用例都必须先 complete 掉首节点，才能观察到分支结果。

        // 用例 A：两条分支条件同时为真 → 排他，必须只产生 1 个分支任务
        Map<String, Object> a = new HashMap<>();
        a.put("contractType", "经营");
        a.put("amount", 800000);
        ProcessInstance pa = rt.startProcessInstanceById(pd.getId(), a);
        List<Task> first = ts.createTaskQuery().processInstanceId(pa.getId()).list();
        System.out.println("[用例A] 启动后任务 = " + names(first));
        if (first.size() != 1 || !"n_dept".equals(first.get(0).getTaskDefinitionKey())) {
            System.out.println("[用例A] **失败**：首个节点不是责任部门审批");
            failed++;
        }
        ts.complete(first.get(0).getId());
        List<Task> ta = ts.createTaskQuery().processInstanceId(pa.getId()).list();
        System.out.println("[用例A] 条件：contractType=经营 且 amount=800000（分支1、分支2 同时为真）");
        System.out.println("[用例A] 完成首节点后 任务数 = " + ta.size() + "  任务 = " + names(ta));
        if (ta.size() != 1) {
            System.out.println("[用例A] **失败**：排他语义被破坏，出现了 " + ta.size() + " 个任务");
            failed++;
        } else {
            System.out.println("[用例A] 通过：两条条件同时为真，只走一条分支");
        }

        // 用例 B：没有任何条件命中 → 必须落到「其他情况」兜底分支
        Map<String, Object> b = new HashMap<>();
        b.put("contractType", "行政");
        b.put("amount", 100);
        ProcessInstance pb = rt.startProcessInstanceById(pd.getId(), b);
        List<Task> firstB = ts.createTaskQuery().processInstanceId(pb.getId()).list();
        ts.complete(firstB.get(0).getId());
        List<Task> tb = ts.createTaskQuery().processInstanceId(pb.getId()).list();
        System.out.println("[用例B] 条件：contractType=行政 且 amount=100（无分支命中）");
        System.out.println("[用例B] 任务 = " + names(tb));
        boolean hitDefault = tb.size() == 1 && "n_admin".equals(tb.get(0).getTaskDefinitionKey());
        System.out.println(hitDefault ? "[用例B] 通过：落到 default 兜底分支" : "[用例B] **失败**：未落到兜底分支");
        if (!hitDefault) {
            failed++;
        }

        // 用例 C：分支任务完成后 → 应合流进入网关下游的固定节点
        if (ta.size() == 1) {
            ts.complete(ta.get(0).getId());
            List<Task> tc = ts.createTaskQuery().processInstanceId(pa.getId()).list();
            System.out.println("[用例C] 分支任务完成后 → 任务 = " + names(tc));
            boolean next = tc.size() == 1 && "n_legal".equals(tc.get(0).getTaskDefinitionKey());
            System.out.println(next ? "[用例C] 通过：合流后进入下一固定节点" : "[用例C] **失败**：合流异常");
            if (!next) {
                failed++;
            }
        }

        // 用例 D：节点扩展参数（flowable:properties）应能被读出来
        org.flowable.bpmn.model.BpmnModel model = rs.getBpmnModel(pd.getId());
        org.flowable.bpmn.model.FlowElement fe = model.getFlowElement("n_dept");
        Map<String, Object> ext = new HashMap<>();
        List<org.flowable.bpmn.model.ExtensionElement> props = fe.getExtensionElements().get("properties");
        if (props != null) {
            for (org.flowable.bpmn.model.ExtensionElement p : props) {
                List<org.flowable.bpmn.model.ExtensionElement> children = p.getChildElements().get("property");
                if (children != null) {
                    for (org.flowable.bpmn.model.ExtensionElement c : children) {
                        ext.put(c.getAttributeValue(null, "name"), c.getAttributeValue(null, "value"));
                    }
                }
            }
        }
        System.out.println("[用例D] n_dept 扩展参数 = " + ext);
        boolean extOk = "REQUIRED".equals(ext.get("signMode")) && "EACH_TIME".equals(ext.get("sameUserPolicy"));
        System.out.println(extOk ? "[用例D] 通过：扩展参数可被读回" : "[用例D] **失败**：扩展参数读不回");
        if (!extOk) {
            failed++;
        }

        System.out.println("----");
        System.out.println(failed == 0 ? "全部用例通过" : ("失败用例数 = " + failed));

        engine.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String names(List<Task> tasks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tasks.get(i).getTaskDefinitionKey()).append("(").append(tasks.get(i).getName()).append(")");
        }
        return sb.append("]").toString();
    }
}
