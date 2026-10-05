/**
 * <p> B4 进销存的<b>单据公共层</b>（2.0 `oa-purchase-sales-stock` 任务 1.1~1.4）。 </p>
 *
 * <p> 内容：状态常量与逐动作白名单状态机（{@code ErpDocStatus}/{@code ErpDocAction}/
 * {@code ErpDocStateMachine}）、单据类型枚举（{@code ErpDocType}）、
 * 金额与数量的唯一精度实现点（{@code ErpAmounts}）、行项主数据守卫与快照
 * （{@code ErpMasterGuards}）、四档数据范围（{@code ErpDocScope}）、
 * 系统参数（{@code ErpStockParams}）；实体公共字段在 {@code domain} 子包，
 * 附件接入点与主数据查询实现在 {@code service}/{@code mapper} 子包。 </p>
 *
 * <p> <b>设计约束</b>：本包<b>不依赖 Spring 容器</b>也能被单测直接调用
 * （纯规则类一律 static、无状态）；需要查库的部分以回调/接口注入，
 * 生产实现放在 {@code service.impl}。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.erp.base;
