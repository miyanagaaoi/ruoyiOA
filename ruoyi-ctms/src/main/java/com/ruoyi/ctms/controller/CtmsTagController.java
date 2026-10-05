package com.ruoyi.ctms.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.ctms.domain.CtmsTag;
import com.ruoyi.ctms.service.ICtmsTagService;

/**
 * <p> 合同标签字典控制器（2.0 B3 任务 4.5）。 </p>
 *
 * <p> 权限点取自 {@code sql/二开-合同台账-菜单.sql} 的
 * {@code ctms:tag:list/add/edit/remove} —— 菜单里标签管理<b>没有</b>独立的 query 按钮权限，
 * 所以读取详情也复用 {@code ctms:tag:list}，不新造权限点
 * （权限点与 {@code sys_menu} 必须一一对应，见 {@code controller/package-info}）。 </p>
 *
 * <p> 路径变量统一收窄为 {@code [A-Za-z0-9]+}，避免与 {@code /list} 之类的字面量段
 * 在映射层面产生歧义。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms/tag")
public class CtmsTagController extends BaseController
{
    @Autowired
    private ICtmsTagService tagService;

    /**
     * 查询标签列表。
     */
    @PreAuthorize("@ss.hasPermi('ctms:tag:list')")
    @GetMapping("/list")
    public TableDataInfo list(CtmsTag query)
    {
        startPage();
        List<CtmsTag> list = tagService.selectTagList(query);
        return getDataTable(list);
    }

    /**
     * 获取标签详情。
     */
    @PreAuthorize("@ss.hasPermi('ctms:tag:list')")
    @GetMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(tagService.selectTagById(id));
    }

    /**
     * 新增标签（名称唯一）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:tag:add')")
    @Log(title = "合同标签", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CtmsTag tag)
    {
        tagService.insertTag(tag);
        return toAjax(1);
    }

    /**
     * 修改标签（名称唯一，排除自身）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:tag:edit')")
    @Log(title = "合同标签", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CtmsTag tag)
    {
        tagService.updateTag(tag);
        return toAjax(1);
    }

    /**
     * 删除标签（被合同引用时拒绝）。
     */
    @PreAuthorize("@ss.hasPermi('ctms:tag:remove')")
    @Log(title = "合同标签", businessType = BusinessType.DELETE)
    @DeleteMapping(value = "/{id:[A-Za-z0-9]+}")
    public AjaxResult remove(@PathVariable("id") String id)
    {
        tagService.deleteTagById(id);
        return toAjax(1);
    }
}
