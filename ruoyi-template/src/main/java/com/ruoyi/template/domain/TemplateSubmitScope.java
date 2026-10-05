package com.ruoyi.template.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 模板可发起范围明细对象 t_template_submit_scope
 *
 * <p> <b>2.0（B1 §3.1，REQ-PERM-001）</b>：一条模板的"谁可以提交该审批"明细。
 * 多态表 —— 按 {@code scopeType} 只有一列 {@code targetId} 有值：
 * {@code 1} 人员ID / {@code 2} 角色ID / {@code 3} 部门ID。 </p>
 *
 * <p> 模板主表上的 {@code submit_scope_type} 是"选了哪一类"，本表是"具体选了谁"；
 * 类型为 {@code 0}（全员）时本表无行。 </p>
 *
 * @author 二开
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TemplateSubmitScope extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 模板ID */
    private String templateId;

    /** 范围类型：1-人员 2-角色 3-部门 */
    private String scopeType;

    /** 人员ID / 角色ID / 部门ID */
    private String targetId;

    /** 创建人ID（BaseEntity 里没有 createId，与既有二开表保持一致自己声明） */
    private String createId;
}
