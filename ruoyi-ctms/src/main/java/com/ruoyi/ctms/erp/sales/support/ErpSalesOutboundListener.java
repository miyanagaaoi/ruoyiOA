package com.ruoyi.ctms.erp.sales.support;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem;
import com.ruoyi.ctms.erp.posting.service.IErpStockOutService;
import com.ruoyi.ctms.erp.posting.service.IErpStockPostingListener;
import com.ruoyi.ctms.erp.posting.support.ErpPostedLine;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderItemMapper;
import com.ruoyi.ctms.erp.sales.service.IErpSalesOrderService;

/**
 * <p> <b>销售订单"已出库量"的过账回写监听器</b>（2.0 B4 任务 4.3；跨组接口由 T3 提供）。 </p>
 *
 * <p> <b>为什么是监听器</b>：过账引擎属于库存域，它不认识 {@code t_ctms_sales_order_item}
 * （跨包直接写别人的表 = 边界破坏）。T3 于是把"发生过账/红冲"这一事实做成扩展点
 * {@link IErpStockPostingListener}，由销售线在自己的包里回写自己的表。
 * 回调在<b>同一事务内</b>执行：回写失败 → 整个过账回滚，不会出现
 * "出了库但订单没记已出库量"的中间态。 </p>
 *
 * <p> <b>这两条路径的定位方式不同（本类最容易写错的地方，与采购线的
 * {@code ErpPurchaseReceiptListener} 同款）</b>： </p>
 * <ul>
 *   <li> <b>过账 {@code afterPosted}</b>：{@link ErpPostedLine#getSrcItemId()} 有值
 *        （过账行由出库单行直接构造，行上有 {@code src_item_id} = 销售订单行项ID），
 *        因此直接按它定位订单行； </li>
 *   <li> <b>红冲 {@code afterReversed}</b>：<b>红冲行由历史流水反推</b>，而
 *        {@code t_ctms_stock_ledger} 表上没有 {@code src_item_id} 列，T3 构造红冲行时
 *        {@code srcItemId} 为 null（刻意）。所以红冲路径改为读<b>出库单自己的行项</b>
 *        （{@code t_ctms_stock_out_item.src_item_id} 有值）来定位订单行。 </li>
 * </ul>
 *
 * <p> <b>方向说明</b>：出库单过账时 {@code qtyChange} 是<b>负数</b>（扣减结存），
 * 而"已出库量"是<b>正数累计</b>，因此过账路径取 {@code abs()}；
 * 红冲路径按出库单行数量取负回退，落库用 {@code GREATEST(0, shipped_qty - qty)}
 * 保证<b>不小于 0</b>。 </p>
 *
 * <p> <b>幂等与边界</b>： </p>
 * <ol>
 *   <li> 手工建的出库单（行上没有 {@code src_item_id}）<b>跳过</b>，不报错； </li>
 *   <li> 找不到对应的销售订单行（历史数据被清理）<b>跳过</b>，不让历史数据把过账整体拖挂； </li>
 *   <li> 引擎在"没有可冲销的流水"时（重复反审核）<b>不会</b>回调监听器，因此不会重复回退； </li>
 *   <li> 调拨/盘点产生的出库行走的是 {@code STOCK_OUT} 类型但行上没有来源订单行，同样被跳过。 </li>
 * </ol>
 *
 * @author 二开
 */
@Component
public class ErpSalesOutboundListener implements IErpStockPostingListener
{
    /** 销售订单服务（已出库量的唯一写入口）。 */
    @Autowired
    private IErpSalesOrderService orderService;

    /** 销售订单行项 Mapper（按行项ID反查所属订单；只读）。 */
    @Autowired
    private ErpSalesOrderItemMapper orderItemMapper;

    /** 出库单服务（红冲路径读出库单行项定位来源订单行；只消费 T3 的接口）。 */
    @Autowired(required = false)
    private IErpStockOutService stockOutService;

    /**
     * 只关心出库单：入库单/调拨/盘点的过账与销售线的"已出库量"无关。
     *
     * @param docType 单据类型
     * @return 出库单返回 true
     */
    @Override
    public boolean supports(ErpDocType docType)
    {
        return docType == ErpDocType.STOCK_OUT;
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
            // 出库方向为负：已出库量要按绝对量累加
            apply(line.getSrcItemId(), abs(line.getQtyChange()));
        }
    }

    @Override
    public void afterReversed(ErpDocType docType, String docId, List<ErpPostedLine> lines)
    {
        if (lines == null || lines.isEmpty())
        {
            return;
        }
        // 红冲行没有 srcItemId（见类注释）：改从出库单行项读回来源标识，
        // 按行项数量回退。出库单不支持部分红冲（T3 的 reverse 冲全部未冲销流水），
        // 因此"出库单行 ↔ 销售订单行"仍是 1:1，回退量 = 该行数量。
        if (stockOutService == null)
        {
            return;
        }
        ErpStockOut stockOut = stockOutService.selectStockOutDetail(docId);
        List<ErpStockOutItem> items = stockOut == null ? null : stockOut.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }
        for (ErpStockOutItem item : items)
        {
            if (item == null || ErpSalRules.isBlank(item.getSrcItemId()) || item.getQty() == null)
            {
                continue;
            }
            // 传负数：落库用 GREATEST(0, shipped_qty - qty) 保证回退后不为负
            apply(item.getSrcItemId(), item.getQty().negate());
        }
    }

    /**
     * 按来源行项ID回写销售订单行的已出库量（找不到就跳过：历史/手工数据不应整笔拖挂）。
     *
     * @param srcItemId 销售订单行项ID（出库单行上的 {@code src_item_id}）
     * @param deltaQty  变动量（正 = 过账累加，负 = 红冲回退）
     */
    protected void apply(String srcItemId, BigDecimal deltaQty)
    {
        if (ErpSalRules.isBlank(srcItemId) || deltaQty == null || deltaQty.signum() == 0)
        {
            return;
        }
        ErpSalesOrderItem orderItem = orderItemMapper.selectItemById(srcItemId);
        if (orderItem == null || ErpSalRules.isBlank(orderItem.getDocId()))
        {
            return;
        }
        orderService.applyShippedQtyChange(orderItem.getDocId(), orderItem.getId(), deltaQty);
    }

    private BigDecimal abs(BigDecimal value)
    {
        return value == null ? null : value.abs();
    }
}
