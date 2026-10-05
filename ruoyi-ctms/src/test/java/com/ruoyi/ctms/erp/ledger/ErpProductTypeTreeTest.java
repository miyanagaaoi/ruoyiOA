package com.ruoyi.ctms.erp.ledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ruoyi.ctms.domain.CtmsProductType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>商品类型子树展开单测</b>（B4 任务 7.1 的"按商品类型筛选"口径）。 </p>
 *
 * <p> 覆盖：正常树 / 叶子 / 空集合 / 不存在的根 / 自环 / 级别无关的死链。 </p>
 *
 * @author 二开
 */
public class ErpProductTypeTreeTest
{
    /**
     * 造一棵三层的类型树 + 一个孤儿节点。
     *
     * @return 类型集合
     */
    private static List<CtmsProductType> tree()
    {
        List<CtmsProductType> types = new ArrayList<>();
        types.add(ErpLedgerTestSupport.type("A", null, "五金"));
        types.add(ErpLedgerTestSupport.type("B", "A", "紧固件"));
        types.add(ErpLedgerTestSupport.type("C", "A", "工具"));
        types.add(ErpLedgerTestSupport.type("D", "B", "螺丝"));
        types.add(ErpLedgerTestSupport.type("E", null, "办公用品"));
        return types;
    }

    @Test
    public void 选父带子_含全部后代且根在最前()
    {
        List<String> ids = ErpProductTypeTree.subtreeIds(tree(), "A");
        assertEquals(Arrays.asList("A", "B", "C", "D"), ids);
    }

    @Test
    public void 选中间层只带自己的后代()
    {
        assertEquals(Arrays.asList("B", "D"), ErpProductTypeTree.subtreeIds(tree(), "B"));
    }

    @Test
    public void 选叶子只有自身()
    {
        assertEquals(Arrays.asList("D"), ErpProductTypeTree.subtreeIds(tree(), "D"));
    }

    @Test
    public void 另一个根不带别的根()
    {
        assertEquals(Arrays.asList("E"), ErpProductTypeTree.subtreeIds(tree(), "E"));
    }

    @Test
    public void 根为空表示不按类型过滤()
    {
        assertNull(ErpProductTypeTree.subtreeIds(tree(), null));
        assertNull(ErpProductTypeTree.subtreeIds(tree(), "   "));
    }

    @Test
    public void 根不存在时仍按该ID过滤()
    {
        assertEquals(Arrays.asList("Z"), ErpProductTypeTree.subtreeIds(tree(), "Z"));
    }

    @Test
    public void 空集合不报错()
    {
        assertEquals(Arrays.asList("A"), ErpProductTypeTree.subtreeIds(new ArrayList<CtmsProductType>(), "A"));
        assertEquals(Arrays.asList("A"), ErpProductTypeTree.subtreeIds(null, "A"));
    }

    @Test
    public void 父子成环也不会死循环()
    {
        List<CtmsProductType> cyclic = new ArrayList<>();
        cyclic.add(ErpLedgerTestSupport.type("X", "Y", "环一"));
        cyclic.add(ErpLedgerTestSupport.type("Y", "X", "环二"));
        List<String> ids = ErpProductTypeTree.subtreeIds(cyclic, "X");
        assertEquals(2, ids.size());
        assertTrue(ids.contains("X"));
        assertTrue(ids.contains("Y"));
    }

    @Test
    public void 自环只返回自身一次()
    {
        List<CtmsProductType> selfLoop = new ArrayList<>();
        selfLoop.add(ErpLedgerTestSupport.type("S", "S", "自环"));
        assertEquals(Arrays.asList("S"), ErpProductTypeTree.subtreeIds(selfLoop, "S"));
    }
}
