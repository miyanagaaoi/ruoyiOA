package com.ruoyi.template.service.impl;

import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.template.domain.TemplateMainText;
import com.ruoyi.template.mapper.TemplateMainTextMapper;
import com.ruoyi.template.service.ITemplateMainTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 正文配置Service业务层处理
 *
 * @author wocurr.com
 */
@Slf4j
@Service
public class TemplateMainTextServiceImpl implements ITemplateMainTextService {
    @Autowired
    private TemplateMainTextMapper templateMainTextMapper;

    /**
     * 根据模板id查询正文配置
     *
     * @param templateId
     * @return
     */
    @Override
    public TemplateMainText getByTemplateId(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return null;
        }
        return templateMainTextMapper.selectByTemplateId(templateId);
    }

    /**
     * 新增正文配置
     *
     * <p> <b>2.0（B1 §1.1）：保证「一个模板只有一套正文配置」。</b>
     * 模板已改为原地 UPDATE（id 不变），同一 templateId 上累积多行会让
     * {@code selectByTemplateId}（返回单对象）抛 TooManyResultsException
     * ——拟稿/盖章/打印链路都在按 templateId 读它。 </p>
     *
     * @param templateMainText 正文配置
     * @return 结果
     */
    @Override
    public int saveTemplateMainText(TemplateMainText templateMainText) {
        templateMainText.setCreateTime(DateUtils.getNowDate());
        if (StringUtils.isNotBlank(templateMainText.getTemplateId())) {
            templateMainTextMapper.deleteMainTextByTemplateId(templateMainText.getTemplateId());
        }
        return templateMainTextMapper.insertTemplateMainText(templateMainText);
    }
}
