package com.ruoyi.template.mapper;

import com.ruoyi.template.domain.TemplateSubmitScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模板可发起范围明细 Mapper（t_template_submit_scope）
 *
 * <p> 2.0（B1 §3.1/§3.2，REQ-PERM-001）。保存走"全量替换"：先按 template_id 删、再批量插。 </p>
 *
 * @author 二开
 */
public interface TemplateSubmitScopeMapper {

    /** 按模板查询明细 */
    List<TemplateSubmitScope> selectByTemplateId(String templateId);

    /**
     * 按多个模板批量查询明细（发起页列表要按范围过滤，避免 N+1 查询）
     *
     * @param templateIds 模板ID集合（调用方保证非空）
     */
    List<TemplateSubmitScope> selectByTemplateIds(@Param("templateIds") List<String> templateIds);

    /** 按模板统计明细条数 */
    int countByTemplateId(String templateId);

    /** 按模板删除全部明细（全量替换语义） */
    int deleteByTemplateId(String templateId);

    /** 批量插入（调用方保证 list 非空；空列表会生成非法 SQL） */
    int batchInsert(@Param("list") List<TemplateSubmitScope> list);
}
