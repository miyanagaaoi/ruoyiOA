package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.PrintTemplate;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 由业务ID反查**单据模板ID**。
     *
     * <p> 单据模板ID **不是**流程定义的 key，也**不一定**是流程变量 ——
     * 它随业务记录落在 {@code t_workflow_todo / t_workflow_done / t_workflow_recycle}
     * 上，所以这里按"待办 → 已办 → 回收站"的顺序取第一个命中的。
     * （踩过：曾用 {@code FlowTaskDto.getProcDefKey()} 当单据模板ID，导致表单服务报"模板ID为空"。） </p>
     */
    String selectTemplateIdByBusinessId(String businessId);

    /** 查询列表 */
    List<PrintTemplate> selectList(PrintTemplate query);

    /** 新增 */
    int insert(PrintTemplate printTemplate);

    /** 更新 */
    int update(PrintTemplate printTemplate);

    /** 逻辑删除 */
    int deleteById(PrintTemplate printTemplate);

    /**
     * 停用同一单据模板下的其它启用模板（保留 keepId 这一套）。
     *
     * <p> 为什么要它：{@code selectByTemplateId} 取的是 {@code order by update_time desc}
     * 的第一条 —— 也就是说"打印时用哪套模板"取决于**谁最近被改过**。
     * 实测后果：新建一套模板后，另一张单据原本能打的签批栏（AC-17/AC-33）悄悄没了。
     * 所以"同一单据模板下只有一套启用"必须是**服务端不变量**，不能靠界面自觉。 </p>
     *
     * @param templateId 单据模板ID
     * @param keepId     要保留的那一套（新启用/新保存的那套）
     */
    int disableOthers(@Param("templateId") String templateId, @Param("keepId") String keepId);
}
