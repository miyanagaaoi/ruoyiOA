package com.ruoyi.serial.support;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.serial.domain.CodeConfigRule;
import com.ruoyi.serial.module.CodeGenContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 合同编号扩展的<b>表驱动单测</b>（2.0 B3 §1.3；判定标准即 design.md D-6 的 4 条 + 向后兼容）。 </p>
 *
 * <p> 被测对象是 {@link CodeRenderSupport} 的纯函数：渲染（日期取参考日期、业务参数入码、
 * 流水号补零）与分桶（计数器键）。把它们抽成纯函数就是为了这一层能真正被断言 ——
 * 这也是 S0 §1.2 实测"配置表达不通过"之后，能证明"扩展后 4 条判定都过"的最直接手段。 </p>
 *
 * @author 二开
 */
public class CodeRenderSupportTest {

    private static final String CONF_ID = "B3NUMVERIFY000000000000000001";

    /* ==================== 夹具 ==================== */

    private static CodeConfigRule rule(String type, String value) {
        CodeConfigRule r = new CodeConfigRule();
        r.setRuleType(type);
        r.setRuleValue(value);
        r.setPadZero("0");
        r.setSeqResetType("0");
        return r;
    }

    private static CodeConfigRule seqRule(String len, String resetType) {
        CodeConfigRule r = rule("2", len);
        r.setPadZero("1");
        r.setSeqResetType(resetType);
        return r;
    }

    /** 合同编号：PARAM(typeCode) + PARAM(subjectCode) + DATE(yyyy) + DATE(MM) + SEQ(6, 按年) */
    private static List<CodeConfigRule> contractRules() {
        List<CodeConfigRule> rules = new ArrayList<>();
        rules.add(rule("3", "typeCode"));
        rules.add(rule("3", "subjectCode"));
        rules.add(rule("1", "yyyy"));
        rules.add(rule("1", "MM"));
        rules.add(seqRule("6", "4"));
        return rules;
    }

    /** 既有形态：FIXED(YX) + DATE(yyyyMMdd) + SEQ(2, 按日) */
    private static List<CodeConfigRule> legacyRules() {
        List<CodeConfigRule> rules = new ArrayList<>();
        rules.add(rule("0", "YX"));
        rules.add(rule("1", "yyyyMMdd"));
        rules.add(seqRule("2", "1"));
        return rules;
    }

    private static Map<String, String> params(String typeCode, String subjectCode) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("typeCode", typeCode);
        m.put("subjectCode", subjectCode);
        return m;
    }

    private static Date date(String yyyyMMdd) {
        Date d = DateUtils.parseDate(yyyyMMdd);
        if (d == null) {
            throw new IllegalStateException("测试日期解析失败：" + yyyyMMdd);
        }
        return d;
    }

    /* ==================== 判定标准 ① 按签订日期产号 ==================== */

    @Test
    public void 判定一_签订日期决定年份与月份码() {
        List<CodeConfigRule> rules = contractRules();
        // 签订 2025-06-01 → PURZC202506000001
        assertEquals("PURZC202506000001", CodeRenderSupport.render(rules, 1,
                new CodeGenContext(date("2025-06-01"), params("PUR", "ZC")), date("2026-10-05")));
        // 签订 2027-01-01 → PURZC202701000001（同一条规则、同一序号，年月都跟着签订日期走）
        assertEquals("PURZC202701000001", CodeRenderSupport.render(rules, 1,
                new CodeGenContext(date("2027-01-01"), params("PUR", "ZC")), date("2026-10-05")));
    }

    @Test
    public void 判定一_不传参考日期时日期规则仍取当天_既有行为() {
        assertEquals("PURZC202610000001", CodeRenderSupport.render(contractRules(), 1,
                new CodeGenContext(null, params("PUR", "ZC")), date("2026-10-05")));
    }

    /* ==================== 判定标准 ② 惰性跨年重置 ==================== */

    @Test
    public void 判定二_年份进入计数器键_新年天然从新桶开始() {
        Map<String, String> p = params("PUR", "ZC");
        String y2025 = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2025-06-01"), p));
        String y2027 = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2027-01-01"), p));
        assertNotEquals("不同年份必须是不同的计数器（否则跨年不会重置）：" + y2025, y2025, y2027);
        assertTrue(y2025.contains(":y2025"));
        assertTrue(y2027.contains(":y2027"));
        // 分桶路径的计数器初值恒为 0 → 新桶/新年的首个序号必然是 1（= 000001）
        assertEquals(0, CodeRenderSupport.initialSeqOf(99, new CodeGenContext(date("2027-01-01"), p)));
        assertEquals("PURZC202701000001", CodeRenderSupport.render(contractRules(),
                CodeRenderSupport.initialSeqOf(99, new CodeGenContext(date("2027-01-01"), p)) + 1,
                new CodeGenContext(date("2027-01-01"), p), date("2026-10-05")));
    }

    @Test
    public void 判定二_重置类型不是按年时年份不进键_不改变既有语义() {
        List<CodeConfigRule> rules = new ArrayList<>(contractRules());
        rules.set(4, seqRule("6", "0")); // 改成"不重置"
        Map<String, String> p = params("PUR", "ZC");
        String k1 = CodeRenderSupport.bucketKeyOf(CONF_ID, rules, new CodeGenContext(date("2025-06-01"), p));
        String k2 = CodeRenderSupport.bucketKeyOf(CONF_ID, rules, new CodeGenContext(date("2027-01-01"), p));
        assertEquals("非按年重置不应因年份分桶", k1, k2);
    }

    /* ==================== 判定标准 ③ 序号维度 = 类型码 + 主体码 + 年份 ==================== */

    @Test
    public void 判定三_不同主体码各自独立计数() {
        String zc = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2025-06-01"), params("PUR", "ZC")));
        String xs = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2025-06-01"), params("PUR", "XS")));
        assertNotEquals("同一配置下不同主体码必须是不同的计数器", zc, xs);
        assertTrue(zc.contains("subjectCode=ZC"));
        assertTrue(xs.contains("subjectCode=XS"));
    }

    @Test
    public void 判定三_不同类型码也各自独立计数() {
        String pur = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2025-06-01"), params("PUR", "ZC")));
        String sal = CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                new CodeGenContext(date("2025-06-01"), params("SAL", "ZC")));
        assertNotEquals(pur, sal);
        assertTrue(pur.contains("typeCode=PUR"));
    }

    @Test
    public void 判定三_分桶键与调用方传参顺序无关() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("subjectCode", "ZC");
        reversed.put("typeCode", "PUR");
        assertEquals(
                CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                        new CodeGenContext(date("2025-06-01"), params("PUR", "ZC"))),
                CodeRenderSupport.bucketKeyOf(CONF_ID, contractRules(),
                        new CodeGenContext(date("2025-06-01"), reversed)));
    }

    /* ==================== 业务参数的取值与报错 ==================== */

    @Test
    public void 业务参数缺失时给出明确报错而不是静默产出错号() {
        try {
            CodeRenderSupport.render(contractRules(), 1,
                    new CodeGenContext(date("2025-06-01"), params("PUR", null)), date("2026-10-05"));
            fail("缺少业务参数应该报错");
        } catch (BaseException e) {
            assertTrue("报错要点出参数名：" + e.getMessage(), e.getMessage().contains("subjectCode"));
        }
    }

    @Test
    public void 未提供上下文时业务参数规则报错而不是当空串() {
        try {
            CodeRenderSupport.render(contractRules(), 1, null, date("2026-10-05"));
            fail("业务参数规则在无上下文时应该报错");
        } catch (BaseException e) {
            assertTrue(e.getMessage().contains("业务参数"));
        }
    }

    /* ==================== 向后兼容（硬约束） ==================== */

    @Test
    public void 兼容_无上下文与扩展前逐字符一致_且键格式不变() {
        Date now = date("2026-10-05");
        // 扩展前的行为：FIXED + DATE(当天 yyyyMMdd) + SEQ(2 位补零)
        assertEquals("YX2026100501", CodeRenderSupport.render(legacyRules(), 1, null, now));
        assertEquals("YX2026100504", CodeRenderSupport.render(legacyRules(), 4, null, now));
        // 键：不带业务参数 → 与扩展前逐字一致
        assertEquals("code:gen:seq:" + CONF_ID,
                CodeRenderSupport.bucketKeyOf(CONF_ID, legacyRules(), null));
        assertEquals("code:gen:seq:" + CONF_ID,
                CodeRenderSupport.bucketKeyOf(CONF_ID, legacyRules(), CodeGenContext.ofDate(now)));
        // 初值：不分桶 → 用配置表 current_seq（既有行为）
        assertEquals(37, CodeRenderSupport.initialSeqOf(37, null));
        assertEquals(37, CodeRenderSupport.initialSeqOf(37, CodeGenContext.ofDate(now)));
        // ⚠ 初值的**装箱类型**必须是 Integer：写进 Redis 后要能被 INCR。
        //   FastJson2 会把 Long 序列化成 "0L"，INCR 直接报 ERR value is not an integer（实测踩过）。
        Object initial = CodeRenderSupport.initialSeqOf(0, null);
        assertTrue("计数器初值必须装箱成 Integer，实际是 "
                        + initial.getClass().getName() + "（写成 Long 会让 Redis INCR 失败）",
                initial instanceof Integer);
        assertFalse(initial instanceof Long);
        // 空上下文（无参数）不触发分桶
        assertFalse(new CodeGenContext(now, null).isBucketed());
        assertFalse(new CodeGenContext(now, new LinkedHashMap<>()).isBucketed());
        assertTrue(new CodeGenContext(now, params("PUR", "ZC")).isBucketed());
    }

    @Test
    public void 兼容_同一配置里含业务参数规则时也识别得出来() {
        assertTrue(CodeRenderSupport.hasParamRule(contractRules()));
        assertFalse(CodeRenderSupport.hasParamRule(legacyRules()));
        assertFalse(CodeRenderSupport.hasParamRule(null));
    }

    /* ==================== 其它既有规则类型不受影响 ==================== */

    @Test
    public void 日期规则支持既有全部格式串() {
        Date now = date("2026-10-05");
        String[][] cases = {
                {"yyyy", "2026"}, {"yyyyMM", "202610"}, {"yyyyMMdd", "20261005"},
                {"yyyyMMddHH", "2026100500"}, {"yyyyMMddHHmm", "2026100500"}
        };
        for (String[] c : cases) {
            List<CodeConfigRule> rules = new ArrayList<>();
            rules.add(rule("1", c[0]));
            String got = CodeRenderSupport.render(rules, 1, null, now);
            // 只断言日期部分的前缀（HH/mm 依赖当天时刻，不写死）
            assertTrue("格式 " + c[0] + " 渲染异常：" + got, got.startsWith(c[1].substring(0, Math.min(6, c[1].length()))));
        }
    }

    @Test
    public void 流水号不补零时按原值输出() {
        List<CodeConfigRule> rules = new ArrayList<>();
        CodeConfigRule r = rule("2", "6");
        r.setPadZero("0");
        rules.add(r);
        assertEquals("42", CodeRenderSupport.render(rules, 42, null, date("2026-10-05")));
    }
}
