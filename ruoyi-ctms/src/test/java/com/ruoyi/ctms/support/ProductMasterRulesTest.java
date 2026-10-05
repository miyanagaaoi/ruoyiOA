package com.ruoyi.ctms.support;

import java.math.BigDecimal;

import org.junit.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link ProductMasterRules} 的<b>表驱动单测</b>（2.0 B3 §3.3）。 </p>
 *
 * <p> 被测对象是纯函数：层级推导、小数位范围、负值拒绝、物化路径拼接与层级反推。
 * 错误文案是前端与验收脚本共用的契约，因此这里<b>逐字</b>断言消息内容。 </p>
 *
 * @author 二开
 */
public class ProductMasterRulesTest
{
    /** 层级超限的统一文案。 */
    private static final String MSG_LEVEL = "商品类型层级不能超过 5 级";

    /** 小数位越界的统一文案。 */
    private static final String MSG_DECIMALS = "计量单位小数位只能是 0 到 4";

    /** 默认单价为负的统一文案。 */
    private static final String MSG_PRICE = "默认单价不能为负数";

    /** 安全库存为负的统一文案。 */
    private static final String MSG_STOCK = "安全库存不能为负数";

    /* ==================== ① 层级推导 ==================== */

    @Test
    public void 层级_根类型为1级_父级已知时逐级加一()
    {
        assertEquals("根（parentLevel=null）必须是第 1 级", 1, ProductMasterRules.childLevel(null));
        assertEquals("父级 0 视同根，子级仍是第 1 级", 1, ProductMasterRules.childLevel(Integer.valueOf(0)));
        int[][] cases = { { 1, 2 }, { 2, 3 }, { 3, 4 }, { 4, 5 } };
        for (int[] c : cases)
        {
            assertEquals("父级 " + c[0] + " 的子级应为 " + c[1],
                    c[1], ProductMasterRules.childLevel(Integer.valueOf(c[0])));
        }
        assertEquals("最大层级常量必须与规格一致", 5, ProductMasterRules.MAX_TYPE_LEVEL);
    }

    @Test
    public void 层级_第五级通过_第六级被拒()
    {
        // 4 级的下级是 5 级：边界内，通过
        assertEquals(5, ProductMasterRules.childLevel(Integer.valueOf(4)));
        // 5 级的下级是 6 级：越界，拒绝
        try
        {
            ProductMasterRules.childLevel(Integer.valueOf(5));
            fail("第 6 级必须被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals(MSG_LEVEL, e.getMessage());
        }
    }

    /* ==================== ② 小数位范围 ==================== */

    @Test
    public void 小数位_0到4通过_越界被拒()
    {
        int[] passed = { 0, 1, 2, 3, 4 };
        for (int v : passed)
        {
            // 通过即不抛异常
            ProductMasterRules.checkUomDecimals(Integer.valueOf(v));
        }
        int[] rejected = { -1, 5, 9, -100 };
        for (int v : rejected)
        {
            try
            {
                ProductMasterRules.checkUomDecimals(Integer.valueOf(v));
                fail("小数位 " + v + " 必须被拒绝");
            }
            catch (ServiceException e)
            {
                assertEquals(MSG_DECIMALS, e.getMessage());
            }
        }
        // null 表示「未传」，由服务层归一为 2 后再校验，这里不判定
        ProductMasterRules.checkUomDecimals(null);
    }

    /* ==================== ③ 默认单价 ==================== */

    @Test
    public void 默认单价_0通过_负数被拒()
    {
        ProductMasterRules.checkDefaultPrice(BigDecimal.ZERO);
        ProductMasterRules.checkDefaultPrice(new BigDecimal("0.0000"));
        ProductMasterRules.checkDefaultPrice(new BigDecimal("12.3456"));
        ProductMasterRules.checkDefaultPrice(null);
        BigDecimal[] rejected = { new BigDecimal("-0.01"), new BigDecimal("-1"), new BigDecimal("-99999.9999") };
        for (BigDecimal v : rejected)
        {
            try
            {
                ProductMasterRules.checkDefaultPrice(v);
                fail("默认单价 " + v + " 必须被拒绝");
            }
            catch (ServiceException e)
            {
                assertEquals(MSG_PRICE, e.getMessage());
            }
        }
    }

    /* ==================== ④ 安全库存 ==================== */

    @Test
    public void 安全库存_未设置与0通过_负数被拒()
    {
        ProductMasterRules.checkSafetyStock(null);
        ProductMasterRules.checkSafetyStock(BigDecimal.ZERO);
        ProductMasterRules.checkSafetyStock(new BigDecimal("10.500"));
        try
        {
            ProductMasterRules.checkSafetyStock(new BigDecimal("-1"));
            fail("安全库存为负必须被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals(MSG_STOCK, e.getMessage());
        }
    }

    /* ==================== ⑤ 物化路径 ==================== */

    @Test
    public void 物化路径_根为斜杠_子级以父id拼接且以斜杠结尾()
    {
        assertEquals("根节点路径固定为 /", "/", ProductMasterRules.childPath(null, null));
        assertEquals("空白 parentId 同样视作根", "/", ProductMasterRules.childPath("/", ""));
        assertEquals("/A/", ProductMasterRules.childPath("/", "A"));
        assertEquals("/A/B/", ProductMasterRules.childPath("/A/", "B"));
        assertEquals("/A/B/C/", ProductMasterRules.childPath("/A/B/", "C"));
        assertTrue("结果必须以 / 结尾", ProductMasterRules.childPath("/A/", "B").endsWith("/"));
        // 末尾缺 "/" 的脏路径要能自愈
        assertEquals("/A/B/", ProductMasterRules.childPath("/A", "B"));
    }

    @Test
    public void 层级反推_路径层级与空值兜底()
    {
        assertEquals(1, ProductMasterRules.levelOfPath("/"));
        assertEquals(2, ProductMasterRules.levelOfPath("/A/"));
        assertEquals(3, ProductMasterRules.levelOfPath("/A/B/"));
        assertEquals(1, ProductMasterRules.levelOfPath(null));
        assertEquals(1, ProductMasterRules.levelOfPath(""));
        assertEquals(1, ProductMasterRules.levelOfPath("   "));
        // 与 childPath 互逆：拼出来的路径反推层级 = 父级 + 1
        assertEquals(2, ProductMasterRules.levelOfPath(ProductMasterRules.childPath("/", "A")));
        assertEquals(3, ProductMasterRules.levelOfPath(ProductMasterRules.childPath("/A/", "B")));
    }

    /* ==================== ⑦ 类型编码限长（真库缺陷的回归守卫） ==================== */

    /**
     * <p> 自动生成类型编码必须限长。 </p>
     *
     * <p> <b>这条用例的来历</b>：真库验收时建第 2 级类型直接 500 ——
     * {@code Data truncation: Data too long for column 'code'}。
     * 根因是留空编码时生成 {@code "PT" + 32 位 UUID} = 34 字符，超过 DDL 的 {@code varchar(32)}。
     * 内存桩单测<b>发现不了</b>这类问题（桩不校验列宽），所以这里显式把"长度"当成不变量来断言。 </p>
     */
    @Test
    public void 自动类型编码_不得超过列宽且前缀稳定()
    {
        // 32 位 UUID（本仓库 IdUtils.fastSimpleUUID 的形态）
        String uuid32 = "586FB3EA59FF47AEA3E160B38CD1D5D6";
        String code = ProductMasterRules.generateTypeCode(uuid32);
        assertTrue("32 位 id 生成的编码必须 ≤ varchar(32)",
                code.length() <= ProductMasterRules.MAX_TYPE_CODE_LENGTH);
        assertEquals(32, code.length());
        assertEquals("P" + uuid32.substring(0, 31), code);
        assertFalse(ProductMasterRules.isTypeCodeTooLong(code));

        // 更长的 id 也要被截到列宽以内
        String longId = uuid32 + uuid32;
        assertTrue(ProductMasterRules.generateTypeCode(longId).length() <= ProductMasterRules.MAX_TYPE_CODE_LENGTH);

        // 短 id：原样保留，不补不截
        assertEquals("PABC", ProductMasterRules.generateTypeCode("ABC"));

        // id 缺失：编码列可空，返回 null 而不是造一个无意义的短码
        assertEquals(null, ProductMasterRules.generateTypeCode(null));
        assertEquals(null, ProductMasterRules.generateTypeCode("   "));

        // 手工超长编码必须能被识别出来（服务层据此在入口拒绝，而不是让 MySQL 报截断）
        assertTrue(ProductMasterRules.isTypeCodeTooLong("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"));
        assertFalse(ProductMasterRules.isTypeCodeTooLong("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"));
        assertFalse(ProductMasterRules.isTypeCodeTooLong(null));
    }

    /* ==================== ⑥ 空白判定（服务层依赖） ==================== */

    @Test
    public void 空白判定_覆盖null空串与纯空格()
    {
        assertTrue(ProductMasterRules.isBlank(null));
        assertTrue(ProductMasterRules.isBlank(""));
        assertTrue(ProductMasterRules.isBlank("   "));
        assertFalse(ProductMasterRules.isBlank("A"));
    }
}
