/**
 * <p> 合同台账域的<b>服务接口</b>。实现放 {@code service/impl/}。 </p>
 *
 * <p> 本层是"业务规则的唯一落点"：反直觉口径（先舍入再汇总、质保到期算法、软删除 30 天整数日差、
 * 编号按年重置）都必须收在服务层，<b>不能</b>散到 Controller 或前端 ——
 * 规格里的每个场景都对应一个可执行单测，测的就是这一层。 </p>
 *
 * <p> 事务边界：写操作（新增/编辑/删除/恢复/认领/批量回填）用
 * {@code @Transactional(rollbackFor = Exception.class)}；变更历史（{@code t_ctms_change_log}）
 * 必须与业务写在同一事务内，避免"改了值但没留痕"。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.service;
