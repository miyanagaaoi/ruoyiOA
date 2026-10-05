package com.ruoyi.ctms.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsChangeLog;
import com.ruoyi.ctms.domain.CtmsContract;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsPartyDraft;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.domain.vo.CtmsMigrationClaimVo;
import com.ruoyi.ctms.mapper.CtmsChangeLogMapper;
import com.ruoyi.ctms.mapper.CtmsContractMapper;
import com.ruoyi.ctms.mapper.CtmsPartyDraftMapper;
import com.ruoyi.ctms.service.ICtmsMigrationService;
import com.ruoyi.ctms.service.ICtmsPartnerService;
import com.ruoyi.ctms.support.ContractRules;
import com.ruoyi.ctms.support.MigrationRules;

/**
 * <p> 历史甲乙方迁移服务实现（2.0 B3 任务 7.1~7.2；规格 {@code ctms/contract-migration}）。 </p>
 *
 * <p> <b>扫描做且只做三件事</b>： </p>
 * <ol>
 *   <li> <b>只读</b>合同表（{@code selectContractList}），按「合同类型 → 档案方向」映射决定看哪一侧文本
 *        （{@code PUR} 看乙方 → 供应商方向、{@code SAL} 看甲方 → 客户方向，其余类型跳过）； </li>
 *   <li> 按「档案方向 + 原始名称」<b>内存聚合</b>，只收「该方向档案引用为空 + 该方向文本非空」的合同； </li>
 *   <li> 逐组"先查后写"：库里没有 → 建一条 {@code pending} 草案；已有 → 按状态分档刷新（见下）。 </li>
 * </ol>
 *
 * <p> ⚠ <b>扫描绝不修改合同</b>：本类对 {@code CtmsContractMapper} 只调用 {@code selectContractList}
 * 这一个读方法，{@code party_a}/{@code party_b}/{@code customer_id}/{@code supplier_id} 一个都不写。
 * 回填只发生在"人工认领"（任务 7.3）时，并且逐份合同写变更历史（可回溯）。 </p>
 *
 * <p> ⚠ <b>扫描包含已停用合同</b>（{@code includeDeleted = "1"}）：迁移要覆盖<b>全量历史</b>，
 * 停用只是业务状态、文本仍然"待认领"；若照列表口径排除停用，草案的 {@code contract_count}
 * 就等于不上人工核对的那张清单（"这份合同到底算不算"会变成第二个口径）。
 * 认领回填同样覆盖停用合同：它只改档案引用，不改 {@code del_flag}，不会把停用合同"复活"。 </p>
 *
 * <p> ⚠ <b>分组键必须与数据库唯一索引同口径</b>：{@code uk_party_draft(party_type, raw_name)} 落在
 * {@code utf8mb4_0900_ai_ci} 上（大小写/重音不敏感），所以分组键用
 * {@link MigrationRules#rawNameKey(String)} 而不是原始字符串 —— 否则 {@code Acme} 与 {@code acme}
 * 会被分成两组，第二条 insert 直接撞唯一键（真库 1062 / 接口 500），而内存桩单测抓不到。
 * 展示用的 {@code raw_name} 仍保留首次出现的原文，不做大小写改写。 </p>
 *
 * <p> <b>重复扫描的状态分档（任务 7.2，规格「草案刷新与幂等」三条场景）</b>： </p>
 * <table border="1">
 *   <caption>刷新口径</caption>
 *   <tr><th>库内状态</th><th>本次扫描命中同一「方向 + 文本」时</th><th>是否写库</th></tr>
 *   <tr><td>{@code pending}</td><td>刷新 {@code contract_count}</td>
 *       <td><b>只有计数真的变了才写</b>（未变化行 update 次数为 0）</td></tr>
 *   <tr><td>{@code claimed}</td><td><b>回到 {@code pending}</b> + 清空 {@code matched_id}</td>
 *       <td>总是写（状态必然变化）</td></tr>
 *   <tr><td>{@code ignored}</td><td><b>不动</b>（不复活、<b>连计数也不刷新</b>）</td>
 *       <td>不写</td></tr>
 *   <tr><td>其它值</td><td>抛业务异常（快速失败）</td><td>不写，事务回滚</td></tr>
 * </table>
 *
 * <p> 三条裁决的理由： </p>
 * <ul>
 *   <li> <b>claimed 为什么无条件回到 pending</b>：聚合阶段已经排除了"该方向已绑定档案"的合同，
 *        所以这一组能存在，就等于"确实还有未绑定档案的合同在用这个文本"。不回归的话，这批新合同会
 *        永远停在"有文本、没档案"，而且没有任何入口提醒运维（草案显示已认领、待认领列表里没有它）。 </li>
 *   <li> <b>ignored 为什么连计数都不刷新</b>：忽略是人工的明确否决（"这条文本不需要档案化"），
 *        忽略行既不出现在待认领列表、也不参与认领，计数没有任何消费方；整行不动 = 幂等可验证
 *        （update 次数为 0），也避免"已经忽略了却在暗中跟着数据变"的错觉。将来若前端要展示忽略行的
 *        历史计数，再回来改这一档并补用例。 </li>
 *   <li> <b>未知状态为什么快速失败</b>：状态机只有三个合法取值，其它值说明这一行被绕过接口直接改过。
 *        扫描是批量运维动作，静默跳过会让"到底少了哪些草案"变成没人知道的事；失败时事务整体回滚，
 *        而合同表本来就没被写过，人工修完那一行再重跑即可。 </li>
 * </ul>
 *
 * <p> ⚠ <b>幂等靠"先查后更新"，不靠"插入撞唯一键再兜底"</b>：代码里<b>没有</b>捕获 1062 /
 * {@code DuplicateKeyException} 的分支（那种写法会把唯一键降级成"异常驱动的控制流"，
 * 并且把并发下的重试语义混进业务逻辑）。唯一键只是数据库侧的兜底，扫描的正常路径永远是
 * {@code selectDraftByTypeAndName → insert 或 updateDraft}。 </p>
 *
 * <p> <b>认领状态机</b>（{@link #requireClaimable(String)}）：取草案 + 判定是否可认领，
 * 判定逻辑收敛在 {@link MigrationRules#checkClaimable(String)}（pending/ignored 放行、claimed 拒绝）；
 * 按原始名称的批量回填与变更历史写入是任务 7.3 的内容。 </p>
 *
 * <p> <b>"当前时间 / 当前用户"收敛成 protected 方法</b>（{@link #now()}、{@link #currentUserId()}、
 * {@link #currentUsername()}），单测用子类替换后即可在不起 Spring、不依赖真实时钟与登录上下文的情况下
 * 断言审计列与幂等行为（与 {@code CtmsContractServiceImpl} 同款手法）。 </p>
 *
 * <p> <b>规模说明</b>：扫描一次性载入合同再内存聚合。迁移期（历史数据首次收敛）这个量级可接受；
 * 若合同量级上到十万级，应改成 SQL 侧 {@code GROUP BY}（那需要给合同 Mapper 增加迁移专用查询，
 * 属专项优化，不在本组任务边界内）。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsMigrationServiceImpl implements ICtmsMigrationService
{
    /** 无登录上下文时的用户ID兜底（与合同服务同口径：迁移脚本/定时任务没有登录会话）。 */
    private static final String DEFAULT_USER_ID = "1";

    /** 无登录上下文时的登录名兜底（同上）。 */
    private static final String DEFAULT_USERNAME = "system";

    /** 变更历史的对象类型（与整单编辑同源，避免两处字面量漂移）。 */
    private static final String OBJECT_TYPE_CONTRACT = ContractRules.OBJECT_TYPE_CONTRACT;

    /** 日志（认领"计数 &gt; 0 而实绑 0"的口径告警是排查这类缺陷的唯一线索）。 */
    private static final Logger log = LoggerFactory.getLogger(CtmsMigrationServiceImpl.class);

    /**
     * 聚合键的分隔符。
     *
     * <p> 用 {@code \u0001}（SOH，不可能是公司名里的字符）而不是 {@code |} 或 {@code -}：
     * 「方向 + 文本」若用可打印字符拼接，{@code supplier} + {@code X|Y} 与 {@code supplier|X} + {@code Y}
     * 会算出同一个键，把两条草案静默合并成一条。 </p>
     */
    private static final String KEY_SEPARATOR = "\u0001";

    /** 合同读数端口（只读：本类只调用它的 {@code selectContractList}）。 */
    @Autowired
    private CtmsContractMapper contractMapper;

    /** 草案的读写端口。 */
    @Autowired
    private CtmsPartyDraftMapper partyDraftMapper;

    /**
     * 往来单位档案服务（任务 7.3 的"新建档案后绑定"复用第 3 组的写入与必填口径，
     * <b>不另写一套档案写入</b>）。
     */
    @Autowired
    private ICtmsPartnerService partnerService;

    /** 变更历史端口（认领批量绑定要"每份被更新的合同一条含操作人的历史"）。 */
    @Autowired
    private CtmsChangeLogMapper changeLogMapper;

    /* ==================== 扫描（任务 7.1） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CtmsPartyDraft> scanPartyDrafts()
    {
        List<CtmsPartyDraft> touched = new ArrayList<>();
        Map<String, DraftGroup> groups = aggregate(loadContracts());
        for (DraftGroup group : groups.values())
        {
            CtmsPartyDraft exist = partyDraftMapper.selectDraftByTypeAndName(group.direction, group.rawName);
            if (exist == null)
            {
                CtmsPartyDraft draft = newDraft(group);
                partyDraftMapper.insertDraft(draft);
                touched.add(draft);
                continue;
            }
            // 已有草案：按状态分档处理（规格「草案刷新与幂等」三条场景）
            String status = ContractRules.trimToNull(exist.getStatus());
            if (MigrationRules.STATUS_PENDING.equals(status))
            {
                // 待认领：只在计数真的变了才写库（未变化行不产生无意义的 update）。
                // updateDraft 刻意不写 create_time / create_id —— 幂等 = "行还在、创建痕迹不变"。
                if (!Integer.valueOf(group.contractCount).equals(exist.getContractCount()))
                {
                    exist.setContractCount(group.contractCount);
                    touch(exist);
                    partyDraftMapper.updateDraft(exist);
                    touched.add(exist);
                }
                continue;
            }
            if (MigrationRules.STATUS_CLAIMED.equals(status))
            {
                // 已认领：本组的存在本身就意味着"仍有未绑定档案的合同在用这个文本"
                // （聚合阶段已经把已绑定的合同排除了），所以回到待认领并清空 matched_id。
                // 不这样做的话，那批新合同会永远停在"有文本、没档案"的状态且没有任何入口提醒。
                exist.setStatus(MigrationRules.STATUS_PENDING);
                exist.setMatchedId(null);
                exist.setContractCount(group.contractCount);
                touch(exist);
                partyDraftMapper.updateDraft(exist);
                touched.add(exist);
                continue;
            }
            if (MigrationRules.STATUS_IGNORED.equals(status))
            {
                // 已忽略：绝不复活到待认领（规格场景），且【连计数也不刷新】。
                // 裁决理由：忽略是人工的明确否决（"这条文本不需要档案化"），忽略行既不出现在待认领列表、
                // 也不参与认领，计数没有任何消费方；保持整行不动 = 幂等可验证（update 次数为 0），
                // 也避免"忽略了却在暗中跟着数据变"的错觉。若将来前端要展示忽略行的历史计数，再改这里。
                continue;
            }
            // 状态机只有 pending/claimed/ignored 三个取值：其它值说明这一行被绕过接口直接改过。
            // 扫描是批量运维动作，遇到脏数据【快速失败】比静默跳过好 —— 静默跳过会让"少了哪些草案"
            // 变成没人知道的事；失败时事务整体回滚，合同表本来就没被写过，人工修完这一行再重跑即可。
            throw new ServiceException("草案状态不合法：" + exist.getStatus()
                    + "（只能是 pending/claimed/ignored，该行可能被绕过接口修改过）");
        }
        return touched;
    }

    /* ==================== 草案读取 ==================== */

    @Override
    public List<CtmsPartyDraft> selectDraftList(CtmsPartyDraft query)
    {
        CtmsPartyDraft effective = query == null ? new CtmsPartyDraft() : query;
        // 前端习惯用 status=all 表达"全部状态"：在服务层归一为 null（= 不过滤）。
        // ⚠ 不在 XML 里写 status != 'all'：MyBatis 的 OGNL 把单引号 '1'/字符字面量当 Character，
        //   与 String 比较恒不相等（DEV-ENV §6.43 / CtmsContractMapper.xml 的 includeDeleted 注释）。
        effective.setStatus(normalizeFilter(effective.getStatus()));
        effective.setPartyType(normalizeFilter(effective.getPartyType()));
        return partyDraftMapper.selectDraftList(effective);
    }

    /* ==================== 认领状态机入口（任务 7.2 判定，7.3 批量绑定） ==================== */

    @Override
    public CtmsPartyDraft requireClaimable(String draftId)
    {
        String id = ContractRules.trimToNull(draftId);
        CtmsPartyDraft draft = id == null ? null : partyDraftMapper.selectDraftById(id);
        if (draft == null)
        {
            throw new ServiceException("草案不存在");
        }
        // 认领判定只有这一处（MigrationRules.checkClaimable）：
        // pending/ignored 放行、claimed 拒绝（关键字「已认领」是 7.3 的验收契约）、未知状态拒绝。
        MigrationRules.checkClaimable(draft.getStatus());
        return draft;
    }

    /* ==================== 认领与批量绑定（任务 7.3） ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CtmsPartyDraft claimPartyDraft(String draftId, CtmsMigrationClaimVo request)
    {
        CtmsMigrationClaimVo claim = request == null ? new CtmsMigrationClaimVo() : request;
        // ① 状态守卫：不存在 / 已认领 / 状态非法都在这一处拒绝（7.2 交付的单一入口）
        CtmsPartyDraft draft = requireClaimable(draftId);
        // 草案的 party_type 就是档案方向（customer / supplier）；非法值只可能是脏数据
        String direction = ContractRules.trimToNull(draft.getPartyType());
        if (!MigrationRules.isDirection(direction))
        {
            throw new ServiceException("草案的档案方向不合法：" + draft.getPartyType());
        }
        String rawName = MigrationRules.normalizeRawName(draft.getRawName());
        if (rawName == null)
        {
            throw new ServiceException("草案的原始名称为空，无法认领");
        }

        // ② 定档案：绑定已有 或 新建（新建一律复用第 3 组的档案服务与必填规则）
        String partyId = ContractRules.trimToNull(claim.getPartyId());
        String partyName;
        if (partyId != null)
        {
            partyName = requireExistingParty(direction, partyId);
        }
        else if (MigrationRules.DIRECTION_SUPPLIER.equals(direction))
        {
            CtmsSupplier created = createSupplierForClaim(rawName, claim);
            partyId = created.getId();
            partyName = created.getName();
        }
        else
        {
            CtmsCustomer created = createCustomerForClaim(rawName, claim);
            partyId = created.getId();
            partyName = created.getName();
        }

        // ③ 批量绑定：只碰「该方向引用为空 + 该方向文本（两侧同口径去空格）= raw_name」的合同，逐份写历史
        List<CtmsContract> targets = contractMapper.selectUnboundPartyCandidates(direction, rawName);
        if (targets.isEmpty() && draft.getContractCount() != null && draft.getContractCount().intValue() > 0)
        {
            // 可诊断信号（t16 F1）：草案说"有 N 份合同在用这个文本"，认领却一份都匹配不上。
            // 典型原因是候选比较口径与扫描口径不一致（Java 侧多去了、或 SQL 侧少去了某类空白），
            // 此时草案会在 claimed↔pending 之间永久往复，日志里这一行往往是唯一线索。
            log.warn("迁移认领未绑定任何合同：draftId={} direction={} rawName=[{}] 草案计数={} —— 请核对候选比较口径"
                            + "（CtmsContractMapper.xml 的 trim(本方向文本) = trim(#{rawName}) 必须与"
                            + " MigrationRules.normalizeRawName 同口径；口径表见该方法注释）",
                    draft.getId(), direction, rawName, draft.getContractCount());
        }
        List<CtmsChangeLog> logs = new ArrayList<>();
        for (CtmsContract contract : targets)
        {
            if (contract == null || ContractRules.isBlank(contract.getId()))
            {
                continue;
            }
            contractMapper.bindPartyRef(contract.getId(), direction, partyId, currentUserId(), currentUsername());
            logs.add(buildPartyRefLog(contract.getId(), direction, partyId, partyName));
        }
        if (!logs.isEmpty())
        {
            changeLogMapper.batchInsertChangeLogs(logs);
        }

        // ④ 草案落终态：claimed + matched_id + 本次实际绑定的合同数（比 scan 时的计数更真）
        draft.setStatus(MigrationRules.STATUS_CLAIMED);
        draft.setMatchedId(partyId);
        draft.setContractCount(Integer.valueOf(logs.size()));
        draft.setRemark(ContractRules.trimToNull(claim.getRemark()));
        touch(draft);
        partyDraftMapper.updateDraft(draft);
        return draft;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CtmsPartyDraft ignorePartyDraft(String draftId, String remark)
    {
        String id = ContractRules.trimToNull(draftId);
        CtmsPartyDraft draft = id == null ? null : partyDraftMapper.selectDraftById(id);
        if (draft == null)
        {
            throw new ServiceException("草案不存在");
        }
        // 忽略守卫只有一处（MigrationRules.checkIgnorable）：pending/ignored 放行、claimed 拒绝
        MigrationRules.checkIgnorable(draft.getStatus());
        draft.setStatus(MigrationRules.STATUS_IGNORED);
        draft.setRemark(ContractRules.trimToNull(remark));
        touch(draft);
        partyDraftMapper.updateDraft(draft);
        return draft;
    }

    /**
     * 新建<b>供应商</b>档案（认领的"新建后绑定"路径）：复用 {@code ICtmsPartnerService.insertSupplier}
     * 与 {@code PartnerRules} 的必填口径（简称必填、账期非负），不另写一套档案写入。
     *
     * @param rawName 草案原始文本（名称/简称的兜底来源）
     * @param claim   认领请求
     * @return 新建的供应商（含第 3 组服务端生成的 id）
     */
    private CtmsSupplier createSupplierForClaim(String rawName, CtmsMigrationClaimVo claim)
    {
        CtmsSupplier supplier = new CtmsSupplier();
        supplier.setCode(requirePartyCode(claim));
        // 名称兜底：档案名就是当初写在那份合同上的文本
        String name = ContractRules.trimToNull(claim.getName());
        supplier.setName(name == null ? rawName : name);
        String shortName = ContractRules.trimToNull(claim.getShortName());
        // 供应商简称在规格里必填；迁移期不允许因为"简称没填"卡住认领 → 以草案原始名称兜底
        // 迁移期兜底：供应商简称必填，缺省时以草案原始名称兜底（规格场景⑤）
        supplier.setShortName(shortName == null ? rawName : shortName);
        supplier.setTaxNo(claim.getTaxNo());
        supplier.setContactName(claim.getContactName());
        supplier.setContactPhone(claim.getContactPhone());
        supplier.setAddress(claim.getAddress());
        supplier.setBankName(claim.getBankName());
        supplier.setBankAccount(claim.getBankAccount());
        supplier.setCreditLimit(claim.getCreditLimit());
        supplier.setLevel(claim.getLevel());
        supplier.setSupplyScope(claim.getSupplyScope());
        supplier.setPaymentDays(claim.getPaymentDays());
        partnerService.insertSupplier(supplier);
        return supplier;
    }

    /**
     * 新建<b>客户</b>档案（认领的"新建后绑定"路径）：口径同
     * {@link #createSupplierForClaim(String, CtmsMigrationClaimVo)}，但客户简称是选填、不做兜底。
     *
     * @param rawName 草案原始文本（名称兜底来源）
     * @param claim   认领请求
     * @return 新建的客户（含第 3 组服务端生成的 id）
     */
    private CtmsCustomer createCustomerForClaim(String rawName, CtmsMigrationClaimVo claim)
    {
        CtmsCustomer customer = new CtmsCustomer();
        customer.setCode(requirePartyCode(claim));
        String name = ContractRules.trimToNull(claim.getName());
        customer.setName(name == null ? rawName : name);
        customer.setShortName(ContractRules.trimToNull(claim.getShortName()));
        customer.setTaxNo(claim.getTaxNo());
        customer.setContactName(claim.getContactName());
        customer.setContactPhone(claim.getContactPhone());
        customer.setAddress(claim.getAddress());
        customer.setBankName(claim.getBankName());
        customer.setBankAccount(claim.getBankAccount());
        customer.setCreditLimit(claim.getCreditLimit());
        customer.setLevel(claim.getLevel());
        partnerService.insertCustomer(customer);
        return customer;
    }

    /**
     * 取新建档案的编码（<b>不兜底</b>）。
     *
     * <p> 编码是全局唯一键、也是第 3 组的既定口径；自动生成会引入第二套编码规则，
     * 所以这里直接要求调用方给出编码并给出可读提示。 </p>
     *
     * @param claim 认领请求
     * @return 编码
     */
    private String requirePartyCode(CtmsMigrationClaimVo claim)
    {
        String code = ContractRules.trimToNull(claim.getCode());
        if (code == null)
        {
            throw new ServiceException("新建档案必须提供编码");
        }
        return code;
    }

    /**
     * 绑定已有档案：档案必须存在（按方向查对应的档案表），返回档案名称。
     *
     * @param direction 档案方向
     * @param partyId   档案ID
     * @return 档案名称
     */
    private String requireExistingParty(String direction, String partyId)
    {
        if (MigrationRules.DIRECTION_SUPPLIER.equals(direction))
        {
            CtmsSupplier supplier = partnerService.selectSupplierById(partyId);
            if (supplier == null)
            {
                throw new ServiceException("供应商档案不存在");
            }
            return supplier.getName();
        }
        CtmsCustomer customer = partnerService.selectCustomerById(partyId);
        if (customer == null)
        {
            throw new ServiceException("客户档案不存在");
        }
        return customer.getName();
    }

    /**
     * 构造一条"档案引用被回填"的变更历史。
     *
     * <p> 口径与整单编辑完全一致（{@code CtmsContractServiceImpl.buildLog}）：字段名用
     * {@link ContractRules#FIELD_CUSTOMER}/{@link ContractRules#FIELD_SUPPLIER}，
     * <b>字段值是档案ID</b>（不是名称），来源为 {@code auto}（认领是迁移动作，不是人工逐字改字段），
     * 操作人取当前上下文；档案名称放进备注，纯粹为了人读时间线时不至于只看到一个 UUID。 </p>
     *
     * @param contractId 合同ID
     * @param direction  档案方向
     * @param partyId    档案ID
     * @param partyName  档案名称（备注用）
     * @return 变更历史
     */
    private CtmsChangeLog buildPartyRefLog(String contractId, String direction, String partyId, String partyName)
    {
        CtmsChangeLog log = new CtmsChangeLog();
        log.setId(IdUtils.fastSimpleUUID());
        log.setContractId(contractId);
        log.setFieldName(MigrationRules.DIRECTION_SUPPLIER.equals(direction)
                ? ContractRules.FIELD_SUPPLIER : ContractRules.FIELD_CUSTOMER);
        // 旧值为空（迁移前只有文本、没有档案引用）；新值是档案ID，与本仓库既有的字段值口径一致
        log.setOldValue(null);
        log.setNewValue(partyId);
        log.setNote("迁移认领绑定：" + (partyName == null ? "" : partyName));
        log.setSource(CtmsChangeLog.SOURCE_AUTO);
        log.setOperatorId(currentUserId());
        log.setOperatorName(currentNickName());
        log.setObjectType(OBJECT_TYPE_CONTRACT);
        log.setObjectId(contractId);
        log.setCreateId(currentUserId());
        log.setCreateBy(currentUsername());
        log.setCreateTime(now());
        return log;
    }

    /* ==================== 内部：读合同 + 聚合 ==================== */

    /**
     * 读取参与迁移的合同（<b>只读</b>，含已停用，理由见类注释）。
     *
     * <p> 刻意直接调 {@code CtmsContractMapper} 而不是 {@code ICtmsContractService}：
     * 后者按当前登录用户的数据范围裁剪（那是合同台账的展示口径），而扫描必须是全库同一口径
     * （理由见 {@code ICtmsMigrationService} 的类注释）。 </p>
     *
     * @return 合同集合（不含任何写操作）
     */
    private List<CtmsContract> loadContracts()
    {
        CtmsContract query = new CtmsContract();
        // "1" = 包含已停用；XML 里的判定写成 includeDeleted != "1"（双引号，见该文件注释）
        query.setIncludeDeleted("1");
        List<CtmsContract> contracts = contractMapper.selectContractList(query);
        return contracts == null ? new ArrayList<CtmsContract>() : contracts;
    }

    /**
     * 按「档案方向 + 原始名称」聚合未绑定档案且文本非空的合同。
     *
     * @param contracts 合同集合
     * @return 聚合结果（保持首次出现顺序；键是方向 + 原始名称的分组键）
     */
    private Map<String, DraftGroup> aggregate(List<CtmsContract> contracts)
    {
        Map<String, DraftGroup> groups = new LinkedHashMap<>();
        for (CtmsContract contract : contracts)
        {
            if (contract == null)
            {
                continue;
            }
            // ① 类型 → 方向：不参与映射的类型（COO/LAB/FIN/NDA/OTH 与未知值）直接跳过，不猜方向
            String direction = MigrationRules.directionOf(contract.getType());
            if (direction == null)
            {
                continue;
            }
            // ② 该方向的原始文本：空白不生成草案
            String rawName = MigrationRules.rawTextOf(direction, contract.getPartyA(), contract.getPartyB());
            if (rawName == null)
            {
                continue;
            }
            // ③ 该方向已绑定档案 → 不需要迁移（只看本方向的列，另一侧的误填不影响本方向）
            if (MigrationRules.referenceIdOf(direction, contract.getCustomerId(), contract.getSupplierId()) != null)
            {
                continue;
            }
            String key = direction + KEY_SEPARATOR + MigrationRules.rawNameKey(rawName);
            DraftGroup group = groups.get(key);
            if (group == null)
            {
                group = new DraftGroup(direction, rawName);
                groups.put(key, group);
            }
            group.contractCount++;
        }
        return groups;
    }

    /**
     * 按聚合结果建一条待认领草案（审计列走当前上下文，无上下文时兜底）。
     *
     * @param group 聚合组
     * @return 草案（未落库）
     */
    private CtmsPartyDraft newDraft(DraftGroup group)
    {
        Date time = now();
        CtmsPartyDraft draft = new CtmsPartyDraft();
        // 主键由服务端生成：与本仓库其它业务表一致（库表无默认值）
        draft.setId(IdUtils.fastSimpleUUID());
        draft.setPartyType(group.direction);
        draft.setRawName(group.rawName);
        draft.setContractCount(group.contractCount);
        draft.setStatus(MigrationRules.STATUS_PENDING);
        // 尚未认领：matched_id 保持空（多态列，认领时才写）
        draft.setMatchedId(null);
        // create_id / create_by 在 DDL 里可空：扫描可能没有登录上下文，这里给兜底值而不是写 null，
        // 目的是让运维能看出来"这条草案是哪次扫描建的"（列本身仍保持可空，见实体注释）
        draft.setCreateId(currentUserId());
        draft.setCreateBy(currentUsername());
        draft.setCreateTime(time);
        draft.setUpdateId(currentUserId());
        draft.setUpdateBy(currentUsername());
        draft.setUpdateTime(time);
        return draft;
    }

    /**
     * 写更新审计列（{@code update_id}/{@code update_by}/{@code update_time}）。
     *
     * <p> 刻意<b>只碰更新列</b>：{@code create_time}/{@code create_id} 是"这条草案是哪次扫描建的"的痕迹，
     * 幂等刷新不得改写它们（任务 7.2 的验收明确要求行数与 {@code create_time} 都不变）。 </p>
     *
     * @param draft 草案（就地写入更新列）
     */
    private void touch(CtmsPartyDraft draft)
    {
        draft.setUpdateId(currentUserId());
        draft.setUpdateBy(currentUsername());
        draft.setUpdateTime(now());
    }

    /**
     * 查询条件的空白归一（空白与 {@code all} 都归一为 null = 不过滤）。
     *
     * @param value 入参值
     * @return 归一后的值
     */
    private String normalizeFilter(String value)
    {
        String trimmed = ContractRules.trimToNull(value);
        return "all".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /* ==================== 可注入点（单测替换） ==================== */

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
     * <p> 无登录上下文时捕获异常并回落为 {@value #DEFAULT_USER_ID}：扫描可能由运维脚本/定时任务触发，
     * 不兜底会直接抛「获取用户ID异常」，把一次只读扫描变成失败。 </p>
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
     * 要么是明确的空值（前端展示占位符）—— 把系统兜底值当操作人会让审计失真
     * （与 {@code CtmsContractServiceImpl.currentNickName} 同口径）。 </p>
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
     * 聚合分组（方向 + 首次出现的原始文本 + 命中合同数）。
     *
     * <p> 原始文本刻意保留<b>首次出现</b>的写法：幂等键在数据库层是大小写不敏感的，
     * 但展示给用户看的 {@code raw_name} 应当是"当初就是这么写的"，不做大小写改写。 </p>
     */
    private static final class DraftGroup
    {
        /** 档案方向（customer / supplier）。 */
        private final String direction;

        /** 首次出现的原始文本（展示用）。 */
        private final String rawName;

        /** 命中合同数（该方向未绑定档案且文本与本组等价的合同数）。 */
        private int contractCount;

        private DraftGroup(String direction, String rawName)
        {
            this.direction = direction;
            this.rawName = rawName;
        }
    }
}
