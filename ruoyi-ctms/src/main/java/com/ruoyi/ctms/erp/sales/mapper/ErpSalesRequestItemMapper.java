package com.ruoyi.ctms.erp.sales.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;

/**
 * <p> 销售申请单行项的数据访问接口，对应 XML
 * {@code resources/mapper/erp/ErpSalRequestItemMapper.xml}。 </p>
 *
 * <p> 与 B3 合同行项同款：行项是从属明细，编辑口径是「先按单据全删、再整批插入」，
 * 因此提供 {@link #deleteItemsByDocId} + {@link #batchInsertItems} 这一对。 </p>
 *
 * <p> {@link #updateOrderedQty} 是"申请 → 订单下推即时累加已下推量"的唯一写入点
 * （由销售下推服务调用；<b>不</b>通过整单编辑路径，避免与全量改写互相覆盖）。 </p>
 *
 * @author 二开
 */
public interface ErpSalesRequestItemMapper
{
    /**
     * 按单据ID查行项（按序号升序）。
     *
     * @param docId 单据ID
     * @return 行项集合（非 null）
     */
    List<ErpSalesRequestItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * <p> 按<b>单据ID集合</b>批量查行项（列表派生列 {@code remainQtySum} / {@code totalAmount} 用）。 </p>
     *
     * <p> <b>为什么返回"平铺的 List"而不是 {@code Map<String,List<…>>}</b>（D-1 缺陷修复）：
     * MyBatis 的 {@code @MapKey} 只支持「<b>一行 → 一个值</b>」——把返回类型写成
     * {@code @MapKey("docId") Map<String, List<ErpSalesRequestItem>>} 时，它会把声明里的 value 类型
     * {@code java.util.List} 当作<b>每行的目标类型</b>，于是逐行尝试
     * {@code (List) ErpSalesRequestItem} ⇒ 运行期抛
     * {@code ErpSalesRequestItem cannot be cast to class java.util.List}，接口返回业务 {@code code=500}。
     * 分组是<b>服务层</b>的职责，不是 MyBatis 的。 </p>
     *
     * <p> <b>不 N+1</b>：列表每页 N 条仍然<b>只查这一次</b>（一条 {@code where doc_id in (…)}）；
     * 服务层按 {@code docId} 一次分组（{@code LinkedHashMap} + {@code computeIfAbsent}）。
     * 排序保证：本语句 {@code order by doc_id asc, seq asc}，因此分组后每个单据内的行项
     * 仍按 {@code seq} 升序，且 Map 的键顺序与查询返回顺序一致。 </p>
     *
     * @param docIds 单据ID集合（服务层保证非空）
     * @return 行项集合（按 {@code doc_id, seq} 升序；无命中返回空集合而不是 null）
     */
    List<ErpSalesRequestItem> selectItemsByDocIds(@Param("docIds") List<String> docIds);

    /**
     * <p> 按<b>来源行项ID</b>集合反查下游单据行（销售订单 → 出库单的过账回写用）。 </p>
     *
     * <p> 返回 {@code src_item_id} 命中给定集合的行项；空集合由服务层挡在调用前。 </p>
     *
     * @param srcItemIds 来源行项ID集合（服务层保证非空）
     * @return 行项集合（按单据、序号升序）
     */
    List<ErpSalesRequestItem> selectItemsBySrcItemIds(@Param("srcItemIds") List<String> srcItemIds);

    /**
     * 按单据ID删除全部行项（编辑时的「先全删」半步）。
     *
     * @param docId 单据ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);

    /**
     * 批量插入行项（{@code id} 由服务层生成；序号由服务层按提交顺序重排）。
     *
     * @param items 行项集合（服务层保证非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpSalesRequestItem> items);

    /**
     * <p> 批量插入或更新行项（<b>编辑路径专用</b>）：按主键命中则更新业务列，未命中则插入。 </p>
     *
     * <p> <b>为什么编辑不能"先全删再全插"</b>：同一行ID在下推里已经被累加过
     * {@code ordered_qty}，删除重建会把"已下推量"清零，等于放开了超量下推
     * （参考仓库 {@code sales.py} 的编辑路径也是保留行ID的）。因此编辑走 upsert，
     * 且 {@code ordered_qty} <b>保持库内值不变</b>（不参与更新列）。 </p>
     *
     * @param items 行项集合（服务层保证非空）
     * @return 影响行数
     */
    int batchUpsertItems(@Param("items") List<ErpSalesRequestItem> items);

    /**
     * 累加某行的已下推数量（下推成功后立即调用，与下推在同一事务内）。
     *
     * @param itemId 行项ID
     * @param delta  本次下推数量（正数）
     * @return 影响行数
     */
    int updateOrderedQty(@Param("itemId") String itemId, @Param("delta") java.math.BigDecimal delta);

    /**
     * 按主键查行项（下推的"来源行"定位与单测断言用）。
     *
     * @param id 行项ID
     * @return 行项；不存在返回 null
     */
    ErpSalesRequestItem selectItemById(@Param("id") String id);
}
