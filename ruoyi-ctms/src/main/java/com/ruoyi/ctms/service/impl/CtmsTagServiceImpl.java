package com.ruoyi.ctms.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsTag;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsTagMapper;
import com.ruoyi.ctms.service.ICtmsTagService;

/**
 * <p> 合同标签字典服务实现（2.0 B3 任务 4.5）。 </p>
 *
 * <p> <b>自动/手动关联的唯一落点</b>：{@code t_ctms_contract_tag} 的复合主键是
 * {@code (contract_id, tag_id)}，也就是说"同一个标签挂到同一份合同上"物理上只能有一行，
 * 因此不存在"手动行 + 自动行各一行"的可能 —— 规格要求的
 * 「手动添加的标签 MUST NOT 被自动同步移除」只能靠<b>保留那一行、不改写它的 auto 标记</b>实现。 </p>
 *
 * <p> 由此得到本类的取舍（规格只要求"手动项不被移除"，细节由这里定）： </p>
 * <ol>
 *   <li> 同步时先 {@code deleteAutoContractTags} —— <b>只删 {@code auto='1'} 的行</b>，
 *        手动行（{@code auto='0'}）天然不受影响； </li>
 *   <li> 插入自动关联前先读一次现有标签的 {@code auto} 标记：若目标标签已经以<b>手动</b>身份挂在
 *        该合同上，则<b>跳过</b>这条自动关联（既不删除也不把它改写成自动）——
 *        这就是「手动同名标签不被移除」的落点； </li>
 *   <li> 若目标标签原本是 {@code auto='1'}，先删后插即可（等价于刷新关联时间）。 </li>
 * </ol>
 *
 * <p> 第 2 条<b>不依赖 {@code insert ignore}</b>：虽然 {@code insertContractTag} 大概率是
 * {@code insert ignore}，但"忽略"只保证不报错、不保证语义可读；显式跳过让"手动优先"成为
 * 可断言的行为，而不是某个 SQL 写法的副产物。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsTagServiceImpl implements ICtmsTagService
{
    /** 自动创建标签的色板（新建标签颜色 = 已有标签总数 % 5）。 */
    private static final String[] COLOR_PALETTE = {
            "#409eff", "#67c23a", "#e6a23c", "#f56c6c", "#909399" };

    /** 框架合同自动标签名（规格：自身为框架、或自身是某个框架的子合同时附加）。 */
    public static final String FRAMEWORK_TAG_NAME = "框架合同";

    /** 关联行标记：自动。 */
    public static final String AUTO_FLAG_AUTO = "1";

    /** 关联行标记：手动。 */
    public static final String AUTO_FLAG_MANUAL = "0";

    /** 内置标签标记：默认非内置。 */
    private static final String BUILTIN_FLAG_OFF = "0";

    /** 无登录上下文时的创建人用户ID兜底。 */
    private static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的创建人登录名兜底。 */
    private static final String DEFAULT_USERNAME = "system";

    @Autowired
    private CtmsTagMapper tagMapper;

    @Autowired
    private CtmsContractMapper contractMapper;

    @Override
    public List<CtmsTag> selectTagList(CtmsTag query)
    {
        return tagMapper.selectTagList(query);
    }

    @Override
    public CtmsTag selectTagById(String id)
    {
        return tagMapper.selectTagById(id);
    }

    @Override
    public void insertTag(CtmsTag tag)
    {
        if (tag == null)
        {
            throw new ServiceException("标签名称不能为空");
        }
        String name = trimToNull(tag.getName());
        if (name == null)
        {
            throw new ServiceException("标签名称不能为空");
        }
        tag.setName(name);
        if (tagMapper.selectTagByName(name) != null)
        {
            throw new ServiceException("标签名称已存在");
        }
        if (isBlank(tag.getId()))
        {
            // 主键由服务端生成（DEV-ENV 6.38：请求体里的 id 会被静默忽略）
            tag.setId(IdUtils.fastSimpleUUID());
        }
        if (isBlank(tag.getColor()))
        {
            tag.setColor(pickColor());
        }
        if (isBlank(tag.getBuiltin()))
        {
            tag.setBuiltin(BUILTIN_FLAG_OFF);
        }
        tag.setCreateId(currentUserId());
        tag.setCreateBy(currentUsername());
        tag.setCreateTime(DateUtils.getNowDate());
        tag.setUpdateId(currentUserId());
        tag.setUpdateBy(currentUsername());
        tag.setUpdateTime(DateUtils.getNowDate());
        tagMapper.insertTag(tag);
    }

    @Override
    public void updateTag(CtmsTag tag)
    {
        if (tag == null || isBlank(tag.getId()))
        {
            throw new ServiceException("标签不存在");
        }
        CtmsTag exist = tagMapper.selectTagById(tag.getId());
        if (exist == null)
        {
            throw new ServiceException("标签不存在");
        }
        String name = trimToNull(tag.getName());
        if (name == null)
        {
            throw new ServiceException("标签名称不能为空");
        }
        CtmsTag same = tagMapper.selectTagByName(name);
        if (same != null && !same.getId().equals(tag.getId()))
        {
            throw new ServiceException("标签名称已存在");
        }
        tag.setName(name);
        if (isBlank(tag.getColor()))
        {
            tag.setColor(exist.getColor());
        }
        if (isBlank(tag.getBuiltin()))
        {
            tag.setBuiltin(exist.getBuiltin());
        }
        tag.setUpdateId(currentUserId());
        tag.setUpdateBy(currentUsername());
        tag.setUpdateTime(DateUtils.getNowDate());
        tagMapper.updateTag(tag);
    }

    @Override
    public void deleteTagById(String id)
    {
        if (isBlank(id))
        {
            throw new ServiceException("标签不存在");
        }
        if (tagMapper.selectTagById(id) == null)
        {
            throw new ServiceException("标签不存在");
        }
        if (contractMapper.countContractReferences(id) > 0)
        {
            throw new ServiceException("标签已被合同引用，无法删除");
        }
        tagMapper.deleteTagById(id);
    }

    @Override
    public List<CtmsTag> selectTagsByContractId(String contractId)
    {
        if (isBlank(contractId))
        {
            return new ArrayList<>();
        }
        List<CtmsTag> tags = tagMapper.selectTagsByContractId(contractId);
        return tags == null ? new ArrayList<CtmsTag>() : tags;
    }

    @Override
    public CtmsTag ensureTag(String name)
    {
        String trimmed = trimToNull(name);
        if (trimmed == null)
        {
            throw new ServiceException("标签名称不能为空");
        }
        CtmsTag exist = tagMapper.selectTagByName(trimmed);
        if (exist != null)
        {
            return exist;
        }
        CtmsTag tag = new CtmsTag();
        tag.setId(IdUtils.fastSimpleUUID());
        tag.setName(trimmed);
        tag.setColor(pickColor());
        tag.setBuiltin(BUILTIN_FLAG_OFF);
        tag.setCreateId(currentUserId());
        tag.setCreateBy(currentUsername());
        tag.setCreateTime(DateUtils.getNowDate());
        tag.setUpdateId(currentUserId());
        tag.setUpdateBy(currentUsername());
        tag.setUpdateTime(DateUtils.getNowDate());
        tagMapper.insertTag(tag);
        return tag;
    }

    @Override
    public void syncAutoTags(String contractId, String type, String isFramework, boolean hasParent)
    {
        if (isBlank(contractId))
        {
            return;
        }
        // ① 先记住"手动关联"的标签ID：同步全程它们必须原样保留（见类注释的取舍 2）
        Set<String> manualTagIds = new HashSet<>();
        List<CtmsTag> current = tagMapper.selectTagsByContractId(contractId);
        if (current != null)
        {
            for (CtmsTag tag : current)
            {
                if (tag != null && AUTO_FLAG_MANUAL.equals(tag.getAuto()) && !isBlank(tag.getId()))
                {
                    manualTagIds.add(tag.getId());
                }
            }
        }
        // ② 只清自动项（手动行 auto='0' 不受影响）
        tagMapper.deleteAutoContractTags(contractId);
        // ③ 计算本次应有的自动标签
        Set<String> targetTagIds = new LinkedHashSet<>();
        String trimmedType = trimToNull(type);
        if (trimmedType != null)
        {
            CtmsTag typeTag = ensureTag(trimmedType);
            if (typeTag != null && !isBlank(typeTag.getId()))
            {
                targetTagIds.add(typeTag.getId());
            }
        }
        if (AUTO_FLAG_AUTO.equals(isFramework) || hasParent)
        {
            CtmsTag frameworkTag = ensureTag(FRAMEWORK_TAG_NAME);
            if (frameworkTag != null && !isBlank(frameworkTag.getId()))
            {
                targetTagIds.add(frameworkTag.getId());
            }
        }
        // ④ 落关联：手动同名标签跳过（保留手动行、不把它改写成自动）
        for (String tagId : targetTagIds)
        {
            if (manualTagIds.contains(tagId))
            {
                continue;
            }
            tagMapper.insertContractTag(contractId, tagId, AUTO_FLAG_AUTO, currentUserId(), currentUsername());
        }
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 按"已有标签总数对色板取模"选色。
     *
     * @return 色板中的颜色
     */
    private String pickColor()
    {
        int total = tagMapper.countTag();
        int index = total % COLOR_PALETTE.length;
        if (index < 0)
        {
            index = 0;
        }
        return COLOR_PALETTE[index];
    }

    /**
     * 当前用户ID（无登录上下文兜底，与既有主数据服务同款写法）。
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            return isBlank(userId) ? DEFAULT_USER_ID : userId;
        }
        catch (Exception e)
        {
            return DEFAULT_USER_ID;
        }
    }

    /**
     * 当前用户登录名快照（无登录上下文兜底）。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return isBlank(username) ? DEFAULT_USERNAME : username;
        }
        catch (Exception e)
        {
            return DEFAULT_USERNAME;
        }
    }

    /**
     * 去空格；空白归一为 null。
     *
     * @param value 值
     * @return 去空格后的值；空白返回 null
     */
    private String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 空白判定。
     *
     * @param value 值
     * @return 为 null 或全空白返回 true
     */
    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }
}
