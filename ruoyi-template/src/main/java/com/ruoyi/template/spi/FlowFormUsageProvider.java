package com.ruoyi.template.spi;

import java.util.List;

/**
 * <p> 「按表单版本 id 反查：还有哪些流程在用这张表单」—— PRD V-8 反向校验的数据来源。 </p>
 *
 * <p> <b>为什么用接口 + 实现分离</b>：流程表 {@code t_flow_simple} 与它的解析口径
 * （{@code SimpleFlowDef}、{@code content.formId}）都属于 {@code ruoyi-workflow}；
 * 而表单保存入口在 {@code ruoyi-template}。模块依赖是 {@code ruoyi-workflow → ruoyi-template}
 * （SimpleFlowServiceImpl 已经 import 了 ITemplateService），反向直接调用会形成循环依赖，
 * 所以这里用"接口在 template、实现在 workflow"做倒置。 </p>
 *
 * <p> 容器里没有实现（例如只装了 template 模块做单测）时**不阻断**表单保存，
 * 但必须留下 WARN 日志 —— 见 {@code FormV8Guard}。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public interface FlowFormUsageProvider {

    /**
     * 列出 {@code content.formId} 等于该表单版本 id 的流程（含草稿与已发布，不含已删除）。
     *
     * @param formId 表单版本 id（t_template_dynamic_form.id）
     * @return 使用方清单；没有命中返回空列表（**不返回 null**）
     */
    List<FlowFormUsage> listByFormId(String formId);
}
