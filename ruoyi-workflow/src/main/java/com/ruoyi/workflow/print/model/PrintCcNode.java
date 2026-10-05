package com.ruoyi.workflow.print.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 一条<b>抄送</b>记录（打印件的"抄送栏"用，{@code showCcNode='1'} 才出栏） </p>
 *
 * <p> 数据来源是 {@code t_workflow_todo} 里 {@code handle_type='6'} 的行 ——
 * 本项目的"抄送"就是一条只读待办（{@code TodoHandleTypeEnum.COPY("6","抄送")}，
 * 由 {@code TodoServiceImpl.buildCopyTaskTodo} 生成），所以抄送人/抄送时间/节点名
 * 分别落在 {@code cur_handler_name} / {@code send_time} / {@code cur_node} 上。
 * 已阅办的抄送**仍在这张表里**（{@code read_flag='1'}），因此开启出栏时能看全。 </p>
 *
 * @author 二开
 */
@Data
public class PrintCcNode implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 触发抄送的流程节点名（{@code t_workflow_todo.cur_node}） */
    private String nodeName;

    /** 抄送人姓名 */
    private String handlerName;

    /** 抄送时间 */
    private Date sendTime;

    /** 是否已阅办：0-未阅 1-已阅 */
    private String readFlag;
}
