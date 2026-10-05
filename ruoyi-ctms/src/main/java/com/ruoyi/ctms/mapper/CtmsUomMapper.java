package com.ruoyi.ctms.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.domain.CtmsUom;

/**
 * <p> 计量单位 {@code t_ctms_uom} 的数据访问（2.0 B3 §3.3）。 </p>
 *
 * <p> 对应 XML：{@code resources/mapper/ctms/CtmsUomMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface CtmsUomMapper
{
    /**
     * 查询计量单位列表（code 模糊、name 模糊、enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 单位集合，按 code 升序
     */
    List<CtmsUom> selectUomList(CtmsUom query);

    /**
     * 按主键查询。
     *
     * @param id 单位ID
     * @return 单位；不存在返回 null
     */
    CtmsUom selectUomById(@Param("id") String id);

    /**
     * 按编码查询（全局唯一判定，含停用项）。
     *
     * @param code 单位编码
     * @return 单位；无返回 null
     */
    CtmsUom selectUomByCode(@Param("code") String code);

    /**
     * 统计引用该单位的物料数量（删除守卫）。
     *
     * @param uomId 单位ID
     * @return 物料数量
     */
    int countProductsByUom(@Param("uomId") String uomId);

    /**
     * 新增。
     *
     * @param uom 单位
     * @return 影响行数
     */
    int insertUom(CtmsUom uom);

    /**
     * 修改（不改 code）。
     *
     * @param uom 单位
     * @return 影响行数
     */
    int updateUom(CtmsUom uom);

    /**
     * 按主键物理删除（删除守卫由服务层负责）。
     *
     * @param id 单位ID
     * @return 影响行数
     */
    int deleteUomById(@Param("id") String id);
}
