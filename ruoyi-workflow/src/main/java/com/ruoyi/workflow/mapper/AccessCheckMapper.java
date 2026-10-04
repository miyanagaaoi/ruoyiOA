package com.ruoyi.workflow.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * <p> 越权防护的两条判定（PRD 11.3 / AC-35）："这张单据你看不看得见"、"这个任务是不是你的" </p>
 *
 * <p> <b>为什么不用 Flowable 的 {@code taskCandidateOrAssigned}</b>：实测对
 * {@code ASSIGNEE_ = 'superAdmin'} 的在办任务（{@code ACT_RU_TASK} 里有明确办理人、
 * 且是当前登录用户）返回 null —— 用它做安全判定会把自己人也挡在门外。
 * 这里的查询是**自己写死的 SQL**：三路命中即认，行为可解释、可回归。 </p>
 *
 * <p> <b>为什么不去新建一张"权限表"</b>：本系统里"我能不能看见这张单据"这件事，
 * 早就由业务表自己回答了 —— 它出现在你的待办、已办、回收站里，或者你就是发起人。
 * 另立一份权限表就等于有了第二个真相，迟早与列表页显示的内容不一致
 * （会出现"列表里看得见、点打印说没权限"）。所以这里直接问那四张表。 </p>
 *
 * <p> 放在 {@code com.ruoyi.workflow.mapper} 下是为了被全局 {@code @MapperScan("com.ruoyi.**.mapper")} 扫到。 </p>
 *
 * @author 二开
 */
public interface AccessCheckMapper {

    /**
     * 该用户对该单据的"可见命中数"（四路任一命中即 &gt; 0）。
     *
     * <ol>
     *   <li> {@code t_workflow_todo.cur_handler} —— 待办（含候选人/加签产生的待办）； </li>
     *   <li> {@code t_workflow_done.handler} —— 已办； </li>
     *   <li> {@code t_workflow_recycle.create_id} —— 回收站； </li>
     *   <li> {@code t_workflow_form.create_id} —— 单据本体（发起人，草稿也走这条）。 </li>
     * </ol>
     *
     * @param businessId 业务ID
     * @param userId     用户ID（本系统是字符串，如 {@code superAdmin} 或 UUID）
     * @return 命中数；&gt;0 表示有查看权
     */
    int countViewerHit(@Param("businessId") String businessId, @Param("userId") String userId);

    /**
     * 该任务是否属于该用户（三路任一命中即 &gt; 0）。
     *
     * <ol>
     *   <li> {@code ACT_RU_TASK.ASSIGNEE_} —— 办理人（RuoYi 直接把人写成 assignee）； </li>
     *   <li> {@code ACT_RU_IDENTITYLINK.USER_ID_} —— 候选人（未签收也算，签收后第 1 路命中）； </li>
     *   <li> {@code t_workflow_todo.cur_handler} —— 应用侧待办（加签/转办等由应用写入）。 </li>
     * </ol>
     *
     * <p> 已知边界：<b>候选组</b>（{@code GROUP_ID_} = 角色/部门ID）不计入 ——
     * 本系统现有流程都是用 {@code flowable:assignee} / {@code candidateUsers} 指到人，
     * 库里 {@code ACT_RU_IDENTITYLINK} 的 candidate 记录 GROUP_ID_ 全为空。
     * 将来若要用候选组，这里必须同步支持（按当前用户的角色/部门展开）。 </p>
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 命中数；&gt;0 表示是自己的任务
     */
    int countTaskOwnerHit(@Param("taskId") String taskId, @Param("userId") String userId);
}
