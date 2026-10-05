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
    @PreAuthorize("@flowAuthz.canEdit(#p0.id)")
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
    @PreAuthorize("@flowAuthz.canPublish(#p0.id)")
    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody PublishBody body) {
        return success(simpleFlowService.publish(body.getId(), body.getRemark(), body.getTemplateId()));
    }

    /**
     * 按模板取用或创建流程草稿（2.0 B1 §4.1）。
     *
     * <p> 模板已绑定 → 返回其草稿（不覆盖）；未绑定 → 按 {@code tpl_ + 模板ID前8位}
     * 生成流程标识、建草稿并回写绑定。连续调用返回同一个流程 id。 </p>
     *
     * <p> 这里刻意不加 {@code @PreAuthorize} 权限点：授权是**模板级**的
     * （{@code TemplateServiceImpl#checkFlowManagePermission}：系统管理员 / 该模板的流程管理员 /
     * 创建人 / 拥有 {@code workflow:template:edit} 者），由服务层给出明确的 403。
     * 用声明式权限点会把被指定的流程管理员一并挡掉（那几个权限点默认只有超管有）。 </p>
     */
    @GetMapping("/by-template/{templateId}")
    public AjaxResult getByTemplate(@PathVariable("templateId") String templateId) {
        return success(simpleFlowService.getOrCreateByTemplate(templateId));
    }

    /** 同 {@link #getByTemplate(String)}（POST 语义相同，便于前端表单式调用） */
    @PostMapping("/by-template/{templateId}")
    public AjaxResult postByTemplate(@PathVariable("templateId") String templateId) {
        return success(simpleFlowService.getOrCreateByTemplate(templateId));
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
        /**
         * 发布成功后要回写绑定的模板ID（2.0 B1 §4.2）。
         *
         * <p> 为空时后端按 {@code t_template.simple_flow_id} 反查 —— 两条路都只会回写到
         * 模板的**当前启用行**。 </p>
         */
        private String templateId;
    }
}
