# 交接文档（给继续开发的 Agent）

> 面向对象：**下一个接手本仓库的 AI Agent**。目标：不看历史，用最短时间搞清楚
> "现在在哪、下一步做什么、怎么证明做对了、哪些坑不能再踩"。
>
> 最后更新：**2026-10-06（第七轮：B4「进销存」52 项全部交付 + 浏览器 E2E 40/0 + 门禁全绿 + 独立终验 pass + 已推送 GitHub）**
> 上一轮（第六轮，§3.9）：合同审批「能发起 + 能走完」+ 三个缺陷修复 + 浏览器 E2E 套件。
> **本轮（B4 进销存，变更集 `openspec/changes/oa-purchase-sales-stock/`）做完：**
> ① **8 类单据**（采购申请/采购单/销售申请/销售订单/入库/出库/盘点/调拨）+ **库存结存/流水**全量落地（18 张表、状态机、下推、过账/红冲、调拨/盘点、数据范围四档、导出）；
> ② **门禁**：浏览器 E2E **40 passed / 0 failed**；`erp-check` 全段 **88 通过 / 0 失败 / 25 跳过 / exit 0**；后端单测 **550/0**；前端 **205/205**；审计 **10/10**；`erp-smoke -Strict` **27/27**；`erp-scope-check` 四档 **59/0**；
> ③ **独立终验 `verdict = pass`**（终验发现并修掉了 blocker **F-01**）；
> ④ 三个仓库已提交并**推送到 GitHub**（`master` 单仓快照 + `backend`/`frontend`/`outer` 三条分支历史，见 §3.10）；
> ⑤ 本轮查出并修掉 **8 条真实缺陷**（单测结构性看不到，详见 §3.10），并登记 **9 条非阻断残留 G-01~G-09**。
> 上轮遗留的"§8 门禁跑不满"已澄清：`flow-regression` 本轮**实跑全绿**（见 §3.2 末尾更新）。
> 上轮（第五轮，§3.8）：发布校验报的问题与画布对不上（**已修**，纯前端）。
> **本轮做完：① 合同审批模板↔流程绑定（此前「新启流程」里根本没有它）；② 流程条件比较值口径 label→码值（提交报 `Error while evaluating expression`）；③ 待办标题为空的根因（`getTitle` 命中了「分组标题」排版控件）；④ 拟稿阶段屏蔽审批意见；⑤ 新增浏览器 E2E 套件 `ruoyi-vue-oa-ui-master\tests\e2e` 并跑通全链路（含发起→4 级审批→办结）。**
> 本轮新增阻塞（**未修，需决策**）：`sys_role_menu` 是空表 + `SecurityUtils.isAdmin` 只认 `superAdmin` ⇒ **非超管用户零菜单零权限**（见 §3.9.5）。
> 本轮另有两条**结论纠正**（`selfPolicy` 与「发起」语义），见 §3.9.7 —— 接手前先读，别再按旧结论推理。
> 第四轮新增缺陷记录：`doc\缺陷-单人节点指定人员可多选.md`。
> ⚠ **§8 声称的"门禁全绿"在当前环境已不成立**（见 §3.2）。

---

## 0. 一句话现状

RuoYi-Vue-OA 的 2.0 二次开发，**B3「合同台账」**（OpenSpec 变更集 `oa-contract-ledger`，64 项任务）
**已全部交付**（第 1~10 组完成、`openspec validate oa-contract-ledger` 通过）；
其后再追加了**五轮**（§3.1 流程条件字段错位 → §3.3 参与人「选人 / 选角色」方案 A →
§3.4 单人节点指定人员收敛 → §3.8 发布校验与画布不同步 → **§3.9 合同审批可发起可走完 + 浏览器 E2E 套件**）。
下一轮的候选：§3 的 **T20-F1** 收口（二选一）、**B4「进销存」**（52 项任务）、四个变更集的**归档**（§9）、
以及本轮新增的待决事项（**§11 的第 12~19 行**：测试数据清理方案 A/B/C、非超管用户的菜单权限、需求①③④②的落地）。

**2026-10-06 更新（B4 已交付）**：**B4「进销存」变更集 `openspec/changes/oa-purchase-sales-stock/`（52 项任务）已全部交付并勾选完毕（`tasks.md` 0 项未勾）**，浏览器 E2E **40/0**、门禁全绿、独立终验 **pass**、三仓已推送 GitHub。细节见 **§3.10**。
⇒ 上面那句"下一轮的候选：**B4「进销存」**"**已完成**；剩余候选收敛为：**B4 的非阻断残留 G-01~G-09**（§3.10 末）、`T20-F1` 收口、四个变更集的**归档**（`openspec archive`）、以及 §11 的待决事项（用户明确"待决策项不做"）。

### 0.1 ⚠ 接手第一件事：先看未提交的东西（改动**尚未提交**）

> **⚠ 2026-10-06 更新：B4 的改动已全部提交并推送**（`master` 单仓快照 + `backend`/`frontend`/`outer` 分支，commit 与推送表见 **§3.10 第 ⑤ 节**）。下面这段描述的是 **B3/前几轮**的历史未提交状态，**仅作追溯用**；接手时请以 `git status` 与远端 `ls-remote` 为准。

第四轮只动了 **前端 2 个文件**（后端/数据/外层脚本零改动）；**第六轮的改动见本节末尾的【第六轮】块**：

```
前端仓库（F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master）
  M src/views/workflow/simple-flow/assigneeMapping.js          ← 新增 singleModeUserIds（纯函数）
  M src/views/workflow/simple-flow/components/FlowDesigner.vue ← 选人弹窗 :type 绑定 + 写入口径收敛（§3.4）
  M tests/assignee-mapping.test.js                             ← +6 条用例（共 90/90）
```

累计未提交（含前三轮）：

```
外层仓库（F:\dsh\ruoyiOA）
  M HANDOFF.md                                      ← 本文件
  M DEV-ENV.md                                      ← 新增 §6.53（浏览器自动化"能点中什么 / 点不中什么"）
  M doc/开发进度-总体.md
  M tools/flow-regression.ps1                       ← testCondition 用例变量已同步（BOM 已核对）
  ?? doc/缺陷-流程条件字段与关联表单错位.md          ← 前几轮缺陷的完整记录（先读这个）
  ?? doc/缺陷-单人节点指定人员可多选.md              ← 第四轮（§3.4）缺陷的完整记录
  ?? tools/build-contract-approval-form.js           ← 【第四轮追加】合同审批表单内容生成器（可重跑 + 自检）
  ?? tools/contract-approval-form-content.json       ← 【第四轮追加】生成的表单 content（已入库为动态表单）
  ?? doc/2.0/合同审批表单字段构成.md                  ← 【第四轮追加】字段构成表 + PRD 条款对照 + 用法
  ?? 前端：src/views/workflow/simple-flow/flowKey.js ← 【第四轮追加】流程标识建议生成 + 格式校验（纯函数）
  ?? 前端：tests/flow-key.test.js                    ← 【第四轮追加】26 条用例（前端单测 110/110）

前端仓库（F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master）
  M src/utils/formSchema.js                          ← 改 CommonJS + extractFieldOptions
  M src/views/workflow/simple-flow/ConditionEditor.vue ← 值控件按类型分流 + 去 allow-create
  M src/views/workflow/simple-flow/components/FlowDesigner.vue ← 条件文字 label + **选人/选角色改造（§3.3）** + 单人人员收敛（§3.4） + 标识格式提示（§3.6）
  M src/views/workflow/simple-flow/index.vue                  ← 【第四轮追加】新建流程弹窗：标识建议生成 + 实时查重（§3.6）
  M tests/run.js                                     ← 挂上两个新用例文件
  ?? src/views/workflow/simple-flow/conditionText.js   ← 条件文字唯一真源（纯函数）
  ?? src/views/workflow/simple-flow/assigneeMapping.js ← 选人↔id 转换（**本轮已接入界面**，见 §3.3）
  ?? tests/condition-text.test.js                      ← 25 条
  ?? tests/assignee-mapping.test.js                    ← 27 条（上轮 12 + 第三轮 9 + 第四轮 6）
```

**【第六轮新增 / 改动】**（与前几轮同批，仍未提交）

```
外层仓库（F:\dsh\ruoyiOA）
  ?? tools/bind-flow-to-template.js                    ← 新增：把「已在简易版做好的流程」绑到可发起模板（幂等 + 5 步自检）
  ?? doc/2.0/合同审批表单字段构成.md                    ← §5.1 补记：绑定根因 / 最终状态 / 重跑命令
  ?? openspec/changes/oa-e2e-playwright/               ← 新增规划件（proposal+specs+design+tasks，4/4，--strict 通过）
  M  HANDOFF.md                                        ← 本文件（第六轮：新增 §3.9、更新 §0.1 / §1 / §8 / §11；**DEV-ENV.md 本轮未动**）
前端仓库（F:\dsh\ruoyi-vue-oa-ui-master）
  M  src/views/workflow/flow-form/index.vue            ← ①computed.isDraftStage（拟稿屏蔽意见区）②getTitle 修复
  M  src/views/workflow/simple-flow/ConditionEditor.vue← 条件值改存**码值**（原存 label）+ 更正误导性提示
  M  src/views/workflow/simple-flow/conditionText.js   ← 更正被写反的「口径真源」注释
  M  .gitignore                                        ← 增 tests/e2e/.auth/（登录态含**真实 token**，绝不能入库）
  ?? tests/e2e/                                        ← 新增浏览器 E2E 套件（独立 package.json；应用侧锁文件一字未动）
后端仓库（ruoyi-vue-oa-master）**本轮零改动** ⇒ 不需要重打包（`git -C ruoyi-vue-oa-master status` 应为空）
```

**数据侧改动（无文件，但接手必须知道）**：

| 对象 | 变化 |
| --- | --- |
| `t_template` | **+1 行** `B9B72AB7…`「合同审批」，`def_key=htsp`、绑表单 `564FA25A…`、范围=全部、已启用（8 张子表 0 行新增） |
| `t_flow_simple.htsp` | **v4**：条件比较值 label→码值（`'经营'→1`、`'经济'→2`）；**v5**：首节点受理人 `张伟 → superAdmin`（为方案 c 绕开权限阻塞） |
| 仍待清理 | **E2E 运行留下的真实单据**：每运行一次留 1 张（当前累积 `t_workflow_form` 4 / `t_workflow_todo` 2 / `t_workflow_done` 12 / `t_workflow_my_draft` 4 行，见 §3.9.6 已知限制）；以及用户要求的"测试流程清理"（方案 A/B/C 待选，见 §11） |

**后端仓库（`ruoyi-vue-oa-master`）本轮零改动**（第四轮为纯前端：`FlowDesigner.vue` 与 `assigneeMapping.js`
都只是前端文件，**没有后端编译期或接口契约变更** ⇒ 不需要重打包）。

| 变更集 | 状态 | 说明 |
| --- | --- | --- |
| `oa-v2-overview` | 交付完成（**未归档**） | 2.0 总览与交付门禁规格 |
| `oa-form-flow-tabs`（B1） | 交付完成（**未归档**） | 模板四页签重构 + 流程↔模板绑定 |
| `oa-print-builtin-templates`（B2） | 交付完成（**未归档**） | 内置打印版式按类型选用 |
| **`oa-contract-ledger`（B3）** | **✅ 64/64 完成** | 合同台账 + 主数据档案 + 迁移工具 |
| `oa-purchase-sales-stock`（B4） | **⬜ 未开工**（52 项任务） | 进销存；**复用 `ruoyi-ctms` 模块**，DDL 边界与 B3 已冻结 |

> ⚠ **四个变更集都还在 `openspec/changes/` 下、`openspec/changes/archive/` 是空的** —— 即"交付完成"与"已归档"是两件事：
> 归档动作（`openspec archive <变更集>`）**一次都没做过**，见 §9 收尾清单。

---

## 1. 5 分钟上手（照抄即可）

```powershell
# ① 起环境（幂等，可反复跑）——MySQL:3306 / Redis:6379 / RabbitMQ:5672 / 后端:8080 / 前端:80
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1   # 拿 token（会过期，重启后必跑）

# ② 读规格与任务（**唯一验收真源**，不要凭记忆写代码）
#    openspec\changes\oa-contract-ledger\specs\ctms\<能力>\spec.md
#      四个能力：contract-ledger / contract-commercials / business-partners / contract-migration
#    openspec\changes\oa-contract-ledger\tasks.md    任务 + 每条「验证：」+ 已交付项的「交付记录」
#    openspec\changes\oa-contract-ledger\design.md   技术决策 D-1~D-10
#    openspec\changes\oa-contract-ledger\notes\*.md  各组交付说明（字段/口径/已知边界/坑）

# ③ 交付门禁（改动后必跑；全表见 DEV-ENV.md §7，共 16 项）
node tools\audit\run-all.js                                                                    # 10 个静态审计
cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test                                            # 217 条
cd .. ; powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1   # 79 条
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1            # 261 条
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-commercials-check.ps1         # 116 条
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1          # 69 条
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-check.ps1           # 52 条（第 7 组迁移）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1                 # 91 条（端到端）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1                # 权限点双向核对
cd ..\ruoyi-vue-oa-ui-master\tests\e2e ; npx playwright test                                   # 【第六轮】浏览器 E2E（尚未进门禁，见 §3.9.6）
```

⚠ **两个 PowerShell 宿主都在**：`powershell`（**5.1，门禁一律用它**）与 `pwsh` 7.6.6（`C:\Program Files\PowerShell\7\pwsh.exe`，已实测可跑同一批脚本且结果逐条一致 —— 但**换宿主属于环境变更**，见 DEV-ENV §3.5）。
⚠ **改 `.ps1` 必须保住 UTF-8 BOM**；`.java/.xml/.md` 必须**无 BOM**（判定与修复见 DEV-ENV §6.33 末段）。
⚠ 在 PowerShell 里写 `"$var"`，**变量后紧跟中文/引号/`$` 时必须写 `${var}`**（DEV-ENV §6.44）。

---

## 2. B3 交付了什么（分组索引）

| 组 | 内容 | 关键交付物 |
| --- | --- | --- |
| 1 | S0 技术验证与 DDL 评审 | `notes/{numbering-verification,ddl-review,ddl-scope,attachment-reuse,data-scope-matrix}.md` |
| 2 | DDL 与约束回补 + 编排 | `sql\二开-合同台账.sql`（13 张表）、体检/影响行数报告/回滚、`初始化-全部.sql`、菜单/字典/编号配置 SQL |
| 3 | 主数据档案（客户/供应商/物料） | `ruoyi-ctms` 四张表全链路；`tools\ctms-masterdata-check.ps1` |
| 4 | 合同主体 | `CtmsContract*` + `ContractRules` + `ContractDataScope`；`tools\ctms-contract-check.ps1` |
| 5 | 商务要素、质保与编号 | 编号服务端生成、质保提醒看板、付款比例派生列；`tools\ctms-commercials-check.ps1` |
| 6 | 附件与操作日志 | `CtmsAttachment*` + 对象类型注册表；窄白名单 / ≤20MB / HTTP 413 / 按对象鉴权 / 删除留痕 |
| 7 | 变更历史与迁移工具 | 草案扫描·认领·忽略（`/ctms/migration/*`）、`tools\ctms-migration-prep.ps1`（体检→快照→DDL，体检不过即阻断）、真实 Excel 导出 + 三个行项写端点 |
| 8 | 前端页面 | `views\ctms\{contract,tag,partner,migration}\` + `api\ctms\{contract,tag,attachment,migration}.js`；`notes\frontend-notes.md` |
| 9 | 权限点与数据范围 | `tools\ctms-perm-audit.ps1`（**26 个权限点双向差异为空**）、4.8b 数据范围矩阵、`tools\authz-check.ps1`（30→45 条）、模块边界审计 |
| 10 | 集成检查 | `tools\ctms-e2e-check.ps1`（91 条）、门禁 16/16、空库/回滚演练、验收编号追溯矩阵；`notes\integration-check.md` |

**两轮独立审查抓出的真缺陷（都已修复并复审 pass）** —— 值得记住的"这类问题真的会发生"：

1. **扫描/认领的比较口径不一致**：扫描按 Java 归一决定 `raw_name`，认领候选却用 SQL 精确 `=` 比较，而列排序规则 `utf8mb4_0900_ai_ci` 是 **NO PAD**（首尾空白显著）⇒ 带空白的合同**"认领成功却实绑 0"**，且 `claimed↔pending` **永久往复**。修前旧 jar **37/14** → 修后 **52/0**。
2. **导出跨组契约漂移**：7.4 把 `/ctms/contract/export` 改成 `ExcelUtil` 流式 xlsx，**没回传前端**，前端仍按 JSON `.rows` 解析 ⇒ 点导出报"没有可导出的合同"。
3. **取号判据过宽 + 上限耗尽静默插入**：`isNoTaken` 把"存在比它大的号"当"此号已占用"，配上 1000 次重试上限 ⇒ 计数器落后于库内桶 max 时**白烧 1000 次后照样插入**（相邻号差 **+1001**）。修复 = 精确占用判定 + 库内桶 max 兜底 + **耗尽显式抛异常**。

---

## 3. ⚠ 唯一的已知边界（归档前须知 · T20-F1）

**问题**：10.3 的"**空库一次性安装 → 回滚**"形态与回滚脚本的"**变更前快照**"前提**天然不匹配**。
- 回滚脚本有安全中止守卫（"某张表有数据但快照不存在 ⇒ 中止"），而**空库安装链里没有生成快照这一步**；
- **补上快照后还有第二层、更危险的形态**：**快照拍晚了 ⇒ 回滚 exit 0 却撤不干净**。实测对照：

| 快照生成时机 | 回滚 exit | 核对 ③权限点/④字典/⑤参数/⑥编号配置 | 结论 |
| --- | --- | --- | --- |
| **B3 增量之后** | **0** | **26 / 5 / 1 / 1**（期望全 0） | ❌ **"成功"却撤不干净** |
| B3 之前 | — | 13 张业务表此刻还不存在 ⇒ 无变更前快照可拍 | 该顺序取不到 |

  机理：回滚对存量表做的是「删白名单行 + **从快照整行恢复**」；快照拍晚了就已含 B3 种子行 ⇒ 删掉又装回来 = **净无效**，而业务表照样被 DROP。

**已证（不影响 B3 交付结论）**：空库可一次性初始化（27 行菜单 / 13 张表 / 26 权限点，9 项自检全中）、**13 张新建表 + 外键可被完整撤掉**、上游基线零改动、`rad_oa` 未被触碰、收尾无残留。
**未证**：空库形态下让七项核对全为 0。
**建议**（二选一，**收尾阶段不要临时改** —— 那会动到已被 `tools\b3-sql-drill.ps1`（68/0）验证过的 `sql/` 资产）：
- (a) 给回滚脚本加**显式的"空库/无变更前状态"模式**：只 DROP 13 张表 + 撤 FK/索引/恢复可空，并明确打印"未执行快照反向回填"；
- (b) 把 10.3 的核对口径改为"**B3 业务表与外键/索引/可空收紧全部消失**"，把系统表种子行排除在回滚目标外。

**运维口径（最重要的一条）**：正确顺序是 **基线 → 快照 → 增量 →（必要时）回滚**。
对着**无快照**的 `rad_oa` 跑回滚会**被中止**（安全）；但**有快照而快照拍晚了 ⇒ exit 0 也可能撤不干净**（危险）——
**假成功比明确失败更难发现**。详见 `notes\integration-check.md` **§8.3 / §8.5 / §8.6**。

---

## 3.1 2026-10-05 缺陷修复：流程条件字段与「关联表单」错位（数据 + 前端）

| 缺陷 | 一句话根因 | 记录 |
| --- | --- | --- |
| **流程条件字段与「关联表单」错位**（V-8 报 `contractType`/`amount` 不存在，界面却显示 `field101`） | 动态表单**保存 = 停用旧版本行 + 插一条新版本行（换 id）**；`TemplateDynamicFormServiceImpl#updateTemplateDynamicForm` 第 ③ 步 `repointFormId` **只改 `t_template.form_id`、不改 `t_flow_simple.content`** ⇒ 模板跟着新版走、**流程留在旧版**，而设计器字段清单取的是流程内容里的 `formId` | `doc\缺陷-流程条件字段与关联表单错位.md` |

**已修**：
1. 对齐数据：流程 `testCondition.content`（`formId` → 当前生效版本；条件 `contractType/amount` → `field101`）+ 模板 `08C77DC1…` 的 `form_id`（校验 `issues=0`）；
2. 条件文字显示口径收口到新纯函数 `src/views/workflow/simple-flow/conditionText.js`（**只显示字段 label、不显示 vModel**；label 查不到回退 vModel；值按选项名显示），画布 / 抽屉"当前条件读作" / 节点摘要**三处共用一份**；
3. 表单字段名 `field101` 的 label 由控件类型名「单选框组」改为业务名「合同类型」（走后端 `PUT /template/dynamic/form`，按版本化语义生成新版本行）；
4. **枚举字段的值控件改为选择器**：单选组 / 下拉类字段的"值"从自由输入框改为**下拉选择**，候选 = 选项 **label**；
   其它字段仍是输入框；比较符为「为空/不为空」时置灰；未关联表单时回退输入框（**绝不把值控件变没**）；
5. **参与人"选人"改造：尝试后已回退** —— 目标是把「指定人员」从"手填 user_id"改成组织树选人，但：
   - 复用 `components/form/FormUserSelect.vue` 失败（它**没有 `value` prop**、内部 `selectedUsers` 同时被当 id 数组与对象数组用；我为它补 `value` 后**下游取不到人、弹窗里没人可选**）；
   - ⇒ **已完整还原该共享组件**（当前 `git status` 对它为空）；`FlowDesigner.vue` 也整体还原后只重放"显示层"改动；
   - ⇒ **当前「指定人员 / 角色」仍是原来的手填输入框**，功能正常；
   - 留下的可复用资产：`assigneeMapping.js` + 12 条用例（**已测但未接入界面**）。
   - ⚠ 该弹窗触发区是 `<div @click>`，**本 Agent 的自动化点不中** ⇒ 这类改造**必须先约定由人工验证**，详见 `doc/缺陷-流程条件字段与关联表单错位.md` §6.4.2 / §6.4.3。
6. **收紧条件字段下拉**：去掉 `allow-create`（此前**手打字段名可绕过整个 V-8**），只能从表单提取的字段里选。

> ⚠ **条件里的值必须是选项 label，不是选项 value**（写错运行期恒不成立）。
> 真源：`BizFormServiceImpl#convertValueToLabel`（行 296-360 分发 / 446-455 单选）——
> 前端提交**原始 value**（`field101 = 1`），后端**翻译成 label**（`经营`）之后才 `runtimeService.setVariables(...)` 写进流程变量。

**实测**：静态审计 10/10 ✅；前端单测 **75/75** ✅；生产构建 ✅；流程回归 `testCondition` 5/5 ✅；页面实测正常（画布显示 `当 合同类型 = 经营 时`）。

**未做（待决策）**：条件字段下拉的 `allow-create`（**手打字段名可绕过 V-8**）；"模板 form_id ↔ 流程 formId"一致性检查；PRD V-8 明文要求但**代码里从未实现**的"表单保存时反向校验"。
**同族存量**（普查发现，未动）：`testSerial/testSerial2/testCombo/testParallel/testParallelAny` 指向**已停用**的 `DFCD2DC3`；`tpl_BRIDGEAC` 流程与 4 个模板指向**已软删**的表单行。

---

## 3.2 ⚠ 2026-10-05 实测：**§8 的"门禁 16/16 全绿"在当前环境已不成立**

> **✅ 2026-10-06 更新（B4 轮）：本条已澄清并关闭。** 当时"跑不满"的那一项是 `tools\flow-regression.ps1`（`testParallel`/`testCombo` 的并行会审 ALL 合流卡死）。B4 轮里该脚本**实跑全绿**（t13 门禁全表 + t18 终验均复跑通过），根因与修复见 `doc/缺陷-并行会审全部完成不合流.md` 与 `openspec/changes/oa-purchase-sales-stock/notes/` 下 t15/t16 的交付记录。⇒ **§8 的现行门禁全表以文末 §8.1（2026-10-06）为准。**

`tools\flow-regression.ps1` 整表**串行复跑**（本轮）：**通过 15 / 失败 5**。

| 失败项 | 形态 | 是否本轮造成 |
| --- | --- | --- |
| `testParallel · 全部会审完成后流程办结` | 4 个会审任务只批了 2 个、仍剩 2 个活动任务，多实例 **ALL 合流未触发**（卡死实例 `1155940`） | ❌ 否 —— 该流程内容本轮**一字未改**，`ACT_RE_PROCDEF` 仍是 `version 2 / DEPLOYMENT 1092501`（早于本轮） |
| `testCombo · 并行之后仍经过「法务」固定必经节点` + 办结类 3 条 | 同一 ALL 合流问题（`testCombo` 也含并行会审） | ❌ 否 —— 同上，内容零改动 |

> 即脚本头注释里写的"rad_oa schema 下并行会审全部完成不合流（流程卡死）"**又出现了**。
> 本轮**未修**（超出"小范围 bug 修复"范围）。**接手时不要相信"门禁全绿"，先跑一遍 `flow-regression`。**

> ✅ **2026-10-05 第三轮实测更新**：`tools\flow-regression.ps1` 整表复跑 **20 通过 / 0 失败**
> （含这里的 `testParallel` ALL 合流与 `testCombo`），即上面两条既有失败**本轮未复现**
> （证据：`.cache\flow-regression-third-round.txt`）。本轮改动是**纯前端**（流程定义与引擎侧一行未动），
> 所以**不能说"是本次修好的"** —— 更可能是运行环境/库内实例状态变化所致。
> 结论仍然成立：**跑一遍再说，别照抄上一轮的红/绿**。

---

## 3.3 ✅ 2026-10-05（第三轮）：参与人「选人 / 选角色」改造 —— **方案 A 已实现并验证**

> 上一轮（同日）**失败并回退**的完整记录在 §3.3.1 与 `doc\缺陷-流程条件字段与关联表单错位.md` §6.4.2 / §6.4.3，
> **动手前先读那两处**，四个坑都别再踩。

**目标（用户原话）**：「选择人员框、应该弹出系统人员树，选择一名同事」——
即节点配置里「指定人员」不再是"手填 user_id"，而是**组织树选人**；「角色」不再是"手填 role_id"，而是**角色列表选择**。

#### 3.3.0 最终做法（方案 A：自绘 chip + 受控弹窗，父组件状态是唯一真源）

| 位置 | 实现 |
| --- | --- |
| 「指定人员」 | `FlowDesigner.vue` 内**自绘 chip**（姓名 + `×` 的 `<el-button>`）+ **`<el-button>选择人员`** 触发受控弹窗 `org/UserAllSelect`（`:open.sync` / `:selected-users` / `@confimUser`） |
| 写入口径 | 确认后 `onUserPicked` **只写 `user_id`**：`assignee.userIds = toUserIds(确认回来的对象数组)` |
| 回显口径 | `toUserObjects(ids, allUsers)` 按 id 反查姓名；**查不到不隐藏**（显示 `id（用户不存在）`） |
| 「角色」 | `el-select multiple filterable collapse-tags`，候选来自 `/system/role/list`，**存 `role_id`** |
| 画布摘要 | 节点卡片显示 `指定人员（张伟、李娜）` / `角色（财务）`，不用点开抽屉就能核对 |
| 共享组件 | **一行未改**：`form/FormUserSelect.vue`、`org/UserAllSelect/index.vue` 的 `git status` 仍为空 |

**三个细节都是上轮踩坑的直接对策**

1. **跨节点写入守卫**：`openUserPicker()` 把**当前节点对象引用**记进 `pickerTargetNode`，`onUserPicked` 只写回它
   （上轮是"切节点时把旧节点的人写到新节点上"，见 §6.4.3 第 2 条）；**关闭弹窗不写任何东西**。
2. **`assignee` 归一化走 `$set`**：旧流程内容可能缺 `userIds`/`roleIds`（甚至整个 `assignee`），
   流程加载后 `normalizeAssignees()` 统一补齐（`assigneePatch()` 纯函数算补丁 + 调用方 `$set`）；
   否则 Vue2 新增属性不响应式（`audit-undeclared-writes` 门禁盯的就是这类）。
3. **用户清单两段式加载**：`/system/user/getUsers` **不含超管**（实测只回 4 人）⇒ 清单里没有的 id
   再按 `GET /system/user/{userId}` 逐个补查，查不到**显式提示**（不做空 catch）。
   ⚠ **触发时机**：`getUsers` 常比流程详情先返回，那一刻 `flow.nodes` 还是空的（算出来"没有缺人"）⇒
   补查必须在 `normalizeAssignees()` **之后再跑一次**。本轮先漏了这条，实测表现为超管 chip 显示 `superAdmin（用户不存在）`。

#### 3.3.1 上一轮（同日、已回退）的四个坑 —— **仍然是"别重犯"清单**

1. **不要为单点需求改共享组件**：`FormUserSelect` 没有 `value` prop（`v-model` 是空操作），
   其 `selectedUsers` 由 `checkedUsers` 填充，而下游 `UserAllSelect` 把 `checkedUsers` 当 **id 数组**，
   部门/委托页却传**对象数组** —— 两种语义混用。补 `value` 后**下游取不到人、弹窗里没人可选**（用户实报）。
2. **跨节点写入竞态**：子组件在挂载/属性同步/切节点时都会回发 `input`，当"用户选择"写回就会串节点（实测把 n2 的 userIds 清空）。
3. **不要用"按行号批量删除"改大文件**（本次因此删坏过 `created()` 与 `assigneeSources`，只能整体还原重放）。
4. **`<div @click>` 触发区点不中**：本桥的 `browser_click` 只认快照索引 ⇒ 旧版「选人」入口无法自动化验证；
   方案 A 换成 `<el-button>` 后**触发与确认都能自动化**（这是选 A 的关键收益）。

#### 3.3.2 验收证据（2026-10-05 实测，逐条可复现）

| # | 口径 | 证据 |
| --- | --- | --- |
| 1 | 点「选择人员」→ **弹窗出现组织树** | ✅ 自动化点中 `<el-button>`：弹窗「用户选择」+ 左树「组织机构 / 测试科技有限公司」+ 该节点已选人员出现在「已选择用户 (2人)」 |
| 2 | 选一位同事 → 节点出现**姓名 chip**；`×` 能移除 | ⚠ **半自动**：chip（张伟 / 李娜 / 超级管理员）与 `×` 移除**已自动化验证**；"在组织树里点某个部门"这一步**不可自动化**（见 §3.3.3 第 1 条），**需人工点一次** |
| 3 | 保存后**落库是 `user_id`** | ✅ `×` 移除李娜 → 保存草稿 → 回读 `t_flow_simple.content`：`"userIds":["58FE8668233B422FB69EE575F5F402A5"]`（张伟的 id，**不是姓名**）；刷新页面 chip 仍为「张伟」 |
| 4 | 发布校验 `issues=0` | ✅ `POST /workflow/simple-flow/validate {"id":…}` → `issues=0` |
| 5 | 弹窗确认 → 写回父组件（不是回显） | ✅ 弹窗内「清空」→「确定」→ 抽屉 chip **消失**、画布摘要从「指定人员（张伟）」变回「指定人员」 |
| 6 | 角色侧 | ✅ 节点存的是 `role_id`：`roleIds:["31ECC4F05C6449AEB39529C099504FB0"]`（财务）；下拉**能展开并列出 12 个角色**（超级管理员 … 资讯管理员） |
| 7 | 静态门禁 | ✅ `node tools/audit/run-all.js` **10/10**（0 失败 / 0 未自证）；`node tests/run.js` **84/84**（新增 9 条）；`npm run build:prod` 成功（8 条 warning 全是既有的） |
| 8 | 未污染既有数据 | ✅ 动手前后 14 条流程的 `content` **MD5 逐条不变**；验证用临时流程（`pickerVerifyTemp1005`）已删除，总数回到 **14** |
| 9 | 流程回归 | ✅ `tools\flow-regression.ps1` **20 通过 / 0 失败**（含上轮记录的 `testParallel` ALL 合流 —— 本轮实测**未复现**该既有失败，见 §3.2 的更新说明） |

#### 3.3.3 ⚠ 无法自动化的两步（**工具边界，不是代码问题；别再盲试**）

1. **在共享弹窗的组织树里点一个部门**：`el-tree` 节点是 `.el-tree-node`（`role=treeitem`、`tabindex=-1`），
   **不进快照索引**；`Tab` 在本桥**不会真的给页面元素焦点**（`browser_get_text` 查 `:focus` 恒不匹配）⇒ 点不中。
   这一步**只在超管账号下必需**：超管 `user.dept.parentId === "0"` ⇒ 共享组件算出的初始 `deptId="0"` ⇒
   **打开弹窗时「用户列表」是空的**，必须先点一个部门（如「研发部」）才会列人。
   **人工确认步骤（约 1 分钟）**：进任一流程设计页 → 点一个审批节点 → 点「选择人员」→ 左侧组织树点「研发部」→
   勾选「王强」→ 点「确定」→ 抽屉出现「王强 ×」→「保存草稿」→ 回读 `content` 应为
   `userIds:["85122F49DC8A4626A1B870FAD25C8CF8"]`。
2. **在 `el-select` 下拉里点一个角色**：选项是**无 role 的 `<li>`**（`.el-select-dropdown__item`）⇒ 同样点不中
   （下拉**能展开、12 个角色都可见**，只是不能点）。**人工确认步骤**：把某节点参与人来源切到「角色」→
   选「财务」→ 保存 → 回读 `roleIds` 应为 `31ECC4F05C6449AEB39529C099504FB0`。
   > 这条边界的通用结论已写进 **DEV-ENV §6.53**（能点中什么 / 点不中什么 + 对策）。

#### 3.3.4 现成资产与建议的后续小变更

- 复用资产：`src/views/workflow/simple-flow/assigneeMapping.js`
  （`toUserIds / toRoleIds / toUserObjects / toRoleObjects / cleanIds / missingUserIds / missingRoleIds / assigneePatch / summarizeNames`）
  + `tests/assignee-mapping.test.js`（**21 条**用例）。
- **建议的后续小变更（已由 §3.7 从另一条路解决，见那里；动共享面前先问用户）**：共享选人弹窗对"部门就是公司根"的账号（超管）
  初始 `deptId="0"` ⇒ 用户列表为空。① 本组件给 `UserAllSelect` 传 `select-user-scope="dept"`
  （`views/workflow/flow-form/index.vue` 的同类用法默认就是 `dept`）；② 治本：共享组件里把
  `initDeptId` 的 `parentId === '0'` 归一到自身 `deptId`。

---

## 3.4 ✅ 2026-10-05（第四轮）：「多人方式 = 单人」时，指定人员只能选 1 个人

**用户原话**：「多人方式选择单人后、指定人员应该只能选取一个人」。

**修复前**：审批节点「多人方式」= `单人`，但点「选择人员」弹出的共享弹窗**能勾任意多个人**，
确认后节点上挂多个姓名 chip。根因是 `FlowDesigner.vue` 里把弹窗写死成 `type="multiple"`
（共享组件 `org/UserAllSelect` 的 `type` 决定 `el-checkbox-group` 的 `:max`：`single`=1 / `multiple`=999999），
且 `onUserPicked` / `normalizeAssignees` **都不看 `multiMode`**。

**危害不止界面**：编译期 `SimpleFlowCompiler#renderUserTask` 按**人数**分支 ——
1 人 ⇒ `flowable:assignee`（直接派给他）；>1 人 ⇒ `flowable:candidateUsers`（**候选人抢单**，
`ASSIGNEE_` 恒为 NULL）。即"配置写单人、跑起来却是多人候选"，与界面上选的语义不一致。

**修法（前端 2 文件，后端与数据零改动）**：

| 位置 | 改动 |
| --- | --- |
| `assigneeMapping.js` | 新增纯函数 `singleModeUserIds(multiMode, ids)`：先 `cleanIds`，单人 / 未设置 ⇒ 只留第 1 个；`AND/OR/SEQ` ⇒ 原样 |
| 弹窗 `:type` | `:type="userPickerType"` —— 取**打开弹窗时锁定的那个节点**的多人方式（单人 ⇒ `single`，共享组件自动 `:max=1` 并隐藏「全选 / 清空」） |
| `onUserPicked` | 写回前过 `singleModeUserIds` —— **不依赖界面**，旧数据/构造请求也挡得住 |
| `onMultiModeChange` | 接在「多人方式」`@change` 上：多选→单人**立刻**只留第 1 个，并明确提示"保留了谁、移除了谁" |
| `normalizeAssignees` | `SINGLE` 却存了多人（历史脏数据）⇒ **内存里** `$set` 收敛成 1 个（不静默改库） |
| `pickerSelectedUsers` | 单人节点只带第 1 个人进弹窗（避免"勾着 3 个、确认后只剩 1 个"） |
| 界面提示 | 「指定人员」「多人方式」两处各加一行随模式变化的说明 |

**实测（逐条可复现）**：前端单测 **90/90**（新增 6 条）✅；静态审计 **10/10** ✅；
`npm run build:prod` **exit 0** ✅；`flow-regression` **20/20** ✅；
页面实测：单人节点弹窗 **「已选择用户 (1人)」且无**「全选 / 清空」按钮；切到**会签**后弹窗**出现**「全选 / 取消全选 / 清空」⇒ `:type` 确实随节点切换 ✅；
全程未保存，回读 `t_flow_simple` 该行 `MD5(content)` 与实验前**逐字相同** ✅。

> ⚠ **仍有一处需人工点一次**：**在共享弹窗里勾第 2 个人**这一步本桥点不中
> （`.user-card` 复选框与组织树节点都不进快照索引；超管账号初始 `deptId="0"` ⇒ 用户列表为空，
> 必须先点组织树部门）。人工步骤与"多人模式对照"见 `doc\缺陷-单人节点指定人员可多选.md` **§5**。

---

## 3.5 ✅ 2026-10-05（第四轮追加）：**生成「合同审批（含用印）」动态表单内容**

**用户原话**：「根据开发文档和原型图构想，帮我按照内容生成合同类审批单表单内容」。

**做法**：按 PRD **§4.2**（电子填写页分组，原型 `doc\参考文档\合同审批表单填写页面.jpg`）做骨架，
把 **§7.8**（纸质栏目 ↔ 系统字段映射，**验收依据**）要求打印的字段**全部补齐**，控件只用系统**已有**的。

| 产物 | 说明 |
| --- | --- |
| `tools/build-contract-approval-form.js` | 生成器（可重跑；写文件前先自检 title / field101 必填 / jointDepts / amount / vModel 重复） |
| `tools/contract-approval-form-content.json` | 生成的表单 `content`（28 项 = 20 个有值字段 + 8 个纯排版控件） |
| `doc/2.0/合同审批表单字段构成.md` | 字段构成表 + 与原型差异的**条款依据** + 用法 + 取证 |
| 系统内动态表单 | `id=7A541B9568654CD3948FD2FB237A0860`，名称 **合同审批（含用印）**（enable=1 / del=0） |

**两个不能想当然改的点**（详见字段构成文档 §4）：
① **「合同类型」沿用 `field101`**（不改名 `contractType`）—— 已发布流程 `testCondition` 的条件就是 `field101 = 经营/经济`，改名会让 V-8 拦发布；
② **`amount` 设为必填** —— §6.3.1 规则①要求条件字段必须已必填；金额必填后"金额分级"分支才立得住（这正是缺陷文档 §7-A 的待定项）。

**实测**：生成器自检 ✅；前端 `extractFormFields` 20 个有值字段 / 必填 8 个 / `multi=[jointDepts]` ✅；
`conditionableFields` 正确排除关联审批 ✅；入库 content 与文件**逐字节相同** ✅；
表单构建器可视化打开 28 控件 + 7 分组标题全部正确 ✅；
**既有 16 行表单的 enable/del/长度/MD5 逐行未变、`t_template` 12 行未变、`t_flow_simple` 未变** ✅；
前端单测 **90/90**、静态审计 **10/10** 复跑无回归 ✅。

**未做（等你确认）**：**建模板 + 绑流程**。当前库里**没有真正的合同审批流程**（只有 `testCondition` 等测试流程），
建模板会污染 `flow-regression` 的夹具集，所以留到业务确认绑哪个流程定义后再做（做法见字段构成文档 §5）。

---

## 3.6 ✅ 2026-10-05（第四轮追加）：「新建流程」的**流程标识**改成"系统建议 + 实时校验"（方案 B）

**用户原话**：「新建流程中的流程表示需要填什么，是否可以优化」→ 选定 **方案 B**。

**问题**：`def_key` 是写进 BPMN 的 **Flowable 流程定义 key**（格式 `[A-Za-z_][A-Za-z0-9_-]*`、**全局唯一**、发布后不可改），
而新建弹窗要求用户**手敲**、且**不查重** —— 敲错要到提交后看后端报错。
实库证据：`def_key='authzPublishGuard'` 曾被 **31 条**流程共用（版本高到 v62）。

**做法（纯前端，2 个文件 + 1 个新纯函数模块）**：

| 位置 | 改动 |
| --- | --- |
| `flowKey.js`（新，纯函数） | `suggestFlowKey(name)`：业务词表（合同→ht、审批→sp…）+ 单字表 + **确定性短码兜底**；`validateFlowKey`（与后端 V-0 同口径）；`suggestAlternativeKey`（撞名 → `_2`） |
| `index.vue` · 模板 | 「流程标识/分类」收进**默认折叠**的「高级选项」；标识输入框带「重新生成」+ 三态提示（检查中/可用/已被占用+一键改用备选）+ 规则说明 |
| `index.vue` · 逻辑 | 名称 → 标识**自动带出**（用户改过就不覆盖）；300ms 防抖查重（`GET list?defKey=`）；**过期响应丢弃**；`submitAdd` 前本地拦格式与已知撞名；`beforeDestroy` 清防抖定时器 |
| `FlowDesigner.vue` | 顶部那格标识加**实时格式提示**（原来零校验）+ 保存草稿前本地拦一次 |

**生成规则**（用户确认）：词表最长优先 → 单字兜底 → 标点忽略 → 未命中汉字补短码 →
`合同审批（含用印）` 得 `htspyyh`、`合同审批2024` 得 `htsp_2024`、`Contract Approval` 得 `contractapproval`。

**实测**（浏览器逐步走完，逐条可复现）：
弹窗默认只显示「流程名称」（技术细节收起）✅；填名 → 展开 → 自动得 `htspyyhbt4rwrv` 且提示"可用" ✅；
手工改成已占用的标识 → 提示「已被占用 + 改用 xxx_2」✅；点改用 → 标识切换、提示回到"可用" ✅；
设计器里把标识改成中文 → 顶栏立刻「标识不可用」、点保存被本地拦下并给出中文原因（**不再需要等服务端**）✅；
创建 → 列表出现 → 设计器回显标识 → 保存草稿成功 ✅；验证用流程**已删除**、列表回到空（未留残留）✅。
门禁：前端单测 **110/110**（新增 26 条）、静态审计 **10/10**、`npm run build:prod` exit 0 ✅。

> ⚠ **踩到并已修的一个真缺陷**：点「改用备选」后，在重新查重的窗口期里界面挂着
> 「`xxx_2` 已被占用，改用 `xxx_2`」—— 原因是发起查重时**没清掉上一次的结论**。
> 修法：`scheduleKeyCheck()` 先清 `keyTaken`/`alternativeKey` 并置 `checkingKey`。
> 结论（DEV-ENV 风格的教训）：**异步校验的"上一次结论"必须在提问时就作废**，否则会出现自相矛盾的提示。

> 📌 **顺带发现（与本次改动无关，但你正在做）**：`t_flow_simple` 里**所有 53 条流程**的 `del_flag` 都是 `1`
> （19:52:33–19:52:52 之间被你逐条删除）⇒ 流程列表现在是**空**的。
> 已部署到 Flowable 的流程定义（`ACT_RE_PROCDEF`）**仍在**，所以 `flow-regression` 的 20 条用例不受影响
> （`tools\flow-regression.ps1` 实测仍 20/20）。**若要用「简化流程设计器」继续做合同审批，需要重新配一条流程**。
> → 你随后自建了一条：`id=1488b3e8c4f542bf8b40bc3ab16601a8 / def_key=htsp / 流程名称=合同审批`（草稿）。

### 3.6.1 ⚠ 2026-10-05：用户报「高级选项点不开 + 创建失败：流程 key 不能为空」——**根因是前端 watcher 假死，不是代码问题**

**结论：代码是对的，运行中的开发服务器送的是过期编译产物。**

判定证据（三方对照）：
1. `npm run build:prod`（webpack 重新读盘）**包含**新弹窗与自动生成逻辑 ⇒ 源码没问题；
2. 开发服务器送出的 bundle **不含**新弹窗文案（`高级选项（流程标识 / 分类）`）与 `还没有流程` 等页面文案；
3. 用户当时遇到的**正是旧代码的两个症状**：旧弹窗没有「高级选项」（点不开），
   旧逻辑不自动生成标识且后端 V-0 要求 `流程 key 不能为空`（报错原文一致）。

**处置（已做）**：**重启前端 `npm run dev`**（旧进程 14728/24188）+ 页面硬刷新。
重启后新会话逐步复验：
「高级选项」能展开 ✅；名称 → 标识自动生成（`htsp`）✅；
**不展开高级选项直接点「创建并设计」也成功**（用户报的那条错误路径不再复现）✅。
另：用户自己的流程 `合同审批 / htsp` 已成功创建 ⇒ 自动生成在新代码下确实生效。

**同轮修掉的一个真缺陷（与 watcher 无关）**：兜底短码会产出带 `-` 的标识
（`复现验证-无需高级选项` → `flow_-4qaxj5`，因为 FNV-1a 对负数 `toString(36)` 带 `-`）。
修法：短码滤掉非字母数字；且**未命中汉字时整串退化为 `flow_` + 短码**（不再拼"半截缩写 + 半截乱码"）。
用例补齐到 **111/111**。

> 教训已写进 **DEV-ENV §6 第 55 条**：交付前端改动后，必须"改一行可见文案 → 硬刷新看到变化"确认 watcher 活着；
> **`npm run build:prod` 通过 ≠ 开发服务器在服务新代码**。别再为了一个"看起来还在的 bug"去改已经正确的代码。

---

## 3.7 ✅ 2026-10-05（第四轮追加）：「指定人员」弹窗现在**能选到超管账号**（方便测试）

**用户原话**：「指定人员选择功能、让我可以选定超管账号、方便测试」。

**为什么原来选不到（两条叠加）**：
1. 平台两条选人接口的 SQL 都**硬排除超管** —— `SysUserMapper.xml` 的 `selectAllUser`（`:182`）
   与 `selectDeptUsers`（`:189`）都写着 `user_id != 'superAdmin'`；
2. 超管挂在**公司根节点**（`sys_dept.parent_id = '0'`），而共享弹窗在 `corp` 模式下把初始 `deptId`
   取成 `parentId='0'` ⇒ 打开时「用户列表」本来就是空的（§3.3.3 已记录）。

**做法（纯前端，不动共享 SQL）**：
- 共享弹窗 `org/UserAllSelect` 新增 **`pinnedUsers`** prop：传入的对象数组**固定在「用户列表」最前面**、
  并预先并入 `allUserMap`（否则勾上之后右侧「已选择用户」查不到对象、会凭空消失）。
  带「常驻」小标签，避免被当成脏数据。样板：日程/知识库授权/转办等入口**不受影响**（默认空数组）。
- `FlowDesigner.vue` 新增 **`assigneePinnedUserIds = ['superAdmin']`**，进页面与流程加载后
  各调一次 `loadPinnedUsers()`：走 **`GET /system/user/{userId}`**（这条不排除超管）取回后交给弹窗。
  已在抽屉提示里写明"列表最前面固定有超级管理员（常驻）"。

**实测**：抽屉提示 ✅；弹窗「用户列表」= **`超级管理员 常驻 测试科技有限公司`** ✅；
用一条**临时流程**（`userIds:["superAdmin"]`，用完已删）验证回显链路：
画布摘要 `指定人员（超级管理员）` ✅、抽屉 chip `超级管理员 ×` ✅、弹窗**「已选择用户 (1人)」** ✅
⇒ 证明"弹窗里勾的人 → 父组件 id → 按 id 反查姓名"整条链对超级管理员成立。
门禁：前端单测 **111/111**、静态审计 **10/10**、`npm run build:prod` exit 0、后端**零改动**（**不需要重打包/重启后端**）。

> ⚠ 仍然点不中：`.user-card` 里的勾选框不进快照（DEV-ENV §6.53 第 5 条），
> 所以"在弹窗里勾一下超管"这一步**要你手动点**；但列表与回显都已验证，勾选后的落库口径同 §3.3.2。

> 📌 **不再需要** §3.3.4 建议的共享弹窗 `deptId` 小变更（"改为 dept 模式"）：即便列表为空，
> 常驻候选人也在列表里；真要修那个空列表，属于另一件独立的共享组件变更，仍**动共享面前先问用户**。

---

## 3.8 ✅ 2026-10-05（第五轮）：发布校验报的问题与**眼前画布对不上**（"只有 1 个审批节点"误报）

**用户原话**：①「阻断项提示需要直接提示那个审批阶段存在问题」；②「提示项我已经加了多个审批节点、检查为什么还会有提示」。

**根因（一条同时解释两个"说不通"）**：`handlePublish()` 把 `{id}` 交给 `/validate`，
而**后端是按 id 读库里 `content` 校验的** —— 看不到画布上还没保存的改动。
所以"发布"校验的是**上一次保存的旧内容**：
- `[V-2] 节点 n_first` 里的 `n_first` 是旧内容里的节点（那时没选人、也还叫「审批节点」），用户随后改名选人的改动**只在内存**；
- `[V-1] 只有 1 个审批节点` 同理：旧内容 `approveCount=1`，后加的 3 个节点还没落盘。

**实测反证**：库里 content 的 approve 计数 = **4**（顶层 2 + 分支内 2），弹窗说 1 ⇒ 两边看的是两份内容。
顺手核过 `SimpleFlowValidator`：V-1 的 `approveCount` 是 `+=` 累加、顶层与分支内都会数；V-2 文案本身带节点名
——**校验器没问题，是喂给它的内容过期**（这一点是本轮最重要的结论，别再往校验器里找 bug）。

**修法（纯前端 1 文件）**：
1. `handlePublish` = **先存当前画布 → 再校验 → 再发布**（抽出 `saveDraftQuietly()`；保存失败就中止，不拿旧内容校验）；
2. `showIssues` 给阻断项补**画布定位** `（画布上叫「XXX」）`（新增 `nodeNameById()` 递归反查，含分支内节点）；
   查不到时显式说"当前画布上找不到这个节点，请重新保存后再校验" —— 那正是内容不同步的信号。

> 顺带闭合一个隐性风险：原来"改了画布没保存就点发布"，**部署到引擎的也是旧内容**（用户以为改完就发布了）。

**实测**：夹具（4 个审批节点、只有中间那个不选人）`POST /validate` **只有 1 条** V-2（带节点名）、**V-1 误报消失** ✅；
点「发布」→ 自动保存 → 校验（仅 V-1 提示）→ 弹"存在提示项，是否继续发布？"→ 取消 ✅；
前端单测 **111/111**、静态审计 **10/10**、生产构建 exit 0、**后端零改动** ✅；夹具已删除、未留残留 ✅。
详见 `doc\缺陷-流程条件字段与关联表单错位.md` **§11**。

**留下的改进点（未做）**：校验接口只吃 `id`（读库）；`showIssues` 只是念出节点名、**不能点击跳转**到该节点。

---

## 3.9 ✅❌ 2026-10-05（第六轮）：合同审批「能发起 + 能走完」+ 三个缺陷修复 + 浏览器 E2E 套件

**用户诉求**：① 表单配置阶段可选模板、发布后自动列入可启用；② 拟稿阶段屏蔽右侧审批意见；③ 签订公司控件改单选；
④ 审批/办理阶段按表单权限让处理人可改控件；另要求清理系统内测试流程（只留合同审批相关）。

本轮交付了其中**能闭环的部分**，并把两条决策性问题留在 §3.9.8。**核心结论：这条链路此前有两个独立的坑，
叠加起来就是"流程做好了却发不起来、发起来了却批不下去"。**

### 3.9.1 「新启流程」里根本没有合同审批 —— 根因是**「流程」≠「发起入口」**（✅ 已修）

- **发起页的数据源是 `t_template`，与 `t_flow_simple` 没有任何关联**：
  `newstart/index.vue` → `GET /template/template/newStart` → `TemplateMapper#selectNewStartTemplateList`：
  `where del_flag='0' and enable_flag='1'`（再过一遍可发起范围），**再无其它条件**。
- **独立入口是死角**：「流程设计（简化版）」是**独立页面**，发布只写 `t_flow_simple` + 部署 Flowable；
  `SimpleFlowServiceImpl.publish:247-252` 在没传 `templateId` 时按 `simple_flow_id` **反查模板**，
  独立新建的流程查不到 ⇒ **`if` 直接跳过绑定：不报错、不提示，界面照样显示"发布成功"**
  （源码注释承诺的"不留下静默悬空态"只在**有模板参与**的路径上成立）。
- 2.0 B1 的正路是**内嵌**（模板配置 → 「流程设计」页签 → `defKey = tpl_ + 模板ID前8位` → 发布回写
  `def_key`/`simple_flow_id`/`flow_mode`）。而**产品没有任何"把已有独立流程绑到模板"的入口**。
- **修法**：新增 `tools\bind-flow-to-template.js`（幂等 + 5 步自检），走产品自己的新增接口
  （`insertTemplate` 支持这三列）构造出 `saveFlowBinding` 本该写出的同一状态。
- **实测**：只新增 **1 行 `t_template`**（`submit_scope`/`flow_admin`/`print_template` 等 **8 张子表 0 行**）；
  发起页出现「合同审批」卡片、点开即「合同审批（含用印）」表单；
  `GET /workflow/simple-flow/by-template/{id}` 返回**原来那条 htsp 流程**（⇒ 流程设计页签不会另建 `tpl_` 草稿）。
- 细节与重跑命令：`doc\2.0\合同审批表单字段构成.md` **§5.1**。

### 3.9.2 提交流程报 `Error while evaluating expression: ${field101 == '经营'}`（✅ 已修）

- **根因：条件比较值口径 ≠ 表单提交口径**。实测三条证据说明**表单统一存码值**：
  `ACT_HI_VARINST` 与 `t_workflow_form.val_data` 里 `field101 = 1`(integer)、`contractCategory="purchase"`、
  `jointDepts="business"`；而表单选项是 `{label:'经营', value:1}`。
- 错在**作者侧**：`ConditionEditor.vue` 的候选值选择器写成 `:value="String(o.label)"` —— **把 label 存进了条件**。
  编译器本身没问题：`SimpleFlowCompiler#literal()` **只对 String 加引号、对 Number/Boolean 原样输出**，
  所以条件里存 `1` 就会生成 `${field101 == 1}`。
- 同时**纠正两处把口径写反的注释**：`conditionText.js` 号称的"口径真源"、`formSchema.js` 的
  "提交落库写的是 label" —— 真实是 `convertValueToLabel()` 的结果只用于**表单落库与正文书签**，
  而 `setFormDataVariable()` 取的是 `commonForm.setValData(sourceValDataJson)`（**转换前的快照**）。
- **修法**：`ConditionEditor.vue` 改存 `o.value`（下拉仍显示中文名）+ 更正 UI 提示与注释；
  存量条件用一次性脚本走 `/draft`+`/publish` 迁移（`'经营'→1`、`'经济'→2`）→ **htsp v4**。
- **实测**：编译产物由 `${field101 == '经营'}` 变为 `${field101 == 1}`；E2E 用例断言**首个待审批节点 = 经发部审批**（即条件命中正确分支）。
- ⚠ **门禁为什么没抓到**：`tools\flow-regression.ps1` 是**手工喂变量** `-Variables @{ field101 = '经营' }`，
  正好与错误的条件口径对上 ⇒ **假绿**。同类假绿见 3.9.3。

### 3.9.3 待办标题为空（`t_workflow_todo.title` 118/118 全 NULL）（✅ 已修）

- **数据定性**：`t_workflow_todo` 118/118 NULL、`t_workflow_done` 444/446、`t_workflow_form` 7/14
  —— **有值的那些全是 PowerShell 脚本显式传了 title**。
- **抓包定位**（拦截 `/biz/form/save` 后 abort，不产生数据）：提交 payload 的 `formData` 只有
  `taskId/templateId/procInstId/formData` 四个键，**`title` 键被 `JSON.stringify` 整个丢掉**
  ⇒ 前端算 title 时拿到 `undefined`（不是空串）。
- **根因**：`flow-form/index.vue#getTitle` 用 `__config__.label.indexOf('标题')` 认标题字段，
  而表单**第 0 个字段是分组控件 `design-section`，它的 label 字面就是「分组标题」**；
  `.some()` 在第一个命中处就 `return true`，于是 `title = valData[field.__vModel__] = valData[undefined]`。
  同一根因也解释了 URL 里那个空参数 `?title&`。
- **修法**：① 优先精确匹配 `__vModel__ === 'title'`；② 兜底按 label 找时**强制要求 `__vModel__` 存在**
  （纯排版控件没有 vModel，被排除）；③ 空值返回 `''` 而不是 `undefined`。全仓同类启发式只此一处。
- **实测**：payload 出现 `title:"E2E标题探针"`；E2E 用例断言待办行与「我发起的」标题 = 夹具名。
- ⚠ **存量不会自愈**：已产生的 118 条待办 / 444 条已办标题仍是 NULL，只能回填（从
  `t_workflow_form.form_data` 的 `valData.title`）或删除。

### 3.9.4 拟稿阶段屏蔽右侧审批意见（✅ 已修）

- **两个不能踩的约束**：① 右栏同时装着**提交/签名按钮**，整栏屏蔽就没法提交；
  ② `completeBtn` 在 `requiredCmt` 为真时**强制校验意见**，而**「待办」里打开的草稿也是 `pageType=0`**
  （走 `else` 分支、`requiredCmt` 仍为真）⇒ **不能按裸 `pageType` 判**，否则草稿单永远提交不了。
- **修法**：抽 `computed.isDraftStage = pageType === '0' && !todoId`（与 `created()` 里把
  `requiredCmt` 置 false 的分支**完全一致**），只屏蔽「审批意见」标签 + 输入框 + 常用意见。
- **实测**：纯拟稿右栏只剩「流程审批 | 签名 提交」、控制台零报错且提交可用；审批页（`pageType=1`）意见框仍在。

### 3.9.5 ⛔ 非 `superAdmin` 用户**零菜单零权限**（未修 · 需决策）

| 证据 | 结果 |
| --- | --- |
| `sys_role_menu` | **0 行（空表）**——13 个角色全部零菜单 |
| `SecurityUtils.isAdmin` | 被硬编码为 `userName == "superAdmin"`（`SecurityUtils.java:125`）⇒ 其余用户走 `selectMenuTreeByUserId` |
| `zhangwei`（`common`）`/getRouters` | **一级菜单 `[]`、权限数 0** |
| `zhangwei` 调接口 | `/my/todo` **404**；`/workflow/done/list`、`/system/user/list` **403 没有权限** |

**影响**：除超管外**没人能用这套 OA**；而合同审批首节点原本指派给张伟 ⇒ **审批人打不开自己的待办，链路走不完**。
不像权限策略，更像**从未初始化或被清过**（PRD `REQ-PERM-006`/`AC-80` 要求 99 个权限点与菜单一一对应）。

**三个候选（用户已选 c）**：
(a) 修最小基线（把「我的」菜单树 + 处理待办所需权限点授给相应角色）；
(b) 业务自己在「角色管理」里配；
(c) **先绕开：首节点受理人改回 superAdmin，E2E 只覆盖超管链路** ← **本轮按此执行**（见 3.9.6）。

### 3.9.6 新增浏览器 E2E 套件（✅ 已交付并跑通）

- **位置**：`ruoyi-vue-oa-ui-master\tests\e2e\`（该仓库 `.gitignore` 早已预留 `tests/e2e/reports`）。
  **独立 `package.json`** —— 应用侧 `package.json` / `package-lock.json` **一字未动**（守 DEV-ENV §7
  「前端用例零新增依赖」的既有口径，也保住已入库的复现基线）。
- **三个"不用付代价"的关键做法**（都实测过）：
  1. `channel: 'chrome'` **复用本机 Chrome**，**不下载**浏览器内核（`npm i` 2 秒 3 个包，无 `ms-playwright` 目录）；
  2. 验证码**从本机 Redis `captcha_codes:<uuid>` 读答案**，**不改** `sys.account.captchaEnabled`
     （`tools/oa-login.ps1` 的"关开关再恢复"会动共享配置，并发还会踩）；两个坑：FastJson 存值**带 JSON 引号**、Redis 长连接**不能等 `end`**；
  3. 登录态落 `storageState` 复用；`.auth/` 含**真实 token**，已 gitignore（**安全项**）。
- **用例**（`specs\contract-approval.spec.js`）：登录 → 发起 → 找到「合同审批」→ 填 8 个必填项（含两个日期选择器、
  单选「经营」、签订公司默认值）→ 提交 → 逐级审批（**经发部审批 → 财务 → 审批节点 → 办理节点**）→ 办结。
- **断言三层**（不是只看界面变绿）：UI 操作 + **引擎终态**（`ACT_HI_PROCINST.END_TIME_` 非空且轨迹走到 `end endEvent`）
  + 两个历史缺陷（条件分支落点 3.9.2、标题 3.9.3）。
- **实测**：`3 passed (1.4m)`；失败自动留截图/trace/录屏；IM WebSocket 的 `ERR_CONNECTION_REFUSED`
  进集中白名单（`start-env.ps1` 不启 8544，每个页面都会有一条，不能当回归）。
- **运行**：`cd ruoyi-vue-oa-ui-master\tests\e2e ; npx playwright test`（或 `npm run e2e`）。
  **尚未登记进 DEV-ENV §7 门禁**（先把"能重复执行"证稳，再进阻断门禁）。
- ⚠ **已知限制**：每次运行**留 1 张真实单据**（收尾未自动化；删实例有产品接口
  `DELETE /flowable/instance/delete/{instanceIds}`，但业务表清理判据需单独设计）。
  用例内保留了前置检查 `assertCanOpenTodo`：**权限模型修好后，把 `CONTRACT.actor` 换成真实审批人、
  首节点受理人改回张伟，用例结构一行不用改即可恢复"跨用户审批"。**

### 3.9.7 ⚠ 纠正前一轮的两条结论（接手先读，别再按旧结论推理）

1. **「`selfPolicy` 是死配置」——前半句仍成立，但与首节点无关。**
   全仓确实只有编译器写它、没有任何运行时代码读它；但实测轨迹显示首节点是**被「发起提交」本身办掉的**：
   `n_first` 22:08:05.698 开始 → **22:08:10.508 结束**（正好是点提交那一刻），随后 gateway 22:08:10.511 求值。
   ⇒ **本系统的「发起」= 拟稿 + 完成第一节点**；首个**待**审批节点是条件分支命中的那个。
   （`htsp` 首节点因此**不在** E2E 的审批链里，见 3.9.6。）
2. **`sys_role_menu` 空表**（3.9.5）也解释了历史现象："普通用户登录后什么都没有"。

### 3.9.8 未决 / 待决策（本轮**没做**的部分）

| # | 事项 | 现状 |
| --- | --- | --- |
| 1 | **清理系统内测试流程**（只留合同审批相关，含已发起实例） | ⛔ **会打断 4 项门禁**：第 2/3/4/5 项分别依赖 `testSerial`、`authzPublishGuard`、`testParallel/testCombo/testCondition`。已给 **A（分两刀：先清实例，再清孤儿）/ B（字面执行 + 改造门禁夹具自建）/ C（导出种子 SQL 再重建）** 三方案，**待用户选定**。另：`ACT_*` 是 Flowable 引擎表，**不能裸 DELETE**。 |
| 2 | 需求①「发布后自动列入可启用」 | 已核实**新建模板 `enable_flag` 默认 `'0'` 且 `add.vue` 根本不提交该字段** ⇒ 建完必须去列表页手动开开关，否则发起页仍然看不到（**第二道坎**）。而"表单配置阶段可以选择模板"这句话的读法**待确认**（是"模板里选表单"还是"独立设计器里选模板并自动启用"）。 |
| 3 | 需求③「签订公司改单选」 | 表单里 `ourCompany` **已是 `multiple=false`**、实测弹窗 `max=1` 且无「全选/清空」⇒ **功能上已是单选**；待改的可能是触发器那种"可删除标签"的**显示样式**。 |
| 4 | 需求④「审批/办理阶段按表单权限让处理人可改控件」 | 现有机制**只有只读黑名单**：`node.fieldReadonly` → 发布时落 `t_template_node_field_auth` → `NodeFieldWriteGuardImpl` 服务端强制"本节点只读字段改不动"。要做"可修改"需引入**可写白名单**维度（DDL + 后端 guard 双向判定 + 前端按节点渲染 + 设计器配置 + 回归），**建议单开变更集**。 |
| 5 | 需求②后半「审批意见绑定审批节点显示」 | 未做；需先定展示位（建议放「办理过程」页签，按节点挂意见）。 |
| 6 | 已产出的规划件 | `openspec\changes\oa-e2e-playwright`（4/4，`openspec validate --strict` 通过）—— 记录 E2E 套件的能力契约与后续批次。 |

---

## 3.10 ✅ 2026-10-06（第七轮）：**B4「进销存」52 项全部交付** + 门禁全绿 + 独立终验 pass + 已推送 GitHub

**变更集**：`openspec/changes/oa-purchase-sales-stock/`（`proposal.md` / `design.md` / `specs/erp/**` / `tasks.md`（**52 项，0 项未勾**）/ `notes/**` 十余份交付记录）。

### ① 做了什么（功能面）
- **18 张表**（`ruoyi-vue-oa-master/sql/二开-进销存.sql`：8 类单据表 + 行项表、`t_ctms_stock`、`t_ctms_stock_ledger`）+ 菜单/权限增量（`sql/二开-进销存-菜单.sql`：121 行菜单 / 107 个权限点，幂等，连跑两次计数不变）；
- **后端** `ruoyi-ctms` 新增包 `com.ruoyi.ctms.erp.**`（`base`/`posting`/`procurement`/`sales`/`stockops`/`ledger`）：状态机（5×6×2 矩阵）、金额"**先舍入再汇总**"唯一实现点、取号（`ICodeGenService` + 月桶）、过账/红冲、下推（采购单→入库单、销售订单→出库单）、调拨两阶段两流水、盘点（全盘/抽盘、盘盈盘亏自动过账、盘亏豁免）、结存/流水与一致性校验、数据范围四档、导出（xlsx 流 + GET/POST）；
- **前端** `src/views/erp/**`（列表壳/表单壳 + 8 类单据页 + 库存明细/流水页 + 选项/字典/状态装载模块）+ `src/api/erp/**`；
- **脚本**：`tools/erp-smoke.ps1`（只读冒烟 27）、`tools/erp-check.ps1`（接口验收 **88/0/25**，自带判别力自证与夹具自清）、`tools/erp-scope-check.ps1`（数据范围四档 **59**）、`tools/flow-form-consistency-check.ps1`（模板 `formId` 一致性 + PRD V-8 反向校验）、`tools/locked-run.ps1`（`build`/`env` 互斥锁，**所有改库/构建的门禁都必须经它串行**）。

### ② 门禁（2026-10-06 复跑，**全部 exit 0**）
| 门禁 | 读数 |
| --- | --- |
| 浏览器 E2E `cd ruoyi-vue-oa-ui-master\tests\e2e ; npx playwright test` | **40 passed / 0 failed** |
| `tools\erp-check.ps1`（全段） | **88 通过 / 0 失败 / 25 跳过** |
| `tools\erp-smoke.ps1 -Strict` | **27/27** |
| `tools\erp-scope-check.ps1` | **59/0**（四档 + 还原 + 哨兵 + 零残留 + 全库无 `data_scope='5'`） |
| `mvn -B -pl ruoyi-ctms test` | **550/0/0** |
| 前端 `node tests\run.js` / `npm.cmd run build:prod` | **205/205** / EXIT 0 |
| `node tools\audit\run-all.js` | **10/10** |
| `ctms-attachment-check` / `flow-form-consistency` / `authz-check` | 107/0 / 27/0 / 45/0 |

### ③ 本轮查出并修掉的 **8 条真实缺陷**（都是"单测结构性看不到"那一类）
| 缺陷 | 为什么单测看不到 | 修复 |
| --- | --- | --- |
| `C-1` `posted` 歧义 getter（8 类单据写路径全 500） | 单测用内存桩**不走 OGNL**；MyBatis **版本偏斜**（测试类路径 3.5.13 容忍、打包件 3.5.7 不容忍）；异常**惰性**，真读属性才抛 | 改名 `isPostedFlag()` + 回归网 `ErpDomainReflectorTest`（逐个读属性 + 反向对照） |
| `D-29` `/sal/request/list` 500 | 页内无行时提前 `return` ⇒ **空表绿、建单后红**；订单侧被 `total=0` 掩盖 | `@MapKey` 误用（`Map<K,List<V>>`）改为平铺 `List` + 服务层分组 |
| `P-②` 表单下拉恒空 | 列表类接口体是 `{rows}` 而前端裸读 `res.data` ⇒ **请求发出去了、页面只是空**、无报错 | `erp-response.js`（`rowsOf`/`dataOf`）逐点声明层级 |
| `P-④` 字典下拉"有选项、无文字" | 字典行 `{dictLabel,dictValue}` 与模板 `o.label/o.value` 不匹配 | `doc-select-shape.js` 统一映射（三类筛选收敛一处） |
| `P-⑤` 单位列表**从未请求**（+ `uomDecimals` 静默退回 3 位，**用户可感知**） | `neededOptions()` 联动判定早于 products 扫描 | 抽出 `doc-options-need.js`，联动移到三个 scan 之后 |
| `T46-1` 库存两页同源 P0 | 门禁扫描范围只覆盖 `doc/**` ⇒ 同类残留逃逸 | 逐点修 + 门禁扫描扩到 `views/erp/**` |
| `F-01` 按商品类型（含子树）筛选**静默失效** | **门禁随库规模翻转**：类型表小时恰好命中 | 新增 `prepareQuery()`，把子树展开移到 `startPage()` **之前** |
| `t33` 表头仓库快照 | 缺 `warehouseId` 时撞 DB 原生 1048 | 应用层可读拒绝 + 服务端回填名称快照 |

### ④ 非阻断残留（`G-01~G-09`，已登记，owner 待定）
`G-01` 25 条 SKIP 的 owner 指向**已关闭的 t13**、部分理由过期（"t8 交付后补"）⇒ 逐条"重指派 / 登记裁剪·延后+owner"；`G-02` `DEV-ENV §7` 规格条数与脚本条目数缺映射表；`G-03` F-5/F-6 无门禁；`G-04` 10 条反直觉口径 4/10；`G-05` 长跑门禁（`b1-e2e-check`/`ctms-migration-prep` 等）未执行；`G-06` **`AC-26` 应标"未验证"**（`sign-feature-check` 42/1 为夹具缺失，不计通过）；`G-07` `related-approval-check` 15/8 属 **B 系列**（非本批改动面）；`G-08` 证据路径写法（`.last-run.json` 实际在 `tests/e2e/reports/artifacts/`）；`G-09` 采购线创建把新单 id 放 `msg` / 下推只接受裸数组（接口一致性）+ **`L-12` 导出腿未实现**。
技术债 `D-1~D-32` 全表见 `openspec/changes/oa-purchase-sales-stock/notes/00-team-brief.md §9.3`。

### ⑤ 已推送 GitHub（SSH 私钥 `~/.ssh/id_ed25519`；`git@github.com:miyanagaaoi/ruoyiOA.git`）
| ref | 内容 |
| --- | --- |
| `master` | **单仓快照**（外层 + `ruoyi-vue-oa-master/` + `ruoyi-vue-oa-ui-master/`，**含 F-01 修复**） |
| `backend` / `frontend` / `outer` | 三个仓库各自的完整历史 |

> **红线**：`.auth/` 令牌、`env/`（含 MySQL 私钥）、`.cache/`、`logs/`、`node_modules/`、`target/` 均已 `.gitignore` 排除，并逐一用 `git check-ignore` 验证。
> 推送方案与复现命令：`openspec/changes/oa-purchase-sales-stock/notes/99-release-push-plan.md`。

### ⑥ 接手要点
`tasks.md` 已 **52/52**；证据分布：`notes/13a-package-smoke.md`（门禁全表 + 逐条定性）、`notes/10-e2e.md`（E2E 收口 + 残留归属）、`notes/09-integration.md`（集成检查 + AC 逐条对照）、`notes/00-team-brief.md`（跨组冻结口径 + 技术债 D-1~D-32）。
**所有改库/构建的门禁必须经 `tools\locked-run.ps1` 串行**（`-LockName build` 给 Maven、`-LockName env` 给停启后端/接口脚本/Playwright）。

---

## 4. 铁律（违反＝返工）

1. **规格是唯一真源**：需求以 `openspec\changes\<变更集>\specs\**\spec.md` 为准；与 `doc\2.0\2.0-PRD-OA升级开发.md` 有歧义时看 `design.md` 的 D-* 决策记录，**不要自己发明口径**。
2. **模块边界**：新业务只加在 `ruoyi-ctms`（B4 复用同一模块）。**禁止**第二套账号表/组织树/自研令牌/权限常量表，**禁止**任何"关掉认证开关即可访问"的后门。认证/组织/权限/字典/编号/附件/日志/打印**一律复用平台能力**。
3. **金额与数量**：只用 `BigDecimal`（金额 scale=2、单价 scale=4、数量 scale=3），且**先舍入到分再汇总**。`grep -rn "double\|float" ruoyi-ctms/src/main/java` 必须零命中。
4. **数据范围服务端强制**：只用 `ContractDataScope` **一处**判定，覆盖列表/详情/编辑/删除/恢复/导出；范围外返回业务码 **403**（不是 500）。SQL 片段只能由服务端白名单拼出，**绝不能来自请求参数**。
5. **提示文案是接口契约**：错误消息被验收脚本按关键字断言，改文案必须同步改脚本。
6. **SQL 只增量、不动基线**：`sql\table.sql`、`sql\data.sql` 保持上游原样（有 `git status` 门禁）；二开对象走 `sql\二开-*.sql`，幂等（`DROP ... IF EXISTS` / 先 DELETE 再 INSERT / `information_schema` 守卫）。
7. **新能力同批交付可重复执行、能自证的回归脚本**（`--selftest` 或显式对照），且**不得让既有回归资产变红**。
8. **任务完成的判据是"真实验收通过"**，不是"编译过了"。按 `tasks.md` 的「验证：」跑，并在任务下补 `> 交付记录（日期）：…证据…`。
9. **`tasks.md` 由 captain/单人统一维护**：不要把它放进任何任务的 inScope（多个任务并发写同一文件会互相覆盖；本批踩过 harness「undeclared」拒收）。
10. **改 `@PreAuthorize` / 权限点 / 常量之后必须重打包**，否则运行态还是旧判定（DEV-ENV §6.46 有"直接在 `.class` 里查字符串"的判定法）。
11. **提交纪律**：`.mvn/maven.config`、`application.yml` 这类**含本机绝对路径/环境相关**的文件，改动前先想清楚是否该进仓库。

---

## 5. 代码地图

```
F:\dsh\ruoyiOA\
├─ DEV-ENV.md                      ← **环境手册 + 52 条踩坑**（动手前必读 §4 常用命令 / §6 坑 / §7 门禁）
├─ HANDOFF.md                      ← 本文件
├─ start-env.ps1 / stop-env.ps1    ← 一键起停（幂等）
├─ openspec\changes\<变更集>\      ← 规格 / 设计 / 任务 / notes（**真源**）
├─ tools\                          ← 验收脚本（**21 个 .ps1**）；tools\audit\ = **10 个静态审计**
├─ env\                            ← 便携 MySQL/Redis（my.ini 是绝对路径，换盘要改）
├─ logs\ uploadPath\ .cache\       ← 运行期产物（已 gitignore）├─ ruoyi-vue-oa-master\            ← 后端（Maven 多模块；**独立 git 仓库**）
│   ├─ ruoyi-ctms\                 ← **B3/B4 业务模块**
│   │   ├─ domain\ (+ vo\)         ← 实体；BaseEntity 提供 createBy/createTime/updateBy/updateTime/remark
│   │   ├─ mapper\ + resources\mapper\ctms\*.xml   ← XML 必须 `*Mapper.xml` 结尾，否则不加载
│   │   ├─ support\                ← 纯函数规则：PartnerRules / ProductMasterRules / ContractRules / ContractNumberRules / ContractDataScope / MigrationRules
│   │   ├─ service\ (+ impl\)      ← 口径集中在这里；控制器只做参数透传
│   │   └─ src\test\java\...\      ← **217 条单测**（内存桩 Mapper，不起 Spring）
│   ├─ ruoyi-serial\               ← 编号（`ICodeGenService`）
│   └─ sql\                        ← 二开增量脚本 + `README-合同台账SQL.md`
└─ ruoyi-vue-oa-ui-master\         ← 前端（Vue2 + element-ui；**独立 git 仓库**）
    ├─ src\api\ctms\               ← contract.js / tag.js / attachment.js / migration.js / partner.js / masterdata.js
    └─ src\views\ctms\             ← contract\ (index.vue + detail.vue + contract-rules.js) / tag\ / partner\ / migration\
```

> 外层仓库**不跟踪**两个子工程（各自独立 `.git`），见外层 `.gitignore`。
> ⚠ **两处"本机绝对路径"**：`env\mysql\my.ini`（datadir/basedir）与 `ruoyi-vue-oa-master\.mvn\maven.config`（`-s` 指向外层 `tools\`）。
> 后者**必须是绝对路径**（相对路径按后端仓库根解析、`${...}` 占位符在 maven.config 里不被展开，两种都实测失败），换机器/换盘符要改。

---

## 6. 关键实现口径（改动前务必先读这些文件头注释）

| 主题 | 口径 | 真源 |
| --- | --- | --- |
| 金额 | 行总价按 HALF_UP 保留 2 位**再**求和（3 行 × 1.665 → **5.01**，不是 5.00） | `ContractRules` / 前端 `contract-rules.js` |
| 编号 | 服务端生成，**停用占号不复用**；`isNoTaken` 只判精确占用；计数器以库内桶 max 兜底；**上限耗尽抛异常** | `ContractNumberRules` + `CtmsContractServiceImpl` |
| 质保 | 到期日 = 生效日 + 期限（月），**只读且不参与提交**（服务端重算） | `ContractRules` |
| 数据范围 | `ALL`/`DEPT`(含下级默认)/`SELF`，多角色取**并集**；范围外业务码 403 | `ContractDataScope` |
| 软删除 | 30 天**整数日差**（第 30 天可恢复、第 31 天不可） | `ContractRules.isWithinRestoreWindow` |
| 变更历史 | 字段名/对象类型**单一真源常量**；值用**档案ID**（名称放 note）；编辑整单与迁移认领**同字** | `ContractRules.FIELD_*` / `OBJECT_TYPE_CONTRACT` |
| 迁移草案状态机 | `pending/claimed/ignored`；`claimed` 遇新未绑定合同回 `pending`；**`ignored` 连计数也不刷新**（不复活） | `MigrationRules` + `notes\migration-notes.md` |
| 标签 | 自动标签由服务端维护（类型名 / 「框架合同」），前端**运行时从字典推导**、不复制清单；手动标签不被自动同步移除 | `CtmsTagServiceImpl` |
| 附件 | 白名单 10 种后缀 / 单文件 ≤20MB / 超限 HTTP **413** / 按**对象**鉴权 / 删除=软删除 + 留痕；限制口径由 `/ctms/attachment/object-types` 接口给出 | `CtmsAttachmentRules` + `notes\attachment-notes.md` |
| 数据范围标签偏差 | `sys_role.data_scope` 的**界面标签与服务端口径不一致**（`'4'` 实际比标签**偏宽**），配范围按**值语义**不按标签 | DEV-ENV §6.49 |

---

## 7. 坑（**别在这里背，去 DEV-ENV §6 查**；这里只列最贵的几条）

| 坑 | 一句话 |
| --- | --- |
| §6.33 | `.ps1` 里把含中文的 SQL 管道喂给 `mysql.exe`，**中文会变成 `?`**（控制它的是 `$OutputEncoding`，不是 `[Console]::OutputEncoding`）——**门禁全绿但界面显示问号**；含判定法与用真源 SQL 修数据 |
| §6.46 / §6.47 | 改了注解/权限点**必须重打包**；切权限态要做**完整重新登录**，不能只靠 `/getInfo` |
| §6.50 | PowerShell 的三种"假值"（`@(... \| Measure-Object).Count` 恒为 1、`-Raw` 只回 1、`Select-String -Path <dir>\**\*` 扫不到）+ `Select-String` **默认不区分大小写**（日期门禁假红）+ 量行数必须 `-Encoding UTF8` + **集合类门禁会被"说明文字"打红** |
| §6.52 | **"绿"和"对"是两件事**：错误的清理判据会让「清理」与「验证清理」**同时失效、双双报绿** ⇒ 必须有**与判据无关的基线兜底网**（另附 `curl.exe` 不编码中文 URL 的同族坑） |
| §6.48 | 验收脚本临时改共享状态（借角色改授权/数据范围）必须"**入口自愈 + 收尾还原 + 哨兵**"；`data_scope='5'` 留在库里会让受测账号**全面 403** |
| §6.14 / §6.42 | 别用"未认证/非法形态 id"验路由（会得到与"路由不存在"同形的 404）；**验收脚本有运行锁，不可并发** |
| §6.38 | 夹具主键由**服务端生成**、业务身份在 `code` 列——按"我以为的主键"清理会**空操作 + 假绿** |

---

## 8. 交付门禁（DEV-ENV.md §7 全表 · 共 16 项）

> **⚠ 2026-10-06 更新**：门禁表已扩到 **21 项**（新增 B4 的 4 个脚本 + 前端 B4 单测 + 浏览器 E2E），**现行全表以 `DEV-ENV.md §7` 为准**（其第 1~19 项 + 下表补的两行）。
> 下表 §8.1 是 **B4 轮的复跑读数**（全部 exit 0），可直接作为"目标基线"对照；下面 §8 原始那段命令清单保留作**历史**（其中 `mvn -pl ruoyi-ctms test` 的 217 条、前端 39 条等数字已过时）。

### 8.1 2026-10-06 现行门禁读数（全绿）
```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1      # token 会过期，跑接口脚本前先跑

# —— 静态 ——
node tools\audit\run-all.js                                                        # 10/10
cd ruoyi-vue-oa-ui-master ; node tests\run.js ; npm.cmd run build:prod ; cd ..      # 205/205 + EXIT 0
# —— 后端单测 ——
cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test ; cd ..                         # 550/0（含 ruoyi-workflow 19 / ruoyi-serial 24）
# —— 运行时（全部经 env 锁串行）——
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName env -Command "cd F:\dsh\ruoyiOA; powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1 -Strict"     # 27/27
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName env -Command "cd F:\dsh\ruoyiOA; powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1"            # 88 通过 / 0 失败 / 25 跳过
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName env -Command "cd F:\dsh\ruoyiOA; powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-scope-check.ps1"        # 59/0
# —— 浏览器 E2E（必须 cd 到 tests\e2e；在仓库根跑会 0 tests）——
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName env -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master\tests\e2e; npx playwright test"                                  # 40 passed / 0 failed
# 其余：authz 45/0、perm-audit 15/15、b3-sql-drill 68/0、flow-form-consistency 27/0、attachment 107/0、contract 261/0、masterdata 79/0、serial 32/0、print 98/0、b1-binding 25/0、ctms-e2e 91/0、flow-regression / migration / commercials 全绿
```
**已知非本批红项（不要当成自己改坏了）**：`sign-feature-check` 42/1（`AC-26` **夹具缺失 ⇒ 该条实际未验证**）、`related-approval-check` 15/8（**B 系列**，非 B4 改动面）。

> ⚠ **先看 §3.2**：本表是**目标基线**，但其中 `tools\flow-regression.ps1` 在当前环境**实测 15 通过 / 5 失败**
> （`testParallel` / `testCombo` 的并行会审 ALL 合流卡死，属**既有问题**、非本轮引入）。
> 即**这张表跑不满**；不要把"某一项没过"直接当成自己改坏了，也不要反过来把"全绿"当成既成事实。

**顺序：先静态、后运行时**；静态失败时不必起服务。任一命令非 0 退出即视为本次交付未完成，**不得以"环境没起"为由记为通过**。

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1

node tools\audit\run-all.js                                                       # 1  静态审计 10 个
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1        # 2  越权 45
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\sign-feature-check.ps1 # 3  签名 44（勿用管道截断）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1    # 4  流程 20
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\print-builtin-check.ps1# 5  打印版式 98
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\serial-numbering-check.ps1 # 6 编号 32
cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-workflow test ; mvn -B -pl ruoyi-serial test ; mvn -B -pl ruoyi-ctms test   # 7~9 单测 19/24/217
cd ..\ruoyi-vue-oa-ui-master ; npm.cmd run test:unit ; cd ..                        # 10 前端用例 39
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b3-sql-drill.ps1       # 11 SQL 四件套演练 68
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1 # 12 主数据 79
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1   # 13 合同 261（含 4.8b 数据范围矩阵）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-commercials-check.ps1# 14 商务 116
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1 # 15 附件 69
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-check.ps1  # +  迁移 52（第 7 组）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1        # +  端到端 91
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1       # +  权限点双向核对（26/26）

# 打包含新代码时（DEV-ENV §6.13）：先停后端 → -pl ruoyi-ctms clean install → -pl ruoyi-admin clean package
#   → **核对 jar 时间戳** → 重启 → 探针端点（改了 @PreAuthorize 后尤其必须）
```

⚠ **验收脚本会临时借用 `common` 角色**改授权与 `data_scope`：收尾必须还原，且脚本要有入口自愈哨兵（DEV-ENV §6.48）。
⚠ **跑夹具类脚本前先记录"零夹具基线"**（当前 `contracts/suppliers/drafts/attachments` 应均为 0），收尾再对一次。

⚠ **本轮（第六轮）新增的浏览器端到端套件尚未登记进本表** —— 先把"可重复执行且结果一致"证稳，再让它进阻断门禁：

```powershell
cd ruoyi-vue-oa-ui-master\tests\e2e
npx playwright test          # 3 passed / 约 1.4 分钟（含 2 次登录 setup + 1 条全链路用例）
```

- 它**独立依赖**（自带 `package.json`，应用侧 `package.json`/`package-lock.json` 一字未动）；
- 前置与其它"需登录"项一致：先 `.\start-env.ps1`；验证码从**本机 Redis** 取答案（**不改验证码开关**）；
- 复用**本机 Chrome**（不下载浏览器内核）；失败留截图/trace/录屏；
- ⚠ 每次运行会**留 1 张真实单据**（收尾未自动化）；细则与已知限制见 **§3.9.6**。

---

## 9. 变更集收尾清单（归档前逐项打勾）

- [x] `tasks.md` 全部 `- [x]`（**B3 已 64/64**），每条有交付记录与证据
- [x] `openspec validate oa-contract-ledger` 通过（实测 `Change 'oa-contract-ledger' is valid`）
- [x] 新增能力都有可重复执行的回归脚本，且已登记进 `DEV-ENV.md` §7 门禁表
- [x] 所有门禁全绿（§8 共 16 项；B3 最后复跑 16/16 exit 0）
- [x] 新踩的坑写进 `DEV-ENV.md` §6（编号顺延：本轮新增 §6.49 / §6.50 / §6.51 / §6.52，并重写 §6.33）
- [ ] **归档**：`openspec archive oa-contract-ledger`（**全部任务完成后**再做；B3 已具备条件，只差人工确认）
- [x] **B4 已对照完毕**：`tasks.md` **52/52 全勾**且每条有交付记录与证据；门禁实测 **E2E 40/0、erp-check 88/0/25 exit 0、mvn 550/0、前端 205/205、audit 10/10、smoke 27/27、scope 59/0**（见 §3.10 §②）
- [x] **B4 新踩的坑已写进 `DEV-ENV.md §6`**（编号顺延：§6.61~§6.66，见 §3.10 与 DEV-ENV 正文）
- [x] **提交并推送**：三个仓库已提交并推送到 `git@github.com:miyanagaaoi/ruoyiOA.git`（`master` 单仓快照 + `backend`/`frontend`/`outer`，见 §3.10 第 ⑤ 节）
- [x] **独立终验 `verdict = pass`**（t18 attempt 2；F-01 三个复核点全中 + AC 逐条对照）
- [ ] **B4 非阻断残留 `G-01~G-09` 逐条处置**（见 §3.10 第 ④ 节；优先 `G-01` 的 SKIP 归属与 `G-09` 的接口一致性 + `L-12` 导出腿）
- [ ] **归档**：四个变更集的 `openspec archive`（`oa-contract-ledger` / `oa-purchase-sales-stock` 等均具备条件，**只差人工确认**）
- [ ] **已知边界 T20-F1**：决定按 §3 的 (a) 或 (b) 收口，或明确接受为已知边界并在交付说明中写明

---

## 10. 下一个 Agent 的推荐起手式

**起手（无论做哪个选项，先做这三步）**
```powershell
cd F:\dsh\ruoyiOA
git status            # 三仓都有未提交改动（清单见 §0.1），先确认是你/上一位留的
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1
node tools\audit\run-all.js                       # 静态审计（应 10/10）
cd ruoyi-vue-oa-ui-master ; node tests\run.js     # 前端单测（应 90/90；第三轮前是 75）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1   # ⚠ 会看到 §3.2 的既有失败
```

**选项 D：参与人「选人 / 选角色」改造 —— ✅ 2026-10-05 已完成（方案 A，见 §3.3）**
剩余动作：按 **§3.3.3** 让**人工**各确认一次（组织树里点部门选人 / 下拉里点角色）；
若人工确认通过，可顺手做 §3.3.4 建议的共享弹窗 `deptId` 小变更（**动共享面前先问用户**）。

**选项 D+：单人节点的「指定人员」收敛 —— ✅ 2026-10-05 已完成（第四轮，见 §3.4）**
剩余动作：按 **§3.4 的 §10.5 人工步骤**确认一次"勾第 2 个人勾不上"（约 1 分钟；本桥点不中复选框）。

**选项 A：收口 B3（成本最低）**
1. 按 §9 清单收尾：提交三仓 → `openspec archive oa-contract-ledger`；
2. 就 §3 的 T20-F1 二选一（建议 (a)：给回滚脚本加"空库/无变更前状态"模式，一个独立小变更即可闭环）；
3. 顺手把 §3 的运维口径写进 `sql\README-合同台账SQL.md`。

**选项 B：开工 B4「进销存」**（`openspec/changes/oa-purchase-sales-stock`，52 项任务）
1. **先读 `notes/ddl-scope.md`**：B3/B4 的 DDL 边界已冻结——B4 有 26 张自己的表，**只引用** B3 的 `t_ctms_product`/`t_ctms_uom`/`t_ctms_warehouse`/`t_ctms_contract`，**不得重建**；
2. 复用 `ruoyi-ctms` 模块与 B3 的全部分层/规则写法；附件接入要按 `notes/attachment-notes.md` 的"接入必改三处"补对象类型（`plannedB4` 里已登记 5 个单据类型）；
3. 沿用本文件 §4 铁律与 §8 门禁；每条任务按 `tasks.md` 的「验证：」取证。

**选项 C：只做维护**：先跑 §8 门禁确认全绿，再动任何代码（注意 §3.2 的既有失败会让你看到"不绿"）。

---

## 11. 本轮遗留清单（交接用 · 逐条对账）

| # | 事项 | 状态 | 位置 |
| --- | --- | --- | --- |
| 1 | 流程条件字段与「关联表单」错位（V-8 误报） | ✅ 已修（数据 + 模板对齐，校验 0 问题） | §3.1 |
| 2 | 分支条件文字显示字段 label（不再显示 `field101`） | ✅ 已修（`conditionText.js` 唯一真源 + 25 条用例） | §3.1 |
| 3 | 表单字段名「单选框组」→「合同类型」 | ✅ 已改（表单换版本，流程/模板已同步改指） | §3.1 |
| 4 | 条件字段下拉的 `allow-create`（可手打字段名绕过 V-8） | ✅ 已收紧 | §3.1 |
| 5 | **参与人「选人 / 选角色」改造** | ✅ **已交付（方案 A）**；仅"组织树里点部门 / 下拉里点角色"两步待人工各确认一次 | **§3.3** |
| 5b | **「多人方式 = 单人」时指定人员仍能勾多人** | ✅ **已修（第四轮，纯前端）**；弹窗勾第 2 个人这一步待人工确认一次 | **§3.4** |
| 5c | **生成「合同审批（含用印）」动态表单内容** | ✅ **已交付**（生成器 + JSON + 已入库表单）；**建模板 / 绑流程待你确认** | **§3.5** |
| 5d | **新建流程的「流程标识」优化（方案 B）** | ✅ **已交付**（自动建议 + 实时查重 + 折叠进高级选项） | **§3.6** |
| 5e | **「指定人员」能选到超管账号（方便测试）** | ✅ **已交付**（共享弹窗加 `pinnedUsers`，超管常驻候选；纯前端） | **§3.7** |
| 5f | **发布校验报的问题与画布对不上（V-1 误报 / V-2 指不到节点）** | ✅ **已修**（发布前先存画布再校验 + 阻断项补画布定位；纯前端） | **§3.8** |
| 6 | 并行会审(ALL)合流卡死（`testParallel`/`testCombo`） | ❌ 既有问题，未修（**使 §8"全绿"不成立**） | §3.2 |
| 7 | 「金额」字段未设必填 ⇒ 分支2 现用 `field101 = 经济` 而非"金额>10000" | ⏸ **用户明确说待定** | 缺陷文档 §7-A |
| 8 | "模板 form_id ↔ 流程 formId"一致性检查 | ❌ 未做 | 缺陷文档 §7-B |
| 9 | PRD V-8 要求的"表单保存时反向校验"（代码里从未实现） | ❌ 未做 | 缺陷文档 §7-B |
| 10 | 同族存量：5 条测试流程 / `tpl_BRIDGEAC` / 4 个模板指向**已停用或已软删**的表单行 | ❌ 未清理（普查结果见缺陷文档 §7-C） | §3.1 |
| 11 | 三仓改动**未提交**；四个变更集**未归档**；T20-F1 未定 | ❌ 待办 | §9 |
| **12** | **「新启流程」里根本没有合同审批**（流程 ≠ 发起入口；独立发布**静默不绑定模板**） | ✅ **已修**（新增 `tools\bind-flow-to-template.js` + 模板 `B9B72AB7…`；只 +1 行 `t_template`） | **§3.9.1** |
| **13** | **提交流程报 `Error while evaluating expression: ${field101 == '经营'}`**（条件存 label、变量是码值） | ✅ **已修**（编辑器改存 `o.value` + 存量迁移 → **htsp v4** + 更正两处写反的注释） | **§3.9.2** |
| **14** | **待办标题为空**（`t_workflow_todo.title` 118/118 NULL；`getTitle` 命中「分组标题」排版控件） | ✅ **根因已修**（E2E 已断言）；⚠ **存量 118 条待办 / 444 条已办仍为 NULL**，需回填或删除 | **§3.9.3** |
| **15** | 拟稿阶段仍显示右侧「审批意见」 | ✅ **已修**（`computed.isDraftStage`；**待办草稿保留**意见框，不挡提交） | **§3.9.4** |
| **16** | **非 `superAdmin` 用户零菜单零权限**（`sys_role_menu` 空表 + `isAdmin` 硬编码为 `superAdmin`） | ⛔ **未修 · 需决策**（a 修最小基线 / b 业务自配 / c 先绕开 ← 本轮按 **c**，流程已到 v5） | **§3.9.5** |
| **17** | **浏览器 E2E 套件**（登录→发起→填表→条件分支→逐级审批→办结） | ✅ **已交付并跑通**（`3 passed / 1.4m`）；⚠ 尚未登记进 §7 门禁；每次运行留 1 张真实单据 | **§3.9.6** |
| **18** | 需求①发布后自动启用（新建模板默认 `enable_flag='0'`）／③签订公司显示样式／④节点可写字段／②意见按节点展示 | ❌ 未做，读法与方案待确认（④建议单开变更集） | **§3.9.8** |
| **19** | 用户要求的**清理系统内测试流程**（只留合同审批相关，含已发起实例） | ⛔ **待用户选定 A/B/C**（字面执行会**打断 4 项门禁**；`ACT_*` 不能裸 DELETE） | **§3.9.8** |

> 完整技术记录（现象/复现/证据链/根因/源码行号/实测）：`doc\缺陷-流程条件字段与关联表单错位.md`（§6.4.2 / §6.4.3 是本轮失败尝试与教训）。
