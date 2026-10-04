package com.ruoyi.workflow.sign.guard;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.flowable.utils.FlowableUtil;
import com.ruoyi.workflow.sign.service.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <p> 节点级「必须签名」的**服务端强制**（PRD AC-26） </p>
 *
 * <p> 设计器为节点配置的签名要求写在 BPMN 的 {@code <flowable:properties>} 里
 * （编译器 {@code SimpleFlowCompiler} 写出 {@code signMode}），
 * 本类在提交时把它读回来做校验。 </p>
 *
 * <p> <b>为什么必须有它</b>：前端在「必需签名」时会把提交按钮挡住，
 * 但那只是 UI 约定 —— 直接调 {@code /biz/flow/submit} 就能绕过。
 * AC-26 明确要求「伪造请求也被服务端拒绝」，故校验落在服务端。 </p>
 *
 * <p> <b>为什么放在同步路径上</b>：任务的真正完成是经 RabbitMQ 异步消费的
 * （{@code FlowAsyncService} → {@code completeTask}）。若把校验只放在消费端，
 * 用户在提交那一刻已经收到「提交成功」，异常只会落进日志 —— 表现为
 * 「提示成功但单据没动」。因此调用方必须在**推 MQ 之前**的同步段调用本类。 </p>
 *
 * @author 二开
 */
@Slf4j
@Component
public class TaskSignGuard {

    /** 节点扩展属性名：签名要求 */
    public static final String EXT_SIGN_MODE = "signMode";
    /** 必须签名 */
    public static final String SIGN_REQUIRED = "REQUIRED";
    /** 流程任务操作类型：完成任务（{@code FLowOperateTypeEnum.COMPLETE}） */
    public static final String OPERATE_COMPLETE = "2";

    @Autowired
    private ISignService signService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private RepositoryService repositoryService;

    /**
     * 本节点要求必须签名、而当前任务尚无有效签名时，抛 {@link BaseException}。
     *
     * <p> 只应在「同意 / 完成任务」时调用；驳回、退回、撤回都不需要签名。 </p>
     *
     * @param taskId     当前任务ID
     * @param businessId 业务ID（与 Flowable 的 businessKey 同值）
     */
    public void requireSigned(String taskId, String businessId) {
        if (StringUtils.isBlank(taskId) || StringUtils.isBlank(businessId)) {
            // 缺参由各调用方自己的参数校验负责报错，这里不越权抛异常
            return;
        }
        String signMode = resolveSignMode(taskId);
        if (!SIGN_REQUIRED.equalsIgnoreCase(signMode)) {
            return;
        }
        if (signService.hasEffectiveSign(businessId, taskId)) {
            return;
        }
        log.warn("节点要求签名但未签名，已拒绝提交：taskId={} businessId={}", taskId, businessId);
        throw new BaseException("本节点要求签名后才能提交，请先完成签名");
    }

    /**
     * 读任务所在节点的 {@code signMode} 扩展属性。
     *
     * <p> 任务已不存在（例如被并发处理掉）或读不到模型时返回 null，
     * 交由调用方按「未配置签名要求」处理 —— 不能因为读不到就把正常提交挡住。 </p>
     */
    private String resolveSignMode(String taskId) {
        try {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                return null;
            }
            BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
            if (bpmnModel == null) {
                return null;
            }
            Map<String, Object> extendVars =
                    FlowableUtil.getExtendVarByTaskDefinitionKey(bpmnModel, task.getTaskDefinitionKey());
            if (extendVars == null) {
                return null;
            }
            Object value = extendVars.get(EXT_SIGN_MODE);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            // 读扩展属性属于辅助校验：异常时不应阻断正常审批，但要留下日志便于排查
            log.warn("读取节点签名的配置失败，按未配置处理：taskId={} err={}", taskId, e.getMessage());
            return null;
        }
    }
}
