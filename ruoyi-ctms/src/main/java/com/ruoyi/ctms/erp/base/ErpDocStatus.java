package com.ruoyi.ctms.erp.base;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p> 单据状态集合（2.0 B4 任务 1.1；design D4「单级状态机，不接 Flowable」）。 </p>
 *
 * <p> <b>唯一真源</b>：状态取值来自参考仓库 `models_doc.py:34-40` 的字典
 * （{@code {"draft":"草稿","submitted":"待审核","approved":"已审核",
 * "completed":"已完成","voided":"已作废"}}），落库列 {@code status varchar(16)}。 </p>
 *
 * <p> <b>两类单据的状态空间不同</b>（移植清单 §3.8 的冻结结论）： </p>
 * <ul>
 *   <li> 申请单 / 采购单 / 销售订单：五态齐全（可用 {@link #COMPLETED}）； </li>
 *   <li> 入库 / 出库 / 盘点 / 调拨（{@link ErpDocType#isStockDoc()}）：{@code approved} 即终态，
 *        <b>不使用 completed</b>，也<b>不注册「置为已完成」动作</b>（见
 *        {@link ErpDocStateMachine#check}）。 </li>
 * </ul>
 *
 * @author 二开
 */
public final class ErpDocStatus
{
    /** 草稿（**唯一可编辑状态**：{@code EDITABLE_STATUSES = {"draft"}}）。 */
    public static final String DRAFT = "draft";

    /** 待审核（提交后）。 */
    public static final String SUBMITTED = "submitted";

    /** 已审核（库存类单据的终态；审核即过账）。 */
    public static final String APPROVED = "approved";

    /** 已完成（仅非库存类单据使用；也可由申请单「归零即完成」自动置位）。 */
    public static final String COMPLETED = "completed";

    /** 已作废（列表默认排除）。 */
    public static final String VOIDED = "voided";

    /** 全部状态（顺序即文档展示顺序）。 */
    private static final List<String> ALL =
            Collections.unmodifiableList(Arrays.asList(DRAFT, SUBMITTED, APPROVED, COMPLETED, VOIDED));

    private ErpDocStatus()
    {
    }

    /**
     * 全部状态清单（只读）。
     *
     * @return 状态清单
     */
    public static List<String> all()
    {
        return ALL;
    }

    /**
     * 状态是否合法（非法状态一律拒绝，避免脏状态被当成"某种可流转态"）。
     *
     * @param status 状态值
     * @return 合法返回 true
     */
    public static boolean isValid(String status)
    {
        return status != null && ALL.contains(status.trim());
    }

    /**
     * 状态中文名（前端状态标签与错误文案共用；未知状态原样返回，便于定位脏数据）。
     *
     * @param status 状态值
     * @return 中文名
     */
    public static String labelOf(String status)
    {
        if (status == null)
        {
            return "";
        }
        String value = status.trim();
        if (DRAFT.equals(value))
        {
            return "草稿";
        }
        if (SUBMITTED.equals(value))
        {
            return "待审核";
        }
        if (APPROVED.equals(value))
        {
            return "已审核";
        }
        if (COMPLETED.equals(value))
        {
            return "已完成";
        }
        if (VOIDED.equals(value))
        {
            return "已作废";
        }
        // 未知状态不伪装：原样返回，让前端与日志都能看出这是脏数据
        return value;
    }

    /**
     * <p> 是否可编辑：**只有草稿可编辑**（参考侧 {@code EDITABLE_STATUSES = {"draft"}}，
     * `models_doc.py:43`）。 </p>
     *
     * <p> 提示文案由调用方负责（参考侧 422「当前状态（X）不可编辑，请先反审核或作废」）；
     * 本方法只做判定，避免"文案"与"判定"两处漂移。 </p>
     *
     * @param status 状态值
     * @return 可编辑返回 true
     */
    public static boolean isEditable(String status)
    {
        return status != null && DRAFT.equals(status.trim());
    }
}
