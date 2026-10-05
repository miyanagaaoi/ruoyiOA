package com.ruoyi.workflow.print.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <p> 内置打印版式的<b>逐项断言</b>（2.0 B2 §1.3–§1.5；REQ-PRINT-011、AC-61） </p>
 *
 * <p> 为什么这些断言值得写：内置版式的栏目就是 AC-61 的验收依据
 * （"每套标题与栏目与 xlsx 对应版式逐项一致"），而它是**代码常量**——
 * 没有人会在改坏它的时候收到编译错误。这里把 PRD 附录 A 的栏目清单钉死在测试里。 </p>
 *
 * <p> 其中 {@code contract} 的"逐字一致"用例是**零回归锚点**：它拿的是升级前
 * {@code PrintServiceImpl.buildDefaultFieldMap()} 的实现快照，改一个栏目/字段/span 都会失败。 </p>
 *
 * @author 二开
 */
public class BuiltinPrintTemplatesTest {

    /* ==================== 注册表 ==================== */

    @Test
    public void 恰好四套内置版式_键与顺序稳定() {
        assertEquals("[contract, fund, matter, payment]", BuiltinPrintTemplates.keys().toString());
    }

    @Test
    public void 四套版式的标题与升级前口径一致() {
        assertEquals("集团合同类文件流转审批单", BuiltinPrintTemplates.titleOf("contract"));
        assertEquals("集团资金审批单", BuiltinPrintTemplates.titleOf("fund"));
        assertEquals("集团事项类打印审批单", BuiltinPrintTemplates.titleOf("matter"));
        assertEquals("付款申请单", BuiltinPrintTemplates.titleOf("payment"));
    }

    /* ==================== §1.4 每套版式的栏目逐项对应 ==================== */

    /**
     * <b>零回归锚点</b>：contract 的版式与升级前 {@code buildDefaultFieldMap()} 的产物**逐字一致**。
     *
     * <p> 下面 {@link #legacyContractFieldMap()} 是升级前那段实现的**原样快照**
     * （同样的 put 顺序，因此 {@code toJSONString()} 可逐字比较）。
     * 自证有效：改动 {@code BuiltinPrintTemplates.buildContractFieldMap()} 的任一栏目/字段/span，
     * 或用 {@link JSONObject} 的默认 Map 顺序之外的方式重建，本用例即失败。 </p>
     */
    @Test
    public void contract_版式与升级前逐字一致() {
        assertEquals(legacyContractFieldMap(), BuiltinPrintTemplates.fieldMapOf("contract"));
    }

    @Test
    public void fund_栏目与PRD附录A逐项对应() {
        JSONObject map = parse("fund");
        assertEquals("集团资金审批单", BuiltinPrintTemplates.titleOf("fund"));

        // 栏目 1–5：审批单位 / 事项分类 / 计划类别 / 付款归属期 / 资金审批内容
        assertEquals("[审批单位, 事项分类, 计划类别, 付款归属期, 资金审批内容]", labelsOf(map).toString());
        assertEquals("$submitterCompany", fieldOf(map, "审批单位"));
        assertEquals("matterCategory", fieldOf(map, "事项分类"));
        assertEquals("planType", fieldOf(map, "计划类别"));
        assertEquals("paymentPeriod", fieldOf(map, "付款归属期"));
        assertEquals("fundContent", fieldOf(map, "资金审批内容"));

        // 栏目 6–8：签批栏三栏（AC-62 的第一验收项）
        assertEquals("[集团职能部门, 集团分管领导, 集团董事长]", signColumnsOf(map).toString());
    }

    @Test
    public void matter_栏目与PRD附录A逐项对应() {
        JSONObject map = parse("matter");
        assertEquals("集团事项类打印审批单", BuiltinPrintTemplates.titleOf("matter"));

        // 公文来源（报送单位/报送人/报送时间）+ 文件编号 / 信息名称 / 文件类型 / 加急程度 / 文件概要
        assertEquals("[公文来源-报送单位, 报送人, 报送时间, 文件编号, 信息名称, 文件类型, 加急程度, 文件概要]",
                labelsOf(map).toString());
        assertEquals("$submitterCompany", fieldOf(map, "公文来源-报送单位"));
        assertEquals("$submitter", fieldOf(map, "报送人"));
        assertEquals("$submitTime", fieldOf(map, "报送时间"));
        assertEquals("docNo", fieldOf(map, "文件编号"));
        assertEquals("matterTitle", fieldOf(map, "信息名称"));
        assertEquals("docType", fieldOf(map, "文件类型"));
        assertEquals("urgency", fieldOf(map, "加急程度"));
        assertEquals("summary", fieldOf(map, "文件概要"));

        // 7 栏按节点生成
        assertEquals("[公文接收及处理, 公文关联单位处理, 集团领导意见-分管领导, 集团领导意见-总经理, "
                + "集团领导意见-董事长, 印鉴证照管理部门, 公文回传及归档]", signColumnsOf(map).toString());
    }

    @Test
    public void payment_栏目与PRD附录A逐项对应() {
        JSONObject map = parse("payment");
        assertEquals("付款申请单", BuiltinPrintTemplates.titleOf("payment"));

        // 抬头 + 收款信息 + 付款信息
        assertEquals("[日期, 计划内/计划外]", labelsOfSection(map, "head").toString());
        assertEquals("[收款单位名称, 开户银行, 账号]", labelsOfSection(map, "payee").toString());
        assertEquals("[付款单位名称, 开户行, 一级分类, 二级分类, 付款归属期, 结算方式, 回单, "
                + "银行付款用途, 付款事由, 金额, 金额大写, 备注]", labelsOfSection(map, "pay").toString());
        assertEquals("payeeName", fieldOfSection(map, "payee", "收款单位名称"));
        assertEquals("payeeBank", fieldOfSection(map, "payee", "开户银行"));
        assertEquals("payeeAccount", fieldOfSection(map, "payee", "账号"));
        assertEquals("amount", fieldOfSection(map, "pay", "金额"));
        // 金额大写只取大写部分（打印件上"金额"与"金额大写"是两个栏目）
        assertEquals("amountUpper", formatOfSection(map, "pay", "金额大写"));

        // 签批栏：制单人取发起人
        assertEquals("[经理, 财务, 部门负责人, 财务负责人, 制单人, 领款人]", signColumnsOf(map).toString());
        assertEquals("$submitter", fieldOfSignColumn(map, "制单人"));
    }

    /* ==================== §3.2 版式自带的签批栏推荐取值（AC-62） ==================== */

    /**
     * <b>AC-62 在服务端的落点</b>：四套内置版式都必须自带"签批栏开启"。
     *
     * <p> 若这一条不成立，回退内置时 {@code fillDefaults} 会把签批栏填成 {@code '0'}，
     * 《集团资金审批单》就还是"看着配好了、打出来没有签名区"。 </p>
     */
    @Test
    public void 四套版式都自带签批栏开启的推荐取值() {
        for (String key : BuiltinPrintTemplates.keys()) {
            assertEquals("签批栏应默认开启：" + key, "1", BuiltinPrintTemplates.showSignatureOf(key));
            assertEquals("附件清单维持既有定位（显式打开）：" + key, "0", BuiltinPrintTemplates.showAttachmentOf(key));
        }
        // 非法/空 key 回退 contract 的取值，不返回 null
        assertEquals("1", BuiltinPrintTemplates.showSignatureOf("fund-v2"));
        assertEquals("0", BuiltinPrintTemplates.showAttachmentOf(null));
    }

    /* ==================== §1.5 未知 key 的降级 ==================== */

    @Test
    public void 未登记的key回退contract并记warning() {
        AtomicReference<String> warned = new AtomicReference<>();
        BuiltinPrintTemplates.Resolution r = BuiltinPrintTemplates.resolve("fund-v2", warned::set);

        assertEquals("contract", r.getKey());
        assertEquals("集团合同类文件流转审批单", r.getTitle());
        assertTrue("应该被判定为降级", r.isFallback());
        assertNotNull("降级必须留下告警文案（供服务层 log.warn）", warned.get());
        assertTrue("告警文案要点出脏值本身：" + warned.get(), warned.get().contains("fund-v2"));
    }

    @Test
    public void 空key同样回退contract且不抛异常() {
        assertEquals("contract", BuiltinPrintTemplates.resolve(null).getKey());
        assertEquals("contract", BuiltinPrintTemplates.resolve("").getKey());
        assertEquals("contract", BuiltinPrintTemplates.resolve("   ").getKey());
        assertTrue(BuiltinPrintTemplates.resolve(null).isFallback());
    }

    @Test
    public void 四个合法key都不降级() {
        for (String key : BuiltinPrintTemplates.keys()) {
            BuiltinPrintTemplates.Resolution r = BuiltinPrintTemplates.resolve(key, msg -> {
                throw new AssertionError("合法 key 不应产生告警：" + msg);
            });
            assertFalse("合法 key 不应被判定为降级：" + key, r.isFallback());
            assertEquals(key, r.getKey());
            assertNotNull(r.getFieldMap());
            assertTrue(BuiltinPrintTemplates.isValidKey(key));
        }
    }

    @Test
    public void 非法key不被isValidKey接受() {
        assertFalse(BuiltinPrintTemplates.isValidKey("FUND"));
        assertFalse(BuiltinPrintTemplates.isValidKey("fund-v2"));
        assertFalse(BuiltinPrintTemplates.isValidKey(""));
        assertFalse(BuiltinPrintTemplates.isValidKey(null));
        // 前后空白按"可以接受的输入"处理（保存入口与 resolve 口径一致，避免"手滑多打一个空格就存不上"）
        assertTrue(BuiltinPrintTemplates.isValidKey(" fund "));
    }

    /* ==================== 工具 ==================== */

    private static JSONObject parse(String key) {
        return JSON.parseObject(BuiltinPrintTemplates.fieldMapOf(key));
    }

    private static JSONArray sections(String key) {
        return parse(key).getJSONArray("sections");
    }

    /** 某 key 的**全部字段表栏目**（跳过签批栏/附件清单这类没有 rows 的 section） */
    private static List<String> labelsOf(JSONObject map) {
        List<String> out = new ArrayList<>();
        for (Object o : map.getJSONArray("sections")) {
            JSONObject sec = (JSONObject) o;
            JSONArray rows = sec.getJSONArray("rows");
            if (rows != null) {
                out.addAll(labelsOfRows(rows));
            }
        }
        return out;
    }

    private static List<String> labelsOfSection(JSONObject map, String sectionId) {
        for (Object o : map.getJSONArray("sections")) {
            JSONObject sec = (JSONObject) o;
            if (sectionId.equals(sec.getString("id"))) {
                return labelsOfRows(sec.getJSONArray("rows"));
            }
        }
        throw new AssertionError("找不到 section：" + sectionId);
    }

    private static List<String> labelsOfRows(JSONArray rows) {
        List<String> out = new ArrayList<>();
        for (Object r : rows) {
            for (Object c : ((JSONObject) r).getJSONArray("cells")) {
                out.add(((JSONObject) c).getString("label"));
            }
        }
        return out;
    }

    private static String cellProp(JSONObject map, String label, String prop) {
        for (Object o : map.getJSONArray("sections")) {
            JSONArray rows = ((JSONObject) o).getJSONArray("rows");
            if (rows == null) {
                continue;
            }
            for (Object r : rows) {
                for (Object c : ((JSONObject) r).getJSONArray("cells")) {
                    JSONObject cell = (JSONObject) c;
                    if (label.equals(cell.getString("label"))) {
                        return cell.getString(prop);
                    }
                }
            }
        }
        throw new AssertionError("找不到栏目：" + label);
    }

    private static String fieldOf(JSONObject map, String label) {
        return cellProp(map, label, "field");
    }

    private static String fieldOfSection(JSONObject map, String sectionId, String label) {
        return cellPropOfSection(map, sectionId, label, "field");
    }

    private static String formatOfSection(JSONObject map, String sectionId, String label) {
        return cellPropOfSection(map, sectionId, label, "format");
    }

    private static String cellPropOfSection(JSONObject map, String sectionId, String label, String prop) {
        for (Object o : map.getJSONArray("sections")) {
            JSONObject sec = (JSONObject) o;
            if (!sectionId.equals(sec.getString("id"))) {
                continue;
            }
            for (Object r : sec.getJSONArray("rows")) {
                for (Object c : ((JSONObject) r).getJSONArray("cells")) {
                    JSONObject cell = (JSONObject) c;
                    if (label.equals(cell.getString("label"))) {
                        return cell.getString(prop);
                    }
                }
            }
        }
        throw new AssertionError("找不到栏目：" + sectionId + "/" + label);
    }

    /** 签批栏的固定栏目（{@code sign.columns}）；没有 columns 时返回空表（contract 就是这种） */
    private static List<String> signColumnsOf(JSONObject map) {
        List<String> out = new ArrayList<>();
        for (Object o : map.getJSONArray("sections")) {
            JSONObject sec = (JSONObject) o;
            if (!"sign".equals(sec.getString("id"))) {
                continue;
            }
            JSONArray cols = sec.getJSONArray("columns");
            if (cols != null) {
                for (Object c : cols) {
                    out.add(((JSONObject) c).getString("label"));
                }
            }
        }
        return out;
    }

    private static String fieldOfSignColumn(JSONObject map, String label) {
        for (Object o : map.getJSONArray("sections")) {
            JSONObject sec = (JSONObject) o;
            if (!"sign".equals(sec.getString("id"))) {
                continue;
            }
            JSONArray cols = sec.getJSONArray("columns");
            if (cols == null) {
                continue;
            }
            for (Object c : cols) {
                if (label.equals(((JSONObject) c).getString("label"))) {
                    return ((JSONObject) c).getString("field");
                }
            }
        }
        throw new AssertionError("签批栏找不到栏目：" + label);
    }

    /**
     * 升级前 {@code PrintServiceImpl.buildDefaultFieldMap()} 的**原样快照**（零回归锚点）。
     *
     * <p> 刻意保留"一个 JSONObject 一个 put"的写法与顺序，这样 {@code toJSONString()}
     * 可以与现实现逐字比较；换成 Map.of 之类的无序结构会让这个断言失去意义。 </p>
     */
    private static String legacyContractFieldMap() {
        JSONObject base = new JSONObject();
        base.put("id", "base");
        JSONArray rows = new JSONArray();

        rows.add(row(cell("提报单位", "$submitterDept", 1), cell("报送人", "$submitter", 1), cell("报送时间", "$submitTime", 1)));
        rows.add(row(cell("合同编号", "contractNo", 3)));
        rows.add(row(cell("合同全称", "contractName", 3)));
        rows.add(row(cell("合同签订主体-甲方", "ourCompany", 1), cell("合同签订主体-乙方", "counterpartyName", 1), cell("合同金额", "amount", 1)));
        rows.add(row(cell("履约开始", "startDate", 1), cell("履约结束", "endDate", 1), cell("合同签订时间", "signDate", 1)));
        rows.add(row(cell("其他会审部门", "jointDepts", 3)));
        rows.add(row(cell("相关说明", "description", 3)));
        base.put("rows", rows);

        JSONArray sections = new JSONArray();
        sections.add(base);
        JSONObject sign = new JSONObject();
        sign.put("id", "sign");
        sign.put("type", "dynamic");
        sign.put("source", "flowNodes");
        sections.add(sign);

        JSONObject attach = new JSONObject();
        attach.put("id", "attach");
        attach.put("type", "attachmentList");
        sections.add(attach);

        JSONObject root = new JSONObject();
        root.put("sections", sections);
        return root.toJSONString();
    }

    private static JSONObject cell(String label, String field, int span) {
        JSONObject c = new JSONObject();
        c.put("label", label);
        c.put("field", field);
        c.put("span", span);
        return c;
    }

    private static JSONObject row(JSONObject... cells) {
        JSONObject r = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject c : cells) {
            arr.add(c);
        }
        r.put("cells", arr);
        return r;
    }
}
