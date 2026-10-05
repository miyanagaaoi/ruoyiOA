package com.ruoyi.ctms.erp.sales;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.MapKey;
import org.junit.Test;

import com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem;
import com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesOrderMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestItemMapper;
import com.ruoyi.ctms.erp.sales.mapper.ErpSalesRequestMapper;
import com.ruoyi.ctms.erp.sales.service.impl.ErpSalesRequestServiceImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>销售线「按单据ID批量取行项」的契约回归（D-1 blocker 的防守线）</b>。 </p>
 *
 * <p> <b>缺陷原文（生产环境的真实栈）</b>：{@code GET /sal/request/list} 返回业务 {@code code=500}： </p>
 * <pre>
 * java.lang.ClassCastException: class com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem
 *     cannot be cast to class java.util.List
 *   at ErpSalesRequestServiceImpl.applyDerivedColumns(ErpSalesRequestServiceImpl.java:170)
 *   at ErpSalesRequestServiceImpl.selectSalesRequestList(ErpSalesRequestServiceImpl.java:125)
 * </pre>
 *
 * <p> <b>根因（不是"MyBatis 把行当成 List"，而是签名与 MyBatis 契约不符）</b>：
 * MyBatis 的 {@code @MapKey} 契约是「<b>一行 → 一个值</b>」——{@code MapResultHandler.handleResult}
 * 就是 {@code map.put(mapKey, 当前行对象)}（泛型擦除后是 {@code put(Object,Object)}，插入时不抛）。
 * 于是 {@code @MapKey("docId") Map<String, List<ErpSalesRequestItem>>} 里"值"被声明成 {@code List}，
 * 但<b>运行时塞进去的是 {@code ErpSalesRequestItem}</b>；真正的爆炸点是<b>调用方那一次泛型读</b>
 * {@code grouped.get(docId)} —— 编译器在这里插入 {@code checkcast java.util.List} ⇒ ClassCastException。
 * 也就是说：这个签名<b>永远不可能被填满</b>，只要列表页有行项就必炸。 </p>
 *
 * <p> 本类用三条断言把"不会再犯"钉住： </p>
 * <ol>
 *   <li> {@link #salesBatchItemMappersMustNotUseMapKeyWithCollectionValue()} ——
 *       反射断言两个批量查询<b>返回平铺 List</b>、<b>不带 {@code @MapKey}</b>，
 *        并对 sales 全部 mapper 施加通用规则："{@code @MapKey} 的 Map 值类型不得是集合/数组/Map"。
 *        <b>把签名改回 {@code @MapKey Map<String,List<…>>} 本用例立刻变红</b>（D-15 反向对照）； </li>
 *   <li> {@link #mapKeyShapeReproducesTheReportedClassCast()} ——
 *        用"一行一个值"的写入口 + 生产同款的泛型读，<b>原样复现</b>那条 ClassCastException
 *        （消息逐字含 {@code cannot be cast to class java.util.List}），证明上面的机制判断成立； </li>
 *   <li> {@link #serviceGroupingKeepsDocOrderSeqOrderAndEmptySemantics()} ——
 *        服务层 Java 分组的<b>行为</b>：键顺序 = 查询顺序、单据内按 seq 升序、空结果 → 空 Map（不是 null）。 </li>
 * </ol>
 *
 * @author 二开
 */
public class ErpSalesItemBatchQueryContractTest
{
    private static final String DOC_A = "DOC-A";

    private static final String DOC_B = "DOC-B";

    /** sales 包下的全部 mapper 接口（通用 {@code @MapKey} 规则的扫描范围）。 */
    private static final Class<?>[] SALES_MAPPERS = {
            ErpSalesRequestItemMapper.class, ErpSalesRequestMapper.class,
            ErpSalesOrderItemMapper.class, ErpSalesOrderMapper.class };

    /* ==================== ① 签名契约（改回 @MapKey + List 值即红） ==================== */

    @Test
    public void salesBatchItemMappersMustNotUseMapKeyWithCollectionValue() throws Exception
    {
        assertFlatBatchSignature(ErpSalesRequestItemMapper.class, "selectItemsByDocIds",
                ErpSalesRequestItem.class);
        assertFlatBatchSignature(ErpSalesOrderItemMapper.class, "selectItemsByDocIds",
                ErpSalesOrderItem.class);

        // 通用规则：@MapKey 只支持「一行 → 一个值」，值的类型不得是集合/数组/Map。
        // 这条规则把 D-1 这一类缺陷在**整个 sales 包**内一次性堵死（不只是那两个方法）。
        List<String> violations = new ArrayList<>();
        for (Class<?> mapper : SALES_MAPPERS)
        {
            for (Method method : mapper.getDeclaredMethods())
            {
                MapKey mapKey = method.getAnnotation(MapKey.class);
                if (mapKey == null)
                {
                    continue;
                }
                Class<?> valueType = mapValueRawType(method);
                if (valueType == null)
                {
                    continue;
                }
                if (java.util.Collection.class.isAssignableFrom(valueType)
                        || Map.class.isAssignableFrom(valueType)
                        || valueType.isArray())
                {
                    violations.add(mapper.getSimpleName() + "#" + method.getName()
                            + " 的 @MapKey(\"" + mapKey.value() + "\") 值是 " + valueType.getSimpleName());
                }
            }
        }
        assertTrue("MyBatis 的 @MapKey 只支持「一行 → 一个值」：以下方法把值声明成了集合/数组/Map，"
                + "运行时插入的是单行对象 ⇒ 调用方泛型读必抛 ClassCastException：" + violations,
                violations.isEmpty());
    }

    /**
     * 断言某个批量查询方法是"平铺 List"形态（返回 {@code List<行项>}、无 {@code @MapKey}）。
     *
     * @param mapper        mapper 接口
     * @param methodName    方法名
     * @param itemType      期望的元素类型
     * @throws Exception 反射失败
     */
    private static void assertFlatBatchSignature(Class<?> mapper, String methodName, Class<?> itemType)
            throws Exception
    {
        Method method = mapper.getMethod(methodName, List.class);
        assertEquals(mapper.getSimpleName() + "#" + methodName + " 必须返回平铺的 List（D-1 修复）",
                List.class, method.getReturnType());
        assertNull(mapper.getSimpleName() + "#" + methodName
                + " 不得再标注 @MapKey（@MapKey 是一行一值，配不上 List 值）",
                method.getAnnotation(MapKey.class));
        Type generic = method.getGenericReturnType();
        assertTrue("返回类型必须是带泛型参数的 List", generic instanceof ParameterizedType);
        Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
        assertEquals("List 的元素类型必须是行项（而不是 List<...> 或 Map<...>）",
                itemType, args[0]);
    }

    /**
     * 取方法返回的 Map 值类型的 raw class（非 Map 或无泛型参数返回 null）。
     *
     * @param method mapper 方法
     * @return 值类型 raw class
     */
    private static Class<?> mapValueRawType(Method method)
    {
        Type generic = method.getGenericReturnType();
        if (!(generic instanceof ParameterizedType))
        {
            return null;
        }
        Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
        if (args.length < 2)
        {
            return null;
        }
        Type valueType = args[1];
        if (valueType instanceof ParameterizedType)
        {
            Type raw = ((ParameterizedType) valueType).getRawType();
            return raw instanceof Class ? (Class<?>) raw : null;
        }
        return valueType instanceof Class ? (Class<?>) valueType : null;
    }

    /* ==================== ② 原样复现那条 ClassCastException ==================== */

    /**
     * <p> <b>复现生产那条异常</b>：按 MyBatis {@code @MapKey} 的真实写入方式（一行一个值、
     * 经擦除后的 {@code Map.put} 插入）填一个"值类型声明为 List"的 Map，
     * 再用生产代码同款的泛型读取出值 —— JVM 在 {@code get} 处插入的
     * {@code checkcast java.util.List} 抛出与生产<b>逐字相同</b>的异常。 </p>
     *
     * <p> 这条用例的意义：它把"为什么这个签名必炸"从<b>论断</b>变成<b>可执行的证据</b>
     * （只靠读代码容易误判成"MyBatis 自己会 cast"）。 </p>
     */
    @Test
    public void mapKeyShapeReproducesTheReportedClassCast()
    {
        ErpSalesRequestItem row = new ErpSalesRequestItem();
        row.setId("IT-1");
        row.setDocId(DOC_A);

        // 旧签名的"形状"：Map<String, List<ErpSalesRequestItem>>
        Map<String, List<ErpSalesRequestItem>> oldShape = new LinkedHashMap<>();
        // MyBatis 的写入路径：泛型擦除后的 put(Object, Object)，插进去的是**行对象**
        putErased(oldShape, DOC_A, row);
        assertEquals("Map 里确实存着行对象本身（不是 List）", row, oldShape.get(DOC_A));

        try
        {
            // 生产代码同款的读取：List<…> items = grouped.get(docId)
            List<ErpSalesRequestItem> items = readGrouped(oldShape, DOC_A);
            fail("按 @MapKey 一行一值的写入 + 泛型读必须抛 ClassCastException，实际拿到 " + items);
        }
        catch (ClassCastException e)
        {
            assertTrue("异常消息必须与生产一致：实际=" + e.getMessage(),
                    e.getMessage().contains("cannot be cast to class java.util.List"));
            assertTrue("消息里应点名行项类：实际=" + e.getMessage(),
                    e.getMessage().contains(ErpSalesRequestItem.class.getName()));
        }
    }

    /** MyBatis {@code MapResultHandler} 的写入语义：擦除后 put(key, 行对象)。 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void putErased(Map<String, List<ErpSalesRequestItem>> target, String key, Object row)
    {
        ((Map) target).put(key, row);
    }

    /** 生产代码那一次泛型读（编译器在此插入 checkcast java.util.List）。 */
    private static List<ErpSalesRequestItem> readGrouped(Map<String, List<ErpSalesRequestItem>> grouped,
            String docId)
    {
        return grouped.get(docId);
    }

    /* ==================== ③ 服务层分组的行为 ==================== */

    @Test
    public void serviceGroupingKeepsDocOrderSeqOrderAndEmptySemantics()
    {
        ErpSalesRequestServiceImpl service = new ErpSalesRequestServiceImpl();
        service.setRequestItemMapper(new FlatRequestItemMapper());

        // 平铺顺序 = SQL 的 order by doc_id asc, seq asc（DOC-A 两行，再 DOC-B 一行）
        List<ErpSalesRequestItem> flat = Arrays.asList(
                item("IT-A1", DOC_A, 1), item("IT-A2", DOC_A, 2), item("IT-B1", DOC_B, 1));
        service.setRequestItemMapper(new FlatRequestItemMapper(flat));

        Map<String, List<ErpSalesRequestItem>> grouped =
                service.selectSalesRequestItemsByDocIds(Arrays.asList(DOC_A, DOC_B));

        assertNotNull("不得返回 null", grouped);
        assertEquals("两个单据两个键", 2, grouped.size());
        assertEquals("键顺序 = 查询顺序（LinkedHashMap）",
                Arrays.asList(DOC_A, DOC_B), new ArrayList<>(grouped.keySet()));
        assertEquals("DOC-A 两行", 2, grouped.get(DOC_A).size());
        assertEquals("单据内保持 seq 升序", Arrays.asList("IT-A1", "IT-A2"),
                Arrays.asList(grouped.get(DOC_A).get(0).getId(), grouped.get(DOC_A).get(1).getId()));
        assertEquals("DOC-B 一行", 1, grouped.get(DOC_B).size());

        // 空集合语义：查不到就"没有键"，而不是 null 或空 List
        service.setRequestItemMapper(new FlatRequestItemMapper(new ArrayList<>()));
        Map<String, List<ErpSalesRequestItem>> empty =
                service.selectSalesRequestItemsByDocIds(Collections.singletonList("DOC-NONE"));
        assertNotNull("空结果必须是空 Map 而不是 null", empty);
        assertTrue("空结果不得出现键", empty.isEmpty());
        assertNull("没有行项的单据取不到键（调用方按 null 兜底）", empty.get("DOC-NONE"));

        // 入口守卫：docIds 为空/为 null 直接返回空 Map（不查库）
        assertTrue(service.selectSalesRequestItemsByDocIds(new ArrayList<>()).isEmpty());
        assertTrue(service.selectSalesRequestItemsByDocIds(null).isEmpty());
    }

    @Test
    public void groupingSkipsRowsWithoutDocIdInsteadOfBlowingUp()
    {
        ErpSalesRequestServiceImpl service = new ErpSalesRequestServiceImpl();
        ErpSalesRequestItem orphan = item("IT-X", null, 1);
        service.setRequestItemMapper(new FlatRequestItemMapper(
                Arrays.asList(item("IT-A1", DOC_A, 1), orphan)));

        Map<String, List<ErpSalesRequestItem>> grouped =
                service.selectSalesRequestItemsByDocIds(Collections.singletonList(DOC_A));

        assertEquals("只有有 docId 的行参与分组", 1, grouped.size());
        assertFalse("孤儿行不得进入任何键", grouped.containsKey(null));
        assertEquals(1, grouped.get(DOC_A).size());
    }

    private static ErpSalesRequestItem item(String id, String docId, int seq)
    {
        ErpSalesRequestItem item = new ErpSalesRequestItem();
        item.setId(id);
        item.setDocId(docId);
        item.setSeq(Integer.valueOf(seq));
        return item;
    }

    /* ==================== 桩：返回"平铺 List"的批量查询 ==================== */

    /** 只实现批量查询与按单据查询的行项 Mapper 桩（其余方法不支持，被调用即抛）。 */
    static class FlatRequestItemMapper implements ErpSalesRequestItemMapper
    {
        private final List<ErpSalesRequestItem> flat;

        FlatRequestItemMapper()
        {
            this(new ArrayList<ErpSalesRequestItem>());
        }

        FlatRequestItemMapper(List<ErpSalesRequestItem> flat)
        {
            this.flat = flat;
        }

        @Override
        public List<ErpSalesRequestItem> selectItemsByDocIds(List<String> docIds)
        {
            List<ErpSalesRequestItem> hit = new ArrayList<>();
            for (ErpSalesRequestItem item : flat)
            {
                if (item.getDocId() != null && docIds.contains(item.getDocId()))
                {
                    hit.add(item);
                }
            }
            return hit;
        }

        @Override
        public List<ErpSalesRequestItem> selectItemsByDocId(String docId)
        {
            List<ErpSalesRequestItem> hit = new ArrayList<>();
            for (ErpSalesRequestItem item : flat)
            {
                if (docId != null && docId.equals(item.getDocId()))
                {
                    hit.add(item);
                }
            }
            return hit;
        }

        @Override
        public List<ErpSalesRequestItem> selectItemsBySrcItemIds(List<String> srcItemIds)
        {
            throw new UnsupportedOperationException("本用例不用");
        }

        @Override
        public int deleteItemsByDocId(String docId)
        {
            throw new UnsupportedOperationException("本用例不用");
        }

        @Override
        public int batchInsertItems(List<ErpSalesRequestItem> items)
        {
            throw new UnsupportedOperationException("本用例不用");
        }

        @Override
        public int batchUpsertItems(List<ErpSalesRequestItem> items)
        {
            throw new UnsupportedOperationException("本用例不用");
        }

        @Override
        public int updateOrderedQty(String itemId, java.math.BigDecimal delta)
        {
            throw new UnsupportedOperationException("本用例不用");
        }

        @Override
        public ErpSalesRequestItem selectItemById(String id)
        {
            throw new UnsupportedOperationException("本用例不用");
        }
    }
}
