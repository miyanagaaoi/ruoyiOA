package com.ruoyi.ctms.erp.base.service.impl;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.mapper.ErpDocLookupMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 单据对象访问校验单测（2.0 B4 任务 1.4 的附件接入点 ②；tasks.md §6.6）。 </p>
 *
 * <p> <b>不依赖 Spring / 数据库</b>：Mapper 用内存桩；数据范围的判定走
 * {@link ErpDocObjectAccessServiceImpl} 的 protected 钩子（子类固定"有无全部范围"），
 * 因此"范围外 403"这条分支能被真实命中 —— 否则无登录上下文时
 * {@code ErpDocScope.hasAllScope()} 会兜底为 true，这条分支永远测不到。 </p>
 *
 * @author 二开
 */
public class ErpDocObjectAccessServiceImplTest
{
    private final StubDocLookupMapper mapper = new StubDocLookupMapper();

    private final TestableAccess service = new TestableAccess();

    @Before
    public void setUp()
    {
        service.setMapper(mapper);
    }

    /** 具备全部范围（等价于超管）→ 只要对象存在就放行。 */
    @Test
    public void 全部范围时只校验对象存在()
    {
        mapper.put("purchase_order", "PO1", "u9", "200");
        service.setAllScope(true);
        service.checkObjectAccess("purchase_order", "PO1");
        assertTrue("6 类单据都应由本实现支持", service.supports("stock_transfer"));
        assertTrue(service.supports("stock_take"));
        assertFalse("合同对象不属于本实现（由 B3 的合同服务处理）", service.supports("contract"));
        assertFalse("未知类型不属于本实现", service.supports("ctms_unknown_object"));
    }

    @Test
    public void 对象不存在时按单据名报错()
    {
        service.setAllScope(true);
        assertRejected("采购单不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("purchase_order", "NOT-EXIST");
            }
        });
        assertRejected("调拨单不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("stock_transfer", "NOT-EXIST");
            }
        });
        assertRejected("盘点单不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("stock_take", "NOT-EXIST");
            }
        });
    }

    @Test
    public void 未注册类型被拒()
    {
        assertRejected("未注册的对象类型", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("ctms_unknown_object", "X1");
            }
        });
        assertRejected("未注册的对象类型", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("contract", "C1");
            }
        });
    }

    @Test
    public void 范围外返回业务码403()
    {
        mapper.put("stock_in", "SI1", "u9", "200");
        service.setAllScope(false);
        service.setRowAccessible(false);
        try
        {
            service.checkObjectAccess("stock_in", "SI1");
            fail("范围外必须被拒");
        }
        catch (ServiceException e)
        {
            assertEquals("必须带 403 业务码（与 authz-check 的 IsForbidden 形态一致）",
                    Integer.valueOf(403), e.getCode());
            assertTrue("文案要点名单据：" + e.getMessage(), e.getMessage().contains("无权访问该入库单"));
        }
    }

    @Test
    public void 范围内放行并传入行的创建人与部门()
    {
        mapper.put("sales_order", "SO1", "u1", "101");
        service.setAllScope(false);
        service.setRowAccessible(true);
        service.checkObjectAccess("sales_order", "SO1");
        assertEquals("判定必须拿到行的 create_id", "u1", service.lastRowCreateId);
        assertEquals("判定必须拿到行的 dept_id", "101", service.lastRowDeptId);
    }

    @Test
    public void 对象标识为空被拒()
    {
        assertRejected("对象标识不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.checkObjectAccess("purchase_order", "   ");
            }
        });
    }

    @Test
    public void 单号与状态也能取到()
    {
        mapper.put("stock_out", "SO2", "u1", "101");
        service.setAllScope(true);
        service.checkObjectAccess("stock_out", "SO2");
        assertEquals("单号快照", "NO-SO2", mapper.lastDocNo);
        assertEquals("状态快照", "draft", mapper.lastStatus);
        assertEquals("查询必须按单据类型分派到对应表", "stock_out", mapper.lastDocType);
    }

    @Test
    public void 单据名与表名映射被冻结()
    {
        assertEquals("未知类型回落为「单据」（错误文案里不会出现空名字）", "单据", service.labelOfUnknown());
        assertEquals("采购申请单", service.labelOf("purchase_request"));
        assertEquals("采购单", service.labelOf("purchase_order"));
        assertEquals("销售申请单", service.labelOf("sales_request"));
        assertEquals("销售订单", service.labelOf("sales_order"));
        assertEquals("入库单", service.labelOf("stock_in"));
        assertEquals("出库单", service.labelOf("stock_out"));
        assertEquals("盘点单", service.labelOf("stock_take"));
        assertEquals("调拨单", service.labelOf("stock_transfer"));
    }

    /* ==================== 桩 ==================== */

    /** 可注入 Mapper、可固定范围判定的测试子类。 */
    private static class TestableAccess extends ErpDocObjectAccessServiceImpl
    {
        private boolean allScope;

        private boolean rowAccessible = true;

        private String lastRowCreateId;

        private String lastRowDeptId;

        void setAllScope(boolean allScope)
        {
            this.allScope = allScope;
        }

        void setRowAccessible(boolean rowAccessible)
        {
            this.rowAccessible = rowAccessible;
        }

        void setMapper(ErpDocLookupMapper mapper)
        {
            try
            {
                Field field = ErpDocObjectAccessServiceImpl.class.getDeclaredField("erpDocLookupMapper");
                field.setAccessible(true);
                field.set(this, mapper);
            }
            catch (Exception e)
            {
                throw new IllegalStateException("注入 Mapper 失败", e);
            }
        }

        @Override
        protected boolean hasAllDataScope()
        {
            return allScope;
        }

        @Override
        protected boolean canAccessRow(String rowCreateId, String rowDeptId)
        {
            this.lastRowCreateId = rowCreateId;
            this.lastRowDeptId = rowDeptId;
            return rowAccessible;
        }

        String labelOfUnknown()
        {
            // 父类的 labelOf(String) 是 public，直接调用即可
            // （用一个包内私有方法去"覆盖"它会被 javac 判为"签名不一致/降低了可见性"）
            return labelOf("ctms_unknown_object");
        }
    }

    /** 内存桩：按单据类型 + 标识存一行判定字段。 */
    private static class StubDocLookupMapper implements ErpDocLookupMapper
    {
        private final Map<String, Map<String, Object>> store = new LinkedHashMap<>();

        private String lastDocType;

        private String lastDocNo;

        private String lastStatus;

        void put(String docType, String docId, String createId, String deptId)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", docId);
            row.put("docNo", "NO-" + docId);
            row.put("status", "draft");
            row.put("createId", createId);
            row.put("deptId", deptId);
            store.put(docType + "|" + docId, row);
        }

        @Override
        public Map<String, Object> selectDocHeaderByType(String docType, String docId)
        {
            this.lastDocType = docType;
            Map<String, Object> row = store.get(docType + "|" + docId);
            if (row != null)
            {
                this.lastDocNo = String.valueOf(row.get("docNo"));
                this.lastStatus = String.valueOf(row.get("status"));
            }
            return row;
        }

        @Override
        public String selectDeptAncestors(String deptId)
        {
            if ("101".equals(deptId))
            {
                return "0,100";
            }
            return null;
        }
    }

    private static void assertRejected(String expectedMessagePart, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒但通过了（期望文案含：" + expectedMessagePart + "）");
        }
        catch (ServiceException e)
        {
            assertTrue("文案应含「" + expectedMessagePart + "」，实际：" + e.getMessage(),
                    e.getMessage().contains(expectedMessagePart));
        }
    }
}
