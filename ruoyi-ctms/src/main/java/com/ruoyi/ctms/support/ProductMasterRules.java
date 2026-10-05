package com.ruoyi.ctms.support;

import java.math.BigDecimal;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> 物料域主数据的<b>纯校验与树规则</b>（2.0 B3 §3.3）。 </p>
 *
 * <p> 这些规则刻意抽成<b>不依赖 Spring、不依赖数据库</b>的静态方法：
 * 层级上限、小数位范围、负值拒绝、物化路径拼接都能被表驱动单测直接断言，
 * 不必起容器（参见 {@code ProductMasterRulesTest}）。 </p>
 *
 * <p> 口径来源：DDL {@code sql/二开-合同台账.sql} 的
 * {@code t_ctms_product_type} / {@code t_ctms_uom} / {@code t_ctms_product} 注释，
 * 以及规格 {@code openspec/changes/oa-purchase-sales-stock/specs/erp/stock-master-data/spec.md}。 </p>
 *
 * <p> ⚠ 本类只做「值本身是否合法」的判定；「同名唯一」「叶子才可挂物料」「被引用不可删」
 * 这类需要读库的规则放在 {@code CtmsProductMasterServiceImpl}，由它组合本类。 </p>
 *
 * @author 二开
 */
public final class ProductMasterRules
{
    /** 商品类型树最大层级（B3 §3.3：任意节点深度不得超过 5 层）。 */
    public static final int MAX_TYPE_LEVEL = 5;

    /** 计量单位小数位下界（含）。 */
    public static final int MIN_UOM_DECIMALS = 0;

    /** 计量单位小数位上界（含）。 */
    public static final int MAX_UOM_DECIMALS = 4;

    /**
     * 商品类型编码列 {@code t_ctms_product_type.code} 的长度上限（DDL：{@code varchar(32)}）。
     *
     * <p> 这个常量存在的唯一理由是<b>修一个真实踩过的缺陷</b>：留空编码时曾退化为
     * {@code "PT" + 类型id}，而本仓库的类型 id 是 32 位 UUID →
     * 生成 34 字符 → MySQL 报
     * {@code Data truncation: Data too long for column 'code'}，
     * 建类型接口直接 500（真库验收脚本抓到的，单测因为用内存桩发现不了）。 </p>
     */
    public static final int MAX_TYPE_CODE_LENGTH = 32;

    /**
     * 物料编码里流水号的位数（{@code "%04d"}）—— 4 位。
     *
     * <p> 与 {@link #MAX_TYPE_CODE_LENGTH} 配合：前缀最多占
     * {@code MAX_TYPE_CODE_LENGTH - PRODUCT_CODE_SEQ_DIGITS} 位，
     * 否则拼出来的物料编码会超过 DDL 的 {@code varchar(32)}。 </p>
     */
    public static final int PRODUCT_CODE_SEQ_DIGITS = 4;

    /** 工具类不允许实例化。 */
    private ProductMasterRules()
    {
    }

    /**
     * <p> 生成「留空时」的商品类型编码：{@code "P" + 类型id 的前若干位}，
     * <b>保证长度不超过 {@link #MAX_TYPE_CODE_LENGTH}</b>。 </p>
     *
     * <p> 为什么不是 {@code "PT" + 完整 id}：那会超长（见 {@link #MAX_TYPE_CODE_LENGTH} 的说明）。
     * 为什么取前缀而不是取后缀：id 是 UUID，前若干位同样能起到"同类型稳定、不同类型不同"的作用，
     * 且保留可读的前缀便于人工识别这是自动编码。 </p>
     *
     * @param typeId 商品类型主键（{@code null} 或空白时返回 {@code null}，交由 DDL 的可空列语义处理）
     * @return 长度 ≤ 32 的类型编码；{@code typeId} 为空时返回 {@code null}
     */
    public static String generateTypeCode(String typeId)
    {
        if (isBlank(typeId))
        {
            // 编码列可空：没有 id 时宁可留空，也不要写一个无意义的短码去占号
            return null;
        }
        String trimmed = typeId.trim();
        // "P" 占 1 位，id 部分最多取 MAX_TYPE_CODE_LENGTH - 1 位
        int keep = Math.min(trimmed.length(), MAX_TYPE_CODE_LENGTH - 1);
        return "P" + trimmed.substring(0, keep);
    }

    /**
     * <p> 商品类型的编码是否过长（超过 DDL 的 {@code varchar(32)}）。 </p>
     *
     * <p> 手工录入的编码必须过这一关 —— 否则会在 insert 时才由 MySQL 报数据截断，
     * 表现为一个 500 而不是一条可读的业务提示。 </p>
     *
     * @param code 类型编码
     * @return true = 过长
     */
    public static boolean isTypeCodeTooLong(String code)
    {
        return code != null && code.length() > MAX_TYPE_CODE_LENGTH;
    }

    /**
     * <p> 计算子级层级：父级层级已知时子级 = 父级 + 1。 </p>
     *
     * <p> {@code parentLevel} 为 {@code null}（或非正数，视同根）时返回 1；
     * 计算结果超过 {@link #MAX_TYPE_LEVEL} 时抛业务异常。 </p>
     *
     * @param parentLevel 父级层级，根类型传 {@code null}
     * @return 子级层级（1 ~ 5）
     * @throws ServiceException 层级超过 5 级
     */
    public static int childLevel(Integer parentLevel)
    {
        if (parentLevel == null || parentLevel.intValue() <= 0)
        {
            // 根：没有父级（或父级是历史脏数据的 0）→ 第 1 级
            return 1;
        }
        int level = parentLevel.intValue() + 1;
        if (level > MAX_TYPE_LEVEL)
        {
            throw new ServiceException("商品类型层级不能超过 5 级");
        }
        return level;
    }

    /**
     * 校验计量单位小数位：取值范围 0 ~ 4。
     *
     * <p> 传 {@code null} 时本方法不做判定（调用方应先把 {@code null} 归一为 2，
     * 见 {@code CtmsProductMasterServiceImpl.insertUom} 的「null 默认 2」口径）。 </p>
     *
     * @param decimals 小数位
     * @throws ServiceException 越界
     */
    public static void checkUomDecimals(Integer decimals)
    {
        if (decimals == null)
        {
            return;
        }
        int value = decimals.intValue();
        if (value < MIN_UOM_DECIMALS || value > MAX_UOM_DECIMALS)
        {
            throw new ServiceException("计量单位小数位只能是 0 到 4");
        }
    }

    /**
     * 校验默认单价：不允许为负数。
     *
     * <p> 传 {@code null} 视为 0（调用方负责把 {@code null} 归一为 {@link BigDecimal#ZERO}）。 </p>
     *
     * @param price 默认单价
     * @throws ServiceException 负数
     */
    public static void checkDefaultPrice(BigDecimal price)
    {
        if (price != null && price.signum() < 0)
        {
            throw new ServiceException("默认单价不能为负数");
        }
    }

    /**
     * 校验安全库存：不允许为负数；{@code null} 合法（「未设置」与「0」需要可区分）。
     *
     * @param safetyStock 安全库存
     * @throws ServiceException 负数
     */
    public static void checkSafetyStock(BigDecimal safetyStock)
    {
        if (safetyStock != null && safetyStock.signum() < 0)
        {
            throw new ServiceException("安全库存不能为负数");
        }
    }

    /**
     * <p> 物化路径拼接（子节点）。 </p>
     *
     * <p> {@code parentId} 为空表示<b>根节点</b>，返回 {@code "/"}；
     * 否则返回 {@code 父路径 + 父id + "/"}，且结果<b>一定以 "/" 结尾</b>。 </p>
     *
     * <p> 例：{@code childPath("/", "A")} = {@code "/A/"}；
     * {@code childPath("/A/", "B")} = {@code "/A/B/"}。 </p>
     *
     * @param parentPath 父节点物化路径（根为 {@code "/"}，允许为空）
     * @param parentId   父节点主键；为空表示根节点
     * @return 子节点物化路径
     */
    public static String childPath(String parentPath, String parentId)
    {
        if (isBlank(parentId))
        {
            // 没有父 → 根节点
            return "/";
        }
        String base = parentPath;
        if (isBlank(base))
        {
            base = "/";
        }
        if (!base.endsWith("/"))
        {
            base = base + "/";
        }
        return base + parentId + "/";
    }

    /**
     * <p> 从物化路径推算层级：{@code "/"} → 1；{@code "/a/"} → 2；{@code "/a/b/"} → 3。 </p>
     *
     * <p> {@code null} / 空串同样按根处理，返回 1。该方法对末尾是否带 "/" 容错，
     * 便于修复历史脏数据时给出仍然合理的层级。 </p>
     *
     * @param path 物化路径
     * @return 层级（≥1）
     */
    public static int levelOfPath(String path)
    {
        if (isBlank(path))
        {
            return 1;
        }
        String trimmed = path.trim();
        // 去掉末尾的分隔符，"个数 + 1" 即层级
        while (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty())
        {
            return 1;
        }
        int level = 1;
        for (int i = 0; i < trimmed.length(); i++)
        {
            if (trimmed.charAt(i) == '/')
            {
                level++;
            }
        }
        return level;
    }

    /**
     * 判断字符串是否为空（{@code null} 或去空格后长度为 0）。
     *
     * @param value 待判定字符串
     * @return 空返回 true
     */
    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
