package com.ruoyi.template.module;

import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateAttachment;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.domain.TemplateFlowAdmin;
import com.ruoyi.template.domain.TemplateSubmitScope;
import lombok.Data;

import java.util.List;


/**
 * @Author wocurr.com
 */
@Data
public class TemplateDTO extends Template {

    /**
     * 附件信息
     */
    private TemplateAttachment attachment;

    /**
     * 动态表单信息
     */
    private TemplateDynamicForm dynamicForm;

    /**
     * 正文信息
     */
    private TemplateMainTextDTO mainText;

    /**
     * 消息通知
     */
    private TemplateMessageNoticeDTO messageNotice;

    /**
     * 谁可以提交该审批的明细（2.0 B1 §3.1；全量替换语义）
     */
    private List<TemplateSubmitScope> submitScope;

    /**
     * 流程管理员（2.0 B1 §3.1；全量替换语义）
     */
    private List<TemplateFlowAdmin> flowAdmins;
}
