package com.ruoyi.ctms.support;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> 历史甲乙方迁移的<b>纯规则</b>（2.0 B3 任务 7.1；规格 {@code ctms/contract-migration} 的
 * 「历史甲乙方文本扫描生成待认领草案」与「草案认领与批量绑定」）。 </p>
 *
 * <p> 本类只回答四个没有副作用的问题，因此既能被扫描与认领两条链路共用，也能在没有容器、
 * 没有数据库、没有时钟的环境里逐条断言： </p>
 * <ol>
 *   <li> 合同类型 → 档案方向的映射（{@link #directionOf(String)}）； </li>
 *   <li> 某方向的「原始文本」取自合同的哪一列（{@link #rawTextOf(String, String, String)}）； </li>
 *   <li> 某方向的「档案引用」取自合同的哪一列（{@link #referenceIdOf(String, String, String)}）； </li>
 *   <li> 原始文本的归一与分组键（{@link #normalizeRawName(String)} / {@link #rawNameKey(String)}）； </li>
 *   <li> 草案状态机：状态是否合法、是否可认领/可忽略（{@link #isKnownStatus(String)} /
 *        {@link #claimable(String)} / {@link #checkClaimable(String)} /
 *        {@link #ignorable(String)} / {@link #checkIgnorable(String)}）。 </li>
 * </ol>
 *
 * <p> <b>方向映射的真源</b>：{@code sys_dict_data.contract_types} 的 {@code dict_value}（3 位类型码，
 * 同时就是合同编号的类型码，见 {@code notes/contract-ledger-notes.md}）叠加规格
 * {@code ctms/business-partners} 的「档案方向按合同类型映射」——<b>采购类合同引用供应商作为乙方、
 * 销售类合同引用客户作为甲方</b>。因此<b>只有</b> {@code PUR} 与 {@code SAL} 参与扫描，
 * 其余类型（{@code COO}/{@code LAB}/{@code FIN}/{@code NDA}/{@code OTH}「其他(历史)」）与任何未知值
 * 一律返回 {@code null}（跳过）。 </p>
 *
 * <p> ⚠ <b>为什么"不参与映射的类型"要跳过，而不是按文本里有没有"公司/厂"猜方向</b>：
 * 方向一旦猜错，认领时会把这个档案写进合同<b>另一个方向</b>的引用列（客户写进 {@code supplier_id}），
 * 这是静默的数据污染，只能靠人工回溯变更历史纠正。宁可漏扫（人可以在迁移清单上补），也不猜。 </p>
 *
 * <p> ⚠ <b>为什么只认类型码、不认中文类型名</b>：本仓库合同 {@code type} 的落库口径就是类型码
 * （{@code tools/ctms-contract-check.ps1}、{@code tools/ctms-commercials-check.ps1} 都按
 * {@code SAL}/{@code PUR} 造数；编号服务 {@code ContractNumberRules} 的入参也是类型码）。
 * {@code sql/二开-合同台账.sql} 里该列 COMMENT 写的「中文类型名」与 {@code DEFAULT '采购'} 是第 2 组
 * 建表时的遗留描述，与实际写入值不一致：中文形态的历史数据必须由导入通道先归一为类型码
 * （{@code 采购→PUR}、{@code 销售→SAL}，参考侧 {@code app/dicts.py} 的旧标签映射就是这个口径），
 * 否则扫描会静默漏掉这些合同 —— 这条口径已写进 {@code notes/migration-notes.md} 的迁移前置检查。 </p>
 *
 * @author 二开
 */
public final class MigrationRules
{
    /* ==================== 档案方向（t_ctms_party_draft.party_type 的取值） ==================== */

    /** 档案方向：客户 —— 销售类合同的甲方（合同的 {@code customer_id} / {@code party_a} 一侧）。 */
    public static final String DIRECTION_CUSTOMER = "customer";

    /** 档案方向：供应商 —— 采购类合同的乙方（合同的 {@code supplier_id} / {@code party_b} 一侧）。 */
    public static final String DIRECTION_SUPPLIER = "supplier";

    /* ==================== 草案状态（t_ctms_party_draft.status 的取值） ==================== */

    /** 草案状态：待认领（DDL 默认值，也是扫描新建草案的唯一初始状态）。 */
    public static final String STATUS_PENDING = "pending";

    /** 草案状态：已认领（已绑定档案，{@code matched_id} 非空）。 */
    public static final String STATUS_CLAIMED = "claimed";

    /** 草案状态：已忽略（人工判定"这条文本不需要档案化"）。 */
    public static final String STATUS_IGNORED = "ignored";

    /**
     * 认领被拒的提示文案（已认领草案不可重复认领）。
     *
     * <p> ⚠ 这是<b>接口契约</b>：任务 7.3 的验收按关键字「已认领」断言，改文案必须同步改用例与前端提示。 </p>
     */
    public static final String MSG_ALREADY_CLAIMED = "该草案已认领，不能重复认领";

    /** 草案状态非法时的提示文案（状态机只允许 {@code pending}/{@code claimed}/{@code ignored}）。 */
    public static final String MSG_STATUS_INVALID = "草案状态不合法，无法认领";

    /** 忽略被拒的提示文案（已认领草案已绑定档案，"忽略"会让状态与事实互相矛盾）。 */
    public static final String MSG_ALREADY_CLAIMED_CANNOT_IGNORE = "该草案已认领，不能忽略";

    /** 忽略时状态非法的提示文案。 */
    public static final String MSG_STATUS_INVALID_IGNORE = "草案状态不合法，无法忽略";

    /* ==================== 参与映射的合同类型码 ==================== */

    /** 合同类型码：采购/支出 → 扫描乙方，落到<b>供应商</b>方向。 */
    public static final String TYPE_PURCHASE = "PUR";

    /** 合同类型码：销售/收入 → 扫描甲方，落到<b>客户</b>方向。 */
    public static final String TYPE_SALES = "SAL";

    /** 三个合法草案状态（只读，顺序即状态机文档顺序）。 */
    private static final List<String> STATUSES = Collections.unmodifiableList(
            Arrays.asList(STATUS_PENDING, STATUS_CLAIMED, STATUS_IGNORED));

    /**
     * <p> 分组键用的排序器：<b>强度取 PRIMARY</b>，即忽略大小写与重音的等价判定。 </p>
     *
     * <p> 它必须与 {@code t_ctms_party_draft} 上唯一索引 {@code uk_party_draft(party_type, raw_name)}
     * 的排序规则同口径：该表的排序规则是 {@code utf8mb4_0900_ai_ci}（{@code _ci} = case insensitive、
     * {@code ai} = accent insensitive）。若 Java 侧按大小写敏感分组，{@code Acme} 与 {@code acme} 会
     * 分成两组、第二次 insert 直接撞唯一键（真库报 1062，接口 500），而内存桩单测发现不了
     * —— 桩不模拟数据库排序规则。故这里显式对齐，见 {@link #rawNameKey(String)}。 </p>
     */
    private static final Collator RAW_NAME_COLLATOR = primaryCollator();

    private MigrationRules()
    {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 合同类型 → 档案方向的映射。
     *
     * @param contractType 合同类型（{@code t_ctms_contract.type}，存的是 3 位类型码）
     * @return {@link #DIRECTION_SUPPLIER}（PUR）/ {@link #DIRECTION_CUSTOMER}（SAL）；
     *         <b>不参与映射或类型为空/未知时返回 {@code null}</b>（调用方据此跳过）
     */
    public static String directionOf(String contractType)
    {
        String type = ContractRules.trimToNull(contractType);
        if (TYPE_PURCHASE.equals(type))
        {
            return DIRECTION_SUPPLIER;
        }
        if (TYPE_SALES.equals(type))
        {
            return DIRECTION_CUSTOMER;
        }
        return null;
    }

    /**
     * 该合同类型是否参与档案方向映射。
     *
     * @param contractType 合同类型（类型码）
     * @return 参与返回 true
     */
    public static boolean participates(String contractType)
    {
        return directionOf(contractType) != null;
    }

    /**
     * 给定值是否是一个合法的<b>档案方向</b>（{@code customer} / {@code supplier}）。
     *
     * <p> 用途：读回来的草案 {@code party_type} 必须先过这一关再拿去决定"写哪一列" ——
     * 脏数据（被绕过接口改过的方向值）如果直接进 SQL，会变成"写错列"或"一列都不写"的静默错误。 </p>
     *
     * @param direction 档案方向
     * @return 合法返回 true
     */
    public static boolean isDirection(String direction)
    {
        String value = ContractRules.trimToNull(direction);
        return DIRECTION_CUSTOMER.equals(value) || DIRECTION_SUPPLIER.equals(value);
    }

    /**
     * 取某方向的<b>原始文本</b>（草案的 {@code raw_name}）。
     *
     * <p> 供应商方向取乙方文本、客户方向取甲方文本；空白一律归一为 {@code null}
     * （规格口径是「该方向文本非空」才生成草案）。 </p>
     *
     * @param direction 档案方向
     * @param partyA    合同的甲方文本
     * @param partyB    合同的乙方文本
     * @return 归一后的文本；方向未知或文本空白时返回 {@code null}
     */
    public static String rawTextOf(String direction, String partyA, String partyB)
    {
        if (DIRECTION_SUPPLIER.equals(direction))
        {
            return normalizeRawName(partyB);
        }
        if (DIRECTION_CUSTOMER.equals(direction))
        {
            return normalizeRawName(partyA);
        }
        return null;
    }

    /**
     * 取某方向的<b>档案引用</b>（判断"是否已绑定该方向档案"）。
     *
     * <p> 供应商方向看 {@code supplier_id}、客户方向看 {@code customer_id}；
     * <b>只看本方向的列</b>：一份采购合同即使误填了客户引用，也不影响它按乙方文本进供应商方向的草案。 </p>
     *
     * @param direction  档案方向
     * @param customerId 合同的客户档案ID
     * @param supplierId 合同的供应商档案ID
     * @return 该方向的档案ID；方向未知或未绑定时返回 {@code null}
     */
    public static String referenceIdOf(String direction, String customerId, String supplierId)
    {
        if (DIRECTION_SUPPLIER.equals(direction))
        {
            return ContractRules.trimToNull(supplierId);
        }
        if (DIRECTION_CUSTOMER.equals(direction))
        {
            return ContractRules.trimToNull(customerId);
        }
        return null;
    }

    /**
     * <p> 原始文本归一：<b>只去首尾 ASCII 空格（U+0020）</b>，全空白归一为 {@code null}。 </p>
     *
     * <p> <b>为什么不直接用 Java 的 {@code String.trim()}</b>：{@code trim()} 去掉的是所有
     * {@code <= 0x20} 的字符（空格、制表、换行、回车…），而认领候选是在 <b>SQL 里</b>比较的，
     * MySQL 的 {@code TRIM()} <b>默认只去空格</b>。两侧只要有一类字符"Java 去、SQL 不去"，
     * 就会出现最难查的一类形态：**扫描把这些合同聚成一条草案（计数 &gt; 0），认领却一条也匹配不上
     * （实绑 0）**，下一次扫描又发现它们仍未绑定 → 草案在 {@code claimed} 与 {@code pending} 之间永久往复
     * （t16 round 1 的 F1）。两侧同口径的最简做法是<b>都只去空格</b>：本方法去首尾 0x20，
     * 候选查询用 {@code trim(c.party_a/party_b) = trim(#{rawName})}（MySQL 的 {@code TRIM()} 也只去 0x20）。 </p>
     *
     * <table border="1">
     *   <caption>两侧同口径表（本机 MySQL 8 实测，见 t26 交付记录）</caption>
     *   <tr><th>字符</th><th>本方法（Java）</th><th>MySQL {@code TRIM()}</th><th>一致？</th></tr>
     *   <tr><td>ASCII 空格 U+0020</td><td>去</td><td>去</td><td>✅</td></tr>
     *   <tr><td>Tab U+0009</td><td><b>保留</b></td><td><b>保留</b></td><td>✅</td></tr>
     *   <tr><td>换行 U+000A / 回车 U+000D</td><td><b>保留</b></td><td><b>保留</b></td><td>✅</td></tr>
     *   <tr><td>全角空格 U+3000</td><td>保留</td><td>保留</td><td>✅</td></tr>
     *   <tr><td>NBSP U+00A0</td><td>保留</td><td>保留</td><td>✅</td></tr>
     * </table>
     *
     * <p> 五类字符<b>没有一类走"两侧不同"的路</b>：要么两侧都去（空格），要么两侧都留（其余）。
     * ⚠ 因此本方法<b>不能</b>改回 {@code String.trim()}，也不能让 SQL 侧改成
     * {@code trim(both ' \t\n\r\v\f' from …)}（MySQL 的 {@code TRIM(remstr)} 是把 remstr
     * 当成"整串重复"来去的，不是字符集合，实测 {@code '\tx\t'} 原样返回）；改一侧就必须同步改另一侧，
     * 并由 {@code CtmsMigrationServiceImplTest} 的空格/Tab/全角三类回归用例 + 真库脚本
     * {@code tools/ctms-migration-check.ps1} 同时钉住。 </p>
     *
     * <p> <b>刻意不做长度截断</b>：源列（{@code t_ctms_contract.party_a/party_b}）与目标列
     * （{@code t_ctms_party_draft.raw_name}）都是 {@code varchar(255)}，宽度天然一致，不需要截断；
     * 而截断会把两个不同的长名称静默合并成一条草案，比让数据库按列宽报错更难发现
     * （对照列宽的口径见 {@code DEV-ENV.md} §6.39）。 </p>
     *
     * @param rawName 原始文本
     * @return 归一后的文本（只去首尾空格）；全空白返回 {@code null}
     */
    public static String normalizeRawName(String rawName)
    {
        String stripped = trimSpaces(rawName);
        // 空白判定仍按 Java 口径（"全是空白"＝没有文本，不建草案）；
        // 但**返回值**只做去空格，保证与 SQL 的 TRIM 同口径（制表/换行/全角空格原样保留，两侧一致）。
        return ContractRules.trimToNull(stripped) == null ? null : stripped;
    }


    /**
     * <p> 去掉首尾的 <b>ASCII 空格（U+0020）</b>，其余字符原样保留。 </p>
     *
     * <p> 与 SQL 侧 {@code trim(col)} 严格同口径（MySQL {@code TRIM()} 默认只去 0x20），
     * 用途见 {@link #normalizeRawName(String)} 的同口径表。 </p>
     *
     * @param value 原始值
     * @return 去首尾空格后的值；入参为 {@code null} 时返回 {@code null}
     */
    public static String trimSpaces(String value)
    {
        if (value == null)
        {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ')
        {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ')
        {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * <p> 草案的<b>分组键</b>：按唯一索引 {@code uk_party_draft(party_type, raw_name)} 的等价语义
     * 归一（大小写不敏感 + 重音不敏感）。 </p>
     *
     * <p> 用途：扫描时按「档案方向 + 本键」聚合，保证同一等价类只会产生一条草案。
     * 展示用的 {@code raw_name} 仍保留首次出现的原文（不做大小写改写），
     * 所以库里的值始终是"用户当初写的样子"。 </p>
     *
     * @param rawName 原始文本（须已归一）
     * @return 分组键（可直接作为 Map 的键；同一等价类返回同一个字符串）
     */
    public static String rawNameKey(String rawName)
    {
        if (rawName == null)
        {
            return "";
        }
        // CollationKey 的字节串是对该排序规则下"等价类"的稳定编码：
        // 同一等价类（大小写/重音不同）得到同一字节串，可直接当 Map 键。
        return new String(RAW_NAME_COLLATOR.getCollationKey(rawName).toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /**
     * 草案状态是否为三个已知取值之一。
     *
     * <p> 用于认领/忽略入口的守卫：状态机只允许 pending/claimed/ignored，
     * 未知状态说明数据被绕过接口改过，应当拒绝而不是按猜测继续流转。 </p>
     *
     * @param status 状态
     * @return 已知返回 true
     */
    public static boolean isKnownStatus(String status)
    {
        return STATUSES.contains(ContractRules.trimToNull(status));
    }

    /**
     * 三个合法草案状态（只读清单，供文档与自检使用）。
     *
     * @return 状态清单（顺序 pending / claimed / ignored）
     */
    public static List<String> statuses()
    {
        return STATUSES;
    }

    /* ==================== 认领状态机（任务 7.2 交付判定，7.3 落地批量绑定） ==================== */

    /**
     * <p> 草案是否<b>可被认领</b>。 </p>
     *
     * <p> 口径（规格 {@code ctms/contract-migration} 的「草案认领与批量绑定」）： </p>
     * <ul>
     *   <li> {@code pending} → 可认领； </li>
     *   <li> {@code ignored} → <b>可</b>重新认领（"忽略"是暂缓，不是永久否决）； </li>
     *   <li> {@code claimed} → <b>不可</b>重复认领（否则会二次改写同一批合同的档案引用与变更历史）； </li>
     *   <li> 其它/为空 → 不可（状态机只有这三个取值，未知状态说明数据被绕过接口改过）。 </li>
     * </ul>
     *
     * @param status 草案状态
     * @return 可认领返回 true
     */
    public static boolean claimable(String status)
    {
        String value = ContractRules.trimToNull(status);
        return STATUS_PENDING.equals(value) || STATUS_IGNORED.equals(value);
    }

    /**
     * 认领守卫：不可认领时抛业务异常（文案见 {@link #MSG_ALREADY_CLAIMED} / {@link #MSG_STATUS_INVALID}）。
     *
     * <p> 判定收敛在这里一处，是为了让 7.3 的"新建档案后绑定"与"绑定已有档案"两条写入路径
     * 共用同一个状态守卫 —— 各写一份判定迟早会出现"一条路径放行、另一条拒绝"的分叉。 </p>
     *
     * @param status 草案状态
     * @throws com.ruoyi.common.exception.ServiceException 已认领或状态非法
     */
    public static void checkClaimable(String status)
    {
        String value = ContractRules.trimToNull(status);
        if (STATUS_CLAIMED.equals(value))
        {
            throw new ServiceException(MSG_ALREADY_CLAIMED);
        }
        if (!claimable(value))
        {
            throw new ServiceException(MSG_STATUS_INVALID);
        }
    }

    /**
     * <p> 草案是否<b>可被忽略</b>。 </p>
     *
     * <p> 口径：{@code pending} 可忽略；{@code ignored} 可重复忽略（幂等）；{@code claimed}
     * <b>不可</b>忽略 —— 它已经把档案引用写进了历史合同，"忽略"会让草案状态与合同事实互相矛盾
     * （合同已绑定，草案却显示"不需要档案化"）；其它/为空不可忽略。 </p>
     *
     * @param status 草案状态
     * @return 可忽略返回 true
     */
    public static boolean ignorable(String status)
    {
        String value = ContractRules.trimToNull(status);
        return STATUS_PENDING.equals(value) || STATUS_IGNORED.equals(value);
    }

    /**
     * 忽略守卫：不可忽略时抛业务异常（文案见 {@link #MSG_ALREADY_CLAIMED_CANNOT_IGNORE} /
     * {@link #MSG_STATUS_INVALID_IGNORE}）。
     *
     * @param status 草案状态
     * @throws com.ruoyi.common.exception.ServiceException 已认领或状态非法
     */
    public static void checkIgnorable(String status)
    {
        String value = ContractRules.trimToNull(status);
        if (STATUS_CLAIMED.equals(value))
        {
            throw new ServiceException(MSG_ALREADY_CLAIMED_CANNOT_IGNORE);
        }
        if (!ignorable(value))
        {
            throw new ServiceException(MSG_STATUS_INVALID_IGNORE);
        }
    }

    /**
     * 构造强度为 PRIMARY 的排序器。
     *
     * @return 排序器
     */
    private static Collator primaryCollator()
    {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }
}
