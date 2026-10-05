package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 附件挂载元数据对象，对应表 {@code t_ctms_attachment}（15 列；DDL 见
 * {@code sql/二开-合同台账.sql} 第 416~436 行）。 </p>
 *
 * <p> <b>本类只承载"业务对象挂载"语义</b>：文件的字节流、随机命名、目录分片与取回链路
 * 全部由平台 {@code ruoyi-file}（{@code FileUploadUtils} + {@code /profile/**} 静态映射）负责
 * （design D-5）。这里存的是"哪个对象的哪个文件"：{@code objectType} + {@code objectId}
 * 是多态定位，{@code contractId} 只是合同侧对象的便捷列（单据附件为 null）。 </p>
 *
 * <p> <b>不加多态 FK</b>：{@code object_type} 指向的对象表因类型而异，无法用一个外键表达，
 * 所以 DDL 侧只建 {@code (object_type, object_id)} 复合索引，对象存在性由服务层
 * {@code CtmsAttachmentRules} 注册表 + 各域服务校验（design D-5 落地要点）。 </p>
 *
 * <p> 删除是<b>软删除</b>（{@code delFlag='1'}）：元数据保留用于审计，下载接口按
 * {@code del_flag='0'} 过滤，因此删除后"下载接口不再返回该文件"（任务 6.3）。 </p>
 *
 * @author 二开
 */
public class CtmsAttachment extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 删除标记：未删除 */
    public static final String DEL_FLAG_NORMAL = "0";

    /** 删除标记：已删除 */
    public static final String DEL_FLAG_DELETED = "1";

    /** 对象类型：合同（本组唯一"已注册且可校验存在性"的业务对象，见 CtmsAttachmentRules） */
    public static final String OBJECT_TYPE_CONTRACT = "contract";

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID，由服务层生成） */
    private String id;

    /** 合同ID（可空：单据附件为 NULL；合同附件与 object_id 同值） */
    private String contractId;

    /** 原始文件名（白名单后缀校验的对象，含后缀） */
    private String fileName;

    /** 存储相对路径（ruoyi-file 返回的 {@code /profile/upload/...}，前端拼 VUE_APP_BASE_API 取回） */
    private String storedPath;

    /** 内容类型（可空：multipart 未带 Content-Type 时为空） */
    private String contentType;

    /** 字节数（上限 20MB，服务层用 CtmsAttachmentRules.MAX_SIZE_BYTES 判定） */
    private Long sizeBytes;

    /** 软删除标志：0-未删除 1-已删除 */
    private String delFlag;

    /** 对象类型（多态，见 {@link #OBJECT_TYPE_CONTRACT}） */
    private String objectType;

    /** 对象标识（多态，与 objectType 成对） */
    private String objectId;

    /** 上传人用户ID（可空：上传路径多，含 B4 单据附件） */
    private String createId;

    /** 更新人用户ID（附件元数据无更新语义，删除用 delFlag，保留列） */
    private String updateId;

    /**
     * 逻辑删除的布尔视图（<b>非持久化</b>）。
     *
     * <p> 前端与验收脚本读 {@code deleted} 比读 {@code delFlag} 更直观；
     * 但字段名<b>不能</b>叫 {@code deleted}，否则 Jackson 会多出一个与 {@code delFlag} 争义的属性。
     * 与 {@code CtmsContract.delFlag} 一样，标志位一律用 {@code char(1)} 落库。 </p>
     *
     * @return 已删除返回 true
     */
    public boolean isDeleted()
    {
        return DEL_FLAG_DELETED.equals(delFlag);
    }

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getContractId()
    {
        return contractId;
    }

    public void setContractId(String contractId)
    {
        this.contractId = contractId;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFileName(String fileName)
    {
        this.fileName = fileName;
    }

    public String getStoredPath()
    {
        return storedPath;
    }

    public void setStoredPath(String storedPath)
    {
        this.storedPath = storedPath;
    }

    public String getContentType()
    {
        return contentType;
    }

    public void setContentType(String contentType)
    {
        this.contentType = contentType;
    }

    public Long getSizeBytes()
    {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes)
    {
        this.sizeBytes = sizeBytes;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getObjectType()
    {
        return objectType;
    }

    public void setObjectType(String objectType)
    {
        this.objectType = objectType;
    }

    public String getObjectId()
    {
        return objectId;
    }

    public void setObjectId(String objectId)
    {
        this.objectId = objectId;
    }

    public String getCreateId()
    {
        return createId;
    }

    public void setCreateId(String createId)
    {
        this.createId = createId;
    }

    public String getUpdateId()
    {
        return updateId;
    }

    public void setUpdateId(String updateId)
    {
        this.updateId = updateId;
    }

    @Override
    public String toString()
    {
        return "CtmsAttachment{" +
                "id='" + id + '\'' +
                ", contractId='" + contractId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", storedPath='" + storedPath + '\'' +
                ", contentType='" + contentType + '\'' +
                ", sizeBytes=" + sizeBytes +
                ", delFlag='" + delFlag + '\'' +
                ", objectType='" + objectType + '\'' +
                ", objectId='" + objectId + '\'' +
                ", createId='" + createId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateId='" + updateId + '\'' +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                '}';
    }
}
