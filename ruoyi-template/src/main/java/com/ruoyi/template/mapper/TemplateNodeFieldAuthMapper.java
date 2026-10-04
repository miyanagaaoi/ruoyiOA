package com.ruoyi.template.mapper;

import com.ruoyi.template.domain.TemplateNodeFieldAuth;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 节点级字段权限 Mapper
 *
 * @author 二开
 */
public interface TemplateNodeFieldAuthMapper {

    /**
     * 按模板 + 节点查该节点的字段权限（只返回只读的，供服务端校验用）
     *
     * @param templateId 单据模板ID
     * @param taskDefKey 节点定义key
     * @return 只读字段清单
     */
    List<TemplateNodeFieldAuth> selectReadonlyFields(@Param("templateId") String templateId,
                                                     @Param("taskDefKey") String taskDefKey);

    /**
     * 按模板查全部（发布时先删后插，调试用）
     */
    List<TemplateNodeFieldAuth> selectByTemplateId(@Param("templateId") String templateId);

    /**
     * 按模板删除（发布时重建）
     */
    int deleteByTemplateId(@Param("templateId") String templateId);

    /**
     * 批量插入（发布时重建）
     */
    int batchInsert(@Param("list") List<TemplateNodeFieldAuth> list);
}
