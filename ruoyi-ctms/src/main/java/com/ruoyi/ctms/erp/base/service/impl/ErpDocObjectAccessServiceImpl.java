package com.ruoyi.ctms.erp.base.service.impl;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.base.ErpDocType;
import com.ruoyi.ctms.erp.base.mapper.ErpDocLookupMapper;
import com.ruoyi.ctms.erp.base.service.IErpDocObjectAccess;

/**
 * <p> 单据对象访问校验的实现（2.0 B4 任务 1.4 的附件接入点 ②）。 </p>
 *
 * <p> <b>一条分派、一处口径</b>：6 类单据（采购单 / 销售订单 / 出入库 / 盘点 / 调拨）
 * 都走 {@link ErpDocLookupMapper#selectDocHeaderByType}（按单据类型分支的显式 SQL），
 * 存在性判定与数据范围判定只有这一份实现 —— 附件服务、后续的单据详情/导出/打印
 * 都能复用，不会出现"附件能看、单据详情不能看"这类不一致。 </p>
 *
 * <p> <b>错误文案</b>（前端与验收脚本按字符串断言）： </p>
 * <ul>
 *   <li> 未注册类型：{@code 未注册的对象类型：<值>}（与 B3 的 {@code CtmsAttachmentRules} 同款）； </li>
 *   <li> 对象不存在：{@code <单据名>不存在：<id>}（合同侧是"合同不存在"，同源风格）； </li>
 *   <li> 超出范围：{@code 无权访问该<单据名>}，业务码 <b>403</b>。 </li>
 * </ul>
 *
 * @author 二开
 */
@Service
public class ErpDocObjectAccessServiceImpl implements IErpDocObjectAccess
{
    /** 数据范围判定的表别名（本查询只查表头，别名用默认的 {@code d} 即可）。 */
    private static final String SCOPE_HINT = "单据";

    @Autowired
    private ErpDocLookupMapper erpDocLookupMapper;

    @Override
    public boolean supports(String objectType)
    {
        return ErpDocType.ofCode(objectType) != null;
    }

    @Override
    public void checkObjectAccess(String objectType, String objectId)
    {
        ErpDocType type = ErpDocType.ofCode(objectType);
        if (type == null)
        {
            throw new ServiceException("未注册的对象类型：" + objectType);
        }
        String id = trimToNull(objectId);
        if (id == null)
        {
            throw new ServiceException("对象标识不能为空");
        }
        Map<String, Object> row = erpDocLookupMapper.selectDocHeaderByType(type.getCode(), id);
        if (row == null || value(row, "id") == null)
        {
            // 与合同侧"合同不存在"同源：按标识取不到就是不存在
            throw new ServiceException(type.getLabel() + "不存在：" + id);
        }
        if (hasAllDataScope())
        {
            return;
        }
        if (!canAccessRow(value(row, "createId"), value(row, "deptId")))
        {
            throw new ServiceException("无权访问该" + type.getLabel(), 403);
        }
    }

    /**
     * <p> 当前用户是否具备"全部数据"（超管或任一角色 <code>data_scope='1'</code>）。 </p>
     *
     * <p> 抽成 protected 方法是为了让"范围外 403"这条分支能被单测命中：
     * 无登录上下文时 {@code ErpDocScope.hasAllScope()} 按兜底返回 true，
     * 单测用子类把它固定为 false 即可（与 B3 的 {@code CtmsContractServiceImplTest}
     * 用子类替换时间/上下文的做法同源）。 </p>
     *
     * @return 具备返回 true
     */
    protected boolean hasAllDataScope()
    {
        return ErpDocScope.hasAllScope();
    }

    /**
     * 单行可见性判定（口径唯一处 {@link ErpDocScope#matches}）。
     *
     * @param rowCreateId 行的 {@code create_id}
     * @param rowDeptId   行的 {@code dept_id}
     * @return 可见返回 true
     */
    protected boolean canAccessRow(final String rowCreateId, final String rowDeptId)
    {
        return ErpDocScope.matches(ErpDocScope.currentDataScopes(),
                ErpDocScope.currentUserId(), ErpDocScope.currentDeptId(),
                rowCreateId, rowDeptId, new ErpDocScope.DeptAncestorsLookup()
                {
                    @Override
                    public String ancestorsOf(String deptId)
                    {
                        return ErpDocObjectAccessServiceImpl.this.ancestorsOf(deptId);
                    }
                });
    }

    /**
     * 单据对象的展示文案（写变更历史/日志时用；未知类型返回"单据"）。
     *
     * @param objectType 对象类型
     * @return 中文名
     */
    public String labelOf(String objectType)
    {
        ErpDocType type = ErpDocType.ofCode(objectType);
        return type == null ? SCOPE_HINT : type.getLabel();
    }

    /**
     * 部门祖先链（范围判定的"含下级"分支用；查询失败按"无祖先"处理，
     * 保守地不放宽范围）。
     *
     * @param deptId 部门ID
     * @return 祖先链；查不到返回 null
     */
    private String ancestorsOf(String deptId)
    {
        if (trimToNull(deptId) == null)
        {
            return null;
        }
        try
        {
            return erpDocLookupMapper.selectDeptAncestors(deptId.trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 从结果 Map 取字符串值（先按驼峰键，再兜底下划线键，
     * 使本实现与 {@code map-underscore-to-camel-case} 配置无关）。
     *
     * @param row 结果行
     * @param key 驼峰键
     * @return 字符串值；无值返回 null
     */
    private static String value(Map<String, Object> row, String key)
    {
        Object value = row.get(key);
        if (value == null)
        {
            // 下划线兜底：deptId → dept_id
            String snake = key.replaceAll("([A-Z])", "_$1").toLowerCase();
            value = row.get(snake);
        }
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text.trim();
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
