package com.ruoyi.ctms.service.impl;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.domain.CtmsWarehouse;
import com.ruoyi.ctms.mapper.CtmsProductMapper;
import com.ruoyi.ctms.mapper.CtmsProductTypeMapper;
import com.ruoyi.ctms.mapper.CtmsUomMapper;
import com.ruoyi.ctms.mapper.CtmsWarehouseMapper;
import com.ruoyi.ctms.support.ProductMasterRules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link CtmsProductMasterServiceImpl} 的业务口径单测（2.0 B3 §3.3）。 </p>
 *
 * <p> <b>不依赖 Spring、不依赖数据库</b>：四个 Mapper 用测试类内的内存桩实现
 * （{@code HashMap}/{@code ArrayList} 存储，{@code count*} 由桩数据现算），
 * 通过反射注入服务的 {@code @Autowired} 字段。这样跑得快，也不会因为环境缺库而假绿。 </p>
 *
 * <p> 每个用例断言的是<b>前端会看到的错误文案</b>（前端与验收脚本共用同一批字符串），
 * 以及「被拒时确实没有落库」这一副作用。 </p>
 *
 * @author 二开
 */
public class CtmsProductMasterServiceImplTest
{
    private final StubProductMapper productMapper = new StubProductMapper();

    private final StubProductTypeMapper typeMapper = new StubProductTypeMapper(productMapper);

    private final StubUomMapper uomMapper = new StubUomMapper(productMapper);

    private final StubWarehouseMapper warehouseMapper = new StubWarehouseMapper();

    private final CtmsProductMasterServiceImpl service = new CtmsProductMasterServiceImpl();

    @Before
    public void setUp()
    {
        inject("productTypeMapper", typeMapper);
        inject("uomMapper", uomMapper);
        inject("warehouseMapper", warehouseMapper);
        inject("productMapper", productMapper);
    }

    /* ==================== 商品类型树 ==================== */

    @Test
    public void 同父下重名类型被拒()
    {
        insertType("办公用品", null, null);
        assertRejected("同级下已存在同名商品类型", new Runnable()
        {
            @Override
            public void run()
            {
                insertType("办公用品", null, null);
            }
        });
        assertEquals("被拒的类型不能落库", 1, typeMapper.store.size());
    }

    @Test
    public void 不同父下同名类型允许()
    {
        CtmsProductType a = insertType("办公用品", null, null);
        CtmsProductType b = insertType("耗材", null, null);
        // 两个不同父下各有一个「打印纸」是合法的
        insertType("打印纸", a.getId(), null);
        insertType("打印纸", b.getId(), null);
        assertEquals(4, typeMapper.store.size());
    }

    @Test
    public void 类型树最多5级_第五级通过第六级被拒()
    {
        CtmsProductType parent = null;
        for (int i = 1; i <= ProductMasterRules.MAX_TYPE_LEVEL; i++)
        {
            CtmsProductType node = insertType("层级" + i, parent == null ? null : parent.getId(), null);
            assertEquals("第 " + i + " 级的 level 必须是 " + i, i, node.getLevel().intValue());
            if (parent == null)
            {
                assertEquals("根节点物化路径固定为 /", "/", node.getPath());
            }
            else
            {
                assertEquals(ProductMasterRules.childPath(parent.getPath(), parent.getId()), node.getPath());
                assertEquals("层级反推必须与落库值一致", i, ProductMasterRules.levelOfPath(node.getPath()));
            }
            assertNotNull(node.getCode());
            parent = node;
        }
        final String fifthId = parent.getId();
        assertRejected("商品类型层级不能超过 5 级", new Runnable()
        {
            @Override
            public void run()
            {
                insertType("层级6", fifthId, null);
            }
        });
        assertEquals("第 6 级不能落库", 5, typeMapper.store.size());
    }

    @Test
    public void 有子类型时删除被拒()
    {
        CtmsProductType root = insertType("原材料", null, null);
        CtmsProductType child = insertType("金属", root.getId(), null);
        assertRejected("存在下级商品类型，无法删除", new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteProductTypeById(root.getId());
            }
        });
        assertNotNull("有子类型时父类型必须还在", typeMapper.store.get(root.getId()));
        assertEquals(child.getId(), typeMapper.store.get(child.getId()).getId());
    }

    @Test
    public void 有物料时删除类型被拒()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType leaf = insertType("标准件", null, null);
        insertProduct("螺钉", leaf.getId(), uom.getId(), null);
        assertRejected("该类型下存在物料，无法删除", new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteProductTypeById(leaf.getId());
            }
        });
        assertNotNull("有物料时类型必须还在", typeMapper.store.get(leaf.getId()));
    }

    @Test
    public void 叶子类型无子无物料时可以删除()
    {
        CtmsProductType leaf = insertType("临时类型", null, null);
        service.deleteProductTypeById(leaf.getId());
        assertNull(typeMapper.store.get(leaf.getId()));
    }

    @Test
    public void 上级类型不存在或已停用时被拒()
    {
        assertRejected("上级商品类型不存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertType("孤儿", "NOT_EXIST_ID", null);
            }
        });
        CtmsProductType stopped = insertType("已停用类型", null, null);
        stopped.setEnableFlag("0");
        service.updateProductType(stopped);
        assertRejected("上级商品类型已停用", new Runnable()
        {
            @Override
            public void run()
            {
                insertType("下级", stopped.getId(), null);
            }
        });
    }

    @Test
    public void 不能把类型移动到自己的下级()
    {
        CtmsProductType root = insertType("根类型", null, null);
        CtmsProductType child = insertType("子类型", root.getId(), null);
        // 把根挂到自己的子下面 → 成环
        root.setParentId(child.getId());
        assertRejected("不能把商品类型移动到自己的下级", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateProductType(root);
            }
        });
        // 自己挂自己同样被拒
        child.setParentId(child.getId());
        assertRejected("不能把商品类型移动到自己的下级", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateProductType(child);
            }
        });
        assertEquals("被拒的移动不能改库", null, typeMapper.store.get(root.getId()).getParentId());
    }

    @Test
    public void 修改类型时同父同名要排除自身且重算层级与路径()
    {
        CtmsProductType root = insertType("根类型", null, null);
        CtmsProductType other = insertType("兄弟", null, null);
        CtmsProductType node = insertType("待改名", null, null);
        // 改成自己的名字：应放行（排除自身）
        node.setName("待改名");
        node.setSort(Integer.valueOf(9));
        service.updateProductType(node);
        assertEquals(Integer.valueOf(9), typeMapper.store.get(node.getId()).getSort());
        // 改成兄弟的名字：应被拒
        node.setName("兄弟");
        assertRejected("同级下已存在同名商品类型", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateProductType(node);
            }
        });
        // 移动到 root 下面：层级与物化路径都要重算
        CtmsProductType moved = typeMapper.store.get(node.getId());
        moved.setName("待改名");
        moved.setParentId(root.getId());
        service.updateProductType(moved);
        CtmsProductType saved = typeMapper.store.get(moved.getId());
        assertEquals(Integer.valueOf(2), saved.getLevel());
        assertEquals(ProductMasterRules.childPath(root.getPath(), root.getId()), saved.getPath());
        assertEquals(other.getId(), typeMapper.store.get(other.getId()).getId());
    }

    @Test
    public void 类型树能把子类型挂到父的children上()
    {
        CtmsProductType root = insertType("根", null, null);
        CtmsProductType mid = insertType("中", root.getId(), null);
        CtmsProductType leaf = insertType("叶", mid.getId(), null);
        List<CtmsProductType> tree = service.selectProductTypeTree(new CtmsProductType());
        assertEquals("只有一个根", 1, tree.size());
        CtmsProductType rootNode = tree.get(0);
        assertEquals(root.getId(), rootNode.getId());
        assertEquals(1, rootNode.getChildren().size());
        CtmsProductType midNode = rootNode.getChildren().get(0);
        assertEquals(mid.getId(), midNode.getId());
        assertEquals("两级以上同样用 children 挂", 1, midNode.getChildren().size());
        assertEquals(leaf.getId(), midNode.getChildren().get(0).getId());
        assertEquals(0, midNode.getChildren().get(0).getChildren().size());
    }

    /* ==================== 计量单位 ==================== */

    @Test
    public void 单位编码重复被拒且首尾空格归一后仍算同码()
    {
        insertUom("PCS", "个", Integer.valueOf(2));
        assertRejected("计量单位编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("PCS", "件", Integer.valueOf(2));
            }
        });
        assertRejected("计量单位编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("  PCS  ", "件", Integer.valueOf(2));
            }
        });
        assertEquals(1, uomMapper.store.size());
    }

    @Test
    public void 单位编码与名称非空_小数位越界被拒()
    {
        assertRejected("计量单位编码不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("  ", "个", Integer.valueOf(2));
            }
        });
        assertRejected("计量单位名称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("PCS", "  ", Integer.valueOf(2));
            }
        });
        assertRejected("计量单位小数位只能是 0 到 4", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("PCS", "个", Integer.valueOf(5));
            }
        });
        assertRejected("计量单位小数位只能是 0 到 4", new Runnable()
        {
            @Override
            public void run()
            {
                insertUom("PCS", "个", Integer.valueOf(-1));
            }
        });
        assertEquals("四次都被拒，不能落库", 0, uomMapper.store.size());
    }

    @Test
    public void 单位小数位留空默认2()
    {
        CtmsUom uom = insertUom("BOX", "箱", null);
        assertEquals(Integer.valueOf(2), uom.getDecimals());
    }

    @Test
    public void 单位被物料引用时删除被拒()
    {
        CtmsUom uom = insertUom("KG", "千克", Integer.valueOf(3));
        CtmsProductType leaf = insertType("散料", null, null);
        insertProduct("钢材", leaf.getId(), uom.getId(), null);
        assertRejected("计量单位已被物料引用，无法删除", new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteUomById(uom.getId());
            }
        });
        assertNotNull("被引用的单位必须还在", uomMapper.store.get(uom.getId()));
    }

    /* ==================== 仓库 ==================== */

    @Test
    public void 仓库编码重复被拒()
    {
        insertWarehouse("WH01", "一号仓");
        assertRejected("仓库编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertWarehouse("WH01", "二号仓");
            }
        });
        assertEquals(1, warehouseMapper.store.size());
    }

    @Test
    public void 仓库编码名称非空且B3阶段可直接物理删除()
    {
        assertRejected("仓库编码不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                insertWarehouse(null, "一号仓");
            }
        });
        assertRejected("仓库名称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                insertWarehouse("WH01", null);
            }
        });
        CtmsWarehouse warehouse = insertWarehouse("WH02", "二号仓");
        service.deleteWarehouseById(warehouse.getId());
        assertNull("B3 阶段仓库是物理删除（引用守卫留给 B4）", warehouseMapper.store.get(warehouse.getId()));
    }

    /* ==================== 物料档案 ==================== */

    @Test
    public void 物料自动编码为类型码加四位序号且序号递增()
    {
        CtmsUom uom = insertUom("KG", "千克", Integer.valueOf(3));
        CtmsProductType type = insertType("原材料", null, "MAT");
        CtmsProduct first = insertProduct("钢材", type.getId(), uom.getId(), null);
        assertEquals("MAT" + "0001", first.getCode());
        CtmsProduct second = insertProduct("铝材", type.getId(), uom.getId(), null);
        assertEquals("MAT" + "0002", second.getCode());
        assertNotEquals("两次自动编码必须不同", first.getCode(), second.getCode());
        // 另一个类型独立计数
        CtmsProductType other = insertType("包装物", null, "PKG");
        CtmsProduct third = insertProduct("纸箱", other.getId(), uom.getId(), null);
        assertEquals("PKG0001", third.getCode());
    }

    @Test
    public void 类型编码留空时按P加id前缀生成且不超列宽()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType type = insertType("无名类型", null, null);
        // ⚠ 回归守卫（真库验收抓到过）：曾经的 "PT" + 32 位 id = 34 字符超过 varchar(32)，
        //    建类型直接 500（Data too long for column 'code'）。自动编码必须**限长**。
        assertEquals(ProductMasterRules.generateTypeCode(type.getId()), type.getCode());
        assertTrue("自动生成的类型编码不得超过 varchar(32)",
                type.getCode().length() <= ProductMasterRules.MAX_TYPE_CODE_LENGTH);
        CtmsProduct product = insertProduct("垫片", type.getId(), uom.getId(), null);
        // 前缀 + 4 位序号必须落在 varchar(32) 内：超长前缀会被截到 28 位（见 generateProductCode 的限长注释）
        int maxPrefix = ProductMasterRules.MAX_TYPE_CODE_LENGTH - ProductMasterRules.PRODUCT_CODE_SEQ_DIGITS;
        String expectPrefix = type.getCode().length() > maxPrefix
                ? type.getCode().substring(0, maxPrefix)
                : type.getCode();
        assertEquals(expectPrefix + "0001", product.getCode());
        assertTrue("物料编码也要符合 varchar(32)",
                product.getCode().length() <= ProductMasterRules.MAX_TYPE_CODE_LENGTH);
    }

    @Test
    public void 手工类型编码超长被拒且不留半成品()
    {
        CtmsProductType type = new CtmsProductType();
        type.setName("超长编码类型");
        type.setCode("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");   // 36 字符
        assertRejected("商品类型编码长度不能超过 32 个字符", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertProductType(type);
            }
        });
    }

    @Test
    public void 手工物料编码重复被拒()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType type = insertType("标准件", null, null);
        insertProduct("螺钉", type.getId(), uom.getId(), "M001");
        assertRejected("物料编码已存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("螺栓", type.getId(), uom.getId(), "M001");
            }
        });
        assertEquals(1, productMapper.store.size());
    }

    @Test
    public void 非叶子类型挂物料被拒()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType root = insertType("原材料", null, null);
        insertType("金属", root.getId(), null);
        assertRejected("只能在叶子类型下挂物料", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("钢材", root.getId(), uom.getId(), null);
            }
        });
        assertEquals("被拒的物料不能落库", 0, productMapper.store.size());
    }

    @Test
    public void 物料必填项与关联对象校验()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType leaf = insertType("标准件", null, null);
        assertRejected("物料名称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct(null, leaf.getId(), uom.getId(), null);
            }
        });
        assertRejected("物料必须选择商品类型", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("螺钉", null, uom.getId(), null);
            }
        });
        assertRejected("物料必须选择计量单位", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("螺钉", leaf.getId(), null, null);
            }
        });
        assertRejected("商品类型不存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("螺钉", "NOT_EXIST_ID", uom.getId(), null);
            }
        });
        assertRejected("计量单位不存在", new Runnable()
        {
            @Override
            public void run()
            {
                insertProduct("螺钉", leaf.getId(), "NOT_EXIST_ID", null);
            }
        });
        assertEquals(0, productMapper.store.size());
    }

    @Test
    public void 物料默认单价与安全库存为负被拒()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType leaf = insertType("标准件", null, null);
        final CtmsProduct negativePrice = newProduct("螺钉", leaf.getId(), uom.getId());
        negativePrice.setDefaultPrice(new BigDecimal("-0.01"));
        assertRejected("默认单价不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertProduct(negativePrice);
            }
        });
        final CtmsProduct negativeStock = newProduct("螺栓", leaf.getId(), uom.getId());
        negativeStock.setSafetyStock(new BigDecimal("-1"));
        assertRejected("安全库存不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertProduct(negativeStock);
            }
        });
        assertEquals("两次都被拒，不能落库", 0, productMapper.store.size());
        // 单价留空视同 0、安全库存留空视同「未设置」，都合法
        CtmsProduct ok = insertProduct("垫片", leaf.getId(), uom.getId(), null);
        assertEquals(0, ok.getDefaultPrice().compareTo(BigDecimal.ZERO));
        assertNull(ok.getSafetyStock());
    }

    @Test
    public void 修改物料时编码不可改且校验口径同新增()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(0));
        CtmsProductType leaf = insertType("标准件", null, null);
        CtmsProduct product = insertProduct("螺钉", leaf.getId(), uom.getId(), "M001");
        CtmsProduct edit = newProduct("螺钉改", leaf.getId(), uom.getId());
        edit.setId(product.getId());
        edit.setCode("HACKED");
        edit.setDefaultPrice(new BigDecimal("2.5000"));
        edit.setSafetyStock(new BigDecimal("5"));
        service.updateProduct(edit);
        CtmsProduct saved = productMapper.store.get(product.getId());
        assertEquals("编码不可改：入参 code 必须被忽略", "M001", saved.getCode());
        assertEquals("物料名称改不动才是 bug", "螺钉改", saved.getName());
        assertEquals(0, saved.getDefaultPrice().compareTo(new BigDecimal("2.5000")));

        final CtmsProduct badPrice = newProduct("螺钉改2", leaf.getId(), uom.getId());
        badPrice.setId(product.getId());
        badPrice.setDefaultPrice(new BigDecimal("-1"));
        assertRejected("默认单价不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateProduct(badPrice);
            }
        });
    }

    /* ==================== 审计字段兜底 ==================== */

    @Test
    public void 无登录上下文时新增不抛异常且审计字段兜底()
    {
        CtmsUom uom = insertUom("PCS", "个", Integer.valueOf(2));
        assertEquals("无登录上下文必须兜底为 1", "1", uom.getCreateId());
        assertEquals("1", uom.getUpdateId());
        assertEquals("system", uom.getCreateBy());
        assertEquals("system", uom.getUpdateBy());
        assertNotNull(uom.getCreateTime());
        assertNotNull(uom.getUpdateTime());

        CtmsProductType type = insertType("根类型", null, null);
        assertEquals("1", type.getCreateId());
        assertEquals("system", type.getCreateBy());

        CtmsWarehouse warehouse = insertWarehouse("WH09", "九号仓");
        assertEquals("1", warehouse.getCreateId());

        CtmsProduct product = insertProduct("螺钉", type.getId(), uom.getId(), null);
        assertEquals("1", product.getCreateId());
        assertEquals("system", product.getCreateBy());
    }

    /* ==================== 夹具与断言工具 ==================== */

    private void inject(String fieldName, Object value)
    {
        try
        {
            Field field = CtmsProductMasterServiceImpl.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(service, value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("桩注入失败：" + fieldName, e);
        }
    }

    private void assertRejected(String expectedMessage, Runnable action)
    {
        try
        {
            action.run();
            fail("期望被拒绝并提示「" + expectedMessage + "」，但调用成功了");
        }
        catch (ServiceException e)
        {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    private CtmsProductType insertType(String name, String parentId, String code)
    {
        CtmsProductType type = new CtmsProductType();
        type.setName(name);
        type.setParentId(parentId);
        type.setCode(code);
        service.insertProductType(type);
        return type;
    }

    private CtmsUom insertUom(String code, String name, Integer decimals)
    {
        CtmsUom uom = new CtmsUom();
        uom.setCode(code);
        uom.setName(name);
        uom.setDecimals(decimals);
        service.insertUom(uom);
        return uom;
    }

    private CtmsWarehouse insertWarehouse(String code, String name)
    {
        CtmsWarehouse warehouse = new CtmsWarehouse();
        warehouse.setCode(code);
        warehouse.setName(name);
        service.insertWarehouse(warehouse);
        return warehouse;
    }

    private CtmsProduct newProduct(String name, String productTypeId, String uomId)
    {
        CtmsProduct product = new CtmsProduct();
        product.setName(name);
        product.setProductTypeId(productTypeId);
        product.setUomId(uomId);
        return product;
    }

    private CtmsProduct insertProduct(String name, String productTypeId, String uomId, String code)
    {
        CtmsProduct product = newProduct(name, productTypeId, uomId);
        product.setCode(code);
        service.insertProduct(product);
        return product;
    }

    /* ==================== 内存桩 Mapper ==================== */

    /** 商品类型桩：HashMap 存储，count* 由桩数据现算。 */
    private static class StubProductTypeMapper implements CtmsProductTypeMapper
    {
        private final Map<String, CtmsProductType> store = new LinkedHashMap<String, CtmsProductType>();

        private final StubProductMapper productMapper;

        StubProductTypeMapper(StubProductMapper productMapper)
        {
            this.productMapper = productMapper;
        }

        @Override
        public List<CtmsProductType> selectProductTypeList(CtmsProductType query)
        {
            List<CtmsProductType> result = new ArrayList<CtmsProductType>();
            for (CtmsProductType node : store.values())
            {
                if (query != null)
                {
                    if (!ProductMasterRules.isBlank(query.getName()) && !node.getName().contains(query.getName()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getParentId())
                            && !query.getParentId().equals(node.getParentId()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getEnableFlag())
                            && !query.getEnableFlag().equals(node.getEnableFlag()))
                    {
                        continue;
                    }
                }
                result.add(copy(node));
            }
            return result;
        }

        @Override
        public CtmsProductType selectProductTypeById(String id)
        {
            CtmsProductType node = store.get(id);
            return node == null ? null : copy(node);
        }

        @Override
        public CtmsProductType selectProductTypeByNameAndParent(String name, String parentId)
        {
            for (CtmsProductType node : store.values())
            {
                if (name != null && name.equals(node.getName()) && same(node.getParentId(), parentId))
                {
                    return copy(node);
                }
            }
            return null;
        }

        @Override
        public int countChildren(String parentId)
        {
            int count = 0;
            for (CtmsProductType node : store.values())
            {
                if (same(node.getParentId(), parentId))
                {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countProductsByType(String productTypeId)
        {
            return productMapper.countByType(productTypeId);
        }

        @Override
        public int insertProductType(CtmsProductType productType)
        {
            store.put(productType.getId(), copy(productType));
            return 1;
        }

        @Override
        public int updateProductType(CtmsProductType productType)
        {
            if (!store.containsKey(productType.getId()))
            {
                return 0;
            }
            store.put(productType.getId(), copy(productType));
            return 1;
        }

        @Override
        public int deleteProductTypeById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        private static CtmsProductType copy(CtmsProductType src)
        {
            CtmsProductType t = new CtmsProductType();
            t.setId(src.getId());
            t.setParentId(src.getParentId());
            t.setCode(src.getCode());
            t.setName(src.getName());
            t.setPath(src.getPath());
            t.setLevel(src.getLevel());
            t.setSort(src.getSort());
            t.setEnableFlag(src.getEnableFlag());
            t.setRemark(src.getRemark());
            t.setCreateId(src.getCreateId());
            t.setCreateBy(src.getCreateBy());
            t.setCreateTime(src.getCreateTime());
            t.setUpdateId(src.getUpdateId());
            t.setUpdateBy(src.getUpdateBy());
            t.setUpdateTime(src.getUpdateTime());
            return t;
        }
    }

    /** 计量单位桩。 */
    private static class StubUomMapper implements CtmsUomMapper
    {
        private final Map<String, CtmsUom> store = new LinkedHashMap<String, CtmsUom>();

        private final StubProductMapper productMapper;

        StubUomMapper(StubProductMapper productMapper)
        {
            this.productMapper = productMapper;
        }

        @Override
        public List<CtmsUom> selectUomList(CtmsUom query)
        {
            List<CtmsUom> result = new ArrayList<CtmsUom>();
            for (CtmsUom node : store.values())
            {
                if (query != null && !ProductMasterRules.isBlank(query.getEnableFlag())
                        && !query.getEnableFlag().equals(node.getEnableFlag()))
                {
                    continue;
                }
                result.add(copy(node));
            }
            return result;
        }

        @Override
        public CtmsUom selectUomById(String id)
        {
            CtmsUom node = store.get(id);
            return node == null ? null : copy(node);
        }

        @Override
        public CtmsUom selectUomByCode(String code)
        {
            for (CtmsUom node : store.values())
            {
                if (same(node.getCode(), code))
                {
                    return copy(node);
                }
            }
            return null;
        }

        @Override
        public int countProductsByUom(String uomId)
        {
            return productMapper.countByUom(uomId);
        }

        @Override
        public int insertUom(CtmsUom uom)
        {
            store.put(uom.getId(), copy(uom));
            return 1;
        }

        @Override
        public int updateUom(CtmsUom uom)
        {
            if (!store.containsKey(uom.getId()))
            {
                return 0;
            }
            store.put(uom.getId(), copy(uom));
            return 1;
        }

        @Override
        public int deleteUomById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        private static CtmsUom copy(CtmsUom src)
        {
            CtmsUom u = new CtmsUom();
            u.setId(src.getId());
            u.setCode(src.getCode());
            u.setName(src.getName());
            u.setDecimals(src.getDecimals());
            u.setEnableFlag(src.getEnableFlag());
            u.setRemark(src.getRemark());
            u.setCreateId(src.getCreateId());
            u.setCreateBy(src.getCreateBy());
            u.setCreateTime(src.getCreateTime());
            u.setUpdateId(src.getUpdateId());
            u.setUpdateBy(src.getUpdateBy());
            u.setUpdateTime(src.getUpdateTime());
            return u;
        }
    }

    /** 仓库桩。 */
    private static class StubWarehouseMapper implements CtmsWarehouseMapper
    {
        private final Map<String, CtmsWarehouse> store = new LinkedHashMap<String, CtmsWarehouse>();

        @Override
        public List<CtmsWarehouse> selectWarehouseList(CtmsWarehouse query)
        {
            List<CtmsWarehouse> result = new ArrayList<CtmsWarehouse>();
            for (CtmsWarehouse node : store.values())
            {
                if (query != null && !ProductMasterRules.isBlank(query.getEnableFlag())
                        && !query.getEnableFlag().equals(node.getEnableFlag()))
                {
                    continue;
                }
                result.add(copy(node));
            }
            return result;
        }

        @Override
        public CtmsWarehouse selectWarehouseById(String id)
        {
            CtmsWarehouse node = store.get(id);
            return node == null ? null : copy(node);
        }

        @Override
        public CtmsWarehouse selectWarehouseByCode(String code)
        {
            for (CtmsWarehouse node : store.values())
            {
                if (same(node.getCode(), code))
                {
                    return copy(node);
                }
            }
            return null;
        }

        @Override
        public int insertWarehouse(CtmsWarehouse warehouse)
        {
            store.put(warehouse.getId(), copy(warehouse));
            return 1;
        }

        @Override
        public int updateWarehouse(CtmsWarehouse warehouse)
        {
            if (!store.containsKey(warehouse.getId()))
            {
                return 0;
            }
            store.put(warehouse.getId(), copy(warehouse));
            return 1;
        }

        @Override
        public int deleteWarehouseById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        private static CtmsWarehouse copy(CtmsWarehouse src)
        {
            CtmsWarehouse w = new CtmsWarehouse();
            w.setId(src.getId());
            w.setCode(src.getCode());
            w.setName(src.getName());
            w.setAddress(src.getAddress());
            w.setKeeperUserId(src.getKeeperUserId());
            w.setEnableFlag(src.getEnableFlag());
            w.setRemark(src.getRemark());
            w.setCreateId(src.getCreateId());
            w.setCreateBy(src.getCreateBy());
            w.setCreateTime(src.getCreateTime());
            w.setUpdateId(src.getUpdateId());
            w.setUpdateBy(src.getUpdateBy());
            w.setUpdateTime(src.getUpdateTime());
            return w;
        }
    }

    /** 物料桩：同时给类型/单位的 count* 提供数据。 */
    private static class StubProductMapper implements CtmsProductMapper
    {
        private final Map<String, CtmsProduct> store = new LinkedHashMap<String, CtmsProduct>();

        int countByType(String productTypeId)
        {
            int count = 0;
            for (CtmsProduct node : store.values())
            {
                if (same(node.getProductTypeId(), productTypeId))
                {
                    count++;
                }
            }
            return count;
        }

        int countByUom(String uomId)
        {
            int count = 0;
            for (CtmsProduct node : store.values())
            {
                if (same(node.getUomId(), uomId))
                {
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<CtmsProduct> selectProductList(CtmsProduct query)
        {
            List<CtmsProduct> result = new ArrayList<CtmsProduct>();
            for (CtmsProduct node : store.values())
            {
                if (query != null)
                {
                    if (!ProductMasterRules.isBlank(query.getName()) && !node.getName().contains(query.getName()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getCode()) && !node.getCode().contains(query.getCode()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getProductTypeId())
                            && !query.getProductTypeId().equals(node.getProductTypeId()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getUomId()) && !query.getUomId().equals(node.getUomId()))
                    {
                        continue;
                    }
                    if (!ProductMasterRules.isBlank(query.getEnableFlag())
                            && !query.getEnableFlag().equals(node.getEnableFlag()))
                    {
                        continue;
                    }
                }
                result.add(copy(node));
            }
            return result;
        }

        @Override
        public CtmsProduct selectProductById(String id)
        {
            CtmsProduct node = store.get(id);
            return node == null ? null : copy(node);
        }

        @Override
        public CtmsProduct selectProductByCode(String code)
        {
            for (CtmsProduct node : store.values())
            {
                if (same(node.getCode(), code))
                {
                    return copy(node);
                }
            }
            return null;
        }

        @Override
        public int insertProduct(CtmsProduct product)
        {
            store.put(product.getId(), copy(product));
            return 1;
        }

        @Override
        public int updateProduct(CtmsProduct product)
        {
            if (!store.containsKey(product.getId()))
            {
                return 0;
            }
            store.put(product.getId(), copy(product));
            return 1;
        }

        @Override
        public int deleteProductById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        private static CtmsProduct copy(CtmsProduct src)
        {
            CtmsProduct p = new CtmsProduct();
            p.setId(src.getId());
            p.setCode(src.getCode());
            p.setName(src.getName());
            p.setSpec(src.getSpec());
            p.setProductTypeId(src.getProductTypeId());
            p.setUomId(src.getUomId());
            p.setBrand(src.getBrand());
            p.setBarcode(src.getBarcode());
            p.setDefaultPrice(src.getDefaultPrice());
            p.setSafetyStock(src.getSafetyStock());
            p.setEnableFlag(src.getEnableFlag());
            p.setRemark(src.getRemark());
            p.setCreateId(src.getCreateId());
            p.setCreateBy(src.getCreateBy());
            p.setCreateTime(src.getCreateTime());
            p.setUpdateId(src.getUpdateId());
            p.setUpdateBy(src.getUpdateBy());
            p.setUpdateTime(src.getUpdateTime());
            return p;
        }
    }

    /** 空值安全的字符串相等（桩里模拟 SQL 的 = 语义）。 */
    private static boolean same(String left, String right)
    {
        return left == null ? right == null : left.equals(right);
    }
}
