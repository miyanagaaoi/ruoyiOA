package com.ruoyi.ctms.erp.stockops.domain;

import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>调拨单行项</b>（表 {@code t_ctms_transfer_item}，2.0 B4 任务 6.1）。 </p>
 *
 * <p> 参考侧该表<b>没有特有列</b>（字段全部来自 {@code DocItemMixin}），因此本类只把公共行项字段
 * 继承下来给出具体类型，供 MyBatis resultMap 与 {@code List<ErpTransferItem>} 装配。 </p>
 *
 * <p> <b>三条调拨专有口径</b>（写在服务层，这里只声明类型）： </p>
 * <ul>
 *   <li> 行项<b>不使用行级仓库</b>（{@code warehouse_id} 恒为 null）：仓库由表头的
 *        调出仓 / 调入仓决定，行项再填仓库会造出"以哪个仓为准"的第二套语义； </li>
 *   <li> 单价与金额恒为 0（调拨是仓库间搬运，不产生金额；流水单价也写 0）； </li>
 *   <li> {@code qty} 必须大于 0，且每行会在过账时展开成<b>两条流水</b>
 *        （调出仓 {@code -qty} + 调入仓 {@code +qty}，同一单号）。 </li>
 * </ul>
 *
 * @author 二开
 */
public class ErpTransferItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;
}
