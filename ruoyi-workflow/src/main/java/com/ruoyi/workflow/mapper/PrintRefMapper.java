package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.print.model.PrintCcNode;
import com.ruoyi.workflow.print.model.SubmitterOrg;

import java.util.List;

/**
 * <p> 打印相关的<b>参照数据</b>查询（发起人组织、单据模板的内置版式键） </p>
 *
 * <p> 单独一个 Mapper 而不是塞进 {@code PrintTemplateMapper}：后者只管打印模板表，
 * 这里查的是"别的表"（{@code t_workflow_form} / {@code sys_user} / {@code sys_dept}）。 </p>
 *
 * <p> ⚠ 与既有 Mapper 同一约定：放在 {@code com.ruoyi.workflow.mapper} 下才会被全局
 * MapperScan 扫到。 </p>
 *
 * @author 二开
 */
public interface PrintRefMapper {

    /**
     * 取单据发起人的组织信息（姓名 + 部门 + 部门物化路径）。
     *
     * <p> 发起人取 {@code t_workflow_form.create_id} —— 流程记录里的
     * {@code startUserName / startDeptName} 在 ruoyi-flowable 里从不赋值（实测恒为 null），
     * 只有单据自身记着"是谁起草的"。草稿（无流程实例）同样有这条记录。 </p>
     *
     * @param businessId 业务ID（= {@code t_workflow_form.id}）
     * @return 找不到返回 null
     */
    SubmitterOrg selectSubmitterOrgByBusinessId(String businessId);

    /** 按部门ID取部门名称（上溯公司级时用） */
    String selectDeptNameById(String deptId);

    /**
     * 取一张单据的**抄送记录**（打印件的抄送栏，2.0 B2 §4.5 / REQ-PRINT-014）。
     *
     * <p> 抄送在本项目里是一条 {@code handle_type='6'} 的只读待办，落在 {@code t_workflow_todo}；
     * 已阅办的记录也在同一张表里（{@code read_flag='1'}），所以这里能取全。 </p>
     */
    List<PrintCcNode> selectCcNodesByBusinessId(String businessId);
}
