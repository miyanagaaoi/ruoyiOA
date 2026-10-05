package com.ruoyi.ctms.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.domain.CtmsProductType;

/**
 * <p> 商品类型树 {@code t_ctms_product_type} 的数据访问（2.0 B3 §3.3）。 </p>
 *
 * <p> 本接口由全局 {@code @MapperScan("com.ruoyi.**.mapper")} 自动注册；
 * 对应 XML 必须是 {@code resources/mapper/ctms/CtmsProductTypeMapper.xml}
 * （文件名以 Mapper.xml 结尾，否则 {@code mapperLocations} 扫不到，
 * 症状是运行时报 {@code Invalid bound statement (not found)}）。 </p>
 *
 * @author 二开
 */
public interface CtmsProductTypeMapper
{
    /**
     * 查询商品类型列表（name 模糊、parentId 精确、enableFlag 精确）。
     *
     * @param query 查询条件（可空字段不参与过滤）
     * @return 类型集合，按 sort、code 升序
     */
    List<CtmsProductType> selectProductTypeList(CtmsProductType query);

    /**
     * 按主键查询。
     *
     * @param id 类型ID
     * @return 类型；不存在返回 null
     */
    CtmsProductType selectProductTypeById(@Param("id") String id);

    /**
     * 按「同父 + 名称」查询（同级名称唯一判定）。
     *
     * @param name     类型名称
     * @param parentId 上级类型ID（根传 null）
     * @return 命中的类型；无返回 null
     */
    CtmsProductType selectProductTypeByNameAndParent(@Param("name") String name, @Param("parentId") String parentId);

    /**
     * 统计下级类型数量（删除守卫）。
     *
     * @param parentId 上级类型ID
     * @return 下级数量
     */
    int countChildren(@Param("parentId") String parentId);

    /**
     * 统计挂在该类型下的物料数量（删除守卫）。
     *
     * @param productTypeId 类型ID
     * @return 物料数量
     */
    int countProductsByType(@Param("productTypeId") String productTypeId);

    /**
     * 新增。
     *
     * @param productType 类型
     * @return 影响行数
     */
    int insertProductType(CtmsProductType productType);

    /**
     * 修改（不改 code）。
     *
     * @param productType 类型
     * @return 影响行数
     */
    int updateProductType(CtmsProductType productType);

    /**
     * 按主键物理删除（删除守卫由服务层负责）。
     *
     * @param id 类型ID
     * @return 影响行数
     */
    int deleteProductTypeById(@Param("id") String id);
}
