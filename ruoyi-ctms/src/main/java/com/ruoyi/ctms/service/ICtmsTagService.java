package com.ruoyi.ctms.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsTag;

/**
 * <p> 合同标签字典服务（2.0 B3 任务 4.5，规格 {@code specs/ctms/contract-ledger}
 * 的「合同标签与自动标签同步」）。 </p>
 *
 * <p> 口径集中在实现类： </p>
 * <ul>
 *   <li> 标签名称<b>全局唯一</b>（{@code uk_tag_name} 是 DB 层兜底，服务层先查重给可读提示）； </li>
 *   <li> 自动创建标签时颜色按「已有标签总数对色板取模」选定； </li>
 *   <li> 关联行用 {@code auto} 列区分自动（{@code '1'}）与手动（{@code '0'}），
 *        自动同步<b>只清理自动项</b>； </li>
 *   <li> 被合同引用中的标签禁止删除。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface ICtmsTagService
{
    /**
     * 查询标签列表。
     *
     * @param query 查询条件（name 模糊）
     * @return 标签集合
     */
    List<CtmsTag> selectTagList(CtmsTag query);

    /**
     * 按主键查询标签。
     *
     * @param id 标签ID
     * @return 标签；不存在返回 null
     */
    CtmsTag selectTagById(String id);

    /**
     * 新增标签（名称唯一；颜色留空时按标签总数取模选色）。
     *
     * @param tag 标签
     */
    void insertTag(CtmsTag tag);

    /**
     * 修改标签（名称唯一，排除自身）。
     *
     * @param tag 标签
     */
    void updateTag(CtmsTag tag);

    /**
     * 删除标签（被任何合同引用时拒绝）。
     *
     * @param id 标签ID
     */
    void deleteTagById(String id);

    /**
     * 查询某合同上的标签（含 {@code auto} 标记：{@code '1'} 为自动附加）。
     *
     * @param contractId 合同ID
     * @return 标签集合
     */
    List<CtmsTag> selectTagsByContractId(String contractId);

    /**
     * 按名称取标签，不存在则新建（自动标签的落点）。
     *
     * @param name 标签名称
     * @return 已存在或新建的标签
     */
    CtmsTag ensureTag(String name);

    /**
     * <p> 同步合同的<b>自动</b>标签：先只清自动项，再按合同类型与框架属性补自动项。 </p>
     *
     * @param contractId  合同ID
     * @param type        合同类型（非空时附加同名自动标签）
     * @param isFramework 是否框架合同（{@code '1'} 时附加「框架合同」自动标签）
     * @param hasParent   是否为某个框架的子合同（true 时同样附加「框架合同」自动标签）
     */
    void syncAutoTags(String contractId, String type, String isFramework, boolean hasParent);
}
