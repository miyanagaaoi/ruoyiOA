package com.ruoyi.workflow.print.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.workflow.domain.PrintLog;
import com.ruoyi.workflow.domain.PrintTemplate;
import com.ruoyi.workflow.guard.DocViewGuard;
import com.ruoyi.workflow.print.model.PrintData;
import com.ruoyi.workflow.print.service.IPrintService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 不再叠加单独的权限点（打印是查看权的延伸）；但 **"有查看权"本身必须由服务端判**
 * （PRD 11.3 越权防护 / AC-35）—— 否则换个 businessId 就能把别人的合同连金额带签批意见
 * 全打印出来。判定见 {@link DocViewGuard}。因此这里的接口都<b>不</b>加
 * {@code @PreAuthorize}，但**都**要过 {@code requireViewable}。 </p>
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

    @Autowired
    private DocViewGuard docViewGuard;

    /**
     * 打印数据聚合：一次拿齐 表单 + 节点签批栏 + 附件清单 + 生效的打印模板。
     *
     * @param businessId 业务ID
     * @param printTplId 指定打印模板（可空，为空则取该单据模板下第一个启用的或系统默认）
     */
    @GetMapping("/data/{businessId}")
    public AjaxResult data(@PathVariable("businessId") String businessId,
                           @RequestParam(value = "printTplId", required = false) String printTplId) {
        // AC-35：打印件含金额与签批意见，必须确认"这张单据你看得见"
        docViewGuard.requireViewable(businessId);
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

    /* ==================== 内置版式（2.0 B2 / REQ-PRINT-011、REQ-PRINT-013） ==================== */

    /**
     * 内置版式清单（4 个 key + 展示名称）。
     *
     * <p> <b>权限点复用已存在的 {@code workflow:print:template}</b>，不新增权限点、不新增菜单
     * （design D10 / PRD 7.5）：内置版式是代码常量，不是数据行，没有 CRUD 页面。 </p>
     */
    @GetMapping("/builtinTemplates")
    @PreAuthorize("@ss.hasPermi('workflow:print:template')")
    public AjaxResult builtinTemplates() {
        return success(printService.listBuiltinTemplates());
    }

    /**
     * 某个内置版式的字段映射（"填入内置版式"的起点内容）。
     *
     * <p> 这是 REQ-PRINT-013 的落点：内置版式常量以**后端为唯一真源**，
     * 客户端不再持有副本。未知 key 回退 {@code contract} 并记 warning（与打印时口径一致）。 </p>
     */
    @GetMapping("/defaultFieldMap/{builtinKey}")
    @PreAuthorize("@ss.hasPermi('workflow:print:template')")
    public AjaxResult defaultFieldMap(@PathVariable("builtinKey") String builtinKey) {
        return success(printService.getBuiltinFieldMap(builtinKey));
    }

    /**
     * 保存单据模板绑定的内置版式键（配置页"选择内置模板"模式，REQ-PRINT-018）。
     *
     * <p> <b>为什么单独一个窄接口</b>（PRD §9.3 的接口清单里没有它）：
     * "保存后该单据模板的 {@code builtin_print_key} 为该键"这条要求必须落到某处，
     * 而复用 {@code PUT /workflow/template} 有两个硬伤 ——
     * ① 那条链路是 {@code TemplateDTO} 的**全量** MapStruct 回写，未提交的字段会被置空，
     * 从打印配置页发一次"只改版式键"的请求会把单据模板的其它字段一起打掉；
     * ② 它要求 {@code workflow:template:edit}，而本页的按钮权限是
     * {@code workflow:print:template:edit}，会出现"页面上能点、点下去 403"。
     * 所以这里给一个只改一列、权限点与本页一致的写口。 </p>
     */
    @PostMapping("/builtinKey")
    @PreAuthorize("@ss.hasPermi('workflow:print:template:edit')")
    @Log(title = "打印模板", businessType = BusinessType.UPDATE)
    public AjaxResult saveBuiltinKey(@RequestBody java.util.Map<String, String> body) {
        String templateId = body == null ? null : body.get("templateId");
        String builtinKey = body == null ? null : body.get("builtinKey");
        return toAjax(printService.saveBuiltinPrintKey(templateId, builtinKey));
    }

    /* ==================== 打印模板配置（PRD 7.7） ==================== */

    /**
     * 某单据模板下的打印模板列表。
     *
     * <p> ⚠ 与上面的 {@code /template/{templateId}} 不冲突：Spring 的路径匹配里
     * **字面量段优先于变量段**，{@code /template/list} 会命中本方法。 </p>
     */
    @GetMapping("/template/list")
    @PreAuthorize("@ss.hasPermi('workflow:print:template')")
    public AjaxResult templateList(@RequestParam(value = "templateId", required = false) String templateId) {
        return success(printService.listTemplates(templateId));
    }

    /**
     * 新增 / 更新打印模板。
     *
     * <p> 写接口此前**刻意没有放出来**（那时没有配置页，放一个没权限点保护的写接口只会留隐患）。
     * 现在配置页来了，权限点 {@code workflow:print:template:edit} 一并落地。 </p>
     */
    @PostMapping("/template")
    @PreAuthorize("@ss.hasPermi('workflow:print:template:edit')")
    @Log(title = "打印模板", businessType = BusinessType.UPDATE)
    public AjaxResult saveTemplate(@RequestBody PrintTemplate printTemplate) {
        return success(printService.saveTemplate(printTemplate));
    }

    /** 删除打印模板（逻辑删，历史打印件不受影响） */
    @DeleteMapping("/template/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:print:template:remove')")
    @Log(title = "打印模板", businessType = BusinessType.DELETE)
    public AjaxResult removeTemplate(@PathVariable("id") String id) {
        return toAjax(printService.deleteTemplate(id));
    }

    /**
     * 写打印留痕（只追加）。
     *
     * <p> 打印人与打印时间由服务端会话与服务器时间决定，**不接受客户端传入**，
     * 否则"谁在何时打印过"就不可信了。IP 与 UA 一并落库。 </p>
     */
    @PostMapping("/log")
    public AjaxResult writeLog(@RequestBody PrintLog printLog) {
        // 写留痕同样要过查看权：否则任何人都能往别人的单据上灌打印记录
        docViewGuard.requireViewable(printLog == null ? null : printLog.getBusinessId());
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
        // 打印记录里有"谁在什么时候打印过"，同样是单据信息，按查看权收口
        docViewGuard.requireViewable(businessId);
        List<PrintLog> list = printService.listLogs(businessId);
        return success(list);
    }
}
