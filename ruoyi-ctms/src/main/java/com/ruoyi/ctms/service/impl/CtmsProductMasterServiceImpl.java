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
import com.ruoyi.ctms.mapper.CtmsProductMapper;
import com.ruoyi.ctms.mapper.CtmsProductTypeMapper;
import com.ruoyi.ctms.mapper.CtmsUomMapper;
import com.ruoyi.ctms.mapper.CtmsWarehouseMapper;
import com.ruoyi.ctms.service.ICtmsProductMasterService;
import com.ruoyi.ctms.support.ProductMasterRules;

/**
 * <p> 物料域主数据服务实现（2.0 B3 §3.3）。 </p>
 *
 * <p> <b>口径集中在这里，控制器不做业务判断</b>：类型树层级与物化路径、同父同名、叶子约束、
 * 编码唯一与自动生成、负值拒绝、引用删除守卫，全部在下面各方法的第一段校验里完成。 </p>
 *
 * <p> <b>审计字段口径</b>：{@code createId/updateId} 是<b>用户ID列</b>，
 * {@code createBy/updateBy} 是<b>登录名快照列</b>，两者都要写。
 * 取当前用户的两个 {@code protected} 方法内部捕获 {@code SecurityUtils} 的异常并兜底
 * （{@value #DEFAULT_USER_ID} / {@value #DEFAULT_USERNAME}）——
 * 内存桩单测、数据迁移脚本、定时任务都没有登录上下文，
 * 不兜底会直接抛「获取用户ID异常」把主数据写入全部打挂。 </p>
 *
 * <p> 事务：本类方法都是「先校验后单条写」，没有多表写入，因此不额外声明事务注解；
 * B4 接入单据引用统计后若出现跨表写，再在具体方法上补 {@code @Transactional}。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsProductMasterServiceImpl implements ICtmsProductMasterService
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
        // 编码不可改：update 语句里不含 code，这里回填原值只为让返回对象与库内一致
        warehouse.setCode(exist.getCode());
        warehouse.setUpdateId(currentUserId());
        warehouse.setUpdateBy(currentUsername());
        warehouse.setUpdateTime(DateUtils.getNowDate());
        warehouseMapper.updateWarehouse(warehouse);
    }

    /**
     * 删除仓库（物理删除）。
     *
     * <p> ⚠ <b>B3 阶段故意不做引用校验</b>：此时还没有库存结存表与出入库单据表，
     * 任何「引用统计」都只能查空表，写了就是假守卫。
     * 规格要求的「仓库被单据引用/有结存时禁止删除（{@code 仓库被单据引用禁止删除}）」
     * 属于 <b>B4</b>（{@code oa-purchase-sales-stock}），B4 接入单据域后必须在此方法内补上。 </p>
     *
     * @param id 仓库ID
     */
    @Override
    public void deleteWarehouseById(String id)
    {
        warehouseMapper.deleteWarehouseById(id);
    }

    /* ==================== 物料档案 ==================== */

    @Override
    public List<CtmsProduct> selectProductList(CtmsProduct query)
    {
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

    @Override
    public void deleteProductById(String id)
    {
        productMapper.deleteProductById(id);
    }

    /* ==================== 内部方法 ==================== */

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
