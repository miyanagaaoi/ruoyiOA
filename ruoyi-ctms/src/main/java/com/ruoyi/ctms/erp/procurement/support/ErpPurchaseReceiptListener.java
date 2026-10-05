package com.ruoyi.ctms.erp.procurement.support;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.domain.ErpStockIn;
import com.ruoyi.ctms.erp.posting.domain.ErpStockInItem;
import com.ruoyi.ctms.erp.posting.service.IErpStockInService;
import com.ruoyi.ctms.erp.posting.service.IErpStockPostingListener;
import com.ruoyi.ctms.erp.posting.support.ErpPostedLine;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.mapper.ErpPurchaseOrderItemMapper;
import com.ruoyi.ctms.erp.procurement.service.IErpPurchaseOrderService;

/**
 * <p> <b>采购单"已入库量"的过账回写监听器</b>（2.0 B4 任务 3.5；跨组接口由 T3 提供）。 </p>
 *
 * <p> <b>为什么是监听器而不是写在过账服务里</b>：过账引擎属于库存域，它不认识
 * {@code t_ctms_purchase_order_item}（跨包直接写别人的表 = 边界破坏）。
 * T3 于是把"发生过账/红冲"这一事实做成扩展点 {@link IErpStockPostingListener}，
 * 由采购线在自己的包里回写自己的表。回调在<b>同一事务内</b>执行：回写失败 →
 * 整个过账回滚，不会出现"入库过了账但采购单没记已入库量"的中间态。 </p>
 *
 * <p> <b>两条路径的定位方式不同（这是本类最容易写错的地方）</b>： </p>
 * <ul>
 *   <li> <b>过账 {@code afterPosted}</b>：{@link ErpPostedLine#getSrcItemId()} 有值
 *        （T3 的过账行由入库单行直接构造，行上有 {@code src_item_id} = 采购单行项ID），
 *        因此直接按它定位采购单行；</li>
 *   <li> <b>红冲 {@code afterReversed}</b>：<b>红冲行由历史流水反推</b>，而
 *        {@code t_ctms_stock_ledger} 表上<b>没有</b> {@code src_item_id} 列，
 *        T3 的 {@code ErpPostingLine.setSrcItemId(null)} 是刻意的 —— 也就是说
 *        红冲行拿不到来源行标识。所以红冲路径改为读<b>入库单自己的行项</b>
 *        （{@code t_ctms_stock_in_item.src_item_id} 有值）来定位采购单行。</li>
 * </ul>
 *
 * <p> <b>幂等与边界</b>： </p>
 * <ol>
 *   <li> 手工建的入库单（行上没有 {@code src_item_id}）<b>跳过</b>，不报错；</li>
 *   <li> 找不到对应的采购单行（单据被清理）<b>跳过</b>，不让历史数据把过账整体拖挂；</li>
 *   <li> 回退量由 {@link IErpPurchaseOrderService#applyReceivedQtyChange} 用
 *        {@code GREATEST(0, received_qty - qty)} 落库，因此"回退后为负"在数据层就不可能；</li>
 *   <li> 引擎在"没有可冲销的流水"时（重复反审核）<b>不会</b>回调监听器，因此不会重复回退。</li>
 * </ol>
 *
 * @author 二开
 */
@Component
public class ErpPurchaseReceiptListener implements IErpStockPostingListener
{
    /** 采购单服务（已入库量的唯一写入口）。 */
    @Autowired
    private IErpPurchaseOrderService orderService;

    /** 采购单行项 Mapper（按行项ID反查所属采购单；只读）。 */
    @Autowired
    private ErpPurchaseOrderItemMapper orderItemMapper;

    /** 入库单服务（红冲路径读入库单行项定位来源采购行；只消费 T3 的接口）。 */
    @Autowired(required = false)
    private IErpStockInService stockInService;

    /**
     * 只关心入库单：出库单/调拨/盘点的过账与采购线的"已入库量"无关。
     *
     * @param docType 单据类型
     * @return 入库单返回 true
     */
    @Override
    public boolean supports(ErpDocType docType)
    {
        return docType == ErpDocType.STOCK_IN;
    }

    @Override
    public void afterPosted(ErpDocType docType, String docId, List<ErpPostedLine> lines)
    {
        if (lines == null || lines.isEmpty())
        {
            return;
        }
        for (ErpPostedLine line : lines)
        {
            if (line == null)
            {
                continue;
            }
            // 入库方向为正：qtyChange 即本次真实入库量
            apply(line.getSrcItemId(), line.getQtyChange());
        }
    }

    @Override
    public void afterReversed(ErpDocType docType, String docId, List<ErpPostedLine> lines)
    {
        if (lines == null || lines.isEmpty())
        {
            return;
        }
        // 红冲行没有 srcItemId（见类注释）：改从入库单行项读回来源标识，
        // 按行项数量回退。入库单不支持部分红冲（T3 的 reverse 冲全部未冲销流水），
        // 因此"入库单行 ↔ 采购单行"仍是 1:1，回退量 = 该行数量。
        if (stockInService == null)
        {
            return;
        }
        ErpStockIn stockIn = stockInService.selectStockInDetail(docId);
        List<ErpStockInItem> items = stockIn == null ? null : stockIn.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }
        for (ErpStockInItem item : items)
        {
            if (item == null || ErpPurRules.isBlank(item.getSrcItemId()) || item.getQty() == null)
            {
                continue;
            }
            // 传负数：applyReceivedQtyChange 内部用 GREATEST(0, ...) 保证不为负
            apply(item.getSrcItemId(), item.getQty().negate());
        }
    }

    /**
     * 按来源行项ID回写采购单行的已入库量（找不到就跳过：历史/手工数据不应整笔拖挂）。
     *
     * @param srcItemId 采购单行项ID（入库单行上的 {@code src_item_id}）
     * @param deltaQty  变动量（正=过账累加，负=红冲回退）
     */
    protected void apply(String srcItemId, BigDecimal deltaQty)
    {
        if (ErpPurRules.isBlank(srcItemId) || deltaQty == null || deltaQty.signum() == 0)
        {
            return;
        }
        ErpPurchaseOrderItem orderItem = orderItemMapper.selectItemById(srcItemId);
        if (orderItem == null || ErpPurRules.isBlank(orderItem.getDocId()))
        {
            return;
        }
        orderService.applyReceivedQtyChange(orderItem.getDocId(), orderItem.getId(), deltaQty);
    }
}
