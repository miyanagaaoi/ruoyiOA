package com.ruoyi.ctms.erp.posting;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.junit.Before;
import org.junit.Test;

import com.ruoyi.ctms.erp.posting.domain.ErpStockOut;
import com.ruoyi.ctms.erp.posting.mapper.ErpStockOutMapper;

import static com.ruoyi.ctms.erp.posting.ErpPostingTestSupport.StubStockOutMapper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> <b>按来源单查下游出库单</b>（t29；销售订单反审核的上游守卫缺口）。 </p>
 *
 * <p> 销售侧按<b>固定签名</b> {@code List<ErpStockOut> selectBySourceDocId(@Param("sourceDocId") String)}
 * 调用本方法，因此本类把两件"改了就会破坏销售侧"的事钉死： </p>
 * <ol>
 *   <li> <b>接口签名与 XML 语句</b>：反射 + 源码级断言（方法名/参数/注解/返回类型，
 *        XML 里的 {@code source_doc_id = #{sourceDocId}} 与 {@code del_flag = '0'}、
 *        复用 resultMap 与 {@code selectStockOutVo}、稳定排序、既有语句未被破坏）； </li>
 *   <li> <b>语义</b>（内存桩，照既有风格）：多条命中全部返回且排除已删除；无命中返回
 *        <b>空集合而不是 null</b>；按 {@code (create_time, id)} 稳定排序。 </li>
 * </ol>
 *
 * <p> ⚠ 桩与 XML 是"同一口径的两处实现"：桩按 {@code (create_time, id)} 排序是为了让排序口径可观察，
 * 真库上的 SQL 行为由 T13/T20 的接口级用例覆盖（本类不连库）。 </p>
 *
 * @author 二开
 */
public class ErpStockOutSourceDocQueryTest
{
    private static final String SOURCE_DOC_ID = "SO202610000001";

    private final StubStockOutMapper mapper = new StubStockOutMapper();

    @Before
    public void setUp()
    {
        mapper.docs.clear();
    }

    /* ==================== ① 签名与 XML（改了就会破坏销售侧） ==================== */

    @Test
    public void mapperSignatureIsFrozenForSalesSide() throws Exception
    {
        Method method = ErpStockOutMapper.class.getMethod("selectBySourceDocId", String.class);

        assertEquals("返回类型必须是 List", List.class, method.getReturnType());
        assertEquals("参数只能有一个", 1, method.getParameterTypes().length);
        assertEquals(String.class, method.getParameterTypes()[0]);

        Annotation[] annotations = method.getParameterAnnotations()[0];
        boolean hasParamName = false;
        for (Annotation annotation : annotations)
        {
            if (annotation instanceof Param && "sourceDocId".equals(((Param) annotation).value()))
            {
                hasParamName = true;
            }
        }
        assertTrue("参数必须标注 @Param(\"sourceDocId\")（XML 用 #{sourceDocId} 绑定）", hasParamName);

        // 只读：接口上不得出现"按来源单写/删"的方法
        for (Method declared : ErpStockOutMapper.class.getMethods())
        {
            String name = declared.getName();
            assertTrue("本方法族不得引入按来源单的写入语义：" + name,
                    !(name.startsWith("update") && name.contains("SourceDoc"))
                            && !(name.startsWith("delete") && name.contains("SourceDoc"))
                            && !(name.startsWith("insert") && name.contains("SourceDoc")));
        }
    }

    @Test
    public void xmlStatementUsesSourceDocIdAndDelFlagAndReusesFragments()
    {
        String xml = mapperXml();

        // 新语句存在、且是 <select>（只读）
        assertTrue("XML 应有 selectBySourceDocId 语句", xml.contains("id=\"selectBySourceDocId\""));
        String block = statementBlock(xml, "selectBySourceDocId");
        assertTrue("必须是 <select>（只读）", block.trim().startsWith("<select"));
        assertTrue("应复用既有 resultMap", block.contains("resultMap=\"ErpStockOutResult\""));
        assertTrue("应复用既有列片段", block.contains("<include refid=\"selectStockOutVo\"/>"));
        assertTrue("过滤条件应为 source_doc_id = #{sourceDocId}",
                block.contains("d.source_doc_id = #{sourceDocId}"));
        assertTrue("只能取未删除", block.contains("d.del_flag = '0'"));
        assertTrue("排序应稳定（create_time, id）", block.contains("order by d.create_time, d.id"));
        // O1（t38 的 low 观察，t40 授权跨包改动）：**不得**按 status 过滤。
        // 冻结契约是"状态口径留调用方"：销售订单反审核守卫自己判 status != 'voided'，
        // 所以本查询必须把作废行也返回（否则"作废下游后上游可反审核"与"有未作废下游时拦住"
        // 两条语义都会被 SQL 单方面决定）。注意 block 里只有 <include refid=.../> 不展开，
        // 因此"列片段里有 d.status 这列"不会误伤本条断言。
        assertFalse("不得按状态过滤（状态口径留调用方）：发现 status 条件 → " + block,
                block.contains("status"));

        // 既有语句一个都不能少（"不影响既有 select 行为"的静态证据）
        for (String id : new String[] { "selectStockOutList", "selectStockOutById",
                "insertStockOut", "updateStockOut", "deleteStockOutByIds" })
        {
            assertTrue("既有语句仍在：" + id, xml.contains("id=\"" + id + "\""));
        }
        // 表内共 34 列，列片段与表逐一对应（对 information_schema 的核对见 notes §12）
        assertEquals("列片段应覆盖表内全部 34 列", 34,
                countDistinctColumns(block + selectVoFragment(xml)));
    }

    /* ==================== ② 语义（内存桩） ==================== */

    @Test
    public void multipleMatchesAreAllReturnedAndDeletedRowsExcluded()
    {
        mapper.seed(doc("OUT-1", SOURCE_DOC_ID, "0", "2026-10-05 10:00:00"));
        mapper.seed(doc("OUT-2", SOURCE_DOC_ID, "1", "2026-10-05 11:00:00"));   // 已删除 → 不返回
        mapper.seed(doc("OUT-3", SOURCE_DOC_ID, "0", "2026-10-05 12:00:00"));
        mapper.seed(doc("OUT-4", "SO2026100000FF", "0", "2026-10-05 13:00:00")); // 别的来源单 → 不返回

        List<ErpStockOut> rows = mapper.selectBySourceDocId(SOURCE_DOC_ID);

        assertNotNull("无命中也要返回空集合，不为 null", rows);
        assertEquals("命中 2 条（已删除的不算）", 2, rows.size());
        assertEquals("OUT-1", rows.get(0).getId());
        assertEquals("OUT-3", rows.get(1).getId());
        for (ErpStockOut row : rows)
        {
            assertEquals("只含未删除", "0", row.getDelFlag());
            assertEquals("只含该来源单", SOURCE_DOC_ID, row.getSourceDocId());
        }
    }

    @Test
    public void noMatchReturnsEmptyListNotNull()
    {
        mapper.seed(doc("OUT-5", "SO2026100000AA", "0", "2026-10-05 10:00:00"));

        List<ErpStockOut> rows = mapper.selectBySourceDocId("SO-NOT-EXIST");

        assertNotNull("无命中必须返回空集合而不是 null", rows);
        assertTrue("无命中必须为空集合", rows.isEmpty());
    }

    /**
     * <p> <b>O1（t38 的 low 观察，t40 授权跨包改动）：作废行<b>仍被返回</b></b>。 </p>
     *
     * <p> 冻结契约是"<b>状态口径留调用方</b>"：本查询只按 {@code source_doc_id} + {@code del_flag='0'} 过滤，
     * <b>不</b>过滤 {@code status}。销售订单反审核守卫正是靠这一点：它拿到全部行之后<b>自己</b>判断
     * {@code status != 'voided'}，于是"下游全部作废后上游可反审核"与"有未作废下游时拦住"两条
     * 语义都由调用方决定（见 {@code ErpSalesOrderServiceImpl.checkNoDownstreamStockOuts}）。 </p>
     *
     * <p> <b>反向对照（本轮实测，见 notes/05a-sales.md §11.4）</b>：往
     * {@code ErpStockOutMapper.xml} 的 {@code selectBySourceDocId} 里临时加一句
     * {@code and d.status != 'voided'}，本用例与
     * {@link #multipleMatchesAreAllReturnedAndDeletedRowsExcluded} 都会变红
     * （前者"作废行应仍被返回"失败；后者命中的行数不足以覆盖已删除过滤的对照）。 </p>
     */
    @Test
    public void voidedRowsAreStillReturnedBecauseStatusIsTheCallersBusiness()
    {
        mapper.seed(doc("OUT-V1", SOURCE_DOC_ID, "0", "2026-10-05 10:00:00"));
        ErpStockOut voided = doc("OUT-V2", SOURCE_DOC_ID, "0", "2026-10-05 11:00:00");
        voided.setStatus("voided");
        mapper.seed(voided);
        // 已删除 + 作废：del_flag 才是本查询的口径，仍然不返回
        ErpStockOut deletedVoided = doc("OUT-V3", SOURCE_DOC_ID, "1", "2026-10-05 12:00:00");
        deletedVoided.setStatus("voided");
        mapper.seed(deletedVoided);

        List<ErpStockOut> rows = mapper.selectBySourceDocId(SOURCE_DOC_ID);

        assertEquals("命中 2 条：作废行仍被返回，已删除行被 del_flag 排除", 2, rows.size());
        assertEquals("OUT-V1", rows.get(0).getId());
        assertEquals("作废行必须仍在结果里（状态口径留调用方）", "OUT-V2", rows.get(1).getId());
        assertEquals("voided", rows.get(1).getStatus());
        for (ErpStockOut row : rows)
        {
            assertEquals("只按 del_flag 过滤", "0", row.getDelFlag());
        }
    }

    @Test
    public void rowsAreOrderedByCreateTimeThenId()
    {
        // 故意乱序插入；其中两条 create_time 相同 → 用 id 兜底
        mapper.seed(doc("OUT-B", SOURCE_DOC_ID, "0", "2026-10-05 12:00:00"));
        mapper.seed(doc("OUT-A", SOURCE_DOC_ID, "0", "2026-10-05 09:00:00"));
        mapper.seed(doc("OUT-D", SOURCE_DOC_ID, "0", "2026-10-05 12:00:00"));
        mapper.seed(doc("OUT-C", SOURCE_DOC_ID, "0", "2026-10-05 12:00:00"));

        List<ErpStockOut> rows = mapper.selectBySourceDocId(SOURCE_DOC_ID);

        assertEquals(4, rows.size());
        assertEquals("先按 create_time", "OUT-A", rows.get(0).getId());
        assertEquals("同秒再按 id", "OUT-B", rows.get(1).getId());
        assertEquals("OUT-C", rows.get(2).getId());
        assertEquals("OUT-D", rows.get(3).getId());
    }

    @Test
    public void nullSourceDocIdMatchesNothing()
    {
        mapper.seed(doc("OUT-6", SOURCE_DOC_ID, "0", "2026-10-05 10:00:00"));

        List<ErpStockOut> rows = mapper.selectBySourceDocId(null);

        assertNotNull("入参为 null 时同样返回空集合", rows);
        assertTrue(rows.isEmpty());
    }

    /* ==================== 夹具与小工具 ==================== */

    private static ErpStockOut doc(String id, String sourceDocId, String delFlag, String createTime)
    {
        ErpStockOut doc = new ErpStockOut();
        doc.setId(id);
        doc.setDocNo(id);
        doc.setSourceDocType("sales_order");
        doc.setSourceDocId(sourceDocId);
        doc.setDelFlag(delFlag);
        doc.setStatus("approved");
        try
        {
            doc.setCreateTime(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(createTime));
        }
        catch (java.text.ParseException e)
        {
            throw new IllegalStateException(e);
        }
        return doc;
    }

    /**
     * 读 Mapper XML（测试期优先取 {@code target/classes} 里的已拷贝副本，回退到源码目录）。
     *
     * @return XML 全文
     */
    private static String mapperXml()
    {
        String relative = "mapper/erp/ErpStockOutMapper.xml";
        File[] candidates = {
                new File("target/classes/" + relative),
                new File("src/main/resources/" + relative) };
        for (File candidate : candidates)
        {
            if (candidate.isFile())
            {
                try
                {
                    return new String(java.nio.file.Files.readAllBytes(candidate.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                catch (Exception e)
                {
                    throw new IllegalStateException("读取 XML 失败：" + candidate, e);
                }
            }
        }
        fail("找不到 ErpStockOutMapper.xml（尝试过 target/classes 与 src/main/resources）");
        return "";
    }

    /**
     * 取某个语句块（{@code <select id="x"> ... </select>}）。
     *
     * @param xml XML 全文
     * @param id  语句 id
     * @return 语句块
     */
    private static String statementBlock(String xml, String id)
    {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?s)<select[^>]*id=\"" + id + "\"[^>]*>.*?</select>")
                .matcher(xml);
        assertTrue("找不到语句块：" + id, matcher.find());
        return matcher.group();
    }

    /**
     * 取列片段 {@code selectStockOutVo} 的本体。
     *
     * @param xml XML 全文
     * @return 片段本体
     */
    private static String selectVoFragment(String xml)
    {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?s)<sql id=\"selectStockOutVo\">(.*?)</sql>").matcher(xml);
        assertTrue("找不到 selectStockOutVo 片段", matcher.find());
        return matcher.group(1);
    }

    /**
     * 统计片段里 {@code d.<列名>} 的去重列数（用于"列片段覆盖表内全部列"）。
     *
     * @param text 片段文本
     * @return 去重列数
     */
    private static int countDistinctColumns(String text)
    {
        java.util.Set<String> columns = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("d\\.([a-z_]+)").matcher(text);
        while (matcher.find())
        {
            columns.add(matcher.group(1));
        }
        return columns.size();
    }
}
