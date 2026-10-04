package com.ruoyi.workflow.print.model;

import com.ruoyi.workflow.domain.PrintTemplate;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p> 打印数据聚合（PRD 10.2-B：{@code GET /workflow/print/data/{businessId}}） </p>
 *
 * <p> 打印是<b>只读</b>动作，所以这里一次性把"渲染一张 A4 需要的一切"聚合齐，
 * 避免打印页再发 5 个请求（PRD 7.3 的决策）。 </p>
 *
 * @author 二开
 */
@Data
public class PrintData implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务ID */
    private String businessId;

    /** 流程实例ID（由 businessId 作为 Flowable businessKey 反查得到） */
    private String procInsId;

    /** 单据编号（内置变量 $businessNo） */
    private String businessNo;

    /** 单据模板ID */
    private String templateId;

    /** 单据模板名称 */
    private String templateName;

    /** 生效的打印模板（DB 里没有时是代码内置的系统模板，不会是 null） */
    private PrintTemplate printTemplate;

    /** 实际使用的标题 */
    private String title;

    /** 发起人姓名（$submitter） */
    private String submitter;

    /** 发起人所属公司/部门（$submitterDept） */
    private String submitterDept;

    /** 发起时间（$submitTime） */
    private Date submitTime;

    /** 办结时间（$finishTime） */
    private Date finishTime;

    /** 实例状态（$instanceStatus）：运行中 / 已结束 / 已终止 */
    private String instanceStatus;

    /**
     * 表单数据。**直接透传 {@code IBizFormService.getBizForm()} 的返回值**，
     * 形状由该接口决定（含字段中文 label 的解析也在它那边完成），这里不二次加工，
     * 避免两处各写一份"字段 → 中文名"的映射而漂移。
     */
    private Object formData;

    /** 表单 Schema（供打印页按 field_map 取值） */
    private Object formSchema;

    /** 签批栏：按流程节点顺序 */
    private List<Node> nodes;

    /** 附件清单（只打印清单，不打印文件内容） */
    private List<Attachment> attachments;

    /** 水印文字（模板 watermark=0 时为空） */
    private String watermarkText;

    /** 打印时间（页脚用） */
    private Date printTime;

    /** 打印人（页脚用） */
    private String printUser;

    /**
     * 一个签批栏。字段与 PRD 7.4-C 的表格一一对应。
     */
    @Data
    public static class Node implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 任务ID */
        private String taskId;

        /** 节点定义 key */
        private String taskDefKey;

        /** 节点名称（= 签批栏标题） */
        private String nodeName;

        /** 接收单位（办理人所属部门） */
        private String deptName;

        /** 接收人（办理人姓名） */
        private String assigneeName;

        /** 签收时间（= 任务创建时间） */
        private Date receiveTime;

        /** 完成时间 */
        private Date finishTime;

        /** 处理意见 */
        private String comment;

        /** 签名图片文件ID（签名功能实现前恒为 null，打印页据此留空栏供手写） */
        private String signFileId;

        /** 节点状态 */
        private String status;
    }

    /** 附件清单行 */
    @Data
    public static class Attachment implements Serializable {

        private static final long serialVersionUID = 1L;

        private String fileId;
        private String fileName;
        private String fileExt;
        private long fileSize;
        private int sort;
    }
}
