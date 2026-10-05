package com.ruoyi.ctms.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.ctms.domain.CtmsChangeLog;

/**
 * <p> 字段级变更历史的数据访问接口，对应 XML
 * {@code resources/mapper/ctms/CtmsChangeLogMapper.xml}。 </p>
 *
 * <p> 本表<b>只追加不更新</b>：没有 update / delete 方法；时间线按 {@code create_time} 倒序读取，
 * 并按 {@code limit} 截断最近若干条。 </p>
 *
 * <p> 多态口径：{@code contractId} 与 {@code objectType} / {@code objectId} 都是「传了才过滤」，
 * B4 单据侧只写后者。 </p>
 *
 * @author 二开
 */
public interface CtmsChangeLogMapper
{
    /**
     * 变更历史列表（contractId / objectType / objectId 传了才过滤；
     * limit 为 null 表示不限，否则取最近 limit 条）
     *
     * @param query 查询条件
     * @return 变更历史集合（按创建时间倒序）
     */
    List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query);

    /**
     * 批量写入变更历史（id 由服务层生成；一条 SQL 多行，降低合同编辑的往返次数）
     *
     * @param logs 变更历史集合（服务层保证非空）
     * @return 影响行数
     */
    int batchInsertChangeLogs(@Param("logs") List<CtmsChangeLog> logs);

    /**
     * 单条写入变更历史
     *
     * @param log 变更历史
     * @return 影响行数
     */
    int insertChangeLog(CtmsChangeLog log);
}
