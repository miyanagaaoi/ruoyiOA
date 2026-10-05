package com.ruoyi.template.mapper;

import com.ruoyi.template.domain.TemplateFlowAdmin;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模板流程管理员 Mapper（t_template_flow_admin）
 *
 * <p> 2.0（B1 §3.1/§3.4，REQ-PERM-005）。保存走"全量替换"。 </p>
 *
 * @author 二开
 */
public interface TemplateFlowAdminMapper {

    /** 按模板查询流程管理员 */
    List<TemplateFlowAdmin> selectByTemplateId(String templateId);

    /** 按模板统计个数 */
    int countByTemplateId(String templateId);

    /** 判断某账号是否是该模板的流程管理员（返回命中条数，0 表示不是） */
    int countByTemplateIdAndUserId(@Param("templateId") String templateId, @Param("userId") String userId);

    /** 按模板删除全部（全量替换语义） */
    int deleteByTemplateId(String templateId);

    /** 批量插入（调用方保证 list 非空） */
    int batchInsert(@Param("list") List<TemplateFlowAdmin> list);
}
