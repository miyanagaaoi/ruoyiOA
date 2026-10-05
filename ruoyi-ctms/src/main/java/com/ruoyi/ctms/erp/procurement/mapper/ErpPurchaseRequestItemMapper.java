package com.ruoyi.ctms.erp.procurement.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;

/**
 * <p> 采购申请行项的数据访问接口（XML：{@code ErpPurRequestItemMapper.xml}）。 </p>
 *
 * <p> 行项的写入方式是<b>全量替换</b>（与 B3 的 {@code CtmsContractItemMapper} 同款）：
 * 编辑时先 {@link #deleteItemsByDocId} 再批量插入，序号按提交顺序从 1 连续重排。 </p>
 *
 * <p> ⚠ {@link #increaseOrderedQty} 是"下推即累加已下单量"的唯一写入口，
 * 用 {@code ordered_qty = ordered_qty + #{qty}} 的<b>库内自增</b>而不是"读出来再写"：
 * 后者在两个下推请求并发时会互相覆盖（少累加），前者由行锁保证不丢。 </p>
 *
 * @author 二开
 */
public interface ErpPurchaseRequestItemMapper
{
    /**
     * 取某单据的全部行项（按序号升序）。
     *
     * @param docId 表头ID
     * @return 行项集合（非 null）
     */
    List<ErpPurchaseRequestItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 取单据行项的下推视图（额外带出 {@code remaining_qty} 派生列，供详情/列表直接展示）。
     *
     * @param docId 表头ID
     * @return 行项集合
     */
    List<ErpPurchaseRequestItem> selectRemainItemsByDocId(@Param("docId") String docId);

    /**
     * 取多个单据的行项（列表页批量装配"剩余可下推量合计"用，避免每行一次查询）。
     *
     * @param docIds 表头ID集合（非空）
     * @return 行项集合（按 doc_id、seq 升序）
     */
    List<ErpPurchaseRequestItem> selectItemsByDocIds(@Param("docIds") List<String> docIds);

    /**
     * 按主键取行项（下推时按 {@code srcItemIds} 定位来源行）。
     *
     * @param id 行项主键
     * @return 行项；不存在返回 null
     */
    ErpPurchaseRequestItem selectItemById(@Param("id") String id);

    /**
     * 批量插入行项（主表必须先落库：{@code doc_id} 上有非空外键）。
     *
     * @param items 行项集合（非空）
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpPurchaseRequestItem> items);

    /**
     * 下推即累加已下单量（库内自增；并发安全）。
     *
     * @param id  行项主键
     * @param qty 本次下推数量
     * @return 影响行数
     */
    int increaseOrderedQty(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 删除单行行项（行项单独维护时用"删旧插新"保证不丢列）。
     *
     * @param id 行项主键
     * @return 影响行数
     */
    int deleteItemById(@Param("id") String id);

    /**
     * 重排单行序号（删除后出现空洞时调用；从 1 连续）。
     *
     * @param id  行项主键
     * @param seq 新序号
     * @return 影响行数
     */
    int updateItemSeq(@Param("id") String id, @Param("seq") Integer seq);

    /**
     * 删除某单据的全部行项（编辑时全量替换 / 夹具清理）。
     *
     * @param docId 表头ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
