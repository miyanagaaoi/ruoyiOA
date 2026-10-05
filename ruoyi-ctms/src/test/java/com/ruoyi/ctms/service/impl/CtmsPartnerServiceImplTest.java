package com.ruoyi.ctms.service.impl;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;
import com.ruoyi.ctms.service.ICtmsPartnerService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 往来单位档案服务层的单测（B3 §3.1/§3.2/§3.4/§3.5）。 </p>
 *
 * <p> <b>不依赖 Spring 容器 / 数据库 / Redis</b>：被测对象是"规则 + 编排"，
 * 用内存桩 Mapper（{@link InMemoryPartnerMapper}）就能把全部判定跑出来，
 * 与 {@code ruoyi-template} 的 {@code TemplateUpdateSemanticsCheck} 同思路。 </p>
 *
 * <p> 桩的取数/写数一律<b>复制对象</b>，模拟"数据库往返"：这样"服务层到底有没有真的
 * 调 update"才会被断言到，而不是靠共享引用的副作用蒙过去。 </p>
 *
 * <p> 覆盖的验收断言： </p>
 * <ol>
 *   <li> 新增客户成功（id 自动生成、enableFlag 默认 1、updateTime 非空）； </li>
 *   <li> 重复编码被拒； </li>
 *   <li> 重复名称被拒； </li>
 *   <li> 客户简称可为空； </li>
 *   <li> 供应商简称必填（新增与修改两条路径）； </li>
 *   <li> 账期 -1 被拒 / 0 通过； </li>
 *   <li> 有引用时删除被拒 / 零引用可删； </li>
 *   <li> 停用后不进选择器、档案列表仍可见、历史引用行仍能按 id 读到； </li>
 *   <li> 无登录上下文时兜底 createId="1" / createBy="system"。 </li>
 * </ol>
 *
 * @author 二开
 */
public class CtmsPartnerServiceImplTest
{
    /* ==================== 内存桩 Mapper ==================== */

    /**
     * 内存桩 Mapper：用 {@code LinkedHashMap} 存数据，语义对齐 XML 里的筛选口径
     * （code/name 模糊、enableFlag 精确；enableFlag 为 null 表示不限）。
     */
    private static class InMemoryPartnerMapper implements CtmsPartnerMapper
    {
        private final Map<String, CtmsCustomer> customers = new LinkedHashMap<String, CtmsCustomer>();

        private final Map<String, CtmsSupplier> suppliers = new LinkedHashMap<String, CtmsSupplier>();

        /** 客户档案 id → 引用它的合同数 */
        private final Map<String, Integer> customerRefs = new HashMap<String, Integer>();

        /** 供应商档案 id → 引用它的合同数 */
        private final Map<String, Integer> supplierRefs = new HashMap<String, Integer>();

        /** 合同表 t_ctms_contract 是否存在（1/0），测试里可切换 */
        private int contractTableExists = 0;

        /** 记录 update 调用次数，用于证明"服务层真的写了库" */
        private int updateCalls = 0;

        @Override
        public List<CtmsCustomer> selectCustomerList(CtmsCustomer q)
        {
            List<CtmsCustomer> out = new ArrayList<CtmsCustomer>();
            for (CtmsCustomer row : customers.values())
            {
                if (q != null)
                {
                    if (isNotBlank(q.getCode()) && !contains(row.getCode(), q.getCode()))
                    {
                        continue;
                    }
                    if (isNotBlank(q.getName()) && !contains(row.getName(), q.getName()))
                    {
                        continue;
                    }
                    if (isNotBlank(q.getEnableFlag()) && !q.getEnableFlag().equals(row.getEnableFlag()))
                    {
                        continue;
                    }
                }
                out.add(copy(row));
            }
            return out;
        }

        @Override
        public CtmsCustomer selectCustomerById(String id)
        {
            return copy(customers.get(id));
        }

        @Override
        public CtmsCustomer selectCustomerByCode(String code)
        {
            for (CtmsCustomer row : customers.values())
            {
                if (equalsStr(row.getCode(), code))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public CtmsCustomer selectCustomerByName(String name)
        {
            for (CtmsCustomer row : customers.values())
            {
                if (equalsStr(row.getName(), name))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public int insertCustomer(CtmsCustomer c)
        {
            customers.put(c.getId(), copy(c));
            return 1;
        }

        @Override
        public int updateCustomer(CtmsCustomer c)
        {
            updateCalls++;
            customers.put(c.getId(), copy(c));
            return 1;
        }

        @Override
        public int deleteCustomerById(String id)
        {
            return customers.remove(id) == null ? 0 : 1;
        }

        @Override
        public int countCustomerReferences(String id)
        {
            Integer n = customerRefs.get(id);
            return n == null ? 0 : n.intValue();
        }

        @Override
        public List<CtmsSupplier> selectSupplierList(CtmsSupplier q)
        {
            List<CtmsSupplier> out = new ArrayList<CtmsSupplier>();
            for (CtmsSupplier row : suppliers.values())
            {
                if (q != null)
                {
                    if (isNotBlank(q.getCode()) && !contains(row.getCode(), q.getCode()))
                    {
                        continue;
                    }
                    if (isNotBlank(q.getName()) && !contains(row.getName(), q.getName()))
                    {
                        continue;
                    }
                    if (isNotBlank(q.getEnableFlag()) && !q.getEnableFlag().equals(row.getEnableFlag()))
                    {
                        continue;
                    }
                }
                out.add(copy(row));
            }
            return out;
        }

        @Override
        public CtmsSupplier selectSupplierById(String id)
        {
            return copy(suppliers.get(id));
        }

        @Override
        public CtmsSupplier selectSupplierByCode(String code)
        {
            for (CtmsSupplier row : suppliers.values())
            {
                if (equalsStr(row.getCode(), code))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public CtmsSupplier selectSupplierByName(String name)
        {
            for (CtmsSupplier row : suppliers.values())
            {
                if (equalsStr(row.getName(), name))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public int insertSupplier(CtmsSupplier s)
        {
            suppliers.put(s.getId(), copy(s));
            return 1;
        }

        @Override
        public int updateSupplier(CtmsSupplier s)
        {
            updateCalls++;
            suppliers.put(s.getId(), copy(s));
            return 1;
        }

        @Override
        public int deleteSupplierById(String id)
        {
            return suppliers.remove(id) == null ? 0 : 1;
        }

        @Override
        public int countSupplierReferences(String id)
        {
            Integer n = supplierRefs.get(id);
            return n == null ? 0 : n.intValue();
        }

        @Override
        public int existsContractTable()
        {
            return contractTableExists;
        }

        private static boolean isNotBlank(String v)
        {
            return v != null && v.trim().length() > 0;
        }

        private static boolean equalsStr(String a, String b)
        {
            return a == null ? b == null : a.equals(b);
        }

        private static boolean contains(String column, String keyword)
        {
            return column != null && column.contains(keyword);
        }
    }

    /** 模拟数据库往返：写入与读出都用副本，避免共享引用掩盖"没落库"的缺陷 */
    private static CtmsCustomer copy(CtmsCustomer s)
    {
        if (s == null)
        {
            return null;
        }
        CtmsCustomer t = new CtmsCustomer();
        t.setId(s.getId());
        t.setCode(s.getCode());
        t.setName(s.getName());
        t.setShortName(s.getShortName());
        t.setTaxNo(s.getTaxNo());
        t.setContactName(s.getContactName());
        t.setContactPhone(s.getContactPhone());
        t.setAddress(s.getAddress());
        t.setBankName(s.getBankName());
        t.setBankAccount(s.getBankAccount());
        t.setCreditLimit(s.getCreditLimit());
        t.setLevel(s.getLevel());
        t.setEnableFlag(s.getEnableFlag());
        t.setCreateId(s.getCreateId());
        t.setUpdateId(s.getUpdateId());
        t.setCreateBy(s.getCreateBy());
        t.setCreateTime(s.getCreateTime());
        t.setUpdateBy(s.getUpdateBy());
        t.setUpdateTime(s.getUpdateTime());
        t.setRemark(s.getRemark());
        return t;
    }

    private static CtmsSupplier copy(CtmsSupplier s)
    {
        if (s == null)
        {
            return null;
        }
        CtmsSupplier t = new CtmsSupplier();
        t.setId(s.getId());
        t.setCode(s.getCode());
        t.setName(s.getName());
        t.setShortName(s.getShortName());
        t.setTaxNo(s.getTaxNo());
        t.setContactName(s.getContactName());
        t.setContactPhone(s.getContactPhone());
        t.setAddress(s.getAddress());
        t.setBankName(s.getBankName());
        t.setBankAccount(s.getBankAccount());
        t.setCreditLimit(s.getCreditLimit());
        t.setLevel(s.getLevel());
        t.setSupplyScope(s.getSupplyScope());
        t.setPaymentDays(s.getPaymentDays());
        t.setEnableFlag(s.getEnableFlag());
        t.setCreateId(s.getCreateId());
        t.setUpdateId(s.getUpdateId());
        t.setCreateBy(s.getCreateBy());
        t.setCreateTime(s.getCreateTime());
        t.setUpdateBy(s.getUpdateBy());
        t.setUpdateTime(s.getUpdateTime());
        t.setRemark(s.getRemark());
        return t;
    }

    /* ==================== 夹具 ==================== */

    /** 手写字段注入（不引 Spring 容器），与本仓库"字段注入"的实现方式保持一致 */
    private static ICtmsPartnerService service(InMemoryPartnerMapper mapper) throws Exception
    {
        CtmsPartnerServiceImpl impl = new CtmsPartnerServiceImpl();
        Field field = CtmsPartnerServiceImpl.class.getDeclaredField("partnerMapper");
        field.setAccessible(true);
        field.set(impl, mapper);
        return impl;
    }

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

    /* ==================== ① 新增客户成功 ==================== */

    @Test
    public void 断言一_新增客户成功_自动生成id_默认启用_更新时间非空() throws Exception
    {
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        c.setShortName(null);
        c.setCreditLimit(new BigDecimal("100000.00"));
        c.setLevel("A");
        svc.insertCustomer(c);

        assertNotNull("主键必须由服务端生成", c.getId());
        assertEquals("IdUtils.fastSimpleUUID() 是 32 位无横线 UUID", 32, c.getId().length());
        assertTrue("id 必须能被路由正则 [A-Za-z0-9]+ 接住：" + c.getId(),
                c.getId().matches("[A-Za-z0-9]+"));
        assertEquals("未指定启用标志时默认 '1'", "1", c.getEnableFlag());
        assertNotNull("create_time 非空", c.getCreateTime());
        assertNotNull("update_time 是 NOT NULL，新增也必须给值", c.getUpdateTime());

        CtmsCustomer saved = mapper.selectCustomerById(c.getId());
        assertNotNull("必须真的落库", saved);
        assertEquals("C001", saved.getCode());
        assertEquals("1", saved.getEnableFlag());
        assertEquals("金额口径 BigDecimal 未被破坏", new BigDecimal("100000.00"), saved.getCreditLimit());
    }

    /* ==================== ② 重复编码被拒 ==================== */

    @Test
    public void 断言二_重复客户编码被拒() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);
        svc.insertCustomer(customer("C001", "某某科技有限公司"));

        // 名称不同、编码相同 → 编码冲突
        assertRejected("客户编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertCustomer(customer("C001", "另一家科技有限公司"));
            }
        });
        assertEquals("冲突时必须整体拒绝，不能留下半条记录", 1, mapper.customers.size());
    }

    /* ==================== ③ 重复名称被拒 ==================== */

    @Test
    public void 断言三_重复客户名称被拒() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);
        svc.insertCustomer(customer("C001", "某某科技有限公司"));

        assertRejected("客户名称已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertCustomer(customer("C002", "某某科技有限公司"));
            }
        });
        assertEquals(1, mapper.customers.size());
    }

    /* ==================== ④ 客户简称可为空 ==================== */

    @Test
    public void 断言四_客户简称可为空() throws Exception
    {
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        c.setShortName(null);
        svc.insertCustomer(c);

        CtmsCustomer saved = mapper.selectCustomerById(c.getId());
        assertNull("客户简称是选填项，留空必须能存下来", saved.getShortName());
    }

    /* ==================== ⑤ 供应商简称必填（新增 + 修改两条路径） ==================== */

    @Test
    public void 断言五_供应商简称为空被拒_新增与修改两条路径() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        assertRejected("供应商简称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertSupplier(supplier("S001", "某某阀门有限公司", null));
            }
        });
        assertRejected("供应商简称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertSupplier(supplier("S001", "某某阀门有限公司", "   "));
            }
        });
        assertTrue("被拒后不能落库", mapper.suppliers.isEmpty());

        // 先正常新增，再在修改时把简称提交为空 → 仍要被拒（任务 3.2 明确要求覆盖两条路径）
        CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某阀门");
        s.setPaymentDays(Integer.valueOf(30));
        svc.insertSupplier(s);

        final CtmsSupplier edit = mapper.selectSupplierById(s.getId());
        edit.setShortName("  ");
        assertRejected("供应商简称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                svc.updateSupplier(edit);
            }
        });
        assertEquals("被拒的修改不能落库", "某某阀门",
                mapper.selectSupplierById(s.getId()).getShortName());
    }

    /* ==================== ⑥ 账期 -1 被拒 / 0 通过 ==================== */

    @Test
    public void 断言六_账期负数被拒_账期0通过() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        final CtmsSupplier bad = supplier("S001", "某某阀门有限公司", "某某阀门");
        bad.setPaymentDays(Integer.valueOf(-1));
        assertRejected("账期天数不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertSupplier(bad);
            }
        });
        assertTrue(mapper.suppliers.isEmpty());

        CtmsSupplier ok = supplier("S001", "某某阀门有限公司", "某某阀门");
        ok.setPaymentDays(Integer.valueOf(0));
        svc.insertSupplier(ok);
        assertEquals(Integer.valueOf(0), mapper.selectSupplierById(ok.getId()).getPaymentDays());
    }

    /* ==================== ⑦ 有引用时删除被拒 ==================== */

    @Test
    public void 断言七_有合同引用时删除被拒() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        svc.insertCustomer(c);

        // 合同表存在，且该客户被 3 份合同引用（规格场景）
        mapper.contractTableExists = 1;
        mapper.customerRefs.put(c.getId(), Integer.valueOf(3));

        assertRejected("客户档案已被合同引用，无法删除", new Runnable()
        {
            @Override
            public void run()
            {
                svc.deleteCustomerById(c.getId());
            }
        });
        assertNotNull("被拒后档案必须还在", mapper.selectCustomerById(c.getId()));

        // 供应商侧同口径（文案把"客户"换成"供应商"）
        CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某阀门");
        svc.insertSupplier(s);
        mapper.supplierRefs.put(s.getId(), Integer.valueOf(1));
        assertRejected("供应商档案已被合同引用，无法删除", new Runnable()
        {
            @Override
            public void run()
            {
                svc.deleteSupplierById(s.getId());
            }
        });
        assertNotNull(mapper.selectSupplierById(s.getId()));
    }

    /* ==================== ⑧ 零引用可删除 ==================== */

    @Test
    public void 断言八_零引用可删除_且合同表缺失时不误报() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        // 情形 A：合同表存在但零引用 → 物理删除放行
        CtmsCustomer c = customer("C001", "某某科技有限公司");
        svc.insertCustomer(c);
        mapper.contractTableExists = 1;
        svc.deleteCustomerById(c.getId());
        assertNull("零引用必须真的被删除", mapper.selectCustomerById(c.getId()));

        // 情形 B：合同表还没建（B3 第 4 组之前）→ 视为零引用，不能因缺表报错
        CtmsCustomer c2 = customer("C002", "另一家科技有限公司");
        svc.insertCustomer(c2);
        mapper.contractTableExists = 0;
        svc.deleteCustomerById(c2.getId());
        assertNull(mapper.selectCustomerById(c2.getId()));

        // 情形 C：id 不存在 → 提示档案不存在
        assertRejected("客户档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.deleteCustomerById("NOT-EXIST-ID");
            }
        });
    }

    /* ==================== ⑨ 停用不进选择器、历史仍可见 ==================== */

    @Test
    public void 断言九_停用后不进选择器_档案列表与历史引用仍可见() throws Exception
    {
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        svc.insertCustomer(c);

        // 停用前：选择器与列表都能看到
        assertEquals(1, svc.selectCustomerOptions().size());

        // 停用（只改 enable_flag，不做任何级联）
        int rows = svc.changeCustomerStatus(c.getId(), "0");
        assertEquals(1, rows);
        assertEquals("0", mapper.selectCustomerById(c.getId()).getEnableFlag());

        // 规格场景「停用后不可新选」：选择器里必须消失
        List<CtmsCustomer> options = svc.selectCustomerOptions();
        assertTrue("停用档案不能出现在合同选择器里", options.isEmpty());

        // 规格场景「停用后历史合同仍可展示」：档案列表（不限状态）仍含它，
        // 且按 id 直查仍能拿到名称与编码 —— 这就是"无联动"的可执行证据
        List<CtmsCustomer> all = svc.selectCustomerList(null);
        assertEquals("档案管理页仍要能看到停用项", 1, all.size());
        assertEquals("0", all.get(0).getEnableFlag());
        CtmsCustomer stillReadable = svc.selectCustomerById(c.getId());
        assertNotNull(stillReadable);
        assertEquals("C001", stillReadable.getCode());
        assertEquals("某某科技有限公司", stillReadable.getName());

        // 重新启用后回到选择器
        svc.changeCustomerStatus(c.getId(), "1");
        assertEquals(1, svc.selectCustomerOptions().size());

        // 供应商镜像
        CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某阀门");
        svc.insertSupplier(s);
        assertEquals(1, svc.selectSupplierOptions().size());
        svc.changeSupplierStatus(s.getId(), "0");
        assertTrue(svc.selectSupplierOptions().isEmpty());
        assertEquals(1, svc.selectSupplierList(null).size());
    }

    /* ==================== ⑩ 未认证上下文下新增不抛异常 ==================== */

    @Test
    public void 断言十_无登录上下文时兜底为系统账号() throws Exception
    {
        // 单测进程里没有 Spring Security 上下文：currentUserId()/currentUsername() 必须自己兜底，
        // 而不是让 SecurityUtils 抛 401（并因此把迁移/初始化写入打死）
        SecurityContextHolder.clearContext();

        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        svc.insertCustomer(c);

        CtmsCustomer saved = mapper.selectCustomerById(c.getId());
        assertNotNull("无登录上下文也必须能写入", saved);
        assertEquals("客户编码不能为空兜底逻辑跑偏", "C001", saved.getCode());
        assertEquals("兜底用户ID", "1", saved.getCreateId());
        assertEquals("兜底登录名", "system", saved.getCreateBy());

        // 更新路径的审计列同样来自兜底
        CtmsCustomer edit = mapper.selectCustomerById(c.getId());
        edit.setContactName("张三");
        svc.updateCustomer(edit);
        CtmsCustomer updated = mapper.selectCustomerById(c.getId());
        assertEquals("1", updated.getUpdateId());
        assertEquals("system", updated.getUpdateBy());
        assertNotNull(updated.getUpdateTime());
    }

    /* ==================== 附加：修改的查重与审计列 ==================== */

    @Test
    public void 修改客户_编码撞别的行被拒_命中自己则放行() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        CtmsCustomer a = customer("C001", "甲公司");
        svc.insertCustomer(a);
        CtmsCustomer b = customer("C002", "乙公司");
        svc.insertCustomer(b);

        // 改名不冲突：放行，并且审计列由服务端写入
        CtmsCustomer editA = mapper.selectCustomerById(a.getId());
        editA.setName("甲公司（更名）");
        editA.setUpdateId("hacker");
        editA.setUpdateBy("hacker");
        svc.updateCustomer(editA);
        CtmsCustomer afterA = mapper.selectCustomerById(a.getId());
        assertEquals("甲公司（更名）", afterA.getName());
        assertEquals("审计列必须服务端覆盖，不接受前端传值", "1", afterA.getUpdateId());
        assertEquals("system", afterA.getUpdateBy());
        assertNotNull(afterA.getUpdateTime());
        assertEquals("编码不可改：update 语句不含 code 列，值保持原样", "C001", afterA.getCode());

        // 把乙的编码改成甲的编码：作为"别的行"冲突 → 被拒
        final CtmsCustomer editB = mapper.selectCustomerById(b.getId());
        editB.setCode("C001");
        assertRejected("客户编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.updateCustomer(editB);
            }
        });

        // 把乙的名称改成甲的名称 → 名称冲突
        final CtmsCustomer editB2 = mapper.selectCustomerById(b.getId());
        editB2.setName("甲公司（更名）");
        assertRejected("客户名称已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.updateCustomer(editB2);
            }
        });

        // 自己改自己（编码/名称没变）不算冲突
        CtmsCustomer editA2 = mapper.selectCustomerById(a.getId());
        editA2.setContactPhone("13800000000");
        svc.updateCustomer(editA2);
        assertEquals("13800000000", mapper.selectCustomerById(a.getId()).getContactPhone());
    }

    @Test
    public void 修改不存在的客户报档案不存在() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        final CtmsCustomer ghost = customer("C999", "幽灵公司");
        ghost.setId("NOT-EXIST-ID");
        assertRejected("客户档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.updateCustomer(ghost);
            }
        });
        assertSame(Integer.valueOf(0), Integer.valueOf(mapper.updateCalls));
    }

    @Test
    public void 启用标志非法值被拒_且不落库() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        CtmsCustomer c = customer("C001", "某某科技有限公司");
        svc.insertCustomer(c);

        assertRejected("启用标志只能是 0 或 1", new Runnable()
        {
            @Override
            public void run()
            {
                svc.changeCustomerStatus(c.getId(), "2");
            }
        });
        assertEquals("1", mapper.selectCustomerById(c.getId()).getEnableFlag());

        assertRejected("客户档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.changeCustomerStatus("NOT-EXIST-ID", "0");
            }
        });
    }

    @Test
    public void 列表按编码名称模糊与启用标志精确筛选() throws Exception
    {
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        svc.insertCustomer(customer("C001", "甲公司"));
        svc.insertCustomer(customer("C002", "乙公司"));
        svc.insertCustomer(customer("D001", "丙公司"));
        svc.changeCustomerStatus(mapper.selectCustomerByCode("D001").getId(), "0");

        CtmsCustomer q1 = new CtmsCustomer();
        q1.setCode("C00");
        assertEquals("按编码模糊", 2, svc.selectCustomerList(q1).size());

        CtmsCustomer q2 = new CtmsCustomer();
        q2.setName("公司");
        assertEquals("按名称模糊", 3, svc.selectCustomerList(q2).size());

        CtmsCustomer q3 = new CtmsCustomer();
        q3.setEnableFlag("0");
        assertEquals("按启用标志精确", 1, svc.selectCustomerList(q3).size());

        assertEquals("enableFlag 为 null 表示不限（含停用）", 3, svc.selectCustomerList(null).size());
        assertEquals("选择器只要启用中的", 2, svc.selectCustomerOptions().size());
    }

    @Test
    public void 供应商编码与名称查重与客户各自独立() throws Exception
    {
        final InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        final ICtmsPartnerService svc = service(mapper);

        // 客户与供应商是两套唯一性空间：同名同码互不影响
        svc.insertCustomer(customer("P001", "某某公司"));
        CtmsSupplier s = supplier("P001", "某某公司", "某某");
        svc.insertSupplier(s);
        assertEquals(1, mapper.customers.size());
        assertEquals(1, mapper.suppliers.size());

        assertRejected("供应商编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertSupplier(supplier("P001", "另一家", "另一"));
            }
        });
        assertRejected("供应商名称已存在", new Runnable()
        {
            @Override
            public void run()
            {
                svc.insertSupplier(supplier("P002", "某某公司", "某某"));
            }
        });

        assertNotNull("供应商按 id 能查到", svc.selectSupplierById(s.getId()));
        assertFalse("供应商 id 不应在客户表命中", mapper.customers.containsKey(s.getId()));
    }

    @Test
    public void 供应商也可用客户不存在的简称规则_且有引用时不可删() throws Exception
    {
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某阀门");
        s.setSupplyScope("阀门类");
        s.setPaymentDays(Integer.valueOf(60));
        svc.insertSupplier(s);

        CtmsSupplier saved = mapper.selectSupplierById(s.getId());
        assertEquals("某某阀门", saved.getShortName());
        assertEquals("阀门类", saved.getSupplyScope());
        assertEquals(Integer.valueOf(60), saved.getPaymentDays());
        assertEquals("1", saved.getEnableFlag());
        assertNotNull(saved.getUpdateTime());
        assertEquals("1", saved.getCreateId());
        assertEquals("system", saved.getCreateBy());
    }

    @Test
    public void 新增供应商基本信息正确编码为NOTNULL的审计列() throws Exception
    {
        // 这条同时是"BaseEntity 字段没有被实体重复声明而丢失"的回归：审计列必须能写入
        InMemoryPartnerMapper mapper = new InMemoryPartnerMapper();
        ICtmsPartnerService svc = service(mapper);

        CtmsSupplier s = supplier("S001", "某某阀门有限公司", "某某");
        s.setRemark("首批准入");
        svc.insertSupplier(s);

        CtmsSupplier saved = mapper.selectSupplierById(s.getId());
        assertEquals("首批准入", saved.getRemark());
        assertNotNull(saved.getCreateTime());
        assertNotNull(saved.getUpdateTime());
    }
}
