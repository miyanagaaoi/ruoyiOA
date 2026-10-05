package com.ruoyi.ctms.erp.base;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 单据取号入口单测（2.0 B4 任务 1.3）。 </p>
 *
 * <p> <b>不依赖 Spring / Redis / 数据库</b>：{@link ICodeGenService} 用内存桩，
 * 桩<b>按 configId + ym 分桶计数</b>并渲染 {@code 前缀 + ym + 6 位序号}（与被测的
 * SQL 配置同构），于是"连续两次取号不同""跨月从 000001 重新开始""不同单据各自计数"
 * 这三条能被真实断言。 </p>
 *
 * <p> 同时锁死 8 类单据的<b>配置 id 与前缀映射</b>（与 {@code sql/二开-进销存.sql} 的
 * 8 行 {@code t_code_config} 逐字对应）——映射漂移时本类会红。 </p>
 *
 * @author 二开
 */
public class ErpDocNoGeneratorTest
{
    /** 与被测 SQL 配置同构的内存桩取号服务。 */
    private static class StubCodeGenService implements ICodeGenService
    {
        private final Map<String, Integer> counters = new LinkedHashMap<>();

        private final Map<String, String> prefixes = new LinkedHashMap<>();

        private String lastConfId;

        private CodeGenContext lastContext;

        StubCodeGenService()
        {
            prefixes.put(ErpDocNoGenerator.CONF_ID_PURCHASE_REQUEST, "PR");
            prefixes.put(ErpDocNoGenerator.CONF_ID_PURCHASE_ORDER, "PO");
            prefixes.put(ErpDocNoGenerator.CONF_ID_SALES_REQUEST, "SR");
            prefixes.put(ErpDocNoGenerator.CONF_ID_SALES_ORDER, "SO");
            prefixes.put(ErpDocNoGenerator.CONF_ID_STOCK_IN, "IN");
            prefixes.put(ErpDocNoGenerator.CONF_ID_STOCK_OUT, "OUT");
            prefixes.put(ErpDocNoGenerator.CONF_ID_STOCK_TAKE, "ST");
            prefixes.put(ErpDocNoGenerator.CONF_ID_STOCK_TRANSFER, "DB");
        }

        @Override
        public String getNextCode(String confId)
        {
            return next(confId, null);
        }

        @Override
        public String getNextCode(String confId, CodeGenContext context)
        {
            return next(confId, context);
        }

        @Override
        public String previewNextCode(String confId, CodeGenContext context)
        {
            this.lastConfId = confId;
            this.lastContext = context;
            return render(confId, context, current(confId, context) + 1);
        }

        private String next(String confId, CodeGenContext context)
        {
            this.lastConfId = confId;
            this.lastContext = context;
            int seq = current(confId, context) + 1;
            counters.put(bucket(confId, context), Integer.valueOf(seq));
            return render(confId, context, seq);
        }

        private int current(String confId, CodeGenContext context)
        {
            Integer value = counters.get(bucket(confId, context));
            return value == null ? 0 : value.intValue();
        }

        /** 计数器分桶键：configId + ym（与平台"业务参数分桶"同构 → 自然按月重置）。 */
        private String bucket(String confId, CodeGenContext context)
        {
            String ym = context == null ? "" : String.valueOf(context.getParams().get(ErpDocNoGenerator.PARAM_YM));
            return confId + "|" + ym;
        }

        private String render(String confId, CodeGenContext context, int seq)
        {
            String prefix = prefixes.get(confId);
            if (prefix == null)
            {
                throw new IllegalStateException("此类型未配置编号规则！");
            }
            String ym = String.valueOf(context.getParams().get(ErpDocNoGenerator.PARAM_YM));
            return prefix + ym + String.format("%06d", Integer.valueOf(seq));
        }
    }

    private static Date date(String text)
    {
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd").parse(text);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void 八类单据的配置id与前缀被冻结()
    {
        assertEquals("9F2C0000000000000000000000C101", ErpDocNoGenerator.confIdOf(ErpDocType.PURCHASE_REQUEST));
        assertEquals("9F2C0000000000000000000000C102", ErpDocNoGenerator.confIdOf(ErpDocType.PURCHASE_ORDER));
        assertEquals("9F2C0000000000000000000000C103", ErpDocNoGenerator.confIdOf(ErpDocType.SALES_REQUEST));
        assertEquals("9F2C0000000000000000000000C104", ErpDocNoGenerator.confIdOf(ErpDocType.SALES_ORDER));
        assertEquals("9F2C0000000000000000000000C105", ErpDocNoGenerator.confIdOf(ErpDocType.STOCK_IN));
        assertEquals("9F2C0000000000000000000000C106", ErpDocNoGenerator.confIdOf(ErpDocType.STOCK_OUT));
        assertEquals("9F2C0000000000000000000000C107", ErpDocNoGenerator.confIdOf(ErpDocType.STOCK_TAKE));
        assertEquals("9F2C0000000000000000000000C108", ErpDocNoGenerator.confIdOf(ErpDocType.STOCK_TRANSFER));

        assertEquals("PR", ErpDocNoGenerator.prefixOf(ErpDocType.PURCHASE_REQUEST));
        assertEquals("PO", ErpDocNoGenerator.prefixOf(ErpDocType.PURCHASE_ORDER));
        assertEquals("SR", ErpDocNoGenerator.prefixOf(ErpDocType.SALES_REQUEST));
        assertEquals("SO", ErpDocNoGenerator.prefixOf(ErpDocType.SALES_ORDER));
        assertEquals("IN", ErpDocNoGenerator.prefixOf(ErpDocType.STOCK_IN));
        assertEquals("OUT", ErpDocNoGenerator.prefixOf(ErpDocType.STOCK_OUT));
        assertEquals("ST", ErpDocNoGenerator.prefixOf(ErpDocType.STOCK_TAKE));
        assertEquals("DB", ErpDocNoGenerator.prefixOf(ErpDocType.STOCK_TRANSFER));

        // 每个枚举都必须有映射（防止将来加单据类型时漏配），且 8 个配置 id / 前缀互不相同
        java.util.Set<String> confIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> prefixes = new java.util.LinkedHashSet<>();
        for (ErpDocType type : ErpDocType.values())
        {
            assertNotNull(type.getLabel(), ErpDocNoGenerator.confIdOf(type));
            assertNotNull(type.getLabel(), ErpDocNoGenerator.prefixOf(type));
            confIds.add(ErpDocNoGenerator.confIdOf(type));
            prefixes.add(ErpDocNoGenerator.prefixOf(type));
        }
        assertEquals("8 条配置 id 必须互不相同", 8, confIds.size());
        assertEquals("8 个前缀必须互不相同", 8, prefixes.size());
    }

    @Test
    public void 编号形态为前缀加年月加六位序号()
    {
        StubCodeGenService stub = new StubCodeGenService();
        ErpDocNoGenerator generator = new ErpDocNoGenerator(stub);
        String code = generator.nextDocNo(ErpDocType.PURCHASE_REQUEST, date("2026-10-05"));
        assertEquals("PR202610000001", code);
        assertEquals("必须走 PR 的配置", ErpDocNoGenerator.CONF_ID_PURCHASE_REQUEST, stub.lastConfId);
        assertEquals("必须传 ym 业务参数（既渲染又分桶）", "202610",
                stub.lastContext.getParams().get(ErpDocNoGenerator.PARAM_YM));
        assertEquals("参考日期必须是调用方传入的单据日期", "2026-10-05",
                new SimpleDateFormat("yyyy-MM-dd").format(stub.lastContext.getReferenceDate()));
        assertTrue("分桶必须启用", stub.lastContext.isBucketed());
    }

    @Test
    public void 连续两次取号不同()
    {
        StubCodeGenService stub = new StubCodeGenService();
        ErpDocNoGenerator generator = new ErpDocNoGenerator(stub);
        String first = generator.nextDocNo(ErpDocType.STOCK_IN, date("2026-10-05"));
        String second = generator.nextDocNo(ErpDocType.STOCK_IN, date("2026-10-05"));
        assertEquals("IN202610000001", first);
        assertEquals("IN202610000002", second);
        assertNotEquals("连续两次取号必须不同", first, second);
    }

    @Test
    public void 跨月从000001重新开始且各单据各自计数()
    {
        StubCodeGenService stub = new StubCodeGenService();
        ErpDocNoGenerator generator = new ErpDocNoGenerator(stub);
        assertEquals("PR202610000001", generator.nextDocNo(ErpDocType.PURCHASE_REQUEST, date("2026-10-05")));
        assertEquals("PR202610000002", generator.nextDocNo(ErpDocType.PURCHASE_REQUEST, date("2026-10-31")));
        // 下个月第一个号必须回到 000001（惰性按月重置，不依赖定时任务）
        assertEquals("PR202611000001", generator.nextDocNo(ErpDocType.PURCHASE_REQUEST, date("2026-11-01")));
        // 同年同月的其他单据类型各自独立计数
        assertEquals("PO202610000001", generator.nextDocNo(ErpDocType.PURCHASE_ORDER, date("2026-10-05")));
        assertEquals("DB202610000001", generator.nextDocNo(ErpDocType.STOCK_TRANSFER, date("2026-10-05")));
        assertEquals("ST202610000001", generator.nextDocNo(ErpDocType.STOCK_TAKE, date("2026-10-05")));
    }

    @Test
    public void 不传参考日期时按当天()
    {
        StubCodeGenService stub = new StubCodeGenService();
        ErpDocNoGenerator generator = new ErpDocNoGenerator(stub);
        String code = generator.nextDocNo(ErpDocType.SALES_ORDER);
        String todayYm = ErpDocNoGenerator.ymOf(new Date());
        assertEquals("SO" + todayYm + "000001", code);
    }

    @Test
    public void 预览不占号()
    {
        StubCodeGenService stub = new StubCodeGenService();
        ErpDocNoGenerator generator = new ErpDocNoGenerator(stub);
        String preview1 = generator.previewDocNo(ErpDocType.STOCK_OUT, date("2026-10-05"));
        String preview2 = generator.previewDocNo(ErpDocType.STOCK_OUT, date("2026-10-05"));
        assertEquals("OUT202610000001", preview1);
        assertEquals("连续两次预览必须相同（不占号）", preview1, preview2);
        assertEquals("预览之后真正取号仍拿 000001", "OUT202610000001",
                generator.nextDocNo(ErpDocType.STOCK_OUT, date("2026-10-05")));
    }

    @Test
    public void 单据类型为空或编号服务缺失时明确报错()
    {
        final ErpDocNoGenerator generator = new ErpDocNoGenerator(new StubCodeGenService());
        try
        {
            generator.nextDocNo(null);
            fail("单据类型为空必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("单据类型不能为空"));
        }

        ErpDocNoGenerator withoutService = new ErpDocNoGenerator(null);
        try
        {
            withoutService.nextDocNo(ErpDocType.PURCHASE_REQUEST);
            fail("编号服务缺失必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案要点名单据与原因：" + e.getMessage(),
                    e.getMessage().contains("编号服务未装配") && e.getMessage().contains("采购申请单"));
        }
    }

    @Test
    public void 取号失败时把单据与配置带进文案()
    {
        // 桩对未知配置抛异常（模拟"此类型未配置编号规则！"）
        ICodeGenService failing = new ICodeGenService()
        {
            @Override
            public String getNextCode(String confId)
            {
                throw new IllegalStateException("此类型未配置编号规则！");
            }

            @Override
            public String getNextCode(String confId, CodeGenContext context)
            {
                throw new IllegalStateException("此类型未配置编号规则！");
            }

            @Override
            public String previewNextCode(String confId, CodeGenContext context)
            {
                throw new IllegalStateException("此类型未配置编号规则！");
            }
        };
        try
        {
            new ErpDocNoGenerator(failing).nextDocNo(ErpDocType.STOCK_TAKE, date("2026-10-05"));
            fail("取号失败必须抛出业务异常");
        }
        catch (ServiceException e)
        {
            assertTrue("文案要点名单据：" + e.getMessage(), e.getMessage().contains("盘点单取号失败"));
            assertTrue("文案要带配置 id：" + e.getMessage(),
                    e.getMessage().contains(ErpDocNoGenerator.CONF_ID_STOCK_TAKE));
            assertTrue("文案要带年月：" + e.getMessage(), e.getMessage().contains("202610"));
        }
    }

    @Test
    public void 年月格式化口径()
    {
        assertEquals("202610", ErpDocNoGenerator.ymOf(date("2026-10-05")));
        assertEquals("202601", ErpDocNoGenerator.ymOf(date("2026-01-01")));
        assertEquals("{前缀}{yyyyMM}{6 位序号}", ErpDocNoGenerator.FORMAT_PATTERN);
    }

    /**
     * <p> <b>跨模块交叉验证</b>：把 {@code sql/二开-进销存.sql} ③ 段那 3 条规则
     * （固定值 PR / 业务参数 ym / 6 位按月序号）原样喂给<b>平台</b>的渲染器
     * （{@code CodeRenderSupport}，ruoyi-serial 的真实实现），断言： </p>
     * <ol>
     *   <li> 渲染结果正是 {@code PR202610000001}（格式与 SQL 配置一致）； </li>
     *   <li> 计数器分桶键**按月变化**（这是"按月重置"的真实实现方式）。 </li>
     * </ol>
     *
     * <p> 这样"SQL 配置"与"平台实现"之间不再是靠文档约定，而是被断言锁住；
     * Redis/DB 不参与，因此可以在单测里跑。 </p>
     */
    @Test
    public void 平台渲染器对同一配置产出前缀加年月加六位序号()
    {
        java.util.List<com.ruoyi.serial.domain.CodeConfigRule> rules = new java.util.ArrayList<>();
        rules.add(rule("0", "PR", "0", "0", 1));
        rules.add(rule("3", ErpDocNoGenerator.PARAM_YM, "0", "0", 2));
        rules.add(rule("2", "6", "1", "3", 3));

        Map<String, String> params = new LinkedHashMap<>();
        params.put(ErpDocNoGenerator.PARAM_YM, "202610");
        CodeGenContext october = new CodeGenContext(date("2026-10-05"), params);
        assertEquals("PR202610000001", com.ruoyi.serial.support.CodeRenderSupport.render(
                rules, 1L, october, date("2026-10-05")));

        Map<String, String> nextParams = new LinkedHashMap<>();
        nextParams.put(ErpDocNoGenerator.PARAM_YM, "202611");
        CodeGenContext november = new CodeGenContext(date("2026-11-01"), nextParams);
        assertEquals("PR202611000001", com.ruoyi.serial.support.CodeRenderSupport.render(
                rules, 1L, november, date("2026-11-01")));

        String keyOctober = com.ruoyi.serial.support.CodeRenderSupport.bucketKeyOf(
                ErpDocNoGenerator.CONF_ID_PURCHASE_REQUEST, rules, october);
        String keyNovember = com.ruoyi.serial.support.CodeRenderSupport.bucketKeyOf(
                ErpDocNoGenerator.CONF_ID_PURCHASE_REQUEST, rules, november);
        assertNotEquals("跨月必须换计数器桶（=惰性按月重置）", keyOctober, keyNovember);
        assertTrue("桶键必须含配置 id：" + keyOctober,
                keyOctober.startsWith("code:gen:seq:" + ErpDocNoGenerator.CONF_ID_PURCHASE_REQUEST));
        assertTrue("桶键必须含 ym 参数：" + keyOctober, keyOctober.contains("ym=202610"));
        assertTrue("分桶路径的计数器初值必须为 0（新月份从 000001 起）",
                com.ruoyi.serial.support.CodeRenderSupport.initialSeqOf(Integer.valueOf(0), october) == 0);
    }

    private static com.ruoyi.serial.domain.CodeConfigRule rule(String ruleType, String ruleValue,
                                                              String padZero, String seqResetType, int sort)
    {
        com.ruoyi.serial.domain.CodeConfigRule rule = new com.ruoyi.serial.domain.CodeConfigRule();
        rule.setRuleType(ruleType);
        rule.setRuleValue(ruleValue);
        rule.setPadZero(padZero);
        rule.setSeqResetType(seqResetType);
        rule.setSort(Integer.valueOf(sort));
        return rule;
    }
}
