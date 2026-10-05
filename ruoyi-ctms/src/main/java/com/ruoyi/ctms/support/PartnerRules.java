package com.ruoyi.ctms.support;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;

/**
 * <p> 往来单位档案的<b>纯校验规则</b>（B3 §3，规格 ctms/business-partners）。 </p>
 *
 * <p> 设计口径：这些规则是"规则本身"，不依赖 Spring 容器、数据库与登录上下文，
 * 全部做成静态纯函数，好处是 </p>
 * <ul>
 *   <li> 表驱动单测可以直接断言"拒绝/放行"与<b>逐字文案</b>（接口验收脚本也断言文案）； </li>
 *   <li> 唯一性、引用保护这类<b>需要访问存储</b>的编排留在 Service 层，
 *        本类不掺入 Mapper 调用，边界清晰。 </li>
 * </ul>
 *
 * <p> ⚠ 提示文案是接口契约的一部分，<b>改动即视为破坏性变更</b>。 </p>
 *
 * <p> 刻意的不对称（规格明确要求，不要"顺手统一"）： </p>
 * <ul>
 *   <li> 客户简称<b>选填</b>；供应商简称<b>必填</b>； </li>
 *   <li> 账期天数只属于供应商；{@code null} 与 {@code 0} 语义不同，前者是"未填"，后者是"现结"，两者都合法。 </li>
 * </ul>
 *
 * @author 二开
 */
public final class PartnerRules
{
    /** 启用标志：启用 */
    private static final String ENABLE_FLAG_YES = "1";

    /** 启用标志：停用 */
    private static final String ENABLE_FLAG_NO = "0";

    private PartnerRules()
    {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 校验客户档案的必填项：编码与名称非空（纯空白视同空）。
     *
     * <p> 简称<b>不校验</b>——客户简称是选填项，这是与供应商的刻意差异。 </p>
     *
     * @param c 客户档案
     * @throws ServiceException 编码或名称为空
     */
    public static void checkCustomerRequired(CtmsCustomer c)
    {
        if (c == null || isBlank(c.getCode()))
        {
            throw new ServiceException("客户编码不能为空");
        }
        if (isBlank(c.getName()))
        {
            throw new ServiceException("客户名称不能为空");
        }
    }

    /**
     * 校验供应商档案的必填项：编码、名称与简称非空（纯空白视同空）。
     *
     * <p> 新增与修改两条路径都要调用——规格要求"新增或修改时提交空的简称 MUST 被拒绝"。 </p>
     *
     * @param s 供应商档案
     * @throws ServiceException 编码、名称或简称为空
     */
    public static void checkSupplierRequired(CtmsSupplier s)
    {
        if (s == null || isBlank(s.getCode()))
        {
            throw new ServiceException("供应商编码不能为空");
        }
        if (isBlank(s.getName()))
        {
            throw new ServiceException("供应商名称不能为空");
        }
        if (isBlank(s.getShortName()))
        {
            throw new ServiceException("供应商简称不能为空");
        }
    }

    /**
     * 校验账期天数：允许 {@code null}（未填）与 {@code 0}（现结），负数一律拒绝。
     *
     * @param paymentDays 账期天数
     * @throws ServiceException 账期天数为负数
     */
    public static void checkPaymentDays(Integer paymentDays)
    {
        if (paymentDays != null && paymentDays.intValue() < 0)
        {
            throw new ServiceException("账期天数不能为负数");
        }
    }

    /**
     * 校验启用标志：只允许 {@code "1"} / {@code "0"}；{@code null} 视同 {@code "1"}（默认启用）。
     *
     * @param enableFlag 启用标志
     * @throws ServiceException 取值非法
     */
    public static void checkEnableFlag(String enableFlag)
    {
        if (enableFlag == null)
        {
            // null 表示"未指定"→ 按默认启用处理，不算非法值
            return;
        }
        if (!ENABLE_FLAG_YES.equals(enableFlag) && !ENABLE_FLAG_NO.equals(enableFlag))
        {
            throw new ServiceException("启用标志只能是 0 或 1");
        }
    }

    /**
     * 纯空白判空（不引 Spring / Apache Commons，保持本类是零依赖纯函数）
     *
     * @param value 待判定字符串
     * @return true = null 或仅含空白字符
     */
    private static boolean isBlank(String value)
    {
        if (value == null)
        {
            return true;
        }
        int len = value.length();
        for (int i = 0; i < len; i++)
        {
            if (!Character.isWhitespace(value.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }
}
