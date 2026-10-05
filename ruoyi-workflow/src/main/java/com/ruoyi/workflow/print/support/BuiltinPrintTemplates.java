package com.ruoyi.workflow.print.support;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p> 内置打印版式的<b>唯一真源</b>（REQ-PRINT-011 / REQ-PRINT-013、AC-61 / AC-63） </p>
 *
 * <p> 2.0 之前内置版式是"全局唯一一套"：标题是单个 {@code DEFAULT_TITLE} 常量，
 * 版式是单个 {@code DEFAULT_FIELD_MAP}，因此《集团资金审批单》这类单据
 * "看着配好了、打出来是合同版式"。现在改为<b>按单据类型选用</b>： </p>
 *
 * <pre>
 *   t_template.builtin_print_key   →  keys(): contract / fund / matter / payment
 *                                        ↓
 *                                     titleOf(key) / fieldMapOf(key)
 * </pre>
 *
 * <p> 三条刻意的设计取舍： </p>
 * <ol>
 *   <li> <b>放在一个不依赖 Spring / DB 的纯静态类里</b>（design D2）：这样版式常量可以被
 *        真正的单元测试逐项断言，而不必起容器；{@code PrintServiceImpl} 只做"读列 + 传入 key"； </li>
 *   <li> <b>未知 key 不抛异常</b>，回退 {@code contract} 并记一条 warning（design D2）：
 *        打印是收尾的只读动作，为一个历史脏值让整张单据打不出来，比版式降级更糟。
 *        保存入口已拒绝非法 key，所以脏值只能来自人工 SQL； </li>
 *   <li> <b>{@code contract} 的版式与升级前逐字一致</b>，这是本次变更的"零回归锚点"
 *        （AC-61：标题与升级前一致）。所以它<b>不做</b>"顺手优化"，
 *        连 section 的字段顺序都保持原样。 </li>
 * </ol>
 *
 * @author 二开
 */
public final class BuiltinPrintTemplates {

    /** 默认版式键：存量模板与新模板都取它（DDL 默认值也是 {@code 'contract'}） */
    public static final String DEFAULT_KEY = "contract";

    /** 版式键 → 展示名称（同时用作打印件标题）。**顺序即配置页下拉顺序** */
    private static final Map<String, String> TITLES;

    /** 版式键 → 版式字段映射 JSON */
    private static final Map<String, String> FIELD_MAPS;

    /**
     * 版式键 → 签批栏推荐取值（{@code 0/1}）。
     *
     * <p> <b>这是 AC-62 能成立的关键</b>：内置版式必须自带签批栏取值，
     * 否则回退内置时 {@code fillDefaults} 会填成 {@code '0'}，
     * 《集团资金审批单》就还是"看着配好了、打出来没有签名区"。
     * 四套版式的栏目里都有按流程节点生成的签名栏（PRD 附录 A），因此都是开启。 </p>
     */
    private static final Map<String, String> SHOW_SIGNATURE;

    /**
     * 版式键 → 附件清单推荐取值。
     *
     * <p> 维持"打印件 = 表单信息 + 签批栏"的既有定位：附件清单仍由管理员显式打开
     * （design D5 只统一了**空值**口径，没有要求把附件清单默认打开）。 </p>
     */
    private static final Map<String, String> SHOW_ATTACHMENT;

    static {
        Map<String, String> titles = new LinkedHashMap<>();
        // ⚠ contract 的名称是**升级前的原值**（PrintServiceImpl.DEFAULT_TITLE），不得改动
        titles.put("contract", "集团合同类文件流转审批单");
        titles.put("fund", "集团资金审批单");
        titles.put("matter", "集团事项类打印审批单");
        titles.put("payment", "付款申请单");
        TITLES = Collections.unmodifiableMap(titles);

        Map<String, String> maps = new LinkedHashMap<>();
        maps.put("contract", buildContractFieldMap());
        maps.put("fund", buildFundFieldMap());
        maps.put("matter", buildMatterFieldMap());
        maps.put("payment", buildPaymentFieldMap());
        FIELD_MAPS = Collections.unmodifiableMap(maps);

        Map<String, String> sign = new LinkedHashMap<>();
        sign.put("contract", "1");
        sign.put("fund", "1");
        sign.put("matter", "1");
        sign.put("payment", "1");
        SHOW_SIGNATURE = Collections.unmodifiableMap(sign);

        Map<String, String> attach = new LinkedHashMap<>();
        attach.put("contract", "0");
        attach.put("fund", "0");
        attach.put("matter", "0");
        attach.put("payment", "0");
        SHOW_ATTACHMENT = Collections.unmodifiableMap(attach);
    }

    private BuiltinPrintTemplates() {
    }

    /** 全部版式键（4 个，顺序稳定） */
    public static List<String> keys() {
        return new ArrayList<>(TITLES.keySet());
    }

    /** 该 key 是否为已登记的内置版式 */
    public static boolean isValidKey(String key) {
        return StringUtils.isNotBlank(key) && TITLES.containsKey(key.trim());
    }

    /**
     * 展示名称 / 打印件标题。
     *
     * <p> 未登记的 key 回退 {@code contract}（不抛异常）。需要区分"是否发生了回退"时
     * 用 {@link #resolve(String, Consumer)}。 </p>
     */
    public static String titleOf(String key) {
        return TITLES.get(normalize(key));
    }

    /** 版式字段映射 JSON；未登记的 key 回退 {@code contract} */
    public static String fieldMapOf(String key) {
        return FIELD_MAPS.get(normalize(key));
    }

    /** 该版式的**签批栏推荐取值**（{@code 0/1}）；未登记的 key 回退 {@code contract} 的取值 */
    public static String showSignatureOf(String key) {
        return SHOW_SIGNATURE.get(normalize(key));
    }

    /** 该版式的**附件清单推荐取值**（{@code 0/1}） */
    public static String showAttachmentOf(String key) {
        return SHOW_ATTACHMENT.get(normalize(key));
    }

    /** 解析结果（供调用方判断是否发生了降级，并据此记 warning） */
    public static final class Resolution {

        private final String key;
        private final String requestedKey;
        private final boolean fallback;

        Resolution(String requestedKey, String key, boolean fallback) {
            this.requestedKey = requestedKey;
            this.key = key;
            this.fallback = fallback;
        }

        /** 实际使用的版式键 */
        public String getKey() {
            return key;
        }

        /** 请求的版式键（可能是脏值或空） */
        public String getRequestedKey() {
            return requestedKey;
        }

        /** 是否发生了"未知 key → contract"的降级 */
        public boolean isFallback() {
            return fallback;
        }

        /** 实际使用的打印件标题 */
        public String getTitle() {
            return TITLES.get(key);
        }

        /** 实际使用的版式字段映射 JSON */
        public String getFieldMap() {
            return FIELD_MAPS.get(key);
        }

        /** 该版式的签批栏推荐取值（0/1） */
        public String getShowSignature() {
            return SHOW_SIGNATURE.get(key);
        }

        /** 该版式的附件清单推荐取值（0/1） */
        public String getShowAttachment() {
            return SHOW_ATTACHMENT.get(key);
        }

        /** 降级时的告警文案（未降级返回 null）—— 由调用方决定怎么记日志 */
        public String warningMessage() {
            if (!fallback) {
                return null;
            }
            return "内置打印版式 key 未登记，已回退 '" + key + "'：请求值="
                    + (StringUtils.isBlank(requestedKey) ? "(空)" : "'" + requestedKey + "'")
                    + "，可用值=" + TITLES.keySet();
        }
    }

    /** 解析版式键（不记日志）；未登记 / 空白 → {@code contract} 且 {@code isFallback()==true} */
    public static Resolution resolve(String key) {
        return resolve(key, null);
    }

    /**
     * 解析版式键，并在发生降级时把告警交给 {@code warn}。
     *
     * <p> 用回调而不是直接 {@code log.warn}：这样"非法 key 会记 warning"这条验收
     * （任务 1.5）可以写成真正的单元测试 —— 测试传入一个收集器，断言收到了告警文案，
     * 而不必去挂 logback 的 appender。 </p>
     */
    public static Resolution resolve(String key, Consumer<String> warn) {
        String normalized = normalize(key);
        boolean fallback = !normalized.equals(trimToNull(key));
        Resolution r = new Resolution(key, normalized, fallback);
        if (fallback && warn != null) {
            warn.accept(r.warningMessage());
        }
        return r;
    }

    /** 空值/脏值一律落到 {@code contract} */
    private static String normalize(String key) {
        String k = trimToNull(key);
        return (k != null && TITLES.containsKey(k)) ? k : DEFAULT_KEY;
    }

    private static String trimToNull(String s) {
        return StringUtils.isBlank(s) ? null : s.trim();
    }

    /* ==================== 版式常量 ==================== */

    /**
     * <b>集团合同类文件流转审批单</b>（沿用 V1 PRD 7.8 的映射，<b>不改</b>）。
     *
     * <p> ⚠ 这是零回归锚点：存量单据（{@code builtin_print_key} 取默认值 contract）
     * 升级后的标题与版式必须与升级前逐项一致。所以这段代码是**从
     * {@code PrintServiceImpl.buildDefaultFieldMap()} 原样搬过来的**，
     * 只换了所在位置，没有改任何一行栏目、字段或 span。 </p>
     */
    private static String buildContractFieldMap() {
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
        // ⚠ contract 的签批栏是"按流程节点动态出栏"（不带固定栏目），保持升级前行为
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

    /**
     * <b>集团资金审批单</b>（PRD 附录 A / xlsx 版式 C）。
     *
     * <p> 栏目：审批单位 / 事项分类 / 计划类别 / 付款归属期 / 资金审批内容，
     * 以及签批栏固定的三栏「集团职能部门 / 集团分管领导 / 集团董事长」（AC-62 的第一验收项）。 </p>
     */
    private static String buildFundFieldMap() {
        JSONArray sections = new JSONArray();

        JSONObject base = new JSONObject();
        base.put("id", "base");
        JSONArray rows = new JSONArray();
        rows.add(row(cell("审批单位", "$submitterCompany", 3)));
        rows.add(row(cell("事项分类", "matterCategory", 1), cell("计划类别", "planType", 1), cell("付款归属期", "paymentPeriod", 1)));
        rows.add(row(cell("资金审批内容", "fundContent", 3)));
        base.put("rows", rows);
        sections.add(base);

        sections.add(signSection("签批栏",
                column("集团职能部门"),
                column("集团分管领导"),
                column("集团董事长")));

        sections.add(attachSection());

        JSONObject root = new JSONObject();
        root.put("sections", sections);
        return root.toJSONString();
    }

    /**
     * <b>集团事项类打印审批单</b>（PRD 附录 A / xlsx 版式 B「集团请示文件流转审批单」）。
     *
     * <p> 前 6 个栏目是表单字段，后 7 栏按流程节点生成（签批栏）。 </p>
     */
    private static String buildMatterFieldMap() {
        JSONArray sections = new JSONArray();

        JSONObject base = new JSONObject();
        base.put("id", "base");
        JSONArray rows = new JSONArray();
        rows.add(row(cell("公文来源-报送单位", "$submitterCompany", 1), cell("报送人", "$submitter", 1), cell("报送时间", "$submitTime", 1)));
        rows.add(row(cell("文件编号", "docNo", 3)));
        rows.add(row(cell("信息名称", "matterTitle", 3)));
        rows.add(row(cell("文件类型", "docType", 1), cell("加急程度", "urgency", 1), cell("文件概要", "summary", 1)));
        base.put("rows", rows);
        sections.add(base);

        sections.add(signSection("公文接收及处理",
                column("公文接收及处理"),
                column("公文关联单位处理"),
                column("集团领导意见-分管领导"),
                column("集团领导意见-总经理"),
                column("集团领导意见-董事长"),
                column("印鉴证照管理部门"),
                column("公文回传及归档")));

        sections.add(attachSection());

        JSONObject root = new JSONObject();
        root.put("sections", sections);
        return root.toJSONString();
    }

    /**
     * <b>付款申请单</b>（PRD 附录 A / xlsx 版式 D）。
     *
     * <p> 抬头（日期 / 计划内-计划外）+ 收款信息 + 付款信息 + 签批栏。
     * 金额大写由 {@code design-amount} 控件提供，这里用只取大写部分的
     * {@code format=amountUpper} 单元格（打印件上"金额"与"金额大写"是两个栏目）。 </p>
     */
    private static String buildPaymentFieldMap() {
        JSONArray sections = new JSONArray();

        // 抬头
        JSONObject head = new JSONObject();
        head.put("id", "head");
        JSONArray headRows = new JSONArray();
        headRows.add(row(cell("日期", "$submitTime", 1), cell("计划内/计划外", "planType", 2)));
        head.put("rows", headRows);
        sections.add(head);

        // 收款信息
        JSONObject payee = new JSONObject();
        payee.put("id", "payee");
        payee.put("title", "收款信息");
        JSONArray payeeRows = new JSONArray();
        payeeRows.add(row(cell("收款单位名称", "payeeName", 3)));
        payeeRows.add(row(cell("开户银行", "payeeBank", 1), cell("账号", "payeeAccount", 2)));
        payee.put("rows", payeeRows);
        sections.add(payee);

        // 付款信息
        JSONObject pay = new JSONObject();
        pay.put("id", "pay");
        pay.put("title", "付款信息");
        JSONArray payRows = new JSONArray();
        payRows.add(row(cell("付款单位名称", "payerName", 3)));
        payRows.add(row(cell("开户行", "payerBank", 1), cell("一级分类", "categoryL1", 1), cell("二级分类", "categoryL2", 1)));
        payRows.add(row(cell("付款归属期", "paymentPeriod", 1), cell("结算方式", "settleType", 1), cell("回单", "receiptNo", 1)));
        payRows.add(row(cell("银行付款用途", "bankPurpose", 3)));
        payRows.add(row(cell("付款事由", "paymentReason", 3)));
        payRows.add(row(cell("金额", "amount", 3)));
        payRows.add(amountUpperRow("金额大写", "amount"));
        payRows.add(row(cell("备注", "remark", 3)));
        pay.put("rows", payRows);
        sections.add(pay);

        // 签批栏：制单人取发起人（PRD 附录 A）
        sections.add(signSection("签批栏",
                column("经理"),
                column("财务"),
                column("部门负责人"),
                column("财务负责人"),
                builtinColumn("制单人", "$submitter"),
                column("领款人")));

        sections.add(attachSection());

        JSONObject root = new JSONObject();
        root.put("sections", sections);
        return root.toJSONString();
    }

    /* ==================== JSON 拼装小工具 ==================== */

    private static JSONObject signSection(String title, JSONObject... columns) {
        JSONObject sign = new JSONObject();
        sign.put("id", "sign");
        sign.put("type", "dynamic");
        sign.put("source", "flowNodes");
        sign.put("title", title);
        JSONArray cols = new JSONArray();
        for (JSONObject c : columns) {
            cols.add(c);
        }
        sign.put("columns", cols);
        return sign;
    }

    private static JSONObject attachSection() {
        JSONObject attach = new JSONObject();
        attach.put("id", "attach");
        attach.put("type", "attachmentList");
        return attach;
    }

    /** 一栏签名区，按**节点名**匹配流程节点；没匹配上也要出栏（留空供手写） */
    private static JSONObject column(String label) {
        JSONObject c = new JSONObject();
        c.put("label", label);
        return c;
    }

    /** 一栏由内置变量直接取值（如"制单人 = 发起人"），不按流程节点匹配 */
    private static JSONObject builtinColumn(String label, String field) {
        JSONObject c = new JSONObject();
        c.put("label", label);
        c.put("field", field);
        return c;
    }

    private static JSONObject amountUpperRow(String label, String field) {
        JSONObject c = cell(label, field, 3);
        c.put("format", "amountUpper");
        JSONObject r = new JSONObject();
        JSONArray arr = new JSONArray();
        arr.add(c);
        r.put("cells", arr);
        return r;
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
