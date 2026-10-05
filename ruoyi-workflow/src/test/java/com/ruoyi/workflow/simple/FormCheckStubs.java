package com.ruoyi.workflow.simple;

import com.ruoyi.template.domain.Template;
import com.ruoyi.template.domain.TemplateDynamicForm;
import com.ruoyi.template.mapper.TemplateDynamicFormMapper;
import com.ruoyi.template.mapper.TemplateMapper;
import com.ruoyi.template.module.FormOption;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.mapper.FlowSimpleMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p> 一致性巡检单测用的内存桩（不起 Spring、不连库）。 </p>
 *
 * <p> 与仓库既有风格一致：{@code TemplateUpdateSemanticsCheck} / {@code CtmsProductMasterServiceImplTest}
 * 都是"内存桩 Mapper + 反射注入真实 ServiceImpl"，这样被测的是**真实实现**而不是复制品。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
final class FormCheckStubs {

    private FormCheckStubs() {
    }

    /** 反射注入（与既有测试同口径） */
    static void inject(Object target, String fieldName, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("字段不存在：" + fieldName);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("注入失败：" + fieldName, e);
        }
    }

    /** t_flow_simple 桩 */
    static class StubFlowSimpleMapper implements FlowSimpleMapper {
        final Map<String, FlowSimple> rows = new LinkedHashMap<>();

        @Override
        public FlowSimple selectById(String id) {
            FlowSimple f = rows.get(id);
            return f != null && !"1".equals(f.getDelFlag()) ? f : null;
        }

        @Override
        public FlowSimple selectByDefKey(String defKey) {
            for (FlowSimple f : rows.values()) {
                if (!"1".equals(f.getDelFlag()) && defKey != null && defKey.equals(f.getDefKey())) {
                    return f;
                }
            }
            return null;
        }

        @Override
        public List<FlowSimple> selectList(FlowSimple query) {
            List<FlowSimple> out = new ArrayList<>();
            for (FlowSimple f : rows.values()) {
                if (!"1".equals(f.getDelFlag())) {
                    out.add(f);
                }
            }
            return out;
        }

        @Override
        public int insert(FlowSimple flowSimple) {
            rows.put(flowSimple.getId(), flowSimple);
            return 1;
        }

        @Override
        public int update(FlowSimple flowSimple) {
            rows.put(flowSimple.getId(), flowSimple);
            return 1;
        }

        @Override
        public int deleteById(FlowSimple flowSimple) {
            FlowSimple f = rows.get(flowSimple.getId());
            if (f == null) {
                return 0;
            }
            f.setDelFlag("1");
            return 1;
        }
    }

    /** t_template 桩（只实现巡检用到的那一个查询） */
    static class StubTemplateMapper implements TemplateMapper {
        final Map<String, Template> rows = new LinkedHashMap<>();

        @Override
        public List<Template> selectTemplateList(Template template) {
            List<Template> out = new ArrayList<>();
            for (Template t : rows.values()) {
                if (!"1".equals(t.getDelFlag())) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override
        public Template selectTemplateById(String id) {
            return rows.get(id);
        }

        @Override
        public int insertTemplate(Template template) {
            rows.put(template.getId(), template);
            return 1;
        }

        @Override
        public int updateTemplate(Template template) {
            rows.put(template.getId(), template);
            return 1;
        }

        @Override
        public int deleteTemplateById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateByIds(String[] ids) {
            return 0;
        }

        @Override
        public int changeEnableFlag(Template template) {
            return 0;
        }

        @Override
        public List<Template> selectNewStartTemplateList(Template template) {
            return new ArrayList<>();
        }

        @Override
        public String selectDeptAncestors(String deptId) {
            return null;
        }

        @Override
        public String selectTemplateIdBySimpleFlowId(String simpleFlowId) {
            for (Template t : rows.values()) {
                if (!"1".equals(t.getDelFlag()) && simpleFlowId != null
                        && simpleFlowId.equals(t.getSimpleFlowId())) {
                    return t.getId();
                }
            }
            return null;
        }

        @Override
        public int repointFormId(String oldFormId, String newFormId) {
            int n = 0;
            for (Template t : rows.values()) {
                if (oldFormId != null && oldFormId.equals(t.getFormId())) {
                    t.setFormId(newFormId);
                    n++;
                }
            }
            return n;
        }

        @Override
        public int countByFormId(String formId) {
            int n = 0;
            for (Template t : rows.values()) {
                if (formId != null && formId.equals(t.getFormId())) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public int countByFormKey(String formKey) {
            return 0;
        }
    }

    /** t_template_dynamic_form 桩（按 id 取行，**不**过滤 del_flag/enable_flag，与真实 Mapper 一致） */
    static class StubTemplateDynamicFormMapper implements TemplateDynamicFormMapper {
        final Map<String, TemplateDynamicForm> rows = new LinkedHashMap<>();

        @Override
        public TemplateDynamicForm selectTemplateDynamicFormById(String id) {
            return rows.get(id);
        }

        @Override
        public List<TemplateDynamicForm> selectTemplateDynamicFormList(TemplateDynamicForm f) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public int insertTemplateDynamicForm(TemplateDynamicForm f) {
            rows.put(f.getId(), f);
            return 1;
        }

        @Override
        public int updateTemplateDynamicForm(TemplateDynamicForm f) {
            rows.put(f.getId(), f);
            return 1;
        }

        @Override
        public int deleteTemplateDynamicFormById(String id) {
            rows.remove(id);
            return 1;
        }

        @Override
        public int deleteTemplateDynamicFormByIds(String[] ids) {
            return 0;
        }

        @Override
        public List<FormOption> selectFormOptionList() {
            return new ArrayList<>();
        }
    }
}
