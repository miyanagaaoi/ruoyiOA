package com.ruoyi.serial.support;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.serial.domain.CodeConfigRule;
import com.ruoyi.serial.enums.RuleTypeEnum;
import com.ruoyi.serial.enums.SeqResetTypeEnum;
import com.ruoyi.serial.module.CodeGenContext;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p> 编号渲染与分桶的<b>纯函数</b>（2.0 B3 §1.3）。 </p>
 *
 * <p> 刻意与 Redis / DB 解耦：这样"日期取参考日期、业务参数入码、按年分桶"这三条规则
 * 可以被真正的单元测试逐条断言（表驱动），而不必起容器或依赖 Redis。 </p>
 *
 * <p> <b>向后兼容是硬约束</b>：{@code context == null} 或未传业务参数时，
 * 本类的输出必须与扩展前 {@code CodeGenServiceImpl.addValueByRuleType} 的逐字符一致 ——
 * 既有 11 类编号全都走这条路径。 </p>
 *
 * @author 二开
 */
public final class CodeRenderSupport {

    private CodeRenderSupport() {
    }

    /**
     * 按规则渲染编号。
     *
     * @param rules         规则（调用方保证已按 {@code sort} 升序）
     * @param seq           本次流水号
     * @param context       取号上下文，可为 null（= 既有行为：日期取当天、无业务参数）
     * @param now           服务器当前时间（由调用方注入，便于测试）
     */
    public static String render(List<CodeConfigRule> rules, long seq, CodeGenContext context, Date now) {
        StringBuilder code = new StringBuilder();
        for (CodeConfigRule rule : rules) {
            appendByRule(rule, code, seq, context, now);
        }
        return code.toString();
    }

    private static void appendByRule(CodeConfigRule rule, StringBuilder code, long seq,
                                     CodeGenContext context, Date now) {
        String ruleValue = rule.getRuleValue();
        RuleTypeEnum type = RuleTypeEnum.getByCode(rule.getRuleType());
        if (type == null) {
            throw new BaseException("编号规则类型错误！");
        }
        switch (type) {
            case FIXED:
                code.append(ruleValue);
                break;
            case DATE:
                // 有参考日期就用参考日期（合同=签订日期），否则维持既有行为"取当天"
                Date date = context != null && context.getReferenceDate() != null
                        ? context.getReferenceDate() : now;
                code.append(DateUtils.parseDateToStr(ruleValue, date));
                break;
            case PARAM: {
                // ruleValue 是"业务参数名"，取值来自调用方传入的上下文
                if (context == null || StringUtils.isBlank(ruleValue)) {
                    throw new BaseException("编号规则【业务参数】未提供参数名！");
                }
                String value = context.getParams().get(ruleValue);
                if (value == null) {
                    throw new BaseException("编号规则缺少业务参数：" + ruleValue);
                }
                code.append(value);
                break;
            }
            case SEQ:
                if (StringUtils.equals("1", rule.getPadZero())) {
                    code.append(StringUtils.padl(seq, Integer.parseInt(ruleValue)));
                } else {
                    code.append(seq);
                }
                break;
            default:
                throw new BaseException("编号规则类型错误！");
        }
    }

    /**
     * 计数器键。
     *
     * <p> 两条路径，<b>键格式互不影响</b>： </p>
     * <ul>
     *   <li> <b>不分桶</b>（{@code context} 为空或没有业务参数）：
     *        {@code code:gen:seq:<confId>} —— 与扩展前**逐字一致**，
     *        计数器初值仍取 {@code t_code_config.current_seq}； </li>
     *   <li> <b>分桶</b>：{@code code:gen:seq:<confId>:<按规则顺序的 k=v>:y<年份>} ——
     *        新桶/新年**天然从 0 开始**（即"惰性跨年重置"：不需要任何定时任务，
     *        也不存在"比较年份再清零"的读改写竞态）。 </li>
     * </ul>
     *
     * <p> 年份后缀只在「唯一那条流水号规则的 {@code seqResetType=按年} 且给了参考日期」时追加；
     * 其余重置类型（不重置/按日/按周/按月）不参与分桶，保持既有语义。 </p>
     */
    public static String bucketKeyOf(String confId, List<CodeConfigRule> rules, CodeGenContext context) {
        String base = "code:gen:seq:" + confId;
        if (context == null || !context.isBucketed()) {
            return base;
        }
        StringBuilder key = new StringBuilder(base);
        // 业务参数按"规则里的出现顺序"入键（稳定、可读，与调用方传 Map 的顺序无关）
        List<String> used = new ArrayList<>();
        for (CodeConfigRule rule : rules) {
            if (!RuleTypeEnum.PARAM.getCode().equals(rule.getRuleType())) {
                continue;
            }
            String name = rule.getRuleValue();
            if (StringUtils.isBlank(name) || used.contains(name)) {
                continue;
            }
            used.add(name);
            key.append(':').append(name).append('=').append(context.getParams().get(name));
        }
        // 流水号规则的重置类型决定是否按年分桶
        for (CodeConfigRule rule : rules) {
            if (!RuleTypeEnum.SEQ.getCode().equals(rule.getRuleType())) {
                continue;
            }
            if (SeqResetTypeEnum.YEAR.getCode().equals(rule.getSeqResetType())
                    && context.getReferenceDate() != null) {
                key.append(":y").append(DateUtils.parseDateToStr("yyyy", context.getReferenceDate()));
            }
            break;
        }
        return key.toString();
    }

    /**
     * 计数器的起始值。
     *
     * <p> 不分桶时用配置表的 {@code current_seq}（既有行为）；分桶时用 <b>0</b> ——
     * 因为 {@code t_code_config.current_seq} 是**单列**，无法表达"多个桶 + 多个年份"的计数，
     * 强行共用会让不同主体码互相污染。分桶路径的权威计数只在 Redis，实际序号同时记入
     * {@code t_code_sequence_log.code_seq} 可查。 </p>
     *
     * <p> ⚠⚠ <b>返回类型必须是 {@code int}（即 Integer），不能是 {@code long}（Long）</b>：
     * 这个值会被写进 Redis 计数器键，而本工程的 Redis 值序列化器是 FastJson2 ——
     * 它把 {@code Long} 写成带后缀的 {@code 0L}，于是随后的 {@code INCR} 直接失败：
     * {@code ERR value is not an integer or out of range}。
     * （实测踩过：改成 long 之后**所有**取号都 500，而预览正常 —— 因为预览不 INCR。
     * 既有实现传的是 {@code CodeConfig.getCurrentSeq()}（Integer），所以一直是好的。） </p>
     */
    public static int initialSeqOf(Integer configCurrentSeq, CodeGenContext context) {
        if (context != null && context.isBucketed()) {
            return 0;
        }
        return configCurrentSeq == null ? 0 : configCurrentSeq;
    }

    /** 单条配置里是否含"业务参数"规则（用于给出更明确的报错/日志） */
    public static boolean hasParamRule(List<CodeConfigRule> rules) {
        if (rules == null) {
            return false;
        }
        for (CodeConfigRule rule : rules) {
            if (RuleTypeEnum.PARAM.getCode().equals(rule.getRuleType())) {
                return true;
            }
        }
        return false;
    }

    /** 供日志/排查：把上下文压成一行 */
    public static String describe(CodeGenContext context) {
        if (context == null) {
            return "(无上下文)";
        }
        Map<String, String> params = context.getParams();
        return "refDate=" + (context.getReferenceDate() == null ? "当天"
                : DateUtils.parseDateToStr("yyyy-MM-dd", context.getReferenceDate()))
                + ", params=" + params;
    }
}
