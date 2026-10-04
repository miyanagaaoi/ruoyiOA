package com.ruoyi.workflow.print.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.print.model.PrintData;
import com.ruoyi.workflow.print.service.IPrintService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p> 表单打印（PRD 第 7 章 / 10.2-B） </p>
 *
 * <p> <b>权限口径</b>：按 PRD 7.6「对单据有查看权即可打印」，打印相关接口只要求登录，
 * 不再叠加单独的权限点（打印是查看权的延伸）。因此这里的接口都<b>不</b>加
 * {@code @PreAuthorize}。 </p>
 *
 * <p> <b>本类暂不提供打印模板的写接口</b>：模板配置页还没实现，此刻放出一个
 * 没有权限点保护的写接口只会留下隐患。写接口与 {@code workflow:print:template}
 * 权限点随"打印模板配置页"一起提交（服务层的 {@code saveTemplate} 已就绪）。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/workflow/print")
public class WorkflowPrintController extends BaseController {

    @Autowired
    private IPrintService printService;

    /**
     * 打印数据聚合：一次拿齐 表单 + 节点签批栏 + 附件清单 + 生效的打印模板。
     *
     * @param businessId 业务ID
     * @param printTplId 指定打印模板（可空，为空则取该单据模板下第一个启用的或系统默认）
     */
    @GetMapping("/data/{businessId}")
    public AjaxResult data(@PathVariable("businessId") String businessId,
                           @RequestParam(value = "printTplId", required = false) String printTplId) {
        PrintData data = printService.getPrintData(businessId, printTplId);
        return success(data);
    }

    /** 读取生效的打印模板（含系统默认；永远不返回空） */
    @GetMapping("/template/{templateId}")
    public AjaxResult template(@PathVariable("templateId") String templateId,
                               @RequestParam(value = "printTplId", required = false) String printTplId) {
        PrintTemplate tpl = printService.getEffectiveTemplate(templateId, printTplId);
        return success(tpl);
    }

    /**
     * 写打印留痕（只追加）。
     *
     * <p> 打印人与打印时间由服务端会话与服务器时间决定，**不接受客户端传入**，
     * 否则"谁在何时打印过"就不可信了。IP 与 UA 一并落库。 </p>
     */
    @PostMapping("/log")
    public AjaxResult writeLog(@RequestBody PrintLog printLog) {
        HttpServletRequest request = ServletUtils.getRequest();
        if (request != null) {
            printLog.setPrintIp(IpUtils.getIpAddr(request));
            String ua = request.getHeader("User-Agent");
            printLog.setUserAgent(ua != null && ua.length() > 500 ? ua.substring(0, 500) : ua);
        }
        printService.writeLog(printLog);
        return success();
    }

    /** 某单据的打印记录（详情页"打印记录"页签用） */
    @GetMapping("/log/list")
    public AjaxResult logList(@RequestParam("businessId") String businessId) {
        List<PrintLog> list = printService.listLogs(businessId);
        return success(list);
    }
}
