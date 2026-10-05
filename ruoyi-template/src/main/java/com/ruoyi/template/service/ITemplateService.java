package com.ruoyi.template.service;

import java.util.List;
import com.ruoyi.template.domain.Template;
import com.ruoyi.template.module.TemplateDTO;
import com.ruoyi.template.module.TemplateModel;
import com.ruoyi.template.module.TemplateOption;

/**
 * 模板配置Service接口
 * 
 * @author wocurr.com
 */
public interface ITemplateService {
    /**
     * 查询模板配置
     * 
     * @param id 模板配置主键
     * @return 模板配置
     */
    public Template getTemplateById(String id);

    /**
     * 「关联审批」控件允许被关联的模板ID清单（2.0 B1 §7，REQ-FORM-010）。
     *
     * <p> 数据源是 {@code t_template_related_approval}（模板保存时从动态表单 schema 解析落库）。
     * 取不到（控件没配候选）就返回空表 —— 调用方按"没有候选项"处理，
     * 失败方向是**拒绝**而不是放行。 </p>
     *
     * @param templateId   宿主模板ID
     * @param fieldVmodel  控件在表单里的 __vModel__
     */
    List<String> listRelatedApprovalAllowTemplates(String templateId, String fieldVmodel);

    /**
     * 模板上配置了「关联审批」控件的字段 __vModel__ 清单（用于判断哪些字段值要落关联关系）。
     *
     * @param templateId 宿主模板ID
     */
    List<String> listRelatedApprovalFields(String templateId);

    /**
     * 统计有多少模板正指向该版本表单（2.0 B1 §7.10，用于保存表单后的影响面提示）。
     *
     * @param formId 表单版本ID
     */
    int countTemplatesOnForm(String formId);

    /**
     * 统计有多少模板正在用某个表单标识的当前版本（§7.10 提示口径，换版本后仍准确）。
     *
     * @param formKey 表单标识
     */
    int countTemplatesOnFormKey(String formKey);

    /**
     * 查询模板配置
     * @param id
     * @return
     */
    public TemplateDTO getTemplateDTOById(String id); //

    /**
     * 查询模板配置列表
     * 
     * @param template 模板配置
     * @return 模板配置集合
     */
    public List<Template> listTemplate(Template template);

    /**
     * 新增模板配置
     * 
     * @param template 模板配置
     * @return 结果
     */
    public int saveTemplate(TemplateDTO template);

    /**
     * 修改模板配置
     * 
     * @param template 模板配置
     * @return 结果
     */
    public int updateTemplate(TemplateDTO template);

    /**
     * 批量删除模板配置
     * 
     * @param ids 需要删除的模板配置主键集合
     * @return 结果
     */
    public int deleteTemplateByIds(String[] ids);

    /**
     * 更新模板配置状态
     *
     * @param template
     * @return
     */
    int changeEnableFlag(Template template);

    /**
     * 查询新启流程模板列表
     *
     * @return
     */
    List<TemplateModel> listNewStartTemplate();

    /**
     * 查询下拉模板列表
     *
     * @return
     */
    List<TemplateOption> getSelectTemplateList();

    // ==================== 2.0（B1 §3.2/§3.3/§3.4）可发起范围与流程管理员 ====================

    /**
     * 当前登录用户能否发起该模板（不抛异常，供列表过滤与测试使用）。
     *
     * @param templateId 模板ID
     * @return 是否可发起；模板不存在返回 false
     */
    boolean canStartTemplate(String templateId);

    /**
     * 发起前的兜底校验：无权发起时抛 403（业务码 403，前端与
     * {@code tools/authz-check.ps1} 都按响应体的 code 判定）。
     *
     * <p> 必须在**落库之前**调用，且必须在会吞异常的 try 之外调用。 </p>
     *
     * @param templateId 模板ID
     */
    void checkStartPermission(String templateId);

    /**
     * 当前登录用户能否编辑/发布该模板的流程。
     *
     * <p> 规则：系统管理员 → 是；模板指定了流程管理员 → 需在名单内；
     * 未指定 → 模板创建人 或 拥有 {@code workflow:template:edit} 权限者。 </p>
     *
     * @param templateId 模板ID
     * @return 是否有权管理该模板的流程
     */
    boolean canManageFlow(String templateId);

    /**
     * 流程编辑/发布前的授权校验：无授权时抛 403。
     *
     * @param templateId 模板ID
     */
    void checkFlowManagePermission(String templateId);

    /**
     * 按"绑定的简化流程ID"做流程管理授权校验（B1 §3.4）。
     *
     * <p> 发布接口只拿到流程 id；流程已绑定模板时按其流程管理员判定，
     * <b>未绑定任何模板时不做模板级授权</b>（设计器里独立创建的流程仍走既有权限点）——
     * 这样 V1 的存量流程发布行为不变。 </p>
     *
     * @param simpleFlowId t_flow_simple.id
     */
    void checkFlowManageByFlowId(String simpleFlowId);

    /**
     * 按"绑定的简化流程ID"反查模板ID（B1 §3.4）。
     *
     * @param simpleFlowId t_flow_simple.id
     * @return 模板ID；没有模板绑定该流程时返回 null
     */
    String getTemplateIdBySimpleFlowId(String simpleFlowId);

    /**
     * 回写模板与简化流程的绑定（B1 §4.1 建草稿时 / §4.2 发布成功时）。
     *
     * <p> 必须落在**当前启用行**上（原地 UPDATE，与 §1.1 语义一致）。 </p>
     *
     * @param templateId   模板ID
     * @param simpleFlowId 绑定的流程ID
     * @param defKey       流程定义key（建草稿时传 null：此时还没发布，不改动已有 def_key）
     * @param flowMode     流程模式，0-简化流程
     */
    void saveFlowBinding(String templateId, String simpleFlowId, String defKey, String flowMode);
}
