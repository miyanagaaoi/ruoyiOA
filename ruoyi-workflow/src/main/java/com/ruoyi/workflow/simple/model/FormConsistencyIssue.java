package com.ruoyi.workflow.simple.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * <p> 「模板 form_id ↔ 流程 content.formId」一致性巡检的一条发现。 </p>
 *
 * <p> 巡检口径见 {@code FormConsistencyChecker}；本类只承载"是什么问题、在哪条流程/哪张模板上"。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Data
public class FormConsistencyIssue implements Serializable {

    private static final long serialVersionUID = 1L;

    /* ---------------- 问题分类 ---------------- */

    /** 流程 content.formId 与绑定模板的 form_id 不一致（本次要抓的主角） */
    public static final String KIND_MISMATCH = "MISMATCH";
    /** 流程引用的表单行不存在 */
    public static final String KIND_FLOW_FORM_MISSING = "FLOW_FORM_MISSING";
    /** 流程引用的表单行已软删（del_flag=1） */
    public static final String KIND_FLOW_FORM_DELETED = "FLOW_FORM_DELETED";
    /** 流程引用的表单行已停用（enable_flag=0） */
    public static final String KIND_FLOW_FORM_DISABLED = "FLOW_FORM_DISABLED";
    /** 流程的条件字段 / 并行分支字段在其引用的表单内容里不存在或未设为必填（PRD V-8 运行时口径） */
    public static final String KIND_FIELD_INVALID = "CONDITION_FIELD_INVALID";
    /** 模板的 form_id 指向不存在 / 已删 / 已停用的表单行 */
    public static final String KIND_TEMPLATE_FORM_BAD = "TEMPLATE_FORM_BAD";
    /** 模板的 form_id 为空（发起页取不到表单） */
    public static final String KIND_TEMPLATE_NO_FORM = "TEMPLATE_NO_FORM";
    /** 模板绑定的 simple_flow_id 指向的流程不存在或已软删 */
    public static final String KIND_TEMPLATE_DANGLING_FLOW = "TEMPLATE_DANGLING_FLOW";

    /* ---------------- 严重度 ---------------- */

    /** 会让运行期报错/条件失效，或正是本次缺陷 */
    public static final String SEV_HIGH = "high";
    /** 会让人看见"两张表各指一个表单"的隐患，但不一定立刻报错 */
    public static final String SEV_MEDIUM = "medium";
    /** 存量/可信噪声（如已停用的模板） */
    public static final String SEV_LOW = "low";

    private String kind;
    private String severity;
    /** flow / template */
    private String subject;

    private String flowId;
    private String defKey;
    private String flowName;
    /** 0-草稿 / 1-已发布 */
    private String flowStatus;

    private String templateId;
    private String templateName;
    /** 模板是否启用（0/1） */
    private String templateEnableFlag;

    /** 流程侧引用的表单行 id（content.formId） */
    private String flowFormId;
    /** 模板侧引用的表单行 id（t_template.form_id） */
    private String templateFormId;

    /** 涉及的字段（字段级问题时给出） */
    private List<String> fields = new ArrayList<>();

    /** 人话说明（直接可读，含定位信息） */
    private String detail;

    public FormConsistencyIssue kind(String kind) {
        this.kind = kind;
        return this;
    }

    public FormConsistencyIssue severity(String severity) {
        this.severity = severity;
        return this;
    }

    public FormConsistencyIssue subject(String subject) {
        this.subject = subject;
        return this;
    }

    public FormConsistencyIssue flow(String flowId, String defKey, String flowName, String flowStatus) {
        this.flowId = flowId;
        this.defKey = defKey;
        this.flowName = flowName;
        this.flowStatus = flowStatus;
        return this;
    }

    public FormConsistencyIssue template(String templateId, String templateName, String enableFlag) {
        this.templateId = templateId;
        this.templateName = templateName;
        this.templateEnableFlag = enableFlag;
        return this;
    }

    public FormConsistencyIssue formIds(String flowFormId, String templateFormId) {
        this.flowFormId = flowFormId;
        this.templateFormId = templateFormId;
        return this;
    }

    public FormConsistencyIssue fields(List<String> fields) {
        this.fields = fields == null ? new ArrayList<String>() : fields;
        return this;
    }

    public FormConsistencyIssue detail(String detail) {
        this.detail = detail;
        return this;
    }
}
