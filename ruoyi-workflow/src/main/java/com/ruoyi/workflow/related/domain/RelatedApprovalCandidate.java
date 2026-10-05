package com.ruoyi.workflow.related.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 「关联审批」候选单据（拟稿页下拉 / 浮窗用的读模型，2.0 B1 §7） </p>
 *
 * <p> 候选项的口径（服务端唯一口径，前端不参与判断）：
 * 与被关联模板处于**同一分组**、且**本人发起**的单据。 </p>
 *
 * @author 二开
 */
@Data
public class RelatedApprovalCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被关联单据的业务ID（控件的取值） */
    private String businessId;

    /** 单据号快照（展示） */
    private String businessNo;

    /** 被关联单据的模板ID */
    private String templateId;

    /** 被关联单据的模板名（弹窗里要显示"是哪类单"） */
    private String templateName;

    /** 单据标题 */
    private String title;

    /** 流程状态（0-草稿 1-审批中 2-通过 3-驳回 …，沿用 t_workflow_my_draft.status 口径） */
    private String status;

    /** 发起时间 */
    private Date createTime;
}
