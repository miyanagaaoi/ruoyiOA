package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 计量单位 {@code t_ctms_uom}（2.0 B3 §3.3 物料域主数据）。 </p>
 *
 * <p> 编码全局唯一（停用项也占号）；{@code decimals} 取值范围 0 ~ 4 由应用层校验，
 * DB 层不建 CHECK。 </p>
 *
 * @author 二开
 */
public class CtmsUom extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID） */
    private String id;

    /** 单位编码（全局唯一） */
    private String code;

    /** 单位名称 */
    private String name;

    /** 小数位（0 ~ 4，null 落库前归一为 2） */
    private Integer decimals;

    /** 启用标志：1-启用 0-停用 */
    private String enableFlag;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID */
    private String updateId;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Integer getDecimals()
    {
        return decimals;
    }

    public void setDecimals(Integer decimals)
    {
        this.decimals = decimals;
    }

    public String getEnableFlag()
    {
        return enableFlag;
    }

    public void setEnableFlag(String enableFlag)
    {
        this.enableFlag = enableFlag;
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
        return "CtmsUom{id=" + id + ", code=" + code + ", name=" + name + ", decimals=" + decimals
                + ", enableFlag=" + enableFlag + ", createId=" + createId + ", updateId=" + updateId + "}";
    }
}
