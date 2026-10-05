package com.ruoyi.ctms.erp.master;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>主数据变更不影响历史单据快照</b>的单测（2.0 B4 §2.4、AC-72）。 </p>
 *
 * <p> 规格场景：{@code 某物料被用于一张已保存的单据后改动物料名称 → 该历史单据的行项仍显示改名前的名称快照}；
 * 以及 {@code 单据行项引用一个已停用的物料并提交保存 → 请求被拒绝并指明是第几行、哪个物料已停用}。 </p>
 *
 * <p> <b>断言方式</b>：行项的编码/名称/规格/单位名/单位小数位都在<b>写入时</b>由
 * {@link ErpMasterGuards#applyItemSnapshot} 从档案固化（这是全模块唯一的快照写入点），
 * 因此"历史不变"= "已写入的行项字段不被任何后续主数据变更改写"。
 * 本类用一个可改名/改停用的内存档案桩把这条口径变成可执行的断言。 </p>
 *
 * <p> 不依赖 Spring / 数据库；真实库里"改物料名后历史行项 product_name 不变"
 * 由 notes/02-master.md 的断言清单（t10 在 env 窗口跑）覆盖。 </p>
 *
 * @author 二开
 */
public class ErpMasterSnapshotTest
{
    /** 行项桩（公共基类 + 什么都不加）。 */
    private static class Item extends ErpDocItem
    {
        private static final long serialVersionUID = 1L;
    }

    /** 可变的物料/单位档案桩（模拟"档案被改名/停用"）。 */
    private static class MutableLookup implements ErpMasterGuards.MasterLookup
    {
        private final Map<String, ErpMasterGuards.MasterRecord> products = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> uoms = new LinkedHashMap<>();

        void putProduct(String id, String code, String name, String spec, String uomId, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setSpec(spec);
            record.setUomId(uomId);
            record.setEnableFlag(enableFlag);
            products.put(id, record);
        }

        void putUom(String id, String code, String name, Integer decimals)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setDecimals(decimals);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            uoms.put(id, record);
        }

        /** 模拟档案编辑：改名 / 换规格 / 停用。 */
        void rename(String id, String name, String spec, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = products.get(id);
            record.setName(name);
            record.setSpec(spec);
            record.setEnableFlag(enableFlag);
        }

        @Override
        public ErpMasterGuards.MasterRecord product(String productId)
        {
            return products.get(productId);
        }

        @Override
        public ErpMasterGuards.MasterRecord warehouse(String warehouseId)
        {
            return null;
        }

        @Override
        public ErpMasterGuards.MasterRecord uom(String uomId)
        {
            return uoms.get(uomId);
        }
    }

    private static Item newItem(String productId, String qty, String price)
    {
        Item item = new Item();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    @Test
    public void 改物料名与规格后历史行项仍显示旧快照()
    {
        MutableLookup lookup = new MutableLookup();
        lookup.putUom("U1", "GE", "个", Integer.valueOf(0));
        lookup.putProduct("P1", "WL-001", "螺丝", "M6×20", "U1", ErpMasterGuards.ENABLE_YES);

        Item historical = newItem("P1", "2", "1.5");
        ErpMasterGuards.applyItemSnapshot(lookup, historical, 1);
        assertEquals("写入时的名称快照", "螺丝", historical.getProductName());
        assertEquals("写入时的规格快照", "M6×20", historical.getSpec());

        // 档案侧改名 + 改规格
        lookup.rename("P1", "螺丝（改名后）", "M8×30", ErpMasterGuards.ENABLE_YES);

        assertEquals("历史行项的名称快照不得随档案改名而变", "螺丝", historical.getProductName());
        assertEquals("历史行项的规格快照不得随档案改名而变", "M6×20", historical.getSpec());
        assertEquals("历史行项的编码快照同样不变", "WL-001", historical.getProductCode());

        // 改名后新建的行项取到的是新值（证明档案确实变了，上一条不是"桩没生效"）
        Item fresh = newItem("P1", "1", "1.5");
        ErpMasterGuards.applyItemSnapshot(lookup, fresh, 1);
        assertEquals("新行项取改名后的名称", "螺丝（改名后）", fresh.getProductName());
        assertEquals("新行项取改名后的规格", "M8×30", fresh.getSpec());
    }

    @Test
    public void 物料停用后不可用于新行项且提示行号与名称()
    {
        MutableLookup lookup = new MutableLookup();
        lookup.putUom("U1", "GE", "个", Integer.valueOf(0));
        lookup.putProduct("P1", "WL-001", "螺丝", "M6×20", "U1", ErpMasterGuards.ENABLE_YES);

        Item historical = newItem("P1", "2", "1.5");
        ErpMasterGuards.applyItemSnapshot(lookup, historical, 1);

        lookup.rename("P1", "螺丝", "M6×20", ErpMasterGuards.ENABLE_NO);

        // 已有行项不受影响（停用不级联）
        assertEquals("停用不影响历史行项的快照", "螺丝", historical.getProductName());

        // 新行项被拒，且文案带行号与物品名
        Item fresh = newItem("P1", "1", "1.5");
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, fresh, 3);
            fail("停用物料必须不能用于新行项");
        }
        catch (ServiceException e)
        {
            assertTrue("文案必须带行号：" + e.getMessage(), e.getMessage().contains("行 3"));
            assertTrue("文案必须点名物料：" + e.getMessage(), e.getMessage().contains("螺丝"));
            assertTrue("文案必须说明停用：" + e.getMessage(), e.getMessage().contains("已停用"));
        }
        assertNull("被拒的新行项不得写入任何快照", fresh.getProductCode());
    }

    @Test
    public void 单位名与小数位也是快照()
    {
        MutableLookup lookup = new MutableLookup();
        lookup.putUom("U1", "GE", "个", Integer.valueOf(0));
        lookup.putProduct("P1", "WL-001", "螺丝", "M6×20", "U1", ErpMasterGuards.ENABLE_YES);

        Item historical = newItem("P1", "2", "1.5");
        ErpMasterGuards.applyItemSnapshot(lookup, historical, 1);
        assertEquals("个", historical.getUomName());
        assertEquals(Integer.valueOf(0), historical.getUomDecimals());

        // 单位改名（档案侧），历史行项不变
        lookup.putUom("U1", "GE", "件", Integer.valueOf(2));
        assertEquals("历史行项的单位名快照不变", "个", historical.getUomName());
        assertEquals("历史行项的单位小数位快照不变", Integer.valueOf(0), historical.getUomDecimals());
    }

    @Test
    public void 快照写入同时按单位小数位校验数量并算出行金额()
    {
        MutableLookup lookup = new MutableLookup();
        lookup.putUom("U1", "MI", "米", Integer.valueOf(2));
        lookup.putProduct("P1", "WL-001", "线材", null, "U1", ErpMasterGuards.ENABLE_YES);

        Item item = newItem("P1", "1.25", "3.125");
        ErpMasterGuards.applyItemSnapshot(lookup, item, 1);
        assertEquals("2 位单位允许 1.25", Integer.valueOf(2), item.getUomDecimals());
        assertEquals("行金额先舍入到 2 位", "3.91", item.getAmount().toPlainString());

        Item tooPrecise = newItem("P1", "1.2345", "1");
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, tooPrecise, 2);
            fail("超出单位小数位必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage().contains("行 2"));
            assertTrue(e.getMessage().contains("2 位小数"));
        }
    }
}
