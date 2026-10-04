package com.ruoyi.workflow.print.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.biz.domain.CommonForm;
import com.ruoyi.biz.service.IBizFormService;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.enums.WhetherStatus;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.domain.dto.FlowTaskDto;
import com.ruoyi.flowable.factory.FlowServiceFactory;
import com.ruoyi.flowable.service.IFlowTaskService;
import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.mapper.PrintLogMapper;
import com.ruoyi.workflow.mapper.PrintTemplateMapper;
import com.ruoyi.workflow.print.model.PrintData;
import com.ruoyi.workflow.print.service.IPrintService;
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
import java.util.Date;
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
        data.setTitle(StringUtils.isNotBlank(tpl.getTitle()) ? tpl.getTitle() : DEFAULT_TITLE);

        // 3) 发起人 / 发起单位：取自流程记录的 startUser* 字段，避免再依赖用户服务
        data.setSubmitter(firstNonBlank(records, FlowTaskDto::getStartUserName));
        data.setSubmitterDept(firstNonBlank(records, FlowTaskDto::getStartDeptName));
        data.setBusinessNo(businessId);

        // 4) 签批栏：签名图片按"每个节点当前有效的签名"取（已被撤销的节点取不到）
        Map<String, String> signMap = signService.effectiveSignByTask(businessId);
        data.setNodes(buildNodes(records, signMap));

        // 5) 附件清单
        data.setAttachments(loadAttachments(businessId));

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
    private List<FlowTaskDto> loadRecords(String procInsId) {
        java.util.Map<String, Object> map = flowTaskService.flowHistoryRecord(procInsId, null, 1, RECORD_PAGE_SIZE);
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
        for (FlowTaskDto r : records) {
            if (r == null) {
                continue;
            }
            PrintData.Node n = new PrintData.Node();
            n.setTaskId(r.getTaskId());
            n.setTaskDefKey(r.getTaskDefKey());
            n.setNodeName(StringUtils.isNotBlank(r.getTaskName()) ? r.getTaskName() : r.getActId());
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

    /* ==================== 打印模板 ==================== */

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
        return builtinTemplate(templateId);
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
     * 没有配置打印模板时使用的<b>系统模板</b>（PRD 7.7 的"本期=系统模板 + 字段映射"）。
     *
     * <p> 这里给出的是一个**保守的默认版式**：只声明"哪些字段进基本信息区、占几列"，
     * 具体渲染由前端按 field_map 执行。缺省的 {@code printable} 字段一律打印。 </p>
     */
    private PrintTemplate builtinTemplate(String templateId) {
        PrintTemplate t = new PrintTemplate();
        t.setId(null);
        t.setTemplateId(templateId);
        t.setName("系统默认打印模板");
        t.setPaper("A4");
        t.setOrientation("portrait");
        t.setTitle(DEFAULT_TITLE);
        t.setFieldMap(DEFAULT_FIELD_MAP);
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

    /* ==================== 工具 ==================== */

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final String DEFAULT_TITLE = "集团合同类文件流转审批单";

    /** 内置字段映射（占位版式：两列布局）。真实版式由"打印模板配置"页编辑后覆盖。 */
    private static final String DEFAULT_FIELD_MAP = buildDefaultFieldMap();

    private static String buildDefaultFieldMap() {
        JSONObject base = new JSONObject();
        base.put("id", "base");
        JSONArray rows = new JSONArray();

        rows.add(row(cell("提报单位", "$submitterDept", 1), cell("报送人", "$submitter", 1), cell("报送时间", "$submitTime", 1)));
        rows.add(row(cell("合同编号", "contractNo", 3)));
        rows.add(row(cell("合同全称", "contractName", 3)));
        rows.add(row(cell("合同签订主体-甲方", "ourCompany", 1), cell("合同签订主体-乙方", "counterpartyName", 1), cell("合同金额", "amount", 1)));
        rows.add(row(cell("履约开始", "startDate", 1), cell("履约结束", "endDate", 1), cell("合同签订时间", "signDate", 1)));
        rows.add(row(cell("其他会审部门", "jointDepts", 3)));
        rows.add(row(cell("相关说明", "description", 3)));
        base.put("rows", rows);

        JSONArray sections = new JSONArray();
        sections.add(base);
        JSONObject sign = new JSONObject();
        sign.put("id", "sign");
        sign.put("type", "dynamic");
        sign.put("source", "flowNodes");
        sections.add(sign);

        JSONObject attach = new JSONObject();
        attach.put("id", "attach");
        attach.put("type", "attachmentList");
        sections.add(attach);

        JSONObject root = new JSONObject();
        root.put("sections", sections);
        return root.toJSONString();
    }

    private static JSONObject cell(String label, String field, int span) {
        JSONObject c = new JSONObject();
        c.put("label", label);
        c.put("field", field);
        c.put("span", span);
        return c;
    }

    private static JSONObject row(JSONObject... cells) {
        JSONObject r = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject c : cells) {
            arr.add(c);
        }
        r.put("cells", arr);
        return r;
    }
}
