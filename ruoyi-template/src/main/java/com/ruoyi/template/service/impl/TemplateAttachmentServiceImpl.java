package com.ruoyi.template.service.impl;

import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.template.domain.TemplateAttachment;
import com.ruoyi.template.mapper.TemplateAttachmentMapper;
import com.ruoyi.template.service.ITemplateAttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 附件配置Service业务层处理
 * 
 * @author wocurr.com
 */
@Slf4j
@Service
public class TemplateAttachmentServiceImpl implements ITemplateAttachmentService {
    @Autowired
    private TemplateAttachmentMapper templateAttachmentMapper;

    /**
     * 根据模板ID查询附件配置
     *
     * @param templateId 模板ID
     * @return
     */
    @Override
    public TemplateAttachment getTemplateAttachmentByTemplateId(String templateId) {
        return templateAttachmentMapper.selectTemplateAttachmentByTemplateId(templateId);
    }

    /**
     * 新增附件配置
     *
     * <p> <b>2.0（B1 §1.1）：保证「一个模板只有一套附件配置」。</b>
     * 模板已改为原地 UPDATE（id 不变），同一 templateId 上累积多行会让
     * {@code selectTemplateAttachmentByTemplateId}（返回单对象）抛
     * TooManyResultsException；先清掉该模板的旧配置再插入。 </p>
     *
     * @param templateAttachment 附件配置
     * @return 结果
     */
    @Override
    public int saveTemplateAttachment(TemplateAttachment templateAttachment) {
        templateAttachment.setId(IdUtils.fastSimpleUUID());
        templateAttachment.setCreateTime(DateUtils.getNowDate());
        if (StringUtils.isNotBlank(templateAttachment.getTemplateId())) {
            templateAttachmentMapper.deleteAttachmentByTemplateId(templateAttachment.getTemplateId());
        }
        return templateAttachmentMapper.insertTemplateAttachment(templateAttachment);
    }
}
