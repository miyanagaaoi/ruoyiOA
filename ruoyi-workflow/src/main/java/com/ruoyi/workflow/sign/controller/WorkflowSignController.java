package com.ruoyi.workflow.sign.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.workflow.domain.SignRecord;
import com.ruoyi.workflow.sign.service.ISignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p> 签名与撤销（PRD 第 8 章 / 10.2-B） </p>
 *
 * <p> 权限口径与打印一致：签名发生在"我自己的审批动作"上，
 * 能办这个任务就能签，因此只要求登录，不叠加单独权限点。 </p>
 *
 * <p> <b>未实现（下一段）</b>：{@code GET /workflow/sign/policy}（节点级签名策略）
 * 需要设计器把 {@code signMode/signTypes/signReuse} 写进 BPMN 扩展属性，
 * 属于"节点级配置"那一轮的工作。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/workflow/sign")
public class WorkflowSignController extends BaseController {

    @Autowired
    private ISignService signService;

    /**
     * 提交签名。
     *
     * <p> 请求体只需 {@code businessId / taskId / taskDefKey / nodeName / signType / fileId}；
     * 签名人、时间、IP、UA、哈希链由服务端生成。 </p>
     */
    @PostMapping("/record")
    public AjaxResult sign(@RequestBody SignRecord req) {
        HttpServletRequest request = ServletUtils.getRequest();
        String ip = request == null ? null : IpUtils.getIpAddr(request);
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua != null && ua.length() > 500) {
            ua = ua.substring(0, 500);
        }
        return success(signService.sign(req, ip, ua));
    }

    /** 撤销某任务的签名（追加一条撤销记录，原记录不动） */
    @PostMapping("/record/revoke")
    public AjaxResult revoke(@RequestParam("businessId") String businessId,
                             @RequestParam("taskId") String taskId,
                             @RequestParam(value = "reason", required = false) String reason) {
        HttpServletRequest request = ServletUtils.getRequest();
        String ip = request == null ? null : IpUtils.getIpAddr(request);
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua != null && ua.length() > 500) {
            ua = ua.substring(0, 500);
        }
        return success(signService.revoke(businessId, taskId, reason, ip, ua));
    }

    /** 某单据的全部签名记录（详情页与打印件用） */
    @GetMapping("/record/list")
    public AjaxResult list(@RequestParam("businessId") String businessId) {
        List<SignRecord> list = signService.listByBusinessId(businessId);
        return success(list);
    }

    /** 某单据"每个节点当前有效的签名"（taskId -> fileId），已被撤销的节点不在结果里 */
    @GetMapping("/record/effective")
    public AjaxResult effective(@RequestParam("businessId") String businessId) {
        return success(signService.effectiveSignByTask(businessId));
    }
}
