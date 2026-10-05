package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 字段级变更历史对象，对应表 {@code t_ctms_change_log}（17 列，B3/B4 共用；DDL 见
 * {@code sql/二开-合同台账.sql} 第 390~413 行）。 </p>
 *
 * <p> 本表只追加不更新：{@code updateId} / {@code updateTime} 是为与全局审计块对齐而保留的空列，
 * 业务侧不写。时间线按 {@code create_time} 倒序读取。 </p>
 *
 * <p> {@code contractId} 可空：B4 单据侧日志不写该列，靠 {@code objectType} / {@code objectId}
 * 多态定位；因此列表查询对这三者都是「传了才过滤」。 </p>
 *
 * <p> {@code source} 取值 {@code manual}（手工）/ {@code auto}（系统自动重算），
 * 自动来源用于区分「用户改的」与「服务端算出来的」。操作人缺失时前端展示占位符「—」，本层不做兜底。 </p>
 *
 * @author 二开
 */
public class CtmsChangeLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 来源：手工 */
    public static final String SOURCE_MANUAL = "manual";

    /** 来源：自动（系统重算） */
    public static final String SOURCE_AUTO = "auto";

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID，由服务层生成） */
    private String id;

    /** 合同ID（可空：B4 单据侧日志不写该列） */
    private String contractId;

    /** 字段名（含特殊名 _summary / _items / _tags / _origin / status / 附件 / deleted，均不超过 64 字符） */
    private String fieldName;

    /** 旧值（可空：新增场景旧值为空） */
    private String oldValue;

    /** 新值（可空：删除/清空场景新值为空） */
    private String newValue;

    /** 备注 / 原因（可空） */
    private String note;

    /** 来源：manual-手动 auto-自动 */
    private String source;

    /** 操作人用户ID（可空：历史数据操作人为空不得报错） */
    private String operatorId;

    /** 操作人姓名快照（可空） */
    private String operatorName;

    /** 对象类型（多态定位，与 objectId 成对） */
    private String objectType;

    /** 对象标识（多态定位，与 objectType 成对） */
    private String objectId;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID（只追加不更新，保留列） */
    private String updateId;

    /* ==================== 非持久化字段（查询条件） ==================== */

    /** 列表最多取多少条（时间线只展示最近若干条；为 null 表示不限） */
    private Integer limit;

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

    public String getFieldName()
    {
        return fieldName;
    }

    public void setFieldName(String fieldName)
    {
        this.fieldName = fieldName;
    }

    public String getOldValue()
    {
        return oldValue;
    }

    public void setOldValue(String oldValue)
    {
        this.oldValue = oldValue;
    }

    public String getNewValue()
    {
        return newValue;
    }

    public void setNewValue(String newValue)
    {
        this.newValue = newValue;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public String getSource()
    {
        return source;
    }

    public void setSource(String source)
    {
        this.source = source;
    }

    public String getOperatorId()
    {
        return operatorId;
    }

    public void setOperatorId(String operatorId)
    {
        this.operatorId = operatorId;
    }

    public String getOperatorName()
    {
        return operatorName;
    }

    public void setOperatorName(String operatorName)
    {
        this.operatorName = operatorName;
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

    public Integer getLimit()
    {
        return limit;
    }

    public void setLimit(Integer limit)
    {
        this.limit = limit;
    }

    @Override
    public String toString()
    {
        return "CtmsChangeLog{" +
                "id='" + id + '\'' +
                ", contractId='" + contractId + '\'' +
                ", fieldName='" + fieldName + '\'' +
                ", oldValue='" + oldValue + '\'' +
                ", newValue='" + newValue + '\'' +
                ", note='" + note + '\'' +
                ", source='" + source + '\'' +
                ", operatorId='" + operatorId + '\'' +
                ", operatorName='" + operatorName + '\'' +
                ", objectType='" + objectType + '\'' +
                ", objectId='" + objectId + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", limit=" + limit +
                '}';
    }
}
