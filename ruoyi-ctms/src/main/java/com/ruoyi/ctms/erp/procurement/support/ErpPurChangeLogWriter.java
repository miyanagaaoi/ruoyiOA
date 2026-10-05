package com.ruoyi.ctms.erp.procurement.support;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.procurement.ErpPurRules;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>单据变更历史的公共写入点</b>（2.0 B4 任务 3.4 的"留痕"与任务 3.1 的编辑留痕）。 </p>
 *
 * <p> <b>为什么复用 B3 的 {@code t_ctms_change_log}</b>：该表 DDL 注释明确写着「B3/B4 共用」，
 * 且 {@code contract_id} 已按移植清单 §2.8 #10 由 NOT NULL 放宽为可空，单据日志靠
 * {@code object_type} / {@code object_id} 多态定位（{@code AC-V2-37}）。
 * 因此 B4 不新增表、也不改 {@code base} 包 —— 直接消费 B3 已交付的
 * {@link CtmsChangeLogMapper}（本包只调用它的接口，不改它）。 </p>
 *
 * <p> <b>同事务</b>："归零即完成"的留痕必须与状态变更在同一事务里：留痕失败不能让状态悄悄改了。
 * 所以调用方在 {@code @Transactional} 方法内调用本类，本类<b>不自开事务、也不吞异常</b>。 </p>
 *
 * <p> <b>幂等由调用方保证</b>：本类没有"去重"逻辑 —— 判据是"当前状态不是 completed 才写"
 * （见 {@code ErpPurchaseRequestServiceImpl.checkAutoComplete}），而不是在这里查一遍日志
 * （那会变成"日志驱动状态"，本末倒置）。 </p>
 *
 * @author 二开
 */
public final class ErpPurChangeLogWriter
{
    private final CtmsChangeLogMapper changeLogMapper;

    /**
     * @param changeLogMapper 变更历史 Mapper（B3 交付，{@code t_ctms_change_log}）
     */
    public ErpPurChangeLogWriter(CtmsChangeLogMapper changeLogMapper)
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
        // ⚠ 刻意不写 contractId：单据日志靠 (object_type, object_id) 定位（移植清单 §2.8 #10）
        log.setFieldName(ErpPurRules.LOG_FIELD_STATUS);
        log.setOldValue(oldStatus);
        log.setNewValue(newStatus);
        log.setNote(note);
        log.setSource(source == null ? ErpPurRules.LOG_SOURCE_MANUAL : source);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setObjectType(docType == null ? null : docType.getCode());
        log.setObjectId(docId);
        log.setCreateId(operatorId);
        log.setCreateBy(operatorName);
        changeLogMapper.insertChangeLog(log);
    }

    /**
     * 批量写留痕（一次编辑产生多条时用；当前只在状态流转里单条写）。
     *
     * @param logs 待写入的日志
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
}
