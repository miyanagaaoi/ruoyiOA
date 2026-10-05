package com.ruoyi.ctms.erp.posting.support;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;

/**
 * <p> <b>库存单据变更历史的写入点</b>（2.0 B4 任务 5.6；表 {@code t_ctms_change_log}）。 </p>
 *
 * <p> <b>为什么复用 B3 的 {@code t_ctms_change_log}</b>：该表 DDL 注释写明「B3/B4 共用」，
 * 且 {@code contract_id} 已放宽为可空，单据日志靠 {@code object_type} / {@code object_id}
 * 多态定位。因此 B4 不建新表、不改 B3 的表结构，只消费它的 Mapper。 </p>
 *
 * <p> <b>同事务</b>：调用方在 {@code @Transactional} 方法里调用本类；本类不自开事务、
 * 不吞异常 —— 留痕失败必须让状态变更一起回滚，否则会出现"状态变了但没人知道是谁改的"。 </p>
 *
 * <p> <b>每次审核/反审核至少一条</b>（tasks.md §5.6 的断言）：
 * 审核写字段 {@code posted}（{@code 0 → 1}）+ 状态 {@code submitted → approved} 两条中的后者，
 * 反审核写 {@code posted 1 → 0} 与红冲说明。 </p>
 *
 * <p> <b>与 {@code procurement.support.ErpPurChangeLogWriter} 的关系</b>：两者都是
 * {@code t_ctms_change_log} 的薄封装，字段名口径一致（{@code status} / {@code manual} / {@code auto}）。
 * 它们应由 {@code base} 包收口成唯一实现（已报文 captain）；在那之前各包自持一份，
 * <b>不得</b>跨包引用别的组的 support 类。 </p>
 *
 * @author 二开
 */
public final class ErpStockChangeLogWriter
{
    private final CtmsChangeLogMapper changeLogMapper;

    /**
     * @param changeLogMapper 变更历史 Mapper（B3 交付）
     */
    public ErpStockChangeLogWriter(CtmsChangeLogMapper changeLogMapper)
    {
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 写一条留痕。
     *
     * @param docType      单据类型（{@code object_type} = 它的 code）
     * @param docId        单据ID
     * @param fieldName    字段名（{@code status} / {@code posted} / {@code _items} …）
     * @param oldValue     旧值（可空）
     * @param newValue     新值（可空）
     * @param note         备注（如反审核原因）
     * @param source       来源（{@code manual} / {@code auto}）
     * @param operatorId   操作人用户ID
     * @param operatorName 操作人登录名
     */
    public void write(ErpDocType docType, String docId, String fieldName, String oldValue, String newValue,
                      String note, String source, String operatorId, String operatorName)
    {
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        // 刻意不写 contractId：单据日志靠 (object_type, object_id) 定位
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setNote(note);
        log.setSource(source);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setObjectType(docType == null ? null : docType.getCode());
        log.setObjectId(docId);
        log.setCreateId(operatorId);
        log.setCreateBy(operatorName);
        changeLogMapper.insertChangeLog(log);
    }

    /**
     * 取某单据的变更历史（按创建时间倒序）。
     *
     * @param docType 单据类型
     * @param docId   单据ID
     * @param limit   最多条数（null 表示不限）
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
