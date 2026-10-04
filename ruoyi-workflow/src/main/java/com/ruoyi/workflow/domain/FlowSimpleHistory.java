package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 简化流程发布历史（版本快照，只追加） </p>
 *
 * <p> 表：{@code t_flow_simple_history}。每次发布写入一条，{@code def_key + version} 唯一。
 * 已发布版本的 {@code content} 不可修改——在途单据依赖它做追溯。 </p>
 *
 * @author 二开
 */
@Data
public class FlowSimpleHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /** 流程定义 key */
    private String defKey;

    /** 版本号（自增，从 1 开始） */
    private Integer version;

    /** 流程名称（发布时快照） */
    private String name;

    /** 分类（快照） */
    private String category;

    /** 简化流程 JSON（发布时快照，不可变） */
    private String content;

    private Integer schemaVersion;

    /** 本次部署ID */
    private String deployId;

    /** 本次流程定义ID */
    private String procDefId;

    private String publisherId;
    private String publisherName;
    private Date publishTime;
    private String remark;
}
