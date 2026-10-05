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

    /**
     * 发布并把流程绑定回写到模板（2.0 B1 §4.2）。
     *
     * <p> 与 {@link #publish(String, String)} 同事务：若回写失败（例如模板被删），
     * 整次发布回滚 —— 不留下"引擎已部署、模板未绑定"的悬空态。 </p>
     *
     * @param id         流程主键
     * @param remark     发布备注
     * @param templateId 要回写的模板ID；为空时等价于 {@link #publish(String, String)}
     */
    FlowSimple publish(String id, String remark, String templateId);

    /**
     * 按模板取用或创建流程草稿（2.0 B1 §4.1）。
     *
     * <p> 模板已绑定 → 返回其草稿（**不覆盖**）；未绑定 → 按
     * {@code tpl_ + 模板ID前8位} 生成流程标识、创建草稿并回写绑定。 </p>
     *
     * @param templateId 模板ID
     * @return 该模板的流程草稿
     */
    FlowSimple getOrCreateByTemplate(String templateId);

    /** 版本历史（倒序） */
    List<FlowSimpleHistory> history(String defKey);

    /** 回滚：以历史版本内容重新发布（生成新版本，不修改历史） */
    FlowSimple rollback(String defKey, Integer version);

    /** 逻辑删除 */
    int delete(String id);
}
