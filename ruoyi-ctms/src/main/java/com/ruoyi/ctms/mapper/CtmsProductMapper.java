package com.ruoyi.ctms.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.ctms.domain.CtmsProduct;

/**
 * <p> 物料档案 {@code t_ctms_product} 的数据访问（2.0 B3 §3.3）。 </p>
 *
 * <p> {@code countProductsByType}/{@code countProductsByUom} 分别是商品类型与计量单位的
 * 删除守卫与自动编码序号的取数来源，因此只依赖本表即可判定。 </p>
 *
 * <p> 对应 XML：{@code resources/mapper/ctms/CtmsProductMapper.xml}。 </p>
 *
 * @author 二开
 */
public interface CtmsProductMapper
{
    /**
     * 查询物料列表（name/code 模糊、productTypeId 精确、uomId 精确、enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 物料集合，按 code 升序
     */
    List<CtmsProduct> selectProductList(CtmsProduct query);

    /**
     * 按主键查询。
     *
     * @param id 物料ID
     * @return 物料；不存在返回 null
     */
    CtmsProduct selectProductById(@Param("id") String id);

    /**
     * 按编码查询（全局唯一判定）。
     *
     * @param code 物料编码
     * @return 物料；无返回 null
     */
    CtmsProduct selectProductByCode(@Param("code") String code);

    /**
     * 新增。
     *
     * @param product 物料
     * @return 影响行数
     */
    int insertProduct(CtmsProduct product);

    /**
     * 修改（不改 code）。
     *
     * @param product 物料
     * @return 影响行数
     */
    int updateProduct(CtmsProduct product);

    /**
     * 按主键物理删除。
     *
     * @param id 物料ID
     * @return 影响行数
     */
    int deleteProductById(@Param("id") String id);
}
