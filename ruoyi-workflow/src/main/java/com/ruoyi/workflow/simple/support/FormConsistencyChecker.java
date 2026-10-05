package com.ruoyi.workflow.simple.support;

import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.mapper.TemplateDynamicFormMapper;
import com.ruoyi.template.mapper.TemplateMapper;
import com.ruoyi.template.support.FormFieldIndex;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.mapper.FlowSimpleMapper;
import com.ruoyi.workflow.simple.model.FormConsistencyIssue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p> <b>「模板 form_id ↔ 流程 content.formId」一致性巡检</b>（HANDOFF §11 第 8 行 / 缺陷文档 §7-B.1）。 </p>
 *
 * <p> <b>为什么要有它</b>：动态表单保存 = 停用旧版本行 + 插新行（换 id），后端只把
 * {@code t_template.form_id} 改指到新版本（{@code TemplateMapper.xml#repointFormId}），
 * 而 {@code t_flow_simple.content.formId} **不迁移** ⇒ 模板跟着新版走、流程留在旧版，
 * 运行期"界面显示 A、校验按 B"（V-8 误报 / 提交报错）。此前只能靠人工对齐数据，代码里没有任何检查。 </p>
 *
 * <p> <b>为什么做成"巡检接口"而不是"发布前阻断"</b>： </p>
 * <ol>
 *   <li> 错位是**表单保存那一刻**造成的，不是发布那一刻 —— 在发布链路里拦，既拦不住源头，
 *        又会让存量流程（本就指向停用/软删表单的历史数据）无法重新发布，属于"用误报换安全感"； </li>
 *   <li> 巡检接口是**只读**的，可以随时跑（发布前 / 门禁 / 排障），不会因为一条历史脏数据把业务卡死； </li>
 *   <li> 源头那一侧由 PRD V-8 反向校验负责**阻断**（{@code FormV8Guard}，表单保存路径），
 *        两者一个治源头、一个扫存量，口径都落在同一份数据上。 </li>
 * </ol>
 *
 * <p> <b>误报边界（有意为之）</b>： </p>
 * <ul>
 *   <li> 只扫**未删除**的流程（{@code t_flow_simple.del_flag='0'}）与**未删除**的模板； </li>
 *   <li> 流程若没有任何模板绑定（既没有 {@code simple_flow_id} 也没有同 {@code def_key} 的模板），
 *        不会产生 MISMATCH —— 独立发布的流程"模板可后补"是合法状态（{@code SimpleFlowServiceImpl#syncNodeFieldAuth} 同口径）； </li>
 *   <li> 已停用（{@code enable_flag=0}）的模板只报 medium/low：它不参与发起，
 *        不会立刻报错，属"该清理的存量"而非"正在流血"； </li>
 *   <li> 草稿流程（{@code status=0}）同样只降级报告（草稿本来就允许与表单不一致）。 </li>
 * </ul>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Slf4j
@Component
public class FormConsistencyChecker {

    @Autowired
    private FlowSimpleMapper flowSimpleMapper;
    @Autowired
    private TemplateMapper templateMapper;
    @Autowired
    private TemplateDynamicFormMapper templateDynamicFormMapper;

    /** 巡检结果（含汇总，便于门禁直接看 highCount） */
    public static class Report {
        private final List<FormConsistencyIssue> issues = new ArrayList<>();
        private int scannedFlows;
        private int scannedTemplates;

        public List<FormConsistencyIssue> getIssues() {
            return issues;
        }

        public int getScannedFlows() {
            return scannedFlows;
        }

        public int getScannedTemplates() {
            return scannedTemplates;
        }

        /** 高严重度条数（门禁要接就接这一条：0 = 没有"正在流血"的错位） */
        public int getHighCount() {
            return count(FormConsistencyIssue.SEV_HIGH);
        }

        public int getMediumCount() {
            return count(FormConsistencyIssue.SEV_MEDIUM);
        }

        public int getLowCount() {
            return count(FormConsistencyIssue.SEV_LOW);
        }

        public int getIssueCount() {
            return issues.size();
        }

        public boolean isClean() {
            return issues.isEmpty();
        }

        public Date getCheckedAt() {
            return new Date();
        }

        private int count(String sev) {
            int n = 0;
            for (FormConsistencyIssue i : issues) {
                if (sev.equals(i.getSeverity())) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 一次巡检的上下文：表单行只查一次 */
    private static final class Ctx {
        private final Map<String, TemplateDynamicForm> formRows = new HashMap<>();

        TemplateDynamicForm form(TemplateDynamicFormMapper mapper, String formId) {
            if (StringUtils.isBlank(formId)) {
                return null;
            }
            if (!formRows.containsKey(formId)) {
                formRows.put(formId, mapper.selectTemplateDynamicFormById(formId));
            }
            return formRows.get(formId);
        }
    }

    /** 全量巡检（只读） */
    public Report checkAll() {
        Report report = new Report();
        List<FlowSimple> flows = flowSimpleMapper.selectList(new FlowSimple());
        List<Template> templates = templateMapper.selectTemplateList(new Template());
        if (flows == null) {
            flows = new ArrayList<>();
        }
        if (templates == null) {
            templates = new ArrayList<>();
        }
        report.scannedFlows = flows.size();
        report.scannedTemplates = templates.size();

        // 模板按两种绑定口径分桶：simple_flow_id（新口径）与 def_key（旧口径/独立发布后绑的）
        Map<String, List<Template>> byFlowId = new LinkedHashMap<>();
        Map<String, List<Template>> byDefKey = new LinkedHashMap<>();
        for (Template t : templates) {
            if (StringUtils.isNotBlank(t.getSimpleFlowId())) {
                addTo(byFlowId, t.getSimpleFlowId(), t);
            }
            if (StringUtils.isNotBlank(t.getDefKey())) {
                addTo(byDefKey, t.getDefKey(), t);
            }
        }
        Set<String> flowIds = new LinkedHashSet<>();
        for (FlowSimple f : flows) {
            flowIds.add(f.getId());
            checkFlow(report, f, boundTemplates(f, byFlowId, byDefKey));
        }
        checkTemplates(report, templates, flowIds);
        return report;
    }

    /** 单条流程的检查（也供 publish 之类的链路复用） */
    public List<FormConsistencyIssue> checkFlow(String flowId) {
        Report report = new Report();
        FlowSimple f = StringUtils.isBlank(flowId) ? null : flowSimpleMapper.selectById(flowId);
        if (f == null) {
            return report.getIssues();
        }
        List<Template> templates = templateMapper.selectTemplateList(new Template());
        Map<String, List<Template>> byFlowId = new LinkedHashMap<>();
        Map<String, List<Template>> byDefKey = new LinkedHashMap<>();
        for (Template t : templates == null ? new ArrayList<Template>() : templates) {
            if (StringUtils.isNotBlank(t.getSimpleFlowId())) {
                addTo(byFlowId, t.getSimpleFlowId(), t);
            }
            if (StringUtils.isNotBlank(t.getDefKey())) {
                addTo(byDefKey, t.getDefKey(), t);
            }
        }
        checkFlow(report, f, boundTemplates(f, byFlowId, byDefKey));
        return report.getIssues();
    }

    /* ------------------------------------------------------------------ */

    private void checkFlow(Report report, FlowSimple f, List<Template> bound) {
        Ctx ctx = new Ctx();
        SimpleFlowContentIndex idx = SimpleFlowContentIndex.parse(f.getContent());
        String flowFormId = idx.isParsed() ? idx.getFormId() : null;
        if (!idx.isParsed()) {
            report.issues.add(new FormConsistencyIssue()
                    .kind(FormConsistencyIssue.KIND_FIELD_INVALID)
                    .severity(FormConsistencyIssue.SEV_MEDIUM)
                    .subject("flow")
                    .flow(f.getId(), f.getDefKey(), f.getName(), f.getStatus())
                    .detail("流程内容无法解析（JSON 非法），跳过字段级校验"));
        } else {
            checkFlowFormRow(report, f, flowFormId, ctx);
            checkFlowFields(report, f, flowFormId, idx, ctx);
        }
        for (Template t : bound) {
            if (!StringUtils.equals(StringUtils.defaultString(flowFormId),
                    StringUtils.defaultString(t.getFormId()))) {
                report.issues.add(new FormConsistencyIssue()
                        .kind(FormConsistencyIssue.KIND_MISMATCH)
                        .severity(FormConsistencyIssue.SEV_HIGH)
                        .subject("flow")
                        .flow(f.getId(), f.getDefKey(), f.getName(), f.getStatus())
                        .template(t.getId(), t.getName(), t.getEnableFlag())
                        .formIds(flowFormId, t.getFormId())
                        .detail("流程内容 formId=" + blank(flowFormId) + " 与模板 form_id="
                                + blank(t.getFormId()) + " 不一致 —— 设计器按流程里的表单出字段、"
                                + "发起页按模板的表单出字段，两边会各说各话"));
            }
        }
    }

    private void checkFlowFormRow(Report report, FlowSimple f, String flowFormId, Ctx ctx) {
        if (StringUtils.isBlank(flowFormId)) {
            report.issues.add(new FormConsistencyIssue()
                    .kind(FormConsistencyIssue.KIND_FLOW_FORM_MISSING)
                    .severity(FormConsistencyIssue.SEV_MEDIUM)
                    .subject("flow")
                    .flow(f.getId(), f.getDefKey(), f.getName(), f.getStatus())
                    .formIds(null, null)
                    .detail("流程内容里没有 formId（未关联动态表单）"));
            return;
        }
        TemplateDynamicForm row = ctx.form(templateDynamicFormMapper, flowFormId);
        String bad = badRowKind(row);
        if (bad == null) {
            return;
        }
        report.issues.add(new FormConsistencyIssue()
                .kind(bad)
                .severity(FormConsistencyIssue.SEV_HIGH)
                .subject("flow")
                .flow(f.getId(), f.getDefKey(), f.getName(), f.getStatus())
                .formIds(flowFormId, null)
                .detail(rowDetail(bad, flowFormId)));
    }

    private void checkFlowFields(Report report, FlowSimple f, String flowFormId,
                                 SimpleFlowContentIndex idx, Ctx ctx) {
        if (StringUtils.isBlank(flowFormId) || idx.allReferencedFields().isEmpty()) {
            return;
        }
        TemplateDynamicForm row = ctx.form(templateDynamicFormMapper, flowFormId);
        if (badRowKind(row) != null) {
            return;   // 表单行本身有问题时，字段级校验没有意义（上面已报）
        }
        FormFieldIndex fields = FormFieldIndex.parse(row.getContent());
        if (!fields.isParsed()) {
            return;
        }
        List<String> bad = new ArrayList<>();
        for (String field : idx.getConditionFields()) {
            if (!fields.isRequired(field)) {
                bad.add(field + (fields.contains(field) ? "（未设为必填）" : "（不存在）"));
            }
        }
        for (String field : idx.getMultiFields()) {
            if (!fields.contains(field)) {
                bad.add(field + "（并行分支字段不存在）");
            }
        }
        if (!bad.isEmpty()) {
            List<String> names = new ArrayList<>(bad);
            report.issues.add(new FormConsistencyIssue()
                    .kind(FormConsistencyIssue.KIND_FIELD_INVALID)
                    .severity(FormConsistencyIssue.SEV_HIGH)
                    .subject("flow")
                    .flow(f.getId(), f.getDefKey(), f.getName(), f.getStatus())
                    .formIds(flowFormId, null)
                    .fields(names)
                    .detail("条件/并行字段在其引用的表单（" + flowFormId + "）里不满足 V-8："
                            + String.join("、", bad)));
        }
    }

    private void checkTemplates(Report report, List<Template> templates, Set<String> liveFlowIds) {
        Ctx ctx = new Ctx();
        for (Template t : templates) {
            boolean enabled = "1".equals(t.getEnableFlag());
            if (StringUtils.isBlank(t.getFormId())) {
                report.issues.add(new FormConsistencyIssue()
                        .kind(FormConsistencyIssue.KIND_TEMPLATE_NO_FORM)
                        .severity(FormConsistencyIssue.SEV_LOW)
                        .subject("template")
                        .template(t.getId(), t.getName(), t.getEnableFlag())
                        .detail("模板没有关联表单（form_id 为空），发起页取不到表单"));
            } else {
                TemplateDynamicForm row = ctx.form(templateDynamicFormMapper, t.getFormId());
                String bad = badRowKind(row);
                if (bad != null) {
                    report.issues.add(new FormConsistencyIssue()
                            .kind(FormConsistencyIssue.KIND_TEMPLATE_FORM_BAD)
                            .severity(enabled ? FormConsistencyIssue.SEV_HIGH : FormConsistencyIssue.SEV_MEDIUM)
                            .subject("template")
                            .template(t.getId(), t.getName(), t.getEnableFlag())
                            .formIds(null, t.getFormId())
                            .detail(rowDetail(bad, t.getFormId()) + (enabled ? "" : "（模板已停用）")));
                }
            }
            if (StringUtils.isNotBlank(t.getSimpleFlowId()) && !liveFlowIds.contains(t.getSimpleFlowId())) {
                report.issues.add(new FormConsistencyIssue()
                        .kind(FormConsistencyIssue.KIND_TEMPLATE_DANGLING_FLOW)
                        .severity(FormConsistencyIssue.SEV_MEDIUM)
                        .subject("template")
                        .template(t.getId(), t.getName(), t.getEnableFlag())
                        .detail("模板绑定的流程 " + t.getSimpleFlowId()
                                + " 不存在或已删除 —— 发起页会找不到流程定义"));
            }
        }
    }

    /** @return 问题分类；表单行健康时返回 null */
    private static String badRowKind(TemplateDynamicForm row) {
        if (row == null) {
            return FormConsistencyIssue.KIND_FLOW_FORM_MISSING;
        }
        if ("1".equals(row.getDelFlag())) {
            return FormConsistencyIssue.KIND_FLOW_FORM_DELETED;
        }
        if (!"1".equals(row.getEnableFlag())) {
            return FormConsistencyIssue.KIND_FLOW_FORM_DISABLED;
        }
        return null;
    }

    private static String rowDetail(String kind, String formId) {
        String what;
        if (FormConsistencyIssue.KIND_FLOW_FORM_MISSING.equals(kind)) {
            what = "表单行不存在";
        } else if (FormConsistencyIssue.KIND_FLOW_FORM_DELETED.equals(kind)) {
            what = "表单行已软删（del_flag=1）";
        } else {
            what = "表单行已停用（enable_flag=0）";
        }
        return "引用的表单 " + formId + " " + what;
    }

    private static List<Template> boundTemplates(FlowSimple f,
                                                 Map<String, List<Template>> byFlowId,
                                                 Map<String, List<Template>> byDefKey) {
        Map<String, Template> merged = new LinkedHashMap<>();
        List<Template> a = byFlowId.get(f.getId());
        if (a != null) {
            for (Template t : a) {
                merged.put(t.getId(), t);
            }
        }
        List<Template> b = byDefKey.get(f.getDefKey());
        if (b != null) {
            for (Template t : b) {
                merged.put(t.getId(), t);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static void addTo(Map<String, List<Template>> map, String key, Template t) {
        List<Template> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        list.add(t);
    }

    private static String blank(String s) {
        return StringUtils.isBlank(s) ? "(空)" : s;
    }
}
