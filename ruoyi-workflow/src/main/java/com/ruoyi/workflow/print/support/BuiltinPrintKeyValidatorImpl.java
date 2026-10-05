package com.ruoyi.workflow.print.support;

import com.ruoyi.template.support.BuiltinPrintKeyValidator;
import org.springframework.stereotype.Component;

/**
 * <p> {@link BuiltinPrintKeyValidator} 的实现：直接委托给内置版式的唯一真源
 * {@link BuiltinPrintTemplates}，**不复制 key 清单**。 </p>
 *
 * <p> 装配在 {@code ruoyi-workflow}（打印模块）里，由 {@code ruoyi-template}
 * 通过接口注入 —— 依赖方向仍是 workflow → template。 </p>
 *
 * @author 二开
 */
@Component
public class BuiltinPrintKeyValidatorImpl implements BuiltinPrintKeyValidator {

    @Override
    public boolean isValid(String key) {
        return BuiltinPrintTemplates.isValidKey(key);
    }

    @Override
    public String allowedKeys() {
        return String.join(" / ", BuiltinPrintTemplates.keys());
    }

    @Override
    public String defaultKey() {
        return BuiltinPrintTemplates.DEFAULT_KEY;
    }
}
