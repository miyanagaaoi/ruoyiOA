package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.FlowSimple;

import java.util.List;

/**
 * <p> 简化流程定义 Mapper（表 t_flow_simple） </p>
 *
 * @author 二开
 */
public interface FlowSimpleMapper {

    /** 按主键查询 */
    FlowSimple selectById(String id);

    /** 按流程定义 key 查询未删除的记录（key 在未删除范围内唯一，由服务层保证） */
    FlowSimple selectByDefKey(String defKey);

    /** 查询列表 */
    List<FlowSimple> selectList(FlowSimple query);

    /** 新增 */
    int insert(FlowSimple flowSimple);

    /** 更新（含 content / status / version / deployId 等） */
    int update(FlowSimple flowSimple);

    /** 逻辑删除 */
    int deleteById(FlowSimple flowSimple);
}
