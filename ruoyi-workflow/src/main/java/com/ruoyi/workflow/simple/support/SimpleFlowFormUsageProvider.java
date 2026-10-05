package com.ruoyi.workflow.simple.support;

import com.ruoyi.template.spi.FlowFormUsage;
import com.ruoyi.template.spi.FlowFormUsageProvider;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.mapper.FlowSimpleMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> {@link FlowFormUsageProvider} 的流程侧实现：按表单版本 id 反查"哪些流程还在用它"。 </p>
 *
 * <p> 供 {@code ruoyi-template} 的 PRD V-8 反向校验调用（表单保存前算出受影响的流程）。
 * 只读、无副作用：只查 {@code t_flow_simple}（{@code del_flag='0'}）并解析 {@code content}。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Slf4j
@Component
public class SimpleFlowFormUsageProvider implements FlowFormUsageProvider {

    @Autowired
    private FlowSimpleMapper flowSimpleMapper;

    @Override
    public List<FlowFormUsage> listByFormId(String formId) {
        List<FlowFormUsage> out = new ArrayList<>();
        if (StringUtils.isBlank(formId)) {
            return out;
        }
        List<FlowSimple> flows = flowSimpleMapper.selectList(new FlowSimple());
        if (flows == null || flows.isEmpty()) {
            return out;
        }
        for (FlowSimple f : flows) {
            SimpleFlowContentIndex idx = SimpleFlowContentIndex.parse(f.getContent());
            if (!idx.isParsed() || !formId.equals(idx.getFormId())) {
                continue;
            }
            FlowFormUsage usage = new FlowFormUsage();
            usage.setFlowId(f.getId());
            usage.setDefKey(f.getDefKey());
            usage.setFlowName(f.getName());
            usage.setStatus(f.getStatus());
            usage.setFormId(idx.getFormId());
            usage.setConditionFields(idx.getConditionFields());
            usage.setMultiFields(idx.getMultiFields());
            out.add(usage);
        }
        if (!out.isEmpty()) {
            log.debug("表单 {} 被 {} 个流程引用：{}", formId, out.size(), out);
        }
        return out;
    }
}
