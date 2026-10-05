package com.ruoyi.ctms.erp.base;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>"金额/数量路径不含浮点类型"的源码级与反射级断言</b>（tasks.md §1.4 的验证方式、
 * REQ-NFR-007、AC-78）。 </p>
 *
 * <p> <b>为什么用源码扫描而不是只靠人工 grep</b>：{@code grep} 是一次性动作，
 * 下一轮改代码没人保证再跑；把它做成单测，任何人在 B4 的包里新增一个
 * 浮点字段/变量都会当场把构建打红。 </p>
 *
 * <p> <b>扫描范围刻意限定在 {@code com/ruoyi/ctms/erp}</b>（B4 自己的代码）：
 * B3 留下的 {@code package-info.java} / {@code CtmsProduct.java} 在 javadoc 里
 * 用这两个词做"禁止说明"，那是历史文本而非类型使用（本轮实测 3 处，
 * 详见 {@code notes/01-base.md} §4.4）；B4 的包内要求<b>连注释里都不出现</b>，
 * 比约定更严。 </p>
 *
 * @author 二开
 */
public class ErpPrecisionTest
{
    /** B4 的业务包（相对 {@code ruoyi-ctms} 模块目录）。 */
    private static final String ERP_SRC_DIR = "src/main/java/com/ruoyi/ctms/erp";

    /**
     * 解析 B4 源码目录（兼容"从模块目录跑"与"从仓库根跑"两种 surefire 工作目录）。
     *
     * @return 源码目录
     */
    private static File erpSourceRoot()
    {
        File direct = new File(ERP_SRC_DIR);
        if (direct.isDirectory())
        {
            return direct;
        }
        File fromRepoRoot = new File("ruoyi-ctms/" + ERP_SRC_DIR);
        return fromRepoRoot.isDirectory() ? fromRepoRoot : direct;
    }

    /** 被禁的类型词（词边界匹配，避免误伤 {@code floatingPoint} 之类）。 */
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(double|float)\\b");

    @Test
    public void B4的源码里不得出现浮点类型()
    {
        File root = erpSourceRoot();
        assertTrue("找不到 B4 源码目录（user.dir=" + new File(".").getAbsolutePath() + "）", root.isDirectory());
        List<File> javaFiles = new ArrayList<>();
        collect(root, javaFiles);
        assertTrue("B4 源码文件数应大于 10", javaFiles.size() > 10);

        List<String> hits = new ArrayList<>();
        for (File file : javaFiles)
        {
            for (String line : readLines(file))
            {
                Matcher matcher = FORBIDDEN.matcher(line);
                if (matcher.find())
                {
                    hits.add(file.getPath() + ": " + line.trim());
                }
            }
        }
        assertEquals("B4 的 erp 包内不得出现浮点类型（含注释）：" + hits, 0, hits.size());
    }

    @Test
    public void B4的类里不得有浮点字段或方法签名()
    {
        File root = erpSourceRoot();
        List<File> javaFiles = new ArrayList<>();
        collect(root, javaFiles);

        List<String> hits = new ArrayList<>();
        int inspected = 0;
        for (File file : javaFiles)
        {
            Class<?> clazz = load(file, root);
            if (clazz == null)
            {
                continue;
            }
            inspected++;
            for (Field field : clazz.getDeclaredFields())
            {
                if (isFloating(field.getType()))
                {
                    hits.add(clazz.getSimpleName() + "." + field.getName() + ": " + field.getType());
                }
            }
            for (Method method : clazz.getDeclaredMethods())
            {
                if (isFloating(method.getReturnType()))
                {
                    hits.add(clazz.getSimpleName() + "." + method.getName() + "(): " + method.getReturnType());
                }
                for (Class<?> parameter : method.getParameterTypes())
                {
                    if (isFloating(parameter))
                    {
                        hits.add(clazz.getSimpleName() + "." + method.getName() + "(...) 参数: " + parameter);
                    }
                }
            }
        }
        assertTrue("反射检查的类数应大于 5（实际 " + inspected + "）", inspected > 5);
        assertEquals("不得存在浮点字段/参数/返回值：" + hits, 0, hits.size());
    }

    @Test
    public void 金额与数量字段一律是BigDecimal()
    {
        com.ruoyi.ctms.erp.base.domain.ErpDocItem item = new com.ruoyi.ctms.erp.base.domain.ErpDocItem()
        {
            private static final long serialVersionUID = 1L;
        };
        assertEquals("行项数量", java.math.BigDecimal.class, returnType(item, "getQty"));
        assertEquals("行项单价", java.math.BigDecimal.class, returnType(item, "getUnitPrice"));
        assertEquals("行项金额", java.math.BigDecimal.class, returnType(item, "getAmount"));
        assertTrue("行项金额必须能现算", item.amountOrCompute() != null);
    }

    private static Class<?> returnType(Object target, String getter)
    {
        try
        {
            return target.getClass().getMethod(getter).getReturnType();
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("缺少方法：" + getter, e);
        }
    }

    private static boolean isFloating(Class<?> type)
    {
        return type == Double.TYPE || type == Float.TYPE
                || type == Double.class || type == Float.class;
    }

    /**
     * 把源码文件映射成类并加载（编译期已存在，直接 Class.forName 即可）。
     *
     * @param file 源码文件
     * @param root 源码根目录
     * @return 类；无法映射（如 {@code package-info.java}）返回 null
     */
    private static Class<?> load(File file, File root)
    {
        String relative = root.toURI().relativize(file.toURI()).getPath();
        if (!relative.endsWith(".java") || relative.endsWith("package-info.java"))
        {
            return null;
        }
        String className = "com.ruoyi.ctms.erp."
                + relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException("源码文件与类名不匹配：" + className, e);
        }
    }

    private static void collect(File dir, List<File> target)
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
                collect(child, target);
            }
            else if (child.getName().endsWith(".java"))
            {
                target.add(child);
            }
        }
    }

    private static List<String> readLines(File file)
    {
        try
        {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("读取源码失败：" + file.getPath(), e);
        }
    }

    @Test
    public void 扫描器本身有判别力()
    {
        // 反向自证：确认正则能抓到"真实的类型使用"，而不是永远返回 0 命中
        assertTrue("正则必须能匹配 double", FORBIDDEN.matcher("private double x;").find());
        assertTrue("正则必须能匹配 float", FORBIDDEN.matcher("float y = 1f;").find());
        assertFalse("必须不误伤标识符", FORBIDDEN.matcher("doubleCheck").find());
        assertFalse("必须不误伤 BigDecimal", FORBIDDEN.matcher("BigDecimal amount").find());
    }
}
