# B4 团队作业简报（captain 维护 · 所有成员动手前必读）

> 生成时间：2026-10-05｜维护人：captain（AgentTeams 队长）
> 本文件是**协作约定**（谁写哪里、怎么跑、怎么取证）；**需求与验收口径的唯一真源仍是 OpenSpec 规格**，见 §2。
> 成员不得修改本文件；发现约定有问题 → 用 `agent_teams_send_message` 报给 captain。

## 0. 本批次目标与范围

**主项（B4）**：`openspec/changes/oa-purchase-sales-stock`（进销存，52 项任务）——完整交付 8 类单据、
下推链路、过账/红冲、调拨、盘点、库存明细与流水、主数据补充、权限与数据范围、前端页面、集成检查。

**附带项**（与 B4 无依赖，可并行）：
- ① `testParallel`/`testCombo` 并行会审 ALL 合流卡死（`tools/flow-regression.ps1` 既有失败，见 `HANDOFF.md` §3.2）；
- ② "模板 `form_id` ↔ 流程 `formId`" 一致性检查（`HANDOFF.md` §3.1、§11 第 8 行）；
- ③ PRD `V-8` 要求但代码从未实现的"表单保存时反向校验"（`HANDOFF.md` §11 第 9 行）。

**明确不做**（用户指示"待决策部分先不做"）：测试数据/测试流程清理（§3.9.8 第 1 项、§11 第 19 行）、
T20-F1 的 a/b 二选一（§3）、非超管用户零菜单零权限的 a/b 修补（§3.9.5，本轮仍按 c 绕开：E2E 走 superAdmin）、
需求①③④②（§3.9.8）。**不要顺手做这些，也不要因为它们的存在而改口径。**

## 1. 仓库与模块落点

| 仓库 | 路径 | 说明 |
| --- | --- | --- |
| 外层 | `F:\dsh\ruoyiOA` | 编排、`tools/`、`doc/`、`openspec/`；`DEV-ENV.md` / `HANDOFF.md` |
| 后端 | `F:\dsh\ruoyiOA\ruoyi-vue-oa-master` | Maven 多模块（独立 git，**铁律：改动 @PreAuthorize/权限点后必须重新打包**） |
| 前端 | `F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | Vue2 + element-ui（独立 git；dev server 起在 80 端口） |

**B4 只加在后端 `ruoyi-ctms` 模块**（铁律 2；DDL 边界见 §2 的冻结件）。**不得新建第二个业务模块、不得重建 B3 的表。**

### 1.1 包与文件布局（按组切分，避免同文件并发写）

| 组 | 包（`ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/`） | Mapper XML（`ruoyi-ctms/src/main/resources/mapper/erp/`） | 单测（`ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/`） |
| --- | --- | --- | --- |
| T1 基础 | `base/` | `Erp*Mapper.xml` | `base/` |
| T2 主数据 | `master/`（并允许改 B3 的 `service/impl/CtmsProductMasterServiceImpl.java`、`support/ProductMasterRules.java`） | `ErpMaster*Mapper.xml` | `master/` |
| T3 过账 | `posting/`（含结存 `t_ctms_stock` / 流水 `t_ctms_stock_ledger` 的 domain+mapper+写入服务） | `ErpStock*Mapper.xml`、`ErpStockIn*Mapper.xml`、`ErpStockOut*Mapper.xml` | `posting/` |
| T4 采购线 | `procurement/` | `ErpPur*Mapper.xml` | `procurement/` |
| T5 销售线 | `sales/` | `ErpSal*Mapper.xml` | `sales/` |
| T6 调拨盘点 | `stockops/` | `ErpTransfer*Mapper.xml`、`ErpStocktake*Mapper.xml` | `stockops/` |
| T7 库存账 | `ledger/`（**只读查询**；结存/流水 domain 归 T3） | `ErpLedger*Mapper.xml` | `ledger/` |
| T9 前端 | 前端 `src/views/erp/`、`src/api/erp/` | — | 前端 `tests/` |
| T12/T13 | 后端 `ruoyi-workflow/`、`ruoyi-template/`；前端 `src/views/workflow/simple-flow/` | — | 各自模块测试 |

**表名已冻结**（`sql/初始化-全部.sql` 末尾自检段 ⑥ 已写死 8 个表头名，T1 必须沿用，否则编排自检变红）：

```
t_ctms_purchase_request / t_ctms_purchase_order / t_ctms_sales_request / t_ctms_sales_order
t_ctms_stock_in / t_ctms_stock_out / t_ctms_stocktake / t_ctms_transfer
```

行项表 = 表头表名 + `_item`（与 B3 `t_ctms_contract_item` 惯例一致）；结存 = `t_ctms_stock`；流水 = `t_ctms_stock_ledger`。
合计 8 + 8 + 1 + 1 = **18 张**，与冻结件一致。

## 2. 真源（不许凭记忆写代码）

1. `openspec/changes/oa-purchase-sales-stock/proposal.md` / `design.md` / `tasks.md`（D1~D12 决策、每条任务的「验证方式」）
2. `openspec/changes/oa-purchase-sales-stock/specs/erp/{stock-master-data,procurement,sales,inventory-operations,stock-ledger}/spec.md`
3. 移植口径（字段级清单/状态机/端点/难点）：`doc/2.0/参考仓库-CTMS-移植清单.md`
4. PRD 硬约束与必须补齐项：`doc/2.0/2.0-PRD-OA升级开发.md` §7.2（C-1..C-7 / F-1..F-7）、§7.4、§7.5、§9.4、§13
5. DDL 边界冻结件：`openspec/changes/oa-contract-ledger/notes/ddl-scope.md`（B4 = 18 张；`t_ctms_product*`/`t_ctms_uom`/`t_ctms_warehouse`/`t_ctms_contract` 只引用不重建）
6. B3 的分层与写法样板（照抄风格）：`ruoyi-ctms` 现有 `support/*Rules.java` + `service/impl/*ServiceImpl.java` + `*Mapper.xml` + `src/test`（内存桩 Mapper，**不起 Spring**）

## 3. 并发与共享资源规则（违反会互相踩坏构建）

1. **单人单写**：只改自己任务 write scope 内的文件。发现别人的文件编译不过 → **不要替他改**，
   `agent_teams_send_message` 通知 captain 后稍等重试（最多 3 次，间隔 ≥3 分钟）。
2. **共享资源必须串行**（Maven / 后端重启 / 接口脚本 / 改库夹具 / playwright）。统一用构建锁：

   ```powershell
   # Maven 构建与单测
   powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 -LockName build -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"
   # 后端打包/重启 + 接口验收脚本 + playwright
   powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 -LockName env -Command "..."
   ```

   等锁是正常现象（默认最多 30 分钟），**不要绕过锁**，也不要另起 Maven/后端进程。
3. **保持模块随时可编译**（**包括 `src/test`**）：不要留下语法错误的半成品。
   实测坑（2026-10-05 23:0x）：`mvn -B -pl ruoyi-ctms test-compile` 因 `src/test` 里一个文件编译不过而整体失败，
   **会同时挡住全队所有人的 L1**（主源码 `compile` 绿也没用）。改多文件时请分批提交式推进，保证每一批 `test-compile` 都绿。
   **2026-10-05 复盘（当天全模块因中间态红了 6 次，每次都让 2~3 人整轮 L1 跑不了）——两类高频原因与对策**：
   - ① **「接口先加、实现后补」**：在接口上加了方法还没写实现 ⇒ `不是抽象的, 并且未覆盖 ... 抽象方法`。
     对策：**先落实现（哪怕先抛 `UnsupportedOperationException` 的桩）再改调用方**，中间态也要能编译。
   - ② **「同名重载歧义」**：新增重载后调用点变成 `对 xxx 的引用不明确`（两个重载都匹配）。
     对策：重载签名必须**可区分**（不同参数个数/类型），或干脆改名；改完**立刻 `test-compile`**（这类错误只在编译期暴露）。
   - 通用对策：**每写完一批就 `test-compile`（11 秒）**，别攒到"全写完再编"。
4. **不要改**：`openspec/changes/oa-purchase-sales-stock/tasks.md`（captain 统一维护）、
   `sql/table.sql`、`sql/data.sql`（上游基线，`git diff` 必须为空）、别人的 notes 文件。
5. **精度铁律**：`BigDecimal`（金额 2 位 / 单价 4 位 / 数量按单位小数位、内部 3 位）；
   `grep -rn "double\|float" ruoyi-ctms/src/main/java` 必须零命中（B3 已零命中，别打破）。
6. **编码**：`.ps1` 保留 **UTF-8 BOM**；`.java/.xml/.md/.sql` **无 BOM**；PowerShell 里 `"$var"` 后紧跟中文/引号/`$` 必须写 `${var}`。
   含中文的 SQL **不要**用 PowerShell 管道喂给 `mysql.exe`（会变 `?`，DEV-ENV §6.33）。
7. **文档**：每条任务的交付记录写进**你自己的** notes 文件（captain 会给路径），不要动别人的 notes。
8. **公共层唯一实现点（2026-10-05 22:44 captain 追加）**：`com.ruoyi.ctms.erp.base` **只由 backend-base 维护**，其他组**只消费、不复刻**。
   已就绪可直接使用：`ErpDocStateMachine` / `ErpDocStatus` / `ErpDocType` / `ErpDocAction` / `ErpAmounts`（金额"先舍入再汇总"）。
   需要新的公共能力（公共表头/行项字段基类、快照写入、数据范围、公共查询壳、公共 Controller 骨架）→ **先报文 captain**，由 backend-base 补进 `base` 包；
   **绝对不要**在自己的包里另写一份状态机、金额口径或数据范围。
   各组的 domain / mapper / service / controller 写在自己的包（`posting` / `procurement` / `sales` / `stockops` / `ledger`）。
   ⚠ 公共层仍在演进：若构建因 `base` 包正在改动而失败，报文 captain 后重试（最多 3 次），**不要**改别人的文件。

## 4. 验证模型（本批次唯一口径，别按 OpenSpec 原文的"接口测试"字面各自重启后端）

OpenSpec 的「验证方式」里大量写"接口测试断言…"。**在本批次里按下述分层执行**，避免 10 个成员各自打包重启互相打断：

| 层 | 谁做 | 怎么跑 | 说明 |
| --- | --- | --- | --- |
| L1 单元测试（必做） | 每个组自己 | `mvn -B -pl ruoyi-ctms test`（`-LockName build`） | 内存桩 Mapper，覆盖状态机/精度/守卫/下推/过账规则；**任务完成的必要条件** |
| L2 静态审计（必做） | 每个组自己 | `node tools/audit/run-all.js`（10 个，只读，可并发） | 前端页面/未声明写入/主题令牌/静默 catch 等 |
| L3 接口级断言（统一窗口） | T10 集成 + T11 E2E | 后端**统一打包重启一次**后跑 `tools/erp-*-check.ps1` 与 Playwright | 你的组必须交付**可复跑的断言脚本或逐条 curl 序列**（请求 + 期望），并在 notes 里登记 |
| L4 端到端（验收） | T11 | `cd ruoyi-vue-oa-ui-master\tests\e2e ; npx playwright test` | 用户明确要求：**功能完成后必须通过 E2E**；失败项由 captain 派发修复任务 |

**任务完成判据**：L1 + L2 真实输出（含断言条数）+ L3 断言清单 + notes 交付记录。
**不得**以"编译过了""代码看起来对""接口应该没问题"当完成；**不得**为了让门禁变绿而放宽断言或删除用例。

## 5. 环境与常用命令（详见 DEV-ENV.md §4；本机 `powershell` = 5.1，门禁一律用它）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1      # 幂等起环境：MySQL/Redis/RabbitMQ/后端 8080/前端 80
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1 # 取 token（会过期，重启后端后必跑）

# 后端
cd ruoyi-vue-oa-master
mvn -B -pl ruoyi-ctms test                     # 模块单测（B3 基线 217 条，只可增不可减）
mvn -B -pl ruoyi-ctms clean install            # 打包前装本地仓库（DEV-ENV §6.13）
mvn -B -DskipTests -pl ruoyi-admin clean package
# 启动用 D:\Program Files\Java\jdk-11\bin\java.exe -jar ruoyi-admin\target\ruoyi-admin.jar（脱离进程启动）

# 前端
cd ruoyi-vue-oa-ui-master
node tests\run.js                              # 前端单测（当前 111/111）
npm.cmd run build:prod                         # 生产构建
cd tests\e2e ; npx playwright test             # 浏览器 E2E（复用本机 Chrome，独立 package.json）

# 门禁（现有基线，跑之前先看 HANDOFF §3.2：flow-regression 有既有失败）
node tools\audit\run-all.js
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-commercials-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1
```

> **captain 实测基线（2026-10-05 22:38）**：`mvn -B -pl ruoyi-ctms test` = **217 条全绿 / BUILD SUCCESS / 约 11 秒**。
> 即 L1 单测很便宜，可以放心频繁跑（仍要走 `-LockName build`）；本机 `.m2` 已装 `ruoyi-ctms-1.0.1.jar`（B3 构建，17:04），
> 所以 `mvn -B -DskipTests -pl ruoyi-admin clean package`（不带 `-am`）能直接解析 ctms 依赖 —— 这是 qa-hardening 在 ctms 正在施工时验证流程侧的正路。

- 账号：`superAdmin` / `admin123`（只有它能绕开 §3.9.5 的零菜单问题）；MySQL `ruoyi/ruoyi`，库 `rad_oa`。
- DB 直连：`F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe -uruoyi -pruoyi rad_oa`（**注意是 `server\bin`，不是 `bin`**；`authz-check.ps1` 用的是同一路径）；
  含中文的 SQL 用 `--default-character-set=utf8mb4` 且避免 PowerShell 管道喂给 mysql.exe（中文会变 `?`，DEV-ENV §6.33）。
  > **captain 实测（2026-10-05 22:40）**：`t1-base` 的 DDL **已落库 rad_oa**（18/18 张表齐备，表名与冻结一致），
  > 字典类型 `stock_biz_types` / `stock_in_types` / `stock_out_types` / `stock_take_types` 已在 `sys_dict_type`。
  > ⇒ 需要**表结构**即可开工的组（前端 t9a、以及后续各组）不必再等。
- 验收脚本有运行锁，**不可并发**；借用角色改授权/数据范围必须"入口自愈 + 收尾还原 + 哨兵"（DEV-ENV §6.48）。

## 6. 汇报协议

- 任务完成：`agent_teams_update_task` 提交（status=completed + output 摘要 + 证据入口），再 `agent_teams_send_message` 给 captain 一条简短汇报（任务号 / 改了哪些文件 / L1+L2 结果 / 待 T10-T11 复核的断言 / 已知边界）。
- 被别人的代码打断构建：报文 captain，含**文件路径 + 原始报错**，不要自己修别人的文件。
- 需要改共享文件（`初始化-全部.sql` 以外的编排、`DEV-ENV.md`、`tools/*.ps1`、`tests/run.js`、`package.json`）：先报文 captain 拿许可。
- **发现规格自相矛盾或无法满足**：立刻报文 captain，不要静默降级或绕开断言。

---

## 9. 冻结口径（captain 裁决 · 2026-10-05，所有组必须一致，**不得各自发明**）

### 9.1 单据单号 = 平台编号服务（`ICodeGenService`），禁止各自算号

- 8 类单据单号**统一走 `ruoyi-serial` 的 `ICodeGenService.getNextCode(confId, ctx)`**（铁律 2：编号一律复用平台能力）；
  **禁止**任何组写"前缀 + 库内最大号 +1"。
- 由 **backend-base** 在 `sql/二开-进销存.sql` 补 8 条 `t_code_config` + `t_code_config_rule` 种子
  （仿 `sql/二开-合同台账-编号配置.sql`：`title` / `current_seq` / `enable_flag` / 规则行 `rule_type`/`rule_value`/`pad_zero`/`seq_reset_type`/`sort`），
  并提供公共实现 `com.ruoyi.ctms.erp.base.ErpDocNoGenerator`（注入 `ICodeGenService`，暴露 `nextDocNo(ErpDocType)`，含单测）。
  各组**只注入调用**；base 尚未就绪时先留注入点，不要自写实现。
- 前缀与格式（与 `参考仓库-CTMS-移植清单.md` §3.6 B 一致）：`PR/PO/SR/SO/IN/OUT/ST/DB`，`{prefix}{yyyyMM}{seq:6}`，**按月重置**。
- 实测事实：`t_code_config` 现有 2 行（`82A6…` 测试编号、`9F2C…C001` 合同编号配置）；B4 用 `9F2C…C101~C108`。

### 9.2 权限点命名（真源：PRD §7.5 资源前缀 + design D6 / tasks 8.1 动作集，**取并集**）

- **动作集（冻结）**：`list / query / add / edit / remove / status / submit / approve / unapprove / void / push / export / print`
  —— **驳回与审核共用 `approve`**；`publish`/`import` 属流程域，单据不使用。
- 资源前缀：采购 `pur:`、销售 `sal:`、库存 `stk:`（B3 合同域已用 `ctms:`，**保持不变**）。
- **冻结表**（`t8-perm-scope` 按此生成 `sys_menu`；各组 `@PreAuthorize` 按此写）：

| 对象 | 资源 | 权限点 |
| --- | --- | --- |
| 采购申请单 | `pur:request` | `pur:request:<动作>` |
| 采购单 | `pur:order` | `pur:order:<动作>` |
| 销售申请单 | `sal:request` | `sal:request:<动作>` |
| 销售订单 | `sal:order` | `sal:order:<动作>` |
| 入库单 | `stk:in-order` | `stk:in-order:<动作>` |
| 出库单 | `stk:out-order` | `stk:out-order:<动作>` |
| 调拨单 | `stk:transfer` | `stk:transfer:<动作>` |
| 盘点单 | `stk:take` | `stk:take:<动作>`（REST base 亦为 `/stk/take`） |
| 库存明细/结存 | `stk:stock` | `stk:stock:list` / `:query` / `:export` |
| 库存流水 | `stk:ledger` | `stk:ledger:list` / `:query` / `:export` |
| 库存重算（运维） | `stk:stock` | `stk:stock:recalc` |
| 库存主数据（B4 只补 C 菜单） | **沿用 B3 既有** `ctms:partner:list/query/add/edit/remove` | B4 新增 4 行 C 菜单；F 复用 B3 现有行；**不新增** `stk:product*` 点 |

> **实测现状（captain 2026-10-05 23:0x，backend-ledger 查库核对）**：`sys_menu` 有 26 条 `ctms:*`、`pur:/sal:/stk:` 现为 **0 条**（B4 新建）。
> **库存主数据的真源注解是 `ctms:partner:*`**：`CtmsProductMasterController.java:49` 为 `@RequestMapping("/ctms")`，其 20 个端点全部
> `@PreAuthorize("@ss.hasPermi('ctms:partner:…')")`；前端亦按此冻结（`src/views/erp/erp-const.js:36` `MASTER_PERM_PREFIX='ctms:partner'`）。
> ⇒ **裁决（2026-10-05）**：主数据菜单**沿用 `ctms:partner:*`**，B4 只新增 C 菜单行（零代码改动）；
> **不新增** `stk:product*` 端点/权限点（那会变成第二套档案接口＝违反铁律 2），**也不给** B3 端点补注解（会打挂 B3 的 `authz-check` 45 条与现有角色）。
> 变更本冻结表必须报文 captain；某组自行改名导致 t8 对不上＝该组返工。
> **补充冻结**：盘点单 REST base `/stk/take`（与参考仓库 `stock.take`/`/api/stock/takes` 及前端 `doc-kinds.js` 已写口径一致）；
> 冻结动作集之外的额外动作（例：盘点"生成行项"）**不得自创命名** —— 并入 `add`/`edit`，或先报文 captain 裁决。
>
> **2026-10-05 追加裁定（captain，解决跨组对不上）**：
> - **"置为已完成"（complete）用 `:status`** —— `status` 本就在冻结动作集内；已落地的采购/销售控制器用的就是 `:status`，是**菜单侧漏了 F 行**。库存类单据无 complete，不生成该点。
> - **下推（push）挂在"来源单据"上**：`pur:request:push` / `pur:order:push` / `sal:request:push` / `sal:order:push` 四个点都要有；盘点单**无 push**，`stk:in-order` / `stk:out-order` **无 push**（它们是下推目标）。
> - **采购 REST base 保留已落地的 `/erp/pur/*`**（PRD §9.4 的 `/pur/*` 是示例路径、非验收项；由前端配置层适配），销售 `/sal/*`、库存 `/stk/*` 与 PRD 一致。

### 9.4 导出响应形态（captain 冻结 · 2026-10-05，由 t27/t28 落地）

- 端点：`GET|POST {restBase}/export`（**前端实际用的是 POST**）。
- 依据（先读前端再定形态）：`src/api/erp/doc.js#exportDocs` → `src/utils/request.js#download` = **POST + `application/x-www-form-urlencoded` + `responseType:'blob'` + `saveAs`**，且 `blobValidate = data.type !== 'application/json'`。
- **成功 = xlsx 二进制流**（`ExcelUtil`）；**失败（含 403）= `application/json` 的 `{code,msg}`**。
- **两边都不许单方面改形态**：后端改成 JSON `.rows`、或前端改成按 JSON 解析，都会重现 B3 那个"点导出报错/没有可导出的数据"的坑（`oa-contract-ledger` 的实测教训）。
- 参数用**普通命令对象**（**不是** `@RequestBody`），使 GET/POST 都能绑定；导出必须与列表**同筛选同数据范围**（复用同一查询入口，含 `dataScopeSql`）。

### 9.5 数据范围矩阵脚本（captain 批准 · 2026-10-05）

- 允许**新建独立脚本** `tools/erp-scope-check.ps1`（不并进 `authz-check.ps1` 的 45 条基线、也不塞进 `erp-check.ps1`），因为它自带"借用角色改授权 + 收尾还原 + 哨兵"的重纪律（DEV-ENV §6.48）。
- 必须：入口自愈 + 收尾还原 + 哨兵；四档（本人/本部门/本部门及下级/全部）×（单据/结存/流水）×（列表/详情/导出）+ 多角色并集 + 范围外 403；跑完把脚本登记进 `DEV-ENV §7` 门禁表并纳入 t13 的门禁全表。
- **⚠ 实测更正（t35）**：该脚本**内部没有运行锁**（与 `ctms-*-check.ps1` 的 `.cache\<脚本>.lock` 不同）⇒ **必须**经 `tools\locked-run.ps1 -LockName env` 串行执行（它会借 `common` 角色改授权与 `data_scope`，并发会互相覆盖并留残留）。

### 9.7 数据库基线口径（captain 裁定 · 2026-10-05）

- **各脚本/用例只清自己的"前缀夹具"**（如 `FIX-` / `T26` / `E2E4` / `T10S`），**不得**去删别人的在飞数据——误删会制造假红/假绿（与 D-12「静默失败」同族风险）。
- **全库基线归零只在集成窗口做**（t13）：由 t13 在跑门禁全表前记录一次"全库 B4 业务表 + 主数据计数"，跑完再对一次，差异逐条给出归属。**不要把"全库为 0"当成本批的交付前提**。
- **历史遗留不要动**：`t_ctms_customer` / `t_ctms_supplier` 里 `code='1'` 的「测试客户1 / 测试供货商1」创建于 **21:38/21:39（早于本批团队启动 22:32）**，属历史遗留，**不算残留、不清理**；E2E/门禁的断言一律只针对各自前缀。
- 清理顺序铁律：**子表 → 父表**（9 张行项表 → 结存/流水 → 8 张单据表头 → 主数据档案）；先删主数据会撞 `FK 1451` 并留下孤儿单据（本轮已实测踩到）。

- 本批 `DEV-ENV.md` 曾出现**并发写**（backend-ledger 的 t10 +110 行 @23:49:36，随后 t35 在其后追加 +72/−1；`git diff --stat` 已核对：唯一删除行是 §7 第 14 条原行，无其它章节受损）。责任在 captain（我先后授权了两方）。
- **新规矩**：`DEV-ENV.md` 由**单一写者**维护；谁要改先报文 captain，由 captain 派单，**不得并发写**。本批写者收编为 **t35（已完成）**；后续（如 t13 复核门禁表后的微调）**必须再走一次派单**。
- 现状：`DEV-ENV.md` **无 BOM**（`.md` 规矩；captain 已复核首字节 = `23 20 52 75`）；`§6` 顺延到 **第 60 条**；`§7` 门禁表共 **19** 条（第 14 条后端单测 174→**535**；第 16 条 `erp-scope-check`；第 17/18/19 条 `erp-smoke` / `erp-check` / `flow-form-consistency-check`）。

| # | 事项 | 现状与处置 | 归属 |
| --- | --- | --- | --- |
| D-1 | 变更历史写入器**同表两套**：`posting.support.ErpStockChangeLogWriter` 与 `procurement.support.ErpPurChangeLogWriter` | 字段口径一致、各自有单测；**第三方不得再写第三份**（T6 必须复用 posting 那份）。是否收口到 `base` 待集成期定 | 集成期 |
| D-2 | `二开-合同台账.sql` 的 ⑦-3/⑦-4 用 `table_name LIKE 't_ctms\_%'` 而非**精确 13 表白名单**（违反 HANDOFF §6.35 的既有口径） | 当前不可达：在 B4 已装库上单独重跑 B3 脚本会在 ⑥-1 DROP 段 `ERROR 3730` 中止，走不到自检 ⇒ 无误报。**t13 集成前决定是否收窄**（收窄属 B3 文件，需 captain 授权） | t13 |
| D-3 | `flow-regression` 20/20 **依赖 t15 的可回滚恢复动作**（6 行 `del_flag` 临时 0 → 应用 publish 重发布 → 已还原为 1；`ACT_RE_*` 副产物保留） | 测试流程/定义的**最终清理属用户待决策项**（HANDOFF §3.9.8 第 1 项），本批不替用户决定；t13 跑门禁前需先用 `ACT_RE_PROCDEF` 清点还剩哪些定义 | t13 |
| D-4 | RuoYi 认证失败 = **HTTP 200 + body `code=401`**（`AuthenticationEntryPointImpl.java:26-33`） | 已写进 `DEV-ENV.md §6.56`；**所有** 用 `Invoke-RestMethod` 的验收脚本都应判 `code`，不得只看异常。已发现 `flow-regression`、`ctms-attachment-check` 同族，其余未普查 | t13 普查 |
| D-5 | 进销存单据的**打印数据源未接线**（`getPrintTemplate` 契约"含系统默认、永不返回空" ⇒ "无版式"不是可观察状态；且 ERP 单据 id 未绑定 workflow `businessId`） | **口径（2026-10-05 captain 修正）**：不新增接口；t12 在壳里用**静态** `printReady:false` 一处判定 → 打印入口 `:disabled` + `title="打印未接入（待集成期接线）"`，**不得静默失败**。本期**不交付可用的打印数据源**，集成期（t13）再核"ERP 单据 → businessId"接线并把 flag 翻 true；最终交付如实报用户 | t12 / t13 |
| D-6 | **既有平台缺陷**：同父菜单下点侧边栏兄弟页时 URL/面包屑变化但 `.app-main` 内容不切换（B3 页面同样复现，非本批引入） | 不影响交付，但 **E2E 一律用 `page.goto`，禁止侧边栏点击导航**；最终交付单独报给用户，不在本批修 | t14 / 交付说明 |
| D-7 | `/stk/take`、`/stk/transfer` 每个动作**同时注册两种 URL 形态**（`POST {base}/{id}/{action}` 与 `POST {base}/{action}/{id}`） | 冗余但无害、安全面不扩大；前端已按 kind 正确分流（`doc-kinds.js` 的 `actionRequest.mode`），**不为此改回**；由 t13 复核时确认两种形态行为一致 | t13 |
| D-8 | `ruoyi-template` 的 `TemplateUpdateSemanticsCheck` 桩漏实现 3 个方法，导致**该模块 test 阶段一直编译不过**（单模块打 fat jar 走本地仓库 jar，谁也跑不到） | 已由 t16 补齐并记入 `DEV-ENV §6.57`；规则：**改了哪个模块就对哪个模块跑 `mvn test`** | 已闭环 |
| D-9 | t30 复核 low-①：`ErpMasterGuardsGeneratedPathTest` 的 javadoc 称反射断言覆盖"本类与**两个单据服务**"，实际只反射了 `ErpMasterGuards`/`MasterRecord`（**行为无风险**，仅覆盖面描述偏大） | 由 t13 集成复核时决定：补两行反射或收窄 javadoc 措辞；**不单开任务** | t13 |
| D-10 | t30 复核 low-②：模块内还有**第二处**先于 t23 的同类豁免 `ErpStockOpsRules.applyStocktakeItemSnapshot:233-255`（盘点行项专用、`219-224` 有说明、不经 `ErpMasterGuards`、非全局开关），**本次未深审** | t13 复核时确认它与 t23 的豁免边界不重叠、且不是"绕过严格路径"的第二个口子 | t13 |
| D-11 | **`posted` 歧义 getter 为什么 531 条单测全绿也漏掉**（三条并存）：① 单测用 stub mapper、不走 XML/OGNL；② **MyBatis 版本偏斜** —— 模块测试类路径解析到 **3.5.13**（容忍这对歧义），而打包运行件里是 **3.5.7**（不容忍，已从 `ruoyi-admin.jar` 抽出实测）；③ 只有含 `posted` 的语句受影响。**且异常不是建 `Reflector` 时抛**：MyBatis 惰性包成 `AmbiguousMethodInvoker`，**真读该属性**（OGNL，触发点 `ErpPurRequestMapper.xml:116`）才抛 ⇒ **captain 原定的"`new Reflector(clazz)` 断言不抛"是无效口径**（修不修都绿）；有效守卫必须**逐个读取全部属性**（1306 个 + `SCAN_FLOOR` 防"扫不到也绿"）。受影响 **7 类**（`ErpDocHeader` + 采购2 + 出入库2 + 盘点/调拨2）；**销售两单不受影响**（`extends BaseEntity` 且自带 String `posted`）。 | 已由 t31 闭环（回归网修复前 4 run/3 fail → 修复后 4/4；同族扫描 924 类：7 → 0）；机制交 t35 写 DEV-ENV §6 |
| D-15 | **验收口径本身必须能复现缺陷**（本次实例：我给的"构建 Reflector 断言不抛"是**装饰性**口径；成员换成"读遍全部属性"后才真正复现） ⇒ 今后凡"回归网 / 守卫测试"，必须给出**"改回缺陷即变红"的反向对照**，否则视为无效证据 | 长期纪律；交 t35 写 DEV-ENV §6（已完成） |
| D-16 | **建单路径是否单事务（表头 + 行项）**：t34 的 D-16 探针已证「带行项 → 表头+行项都落」「`items=[]` → 业务拒绝（`单据至少需要一行行项`）且库内 0 行」⇒ 至少是**先校验后写入**。**但"业务校验提前拦下"≠"写入失败会整体回滚"**，仍需一次**失败注入**（行项 `productId` 指向不存在物料让 INSERT 撞 FK）断言 **表头行 = 0** 才算闭环 | 归 **t34**（stockops-integrator）补做那次失败注入并写进 `13a` §D-16；若表头留下半截单据 → 立即派 repair（与 F-2「建单必须事务」同类） | t34 |
| D-21 | **探针自身会制造"假缺陷"**：`.cache\t34\d16.txt` 的 C1 报「GET 详情 → 500」，而 captain 亲测 `GET /erp/pur/request/{id}` = **200 操作成功**（Controller 是 `@GetMapping("/{id}")`；`/detail/{id}` 才是 404）⇒ 该"详情坏了"的记录**不成立**，是探针用了不存在的路径 | 已要求探针作者更正 13a 记录；**纪律**：凡"接口 500/404"类发现，交付记录里必须附**完整 URL + 原始 body**，否则不得写成缺陷 | 已闭环 |
| D-22 | **MySQL 不允许在同一条语句里两次引用同一个 `TEMPORARY` 表**（`ERROR 1137 Can't reopen table`）：`tools\erp-check.ps1:271` 建的是 `CREATE TEMPORARY TABLE t_erpchk_docs`，清理语句里若有"同时按 `doc_id` 与 `tbl` 匹配"的写法就会撞 | 三选一：**(a) 弃用临时表**，用已有 `IdListCsv` 拼 `IN (...)`（captain 推荐）；(b) 改普通表 + 收尾 `DROP`（须让 `CLEAN-01` 覆盖它自己）；(c) 保持临时表但每条语句只引用一次 | **已由 t34 修复（`CLEAN-01` 转 PASS）**；坑本身交 t13 写进 DEV-ENV §6 |
| D-23 | **URL 里裸拼非 ASCII 参数值 ⇒ 请求根本发不出去**：`tools\erp-check.ps1` 的 `P-12`/`P-13` 用 `?reason=录错数量` / `?reason=重复`，表现为 `code=-1` 且 `status/posted/qty` **全空**（说明拿到的不是业务响应），而接口契约本身是对的（`POST /stk/in-order/unapprove/{id}?reason=`，Controller:252-259）。**判定实验**：把 `reason` 换成 ASCII 重跑——变绿即脚本编码问题 | 修法：`reason` 用 ASCII，或显式 `[uri]::EscapeDataString(...)`；**纪律：交付记录里"接口失败"必须附完整 URL（已编码）+ HTTP 状态码 + 原始 body**，否则不得写成缺陷 | 归 **t34**；坑交 t13 写进 DEV-ENV §6 |
| D-24 | PS 5.1 下用 `System.Net.Http.HttpClient` **必须先 `Add-Type -AssemblyName System.Net.Http`**，否则 `Cannot find type [System.Net.Http.HttpClient]`（t34 并发段 `P-EXC` 的真实报错） | 修法：先 `Add-Type` 或用 `Invoke-RestMethod`/`WebClient`；并发段失败必须**收敛成"哪个请求/什么状态/原始异常"**的可诊断记录 | 归 **t34** |
| D-25 | `tools\run-db-sql.ps1 -Files` 是按仓库 `sql/` 目录解析**相对路径**的 ⇒ 临时 SQL 给相对路径会报「找不到 SQL 文件：…\sql\.cache\…」，**必须给绝对路径** | 交 t13 写进 DEV-ENV §6（脚本类坑） | t13 |
| D-26 | **"测试声称锁了契约、实际不能因回归而变红"是一类系统性风险**（本轮已出现四处：F1 `@Excel` 注解未被执行、F2/D-19 参数注解断言缺失、O1「不过滤状态」无作废行种子） | 已合并进 **t40** 一次改完，且**每处都要求"改回缺陷即变红"的反向对照**；t41/t44 复核以此为 pass 的硬条件。纪律同 D-15 | t40 已完成；**t41/t44 复核确认三处反向对照全部真的会变红**（含 ②b 证明"修补前拦不住"） |
| D-27 | **captain 裁定：t44 的 1 词差「免修、登记归档」**。t43 在"只改 notes"的文档侧修复中，**顺手把 `ErpSalesExportTest:627` 断言失败消息里的一个字从 `带有` 改成 `存在`**，而 notes §11.7.3 又声称"仓库里没有任何测试代码变更"⇒ t44 用 **artifact 时间线**（`contrast3b.log` 00:12:56 早于 t43 首次写仓 00:14:01）证明该陈述不实。**严重度 low**：只落在失败消息的字面量，`assertNull(...)` 的判定逻辑与红/绿结论**完全不变**。 | **裁定：不为此再开一轮修复+复核**；t44 的独立核对作为该问题的权威证据归档，本表 + 最终报告如实登记"已知 1 词差"；**纪律**：派"文档侧修复"任务时必须写明"**禁止顺手改任何测试/产品代码**" | 已闭环（裁定归档） |
| D-28 | **"过时证据"是本批第三次伤人的同一族**：① D-21 用不存在的 `/detail/{id}` 路径写出"详情 500"；② D-23 我基于"中文未编码"的猜测方向（被 `urlprobe` 证伪）；③ **t14 的 P-③ 引用了 t24 期间（≈23:2x）读到的 `doc-rules.js:129/:154 pending:true`，而 t32 已在 23:38 修掉** ⇒ captain 复核后用"重读当前源码"推翻，qa-hardening 已撤回并写入 `notes/10-e2e.md §7` 更正（原文保留可追溯），并自记教训"**引用行号前必须重读当前文件**" | **纪律**：任何"缺陷/阻塞"结论必须由**当前时刻**的证据支撑（重读文件 / 重打接口 / 看 artifact 时间戳）；**引用他人或自己早前的行号=不可作为派单依据**。与 D-15（反向对照）、D-21/D-23（附完整 URL+body）同族 | 已闭环（更正入档） |
| D-29 | **t13 报告里的 "D-1"（与本表 D-1 同名，实为两件事）**：`/sal/request/list` 500 `ErpSalesRequestItem cannot be cast to java.util.List`。**根因**：`ErpSalesRequestItemMapper` 把 `@MapKey("docId")` 标在 `Map<String, List<ErpSalesRequestItem>>` 上 —— `@MapKey` 契约是"一行→一个值"（`map.put(k, 行对象)` 擦除后**不抛**），与 value 声明为 `List` 冲突 ⇒ **调用方那次泛型读**（`ErpSalesRequestServiceImpl.applyDerivedColumns:170`）被编译器插入 `checkcast java.util.List` 才炸。**为何 smoke 与 erp-check 打架**：该方法在"页内无行"时直接 return ⇒ 表空（S03）绿、建单后（S-A6）红；`/sal/order/list` 当时 total=0 **掩盖了订单侧同一缺陷**（订单 mapper 一样错）。 | 已由 **t45** 闭环：mapper 返回平铺 `List` + 服务层 `LinkedHashMap`+`computeIfAbsent`（**不 N+1**、SQL 未改）；jar 00:43:33 **字节级证明**嵌套包常量池已无 `MapKey`；`clean install` **547/547**；`erp-smoke -Strict` **27/27**（S03 500→200）；S-A6 红→绿；**有数据实证**（`total=1`、`remainQtySum=3.000 canPush=True totalAmount=0.39`）。新增 `ErpSalesItemBatchQueryContractTest` 4 条（含通用规则"`@MapKey` 值不得是集合/数组/Map"）+ 两处反向对照 | 已闭环 |
| D-30 | **F-01（blocker，由 t18 终验发现）**：`GET /stk/stock/list?productTypeId=<父类型>` **不含子树**（退化成精确匹配）。根因：`ErpStockLedgerQueryServiceImpl.java:136` 的 `expandProductTypes` 用 `productTypeMapper.selectProductTypeList(...)` 拉"类型清单"，而控制器 `ErpStockBalanceController:70-74` 已 `startPage()` ⇒ **辅助查询被 PageHelper 截断成前 pageSize 行** ⇒ `ErpProductTypeTree.subtreeIds` 只见部分类型。**定量证明**：同一查询 `pageSize=1/10/20 → total=0`、`47/100/500 → total=1`（类型表 47 行）。**影响**：前端默认 `pageSize=10` ⇒ 客户类型超过 10 行时"按商品类型（含子树）筛选"**静默失效**，与 `notes/07-ledger §7.1` 冻结口径（选父带子）直接冲突。 | 归 **t54**（backend-ledger）修（移出分页上下文 + 服务层回归断言 + 反向对照）。**同时暴露 F-02**：`erp-check` 的 L-06 判据会**随库规模翻转**（t50 六连 PASS 时类型表小、`E2E4%` 累积到 47 行后必红）⇒ 判据须改成与页大小无关（如 `total(parent) ≥ total(leaf) > 0` 或双 pageSize 对拍） |
| D-30a | **captain 授权（2026-10-06）**：允许 t54 **只改 `tools/erp-check.ps1` 里 L-06 那一个 `Case`**（其它一行不碰），判据改为 **`pageSize=10` 与 `pageSize=500` 双跑对拍**：父类型 `total` 两次一致、且 `total(父) ≥ total(子) > 0`；**失败时必须打印可观测量**（两档 total、类型表行数、父/子类型 id 或 code）；注释写明纪律"**门禁判据不得依赖库规模/夹具位置**"（F-02 教训）。**修复方向已认可**：子树展开移到 `startPage()` **之前**（新增 `prepareQuery(query)`，Controller 与 `export` 同口径），并要求给该新契约写 javadoc 说明"必须在 `startPage()` 之前调用"及其违反后果（即 F-01）。t54 已在 59 行类型表上复现（`pageSize=10 → 0`、`500 → 1`） | 执行中 |
| D-31 | **F-03（low，证据卫生）**：t53 引作证据的 `tests/e2e/.last-run.json` 在盘上**不存在**（t18 复跑后也未生成）⇒ 交付记录引用了不存在的文件。另 F-04/F-05：`related-approval-check` 实跑 **15/8**（B 系列、非本批面）、`sign-feature-check` 42/1 确为 **AC-26 夹具缺失**（意味着 AC-26 实际**未验证**，不得计为通过）。 | **纪律**：artifact 路径必须"引用前存在性核验"；B 系列与 AC-26 登记归属、不计入本批通过率 | 已登记 |
| D-32 | **F-07（low，交付缺陷）**：t18 判两条接口一致性观察为低危交付缺陷——① 采购线创建把新单 id 放 **`msg`**（`data` 空）；② 采购下推端点**只接受裸数组**。**F-08（非缺陷）**：盘点遇负账面**整单失败**判为 AC-75 的原子性取舍，**不是缺陷**，只需登记口径 + 保证文案可读。 | F-07 待派规范化（保留一版兼容）；F-08 登记口径即可 | 待派/已登记 |
| D-17 | `warehouse_name` 的精确 DDL：表头 `warehouse_id varchar(64) NOT NULL + FK`、`warehouse_name varchar(64) NOT NULL DEFAULT ''`（**快照列、无 FK**）；行级两列均可空。⇒ MyBatis 显式写 `#{warehouseName}=null` **不走 DEFAULT**，这才是 `Column 'warehouse_name' cannot be null` 的根因（captain 此前口头写的"两个都 NOT NULL+FK"不精确，t33 直查 `information_schema` 更正） | 已由 t33 修复（只给 id 时服务端回填档案名快照）；口径以本条为准 | 已闭环 |
| D-18 | 「表头仓库必填」的中文文案在 `ErpStockInServiceImpl` 与 `ErpStockOutServiceImpl` 里各有一份**私有常量**（t33 原本想放 `ErpStockRules`，但被 inScope 范围校验拒），靠单测逐字锁定防漂移 | **本期不改源码**——现在动 posting 源码会让 t34 刚跑出的冒烟证据失效并需要再打包一次；若 t13 的集成窗口有余量，可在**最终那次打包之前**收口为单一实现点（`ErpStockRules` 或 base 的"表头仓库守卫"），否则留作交付后清理项 | t13（有余量时） |
| D-19 | t36 复核 L1：导出端点的测试断言了 GET+POST / 权限点 / 路径，但**没有断言"参数上没有 `@RequestBody`"**（复核方用反射探针补证为 false）。⇒ 将来有人随手加 `@RequestBody`，GET 会**全失效而测试仍绿** | 低风险但属"测试没牙"类；**本期不新开任务**（避免在收尾期动源/测试并重跑）。若 t13 集成窗口有余量，在 **procurement 与 sales 两边**的 `*ExportTest` 里各补一条参数注解断言；否则留作交付后清理项 | t13（有余量时） |
| D-20 | t36 复核 L2：`ErpPurRequestExportRow.of(null)` **静默返回空集**；若 `selectRequestDetail` 哪天退化成"返回 null 而不抛 403"，导出会变成"HTTP 200 + 空 xlsx"——**不泄露数据但静默失败**（与 D-12「静默失败」同族） | 当前真实行为是抛 403（复核已确认），故属**防御性**问题；处置同 D-19（t13 有余量时补"详情为空即抛业务错"的守卫），否则留作交付后清理项 | t13（有余量时） |
| D-12 | **前端"默认导入但模块只有命名导出"⇒ `api===undefined` ⇒ 请求根本没发出、页面静默空表**（自 t11 潜伏的 high 缺陷，8 个文件；浏览器只读实测才发现） | 已由 frontend-erp 在 t12 修复（改 `import * as api`）并新增**静态门禁「api 导入形状」+ 反向对照**；教训与 D-11 同族：**"没发出请求"和"后端没返回"在界面上长得一样** | 已闭环 |
| D-13 | `DEV-ENV.md` §7 第 14 行仍写"后端单测 **174 条**"，实测已 **535 条**（B3 期计数过时） | 属门禁表维护，交 **t13** 一并更新（连同本轮新增的 `tools/erp-scope-check.ps1` 第 16 条复核）；**其他人不要改 DEV-ENV** | t13 |
| D-14 | **用不带 BOM 的写法编辑 `.ps1` 会剥掉 UTF-8 BOM**，PS 5.1 立刻按 ANSI 读 ⇒ 中文乱码、引号被破坏（`Missing ] at end of attribute or type literal`） | 已由 backend-ledger 实测踩到并修复（用 `UTF8Encoding($true)` 加回）；已写进 `DEV-ENV §6.59`。**本轮它又打在了工具自己身上**：`tools\erp-check.ps1` 在 00:02 半写状态丢 BOM ⇒ `Missing closing ')' ... erp-check.ps1:65` ⇒ **那一批 acceptance 输出（7 通过/6 失败，含 P-EXC 的 `WaitAll` 错误）全部作废，不得计入结论**（captain 已复核：当前两脚本 `BOM=True` 且 `Parser::ParseFile` 解析错误 0）。**纪律：任何一次"结果"之前，先给"前三字节 + Parser 自检"两行自证** | 已闭环（口径强化）；t34 需按此重跑 |
