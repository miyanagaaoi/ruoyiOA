package com.ruoyi.workflow.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p> 用户预存签名（表 {@code t_user_sign_preset}） </p>
 *
 * <p> 用途（PRD 8.4）：审批人不必每次手写 —— 平时存好常用签名，审批时一键选用。 </p>
 *
 * <p> 约束：一人可多枚，**默认签名唯一**。唯一性由服务端在同一事务里保证
 * （{@code UserSignPresetServiceImpl#setDefault} 先清后置），不依赖前端。 </p>
 *
 * <p> 删除用逻辑删（{@code delFlag=2}）：签名图片可能已被历史签名记录引用，
 * 物理删会留下取不到图的记录。 </p>
 *
 * @author 二开
 */
@Data
public class UserSignPreset implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 启用 */
    public static final String STATUS_ENABLED = "1";
    /** 停用 */
    public static final String STATUS_DISABLED = "0";
    /** 是默认 */
    public static final String DEFAULT_YES = "1";
    /** 非默认 */
    public static final String DEFAULT_NO = "0";
    /** 逻辑删除 */
    public static final String DEL_YES = "2";
    /** 未删除 */
    public static final String DEL_NO = "0";

    /** 主键 */
    private String id;

    /** 用户ID */
    private String userId;

    /** 签名名称（如"常用签名"） */
    private String name;

    /** 签名图片文件ID */
    private String fileId;

    /** 是否默认，0-否，1-是 */
    private String isDefault;

    /** 状态，0-停用，1-启用 */
    private String status;

    /** 删除标识，0-存在，2-删除 */
    private String delFlag;

    /** 创建者 */
    private String createId;

    /** 创建时间 */
    private Date createTime;

    /** 更新者 */
    private String updateId;

    /** 更新时间 */
    private Date updateTime;
}
