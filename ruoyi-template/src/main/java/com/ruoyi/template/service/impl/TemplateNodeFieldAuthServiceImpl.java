package com.ruoyi.template.service.impl;

import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.template.domain.TemplateNodeFieldAuth;
import com.ruoyi.template.mapper.TemplateNodeFieldAuthMapper;
import com.ruoyi.template.service.ITemplateNodeFieldAuthService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 节点级字段权限 Service 实现
 *
 * @author 二开
 */
@Slf4j
@Service
public class TemplateNodeFieldAuthServiceImpl implements ITemplateNodeFieldAuthService {

    @Autowired
    private TemplateNodeFieldAuthMapper nodeFieldAuthMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildByTemplate(String templateId, List<TemplateNodeFieldAuth> list) {
        if (StringUtils.isBlank(templateId)) {
            return;
        }
        // 发布 = 全量重建：先清掉该模板旧配置，避免改名/删字段后留下悬空规则
        nodeFieldAuthMapper.deleteByTemplateId(templateId);
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (TemplateNodeFieldAuth item : list) {
            item.setTemplateId(templateId);
            if (StringUtils.isBlank(item.getId())) {
                item.setId(IdUtils.fastSimpleUUID());
            }
            if (StringUtils.isBlank(item.getReadonly())) {
                item.setReadonly("1");
            }
            if (StringUtils.isBlank(item.getRequired())) {
                item.setRequired("0");
            }
            if (StringUtils.isBlank(item.getHidden())) {
                item.setHidden("0");
            }
        }
        nodeFieldAuthMapper.batchInsert(list);
        log.info("节点级字段权限已重建：templateId={} 行数={}", templateId, list.size());
    }

    @Override
    public List<String> listReadonlyFields(String templateId, String taskDefKey) {
        // 约定：查不到就返回**空列表**而不是 null —— 调用方（提交校验）不该因为没配置而 NPE，
        // 也不该把"没配置"误当成"全部只读"
        if (StringUtils.isBlank(templateId) || StringUtils.isBlank(taskDefKey)) {
            return new ArrayList<>();
        }
        List<TemplateNodeFieldAuth> rows = nodeFieldAuthMapper.selectReadonlyFields(templateId, taskDefKey);
        if (CollectionUtils.isEmpty(rows)) {
            return new ArrayList<>();
        }
        return rows.stream()
                .map(TemplateNodeFieldAuth::getFieldVmodel)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }
}
