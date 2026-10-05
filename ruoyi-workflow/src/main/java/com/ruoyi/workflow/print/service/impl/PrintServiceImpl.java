package com.ruoyi.workflow.print.service.impl;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.biz.domain.CommonForm;
import com.ruoyi.biz.service.IBizFormService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.enums.WhetherStatus;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.domain.dto.FlowTaskDto;
import com.ruoyi.flowable.domain.vo.FlowCommentVo;
import com.ruoyi.flowable.factory.FlowServiceFactory;
import com.ruoyi.flowable.service.IFlowTaskService;
import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.mapper.PrintLogMapper;
import com.ruoyi.workflow.mapper.PrintRefMapper;
import com.ruoyi.workflow.mapper.PrintTemplateMapper;
import com.ruoyi.workflow.print.model.BuiltinTemplateOption;
import com.ruoyi.workflow.print.model.PrintCcNode;
import com.ruoyi.workflow.print.model.PrintData;
import com.ruoyi.workflow.print.model.SubmitterOrg;
import com.ruoyi.workflow.print.service.IPrintService;
import com.ruoyi.workflow.print.support.BuiltinPrintTemplates;
import com.ruoyi.workflow.sign.service.ISignService;
import com.ruoyi.workfile.module.BizAttachmentDTO;
import com.ruoyi.workfile.service.IWorkflowAttachmentService;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.history.HistoricProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p> 表单打印服务实现（PRD 第 7 章） </p>
 *
 * <p> 三个刻意的设计取舍： </p>
 * <ol>
 *   <li>协议链路上 <b>businessId 与 Flowable 的 businessKey 是同一个值</b>
 *       （证据：{@code TodoAsyncService} 里 {@code runtimeService.updateBusinessKey(procInsId, businessId)}），
 *       所以用历史查询反查 procInsId，一套查询同时覆盖运行中与已结束的实例；</li>
 *   <li>签批栏直接由 {@code flowHistoryRecord} 的 {@link FlowTaskDto} 列表生成 ——
 *       节点名/接收人/接收单位/签收时间/完成时间/意见**都已经在里面**，不再另查一遍意见表，
 *       避免两处拼装导致顺序或内容不一致；</li>
 *   <li>打印模板缺省时回退到代码内置的系统模板，DB 里没有记录也能打印。</li>
 * </ol>
 *
 * @author 二开
 */
@Service
public class PrintServiceImpl extends FlowServiceFactory implements IPrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintServiceImpl.class);

    /** 签批栏最多取多少条流转记录（打印场景一次性取全，不做分页） */
    private static final int RECORD_PAGE_SIZE = 500;

    /** 草稿（尚未发起流程）的实例状态 —— 草稿没有 HistoricProcessInstance，用这个值兜底 */
    private static final String INSTANCE_STATUS_DRAFT = "草稿";

    @Autowired
    private PrintTemplateMapper printTemplateMapper;

    @Autowired
    private PrintLogMapper printLogMapper;

    @Autowired
    private PrintRefMapper printRefMapper;

    @Autowired
    private IFlowTaskService flowTaskService;

    @Autowired
    private IWorkflowAttachmentService attachmentService;

    @Autowired
    private IBizFormService bizFormService;

    @Autowired
    private ISignService signService;

    /* ==================== 打印数据聚合 ==================== */

    @Override
    public PrintData getPrintData(String businessId, String printTplId) {
        if (StringUtils.isBlank(businessId)) {
            throw new ServiceException("打印缺少业务ID");
        }
        PrintData data = new PrintData();
        data.setBusinessId(businessId);
        data.setPrintTime(new Date());
        data.setPrintUser(SecurityUtils.getUsername());

        // 1) businessId -> procInsId（历史查询同时覆盖运行中与已结束）
        //
        // ⚠ 草稿（尚未发起流程）**没有**流程实例，这是正常状态而不是错误：
        //   打印件只输出表单信息，签批栏与附件本就默认不打印，因此草稿同样可打印
        //   （表单数据由 t_workflow_form 提供，见 selectTemplateIdByBusinessId 的第四路 union）。
        //   原实现直接抛 ServiceException，用户点打印只看到一个技术性报错。
        HistoricProcessInstance hpi = findHistoricInstance(businessId);
        if (hpi != null) {
            data.setProcInsId(hpi.getId());
            data.setSubmitTime(hpi.getStartTime());
            data.setFinishTime(hpi.getEndTime());
            data.setInstanceStatus(instanceStatusOf(hpi));
        } else {
            data.setInstanceStatus(INSTANCE_STATUS_DRAFT);
        }

        // 2) 单据模板：它落在业务记录（待办/已办/回收站/**草稿**）上，**不是流程定义的 key**，
        //    也不是流程变量。踩过：用 procDefKey 当单据模板ID，导致表单服务报"模板ID为空"。
        String templateId = printTemplateMapper.selectTemplateIdByBusinessId(businessId);
        // 无实例时不去查流转记录：loadRecords 按 procInsId 查历史，传 null 没有意义
        List<FlowTaskDto> records = hpi != null ? loadRecords(hpi.getId()) : new ArrayList<>();
        data.setTemplateId(templateId);
        PrintTemplate tpl = getEffectiveTemplate(templateId, printTplId);
        data.setPrintTemplate(tpl);
        data.setTitle(StringUtils.isNotBlank(tpl.getTitle())
                ? tpl.getTitle() : BuiltinPrintTemplates.titleOf(tpl.getBuiltinKey()));

        // 3) 发起人 / 发起单位：
        //    流程记录的 startUser* 在 ruoyi-flowable 里**从不赋值**（实测恒为 null），
        //    所以真正的发起人取单据自身（t_workflow_form.create_id）→ sys_user → sys_dept。
        //    这三个值支撑内置版式的「报送人/报送单位/审批单位」栏目（PRD 附录 A）。
        //    取不到就留空（打印件该栏为空，不影响其余部分），**不抛异常**。
        data.setSubmitter(firstNonBlank(records, FlowTaskDto::getStartUserName));
        data.setSubmitterDept(firstNonBlank(records, FlowTaskDto::getStartDeptName));
        applySubmitterOrg(data, businessId);
        data.setBusinessNo(businessId);

        // 4) 签批栏：签名图片按"每个节点当前有效的签名"取（已被撤销的节点取不到）
        Map<String, String> signMap = signService.effectiveSignByTask(businessId);
        data.setNodes(buildNodes(records, signMap));

        // 5) 附件清单
        data.setAttachments(loadAttachments(businessId));

        // 5.1) 抄送栏数据（2.0 B2 §4.5）：出栏与否由模板的 showCcNode 决定，这里只给数据
        data.setCcNodes(loadCcNodes(businessId));

        // 6) 表单数据：形状由 IBizFormService 决定，这里不二次加工。
        //    表单服务**要求模板ID**，取不到时直接跳过，不打无意义的告警。
        if (StringUtils.isNotBlank(templateId)) {
            try {
                CommonForm cf = new CommonForm();
                cf.setBizId(businessId);
                cf.setTemplateId(templateId);
                data.setFormData(bizFormService.getBizForm(cf));
            } catch (Exception e) {
                // 表单取不到不该让整张打印件失败：签批栏与附件清单仍有价值
                log.warn("打印聚合：取表单数据失败 businessId={} templateId={}，将只打印流程部分",
                        businessId, templateId, e);
            }
        } else {
            log.info("打印聚合：businessId={} 查不到单据模板ID，跳过表单数据（只打印流程部分）", businessId);
        }

        // 7) 水印
        if ("1".equals(tpl.getWatermark())) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            data.setWatermarkText(data.getPrintUser() + " 于 " + sdf.format(new Date()) + " 打印，仅供内部使用");
        }
        return data;
    }

    /** 用 businessKey 反查历史流程实例（取最近一次） */
    private HistoricProcessInstance findHistoricInstance(String businessId) {
        List<HistoricProcessInstance> list = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessId)
                .orderByProcessInstanceStartTime().desc()
                .list();
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private String instanceStatusOf(HistoricProcessInstance hpi) {
        if (hpi.getDeleteReason() != null) {
            return "已终止";
        }
        return hpi.getEndTime() == null ? "运行中" : "已结束";
    }

    @SuppressWarnings("unchecked")
    /**
     * 取流程的流转记录（含处理意见）。
     *
     * <p> 必须<b>两个都调</b>，各自缺一半： </p>
     * <ul>
     *   <li> {@code flowHistoryRecord}：给**全部**活动（含进行中的当前节点 —— 签批栏要能看到
     *        "已签但还没提交"的那一栏），但**不带处理意见**、也不按节点归并； </li>
     *   <li> {@code flowCmts}：带处理意见（意见在 ACT_HI_COMMENT 里），但**只给已办结的活动** ——
     *        只用它会让进行中的节点整栏消失（实测：AC-33 那张在跑的单据签批栏变空）。 </li>
     * </ul>
     * <p> 所以：以 {@code flowHistoryRecord} 的列表为准，按 taskId 把 {@code flowCmts} 的意见补上。
     * 节点顺序与归并在 {@link #buildNodes} 里按签收时间自己排（那个 service 的"首次出现顺序"
     * 实测不是时间序）。 </p>
     */
    private List<FlowTaskDto> loadRecords(String procInsId) {
        List<FlowTaskDto> records = extractFlowList(flowTaskService.flowHistoryRecord(procInsId, null, 1, RECORD_PAGE_SIZE));
        // 意见：只有已办结的任务有意见（进行中的任务提交时才写意见），查不到就留空
        Map<String, String> commentMap = new HashMap<>();
        for (FlowTaskDto t : extractFlowList(flowTaskService.flowCmts(procInsId, null, 1, RECORD_PAGE_SIZE))) {
            if (t != null && StringUtils.isNotBlank(t.getTaskId()) && t.getComment() != null
                    && StringUtils.isNotBlank(t.getComment().getComment())) {
                commentMap.put(t.getTaskId(), t.getComment().getComment());
            }
        }
        for (FlowTaskDto t : records) {
            if (t != null && t.getComment() == null && commentMap.containsKey(t.getTaskId())) {
                t.setComment(FlowCommentVo.builder().type("1").comment(commentMap.get(t.getTaskId())).build());
            }
        }
        return records;
    }

    private List<FlowTaskDto> extractFlowList(java.util.Map<String, Object> map) {
        if (map == null) {
            return new ArrayList<>();
        }
        Object flowList = map.get("flowList");
        return flowList instanceof List ? (List<FlowTaskDto>) flowList : new ArrayList<>();
    }

    /**
     * 由流转记录生成签批栏。
     *
     * <p> 未到达的节点**不出栏**（PRD 7.4-C 的"未到达节点整栏留空"由前端按模板开关处理，
     * 这里只给出真实发生过的节点，避免凭空捏造节点顺序）。 </p>
     */
    private List<PrintData.Node> buildNodes(List<FlowTaskDto> records, Map<String, String> signMap) {
        List<PrintData.Node> nodes = new ArrayList<>();
        if (records == null) {
            return nodes;
        }
        // ⚠ 顺序必须自己按"签收时间"排：flowCmts 的 "首次出现顺序" 实测**不是**时间序
        // （踩过：n2 会签排在 n1 预审前面，签批栏顺序与流程节点顺序相反 → AC-17 不达标）。
        // 稳定排序：会签多人签收时间相同，排完仍相邻。
        List<FlowTaskDto> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparing(FlowTaskDto::getCreateTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // 节点序号：同一个流程节点（actId）的多人共用同一个序号 —— 打印页据此把会签合成一栏
        Map<String, Integer> nodeIndexMap = new LinkedHashMap<>();
        for (FlowTaskDto r : ordered) {
            if (r == null) {
                continue;
            }
            // ⚠ flowCmts 只填 actId，不填 taskDefKey（实测 taskDefKey 为空），这里退化为 actId
            String nodeKey = StringUtils.isNotBlank(r.getTaskDefKey()) ? r.getTaskDefKey() : r.getActId();
            Integer nodeIndex = nodeIndexMap.computeIfAbsent(nodeKey, k -> nodeIndexMap.size() + 1);

            PrintData.Node n = new PrintData.Node();
            n.setTaskId(r.getTaskId());
            n.setTaskDefKey(nodeKey);
            n.setNodeName(StringUtils.isNotBlank(r.getTaskName()) ? r.getTaskName() : r.getActId());
            n.setNodeIndex(nodeIndex);
            // 接收单位优先取"办理人所属部门"，退化为流程记录的部门
            n.setDeptName(StringUtils.isNotBlank(r.getAssigneeDeptName())
                    ? r.getAssigneeDeptName() : r.getDeptName());
            n.setAssigneeName(r.getAssigneeName());
            n.setReceiveTime(r.getCreateTime());
            n.setFinishTime(r.getFinishTime());
            n.setStatus(r.getStatus());
            if (r.getComment() != null) {
                // FlowCommentVo 只有 type 与 comment 两个字段
                n.setComment(r.getComment().getComment());
            }
            // 签名图片：按任务ID取"当前有效"的那一张（被撤销的节点取不到，打印件留空供手写）
            n.setSignFileId(signMap == null ? null : signMap.get(r.getTaskId()));
            nodes.add(n);
        }
        return nodes;
    }

    private List<PrintData.Attachment> loadAttachments(String businessId) {
        List<PrintData.Attachment> out = new ArrayList<>();
        try {
            List<BizAttachmentDTO> list = attachmentService.listAttachment(businessId);
            if (list != null) {
                for (BizAttachmentDTO a : list) {
                    PrintData.Attachment x = new PrintData.Attachment();
                    x.setFileId(a.getFileId());
                    x.setFileName(a.getFileName());
                    x.setFileExt(a.getFileExt());
                    x.setFileSize(a.getFileSize());
                    x.setSort(a.getSort());
                    out.add(x);
                }
            }
        } catch (Exception e) {
            log.warn("打印聚合：取附件清单失败 businessId={}", businessId, e);
        }
        return out;
    }

    /**
     * 抄送记录（2.0 B2 §4.5）。是否出栏由模板的 {@code showCcNode} 决定，这里只负责取全 ——
     * 取不到不该影响其余栏目，所以失败只记告警并返回空表。
     */
    private List<PrintCcNode> loadCcNodes(String businessId) {
        try {
            List<PrintCcNode> list = printRefMapper.selectCcNodesByBusinessId(businessId);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("打印聚合：取抄送记录失败 businessId={}，抄送栏将为空", businessId, e);
            return new ArrayList<>();
        }
    }

    private String firstNonBlank(List<FlowTaskDto> list, java.util.function.Function<FlowTaskDto, String> getter) {
        if (list == null) {
            return null;
        }
        for (FlowTaskDto r : list) {
            if (r == null) {
                continue;
            }
            String v = getter.apply(r);
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 用**单据发起人**补全 发起人 / 发起单位 / 发起公司 三个内置变量。
     *
     * <p> 为什么不能只靠流程记录：{@code FlowTaskDto.startUserName} / {@code startDeptName}
     * 在 ruoyi-flowable 里**没有任何地方赋值**（全仓 {@code setStartUserName} 零命中，
     * 实测接口恒返回 null），于是打印件上的「提报单位/报送人/审批单位」一直是空的。
     * 真正可靠的发起人是单据自身：{@code t_workflow_form.create_id}。 </p>
     *
     * <p> 只在流程记录**取不到**时才覆盖：这样"本来有值"的情形行为完全不变。 </p>
     */
    private void applySubmitterOrg(PrintData data, String businessId) {
        if (StringUtils.isBlank(businessId)) {
            return;
        }
        try {
            SubmitterOrg org = printRefMapper.selectSubmitterOrgByBusinessId(businessId);
            if (org == null) {
                return;
            }
            if (StringUtils.isBlank(data.getSubmitter())) {
                data.setSubmitter(org.getUserName());
            }
            if (StringUtils.isBlank(data.getSubmitterDept())) {
                data.setSubmitterDept(org.getDeptName());
            }
            data.setSubmitterCompany(companyNameOf(org));
        } catch (Exception e) {
            // 发起人组织取不到不该让整张打印件失败：该三栏留空即可（其余栏目仍有价值）
            log.warn("打印聚合：解析发起人组织失败 businessId={}，相关栏目将留空", businessId, e);
        }
    }

    /**
     * 发起人所属**公司级**部门名称。
     *
     * <p> {@code sys_dept.ancestors} 是"根到父"的 id 串（如 {@code 0,公司ID}），
     * 第 2 段即公司级；本部门本身就是公司级时（ancestors 只有 {@code 0}）取它自己。 </p>
     */
    private String companyNameOf(SubmitterOrg org) {
        String ancestors = org.getAncestors();
        if (StringUtils.isNotBlank(ancestors)) {
            String[] parts = ancestors.split(",");
            if (parts.length >= 2) {
                String companyId = StringUtils.trimToNull(parts[1]);
                if (companyId != null && !"0".equals(companyId)) {
                    String name = printRefMapper.selectDeptNameById(companyId);
                    if (StringUtils.isNotBlank(name)) {
                        return name;
                    }
                }
            }
        }
        return org.getDeptName();
    }

    /* ==================== 打印模板 ==================== */

    /**
     * 取生效的打印模板。**优先级链与升级前完全一致**（design：本次不动）：
     * <pre>
     *   显式 printTplId  >  该单据模板下的启用行  >  内置版式
     * </pre>
     *
     * <p> 唯一的变化在最后一段：内置版式不再"全局唯一一套"，而是按单据模板的
     * {@code t_template.builtin_print_key} 选用（2.0 B2 / REQ-PRINT-011）。 </p>
     */
    @Override
    public PrintTemplate getEffectiveTemplate(String templateId, String printTplId) {
        if (StringUtils.isNotBlank(printTplId)) {
            PrintTemplate t = printTemplateMapper.selectById(printTplId);
            if (t != null) {
                return t;
            }
        }
        if (StringUtils.isNotBlank(templateId)) {
            List<PrintTemplate> list = printTemplateMapper.selectByTemplateId(templateId);
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
        }
        return builtinTemplate(templateId, lookupBuiltinKey(templateId));
    }

    /**
     * 读单据模板绑定的内置版式键。
     *
     * <p> 读不到（模板不存在 / 列还是空值 / 查库异常）一律返回 null，
     * 由 {@link BuiltinPrintTemplates#resolve} 回退 {@code contract} ——
     * 这样"打印"这件事不会因为一个新列没准备好而整张打不出来。 </p>
     */
    private String lookupBuiltinKey(String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return null;
        }
        try {
            return printTemplateMapper.selectBuiltinPrintKeyByTemplateId(templateId);
        } catch (Exception e) {
            log.warn("打印模板：读取内置版式键失败 templateId={}，回退内置默认版式", templateId, e);
            return null;
        }
    }

    @Override
    public List<PrintTemplate> listTemplates(String templateId) {
        PrintTemplate q = new PrintTemplate();
        q.setTemplateId(templateId);
        return printTemplateMapper.selectList(q);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrintTemplate saveTemplate(PrintTemplate printTemplate) {
        if (printTemplate == null) {
            throw new ServiceException("打印模板不能为空");
        }
        if (StringUtils.isBlank(printTemplate.getTemplateId())) {
            throw new ServiceException("打印模板必须关联单据模板");
        }
        // fieldMap 若是 JSON 字符串，先校验可解析：宁可在这里报错，也不要在打印时才发现模板坏了
        if (StringUtils.isNotBlank(printTemplate.getFieldMap())) {
            try {
                JSON.parseObject(printTemplate.getFieldMap());
            } catch (Exception e) {
                throw new ServiceException("字段映射不是合法 JSON：" + e.getMessage());
            }
        }
        Date now = new Date();
        if (StringUtils.isBlank(printTemplate.getId())) {
            printTemplate.setId(uuid());
            printTemplate.setCreateId(SecurityUtils.getUserId() == null ? null
                    : String.valueOf(SecurityUtils.getUserId()));
            printTemplate.setCreateBy(SecurityUtils.getUsername());
            printTemplate.setCreateTime(now);
            fillDefaults(printTemplate);
            printTemplateMapper.insert(printTemplate);
        } else {
            printTemplate.setUpdateId(SecurityUtils.getUserId() == null ? null
                    : String.valueOf(SecurityUtils.getUserId()));
            printTemplate.setUpdateBy(SecurityUtils.getUsername());
            printTemplate.setUpdateTime(now);
            printTemplateMapper.update(printTemplate);
        }
        // 「同一单据模板下只有一套启用」是服务端不变量：
        // 否则 selectByTemplateId 取的是 update_time 最新的那条 —— 改一下旧模板
        // 就会悄悄改变打印结果（实测：新键一套模板后，另一张单据的签批栏没了）。
        // 放在最后一律执行：只传 enableFlag='0' 的部分更新不会触发它。
        if (WhetherStatus.YES.getCode().equals(printTemplate.getEnableFlag())) {
            printTemplateMapper.disableOthers(printTemplate.getTemplateId(), printTemplate.getId());
        }
        return printTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTemplate(String id) {
        PrintTemplate t = new PrintTemplate();
        t.setId(id);
        t.setUpdateBy(SecurityUtils.getUsername());
        return printTemplateMapper.deleteById(t);
    }

    /**
     * 没有配置打印模板时使用的<b>系统内置版式</b>（PRD 7.7 / 2.0 B2）。
     *
     * <p> 版式由 {@code builtinKey} 索引 {@link BuiltinPrintTemplates} 给出（标题 + 字段映射），
     * 未知/空 key 回退 {@code contract} 并记一条 warning（design D2：
     * 为一个历史脏值让整张单据打不出来，比版式降级更糟）。 </p>
     *
     * <p> ⚠ {@code builtinKey} 会带回客户端：前端据此决定排版路径 ——
     * {@code contract} 保持升级前的"按表单 schema 自动排版"（存量单据零回归），
     * 另外三套以这份版式常量为准。 </p>
     */
    private PrintTemplate builtinTemplate(String templateId, String builtinKey) {
        BuiltinPrintTemplates.Resolution r = BuiltinPrintTemplates.resolve(builtinKey, log::warn);
        PrintTemplate t = new PrintTemplate();
        t.setId(null);
        t.setTemplateId(templateId);
        t.setName("系统默认打印模板");
        t.setPaper("A4");
        t.setOrientation("portrait");
        t.setTitle(r.getTitle());
        t.setFieldMap(r.getFieldMap());
        t.setBuiltinKey(r.getKey());
        // ⚠ 顺序要紧：先把**版式自带的推荐取值**填上，再让 fillDefaults 补其余空值。
        //   否则 fillDefaults 的"签批栏默认关闭"会把 fund 的签批栏关掉 ——
        //   那正是本次变更要修掉的"看着配好了、打出来没有签名区"（AC-62）。
        t.setShowSignature(r.getShowSignature());
        t.setShowAttachment(r.getShowAttachment());
        fillDefaults(t);
        return t;
    }

    private void fillDefaults(PrintTemplate t) {
        if (StringUtils.isBlank(t.getPaper())) {
            t.setPaper("A4");
        }
        if (StringUtils.isBlank(t.getOrientation())) {
            t.setOrientation("portrait");
        }
        // 打印件的定位是「只打印表单信息」：签批栏与附件清单属流程侧数据，
        // 默认**不打印**，由管理员在打印模板里显式打开（showSignature='1' / showAttachment='1'）。
        // 注意这两项原先默认 '1'，与「只打印表单信息」相反。
        if (StringUtils.isBlank(t.getShowSignature())) {
            t.setShowSignature("0");
        }
        if (StringUtils.isBlank(t.getShowComment())) {
            t.setShowComment("1");
        }
        if (StringUtils.isBlank(t.getShowAttachment())) {
            t.setShowAttachment("0");
        }
        if (StringUtils.isBlank(t.getShowCcNode())) {
            t.setShowCcNode("0");
        }
        if (StringUtils.isBlank(t.getWatermark())) {
            t.setWatermark("1");
        }
        if (StringUtils.isBlank(t.getEnableFlag())) {
            t.setEnableFlag("1");
        }
    }

    /* ==================== 打印留痕 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void writeLog(PrintLog printLog) {
        if (printLog == null || StringUtils.isBlank(printLog.getBusinessId())) {
            throw new ServiceException("打印日志缺少业务ID");
        }
        if (StringUtils.isBlank(printLog.getId())) {
            printLog.setId(uuid());
        }
        if (printLog.getPrintTime() == null) {
            printLog.setPrintTime(new Date());
        }
        // 打印人一律以服务端会话为准，不接受客户端传入（留痕的可信性）
        printLog.setPrintUserId(SecurityUtils.getUserId() == null ? null
                : String.valueOf(SecurityUtils.getUserId()));
        printLog.setPrintUserName(SecurityUtils.getUsername());
        printLogMapper.insert(printLog);
    }

    @Override
    public List<PrintLog> listLogs(String businessId) {
        return printLogMapper.selectByBusinessId(businessId);
    }

    /* ==================== 内置版式（2.0 B2） ==================== */

    /**
     * 内置版式清单（4 个 key + 展示名称）。
     *
     * <p> 给配置页渲染"选择内置模板"的下拉用。刻意做成接口而不是让前端再抄一份常量：
     * 那就又变成"前后端各一份"的重复（REQ-PRINT-013 要消除的正是这个）。 </p>
     */
    @Override
    public List<BuiltinTemplateOption> listBuiltinTemplates() {
        List<BuiltinTemplateOption> list = new ArrayList<>();
        for (String key : BuiltinPrintTemplates.keys()) {
            list.add(new BuiltinTemplateOption(key, BuiltinPrintTemplates.titleOf(key),
                    BuiltinPrintTemplates.showSignatureOf(key), BuiltinPrintTemplates.showAttachmentOf(key)));
        }
        return list;
    }

    /**
     * 某个内置版式的字段映射（版式常量本身，非第二份副本）。
     *
     * <p> 未登记的 key 回退 {@code contract}（与打印时的口径一致），并记 warning。 </p>
     */
    @Override
    public Object getBuiltinFieldMap(String builtinKey) {
        BuiltinPrintTemplates.Resolution r = BuiltinPrintTemplates.resolve(builtinKey, log::warn);
        return JSON.parse(r.getFieldMap());
    }

    /**
     * 保存单据模板绑定的内置版式键。
     *
     * <p> 只改 {@code t_template.builtin_print_key} 一列 —— 不走"全量 DTO 回写"，
     * 因为那条路会把未提交的字段一起置空（MapStruct 的默认空值策略），
     * 且要求的是另一个权限点（见 {@code PrintTemplateMapper.updateBuiltinPrintKey} 的注释）。 </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveBuiltinPrintKey(String templateId, String builtinKey) {
        if (StringUtils.isBlank(templateId)) {
            throw new ServiceException("缺少单据模板ID");
        }
        // 非法 key 一律拒绝：静默纠正成 contract 会让用户以为配好了（PRD 风险 R-3 那类问题）
        if (!BuiltinPrintTemplates.isValidKey(builtinKey)) {
            throw new ServiceException("内置打印版式键不合法："
                    + (StringUtils.isBlank(builtinKey) ? "(空)" : builtinKey)
                    + "（可用值：" + String.join(" / ", BuiltinPrintTemplates.keys()) + "）");
        }
        return printTemplateMapper.updateBuiltinPrintKey(templateId, builtinKey.trim());
    }

    /* ==================== 工具 ==================== */

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
