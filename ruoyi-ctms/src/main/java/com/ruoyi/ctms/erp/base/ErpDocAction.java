package com.ruoyi.ctms.erp.base;

/**
 * <p> 单据动作（审议动作集合，2.0 B4 任务 1.1）。 </p>
 *
 * <p> 动作集合与文案逐条对齐参考仓库 `doc_service.py:166-170` 的 `_ensure_status(doc, allowed, action)`
 * 与移植清单 §3.8 的状态机图；每个动作的<b>前置白名单</b>与<b>原因是否必填</b>写在
 * {@link ErpDocStateMachine}（本枚举只承载"动作是什么"）。 </p>
 *
 * <p> {@code actionCode} 同时用于变更历史/操作日志的 {@code action} 字段（参考侧同款）。 </p>
 *
 * @author 二开
 */
public enum ErpDocAction
{
    /** 提交：仅草稿，且必须有行项。 */
    SUBMIT("submit", "提交"),

    /** 审核：仅待审核；库存类单据同时触发过账（同一事务）。 */
    APPROVE("approve", "审核"),

    /** 驳回：仅待审核，原因必填（回到草稿）。 */
    REJECT("reject", "驳回"),

    /** 作废：仅草稿或待审核，原因必填。 */
    VOID("void", "作废"),

    /** 反审核：仅已审核或已完成，原因必填；已过账则红冲（库存类）。 */
    UNAPPROVE("unapprove", "反审核"),

    /** 置为已完成：仅已审核，且**库存类单据不提供该动作**。 */
    COMPLETE("complete", "置为已完成");

    private final String actionCode;

    private final String label;

    ErpDocAction(String actionCode, String label)
    {
        this.actionCode = actionCode;
        this.label = label;
    }

    /**
     * 动作码（落操作日志/变更历史用；与参考侧的 action 取值一致）。
     *
     * @return 动作码
     */
    public String getActionCode()
    {
        return actionCode;
    }

    /**
     * 动作中文名（拼错误文案与变更历史用）。
     *
     * @return 中文名
     */
    public String getLabel()
    {
        return label;
    }

    /**
     * 本动作在流转时是否必须提供原因（驳回/作废/反审核三个动作必填）。
     *
     * @return 必填返回 true
     */
    public boolean isReasonRequired()
    {
        return this == REJECT || this == VOID || this == UNAPPROVE;
    }

    /**
     * 原因缺失时的错误文案（前端与验收脚本按这些字符串断言，**不要各写一套**）。
     *
     * @return 形如 {@code 驳回原因必填}
     */
    public String reasonRequiredMessage()
    {
        return label + "原因必填";
    }
}
