# Proposal

> **覆盖需求**：`REQ-CTMS-001..008`、`REQ-DATA-002`、`REQ-DATA-003`、`REQ-DATA-005`、`REQ-NFR-003`（数据范围口径）
> **验收覆盖**：`AC-68..AC-71`、`AC-77`、`AC-79`、`AC-82`
> **依赖**：`oa-v2-overview`（`platform/module-boundary`、`platform/delivery-gate`）；被 `oa-purchase-sales-stock` 依赖（其单据的往来单位引用与"关联合同"依赖本批次的档案与台账）
> **批次**：B3，随 2.2 交付

## Why

OA 2.0 的 B3 批次要把已通过评审、已在 UAT 运行的参考仓库 CTMS（`.cache/contract-ref`）中的**合同台账与往来单位**能力移植进 RuoYi-OA：合同目前只有审批与打印，没有任何合同台账主体、行项、质保跟踪与甲乙方档案；而参考实现的这层业务规则（质保到期算法、金额先舍入再汇总、框架合同一对多、软删除 30 天窗口、合同编号按年重置）在 OA 侧完全没有对应物，属于必须逐条复刻的业务资产。

移植路径已在 `doc/2.0/2.0-PRD-OA升级开发.md` §7.1 完成评审并采纳方案 B（Java + Vue2 重写进 RuoYi），§12 已确认分批：B3 = `oa-contract-ledger`（本变更集），B4 = `oa-purchase-sales-stock`。本变更集只做规划件，不改代码。

## What Changes

- **新增合同台账主体**：合同的新增/编辑/多维筛选列表/详情/软删除（30 天内可恢复，第 31 天不可）/标签（含「框架合同」与类型名自动标签）/变更历史（人、时间、字段、旧值、新值、来源）。
- **新增合同商务要素**：行项明细（序号/类型/名称/规格/数量/单价/总价/备注/物料档案引用）、金额与质保金（含质保到期日算法与金额↔比例互换、付款比例）、合同编号规则（类型码 + 主体码 + 按年重置 + 停用占号不复用）。
- **新增往来单位档案**：客户与供应商档案，合同甲乙方由纯文本改为**档案引用 + 保留文本兜底**（历史数据不阻塞）。
- **新增历史迁移工具**：扫描未绑定档案的历史合同文本 → 生成「待认领草案」→ 人工认领/绑定，并回写匹配的历史合同（含变更历史留痕）。
- **新增数据库约束回补**：参考实现在 SQLite 下靠「应用层强制、DB 可空」的 17 类字段，在 MySQL DDL 中改回 `NOT NULL` + 真实 FK；迁移脚本**先跑数据体检输出违例行清单，再执行 DDL**。
- **数据范围自建**：`SELF` / `DEPT`（含下级为默认）/ `ALL` 三档在合同台账上生效，多角色取**并集**；**不复用** RuoYi 的 `@DataScope`（它只覆盖系统模块 5 处），参照既有 `DocViewGuard` + 四路 union 范式自建。
- **模块边界**：复用 OA 的认证、组织（`sys_dept`）、权限（`sys_menu` + `@PreAuthorize`）、字典（`sys_dict_*`）、编号（`ruoyi-serial`）、附件（`ruoyi-file`）、操作日志（`@Log`）、打印（`ruoyi-workflow/print`）；**禁止**第二套体系与任何免认证后门。
- **依赖声明（重要）**：本变更集依赖 `oa-v2-overview` 的 `platform/module-boundary`（复用 OA 认证/组织/权限/字典/编号/附件/日志/打印，禁止第二套体系、禁止免认证后门）与 `platform/delivery-gate`（既有回归资产全绿、V1 验收不回归、新增能力同批交付回归脚本、移植缺陷不得一并搬入）；若总纲尚未归档，本变更集不引用 `MODIFIED`，以自身新能力承载要求，待总纲归档后再收敛。
- 无 **BREAKING** 变更：不修改任何既有表结构、不改存量权限点名称、不改 V1 打印与流程行为。

## Capabilities

### New Capabilities

- `ctms/contract-ledger`: 合同台账主体的全生命周期（新增/编辑/列表/详情/软删除恢复/标签/框架合同/变更历史）。
- `ctms/contract-commercials`: 合同商务要素（行项明细、金额与质保金、编号规则）。
- `ctms/business-partners`: 客户与供应商档案，以及合同甲乙方的档案化引用。
- `ctms/contract-migration`: 参考实现的历史合同档案迁移（生成待认领草案）与数据库约束回补。

### Modified Capabilities

（无。理由见 What Changes 的**依赖声明**：`oa-v2-overview` 的 `platform/module-boundary` 与 `platform/delivery-gate` 若尚未归档，主规格 `openspec/specs/platform/**` 可能不存在；此时引用 `MODIFIED` 会让 `openspec validate` 失败。本变更集因此以四个新能力承载全部要求，不声明 `MODIFIED`。）

## Impact

**受影响的后端**
- 新增 Maven 模块 `ruoyi-ctms`（B3 建壳，B4 复用同一模块装载单据/库存域），在根 `pom.xml` 的 `<modules>` 与 `ruoyi-admin/pom.xml` 登记。
- 域：`domain/` + `mapper/` + `service/` + `controller/`，命名空间 `com.ruoyi.ctms.*`；Mapper XML 放 `ruoyi-ctms/src/main/resources/mapper/ctms/`。
- 复用（不新建）：`ruoyi-framework` 认证链、`ruoyi-common` 的 `@Log`/`ExcelUtil`、`ruoyi-serial` 的 `ICodeGenService`、`ruoyi-file` 的上传能力、`ruoyi-workflow/print` 的打印能力。

**受影响的前端**
- 新增 `ruoyi-vue-oa-ui-master/src/views/ctms/**`（合同台账列表/详情/编辑、标签、客户档案、供应商档案、迁移认领页）与 `src/api/ctms/**`。
- 复用 `src/layout`、`src/store/modules/permission.js` 动态路由、`v-hasPermi` 指令；`element-ui` 2.15.14 既有组件集合足够，无需新增组件库。

**受影响的数据与 SQL**
- 新增增量脚本 `ruoyi-vue-oa-master/sql/二开-合同台账.sql`（B3 域表 DDL + 约束 + 索引）与 `二开-合同台账-菜单.sql`（`sys_menu` 初始化，幂等：先 `DELETE` 再 `INSERT`）。
- 新增 `sql/初始化-全部.sql`（REQ-DATA-005 的初始化编排，按序 `source` 全部增量文件；`table.sql` / `data.sql` 保持上游原样基线不修改）。
- 新增数据体检脚本（输出违例行清单）与回补 DDL，供 F-3（17 类字段改回 `NOT NULL` + FK）使用。

**受影响的能力边界与风险**
- 数据范围是本批次最高风险项（口径差异导致越权或「该看的看不到」），必须在 S0 冻结口径并实测。
- 合同编号依赖 `ruoyi-serial` 能否表达「类型码 + 主体码 + 按年重置 + 停用占号不复用」，S0 技术验证的结论决定是「配置表达」还是「扩展序列服务」。
- 迁移脚本会改动存量合同数据（回填 `customer_id`/`supplier_id`），必须先输出影响行数报告并提供回滚策略。

**不受影响**
- 不改 `t_template` / 打印模板 / 流程引擎 / 既有 `sys_*` 表结构与数据；不改存量权限点名；不引入 Flowable 多级审批（口径 Q-B7：保留单级状态机）。
