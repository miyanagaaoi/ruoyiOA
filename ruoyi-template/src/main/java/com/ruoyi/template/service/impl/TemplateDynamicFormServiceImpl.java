package com.ruoyi.template.service.impl;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.WhetherStatus;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.mapper.TemplateDynamicFormMapper;
import com.ruoyi.template.mapper.TemplateMapper;
import com.ruoyi.template.mapper.TemplateSourceTargetMapper;
import com.ruoyi.template.module.FormOption;
import com.ruoyi.template.service.ITemplateDynamicFormService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 动态单Service业务层处理
 * 
 * @author wucorr.com
 */
@Slf4j
@Service
public class TemplateDynamicFormServiceImpl implements ITemplateDynamicFormService {

    @Autowired
    private TemplateMapper templateMapper;
    @Autowired
    private TemplateDynamicFormMapper templateDynamicFormMapper;

    /**
     * 查询动态单
     * 
     * @param id 动态单主键
     * @return 动态单
     */
    @Override
    public TemplateDynamicForm getTemplateDynamicFormById(String id) {
        return templateDynamicFormMapper.selectTemplateDynamicFormById(id);
    }

    /**
     * 查询动态单列表
     * 
     * @param templateDynamicForm 动态单
     * @return 动态单
     */
    @Override
    public List<TemplateDynamicForm> listTemplateDynamicForm(TemplateDynamicForm templateDynamicForm) {
        templateDynamicForm.setEnableFlag(WhetherStatus.YES.getCode());
        templateDynamicForm.setDelFlag(WhetherStatus.NO.getCode());
        return templateDynamicFormMapper.selectTemplateDynamicFormList(templateDynamicForm);
    }

    /**
     * 新增动态单
     * 
     * @param templateDynamicForm 动态单
     * @return 结果
     */
    @Override
    public int saveTemplateDynamicForm(TemplateDynamicForm templateDynamicForm) {
        templateDynamicForm.setId(IdUtils.fastSimpleUUID());
        templateDynamicForm.setFormKey(IdUtils.fastSimpleUUID());
        String userId = SecurityUtils.getLoginUser().getUserId();
        templateDynamicForm.setCreateId(userId);
        templateDynamicForm.setCreateBy(SecurityUtils.getLoginUser().getUser().getNickName());
        templateDynamicForm.setCreateTime(DateUtils.getNowDate());
        return templateDynamicFormMapper.insertTemplateDynamicForm(templateDynamicForm);
    }

    /**
     * 修改动态单
     * 
     * @param templateDynamicForm 动态单
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTemplateDynamicForm(TemplateDynamicForm templateDynamicForm) {
        TemplateDynamicForm newDynamicForm = TemplateSourceTargetMapper.INSTANCE.copyDynamicForm(templateDynamicForm);
        String userId = SecurityUtils.getUserId();
        SysUser user = SecurityUtils.getLoginUser().getUser();
        Date now = DateUtils.getNowDate();
        //设置停用状态
        templateDynamicForm.setEnableFlag(WhetherStatus.NO.getCode());
        templateDynamicForm.setUpdateTime(now);
        templateDynamicForm.setUpdateId(userId);
        templateDynamicForm.setUpdateBy(user.getNickName());
        templateDynamicFormMapper.updateTemplateDynamicForm(templateDynamicForm);
        //新增表单
        newDynamicForm.setId(IdUtils.fastSimpleUUID());
        newDynamicForm.setCreateId(userId);
        newDynamicForm.setCreateBy(user.getNickName());
        newDynamicForm.setCreateTime(now);
        int rows = templateDynamicFormMapper.insertTemplateDynamicForm(newDynamicForm);
        // 2.0（B1 §7.10）动态表单版本化：模板上的 form_id 存的是"具体那一版"，
        // 不跟着改指，模板就会永远停在旧版本上（用户看到"保存成功"，改的却是空气）。
        // 与表单插入同事务：改指失败就一起回滚，不会出现"新版本在、模板还指旧版"。
        int repointed = templateMapper.repointFormId(templateDynamicForm.getId(), newDynamicForm.getId());
        log.info("动态表单换版本：old={} new={} 已改指模板数={}", templateDynamicForm.getId(), newDynamicForm.getId(), repointed);
        return rows;
    }

    /**
     * 批量删除动态单
     * 
     * @param ids 需要删除的动态单主键
     * @return 结果
     */
    @Override
    public int deleteTemplateDynamicFormByIds(String[] ids) {
        return templateDynamicFormMapper.deleteTemplateDynamicFormByIds(ids);
    }

    @Override
    public List<FormOption> getOptionSelect() {
        return templateDynamicFormMapper.selectFormOptionList();
    }
}
