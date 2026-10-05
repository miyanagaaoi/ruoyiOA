package com.ruoyi.template.spi;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * <p> 「这张表单被哪个流程用着、用到了哪些字段」的一条记录（PRD V-8 反向校验的输入）。 </p>
 *
 * <p> 放在 {@code ruoyi-template} 的 {@code spi} 包下、由 {@code ruoyi-workflow} 实现
 * （见 {@link FlowFormUsageProvider}）—— 依赖方向是 workflow → template，反向调用会成环。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
@Data
public class FlowFormUsage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** t_flow_simple.id */
    private String flowId;

    /** 流程定义 key（t_flow_simple.def_key） */
    private String defKey;

    /** 流程名称 */
    private String flowName;

    /** 0-草稿 / 1-已发布（只有"已发布"才属于 PRD V-8 的阻断口径） */
    private String status;

    /** 流程内容里的 formId（t_flow_simple.content.formId） */
    private String formId;

    /** 条件分支引用到的字段 {@code __vModel__} 清单 */
    private List<String> conditionFields = new ArrayList<>();

    /** 并行分支引用到的"表单多选字段"清单（运行时取不到该字段会直接报错，故一并校验） */
    private List<String> multiFields = new ArrayList<>();

    /** 是否已发布（V-8 阻断口径只看已发布流程） */
    public boolean isPublished() {
        return "1".equals(status);
    }
}
