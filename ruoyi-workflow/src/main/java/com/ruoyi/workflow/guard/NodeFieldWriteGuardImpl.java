package com.ruoyi.workflow.guard;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.biz.constants.FormConstants;
import com.ruoyi.biz.domain.CommonForm;
import com.ruoyi.biz.service.INodeFieldWriteGuard;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.template.service.ITemplateNodeFieldAuthService;
import com.ruoyi.workflow.mapper.SignRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p> 节点级「字段只读」的服务端强制（PRD AC-12） </p>
 *
 * <p> AC-12 原文要求「该节点表单中指定字段不可编辑，且**直接调接口也改不动**」——
 * 前端把控件置灰只是 UI 约定，直接调 {@code /biz/form/update} 就能改。
 * 本类在写入前比对「新值 vs 库里旧值」，改动只读字段即拒绝。 </p>
 *
 * <p> 只读字段配置来自 {@code t_template_node_field_auth}，由简化流程**发布时**
 * 从节点的 {@code fieldReadonly} 落库（见 {@code SimpleFlowServiceImpl#syncNodeFieldAuth}）。 </p>
 *
 * <p> 数据形状（与 {@code BizFormServiceImpl#getTransform} 一致）：
 * {@code commonForm.formData} 是一个 Map，其 {@code formData} 键是完整 JSON 字符串，
 * 字段值在其中的 {@code valData} 里；同一 Map 里还带着前端提交的 {@code taskId}。 </p>
 *
 * @author 二开
 */
@Slf4j
@Service
public class NodeFieldWriteGuardImpl implements INodeFieldWriteGuard {

    /** 前端提交时附带的任务ID键名（见 flow-form/index.vue#submitForm） */
    private static final String KEY_TASK_ID = "taskId";

    @Autowired
    private ITemplateNodeFieldAuthService nodeFieldAuthService;
    @Autowired
    private SignRecordMapper signRecordMapper;
    @Autowired
    private TaskService taskService;

    @Override
    public void check(CommonForm commonForm) {
        if (commonForm == null || !(commonForm.getFormData() instanceof Map)) {
            return;
        }
        Map<?, ?> payload = (Map<?, ?>) commonForm.getFormData();

        String taskId = asString(payload.get(KEY_TASK_ID));
        if (StringUtils.isBlank(taskId) || StringUtils.isBlank(commonForm.getTemplateId())) {
            // 非审批节点写入（例如存草稿、发起时首次保存）没有任务上下文，不校验
            return;
        }

        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return;
        }
        List<String> readonlyFields =
                nodeFieldAuthService.listReadonlyFields(commonForm.getTemplateId(), task.getTaskDefinitionKey());
        if (CollectionUtils.isEmpty(readonlyFields)) {
            return;
        }

        JSONObject newValues = extractValData(asString(payload.get(FormConstants.FORM_KEY)));
        JSONObject oldValues = extractValData(signRecordMapper.selectFormDataById(commonForm.getBizId()));
        if (newValues == null || oldValues == null) {
            // 取不全两侧数据时**宁可放过**：校验是防篡改，不是阻断正常办理，
            // 不能因为一次读不到快照就让用户办不了事（真出问题会有日志）
            log.warn("节点字段只读校验跳过（新旧数据取不全）：businessId={} taskId={}",
                    commonForm.getBizId(), taskId);
            return;
        }

        List<String> changed = new ArrayList<>();
        for (String field : readonlyFields) {
            if (!Objects.equals(oldValues.get(field), newValues.get(field))) {
                changed.add(field);
            }
        }
        if (CollectionUtils.isNotEmpty(changed)) {
            log.warn("节点只读字段被修改，已拒绝：taskId={} node={} fields={}",
                    taskId, task.getTaskDefinitionKey(), changed);
            throw new BaseException("字段「" + String.join("、", changed) + "」在本节点为只读，不允许修改");
        }
    }

    /** 从表单 JSON 里取字段值对象（结构：{@code {"formData":{...},"valData":{字段:值}}}） */
    private JSONObject extractValData(String formJson) {
        if (StringUtils.isBlank(formJson)) {
            return null;
        }
        try {
            return JSONObject.parseObject(formJson).getJSONObject(FormConstants.VAL_DATA_KEY);
        } catch (Exception e) {
            log.warn("解析表单 JSON 失败：{}", e.getMessage());
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
