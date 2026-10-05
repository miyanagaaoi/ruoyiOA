/**
 * <p> 合同台账域的<b>服务实现</b>。 </p>
 *
 * <p> 三条实现纪律： </p>
 * <ol>
 *   <li> <b>金额只用 {@code BigDecimal}</b>：工厂 scale 固定（金额 2 / 单价 4 / 数量 3），
 *        行总价 = 数量×单价 {@code setScale(2, HALF_UP)} <b>之后</b>才求和（C-1，判别用例
 *        3 × 1.665 → 5.01 而非 5.00）； </li>
 *   <li> <b>质保到期</b>用两个纯函数（{@code addMonths} + {@code computeWarrantyEnd}）实现，
 *        新增与编辑两条路径都<b>服务端计算</b>，前端传值不参与判定（D-7 的文档口径收敛）；
 *        做成表驱动单测，覆盖 6 个规格场景（含 2026-03-15 + 24 → 2028-02-29）； </li>
 *   <li> <b>软删除恢复</b>用整数日差 {@code days(now) - days(deletedAt) <= 30}
 *        （第 30 天可恢复、第 31 天不可）——<b>不要</b>写成 {@code deletedAt + 30 天} 的瞬时比较，
 *        那会把边界推到第 30 天的同一时刻。 </li>
 * </ol>
 *
 * @author 二开
 */
package com.ruoyi.ctms.service.impl;
