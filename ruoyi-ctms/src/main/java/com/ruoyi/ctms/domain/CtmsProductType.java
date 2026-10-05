package com.ruoyi.ctms.domain;

import java.util.List;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * <p> 商品类型树 {@code t_ctms_product_type}（2.0 B3 §3.3 物料域主数据）。 </p>
 *
 * <p> 字段<b>逐列对应 DDL</b>：{@code createBy/createTime/updateBy/updateTime/remark/params}
 * 由 {@link BaseEntity} 提供，本类只声明 {@code create_id}/{@code update_id} 这两个
 * 「用户ID」列（DDL 里它们是独立列，不能与登录名快照混用）。 </p>
 *
 * <p> {@code path}/{@code level} 是<b>服务端派生列</b>：新增与改父都由
 * {@code CtmsProductMasterServiceImpl} 按物化路径重算，前端传入值不参与判定。 </p>
 *
 * @author 二开
 */
public class CtmsProductType extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键（应用侧 UUID） */
    private String id;

    /** 上级类型ID（自引用，可空=根） */
    private String parentId;

    /** 类型编码（物料编码前缀来源；可空时由服务端退化为 PT+类型id） */
    private String code;

    /** 类型名称（同父下唯一，由服务端校验） */
    private String name;

    /** 物化路径，形如 /1/5/（下级查询依赖 LIKE 前缀） */
    private String path;

    /** 层级（1 ~ 5，由服务端重算） */
    private Integer level;

    /** 排序号 */
    private Integer sort;

    /** 启用标志：1-启用 0-停用 */
    private String enableFlag;

    /** 创建人用户ID */
    private String createId;

    /** 更新人用户ID */
    private String updateId;

    /**
     * 子类型集合（<b>非持久化字段</b>）：仅用于 {@code /ctms/product-type/tree} 的树形装配，
     * 不参与任何 SQL 列映射。
     */
    private List<CtmsProductType> children;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getParentId()
    {
        return parentId;
    }

    public void setParentId(String parentId)
    {
        this.parentId = parentId;
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

    public String getPath()
    {
        return path;
    }

    public void setPath(String path)
    {
        this.path = path;
    }

    public Integer getLevel()
    {
        return level;
    }

    public void setLevel(Integer level)
    {
        this.level = level;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
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

    public List<CtmsProductType> getChildren()
    {
        return children;
    }

    public void setChildren(List<CtmsProductType> children)
    {
        this.children = children;
    }

    @Override
    public String toString()
    {
        return "CtmsProductType{id=" + id + ", parentId=" + parentId + ", code=" + code
                + ", name=" + name + ", path=" + path + ", level=" + level + ", sort=" + sort
                + ", enableFlag=" + enableFlag + ", createId=" + createId + ", updateId=" + updateId
                + ", children=" + (children == null ? 0 : children.size()) + "}";
    }
}
