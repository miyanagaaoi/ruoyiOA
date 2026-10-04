package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.FlowSimpleHistory;

import java.util.List;

/**
 * <p> 简化流程发布历史 Mapper（表 t_flow_simple_history，只追加） </p>
 *
 * @author 二开
 */
public interface FlowSimpleHistoryMapper {

    /** 按 key 查询全部版本（倒序） */
    List<FlowSimpleHistory> selectByDefKey(String defKey);

    /** 按 key + 版本号查询 */
    FlowSimpleHistory selectByDefKeyAndVersion(FlowSimpleHistory query);

    /** 查询某 key 当前最大版本号（无记录返回 null） */
    Integer selectMaxVersion(String defKey);

    /** 追加一条发布快照 */
    int insert(FlowSimpleHistory history);
}
