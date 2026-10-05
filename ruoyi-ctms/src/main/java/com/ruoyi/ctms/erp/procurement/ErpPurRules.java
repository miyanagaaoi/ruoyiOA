package com.ruoyi.ctms.erp.procurement;

import java.math.BigDecimal;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem;
import com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem;

/**
 * <p> <b>采购线纯规则与提示文案的唯一实现点</b>（2.0 B4 任务 3.1~3.4、3.6；规格
 * {@code specs/erp/procurement/spec.md}）。 </p>
 *
 * <p> <b>为什么单独一个类</b>：任务书要求"3.1~3.6 每条断言都写成内存桩单测"，
 * 而提示文案是<b>接口契约</b>（前端与 {@code tools/erp-*-check.ps1} 按同一批字符串断言）。
 * 把判定与文案收在这里，服务层只负责取数与落库，单测可以直接表驱动。</p>
 *
 * <p> <b>不在本类里做的事</b>：状态机判定（{@code ErpDocStateMachine}）、金额与数量精度
 * （{@code ErpAmounts}）、物料/单位/仓库的主数据守卫（{@code ErpMasterGuards}）、
 * 数据范围（{@code ErpDocScope}）、单号（{@code ruoyi-serial}）。
 * 这里只承载"采购线特有"的口径：剩余量与归零、单据方向、供应商与客户互斥、以及各条中文提示。 </p>
 *
 * <p> <b>方向口径（任务 3.2 / 3.6）</b>：合同方向只判<b>已落库的引用列</b>
 * （{@code contract.supplier_id} / {@code contract.customer_id}），不去猜合同类型字典 ——
 * 因为类型字典是可增删的自定义项（B3 的 {@code contract_types} 由用户维护）。 </p>
 *
 * @author 二开
 */
public final class ErpPurRules
{
    /* ==================== 方向 ==================== */

    /** 采购方向（合同乙方为供应商；单据引用供应商）。 */
    public static final String DIRECTION_PURCHASE = "purchase";

    /** 销售方向（合同甲方为客户；单据引用客户）。 */
    public static final String DIRECTION_SALES = "sales";

    /* ==================== 单号前缀（真源在 base 的 ErpDocNoGenerator，此处不重复声明） ==================== */

    /*
     * 采购申请单 = PR、采购单 = PO：这两个前缀由 base 的
     * com.ruoyi.ctms.erp.base.ErpDocNoGenerator.prefixOf(ErpDocType) 提供，并与
     * sql/二开-进销存.sql ③ 段的 8 条 t_code_config 逐字一致。
     * 本类**刻意不再写一份常量**：真源有两份就一定会漂移（简报 §9 的冻结口径）。
     */

    /* ==================== 标志位 ==================== */

    /** 启用 / 是。 */
    public static final String FLAG_YES = "1";

    /** 停用 / 否。 */
    public static final String FLAG_NO = "0";

    /* ==================== 变更历史 ==================== */

    /** 变更历史来源：系统自动（"归零即完成"用）。 */
    public static final String LOG_SOURCE_AUTO = "auto";

    /** 变更历史来源：手工。 */
    public static final String LOG_SOURCE_MANUAL = "manual";

    /** 变更历史字段名：状态。 */
    public static final String LOG_FIELD_STATUS = "status";

    /** 归零即完成的留痕备注（任务 3.4；前端变更历史时间线直接展示这句）。 */
    public static final String LOG_NOTE_AUTO_COMPLETE = "全部行项剩余可下推量为 0，自动置为已完成";

    /* ==================== 提示文案（接口契约，逐字一致） ==================== */

    /** 单据不存在。 */
    public static final String MSG_NOT_FOUND = "单据不存在";

    /** 行项为空。 */
    public static final String MSG_NO_ITEMS = "单据至少需要一行行项";

    /** 数量必须大于 0。 */
    public static final String MSG_QTY_POSITIVE = "数量必须大于 0";

    /** 单价不得为负。 */
    public static final String MSG_PRICE_NEGATIVE = "单价不得为负数";

    /** 供应商档案不存在（传客户 id 也落到这句：规格场景「采购单只能引供应商」断言的就是它）。 */
    public static final String MSG_SUPPLIER_NOT_FOUND = "供应商档案不存在";

    /** 供应商已停用。 */
    public static final String MSG_SUPPLIER_DISABLED = "供应商已停用，不能用于新单据";

    /** 供应商必填。 */
    public static final String MSG_SUPPLIER_REQUIRED = "采购单必须选择供应商";

    /** 关联合同不存在或方向不符。 */
    public static final String MSG_CONTRACT_DIRECTION = "关联合同不存在或方向不符";

    /** 关联合同已停用。 */
    public static final String MSG_CONTRACT_DISABLED = "关联合同已停用";

    /** 下游存在时禁止反审核（规格「下游存在时禁止反审核」）。 */
    public static final String MSG_DOWNSTREAM_BLOCK = "已存在下游采购单，请先处理下游单据（反审核或作废）后再操作";

    /** 仅已审核可下推。 */
    public static final String MSG_NOT_APPROVED_PUSH = "仅已审核的采购申请单可以下推采购单";

    /** 下推数量必须大于 0。 */
    public static final String MSG_PUSH_QTY_POSITIVE = "下推数量必须大于 0";

    /** 该行已无剩余可下推量（与销售线 {@code ErpSalRules} 的文案逐字一致）。 */
    public static final String MSG_NO_REMAINING = "已无可下推数量（剩余 0）";

    /** 下推行项不属于来源单据。 */
    public static final String MSG_PUSH_ITEM_NOT_FOUND = "下推行项不属于该采购申请单";

    /** 下推成功提示（template；任务 3.3/3.4 的前端提示文案）。 */
    public static final String MSG_PUSH_OK_TEMPLATE = "已生成采购单 %s（草稿），本次合计下推 %s";

    /** 下推并触发"归零即完成"的提示（任务 3.4）。 */
    public static final String MSG_PUSH_AUTO_COMPLETE_TEMPLATE =
            "已生成采购单 %s（草稿），本次合计下推 %s；申请单已全部下推完毕，自动置为已完成";

    /** 操作人为空时的兜底登录名（与 base 的 {@code ErpDocScope.currentUsername()} 同款）。 */
    public static final String DEFAULT_USERNAME = "system";

    /** 操作人为空时的兜底用户ID。 */
    public static final String DEFAULT_USER_ID = "1";

    /* ==================== 采购单 → 入库单（任务 3.5） ==================== */

    /** 仅已审核或已完成的采购单可推入库单（规格「采购单→入库单下推」的前置）。 */
    public static final String MSG_ORDER_NOT_PUSHABLE_IN = "仅已审核或已完成的采购单可以下推入库单";

    /** 该行已无可入库数量。 */
    public static final String MSG_IN_NO_REMAINING = "已无可入库数量（剩余 0）";

    /** 下推入库数量必须大于 0（与申请→采购单同一句文案）。 */
    public static final String MSG_IN_QTY_POSITIVE = "下推数量必须大于 0";

    /** 下推行项不属于该采购单。 */
    public static final String MSG_IN_ITEM_NOT_FOUND = "下推行项不属于该采购单";

    /** 下推入库成功提示（template）。 */
    public static final String MSG_PUSH_IN_OK_TEMPLATE = "已生成入库单 %s（草稿），本次合计下推 %s";

    /** 入库单默认入库类型（流水 biz_type 的真源；与 DDL 默认值一致）。 */
    public static final String DEFAULT_IN_TYPE = "采购入库";

    /** 未指定默认收货仓库时的提示（下推时必须能解析出仓库，理由见 notes 04b 的 DDL 约束）。 */
    public static final String MSG_RECEIPT_WAREHOUSE_REQUIRED =
            "采购单未指定默认收货仓库：请先补充采购单的收货仓库，或在下推时指定仓库";

    private ErpPurRules()
    {
    }

    /* ==================== 剩余可入库量（任务 3.5） ==================== */

    /**
     * 剩余可入库量 = 数量 − 已入库量（口径与"剩余可下推量"完全同构，只是列不同）。
     *
     * @param qty         采购单行数量
     * @param receivedQty 已入库量
     * @return 3 位小数的剩余量（≥ 0）
     */
    public static BigDecimal remainingReceiveQty(BigDecimal qty, BigDecimal receivedQty)
    {
        return remainingQty(qty, receivedQty);
    }

    /**
     * 取采购单行上的剩余可入库量。
     *
     * @param item 采购单行
     * @return 剩余量
     */
    public static BigDecimal remainingReceiveQtyOf(ErpPurchaseOrderItem item)
    {
        return item == null ? BigDecimal.ZERO : remainingQty(item.getQty(), item.getReceivedQty());
    }

    /**
     * 采购单行集合的剩余可入库量合计（列表 {@code remainQtySum} 与"可推入库"标志的依据）。
     *
     * @param items 行项集合（可空）
     * @return 3 位小数的合计
     */
    public static BigDecimal remainingReceiveSum(List<ErpPurchaseOrderItem> items)
    {
        BigDecimal total = BigDecimal.ZERO;
        if (items != null)
        {
            for (ErpPurchaseOrderItem item : items)
            {
                total = total.add(remainingReceiveQtyOf(item));
            }
        }
        return ErpAmounts.roundQty(total);
    }

    /**
     * 全部行项是否都已入库完毕（剩余可入库量均为 0）。
     *
     * <p> 空集合不算"已入库完毕"（没有行项的采购单连提交都不合法）。 </p>
     *
     * @param items 行项集合
     * @return 全部剩余量为 0 返回 true
     */
    public static boolean isFullyReceived(List<ErpPurchaseOrderItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return false;
        }
        for (ErpPurchaseOrderItem item : items)
        {
            if (remainingReceiveQtyOf(item).signum() > 0)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 下推入库数量的解析（口径与申请→采购单逐条对齐）：
     * 剩余量 ≤ 0 → 拒绝；{@code requestedQty} 为空或 ≤ 0 → 按剩余量全推；否则按请求量推。
     *
     * @param requestedQty 本次请求数量（可空）
     * @param remainingQty 该行剩余可入库量
     * @return 实际下推数量
     * @throws ServiceException 该行已无剩余量
     */
    public static BigDecimal resolveReceiveQty(BigDecimal requestedQty, BigDecimal remainingQty)
    {
        BigDecimal remaining = remainingQty == null ? BigDecimal.ZERO : remainingQty;
        if (remaining.signum() <= 0)
        {
            throw new ServiceException(MSG_IN_NO_REMAINING);
        }
        if (requestedQty == null || requestedQty.signum() <= 0)
        {
            return ErpAmounts.roundQty(remaining);
        }
        return ErpAmounts.roundQty(requestedQty);
    }

    /**
     * 超量下推入库的拦截（提示带剩余量与本次量）。
     *
     * @param remaining 剩余可入库量
     * @param pushQty   本次下推量
     * @param label     单据标签（"采购单"）
     * @param product   物料名称
     * @throws ServiceException 本次数量 ≤ 0 或大于剩余量
     */
    public static void checkReceiveQty(BigDecimal remaining, BigDecimal pushQty, String label, String product)
    {
        BigDecimal actual = pushQty == null ? BigDecimal.ZERO : pushQty;
        if (actual.signum() <= 0)
        {
            throw new ServiceException(MSG_IN_QTY_POSITIVE);
        }
        BigDecimal left = remaining == null ? BigDecimal.ZERO : remaining;
        if (actual.compareTo(left) > 0)
        {
            throw new ServiceException(label + "「" + (product == null ? "" : product)
                    + "」可入库数量不足（剩余 " + ErpAmounts.textOf(left) + "，本次 "
                    + ErpAmounts.textOf(actual) + "）");
        }
    }

    /* ==================== 剩余量与归零（任务 3.3 / 3.4） ==================== */

    /**
     * 剩余可下推量 = 数量 − 已下单量。
     *
     * <p> {@code null} 一律视为 0；结果<b>不小于 0</b>（历史脏数据里可能出现
     * {@code ordered_qty > qty}，直接做差会让前端显示"可推 -5"这种没意义的数字）。 </p>
     *
     * @param qty        行项数量
     * @param orderedQty 已下单量
     * @return 3 位小数的剩余量（≥ 0）
     */
    public static BigDecimal remainingQty(BigDecimal qty, BigDecimal orderedQty)
    {
        BigDecimal total = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal used = orderedQty == null ? BigDecimal.ZERO : orderedQty;
        BigDecimal remaining = total.subtract(used);
        if (remaining.signum() < 0)
        {
            remaining = BigDecimal.ZERO;
        }
        return ErpAmounts.roundQty(remaining);
    }

    /**
     * 取行项上的剩余可下推量。
     *
     * @param item 行项
     * @return 剩余量
     */
    public static BigDecimal remainingQtyOf(ErpPurchaseRequestItem item)
    {
        return item == null ? BigDecimal.ZERO : remainingQty(item.getQty(), item.getOrderedQty());
    }

    /**
     * 行项集合的剩余可下推量合计（列表字段 {@code remainQtySum} 与下推按钮显隐的依据）。
     *
     * @param items 行项集合（可空）
     * @return 3 位小数的合计
     */
    public static BigDecimal remainingSum(List<ErpPurchaseRequestItem> items)
    {
        BigDecimal total = BigDecimal.ZERO;
        if (items != null)
        {
            for (ErpPurchaseRequestItem item : items)
            {
                total = total.add(remainingQtyOf(item));
            }
        }
        return ErpAmounts.roundQty(total);
    }

    /**
     * <p> <b>归零判定</b>（任务 3.4）：全部行项的剩余可下推量均为 0。 </p>
     *
     * <p> 空集合<b>不算归零</b>：没有行项的申请单连提交都不合法，更不该被自动"完成"而不再可编辑。 </p>
     *
     * @param items 行项集合
     * @return 全部行项剩余量 ≤ 0 返回 true
     */
    public static boolean isFullyOrdered(List<ErpPurchaseRequestItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return false;
        }
        for (ErpPurchaseRequestItem item : items)
        {
            if (remainingQtyOf(item).signum() > 0)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 下推数量的解析：{@code requestedQty} 为空或 ≤ 0 时按剩余量全推（任务 3.3）。
     *
     * <p> <b>与销售线 {@code ErpSalRules.resolvePushQty} 逐条对齐</b>（两条线的下推口径必须同构，
     * 否则同一份前端代码在两条线上行为不同）： </p>
     * <ol>
     *   <li> 剩余量 ≤ 0 → 抛 {@link #MSG_NO_REMAINING}（已归零的行不能再推——
     *        否则"再推一次"会生成一张 0 数量的草稿单）；</li>
     *   <li> {@code requestedQty} 为空或 ≤ 0 → 按剩余量全推；</li>
     *   <li> 否则按请求量推（是否超量由 {@link #checkPushQty} 判定）。</li>
     * </ol>
     *
     * @param requestedQty 本次请求数量（可为 null）
     * @param remainingQty 该行剩余可下推量
     * @return 实际下推数量（3 位小数）
     * @throws ServiceException 该行已无剩余量
     */
    public static BigDecimal resolvePushQty(BigDecimal requestedQty, BigDecimal remainingQty)
    {
        BigDecimal remaining = remainingQty == null ? BigDecimal.ZERO : remainingQty;
        if (remaining.signum() <= 0)
        {
            throw new ServiceException(MSG_NO_REMAINING);
        }
        if (requestedQty == null || requestedQty.signum() <= 0)
        {
            return ErpAmounts.roundQty(remaining);
        }
        return ErpAmounts.roundQty(requestedQty);
    }

    /**
     * 下推数量的解析（取行项上的剩余量；口径见
     * {@link #resolvePushQty(BigDecimal, BigDecimal)}）。
     *
     * @param requestedQty 本次请求数量（可为 null）
     * @param item         来源行项
     * @return 实际下推数量
     */
    public static BigDecimal resolvePushQty(BigDecimal requestedQty, ErpPurchaseRequestItem item)
    {
        return resolvePushQty(requestedQty, remainingQtyOf(item));
    }

    /**
     * 超量下推的拦截（规格场景「超量下推被拒」）。
     *
     * @param remaining 剩余可下推量
     * @param pushQty   本次下推量
     * @param label     单据标签（错误文案用，如"采购申请单"）
     * @param product   物料名称（错误文案用）
     * @throws ServiceException 本次数量 ≤ 0 或大于剩余量
     */
    public static void checkPushQty(BigDecimal remaining, BigDecimal pushQty, String label, String product)
    {
        if (pushQty == null || pushQty.signum() <= 0)
        {
            throw new ServiceException(MSG_PUSH_QTY_POSITIVE);
        }
        if (pushQty.compareTo(remaining == null ? BigDecimal.ZERO : remaining) > 0)
        {
            throw new ServiceException(label + "「" + (product == null ? "" : product) + "」可下推数量不足（剩余 "
                    + ErpAmounts.textOf(remaining) + "，本次 " + ErpAmounts.textOf(pushQty) + "）");
        }
    }

    /* ==================== 行项校验（任务 3.1） ==================== */

    /**
     * 行项集合校验：至少一行、数量 &gt; 0、单价非负、数量精度按单位小数位（逐行带行号）。
     *
     * <p> ⚠ 精度校验必须在"行项快照装配之后、数量被 scale 归整之前"执行，否则
     * {@code 1.5000}（单位 0 位小数）会被 {@code setScale(3)} 洗成 {@code 1.500} 再判定通过。
     * 因此本方法接收的是<b>已写入单位小数位快照、但数量仍是入参原值</b>的行项。 </p>
     *
     * @param items 行项集合
     * @throws ServiceException 校验不通过
     */
    public static void checkItemsBasic(List<? extends ErpDocItem> items)
    {
        if (items == null || items.isEmpty())
        {
            throw new ServiceException(MSG_NO_ITEMS);
        }
        int rowNo = 0;
        for (ErpDocItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException("行 " + rowNo + "：" + MSG_NO_ITEMS);
            }
            if (item.getQty() == null || item.getQty().signum() <= 0)
            {
                throw new ServiceException(msgQtyPositive(rowNo));
            }
            if (item.getUnitPrice() != null && item.getUnitPrice().signum() < 0)
            {
                throw new ServiceException(msgPriceNegative(rowNo));
            }
            // 单位 0 位小数时填 1.5 必须被拒（规格场景「单位小数位为零时拒绝小数数量」）
            ErpAmounts.checkQuantityScale(item.getQty(), item.getUomDecimals(), rowNo);
        }
    }

    /**
     * 单据金额合计（"先舍入再汇总"的唯一实现点是 {@code ErpAmounts.totalOf}）。
     *
     * @param items 行项集合
     * @return 2 位合计
     */
    public static BigDecimal totalAmountOf(List<? extends ErpDocItem> items)
    {
        return ErpAmounts.totalOf(items);
    }

    /* ==================== 往来单位（任务 3.2） ==================== */

    /**
     * 单据侧方向校验：采购单只能引供应商。
     *
     * @param supplierId    供应商档案ID（空 → 采购单必填校验失败）
     * @param supplierFound 供应商档案是否查到（<b>传客户 id 时这里必然是 false</b>：
     *                      供应商档案按 {@code t_ctms_supplier.id} 命中，客户 id 命不中）
     * @param enabled       档案是否启用
     * @throws ServiceException 缺失 / 不存在 / 已停用
     */
    public static void checkSupplierDirection(String supplierId, boolean supplierFound, boolean enabled)
    {
        if (isBlank(supplierId))
        {
            throw new ServiceException(MSG_SUPPLIER_REQUIRED);
        }
        if (!supplierFound)
        {
            throw new ServiceException(MSG_SUPPLIER_NOT_FOUND);
        }
        if (!enabled)
        {
            throw new ServiceException(MSG_SUPPLIER_DISABLED);
        }
    }

    /* ==================== 合同方向（任务 3.6） ==================== */

    /**
     * 合同方向校验：采购单据只能关联"采购方向"合同。
     *
     * <p> 采购方向 = 合同乙方填了 {@code supplier_id}；销售方向 = 合同甲方填了 {@code customer_id}。
     * 两者都填或都没填时<b>拒绝</b>（无法证明方向，宁可让用户先补合同档案，
     * 也不要让销售合同混进采购单）。 </p>
     *
     * @param supplierId   合同上的供应商引用（乙方）
     * @param customerId   合同上的客户引用（甲方）
     * @param docDirection 单据方向（{@link #DIRECTION_PURCHASE} / {@link #DIRECTION_SALES}）
     * @throws ServiceException 方向不符
     */
    public static void checkContractDirection(String supplierId, String customerId, String docDirection)
    {
        boolean matches;
        if (DIRECTION_SALES.equals(docDirection))
        {
            matches = !isBlank(customerId) && isBlank(supplierId);
        }
        else
        {
            matches = !isBlank(supplierId) && isBlank(customerId);
        }
        if (!matches)
        {
            throw new ServiceException(MSG_CONTRACT_DIRECTION);
        }
    }

    /**
     * 合同存在且未停用校验（规格：关联时必须校验合同存在且未停用）。
     *
     * @param found   合同是否查到（查询刻意放开 {@code includeDeleted}，因此这里能看到已停用行）
     * @param delFlag 软删除标志（{@code '1'} = 已停用）
     * @throws ServiceException 不存在或已停用
     */
    public static void checkContractUsable(boolean found, String delFlag)
    {
        if (!found)
        {
            throw new ServiceException(MSG_CONTRACT_DIRECTION);
        }
        if (FLAG_YES.equals(delFlag))
        {
            throw new ServiceException(MSG_CONTRACT_DISABLED);
        }
    }

    /* ==================== 文案工厂 ==================== */

    /**
     * 数量校验文案（带行号）。
     *
     * @param rowNo 行号（从 1 开始）
     * @return 文案
     */
    public static String msgQtyPositive(int rowNo)
    {
        return rowNo > 0 ? ("行 " + rowNo + "：" + MSG_QTY_POSITIVE) : MSG_QTY_POSITIVE;
    }

    /**
     * 单价校验文案（带行号）。
     *
     * @param rowNo 行号
     * @return 文案
     */
    public static String msgPriceNegative(int rowNo)
    {
        return rowNo > 0 ? ("行 " + rowNo + "：" + MSG_PRICE_NEGATIVE) : MSG_PRICE_NEGATIVE;
    }

    /**
     * 是否空白（null / 全空格）。
     *
     * @param value 文本
     * @return 空白返回 true
     */
    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 去首尾空格，空白转 {@code null}。
     *
     * @param value 文本
     * @return 归一后的文本
     */
    public static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 标志位回落：入参非空用入参，否则用兜底值，再否则 {@code "0"}。
     *
     * @param value    入参
     * @param fallback 兜底值
     * @return 标志位
     */
    public static String flagOr(String value, String fallback)
    {
        String trimmed = trimToNull(value);
        if (trimmed != null)
        {
            return trimmed;
        }
        String fallbackTrimmed = trimToNull(fallback);
        return fallbackTrimmed == null ? FLAG_NO : fallbackTrimmed;
    }
}
