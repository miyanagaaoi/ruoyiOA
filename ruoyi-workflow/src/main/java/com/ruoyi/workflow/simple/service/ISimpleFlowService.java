package com.ruoyi.workflow.simple.service;

import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.domain.FlowSimpleHistory;
import com.ruoyi.workflow.simple.validate.SimpleFlowValidator;

import java.util.List;

/**
 * <p> 简化流程设计器服务 </p>
 *
 * <p> 职责：草稿保存 → 发布校验 → 编译为 BPMN → 部署 → 记录版本快照。 </p>
 *
 * @author 二开
 */
public interface ISimpleFlowService {

    /** 列表 */
    List<FlowSimple> selectList(FlowSimple query);

    /** 详情 */
    FlowSimple selectById(String id);

    /** 保存草稿（id 为空则新增） */
    int saveDraft(FlowSimple flowSimple);

    /**
     * 发布前校验
     *
     * @param id             流程主键
     * @param requiredFields 表单中已设为必填的字段（校验条件字段），可为 null 表示跳过
     * @param multiFields    表单中的多选字段（校验并行分支集合来源），可为 null 表示跳过
     */
    List<SimpleFlowValidator.Issue> validate(String id, List<String> requiredFields, List<String> multiFields);

    /** 发布：校验 → 编译 → 部署 → 版本 +1 → 写快照 */
    FlowSimple publish(String id, String remark);

    /** 版本历史（倒序） */
    List<FlowSimpleHistory> history(String defKey);

    /** 回滚：以历史版本内容重新发布（生成新版本，不修改历史） */
    FlowSimple rollback(String defKey, Integer version);

    /** 逻辑删除 */
    int delete(String id);
}
