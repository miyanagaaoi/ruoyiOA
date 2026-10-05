package com.ruoyi.ctms.erp.ledger.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.ledger.ErpLedgerRules;
import com.ruoyi.ctms.erp.ledger.ErpLedgerTestSupport;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>库存账查询服务单测</b>（B4 任务 7.1 / 7.3 + 数据范围的 403/404 分支）。 </p>
 *
 * <p> 数据范围分支靠 {@link ScopedService} 覆写 {@code currentScopeSql()} 打开：
 * 单测里没有 Spring Security 上下文，{@code ErpDocScope} 会返回 null（不受限），
 * 那样"范围外 403"永远走不到 —— 也就等于没测。 </p>
 *
 * @author 二开
 */
public class ErpStockLedgerQueryServiceImplTest
{
    /** 结存桩。 */
    private ErpLedgerTestSupport.StubBalanceMapper balanceMapper;

    /** 流水桩。 */
    private ErpLedgerTestSupport.StubLedgerQueryMapper ledgerMapper;

    /** 类型桩。 */
    private ErpLedgerTestSupport.StubProductTypeMapper typeMapper;

    /** 被测服务（可注入固定范围片段）。 */
    private ScopedService service;

    /**
     * 被测服务的测试子类：把"当前用户的数据范围片段"变成可控字段。
     */
    private static class ScopedService extends ErpStockLedgerQueryServiceImpl
    {
        /** 固定返回的片段（null = 不受限）。 */
        private String scope;

        @Override
        protected String currentScopeSql()
        {
            return scope;
        }
    }

    /**
     * 组装被测服务与三个桩。
     */
    @Before
    public void setUp()
    {
        balanceMapper = new ErpLedgerTestSupport.StubBalanceMapper();
        ledgerMapper = new ErpLedgerTestSupport.StubLedgerQueryMapper();
        typeMapper = new ErpLedgerTestSupport.StubProductTypeMapper();
        service = new ScopedService();
        ErpPostingTestSupport.inject(service, "stockBalanceMapper", balanceMapper);
        ErpPostingTestSupport.inject(service, "stockLedgerQueryMapper", ledgerMapper);
        ErpPostingTestSupport.inject(service, "productTypeMapper", typeMapper);
    }

    /**
     * 造一个相对"现在"偏移若干小时的时间。
     *
     * @param hoursAgo 小时数
     * @return 时间
     */
    private static Date hoursAgo(int hoursAgo)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, -hoursAgo);
        return calendar.getTime();
    }

    /* ==================== 7.1 库存明细 ==================== */

    @Test
    public void 明细行的名称列是商品类型名称减物料名称()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "2", "5", "五金", "螺丝", "t1"));
        List<ErpStockBalance> rows = service.selectBalanceList(new ErpStockBalance());

        assertEquals(1, rows.size());
        assertEquals("五金-螺丝", rows.get(0).getDisplayName());
        assertTrue("结存 2 < 安全库存 5", rows.get(0).isBelowSafetyStock());
    }

    @Test
    public void 低于安全库存筛选命中结存二而不含结存六()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p-low", "w1", "2", "5"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p-ok", "w1", "6", "5"));

        ErpStockBalance query = new ErpStockBalance();
        query.setBelowSafetyOnly(Boolean.TRUE);
        List<ErpStockBalance> rows = service.selectBalanceList(query);

        assertEquals(1, rows.size());
        assertEquals("p-low", rows.get(0).getProductId());
        assertEquals(0, new BigDecimal("2").compareTo(rows.get(0).getQty()));
    }

    @Test
    public void 数量区间筛选()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p2", "w1", "2", "0"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p6", "w1", "6", "0"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p12", "w1", "12", "0"));

        ErpStockBalance query = new ErpStockBalance();
        query.setBeginQty(new BigDecimal("5"));
        query.setEndQty(new BigDecimal("10"));
        List<ErpStockBalance> rows = service.selectBalanceList(query);

        assertEquals(1, rows.size());
        assertEquals("p6", rows.get(0).getProductId());
    }

    @Test
    public void 商品类型按子树筛选_选父带子()
    {
        // 类型树：五金(A) → 紧固件(B)；另一个根 办公(E)
        typeMapper.seed(ErpLedgerTestSupport.type("A", null, "五金"));
        typeMapper.seed(ErpLedgerTestSupport.type("B", "A", "紧固件"));
        typeMapper.seed(ErpLedgerTestSupport.type("E", null, "办公"));

        balanceMapper.seed(ErpLedgerTestSupport.balance("p-screw", "w1", "3", "0", "紧固件", "螺丝", "B"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p-pen", "w1", "3", "0", "办公", "签字笔", "E"));

        ErpStockBalance query = new ErpStockBalance();
        query.setProductTypeId("A");
        List<ErpStockBalance> rows = service.selectBalanceList(query);

        assertEquals("选 五金 应带出挂在 紧固件(子) 下的物料", 1, rows.size());
        assertEquals("p-screw", rows.get(0).getProductId());
        assertEquals("子树展开结果应为 [A, B]", java.util.Arrays.asList("A", "B"), query.getProductTypeIds());
    }

    @Test
    public void 物料关键字按编码或名称模糊()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "3", "0", "五金", "螺丝", "t1"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p2", "w1", "3", "0", "五金", "螺母", "t1"));

        ErpStockBalance byName = new ErpStockBalance();
        byName.setKeyword("螺");
        assertEquals(2, service.selectBalanceList(byName).size());

        ErpStockBalance byCode = new ErpStockBalance();
        byCode.setKeyword("P-p2");
        List<ErpStockBalance> rows = service.selectBalanceList(byCode);
        assertEquals(1, rows.size());
        assertEquals("p2", rows.get(0).getProductId());
    }

    @Test
    public void 隐藏零结存开关()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p-zero", "w1", "0", "0"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p-some", "w1", "3", "0"));

        assertEquals("默认列出零结存", 2, service.selectBalanceList(new ErpStockBalance()).size());

        ErpStockBalance query = new ErpStockBalance();
        query.setHideZero(Boolean.TRUE);
        List<ErpStockBalance> rows = service.selectBalanceList(query);
        assertEquals(1, rows.size());
        assertEquals("p-some", rows.get(0).getProductId());
    }

    /* ==================== 7.3 库存流水 ==================== */

    @Test
    public void 流水下钻按时间倒序且字段齐备()
    {
        Date newest = hoursAgo(1);
        Date middle = hoursAgo(2);
        Date oldest = hoursAgo(3);
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L3", "p1", "w1", "采购入库", "10", "2.5", newest));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "5", "2.5", oldest));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L2", "p1", "w1", "红冲-采购入库", "-5", "2.5", middle));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L9", "p2", "w1", "采购入库", "1", "1", newest));

        ErpStockLedgerRow query = new ErpStockLedgerRow();
        query.setProductId("p1");
        query.setWarehouseId("w1");
        List<ErpStockLedgerRow> rows = service.selectLedgerRows(query);

        assertEquals("下钻只含该（物料, 仓库）", 3, rows.size());
        assertEquals("按记账时间倒序", java.util.Arrays.asList("L3", "L2", "L1"),
                java.util.Arrays.asList(rows.get(0).getId(), rows.get(1).getId(), rows.get(2).getId()));

        ErpStockLedgerRow first = rows.get(0);
        assertNotNull("业务类型", first.getBizType());
        assertNotNull("单据类型", first.getDocType());
        assertNotNull("单据号", first.getDocNo());
        assertNotNull("来源单号", first.getSrcDocNo());
        assertNotNull("数量变动", first.getQtyChange());
        assertNotNull("变动后结存", first.getQtyAfter());
        assertNotNull("单价", first.getUnitPrice());
        assertNotNull("操作人", first.getCreateBy());
        assertNotNull("时间", first.getLedgerTime());
        assertEquals("类型-甲-物料-p1", first.getDisplayName());
        assertTrue("红冲行可识别", rows.get(1).isReversal());
        assertFalse("非红冲行不得被误判", first.isReversal());
    }

    @Test
    public void 流水时间区间与红冲行都保留()
    {
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L-old", "p1", "w1", "采购入库", "10", "2", hoursAgo(48)));
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L-new", "p1", "w1", "红冲-采购入库", "-10", "2", hoursAgo(1)));

        ErpStockLedgerRow query = new ErpStockLedgerRow();
        query.setProductId("p1");
        query.setWarehouseId("w1");
        query.setBeginTime(hoursAgo(24));
        List<ErpStockLedgerRow> rows = service.selectLedgerRows(query);

        assertEquals("区间外的原流水被过滤，区间内的红冲流水在", 1, rows.size());
        assertEquals("L-new", rows.get(0).getId());
        assertEquals("红冲-采购入库", rows.get(0).getBizType());
    }

    /* ==================== 数据范围：403 / 404 可区分 ==================== */

    @Test
    public void 明细详情_不存在返回404()
    {
        try
        {
            service.selectBalanceByKey("p-none", "w1");
            fail("应当抛 404");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(404), e.getCode());
            assertEquals(ErpLedgerRules.MSG_STOCK_NOT_FOUND, e.getMessage());
        }
    }

    @Test
    public void 明细详情_范围外返回403()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "3", "0"));
        service.scope = "l.create_id = '1'";

        try
        {
            service.selectBalanceByKey("p1", "w1");
            fail("应当抛 403");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
            assertEquals(ErpLedgerRules.MSG_SCOPE_FORBIDDEN, e.getMessage());
        }
    }

    @Test
    public void 明细详情_范围内放行()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "3", "0"));
        balanceMapper.markVisible("p1", "w1");
        service.scope = "l.create_id = '1'";

        ErpStockBalance row = service.selectBalanceByKey("p1", "w1");
        assertEquals("p1", row.getProductId());
    }

    @Test
    public void 明细详情_无范围片段时不限制()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "3", "0"));
        service.scope = null;

        assertEquals("p1", service.selectBalanceByKey("p1", "w1").getProductId());
    }

    @Test
    public void 流水详情_不存在返回404_范围外返回403_范围内放行()
    {
        ledgerMapper.seed(ErpLedgerTestSupport.ledger("L1", "p1", "w1", "采购入库", "10", "2", hoursAgo(1)));

        try
        {
            service.selectLedgerRow("L-none");
            fail("应当抛 404");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(404), e.getCode());
        }

        service.scope = "l.dept_id = '100'";
        try
        {
            service.selectLedgerRow("L1");
            fail("应当抛 403");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
        }

        ledgerMapper.markVisible("L1");
        assertEquals("L1", service.selectLedgerRow("L1").getId());
    }

    @Test
    public void 列表查询会把范围片段交给Mapper()
    {
        service.scope = "l.create_id = '1'";
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "3", "0"));
        balanceMapper.markVisible("p1", "w1");
        balanceMapper.seed(ErpLedgerTestSupport.balance("p2", "w1", "3", "0"));

        List<ErpStockBalance> rows = service.selectBalanceList(new ErpStockBalance());
        assertEquals("范围外的 p2 被片段挡掉", 1, rows.size());
        assertEquals("p1", rows.get(0).getProductId());

        List<ErpStockLedgerRow> ledgerRows = service.selectLedgerRows(new ErpStockLedgerRow());
        assertEquals("流水列表同样带上片段（此桩未标记任何可见流水）", 0, ledgerRows.size());
    }

    @Test
    public void 结存行数走只读计数()
    {
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "1", "0"));
        balanceMapper.seed(ErpLedgerTestSupport.balance("p2", "w1", "1", "0"));
        assertEquals(2, service.countBalance());
    }

    @Test
    public void 空查询参数不会空指针()
    {
        List<ErpStockBalance> rows = service.selectBalanceList(null);
        assertNotNull(rows);
        assertTrue(rows.isEmpty());
        assertNotNull(service.selectLedgerRows(null));

        List<ErpStockBalance> typed = new ArrayList<>();
        balanceMapper.seed(ErpLedgerTestSupport.balance("p1", "w1", "1", "0"));
        typed.addAll(service.selectBalanceList(new ErpStockBalance()));
        assertEquals(1, typed.size());
    }
}
