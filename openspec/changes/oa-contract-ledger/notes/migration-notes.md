# 迁移工具交付说明（扫描 / 认领 / 忽略 / 迁移准备）

> 变更集：`oa-contract-ledger`（B3 合同台账）第 7 组｜规格：`specs/ctms/contract-migration`
> 任务：7.1 扫描生成草案、7.2 刷新幂等与状态流转、7.3 认领与批量绑定、7.4 未认领不阻塞、7.5 迁移准备编排
> 生成日期：2026-10-05（第 7 组收敛；本文件由 **t25（7.6b）** 交付，取代被 failed 的 t6 —— t6 的验收要求走通「认领」，而当时 7.3 尚未落地且 8080 上还是旧 jar）
> 读者：接手迁移的运维/后端 与 需要照着做一遍的验收人

**一句话**：把「历史合同里只有文本、没有档案引用」的甲乙方，按「档案方向 + 原始文本」聚合成**待认领草案**；
人工认领（可新建档案、也可绑定已有档案）后，按原始名称**批量回填**合同的档案引用并逐份留痕；
上数据库约束之前**先体检 → 0 违例才拍快照 → 最后才执行 DDL**；每一步都可回滚。

---

## 0. 真源清单（先看这张，不要抄数字）

| 关注点 | 真源（改这里才生效） | 位置 |
| --- | --- | --- |
| 方向映射 / 状态枚举 / 状态机 / 分组键 | `MigrationRules` | `ruoyi-ctms/.../support/MigrationRules.java`：`STATUS_PENDING/CLAIMED/IGNORED`（62/65/68）、`directionOf`（121）、`isDirection`（155）、`rawNameKey`（236）、`claimable`（287）、`checkClaimable`（302）、`ignorable`（325）、`checkIgnorable`（338） |
| 拒绝文案（验收按关键字断言） | `MigrationRules` 的常量 | `MSG_ALREADY_CLAIMED`（75）＝「该草案已认领，不能重复认领」；`MSG_ALREADY_CLAIMED_CANNOT_IGNORE`（81）＝「该草案已认领，不能忽略」 |
| 变更历史字段名 / 对象类型（整单编辑与认领**同字**） | `ContractRules` | `FIELD_CUSTOMER`（67）`FIELD_SUPPLIER`（72）`OBJECT_TYPE_CONTRACT`（80） |
| 端点与权限点 | `CtmsMigrationController` | scan（55/57）、drafts（69/70）、claim（91/93）、ignore（109/111） |
| 草案表 13 列与唯一键 | `sql/二开-合同台账.sql` | `CREATE TABLE t_ctms_party_draft`（262~282）；`UNIQUE KEY uk_party_draft(party_type,raw_name)`（277） |
| 菜单行数 / 权限点个数 | `sql/二开-合同台账-菜单.sql` 自带自检 | 自检段（150~186）：**27 行 = 1 目录 + 4 菜单 + 22 按钮 / 26 个 `ctms:*` 权限点**；迁移四个按钮在 138~147 行 |
| 体检（只读） | `sql/体检-合同台账-20261005.sql` | 13 列逐列统计（37~139）、结论（141~151）、违例明细含主键（153~287）、只读自证（289~298） |
| 影响行数报告 + 快照 | `sql/影响行数报告-合同台账-20261005.sql` | 报告行（24~121）、快照段（~250~321）、输出与两条一致性核对（403~415） |
| 回滚 | `sql/回滚-合同台账-20261005.sql` | 逆序「删 FK → 删索引 → 恢复可空 → DROP 本变更集新建表 → 按快照反向回填 → 核对」＋**中止守卫**（缺快照拒绝执行） |
| 编排入口（体检→快照→DDL） | `tools/ctms-migration-prep.ps1` | 阻断判据注释（335~347）、四条阻断自证断言（414~417）、退出码（524~529） |
| DDL 编排演练（第 2 组） | `tools/b3-sql-drill.ps1` | 体检/快照/回滚的独立演练，本文件不重复它的断言 |
| 纪律 | `DEV-ENV.md` | §6.13 打包顺序、§6.14 未认证返回 401 验不了路由、§6.21 脱离进程起服、§6.33 中文 SQL 走 stdin + `$OutputEncoding`、§6.35 快照表会污染 `like 't_ctms\_%'` 统计、§6.43 OGNL 单引号坑、§6.44 变量后紧跟中文要写 `${var}` |

---

## 1. 接口语义

### 1.1 端点表（路径与权限点逐字，未新增权限点）

| 端点 | 权限点 | 入参 | 出参 |
| --- | --- | --- | --- |
| `POST /ctms/migration/scan` | `ctms:migration:scan` | 无 body | `AjaxResult`：`data` = 本次**新建或刷新**的草案列表，`count` = 条数（数据无变化时 `count=0`） |
| `GET /ctms/migration/drafts` | `ctms:migration:list` | 查询串：`pageNum/pageSize`、可选 `partyType`、`rawName`（模糊）、`status`（`pending/claimed/ignored`，**`all` 表示全部**）、`matchedId` | `TableDataInfo`：`total` + `rows[]`（按 `create_time desc`） |
| `POST /ctms/migration/drafts/{id:[A-Za-z0-9]+}/claim` | `ctms:migration:claim` | body（可空）= `CtmsMigrationClaimVo`：`partyId`（绑定已有）**或** `code`（新建必填）+ `name/shortName/taxNo/contactName/contactPhone/address/bankName/bankAccount/creditLimit/level` + 供应商专有 `supplyScope/paymentDays` + `remark` | `AjaxResult.data` = 认领后的草案（`status=claimed`、`matchedId`、`contractCount`=本次实绑数） |
| `POST /ctms/migration/drafts/{id:[A-Za-z0-9]+}/ignore` | `ctms:migration:ignore` | 查询串 `remark`（可空，写入草案 `remark`） | `AjaxResult.data` = 忽略后的草案（`status=ignored`） |

> **路径形态**：`{id:[A-Za-z0-9]+}` 是刻意收窄的正则（与合同控制器同款），目的是让字面量段与路径变量在映射层就分开；副作用是**非法字符的 id 会得到 404**（见 §6.1）。

### 1.2 扫描（`ICtmsMigrationService.scanPartyDrafts`，实现 148 行）

口径（三条，逐条都有对应的代码注释）：

1. **只看映射出来的那一侧**：合同类型 → 档案方向由 `MigrationRules.directionOf` 决定 —— **采购类 `PUR` 看乙方 `party_b` → 供应商方向；销售类 `SAL` 看甲方 `party_a` → 客户方向**；其余类型（`COO/LAB/FIN/NDA/OTH` 与未知值）**一律跳过**（不猜方向：猜错会把客户档案写进 `supplier_id`）。
2. **只收「该方向档案引用为空 + 该方向文本非空」**的合同；**包含已停用合同**（`includeDeleted="1"`）：迁移要覆盖全量历史，停用只是业务状态，文本仍待认领；若照列表口径排除停用，`contract_count` 就对不上人工核对的那张清单。
3. **按「方向 + 原始文本」聚合**，分组键用 `MigrationRules.rawNameKey`（与 `uk_party_draft` 所在排序规则 `utf8mb4_0900_ai_ci` 同口径：**大小写/重音不敏感**）；展示用的 `raw_name` 保留**首次出现**的原文。

写入是**「先查后写」**：`selectDraftByTypeAndName` → 未命中才 `insertDraft`；命中按 `status` 分档（见 §2）。**全程只读合同表**：对 `CtmsContractMapper` 只调用 `selectContractList`，绝不写 `party_a/party_b/customer_id/supplier_id`。

### 1.3 草案列表（`selectDraftList`，实现 190 行附近）

- `status=all` / 空串在**服务层**归一为 `null`（= 不过滤），**不写进 XML**：MyBatis 的 OGNL 把单引号字面量当 `Character`，`status != 'all'` 恒为真（DEV-ENV §6.43）。
- 列表受 `ctms:migration:list` 保护；**草案不做数据范围裁剪**（它是"文本 → 档案"的聚合，不含可识别的合同明细；若按登录用户范围裁剪，两个管理员会看到不同的 `contract_count`）。

### 1.4 认领（`claimPartyDraft`，实现 242 行）——两条路径

| 路径 | 请求特征 | 行为 |
| --- | --- | --- |
| **绑定已有档案** | `partyId` 非空 | 按方向查 `ICtmsPartnerService.selectCustomerById/selectSupplierById`，不存在抛「客户档案不存在」/「供应商档案不存在」；**不新建任何档案** |
| **新建档案后绑定** | `partyId` 为空 | 复用第 3 组的 `ICtmsPartnerService.insertCustomer/insertSupplier` 与 `PartnerRules` 必填口径（**不另写一套档案写入**）：`code` **必填**（缺→「新建档案必须提供编码」；不自动生成，避免第二套编码规则）；`name` 缺省→草案 `raw_name`；**供应商 `shortName` 缺省→草案 `raw_name`**（规格场景⑤：不允许因简称缺失卡住迁移）；客户简称选填、不兜底 |

随后**按原始名称批量绑定**（`selectUnboundPartyCandidates` → 逐份 `bindPartyRef`）：

- 只更新「**该方向档案引用为空** 且 **该方向文本 = 草案 `raw_name`**」的合同（不过滤 `del_flag`）；
- **不改文本**（`party_a/party_b` 原样保留 —— 认领只是把"文本"升级成"档案引用"）；
- **不覆盖已绑定别的档案的合同**（它们压根不在候选集合里）；
- 每份被更新的合同写**一条**变更历史：`field_name` = `ContractRules.FIELD_CUSTOMER/FIELD_SUPPLIER`（「客户档案」/「供应商档案」），`old_value` = 空，`new_value` = **档案ID**（与整单编辑同口径），`source=auto`，`operator_id`/`operator_name` = 当前操作人，`object_type=contract`，档案名称放在 `note` 里（便于人读时间线）；
- 草案落 `claimed` + `matched_id` = 档案ID + `contract_count` = **本次实绑数**（比扫描时的计数更真）。

整个方法带 `@Transactional(rollbackFor = Exception.class)`：档案写入、引用回填、变更历史、草案状态**同事务**，部分失败不留半成品。

### 1.5 忽略（`ignorePartyDraft`，实现 308 行）

- `pending` 可忽略；`ignored` **可重复忽略（幂等**，后一次 `remark` 覆盖前一次）；`claimed` **拒绝**（已认领的草案已经把引用写进合同，再"忽略"会让状态与事实矛盾）→ 文案「该草案已认领，不能忽略」。
- 忽略后草案**不会被扫描复活**（§2），历史合同的文本原样保留、继续正常展示。

### 1.6 错误形态

业务异常由 `GlobalExceptionHandler` 统一成 `HTTP 200 + {"code":500,"msg":"<文案>"}`（数据范围类为 `code=403`）；**鉴权/路由类**才是非 200（未认证 401、路径不存在 404）。认领/忽略的常见文案：

| 场景 | 文案（关键字加粗） |
| --- | --- |
| 草案不存在 | 草案不存在 |
| 重复认领 | 该草案**已认领**，不能重复认领 |
| 认领后想忽略 | 该草案**已认领**，不能忽略 |
| 新建缺编码 | 新建档案必须提供编码 |
| 绑定不存在的档案 | 客户档案不存在 / 供应商档案不存在 |
| 跨合同搬迁行项（契约侧） | 行项不属于该合同 |

---

## 1.7 两条链路的文本比较口径（t26 / t16 F1 修复后的唯一口径）

**扫描链路（Java）**：`MigrationRules.normalizeRawName` = **只去首尾 ASCII 空格（U+0020）**，其余字符（Tab / CR / LF / 全角空格 / NBSP）原样写进 `raw_name`。
**认领链路（SQL）**：`CtmsContractMapper.xml` 的 `selectUnboundPartyCandidates` = `trim(c.party_a|party_b) = trim(#{rawName})`；MySQL 的 `TRIM()` **默认只去 0x20**。

两侧逐类同口径（本机 MySQL 8 + JDK 11 实测）：

| 字符 | 修复前 Java（`ContractRules.trimToNull` 用 `Character.isWhitespace`） | MySQL `TRIM()` | 修复后 Java（`MigrationRules.trimSpaces`） | 一致？ |
| --- | --- | --- | --- | --- |
| ASCII 空格 U+0020 | 去 | 去 | 去 | ✅ |
| Tab U+0009 | 去 ❌ | 不去 | **保留** | ✅ |
| 换行 / 回车 U+000A / U+000D | 去 ❌ | 不去 | **保留** | ✅ |
| 全角空格 U+3000 | **去**（`Character.isWhitespace('\u3000') == true`） ❌ | 不去 | **保留** | ✅ |
| NBSP U+00A0 | 不去 | 不去 | 保留 | ✅ |

⚠ **一个容易抄错的细节**：`Character.isWhitespace` **包含 U+3000**（而 `String.trim()` 不包含），本仓库的 `ContractRules.trimToNull` 用的正是前者 —— 所以修复前**全角空格也在差集里**（真库实测：草案 `raw_name` 丢掉了 U+3000，认领候选一条都匹配不上 → 实绑 **0**）。
结论：**"只改 SQL 的 `TRIM()`"修不好 Tab 与全角这两类**（MySQL 的 `TRIM(remstr)` 是把 remstr 当"整串重复"去、不是字符集合，实测 `'\tx\t'` 原样返回），必须两侧同时改成"只去 0x20"。
⚠ 因此 `MigrationRules.normalizeRawName` **不能改回** `ContractRules.trimToNull` / `String.trim()`；改任一侧都必须同步改另一侧，并由服务层三类回归用例 + §8.1 的真库脚本同时钉住。

> **口径表更正记录**：本节的差集最初由 captain 初审给出，当时按 `String.trim()` 推断，**少列了全角空格 U+3000**；t26 实现时读真源发现实际走的是 `ContractRules.trimToNull` 的 `Character.isWhitespace`（含 U+3000），并用旧 jar 在真库上复现了"全角类实绑 0"，据此**补全为上面这张表**。凡按本节推断口径，以这张表（而不是更早的口径描述）为准。

**修复前的真库症状（旧 jar 上可复现，见 §8.1 的 S6~S8）**：

| 类 | 草案计数 | 本次实绑 | 后果 |
| --- | --- | --- | --- |
| 空格四写法（无 / 前导 / 尾随 / 双侧多空格） | 4 | **1** | 再扫描时剩余 3 份仍未绑定 → 草案回 `pending` → `claimed↔pending` 往复 |
| Tab 包裹 | 1（`raw_name` 丢了 Tab） | 认领时找不到草案 | 同上 |
| 全角空格包裹 | 1（`raw_name` 丢了 U+3000） | **0** | "计数 &gt; 0 而实绑 0" |

**可诊断信号**：认领时若出现"草案计数 &gt; 0 而本次实绑 0"，`CtmsMigrationServiceImpl` 会打一行 `log.warn`（含 draftId / direction / rawName / 草案计数 与排查提示）；真库侧由 §8.1 的 S12 直接判红。

**已知边界（如实登记，本轮不修）**：Java 分组键用的 `Collator.PRIMARY` 把空白当**可忽略元素**，而 MySQL `utf8mb4_0900_ai_ci` **空白显著**（实测 `rawNameKey("X阀门") == rawNameKey("\tX阀门")`，而 `select 'x' = '\tx' collate utf8mb4_0900_ai_ci` = 0）。
影响面：同一文本的空白变体若混在一条分组里，**一轮认领可能只绑上"代表文本"那一份**；代表文本必然匹配（`trim(本方向文本) = trim(raw_name)` 对它是恒等式），所以**不会出现实绑 0、也不会永久往复**。
**它的可观察表现（务必照此判读，别当成 bug）**：没绑上的空白变体会在**下一次扫描**时成为**另一条"看起来一模一样"的草案**（`raw_name` 与该变体原文一致、`contract_count` 是剩余数），**再认领一轮即收敛**。
**实测的收敛轨迹**（round-2 复审独立复现，Tab / 全角 U+3000 / NBSP 混布夹具）：`touched 4 → 3 → 0`，三轮后 8/8 合同全部绑定、草案终态全 `claimed`、**无 pending 残留、无实绑 0**。
规避：认领后核对"**实绑数 == 草案计数**"；不等就再跑一次「扫描 + 认领」，直到草案列表稳定。要彻底一致需把分组键改成"空白显著 + 大小写不敏感"，会牵动 `uk_party_draft(party_type, raw_name)` 的语义，超出本任务范围。

## 2. 草案状态机

### 2.1 流转表

| 源状态 | 触发 | 目标状态 | 其他列变化 | 是否写库 |
| --- | --- | --- | --- | --- |
| （库里没有） | 扫描命中 | `pending` | `contract_count`=命中数、`matched_id`=空 | 插入 1 行 |
| `pending` | 扫描命中（计数变化） | `pending` | `contract_count` 刷新 | 仅计数变化时写 |
| `pending` | 扫描命中（计数没变） | `pending` | 无 | **不写**（幂等：重复扫描 0 次 update） |
| `pending` | **认领** | `claimed` | `matched_id`=档案ID、`contract_count`=实绑数、`remark`=认领备注、`update_*` | 写 1 次 + 逐份合同回填与历史 |
| `pending` | **忽略** | `ignored` | `remark`=忽略原因、`update_*` | 写 1 次 |
| `claimed` | 扫描命中（仍有未绑定合同用同一文本） | `pending` | **清空 `matched_id`**、`contract_count` 刷新 | 写（状态必然变化） |
| `claimed` | 再次认领 / 忽略 | 不变 | 无 | **拒绝**（关键字「已认领」） |
| `ignored` | **认领** | `claimed` | 同"pending → claimed" | 写（忽略是暂缓，不是永久否决） |
| `ignored` | 扫描命中 | `ignored` | **整行不动（含计数）** | **不写** |
| `ignored` | 再次忽略 | `ignored` | `remark` 覆盖 | 写（幂等成功） |
| 其它/为空 | 扫描命中 | —— | —— | 抛「草案状态不合法」快速失败（事务回滚） |

### 2.2 触发条件与真源

- 「可认领」= `pending` 或 `ignored`（`MigrationRules.claimable`，287 行；守卫 `checkClaimable`，302 行）；
- 「可忽略」= `pending` 或 `ignored`（`ignorable`，325 行；`checkIgnorable`，338 行）；
- 扫描的分档在 `CtmsMigrationServiceImpl.scanPartyDrafts`（148 行起）；
- 认领入口先过 `requireClaimable`（224 行）→ 两条路径共用同一个状态守卫，**不允许各写一份判定**。

### 2.3 两处裁决（写清楚理由，避免后人以为是漏刷/漏改）

1. **已忽略的草案连计数也不刷新**：规格对 `ignored` 只有一条约束——「MUST NOT 被自动改回待认领」；忽略行既不在待认领列表、也不参与认领，`contract_count` 没有消费方；整行不动 = 幂等可用"0 次 update"证明，也避免"已经忽略了却在暗中跟着数据变"的错觉。**若将来有消费方需要 ignored 的计数，改这一档并同步补断言**（用例名：`已忽略的草案不被自动复活`）。
2. **已认领的草案在发现新未绑定合同时回到待认领**：聚合阶段已经排除了"该方向已绑定档案"的合同，所以这一组能存在，就等于**确实还有未绑定合同在用这个文本**；不回归的话，这批新合同会永远停在"有文本、没档案"，且没有任何入口提醒运维。（用例名：`认领后出现新合同则回到待认领`）

---

## 3. 迁移准备（体检 → 快照 → DDL）

### 3.1 两条通道（**先分清，否则会把命令跑在错的库上**）

| 通道 | 覆盖步骤 | 作用对象 | 用什么执行 |
| --- | --- | --- | --- |
| **SQL 通道** | 体检、影响行数报告+快照、DDL、回滚 | **一次性演练库**（`b3_*_drill`）或目标存量库 | `mysql.exe` + `sql/` 下的脚本（中文文件名走 stdin），编排入口 `tools/ctms-migration-prep.ps1` |
| **应用通道** | 扫描、认领、忽略、草案列表 | **运行中的后端所连库**（本环境是 `rad_oa`） | `curl`/`Invoke-RestMethod` 调 `/ctms/migration/*`，带登录 token |

> 两通道**不是**同一个库：应用的 `DataSource` 指向业务库（`rad_oa`），而演练库是一次性库。演练时应用通道要用"文本夹具 + 用完即撤"，SQL 通道在一次性库上做（见 §4.3 的撤离口径）。

### 3.2 执行顺序（顺序不可换）

```
① 体检（只读）            体检-合同台账-<日期>.sql
② 有违例 → 阻断           不生成快照、不执行 DDL；违例清单落盘并打印路径
③ 0 违例 → 影响行数报告+快照  影响行数报告-合同台账-<日期>.sql（快照 = 回滚的唯一凭据）
④ 最后才是 DDL            二开-合同台账.sql（建表 + 约束回补：① 回填 → ② 加 NOT NULL → ③ 加 FK）
```

**为什么不能换**：`二开-合同台账.sql` 对 13 张业务表是 `DROP TABLE IF EXISTS` + `CREATE` 并内含约束回补三段；在脏数据上执行等于丢弃历史行（NULL 行无法满足 NOT NULL）。快照也必须在 DDL **之前**拍，否则回滚脚本没有"动之前"的凭据（缺快照时它会**拒绝执行**）。

一条命令走完 ①~④（演练库 + 日志落到临时目录）：

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-prep.ps1 `
  -Database b3_migration_notes_drill -LogDir $env:TEMP\ctms-notes-drill
```

### 3.3 阻断判据与退出码（**核心口径**）

**判据 = 逐列违例行数之和 > 0 就阻断**（`tools/ctms-migration-prep.ps1`，注释在 335~347 行）。

**为什么不用体检结论行的「需回填 N 列」**：体检脚本的档位语义是「列当前可空 → 需回填（**但违例行数可能是 0**）」「列已收紧 → 可加约束」。也就是说，在**只有可空列、没有一行脏数据**的健康存量库上，结论行照样会写「需回填 N 列」—— 照它判就会**误阻断**一条本该放行的迁移路径。规格要求的是「体检对全部待加约束列均返回 **0 违例** → 允许继续」，判的是**行**不是**列**：`违例行数合计 = 0` 就放行（哪怕列还等着 DDL 去收紧），`> 0` 就阻断。

**退出码语义**：

| 退出码 | 含义 |
| --- | --- |
| `0` | 体检通过并跑完 报告+快照+DDL（断言全绿） |
| `1` | 断言失败（含"阻断路径里居然动了库"这类硬断言红了） |
| `2` | 库名守卫拒绝（`rad_oa` 与系统库在**建立连接前**就拒绝；库名只允许 `[A-Za-z0-9_]`） |
| `3` | **体检未通过而阻断**（不生成快照、不执行 DDL）—— 这是"预期中的非成功"，不是脚本故障 |

阻断时脚本用**四条硬断言**自证"除了读什么都没做"（414~417 行）：`*_bak_*` 快照 0 张、13 个收紧目标列仍全部可空（= DDL 的『加 NOT NULL』阶段未执行）、编排前后表名集合一致、夹具业务表数据指纹一致。

---

## 4. 真实演练（2026-10-05，在**重打包后的新 jar** 上）

### 4.0 执行前置：运行态自证（**这一步必须先做**）

| 检查 | 命令 | 实测结果 |
| --- | --- | --- |
| jar 时间戳晚于最后一次代码变更（t22） | `(Get-Item ruoyi-vue-oa-master\ruoyi-admin\target\ruoyi-admin.jar).LastWriteTime` | **2026-10-05 15:13:30**（旧包 14:00:06），size 184236121（旧 184213507） |
| 后端在跑 | `Get-NetTCPConnection -LocalPort 8080 -State Listen` | 有监听（pid 由 captain 用 `Start-Process` 脱离进程方式起） |
| 新端点真的在 | 用真实 token 调 4 个端点（**合法形态的 id**） | scan → **200**（`count=0`）、drafts → **200**（`total=0`）、claim → **200**（`{"code":500,"msg":"草案不存在"}`）、ignore → **200**（同）；对照 `GET /ctms/migration/nope-not-here` → **404** |

> ⚠ **自证不成立就立即停**：如果 4 个端点里任何一个返回 404/401，说明 8080 上跑的还是旧 jar（此时任何"按文档跑通"的证据都是假的），必须先按 DEV-ENV §6.13 重打包重启：停后端 → `mvn -B -DskipTests -pl ruoyi-ctms clean install` → `-pl ruoyi-admin clean package` → 核对 jar 时间戳 → 重启。

### 4.1 运行 A：脏存量库 → 体检阻断（期望退出码 3）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-prep.ps1 `
  -Database b3_migration_notes_drill -WithViolations -LogDir $env:TEMP\ctms-notes-drill
```

实测摘要（原始输出留档 `$env:TEMP\ctms-notes-drill\ctms-migration-prep-blocked.log`）：

```
体检结论：需回填 13 列；违例行数合计 22 行（0 = 通过）
违例清单已落盘：...\ctms-migration-prep-violations-b3_migration_notes_drill-20261005-151545.txt
[OK] 阻断：未生成任何快照表（*_bak_* = 0 张）
[OK] 阻断：13 个收紧目标列仍全部可空（13/13）→ DDL 的『加 NOT NULL』阶段未执行
[OK] 阻断：编排前后表名集合完全一致（未新建/未删除任何表）
[OK] 阻断：夹具业务表数据指纹一致（未跑 DDL 的回填/加约束）
[阻断] 体检未通过：需回填 13 列、违例行数合计 22 行。按规格 MUST NOT 执行加约束 DDL；本次未生成快照、未执行 DDL。
体检阻断路径演练结束：断言 通过 29 / 失败 0（退出码 3 = 体检未通过而阻断，未生成快照、未执行 DDL）
```

期望与实测一致：**退出码 3**、断言 29/0、违例清单含列名/违例行数/**可定位主键**（如 `t_ctms_contract DRLCT1 dept_id NULL`）。

### 4.2 运行 B：健康存量库 → 报告+快照 → DDL（期望退出码 0）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-prep.ps1 `
  -Database b3_migration_notes_drill -LogDir $env:TEMP\ctms-notes-drill -KeepDatabase
```

（`-KeepDatabase` 只为接着做 §4.4 的回滚；**默认行为是收尾 `DROP DATABASE`**。）

实测摘要：

```
体检结论：需回填 0 列；违例行数合计 0 行（0 = 通过）
[OK] 报告行数与真库 COUNT(*) 逐表一致（13 张）
[OK] DDL 第 1 次执行无错误
[OK] DDL 第 2 次执行无错误（幂等）
[OK] 13 个收紧目标列已全部收紧为 NOT NULL（13/13）
[OK] 本变更集相关外键已建立（17 条）
体检通过路径演练结束：断言 通过 30 / 失败 0
```

期望与实测一致：**退出码 0**、断言 30/0；13 张业务表逐表「报告受影响行数 == 真库 `COUNT(*)` == 快照表实查 == 报告快照行数」；
报告自带的两条结论为「通过：所有已生成快照的行数都与受影响行数一致」「通过：需要快照的行都已生成快照」；
DDL 连跑两次均无 ERROR（幂等）；13/13 列已收紧、相关外键 17 条。
同一命令在同一库名上再跑一次：退出码 0、断言 30/0（两次均为 30 通过 / 0 失败；逐条 diff 一致的证明在 t3 交付记录里，用的是同一版脚本）。

### 4.3 应用通道：扫描 → 认领 → 忽略 → 再扫描（在运行中的后端上）

夹具（用 API 造"迁移期形态"的历史合同：有文本、无档案引用；合同类型 `PUR`/`SAL`）：

```powershell
$H = @{ Authorization = "Bearer $((Get-Content F:\dsh\ruoyiOA\.cache\token-superAdmin.txt -Raw).Trim())" }
# 3 份采购（乙方文本相同 = 文档演练阀门有限公司<ts>）+ 1 份销售（甲方文本 = 文档演练集团<ts>）
Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8080/ctms/contract -Headers $H -ContentType 'application/json' ``
  -Body '{"contractNo":"PURZC202610700001","name":"文档演练采购合同1","type":"PUR","partyA":"我方智澈公司","partyB":"文档演练阀门有限公司151508"}'
# ① 扫描
Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8080/ctms/migration/scan -Headers $H
# ② 取草案 id
Invoke-RestMethod -Method GET  -Uri 'http://127.0.0.1:8080/ctms/migration/drafts?pageNum=1&pageSize=50&status=all' -Headers $H
# ③ 认领（新建供应商档案；刻意不传 shortName → 期望以 rawName 兜底）
Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8080/ctms/migration/drafts/<草案id>/claim -Headers $H ``
  -ContentType 'application/json' -Body '{"code":"SUP-NOTES-151508"}'
# ④ 忽略另一条草案
Invoke-RestMethod -Method POST -Uri 'http://127.0.0.1:8080/ctms/migration/drafts/<草案id>/ignore?remark=文档演练：这条不认领' -Headers $H
```

实测摘要（数据库侧逐项核对，`mysql.exe ... -e "select ..."`）：

| 检查 | 实测 |
| --- | --- |
| 扫描 | `code=200 count=2`（新建 2 条草案：供应商方向 1 条 `contract_count=3`、客户方向 1 条 `contract_count=1`） |
| 草案列表 | `total` 含这两条，`status=pending`、`partyType=supplier/customer` 与方向一致 |
| 认领 | `code=200`，草案 `status=claimed`、`matchedId=E71EEF63…D7A0`、`contractCount=3` |
| 3 份合同的供应商引用 | `select count(*) … where supplier_id='E71EEF63…'` = **3** |
| 乙方文本 | `select count(*) … where party_b='文档演练阀门有限公司151508'` = **3**（**文本未被改写**） |
| 变更历史 | **3 条**，逐条 `field_name=供应商档案`、`new_value=E71EEF63…`、`operator_id=superAdmin`、`source=auto`，`object_id` 三份各不相同 |
| 供应商简称兜底 | `short_name = 文档演练阀门有限公司151508`（= 草案 `raw_name`），`name` 同值 |
| 忽略 | `code=200 status=ignored`，`remark=文档演练：这条不认领` |
| 再扫描（幂等/不复活） | 被忽略草案仍 `ignored`；已认领草案仍 `claimed`；销售合同引用仍为空（因为它那条草案被忽略了） |

夹具撤离（**用完即撤**，读数为 0 才算干净）：删除本次夹具的变更历史 → 合同 → 新建的供应商档案 → 两条草案；
撤离后 `夹具合同=0 / 夹具草案=0 / 新档案=0 / 变更历史=0`，`rad_oa` 的合同总数与草案总数都回到 0。

> 注意：扫描是**全库**行为（会对库里所有"未绑定文本"的历史合同建草案）。演练只清理**本次夹具**产生的行；真实迁移时不要"扫描完就清理草案"——草案就是要留在库里给人工认领的。

### 4.4 回滚（在 §4.2 留下的演练库上真实执行）

```powershell
cd F:\dsh\ruoyiOA
$mysql = "$PWD\env\mysql\server\bin\mysql.exe"
Get-Content -Raw -Encoding UTF8 'ruoyi-vue-oa-master\sql\回滚-合同台账-20261005.sql' |
  & $mysql --host=127.0.0.1 --user=root --database=b3_migration_notes_drill --default-character-set=utf8mb4
```

实测摘要：

| 核对项 | 回滚前 | 回滚后 |
| --- | --- | --- |
| 本变更集 13 张业务表 | 13 | **0** |
| 相关外键（双向：本表引用 / 被他表引用） | 17 | **0** |
| `t_ctms_contract.create_id` 可空性 | `NO`（已收紧） | 列随表消失 |
| 库内表总数 | 148 | 135 |
| `*_bak_20261005` 快照表 | 19 | **19（全部保留）** |
| 8 张存量表行数（`sys_user/sys_dept/sys_menu/sys_dict_type/sys_dict_data/sys_config/t_code_config/t_code_config_rule`） | —— | **逐表与回滚前一致** |
| 回滚脚本输出是否含 `ERROR nnnn` | —— | **否** |

**结论**：本变更集新建的对象（13 表 + 17 外键 + 索引）全部消失；19 张快照表**刻意保留**（回滚凭据；要彻底清理再人工 `DROP`）；
存量表**逐表行数一致、表一个没少**（未被触碰）。最后 `DROP DATABASE b3_migration_notes_drill` 收尾（查询报 `Unknown database` 即已删）。

---

## 5. 回滚步骤（可照抄）

> 触发场景：DDL 执行后发现问题、或约束回补导致业务异常需要回到"动之前"。

```powershell
# 0) 先确认回滚凭据在：缺快照必须拒绝执行（宁可失败也不要"回滚一半"）
& $mysql --host=127.0.0.1 --user=root --database=<目标库> --batch --skip-column-names `
  -e "select count(*) from information_schema.tables where table_schema=database() and table_name like '%_bak_20261005'"

# 1) 执行回滚（逆序：删 FK → 删索引 → 恢复可空 → DROP 本变更集新建表 → 按快照反向回填 → 核对）
Get-Content -Raw -Encoding UTF8 'ruoyi-vue-oa-master\sql\回滚-合同台账-20261005.sql' |
  & $mysql --host=127.0.0.1 --user=root --database=<目标库> --default-character-set=utf8mb4

# 2) 只撤约束、保留表（可选阶段开关）
#    见回滚脚本内的 CONSTRAINTS_ONLY 段：外键归零、13 列恢复可空、表与数据保留
```

**回滚后必须核对三项**（回滚脚本自己也会打印，但验收要独立查一遍）：

```sql
-- ① 本变更集新建对象全部消失
select count(*) from information_schema.tables
 where table_schema = database()
   and table_name in ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse','t_ctms_product');
-- 期望 0（⚠ 不要用 like 't_ctms\_%'：会把 *_bak_* 快照表一起数进来，DEV-ENV §6.35）

select count(*) from information_schema.key_column_usage
 where constraint_schema = database() and referenced_table_name is not null
   and (table_name like 't\_ctms\_%' or referenced_table_name like 't\_ctms\_%');
-- 期望 0

-- ② 存量对象未被触碰（逐表比对行数；白名单行还能从快照里对上）
select count(*) from sys_menu; select count(*) from sys_dict_data; select count(*) from sys_config;
select count(*) from t_code_config; select count(*) from t_code_config_rule;
-- 期望与回滚前一致；本次演练实测 8 张存量表逐表一致
```

**回滚不覆盖的三件事**（写清楚，免得误以为"回滚万能"）：
1. **已回填的档案引用**：认领写进 `customer_id/supplier_id` 的值不会因为 DDL 回滚而消失；要还原请用 `t_ctms_change_log` 的「客户档案/供应商档案」记录 + `*_bak_*` 快照反向 UPDATE（design.md 的回滚策略）。
2. **快照表本身**：回滚脚本保留 `*_bak_20261005`（19 张），需要清理时人工 `DROP`。
3. **菜单与角色授权**：菜单回滚是 `DELETE FROM sys_menu WHERE menu_id IN (...)` + 撤销角色勾选（人工步骤）。

---

## 6. 排错小节（都是实际踩到/差点踩到的形态）

1. **"端点 404"要先分辨是哪种 404**：`POST /ctms/migration/drafts/__NOPE__/claim` 返回 **404**，但那是**路由正则拒绝**（`{id:[A-Za-z0-9]+}` 不匹配下划线），**不是端点缺失**。判定端点是否存在必须用**合法形态的 id**（32 位 hex）——用它调 claim 应得到 **HTTP 200 + 业务错误「草案不存在」**。这与 DEV-ENV §6.14「未认证返回 401 验不了路由」是同一类"验证手段本身失效"的坑。
2. **旧 jar 的形态**：新增端点全部 404、老端点正常 → 八成是没重打包（`ruoyi-admin` 打包时用了旧的 `ruoyi-ctms` 依赖）。按 §6.13 顺序先 `-pl ruoyi-ctms clean install` 再 `-pl ruoyi-admin clean package`，并核对 jar 时间戳与大小。
3. **含中文的 SQL 必须走 stdin**：`Get-Content -Raw -Encoding UTF8 <中文文件名>.sql | & mysql.exe ...`，**不能**把中文文件名/SQL 当命令行参数；脚本开头要设 `$OutputEncoding = New-Object System.Text.UTF8Encoding($false)`（PS 5.1 默认 ASCII，中文列名会变 `??`，DEV-ENV §6.33）。
4. **合同新增接口不返回 id**：`POST /ctms/contract` 是 `toAjax(1)`（无 `data.id`）；夹具要拿 id 请**查库**（按 `contract_no`）或调列表接口。演练脚本里因此踩过一次"用空 id 去核对"的坑（核对结果全是 0，差点误判为"认领没生效"）。
5. **`status=all` 不能写进 XML**：`'all'` 这类字面量在 OGNL 里是 `Character`，比较恒不相等（DEV-ENV §6.43）；本模块统一在服务层归一为 `null`。
6. **统计"还剩几张表"不要用 `like 't_ctms\_%'`**：会把 `*_bak_*` 快照表数进来（DEV-ENV §6.35）；用 §5 的显式 13 表清单。
7. **PS 5.1 的字符串拼接**：`"$var"` 后面紧跟中文/引号/`$` 必须写 `${var}`（DEV-ENV §6.44）；另外命令模式下 `"...$a" + $b` 会把 `+` 当成独立参数（实测报 `ERROR 1064 ... near '''`），要先拼进变量再调用。

---

## 7. 边界与未做项（不要当成 bug）

- **契约侧行项写**（t5 追加）：`POST/PUT /ctms/contract/items`、`DELETE /ctms/contract/items/{id}`，权限点 `ctms:contract-item:add/edit/remove`；**行项主键在每次增/改/删后会重新生成**（全量替换口径），前端保存后应刷新行项列表再按新 id 操作。
- **B4 单据侧**：`t_ctms_change_log.object_type/object_id` 的多态定位已预留，单据日志不写 `contract_id`；单据侧可空列**不加约束**（规格明确）。
- **不搬运历史附件二进制**（design.md 未决项 6）：迁移只收敛甲乙方档案引用。
- **认领不做数据范围裁剪**：认领是运维动作（`ctms:migration:claim`），按全库同一口径回填；合同的列表/详情/导出仍照常受数据范围限制（范围外按标识直查返回 403）。
- **`ignored` 的计数不刷新**、**扫描包含已停用合同**：见 §2.3 与 §1.2 的理由，属于**有意为之**的口径。
- **t_ctms_party_draft 的 `create_id/create_by` 可空**：草案由扫描任务生成，可能是无登录上下文的运维/定时运行；列保持可空是第 2 组评审（§1.9）与任务 2.4 的明文口径。
- **分组键与 `ai_ci` 在空白维度不同口径**：见 §1.7 的「已知边界」。**影响面与判读**：一轮认领可能只绑上"代表文本"那一份，不产生实绑 0、不永久往复；**剩余变体会以另一条外观相同的草案出现，再「扫描 + 认领」一轮即收敛**（实测轨迹 `touched 4 → 3 → 0`）。运维看到"又冒出一条同名的草案"属**预期行为**，不是重复建条 bug。

---

## 8. 真库复核资产与量数纪律（t26 追加）

### 8.1 `tools/ctms-migration-check.ps1`（第 7 组的真库语义复核，供 10.1 / 10.3 复用）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-check.ps1
# 可选：-Database rad_oa -BaseUrl http://localhost:8080 -Force（跳过并发锁）
```

退出码：`0` 全绿 / `1` 有断言失败 / `2` **前置不成立**（jar 时间戳缺失、四个迁移端点返回 404/401 —— 此时**不产出任何验收证据**，先按 §6.2 重打包重启）。

覆盖（每条都对应"只有真实 SQL 才能证明"的语义）：

| 段 | 证明什么 |
| --- | --- |
| §0 | 运行态自证：jar 时间戳 + `scan`/`drafts`/`claim`/`ignore` 四端点非 404/401（合法 32 位 id 拿到「草案不存在」而不是 404）+ 对照组 `/nope-not-here` 404 |
| §1 | 静态口径自证：`CtmsContractMapper.xml` 两侧 `trim(` + Java 侧 `trimSpaces` 存在（改一侧就红） |
| S1 | `includeDeleted="1"` 生效（**已停用**合同进扫描）+ 扫描只读（行数与关键列指纹一致） |
| S2 | `ai_ci` 大小写不敏感：大小写两种写法聚合成一条并**都被绑定** |
| S3 | `is null` 候选过滤：已绑定**别的**档案的同文本合同**不被覆盖** |
| S4 / S5 | 每份被绑定合同各 1 条含操作人的「供应商档案」历史（`source=auto`）；未传 `shortName` 时以 `raw_name` 兜底；认领不改写原文 |
| S6 / S7 / S8 | §1.7 的三类空白（空格 / Tab / 全角）逐类"聚合 → 认领真绑定 → 再扫描稳定 `claimed`" |
| S9 | `claimed` 回归：认领后再出现同文本未绑定合同 → 回 `pending` 且清 `matched_id`，再次认领复用同一档案 |
| S10 | `ignored` 不复活（忽略后扫描仍是 `ignored`） |
| S12 | 口径自诊断：不存在"`claimed` 且 `contract_count = 0`"的草案（F1 的症状形态） |

夹具用完即撤（按显式 id 清单物理删除合同 / 行项 / 关联 / 变更历史 / 认领新建的档案 / 草案），内置并发锁 `.cache/ctms-migration-check.lock`。

### 8.2 量行数一律带 `-Encoding UTF8`（与 DEV-ENV §6.33 / §6.44 同族）

PowerShell 5.1 的 `Get-Content <文件>` **不带 `-Encoding`** 时按系统 ANSI（GBK）解码 BOM-less UTF-8，会把多字节序列读坏并**吞掉 `0x0A`**，于是行数读出**伪数**：

```powershell
(Get-Content <文件>).Count                       # ❌ 伪数（本文件实测 282）
(Get-Content <文件> -Encoding UTF8).Count        # ✅ 370（修正前实测）
[System.IO.File]::ReadAllLines($p,[Text.Encoding]::UTF8).Count   # ✅ 同样 370
```

字节数两种读法一致（31118）。→ **量行数、比对行数、写"共 N 行"这类记录时必须带编码**；`.ps1`/`.sql` 一律 UTF-8 **with BOM**，`.java`/`.xml`/`.md` 一律**无 BOM** UTF-8。

> 本文档当前：**451 行 / 38314 字节**（`Get-Content -Encoding UTF8` 量法，2026-10-05 t26 追加 §1.7 与 §8 之后）。
