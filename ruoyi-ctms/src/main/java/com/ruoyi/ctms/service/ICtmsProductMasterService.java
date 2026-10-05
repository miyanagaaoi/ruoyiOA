package com.ruoyi.ctms.service;

import java.util.List;

import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.domain.CtmsWarehouse;

/**
 * <p> 物料域主数据服务（2.0 B3 §3.3）：商品类型树 / 计量单位 / 仓库 / 物料档案。 </p>
 *
 * <p> 本接口是这批主数据<b>业务口径的唯一落点</b>：控制器只做参数透传，
 * 前端只做体验校验。口径与规格
 * {@code openspec/changes/oa-purchase-sales-stock/specs/erp/stock-master-data/spec.md} 对齐： </p>
 * <ol>
 *   <li> 类型树任意节点深度 ≤ 5、同父下名称唯一、仅叶子可挂物料、有子类型或有物料禁止删除； </li>
 *   <li> 计量单位编码全局唯一、小数位 0~4、被物料引用后禁止删除； </li>
 *   <li> 仓库编码全局唯一（B3 阶段无单据/库存表，删除守卫留给 B4）； </li>
 *   <li> 物料编码全局唯一（留空自动生成）、必须关联叶子类型与计量单位、默认单价与安全库存不允许为负。 </li>
 * </ol>
 *
 * @author 二开
 */
public interface ICtmsProductMasterService
{
    /**
     * 查询商品类型列表（name 模糊、parentId 精确、enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 类型集合
     */
    List<CtmsProductType> selectProductTypeList(CtmsProductType query);

    /**
     * 查询商品类型树：把子类型挂到父节点的 {@code children} 上（层级不限，两级以上同样用 children 递归）。
     *
     * @param query 查询条件
     * @return 根节点集合（父不在结果集内的节点也视作根，便于按条件裁剪子树）
     */
    List<CtmsProductType> selectProductTypeTree(CtmsProductType query);

    /**
     * 按主键查询商品类型。
     *
     * @param id 类型ID
     * @return 类型；不存在返回 null
     */
    CtmsProductType selectProductTypeById(String id);

    /**
     * 新增商品类型：层级由父级推导（≤5），物化路径由父路径拼接，同父下名称唯一。
     *
     * @param productType 类型（code 留空时退化为 PT+类型id）
     */
    void insertProductType(CtmsProductType productType);

    /**
     * 修改商品类型：不允许把父级改到自己的后代（会成环），其余同新增。
     *
     * @param productType 类型
     */
    void updateProductType(CtmsProductType productType);

    /**
     * 删除商品类型：有子类型或有物料挂载时拒绝。
     *
     * @param id 类型ID
     */
    void deleteProductTypeById(String id);

    /**
     * 查询计量单位列表。
     *
     * @param query 查询条件
     * @return 单位集合
     */
    List<CtmsUom> selectUomList(CtmsUom query);

    /**
     * 计量单位选择器（B4 §2.2）：**只返回启用中的单位**，供物料建档时的单位下拉使用。
     *
     * @return 启用中的单位（按 code 升序）
     */
    List<CtmsUom> selectUomOptions();

    /**
     * 按主键查询计量单位。
     *
     * @param id 单位ID
     * @return 单位；不存在返回 null
     */
    CtmsUom selectUomById(String id);

    /**
     * 新增计量单位：编码/名称非空、编码全局唯一、小数位 0~4（null 默认 2）。
     *
     * @param uom 单位
     */
    void insertUom(CtmsUom uom);

    /**
     * 修改计量单位（编码不可改）。
     *
     * @param uom 单位
     */
    void updateUom(CtmsUom uom);

    /**
     * 删除计量单位：被物料引用时拒绝。
     *
     * @param id 单位ID
     */
    void deleteUomById(String id);

    /**
     * 查询仓库列表。
     *
     * @param query 查询条件
     * @return 仓库集合
     */
    List<CtmsWarehouse> selectWarehouseList(CtmsWarehouse query);

    /**
     * 仓库选择器（B4 §2.3）：**只返回启用中的仓库**，供新单据的仓库下拉使用
     * （规格「停用仓库 MUST NOT 出现在新单据的仓库下拉中」）。
     *
     * @return 启用中的仓库（按 code 升序）
     */
    List<CtmsWarehouse> selectWarehouseOptions();

    /**
     * 按主键查询仓库。
     *
     * @param id 仓库ID
     * @return 仓库；不存在返回 null
     */
    CtmsWarehouse selectWarehouseById(String id);

    /**
     * 新增仓库：编码/名称非空、**编码与名称都全局唯一**（B4 §2.3）。
     *
     * @param warehouse 仓库
     */
    void insertWarehouse(CtmsWarehouse warehouse);

    /**
     * 修改仓库（编码不可改；改名同样查重并排除自身）。
     *
     * @param warehouse 仓库
     */
    void updateWarehouse(CtmsWarehouse warehouse);

    /**
     * 删除仓库（B4 §2.3 的守卫已落地）：**有库存结存、有库存流水、被任一单据引用时被拒绝**
     * （{@code 仓库已有库存结存，无法删除} / {@code 仓库已有库存流水，无法删除} /
     * {@code 仓库已被单据引用，无法删除}），无引用时物理删除。
     *
     * @param id 仓库ID
     */
    void deleteWarehouseById(String id);

    /**
     * 查询物料列表（name/code 模糊、productTypeId/uomId/enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 物料集合
     */
    List<CtmsProduct> selectProductList(CtmsProduct query);

    /**
     * 物料选择器（B4 §2.4）：**只返回启用中的物料**，供新单据行项的物料下拉使用。
     *
     * @return 启用中的物料（按 code 升序）
     */
    List<CtmsProduct> selectProductOptions();

    /**
     * 按主键查询物料。
     *
     * @param id 物料ID
     * @return 物料；不存在返回 null
     */
    CtmsProduct selectProductById(String id);

    /**
     * 新增物料：必须挂叶子类型与存在的单位；编码留空时自动生成；金额与库存不允许为负。
     *
     * @param product 物料
     */
    void insertProduct(CtmsProduct product);

    /**
     * 修改物料：编码不可改（服务端用库中原值覆盖入参），其余同新增。
     *
     * @param product 物料
     */
    void updateProduct(CtmsProduct product);

    /**
     * 删除物料（B4 §2.4 的守卫已落地）：**有库存结存、有库存流水、被单据行项引用时被拒绝**
     * （{@code 物料已有库存结存，无法删除} / {@code 物料已有库存流水，无法删除} /
     * {@code 物料已被单据行项引用，无法删除}），无引用时物理删除。
     *
     * @param id 物料ID
     */
    void deleteProductById(String id);
}
