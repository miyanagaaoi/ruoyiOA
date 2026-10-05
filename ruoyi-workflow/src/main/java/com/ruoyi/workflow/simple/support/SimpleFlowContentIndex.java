package com.ruoyi.workflow.simple.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.workflow.simple.model.SimpleFlowDef;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <p> 简化流程内容 JSON 的<b>只读索引</b>：{@code formId} + 条件字段 + 并行分支字段。 </p>
 *
 * <p> 为什么要单独一层：{@link SimpleFlowDef} 只建模"能被编译成 BPMN 的部分"，
 * {@code content.formId}（关联的动态表单版本）**不在其中**，而"模板 form_id ↔ 流程 formId"
 * 的一致性检查、以及 PRD V-8 反向校验都恰恰要看这个字段。 </p>
 *
 * <p> 解析失败不抛异常：{@link #isParsed()} 为 false，调用方自行决定"跳过并告警"，
 * 不能因为一条坏草稿把巡检/保存链路打断。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public final class SimpleFlowContentIndex {

    /** 流程内容里的"关联表单版本 id"字段名 */
    public static final String FIELD_FORM_ID = "formId";

    private final boolean parsed;
    private String formId;
    private final LinkedHashSet<String> conditionFields = new LinkedHashSet<>();
    private final LinkedHashSet<String> multiFields = new LinkedHashSet<>();

    private SimpleFlowContentIndex(boolean parsed) {
        this.parsed = parsed;
    }

    /** 解析流程内容；任何异常都退化为"未解析" */
    public static SimpleFlowContentIndex parse(String content) {
        if (StringUtils.isBlank(content)) {
            return new SimpleFlowContentIndex(false);
        }
        SimpleFlowContentIndex idx = new SimpleFlowContentIndex(true);
        try {
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                return new SimpleFlowContentIndex(false);
            }
            idx.formId = root.getString(FIELD_FORM_ID);
            SimpleFlowDef def = root.toJavaObject(SimpleFlowDef.class);
            if (def != null && def.getNodes() != null) {
                for (SimpleFlowDef.Node n : def.getNodes()) {
                    idx.collect(n);
                }
            }
        } catch (Exception e) {
            return new SimpleFlowContentIndex(false);
        }
        return idx;
    }

    /** 递归收集节点（条件分支内部的节点也要看，它们同样可能带并行分支） */
    private void collect(SimpleFlowDef.Node n) {
        if (n == null) {
            return;
        }
        if (SimpleFlowDef.T_CONDITION.equals(n.getType()) && n.getBranches() != null) {
            for (SimpleFlowDef.Branch b : n.getBranches()) {
                if (b == null || b.isDefaultBranch() || b.getGroups() == null) {
                    continue;
                }
                for (SimpleFlowDef.Group g : b.getGroups()) {
                    if (g == null || g.getRows() == null) {
                        continue;
                    }
                    for (SimpleFlowDef.Row r : g.getRows()) {
                        if (r != null && StringUtils.isNotBlank(r.getField())) {
                            conditionFields.add(r.getField());
                        }
                    }
                }
            }
        }
        if (SimpleFlowDef.T_PARALLEL.equals(n.getType()) && StringUtils.isNotBlank(n.getFormField())) {
            multiFields.add(n.getFormField());
        }
        if (n.getBranches() != null) {
            for (SimpleFlowDef.Branch b : n.getBranches()) {
                if (b == null || b.getNodes() == null) {
                    continue;
                }
                for (SimpleFlowDef.Node sub : b.getNodes()) {
                    collect(sub);
                }
            }
        }
    }

    public boolean isParsed() {
        return parsed;
    }

    public String getFormId() {
        return formId;
    }

    /** 条件分支引用到的字段（去重、保持出现顺序） */
    public List<String> getConditionFields() {
        return new ArrayList<>(conditionFields);
    }

    /** 并行分支引用的表单多选字段（去重） */
    public List<String> getMultiFields() {
        return new ArrayList<>(multiFields);
    }

    public Set<String> allReferencedFields() {
        Set<String> all = new LinkedHashSet<>(conditionFields);
        all.addAll(multiFields);
        return all;
    }
}
