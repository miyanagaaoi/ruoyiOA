package com.ruoyi.workflow.related.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 单据间的「关联审批」关系（2.0 B1 §7，REQ-FORM-010 / AC-53..AC-55） </p>
 *
 * <p> 一行 = 宿主单据在某个「关联审批」控件上选了某一张单据。 </p>
 *
 * <p> {@code relatedBusinessNo} 是**单据号快照**：被关联单据的单据号可能因为模板改名、
 * 编号规则调整而变化，而"我当时提交时引用的就是这张单"是历史事实，
 * 必须按快照展示（与打印件、审批意见的取值口径一致）。 </p>
 *
 * @author 二开
 */
@Data
public class RelatedApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 宿主单据业务ID */
    private String businessId;

    /** 控件 __vModel__ */
    private String fieldVmodel;

    /** 被关联单据业务ID */
    private String relatedBusinessId;

    /** 被关联单据的模板ID */
    private String relatedTemplateId;

    /** 被关联单据的单据号快照（展示用，不随后续改名变化） */
    private String relatedBusinessNo;

    /** 创建人（提交人） */
    private String createId;

    /** 创建时间 */
    private Date createTime;
}
