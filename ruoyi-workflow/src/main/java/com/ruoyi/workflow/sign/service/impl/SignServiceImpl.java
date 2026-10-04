package com.ruoyi.workflow.sign.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.factory.FlowServiceFactory;
import com.ruoyi.workflow.domain.SignRecord;
import com.ruoyi.workflow.mapper.SignRecordMapper;
import com.ruoyi.workflow.sign.service.ISignService;
import org.apache.commons.lang3.StringUtils;
import org.flowable.engine.history.HistoricProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p> 签名服务实现（PRD 第 8 章） </p>
 *
 * <p> <b>只追加</b>：没有任何 update / delete 路径。撤销签名是追加一条
 * {@code signType=9} 的记录，历史记录一分不动 —— 这样"谁在什么时候签过什么"
 * 才真正不可否认。 </p>
 *
 * <p> <b>客户端能决定的事情只有两件</b>：签在哪个节点、用哪张图。
 * 签名人、签名时间、IP、UA、表单快照哈希、上一条哈希，全部由服务端生成，
 * 客户端传什么都不采信 —— 否则哈希链就失去意义。 </p>
 *
 * @author 二开
 */
@Service
public class SignServiceImpl extends FlowServiceFactory implements ISignService {

    private static final Logger log = LoggerFactory.getLogger(SignServiceImpl.class);

    @Autowired
    private SignRecordMapper signRecordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignRecord sign(SignRecord req, String ip, String userAgent) {
        if (req == null || StringUtils.isBlank(req.getBusinessId())) {
            throw new ServiceException("签名缺少业务ID");
        }
        String signType = StringUtils.isBlank(req.getSignType())
                ? SignRecord.TYPE_HANDWRITE : req.getSignType();
        if (SignRecord.TYPE_REVOKE.equals(signType)) {
            throw new ServiceException("撤销签名请使用撤销接口");
        }
        // 三种签名方式都需要一张图片（印章是盖章后的文件ID）
        if (StringUtils.isBlank(req.getFileId())) {
            throw new ServiceException("签名缺少图片文件ID");
        }

        String businessId = req.getBusinessId();
        SignRecord r = new SignRecord();
        r.setId(uuid());
        r.setBusinessId(businessId);
        r.setProcInsId(resolveProcInsId(businessId));
        r.setTaskId(req.getTaskId());
        r.setTaskDefKey(req.getTaskDefKey());
        r.setNodeName(req.getNodeName());
        r.setSignType(signType);
        r.setFileId(req.getFileId());
        // ↓ 以下全部由服务端决定，不采信客户端
        r.setSignUserId(String.valueOf(SecurityUtils.getUserId()));
        r.setSignUserName(SecurityUtils.getUsername());
        r.setSignTime(new Date());
        r.setSignIp(ip);
        r.setUserAgent(userAgent);
        r.setFormDataHash(sha256OrNull(loadFormData(businessId)));
        SignRecord last = signRecordMapper.selectLatestByBusinessId(businessId);
        r.setPrevHash(last == null ? null : last.getRecordHash());
        r.setRecordHash(sha256(chainOf(r)));
        signRecordMapper.insert(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignRecord revoke(String businessId, String taskId, String reason, String ip, String userAgent) {
        if (StringUtils.isBlank(businessId) || StringUtils.isBlank(taskId)) {
            throw new ServiceException("撤销签名需要业务ID与任务ID");
        }
        SignRecord latest = signRecordMapper.selectLatestByTask(businessId, taskId);
        if (latest == null || SignRecord.TYPE_REVOKE.equals(latest.getSignType())) {
            throw new ServiceException("该节点当前没有可撤销的签名");
        }
        SignRecord r = new SignRecord();
        r.setId(uuid());
        r.setBusinessId(businessId);
        r.setProcInsId(latest.getProcInsId());
        r.setTaskId(taskId);
        r.setTaskDefKey(latest.getTaskDefKey());
        r.setNodeName(latest.getNodeName());
        r.setSignType(SignRecord.TYPE_REVOKE);
        r.setFileId(null);
        r.setSignUserId(String.valueOf(SecurityUtils.getUserId()));
        r.setSignUserName(SecurityUtils.getUsername());
        r.setSignTime(new Date());
        r.setSignIp(ip);
        r.setUserAgent(StringUtils.isBlank(reason) ? userAgent : userAgent + " | 撤签原因：" + reason);
        r.setFormDataHash(sha256OrNull(loadFormData(businessId)));
        SignRecord last = signRecordMapper.selectLatestByBusinessId(businessId);
        r.setPrevHash(last == null ? null : last.getRecordHash());
        r.setRecordHash(sha256(chainOf(r)));
        signRecordMapper.insert(r);
        return r;
    }

    @Override
    public List<SignRecord> listByBusinessId(String businessId) {
        return signRecordMapper.selectByBusinessId(businessId);
    }

    /**
     * 每个节点"当前有效"的签名：{@code taskId -> fileId}。
     *
     * <p> 判定规则：按时间顺序扫，**最后一条说了算** ——
     * 最后一条是撤销（signType=9）则该节点视为未签，否则取它的 fileId。
     * 用"最后一条"而不是"存在一条非撤销记录"，是为了让"签了又撤"能正确回到未签状态。 </p>
     */
    @Override
    public Map<String, String> effectiveSignByTask(String businessId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (StringUtils.isBlank(businessId)) {
            return out;
        }
        List<SignRecord> all = signRecordMapper.selectByBusinessId(businessId);
        if (all == null) {
            return out;
        }
        Map<String, SignRecord> lastByTask = new HashMap<>();
        for (SignRecord r : all) {
            if (r == null || StringUtils.isBlank(r.getTaskId())) {
                continue;
            }
            // 列表按 sign_time asc，后写覆盖前写
            lastByTask.put(r.getTaskId(), r);
        }
        for (Map.Entry<String, SignRecord> e : lastByTask.entrySet()) {
            SignRecord r = e.getValue();
            if (!SignRecord.TYPE_REVOKE.equals(r.getSignType()) && StringUtils.isNotBlank(r.getFileId())) {
                out.put(e.getKey(), r.getFileId());
            }
        }
        return out;
    }

    @Override
    public boolean hasEffectiveSign(String businessId, String taskId) {
        if (StringUtils.isBlank(businessId) || StringUtils.isBlank(taskId)) {
            return false;
        }
        return effectiveSignByTask(businessId).containsKey(taskId);
    }

    /* ==================== 内部 ==================== */

    /** businessId 与 Flowable 的 businessKey 同值，据此反查实例 */
    private String resolveProcInsId(String businessId) {
        try {
            List<HistoricProcessInstance> list = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(businessId)
                    .orderByProcessInstanceStartTime().desc()
                    .list();
            return (list == null || list.isEmpty()) ? null : list.get(0).getId();
        } catch (Exception e) {
            log.warn("签名：反查流程实例失败 businessId={}", businessId, e);
            return null;
        }
    }

    private String loadFormData(String businessId) {
        try {
            return signRecordMapper.selectFormDataById(businessId);
        } catch (Exception e) {
            log.warn("签名：取表单快照失败 businessId={}", businessId, e);
            return null;
        }
    }

    /**
     * 哈希链的原文（PRD 8.5）：
     * {@code id‖businessId‖taskId‖fileId‖signUserId‖signTime‖formDataHash‖prevHash}。
     * 用「‖」分隔并给空值统一占位，避免不同字段拼接出同一串（例如 a|bc 与 ab|c）。
     */
    private String chainOf(SignRecord r) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return join(r.getId()) + '‖' + join(r.getBusinessId()) + '‖' + join(r.getTaskId()) + '‖'
                + join(r.getFileId()) + '‖' + join(r.getSignUserId()) + '‖'
                + (r.getSignTime() == null ? "" : sdf.format(r.getSignTime())) + '‖'
                + join(r.getFormDataHash()) + '‖' + join(r.getPrevHash());
    }

    private static String join(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String s) {
        if (s == null) {
            s = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ServiceException("计算签名哈希失败：" + e.getMessage());
        }
    }

    /** 表单快照取不到时不写哈希（而不是写空串的哈希，那会假装"有内容证据"） */
    private static String sha256OrNull(String s) {
        return StringUtils.isBlank(s) ? null : sha256(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
