package com.ruoyi.ctms.erp.procurement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link ErpPurRules} 的纯规则单测（2.0 B4 任务 3.1~3.4、3.6 的"每一条断言"）。 </p>
 *
 * <p> <b>为什么必须是纯单测</b>：本类不读库、不起 Spring，所以这些口径可以逐字锁定 ——
 * 包括错误文案（接口契约）、剩余量边界与归零判定的"空集合不算归零"等容易写错的边界。
 * 服务层的用例如果装配失败，本类仍能证明"规则本身是对的"，便于定位是规则错还是装配错。 </p>
 *
 * @author 二开
 */
public class ErpPurRulesTest
{
    /* ==================== 夹具 ==================== */

    private static ErpPurchaseRequestItem item(String productId, String qty, String orderedQty, Integer decimals)
    {
        ErpPurchaseRequestItem line = new ErpPurchaseRequestItem();
        line.setId(productId + "-ITEM");
        line.setProductId(productId);
        line.setProductName("物料-" + productId);
        line.setQty(qty == null ? null : new BigDecimal(qty));
        line.setOrderedQty(orderedQty == null ? null : new BigDecimal(orderedQty));
        line.setUomDecimals(decimals);
        line.setUnitPrice(new BigDecimal("0.125"));
        return line;
    }

    private static void assertRejected(final String expectedMessage, Runnable action)
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

    /* ==================== 剩余量与归零（3.3 / 3.4） ==================== */

    @Test
    public void 剩余量等于数量减已下单量()
    {
        ErpPurchaseRequestItem line = item("P1", "100", "60", Integer.valueOf(0));
        assertEquals("剩余量 = 100 - 60", 0,
                ErpPurRules.remainingQtyOf(line).compareTo(new BigDecimal("40")));
    }

    @Test
    public void 剩余量恒不为负且空值视为零()
    {
        assertEquals("已下单量超过数量时剩余量为 0（不能给前端负数）", 0,
                ErpPurRules.remainingQty(new BigDecimal("10"), new BigDecimal("30"))
                        .compareTo(BigDecimal.ZERO));
        assertEquals("已下单量为空视为 0", 0,
                ErpPurRules.remainingQty(new BigDecimal("5"), null).compareTo(new BigDecimal("5")));
        assertEquals("数量为空视为 0", 0,
                ErpPurRules.remainingQty(null, null).compareTo(BigDecimal.ZERO));
    }

    @Test
    public void 剩余量合计与归零判定()
    {
        List<ErpPurchaseRequestItem> items = new ArrayList<>();
        items.add(item("P1", "100", "60", Integer.valueOf(0)));
        items.add(item("P2", "10", "10", Integer.valueOf(0)));
        assertEquals("剩余合计 = 40 + 0", 0,
                ErpPurRules.remainingSum(items).compareTo(new BigDecimal("40")));
        assertFalse("只推完一行不归零", ErpPurRules.isFullyOrdered(items));
        items.get(0).setOrderedQty(new BigDecimal("100"));
        assertEquals("两行都推完后剩余合计为 0", 0,
                ErpPurRules.remainingSum(items).compareTo(BigDecimal.ZERO));
        assertTrue("全部行项剩余量为 0 即归零", ErpPurRules.isFullyOrdered(items));
    }

    @Test
    public void 空行项集合不算归零()
    {
        assertFalse("没有行项的申请单不该被自动完成（它连提交都不合法）",
                ErpPurRules.isFullyOrdered(null));
        assertFalse("空集合不算归零", ErpPurRules.isFullyOrdered(new ArrayList<ErpPurchaseRequestItem>()));
    }

    /* ==================== 下推数量（3.3） ==================== */

    @Test
    public void 未填数量按剩余量全推()
    {
        ErpPurchaseRequestItem line = item("P1", "100", "60", Integer.valueOf(0));
        assertEquals("不填数量 → 推剩余 40", 0,
                ErpPurRules.resolvePushQty(null, line).compareTo(new BigDecimal("40")));
        assertEquals("不填数量（按剩余量的重载）→ 推剩余 40", 0,
                ErpPurRules.resolvePushQty(null, new BigDecimal("40")).compareTo(new BigDecimal("40")));
        assertEquals("填 0 与不填同义（与销售线 ErpSalRules 同构）", 0,
                ErpPurRules.resolvePushQty(BigDecimal.ZERO, new BigDecimal("40"))
                        .compareTo(new BigDecimal("40")));
        assertEquals("填负数同样按剩余量全推", 0,
                ErpPurRules.resolvePushQty(new BigDecimal("-5"), new BigDecimal("40"))
                        .compareTo(new BigDecimal("40")));
    }

    @Test
    public void 已归零的行再推被拒()
    {
        assertRejected(ErpPurRules.MSG_NO_REMAINING, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.resolvePushQty(null, BigDecimal.ZERO);
            }
        });
        assertRejected(ErpPurRules.MSG_NO_REMAINING, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.resolvePushQty(new BigDecimal("1"), BigDecimal.ZERO);
            }
        });
    }

    @Test
    public void 超量下推被拒且文案带剩余量与本次量()
    {
        assertRejected("采购申请单「物料-P1」可下推数量不足（剩余 40，本次 50）",
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        ErpPurRules.checkPushQty(new BigDecimal("40"), new BigDecimal("50"),
                                "采购申请单", "物料-P1");
                    }
                });
    }

    @Test
    public void 下推数量必须大于零()
    {
        assertRejected(ErpPurRules.MSG_PUSH_QTY_POSITIVE, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkPushQty(new BigDecimal("40"), BigDecimal.ZERO, "采购申请单", "物料-P1");
            }
        });
    }

    @Test
    public void 下推数量等于剩余量放行()
    {
        ErpPurRules.checkPushQty(new BigDecimal("40"), new BigDecimal("40"), "采购申请单", "物料-P1");
    }

    /* ==================== 行项校验（3.1） ==================== */

    @Test
    public void 无行项提交被拒()
    {
        assertRejected(ErpPurRules.MSG_NO_ITEMS, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkItemsBasic(new ArrayList<com.ruoyi.ctms.erp.base.domain.ErpDocItem>());
            }
        });
    }

    @Test
    public void 数量为零被拒且带行号()
    {
        final List<ErpPurchaseRequestItem> items = new ArrayList<>();
        items.add(item("P1", "1", "0", Integer.valueOf(3)));
        items.add(item("P2", "0", "0", Integer.valueOf(0)));
        assertRejected("行 2：数量必须大于 0", new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkItemsBasic(items);
            }
        });
    }

    @Test
    public void 单位零位小数时拒绝小数数量()
    {
        final List<ErpPurchaseRequestItem> items = new ArrayList<>();
        items.add(item("P1", "1.5", "0", Integer.valueOf(0)));
        try
        {
            ErpPurRules.checkItemsBasic(items);
            fail("单位 0 位小数时 1.5 必须被拒");
        }
        catch (ServiceException e)
        {
            assertTrue("文案必须是「行 N：数量的最小单位是 0 位小数…」，实际=" + e.getMessage(),
                    e.getMessage().startsWith("行 1：数量的最小单位是 0 位小数"));
        }
    }

    @Test
    public void 单价为负被拒且带行号()
    {
        final List<ErpPurchaseRequestItem> items = new ArrayList<>();
        ErpPurchaseRequestItem line = item("P1", "1", "0", Integer.valueOf(3));
        line.setUnitPrice(new BigDecimal("-0.01"));
        items.add(line);
        assertRejected("行 1：单价不得为负数", new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkItemsBasic(items);
            }
        });
    }

    @Test
    public void 三行零点一二五先舍入再汇总为三十九分()
    {
        List<com.ruoyi.ctms.erp.base.domain.ErpDocItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            ErpPurchaseRequestItem line = item("P" + i, "1", "0", Integer.valueOf(3));
            line.setUnitPrice(new BigDecimal("0.125"));
            line.recalcAmount();
            assertEquals("每行先各自四舍五入为 0.13", 0,
                    line.getAmount().compareTo(new BigDecimal("0.13")));
            items.add(line);
        }
        // 合计 = 各行舍入后之和 = 0.13 × 3 = 0.39（不是 0.375 → 0.38）
        assertEquals("合计必须是 0.39 而不是 0.38", 0,
                ErpPurRules.totalAmountOf(items).compareTo(new BigDecimal("0.39")));
    }

    /* ==================== 供应商方向（3.2） ==================== */

    @Test
    public void 传客户ID按供应商不存在处理()
    {
        // 客户档案按 t_ctms_supplier.id 命不中 → supplierFound 为 false
        assertRejected(ErpPurRules.MSG_SUPPLIER_NOT_FOUND, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkSupplierDirection("C-001", false, true);
            }
        });
    }

    @Test
    public void 供应商未选与已停用分别被拒()
    {
        assertRejected(ErpPurRules.MSG_SUPPLIER_REQUIRED, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkSupplierDirection("  ", true, true);
            }
        });
        assertRejected(ErpPurRules.MSG_SUPPLIER_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkSupplierDirection("S-001", true, false);
            }
        });
        // 启用中的供应商放行
        ErpPurRules.checkSupplierDirection("S-001", true, true);
    }

    /* ==================== 合同方向与停用（3.6） ==================== */

    @Test
    public void 采购单据不能关联销售方向合同()
    {
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                // 销售方向合同：甲方有客户、乙方无供应商
                ErpPurRules.checkContractDirection(null, "C-001", ErpPurRules.DIRECTION_PURCHASE);
            }
        });
    }

    @Test
    public void 方向无法判定时拒绝()
    {
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                // 客户与供应商都没填：无法证明方向
                ErpPurRules.checkContractDirection(null, null, ErpPurRules.DIRECTION_PURCHASE);
            }
        });
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                // 两边都填：方向自相矛盾，同样拒绝
                ErpPurRules.checkContractDirection("S-001", "C-001", ErpPurRules.DIRECTION_PURCHASE);
            }
        });
    }

    @Test
    public void 采购方向合同放行而销售单据拒绝()
    {
        ErpPurRules.checkContractDirection("S-001", null, ErpPurRules.DIRECTION_PURCHASE);
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkContractDirection("S-001", null, ErpPurRules.DIRECTION_SALES);
            }
        });
    }

    @Test
    public void 合同不存在与已停用分别被拒()
    {
        assertRejected(ErpPurRules.MSG_CONTRACT_DIRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkContractUsable(false, null);
            }
        });
        assertRejected(ErpPurRules.MSG_CONTRACT_DISABLED, new Runnable()
        {
            @Override
            public void run()
            {
                ErpPurRules.checkContractUsable(true, ErpPurRules.FLAG_YES);
            }
        });
        ErpPurRules.checkContractUsable(true, ErpPurRules.FLAG_NO);
    }

    /* ==================== 单号前缀（真源在 base，不重复声明） ==================== */

    @Test
    public void 单号前缀由base统一提供且即PR与PO()
    {
        // 本组不再自持前缀常量：真源是 base 的 ErpDocNoGenerator（与 t_code_config 种子逐字一致）
        assertEquals("PR", com.ruoyi.ctms.erp.base.ErpDocNoGenerator.prefixOf(
                com.ruoyi.ctms.erp.base.ErpDocType.PURCHASE_REQUEST));
        assertEquals("PO", com.ruoyi.ctms.erp.base.ErpDocNoGenerator.prefixOf(
                com.ruoyi.ctms.erp.base.ErpDocType.PURCHASE_ORDER));
    }
}
