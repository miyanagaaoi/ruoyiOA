package com.ruoyi.workflow.simple.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.simple.service.ISimpleFlowService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p> 简化流程设计器 </p>
 *
 * <p> 接口清单（PRD 10.2-A）：草稿 / 校验 / 发布 / 版本历史 / 回滚；另附 {@code preview}
 * 用于查看编译产物（不部署），便于设计器"预览 BPMN"。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/workflow/simple-flow")
public class SimpleFlowController extends BaseController {

    @Autowired
    private ISimpleFlowService simpleFlowService;

    /** 列表 */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:list')")
    @GetMapping("/list")
    public TableDataInfo list(FlowSimple query) {
        startPage();
        return getDataTable(simpleFlowService.selectList(query));
    }

    /** 详情 */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") String id) {
        return success(simpleFlowService.selectById(id));
    }

    /** 保存草稿（id 为空 = 新增） */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:edit')")
    @PostMapping("/draft")
    public AjaxResult draft(@RequestBody FlowSimple flowSimple) {
        simpleFlowService.saveDraft(flowSimple);
        FlowSimple saved = flowSimple.getId() == null ? null : simpleFlowService.selectById(flowSimple.getId());
        return success(saved);
    }

    /** 发布前校验（返回问题清单，不抛异常） */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:edit')")
    @PostMapping("/validate")
    public AjaxResult validate(@RequestBody ValidateBody body) {
        return success(simpleFlowService.validate(body.getId(), body.getRequiredFields(), body.getMultiFields()));
    }

    /** 编译预览（只编译不部署，返回 BPMN XML 文本） */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:query')")
    @GetMapping("/preview/{id}")
    public AjaxResult preview(@PathVariable("id") String id) {
        FlowSimple db = simpleFlowService.selectById(id);
        if (db == null) {
            return AjaxResult.error("流程不存在或已删除：" + id);
        }
        try {
            // 注意：不要写成 success(xmlString)——那是 success(String msg) 重载，会把 XML 放进 msg 字段
            AjaxResult ajax = AjaxResult.success();
            ajax.put("xml", com.ruoyi.workflow.simple.compile.SimpleFlowCompiler.compileJson(db.getContent()));
            return ajax;
        } catch (RuntimeException e) {
            return AjaxResult.error("编译失败：" + e.getMessage());
        }
    }

    /** 发布 */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:publish')")
    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody PublishBody body) {
        return success(simpleFlowService.publish(body.getId(), body.getRemark()));
    }

    /** 版本历史 */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:list')")
    @GetMapping("/history/{defKey}")
    public AjaxResult history(@PathVariable("defKey") String defKey) {
        return success(simpleFlowService.history(defKey));
    }

    /** 回滚（以历史版本重新发布，生成新版本） */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:publish')")
    @PostMapping("/rollback")
    public AjaxResult rollback(@RequestBody PublishBody body) {
        return success(simpleFlowService.rollback(body.getDefKey(), body.getVersion()));
    }

    /** 删除（逻辑删除） */
    @PreAuthorize("@ss.hasPermi('workflow:simpleFlow:remove')")
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") String id) {
        return toAjax(simpleFlowService.delete(id));
    }

    /* ---------------- 请求体 ---------------- */

    @Data
    public static class ValidateBody {
        private String id;
        /** 表单中已设为必填的字段（校验条件字段），可空 */
        private List<String> requiredFields;
        /** 表单中的多选字段（校验并行分支集合来源），可空 */
        private List<String> multiFields;
    }

    @Data
    public static class PublishBody {
        private String id;
        private String remark;
        /** 回滚用 */
        private String defKey;
        private Integer version;
    }
}
