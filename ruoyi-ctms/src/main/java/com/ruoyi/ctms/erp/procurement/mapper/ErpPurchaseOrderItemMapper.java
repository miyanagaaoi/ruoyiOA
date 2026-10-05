package com.ruoyi.ctms.erp.procurement.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;

/**
 * <p> 采购单行项的数据访问接口（XML：{@code ErpPurOrderItemMapper.xml}）。 </p>
 *
 * <p> 写入方式与申请行项一致：<b>全量替换</b> + 序号重排。 </p>
 *
 * <p> ⚠ {@link #increaseReceivedQty} / {@link #decreaseReceivedQty} 是任务 3.5 的落点
 * （过账累加、红冲回退），本任务（3.1~3.4、3.6）<b>只声明不使用</b>：
 * 之所以现在就放进来，是因为"回写口径只有这两个方法"必须一次定死，
 * 后续 t4b 不允许再各写一条 {@code update}。 </p>
 *
 * @author 二开
 */
public interface ErpPurchaseOrderItemMapper
{
    /**
     * 取某采购单的全部行项（按序号升序）。
     *
     * @param docId 表头ID
     * @return 行项集合（非 null）
     */
    List<ErpPurchaseOrderItem> selectItemsByDocId(@Param("docId") String docId);

    /**
     * 取多个采购单的行项（列表页批量装配"剩余可入库量合计"用）。
     *
     * @param docIds 表头ID集合（非空）
     * @return 行项集合
     */
    List<ErpPurchaseOrderItem> selectItemsByDocIds(@Param("docIds") List<String> docIds);

    /**
     * 按主键取行项。
     *
     * @param id 行项主键
     * @return 行项；不存在返回 null
     */
    ErpPurchaseOrderItem selectItemById(@Param("id") String id);

    /**
     * 批量插入行项（主表必须先落库）。
     *
     * @param items 行项集合
     * @return 影响行数
     */
    int batchInsertItems(@Param("items") List<ErpPurchaseOrderItem> items);

    /**
     * 过账时累加已入库量（任务 3.5；库内自增）。
     *
     * @param id  行项主键
     * @param qty 本次入库数量
     * @return 影响行数
     */
    int increaseReceivedQty(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 红冲时回退已入库量（任务 3.5；库侧用 {@code GREATEST(0, received_qty - qty)} 保证不为负）。
     *
     * @param id  行项主键
     * @param qty 回退数量
     * @return 影响行数
     */
    int decreaseReceivedQty(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 删除单行行项（行项单独维护时用"删旧插新"保证不丢列）。
     *
     * @param id 行项主键
     * @return 影响行数
     */
    int deleteItemById(@Param("id") String id);

    /**
     * 重排单行序号（从 1 连续）。
     *
     * @param id  行项主键
     * @param seq 新序号
     * @return 影响行数
     */
    int updateItemSeq(@Param("id") String id, @Param("seq") Integer seq);

    /**
     * 删除某采购单的全部行项（编辑时全量替换 / 夹具清理）。
     *
     * @param docId 表头ID
     * @return 影响行数
     */
    int deleteItemsByDocId(@Param("docId") String docId);
}
