package com.ruoyi.ctms.erp.ledger.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.ctms.domain.CtmsProductType;
import com.ruoyi.ctms.erp.base.ErpDocScope;
import com.ruoyi.ctms.erp.ledger.ErpLedgerRules;
import com.ruoyi.ctms.erp.ledger.ErpProductTypeTree;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockBalanceMapper;
import com.ruoyi.ctms.erp.ledger.mapper.ErpStockLedgerQueryMapper;
import com.ruoyi.ctms.erp.ledger.service.IErpStockLedgerQueryService;
import com.ruoyi.ctms.mapper.CtmsProductTypeMapper;

/**
 * <p> <b>库存账只读查询服务实现</b>（B4 任务 7.1~7.3）。 </p>
 *
 * <p> 三条实现口径（评审重点）： </p>
 * <ol>
 *   <li> <b>数据范围片段一律由服务端拼</b>（{@link ErpDocScope#buildDataScopeSql(String)}，
>        取登录上下文里的 userId/deptId，请求参数永不进入片段），
>        且别名固定用 {@link ErpLedgerRules#ALIAS_LEDGER} —— 结存表没有
>        {@code dept_id}/{@code create_id} 列，片段是注入到"按流水判定可见性"的子查询里的
>        （见 {@code ErpStockBalanceMapper.xml}），别名写错会让 SQL 直接报错而不是静默越权； </li>
 *   <li> <b>单行详情先查存在性再查可见性</b>：不存在给 404、范围外给 403 ——
>        两者必须可区分，否则验收脚本没法断言"越权被拒而不是数据不存在"； </li>
 *   <li> <b>列表与下钻同源</b>：下钻只是多带（物料, 仓库）条件，走同一个
>        {@code selectLedgerRows}，所以"下钻看到的总和"必然等于"列表筛选后看到的"。 </li>
 * </ol>
 *
 * @author 二开
 */
@Service
public class ErpStockLedgerQueryServiceImpl implements IErpStockLedgerQueryService
{
    /** 结存明细 Mapper（只读）。 */
    @Autowired
    private ErpStockBalanceMapper stockBalanceMapper;

    /** 流水只读 Mapper。 */
    @Autowired
    private ErpStockLedgerQueryMapper stockLedgerQueryMapper;

    /** 商品类型档案（只读复用 B3 的 Mapper，用于"选父带子"展开）。 */
    @Autowired
    private CtmsProductTypeMapper productTypeMapper;

    @Override
    public List<ErpStockBalance> selectBalanceList(ErpStockBalance query)
    {
        ErpStockBalance effective = query == null ? new ErpStockBalance() : query;
        effective.setProductTypeIds(expandProductTypes(effective.getProductTypeId()));
        effective.setDataScopeSql(currentScopeSql());
        return stockBalanceMapper.selectBalanceList(effective);
    }

    @Override
    public ErpStockBalance selectBalanceByKey(String productId, String warehouseId)
    {
        ErpStockBalance row = stockBalanceMapper.selectBalanceByKey(productId, warehouseId);
        if (row == null)
        {
            throw new ServiceException(ErpLedgerRules.MSG_STOCK_NOT_FOUND, ErpLedgerRules.CODE_NOT_FOUND);
        }
        if (!visibleBalance(productId, warehouseId))
        {
            throw new ServiceException(ErpLedgerRules.MSG_SCOPE_FORBIDDEN, ErpLedgerRules.CODE_FORBIDDEN);
        }
        return row;
    }

    @Override
    public List<ErpStockLedgerRow> selectLedgerRows(ErpStockLedgerRow query)
    {
        ErpStockLedgerRow effective = query == null ? new ErpStockLedgerRow() : query;
        effective.setDataScopeSql(currentScopeSql());
        return stockLedgerQueryMapper.selectLedgerRows(effective);
    }

    @Override
    public ErpStockLedgerRow selectLedgerRow(String id)
    {
        ErpStockLedgerRow row = stockLedgerQueryMapper.selectLedgerRowById(id);
        if (row == null)
        {
            throw new ServiceException(ErpLedgerRules.MSG_LEDGER_NOT_FOUND, ErpLedgerRules.CODE_NOT_FOUND);
        }
        if (!visibleLedger(id))
        {
            throw new ServiceException(ErpLedgerRules.MSG_SCOPE_FORBIDDEN, ErpLedgerRules.CODE_FORBIDDEN);
        }
        return row;
    }

    @Override
    public int countBalance()
    {
        return stockBalanceMapper.countBalance();
    }

    /**
     * 当前用户的数据范围 SQL 片段（生产实现只此一处，别名固定 {@link ErpLedgerRules#ALIAS_LEDGER}）。
     *
     * <p> 抽成可覆写方法，是为了让"范围外 403 / 不存在 404"两个分支能<b>脱登录态单测</b>：
     * 单测里没有 Spring Security 上下文，{@link ErpDocScope#buildDataScopeSql(String)} 会
     * 按它自己的兜底返回 {@code null}（"不受限"，见其类注释），
     * 于是范围分支永远不被执行 = 等于没测。测试子类覆写本方法返回一个固定片段即可。 </p>
     *
     * <p> ⚠ 覆写只允许出现在测试里：生产代码若覆写它，就等于绕开了平台的角色档位判定。 </p>
     *
     * @return SQL 片段；不受限返回 null
     */
    protected String currentScopeSql()
    {
        return ErpDocScope.buildDataScopeSql(ErpLedgerRules.ALIAS_LEDGER);
    }

    /**
     * 把"商品类型根节点"展开成子树 ID 列表（空白 → null，表示不过滤）。
     *
     * @param productTypeId 根类型ID
     * @return 子树ID列表；无需过滤时返回 null
     */
    private List<String> expandProductTypes(String productTypeId)
    {
        String root = ErpLedgerRules.trimToNull(productTypeId);
        if (root == null)
        {
            return null;
        }
        List<CtmsProductType> types = productTypeMapper.selectProductTypeList(new CtmsProductType());
        return ErpProductTypeTree.subtreeIds(types, root);
    }

    /**
     * 某（物料, 仓库）是否落在当前用户的数据范围内。
     *
     * <p> 实现方式与列表<b>完全同一处片段</b>：把（物料, 仓库）当条件再查一次，
     * 查得到即可见 —— 这样"列表里看得见的行"与"详情放行的行"不可能漂移。 </p>
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 可见返回 true
     */
    private boolean visibleBalance(String productId, String warehouseId)
    {
        String scope = currentScopeSql();
        if (scope == null)
        {
            return true;
        }
        ErpStockBalance probe = new ErpStockBalance();
        probe.setProductId(productId);
        probe.setWarehouseId(warehouseId);
        probe.setDataScopeSql(scope);
        List<ErpStockBalance> rows = stockBalanceMapper.selectBalanceList(probe);
        return rows != null && !rows.isEmpty();
    }

    /**
     * 某条流水是否落在当前用户的数据范围内（同上：同一处片段）。
     *
     * @param id 流水ID
     * @return 可见返回 true
     */
    private boolean visibleLedger(String id)
    {
        String scope = currentScopeSql();
        if (scope == null)
        {
            return true;
        }
        ErpStockLedgerRow probe = new ErpStockLedgerRow();
        probe.setLedgerId(id);
        probe.setDataScopeSql(scope);
        List<ErpStockLedgerRow> rows = stockLedgerQueryMapper.selectLedgerRows(probe);
        return rows != null && !rows.isEmpty();
    }
}
