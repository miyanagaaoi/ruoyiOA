package com.ruoyi.ctms.support;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link ContractNumberRules} 的表驱动单测（2.0 B3 任务 5.8；规格 ctms/contract-commercials 的
 * 「合同编号格式与按年重置」与「编号类型与主体取值校验」）。 </p>
 *
 * <p> 编号的<b>拼装与计数</b>由 {@code ruoyi-serial} 承担（它的回归在
 * {@code tools/serial-numbering-check.ps1} 与 {@code ruoyi-serial} 的单测里），
 * 本类只锁三件本模块自己负责的事：类型码/主体码取值校验、编号格式校验、编号各段解析 ——
 * 这三件都直接决定"停用占号不复用"和"迁移导入的手工编号能不能被识破"。 </p>
 *
 * @author 二开
 */
public class ContractNumberRulesTest
{
    /** 启用且可参与自动编号的类型码（与 contract_types 字典的 dict_value 一致） */
    private static final List<String> TYPE_CODES =
            Arrays.asList("SAL", "PUR", "COO", "LAB", "FIN", "NDA");

    /** 已配置的主体码（与 subjects 字典的 dict_value 一致） */
    private static final List<String> SUBJECT_CODES = Arrays.asList("ZC", "YX");

    /* ==================== ① 类型码 ==================== */

    @Test
    public void 类型码必须是启用且可映射为三位大写字母()
    {
        assertEquals("PUR", ContractNumberRules.checkTypeForAutoNumber("PUR", TYPE_CODES));
        assertEquals("两侧空白被归一", "SAL", ContractNumberRules.checkTypeForAutoNumber("  SAL  ", TYPE_CODES));

        // 未启用/历史类型（OTH 在字典里 status='1'，不会出现在允许集合里）
        assertRejected(ContractNumberRules.MSG_TYPE_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkTypeForAutoNumber("OTH", TYPE_CODES);
            }
        });
        // 类型为空
        assertRejected(ContractNumberRules.MSG_TYPE_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkTypeForAutoNumber(null, TYPE_CODES);
            }
        });
        // 不是 3 位大写（中文类型名、小写、长度不对）—— 规格要求「可映射为 3 位大写字母」
        assertRejected(ContractNumberRules.MSG_TYPE_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkTypeForAutoNumber("采购", TYPE_CODES);
            }
        });
        assertRejected(ContractNumberRules.MSG_TYPE_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkTypeForAutoNumber("pur", TYPE_CODES);
            }
        });
        assertRejected(ContractNumberRules.MSG_TYPE_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkTypeForAutoNumber("PURC", TYPE_CODES);
            }
        });
    }

    /* ==================== ② 主体码 ==================== */

    @Test
    public void 主体码必须是配置清单内的两位大写字母()
    {
        assertEquals("ZC", ContractNumberRules.checkSubjectForAutoNumber("ZC", SUBJECT_CODES));
        assertEquals("YX", ContractNumberRules.checkSubjectForAutoNumber(" YX ", SUBJECT_CODES));

        assertRejected(ContractNumberRules.MSG_SUBJECT_INVALID, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkSubjectForAutoNumber(null, SUBJECT_CODES);
            }
        });
        assertRejected(ContractNumberRules.MSG_SUBJECT_INVALID, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkSubjectForAutoNumber("", SUBJECT_CODES);
            }
        });
        // 不在清单内
        assertRejected(ContractNumberRules.MSG_SUBJECT_INVALID, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkSubjectForAutoNumber("ZZ", SUBJECT_CODES);
            }
        });
        // 中文主体名（字典的 dict_label 不是码）
        assertRejected(ContractNumberRules.MSG_SUBJECT_INVALID, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkSubjectForAutoNumber("智澈公司", SUBJECT_CODES);
            }
        });
    }

    /* ==================== ③ 编号格式 ==================== */

    @Test
    public void 编号格式为三段码加年月再加六位序号()
    {
        assertTrue(ContractNumberRules.isValidNo("PURZC202506000001"));
        assertTrue("示例编号长度必须是 17（3+2+4+2+6）",
                "PURZC202506000001".length() == ContractNumberRules.NO_LENGTH);
        assertTrue(ContractNumberRules.isValidNo("SALYX202701000001"));

        assertFalse("序号不足 6 位", ContractNumberRules.isValidNo("PURZC2025060001"));
        assertFalse("序号超过 6 位", ContractNumberRules.isValidNo("PURZC2025060000001"));
        assertFalse("类型码小写", ContractNumberRules.isValidNo("purZC202506000001"));
        assertFalse("主体码中文", ContractNumberRules.isValidNo("PUR智202506000001"));
        assertFalse("年月不是数字", ContractNumberRules.isValidNo("PURZCyyyyMM000001"));
        assertFalse("带分隔符", ContractNumberRules.isValidNo("PUR-ZC-2025-06-000001"));
        assertFalse("空值", ContractNumberRules.isValidNo(null));
        assertFalse("空白", ContractNumberRules.isValidNo("   "));

        assertEquals("PURZC202506000001", ContractNumberRules.checkNoFormat(" PURZC202506000001 "));
        assertRejected(ContractNumberRules.MSG_NO_FORMAT, new Runnable()
        {
            @Override
            public void run()
            {
                ContractNumberRules.checkNoFormat("CTMCP123456");
            }
        });
    }

    /* ==================== ④ 各段解析与占号扫描 ==================== */

    @Test
    public void 编号各段解析用于占号扫描()
    {
        // 把年/月从编号里拆出来，是为了证明"月份码不参与序号、年份参与序号"这两条口径
        assertEquals("2025", ContractNumberRules.yearOf("PURZC202506000001"));
        assertEquals("06", ContractNumberRules.monthOf("PURZC202506000001"));
        assertEquals(1, ContractNumberRules.seqOf("PURZC202506000001"));
        assertEquals("PURZC2025", ContractNumberRules.prefixOf("PURZC202506000001"));

        // 月份不同、前缀相同 → 同一序号桶（这正是"跨月连续、不按月重置"的落点）
        assertEquals(ContractNumberRules.prefixOf("PURZC202609000003"),
                ContractNumberRules.prefixOf("PURZC202610000004"));
        assertEquals(3, ContractNumberRules.seqOf("PURZC202609000003"));
        assertEquals(4, ContractNumberRules.seqOf("PURZC202610000004"));

        // 年份不同 → 不同桶（跨年重置）
        assertFalse(ContractNumberRules.prefixOf("PURZC202612000007")
                .equals(ContractNumberRules.prefixOf("PURZC202701000001")));

        // 格式不合法时各段解析返回"空"，而不是抛异常或返回 0（0 会被误读成"最小序号"）
        assertNull(ContractNumberRules.yearOf("BAD-NO"));
        assertNull(ContractNumberRules.monthOf("BAD-NO"));
        assertNull(ContractNumberRules.prefixOf("BAD-NO"));
        assertEquals(-1, ContractNumberRules.seqOf("BAD-NO"));
    }

    @Test
    public void 占号扫描只挑出同桶编号且忽略非法格式()
    {
        List<String> occupied = Arrays.asList(
                "PURZC202609000001",   // 同桶
                "PURZC202610000002",   // 同桶（月份不同但年份/类型/主体相同）→ 跨月连续
                "PURZC202701000001",   // 换年 → 不同桶
                "PURYX202609000001",   // 换主体 → 不同桶
                "SALZC202609000001",   // 换类型 → 不同桶
                "BAD-NO");             // 非法格式 → 必须被忽略，不能让整次扫描抛异常

        List<String> same = ContractNumberRules.sameBucket(occupied,
                ContractNumberRules.prefixOf("PURZC202609000001"));
        assertEquals("只保留同类型码 + 主体码 + 年份的编号", 2, same.size());
        assertTrue(same.contains("PURZC202609000001"));
        assertTrue("同前缀下按序号续号：PURZC202610000002 与 PURZC202609000001 同桶",
                same.contains("PURZC202610000002"));

        assertTrue("前缀为空时不做任何筛选（防御性：不返回 null）",
                ContractNumberRules.sameBucket(occupied, null).isEmpty());
        assertTrue("编号集合为空时返回空集合",
                ContractNumberRules.sameBucket(null, "PURZC2025").isEmpty());
    }

    /* ==================== 小工具 ==================== */

    /**
     * 断言某段逻辑被拒绝并给出指定文案。
     *
     * @param expectedMessage 期望文案片段
     * @param runnable        待执行逻辑
     */
    private static void assertRejected(String expectedMessage, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒绝并提示：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            String message = e.getMessage();
            assertTrue("提示应包含「" + expectedMessage + "」，实际为「" + message + "」",
                    message != null && message.contains(expectedMessage));
        }
    }

    /**
     * <p> t31 / R2-② 的纯函数：<b>同桶最大序号</b>（取号兜底的水位）与<b>按模板重拼编号</b>。 </p>
     *
     * <p> 这两条是"计数器落后于库内桶时从库内 max 起步"的全部逻辑；
     * ⚠ 重拼必须带**月份**（{@code prefixOf} 只到年份，9 位 + 6 位序号只有 15 位、格式非法）。 </p>
     */
    @Test
    public void 同桶最大序号与按模板重拼编号()
    {
        List<String> nos = Arrays.asList("PURZC202609000001", "PURZC202609000500", "PURZC202610000003",
                "PURYX202609000900", "PURZC202609999999", "BAD", null);
        assertEquals("同桶（类型码 + 主体码 + 年份）最大序号", 999999,
                ContractNumberRules.maxSeqOf(nos, "PURZC2026"));
        assertEquals("其它桶互不干扰", 900, ContractNumberRules.maxSeqOf(nos, "PURYX2026"));
        assertEquals("没有同桶编号时返回 0", 0, ContractNumberRules.maxSeqOf(nos, "SALZC2026"));
        assertEquals("前缀为空返回 0", 0, ContractNumberRules.maxSeqOf(nos, null));
        assertEquals("编号集合为空返回 0", 0, ContractNumberRules.maxSeqOf(Arrays.asList(), "PURZC2026"));

        assertEquals("按模板头部（含月份）重拼：9 位前缀 + 2 位月份 + 6 位序号",
                "PURZC202609470814", ContractNumberRules.buildNo("PURZC202609000001", 470814));
        assertEquals("月份取模板的月份，不写死", "PURZC202610000002",
                ContractNumberRules.buildNo("PURZC202610000001", 2));
        assertTrue("重拼结果必须过同一套格式校验",
                ContractNumberRules.isValidNo(ContractNumberRules.buildNo("PURZC202609000001", 999999)));
        assertNull("序号超 6 位 → null（交给取号失败路径）",
                ContractNumberRules.buildNo("PURZC202609000001", 1000000));
        assertNull("序号为 0 → null", ContractNumberRules.buildNo("PURZC202609000001", 0));
        assertNull("模板格式非法 → null", ContractNumberRules.buildNo("BAD", 1));
        assertNull("模板为空 → null", ContractNumberRules.buildNo(null, 1));
    }

    /** 取号失败文案是接口契约：脚本/用例按关键字断言，改文案必须同步改用例。 */
    @Test
    public void 取号失败文案含可断言关键字()
    {
        assertTrue("必须含「取号失败」：" + ContractNumberRules.MSG_NO_EXHAUSTED,
                ContractNumberRules.MSG_NO_EXHAUSTED.contains("取号失败"));
        assertTrue("必须含「重试上限」：" + ContractNumberRules.MSG_NO_EXHAUSTED,
                ContractNumberRules.MSG_NO_EXHAUSTED.contains("重试上限"));
    }
}