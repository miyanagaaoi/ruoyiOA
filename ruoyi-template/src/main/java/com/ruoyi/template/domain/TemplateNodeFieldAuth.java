package com.ruoyi.template.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 节点级字段权限对象 t_template_node_field_auth
 *
 * <p> 由简化流程设计器在**发布时**落库（源数据是流程 JSON 里节点的
 * {@code fieldReadonly}），用于在**服务端**强制「本节点只读字段改不动」。 </p>
 *
 * <p> 为什么不能只靠前端置灰：AC-12 明确要求「直接调接口也改不动」。 </p>
 *
 * @author 二开
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TemplateNodeFieldAuth extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private String id;

    /** 单据模板ID */
    private String templateId;

    /** 节点定义key */
    private String taskDefKey;

    /** 表单字段 __vModel__ */
    private String fieldVmodel;

    /** 只读，0-否，1-是 */
    private String readonly;

    /** 必填，0-否，1-是 */
    private String required;

    /** 隐藏，0-否，1-是 */
    private String hidden;
}
