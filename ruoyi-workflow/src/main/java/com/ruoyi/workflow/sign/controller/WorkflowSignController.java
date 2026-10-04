package com.ruoyi.workflow.sign.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.workflow.domain.SignRecord;
import com.ruoyi.workflow.domain.UserSignPreset;
import com.ruoyi.workflow.sign.policy.SignPolicyReader;
import com.ruoyi.workflow.sign.service.ISignService;
import com.ruoyi.workflow.sign.service.IUserSignPresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p> 签名 / 撤销 / 预存签名（PRD 第 8 章 / 10.2-B） </p>
 *
 * <p> 权限口径与打印一致：签名发生在"我自己的审批动作"上，
 * 能办这个任务就能签，因此只要求登录，不叠加单独权限点。
 * 预存签名同理 —— 服务端按当前登录用户过滤并校验归属，越权在服务端拒绝。 </p>
 *
 * <p> <b>关于节点级签名策略</b>：编译器 {@code SimpleFlowCompiler} 已把
 * {@code signMode / signTypes} 写进 BPMN 扩展属性，服务端读它做提交拦截
 * （{@code TaskSignGuard}）。{@code GET /workflow/sign/policy} 是**同一份解释**
 * 对前端的出口 —— 签名弹窗要靠它决定"这个节点允许手写还是预存"（PRD 8.2 的
 * "允许方式"生效点 / AC-25）。原先以为前端能从任务的扩展变量里直接拿到，
 * 实测拿不到（扩展属性在 BPMN 模型上，不在任务的 variable 里），故补该接口。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/workflow/sign")
public class WorkflowSignController extends BaseController {

    @Autowired
    private ISignService signService;

    @Autowired
    private IUserSignPresetService userSignPresetService;

    @Autowired
    private SignPolicyReader signPolicyReader;

    /**
     * 当前任务节点的签名策略（PRD 8.2 / 10.2-B、AC-25）。
     *
     * <p> 返回 {@code {signMode, signTypes[], signRequired}}；任务不存在或读不到模型时
     * 返回 {@code OPTIONAL + [HANDWRITE, PRESET]}（PRD 默认值），**不报错** ——
     * 前端据此只是决定显示哪几个 Tab，读不到不该让整个签名入口不可用。 </p>
     */
    @GetMapping("/policy")
    public AjaxResult policy(@RequestParam("taskId") String taskId) {
        return success(signPolicyReader.describe(taskId));
    }

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

    /* ==================== 预存签名（PRD 8.4 / AC-29、AC-30） ==================== */

    /** 我的预存签名列表（默认签名排最前） */
    @GetMapping("/preset/list")
    public AjaxResult presetList() {
        return success(userSignPresetService.listMine());
    }

    /**
     * 我的默认预存签名。
     *
     * <p> 没有配过时 {@code data} 为 null —— 前端据此决定"一键使用默认签名"是否可用，
     * 不要让它去猜。 </p>
     */
    @GetMapping("/preset/default")
    public AjaxResult presetDefault() {
        return success(userSignPresetService.getMyDefault());
    }

    /** 新增预存签名（手写采集或上传图片后，把 fileId 存下来）；第一枚自动成为默认 */
    @PostMapping("/preset")
    public AjaxResult presetAdd(@RequestBody UserSignPreset preset) {
        return success(userSignPresetService.save(preset));
    }

    /** 改名 / 停用启用 */
    @PutMapping("/preset")
    public AjaxResult presetEdit(@RequestBody UserSignPreset preset) {
        return success(userSignPresetService.update(preset));
    }

    /** 设为默认（服务端同一事务内先清后置，保证同一用户只有一个默认） */
    @PutMapping("/preset/default/{id}")
    public AjaxResult presetSetDefault(@PathVariable("id") String id) {
        return success(userSignPresetService.setDefault(id));
    }

    /** 逻辑删除 */
    @DeleteMapping("/preset/{id}")
    public AjaxResult presetRemove(@PathVariable("id") String id) {
        userSignPresetService.remove(id);
        return success();
    }
}
