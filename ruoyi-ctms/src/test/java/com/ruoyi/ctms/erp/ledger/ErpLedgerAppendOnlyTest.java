package com.ruoyi.ctms.erp.ledger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.ruoyi.ctms.erp.ledger.mapper.ErpStockBalanceMapper;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockLedgerQueryMapper;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockLedgerMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <p> <b>"流水只增不改"与"额度口径单一真源"的源码级 / 反射级断言</b>
 * （B4 任务 7.3 的验证方式、{@code REQ-STK-003}、AC-73）。 </p>
 *
 * <p> 规格场景是"尝试修改或删除任一已写入的库存流水 → 系统中不存在这样的入口"。
 * 这句话要能<b>持续成立</b>，就不能靠一次性 grep，必须做成门禁： </p>
 * <ul>
 *   <li> 接口层：流水的两个 Mapper（T3 的写入侧 + 本组的只读侧）都不得出现
 *        {@code update*} / {@code delete*} 方法； </li>
 *   <li> SQL 层：两个 Mapper XML 都不得出现 {@code <update>} / {@code <delete>} 标签； </li>
 *   <li> 结存层：本组的明细 Mapper 也不得有 update/delete（否则就是第二个结存写入口，
 *        design D2 只认过账服务 + 运维修复复用 T3 的 {@code updateStockQty}）。 </li>
 * </ul>
 *
 * <p> 另外把"额度口径的关键字"做成两边一致性断言：Java 常量与 SQL 里的
 * {@code like '%入库%' and not like '%调拨入库%'} 必须同时对，改一处忘另一处会被打红。 </p>
 *
 * @author 二开
 */
public class ErpLedgerAppendOnlyTest
{
    /** Mapper XML 的相对目录（兼容从模块目录或仓库根跑）。 */
    private static final String[] XML_CANDIDATES = {
            "src/main/resources/mapper/erp",
            "ruoyi-ctms/src/main/resources/mapper/erp"
    };

    /**
     * 解析 Mapper XML 目录。
     *
     * @return 目录
     */
    private static File mapperDir()
    {
        for (String candidate : XML_CANDIDATES)
        {
            File dir = new File(candidate);
            if (dir.isDirectory())
            {
                return dir;
            }
        }
        return new File(XML_CANDIDATES[0]);
    }

    /**
     * 读一个 Mapper XML 的文本。
     *
     * @param name 文件名
     * @return 文本
     * @throws IOException 读失败
     */
    private static String readXml(String name) throws IOException
    {
        File file = new File(mapperDir(), name);
        assertTrue("找不到 Mapper XML：" + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 取接口里所有方法名。
     *
     * @param type 接口
     * @return 方法名列表
     */
    private static List<String> methodNames(Class<?> type)
    {
        List<String> names = new ArrayList<>();
        for (Method method : type.getDeclaredMethods())
        {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * 断言接口里没有写方法。
     *
     * @param type  接口
     * @param label 断言里的说明
     */
    private static void assertNoWriteMethods(Class<?> type, String label)
    {
        for (String name : methodNames(type))
        {
            String lower = name.toLowerCase();
            assertFalse(label + "不得出现写方法：" + name,
                    lower.startsWith("update") || lower.startsWith("delete") || lower.startsWith("insert"));
        }
    }

    @Test
    public void 流水相关Mapper没有任何写方法()
    {
        assertNoWriteMethods(ErpStockLedgerQueryMapper.class, "库存流水只读 Mapper");
        // T3 的写入侧只允许 insertLedger（没有 update / delete）
        for (String name : methodNames(ErpStockLedgerMapper.class))
        {
            String lower = name.toLowerCase();
            assertFalse("流水写入侧不得有 update：" + name, lower.startsWith("update"));
            assertFalse("流水写入侧不得有 delete：" + name, lower.startsWith("delete"));
        }
        assertTrue("流水写入侧仍应有 insertLedger",
                methodNames(ErpStockLedgerMapper.class).contains("insertLedger"));
    }

    @Test
    public void 结存明细Mapper没有写方法()
    {
        assertNoWriteMethods(ErpStockBalanceMapper.class, "库存明细只读 Mapper");
    }

    @Test
    public void 两个MapperXML都没有update或delete标签() throws IOException
    {
        String ledgerXml = readXml("ErpStockLedgerQueryMapper.xml");
        String balanceXml = readXml("ErpStockBalanceMapper.xml");
        assertFalse("流水查询 XML 不得有 <update>", ledgerXml.contains("<update"));
        assertFalse("流水查询 XML 不得有 <delete>", ledgerXml.contains("<delete"));
        assertFalse("结存明细 XML 不得有 <update>", balanceXml.contains("<update"));
        assertFalse("结存明细 XML 不得有 <delete>", balanceXml.contains("<delete"));
        assertTrue("流水查询 XML 必须有倒序排序", ledgerXml.contains("order by l.create_time desc"));
        assertTrue("结存明细 XML 必须有额度聚合", balanceXml.contains("goods_quota"));
    }

    @Test
    public void 额度口径的SQL与Java常量一致() throws IOException
    {
        String balanceXml = readXml("ErpStockBalanceMapper.xml");
        assertTrue("SQL 必须按「入库」子串计入（与 ErpLedgerRules.QUOTA_INCLUDE_KEYWORD 一致）",
                balanceXml.contains("like '%" + ErpLedgerRules.QUOTA_INCLUDE_KEYWORD + "%'"));
        assertTrue("SQL 必须排除「调拨入库」（与 ErpLedgerRules.QUOTA_EXCLUDE_KEYWORD 一致）",
                balanceXml.contains("not like '%" + ErpLedgerRules.QUOTA_EXCLUDE_KEYWORD + "%'"));
        assertEquals("入库", ErpLedgerRules.QUOTA_INCLUDE_KEYWORD);
        assertEquals("调拨入库", ErpLedgerRules.QUOTA_EXCLUDE_KEYWORD);
        // 红冲前缀必须让红冲行同样落在"入库"集合里（否则额度不会被抵扣）
        assertTrue("红冲前缀应使得「红冲-采购入库」仍含入库",
                ErpLedgerRules.countedInQuota("红冲-采购入库"));
    }

    @Test
    public void 结存表在数据集里只被过账服务写()
    {
        // 本组的两个只读 Mapper 都不提供结存写入；运维修复走 T3 的唯一写方法
        boolean hasStockUpdate = false;
        for (String name : methodNames(ErpStockBalanceMapper.class))
        {
            if ("updateStockQty".equals(name))
            {
                hasStockUpdate = true;
            }
        }
        assertFalse("明细 Mapper 不得自带 updateStockQty（复用 T3 的 ErpStockMapper）", hasStockUpdate);
    }
}
