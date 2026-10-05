package com.ruoyi.ctms.erp.ledger.service;

import java.util.List;

import com.ruoyi.ctms.erp.ledger.domain.ErpStockBalance;
import com.ruoyi.ctms.erp.ledger.domain.ErpStockLedgerRow;

/**
 * <p> <b>库存账只读查询服务</b>（B4 任务 7.1 / 7.2 / 7.3；AC-73、REQ-STK-003）。 </p>
 *
 * <p> 三件事在本接口里各有唯一入口： </p>
 * <ul>
 *   <li> {@link #selectBalanceList(ErpStockBalance)} —— 库存明细（含「商品类型名称-物料名称」、
 *        货品总额度、低于安全库存、数量区间、商品类型子树、数据范围）； </li>
 *   <li> {@link #selectLedgerRows(ErpStockLedgerRow)} —— 库存流水列表 / 按（物料, 仓库）与
 *        时间区间下钻；列表与下钻<b>同一处筛选、同一处数据范围</b>； </li>
 *   <li> {@link #selectBalanceByKey(String, String)} / {@link #selectLedgerRow(String)} ——
 *        单行详情，先过数据范围再给结果（范围外 403、不存在 404，两者可区分）。 </li>
 * </ul>
 *
 * <p> <b>只读</b>：本接口没有任何写方法；结存的写入口是 T3 的过账/红冲，
 * 运维修复只走 {@code IErpStockRecalcService}（它复用 T3 的 {@code updateStockQty}）。 </p>
 *
 * @author 二开
 */
public interface IErpStockLedgerQueryService
{
    /**
     * 库存明细列表。
     *
     * @param query 查询条件（商品类型传根节点即可，服务层展开子树；数据范围由服务层装配）
     * @return 明细行集合（含额度与派生字段）
     */
    /**
     * <p> 列表/导出的<b>查询前置</b>：展开商品类型子树 + 装配数据范围片段。 </p>
     *
     * <p> <b>必须在 {@code startPage()} 之前调用</b>（F-01）：展开要读全表"类型清单"，
     * 而 {@code startPage()} 会把 ThreadLocal 里的 Page 交给**下一条** MyBatis 查询 ——
     * 若这条辅助查询落在分页上下文里，类型清单会被截成前 {@code pageSize} 行，
     * 根类型不在其中时子树就算不出来（"按父类型筛选"漏子类型物料，且随页大小翻转）。 </p>
     *
     * <p> 幂等：重复调用只是重算同样的值；返回的是同一个入参对象（便于 Controller 链式使用）。 </p>
     *
     * @param query 查询条件（可空）
     * @return 装配后的查询对象
     */
    ErpStockBalance prepareQuery(ErpStockBalance query);

    List<ErpStockBalance> selectBalanceList(ErpStockBalance query);

    /**
     * 单行库存明细（按（物料, 仓库））。
     *
     * @param productId   物料ID
     * @param warehouseId 仓库ID
     * @return 明细行
     * @throws com.ruoyi.common.exception.ServiceException 不存在（404）或范围外（403）
     */
    ErpStockBalance selectBalanceByKey(String productId, String warehouseId);

    /**
     * 库存流水列表 / 下钻（时间倒序）。
     *
     * @param query 查询条件（带 productId + warehouseId 即为下钻）
     * @return 流水行集合
     */
    List<ErpStockLedgerRow> selectLedgerRows(ErpStockLedgerRow query);

    /**
     * 单行流水详情。
     *
     * @param id 流水ID
     * @return 流水行
     * @throws com.ruoyi.common.exception.ServiceException 不存在（404）或范围外（403）
     */
    ErpStockLedgerRow selectLedgerRow(String id);

    /**
     * 结存行数（运维自检与一致性校验的"被检查行数"）。
     *
     * @return 行数
     */
    int countBalance();
}
