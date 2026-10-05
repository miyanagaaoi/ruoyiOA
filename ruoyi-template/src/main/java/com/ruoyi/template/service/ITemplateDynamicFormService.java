package com.ruoyi.template.service;

import java.util.List;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.module.FormOption;
import com.ruoyi.template.support.FormV8Impact;

/**
 * 动态单Service接口
 * 
 * @author wucorr.com
 */
public interface ITemplateDynamicFormService {
    /**
     * 查询动态单
     * 
     * @param id 动态单主键
     * @return 动态单
     */
    public TemplateDynamicForm getTemplateDynamicFormById(String id);

    /**
     * 查询动态单列表
     * 
     * @param templateDynamicForm 动态单
     * @return 动态单集合
     */
    public List<TemplateDynamicForm> listTemplateDynamicForm(TemplateDynamicForm templateDynamicForm);

    /**
     * 新增动态单
     * 
     * @param templateDynamicForm 动态单
     * @return 结果
     */
    public int saveTemplateDynamicForm(TemplateDynamicForm templateDynamicForm);

    /**
     * 修改动态单
     * 
     * @param templateDynamicForm 动态单
     * @return 结果
     */
    public int updateTemplateDynamicForm(TemplateDynamicForm templateDynamicForm);

    /**
     * <b>PRD V-8 反向校验（只读）</b>：预演"这次保存表单会让哪些流程的条件字段失效 / 错位"。
     *
     * <p> 之所以单独暴露一个只读方法：{@link #updateTemplateDynamicForm} 在**阻断**时直接抛异常，
     * 拿不到"放行但有告警"的那份明细；而调用方（Controller）必须把告警回给界面（不得静默）。 </p>
     *
     * <p> 两个入口共用同一份判定逻辑（{@code FormV8Guard#analyze}），不存在"预览说没事、保存却炸"。 </p>
     *
     * @param templateDynamicForm 本次提交的表单（id = 即将被替换的旧版本行）
     * @return 影响面；永不返回 null
     */
    public FormV8Impact previewFormSaveImpact(TemplateDynamicForm templateDynamicForm);

    /**
     * 批量删除动态单
     * 
     * @param ids 需要删除的动态单主键集合
     * @return 结果
     */
    public int deleteTemplateDynamicFormByIds(String[] ids);

    /**
     * 获取可关联动态表单定义列表
     *
     * @return List<FormOption>
     */
    public List<FormOption> getOptionSelect();
}
