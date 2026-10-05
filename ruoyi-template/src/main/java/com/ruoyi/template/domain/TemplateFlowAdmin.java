package com.ruoyi.template.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 模板流程管理员对象 t_template_flow_admin
 *
 * <p> <b>2.0（B1 §3.4，REQ-PERM-005）</b>：这条模板的流程由谁编辑/发布。
 * 未指定任何行时回退为"模板创建人 + 拥有模板编辑权限者"（见
 * {@code TemplateServiceImpl#canManageFlow}）。 </p>
 *
 * @author 二开
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TemplateFlowAdmin extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 模板ID */
    private String templateId;

    /** 被指定的账号ID */
    private String userId;

    /** 创建人ID（BaseEntity 里没有 createId，与既有二开表保持一致自己声明） */
    private String createId;
}
