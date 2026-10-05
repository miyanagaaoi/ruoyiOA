package com.ruoyi.ctms.erp.ledger.service;

import com.ruoyi.ctms.erp.ledger.domain.ErpStockRecalcResult;

/**
 * <p> <b>库存结存一致性校验 / 修复服务</b>（B4 任务 7.4；{@code REQ-STK-006}、AC-76）。 </p>
 *
 * <p> 不变式：对每个（物料, 仓库），{@code t_ctms_stock.qty == Σ t_ctms_stock_ledger.qty_change}。 </p>
 *
 * <p> <b>为什么修复不是"危险动作"由本接口自我约束</b>： </p>
 * <ul>
 *   <li> {@code repair=false} 时<b>只读</b>，不改任何行（默认值，菜单按钮走的就是只读校验）； </li>
 *   <li> {@code repair=true} 时才把不一致行的结存改写成"流水累计"，
 *        且逐行写绝对值（复用 T3 的 {@code updateStockQty}），不新增第二个写入口； </li>
 *   <li> 一致的数据行<b>永不被改写</b>（幂等：重复调用第二次 repairedCount = 0）。 </li>
 * </ul>
 *
 * <p> 修复的风险提示与使用场景见 {@code notes/07-ledger.md}（"流水是账、结存是缓存"的口径）。 </p>
 *
 * @author 二开
 */
public interface IErpStockRecalcService
{
    /**
     * 校验（可选修复）全部结存行与流水累计的一致性。
     *
     * @param repair 是否把不一致行改写成流水累计
     * @return 校验结果（被检查行数 / 是否一致 / 不一致条数与明细 / 实际修复行数）
     */
    ErpStockRecalcResult recalc(boolean repair);
}
