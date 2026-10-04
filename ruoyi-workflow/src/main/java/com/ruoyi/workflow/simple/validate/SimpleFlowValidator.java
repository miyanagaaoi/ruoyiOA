package com.ruoyi.workflow.simple.validate;

import com.ruoyi.workflow.simple.model.SimpleFlowDef;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Assignee;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Branch;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Group;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Node;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Row;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p> 简化流程发布校验（PRD 6.7 的 V-1 ~ V-12） </p>
 *
 * <p> 设计原则： </p>
 * <ol>
 *   <li> <b>只报告不抛异常</b>——一次列全问题并定位到节点，管理员一次改完； </li>
 *   <li> <b>递归遍历分支内的子链</b>——条件分支里的节点同样要检查参与人、名称、签名策略，
 *        否则会出现"校验通过但编译抛错"（编译器对分支内节点有同样的要求）； </li>
 *   <li> 名称唯一性按<b>链</b>判定：顶层链内唯一、每条分支的子链内各自唯一。
 *        互斥分支里出现同名节点是合理的（打印件只会出现走过的分支），不作阻断。 </li>
 * </ol>
 *
 * @author 二开
 */
public class SimpleFlowValidator {

    /** 阻断 */
    public static final String BLOCK = "BLOCK";
    /** 提示 */
    public static final String WARN = "WARN";

    private static final int MAX_GROUPS = 5;
    private static final int MAX_ROWS_PER_GROUP = 5;
    private static final int MAX_BRANCHES = 5;
    private static final int MAX_NAME_LEN = 20;

    /** 校验结果项 */
    public static class Issue {
        private final String level;
        private final String rule;
        private final String nodeId;
        private final String message;

        public Issue(String level, String rule, String nodeId, String message) {
            this.level = level;
            this.rule = rule;
            this.nodeId = nodeId;
            this.message = message;
        }

        public String getLevel() {
            return level;
        }

        public String getRule() {
            return rule;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "[" + level + "][" + rule + "]"
                    + (StringUtils.isBlank(nodeId) ? "" : "(节点 " + nodeId + ")") + " " + message;
        }
    }

    /**
     * 校验流程定义
     *
     * @param def                简化流程定义
     * @param requiredFormFields 表单中<b>已设为必填</b>的字段 __vModel__ 集合；传 null 表示暂不校验条件字段
     * @param multiFormFields    表单中的<b>多选</b>字段 __vModel__ 集合；传 null 表示暂不校验并行分支集合来源
     */
    public static List<Issue> validate(SimpleFlowDef def, Set<String> requiredFormFields, Set<String> multiFormFields) {
        List<Issue> issues = new ArrayList<>();
        if (def == null) {
            issues.add(new Issue(BLOCK, "V-0", null, "流程定义为空"));
            return issues;
        }
        if (StringUtils.isBlank(def.getKey())) {
            issues.add(new Issue(BLOCK, "V-0", null, "流程 key 不能为空"));
        } else if (!def.getKey().matches("[A-Za-z_][A-Za-z0-9_\\-]*")) {
            issues.add(new Issue(BLOCK, "V-0", null,
                    "流程 key 不合法（仅允许字母/数字/下划线/中划线，且不以数字开头）：" + def.getKey()));
        }
        if (StringUtils.isBlank(def.getName())) {
            issues.add(new Issue(BLOCK, "V-0", null, "流程名称不能为空"));
        }

        List<Node> nodes = def.getNodes() == null ? new ArrayList<>() : def.getNodes();
        if (nodes.isEmpty()) {
            issues.add(new Issue(BLOCK, "V-0", null, "流程至少需要一个节点"));
            return issues;
        }

        Set<String> seenIds = new HashSet<>();
        int approveCount = 0;
        String firstApproveId = null;

        // ---- 顶层链 ----
        Set<String> topNames = new HashSet<>();
        for (Node n : nodes) {
            approveCount += checkNode(n, issues, requiredFormFields, multiFormFields, seenIds, topNames);
            if (firstApproveId == null && SimpleFlowDef.T_APPROVE.equals(n.getType())) {
                firstApproveId = n.getId();
            }
        }

        // ---- 分支内的子链（递归）----
        for (Node n : nodes) {
            if (!SimpleFlowDef.T_CONDITION.equals(n.getType())) {
                continue;
            }
            List<Branch> branches = n.getBranches() == null ? new ArrayList<>() : n.getBranches();
            for (Branch b : branches) {
                Set<String> subNames = new HashSet<>();
                List<Node> sub = b.getNodes() == null ? new ArrayList<>() : b.getNodes();
                for (Node sn : sub) {
                    approveCount += checkNode(sn, issues, requiredFormFields, multiFormFields, seenIds, subNames);
                }
            }
        }

        // ---- V-1 审批节点数量 ----
        if (approveCount == 0) {
            issues.add(new Issue(BLOCK, "V-1", null, "流程至少需要 1 个审批节点"));
        } else if (approveCount < 2) {
            issues.add(new Issue(WARN, "V-1", null, "只有 1 个审批节点：不会产生待办，建议至少 2 个"));
        }

        // ---- V-3 首个审批节点不得用"角色 / 发起人自选" ----
        if (firstApproveId != null) {
            for (Node n : nodes) {
                if (firstApproveId.equals(n.getId())) {
                    Assignee a = n.getAssignee();
                    String src = a == null ? null : a.getSource();
                    if (SimpleFlowDef.S_ROLE.equals(src) || SimpleFlowDef.S_INITIATOR_SELECT.equals(src)) {
                        issues.add(new Issue(BLOCK, "V-3", n.getId(),
                                "首个审批节点不支持按「角色」或「发起人自选」（引擎在发起时解析不到该变量）"));
                    }
                    break;
                }
            }
        }

        return issues;
    }

    /**
     * 单个节点的通用校验
     *
     * @return 该节点是否为审批节点（用于 V-1 计数）
     */
    private static int checkNode(Node n, List<Issue> issues, Set<String> requiredFormFields,
                                 Set<String> multiFormFields, Set<String> seenIds, Set<String> chainNames) {
        String type = StringUtils.defaultString(n.getType());
        String nodeId = StringUtils.defaultIfBlank(n.getId(), "(未命名)");

        // V-4 id
        if (StringUtils.isBlank(n.getId())) {
            issues.add(new Issue(BLOCK, "V-4", null, "存在没有 id 的节点：" + type));
        } else if (!seenIds.add(n.getId())) {
            issues.add(new Issue(BLOCK, "V-4", nodeId, "节点 id 重复：" + n.getId()));
        }

        // V-4 名称（起止节点除外）
        if (!SimpleFlowDef.T_START.equals(type) && !SimpleFlowDef.T_END.equals(type)) {
            String name = StringUtils.trimToEmpty(n.getName());
            if (StringUtils.isBlank(name)) {
                issues.add(new Issue(BLOCK, "V-4", nodeId, "节点名称不能为空"));
            } else if (name.length() > MAX_NAME_LEN) {
                issues.add(new Issue(BLOCK, "V-4", nodeId, "节点名称超过 " + MAX_NAME_LEN + " 个字：" + name));
            } else if (!chainNames.add(name)) {
                issues.add(new Issue(BLOCK, "V-4", nodeId, "同一条链上节点名称重复：" + name));
            }
        }

        switch (type) {
            case SimpleFlowDef.T_APPROVE:
            case SimpleFlowDef.T_HANDLE:
                checkApprover(n, nodeId, issues);
                return SimpleFlowDef.T_APPROVE.equals(type) ? 1 : 0;
            case SimpleFlowDef.T_CONDITION:
                checkCondition(n, nodeId, issues, requiredFormFields);
                return 0;
            case SimpleFlowDef.T_PARALLEL:
                checkParallel(n, nodeId, issues, multiFormFields);
                return 0;
            default:
                return 0;
        }
    }

    /** V-2 参与人 + V-7 签名策略 */
    private static void checkApprover(Node n, String nodeId, List<Issue> issues) {
        Assignee a = n.getAssignee();
        if (a == null || StringUtils.isBlank(a.getSource())) {
            issues.add(new Issue(BLOCK, "V-2", nodeId, "节点「" + n.getName() + "」未配置参与人"));
        } else {
            String src = a.getSource();
            if (SimpleFlowDef.S_USER.equals(src) && (a.getUserIds() == null || a.getUserIds().isEmpty())) {
                issues.add(new Issue(BLOCK, "V-2", nodeId, "节点「" + n.getName() + "」选择「指定人员」但未选人"));
            }
            if (SimpleFlowDef.S_ROLE.equals(src) && (a.getRoleIds() == null || a.getRoleIds().isEmpty())) {
                issues.add(new Issue(BLOCK, "V-2", nodeId, "节点「" + n.getName() + "」选择「角色」但未选角色"));
            }
        }
        if ("REQUIRED".equals(n.getSignMode()) && (n.getSignTypes() == null || n.getSignTypes().isEmpty())) {
            issues.add(new Issue(BLOCK, "V-7", nodeId,
                    "节点「" + n.getName() + "」要求必签名，但未选择签名方式"));
        }
    }

    /** V-5 / V-8 / V-9 / V-10 / V-11 */
    private static void checkCondition(Node n, String nodeId, List<Issue> issues, Set<String> requiredFormFields) {
        List<Branch> branches = n.getBranches() == null ? new ArrayList<>() : n.getBranches();
        if (branches.isEmpty()) {
            issues.add(new Issue(BLOCK, "V-5", nodeId, "条件分支「" + n.getName() + "」没有任何分支"));
            return;
        }
        if (branches.size() > MAX_BRANCHES + 1) {
            issues.add(new Issue(BLOCK, "V-10", nodeId,
                    "条件分支「" + n.getName() + "」分支数超过 " + MAX_BRANCHES + " 条（不含兜底）"));
        }
        int defaultCount = 0;
        Set<String> branchNames = new HashSet<>();
        for (Branch b : branches) {
            String bName = StringUtils.defaultIfBlank(b.getName(), b.getId());
            if (b.isDefaultBranch()) {
                defaultCount++;
            } else {
                if (StringUtils.isBlank(b.getName())) {
                    issues.add(new Issue(BLOCK, "V-10", nodeId, "存在没有名称的分支"));
                } else if (!branchNames.add(bName)) {
                    issues.add(new Issue(BLOCK, "V-10", nodeId, "分支名称重复：" + bName));
                }
                if (b.getGroups() == null || b.getGroups().isEmpty()) {
                    issues.add(new Issue(BLOCK, "V-9", nodeId,
                            "分支「" + bName + "」未设条件，会导致「其他情况」永不执行"));
                } else {
                    if (b.getGroups().size() > MAX_GROUPS) {
                        issues.add(new Issue(BLOCK, "V-10", nodeId,
                                "分支「" + bName + "」条件组超过 " + MAX_GROUPS + " 组"));
                    }
                    for (Group g : b.getGroups()) {
                        List<Row> rows = g.getRows() == null ? new ArrayList<>() : g.getRows();
                        if (rows.isEmpty()) {
                            issues.add(new Issue(BLOCK, "V-9", nodeId, "分支「" + bName + "」存在空条件组"));
                        }
                        if (rows.size() > MAX_ROWS_PER_GROUP) {
                            issues.add(new Issue(BLOCK, "V-10", nodeId,
                                    "分支「" + bName + "」某一条件组内条件超过 " + MAX_ROWS_PER_GROUP + " 条"));
                        }
                        for (Row r : rows) {
                            if (StringUtils.isBlank(r.getField()) || StringUtils.isBlank(r.getOp())) {
                                issues.add(new Issue(BLOCK, "V-9", nodeId,
                                        "分支「" + bName + "」存在缺少字段或比较符的条件"));
                                continue;
                            }
                            if (requiredFormFields != null && !requiredFormFields.contains(r.getField())) {
                                issues.add(new Issue(BLOCK, "V-8", nodeId,
                                        "条件引用的字段「" + r.getField() + "」不存在或未设为必填"));
                            }
                        }
                    }
                }
            }
            List<Node> sub = b.getNodes() == null ? new ArrayList<>() : b.getNodes();
            if (sub.isEmpty()) {
                issues.add(new Issue(BLOCK, "V-5", nodeId,
                        "分支「" + bName + "」下没有任何节点，会让单据卡住"));
            }
            for (Node sn : sub) {
                if (SimpleFlowDef.T_CONDITION.equals(sn.getType())) {
                    issues.add(new Issue(BLOCK, "V-11", nodeId,
                            "条件分支不支持嵌套（分支「" + bName + "」内还有条件分支）"));
                }
                if (SimpleFlowDef.T_PARALLEL.equals(sn.getType())) {
                    issues.add(new Issue(BLOCK, "V-11", nodeId,
                            "条件分支内暂不支持并行分支（分支「" + bName + "」）"));
                }
            }
        }
        if (defaultCount == 0) {
            issues.add(new Issue(BLOCK, "V-5", nodeId, "条件分支「" + n.getName() + "」缺少「其他情况」兜底分支"));
        } else if (defaultCount > 1) {
            issues.add(new Issue(BLOCK, "V-5", nodeId, "条件分支「" + n.getName() + "」有多个「其他情况」兜底分支"));
        }
    }

    /** V-6 / V-12：并行分支 */
    private static void checkParallel(Node n, String nodeId, List<Issue> issues, Set<String> multiFormFields) {
        String src = StringUtils.defaultIfBlank(n.getBranchSource(), SimpleFlowDef.SRC_FORM_MULTI);
        if (!SimpleFlowDef.SRC_FORM_MULTI.equals(src)) {
            issues.add(new Issue(BLOCK, "V-12", nodeId,
                    "并行分支「" + n.getName() + "」暂只支持「按表单多选字段动态生成」"));
            return;
        }
        String field = StringUtils.trimToEmpty(n.getFormField());
        if (StringUtils.isBlank(field)) {
            issues.add(new Issue(BLOCK, "V-12", nodeId, "并行分支「" + n.getName() + "」未指定表单字段"));
        } else if (!field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            issues.add(new Issue(BLOCK, "V-12", nodeId, "并行分支引用的字段名不合法：" + field));
        } else if (multiFormFields != null && !multiFormFields.contains(field)) {
            issues.add(new Issue(BLOCK, "V-12", nodeId,
                    "并行分支引用的字段「" + field + "」不存在或不是多选字段"));
        }
        Assignee ba = n.getBranchAssignee();
        String bsrc = ba == null ? SimpleFlowDef.A_BRANCH_VALUE : StringUtils.defaultString(ba.getSource());
        if (!SimpleFlowDef.A_BRANCH_VALUE.equals(bsrc)) {
            issues.add(new Issue(BLOCK, "V-12", nodeId,
                    "并行分支「" + n.getName() + "」的参与人暂只支持「该分支对应的部门」"));
        }
        if (n.getMaxBranches() != null && n.getMaxBranches() <= 0) {
            issues.add(new Issue(BLOCK, "V-10", nodeId, "并行分支的分支上限必须大于 0"));
        }
    }

    /** 是否存在阻断项 */
    public static boolean hasBlock(List<Issue> issues) {
        for (Issue i : issues) {
            if (BLOCK.equals(i.getLevel())) {
                return true;
            }
        }
        return false;
    }
}
