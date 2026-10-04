package com.ruoyi.workflow.simple.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * <p> 简化流程定义（设计器 JSON 模型） </p>
 *
 * <p> 设计器只产出本模型；BPMN XML 由 {@code SimpleFlowCompiler} 生成，
 * 因此设计器侧完全不出现 BPMN 词汇，也不会把条件直接挂在 userTask 出口上。 </p>
 *
 * <p> JSON 示例： </p>
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "key": "contractApproval",
 *   "name": "合同审批（含用印）",
 *   "category": "contract",
 *   "nodes": [
 *     { "id": "start", "type": "start", "name": "发起" },
 *     { "id": "n1", "type": "approve", "name": "责任部门审批",
 *       "assignee": { "source": "DEPT_LEADER", "deptScope": "SUBMITTER_DEPT", "level": 1 },
 *       "multiMode": "SINGLE", "signMode": "REQUIRED", "signTypes": ["HANDWRITE"],
 *       "buttons": ["agree", "return", "copy", "print"] },
 *     { "id": "b1", "type": "condition", "name": "合同类型路由",
 *       "branches": [
 *         { "id": "b1_1", "name": "分支1",
 *           "groups": [ { "logic": "AND", "rows": [
 *               { "field": "contractType", "op": "EQ", "value": "经营" } ] } ],
 *           "nodes": [ { "id": "n2", "type": "approve", "name": "经发负责人审批",
 *                        "assignee": { "source": "USER", "userIds": ["100"] } } ] },
 *         { "id": "b1_default", "name": "其他情况", "defaultBranch": true, "nodes": [ ... ] }
 *       ] },
 *     { "id": "end", "type": "end", "name": "结束" }
 *   ]
 * }
 * </pre>
 *
 * @author 二开
 */
@Data
public class SimpleFlowDef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结构版本，用于后续演进（PRD AC-40） */
    private Integer schemaVersion = 1;

    /** 流程定义 key（部署到 Flowable 的 process id，需全局唯一） */
    private String key;

    /** 流程名称 */
    private String name;

    /** 流程分类 */
    private String category;

    /** 顶层节点清单，顺序即流转顺序 */
    private List<Node> nodes = new ArrayList<>();

    /* ------------------------------------------------------------------ */

    /** 节点类型常量 */
    public static final String T_START = "start";
    public static final String T_APPROVE = "approve";
    public static final String T_HANDLE = "handle";
    public static final String T_CC = "cc";
    public static final String T_CONDITION = "condition";
    public static final String T_PARALLEL = "parallel";
    public static final String T_END = "end";

    /** 参与人来源常量 */
    public static final String S_USER = "USER";
    public static final String S_ROLE = "ROLE";
    public static final String S_DEPT_LEADER = "DEPT_LEADER";
    public static final String S_LEADER = "LEADER";
    public static final String S_INITIATOR = "INITIATOR";
    public static final String S_INITIATOR_SELECT = "INITIATOR_SELECT";
    public static final String S_PREV_APPROVER = "PREV_APPROVER";

    /** 多人方式常量 */
    public static final String M_SINGLE = "SINGLE";
    public static final String M_AND = "AND";
    public static final String M_OR = "OR";
    public static final String M_SEQ = "SEQ";

    /** 同一审批人策略 */
    public static final String SAME_EACH_TIME = "EACH_TIME";
    public static final String SAME_AUTO_PASS = "AUTO_PASS";

    /** 合流规则 */
    public static final String JOIN_ALL = "ALL";
    public static final String JOIN_ANY = "ANY";

    /** 并行分支来源：按表单多选字段动态生成 */
    public static final String SRC_FORM_MULTI = "FORM_MULTI";
    /** 并行分支参与人：取该分支对应的值（部门）本身 */
    public static final String A_BRANCH_VALUE = "BRANCH_VALUE";

    @Data
    public static class Node implements Serializable {
        private static final long serialVersionUID = 1L;

        private String id;
        /** start | approve | handle | cc | condition | parallel | end */
        private String type;
        private String name;

        /** 审批 / 办理节点的参与人 */
        private Assignee assignee;
        /** SINGLE | AND | OR | SEQ */
        private String multiMode = M_SINGLE;
        /** EACH_TIME（默认，每次都审） | AUTO_PASS（同一人自动通过，Q3 预留） */
        private String sameUserPolicy = SAME_EACH_TIME;

        /** NONE | OPTIONAL | REQUIRED */
        private String signMode = "OPTIONAL";
        private List<String> signTypes = new ArrayList<>();

        /** 可用按钮：agree/return/reject/addSign/transfer/delegate/copy/urge/stamp/print */
        private List<String> buttons = new ArrayList<>();

        /** 本节点只读字段（字段 __vModel__ 列表） */
        private List<String> fieldReadonly = new ArrayList<>();

        /** 超时提醒小时数（null=不提醒） */
        private Integer timeoutHours;

        /** 审批人为空时：ADMIN（转流程管理员，默认） | AUTO_PASS | USER */
        private String emptyPolicy = "ADMIN";
        /** 与发起人同人时：SKIP（默认自动跳过） | SELF | LEADER */
        private String selfPolicy = "SKIP";
        /** 审批人离职时：LEADER（默认转直属上级） | ADMIN */
        private String leftPolicy = "LEADER";

        /* ---- condition ---- */
        private List<Branch> branches = new ArrayList<>();

        /* ---- parallel ---- */
        /** FORM_MULTI（按表单多选字段动态生成） | FIXED */
        private String branchSource = "FORM_MULTI";
        /** branchSource=FORM_MULTI 时的表单字段 __vModel__ */
        private String formField;
        /** 每条分支的审批人（来源里可用 BRANCH_VALUE 表示"该分支对应的部门"） */
        private Assignee branchAssignee;
        /** 分支数上限 */
        private Integer maxBranches = 5;
        /** ALL（默认，全部完成） | ANY（任一完成即继续） */
        private String joinMode = JOIN_ALL;

        /* ---- cc ---- */
        private List<Assignee> ccers = new ArrayList<>();
    }

    @Data
    public static class Assignee implements Serializable {
        private static final long serialVersionUID = 1L;

        /** USER | ROLE | DEPT_LEADER | LEADER | INITIATOR | INITIATOR_SELECT | PREV_APPROVER */
        private String source;
        private List<String> userIds = new ArrayList<>();
        private List<String> roleIds = new ArrayList<>();
        /** SUBMITTER_DEPT | BRANCH_VALUE | FIXED */
        private String deptScope;
        private String deptId;
        /** 层级（部门负责人/上级上溯级数） */
        private Integer level = 1;
    }

    @Data
    public static class Branch implements Serializable {
        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        /** true = 「其他情况」兜底分支（编译为排他网关的 default 流） */
        private boolean defaultBranch;
        /** 条件组，组间「或」 */
        private List<Group> groups = new ArrayList<>();
        /** 分支内部子链 */
        private List<Node> nodes = new ArrayList<>();
    }

    @Data
    public static class Group implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 组内逻辑，固定 AND */
        private String logic = "AND";
        private List<Row> rows = new ArrayList<>();
    }

    @Data
    public static class Row implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 表单字段 __vModel__ 或系统内置字段（initiator / initiatorDept） */
        private String field;
        /** EQ NE GT GE LT LE IN NOT_IN CONTAINS NOT_CONTAINS EMPTY NOT_EMPTY BETWEEN */
        private String op;
        private Object value;
        /** BETWEEN 的上界 */
        private Object value2;
    }
}
