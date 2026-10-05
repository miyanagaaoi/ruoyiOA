package com.ruoyi.ctms.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> 附件业务语义的<b>纯规则</b>集合（2.0 B3 任务 6.1/6.2；design D-5）。 </p>
 *
 * <p> <b>为什么这些规则必须自建，而不能靠平台</b>（§1.5 的实测结论，见
 * {@code notes/attachment-reuse.md}）：平台 {@code /common/upload} 的白名单<b>比 CTMS 要求更宽</b>
 * （含 {@code ppt/html/rar/zip/mp4} 等 11 种），且平台上限是 <b>50MB</b>
 * （实测 25MB 被接受），返回的也是 {@code code=500} 而不是 413。所以：
 * 上传/存储/取回链路整体复用，但"更窄白名单 + ≤20MB + 413 + 按对象鉴权"在 CTMS 侧自建。 </p>
 *
 * <p> <b>本类不依赖 Spring / 不碰 IO / 不查库</b>：只做字符串与数值判定，便于表驱动单测；
 * 对象存在性校验（需要查库）在服务层。 </p>
 *
 * <p> <b>提示文案是接口契约</b>：后缀被拒的文案与平台 {@code FileUploadUtils.assertAllowed}
 * 的同款措辞保持一致（{@code 文件[x]后缀[y]不正确，请上传[...]格式}），
 * 避免"同一种错误两种文案"让前端与验收脚本各写一套匹配。 </p>
 *
 * @author 二开
 */
public final class CtmsAttachmentRules
{
    /** 单文件上限：20MB（平台是 50MB，见 §1.5 实测）。 */
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 上限的展示值（MB），用于报错文案与文档，避免两处各写一个 20。 */
    public static final long MAX_SIZE_MB = MAX_SIZE_BYTES / 1024 / 1024;

    /** 超限的业务码：413 Payload Too Large（任务 6.2 明确要求这个语义）。 */
    public static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    /** 对象类型最大长度（与 {@code t_ctms_attachment.object_type varchar(32)} 对齐）。 */
    public static final int OBJECT_TYPE_MAX_LENGTH = 32;

    /** 对象标识最大长度（与 {@code t_ctms_attachment.object_id varchar(64)} 对齐）。 */
    public static final int OBJECT_ID_MAX_LENGTH = 64;

    /**
     * CTMS 允许的附件后缀（<b>比平台窄</b>：平台还允许 ppt/pptx/html/htm/rar/zip/gz/bz2/mp4/avi/rmvb）。
     *
     * <p> 顺序即报错文案里的展示顺序；10 种，与任务 6.2 的清单逐项一致。 </p>
     */
    private static final String[] EXTENSIONS = {
            "pdf", "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg", "gif", "txt" };

    /** 后缀白名单的不可变视图（只读遍历用）。 */
    public static final List<String> ALLOWED_EXTENSIONS =
            Collections.unmodifiableList(Arrays.asList(EXTENSIONS.clone()));

    /** 后缀白名单的集合视图（判重 O(1)）。 */
    private static final Set<String> ALLOWED_EXTENSION_SET =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(EXTENSIONS)));

    /**
     * <b>已注册</b>的业务对象类型（任务 6.1：未注册对象类型被拒）。
     *
     * <p> 当前只注册合同：它是本组唯一"能查库校验存在性"的对象。B4 的单据侧对象
     * （采购/销售/出入库单据）需在 B4 交付时<b>同批</b>追加进 {@link CtmsAttachmentObjectTypes}，
     * 否则上传会被判为"对象类型未注册"——这正是刻意的失败方式：
     * 宁可拒绝，也不要产生一条指向不存在对象的孤儿附件。 </p>
     *
     * <p> 注册表<b>只有一个来源</b>（{@link CtmsAttachmentObjectTypes#registered()}），
     * 本类不再自己写一份，避免"两处清单漂移"。 </p>
     */
    private static final Set<String> REGISTERED_OBJECT_TYPES =
            Collections.unmodifiableSet(new LinkedHashSet<>(CtmsAttachmentObjectTypes.registered()));

    private CtmsAttachmentRules()
    {
    }

    /**
     * 对象类型是否已注册（未注册的类型不允许挂附件）。
     *
     * @param objectType 对象类型（大小写不敏感，内部先归一为小写）
     * @return 已注册返回 true
     */
    public static boolean isRegisteredObjectType(String objectType)
    {
        String normalized = normalizeObjectType(objectType);
        return normalized != null && REGISTERED_OBJECT_TYPES.contains(normalized);
    }

    /**
     * 已注册对象类型的只读清单（任务 6.4 的文档核对用，也是 B4 的追加点）。
     *
     * @return 对象类型清单
     */
    public static Set<String> registeredObjectTypes()
    {
        return REGISTERED_OBJECT_TYPES;
    }

    /**
     * 归一对象类型：去首尾空白 + 转小写（{@code Contract} 与 {@code contract} 视为同一类型）。
     *
     * @param objectType 原始值
     * @return 归一值；空返回 null
     */
    public static String normalizeObjectType(String objectType)
    {
        String value = ContractRules.trimToNull(objectType);
        return value == null ? null : value.toLowerCase();
    }

    /**
     * 校验对象类型：必填、已注册、长度不超列宽。
     *
     * @param objectType 对象类型
     * @return 归一后的对象类型
     * @throws ServiceException 未填 / 未注册 / 超长
     */
    public static String checkObjectType(String objectType)
    {
        String value = ContractRules.trimToNull(objectType);
        if (value == null)
        {
            throw new ServiceException("对象类型不能为空");
        }
        if (value.length() > OBJECT_TYPE_MAX_LENGTH)
        {
            throw new ServiceException("对象类型长度不能超过 " + OBJECT_TYPE_MAX_LENGTH + " 个字符");
        }
        String normalized = normalizeObjectType(value);
        if (!REGISTERED_OBJECT_TYPES.contains(normalized))
        {
            throw new ServiceException("未注册的对象类型：" + value);
        }
        return normalized;
    }

    /**
     * 校验对象标识：必填、长度不超列宽。
     *
     * @param objectId 对象标识
     * @return 去空白后的对象标识
     * @throws ServiceException 未填 / 超长
     */
    public static String checkObjectId(String objectId)
    {
        String value = ContractRules.trimToNull(objectId);
        if (value == null)
        {
            throw new ServiceException("对象标识不能为空");
        }
        if (value.length() > OBJECT_ID_MAX_LENGTH)
        {
            throw new ServiceException("对象标识长度不能超过 " + OBJECT_ID_MAX_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 校验上传文件名：必须带后缀，且后缀在白名单内。
     *
     * <p> 文案与平台 {@code FileUploadUtils.assertAllowed} 同款（见类注释）。 </p>
     *
     * @param fileName 原始文件名
     * @return 去空白后的文件名
     * @throws ServiceException 文件名为空 / 无后缀 / 后缀不在白名单
     */
    public static String checkFileName(String fileName)
    {
        String value = ContractRules.trimToNull(fileName);
        if (value == null)
        {
            throw new ServiceException("文件名不能为空");
        }
        String extension = extensionOf(value);
        // ⚠ 这里必须 return value（已校验的**整个文件名**），不能 return checkExtension 的返回值
        // —— 后者是归一后的**后缀**。曾经写成 return checkExtension(...)，症状是
        // 落库的 file_name 变成 "pdf"、存储名变成 "pdf_<时间戳>" 且丢掉后缀
        // （中文/ASCII 文件名都一样中招）；因为"后缀确实被校验过了"，
        // 白名单用例全都照常通过，只有"文件名是否被完整保留"的断言能抓到它。
        checkExtension(value, extension);
        return value;
    }

    /**
     * 校验后缀（拆出来是为了让 {@code API 传入的 contentType 兜底} 这条分支也能被单测直接命中）。
     *
     * @param fileName  原始文件名（仅用于报错文案）
     * @param extension 已解析出的后缀（可为 null）
     * @return 归一后的后缀（小写）
     * @throws ServiceException 无后缀 / 后缀不在白名单
     */
    public static String checkExtension(String fileName, String extension)
    {
        String value = ContractRules.trimToNull(extension);
        if (value == null)
        {
            throw new ServiceException("文件[" + fileName + "]必须带后缀名，请上传["
                    + String.join(", ", EXTENSIONS) + "]格式");
        }
        String normalized = value.toLowerCase();
        if (!ALLOWED_EXTENSION_SET.contains(normalized))
        {
            throw new ServiceException("文件[" + fileName + "]后缀[" + value + "]不正确，请上传["
                    + String.join(", ", EXTENSIONS) + "]格式");
        }
        return normalized;
    }

    /**
     * 解析文件后缀（{@code a.tar.gz} → {@code gz}；无点或点在末尾返回 null）。
     *
     * @param fileName 文件名
     * @return 小写后缀；解析不出返回 null
     */
    public static String extensionOf(String fileName)
    {
        String value = ContractRules.trimToNull(fileName);
        if (value == null)
        {
            return null;
        }
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1)
        {
            return null;
        }
        String extension = ContractRules.trimToNull(value.substring(dot + 1));
        return extension == null ? null : extension.toLowerCase();
    }

    /**
     * 校验单文件大小：{@code size > 20MB} 拒绝。
     *
     * <p> <b>边界口径</b>：恰好等于 20MB（{@code 20971520} 字节）<b>允许</b>
     * （任务 6.2 要求"等于与超出各一次"两个边界用例），超出 1 字节即拒绝。 </p>
     *
     * <p> 超限抛业务码 <b>413</b>：这不是普通的参数错误，而是"请求体太大"的 HTTP 语义，
     * 前端与网关按 413 处理；控制器的异常处理器据此把 HTTP 状态也置为 413。 </p>
     *
     * @param size 字节数（null 视为 0）
     * @throws ServiceException 超出 20MB
     */
    public static void checkSize(Long size)
    {
        long actual = size == null ? 0L : size;
        if (actual > MAX_SIZE_BYTES)
        {
            throw new ServiceException("上传的文件大小超出限制，单个附件不能超过 " + MAX_SIZE_MB + "MB",
                    HTTP_PAYLOAD_TOO_LARGE);
        }
    }

    /**
     * 大小是否超限（只判定不抛，给"要不要清理半成品"这类判断用）。
     *
     * @param size 字节数
     * @return 超限返回 true
     */
    public static boolean exceedsMaxSize(Long size)
    {
        return (size == null ? 0L : size) > MAX_SIZE_BYTES;
    }

    /**
     * 字节数的展示文案（KB/MB 两位小数），用于变更历史的新值快照。
     *
     * @param size 字节数
     * @return 形如 {@code 15 B} / {@code 1.50 KB}
     */
    public static String sizeText(Long size)
    {
        long actual = size == null ? 0L : size;
        if (actual < 1024)
        {
            return actual + " B";
        }
        if (actual < 1024 * 1024)
        {
            return String.format("%.2f KB", actual / 1024.0);
        }
        return String.format("%.2f MB", actual / 1024.0 / 1024.0);
    }

    /**
     * <p> 存储文件名：{@code yyyy/MM/dd/原名_yyyyMMddHHmmss + 4 位随机.后缀}。 </p>
     *
     * <p> <b>为什么不用平台现成的 {@code FileUploadUtils.extractFilename}</b>：
     * 它内部用 {@code FilenameUtils.getBaseName()} 取主名，而这个调用在
     * <b>非 ASCII 文件名</b>上依赖运行环境的字符集，实测中文名会退化成空串，
     * 存储名变成 {@code .pdf}（真实环境用中文名上传会得到一个难以排查的"文件没有名字"）。
     * 本方法改用{@code lastIndexOf('.')} 自己切，字符集无关。 </p>
     *
     * <p> 命名口径与平台一致的三点：日期目录分片、保留原主名（可读性）、
     * 追加时间戳 + 随机数（<b>同名不同次必然不同名</b>，对应任务 6.2 的"随机文件名"）。
     * 时间戳用 {@code yyyyMMddHHmmss} + 4 位随机，同一秒内的两次上传也不会撞名。 </p>
     *
     * @param originalName 原始文件名（已通过白名单校验）
     * @return 相对路径（形如 {@code 2026/10/05/合同_20261005123045A123.pdf}）
     */
    public static String storedNameOf(String originalName)
    {
        String name = ContractRules.trimToNull(originalName);
        if (name == null)
        {
            throw new ServiceException("文件名不能为空");
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot + 1) : "";
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("%1$tY/%1$tm/%1$td", new java.util.Date())).append('/');
        builder.append(base).append('_');
        builder.append(String.format("%1$tY%1$tm%1$td%1$tH%1$tM%1$tS", new java.util.Date()));
        builder.append(String.format("%04d", (int) (Math.random() * 10000)));
        if (!extension.isEmpty())
        {
            builder.append('.').append(extension);
        }
        return builder.toString();
    }
}
