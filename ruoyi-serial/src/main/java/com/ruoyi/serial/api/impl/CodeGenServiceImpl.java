package com.ruoyi.serial.api.impl;

import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.domain.CodeConfigRule;
import com.ruoyi.serial.domain.CodeSequenceLog;
import com.ruoyi.serial.module.CodeConfigDTO;
import com.ruoyi.serial.module.CodeGenContext;
import com.ruoyi.serial.service.ICodeConfigService;
import com.ruoyi.serial.service.ICodeSequenceLogService;
import com.ruoyi.serial.support.CodeRenderSupport;
import com.ruoyi.tools.lock.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 编号生成服务实现类
 *
 * <p> <b>2.0 B3 §1.3 的扩展（design.md D-6 的"最小扩展面"）</b>： </p>
 * <ol>
 *   <li> 新增 {@link #getNextCode(String, CodeGenContext)}：支持"调用方传参考日期"（日期规则按它取）
 *        与"业务参数入码 + 分桶计数"（按参数组合与年份分桶，新桶/新年天然从 0 起
 *        —— 即惰性跨年重置，<b>不依赖 1 月 1 日的定时任务</b>）； </li>
 *   <li> 新增 {@link #previewNextCode(String, CodeGenContext)}：预览不占号； </li>
 *   <li> 既有 {@link #getNextCode(String)} <b>行为与键格式一字不变</b>
 *        （{@code code:gen:seq:<confId>} + DB {@code current_seq} 初值 + 回写 current_seq + 写流水），
 *        既有 11 类编号不受影响 —— 这是本扩展的硬约束。 </li>
 * </ol>
 *
 * <p> 渲染与分桶的判定逻辑放在 {@link CodeRenderSupport} 的纯函数里，便于表驱动单测。 </p>
 *
 * @Author wocurr.com
 */
@Slf4j
@Service
public class CodeGenServiceImpl implements ICodeGenService {

    @Autowired
    private ICodeConfigService codeConfigService;
    @Autowired
    private ICodeSequenceLogService codeSequenceLogService;
    @Autowired
    private RedisLock redisLock;
    @Autowired
    private RedisCache redisCache;

    private static final String LOCK_KEY_SUFFIX = "code:gen:lock:";
    private static final String SEQ_KEY_SUFFIX = "code:gen:seq:";

    /**
     * 获取下一个编号（既有入口，行为不变）
     *
     * @param confId
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String getNextCode(String confId) {
        return doGetNextCode(confId, null);
    }

    /**
     * 获取下一个编号（带上下文）
     *
     * @param confId  编号配置id
     * @param context 取号上下文；null 时与 {@link #getNextCode(String)} 完全一致
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String getNextCode(String confId, CodeGenContext context) {
        return doGetNextCode(confId, context);
    }

    private String doGetNextCode(String confId, CodeGenContext context) {
        if (StringUtils.isBlank(confId)) {
            throw new BaseException("参数错误");
        }
        String key = LOCK_KEY_SUFFIX + confId;
        try {
            redisLock.tryLock(key, 10L, 20L, TimeUnit.SECONDS);
            CodeConfigDTO codeConfigDTO = codeConfigService.getCodeConfigById(confId);
            if (codeConfigDTO == null) {
                throw new BaseException("此类型未配置编号规则！");
            }
            List<CodeConfigRule> rules = codeConfigDTO.getRules();
            if (rules == null || rules.isEmpty()) {
                throw new BaseException("此类型未配置编号规则！");
            }
            boolean bucketed = context != null && context.isBucketed();
            String codeKey = CodeRenderSupport.bucketKeyOf(codeConfigDTO.getId(), rules, context);
            if (!redisCache.hasKey(codeKey)) {
                // 不分桶：初值取配置表（既有行为）；
                // 分桶：初值 0（单列 current_seq 无法表达多桶，见 CodeRenderSupport.initialSeqOf 的注释）
                // ⚠ 初值必须是 Integer —— Long 会被 FastJson2 写成 "0L"，随后 INCR 直接报错
                redisCache.setCacheObject(codeKey,
                        CodeRenderSupport.initialSeqOf(codeConfigDTO.getCurrentSeq(), context));
            }
            long nowSeq = redisCache.getIncr(codeKey);
            log.info("获取编号，businessType：{}，当前序号：{}，分桶：{}，上下文：{}",
                    confId, nowSeq, bucketed, CodeRenderSupport.describe(context));
            String nextCode = CodeRenderSupport.render(rules, nowSeq, context, DateUtils.getNowDate());
            // 更新最新流水号：只在不分桶时回写（分桶路径的单列语义无法表达，见类注释）
            if (!bucketed) {
                codeConfigService.incrCurrentSeq(codeConfigDTO.getId());
            }
            // 插入编号记录
            CodeSequenceLog codeSequenceLog = new CodeSequenceLog();
            codeSequenceLog.setTitle(codeConfigDTO.getTitle());
            codeSequenceLog.setCode(nextCode);
            codeSequenceLog.setCodeSeq((int) nowSeq);
            codeSequenceLog.setCreateTime(DateUtils.getNowDate());
            codeSequenceLogService.saveCodeSequenceLog(codeSequenceLog);
            return nextCode;
        } catch (BaseException e) {
            throw new BaseException(e.getMessage());
        } catch (Exception e) {
            // ⚠ 把真实原因**打进日志**再包装成业务文案。
            //   原实现只 `throw new BaseException("获取编号失败！")`，异常链被丢掉 ——
            //   线上/联调时只能看到这句无信息量的话（实测排查成本极高：明明 Redis 里
            //   计数器已经建好了，却完全看不出是哪一步炸的）。
            log.error("获取编号失败 confId={}，上下文：{}", confId, CodeRenderSupport.describe(context), e);
            throw new BaseException("获取编号失败！");
        } finally {
            redisLock.unlock(key);
        }
    }

    /**
     * 预览下一个编号：<b>只算不占号</b>。
     *
     * <p> 不取锁、不 INCR、不写流水、不回写配置表 —— 因此连续两次预览返回同一编号。
     * 预估值 = 计数器当前值 + 1；计数器不存在时用配置表的 {@code current_seq}（与真正取号的初值一致）。 </p>
     */
    @Override
    public String previewNextCode(String confId, CodeGenContext context) {
        if (StringUtils.isBlank(confId)) {
            throw new BaseException("参数错误");
        }
        try {
            CodeConfigDTO codeConfigDTO = codeConfigService.getCodeConfigById(confId);
            if (codeConfigDTO == null) {
                throw new BaseException("此类型未配置编号规则！");
            }
            List<CodeConfigRule> rules = codeConfigDTO.getRules();
            if (rules == null || rules.isEmpty()) {
                throw new BaseException("此类型未配置编号规则！");
            }
            String codeKey = CodeRenderSupport.bucketKeyOf(codeConfigDTO.getId(), rules, context);
            long next = (long) CodeRenderSupport.initialSeqOf(codeConfigDTO.getCurrentSeq(), context) + 1;
            if (redisCache.hasKey(codeKey)) {
                long current = readCounter(codeKey);
                next = current + 1;
            }
            return CodeRenderSupport.render(rules, next, context, DateUtils.getNowDate());
        } catch (BaseException e) {
            throw new BaseException(e.getMessage());
        } catch (Exception e) {
            log.warn("预览编号失败 confId={}", confId, e);
            throw new BaseException("预览编号失败！");
        }
    }

    /**
     * 读计数器当前值（**不改变它**）。
     *
     * <p> 计数器由 {@code opsForValue().increment()} 写入，Redis 里是裸整数；
     * 配置初始值则是用序列化器写入的。两种形态都要能读，所以这里对
     * {@code Number} 与数字字符串都做兼容，读不出来就退化为 0（宁可预览从 1 开始，
     * 也不要把"读计数器"变成一个新故障点）。 </p>
     */
    private long readCounter(String codeKey) {
        try {
            Object cached = redisCache.getCacheObject(codeKey);
            if (cached == null) {
                return 0L;
            }
            if (cached instanceof Number) {
                return ((Number) cached).longValue();
            }
            return Long.parseLong(String.valueOf(cached).trim());
        } catch (Exception e) {
            log.warn("读取编号计数器失败，按 0 处理：key={}", codeKey, e);
            return 0L;
        }
    }
}
