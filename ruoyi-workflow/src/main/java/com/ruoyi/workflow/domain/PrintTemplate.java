package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 打印模板（表 {@code t_template_print_template}） </p>
 *
 * <p> 决定"一张 A4 打印件长什么样"：纸张、标题、各区显示开关，以及最关键的
 * {@code fieldMap} —— 基本信息区的"字段落哪一行哪一格"。 </p>
 *
 * <p> {@code fieldMap} 为空时**不报错**，由服务层回退到内置的系统模板
 * （见 {@code PrintServiceImpl#defaultFieldMap()}），这样"还没有配模板"的单据也能打印。 </p>
 *
 * @author 二开
 */
@Data
public class PrintTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 单据模板ID（t_template.id） */
    private String templateId;

    /** 打印模板名称 */
    private String name;

    /** 纸张，默认 A4 */
    private String paper;

    /** 方向，默认 portrait */
    private String orientation;

    /** 单据标题（为空则取内置默认标题） */
    private String title;

    /** Logo 文件ID */
    private String logoFileId;

    /** 版式字段映射 JSON */
    private String fieldMap;

    /** 是否打印签名：0-否，1-是 */
    private String showSignature;

    /** 是否打印意见：0-否，1-是 */
    private String showComment;

    /** 是否打印附件清单：0-否，1-是 */
    private String showAttachment;

    /** 抄送节点是否出栏：0-否，1-是 */
    private String showCcNode;

    /** 是否加水印：0-否，1-是 */
    private String watermark;

    /** 页脚备注 */
    private String footerNote;

    /** 是否启用：0-否，1-是 */
    private String enableFlag;

    /** 删除标识：0-未删除，1-已删除 */
    private String delFlag;

    private String createId;
    private String createBy;
    private Date createTime;
    private String updateId;
    private String updateBy;
    private Date updateTime;
}
