package com.ruoyi.workflow.related.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.template.service.ITemplateService;
import com.ruoyi.workflow.mapper.SignRecordMapper;
import com.ruoyi.workflow.related.service.IRelatedApprovalConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> 「关联审批」配置读取与单据号解析（2.0 B1 §7） </p>
 *
 * <p> 单据号（流水号）在动态表单里是**一个控件的值**（{@code design-serial-no}），
 * 库里没有独立列，所以这里从 {@code t_workflow_form.form_data} 的 valData 里取。
 * 取不到就退回调用方给的备用值，再退回短ID —— 宁可显示得难看，也不编造一个单据号。 </p>
 *
 * @author 二开
 */
@Slf4j
@Service
public class RelatedApprovalConfigServiceImpl implements IRelatedApprovalConfigService {

    /** 流水号控件在表单 schema 里的 tag */
    private static final String SERIAL_NO_TAG = "design-serial-no";

    @Autowired
    private SignRecordMapper signRecordMapper;

    @Autowired
    private ITemplateService templateService;

    @Override
    public List<String> listConfiguredFields(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return new ArrayList<>();
        }
        // 权威来源是候选表（模板保存时从 schema 解析落库）；表里没有 = 该模板没配这个控件
        List<String> fields = templateService.listRelatedApprovalFields(templateId);
        return fields == null ? new ArrayList<>() : fields;
    }

    @Override
    public String resolveBusinessNo(String businessId, String fallback) {
        String fromForm = readSerialNoFromForm(businessId);
        if (StringUtils.isNotBlank(fromForm)) {
            return fromForm;
        }
        if (StringUtils.isNotBlank(fallback)) {
            return fallback;
        }
        return StringUtils.isBlank(businessId) ? "" : "单据" + shortId(businessId);
    }

    /** 从表单数据里取流水号控件的值（schema 里 tag=design-serial-no 的字段） */
    private String readSerialNoFromForm(String businessId) {
        if (StringUtils.isBlank(businessId)) {
            return null;
        }
        try {
            String formJson = signRecordMapper.selectFormDataById(businessId);
            if (StringUtils.isBlank(formJson)) {
                return null;
            }
            JSONObject root = JSON.parseObject(formJson);
            JSONObject schema = root.getJSONObject("formData");
            JSONObject values = root.getJSONObject("valData");
            if (schema == null || values == null) {
                return null;
            }
            List<String> vModels = new ArrayList<>();
            collectSerialVModels(schema.get("fields"), vModels);
            for (String vModel : vModels) {
                Object value = values.get(vModel);
                if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                    return String.valueOf(value);
                }
            }
        } catch (Exception e) {
            log.warn("关联审批：单据号解析失败（按无单据号处理）：businessId={} err={}", businessId, e.getMessage());
        }
        return null;
    }

    private void collectSerialVModels(Object node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectSerialVModels(item, out);
            }
            return;
        }
        JSONObject obj;
        try {
            obj = node instanceof JSONObject ? (JSONObject) node : JSON.parseObject(JSON.toJSONString(node));
        } catch (Exception e) {
            return;
        }
        if (obj == null) {
            return;
        }
        JSONObject config = obj.getJSONObject("__config__");
        if (config != null && SERIAL_NO_TAG.equals(config.getString("tag"))
                && StringUtils.isNotBlank(obj.getString("__vModel__"))) {
            out.add(obj.getString("__vModel__"));
        }
        if (config != null && config.get("children") != null) {
            collectSerialVModels(config.get("children"), out);
        }
        if (obj.get("children") != null) {
            collectSerialVModels(obj.get("children"), out);
        }
    }

    private String shortId(String id) {
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }
}
