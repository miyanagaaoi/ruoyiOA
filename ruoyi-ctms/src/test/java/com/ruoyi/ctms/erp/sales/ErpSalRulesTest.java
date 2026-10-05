package com.ruoyi.ctms.erp.sales;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 销售线纯规则（{@link ErpSalRules}）与两个行项累计列的表驱动单测。 </p>
 *
 * <p> 这些口径都是"写错不报错、数据却会错"的类型，所以逐条锁定<b>数值</b>与<b>文案</b>：
 * 剩余量算法、超量拦截的提示（剩余 R / 本次 Q）、归零即完成的判据、合同方向判定、
 * 客户启停用判定、已出库量的回退不小于 0。 </p>
 *
 * @author 二开
 */
public class ErpSalRulesTest
{
    /* ==================== 剩余量与"归零即完成" ==================== */

    @Test
    public void remainingQtyIsOriginalMinusOrdered()
    {
        ErpSalesRequestItem item = requestItem("80", "30");
        assertEquals(0, new BigDecimal("50").compareTo(item.remainingQty()));
        assertFalse(item.isFullyOrdered());

        item.addOrderedQty(new BigDecimal("50"));
        assertEquals(0, new BigDecimal("80").compareTo(item.getOrderedQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.remainingQty()));
        assertTrue(item.isFullyOrdered());
    }

    @Test
    public void orderedQtyDefaultsToZeroWhenNull()
    {
        ErpSalesRequestItem item = requestItem("10", null);
        assertEquals(0, new BigDecimal("10").compareTo(item.remainingQty()));
    }

    @Test
    public void fullyOrderedRequiresEveryLineZero()
    {
        ErpSalesRequestItem done = requestItem("10", "10");
        ErpSalesRequestItem open = requestItem("5", "2");
        assertFalse(ErpSalRules.isFullyOrdered(Arrays.asList(done, open)));
        assertTrue(ErpSalRules.isFullyOrdered(Arrays.asList(done, requestItem("5", "5"))));
        // 空集合不判为"推完了"（无行项本身连提交都过不去）
        assertFalse(ErpSalRules.isFullyOrdered(new ArrayList<ErpSalesRequestItem>()));
        assertFalse(ErpSalRules.isFullyOrdered(null));
    }

    @Test
    public void remainingQtySumClampsNegativeToZero()
    {
        // 脏数据（已下量 > 数量）时合计按 0 计，不把负数带给前端
        List<ErpSalesRequestItem> items = Arrays.asList(requestItem("10", "12"), requestItem("5", "1"));
        assertEquals(0, new BigDecimal("4").compareTo(ErpSalRules.remainingQtySum(items)));
    }

    /* ==================== 下推数量解析与超量拦截 ==================== */

    @Test
    public void resolvePushQtyFallsBackToRemaining()
    {
        assertEquals(0, new BigDecimal("50").compareTo(
                ErpSalRules.resolvePushQty(null, new BigDecimal("50"))));
        assertEquals(0, new BigDecimal("50").compareTo(
                ErpSalRules.resolvePushQty(BigDecimal.ZERO, new BigDecimal("50"))));
        assertEquals(0, new BigDecimal("20").compareTo(
                ErpSalRules.resolvePushQty(new BigDecimal("20"), new BigDecimal("50"))));
    }

    @Test
    public void resolvePushQtyRejectsWhenNothingRemains()
    {
        try
        {
            ErpSalRules.resolvePushQty(new BigDecimal("1"), BigDecimal.ZERO);
            fail("剩余量为 0 时不应可下推");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("已无可下推数量"));
        }
    }

    @Test
    public void overPushMessageCarriesRemainingAndRequested()
    {
        try
        {
            ErpSalRules.checkPushQty("螺丝", new BigDecimal("50"), new BigDecimal("60"));
            fail("超量下推不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("「螺丝」可下推数量不足（剩余 50，本次 60）", e.getMessage());
        }
    }

    @Test
    public void pushQtyMustBePositive()
    {
        try
        {
            ErpSalRules.checkPushQty("螺丝", new BigDecimal("50"), BigDecimal.ZERO);
            fail("下推数量为 0 不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("下推数量必须大于 0", e.getMessage());
        }
    }

    @Test
    public void onlyApprovedRequestCanBePushed()
    {
        ErpSalRules.checkPusheable(ErpDocStatus.APPROVED);
        for (String status : Arrays.asList(ErpDocStatus.DRAFT, ErpDocStatus.SUBMITTED,
                ErpDocStatus.COMPLETED, ErpDocStatus.VOIDED))
        {
            try
            {
                ErpSalRules.checkPusheable(status);
                fail("状态 " + status + " 不应可下推");
            }
            catch (ServiceException e)
            {
                assertEquals("仅已审核的销售申请单可以下推销售订单", e.getMessage());
            }
        }
    }

    /* ==================== 合同方向 ==================== */

    @Test
    public void onlySaleDirectionContractIsAccepted()
    {
        ErpSalRules.checkContractDirection("销售/收入");
        assertTrue(ErpSalRules.isSaleDirection("销售/收入"));
        assertFalse(ErpSalRules.isSaleDirection("采购/支出"));
        try
        {
            ErpSalRules.checkContractDirection("采购/支出");
            fail("采购方向合同不应可关联到销售单据");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("只能关联销售方向的合同"));
        }
    }

    @Test
    public void missingContractTypeIsReportedAsNotFound()
    {
        try
        {
            ErpSalRules.checkContractDirection(null);
            fail("合同类型缺失不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("关联合同不存在或已停用", e.getMessage());
        }
    }

    /* ==================== 客户引用 ==================== */

    @Test
    public void customerMustExistAndBeEnabled()
    {
        ErpSalRules.checkCustomerUsable("C1", "启用客户", "1");

        try
        {
            ErpSalRules.checkCustomerUsable(null, null, null);
            fail("未选客户不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("必须选择客户档案"));
        }

        try
        {
            ErpSalRules.checkCustomerUsable("C9", null, null);
            fail("客户档案不存在不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("客户档案不存在"));
        }

        try
        {
            ErpSalRules.checkCustomerUsable("C2", "停用客户", "0");
            fail("停用客户不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
            assertTrue(e.getMessage(), e.getMessage().contains("停用客户"));
        }
    }

    @Test
    public void supplierAsCustomerIsReportedAsNotFound()
    {
        try
        {
            ErpSalRules.rejectSupplierAsCustomer("SUP-1");
            fail("供应商ID传到客户字段不应通过");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("客户档案不存在"));
            assertTrue(e.getMessage(), e.getMessage().contains("供应商档案"));
        }
    }

    /* ==================== 行项守卫 ==================== */

    @Test
    public void normalizeItemsResequencesAndRecalculatesAmounts()
    {
        ErpSalesOrderItem first = orderItem("1", "0.125");
        ErpSalesOrderItem second = orderItem("3", "0.1");
        int count = ErpSalRules.normalizeItems(Arrays.asList(first, second));

        assertEquals(2, count);
        assertEquals(Integer.valueOf(1), first.getSeq());
        assertEquals(Integer.valueOf(2), second.getSeq());
        assertEquals(0, new BigDecimal("0.13").compareTo(first.getAmount()));
        assertEquals(0, new BigDecimal("0.30").compareTo(second.getAmount()));
        assertEquals(0, new BigDecimal("0.43").compareTo(
                com.ruoyi.ctms.erp.base.ErpAmounts.totalOf(Arrays.asList(first, second))));
    }

    @Test
    public void normalizeItemsRejectsZeroOrNegativeQtyAndNegativePrice()
    {
        assertRejected(orderItem("0", "1"), "数量必须大于 0");
        assertRejected(orderItem("-1", "1"), "数量必须大于 0");
        assertRejected(orderItem("1", "-1"), "单价不能为负数");
        // 单价为 0 合法（赠品行）
        assertEquals(1, ErpSalRules.normalizeItems(Arrays.asList(orderItem("1", "0"))));
    }

    @Test
    public void requireAtLeastOneItemRejectsEmpty()
    {
        try
        {
            ErpSalRules.requireAtLeastOneItem(0);
            fail("没有行项不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("单据没有行项", e.getMessage());
        }
        ErpSalRules.requireAtLeastOneItem(1);
    }

    /* ==================== 已出库量的累加与回退 ==================== */

    @Test
    public void shippedQtyAccumulatesAndNeverGoesNegative()
    {
        ErpSalesOrderItem item = orderItem("10", "1");
        item.addShippedQty(new BigDecimal("4"));
        assertEquals(0, new BigDecimal("4").compareTo(item.getShippedQty()));
        assertEquals(0, new BigDecimal("6").compareTo(item.remainingQty()));

        // 红冲回退：减到 0 为止，不产生负数
        item.subtractShippedQty(new BigDecimal("9"));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.getShippedQty()));
        assertEquals(0, new BigDecimal("10").compareTo(item.remainingQty()));
    }

    @Test
    public void remainingShippableQtySumClampsNegativeToZero()
    {
        ErpSalesOrderItem a = orderItem("10", "1");
        a.setShippedQty(new BigDecimal("3"));
        ErpSalesOrderItem b = orderItem("5", "1");
        b.setShippedQty(new BigDecimal("7"));
        assertEquals(0, new BigDecimal("7").compareTo(
                ErpSalRules.remainingShippableQtySum(Arrays.asList(a, b))));
    }

    /* ==================== 表头与状态 ==================== */

    @Test
    public void headerRequiresDocDate()
    {
        ErpSalesRequest request = new ErpSalesRequest();
        try
        {
            ErpSalRules.checkSalesRequestHeader(request);
            fail("单据日期为空不应通过");
        }
        catch (ServiceException e)
        {
            assertEquals("单据日期不能为空", e.getMessage());
        }
    }

    @Test
    public void fieldLabelFallsBackToRawName()
    {
        assertEquals("状态", ErpSalRules.fieldLabel("status"));
        assertEquals("关联合同", ErpSalRules.fieldLabel("关联合同"));
        assertEquals("unknownField", ErpSalRules.fieldLabel("unknownField"));
    }

    /* ==================== 夹具 ==================== */

    private static ErpSalesRequestItem requestItem(String qty, String orderedQty)
    {
        ErpSalesRequestItem item = new ErpSalesRequestItem();
        item.setQty(new BigDecimal(qty));
        if (orderedQty != null)
        {
            item.setOrderedQty(new BigDecimal(orderedQty));
        }
        return item;
    }

    private static ErpSalesOrderItem orderItem(String qty, String unitPrice)
    {
        ErpSalesOrderItem item = new ErpSalesOrderItem();
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    private static void assertRejected(ErpSalesOrderItem item, String expectedFragment)
    {
        try
        {
            ErpSalRules.normalizeItems(Arrays.asList(item));
            fail("应当被拒：" + expectedFragment);
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
        }
    }
}
