# 交接文档（给继续开发的 Agent）

> 面向对象：**下一个接手本仓库的 AI Agent**。目标：不看历史，用最短时间搞清楚
> "现在在哪、下一步做什么、怎么证明做对了、哪些坑不能再踩"。
>
> 最后更新：2026-10-05（**B3「合同台账」已完成 64/64**；门禁 16/16 全绿；**1 条已知边界**见 §3）

---

## 0. 一句话现状

RuoYi-Vue-OA 的 2.0 二次开发，**B3「合同台账」**（OpenSpec 变更集 `oa-contract-ledger`，64 项任务）
**已全部交付**：第 1~10 组完成，**16 项交付门禁逐条 exit 0**，`openspec validate oa-contract-ledger` 通过。

| 变更集 | 状态 | 说明 |
| --- | --- | --- |
| `oa-v2-overview` | 交付完成（**未归档**） | 2.0 总览与交付门禁规格 |
| `oa-form-flow-tabs`（B1） | 交付完成（**未归档**） | 模板四页签重构 + 流程↔模板绑定 |
| `oa-print-builtin-templates`（B2） | 交付完成（**未归档**） | 内置打印版式按类型选用 |
| **`oa-contract-ledger`（B3）** | **✅ 64/64 完成**（本文件主体） | 合同台账 + 主数据档案 + 迁移工具 |
| `oa-purchase-sales-stock`（B4） | **⬜ 未开工**（52 项任务） | 进销存；**复用 `ruoyi-ctms` 模块**，DDL 边界与 B3 已冻结 |

> ⚠ **四个变更集都还在 `openspec/changes/` 下、`openspec/changes/archive/` 是空的** —— 即"交付完成"与"已归档"是两件事：
> 归档动作（`openspec archive <变更集>`）**一次都没做过**，见 §9 收尾清单。
> 三个仓库的改动也**尚未提交** —— 接手时先 `git status` 确认。

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

---

## 9. 变更集收尾清单（归档前逐项打勾）

- [x] `tasks.md` 全部 `- [x]`（**B3 已 64/64**），每条有交付记录与证据
- [x] `openspec validate oa-contract-ledger` 通过（实测 `Change 'oa-contract-ledger' is valid`）
- [x] 新增能力都有可重复执行的回归脚本，且已登记进 `DEV-ENV.md` §7 门禁表
- [x] 所有门禁全绿（§8 共 16 项；B3 最后复跑 16/16 exit 0）
- [x] 新踩的坑写进 `DEV-ENV.md` §6（编号顺延：本轮新增 §6.49 / §6.50 / §6.51 / §6.52，并重写 §6.33）
- [ ] **归档**：`openspec archive oa-contract-ledger`（**全部任务完成后**再做；B3 已具备条件，只差人工确认）
- [ ] **提交**：三个仓库（外层 / 后端 / 前端）的改动尚未提交，提交信息建议按变更集分组
- [ ] **已知边界 T20-F1**：决定按 §3 的 (a) 或 (b) 收口，或明确接受为已知边界并在交付说明中写明

---

## 10. 下一个 Agent 的推荐起手式

**选项 A：收口 B3（推荐，成本最低）**
1. 按 §9 清单收尾：提交三仓 → `openspec archive oa-contract-ledger`；
2. 就 §3 的 T20-F1 二选一（建议 (a)：给回滚脚本加"空库/无变更前状态"模式，一个独立小变更即可闭环）；
3. 顺手把 §3 的运维口径写进 `sql\README-合同台账SQL.md`。

**选项 B：开工 B4「进销存」**（`openspec/changes/oa-purchase-sales-stock`，52 项任务）
1. **先读 `notes/ddl-scope.md`**：B3/B4 的 DDL 边界已冻结——B4 有 26 张自己的表，**只引用** B3 的 `t_ctms_product`/`t_ctms_uom`/`t_ctms_warehouse`/`t_ctms_contract`，**不得重建**；
2. 复用 `ruoyi-ctms` 模块与 B3 的全部分层/规则写法；附件接入要按 `notes/attachment-notes.md` 的"接入必改三处"补对象类型（`plannedB4` 里已登记 5 个单据类型）；
3. 沿用本文件 §4 铁律与 §8 门禁；每条任务按 `tasks.md` 的「验证：」取证。

**选项 C：只做维护**：先跑 §8 门禁确认全绿，再动任何代码。
