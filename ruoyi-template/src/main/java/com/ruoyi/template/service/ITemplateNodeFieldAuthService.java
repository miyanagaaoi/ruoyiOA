package com.ruoyi.template.service;

import com.ruoyi.template.domain.TemplateNodeFieldAuth;

import java.util.List;

/**
 * 节点级字段权限 Service
 *
 * @author 二开
 */
public interface ITemplateNodeFieldAuthService {

    /**
     * 按模板重建该模板的全部节点字段权限（发布流程时调用）
     *
     * @param templateId 单据模板ID
     * @param list       待写入的行（可为空，表示清空）
     */
    void rebuildByTemplate(String templateId, List<TemplateNodeFieldAuth> list);

    /**
     * 取某节点上被设为「只读」的字段清单（服务端校验用）
     *
     * @return 字段 __vModel__ 列表；无配置时返回空列表（**不返回 null**，调用方不必判空）
     */
    List<String> listReadonlyFields(String templateId, String taskDefKey);
}
