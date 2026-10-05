package com.ruoyi.workflow.print.service.impl;

import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.mapper.PrintTemplateMapper;
import com.ruoyi.workflow.print.support.BuiltinPrintTemplates;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <p> 生效打印模板的<b>优先级链</b>与"按单据类型选内置版式"（2.0 B2 §1.6；REQ-PRINT-011） </p>
 *
 * <p> 优先级链是本次变更**明确不许动**的东西（design "优先级链已存在且正确。本次不动"），
 * 所以这里把它钉成可执行断言：显式 {@code printTplId} &gt; 该单据模板下的启用行 &gt; 内置版式。
 * 同时断言"该走哪一段就只查哪一段"——避免以后有人为了省事把三次查询都无脑走一遍。 </p>
 *
 * <p> 不引 Mockito：{@link PrintTemplateMapper} 是接口，写一个记录调用的手写桩更直白，
 * 也顺便证明了服务层除了这个 Mapper 之外没有别的隐藏依赖。 </p>
 *
 * @author 二开
 */
public class PrintEffectiveTemplatePriorityTest {

    /** 手写桩：记录自己被问了什么，便于断言"没有多查" */
    private static class StubMapper implements PrintTemplateMapper {

        PrintTemplate byId;
        List<PrintTemplate> byTemplateId;
        String builtinKey;

        int byTemplateIdCalls;
        int builtinKeyCalls;

        @Override
        public PrintTemplate selectById(String id) {
            return byId;
        }

        @Override
        public List<PrintTemplate> selectByTemplateId(String templateId) {
            byTemplateIdCalls++;
            return byTemplateId;
        }

        @Override
        public String selectBuiltinPrintKeyByTemplateId(String templateId) {
            builtinKeyCalls++;
            return builtinKey;
        }

        @Override
        public String selectTemplateIdByBusinessId(String businessId) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public List<PrintTemplate> selectList(PrintTemplate query) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public int insert(PrintTemplate printTemplate) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public int update(PrintTemplate printTemplate) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public int deleteById(PrintTemplate printTemplate) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public int disableOthers(String templateId, String keepId) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }

        @Override
        public int updateBuiltinPrintKey(String templateId, String builtinKey) {
            throw new UnsupportedOperationException("优先级链用例不应走到这里");
        }
    }

    private StubMapper mapper;
    private PrintServiceImpl service;

    @Before
    public void setUp() throws Exception {
        mapper = new StubMapper();
        service = new PrintServiceImpl();
        Field f = PrintServiceImpl.class.getDeclaredField("printTemplateMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    /* ==================== ① 显式 printTplId 优先 ==================== */

    @Test
    public void 显式printTplId优先_不读启用行也不读内置键() {
        PrintTemplate explicit = tpl("PT-EXPLICIT", "手工指定的一套");
        mapper.byId = explicit;
        mapper.byTemplateId = Collections.singletonList(tpl("PT-ENABLED", "该模板下的启用行"));
        mapper.builtinKey = "fund";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", "PT-EXPLICIT");

        assertSame(explicit, got);
        assertEquals("不该去查该单据模板下的启用行", 0, mapper.byTemplateIdCalls);
        assertEquals("不该去读内置版式键", 0, mapper.builtinKeyCalls);
    }

    /* ==================== ② 启用行优先于内置 ==================== */

    @Test
    public void 启用行优先于内置_不读内置键() {
        PrintTemplate enabled = tpl("PT-ENABLED", "该模板下的启用行");
        mapper.byTemplateId = Collections.singletonList(enabled);
        mapper.builtinKey = "fund";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        assertSame(enabled, got);
        assertEquals(1, mapper.byTemplateIdCalls);
        assertEquals("有启用行时不该回退内置，也就无需读内置键", 0, mapper.builtinKeyCalls);
    }

    /* ==================== ③ 都没有时回退内置，且按 key 取版式 ==================== */

    @Test
    public void 无显式也无启用行时回退内置_按键取标题与版式() {
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = "fund";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        assertNull("内置模板没有数据库行，id 必须为空（前端据此判定为内置）", got.getId());
        assertEquals("TPL-1", got.getTemplateId());
        assertEquals("集团资金审批单", got.getTitle());
        assertEquals("fund", got.getBuiltinKey());
        assertEquals(BuiltinPrintTemplates.fieldMapOf("fund"), got.getFieldMap());
        assertEquals(1, mapper.builtinKeyCalls);
    }

    @Test
    public void 内置版式键为空时回退contract并保持升级前标题() {
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = null;

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        assertEquals("集团合同类文件流转审批单", got.getTitle());
        assertEquals("contract", got.getBuiltinKey());
        assertEquals(BuiltinPrintTemplates.fieldMapOf("contract"), got.getFieldMap());
    }

    @Test
    public void 内置版式键为脏值时回退contract_不抛异常() {
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = "fund-v2";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        assertEquals("contract", got.getBuiltinKey());
        assertEquals("集团合同类文件流转审批单", got.getTitle());
    }

    @Test
    public void 显式printTplId查不到时继续往下走而不是报错() {
        mapper.byId = null;
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = "matter";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", "PT-NOT-EXIST");

        assertEquals("集团事项类打印审批单", got.getTitle());
        assertEquals("matter", got.getBuiltinKey());
    }

    @Test
    public void 回退内置fund版式时必须自带签批栏开启_AC62() {
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = "fund";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        // AC-62 的第一验收项：内置 fund 版式用内置模板即可打印出三栏签名区。
        // 若这里被 fillDefaults 的"默认关闭"覆盖，资金审批单就又没有签名区了。
        assertEquals("1", got.getShowSignature());
        assertEquals("0", got.getShowAttachment());
    }

    /* ==================== 内置模板的既有取值不被本次变更破坏 ==================== */

    @Test
    public void 内置模板的空值填充口径不变() {
        mapper.byTemplateId = new ArrayList<>();
        mapper.builtinKey = "payment";

        PrintTemplate got = service.getEffectiveTemplate("TPL-1", null);

        assertEquals("A4", got.getPaper());
        assertEquals("portrait", got.getOrientation());
        // 签批栏由版式自带推荐值（1）；其余仍是既有默认
        assertEquals("1", got.getShowSignature());
        assertEquals("0", got.getShowAttachment());
        assertEquals("1", got.getShowComment());
        assertEquals("1", got.getWatermark());
        assertEquals("1", got.getEnableFlag());
        assertNotNull(got.getFieldMap());
        assertTrue(got.getBuiltinKey().length() > 0);
    }

    private static PrintTemplate tpl(String id, String name) {
        PrintTemplate t = new PrintTemplate();
        t.setId(id);
        t.setName(name);
        return t;
    }
}
