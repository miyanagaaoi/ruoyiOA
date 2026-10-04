package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 签名记录（表 {@code t_workflow_sign_record}，只追加） </p>
 *
 * <p> 签名 = <b>人</b>；盖章 = 组织。签名落在单据（打印件）上，盖章落在正文文件上（PRD 8.1）。 </p>
 *
 * <p> 防篡改靠哈希链（PRD 8.5）：
 * {@code recordHash = SHA-256(id‖businessId‖taskId‖fileId‖signUserId‖signTime‖formDataHash‖prevHash)}，
 * {@code prevHash} 取同单据上一条记录。本表<b>没有 update / delete</b>，
 * 撤签是追加一条 {@code signType=9} 的记录。 </p>
 *
 * @author 二开
 */
@Data
public class SignRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 手写签名 */
    public static final String TYPE_HANDWRITE = "0";
    /** 预存签名 */
    public static final String TYPE_PRESET = "1";
    /** 电子印章 */
    public static final String TYPE_SEAL = "2";
    /** 撤销（追加记录，不改旧记录） */
    public static final String TYPE_REVOKE = "9";

    /** 主键 */
    private String id;

    /** 业务ID */
    private String businessId;

    /** 流程实例ID */
    private String procInsId;

    /** 任务ID */
    private String taskId;

    /** 节点定义key */
    private String taskDefKey;

    /** 节点名称（= 打印件签批栏标题） */
    private String nodeName;

    /** 签名方式：0-手写，1-预存，2-印章，9-撤销 */
    private String signType;

    /** 签名图片文件ID */
    private String fileId;

    /** 签名人ID */
    private String signUserId;

    /** 签名人姓名 */
    private String signUserName;

    /** 签名时间 */
    private Date signTime;

    /** 签名IP */
    private String signIp;

    /** 浏览器UA */
    private String userAgent;

    /** 签名时表单数据 SHA-256（证明"签的是哪一版内容"） */
    private String formDataHash;

    /** 上一条记录哈希 */
    private String prevHash;

    /** 本条记录哈希 */
    private String recordHash;
}
