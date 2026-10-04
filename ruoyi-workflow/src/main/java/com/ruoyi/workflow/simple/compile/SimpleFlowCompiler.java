package com.ruoyi.workflow.simple.compile;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.flowable.common.constant.ProcessConstants;
import com.ruoyi.workflow.simple.model.SimpleFlowDef;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Assignee;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Branch;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Group;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Node;
import com.ruoyi.workflow.simple.model.SimpleFlowDef.Row;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 简化流程编译器：设计器 JSON → BPMN 2.0 XML </p>
 *
 * <p> 产物直接交给现有部署链路 {@code POST /flowable/definition/save}
 * （{@code repositoryService.createDeployment().addInputStream(name + ".bpmn", in)}）。 </p>
 *
 * <p> 实现分两步：<b>先建图</b>（元素 + 顺序流，含条件与兜底），<b>再渲染</b> XML。
 * 这样网关的 {@code default} 属性、元素的 incoming/outgoing 都能在下游确定后回填，
 * 不会出现"先生成 XML 再打补丁"的错位。 </p>
 *
 * <h3> 编译硬约束（PRD 6.8，必须遵守） </h3>
 * <ol>
 *   <li> <b>条件分支一律编译为 {@code exclusiveGateway}</b>，条件写在网关的出口顺序流上，
 *        「其他情况」编译为该网关的 {@code default} 流。 </li>
 *   <li> <b>禁止把条件直接挂在 {@code userTask} 的多条出口顺序流上</b>——BPMN 原生语义下多条条件为真会
 *        生成多个执行并以并行方式继续，会让条件分支静默退化成并行分支
 *        （证据：{@code UI/components/Process/panel/conditionPanel.vue:11-13} 官方提示原文）。 </li>
 *   <li> 审批人沿用本项目既有约定：{@code flowable:assignee}/{@code flowable:candidateUsers}
 *        + {@code flowable:userType} + {@code flowable:dataType}；节点扩展参数写入
 *        {@code <flowable:properties>}（读取方 {@code FlowableUtil#getExtendVarByTaskDefinitionKey}）。 </li>
 * </ol>
 *
 * @author 二开
 */
public class SimpleFlowCompiler {

    /* ------------------------- BPMN 常量 ------------------------- */

    private static final String NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String NS_BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String NS_OMGDC = "http://www.omg.org/spec/DD/20100524/DC";
    private static final String NS_OMGDI = "http://www.omg.org/spec/DD/20100524/DI";
    private static final String NS_FLOWABLE = "http://flowable.org/bpmn";
    private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String NS_BIOC = "http://bpmn.io/schema/bpmn/biocolor/1.0";
    private static final String TARGET_NS = "http://www.flowable.org/processdef";
    private static final String XSI_FORMAL = "tFormalExpression";

    /**
     * 多实例参与人列表的「启动注入」监听器（实现见 simple/listener/ApprovalUsersInitListener）。
     *
     * <p> 「会签 / 或签 / 依次」的 {@code flowable:collection} 指向 {@code {nodeId}_approval}，
     * 而本项目运行时**并不产生**该变量，故由编译器在流程启动时注入。
     * 必须在启动时注入：首节点为会签时没有任何"上一节点"可以触发。 </p>
     */
    private static final String APPROVAL_INIT_LISTENER =
            "com.ruoyi.workflow.simple.listener.ApprovalUsersInitListener";

    /**
     * 会签 / 或签 / 依次：多实例的**元素变量名**。
     * 任务审批人必须绑定到它（{@code flowable:assignee="${assignee}"}），
     * 否则任务能被创建，但 ASSIGNEE_ 为 NULL，谁都认领不到。
     */
    private static final String MI_ELEMENT_VAR = "assignee";

    /** 是否「多人会签 / 或签 / 依次」——即由参与人列表驱动的多实例节点 */
    private static boolean isMultiInstanceNode(Node node) {
        String mm = StringUtils.defaultIfBlank(node.getMultiMode(), SimpleFlowDef.M_SINGLE);
        return SimpleFlowDef.M_AND.equals(mm) || SimpleFlowDef.M_OR.equals(mm)
                || SimpleFlowDef.M_SEQ.equals(mm);
    }

    private static final String ID_START = "start_event";
    private static final String ID_END = "end_event";

    /* ------------------------- 布局常量 ------------------------- */

    private static final int X0 = 240;
    private static final int Y0 = 200;
    private static final int DX = 170;
    private static final int DY = 130;
    private static final int W_TASK = 100;
    private static final int H_TASK = 80;
    private static final int S_EVENT = 30;
    private static final int S_END = 36;
    private static final int S_GATEWAY = 40;

    /* ------------------------- 图结构 ------------------------- */

    private static class Elem {
        String id;
        String kind;      // start | userTask | gateway | end
        String name;
        Node node;        // userTask 用
        int x, y, w, h;
        String defaultFlowId;   // 仅排他网关
        /** 参与人属性整段覆盖（并行会审等由 elementVariable 决定的场景） */
        String assigneeAttrs;
        /** 多实例：集合来源（null = 非多实例） */
        String miCollection;
        String miElementVar;
        boolean miSequential;
        String miCompletion;
        final List<String> incoming = new ArrayList<>();
        final List<String> outgoing = new ArrayList<>();
    }

    private static class Edge {
        String id;
        String from;
        String to;
        String condition;   // null = 无条件
    }

    private final Map<String, Elem> elems = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    private SimpleFlowDef def;
    private int seq = 0;

    /* ------------------------- 对外 API ------------------------- */

    /** JSON 文本 → BPMN XML */
    public static String compileJson(String json) {
        return compile(JSON.parseObject(json, SimpleFlowDef.class));
    }

    /** 模型 → BPMN XML */
    public static String compile(SimpleFlowDef def) {
        return new SimpleFlowCompiler().doCompile(def);
    }

    private String doCompile(SimpleFlowDef def) {
        if (def == null) {
            throw new IllegalArgumentException("流程定义为空");
        }
        if (StringUtils.isBlank(def.getKey())) {
            throw new IllegalArgumentException("流程 key 不能为空");
        }
        if (!def.getKey().matches("[A-Za-z_][A-Za-z0-9_\\-]*")) {
            throw new IllegalArgumentException("流程 key 不合法（字母/数字/下划线/中划线，且不以数字开头）：" + def.getKey());
        }
        this.def = def;

        List<Node> nodes = def.getNodes() == null ? new ArrayList<>() : def.getNodes();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("流程至少需要一个节点");
        }

        List<String> tails = new ArrayList<>();
        int column = 0;

        for (Node node : nodes) {
            String type = StringUtils.defaultString(node.getType());
            switch (type) {
                case SimpleFlowDef.T_START: {
                    Elem e = newElem(uniqueId(StringUtils.defaultIfBlank(node.getId(), ID_START)),
                            "start", StringUtils.defaultIfBlank(node.getName(), "发起"), null,
                            X0, Y0, S_EVENT, S_EVENT);
                    tails = one(e.id);
                    column = 1;
                    break;
                }
                case SimpleFlowDef.T_APPROVE:
                case SimpleFlowDef.T_HANDLE: {
                    Elem e = emitTask(node, uniqueId(node.getId()), X0 + column * DX, Y0);
                    for (String t : tails) {
                        addEdge(t, e.id, null);
                    }
                    tails = one(e.id);
                    column++;
                    break;
                }
                case SimpleFlowDef.T_CONDITION: {
                    CondResult r = emitCondition(node, column);
                    for (String t : tails) {
                        addEdge(t, r.gatewayId, null);
                    }
                    tails = r.tails;
                    column = r.usedColumns;
                    break;
                }
                case SimpleFlowDef.T_END: {
                    Elem e = newElem(uniqueId(StringUtils.defaultIfBlank(node.getId(), ID_END)),
                            "end", StringUtils.defaultIfBlank(node.getName(), "结束"), null,
                            X0 + (column + 1) * DX, Y0 + (H_TASK - S_END) / 2, S_END, S_END);
                    for (String t : tails) {
                        addEdge(t, e.id, null);
                    }
                    tails = one(e.id);
                    column += 2;
                    break;
                }
                case SimpleFlowDef.T_CC:
                    throw new UnsupportedOperationException("本次增量尚未支持「抄送节点」的编译（nodeId="
                            + node.getId() + "）；暂用现有发起/结束节点抄送能力（PRD 6.3）");
                case SimpleFlowDef.T_PARALLEL: {
                    // 并行会审 → 「多实例用户任务」，集合 = 表单多选字段的值列表。
                    // 真实引擎已验证（ParallelMiSmokeTest）：
                    //   集合有 N 个值 → N 个并行待办；全部完成自动合流；**集合为空自动跳过**；
                    //   但**变量不存在会直接报错**，所以提交链路必须保证该字段始终写入（空数组也要写）。
                    String src = StringUtils.defaultIfBlank(node.getBranchSource(), SimpleFlowDef.SRC_FORM_MULTI);
                    if (!SimpleFlowDef.SRC_FORM_MULTI.equals(src)) {
                        throw new UnsupportedOperationException("并行分支暂只支持「按表单多选字段动态生成」"
                                + "（nodeId=" + node.getId() + "，收到 branchSource=" + src + "）");
                    }
                    String field = StringUtils.trimToEmpty(node.getFormField());
                    if (StringUtils.isBlank(field) || !field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException("并行分支未指定合法的表单字段 __vModel__（nodeId="
                                + node.getId() + "）");
                    }
                    Assignee ba = node.getBranchAssignee();
                    String bsrc = ba == null ? SimpleFlowDef.A_BRANCH_VALUE : StringUtils.defaultString(ba.getSource());
                    if (!SimpleFlowDef.A_BRANCH_VALUE.equals(bsrc)) {
                        throw new UnsupportedOperationException("并行分支的参与人暂只支持「该分支对应的部门」"
                                + "（nodeId=" + node.getId() + "，收到 source=" + bsrc
                                + "）；「分支部门负责人」需要新增 EL 解析器");
                    }
                    Elem e = emitTask(node, uniqueId(node.getId()), X0 + column * DX, Y0);
                    e.assigneeAttrs = " flowable:assignee=\"${dept}\" flowable:userType=\""
                            + ProcessConstants.ASSIGNEE + "\" flowable:dataType=\"" + ProcessConstants.DYNAMIC + "\"";
                    e.miCollection = field;
                    e.miElementVar = "dept";
                    e.miSequential = false;
                    // 完成条件：
                    //   ANY（任一完成即合流）→ 显式条件
                    //   ALL（全部完成才合流）→ **不生成条件**，交给引擎默认语义 ——
                    //     并行多实例在「全部实例完成」后本就会自动完成，
                    //     显式写 == nrOfInstances 不但多余，在本环境还会坏事。
                    //
                    // 实测踩过：nrOfCompletedInstances 被存成
                    // bpmnParallelMultiInstanceCompleted 类型、数值为 NULL，
                    // 于是 ${nrOfCompletedInstances == nrOfInstances} 恒为 false ——
                    // 全部会审人审批完毕后活动任务归零，流程却永不结束（END_TIME_ 一直为空）。
                    e.miCompletion = SimpleFlowDef.JOIN_ANY.equals(node.getJoinMode())
                            ? "${nrOfCompletedInstances >= 1}"
                            : null;
                    for (String t : tails) {
                        addEdge(t, e.id, null);
                    }
                    tails = one(e.id);
                    column++;
                    break;
                }
                default:
                    throw new IllegalArgumentException("未知节点类型：" + type + "（nodeId=" + node.getId() + "）");
            }
        }

        return render();
    }

    /* ------------------------- 条件分支 ------------------------- */

    private static class CondResult {
        String gatewayId;
        List<String> tails;
        int usedColumns;
    }

    private CondResult emitCondition(Node node, int column) {
        List<Branch> branches = node.getBranches() == null ? new ArrayList<>() : node.getBranches();
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("条件分支没有任何分支（nodeId=" + node.getId() + "）");
        }
        Branch fallback = null;
        for (Branch b : branches) {
            if (b.isDefaultBranch()) {
                if (fallback != null) {
                    throw new IllegalArgumentException("条件分支只能有一个「其他情况」兜底分支（nodeId=" + node.getId() + "）");
                }
                fallback = b;
            }
        }
        if (fallback == null) {
            throw new IllegalArgumentException("条件分支缺少「其他情况」兜底分支（nodeId=" + node.getId() + "）");
        }

        int gwX = X0 + column * DX;
        Elem gw = newElem(uniqueId(StringUtils.defaultIfBlank(node.getId(), null)), "gateway",
                StringUtils.defaultIfBlank(node.getName(), "条件分支"), null,
                gwX, Y0 + (H_TASK - S_GATEWAY) / 2, S_GATEWAY, S_GATEWAY);

        List<String> tails = new ArrayList<>();
        int lane = 0;
        int maxSub = 1;
        for (Branch b : branches) {
            List<Node> sub = b.getNodes() == null ? new ArrayList<>() : b.getNodes();
            if (sub.isEmpty()) {
                throw new IllegalArgumentException("分支「" + StringUtils.defaultIfBlank(b.getName(), b.getId())
                        + "」下没有任何节点（空分支会让单据卡住）");
            }
            int laneY = Y0 + (lane + 1) * DY;
            String first = null;
            String last = null;
            int col = 1;
            for (Node sn : sub) {
                if (!SimpleFlowDef.T_APPROVE.equals(sn.getType()) && !SimpleFlowDef.T_HANDLE.equals(sn.getType())) {
                    throw new UnsupportedOperationException("分支内暂只支持审批/办理节点，收到："
                            + sn.getType() + "（nodeId=" + sn.getId() + "）");
                }
                Elem se = emitTask(sn, uniqueId(sn.getId()), X0 + col * DX, laneY);
                if (last != null) {
                    addEdge(last, se.id, null);
                }
                if (first == null) {
                    first = se.id;
                }
                last = se.id;
                col++;
            }
            Edge e;
            if (b.isDefaultBranch()) {
                // 兜底分支：不带条件，登记为网关的 default 流
                e = addEdge(gw.id, first, null);
                gw.defaultFlowId = e.id;
            } else {
                // 整句包进一对 ${}（见 toExpression 说明），否则运行期报 non-Boolean
                e = addEdge(gw.id, first, "${" + toExpression(b) + "}");
            }
            tails.add(last);
            maxSub = Math.max(maxSub, col - 1);
            lane++;
        }

        CondResult r = new CondResult();
        r.gatewayId = gw.id;
        r.tails = tails;
        r.usedColumns = maxSub + 2;
        return r;
    }

    /* ------------------------- 节点 ------------------------- */

    private Elem emitTask(Node node, String id, int x, int y) {
        Elem e = newElem(id, "userTask", StringUtils.defaultIfBlank(node.getName(), "审批"),
                node, x, y, W_TASK, H_TASK);
        return e;
    }

    private Elem newElem(String id, String kind, String name, Node node, int x, int y, int w, int h) {
        Elem e = new Elem();
        e.id = id;
        e.kind = kind;
        e.name = name;
        e.node = node;
        e.x = x;
        e.y = y;
        e.w = w;
        e.h = h;
        elems.put(id, e);
        return e;
    }

    private Edge addEdge(String from, String to, String condition) {
        Edge e = new Edge();
        e.id = "Flow_" + (++seq);
        e.from = from;
        e.to = to;
        e.condition = condition;
        edges.add(e);
        Elem fe = elems.get(from);
        Elem te = elems.get(to);
        if (fe != null) {
            fe.outgoing.add(e.id);
        }
        if (te != null) {
            te.incoming.add(e.id);
        }
        return e;
    }

    /* ------------------------- 渲染 ------------------------- */

    /**
     * 收集「会签 / 或签 / 依次」节点的参与人列表，供启动监听器注入。
     *
     * <p> 这些节点的 {@code flowable:collection} 指向 {@code {nodeId}_approval}，
     * 而本项目运行时不产生该变量（实测报 {@code Variable 'xxx_approval' was not found}），
     * 因此改由编译器在流程启动时注入 —— 挂 <b>start</b> 而非节点上，
     * 是因为首节点为会签时没有任何"上一节点"可以触发。 </p>
     *
     * <p> 只支持「指定人员」来源：角色 / 部门负责人等需要运行时解析，编译器无从得知；
     * 与其生成一份到运行期才炸的 BPMN，不如在**发布时**就明确报错。 </p>
     *
     * @return {@code "n1=u1,u2;n2=u3"}；无多实例节点时返回空串
     */
    private String collectApprovalVars() {
        StringBuilder sb = new StringBuilder();
        for (Elem e : elems.values()) {
            if (!"userTask".equals(e.kind) || e.node == null) {
                continue;
            }
            String mm = StringUtils.defaultIfBlank(e.node.getMultiMode(), SimpleFlowDef.M_SINGLE);
            boolean multi = SimpleFlowDef.M_AND.equals(mm) || SimpleFlowDef.M_OR.equals(mm)
                    || SimpleFlowDef.M_SEQ.equals(mm);
            if (!multi) {
                continue;
            }
            Assignee a = e.node.getAssignee();
            List<String> ids = a == null ? null : a.getUserIds();
            if (ids == null || ids.isEmpty()) {
                throw new UnsupportedOperationException("多人会签 / 或签 / 依次节点暂只支持「指定人员」参与人"
                        + "（nodeId=" + e.node.getId() + "，multiMode=" + mm
                        + "）；角色 / 部门负责人等需运行时解析的来源请改用单人节点");
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.id).append('=').append(String.join(",", ids));
        }
        return sb.toString();
    }

    private String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<definitions xmlns=\"").append(NS_BPMN).append("\"")
                .append(" xmlns:xsi=\"").append(NS_XSI).append("\"")
                .append(" xmlns:bpmndi=\"").append(NS_BPMNDI).append("\"")
                .append(" xmlns:omgdc=\"").append(NS_OMGDC).append("\"")
                .append(" xmlns:bioc=\"").append(NS_BIOC).append("\"")
                .append(" xmlns:flowable=\"").append(NS_FLOWABLE).append("\"")
                .append(" xmlns:di=\"").append(NS_OMGDI).append("\"")
                .append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"")
                .append(" targetNamespace=\"").append(TARGET_NS).append("\">\n");
        // isExecutable 是 BPMN 规约要求的可执行标记（缺省按 false 解释，部分工具会因此拒绝部署）
        sb.append("  <process id=\"").append(def.getKey()).append("\" name=\"")
                .append(esc(StringUtils.defaultIfBlank(def.getName(), def.getKey())))
                .append("\" isExecutable=\"true\">\n");

        // 多实例参与人列表：必须作为 <process> 的**第一个子元素**（BPMN 的 tBaseElement 要求
        // extensionElements 在流元素之前），否则引擎解析不到。
        String approvalVars = collectApprovalVars();
        if (StringUtils.isNotBlank(approvalVars)) {
            sb.append("    <extensionElements>\n");
            sb.append("      <flowable:executionListener event=\"start\" class=\"")
                    .append(APPROVAL_INIT_LISTENER).append("\">\n");
            sb.append("        <flowable:field name=\"vars\" stringValue=\"")
                    .append(esc(approvalVars)).append("\"/>\n");
            sb.append("      </flowable:executionListener>\n");
            sb.append("    </extensionElements>\n");
        }

        // 流元素
        for (Elem e : elems.values()) {
            switch (e.kind) {
                case "start":
                    sb.append("    <startEvent id=\"").append(e.id).append("\" name=\"").append(esc(e.name)).append("\">")
                            .append(io(e)).append("</startEvent>\n");
                    break;
                case "end":
                    sb.append("    <endEvent id=\"").append(e.id).append("\" name=\"").append(esc(e.name)).append("\">")
                            .append(io(e)).append("</endEvent>\n");
                    break;
                case "gateway":
                    sb.append("    <exclusiveGateway id=\"").append(e.id).append("\" name=\"").append(esc(e.name)).append("\"");
                    if (StringUtils.isNotBlank(e.defaultFlowId)) {
                        sb.append(" default=\"").append(e.defaultFlowId).append("\"");
                    }
                    sb.append(">").append(io(e)).append("</exclusiveGateway>\n");
                    break;
                case "userTask":
                    sb.append(renderUserTask(e));
                    break;
                default:
                    throw new IllegalStateException("未知元素类型：" + e.kind);
            }
        }

        // 顺序流（统一放在后面，Flowable 解析器支持前向引用）
        for (Edge e : edges) {
            sb.append("    <sequenceFlow id=\"").append(e.id)
                    .append("\" sourceRef=\"").append(e.from)
                    .append("\" targetRef=\"").append(e.to).append("\"");
            if (StringUtils.isBlank(e.condition)) {
                sb.append(" />\n");
            } else {
                sb.append(">\n      <conditionExpression xsi:type=\"").append(XSI_FORMAL)
                        .append("\"><![CDATA[").append(e.condition).append("]]></conditionExpression>\n")
                        .append("    </sequenceFlow>\n");
            }
        }

        sb.append("  </process>\n");
        sb.append(renderDiagram());
        sb.append("</definitions>\n");
        return sb.toString();
    }

    private String renderUserTask(Elem e) {
        Node node = e.node;
        StringBuilder sb = new StringBuilder();
        sb.append("    <userTask id=\"").append(e.id).append("\" name=\"").append(esc(e.name)).append("\"");

        Assignee a = node.getAssignee();
        String assigneeExpr = null;
        String candidateExpr = null;
        String dataType = "fixed";
        if (StringUtils.isNotBlank(e.assigneeAttrs)) {
            // 并行会审：每实例参与人由 elementVariable 决定，属性整段给出
            sb.append(e.assigneeAttrs);
            dataType = null;
        } else if (a != null) {
            String source = StringUtils.defaultString(a.getSource());
            switch (source) {
                case SimpleFlowDef.S_USER: {
                    List<String> users = a.getUserIds() == null ? new ArrayList<>() : a.getUserIds();
                    if (users.size() == 1) {
                        assigneeExpr = users.get(0);
                    } else if (users.size() > 1) {
                        candidateExpr = String.join(",", users);
                    } else {
                        throw new IllegalArgumentException("「指定人员」未选择人员（nodeId=" + node.getId() + "）");
                    }
                    break;
                }
                case SimpleFlowDef.S_ROLE: {
                    List<String> roles = a.getRoleIds() == null ? new ArrayList<>() : a.getRoleIds();
                    if (roles.isEmpty()) {
                        throw new IllegalArgumentException("「角色」未选择角色（nodeId=" + node.getId() + "）");
                    }
                    dataType = ProcessConstants.ROLES;
                    candidateExpr = String.join(",", roles);
                    break;
                }
                case SimpleFlowDef.S_INITIATOR: {
                    dataType = ProcessConstants.DYNAMIC;
                    assigneeExpr = "${" + ProcessConstants.PROCESS_INITIATOR + "}";
                    break;
                }
                case SimpleFlowDef.S_DEPT_LEADER:
                case SimpleFlowDef.S_LEADER: {
                    // 动态解析：运行时由引擎按范围选人（本项目 FlowCommonService:382-452）
                    dataType = ProcessConstants.DYNAMIC;
                    assigneeExpr = "${" + e.id + ProcessConstants.PROCESS_HANDLER_SUFFIX + "}";
                    break;
                }
                default:
                    throw new UnsupportedOperationException("本次增量尚未支持参与人来源：" + source
                            + "（nodeId=" + node.getId() + "）");
            }
        }

        // 会签 / 或签 / 依次：任务由多实例按参与人列表逐条创建，任务的审批人必须绑定到
        // **多实例的元素变量**（${assignee}），不能写成 candidateUsers ——
        // candidateUsers 生成的是"候选人"任务（ASSIGNEE_ 恒为 NULL），与元素变量毫无关联，
        // 结果是任务建出来了却无人认领（实测：两个会签任务 ASSIGNEE_ 全为 NULL，
        // 在各人的"我的待办"里都看不到）。
        // 注意排除并行会审：它走 e.assigneeAttrs（集合来自表单字段，元素变量是 dept）。
        boolean miByApprovalList = StringUtils.isBlank(e.miCollection) && isMultiInstanceNode(node);
        if (miByApprovalList) {
            sb.append(" flowable:assignee=\"${").append(MI_ELEMENT_VAR).append("}\"");
            sb.append(" flowable:userType=\"").append(ProcessConstants.ASSIGNEE).append("\"");
            dataType = ProcessConstants.DYNAMIC;
        } else if (StringUtils.isNotBlank(assigneeExpr)) {
            sb.append(" flowable:assignee=\"").append(esc(assigneeExpr)).append("\"");
            sb.append(" flowable:userType=\"").append(ProcessConstants.ASSIGNEE).append("\"");
        } else if (StringUtils.isNotBlank(candidateExpr)) {
            sb.append(" flowable:candidateUsers=\"").append(esc(candidateExpr)).append("\"");
            sb.append(" flowable:userType=\"").append(ProcessConstants.CANDIDATE_USERS).append("\"");
        }
        if (StringUtils.isNotBlank(dataType)) {
            sb.append(" flowable:dataType=\"").append(dataType).append("\">\n");
        } else {
            sb.append(">\n");
        }

        // 1) 扩展参数
        sb.append(buildExtensionProperties(node));
        // 2) incoming / outgoing
        sb.append(io(e));
        // 3) 多实例
        if (StringUtils.isNotBlank(e.miCollection)) {
            // 并行会审：集合来自表单多选字段（真实引擎已验证：集合为空会自动跳过）
            appendMultiInstance(sb, e.miSequential, e.miCollection, e.miElementVar, e.miCompletion);
        } else {
            // 会签 / 或签 / 依次：集合由运行时按参与人解析结果注入（沿用本项目既有约定）
            String multiMode = StringUtils.defaultIfBlank(node.getMultiMode(), SimpleFlowDef.M_SINGLE);
            if (SimpleFlowDef.M_AND.equals(multiMode) || SimpleFlowDef.M_OR.equals(multiMode)
                    || SimpleFlowDef.M_SEQ.equals(multiMode)) {
                // 完成条件同并行分支：只有「任一完成」（或签）需要显式条件；
                // 「全部完成」（会签 AND / 依次 SEQ）交给引擎默认语义，
                // 避免踩 nrOfCompletedInstances 在本环境不可比较的坑（见并行分支处的注释）。
                String completion = SimpleFlowDef.M_OR.equals(multiMode)
                        ? "${nrOfCompletedInstances >= 1}"
                        : null;
                boolean sequential = SimpleFlowDef.M_SEQ.equals(multiMode);
                appendMultiInstance(sb, sequential, e.id + "_approval", MI_ELEMENT_VAR, completion);
            }
        }
        sb.append("    </userTask>\n");
        return sb.toString();
    }

    private void appendMultiInstance(StringBuilder sb, boolean sequential, String collection,
                                     String elementVariable, String completion) {
        sb.append("      <multiInstanceLoopCharacteristics isSequential=\"").append(sequential)
                .append("\" flowable:collection=\"").append(collection)
                .append("\" flowable:elementVariable=\"").append(elementVariable).append("\">\n");
        // completion 为空白表示「不设完成条件」—— 交给引擎默认语义（全部实例完成即完成）。
        // ⚠ 必须判空：原实现无条件输出，若传 null 会写出 <![CDATA[null]]>，
        //   引擎会把它当表达式求值，比「不写条件」更糟。
        if (StringUtils.isNotBlank(completion)) {
            sb.append("        <completionCondition xsi:type=\"").append(XSI_FORMAL)
                    .append("\"><![CDATA[").append(completion).append("]]></completionCondition>\n");
        }
        sb.append("      </multiInstanceLoopCharacteristics>\n");
    }

    private String io(Elem e) {
        StringBuilder sb = new StringBuilder();
        for (String f : e.incoming) {
            sb.append("\n      <incoming>").append(f).append("</incoming>");
        }
        for (String f : e.outgoing) {
            sb.append("\n      <outgoing>").append(f).append("</outgoing>");
        }
        return sb.length() == 0 ? "" : sb.append("\n    ").toString();
    }

    /**
     * 节点扩展参数 → {@code <flowable:properties>}
     * <p>读取方 {@code FlowableUtil#getExtendVarByTaskDefinitionKey}（properties → property → name/value）。</p>
     */
    private String buildExtensionProperties(Node node) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("nodeKind", SimpleFlowDef.T_HANDLE.equals(node.getType()) ? "handle" : "approve");
        props.put("signMode", StringUtils.defaultIfBlank(node.getSignMode(), "OPTIONAL"));
        if (node.getSignTypes() != null && !node.getSignTypes().isEmpty()) {
            props.put("signTypes", String.join(",", node.getSignTypes()));
        }
        if (node.getButtons() != null && !node.getButtons().isEmpty()) {
            props.put("buttons", String.join(",", node.getButtons()));
        }
        if (node.getFieldReadonly() != null && !node.getFieldReadonly().isEmpty()) {
            props.put("fieldReadonly", String.join(",", node.getFieldReadonly()));
        }
        if (node.getTimeoutHours() != null && node.getTimeoutHours() > 0) {
            props.put("timeoutHours", String.valueOf(node.getTimeoutHours()));
        }
        props.put("sameUserPolicy", StringUtils.defaultIfBlank(node.getSameUserPolicy(), SimpleFlowDef.SAME_EACH_TIME));
        props.put("emptyPolicy", StringUtils.defaultIfBlank(node.getEmptyPolicy(), "ADMIN"));
        props.put("selfPolicy", StringUtils.defaultIfBlank(node.getSelfPolicy(), "SKIP"));
        props.put("leftPolicy", StringUtils.defaultIfBlank(node.getLeftPolicy(), "LEADER"));

        StringBuilder sb = new StringBuilder();
        sb.append("      <extensionElements>\n        <flowable:properties>\n");
        for (Map.Entry<String, String> en : props.entrySet()) {
            sb.append("          <flowable:property name=\"").append(esc(en.getKey()))
                    .append("\" value=\"").append(esc(en.getValue())).append("\" />\n");
        }
        sb.append("        </flowable:properties>\n      </extensionElements>\n");
        return sb.toString();
    }

    private String renderDiagram() {
        StringBuilder sb = new StringBuilder();
        sb.append("  <bpmndi:BPMNDiagram id=\"BPMNDiagram_").append(def.getKey()).append("\">\n");
        sb.append("    <bpmndi:BPMNPlane id=\"BPMNPlane_").append(def.getKey())
                .append("\" bpmnElement=\"").append(def.getKey()).append("\">\n");
        for (Elem e : elems.values()) {
            sb.append("      <bpmndi:BPMNShape id=\"").append(e.id).append("_di\" bpmnElement=\"")
                    .append(e.id).append("\">\n");
            sb.append("        <omgdc:Bounds x=\"").append(e.x).append("\" y=\"").append(e.y)
                    .append("\" width=\"").append(e.w).append("\" height=\"").append(e.h).append("\" />\n");
            sb.append("      </bpmndi:BPMNShape>\n");
        }
        for (Edge e : edges) {
            Elem f = elems.get(e.from);
            Elem t = elems.get(e.to);
            if (f == null || t == null) {
                continue;
            }
            sb.append("      <bpmndi:BPMNEdge id=\"").append(e.id).append("_di\" bpmnElement=\"")
                    .append(e.id).append("\">\n");
            sb.append("        <di:waypoint x=\"").append(f.x + f.w).append("\" y=\"").append(f.y + f.h / 2).append("\" />\n");
            sb.append("        <di:waypoint x=\"").append(t.x).append("\" y=\"").append(t.y + t.h / 2).append("\" />\n");
            sb.append("      </bpmndi:BPMNEdge>\n");
        }
        sb.append("    </bpmndi:BPMNPlane>\n  </bpmndi:BPMNDiagram>\n");
        return sb.toString();
    }

    /* ------------------------- 条件表达式 ------------------------- */

    /**
     * 图形化条件组 → EL 表达式<b>内部文本</b>（不含外层 <code>${}</code>）：组内「且」，组间「或」
     *
     * <p> <b>调用方必须把整句包进一对 <code>${}</code></b>，形如 <code>${(A &amp;&amp; B) || C}</code>。
     * 不能写成 <code>${A} &amp;&amp; ${B}</code>——那样 EL 只把第一个 <code>${}</code> 当表达式，
     * 整体变成"取值表达式 + 字面量尾巴"，求值返回字符串，运行期报
     * <code>condition expression returns non-Boolean</code>
     * （此坑已由 {@code FlowEngineSmokeTest} 在真实 Flowable 引擎上捕获）。 </p>
     */
    public static String toExpression(Branch branch) {
        List<Group> groups = branch.getGroups() == null ? new ArrayList<>() : branch.getGroups();
        List<String> ors = new ArrayList<>();
        for (Group g : groups) {
            List<Row> rows = g.getRows() == null ? new ArrayList<>() : g.getRows();
            List<String> ands = new ArrayList<>();
            for (Row r : rows) {
                ands.add(rowToExpression(r));
            }
            if (!ands.isEmpty()) {
                ors.add(ands.size() == 1 ? ands.get(0) : "(" + String.join(" && ", ands) + ")");
            }
        }
        if (ors.isEmpty()) {
            throw new IllegalArgumentException("分支「" + StringUtils.defaultIfBlank(branch.getName(), branch.getId())
                    + "」没有任何条件（无条件分支会让「其他情况」永不执行）");
        }
        return ors.size() == 1 ? ors.get(0) : "(" + String.join(" || ", ors) + ")";
    }

    private static String rowToExpression(Row r) {
        String field = StringUtils.trimToEmpty(r.getField());
        String op = StringUtils.upperCase(StringUtils.trimToEmpty(r.getOp()));
        if (StringUtils.isBlank(field) || StringUtils.isBlank(op)) {
            throw new IllegalArgumentException("条件行缺少字段或比较符");
        }
        // 字段名必须是合法标识符，避免把任意文本拼进 EL（图形化组件本不该产生这种输入）
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("条件字段名不合法：" + field);
        }
        String f = field;
        switch (op) {
            case "EQ":
                return f + " == " + literal(r.getValue());
            case "NE":
                return f + " != " + literal(r.getValue());
            case "GT":
                return f + " > " + literal(r.getValue());
            case "GE":
                return f + " >= " + literal(r.getValue());
            case "LT":
                return f + " < " + literal(r.getValue());
            case "LE":
                return f + " <= " + literal(r.getValue());
            case "CONTAINS":
            case "IN":
                return f + ".contains(" + literal(r.getValue()) + ")";
            case "NOT_CONTAINS":
            case "NOT_IN":
                return "!" + f + ".contains(" + literal(r.getValue()) + ")";
            case "EMPTY":
                return "empty " + f;
            case "NOT_EMPTY":
                return "not empty " + f;
            default:
                throw new IllegalArgumentException("暂不支持的比较符：" + op);
        }
    }

    /** 值字面量：字符串单引号（含单引号则用双引号），数字/布尔原样 */
    private static String literal(Object v) {
        if (v == null) {
            return "''";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (s.contains("'")) {
            if (s.contains("\"")) {
                throw new IllegalArgumentException("条件值不能同时包含单双引号：" + s);
            }
            return "\"" + s + "\"";
        }
        return "'" + s + "'";
    }

    /* ------------------------- 工具 ------------------------- */

    private static List<String> one(String s) {
        List<String> l = new ArrayList<>(1);
        l.add(s);
        return l;
    }

    private String uniqueId(String wanted) {
        String base = StringUtils.isNotBlank(wanted) ? wanted : "Activity_" + (++seq);
        String id = base;
        int n = 1;
        while (elems.containsKey(id)) {
            id = base + "_" + (n++);
        }
        return id;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
