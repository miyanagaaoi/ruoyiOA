package com.ruoyi.template.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p> 动态表单内容（form-generator schema）→ <b>字段索引</b>：{@code __vModel__} → 标签/控件/是否必填。 </p>
 *
 * <p> <b>为什么需要它</b>：PRD V-8 要求"条件字段必须是表单中存在的、且为『必填』的字段"，
 * 而这条口径此前只在前端（{@code src/utils/formSchema.js} 的 {@code extractFormFields} /
 * {@code requiredFieldNames}）实现过 —— 后端拿不到字段清单，于是"表单保存时反向校验"（PRD V-8 后半句）
 * 根本无法实现。本类是后端侧的唯一解析口径，**与前端同名函数逐条对齐**： </p>
 *
 * <ul>
 *   <li> 只递归 {@code __config__.children}（行容器），容器自身不是字段； </li>
 *   <li> 既没有 {@code __vModel__} 也没有 {@code __config__.tag} 的条目跳过； </li>
 *   <li> 必填 = {@code __config__.required === true}（严格等于 true，字符串 {@code "true"} 不算，
 *        与前端 {@code cfg.required === true} 一致）； </li>
 *   <li> 多选 = tag 为 {@code el-checkbox-group}（同前端 {@code MULTI_TAGS}）。 </li>
 * </ul>
 *
 * <p> 解析失败（非法 JSON）时返回**空索引**而不是抛异常：调用方（表单保存守卫）必须能区分
 * "字段确实少"与"内容压根解析不了"，后者要单独告警而不能误判成"所有流程的字段都失效"。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public final class FormFieldIndex {

    /** 多选控件 tag（与前端 {@code formSchema.js} 的 MULTI_TAGS 同口径） */
    public static final String TAG_CHECKBOX_GROUP = "el-checkbox-group";

    private final Map<String, Field> fields = new LinkedHashMap<>();
    /** 内容是否解析失败（区分"空表单"与"坏 JSON"） */
    private final boolean parsed;

    private FormFieldIndex(boolean parsed) {
        this.parsed = parsed;
    }

    /** 解析表单内容；任何异常都退化为"不可用索引"（{@link #isParsed()} 为 false） */
    public static FormFieldIndex parse(String content) {
        if (StringUtils.isBlank(content)) {
            return new FormFieldIndex(false);
        }
        FormFieldIndex index = new FormFieldIndex(true);
        try {
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                return new FormFieldIndex(false);
            }
            index.walk(root.getJSONArray("fields"));
        } catch (Exception e) {
            return new FormFieldIndex(false);
        }
        return index;
    }

    private void walk(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject f = arr.getJSONObject(i);
            if (f == null) {
                continue;
            }
            JSONObject cfg = f.getJSONObject("__config__");
            if (cfg == null) {
                continue;
            }
            JSONArray children = cfg.getJSONArray("children");
            if (children != null && !children.isEmpty()) {
                walk(children);
                continue;
            }
            String vModel = f.getString("__vModel__");
            String tag = cfg.getString("tag");
            if (StringUtils.isBlank(vModel) || StringUtils.isBlank(tag)) {
                continue;
            }
            Field field = new Field();
            field.vModel = vModel;
            field.tag = tag;
            field.label = StringUtils.defaultIfBlank(cfg.getString("label"), vModel);
            // ⚠ 必须"严格等于布尔 true"：前端口径是 cfg.required === true。
            //   不能写 cfg.getBoolean("required")——fastjson 会把字符串 "true"/"1" 也转成 true，
            //   于是"后端认为必填、前端认为不必填"，又造出一个新的口径分叉（V-8 会两边不一致）。
            Object rawRequired = cfg.get("required");
            field.required = (rawRequired instanceof Boolean) && ((Boolean) rawRequired).booleanValue();
            field.multi = TAG_CHECKBOX_GROUP.equals(tag);
            fields.put(vModel, field);
        }
    }

    /** 字段是否存在 */
    public boolean contains(String vModel) {
        return StringUtils.isNotBlank(vModel) && fields.containsKey(vModel);
    }

    /** 字段是否存在**且**被设为必填（PRD V-8 的判据） */
    public boolean isRequired(String vModel) {
        Field f = StringUtils.isBlank(vModel) ? null : fields.get(vModel);
        return f != null && f.required;
    }

    /** 字段是否存在**且**为多选控件（并行分支"集合来源"的判据） */
    public boolean isMulti(String vModel) {
        Field f = StringUtils.isBlank(vModel) ? null : fields.get(vModel);
        return f != null && f.multi;
    }

    /** 字段标签（查不到回退字段名，用于错误文案） */
    public String labelOf(String vModel) {
        Field f = fields.get(vModel);
        return f == null ? vModel : f.label;
    }

    /** 解析成功且字段清单已建立 */
    public boolean isParsed() {
        return parsed;
    }

    public int size() {
        return fields.size();
    }

    /** 全部字段名（只读） */
    public Set<String> vModels() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    /** 必填字段名（对应前端 {@code requiredFieldNames}） */
    public Set<String> requiredFields() {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : fields.values()) {
            if (f.required) {
                out.add(f.vModel);
            }
        }
        return out;
    }

    /** 多选字段名（对应前端 {@code multiFieldNames}） */
    public Set<String> multiFields() {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : fields.values()) {
            if (f.multi) {
                out.add(f.vModel);
            }
        }
        return out;
    }

    public Map<String, Field> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** 单个字段（不可变视图） */
    public static final class Field {
        private String vModel;
        private String label;
        private String tag;
        private boolean required;
        private boolean multi;

        public String getVModel() {
            return vModel;
        }

        public String getLabel() {
            return label;
        }

        public String getTag() {
            return tag;
        }

        public boolean isRequired() {
            return required;
        }

        public boolean isMulti() {
            return multi;
        }
    }
}
