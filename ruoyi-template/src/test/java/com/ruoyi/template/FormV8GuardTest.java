package com.ruoyi.template;

import com.ruoyi.template.spi.FlowFormUsage;
import com.ruoyi.template.spi.FlowFormUsageProvider;
import com.ruoyi.template.support.FormV8Guard;
import com.ruoyi.template.support.FormV8Impact;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>PRD V-8 反向校验</b>单测（{@link FormV8Guard}）。 </p>
 *
 * <p> 不起 Spring、不连库：用内存桩 {@link FlowFormUsageProvider} 喂"谁在用这张表单"，
 * 被测的是**真实**的判定逻辑。用例覆盖： </p>
 * <ul>
 *   <li> 字段被删 / 改为非必填 → 已发布流程 ⇒ **阻断**（PRD V-8 原文口径） </li>
 *   <li> 字段仍然成立 ⇒ 只告警（错位提醒），不阻断 </li>
 *   <li> 草稿流程字段失效 ⇒ 只告警（草稿允许改） </li>
 *   <li> 并行分支字段被删 ⇒ 阻断（运行期取不到集合会直接报错） </li>
 *   <li> 没有 provider / 坏 JSON / 反查异常 ⇒ 不阻断且不静默（有告警或日志） </li>
 * </ul>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public class FormV8GuardTest {

    private static final String OLD_FORM_ID = "FORM-OLD-0001";

    /* ------------------------- 夹具构造 ------------------------- */

    /** 表单内容：field101 必填/非必填可切换；field105 放在行容器（__config__.children）里 */
    private static String formContent(boolean field101Required) {
        return "{\"formRef\":\"elForm\",\"formModel\":\"formData\",\"fields\":["
                + "{\"__config__\":{\"label\":\"合同类型\",\"tag\":\"el-radio-group\",\"required\":"
                + field101Required + "},\"__vModel__\":\"field101\"},"
                + "{\"__config__\":{\"label\":\"行容器\",\"tag\":\"rowFormItem\",\"children\":["
                + "{\"__config__\":{\"label\":\"会审部门\",\"tag\":\"el-checkbox-group\",\"required\":true},"
                + "\"__vModel__\":\"field105\"}]}}"
                + "]}";
    }

    private static FlowFormUsage usage(String flowId, String name, String status,
                                       List<String> conditionFields, List<String> multiFields) {
        FlowFormUsage u = new FlowFormUsage();
        u.setFlowId(flowId);
        u.setDefKey("flow_" + flowId);
        u.setFlowName(name);
        u.setStatus(status);
        u.setFormId(OLD_FORM_ID);
        u.setConditionFields(conditionFields == null ? new ArrayList<String>() : conditionFields);
        u.setMultiFields(multiFields == null ? new ArrayList<String>() : multiFields);
        return u;
    }

    private static final class StubProvider implements FlowFormUsageProvider {
        final Map<String, List<FlowFormUsage>> byFormId = new HashMap<>();

        void put(String formId, FlowFormUsage... usages) {
            byFormId.put(formId, new ArrayList<>(Arrays.asList(usages)));
        }

        @Override
        public List<FlowFormUsage> listByFormId(String formId) {
            List<FlowFormUsage> l = byFormId.get(formId);
            return l == null ? new ArrayList<FlowFormUsage>() : l;
        }
    }

    private static FormV8Guard guardWith(FlowFormUsageProvider provider) throws Exception {
        FormV8Guard guard = new FormV8Guard();
        if (provider != null) {
            Field f = FormV8Guard.class.getDeclaredField("flowFormUsageProvider");
            f.setAccessible(true);
            f.set(guard, provider);
        }
        return guard;
    }

    /* ------------------------- 用例 ------------------------- */

    @Test
    public void 条件字段被删且流程已发布_必须阻断并列出流程() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F1", "合同审批", "1",
                Arrays.asList("field101"), new ArrayList<String>()));
        // 新内容把 field101 删掉了（只剩行容器里的 field105）
        String newContent = "{\"fields\":[{\"__config__\":{\"label\":\"行容器\",\"tag\":\"rowFormItem\","
                + "\"children\":[{\"__config__\":{\"label\":\"会审部门\",\"tag\":\"el-checkbox-group\","
                + "\"required\":true},\"__vModel__\":\"field105\"}]}}]}";

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, newContent);

        assertTrue("字段被删 + 流程已发布 ⇒ 必须阻断", impact.isBlocked());
        assertEquals(1, impact.getBlocking().size());
        assertEquals("F1", impact.getBlocking().get(0).getFlowId());
        assertTrue(impact.getBlocking().get(0).getFields().contains("field101"));
        assertTrue("阻断文案必须列出受影响的流程与原因",
                impact.describe().contains("合同审批") && impact.describe().contains("field101"));
    }

    @Test
    public void 条件字段改为非必填且流程已发布_必须阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F1", "合同审批", "1",
                Arrays.asList("field101"), new ArrayList<String>()));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, formContent(false));

        assertTrue(impact.isBlocked());
        assertTrue(impact.getBlocking().get(0).getReason().contains("未设为必填"));
    }

    @Test
    public void 字段仍在且必填_只告警不阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F1", "合同审批", "1",
                Arrays.asList("field101"), new ArrayList<String>()));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, formContent(true));

        assertFalse("字段仍成立 ⇒ 不能阻断（否则改个 label 都会被拦）", impact.isBlocked());
        assertEquals(1, impact.getWarnings().size());
        assertTrue(impact.getWarnings().get(0).getReason().contains("不会自动跟随"));
    }

    @Test
    public void 草稿流程字段失效_只告警不阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F2", "未发布的草稿", "0",
                Arrays.asList("field101"), new ArrayList<String>()));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, "{\"fields\":[]}");

        assertFalse(impact.isBlocked());
        assertEquals(1, impact.getWarnings().size());
        assertTrue(impact.getWarnings().get(0).getReason().contains("尚未发布"));
    }

    @Test
    public void 并行分支字段被删_必须阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F3", "并行会审流程", "1",
                new ArrayList<String>(), Arrays.asList("field105")));
        // 新内容里 field105 也没了
        String newContent = "{\"fields\":[{\"__config__\":{\"label\":\"合同类型\",\"tag\":\"el-input\","
                + "\"required\":true},\"__vModel__\":\"field101\"}]}";

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, newContent);

        assertTrue(impact.isBlocked());
        assertTrue(impact.getBlocking().get(0).getReason().contains("并行分支字段"));
    }

    @Test
    public void 行容器里的子控件也被识别_字段仍成立时不阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F3", "并行会审流程", "1",
                new ArrayList<String>(), Arrays.asList("field105")));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, formContent(true));

        assertFalse("field105 在 __config__.children 里且必填 ⇒ 不该判成缺失", impact.isBlocked());
    }

    @Test
    public void 没有provider_不阻断也不报错() throws Exception {
        FormV8Impact impact = guardWith(null).analyze(OLD_FORM_ID, formContent(false));
        assertFalse(impact.isBlocked());
        assertTrue(impact.isEmpty());
    }

    @Test
    public void 坏JSON_只告警不阻断() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put(OLD_FORM_ID, usage("F1", "合同审批", "1",
                Arrays.asList("field101"), new ArrayList<String>()));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, "{不是 JSON");

        assertFalse("解析不了时不能把所有流程判成字段失效", impact.isBlocked());
        assertEquals(1, impact.getWarnings().size());
        assertTrue(impact.getWarnings().get(0).getReason().contains("无法解析"));
    }

    @Test
    public void 没有流程在用这张表单_完全无影响() throws Exception {
        StubProvider provider = new StubProvider();
        provider.put("OTHER-FORM", usage("F9", "别的流程", "1",
                Arrays.asList("field101"), new ArrayList<String>()));

        FormV8Impact impact = guardWith(provider).analyze(OLD_FORM_ID, "{\"fields\":[]}");

        assertTrue(impact.isEmpty());
    }
}
