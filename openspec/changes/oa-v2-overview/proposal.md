# Proposal — OA 2.0 总纲（跨批次约束与交付门禁）

## Why

`doc/功能变更点.txt` 提出四项诉求（表单四页签 + 关联审批控件、打印模板、合同管理与出入库），
经源码核对与参考仓库评审后，2.0 的实质是**三条不同性质的工作**：

1. **改结构**：新增模板的入口散在 5 个纵向分段 + 另一个菜单（简化流程设计器），流程标识靠人工抄写（`UI/views/workflow/template/add.vue:1-45`、`basic-info.vue:22-31`）；
2. **补缺口**：`submitScope`（谁可以提交）在全仓零命中，现状是"任何登录用户可发起任何启用模板"；内置打印模板只有全局唯一一套且被前端强制关闭签批栏；
3. **搬系统**：合同台账 / 采购 / 销售 / 库存目前在参考仓库 `miyanagaaoi/contract`（Python FastAPI + Vue3，39 表 / ~231 端点 / 后端 9,385 行 / 前端 35 视图），与 OA 零集成；用户已决定**移植进 RuoYi**（Java + Vue2）。

三者工作量相差两个量级，且第 3 项会在"账号、组织、角色、字典、编号、附件、日志、打印"上与 OA 大面积重叠。
如果不先把**跨批次约束**和**交付门禁**钉死，各批次会各自长出第二套账号/组织/权限体系，或把参考仓库的已知缺陷（应用层强制、无行锁、金额"先汇总再舍入"）一并搬进来。

本变更集是 2.0 的**总纲**：只声明跨批次必须共同遵守的行为，不实现任何批次功能。

## What Changes

- **新增跨批次模块边界规格**：B3/B4 引入的全部业务模块必须复用 OA 既有平台能力（统一登录、`sys_dept` 组织、角色菜单权限、`sys_dict_*` 字典、`ruoyi-serial` 编号、`ruoyi-file` 附件、`@Log` 操作日志、`ruoyi-workflow/print` 打印）；系统中不得出现第二套账号表、第二套组织树、自研 JWT、代码常量权限表，也不得移植参考仓库的免认证调试后门。
- **新增交付门禁规格**：列明每次交付前必须全绿既有验证资产（`tools/flow-regression.ps1`、`tools/sign-feature-check.ps1`、`tools/authz-check.ps1`、`tools/audit/run-all.js`），以及 V1 的 44 条验收项继续作为回归门禁。
- **新增需求编号台账与追溯约定**：2.0 的 `REQ-*` 按域续接 V1 号段（新增 `CTMS`/`PUR`/`SAL`/`STK` 域），`AC-*` 从 `AC-45` 续编；每批次的 OpenSpec 任务必须能映射到 PRD 的 `REQ-*` 与 `AC-*`。
- **不移除、不修改任何既有能力**；本变更集**不写代码**（规划边界）。

> 批次功能本身不在本变更集内，各自单独立变更：
> `oa-form-flow-tabs`（模板四页签 / 设计器内嵌 / 自动绑定 / 可发起范围 / 关联审批）、
> `oa-print-builtin-templates`（按单据类型选内置打印模板）、
> `oa-contract-ledger`（合同台账与往来单位）、
> `oa-purchase-sales-stock`（采购 / 销售 / 库存）。

## Capabilities

### New Capabilities

- `platform/module-boundary`: 2.0 引入的业务模块与 OA 既有平台能力之间的边界契约 —— 认证、组织、权限、字典、编号、附件、日志、打印必须单一真源；明令禁止并行体系与免认证后门。
- `platform/delivery-gate`: 每次交付的验收门禁 —— 既有回归脚本必须全绿、V1 验收项不回归、静态审计通过；新增能力的回归脚本必须与能力同批交付。

### Modified Capabilities

无（`openspec/specs/` 当前为空，尚无既有能力被修改）。

## Impact

- **规格**：新增 `openspec/specs/platform/module-boundary/spec.md`、`openspec/specs/platform/delivery-gate/spec.md`；本变更集是后续四个批次变更集的约束来源。
- **文档**：`doc/2.0/2.0-PRD-OA升级开发.md`（2.0 主 PRD，本次新增）、`doc/2.0/RuoYi现状勘察.md`、`doc/2.0/参考仓库-CTMS-移植清单.md`。
- **代码**：本变更集**不改代码**。后续批次会触及后端 `ruoyi-template` / `ruoyi-workflow`（print/sign/simple）/ 新建 `ruoyi-ctms` 模块，前端 `views/workflow/template/**`、`views/workflow/simple-flow/**`、`views/workflow/print*/**`、新建 `views/ctms/**`，以及 `sql/` 增量脚本。
- **数据库**：后续批次会新增 4 张模板相关表、`t_template` 增 13 列、参考仓库 39 张表的 MySQL DDL；以及一项**语义修正**（`updateTemplate` 由"换新 ID"改为原地 UPDATE）与配套存量归并迁移。
- **依赖**：不新增第三方依赖；**建议移除**已注册但全仓零使用的 `vform` UMD（1.36 MB + 90 KB CSS）。
- **风险**：`updateTemplate` 的换 ID 语义若不先修，后续新增的 4 张表会与原有两个 `template_id` 外键表一起在模板编辑后失联（详见 PRD 第 8.3 节 R-1）。
