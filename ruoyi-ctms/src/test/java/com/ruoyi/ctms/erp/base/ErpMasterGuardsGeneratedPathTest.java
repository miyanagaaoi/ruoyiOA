package com.ruoyi.ctms.erp.base;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link ErpMasterGuards} 的<b>生成路径豁免</b>口径单测（2.0 B4 任务 t23；AC-75）。 </p>
 *
 * <p> <b>缺陷</b>：盘点审核按差异自动生成的盘盈入库单 / 盘亏出库单，行项来自该仓库已有结存，
 * 而那些物料可能在建结存之后被停用；走面向用户录入的 {@code applyItemSnapshot} 会报
 * "物料「X」已停用，不能用于新单据"，导致盘点永远无法平账。 </p>
 *
 * <p> <b>修复口径</b>：新增专用方法 {@link ErpMasterGuards#applyGeneratedItemSnapshot}，
 * 只豁免"物料已停用"这一条；本类把三件事钉死： </p>
 * <ol>
 *   <li> 严格路径（用户录入）<b>不变</b>：停用物料仍拒且文案带行号； </li>
 *   <li> 生成路径放过停用物料，但快照/金额照旧写入； </li>
 *   <li> 豁免<b>不是全局开关</b>：同一进程内先调生成路径再调严格路径，严格路径仍然拒绝
 *        （每次调用各自显式选择）；并用反射断言本类没有引入静态布尔开关。 </li>
 * </ol>
 *
 * @author 二开
 */
public class ErpMasterGuardsGeneratedPathTest
{
    private static final String UOM = "UOM-1";

    private static final String DISABLED_PRODUCT = "P-OFF";

    private static final String MISSING_PRODUCT = "P-NOT-EXIST";

    private final StubLookup lookup = new StubLookup();

    @Before
    public void setUp()
    {
        lookup.addUom(UOM, "个", Integer.valueOf(3));
        lookup.addUom("UOM-0", "件", Integer.valueOf(0));
        lookup.addDisabledUom("UOM-OFF", "停用单位", Integer.valueOf(3));
        lookup.addProduct("P-ON", "LS-0001", "在售螺丝", UOM);
        lookup.addDisabledProduct(DISABLED_PRODUCT, "LS-9999", "停用螺丝", UOM);
        lookup.addProductWithMissingUom("P-NO-UOM", "LS-8888", "无单位物料", "UOM-MISSING");
        lookup.addProduct("P-ZERO-UOM", "LS-6666", "零位物料", "UOM-0");
        lookup.addProduct("P-OFF-UOM", "LS-7777", "单位停用物料", "UOM-OFF");
    }

    @Test
    public void strictGuardStillRejectsDisabledProductWithRowNo()
    {
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, item(DISABLED_PRODUCT, "2"), 3);
            fail("用户录入路径遇到停用物料应被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("行 3：物料「停用螺丝」已停用，不能用于新单据", e.getMessage());
        }
    }

    @Test
    public void generatedGuardAllowsDisabledProductAndWritesSnapshotAndLineAmount()
    {
        ErpStockInItem item = item(DISABLED_PRODUCT, "1.5");
        item.setUnitPrice(new BigDecimal("0.125"));

        ErpMasterGuards.Snapshot snapshot = ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item, 1);

        assertNotNull("生成路径应返回快照", snapshot);
        assertEquals("停用螺丝", item.getProductName());
        assertEquals("LS-9999", item.getProductCode());
        assertEquals("个", item.getUomName());
        assertEquals(Integer.valueOf(3), item.getUomDecimals());
        // 行金额仍按"先舍入到 2 位"（0.1875 → 0.19），证明快照逻辑未被豁免整段跳过
        assertEquals(0, new BigDecimal("0.19").compareTo(item.getAmount()));
        assertEquals(DISABLED_PRODUCT, snapshot.getProduct().getId());
    }

    @Test
    public void generatedGuardStillRejectsMissingProductMissingUomDisabledUomAndPrecision()
    {
        // ① 物料不存在
        assertMessageContains("行 1：物料档案不存在：" + MISSING_PRODUCT,
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item(MISSING_PRODUCT, "2"), 1);
                    }
                });
        // ② 单位不存在
        assertMessageContains("计量单位不存在：UOM-MISSING", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item("P-NO-UOM", "2"), 1);
            }
        });
        // ③ 单位已停用（生成路径只豁免"物料停用"，不豁免单位停用）
        assertMessageContains("计量单位「停用单位」已停用，不能用于新单据", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item("P-OFF-UOM", "2"), 1);
            }
        });
        // ④ 数量精度（单位 0 位小数，录 1.5）
        assertMessageContains("行 1：数量的最小单位是 0 位小数", new Runnable()
        {
            @Override
            public void run()
            {
                ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item("P-ZERO-UOM", "1.5"), 1);
            }
        });
    }

    @Test
    public void exemptionIsPerCallNotAGlobalSwitch()
    {
        // 先走生成路径（放过停用物料）
        assertNotNull(ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item(DISABLED_PRODUCT, "2"), 1));
        // 紧接着走严格路径：必须仍然拒绝 —— 说明豁免不是被"打开"的全局状态
        try
        {
            ErpMasterGuards.applyItemSnapshot(lookup, item(DISABLED_PRODUCT, "2"), 1);
            fail("生成路径调用之后，严格路径仍必须拒绝停用物料");
        }
        catch (ServiceException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("已停用"));
        }
        // 再走生成路径：仍然放过（每次调用独立选择）
        assertNotNull(ErpMasterGuards.applyGeneratedItemSnapshot(lookup, item(DISABLED_PRODUCT, "2"), 1));

        // 反射断言：本类与两个单据服务都没有"静态布尔开关"（形如全局放宽的写法会被这条抓住）
        assertNoStaticBooleanFlag(ErpMasterGuards.class);
        assertNoStaticBooleanFlag(ErpMasterGuards.MasterRecord.class);
    }

    /* ==================== 桩与小工具 ==================== */

    /**
     * 断言某个类没有静态布尔字段（全局开关的判别式；常量字符串/枚举不受影响）。
     *
     * @param type 待检查的类型
     */
    private static void assertNoStaticBooleanFlag(Class<?> type)
    {
        for (Field field : type.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class)
            {
                fail("不得引入全局布尔开关：" + type.getSimpleName() + "." + field.getName());
            }
        }
    }

    /**
     * 构造一条入库行项（用真实的行项类型，避免测试里另造实现类）。
     *
     * @param productId 物料ID
     * @param qty       数量
     * @return 行项
     */
    private static ErpStockInItem item(String productId, String qty)
    {
        ErpStockInItem item = new ErpStockInItem();
        item.setProductId(productId);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(BigDecimal.ZERO);
        return item;
    }

    private static void assertMessageContains(String expected, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒绝并提示：" + expected);
        }
        catch (ServiceException e)
        {
            assertTrue("提示应包含「" + expected + "」，实际为「" + e.getMessage() + "」",
                    e.getMessage() != null && e.getMessage().contains(expected));
        }
    }

    /**
     * 主数据桩：物料 / 仓库 / 单位（与生产 {@code ErpMasterLookupImpl} 同语义，只回摘要）。
     */
    private static class StubLookup implements ErpMasterGuards.MasterLookup
    {
        private final Map<String, ErpMasterGuards.MasterRecord> products = new LinkedHashMap<>();

        private final Map<String, ErpMasterGuards.MasterRecord> uoms = new LinkedHashMap<>();

        private void addUom(String id, String name, Integer decimals)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, id, name);
            record.setDecimals(decimals);
            record.setEnableFlag(ErpMasterGuards.ENABLE_YES);
            uoms.put(id, record);
        }

        private void addDisabledUom(String id, String name, Integer decimals)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, id, name);
            record.setDecimals(decimals);
            record.setEnableFlag(ErpMasterGuards.ENABLE_NO);
            uoms.put(id, record);
        }

        private void addProduct(String id, String code, String name, String uomId)
        {
            putProduct(id, code, name, uomId, ErpMasterGuards.ENABLE_YES);
        }

        private void addDisabledProduct(String id, String code, String name, String uomId)
        {
            putProduct(id, code, name, uomId, ErpMasterGuards.ENABLE_NO);
        }

        private void addProductWithMissingUom(String id, String code, String name, String missingUom)
        {
            putProduct(id, code, name, missingUom, ErpMasterGuards.ENABLE_YES);
        }

        private void putProduct(String id, String code, String name, String uomId, String enableFlag)
        {
            ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(id, code, name);
            record.setUomId(uomId);
            record.setEnableFlag(enableFlag);
            products.put(id, record);
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
}
