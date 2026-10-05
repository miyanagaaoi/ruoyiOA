package com.ruoyi.ctms.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.domain.CtmsWarehouse;

/**
 * <p> 仓库档案 {@code t_ctms_warehouse} 的数据访问（2.0 B3 §3.3）。 </p>
 *
 * <p> B3 阶段还没有库存/单据表，所以<b>没有</b>引用统计方法；
 * B4 接入单据与库存后需要补「有结存或被单据引用时禁止删除」的守卫。 </p>
 *
 * <p> 对应 XML：{@code resources/mapper/ctms/CtmsWarehouseMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface CtmsWarehouseMapper
{
    /**
     * 查询仓库列表（code 模糊、name 模糊、enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 仓库集合，按 code 升序
     */
    List<CtmsWarehouse> selectWarehouseList(CtmsWarehouse query);

    /**
     * 按主键查询。
     *
     * @param id 仓库ID
     * @return 仓库；不存在返回 null
     */
    CtmsWarehouse selectWarehouseById(@Param("id") String id);

    /**
     * 按编码查询（全局唯一判定，含停用项）。
     *
     * @param code 仓库编码
     * @return 仓库；无返回 null
     */
    CtmsWarehouse selectWarehouseByCode(@Param("code") String code);

    /**
     * 新增。
     *
     * @param warehouse 仓库
     * @return 影响行数
     */
    int insertWarehouse(CtmsWarehouse warehouse);

    /**
     * 修改（不改 code）。
     *
     * @param warehouse 仓库
     * @return 影响行数
     */
    int updateWarehouse(CtmsWarehouse warehouse);

    /**
     * 按主键物理删除（B3 阶段无引用统计，守卫留给 B4）。
     *
     * @param id 仓库ID
     * @return 影响行数
     */
    int deleteWarehouseById(@Param("id") String id);
}
