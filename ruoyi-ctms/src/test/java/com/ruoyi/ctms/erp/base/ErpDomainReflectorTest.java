package com.ruoyi.ctms.erp.base;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.reflection.Reflector;
import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.junit.Test;

import com.ruoyi.ctms.erp.base.domain.ErpDocHeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>MyBatis 元数据守卫</b>（t31 修复后新增）：对 {@code erp/**} 下所有 domain 类
 * 建 {@link Reflector} 并<b>逐个读取 getter</b>，确保不存在"同属性名、不同类型"的歧义 getter。 </p>
 *
 * <p> <b>为什么必须读一遍，而不是只建 Reflector</b>：{@code ErpDocHeader} 曾同时有
 * {@code getPosted()}（{@code String}，落库列）与 {@code isPosted()}（{@code boolean}，便捷判定）——
 * 它们是 JavaBeans 口径下<b>同一个属性 {@code posted} 的两个 getter</b>，类型互不兼容。
 * MyBatis 建 {@code Reflector} 时<b>不会</b>立刻抛错，而是把该属性包成
 * {@link AmbiguousMethodInvoker}，等到<b>真的读这个属性</b>时才抛：
 *
 * <pre>
 * org.apache.ibatis.reflection.ReflectionException: Illegal overloaded getter method with
 * ambiguous type for property 'posted' in class 'com.ruoyi.ctms.erp.base.domain.ErpDocHeader'.
 * </pre>
 *
 * <p> 真实触发点是 mapper XML 的动态 SQL：{@code ErpPurRequestMapper.xml:116} 的
 * {@code <if test="posted != null and posted != ''">} —— OGNL 读 {@code posted} ⇒
 * {@code BeanWrapper.get} ⇒ 抛异常。因此 8 类单据的<b>所有含该条件的查询/写入</b>整体 500，
 * 而纯单测（stub mapper、不走 XML/OGNL）全绿也发现不了。 </p>
 *
 * <p> 覆盖口径：不写死清单，而是<b>扫描 {@code target/classes} 下 {@code com/ruoyi/ctms/erp} 的全部 .class</b>
 * （含 8 类单据的 header/item、VO、内部类），逐个建 Reflector 并读取全部可读属性；
 * 另用 {@link #SCAN_FLOOR} 兜底"扫描为空也通过"的假绿。 </p>
 *
 * @author 二开
 */
public class ErpDomainReflectorTest
{
    /** 扫描到的类数下限（防止扫描逻辑失效导致"零断言通过"）。 */
    private static final int SCAN_FLOOR = 40;

    /** 必须被扫描到的关键类：base 两个 + 8 类单据的 header/item（16）。 */
    private static final String[] KEY_CLASSES =
    {
        "com.ruoyi.ctms.erp.base.domain.ErpDocHeader",
        "com.ruoyi.ctms.erp.base.domain.ErpDocItem",
        "com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest",
        "com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequestItem",
        "com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder",
        "com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrderItem",
        "com.ruoyi.ctms.erp.sales.domain.ErpSalesRequest",
        "com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem",
        "com.ruoyi.ctms.erp.sales.domain.ErpSalesOrder",
        "com.ruoyi.ctms.erp.sales.domain.ErpSalesOrderItem",
        "com.ruoyi.ctms.erp.posting.domain.ErpStockIn",
        "com.ruoyi.ctms.erp.posting.domain.ErpStockInItem",
        "com.ruoyi.ctms.erp.posting.domain.ErpStockOut",
        "com.ruoyi.ctms.erp.posting.domain.ErpStockOutItem",
        "com.ruoyi.ctms.erp.stockops.domain.ErpStocktake",
        "com.ruoyi.ctms.erp.stockops.domain.ErpStocktakeItem",
        "com.ruoyi.ctms.erp.stockops.domain.ErpTransfer",
        "com.ruoyi.ctms.erp.stockops.domain.ErpTransferItem"
    };

    /**
     * <p> ① 每个类都能建 `Reflector`；② 每个可读属性都能被<b>真正读取</b>
     * （即没有任何属性被包成 {@link AmbiguousMethodInvoker}）。 </p>
     */
    @Test
    public void everyErpDomainPropertyIsReadableWithoutAmbiguity()
    {
        List<Class<?>> classes = scanErpClasses();
        assertTrue("扫描到的 erp 类太少（" + classes.size() + " < " + SCAN_FLOOR
                + "）：扫描逻辑可能失效", classes.size() >= SCAN_FLOOR);

        List<String> failures = new ArrayList<String>();
        int propertiesChecked = 0;
        for (Class<?> clazz : classes)
        {
            Reflector reflector;
            try
            {
                // 这一步就是 MyBatis 建元数据时做的事；歧义的属性在这里被包成 AmbiguousMethodInvoker
                reflector = new Reflector(clazz);
            }
            catch (Throwable t)
            {
                failures.add(clazz.getName() + " 建 Reflector 失败 -> " + typeAndMessage(t));
                continue;
            }
            Object instance = tryInstantiate(clazz);
            for (String property : reflector.getGetablePropertyNames())
            {
                propertiesChecked++;
                Invoker invoker;
                try
                {
                    invoker = reflector.getGetInvoker(property);
                }
                catch (Throwable t)
                {
                    failures.add(clazz.getName() + "." + property + " 取 invoker 失败 -> " + typeAndMessage(t));
                    continue;
                }
                if (invoker instanceof AmbiguousMethodInvoker)
                {
                    failures.add(clazz.getName() + "." + property
                            + " 的 getter 是 AmbiguousMethodInvoker（同属性名两个不同类型 getter）");
                    continue;
                }
                try
                {
                    invoker.invoke(instance, new Object[0]);
                }
                catch (Throwable t)
                {
                    // 只把"歧义"当失败：null 目标上的 NPE / 抽象类实例化失败都不算（属性本身没歧义）
                    if (isAmbiguity(typeAndMessage(t)))
                    {
                        failures.add(clazz.getName() + "." + property + " 读取失败 -> " + typeAndMessage(t));
                    }
                }
            }
            // ③ 与 MyBatis 版本无关的静态口径：同一属性名不得出现两个不同类型的 getter
            for (String ambiguous : staticAmbiguousProperties(clazz))
            {
                failures.add(clazz.getName() + " 静态扫描发现歧义属性：" + ambiguous);
            }
        }
        assertTrue("检查了 " + propertiesChecked + " 个属性，发现以下歧义：\n  · " + join(failures),
                failures.isEmpty());
    }

    @Test
    public void scanCoversEveryDocHeaderAndItem()
    {
        Set<String> names = new LinkedHashSet<String>();
        for (Class<?> clazz : scanErpClasses())
        {
            names.add(clazz.getName());
        }
        List<String> missing = new ArrayList<String>();
        for (String key : KEY_CLASSES)
        {
            if (!names.contains(key))
            {
                missing.add(key);
            }
        }
        assertTrue("扫描未覆盖到关键类：" + join(missing), missing.isEmpty());
    }

    /**
     * <p> 锁住落库列语义：{@code posted} 仍是 {@code String}（'0'/'1'），
     * 便捷布尔判定改用<b>不冲突的属性名</b> {@code postedFlag}。 </p>
     */
    @Test
    public void postedStaysStringAndFlagLivesUnderItsOwnProperty()
    {
        Reflector reflector = new Reflector(ErpDocHeader.class);

        assertTrue("posted 属性必须可读（落库列）", reflector.hasGetter("posted"));
        assertTrue("posted 属性必须可写（落库列）", reflector.hasSetter("posted"));
        assertTrue("postedFlag 属性应存在（boolean 便捷判定）", reflector.hasGetter("postedFlag"));
        assertFalse("不得再存在 isPosted() 这类与 posted 冲突的 getter",
                hasMethod(ErpDocHeader.class, "isPosted"));
        assertFalse("不得再存在 isEditable() 与 editable 属性冲突以外的同名歧义",
                staticAmbiguousProperties(ErpDocHeader.class).contains("posted"));
    }

    /**
     * 兜底行为：{@code POSTED_NO} 的非空填充与 {@code POSTED_YES} 判定不变。
     *
     * <p> 便捷判定用<b>反射</b>取（而不是直接写 {@code header.isPostedFlag()}）：
     * 这样本测试类在<b>修复前也编译得通</b>，从而能真复现"修复前红 / 修复后绿"。 </p>
     */
    @Test
    public void postedFallbackAndFlagSemanticsUnchanged() throws Exception
    {
        java.lang.reflect.Method flag;
        try
        {
            flag = ErpDocHeader.class.getMethod("isPostedFlag");
        }
        catch (NoSuchMethodException e)
        {
            flag = null;
        }
        assertTrue("便捷布尔判定应存在且不与 posted 属性冲突（isPostedFlag）", flag != null);
        assertTrue("便捷判定必须返回 boolean", flag.getReturnType() == boolean.class);

        ErpDocHeader header = new ErpDocHeader()
        {
        };
        assertFalse("未过账时 posted 字段为空，便捷判定为 false", (Boolean) flag.invoke(header));

        header.setPosted(null);
        header.applyCreateSnapshot("U-1", "D-1", "tester");
        assertEquals("posted 为空时兜底 POSTED_NO", ErpDocHeader.POSTED_NO, header.getPosted());
        assertFalse((Boolean) flag.invoke(header));

        header.setPosted(ErpDocHeader.POSTED_YES);
        assertTrue((Boolean) flag.invoke(header));
        assertEquals(ErpDocHeader.POSTED_YES, header.getPosted());
    }

    /* ==================== 扫描与小工具 ==================== */

    private static List<Class<?>> scanErpClasses()
    {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        File root = erpClassRoot();
        if (root == null || !root.isDirectory())
        {
            return classes;
        }
        List<File> files = new ArrayList<File>();
        collect(root, files);
        Collections.sort(files);
        for (File file : files)
        {
            String relative = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            String className = "com.ruoyi.ctms.erp."
                    + relative.replace(File.separatorChar, '.').replace('/', '.');
            className = className.substring(0, className.length() - ".class".length());
            if (className.endsWith("package-info"))
            {
                continue;
            }
            try
            {
                Class<?> clazz = Class.forName(className, false, ErpDomainReflectorTest.class.getClassLoader());
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotation() || clazz.isAnonymousClass())
                {
                    continue;
                }
                classes.add(clazz);
            }
            catch (Throwable ignored)
            {
                // 加载失败的类跳过（对 domain 类不会发生）
            }
        }
        return classes;
    }

    /** {@code target/classes/com/ruoyi/ctms/erp} 目录（由 base 包的位置反推）。 */
    private static File erpClassRoot()
    {
        try
        {
            URL url = ErpDocHeader.class.getProtectionDomain().getCodeSource().getLocation();
            File base = new File(url.toURI());
            return new File(new File(new File(base, "com"), "ruoyi"), "ctms" + File.separator + "erp");
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void collect(File dir, List<File> out)
    {
        File[] children = dir.listFiles();
        if (children == null)
        {
            return;
        }
        for (File child : children)
        {
            if (child.isDirectory())
            {
                collect(child, out);
            }
            else if (child.getName().endsWith(".class"))
            {
                out.add(child);
            }
        }
    }

    /** 尽力实例化（无参构造）；抽象类/无无参构造返回 null。 */
    private static Object tryInstantiate(Class<?> clazz)
    {
        try
        {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * 与 MyBatis 版本无关的静态口径：同一属性名下若出现"两个不同类型"的 getter（{@code getX()} 与
     * {@code isX()}），JavaBeans 口径即为歧义。
     *
     * @param clazz 目标类
     * @return 歧义属性名列表
     */
    private static List<String> staticAmbiguousProperties(Class<?> clazz)
    {
        java.util.Map<String, java.util.Set<Class<?>>> types = new java.util.LinkedHashMap<String, java.util.Set<Class<?>>>();
        for (java.lang.reflect.Method method : clazz.getMethods())
        {
            if (method.getParameterTypes().length != 0 || method.isSynthetic() || method.isBridge())
            {
                continue;
            }
            String name = method.getName();
            String property = null;
            if (name.startsWith("get") && name.length() > 3)
            {
                property = decapitalize(name.substring(3));
            }
            else if (name.startsWith("is") && name.length() > 2 && (method.getReturnType() == boolean.class
                    || method.getReturnType() == Boolean.class))
            {
                property = decapitalize(name.substring(2));
            }
            if (property == null)
            {
                continue;
            }
            java.util.Set<Class<?>> set = types.get(property);
            if (set == null)
            {
                set = new LinkedHashSet<Class<?>>();
                types.put(property, set);
            }
            set.add(method.getReturnType());
        }
        List<String> ambiguous = new ArrayList<String>();
        for (java.util.Map.Entry<String, java.util.Set<Class<?>>> entry : types.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                ambiguous.add(entry.getKey() + " -> " + entry.getValue());
            }
        }
        return ambiguous;
    }

    private static String decapitalize(String name)
    {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)))
        {
            return name;
        }
        return name.substring(0, 1).toLowerCase(java.util.Locale.ENGLISH) + name.substring(1);
    }

    private static boolean hasMethod(Class<?> clazz, String name)
    {
        try
        {
            clazz.getMethod(name);
            return true;
        }
        catch (NoSuchMethodException e)
        {
            return false;
        }
    }

    private static boolean isAmbiguity(String message)
    {
        return message != null && (message.contains("ambiguous") || message.contains("Ambiguous"));
    }

    private static String typeAndMessage(Throwable t)
    {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String join(List<String> lines)
    {
        StringBuilder sb = new StringBuilder();
        for (String line : lines)
        {
            sb.append("\n  · ").append(line);
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }
}
