package com.ruoyi.ctms.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.domain.CtmsWarehouse;
import com.ruoyi.ctms.erp.master.ErpMasterRefGuards;
import com.ruoyi.ctms.erp.master.mapper.ErpMasterRefMapper;
import com.ruoyi.ctms.mapper.CtmsProductMapper;
import com.ruoyi.ctms.mapper.CtmsProductTypeMapper;
import com.ruoyi.ctms.mapper.CtmsUomMapper;
import com.ruoyi.ctms.mapper.CtmsWarehouseMapper;
import com.ruoyi.ctms.service.ICtmsProductMasterService;
import com.ruoyi.ctms.support.ProductMasterRules;

/**
 * <p> 物料域主数据服务实现（2.0 B3 §3.3；B4 §2.3/§2.4 在其上补引用守卫与选择器）。 </p>
 *
 * <p> <b>口径集中在这里，控制器不做业务判断</b>：类型树层级与物化路径、同父同名、叶子约束、
 * 编码唯一与自动生成、负值拒绝、引用删除守卫，全部在下面各方法的第一段校验里完成。 </p>
 *
 * <p> <b>B4 补的三件事</b>（B3 阶段无单据/结存表，刻意留到 B4，见
 * {@code oa-contract-ledger/notes/master-data-notes.md} §1.3）： </p>
 * <ol>
 *   <li> 仓库<b>名称</b>唯一（B4 规格：{@code 仓库 SHALL 具有全局唯一的编码与名称}；
 *        B3 的 DDL 只有 {@code uk_warehouse_code}，因此名称唯一在服务层强制）； </li>
 *   <li> 仓库/物料的<b>引用守卫</b>：有结存、有流水、被单据引用时禁止物理删除
 *        （{@link ErpMasterRefGuards} + {@link ErpMasterRefMapper}），
 *        把"外键 1451 的 500"变成可读的中文拒绝； </li>
 *   <li> <b>选择器</b>接口：{@code selectWarehouseOptions} / {@code selectProductOptions} /
 *        {@code selectUomOptions} 只返回启用中的档案（规格「停用后不出现在新单据下拉」）。 </li>
 * </ol>
 *
 * <p> <b>审计字段口径</b>：{@code createId/updateId} 是<b>用户ID列</b>，
 * {@code createBy/updateBy} 是<b>登录名快照列</b>，两者都要写。
 * 取当前用户的两个 {@code protected} 方法内部捕获 {@code SecurityUtils} 的异常并兜底
 * （{@value #DEFAULT_USER_ID} / {@value #DEFAULT_USERNAME}）——
 * 内存桩单测、数据迁移脚本、定时任务都没有登录上下文，
 * 不兜底会直接抛「获取用户ID异常」把主数据写入全部打挂。 </p>
 *
 * <p> 事务：本类方法都是「先校验后单条写」，没有多表写入，因此不额外声明事务注解；
 * 引用统计是只读查询，同样不需要事务。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsProductMasterServiceImpl implements ICtmsProductMasterService, ErpMasterRefGuards.RefCounter
{
    /** 启用标志：1-启用。 */
    private static final String ENABLE_FLAG_ON = "1";

    /** 计量单位小数位默认值（与 DDL 的 {@code DEFAULT 2} 对齐）。 */
    private static final int DEFAULT_UOM_DECIMALS = 2;

    /** 无登录上下文时的创建人用户ID兜底。 */
    private static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的创建人登录名兜底。 */
    private static final String DEFAULT_USERNAME = "system";

    /** 自动编码的安全上限：序号最多顺延 1000 次，避免脏数据导致死循环。 */
    private static final int MAX_CODE_SEQ_RETRY = 1000;

    @Autowired
    private CtmsProductTypeMapper productTypeMapper;

    @Autowired
    private CtmsUomMapper uomMapper;

    @Autowired
    private CtmsWarehouseMapper warehouseMapper;

    @Autowired
    private CtmsProductMapper productMapper;

    /**
     * 引用统计（B4 §2.3 新增）：仓库/物料的删除守卫用它把"被结存/流水/单据引用"变成
     * 可读的中文拒绝，而不是数据库外键的 1451。
     *
     * <p> 声明为<b>必需</b>依赖（不是 {@code required = false}）：删档这种不可逆动作
     * 不允许出现"统计没装配就悄悄放行"的路径；装配缺失时
     * {@link ErpMasterRefGuards} 也会保护性拒绝（见其类注释）。 </p>
     */
    @Autowired
    private ErpMasterRefMapper masterRefMapper;

    /* ==================== 引用统计回调（B4 §2.3） ==================== */

    @Override
    public int countStockRefs(String warehouseId, String productId)
    {
        return requireRefMapper().countStockRefs(warehouseId, productId);
    }

    @Override
    public int countLedgerRefs(String warehouseId, String productId)
    {
        return requireRefMapper().countLedgerRefs(warehouseId, productId);
    }

    @Override
    public int countDocRefsByWarehouse(String warehouseId)
    {
        return requireRefMapper().countDocRefsByWarehouse(warehouseId);
    }

    @Override
    public int countDocRefsByProduct(String productId)
    {
        return requireRefMapper().countDocRefsByProduct(productId);
    }

    /**
     * 取引用统计 Mapper；未装配时<b>保护性拒绝</b>（宁可报错，也不要无校验地物理删档）。
     *
     * @return 引用统计 Mapper
     * @throws ServiceException 未装配
     */
    private ErpMasterRefMapper requireRefMapper()
    {
        if (masterRefMapper == null)
        {
            throw new ServiceException(ErpMasterRefGuards.COUNTER_MISSING_MESSAGE);
        }
        return masterRefMapper;
    }

    /* ==================== 商品类型树 ==================== */

    @Override
    public List<CtmsProductType> selectProductTypeList(CtmsProductType query)
    {
        return productTypeMapper.selectProductTypeList(query);
    }

    @Override
    public List<CtmsProductType> selectProductTypeTree(CtmsProductType query)
    {
        return buildProductTypeTree(productTypeMapper.selectProductTypeList(query));
    }

    @Override
    public CtmsProductType selectProductTypeById(String id)
    {
        return productTypeMapper.selectProductTypeById(id);
    }

    @Override
    public void insertProductType(CtmsProductType productType)
    {
        if (productType == null)
        {
            throw new ServiceException("商品类型名称不能为空");
        }
        if (ProductMasterRules.isBlank(productType.getName()))
        {
            throw new ServiceException("商品类型名称不能为空");
        }
        if (ProductMasterRules.isBlank(productType.getId()))
        {
            // id 先生成：类型编码留空时要退化为 PT+类型id，需要先有 id
            productType.setId(IdUtils.fastSimpleUUID());
        }
        String parentId = normalizeId(productType.getParentId());
        productType.setParentId(parentId);
        if (parentId == null)
        {
            // 根节点：第 1 级、物化路径 "/"
            productType.setLevel(ProductMasterRules.childLevel(null));
            productType.setPath("/");
        }
        else
        {
            CtmsProductType parent = requireEnabledParent(parentId);
            productType.setLevel(ProductMasterRules.childLevel(parent.getLevel()));
            productType.setPath(ProductMasterRules.childPath(parent.getPath(), parent.getId()));
        }
        // 同父下名称唯一（根节点之间也一样）
        CtmsProductType same = productTypeMapper.selectProductTypeByNameAndParent(productType.getName(), parentId);
        if (same != null && !same.getId().equals(productType.getId()))
        {
            throw new ServiceException("同级下已存在同名商品类型");
        }
        if (ProductMasterRules.isBlank(productType.getCode()))
        {
            // 编码可空：为空时按「P + 类型id 前缀」生成**限长**编码（保证 ≤ varchar(32)），
            // 这样物料编码前缀总有来源。⚠ 曾经的 "PT" + 完整 32 位 id = 34 字符会超长报 500。
            productType.setCode(ProductMasterRules.generateTypeCode(productType.getId()));
        }
        else
        {
            productType.setCode(productType.getCode().trim());
            if (ProductMasterRules.isTypeCodeTooLong(productType.getCode()))
            {
                // 在入口处拒绝，而不是让 MySQL 报 Data truncation（后者是 500，用户看不懂）
                throw new ServiceException("商品类型编码长度不能超过 "
                        + ProductMasterRules.MAX_TYPE_CODE_LENGTH + " 个字符");
            }
        }
        if (productType.getSort() == null)
        {
            productType.setSort(0);
        }
        if (ProductMasterRules.isBlank(productType.getEnableFlag()))
        {
            productType.setEnableFlag(ENABLE_FLAG_ON);
        }
        productType.setCreateId(currentUserId());
        productType.setCreateBy(currentUsername());
        productType.setCreateTime(DateUtils.getNowDate());
        productType.setUpdateId(currentUserId());
        productType.setUpdateBy(currentUsername());
        productType.setUpdateTime(DateUtils.getNowDate());
        productTypeMapper.insertProductType(productType);
    }

    @Override
    public void updateProductType(CtmsProductType productType)
    {
        if (productType == null || ProductMasterRules.isBlank(productType.getId()))
        {
            throw new ServiceException("商品类型不存在");
        }
        CtmsProductType exist = productTypeMapper.selectProductTypeById(productType.getId());
        if (exist == null)
        {
            throw new ServiceException("商品类型不存在");
        }
        if (ProductMasterRules.isBlank(productType.getName()))
        {
            throw new ServiceException("商品类型名称不能为空");
        }
        String parentId = normalizeId(productType.getParentId());
        productType.setParentId(parentId);
        if (parentId == null)
        {
            productType.setLevel(ProductMasterRules.childLevel(null));
            productType.setPath("/");
        }
        else
        {
            // 成环守卫要排在父级存在性之前：把父级指向自己的后代时，提示应该是「移动到下级」
            checkMoveIntoDescendant(productType.getId(), parentId);
            CtmsProductType parent = requireEnabledParent(parentId);
            productType.setLevel(ProductMasterRules.childLevel(parent.getLevel()));
            productType.setPath(ProductMasterRules.childPath(parent.getPath(), parent.getId()));
        }
        // 同父下名称唯一，但要排除自身
        CtmsProductType same = productTypeMapper.selectProductTypeByNameAndParent(productType.getName(), parentId);
        if (same != null && !same.getId().equals(productType.getId()))
        {
            throw new ServiceException("同级下已存在同名商品类型");
        }
        // 编码不可改：入参 code 不参与 update 语句（编码是物料编码前缀来源，不允许漂移）
        if (productType.getSort() == null)
        {
            productType.setSort(exist.getSort() == null ? 0 : exist.getSort());
        }
        if (ProductMasterRules.isBlank(productType.getEnableFlag()))
        {
            productType.setEnableFlag(exist.getEnableFlag());
        }
        productType.setUpdateId(currentUserId());
        productType.setUpdateBy(currentUsername());
        productType.setUpdateTime(DateUtils.getNowDate());
        productTypeMapper.updateProductType(productType);
    }

    @Override
    public void deleteProductTypeById(String id)
    {
        if (productTypeMapper.countChildren(id) > 0)
        {
            throw new ServiceException("存在下级商品类型，无法删除");
        }
        if (productTypeMapper.countProductsByType(id) > 0)
        {
            throw new ServiceException("该类型下存在物料，无法删除");
        }
        productTypeMapper.deleteProductTypeById(id);
    }

    /* ==================== 计量单位 ==================== */

    @Override
    public List<CtmsUom> selectUomList(CtmsUom query)
    {
        return uomMapper.selectUomList(query);
    }

    /**
     * 计量单位选择器（B4 §2.2）：<b>只返回启用中的单位</b>。
     *
     * <p> 单位的选择发生在物料建档时（物料必挂单位），停用单位不应再被新物料引用；
     * 历史物料仍按 {@code uom_id} 正常展示（停用不级联）。 </p>
     *
     * @return 启用中的单位（按 code 升序）
     */
    @Override
    public List<CtmsUom> selectUomOptions()
    {
        CtmsUom query = new CtmsUom();
        query.setEnableFlag(ENABLE_FLAG_ON);
        return uomMapper.selectUomList(query);
    }

    @Override
    public CtmsUom selectUomById(String id)
    {
        return uomMapper.selectUomById(id);
    }

    @Override
    public void insertUom(CtmsUom uom)
    {
        if (uom == null)
        {
            throw new ServiceException("计量单位编码不能为空");
        }
        if (ProductMasterRules.isBlank(uom.getCode()))
        {
            throw new ServiceException("计量单位编码不能为空");
        }
        if (ProductMasterRules.isBlank(uom.getName()))
        {
            throw new ServiceException("计量单位名称不能为空");
        }
        uom.setCode(uom.getCode().trim());
        if (uomMapper.selectUomByCode(uom.getCode()) != null)
        {
            throw new ServiceException("计量单位编码已存在");
        }
        if (uom.getDecimals() == null)
        {
            // DDL 默认 2；null 归一后再校验
            uom.setDecimals(Integer.valueOf(DEFAULT_UOM_DECIMALS));
        }
        ProductMasterRules.checkUomDecimals(uom.getDecimals());
        if (ProductMasterRules.isBlank(uom.getId()))
        {
            uom.setId(IdUtils.fastSimpleUUID());
        }
        if (ProductMasterRules.isBlank(uom.getEnableFlag()))
        {
            uom.setEnableFlag(ENABLE_FLAG_ON);
        }
        uom.setCreateId(currentUserId());
        uom.setCreateBy(currentUsername());
        uom.setCreateTime(DateUtils.getNowDate());
        uom.setUpdateId(currentUserId());
        uom.setUpdateBy(currentUsername());
        uom.setUpdateTime(DateUtils.getNowDate());
        uomMapper.insertUom(uom);
    }

    @Override
    public void updateUom(CtmsUom uom)
    {
        if (uom == null || ProductMasterRules.isBlank(uom.getId()))
        {
            throw new ServiceException("计量单位不存在");
        }
        CtmsUom exist = uomMapper.selectUomById(uom.getId());
        if (exist == null)
        {
            throw new ServiceException("计量单位不存在");
        }
        if (ProductMasterRules.isBlank(uom.getCode()))
        {
            throw new ServiceException("计量单位编码不能为空");
        }
        if (ProductMasterRules.isBlank(uom.getName()))
        {
            throw new ServiceException("计量单位名称不能为空");
        }
        if (uom.getDecimals() == null)
        {
            uom.setDecimals(exist.getDecimals() == null ? Integer.valueOf(DEFAULT_UOM_DECIMALS) : exist.getDecimals());
        }
        ProductMasterRules.checkUomDecimals(uom.getDecimals());
        if (ProductMasterRules.isBlank(uom.getEnableFlag()))
        {
            uom.setEnableFlag(exist.getEnableFlag());
        }
        uom.setCode(exist.getCode());
        uom.setUpdateId(currentUserId());
        uom.setUpdateBy(currentUsername());
        uom.setUpdateTime(DateUtils.getNowDate());
        uomMapper.updateUom(uom);
    }

    @Override
    public void deleteUomById(String id)
    {
        if (uomMapper.countProductsByUom(id) > 0)
        {
            throw new ServiceException("计量单位已被物料引用，无法删除");
        }
        uomMapper.deleteUomById(id);
    }

    /* ==================== 仓库 ==================== */

    @Override
    public List<CtmsWarehouse> selectWarehouseList(CtmsWarehouse query)
    {
        return warehouseMapper.selectWarehouseList(query);
    }

    /**
     * 仓库选择器（B4 §2.3）：<b>只返回启用中的仓库</b>，供新单据的仓库下拉使用。
     *
     * <p> 与"档案管理页"刻意分成两个接口（沿用 B3 对往来单位的同一口径）：
     * 档案页要能看到停用项，单据下拉不能看到停用项；合成一个接口必然二选一地出缺陷。 </p>
     *
     * @return 启用中的仓库（按 code 升序）
     */
    @Override
    public List<CtmsWarehouse> selectWarehouseOptions()
    {
        CtmsWarehouse query = new CtmsWarehouse();
        query.setEnableFlag(ENABLE_FLAG_ON);
        return warehouseMapper.selectWarehouseList(query);
    }

    @Override
    public CtmsWarehouse selectWarehouseById(String id)
    {
        return warehouseMapper.selectWarehouseById(id);
    }

    @Override
    public void insertWarehouse(CtmsWarehouse warehouse)
    {
        if (warehouse == null)
        {
            throw new ServiceException("仓库编码不能为空");
        }
        if (ProductMasterRules.isBlank(warehouse.getCode()))
        {
            throw new ServiceException("仓库编码不能为空");
        }
        if (ProductMasterRules.isBlank(warehouse.getName()))
        {
            throw new ServiceException("仓库名称不能为空");
        }
        warehouse.setCode(warehouse.getCode().trim());
        if (warehouseMapper.selectWarehouseByCode(warehouse.getCode()) != null)
        {
            throw new ServiceException("仓库编码已存在");
        }
        // B4 §2.3：仓库**名称**也必须全局唯一（B3 的 DDL 只有 uk_warehouse_code，名称唯一在服务层强制）
        if (findWarehouseByName(warehouse.getName(), null) != null)
        {
            throw new ServiceException("仓库名称已存在");
        }
        if (ProductMasterRules.isBlank(warehouse.getId()))
        {
            warehouse.setId(IdUtils.fastSimpleUUID());
        }
        if (ProductMasterRules.isBlank(warehouse.getEnableFlag()))
        {
            warehouse.setEnableFlag(ENABLE_FLAG_ON);
        }
        warehouse.setCreateId(currentUserId());
        warehouse.setCreateBy(currentUsername());
        warehouse.setCreateTime(DateUtils.getNowDate());
        warehouse.setUpdateId(currentUserId());
        warehouse.setUpdateBy(currentUsername());
        warehouse.setUpdateTime(DateUtils.getNowDate());
        warehouseMapper.insertWarehouse(warehouse);
    }

    @Override
    public void updateWarehouse(CtmsWarehouse warehouse)
    {
        if (warehouse == null || ProductMasterRules.isBlank(warehouse.getId()))
        {
            throw new ServiceException("仓库不存在");
        }
        CtmsWarehouse exist = warehouseMapper.selectWarehouseById(warehouse.getId());
        if (exist == null)
        {
            throw new ServiceException("仓库不存在");
        }
        if (ProductMasterRules.isBlank(warehouse.getCode()))
        {
            throw new ServiceException("仓库编码不能为空");
        }
        if (ProductMasterRules.isBlank(warehouse.getName()))
        {
            throw new ServiceException("仓库名称不能为空");
        }
        if (ProductMasterRules.isBlank(warehouse.getEnableFlag()))
        {
            warehouse.setEnableFlag(exist.getEnableFlag());
        }
        // B4 §2.3：改名同样要查重（排除自身），否则"改名绕过去"就成了唯一性缺口
        if (findWarehouseByName(warehouse.getName(), warehouse.getId()) != null)
        {
            throw new ServiceException("仓库名称已存在");
        }
        // 编码不可改：update 语句里不含 code，这里回填原值只为让返回对象与库内一致
        warehouse.setCode(exist.getCode());
        warehouse.setUpdateId(currentUserId());
        warehouse.setUpdateBy(currentUsername());
        warehouse.setUpdateTime(DateUtils.getNowDate());
        warehouseMapper.updateWarehouse(warehouse);
    }

    /**
     * 删除仓库（无引用时物理删除；有结存/流水/单据引用时被拒绝）。
     *
     * <p> <b>B4 §2.3 的守卫</b>（B3 阶段故意留到 B4，见
     * {@code oa-contract-ledger/notes/master-data-notes.md} §1.3）：
     * 仓库表被 {@code t_ctms_stock} / {@code t_ctms_stock_ledger} / 4 张单据表头
     * （以及行项的 {@code warehouse_id}）外键或业务引用；不提前拦就会变成
     * 数据库的 1451 错误（前端看到 500）。 </p>
     *
     * <p> 判定与文案全部在 {@link ErpMasterRefGuards#checkWarehouseDeletable} 一处，
     * 本方法只负责"取行 → 守卫 → 删"三步。 </p>
     *
     * @param id 仓库ID
     * @throws ServiceException 仓库不存在 / 有引用（结存、流水、单据之一）
     */
    @Override
    public void deleteWarehouseById(String id)
    {
        if (ProductMasterRules.isBlank(id))
        {
            throw new ServiceException("仓库不存在");
        }
        if (warehouseMapper.selectWarehouseById(id) == null)
        {
            throw new ServiceException("仓库不存在");
        }
        ErpMasterRefGuards.checkWarehouseDeletable(this, id);
        warehouseMapper.deleteWarehouseById(id);
    }

    /* ==================== 物料档案 ==================== */

    @Override
    public List<CtmsProduct> selectProductList(CtmsProduct query)
    {
        return productMapper.selectProductList(query);
    }

    /**
     * 物料选择器（B4 §2.4）：<b>只返回启用中的物料</b>，供新单据行项的物料下拉使用。
     *
     * <p> 停用物料在<b>写入</b>时还会被 T1 公共层的
     * {@code ErpMasterGuards.applyItemSnapshot} 再拦一次（带行号），
     * 本接口只是"界面层面不给你选"，不是唯一防线。 </p>
     *
     * @return 启用中的物料（按 code 升序）
     */
    @Override
    public List<CtmsProduct> selectProductOptions()
    {
        CtmsProduct query = new CtmsProduct();
        query.setEnableFlag(ENABLE_FLAG_ON);
        return productMapper.selectProductList(query);
    }

    @Override
    public CtmsProduct selectProductById(String id)
    {
        return productMapper.selectProductById(id);
    }

    @Override
    public void insertProduct(CtmsProduct product)
    {
        if (product == null)
        {
            throw new ServiceException("物料名称不能为空");
        }
        if (ProductMasterRules.isBlank(product.getName()))
        {
            throw new ServiceException("物料名称不能为空");
        }
        CtmsProductType type = checkProductBinding(product.getProductTypeId(), product.getUomId());
        if (ProductMasterRules.isBlank(product.getId()))
        {
            product.setId(IdUtils.fastSimpleUUID());
        }
        if (ProductMasterRules.isBlank(product.getCode()))
        {
            // 编码留空：按「类型编码（缺省 PT+类型id）+ 4 位序号」自动生成
            product.setCode(generateProductCode(type));
        }
        else
        {
            product.setCode(product.getCode().trim());
            if (productMapper.selectProductByCode(product.getCode()) != null)
            {
                throw new ServiceException("物料编码已存在");
            }
        }
        // 默认单价：null 视同 0（DDL 是 NOT NULL DEFAULT 0），负数一律拒绝
        BigDecimal defaultPrice = product.getDefaultPrice() == null ? BigDecimal.ZERO : product.getDefaultPrice();
        ProductMasterRules.checkDefaultPrice(defaultPrice);
        product.setDefaultPrice(defaultPrice);
        // 安全库存：null 合法（「未设置」与「0」必须可区分）
        ProductMasterRules.checkSafetyStock(product.getSafetyStock());
        if (ProductMasterRules.isBlank(product.getEnableFlag()))
        {
            product.setEnableFlag(ENABLE_FLAG_ON);
        }
        product.setCreateId(currentUserId());
        product.setCreateBy(currentUsername());
        product.setCreateTime(DateUtils.getNowDate());
        product.setUpdateId(currentUserId());
        product.setUpdateBy(currentUsername());
        product.setUpdateTime(DateUtils.getNowDate());
        productMapper.insertProduct(product);
    }

    @Override
    public void updateProduct(CtmsProduct product)
    {
        if (product == null || ProductMasterRules.isBlank(product.getId()))
        {
            throw new ServiceException("物料不存在");
        }
        CtmsProduct exist = productMapper.selectProductById(product.getId());
        if (exist == null)
        {
            throw new ServiceException("物料不存在");
        }
        if (ProductMasterRules.isBlank(product.getName()))
        {
            throw new ServiceException("物料名称不能为空");
        }
        checkProductBinding(product.getProductTypeId(), product.getUomId());
        BigDecimal defaultPrice = product.getDefaultPrice() == null ? BigDecimal.ZERO : product.getDefaultPrice();
        ProductMasterRules.checkDefaultPrice(defaultPrice);
        product.setDefaultPrice(defaultPrice);
        ProductMasterRules.checkSafetyStock(product.getSafetyStock());
        // 编码不可改的口径：**直接忽略入参 code**，用库中原值覆盖，
        // 前端误传改码不会生效（自动编码是物料在单据行项上的稳定标识，不允许漂移）。
        product.setCode(exist.getCode());
        if (ProductMasterRules.isBlank(product.getEnableFlag()))
        {
            product.setEnableFlag(exist.getEnableFlag());
        }
        product.setUpdateId(currentUserId());
        product.setUpdateBy(currentUsername());
        product.setUpdateTime(DateUtils.getNowDate());
        productMapper.updateProduct(product);
    }

    /**
     * 删除物料（无引用时物理删除；有结存/流水/单据行项引用时被拒绝）。
     *
     * <p> B4 §2.4 的守卫：行项的 {@code product_id} 是 DB 级外键，
     * 结存/流水也各有两个外键指向物料 —— 不提前拦就是 1451 的 500。 </p>
     *
     * @param id 物料ID
     * @throws ServiceException 物料不存在 / 有引用
     */
    @Override
    public void deleteProductById(String id)
    {
        if (ProductMasterRules.isBlank(id))
        {
            throw new ServiceException("物料不存在");
        }
        if (productMapper.selectProductById(id) == null)
        {
            throw new ServiceException("物料不存在");
        }
        ErpMasterRefGuards.checkProductDeletable(this, id);
        productMapper.deleteProductById(id);
    }

    /* ==================== 内部方法 ==================== */

    /**
     * <p> 按名称精确查找仓库（含停用行；名称全局唯一，停用项也占名）。 </p>
     *
     * <p> <b>为什么用"列表 + Java 精确比对"而不是新建一个 Mapper 方法</b>：
     * {@code CtmsWarehouseMapper} 是 B3 的产物，B4 只允许引用、不重建 B3 的表与档案接口
     * （{@code ddl-scope.md} §4）。{@code selectWarehouseList} 的 {@code name} 条件是 {@code like}，
     * 返回的是<b>超集</b>，在 Java 里做一次精确比对即可得到唯一性判定；
     * 名称里带 {@code %} / {@code _} 时 like 只会多返回、不会漏返回，因此不会出现"重名没查到"。 </p>
     *
     * @param name      仓库名称（空白 → 返回 null，由调用方先报"名称不能为空"）
     * @param excludeId 需要排除的仓库ID（修改场景排除自身；新增传 null）
     * @return 命中的同名仓库；无同名返回 null
     */
    private CtmsWarehouse findWarehouseByName(String name, String excludeId)
    {
        if (ProductMasterRules.isBlank(name))
        {
            return null;
        }
        String target = name.trim();
        CtmsWarehouse query = new CtmsWarehouse();
        query.setName(target);
        List<CtmsWarehouse> rows = warehouseMapper.selectWarehouseList(query);
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        for (CtmsWarehouse row : rows)
        {
            if (row == null || ProductMasterRules.isBlank(row.getName()))
            {
                continue;
            }
            if (!target.equals(row.getName().trim()))
            {
                continue;
            }
            if (excludeId != null && excludeId.equals(row.getId()))
            {
                continue;
            }
            return row;
        }
        return null;
    }

    /**
     * 校验物料的类型与单位绑定：类型必须存在、必须是叶子、必须启用；单位必须存在。
     *
     * @param productTypeId 商品类型ID
     * @param uomId         计量单位ID
     * @return 命中的商品类型（自动编码需要它的 code 与 id）
     */
    private CtmsProductType checkProductBinding(String productTypeId, String uomId)
    {
        if (ProductMasterRules.isBlank(productTypeId))
        {
            throw new ServiceException("物料必须选择商品类型");
        }
        if (ProductMasterRules.isBlank(uomId))
        {
            throw new ServiceException("物料必须选择计量单位");
        }
        CtmsProductType type = productTypeMapper.selectProductTypeById(productTypeId);
        if (type == null)
        {
            throw new ServiceException("商品类型不存在");
        }
        if (productTypeMapper.countChildren(type.getId()) > 0)
        {
            // 只有叶子节点能挂物料：否则「类型改名/移动」会连带影响一批物料的归属语义
            throw new ServiceException("只能在叶子类型下挂物料");
        }
        if (!ENABLE_FLAG_ON.equals(type.getEnableFlag()))
        {
            throw new ServiceException("商品类型已停用");
        }
        if (uomMapper.selectUomById(uomId) == null)
        {
            throw new ServiceException("计量单位不存在");
        }
        return type;
    }

    /**
     * 生成物料编码：{@code 类型编码（为空时 PT+类型id） + 4 位序号}。
     *
     * <p> 序号起点 = 该类型下已有物料数 + 1；若与手工录入的编码撞号则继续顺延，
     * 直到不冲突（最多顺延 {@value #MAX_CODE_SEQ_RETRY} 次）。 </p>
     *
     * @param type 叶子商品类型
     * @return 物料编码
     */
    private String generateProductCode(CtmsProductType type)
    {
        String prefix = ProductMasterRules.isBlank(type.getCode())
                ? ProductMasterRules.generateTypeCode(type.getId())
                : type.getCode();
        // ⚠ 限长：物料编码列是 varchar(32)，后面还要拼 4 位序号，
        //   所以前缀最多只能占 28 位。类型编码是"前缀来源"而非业务标识，截断是安全的方向；
        //   不截断的话会变成 MySQL 的 Data truncation（500），用户看不到可读原因。
        int maxPrefix = ProductMasterRules.MAX_TYPE_CODE_LENGTH - ProductMasterRules.PRODUCT_CODE_SEQ_DIGITS;
        if (prefix != null && prefix.length() > maxPrefix)
        {
            prefix = prefix.substring(0, maxPrefix);
        }
        int seq = productTypeMapper.countProductsByType(type.getId()) + 1;
        String code = prefix + String.format(Locale.ROOT, "%04d", seq);
        int retry = 0;
        while (productMapper.selectProductByCode(code) != null && retry < MAX_CODE_SEQ_RETRY)
        {
            seq++;
            code = prefix + String.format(Locale.ROOT, "%04d", seq);
            retry++;
        }
        return code;
    }

    /**
     * 取父类型并校验：必须存在且启用。
     *
     * @param parentId 父类型ID（非空）
     * @return 父类型
     */
    private CtmsProductType requireEnabledParent(String parentId)
    {
        CtmsProductType parent = productTypeMapper.selectProductTypeById(parentId);
        if (parent == null)
        {
            throw new ServiceException("上级商品类型不存在");
        }
        if (!ENABLE_FLAG_ON.equals(parent.getEnableFlag()))
        {
            throw new ServiceException("上级商品类型已停用");
        }
        return parent;
    }

    /**
     * 成环守卫：从待挂的父节点沿 parent_id 向上走，若遇到自身则说明把节点挂到了自己的下级。
     *
     * @param id       被移动的类型ID
     * @param parentId 目标父类型ID
     */
    private void checkMoveIntoDescendant(String id, String parentId)
    {
        String cursor = parentId;
        int guard = 0;
        while (!ProductMasterRules.isBlank(cursor) && guard <= ProductMasterRules.MAX_TYPE_LEVEL)
        {
            if (cursor.equals(id))
            {
                throw new ServiceException("不能把商品类型移动到自己的下级");
            }
            CtmsProductType node = productTypeMapper.selectProductTypeById(cursor);
            if (node == null)
            {
                return;
            }
            cursor = normalizeId(node.getParentId());
            guard++;
        }
    }

    /**
     * 组装类型树：父节点不在结果集内（含 parent_id 为空）的节点视作根，
     * 这样 {@code parentId} 条件裁剪子树时也能直接得到该子树的根。
     *
     * @param list 扁平类型列表
     * @return 根节点集合
     */
    private List<CtmsProductType> buildProductTypeTree(List<CtmsProductType> list)
    {
        List<CtmsProductType> trees = new ArrayList<>();
        if (list == null || list.isEmpty())
        {
            return trees;
        }
        Set<String> ids = new HashSet<>();
        for (CtmsProductType node : list)
        {
            ids.add(node.getId());
        }
        for (CtmsProductType node : list)
        {
            if (ProductMasterRules.isBlank(node.getParentId()) || !ids.contains(node.getParentId()))
            {
                trees.add(node);
            }
        }
        // visited 兼作环保护：脏数据（A 的父是 B、B 的父是 A）不会把接口递归到栈溢出
        Set<String> visited = new HashSet<>();
        for (CtmsProductType root : trees)
        {
            recursionFn(list, root, visited);
        }
        return trees;
    }

    /**
     * 递归把子节点挂到 {@code children} 上。
     *
     * @param list    扁平列表
     * @param parent  当前父节点
     * @param visited 已处理节点（环保护）
     */
    private void recursionFn(List<CtmsProductType> list, CtmsProductType parent, Set<String> visited)
    {
        if (parent.getId() != null && !visited.add(parent.getId()))
        {
            return;
        }
        List<CtmsProductType> children = getChildList(list, parent);
        parent.setChildren(children);
        for (CtmsProductType child : children)
        {
            recursionFn(list, child, visited);
        }
    }

    /**
     * 取某节点的直接子节点。
     *
     * @param list   扁平列表
     * @param parent 父节点
     * @return 子节点集合（无子返回空集合）
     */
    private List<CtmsProductType> getChildList(List<CtmsProductType> list, CtmsProductType parent)
    {
        List<CtmsProductType> children = new ArrayList<>();
        for (CtmsProductType node : list)
        {
            if (!ProductMasterRules.isBlank(node.getParentId()) && node.getParentId().equals(parent.getId()))
            {
                children.add(node);
            }
        }
        return children;
    }

    /**
     * 把空白标识归一为 {@code null}（根节点的 parent_id 必须是 SQL NULL，不能是空串）。
     *
     * @param id 标识
     * @return 去空格后的标识；空白返回 null
     */
    private String normalizeId(String id)
    {
        return ProductMasterRules.isBlank(id) ? null : id.trim();
    }

    /**
     * 当前用户ID（DDL 的 {@code create_id}/{@code update_id} 列）。
     *
     * <p> <b>必须兜底</b>：无登录上下文时 {@code SecurityUtils.getUserId()} 会抛
     * 「获取用户ID异常」，这里捕获后回落为 {@value #DEFAULT_USER_ID}。 </p>
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            return ProductMasterRules.isBlank(userId) ? DEFAULT_USER_ID : userId;
        }
        catch (Exception e)
        {
            return DEFAULT_USER_ID;
        }
    }

    /**
     * 当前用户登录名快照（DDL 的 {@code create_by}/{@code update_by} 列）。
     *
     * <p> 同样兜底：无登录上下文时回落为 {@value #DEFAULT_USERNAME}。 </p>
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return ProductMasterRules.isBlank(username) ? DEFAULT_USERNAME : username;
        }
        catch (Exception e)
        {
            return DEFAULT_USERNAME;
        }
    }
}
