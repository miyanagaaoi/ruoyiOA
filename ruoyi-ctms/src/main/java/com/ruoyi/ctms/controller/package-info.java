/**
 * <p> 合同台账域的<b>控制器</b>。 </p>
 *
 * <p> 统一约定： </p>
 * <ul>
 *   <li> 继承 {@code com.ruoyi.common.core.controller.BaseController}（提供
 *        {@code startPage()} / {@code getDataTable()} / {@code success()} / {@code toAjax()}）； </li>
 *   <li> 每个接口都加 {@code @PreAuthorize("@ss.hasPermi('ctms:<资源>:<动作>')")}，
 *        <b>权限点真源是 {@code sys_menu}</b>（{@code sql/二开-合同台账-菜单.sql} 初始化），
 *        不允许在代码里写常量权限表 —— 权限点与菜单必须一一对应（任务 9.1 有集合核对）； </li>
 *   <li> 写操作加 {@code @Log(title = "...", businessType = ...)}（操作日志走 OA 既有的
 *        {@code SysOperLog}；字段级前后值走 {@code t_ctms_change_log}，两者职责不同，见 D-10）； </li>
 *   <li> <b>数据范围的 403 由服务层抛出</b>，控制器不自己拼可见性条件 ——
 *        范围外按标识直查也必须 403（规格场景），所以判定必须在服务端且集中。 </li>
 * </ul>
 *
 * @author 二开
 */
package com.ruoyi.ctms.controller;
