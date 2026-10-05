package com.ruoyi.workflow.related.guard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.biz.domain.CommonForm;
import com.ruoyi.biz.service.IRelatedApprovalGuard;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.service.ITemplateService;
import com.ruoyi.workflow.related.domain.RelatedApproval;
import com.ruoyi.workflow.related.domain.RelatedApprovalCandidate;
import com.ruoyi.workflow.related.mapper.RelatedApprovalMapper;
import com.ruoyi.workflow.related.service.IRelatedApprovalConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p> 「关联审批」选择范围的服务端复核 + 关联关系落库（2.0 B1 §7.9，AC-54/AC-55） </p>
 *
 * <p> <b>为什么范围必须在服务端判</b>：前端候选下拉只是把范围画出来。
 * 绕过前端直接 POST 一个不在候选范围内的 {@code businessId}，
 * 就能把任意单据挂到自己单子上，连带把别人的单据号、审批状态暴露出去。
 * 所以这里的口径与候选列表**完全同源**（同一张候选表 + 同一套 SQL）。 </p>
 *
 * @author 二开
 */
@Slf4j
@Component
public class RelatedApprovalGuardImpl implements IRelatedApprovalGuard {

    /** valData 在 formData 载荷里的键（与 NodeFieldWriteGuardImpl 保持一致） */
    private static final String FORM_KEY = "formData";
    private static final String VAL_KEY = "valData";

    @Autowired
    private RelatedApprovalMapper relatedApprovalMapper;

    @Autowired
    private ITemplateService templateService;

    @Autowired
    private IRelatedApprovalConfigService relatedApprovalConfigService;

    @Override
    public void validate(CommonForm commonForm) {
        if (commonForm == null || StringUtils.isBlank(commonForm.getTemplateId())) {
            return;
        }
        Map<String, List<String>> selected = extractSelected(commonForm);
        if (selected.isEmpty()) {
            return;
        }
        Template host = templateService.getTemplateById(commonForm.getTemplateId());
        if (host == null) {
            throw new com.ruoyi.common.exception.base.BaseException("关联审批：宿主模板不存在");
        }
        String userId = SecurityUtils.getUserId();
        for (Map.Entry<String, List<String>> entry : selected.entrySet()) {
            String fieldVmodel = entry.getKey();
            List<String> chosen = entry.getValue();
            // 控件没配候选 → 不允许选任何单据（失败方向是拒绝，不是放行）
            List<String> allow = templateService.listRelatedApprovalAllowTemplates(host.getId(), fieldVmodel);
            if (allow.isEmpty()) {
                throw new com.ruoyi.common.exception.base.BaseException(
                        "关联审批：控件未配置候选模板，不能关联任何单据（字段 " + fieldVmodel + "）");
            }
            List<RelatedApprovalCandidate> candidates =
                    relatedApprovalMapper.selectCandidates(allow, host.getType(), userId, null);
            Set<String> allowed = new LinkedHashSet<>();
            for (RelatedApprovalCandidate candidate : candidates) {
                allowed.add(candidate.getBusinessId());
            }
            for (String businessId : chosen) {
                if (!allowed.contains(businessId)) {
                    log.warn("关联审批越界选择（已拒绝）：host={} field={} related={} userId={}",
                            host.getId(), fieldVmodel, businessId, userId);
                    throw new com.ruoyi.common.exception.base.BaseException(
                            "关联审批：单据 " + businessId + " 不在可选范围内（仅限同分组、本人发起的单据）");
                }
            }
        }
    }

    @Override
    public void persist(CommonForm commonForm, String businessId) {
        if (commonForm == null || StringUtils.isBlank(businessId)) {
            return;
        }
        List<RelatedApproval> rows = new ArrayList<>();
        Map<String, List<String>> selected = extractSelected(commonForm);
        if (!selected.isEmpty()) {
            String userId = SecurityUtils.getUserId();
            Date now = new Date();
            for (Map.Entry<String, List<String>> entry : selected.entrySet()) {
                for (String relatedId : entry.getValue()) {
                    RelatedApproval row = new RelatedApproval();
                    row.setId(IdUtils.fastSimpleUUID());
                    row.setBusinessId(businessId);
                    row.setFieldVmodel(entry.getKey());
                    row.setRelatedBusinessId(relatedId);
                    // 快照：模板ID + 单据号（被关联单据之后改名也不影响历史展示）
                    RelatedApprovalCandidate candidate = relatedApprovalMapper.selectCandidateByBusinessId(relatedId);
                    if (candidate != null) {
                        row.setRelatedTemplateId(candidate.getTemplateId());
                        row.setRelatedBusinessNo(relatedApprovalConfigService.resolveBusinessNo(relatedId,
                                candidate.getBusinessNo()));
                    }
                    row.setCreateId(userId);
                    row.setCreateTime(now);
                    rows.add(row);
                }
            }
        }
        // 全量替换：改单时取消勾选要真的删掉，不能只增不减
        relatedApprovalMapper.deleteByBusinessId(businessId);
        if (!rows.isEmpty()) {
            relatedApprovalMapper.batchInsert(rows);
        }
    }

    /**
     * 从表单载荷里取出「关联审批」控件当前选中的单据ID。
     *
     * <p> 只挑**本模板配置过的控件**（{@code t_template_related_approval} 里的 field_vmodel），
     * 其余字段一律不看 —— 避免把普通字段的偶然同名字段当成关联关系。 </p>
     *
     * @return field_vmodel -> 去重后的被关联单据ID
     */
    private Map<String, List<String>> extractSelected(CommonForm commonForm) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        JSONObject values = extractValData(commonForm);
        if (values == null || values.isEmpty()) {
            return out;
        }
        List<String> fields = relatedApprovalConfigService.listConfiguredFields(commonForm.getTemplateId());
        for (String vModel : fields) {
            Object value = values.get(vModel);
            List<String> ids = toIdList(value);
            if (!ids.isEmpty()) {
                out.put(vModel, ids);
            }
        }
        return out;
    }

    /** 控件的值可能是数组、也可能是逗号串（历史数据/直接调接口两种都见过） */
    private List<String> toIdList(Object value) {
        List<String> ids = new ArrayList<>();
        if (value == null) {
            return ids;
        }
        if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) {
                String id = item == null ? null : String.valueOf(item).trim();
                if (StringUtils.isNotBlank(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String id = item == null ? null : String.valueOf(item).trim();
                if (StringUtils.isNotBlank(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        for (String id : String.valueOf(value).split(",")) {
            String trimmed = id.trim();
            if (StringUtils.isNotBlank(trimmed) && !ids.contains(trimmed)) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    /** valData 既可能在 formData 载荷里，也可能已经在 commonForm.valData 上（两条入口都要认） */
    private JSONObject extractValData(CommonForm commonForm) {
        if (commonForm.getValData() != null && !commonForm.getValData().isEmpty()) {
            return new JSONObject((Map<String, Object>) commonForm.getValData());
        }
        Object formData = commonForm.getFormData();
        if (!(formData instanceof Map)) {
            if (formData instanceof String) {
                try {
                    formData = JSON.parseObject((String) formData);
                } catch (Exception e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        Object payload = ((Map<?, ?>) formData).get(FORM_KEY);
        if (payload == null) {
            payload = formData;
        }
        try {
            JSONObject obj = payload instanceof String ? JSON.parseObject((String) payload) : JSON.parseObject(JSON.toJSONString(payload));
            if (obj == null) {
                return null;
            }
            Object val = obj.get(VAL_KEY);
            if (val == null) {
                return obj;
            }
            return val instanceof String ? JSON.parseObject((String) val) : JSON.parseObject(JSON.toJSONString(val));
        } catch (Exception e) {
            log.warn("关联审批：表单载荷解析失败，按未选择处理：{}", e.getMessage());
            return null;
        }
    }
}
