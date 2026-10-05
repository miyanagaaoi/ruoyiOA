/**
 * <p> 公共层的 Spring 实现：{@code ErpDocObjectAccessServiceImpl}（单据对象访问校验，
 * 附件接入点 ②）与 {@code ErpMasterLookupImpl}（停用物料/仓库/单位的只读查询）。 </p>
 *
 * <p> 两者都只做"读 + 判定"，不持有事务；过账/状态流转的事务边界在各自的单据服务里。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.erp.base.service.impl;
