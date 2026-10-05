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
    public ErpStockBalance prepareQuery(ErpStockBalance query)
    {
        ErpStockBalance effective = query == null ? new ErpStockBalance() : query;
        effective.setProductTypeIds(expandProductTypes(effective.getProductTypeId()));
        effective.setDataScopeSql(currentScopeSql());
        return effective;
    }

    @Override
    public List<ErpStockBalance> selectBalanceList(ErpStockBalance query)
    {
        ErpStockBalance effective = query == null ? new ErpStockBalance() : query;
        // ⚠ F-01：**只在未 prepare 过时**才在这里展开类型子树。展开要读"类型清单"（全表），
        //   而这种辅助查询一旦落在分页上下文里就会被 PageHelper 截成前 pageSize 行
        //   （见 prepareQuery 的 javadoc）。因此正常入口（Controller）先 prepare 再 startPage；
        //   这里保留兜底是为了服务层单独调用/单测时不至于完全不展开。
        if (effective.getProductTypeIds() == null)
        {
            effective.setProductTypeIds(expandProductTypes(effective.getProductTypeId()));
        }
        if (effective.getDataScopeSql() == null)
        {
            effective.setDataScopeSql(currentScopeSql());
        }
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
     * <p> <b>⚠ F-01（blocker）的根因就在这一句查询上</b>：{@code selectProductTypeList} 读的是
     * <b>全表类型</b>，而 RuoYi 的 {@code startPage()} 会把"下一个 MyBatis 查询"当成要分页的那条
     * （ThreadLocal 里的 Page 被<b>第一条</b>查询消费）。若本方法在 {@code startPage()} 之后被调用
     * （曾经就是这样：Controller 先 startPage → 服务里先展开类型 → 再查明细），两次都错：
     * <ol>
     *   <li> 类型清单被截成前 {@code pageSize} 行 ⇒ 根类型不在前 N 行时子树算不出来 ⇒
     *        "按父类型筛选"查不到子类型物料（F-01 现象，页大小一变结果就翻转）； </li>
     *   <li> 分页被这条辅助查询"吃掉" ⇒ 随后的明细查询反而不再分页。 </li>
     * </ol>
     * 因此调用顺序被固定为：**先 {@code prepareQuery}（无分页上下文）再 {@code startPage}**。
     * 之所以不选 {@code PageHelper.clearPage()}：那会连明细查询自己的分页一起清掉（静默改变页大小）；
     * 也不选"让调用方传大 pageSize"：那只是把 bug 藏起来，库一大就复发。 </p>
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
