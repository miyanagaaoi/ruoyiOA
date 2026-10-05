package com.ruoyi.biz.service.impl;

import com.ruoyi.biz.domain.CommonFlowSubmit;
import com.ruoyi.biz.factory.BizFlowSubmitFactory;
import com.ruoyi.biz.service.IBizFLowService;
import com.ruoyi.biz.service.IBizFLowSubmitService;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.service.ITemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * <p> 业务流程接口实现类 </p>
 *
 * @Author wocurr.com
 */
@Slf4j
@Service
public class BizFLowServiceImpl implements IBizFLowService {

    @Autowired
    private BizFlowSubmitFactory bizFlowSubmitFactory;
    @Autowired
    private ITemplateService templateService;

    @Override
    public void submit(CommonFlowSubmit submit) {
        if (Objects.isNull(submit.getFlowTask()) || StringUtils.isBlank(submit.getFlowTask().getTemplateId())) {
            throw new BaseException("模板ID为空");
        }
        Template template = templateService.getTemplateById(submit.getFlowTask().getTemplateId());
        if (Objects.isNull(template)) {
            throw new BaseException("未找到模板");
        }
        if (StringUtils.isBlank(template.getFormCode())) {
            throw new BaseException("表单编码为空");
        }
        // 2.0（B1 §3.5，REQ-FORM-003）：`type`（模板分类/分组）为空不再是硬错误。
        // 发起页现在把这类模板归入「未分类」组并允许发起（delta spec workbook/form-definition：
        // 「它在发起审批页面出现在「未分类」分组下并可被有权用户发起」）。
        // 这里只提示一条日志：真正决定用哪个业务实现的是 formCode（下一行），与 type 无关。
        if (StringUtils.isBlank(template.getType())) {
            log.warn("模板未设置分类（type 为空），按流程继续提交：templateId={}", template.getId());
        }
        IBizFLowSubmitService bizFLowSubmitImpl = bizFlowSubmitFactory.getBizFLowSubmitImplByType(template.getFormCode());
        if (Objects.isNull(bizFLowSubmitImpl)) {
            throw new BaseException("未找到业务流程实现类");
        }
        submit.setTemplate(template);
        bizFLowSubmitImpl.submit(submit);
    }
}
