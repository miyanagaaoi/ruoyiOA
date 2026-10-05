package com.ruoyi.ctms.erp.stockops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.ctms.erp.posting.service.impl.ErpStockJournalServiceImpl;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktake;
import com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;
import com.ruoyi.ctms.erp.stockops.domain.ErpTransferItem;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStockOpsMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStocktakeItemMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpStocktakeMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpTransferItemMapper;
import com.ruoyi.ctms.erp.stockops.mapper.ErpTransferMapper;

/**
 * <p> <b>调拨 / 盘点单测的内存桩</b>（2.0 B4 任务 6.7）。 </p>
 *
 * <p> 只提供本组新增的四个 Mapper 与只读辅助查询的桩；结存 / 流水 / 变更历史 / 数据范围 /
 * 主数据这五类桩<b>直接复用</b> T3 的 {@code ErpPostingTestSupport}
 * （那套桩已经具备"行锁持有区间"的并发语义，重写一份只会造成两套并发语义）。 </p>
 *
 * <p> 桩一律<b>持有服务传入的同一个实体对象</b>（不做字段拷贝）：实体被服务层改写后
 * 断言立刻可见；这是"脱库单测"下最贴近真实 MyBatis 回读的行为。 </p>
 *
 * @author 二开
 */
public final class ErpStockOpsTestSupport
{
    private ErpStockOpsTestSupport()
    {
    }

    /**
     * 测试用过账引擎：把"是否允许负库存"参数换成可写字段（生产实现读 {@code sys_config}）。
     *
     * <p> 盘亏豁免的用例靠它证明"参数是关闭的"，从而说明盘亏能过账是<b>显式传参</b>的结果。 </p>
     */
    public static class TestJournalService extends ErpStockJournalServiceImpl
    {
        /** 参数值（默认关闭）。 */
        public String negativeParam = "false";

        @Override
        protected String readNegativeStockParam()
        {
            return negativeParam;
        }
    }

    /** 调拨单表头桩。 */
    public static class StubTransferMapper implements ErpTransferMapper
    {
        /** 已落库表头（id → 单据；持有同一对象引用）。 */
        public final Map<String, ErpTransfer> docs = new LinkedHashMap<>();

        /** 最近一次列表查询（断言数据范围片段用）。 */
        public ErpTransfer lastQuery;

        @Override
        public List<ErpTransfer> selectTransferList(ErpTransfer query)
        {
            lastQuery = query;
            return new ArrayList<>(docs.values());
        }

        @Override
        public ErpTransfer selectTransferById(String id)
        {
            return docs.get(id);
        }

        @Override
        public int insertTransfer(ErpTransfer doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int updateTransfer(ErpTransfer doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int deleteTransferByIds(String[] ids)
        {
            int removed = 0;
            if (ids != null)
            {
                for (String id : ids)
                {
                    if (docs.remove(id) != null)
                    {
                        removed++;
                    }
                }
            }
            return removed;
        }
    }

    /** 调拨单行项桩。 */
    public static class StubTransferItemMapper implements ErpTransferItemMapper
    {
        /** 行项（docId → 行项）。 */
        public final Map<String, List<ErpTransferItem>> items = new LinkedHashMap<>();

        @Override
        public List<ErpTransferItem> selectItemsByDocId(String docId)
        {
            List<ErpTransferItem> rows = items.get(docId);
            return rows == null ? new ArrayList<ErpTransferItem>() : new ArrayList<>(rows);
        }

        @Override
        public int batchInsertItems(List<ErpTransferItem> rows)
        {
            if (rows == null)
            {
                return 0;
            }
            for (ErpTransferItem row : rows)
            {
                items.computeIfAbsent(row.getDocId(), key -> new ArrayList<ErpTransferItem>()).add(row);
            }
            return rows.size();
        }

        @Override
        public int deleteItemsByDocId(String docId)
        {
            items.remove(docId);
            return 1;
        }
    }

    /** 盘点单表头桩。 */
    public static class StubStocktakeMapper implements ErpStocktakeMapper
    {
        /** 已落库表头（id → 单据；持有同一对象引用）。 */
        public final Map<String, ErpStocktake> docs = new LinkedHashMap<>();

        /** 最近一次列表查询（断言数据范围片段用）。 */
        public ErpStocktake lastQuery;

        @Override
        public List<ErpStocktake> selectStocktakeList(ErpStocktake query)
        {
            lastQuery = query;
            return new ArrayList<>(docs.values());
        }

        @Override
        public ErpStocktake selectStocktakeById(String id)
        {
            return docs.get(id);
        }

        @Override
        public int insertStocktake(ErpStocktake doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int updateStocktake(ErpStocktake doc)
        {
            docs.put(doc.getId(), doc);
            return 1;
        }

        @Override
        public int deleteStocktakeByIds(String[] ids)
        {
            int removed = 0;
            if (ids != null)
            {
                for (String id : ids)
                {
                    if (docs.remove(id) != null)
                    {
                        removed++;
                    }
                }
            }
            return removed;
        }
    }

    /** 盘点单行项桩。 */
    public static class StubStocktakeItemMapper implements ErpStocktakeItemMapper
    {
        /** 行项（docId → 行项）。 */
        public final Map<String, List<ErpStocktakeItem>> items = new LinkedHashMap<>();

        @Override
        public List<ErpStocktakeItem> selectItemsByDocId(String docId)
        {
            List<ErpStocktakeItem> rows = items.get(docId);
            return rows == null ? new ArrayList<ErpStocktakeItem>() : new ArrayList<>(rows);
        }

        @Override
        public int batchInsertItems(List<ErpStocktakeItem> rows)
        {
            if (rows == null)
            {
                return 0;
            }
            for (ErpStocktakeItem row : rows)
            {
                items.computeIfAbsent(row.getDocId(), key -> new ArrayList<ErpStocktakeItem>()).add(row);
            }
            return rows.size();
        }

        @Override
        public int deleteItemsByDocId(String docId)
        {
            items.remove(docId);
            return 1;
        }
    }

    /**
     * 盘点只读辅助查询桩：商品类型子树与"按类型取启用物料"都可配置。
     *
     * <p> 递归 CTE 在内存里就是一张显式的子树表：{@code subtree.put(typeId, [typeId, 子, 孙…])}。 </p>
     */
    public static class StubStockOpsMapper implements ErpStockOpsMapper
    {
        /** 类型 → 子树类型ID（含自身）。 */
        public final Map<String, List<String>> subtree = new LinkedHashMap<>();

        /** 类型 → 启用物料ID。 */
        public final Map<String, List<String>> productsByType = new LinkedHashMap<>();

        /** 被查询过的类型（断言"整棵子树"确实被展开）。 */
        public final Set<String> queriedTypes = new LinkedHashSet<>();

        /**
         * 登记一棵子树。
         *
         * @param typeId 类型ID
         * @param ids    子树类型ID（含自身）
         */
        public void addSubtree(String typeId, String... ids)
        {
            List<String> list = new ArrayList<>();
            for (String id : ids)
            {
                list.add(id);
            }
            subtree.put(typeId, list);
        }

        /**
         * 登记某类型下的启用物料。
         *
         * @param typeId     类型ID
         * @param productIds 物料ID
         */
        public void addProducts(String typeId, String... productIds)
        {
            List<String> list = new ArrayList<>();
            for (String id : productIds)
            {
                list.add(id);
            }
            productsByType.put(typeId, list);
        }

        @Override
        public List<String> selectTypeSubtreeIds(String typeId)
        {
            queriedTypes.add(typeId);
            List<String> ids = subtree.get(typeId);
            return ids == null ? new ArrayList<String>() : new ArrayList<>(ids);
        }

        @Override
        public List<String> selectEnabledProductIdsByTypeIds(List<String> typeIds)
        {
            List<String> result = new ArrayList<>();
            if (typeIds != null)
            {
                for (String typeId : typeIds)
                {
                    List<String> products = productsByType.get(typeId);
                    if (products != null && !result.containsAll(products))
                    {
                        for (String product : products)
                        {
                            if (!result.contains(product))
                            {
                                result.add(product);
                            }
                        }
                    }
                }
            }
            return result;
        }
    }
}
