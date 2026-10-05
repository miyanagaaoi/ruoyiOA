package com.ruoyi.ctms.erp.base;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> 行项主数据守卫与快照单测（2.0 B4 任务 1.4；tasks.md §2.4/§5.1、AC-72）。 </p>
 *
 * <p> <b>不依赖 Spring / 数据库</b>：{@link ErpMasterGuards.MasterLookup} 用内存桩，
 * 于是"停用物料/仓库/单位被拒""快照写入""行级仓库回落表头"这几条可以逐条断言到文案。 </p>
 *
 * @author 二开
 */
public class ErpMasterGuardsTest
{
    /** 行项桩（公共基类 + 什么也不加）。 */
    private static class Item extends ErpDocItem
    {
        private static final long serialVersionUID = 1L;
    }

    /** 内存主数据桩。 */
    private static class StubLookup implements ErpMasterGuards.MasterLookup
    {
        private final Map<String, ErpMasterGuards.MasterRecord> products = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> warehouses = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> uoms = new LinkedHashMap<>();

        void product(String id, String code, String name, String spec, String uomId, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setSpec(spec);
            record.setUomId(uomId);
            record.setEnableFlag(enableFlag);
            products.put(id, record);
        }

        void warehouse(String id, String code, String name, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setEnableFlag(enableFlag);
            warehouses.put(id, record);
        }

        void uom(String id, String code, String name, Integer decimals, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setDecimals(decimals);
            record.setEnableFlag(enableFlag);
            uoms.put(id, record);
        }

        @Override
        public ErpMasterGuards.MasterRecord product(String productId)
        {
            return products.get(productId);
        }

        @Override
        public ErpMasterGuards.MasterRecord warehouse(String warehouseId)
        {
            return warehouses.get(warehouseId);
        }

        @Override
        public ErpMasterGuards.MasterRecord uom(String uomId)
        {
            return uoms.get(uomId);
        }
    }

    private static StubLookup baseLookup()
    {
        StubLookup lookup = new StubLookup();
        lookup.product("P1", "WL-001", "螺丝", "M6×20", "U1", ErpMasterGuards.ENABLE_YES);
        lookup.uom("U1", "GE", "个", Integer.valueOf(0), ErpMasterGuards.ENABLE_YES);
        lookup.warehouse("W1", "CK-001", "主仓", ErpMasterGuards.ENABLE_YES);
        return lookup;
    }

    @Test
    public void 合法行项写入快照并按单位精度校验()
    {
        StubLookup lookup = baseLookup();
        Item item = new Item();
        item.setProductId("P1");
        item.setQty(new BigDecimal("2"));
        item.setUnitPrice(new BigDecimal("3.125"));

        ErpMasterGuards.applyItemSnapshot(lookup, item, 1);

        assertEquals("物料编码快照", "WL-001", item.getProductCode());
        assertEquals("物料名称快照", "螺丝", item.getProductName());
        assertEquals("规格快照", "M6×20", item.getSpec());
        assertEquals("单位名快照", "个", item.getUomName());
        assertEquals("单位小数位快照", Integer.valueOf(0), item.getUomDecimals());
        assertEquals("行金额先舍入到 2 位", "6.25", item.getAmount().toPlainString());
    }

    @Test
    public void 停用物料被拒且提示行号()
    {
        StubLookup lookup = baseLookup();
        lookup.product("P2", "WL-002", "旧螺丝", "M6", "U1", ErpMasterGuards.ENABLE_NO);
        Item item = new Item();
        item.setProductId("P2");
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, item, 2);
            fail("停用物料必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案必须带行号：" + e.getMessage(), e.getMessage().contains("行 2"));
            assertTrue("文案要点名物料与停用：" + e.getMessage(), e.getMessage().contains("旧螺丝"));
            assertTrue(e.getMessage().contains("已停用"));
        }
        assertNull("被拒时不得写入快照", item.getProductCode());
    }

    @Test
    public void 未选物料与不存在物料被拒()
    {
        final StubLookup lookup = baseLookup();
        assertRejected("行 1：必须选择物料档案", new Runnable()
        {
            @Override
            public void run()
            {
                Item item = new Item();
                ErpMasterGuards.applyItemSnapshot(lookup, item, 1);
            }
        });
        assertRejected("物料档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                Item item = new Item();
                item.setProductId("NOT-EXIST");
                ErpMasterGuards.applyItemSnapshot(lookup, item, 3);
            }
        });
    }

    @Test
    public void 停用计量单位被拒()
    {
        StubLookup lookup = baseLookup();
        lookup.uom("U2", "XIANG", "箱", Integer.valueOf(1), ErpMasterGuards.ENABLE_NO);
        lookup.product("P3", "WL-003", "整箱螺丝", null, "U2", ErpMasterGuards.ENABLE_YES);
        Item item = new Item();
        item.setProductId("P3");
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, item, 1);
            fail("停用单位必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("箱"));
            assertTrue(e.getMessage().contains("已停用"));
        }
        // 物料没有单位：同样拒绝（不然数量精度无从校验）
        StubLookup noUom = baseLookup();
        noUom.product("P4", "WL-004", "无单位物料", null, null, ErpMasterGuards.ENABLE_YES);
        assertRejected("物料未配置计量单位", new Runnable()
        {
            @Override
            public void run()
            {
                Item item = new Item();
                item.setProductId("P4");
                ErpMasterGuards.applyItemSnapshot(noUom, item, 1);
            }
        });
    }

    @Test
    public void 数量超出单位小数位被拒()
    {
        StubLookup lookup = baseLookup();
        lookup.uom("U3", "MI", "米", Integer.valueOf(2), ErpMasterGuards.ENABLE_YES);
        lookup.product("P5", "WL-005", "线材", null, "U3", ErpMasterGuards.ENABLE_YES);
        Item item = new Item();
        item.setProductId("P5");
        item.setQty(new BigDecimal("1.2345"));
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, item, 4);
            fail("超出单位小数位的数量必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("行 4"));
            assertTrue(e.getMessage().contains("2 位小数"));
        }
    }

    @Test
    public void 停用仓库被拒且未选仓库由表头回落()
    {
        StubLookup lookup = baseLookup();
        lookup.warehouse("W2", "CK-002", "旧仓", ErpMasterGuards.ENABLE_NO);
        assertRejected("仓库「旧仓」已停用", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.requireEnabledWarehouse(lookup, "W2", null);
            }
        });
        assertRejected("仓库不存在", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.requireEnabledWarehouse(lookup, "W9", null);
            }
        });
        assertRejected("必须选择仓库", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.requireEnabledWarehouse(lookup, "  ", null);
            }
        });

        // 行级仓库为空 → 回落表头仓库；行级填写 → 用行级
        Item item = new Item();
        assertEquals("未填行级仓库时回落表头", "W1", ErpMasterGuards.resolveWarehouseId(item, "W1"));
        item.setWarehouseId("W3");
        assertEquals("填了行级仓库时用行级", "W3", ErpMasterGuards.resolveWarehouseId(item, "W1"));
        assertNull("两者都空时返回 null（由审核阶段按行号报错）",
                ErpMasterGuards.resolveWarehouseId(new Item(), null));
    }

    @Test
    public void 行级仓库快照写入()
    {
        StubLookup lookup = baseLookup();
        Item item = new Item();
        item.setWarehouseId("W1");
        ErpMasterGuards.applyItemWarehouseSnapshot(lookup, item, 1);
        assertEquals("仓库名快照写入行项", "主仓", item.getWarehouseName());
        // 未填行级仓库：不写快照（回落表头，由调用方复制表头仓库名）
        Item empty = new Item();
        ErpMasterGuards.applyItemWarehouseSnapshot(lookup, empty, 1);
        assertNull(empty.getWarehouseName());
    }

    @Test
    public void 守卫只转发一处理精度实现()
    {
        assertEquals("6.25", ErpMasterGuards.lineAmount(new BigDecimal("2"), new BigDecimal("3.125")).toPlainString());
        assertEquals("0.39", ErpAmounts.totalOf(java.util.Arrays.<ErpDocItem>asList(
                new ErpAmountsTestItem("1", "0.125"), new ErpAmountsTestItem("1", "0.125"), new ErpAmountsTestItem("1", "0.125")))
                .toPlainString());
    }

    private static class ErpAmountsTestItem extends ErpDocItem
    {
        private static final long serialVersionUID = 1L;

        ErpAmountsTestItem(String qty, String price)
        {
            setQty(new BigDecimal(qty));
            setUnitPrice(new BigDecimal(price));
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
