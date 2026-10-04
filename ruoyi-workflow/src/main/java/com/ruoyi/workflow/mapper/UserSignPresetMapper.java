package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.UserSignPreset;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户预存签名 Mapper
 *
 * @author 二开
 */
public interface UserSignPresetMapper {

    /** 查某用户的预存签名（不含已删除），默认签名排最前 */
    List<UserSignPreset> selectByUserId(@Param("userId") String userId);

    /** 按主键查（含已删除，供权限校验时判断归属） */
    UserSignPreset selectById(@Param("id") String id);

    /** 取某用户的默认签名（启用且未删除）；无则 null */
    UserSignPreset selectDefault(@Param("userId") String userId);

    int insert(UserSignPreset preset);

    int update(UserSignPreset preset);

    /**
     * 把某用户所有记录的「默认」标记清掉。
     *
     * <p> 与置默认动作必须在**同一事务**里，否则并发下会出现两个默认签名。 </p>
     */
    int clearDefault(@Param("userId") String userId);
}
