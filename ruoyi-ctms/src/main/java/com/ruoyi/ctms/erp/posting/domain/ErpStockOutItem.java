package com.ruoyi.ctms.erp.posting.domain;

import com.ruoyi.ctms.erp.base.domain.ErpDocItem;

/**
 * <p> <b>出库单行项</b>（表 {@code t_ctms_stock_out_item}）。 </p>
 *
 * <p> 与入库单行项对称：没有特有列，只有继承来的公共行项字段。
 * 过账时数量按 {@code sign = -1} 扣减结存，并受"是否允许负库存"参数约束
 * （{@code ErpStockParams.KEY_ALLOW_NEGATIVE_STOCK}，默认关闭）。 </p>
 *
 * @author 二开
 */
public class ErpStockOutItem extends ErpDocItem
{
    private static final long serialVersionUID = 1L;
}
