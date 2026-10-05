package com.ruoyi.workflow.simple;

import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.support.FormFieldIndex;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.simple.FormCheckStubs.StubFlowSimpleMapper;
import com.ruoyi.workflow.simple.FormCheckStubs.StubTemplateDynamicFormMapper;
import com.ruoyi.workflow.simple.FormCheckStubs.StubTemplateMapper;
import com.ruoyi.workflow.simple.model.FormConsistencyIssue;
import com.ruoyi.workflow.simple.support.FormConsistencyChecker;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.ruoyi.workflow.simple.FormCheckStubs.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>「模板 form_id ↔ 流程 content.formId」一致性巡检</b>单测（{@link FormConsistencyChecker}）。 </p>
 *
 * <p> 不起 Spring、不连库：3 个 Mapper 用内存桩，被测的是真实巡检实现。用例覆盖： </p>
 * <ul>
 *   <li> 错位（流程 formId ≠ 模板 form_id）→ 检出，且能定位到具体流程与模板； </li>
 *   <li> 一致 → 干净（反向自证：不是"永远报 red"的假门禁）； </li>
 *   <li> 流程引用的表单行已软删 / 不存在； </li>
 *   <li> 条件字段在其引用表单里不存在或未设为必填（V-8 运行时口径）； </li>
 *   <li> 模板侧：form_id 指向已删表单 / form_id 为空 / 绑定的流程已被删； </li>
 *   <li> 按 {@code def_key} 绑定的模板同样参与比对（旧口径）。 </li>
 * </ul>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public class FormConsistencyCheckerTest {

    private static final String FORM_A = "FORM-A-1111";
    private static final String FORM_B = "FORM-B-2222";
    private static final String FORM_GONE = "FORM-GONE-9999";

    private StubFlowSimpleMapper flowMapper;
    private StubTemplateMapper templateMapper;
    private StubTemplateDynamicFormMapper formMapper;
    private FormConsistencyChecker checker;

    @Before
    public void setUp() {
        flowMapper = new StubFlowSimpleMapper();
        templateMapper = new StubTemplateMapper();
        formMapper = new StubTemplateDynamicFormMapper();
        checker = new FormConsistencyChecker();
        inject(checker, "flowSimpleMapper", flowMapper);
        inject(checker, "templateMapper", templateMapper);
        inject(checker, "templateDynamicFormMapper", formMapper);
    }

    /* ------------------------- 夹具 ------------------------- */

    private static String formContent(boolean required) {
        return "{\"fields\":[{\"__config__\":{\"label\":\"合同类型\",\"tag\":\"el-radio-group\",\"required\":"
                + required + "},\"__vModel__\":\"field101\"}]}";
    }

    /** 一个带条件分支（引用 field101）的流程内容 */
    private static String flowContent(String formId) {
        return "{\"schemaVersion\":1,\"key\":\"k\",\"name\":\"n\",\"formId\":\"" + formId + "\",\"nodes\":["
                + "{\"id\":\"start\",\"type\":\"start\"},"
                + "{\"id\":\"b1\",\"type\":\"condition\",\"name\":\"路由\",\"branches\":["
                + "{\"id\":\"b1_1\",\"name\":\"分支1\",\"groups\":[{\"logic\":\"AND\",\"rows\":["
                + "{\"field\":\"field101\",\"op\":\"EQ\",\"value\":\"经营\"}]}],"
                + "\"nodes\":[{\"id\":\"n2\",\"type\":\"approve\"}]},"
                + "{\"id\":\"b1_d\",\"name\":\"其他情况\",\"defaultBranch\":true,"
                + "\"nodes\":[{\"id\":\"n3\",\"type\":\"approve\"}]}]},"
                + "{\"id\":\"end\",\"type\":\"end\"}]}";
    }

    private void addForm(String id, String content, String enableFlag, String delFlag) {
        TemplateDynamicForm f = new TemplateDynamicForm();
        f.setId(id);
        f.setName("表单-" + id);
        f.setFormKey("FK-" + id);
        f.setContent(content);
        f.setEnableFlag(enableFlag);
        f.setDelFlag(delFlag);
        formMapper.rows.put(id, f);
    }

    private void addFlow(String id, String defKey, String status, String formId) {
        FlowSimple f = new FlowSimple();
        f.setId(id);
        f.setDefKey(defKey);
        f.setName("流程-" + defKey);
        f.setStatus(status);
        f.setDelFlag("0");
        f.setContent(flowContent(formId));
        flowMapper.rows.put(id, f);
    }

    private void addTemplate(String id, String name, String defKey, String formId,
                             String simpleFlowId, String enableFlag) {
        Template t = new Template();
        t.setId(id);
        t.setName(name);
        t.setDefKey(defKey);
        t.setFormId(formId);
        t.setSimpleFlowId(simpleFlowId);
        t.setEnableFlag(enableFlag);
        t.setDelFlag("0");
        templateMapper.rows.put(id, t);
    }

    private static List<FormConsistencyIssue> byKind(List<FormConsistencyIssue> issues, String kind) {
        List<FormConsistencyIssue> out = new ArrayList<>();
        for (FormConsistencyIssue i : issues) {
            if (kind.equals(i.getKind())) {
                out.add(i);
            }
        }
        return out;
    }

    /* ------------------------- 用例 ------------------------- */

    @Test
    public void 错位时检出并定位到具体流程与模板() {
        addForm(FORM_A, formContent(true), "1", "0");
        addForm(FORM_B, formContent(true), "1", "0");
        addFlow("F1", "htsp", "1", FORM_A);
        addTemplate("T1", "合同审批", "htsp", FORM_B, "F1", "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        List<FormConsistencyIssue> mism = byKind(report.getIssues(), FormConsistencyIssue.KIND_MISMATCH);
        assertEquals(1, mism.size());
        assertEquals("F1", mism.get(0).getFlowId());
        assertEquals("htsp", mism.get(0).getDefKey());
        assertEquals("T1", mism.get(0).getTemplateId());
        assertEquals(FORM_A, mism.get(0).getFlowFormId());
        assertEquals(FORM_B, mism.get(0).getTemplateFormId());
        assertEquals(FormConsistencyIssue.SEV_HIGH, mism.get(0).getSeverity());
        assertEquals(1, report.getHighCount());
        assertFalse(report.isClean());
    }

    @Test
    public void 两边一致时干净不误报() {
        addForm(FORM_A, formContent(true), "1", "0");
        addFlow("F1", "htsp", "1", FORM_A);
        addTemplate("T1", "合同审批", "htsp", FORM_A, "F1", "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        assertTrue("一致就必须干净（否则就是永远红的假门禁）：" + report.getIssues(), report.isClean());
        assertEquals(1, report.getScannedFlows());
        assertEquals(1, report.getScannedTemplates());
    }

    @Test
    public void 按defKey绑定的模板也参与比对() {
        addForm(FORM_A, formContent(true), "1", "0");
        addForm(FORM_B, formContent(true), "1", "0");
        addFlow("F1", "testParallel", "1", FORM_A);
        // 旧口径：模板没有 simple_flow_id，只有 def_key
        addTemplate("T1", "测试03-并行会审", "testParallel", FORM_B, null, "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        assertEquals(1, byKind(report.getIssues(), FormConsistencyIssue.KIND_MISMATCH).size());
    }

    @Test
    public void 流程引用的表单行已软删_检出() {
        addForm(FORM_A, formContent(true), "1", "1");   // del_flag=1
        addFlow("F1", "htsp", "1", FORM_A);

        FormConsistencyChecker.Report report = checker.checkAll();

        List<FormConsistencyIssue> del = byKind(report.getIssues(), FormConsistencyIssue.KIND_FLOW_FORM_DELETED);
        assertEquals(1, del.size());
        assertEquals(FormConsistencyIssue.SEV_HIGH, del.get(0).getSeverity());
    }

    @Test
    public void 流程引用的表单行不存在_检出() {
        addFlow("F1", "htsp", "1", FORM_GONE);

        FormConsistencyChecker.Report report = checker.checkAll();

        assertEquals(1, byKind(report.getIssues(), FormConsistencyIssue.KIND_FLOW_FORM_MISSING).size());
    }

    @Test
    public void 条件字段未设为必填_按V8检出() {
        addForm(FORM_A, formContent(false), "1", "0");   // field101 存在但非必填
        addFlow("F1", "testCondition", "1", FORM_A);

        FormConsistencyChecker.Report report = checker.checkAll();

        List<FormConsistencyIssue> bad = byKind(report.getIssues(), FormConsistencyIssue.KIND_FIELD_INVALID);
        assertEquals(1, bad.size());
        assertEquals("F1", bad.get(0).getFlowId());
        assertTrue(bad.get(0).getFields().get(0).contains("field101"));
        assertTrue(bad.get(0).getFields().get(0).contains("未设为必填"));
    }

    @Test
    public void 条件字段在新版本里不存在_按V8检出() {
        addForm(FORM_A, "{\"fields\":[{\"__config__\":{\"label\":\"别的\",\"tag\":\"el-input\","
                + "\"required\":true},\"__vModel__\":\"field999\"}]}", "1", "0");
        addFlow("F1", "testCondition", "1", FORM_A);

        FormConsistencyChecker.Report report = checker.checkAll();

        List<FormConsistencyIssue> bad = byKind(report.getIssues(), FormConsistencyIssue.KIND_FIELD_INVALID);
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).getFields().get(0).contains("不存在"));
    }

    @Test
    public void 模板引用的表单已删_启用模板报高_停用模板降级() {
        addForm(FORM_GONE, formContent(true), "1", "1");
        addFlow("F1", "htsp", "1", FORM_GONE);
        addTemplate("T1", "启用的模板", "htsp", FORM_GONE, "F1", "1");
        addTemplate("T2", "停用的模板", "htsp", FORM_GONE, "F1", "0");

        FormConsistencyChecker.Report report = checker.checkAll();

        List<FormConsistencyIssue> bad = byKind(report.getIssues(), FormConsistencyIssue.KIND_TEMPLATE_FORM_BAD);
        assertEquals(2, bad.size());
        assertEquals(FormConsistencyIssue.SEV_HIGH, bad.get(0).getSeverity());
        assertEquals(FormConsistencyIssue.SEV_MEDIUM, bad.get(1).getSeverity());
    }

    @Test
    public void 模板form_id为空_低严重度提示() {
        addFlow("F1", "htsp", "1", FORM_A);
        addForm(FORM_A, formContent(true), "1", "0");
        addTemplate("T1", "没绑表单的模板", "htsp", null, "F1", "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        assertEquals(1, byKind(report.getIssues(), FormConsistencyIssue.KIND_TEMPLATE_NO_FORM).size());
        // 流程 formId=FORM_A vs 模板 form_id=null ⇒ 同时是 MISMATCH（人工确认的正确答案）
        assertEquals(1, byKind(report.getIssues(), FormConsistencyIssue.KIND_MISMATCH).size());
    }

    @Test
    public void 模板绑定的流程已被删_检出悬空绑定() {
        addForm(FORM_A, formContent(true), "1", "0");
        addFlow("F1", "htsp", "1", FORM_A);
        addTemplate("T1", "悬空绑定", "htsp", FORM_A, "FLOW-DELETED", "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        assertEquals(1, byKind(report.getIssues(), FormConsistencyIssue.KIND_TEMPLATE_DANGLING_FLOW).size());
    }

    @Test
    public void 已软删的流程不参与巡检() {
        addForm(FORM_B, formContent(true), "1", "0");
        addFlow("F1", "htsp", "1", FORM_A);
        // 流程被删掉后，模板与它不再是一对（不报 MISMATCH）
        flowMapper.rows.get("F1").setDelFlag("1");
        addTemplate("T1", "合同审批", "htsp", FORM_B, "F1", "1");

        FormConsistencyChecker.Report report = checker.checkAll();

        assertEquals(0, report.getScannedFlows());
        assertEquals(0, byKind(report.getIssues(), FormConsistencyIssue.KIND_MISMATCH).size());
    }

    @Test
    public void 表单内容与前端口径一致_必填字段可被解析() {
        // 自证：巡检用的解析器就是 FormFieldIndex（口径唯一），这里直接钉住它的行为
        addForm(FORM_A, formContent(true), "1", "0");
        assertTrue(FormFieldIndex.parse(formMapper.rows.get(FORM_A).getContent()).isRequired("field101"));
    }
}
