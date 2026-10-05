package com.ruoyi.ctms.erp.ledger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.ctms.domain.CtmsProductType;

/**
 * <p> <b>商品类型树 → 子树 ID 集合</b>（B4 任务 7.1 的「按商品类型筛选」口径）。 </p>
 *
 * <p> <b>为什么"选父带子"</b>：商品类型是树（{@code t_ctms_product_type.parent_id}，
 * 层级上限 5）。界面上选「五金」时用户期待看到五金下所有物料（螺丝、螺母…），
 * 而不是只有直接挂在"五金"上的（通常一个也没有）。 </p>
 *
 * <p> <b>为什么在 Java 里展开而不是递归 CTE</b>： </p>
 * <ol>
 *   <li> 纯函数可脱库单测（{@code ErpProductTypeTreeTest} 覆盖空树/孤儿/成环/深链）；
 *        递归 CTE 只能靠真库验证，而真库验证要等 T10/T13 的接口断言； </li>
 *   <li> 类型档案是**小表**（一个企业几十到几百行），一次全量读 + 内存 BFS 的代价可忽略，
 *        而递归 CTE 在 MySQL 里还要依赖 {@code cte_max_recursion_depth} 与数据无环； </li>
 *   <li> 展开结果作为 {@code in (...)} 列表进查询，执行计划稳定（走 {@code idx}）。 </li>
 * </ol>
 *
 * <p> <b>成环保护</b>：{@code visited} 集合保证每个 ID 只入队一次 ——
 * 即使有人手工把 {@code parent_id} 改成一个环（B3 的服务层有层级守卫，但库级没有），
 * 这里也不会死循环。 </p>
 *
 * @author 二开
 */
public final class ErpProductTypeTree
{
    private ErpProductTypeTree()
    {
    }

    /**
     * 收集以 {@code rootId} 为根的子树 ID 集合（含根自身）。
     *
     * @param types  全部类型（可空；通常来自 {@code CtmsProductTypeMapper.selectProductTypeList}）
     * @param rootId 根类型ID（空白 → 返回 {@code null}，表示"不按类型过滤"）
     * @return 子树ID列表（根在前、层序）；{@code rootId} 为空白时返回 {@code null}
     */
    public static List<String> subtreeIds(List<CtmsProductType> types, String rootId)
    {
        String root = ErpLedgerRules.trimToNull(rootId);
        if (root == null)
        {
            return null;
        }
        Map<String, List<String>> childrenOf = groupByParent(types);
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root);
        while (!queue.isEmpty())
        {
            String current = queue.poll();
            ordered.add(current);
            List<String> children = childrenOf.get(current);
            if (children == null)
            {
                continue;
            }
            for (String child : children)
            {
                if (child == null || visited.contains(child))
                {
                    continue;
                }
                visited.add(child);
                queue.add(child);
            }
        }
        return ordered;
    }

    /**
     * 把类型集合按 {@code parent_id} 分组（保持传入顺序）。
     *
     * @param types 类型集合
     * @return parentId → 子ID列表（parentId 为 null/空时归到 {@code ""} 键）
     */
    private static Map<String, List<String>> groupByParent(List<CtmsProductType> types)
    {
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        if (types == null)
        {
            return childrenOf;
        }
        for (CtmsProductType type : types)
        {
            if (type == null || ErpLedgerRules.isBlank(type.getId()))
            {
                continue;
            }
            String parentKey = type.getParentId() == null ? "" : type.getParentId().trim();
            List<String> children = childrenOf.get(parentKey);
            if (children == null)
            {
                children = new ArrayList<>();
                childrenOf.put(parentKey, children);
            }
            children.add(type.getId().trim());
        }
        return childrenOf;
    }
}
