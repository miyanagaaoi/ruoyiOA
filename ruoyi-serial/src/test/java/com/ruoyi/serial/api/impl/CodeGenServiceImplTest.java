package com.ruoyi.serial.api.impl;

import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.serial.domain.CodeConfig;
import com.ruoyi.serial.domain.CodeConfigRule;
import com.ruoyi.serial.domain.CodeSequenceLog;
import com.ruoyi.serial.module.CodeConfigDTO;
import com.ruoyi.serial.module.CodeGenContext;
import com.ruoyi.serial.service.ICodeConfigService;
import com.ruoyi.serial.service.ICodeSequenceLogService;
import com.ruoyi.tools.lock.RedisLock;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 取号服务<b>两条路径</b>的行为断言（2.0 B3 §1.3）。 </p>
 *
 * <p> 为什么需要它（而不是只测纯函数）：真实环境里"老入口取号失败、预览却正常"这一类故障，
 * 根因往往在**取号路径本身的编排**（加锁 → 计数器初始化 → INCR → 渲染 → 回写 → 留痕），
 * 而这部分以前没有任何自动化覆盖。这里用手写桩替换 Redis/DB（{@link RedisCache} 与
 * {@link RedisLock} 都是可继承的普通类，接口用匿名实现），把整条编排跑在内存里。 </p>
 *
 * <p> 覆盖三件事： </p>
 * <ol>
 *   <li> 老入口 {@code getNextCode(confId)}：编号形态、计数器键、回写 {@code current_seq}、写流水，全部与扩展前一致； </li>
 *   <li> 新入口 {@code getNextCode(confId, ctx)}：按参考日期与业务参数产号、按桶计数、**不回写** current_seq； </li>
 *   <li> {@code previewNextCode}：不占号（不 INCR、不写流水、建键），且随后的取号正是预览值。 </li>
 * </ol>
 *
 * @author 二开
 */
public class CodeGenServiceImplTest {

    private static final String CONF_ID = "UNIT-CONF-0001";

    /* ==================== 桩 ==================== */

    /** 内存版 RedisCache：只实现取号用到的 4 个方法 */
    private static class StubRedisCache extends RedisCache {
        final Map<String, Object> store = new ConcurrentHashMap<>();
        /** 记录"计数器初值"的装箱类型 —— 它是 Redis INCR 能否工作的关键 */
        final List<Object> initialWrites = new ArrayList<>();

        @Override
        public Boolean hasKey(String key) {
            return store.containsKey(key);
        }

        @Override
        public <T> void setCacheObject(String key, T value) {
            initialWrites.add(value);
            store.put(key, value);
        }

        @Override
        public <T> T getCacheObject(String key) {
            @SuppressWarnings("unchecked")
            T v = (T) store.get(key);
            return v;
        }

        @Override
        public long getIncr(String key) {
            long next = ((Number) store.getOrDefault(key, 0L)).longValue() + 1;
            store.put(key, next);
            return next;
        }
    }

    /** 桩锁：永远"拿到了"，并记录调用次数（用于断言预览不取锁） */
    private static class StubRedisLock extends RedisLock {
        final AtomicInteger locks = new AtomicInteger();

        @Override
        public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
            locks.incrementAndGet();
            return true;
        }

        @Override
        public void unlock(String lockKey) {
        }
    }

    /** 桩配置服务：返回固定规则，并记录 current_seq 回写次数 */
    private static class StubConfigService implements ICodeConfigService {
        List<CodeConfigRule> rules = new ArrayList<>();
        final AtomicInteger incrCalls = new AtomicInteger();
        Integer currentSeq = 0;

        @Override
        public CodeConfigDTO getCodeConfigById(String id) {
            if (!CONF_ID.equals(id)) {
                return null;
            }
            CodeConfigDTO dto = new CodeConfigDTO();
            CodeConfig cfg = new CodeConfig();
            cfg.setId(CONF_ID);
            cfg.setTitle("单元测试编号");
            cfg.setCurrentSeq(currentSeq);
            dto.setId(CONF_ID);
            dto.setTitle("单元测试编号");
            dto.setCurrentSeq(currentSeq);
            dto.setRules(rules);
            return dto;
        }

        @Override
        public int incrCurrentSeq(String id) {
            incrCalls.incrementAndGet();
            return 1;
        }

        // 以下方法本用例用不到
        @Override
        public List<CodeConfig> listCodeConfig(CodeConfig codeConfig) {
            return new ArrayList<>();
        }

        @Override
        public List<CodeConfig> serialOptions() {
            return new ArrayList<>();
        }

        @Override
        public int saveCodeConfig(CodeConfigDTO codeConfig) {
            return 0;
        }

        @Override
        public int updateCodeConfig(CodeConfigDTO codeConfig) {
            return 0;
        }

        @Override
        public int changeEnableFlag(CodeConfig codeConfig) {
            return 0;
        }

        @Override
        public int deleteCodeConfigById(String id) {
            return 0;
        }

        @Override
        public void restSeq() {
        }
    }

    /** 桩流水服务：记录写入的编号 */
    private static class StubLogService implements ICodeSequenceLogService {
        final List<CodeSequenceLog> saved = new ArrayList<>();

        @Override
        public int saveCodeSequenceLog(CodeSequenceLog log) {
            saved.add(log);
            return 1;
        }

        @Override
        public CodeSequenceLog getCodeSequenceLogById(Long id) {
            return null;
        }

        @Override
        public List<CodeSequenceLog> listCodeSequenceLog(CodeSequenceLog codeSequenceLog) {
            return new ArrayList<>();
        }
    }

    /* ==================== 夹具与注入 ==================== */

    private StubRedisCache redis;
    private StubRedisLock lock;
    private StubConfigService configService;
    private StubLogService logService;
    private CodeGenServiceImpl service;

    @Before
    public void setUp() throws Exception {
        redis = new StubRedisCache();
        lock = new StubRedisLock();
        configService = new StubConfigService();
        logService = new StubLogService();
        service = new CodeGenServiceImpl();
        inject("codeConfigService", configService);
        inject("codeSequenceLogService", logService);
        inject("redisLock", lock);
        inject("redisCache", redis);
    }

    private void inject(String name, Object value) throws Exception {
        Field f = CodeGenServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static CodeConfigRule rule(String type, String value) {
        CodeConfigRule r = new CodeConfigRule();
        r.setRuleType(type);
        r.setRuleValue(value);
        r.setPadZero("0");
        r.setSeqResetType("0");
        return r;
    }

    /** 既有形态：FIXED(YX) + DATE(yyyyMMdd) + SEQ(2, 按日) */
    private void useLegacyRules() {
        List<CodeConfigRule> rules = new ArrayList<>();
        rules.add(rule("0", "YX"));
        rules.add(rule("1", "yyyyMMdd"));
        CodeConfigRule seq = rule("2", "2");
        seq.setPadZero("1");
        seq.setSeqResetType("1");
        rules.add(seq);
        configService.rules = rules;
    }

    /** 合同形态：PARAM(typeCode) + PARAM(subjectCode) + DATE(yyyy) + DATE(MM) + SEQ(6, 按年) */
    private void useContractRules() {
        List<CodeConfigRule> rules = new ArrayList<>();
        rules.add(rule("3", "typeCode"));
        rules.add(rule("3", "subjectCode"));
        rules.add(rule("1", "yyyy"));
        rules.add(rule("1", "MM"));
        CodeConfigRule seq = rule("2", "6");
        seq.setPadZero("1");
        seq.setSeqResetType("4");
        rules.add(seq);
        configService.rules = rules;
    }

    private static Map<String, String> params(String typeCode, String subjectCode) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("typeCode", typeCode);
        m.put("subjectCode", subjectCode);
        return m;
    }

    /* ==================== ① 老入口：整条编排仍然照旧 ==================== */

    @Test
    public void 老入口_编号形态_计数键_回写_留痕全部照旧() {
        useLegacyRules();
        redis.store.put("code:gen:seq:" + CONF_ID, 0);

        String c1 = service.getNextCode(CONF_ID);
        String c2 = service.getNextCode(CONF_ID);

        assertTrue("编号形态异常：" + c1, c1.startsWith("YX") && c1.endsWith("01"));
        assertTrue("编号应递增：" + c2, c2.endsWith("02"));
        assertEquals("老入口必须回写 current_seq（既有行为）", 2, configService.incrCalls.get());
        assertEquals("每次取号写一条流水", 2, logService.saved.size());
        assertEquals("流水里的序号应与编号一致", 2, logService.saved.get(1).getCodeSeq().intValue());
        assertTrue("老入口必须走分布式锁", lock.locks.get() >= 2);
    }

    @Test
    public void 老入口_计数器不存在时用配置表_current_seq_作为初值() {
        useLegacyRules();
        configService.currentSeq = 7; // 模拟"Redis 被清过、DB 里有历史序号"
        String code = service.getNextCode(CONF_ID);
        assertTrue("应从 DB 的 7 继续，得到 ...08，实际 " + code, code.endsWith("08"));
    }

    /* ==================== ② 新入口：参考日期 + 业务参数 + 分桶 ==================== */

    @Test
    public void 新入口_按参考日期与业务参数产号_且不回写current_seq() {
        useContractRules();
        CodeGenContext ctx = new CodeGenContext(parse("2025-06-01"), params("PUR", "ZC"));

        String c1 = service.getNextCode(CONF_ID, ctx);
        String c2 = service.getNextCode(CONF_ID, ctx);
        assertEquals("PURZC202506000001", c1);
        assertEquals("PURZC202506000002", c2);
        assertEquals("分桶路径不回写 current_seq", 0, configService.incrCalls.get());
        assertEquals("分桶路径仍要留痕", 2, logService.saved.size());
        assertTrue("计数键应含参数与年份：" + redis.store.keySet(),
                redis.store.containsKey("code:gen:seq:" + CONF_ID + ":typeCode=PUR:subjectCode=ZC:y2025"));
    }

    @Test
    public void 新入口_不同主体码各自独立计数() {
        useContractRules();
        String zc = service.getNextCode(CONF_ID, new CodeGenContext(parse("2025-06-01"), params("PUR", "ZC")));
        String xs = service.getNextCode(CONF_ID, new CodeGenContext(parse("2025-06-01"), params("PUR", "XS")));
        assertEquals("PURZC202506000001", zc);
        assertEquals("PURXS202506000001", xs);
    }

    @Test
    public void 新入口_跨年换桶_新年首号为000001() {
        useContractRules();
        String y25 = service.getNextCode(CONF_ID, new CodeGenContext(parse("2025-06-01"), params("PUR", "ZC")));
        String y27 = service.getNextCode(CONF_ID, new CodeGenContext(parse("2027-01-01"), params("PUR", "ZC")));
        assertEquals("PURZC202506000001", y25);
        assertEquals("PURZC202701000001", y27);
    }

    @Test
    public void 新入口_缺业务参数时报错且不写流水() {
        useContractRules();
        try {
            service.getNextCode(CONF_ID, new CodeGenContext(parse("2025-06-01"), params("PUR", null)));
            fail("缺业务参数应报错");
        } catch (BaseException e) {
            assertTrue("报错要点名参数：" + e.getMessage(), e.getMessage().contains("subjectCode"));
        }
        assertEquals("失败时不应写流水", 0, logService.saved.size());
    }

    /* ==================== ③ 预览不占号 ==================== */

    @Test
    public void 预览_连续两次同值_且不占号不取锁不写流水() {
        useContractRules();
        CodeGenContext ctx = new CodeGenContext(parse("2025-06-01"), params("PUR", "ZC"));
        int locksBefore = lock.locks.get();

        String p1 = service.previewNextCode(CONF_ID, ctx);
        String p2 = service.previewNextCode(CONF_ID, ctx);

        assertEquals("PURZC202506000001", p1);
        assertEquals("两次预览必须同值", p1, p2);
        assertEquals("预览不应取锁", locksBefore, lock.locks.get());
        assertEquals("预览不应写流水", 0, logService.saved.size());
        assertEquals("预览不应回写 current_seq", 0, configService.incrCalls.get());
        assertTrue("预览不应创建计数器：" + redis.store.keySet(), redis.store.isEmpty());

        String taken = service.getNextCode(CONF_ID, ctx);
        assertEquals("随后的取号正是预览值", p1, taken);
    }

    @Test
    public void 预览_有历史计数时预告下一个() {
        useContractRules();
        CodeGenContext ctx = new CodeGenContext(parse("2025-06-01"), params("PUR", "ZC"));
        service.getNextCode(CONF_ID, ctx); // 000001 被占
        String p = service.previewNextCode(CONF_ID, ctx);
        assertEquals("PURZC202506000002", p);
    }

    /* ==================== 边界 ==================== */

    /**
     * <b>回归守卫（实测踩过）</b>：写进 Redis 计数器的初值必须是 {@code Integer}。
     *
     * <p> 本工程的 Redis 值序列化器是 FastJson2，它把 {@code Long} 序列化成带后缀的 {@code 0L}，
     * 于是紧接着的 {@code INCR} 抛
     * {@code ERR value is not an integer or out of range} —— 表现为**所有**取号 500、
     * 而预览接口却一切正常（预览不 INCR）。既有实现传的是 {@code getCurrentSeq()}（Integer），
     * 所以这条断言同时也守住"不要顺手把初值改成 long"。 </p>
     */
    @Test
    public void 回归守卫_计数器初值必须是Integer() {
        useLegacyRules();
        configService.currentSeq = 5;
        service.getNextCode(CONF_ID);
        assertEquals("应当写入一次计数器初值", 1, redis.initialWrites.size());
        Object written = redis.initialWrites.get(0);
        assertTrue("计数器初值必须是 Integer，实际是 " + written.getClass().getName()
                + "（Long 会被 FastJson2 写成 0L，Redis INCR 直接失败）", written instanceof Integer);
        assertTrue("初值不应是 Long", !(written instanceof Long));
    }

    @Test
    public void 配置不存在或没有规则时给出明确报错() {
        useLegacyRules();
        try {
            service.getNextCode("NOT-EXIST");
            fail("配置不存在应报错");
        } catch (BaseException e) {
            assertTrue(e.getMessage().contains("未配置编号规则"));
        }
        configService.rules = new ArrayList<>();
        try {
            service.getNextCode(CONF_ID);
            fail("没有规则应报错");
        } catch (BaseException e) {
            assertTrue(e.getMessage().contains("未配置编号规则"));
        }
    }

    @Test
    public void 空confId报参数错误() {
        try {
            service.getNextCode("  ");
            fail("空 confId 应报参数错误");
        } catch (BaseException e) {
            assertEquals("参数错误", e.getMessage());
        }
    }

    /* ==================== 工具 ==================== */

    private static java.util.Date parse(String yyyyMMdd) {
        java.util.Date d = com.ruoyi.common.utils.DateUtils.parseDate(yyyyMMdd);
        if (d == null) {
            throw new IllegalStateException("日期解析失败：" + yyyyMMdd);
        }
        return d;
    }
}
