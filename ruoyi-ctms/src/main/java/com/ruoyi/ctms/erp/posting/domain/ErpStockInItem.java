package com.ruoyi.ctms.erp.posting.domain;

import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>入库单行项</b>（表 {@code t_ctms_stock_in_item}）。 </p>
 *
 * <p> 参考侧该表<b>没有特有列</b>（{@code models_doc.py} 的字段全部来自 {@code DocItemMixin}），
 * 因此本类只是把公共行项字段继承下来、给出具体类型，便于 MyBatis 的 resultMap 与
 * 泛型 {@code List<ErpStockInItem>} 装配（design D1 的"每表独立实体"）。 </p>
 *
 * <p> 过账时数量按 {@code sign = +1} 累加结存（对比 {@link ErpStockOutItem} 的 {@code -1}）。 </p>
 *
 * @author 二开
 */
public class ErpStockInItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;
}
