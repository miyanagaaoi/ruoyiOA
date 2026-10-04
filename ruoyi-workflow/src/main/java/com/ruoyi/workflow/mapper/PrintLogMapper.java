package com.ruoyi.workflow.mapper;

import com.ruoyi.workflow.domain.PrintLog;

import java.util.List;

/**
 * <p> 打印日志 Mapper（表 t_print_log，只追加） </p>
 *
 * <p> 只提供 insert 与查询，**不提供 update / delete** —— 与"打印留痕"的语义一致。 </p>
 *
 * @author 二开
 */
public interface PrintLogMapper {

    /** 新增（只追加） */
    int insert(PrintLog printLog);

    /** 按业务ID查询打印记录（倒序） */
    List<PrintLog> selectByBusinessId(String businessId);

    /** 查询列表（可按 businessId / 打印人过滤） */
    List<PrintLog> selectList(PrintLog query);
}
