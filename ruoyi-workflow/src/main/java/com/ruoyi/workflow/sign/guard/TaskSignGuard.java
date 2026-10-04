package com.ruoyi.workflow.sign.guard;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.workflow.sign.policy.SignPolicyReader;
import com.ruoyi.workflow.sign.service.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    /** 节点扩展属性名：签名要求（读取口径统一在 {@link SignPolicyReader}） */
    public static final String EXT_SIGN_MODE = SignPolicyReader.EXT_SIGN_MODE;
    /** 必须签名 */
    public static final String SIGN_REQUIRED = SignPolicyReader.MODE_REQUIRED;
    /** 流程任务操作类型：完成任务（{@code FLowOperateTypeEnum.COMPLETE}） */
    public static final String OPERATE_COMPLETE = "2";

    @Autowired
    private ISignService signService;
    @Autowired
    private SignPolicyReader signPolicyReader;

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
     * <p> 读取与默认值的口径统一在 {@link SignPolicyReader} —— 前端拿"允许方式"、
     * 服务端判"是否必需"必须看同一份解释，否则会出现"界面说不需要、服务端说必需"。
     * 未配置时给 {@code OPTIONAL}，不会把正常提交挡住。 </p>
     */
    private String resolveSignMode(String taskId) {
        return signPolicyReader.resolveSignMode(taskId);
    }
}
