package com.ruoyi.ctms.support;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 往来单位档案<b>纯校验规则</b>的表驱动单测（B3 §3；规格 ctms/business-partners）。 </p>
 *
 * <p> 为什么做成纯函数单测：唯一性要靠查库，但"必填口径"和"取值范围"是纯规则，
 * 把它们从 Service 里摘出来（{@link PartnerRules}）之后，就能<b>逐字</b>断言提示文案
 * —— 文案是接口契约的一部分（验收脚本会比对），写死在这里最稳。 </p>
 *
 * <p> 三条刻意的不对称（都要有反例锁定，避免以后被"顺手统一"）： </p>
 * <ul>
 *   <li> 客户简称选填 / 供应商简称必填； </li>
 *   <li> 账期 {@code 0} 合法（现结）而 {@code -1} 非法；{@code null} 也合法（未填）； </li>
 *   <li> 启用标志 {@code null} 视同 {@code "1"}，只有 {@code "0"}/{@code "1"} 是合法值。 </li>
 * </ul>
 *
 * @author 二开
 */
public class PartnerRulesTest
{
    /* ==================== 夹具 ==================== */

    private static CtmsCustomer customer(String code, String name)
    {
        CtmsCustomer c = new CtmsCustomer();
        c.setCode(code);
        c.setName(name);
        return c;
    }

    private static CtmsSupplier supplier(String code, String name, String shortName)
    {
        CtmsSupplier s = new CtmsSupplier();
        s.setCode(code);
        s.setName(name);
        s.setShortName(shortName);
        return s;
    }

    /** 断言动作被拒，且提示文案逐字一致 */
    private static void assertRejected(String expectedMessage, Runnable action)
    {
        try
        {
            action.run();
            fail("本应被拒绝，提示应为：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            assertEquals("提示文案是接口契约，必须逐字一致", expectedMessage, e.getMessage());
        }
    }

    /** 断言动作放行（不抛业务异常） */
    private static void assertAccepted(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (ServiceException e)
        {
            fail("本应放行，却抛出业务异常：" + e.getMessage());
        }
    }

    /* ==================== ① 客户编码空白被拒 ==================== */

    @Test
    public void 判定一_客户编码空白被拒()
    {
        // null / 空串 / 纯空白 三种"没填"的形态都要挡住
        String[] blanks = {null, "", "   ", "\t\n"};
        for (final String blank : blanks)
        {
            assertRejected("客户编码不能为空", new Runnable()
            {
                @Override
                public void run()
                {
                    PartnerRules.checkCustomerRequired(customer(blank, "某某科技有限公司"));
                }
            });
        }
    }

    /* ==================== ② 客户名称空白被拒 ==================== */

    @Test
    public void 判定二_客户名称空白被拒()
    {
        String[] blanks = {null, "", " \t "};
        for (final String blank : blanks)
        {
            assertRejected("客户名称不能为空", new Runnable()
            {
                @Override
                public void run()
                {
                    PartnerRules.checkCustomerRequired(customer("C001", blank));
                }
            });
        }
    }

    /* ==================== ③ 供应商简称为空被拒（新增/修改同一规则） ==================== */

    @Test
    public void 判定三_供应商简称为空被拒()
    {
        String[] blanks = {null, "", "  "};
        for (final String blank : blanks)
        {
            assertRejected("供应商简称不能为空", new Runnable()
            {
                @Override
                public void run()
                {
                    PartnerRules.checkSupplierRequired(supplier("S001", "某某阀门有限公司", blank));
                }
            });
        }
        // 编码/名称的文案与客户刻意不同，不能共用一套
        assertRejected("供应商编码不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkSupplierRequired(supplier(null, "某某阀门有限公司", "某某阀门"));
            }
        });
        assertRejected("供应商名称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkSupplierRequired(supplier("S001", "  ", "某某阀门"));
            }
        });
    }

    /* ==================== ④ 账期 -1 被拒 ==================== */

    @Test
    public void 判定四_账期天数为负数被拒()
    {
        assertRejected("账期天数不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkPaymentDays(Integer.valueOf(-1));
            }
        });
        assertRejected("账期天数不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkPaymentDays(Integer.valueOf(Integer.MIN_VALUE));
            }
        });
    }

    /* ==================== ⑤ 账期 0 通过 ==================== */

    @Test
    public void 判定五_账期天数0通过()
    {
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkPaymentDays(Integer.valueOf(0));
            }
        });
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkPaymentDays(Integer.valueOf(90));
            }
        });
    }

    /* ==================== ⑥ 账期 null 通过 ==================== */

    @Test
    public void 判定六_账期天数为null通过()
    {
        // null = 未填，与 0（现结）语义不同，两者都必须放行
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkPaymentDays(null);
            }
        });
    }

    /* ==================== ⑦ enableFlag 非法值被拒 ==================== */

    @Test
    public void 判定七_启用标志非法值被拒()
    {
        String[] illegal = {"2", "", "Y", "yes", "01", "-1"};
        for (final String flag : illegal)
        {
            assertRejected("启用标志只能是 0 或 1", new Runnable()
            {
                @Override
                public void run()
                {
                    PartnerRules.checkEnableFlag(flag);
                }
            });
        }
        // 合法值与非空默认语义
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkEnableFlag("1");
            }
        });
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkEnableFlag("0");
            }
        });
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkEnableFlag(null);
            }
        });
    }

    /* ==================== ⑧ 合法入参不抛异常 ==================== */

    @Test
    public void 判定八_合法入参不抛异常()
    {
        // 客户：编码 + 名称即可，简称留空（选填）
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                CtmsCustomer c = customer("C001", "某某科技有限公司");
                PartnerRules.checkCustomerRequired(c);
                PartnerRules.checkEnableFlag(c.getEnableFlag());
            }
        });
        // 供应商：编码 + 名称 + 简称，账期 0
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某阀门");
                s.setPaymentDays(Integer.valueOf(0));
                PartnerRules.checkSupplierRequired(s);
                PartnerRules.checkPaymentDays(s.getPaymentDays());
                PartnerRules.checkEnableFlag(s.getEnableFlag());
            }
        });
    }

    /* ==================== 附加：空对象与"客户简称选填"的锁定 ==================== */

    @Test
    public void 空对象按客户口径报编码缺失()
    {
        assertRejected("客户编码不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkCustomerRequired(null);
            }
        });
        assertRejected("供应商编码不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkSupplierRequired(null);
            }
        });
    }

    @Test
    public void 客户简称确实选填_与供应商的不对称被锁定()
    {
        // 客户不填简称：放行
        assertAccepted(new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkCustomerRequired(customer("C001", "某某科技有限公司"));
            }
        });
        // 同一份数据换成供应商口径：被拒（证明两条规则确实不同）
        assertTrue("客户简称是选填项", new CtmsCustomer().getShortName() == null);
        assertRejected("供应商简称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                PartnerRules.checkSupplierRequired(supplier("C001", "某某科技有限公司", null));
            }
        });
    }
}
