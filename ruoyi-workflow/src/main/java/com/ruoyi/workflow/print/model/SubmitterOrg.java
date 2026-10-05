package com.ruoyi.workflow.print.model;

import lombok.Data;

import java.io.Serializable;

/**
 * <p> 单据发起人的组织信息（打印件的内置变量 {@code $submitter} / {@code $submitterDept} /
 * {@code $submitterCompany} 的数据来源） </p>
 *
 * <p> <b>为什么要专门查一次</b>：签发栏之外的"报送单位/报送人/审批单位"三个栏目取自
 * <b>发起人组织</b>，而流程记录里其实拿不到它 ——
 * {@code FlowTaskDto.startUserName} / {@code startDeptName} 在 ruoyi-flowable 里
 * <b>没有任何地方赋值</b>（全仓 {@code setStartUserName} 零命中，实测接口返回恒为 null）。
 * 真正可靠的发起人是单据自身：{@code t_workflow_form.create_id}。 </p>
 *
 * <p> 公司级名称用 {@code sys_dept.ancestors} 上溯：ancestors 是"根到父"的 id 串
 * （如 {@code 0,000AAA...}），<b>第 2 段</b>就是公司级部门；若本部门已是公司级
 * （ancestors 只有 {@code 0}），则用本部门名称。 </p>
 *
 * @author 二开
 */
@Data
public class SubmitterOrg implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发起人ID（sys_user.user_id） */
    private String userId;

    /** 发起人姓名（优先昵称） */
    private String userName;

    /** 发起人所属部门ID */
    private String deptId;

    /** 发起人所属部门名称 */
    private String deptName;

    /** 部门物化路径（根到父，逗号分隔），公司级名称由它上溯 */
    private String ancestors;
}
