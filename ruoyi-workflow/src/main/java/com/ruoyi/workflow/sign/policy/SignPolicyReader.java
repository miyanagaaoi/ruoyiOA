package com.ruoyi.workflow.sign.policy;

import com.ruoyi.flowable.utils.FlowableUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 节点级签名策略的**唯一读取点**（PRD 8.2 / 10.2-B、AC-25） </p>
 *
 * <p> 设计器为节点配置的签名要求与允许方式，由编译器 {@code SimpleFlowCompiler}
 * 写进 BPMN 的 {@code <flowable:properties>}（{@code signMode / signTypes}）。
 * 读回它的地方有两处，必须口径一致： </p>
 * <ol>
 *   <li> 提交时的服务端拦截 {@code TaskSignGuard}（"必需签名"）； </li>
 *   <li> 前端签名弹窗要知道"这个节点允许手写还是预存"（{@code GET /workflow/sign/policy}）。 </li>
 * </ol>
 *
 * <p> <b>为什么不用 {@code Map<String,Object>} 直接对外</b>：调用方各自 {@code get()} 再自己
 * 判空、自己给默认值，迟早出现"一处默认 OPTIONAL、另一处默认 NONE"。默认值只在这里给一次。 </p>
 *
 * <p> <b>读不到时一律按"未配置"处理，绝不抛异常</b>：这是辅助信息 ——
 * 任务已被并发处理掉、模型读不出来都不该让用户签不了名或提交不了单。 </p>
 *
 * @author 二开
 */
@Slf4j
@Component
public class SignPolicyReader {

    /** 节点扩展属性名：签名要求 */
    public static final String EXT_SIGN_MODE = "signMode";
    /** 节点扩展属性名：允许的签名方式（逗号分隔） */
    public static final String EXT_SIGN_TYPES = "signTypes";

    /** 不需要签名 */
    public static final String MODE_NONE = "NONE";
    /** 可选签名（默认） */
    public static final String MODE_OPTIONAL = "OPTIONAL";
    /** 必须签名 */
    public static final String MODE_REQUIRED = "REQUIRED";

    /** 手写签名 */
    public static final String TYPE_HANDWRITE = "HANDWRITE";
    /** 预存签名 */
    public static final String TYPE_PRESET = "PRESET";

    /** PRD 8.2 默认值：允许方式 = 手写 + 预存 */
    private static final List<String> DEFAULT_TYPES =
            new ArrayList<>(Arrays.asList(TYPE_HANDWRITE, TYPE_PRESET));

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    /**
     * 读任务所在节点的全部扩展属性。
     *
     * @param taskId 任务ID
     * @return 扩展属性；任务不存在 / 模型读不到 / 异常时返回**空 Map**（不是 null）
     */
    public Map<String, Object> readExtendVars(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return new HashMap<>();
        }
        try {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                return new HashMap<>();
            }
            BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
            if (bpmnModel == null) {
                return new HashMap<>();
            }
            Map<String, Object> extendVars =
                    FlowableUtil.getExtendVarByTaskDefinitionKey(bpmnModel, task.getTaskDefinitionKey());
            return extendVars == null ? new HashMap<>() : extendVars;
        } catch (Exception e) {
            log.warn("读取节点签名策略失败，按未配置处理：taskId={} err={}", taskId, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 签名要求：{@code REQUIRED / OPTIONAL / NONE}；未配置返回 {@code OPTIONAL}（PRD 8.2 默认值）。
     */
    public String resolveSignMode(String taskId) {
        Object value = readExtendVars(taskId).get(EXT_SIGN_MODE);
        String mode = value == null ? null : String.valueOf(value);
        return StringUtils.isBlank(mode) ? MODE_OPTIONAL : mode.trim().toUpperCase();
    }

    /**
     * 允许的签名方式；未配置按 PRD 8.2 默认值给「手写 + 预存」。
     *
     * @return 至少一项；值已转大写去空格，顺序即配置顺序
     */
    public List<String> resolveSignTypes(String taskId) {
        Object value = readExtendVars(taskId).get(EXT_SIGN_TYPES);
        String raw = value == null ? null : String.valueOf(value);
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>(DEFAULT_TYPES);
        }
        List<String> types = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (StringUtils.isNotBlank(s)) {
                types.add(s.trim().toUpperCase());
            }
        }
        return types.isEmpty() ? new ArrayList<>(DEFAULT_TYPES) : types;
    }

    /**
     * 供前端消费的策略视图（{@code GET /workflow/sign/policy}）。
     *
     * <p> 字段名与 BPMN 扩展属性保持一致，前端不必做二次翻译。 </p>
     *
     * @return {signMode, signTypes[], signRequired}
     */
    public Map<String, Object> describe(String taskId) {
        Map<String, Object> extendVars = readExtendVars(taskId);
        String mode = asMode(extendVars.get(EXT_SIGN_MODE));
        List<String> types = asTypes(extendVars.get(EXT_SIGN_TYPES));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("signMode", mode);
        view.put("signTypes", types);
        // 让前端不必自己比对字符串大小写（"必需时未签名不能提交"的提示要用）
        view.put("signRequired", MODE_REQUIRED.equals(mode));
        return view;
    }

    private String asMode(Object value) {
        String mode = value == null ? null : String.valueOf(value);
        return StringUtils.isBlank(mode) ? MODE_OPTIONAL : mode.trim().toUpperCase();
    }

    private List<String> asTypes(Object value) {
        String raw = value == null ? null : String.valueOf(value);
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>(DEFAULT_TYPES);
        }
        List<String> types = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (StringUtils.isNotBlank(s)) {
                types.add(s.trim().toUpperCase());
            }
        }
        return types.isEmpty() ? new ArrayList<>(DEFAULT_TYPES) : types;
    }
}
