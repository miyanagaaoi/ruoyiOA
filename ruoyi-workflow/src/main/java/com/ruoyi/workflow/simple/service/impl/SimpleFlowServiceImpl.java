package com.ruoyi.workflow.simple.service.impl;

import com.alibaba.fastjson2.JSON;
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
@Service
public class SimpleFlowServiceImpl extends FlowServiceFactory implements ISimpleFlowService {

    /** 草稿 */
    private static final String STATUS_DRAFT = "0";
    /** 已发布 */
    private static final String STATUS_PUBLISHED = "1";

    @Autowired
    private FlowSimpleMapper flowSimpleMapper;

    @Autowired
    private FlowSimpleHistoryMapper historyMapper;

    @Autowired
    private IFlowDefinitionService flowDefinitionService;

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
    @Transactional(rollbackFor = Exception.class)
    public FlowSimple publish(String id, String remark) {
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

        return flowSimpleMapper.selectById(db.getId());
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
