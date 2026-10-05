package com.ruoyi.serial.api;

import com.ruoyi.serial.module.CodeGenContext;

/**
 * 编号生成服务
 * @Author wocurr.com
 */
public interface ICodeGenService {

    /**
     * 获取下一个编号
     * @param confId 编号配置id
     * @return
     */
    public String getNextCode(String confId);

    /**
     * <p> 获取下一个编号（带取号上下文，2.0 B3 §1.3 新增）。 </p>
     *
     * <p> 相对 {@link #getNextCode(String)} 的三点增强，<b>全部由上下文触发、不传即不变</b>： </p>
     * <ol>
     *   <li> <b>日期规则取参考日期</b>：{@code context.referenceDate} 非空时，日期规则按它格式化
     *        （合同场景传签订日期）；否则维持既有行为"取当天"； </li>
     *   <li> <b>业务参数入码 + 分桶计数</b>：{@code context.params} 非空时，类型为「业务参数」的
     *        规则按参数名取值拼进编号，并让计数器按「参数组合 + 年份」分桶
     *        （新桶/新年天然从 0 开始 = 惰性跨年重置，不依赖任何定时任务）； </li>
     *   <li> 分桶路径<b>不回写</b> {@code t_code_config.current_seq}（单列无法表达多桶），
     *        实际序号仍写入 {@code t_code_sequence_log.code_seq} 可查。 </li>
     * </ol>
     *
     * @param confId  编号配置id
     * @param context 取号上下文；{@code null} 时行为与 {@link #getNextCode(String)} 完全一致
     * @return 编号
     */
    public String getNextCode(String confId, CodeGenContext context);

    /**
     * <p> 预览下一个编号（<b>不占号</b>，2.0 B3 §1.3 新增）。 </p>
     *
     * <p> 返回"此刻取号会得到的编号"，但<b>不递增计数器、不写编号流水、不修改配置表</b>，
     * 因此连续两次预览返回同一个编号。 </p>
     *
     * @param confId  编号配置id
     * @param context 取号上下文，可为 null
     */
    public String previewNextCode(String confId, CodeGenContext context);
}
