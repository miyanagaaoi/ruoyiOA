package com.ruoyi.template.support;

import com.ruoyi.template.spi.FlowFormUsage;
import com.ruoyi.template.spi.FlowFormUsageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> <b>PRD V-8 反向校验</b>：动态表单保存前，算出"这次保存会让哪些流程的条件字段失效 / 错位"。 </p>
 *
 * <p> 缺陷背景（{@code doc/缺陷-流程条件字段与关联表单错位.md} §4.5）：表单保存 = 停用旧版本行 +
 * 插一条新版本行（换 id），后端会把模板 {@code t_template.form_id} 改指到新版本，
 * 但**不会**改 {@code t_flow_simple.content} 里的 {@code formId} ⇒ "模板跟着新版走、流程留在旧版"，
 * 运行期条件字段找不到（V-8 误报 / 提交报错）。当时靠人工对齐数据修掉，代码层没有防线。 </p>
 *
 * <p> <b>判定口径</b>（只读，不写库；{@code updateTemplateDynamicForm} 调用它并在阻断时抛错）： </p>
 * <ol>
 *   <li> 用 {@link FormFieldIndex} 解析**本次提交的新内容**，得到"存在且必填"的字段集合； </li>
 *   <li> 反查所有 {@code content.formId == 旧版本id} 的流程（{@link FlowFormUsageProvider}）； </li>
 *   <li> 流程的条件字段（{@code nodes[].branches[].groups[].rows[].field}）或并行分支字段
 *        （{@code nodes[].formField}）在新内容里**不存在 / 未设为必填** ⇒ 按 PRD V-8 列为阻断项； </li>
 *   <li> 字段仍然成立的流程 ⇒ 列为告警项（保存后它会与新版本错位，必须让管理员看见）。 </li>
 * </ol>
 *
 * <p> <b>误报边界（有意为之，别当 bug）</b>： </p>
 * <ul>
 *   <li> <b>草稿流程不阻断</b>（只告警）：{@code status != 1} 的流程还没发布，PRD V-8 的"阻断并列出
 *        受影响的流程"针对的是已生效的流程；草稿本来就允许改。 </li>
 *   <li> <b>新内容解析失败 → 不做字段级判定</b>：只发一条告警（"内容无法解析，未做字段级校验"），
 *        否则一次坏 JSON 会误伤所有流程。 </li>
 *   <li> <b>容器里没有 {@link FlowFormUsageProvider} → 校验整体跳过</b>（WARN 日志），
 *        这样 ruoyi-template 单独装配/单测时不会因为缺 workflow 而保存不了表单。 </li>
 *   <li> <b>不校验"模板是否绑定该流程"</b>：这条链路由巡检接口
 *        {@code GET /workflow/simple-flow/form-consistency} 负责（见 {@code FormConsistencyChecker}）。 </li>
 * </ul>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Slf4j
@Component
public class FormV8Guard {

    /** 容器里可能没有实现（模块化/单测场景） */
    @Autowired(required = false)
    private FlowFormUsageProvider flowFormUsageProvider;

    /**
     * 只读分析：这次"用 {@code newContent} 替换表单 {@code oldFormId}"会影响哪些流程。
     *
     * @param oldFormId  即将被替换的表单版本 id（= 当前行 id）
     * @param newContent 本次提交的新表单内容
     * @return 影响面（永不返回 null）
     */
    public FormV8Impact analyze(String oldFormId, String newContent) {
        FormV8Impact impact = new FormV8Impact();
        if (StringUtils.isBlank(oldFormId)) {
            return impact;
        }
        if (flowFormUsageProvider == null) {
            log.warn("PRD V-8 反向校验跳过：容器里没有 FlowFormUsageProvider（ruoyi-workflow 未装配），"
                    + "oldFormId={}", oldFormId);
            return impact;
        }

        FormFieldIndex index = FormFieldIndex.parse(newContent);
        List<FlowFormUsage> usages;
        try {
            usages = flowFormUsageProvider.listByFormId(oldFormId);
        } catch (Exception e) {
            // 反查失败绝不能把"保存表单"连带打断——但要留痕，别静默
            log.warn("PRD V-8 反向校验跳过：反查表单使用方失败（oldFormId={}）：{}", oldFormId, e.getMessage());
            return impact;
        }
        if (usages == null || usages.isEmpty()) {
            return impact;
        }
        if (!index.isParsed()) {
            log.warn("PRD V-8 反向校验：新表单内容无法解析，未做字段级校验（oldFormId={}，涉及 {} 个流程）",
                    oldFormId, usages.size());
            for (FlowFormUsage u : usages) {
                impact.addWarning(FormV8Impact.Item.of(u.getFlowId(), u.getDefKey(), u.getFlowName(),
                        u.isPublished(), new ArrayList<String>(),
                        "新表单内容无法解析，未做字段级校验；保存后本流程仍指向旧版本表单"));
            }
            return impact;
        }

        for (FlowFormUsage u : usages) {
            List<String> broken = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (String field : safe(u.getConditionFields())) {
                if (!index.isRequired(field)) {
                    broken.add(field);
                    reasons.add("条件字段「" + index.labelOf(field) + "」" + (index.contains(field)
                            ? "在新版本里未设为必填" : "在新版本里不存在"));
                }
            }
            for (String field : safe(u.getMultiFields())) {
                if (!index.contains(field)) {
                    broken.add(field);
                    reasons.add("并行分支字段「" + index.labelOf(field) + "」在新版本里不存在");
                }
            }
            String who = StringUtils.defaultIfBlank(u.getFlowName(), u.getDefKey());
            if (broken.isEmpty()) {
                impact.addWarning(FormV8Impact.Item.of(u.getFlowId(), u.getDefKey(), who, u.isPublished(),
                        new ArrayList<String>(),
                        "本流程 content.formId 指向即将被替换的旧版本表单，保存后不会自动跟随"
                                + "（模板会改指新版本）——请在设计器重新保存/发布该流程"));
                continue;
            }
            String detail = String.join("；", reasons);
            if (u.isPublished()) {
                impact.addBlocking(FormV8Impact.Item.of(u.getFlowId(), u.getDefKey(), who, true,
                        broken, detail));
            } else {
                impact.addWarning(FormV8Impact.Item.of(u.getFlowId(), u.getDefKey(), who, false,
                        broken, detail + "（该流程尚未发布，故只告警不阻断）"));
            }
        }
        if (impact.isBlocked()) {
            log.warn("PRD V-8 反向校验阻断表单保存：oldFormId={} 影响 {} 个已发布流程：{}",
                    oldFormId, impact.getBlocking().size(), impact.describe());
        } else if (!impact.getWarnings().isEmpty()) {
            log.warn("PRD V-8 反向校验：表单保存放行，但有 {} 个流程将与新版本错位：oldFormId={}",
                    impact.getWarnings().size(), oldFormId);
        }
        return impact;
    }

    private static List<String> safe(List<String> in) {
        return in == null ? new ArrayList<String>() : in;
    }
}
