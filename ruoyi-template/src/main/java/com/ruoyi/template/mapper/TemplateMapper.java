package com.ruoyi.template.mapper;

import java.util.List;
import com.ruoyi.template.domain.Template;
import org.apache.ibatis.annotations.Param;

/**
 * 模板配置Mapper接口
 * 
 * @author wocurr.com
 */
public interface TemplateMapper {
    /**
     * 查询模板配置
     * 
     * @param id 模板配置主键
     * @return 模板配置
     */
    public Template selectTemplateById(String id);

    /**
     * 查询模板配置列表
     * 
     * @param template 模板配置
     * @return 模板配置集合
     */
    public List<Template> selectTemplateList(Template template);

    /**
     * 新增模板配置
     * 
     * @param template 模板配置
     * @return 结果
     */
    public int insertTemplate(Template template);

    /**
     * 修改模板配置
     * 
     * @param template 模板配置
     * @return 结果
     */
    public int updateTemplate(Template template);

    /**
     * 删除模板配置
     * 
     * @param id 模板配置主键
     * @return 结果
     */
    public int deleteTemplateById(String id);

    /**
     * 批量删除模板配置
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteTemplateByIds(String[] ids);

    /**
     * 更新模板配置状态
     *
     * @param template 模板配置
     * @return 结果
     */
    int changeEnableFlag(Template template);


    /**
     * 查询新启流程模板列表
     *
     * @param template 模板配置
     * @return 模板列表
     */
    List<Template> selectNewStartTemplateList(Template template);

    /**
     * 取部门的物化路径 {@code sys_dept.ancestors}（2.0 B1 §3.2）。
     *
     * <p> 用于"部门范围是否含下级"的判定：目标部门出现在我的 ancestors 里，
     * 说明我属于它的后代部门。读的是平台既有的组织树，不自建第二套。 </p>
     *
     * @param deptId 部门ID
     * @return 形如 {@code 0,100,101}；部门不存在返回 null
     */
    String selectDeptAncestors(String deptId);

    /**
     * 按"绑定的简化流程ID"反查模板ID（2.0 B1 §3.4）。
     *
     * <p> 发布接口只拿到流程 id，要先知道这条流程属于哪张模板，才能做模板级授权。 </p>
     *
     * @param simpleFlowId t_flow_simple.id
     * @return 模板ID；没有模板绑定该流程时返回 null
     */
    String selectTemplateIdBySimpleFlowId(String simpleFlowId);

    /**
     * 把指向旧版本表单的模板改指到新版本（2.0 B1 §7.10，动态表单版本化）。
     *
     * <p> 动态表单保存走的是"停用旧行 + 插入新行（新 id、同 form_key）"，
     * 而模板上的 {@code form_id} 存的是**具体那一版的行 id**。
     * 不做这一步，模板会永远停留在旧版本表单上：改了表单却"没生效"，
     * 而用户看到的是"保存成功"。 </p>
     *
     * @param oldFormId 旧版本表单ID
     * @param newFormId 新版本表单ID
     * @return 被改指的模板数
     */
    int repointFormId(@Param("oldFormId") String oldFormId, @Param("newFormId") String newFormId);

    /**
     * 统计有多少模板正指向该版本表单（用于保存后的提示："已影响 N 个模板"）。
     *
     * @param formId 表单版本ID
     */
    int countByFormId(@Param("formId") String formId);

    /**
     * 统计有多少模板正在用某个「表单标识」的**当前版本**（2.0 B1 §7.10 提示用）。
     *
     * <p> 为什么不按 form_id 数：表单换版本后 form_id 会指向新行，
     * 拿旧 id 去数永远是 0 —— 提示就会变成"影响 0 个模板"，比不提示更误导。 </p>
     *
     * @param formKey 表单标识（t_template_dynamic_form.form_key）
     */
    int countByFormKey(@Param("formKey") String formKey);
}
