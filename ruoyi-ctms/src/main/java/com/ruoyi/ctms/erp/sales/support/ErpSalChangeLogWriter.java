package com.ruoyi.ctms.erp.sales.support;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.sales.ErpSalRules;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>销售单据的变更历史写入点</b>（2.0 B4 任务 4.2 的"留痕"）。 </p>
 *
 * <p> 口径与采购线（{@code ErpPurChangeLogWriter}）<b>逐字同构</b>，两边都消费 B3 已交付的
 * {@link CtmsChangeLogMapper}（表 {@code t_ctms_change_log} 的 DDL 注释写明"B3/B4 共用"，
 * 且 {@code contract_id} 已放宽为可空，单据日志靠 {@code object_type}/{@code object_id}
 * 多态定位）——因此 B4 不新增表、也不改 {@code base} 包。 </p>
 *
 * <p> <b>为什么不合并成一份公共类</b>：采购线与销售线在并行开发，
 * 合并会引入"两组同时改一个文件"的写冲突；两边的差异只有 {@code object_type}
 * （取自 {@link ErpDocType}）与日志字段常量，其余完全同构。若后续需要合并，
 * 由 captain 指派 base 组统一搬迁（本类注释已登记这一意图）。 </p>
 *
 * <p> <b>同事务</b>：调用方必须在 {@code @Transactional} 方法里调用本类；本类不自开事务、
 * 不吞异常。"重复判定不重复留痕"的幂等由调用方用"当前状态不是 completed 才写"实现。 </p>
 *
 * @author 二开
 */
public final class ErpSalChangeLogWriter
{
    private final CtmsChangeLogMapper changeLogMapper;

    /**
     * @param changeLogMapper 变更历史 Mapper（B3 交付，t_ctms_change_log）
     */
    public ErpSalChangeLogWriter(CtmsChangeLogMapper changeLogMapper)
    {
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 写一条状态变更留痕（同一事务内调用）。
     *
     * @param docType      单据类型（{@code object_type} 取它的 {@code code}）
     * @param docId        单据ID（{@code object_id}）
     * @param oldStatus    旧状态
     * @param newStatus    新状态
     * @param note         备注（"归零即完成"用固定文案）
     * @param source       来源：{@code auto} / {@code manual}
     * @param operatorId   操作人用户ID
     * @param operatorName 操作人登录名
     */
    public void writeStatusChange(ErpDocType docType, String docId, String oldStatus, String newStatus,
                                 String note, String source, String operatorId, String operatorName)
    {
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        // ⚠ 刻意不写 contractId：单据日志靠 (object_type, object_id) 定位
        log.setFieldName(ErpSalRules.LOG_FIELD_STATUS);
        log.setOldValue(oldStatus);
        log.setNewValue(newStatus);
        log.setNote(note);
        log.setSource(ErpSalRules.trim(source) == null ? ErpSalRules.LOG_SOURCE_MANUAL : source);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setObjectType(docType == null ? null : docType.getCode());
        log.setObjectId(docId);
        log.setCreateId(operatorId);
        log.setCreateBy(operatorName);
        changeLogMapper.insertChangeLog(log);
    }

    /**
     * 写一条"关联合同"留痕（任务 4.4：关联/解除都要可追溯；合同字段<b>不回写</b>）。
     *
     * @param docType      单据类型
     * @param docId        单据ID
     * @param oldContractNo 旧合同编号（可空）
     * @param newContractNo 新合同编号（可空：解除关联时为空）
     * @param operatorId   操作人用户ID
     * @param operatorName 操作人登录名
     */
    public void writeContractChange(ErpDocType docType, String docId, String oldContractNo,
                                    String newContractNo, String operatorId, String operatorName)
    {
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        log.setFieldName(ErpSalRules.LOG_FIELD_CONTRACT);
        log.setOldValue(oldContractNo);
        log.setNewValue(newContractNo);
        log.setSource(ErpSalRules.LOG_SOURCE_MANUAL);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setObjectType(docType == null ? null : docType.getCode());
        log.setObjectId(docId);
        log.setCreateId(operatorId);
        log.setCreateBy(operatorName);
        changeLogMapper.insertChangeLog(log);
    }

    /**
     * 批量写留痕（新增场景一般只有一条，留给后续批量动作复用）。
     *
     * @param logs 待写入的日志（由调用方构造）
     */
    public void writeAll(List<CtmsChangeLog> logs)
    {
        if (logs == null || logs.isEmpty())
        {
            return;
        }
        changeLogMapper.batchInsertChangeLogs(new ArrayList<>(logs));
    }

    /**
     * 取某单据的变更历史（详情装配用；按创建时间倒序）。
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @param limit   最多条数（{@code null} 表示不限）
     * @return 日志集合（非 null）
     */
    public List<CtmsChangeLog> listOf(ErpDocType docType, String docId, Integer limit)
    {
        CtmsChangeLog query = new CtmsChangeLog();
        query.setObjectType(docType == null ? null : docType.getCode());
        query.setObjectId(docId);
        query.setLimit(limit);
        List<CtmsChangeLog> logs = changeLogMapper.selectChangeLogList(query);
        return logs == null ? new ArrayList<CtmsChangeLog>() : logs;
    }

    /**
     * 操作人姓名兜底（无登录上下文时给 {@code system}）。
     *
     * @param username 登录名
     * @return 归一后的登录名
     */
    public static String operatorName(String username)
    {
        return ErpSalRules.isBlank(username) ? ErpSalRules.DEFAULT_USERNAME : username;
    }

    /**
     * 操作人ID兜底。
     *
     * @param userId 用户ID
     * @return 归一后的用户ID
     */
    public static String operatorId(String userId)
    {
        return ErpSalRules.isBlank(userId) ? ErpSalRules.DEFAULT_USER_ID : userId;
    }
}
