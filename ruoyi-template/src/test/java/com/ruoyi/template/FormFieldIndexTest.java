package com.ruoyi.template;

import com.ruoyi.template.support.FormFieldIndex;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> 动态表单内容 → 字段索引的解析口径单测（{@link FormFieldIndex}）。 </p>
 *
 * <p> 口径必须与前端 {@code src/utils/formSchema.js} 的 {@code extractFormFields} /
 * {@code requiredFieldNames} / {@code multiFieldNames} 一致 —— 两边各算一套的话，
 * "界面说这个字段能选、后端说它不存在"就成了新的错位来源。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public class FormFieldIndexTest {

    @Test
    public void 解析基本字段与必填标记() {
        String content = "{\"fields\":["
                + "{\"__config__\":{\"label\":\"合同类型\",\"tag\":\"el-radio-group\",\"required\":true},"
                + "\"__vModel__\":\"field101\"},"
                + "{\"__config__\":{\"label\":\"金额\",\"tag\":\"el-input\",\"required\":false},"
                + "\"__vModel__\":\"field104\"}]}";

        FormFieldIndex idx = FormFieldIndex.parse(content);

        assertTrue(idx.isParsed());
        assertEquals(2, idx.size());
        assertTrue(idx.contains("field101"));
        assertTrue(idx.isRequired("field101"));
        assertFalse(idx.isRequired("field104"));
        assertEquals("金额", idx.labelOf("field104"));
        // 查不到的字段 → labelOf 回退字段名（错误文案里不能是 null）
        assertEquals("field999", idx.labelOf("field999"));
    }

    @Test
    public void 行容器里的子控件要递归识别() {
        String content = "{\"fields\":["
                + "{\"__config__\":{\"label\":\"行容器\",\"tag\":\"rowFormItem\",\"children\":["
                + "{\"__config__\":{\"label\":\"会审部门\",\"tag\":\"el-checkbox-group\",\"required\":true},"
                + "\"__vModel__\":\"field105\"}]}}]}";

        FormFieldIndex idx = FormFieldIndex.parse(content);

        assertEquals(1, idx.size());
        assertTrue(idx.isRequired("field105"));
        assertTrue(idx.isMulti("field105"));
        assertEquals(1, idx.requiredFields().size());
        assertEquals(1, idx.multiFields().size());
    }

    @Test
    public void 容器自身不算字段() {
        String content = "{\"fields\":[{\"__config__\":{\"label\":\"行容器\",\"tag\":\"rowFormItem\","
                + "\"required\":true}}]}";

        FormFieldIndex idx = FormFieldIndex.parse(content);

        assertEquals("行容器没有 __vModel__，不该被当成字段", 0, idx.size());
    }

    @Test
    public void 必填必须是严格true() {
        // 前端口径是 cfg.required === true；字符串 "true" 不算
        String content = "{\"fields\":[{\"__config__\":{\"label\":\"x\",\"tag\":\"el-input\","
                + "\"required\":\"true\"},\"__vModel__\":\"fieldX\"}]}";

        FormFieldIndex idx = FormFieldIndex.parse(content);

        assertFalse(idx.isRequired("fieldX"));
    }

    @Test
    public void 坏JSON标记为未解析() {
        FormFieldIndex idx = FormFieldIndex.parse("{不是 JSON");
        assertFalse(idx.isParsed());
        assertEquals(0, idx.size());
    }

    @Test
    public void 空内容标记为未解析() {
        assertFalse(FormFieldIndex.parse(null).isParsed());
        assertFalse(FormFieldIndex.parse("   ").isParsed());
    }
}
