package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 简化流程定义（设计器草稿 / 已发布） </p>
 *
 * <p> 表：{@code t_flow_simple}；{@code content} 存放设计器产出的简化流程 JSON，
 * 由 {@code SimpleFlowCompiler} 编译为 BPMN XML 后部署。 </p>
 *
 * @author 二开
 */
@Data
public class FlowSimple implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 流程定义 key（部署到 Flowable 的 process id） */
    private String defKey;

    /** 流程名称 */
    private String name;

    /** 流程分类 */
    private String category;

    /** 简化流程 JSON */
    private String content;

    /** JSON 结构版本 */
    private Integer schemaVersion;

    /** 状态：0-草稿，1-已发布 */
    private String status;

    /** 已发布版本号（0 表示从未发布） */
    private Integer version;

    /** 最近一次部署ID */
    private String deployId;

    /** 最近一次流程定义ID */
    private String procDefId;

    /** 备注 */
    private String remark;

    /** 删除标识：0-未删除，1-已删除 */
    private String delFlag;

    private String createId;
    private String createBy;
    private Date createTime;
    private String updateId;
    private String updateBy;
    private Date updateTime;
}
