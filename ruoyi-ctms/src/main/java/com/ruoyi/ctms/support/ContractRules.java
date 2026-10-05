package com.ruoyi.ctms.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsContractItem;

/**
 * <p> 合同台账的<b>纯规则</b>（金额与数量口径、质保到期算法、恢复窗口、状态取值、字段回落与摘要），
 * 规格 ctms/contract-commercials 与 ctms/contract-ledger。 </p>
 *
 * <p> 设计口径与 {@code PartnerRules} 一致：本类<b>不依赖 Spring 容器、数据库与登录上下文</b>，
 * 全部是静态纯函数，因此可以做成表驱动单测，并<b>逐字</b>锁定提示文案与边界值
 * （文案是接口契约的一部分，改动即视为破坏性变更）。 </p>
 *
 * <p> <b>金额口径（C-1，规格 ctms/contract-commercials）</b>：行总价 = 数量 × 单价，
 * <b>先</b>按 HALF_UP 舍入到 {@link #SCALE_AMOUNT} 位，合同金额是各<b>已舍入</b>行总价之和；
 * 先汇总再一次性舍入会在小数单价场景少 1 分钱（判别用例：3 行「数量 1 × 单价 1.665」
 * 正确结果是 1.67 + 1.67 + 1.67 = 5.01，先汇总再舍入会得到 5.00）。 </p>
 *
 * <p> 全部计算走 {@link BigDecimal}，本类不得出现二进制浮点类型（规格 {@code REQ-NFR-007}）。 </p>
 *
 * <p> 边界与取舍（都已在单测里锁定，改前先看这里）： </p>
 * <ul>
 *   <li> {@link #lineTotal} 的 null 入参视同 0（行项刚建、单价还没填时不至于抛异常）； </li>
 *   <li> {@link #paidRate} 的分母为空或 0 时返回 {@code null}（规格要求「返回空值而非报错」，
 *        前端展示为空而不是 0%）； </li>
 *   <li> 质保金额与比例互换时合同金额为空或 0 会抛业务异常（没有任何比例是「有意义的」，
 *        静默返回 0 会让用户以为填成功了）； </li>
 *   <li> {@link #computeWarrantyEnd} 的期限为 null 或小于 1 时按 1 处理（规格「至少 1 个月」）； </li>
 *   <li> {@link #daysBetween} 只看日期不看时刻（恢复窗口按整数日差判定），入参为空抛业务异常 ——
 *        静默返回 0 会让「未停用/时间缺失」的合同看起来永远在窗口内。 </li>
 * </ul>
 *
 * @author 二开
 */
public final class ContractRules
{
    /** 金额舍入模式：四舍五入（禁止改成其它模式，接口验收会比对） */
    public static final RoundingMode MODE = RoundingMode.HALF_UP;

    /** 金额小数位 */
    public static final int SCALE_AMOUNT = 2;

    /** 数量小数位（沿用 decimal(12,3)） */
    public static final int SCALE_QTY = 3;

    /** 单价小数位（decimal(14,4)） */
    public static final int SCALE_PRICE = 4;

    /** 恢复窗口天数：停用时间到恢复时刻的整数日差不超过该值即可恢复 */
    public static final int RESTORE_WINDOW_DAYS = 30;

    /**
     * <p> 变更历史的字段名：{@code 客户档案}。 </p>
     *
     * <p> <b>这是单一真源</b>：合同整单编辑（{@code CtmsContractServiceImpl}）与迁移认领
     * （{@code CtmsMigrationServiceImpl} 按原始名称批量回填）都会写这个字段名的历史记录 ——
     * 两处必须同字，否则时间线里会出现两个看起来不同的字段（前端按字段名分组展示）。 </p>
     */
    public static final String FIELD_CUSTOMER = "客户档案";

    /**
     * 变更历史的字段名：{@code 供应商档案}（与 {@link #FIELD_CUSTOMER} 同款，见其注释）。
     */
    public static final String FIELD_SUPPLIER = "供应商档案";

    /**
     * <p> 变更历史的对象类型：{@code contract}。 </p>
     *
     * <p> <b>单一真源</b>：合同整单编辑与迁移认领写的都是合同对象的日志，{@code object_type} 必须同字
     * （它同时是"这条日志属于哪类对象"的定位键，B4 单据侧会写别的值）。 </p>
     */
    public static final String OBJECT_TYPE_CONTRACT = "contract";

    /** 比例小数位（质保比例、付款比例统一 4 位） */
    private static final int SCALE_RATE = 4;

    /** 百分比换算因子 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 金额零值（带 scale，保证输出形态稳定为 0.00） */
    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(SCALE_AMOUNT, MODE);

    /** 摘要多条之间的连接符（规格：多条以分号连接） */
    private static final char ITEM_SEPARATOR = '；';

    /** 摘要里「名称(规格)×数量」的乘号 */
    private static final char QTY_SIGN = '×';

    private ContractRules()
    {
        // 纯函数工具类，禁止实例化
    }

    /* ==================== 金额口径（C-1） ==================== */

    /**
     * 行总价 = 数量 × 单价，<b>先</b>按 HALF_UP 舍入到 2 位；null 视同 0。
     *
     * <p> 舍入发生在行级：合同金额必须是本方法返回值的和，不能拿未舍入的乘积去求和。 </p>
     *
     * @param qty 数量（可空，空视同 0）
     * @param unitPrice 单价（可空，空视同 0）
     * @return 行总价（scale = 2）
     */
    public static BigDecimal lineTotal(BigDecimal qty, BigDecimal unitPrice)
    {
        BigDecimal q = (qty == null ? BigDecimal.ZERO : qty);
        BigDecimal p = (unitPrice == null ? BigDecimal.ZERO : unitPrice);
        return q.multiply(p).setScale(SCALE_AMOUNT, MODE);
    }

    /**
     * 合同金额 = 各<b>已舍入</b>行总价之和；空列表返回 0.00。
     *
     * <p> 入参必须是 {@link #lineTotal} 的结果（或库中 scale=2 的行总价），
     * 本方法只做加法与 scale 归一，不再做「汇总后舍入」的补救。 </p>
     *
     * @param lineTotals 已舍入的行总价集合（可空，集合内的 null 元素会被跳过）
     * @return 合同金额（scale = 2）
     */
    public static BigDecimal sumLineTotals(List<BigDecimal> lineTotals)
    {
        if (lineTotals == null || lineTotals.isEmpty())
        {
            return ZERO_AMOUNT;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal line : lineTotals)
        {
            if (line != null)
            {
                sum = sum.add(line);
            }
        }
        return sum.setScale(SCALE_AMOUNT, MODE);
    }

    /**
     * 付款比例 = 累计已付 ÷ 合同金额 × 100，4 位 HALF_UP。
     *
     * <p> 分子分母都<b>不</b>扣除质量保证金（规格明确要求）。合同金额为空或 0 时返回
     * {@code null}（返回空值而不是抛异常），累计已付为空时按 0 处理。 </p>
     *
     * @param paidAmount 累计已付金额（可空）
     * @param amount 合同金额
     * @return 付款比例（scale = 4）；合同金额为空或 0 时返回 null
     */
    public static BigDecimal paidRate(BigDecimal paidAmount, BigDecimal amount)
    {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)
        {
            return null;
        }
        BigDecimal paid = (paidAmount == null ? BigDecimal.ZERO : paidAmount);
        return paid.multiply(HUNDRED).divide(amount, SCALE_RATE, MODE);
    }

    /**
     * 质保比例 → 质保金额：合同金额 × 比例 ÷ 100，2 位 HALF_UP。
     *
     * @param amount 合同金额（不能为空或 0）
     * @param rate 质保比例（百分数，可空，空视同 0）
     * @return 质保金额（scale = 2）
     * @throws ServiceException 合同金额为空或 0
     */
    public static BigDecimal warrantyAmountOfRate(BigDecimal amount, BigDecimal rate)
    {
        checkAmountForWarranty(amount);
        BigDecimal r = (rate == null ? BigDecimal.ZERO : rate);
        return amount.multiply(r).divide(HUNDRED, SCALE_AMOUNT, MODE);
    }

    /**
     * 质保金额 → 质保比例：金额 ÷ 合同金额 × 100，4 位 HALF_UP。
     *
     * @param amount 合同金额（不能为空或 0）
     * @param warrantyAmount 质保金额（可空，空视同 0）
     * @return 质保比例（百分数，scale = 4）
     * @throws ServiceException 合同金额为空或 0
     */
    public static BigDecimal warrantyRateOfAmount(BigDecimal amount, BigDecimal warrantyAmount)
    {
        checkAmountForWarranty(amount);
        BigDecimal w = (warrantyAmount == null ? BigDecimal.ZERO : warrantyAmount);
        return w.multiply(HUNDRED).divide(amount, SCALE_RATE, MODE);
    }

    /* ==================== 质保日期算法 ==================== */

    /**
     * 年月进位加月：先整体加月，日取「原日 与 目标月最大日 的较小值」，时刻保持不变。
     *
     * <p> 例：2026-01-31 加 1 个月 = 2026-02-28（2 月没有 31 日，归一到月末）。 </p>
     *
     * @param start 起始日期（不能为空）
     * @param months 月数（可为负）
     * @return 加月后的日期
     * @throws ServiceException 起始日期为空
     */
    public static java.util.Date addMonths(java.util.Date start, int months)
    {
        if (start == null)
        {
            throw new ServiceException("日期不能为空");
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(start);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        // 先把日压到 1，避免 Calendar 在「1 月 31 日 + 1 个月」时先溢出再回卷
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.add(Calendar.MONTH, months);
        int maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        calendar.set(Calendar.DAY_OF_MONTH, Math.min(day, maxDay));
        return calendar.getTime();
    }

    /**
     * 质保到期日 = 生效日 + (max(1, 月数) − 1) 个月，再取该结果所在月的最后一天。
     *
     * <p> 刻意不是「生效日 + 月数」：规格的口径是「生效日 2025-06-01、期限 12 个月 → 2026-05-31」，
     * 即期限是「覆盖的整月数」，末月按整月处理。 </p>
     *
     * @param start 质保生效日（不能为空）
     * @param months 质保期限（月；为 null 或小于 1 时按 1 处理）
     * @return 质保到期日（当天 00:00 起的同一时刻）
     * @throws ServiceException 生效日为空
     */
    public static java.util.Date computeWarrantyEnd(java.util.Date start, Integer months)
    {
        if (start == null)
        {
            throw new ServiceException("日期不能为空");
        }
        int actualMonths = (months == null ? 1 : months.intValue());
        if (actualMonths < 1)
        {
            actualMonths = 1;
        }
        java.util.Date target = addMonths(start, actualMonths - 1);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(target);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        return calendar.getTime();
    }

    /**
     * 把日期归一到<b>当天零点</b>（质保提醒的"今天/到期日"比较基准）。
     *
     * <p> 存在的理由：{@code warranty_end} 是 MySQL 的 {@code date} 列，JDBC 取回来可能是
     * {@code java.sql.Date}（零点）也可能是带时刻的 {@code java.util.Date}。
     * 提醒判定"到期日不早于当天"是<b>日期</b>语义，直接比较时刻会让同一天的记录因毫秒差异
     * 被判成"已到期"或"还没到期"，所以两边都先归一到零点再比。 </p>
     *
     * @param date 时间（可空）
     * @return 当天零点；入参为空返回 null
     */
    public static java.util.Date atStartOfDay(java.util.Date date)
    {
        if (date == null)
        {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 日期加减天数（质保提醒窗口的右端点 = 当天 + 窗口天数）。
     *
     * @param date 基准日期（可空）
     * @param days 天数（可为负）
     * @return 结果日期；入参为空返回 null
     */
    public static java.util.Date plusDays(java.util.Date date, int days)
    {
        if (date == null)
        {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }

    /**
     * 解析 {@code yyyy-MM-dd} 参考日期（编号预览接口的 HTTP 入参）。
     *
     * @param text 日期文本（可空）
     * @return 日期；入参为空返回 null
     * @throws ServiceException 文本非空但格式不合法
     */
    public static java.util.Date parseDay(String text)
    {
        String value = trimToNull(text);
        if (value == null)
        {
            return null;
        }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
        format.setLenient(false);
        try
        {
            return format.parse(value);
        }
        catch (java.text.ParseException e)
        {
            throw new ServiceException("参考日期格式错误，应为 yyyy-MM-dd：" + value);
        }
    }

    /**
     * 系统参数的整数取值（质保提醒窗口）。
     *
     * <p><b>为什么读不出来时回落默认值而不是抛异常</b>：提醒窗口是"展示口径"，
     * 参数缺失/写成非数字时让看板整体 500 是不可接受的；回落 {@code fallback} 并保持行为可预期，
     * 参数一旦被正确配置就立刻生效（规格场景："改参数后行为随之变化"）。 </p>
     *
     * @param text     参数原始值（可空）
     * @param fallback 回落值
     * @return 解析结果；无法解析时返回 fallback
     */
    public static int intValue(String text, int fallback)
    {
        String value = trimToNull(text);
        if (value == null)
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    /**
     * 两个时间的<b>整数日差</b>（只看日期，不看时刻），后者减前者。
     *
     * <p> 例：2026-03-01 09:00 到 2026-03-31 08:00 的结果是 30（不是 29）。 </p>
     *
     * @param from 起始时间（不能为空）
     * @param to 结束时间（不能为空）
     * @return 整数日差（可为负）
     * @throws ServiceException 任一时间为空
     */
    public static long daysBetween(java.util.Date from, java.util.Date to)
    {
        if (from == null || to == null)
        {
            throw new ServiceException("日期不能为空");
        }
        return ChronoUnit.DAYS.between(toLocalDate(from), toLocalDate(to));
    }

    /**
     * 是否还在 30 天恢复窗口内（整数日差不超过 {@link #RESTORE_WINDOW_DAYS}）。
     *
     * <p> 停用时间为空时返回 {@code false}：没有停用时间的行不适用恢复窗口，
     * 不能因为「算不出来」就放行。 </p>
     *
     * @param deletedAt 停用时间
     * @param now 参考时间（通常是当前时间）
     * @return true = 允许恢复
     */
    public static boolean isWithinRestoreWindow(java.util.Date deletedAt, java.util.Date now)
    {
        if (deletedAt == null || now == null)
        {
            return false;
        }
        return daysBetween(deletedAt, now) <= RESTORE_WINDOW_DAYS;
    }

    /* ==================== 状态取值校验 ==================== */

    /**
     * 校验进度状态取值是否合法（合法集合由调用方从字典取出后传入）。
     *
     * @param status 待校验状态
     * @param allowed 合法状态集合（配置驱动，本类不写死默认值）
     * @throws ServiceException 取值为空或不在集合内（文案含「无效状态」）
     */
    public static void checkStatusIn(String status, Collection<String> allowed)
    {
        String value = trimToNull(status);
        if (value == null || allowed == null || !allowed.contains(value))
        {
            throw new ServiceException("无效状态：" + status);
        }
    }

    /**
     * 校验到货状态取值是否合法。
     *
     * <p> 到货状态与进度状态<b>互相独立</b>：两边各自校验，不联动改写。 </p>
     *
     * @param arrivalStatus 待校验到货状态
     * @param allowed 合法到货状态集合
     * @throws ServiceException 取值为空或不在集合内（文案含「无效到货状态」）
     */
    public static void checkArrivalStatusIn(String arrivalStatus, Collection<String> allowed)
    {
        String value = trimToNull(arrivalStatus);
        if (value == null || allowed == null || !allowed.contains(value))
        {
            throw new ServiceException("无效到货状态：" + arrivalStatus);
        }
    }

    /* ==================== 行项回落与摘要 ==================== */

    /**
     * 行项名称回落：行项名称为空（纯空白也算空）时用物料档案名称。
     *
     * @param itemName 行项名称
     * @param productName 物料档案名称
     * @return 回落后的名称；两者都为空时返回 null
     */
    public static String itemNameOf(String itemName, String productName)
    {
        String value = trimToNull(itemName);
        return (value != null ? value : trimToNull(productName));
    }

    /**
     * 行项规格回落：行项规格为空（纯空白也算空）时用物料档案规格。
     *
     * @param itemSpec 行项规格
     * @param productSpec 物料档案规格
     * @return 回落后的规格；两者都为空时返回 null
     */
    public static String itemSpecOf(String itemSpec, String productSpec)
    {
        String value = trimToNull(itemSpec);
        return (value != null ? value : trimToNull(productSpec));
    }

    /**
     * 标的物摘要：「名称(规格)×数量」多条以分号连接；名称为空回落物料名称，
     * 规格为空时省略括号（规格没有「物料规格」可回落 —— 它不在行项表里，由调用方自行决定）。
     *
     * <p> 数量按去尾零输出（2.000 → 2，2.500 → 2.5），避免摘要里出现「×2.000」这种观感。 </p>
     *
     * @param items 行项集合（可空）
     * @return 摘要文本；无行项时返回空串
     */
    public static String subjectMatterOf(List<CtmsContractItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (CtmsContractItem item : items)
        {
            if (item == null)
            {
                continue;
            }
            String name = itemNameOf(item.getName(), item.getProductName());
            if (name == null)
            {
                // 名称与物料名称都为空：这一行没有任何可读信息，跳过而不是产出「(DN50)×2」
                continue;
            }
            String spec = trimToNull(item.getSpec());
            if (summary.length() > 0)
            {
                summary.append(ITEM_SEPARATOR);
            }
            summary.append(name);
            if (spec != null)
            {
                summary.append('(').append(spec).append(')');
            }
            summary.append(QTY_SIGN).append(formatQty(item.getQty()));
        }
        return summary.toString();
    }

    /* ==================== 通用小工具 ==================== */

    /**
     * 逻辑判空：null 或纯空白（含全角空格）都算空。
     *
     * @param value 待判定字符串
     * @return true = 空
     */
    public static boolean isBlank(String value)
    {
        return trimToNull(value) == null;
    }

    /**
     * 逻辑非空去空白：空（纯空白）返回 null，否则返回去掉首尾空白后的字符串。
     *
     * @param value 待处理字符串
     * @return 去空白后的字符串，或 null
     */
    public static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        int length = value.length();
        int start = 0;
        while (start < length && Character.isWhitespace(value.charAt(start)))
        {
            start++;
        }
        if (start == length)
        {
            return null;
        }
        int end = length;
        while (end > start && Character.isWhitespace(value.charAt(end - 1)))
        {
            end--;
        }
        return value.substring(start, end);
    }

    /* ==================== 私有实现 ==================== */

    /** 质保换算的前置条件：合同金额必须存在且不为 0 */
    private static void checkAmountForWarranty(BigDecimal amount)
    {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)
        {
            throw new ServiceException("合同金额为空或 0，无法换算质保金额");
        }
    }

    /** 取日期部分（只用年/月/日，刻意绕开 java.sql.Date 对 toInstant 的限制） */
    private static LocalDate toLocalDate(java.util.Date date)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return LocalDate.of(calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** 数量文本：去尾零且不用科学计数法（2.000 → 2） */
    private static String formatQty(BigDecimal qty)
    {
        BigDecimal value = (qty == null ? BigDecimal.ZERO : qty);
        if (value.compareTo(BigDecimal.ZERO) == 0)
        {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
