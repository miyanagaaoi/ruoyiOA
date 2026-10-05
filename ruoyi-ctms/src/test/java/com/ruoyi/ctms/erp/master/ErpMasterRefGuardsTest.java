package com.ruoyi.ctms.erp.master;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 主数据引用守卫单测（2.0 B4 §2.3/§2.4；B3 刻意留给 B4 的部分）。 </p>
 *
 * <p> 三条不变量逐条断言： </p>
 * <ol>
 *   <li> <b>有引用就拒绝</b>，且三档引用（结存 / 流水 / 单据）各有<b>确定的中文文案</b>
 *        （前端与接口验收脚本按文案匹配，所以必须是精确相等而不是包含）； </li>
 *   <li> <b>无引用才放行</b>（不能因为"有守卫"就一律拒绝）； </li>
 *   <li> <b>统计未装配时保护性拒绝</b>（宁可报错，也不允许无校验地物理删档）。 </li>
 * </ol>
 *
 * <p> 不依赖 Spring / 数据库：引用计数用可配置的内存桩
 * （真实 SQL 的覆盖见 {@code tools/ctms-masterdata-check.ps1} 与 notes/02-master.md 的断言清单）。 </p>
 *
 * @author 二开
 */
public class ErpMasterRefGuardsTest
{
    /** 可逐档设置的引用统计桩。 */
    private static class StubCounter implements ErpMasterRefGuards.RefCounter
    {
        private int stockRefs;

        private int ledgerRefs;

        private int warehouseDocRefs;

        private int productDocRefs;

        private String lastWarehouseId;

        private String lastProductId;

        private boolean warehouseDocCalled;

        private boolean productDocCalled;

        @Override
        public int countStockRefs(String warehouseId, String productId)
        {
            this.lastWarehouseId = warehouseId;
            this.lastProductId = productId;
            return stockRefs;
        }

        @Override
        public int countLedgerRefs(String warehouseId, String productId)
        {
            return ledgerRefs;
        }

        @Override
        public int countDocRefsByWarehouse(String warehouseId)
        {
            this.warehouseDocCalled = true;
            return warehouseDocRefs;
        }

        @Override
        public int countDocRefsByProduct(String productId)
        {
            this.productDocCalled = true;
            return productDocRefs;
        }
    }

    /* ==================== 仓库 ==================== */

    @Test
    public void 仓库三档引用的文案与优先级()
    {
        StubCounter counter = new StubCounter();

        counter.stockRefs = 1;
        assertRejected(ErpMasterRefGuards.WAREHOUSE_STOCK_MESSAGE, counter, "W1", true);

        counter.stockRefs = 0;
        counter.ledgerRefs = 3;
        assertRejected(ErpMasterRefGuards.WAREHOUSE_LEDGER_MESSAGE, counter, "W1", true);

        counter.ledgerRefs = 0;
        counter.warehouseDocRefs = 1;
        assertRejected(ErpMasterRefGuards.WAREHOUSE_DOC_MESSAGE, counter, "W1", true);
    }

    @Test
    public void 仓库无引用时放行且只查仓库维度()
    {
        StubCounter counter = new StubCounter();
        ErpMasterRefGuards.checkWarehouseDeletable(counter, "W1");
        assertEquals("仓库判定必须按 warehouseId 查结存", "W1", counter.lastWarehouseId);
        assertEquals("仓库判定不得按物料维度查", null, counter.lastProductId);
        assertTrue("无引用应判定为可删", ErpMasterRefGuards.isWarehouseDeletable(counter, "W1"));
    }

    @Test
    public void 仓库ID为空时按不存在拒绝()
    {
        StubCounter counter = new StubCounter();
        assertEquals("仓库不存在", messageOf(counter, null, true));
        assertEquals("仓库不存在", messageOf(counter, "   ", true));
    }

    /* ==================== 物料 ==================== */

    @Test
    public void 物料三档引用的文案()
    {
        StubCounter counter = new StubCounter();

        counter.stockRefs = 1;
        assertRejected(ErpMasterRefGuards.PRODUCT_STOCK_MESSAGE, counter, "P1", false);

        counter.stockRefs = 0;
        counter.ledgerRefs = 1;
        assertRejected(ErpMasterRefGuards.PRODUCT_LEDGER_MESSAGE, counter, "P1", false);

        counter.ledgerRefs = 0;
        counter.productDocRefs = 2;
        assertRejected(ErpMasterRefGuards.PRODUCT_DOC_MESSAGE, counter, "P1", false);
    }

    @Test
    public void 物料无引用时放行且按单据行项维度查()
    {
        StubCounter counter = new StubCounter();
        ErpMasterRefGuards.checkProductDeletable(counter, "P1");
        assertTrue("必须查过单据行项引用", counter.productDocCalled);
        assertFalse("仓库维度的统计不该被调用", counter.warehouseDocCalled);
        assertTrue(ErpMasterRefGuards.isProductDeletable(counter, "P1"));
    }

    /* ==================== 保护性拒绝 ==================== */

    @Test
    public void 统计未装配时保护性拒绝()
    {
        assertRejected(ErpMasterRefGuards.COUNTER_MISSING_MESSAGE, null, "W1", true);
        assertRejected(ErpMasterRefGuards.COUNTER_MISSING_MESSAGE, null, "P1", false);
        assertFalse("统计缺失时不得判定为可删", ErpMasterRefGuards.isWarehouseDeletable(null, "W1"));
        assertFalse("统计缺失时不得判定为可删", ErpMasterRefGuards.isProductDeletable(null, "P1"));
    }

    @Test
    public void 文案被冻结()
    {
        assertEquals("仓库已有库存结存，无法删除", ErpMasterRefGuards.WAREHOUSE_STOCK_MESSAGE);
        assertEquals("仓库已有库存流水，无法删除", ErpMasterRefGuards.WAREHOUSE_LEDGER_MESSAGE);
        assertEquals("仓库已被单据引用，无法删除", ErpMasterRefGuards.WAREHOUSE_DOC_MESSAGE);
        assertEquals("物料已有库存结存，无法删除", ErpMasterRefGuards.PRODUCT_STOCK_MESSAGE);
        assertEquals("物料已有库存流水，无法删除", ErpMasterRefGuards.PRODUCT_LEDGER_MESSAGE);
        assertEquals("物料已被单据行项引用，无法删除", ErpMasterRefGuards.PRODUCT_DOC_MESSAGE);
    }

    /* ==================== 工具 ==================== */

    private static void assertRejected(String expectedMessage, ErpMasterRefGuards.RefCounter counter,
                                       String id, boolean warehouse)
    {
        assertEquals(expectedMessage, messageOf(counter, id, warehouse));
    }

    private static String messageOf(ErpMasterRefGuards.RefCounter counter, String id, boolean warehouse)
    {
        try
        {
            if (warehouse)
            {
                ErpMasterRefGuards.checkWarehouseDeletable(counter, id);
            }
            else
            {
                ErpMasterRefGuards.checkProductDeletable(counter, id);
            }
            fail("应当被拒绝但通过了（" + (warehouse ? "仓库 " : "物料 ") + id + "）");
            return null;
        }
        catch (ServiceException e)
        {
            return e.getMessage();
        }
    }
}
