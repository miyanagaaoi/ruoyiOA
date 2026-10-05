package com.ruoyi.template.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 模板配置对象 t_template
 *
 * @author wocurr.com
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Template extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private String id;

    /**
     * 模板名称
     */
    @Excel(name = "模板名称")
    private String name;

    /**
     * 模板类型
     */
    @Excel(name = "模板类型")
    private String type;

    /**
     * 模板类型名称
     */
    private String typeName;

    /**
     * 流程定义key
     */
    @Excel(name = "流程定义key")
    private String defKey;

    /**
     * 表单配置ID
     */
    @Excel(name = "表单配置ID")
    private String formId;

    /**
     * 业务表单key
     */
    private String formKey;

    /**
     * 表单类型，1-动态表单，2-业务表单
     */
    @Excel(name = "模板类型，1-动态表单，2-业务表单")
    private String formType;

    /**
     * 表单编码，动态表单默认是dynamic，业务表单用户自定义
     */
    private String formCode;

    /**
     * 是否需要正文，0-否，1-时
     */
    private String mainTextFlag;

    /**
     * 是否有附件，0-否，1-是
     */
    private String attachFlag;

    /**
     * 是否启用，0-否，1-是
     */
    @Excel(name = "是否启用，0-否，1-是")
    private String enableFlag;

    /**
     * 是否需要消息通知，0-否，1-时
     */
    private String messageNoticeFlag;

    /**
     * 发布状态，0-未发布，1-已发布，2-已下架
     */
    @Excel(name = "发布状态，0-未发布，1-已发布，2-已下架")
    private String delFlag;

    /**
     * 排序
     */
    private Integer sort;

    // ==================== 2.0（B1 §2.1）新增 13 列 ====================
    // ⚠️ 加列必须五处同步：本实体 / TemplateMapper.xml 的 resultMap、selectTemplateVo、
    //    insertTemplate、updateTemplate。漏一处就"保存成功但字段静默丢失"。

    /**
     * 发起页卡片图标（预设图标集，默认第一个）
     */
    private String icon;

    /**
     * 谁可以提交该审批：0-全员 1-指定人员 2-指定角色 3-指定部门
     */
    private String submitScopeType;

    /**
     * 部门范围是否包含下级部门：0-否 1-是（默认含）
     */
    private String includeChildDept;

    /**
     * 绑定的简化流程草稿ID（t_flow_simple.id）
     */
    private String simpleFlowId;

    /**
     * 流程模式：0-简化流程 1-BPMN高级
     */
    private String flowMode;

    /**
     * 内置打印模板键：contract/fund/matter/payment
     */
    private String builtinPrintKey;

    /**
     * 提交后多少分钟内可撤销，0-不限制
     */
    private Integer revokeLimitMinutes;

    /**
     * 允许提交人修改已提交单据：0-否 1-是
     */
    private String allowEditAfterSubmit;

    /**
     * 允许代他人提交：0-否 1-是
     */
    private String allowSubmitForOther;

    /**
     * 允许审批人撤回：0-否 1-是
     */
    private String allowApproverRevoke;

    /**
     * 允许批量审批：0-否 1-是
     */
    private String allowBatchApprove;

    /**
     * 审批人去重策略：1-自动同意 2-自动跳过 3-不自动同意
     */
    private String approverDedup;

    /**
     * 审批转发范围（复用可发起范围枚举），NULL-不限制
     */
    private String forwardScope;

    /**
     * 创建人
     */
    @Excel(name = "创建人")
    private String createId;

    /**
     * 更新人
     */
    @Excel(name = "更新人")
    private String updateId;


    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("name", getName())
                .append("type", getType())
                .append("defKey", getDefKey())
                .append("formId", getFormId())
                .append("formType", getFormType())
                .append("formCode", getFormCode())
                .append("icon", getIcon())
                .append("submitScopeType", getSubmitScopeType())
                .append("includeChildDept", getIncludeChildDept())
                .append("simpleFlowId", getSimpleFlowId())
                .append("flowMode", getFlowMode())
                .append("builtinPrintKey", getBuiltinPrintKey())
                .append("approverDedup", getApproverDedup())
                .append("delFlag", getDelFlag())
                .append("createId", getCreateId())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateId", getUpdateId())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .toString();
    }
}
