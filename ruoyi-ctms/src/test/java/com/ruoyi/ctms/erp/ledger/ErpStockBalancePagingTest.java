package com.ruoyi.ctms.erp.ledger;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github.pagehelper.PageHelper;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.service.impl.ErpStockLedgerQueryServiceImpl;
import com.ruoyi.ctms.erp.posting.ErpPostingTestSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>F-01（blocker）服务层回归</b>：按商品类型<b>含子树</b>筛选不得被 PageHelper 分页污染。 </p>
 *
 * <p> 这条断言为什么必须放在<b>服务层</b>：{@link ErpProductTypeTreeTest} 只测纯函数，
 * 而 F-01 的缺陷根本不在纯函数里 —— 它出在"给纯函数喂数据的那个查询"上：
 * {@code startPage()} 会把 ThreadLocal 里的 Page 交给**下一条** MyBatis 查询，
 * 一旦"读全表类型清单"的辅助查询排在它后面，清单就被截成前 {@code pageSize} 行，
 * 根类型不在其中时子树算不出来（现象是"按父类型筛选"漏子类型物料，且**随页大小翻转**）。 </p>
 *
 * <p> 桩（{@code StubProductTypeMapper}）忠实模拟该拦截行为：线程里有活动页时只回前
 * {@code pageSize} 行并"消费"掉这页。于是： </p>
 * <ul>
 *   <li> 正例（现在的顺序：先 {@code prepareQuery} 再 {@code startPage}）⇒ 子树完整、命中； </li>
 *   <li> <b>反向对照</b>（把类型清单查询放回分页上下文）⇒ 桩必定截断、{@code ROOT} 不在前 N 行
 *        ⇒ 子树只剩自己 ⇒ 用例变红。</li>
 * </ul>
 *
 * @author 二开
 */
public class ErpStockBalancePagingTest
{
    /** 模拟 Controller 的默认页大小（RuoYi BaseController.startPage 缺参时是 10）。 */
    private static final int PAGE_SIZE = 10;

    /** 填充类型数量：45 + ROOT + LEAF = 47 > PAGE_SIZE（复刻"类型表比页大小大得多"的现场）。 */
    private static final int FILLERS = 45;

    /** 夹具物料（挂在 LEAF 下）。 */
    private static final String PRODUCT = "P-LEAF";

    private ErpLedgerTestSupport.StubBalanceMapper balanceMapper;

    private ErpLedgerTestSupport.StubProductTypeMapper typeMapper;

    private ErpStockLedgerQueryServiceImpl service;

    /**
     * 组装服务与桩：类型清单按"填充在前、ROOT/LEAF 在后"播种（真库按 sort/code 排序，效果一致）。
     */
    @Before
    public void setUp()
    {
        balanceMapper = new ErpLedgerTestSupport.StubBalanceMapper();
        typeMapper = new ErpLedgerTestSupport.StubProductTypeMapper();
        service = new ScopeFreeService();
        ErpPostingTestSupport.inject(service, "stockBalanceMapper", balanceMapper);
        ErpPostingTestSupport.inject(service, "productTypeMapper", typeMapper);

        for (int i = 0; i < FILLERS; i++)
        {
            typeMapper.seed(ErpLedgerTestSupport.type(String.format("F%02d", Integer.valueOf(i)), null, "填充" + i));
        }
        typeMapper.seed(ErpLedgerTestSupport.type("ROOT", null, "父类型"));
        typeMapper.seed(ErpLedgerTestSupport.type("LEAF", "ROOT", "子类型"));
        balanceMapper.seed(ErpLedgerTestSupport.balance(PRODUCT, "W1", "5", "0", "子类型", "物料", "LEAF"));
    }

    /**
     * 每个用例后清掉 PageHelper 的 ThreadLocal 页，避免污染同线程的其它用例。
     */
    @After
    public void tearDown()
    {
        PageHelper.clearPage();
    }

    /**
     * 正例：类型行数（47）远大于页大小（10）时，按父类型筛选仍必须命中子类型物料。
     */
    @Test
    public void 类型行数远大于页大小时按父类型筛选仍命中子类型物料()
    {
        ErpStockBalance query = new ErpStockBalance();
        query.setProductTypeId("ROOT");

        service.prepareQuery(query);           // Controller 的新顺序：先展开（此时还没有分页上下文）
        PageHelper.startPage(1, PAGE_SIZE);    // 再开分页
        List<ErpStockBalance> rows = service.selectBalanceList(query);

        assertEquals("子树展开必须覆盖 LEAF（47 个类型 > pageSize 10）", 2, query.getProductTypeIds().size());
        assertTrue(query.getProductTypeIds().contains("LEAF"));
        assertEquals("F-01：父类型必须命中挂在子类型下的物料", 1, hits(rows));
        assertEquals("类型清单这次**不该**被分页截断", 0, typeMapper.truncatedCalls);
    }

    /**
     * 页大小无关性：pageSize=10 与 pageSize=500 的结果必须一致（门禁不得随库规模/页大小翻转）。
     */
    @Test
    public void 页大小变化不影响父类型筛选结果()
    {
        for (int pageSize : new int[] { 10, 500 })
        {
            ErpStockBalance query = new ErpStockBalance();
            query.setProductTypeId("ROOT");
            service.prepareQuery(query);
            PageHelper.startPage(1, pageSize);
            List<ErpStockBalance> rows = service.selectBalanceList(query);
            assertEquals("pageSize=" + pageSize + " 时也必须命中子类型物料", 1, hits(rows));
            PageHelper.clearPage();
        }
    }

    /**
     * <p> <b>反向对照</b>：把"读全表类型清单"放回分页上下文（= 修复前的写法）⇒ 桩按
     * PageHelper 语义截断前 {@code pageSize} 行 ⇒ {@code ROOT} 不在其中 ⇒ 子树只剩自己。 </p>
     *
     * <p> 这条用例的价值：它证明"桩确实能复现分页污染"，因此上面两条正例不是假绿 ——
     * 一旦有人把展开逻辑挪回 {@code startPage()} 之后，正例立刻变红。 </p>
     */
    @Test
    public void 反向对照_旧写法在分页上下文里会被截断并丢掉子树()
    {
        PageHelper.startPage(1, PAGE_SIZE);
        List<CtmsProductType> truncated = typeMapper.selectProductTypeList(new CtmsProductType());

        assertEquals("桩必须忠实模拟 PageHelper：只回前 pageSize 行", PAGE_SIZE, truncated.size());
        List<String> ids = new ArrayList<>();
        for (CtmsProductType type : truncated)
        {
            ids.add(type.getId());
        }
        assertFalse("ROOT 排在 47 个类型的末尾 ⇒ 不在前 10 行内（这就是 F-01 的现场）", ids.contains("ROOT"));
        assertEquals("在截断清单上展开 ROOT ⇒ 只剩自己（子树丢失）", 1,
                ErpProductTypeTree.subtreeIds(truncated, "ROOT").size());
        assertEquals("桩确实发生过一次分页截断", 1, typeMapper.truncatedCalls);
    }

    /**
     * 统计返回行里夹具物料的条数。
     *
     * @param rows 返回行
     * @return 命中条数
     */
    /**
     * 测试子类：把数据范围片段固定为 null（不受限）。
     *
     * <p> 为什么需要：单测没有登录上下文，ErpDocScope 会走它自己的"一个档位都没有"分支返回
     * <b>1=0</b>（"不可见任何行"），而桩忠实模拟该片段语义 => 会把夹具行全挡掉，让"分页是否污染
     * 子树展开"这个唯一变量被掩盖。这里显式置为不受限，只留分页变量。 </p>
     */
    private static class ScopeFreeService extends ErpStockLedgerQueryServiceImpl
    {
        @Override
        protected String currentScopeSql()
        {
            return null;
        }
    }
    private static int hits(List<ErpStockBalance> rows)
    {
        int count = 0;
        if (rows == null)
        {
            return 0;
        }
        for (ErpStockBalance row : rows)
        {
            if (row != null && PRODUCT.equals(row.getProductId()))
            {
                count++;
            }
        }
        return count;
    }
}
