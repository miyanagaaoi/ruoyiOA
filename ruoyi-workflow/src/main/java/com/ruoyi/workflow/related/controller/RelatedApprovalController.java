package com.ruoyi.workflow.related.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.service.ITemplateService;
import com.ruoyi.workflow.guard.DocViewGuard;
import com.ruoyi.workflow.mapper.SignRecordMapper;
import com.ruoyi.workflow.related.domain.RelatedApproval;
import com.ruoyi.workflow.related.domain.RelatedApprovalCandidate;
import com.ruoyi.workflow.related.mapper.RelatedApprovalMapper;
import com.ruoyi.workflow.related.service.IRelatedApprovalConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 「关联审批」控件相关的查询接口（2.0 B1 §7.8，AC-53/AC-54） </p>
 *
 * <p> 三个口子，权限口径各不同，**都在服务端判**： </p>
 * <ul>
 *   <li>{@code /candidates}：候选单据。范围 = 控件配置的候选模板 ∩ 同分组 ∩ 本人发起。
 *       不返回别人的单据，所以不存在"看得见但点不进去"的空窗。</li>
 *   <li>{@code /detail}：浮窗只读详情（表单数据 + 审批状态）。**复用单据查看权的守卫**
 *       （{@link DocViewGuard}）：没有查看权一律 403 —— 浮窗最容易变成越权入口，
 *       因为它把"看别人的单据"做成了顺手一点的动作。</li>
 *   <li>{@code /reverse}：按被关联单据反查宿主单据（AC-55）。
 *       返回的是"宿主单据的ID与单据号"，同样要求调用方对该宿主单据有查看权，
 *       否则拿到ID列表就等于知道别人单子的存在。</li>
 * </ul>
 *
 * @author 二开
 */
@Slf4j
@RestController
@RequestMapping("/workflow/related-approval")
public class RelatedApprovalController {

    @Autowired
    private RelatedApprovalMapper relatedApprovalMapper;

    @Autowired
    private ITemplateService templateService;

    @Autowired
    private IRelatedApprovalConfigService relatedApprovalConfigService;

    @Autowired
    private DocViewGuard docViewGuard;

    @Autowired
    private SignRecordMapper signRecordMapper;

    /**
     * 候选单据列表（拟稿页的关联审批下拉）。
     *
     * @param templateId  宿主模板ID
     * @param fieldVmodel 控件 __vModel__
     * @param keyword     单据号/标题关键字（可空）
     */
    @GetMapping("/candidates")
    public AjaxResult candidates(@RequestParam("templateId") String templateId,
                                 @RequestParam("fieldVmodel") String fieldVmodel,
                                 @RequestParam(value = "keyword", required = false) String keyword) {
        Template host = templateService.getTemplateById(templateId);
        if (host == null) {
            return AjaxResult.error("模板不存在");
        }
        List<String> allow = templateService.listRelatedApprovalAllowTemplates(templateId, fieldVmodel);
        if (allow == null || allow.isEmpty()) {
            // 控件没配候选：返回空列表而不是报错 —— 这是配置状态，不是调用错误
            return AjaxResult.success(new ArrayList<>());
        }
        List<RelatedApprovalCandidate> rows = relatedApprovalMapper.selectCandidates(
                allow, host.getType(), SecurityUtils.getUserId(), keyword);
        for (RelatedApprovalCandidate row : rows) {
            // 单据号快照口径与落库时一致（流水号控件 → biz_title → 短ID）
            row.setBusinessNo(relatedApprovalConfigService.resolveBusinessNo(row.getBusinessId(), row.getBusinessNo()));
        }
        return AjaxResult.success(rows);
    }

    /**
     * 浮窗只读详情：表单数据 + 审批状态（AC-53）。
     *
     * <p> 无查看权 → 403（不是空数据：空数据会让人以为是"没有内容"，403 才说得清是"没权限"）。 </p>
     */
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("businessId") String businessId) {
        docViewGuard.requireViewable(businessId);
        String formJson = signRecordMapper.selectFormDataById(businessId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessId", businessId);
        data.put("businessNo", relatedApprovalConfigService.resolveBusinessNo(businessId, null));
        JSONObject values = new JSONObject();
        JSONObject schema = null;
        if (StringUtils.isNotBlank(formJson)) {
            try {
                JSONObject root = JSON.parseObject(formJson);
                schema = root.getJSONObject("formData");
                JSONObject val = root.getJSONObject("valData");
                if (val != null) {
                    values = val;
                }
            } catch (Exception e) {
                log.warn("关联审批浮窗：表单数据解析失败 businessId={} err={}", businessId, e.getMessage());
            }
        }
        data.put("fields", flattenFields(schema, values));
        data.put("approval", approvalStatus(businessId));
        return AjaxResult.success(data);
    }

    /** 宿主单据关联了哪些单据（审批页/详情页回显，按控件分组） */
    @GetMapping("/by-host")
    public AjaxResult byHost(@RequestParam("businessId") String businessId) {
        docViewGuard.requireViewable(businessId);
        return AjaxResult.success(groupByField(relatedApprovalMapper.selectByBusinessId(businessId)));
    }

    /**
     * 反查：哪些宿主单据关联了这张单据（AC-55）。
     *
     * <p> 每条结果都要先过一遍查看权 —— 无权的宿主单据直接不返回（而不是返回ID让前端去试）。 </p>
     */
    @GetMapping("/reverse")
    public AjaxResult reverse(@RequestParam("businessId") String businessId) {
        docViewGuard.requireViewable(businessId);
        List<RelatedApproval> rows = relatedApprovalMapper.selectByRelatedBusinessId(businessId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RelatedApproval row : rows) {
            if (!docViewGuard.canView(row.getBusinessId())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("businessId", row.getBusinessId());
            item.put("fieldVmodel", row.getFieldVmodel());
            item.put("businessNo", relatedApprovalConfigService.resolveBusinessNo(row.getBusinessId(), null));
            item.put("createId", row.getCreateId());
            item.put("createTime", row.getCreateTime());
            out.add(item);
        }
        return AjaxResult.success(out);
    }

    /** 关联关系按控件分组（前端渲染"关联审批"区块用） */
    private Map<String, List<Map<String, Object>>> groupByField(List<RelatedApproval> rows) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (RelatedApproval row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("businessId", row.getRelatedBusinessId());
            // 历史快照：不随后续改名变化，展示的就是"提交时引用的那张单"
            item.put("businessNo", StringUtils.defaultIfBlank(row.getRelatedBusinessNo(),
                    relatedApprovalConfigService.resolveBusinessNo(row.getRelatedBusinessId(), null)));
            item.put("templateId", row.getRelatedTemplateId());
            out.computeIfAbsent(row.getFieldVmodel(), k -> new ArrayList<>()).add(item);
        }
        return out;
    }

    /**
     * 审批状态（浮窗要显示"这张单走到哪了"）。
     *
     * <p> 口径与"我发起的"列表一致：取 my_draft.status，没有记录则视为未发起。 </p>
     */
    private Map<String, Object> approvalStatus(String businessId) {
        Map<String, Object> out = new HashMap<>();
        RelatedApprovalCandidate candidate = relatedApprovalMapper.selectCandidateByBusinessId(businessId);
        if (candidate == null) {
            out.put("status", null);
            out.put("statusText", "未发起");
            out.put("templateId", null);
            out.put("templateName", null);
            return out;
        }
        out.put("status", candidate.getStatus());
        out.put("statusText", statusText(candidate.getStatus()));
        out.put("templateId", candidate.getTemplateId());
        out.put("templateName", candidate.getTemplateName());
        return out;
    }

    /** 与 t_workflow_my_draft.status 的口径一致（0-草稿 1-审批中 2-通过 3-驳回） */
    private String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "0":
                return "草稿";
            case "1":
                return "审批中";
            case "2":
                return "已通过";
            case "3":
                return "已驳回";
            default:
                return "未知";
        }
    }

    /**
     * 只读展示用的字段清单：从 schema 里挑**有值的**字段，按 schema 顺序返回。
     *
     * <p> 直接把整个 valData 丢给前端会把隐藏字段、内部标识一起暴露出去；
     * 这里只回 label + 值，且跳过纯排版控件（它们没有值）。 </p>
     */
    private List<Map<String, Object>> flattenFields(JSONObject schema, JSONObject values) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (schema == null || values == null) {
            return out;
        }
        collectFields(schema.get("fields"), values, out);
        return out;
    }

    private void collectFields(Object node, JSONObject values, List<Map<String, Object>> out) {
        if (node == null) {
            return;
        }
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectFields(item, values, out);
            }
            return;
        }
        JSONObject field;
        try {
            field = node instanceof JSONObject ? (JSONObject) node : JSON.parseObject(JSON.toJSONString(node));
        } catch (Exception e) {
            return;
        }
        if (field == null) {
            return;
        }
        JSONObject config = field.getJSONObject("__config__");
        String vModel = field.getString("__vModel__");
        if (config != null && StringUtils.isNotBlank(vModel)) {
            Object value = values.get(vModel);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", StringUtils.defaultIfBlank(config.getString("label"), vModel));
                item.put("vModel", vModel);
                item.put("value", value);
                out.add(item);
            }
        }
        if (config != null && config.get("children") != null) {
            collectFields(config.get("children"), values, out);
        }
        if (field.get("children") != null) {
            collectFields(field.get("children"), values, out);
        }
    }
}
