package com.ruoyi.workflow.simple;

import com.ruoyi.template.spi.FlowFormUsage;
import com.ruoyi.workflow.domain.FlowSimple;
import com.ruoyi.workflow.simple.FormCheckStubs.StubFlowSimpleMapper;
import com.ruoyi.workflow.simple.support.SimpleFlowContentIndex;
import com.ruoyi.workflow.simple.support.SimpleFlowFormUsageProvider;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static com.ruoyi.workflow.simple.FormCheckStubs.inject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> 流程内容索引与"按表单反查流程"的单测
 * （{@link SimpleFlowContentIndex} / {@link SimpleFlowFormUsageProvider}）。 </p>
 *
 * <p> 这两块是 PRD V-8 反向校验的数据来源：既要**看得见** {@code content.formId} 与
 * 条件/并行字段，又不能因为一条坏草稿把表单保存链路带崩。 </p>
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
public class SimpleFlowFormUsageProviderTest {

    private static final String FORM_A = "FORM-A-1111";

    private StubFlowSimpleMapper flowMapper;
    private SimpleFlowFormUsageProvider provider;

    @Before
    public void setUp() {
        flowMapper = new StubFlowSimpleMapper();
        provider = new SimpleFlowFormUsageProvider();
        inject(provider, "flowSimpleMapper", flowMapper);
    }

    private static String content(String formId) {
        return "{\"schemaVersion\":1,\"key\":\"k\",\"formId\":\"" + formId + "\",\"nodes\":["
                + "{\"id\":\"start\",\"type\":\"start\"},"
                + "{\"id\":\"b1\",\"type\":\"condition\",\"name\":\"路由\",\"branches\":["
                + "{\"id\":\"b1_1\",\"name\":\"分支1\",\"groups\":[{\"logic\":\"AND\",\"rows\":["
                + "{\"field\":\"field101\",\"op\":\"EQ\",\"value\":\"经营\"},"
                + "{\"field\":\"field104\",\"op\":\"GT\",\"value\":10000}]}],"
                + "\"nodes\":[{\"id\":\"p1\",\"type\":\"parallel\",\"formField\":\"field105\"}]},"
                + "{\"id\":\"b1_d\",\"name\":\"其他情况\",\"defaultBranch\":true,"
                + "\"nodes\":[{\"id\":\"n3\",\"type\":\"approve\"}]}]},"
                + "{\"id\":\"end\",\"type\":\"end\"}]}";
    }

    private void addFlow(String id, String defKey, String status, String content) {
        FlowSimple f = new FlowSimple();
        f.setId(id);
        f.setDefKey(defKey);
        f.setName("流程-" + defKey);
        f.setStatus(status);
        f.setDelFlag("0");
        f.setContent(content);
        flowMapper.rows.put(id, f);
    }

    @Test
    public void 按表单反查流程_带上条件字段与并行字段() {
        addFlow("F1", "htsp", "1", content(FORM_A));

        List<FlowFormUsage> usages = provider.listByFormId(FORM_A);

        assertEquals(1, usages.size());
        FlowFormUsage u = usages.get(0);
        assertEquals("F1", u.getFlowId());
        assertEquals("htsp", u.getDefKey());
        assertTrue(u.isPublished());
        assertEquals(FORM_A, u.getFormId());
        assertTrue("条件字段要全部收集到：" + u.getConditionFields(),
                u.getConditionFields().contains("field101") && u.getConditionFields().contains("field104"));
        assertEquals("并行分支字段也要收集", 1, u.getMultiFields().size());
        assertEquals("field105", u.getMultiFields().get(0));
    }

    @Test
    public void 兜底分支的条件不算字段() {
        // 兜底分支（defaultBranch）不写条件，不该产生字段引用
        String c = "{\"formId\":\"" + FORM_A + "\",\"nodes\":[{\"id\":\"b1\",\"type\":\"condition\","
                + "\"branches\":[{\"id\":\"b1_d\",\"name\":\"其他情况\",\"defaultBranch\":true,"
                + "\"groups\":[{\"rows\":[{\"field\":\"shouldNotAppear\",\"op\":\"EQ\",\"value\":1}]}],"
                + "\"nodes\":[{\"id\":\"n3\",\"type\":\"approve\"}]}]}]}";
        addFlow("F1", "htsp", "1", c);

        List<FlowFormUsage> usages = provider.listByFormId(FORM_A);

        assertEquals(1, usages.size());
        assertTrue(usages.get(0).getConditionFields().isEmpty());
    }

    @Test
    public void 指向别的表单的流程不被返回() {
        addFlow("F1", "htsp", "1", content("OTHER-FORM"));
        assertTrue(provider.listByFormId(FORM_A).isEmpty());
    }

    @Test
    public void 坏JSON的流程被跳过_不影响其它流程() {
        addFlow("BAD", "broken", "1", "{不是 JSON");
        addFlow("F1", "htsp", "1", content(FORM_A));

        List<FlowFormUsage> usages = provider.listByFormId(FORM_A);

        assertEquals(1, usages.size());
        assertEquals("F1", usages.get(0).getFlowId());
    }

    @Test
    public void 已软删的流程不返回() {
        addFlow("F1", "htsp", "1", content(FORM_A));
        flowMapper.rows.get("F1").setDelFlag("1");

        assertTrue(provider.listByFormId(FORM_A).isEmpty());
    }

    @Test
    public void 空formId_直接返回空() {
        assertTrue(provider.listByFormId(null).isEmpty());
        assertTrue(provider.listByFormId("  ").isEmpty());
    }

    @Test
    public void 内容索引能读出formId() {
        SimpleFlowContentIndex idx = SimpleFlowContentIndex.parse(content(FORM_A));
        assertTrue(idx.isParsed());
        assertEquals(FORM_A, idx.getFormId());
        assertFalse(idx.allReferencedFields().isEmpty());
    }
}
