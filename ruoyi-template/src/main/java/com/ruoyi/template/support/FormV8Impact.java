package com.ruoyi.template.support;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * <p> 表单保存的影响面（PRD V-8 反向校验结果）。 </p>
 *
 * <p> 两类要分开，口径严格按 PRD V-8 原文（"字段被删/改为非必填 → 阻断并列出受影响的流程"）： </p>
 * <ul>
 *   <li> {@link #blocking}：**已发布**流程引用了这张表单，而这次保存会让它的条件字段
 *        （或并行分支的表单多选字段）在新版本里**不存在或不再必填** ⇒ 必须阻断表单保存； </li>
 *   <li> {@link #warnings}：换版本后流程**不会自动跟随**（模板会被后端改指到新版本、
 *        流程 content.formId 仍是旧的），但字段本身还成立 ⇒ 保存放行，但必须**明确告警**
 *        （提前把"界面显示 A、校验按 B"的错位点出来，不让它静默发生）。 </li>
 * </ul>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Data
public class FormV8Impact implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 阻断项（PRD V-8：字段被删 / 改为非必填，且流程已发布） */
    private final List<Item> blocking = new ArrayList<>();

    /** 告警项（字段仍有效，但流程会停留在旧版本表单上 → 错位） */
    private final List<Item> warnings = new ArrayList<>();

    public void addBlocking(Item item) {
        blocking.add(item);
    }

    public void addWarning(Item item) {
        warnings.add(item);
    }

    /** 是否需要阻断本次表单保存 */
    public boolean isBlocked() {
        return !blocking.isEmpty();
    }

    public boolean isEmpty() {
        return blocking.isEmpty() && warnings.isEmpty();
    }

    /** 阻断原因（给 ServiceException 用：必须"列出受影响的流程"） */
    public String describe() {
        StringBuilder sb = new StringBuilder("表单保存被阻断（PRD V-8 反向校验）：本次保存会让 ");
        sb.append(blocking.size()).append(" 个已发布流程的条件字段失效");
        for (Item it : blocking) {
            sb.append("；流程「").append(it.getFlowName()).append("」(").append(it.getDefKey())
                    .append(") 条件字段 ").append(String.join("/", it.getFields()))
                    .append(" —— ").append(it.getReason());
        }
        sb.append("。请先改这些流程的分支条件（或把字段保留为必填）后再保存表单。");
        return sb.toString();
    }

    /** 一条影响记录 */
    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** t_flow_simple.id */
        private String flowId;
        private String defKey;
        private String flowName;
        /** 是否已发布 */
        private boolean published;
        /** 涉及的字段（条件字段 / 并行分支字段） */
        private List<String> fields = new ArrayList<>();
        /** 原因（人话，直接给界面/日志用） */
        private String reason;

        public static Item of(String flowId, String defKey, String flowName, boolean published,
                              List<String> fields, String reason) {
            Item it = new Item();
            it.flowId = flowId;
            it.defKey = defKey;
            it.flowName = flowName;
            it.published = published;
            it.fields = fields == null ? new ArrayList<>() : fields;
            it.reason = reason;
            return it;
        }
    }
}
