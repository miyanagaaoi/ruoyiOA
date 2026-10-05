# 09 · B4 §9 集成检查（t13 交付记录）

> 本轮结论（先看这里）：**打包重启 ✅；门禁 15 项绿 / 2 项非零 / 0 项未执行；但 §9.1~§9.3 的端到端断言未闭环**，
> 且新 jar 上爆出一条**真产品缺陷**：`GET /sal/request/list` → `ClassCastException`（见 §5 缺陷 D-1）。**在 D-1 修掉前不建议放行 E2E。**
> 全部结果均为**本轮真实输出**；未执行的项在 §7 如实标注，不用旧结果冒名。

## 1. 统一打包重启（DEV-ENV §6.13，本任务唯一负责人）

| 步骤 | 命令 / 动作 | 真实结果 |
| --- | --- | --- |
| 停后端 | 只停 8080 的 java（PID 19912） | `PORT-8080-FREE`（不动基础设施） |
| ① 装依赖 | `mvn -B -pl ruoyi-ctms clean install`（build 锁） | **Tests run: 543, Failures: 0, Errors: 0, Skipped: 0** + BUILD SUCCESS |
| ② 打 fat jar | `mvn -B -DskipTests -pl ruoyi-admin clean package`（build 锁） | BUILD SUCCESS |
| ③ 核对 | `ruoyi-admin\target\ruoyi-admin.jar` | **LastWriteTime = 2026/10/06 00:31:31**（本轮构建；上一版 00:00:25）、176.1MB |
| ④ 脱离进程启动 | `start-env.ps1 -Only Backend` | `START_EXIT=0`，8080 就绪 |
| ⑤ 登录 | `tools\oa-login.ps1` | `LOGIN_EXIT=0`（登录成功 5 / 失败 0） |

> 上一轮 jar（00:00:25）之后源码又新增了 4 条单测（539→**543**），因此本轮**重新打包是必要的**——
> 也正因为换了新 jar，才暴露出 §5 的 D-1（旧 jar 上 S03 还是绿的）。

## 2. 验收脚本（可重复执行、能自证）

| 脚本 | 覆盖 | 断言数（本轮真实） |
| --- | --- | --- |
| `tools\erp-smoke.ps1` | 只读冒烟：8 类列表 + 库存账两页 + 3 个选择器 + 附件注册表 + 8 类动作路由（不建业务数据） | 探针 **27** → 通过 26 / 失败 1（S03，见 D-1） |
| `tools\erp-check.ps1` | §9.1/§9.2/§9.3 主链路 + 过账/红冲/幂等/负库存/并发/唯一约束/状态机/库存账 + 权限越权；自建夹具 + 收尾清理 | **88 项**（通过 53 / 失败 14 / 跳过 21）+ `CLEAN-01` 零残留 + `CLEAN-02` 全库 `结存==Σ流水` |
| `tools\erp-scope-check.ps1`（t10 交付） | §8.2 四档数据范围矩阵 | **通过 59 / 失败 0**（exit 0） |
| 自证能力 | `erp-check.ps1 -Selftest`（2 真 + 1 故意假 ⇒ 假的必须计入 FAIL） | 见 notes/13a §1（判别力自证通过） |

## 3. 门禁全表（真实输出 + 退出码）

| # | 门禁 | 真实结果 | 退出码 |
| --- | --- | --- | --- |
| 1 | `mvn -B -pl ruoyi-ctms test` | **543 / 0 / 0 / 0** + BUILD SUCCESS | **0** ✅ |
| 2 | `node tools/audit/run-all.js` | **10 个审计 / 失败 0 / 未自证 0** | **0** ✅ |
| 3 | 前端 `node tests/run.js` | **196 / 196** | **0** ✅ |
| 4 | 前端 `npm run build:prod` | 成功 | **0** ✅ |
| 5 | `tools\erp-smoke.ps1 -Strict` | 探针 27 → **通过 26 / 失败 1**（S03） | **1** ❌ |
| 6 | `tools\erp-check.ps1`（全段） | **通过 53 / 失败 14 / 跳过 21**；`CLEAN-01`+`CLEAN-02` [PASS] | **1** ❌ |
| 7 | `tools\erp-scope-check.ps1` | **59 / 0** | **0** ✅ |
| 8 | `tools\ctms-masterdata-check.ps1` | **79 / 0** | **0** ✅ |
| 9 | `tools\ctms-contract-check.ps1` | **261 / 0** | **0** ✅ |
| 10 | `tools\ctms-attachment-check.ps1` | **107 / 0** | **0** ✅ |
| 11 | `tools\ctms-e2e-check.ps1` | **91 / 0**（零夹具基线未漂移） | **0** ✅ |
| 12 | `tools\ctms-migration-check.ps1` | 通过（迁移草案计数无 0 口径） | **0** ✅ |
| 13 | `tools\ctms-commercials-check.ps1` | 通过 | **0** ✅ |
| 14 | `tools\flow-regression.ps1` | 通过（**未为绿而改它**） | **0** ✅ |
| 15 | `tools\authz-check.ps1` | **45 / 0**（基线 30 + B3 §9.3 新增 15，含断言总数自检） | **0** ✅ |
| 16 | `tools\ctms-perm-audit.ps1` | **15 / 15**（菜单 26 / 后端 26，差异为空） | **0** ✅ |
| 17 | `tools\b3-sql-drill.ps1` | **68 / 0** | **0** ✅ |
| 18 | `tools\flow-form-consistency-check.ps1` | **27 / 0** | **0** ✅ |
| 19 | `tools\b1-binding-check.ps1` | **25 / 0** | **0** ✅ |
| 20 | `tools\serial-numbering-check.ps1` | **32 / 0** | **0** ✅ |
| 21 | `tools\print-builtin-check.ps1` | **98 / 0** | **0** ✅ |
| 22 | `tools\sign-feature-check.ps1` | **42 / 1** —— 失败项 `AC-26 未验证：找不到 signMode=REQUIRED 的在办任务`（**夹具性未验证**，非断言被推翻） | **1** ⚠ |
| 23 | `tools\related-approval-check.ps1` | 未通过（原始行：`落库 2 行关联关系（实际 0）`、`单据号快照（）`、`按控件分组返回 2 条（实际 1）`）—— **B 系列（V1 关联审批），不在本轮 B4 改动面** | **1** ⚠ |
| 24 | `tools\b1-e2e-check.ps1` / `tools\ctms-migration-prep.ps1` / `tools\ctms-contract-check` 之外的 B/C 系列长跑 | **未执行**（原因见 §7） | — |

日志：`.cache/t13/{package-t13,gates-batch2,erp-gates,b-gates,mvn-test2,audit-run-all,ui-tests,ui-build}.log`

## 4. §9.5 交付评审

### 4.1 AC 对照（对应断言名 + 执行结果）

| AC | 要点 | 对应断言（脚本/用例） | 本轮结果 |
| --- | --- | --- | --- |
| **AC-72** 下推与快照（申请→单→入库/出库、源单回填、行项快照） | 下推量/剩余量、来源单号+来源行、快照 | `erp-check` A-A1/A-A5/A-A6、B-*、E2E-PUR-1..4、S-C1..C7、E2E-SAL | ❌ **未闭环**：A-A1 FAIL（字段口径）、E2E-PUR-1 `code=-1`（派生）、S-C1..C7/E2E-SAL 仍 SKIP |
| **AC-73** 状态机 + 审核即过账 + 负库存 + 红冲 + 流水只增不改 | 5 状态 × 6 动作矩阵、过账幂等、-2 场景、红冲两行 | `erp-check` P-03/05/06/08/09/10/12/13/18/19/20、T-05/T-08/T-09 | ⚠ **部分**：库存线 P 段 **全 [PASS]**（含负库存被拒→开参数后 -2、红冲回位、重复反审核被拒）；**调拨 T-05/08/09 FAIL**（见 D-5/D-6/D-7） |
| **AC-74** 并发过账（两次都落入结存，结存==Σ流水） | 行锁 + 事务 + 重试 | `erp-check` P-15/P-16（真并发 .NET 双 Task）、`CLEAN-02` | ✅ **闭环**：P-15 `qty=40.000`、P-16 `qty=70.000`、`CLEAN-02` 全库不平凡 = 0；⚠ 但 `L-13`（recalc 在运行中段）报 `inconsistentCount=3`（脚本种子口径，见 D-8） |
| **AC-75** 调拨/盘点/额度 | 两阶段两流水、单价 0、额度不变、盘盈盘亏生成、盘亏豁免、反审核级联 | `erp-check` T-01..T-23、L-08 | ❌ **未闭环**：T-05/T-08/T-09 FAIL、T-EXC、L-08 SKIP |
| **AC-76** 存储前提 + recalc | 唯一约束/非空/精度 + 一致性校验 | `b3-sql-drill`（DDL 级）、`erp-check` P-18（`ERROR 1062` 唯一键）、L-13 | ⚠ **部分**：DDL 与唯一键有证据（68/0、P-18 [PASS]）；`L-13` FAIL（D-8） |
| **AC-78** 精度四方一致（未审核→已审核→打印→导出） | 0.125×3=0.39、路径无浮点 | `erp-check` P-01（10.00）、S-B3（0.39，**FAIL**）、L-09（打印，归 t14）、L-12（导出，未实现） | ❌ **未闭环**：S-B3 FAIL（D-3）、打印/导出两处未验证 |

### 4.2 F-1~F-7 补齐项逐条确认

| 项 | 要求 | 证据 / 结论 |
| --- | --- | --- |
| F-1 行锁与事务 | 按（物料,仓库）行锁、显式事务、重试 | ✅ `erp-check` P-15/P-16（真并发不丢更新）+ `CLEAN-02`；单测见 T1.6/5.5 |
| F-2 结存唯一索引与重试 | `uk_stock_product_warehouse` + 重复键重试 | ✅ `erp-check` P-18：第一条成功、第二条 **`ERROR 1062`** 被唯一键拒绝、该 key 仍 1 行 |
| F-3 非空与引用库级约束 | 服务端回填 + 库级 NOT NULL/FK | ✅ `erp-check` P-08：缺表头仓库 → **创建期**可读拒绝 `表头仓库不能为空：请选择入库仓库`（不是 1048/1452 堆栈）；`b3-sql-drill` 68/0 |
| F-4 数据范围映射 | 四档 + 快照 + 并集 + 导出同范围 | ✅ `erp-scope-check.ps1` **59/0**（t10 §8.2 矩阵：4/4/4、1、1、4、并集、导出=列表、越权 403） |
| F-5 流水索引 | 按（物料,仓库,id）与（单据类型,单据 id） | ⚠ **未单独取证**：DDL 断言在 `b3-sql-drill`/初始化脚本里，本轮**未逐条核对索引名**（D-11，待补） |
| F-6 日期格式串 | 无 `YYYY-MM-DD` 残留（静默错误） | ⚠ **部分**：前端 `tests/run.js` 196/196 + `node tools/audit/run-all.js` 10/10 通过；但本轮**未单独跑日期格式 grep 门禁**（D-12，待补） |
| F-7 盘点反审核级联 + 调拨附件 | 级联红冲/作废/回待审核；调拨可挂附件 | ⚠ **部分**：`ctms-attachment-check` **107/0**（附件域通用，**不特定于调拨**）；盘点级联由 `T-08` 覆盖 → **FAIL**（D-6） |

### 4.3 参考仓库 10 条"反直觉口径"复核

⚠ **未完成**（轮次预算）。本轮**已可对上号的**四条：① 货品总额度按业务类型**子串匹配"入库"且排除"调拨入库"**（`L-08` 用例存在，但本轮 SKIP，未实测）；
② 调拨流水**单价恒 0**（`T-05` 段目标，FAIL）；③ 红冲以**负数追加**、原流水保留（`P-12` [PASS]）；④ `recalc` 的修复开关风险（`L-13` FAIL + `CLEAN-02` [PASS]）。
其余六条**未逐条核对**，明确标为未闭环，不留推测。

## 5. 缺陷清单（用例 / 期望 / 实际 / 证据 / 疑似责任组）

| ID | 用例 | 期望 | 实际 | 证据 | 疑似责任组 / 初判 |
| --- | --- | --- | --- | --- | --- |
| **D-1** | `GET /sal/request/list?pageNum=1&pageSize=1` | `code=200` + 分页可读 | **`code=500`**：`class com.ruoyi.ctms.erp.sales.domain.ErpSalesRequestItem cannot be cast to class java.util.List` | 本轮实测原始 body（HTTP 200 / 业务 500）+ `erp-smoke` S03 失败 + `erp-check` S-A6 失败 | **产品缺陷（新，真）**——`erp/sales` 列表映射把行项类型当成集合（疑似 mapper `resultMap`/`ofType` 或 `@Results` 嵌套映射错误）。**建议立即派单并重打包**；它同时解释 S-A6 与 S-B3/B4/B5 的连带失败 |
| **D-2** | `A-A1` 采购申请单新增 | `code=200 + totalAmount=25.00 + status=draft` | `code=200 total= status=` | `.cache/t13/gates-batch2.log` | 脚本（响应字段口径）— 建单本身成功（`code=200`），断言取空；`E2E-PUR-1 code=-1` 为其派生 |
| **D-3** | `S-B3` 销售订单三行 1×0.125 | `totalAmount=0.39` | `code=500 total= customerName=` | 同上 | 待定性：与 D-1 同域（`erp/sales`），**优先按 D-1 修完复跑再判** |
| **D-4** | `S-B4/S-B5` 交货/地址/联系人/发货仓、详情 items+change-logs | 字段回读有值 | 全空 | 同上 | 脚本（派生自 D-3） |
| **D-5** | `T-05` 调拨审核两阶段过账 | `qtyA=6 qtyB=4` | `qtyA=6.000 qtyB=74.000 status=approved posted=1` | 同上 | **脚本（夹具/期望口径）**：过账本身正确（A 10→6、B +4），断言假设 B 初值 0 而实际 70 |
| **D-6** | `T-08` 调拨反审核红冲 | `qtyA=10 qtyB=0`、红冲 2 行 | `status=submitted qtyA=10.000 qtyB=70.000 红冲=2` | 同上 | **脚本（同 D-5 口径）**：红冲两行都写了、状态也对 |
| **D-7** | `T-09` 调出仓不足整单不写入 | 非 200 + 流水条数不变 | `code=200 操作成功 流水 66→70` | 同上 | **脚本（夹具）或产品**：需打印"请求量 vs 可用量"后定性（同 D-5 初值假设） |
| **D-8** | `L-13` recalc 默认 | `inconsistentCount=0` | `inconsistentCount=3` | 同上；收尾 `CLEAN-02` [PASS] 可证是运行中段现象 | **脚本（种子口径）**：脚本 SQL 种结存无流水，与 P-17 同类 |
| **D-9** | `L-06` 按父类型筛选（含子树） | `code=200` 且命中子树物料行 | `code=200 total=0` | 同上 | 待定性（需夹具：类型子树 + 结存行） |
| **D-10** | `T-EXC` / `A-EXC` | 段落不应抛异常 | `Cannot index into a null array.` | 同上 | **脚本（派生）** |
| **D-11** | F-5 流水索引 | 两条索引存在 | — | 未单独取证 | 待补（t13 正式轮） |
| **D-12** | F-6 日期格式串 | 无 `YYYY-MM-DD` 残留 | — | 未单独跑该门禁 | 待补（t13 正式轮） |
| **D-13** | 门禁 22/23（`sign-feature-check` / `related-approval-check`） | 全绿 | 42/1（夹具性未验证）、B 系列未通过 | `.cache/t13/b-gates.log` | 前者需在"有 `signMode=REQUIRED` 在办任务"时复跑；后者交 B 系列 owner |

## 6. DEV-ENV 登记（复核结论）

- 按 captain 安排，`DEV-ENV.md` 的 §6/§7 收编由 **t35（backend-base）** 负责，我**不并发写该文件**。
- **复核发现（需补）**：当前 `DEV-ENV.md` 中**尚未出现** `tools\erp-smoke.ps1`、`tools\erp-check.ps1`、`tools\erp-scope-check.ps1` 的 §7 门禁表条目
  （全文检索无命中；§7 目前仍标称 16 项）。⇒ **待 t35 收编后再复核一次**，并请把本轮 3 条方法论级坑（`ApiDiag` 证据形态、PowerShell `"?..."` 变量名陷阱、MySQL 临时表 1137/1267）纳入 §6。

## 7. 未执行项与原因（如实记录，不用旧结果冒名）

| 未执行 | 原因 |
| --- | --- |
| `tools\b1-e2e-check.ps1`、`tools\ctms-migration-prep.ps1`、其余 B/C 系列长跑 | 轮次预算用尽；且与 qa-hardening 的浏览器 E2E 共用库与 `-LockName env` |
| §9.1~§9.3 的端到端主链路（E2E-PUR-1..4 / E2E-SAL / E2E-TR） | **未闭环**：受 D-1（`/sal/request/list` 500）与 A 段口径影响；E2E-SAL 依赖 t8 下推端点（已交付但未跑通） |
| F-5 流水索引逐条取证、F-6 日期格式门禁、10 条反直觉口径逐条复核 | 轮次预算；已在 §4.2/§4.3 标为待补 |

## 8. 结论与放行建议

1. **可放行**：打包重启、543/0 单测、audit 10/10、前端 196/196 + build、只读冒烟（除 S03）、§8.2 四档矩阵（59/0）、既有 B 系列主要门禁（含 ctms-e2e/migration/commercials/flow-regression 全 0）。
2. **不可放行**：**D-1**（销售申请列表 `ClassCastException`，核心端点 500）必须先修；`erp-check` 全段的 14 条红项中，**D-2/D-4..D-8/D-10 属验收脚本自身**（口径/夹具/派生），**D-3/D-9 待定性**；§9.1~§9.3 端到端与 AC-72/75/78 未闭环。
3. **下一步（建议派单）**：① 修 D-1（`erp/sales` 列表映射）→ 重打包 → 复跑 `erp-smoke -Strict`（期望 27/27）+ `erp-check` 全段；
   ② 由我（t13 正式轮）修 A/T/L 段脚本口径（D-2/D-5..D-8/D-10）并补 D-11/D-12 与 10 条口径复核；③ 待 t35 收编 DEV-ENV 后复核 §7 门禁表。
