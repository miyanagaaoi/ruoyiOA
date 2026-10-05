package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsTag;

/**
 * <p> 合同标签字典与「合同↔标签」关联的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsTagMapper.xml}。 </p>
 *
 * <p> <b>契约要点</b>： </p>
 * <ul>
 *   <li> 标签名称全局唯一、<b>没有 del_flag</b>（删除标签的关联行由级联外键清理），
 *        所以 {@link #selectTagByName} 不需要过滤停用行； </li>
 *   <li> 自动标签同步的写法是「先 {@link #deleteAutoContractTags} 清掉本合同的自动项、
 *        再按需 {@link #insertContractTag} 写回」，手工项不会被清；
 *        因此 {@link #insertContractTag} 用 {@code insert ignore} 语义，
 *        「再加一次已存在的关联」不报主键冲突； </li>
 *   <li> {@link #selectTagsByContractId} 是连表查询，会把关联表的 {@code auto}
 *        一并填进 {@code CtmsTag.auto}（非持久化字段），供「只清自动项」的判断与前端展示； </li>
 *   <li> {@link #countTag} 供「新建标签颜色按已有标签总数对色板取模」使用。 </li>
 * </ul>
 *
 * @author 二开
 */
public interface CtmsTagMapper
{
    /**
     * 标签列表（name 模糊、builtin 精确；传了才过滤）
     *
     * @param query 查询条件
     * @return 标签集合
     */
    List<CtmsTag> selectTagList(CtmsTag query);

    /**
     * 按主键查标签
     *
     * @param id 标签ID
     * @return 标签；不存在返回 null
     */
    CtmsTag selectTagById(@Param("id") String id);

    /**
     * 按名称查标签（自动附加前先按名称找，找不到才新建）
     *
     * @param name 标签名称
     * @return 标签；不存在返回 null
     */
    CtmsTag selectTagByName(@Param("name") String name);

    /**
     * 统计标签总数（新建标签选色用：总数对色板长度取模）
     *
     * @return 标签总数
     */
    int countTag();

    /**
     * 新增标签（id 由服务层生成）
     *
     * @param tag 标签
     * @return 影响行数
     */
    int insertTag(CtmsTag tag);

    /**
     * 修改标签（名称、颜色、内置标志与审计列）
     *
     * @param tag 标签
     * @return 影响行数
     */
    int updateTag(CtmsTag tag);

    /**
     * 按主键物理删除标签（引用守卫在服务层，用
     * {@code CtmsContractMapper#countContractReferences} 判定）
     *
     * @param id 标签ID
     * @return 影响行数
     */
    int deleteTagById(@Param("id") String id);

    /**
     * 查某合同挂载的全部标签（连表带出关联表的 {@code auto}）
     *
     * @param contractId 合同ID
     * @return 标签集合（含 auto 标记）
     */
    List<CtmsTag> selectTagsByContractId(@Param("contractId") String contractId);

    /**
     * 新增「合同↔标签」关联（重复插入同一对时静默忽略，不报主键冲突）
     *
     * @param contractId 合同ID
     * @param tagId 标签ID
     * @param auto 是否自动标签："1" 自动 / "0" 手工
     * @param createId 创建人用户ID
     * @param createBy 创建人登录名快照
     * @return 影响行数（被忽略时返回 0）
     */
    int insertContractTag(@Param("contractId") String contractId, @Param("tagId") String tagId,
                          @Param("auto") String auto, @Param("createId") String createId,
                          @Param("createBy") String createBy);

    /**
     * 解除一条「合同↔标签」关联（手工移除标签用）
     *
     * @param contractId 合同ID
     * @param tagId 标签ID
     * @return 影响行数
     */
    int deleteContractTag(@Param("contractId") String contractId, @Param("tagId") String tagId);

    /**
     * 只清理某合同的<b>自动</b>标签关联（手工项保留，规格强制要求）
     *
     * @param contractId 合同ID
     * @return 影响行数
     */
    int deleteAutoContractTags(@Param("contractId") String contractId);
}
