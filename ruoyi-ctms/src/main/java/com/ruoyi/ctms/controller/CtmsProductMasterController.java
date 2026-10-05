package com.ruoyi.ctms.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.domain.CtmsWarehouse;
import com.ruoyi.ctms.service.ICtmsProductMasterService;

/**
 * <p> 物料域主数据控制器（2.0 B3 §3.3）：商品类型树、计量单位、仓库、物料档案。 </p>
 *
 * <p> <b>权限点不新增</b>：本批沿用既有真源 {@code ctms:partner:*}（档案类主数据统一归
 * 「往来单位」资源），即 {@code ctms:partner:list} / {@code ctms:partner:query} /
 * {@code ctms:partner:add} / {@code ctms:partner:edit} / {@code ctms:partner:remove}。
 * 新增权限点意味着要同步改 {@code sys_menu} 与前端菜单，本批不做。 </p>
 *
 * <p> <b>路径</b>：类级前缀 {@code /ctms}，方法级写全资源子路径，
 * 因此实际接口是 {@code /ctms/product-type/list}、{@code /ctms/uom/list}、
 * {@code /ctms/warehouse/list}、{@code /ctms/product/list} 等
 * （与前端 {@code src/api/ctms/masterdata.js} 的调用逐条对齐）。 </p>
 *
 * <p> <b>路径变量收窄</b>：四个资源的标识变量都写成 {@code {id:[A-Za-z0-9]+}}，
 * 否则 {@code /ctms/product-type/tree} 会被 {@code /{id}} 抢先匹配成 id="tree"。
 * id 由 {@code IdUtils.fastSimpleUUID()} 产生（大写十六进制），字符集正好落在该正则内。 </p>
 *
 * <p> 业务口径全部在 {@link ICtmsProductMasterService} 实现里，控制器只做参数透传与分页。 </p>
 *
 * @author 二开
 */
@RestController
@RequestMapping("/ctms")
public class CtmsProductMasterController extends BaseController
{
    @Autowired
    private ICtmsProductMasterService productMasterService;

    /* ==================== 商品类型 ==================== */

    /**
     * 查询商品类型列表（name 模糊、parentId / enableFlag 精确）。
     *
     * @param query 查询条件
     * @return 分页后的类型集合
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/product-type/list")
    public TableDataInfo listProductType(CtmsProductType query)
    {
        startPage();
        List<CtmsProductType> list = productMasterService.selectProductTypeList(query);
        return getDataTable(list);
    }

    /**
     * 查询商品类型树（子类型挂在父节点的 children 上）。
     *
     * @param query 查询条件
     * @return 类型树
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/product-type/tree")
    public AjaxResult treeProductType(CtmsProductType query)
    {
        return success(productMasterService.selectProductTypeTree(query));
    }

    /**
     * 查询商品类型详细。
     *
     * @param id 类型ID
     * @return 类型
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/product-type/{id:[A-Za-z0-9]+}")
    public AjaxResult getProductType(@PathVariable("id") String id)
    {
        return success(productMasterService.selectProductTypeById(id));
    }

    /**
     * 新增商品类型。
     *
     * @param productType 类型
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "商品类型", businessType = BusinessType.INSERT)
    @PostMapping("/product-type")
    public AjaxResult addProductType(@RequestBody CtmsProductType productType)
    {
        productMasterService.insertProductType(productType);
        return success(productType);
    }

    /**
     * 修改商品类型（不允许移动到自己的下级）。
     *
     * @param productType 类型
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "商品类型", businessType = BusinessType.UPDATE)
    @PutMapping("/product-type")
    public AjaxResult editProductType(@RequestBody CtmsProductType productType)
    {
        productMasterService.updateProductType(productType);
        return success(productType);
    }

    /**
     * 删除商品类型（有子类型或有物料时被服务端拒绝）。
     *
     * @param id 类型ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "商品类型", businessType = BusinessType.DELETE)
    @DeleteMapping("/product-type/{id:[A-Za-z0-9]+}")
    public AjaxResult removeProductType(@PathVariable("id") String id)
    {
        productMasterService.deleteProductTypeById(id);
        return success();
    }

    /* ==================== 计量单位 ==================== */

    /**
     * 查询计量单位列表。
     *
     * @param query 查询条件
     * @return 分页后的单位集合
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/uom/list")
    public TableDataInfo listUom(CtmsUom query)
    {
        startPage();
        List<CtmsUom> list = productMasterService.selectUomList(query);
        return getDataTable(list);
    }

    /**
     * 查询计量单位详细。
     *
     * @param id 单位ID
     * @return 单位
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/uom/{id:[A-Za-z0-9]+}")
    public AjaxResult getUom(@PathVariable("id") String id)
    {
        return success(productMasterService.selectUomById(id));
    }

    /**
     * 新增计量单位。
     *
     * @param uom 单位
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "计量单位", businessType = BusinessType.INSERT)
    @PostMapping("/uom")
    public AjaxResult addUom(@RequestBody CtmsUom uom)
    {
        productMasterService.insertUom(uom);
        return success(uom);
    }

    /**
     * 修改计量单位（编码不可改）。
     *
     * @param uom 单位
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "计量单位", businessType = BusinessType.UPDATE)
    @PutMapping("/uom")
    public AjaxResult editUom(@RequestBody CtmsUom uom)
    {
        productMasterService.updateUom(uom);
        return success(uom);
    }

    /**
     * 删除计量单位（被物料引用时被服务端拒绝）。
     *
     * @param id 单位ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "计量单位", businessType = BusinessType.DELETE)
    @DeleteMapping("/uom/{id:[A-Za-z0-9]+}")
    public AjaxResult removeUom(@PathVariable("id") String id)
    {
        productMasterService.deleteUomById(id);
        return success();
    }

    /* ==================== 仓库 ==================== */

    /**
     * 查询仓库列表。
     *
     * @param query 查询条件
     * @return 分页后的仓库集合
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/warehouse/list")
    public TableDataInfo listWarehouse(CtmsWarehouse query)
    {
        startPage();
        List<CtmsWarehouse> list = productMasterService.selectWarehouseList(query);
        return getDataTable(list);
    }

    /**
     * 查询仓库详细。
     *
     * @param id 仓库ID
     * @return 仓库
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/warehouse/{id:[A-Za-z0-9]+}")
    public AjaxResult getWarehouse(@PathVariable("id") String id)
    {
        return success(productMasterService.selectWarehouseById(id));
    }

    /**
     * 新增仓库。
     *
     * @param warehouse 仓库
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "仓库", businessType = BusinessType.INSERT)
    @PostMapping("/warehouse")
    public AjaxResult addWarehouse(@RequestBody CtmsWarehouse warehouse)
    {
        productMasterService.insertWarehouse(warehouse);
        return success(warehouse);
    }

    /**
     * 修改仓库（编码不可改）。
     *
     * @param warehouse 仓库
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "仓库", businessType = BusinessType.UPDATE)
    @PutMapping("/warehouse")
    public AjaxResult editWarehouse(@RequestBody CtmsWarehouse warehouse)
    {
        productMasterService.updateWarehouse(warehouse);
        return success(warehouse);
    }

    /**
     * 删除仓库。
     *
     * <p> B3 阶段直接物理删除；「有结存或被单据引用禁止删除」的守卫留给 B4
     * （详见服务实现 {@code deleteWarehouseById} 的注释）。 </p>
     *
     * @param id 仓库ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "仓库", businessType = BusinessType.DELETE)
    @DeleteMapping("/warehouse/{id:[A-Za-z0-9]+}")
    public AjaxResult removeWarehouse(@PathVariable("id") String id)
    {
        productMasterService.deleteWarehouseById(id);
        return success();
    }

    /* ==================== 物料档案 ==================== */

    /**
     * 查询物料列表。
     *
     * @param query 查询条件
     * @return 分页后的物料集合
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:list')")
    @GetMapping("/product/list")
    public TableDataInfo listProduct(CtmsProduct query)
    {
        startPage();
        List<CtmsProduct> list = productMasterService.selectProductList(query);
        return getDataTable(list);
    }

    /**
     * 查询物料详细。
     *
     * @param id 物料ID
     * @return 物料
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:query')")
    @GetMapping("/product/{id:[A-Za-z0-9]+}")
    public AjaxResult getProduct(@PathVariable("id") String id)
    {
        return success(productMasterService.selectProductById(id));
    }

    /**
     * 新增物料（code 留空时由服务端按「类型编码 + 4 位序号」生成）。
     *
     * @param product 物料
     * @return 结果（含服务端生成的 code 与 id）
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:add')")
    @Log(title = "物料", businessType = BusinessType.INSERT)
    @PostMapping("/product")
    public AjaxResult addProduct(@RequestBody CtmsProduct product)
    {
        productMasterService.insertProduct(product);
        return success(product);
    }

    /**
     * 修改物料（编码不可改，入参 code 被服务端忽略）。
     *
     * @param product 物料
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:edit')")
    @Log(title = "物料", businessType = BusinessType.UPDATE)
    @PutMapping("/product")
    public AjaxResult editProduct(@RequestBody CtmsProduct product)
    {
        productMasterService.updateProduct(product);
        return success(product);
    }

    /**
     * 删除物料。
     *
     * @param id 物料ID
     * @return 结果
     */
    @PreAuthorize("@ss.hasPermi('ctms:partner:remove')")
    @Log(title = "物料", businessType = BusinessType.DELETE)
    @DeleteMapping("/product/{id:[A-Za-z0-9]+}")
    public AjaxResult removeProduct(@PathVariable("id") String id)
    {
        productMasterService.deleteProductById(id);
        return success();
    }
}
