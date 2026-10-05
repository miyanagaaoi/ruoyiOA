package com.ruoyi.template.mapper;

import com.ruoyi.template.domain.TemplateRelatedApproval;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p> 关联审批控件候选模板 Mapper（2.0 B1 §7） </p>
 *
 * @author 二开
 */
public interface TemplateRelatedApprovalMapper {

    /** 列出模板下所有控件的候选配置（供"关联审批控件候选模板"审计/反查用） */
    List<TemplateRelatedApproval> selectByTemplateId(@Param("templateId") String templateId);

    /** 取某个控件允许关联的模板ID清单（服务端复核范围用） */
    List<String> selectAllowTemplateIds(@Param("templateId") String templateId,
                                        @Param("fieldVmodel") String fieldVmodel);

    /** 清空某个模板的全部候选配置（全量替换的第一步） */
    int deleteByTemplateId(@Param("templateId") String templateId);

    /** 批量写入 */
    int batchInsert(@Param("rows") List<TemplateRelatedApproval> rows);

    /**
     * 反查：哪些模板把 {@code allowTemplateId} 配成了候选（改模板启停/删除前的影响面自查）。
     */
    List<TemplateRelatedApproval> selectByAllowTemplateId(@Param("allowTemplateId") String allowTemplateId);
}
