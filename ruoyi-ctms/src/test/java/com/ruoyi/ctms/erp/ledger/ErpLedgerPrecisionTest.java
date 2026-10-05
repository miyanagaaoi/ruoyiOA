package com.ruoyi.ctms.erp.ledger;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.posting.controller.ErpStockInController;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>精度四方一致回归</b>（B4 任务 7.5；{@code REQ-NFR-007}、AC-78）。 </p>
 *
 * <p> 四个"面"指同一批行项在四处展示/落库的金额必须<b>完全一致、不差 1 分钱</b>： </p>
 * <ol>
 *   <li> <b>未审核</b> —— 草稿保存时逐行写的 {@code amount} 与合计； </li>
 *   <li> <b>已审核</b> —— 审核后落库的 {@code total_amount}（同一批行项重算）； </li>
 *   <li> <b>打印</b> —— 打印数据源用的金额文本（{@code ErpAmounts.amountTextOf}）； </li>
 *   <li> <b>导出</b> —— 导出行对象的金额（用 T3 真实的
 *        {@code ErpStockInController.ErpStockInExportRow}，不是测试里另写的替身）。 </li>
 * </ol>
 *
 * <p> 夹具刻意挑<b>能区分"逐行舍入后再求和"与"先求和再舍入"</b>的单价：
 * 逐行 {@code 0.13 + 10.01 + 2.78 = 12.92}，而先求和再舍入是 {@code 12.91} ——
 * 后者正是 AC-78 要杜绝的 1 分钱差异（断言里把它写成反例）。 </p>
 *
 * <p> "不含浮点类型"另由 {@code ErpPrecisionTest} 做源码级扫描（它覆盖整个
 * {@code com/ruoyi/ctms/erp} 包，本包新增文件自动纳入）。 </p>
 *
 * @author 二开
 */
public class ErpLedgerPrecisionTest
{
    /**
     * 造一行含小数单价的行项。
     *
     * @param seq       序号
     * @param qty       数量
     * @param unitPrice 单价
     * @return 行项
     */
    private static ErpStockInItem item(int seq, String qty, String unitPrice)
    {
        ErpStockInItem item = new ErpStockInItem();
        item.setSeq(seq);
        item.setQty(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    /**
     * 三个会暴露舍入口径的行项。
     *
     * @return 行项集合
     */
    private static List<ErpStockInItem> items()
    {
        List<ErpStockInItem> items = new ArrayList<>();
        items.add(item(1, "1", "0.125"));    // 0.125 → 0.13（HALF_UP，不是 0.12）
        items.add(item(2, "3", "3.335"));    // 10.005 → 10.01
        items.add(item(3, "2.5", "1.111"));  // 2.7775 → 2.78
        return items;
    }

    /**
     * 取"导出面"的金额：调用 T3 真实的导出行装配方法。
     *
     * <p> {@code ErpStockInExportRow.of(...)} 是包内可见的静态方法，且测试不在同一个包 ——
     * 这里用反射调用，而<b>不是</b>为了测试去放宽别人代码的可见性（那会改到 T3 的交付物）。
     * 断言的意义不变：导出面用的是<b>真实</b>的导出行对象，不是测试替身。 </p>
     *
     * @param doc 入库单
     * @return 导出行的金额合计
     * @throws Exception 反射失败
     */
    private static BigDecimal exportTotalOf(ErpStockIn doc) throws Exception
    {
        Method of = ErpStockInController.ErpStockInExportRow.class.getDeclaredMethod("of", ErpStockIn.class);
        of.setAccessible(true);
        Object row = of.invoke(null, doc);
        Method getter = row.getClass().getMethod("getTotalAmount");
        return (BigDecimal) getter.invoke(row);
    }

    @Test
    public void 未审核到已审核到打印到导出四处金额完全一致() throws Exception
    {
        List<ErpStockInItem> items = items();

        // ① 未审核：保存路径逐行写 amount（唯一实现点 ErpAmounts.applyLineAmount）
        BigDecimal draftTotal = BigDecimal.ZERO;
        for (ErpStockInItem item : items)
        {
            draftTotal = draftTotal.add(ErpAmounts.applyLineAmount(item));
        }
        assertEquals("逐行舍入后求和", new BigDecimal("12.92"), draftTotal.setScale(2));

        // ② 已审核：审计通过后落库的 total_amount（同一批行项重算）
        ErpStockIn doc = new ErpStockIn();
        doc.setDocNo("IN-TEST-0001");
        BigDecimal approvedTotal = ErpAmounts.totalOf(items);
        doc.setTotalAmount(approvedTotal);
        assertEquals("未审核与已审核同源", draftTotal.setScale(2), doc.getTotalAmount().setScale(2));

        // ③ 打印：打印数据源用的金额文本
        assertEquals("12.92", ErpAmounts.amountTextOf(doc.getTotalAmount()));
        assertEquals("0.13", ErpAmounts.amountTextOf(items.get(0).getAmount()));

        // ④ 导出：用 T3 真实的导出行对象（不是测试替身）
        assertEquals("导出面与打印/落库完全一致", new BigDecimal("12.92"), exportTotalOf(doc).setScale(2));

        // 反例：先求和再舍入会得到 12.91（1 分钱差异）
        BigDecimal sumThenRound = new BigDecimal("0.125").add(new BigDecimal("10.005")).add(new BigDecimal("2.7775"));
        assertEquals(new BigDecimal("12.91"), ErpAmounts.roundAmount(sumThenRound));
        assertNotEquals("四处金额必须落在正确一侧", ErpAmounts.roundAmount(sumThenRound), draftTotal.setScale(2));
    }

    @Test
    public void 金额与数量都走定点类型且小数位固定()
    {
        List<ErpStockInItem> items = items();
        for (ErpStockInItem item : items)
        {
            ErpAmounts.applyLineAmount(item);
        }
        BigDecimal total = ErpAmounts.totalOf(items);

        assertEquals("金额类型必须是 BigDecimal", BigDecimal.class, total.getClass());
        assertEquals("金额列固定 2 位（decimal(16,2)）", 2, total.scale());
        for (ErpStockInItem item : items)
        {
            assertEquals(BigDecimal.class, item.getAmount().getClass());
            assertEquals("行金额固定 2 位", 2, item.getAmount().scale());
            assertEquals("数量列固定 3 位（decimal(16,3)）", 3, ErpAmounts.roundQty(item.getQty()).scale());
        }
        assertTrue("单价按 4 位定点（decimal(14,4)）",
                new BigDecimal("3.335").compareTo(ErpAmounts.roundPrice(items.get(1).getUnitPrice())) == 0);
    }

    @Test
    public void 额度口径的逐行舍入与金额口径同源()
    {
        // 同一行数字经"额度"路径（流水）与"金额"路径（行项）应得到同一个 2 位值
        BigDecimal viaAmount = ErpAmounts.lineAmount(new BigDecimal("3"), new BigDecimal("3.335"));
        BigDecimal viaQuota = ErpLedgerRules.quotaTerm(new BigDecimal("3"), new BigDecimal("3.335"));
        assertEquals("额度与金额共用 ErpAmounts.lineAmount", viaAmount, viaQuota);
        assertEquals(new BigDecimal("10.01"), viaQuota);
    }
}
