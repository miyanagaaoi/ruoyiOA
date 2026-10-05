/**
 * <p> B4 的<b>库存主数据补充层</b>（2.0 `oa-purchase-sales-stock` §2.3/§2.4）。 </p>
 *
 * <p> 四张档案表（商品类型 / 计量单位 / 仓库 / 物料）与档案接口由 B3 交付
 * （{@code CtmsProductMasterController} + {@code CtmsProductMasterServiceImpl} +
 * {@code ProductMasterRules}）；本包只补 B4 阶段才能成立的两件事： </p>
 * <ol>
 *   <li> {@link ErpMasterRefGuards}：仓库/物料的<b>引用守卫</b>
 *        （有结存、有流水、被单据引用时禁止物理删除）； </li>
 *   <li> {@code mapper.ErpMasterRefMapper}：上条所需的引用统计（只读 count）。 </li>
 * </ol>
 *
 * <p> 状态的"停用不可用于新行项"由 T1 的公共层 {@code ErpMasterGuards} 统一守卫，
 * 本包<b>不重复实现</b>第二条判定路径（重复必然漂移）。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.erp.master;
