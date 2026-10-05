package com.ruoyi.workflow.related.service;

import java.util.List;

/**
 * <p> 「关联审批」控件的配置读取与单据号解析（2.0 B1 §7） </p>
 *
 * <p> 拆出接口是为了让守卫（security 侧）与查询接口共用同一套口径：
 * 候选模板清单只从 {@code t_template_related_approval} 读，单据号只在一处解析。 </p>
 *
 * @author 二开
 */
public interface IRelatedApprovalConfigService {

    /** 模板上配置了「关联审批」控件的字段 __vModel__ 清单 */
    List<String> listConfiguredFields(String templateId);

    /**
     * 解析"单据号"（AC-54 要求落库单据号快照）。
     *
     * <p> 口径：动态表单里的流水号控件的值 → 兜底用调用方给的备用值（如 my_draft.biz_title）
     * → 再兜底用短ID。**不编造**：拿不到就明确显示短ID，便于排查。 </p>
     *
     * @param businessId 单据业务ID
     * @param fallback   备用值（可空）
     */
    String resolveBusinessNo(String businessId, String fallback);
}
