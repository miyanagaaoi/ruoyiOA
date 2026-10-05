package com.ruoyi.ctms.erp.stockops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.erp.stockops.domain.ErpTransfer;

/**
 * <p> <b>调拨单表头 Mapper</b>（表 {@code t_ctms_transfer}，2.0 B4 任务 6.1）。 </p>
 *
 * <p> XML：{@code resources/mapper/erp/ErpTransferMapper.xml}。
 * 与 {@code ErpStockInMapper} 同口径：列表默认排除已作废/已删除、数据范围片段由服务层拼出、
 * {@code doc_no} 与创建快照（{@code dept_id}/{@code create_id}/{@code create_by}）在 update 里不可改。 </p>
 *
 * @author 二开
 */
public interface ErpTransferMapper
{
    /**
     * 调拨单列表（单号关键字 / 状态 / 两仓 / 单据日期区间 + 数据范围）。
     *
     * @param query 查询条件（{@code dataScopeSql} 由服务层写入）
     * @return 单据集合
     */
    List<ErpTransfer> selectTransferList(ErpTransfer query);

    /**
     * 按主键取表头（不含行项）。
     *
     * @param id 单据ID
     * @return 单据；不存在返回 null
     */
    ErpTransfer selectTransferById(@Param("id") String id);

    /**
     * 新增调拨单（id/doc_no 由服务层生成）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int insertTransfer(ErpTransfer doc);

    /**
     * 覆盖式更新（状态 / 痕迹列 / 过账标记 / 两仓 / 审计块；单号与创建快照不可改）。
     *
     * @param doc 单据
     * @return 影响行数
     */
    int updateTransfer(ErpTransfer doc);

    /**
     * 按主键批量删除（物理删除；业务侧只允许删除草稿）。
     *
     * @param ids 单据ID数组
     * @return 影响行数
     */
    int deleteTransferByIds(@Param("ids") String[] ids);
}
