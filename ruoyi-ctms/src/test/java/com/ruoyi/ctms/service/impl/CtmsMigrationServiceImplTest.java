package com.ruoyi.ctms.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import com.ruoyi.ctms.controller.CtmsMigrationController;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsPartyDraft;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.vo.CtmsMigrationClaimVo;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartyDraftMapper;
import com.ruoyi.ctms.support.ContractRules;
import com.ruoyi.ctms.support.MigrationRules;
import com.ruoyi.ctms.support.PartnerRules;
import com.ruoyi.common.exception.ServiceException;

/**
 * <p> {@link CtmsMigrationServiceImpl} 的业务口径单测（2.0 B3 任务 7.1~7.2）。 </p>
 *
 * <p> <b>不依赖 Spring、不依赖数据库、不依赖真实时钟</b>：合同与草案两个 Mapper 都是本类内的内存桩，
 * 通过反射注入 {@code @Autowired} 字段；"当前时间 / 当前用户"由 {@link TestMigrationService} 子类固定。 </p>
 *
 * <p> 规格 {@code ctms/contract-migration} 的两组场景逐条对应一个用例： </p>
 * <ul>
 *   <li> 「历史甲乙方文本扫描生成待认领草案」四条：采购按乙方聚合 5 → 1 条、销售按甲方聚合 3 → 1 条、
 *        不参与映射的类型跳过、扫描前后合同字段逐字段一致； </li>
 *   <li> 「草案刷新与幂等」三条：重复扫描不重复建草案（行数与 create_time 不变、未变化行 0 次 update）、
 *        已认领草案在出现新未绑定合同时回到待认领并清空 matched_id、已忽略草案不被自动复活
 *        （含"忽略行连计数也不刷新"这条自裁决口径，用 update 次数锁死）。 </li>
 * </ul>
 *
 * <p> 另外补了三类"防退化"用例： </p>
 * <ul>
 *   <li> 聚合口径（同方向同文本合并 / 不同方向与不同文本各自独立）； </li>
 *   <li> 唯一索引口径（{@code uk_party_draft} 落在 ai_ci 上，大小写不同是同一个键）； </li>
 *   <li> 真源对照（草案表的 13 列与 DDL 逐列一致、控制器权限点全部来自菜单 SQL）。 </li>
 * </ul>
 *
 * <p> ⚠ 后两类刻意<b>解析真实的 DDL 与菜单 SQL</b>，而不是在本文件里再抄一份清单：
 * 抄一份只能证明"我抄对了"，解析真源才能证明"实现与真源没分叉"（口径分散是本仓库的既有教训）。 </p>
 *
 * @author 二开
 */
public class CtmsMigrationServiceImplTest
{
    /** 采购合同的乙方文本（规格场景里的「某某阀门有限公司」）。 */
    private static final String SUPPLIER_TEXT = "某某阀门有限公司";

    /** 销售合同的甲方文本（规格场景里的「某某集团」）。 */
    private static final String CUSTOMER_TEXT = "某某集团";

    /** 草案表 DDL 所在文件（仓库相对路径，从工作目录逐级向上探测）。 */
    private static final String DDL_PATH = "ruoyi-vue-oa-master/sql/二开-合同台账.sql";

    /** 草案 Mapper XML（仓库相对路径）。 */
    private static final String DRAFT_XML_PATH =
            "ruoyi-vue-oa-master/ruoyi-ctms/src/main/resources/mapper/ctms/CtmsPartyDraftMapper.xml";

    /** 权限点真源：菜单 SQL（仓库相对路径）。 */
    private static final String MENU_SQL_PATH = "ruoyi-vue-oa-master/sql/二开-合同台账-菜单.sql";

    /** 权限点文本（与 9.1 双向核对用的正则同口径：允许连字符，如 {@code ctms:contract-item:list}）。 */
    private static final Pattern PERM_PATTERN = Pattern.compile("ctms:[a-z-]+:[a-z-]+");

    /** 13 列的真源清单（DDL 与实体、resultMap 都必须与它逐列一致）。 */
    private static final List<String> DRAFT_COLUMNS = Arrays.asList(
            "id", "party_type", "raw_name", "contract_count", "status", "matched_id", "remark",
            "create_time", "update_time", "create_id", "create_by", "update_id", "update_by");

    /** 必须保持可空的 6 列（含 create_id / create_by 的"扫描任务可能无登录用户"语义）。 */
    private static final List<String> NULLABLE_COLUMNS = Arrays.asList(
            "matched_id", "remark", "create_id", "create_by", "update_id", "update_by");

    /** 必须非空的 7 列。 */
    private static final List<String> NOT_NULL_COLUMNS = Arrays.asList(
            "id", "party_type", "raw_name", "contract_count", "status", "create_time", "update_time");

    private final StubContractMapper contractMapper = new StubContractMapper();

    private final StubPartyDraftMapper draftMapper = new StubPartyDraftMapper();

    private final StubPartnerService partnerService = new StubPartnerService();

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final TestMigrationService service = new TestMigrationService();

    @Before
    public void setUp()
    {
        inject(service, "contractMapper", contractMapper);
        inject(service, "partyDraftMapper", draftMapper);
        inject(service, "partnerService", partnerService);
        inject(service, "changeLogMapper", changeLogMapper);
    }

    /* ==================== 第 7 组 7.3：认领与批量绑定 ==================== */

    /**
     * 场景①「认领新建档案并批量绑定」+ 场景⑤「供应商简称以原始名称兜底」：
     * 4 份历史采购合同的供应商引用被写入新档案，每份合同各新增 1 条含操作人的变更历史；
     * 未提供简称时 short_name 落库为草案 raw_name。
     */
    @Test
    public void 认领新建供应商档案并批量绑定四份合同()
    {
        for (int i = 1; i <= 4; i++)
        {
            seed("CLAIM-S-" + i, "PUR", "我方智澈公司", SUPPLIER_TEXT, null, null);
        }
        service.scanPartyDrafts();
        String draftId = draftMapper.store.values().iterator().next().getId();

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-MIG-0001");
        // 刻意不提供 name / shortName：两者都应以草案 raw_name 兜底
        CtmsPartyDraft claimed = service.claimPartyDraft(draftId, request);

        assertEquals("新建档案只 1 次", 1, partnerService.supplierInsertCount);
        assertEquals("绑定已有档案的插入次数为 0", 0, partnerService.customerInsertCount);
        CtmsSupplier created = partnerService.lastSupplier;
        assertNotNull("档案ID由第 3 组服务生成", created.getId());
        assertEquals("名称以草案 raw_name 兜底", SUPPLIER_TEXT, created.getName());
        assertEquals("简称以草案 raw_name 兜底（规格场景⑤）", SUPPLIER_TEXT, created.getShortName());
        assertEquals("编码按请求体落库（不自动生成）", "SUP-MIG-0001", created.getCode());

        assertEquals(MigrationRules.STATUS_CLAIMED, claimed.getStatus());
        assertEquals("matched_id 写入新档案", created.getId(), claimed.getMatchedId());
        assertEquals("计数刷新为本次实绑数", Integer.valueOf(4), claimed.getContractCount());

        for (int i = 1; i <= 4; i++)
        {
            CtmsContract row = contractMapper.store.get("CLAIM-S-" + i);
            assertEquals("合同供应商引用被写入新档案", created.getId(), row.getSupplierId());
            assertEquals("乙方文本不被改写", SUPPLIER_TEXT, row.getPartyB());
        }
        List<CtmsChangeLog> logs = changeLogsOfSupplierRef(created.getId());
        assertEquals("每份被更新的合同各新增 1 条变更历史", 4, logs.size());
        for (CtmsChangeLog log : logs)
        {
            assertEquals("字段名口径", ContractRules.FIELD_SUPPLIER, log.getFieldName());
            assertNotNull("必须含操作人（用户ID）", log.getOperatorId());
            assertEquals("操作人姓名快照", "张三", log.getOperatorName());
            assertEquals("来源为自动（迁移认领动作）", CtmsChangeLog.SOURCE_AUTO, log.getSource());
            assertEquals("对象类型", ContractRules.OBJECT_TYPE_CONTRACT, log.getObjectType());
            assertNotNull("对象标识", log.getObjectId());
            assertEquals("新值是档案ID（与整单编辑同口径）", created.getId(), log.getNewValue());
        }
    }

    /**
     * 场景②「认领绑定已有档案」：客户引用指向该已存在档案，且不新建任何重复档案。
     */
    @Test
    public void 认领绑定已有客户档案不新建重复档案()
    {
        CtmsCustomer existing = new CtmsCustomer();
        existing.setId("C-EXIST-1");
        existing.setCode("CUS-EXIST");
        existing.setName("百脉泉阀门有限公司");
        partnerService.customers.put(existing.getId(), existing);

        for (int i = 1; i <= 3; i++)
        {
            seed("CLAIM-C-" + i, "SAL", CUSTOMER_TEXT, "我方乙方", null, null);
        }
        service.scanPartyDrafts();
        String draftId = draftMapper.store.values().iterator().next().getId();

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setPartyId(existing.getId());
        CtmsPartyDraft claimed = service.claimPartyDraft(draftId, request);

        assertEquals("绑定已有档案：客户插入次数必须为 0", 0, partnerService.customerInsertCount);
        assertEquals("供应商插入次数也必须为 0", 0, partnerService.supplierInsertCount);
        assertEquals(existing.getId(), claimed.getMatchedId());
        for (int i = 1; i <= 3; i++)
        {
            assertEquals("客户引用指向已存在档案", existing.getId(),
                    contractMapper.store.get("CLAIM-C-" + i).getCustomerId());
        }
        assertEquals("3 条客户档案变更历史", 3, changeLogsOfCustomerRef(existing.getId()).size());
        assertEquals("字段名用「客户档案」", ContractRules.FIELD_CUSTOMER,
                changeLogsOfCustomerRef(existing.getId()).get(0).getFieldName());
    }

    /**
     * 场景③「已认领草案不可重复认领」：再次提交被拒且提示含「已认领」关键字。
     */
    @Test
    public void 已认领草案不可重复认领()
    {
        seed("AGAIN-1", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        String draftId = draftMapper.store.values().iterator().next().getId();
        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-AGAIN");
        service.claimPartyDraft(draftId, request);

        assertRejected("已认领", draftId, request);
        assertRejected("已认领", draftId, request);
        assertEquals("拒绝时不得重复建档案", 1, partnerService.supplierInsertCount);
        assertEquals("拒绝时不得改写草案状态", MigrationRules.STATUS_CLAIMED,
                draftMapper.store.get(draftId).getStatus());
    }

    /**
     * 场景④「已忽略草案可重新认领」：认领成功、status=claimed、匹配合同完成绑定。
     */
    @Test
    public void 已忽略草案可重新认领()
    {
        seed("IGN-1", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("IGN-2", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        String draftId = draftMapper.store.values().iterator().next().getId();

        CtmsPartyDraft ignored = service.ignorePartyDraft(draftId, "先不做这条");
        assertEquals(MigrationRules.STATUS_IGNORED, ignored.getStatus());
        assertEquals("忽略原因写入 remark", "先不做这条", ignored.getRemark());

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-IGNORED");
        CtmsPartyDraft claimed = service.claimPartyDraft(draftId, request);
        assertEquals("已忽略草案可重新认领", MigrationRules.STATUS_CLAIMED, claimed.getStatus());
        assertEquals(Integer.valueOf(2), claimed.getContractCount());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("IGN-1").getSupplierId());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("IGN-2").getSupplierId());
        assertEquals("2 条变更历史", 2, changeLogsOfSupplierRef(claimed.getMatchedId()).size());
    }

    /**
     * 附加契约：批量绑定只更新「该方向档案引用为空且该方向文本等于 raw_name」的合同，
     * 不覆盖已绑定别的档案的合同，也不碰文本不同的合同。
     */
    @Test
    public void 批量绑定不覆盖已绑定别的档案的合同()
    {
        seed("BIND-1", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("BIND-2", "PUR", "", SUPPLIER_TEXT, null, null);
        // 已绑定别的供应商：不在候选集合里
        seed("BIND-3", "PUR", "", SUPPLIER_TEXT, null, "S-OTHER");
        // 文本不同：不在候选集合里
        seed("BIND-4", "PUR", "", "另一家供应商", null, null);
        service.scanPartyDrafts();
        assertEquals("只有 2 份合同进草案计数", Integer.valueOf(2),
                draftMapper.store.values().iterator().next().getContractCount());

        String draftId = draftMapper.store.values().iterator().next().getId();
        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-BIND");
        CtmsPartyDraft claimed = service.claimPartyDraft(draftId, request);

        assertEquals("只绑定 2 份", Integer.valueOf(2), claimed.getContractCount());
        assertEquals("2 条历史", 2, changeLogsOfSupplierRef(claimed.getMatchedId()).size());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("BIND-1").getSupplierId());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("BIND-2").getSupplierId());
        assertEquals("已绑定别的档案的合同不被覆盖", "S-OTHER",
                contractMapper.store.get("BIND-3").getSupplierId());
        assertNull("文本不同的合同不被绑定", contractMapper.store.get("BIND-4").getSupplierId());
        assertEquals("文本也不被改写", "另一家供应商", contractMapper.store.get("BIND-4").getPartyB());
    }

    /**
     * 附加契约：认领方法带 {@code @Transactional(rollbackFor = Exception.class)}（部分失败不留半成品）。
     */
    @Test
    public void 认领方法带事务回滚()
    {
        assertTransactional("claimPartyDraft", String.class, CtmsMigrationClaimVo.class);
        assertTransactional("ignorePartyDraft", String.class, String.class);
    }

    /**
     * 附加契约：新建模式的编码不兜底、忽略守卫（已认领不能忽略、重复忽略幂等）。
     */
    @Test
    public void 认领与忽略的守卫齐全()
    {
        seed("GUARD-1", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        final String draftId = draftMapper.store.values().iterator().next().getId();

        final CtmsMigrationClaimVo noCode = new CtmsMigrationClaimVo();
        noCode.setName("某某阀门有限公司");
        assertRejectedMessage("新建档案必须提供编码", new Runnable()
        {
            @Override
            public void run()
            {
                service.claimPartyDraft(draftId, noCode);
            }
        });
        assertEquals("被拒后不建档案", 0, partnerService.supplierInsertCount);

        // 重复忽略幂等
        service.ignorePartyDraft(draftId, "第一次");
        CtmsPartyDraft again = service.ignorePartyDraft(draftId, "第二次");
        assertEquals(MigrationRules.STATUS_IGNORED, again.getStatus());
        assertEquals("后一次备注覆盖前一次", "第二次", again.getRemark());

        // 认领后不能再忽略（否则状态与事实矛盾）
        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-GUARD");
        service.claimPartyDraft(draftId, request);
        assertRejectedMessage("已认领", new Runnable()
        {
            @Override
            public void run()
            {
                service.ignorePartyDraft(draftId, "已认领后想忽略");
            }
        });
        assertEquals("被拒后状态仍是 claimed", MigrationRules.STATUS_CLAIMED,
                draftMapper.store.get(draftId).getStatus());
    }

    /* ==================== t26 / t16 F1：扫描与认领的文本比较口径统一 ==================== */

    /**
     * <p> F1 回归（<b>ASCII 空格</b>）：同一可见文本的四种写法（无空格 / 前导 / 尾随 / 双侧多空格）
     * 必须聚合成<b>一条</b>草案，认领后<b>四份合同都真的被绑定</b>，且再扫描后草案
     * <b>稳定停在 {@code claimed}</b>（不得回到 pending、不得清 matched_id）。 </p>
     *
     * <p> 这条用例同时是"claimed↔pending 永久往复"的回归守卫：旧实现里扫描写入的是去空格文本、
     * 而候选 SQL 用精确相等，带空格的合同会被扫描计数、认领实绑 0、再扫描又回到 pending。 </p>
     */
    @Test
    public void 首尾空格变体聚合成一条草案且认领后四份都真被绑定()
    {
        final String text = "口径阀门有限公司";
        seed("WS-1", "PUR", "", text, null, null);
        seed("WS-2", "PUR", "", " " + text, null, null);
        seed("WS-3", "PUR", "", text + " ", null, null);
        seed("WS-4", "PUR", "", "   " + text + "   ", null, null);

        service.scanPartyDrafts();
        assertEquals("四种空格写法必须聚合成 1 条草案", 1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals("raw_name 是去首尾空格后的文本", text, draft.getRawName());
        assertEquals("计数=4", Integer.valueOf(4), draft.getContractCount());

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-WS-1");
        CtmsPartyDraft claimed = service.claimPartyDraft(draft.getId(), request);
        assertEquals("必须真的绑定 4 份（不允许「code=200 但实绑 0」）", Integer.valueOf(4),
                claimed.getContractCount());
        assertEquals("4 条变更历史", 4, changeLogsOfSupplierRef(claimed.getMatchedId()).size());
        for (int i = 1; i <= 4; i++)
        {
            assertEquals("WS-" + i + " 的供应商引用必须被写入", claimed.getMatchedId(),
                    contractMapper.store.get("WS-" + i).getSupplierId());
        }

        // 再扫描：4 份都已绑定 → 不再有"未绑定的同文本合同" → 草案必须稳定停在 claimed
        List<CtmsPartyDraft> touched = service.scanPartyDrafts();
        assertEquals("已认领且无残留未绑定合同：本次扫描没有要写的草案", 0, touched.size());
        CtmsPartyDraft after = draftMapper.store.get(draft.getId());
        assertEquals("再扫描后必须稳定停在 claimed（不得回 pending）",
                MigrationRules.STATUS_CLAIMED, after.getStatus());
        assertEquals("matched_id 不得被清空", claimed.getMatchedId(), after.getMatchedId());
        assertEquals("计数不被改写", Integer.valueOf(4), after.getContractCount());
    }

    /**
     * <p> F1 回归（<b>Tab</b>）：Java 侧与 SQL 侧都<b>保留</b>制表符（MySQL 的 {@code TRIM()} 只去 0x20），
     * 所以 {@code \t文本\t} 的合同既能被扫描聚合、也能被认领真正绑定。 </p>
     *
     * <p> 这正是"只改 SQL 的 TRIM()"修不动的那一类：旧实现 Java 用 {@code String.trim()} 去了 tab、
     * SQL 的 TRIM 不去 → 实绑 0 且永久往复。本用例把"两侧都保留"锁死。 </p>
     */
    @Test
    public void Tab包裹的文本两侧同口径且认领后真被绑定()
    {
        final String tab = "\t";
        final String text = tab + "制表阀门" + tab;
        seed("TAB-1", "PUR", "", text, null, null);
        // 空格 + tab 混合：去空格后应与纯 tab 那条同组
        seed("TAB-2", "PUR", "", " " + text + " ", null, null);

        service.scanPartyDrafts();
        assertEquals("纯 tab 与「空格+tab」必须聚合成 1 条草案", 1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals("tab 原样保留在 raw_name 里（两侧都不去）", text, draft.getRawName());
        assertEquals(Integer.valueOf(2), draft.getContractCount());

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-TAB-1");
        CtmsPartyDraft claimed = service.claimPartyDraft(draft.getId(), request);
        assertEquals("tab 包裹的合同也必须被绑定", Integer.valueOf(2), claimed.getContractCount());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("TAB-1").getSupplierId());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("TAB-2").getSupplierId());

        service.scanPartyDrafts();
        assertEquals("再扫描后稳定停在 claimed", MigrationRules.STATUS_CLAIMED,
                draftMapper.store.get(draft.getId()).getStatus());
    }

    /**
     * <p> F1 回归（<b>全角空格</b>）：两侧都保留 U+3000（Java {@code trim()} 与 MySQL {@code TRIM()}
     * 都不处理它），因此口径一致、认领能真正绑定。 </p>
     */
    @Test
    public void 全角空格包裹的文本两侧同口径且认领后真被绑定()
    {
        final String fw = "\u3000";
        final String text = fw + "全角阀门" + fw;
        seed("FW-1", "PUR", "", text, null, null);

        service.scanPartyDrafts();
        assertEquals(1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals("全角空格原样保留", text, draft.getRawName());

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-FW-1");
        CtmsPartyDraft claimed = service.claimPartyDraft(draft.getId(), request);
        assertEquals("全角空格包裹的合同也必须被绑定", Integer.valueOf(1), claimed.getContractCount());
        assertEquals(claimed.getMatchedId(), contractMapper.store.get("FW-1").getSupplierId());

        service.scanPartyDrafts();
        assertEquals("再扫描后稳定停在 claimed", MigrationRules.STATUS_CLAIMED,
                draftMapper.store.get(draft.getId()).getStatus());
    }

    /**
     * <p> 口径不一致时的可诊断路径（"草案计数 &gt; 0 而本次实绑 0"）：认领必须<b>如实</b>把计数落成 0，
     * 而不是把草案自己的旧计数抄回去 —— 这样运维在列表里就能看到"说 3 份、实际 0 份"的异常，
     * 配合实现里的 {@code log.warn} 定位到比较口径。 </p>
     */
    @Test
    public void 候选为空时实绑计数如实落零()
    {
        // 直接构造一条"计数 3"的草案，且库里没有任何合同与它同文本（模拟口径错位的结果形态）
        CtmsPartyDraft draft = new CtmsPartyDraft();
        draft.setId("DRAFT-ORPHAN");
        draft.setPartyType(MigrationRules.DIRECTION_SUPPLIER);
        draft.setRawName("库里不存在的文本");
        draft.setContractCount(Integer.valueOf(3));
        draft.setStatus(MigrationRules.STATUS_PENDING);
        draftMapper.store.put(draft.getId(), draft);

        CtmsMigrationClaimVo request = new CtmsMigrationClaimVo();
        request.setCode("SUP-ORPHAN");
        CtmsPartyDraft claimed = service.claimPartyDraft(draft.getId(), request);
        assertEquals("实绑 0 就必须落 0（不得抄旧计数）", Integer.valueOf(0), claimed.getContractCount());
        assertEquals("状态仍是 claimed（人工会看到这个异常并排查）",
                MigrationRules.STATUS_CLAIMED, claimed.getStatus());
        assertEquals("0 条变更历史", 0, changeLogsOfSupplierRef(claimed.getMatchedId()).size());
    }

    /* ==================== 场景①：采购合同按乙方聚合 ==================== */

    @Test
    public void 采购合同按乙方聚合成一条待认领草案()
    {
        for (int i = 1; i <= 5; i++)
        {
            // 甲方文本也非空：用来证明"只看映射出来的那一侧"（naive 实现会多出一条客户草案）
            seed("PUR-" + i, "PUR", "我方智澈公司", SUPPLIER_TEXT, null, null);
        }
        List<CtmsPartyDraft> touched = service.scanPartyDrafts();
        assertEquals("5 份同乙方的采购合同只生成 1 条草案", 1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals(MigrationRules.DIRECTION_SUPPLIER, draft.getPartyType());
        assertEquals(SUPPLIER_TEXT, draft.getRawName());
        assertEquals(Integer.valueOf(5), draft.getContractCount());
        assertEquals(MigrationRules.STATUS_PENDING, draft.getStatus());
        assertNull("待认领草案没有已匹配档案", draft.getMatchedId());
        assertNotNull("主键由服务端生成", draft.getId());
        assertEquals("create_id 落库（可空列，服务端给兜底值便于运维追溯）", "100", draft.getCreateId());
        assertEquals("create_by 落库", "zhangsan", draft.getCreateBy());
        assertEquals("本次新建 1 条", 1, touched.size());
        assertNull("映射出来的方向是供应商：不应多出客户方向的草案",
                draftMapper.find(MigrationRules.DIRECTION_CUSTOMER, "我方智澈公司"));
        // 重复扫描：不重复建草案（先查后写 + 唯一索引），数据无变化时无写库动作
        assertEquals("数据无变化时第二次扫描没有要写的草案", 0, service.scanPartyDrafts().size());
        assertEquals("草案总数不变", 1, draftMapper.store.size());
        assertEquals("计数保持 5", Integer.valueOf(5),
                draftMapper.store.values().iterator().next().getContractCount());
        assertEquals("全程只有 1 次 insert", 1, draftMapper.insertCount);
    }

    /* ==================== 场景②：销售合同按甲方聚合 ==================== */

    @Test
    public void 销售合同按甲方聚合成一条待认领草案()
    {
        for (int i = 1; i <= 3; i++)
        {
            seed("SAL-" + i, "SAL", CUSTOMER_TEXT, "我方乙方文本", null, null);
        }
        service.scanPartyDrafts();
        assertEquals(1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals(MigrationRules.DIRECTION_CUSTOMER, draft.getPartyType());
        assertEquals(CUSTOMER_TEXT, draft.getRawName());
        assertEquals(Integer.valueOf(3), draft.getContractCount());
        assertEquals(MigrationRules.STATUS_PENDING, draft.getStatus());
    }

    /* ==================== 场景③：不参与映射的类型被跳过 ==================== */

    @Test
    public void 不参与映射的类型被跳过()
    {
        String[] skippedTypes = { "COO", "LAB", "FIN", "NDA", "OTH", "其他" };
        int index = 0;
        for (String type : skippedTypes)
        {
            seed("SKIP-" + (++index), type, CUSTOMER_TEXT, SUPPLIER_TEXT, null, null);
            assertNull("类型「" + type + "」不参与档案方向映射", MigrationRules.directionOf(type));
        }
        assertTrue("不参与映射的类型不产生草案", service.scanPartyDrafts().isEmpty());
        assertEquals("草案表必须为空", 0, draftMapper.store.size());
        assertNull("类型为空同样不参与映射", MigrationRules.directionOf("  "));
    }

    /* ==================== 场景④：扫描不改动合同 ==================== */

    @Test
    public void 扫描不改动合同的甲乙方与档案引用()
    {
        seed("M-1", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("M-2", "SAL", CUSTOMER_TEXT, "乙方文本", null, null);
        seed("M-3", "PUR", "甲方文本", SUPPLIER_TEXT, null, "S-001");
        seed("M-4", "SAL", CUSTOMER_TEXT, "", "C-001", null);
        seed("M-5", "COO", CUSTOMER_TEXT, SUPPLIER_TEXT, null, null);
        seed("M-6", "PUR", "", "停用合同的乙方", null, null).setDelFlag("1");
        List<String> before = contractSnapshot();
        service.scanPartyDrafts();
        assertEquals("扫描前后每份合同的 party_a/party_b/customer_id/supplier_id/del_flag 必须逐字段一致",
                before, contractSnapshot());
        assertEquals("扫描不得调用任何合同写方法（全程只读合同表）", 0, contractMapper.writeCalls);
        assertEquals("快照必须覆盖全部 6 份合同", 6, contractMapper.store.size());
        assertEquals("3 条未绑定文本各自成条（乙方 1 + 停用的乙方 1 + 甲方 1）", 3, draftMapper.store.size());
    }

    /* ==================== 聚合口径 ==================== */

    @Test
    public void 同一方向同一文本合并而不同方向不同文本各自独立()
    {
        seed("A-1", "PUR", "", "X阀门", null, null);
        seed("A-2", "PUR", "", "X阀门", null, null);
        seed("A-3", "PUR", "", "Y阀门", null, null);
        seed("A-4", "SAL", "X阀门", "", null, null);
        service.scanPartyDrafts();
        assertEquals("同一 (方向, 文本) 只一条；不同文本/不同方向各自独立", 3, draftMapper.store.size());
        assertEquals(Integer.valueOf(2), draftMapper.find("supplier", "X阀门").getContractCount());
        assertEquals(Integer.valueOf(1), draftMapper.find("supplier", "Y阀门").getContractCount());
        assertEquals("同样的文本在另一个方向是另一条草案", Integer.valueOf(1),
                draftMapper.find("customer", "X阀门").getContractCount());
    }

    @Test
    public void 同一文本的大小写差异按唯一索引进同一条草案()
    {
        seed("B-1", "PUR", "", "Acme Valve Co", null, null);
        seed("B-2", "PUR", "", "acme valve co", null, null);
        service.scanPartyDrafts();
        assertEquals("uk_party_draft 落在 utf8mb4_0900_ai_ci 上：大小写不同是同一个键，不能建第二行",
                1, draftMapper.store.size());
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals(Integer.valueOf(2), draft.getContractCount());
        assertEquals("展示用的原始文本保留首次出现的写法", "Acme Valve Co", draft.getRawName());
        assertEquals("两个文本必须算出同一个分组键",
                MigrationRules.rawNameKey("Acme Valve Co"), MigrationRules.rawNameKey("acme valve co"));
        assertEquals(1, draftMapper.insertCount);
    }

    @Test
    public void 已绑定该方向档案的合同不参与扫描()
    {
        seed("C-1", "PUR", "", SUPPLIER_TEXT, null, "S-001");
        seed("C-2", "SAL", CUSTOMER_TEXT, "", "C-001", null);
        assertTrue("已绑定该方向档案的合同不进草案", service.scanPartyDrafts().isEmpty());
        assertEquals(0, draftMapper.store.size());
        // 只看本方向的引用列：采购合同误填了客户引用，不影响它按乙方文本进供应商方向的草案
        seed("C-3", "PUR", "", "跨方向误填", "C-001", null);
        service.scanPartyDrafts();
        assertEquals(1, draftMapper.store.size());
        assertEquals("跨方向误填", draftMapper.store.values().iterator().next().getRawName());
    }

    @Test
    public void 重复扫描只刷新计数不新建草案()
    {
        for (int i = 1; i <= 3; i++)
        {
            seed("D-1-" + i, "PUR", "", SUPPLIER_TEXT, null, null);
        }
        service.scanPartyDrafts();
        assertEquals(1, draftMapper.store.size());
        assertEquals(Integer.valueOf(3), draftMapper.store.values().iterator().next().getContractCount());
        Date createTime = draftMapper.store.values().iterator().next().getCreateTime();
        String createId = draftMapper.store.values().iterator().next().getCreateId();
        seed("D-2-1", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("D-2-2", "PUR", "", SUPPLIER_TEXT, null, null);
        List<CtmsPartyDraft> touched = service.scanPartyDrafts();
        assertEquals("第二次扫描不新建第二行", 1, draftMapper.store.size());
        assertEquals(Integer.valueOf(5), draftMapper.store.values().iterator().next().getContractCount());
        assertEquals("刷新走 update", 1, touched.size());
        assertEquals("两次扫描合计只 1 次 insert", 1, draftMapper.insertCount);
        assertEquals("刷新计数会写审计列", "zhangsan",
                draftMapper.store.values().iterator().next().getUpdateBy());
        assertEquals("幂等刷新不得改 create_time（「行还在、创建痕迹不变」）", createTime,
                draftMapper.store.values().iterator().next().getCreateTime());
        assertEquals("幂等刷新不得改 create_id", createId,
                draftMapper.store.values().iterator().next().getCreateId());
    }

    /* ==================== 任务 7.2：草案刷新与幂等 ==================== */

    @Test
    public void 重复扫描对未变化行不产生无意义更新()
    {
        for (int i = 1; i <= 5; i++)
        {
            seed("F-1-" + i, "PUR", "", SUPPLIER_TEXT, null, null);
        }
        service.scanPartyDrafts();
        assertEquals(1, draftMapper.insertCount);
        Date createTime = draftMapper.store.values().iterator().next().getCreateTime();
        // 数据没变：第二次扫描不得写库（行数、create_time、update 次数都不动）
        assertTrue("数据无变化时第二次扫描没有要写的草案", service.scanPartyDrafts().isEmpty());
        assertEquals("未变化行不得产生 update", 0, draftMapper.updateCount);
        assertEquals("草案行数不变", 1, draftMapper.store.size());
        assertEquals("create_time 不变", createTime,
                draftMapper.store.values().iterator().next().getCreateTime());
        // 第三次扫描同样安静
        assertTrue(service.scanPartyDrafts().isEmpty());
        assertEquals("再多扫一次仍然 0 次 update", 0, draftMapper.updateCount);
        assertEquals("也仍然只有 1 次 insert（先查后写，不是插入撞唯一键）", 1, draftMapper.insertCount);
    }

    @Test
    public void 认领后出现新合同则回到待认领()
    {
        for (int i = 1; i <= 3; i++)
        {
            seed("G-1-" + i, "PUR", "", SUPPLIER_TEXT, null, null);
        }
        service.scanPartyDrafts();
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        Date createTime = draft.getCreateTime();
        draftMapper.markClaimed(draft.getId(), "S-001");
        assertEquals(MigrationRules.STATUS_CLAIMED, draftMapper.store.get(draft.getId()).getStatus());
        // 又来了一份同文本、未绑定供应商档案的采购合同 → 再次扫描
        seed("G-2-1", "PUR", "", SUPPLIER_TEXT, null, null);
        List<CtmsPartyDraft> touched = service.scanPartyDrafts();
        CtmsPartyDraft after = draftMapper.store.get(draft.getId());
        assertEquals("已认领草案必须回到待认领", MigrationRules.STATUS_PENDING, after.getStatus());
        assertNull("必须清空已匹配档案（否则会显示「已认领」却还留在待认领列表）", after.getMatchedId());
        assertEquals("计数刷新为 4", Integer.valueOf(4), after.getContractCount());
        assertEquals("create_time 不变", createTime, after.getCreateTime());
        assertEquals("只写 1 次", 1, draftMapper.updateCount);
        assertEquals("不得新建第二行", 1, draftMapper.store.size());
        assertEquals("回归后的草案在待认领列表里可见", 1, touched.size());
        // 回到待认领后再扫描（数据无变化）→ 又安静下来
        assertTrue(service.scanPartyDrafts().isEmpty());
        assertEquals("状态已稳定，不再重复写", 1, draftMapper.updateCount);
    }

    @Test
    public void 已忽略的草案不被自动复活()
    {
        seed("H-1", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("H-2", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        draftMapper.markIgnored(draft.getId());
        // 又来了 2 份同文本、未绑定档案的合同
        seed("H-3", "PUR", "", SUPPLIER_TEXT, null, null);
        seed("H-4", "PUR", "", SUPPLIER_TEXT, null, null);
        List<CtmsPartyDraft> touched = service.scanPartyDrafts();
        CtmsPartyDraft after = draftMapper.store.get(draft.getId());
        assertEquals("已忽略的草案绝不被自动改回待认领", MigrationRules.STATUS_IGNORED, after.getStatus());
        assertEquals("裁决：忽略行连计数也不刷新（整行不动）", Integer.valueOf(2), after.getContractCount());
        assertNull("忽略不等于认领，matched_id 仍为空", after.getMatchedId());
        assertEquals("忽略行不产生 update", 0, draftMapper.updateCount);
        assertEquals("忽略行不出现在本次扫描的返回值里", 0, touched.size());
        assertEquals("也不新建第二行", 1, draftMapper.store.size());
        // 但"忽略"是暂缓不是否决：仍然允许重新认领（状态判定入口放行）
        CtmsPartyDraft claimable = service.requireClaimable(draft.getId());
        assertEquals(MigrationRules.STATUS_IGNORED, claimable.getStatus());
    }

    @Test
    public void 认领状态机入口拒绝已认领并放行待认领()
    {
        seed("I-1", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        CtmsPartyDraft draft = draftMapper.store.values().iterator().next();
        assertEquals("待认领可认领", draft.getId(), service.requireClaimable(draft.getId()).getId());
        draftMapper.markClaimed(draft.getId(), "S-001");
        assertRejected("已认领", new Runnable()
        {
            @Override
            public void run()
            {
                service.requireClaimable(draft.getId());
            }
        });
        assertRejected("草案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.requireClaimable("NOT-EXIST");
            }
        });
        assertRejected("草案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.requireClaimable("   ");
            }
        });
    }

    @Test
    public void 未知状态的草案让扫描快速失败()
    {
        seed("J-1", "PUR", "", SUPPLIER_TEXT, null, null);
        service.scanPartyDrafts();
        String id = draftMapper.store.values().iterator().next().getId();
        draftMapper.breakStatus(id, "done");
        assertRejected("草案状态不合法", new Runnable()
        {
            @Override
            public void run()
            {
                service.scanPartyDrafts();
            }
        });
        // 脏数据行保持原样（不写库），修回合法状态后扫描恢复正常
        assertEquals("done", draftMapper.store.get(id).getStatus());
        assertEquals("失败前不得写入任何草案变更", 0, draftMapper.updateCount);
        draftMapper.breakStatus(id, MigrationRules.STATUS_CLAIMED);
        assertEquals("修成 claimed 后按「回归待认领」处理", 1, service.scanPartyDrafts().size());
        assertEquals(MigrationRules.STATUS_PENDING, draftMapper.store.get(id).getStatus());
    }

    @Test
    public void 草案列表按方向与状态过滤且all表示全部()
    {
        seed("E-1", "PUR", "", "过滤甲", null, null);
        seed("E-2", "SAL", "过滤乙", "", null, null);
        service.scanPartyDrafts();
        CtmsPartyDraft all = new CtmsPartyDraft();
        all.setStatus("all");
        assertEquals("status=all 在服务层归一为 null（不能落进 SQL 当字面量）", 2,
                service.selectDraftList(all).size());
        CtmsPartyDraft byType = new CtmsPartyDraft();
        byType.setPartyType("supplier");
        assertEquals(1, service.selectDraftList(byType).size());
        assertEquals("过滤甲", service.selectDraftList(byType).get(0).getRawName());
        CtmsPartyDraft pending = new CtmsPartyDraft();
        pending.setStatus("pending");
        assertEquals(2, service.selectDraftList(pending).size());
        CtmsPartyDraft claimed = new CtmsPartyDraft();
        claimed.setStatus("claimed");
        assertTrue("尚未认领，claimed 状态应为空", service.selectDraftList(claimed).isEmpty());
        assertEquals("入参为 null 时返回全部", 2, service.selectDraftList(null).size());
    }

    /* ==================== 真源对照：13 列映射 ==================== */

    @Test
    public void 草案表十三列与DDL逐列一致()
    {
        String ddl = read(repoFile(DDL_PATH));
        Map<String, String> declared = ddlColumns(ddl, "t_ctms_party_draft");
        assertEquals("DDL 列数必须是 13", 13, declared.keySet().size());
        assertEquals("DDL 列集合", new LinkedHashSet<>(DRAFT_COLUMNS), declared.keySet());
        for (String column : NULLABLE_COLUMNS)
        {
            assertFalse("列 " + column + " 必须保持可空（DDL 不得写 NOT NULL，含 create_id/create_by 的可空语义）",
                    declared.get(column).contains("NOT NULL"));
        }
        for (String column : NOT_NULL_COLUMNS)
        {
            assertTrue("列 " + column + " 必须非空", declared.get(column).contains("NOT NULL"));
        }
        assertTrue("必须存在幂等键 uk_party_draft(party_type, raw_name)",
                ddl.contains("UNIQUE KEY `uk_party_draft` (`party_type`,`raw_name`)"));

        // resultMap 的列集合（含 id/result 两种标签）必须与 DDL 完全一致
        String xml = read(repoFile(DRAFT_XML_PATH));
        Set<String> mapped = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("column=\"([a-z_]+)\"").matcher(resultMapBlock(xml));
        while (matcher.find())
        {
            mapped.add(matcher.group(1));
        }
        assertEquals("Mapper XML 的 resultMap 列集合必须与 DDL 逐列一致（多一列或少一列都要红）",
                declared.keySet(), mapped);

        // 实体字段（本类声明的 + BaseEntity 提供的 5 列）也必须与 DDL 一致
        Set<String> entity = new LinkedHashSet<>();
        for (Field field : CtmsPartyDraft.class.getDeclaredFields())
        {
            if (!Modifier.isStatic(field.getModifiers()))
            {
                entity.add(camelToSnake(field.getName()));
            }
        }
        for (String baseField : Arrays.asList("createBy", "createTime", "updateBy", "updateTime", "remark"))
        {
            entity.add(camelToSnake(baseField));
        }
        assertEquals("实体字段必须与 DDL 逐列一致", declared.keySet(), entity);

        // insert 语句必须覆盖全部"非空且无默认值"的列（否则真库插入报 1364）
        String insert = insertBlock(xml);
        for (String column : Arrays.asList("id", "party_type", "raw_name", "contract_count", "status"))
        {
            assertTrue("insert 语句必须写入列 " + column, insert.contains(column));
        }
        assertFalse("update 语句不得改写幂等键 party_type", updateBlock(xml).contains("party_type"));
        assertFalse("update 语句不得改写幂等键 raw_name", updateBlock(xml).contains("raw_name"));
    }

    /* ==================== 真源对照：控制器权限点 ==================== */

    @Test
    public void 控制器权限点全部来自菜单SQL且未新增()
    {
        Set<String> controllerPerms = new TreeSet<>();
        boolean scanEndpointUsesScanPerm = false;
        for (Method method : CtmsMigrationController.class.getDeclaredMethods())
        {
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            if (preAuthorize != null)
            {
                controllerPerms.addAll(permsIn(preAuthorize.value()));
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null && Arrays.asList(post.value()).contains("/scan"))
            {
                scanEndpointUsesScanPerm = preAuthorize != null
                        && preAuthorize.value().contains("ctms:migration:scan");
            }
        }
        assertEquals("控制器必须用 POST /scan 且挂在 ctms:migration:scan 上（草案列表用 ctms:migration:list，"
                        + "认领/忽略分别用 claim/ignore —— 这三个是 9.1 权限点闭合的唯一剩余差异点）",
                new TreeSet<>(Arrays.asList("ctms:migration:list", "ctms:migration:scan",
                        "ctms:migration:claim", "ctms:migration:ignore")), controllerPerms);
        assertTrue("扫描端点必须存在且使用 ctms:migration:scan", scanEndpointUsesScanPerm);

        Set<String> menuPerms = permsIn(read(repoFile(MENU_SQL_PATH)));
        assertEquals("菜单 SQL 的 ctms:* 权限点数量应与第 6 组交付口径一致（26 个）", 26, menuPerms.size());
        assertTrue("控制器用到的权限点必须全部已在菜单 SQL 里（未新增权限点）",
                menuPerms.containsAll(controllerPerms));
    }

    /* ==================== 夹具与工具 ==================== */

    /**
     * 直接往桩库塞一份合同（扫描只读它，写路径由桩的 writeCalls 计数看守）。
     *
     * @param id         主键
     * @param type       合同类型（类型码）
     * @param partyA     甲方文本
     * @param partyB     乙方文本
     * @param customerId 客户档案ID
     * @param supplierId 供应商档案ID
     * @return 合同（可继续改字段，如 delFlag）
     */
    private CtmsContract seed(String id, String type, String partyA, String partyB,
                             String customerId, String supplierId)
    {
        CtmsContract contract = new CtmsContract();
        contract.setId(id);
        contract.setContractNo("NO-" + id);
        contract.setName("合同 " + id);
        contract.setType(type);
        contract.setPartyA(partyA);
        contract.setPartyB(partyB);
        contract.setCustomerId(customerId);
        contract.setSupplierId(supplierId);
        contract.setDelFlag("0");
        contractMapper.store.put(id, contract);
        return contract;
    }

    /**
     * 全部合同的甲乙方文本与档案引用快照（扫描前的"取证"）。
     *
     * @return 每份合同一行 "id|partyA|partyB|customerId|supplierId|delFlag"
     */
    private List<String> contractSnapshot()
    {
        List<String> rows = new ArrayList<>();
        for (CtmsContract row : contractMapper.store.values())
        {
            rows.add(row.getId() + "|" + row.getPartyA() + "|" + row.getPartyB() + "|"
                    + row.getCustomerId() + "|" + row.getSupplierId() + "|" + row.getDelFlag());
        }
        return rows;
    }

    /**
     * 断言某段逻辑被拒绝并给出指定文案。
     *
     * @param expectedMessage 期望文案片段
     * @param runnable        待执行逻辑
     */
    private static void assertRejected(String expectedMessage, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒绝并提示：" + expectedMessage);
        }
        catch (ServiceException e)
        {
            String message = e.getMessage();
            assertTrue("提示应包含「" + expectedMessage + "」，实际为「" + message + "」",
                    message != null && message.contains(expectedMessage));
        }
    }

    /**
     * 反射注入服务里的 {@code @Autowired} 字段。
     *
     * @param target 目标对象
     * @param name   字段名
     * @param value  桩
     */
    private static void inject(Object target, String name, Object value)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                // 字段声明在父类（TestMigrationService 继承的服务实现）→ 继续往上找
                type = type.getSuperclass();
            }
            catch (Exception e)
            {
                throw new IllegalStateException("注入字段失败：" + name, e);
            }
        }
        throw new IllegalStateException("找不到可注入的字段：" + name);
    }

    /**
     * 从工作目录逐级向上找仓库内的文件。
     *
     * @param relativePath 仓库相对路径（POSIX 分隔符）
     * @return 文件
     */
    private static File repoFile(String relativePath)
    {
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null)
        {
            File candidate = new File(dir, relativePath);
            if (candidate.isFile())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("找不到文件：" + relativePath + "（从 " + System.getProperty("user.dir") + " 逐级向上探测失败）");
        return null;
    }

    /**
     * 读文本文件（无 BOM UTF-8）。
     *
     * @param file 文件
     * @return 内容
     */
    private static String read(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("读文件失败：" + file, e);
        }
    }

    /**
     * 解析 {@code CREATE TABLE} 的列定义（列名 → 该行的剩余文本，含类型、可空与默认值）。
     *
     * @param ddl   建表脚本全文
     * @param table 表名
     * @return 列定义（保持脚本中的顺序）
     */
    private static Map<String, String> ddlColumns(String ddl, String table)
    {
        Map<String, String> columns = new LinkedHashMap<>();
        int start = ddl.indexOf("CREATE TABLE `" + table + "`");
        if (start < 0)
        {
            fail("建表脚本里找不到表 " + table);
        }
        int body = ddl.indexOf('(', start);
        int end = ddl.indexOf(") ENGINE", body);
        if (body < 0 || end < body)
        {
            fail("建表脚本里找不到 " + table + " 的建表体");
        }
        for (String raw : ddl.substring(body + 1, end).split("\n"))
        {
            String line = raw.trim();
            if (!line.startsWith("`"))
            {
                continue;
            }
            int close = line.indexOf('`', 1);
            columns.put(line.substring(1, close), line.substring(close + 1).trim());
        }
        return columns;
    }

    /**
     * 取 XML 里第一个 resultMap 的内容块。
     *
     * @param xml Mapper XML 全文
     * @return resultMap 块
     */
    private static String resultMapBlock(String xml)
    {
        int start = xml.indexOf("<resultMap");
        int end = xml.indexOf("</resultMap>");
        if (start < 0 || end < start)
        {
            fail("Mapper XML 里找不到 resultMap");
        }
        return xml.substring(start, end);
    }

    /**
     * 取 XML 里 insert 语句块。
     *
     * @param xml Mapper XML 全文
     * @return insert 块
     */
    private static String insertBlock(String xml)
    {
        return statementBlock(xml, "<insert", "</insert>");
    }

    /**
     * 取 XML 里 update 语句块。
     *
     * @param xml Mapper XML 全文
     * @return update 块
     */
    private static String updateBlock(String xml)
    {
        return statementBlock(xml, "<update", "</update>");
    }

    /**
     * 取 XML 里某类语句块（本文件只有一个 update，取第一段即可）。
     *
     * @param xml   Mapper XML 全文
     * @param open  开标签
     * @param close 闭标签
     * @return 语句块
     */
    private static String statementBlock(String xml, String open, String close)
    {
        int start = xml.indexOf(open);
        int end = xml.indexOf(close);
        if (start < 0 || end < start)
        {
            fail("Mapper XML 里找不到 " + open + " 语句");
        }
        return xml.substring(start, end);
    }

    /**
     * 抽取文本里的 {@code ctms:*} 权限点。
     *
     * @param text 文本（菜单 SQL 或 {@code @PreAuthorize} 的表达式）
     * @return 权限点集合（去重）
     */
    private static Set<String> permsIn(String text)
    {
        Set<String> perms = new TreeSet<>();
        Matcher matcher = PERM_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find())
        {
            perms.add(matcher.group());
        }
        return perms;
    }

    /**
     * 驼峰 → 下划线（实体字段名与 DDL 列名的对照口径）。
     *
     * @param camel 驼峰名
     * @return 下划线名
     */
    private static String camelToSnake(String camel)
    {
        StringBuilder snake = new StringBuilder();
        for (int i = 0; i < camel.length(); i++)
        {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c))
            {
                snake.append('_').append(Character.toLowerCase(c));
            }
            else
            {
                snake.append(c);
            }
        }
        return snake.toString();
    }

    /* ==================== 可注入点的服务子类 ==================== */

    /**
     * 把"当前时间 / 当前用户"换成固定值的服务子类（生产实现里是 protected 方法）。
     */
    private class TestMigrationService extends CtmsMigrationServiceImpl
    {
        private final Date fixedNow = new Date(1767225600000L);

        private final String userId = "100";

        private final String username = "zhangsan";

        private final String nickName = "张三";

        @Override
        protected Date now()
        {
            return fixedNow;
        }

        @Override
        protected String currentUserId()
        {
            return userId;
        }

        @Override
        protected String currentUsername()
        {
            return username;
        }

        @Override
        protected String currentNickName()
        {
            // 变更历史的 operator_name 快照（认领要"每份合同一条含操作人的历史"）
            return nickName;
        }
    }

    /* ==================== 内存桩 ==================== */

    /**
     * 合同桩：只实现扫描用到的 {@code selectContractList}（含 {@code includeDeleted} 语义），
     * 其余方法给空值；<b>任何写方法都只计数、不改数据</b> —— 计数为 0 就是"扫描只读合同"的证据。
     */
    private static class StubContractMapper implements CtmsContractMapper
    {
        private final Map<String, CtmsContract> store = new LinkedHashMap<>();

        /** 写方法被调用的次数（扫描必须为 0）。 */
        private int writeCalls;

        /** 认领批量回填的调用次数（任务 7.3）。 */
        private int bindCount;

        @Override
        public List<CtmsContract> selectContractList(CtmsContract query)
        {
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                // 与 CtmsContractMapper.xml 同款：默认口径排除已停用，只有 includeDeleted="1" 才放开
                if (query != null && !"1".equals(query.getIncludeDeleted()) && "1".equals(row.getDelFlag()))
                {
                    continue;
                }
                result.add(copy(row));
            }
            return result;
        }

        @Override
        public CtmsContract selectContractById(String id)
        {
            CtmsContract row = store.get(id);
            return row == null ? null : copy(row);
        }

        @Override
        public CtmsContract selectContractByNo(String contractNo)
        {
            for (CtmsContract row : store.values())
            {
                if (contractNo != null && contractNo.equals(row.getContractNo()))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public int insertContract(CtmsContract contract)
        {
            writeCalls++;
            return 0;
        }

        @Override
        public int updateContract(CtmsContract contract)
        {
            writeCalls++;
            return 0;
        }

        @Override
        public int updateContractDelFlag(CtmsContract contract)
        {
            writeCalls++;
            return 0;
        }

        @Override
        public int restoreContract(String id, String updateId, String updateBy)
        {
            writeCalls++;
            return 0;
        }

        @Override
        public int deleteContractById(String id)
        {
            writeCalls++;
            return 0;
        }

        @Override
        public List<CtmsContract> selectChildren(String parentId)
        {
            return new ArrayList<>();
        }

        @Override
        public int countActiveChildren(String parentId)
        {
            return 0;
        }

        @Override
        public int countContractReferences(String tagId)
        {
            return 0;
        }

        @Override
        public List<CtmsContract> selectWarrantyReminderCandidates(Date windowEnd, String dataScopeSql, int limit)
        {
            return new ArrayList<>();
        }

        @Override
        public List<String> selectAllContractNos()
        {
            return new ArrayList<>();
        }

        @Override
        public List<CtmsContract> selectUnboundPartyCandidates(String partyType, String rawName)
        {
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                // 与 XML 同口径：本方向引用为空 + 本方向文本与 raw_name 等价（ai_ci 比较）
                if (MigrationRules.DIRECTION_SUPPLIER.equals(partyType))
                {
                    if (row.getSupplierId() != null || !sameText(row.getPartyB(), rawName))
                    {
                        continue;
                    }
                }
                else if (MigrationRules.DIRECTION_CUSTOMER.equals(partyType))
                {
                    if (row.getCustomerId() != null || !sameText(row.getPartyA(), rawName))
                    {
                        continue;
                    }
                }
                else
                {
                    continue;
                }
                result.add(copy(row));
            }
            return result;
        }

        @Override
        public int bindPartyRef(String id, String partyType, String partyId, String updateId, String updateBy)
        {
            CtmsContract row = store.get(id);
            if (row == null)
            {
                return 0;
            }
            bindCount++;
            if (MigrationRules.DIRECTION_SUPPLIER.equals(partyType))
            {
                row.setSupplierId(partyId);
            }
            else
            {
                row.setCustomerId(partyId);
            }
            return 1;
        }

        /**
         * 文本等价比对：<b>与 XML 同口径</b> —— 两侧都只去首尾 ASCII 空格
         * （{@code trim(c.party_b) = trim(#{rawName})}）。
         *
         * <p> ⚠ 这里刻意<b>不用</b> {@link MigrationRules#rawNameKey(String)} 做等价判定：
         * Java 的 {@code Collator.PRIMARY} 把空白当可忽略元素（variable weighting），
         * 而 MySQL 的 {@code utf8mb4_0900_ai_ci} <b>空白显著</b>（实测 {@code 'x' = '\tx'} 为 0）。
         * 若桩沿用 Collator 的松口径，"候选比较漏了 trim" 这类缺陷在服务层用例里根本不会变红
         * （t26 的负面对照已经踩过这一点）——所以这里显式模型化 ai_ci 的空白语义：空白显著 + 大小写不敏感。 </p>
         *
         * @param left  库内文本
         * @param right 草案原始名称
         * @return 等价返回 true
         */
        private static boolean sameText(String left, String right)
        {
            if (left == null || right == null)
            {
                return false;
            }
            return MigrationRules.trimSpaces(left).equalsIgnoreCase(MigrationRules.trimSpaces(right));
        }

        private static CtmsContract copy(CtmsContract source)
        {
            CtmsContract target = new CtmsContract();
            target.setId(source.getId());
            target.setContractNo(source.getContractNo());
            target.setName(source.getName());
            target.setType(source.getType());
            target.setPartyA(source.getPartyA());
            target.setPartyB(source.getPartyB());
            target.setCustomerId(source.getCustomerId());
            target.setSupplierId(source.getSupplierId());
            target.setDelFlag(source.getDelFlag());
            return target;
        }
    }

    /**
     * 草案桩：模拟 {@code uk_party_draft(party_type, raw_name)} 的唯一性与 ai_ci 比较规则 ——
     * 查找用 {@link MigrationRules#rawNameKey(String)}（大小写/重音不敏感），
     * 重复插入直接抛错（真库是 1062），这样"分组键口径写错"会在单测里当场暴露而不是只算错计数。
     */
    private static class StubPartyDraftMapper implements CtmsPartyDraftMapper
    {
        private final Map<String, CtmsPartyDraft> store = new LinkedHashMap<>();

        /** insert 次数（幂等用例断言"只插了一次"）。 */
        private int insertCount;

        /** update 次数（任务 7.2：扫描对未变化行不得产生无意义的 update）。 */
        private int updateCount;

        @Override
        public List<CtmsPartyDraft> selectDraftList(CtmsPartyDraft query)
        {
            List<CtmsPartyDraft> result = new ArrayList<>();
            for (CtmsPartyDraft row : store.values())
            {
                if (query != null)
                {
                    if (!blank(query.getId()) && !query.getId().equals(row.getId()))
                    {
                        continue;
                    }
                    if (!blank(query.getPartyType()) && !query.getPartyType().equals(row.getPartyType()))
                    {
                        continue;
                    }
                    if (!blank(query.getRawName())
                            && (row.getRawName() == null || !row.getRawName().contains(query.getRawName())))
                    {
                        continue;
                    }
                    if (!blank(query.getStatus()) && !query.getStatus().equals(row.getStatus()))
                    {
                        continue;
                    }
                    if (!blank(query.getMatchedId()) && !query.getMatchedId().equals(row.getMatchedId()))
                    {
                        continue;
                    }
                }
                result.add(copy(row));
            }
            return result;
        }

        @Override
        public CtmsPartyDraft selectDraftByTypeAndName(String partyType, String rawName)
        {
            CtmsPartyDraft row = find(partyType, rawName);
            return row == null ? null : copy(row);
        }

        @Override
        public CtmsPartyDraft selectDraftById(String id)
        {
            CtmsPartyDraft row = store.get(id);
            return row == null ? null : copy(row);
        }

        @Override
        public int insertDraft(CtmsPartyDraft draft)
        {
            if (find(draft.getPartyType(), draft.getRawName()) != null)
            {
                // 真库在这里会报 1062 Duplicate entry：桩也"硬失败"，避免把撞唯一键写成一个静默的算错
                throw new IllegalStateException("uk_party_draft 冲突：重复的 (party_type, raw_name) = ("
                        + draft.getPartyType() + ", " + draft.getRawName() + ")");
            }
            insertCount++;
            store.put(draft.getId(), copy(draft));
            return 1;
        }

        @Override
        public int updateDraft(CtmsPartyDraft draft)
        {
            if (!store.containsKey(draft.getId()))
            {
                return 0;
            }
            updateCount++;
            store.put(draft.getId(), copy(draft));
            return 1;
        }

        /**
         * 模拟 7.3 的认领写入（本任务只需要它的"状态前置"）。
         *
         * <p> 直接改桩库、<b>不计入 {@link #updateCount}</b>：那个计数只统计"扫描自己发起的 update"，
         * 否则夹具动作会把"扫描对未变化行不写库"的断言弄脏。 </p>
         *
         * @param id        草案主键
         * @param matchedId 认领后绑定的档案ID
         */
        private void markClaimed(String id, String matchedId)
        {
            CtmsPartyDraft row = store.get(id);
            row.setStatus(MigrationRules.STATUS_CLAIMED);
            row.setMatchedId(matchedId);
            row.setUpdateId("100");
            row.setUpdateBy("zhangsan");
            row.setUpdateTime(new Date());
        }

        /**
         * 模拟人工忽略（同上，不计入扫描的 update 次数）。
         *
         * @param id 草案主键
         */
        private void markIgnored(String id)
        {
            CtmsPartyDraft row = store.get(id);
            row.setStatus(MigrationRules.STATUS_IGNORED);
            row.setUpdateId("100");
            row.setUpdateBy("zhangsan");
            row.setUpdateTime(new Date());
        }

        /**
         * 模拟"被绕过接口改坏的行"（状态机之外的取值）。
         *
         * @param id     草案主键
         * @param status 非法状态
         */
        private void breakStatus(String id, String status)
        {
            store.get(id).setStatus(status);
        }

        /**
         * 按幂等键查（比较规则与数据库的 ai_ci 一致）。
         *
         * @param partyType 档案方向
         * @param rawName   原始文本
         * @return 草案；不存在返回 null
         */
        private CtmsPartyDraft find(String partyType, String rawName)
        {
            for (CtmsPartyDraft row : store.values())
            {
                if (same(row.getPartyType(), partyType) && sameRawName(row.getRawName(), rawName))
                {
                    return row;
                }
            }
            return null;
        }

        private static boolean sameRawName(String left, String right)
        {
            if (left == null || right == null)
            {
                return false;
            }
            return MigrationRules.rawNameKey(left).equals(MigrationRules.rawNameKey(right));
        }

        private static boolean same(String left, String right)
        {
            return left != null && left.equals(right);
        }

        private static boolean blank(String value)
        {
            return value == null || value.isEmpty();
        }

        private static CtmsPartyDraft copy(CtmsPartyDraft source)
        {
            CtmsPartyDraft target = new CtmsPartyDraft();
            target.setId(source.getId());
            target.setPartyType(source.getPartyType());
            target.setRawName(source.getRawName());
            target.setContractCount(source.getContractCount());
            target.setStatus(source.getStatus());
            target.setMatchedId(source.getMatchedId());
            target.setRemark(source.getRemark());
            target.setCreateTime(source.getCreateTime());
            target.setUpdateTime(source.getUpdateTime());
            target.setCreateId(source.getCreateId());
            target.setCreateBy(source.getCreateBy());
            target.setUpdateId(source.getUpdateId());
            target.setUpdateBy(source.getUpdateBy());
            return target;
        }
    }

    /**
     * 往来单位档案桩（任务 7.3 的"新建档案后绑定"路径）。
     *
     * <p> 继承真实的 {@code CtmsPartnerServiceImpl} 并只覆盖用到的方法：<b>新增先跑一遍
     * {@code PartnerRules.checkCustomerRequired/checkSupplierRequired}</b>（与真实实现同一行），
     * 这样"简称没兜底"会被真实规则拒绝，而不是靠桩自己发明一条校验收据。 </p>
     */
    private static class StubPartnerService extends CtmsPartnerServiceImpl
    {
        private final Map<String, CtmsCustomer> customers = new LinkedHashMap<>();

        private final Map<String, CtmsSupplier> suppliers = new LinkedHashMap<>();

        /** 客户插入次数（"绑定已有档案不新建"的断言依据）。 */
        private int customerInsertCount;

        /** 供应商插入次数。 */
        private int supplierInsertCount;

        /** 最近一次插入的供应商（断言 short_name 兜底）。 */
        private CtmsSupplier lastSupplier;

        private int seq;

        @Override
        public CtmsCustomer selectCustomerById(String id)
        {
            return customers.get(id);
        }

        @Override
        public CtmsSupplier selectSupplierById(String id)
        {
            return suppliers.get(id);
        }

        @Override
        public void insertCustomer(CtmsCustomer c)
        {
            PartnerRules.checkCustomerRequired(c);
            customerInsertCount++;
            c.setId("GEN-C-" + (++seq));
            customers.put(c.getId(), c);
        }

        @Override
        public void insertSupplier(CtmsSupplier s)
        {
            PartnerRules.checkSupplierRequired(s);
            supplierInsertCount++;
            s.setId("GEN-S-" + (++seq));
            lastSupplier = s;
            suppliers.put(s.getId(), s);
        }
    }

    /** 变更历史桩：只实现认领用到的批量写入与列表。 */
    private static class StubChangeLogMapper implements CtmsChangeLogMapper
    {
        private final List<CtmsChangeLog> store = new ArrayList<>();

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            return new ArrayList<>(store);
        }

        @Override
        public int batchInsertChangeLogs(List<CtmsChangeLog> logs)
        {
            if (logs == null)
            {
                return 0;
            }
            store.addAll(logs);
            return logs.size();
        }

        @Override
        public int insertChangeLog(CtmsChangeLog log)
        {
            store.add(log);
            return 1;
        }
    }

    /* ==================== 本文件的小工具 ==================== */

    /**
     * 取某档案ID对应的「供应商档案」变更历史。
     *
     * @param partyId 档案ID
     * @return 历史集合
     */
    private List<CtmsChangeLog> changeLogsOfSupplierRef(String partyId)
    {
        return changeLogsOf(ContractRules.FIELD_SUPPLIER, partyId);
    }

    /**
     * 取某档案ID对应的「客户档案」变更历史。
     *
     * @param partyId 档案ID
     * @return 历史集合
     */
    private List<CtmsChangeLog> changeLogsOfCustomerRef(String partyId)
    {
        return changeLogsOf(ContractRules.FIELD_CUSTOMER, partyId);
    }

    /**
     * 按字段名 + 新值（档案ID）过滤变更历史。
     *
     * @param fieldName 字段名
     * @param partyId   档案ID
     * @return 历史集合
     */
    private List<CtmsChangeLog> changeLogsOf(String fieldName, String partyId)
    {
        List<CtmsChangeLog> result = new ArrayList<>();
        for (CtmsChangeLog log : changeLogMapper.store)
        {
            if (fieldName.equals(log.getFieldName()) && partyId != null && partyId.equals(log.getNewValue()))
            {
                result.add(log);
            }
        }
        return result;
    }

    /**
     * 断言某服务方法带 {@code @Transactional(rollbackFor = Exception.class)}。
     *
     * @param methodName 方法名
     * @param paramTypes 形参类型（同名方法靠它区分）
     */
    private void assertTransactional(String methodName, Class<?>... paramTypes)
    {
        try
        {
            Method method = CtmsMigrationServiceImpl.class.getMethod(methodName, paramTypes);
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertNotNull(methodName + " 必须带 @Transactional", annotation);
            assertEquals(methodName + " 必须对 Exception 回滚",
                    Exception.class, annotation.rollbackFor()[0]);
        }
        catch (NoSuchMethodException e)
        {
            fail("找不到方法：" + methodName);
        }
    }

    /**
     * 断言认领被拒（文案含关键字）。
     *
     * @param keyword 关键字
     * @param draftId 草案ID
     * @param request 认领请求
     */
    private void assertRejected(final String keyword, final String draftId, final CtmsMigrationClaimVo request)
    {
        assertRejectedMessage(keyword, new Runnable()
        {
            @Override
            public void run()
            {
                service.claimPartyDraft(draftId, request);
            }
        });
    }

    /**
     * 断言某段逻辑被拒绝且文案含关键字。
     *
     * @param keyword  关键字
     * @param runnable 待执行逻辑
     */
    private void assertRejectedMessage(String keyword, Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("应当被拒绝并提示：" + keyword);
        }
        catch (ServiceException e)
        {
            assertTrue("提示应包含「" + keyword + "」，实际为「" + e.getMessage() + "」",
                    e.getMessage() != null && e.getMessage().contains(keyword));
        }
    }
}
