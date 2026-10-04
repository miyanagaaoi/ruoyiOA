package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 打印日志（表 {@code t_print_log}，只追加） </p>
 *
 * <p> 用于满足"审计/检查补打要能追溯谁在何时打印过"（PRD 7.1 / 7.6）。
 * 只追加，不做更新与删除。 </p>
 *
 * @author 二开
 */
@Data
public class PrintLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private String id;

    /** 业务ID */
    private String businessId;

    /** 流程实例ID */
    private String procInsId;

    /** 单据模板ID */
    private String templateId;

    /** 打印模板ID */
    private String printTplId;

    /** 打印人ID */
    private String printUserId;

    /** 打印人姓名 */
    private String printUserName;

    /** 打印时间 */
    private Date printTime;

    /** 打印IP */
    private String printIp;

    /** 浏览器UA */
    private String userAgent;

    /** 打印页数 */
    private Integer pageCount;

    /** 水印文字 */
    private String watermarkText;
}
