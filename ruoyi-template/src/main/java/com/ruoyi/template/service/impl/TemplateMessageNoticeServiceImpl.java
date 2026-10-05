package com.ruoyi.template.service.impl;

import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.template.domain.TemplateMessageNotice;
import com.ruoyi.template.mapper.TemplateMessageNoticeMapper;
import com.ruoyi.template.service.ITemplateMessageNoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 消息通知模板Service业务层处理
 * 
 * @author wocurr.com
 */
@Slf4j
@Service
public class TemplateMessageNoticeServiceImpl implements ITemplateMessageNoticeService {
    @Autowired
    private TemplateMessageNoticeMapper templateMessageNoticeMapper;

    /**
     * 新增消息通知模板
     *
     * <p> <b>2.0（B1 §1.1）：保证「一个模板只有一套消息通知配置」。</b>
     * 模板已改为原地 UPDATE（id 不变），同一 templateId 上累积多行会让
     * {@code selectByTemplateId}（返回单对象）抛 TooManyResultsException
     * ——发起/审批链路都在按 templateId 读它。 </p>
     *
     * @param templateMessageNotice 消息通知模板
     * @return 结果
     */
    @Override
    public int saveTemplateMessageNotice(TemplateMessageNotice templateMessageNotice) {
        templateMessageNotice.setCreateTime(DateUtils.getNowDate());
        if (StringUtils.isNotBlank(templateMessageNotice.getTemplateId())) {
            templateMessageNoticeMapper.deleteMessageNoticeByTemplateId(templateMessageNotice.getTemplateId());
        }
        return templateMessageNoticeMapper.insertTemplateMessageNotice(templateMessageNotice);
    }

    /**
     * 根据模板id查询消息通知模板
     *
     * @param templateId
     * @return 结果
     */
    @Override
    public TemplateMessageNotice getByTemplateId(String templateId) {
        return templateMessageNoticeMapper.selectByTemplateId(templateId);
    }
}
