package com.ruoyi.template.service.impl;

import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.WhetherStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.file.business.domain.FileStorage;
import com.ruoyi.file.business.service.IFileStorageService;
import com.ruoyi.template.domain.*;
import com.ruoyi.template.enums.FormTypeEnum;
import com.ruoyi.template.mapper.TemplateFlowAdminMapper;
import com.ruoyi.template.mapper.TemplateMapper;
import com.ruoyi.template.mapper.TemplateRelatedApprovalMapper;
import com.ruoyi.template.mapper.TemplateSourceTargetMapper;
import com.ruoyi.template.mapper.TemplateSubmitScopeMapper;
import com.ruoyi.template.module.*;
import com.ruoyi.template.service.*;
import com.ruoyi.template.support.BuiltinPrintKeyValidator;
import com.ruoyi.template.support.SubmitScopeMatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模板配置Service业务层处理
 *
 * @author wocurr.com
 */
@Slf4j
@Service
public class TemplateServiceImpl implements ITemplateService {

    /** 关联审批控件在表单 schema 里的 tag（前端 DesignRelatedApproval / config.js 三处登记之一） */
    private static final String RELATED_APPROVAL_TAG = "design-related-approval";

    /** 流程标识前缀（与 {@code SimpleFlowServiceImpl.TEMPLATE_DEF_KEY_PREFIX} 必须一致） */
    private static final String TEMPLATE_DEF_KEY_PREFIX = "tpl_";

    /** 流程标识里取模板ID的前几位（与 {@code SimpleFlowServiceImpl.TEMPLATE_DEF_KEY_ID_LEN} 一致） */
    private static final int TEMPLATE_DEF_KEY_ID_LEN = 8;

    /**
     * 按「tpl_ + 模板ID前 8 位」派生流程标识（2.0 B1 §4.2/§5.4）。
     *
     * <p> 这里与流程侧各有一份实现是有意的取舍：模块依赖方向是
     * {@code ruoyi-workflow → ruoyi-template}，模板侧不能反向引用流程侧的静态工具。
     * 两处的常量必须同步修改。 </p>
     */
    private String buildTemplateDefKey(String templateId) {
        String id = StringUtils.defaultString(templateId);
        String head = id.length() > TEMPLATE_DEF_KEY_ID_LEN ? id.substring(0, TEMPLATE_DEF_KEY_ID_LEN) : id;
        return TEMPLATE_DEF_KEY_PREFIX + head;
    }

    @Autowired
    private TemplateMapper templateMapper;
    @Autowired
    private ITemplateAttachmentService templateAttachmentService;    @Autowired
    private ITemplateDynamicFormService templateDynamicFormService;
    @Autowired
    private ITemplateMainTextService templateMainTextService;
    @Autowired
    private IFileStorageService fileStorageService;
    @Autowired
    private ITemplateMessageNoticeService templateMessageNoticeService;
    @Autowired
    private ITemplateTypeService templateTypeService;
    @Autowired
    private TemplateSubmitScopeMapper templateSubmitScopeMapper;
    @Autowired
    private TemplateFlowAdminMapper templateFlowAdminMapper;
    @Autowired
    private TemplateRelatedApprovalMapper templateRelatedApprovalMapper;

    /**
     * 内置打印版式键的校验口（2.0 B2 / REQ-PRINT-011）。
     *
     * <p> 实现在 {@code ruoyi-workflow} 的打印模块里（依赖方向 workflow → template），
     * 所以这里用 {@code required = false}：打印模块未装配时校验放行，
     * 不让一个可选能力把模板保存链路堵死。 </p>
     */
    @Autowired(required = false)
    private BuiltinPrintKeyValidator builtinPrintKeyValidator;

    /**
     * 保存前校验内置打印版式键（2.0 B2 / REQ-PRINT-011）。
     *
     * <p> 为什么必须拒绝而不是"静默纠正成 contract"：{@code builtin_print_key} 决定打印版式，
     * 一个拼错的 key 会让单据"打出来是另一套版式"，而用户以为自己配好了
     * —— 正是 PRD 风险 R-3 那类"看着配好了、打出来不对"的问题。
     * 校验在**任何写库动作之前**抛错，因此失败时该模板的原值保持不变。 </p>
     */
    private void checkBuiltinPrintKey(Template template) {
        String key = template == null ? null : template.getBuiltinPrintKey();
        if (StringUtils.isBlank(key) || builtinPrintKeyValidator == null) {
            return;
        }
        if (!builtinPrintKeyValidator.isValid(key)) {
            throw new ServiceException("内置打印版式键不合法：" + key
                    + "（可用值：" + builtinPrintKeyValidator.allowedKeys() + "）");
        }
    }

    /**
     * 查询模板配置
     *
     * @param id 模板配置主键
     * @return 模板配置
     */
    @Override
    public Template getTemplateById(String id) {
        return templateMapper.selectTemplateById(id);
    }

    /**
     * 查询模板配置
     *
     * @param id 模板配置主键
     * @return
     */
    @Override
    public TemplateDTO getTemplateDTOById(String id) {
        Template template = getTemplateById(id);
        if (template == null) {
            return null;
        }
        TemplateDTO dto = TemplateSourceTargetMapper.INSTANCE.convertTemplateDTO(template);
        if (StringUtils.equals(Constants.YES_VALUE, template.getAttachFlag())) {
            TemplateAttachment attachment = templateAttachmentService.getTemplateAttachmentByTemplateId(template.getId());
            dto.setAttachment(attachment);
        }
        // 动态表单才填充
        if (StringUtils.equals(FormTypeEnum.DYNAMIC.getCode(), template.getFormType())) {
            TemplateDynamicForm dynamicForm = templateDynamicFormService.getTemplateDynamicFormById(template.getFormId());
            dto.setDynamicForm(dynamicForm);
        }
        if (StringUtils.equals(Constants.YES_VALUE, template.getMainTextFlag())) {
            TemplateMainText mainText = templateMainTextService.getByTemplateId(template.getId());
            TemplateMainTextDTO mainTextDTO = TemplateSourceTargetMapper.INSTANCE.copyTemplateMainTextDTO(mainText);
            if (mainText != null && StringUtils.isNotBlank(mainText.getFileId())) {
                FileStorage fileStorage = fileStorageService.getFileStorageByFileId(mainText.getFileId());
                if (fileStorage != null) {
                    mainTextDTO.setFileName(fileStorage.getFileName());
                    mainTextDTO.setExtendName(fileStorage.getExtendName());
                }
            }
            dto.setMainText(mainTextDTO);
        }
        if (StringUtils.equals(Constants.YES_VALUE, template.getMessageNoticeFlag())) {
            TemplateMessageNotice messageNotice = templateMessageNoticeService.getByTemplateId(template.getId());
            TemplateMessageNoticeDTO messageNoticeDTO = TemplateSourceTargetMapper.INSTANCE.copyTemplateMessageNoticeDTO(messageNotice);
            dto.setMessageNotice(messageNoticeDTO);
        }
        // 2.0（B1 §3.1）：可发起范围明细 + 流程管理员（模板编辑页要能回显）
        dto.setSubmitScope(templateSubmitScopeMapper.selectByTemplateId(template.getId()));
        dto.setFlowAdmins(templateFlowAdminMapper.selectByTemplateId(template.getId()));
        return dto;
    }

    /**
     * 查询模板配置列表
     *
     * @param template 模板配置
     * @return 模板配置
     */
    @Override
    public List<Template> listTemplate(Template template) {
        template.setDelFlag(WhetherStatus.NO.getCode());
        List<Template> result = templateMapper.selectTemplateList(template);
        if (CollectionUtils.isEmpty(result)) {
            return Collections.emptyList();
        }
        List<String> templateTypes = result.stream().map(Template::getType).distinct().collect(Collectors.toList());
        List<TemplateType> templateTypList = templateTypeService.listTemplateType(templateTypes);
        Map<String, String> templateMap = templateTypList.stream().collect(Collectors.toMap(TemplateType::getId, TemplateType::getName));
        result.stream().forEach(t -> {
            t.setTypeName(templateMap.get(t.getType()));
        });
        return result;
    }

    /**
     * 新增模板配置
     *
     * @param templateDTO 模板配置
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveTemplate(TemplateDTO templateDTO) {
        Template template = TemplateSourceTargetMapper.INSTANCE.convertTemplate(templateDTO);
        // 2.0（B2 §2）：版式键非法一律拒绝保存（见 checkBuiltinPrintKey 的注释）
        checkBuiltinPrintKey(template);
        template.setId(IdUtils.fastSimpleUUID());
        // 2.0（B1 §5.4）：基础信息页签**取消了「关联流程」手选下拉**（改为只读展示绑定结果，
        // 流程标识由系统按"tpl_ + 模板ID前 8 位"派生、发布时回写）。
        // 于是新建模板时 defKey 是空的 —— 而 t_template.def_key 是 NOT NULL 且无默认值，
        // 不在这里补上就是"新建模板直接 500"（实测踩过）。
        // 派生规则必须与流程侧 SimpleFlowServiceImpl.buildTemplateDefKey **完全一致**，
        // 否则发布时回写的 key 与这里落库的不是同一个。
        if (StringUtils.isBlank(template.getDefKey())) {
            template.setDefKey(buildTemplateDefKey(template.getId()));
        }
        template.setCreateId(SecurityUtils.getUserId());
        template.setCreateBy(SecurityUtils.getLoginUser().getUser().getNickName());
        template.setCreateTime(DateUtils.getNowDate());
        handleAttachment(template.getId(), template.getAttachFlag(), templateDTO.getAttachment());
        handleMainText(template.getId(), template.getMainTextFlag(), templateDTO.getMainText());
        handleMessageNotice(template.getId(), template.getMessageNoticeFlag(), templateDTO.getMessageNotice());
        handleSubmitScope(template.getId(), template.getSubmitScopeType(), templateDTO.getSubmitScope());
        handleFlowAdmins(template.getId(), templateDTO.getFlowAdmins());
        handleRelatedApproval(template.getId(), templateDTO.getDynamicForm());
        return templateMapper.insertTemplate(template);
    }

    /**
     * 修改模板配置
     *
     * <p> <b>2.0（B1 §1.1，REQ-DATA-004）：原地 UPDATE 同一行，id 不变。</b> </p>
     *
     * <p> 原实现是"旧行置 enable_flag='0' + del_flag='1'、再插入一条新 UUID"。
     * 后果是所有 {@code template_id} 外键表（{@code t_template_print_template}、
     * {@code t_template_node_field_auth}，以及 2.0 新增的 4 张表）在**每次编辑模板后集体失联**：
     * 子表还挂在废弃行上，而列表/详情只认启用行。 </p>
     *
     * <p> 因此这里改为：读出现有行 → 用提交内容覆盖其业务字段 → 原地 update。
     * 不变项：{@code id} / {@code create_*} / {@code del_flag} / {@code enable_flag}
     * （编辑不应改变创建信息，也不应悄悄启用或停用模板——启停有 {@code changeEnableFlag} 专口）。 </p>
     *
     * @param templateDTO 模板配置
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTemplate(TemplateDTO templateDTO) {
        Template template = templateMapper.selectTemplateById(templateDTO.getId());
        if (template == null) {
            throw new BaseException("记录不存在");
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Template updated = TemplateSourceTargetMapper.INSTANCE.copyTemplate(templateDTO);
        // 2.0（B2 §2）：版式键非法一律拒绝保存，且**在任何写库动作之前**抛出
        // → 该模板的 builtin_print_key 保持原值不变（REQ-PRINT-011 的 Scenario）
        checkBuiltinPrintKey(updated);
        // 主键必须落在同一行上（MapStruct 会带上 DTO 里的 id，这里再显式兜一次）
        updated.setId(template.getId());
        // 创建信息与启停状态不随编辑改变
        updated.setCreateId(template.getCreateId());
        updated.setCreateBy(template.getCreateBy());
        updated.setCreateTime(template.getCreateTime());
        updated.setDelFlag(template.getDelFlag());
        updated.setEnableFlag(template.getEnableFlag());
        updated.setUpdateId(loginUser.getUserId());
        updated.setUpdateBy(loginUser.getUser().getNickName());
        updated.setUpdateTime(DateUtils.getNowDate());
        handleAttachment(updated.getId(), updated.getAttachFlag(), templateDTO.getAttachment());
        handleMainText(updated.getId(), updated.getMainTextFlag(), templateDTO.getMainText());
        handleMessageNotice(updated.getId(), updated.getMessageNoticeFlag(), templateDTO.getMessageNotice());
        handleSubmitScope(updated.getId(), updated.getSubmitScopeType(), templateDTO.getSubmitScope());
        handleFlowAdmins(updated.getId(), templateDTO.getFlowAdmins());
        handleRelatedApproval(updated.getId(), templateDTO.getDynamicForm());
        return templateMapper.updateTemplate(updated);
    }

    /**
     * 批量删除模板配置
     *
     * @param ids 需要删除的模板配置主键
     * @return 结果
     */
    @Override
    public int deleteTemplateByIds(String[] ids) {
        return templateMapper.deleteTemplateByIds(ids);
    }

    /**
     * 更新模板配置状态
     *
     * @param template 模板
     * @return Integer
     */
    @Override
    public int changeEnableFlag(Template template) {
        return templateMapper.changeEnableFlag(template);
    }

    /**
     * 查询新启流程模板列表（2.0 B1 §3.2/§3.5）
     *
     * <p> 相对升级前的两处变化： </p>
     * <ol>
     *   <li><b>按可发起范围在服务端过滤</b>（在**分组之前**过滤，保留"分组驱动"语义）——
     *       只靠前端隐藏可被直接构造请求绕过；</li>
     *   <li><b>无分类模板不再被静默丢弃</b>：{@code type} 为空的模板归入「未分类」组返回。</li>
     * </ol>
     *
     * @return List<TemplateModel>
     */
    @Override
    public List<TemplateModel> listNewStartTemplate() {
        List<TemplateModel> templateModels = new ArrayList<>();
        Template template = new Template();
        List<Template> templates = templateMapper.selectNewStartTemplateList(template);
        if (CollectionUtils.isEmpty(templates)) {
            return templateModels;
        }

        // ---------- 1. 可发起范围过滤（服务端强制，B1 §3.2） ----------
        SubmitScopeMatcher.ScopeUser scopeUser = buildScopeUser();
        Map<String, List<TemplateSubmitScope>> scopeMap = loadScopeMap(
                templates.stream().map(Template::getId).collect(Collectors.toList()));
        templates = templates.stream()
                .filter(t -> SubmitScopeMatcher.canStart(
                        t.getSubmitScopeType(), t.getIncludeChildDept(),
                        scopeMap.get(t.getId()), scopeUser))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(templates)) {
            return templateModels;
        }

        // 按类型分组
        // ⚠ Collectors.groupingBy 的分类键为 null 时抛 "element cannot be mapped to a null key"：
        //   只要有一条模板没选分类，整个「新启流程」列表就 500。
        //   这里空键归入同一组（"" 组），下面单独作为「未分类」返回，不再连累整页。
        Map<String, List<Template>> templateTypeMap = templates.stream()
                .collect(Collectors.groupingBy(
                        item -> StringUtils.defaultString(item.getType()),
                        () -> new LinkedHashMap<>(),
                        Collectors.toList()
                ));
        // 按模板类型的顺序进行排序
        List<String> templateTypes = templates.stream()
                .map(Template::getType)
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        List<TemplateType> templateTypList = CollectionUtils.isEmpty(templateTypes)
                ? new ArrayList<>()
                : templateTypeService.listTemplateType(templateTypes);
        for (TemplateType templateType : templateTypList) {
            String typeId = templateType.getId();
            if (!templateTypeMap.containsKey(typeId)) {
                continue;
            }
            TemplateModel templateModel = new TemplateModel();
            templateModel.setType(templateType.getId());
            templateModel.setTypeName(templateType.getName());
            templateModel.setTemplates(templateTypeMap.get(templateType.getId()));
            templateModels.add(templateModel);
        }

        // ---------- 2. 「未分类」组（B1 §3.5） ----------
        // 升级前这里的循环由 templateTypList 驱动，type 为空的模板匹配不到任何分类行 → 静默消失。
        List<Template> uncategorized = templateTypeMap.get("");
        if (CollectionUtils.isNotEmpty(uncategorized)) {
            TemplateModel uncategorizedModel = new TemplateModel();
            uncategorizedModel.setType("");
            uncategorizedModel.setTypeName("未分类");
            uncategorizedModel.setTemplates(uncategorized);
            templateModels.add(uncategorizedModel);
        }
        return templateModels;
    }

    /**
     * 获取模板选择列表
     *
     * @return
     */
    @Override
    public List<TemplateOption> getSelectTemplateList() {
        Template template = new Template();
        List<Template> templates = templateMapper.selectNewStartTemplateList(template);
        if (CollectionUtils.isEmpty(templates)) {
            return Collections.emptyList();
        }
        List<TemplateOption> templateOptions = new ArrayList<>();
        for (Template template1 : templates) {
            TemplateOption templateOption = new TemplateOption();
            templateOption.setTemplateId(template1.getId());
            templateOption.setTemplateName(template1.getName());
            templateOptions.add(templateOption);
        }
        return templateOptions;
    }

    /**
     * 处理附件（2.0：同一模板只保留一套配置）
     *
     * <p> 原注释为"为了不影响已在途的文件，每次模板的修改都新增"——那是因为**模板行本身**
     * 每次编辑都换新 id，旧行连同旧配置一起留存。改为原地更新后，同一个 templateId 上
     * 再累积多行会让 {@code getTemplateAttachmentByTemplateId}（返回单对象）抛
     * TooManyResultsException，模板编辑页直接 500。故由子配置服务保证"一模板一套"。 </p>
     *
     * @param templateId 模板ID
     * @param attachFlag 是否有附件
     * @param attachment 附件集合
     */
    private void handleAttachment(String templateId, String attachFlag, TemplateAttachment attachment) {
        if (attachment == null || StringUtils.equals(Constants.NO_VALUE, attachFlag)) {
            return;
        }
        String userId = SecurityUtils.getLoginUser().getUserId();
        attachment.setId(IdUtils.fastSimpleUUID());
        attachment.setTemplateId(templateId);
        attachment.setCreateId(userId);
        attachment.setCreateTime(DateUtils.getNowDate());
        templateAttachmentService.saveTemplateAttachment(attachment);
    }

    /**
     * 处理正文（为了不影响已在途的文件，每次模板的修改都新增）
     *
     * @param templateId
     * @param mainTextFlag
     * @param mainText
     */
    private void handleMainText(String templateId, String mainTextFlag, TemplateMainText mainText) {
        if (mainText == null || StringUtils.equals(Constants.NO_VALUE, mainTextFlag)) {
            return;
        }
        if (StringUtils.equals(mainText.getType(), Constants.YES_VALUE)) {
            mainText.setLimitSize(null);
            mainText.setLimitType(null);
        } else {
            mainText.setFileId(null);
        }
        String userId = SecurityUtils.getLoginUser().getUserId();
        mainText.setId(IdUtils.fastSimpleUUID());
        mainText.setTemplateId(templateId);
        mainText.setCreateId(userId);
        mainText.setCreateTime(DateUtils.getNowDate());
        templateMainTextService.saveTemplateMainText(mainText);
    }

    /**
     * 处理消息（为了不影响已在途的文件，每次模板的修改都新增）
     *
     * @param templateId
     * @param messageNoticeFlag
     * @param messageNotice
     */
    private void handleMessageNotice(String templateId, String messageNoticeFlag, TemplateMessageNotice messageNotice) {
        if (messageNotice == null || StringUtils.equals(Constants.NO_VALUE, messageNoticeFlag)) {
            return;
        }
        messageNotice.setId(IdUtils.fastSimpleUUID());
        messageNotice.setTemplateId(templateId);
        messageNotice.setCreateId(SecurityUtils.getLoginUser().getUserId());
        messageNotice.setCreateTime(DateUtils.getNowDate());
        templateMessageNoticeService.saveTemplateMessageNotice(messageNotice);
    }

    // ================================================================================
    // 2.0（B1 §3.1–§3.5）可发起范围与流程管理员
    // ================================================================================

    /**
     * 取「关联审批」控件允许被关联的模板ID清单（2.0 B1 §7）。
     *
     * <p> 运行时只认 {@code t_template_related_approval}：模板保存时已从 schema 解析落库。
     * 表里没有 = 控件没配候选 = 没有候选项（调用方按拒绝处理）。 </p>
     */
    @Override
    public List<String> listRelatedApprovalAllowTemplates(String templateId, String fieldVmodel) {
        if (StringUtils.isBlank(templateId) || StringUtils.isBlank(fieldVmodel)) {
            return Collections.emptyList();
        }
        return templateRelatedApprovalMapper.selectAllowTemplateIds(templateId, fieldVmodel);
    }

    /**
     * 统计有多少模板正指向该版本表单（2.0 B1 §7.10）。
     */
    @Override
    public int countTemplatesOnForm(String formId) {
        if (StringUtils.isBlank(formId)) {
            return 0;
        }
        return templateMapper.countByFormId(formId);
    }

    /**
     * 统计有多少模板正在用某个表单标识的当前版本（§7.10）。
     */
    @Override
    public int countTemplatesOnFormKey(String formKey) {
        if (StringUtils.isBlank(formKey)) {
            return 0;
        }
        return templateMapper.countByFormKey(formKey);
    }

    /**
     * 模板上配置了「关联审批」控件的字段清单（2.0 B1 §7）。
     */
    @Override
    public List<String> listRelatedApprovalFields(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return Collections.emptyList();
        }
        List<TemplateRelatedApproval> rows = templateRelatedApprovalMapper.selectByTemplateId(templateId);
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        // 去重且保持稳定顺序（同一模板下可能有多个关联审批控件）
        return rows.stream()
                .map(TemplateRelatedApproval::getFieldVmodel)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 同步「关联审批」控件的候选模板（2.0 B1 §7，REQ-FORM-010 / AC-54）。
     *
     * <p> 控件的候选范围是**控件的配置**，天然存在动态表单 schema 里
     * （form-generator 的字段对象上）。这里把它解析出来落到
     * {@code t_template_related_approval}，作为服务端复核选择范围的**权威来源**：
     * 运行时只认这张表，认不出（没配）就等于没有候选项 —— 失败方向是"拒绝"而不是"放行"。 </p>
     *
     * <p> 全量替换：先清后插。改配置时若只增不减，范围会越改越宽。 </p>
     *
     * @param templateId  宿主模板ID
     * @param dynamicForm 动态表单（DTO 里的那个，content 是 schema JSON）
     */
    private void handleRelatedApproval(String templateId, TemplateDynamicForm dynamicForm) {
        templateRelatedApprovalMapper.deleteByTemplateId(templateId);
        if (dynamicForm == null || StringUtils.isBlank(dynamicForm.getContent())) {
            return;
        }
        List<TemplateRelatedApproval> rows = new ArrayList<>();
        try {
            Object content = JSON.parse(dynamicForm.getContent());
            collectRelatedApproval(content, rows);
        } catch (Exception e) {
            // schema 坏了不该把模板保存整体搞失败（页面上的表单此刻可能还没保存）
            log.warn("关联审批候选配置解析失败，本次不同步候选表：templateId={} err={}", templateId, e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        Set<String> dedup = new HashSet<>();
        List<TemplateRelatedApproval> valid = new ArrayList<>();
        for (TemplateRelatedApproval row : rows) {
            if (StringUtils.isBlank(row.getFieldVmodel()) || StringUtils.isBlank(row.getAllowTemplateId())) {
                continue;
            }
            if (row.getAllowTemplateId().equals(templateId)) {
                // 自己关联自己没有意义，而且会让候选查询把自己列出来
                continue;
            }
            if (!dedup.add(row.getFieldVmodel() + ":" + row.getAllowTemplateId())) {
                continue;
            }
            row.setId(IdUtils.fastSimpleUUID());
            row.setTemplateId(templateId);
            row.setCreateTime(DateUtils.getNowDate());
            valid.add(row);
        }
        if (!valid.isEmpty()) {
            templateRelatedApprovalMapper.batchInsert(valid);
        }
    }

    /**
     * 递归收集 schema 里的「关联审批」控件。
     *
     * <p> 结构：{@code {fields:[{__config__:{tag},__vModel__,allowTemplates:[...]}, ...]}}，
     * 行容器（layout=rowFormItem）的子控件在 {@code __config__.children} 里，必须递归。 </p>
     */
    private void collectRelatedApproval(Object node, List<TemplateRelatedApproval> out) {
        if (node == null) {
            return;
        }
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectRelatedApproval(item, out);
            }
            return;
        }
        JSONObject obj = toJsonObject(node);
        if (obj == null) {
            return;
        }
        JSONObject config = obj.getJSONObject("__config__");
        if (config != null && RELATED_APPROVAL_TAG.equals(config.getString("tag"))) {
            collectAllowTemplates(obj, out);
        }
        // 行容器 / 子表单：继续往下走
        if (config != null && config.get("children") != null) {
            collectRelatedApproval(config.get("children"), out);
        }
        if (obj.get("children") != null) {
            collectRelatedApproval(obj.get("children"), out);
        }
        if (obj.get("fields") != null) {
            collectRelatedApproval(obj.get("fields"), out);
        }
    }

    /** 取单个控件上配置的候选模板清单（兼容 allowTemplates 数组与逗号串两种写法） */
    private void collectAllowTemplates(JSONObject field, List<TemplateRelatedApproval> out) {
        String vModel = field.getString("__vModel__");
        Object allow = field.get("allowTemplates");
        if (allow == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        if (allow instanceof Iterable) {
            for (Object id : (Iterable<?>) allow) {
                ids.add(id == null ? "" : String.valueOf(id).trim());
            }
        } else {
            for (String id : String.valueOf(allow).split(",")) {
                ids.add(id.trim());
            }
        }
        for (String id : ids) {
            if (StringUtils.isBlank(id)) {
                continue;
            }
            TemplateRelatedApproval row = new TemplateRelatedApproval();
            row.setFieldVmodel(vModel);
            row.setAllowTemplateId(id);
            out.add(row);
        }
    }

    private JSONObject toJsonObject(Object node) {
        if (node instanceof JSONObject) {
            return (JSONObject) node;
        }
        if (node instanceof Map) {
            return new JSONObject((Map<String, Object>) node);
        }
        if (node instanceof String) {
            try {
                return JSON.parseObject((String) node);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 处理「谁可以提交该审批」明细（<b>全量替换</b>语义，B1 §3.1）。
     *
     * <p> 类型为"指定"类时明细不能为空（delta spec：
     * 「当类型为"指定"类时，明细 MUST NOT 为空」）；类型为全员时清掉历史明细。 </p>
     *
     * @param templateId  模板ID
     * @param scopeType   范围类型
     * @param submitScope 明细
     */
    private void handleSubmitScope(String templateId, String scopeType, List<TemplateSubmitScope> submitScope) {
        // 全量替换：先清后插（与 t_template_submit_scope 的唯一键 uk_tpl_scope_target 配合，
        // 避免"改了又改"时残留旧明细造成放行范围越改越宽）
        templateSubmitScopeMapper.deleteByTemplateId(templateId);

        boolean specified = SubmitScopeMatcher.TYPE_USER.equals(scopeType)
                || SubmitScopeMatcher.TYPE_ROLE.equals(scopeType)
                || SubmitScopeMatcher.TYPE_DEPT.equals(scopeType);
        if (!specified) {
            return;
        }
        if (CollectionUtils.isEmpty(submitScope)) {
            throw new BaseException("「谁可以提交该审批」选择了指定范围时，必须至少选择一项");
        }
        List<TemplateSubmitScope> rows = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        String userId = SecurityUtils.getLoginUser().getUserId();
        for (TemplateSubmitScope item : submitScope) {
            if (item == null || StringUtils.isBlank(item.getTargetId())) {
                continue;
            }
            // 明细自带的 scope_type 为空时按模板上选的类型落库
            String type = StringUtils.defaultIfBlank(item.getScopeType(), scopeType);
            if (!dedup.add(type + ":" + item.getTargetId())) {
                continue;
            }
            TemplateSubmitScope row = new TemplateSubmitScope();
            row.setId(IdUtils.fastSimpleUUID());
            row.setTemplateId(templateId);
            row.setScopeType(type);
            row.setTargetId(item.getTargetId());
            row.setCreateId(userId);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new BaseException("「谁可以提交该审批」选择了指定范围时，必须至少选择一项有效明细");
        }
        templateSubmitScopeMapper.batchInsert(rows);
    }

    /**
     * 处理流程管理员（<b>全量替换</b>语义，B1 §3.1）。
     *
     * @param templateId 模板ID
     * @param flowAdmins 流程管理员
     */
    private void handleFlowAdmins(String templateId, List<TemplateFlowAdmin> flowAdmins) {
        templateFlowAdminMapper.deleteByTemplateId(templateId);
        if (CollectionUtils.isEmpty(flowAdmins)) {
            return;
        }
        List<TemplateFlowAdmin> rows = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        String userId = SecurityUtils.getLoginUser().getUserId();
        for (TemplateFlowAdmin item : flowAdmins) {
            if (item == null || StringUtils.isBlank(item.getUserId()) || !dedup.add(item.getUserId())) {
                continue;
            }
            TemplateFlowAdmin row = new TemplateFlowAdmin();
            row.setId(IdUtils.fastSimpleUUID());
            row.setTemplateId(templateId);
            row.setUserId(item.getUserId());
            row.setCreateId(userId);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            templateFlowAdminMapper.batchInsert(rows);
        }
    }

    /** 批量取模板的范围明细，避免发起页按模板逐个查（N+1） */
    private Map<String, List<TemplateSubmitScope>> loadScopeMap(List<String> templateIds) {
        Map<String, List<TemplateSubmitScope>> map = new HashMap<>();
        if (CollectionUtils.isEmpty(templateIds)) {
            return map;
        }
        List<TemplateSubmitScope> all = templateSubmitScopeMapper.selectByTemplateIds(templateIds);
        if (CollectionUtils.isEmpty(all)) {
            return map;
        }
        for (TemplateSubmitScope scope : all) {
            List<TemplateSubmitScope> list = map.get(scope.getTemplateId());
            if (list == null) {
                list = new ArrayList<>();
                map.put(scope.getTemplateId(), list);
            }
            list.add(scope);
        }
        return map;
    }

    /**
     * 组装当前登录用户的判定上下文（部门物化路径走 {@code sys_dept.ancestors}，
     * 即平台既有的组织树，不自建第二套）。
     */
    private SubmitScopeMatcher.ScopeUser buildScopeUser() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        String userId = loginUser.getUserId();
        String deptId = loginUser.getDeptId();
        Set<String> roleIds = new HashSet<>();
        if (loginUser.getUser() != null && CollectionUtils.isNotEmpty(loginUser.getUser().getRoles())) {
            for (SysRole role : loginUser.getUser().getRoles()) {
                if (role != null && role.getRoleId() != null) {
                    roleIds.add(String.valueOf(role.getRoleId()));
                }
            }
        }
        String ancestors = StringUtils.isBlank(deptId) ? null : templateMapper.selectDeptAncestors(deptId);
        return new SubmitScopeMatcher.ScopeUser(userId, deptId, roleIds, ancestors,
                SecurityUtils.isAdmin(userId));
    }

    @Override
    public boolean canStartTemplate(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return false;
        }
        Template template = templateMapper.selectTemplateById(templateId);
        if (template == null) {
            return false;
        }
        // 已删除/未启用的模板不允许发起（与发起页列表口径一致）
        if (!WhetherStatus.NO.getCode().equals(template.getDelFlag())
                || !WhetherStatus.YES.getCode().equals(template.getEnableFlag())) {
            return false;
        }
        return SubmitScopeMatcher.canStart(template.getSubmitScopeType(), template.getIncludeChildDept(),
                templateSubmitScopeMapper.selectByTemplateId(templateId), buildScopeUser());
    }

    @Override
    public void checkStartPermission(String templateId) {
        if (canStartTemplate(templateId)) {
            return;
        }
        Template template = StringUtils.isBlank(templateId) ? null : templateMapper.selectTemplateById(templateId);
        String name = template == null ? templateId : template.getName();
        log.warn("发起越权被拒：userId={} templateId={}", SecurityUtils.getLoginUser().getUserId(), templateId);
        // 业务码 403：后端仍回 HTTP 200，前端与 tools/authz-check.ps1 按响应体的 code 判定
        throw new ServiceException("无发起权限：该审批未对当前账号开放（模板：" + name + "）", HttpStatus.FORBIDDEN);
    }

    @Override
    public boolean canManageFlow(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return false;
        }
        Template template = templateMapper.selectTemplateById(templateId);
        if (template == null) {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        String userId = loginUser.getUserId();
        // 系统管理员不受限制
        if (SecurityUtils.isAdmin(userId)) {
            return true;
        }
        int adminCount = templateFlowAdminMapper.countByTemplateId(templateId);
        if (adminCount > 0) {
            // 指定了流程管理员：必须在其名单内
            return templateFlowAdminMapper.countByTemplateIdAndUserId(templateId, userId) > 0;
        }
        // 未指定：回退到创建人 + 拥有模板编辑权限者
        if (StringUtils.equals(template.getCreateId(), userId)) {
            return true;
        }
        return SecurityUtils.hasPermi("workflow:template:edit");
    }

    @Override
    public void checkFlowManagePermission(String templateId) {
        if (canManageFlow(templateId)) {
            return;
        }
        Template template = StringUtils.isBlank(templateId) ? null : templateMapper.selectTemplateById(templateId);
        String name = template == null ? templateId : template.getName();
        log.warn("流程发布越权被拒：userId={} templateId={}", SecurityUtils.getLoginUser().getUserId(), templateId);
        throw new ServiceException("无流程管理权限：你不是该模板的流程管理员，也不是创建人（模板：" + name + "）",
                HttpStatus.FORBIDDEN);
    }

    @Override
    public String getTemplateIdBySimpleFlowId(String simpleFlowId) {
        if (StringUtils.isBlank(simpleFlowId)) {
            return null;
        }
        return templateMapper.selectTemplateIdBySimpleFlowId(simpleFlowId);
    }

    @Override
    public void checkFlowManageByFlowId(String simpleFlowId) {
        if (StringUtils.isBlank(simpleFlowId)) {
            return;
        }
        String templateId = templateMapper.selectTemplateIdBySimpleFlowId(simpleFlowId);
        if (StringUtils.isBlank(templateId)) {
            // 未绑定模板的独立流程（含 V1 存量流程）：不做模板级授权，保持既有权限点不变
            return;
        }
        checkFlowManagePermission(templateId);
    }

    @Override
    public void saveFlowBinding(String templateId, String simpleFlowId, String defKey, String flowMode) {
        Template template = templateMapper.selectTemplateById(templateId);
        if (template == null || !WhetherStatus.NO.getCode().equals(template.getDelFlag())) {
            // 抛异常是刻意的：发布回写与部署在同一事务里，写入失败必须让整次发布回滚，
            // 不能留下"引擎已部署、模板未绑定"的静默悬空态（B1 §4.4）
            throw new BaseException("模板不存在或已删除，无法回写流程绑定：" + templateId);
        }
        Template upd = new Template();
        // 只在启用行上原地更新（§1.1 语义）：绝不新建行、绝不写到废弃行
        upd.setId(template.getId());
        upd.setSimpleFlowId(simpleFlowId);
        upd.setDefKey(defKey);
        upd.setFlowMode(flowMode);
        upd.setUpdateId(SecurityUtils.getUserId());
        upd.setUpdateBy(SecurityUtils.getLoginUser().getUser().getNickName());
        upd.setUpdateTime(DateUtils.getNowDate());
        templateMapper.updateTemplate(upd);
    }
}
