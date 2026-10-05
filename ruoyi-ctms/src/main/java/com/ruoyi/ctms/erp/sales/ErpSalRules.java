package com.ruoyi.ctms.erp.sales;

import java.math.BigDecimal;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpAmounts;
import com.ruoyi.ctms.erp.base.ErpDocStatus;
import com.ruoyi.ctms.erp.base.domain.ErpDocItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.base.ErpDocStatus;

/**
 * <p> <b>销售线的纯规则</b>（2.0 B4 任务 4.1 / 4.2 / 4.4；规格 {@code erp/sales}）。 </p>
 *
 * <p> 与 B3 的 {@code PartnerRules} / {@code ContractRules} 同一口径：本类
 * <b>不依赖 Spring 容器、数据库与登录上下文</b>，全部是静态纯函数，因此可以做表驱动单测，
 * 并<b>逐字</b>锁定提示文案（文案是接口契约的一部分，改动即视为破坏性变更）。 </p>
 *
 * <p> <b>这里只放"规则本身"</b>：需要访问存储的编排（查客户档案、查合同、取单号、
 * 累加已下单量）留在 Service 层；主数据守卫（停用物料/单位、快照、数量精度）
 * 由公共层 {@code ErpMasterGuards} 承担，本类不重复实现。</p>
 *
 * <p> <b>三条容易写错、写错不报错的口径</b>（都已被单测锁死）： </p>
 * <ol>
 *   <li> 剩余可下推量 = 行数量 − 已下推量（{@code ordered_qty}），<b>不是</b>"数量本身"； </li>
 *   <li> 超量下推报错文案必须<b>同时给出剩余量与本次量</b>，并且抛出前<b>不</b>产生任何累加； </li>
 *   <li> "归零即完成"看的是<b>全部行项</b>的剩余量为 0，只推完一行不置完成。 </li>
 * </ol>
 *
 * @author 二开
 */
public final class ErpSalRules
{
    /** 启用标志：启用。 */
    public static final String ENABLE_YES = "1";

    /** 启用标志：停用。 */
    public static final String ENABLE_NO = "0";

    /** 软删除标志：已删除/已停用（{@code del_flag='1'}；{@code '0'} 表示**未**删除）。 */
    public static final String DEL_FLAG_DELETED = "1";

    /** 变更历史的字段名：状态（与采购线的 {@code ErpPurRules.LOG_FIELD_STATUS} 同字）。 */
    public static final String LOG_FIELD_STATUS = "status";

    /** 变更历史的字段名：关联合同。 */
    public static final String LOG_FIELD_CONTRACT = "关联合同";

    /** 变更历史来源：手工。 */
    public static final String LOG_SOURCE_MANUAL = "manual";

    /** 变更历史来源：自动（服务端判定）。 */
    public static final String LOG_SOURCE_AUTO = "auto";

    /** "归零即完成"的固定留痕文案（接口验收脚本按字符串断言）。 */
    public static final String NOTE_AUTO_COMPLETE = "全部行项剩余可下推量为 0，自动置为已完成";

    /** 无登录上下文时的兜底用户ID。 */
    public static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的兜底登录名。 */
    public static final String DEFAULT_USERNAME = "system";

    /** 合同类型字典的"销售方向"关键字（{@code sys_dict_data.contract_types} 的标签含此词）。 */
    public static final String CONTRACT_DIRECTION_KEYWORD = "销售";

    /** 合同类型字典值：销售（{@code contract_types} 的 {@code dict_value=SAL}）。 */
    public static final String CONTRACT_TYPE_SALE = "SAL";

    /** 销售出库单的出库类型（流水 {@code biz_type} 的真源；与 T3 的默认值一致）。 */
    public static final String OUT_TYPE_SALE = "销售出库";

    /* ==================== 销售订单 → 出库单（任务 4.3）的冻结文案 ==================== */

    /** 仅"已审核或已完成"的销售订单可下推出库单。 */
    public static final String MSG_ORDER_NOT_PUSHABLE_OUT = "仅已审核或已完成的销售订单可以下推出库单";

    /** 下推时解析不出出库仓库（入参与订单默认发货仓库都为空）。 */
    public static final String MSG_OUT_WAREHOUSE_REQUIRED =
            "销售订单未指定发货仓库，无法下推出库单（请先设置默认发货仓库，或在下推时指定出库仓库）";

    /** 本次下推数量合计必须大于 0。 */
    public static final String MSG_OUT_QTY_POSITIVE = "下推数量必须大于 0";

    /** 剩余可出库量为 0 时还下推。 */
    public static final String MSG_NO_REMAINING_SHIPPABLE = "已无可下推数量（剩余 0）";

    /** 存在未作废的下游单据时禁止反审核（与采购线同口径、同句式）。 */
    public static final String MSG_DOWNSTREAM_BLOCK =
            "已存在下游销售订单，请先处理下游单据（反审核或作废）后再操作";

    /** 销售订单存在未作废的下游出库单时禁止反审核（同句式，对象换成出库单）。 */
    public static final String MSG_DOWNSTREAM_STOCK_OUT_BLOCK =
            "已存在下游出库单，请先处理下游单据（反审核或作废）后再操作";

    private ErpSalRules()
    {
    }

    /* ==================== 单据必填与行项守卫 ==================== */

    /**
     * 销售申请单的表头必填项（单据日期 + 至少一行行项交由 {@link #normalizeItems}）。
     *
     * @param doc 单据
     * @throws ServiceException 单据为空 / 单据日期为空
     */
    public static void checkSalesRequestHeader(ErpSalesRequest doc)
    {
        if (doc == null)
        {
            throw new ServiceException("销售申请单不能为空");
        }
        if (doc.getDocDate() == null)
        {
            throw new ServiceException("单据日期不能为空");
        }
    }

    /**
     * 销售订单的表头必填项。
     *
     * <p> 客户引用<b>不在这里</b>校验：客户必须回档案库查（是否存在、是否停用、
     * 是否为供应商误传），属编排，放在服务层；这里只校验能自证的两项。 </p>
     *
     * @param doc 单据
     * @throws ServiceException 单据为空 / 单据日期为空
     */
    public static void checkSalesOrderHeader(ErpSalesOrder doc)
    {
        if (doc == null)
        {
            throw new ServiceException("销售订单不能为空");
        }
        if (doc.getDocDate() == null)
        {
            throw new ServiceException("单据日期不能为空");
        }
    }

    /**
     * <p> 行项通用守卫 + 序号重排 + 金额重算（申请单与订单共用一份口径）。 </p>
     *
     * <p> 校验顺序刻意"先数量后单价"：任务 4.1 的断言包含"数量为 0 被拒"，
     * 而单价为 0 是合法的（赠品行），因此不能把单价放到数量前面做短路。 </p>
     *
     * <p> 原地加工（不新建集合）是为了保持调用方的具体类型：申请行与订单行各自持有
     * 自己的累计列，服务层传进来的集合不能被当成"公共行项列表"返回，否则下游要再转型一次。 </p>
     *
     * @param items 行项集合（可空：返回 0，由调用方判"至少一行"）
     * @return 行项数量
     * @throws ServiceException 行项为空对象 / 数量为 0 或负 / 单价为负
     */
    public static int normalizeItems(List<? extends ErpDocItem> items)
    {
        if (items == null)
        {
            return 0;
        }
        int rowNo = 0;
        for (ErpDocItem item : items)
        {
            rowNo++;
            if (item == null)
            {
                throw new ServiceException("行 " + rowNo + "：行项不能为空");
            }
            BigDecimal qty = item.getQty();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
            {
                throw new ServiceException("行 " + rowNo + "：数量必须大于 0");
            }
            if (item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) < 0)
            {
                throw new ServiceException("行 " + rowNo + "：单价不能为负数");
            }
            item.setSeq(rowNo);
            // 金额的唯一实现点在 base 的 ErpAmounts（先 HALF_UP 到 2 位）
            item.recalcAmount();
        }
        return rowNo;
    }

    /**
     * "至少一行"守卫（提交与保存共用；文案与参考仓库的"单据没有行项"对齐）。
     *
     * @param rowCount 行项数量
     * @throws ServiceException 没有行项
     */
    public static void requireAtLeastOneItem(int rowCount)
    {
        if (rowCount <= 0)
        {
            throw new ServiceException("单据没有行项");
        }
    }

    /* ==================== 销售申请 → 销售订单：下推规则 ==================== */

    /**
     * <p> 本次下推数量：{@code qty} 为空或 ≤0 表示"按剩余量全推"。 </p>
     *
     * @param qty          本次下推数量（可空）
     * @param remainingQty 剩余可下推量
     * @return 实际下推数量（正数）
     * @throws ServiceException 剩余量已为 0（无剩余可下推）
     */
    public static BigDecimal resolvePushQty(BigDecimal qty, BigDecimal remainingQty)
    {
        BigDecimal remaining = remainingQty == null ? BigDecimal.ZERO : remainingQty;
        if (remaining.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("已无可下推数量（剩余 0）");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
        {
            return ErpAmounts.roundQty(remaining);
        }
        return ErpAmounts.roundQty(qty);
    }

    /**
     * <p> 下推数量必须大于 0 且不得超过剩余量。 </p>
     *
     * <p> 报文文案与参考仓库 {@code push_service.py:48-54} 同构：
     * {@code 「<物料名>」可下推数量不足（剩余 R，本次 Q）}。接口验收脚本按此字符串断言。 </p>
     *
     * @param productName  物料名称（用于定位到行）
     * @param remainingQty 剩余可下推量
     * @param pushQty      本次下推数量
     * @throws ServiceException 数量 ≤0 / 超量
     */
    public static void checkPushQty(String productName, BigDecimal remainingQty, BigDecimal pushQty)
    {
        BigDecimal remaining = remainingQty == null ? BigDecimal.ZERO : remainingQty;
        BigDecimal actual = pushQty == null ? BigDecimal.ZERO : pushQty;
        if (actual.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("下推数量必须大于 0");
        }
        if (actual.compareTo(remaining) > 0)
        {
            throw new ServiceException("「" + (productName == null ? "" : productName)
                    + "」可下推数量不足（剩余 " + ErpAmounts.textOf(remaining)
                    + "，本次 " + ErpAmounts.textOf(actual) + "）");
        }
    }

    /**
     * <p> 下推的前置状态守卫：<b>仅已审核</b>的销售申请单可以下推。 </p>
     *
     * @param status 当前状态
     * @throws ServiceException 非已审核
     */
    public static void checkPusheable(String status)
    {
        if (!ErpDocStatus.APPROVED.equals(trim(status)))
        {
            throw new ServiceException("仅已审核的销售申请单可以下推销售订单");
        }
    }

    /**
     * 本次下推数量必须为正（下推行校验失败时整体不写入）。
     *
     * @param pushQty 本次下推数量
     * @param rowNo   行号（1 起）
     * @throws ServiceException 数量缺失或 ≤0
     */
    public static void checkPushQtyPositive(BigDecimal pushQty, int rowNo)
    {
        if (pushQty == null || pushQty.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("行 " + rowNo + "：下推数量必须大于 0");
        }
    }

    /* ==================== 归零即完成 ==================== */

    /**
     * <p> <b>申请行是否全部推完</b>（"归零即完成"的判据）。 </p>
     *
     * <p> 空集合返回 {@code false}：没有行项的申请单不该被判成"推完了"
     * （无行项本身连提交都过不去）。 </p>
     *
     * @param items 销售申请行项集合
     * @return 全部行项剩余量为 0 返回 true
     */
    public static boolean isFullyOrdered(List<ErpSalesRequestItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return false;
        }
        for (ErpSalesRequestItem item : items)
        {
            if (item == null || !item.isFullyOrdered())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 列表剩余可下推量合计（供前端控制下推按钮显隐；行项为负剩余量时按 0 计）。
     *
     * @param items 行项集合（可空）
     * @return 3 位合计
     */
    public static BigDecimal remainingQtySum(List<ErpSalesRequestItem> items)
    {
        BigDecimal sum = BigDecimal.ZERO;
        if (items != null)
        {
            for (ErpSalesRequestItem item : items)
            {
                if (item == null)
                {
                    continue;
                }
                BigDecimal remaining = item.remainingQty();
                sum = sum.add(remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining);
            }
        }
        return ErpAmounts.roundQty(sum);
    }

    /**
     * 销售订单行的剩余可出库量合计（与申请侧同构，供列表与下推对话框展示）。
     *
     * @param items 订单行项集合（可空）
     * @return 3 位合计
     */
    public static BigDecimal remainingShippableQtySum(List<ErpSalesOrderItem> items)
    {
        BigDecimal sum = BigDecimal.ZERO;
        if (items != null)
        {
            for (ErpSalesOrderItem item : items)
            {
                if (item == null)
                {
                    continue;
                }
                BigDecimal remaining = item.remainingQty();
                sum = sum.add(remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining);
            }
        }
        return ErpAmounts.roundQty(sum);
    }

    /* ==================== 关联合同（仅销售方向） ==================== */

    /**
     * <p> <b>销售单据只能关联销售方向的合同</b>（任务 4.4）。 </p>
     *
     * <p> 方向判定按契约类型的<b>标签</b>：B3 的合同类型字典 {@code contract_types} 里
     * 销售/收入方向的标签含"销售"（{@code dict_value=SAL}），采购/支出方向含"采购"
     * （{@code dict_value=PUR}）。传标签而不是传 {@code dict_value} 是因为合同表存的是
     * 中文类型名（DDL 注释：取值集合落 {@code sys_dict_data.contract_types}）。 </p>
     *
     * @param contractTypeLabel 合同类型标签（来自 {@code sys_dict_data}，可空）
     * @throws ServiceException 缺失 / 非销售方向
     */
    public static void checkContractDirection(String contractTypeLabel)
    {
        String label = trim(contractTypeLabel);
        if (label == null)
        {
            throw new ServiceException("关联合同不存在或已停用");
        }
        if (label.indexOf(CONTRACT_DIRECTION_KEYWORD) < 0)
        {
            throw new ServiceException("只能关联销售方向的合同（当前合同方向：" + label + "）");
        }
    }

    /**
     * 判断合同类型是否销售方向（纯函数版本；供服务层在拿到标签后自行分支）。
     *
     * @param contractTypeLabel 合同类型标签
     * @return 销售方向返回 true
     */
    public static boolean isSaleDirection(String contractTypeLabel)
    {
        String label = trim(contractTypeLabel);
        return label != null && label.indexOf(CONTRACT_DIRECTION_KEYWORD) >= 0;
    }

    /* ==================== 客户引用守卫（纯判定部分） ==================== */

    /**
     * 客户引用必须存在且启用（存在性由服务层先查出来，本方法只判"是否可用"）。
     *
     * @param customerId 客户档案ID
     * @param name       客户名称（用于文案）
     * @param enableFlag 启用标志（{@code loadEnableFlag} 的返回值：{@code null} = 档案不存在）
     * @throws ServiceException 未选客户 / 客户档案不存在 / 已停用
     */
    public static void checkCustomerUsable(String customerId, String name, String enableFlag)
    {
        if (trim(customerId) == null)
        {
            throw new ServiceException("销售订单必须选择客户档案");
        }
        if (enableFlag == null)
        {
            throw new ServiceException("客户档案不存在：" + customerId);
        }
        if (ENABLE_NO.equals(trim(enableFlag)))
        {
            throw new ServiceException("客户「" + (trim(name) == null ? "" : trim(name)) + "」已停用，不能用于新销售订单");
        }
    }

    /* ==================== 排他性判定（3.2 / 4.1 的对称断言） ==================== */

    /**
     * <p> 供应商与客户是两套档案：把供应商ID传到客户字段必须能识别出来。 </p>
     *
     * <p> 判据是"客户档案里查不到，但供应商档案里查得到"，文案与规格场景一致
     * （{@code 请求被拒绝并提示客户档案不存在}），并额外点明"该标识属于供应商档案"
     * 以便前端直接给用户改错的机会。 </p>
     *
     * @param customerId 客户字段收到的标识
     * @throws ServiceException 该标识是供应商档案
     */
    public static void rejectSupplierAsCustomer(String customerId)
    {
        throw new ServiceException("客户档案不存在：" + customerId + "（该标识属于供应商档案，销售订单只能引用客户）");
    }

    /* ==================== 通用小工具 ==================== */

    /**
     * 逻辑判空：null 或纯空白都算空。
     *
     * @param value 待判定字符串
     * @return true = 空
     */
    public static boolean isBlank(String value)
    {
        return trim(value) == null;
    }

    /**
     * 去空白归一（空返回 null）。
     *
     * @param value 原始值
     * @return 去首尾空白后的值，或 null
     */
    public static String trim(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 变更历史字段名 → 展示标签（详情装配用；未登记的字段名原样返回）。
     *
     * @param fieldName 字段名
     * @return 中文标签
     */
    public static String fieldLabel(String fieldName)
    {
        if (LOG_FIELD_STATUS.equals(fieldName))
        {
            return "状态";
        }
        if (LOG_FIELD_CONTRACT.equals(fieldName))
        {
            return "关联合同";
        }
        return fieldName;
    }
}
