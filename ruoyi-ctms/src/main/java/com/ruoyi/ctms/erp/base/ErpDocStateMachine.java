package com.ruoyi.ctms.erp.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;

/**
 * <p> <b>逐动作白名单状态机</b>（2.0 B4 任务 1.1；design D4、移植清单 §3.8）。 </p>
 *
 * <p> 状态机只有一张表：<b>动作 → 允许的来源状态集合</b>。判定入口一律
 * {@link #check(ErpDocAction, String, ErpDocType, boolean, String)}；禁止在业务服务里
 * 各写一段 {@code if (status.equals(...))}，否则 8 类单据会各自漂移。 </p>
 *
 * <p> 冻结矩阵（逐条对应移植清单 §3.8 的图与 `doc_service.py:166-170`）： </p>
 * <pre>
 * draft     --submit--&gt;     submitted   （要求至少有 1 行行项）
 * submitted --reject--&gt;     draft       （原因必填）
 * submitted --approve--&gt;    approved
 * draft     --void--&gt;       voided      （原因必填）
 * submitted --void--&gt;       voided      （原因必填）
 * approved  --unapprove--&gt;  submitted   （原因必填；已过账则红冲库存）
 * completed --unapprove--&gt;  draft       （原因必填）
 * approved  --complete--&gt;   completed   （**库存类单据不提供该动作**）
 * </pre>
 *
 * <p> <b>为什么"库存类不提供 complete"也要在状态机里显式拒绝</b>：入口不注册只是"界面不显示"，
 * 直接调用接口仍会进来；显式拒绝才能保证"系统中不存在已完成的库存类单据"
 * （design.md Risks 的这一条是验收口径，不是 UI 约定）。 </p>
 *
 * @author 二开
 */
public final class ErpDocStateMachine
{
    /** 动作 → 允许的来源状态（冻结矩阵；顺序即错误文案里的展示顺序）。 */
    private static final Map<ErpDocAction, List<String>> ALLOWED = buildAllowedMatrix();

    private ErpDocStateMachine()
    {
    }

    private static Map<ErpDocAction, List<String>> buildAllowedMatrix()
    {
        Map<ErpDocAction, List<String>> matrix = new LinkedHashMap<>();
        matrix.put(ErpDocAction.SUBMIT, list(ErpDocStatus.DRAFT));
        matrix.put(ErpDocAction.APPROVE, list(ErpDocStatus.SUBMITTED));
        matrix.put(ErpDocAction.REJECT, list(ErpDocStatus.SUBMITTED));
        matrix.put(ErpDocAction.VOID, list(ErpDocStatus.DRAFT, ErpDocStatus.SUBMITTED));
        matrix.put(ErpDocAction.UNAPPROVE, list(ErpDocStatus.APPROVED, ErpDocStatus.COMPLETED));
        matrix.put(ErpDocAction.COMPLETE, list(ErpDocStatus.APPROVED));
        return Collections.unmodifiableMap(matrix);
    }

    private static List<String> list(String... values)
    {
        List<String> result = new ArrayList<>(values.length);
        Collections.addAll(result, values);
        return Collections.unmodifiableList(result);
    }

    /**
     * 某动作允许的来源状态清单（只读；供前端按钮显隐与测试矩阵复用）。
     *
     * @param action 动作
     * @return 状态清单
     */
    public static List<String> allowedFrom(ErpDocAction action)
    {
        List<String> allowed = action == null ? null : ALLOWED.get(action);
        return allowed == null ? Collections.<String>emptyList() : allowed;
    }

    /**
     * 校验动作是否可以执行（不改变状态；"审核即过账"由过账服务在同一事务内接续完成）。
     *
     * @param action   动作（不得为 null）
     * @param status   当前状态（取自库里的 status 列）
     * @param docType  单据类型（用于"库存类不提供 complete"的判定；可传 null 表示不限定）
     * @param hasItems 是否至少有一行行项（提交动作要求 true；其它动作传 false 不影响判定）
     * @param reason   原因（驳回/作废/反审核要求非空）
     * @throws ServiceException 状态非法 / 动作不在白名单 / 缺行项 / 缺原因 / 库存类置完成
     */
    public static void check(ErpDocAction action, String status, ErpDocType docType,
                            boolean hasItems, String reason)
    {
        checkAndNext(action, status, docType, hasItems, reason);
    }

    /**
     * 校验动作并返回<b>流转后的目标状态</b>（唯一的状态计算点）。
     *
     * @param action   动作
     * @param status   当前状态
     * @param docType  单据类型（可空）
     * @param hasItems 是否至少有一行行项
     * @param reason   原因
     * @return 流转后的目标状态
     * @throws ServiceException 同 {@link #check}
     */
    public static String checkAndNext(ErpDocAction action, String status, ErpDocType docType,
                                      boolean hasItems, String reason)
    {
        if (action == null)
        {
            throw new ServiceException("单据动作不能为空");
        }
        String current = status == null ? null : status.trim();
        if (!ErpDocStatus.isValid(current))
        {
            throw new ServiceException("无效的单据状态：" + status);
        }
        // ① 库存类单据不提供「置为已完成」：先于白名单判定，报错更准确
        if (action == ErpDocAction.COMPLETE && docType != null && !docType.supportsCompleteAction())
        {
            throw new ServiceException(docType.getLabel() + "为库存类单据，不支持「置为已完成」");
        }
        // ② 逐动作白名单
        if (!allowedFrom(action).contains(current))
        {
            throw new ServiceException("当前状态（" + ErpDocStatus.labelOf(current) + "）不可执行「"
                    + action.getLabel() + "」");
        }
        // ③ 提交必须有行项（参考侧 422「单据没有行项，不能提交」）
        if (action == ErpDocAction.SUBMIT && !hasItems)
        {
            throw new ServiceException("单据没有行项，不能提交");
        }
        // ④ 原因必填（驳回/作废/反审核）
        if (action.isReasonRequired() && isBlank(reason))
        {
            throw new ServiceException(action.reasonRequiredMessage());
        }
        return nextStatus(action, current);
    }

    /**
     * 目标状态计算（{@link #checkAndNext} 已校验前置条件）。
     *
     * <p> {@code unapprove} 的目标状态<b>依赖来源状态</b>：{@code approved → submitted}、
     * {@code completed → draft}（移植清单 §3.8 的图）。{@code submit} 之后一律 {@code submitted}。 </p>
     *
     * @param action  动作
     * @param current 当前状态（已校验合法）
     * @return 目标状态
     */
    public static String nextStatus(ErpDocAction action, String current)
    {
        if (action == null)
        {
            throw new ServiceException("单据动作不能为空");
        }
        switch (action)
        {
            case SUBMIT:
                return ErpDocStatus.SUBMITTED;
            case APPROVE:
                return ErpDocStatus.APPROVED;
            case REJECT:
                return ErpDocStatus.DRAFT;
            case VOID:
                return ErpDocStatus.VOIDED;
            case UNAPPROVE:
                return ErpDocStatus.COMPLETED.equals(current) ? ErpDocStatus.DRAFT : ErpDocStatus.SUBMITTED;
            case COMPLETE:
                return ErpDocStatus.COMPLETED;
            default:
                throw new ServiceException("未实现的动作：" + action);
        }
    }

    /**
     * 编辑前的状态守卫（参考侧原文：{@code 当前状态（X）不可编辑，请先反审核或作废}）。
     *
     * <p> ⚠ 文案与参考测<b>逐字一致</b>（`models_doc.py:43` + `doc_service.py:39-41`）：
     * 前端与验收脚本按这句断言，因此<b>不</b>在句中拼接单据名。
     * {@code docType} 参数保留给调用方（8 类单据共用一个入口）与将来的审计/日志扩展。 </p>
     *
     * @param status  当前状态
     * @param docType 单据类型（可空；本方法不把它拼进错误文案）
     * @throws ServiceException 非草稿
     */
    public static void checkEditable(String status, ErpDocType docType)
    {
        if (ErpDocStatus.isEditable(status))
        {
            return;
        }
        throw new ServiceException("当前状态（" + ErpDocStatus.labelOf(status) + "）不可编辑，请先反审核或作废");
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
