package com.ruoyi.ctms.erp.base;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;

/**
 * <p> <b>8 类单据的唯一取号入口</b>（2.0 B4 任务 1.3；design D9 的"平台能力零重复实现"）。 </p>
 *
 * <p> <b>铁律：单据单号只允许通过本类获取，各组不得自算号。</b>
 * 取号要并发安全、要可审计，这些都已由平台 {@code ruoyi-serial} 提供
 * （Redis 计数器 + 分布式锁 + {@code t_code_sequence_log} 流水）；
 * 在 8 个单据服务里各写一遍"前缀 + 日期 + 流水"必然漂移，也无法统一换号规则。 </p>
 *
 * <p> <b>编号形态</b>：{@code {前缀}{yyyyMM}{6 位序号}}，例 {@code PR202610000001}。 </p>
 * <ul>
 *   <li> 前缀与配置 id 的映射见下面的常量（与 {@code sql/二开-进销存.sql} ③ 段的
 *        8 条 {@code t_code_config} 逐字一致 —— <b>两处真源，改一处必须改两处</b>）； </li>
 *   <li> 年月由 {@code CodeGenContext.referenceDate}（默认当天；建议传单据日期）格式化，
 *        并以业务参数 {@link #PARAM_YM} 传给编号服务；
 *        <b>该参数同时决定计数器分桶</b>（键含 {@code ym=yyyyMM}），
 *        于是"按月重置"是<b>惰性</b>成立的（新月份第一个号必然是 {@code 000001}，
 *        不依赖每月 1 号的定时任务）——理由与实测见 SQL 文件 ③ 段的说明。 </li>
 * </ul>
 *
 * <p> <b>调用时机</b>：只在<b>创建（insert）</b>路径取一次；编辑/流转<b>不得</b>重新取号
 * （否则单号会变，而单号是外部凭证标识）。 </p>
 *
 * @author 二开
 */
@Component
public class ErpDocNoGenerator
{
    /** 采购申请单编号配置 id（前缀 PR）。 */
    public static final String CONF_ID_PURCHASE_REQUEST = "9F2C0000000000000000000000C101";

    /** 采购单编号配置 id（前缀 PO）。 */
    public static final String CONF_ID_PURCHASE_ORDER = "9F2C0000000000000000000000C102";

    /** 销售申请单编号配置 id（前缀 SR）。 */
    public static final String CONF_ID_SALES_REQUEST = "9F2C0000000000000000000000C103";

    /** 销售订单编号配置 id（前缀 SO）。 */
    public static final String CONF_ID_SALES_ORDER = "9F2C0000000000000000000000C104";

    /** 入库单编号配置 id（前缀 IN）。 */
    public static final String CONF_ID_STOCK_IN = "9F2C0000000000000000000000C105";

    /** 出库单编号配置 id（前缀 OUT）。 */
    public static final String CONF_ID_STOCK_OUT = "9F2C0000000000000000000000C106";

    /** 盘点单编号配置 id（前缀 ST）。 */
    public static final String CONF_ID_STOCK_TAKE = "9F2C0000000000000000000000C107";

    /** 调拨单编号配置 id（前缀 DB）。 */
    public static final String CONF_ID_STOCK_TRANSFER = "9F2C0000000000000000000000C108";

    /** 业务参数名：{@code yyyyMM}（渲染进编号 + 作为计数器分桶键）。 */
    public static final String PARAM_YM = "ym";

    /** 编号格式说明（文档与错误提示共用，避免各处各写一个样例）。 */
    public static final String FORMAT_PATTERN = "{前缀}{yyyyMM}{6 位序号}";

    /** 年月格式。 */
    public static final String YM_PATTERN = "yyyyMM";

    private final ICodeGenService codeGenService;

    /**
     * 构造注入（Spring 4.3+ 对"唯一构造器"自动注入，无需 {@code @Autowired}；
     * 单测也可以直接用桩构造，不必起容器）。
     *
     * @param codeGenService 平台编号服务；直接 new 时可为 null（此时取号会明确报错）
     */
    public ErpDocNoGenerator(ICodeGenService codeGenService)
    {
        this.codeGenService = codeGenService;
    }

    /**
     * 取下一个单据号（参考日期 = 服务器当天）。
     *
     * @param type 单据类型
     * @return 形如 {@code PR202610000001} 的单号
     * @throws ServiceException 单据类型为空 / 编号服务未装配 / 取号失败
     */
    public String nextDocNo(ErpDocType type)
    {
        return nextDocNo(type, null);
    }

    /**
     * 取下一个单据号。
     *
     * <p> 建议调用方传<b>单据日期</b>作为参考日期：这样"补录上月单据"会落在上月的号段里
     * （与 {@code doc_date} 一致），而不会把当前月的序号提前消耗掉。 </p>
     *
     * @param type          单据类型
     * @param referenceDate 参考日期（null → 服务器当天）
     * @return 单号
     * @throws ServiceException 取号失败
     */
    public String nextDocNo(ErpDocType type, Date referenceDate)
    {
        ErpDocType docType = requireType(type);
        if (codeGenService == null)
        {
            throw new ServiceException("编号服务未装配，无法为" + docType.getLabel() + "取号");
        }
        Date reference = referenceDate == null ? DateUtils.getNowDate() : referenceDate;
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_YM, DateUtils.parseDateToStr(YM_PATTERN, reference));
        try
        {
            return codeGenService.getNextCode(confIdOf(docType), new CodeGenContext(reference, params));
        }
        catch (Exception e)
        {
            // 把"哪一种单据、哪个配置、哪个日期"打进取号失败的原因里：
            // 裸的"获取编号失败！"在联调时没有信息量（CodeGenServiceImpl 的注释里同款教训）
            throw new ServiceException(docType.getLabel() + "取号失败（配置 " + confIdOf(docType)
                    + "，年月 " + params.get(PARAM_YM) + "）：" + describe(e));
        }
    }

    /**
     * 预览下一个单据号（<b>不占号</b>，用于表单上的编号提示）。
     *
     * @param type          单据类型
     * @param referenceDate 参考日期（null → 服务器当天）
     * @return 单号（连续两次预览返回同一个值）
     */
    public String previewDocNo(ErpDocType type, Date referenceDate)
    {
        ErpDocType docType = requireType(type);
        if (codeGenService == null)
        {
            throw new ServiceException("编号服务未装配，无法为" + docType.getLabel() + "预览编号");
        }
        Date reference = referenceDate == null ? DateUtils.getNowDate() : referenceDate;
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_YM, DateUtils.parseDateToStr(YM_PATTERN, reference));
        return codeGenService.previewNextCode(confIdOf(docType), new CodeGenContext(reference, params));
    }

    /**
     * 单据类型 → 编号配置 id（冻结映射；与 SQL 的 8 行 {@code t_code_config} 一致）。
     *
     * @param type 单据类型
     * @return 配置 id
     * @throws ServiceException 类型为空
     */
    public static String confIdOf(ErpDocType type)
    {
        ErpDocType docType = requireType(type);
        switch (docType)
        {
            case PURCHASE_REQUEST:
                return CONF_ID_PURCHASE_REQUEST;
            case PURCHASE_ORDER:
                return CONF_ID_PURCHASE_ORDER;
            case SALES_REQUEST:
                return CONF_ID_SALES_REQUEST;
            case SALES_ORDER:
                return CONF_ID_SALES_ORDER;
            case STOCK_IN:
                return CONF_ID_STOCK_IN;
            case STOCK_OUT:
                return CONF_ID_STOCK_OUT;
            case STOCK_TAKE:
                return CONF_ID_STOCK_TAKE;
            case STOCK_TRANSFER:
            default:
                return CONF_ID_STOCK_TRANSFER;
        }
    }

    /**
     * 单据类型 → 单号前缀（冻结映射）。
     *
     * @param type 单据类型
     * @return 前缀（PR/PO/SR/SO/IN/OUT/ST/DB）
     */
    public static String prefixOf(ErpDocType type)
    {
        ErpDocType docType = requireType(type);
        switch (docType)
        {
            case PURCHASE_REQUEST:
                return "PR";
            case PURCHASE_ORDER:
                return "PO";
            case SALES_REQUEST:
                return "SR";
            case SALES_ORDER:
                return "SO";
            case STOCK_IN:
                return "IN";
            case STOCK_OUT:
                return "OUT";
            case STOCK_TAKE:
                return "ST";
            case STOCK_TRANSFER:
            default:
                return "DB";
        }
    }

    /**
     * 年月的格式化（单测与文档共用）。
     *
     * @param date 日期
     * @return {@code yyyyMM}
     */
    public static String ymOf(Date date)
    {
        return DateUtils.parseDateToStr(YM_PATTERN, date);
    }

    private static ErpDocType requireType(ErpDocType type)
    {
        if (type == null)
        {
            throw new ServiceException("单据类型不能为空，无法取号");
        }
        return type;
    }

    private static String describe(Exception e)
    {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
