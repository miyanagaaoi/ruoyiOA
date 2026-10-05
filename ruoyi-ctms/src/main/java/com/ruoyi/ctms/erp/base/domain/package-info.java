/**
 * <p> 单据公共字段的实体基类（B4）：{@code ErpDocHeader}（表头 28 个公共字段）与
 * {@code ErpDocItem}（行项 22 个公共字段 + 快照列）。 </p>
 *
 * <p> 8 类单据的实体继承它们，只声明各自的专有字段（design D1：公共行为一份实现、
 * 表结构仍每表独立）。数值字段一律 {@code BigDecimal}（金额 2 位 / 单价 4 位 / 数量 3 位）。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.erp.base.domain;
