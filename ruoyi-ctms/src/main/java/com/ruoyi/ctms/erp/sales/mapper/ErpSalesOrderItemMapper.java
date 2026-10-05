package com.ruoyi.ctms.erp.sales.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;

/**
 * <p> 销售订单行项的数据访问接口，对应 XML
 * {@code resources/mapper/erp/ErpSalOrderItemMapper.xml}。 </p>
 *
 * <p> 与申请行项的差别：本类多一个 {@code shipped_qty} 累计列的写入点，并且
 * <b>通过 {@code src_item_id} 反查</b>是"销售订单 → 出库单过账回写"的匹配方式
 * （tasks.md §4.3 属后续任务，方法在此预留以免下游再改本接口）。 </p>
 *
 * @author 二开
 */
public interface ErpSalesOrderItemMapper
{
    /**
     * 按单据ID查行项（按序号升序）。
     *
     * @param docId 单据ID
     * @return 行项集合（非 null）
     */
    List<ErpSalesOrderItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * <p> 按<b>单据ID集合</b>批量查行项（列表派生列 {@code remainQtySum} 用）。 </p>
     *
     * <p> <b>为什么返回"平铺的 List"而不是 {@code Map<String,List<…>>}</b>（D-1 缺陷修复）：
     * MyBatis 的 {@code @MapKey} 只支持「<b>一行 → 一个值</b>」；返回类型写成
     * {@code @MapKey("docId") Map<String, List<ErpSalesOrderItem>>} 时，它把 value 类型
     * {@code java.util.List} 当作每行的目标类型 ⇒ 逐行 {@code (List) ErpSalesOrderItem}
     * 抛 {@code cannot be cast to class java.util.List}。分组归服务层做。 </p>
     *
     * <p> <b>不 N+1</b>：每页 N 条只查这一次；服务层 {@code LinkedHashMap} + {@code computeIfAbsent}
     * 一次分组。语句 {@code order by doc_id asc, seq asc} 保证分组后每个单据内按 {@code seq} 升序。 </p>
     *
     * @param docIds 单据ID集合（服务层保证非空）
     * @return 行项集合（按 {@code doc_id, seq} 升序；无命中返回空集合而不是 null）
     */
    List<ErpSalesOrderItem> selectItemsByDocIds(@Param("docIds") List<String> docIds);

    /**
     * <p> 按<b>来源行项ID</b>集合反查下游单据行（出库单行 → 销售订单行的匹配依据）。 </p>
     *
     * @param srcItemIds 来源行项ID集合（服务层保证非空）
     * @return 行项集合（按单据、序号升序）
     */
    List<ErpSalesOrderItem> selectItemsBySrcItemIds(@Param("srcItemIds") List<String> srcItemIds);

    /**
     * 按单据ID删除全部行项（编辑时的「先全删」半步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项（{@code id} 由服务层生成）。
     *
     * @param items 行项集合（服务层保证非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpSalesOrderItem> items);

    /**
     * <p> 批量插入或更新行项（<b>编辑路径专用</b>）：按主键命中则更新业务列，未命中则插入。 </p>
     *
     * <p> 与申请行项同款：删除重建会把 {@code shipped_qty} 清零，因此编辑走 upsert，
     * 且 {@code shipped_qty} 不参与更新列（本任务不涉及该列的累加，见 §4.3）。 </p>
     *
     * @param items 行项集合（服务层保证非空）
     * @return 影响行数
     */
    int batchUpsertItems(@Param("items") List<ErpSalesOrderItem> items);

    /**
     * 按主键查行项。
     *
     * @param id 行项ID
     * @return 行项；不存在返回 null
     */
    ErpSalesOrderItem selectItemById(@Param("id") String id);

    /**
     * <p> 累加已出库数量（<b>过账成功时</b>由出库单过账路径调用，tasks.md §4.3）。 </p>
     *
     * @param itemId 行项ID
     * @param delta  本次出库数量（正数）
     * @return 影响行数
     */
    int addShippedQty(@Param("itemId") String itemId, @Param("delta") BigDecimal delta);

    /**
     * <p> 回退已出库数量（红冲时调用），SQL 用 {@code greatest(0, ...)} 保证<b>不小于 0</b>。 </p>
     *
     * @param itemId 行项ID
     * @param delta  本次出库数量（正数）
     * @return 影响行数
     */
    int subtractShippedQty(@Param("itemId") String itemId, @Param("delta") BigDecimal delta);
}
