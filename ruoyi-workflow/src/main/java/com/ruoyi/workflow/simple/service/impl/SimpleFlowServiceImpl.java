package com.ruoyi.workflow.simple.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.factory.FlowServiceFactory;
import com.ruoyi.flowable.service.IFlowDefinitionService;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.domain.FlowSimpleHistory;
import com.ruoyi.workflow.mapper.FlowSimpleHistoryMapper;
import com.ruoyi.workflow.mapper.FlowSimpleMapper;
import com.ruoyi.workflow.simple.compile.SimpleFlowCompiler;
import com.ruoyi.workflow.simple.model.SimpleFlowDef;
import com.ruoyi.workflow.simple.service.ISimpleFlowService;
import com.ruoyi.workflow.simple.validate.SimpleFlowValidator;
import com.ruoyi.workflow.simple.validate.SimpleFlowValidator.Issue;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateNodeFieldAuth;
import com.ruoyi.template.service.ITemplateNodeFieldAuthService;
import com.ruoyi.template.service.ITemplateService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <p> 简化流程设计器服务实现 </p>
 *
 * <p> 发布链路刻意<b>复用现有部署方法</b> {@link IFlowDefinitionService#saveFile}，
 * 不另起一套部署逻辑；因此部署后的流程与手工用 BPMN 设计器发布的流程完全同源。 </p>
 *
 * @author 二开
 */
@Slf4j
@Service
public class SimpleFlowServiceImpl extends FlowServiceFactory implements ISimpleFlowService {

    /** 草稿 */
    private static final String STATUS_DRAFT = "0";
    /** 已发布 */
    private static final String STATUS_PUBLISHED = "1";
    /** 流程模式：0-简化流程（B1 §4.1，与 t_template.flow_mode 同口径） */
    public static final String FLOW_MODE_SIMPLE = "0";
    /**
     * 模板派生流程标识的前缀（B1 §4.1）。
     *
     * <p> <b>派生规则只有这一处定义</b> —— {@code tools/audit/audit-flow-template-binding.js}
     * 会检查全仓没有第二处拼 {@code tpl_} 前缀的地方，避免前端/后端各算一套导致标识对不上。 </p>
     */
    public static final String TEMPLATE_DEF_KEY_PREFIX = "tpl_";
    /** 取模板ID的前 8 位做后缀 */
    private static final int TEMPLATE_DEF_KEY_ID_LEN = 8;

    @Autowired
    private FlowSimpleMapper flowSimpleMapper;

    @Autowired
    private FlowSimpleHistoryMapper historyMapper;

    @Autowired
    private IFlowDefinitionService flowDefinitionService;

    @Autowired
    private ITemplateService templateService;

    @Autowired
    private ITemplateNodeFieldAuthService nodeFieldAuthService;

    @Override
    public List<FlowSimple> selectList(FlowSimple query) {
        return flowSimpleMapper.selectList(query);
    }

    @Override
    public FlowSimple selectById(String id) {
        return flowSimpleMapper.selectById(id);
    }

    @Override
    public int saveDraft(FlowSimple flowSimple) {
        if (flowSimple == null || StringUtils.isBlank(flowSimple.getContent())) {
            throw new ServiceException("流程内容不能为空");
        }
        // 内容必须是合法 JSON，且 key 合法——不等到发布才报错
        SimpleFlowDef def = parse(flowSimple.getContent());
        List<Issue> issues = SimpleFlowValidator.validate(def, null, null);
        List<Issue> fatal = issues.stream()
                .filter(i -> SimpleFlowValidator.BLOCK.equals(i.getLevel()) && "V-0".equals(i.getRule()))
                .collect(Collectors.toList());
        if (!fatal.isEmpty()) {
            throw new ServiceException("流程基本信息有误：" + fatal.get(0).getMessage());
        }

        String userId = SecurityUtils.getUserId();
        String userName = SecurityUtils.getUsername();

        if (StringUtils.isBlank(flowSimple.getId())) {
            // 新增：def_key 在未删除范围内唯一
            FlowSimple exist = flowSimpleMapper.selectByDefKey(def.getKey());
            if (exist != null) {
                throw new ServiceException("流程 key 已存在：" + def.getKey() + "（请换一个，或先删除原流程）");
            }
            flowSimple.setId(uuid());
            flowSimple.setDefKey(def.getKey());
            flowSimple.setName(def.getName());
            flowSimple.setCategory(def.getCategory());
            flowSimple.setSchemaVersion(def.getSchemaVersion());
            flowSimple.setStatus(STATUS_DRAFT);
            flowSimple.setVersion(0);
            flowSimple.setCreateId(userId);
            flowSimple.setCreateBy(userName);
            flowSimple.setUpdateId(userId);
            flowSimple.setUpdateBy(userName);
            return flowSimpleMapper.insert(flowSimple);
        }

        FlowSimple db = flowSimpleMapper.selectById(flowSimple.getId());
        if (db == null) {
            throw new ServiceException("流程不存在或已删除：" + flowSimple.getId());
        }
        // 2.0（B1 §3.4）：编辑已绑定模板的流程，同样要模板级授权（未绑定模板则跳过）
        templateService.checkFlowManageByFlowId(flowSimple.getId());
        if (STATUS_PUBLISHED.equals(db.getStatus()) && !StringUtils.equals(db.getDefKey(), def.getKey())) {
            throw new ServiceException("已发布的流程不允许修改 key");
        }
        FlowSimple other = flowSimpleMapper.selectByDefKey(def.getKey());
        if (other != null && !other.getId().equals(flowSimple.getId())) {
            throw new ServiceException("流程 key 已被其他流程占用：" + def.getKey());
        }
        flowSimple.setDefKey(def.getKey());
        flowSimple.setName(def.getName());
        flowSimple.setCategory(def.getCategory());
        flowSimple.setSchemaVersion(def.getSchemaVersion());
        flowSimple.setUpdateId(userId);
        flowSimple.setUpdateBy(userName);
        return flowSimpleMapper.update(flowSimple);
    }

    @Override
    public List<Issue> validate(String id, List<String> requiredFields, List<String> multiFields) {
        FlowSimple db = requireById(id);
        SimpleFlowDef def = parse(db.getContent());
        return SimpleFlowValidator.validate(def,
                requiredFields == null ? null : new java.util.HashSet<>(requiredFields),
                multiFields == null ? null : new java.util.HashSet<>(multiFields));
    }

    @Override
    public FlowSimple publish(String id, String remark) {
        return publish(id, remark, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowSimple publish(String id, String remark, String templateId) {
        // 0) 模板级授权（2.0 B1 §3.4，REQ-PERM-005）：该流程若已绑定模板，
        //    只有流程管理员/创建人/有模板编辑权限者能发布；未绑定模板则不做此校验。
        //    必须放在最前面：失败时流程定义与模板绑定都不允许有任何变化。
        templateService.checkFlowManageByFlowId(id);
        FlowSimple db = requireById(id);
        SimpleFlowDef def = parse(db.getContent());

        // 1) 发布校验（不带表单字段集合：字段级校验由设计器在调用前传入，见 validate）
        List<Issue> issues = SimpleFlowValidator.validate(def, null, null);
        if (SimpleFlowValidator.hasBlock(issues)) {
            String msg = issues.stream()
                    .filter(i -> SimpleFlowValidator.BLOCK.equals(i.getLevel()))
                    .limit(5)
                    .map(Issue::toString)
                    .collect(Collectors.joining("；"));
            throw new ServiceException("发布校验未通过：" + msg);
        }

        // 2) 编译为 BPMN XML
        String xml;
        try {
            xml = SimpleFlowCompiler.compile(def);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new ServiceException("编译失败：" + e.getMessage());
        }

        // 3) 部署（复用现有链路；同 key 再次部署 = Flowable 自动升版本）
        try {
            flowDefinitionService.saveFile(def.getName(), StringUtils.defaultString(def.getCategory()), xml);
        } catch (Exception e) {
            throw new ServiceException("部署失败：" + e.getMessage());
        }

        // 4) 取回本次部署的流程定义
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(def.getKey())
                .latestVersion()
                .singleResult();
        String deployId = pd == null ? null : pd.getDeploymentId();
        String procDefId = pd == null ? null : pd.getId();

        // 5) 版本 +1 并写快照
        Integer max = historyMapper.selectMaxVersion(def.getKey());
        int nextVersion = (max == null ? 0 : max) + 1;

        FlowSimpleHistory his = new FlowSimpleHistory();
        his.setId(uuid());
        his.setDefKey(def.getKey());
        his.setVersion(nextVersion);
        his.setName(def.getName());
        his.setCategory(def.getCategory());
        his.setContent(db.getContent());
        his.setSchemaVersion(def.getSchemaVersion());
        his.setDeployId(deployId);
        his.setProcDefId(procDefId);
        his.setPublisherId(SecurityUtils.getUserId());
        his.setPublisherName(SecurityUtils.getUsername());
        his.setRemark(remark);
        historyMapper.insert(his);

        // 6) 回写主表
        FlowSimple upd = new FlowSimple();
        upd.setId(db.getId());
        upd.setStatus(STATUS_PUBLISHED);
        upd.setVersion(nextVersion);
        upd.setDeployId(deployId);
        upd.setProcDefId(procDefId);
        upd.setUpdateId(SecurityUtils.getUserId());
        upd.setUpdateBy(SecurityUtils.getUsername());
        flowSimpleMapper.update(upd);

        // 7) 节点级字段权限落库（AC-12）
        //    设计器把「本节点只读字段」写在流程 JSON 的 node.fieldReadonly 里，
        //    发布时**同时落库**，供提交时做服务端强制 —— 只靠前端置灰不算通过。
        syncNodeFieldAuth(def);

        // 8) 回写模板绑定（2.0 B1 §4.2/§4.3/§4.4）
        //    与部署同一事务：回写失败（如模板已被删）会连带回滚本次部署，
        //    不留下"界面说发布了、发起时找不到流程"的静默悬空态。
        //    第 4.3 条：回写只认当前启用行 —— 调用方给 templateId 就用它，
        //    没给（rollback 等内部调用）就按 simple_flow_id 反查，同样落在启用行上。
        String bindTemplateId = StringUtils.isNotBlank(templateId)
                ? templateId
                : templateService.getTemplateIdBySimpleFlowId(db.getId());
        if (StringUtils.isNotBlank(bindTemplateId)) {
            templateService.saveFlowBinding(bindTemplateId, db.getId(), def.getKey(), FLOW_MODE_SIMPLE);
        }

        return flowSimpleMapper.selectById(db.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowSimple getOrCreateByTemplate(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            throw new ServiceException("模板ID为空");
        }
        // 授权：能管理这条模板的流程，才能取/建它的草稿（§3.4）
        templateService.checkFlowManagePermission(templateId);
        Template template = templateService.getTemplateById(templateId);
        if (template == null || "1".equals(template.getDelFlag())) {
            throw new ServiceException("未找到模板：" + templateId);
        }

        // 1) 已绑定且草稿还在 → 直接返回，**绝不覆盖已有草稿**（§4.1：连续调用返回同一个流程 id）
        if (StringUtils.isNotBlank(template.getSimpleFlowId())) {
            FlowSimple bound = flowSimpleMapper.selectById(template.getSimpleFlowId());
            if (bound != null) {
                return bound;
            }
            // 绑定指向的草稿已被删除：清掉悬空引用后重建
            log.warn("模板绑定的流程草稿不存在，重建草稿：templateId={} staleFlowId={}",
                    templateId, template.getSimpleFlowId());
        }

        // 2) 流程标识由系统派生（用户不手输）：tpl_ + 模板ID 前 8 位
        String defKey = uniqueDefKey(buildTemplateDefKey(templateId));

        // 3) 建草稿
        String userId = SecurityUtils.getUserId();
        String userName = SecurityUtils.getUsername();
        FlowSimple flow = new FlowSimple();
        flow.setId(uuid());
        flow.setDefKey(defKey);
        flow.setName(StringUtils.defaultIfBlank(template.getName(), defKey));
        flow.setCategory(StringUtils.defaultIfBlank(template.getType(), "template"));
        flow.setContent(defaultDraftContent(template, defKey));
        flow.setSchemaVersion(1);
        flow.setStatus(STATUS_DRAFT);
        flow.setVersion(0);
        flow.setCreateId(userId);
        flow.setCreateBy(userName);
        flow.setUpdateId(userId);
        flow.setUpdateBy(userName);
        flowSimpleMapper.insert(flow);

        // 4) 回写绑定：此时还没发布，defKey 传 null（不动模板已有的 def_key）
        templateService.saveFlowBinding(templateId, flow.getId(), null, FLOW_MODE_SIMPLE);
        return flowSimpleMapper.selectById(flow.getId());
    }

    /**
     * 流程标识派生规则：{@code tpl_} + 模板ID前 8 位。
     *
     * <p> <b>全仓唯一一处定义</b>（静态审计会校验）——前端只展示后端给的值，不自己算。 </p>
     */
    public static String buildTemplateDefKey(String templateId) {
        String id = StringUtils.defaultString(templateId);
        String head = id.length() > TEMPLATE_DEF_KEY_ID_LEN ? id.substring(0, TEMPLATE_DEF_KEY_ID_LEN) : id;
        return TEMPLATE_DEF_KEY_PREFIX + head;
    }

    /** defKey 在未删除范围内唯一；被占用时加数字后缀（正常情况下同一模板第二次调用会命中"已绑定"分支） */
    private String uniqueDefKey(String base) {
        String candidate = base;
        int i = 1;
        while (flowSimpleMapper.selectByDefKey(candidate) != null) {
            i++;
            if (i > 50) {
                throw new ServiceException("流程标识生成失败：" + base + " 已被占用");
            }
            candidate = base + "_" + i;
        }
        return candidate;
    }

    /**
     * 草稿的初始内容：开始 → 部门负责人审批 → 结束。
     *
     * <p> 首个审批节点用「部门负责人」（无需人员ID，也避开校验规则 V-3 对
     * 首个节点不能用「角色 / 发起人自选」的限制）；用户可在设计器里继续改。 </p>
     */
    private String defaultDraftContent(Template template, String defKey) {
        JSONObject def = new JSONObject();
        def.put("schemaVersion", 1);
        def.put("key", defKey);
        def.put("name", StringUtils.defaultIfBlank(template.getName(), defKey));
        def.put("category", StringUtils.defaultIfBlank(template.getType(), "template"));

        JSONArray nodes = new JSONArray();
        nodes.add(newNode("start", SimpleFlowDef.T_START, "开始"));

        JSONObject approve = newNode("n1", SimpleFlowDef.T_APPROVE, "部门负责人审批");
        JSONObject assignee = new JSONObject();
        assignee.put("source", SimpleFlowDef.S_DEPT_LEADER);
        assignee.put("userIds", new JSONArray());
        assignee.put("roleIds", new JSONArray());
        assignee.put("level", 1);
        approve.put("assignee", assignee);
        approve.put("multiMode", SimpleFlowDef.M_SINGLE);
        approve.put("signMode", "NONE");
        JSONArray buttons = new JSONArray();
        buttons.add("agree");
        buttons.add("return");
        buttons.add("reject");
        approve.put("buttons", buttons);
        nodes.add(approve);

        nodes.add(newNode("end", SimpleFlowDef.T_END, "结束"));
        def.put("nodes", nodes);
        return def.toJSONString();
    }

    private JSONObject newNode(String id, String type, String name) {
        JSONObject node = new JSONObject();
        node.put("id", id);
        node.put("type", type);
        node.put("name", name);
        return node;
    }

    /**
     * 把流程定义里各节点的只读字段配置回写到 {@code t_template_node_field_auth}。
     *
     * <p> 一个 defKey 可能被多个模板绑定（模板才是发起入口，字段权限也按 template_id 存），
     * 所以对每个绑定模板各建一份，避免共用同一批对象导致主键/模板ID 被反复覆盖。 </p>
     */
    private void syncNodeFieldAuth(SimpleFlowDef def) {
        Template query = new Template();
        query.setDefKey(def.getKey());
        List<Template> templates = templateService.listTemplate(query);
        if (CollectionUtils.isEmpty(templates)) {
            // 还没建模板时发布也是合法流程（模板可后补），此处不算失败
            return;
        }
        for (Template template : templates) {
            List<TemplateNodeFieldAuth> rows = new ArrayList<>();
            collectReadonly(def.getNodes(), rows);
            nodeFieldAuthService.rebuildByTemplate(template.getId(), rows);
        }
    }

    /**
     * 递归收集节点上的只读字段；条件分支内部的节点也要收（它们同样是可办理节点）。
     */
    private void collectReadonly(List<SimpleFlowDef.Node> nodes, List<TemplateNodeFieldAuth> out) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        for (SimpleFlowDef.Node node : nodes) {
            if (node.getFieldReadonly() != null) {
                for (String field : node.getFieldReadonly()) {
                    if (StringUtils.isBlank(field)) {
                        continue;
                    }
                    TemplateNodeFieldAuth row = new TemplateNodeFieldAuth();
                    row.setTaskDefKey(node.getId());
                    row.setFieldVmodel(field.trim());
                    row.setReadonly("1");
                    row.setRequired("0");
                    row.setHidden("0");
                    out.add(row);
                }
            }
            if (CollectionUtils.isNotEmpty(node.getBranches())) {
                for (SimpleFlowDef.Branch branch : node.getBranches()) {
                    collectReadonly(branch.getNodes(), out);
                }
            }
        }
    }

    @Override
    public List<FlowSimpleHistory> history(String defKey) {
        return historyMapper.selectByDefKey(defKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowSimple rollback(String defKey, Integer version) {
        FlowSimpleHistory his = new FlowSimpleHistory();
        his.setDefKey(defKey);
        his.setVersion(version);
        FlowSimpleHistory target = historyMapper.selectByDefKeyAndVersion(his);
        if (target == null) {
            throw new ServiceException("历史版本不存在：" + defKey + " v" + version);
        }
        FlowSimple db = flowSimpleMapper.selectByDefKey(defKey);
        if (db == null) {
            throw new ServiceException("流程不存在：" + defKey);
        }
        FlowSimple upd = new FlowSimple();
        upd.setId(db.getId());
        upd.setContent(target.getContent());
        upd.setUpdateId(SecurityUtils.getUserId());
        upd.setUpdateBy(SecurityUtils.getUsername());
        flowSimpleMapper.update(upd);
        // 以回滚后的内容重新发布（生成新版本，历史不可变）
        return publish(db.getId(), "回滚至 v" + version);
    }

    @Override
    public int delete(String id) {
        FlowSimple db = requireById(id);
        FlowSimple upd = new FlowSimple();
        upd.setId(db.getId());
        upd.setUpdateId(SecurityUtils.getUserId());
        upd.setUpdateBy(SecurityUtils.getUsername());
        return flowSimpleMapper.deleteById(upd);
    }

    /* ---------------- 内部 ---------------- */

    private FlowSimple requireById(String id) {
        FlowSimple db = StringUtils.isBlank(id) ? null : flowSimpleMapper.selectById(id);
        if (db == null) {
            throw new ServiceException("流程不存在或已删除：" + id);
        }
        return db;
    }

    /** 解析设计器 JSON（失败给出可读错误，而不是 500 堆栈） */
    private SimpleFlowDef parse(String content) {
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("流程内容为空");
        }
        try {
            SimpleFlowDef def = JSON.parseObject(content, SimpleFlowDef.class);
            if (def == null) {
                throw new ServiceException("流程内容不是合法 JSON");
            }
            return def;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("流程内容解析失败：" + e.getMessage());
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
