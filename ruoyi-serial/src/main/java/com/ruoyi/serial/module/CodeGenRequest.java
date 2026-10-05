package com.ruoyi.serial.module;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p> 取号上下文的<b>HTTP 入参</b>（2.0 B3 §1.3）。 </p>
 *
 * <p> 为什么不直接把 {@link CodeGenContext} 当 {@code @RequestBody}：
 * 它里面的 {@code referenceDate} 是 {@code java.util.Date}，而本工程的 Jackson 全局日期格式是
 * {@code yyyy-MM-dd HH:mm:ss} —— 传 {@code "2025-06-01"} 这种纯日期会解析失败，
 * 而"按签订日期取号"传的恰恰就是纯日期。所以这里用字符串承接、由服务端显式解析，
 * 避免踩全局日期格式的坑。 </p>
 *
 * @author 二开
 */
public class CodeGenRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 参考日期（合同场景 = 签订日期），格式 {@code yyyy-MM-dd}；空则日期规则取当天 */
    private String referenceDate;

    /** 业务参数（如 {@code typeCode} / {@code subjectCode}），键名与规则里的「业务参数」规则一一对应 */
    private Map<String, String> params;

    public String getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(String referenceDate) {
        this.referenceDate = referenceDate;
    }

    public Map<String, String> getParams() {
        return params == null ? new LinkedHashMap<>() : params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }
}
