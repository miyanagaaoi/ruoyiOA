package com.ruoyi.template.support;

/**
 * <p> 「内置打印版式键」的合法性校验口（2.0 B2 / REQ-PRINT-011） </p>
 *
 * <p> <b>为什么是一个接口而不是一个常量表</b>：单据模板保存在 {@code ruoyi-template}，
 * 而内置版式的真源在 {@code ruoyi-workflow}（{@code BuiltinPrintTemplates}），
 * 依赖方向是 workflow → template。若在模板模块里再抄一份 key 清单，
 * 就正好制造了本变更要消除的那类重复（REQ-NFR-009 / REQ-PRINT-013）。
 * 所以这里只声明"校验口"，实现由打印模块注入；实现缺失（例如打印模块未装配）时
 * 校验**放行**，不让一个可选能力把模板保存链路堵死。 </p>
 *
 * @author 二开
 */
public interface BuiltinPrintKeyValidator {

    /** 该 key 是否为已登记的内置版式 */
    boolean isValid(String key);

    /** 合法取值（逗号分隔），用于错误提示文案 */
    String allowedKeys();

    /** 默认版式键（新模板未显式选择时用） */
    String defaultKey();
}
