package com.ruoyi.ctms.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 合同标签字典对象，对应表 {@code t_ctms_tag}（10 列，B3 第 4 组；DDL 见
 * {@code sql/二开-合同台账.sql} 第 94~107 行）。 </p>
 *
 * <p> 该表<b>没有 {@code del_flag}</b>：删除标签的引用副作用由
 * {@code t_ctms_contract_tag} 的级联外键承担，因此本类也没有软删除字段。 </p>
 *
 * <p> ⚠ 该表<b>没有 {@code remark} 列</b>，所以 Mapper XML 的 resultMap 与查询列清单里
 * 都不能出现 {@code remark}（基类里虽有该属性，但库里无此列）。 </p>
 *
 * <p> {@code auto} 是<b>非持久化字段</b>：它来自关联表 {@code t_ctms_contract_tag.auto}，
 * 只在「按合同查标签」的连表查询里被填充，用于区分自动标签与手工标签（自动同步只清理自动项）。 </p>
 *
 * @author 二开
 */
public class CtmsTag extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /* ==================== 持久化字段（逐列对应 DDL） ==================== */

    /** 主键（应用侧 UUID，由服务层生成） */
    private String id;

    /** 标签名称（全局唯一） */
    private String name;

    /** 标签颜色（自动创建时按已有标签总数对色板取模选定） */
    private String color;

    /** 是否内置标签：0-否 1-是 */
    private String builtin;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID */
    private String updateId;

    /* ==================== 非持久化字段（来自关联表） ==================== */

    /** 关联类型：0-手工 1-自动（来自 {@code t_ctms_contract_tag.auto}，仅连表查询填充） */
    private String auto;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getColor()
    {
        return color;
    }

    public void setColor(String color)
    {
        this.color = color;
    }

    public String getBuiltin()
    {
        return builtin;
    }

    public void setBuiltin(String builtin)
    {
        this.builtin = builtin;
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

    public String getAuto()
    {
        return auto;
    }

    public void setAuto(String auto)
    {
        this.auto = auto;
    }

    @Override
    public String toString()
    {
        return "CtmsTag{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", color='" + color + '\'' +
                ", builtin='" + builtin + '\'' +
                ", createId='" + createId + '\'' +
                ", updateId='" + updateId + '\'' +
                ", createBy='" + getCreateBy() + '\'' +
                ", createTime=" + getCreateTime() +
                ", updateBy='" + getUpdateBy() + '\'' +
                ", updateTime=" + getUpdateTime() +
                ", auto='" + auto + '\'' +
                '}';
    }
}
