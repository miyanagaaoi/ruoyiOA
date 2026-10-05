package com.ruoyi.ctms.support;

import java.util.Collection;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> 合同编号的<b>纯规则</b>（2.0 B3 任务 5.8；规格 ctms/contract-commercials 的
 * 「合同编号格式与按年重置」与「编号类型与主体取值校验」两个 Requirement）。 </p>
 *
 * <p> 格式（紧凑、无分隔符）：<b>3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号</b>，
 * 例 {@code PURZC202506000001}。编号的<b>拼装与计数</b>不在本类 —— 那部分是
 * {@code ruoyi-serial} 的职责（配置 id {@link #CONF_ID}，规则见
 * {@code sql/二开-合同台账-编号配置.sql}）；本类只负责三件可在无容器环境下逐条断言的事： </p>
 * <ol>
 *   <li> 类型码 / 主体码的<b>取值校验</b>（含"不参与自动编号的类型被拒"）； </li>
 *   <li> 编号文本的<b>格式校验</b>与各段<b>解析</b>（迁移导入通道校验手工编号用）； </li>
 *   <li> 序号/年份/月份的提取（"停用占号不复用"的占号集合扫描用）。 </li>
 * </ol>
 *
 * <p> <b>类型码从哪来</b>：{@code sys_dict_data.contract_types} 的 {@code dict_value}
 * 就是 3 位大写类型码（{@code SAL/PUR/COO/LAB/FIN/NDA}），{@code status='1'} 的类型
 * （{@code OTH} 其他(历史)）语义就是<b>不参与自动编号</b>；字典接口本身只返回启用项，
 * 所以"允许集合"取 {@code selectDictDataByType} 的 {@code dict_value} 即可，
 * 而"被停用"需要调用方单独判定（见 {@link #checkTypeForAutoNumber}）。 </p>
 *
 * <p> 主体码同理由 {@code sys_dict_data.subjects} 提供（{@code ZC/YX}，2 位大写）。 </p>
 *
 * <p> ⚠ 口径与参考侧一致、且已在规格里写成可观察行为的两条： </p>
 * <ul>
 *   <li> 月份码取<b>签订日期</b>所在月，且<b>不参与序号</b>（同年同类型同主体跨月连续递增）； </li>
 *   <li> 编号一旦被占用（<b>含已停用合同占号</b>）不得复用 —— 唯一索引 + 查重都在软删除行上照常生效。 </li>
 * </ul>
 *
 * @author 二开
 */
public final class ContractNumberRules
{
    /**
     * 合同编号的编号配置 id（{@code t_code_config.id}）。
     *
     * <p> 真源是 {@code sql/二开-合同台账-编号配置.sql}（一条配置服务全部
     * 类型码 × 主体码组合：类型码/主体码是「业务参数」规则，年份/月份取签订日期，
     * 序号 6 位按年重置）。这里的常量必须与那份 SQL 逐字一致。 </p>
     */
    public static final String CONF_ID = "9F2C0000000000000000000000C001";

    /** 类型码长度（规格：3 位类型码） */
    public static final int TYPE_CODE_LENGTH = 3;

    /** 主体码长度（规格：2 位主体码） */
    public static final int SUBJECT_CODE_LENGTH = 2;

    /** 编号总长度：3 + 2 + 4 + 2 + 6 = 17 */
    public static final int NO_LENGTH = 17;

    /** 编号正则：3 位大写类型码 + 2 位大写主体码 + yyyy + MM + 6 位序号 */
    private static final String NO_PATTERN = "^[A-Z]{3}[A-Z]{2}\\d{4}\\d{2}\\d{6}$";

    /** 被停用（不参与自动编号）的类型提示文案 —— 规格逐字要求，改动即破坏性变更 */
    public static final String MSG_TYPE_DISABLED = "该类型暂不支持自动编号";

    /** 主体缺失/非法的提示文案 —— 规格逐字要求 */
    public static final String MSG_SUBJECT_INVALID = "请选择有效主体";

    /** 编号格式非法的提示文案（手工/历史编号通道） */
    public static final String MSG_NO_FORMAT = "合同编号格式不正确，应为 3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号";

    /**
     * <p> 自动取号重试耗尽时的异常文案（t31 / R2-③）。 </p>
     *
     * <p> ⚠ <b>这是接口契约</b>：脚本/用例按关键字「取号失败」与「重试上限」断言，
     * 改文案必须同步改用例。它存在的意义是：**上限耗尽必须显式失败，绝不允许静默插入已占用编号**
     * （那等于把唯一索引之外的兜底变成静默数据风险）。 </p>
     */
    public static final String MSG_NO_EXHAUSTED = "自动编号取号失败：重试上限内未取到可用编号";

    private ContractNumberRules()
    {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 校验合同的类型是否可用于<b>自动编号</b>，并返回其 3 位类型码。
     *
     * @param type      合同类型（{@code t_ctms_contract.type}，存的就是类型码 SAL/PUR/…）
     * @param typeCodes 当前<b>启用</b>且可用于编号的类型码集合（来自 {@code contract_types} 字典）
     * @return 归一后的类型码
     * @throws ServiceException 类型为空、不在启用集合内，或不是 3 位大写字母（文案见
     *                          {@link #MSG_TYPE_DISABLED}）
     */
    public static String checkTypeForAutoNumber(String type, Collection<String> typeCodes)
    {
        String value = ContractRules.trimToNull(type);
        if (value != null && isTypeCode(value)
                && typeCodes != null && typeCodes.contains(value))
        {
            return value;
        }
        // 类型缺失/停用（OTH）/ 不是 3 位大写码 —— 规格只给了一条文案，不细分
        throw new ServiceException(MSG_TYPE_DISABLED);
    }

    /**
     * 校验主体码（自动编号用），并返回归一后的 2 位主体码。
     *
     * @param subjectCode 主体码（{@code t_ctms_contract.subject_code}）
     * @param subjectCodes 已配置的主体码集合（来自 {@code subjects} 字典）
     * @return 归一后的主体码
     * @throws ServiceException 主体为空、不在集合内，或不是 2 位大写字母（文案见
     *                          {@link #MSG_SUBJECT_INVALID}）
     */
    public static String checkSubjectForAutoNumber(String subjectCode, Collection<String> subjectCodes)
    {
        String value = ContractRules.trimToNull(subjectCode);
        if (value != null && isSubjectCode(value)
                && subjectCodes != null && subjectCodes.contains(value))
        {
            return value;
        }
        throw new ServiceException(MSG_SUBJECT_INVALID);
    }

    /**
     * 编号文本是否合法（迁移导入通道与"手工指定编号"的入口校验）。
     *
     * @param contractNo 编号
     * @return 合法返回 true
     */
    public static boolean isValidNo(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        return value != null && value.matches(NO_PATTERN);
    }

    /**
     * 校验编号格式（写入前的守卫）。
     *
     * @param contractNo 编号
     * @return 归一后的编号
     * @throws ServiceException 格式不合法（文案见 {@link #MSG_NO_FORMAT}）
     */
    public static String checkNoFormat(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        if (!isValidNo(value))
        {
            throw new ServiceException(MSG_NO_FORMAT);
        }
        return value;
    }

    /**
     * 从编号中提取<b>年份段</b>（第 6~9 位）。
     *
     * <p> 与 {@code t_code_config_rule} 的「按年重置」是同一年份口径：
     * 序号只在「类型码 + 主体码 + 年份」维度内递增，跨年从 {@code 000001} 重来。 </p>
     *
     * @param contractNo 编号
     * @return 4 位年份文本；格式不合法返回 null
     */
    public static String yearOf(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        return isValidNo(value) ? value.substring(5, 9) : null;
    }

    /**
     * 从编号中提取<b>月份段</b>（第 10~11 位）。
     *
     * <p> 月份码只反映签订月份，<b>不参与序号</b> —— 同一编号前缀下跨月是连续递增的。 </p>
     *
     * @param contractNo 编号
     * @return 2 位月份文本；格式不合法返回 null
     */
    public static String monthOf(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        return isValidNo(value) ? value.substring(9, 11) : null;
    }

    /**
     * 从编号中提取<b>序号段</b>（末 6 位）并转为整数。
     *
     * <p> 用途：在"编号已被占用（含停用占号）不复用"的判定里，需要在同一
     * 「类型码 + 主体码 + 年份」维度上找出已用掉的最大序号。 </p>
     *
     * @param contractNo 编号
     * @return 序号；格式不合法返回 -1
     */
    public static int seqOf(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        if (!isValidNo(value))
        {
            return -1;
        }
        return Integer.parseInt(value.substring(11));
    }

    /**
     * 从编号中提取<b>前缀</b>（类型码 + 主体码 + 年份 = 前 9 位），即序号的分桶键。
     *
     * @param contractNo 编号
     * @return 9 位前缀；格式不合法返回 null
     */
    public static String prefixOf(String contractNo)
    {
        String value = ContractRules.trimToNull(contractNo);
        return isValidNo(value) ? value.substring(0, 9) : null;
    }

    /**
     * 在一批已占用编号里筛出与给定前缀同桶的那些编号（占号扫描）。
     *
     * <p> <b>必须把已停用合同一并传进来</b>：规格明确"停用占号不复用"。 </p>
     *
     * @param contractNos 已占用编号集合（含软删除行）
     * @param prefix      前缀（{@link #prefixOf} 的结果）
     * @return 同前缀的编号集合（保持入参顺序）
     */
    public static List<String> sameBucket(List<String> contractNos, String prefix)
    {
        List<String> result = new java.util.ArrayList<>();
        if (contractNos == null || prefix == null)
        {
            return result;
        }
        for (String no : contractNos)
        {
            if (prefix.equals(prefixOf(no)))
            {
                result.add(no);
            }
        }
        return result;
    }

    /**
     * <p> 一批已占编号里，与给定前缀同桶的<b>最大序号</b>（t31 / R2-②）。 </p>
     *
     * <p> 用途：取号的权威计数器在 Redis；一旦它落后于库内桶（Redis 被清空、从快照恢复库、
     * 编号配置重建），若仍从计数器低位起步，就会反复撞上"库里已经有这个号"，
     * 把有界重试白烧光。取号前先用"库内桶 max"把起点抬到真实水位之上，才不会空烧。 </p>
     *
     * @param contractNos 已占用编号集合（含软删除行，见 {@code selectAllContractNos} 的注释）
     * @param prefix      前缀（{@link #prefixOf} 的结果）
     * @return 同桶最大序号；无同桶编号（或前缀为空）返回 0
     */
    public static int maxSeqOf(List<String> contractNos, String prefix)
    {
        int max = 0;
        for (String used : sameBucket(contractNos, prefix))
        {
            int seq = seqOf(used);
            if (seq > max)
            {
                max = seq;
            }
        }
        return max;
    }

    /**
     * <p> 按「模板编号的头部（11 位：类型码 + 主体码 + 年份 + 月份）+ 新序号」拼回一个合法编号
     * （t31 / R2-②）。 </p>
     *
     * <p> ⚠ 必须带<b>月份</b>：{@link #prefixOf} 只到年份（9 位，因为序号桶不按月重置），
     * 若用 9 位前缀直接拼 6 位序号会得到 15 位、格式非法。所以这里用"候选编号"当模板，
     * 取其前 11 位（含月份）再配新序号。 </p>
     *
     * <p> 用途：把取号结果抬到「库内桶 max + 1」时按同口径拼回编号，而不是自己截/串字符串。
     * 序号超出 6 位（&gt; 999999）或模板非法时返回 {@code null}，让调用方走"取号失败"的显式异常。 </p>
     *
     * @param templateNo 模板编号（通常就是刚生成的候选）
     * @param seq        序号（1 ~ 999999）
     * @return 合法编号；参数非法或序号越界返回 {@code null}
     */
    public static String buildNo(String templateNo, int seq)
    {
        String head = ContractRules.trimToNull(templateNo);
        if (head == null || head.length() != NO_LENGTH || !isValidNo(head) || seq <= 0 || seq > 999999)
        {
            return null;
        }
        String no = head.substring(0, TYPE_CODE_LENGTH + SUBJECT_CODE_LENGTH + 4 + 2)
                + String.format(java.util.Locale.ROOT, "%06d", seq);
        return isValidNo(no) ? no : null;
    }

    /**
     * 类型码格式：恰好 3 位且全为大写 ASCII 字母（规格「可映射为 3 位大写字母」）。
     *
     * @param value 值
     * @return 合法返回 true
     */
    public static boolean isTypeCode(String value)
    {
        return isUpperAlpha(value, TYPE_CODE_LENGTH);
    }

    /**
     * 主体码格式：恰好 2 位且全为大写 ASCII 字母（规格「2 位大写字母」）。
     *
     * @param value 值
     * @return 合法返回 true
     */
    public static boolean isSubjectCode(String value)
    {
        return isUpperAlpha(value, SUBJECT_CODE_LENGTH);
    }

    /**
     * 固定长度的大写字母串判定。
     *
     * @param value  值
     * @param length 期望长度
     * @return 合法返回 true
     */
    private static boolean isUpperAlpha(String value, int length)
    {
        if (value == null || value.length() != length)
        {
            return false;
        }
        for (int i = 0; i < length; i++)
        {
            char c = value.charAt(i);
            if (c < 'A' || c > 'Z')
            {
                return false;
            }
        }
        return true;
    }
}
