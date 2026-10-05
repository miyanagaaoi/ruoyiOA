package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 仓库档案 {@code t_ctms_warehouse}（2.0 B3 §3.3 物料域主数据）。 </p>
 *
 * <p> 编码全局唯一；{@code keeperUserId} 仅用于反查仓管员名称，
 * 因此 DDL 上<b>保持可空、只加索引、不加 FK</b>。 </p>
 *
 * @author 二开
 */
public class CtmsWarehouse extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID） */
    private String id;

    /** 仓库编码（全局唯一） */
    private String code;

    /** 仓库名称 */
    private String name;

    /** 仓库地址（可空） */
    private String address;

    /** 仓管员用户ID（可空，仅反查名称） */
    private String keeperUserId;

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

    public String getAddress()
    {
        return address;
    }

    public void setAddress(String address)
    {
        this.address = address;
    }

    public String getKeeperUserId()
    {
        return keeperUserId;
    }

    public void setKeeperUserId(String keeperUserId)
    {
        this.keeperUserId = keeperUserId;
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
        return "CtmsWarehouse{id=" + id + ", code=" + code + ", name=" + name + ", address=" + address
                + ", keeperUserId=" + keeperUserId + ", enableFlag=" + enableFlag
                + ", createId=" + createId + ", updateId=" + updateId + "}";
    }
}
