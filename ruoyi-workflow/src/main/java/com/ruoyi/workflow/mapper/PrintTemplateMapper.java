package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.PrintTemplate;

import java.util.List;

/**
 * <p> 打印模板 Mapper（表 t_template_print_template） </p>
 *
 * <p> 注意：放在 {@code com.ruoyi.workflow.mapper} 下是为了被全局 MapperScan 扫到，
 * 与既有 Mapper 保持同一约定。 </p>
 *
 * @author 二开
 */
public interface PrintTemplateMapper {

    /** 按主键查询（未删除） */
    PrintTemplate selectById(String id);

    /** 按单据模板ID查询已启用的打印模板列表 */
    List<PrintTemplate> selectByTemplateId(String templateId);

    /** 查询列表 */
    List<PrintTemplate> selectList(PrintTemplate query);

    /** 新增 */
    int insert(PrintTemplate printTemplate);

    /** 更新 */
    int update(PrintTemplate printTemplate);

    /** 逻辑删除 */
    int deleteById(PrintTemplate printTemplate);
}
