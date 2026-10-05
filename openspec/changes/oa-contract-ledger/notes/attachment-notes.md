# B3 §6 附件与操作日志接入 —— 交付说明

> 变更集：`oa-contract-ledger`｜任务：tasks.md §6.1~6.4（design D-5 / D-10）
> 交付时间：**2026-10-05**｜后端 jar `2026-10-05 14:00:06`
> 验收资产：`tools\ctms-attachment-check.ps1`（**69 条断言，连跑两次残留 0 行**）+
> `CtmsAttachmentServiceImplTest`（18 条）｜设计输入：`notes/attachment-reuse.md`（§1.5 实测）

---

## 1. 一句话结论

上传/存储/随机命名/**取回**整体复用平台 `ruoyi-file`（一行不改 `ruoyi-common`），
B3 只新建**业务语义层** `t_ctms_attachment`（第 2 组已建表）+ `CtmsAttachment*` 四件套；
平台给不了的四件事全部自建：**更窄白名单、≤20MB + 413、按对象鉴权、删除留痕**。

**本组新增 1 个权限点** `ctms:attachment:list`（菜单 SQL 25 → **26** 个 `ctms:*`，
菜单行 26 → **27**），后端 `@PreAuthorize` 与菜单 SQL 逐字一致（任务 9.1 由此从 25 改判 26）。

---

## 2. 对象类型注册清单（任务 6.4 的核对项，与常量逐项一致）

常量真源：`ruoyi-ctms/.../support/CtmsAttachmentObjectTypes.java`

| 对象类型 | 常量 | 目标表 | 状态 | 对象存在性校验落点 |
| --- | --- | --- | --- | --- |
| `contract` | `CONTRACT` | `t_ctms_contract` | **已注册（B3）** | `ICtmsContractService.checkContractAccess` |
| `purchase_request` | `PURCHASE_REQUEST` | `t_ctms_purchase_request` | **已注册（B4）** | `ErpDocObjectAccessServiceImpl`（按类型分派 `ErpDocLookupMapper`） |
| `purchase_order` | `PURCHASE_ORDER` | `t_ctms_purchase_order` | **已注册（B4）** | 同上 |
| `sales_request` | `SALES_REQUEST` | `t_ctms_sales_request` | **已注册（B4）** | 同上 |
| `sales_order` | `SALES_ORDER` | `t_ctms_sales_order` | **已注册（B4）** | 同上 |
| `stock_in` | `STOCK_IN` | `t_ctms_stock_in` | **已注册（B4）** | 同上 |
| `stock_out` | `STOCK_OUT` | `t_ctms_stock_out` | **已注册（B4）** | 同上 |
| `stock_take` | `STOCK_TAKE` | `t_ctms_stocktake` | **已注册（B4）** | 同上 |
| `stock_transfer` | `STOCK_TRANSFER` | `t_ctms_transfer` | **已注册（B4）** | 同上 |

> **本表已对齐：登记清单 = `contract` + 8 类单据 = 9 项，`plannedB4` 为空。**
> （B3 交付时只有 `contract` 一项、另有 5 个"B4 待补"；B4 首轮补到 7 项，t19 按参考仓库
> `app/routers/attachments.py:35-44` 的 `OBJECT_PERMS` 口径补齐两个申请单到 9 项。
> **参考侧 `OBJECT_PERMS` 缺 `stock_transfer` 是它的已知缺陷，我们按 Q-B10 补上。**）
> 计数、逐项清单与取证以 `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md`
> 为准（该文件含 `attachments.py:35-44` 与移植清单/PRD 的行号证据链；接口的原始返回形态见其 §5，
> 其中还包含 `docObjectTypes` 键）。

- 清单只有**一个来源**：`CtmsAttachmentObjectTypes.registered()` /
  `plannedB4()`，`CtmsAttachmentRules` 的注册表直接从它构建（避免两处清单漂移）。
- 前端/运维可查：`GET /ctms/attachment/object-types` 返回
  `{registered, plannedB4, maxSizeBytes, maxSizeMb, extensions}`。
- **未注册类型的行为是"明确拒绝"**：`未注册的对象类型：<值>`（**不是**静默放过）——
  宁可拒绝，也不要产生一条指向不存在对象的孤儿附件。
- **B4 接入时必改三处**：① 本文件常量 + `plannedB4` 挪进 `REGISTERED`；
  ② 服务层 `requireObjectAccess` 补该对象的存在性校验分支；
  ③ 本表补"目标表"与"校验落点"。三处漏一处就会被 `CtmsAttachmentRulesTest` /
  验收脚本的"未注册即拒绝"用例抓出来。
  **（状态：B4 已交付，三处均已全部完成，登记 9 项 —— 对账见
  `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` §3，以该文件为准。）**

---

## 3. 上传限制口径（任务 6.2 的核对项）

| 口径 | 值 | 为什么不能靠平台（`notes/attachment-reuse.md` §2 实测） |
| --- | --- | --- |
| 后缀白名单 | **10 种**：`pdf doc docx xls xlsx png jpg jpeg gif txt` | 平台白名单含 `ppt/pptx/html/htm/rar/zip/gz/bz2/mp4/avi/rmvb` 共 11 种 CTMS 不允许的后缀 |
| 单文件上限 | **20 MB（20971520 字节）**，`> 20MB` 拒绝、`== 20MB` **放行** | 平台是 50MB（实测 25MB 被接受） |
| 超限返回 | **HTTP 413** + body `{"code":413,"msg":"…不能超过 20MB"}` | 平台返回 `code=500` + 中文文案，不是 413 语义 |
| 半成品清理 | 超限**不新增任何文件** | 本实现"先判大小再落盘"，另在落盘后复检 + 清理兜底 |
| 随机文件名 | `yyyy/MM/dd/原名_yyyyMMddHHmmss+4位随机.后缀` | 同名不同次必然不同名 |
| 存储位置 | `profile/upload/**`，落库为 `/profile/upload/...` 相对路径 | 前端拼 `VUE_APP_BASE_API` 取回（DEV-ENV §6.20） |

**两条实现要点（踩过才写下来的）**

1. **不用 `FileUploadUtils.extractFilename`**：它内部 `FilenameUtils.getBaseName()`
   对非 ASCII 文件名会退化成空串，存储名变成 `.pdf`（实测）。改用
   `CtmsAttachmentRules.storedNameOf()`，用 `lastIndexOf('.')` 自己切，字符集无关。
   验收脚本专门有一条"中文主名被完整保留"的断言锁死这一点。
2. **`checkFileName` 必须 `return value`（整个文件名）而不是 `return checkExtension(...)`（后缀）**：
   写成后者时落库的 `file_name` 会变成 `pdf`、存储名丢后缀，而"白名单校验确实生效了"
   所以后缀用例全部照常通过，只有"文件名是否被完整保留"的断言能抓到它（本轮实际踩到）。

---

## 4. 鉴权入口（任务 6.2 的核对项）

| 端点 | 方法 | 权限点 | 数据范围 |
| --- | --- | --- | --- |
| `/ctms/attachment/list` | GET | `ctms:attachment:list` | 对象级 403 + SQL 侧白名单片段 |
| `/ctms/attachment/{id}/download` | GET | `ctms:attachment:list` | 同上（**先鉴权再吐字节**） |
| `/ctms/attachment/upload` | POST | `ctms:contract:edit` **且** `ctms:attachment:list` | 同上 |
| `/ctms/attachment/{id}` | DELETE | `ctms:contract:edit` **且** `ctms:attachment:list` | 同上 |
| `/ctms/attachment/object-types` | GET | `ctms:attachment:list` | —（口径元数据，无业务数据） |

### 为什么写动作要两个权限点

只查 `ctms:contract:edit` 会造出"能上传却看不见"的角色（上传后自己列表里没有），
只查 `ctms:attachment:list` 又不够——附件是合同的一部分，改附件必须能改这份合同。
两个一起要，是**唯一不产生自相矛盾角色**的组合。验收脚本两条都反向对照了：
① 只有合同编辑权 → 上传被拒；② 只有附件查看权 → 上传被拒；③ 两个都有 → 上传 200。

### 数据范围口径

**附件继承它所属合同的数据范围**，判定逻辑**只在合同服务一处**：
`CtmsAttachmentServiceImpl.requireObjectAccess` → `ICtmsContractService.checkContractAccess`
→ `CtmsContractServiceImpl.requireAccessible` → `ContractDataScope`。
附件侧**不自己实现一份范围判定**（复制一份必然漂移）。

- 按标识访问（列表/下载/上传/删除）：范围外 → `ServiceException("无权访问该合同", 403)`。
- 列表另外在 SQL 侧用 `ContractDataScope.buildDataScopeSql()` 的片段对 `contract_id`
  做 `exists(...)` 过滤，与上面的 Java 判定同源。
- `403` 的返回形态与既有 `DocViewGuard` 一致：`{"code":403,"msg":"..."}`（HTTP 仍 200），
  这也是 `tools/authz-check.ps1` 的 `IsForbidden` 所断言的形态。

---

## 5. 删除留痕口径（任务 6.3 的核对项）

| 项 | 口径 |
| --- | --- |
| 元数据 | **软删除**：`del_flag='1'` + `update_id/update_by/update_time` |
| 物理文件 | **保留**（审计优先：元数据是唯一"可见性开关"，删元数据即等于删文件） |
| 变更历史 | 一条 `field_name='附件'`、`source='manual'`、含 `operator_id` + `operator_name` 快照 |
| 历史挂载点 | `contract_id = object_id`（合同附件）；B4 单据附件只写 `object_type/object_id`（`contract_id` 允许为空） |
| 旧值格式 | `文件名（大小）`，例如 `ATC-ok.pdf（32 B）`；新值为空表示该附件被移除 |
| 可查位置 | `GET /ctms/contract/{id}/change-logs`（合同详情的变更历史面板） |
| 删除后 | 列表不再出现；按标识下载返回 `附件不存在`；重复删除第二次拒绝 |

参考侧口径对应：`change_logs` 的特殊字段名集合含 `附件`（design D-10）。

---

## 6. 操作日志（`@Log`）分工

- 附件动作走 `@Log(title = "合同附件", businessType = INSERT/DELETE)` → 落 `sys_oper_log`；
  实测日志行含 `com.ruoyi.ctms.controller.CtmsAttachmentController.upload()`。
- 与 `t_ctms_change_log` 的分工沿用 design D-10：**`@Log` 记"动作"，变更历史记"字段前后值"**。
- 读操作（列表/下载）**不**记操作日志（避免把"看一眼"写成审计噪声）。

---

## 7. 验收资产与证据

| 资产 | 条数 | 覆盖 |
| --- | --- | --- |
| `tools\ctms-attachment-check.ps1` | **69**（真实验境 + 真磁盘 + 真 HTTP） | 6.1 对象挂载/未注册类型/对象不存在/复合索引；6.2 白名单窄于平台（zip/mp4 判别用例）/20MB 双向边界/413/半成品清理/随机命名/中文名/按对象鉴权/数据范围；6.3 删除留痕/删除后不可下载；元数据口径 |
| `CtmsAttachmentServiceImplTest` | **18** | 上述每条的服务层分支 + 文件名完整性 + 中文名保留 + 事务补偿清理 |
| `mvn -pl ruoyi-ctms test` | **174**（156 → 174） | 既有 156 条不回归 |

脚本的三条自证纪律（沿用第 4/5 组）：
① **幂等**：夹具 ASCII 前缀 `ATTC*/ATCUS*`，收尾物理删除，连跑两次残留 0 行；
② **有运行锁** `.cache\ctms-attachment-check.lock`（不可并发，`-Force` 可跳过）；
③ **有区分力**：白名单用例挑的是"平台允许但 CTMS 不允许"的 `zip/mp4`，
否则"白名单比平台窄"这条改动根本不可见。

### 本轮踩到的两个"看起来像功能坏了"的坑

1. **改了 `@PreAuthorize` 但没重新打 jar** → 运行中的后端仍是旧判定，
   症状是"用户明明有 `ctms:attachment:list`，接口还是 403"。
   排查方式：**直接检查编译产物里的字符串**
   （`Select-String` 在 `.class` 里找 `ctms:attachment:list`，找不到就是没编译进去）。
   结论：改权限点/注解后必须走 `-pl ruoyi-ctms clean install` → `-pl ruoyi-admin clean package`
   → 停旧进程 → 起新进程（与 DEV-ENV §6.13 同源）。
2. **只调 `/getInfo` 不总能刷新权限缓存** → 实测出现"`/getInfo` 返回的 permissions 里
   已经有新权限，但同一个 token 调目标接口仍然 403"。脚本因此把**权限态切换**
   一律改为**完整重新登录**（`Relogin` → `oa-login.ps1`），
   不再依赖 `refreshToken` 的写回时机（DEV-ENV §6.37 的加强版）。

---

## 8. 脚本夹具的临时改动与自愈

脚本会临时改两处共享状态，**收尾一定还原**，并且**开跑前自愈上一轮的残留**：

| 状态 | 临时值 | 还原 | 自愈 |
| --- | --- | --- | --- |
| `sys_role_menu`（`common` 角色） | + 合同台账菜单 + `ctms:contract:edit` + `ctms:attachment:list` | 按进入时快照还原，且**先剔除本脚本管理的这 3 个 menu_id** | 无需（无状态） |
| `sys_role.data_scope`（`common` 角色） | `'1'` → `'5'`（仅本人）验范围外 403 | 还原为进入时的值 | `remark='__B3_ATT_SCOPE_TMP__'` 哨兵：**哨兵在 + 范围是 '5' ⇒ 上一轮异常退出 ⇒ 强制还原为 '1'** |

> 哨兵的由来：数据范围留在 `'5'` 时，受测账号连 `/getInfo` 都会 403（角色没有任何菜单），
> 现象是"全面 403"，极难定位。用 remark 当哨兵是**不新增表**的最低成本做法。

---

## 9. 与其它任务的边界

| 关系 | 说明 |
| --- | --- |
| **第 2 组（DDL）** | `t_ctms_attachment`（15 列）+ `idx_object(object_type, object_id)` + 17 个外键里的 `fk_attachment_contract` 均已由第 2 组交付；本组只做业务挂载层 |
| **第 4 组（合同主体）** | 本组新增 `ICtmsContractService.checkContractAccess(id)`：只做"对象存在 + 数据范围"，**不返回合同数据**（避免下游顺手外泄）。合同侧逻辑一行未改 |
| **第 7 组（迁移）** | 迁移草案对象类型（`party_draft`）**尚未注册**——第 7 组交付时按 §2 的"三处必改"接入 |
| **B4（单据/库存）** | **已接入：8 类单据对象类型全部注册，登记清单 9 项，`plannedB4` 为空**（B3 阶段这 5 个曾在 `plannedB4` 里"登记但不放行"，B4 补齐后已全部放行）；接入方式见 §2，计数与取证以 `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` 为准 |
| **任务 9.1** | 权限点集合由 **25 → 26**；`ctms:attachment:list` 在菜单 SQL 与后端注解里各一处，双向核对通过 |
| **任务 9.3** | "无查看权下载附件 403"的断言本组已落在自己的脚本里（69 条内），第 9 组只在 `authz-check.ps1` 里做补充 |
