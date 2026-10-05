package com.ruoyi.ctms.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsContractItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 合同台账纯规则的表驱动单测（规格 ctms/contract-commercials 与 ctms/contract-ledger）。 </p>
 *
 * <p> 为什么必须是纯单测：本类不读库、不起 Spring、不用 Redis，所以这些口径可以<b>逐字</b>锁定 ——
 * 包括提示文案、scale 与边界日。金额口径（先舍入再汇总）与质保到期算法都是「写错不会报错、
 * 数据却会错」的类型，只能靠边界用例守住。 </p>
 *
 * <p> 用例覆盖（对应交付清单 ①~⑨）：质保到期 6 条场景、3 行 1.665 的 5.01 判别、
 * 已舍入行总价之和、恢复窗口 30/31 天边界、付款比例、质保金额与比例互换、
 * 摘要与名称/规格回落、状态文案、行总价 null 兜底。 </p>
 *
 * @author 二开
 */
public class ContractRulesTest
{
    /* ==================== 夹具 ==================== */

    /** 解析 yyyy-MM-dd（严格模式，避免 2 月 30 日被静默纠偏） */
    private static Date day(String text)
    {
        return parse(text, "yyyy-MM-dd");
    }

    /** 解析 yyyy-MM-dd HH:mm（恢复窗口要验「只看日期不看时刻」） */
    private static Date moment(String text)
    {
        return parse(text, "yyyy-MM-dd HH:mm");
    }

    private static Date parse(String text, String pattern)
    {
        SimpleDateFormat format = new SimpleDateFormat(pattern);
        format.setLenient(false);
        try
        {
            return format.parse(text);
        }
        catch (ParseException e)
        {
            throw new IllegalArgumentException("夹具日期不合法：" + text + "（" + pattern + "）", e);
        }
    }

    /** 输出 yyyy-MM-dd，便于边界日断言 */
    private static String dayText(Date date)
    {
        return new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    /** 构造一行行项 */
    private static CtmsContractItem item(String name, String spec, String qty, String productName)
    {
        CtmsContractItem line = new CtmsContractItem();
        line.setName(name);
        line.setSpec(spec);
        line.setQty(new BigDecimal(qty));
        line.setProductName(productName);
        return line;
    }

    /** 断言动作被拒，且提示文案逐字一致 */
    private static void assertRejected(final String expectedMessage, Runnable action)
    {
        try
        {
            action.run();
            fail("本应被拒绝，提示应为：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            assertEquals("提示文案是接口契约，必须逐字一致", expectedMessage, e.getMessage());
        }
    }

    /** 断言动作放行 */
    private static void assertAccepted(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (ServiceException e)
        {
            fail("本应放行，却抛出业务异常：" + e.getMessage());
        }
    }

    /* ==================== ⓪ 常量口径 ==================== */

    @Test
    public void 常量_舍入模式与scale固定()
    {
        // 舍入模式被规格锁定为 HALF_UP，改成别的模式必须让这条用例先红
        assertEquals(RoundingMode.HALF_UP, ContractRules.MODE);
        assertEquals(2, ContractRules.SCALE_AMOUNT);
        assertEquals(3, ContractRules.SCALE_QTY);
        assertEquals(4, ContractRules.SCALE_PRICE);
        assertEquals(30, ContractRules.RESTORE_WINDOW_DAYS);
    }

    /* ==================== ① 质保到期 6 条场景 ==================== */

    @Test
    public void 质保到期_六条场景_表驱动()
    {
        // 规格 ctms/contract-commercials「质量保证金到期日算法」的 6 条场景，逐条锁定
        String[] starts = {"2025-06-01", "2026-09-01", "2026-08-01",
                           "2026-01-31", "2026-01-31", "2026-03-15"};
        int[] months = {12, 12, 5, 1, 2, 24};
        String[] expectedEnds = {"2026-05-31", "2027-08-31", "2026-12-31",
                                 "2026-01-31", "2026-02-28", "2028-02-29"};
        for (int i = 0; i < starts.length; i++)
        {
            String actual = dayText(ContractRules.computeWarrantyEnd(day(starts[i]), Integer.valueOf(months[i])));
            assertEquals("生效日 " + starts[i] + " 期限 " + months[i] + " 个月",
                    expectedEnds[i], actual);
        }
    }

    @Test
    public void 质保到期_期限小于一按一个月处理()
    {
        // 规格：期限至少 1 个月（小于 1 按 1 处理）
        assertEquals("2026-01-31", dayText(ContractRules.computeWarrantyEnd(day("2026-01-31"), Integer.valueOf(0))));
        assertEquals("2026-01-31", dayText(ContractRules.computeWarrantyEnd(day("2026-01-31"), Integer.valueOf(-5))));
        assertEquals("2026-01-31", dayText(ContractRules.computeWarrantyEnd(day("2026-01-31"), null)));
    }

    @Test
    public void 加月_日归一到目标月最大日且时刻不变()
    {
        assertEquals("2026-02-28", dayText(ContractRules.addMonths(day("2026-01-31"), 1)));
        assertEquals("2028-02-29", dayText(ContractRules.addMonths(day("2028-01-31"), 1)));
        assertEquals("2025-12-31", dayText(ContractRules.addMonths(day("2026-01-31"), -1)));
        // 时刻不变：跨月只改年月与日
        assertEquals("2026-02-28 09:30", new SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(ContractRules.addMonths(moment("2026-01-31 09:30"), 1)));
    }

    @Test
    public void 日期为空_文案逐字()
    {
        assertRejected("日期不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.addMonths(null, 1);
            }
        });
        assertRejected("日期不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.computeWarrantyEnd(null, Integer.valueOf(12));
            }
        });
        assertRejected("日期不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.daysBetween(day("2026-03-01"), null);
            }
        });
    }

    /* ==================== ② 先舍入再汇总的判别用例 ==================== */

    @Test
    public void 金额_三行一千六百六十五_必须得五点零一()
    {
        BigDecimal unitPrice = new BigDecimal("1.665");
        BigDecimal perLine = ContractRules.lineTotal(BigDecimal.ONE, unitPrice);
        // 行级先 HALF_UP 到 2 位：1.665 → 1.67
        assertEquals(new BigDecimal("1.67"), perLine);
        assertEquals(2, perLine.scale());

        List<BigDecimal> lines = new ArrayList<BigDecimal>();
        lines.add(perLine);
        lines.add(perLine);
        lines.add(perLine);
        assertEquals("合同金额 = 1.67 + 1.67 + 1.67", new BigDecimal("5.01"), ContractRules.sumLineTotals(lines));

        // 反例（错误实现）：先汇总再一次性舍入会得到 5.00 —— 这就是「先舍入再汇总」的判别点
        BigDecimal wrong = unitPrice.multiply(new BigDecimal("3")).setScale(ContractRules.SCALE_AMOUNT,
                ContractRules.MODE);
        assertEquals(new BigDecimal("5.00"), wrong);
        assertFalse("两种口径必须能区分开，否则本用例没有判别力", wrong.equals(ContractRules.sumLineTotals(lines)));
    }

    @Test
    public void 金额_数量与单价精度不丢()
    {
        BigDecimal total = ContractRules.lineTotal(new BigDecimal("3.333"), new BigDecimal("0.03"));
        // 3.333 × 0.03 = 0.09999 → HALF_UP 到 2 位 = 0.10
        assertEquals(new BigDecimal("0.10"), total);
        assertEquals(2, total.scale());
    }

    /* ==================== ③ 求和用「已舍入值之和」 ==================== */

    @Test
    public void 求和_已舍入行总价之和()
    {
        List<BigDecimal> lines = new ArrayList<BigDecimal>();
        lines.add(new BigDecimal("5.01"));
        lines.add(new BigDecimal("5.01"));
        assertEquals(new BigDecimal("10.02"), ContractRules.sumLineTotals(lines));

        // 空列表与 null 都返回 0.00（scale 固定为 2，不是裸 0）
        assertEquals(new BigDecimal("0.00"), ContractRules.sumLineTotals(new ArrayList<BigDecimal>()));
        assertEquals(new BigDecimal("0.00"), ContractRules.sumLineTotals(null));
        assertEquals(2, ContractRules.sumLineTotals(null).scale());

        // 集合内的 null 元素按「没有这一行」跳过，不抛异常
        List<BigDecimal> withNull = new ArrayList<BigDecimal>();
        withNull.add(new BigDecimal("0.01"));
        withNull.add(null);
        withNull.add(new BigDecimal("0.02"));
        assertEquals(new BigDecimal("0.03"), ContractRules.sumLineTotals(withNull));
    }

    /* ==================== ④ 恢复窗口 30 / 31 天边界 ==================== */

    @Test
    public void 恢复窗口_三十天可恢复三十一天不可()
    {
        Date deletedAt = moment("2026-03-01 09:00");

        Date day30 = moment("2026-03-31 08:00");
        // 只看日期不看时刻：09:00 → 08:00 也是整 30 天
        assertEquals(30L, ContractRules.daysBetween(deletedAt, day30));
        assertTrue("整数日差 30 = 在窗口内", ContractRules.isWithinRestoreWindow(deletedAt, day30));

        Date day31 = moment("2026-04-01 09:00");
        assertEquals(31L, ContractRules.daysBetween(deletedAt, day31));
        assertFalse("整数日差 31 = 超出窗口", ContractRules.isWithinRestoreWindow(deletedAt, day31));
    }

    @Test
    public void 恢复窗口_停用时间缺失时不放行()
    {
        assertFalse(ContractRules.isWithinRestoreWindow(null, moment("2026-03-31 08:00")));
        assertFalse(ContractRules.isWithinRestoreWindow(moment("2026-03-01 09:00"), null));
    }

    /* ==================== ⑤ 付款比例 ==================== */

    @Test
    public void 付款比例_按累计已付除以合同金额()
    {
        // 规格场景：合同金额 200000、累计已付 50000 → 25.00%
        assertEquals(new BigDecimal("25.0000"),
                ContractRules.paidRate(new BigDecimal("50000"), new BigDecimal("200000")));
        assertEquals(4, ContractRules.paidRate(new BigDecimal("50000"), new BigDecimal("200000")).scale());

        // 参数顺序判别：分子是累计已付（反过来是 400%）
        assertEquals(new BigDecimal("400.0000"),
                ContractRules.paidRate(new BigDecimal("200000"), new BigDecimal("50000")));

        // 已付超出合同金额仍可计算（保存由服务层放行，这里不拒绝）
        assertEquals(new BigDecimal("120.0000"),
                ContractRules.paidRate(new BigDecimal("1200"), new BigDecimal("1000")));
    }

    @Test
    public void 付款比例_合同金额为空或零返回空值()
    {
        assertNull(ContractRules.paidRate(BigDecimal.ZERO, BigDecimal.ZERO));
        assertNull(ContractRules.paidRate(new BigDecimal("50000"), null));
        // 累计已付为空按 0 处理，分母有效时返回 0 而不是 null
        assertEquals(new BigDecimal("0.0000"), ContractRules.paidRate(null, new BigDecimal("200000")));
    }

    /* ==================== ⑥ 质保金额与比例互换 ==================== */

    @Test
    public void 质保换算_比例补金额与金额补比例()
    {
        // 规格场景：合同金额 100000、比例 5 → 质保金额 5000.00
        BigDecimal amount = ContractRules.warrantyAmountOfRate(new BigDecimal("100000"), new BigDecimal("5"));
        assertEquals(new BigDecimal("5000.00"), amount);
        assertEquals(2, amount.scale());

        // 规格场景：合同金额 30000、质保金额 3000 → 比例 10.0000
        BigDecimal rate = ContractRules.warrantyRateOfAmount(new BigDecimal("30000"), new BigDecimal("3000"));
        assertEquals(new BigDecimal("10.0000"), rate);
        assertEquals(4, rate.scale());

        // 两者可互相还原（同 scale 往返）
        assertEquals(new BigDecimal("5.0000"),
                ContractRules.warrantyRateOfAmount(new BigDecimal("100000"),
                        ContractRules.warrantyAmountOfRate(new BigDecimal("100000"), new BigDecimal("5"))));
    }

    @Test
    public void 质保换算_合同金额为空或零被拒()
    {
        assertRejected("合同金额为空或 0，无法换算质保金额", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.warrantyAmountOfRate(BigDecimal.ZERO, new BigDecimal("5"));
            }
        });
        assertRejected("合同金额为空或 0，无法换算质保金额", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.warrantyRateOfAmount(null, new BigDecimal("3000"));
            }
        });
    }

    /* ==================== ⑦ 摘要格式与名称/规格回落 ==================== */

    @Test
    public void 摘要_格式与回落与数量去尾零()
    {
        List<CtmsContractItem> items = new ArrayList<CtmsContractItem>();
        items.add(item("球阀", "DN50", "2", "球阀"));
        items.add(item("法兰", null, "10", "法兰"));
        assertEquals("球阀(DN50)×2；法兰×10", ContractRules.subjectMatterOf(items));

        // 名称为空白 → 回落物料名称；数量 2.000 → 2（去尾零，不出现 ×2.000）
        List<CtmsContractItem> fallback = new ArrayList<CtmsContractItem>();
        fallback.add(item("  ", null, "2.000", "球阀"));
        assertEquals("球阀×2", ContractRules.subjectMatterOf(fallback));

        // 小数数量保留必要小数位
        List<CtmsContractItem> decimalQty = new ArrayList<CtmsContractItem>();
        decimalQty.add(item("球阀", "DN50", "2.500", "球阀"));
        assertEquals("球阀(DN50)×2.5", ContractRules.subjectMatterOf(decimalQty));

        // 无行项 → 空串
        assertEquals("", ContractRules.subjectMatterOf(null));
        assertEquals("", ContractRules.subjectMatterOf(new ArrayList<CtmsContractItem>()));
    }

    @Test
    public void 回落_行项名称为空用物料名称()
    {
        assertEquals("球阀", ContractRules.itemNameOf(null, "球阀"));
        assertEquals("球阀", ContractRules.itemNameOf("   ", " 球阀 "));
        assertEquals("手填名称", ContractRules.itemNameOf(" 手填名称 ", "球阀"));
        assertNull(ContractRules.itemNameOf(null, null));

        assertEquals("DN50", ContractRules.itemSpecOf("", "DN50"));
        assertEquals("DN50", ContractRules.itemSpecOf(null, " DN50 "));
        assertEquals("手填规格", ContractRules.itemSpecOf("手填规格", "DN50"));
        assertNull(ContractRules.itemSpecOf("  ", null));
    }

    /* ==================== ⑧ 状态校验文案 ==================== */

    @Test
    public void 状态校验_非法取值文案逐字()
    {
        final List<String> statuses = Arrays.asList("内部审批中", "集团审批中", "已签订", "付款中", "发货", "到货", "已终止");
        // 合法值放行
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkStatusIn("已签订", statuses);
            }
        });
        // 首尾空白不算非法（去空白后再比对）
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkStatusIn(" 已签订 ", statuses);
            }
        });
        assertRejected("无效状态：待审批", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkStatusIn("待审批", statuses);
            }
        });
        assertRejected("无效状态：null", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkStatusIn(null, statuses);
            }
        });
        assertRejected("无效状态：已签订", new Runnable()
        {
            @Override
            public void run()
            {
                // 合法集合为空（字典没配）时同样视为非法，不能静默放行
                ContractRules.checkStatusIn("已签订", new ArrayList<String>());
            }
        });
    }

    @Test
    public void 到货状态校验_非法取值文案逐字()
    {
        final List<String> arrivalStatuses = Arrays.asList("未到货", "部分到货", "已到货");
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkArrivalStatusIn("部分到货", arrivalStatuses);
            }
        });
        assertRejected("无效到货状态：已签收", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkArrivalStatusIn("已签收", arrivalStatuses);
            }
        });
        assertRejected("无效到货状态：null", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.checkArrivalStatusIn(null, arrivalStatuses);
            }
        });
    }

    /* ==================== ⑨ 行总价 null 兜底 ==================== */

    @Test
    public void 行总价_null兜底为零()
    {
        assertEquals(new BigDecimal("0.00"), ContractRules.lineTotal(null, null));
        assertEquals(new BigDecimal("0.00"), ContractRules.lineTotal(null, new BigDecimal("1.665")));
        assertEquals(new BigDecimal("0.00"), ContractRules.lineTotal(new BigDecimal("3"), null));
        assertEquals(2, ContractRules.lineTotal(null, null).scale());
        // 数量与单价都为 0 也是 0.00
        assertEquals(new BigDecimal("0.00"), ContractRules.lineTotal(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /* ==================== ⑩ 质保提醒的日期工具（任务 5.6） ==================== */

    @Test
    public void 质保提醒窗口的日期归零与加减天()
    {
        // 归一到当天零点：只看日期不看时刻（同一份合同不能因为毫秒差异被判成"已到期"）
        assertEquals("2026-05-31", dayText(ContractRules.atStartOfDay(moment("2026-05-31 23:59"))));
        assertEquals("2026-05-31", dayText(ContractRules.atStartOfDay(moment("2026-05-31 00:00"))));
        assertEquals("2026-05-31", dayText(ContractRules.atStartOfDay(moment("2026-05-31 12:00"))));
        assertNull(ContractRules.atStartOfDay(null));

        // 窗口右端点 = 今天 + 30 天（规格：窗口边界 2026-05-01 + 30 → 含 2026-05-31）
        assertEquals("2026-05-31", dayText(ContractRules.plusDays(moment("2026-05-01 09:00"), 30)));
        assertEquals("2026-01-01", dayText(ContractRules.plusDays(moment("2025-12-31 09:00"), 1)));
        assertNull(ContractRules.plusDays(null, 3));
    }

    @Test
    public void 参考日期解析与系统参数整数解析()
    {
        assertEquals("2026-09-15", dayText(ContractRules.parseDay("2026-09-15")));
        assertNull("空参考日期表示「取当天」，不报错", ContractRules.parseDay("  "));
        assertRejected("参考日期格式错误，应为 yyyy-MM-dd：2026/09/15", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.parseDay("2026/09/15");
            }
        });
        assertRejected("参考日期格式错误，应为 yyyy-MM-dd：2026-02-30", new Runnable()
        {
            @Override
            public void run()
            {
                ContractRules.parseDay("2026-02-30");
            }
        });

        assertEquals("正常整数按原值", 30, ContractRules.intValue("30", 30));
        assertEquals("空值回落默认", 30, ContractRules.intValue(null, 30));
        assertEquals("空串回落默认", 30, ContractRules.intValue("  ", 30));
        assertEquals("非数字回落默认（不让看板整体报错）", 30, ContractRules.intValue("三十天", 30));
        assertEquals("10", 10, ContractRules.intValue("10", 30));
    }

    /* ==================== 通用小工具 ==================== */

    @Test
    public void 判空与去空白()
    {
        assertTrue(ContractRules.isBlank(null));
        assertTrue(ContractRules.isBlank(""));
        assertTrue(ContractRules.isBlank("   "));
        // 全角空格也算空白（前端中文输入法很容易带进来）
        assertTrue(ContractRules.isBlank("　"));
        assertFalse(ContractRules.isBlank("0"));
        assertFalse(ContractRules.isBlank(" 已签订 "));

        assertNull(ContractRules.trimToNull(null));
        assertNull(ContractRules.trimToNull(" \t\n "));
        assertEquals("已签订", ContractRules.trimToNull("  已签订  "));
    }
}
