package com.ruoyi.ctms.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsContractItem;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.CtmsTag;
import com.ruoyi.ctms.domain.vo.CtmsContractDetailVo;
import com.ruoyi.ctms.domain.vo.CtmsRelatedDocVo;
import com.ruoyi.ctms.domain.vo.CtmsWarrantyReminderVo;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractItemMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsTagMapper;
import com.ruoyi.ctms.service.ICtmsContractService;
import com.ruoyi.ctms.service.ICtmsPartnerService;
import com.ruoyi.ctms.service.ICtmsProductMasterService;
import com.ruoyi.ctms.service.ICtmsTagService;
import com.ruoyi.ctms.support.ContractDataScope;
import com.ruoyi.ctms.support.ContractNumberRules;
import com.ruoyi.ctms.support.ContractRules;
import com.ruoyi.serial.api.ICodeGenService;
import com.ruoyi.serial.module.CodeGenContext;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * <p> 合同台账主体服务实现（2.0 B3 任务 4.1~4.8）。 </p>
 *
 * <p> <b>几个"反直觉"的口径在这里集中收敛</b>（对应 design.md D-7）： </p>
 * <ol>
 *   <li> <b>金额先舍入再汇总</b>：行总价 = 数量 × 单价 按 HALF_UP 保留 2 位，合同金额 = 各已舍入
 *        行总价之和；3 行各 1 × 1.665 得 5.01 而不是 5.00； </li>
 *   <li> <b>质保到期日在新增与编辑两条路径都由服务端重算</b>，前端传入的值不参与判定； </li>
 *   <li> <b>标的物摘要真正落库</b>（不只是写日志），格式「名称(规格)×数量」多条以分号连接； </li>
 *   <li> <b>软删除边界用整数日差</b>：第 30 天可恢复、第 31 天不可（口径在
 *        {@code ContractRules.isWithinRestoreWindow}）。 </li>
 * </ol>
 *
 * <p> <b>终止原因为什么不落库</b>：{@code t_ctms_contract} 没有"终止原因"列，B3 不新增列。
 * 接口层用入参的 {@code deletedReason} 字段承载该必填项（<b>仅为此用途复用字段名</b>，
 * 与软删除无关：软删除的原因写 {@code deleted_reason} 列，走 {@code softDelete}）。
 * 终止原因只写进一条 {@code field_name='status'} 的变更历史的新值文本里
 * （新值形如 {@code 已终止（终止原因：xxx）}），这样"必填 + 可追溯"都成立，
 * 而不必为它加一列。 </p>
 *
 * <p> <b>自动标签与手动同名标签</b>：{@code t_ctms_contract_tag} 的主键是
 * {@code (contract_id, tag_id)}，同一标签在一份合同上物理上只有一行，所以"自动/手动"
 * 只能取最后写入者。同步自动标签时先删 {@code auto='1'} 的行，再读一次现有标记：
 * 目标标签若已以<b>手动</b>身份存在，则跳过该次自动关联（既不删也不改写它的 {@code auto}），
 * 这就是"手动添加的标签不被自动同步移除"的落点。细节见
 * {@code CtmsTagServiceImpl.syncAutoTags}。 </p>
 *
 * <p> <b>数据范围（4.8，本批最高风险项）</b>：列表把
 * {@code ContractDataScope.buildDataScopeSql()} 写进 {@code query.dataScopeSql}；详情/编辑/
 * 删除/恢复/质保释放/框架详情都先取行再用 {@link #canAccess(CtmsContract)} 做同一口径的判定。
 * 范围外抛 {@code ServiceException("无权访问该合同", HttpStatus.FORBIDDEN)} —— 本项目的
 * {@code GlobalExceptionHandler.handleServiceException} 对带 code 的 ServiceException
 * 返回 {@code AjaxResult.error(code, msg)}，所以响应体是 {@code {"code":403,"msg":"无权访问该合同"}}，
 * 与既有 {@code DocViewGuard} 的做法一致，也正是 {@code tools/authz-check.ps1} 的
 * {@code IsForbidden} 断言所检查的形态（HTTP 仍为 200、按响应体业务码判定）。
 * 因此控制器<b>不</b>需要 try/catch 改写返回体。 </p>
 *
 * <p> <b>"当前时间"与"当前用户"都收敛成 protected 方法</b>（{@link #now()}、{@link #currentUserId()}、
 * {@link #currentDeptId()}、{@link #canAccess(CtmsContract)}），单测用子类替换后即可固定时间、
 * 模拟多角色档位，不必启动 Spring 或依赖真实时钟。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsContractServiceImpl implements ICtmsContractService, ICtmsContractService.ItemWriter
{
    /* ==================== 字典与枚举 ==================== */

    /** 进度状态字典类型。 */
    private static final String DICT_CONTRACT_STATUSES = "contract_statuses";

    /** 到货状态字典类型。 */
    private static final String DICT_ARRIVAL_STATUSES = "arrival_statuses";

    /** 合同类型字典类型（{@code dict_value} 就是 3 位类型码；{@code status='1'} = 不参与自动编号）。 */
    private static final String DICT_CONTRACT_TYPES = "contract_types";

    /** 我方主体字典类型（{@code dict_value} 就是 2 位主体码）。 */
    private static final String DICT_SUBJECTS = "subjects";

    /** 质保提醒窗口的系统参数键（天数；缺省 30）。 */
    private static final String CONFIG_WARRANTY_WINDOW_DAYS = "warranty_window_days";

    /** 质保提醒窗口天数缺省值（与 {@code sql/二开-合同台账-字典参数.sql} 的种子值一致）。 */
    private static final int DEFAULT_WARRANTY_WINDOW_DAYS = 30;

    /** 质保提醒看板最多返回条数（规格：看板区最多 100 条、按到期日升序）。 */
    private static final int WARRANTY_REMINDER_LIMIT = 100;

    /**
     * 自动编号的"撞号重试"上限。
     *
     * <p> 用于 Redis 计数器与库内编号不一致的场景（Redis 被清空 / 库从快照恢复 /
     * 编号配置被重建）：每次取号都会把计数器 +1，所以重试几次就能翻过库内已占用的那一段。
     * 上限存在的意义是"宁可失败也不无限循环"。 </p>
     */
    private static final int NO_COLLISION_RETRY_LIMIT = 1000;

    /** 终态：已终止（改为该状态时终止原因必填）。 */
    private static final String STATUS_TERMINATED = "已终止";

    /** 进度状态兜底默认值（与 DDL 的 DEFAULT 一致）。 */
    private static final String DEFAULT_STATUS = "内部审批中";

    /** 到货状态兜底默认值（与 DDL 的 DEFAULT 一致）。 */
    private static final String DEFAULT_ARRIVAL_STATUS = "未到货";

    /** 币种兜底默认值。 */
    private static final String DEFAULT_CURRENCY = "CNY";

    /* ==================== 标志位与兜底 ==================== */

    /**
     * 日志。
     *
     * <p> 取号里那条"计数器落后于库内桶 max，已按库内兜底"的告警（t31 / R2-②）是排查
     * "相邻号差 +1001 / 号被白烧"的唯一线索，所以本类需要一个 logger。 </p>
     */
    private static final Logger log = LoggerFactory.getLogger(CtmsContractServiceImpl.class);

    /** 标志位：是 / 已删除。 */
    private static final String FLAG_ON = "1";

    /** 标志位：否 / 未删除。 */
    private static final String FLAG_OFF = "0";

    /** 无登录上下文时的用户ID兜底。 */
    private static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的登录名兜底。 */
    private static final String DEFAULT_USERNAME = "system";

    /** 无登录上下文时的部门ID兜底。 */
    private static final String DEFAULT_DEPT_ID = "0";

    /** 详情返回的变更历史上限（规格只要求"入口"，不要求全量）。 */
    private static final int CHANGE_LOG_LIMIT = 200;

    /** 变更历史的对象类型。 */
    private static final String OBJECT_TYPE_CONTRACT = ContractRules.OBJECT_TYPE_CONTRACT;

    /** 变更历史来源：手工。 */
    public static final String SOURCE_MANUAL = "manual";

    /** 变更历史来源：自动。 */
    public static final String SOURCE_AUTO = "auto";

    /* ==================== 字段名口径（中文列名，特殊名沿用参考侧） ==================== */

    private static final String F_CONTRACT_NO = "合同编号";
    private static final String F_NAME = "合同名称";
    private static final String F_TYPE = "合同类型";
    private static final String F_PARTY_A = "甲方";
    private static final String F_PARTY_B = "乙方";
    private static final String F_SIGN_DATE = "签订日期";
    private static final String F_EFFECTIVE_DATE = "生效日期";
    private static final String F_SUMMARY = "_summary";
    private static final String F_AMOUNT = "合同金额";
    private static final String F_CURRENCY = "币种";
    private static final String F_SUBJECT_CODE = "主体码";
    private static final String F_CUSTOMER = ContractRules.FIELD_CUSTOMER;
    private static final String F_SUPPLIER = ContractRules.FIELD_SUPPLIER;
    private static final String F_PAID_AMOUNT = "累计已付金额";
    private static final String F_HAS_WARRANTY = "是否启用质保";
    private static final String F_WARRANTY_AMOUNT = "质保金额";
    private static final String F_WARRANTY_RATE = "质保比例";
    private static final String F_WARRANTY_START = "质保起始日";
    private static final String F_WARRANTY_MONTHS = "质保月数";
    private static final String F_WARRANTY_END = "质量保证金到期日";
    private static final String F_WARRANTY_RELEASED = "质保是否释放";
    private static final String F_WARRANTY_RELEASE_DATE = "质保释放日期";
    private static final String F_WARRANTY_NOTE = "质保备注";
    private static final String F_IS_FRAMEWORK = "是否框架合同";
    private static final String F_PARENT = "父框架合同";
    private static final String F_ARRIVAL_STATUS = "到货状态";
    private static final String F_EXPECTED_ARRIVAL = "预计到货日期";
    private static final String F_STATUS = "进度状态";
    private static final String F_OWNER = "经办人";
    private static final String F_REMARK = "备注";

    /** 特殊字段名：行项集合整体变化。 */
    private static final String F_ITEMS = "_items";

    /** 特殊字段名：标签集合变化。 */
    private static final String F_TAGS = "_tags";

    /** 特殊字段名：软删除 / 恢复。 */
    private static final String F_DELETED = "deleted";

    /** 特殊字段名：进度状态的特殊落点（终止原因写在这条记录的新值文本里）。 */
    private static final String F_STATUS_TERMINATED = "status";

    /** 逐字段比对的字段清单（顺序即界面顺序；集合类字段单独处理）。 */
    private static final String[] DIFF_FIELDS = {
            F_CONTRACT_NO, F_NAME, F_TYPE, F_PARTY_A, F_PARTY_B, F_SIGN_DATE, F_EFFECTIVE_DATE,
            F_SUMMARY, F_AMOUNT, F_CURRENCY, F_SUBJECT_CODE, F_CUSTOMER, F_SUPPLIER, F_PAID_AMOUNT,
            F_HAS_WARRANTY, F_WARRANTY_AMOUNT, F_WARRANTY_RATE, F_WARRANTY_START, F_WARRANTY_MONTHS,
            F_WARRANTY_END, F_WARRANTY_RELEASED, F_WARRANTY_RELEASE_DATE, F_WARRANTY_NOTE,
            F_IS_FRAMEWORK, F_PARENT, F_ARRIVAL_STATUS, F_EXPECTED_ARRIVAL, F_STATUS, F_OWNER, F_REMARK };

    /** 关闭质保时要清空的 7 个字段（{@code warrantyNote} 按任务口径保留）。 */
    private static final String[] WARRANTY_CLEAR_FIELDS = {
            F_WARRANTY_AMOUNT, F_WARRANTY_RATE, F_WARRANTY_START, F_WARRANTY_MONTHS,
            F_WARRANTY_END, F_WARRANTY_RELEASED, F_WARRANTY_RELEASE_DATE };

    /* ==================== 依赖 ==================== */

    @Autowired
    private CtmsContractMapper contractMapper;

    @Autowired
    private CtmsContractItemMapper itemMapper;

    @Autowired
    private CtmsTagMapper tagMapper;

    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    @Autowired
    private ICtmsTagService tagService;

    @Autowired
    private ICtmsPartnerService partnerService;

    @Autowired
    private ICtmsProductMasterService productMasterService;

    @Autowired
    private ISysDictTypeService dictTypeService;

    /**
     * 编号服务（2.0 B3 §1.3 扩展后的入口）：合同编号的类型码/主体码分桶 + 按签订日期取年月
     * + 按年惰性重置都由它承担，本模块<b>不自研第二套编号</b>（模块边界 REQ-NFR-001）。
     */
    @Autowired
    private ICodeGenService codeGenService;

    /**
     * 系统参数服务：只用于质保提醒窗口 {@code warranty_window_days}。
     *
     * <p> 声明为 {@code required=false}：内存桩单测不装配它，读不到时回落
     * {@value #DEFAULT_WARRANTY_WINDOW_DAYS} 天（与种子参数一致），不会把测试卡死。 </p>
     */
    @Autowired(required = false)
    private ISysConfigService configService;

    /**
     * 组织服务：只用于数据范围的"含下级"判定（{@code sys_dept.ancestors}）。
     *
     * <p> 声明为 {@code required=false}：内存桩单测不装配它，未知部门时"含下级"判定退化为
     * 精确匹配（{@link #deptAncestors(String)} 返回 null），不会把测试卡死。 </p>
     */
    @Autowired(required = false)
    private ISysDeptService deptService;

    /* ==================== 列表与详情 ==================== */

    @Override
    public List<CtmsContract> selectContractList(CtmsContract query)
    {
        CtmsContract effective = query == null ? new CtmsContract() : query;
        // 数据范围由服务端强制：片段只由服务端上下文拼出（见 ContractDataScope 的类注释）
        effective.setDataScopeSql(ContractDataScope.buildDataScopeSql());
        List<CtmsContract> list = contractMapper.selectContractList(effective);
        // 付款比例是派生列（不落库）：列表同样要能看到，口径与详情共用 ContractRules.paidRate
        if (list != null)
        {
            for (CtmsContract row : list)
            {
                fillDerivedFields(row);
            }
        }
        return list;
    }

    @Override
    public void checkContractAccess(String id)
    {
        // 复用与详情/编辑/删除完全相同的入口（存在性 + 数据范围 403），只丢弃返回值。
        // 给附件这类"挂在合同上的下游资源"用：附件的数据范围 = 合同的数据范围，
        // 不允许下游自己再写一份判定（见 ICtmsContractService 的接口注释）。
        requireAccessible(id);
    }

    @Override
    public CtmsContractDetailVo selectContractDetail(String id)
    {
        // 范围校验（范围外抛业务码 403）后再取各子集合；全过程只读，不回写合同任何字段
        CtmsContract contract = requireAccessible(id);
        CtmsContractDetailVo vo = new CtmsContractDetailVo();
        BeanUtils.copyProperties(contract, vo);
        fillDerivedFields(vo);
        vo.setTags(tagMapper.selectTagsByContractId(id));
        CtmsContractItem itemQuery = new CtmsContractItem();
        itemQuery.setContractId(id);
        vo.setItems(itemMapper.selectItemList(itemQuery));
        CtmsChangeLog logQuery = new CtmsChangeLog();
        logQuery.setContractId(id);
        logQuery.setObjectType(OBJECT_TYPE_CONTRACT);
        logQuery.setObjectId(id);
        logQuery.setLimit(Integer.valueOf(CHANGE_LOG_LIMIT));
        List<CtmsChangeLog> logs = changeLogMapper.selectChangeLogList(logQuery);
        vo.setChangeLogs(logs == null ? new ArrayList<CtmsChangeLog>() : logs);
        // B4 交付单据域后在此处接 t_erp 单据的只读查询；B3 固定返回空列表，
        // 且 MUST NOT 触发对合同任何字段的回写（规格：关联单据区块只读）
        vo.setRelatedDocs(new ArrayList<CtmsRelatedDocVo>());
        return vo;
    }

    /**
     * 填充<b>派生列</b>（目前只有付款比例；不落库，见 {@code CtmsContract.paidRate} 的说明）。
     *
     * <p> 口径：付款比例 = 累计已付 ÷ 合同金额 × 100%，4 位 HALF_UP，<b>不扣质保金</b>；
     * 合同金额为空或 0 时保持 {@code null}（规格：返回空值而非报错）。 </p>
     *
     * @param contract 合同（就地填充）
     */
    protected void fillDerivedFields(CtmsContract contract)
    {
        if (contract == null)
        {
            return;
        }
        contract.setPaidRate(ContractRules.paidRate(contract.getPaidAmount(), contract.getAmount()));
    }

    @Override
    public CtmsWarrantyReminderVo selectWarrantyReminders()
    {
        int windowDays = warrantyWindowDays();
        Date today = ContractRules.atStartOfDay(now());
        CtmsWarrantyReminderVo vo = new CtmsWarrantyReminderVo();
        vo.setWindowDays(windowDays);
        vo.setToday(day(today));
        // SQL 只取"窗口内的候选集"（启用质保 + 未释放 + 到期日非空 + 未停用 + 到期日 <= 右端点），
        // 即将/已到期的切分在下面按同一天口径完成 —— 两个列表的边界只有一处实现
        List<CtmsContract> candidates = contractMapper.selectWarrantyReminderCandidates(
                ContractRules.plusDays(today, windowDays),
                ContractDataScope.buildDataScopeSql(),
                WARRANTY_REMINDER_LIMIT);
        List<CtmsContract> expiring = new ArrayList<>();
        List<CtmsContract> expired = new ArrayList<>();
        if (candidates != null)
        {
            for (CtmsContract row : candidates)
            {
                Date end = ContractRules.atStartOfDay(row.getWarrantyEnd());
                if (end == null)
                {
                    // 库侧已用 is not null 过滤，这里只是防御：宁可漏提醒，也不要把没到期日的合同当已到期
                    continue;
                }
                fillDerivedFields(row);
                if (end.before(today))
                {
                    expired.add(row);
                }
                else
                {
                    expiring.add(row);
                }
            }
        }
        vo.setExpiring(expiring);
        vo.setExpired(expired);
        return vo;
    }

    @Override
    public String previewContractNo(String type, String subjectCode, String referenceDay)
    {
        // 预校验与登记路径**同一套**：预览能通过的组合，登记时也一定通过（否则前端会先看到号、再被拒）
        String typeCode = ContractNumberRules.checkTypeForAutoNumber(type, dictValues(DICT_CONTRACT_TYPES));
        String subject = ContractNumberRules.checkSubjectForAutoNumber(subjectCode, dictValues(DICT_SUBJECTS));
        Date reference = ContractRules.parseDay(referenceDay);
        if (reference == null)
        {
            // 签订日期为空时取当天（规格：月份码取签订日期，为空取当天月份）
            reference = ContractRules.atStartOfDay(now());
        }
        return codeGenService.previewNextCode(ContractNumberRules.CONF_ID, numberContext(reference, typeCode, subject));
    }

    /**
     * 取号上下文：类型码/主体码作为业务参数（决定计数器分桶 = 序号维度），参考日期决定年份与月份码。
     *
     * @param referenceDate 参考日期（签订日期；为空时调用方已回落当天）
     * @param typeCode      3 位类型码
     * @param subjectCode   2 位主体码
     * @return 上下文
     */
    private CodeGenContext numberContext(Date referenceDate, String typeCode, String subjectCode)
    {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        // 键名必须与 sql/二开-合同台账-编号配置.sql 里 rule_value 的参数名逐字一致
        params.put("typeCode", typeCode);
        params.put("subjectCode", subjectCode);
        return new CodeGenContext(referenceDate, params);
    }

    /**
     * 质保提醒窗口天数（{@code sys_config.warranty_window_days}）。
     *
     * <p> <b>每次调用都重新读</b>：参数被改后行为立刻变化（规格场景要求）。
     * 读不到或写成非数字时回落 {@value #DEFAULT_WARRANTY_WINDOW_DAYS}（与种子值一致），
     * 不让看板整体报错。 </p>
     *
     * @return 窗口天数
     */
    protected int warrantyWindowDays()
    {
        String configured = null;
        try
        {
            configured = configService == null ? null : configService.selectConfigByKey(CONFIG_WARRANTY_WINDOW_DAYS);
        }
        catch (Exception e)
        {
            // 无登录上下文 / 缓存未加载时的兜底：提醒窗口是展示口径，不值得让接口 500
            configured = null;
        }
        return ContractRules.intValue(configured, DEFAULT_WARRANTY_WINDOW_DAYS);
    }

    @Override
    public List<CtmsChangeLog> selectChangeLogList(CtmsChangeLog query)
    {
        CtmsChangeLog effective = query == null ? new CtmsChangeLog() : query;
        // 与详情同一处数据范围判定：范围外不允许读到别人的变更历史
        requireAccessible(effective.getContractId());
        return changeLogMapper.selectChangeLogList(effective);
    }

    @Override
    public CtmsContract selectFrameworkDetail(String id)
    {
        CtmsContract framework = new CtmsContract();
        // 复制一份再挂子集合：避免把 children/汇总写回 Mapper 返回的对象（保持只读语义）
        BeanUtils.copyProperties(requireAccessible(id), framework);
        List<CtmsContract> children = contractMapper.selectChildren(id);
        if (children == null)
        {
            children = new ArrayList<>();
        }
        framework.setChildren(children);
        framework.setChildrenCount(Integer.valueOf(children.size()));
        BigDecimal sum = BigDecimal.ZERO;
        for (CtmsContract child : children)
        {
            if (child != null && child.getAmount() != null)
            {
                sum = sum.add(child.getAmount());
            }
        }
        // 子合同金额合计与框架自身 amount 分开呈现（规格明确不要求相等）
        framework.setChildrenAmountSum(sum.setScale(ContractRules.SCALE_AMOUNT, java.math.RoundingMode.HALF_UP));
        return framework;
    }

    /* ==================== 登记 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertContract(CtmsContract contract)
    {
        if (contract == null)
        {
            throw new ServiceException("合同名称不能为空");
        }
        // 主键由服务端生成：请求体里的 id 一律忽略（DEV-ENV 6.38）
        contract.setId(IdUtils.fastSimpleUUID());
        normalizeCommon(contract, null);
        // ⚠ 顺序不能反：行项表对 t_ctms_contract 有非空外键（fk_contract_item_contract），
        //   所以必须**先插主表、再插行项**。曾经的写法是"applyItems 里顺手落行项"，
        //   真库直接报 1452（Cannot add or update a child row ... fk_contract_item_contract）→ 登记 500；
        //   内存桩单测发现不了（桩不校验 FK），只有真库能抓出来。
        //   因此这里把"算"与"落"拆开：applyItems 只做校验/重排/汇总，persistItems 在主表之后执行。
        Set<String> autoFields = new HashSet<>();
        applyItems(contract);
        Set<String> forcedFields = markItemOverriddenFields(contract, autoFields);
        applyWarranty(contract, null, autoFields);
        applyFrameworkGuards(contract, null);
        contract.setDelFlag(FLAG_OFF);
        contract.setDeletedAt(null);
        contract.setDeletedReason(null);
        contract.setCreateId(currentUserId());
        contract.setCreateBy(currentUsername());
        contract.setCreateTime(now());
        contract.setUpdateId(currentUserId());
        contract.setUpdateBy(currentUsername());
        contract.setUpdateTime(now());
        contractMapper.insertContract(contract);
        // 行项、标签、变更历史都和主表同事务（@Transactional）：任何一步失败都整体回滚，
        // 不留"有主表没行项"的半成品；行项必须排在主表之后（见上面的 FK 说明）
        persistItems(contract);
        // 标签：先落手动集合（提交了才动），再同步自动标签
        syncManualTags(contract);
        tagService.syncAutoTags(contract.getId(), contract.getType(), contract.getIsFramework(),
                !ContractRules.isBlank(contract.getParentId()));
        // 新增只留"服务端自动算出"的那几条（规格：新增即按算法算到期日并留痕为自动来源）
        List<CtmsChangeLog> logs = new ArrayList<>();
        collectAutoChanges(logs, null, contract, autoFields, forcedFields, contract.getId());
        persistLogs(logs);
    }

    /* ==================== 编辑 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContract(CtmsContract contract)
    {
        if (contract == null || ContractRules.isBlank(contract.getId()))
        {
            throw new ServiceException("合同不存在");
        }
        CtmsContract exist = requireAccessible(contract.getId());
        if (FLAG_ON.equals(exist.getDelFlag()))
        {
            throw new ServiceException("合同已停用，请先恢复");
        }
        // 行项/标签的"前"快照必须在替换之前取（Mapper 不会在查主表时带出行项）
        String oldItemSignature = contract.getItems() == null ? null : itemSignature(loadItems(exist.getId()));
        String oldTagSignature = tagSignature(tagMapper.selectTagsByContractId(exist.getId()));
        CtmsContract target = mergeForUpdate(exist, contract);
        normalizeCommon(target, exist);
        // 编号不可改：Mapper 的 updateContract 明确不覆盖 contract_no（它是"停用占号不复用"的
        // 唯一键，见 CtmsContractMapper 的接口注释）。入参改了编号只做唯一性校验（上面已做），
        // 这里把值退回库内原值 —— 既不落库、也不留痕，避免历史里出现一条"没发生过的变更"。
        target.setContractNo(exist.getContractNo());
        applyItems(target);
        Set<String> autoFields = new HashSet<>();
        Set<String> forcedFields = markItemOverriddenFields(target, autoFields);
        applyWarranty(target, exist, autoFields);
        applyFrameworkGuards(target, exist);
        target.setDelFlag(exist.getDelFlag());
        target.setDeletedAt(exist.getDeletedAt());
        target.setDeletedReason(exist.getDeletedReason());
        target.setUpdateId(currentUserId());
        target.setUpdateBy(currentUsername());
        target.setUpdateTime(now());
        contractMapper.updateContract(target);
        persistItems(target);
        syncManualTags(target);
        tagService.syncAutoTags(target.getId(), target.getType(), target.getIsFramework(),
                !ContractRules.isBlank(target.getParentId()));
        List<CtmsChangeLog> logs = new ArrayList<>();
        collectFieldChanges(logs, exist, target, null, autoFields, forcedFields, target.getId());
        // 集合类变化：行项（提交了才比）/ 标签
        if (contract.getItems() != null && !eq(oldItemSignature, itemSignature(target.getItems())))
        {
            logs.add(buildLog(F_ITEMS, oldItemSignature, itemSignature(target.getItems()), "行项全量替换",
                    SOURCE_MANUAL, target.getId()));
        }
        String newTagSignature = tagSignature(tagMapper.selectTagsByContractId(target.getId()));
        if (!eq(oldTagSignature, newTagSignature))
        {
            logs.add(buildLog(F_TAGS, oldTagSignature, newTagSignature, null, SOURCE_MANUAL, target.getId()));
        }
        persistLogs(logs);
    }

    /* ==================== 状态流转 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(CtmsContract contract)
    {
        if (contract == null || ContractRules.isBlank(contract.getId()))
        {
            throw new ServiceException("合同不存在");
        }
        CtmsContract exist = requireAccessible(contract.getId());
        if (FLAG_ON.equals(exist.getDelFlag()))
        {
            throw new ServiceException("合同已停用，请先恢复");
        }
        // 只改状态/到货状态/预计到货日期：其余列全部从库内行带过来，
        // 这样无论 Mapper 的 update 是否用 <if> 守卫，都不会把别的列写成 NULL
        CtmsContract patch = new CtmsContract();
        BeanUtils.copyProperties(exist, patch);
        List<CtmsChangeLog> logs = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        Set<String> autoFields = new HashSet<>();
        if (contract.getStatus() != null)
        {
            // 任意状态互转、不做顺序守卫
            ContractRules.checkStatusIn(contract.getStatus(), dictValues(DICT_CONTRACT_STATUSES));
            if (STATUS_TERMINATED.equals(contract.getStatus()))
            {
                String reason = ContractRules.trimToNull(contract.getDeletedReason());
                if (reason == null)
                {
                    throw new ServiceException("已终止必须填写终止原因");
                }
                // 终止原因不落库（合同表无该列），只写进这条历史的新值文本
                logs.add(buildLog(F_STATUS_TERMINATED, ContractRules.trimToNull(exist.getStatus()),
                        STATUS_TERMINATED + "（终止原因：" + reason + "）", reason, SOURCE_MANUAL, contract.getId()));
                excluded.add(F_STATUS);
            }
            patch.setStatus(contract.getStatus());
        }
        if (contract.getArrivalStatus() != null)
        {
            // 到货状态与进度状态无联动：这里只处理显式传入的那一个字段
            ContractRules.checkArrivalStatusIn(contract.getArrivalStatus(), dictValues(DICT_ARRIVAL_STATUSES));
            patch.setArrivalStatus(contract.getArrivalStatus());
        }
        if (contract.getExpectedArrivalDate() != null)
        {
            patch.setExpectedArrivalDate(contract.getExpectedArrivalDate());
        }
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        int rows = contractMapper.updateContract(patch);
        // 字段级变更历史：只改一个字段就只写一个字段
        collectFieldChanges(logs, exist, patch, excluded, autoFields, null, contract.getId());
        persistLogs(logs);
        return rows;
    }

    /* ==================== 软删除与恢复 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(String id, String reason)
    {
        String trimmedReason = ContractRules.trimToNull(reason);
        if (trimmedReason == null)
        {
            throw new ServiceException("停用原因不能为空");
        }
        CtmsContract exist = requireAccessible(id);
        if (FLAG_ON.equals(exist.getDelFlag()))
        {
            // 幂等：已停用不再报错，也不重复写历史
            return;
        }
        CtmsContract patch = new CtmsContract();
        BeanUtils.copyProperties(exist, patch);
        patch.setDelFlag(FLAG_ON);
        patch.setDeletedAt(now());
        patch.setDeletedReason(trimmedReason);
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        contractMapper.updateContractDelFlag(patch);
        List<CtmsChangeLog> logs = new ArrayList<>();
        logs.add(buildLog(F_DELETED, FLAG_OFF, FLAG_ON, trimmedReason, SOURCE_MANUAL, id));
        persistLogs(logs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(String id)
    {
        CtmsContract exist = requireAccessible(id);
        if (!FLAG_ON.equals(exist.getDelFlag()))
        {
            // 幂等：未停用直接成功（控制器据此返回"该合同未停用"提示）
            return;
        }
        // 边界用整数日差：第 30 天可恢复、第 31 天不可（口径在 ContractRules）
        if (exist.getDeletedAt() != null
                && !ContractRules.isWithinRestoreWindow(exist.getDeletedAt(), now()))
        {
            throw new ServiceException("已超过 30 天保留期，无法恢复");
        }
        contractMapper.restoreContract(id, currentUserId(), currentUsername());
        List<CtmsChangeLog> logs = new ArrayList<>();
        logs.add(buildLog(F_DELETED, FLAG_ON, FLAG_OFF, null, SOURCE_MANUAL, id));
        persistLogs(logs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseWarranty(String id)
    {
        CtmsContract exist = requireAccessible(id);
        if (!FLAG_ON.equals(exist.getHasWarranty()))
        {
            throw new ServiceException("该合同未启用质保");
        }
        CtmsContract patch = new CtmsContract();
        BeanUtils.copyProperties(exist, patch);
        patch.setWarrantyReleased(FLAG_ON);
        patch.setWarrantyReleaseDate(now());
        patch.setUpdateId(currentUserId());
        patch.setUpdateBy(currentUsername());
        patch.setUpdateTime(now());
        int rows = contractMapper.updateContract(patch);
        List<CtmsChangeLog> logs = new ArrayList<>();
        collectFieldChanges(logs, exist, patch, null, new HashSet<String>(), null, id);
        persistLogs(logs);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContract(String id)
    {
        if (ContractRules.isBlank(id))
        {
            throw new ServiceException("合同不存在");
        }
        // 物理删除：仅供回滚演练与测试夹具（控制器不暴露）。DB 侧行项/标签关联是级联删除，
        // 这里显式先删行项，让"夹具清理"在没有 FK 级联的环境里也成立
        itemMapper.deleteItemsByContractId(id);
        List<CtmsTag> tags = tagMapper.selectTagsByContractId(id);
        if (tags != null)
        {
            for (CtmsTag tag : tags)
            {
                if (tag != null && !ContractRules.isBlank(tag.getId()))
                {
                    tagMapper.deleteContractTag(id, tag.getId());
                }
            }
        }
        contractMapper.deleteContractById(id);
    }

    /* ==================== 数据范围（4.8） ==================== */

    /**
     * <p> 当前用户能否访问这一行（超管 / 任一角色为"全部"时恒 true）。 </p>
     *
     * <p> 判定口径来自 {@link ContractDataScope#matches}，与列表用的 SQL 片段
     * {@link ContractDataScope#buildDataScopeSql()} 一一对应；
     * <b>改档位口径时必须同时改这两个地方</b>。 </p>
     *
     * @param contract 已按 id 取出的合同行（不得为 null）
     * @return 可见返回 true
     */
    protected boolean canAccess(CtmsContract contract)
    {
        if (contract == null)
        {
            return false;
        }
        if (hasAllDataScope())
        {
            return true;
        }
        return ContractDataScope.matches(currentDataScopes(), currentUserId(), currentDeptId(),
                contract.getCreateId(), contract.getDeptId(), new ContractDataScope.DeptAncestorsLookup()
                {
                    @Override
                    public String ancestorsOf(String deptId)
                    {
                        return deptAncestors(deptId);
                    }
                });
    }

    /**
     * 当前用户是否具备"全部数据"权限（超管或任一角色 {@code data_scope='1'}）。
     *
     * @return 具备返回 true
     */
    protected boolean hasAllDataScope()
    {
        return ContractDataScope.hasAllScope();
    }

    /**
     * 当前用户的角色数据范围档位集合（多角色并集）。
     *
     * @return 档位集合
     */
    protected List<String> currentDataScopes()
    {
        return ContractDataScope.currentDataScopes();
    }

    /**
     * 部门祖先链（数据范围"含下级"判定用）。
     *
     * @param deptId 部门ID
     * @return 形如 {@code "0,100,101"} 的祖先链；查不到返回 null
     */
    protected String deptAncestors(String deptId)
    {
        if (deptService == null || ContractRules.isBlank(deptId))
        {
            return null;
        }
        try
        {
            SysDept dept = deptService.selectDeptById(deptId);
            return dept == null ? null : dept.getAncestors();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 按标识取行 + 范围校验（详情/编辑/删除/恢复/质保释放/框架详情的统一入口）。
     *
     * <p> ⚠ 依赖 {@code selectContractById} <b>返回已停用行</b>：恢复与幂等判定都要先看到
     * {@code del_flag='1'} 的那一行，否则恢复永远报"合同不存在"。 </p>
     *
     * @param id 合同ID
     * @return 合同行
     */
    protected CtmsContract requireAccessible(String id)
    {
        if (ContractRules.isBlank(id))
        {
            throw new ServiceException("合同不存在");
        }
        CtmsContract contract = contractMapper.selectContractById(id);
        if (contract == null)
        {
            throw new ServiceException("合同不存在");
        }
        if (!canAccess(contract))
        {
            // 业务码 403：GlobalExceptionHandler 会原样带出 code，见类注释
            throw new ServiceException("无权访问该合同", HttpStatus.FORBIDDEN);
        }
        return contract;
    }

    /* ==================== 登记/编辑的公共校验 ==================== */

    /**
     * 公共校验与默认值：编号唯一、名称/类型必填、状态字典、档案引用与文本兜底、
     * 标志位与币种默认、审计快照。
     *
     * @param contract 待保存对象（就地归一）
     * @param exist    已存在行（新增传 null）
     */
    private void normalizeCommon(CtmsContract contract, CtmsContract exist)
    {
        // 顺序说明：先做"名称/类型必填"这类最基础的口径校验，再解编号。
        // 反过来会得到一个很难懂的报错：一份**既没填名称、类型也不是合法码**的请求会先撞上
        // 「该类型暂不支持自动编号」，而用户真正该修的是"类型没选"。
        String name = ContractRules.trimToNull(contract.getName());
        if (name == null)
        {
            throw new ServiceException("合同名称不能为空");
        }
        contract.setName(name);
        String type = ContractRules.trimToNull(contract.getType());
        if (type == null)
        {
            throw new ServiceException("合同类型不能为空");
        }
        contract.setType(type);
        // 编号：留空 = 服务端按规则生成（默认路径）；非空 = 手工/历史编号（只校验格式与唯一性）。
        // 详见 resolveContractNo 的口径说明 —— 这是 5.8 相对 4.1 的唯一行为变化。
        resolveContractNo(contract, exist);
        // 进度状态：为空取字典首项（退化为 DDL 默认值），非空则必须命中字典取值集合
        List<String> statuses = dictValues(DICT_CONTRACT_STATUSES);
        String status = ContractRules.trimToNull(contract.getStatus());
        if (status == null)
        {
            status = statuses.isEmpty() ? DEFAULT_STATUS : statuses.get(0);
        }
        ContractRules.checkStatusIn(status, statuses);
        contract.setStatus(status);
        // 到货状态：独立字段，同样按字典校验
        List<String> arrivals = dictValues(DICT_ARRIVAL_STATUSES);
        String arrival = ContractRules.trimToNull(contract.getArrivalStatus());
        if (arrival == null)
        {
            arrival = arrivals.isEmpty() ? DEFAULT_ARRIVAL_STATUS : arrivals.get(0);
        }
        ContractRules.checkArrivalStatusIn(arrival, arrivals);
        contract.setArrivalStatus(arrival);
        // 三个开关与币种：空则回落原值 / DDL 默认
        contract.setHasWarranty(flagOr(contract.getHasWarranty(), exist == null ? FLAG_OFF : exist.getHasWarranty()));
        contract.setIsFramework(flagOr(contract.getIsFramework(), exist == null ? FLAG_OFF : exist.getIsFramework()));
        contract.setWarrantyReleased(flagOr(contract.getWarrantyReleased(),
                exist == null ? FLAG_OFF : exist.getWarrantyReleased()));
        String currency = ContractRules.trimToNull(contract.getCurrency());
        if (currency == null)
        {
            currency = exist == null ? null : ContractRules.trimToNull(exist.getCurrency());
        }
        contract.setCurrency(currency == null ? DEFAULT_CURRENCY : currency);
        // 档案引用与文本兜底（规格 business-partners 场景）
        resolvePartners(contract);
        // 所属部门是<b>服务端列</b>：新增取当前用户部门，编辑保持库内原值 ——
        // 请求体里的 deptId 一律忽略。它同时是 DEPT/SELF 数据范围的判定列，
        // 允许由请求改写等于把"把合同搬进自己部门"变成一条普通编辑就能完成的越权动作。
        contract.setDeptId(exist == null ? currentDeptId() : exist.getDeptId());
        // NOT NULL 文本/金额列归一
        if (contract.getPartyA() == null)
        {
            contract.setPartyA("");
        }
        if (contract.getPartyB() == null)
        {
            contract.setPartyB("");
        }
        if (contract.getSubjectMatter() == null)
        {
            contract.setSubjectMatter("");
        }
        if (contract.getAmount() == null)
        {
            contract.setAmount(BigDecimal.ZERO);
        }
        if (contract.getPaidAmount() == null)
        {
            contract.setPaidAmount(BigDecimal.ZERO);
        }
    }

    /**
     * <p> 合同编号的落点（2.0 B3 任务 5.8；规格 ctms/contract-commercials 的
     * 「合同编号格式与按年重置」+「编号预览与占用冲突处理」）。 </p>
     *
     * <p> <b>两条通道，边界写在方法上</b>： </p>
     * <ol>
     *   <li> <b>编号留空 → 服务端生成</b>（用户登记的默认路径）：走 {@code ruoyi-serial}
     *        （配置 {@link ContractNumberRules#CONF_ID}），
     *        类型码 / 主体码作为业务参数分桶（= 序号在「类型码 + 主体码 + 年份」维度共享递增），
     *        年份/月份码取<b>签订日期</b>（为空取当天），序号 6 位按年重置、
     *        <b>不</b>按月重置。<b>请求体里传的编号一律不参与</b>。 </li>
     *   <li> <b>编号非空 → 手工/历史编号</b>（迁移导入通道）：只做格式校验
     *        （{@link ContractNumberRules#checkNoFormat}）+ 唯一性校验，
     *        <b>不</b>要求类型参与自动编号、<b>不</b>要求主体码 —— 历史数据里存在已停用类型
     *        （{@code OTH}）与缺主体码的合同，若强制校验会把"导入"变成不可能。 </li>
     * </ol>
     *
     * <p> 唯一性判定对两条通道一致，且<b>穷尽式</b>查重：{@code selectContractByNo} 明确
     * 不排除已停用行，所以"停用占号不复用"成立；自动编号这一路还额外对全库已占编号做一次
     * 兜底扫描（序号的权威计数在 Redis，编号配置被重建或计数器被清时可能与库内不一致——
     * 那种情况下宁可跳号，也不能让两条合同拿到同一个编号）。 </p>
     *
     * @param contract 合同（就地写入 {@code contractNo}）
     * @param exist    已存在行（新增传 null；编辑路径已在调用方把编号退回库内原值）
     */
    private void resolveContractNo(CtmsContract contract, CtmsContract exist)
    {
        String submitted = ContractRules.trimToNull(contract.getContractNo());
        if (submitted != null)
        {
            // 通道 ②：手工/历史编号（格式 + 唯一性）
            // ⚠ 格式非法而只是"看起来不像自动编号"的旧数据也会被这里拒绝 —— 这是刻意的：
            //   规格要求手工编号 MUST 校验格式，放行任意文本会让唯一索引与占号扫描全部失效
            contract.setContractNo(ContractNumberRules.checkNoFormat(submitted));
        }
        else
        {
            // 通道 ①：服务端生成。
            // ⚠ 生成**必须**带"撞号即换下一个"的有界重试，理由见 assertNoNotTaken：
            //   序号的权威计数器在 Redis（按参数分桶），而 t_code_config.current_seq 在分桶路径下
            //   不回写；一旦 Redis 被清空 / 从快照恢复库 / 编号配置被重建，取号结果就可能与库内
            //   已有编号重合。唯一索引会兜住（1062），但那时错误形态就变成数据库异常了。
            //   这里做的是"翻到本桶第一个没被占用的号"，因此它是**碰撞恢复**而不是失败：
            //   代价只是那几个号被跳过（规格只要求"不得复用已占用编号"，跳号是允许的）。
            String typeCode = ContractNumberRules.checkTypeForAutoNumber(contract.getType(),
                    dictValues(DICT_CONTRACT_TYPES));
            String subjectCode = ContractNumberRules.checkSubjectForAutoNumber(contract.getSubjectCode(),
                    dictValues(DICT_SUBJECTS));
            Date reference = ContractRules.atStartOfDay(contract.getSignDate());
            if (reference == null)
            {
                reference = ContractRules.atStartOfDay(now());
            }
            CodeGenContext context = numberContext(reference, typeCode, subjectCode);
            // R2-②：先取一个候选（让分桶计数器正常前进），再用「库内同桶 max」兜底抬起点。
            // 计数器落后于库内桶（Redis 被清空 / 从快照恢复库 / 配置重建）时，若不做这一步，
            // 每一次取号都会撞上"库里已经有这个号"，把 1000 次重试白烧光。
            String candidate = codeGenService.getNextCode(ContractNumberRules.CONF_ID, context);
            String bucketPrefix = ContractNumberRules.prefixOf(candidate);
            int bucketMax = ContractNumberRules.maxSeqOf(contractMapper.selectAllContractNos(), bucketPrefix);
            String generated = reconcileGeneratedNo(candidate, bucketPrefix, bucketMax);
            int attempt = 0;
            while (isNoTaken(generated))
            {
                // R2-③：上限耗尽必须**显式失败**。旧实现是 `for (… < LIMIT && isNoTaken(…); attempt++)`
                // 之后再无条件 setContractNo(generated) —— 循环退出时可能仍然是个已占用的号，
                // 于是"检测到冲突却照样插入"，只在落库那一刻靠唯一索引 1062 兜底（错误形态变成 500）。
                if (++attempt >= NO_COLLISION_RETRY_LIMIT)
                {
                    throw new ServiceException(ContractNumberRules.MSG_NO_EXHAUSTED
                            + "（前缀=" + bucketPrefix + "，起点序号=" + (bucketMax + 1)
                            + "，重试上限=" + NO_COLLISION_RETRY_LIMIT + "）");
                }
                candidate = codeGenService.getNextCode(ContractNumberRules.CONF_ID, context);
                // 每一次重试都用"库内桶 max + 已试次数"当起点：既不会回落到低位空烧，
                // 也能在并发写入者抢走相邻号时逐个绕过
                generated = reconcileGeneratedNo(candidate, bucketPrefix, bucketMax + attempt);
            }
            contract.setContractNo(generated);
        }
        String contractNo = contract.getContractNo();
        CtmsContract same = contractMapper.selectContractByNo(contractNo);
        if (same != null && (exist == null || !same.getId().equals(exist.getId())))
        {
            // 已被占用（含被停用合同占用的号）：冲突语义按 design D-6 的 409
            throw new ServiceException("合同编号已存在（自动编号被占用，请重新生成）", HttpStatus.CONFLICT);
        }
    }

    /**
     * 该自动编号是否已被占用（含被停用合同占用的号）。
     *
     * <p> 用"全库已占编号 → 同桶集合 → 序号比较"判定，而不是单条 {@code selectContractByNo}：
     * 前者一次查询就能支撑有界重试里的多次判定，也顺带证明"停用占号不复用"
     * （{@code selectAllContractNos} 刻意不过滤 {@code del_flag}）。 </p>
     *
     * <p> ⚠ <b>只判"这个号本身"是否被占用（序号精确相等）</b>（t31 / R2-①）：
     * 旧实现写成 {@code seqOf(used) >= seq}，把"同桶存在比它大的号"当成"这个号被占用"——
     * 只要 Redis 计数器落后于库内桶 max，**每一次取号都会判成撞号**，白烧 1000 次重试后照样插入，
     * 现象就是相邻取号差 +1001。"存在更大的号"与"这个号被占用"是两件事。 </p>
     *
     * @param contractNo 刚生成的编号
     * @return 已被占用返回 true
     */
    private boolean isNoTaken(String contractNo)
    {
        List<String> occupied = contractMapper.selectAllContractNos();
        if (occupied == null || occupied.isEmpty())
        {
            return false;
        }
        String prefix = ContractNumberRules.prefixOf(contractNo);
        if (prefix == null)
        {
            // 前缀解析不出来（格式非法）不可能来自生成器；这里不判"占用"，
            // 让后面的格式/唯一性校验按各自的口径报错，避免把两种问题混成一个
            return false;
        }
        int seq = ContractNumberRules.seqOf(contractNo);
        for (String used : ContractNumberRules.sameBucket(occupied, prefix))
        {
            if (ContractNumberRules.seqOf(used) == seq)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * <p> 用「库内同桶 max」给取号结果兜底（t31 / R2-②）。 </p>
     *
     * <p> 候选序号 <b>不高于</b>库内桶 max 时，说明计数器落后于库内真实水位，
     * 直接按 {@code floorSeq + 1} 重拼编号并打一条 {@code log.warn}；
     * 否则（计数器正常领先）原样返回，<b>不改变既有行为</b>。 </p>
     *
     * <p> ⚠ 兜底只发生在 ruoyi-ctms 侧（取号结果不回落）：{@code ICodeGenService} 只提供
     * 取号/预览、没有 seed/advance 计数器的方法，而 {@code ruoyi-serial} 不在本任务范围内。
     * 因此 Redis 计数器本体的值可能仍然偏低 —— 但"取号必须以库内 max 为起点继续递增"这条语义
     * 由本方法保证（连续取号 = 库内 max+1、max+2…，且每次落库都会把库内 max 往前推）。 </p>
     *
     * @param candidate    生成器给的候选编号
     * @param bucketPrefix 同桶前缀（{@link ContractNumberRules#prefixOf} 的结果）
     * @param floorSeq     库内同桶水位（重试时传"库内 max + 已试次数"）
     * @return 实际要用的编号（无法抬升时原样返回候选，交给上层显式失败）
     */
    private String reconcileGeneratedNo(String candidate, String bucketPrefix, int floorSeq)
    {
        int candidateSeq = ContractNumberRules.seqOf(candidate);
        if (bucketPrefix == null || candidateSeq < 0 || candidateSeq > floorSeq)
        {
            return candidate;
        }
        String jumped = ContractNumberRules.buildNo(candidate, floorSeq + 1);
        if (jumped == null)
        {
            // 序号空间用尽（> 999999）或前缀非法：交回候选，由 isNoTaken/上限耗尽路径显式失败
            return candidate;
        }
        log.warn("取号计数器落后于库内桶 max：已按库内兜底。桶={} 计数器序号={} 库内 max={} → 本次从序号 {} 起编",
                bucketPrefix, candidateSeq, floorSeq, floorSeq + 1);
        return jumped;
    }

    /**
     * 甲乙方档案引用与文本兜底。
     *
     * <ul>
     *   <li> 传了 {@code customerId} → 档案必须存在（否则「客户档案不存在」），
     *        {@code partyA} 为空时用档案名称回填； </li>
     *   <li> 传了 {@code supplierId} → 同理（「供应商档案不存在」/ 回填 {@code partyB}）； </li>
     *   <li> 两者都为空 → 直接用传入文本（可为空串），<b>不报错</b>：
     *       历史合同与迁移期合同允许"只有文本、没有档案"。 </li>
     * </ul>
     *
     * @param contract 待保存对象（就地归一）
     */
    private void resolvePartners(CtmsContract contract)
    {
        String customerId = ContractRules.trimToNull(contract.getCustomerId());
        contract.setCustomerId(customerId);
        if (customerId != null)
        {
            CtmsCustomer customer = partnerService.selectCustomerById(customerId);
            if (customer == null)
            {
                throw new ServiceException("客户档案不存在");
            }
            if (ContractRules.isBlank(contract.getPartyA()))
            {
                contract.setPartyA(customer.getName());
            }
        }
        String supplierId = ContractRules.trimToNull(contract.getSupplierId());
        contract.setSupplierId(supplierId);
        if (supplierId != null)
        {
            CtmsSupplier supplier = partnerService.selectSupplierById(supplierId);
            if (supplier == null)
            {
                throw new ServiceException("供应商档案不存在");
            }
            if (ContractRules.isBlank(contract.getPartyB()))
            {
                contract.setPartyB(supplier.getName());
            }
        }
    }

    /**
     * 行项与金额（C-1：先舍入再汇总）：<b>只算不落库</b>。
     *
     * <p> {@code items == null} 表示"本次未提交行项"（不动行项与由行项覆盖的金额/摘要）；
     * 提交空集合表示"清空行项"；非空则重算金额与标的物摘要并归一每个行项。 </p>
     *
     * <p> 落库由 {@link #persistItems(CtmsContract)} 在主表之后完成 —— 拆开的原因见
     * {@code insertContract} 里的 FK 顺序说明。 </p>
     *
     * @param contract 合同（{@code items} 会被就地重排与补全；{@code amount}/{@code subjectMatter} 会被覆盖）
     */
    private void applyItems(CtmsContract contract)
    {
        List<CtmsContractItem> items = contract.getItems();
        if (items == null)
        {
            return;
        }
        if (items.isEmpty())
        {
            // 清空行项：真正的删除动作留给 persistItems（必须排在主表之后）
            contract.setItems(new ArrayList<CtmsContractItem>());
            return;
        }
        List<CtmsContractItem> normalized = new ArrayList<>();
        List<BigDecimal> totals = new ArrayList<>();
        int seq = 1;
        for (CtmsContractItem item : items)
        {
            if (item == null)
            {
                continue;
            }
            int lineNo = seq;
            // ① 序号按提交顺序从 1 连续重排
            item.setSeq(Integer.valueOf(lineNo));
            // ② 物料档案必填且必须存在；名称/规格为空时回落档案
            String productId = ContractRules.trimToNull(item.getProductId());
            if (productId == null)
            {
                throw new ServiceException("行项第 " + lineNo + " 行必须选择物料档案");
            }
            CtmsProduct product = productMasterService.selectProductById(productId);
            if (product == null)
            {
                throw new ServiceException("行项第 " + lineNo + " 行的物料档案不存在");
            }
            item.setProductId(productId);
            item.setName(ContractRules.itemNameOf(item.getName(), product.getName()));
            item.setSpec(ContractRules.itemSpecOf(item.getSpec(), product.getSpec()));
            item.setProductCode(product.getCode());
            item.setProductName(product.getName());
            // ③ 负数拒绝
            if (item.getQty() != null && item.getQty().signum() < 0)
            {
                throw new ServiceException("行项第 " + lineNo + " 行的数量不能为负数");
            }
            if (item.getUnitPrice() != null && item.getUnitPrice().signum() < 0)
            {
                throw new ServiceException("行项第 " + lineNo + " 行的单价不能为负数");
            }
            BigDecimal qty = item.getQty() == null ? BigDecimal.ZERO : item.getQty();
            BigDecimal unitPrice = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
            item.setQty(qty);
            item.setUnitPrice(unitPrice);
            // ④ 行总价先舍入，再在下面汇总
            BigDecimal total = ContractRules.lineTotal(qty, unitPrice);
            item.setTotal(total);
            totals.add(total);
            item.setId(IdUtils.fastSimpleUUID());
            item.setContractId(contract.getId());
            if (item.getName() == null)
            {
                item.setName("");
            }
            if (item.getSpec() == null)
            {
                item.setSpec("");
            }
            item.setCreateId(currentUserId());
            item.setCreateBy(currentUsername());
            item.setCreateTime(now());
            item.setUpdateId(currentUserId());
            item.setUpdateBy(currentUsername());
            item.setUpdateTime(now());
            normalized.add(item);
            seq++;
        }
        contract.setItems(normalized);
        contract.setAmount(ContractRules.sumLineTotals(totals));
        // ⑤ 标的物摘要真正落库（不只是写日志）
        contract.setSubjectMatter(ContractRules.subjectMatterOf(normalized));
    }

    /**
     * 落库行项（<b>必须在主表之后</b>）。
     *
     * <p> {@code t_ctms_contract_item.contract_id} 对 {@code t_ctms_contract} 有非空外键，
     * 所以行项的写只能排在主表插入/更新之后；{@code items == null} 表示"本次未提交行项"（不动），
     * 空集合表示"清空行项"，非空则 delete 后 batchInsert（全量替换）。 </p>
     *
     * @param contract 合同（{@code items} 已由 {@link #applyItems(CtmsContract)} 归一）
     */
    private void persistItems(CtmsContract contract)
    {
        List<CtmsContractItem> items = contract.getItems();
        if (items == null)
        {
            return;
        }
        itemMapper.deleteItemsByContractId(contract.getId());
        if (!items.isEmpty())
        {
            itemMapper.batchInsertItems(items);
        }
    }

    /**
     * 标记"由行项覆盖"的自动字段：合同金额与标的物摘要。
     *
     * <p> 规格 ctms/contract-commercials 的场景是「合同原金额 100.00，提交总价 60.00 与 40.00 的两行，
     * 变更历史新增一条字段为金额、来源为自动的记录」—— 重算结果与库值<b>相同</b>时也要留痕，
     * 所以这两个字段进"强制留痕"集合，不走"值不同才写"的通用判定。
     * 质保到期日不在此列：D-7 明确它保留"与库值不同才写"的行为。 </p>
     *
     * @param contract   合同（{@code items} 非空表示本次由行项重算）
     * @param autoFields 自动来源字段集合（就地追加）
     * @return 强制留痕字段集合
     */
    private Set<String> markItemOverriddenFields(CtmsContract contract, Set<String> autoFields)
    {
        Set<String> forced = new HashSet<>();
        if (contract.getItems() != null && !contract.getItems().isEmpty())
        {
            autoFields.add(F_AMOUNT);
            autoFields.add(F_SUMMARY);
            forced.add(F_AMOUNT);
            forced.add(F_SUMMARY);
        }
        return forced;
    }

    /**
     * 质保口径（4.5 的算法面）。
     *
     * @param contract   合同（就地归一）
     * @param exist      已存在行（新增传 null）
     * @param autoFields 收集"本次由服务端算出"的字段名，来源记为自动
     */
    private void applyWarranty(CtmsContract contract, CtmsContract exist, Set<String> autoFields)
    {
        boolean hasWarranty = FLAG_ON.equals(contract.getHasWarranty());
        contract.setHasWarranty(hasWarranty ? FLAG_ON : FLAG_OFF);
        if (hasWarranty)
        {
            if (contract.getWarrantyStart() == null)
            {
                throw new ServiceException("质保起始日不能为空");
            }
            if (contract.getWarrantyMonths() == null || contract.getWarrantyMonths().intValue() <= 0)
            {
                throw new ServiceException("质保月数必须大于 0");
            }
            // 到期日一律服务端重算：前端传入值不参与判定（新增与编辑两条路径都算）
            contract.setWarrantyEnd(ContractRules.computeWarrantyEnd(contract.getWarrantyStart(),
                    contract.getWarrantyMonths()));
            autoFields.add(F_WARRANTY_END);
            BigDecimal amount = contract.getAmount() == null ? BigDecimal.ZERO : contract.getAmount();
            boolean hasAmount = contract.getWarrantyAmount() != null;
            boolean hasRate = contract.getWarrantyRate() != null;
            if (hasRate && !hasAmount)
            {
                // 只填比例 → 补金额
                contract.setWarrantyAmount(ContractRules.warrantyAmountOfRate(amount, contract.getWarrantyRate()));
                autoFields.add(F_WARRANTY_AMOUNT);
            }
            else if (hasAmount && !hasRate)
            {
                // 只填金额 → 补比例
                contract.setWarrantyRate(ContractRules.warrantyRateOfAmount(amount, contract.getWarrantyAmount()));
                autoFields.add(F_WARRANTY_RATE);
            }
            // 两者都填：以金额为准，不做换算
            return;
        }
        // 关闭质保：清空 7 个字段（warrantyNote 按任务口径保留），并逐个记为自动来源
        contract.setWarrantyAmount(null);
        contract.setWarrantyRate(null);
        contract.setWarrantyStart(null);
        contract.setWarrantyMonths(null);
        contract.setWarrantyEnd(null);
        contract.setWarrantyReleased(FLAG_OFF);
        contract.setWarrantyReleaseDate(null);
        if (exist != null)
        {
            for (String field : WARRANTY_CLEAR_FIELDS)
            {
                autoFields.add(field);
            }
        }
    }

    /**
     * 框架绑定守卫（四条，错误文案与规格逐字一致）。
     *
     * @param contract 合同（就地归一 {@code isFramework}/{@code parentId}）
     * @param exist    已存在行（新增传 null）
     */
    private void applyFrameworkGuards(CtmsContract contract, CtmsContract exist)
    {
        boolean isFramework = FLAG_ON.equals(contract.getIsFramework());
        contract.setIsFramework(isFramework ? FLAG_ON : FLAG_OFF);
        String parentId = ContractRules.trimToNull(contract.getParentId());
        contract.setParentId(parentId);
        boolean wasFramework = exist != null && FLAG_ON.equals(exist.getIsFramework());
        boolean wasChild = exist != null && !ContractRules.isBlank(exist.getParentId());
        // ① 已是子合同（库内 parent_id 非空）再勾选框架标记
        if (wasChild && !wasFramework && isFramework)
        {
            throw new ServiceException("已是子合同，不能再标记为框架合同");
        }
        // ② 框架合同不能再挂到其他框架下（含"同时声明自己是框架又挂到父下"）
        if (isFramework && parentId != null)
        {
            throw new ServiceException("框架合同不能再挂到其他框架下");
        }
        // ③ 父合同必须存在、未停用、且本身是框架
        if (parentId != null)
        {
            CtmsContract parent = contractMapper.selectContractById(parentId);
            if (parent == null || FLAG_ON.equals(parent.getDelFlag()))
            {
                throw new ServiceException("父合同不存在或已停用");
            }
            if (!FLAG_ON.equals(parent.getIsFramework()))
            {
                throw new ServiceException("只能挂到框架合同下");
            }
        }
        // ④ 取消框架标记时仍有未停用子合同
        if (wasFramework && !isFramework && contract.getId() != null
                && contractMapper.countActiveChildren(contract.getId()) > 0)
        {
            throw new ServiceException("框架下仍有子合同，请先解除子合同");
        }
    }

    /**
     * 编辑场景的字段合并：<b>以库内行为底</b>，只覆盖"提交了的字段"。
     *
     * <p> 语义：{@code null} = 本次未提交（保持库内原值），空串 = 显式清空（文本列归一为 null）。
     * 这样即使前端只回传了被改动的字段，也不会把其它列写成 NULL。 </p>
     *
     * <p> {@code items}/{@code tagIds} 不做合并：{@code null} 表示"本次不提交该子集合"，
     * 由各自的下游方法判断。 </p>
     *
     * @param exist 库内行
     * @param input 入参
     * @return 合并后的保存对象
     */
    private CtmsContract mergeForUpdate(CtmsContract exist, CtmsContract input)
    {
        CtmsContract t = new CtmsContract();
        t.setId(exist.getId());
        t.setContractNo(pick(input.getContractNo(), exist.getContractNo()));
        t.setName(pick(input.getName(), exist.getName()));
        t.setType(pick(input.getType(), exist.getType()));
        t.setPartyA(pick(input.getPartyA(), exist.getPartyA()));
        t.setPartyB(pick(input.getPartyB(), exist.getPartyB()));
        t.setSignDate(input.getSignDate() == null ? exist.getSignDate() : input.getSignDate());
        t.setEffectiveDate(input.getEffectiveDate() == null ? exist.getEffectiveDate() : input.getEffectiveDate());
        t.setSubjectMatter(pick(input.getSubjectMatter(), exist.getSubjectMatter()));
        t.setAmount(input.getAmount() == null ? exist.getAmount() : input.getAmount());
        t.setCurrency(pick(input.getCurrency(), exist.getCurrency()));
        t.setSubjectCode(pick(input.getSubjectCode(), exist.getSubjectCode()));
        t.setCustomerId(pick(input.getCustomerId(), exist.getCustomerId()));
        t.setSupplierId(pick(input.getSupplierId(), exist.getSupplierId()));
        t.setPaidAmount(input.getPaidAmount() == null ? exist.getPaidAmount() : input.getPaidAmount());
        t.setHasWarranty(pick(input.getHasWarranty(), exist.getHasWarranty()));
        t.setWarrantyAmount(input.getWarrantyAmount() == null ? exist.getWarrantyAmount() : input.getWarrantyAmount());
        t.setWarrantyRate(input.getWarrantyRate() == null ? exist.getWarrantyRate() : input.getWarrantyRate());
        t.setWarrantyStart(input.getWarrantyStart() == null ? exist.getWarrantyStart() : input.getWarrantyStart());
        t.setWarrantyMonths(input.getWarrantyMonths() == null ? exist.getWarrantyMonths() : input.getWarrantyMonths());
        t.setWarrantyEnd(input.getWarrantyEnd() == null ? exist.getWarrantyEnd() : input.getWarrantyEnd());
        t.setWarrantyReleased(pick(input.getWarrantyReleased(), exist.getWarrantyReleased()));
        t.setWarrantyReleaseDate(input.getWarrantyReleaseDate() == null ? exist.getWarrantyReleaseDate()
                : input.getWarrantyReleaseDate());
        t.setWarrantyNote(pick(input.getWarrantyNote(), exist.getWarrantyNote()));
        t.setIsFramework(pick(input.getIsFramework(), exist.getIsFramework()));
        // parentId 特例：null = 未提交（保持原值）；空串 = 显式解绑（归 null）
        t.setParentId(input.getParentId() == null ? exist.getParentId()
                : ContractRules.trimToNull(input.getParentId()));
        t.setArrivalStatus(pick(input.getArrivalStatus(), exist.getArrivalStatus()));
        t.setExpectedArrivalDate(input.getExpectedArrivalDate() == null ? exist.getExpectedArrivalDate()
                : input.getExpectedArrivalDate());
        t.setStatus(pick(input.getStatus(), exist.getStatus()));
        t.setOwnerName(pick(input.getOwnerName(), exist.getOwnerName()));
        // dept_id 不进合并：它在 normalizeCommon 里按"服务端列"统一赋值（新增取当前用户部门、
        // 编辑保持库内原值），入参的 deptId 一律忽略
        t.setRemark(pick(input.getRemark(), exist.getRemark()));
        // 审计与软删除列不由入参决定
        t.setCreateId(exist.getCreateId());
        t.setCreateBy(exist.getCreateBy());
        t.setCreateTime(exist.getCreateTime());
        t.setDelFlag(exist.getDelFlag());
        t.setDeletedAt(exist.getDeletedAt());
        t.setDeletedReason(exist.getDeletedReason());
        t.setItems(input.getItems());
        t.setTagIds(input.getTagIds());
        return t;
    }

    /* ==================== 标签同步 ==================== */

    /**
     * 同步<b>手动</b>标签集合（{@code tagIds} 为 null 时不动）。
     *
     * <p> 只增删 {@code auto='0'} 的关联行：自动行由
     * {@code ICtmsTagService.syncAutoTags} 负责，避免两边互相踩。 </p>
     *
     * @param contract 合同（{@code tagIds} 为期望的手动标签集合）
     */
    private void syncManualTags(CtmsContract contract)
    {
        List<String> tagIds = contract.getTagIds();
        if (tagIds == null)
        {
            return;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String tagId : tagIds)
        {
            String trimmed = ContractRules.trimToNull(tagId);
            if (trimmed != null)
            {
                wanted.add(trimmed);
            }
        }
        for (String tagId : wanted)
        {
            if (tagMapper.selectTagById(tagId) == null)
            {
                throw new ServiceException("标签不存在");
            }
        }
        Set<String> currentManual = new LinkedHashSet<>();
        List<CtmsTag> current = tagMapper.selectTagsByContractId(contract.getId());
        if (current != null)
        {
            for (CtmsTag tag : current)
            {
                if (tag != null && CtmsTagServiceImpl.AUTO_FLAG_MANUAL.equals(tag.getAuto())
                        && !ContractRules.isBlank(tag.getId()))
                {
                    currentManual.add(tag.getId());
                }
            }
        }
        for (String tagId : currentManual)
        {
            if (!wanted.contains(tagId))
            {
                tagMapper.deleteContractTag(contract.getId(), tagId);
            }
        }
        for (String tagId : wanted)
        {
            if (!currentManual.contains(tagId))
            {
                tagMapper.insertContractTag(contract.getId(), tagId, CtmsTagServiceImpl.AUTO_FLAG_MANUAL,
                        currentUserId(), currentUsername());
            }
        }
    }

    /**
     * 行项集合的规范化签名（用于 {@code _items} 变更判定）。
     *
     * @param items 行项
     * @return 签名；无行项返回 null
     */
    private String itemSignature(List<CtmsContractItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (CtmsContractItem item : items)
        {
            if (item == null)
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(';');
            }
            sb.append(nullToEmpty(item.getName())).append('(').append(nullToEmpty(item.getSpec())).append(')')
                    .append('x').append(plain(item.getQty())).append('@').append(plain(item.getUnitPrice()))
                    .append('=').append(plain(item.getTotal())).append('#').append(nullToEmpty(item.getProductId()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 标签集合的规范化签名（用于 {@code _tags} 变更判定）。
     *
     * @param tags 标签
     * @return 逗号连接的标签名（按名称升序）；无标签返回 null
     */
    private String tagSignature(List<CtmsTag> tags)
    {
        if (tags == null || tags.isEmpty())
        {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (CtmsTag tag : tags)
        {
            if (tag != null && !ContractRules.isBlank(tag.getName()))
            {
                names.add(tag.getName().trim());
            }
        }
        if (names.isEmpty())
        {
            return null;
        }
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String name : names)
        {
            if (sb.length() > 0)
            {
                sb.append(',');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /* ==================== 变更历史 ==================== */

    /**
     * 逐字段比对写变更历史（编辑/状态流转/释放质保共用）。
     *
     * @param logs        收集容器
     * @param old         原行
     * @param now         新行
     * @param excluded    需要跳过的字段名（例如已按特殊字段名单独留痕的"进度状态"）
     * @param autoFields  本次由服务端算出的字段名（来源记为 {@code auto}）
     * @param contractId  合同ID
     */
    private void collectFieldChanges(List<CtmsChangeLog> logs, CtmsContract old, CtmsContract now,
                                     Set<String> excluded, Set<String> autoFields, Set<String> forcedFields,
                                     String contractId)
    {
        for (String field : DIFF_FIELDS)
        {
            if (excluded != null && excluded.contains(field))
            {
                continue;
            }
            boolean forced = forcedFields != null && forcedFields.contains(field);
            String oldValue = fieldValue(old, field);
            String newValue = fieldValue(now, field);
            if (!forced && eq(oldValue, newValue))
            {
                continue;
            }
            String source = (autoFields != null && autoFields.contains(field)) ? SOURCE_AUTO : SOURCE_MANUAL;
            logs.add(buildLog(field, oldValue, newValue, null, source, contractId));
        }
    }

    /**
     * 只留"服务端自动算出"的字段（登记场景：旧值为空，逐字段比对会写出一整页无意义记录）。
     *
     * @param logs        收集容器
     * @param old         原行（登记场景为 null）
     * @param now         新行
     * @param autoFields  自动字段集合
     * @param contractId  合同ID
     */
    private void collectAutoChanges(List<CtmsChangeLog> logs, CtmsContract old, CtmsContract now,
                                    Set<String> autoFields, Set<String> forcedFields, String contractId)
    {
        if (autoFields == null || autoFields.isEmpty())
        {
            return;
        }
        for (String field : DIFF_FIELDS)
        {
            if (!autoFields.contains(field))
            {
                continue;
            }
            boolean forced = forcedFields != null && forcedFields.contains(field);
            String oldValue = fieldValue(old, field);
            String newValue = fieldValue(now, field);
            if (!forced && eq(oldValue, newValue))
            {
                continue;
            }
            logs.add(buildLog(field, oldValue, newValue, null, SOURCE_AUTO, contractId));
        }
    }

    /**
     * 取字段的"变更历史口径"字符串值：金额用 {@code toPlainString}、日期用 {@code yyyy-MM-dd}。
     *
     * @param contract 合同（可为 null）
     * @param field    字段名
     * @return 字符串值；空值返回 null
     */
    private String fieldValue(CtmsContract contract, String field)
    {
        if (contract == null || field == null)
        {
            return null;
        }
        if (F_CONTRACT_NO.equals(field))
        {
            return ContractRules.trimToNull(contract.getContractNo());
        }
        if (F_NAME.equals(field))
        {
            return ContractRules.trimToNull(contract.getName());
        }
        if (F_TYPE.equals(field))
        {
            return ContractRules.trimToNull(contract.getType());
        }
        if (F_PARTY_A.equals(field))
        {
            return ContractRules.trimToNull(contract.getPartyA());
        }
        if (F_PARTY_B.equals(field))
        {
            return ContractRules.trimToNull(contract.getPartyB());
        }
        if (F_SIGN_DATE.equals(field))
        {
            return day(contract.getSignDate());
        }
        if (F_EFFECTIVE_DATE.equals(field))
        {
            return day(contract.getEffectiveDate());
        }
        if (F_SUMMARY.equals(field))
        {
            return ContractRules.trimToNull(contract.getSubjectMatter());
        }
        if (F_AMOUNT.equals(field))
        {
            return plain(contract.getAmount());
        }
        if (F_CURRENCY.equals(field))
        {
            return ContractRules.trimToNull(contract.getCurrency());
        }
        if (F_SUBJECT_CODE.equals(field))
        {
            return ContractRules.trimToNull(contract.getSubjectCode());
        }
        if (F_CUSTOMER.equals(field))
        {
            return ContractRules.trimToNull(contract.getCustomerId());
        }
        if (F_SUPPLIER.equals(field))
        {
            return ContractRules.trimToNull(contract.getSupplierId());
        }
        if (F_PAID_AMOUNT.equals(field))
        {
            return plain(contract.getPaidAmount());
        }
        if (F_HAS_WARRANTY.equals(field))
        {
            return ContractRules.trimToNull(contract.getHasWarranty());
        }
        if (F_WARRANTY_AMOUNT.equals(field))
        {
            return plain(contract.getWarrantyAmount());
        }
        if (F_WARRANTY_RATE.equals(field))
        {
            return plain(contract.getWarrantyRate());
        }
        if (F_WARRANTY_START.equals(field))
        {
            return day(contract.getWarrantyStart());
        }
        if (F_WARRANTY_MONTHS.equals(field))
        {
            return contract.getWarrantyMonths() == null ? null : contract.getWarrantyMonths().toString();
        }
        if (F_WARRANTY_END.equals(field))
        {
            return day(contract.getWarrantyEnd());
        }
        if (F_WARRANTY_RELEASED.equals(field))
        {
            return ContractRules.trimToNull(contract.getWarrantyReleased());
        }
        if (F_WARRANTY_RELEASE_DATE.equals(field))
        {
            return day(contract.getWarrantyReleaseDate());
        }
        if (F_WARRANTY_NOTE.equals(field))
        {
            return ContractRules.trimToNull(contract.getWarrantyNote());
        }
        if (F_IS_FRAMEWORK.equals(field))
        {
            return ContractRules.trimToNull(contract.getIsFramework());
        }
        if (F_PARENT.equals(field))
        {
            return ContractRules.trimToNull(contract.getParentId());
        }
        if (F_ARRIVAL_STATUS.equals(field))
        {
            return ContractRules.trimToNull(contract.getArrivalStatus());
        }
        if (F_EXPECTED_ARRIVAL.equals(field))
        {
            return day(contract.getExpectedArrivalDate());
        }
        if (F_STATUS.equals(field))
        {
            return ContractRules.trimToNull(contract.getStatus());
        }
        if (F_OWNER.equals(field))
        {
            return ContractRules.trimToNull(contract.getOwnerName());
        }
        if (F_REMARK.equals(field))
        {
            return ContractRules.trimToNull(contract.getRemark());
        }
        return null;
    }

    /**
     * 组装一条变更历史。
     *
     * <p> 操作人标识与姓名快照都取当前登录用户；<b>取不到姓名时留 null</b>（不报错），
     * 前端按约定展示占位符。 </p>
     *
     * @param field      字段名
     * @param oldValue   旧值
     * @param newValue   新值
     * @param note       备注（例如软删除的停用原因）
     * @param source     来源：manual / auto
     * @param contractId 合同ID
     * @return 变更历史
     */
    private CtmsChangeLog buildLog(String field, String oldValue, String newValue, String note,
                                   String source, String contractId)
    {
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        log.setContractId(contractId);
        log.setFieldName(field);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setNote(note);
        log.setSource(source);
        log.setOperatorId(currentUserId());
        log.setOperatorName(currentNickName());
        log.setObjectType(OBJECT_TYPE_CONTRACT);
        log.setObjectId(contractId);
        log.setCreateId(currentUserId());
        log.setCreateBy(currentUsername());
        log.setCreateTime(now());
        return log;
    }

    /**
     * 批量落变更历史（空集合直接返回，避免无意义的 Mapper 调用）。
     *
     * @param logs 变更历史
     */
    private void persistLogs(List<CtmsChangeLog> logs)
    {
        if (logs == null || logs.isEmpty())
        {
            return;
        }
        changeLogMapper.batchInsertChangeLogs(logs);
    }

    /* ==================== 行项单行写（任务 7.4 追加的 9.1 权限点闭合项） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CtmsContractItem insertContractItem(CtmsContractItem item)
    {
        if (item == null)
        {
            throw new ServiceException("行项不能为空");
        }
        String contractId = ContractRules.trimToNull(item.getContractId());
        if (contractId == null)
        {
            throw new ServiceException("行项必须指定所属合同");
        }
        CtmsContract exist = requireEditableContract(contractId);
        List<CtmsContractItem> merged = loadItems(exist.getId());
        // 新增：主键由服务端生成（请求体里的 id 一律忽略，与新增合同同款口径）
        item.setId(null);
        merged.add(item);
        writeItems(exist, merged);
        // applyItems 已就地归一（生成 id/序号、补物料快照、算行总价），所以这里返回的就是落库值
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CtmsContractItem updateContractItem(CtmsContractItem item)
    {
        if (item == null || ContractRules.isBlank(item.getId()))
        {
            throw new ServiceException("行项不存在");
        }
        CtmsContractItem current = itemMapper.selectItemById(ContractRules.trimToNull(item.getId()));
        if (current == null)
        {
            throw new ServiceException("行项不存在");
        }
        CtmsContract exist = requireEditableContract(current.getContractId());
        String submittedContractId = ContractRules.trimToNull(item.getContractId());
        if (submittedContractId != null && !exist.getId().equals(submittedContractId))
        {
            // 不允许把行项搬到另一份合同：那等于绕过两份合同各自的数据范围判定
            throw new ServiceException("行项不属于该合同");
        }
        item.setContractId(exist.getId());
        List<CtmsContractItem> merged = new ArrayList<>();
        boolean hit = false;
        for (CtmsContractItem row : loadItems(exist.getId()))
        {
            if (current.getId().equals(row.getId()))
            {
                merged.add(item);
                hit = true;
            }
            else
            {
                merged.add(row);
            }
        }
        if (!hit)
        {
            throw new ServiceException("行项不存在");
        }
        writeItems(exist, merged);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContractItem(String itemId)
    {
        String id = ContractRules.trimToNull(itemId);
        CtmsContractItem current = id == null ? null : itemMapper.selectItemById(id);
        if (current == null)
        {
            throw new ServiceException("行项不存在");
        }
        CtmsContract exist = requireEditableContract(current.getContractId());
        List<CtmsContractItem> merged = new ArrayList<>();
        for (CtmsContractItem row : loadItems(exist.getId()))
        {
            if (!id.equals(row.getId()))
            {
                merged.add(row);
            }
        }
        writeItems(exist, merged);
    }

    /**
     * <p> 行项写入口径：把「归一 + 校验 + 汇总 + 落库 + 变更历史」整条链路交给既有的
     * {@link #updateContract(CtmsContract)}（<b>不另造第二套行项写入</b>）。 </p>
     *
     * <p> 具体做法：以库内行做一份"除了行项什么都没变"的补丁，把合并后的行项集合挂上去。
     * 于是变更历史自然产生一条 {@code _items}（前后签名）+ 金额/标的物摘要两条自动来源记录，
     * 金额仍按 C-1「先舍入再汇总」由 {@code applyItems} 重算。 </p>
     *
     * <p> {@code tagIds} 刻意保持 {@code null}：单行行项写不碰手动标签
     * （{@code syncManualTags} 对 null 直接返回）。 </p>
     *
     * <p> 孤儿行：合同存在性 + 数据范围已在调用方校验，行项表对 {@code t_ctms_contract} 又是非空外键，
     * 且 delete+insert 与合同更新同事务，因此不可能留下"有行项没合同"的行。 </p>
     *
     * @param exist 库内合同（已过数据范围与停用校验）
     * @param items 合并后的行项集合（全量替换语义）
     */
    private void writeItems(CtmsContract exist, List<CtmsContractItem> items)
    {
        CtmsContract patch = new CtmsContract();
        BeanUtils.copyProperties(exist, patch);
        patch.setTagIds(null);
        patch.setItems(items);
        updateContract(patch);
    }

    /**
     * 取一份"可编辑"的合同：存在 → 数据范围 → 未停用（三件事一处收敛，行项写端点共用）。
     *
     * @param contractId 合同ID
     * @return 库内合同
     */
    private CtmsContract requireEditableContract(String contractId)
    {
        // 数据范围复用合同侧唯一入口（不存在抛「合同不存在」，范围外抛业务码 403）
        CtmsContract exist = requireAccessible(contractId);
        if (FLAG_ON.equals(exist.getDelFlag()))
        {
            throw new ServiceException("合同已停用，请先恢复");
        }
        return exist;
    }

    /* ==================== 小工具 ==================== */

    /**
     * 查询某合同的行项（编辑前取"前"快照用）。
     *
     * @param contractId 合同ID
     * @return 行项集合（无则空集合）
     */
    private List<CtmsContractItem> loadItems(String contractId)
    {
        CtmsContractItem query = new CtmsContractItem();
        query.setContractId(contractId);
        List<CtmsContractItem> items = itemMapper.selectItemList(query);
        return items == null ? new ArrayList<CtmsContractItem>() : items;
    }

    /**
     * 读取字典取值集合（{@code status='0'} 的启用项；停用项由字典接口自己过滤）。
     *
     * @param dictType 字典类型
     * @return 取值集合；读不到时返回空集合
     */
    protected List<String> dictValues(String dictType)
    {
        List<String> values = new ArrayList<>();
        if (dictTypeService == null)
        {
            return values;
        }
        try
        {
            List<SysDictData> data = dictTypeService.selectDictDataByType(dictType);
            if (data == null)
            {
                return values;
            }
            for (SysDictData item : data)
            {
                if (item == null)
                {
                    continue;
                }
                String value = ContractRules.trimToNull(item.getDictValue());
                if (value != null && !values.contains(value))
                {
                    values.add(value);
                }
            }
        }
        catch (Exception e)
        {
            // 字典读不到时交给 ContractRules 统一报「无效状态」，不在这里抛与业务无关的异常
            return values;
        }
        return values;
    }

    /**
     * 取第一个非空白值（编辑场景的字段合并）。
     *
     * @param preferred 入参值
     * @param fallback  库内原值
     * @return 归一后的值
     */
    private String pick(String preferred, String fallback)
    {
        String value = ContractRules.trimToNull(preferred);
        return value == null ? ContractRules.trimToNull(fallback) : value;
    }

    /**
     * 标志位归一：非 {@code '1'} 一律视为 {@code '0'}。
     *
     * @param value    入参值
     * @param fallback 兜底值
     * @return {@code '0'} 或 {@code '1'}
     */
    private String flagOr(String value, String fallback)
    {
        if (FLAG_ON.equals(ContractRules.trimToNull(value)))
        {
            return FLAG_ON;
        }
        if (ContractRules.trimToNull(value) == null && FLAG_ON.equals(fallback))
        {
            return FLAG_ON;
        }
        return FLAG_OFF;
    }

    /**
     * 金额口径的字符串（{@code toPlainString}，避免科学计数法）。
     *
     * @param value 金额
     * @return 字符串；null 返回 null
     */
    private String plain(BigDecimal value)
    {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 日期口径的字符串（{@code yyyy-MM-dd}）。
     *
     * @param value 日期
     * @return 字符串；null 返回 null
     */
    private String day(Date value)
    {
        return value == null ? null : DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD, value);
    }

    /**
     * 空串兜底。
     *
     * @param value 值
     * @return 非 null 的字符串
     */
    private String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    /**
     * 两个值是否等价（null 与空白视为同一"空"语义）。
     *
     * @param left  左值
     * @param right 右值
     * @return 等价返回 true
     */
    private boolean eq(String left, String right)
    {
        if (left == null || right == null)
        {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    /**
     * 当前时间（单测用子类替换它即可固定时间）。
     *
     * @return 当前时间
     */
    protected Date now()
    {
        return DateUtils.getNowDate();
    }

    /**
     * 当前用户ID（DDL 的 {@code create_id}/{@code update_id} 列）。
     *
     * <p> 无登录上下文时捕获异常并回落为 {@value #DEFAULT_USER_ID}：内存桩单测、
     * 数据迁移脚本、定时任务都没有登录上下文，不兜底会直接抛「获取用户ID异常」。 </p>
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            return ContractRules.isBlank(userId) ? DEFAULT_USER_ID : userId;
        }
        catch (Exception e)
        {
            return DEFAULT_USER_ID;
        }
    }

    /**
     * 当前用户登录名快照（DDL 的 {@code create_by}/{@code update_by} 列）。
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return ContractRules.isBlank(username) ? DEFAULT_USERNAME : username;
        }
        catch (Exception e)
        {
            return DEFAULT_USERNAME;
        }
    }

    /**
     * 当前用户姓名快照（变更历史的 {@code operator_name}）。
     *
     * <p> <b>拿不到就返回 null</b>，不回落为 {@code "system"}：历史记录的"操作人"必须要么是真人、
     * 要么是明确的空值（前端展示占位符），把系统兜底值当操作人会让审计失真。 </p>
     *
     * @return 姓名；无登录上下文返回 null
     */
    protected String currentNickName()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getUser() == null)
            {
                return null;
            }
            return ContractRules.trimToNull(loginUser.getUser().getNickName());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 当前用户部门ID（登记时的 {@code dept_id} 快照，数据范围的部门口径）。
     *
     * @return 部门ID；无登录上下文回落为 {@value #DEFAULT_DEPT_ID}
     */
    protected String currentDeptId()
    {
        try
        {
            String deptId = SecurityUtils.getDeptId();
            return ContractRules.isBlank(deptId) ? DEFAULT_DEPT_ID : deptId;
        }
        catch (Exception e)
        {
            return DEFAULT_DEPT_ID;
        }
    }
}
