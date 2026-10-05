/**
 * <p> 合同台账域的<b>实体与传输对象</b>。 </p>
 *
 * <p> 放置约定： </p>
 * <ul>
 *   <li> 实体直接放本包（如 {@code CtmsContract}），与表 {@code t_ctms_contract} 一一对应； </li>
 *   <li> 入参对象放 {@code domain/qo/}，出参对象放 {@code domain/vo/}（与既有 {@code ruoyi-kbs} 一致）； </li>
 *   <li> 复杂入参（含行项/标签/质保区）用 DTO 承载，避免实体上挂一堆非持久化字段。 </li>
 * </ul>
 *
 * <p> ⚠ 类型口径（移植时最容易出错的地方）： </p>
 * <ul>
 *   <li> 金额/单价/数量一律 {@code BigDecimal}（金额 scale=2、单价 scale=4、数量 scale=3），
 *        <b>不得</b>出现 {@code double}/{@code float}； </li>
 *   <li> 主键与各类外键标识 {@code String}（{@code varchar(64)}，应用侧 UUID）； </li>
 *   <li> 标志位 {@code String}（{@code char(1)}，'0' 否 / '1' 是）——与 RuoYi 惯例一致； </li>
 *   <li> 日期用 {@code java.util.Date}（与既有模块一致），格式化统一 {@code yyyy-MM-dd}。 </li>
 * </ul>
 *
 * <p> {@code typeAliasesPackage: com.ruoyi.**.domain} 会为本包生成 MyBatis 别名，
 * 所以 Mapper XML 里可以直接写类名（如 {@code resultType="CtmsContract"}）。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.domain;
