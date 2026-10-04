package com.ruoyi.workflow.sign.service;

import com.ruoyi.workflow.domain.SignRecord;

import java.util.List;
import java.util.Map;

/**
 * <p> 签名服务（PRD 第 8 章） </p>
 *
 * <p> 签名记录<b>只追加</b>：本服务不提供修改与删除，
 * 撤销签名 = 追加一条 {@code signType=9} 的记录（PRD 8.5）。 </p>
 *
 * @author 二开
 */
public interface ISignService {

    /**
     * 提交签名。
     *
     * <p> 服务端负责：解析流程实例、取会话里的签名人与 IP/UA、算表单快照哈希、
     * 串哈希链。客户端只能决定"签在哪个节点、用哪张图"。 </p>
     *
     * @return 落库后的记录（含 recordHash，供前端展示"已签名"凭证）
     */
    SignRecord sign(SignRecord req, String ip, String userAgent);

    /** 撤销某任务的签名（追加一条撤销记录，不改旧记录） */
    SignRecord revoke(String businessId, String taskId, String reason, String ip, String userAgent);

    /** 某单据的全部签名记录（升序） */
    List<SignRecord> listByBusinessId(String businessId);

    /**
     * 某单据"每个节点当前有效的签名"：{@code taskId -> fileId}。
     *
     * <p> 打印件签批栏按节点取签名图片时用；已被撤销（最后一条是 signType=9）的节点不出现在结果里。 </p>
     */
    Map<String, String> effectiveSignByTask(String businessId);

    /** 某任务当前是否已有有效签名（"签名要求=必需"时用于阻断提交） */
    boolean hasEffectiveSign(String businessId, String taskId);
}
