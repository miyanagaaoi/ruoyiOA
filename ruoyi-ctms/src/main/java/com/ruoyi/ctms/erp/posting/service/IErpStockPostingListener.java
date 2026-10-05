package com.ruoyi.ctms.erp.posting.service;

import java.util.List;

import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.support.ErpPostedLine;

/**
 * <p> <b>过账/红冲的扩展点</b>（2.0 B4；给采购线与销售线的"过账时回写累计量"用）。 </p>
 *
 * <p> <b>为什么用回调而不是让 posting 包直接改采购/销售的表</b>：tasks.md §3.5/§4.3 要求
 * "采购单 → 入库单<b>过账时</b>累加已入库量、红冲时回退"，但 posting 包不认识
 * {@code t_ctms_purchase_order_item} 这类表（那会变成跨包直接写别人的表）。
 * 于是 posting 只声明"发生过账/红冲"这一事实（单据类型 + 单据ID + 行的物料/仓库/
 * 变动量 + 来源行项ID），由关心它的组在自己的包里实现监听器。 </p>
 *
 * <p> <b>事务语义</b>：监听器在<b>同一事务内</b>被调用（design D2）；
 * 监听器抛异常 → 整个过账回滚。这是刻意的：回写失败意味着"入库过了账但采购单没记已入库量"，
 * 这种中间态比"整单重试"危险得多。 </p>
 *
 * <p> <b>顺序</b>：Spring 注入的 {@code List<IErpStockPostingListener>} 顺序即调用顺序；
 * 多个监听器之间不得有隐式依赖（它们各自独立回写自己的单据）。 </p>
 *
 * @author 二开
 */
public interface IErpStockPostingListener
{
    /**
     * 是否关心该单据类型（false 时不会被调用，避免无关实现被拉进来）。
     *
     * @param docType 单据类型
     * @return 关心返回 true
     */
    boolean supports(ErpDocType docType);

    /**
     * 过账成功后回调（同一事务）。
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @param lines   已落库的行结果（{@code qtyChange} 为本次写入值，{@code srcItemId} 为来源行项）
     */
    void afterPosted(ErpDocType docType, String docId, List<ErpPostedLine> lines);

    /**
     * 红冲成功后回调（同一事务）。
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @param lines   已落库的红冲行（{@code originalQtyChange} 为被冲销的原变动，
     *                调用方据此把累计量回退，且回退后不得小于 0）
     */
    void afterReversed(ErpDocType docType, String docId, List<ErpPostedLine> lines);
}
