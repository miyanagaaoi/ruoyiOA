package com.ruoyi.template.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 关联审批控件的候选模板（2.0 B1 §7，REQ-FORM-010） </p>
 *
 * <p> 一行 = 某个模板的某个「关联审批」控件允许关联的一个模板。
 * 这份数据是**服务端复核选择范围的权威来源**（见
 * {@code com.ruoyi.workflow.related.RelatedApprovalGuardImpl}）：
 * 前端下拉只是把范围画出来，绕过前端提交的越界单据必须在这里被拦下。 </p>
 *
 * <p> 写入时机：模板保存/编辑时从动态表单 schema 里解析控件配置后**全量替换**
 * （见 {@code TemplateServiceImpl.handleRelatedApproval}）。 </p>
 *
 * @author 二开
 */
@Data
public class TemplateRelatedApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 宿主模板ID */
    private String templateId;

    /** 控件在表单里的 __vModel__ */
    private String fieldVmodel;

    /** 允许被关联的模板ID（必须 enable_flag=1） */
    private String allowTemplateId;

    /** 创建时间 */
    private Date createTime;
}
