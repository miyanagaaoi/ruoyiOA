package com.ruoyi.ctms.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.ctms.domain.CtmsCustomer;
import com.ruoyi.ctms.domain.CtmsSupplier;
import com.ruoyi.ctms.mapper.CtmsPartnerMapper;
import com.ruoyi.ctms.service.ICtmsPartnerService;
import com.ruoyi.ctms.support.PartnerRules;

/**
 * <p> 往来单位档案（客户 / 供应商）的业务实现（B3 §3，规格 ctms/business-partners）。 </p>
 *
 * <h3>实现口径（逐条对应规格场景）</h3>
 * <ol>
 *   <li> <b>唯一性</b>：编码与名称在库内唯一，<b>查重不排除停用行</b>（停用项也占号），
 *        所以查重一律走 {@code selectXxxByCode}/{@code selectXxxByName}； </li>
 *   <li> <b>必填</b>：客户编码/名称；供应商再加简称（新增与修改两条路径都校验）；
 *        账期天数为非负整数（{@code 0} 与 {@code null} 都合法）； </li>
 *   <li> <b>引用保护</b>：删除前统计"引用该档案的合同数"，&gt;0 即拒绝；
 *        合同表 {@code t_ctms_contract} 可能尚未创建，所以先探测
 *        {@code existsContractTable()}，为 0 时直接放行删除（避免缺表报错）； </li>
 *   <li> <b>无联动</b>：启停用只改 {@code enable_flag}，<b>不</b>触碰任何合同记录——
 *        "停用后历史合同仍能展示档案名称"是"不做级联"的自然结果，
 *        因此这里绝不能出现任何级联更新； </li>
 *   <li> <b>数据范围</b>：档案不做隔离，本实现<b>刻意不</b>注入任何范围条件。 </li>
 * </ol>
 *
 * <p> ⚠ 当前用户信息收敛成 {@link #currentUserId()} / {@link #currentUsername()} 两个
 * {@code protected} 方法：{@code SecurityUtils} 在没有登录上下文时会抛
 * {@code ServiceException(401)}，而档案写入在<b>迁移/初始化/定时任务</b>等系统场景下
 * 本来就没有登录上下文。把它收敛成一处既是兜底，也让服务层能被内存桩单测直接驱动。 </p>
 *
 * @author 二开
 */
@Service
public class CtmsPartnerServiceImpl implements ICtmsPartnerService
{
    /** 启用标志：启用 */
    private static final String ENABLE_FLAG_YES = "1";

    /** 无登录上下文时的兜底用户ID（RuoYi 内置管理员，仅用于系统写入） */
    private static final String SYSTEM_USER_ID = "1";

    /** 无登录上下文时的兜底登录名（仅用于系统写入） */
    private static final String SYSTEM_USERNAME = "system";

    @Autowired
    private CtmsPartnerMapper partnerMapper;

    /* ==================== 客户档案 ==================== */

    @Override
    public List<CtmsCustomer> selectCustomerList(CtmsCustomer query)
    {
        // 档案管理页要看停用项：enableFlag 为 null 就表示"不限"，不能在这里偷偷补 '1'
        CtmsCustomer q = query == null ? new CtmsCustomer() : query;
        return partnerMapper.selectCustomerList(q);
    }

    @Override
    public List<CtmsCustomer> selectCustomerOptions()
    {
        // 选择器口径：只返回启用中的档案（规格「停用后不可新选」）
        CtmsCustomer q = new CtmsCustomer();
        q.setEnableFlag(ENABLE_FLAG_YES);
        return partnerMapper.selectCustomerList(q);
    }

    @Override
    public CtmsCustomer selectCustomerById(String id)
    {
        return partnerMapper.selectCustomerById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertCustomer(CtmsCustomer c)
    {
        PartnerRules.checkCustomerRequired(c);
        PartnerRules.checkEnableFlag(c.getEnableFlag());
        // 查重含停用行：编码/名称全局唯一，停用项也占号
        if (partnerMapper.selectCustomerByCode(c.getCode()) != null)
        {
            throw new ServiceException("客户编码已存在");
        }
        if (partnerMapper.selectCustomerByName(c.getName()) != null)
        {
            throw new ServiceException("客户名称已存在");
        }

        Date now = DateUtils.getNowDate();
        c.setId(IdUtils.fastSimpleUUID());
        c.setCreateId(currentUserId());
        c.setCreateBy(currentUsername());
        c.setCreateTime(now);
        // update_time 是 NOT NULL：新增时也要给值，不能留给数据库默认值兜底
        c.setUpdateTime(now);
        if (c.getEnableFlag() == null)
        {
            c.setEnableFlag(ENABLE_FLAG_YES);
        }
        partnerMapper.insertCustomer(c);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCustomer(CtmsCustomer c)
    {
        PartnerRules.checkCustomerRequired(c);
        CtmsCustomer exist = partnerMapper.selectCustomerById(c.getId());
        if (exist == null)
        {
            throw new ServiceException("客户档案不存在");
        }
        PartnerRules.checkEnableFlag(c.getEnableFlag());

        // 唯一性：只有命中"别的行"才算冲突（命中自己说明编码/名称没变）
        CtmsCustomer byCode = partnerMapper.selectCustomerByCode(c.getCode());
        if (byCode != null && !byCode.getId().equals(c.getId()))
        {
            throw new ServiceException("客户编码已存在");
        }
        CtmsCustomer byName = partnerMapper.selectCustomerByName(c.getName());
        if (byName != null && !byName.getId().equals(c.getId()))
        {
            throw new ServiceException("客户名称已存在");
        }

        // enable_flag 是 NOT NULL：本次未提交时沿用库里的值，避免全字段覆盖把它冲成 null
        if (c.getEnableFlag() == null)
        {
            c.setEnableFlag(exist.getEnableFlag());
        }
        // 审计列一律服务端写入，不接受前端传值
        c.setUpdateId(currentUserId());
        c.setUpdateBy(currentUsername());
        c.setUpdateTime(DateUtils.getNowDate());
        partnerMapper.updateCustomer(c);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCustomerById(String id)
    {
        CtmsCustomer exist = partnerMapper.selectCustomerById(id);
        if (exist == null)
        {
            throw new ServiceException("客户档案不存在");
        }
        // 合同表可能还没建（B3 第 4 组）：先探测再统计，缺表时视为"无引用"放行删除
        if (partnerMapper.existsContractTable() > 0 && partnerMapper.countCustomerReferences(id) > 0)
        {
            throw new ServiceException("客户档案已被合同引用，无法删除");
        }
        partnerMapper.deleteCustomerById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeCustomerStatus(String id, String enableFlag)
    {
        PartnerRules.checkEnableFlag(enableFlag);
        CtmsCustomer exist = partnerMapper.selectCustomerById(id);
        if (exist == null)
        {
            throw new ServiceException("客户档案不存在");
        }
        // 只改启用标志（+ 审计列）：以库里的行为基准做全量覆盖，其余业务列原样写回。
        // 这里刻意不写任何级联 —— 停用不影响已引用它的历史合同。
        exist.setEnableFlag(enableFlag == null ? ENABLE_FLAG_YES : enableFlag);
        exist.setUpdateId(currentUserId());
        exist.setUpdateBy(currentUsername());
        exist.setUpdateTime(DateUtils.getNowDate());
        return partnerMapper.updateCustomer(exist);
    }

    /* ==================== 供应商档案 ==================== */

    @Override
    public List<CtmsSupplier> selectSupplierList(CtmsSupplier query)
    {
        CtmsSupplier q = query == null ? new CtmsSupplier() : query;
        return partnerMapper.selectSupplierList(q);
    }

    @Override
    public List<CtmsSupplier> selectSupplierOptions()
    {
        CtmsSupplier q = new CtmsSupplier();
        q.setEnableFlag(ENABLE_FLAG_YES);
        return partnerMapper.selectSupplierList(q);
    }

    @Override
    public CtmsSupplier selectSupplierById(String id)
    {
        return partnerMapper.selectSupplierById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertSupplier(CtmsSupplier s)
    {
        // 供应商比客户严：简称必填（规格明确要求，不要与客户"统一"）
        PartnerRules.checkSupplierRequired(s);
        PartnerRules.checkPaymentDays(s.getPaymentDays());
        PartnerRules.checkEnableFlag(s.getEnableFlag());
        if (partnerMapper.selectSupplierByCode(s.getCode()) != null)
        {
            throw new ServiceException("供应商编码已存在");
        }
        if (partnerMapper.selectSupplierByName(s.getName()) != null)
        {
            throw new ServiceException("供应商名称已存在");
        }

        Date now = DateUtils.getNowDate();
        s.setId(IdUtils.fastSimpleUUID());
        s.setCreateId(currentUserId());
        s.setCreateBy(currentUsername());
        s.setCreateTime(now);
        s.setUpdateTime(now);
        if (s.getEnableFlag() == null)
        {
            s.setEnableFlag(ENABLE_FLAG_YES);
        }
        partnerMapper.insertSupplier(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSupplier(CtmsSupplier s)
    {
        // 修改路径同样校验简称必填（规格：新增或修改提交空简称都必须被拒绝）
        PartnerRules.checkSupplierRequired(s);
        PartnerRules.checkPaymentDays(s.getPaymentDays());
        CtmsSupplier exist = partnerMapper.selectSupplierById(s.getId());
        if (exist == null)
        {
            throw new ServiceException("供应商档案不存在");
        }
        PartnerRules.checkEnableFlag(s.getEnableFlag());

        CtmsSupplier byCode = partnerMapper.selectSupplierByCode(s.getCode());
        if (byCode != null && !byCode.getId().equals(s.getId()))
        {
            throw new ServiceException("供应商编码已存在");
        }
        CtmsSupplier byName = partnerMapper.selectSupplierByName(s.getName());
        if (byName != null && !byName.getId().equals(s.getId()))
        {
            throw new ServiceException("供应商名称已存在");
        }

        if (s.getEnableFlag() == null)
        {
            s.setEnableFlag(exist.getEnableFlag());
        }
        s.setUpdateId(currentUserId());
        s.setUpdateBy(currentUsername());
        s.setUpdateTime(DateUtils.getNowDate());
        partnerMapper.updateSupplier(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSupplierById(String id)
    {
        CtmsSupplier exist = partnerMapper.selectSupplierById(id);
        if (exist == null)
        {
            throw new ServiceException("供应商档案不存在");
        }
        if (partnerMapper.existsContractTable() > 0 && partnerMapper.countSupplierReferences(id) > 0)
        {
            throw new ServiceException("供应商档案已被合同引用，无法删除");
        }
        partnerMapper.deleteSupplierById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeSupplierStatus(String id, String enableFlag)
    {
        PartnerRules.checkEnableFlag(enableFlag);
        CtmsSupplier exist = partnerMapper.selectSupplierById(id);
        if (exist == null)
        {
            throw new ServiceException("供应商档案不存在");
        }
        exist.setEnableFlag(enableFlag == null ? ENABLE_FLAG_YES : enableFlag);
        exist.setUpdateId(currentUserId());
        exist.setUpdateBy(currentUsername());
        exist.setUpdateTime(DateUtils.getNowDate());
        return partnerMapper.updateSupplier(exist);
    }

    /* ==================== 当前用户（可测的收敛点） ==================== */

    /**
     * 当前登录用户ID；<b>无登录上下文</b>时回落到 {@code "1"}。
     *
     * <p> 兜底仅用于无登录上下文的系统写入（历史数据迁移、初始化脚本、定时任务等），
     * 正常的 Web 请求一定有上下文，不会走到这里。 </p>
     *
     * @return 用户ID
     */
    protected String currentUserId()
    {
        try
        {
            String userId = SecurityUtils.getUserId();
            return userId == null ? SYSTEM_USER_ID : userId;
        }
        catch (Exception e)
        {
            // 仅用于无登录上下文的系统写入（迁移/初始化）
            return SYSTEM_USER_ID;
        }
    }

    /**
     * 当前登录用户名；<b>无登录上下文</b>时回落到 {@code "system"}。
     *
     * <p> 兜底仅用于无登录上下文的系统写入（历史数据迁移、初始化脚本、定时任务等）。 </p>
     *
     * @return 登录名
     */
    protected String currentUsername()
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return username == null ? SYSTEM_USERNAME : username;
        }
        catch (Exception e)
        {
            // 仅用于无登录上下文的系统写入（迁移/初始化）
            return SYSTEM_USERNAME;
        }
    }
}
