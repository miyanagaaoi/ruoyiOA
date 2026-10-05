package com.ruoyi.serial.module;

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p> 取号上下文（2.0 B3 §1.3 对编号服务的最小扩展；design.md D-6）。 </p>
 *
 * <p> <b>为什么需要它</b>：合同编号的格式是「3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号」
 * （例 {@code PURZC202506000001}），其中 </p>
 * <ul>
 *   <li> 年份/月份码要取<b>签订日期</b>，而既有实现恒取服务器当天（{@code CodeGenServiceImpl:112}）； </li>
 *   <li> 类型码/主体码是<b>业务参数</b>，而 {@code ruleValue} 只能承载固定值或日期格式串； </li>
 *   <li> 序号维度是「类型码 + 主体码 + 年份」，而既有实现的计数器只按 {@code confId} 分桶。 </li>
 * </ul>
 *
 * <p> 这三点都要求"调用方把上下文传进来"，所以新增本类作为可选入参：
 * <b>不传（{@code null}）时，取号行为与扩展前逐字一致</b> —— 既有 11 类编号不受影响。 </p>
 *
 * @author 二开
 */
public class CodeGenContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 参考日期：日期规则按它格式化（合同场景传签订日期）；为空则维持既有行为"取当天" */
    private Date referenceDate;

    /**
     * 业务参数：规则类型为「业务参数」的规则按 {@code ruleValue} 作为键从这里取值。
     *
     * <p> 非空即启用<b>分桶计数</b>：计数器键会带上这些参数（见
     * {@code CodeRenderSupport.bucketKeyOf}），从而让"类型码 + 主体码"各自独立计数。 </p>
     */
    private Map<String, String> params;

    public CodeGenContext() {
    }

    public CodeGenContext(Date referenceDate, Map<String, String> params) {
        this.referenceDate = referenceDate;
        this.params = params == null ? null : new LinkedHashMap<>(params);
    }

    /** 只带参考日期（不启用分桶） */
    public static CodeGenContext ofDate(Date referenceDate) {
        return new CodeGenContext(referenceDate, null);
    }

    /** 只带业务参数（日期规则仍取当天） */
    public static CodeGenContext ofParams(Map<String, String> params) {
        return new CodeGenContext(null, params);
    }

    public Date getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(Date referenceDate) {
        this.referenceDate = referenceDate;
    }

    public Map<String, String> getParams() {
        return params == null ? Collections.emptyMap() : params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }

    /** 是否启用分桶计数（有业务参数才算） */
    public boolean isBucketed() {
        return params != null && !params.isEmpty();
    }
}
