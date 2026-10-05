/**
 * <p> <b>合同台账与往来单位档案</b>（2.0 批次 B3，变更集 {@code oa-contract-ledger}） </p>
 *
 * <p> 本模块是参考仓库 CTMS（{@code .cache/contract-ref}，Python/FastAPI）中
 * 「合同域 + 主数据域」在 RuoYi-OA 侧的落点，采用 Java + Vue2 <b>重写</b>（PRD §7.1 方案 B），
 * 不是机械转译。B4（{@code oa-purchase-sales-stock}）会在<b>同一个模块</b>里追加单据域与库存域。 </p>
 *
 * <h3>分层与命名</h3>
 * <pre>
 *   com.ruoyi.ctms.domain      实体 / DTO / QO / VO（MyBatis 别名的扫描范围是 com.ruoyi.**.domain）
 *   com.ruoyi.ctms.mapper      Mapper 接口（@MapperScan("com.ruoyi.**.mapper") 全局扫描）
 *   com.ruoyi.ctms.service     服务接口
 *   com.ruoyi.ctms.service.impl 服务实现
 *   com.ruoyi.ctms.controller  控制器（继承 BaseController，权限点走 @PreAuthorize）
 *   resources/mapper/ctms/     Mapper XML（命名必须以 Mapper.xml 结尾，见该目录 README）
 * </pre>
 *
 * <h3>三条硬约束（违反即交付不通过）</h3>
 * <ol>
 *   <li> <b>复用平台能力，禁止第二套</b>：认证/组织/权限/字典/编号/附件/日志/打印
 *        一律用 OA 既有实现（{@code ruoyi-system} / {@code sys_menu} / {@code sys_dict_*} /
 *        {@code ruoyi-serial} / {@code ruoyi-file} / {@code @Log} / {@code ruoyi-workflow/print}）。
 *        参考仓库的账号表、组织树、JWT，以及那个「<b>关掉认证开关即可访问</b>」的后门常量
 *        <b>绝不移植</b>（判据见平台能力 {@code platform/module-boundary}，静态审计见 9.4；
 *        本条刻意不写出该常量的字面名，以免 9.4 的「零命中」判据被注释自己打红）。 </li>
 *   <li> <b>金额与数量禁用 {@code double}/{@code float}</b>：一律 {@code BigDecimal}，
 *        金额 scale=2、数量 scale=3、单价 scale=4，且「先舍入到分再汇总」（C-1）。 </li>
 *   <li> <b>数据范围服务端强制</b>：{@code ALL}/{@code DEPT}（含下级为默认）/{@code SELF} 三档、
 *        多角色取<b>并集</b>；不复用 {@code @DataScope}（它只覆盖系统模块 5 处，语义也不等价），
 *        按 {@code DocViewGuard} 的「显式命中即放行」范式自建，且列表/详情/编辑/删除/恢复/导出
 *        全部走同一处判定（design.md D-4）。 </li>
 * </ol>
 *
 * <h3>文档索引</h3>
 * <ul>
 *   <li> 规格：{@code openspec/changes/oa-contract-ledger/specs/ctms/&lt;能力&gt;/spec.md}
 *        （四个能力：contract-ledger / contract-commercials / business-partners / contract-migration） </li>
 *   <li> 技术决策：{@code openspec/changes/oa-contract-ledger/design.md}（D-1 ~ D-10） </li>
 *   <li> 任务与验证方式：{@code openspec/changes/oa-contract-ledger/tasks.md} </li>
 *   <li> 移植清单（参考侧字段与业务规则的唯一对照依据）：
 *        {@code doc/2.0/参考仓库-CTMS-移植清单.md} </li>
 *   <li> 环境与命令：{@code DEV-ENV.md} </li>
 * </ul>
 *
 * @author 二开
 */
package com.ruoyi.ctms;
