package com.ruoyi.workflow.sign.service.impl;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.workflow.domain.UserSignPreset;
import com.ruoyi.workflow.mapper.UserSignPresetMapper;
import com.ruoyi.workflow.sign.service.IUserSignPresetService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> 用户预存签名服务实现（PRD 8.4 / AC-29、AC-30） </p>
 *
 * <p> 两条不变量由**服务端**保证，不依赖前端： </p>
 * <ol>
 *   <li> <b>只能操作自己的记录</b> —— 每次按主键取出来都要校验 {@code userId}； </li>
 *   <li> <b>默认签名唯一</b> —— 置默认时在同一事务里先清后置。 </li>
 * </ol>
 *
 * @author 二开
 */
@Slf4j
@Service
public class UserSignPresetServiceImpl implements IUserSignPresetService {

    @Autowired
    private UserSignPresetMapper userSignPresetMapper;

    @Override
    public List<UserSignPreset> listMine() {
        List<UserSignPreset> list = userSignPresetMapper.selectByUserId(SecurityUtils.getUserId());
        return list == null ? new ArrayList<>() : list;
    }

    @Override
    public UserSignPreset getMyDefault() {
        return userSignPresetMapper.selectDefault(SecurityUtils.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSignPreset save(UserSignPreset preset) {
        if (preset == null || StringUtils.isBlank(preset.getFileId())) {
            throw new BaseException("请先采集或上传签名图片");
        }
        String userId = SecurityUtils.getUserId();
        preset.setId(IdUtils.fastSimpleUUID());
        preset.setUserId(userId);
        preset.setDelFlag(UserSignPreset.DEL_NO);
        preset.setStatus(UserSignPreset.STATUS_ENABLED);
        preset.setCreateId(userId);

        // 第一枚自动成为默认：否则用户存完还得再点一次「设为默认」才能用上
        boolean first = CollectionUtils.isEmpty(listMine());
        if (first || UserSignPreset.DEFAULT_YES.equals(preset.getIsDefault())) {
            userSignPresetMapper.clearDefault(userId);
            preset.setIsDefault(UserSignPreset.DEFAULT_YES);
        } else {
            preset.setIsDefault(UserSignPreset.DEFAULT_NO);
        }
        userSignPresetMapper.insert(preset);
        return userSignPresetMapper.selectById(preset.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSignPreset update(UserSignPreset preset) {
        if (preset == null || StringUtils.isBlank(preset.getId())) {
            throw new BaseException("签名ID为空");
        }
        UserSignPreset existing = requireMine(preset.getId());

        UserSignPreset upd = new UserSignPreset();
        upd.setId(existing.getId());
        upd.setUpdateId(SecurityUtils.getUserId());
        if (StringUtils.isNotBlank(preset.getName())) {
            upd.setName(preset.getName());
        }
        if (StringUtils.isNotBlank(preset.getStatus())) {
            upd.setStatus(preset.getStatus());
        }
        userSignPresetMapper.update(upd);

        // 把「默认签名」停用掉时，必须同时摘掉默认标记：
        // selectDefault 要求 status='1'，否则会留下一个"有默认却取不到"的悬空状态
        if (UserSignPreset.STATUS_DISABLED.equals(upd.getStatus())
                && UserSignPreset.DEFAULT_YES.equals(existing.getIsDefault())) {
            UserSignPreset clear = new UserSignPreset();
            clear.setId(existing.getId());
            clear.setIsDefault(UserSignPreset.DEFAULT_NO);
            userSignPresetMapper.update(clear);
        }
        return userSignPresetMapper.selectById(existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSignPreset setDefault(String id) {
        UserSignPreset existing = requireMine(id);
        String userId = SecurityUtils.getUserId();

        // 先清后置必须在同一事务里 —— 否则并发下会出现两条 is_default='1'
        userSignPresetMapper.clearDefault(userId);

        UserSignPreset upd = new UserSignPreset();
        upd.setId(existing.getId());
        upd.setIsDefault(UserSignPreset.DEFAULT_YES);
        // 设为默认的同时确保启用：停用态取不到，"默认"就是假的
        upd.setStatus(UserSignPreset.STATUS_ENABLED);
        upd.setUpdateId(userId);
        userSignPresetMapper.update(upd);

        return userSignPresetMapper.selectById(existing.getId());
    }

    @Override
    public void remove(String id) {
        UserSignPreset existing = requireMine(id);
        // 逻辑删：签名图片可能已被历史签名记录引用，物理删会留下取不到图的记录
        UserSignPreset upd = new UserSignPreset();
        upd.setId(existing.getId());
        upd.setDelFlag(UserSignPreset.DEL_YES);
        upd.setUpdateId(SecurityUtils.getUserId());
        userSignPresetMapper.update(upd);
    }

    /**
     * 取出并校验归属：不存在 / 已删除 / 不是自己的，一律拒绝。
     *
     * <p> 越权防护放在服务端 —— 前端只展示自己的数据不算数（同类要求见 AC-35）。 </p>
     */
    private UserSignPreset requireMine(String id) {
        if (StringUtils.isBlank(id)) {
            throw new BaseException("签名ID为空");
        }
        UserSignPreset preset = userSignPresetMapper.selectById(id);
        if (preset == null || UserSignPreset.DEL_YES.equals(preset.getDelFlag())) {
            throw new BaseException("预存签名不存在或已删除");
        }
        if (!StringUtils.equals(preset.getUserId(), SecurityUtils.getUserId())) {
            log.warn("越权访问他人预存签名：id={} owner={} current={}",
                    id, preset.getUserId(), SecurityUtils.getUserId());
            throw new BaseException("只能操作自己的预存签名");
        }
        return preset;
    }
}
