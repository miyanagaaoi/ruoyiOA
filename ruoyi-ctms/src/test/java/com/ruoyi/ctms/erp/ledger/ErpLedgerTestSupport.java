package com.ruoyi.ctms.erp.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.erp.ledger.domain.ErpLedgerQtySum;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockBalanceMapper;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockLedgerQueryMapper;
import com.ruoyi.ctms.mapper.CtmsProductTypeMapper;

/**
 * <p> <b>库存账单测的内存桩</b>（B4 第 7 组；照 B3/T3 的"不起 Spring"风格）。 </p>
 *
 * <p> <b>桩要模拟的是 SQL 语义，不是"返回我塞的东西"</b> —— 否则单测只能证明
 * "服务层没删行"，证明不了筛选/排序/范围口径。因此每个桩都逐条实现了对应
 * Mapper XML 里的 where/order by： </p>
 * <ul>
 *   <li> {@link StubBalanceMapper} —— 关键字 / 仓库 / 商品类型 ID 集合 / 数量区间 /
 *        低于安全库存 / 隐藏零结存 / 数据范围； </li>
 *   <li> {@link StubLedgerQueryMapper} —— 按 ID、按（物料, 仓库）、业务类型、单据类型、
 *        时间区间、关键字 / 数据范围，并按"记账时间 desc, id desc"排序； </li>
 *   <li> 数据范围用 {@code visibleKeys} / {@code visibleLedgerIds} 模拟：
 *        片段非空时只保留集合内的行（等价于 XML 里注入的 {@code and ( ${dataScopeSql} )}）。 </li>
 * </ul>
 *
 * <p> <b>与真实库的差别（必须写进 notes）</b>：桩里"先乘后舍入"这类 SQL 端行为由
 * SQL 自己保证（{@code round(qty_change * unit_price, 2)}），桩只搬运已算好的字段；
 * 真正的额度 SQL 断言在 T10/T13 的接口清单里（见 {@code notes/07-ledger.md}）。 </p>
 *
 * @author 二开
 */
public final class ErpLedgerTestSupport
{
    private ErpLedgerTestSupport()
    {
    }

    /**
     * 造一行结存明细（数量/安全库存用字符串，避免测试里出现浮点字面量）。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @param qty         结存数量
     * @param safetyStock 安全库存
     * @return 明细行
     */
    public static ErpStockBalance balance(String productId, String warehouseId, String qty, String safetyStock)
    {
        return balance(productId, warehouseId, qty, safetyStock, null, null, null);
    }

    /**
     * 造一行结存明细（带展示字段）。
     *
     * @param productId       物料ID
     * @param warehouseId     仓库ID
     * @param qty             结存数量
     * @param safetyStock     安全库存
     * @param productTypeName 商品类型名称
     * @param productName     物料名称
     * @param productTypeId   商品类型ID
     * @return 明细行
     */
    public static ErpStockBalance balance(String productId, String warehouseId, String qty, String safetyStock,
                                          String productTypeName, String productName, String productTypeId)
    {
        ErpStockBalance row = new ErpStockBalance();
        row.setId("S-" + productId + "-" + warehouseId);
        row.setProductId(productId);
        row.setWarehouseId(warehouseId);
        row.setQty(new BigDecimal(qty));
        row.setSafetyStock(safetyStock == null ? null : new BigDecimal(safetyStock));
        row.setProductCode("P-" + productId);
        row.setProductName(productName);
        row.setProductTypeName(productTypeName);
        row.setProductTypeId(productTypeId);
        row.setWarehouseCode("W-" + warehouseId);
        row.setWarehouseName("仓-" + warehouseId);
        return row;
    }

    /**
     * 造一条库存流水。
     *
     * @param id          流水ID
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @param bizType     业务类型
     * @param qtyChange   数量变动
     * @param unitPrice   单价（可空）
     * @param createTime  记账时间
     * @return 流水行
     */
    public static ErpStockLedgerRow ledger(String id, String productId, String warehouseId, String bizType,
                                           String qtyChange, String unitPrice, Date createTime)
    {
        ErpStockLedgerRow row = new ErpStockLedgerRow();
        row.setId(id);
        row.setProductId(productId);
        row.setWarehouseId(warehouseId);
        row.setBizType(bizType);
        row.setDocType("stock_in");
        row.setDocNo("IN-" + id);
        row.setSrcDocNo("PO-" + id);
        row.setQtyChange(new BigDecimal(qtyChange));
        row.setQtyAfter(new BigDecimal(qtyChange));
        row.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        row.setLedgerTime(createTime);
        row.setCreateBy("tester");
        row.setOperatorId("1");
        row.setDeptId("100");
        row.setProductCode("P-" + productId);
        row.setProductName("物料-" + productId);
        row.setProductTypeName("类型-甲");
        row.setWarehouseCode("W-" + warehouseId);
        row.setWarehouseName("仓-" + warehouseId);
        return row;
    }

    /**
     * 造一个商品类型节点。
     *
     * @param id       类型ID
     * @param parentId 上级类型ID（根传 null）
     * @param name     类型名称
     * @return 类型
     */
    public static CtmsProductType type(String id, String parentId, String name)
    {
        CtmsProductType type = new CtmsProductType();
        type.setId(id);
        type.setParentId(parentId);
        type.setName(name);
        return type;
    }

    /**
     * 结存明细桩（逐条实现 XML 的 where 语义）。
     */
    public static class StubBalanceMapper implements ErpStockBalanceMapper
    {
        /** 预置的明细行。 */
        public final List<ErpStockBalance> rows = new ArrayList<>();

        /** 数据范围片段非空时"可见"的（物料 \u0001 仓库）集合。 */
        public final Set<String> visibleKeys = new HashSet<>();

        /**
         * 预置一行。
         *
         * @param row 明细行
         */
        public void seed(ErpStockBalance row)
        {
            rows.add(row);
        }

        /**
         * 标记某（物料, 仓库）在数据范围内可见。
         *
         * @param productId   物料ID
         * @param warehouseId 仓库ID
         */
        public void markVisible(String productId, String warehouseId)
        {
            visibleKeys.add(key(productId, warehouseId));
        }

        @Override
        public List<ErpStockBalance> selectBalanceList(ErpStockBalance query)
        {
            List<ErpStockBalance> hits = new ArrayList<>();
            boolean scoped = query != null && query.getDataScopeSql() != null && !query.getDataScopeSql().isEmpty();
            for (ErpStockBalance row : rows)
            {
                if (query != null)
                {
                    if (notBlank(query.getProductId()) && !query.getProductId().equals(row.getProductId()))
                    {
                        continue;
                    }
                    if (notBlank(query.getWarehouseId()) && !query.getWarehouseId().equals(row.getWarehouseId()))
                    {
                        continue;
                    }
                    if (query.getProductTypeIds() != null && !query.getProductTypeIds().isEmpty()
                            && !query.getProductTypeIds().contains(row.getProductTypeId()))
                    {
                        continue;
                    }
                    if (notBlank(query.getKeyword()) && !containsAny(row, query.getKeyword()))
                    {
                        continue;
                    }
                    if (query.getBeginQty() != null && row.getQty().compareTo(query.getBeginQty()) < 0)
                    {
                        continue;
                    }
                    if (query.getEndQty() != null && row.getQty().compareTo(query.getEndQty()) > 0)
                    {
                        continue;
                    }
                    if (Boolean.TRUE.equals(query.getBelowSafetyOnly()) && !row.isBelowSafetyStock())
                    {
                        continue;
                    }
                    if (Boolean.TRUE.equals(query.getHideZero()) && row.getQty().signum() == 0)
                    {
                        continue;
                    }
                }
                if (scoped && !visibleKeys.contains(key(row.getProductId(), row.getWarehouseId())))
                {
                    continue;
                }
                hits.add(row);
            }
            return hits;
        }

        @Override
        public ErpStockBalance selectBalanceByKey(String productId, String warehouseId)
        {
            for (ErpStockBalance row : rows)
            {
                if (row.getProductId().equals(productId) && row.getWarehouseId().equals(warehouseId))
                {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int countBalance()
        {
            return rows.size();
        }

        private static boolean containsAny(ErpStockBalance row, String keyword)
        {
            String needle = keyword.trim();
            return contains(row.getProductCode(), needle) || contains(row.getProductName(), needle)
                    || contains(row.getSpec(), needle);
        }

        private static boolean contains(String value, String needle)
        {
            return value != null && value.contains(needle);
        }
    }

    /**
     * 流水只读桩（实现筛选 / 排序 / 聚合）。
     */
    public static class StubLedgerQueryMapper implements ErpStockLedgerQueryMapper
    {
        /** 预置的流水行。 */
        public final List<ErpStockLedgerRow> rows = new ArrayList<>();

        /** 数据范围片段非空时"可见"的流水ID集合。 */
        public final Set<String> visibleLedgerIds = new HashSet<>();

        /**
         * 预置一条流水。
         *
         * @param row 流水行
         */
        public void seed(ErpStockLedgerRow row)
        {
            rows.add(row);
        }

        /**
         * 标记某条流水在数据范围内可见。
         *
         * @param id 流水ID
         */
        public void markVisible(String id)
        {
            visibleLedgerIds.add(id);
        }

        @Override
        public List<ErpStockLedgerRow> selectLedgerRows(ErpStockLedgerRow query)
        {
            List<ErpStockLedgerRow> hits = new ArrayList<>();
            boolean scoped = query != null && query.getDataScopeSql() != null && !query.getDataScopeSql().isEmpty();
            for (ErpStockLedgerRow row : rows)
            {
                if (query != null)
                {
                    if (notBlank(query.getLedgerId()) && !query.getLedgerId().equals(row.getId()))
                    {
                        continue;
                    }
                    if (notBlank(query.getProductId()) && !query.getProductId().equals(row.getProductId()))
                    {
                        continue;
                    }
                    if (notBlank(query.getWarehouseId()) && !query.getWarehouseId().equals(row.getWarehouseId()))
                    {
                        continue;
                    }
                    if (notBlank(query.getBizTypeFilter()) && !query.getBizTypeFilter().equals(row.getBizType()))
                    {
                        continue;
                    }
                    if (notBlank(query.getDocTypeFilter()) && !query.getDocTypeFilter().equals(row.getDocType()))
                    {
                        continue;
                    }
                    if (query.getBeginTime() != null && row.getLedgerTime().before(query.getBeginTime()))
                    {
                        continue;
                    }
                    if (query.getEndTime() != null && row.getLedgerTime().after(query.getEndTime()))
                    {
                        continue;
                    }
                    if (notBlank(query.getKeyword()) && !containsAny(row, query.getKeyword()))
                    {
                        continue;
                    }
                }
                if (scoped && !visibleLedgerIds.contains(row.getId()))
                {
                    continue;
                }
                hits.add(row);
            }
            // 与 XML 的 order by l.create_time desc, l.id desc 一致
            hits.sort((left, right) -> {
                int byTime = right.getLedgerTime().compareTo(left.getLedgerTime());
                return byTime != 0 ? byTime : right.getId().compareTo(left.getId());
            });
            return hits;
        }

        @Override
        public ErpStockLedgerRow selectLedgerRowById(String id)
        {
            for (ErpStockLedgerRow row : rows)
            {
                if (row.getId().equals(id))
                {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<ErpLedgerQtySum> selectLedgerQtySums()
        {
            Map<String, ErpLedgerQtySum> sums = new LinkedHashMap<>();
            Map<String, BigDecimal> values = new HashMap<>();
            for (ErpStockLedgerRow row : rows)
            {
                String key = key(row.getProductId(), row.getWarehouseId());
                BigDecimal current = values.get(key);
                BigDecimal next = (current == null ? BigDecimal.ZERO : current).add(row.getQtyChange());
                values.put(key, next);
                ErpLedgerQtySum sum = sums.get(key);
                if (sum == null)
                {
                    sum = new ErpLedgerQtySum();
                    sum.setProductId(row.getProductId());
                    sum.setWarehouseId(row.getWarehouseId());
                    sums.put(key, sum);
                }
                sum.setQtySum(next);
            }
            return new ArrayList<>(sums.values());
        }

        private static boolean containsAny(ErpStockLedgerRow row, String keyword)
        {
            String needle = keyword.trim();
            return contains(row.getDocNo(), needle) || contains(row.getSrcDocNo(), needle)
                    || contains(row.getProductCode(), needle) || contains(row.getProductName(), needle);
        }

        private static boolean contains(String value, String needle)
        {
            return value != null && value.contains(needle);
        }
    }

    /**
     * 商品类型桩（只实现"全量列表"与"按 ID 查"；写方法直接抛错，避免被误用）。
     */
    public static class StubProductTypeMapper implements CtmsProductTypeMapper
    {
        /** 预置的类型节点。 */
        public final List<CtmsProductType> types = new ArrayList<>();

        /**
         * 预置一个类型。
         *
         * @param type 类型
         */
        public void seed(CtmsProductType type)
        {
            types.add(type);
        }

        /** 模拟 PageHelper 拦截：线程里有活动页时只返回前 pageSize 行，并"消费"掉该页（F-01 回归用）。 */
        public boolean simulatePageHelper = true;

        /** 被"分页截断"的次数（断言桩确实生效过）。 */
        public int truncatedCalls;

        @Override
        public List<CtmsProductType> selectProductTypeList(CtmsProductType query)
        {
            List<CtmsProductType> all = new ArrayList<>(types);
            if (!simulatePageHelper)
            {
                return all;
            }
            com.github.pagehelper.Page<?> page = com.github.pagehelper.PageHelper.getLocalPage();
            if (page == null || page.getPageSize() <= 0)
            {
                return all;
            }
            com.github.pagehelper.PageHelper.clearPage();   // 与真实拦截器一致：这次分页被本查询消费
            truncatedCalls++;
            int limit = Math.min(page.getPageSize(), all.size());
            return new ArrayList<>(all.subList(0, limit));
        }

        @Override
        public CtmsProductType selectProductTypeById(String id)
        {
            for (CtmsProductType type : types)
            {
                if (type.getId().equals(id))
                {
                    return type;
                }
            }
            return null;
        }

        @Override
        public CtmsProductType selectProductTypeByNameAndParent(String name, String parentId)
        {
            throw new UnsupportedOperationException("单测桩不提供按名查询");
        }

        @Override
        public int countChildren(String parentId)
        {
            throw new UnsupportedOperationException("单测桩不提供计数");
        }

        @Override
        public int countProductsByType(String productTypeId)
        {
            throw new UnsupportedOperationException("单测桩不提供计数");
        }

        @Override
        public int insertProductType(CtmsProductType productType)
        {
            throw new UnsupportedOperationException("库存账只读，不应写档案");
        }

        @Override
        public int updateProductType(CtmsProductType productType)
        {
            throw new UnsupportedOperationException("库存账只读，不应写档案");
        }

        @Override
        public int deleteProductTypeById(String id)
        {
            throw new UnsupportedOperationException("库存账只读，不应写档案");
        }
    }

    /**
     * 聚合键（与真实 SQL 的 {@code group by product_id, warehouse_id} 等价）。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 键
     */
    public static String key(String productId, String warehouseId)
    {
        return productId + "\u0001" + warehouseId;
    }

    private static boolean notBlank(String value)
    {
        return value != null && !value.trim().isEmpty();
    }
}
