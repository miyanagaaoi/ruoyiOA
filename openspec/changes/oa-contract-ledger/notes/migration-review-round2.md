# 第 7 组（变更历史与迁移工具）复审 —— t27 / round 2（针对 t26 的 F1/F2/F3 修复）

> 复核人：reviewer（独立复核，未修改任何实现代码）
> 复核时间：2026-10-05 15:56~16:12（PS 5.1 + MySQL 8.0.40 + 8080 真实端点）
> 被审对象：t26（round-2 修复，覆盖 `notes/migration-review.md`(t16) 的 F1/F2/F3）＋ 规格 6 个 Requirement 与第 7 组 6 项任务的证据是否仍然成立
> 环境：后端 jar mtime **2026-10-05 15:52:04** / size 184237301 / 8080 pid 21180（与 t26 记录一致）；改动面 mtime：`CtmsContractMapper.xml` 15:39、`CtmsMigrationServiceImpl` 15:39、`MigrationRules` 15:42、两个测试类 15:41/15:43、`notes/migration-notes.md` 15:52、`tools/ctms-migration-check.ps1` 15:53

## 0. 结论

**verdict = pass**（round 1 的 F1/F2/F3 已全部修复，且由我在真库上独立复现；规格 6 个 Requirement 与 6 项任务的证据仍成立）
另记 **1 条 low（R2-F1，可选）**：`notes/migration-notes.md` §1.7 的「已知边界」描述可再补一句"剩余变体会以另一条**外观相同**的草案出现、需再扫描+再认领一轮"，并附我实测的 3 轮收敛数字，免得运维看到两条同名草案时误判。

| round 1 finding | 处置 | 我的独立复核 |
| --- | --- | --- |
| F1（medium）扫描/认领口径不一致 → 实绑 0 + claimed↔pending 永久往复 | **已修**：Java `MigrationRules.trimSpaces` 只去 0x20；候选 SQL 改 `trim(本方向文本) = trim(#{rawName})`；另加 `log.warn` 诊断 | 真库三组实测：**空格类一轮实绑 4/4**、Tab/全角/NBSP 混布**两轮内收敛、第三轮 touched=0**；扫描只读指纹前后一致（见 §2） |
| F2（low）`migration-notes.md` 行数记录不实 | **已修**：正文写 **451 行 / 38314 字节**（`-Encoding UTF8` 量法），§8.2 落纪律；`tasks.md:529` 也把原 282 更正为 370（历史状态） | 我重量：`ReadAllLines`=**451**、`Get-Content -Encoding UTF8`=**451**、默认读法=338（GBK 伪数）、字节=**38314** |
| F3（low）真库语义缺自动化资产 | **已沉淀**：`tools/ctms-migration-check.ps1`（551 行、S1~S13、夹具用完即撤） | 我独立跑：**52 通过 / 0 失败 / exit 0**，收尾夹具全删（见 §4） |

## 1. 复跑（acceptance 第 2 条）

| 命令 | 结果 |
| --- | --- |
| `mvn -B -pl ruoyi-ctms test` | **Tests run: 212, Failures: 0, Errors: 0**（`CtmsMigrationServiceImplTest` **27** = 原 23 + 新 4；`MigrationRulesTest` 6；合计 33） |
| `node tools/audit/run-all.js` | **10 审计 / 失败 0 / 未自证 0**，exit 0 |
| `tools/ctms-migration-prep.ps1 -WithViolations` | **断言 29/0**，**exit 3** |
| `tools/ctms-migration-prep.ps1`（默认 DROP） | **断言 32/0**，exit 0 |
| `tools/ctms-migration-check.ps1`（t26 新增） | **52 通过 / 0 失败**，exit 0 |

五条与 t26/captain 的记录逐字一致。

## 2. F1 —— 口径统一（我按"源码 + 真库三组 + 只读指纹"独立复核）

### 2.1 源码层（读真源，不看自述）

- `MigrationRules.trimSpaces:263-279`：只去 `charAt == ' '`（0x20），**Tab/CR/LF/全角 U+3000/NBSP 一律保留**；`normalizeRawName:245-251` = 先 `trimSpaces`、再用 `ContractRules.trimToNull` 判"是否全空白"（全空白 → null），返回值保留非空格字符。
- `CtmsContractMapper.xml:347-358`：候选查询两侧都写 `trim(...)` —— `trim(c.party_a) = trim(#{rawName})` / `trim(c.party_b) = trim(#{rawName})`；XML 里的 `trim(` 共 **8** 处（**SQL 侧 4 处** + 文档注释 4 处），与 t26 记录的"4"（指 SQL 侧）一致。
- `CtmsMigrationServiceImpl:287-295`：`targets.isEmpty() && draft.contractCount > 0` → `log.warn("迁移认领未绑定任何合同：…请核对候选比较口径")` —— 即 round 1 要求的"实绑 0 不得静默"落点（Logger 声明在 `:119`）。
- 新用例 4 条：`首尾空格变体聚合成一条草案且认领后四份都真被绑定`、`Tab包裹的文本两侧同口径且认领后真被绑定`、`全角空格包裹的文本两侧同口径且认领后真被绑定`、`候选为空时实绑计数如实落零`；桩的 `sameText` 也改成 `trimSpaces(...)+equalsIgnoreCase`（`CtmsMigrationServiceImplTest:1372-1391`），即**桩与 XML 同口径**（不会用宽松比较掩盖缺陷）。

### 2.2 真库证据 A：空格类（原 F1 的正例）

| 夹具 | 扫描结果 | 认领结果 |
| --- | --- | --- |
| `T27S1='T27SP阀门'`、`T27S2=' T27SP阀门 '`（hex 头 `2054`） | **1 条草案**、`count=2`、`raw_name='T27SP阀门'` | `code=200` **实绑=2**、状态 `claimed`、两份合同 `supplier_id` 同一值 |

补充：工具 `ctms-migration-check.ps1` 的 S6 用"四种空格写法"复跑同样结论（**实绑 4/4**、再扫描稳定 `claimed`）——我独立执行得到 52/0。

### 2.3 真库证据 B：非空格空白（t26 记录的"已知边界"，我实测会不会往复）

夹具 4 组各两份（`T27T/F/N` = Tab / U+3000 / NBSP 变体），逐轮轨迹（我实测）：

```
round 1: scan touched=4 pendingDrafts=4 unboundContracts=8
   认领 raw_name=[\tT27TAB阀门] 草案计数=2 → 实绑=1 claimed
   认领 raw_name=[　T27FW阀门] 草案计数=2 → 实绑=1 claimed
   认领 raw_name=[ T27NB阀门] 草案计数=2 → 实绑=1 claimed
   认领 raw_name=[T27SP阀门]  草案计数=2 → 实绑=2 claimed   ← 空格类一轮绑全
round 2: scan touched=3 pendingDrafts=3 unboundContracts=3
   认领 [T27FW阀门] 计数=1 → 实绑=1 claimed
   认领 [T27NB阀门] 计数=1 → 实绑=1 claimed
   认领 [T27TAB阀门] 计数=1 → 实绑=1 claimed   ← 剩余变体成为“第二条草案”（名称仅差不可见空白）
round 3: scan touched=0 pendingDrafts=0 unboundContracts=0   ← 收敛
```

结论：**8 份合同全部被绑定、所有草案终态 `claimed`、收敛后再扫描 `touched=0`**；逐行核对只有 `supplier_id` 被写入，`party_a/party_b`（含空白原文）、`del_flag`、`customer_id` 全部未变。
⇒ t26 的"已知边界"描述**成立**：非空格空白变体只影响"一轮能否绑全"，**不产生实绑 0、不永久往复**（我 round 1 担心的 claimed↔pending 死循环在修后不存在）。
⚠ 值得写进文档的补充：轮 1 之后会多出**外观相同的第二条草案**（`\tT27TAB阀门` 与 `T27TAB阀门` 在列表里看不出差别），运维需再扫描+再认领一次 → 见 R2-F1。

### 2.4 真库证据 C：扫描只读（acceptance 第 3 条）

夹具 `T27FP1`（`party_b=' T27FP乙方 '`，`char_length=9`）→ 记录 `md5(group_concat(id|type|party_a|party_b|customer_id|supplier_id|del_flag|update_time))` → `POST /ctms/migration/scan`（`count=1`）→ **指纹 md5 完全一致**（`1ea7b9b7a33c7fcf39b593c07ab19c3e` → 同值），`party_b` 长度仍 9、草案 `raw_name='T27FP乙方'`、`count=1`；收尾残留 0。

### 2.5 库结构复核

`t_ctms_party_draft`：**13 列**、`PRIMARY(id)`、`UNIQUE uk_party_draft(party_type, raw_name)` —— 与 DDL（`sql/二开-合同台账.sql:262-282`）、实体、`resultMap` 四方一致，本轮未被改动。

## 3. F2 —— 文档规模

- `notes/migration-notes.md` 实测 **451 行 / 38314 字节**（`[IO.File]::ReadAllLines`=451、`Get-Content -Encoding UTF8`=451、默认读法=338 → 后者是 GBK 伪数）；正文 `:451` 自带"本文档当前 451 行 / 38314 字节"说明，§8.2 写清三种读法对照与 BOM 纪律。
- `tasks.md:529` 已把 7.6 记录里的"282 行"更正为 **370 行**，并在 `:530-531` 附更正说明（"由 t16 复核发现、captain 复核确认"）——round 1 的 F2 在 `tasks.md` 侧也已闭合。

## 4. F3 —— 真库回归资产（我独立执行）

`tools/ctms-migration-check.ps1`（551 行、UTF-8 with BOM、并发锁、退出码 0/1/2）：我按脚本自身用法直接跑，**52 通过 / 0 失败 / exit 0**，覆盖 S1（includeDeleted 生效 + 扫描只读指纹）、S1b、S2（ai_ci 聚合且都绑定）、S3（`is null` 不覆盖已绑别的档案）、S4/S5（历史含操作人 + `source=auto` + 简称兜底 + 不改写原文）、**S6/S7/S8（F1 三类）**、S9（claimed 回归）、S10/S11（ignored 不复活 + 已认领拒绝）、S12（口径自诊断：不允许"计数>0 而实绑 0"）、S13（静态口径自证：两侧都写 `trim(` 且 `trimSpaces` 存在）；收尾"夹具合同/草案/档案已全部删除"逐条 [OK]。
⇒ round 1 F3 要求的"把真库语义沉淀成可重复资产"已落地，10.1/10.2 可直接复用。

## 5. 断言有效性（突变矩阵：把实现改坏，用例必须变红）

在仓库副本（t26 修复后的当前代码）里逐条突变、跑全量 212 条，**收尾逐字节比对确认副本=仓库原文（实现类/规则类均 True），仓库源码零改动**。

| 突变 | 红条数 | 变红的用例（节选） |
| --- | --- | --- |
| 基线 | **0** | —— |
| M1 去掉聚合的"该方向已绑定档案 → 跳过" | **5** | `已绑定该方向档案的合同不参与扫描`、`批量绑定不覆盖已绑定别的档案的合同`、**`首尾空格变体…再扫描稳定停在 claimed`**、`Tab/全角…稳定停在 claimed` |
| M2 `claimed` 回归时不清 `matched_id` | **1** | `认领后出现新合同则回到待认领` |
| M3 `ignored` 跟着刷新计数 | **1** | `已忽略的草案不被自动复活` |
| M4 分组键退化为大小写敏感 | **2** | `同一文本的大小写差异按唯一索引进同一条草案`、`MigrationRulesTest.草案键与唯一素引同口径` |
| **M6 `trimSpaces` 退回"去 <=0x20"（= 还原 F1 旧口径）** | **2** | **`Tab包裹的文本两侧同口径且认领后真被绑定`（tab 未被保留）**、`MigrationRulesTest.原始文本只去首尾空格…（Tab 两侧都保留）` |

⇒ 关键断言（聚合口径 / claimed 回归 / ignored 不复活 / 幂等键口径 / **本轮 F1 修复本身**）都被用例真实钉住；M6 是专门针对 F1 修复的"退回去必须变红"对照。

## 6. 规格 6 Requirement 与第 7 组 6 项任务的映射（round 2 状态）

映射表主体（Requirement ↔ 实现文件 ↔ 用例名 ↔ 证据）见 `notes/migration-review.md` §2；本轮**结论不变**，变化点只有 3 处：

| 条目 | round 1 | round 2 增量 |
| --- | --- | --- |
| R1 扫描生成草案 | 通过 | 口径改为"两侧都去 0x20"；新增 4 条用例；真库三组空白变体复跑（§2.2/§2.3） |
| R2 刷新与幂等 | 通过 | 不变；收敛性用真库 3 轮轨迹（touched 4→3→0）补强 |
| R3 认领与批量绑定 | 通过（但空白文本边界**不收敛**） | **空格类一轮绑全**、非空格类两轮收敛；"计数>0 而实绑 0"有 `log.warn` + S12 + 单测 `候选为空时实绑计数如实落零` |
| R4 未认领不阻塞业务 | 通过 | 不变（本轮未触碰导出面） |
| R5 体检清单/报告/快照 | 通过 | 两条路径复跑：29/0 exit 3 与 32/0 exit 0（§1） |
| R6 约束回补范围与口径 | 本轮仅覆盖编排能证的部分 | 不变（承接 round 1 的取证边界说明） |
| 任务 7.1~7.6 | 7.1/7.2/7.3/7.4/7.5/7.6 均通过 | 7.6 的行数记录（F2）与 7.1~7.3 的 F1 一并闭合；`tasks.md:558-579` 已由 captain 补记 round-2 修复记录 |

## 7. Findings（round 2）

### R2-F1（low，可选）§1.7「已知边界」的影响面可再补一句：剩余变体会以另一条外观相同的草案出现

- **problem**：`notes/migration-notes.md:129`（§1.7）与 `:406`（§8）把边界影响面写成"一轮认领可能只绑上代表文本那一份，不产生实绑 0、不永久往复"——方向正确（我实测确认不往复），但**漏了运维可见的中间形态**：
  轮 1 认领后，剩余变体会在**下一次扫描**里生成**另一条草案**（名称只差一个不可见的前导空白，如 `\tT27TAB阀门` 与 `T27TAB阀门`），列表里看起来是"两条同名草案"（一条 claimed 计数 1、一条 pending 计数 1），需要**再扫描 + 再认领一轮**才收敛。我实测的轨迹是 `touched 4 → 3 → 0`（第 3 轮无写）。
- **file / line**：`openspec/changes/oa-contract-ledger/notes/migration-notes.md:129`、`:406`
- **requiredFix**（二选一，均为低风险）：
  1. 在 §1.7 的「已知边界」补一句影响面："非 ASCII 空白的首尾变体会在下一轮以**另一条草案**（名称仅差不可见空白）出现，需再扫描+再认领一轮；真库实测 3 轮收敛（touched 4→3→0，终态全部 claimed）"；
  2. 顺手把这条多轮收敛做成断言（`CtmsMigrationServiceImplTest` 加一条"plain + tab 混布 → 两轮内全部绑定、终态不再写库"，或在 `tools/ctms-migration-check.ps1` 的 S7 旁加 S7b）——这样将来谁改动 `rawNameKey`/候选口径，除了 S12 的"实绑 0"信号之外还能直接钉住收敛性。
  注：即使不改，现有证据（S12 + 本轮真库实测）已足以判定修复有效，因此**不影响 pass**。

## 8. 观察（不计 finding）

- **O1 旧 jar 的 37/14 无法复跑**：`ruoyi-admin.jar` 已重建为 15:52:04，旧包不可再现；但"空格类实绑 0 且回 pending"这一症状形态我在 round 1（t16 探针 C）亲自复现过，与 t26 的 14 条失败同因，历史证据可信。
- **O2 `trim(` 计数口径**：XML 全文 `trim(` = 8（SQL 4 + 文档注释 4），t26 记录的"4"指 SQL 侧，两者不矛盾。
- **O3 t26 改动面与声明一致**：8 个交付文件（`MigrationRules` / `CtmsMigrationServiceImpl` / `CtmsContractMapper.xml` / 两个测试类 / `notes/migration-notes.md` / `tools/ctms-migration-check.ps1` / `tasks.md` 记录由 captain 补）；未动 `sql/`、controller、ui-master、`tools/audit`。
- **O4 上游 round 1 的两条非 group-7 事项**：`tasks.md` 10.1/10.2 仍未勾选（属第 10 组）；`notes/migration-review.md`(t16) 保持原判定不变。

## 9. 未覆盖边界与卫生

- 未复跑：`b3-sql-drill.ps1` 与第 2 组三份 SQL 的逐列评审（不属第 7 组本轮修复面，round 1 已标注取证边界）。
- 夹具/产物：`T27*`（合同/草案/档案/历史）全部撤销，残留 **0**；`b3*` 演练库 **0** 个；我的临时脚本与日志都在 `$env:TEMP\ctms-review*`；未改任何实现文件，本轮仅新增这份复审 note。
