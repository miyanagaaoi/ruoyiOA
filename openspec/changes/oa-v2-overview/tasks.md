# Tasks

> 本变更集是 2.0 **总纲**：只交付规划件与门禁，不写业务代码（规划边界）。
> 每个任务都在描述里写明**如何验证完成**。

## 1. 需求编号台账与追溯（对齐 `platform/delivery-gate`）

- [x] 1.1 在 `doc/2.0/2.0-PRD-OA升级开发.md` 第 0.2 节维护 `REQ-*` 号段台账，声明 2.0 起用号（`FORM-003`/`DESIGN-012`/`PRINT-011`/`PERM-001`/`DATA-002`/`NFR-001` 及新域 `CTMS`/`PUR`/`SAL`/`STK`）。验证：`grep -n "2.0 起用号" doc/2.0/2.0-PRD-OA升级开发.md` 命中，且文中不存在与 V1 PRD 重复占用的 `REQ-*`（对照 `doc/PRD-合同审批二开.md` 的 41 个编号逐一比对无交集）。
- [x] 1.2 在 PRD 附录 C 维护"用户原文 → 章节 → `REQ-*`"追溯矩阵，逐行覆盖 `doc/功能变更点.txt` 的全部 4 项诉求。验证：`doc/功能变更点.txt` 的每条诉求都能在附录 C 找到对应行；`grep -c "REQ-" 附录 C` ≥ 诉求条数。
- [x] 1.3 在 PRD 第 11 章维护 `AC-45..AC-84` 验收清单，并确认与 V1 的 `AC-01..AC-44` 无编号重叠。验证：把两份文档的 `AC-\d+` 全部抽出做集合交集，交集必须为空。

## 2. 平台能力边界契约（对齐 `platform/module-boundary`）

- [x] 2.1 在 PRD 第 7.3 节维护"参考实现 → 目标侧"对齐表，逐行标明**复用 / 不改 / 禁止移植**。验证：表中每一行都有明确结论列，且 `CTMS_AUTH_ENABLED=0` 后门一行结论为"绝不移植"。`grep -n "CTMS_AUTH_ENABLED" doc/2.0/*.md` 命中且上下文含"禁止/绝不"。
- [x] 2.2 在 PRD 第 7.3 节明确数据范围口径映射（`SELF`→创建人/所属部门、`DEPT`→`dept_id`+`ancestors`、多角色取并集），并写明**不复用 `@DataScope`** 及理由。验证：第 7.3 节含该映射行；`grep -rn "DataScope" doc/2.0/2.0-PRD-OA升级开发.md` 的命中处均给出"不复用"的理由。
- [x] 2.3 在 PRD 第 15.2 节写明 OpenSpec 规划边界（规划件不写代码、实现需显式发起 apply 工作流、delta spec 用 ADDED/MODIFIED/REMOVED、任务须映射 `REQ-*`/`AC-*`）。验证：第 15.2 节五条约束齐备；`openspec validate "oa-v2-overview" --strict` 通过。

## 3. 交付门禁基线（对齐 `platform/delivery-gate`）

- [x] 3.1 核实门禁脚本存在且可执行：`tools/flow-regression.ps1`、`tools/sign-feature-check.ps1`、`tools/authz-check.ps1`、`tools/audit/run-all.js`（含 `--selftest`）。验证：四个路径在仓库中存在；对每个脚本按 `DEV-ENV.md` 的固定命令执行一次并记录退出码（服务未起导致的环境性失败须在记录中标注，不得记为"通过"）。
  - 已核实存在与规模：`tools/flow-regression.ps1`（16127 B）、`tools/sign-feature-check.ps1`（22592 B）、`tools/authz-check.ps1`（11766 B）、`tools/audit/run-all.js`（8101 B）+ `_selftest.js` + 6 个审计脚本 + `baseline.json`。`run-all.js` 支持 `--selftest` / `--no-selftest`（见其文件头第 5、19 行）。
  - 环境执行留待 2.1 交付时（需先跑 `start-env.ps1` + `oa-login.ps1`）；脚本存在性是本任务的判定依据。
- [x] 3.2 在 PRD 第 10 章锁定门禁清单（`REQ-NFR-010`/`REQ-NFR-011`）与批次新增回归脚本清单（`AC-83` 的 3 个新脚本：模板四页签重构回归、流程↔模板绑定回归、多内置打印模板回归）。验证：`grep -n "REQ-NFR-010\|REQ-NFR-011" doc/2.0/2.0-PRD-OA升级开发.md` 均命中；`AC-83` 行点名了 3 个脚本。
  - 已核实：`REQ-NFR-010`、`REQ-NFR-011` 各命中 1 处（第 10 章表格）；`AC-83`（第 11.6 节）逐项点名 4 个既有资产 + 3 个新增脚本，且 3 个新增脚本已分别落到批次变更集的 tasks（B1 §4.6 / §6.5、B2、B1 §7.11）。
- [x] 3.3 在 `DEV-ENV.md` 增加一节"2.0 交付门禁"，列出执行顺序（静态审计 → 越权脚本 → 签名脚本 → 流程回归 → 批次新增脚本）与"失败即阻断交付"的规则。验证：新节存在；按该节命令顺序执行一遍，顺序中的每个命令都能在文档里找到对应的可复制命令行。
  - 已新增 `DEV-ENV.md` 第 7 节「2.0 交付门禁（每次交付前必跑，失败即阻断）」：含前置起环境/登录的可复制命令、5 步执行顺序表（每步给了完整可复制命令行）、3 个批次新增回归脚本的归属表、4 条规格侧硬约束；原第 7 节结论改为第 8 节并补一行门禁状态。

## 4. 批次顺序、前置条件与变更集编排

- [x] 4.1 在 PRD 第 12 章锁定批次划分（B1/B2 与 B3/B4 分线）、关键路径与各批出口验收。验证：第 12 章表格中每批都有"出口验收"列且引用了 `AC-*` 编号。
- [x] 4.2 在 PRD 第 8.3 与第 13.1 节把"`updateTemplate` 换 ID 语义修正"登记为 **B1 的前置条件**（不是可选项），并要求迁移前输出影响行数报告与回滚快照表。验证：`grep -n "REQ-DATA-004" doc/2.0/2.0-PRD-OA升级开发.md` 在第 8.3、第 13.1 节均命中；第 8.3 节含"影响行数报告"与"快照"字样。
- [x] 4.3 创建后续四个批次变更集的规划空壳（`openspec new change`）：`oa-form-flow-tabs`、`oa-print-builtin-templates`、`oa-contract-ledger`、`oa-purchase-sales-stock`。验证：`openspec list` 能列出这四个变更集；每个变更集目录下存在 `.openspec.yaml`。**注意**：只创建空壳，不生成其 `proposal/specs/design/tasks`（那是各自批次的工作）。
- [x] 4.4 在每个批次变更集的 proposal 首部写明它覆盖的 `REQ-*` 段、验收编号与依赖（B1 覆盖 `REQ-FORM-003..011`/`REQ-DESIGN-012..017`/`REQ-PERM-001..005`；B2 覆盖 `REQ-PRINT-011..018` 且依赖 B1 的共享增列；B3 覆盖 `REQ-CTMS-001..008`/`REQ-DATA-002..005`；B4 覆盖 `REQ-PUR-001`/`REQ-SAL-001`/`REQ-STK-001..006` 且依赖 B3 与 B2）。验证：五个变更集的 `proposal.md` 首部都有"覆盖需求/验收覆盖/依赖"三行；`openspec validate "<变更名>" --strict` 对每个变更集均通过。

## 5. 集成检查（只做跨批次确认，不补早期组的测试与文档）

- [x] 5.1 确认总纲的三份文档内部一致：PRD 引用的路径与行号在 `RuoYi现状勘察.md` / `参考仓库-CTMS-移植清单.md` 中可达；PRD 的 `REQ-*`/`AC-*` 引用无悬空（每个被引用的编号都有定义处）。验证：把 PRD 中所有 `REQ-*`、`AC-*` 引用抽出，逐个确认存在定义；对 PRD 里引用的 `doc/2.0/*.md` 章节号逐个确认存在。
- [x] 5.2 运行 `openspec validate "oa-v2-overview" --strict` 并确认通过；确认 `openspec/specs/` 尚未被写入（本次是 change 内的 delta，主规格在 archive 时才落）。验证：命令输出 `is valid`；`openspec/specs/` 下仍只有 `.gitkeep`。
- [x] 5.3 生成一份"提交评审"清单：把 PRD 第 13.2 节 B 组的 10 个待确认问题（Q-B1..Q-B10）单独抽出，交给用户逐条答复。验证：清单条目数与第 13.2 节 B 组行数一致（10 条），且每条都附了本 PRD 的建议口径。
