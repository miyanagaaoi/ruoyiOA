package com.ruoyi.kbs.service.impl;

import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.kbs.domain.KbsFavorite;
import com.ruoyi.kbs.domain.KbsFavoriteGroup;
import com.ruoyi.kbs.domain.qo.KbsFavoriteQo;
import com.ruoyi.kbs.domain.vo.KbsFavoriteGroupVo;
import com.ruoyi.kbs.mapper.KbsFavoriteMapper;
import com.ruoyi.kbs.service.IKbsFavoriteGroupService;
import com.ruoyi.kbs.service.IKbsFavoriteService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库收藏Service业务层处理
 *
 * @author wocurr.com
 */
@Slf4j
@Service
public class KbsFavoriteServiceImpl implements IKbsFavoriteService {
    @Autowired
    private KbsFavoriteMapper kbsFavoriteMapper;
    @Autowired
    private IKbsFavoriteGroupService kbsFavoriteGroupService;

    /**
     * 查询知识库收藏列表
     *
     * @param kbsFavorite 知识库收藏
     * @return 知识库收藏
     */
    @Override
    public List<KbsFavoriteGroupVo> listKbsFavorite(KbsFavorite kbsFavorite) {
        kbsFavorite.setUserId(SecurityUtils.getUserId());
        List<KbsFavorite> kbsFavorites = kbsFavoriteMapper.selectKbsFavoriteList(kbsFavorite);
        if (CollectionUtils.isEmpty(kbsFavorites)) {
            return Collections.emptyList();
        }
        // 按组ID分组
        // ⚠ 「未分组收藏」是合法状态（group_id 为空），而 Collectors.groupingBy 的分类键为
        //    null 时 JDK 必然抛 "element cannot be mapped to a null key"，会把整个收藏页打挂；
        //    同时 null 混进 groupIds 还会让下面的 IN 查询带上 null。
        //    这里把未分组收藏单独归到「未分组」，既不丢数据也不再崩。
        Map<String, List<KbsFavorite>> kbsFavoriteListMap = kbsFavorites.stream()
                .filter(favorite -> StringUtils.isNotEmpty(favorite.getGroupId()))
                .collect(Collectors.groupingBy(KbsFavorite::getGroupId));
        // 收藏组（空组不下发到 IN 查询）
        List<String> groupIds = kbsFavorites.stream()
                .map(KbsFavorite::getGroupId)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        List<KbsFavoriteGroup> kbsFavoriteGroups = CollectionUtils.isEmpty(groupIds)
                ? Collections.emptyList()
                : kbsFavoriteGroupService.listKbsFavoriteGroupByIds(groupIds);
        List<KbsFavoriteGroupVo> kbsFavoriteGroupVos = new ArrayList<>();
        for (KbsFavoriteGroup group : kbsFavoriteGroups) {
            List<KbsFavorite> kbsFavoritesByGroupId = kbsFavoriteListMap.get(group.getId());
            if (CollectionUtils.isEmpty(kbsFavoritesByGroupId)) {
                continue;
            }
            kbsFavoriteGroupVos.add(buildKbsFavoriteGroupVo(group, kbsFavoritesByGroupId));
        }
        // 未分组的收藏仍要展示出来，否则用户会以为收藏丢了
        List<KbsFavorite> ungrouped = kbsFavorites.stream()
                .filter(favorite -> StringUtils.isEmpty(favorite.getGroupId()))
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(ungrouped)) {
            KbsFavoriteGroup placeholder = new KbsFavoriteGroup();
            placeholder.setId("");
            placeholder.setName("未分组");
            kbsFavoriteGroupVos.add(buildKbsFavoriteGroupVo(placeholder, ungrouped));
        }
        return kbsFavoriteGroupVos;
    }

    /**
     * 构建收藏组信息（包含收藏列表）
     *
     * @param group
     * @param kbsFavoritesByGroupId
     * @return
     */
    private KbsFavoriteGroupVo buildKbsFavoriteGroupVo(KbsFavoriteGroup group, List<KbsFavorite> kbsFavoritesByGroupId) {
        KbsFavoriteGroupVo vo = new KbsFavoriteGroupVo();
        vo.setGroupId(group.getId());
        vo.setGroupName(group.getName());
        // 按创建时间倒序排序
        kbsFavoritesByGroupId.sort(Comparator.comparing(BaseEntity::getCreateTime).reversed());
        vo.setFavorites(kbsFavoritesByGroupId);
        return vo;
    }

    /**
     * 新增知识库收藏
     *
     * @param kbsFavorite 知识库收藏
     * @return 结果
     */
    @Override
    public int saveKbsFavorite(KbsFavorite kbsFavorite) {
        KbsFavorite queryParam = new KbsFavorite();
        queryParam.setObjectId(kbsFavorite.getObjectId());
        queryParam.setGroupId(kbsFavorite.getGroupId());
        List<KbsFavorite> kbsFavorites = kbsFavoriteMapper.selectKbsFavoriteList(queryParam);
        if (CollectionUtils.isNotEmpty(kbsFavorites)) {
            return 1;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isBlank(kbsFavorite.getId())) {
            kbsFavorite.setId(IdUtils.fastSimpleUUID());
        }
        kbsFavorite.setUserId(loginUser.getUserId());
        kbsFavorite.setCreateId(loginUser.getUserId());
        kbsFavorite.setCreateBy(loginUser.getUser().getNickName());
        kbsFavorite.setCreateTime(DateUtils.getNowDate());
        return kbsFavoriteMapper.insertKbsFavorite(kbsFavorite);
    }

    /**
     * 根据组ID批量删除知识收藏
     *
     * @param ids 组ID集合
     * @return 结果
     */
    @Override
    public int deleteKbsFavoriteByGroupIds(String[] ids) {
        return kbsFavoriteMapper.deleteKbsFavoriteByGroupIds(ids);
    }

    /**
     * 根据文档ID统计收藏次数
     *
     * @param docId 文档ID
     * @return 结果
     */
    @Override
    public Long statDocumentFavoriteNum(String docId) {
        return kbsFavoriteMapper.statDocumentFavoriteNum(docId);
    }

    /**
     * 根据用户ID查询收藏
     *
     * @param docId 文档ID
     * @return
     */
    @Override
    public KbsFavorite getKbsFavoriteByUserId(String docId) {
        KbsFavoriteQo qo = new KbsFavoriteQo();
        qo.setDocId(docId);
        qo.setUserId(SecurityUtils.getUserId());
        List<KbsFavorite> kbsFavorites = kbsFavoriteMapper.selectKbsFavoriteByUserId(qo);
        if (CollectionUtils.isEmpty(kbsFavorites)) {
            return null;
        }
        return kbsFavorites.get(0);
    }

    /**
     * 取消收藏
     *
     * @param docId 文档ID
     * @return 结果
     */
    @Override
    public int cancelFavoriteByDocUser(String docId) {
        KbsFavoriteQo qo = new KbsFavoriteQo();
        qo.setDocId(docId);
        qo.setUserId(SecurityUtils.getUserId());
        return kbsFavoriteMapper.deleteFavoriteByDocUser(qo);
    }

    @Override
    public int deleteKbsFavoriteIds(List<String> objectIds) {
        if (CollectionUtils.isEmpty(objectIds)) {
            return 0;
        }
        return kbsFavoriteMapper.deleteKbsFavoriteObjectIds(objectIds);
    }
}
