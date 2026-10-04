package com.ruoyi.workflow.simple.listener;

import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> 多实例参与人列表的「启动注入」监听器 </p>
 *
 * <p> 简化流程编译器为「会签 / 或签 / 依次」生成的是**多实例用户任务**，
 * 其集合来源是一个按节点 id 命名的流程变量（{@code {nodeId}_approval}）。
 * 而本项目运行时**并不产生**这个变量 —— 于是节点一旦到达就抛
 * {@code Variable 'xxx_approval' was not found}：
 * 该节点位于首位时连流程都启动不了，位于后面时上一节点提交后异步处理失败。 </p>
 *
 * <p> 本监听器挂在 {@code <process>} 的 <b>start</b> 事件上，在流程启动时
 * 一次性把所有多实例节点的参与人列表写成流程变量。挂在流程启动而不是节点上，
 * 是因为**首节点为会签时没有任何"上一节点"可以触发注入**。 </p>
 *
 * <p> 参与人列表由编译器写进 {@code flowable:field name="vars"}，格式为
 * {@code 节点id=用户1,用户2;节点id2=用户3}（节点 id 与用户 id 都不含
 * {@code ; = ,} 这三个分隔符）。 </p>
 *
 * @author 二开
 */
public class ApprovalUsersInitListener implements ExecutionListener {

    private static final long serialVersionUID = 1L;

    /** 形如 {@code "n1=u1,u2;n2=u3"}；由 Flowable 按 flowable:field 注入 */
    private Expression vars;

    /** 列表分隔符：节点之间 */
    private static final String PAIR_SEP = ";";
    /** 节点 id 与用户列表之间 */
    private static final char KV_SEP = '=';
    /** 用户之间 */
    private static final String USER_SEP = ",";

    @Override
    public void notify(DelegateExecution execution) {
        String raw = vars == null ? null : String.valueOf(vars.getValue(execution));
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String pair : raw.split(PAIR_SEP)) {
            int i = pair.indexOf(KV_SEP);
            if (i <= 0 || i == pair.length() - 1) {
                continue;
            }
            String nodeId = pair.substring(0, i).trim();
            String users = pair.substring(i + 1).trim();
            if (nodeId.isEmpty() || users.isEmpty()) {
                continue;
            }
            List<String> list = new ArrayList<>();
            for (String u : users.split(USER_SEP)) {
                String v = u.trim();
                if (!v.isEmpty()) {
                    list.add(v);
                }
            }
            if (!list.isEmpty()) {
                // 多实例的 flowable:collection 就是读这个名字
                execution.setVariable(nodeId + "_approval", list);
            }
        }
    }

    public void setVars(Expression vars) {
        this.vars = vars;
    }
}
