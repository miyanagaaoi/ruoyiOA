package com.ruoyi.template.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.WhetherStatus;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.service.ITemplateDynamicFormService;
import com.ruoyi.template.service.ITemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 动态单Controller
 * 
 * @author wucorr.com
 */
@Slf4j
@RestController
@RequestMapping("/template/dynamic/form")
public class TemplateDynamicFormController extends BaseController {

    @Autowired
    private ITemplateService templateService;
    @Autowired
    private ITemplateDynamicFormService templateDynamicFormService;

    /**
     * 查询动态单列表
     */
    @PreAuthorize("@ss.hasPermi('template.dynamic:form:list')")
    @GetMapping("/list")
    public TableDataInfo list(TemplateDynamicForm templateDynamicForm) {
        startPage();
        templateDynamicForm.setEnableFlag(WhetherStatus.YES.getCode());
        templateDynamicForm.setDelFlag(WhetherStatus.NO.getCode());
        List<TemplateDynamicForm> list = templateDynamicFormService.listTemplateDynamicForm(templateDynamicForm);
        return getDataTable(list);
    }

    /**
     * 获取动态单详细信息
     */
    @PreAuthorize("@ss.hasPermi('template.dynamic:form:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") String id) {
        return success(templateDynamicFormService.getTemplateDynamicFormById(id));
    }

    /**
     * 新增动态单
     */
    @PreAuthorize("@ss.hasPermi('template.dynamic:form:add')")
    @Log(title = "动态单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody TemplateDynamicForm templateDynamicForm) {
        return toAjax(templateDynamicFormService.saveTemplateDynamicForm(templateDynamicForm));
    }

    /**
     * 修改动态单
     */
    @PreAuthorize("@ss.hasPermi('template.dynamic:form:edit')")
    @Log(title = "动态单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody TemplateDynamicForm templateDynamicForm) {
        int rows = templateDynamicFormService.updateTemplateDynamicForm(templateDynamicForm);
        // 2.0（B1 §7.10）：换版本会把模板显式改指到新表单，把影响面回给前端做提示。
        // 提示口径是"当前有多少模板正使用这张表单"，而不是"本次改指了几条" ——
        // 后者在"表单一改再改"时会让人以为影响面在缩水。
        int affected = 0;
        try {
            // 按 form_key 的**当前版本**统计：换版本后 form_id 已指向新行，
            // 拿旧 id 去数永远是 0，提示就成了误报。
            affected = templateService.countTemplatesOnFormKey(templateDynamicForm.getFormKey());
            if (affected == 0) {
                affected = templateService.countTemplatesOnForm(templateDynamicForm.getId());
            }
        } catch (Exception e) {
            log.warn("统计表单影响面失败（不影响保存结果）：{}", e.getMessage());
        }
        AjaxResult result = toAjax(rows);
        result.put("affectedTemplates", affected);
        return result;
    }

    /**
     * 删除动态单
     */
    @PreAuthorize("@ss.hasPermi('template.dynamic:form:remove')")
    @Log(title = "动态单", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable String[] ids) {
        return toAjax(templateDynamicFormService.deleteTemplateDynamicFormByIds(ids));
    }

    /**
     * 获取可关联动态表单定义列表
     */
    @GetMapping("/optionSelect")
    public AjaxResult optionSelect() {
        return AjaxResult.success(templateDynamicFormService.getOptionSelect());
    }
}
