package com.ruoyi.ctms.erp.base;

/**
 * <p> 8 类单据的类型枚举（2.0 B4；表名与单据码已冻结）。 </p>
 *
 * <p> <b>为什么要有这个枚举</b>：文档码 / 标签 / 表名 / 行项表名 / 是否库存类这几件事，
 * 在公共层（状态机）、下推服务、过账服务、附件对象校验、库存账查询里都要用；
 * 写散等于给"8 类单据"造 8 份常量。这里给一份，其余地方引用它。 </p>
 *
 * <p> <b>冻结来源</b> </p>
 * <ul>
 *   <li> {@code code}：与附件对象类型（{@code CtmsAttachmentObjectTypes}）**逐字相同**，
 *        这样"给单据挂附件"不需要再做一次码表映射（盘点/调拨的差异见下面两个常量注释）； </li>
 *   <li> {@code tableName} / {@code itemTableName}：冻结在
 *        {@code notes/00-team-brief.md} §1.1 与 {@code sql/初始化-全部.sql} 的自检段； </li>
 *   <li> {@code stockDoc}：库存类 = 入库 / 出库 / 盘点 / 调拨（移植清单 §3.8：
 *        它们的 {@code approved} 即终态，不注册「置为已完成」）。 </li>
 * </ul>
 *
 * <p> <b>刻意不含权限点前缀</b>：权限点（{@code pur:*} / {@code sal:*} / {@code stk:*}）
 * 的真源是 {@code sys_menu} 与 {@code @PreAuthorize}（tasks.md §8.1、AC-80），
 * 在这里再写一份会与菜单脚本漂移。 </p>
 *
 * @author 二开
 */
public enum ErpDocType
{
    /** 采购申请单（链路源头，下推采购单）。 */
    PURCHASE_REQUEST("purchase_request", "采购申请单",
            "t_ctms_purchase_request", "t_ctms_purchase_request_item", false),

    /** 采购单（采购申请的下一环，下推入库单）。 */
    PURCHASE_ORDER("purchase_order", "采购单",
            "t_ctms_purchase_order", "t_ctms_purchase_order_item", false),

    /** 销售申请单。 */
    SALES_REQUEST("sales_request", "销售申请单",
            "t_ctms_sales_request", "t_ctms_sales_request_item", false),

    /** 销售订单。 */
    SALES_ORDER("sales_order", "销售订单",
            "t_ctms_sales_order", "t_ctms_sales_order_item", false),

    /** 入库单（审核即过账；direction=+1）。 */
    STOCK_IN("stock_in", "入库单",
            "t_ctms_stock_in", "t_ctms_stock_in_item", true),

    /** 出库单（审核即过账；direction=-1）。 */
    STOCK_OUT("stock_out", "出库单",
            "t_ctms_stock_out", "t_ctms_stock_out_item", true),

    /**
     * 盘点单（审核自动生成盘盈入库单 / 盘亏出库单）。
     *
     * <p> 附件对象类型码同样是 {@code stock_take}（B4 任务 6.6 的口径）。 </p>
     */
    STOCK_TAKE("stock_take", "盘点单",
            "t_ctms_stocktake", "t_ctms_stocktake_item", true),

    /** 调拨单（两阶段过账；一单两条流水）。 */
    STOCK_TRANSFER("stock_transfer", "调拨单",
            "t_ctms_transfer", "t_ctms_transfer_item", true);

    private final String code;

    private final String label;

    private final String tableName;

    private final String itemTableName;

    private final boolean stockDoc;

    ErpDocType(String code, String label, String tableName, String itemTableName, boolean stockDoc)
    {
        this.code = code;
        this.label = label;
        this.tableName = tableName;
        this.itemTableName = itemTableName;
        this.stockDoc = stockDoc;
    }

    /**
     * 单据码（落 {@code doc_type} / {@code source_doc_type} / {@code stock_ledger.doc_type}
     * 与附件 {@code object_type}）。
     *
     * @return 单据码
     */
    public String getCode()
    {
        return code;
    }

    /**
     * 单据中文名（错误文案与状态标签用）。
     *
     * @return 中文名
     */
    public String getLabel()
    {
        return label;
    }

    /**
     * 表头表名（供公共 Mapper 的"按单据类型查表"用；取值来自冻结件，不含用户输入）。
     *
     * @return 表名
     */
    public String getTableName()
    {
        return tableName;
    }

    /**
     * 行项表名。
     *
     * @return 行项表名
     */
    public String getItemTableName()
    {
        return itemTableName;
    }

    /**
     * 是否库存类单据（入库/出库/盘点/调拨）。
     *
     * @return 库存类返回 true
     */
    public boolean isStockDoc()
    {
        return stockDoc;
    }

    /**
     * 是否提供「置为已完成」动作（库存类**不提供**，见 design D4 的风险条目）。
     *
     * @return 提供返回 true
     */
    public boolean supportsCompleteAction()
    {
        return !stockDoc;
    }

    /**
     * 按单据码取枚举（大小写不敏感、去首尾空白）。
     *
     * @param code 单据码
     * @return 枚举；未匹配返回 {@code null}（调用方决定报错文案）
     */
    public static ErpDocType ofCode(String code)
    {
        if (code == null)
        {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (ErpDocType type : values())
        {
            if (type.code.equals(normalized))
            {
                return type;
            }
        }
        return null;
    }
}
