package com.ruoyi.workflow.sign.service;

import com.ruoyi.workflow.domain.UserSignPreset;

import java.util.List;

/**
 * <p> 用户预存签名服务（PRD 8.4 / AC-29、AC-30） </p>
 *
 * <p> 所有方法都只操作**当前登录用户**自己的记录：越权由服务端拦住，
 * 不依赖前端只展示自己的数据。 </p>
 *
 * @author 二开
 */
public interface IUserSignPresetService {

    /** 当前用户的预存签名（默认签名排最前） */
    List<UserSignPreset> listMine();

    /** 取当前用户的默认签名；无则 null */
    UserSignPreset getMyDefault();

    /**
     * 新增一枚预存签名。
     *
     * <p> {@code isDefault=1} 时会在同一事务里先清掉其它默认 —— **默认唯一由服务端保证**。 </p>
     *
     * @return 落库后的记录
     */
    UserSignPreset save(UserSignPreset preset);

    /** 改名 / 停用启用（只允许改自己的） */
    UserSignPreset update(UserSignPreset preset);

    /** 设为默认（同一事务内先清后置，保证唯一） */
    UserSignPreset setDefault(String id);

    /** 逻辑删除（只允许删自己的） */
    void remove(String id);
}
