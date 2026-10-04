package com.ruoyi.workflow.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.biz.domain.CommonFlowSubmit;
import com.ruoyi.biz.domain.CommonForm;
import com.ruoyi.biz.service.abs.AbstractBizFlowSubmitData;
import com.ruoyi.flowable.domain.vo.FlowTaskVo;
import com.ruoyi.mq.api.ISyncPush;
import com.ruoyi.mq.enums.QueueEnum;
import com.ruoyi.todo.domain.Todo;
import com.ruoyi.todo.service.ITodoService;
import com.ruoyi.workflow.sign.guard.TaskSignGuard;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 动态表单数据相关业务实现 </p>
 *
 * @Author wocurr.com
 */
@Slf4j
@Service
public class DynamicFlowSubmitDataImpl extends AbstractBizFlowSubmitData {

    @Autowired
    private ISyncPush syncPush;
    @Autowired
    private TaskSignGuard taskSignGuard;
    @Autowired
    private ITodoService todoService;

    @Override
    public String getBizType() {
        return "dynamic";
    }

    @Override
    public void buildBizFlowData(CommonFlowSubmit submit) {
        log.info("动态表单提交");
    }

    @Override
    public void beforeSubmit(CommonFlowSubmit commFlowSubmit) {
        log.info("动态表单提交前处理");
        // AC-26：节点要求「必须签名」时，未签名必须在**同步段**就被拒绝。
        // 任务的真正完成是经 RabbitMQ 异步消费的（submitFlowTaskBySync → FlowAsyncService），
        // 若把校验只放在消费端，用户在提交那一刻已经收到「提交成功」，异常只会落进日志，
        // 表现为「提示成功但单据没往下走」。父类的调用顺序正是
        //   handleBizFlowData(映射 operateType) → beforeSubmit(本方法) → submitFlowTaskBySync(推 MQ)
        // 所以这里是同步拦截的唯一正确位置。
        FlowTaskVo flowTask = commFlowSubmit.getFlowTask();
        if (flowTask == null || !TaskSignGuard.OPERATE_COMPLETE.equals(flowTask.getOperateType())) {
            // 只有「同意 / 完成任务」需要签名；驳回、退回、撤回都不校验
            return;
        }
        taskSignGuard.requireSigned(flowTask.getTaskId(), resolveBusinessId(flowTask));
    }

    /**
     * 取 businessId。
     *
     * <p> 正常由前端随请求传入；缺省时回退到该任务的待办记录 ——
     * 父类 {@code submitFlowTaskBySync} 也是这么兜的，但那段发生在本方法**之后**，
     * 所以这里必须自己兜一次，否则校验会因为 businessId 为空而被跳过。 </p>
     */
    private String resolveBusinessId(FlowTaskVo flowTask) {
        if (StringUtils.isNotBlank(flowTask.getBusinessId())) {
            return flowTask.getBusinessId();
        }
        List<Todo> todos = todoService.listTodoByTaskId(flowTask.getTaskId());
        return CollectionUtils.isEmpty(todos) ? null : todos.get(0).getBusinessId();
    }

    @Override
    public void afterSubmit(CommonFlowSubmit commFlowSubmit) {
        log.info("动态表单提交后处理");
    }
}
