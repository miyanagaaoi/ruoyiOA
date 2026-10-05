package com.ruoyi.ctms.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> {@link MigrationRules} 的纯规则单测（2.0 B3 任务 7.1~7.2）。 </p>
 *
 * <p> 覆盖五件可被独立断言的事：方向映射（只认 PUR/SAL）、某方向的文本与档案引用取自哪一列、
 * 原始文本归一、分组键与唯一索引同口径（大小写/重音不敏感）、草案状态机与认领守卫。 </p>
 *
 * @author 二开
 */
public class MigrationRulesTest
{
    @Test
    public void 只有采购与销售参与方向映射()
    {
        assertEquals(MigrationRules.DIRECTION_SUPPLIER, MigrationRules.directionOf("PUR"));
        assertEquals(MigrationRules.DIRECTION_CUSTOMER, MigrationRules.directionOf("SAL"));
        assertEquals("两侧空白要归一后再判", MigrationRules.DIRECTION_SUPPLIER, MigrationRules.directionOf(" PUR "));
        for (String skipped : Arrays.asList("COO", "LAB", "FIN", "NDA", "OTH", "其他", "pur", "SALX", ""))
        {
            assertNull("类型「" + skipped + "」不参与映射（宁可不扫，不猜方向）",
                    MigrationRules.directionOf(skipped));
            assertFalse(MigrationRules.participates(skipped));
        }
        assertNull("类型为空不参与映射", MigrationRules.directionOf(null));
        assertTrue(MigrationRules.participates("SAL"));
    }

    @Test
    public void 原始文本与档案引用按方向取对应列()
    {
        assertEquals("供应商方向取乙方文本", "乙方",
                MigrationRules.rawTextOf(MigrationRules.DIRECTION_SUPPLIER, "甲方", "乙方"));
        assertEquals("客户方向取甲方文本", "甲方",
                MigrationRules.rawTextOf(MigrationRules.DIRECTION_CUSTOMER, "甲方", "乙方"));
        assertNull("方向未知时不取任何文本", MigrationRules.rawTextOf("other", "甲方", "乙方"));
        assertNull("不参与映射的类型没有方向，也就没有文本",
                MigrationRules.rawTextOf(MigrationRules.directionOf("COO"), "甲方", "乙方"));

        assertEquals("供应商方向看 supplier_id", "S-1",
                MigrationRules.referenceIdOf(MigrationRules.DIRECTION_SUPPLIER, "C-1", "S-1"));
        assertEquals("客户方向看 customer_id", "C-1",
                MigrationRules.referenceIdOf(MigrationRules.DIRECTION_CUSTOMER, "C-1", "S-1"));
        assertNull("该方向未绑定档案", MigrationRules.referenceIdOf(MigrationRules.DIRECTION_SUPPLIER, "C-1", " "));
        assertNull("方向未知时不给引用", MigrationRules.referenceIdOf("other", "C-1", "S-1"));
    }

    /**
     * <p> 原始文本归一：<b>只去首尾 ASCII 空格</b>，其余空白原样保留 —— 这是与认领候选 SQL
     * （{@code trim(c.party_a/party_b) = trim(#{rawName})}，MySQL 的 TRIM 只去 0x20）
     * <b>逐类同口径</b>的锁定（t26 / t16 round 1 的 F1）。 </p>
     *
     * <p> 若这里把某一类改成"Java 去、SQL 不去"（例如改回 {@code String.trim()} 让 tab 被去掉），
     * 带 tab 的合同就会"扫描聚合成草案、认领实绑 0、再扫描回 pending"永久往复。 </p>
     */
    @Test
    public void 原始文本只去首尾空格且其余空白与SQL同口径()
    {
        String tab = "\t";
        String cr = "\r";
        String lf = "\n";
        String fw = "\u3000";
        String nbsp = "\u00a0";

        // ✅ 两侧都去：ASCII 空格（前 / 后 / 双侧多空格）
        assertEquals("某某阀门有限公司", MigrationRules.normalizeRawName("  某某阀门有限公司 "));
        assertEquals("某某阀门有限公司", MigrationRules.normalizeRawName("某某阀门有限公司"));
        assertEquals("某某阀门有限公司", MigrationRules.normalizeRawName(" 某某阀门有限公司"));

        // ✅ 两侧都留：Tab / CR / LF / 全角空格 / NBSP（MySQL TRIM 也不去它们，实测见 t26 交付记录）
        assertEquals("Tab 原样保留（Java 与 SQL 都不去）",
                tab + "某某阀门有限公司" + tab, MigrationRules.normalizeRawName(" " + tab + "某某阀门有限公司" + tab + " "));
        assertEquals("CR/LF 原样保留", cr + lf + "某某阀门有限公司" + cr + lf,
                MigrationRules.normalizeRawName(cr + lf + "某某阀门有限公司" + cr + lf));
        assertEquals("全角空格原样保留（Java trim 与 MySQL TRIM 都不处理 U+3000）",
                fw + "某某阀门有限公司" + fw, MigrationRules.normalizeRawName(fw + "某某阀门有限公司" + fw));
        assertEquals("NBSP 原样保留", nbsp + "某某阀门有限公司" + nbsp,
                MigrationRules.normalizeRawName(nbsp + "某某阀门有限公司" + nbsp));

        // 全空白（含制表/换行）仍视为"没有文本"：不建草案
        assertNull("全空白归一为 null（该方向视为没有文本）", MigrationRules.normalizeRawName("   "));
        assertNull("全 tab 也视为没有文本", MigrationRules.normalizeRawName(tab + tab + tab));
        assertNull(MigrationRules.normalizeRawName(null));

        // trimSpaces 单独锁一遍（它是与 SQL 同口径的那一半）
        assertEquals("", MigrationRules.trimSpaces("    "));
        assertEquals("x", MigrationRules.trimSpaces("  x  "));
        assertEquals(tab + "x" + tab, MigrationRules.trimSpaces(tab + "x" + tab));
        assertNull(MigrationRules.trimSpaces(null));

        // 不做长度截断：源列与目标列同为 varchar(255)，截断会把两个长名称静默合并（DEV-ENV §6.39 的反向应用）
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++)
        {
            longName.append('A');
        }
        assertEquals("不截断，长度原样保留", longName.toString(), MigrationRules.normalizeRawName(longName.toString()));
    }

    @Test
    public void 分组键与唯一索引同口径()
    {
        assertEquals("大小写不同 = 同一个键（utf8mb4_0900_ai_ci）",
                MigrationRules.rawNameKey("Acme Valve Co"), MigrationRules.rawNameKey("acme valve co"));
        assertEquals("重音不同 = 同一个键（ai = accent insensitive）",
                MigrationRules.rawNameKey("Societe Generale"), MigrationRules.rawNameKey("Société Générale"));
        assertFalse("不同文本必须是不同的键",
                MigrationRules.rawNameKey("X阀门").equals(MigrationRules.rawNameKey("Y阀门")));
        // ⚠ 已知边界（t26 实测登记，两侧**在空白维度不同口径**）：
        //    · Java Collator.PRIMARY：把空白当"可忽略元素"（variable weighting）→ Tab/空格前缀的键相同；
        //    · MySQL utf8mb4_0900_ai_ci：空白**不可忽略**（实测 'x' = '\tx' → 0、'x' = ' x' → 0）。
        //    这里把"Java 侧更松"这一事实锁死（不是在认可它），免得后人以为两侧键完全同口径。
        //    影响面与规避见 notes/migration-notes.md 的「已知边界」一节：
        //    它只影响"同一文本的空白变体能否在**一轮**认领里绑全"，不会出现实绑 0 或永久往复
        //    —— 草案的代表文本本身必定匹配（`trim(本方向文本) = trim(raw_name)` 对它是恒等式）。
        assertEquals("已知边界：Java 键在空白维度比 ai_ci 松（Tab 前缀同键）",
                MigrationRules.rawNameKey("X阀门"), MigrationRules.rawNameKey("\tX阀门"));
        assertEquals("已知边界：空格前缀同理（raw_name 已去首尾空格，故这条只在中间/前缀空白上体现）",
                MigrationRules.rawNameKey("X阀门"), MigrationRules.rawNameKey("\u3000X阀门"));
        assertEquals("null 归一为空键（调用方在此之前已用 normalizeRawName 挡掉）",
                "", MigrationRules.rawNameKey(null));
    }

    @Test
    public void 状态枚举是三个已知取值()
    {
        List<String> statuses = MigrationRules.statuses();
        assertEquals(3, statuses.size());
        assertTrue(statuses.contains(MigrationRules.STATUS_PENDING));
        assertTrue(statuses.contains(MigrationRules.STATUS_CLAIMED));
        assertTrue(statuses.contains(MigrationRules.STATUS_IGNORED));
        assertTrue(MigrationRules.isKnownStatus("pending"));
        assertTrue(MigrationRules.isKnownStatus(" claimed "));
        assertFalse(MigrationRules.isKnownStatus("ignored_x"));
        assertFalse(MigrationRules.isKnownStatus(null));
    }

    @Test
    public void 认领状态机拒绝已认领并放行已忽略()
    {
        assertTrue("待认领可认领", MigrationRules.claimable(MigrationRules.STATUS_PENDING));
        assertTrue("忽略是暂缓不是否决：允许重新认领", MigrationRules.claimable(MigrationRules.STATUS_IGNORED));
        assertFalse("已认领不可重复认领", MigrationRules.claimable(MigrationRules.STATUS_CLAIMED));
        assertFalse("状态机之外的取值不可认领", MigrationRules.claimable("done"));
        assertFalse("状态为空不可认领", MigrationRules.claimable(null));
        // 守卫：合法状态静默通过
        MigrationRules.checkClaimable(MigrationRules.STATUS_PENDING);
        MigrationRules.checkClaimable(MigrationRules.STATUS_IGNORED);
        assertRejected(MigrationRules.MSG_ALREADY_CLAIMED, MigrationRules.STATUS_CLAIMED);
        assertRejected(MigrationRules.MSG_STATUS_INVALID, "done");
        assertTrue("已认领的提示必须含关键字「已认领」（7.3 的验收契约）",
                MigrationRules.MSG_ALREADY_CLAIMED.contains("已认领"));
    }

    /**
     * 断言某状态被认领守卫拒绝并给出指定文案。
     *
     * @param expectedMessage 期望文案
     * @param status          草案状态
     */
    private static void assertRejected(String expectedMessage, final String status)
    {
        try
        {
            MigrationRules.checkClaimable(status);
            org.junit.Assert.fail("应当被拒绝并提示：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            assertTrue("提示应包含「" + expectedMessage + "」，实际为「" + e.getMessage() + "」",
                    e.getMessage() != null && e.getMessage().contains(expectedMessage));
        }
    }
}
