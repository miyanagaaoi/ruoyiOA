package com.ruoyi.ctms.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Before;
import org.junit.Test;

import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.ctms.controller.CtmsContractController;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsContractItem;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.CtmsTag;
import com.ruoyi.ctms.domain.vo.CtmsContractDetailVo;
import com.ruoyi.ctms.domain.vo.CtmsWarrantyReminderVo;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractItemMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsTagMapper;
import com.ruoyi.ctms.support.ContractNumberRules;
import com.ruoyi.ctms.support.ContractRules;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictTypeService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <p> {@link CtmsContractServiceImpl} 的业务口径单测（2.0 B3 任务 4.1~4.8）。 </p>
 *
 * <p> <b>不依赖 Spring、不依赖数据库、不依赖 Redis、不依赖真实时钟</b>：四个 Mapper 与
 * 字典/档案/物料服务都用测试类内的内存桩，通过反射注入 {@code @Autowired} 字段；
 * "当前时间"与"当前用户/数据范围档位"由 {@link TestContractService} 子类固定，
 * 因此 30 天恢复窗口这类边界可以被精确断言。 </p>
 *
 * <p> 每个用例断言的都是<b>前端会看到的错误文案</b>（前端与验收脚本共用同一批字符串），
 * 以及"被拒时确实没有落库"这一副作用。 </p>
 *
 * @author 二开
 */
public class CtmsContractServiceImplTest
{
    /** 物料桩：球阀/法兰/螺栓/垫片。 */
    private static final String PRODUCT_VALVE = "P-VALVE";

    private static final String PRODUCT_FLANGE = "P-FLANGE";

    private static final String PRODUCT_BOLT = "P-BOLT";

    private static final String PRODUCT_GASKET = "P-GASKET";

    private final StubContractMapper contractMapper = new StubContractMapper();

    private final StubItemMapper itemMapper = new StubItemMapper();

    private final StubTagMapper tagMapper = new StubTagMapper(contractMapper);

    private final StubChangeLogMapper changeLogMapper = new StubChangeLogMapper();

    private final StubPartnerService partnerService = new StubPartnerService();

    private final StubProductMasterService productMasterService = new StubProductMasterService();

    private final StubDictService dictService = new StubDictService();

    private final StubCodeGenService codeGenService = new StubCodeGenService();

    private final CtmsTagServiceImpl tagService = new CtmsTagServiceImpl();

    private final TestContractService service = new TestContractService();

    @Before
    public void setUp()
    {
        inject(service, "contractMapper", contractMapper);
        inject(service, "itemMapper", itemMapper);
        inject(service, "tagMapper", tagMapper);
        inject(service, "changeLogMapper", changeLogMapper);
        inject(service, "tagService", tagService);
        inject(service, "partnerService", partnerService);
        inject(service, "productMasterService", productMasterService);
        inject(service, "dictTypeService", dictService);
        inject(service, "codeGenService", codeGenService);
        inject(tagService, "tagMapper", tagMapper);
        inject(tagService, "contractMapper", contractMapper);
        // 字典：与 sql/二开-合同台账-字典参数.sql 的取值一致（合法集合就是这里返回的 dictValue 集合）
        dictService.addDict("contract_statuses", "内部审批中", "集团审批中", "已签订", "付款中", "发货", "到货", "已终止");
        dictService.addDict("arrival_statuses", "未到货", "部分到货", "已到货");
        // 合同类型：dict_value 就是类型码（启用 6 项；OTH 其他(历史) 用 status='1' 表达不参与编号，
        // 字典接口本身不返回停用项 → 桩里就是不包含它）
        dictService.addDict("contract_types", "SAL", "PUR", "COO", "LAB", "FIN", "NDA");
        dictService.addDict("subjects", "ZC", "YX");
        dictService.addDict("item_types", "采购", "销售", "服务", "其他");
        // 物料档案
        productMasterService.add(PRODUCT_VALVE, "球阀", "DN50", "WL-0001");
        productMasterService.add(PRODUCT_FLANGE, "法兰", null, "WL-0002");
        productMasterService.add(PRODUCT_BOLT, "螺栓", "M12", "WL-0003");
        productMasterService.add(PRODUCT_GASKET, "垫片", "DN50", "WL-0004");
        // 往来单位档案
        CtmsCustomer customer = new CtmsCustomer();
        customer.setId("C-001");
        customer.setName("百脉泉阀门有限公司");
        partnerService.customers.put(customer.getId(), customer);
        CtmsSupplier supplier = new CtmsSupplier();
        supplier.setId("S-001");
        supplier.setName("云羲数字科技有限公司");
        partnerService.suppliers.put(supplier.getId(), supplier);
    }

    /* ==================== ① 登记后可在列表检索 ==================== */

    @Test
    public void 新增后可在列表按关键字检索到()
    {
        CtmsContract contract = payload("PURZC202609000001", "阀门采购合同");
        service.insertContract(contract);
        assertNotNull("详情必须返回同一编号", contractMapper.store.get(contract.getId()));
        CtmsContract query = new CtmsContract();
        query.setKeyword("阀门");
        List<CtmsContract> list = service.selectContractList(query);
        assertEquals("按关键字「阀门」应检索到 1 条", 1, list.size());
        assertEquals("PURZC202609000001", list.get(0).getContractNo());
        assertEquals("详情返回同一编号", "PURZC202609000001",
                service.selectContractDetail(contract.getId()).getContractNo());
        assertTrue("数据范围片段必须写进查询对象（本用例无登录上下文 → 不加条件）",
                query.getDataScopeSql() == null || query.getDataScopeSql().length() > 0);
    }

    @Test
    public void 编号重复被拒且名称类型必填()
    {
        service.insertContract(payload("PURZC202609000002", "合同甲"));
        final CtmsContract duplicate = payload("PURZC202609000002", "合同乙");
        assertRejected("合同编号已存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(duplicate);
            }
        });
        final CtmsContract noName = payload("PURZC202609000003", null);
        assertRejected("合同名称不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(noName);
            }
        });
        // 编号留空 = 服务端生成（5.8）：类型缺失时无法生成编号，但报错文案是类型而不是"编号不能为空"
        final CtmsContract noType = new CtmsContract();
        noType.setName("无类型合同");
        assertRejected("合同类型不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(noType);
            }
        });
        // 手工/历史编号通道要校验格式（否则唯一索引与占号扫描都失效）
        final CtmsContract badNo = payload("PURZC2026", "编号格式非法合同");
        assertRejected("合同编号格式不正确", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(badNo);
            }
        });
        assertEquals("被拒的都不许落库", 1, contractMapper.store.size());
    }

    /* ==================== ② 已停用不可编辑 ==================== */

    @Test
    public void 已停用合同不可编辑()
    {
        CtmsContract contract = payload("PURZC202609000010", "待停用合同");
        service.insertContract(contract);
        service.softDelete(contract.getId(), "客户取消订单");
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setName("改名尝试");
        assertRejected("合同已停用，请先恢复", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
        assertEquals("被拒后库中数据不变", "待停用合同", contractMapper.store.get(contract.getId()).getName());
    }

    /* ==================== ③ 非法状态被拒 ==================== */

    @Test
    public void 非法进度状态被拒()
    {
        final CtmsContract bad = payload("PURZC202609000011", "非法状态合同");
        bad.setStatus("已完成");
        assertRejected("无效状态", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(bad);
            }
        });
        assertEquals("状态非法时整单不落库", 0, contractMapper.store.size());

        CtmsContract contract = payload("PURZC202609000012", "合法状态合同");
        service.insertContract(contract);
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setStatus("未知状态");
        assertRejected("无效状态", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
        assertEquals("编辑被拒时库中状态保持原值", "内部审批中",
                contractMapper.store.get(contract.getId()).getStatus());
    }

    @Test
    public void 非法到货状态被拒且为空时取字典默认()
    {
        final CtmsContract bad = payload("PURZC202609000013", "非法到货状态合同");
        bad.setArrivalStatus("已签收");
        assertRejected("无效到货状态", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(bad);
            }
        });
        CtmsContract contract = payload("PURZC202609000014", "默认到货状态合同");
        service.insertContract(contract);
        assertEquals("到货状态为空时取字典第一项", "未到货",
                contractMapper.store.get(contract.getId()).getArrivalStatus());
        assertEquals("进度状态为空时取字典第一项", "内部审批中",
                contractMapper.store.get(contract.getId()).getStatus());
    }

    /* ==================== ④ 服务端快照 ==================== */

    @Test
    public void 登记时创建人与部门取服务端快照()
    {
        service.userId = "100";
        service.username = "zhangsan";
        service.deptId = "103";
        CtmsContract contract = payload("PURZC202609000020", "快照合同");
        // 请求体里带上的 id/deptId/createId 一律不得生效
        contract.setId("FORGED-ID");
        contract.setDeptId("999");
        contract.setCreateId("999");
        service.insertContract(contract);
        assertFalse("主键必须由服务端生成，请求体的 id 被忽略", "FORGED-ID".equals(contract.getId()));
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("dept_id 取当前用户部门", "103", saved.getDeptId());
        assertEquals("create_id 取当前用户", "100", saved.getCreateId());
        assertEquals("create_by 取当前用户登录名快照", "zhangsan", saved.getCreateBy());
        assertEquals("update_id 同样写用户ID", "100", saved.getUpdateId());
        assertEquals("del_flag 默认未删除", "0", saved.getDelFlag());
        assertEquals("币种默认 CNY", "CNY", saved.getCurrency());
        assertEquals("has_warranty 默认 0", "0", saved.getHasWarranty());
        assertEquals("is_framework 默认 0", "0", saved.getIsFramework());
        assertEquals("warranty_released 默认 0", "0", saved.getWarrantyReleased());
    }

    /* ==================== ⑤ 档案引用与文本兜底 ==================== */

    @Test
    public void 客户或供应商档案不存在被拒()
    {
        final CtmsContract badCustomer = payload("PURZC202609000030", "客户不存在合同");
        badCustomer.setCustomerId("NOT-EXIST");
        assertRejected("客户档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(badCustomer);
            }
        });
        final CtmsContract badSupplier = payload("PURZC202609000031", "供应商不存在合同");
        badSupplier.setSupplierId("NOT-EXIST");
        assertRejected("供应商档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(badSupplier);
            }
        });
        assertEquals("档案不存在时整单不落库", 0, contractMapper.store.size());
    }

    @Test
    public void 未填甲乙方文本时用档案名称回填()
    {
        CtmsContract byCustomer = payload("PURZC202609000032", "按客户回填合同");
        byCustomer.setCustomerId("C-001");
        service.insertContract(byCustomer);
        assertEquals("party_a 用客户档案名称回填", "百脉泉阀门有限公司",
                contractMapper.store.get(byCustomer.getId()).getPartyA());

        CtmsContract bySupplier = payload("PURZC202609000033", "按供应商回填合同");
        bySupplier.setSupplierId("S-001");
        service.insertContract(bySupplier);
        assertEquals("party_b 用供应商档案名称回填", "云羲数字科技有限公司",
                contractMapper.store.get(bySupplier.getId()).getPartyB());
    }

    @Test
    public void 已填文本时不被档案名称覆盖()
    {
        CtmsContract contract = payload("PURZC202609000034", "自带文本合同");
        contract.setCustomerId("C-001");
        contract.setPartyA("我自己的甲方文本");
        service.insertContract(contract);
        assertEquals("已有文本优先", "我自己的甲方文本", contractMapper.store.get(contract.getId()).getPartyA());
    }

    /* ==================== ⑥ 未绑定档案的合同正常保存 ==================== */

    @Test
    public void 未绑定档案的合同按文本兜底保存()
    {
        CtmsContract contract = payload("PURZC202609000040", "纯文本合同");
        contract.setPartyA("甲方文本");
        contract.setPartyB("乙方文本");
        service.insertContract(contract);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("甲方文本保留", "甲方文本", saved.getPartyA());
        assertEquals("乙方文本保留", "乙方文本", saved.getPartyB());

        // 甲乙方都为空串也必须能存（历史合同/迁移期合同）
        CtmsContract empty = payload("PURZC202609000041", "空文本合同");
        empty.setPartyA("");
        empty.setPartyB(null);
        service.insertContract(empty);
        CtmsContract savedEmpty = contractMapper.store.get(empty.getId());
        assertEquals("空串保留为空串", "", savedEmpty.getPartyA());
        assertEquals("null 归一为空串（party_a/party_b 是 NOT NULL 列）", "", savedEmpty.getPartyB());
    }

    /* ==================== ⑦ 金额先舍入再汇总 + 标的物摘要落库 ==================== */

    @Test
    public void 三行单价1_665时合同金额先舍入再汇总为5_01()
    {
        CtmsContract contract = payload("PURZC202609000050", "先舍入再汇总合同");
        contract.setItems(items(
                item(PRODUCT_VALVE, "1", "1.665"),
                item(PRODUCT_BOLT, "1", "1.665"),
                item(PRODUCT_GASKET, "1", "1.665")));
        service.insertContract(contract);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("1.67 + 1.67 + 1.67 = 5.01（先汇总再舍入会得 5.00）", "5.01",
                saved.getAmount().toPlainString());
        List<CtmsContractItem> stored = itemMapper.byContract.get(contract.getId());
        assertEquals("三行都要落库", 3, stored.size());
        for (int i = 0; i < stored.size(); i++)
        {
            assertEquals("行总价逐行 HALF_UP 到 2 位", "1.67", stored.get(i).getTotal().toPlainString());
            assertEquals("序号按提交顺序从 1 连续重排", Integer.valueOf(i + 1), stored.get(i).getSeq());
        }
    }

    @Test
    public void 标的物摘要真正落库为球阀与法兰()
    {
        CtmsContract contract = payload("PURZC202609000051", "摘要合同");
        // 名称与规格留空 → 回落物料档案；第二行物料规格为空 → 摘要退化为「法兰×10」
        contract.setItems(items(
                item(PRODUCT_VALVE, "2", "1.000"),
                item(PRODUCT_FLANGE, "10", "1.000")));
        service.insertContract(contract);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("标的物摘要必须真正落库", "球阀(DN50)×2；法兰×10", saved.getSubjectMatter());
        assertEquals("合同金额为已舍入行总价之和", "12.00", saved.getAmount().toPlainString());
        List<CtmsContractItem> stored = itemMapper.byContract.get(contract.getId());
        assertEquals("行项名称回落物料档案名称", "球阀", stored.get(0).getName());
        assertEquals("行项规格回落物料档案规格", "DN50", stored.get(0).getSpec());
        assertEquals("保留物料编码快照", "WL-0001", stored.get(0).getProductCode());
        assertEquals("保留物料名称快照", "球阀", stored.get(0).getProductName());
        assertEquals("物料规格为空时摘要在括号处退化", "法兰", stored.get(1).getName());
        assertEquals("", stored.get(1).getSpec());
    }

    @Test
    public void 编辑时行项全量替换并重算金额与摘要()
    {
        CtmsContract contract = payload("PURZC202609000052", "行项替换合同");
        contract.setItems(items(item(PRODUCT_VALVE, "2", "10.000")));
        service.insertContract(contract);
        assertEquals("20.00", contractMapper.store.get(contract.getId()).getAmount().toPlainString());

        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setItems(items(item(PRODUCT_FLANGE, "3", "5.000")));
        service.updateContract(edit);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("全量替换后只剩 1 行", 1, itemMapper.byContract.get(contract.getId()).size());
        assertEquals("15.00", saved.getAmount().toPlainString());
        assertEquals("法兰×3", saved.getSubjectMatter());
    }

    /* ==================== ⑧ 未选物料被拒 ==================== */

    @Test
    public void 未选物料档案的行项被拒且行号取重排后的序号()
    {
        CtmsContract first = payload("PURZC202609000060", "首行未选物料");
        first.setItems(items(item(null, "1", "1.000")));
        assertRejected("行项第 1 行必须选择物料档案", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(first);
            }
        });

        CtmsContract second = payload("PURZC202609000061", "次行未选物料");
        second.setItems(items(item(PRODUCT_VALVE, "1", "1.000"), item(null, "1", "1.000")));
        assertRejected("行项第 2 行必须选择物料档案", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(second);
            }
        });

        CtmsContract ghost = payload("PURZC202609000062", "物料不存在");
        ghost.setItems(items(item("NOT-EXIST", "1", "1.000")));
        assertRejected("行项第 1 行的物料档案不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(ghost);
            }
        });
        assertEquals("整单不落库", 0, contractMapper.store.size());
    }

    /* ==================== ⑨ 负数被拒 ==================== */

    @Test
    public void 数量或单价为负数的行项被拒()
    {
        CtmsContract negativeQty = payload("PURZC202609000070", "负数数量合同");
        negativeQty.setItems(items(item(PRODUCT_VALVE, "-1", "1.000")));
        assertRejected("行项第 1 行的数量不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(negativeQty);
            }
        });

        CtmsContract negativePrice = payload("PURZC202609000071", "负数单价合同");
        negativePrice.setItems(items(item(PRODUCT_VALVE, "1", "-1.00")));
        assertRejected("行项第 1 行的单价不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(negativePrice);
            }
        });

        CtmsContract secondLine = payload("PURZC202609000072", "次行负数合同");
        secondLine.setItems(items(item(PRODUCT_VALVE, "1", "1.00"), item(PRODUCT_FLANGE, "-2", "1.00")));
        assertRejected("行项第 2 行的数量不能为负数", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(secondLine);
            }
        });
        assertEquals("整单不落库", 0, contractMapper.store.size());
    }

    /* ==================== ⑩⑪ 状态自由流转 ==================== */

    @Test
    public void 状态可回退且到货状态不变()
    {
        CtmsContract contract = payload("PURZC202609000080", "状态回退合同");
        contract.setStatus("已签订");
        contract.setArrivalStatus("已到货");
        service.insertContract(contract);
        int logsBefore = changeLogMapper.store.size();

        CtmsContract change = new CtmsContract();
        change.setId(contract.getId());
        change.setStatus("内部审批中");
        assertEquals("更新 1 行", 1, service.changeStatus(change));
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("允许从任意状态改到任意状态（无顺序守卫）", "内部审批中", saved.getStatus());
        assertEquals("到货状态与进度状态无联动，必须保持原值", "已到货", saved.getArrivalStatus());
        assertEquals("只改一个字段就只写一条历史", logsBefore + 1, changeLogMapper.store.size());
        CtmsChangeLog log = changeLogMapper.lastOf("进度状态");
        assertNotNull("必须留下进度状态的历史", log);
        assertEquals("已签订", log.getOldValue());
        assertEquals("内部审批中", log.getNewValue());
        assertEquals("manual", log.getSource());
    }

    @Test
    public void 只改到货状态时不写进度状态历史()
    {
        CtmsContract contract = payload("PURZC202609000081", "到货状态合同");
        contract.setStatus("发货");
        service.insertContract(contract);
        int logsBefore = changeLogMapper.store.size();
        CtmsContract change = new CtmsContract();
        change.setId(contract.getId());
        change.setArrivalStatus("部分到货");
        change.setExpectedArrivalDate(at("2026-03-20 00:00:00"));
        service.changeStatus(change);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("进度状态不动", "发货", saved.getStatus());
        assertEquals("到货状态更新", "部分到货", saved.getArrivalStatus());
        assertEquals("预计到货日期一并更新", "2026-03-20",
                new SimpleDateFormat("yyyy-MM-dd").format(saved.getExpectedArrivalDate()));
        assertEquals("只写两条历史（到货状态 + 预计到货日期）", logsBefore + 2, changeLogMapper.store.size());
        assertNull("没有进度状态的变更记录", changeLogMapper.lastOfNewValue("进度状态"));
    }

    @Test
    public void 改为已终止未填终止原因被拒()
    {
        CtmsContract contract = payload("PURZC202609000082", "终止合同");
        contract.setStatus("已签订");
        contract.setArrivalStatus("已到货");
        service.insertContract(contract);

        final CtmsContract noReason = new CtmsContract();
        noReason.setId(contract.getId());
        noReason.setStatus("已终止");
        assertRejected("已终止必须填写终止原因", new Runnable()
        {
            @Override
            public void run()
            {
                service.changeStatus(noReason);
            }
        });
        assertEquals("被拒时状态保持原值", "已签订", contractMapper.store.get(contract.getId()).getStatus());

        CtmsContract withReason = new CtmsContract();
        withReason.setId(contract.getId());
        withReason.setStatus("已终止");
        withReason.setDeletedReason("客户单方面取消");
        service.changeStatus(withReason);
        assertEquals("填了原因即可终止", "已终止", contractMapper.store.get(contract.getId()).getStatus());
        CtmsChangeLog log = changeLogMapper.lastOf("status");
        assertNotNull("已终止必须写一条 field_name='status' 的历史", log);
        assertTrue("新值里要带上终止原因文本：" + log.getNewValue(),
                log.getNewValue() != null && log.getNewValue().contains("客户单方面取消"));
        assertEquals("终止原因也落在 note 上", "客户单方面取消", log.getNote());
    }

    /* ==================== ⑫ 软删除与 30 天恢复 ==================== */

    @Test
    public void 停用后第30天可恢复第31天不可()
    {
        service.fixedNow = at("2026-03-01 09:00:00");
        CtmsContract contract = payload("PURZC202609000090", "恢复窗口合同");
        service.insertContract(contract);
        service.softDelete(contract.getId(), "误录");
        CtmsContract deleted = contractMapper.store.get(contract.getId());
        assertEquals("停用标志落库", "1", deleted.getDelFlag());
        assertEquals("停用原因落库", "误录", deleted.getDeletedReason());
        assertEquals("停用时间落库", "2026-03-01 09:00:00",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(deleted.getDeletedAt()));
        CtmsChangeLog deletedLog = changeLogMapper.lastOf("deleted");
        assertNotNull("软删除要留一条 deleted 历史", deletedLog);
        assertEquals("0", deletedLog.getOldValue());
        assertEquals("1", deletedLog.getNewValue());
        assertEquals("误录", deletedLog.getNote());

        // 第 30 天（整数日差 30）→ 可恢复
        service.fixedNow = at("2026-03-31 08:00:00");
        service.restore(contract.getId());
        CtmsContract restored = contractMapper.store.get(contract.getId());
        assertEquals("恢复后回到未停用", "0", restored.getDelFlag());
        assertNull("停用时间被清空", restored.getDeletedAt());
        assertNull("停用原因被清空", restored.getDeletedReason());

        // 再停用一次（仍在 3-01 当天），第 31 天 → 拒绝
        service.fixedNow = at("2026-03-01 10:00:00");
        service.softDelete(contract.getId(), "再次误录");
        service.fixedNow = at("2026-04-01 09:00:00");
        assertRejected("已超过 30 天保留期，无法恢复", new Runnable()
        {
            @Override
            public void run()
            {
                service.restore(contract.getId());
            }
        });
        assertEquals("被拒时保持停用", "1", contractMapper.store.get(contract.getId()).getDelFlag());
    }

    @Test
    public void 未停用合同调用恢复是幂等的()
    {
        CtmsContract contract = payload("PURZC202609000091", "幂等恢复合同");
        service.insertContract(contract);
        int logsBefore = changeLogMapper.store.size();
        service.restore(contract.getId());
        assertEquals("未停用时仍是未停用", "0", contractMapper.store.get(contract.getId()).getDelFlag());
        assertEquals("幂等恢复不写历史", logsBefore, changeLogMapper.store.size());
    }

    @Test
    public void 已停用时重复停用是幂等的()
    {
        CtmsContract contract = payload("PURZC202609000092", "幂等停用合同");
        service.insertContract(contract);
        service.softDelete(contract.getId(), "第一次");
        int logsBefore = changeLogMapper.store.size();
        service.softDelete(contract.getId(), "第二次");
        assertEquals("已停用时不再报错", "1", contractMapper.store.get(contract.getId()).getDelFlag());
        assertEquals("已停用时原值不被覆盖", "第一次", contractMapper.store.get(contract.getId()).getDeletedReason());
        assertEquals("不重复写历史", logsBefore, changeLogMapper.store.size());
    }

    @Test
    public void 停用原因为空被拒()
    {
        final CtmsContract contract = payload("PURZC202609000093", "缺原因合同");
        service.insertContract(contract);
        assertRejected("停用原因不能为空", new Runnable()
        {
            @Override
            public void run()
            {
                service.softDelete(contract.getId(), "   ");
            }
        });
        assertEquals("被拒时保持未停用", "0", contractMapper.store.get(contract.getId()).getDelFlag());
    }

    /* ==================== ⑬ 标签与框架 ==================== */

    @Test
    public void 框架合同不能再挂到其他框架下()
    {
        CtmsContract framework = insertFramework("PURZC202609000100", "框架甲");
        final CtmsContract other = insertFramework("PURZC202609000101", "框架乙");
        CtmsContract edit = new CtmsContract();
        edit.setId(other.getId());
        edit.setParentId(framework.getId());
        assertRejected("框架合同不能再挂到其他框架下", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
    }

    @Test
    public void 父合同不存在或已停用时被拒()
    {
        CtmsContract child = payload("PURZC202609000110", "子合同");
        service.insertContract(child);
        CtmsContract edit = new CtmsContract();
        edit.setId(child.getId());
        edit.setParentId("NOT-EXIST");
        assertRejected("父合同不存在或已停用", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });

        CtmsContract framework = insertFramework("PURZC202609000111", "待停用框架");
        service.softDelete(framework.getId(), "框架作废");
        CtmsContract edit2 = new CtmsContract();
        edit2.setId(child.getId());
        edit2.setParentId(framework.getId());
        assertRejected("父合同不存在或已停用", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit2);
            }
        });
    }

    @Test
    public void 父合同不是框架合同时被拒()
    {
        CtmsContract plain = payload("PURZC202609000120", "普通合同");
        service.insertContract(plain);
        CtmsContract child = payload("PURZC202609000121", "待绑定子合同");
        service.insertContract(child);
        CtmsContract edit = new CtmsContract();
        edit.setId(child.getId());
        edit.setParentId(plain.getId());
        assertRejected("只能挂到框架合同下", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
    }

    @Test
    public void 已是子合同不能再标记为框架合同()
    {
        CtmsContract framework = insertFramework("PURZC202609000130", "框架丙");
        CtmsContract child = payload("PURZC202609000131", "已绑定子合同");
        child.setParentId(framework.getId());
        service.insertContract(child);
        CtmsContract edit = new CtmsContract();
        edit.setId(child.getId());
        edit.setIsFramework("1");
        edit.setParentId(framework.getId());
        assertRejected("已是子合同，不能再标记为框架合同", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
    }

    @Test
    public void 仍有未停用子合同时不能取消框架标记()
    {
        CtmsContract framework = insertFramework("PURZC202609000140", "框架丁");
        CtmsContract child = payload("PURZC202609000141", "子合同一");
        child.setParentId(framework.getId());
        service.insertContract(child);
        CtmsContract edit = new CtmsContract();
        edit.setId(framework.getId());
        edit.setIsFramework("0");
        assertRejected("框架下仍有子合同，请先解除子合同", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContract(edit);
            }
        });
        // 解除子合同后即可取消框架标记
        CtmsContract detach = new CtmsContract();
        detach.setId(child.getId());
        detach.setParentId("");
        service.updateContract(detach);
        service.updateContract(edit);
        assertEquals("子合同解除后框架标记可以取消", "0",
                contractMapper.store.get(framework.getId()).getIsFramework());
    }

    @Test
    public void 框架详情汇总子合同且与自身金额分开()
    {
        CtmsContract framework = insertFramework("PURZC202609000150", "汇总框架");
        framework.setAmount(new BigDecimal("100000.00"));
        service.updateContract(framework);
        CtmsContract first = payload("PURZC202609000151", "子合同一");
        first.setParentId(framework.getId());
        first.setAmount(new BigDecimal("60000.00"));
        service.insertContract(first);
        CtmsContract second = payload("PURZC202609000152", "子合同二");
        second.setParentId(framework.getId());
        second.setAmount(new BigDecimal("30000.00"));
        service.insertContract(second);

        CtmsContract detail = service.selectFrameworkDetail(framework.getId());
        assertEquals("子合同数量", Integer.valueOf(2), detail.getChildrenCount());
        assertEquals("子合同清单", 2, detail.getChildren().size());
        assertEquals("子合同金额合计", "90000.00", detail.getChildrenAmountSum().toPlainString());
        assertEquals("框架自身金额保持不变，两者分开呈现", "100000.00",
                detail.getAmount().toPlainString());
        assertEquals("框架自身金额不被汇总覆盖", "100000.00",
                contractMapper.store.get(framework.getId()).getAmount().toPlainString());
    }

    /* ==================== ⑭ 自动标签与手动标签 ==================== */

    @Test
    public void 类型自动标签在类型变更时替换()
    {
        CtmsContract contract = payload("PURZC202609000160", "类型标签合同");
        contract.setType("PUR");
        service.insertContract(contract);
        String purchaseTagId = tagMapper.tagIdOf("PUR");
        assertNotNull("类型自动标签必须被创建", purchaseTagId);
        assertEquals("关联方式为自动", "1", tagMapper.links.get(contract.getId()).get(purchaseTagId));

        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setType("SAL");
        service.updateContract(edit);
        assertNull("旧类型的自动标签被移除", tagMapper.links.get(contract.getId()).get(purchaseTagId));
        String salesTagId = tagMapper.tagIdOf("SAL");
        assertNotNull("新类型的自动标签必须被附加", salesTagId);
        assertEquals("1", tagMapper.links.get(contract.getId()).get(salesTagId));
    }

    @Test
    public void 手动同名标签不被自动同步移除()
    {
        CtmsContract contract = payload("PURZC202609000161", "手动标签合同");
        contract.setType("SAL");
        service.insertContract(contract);
        String salesTagId = tagMapper.tagIdOf("SAL");
        // 构造"用户手工添加过同名标签"的库内状态：关联行的 auto 被改成 0
        tagMapper.links.get(contract.getId()).put(salesTagId, "0");

        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setType("PUR");
        service.updateContract(edit);
        assertEquals("手动同名标签必须保留，且不被改写成自动", "0",
                tagMapper.links.get(contract.getId()).get(salesTagId));
        assertNotNull("手动关联行没有被删除", tagMapper.links.get(contract.getId()).get(salesTagId));
        assertEquals("新类型的自动标签照常附加", "1",
                tagMapper.links.get(contract.getId()).get(tagMapper.tagIdOf("PUR")));
    }

    @Test
    public void 手工标签按集合增删且不动自动标签()
    {
        CtmsContract contract = payload("PURZC202609000162", "手工标签集合合同");
        contract.setType("PUR");
        CtmsTag manual = new CtmsTag();
        manual.setName("加急");
        tagService.insertTag(manual);
        contract.setTagIds(Arrays.asList(manual.getId()));
        service.insertContract(contract);
        assertEquals("手工标签关联为手动", "0", tagMapper.links.get(contract.getId()).get(manual.getId()));
        assertEquals("类型自动标签同时在", "1",
                tagMapper.links.get(contract.getId()).get(tagMapper.tagIdOf("PUR")));

        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setTagIds(new ArrayList<String>());
        service.updateContract(edit);
        assertNull("手工标签被移出", tagMapper.links.get(contract.getId()).get(manual.getId()));
        assertNotNull("自动标签不受手工集合影响", tagMapper.links.get(contract.getId()).get(tagMapper.tagIdOf("PUR")));
    }

    @Test
    public void 子合同自动获得框架合同标签()
    {
        CtmsContract framework = insertFramework("PURZC202609000163", "标签框架");
        CtmsContract child = payload("PURZC202609000164", "待绑定合同");
        child.setParentId(framework.getId());
        service.insertContract(child);
        String frameworkTagId = tagMapper.tagIdOf(CtmsTagServiceImpl.FRAMEWORK_TAG_NAME);
        assertNotNull("「框架合同」标签必须被自动创建", frameworkTagId);
        assertEquals("子合同的框架标签为自动", "1", tagMapper.links.get(child.getId()).get(frameworkTagId));
        assertEquals("框架自身的框架标签为自动", "1", tagMapper.links.get(framework.getId()).get(frameworkTagId));
    }

    @Test
    public void 自动创建标签的颜色按已有总数取模()
    {
        CtmsTag first = tagService.ensureTag("标签一");
        CtmsTag second = tagService.ensureTag("标签二");
        assertEquals("#409eff", first.getColor());
        assertEquals("#67c23a", second.getColor());
        assertEquals("同名再取不会新建", first.getId(), tagService.ensureTag("标签一").getId());
        assertEquals("builtin 默认 0", "0", first.getBuiltin());
    }

    /* ==================== ⑮ 变更历史 ==================== */

    @Test
    public void 手工改名留下manual来源的字段级历史()
    {
        CtmsContract contract = payload("PURZC202609000170", "A 合同");
        service.insertContract(contract);
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setName("B 合同");
        service.updateContract(edit);
        CtmsChangeLog log = changeLogMapper.lastOf("合同名称");
        assertNotNull("改名必须留痕", log);
        assertEquals("A 合同", log.getOldValue());
        assertEquals("B 合同", log.getNewValue());
        assertEquals("手工改的来源是 manual", "manual", log.getSource());
        assertEquals("必须含当前操作人标识", "100", log.getOperatorId());
        assertEquals("必须含当前操作人姓名快照", "张三", log.getOperatorName());
        assertEquals("对象类型固定为 contract", "contract", log.getObjectType());
        assertEquals("对象标识是合同ID", contract.getId(), log.getObjectId());
    }

    @Test
    public void 自动重算留痕为auto来源()
    {
        CtmsContract contract = payload("PURZC202609000171", "质保合同");
        contract.setHasWarranty("1");
        contract.setWarrantyStart(at("2025-06-01 00:00:00"));
        contract.setWarrantyMonths(Integer.valueOf(12));
        service.insertContract(contract);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertEquals("新增即服务端计算到期日", "2026-05-31",
                new SimpleDateFormat("yyyy-MM-dd").format(saved.getWarrantyEnd()));
        CtmsChangeLog insertLog = changeLogMapper.lastOf("质量保证金到期日");
        assertNotNull("新增也要留痕", insertLog);
        assertEquals("新增的到期日来源为 auto", "auto", insertLog.getSource());
        assertNull("旧值为空", insertLog.getOldValue());
        assertEquals("2026-05-31", insertLog.getNewValue());

        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setWarrantyMonths(Integer.valueOf(24));
        service.updateContract(edit);
        CtmsChangeLog editLog = changeLogMapper.lastOf("质量保证金到期日");
        assertEquals("编辑导致重算的来源同样是 auto", "auto", editLog.getSource());
        assertEquals("2026-05-31", editLog.getOldValue());
        assertEquals("2027-05-31", editLog.getNewValue());
    }

    @Test
    public void 行项改动让金额与摘要留痕为auto()
    {
        CtmsContract contract = payload("PURZC202609000172", "金额留痕合同");
        contract.setAmount(new BigDecimal("100.00"));
        service.insertContract(contract);
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setItems(items(item(PRODUCT_VALVE, "2", "30.000"), item(PRODUCT_FLANGE, "4", "10.000")));
        service.updateContract(edit);
        CtmsChangeLog amountLog = changeLogMapper.lastOf("合同金额");
        assertNotNull("合同金额必须留痕", amountLog);
        assertEquals("auto", amountLog.getSource());
        assertEquals("100.00", amountLog.getOldValue());
        assertEquals("100.00", amountLog.getNewValue());
        CtmsChangeLog summaryLog = changeLogMapper.lastOf("_summary");
        assertNotNull("标的物摘要用特殊字段名 _summary", summaryLog);
        assertEquals("auto", summaryLog.getSource());
        assertEquals("球阀(DN50)×2；法兰×4", summaryLog.getNewValue());
        CtmsChangeLog itemsLog = changeLogMapper.lastOf("_items");
        assertNotNull("行项集合变化用特殊字段名 _items", itemsLog);
        assertEquals("manual", itemsLog.getSource());
    }

    @Test
    public void 关闭质保清空七个字段并逐个留痕为auto()
    {
        CtmsContract contract = payload("PURZC202609000173", "关闭质保合同");
        contract.setAmount(new BigDecimal("100.00"));
        contract.setHasWarranty("1");
        contract.setWarrantyStart(at("2025-06-01 00:00:00"));
        contract.setWarrantyMonths(Integer.valueOf(12));
        contract.setWarrantyRate(new BigDecimal("5"));
        contract.setWarrantyReleased("1");
        contract.setWarrantyReleaseDate(at("2026-06-01 00:00:00"));
        contract.setWarrantyNote("保留这条备注");
        service.insertContract(contract);
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setHasWarranty("0");
        service.updateContract(edit);
        CtmsContract saved = contractMapper.store.get(contract.getId());
        assertNull("质保金额被清空", saved.getWarrantyAmount());
        assertNull("质保比例被清空", saved.getWarrantyRate());
        assertNull("质保起始日被清空", saved.getWarrantyStart());
        assertNull("质保月数被清空", saved.getWarrantyMonths());
        assertNull("质保到期日被清空", saved.getWarrantyEnd());
        assertEquals("质保释放被重置为未释放", "0", saved.getWarrantyReleased());
        assertNull("质保释放日期被清空", saved.getWarrantyReleaseDate());
        assertEquals("warrantyNote 按本批口径保留", "保留这条备注", saved.getWarrantyNote());
        for (String field : new String[] { "质保金额", "质保比例", "质保起始日", "质保月数", "质量保证金到期日",
                "质保是否释放", "质保释放日期" })
        {
            CtmsChangeLog log = changeLogMapper.lastOf(field);
            assertNotNull("被清空的字段都要留痕：" + field, log);
            assertEquals("清空来源为 auto：" + field, "auto", log.getSource());
        }
    }

    @Test
    public void 操作人为空时详情与历史不报错()
    {
        CtmsContract contract = payload("PURZC202609000174", "无操作人合同");
        service.insertContract(contract);
        service.nickName = null;
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setName("无操作人改名");
        service.updateContract(edit);
        CtmsChangeLog log = changeLogMapper.lastOf("合同名称");
        assertNull("姓名快照拿不到就留 null", log.getOperatorName());
        CtmsContractDetailVo detail = service.selectContractDetail(contract.getId());
        assertNotNull("详情必须正常返回", detail);
        assertFalse("历史列表必须正常返回", detail.getChangeLogs().isEmpty());
    }

    /* ==================== ⑯ 数据范围 ==================== */

    @Test
    public void 范围外访问详情抛403()
    {
        CtmsContract other = seed("OTHER-1", "PURZC202609000180", "别人的合同", "999", "888");
        service.allScope = false;
        service.scopes = new ArrayList<>(Arrays.asList("5"));
        try
        {
            service.selectContractDetail(other.getId());
            fail("范围外访问详情必须被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals("业务码必须是 403（GlobalExceptionHandler 会原样带出 code）",
                    Integer.valueOf(403), e.getCode());
            assertTrue("提示文案：" + e.getMessage(), e.getMessage().contains("无权访问该合同"));
        }
        try
        {
            service.updateContract(other);
            fail("范围外编辑必须被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
        }
        try
        {
            service.softDelete(other.getId(), "越权删除");
            fail("范围外停用必须被拒绝");
        }
        catch (ServiceException e)
        {
            assertEquals(Integer.valueOf(403), e.getCode());
        }
        assertEquals("被拒后行未改动", "0", contractMapper.store.get(other.getId()).getDelFlag());
    }

    @Test
    public void 多角色可见集合取并集()
    {
        CtmsContract row = seed("UNION-1", "PURZC202609000181", "并集合同", "999", "103");
        service.allScope = false;
        service.scopes = new ArrayList<>(Arrays.asList("3", "5"));
        assertTrue("本部门角色命中即可见（本人角色不命中也不影响）", service.canAccess(row));
        service.scopes = new ArrayList<>(Arrays.asList("5"));
        assertFalse("只留仅本人角色时不可见", service.canAccess(row));
    }

    @Test
    public void 本部门范围默认含下级()
    {
        CtmsContract child = seed("DEPT-1", "PURZC202609000182", "子部门合同", "999", "104");
        CtmsContract outsider = seed("DEPT-2", "PURZC202609000183", "别的部门合同", "999", "201");
        service.allScope = false;
        service.scopes = new ArrayList<>(Arrays.asList("3"));
        service.ancestors.put("104", "0,100,103");
        service.ancestors.put("201", "0,200");
        assertTrue("含下级为默认：子部门的合同可见", service.canAccess(child));
        assertFalse("不在子树内不可见", service.canAccess(outsider));
        assertTrue("本部门自己的合同当然可见",
                service.canAccess(seed("DEPT-3", "PURZC202609000184", "本部门合同", "999", "103")));
        // 仅本人（无部门）档位：只按创建人判定
        service.scopes = new ArrayList<>(Arrays.asList("5"));
        assertTrue("仅本人档位：本人创建的合同可见（部门无关）",
                service.canAccess(seed("DEPT-4", "PURZC202609000185", "本人合同", "100", "999")));
        assertFalse("仅本人档位：他人创建的不可见", service.canAccess(outsider));
    }

    @Test
    public void 超管放行全部()
    {
        CtmsContract row = seed("ADMIN-1", "PURZC202609000186", "任意合同", "999", "888");
        service.allScope = true;
        assertTrue("超管/全部档位放行", service.canAccess(row));
        service.allScope = false;
        service.scopes = new ArrayList<>(Arrays.asList("5"));
        assertFalse("取消全部档位后范围外不可见", service.canAccess(row));
    }

    /* ==================== ⑰ 详情只读 ==================== */

    @Test
    public void 打开详情前后合同金额与状态逐字段一致()
    {
        CtmsContract contract = payload("PURZC202609000190", "只读详情合同");
        contract.setItems(items(item(PRODUCT_VALVE, "2", "30.000")));
        contract.setStatus("付款中");
        contract.setArrivalStatus("部分到货");
        contract.setPaidAmount(new BigDecimal("10.00"));
        service.insertContract(contract);
        CtmsContract before = contractMapper.store.get(contract.getId());
        String[] fields = { "amount", "status", "arrivalStatus", "subjectMatter", "paidAmount", "delFlag", "name",
                "type", "warrantyEnd" };
        List<String> snapshot = snapshot(before, fields);

        CtmsContractDetailVo detail = service.selectContractDetail(contract.getId());
        assertNotNull("详情必须返回关联单据区块", detail.getRelatedDocs());
        assertTrue("B3 阶段关联单据固定为空列表（不因关联单据回写合同字段）", detail.getRelatedDocs().isEmpty());
        assertEquals("详情里的合同编号一致", "PURZC202609000190", detail.getContractNo());

        CtmsContract after = contractMapper.store.get(contract.getId());
        assertEquals("打开详情前后合同的每个字段都必须一致", snapshot, snapshot(after, fields));
        assertEquals("金额一致", "60.00", detail.getAmount().toPlainString());
        assertEquals("状态一致", "付款中", detail.getStatus());
    }

    /* ==================== ⑱ 物理删除（夹具/回滚通道） ==================== */

    @Test
    public void 物理删除会清掉行项与标签关联()
    {
        CtmsContract contract = payload("PURZC202609000200", "物理删除合同");
        contract.setItems(items(item(PRODUCT_VALVE, "1", "1.000")));
        service.insertContract(contract);
        assertNotNull(tagMapper.links.get(contract.getId()));
        service.deleteContract(contract.getId());
        assertNull("主表行被删除", contractMapper.store.get(contract.getId()));
        assertNull("行项被删除", itemMapper.byContract.get(contract.getId()));
        Map<String, String> links = tagMapper.links.get(contract.getId());
        assertTrue("标签关联被删除", links == null || links.isEmpty());
    }

    /* ==================== ⑲ 第 5 组：编号 / 付款比例 / 质保提醒 ==================== */

    @Test
    public void 编号留空时服务端按类型码主体码与签订日期生成()
    {
        CtmsContract contract = autoNoPayload("自动编号合同", "PUR", "ZC", at("2025-06-01 00:00:00"));
        service.insertContract(contract);
        assertEquals("3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号",
                "PURZC202506000001", contractMapper.store.get(contract.getId()).getContractNo());
        assertTrue("生成的编号必须是合法格式（同一套正则给迁移导入通道复用）",
                ContractNumberRules.isValidNo(contract.getContractNo()));
    }

    @Test
    public void 序号按类型码与主体码分桶且跨年重置不按月重置()
    {
        // 同年、同类型/主体：月份码随签订日期变，序号连续递增（月份不参与序号）
        CtmsContract sep = autoNoPayload("九月合同", "PUR", "ZC", at("2026-09-15 00:00:00"));
        service.insertContract(sep);
        CtmsContract oct = autoNoPayload("十月合同", "PUR", "ZC", at("2026-10-20 00:00:00"));
        service.insertContract(oct);
        assertEquals("PURZC202609000001", sep.getContractNo());
        assertEquals("同年前缀下序号连续、月份码不参与序号：PURZC202610000002",
                "PURZC202610000002", oct.getContractNo());

        // 换主体码 → 另一个桶，从 000001 起
        CtmsContract otherSubject = autoNoPayload("云羲合同", "PUR", "YX", at("2026-09-20 00:00:00"));
        service.insertContract(otherSubject);
        assertEquals("主体码各自独立计数", "PURYX202609000001", otherSubject.getContractNo());

        // 换类型码 → 另一个桶，从 000001 起
        CtmsContract otherType = autoNoPayload("销售合同", "SAL", "ZC", at("2026-09-21 00:00:00"));
        service.insertContract(otherType);
        assertEquals("类型码各自独立计数", "SALZC202609000001", otherType.getContractNo());

        // 跨年 → 新年份天然从 000001 起（不依赖 1 月 1 日的定时任务）
        CtmsContract nextYear = autoNoPayload("次年合同", "PUR", "ZC", at("2027-01-05 00:00:00"));
        service.insertContract(nextYear);
        assertEquals("跨年重置为 000001（年份进计数器键，与任何定时任务无关）",
                "PURZC202701000001", nextYear.getContractNo());

        // 回到 2026 年桶：不受跨年影响，继续递增
        CtmsContract backTo2026 = autoNoPayload("回到2026合同", "PUR", "ZC", at("2026-11-01 00:00:00"));
        service.insertContract(backTo2026);
        assertEquals("跨年换桶不影响旧年份桶的计数", "PURZC202611000003", backTo2026.getContractNo());
    }

    @Test
    public void 编号预览不占号且连续两次相同()
    {
        String first = service.previewContractNo("PUR", "ZC", "2026-09-15");
        String second = service.previewContractNo("PUR", "ZC", "2026-09-15");
        assertEquals("预览不占号：连续两次返回同一编号", first, second);
        assertEquals("PURZC202609000001", first);

        // 预览之后真正取号，拿到的正是刚才预览的值
        CtmsContract contract = autoNoPayload("预览后登记合同", "PUR", "ZC", at("2026-09-15 00:00:00"));
        service.insertContract(contract);
        assertEquals("预览值与首次取号值一致", first, contract.getContractNo());
    }

    @Test
    public void 编号类型与主体取值校验与预览路径一致()
    {
        // 类型为"已停用/不参与自动编号"（字典里没有 OTH，因为它 status='1'）
        assertRejected("该类型暂不支持自动编号", new Runnable()
        {
            @Override
            public void run()
            {
                service.previewContractNo("OTH", "ZC", "2026-09-15");
            }
        });
        // 未选择主体
        assertRejected("请选择有效主体", new Runnable()
        {
            @Override
            public void run()
            {
                service.previewContractNo("PUR", null, "2026-09-15");
            }
        });
        // 主体不在字典内
        assertRejected("请选择有效主体", new Runnable()
        {
            @Override
            public void run()
            {
                service.previewContractNo("PUR", "ZZ", "2026-09-15");
            }
        });
        // 登记路径与预览同一套校验：类型不可编号时（合同类型为空/非法）第 4 组的"类型不能为空"优先
        final CtmsContract disabledType = autoNoPayload("停用类型合同", "OTH", "ZC", at("2026-09-15 00:00:00"));
        assertRejected("该类型暂不支持自动编号", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(disabledType);
            }
        });
        final CtmsContract noSubject = autoNoPayload("无主体合同", "PUR", null, at("2026-09-15 00:00:00"));
        assertRejected("请选择有效主体", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(noSubject);
            }
        });
        assertEquals("被拒的都不许落库", 0, contractMapper.store.size());
    }

    @Test
    public void 自动编号不去占用已停用合同占用的号()
    {
        // 先占掉 PUR/ZC/2026 的 000001（这份合同随后被停用）
        CtmsContract occupied = autoNoPayload("已停用占号合同", "PUR", "ZC", at("2026-09-01 00:00:00"));
        service.insertContract(occupied);
        assertEquals("PURZC202609000001", occupied.getContractNo());
        service.softDelete(occupied.getId(), "验收：停用占号");

        // 另一个类型码/主体码的桶不受影响
        CtmsContract otherBucket = autoNoPayload("其他桶合同", "PUR", "YX", at("2026-09-02 00:00:00"));
        service.insertContract(otherBucket);
        assertEquals("停用占号不污染其它桶", "PURYX202609000001", otherBucket.getContractNo());

        // 回头手工指定已被（停用合同）占用的编号 → 拒绝
        final CtmsContract reuse = payload("PURZC202609000001", "复用占号合同");
        assertRejected("合同编号已存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContract(reuse);
            }
        });
    }

    /* ==================== t31 / R2：取号判据与上限耗尽 ==================== */

    /**
     * <p> R2 回归（直接钉住 +1001 形态）：<b>库内同桶已有高位号、而取号计数器还在低位</b>时，
     * 连续两次取号的序号差必须是 <b>1</b>，而不是 1001（旧实现下每次取号都会白烧 1000 次重试，
     * 相邻号差 = 重试上限 + 1，与现象精确吻合）。 </p>
     *
     * <p> ⚠ 为什么必须自己构造这个状态：真库上放大该现象的遗留夹具已被清理，
     * "顺手跑一遍真库脚本"再也复现不出来。这里用"先落一条高序号存量合同"把状态造出来，
     * 与 Redis 被清空 / 从快照恢复库 / 编号配置重建后的真实形态一致。 </p>
     */
    @Test
    public void 计数器落后于库内桶水位时相邻取号差为一而不是一千零一()
    {
        // ① 造状态：库内同桶（PUR/ZC/2026）已有一条高序号存量合同；取号计数器仍是低位（桩从 1 起）
        CtmsContract legacy = payload("PURZC202609470813", "存量高序号合同");
        service.insertContract(legacy);
        assertEquals("手工/存量编号通道不消耗自动取号计数器", 0, codeGenService.genCalls);

        // ② 连续两次自动取号
        CtmsContract first = autoNoPayload("计数器落后-第一份", "PUR", "ZC", at("2026-09-15 00:00:00"));
        service.insertContract(first);
        CtmsContract second = autoNoPayload("计数器落后-第二份", "PUR", "ZC", at("2026-09-16 00:00:00"));
        service.insertContract(second);

        int firstSeq = ContractNumberRules.seqOf(first.getContractNo());
        int secondSeq = ContractNumberRules.seqOf(second.getContractNo());
        assertEquals("取号必须从库内桶 max 起步：470813 + 1 = 470814", 470814, firstSeq);
        assertEquals("再取一个：470813 + 2 = 470815（不得回落到计数器低位）", 470815, secondSeq);
        assertEquals("相邻取号差必须是 1（旧实现在这里会是 1001 = 重试上限 + 1）",
                1, secondSeq - firstSeq);
        assertEquals("两次取号只调用 2 次取号服务（没有白烧 1000 次重试）", 2, codeGenService.genCalls);
        assertTrue("抬升后的编号仍必须是合法格式", ContractNumberRules.isValidNo(second.getContractNo()));
    }

    /**
     * <p> R2 回归（正常路径不回归）：计数器<b>领先</b>库内桶时，取号仍按计数器连续递增、
     * 不跳号、不多调取号服务。 </p>
     */
    @Test
    public void 计数器领先库内桶时取号行为不变()
    {
        CtmsContract first = autoNoPayload("正常取号-第一份", "PUR", "ZC", at("2026-09-15 00:00:00"));
        service.insertContract(first);
        CtmsContract second = autoNoPayload("正常取号-第二份", "PUR", "ZC", at("2026-09-15 00:00:00"));
        service.insertContract(second);
        assertEquals("PURZC202609000001", first.getContractNo());
        assertEquals("PURZC202609000002", second.getContractNo());
        assertEquals("两次取号 = 2 次调用（无重试、无跳号）", 2, codeGenService.genCalls);
    }

    /**
     * <p> R2-③ 回归：重试上限耗尽时<b>必须显式失败</b>（业务异常、文案含「取号失败」与「重试上限」），
     * 且<b>不得落库</b> —— 旧实现会在耗尽后照样把（仍然被占用的）编号插进去。 </p>
     *
     * <p> 注入方式：让占用集合每次被读取都"谎报"下一个号也已被占用，等价于
     * "并发写入者永远抢先一步"的最坏情况。 </p>
     */
    @Test
    public void 取号重试上限耗尽时显式失败且不落库()
    {
        CtmsContract base = payload("PURZC202609000500", "占位存量合同");
        service.insertContract(base);

        contractMapper.phantomNextTaken = true;
        final CtmsContract pending = autoNoPayload("上限耗尽合同", "PUR", "ZC", at("2026-09-15 00:00:00"));
        String message = null;
        try
        {
            service.insertContract(pending);
            fail("重试上限耗尽必须抛业务异常，而不是静默插入已占用的编号");
        }
        catch (ServiceException e)
        {
            message = e.getMessage();
        }
        finally
        {
            contractMapper.phantomNextTaken = false;
        }
        assertTrue("文案必须含关键字「取号失败」（脚本可断言）：" + message,
                message != null && message.contains("取号失败"));
        assertTrue("文案必须含关键字「重试上限」：" + message,
                message != null && message.contains("重试上限"));
        // 故障注入会往桩里塞"占号幽灵"行，所以不能按 store 总数判"没落库"，
        // 改按**本次要插的那份合同**判：它必须一个字都没写进去
        boolean persisted = false;
        for (CtmsContract row : contractMapper.store.values())
        {
            if ("上限耗尽合同".equals(row.getName()))
            {
                persisted = true;
            }
        }
        assertFalse("耗尽后不得落库（本次合同一个字都不该写入）", persisted);
        assertNull("耗尽后不应对外暴露编号", pending.getContractNo());
    }
    @Test
    public void 付款比例按累计已付与合同金额计算且金额为零返回空值()
    {
        CtmsContract contract = payload("PURZC202609000300", "付款比例合同");
        contract.setAmount(new BigDecimal("200000.00"));
        contract.setPaidAmount(new BigDecimal("50000.00"));
        service.insertContract(contract);
        CtmsContractDetailVo detail = service.selectContractDetail(contract.getId());
        assertEquals("200000.00 与 50000.00 → 25.00%（4 位 HALF_UP）",
                "25.0000", detail.getPaidRate().toPlainString());

        // 合同金额为 0 → 返回空值而不是报错
        CtmsContract zero = payload("PURZC202609000301", "零金额合同");
        zero.setAmount(BigDecimal.ZERO);
        zero.setPaidAmount(BigDecimal.ZERO);
        service.insertContract(zero);
        assertNull("合同金额为 0 时付款比例为空值",
                service.selectContractDetail(zero.getId()).getPaidRate());
    }

    @Test
    public void 累计已付超出合同金额仍可保存且留痕()
    {
        CtmsContract contract = payload("PURZC202609000302", "超出已付合同");
        contract.setAmount(new BigDecimal("1000.00"));
        contract.setPaidAmount(new BigDecimal("1000.00"));
        service.insertContract(contract);
        CtmsContract edit = new CtmsContract();
        edit.setId(contract.getId());
        edit.setPaidAmount(new BigDecimal("1200.00"));
        service.updateContract(edit);
        assertEquals("超出合同金额的累计已付允许保存",
                "1200.00", contractMapper.store.get(contract.getId()).getPaidAmount().toPlainString());
        CtmsChangeLog log = changeLogMapper.lastOf("累计已付金额");
        assertNotNull("超出金额的编辑必须留痕", log);
        assertEquals("1200.00", log.getNewValue());
        assertEquals("120.0000",
                service.selectContractDetail(contract.getId()).getPaidRate().toPlainString());
    }

    @Test
    public void 质保提醒按窗口边界切分即将到期与已到期()
    {
        // 固定"今天"= 2026-01-05，窗口 30 天（缺省）
        CtmsContract boundary = withWarrantyEnd("窗口边界合同", "2026-02-04");
        CtmsContract inside = withWarrantyEnd("窗口内合同", "2026-01-20");
        CtmsContract justExpired = withWarrantyEnd("刚到期合同", "2026-01-04");
        CtmsContract released = withWarrantyEnd("已释放合同", "2026-01-20");
        released.setWarrantyReleased("1");
        service.updateContract(released);
        CtmsContract disabled = withWarrantyEnd("已停用提醒合同", "2026-01-20");
        service.softDelete(disabled.getId(), "验收：已停用不提醒");

        CtmsWarrantyReminderVo reminders = service.selectWarrantyReminders();
        assertEquals("窗口天数取 sys_config（缺省 30）", 30, reminders.getWindowDays());
        assertEquals("今天取服务端时钟", "2026-01-05", reminders.getToday());
        assertTrue("窗口右端点当天（2026-02-04 = 今天 + 30 天）必须落在「即将到期」",
                ids(reminders.getExpiring()).contains(boundary.getId()));
        assertTrue("窗口内的合同在「即将到期」", ids(reminders.getExpiring()).contains(inside.getId()));
        assertFalse("到期日早于今天 → 只在「已到期」，不在「即将到期」",
                ids(reminders.getExpiring()).contains(justExpired.getId()));
        assertTrue("到期日早于今天 → 在「已到期」",
                ids(reminders.getExpired()).contains(justExpired.getId()));
        assertFalse("已释放的合同不再提醒", ids(reminders.getExpiring()).contains(released.getId()));
        assertFalse("已释放的合同不在已到期列表", ids(reminders.getExpired()).contains(released.getId()));
        assertFalse("已停用合同不提醒", ids(reminders.getExpiring()).contains(disabled.getId()));
        assertFalse("已停用合同不在已到期列表", ids(reminders.getExpired()).contains(disabled.getId()));
    }

    @Test
    public void 质保释放后从两个提醒列表消失()
    {
        CtmsContract contract = withWarrantyEnd("释放闭环合同", "2026-01-20");
        assertTrue("释放前在「即将到期」",
                ids(service.selectWarrantyReminders().getExpiring()).contains(contract.getId()));
        service.releaseWarranty(contract.getId());
        CtmsWarrantyReminderVo after = service.selectWarrantyReminders();
        assertFalse("释放后从「即将到期」消失", ids(after.getExpiring()).contains(contract.getId()));
        assertFalse("释放后从「已到期」消失", ids(after.getExpired()).contains(contract.getId()));
        assertEquals("释放日期落库为服务端当天", "2026-01-05",
                new SimpleDateFormat("yyyy-MM-dd").format(
                        contractMapper.store.get(contract.getId()).getWarrantyReleaseDate()));
    }

    @Test
    public void 质保提醒窗口天数随系统参数变化()
    {
        // 到期日距"今天"（2026-01-05）20 天
        CtmsContract contract = withWarrantyEnd("窗口参数合同", "2026-01-25");
        StubConfigService config = new StubConfigService();
        config.put("warranty_window_days", "30");
        inject(service, "configService", config);
        assertTrue("窗口 30 天时在「即将到期」",
                ids(service.selectWarrantyReminders().getExpiring()).contains(contract.getId()));

        config.put("warranty_window_days", "10");
        CtmsWarrantyReminderVo narrow = service.selectWarrantyReminders();
        assertEquals("窗口天数随参数变化", 10, narrow.getWindowDays());
        assertFalse("窗口缩到 10 天后不再出现在「即将到期」", ids(narrow.getExpiring()).contains(contract.getId()));
        assertFalse("窗口缩小不会把它算成已到期", ids(narrow.getExpired()).contains(contract.getId()));
    }

    /* ==================== ⑳ 第 7 组 7.4：未认领不阻塞展示/导出 + 行项单行写 ==================== */

    /**
     * 「未认领合同正常展示」：10 份未绑定任何档案的历史合同（迁移扫描会为它们生成 pending 草案），
     * 列表与详情都必须正常返回甲乙方<b>文本</b>，不因缺档案引用而报错、隐藏或阻塞。
     */
    @Test
    public void 未认领合同列表与详情正常展示甲乙方文本()
    {
        seedUnclaimedContracts(10);
        CtmsContract query = new CtmsContract();
        List<CtmsContract> list = service.selectContractList(query);
        assertEquals("10 份未绑定档案的合同必须全部返回（不得被隐藏）", 10, list.size());
        for (CtmsContract row : list)
        {
            assertFalse("甲方文本非空：" + row.getId(), isBlank(row.getPartyA()));
            assertFalse("乙方文本非空：" + row.getId(), isBlank(row.getPartyB()));
            assertNull("用例前提：确实没有绑定客户档案", row.getCustomerId());
            assertNull("用例前提：确实没有绑定供应商档案", row.getSupplierId());
        }
        // 清单/详情/导出三条链路都必须经"合同列表那一处取数入口"（而不是各自另开一条旁路）：
        // 同一 query 对象直达 Mapper 就是这条结构证据（范围判定在该入口的服务层完成，见 ⑯ 数据范围用例）
        assertSame("取数必须走同一处入口（同一 query 对象直达 Mapper）", query, contractMapper.lastQuery);

        CtmsContract detail = service.selectContractDetail("MIG-A-1");
        assertEquals("供应商文本1", detail.getPartyB());
        assertEquals("客户文本1", detail.getPartyA());
        assertTrue("详情不因未绑定档案而报错/隐藏", !isBlank(detail.getPartyB()));
    }

    /**
     * 「未认领合同可正常导出」：用 {@code ExcelUtil} 真的生成一个 xlsx，再逐行核对
     * <b>甲方/乙方</b>两列输出的是文本值（任务原文要求的口径）。
     *
     * <p> 导出列定义在 {@code CtmsContractController.ContractExportRow}，行装配走
     * {@code controller.exportRows(query)}（与列表同一处数据范围判定），
     * 最后用与控制器同样的 {@code exportExcel(response, rows, sheetName)} 落成字节流再读回。 </p>
     */
    @Test
    public void 未认领合同可正常导出且甲乙列逐行为文本()
    {
        seedUnclaimedContracts(10);
        CtmsContractController controller = new CtmsContractController();
        inject(controller, "contractService", service);

        CtmsContract query = new CtmsContract();
        List<CtmsContractController.ContractExportRow> rows = controller.exportRows(query);
        assertEquals("导出装配 10 行", 10, rows.size());
        for (CtmsContractController.ContractExportRow row : rows)
        {
            assertFalse("装配出来的甲方列非空", isBlank(row.getPartyA()));
            assertFalse("装配出来的乙方列非空", isBlank(row.getPartyB()));
        }
        // 导出取数走的是合同列表那一个入口（同一 query 对象直达 Mapper），
        // 因此数据范围与列表完全一致；范围外 403 的行为由 ⑯ 数据范围用例与行项写用例覆盖。
        assertSame("导出必须走同一处取数入口（同一 query 对象直达 Mapper）", query, contractMapper.lastQuery);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new ExcelUtil<>(CtmsContractController.ContractExportRow.class)
                .exportExcel(fakeResponse(buffer), rows, "合同台账");
        assertTrue("必须真的产出非空 xlsx 字节流", buffer.size() > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(buffer.toByteArray())))
        {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            int partyACol = -1;
            int partyBCol = -1;
            for (int c = 0; c < header.getLastCellNum(); c++)
            {
                String text = header.getCell(c) == null ? "" : header.getCell(c).toString();
                if ("甲方".equals(text))
                {
                    partyACol = c;
                }
                if ("乙方".equals(text))
                {
                    partyBCol = c;
                }
            }
            assertTrue("表头必须有「甲方」列", partyACol >= 0);
            assertTrue("表头必须有「乙方」列", partyBCol >= 0);
            assertEquals("表头 + 10 行数据", 11, sheet.getLastRowNum() + 1);
            for (int r = 1; r <= 10; r++)
            {
                Row row = sheet.getRow(r);
                assertNotNull("第 " + r + " 行必须存在", row);
                String partyA = row.getCell(partyACol) == null ? "" : row.getCell(partyACol).toString();
                String partyB = row.getCell(partyBCol) == null ? "" : row.getCell(partyBCol).toString();
                assertFalse("第 " + r + " 行甲方列必须是文本且非空，实际=" + partyA, isBlank(partyA));
                assertFalse("第 " + r + " 行乙方列必须是文本且非空，实际=" + partyB, isBlank(partyB));
                assertTrue("甲方列输出的是合同里的文本值，实际=" + partyA, partyA.startsWith("客户文本"));
                assertTrue("乙方列输出的是合同里的文本值，实际=" + partyB, partyB.startsWith("供应商文本"));
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("读回导出的 xlsx 失败", e);
        }
    }

    /**
     * 行项单行写三个入口：新增 / 编辑 / 删除——复用既有"行项全量替换 + 变更历史"链路，
     * 金额仍按 C-1 先舍入再汇总重算，且不产生孤儿行。
     */
    @Test
    public void 行项新增编辑删除复用既有口径且不产生孤儿行()
    {
        CtmsContract contract = payload("PURZC202609000700", "行项写端点合同");
        service.insertContract(contract);
        String contractId = contract.getId();

        // ① 新增一行：1 × 1.665 → 行总价 1.67（先舍入）
        CtmsContractItem first = item(PRODUCT_VALVE, "1", "1.665");
        first.setContractId(contractId);
        CtmsContractItem saved = service.insertContractItem(first);
        assertNotNull("新增必须返回带主键的行项（主键由服务端生成）", saved.getId());
        assertEquals(Integer.valueOf(1), saved.getSeq());
        assertEquals("物料快照由档案回填", "球阀", saved.getName());
        assertEquals("行总价先舍入到 2 位", "1.67", saved.getTotal().toPlainString());
        assertEquals("合同金额按 C-1 由行项重算", "1.67",
                contractMapper.store.get(contractId).getAmount().toPlainString());
        assertEquals("行项已落库", 1, itemMapper.selectItemList(queryOf(contractId)).size());

        // ② 再新增一行：1 × 0.005 → 0.01；若"先汇总再舍入"会得 1.67，这里必须是 1.68
        CtmsContractItem second = item(PRODUCT_FLANGE, "1", "0.005");
        second.setContractId(contractId);
        CtmsContractItem savedSecond = service.insertContractItem(second);
        assertEquals(Integer.valueOf(2), savedSecond.getSeq());
        assertEquals("先舍入再汇总（1.67 + 0.01）", "1.68",
                contractMapper.store.get(contractId).getAmount().toPlainString());
        assertEquals("两行行项", 2, itemMapper.selectItemList(queryOf(contractId)).size());

        // ③ 编辑第一行：改数量与单价 → 金额跟着重算
        CtmsContractItem edit = item(PRODUCT_VALVE, "2", "1.665");
        edit.setId(saved.getId());
        edit.setContractId(contractId);
        CtmsContractItem edited = service.updateContractItem(edit);
        assertEquals("2 × 1.665 → 3.33", "3.33", edited.getTotal().toPlainString());
        assertEquals("合同金额 = 3.33 + 0.01", "3.34",
                contractMapper.store.get(contractId).getAmount().toPlainString());
        assertEquals("编辑不新增行", 2, itemMapper.selectItemList(queryOf(contractId)).size());

        // ④ 删除第二行 → 只剩一行
        service.deleteContractItem(savedSecond.getId());
        assertEquals("删除后只剩一行", 1, itemMapper.selectItemList(queryOf(contractId)).size());

        // ⑤ 变更历史：每次行项写都留一条 _items，且金额/摘要按自动来源留痕
        assertTrue("必须存在 _items 变更历史（复用既有口径，不是第二套写入）",
                hasChangeLog(contractId, "_items"));
        assertTrue("金额重算必须留痕", hasChangeLog(contractId, "合同金额"));

        // ⑥ 不产生孤儿行：每一条行项都指向一份真实存在的合同
        for (List<CtmsContractItem> rows : itemRows())
        {
            for (CtmsContractItem row : rows)
            {
                assertNotNull("行项必须指向存在的合同：" + row.getId(),
                        contractMapper.store.get(row.getContractId()));
            }
        }
    }

    /**
     * 行项写端点的守卫：不存在 / 停用 / 跨合同搬迁 / 非法入参都必须被拒并给出确定文案。
     */
    @Test
    public void 行项写端点的守卫齐全()
    {
        CtmsContract contract = payload("PURZC202609000701", "守卫合同");
        service.insertContract(contract);
        final String contractId = contract.getId();

        final CtmsContractItem ok = item(PRODUCT_VALVE, "1", "1.000");
        ok.setContractId(contractId);
        CtmsContractItem saved = service.insertContractItem(ok);

        // 合同不存在
        final CtmsContractItem ghostContract = item(PRODUCT_VALVE, "1", "1.000");
        ghostContract.setContractId("NOT-EXIST");
        assertRejected("合同不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContractItem(ghostContract);
            }
        });
        // 行项必须指定所属合同
        final CtmsContractItem noContract = item(PRODUCT_VALVE, "1", "1.000");
        assertRejected("行项必须指定所属合同", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContractItem(noContract);
            }
        });
        // 行项不存在（编辑 / 删除）
        final CtmsContractItem ghostItem = item(PRODUCT_VALVE, "1", "1.000");
        ghostItem.setId("NOT-EXIST");
        assertRejected("行项不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContractItem(ghostItem);
            }
        });
        assertRejected("行项不存在", new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteContractItem("NOT-EXIST");
            }
        });
        // 不允许把行项搬到另一份合同（否则等于绕过两份合同各自的数据范围）
        CtmsContract other = payload("PURZC202609000702", "另一份合同");
        service.insertContract(other);
        final CtmsContractItem moved = item(PRODUCT_VALVE, "1", "1.000");
        moved.setId(saved.getId());
        moved.setContractId(other.getId());
        assertRejected("行项不属于该合同", new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContractItem(moved);
            }
        });
        // 已停用合同不允许写行项
        service.softDelete(contractId, "守卫用例停用");
        final CtmsContractItem onStopped = item(PRODUCT_VALVE, "1", "1.000");
        onStopped.setContractId(contractId);
        assertRejected("合同已停用，请先恢复", new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContractItem(onStopped);
            }
        });
        assertEquals("被拒后行项数不变", 1, itemMapper.selectItemList(queryOf(contractId)).size());
    }

    /**
     * 行项写端点同样先做数据范围判定：范围外一律业务码 403（复用合同侧唯一入口，不新写一份判定）。
     */
    @Test
    public void 行项写端点在范围外抛403()
    {
        CtmsContract other = seed("OTHER-ITEM-1", "PURZC202609000703", "别人的行项合同", "999", "888");
        CtmsContractItem row = item(PRODUCT_VALVE, "1", "1.000");
        row.setId("ITEM-OF-OTHER");
        row.setContractId(other.getId());
        itemMapper.batchInsertItems(items(row));

        service.allScope = false;
        service.scopes = new ArrayList<>(Arrays.asList("5"));
        final CtmsContractItem add = item(PRODUCT_VALVE, "1", "1.000");
        add.setContractId(other.getId());
        assertForbidden(new Runnable()
        {
            @Override
            public void run()
            {
                service.insertContractItem(add);
            }
        });
        final CtmsContractItem edit = item(PRODUCT_VALVE, "1", "1.000");
        edit.setId("ITEM-OF-OTHER");
        assertForbidden(new Runnable()
        {
            @Override
            public void run()
            {
                service.updateContractItem(edit);
            }
        });
        assertForbidden(new Runnable()
        {
            @Override
            public void run()
            {
                service.deleteContractItem("ITEM-OF-OTHER");
            }
        });
        assertEquals("被拒后行项未被改动", 1, itemMapper.selectItemList(queryOf(other.getId())).size());
    }

    /* ==================== 小工具 ==================== */

    /**
     * 造 N 份「未绑定任何档案、只有甲乙方文本」的历史合同（迁移期形态；编号合法以便后续走真实写链路）。
     *
     * @param count 份数
     */
    private void seedUnclaimedContracts(int count)
    {
        for (int i = 1; i <= count; i++)
        {
            CtmsContract contract = seed("MIG-A-" + i, String.format("PURZC202609%06d", i),
                    "未认领合同" + i, "900", "888");
            contract.setPartyA("客户文本" + i);
            contract.setPartyB("供应商文本" + i);
        }
    }

    /**
     * 空白判定（转发到与生产同源的规则，避免测试里出现第二份口径）。
     *
     * @param value 值
     * @return 空白返回 true
     */
    private static boolean isBlank(String value)
    {
        return ContractRules.isBlank(value);
    }

    /**
     * 按合同查行项的查询对象。
     *
     * @param contractId 合同ID
     * @return 查询对象
     */
    private static CtmsContractItem queryOf(String contractId)
    {
        CtmsContractItem query = new CtmsContractItem();
        query.setContractId(contractId);
        return query;
    }

    /**
     * 桩库里的全部行项（按合同分组）。
     *
     * @return 行项分组
     */
    private List<List<CtmsContractItem>> itemRows()
    {
        return new ArrayList<>(itemMapper.byContract.values());
    }

    /**
     * 是否存在指定合同、指定字段名的变更历史。
     *
     * @param contractId 合同ID
     * @param fieldName  字段名
     * @return 存在返回 true
     */
    private boolean hasChangeLog(String contractId, String fieldName)
    {
        for (CtmsChangeLog log : changeLogMapper.store)
        {
            if (same(log.getContractId(), contractId) && fieldName.equals(log.getFieldName()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 断言某段逻辑因数据范围被拒（业务码 403 + 统一文案）。
     *
     * @param runnable 待执行逻辑
     */
    private static void assertForbidden(Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("范围外必须被拒绝（业务码 403）");
        }
        catch (ServiceException e)
        {
            assertEquals("业务码必须是 403（GlobalExceptionHandler 会原样带出 code）",
                    Integer.valueOf(403), e.getCode());
            assertTrue("提示文案：" + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains("无权访问该合同"));
        }
    }

    /**
     * 伪造一个只支持 {@code getOutputStream()} 的 {@link HttpServletResponse}，
     * 用来把 {@code ExcelUtil.exportExcel(response, ...)} 的产物接进内存字节流
     * （这样单测核对的就是"控制器真正走的那条导出通路"，而不是另写一份生成逻辑）。
     *
     * @param buffer 输出缓冲
     * @return 伪造响应
     */
    private static HttpServletResponse fakeResponse(final ByteArrayOutputStream buffer)
    {
        return (HttpServletResponse) Proxy.newProxyInstance(
                CtmsContractServiceImplTest.class.getClassLoader(),
                new Class<?>[] { HttpServletResponse.class },
                new InvocationHandler()
                {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                    {
                        String name = method.getName();
                        if ("getOutputStream".equals(name))
                        {
                            return new ServletOutputStream()
                            {
                                @Override
                                public void write(int b)
                                {
                                    buffer.write(b);
                                }

                                @Override
                                public boolean isReady()
                                {
                                    return true;
                                }

                                @Override
                                public void setWriteListener(javax.servlet.WriteListener writeListener)
                                {
                                    // 内存泄漏：不需要异步写监听
                                }
                            };
                        }
                        if ("toString".equals(name))
                        {
                            return "fake-excel-response";
                        }
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class)
                        {
                            return Boolean.FALSE;
                        }
                        if (type == int.class)
                        {
                            return Integer.valueOf(0);
                        }
                        if (type == long.class)
                        {
                            return Long.valueOf(0L);
                        }
                        return null;
                    }
                });
    }

    /**
     * 造一份最小可保存的合同。
     *
     * @param contractNo 编号
     * @param name       名称
     * @return 合同
     */
    private static CtmsContract payload(String contractNo, String name)
    {
        CtmsContract contract = new CtmsContract();
        contract.setContractNo(contractNo);
        contract.setName(name);
        // 类型存的是 contract_types 字典的 dict_value（3 位类型码，同时是编号的类型码）
        contract.setType("PUR");
        return contract;
    }

    /**
     * 造一份"留空编号、走服务端自动编号"的合同（任务 5.8 的默认登记路径）。
     *
     * @param name         名称
     * @param type         类型码
     * @param subjectCode  主体码
     * @param signDate     签订日期
     * @return 合同
     */
    private static CtmsContract autoNoPayload(String name, String type, String subjectCode, Date signDate)
    {
        CtmsContract contract = new CtmsContract();
        contract.setName(name);
        contract.setType(type);
        contract.setSubjectCode(subjectCode);
        contract.setSignDate(signDate);
        return contract;
    }

    /**
     * 造一份"启用质保、到期日精确指定"的合同（任务 5.6 的提醒口径用）。
     *
     * <p> 质保到期日刻意用 SQL 直改：登记路径会按算法重算到期日（这是规格要求的），
     * 但提醒用例要的边界是"任意指定的一天"，用算法反推既绕又不好读。 </p>
     *
     * @param name        名称
     * @param warrantyEnd 到期日（{@code yyyy-MM-dd}）
     * @return 合同
     */
    private CtmsContract withWarrantyEnd(String name, String warrantyEnd)
    {
        // 编号留空 → 走服务端自动编号（顺便证明"提醒用例走的也是真实登记链路"）
        CtmsContract contract = autoNoPayload(name, "PUR", "ZC", at("2026-01-02 00:00:00"));
        contract.setHasWarranty("1");
        contract.setWarrantyStart(at("2025-01-01 00:00:00"));
        contract.setWarrantyMonths(Integer.valueOf(12));
        contract.setWarrantyReleased("0");
        service.insertContract(contract);
        CtmsContract stored = contractMapper.store.get(contract.getId());
        stored.setWarrantyEnd(ContractRules.parseDay(warrantyEnd));
        return stored;
    }

    /**
     * 取合同集合的主键集合（提醒列表的成员判定用）。
     *
     * @param contracts 合同集合
     * @return 主键集合
     */
    private static List<String> ids(List<CtmsContract> contracts)
    {
        List<String> result = new ArrayList<>();
        if (contracts != null)
        {
            for (CtmsContract contract : contracts)
            {
                result.add(contract.getId());
            }
        }
        return result;
    }

    /**
     * 造一个行项。
     *
     * @param productId 物料ID（可为 null，用于测"未选物料"）
     * @param qty       数量文本
     * @param price     单价文本
     * @return 行项
     */
    private static CtmsContractItem item(String productId, String qty, String price)
    {
        CtmsContractItem item = new CtmsContractItem();
        item.setProductId(productId);
        if (qty != null)
        {
            item.setQty(new BigDecimal(qty));
        }
        if (price != null)
        {
            item.setUnitPrice(new BigDecimal(price));
        }
        return item;
    }

    /**
     * 行项集合。
     *
     * @param items 行项
     * @return 集合
     */
    private static List<CtmsContractItem> items(CtmsContractItem... items)
    {
        return new ArrayList<>(Arrays.asList(items));
    }

    /**
     * 解析 {@code yyyy-MM-dd HH:mm:ss} 时间（固定时间注入用）。
     *
     * @param text 文本
     * @return 时间
     */
    private static Date at(String text)
    {
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text);
        }
        catch (ParseException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 反射注入服务里的 {@code @Autowired} 字段（与既有主数据单测同款手法）。
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
                // 字段声明在父类（例如 TestContractService 继承的服务实现）→ 继续往上找
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
     * 直接往桩库塞一行（数据范围用例要构造"别人创建的合同"）。
     *
     * @param id       主键
     * @param no       编号
     * @param name     名称
     * @param createId 创建人
     * @param deptId   部门
     * @return 合同
     */
    private CtmsContract seed(String id, String no, String name, String createId, String deptId)
    {
        CtmsContract contract = new CtmsContract();
        contract.setId(id);
        contract.setContractNo(no);
        contract.setName(name);
        contract.setType("PUR");
        contract.setStatus("内部审批中");
        contract.setArrivalStatus("未到货");
        contract.setAmount(new BigDecimal("0.00"));
        contract.setPaidAmount(new BigDecimal("0.00"));
        contract.setDelFlag("0");
        contract.setCreateId(createId);
        contract.setDeptId(deptId);
        contractMapper.store.put(id, contract);
        return contract;
    }

    /**
     * 登记一份框架合同。
     *
     * @param no   编号
     * @param name 名称
     * @return 框架合同
     */
    private CtmsContract insertFramework(String no, String name)
    {
        CtmsContract framework = payload(no, name);
        framework.setIsFramework("1");
        service.insertContract(framework);
        return framework;
    }

    /**
     * 取若干个字段的字符串快照（前后比对用）。
     *
     * @param contract 合同
     * @param fields   字段名
     * @return 快照
     */
    private static List<String> snapshot(CtmsContract contract, String[] fields)
    {
        List<String> values = new ArrayList<>();
        for (String field : fields)
        {
            values.add(field + "=" + String.valueOf(read(contract, field)));
        }
        return values;
    }

    /**
     * 按字段名读值（只覆盖本用例用到的字段）。
     *
     * @param contract 合同
     * @param field    字段名
     * @return 值
     */
    private static Object read(CtmsContract contract, String field)
    {
        if ("amount".equals(field))
        {
            return contract.getAmount() == null ? null : contract.getAmount().toPlainString();
        }
        if ("paidAmount".equals(field))
        {
            return contract.getPaidAmount() == null ? null : contract.getPaidAmount().toPlainString();
        }
        if ("status".equals(field))
        {
            return contract.getStatus();
        }
        if ("arrivalStatus".equals(field))
        {
            return contract.getArrivalStatus();
        }
        if ("subjectMatter".equals(field))
        {
            return contract.getSubjectMatter();
        }
        if ("delFlag".equals(field))
        {
            return contract.getDelFlag();
        }
        if ("name".equals(field))
        {
            return contract.getName();
        }
        if ("type".equals(field))
        {
            return contract.getType();
        }
        if ("warrantyEnd".equals(field))
        {
            return contract.getWarrantyEnd() == null ? null
                    : new SimpleDateFormat("yyyy-MM-dd").format(contract.getWarrantyEnd());
        }
        return null;
    }

    /* ==================== 可注入点的服务子类 ==================== */

    /**
     * <p> 把"当前时间 / 当前用户 / 数据范围档位"换成固定值的服务子类。 </p>
     *
     * <p> 这些点在生产实现里分别是 {@code now()}、{@code currentUserId()} 等 protected 方法，
     * 这样 30 天窗口、服务端快照、多角色并集都能在没有 Spring 与真实时钟的情况下精确断言。 </p>
     */
    private class TestContractService extends CtmsContractServiceImpl
    {
        private Date fixedNow = at("2026-01-05 10:00:00");

        private String userId = "100";

        private String username = "zhangsan";

        private String nickName = "张三";

        private String deptId = "103";

        private boolean allScope = true;

        private List<String> scopes = new ArrayList<>(Arrays.asList("1"));

        private final Map<String, String> ancestors = new HashMap<>();

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
            return nickName;
        }

        @Override
        protected String currentDeptId()
        {
            return deptId;
        }

        @Override
        protected boolean hasAllDataScope()
        {
            return allScope;
        }

        @Override
        protected List<String> currentDataScopes()
        {
            return scopes;
        }

        @Override
        protected String deptAncestors(String targetDeptId)
        {
            return ancestors.get(targetDeptId);
        }
    }

    /* ==================== 内存桩 ==================== */

    /** 合同桩：关键字按编号/名称/甲方/乙方模糊匹配（模拟 Mapper 的关键字口径）。 */
    private static class StubContractMapper implements CtmsContractMapper
    {
        private final Map<String, CtmsContract> store = new LinkedHashMap<>();

        private CtmsContract lastQuery;

        /** 故障注入（t31 / R2-③）：每次读占用集合都谎报"下一个号也被占用"。 */
        private boolean phantomNextTaken;

        /**
         * 迁移认领的批量绑定（任务 7.3）：本合同用例不涉及认领，只把契约补齐；
         * 认领的行为断言在 {@code CtmsMigrationServiceImplTest} 里做（那边有草案桩与档案桩）。
         */
        @Override
        public List<CtmsContract> selectUnboundPartyCandidates(String partyType, String rawName)
        {
            return new ArrayList<>();
        }

        @Override
        public int bindPartyRef(String id, String partyType, String partyId, String updateId, String updateBy)
        {
            // 本合同用例不涉及认领；真库行为（只改引用列）由 CtmsMigrationServiceImplTest 断言
            return 0;
        }

        @Override
        public List<CtmsContract> selectContractList(CtmsContract query)
        {
            lastQuery = query;
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                if (query != null)
                {
                    if (!"1".equals(query.getIncludeDeleted()) && "1".equals(row.getDelFlag()))
                    {
                        continue;
                    }
                    String keyword = query.getKeyword();
                    if (keyword != null && !keyword.isEmpty() && !hit(row, keyword))
                    {
                        continue;
                    }
                }
                result.add(copy(row));
            }
            return result;
        }

        private static boolean hit(CtmsContract row, String keyword)
        {
            return contains(row.getContractNo(), keyword) || contains(row.getName(), keyword)
                    || contains(row.getPartyA(), keyword) || contains(row.getPartyB(), keyword);
        }

        private static boolean contains(String value, String keyword)
        {
            return value != null && value.contains(keyword);
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
                if (same(row.getContractNo(), contractNo))
                {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public int insertContract(CtmsContract contract)
        {
            store.put(contract.getId(), copy(contract));
            return 1;
        }

        @Override
        public int updateContract(CtmsContract contract)
        {
            if (!store.containsKey(contract.getId()))
            {
                return 0;
            }
            store.put(contract.getId(), copy(contract));
            return 1;
        }

        @Override
        public int updateContractDelFlag(CtmsContract contract)
        {
            if (!store.containsKey(contract.getId()))
            {
                return 0;
            }
            store.put(contract.getId(), copy(contract));
            return 1;
        }

        @Override
        public int restoreContract(String id, String updateId, String updateBy)
        {
            CtmsContract row = store.get(id);
            if (row == null)
            {
                return 0;
            }
            row.setDelFlag("0");
            row.setDeletedAt(null);
            row.setDeletedReason(null);
            row.setUpdateId(updateId);
            row.setUpdateBy(updateBy);
            return 1;
        }

        @Override
        public int deleteContractById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        @Override
        public List<CtmsContract> selectChildren(String parentId)
        {
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                if (same(row.getParentId(), parentId) && !"1".equals(row.getDelFlag()))
                {
                    result.add(copy(row));
                }
            }
            return result;
        }

        @Override
        public int countActiveChildren(String parentId)
        {
            int count = 0;
            for (CtmsContract row : store.values())
            {
                if (same(row.getParentId(), parentId) && !"1".equals(row.getDelFlag()))
                {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countContractReferences(String tagId)
        {
            return 0;
        }

        @Override
        public List<CtmsContract> selectWarrantyReminderCandidates(Date windowEnd, String dataScopeSql, int limit)
        {
            List<CtmsContract> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                if (!"1".equals(row.getHasWarranty()) || !"0".equals(row.getWarrantyReleased())
                        || row.getWarrantyEnd() == null || "1".equals(row.getDelFlag()))
                {
                    continue;
                }
                // 模拟 SQL 的 warranty_end <= #{windowEnd}（只比日期，不看时刻）
                long endDay = ContractRules.atStartOfDay(row.getWarrantyEnd()).getTime();
                long windowDay = ContractRules.atStartOfDay(windowEnd).getTime();
                if (endDay > windowDay)
                {
                    continue;
                }
                result.add(copy(row));
                if (result.size() >= limit)
                {
                    break;
                }
            }
            return result;
        }

        @Override
        public List<String> selectAllContractNos()
        {
            List<String> result = new ArrayList<>();
            for (CtmsContract row : store.values())
            {
                if (row.getContractNo() != null && !row.getContractNo().isEmpty())
                {
                    result.add(row.getContractNo());
                }
            }
            if (phantomNextTaken && !result.isEmpty())
            {
                // 故障注入（t31 / R2-③）：每次读占用集合都额外"谎报"下一个号已被占用**并真的写进 store**，
                // 模拟"并发写入者永远抢先一步、把下一个号用掉"。用它来验证重试上限耗尽时
                // 必须显式失败（抛异常且不落库），而不是静默插入一个已占用的号。
                String template = result.get(0);
                int max = 0;
                for (String no : result)
                {
                    int seq = ContractNumberRules.seqOf(no);
                    if (seq > max)
                    {
                        max = seq;
                        template = no;
                    }
                }
                String phantom = ContractNumberRules.buildNo(template, max + 1);
                if (phantom != null)
                {
                    result.add(phantom);
                    CtmsContract ghost = new CtmsContract();
                    ghost.setId("GHOST-" + max);
                    ghost.setContractNo(phantom);
                    ghost.setName("并发写入者占号（故障注入）");
                    store.put(ghost.getId(), ghost);
                }
            }
            return result;
        }

        private static CtmsContract copy(CtmsContract src)
        {
            CtmsContract c = new CtmsContract();
            c.setId(src.getId());
            c.setContractNo(src.getContractNo());
            c.setName(src.getName());
            c.setType(src.getType());
            c.setPartyA(src.getPartyA());
            c.setPartyB(src.getPartyB());
            c.setSignDate(src.getSignDate());
            c.setEffectiveDate(src.getEffectiveDate());
            c.setSubjectMatter(src.getSubjectMatter());
            c.setAmount(src.getAmount());
            c.setCurrency(src.getCurrency());
            c.setSubjectCode(src.getSubjectCode());
            c.setCustomerId(src.getCustomerId());
            c.setSupplierId(src.getSupplierId());
            c.setPaidAmount(src.getPaidAmount());
            c.setHasWarranty(src.getHasWarranty());
            c.setWarrantyAmount(src.getWarrantyAmount());
            c.setWarrantyRate(src.getWarrantyRate());
            c.setWarrantyStart(src.getWarrantyStart());
            c.setWarrantyMonths(src.getWarrantyMonths());
            c.setWarrantyEnd(src.getWarrantyEnd());
            c.setWarrantyReleased(src.getWarrantyReleased());
            c.setWarrantyReleaseDate(src.getWarrantyReleaseDate());
            c.setWarrantyNote(src.getWarrantyNote());
            c.setIsFramework(src.getIsFramework());
            c.setParentId(src.getParentId());
            c.setArrivalStatus(src.getArrivalStatus());
            c.setExpectedArrivalDate(src.getExpectedArrivalDate());
            c.setStatus(src.getStatus());
            c.setOwnerName(src.getOwnerName());
            c.setDeptId(src.getDeptId());
            c.setDelFlag(src.getDelFlag());
            c.setDeletedAt(src.getDeletedAt());
            c.setDeletedReason(src.getDeletedReason());
            c.setCreateId(src.getCreateId());
            c.setUpdateId(src.getUpdateId());
            c.setRemark(src.getRemark());
            c.setCreateBy(src.getCreateBy());
            c.setCreateTime(src.getCreateTime());
            c.setUpdateBy(src.getUpdateBy());
            c.setUpdateTime(src.getUpdateTime());
            c.setTagIds(src.getTagIds());
            c.setItems(src.getItems());
            c.setPaidRate(src.getPaidRate());
            return c;
        }
    }

    /** 行项桩：按 contract_id 分组，delete + batchInsert 即"全量替换"。 */
    private static class StubItemMapper implements CtmsContractItemMapper
    {
        private final Map<String, List<CtmsContractItem>> byContract = new LinkedHashMap<>();

        @Override
        public List<CtmsContractItem> selectItemList(CtmsContractItem query)
        {
            List<CtmsContractItem> result = new ArrayList<>();
            if (query == null)
            {
                return result;
            }
            List<CtmsContractItem> rows = byContract.get(query.getContractId());
            if (rows != null)
            {
                result.addAll(rows);
            }
            return result;
        }

        @Override
        public List<CtmsContractItem> selectItemsByContractIds(List<String> contractIds)
        {
            List<CtmsContractItem> result = new ArrayList<>();
            if (contractIds != null)
            {
                for (String contractId : contractIds)
                {
                    List<CtmsContractItem> rows = byContract.get(contractId);
                    if (rows != null)
                    {
                        result.addAll(rows);
                    }
                }
            }
            return result;
        }

        @Override
        public CtmsContractItem selectItemById(String id)
        {
            for (List<CtmsContractItem> rows : byContract.values())
            {
                for (CtmsContractItem item : rows)
                {
                    if (same(item.getId(), id))
                    {
                        return item;
                    }
                }
            }
            return null;
        }

        @Override
        public int deleteItemsByContractId(String contractId)
        {
            List<CtmsContractItem> removed = byContract.remove(contractId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public int batchInsertItems(List<CtmsContractItem> items)
        {
            if (items == null)
            {
                return 0;
            }
            for (CtmsContractItem item : items)
            {
                List<CtmsContractItem> rows = byContract.get(item.getContractId());
                if (rows == null)
                {
                    rows = new ArrayList<>();
                    byContract.put(item.getContractId(), rows);
                }
                rows.add(item);
            }
            return items.size();
        }

        @Override
        public int countByProductId(String productId)
        {
            int count = 0;
            for (List<CtmsContractItem> rows : byContract.values())
            {
                for (CtmsContractItem item : rows)
                {
                    if (same(item.getProductId(), productId))
                    {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    /** 标签桩：{@code links} 模拟 {@code t_ctms_contract_tag} 的 (contract_id, tag_id) → auto。 */
    private static class StubTagMapper implements CtmsTagMapper
    {
        private final Map<String, CtmsTag> store = new LinkedHashMap<>();

        private final Map<String, Map<String, String>> links = new LinkedHashMap<>();

        private final CtmsContractMapper contractMapper;

        StubTagMapper(CtmsContractMapper contractMapper)
        {
            this.contractMapper = contractMapper;
        }

        String tagIdOf(String name)
        {
            for (CtmsTag tag : store.values())
            {
                if (same(tag.getName(), name))
                {
                    return tag.getId();
                }
            }
            return null;
        }

        @Override
        public List<CtmsTag> selectTagList(CtmsTag query)
        {
            List<CtmsTag> result = new ArrayList<>();
            for (CtmsTag tag : store.values())
            {
                if (query != null && query.getName() != null && !query.getName().isEmpty()
                        && (tag.getName() == null || !tag.getName().contains(query.getName())))
                {
                    continue;
                }
                result.add(copy(tag));
            }
            return result;
        }

        @Override
        public CtmsTag selectTagById(String id)
        {
            CtmsTag tag = store.get(id);
            return tag == null ? null : copy(tag);
        }

        @Override
        public CtmsTag selectTagByName(String name)
        {
            for (CtmsTag tag : store.values())
            {
                if (same(tag.getName(), name))
                {
                    return copy(tag);
                }
            }
            return null;
        }

        @Override
        public int countTag()
        {
            return store.size();
        }

        @Override
        public int insertTag(CtmsTag tag)
        {
            store.put(tag.getId(), copy(tag));
            return 1;
        }

        @Override
        public int updateTag(CtmsTag tag)
        {
            if (!store.containsKey(tag.getId()))
            {
                return 0;
            }
            store.put(tag.getId(), copy(tag));
            return 1;
        }

        @Override
        public int deleteTagById(String id)
        {
            return store.remove(id) == null ? 0 : 1;
        }

        @Override
        public List<CtmsTag> selectTagsByContractId(String contractId)
        {
            List<CtmsTag> result = new ArrayList<>();
            Map<String, String> contractLinks = links.get(contractId);
            if (contractLinks == null)
            {
                return result;
            }
            for (Map.Entry<String, String> entry : contractLinks.entrySet())
            {
                CtmsTag tag = store.get(entry.getKey());
                if (tag == null)
                {
                    continue;
                }
                CtmsTag view = copy(tag);
                view.setAuto(entry.getValue());
                result.add(view);
            }
            return result;
        }

        @Override
        public int insertContractTag(String contractId, String tagId, String auto, String createId, String createBy)
        {
            Map<String, String> contractLinks = links.get(contractId);
            if (contractLinks == null)
            {
                contractLinks = new LinkedHashMap<>();
                links.put(contractId, contractLinks);
            }
            // 复合主键 + insert ignore：已存在则静默忽略
            if (!contractLinks.containsKey(tagId))
            {
                contractLinks.put(tagId, auto);
                return 1;
            }
            return 0;
        }

        @Override
        public int deleteContractTag(String contractId, String tagId)
        {
            Map<String, String> contractLinks = links.get(contractId);
            if (contractLinks == null)
            {
                return 0;
            }
            return contractLinks.remove(tagId) == null ? 0 : 1;
        }

        @Override
        public int deleteAutoContractTags(String contractId)
        {
            Map<String, String> contractLinks = links.get(contractId);
            if (contractLinks == null)
            {
                return 0;
            }
            List<String> autoTagIds = new ArrayList<>();
            for (Map.Entry<String, String> entry : contractLinks.entrySet())
            {
                if ("1".equals(entry.getValue()))
                {
                    autoTagIds.add(entry.getKey());
                }
            }
            for (String tagId : autoTagIds)
            {
                contractLinks.remove(tagId);
            }
            return autoTagIds.size();
        }

        private static CtmsTag copy(CtmsTag src)
        {
            CtmsTag tag = new CtmsTag();
            tag.setId(src.getId());
            tag.setName(src.getName());
            tag.setColor(src.getColor());
            tag.setBuiltin(src.getBuiltin());
            tag.setCreateId(src.getCreateId());
            tag.setUpdateId(src.getUpdateId());
            tag.setAuto(src.getAuto());
            return tag;
        }
    }

    /** 变更历史桩：只追加，可按字段名取最后一条。 */
    private static class StubChangeLogMapper implements CtmsChangeLogMapper
    {
        private final List<CtmsChangeLog> store = new ArrayList<>();

        @Override
        public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
        {
            List<CtmsChangeLog> result = new ArrayList<>();
            int limit = query != null && query.getLimit() != null ? query.getLimit().intValue() : Integer.MAX_VALUE;
            for (CtmsChangeLog log : store)
            {
                if (query != null && query.getContractId() != null
                        && !query.getContractId().equals(log.getContractId()))
                {
                    continue;
                }
                if (result.size() >= limit)
                {
                    break;
                }
                result.add(log);
            }
            return result;
        }

        @Override
        public int insertChangeLog(CtmsChangeLog log)
        {
            store.add(log);
            return 1;
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

        /**
         * 取指定字段名最后一条历史。
         *
         * @param fieldName 字段名
         * @return 历史；不存在返回 null
         */
        CtmsChangeLog lastOf(String fieldName)
        {
            for (int i = store.size() - 1; i >= 0; i--)
            {
                if (same(store.get(i).getFieldName(), fieldName))
                {
                    return store.get(i);
                }
            }
            return null;
        }

        /**
         * 取指定字段名的最后一条历史的新值（用于断言"没有写这条"）。
         *
         * @param fieldName 字段名
         * @return 新值；无记录返回 null
         */
        String lastOfNewValue(String fieldName)
        {
            CtmsChangeLog log = lastOf(fieldName);
            return log == null ? null : log.getNewValue();
        }
    }

    /** 往来单位桩：档案存在性与名称快照的最小实现。 */
    private static class StubPartnerService extends CtmsPartnerServiceImpl
    {
        private final Map<String, CtmsCustomer> customers = new LinkedHashMap<>();

        private final Map<String, CtmsSupplier> suppliers = new LinkedHashMap<>();

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
    }

    /** 物料桩：只实现行项需要的按主键取物料。 */
    private static class StubProductMasterService extends CtmsProductMasterServiceImpl
    {
        private final Map<String, CtmsProduct> store = new LinkedHashMap<>();

        void add(String id, String name, String spec, String code)
        {
            CtmsProduct product = new CtmsProduct();
            product.setId(id);
            product.setName(name);
            product.setSpec(spec);
            product.setCode(code);
            store.put(id, product);
        }

        @Override
        public CtmsProduct selectProductById(String id)
        {
            return store.get(id);
        }
    }

    /**
     * <p> 编号服务桩（任务 5.8）：只实现 {@code ruoyi-serial} 的<b>分桶 + 渲染</b>语义，
     * 不碰 Redis / DB / 配置表。 </p>
     *
     * <p> 计数器键 = 类型码 + 主体码 + 年份（"序号在类型码+主体码+年份维度共享递增、
     * 按年重置、不按月重置"这条规格的唯一落点）。年份进键意味着换年天然从 0 起 ——
     * 这正是"惰性跨年重置、不依赖 1 月 1 日定时任务"，桩这样实现是为了让单测能证明
     * 服务层确实把「签订日期的年份」传了下来。 </p>
     *
     * <p> 渲染：类型码 + 主体码 + yyyy + MM + 6 位序号（序号不参与月份）。 </p>
     */
    private static class StubCodeGenService implements ICodeGenService
    {
        private final Map<String, Integer> counters = new LinkedHashMap<>();

        /** getNextCode 被调用的次数：用来证明"没有白烧重试"（旧实现两次取号会烧 2002 次）。 */
        private int genCalls;

        /** 取号（占用序号） */
        @Override
        public String getNextCode(String confId)
        {
            return getNextCode(confId, null);
        }

        @Override
        public String getNextCode(String confId, CodeGenContext context)
        {
            String key = bucketOf(confId, context);
            int next = counters.containsKey(key) ? counters.get(key) + 1 : 1;
            counters.put(key, next);
            genCalls++;
            return render(context, next);
        }

        /** 预览（不占号）：连续两次返回同一编号 */
        @Override
        public String previewNextCode(String confId, CodeGenContext context)
        {
            String key = bucketOf(confId, context);
            int next = counters.containsKey(key) ? counters.get(key) + 1 : 1;
            return render(context, next);
        }

        private static String bucketOf(String confId, CodeGenContext context)
        {
            StringBuilder key = new StringBuilder(confId);
            if (context != null && context.isBucketed())
            {
                key.append(':').append(context.getParams().get("typeCode"))
                        .append(':').append(context.getParams().get("subjectCode"));
                if (context.getReferenceDate() != null)
                {
                    key.append(":y").append(new SimpleDateFormat("yyyy").format(context.getReferenceDate()));
                }
            }
            return key.toString();
        }

        private static String render(CodeGenContext context, int seq)
        {
            if (context == null)
            {
                return String.format("GEN%06d", seq);
            }
            String month = context.getReferenceDate() == null ? "01"
                    : new SimpleDateFormat("MM").format(context.getReferenceDate());
            String year = context.getReferenceDate() == null ? "0000"
                    : new SimpleDateFormat("yyyy").format(context.getReferenceDate());
            return context.getParams().get("typeCode") + context.getParams().get("subjectCode")
                    + year + month + String.format("%06d", seq);
        }
    }

    /** 系统参数桩：只需 {@code selectConfigByKey}（质保提醒窗口用）。 */
    private static class StubConfigService implements ISysConfigService
    {
        private final Map<String, String> values = new LinkedHashMap<>();

        void put(String key, String value)
        {
            values.put(key, value);
        }

        @Override
        public String selectConfigByKey(String configKey)
        {
            return values.get(configKey);
        }

        @Override
        public SysConfig selectConfigById(String configId)
        {
            return null;
        }

        @Override
        public boolean selectCaptchaEnabled()
        {
            return false;
        }

        @Override
        public List<SysConfig> selectConfigList(SysConfig config)
        {
            return new ArrayList<>();
        }

        @Override
        public int insertConfig(SysConfig config)
        {
            return 0;
        }

        @Override
        public int updateConfig(SysConfig config)
        {
            return 0;
        }

        @Override
        public void deleteConfigByIds(String[] configIds)
        {
            // 桩：只读
        }

        @Override
        public void loadingConfigCache()
        {
            // 桩：无缓存
        }

        @Override
        public void clearConfigCache()
        {
            // 桩：无缓存
        }

        @Override
        public void resetConfigCache()
        {
            // 桩：无缓存
        }

        @Override
        public boolean checkConfigKeyUnique(SysConfig config)
        {
            return true;
        }
    }

    /** 字典桩：{@code contract_statuses} / {@code arrival_statuses} 的取值集合。 */
    private static class StubDictService implements ISysDictTypeService
    {        private final Map<String, List<SysDictData>> dicts = new LinkedHashMap<>();

        void addDict(String dictType, String... values)
        {
            List<SysDictData> rows = new ArrayList<>();
            for (String value : values)
            {
                SysDictData data = new SysDictData();
                data.setDictType(dictType);
                data.setDictValue(value);
                data.setDictLabel(value);
                data.setStatus("0");
                rows.add(data);
            }
            dicts.put(dictType, rows);
        }

        @Override
        public List<SysDictData> selectDictDataByType(String dictType)
        {
            List<SysDictData> rows = dicts.get(dictType);
            return rows == null ? new ArrayList<SysDictData>() : new ArrayList<>(rows);
        }

        @Override
        public List<SysDictType> selectDictTypeList(SysDictType dictType)
        {
            return new ArrayList<>();
        }

        @Override
        public List<SysDictType> selectDictTypeAll()
        {
            return new ArrayList<>();
        }

        @Override
        public SysDictType selectDictTypeById(String dictId)
        {
            return null;
        }

        @Override
        public SysDictType selectDictTypeByType(String dictType)
        {
            return null;
        }

        @Override
        public void deleteDictTypeByIds(String[] dictIds)
        {
            // 桩：只读字典，不做写操作
        }

        @Override
        public void loadingDictCache()
        {
            // 桩：无缓存
        }

        @Override
        public void clearDictCache()
        {
            // 桩：无缓存
        }

        @Override
        public void resetDictCache()
        {
            // 桩：无缓存
        }

        @Override
        public int insertDictType(SysDictType dictType)
        {
            return 0;
        }

        @Override
        public int updateDictType(SysDictType dictType)
        {
            return 0;
        }

        @Override
        public boolean checkDictTypeUnique(SysDictType dictType)
        {
            return true;
        }
    }

    /**
     * 空值安全的字符串相等（桩里模拟 SQL 的 = 语义）。
     *
     * @param left  左值
     * @param right 右值
     * @return 相等返回 true
     */
    private static boolean same(String left, String right)
    {
        return left == null ? right == null : left.equals(right);
    }
}
