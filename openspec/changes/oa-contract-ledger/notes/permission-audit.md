# 9.1 权限点双向核对（菜单 SQL ↔ 后端 @PreAuthorize）＋ 9.2 数据范围四档矩阵实测

> **本文件五段**：§0~§8 = 任务 9.1（权限点双向核对，attempt 1~2）；
> **§9 = 任务 9.2（数据范围四档矩阵实测）**（小节 `9-1 … 9-8`）；
> **§10 = 任务 9.4（模块边界静态审计 AC-81）**（小节 `10-1 … 10-7`）；
> **§11 = 任务 9.1b / t23（权限点闭合复核，含判据边界 §11-8）**（小节 `11-1 … 11-8`）；
> **§12 = 任务 9.1c / t24（权限点闭合复核·最终：差异为空 → 通过）**（小节 `12-1 … 12-5`）。
>
> 任务：`openspec/changes/oa-contract-ledger/tasks.md` §9.1
> 执行：2026-10-05 14:25（qa，attempt 1，差异 **7** 个）｜14:27:30（qa，attempt 2 复核，差异 **5** 个）
> 判定：**未通过**（截至 14:27:30 方向① 5 个、方向② 0 个）—— 差异清单**不为空**，按 §9.1 的口径（"差异清单为空"）本项不能判过。
> 证据可复跑：`powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1`（退出码 1 = 未通过）
> 闭合路线（队长 2026-10-05 决定，**不删菜单行、不放宽判定**）：`ctms:migration:{claim,ignore}` 由第 7 组落地；`ctms:contract-item:{add,edit,remove}` 由后端任务 t5 补行项写端点（`POST/PUT /ctms/contract/items`、`DELETE /ctms/contract/items/{id}`）；权限点闭合的**复核**移交新任务 t23（9.1b）。

---

## 0. 复核更新：attempt 2（2026-10-05 14:27:30）差异 7 → 5

第 7 组 `t1` 的迁移控制器已落地，后端侧从 19 涨到 **21** 个去重权限点（`.java` 58 个 / 命中 83 次 / 注解行 63 次），
`ctms:migration:list`（`CtmsMigrationController.java:65`）与 `ctms:migration:scan`（`:51`）**已闭合**：

```
==> 3. 后端侧集合 B：ruoyi-ctms 源码里的 ctms:* 注解
    扫描 .java 文件：58 个；权限点字符串出现 83 次（其中 @PreAuthorize/注解行 63 次）
    后端去重权限点：21 个
    [OK] 后端集合不依赖注释：全部命中集合 == 注解行集合（注释里提过的权限点不算"有实现"）
==> 4. 双向差异清单（两个方向都必须打印）
    方向① 菜单有、后端无（共 5 个）：
      - ctms:contract-item:add   [sys_menu.menu_id = 9F2C0000000000000000000000000028]
      - ctms:contract-item:edit   [sys_menu.menu_id = 9F2C0000000000000000000000000029]
      - ctms:contract-item:remove   [sys_menu.menu_id = 9F2C0000000000000000000000000030]
      - ctms:migration:claim   [sys_menu.menu_id = 9F2C0000000000000000000000000040]
      - ctms:migration:ignore   [sys_menu.menu_id = 9F2C0000000000000000000000000041]
    方向② 后端有、菜单无（共 0 个）：
          （空）
    [!!] 方向① 菜单有后端无：差异为空（实际 5 个）
    [OK] 方向② 后端有菜单无：差异为空（实际 0 个）
    [!!] 集合 A == 集合 B（两侧各 26 / 21 个）
…
权限点双向核对未通过：13 条通过 / 2 条失败。
  差异方向① 菜单有后端无：5 个 -> ctms:contract-item:add, ctms:contract-item:edit, ctms:contract-item:remove, ctms:migration:claim, ctms:migration:ignore
  差异方向② 后端有菜单无：0 个 ->
```

| 差异项 | attempt 1（14:25） | attempt 2（14:27:30） | 闭合方式 |
| --- | --- | --- | --- |
| `ctms:migration:list` | 菜单有后端无 | ✅ 已闭合（`CtmsMigrationController.java:65`） | 第 7 组 t1 落地 |
| `ctms:migration:scan` | 菜单有后端无 | ✅ 已闭合（`CtmsMigrationController.java:51`） | 第 7 组 t1 落地 |
| `ctms:migration:claim` | 菜单有后端无 | ❌ 仍未闭合 | 第 7 组（认领/批量绑定） |
| `ctms:migration:ignore` | 菜单有后端无 | ❌ 仍未闭合 | 第 7 组（认领/批量绑定） |
| `ctms:contract-item:add/edit/remove` | 菜单有后端无 | ❌ 仍未闭合 | 后端任务 t5 补行项写端点（权限点逐字同名） |

> attempt 2 与 attempt 1 的原始输出用 `Compare-Object` 逐行比对：**22 行成对差异，全部落在
> `scanned files / 命中次数 / 去重数 / 方向①计数` 这 4 个计数行与 `migration:list`、`migration:scan` 两项的用法行上，
> 其余每一行完全一致**（脚本不打印时间戳，所以两次输出可直接逐行比对）；菜单侧 26/27 两条基线两次完全一致。
> 即 attempt 2 相对 attempt 1 的**唯一**变化就是这两个权限点闭合，没有其它隐性漂移。
> **本次 attempt 2 之后不再复跑** —— 按队长指示，闭合复核由任务 t23（9.1b）承担；届时直接运行 §2 的命令即可。

---

## 1. 真源与判定口径

| 项 | 内容 |
| --- | --- |
| 菜单侧真源（权限点） | `ruoyi-vue-oa-master\sql\二开-合同台账-菜单.sql`（2.7 交付；含第 6 组新增的 `ctms:attachment:list`） |
| 菜单侧真源（运行库） | `rad_oa.sys_menu`，`SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%'`（root 空口令，便携 MySQL `env\mysql\server\bin\mysql.exe`） |
| 后端侧真源 | `ruoyi-vue-oa-master\ruoyi-ctms\src\main\java`（`@PreAuthorize("@ss.hasPermi('ctms:…')")`） |
| 判定 | 两侧**集合完全相等**：① 菜单有、后端无 = 0；② 后端有、菜单无 = 0。**两个方向都要输出** |
| 口径数字 | 权限点 **26** 个（25 → 26 由第 6 组新增 `ctms:attachment:list`，见 `notes/attachment-notes.md` §4）；本批菜单 **27** 行（1 目录 + 4 菜单 + 22 按钮，与菜单 SQL 文件自检①一致） |

## 2. 核对命令（可直接复制执行）

```powershell
# 一键核对（脚本只读，退出码 0 = 通过，1 = 有差异/数字不符）
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1

# 契约里给出的两条独立命令（与脚本内部口径一致，可用于交叉复核）
# ① 菜单侧（真库）
F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe --host=127.0.0.1 --user=root --database=rad_oa `
  --batch --skip-column-names --default-character-set=utf8mb4 `
  -e "SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%' ORDER BY perms;"
# ② 后端侧（去重计数；含连字符的 ctms:contract-item:* 必须被匹配）
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
(Select-String -Path ruoyi-ctms\src\main\java\com\ruoyi\ctms\**\*.java -Pattern 'ctms:[a-z-]+:[a-z-]+' -AllMatches).Matches.Value | Sort-Object -Unique
```

## 3. 原始输出（attempt 1｜2026-10-05 14:25，退出码 1）

```
==> 0. 前置（只读核对：本脚本不执行任何 DDL/DML）
    仓库根：F:\dsh\ruoyiOA
    菜单 SQL：ruoyi-vue-oa-master\sql\二开-合同台账-菜单.sql
    后端源码：ruoyi-vue-oa-master\ruoyi-ctms\src\main\java
    数据库  ：rad_oa@127.0.0.1（root 空口令）
    [OK] mysql 客户端存在：F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe
    [OK] 菜单 SQL 真源存在：ruoyi-vue-oa-master\sql\二开-合同台账-菜单.sql
    [OK] 后端源码目录存在：ruoyi-vue-oa-master\ruoyi-ctms\src\main\java
==> 1. 受控自证：正则必须能匹配含连字符的权限点，且注释命中不得冒充注解
    [OK] hyphen 自检：'ctms:[a-z-]+:[a-z-]+' 在夹具上命中 3 个且含 ctms:contract-item:add（实际：ctms:attachment:list | ctms:contract:edit | ctms:contract-item:add）
==> 2. 菜单侧集合 A：菜单 SQL 文件 vs 真库 sys_menu
    菜单 SQL 声明权限点：26 个；声明 menu_id：27 行
    真库 ctms 权限点行数：26 行 / 去重 26 个
    [OK] 菜单 SQL 声明的权限点数 = 26（实际 26）
    [OK] 菜单 SQL 声明的菜单行数 = 27（1 目录 + 4 菜单 + 22 按钮，实际 27）
    [OK] 带 ctms 权限点的菜单行数 = 26（=权限点数；目录行 perms 为 NULL 不计数，实际 26）
    [OK] 真库 perms 集合 == 菜单 SQL 声明集合（证明真库执行的就是本文件）
    [OK] 本批 27 个 menu_id 在真库的行数 = 27（SELECT COUNT(*) 与文件自检①一致，实际 27）
    [OK] 本批 menu_id 覆盖的 ctms 权限点数 = 26（实际 26）
    [OK] 第 6 组新增权限点 ctms:attachment:list 已在真库菜单行中（25 → 26 口径生效）
==> 3. 后端侧集合 B：ruoyi-ctms 源码里的 ctms:* 注解
    扫描 .java 文件：52 个；权限点字符串出现 78 次（其中 @PreAuthorize/注解行 61 次）
    后端去重权限点：19 个
    [OK] 后端集合不依赖注释：全部命中集合 == 注解行集合（注释里提过的权限点不算"有实现"）
==> 4. 双向差异清单（两个方向都必须打印）
    方向① 菜单有、后端无（共 7 个）：
      - ctms:contract-item:add   [sys_menu.menu_id = 9F2C0000000000000000000000000028]
      - ctms:contract-item:edit   [sys_menu.menu_id = 9F2C0000000000000000000000000029]
      - ctms:contract-item:remove   [sys_menu.menu_id = 9F2C0000000000000000000000000030]
      - ctms:migration:claim   [sys_menu.menu_id = 9F2C0000000000000000000000000040]
      - ctms:migration:ignore   [sys_menu.menu_id = 9F2C0000000000000000000000000041]
      - ctms:migration:list   [sys_menu.menu_id = 9F2C0000000000000000000000000014]
      - ctms:migration:scan   [sys_menu.menu_id = 9F2C0000000000000000000000000039]
    方向② 后端有、菜单无（共 0 个）：
          （空）
    [!!] 方向① 菜单有后端无：差异为空（实际 7 个）
    [OK] 方向② 后端有菜单无：差异为空（实际 0 个）
    [!!] 集合 A == 集合 B（两侧各 26 / 19 个）
==> 5. 逐权限点使用位置（控制器:行；菜单侧 26 个全列）
    ctms:attachment:list          5  CtmsAttachmentController.java:72, CtmsAttachmentController.java:89, CtmsAttachmentController.java:117, CtmsAttachmentController.java:136, CtmsAttachmentController.java:148
    ctms:contract:add             2  CtmsContractController.java:151, CtmsContractController.java:176
    ctms:contract:edit            4  CtmsAttachmentController.java:89, CtmsAttachmentController.java:136, CtmsContractController.java:188, CtmsContractController.java:214
    ctms:contract:export          1  CtmsContractController.java:82
    ctms:contract:list            2  CtmsContractController.java:65, CtmsContractController.java:166
    ctms:contract:query           3  CtmsContractController.java:94, CtmsContractController.java:104, CtmsContractController.java:131
    ctms:contract:remove          2  CtmsContractController.java:225, CtmsContractController.java:243
    ctms:contract:status          1  CtmsContractController.java:203
    ctms:contract-item:add        0  —（后端无使用）
    ctms:contract-item:edit       0  —（后端无使用）
    ctms:contract-item:list       1  CtmsContractController.java:121
    ctms:contract-item:remove     0  —（后端无使用）
    ctms:migration:claim          0  —（后端无使用）
    ctms:migration:ignore         0  —（后端无使用）
    ctms:migration:list           0  —（后端无使用）
    ctms:migration:scan           0  —（后端无使用）
    ctms:partner:add              6  CtmsPartnerController.java:88, CtmsPartnerController.java:169, CtmsProductMasterController.java:104, CtmsProductMasterController.java:179, CtmsProductMasterController.java:254, CtmsProductMasterController.java:332
    ctms:partner:edit             6  CtmsPartnerController.java:100, CtmsPartnerController.java:181, CtmsProductMasterController.java:119, CtmsProductMasterController.java:194, CtmsProductMasterController.java:269, CtmsProductMasterController.java:347
    ctms:partner:list             7  CtmsPartnerController.java:56, CtmsPartnerController.java:137, CtmsProductMasterController.java:63, CtmsProductMasterController.java:78, CtmsProductMasterController.java:151, CtmsProductMasterController.java:226, CtmsProductMasterController.java:304
    ctms:partner:query            8  CtmsPartnerController.java:68, CtmsPartnerController.java:78, CtmsPartnerController.java:149, CtmsPartnerController.java:159, CtmsProductMasterController.java:91, CtmsProductMasterController.java:166, CtmsProductMasterController.java:241, CtmsProductMasterController.java:319
    ctms:partner:remove           6  CtmsPartnerController.java:123, CtmsPartnerController.java:204, CtmsProductMasterController.java:134, CtmsProductMasterController.java:209, CtmsProductMasterController.java:287, CtmsProductMasterController.java:362
    ctms:partner:status           2  CtmsPartnerController.java:112, CtmsPartnerController.java:193
    ctms:tag:add                  1  CtmsTagController.java:69
    ctms:tag:edit                 1  CtmsTagController.java:81
    ctms:tag:list                 2  CtmsTagController.java:47, CtmsTagController.java:59
    ctms:tag:remove               1  CtmsTagController.java:93

------------------------------------------------------------------------------
权限点双向核对未通过：13 条通过 / 2 条失败。
  [!!] 方向① 菜单有后端无：差异为空（实际 7 个）
  [!!] 集合 A == 集合 B（两侧各 26 / 19 个）
  差异方向① 菜单有后端无：7 个 -> ctms:contract-item:add, ctms:contract-item:edit, ctms:contract-item:remove, ctms:migration:claim, ctms:migration:ignore, ctms:migration:list, ctms:migration:scan
  差异方向② 后端有菜单无：0 个 -> 
  修法由任务负责人决定：本脚本只报告，不改 @PreAuthorize、不改菜单 SQL。
```

（`EXIT=1`；连跑两次结果一致，14:25:14 与 14:25:24 两次输出相同 → 可重复。）

## 4. 双向差异清单（attempt 1 快照 14:25；最新状态与闭合进度见 §0）

### 方向① 菜单有、后端无：attempt 1 = **7 个**（attempt 2 = 5 个，见 §0）

| # | 权限点 | `sys_menu.menu_id` | 菜单行 | 现状 | 归属 |
| --- | --- | --- | --- | --- | --- |
| 1 | `ctms:contract-item:add` | `…00000028` | 行项新增（F） | 后端无任何 `@PreAuthorize` 使用；全仓库（含前端 `v-hasPermi`）均无使用点 | 第 4 组交付边界（行项写操作门禁挂在 `ctms:contract:add`/`edit`） |
| 2 | `ctms:contract-item:edit` | `…00000029` | 行项编辑（F） | 同上 | 同上 |
| 3 | `ctms:contract-item:remove` | `…00000030` | 行项删除（F） | 同上 | 同上 |
| 4 | `ctms:migration:list` | `…00000014` | 迁移认领（C） | 后端无 `CtmsMigration*` 控制器（`controller\` 目录当前 5 个控制器 + package-info） | 第 7 组（`t1` 执行中） |
| 5 | `ctms:migration:scan` | `…00000039` | 迁移扫描（F） | 同上 | 第 7 组（`t1` 执行中） |
| 6 | `ctms:migration:claim` | `…00000040` | 草案认领（F） | 同上 | 第 7 组（`t22`/`t5` 待做） |
| 7 | `ctms:migration:ignore` | `…00000041` | 草案忽略（F） | 同上 | 第 7 组（`t22` 待做） |

### 方向② 后端有、菜单无：**0 个**（该方向已闭合）

后端 19 个 `ctms:*` 全部能在 `sys_menu` 找到对应行：`contract:list/query/add/edit/remove/status/export`（7）、
`contract-item:list`（1）、`tag:list/add/edit/remove`（4）、`partner:list/query/add/edit/remove/status`（6）、
`attachment:list`（1）。

### 结论

- **本项按 §9.1 口径不通过**：差异方向① 不为空（attempt 1 = 7 个，attempt 2 = 5 个）。方向② 已为空。
- 其中 **4 个 `ctms:migration:*`** 属"实现尚未落地"（第 7 组 `t1` 正在做，脚本复核时间点 14:25 时尚无迁移控制器）→
  `list`/`scan` 已于 14:27:30（attempt 2）闭合，`claim`/`ignore` 待第 7 组认领/批量绑定落地。
- 其中 **3 个 `ctms:contract-item:{add,edit,remove}`** 属**结构性**差异（第 4 组已交付 9/9）：
  行项写操作实际由 `CtmsContractController` 的 `ctms:contract:add` / `ctms:contract:edit` 承载
  （`CtmsContractController.java:176` / `:188`），行项只读接口才用 `ctms:contract-item:list`（`:121`）。
  口径决定（**不属于 9.1 的范围，本任务只报告**）：
  ① 后端为行项写操作补独立端点并挂这 3 个权限点；或
  ② 从 `sql\二开-合同台账-菜单.sql` 删掉这 3 行按钮（权限点口径 26 → 23）并同步 `tasks.md` §2.7 记录。
  → **队长 2026-10-05 已定：选①**（依据菜单 SQL `:22` 的分组注释明确要求「行项 查看/新增/编辑/删除」，
  删行等于改口径迁就门禁），并入后端任务 **t5**（`POST/PUT /ctms/contract/items`、`DELETE /ctms/contract/items/{id}`，
  权限点逐字用 `ctms:contract-item:add/edit/remove`）；闭合**复核**移交任务 **t23（9.1b）**。
- `notes/permission-audit.md` §6 的口径数字核对**全部通过**（26 个权限点 / 27 行菜单），
  即"菜单侧真源"本身是自洽的；不通过的是"菜单 ↔ 后端"的对应关系。

## 5. 脚本自身的受控自证

脚本在比对前先跑两条自证，避免"正则失效 / 注释冒充实现"这类假绿：

1. **hyphen 自检**：`ctms:[a-z-]+:[a-z-]+` 必须在夹具（含 `ctms:contract-item:add`）上命中 3 个 —— 防止正则漏掉带连字符的 `ctms:contract-item:*`；
2. **注释不算实现**："全部命中集合"必须等于"`hasPermi` 注解行集合" —— 若某权限点只出现在注释里（如 `CtmsContractController` 类注释里的 `ctms:contract:list/query/add/...`），不得被当成有后端实现。

首轮本脚本自身有两处断言写错（**不计入被核对对象的差异**，已修正并复跑）：

| 现象 | 真因 | 修法 |
| --- | --- | --- |
| hyphen 自检首轮误报失败 | 用 `（排序后 join）== 期望字符串` 断言集合：PowerShell `Sort-Object` 是**文化序**（会忽略连字符），实际顺序为 `attachment:list \| contract:edit \| contract-item:add` | 改为"数量 + `-contains` 三个成员"的集合断言 |
| "真库 ctms 权限点行数 = 27" 首轮误报失败 | 27 行里有 1 行是**目录**（`perms` 为 NULL），带 `ctms:*` 的只有 26 行；27 是"本批 `menu_id` 行数"，两者不是同一个数 | 拆成两条断言：本批 `menu_id` 行数 27（与文件自检①一致）、带 `ctms:*` 的菜单行数 26 |

## 6. 数字核对（口径 26 / 27）

| 检查 | 期望 | 实测 | 结论 |
| --- | --- | --- | --- |
| 菜单 SQL 文件声明的 `ctms:*` 权限点 | 26 | 26 | ✅ |
| 真库 `SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%'` | 26（去重） | 26 行 / 26 去重 | ✅ |
| 真库 perms 集合 == 菜单 SQL 文件声明集合 | 相等 | 相等 | ✅（证明真库执行的就是本文件） |
| 本批 `menu_id` 行数（`SELECT COUNT(*) … WHERE menu_id IN (27 个)`） | 27 | 27 | ✅ |
| `ctms:attachment:list` 在真库菜单行中 | 1 行 | 1 行 | ✅（25 → 26 口径生效） |
| 后端 `@PreAuthorize` 去重权限点 | 26（与菜单相等） | attempt 1：**19** / attempt 2：**21** | ❌ 依次差 7 / 5 个（见 §0 与 §4 方向①） |

## 7. 只读声明（本任务未改任何 `@PreAuthorize` / 菜单 SQL）

- 核对脚本 `tools\ctms-perm-audit.ps1` **只做 SELECT**：全文无 `INSERT`/`UPDATE`/`DELETE`/`DROP`（菜单侧一律 `SELECT … FROM sys_menu`）；
- 被核对对象的改动前取证（attempt 1 执行时刻 **2026-10-05 14:25**；attempt 2 复核时菜单 SQL 与
  `CtmsContractController`/`CtmsTagController`/`CtmsAttachmentController` 的 mtime **未变化**，证明两次核对的对象一致、
  attempt 2 不是"换了输入"的假复核）：

| 对象 | 大小/摘要 | 最后修改时间 |
| --- | --- | --- |
| `ruoyi-vue-oa-master\sql\二开-合同台账-菜单.sql` | SHA256 `6419D8747F818959…` | 2026-10-05 13:52:34 |
| `CtmsContractController.java` | SHA256 `DC6B86E285A73984…` | 2026-10-05 12:55:58 |
| `CtmsTagController.java` | SHA256 `F9431FA6E2830CE5…` | 2026-10-05 12:19:54 |
| `CtmsAttachmentController.java` | SHA256 `89C7A0ADCCC34692…` | 2026-10-05 13:52:47 |
| `CtmsMigrationController.java`（attempt 2 新增） | SHA256 `ED8CD3182294EF84…` | 2026-10-05 14:27:21（注解：`scan`:51、`list`:65） |

- 本次 attempt 只写了两个在范围内的文件：`tools\ctms-perm-audit.ps1`（新）与本文件；
  `ruoyi-vue-oa-master\` 下未做任何写入（`.ps1` 为 UTF-8 with BOM，`.md` 无 BOM）。

## 8. 复跑方式（差异闭合后）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1   # 期望：退出码 0，"差异为空"
```

修好后的期望输出形态：`方向① 菜单有、后端无（共 0 个）` + `方向② 后端有、菜单无（共 0 个）` +
`权限点双向核对通过：… 差异为空（菜单 26 / 后端 26，菜单行 27）`。

> 提醒（第 7 组落地时）：`ctms:migration:list` 挂菜单行 `…0014`，`scan/claim/ignore` 挂 `…0039/0040/0041`；
> 菜单 SQL 已就位，后端只需逐字使用同名权限点，**不要**新增/改名（改名会同时打破 9.1 与 2.7 的自检）。
>
> 提醒（t5 落地时）：`ctms:contract-item:{add,edit,remove}` 挂菜单行 `…0028/0029/0030`，按队长决定**补后端行项写端点**
> （`POST/PUT /ctms/contract/items`、`DELETE /ctms/contract/items/{id}`）、权限点逐字同名，**不要删菜单行**。
> 闭合后由 **t23（9.1b）** 复跑本脚本，期望退出码 0。

---

# 9. 任务 9.2：数据范围四档矩阵实测（SELF/DEPT/DEPT_AND_CHILD/ALL × 列表/详情/导出）

> 执行：2026-10-05（qa，attempt 1）
> 判定：**通过** —— 逐格实测与基线**差异为空**；7 档 × 4 夹具 × 3 可见面全部一致，另有 24 条「HTTP 200 + 业务码 403」原始响应体证据。
> 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1`（退出码 0，**253 项**断言全绿）
> 实现在 `tools\ctms-contract-check.ps1` 的 4.8 / **4.8b** 段（4.8 保留原有"无权限 403"门禁，4.8b 是本次新增的矩阵）。

## 9-1 基线：1.4 的预期矩阵**未成文**，故先显式写出（含来源）

- 任务 1.4（`tasks.md:31`）**仍是 `- [ ]`**，其产物 `openspec/changes/oa-contract-ledger/notes/data-scope-matrix.md`
  **不存在**（`glob` 无命中）→ 按 9.2 验收条款"若 1.4 的预期矩阵未成文，先把它显式写出来作为比对基线并说明来源"，
  本节的表 9-1 就是**比对基线**；1.4 后续认领时可直接引用本段（不必重造）。
- 基线来源（逐条可查）：

| 来源 | 位置 | 取到的口径 |
| --- | --- | --- |
| 规格（**唯一真源**） | `specs/ctms/contract-ledger/spec.md:135` + 场景 `:137~:151` | 档位支持「全部 / 本部门（含默认下级）/ 仅本人」；多角色**取并集**；按标识直查同样校验、范围外 **403**；超管可见全部 |
| 设计决策 | `design.md:75`、`design.md:78`（D-4 落地要点） | `SELF` = 创建人本人 **或** 创建人所属部门（含下级）；`DEPT` = 合同 `dept_id` 命中用户部门 `sys_dept.ancestors` 链（**含下级为默认**，可配置关闭）；`ALL` = 不加条件；超管放行 |
| 任务口径 | `tasks.md:31`（1.4）、`tasks.md:263~271`（4.8 交付记录：判定已收敛到 `ContractDataScope` 单入口，矩阵待补） | 五条口径（SELF 语义 / DEPT 含下级 / 多角色并集 / 范围外 403 / 超管全量） |
| 验收条款 | `doc/2.0/2.0-PRD-OA升级开发.md:878`（AC-79） | 四档名 **`SELF`/`DEPT`/`DEPT_AND_CHILD`/`ALL`** × 可见集合与预期一致 |
| 参考侧值语义（**仅用于说明差异**） | `doc/2.0/参考仓库-CTMS-移植清单.md:1227` | 参考侧 `DATA_SCOPE_ALL=1 / CUSTOM=2 / DEPT=3 / DEPT_AND_CHILD=4 / SELF=5` |

**表 9-1　档位名 → 实现档位值（`sys_role.data_scope`）映射（本次实测的口径）**

| 9.2 / AC-79 档位名 | 实现档位值 | 语义（实现口径 = spec + D-4） | 判别夹具 | 与参考侧值语义 |
| --- | --- | --- | --- | --- |
| `ALL` | `'1'` | 不加条件（全部未停用合同） | 4 夹具应全可见 | 一致 |
| `DEPT` | `'3'` | 本部门；**实现含下级为默认** | 「本部门他建」应可见 | 参考侧 `'3'` 不含下级 |
| `DEPT_AND_CHILD` | `'3'`（**与 DEPT 同值**） | 本部门及以下 | 「下级他建」应可见（若实现不含下级 → 该格必红） | 参考侧 `'4'`；本实现 `'4'` 是 SELF |
| `SELF` | `'4'` | 本人创建 **或** 本人所属部门及以下 | 「本人外部门」应可见、「外部门他建」应 403 | 参考侧 `'5'` 才是仅本人 |
| （补充）`SELF_ONLY` | `'5'` | 仅本人创建（不看部门） | 「本人外部门」可见、「本部门他建」应 403 | 参考侧 `'5'` |
| （补充）多角色并集 | `'3'`+`'5'` | 两角色按 `or` 取并集 | 以上两格同时可见 | 参考侧"取最宽"（语义接近） |
| （补充）超管放行 | 不经角色档位 | `SecurityUtils.isAdmin` 直接放行 | 4 夹具全可见 | 一致 |

> **两条必须写明的口径事实**（否则表 9-2 会被误读）：
> 1. 实现把 `DEPT` 与 `DEPT_AND_CHILD` **收敛到同一个值 `'3'`**（`ContractDataScope` 的 `'3'` 分支即"本部门及以下"，
>    `'2'` 自定义部门也与它同口径）。`design.md:78` 提到"可配置关闭"，但**代码里没有这个开关**，spec 也只要求"默认含下级"，
>    因此矩阵里这两行靠**不同夹具**（本部门 vs 下级部门）取得判别力，而不是靠两个不同的 role 值。
> 2. **值语义与平台角色页标签存在偏差**（观察项，非本任务缺陷 —— design D-4 已把本项目口径写死为真源）：
>    角色管理页的下拉标签是 RuoYi 标准（`3`=本部门、`4`=本部门及以下、`5`=仅本人），
>    而本实现 `'4'` = 本人创建**或**本人部门及以下、`'5'` = 仅本人创建。
>    运营若按标签选 `'4'`，会看到"本人创建但不在本部门"的合同 —— 比标签更宽。
>    建议把该映射写进交付/配置说明（本任务不改实现与前端）。

## 9-2 夹具与判定方法（可复跑、可自证）

**夹具（全部 ASCII 前缀，收尾物理删除；直接 SQL 插入以便精确控制 `create_id`/`dept_id`）**

| 夹具 | `id` | `dept_id` | `create_id` | 判别用途 |
| --- | --- | --- | --- | --- |
| 本部门他建 | `CTMDSAME<S>` | 受测账号所在部门（行政部 `A070B84D000F4DC48F591AE081F3047E`） | `superAdmin` | `DEPT` 判别格 |
| 下级他建 | `CTMDSCH<S>` | 临时子部门 `CTMDEPT<S>`（父 = 行政部，`ancestors=0,<根>,行政部`） | `superAdmin` | `DEPT_AND_CHILD` 判别格（含下级默认） |
| 外部门他建 | `CTMDSOTH<S>` | 人资部 `203A510A850A479197596F1504FCDEE1` | `superAdmin` | 范围外（应 403） |
| 本人外部门 | `CTMDSOWN<S>` | 人资部（外部门） | 受测账号 `zhangwei` | `SELF` 判别格 |

**账号**：受测账号 `zhangwei`（只有 `common` 角色；本次临时给它挂 `bm` 角色以验并集 —— `bm` 无任何菜单、未分配给任何账号）。
**三个可见面**：`GET /ctms/contract/list`、`GET /ctms/contract/{id}`、`GET /ctms/contract/export`；
另加三个"按标识直查"旁路入口：`PUT /ctms/contract`（编辑）、`DELETE /ctms/contract/{id}?reason=…`（删除）、`PUT /ctms/contract/{id}/restore`（恢复）。
**判定方法（双重，避免"只测自己造的那几行"）**：
1. **逐格**：4 夹具 × 3 可见面（+ 范围外 3 个旁路入口）逐格给「被登录账号 + 实测 + 预期」；
2. **整集比对**：用**独立 SQL**（按表 9-1 的 spec 口径重写，不复用后端代码）算出该档的期望可见 id 集合，
   与实际列表/导出的 id 集合**逐 id 比对**；取值前后各取一次快照，两次一致才断言（若期间有并发写入则打印并跳过整集断言，只保留逐格判定）。
**权限态切换**：每次改 `data_scope` 后走 `tools\oa-login.ps1` 的**完整重新登录**（DEV-ENV §6.47），不用 `/getInfo` 顶替。
**授权前提**：先证明"无授权 → 403"，再给 `common` 临时授权 `list/query/edit/remove/export` 五个菜单，
这样"范围外 403"才能证明是**数据范围**造成的，而不是权限点造成的。

## 9-3 实测矩阵（脚本 4.8b 段的原始输出，`<S>=143320187`）

```
[超管放行          ] 账号=superAdmin  列表：本部门他建=可见, 下级他建=可见, 外部门他建=可见, 本人外部门=可见
                 详情：本部门他建=200, 下级他建=200, 外部门他建=200, 本人外部门=200
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=可见, 本人外部门=可见
[ALL           ] 账号=zhangwei    列表：本部门他建=可见, 下级他建=可见, 外部门他建=可见, 本人外部门=可见
                 详情：本部门他建=200, 下级他建=200, 外部门他建=200, 本人外部门=200
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=可见, 本人外部门=可见
[DEPT          ] 账号=zhangwei    列表：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=403
                 详情：本部门他建=200, 下级他建=200, 外部门他建=403, 本人外部门=403
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=403
[DEPT_AND_CHILD] 账号=zhangwei    列表：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=403
                 详情：本部门他建=200, 下级他建=200, 外部门他建=403, 本人外部门=403
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=403
[SELF          ] 账号=zhangwei    列表：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=可见
                 详情：本部门他建=200, 下级他建=200, 外部门他建=403, 本人外部门=200
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=可见
[SELF_ONLY     ] 账号=zhangwei    列表：本部门他建=403, 下级他建=403, 外部门他建=403, 本人外部门=可见
                 详情：本部门他建=403, 下级他建=403, 外部门他建=403, 本人外部门=200
                 导出：本部门他建=403, 下级他建=403, 外部门他建=403, 本人外部门=可见
[并集(3+5)       ] 账号=zhangwei    列表：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=可见
                 详情：本部门他建=200, 下级他建=200, 外部门他建=403, 本人外部门=200
                 导出：本部门他建=可见, 下级他建=可见, 外部门他建=403, 本人外部门=可见
```

### 9-3.1 与基线逐格比对：**差异为空**

| 档位 | 期望（表 9-1） | 列表 | 详情 | 导出 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 超管放行 | 4 夹具全可见 | 一致 | 一致（4×`code=200`） | 一致 | ✅ |
| `ALL`（`1`） | 4 夹具全可见 | 一致 | 一致 | 一致 | ✅ |
| `DEPT`（`3`） | 本部门他建 ✓、下级他建 ✓、外部门他建 ✗、本人外部门 ✗ | 一致 | 一致（✗ 两格 `code=403`） | 一致 | ✅ |
| `DEPT_AND_CHILD`（`3`，判别格=下级他建） | 同 `DEPT` | 一致 | 一致 | 一致 | ✅（含下级默认生效） |
| `SELF`（`4`） | 本部门他建 ✓、下级他建 ✓、本人外部门 ✓、外部门他建 ✗ | 一致 | 一致 | 一致 | ✅ |
| `SELF_ONLY`（`5`，补充） | 仅本人外部门 ✓，其余 ✗ | 一致 | 一致 | 一致 | ✅ |
| 并集 `3+5` | 本部门他建 ✓（3）+ 本人外部门 ✓（5）、下级他建 ✓、外部门他建 ✗ | 一致 | 一致 | 一致 | ✅ |

**逐格对照（预期 → 实测；账号：超管行 = `superAdmin`，其余 = `zhangwei`）**

| 档位 | 可见面 | 本部门他建 | 下级他建 | 外部门他建 | 本人外部门 |
| --- | --- | --- | --- | --- | --- |
| 超管放行 | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 |
| `ALL`（`1`） | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 |
| `DEPT`（`3`） | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 不可见→403 / 403→403 / 不可见→403 | 不可见→403 / 403→403 / 不可见→403 |
| `DEPT_AND_CHILD`（`3`） | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 不可见→403 / 403→403 / 不可见→403 | 不可见→403 / 403→403 / 不可见→403 |
| `SELF`（`4`） | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 不可见→403 / 403→403 / 不可见→403 | 可见→可见 / 200→200 / 可见→可见 |
| `SELF_ONLY`（`5`，补充） | 列表 / 详情 / 导出 | 不可见→403 / 403→403 / 不可见→403 | 不可见→403 / 403→403 / 不可见→403 | 不可见→403 / 403→403 / 不可见→403 | 可见→可见 / 200→200 / 可见→可见 |
| 并集 `3+5`（补充） | 列表 / 详情 / 导出 | 可见→可见 / 200→200 / 可见→可见 | 可见→可见 / 200→200 / 可见→可见 | 不可见→403 / 403→403 / 不可见→403 | 可见→可见 / 200→200 / 可见→可见 |

（`不可见→403` 指"列表/导出里没有该行"与"详情返回**业务码 403**"两种形态；每档另跑编辑/删除/恢复三个旁路入口，见 §9-4。）

**整集比对**（期望集合来自独立 SQL，逐 id 相等）：| 档位 | 该时刻期望可见行数（`del_flag='0'` + 该档谓词） | 列表实际 | 导出实际 |
| --- | --- | --- | --- |
| 超管放行 | 14 | 14 行逐 id 一致 ✅ | 14 行逐 id 一致 ✅ |
| `ALL` | 14 | 14 行 ✅ | 14 行 ✅ |
| `DEPT` | 2 | 2 行 ✅ | 2 行 ✅ |
| `DEPT_AND_CHILD` | 2 | 2 行 ✅ | 2 行 ✅ |
| `SELF` | 3 | 3 行 ✅ | 3 行 ✅ |
| `SELF_ONLY` | 1 | 1 行 ✅ | 1 行 ✅ |
| 并集 `3+5` | 3 | 3 行 ✅ | 3 行 ✅ |

## 9-4 范围外一律「HTTP 200 + 业务码 403」：24 条原始响应体证据

`PUT /ctms/contract`（编辑）、`DELETE /ctms/contract/{id}?reason=…`（删除）、`PUT /ctms/contract/{id}/restore`（恢复）
与被断言为 403 的详情一起，**逐条**记录 HTTP 状态与响应体（`HTTP=200` 全部为真，响应体 `code=403`）：

```
[DEPT] GET /ctms/contract/CTMDSOTH<S>（外部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT] GET /ctms/contract/CTMDSOWN<S>（本人外部门）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT] PUT /ctms/contract(id=CTMDSOTH<S>) → HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT] DELETE /ctms/contract/CTMDSOTH<S>?reason=%E8%B6%8A%E6%9D%83%E5%81%9C%E7%94%A8 → HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT] PUT /ctms/contract/CTMDSOTH<S>/restore → HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT_AND_CHILD] GET /ctms/contract/CTMDSOTH<S>（外部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT_AND_CHILD] GET /ctms/contract/CTMDSOWN<S>（本人外部门）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT_AND_CHILD] PUT /ctms/contract(id=CTMDSOTH<S>) → HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT_AND_CHILD] DELETE /ctms/contract/CTMDSOTH<S>?reason=… → HTTP=200 body={"msg":"无权访问该合同","code":403}
[DEPT_AND_CHILD] PUT /ctms/contract/CTMDSOTH<S>/restore → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF] GET /ctms/contract/CTMDSOTH<S>（外部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF] PUT /ctms/contract(id=CTMDSOTH<S>) → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF] DELETE /ctms/contract/CTMDSOTH<S>?reason=… → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF] PUT /ctms/contract/CTMDSOTH<S>/restore → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] GET /ctms/contract/CTMDSAME<S>（本部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] GET /ctms/contract/CTMDSCH<S>（下级他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] GET /ctms/contract/CTMDSOTH<S>（外部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] PUT /ctms/contract(id=CTMDSOTH<S>) → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] DELETE /ctms/contract/CTMDSOTH<S>?reason=… → HTTP=200 body={"msg":"无权访问该合同","code":403}
[SELF_ONLY] PUT /ctms/contract/CTMDSOTH<S>/restore → HTTP=200 body={"msg":"无权访问该合同","code":403}
[UNION] GET /ctms/contract/CTMDSOTH<S>（外部门他建）→ HTTP=200 body={"msg":"无权访问该合同","code":403}
[UNION] PUT /ctms/contract(id=CTMDSOTH<S>) → HTTP=200 body={"msg":"无权访问该合同","code":403}
[UNION] DELETE /ctms/contract/CTMDSOTH<S>?reason=… → HTTP=200 body={"msg":"无权访问该合同","code":403}
[UNION] PUT /ctms/contract/CTMDSOTH<S>/restore → HTTP=200 body={"msg":"无权访问该合同","code":403}
```

- 每格另有一张"被拒后**数据未变**"的断言：`SELECT CONCAT(name,'|',del_flag)` 仍是 `外部门他建<S>|0`（越权编辑没有改名、越权删除没有置 `del_flag`）。
- 无授权基线（同为业务码 403，非 HTTP 层 403）：`msg=没有权限，请联系管理员授权`，且 403 响应体里**没有任何合同行/合同内容**。

## 9-5 多角色并集（AC-79 的"多角色取最宽/OR 叠加"）

1. **运行期用例**：`common`(=`'3'`) + `bm`(=`'5'`) 两个角色，可见集合 = 两档 `or` 并集：
   「本部门他建」由 `'3'` 命中、「本人外部门」由 `'5'` 命中（都在结果里），「外部门他建」两档都不命中 → 403。
   整集比对 3 行逐 id 一致 ✅（表 9-3）。
2. **代码注释**：`grep -rn "并集" ruoyi-vue-oa-master/ruoyi-ctms/src/main/java` 命中 **7 处**，其中数据范围实现类
   `support/ContractDataScope.java` 命中 4 处（`:21` 类注释口径、`:130`/`:245` 方法说明、`:139` "并集为空 → 不可见任何行"），
   另有 `package-info.java:28`、`mapper/package-info.java:15`、`CtmsContractServiceImpl.java:790`。
3. **单元测试**：`mvn -B -pl ruoyi-ctms test -Dtest=ContractDataScopeTest` → `Tests run: 14, Failures: 0, Errors: 0`，
   其中 `多角色片段用or连接取并集`、`多角色取并集`、`多角色含全部档位时并集就是全部`、`无档位时不可见任何行` 四条覆盖并集语义。

## 9-6 幂等与共享状态还原（§6.48 三件套）

- **连跑两次**：run1 `通过 253 项，失败 0 项`、run2 同样 `253 / 0`，两次退出码均为 0；
  收尾核对两次都是：`本脚本管理的菜单授权已摘净（残留 0 行）`、`common 角色 data_scope 已还原为进入时的值（1|，哨兵已清）`、
  `bm 角色 data_scope 已还原为进入时的值（1|，哨兵已清）`、`并集角色的临时授权已还原（残留 0 行，进入时为 0 行）`、
  `夹具残留行数（含临时子部门，应为 0）：0`。
- **入口自愈哨兵**：改 `data_scope` 前写 `sys_role.remark = __B3_DS_SCOPE_TMP_COMMON__ / __B3_DS_SCOPE_TMP_UNION__`；
  脚本开头若发现"哨兵在"就强制把该角色还原为 `'1'` 并清哨兵（不需要新增表）。
  还原时先删**本脚本管理的菜单集合**（`…0011/0021/0023/0024/0026`）再把快照里不属于该集合的部分插回（§6.48 坑①）。
- **入口自愈**演练**（不是只写在注释里）**：人为把 `common` 造成"上一轮异常退出"的现场
  （`data_scope='5'` + 哨兵 `remark`），再跑本脚本 —— 第 3 次运行（14:38）实测：

```
== 制造上一轮异常退出的现场：common 留下 data_scope='5' + 哨兵 ==
common=5|__B3_DS_SCOPE_TMP_COMMON__
== 运行脚本（观察入口自愈） ==
EXIT=0
    入口自愈：common 角色（role_id=BE63F3F093C1411892284CDF9AD0BE07）残留哨兵 __B3_DS_SCOPE_TMP_COMMON__ → data_scope 已还原为基线 '1'
    [OK] 本脚本管理的菜单授权已摘净（残留 0 行）
    [OK] common 角色 data_scope 已还原为进入时的值（1|，哨兵已清）
    [OK] bm 角色 data_scope 已还原为进入时的值（1|，哨兵已清）
    [OK] 并集角色的临时授权已还原（残留 0 行，进入时为 0 行）
    [OK] 夹具残留行数（含临时子部门，应为 0）：0
通过 253 项，失败 0 项
== 收尾后的库状态 ==
common=1|<null>
bm=1|<null>
```

  即 `data_scope='5'` **不会**留在库里（这正是 §6.48 ② 说的"灾难现场"），且自愈后本轮仍然全绿。
- **不新增第二套判定**：4.8b 不改 `ruoyi-ctms` 任何代码（矩阵全部通过接口 + SQL 观测完成）。

## 9-7 复跑方式（可直接复制）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1        # 起环境（幂等）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1   # 拿/刷新 token
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1   # 期望：退出码 0、253/253
cd ruoyi-vue-oa-master
mvn -B -pl ruoyi-ctms test -Dtest=ContractDataScopeTest                    # 期望：Tests run: 14, Failures: 0
```

> ⚠ 本脚本**有运行锁**（`.cache\ctms-contract-check.lock`），不可与自身并发；矩阵期间会临时占用 `common` 与 `bm` 角色，
> 请勿同时运行其它会借用 `common` 角色的验收脚本（如 `ctms-attachment-check.ps1`）。
> ⚠ 首次 `mvn` 复跑时若 `ruoyi-ctms` 正被其它成员编辑（例如第 7 组的迁移服务），编译可能瞬时失败 ——
> 本次实测就遇到过：14:34:15 因 `CtmsMigrationServiceImpl.java:202` 编译错误失败，14:34:42 重跑即 `BUILD SUCCESS`（属并发编辑，与本任务无关）。

## 9-8 观察项与后续（均**不在**本任务范围，只报告）

1. **断言条数需要同步**：本脚本从 **113 条**涨到 **253 条**（新增 4.8b 段）。
   `DEV-ENV.md` §7 门禁表第 12 行与 `HANDOFF.md` §1/§7 里写的 `113` 需由对应负责人更新（本任务只改脚本与本文件）。
2. **`data_scope` 值语义与角色页标签的偏差**（见表 9-1 注 2）：建议把映射写进交付/配置说明；
   若要让平台标签与业务口径完全一致，属**新增变更**（改前端标签或改判定分支），不在本批次偷偷改。
3. **1.4 仍未完成**：`notes/data-scope-matrix.md` 不存在。本 §9 已是可复跑的完整四档矩阵，
   1.4 认领时可直接引用本段（或把本段内容移动/复制为 1.4 的产物）——口径与证据都已闭环。

---

# 10. 任务 9.4（9.4 段）：模块边界静态审计（AC-81）

> 执行：2026-10-05（qa）。**attempt 1 = failed**（下面 10-1~10-7 是那时的原始记录，保留为历史）；
> **attempt 2 = 通过**（队长消除根因 + 契约 amend 判据命令可靠性后的复核，见 §10-0）。
> 范围：`ruoyi-vue-oa-master/ruoyi-ctms`（**只读核对**，未改任何实现、未新增审计脚本）
>
> **attempt 1 结论**：模块边界本身**成立** —— 无第二套账号表/组织树/JWT/权限常量表、无免认证后门、无绕过认证入口、模块内无自建 DDL；
> 但 **AC-81 的 grep 判据按字面未达「零命中」**：可靠递归扫描命中 **1 处**，位置是 `package-info.java:23` 的一句**禁止性注释**
> （原文："参考仓库的账号表、组织树、JWT 与 `{@code CTMS_AUTH_ENABLED}` 免认证后门 **绝不移植**"）→ 判 **failed**（**不放宽判据语义**）。
> 另附一条**方法**问题（判据②）：契约给的两条 PowerShell 命令都**不能直接当门禁用**（见 10-2 与 10-6），下面给出可靠写法。

## 10-0 复核更新（attempt 2，2026-10-05 14:4x）：判据零命中 → **通过**

**队长做的两件事（都不是我改的）**：
1. **消除根因**：`ruoyi-ctms/.../package-info.java`（mtime 2026-10-05 **14:46:12**）把那句 javadoc 里的后门常量**字面名去掉**，改写为
   「参考仓库的账号表、组织树、JWT，以及那个「**关掉认证开关即可访问**」的后门常量 **绝不移植**……本条刻意不写出该常量的字面名，
   以免 9.4 的「零命中」判据被注释自己打红」——**语义完全不变**（仍然禁止移植），只是不再自己打红判据。
2. **契约 amend**（tasks.md 9.4）：新增两条「判定命令可靠性」——「零命中」必须以**可靠递归扫描**为判据（不得用 `Select-String -Path <dir>\**\*`），
   依赖条数判据必须用 `-AllMatches` 取 `.Matches.Count`。即 attempt 1 的 F2 被正式纳入口径。

**原始输出（attempt 2 复核，全部为真值计数：`@(匹配结果).Count` 或 `(匹配结果 | Measure-Object).Count`；不用 `@(… | Measure-Object).Count`）**：

```
[1] 不可靠写法（契约原始命令，留档作"假绿"对照）：
    Count=0  Errors=9
      ERR: 无法读取文件 ...\ruoyi-ctms\src\main: 对路径"…"的访问被拒绝。
      ERR: 无法读取文件 ...\ruoyi-ctms\src\test: 对路径"…"的访问被拒绝。
      ERR: 无法读取文件 ...\ruoyi-ctms\target\classes: 对路径"…"的访问被拒绝。（共 9 条）
[2] 可靠递归扫描 — 源文件（排除 target，判定判据）：
    source_files=82  hits=0            ← 零命中 ✅
[3] 可靠递归扫描 — 整个模块（含 target 编译产物）：
    all_files=369  hits=0              ← 零命中 ✅
[4] git grep 交叉验证（契约给出的第二种可靠写法）：
    git grep -n --untracked -i -E 'auth_enabled|second.*jwt|ctms_user|ctms_org' -- ruoyi-ctms
    → 0 行输出，exit=1（grep 语义：1 = 无命中）✅
[6] 注释现文（语义未变）：
    参考仓库的账号表、组织树、JWT，以及那个「关掉认证开关即可访问」的后门常量
    绝不移植（判据见平台能力 platform/module-boundary，静态审计见 9.4；
    本条刻意不写出该常量的字面名，以免 9.4 的「零命中」判据被注释自己打红）。
```

**⚠ 顺带记录我在 attempt 2 复核时自己又踩的一次坑（写清楚免得后来者照抄出一个恒为 1 的判据）**：

我一度写成"**不要接 `Measure-Object`**"，这个归因**不准确**（2026-10-05 队长更正 + 我复核实测）：`Measure-Object` 本身没问题 ——
它对**零输入就报 0**，还顺带给你 `.Count`。真正的陷阱是 **`@()` 包到了"统计对象"上**：统计对象不是 `$null`，
`@()` 会把它变成**单元素数组**，于是 `.Count` 恒为 **1**。

**实测对照（本机 PowerShell 5.1，`$src` = 82 个源文件；两种输入各跑一遍）**：

| 写法 | 零命中（`zzz_not_exist_zzz`） | 有命中（`class`，111 处） | 判定 |
| --- | --- | --- | --- |
| `@($src \| Select-String -Pattern 'p').Count` | **0** | **111** | ✅ 正确（`@()` 包的是**匹配结果**） |
| `($src \| Select-String -Pattern 'p' \| Measure-Object).Count` | **0** | **111** | ✅ 正确（读统计对象的 `.Count`，零输入也是 0） |
| `@($src \| Select-String -Pattern 'p' \| Measure-Object).Count` | **1** ❌ | **1** ❌ | ❌ 错误（`@()` 包住了**统计对象** → 单元素数组） |

机制验证：`$stat = ($src | Select-String -Pattern 'zzz_not_exist_zzz' | Measure-Object)` → `$stat` 是**单个统计对象**（不是数组），
其 `.Count` 属性 = **0**，而 `@($stat).Count` = **1**。

**正确表述**：**`@()` 必须包在"匹配结果"上**（`@(… | Select-String …).Count`），或者把 `Measure-Object` 的 `.Count` 属性直接取出来
（`(… | Select-String … | Measure-Object).Count`）—— 两者都可以；**只有 `@(… | Measure-Object).Count` 这一个组合是恒为 1 的**。
另外，`Get-Content -Raw | Select-String '<artifactId>' | Measure-Object` 报 1 是**另一回事**（对整块字符串只产生 1 个匹配对象，
与 `@()` 无关，见 §10-2）。

**其余判据在当前子树重核（attempt 2）结果**：

| 判据 | attempt 2 实测 | 结论 |
| --- | --- | --- |
| ② pom 依赖 | `-AllMatches` 计数 = **7** | ✅ 无认证/组织自建模块 |
| ③ 表名集合 | 24 token、**违规 0**；mapper XML SQL 目标 = 13 张业务表 + `information_schema`；B3 DDL 13 张全 `t_ctms_*`（非 t_ctms_ 的 CREATE = 0，账号/组织/权限表名命中 = 0） | ✅ |
| ④ 端点覆盖 | 映射 67 = 6 类级前缀 + **61 端点**，**61/61 带 `@PreAuthorize`**；20 个越权/后门关键词（permitAll、@Anonymous、PermitAllUrl、SaIgnore、@Configuration、@Bean、过滤器/拦截器/令牌类…）逐个 **0 命中** | ✅ |
| ④ 与 9.1 交叉印证 | 菜单侧 `ctms:*` = **26**、后端 `@PreAuthorize` 去重 = **21**，差 5 = 9.1 记录的两项未闭合差异 | ✅ 数字互相印证 |
| ⑤ 不新增审计脚本 | `tools/audit` 下 `audit-*.js` = **9**（与 run-all.js 的 AUDITS 9 条一致） | ✅ |

**attempt 2 判定**：6 条验收判据**全部满足**（① 可靠递归扫描零命中且两条命令的原始输出都已留档；②~⑥ 同上表）→ **通过**。
本文件 10-1~10-7 保留 attempt 1 的原始记录（含当时那处注释命中的原文与"假绿"命令的输出），便于追溯口径演进。

## 10-1 判据①：后门关键词扫描（**attempt 1 记录**；当前状态见 §10-0 —— 已零命中）

**契约命令原样执行（退出码 0，Count=0）—— ⚠ 这个 0 不可信：**

```powershell
cd F:\dsh\ruoyiOA
Select-String -Path ruoyi-vue-oa-master\ruoyi-ctms\**\* -Pattern 'auth_enabled|second.*jwt|ctms_user|ctms_org' -CaseSensitive:$false |
  Measure-Object | Select-Object -ExpandProperty Count
# 输出：0
# 但同一次调用还输出了 9 条错误（`\**\*` 把**目录**也喂给了 Select-String）：
#   Select-String : 无法读取文件 ...\ruoyi-ctms\src\main: 对路径"…"的访问被拒绝。
#   （src\test、target\classes、target\generated-sources、target\maven-archiver、
#     target\maven-status、target\surefire-reports、target\test-classes 同理）
```

**可靠递归扫描（本次实际采用的判据）：**

```powershell
$src = Get-ChildItem -Path ruoyi-vue-oa-master\ruoyi-ctms -Recurse -File | Where-Object { $_.FullName -notmatch '\\target\\' }
$src.Count                                                                          # → 82 个源文件（src/main + src/test + pom.xml + mapper README.md）
$src | Select-String -Pattern 'auth_enabled|second.*jwt|ctms_user|ctms_org' -CaseSensitive:$false
# → 1 命中：
# package-info.java:23  *        参考仓库的账号表、组织树、JWT 与 {@code CTMS_AUTH_ENABLED} 免认证后门

$tgt = Get-ChildItem -Path ruoyi-vue-oa-master\ruoyi-ctms\target -Recurse -File      # → 287 个编译产物
$tgt | Select-String -Pattern 'auth_enabled|permitAll|SaIgnore|JwtUtil|TokenService' -CaseSensitive:$false
# → 0 命中
```

**判读（attempt 1 时的状态）**：全模块只有 **1 处**字面命中，且它是**禁止移植后门的注释**（语义与后门相反）；
`second.*jwt` / `ctms_user` / `ctms_org` 三个模式 **0 命中**。按 AC-81 的**意图**边界成立，按字面「零命中」未达
→ 该注释已于 2026-10-05 14:46:12 改写（去掉字面名、语义不变），**attempt 2 复核为 0 命中**（见 §10-0）。

**放宽关键词全扫（源码 82 文件范围，逐词计数）：**

| 关键词 | 命中 | 结论 |
| --- | --- | --- |
| `auth_enabled` | 1 | 即那句禁止性注释（`package-info.java:23`） |
| `permitAll` / `anonymous` / `@Anonymous` / `@PermitAll` / `SaIgnore` / `PermitAllUrl` | 0 | **无**匿名白名单式入口，模块也没往平台匿名白名单里加路径 |
| `@Configuration` / `@Bean` / `WebSecurityConfigurerAdapter` / `SecurityFilterChain` / `OncePerRequestFilter` / `HandlerInterceptor` / `addInterceptors` / `FilterRegistrationBean` / `WebMvcConfigurer` | 0 | 模块内**没有**自己的安全配置 / 过滤器 / 拦截器 |
| `JwtUtil` / `JwtToken` / `createToken` / `TokenService` | 0 | **无**自研令牌与解析器 |
| `ctmsUser` / `ctmsOrg` / `sysUserCtms` / `ctmsAccount` / `CTMS_AUTH` | 0（后者仅在注释里以 `CTMS_AUTH_ENABLED` 出现） | **无**第二套账号/组织命名 |
| 类名匹配 `(?i)(config|filter|interceptor|security|token|jwt|auth)` 的源文件 | 0 | 模块里根本没有这类新类 |

## 10-2 判据②：`ruoyi-ctms/pom.xml` 依赖清单（逐条）

> attempt 2 复核同值：`-AllMatches` 计数 = **7**（该口径已被队长写进 tasks.md §9.4 的 acceptance）。

```powershell
Get-Content -Raw ruoyi-vue-oa-master\ruoyi-ctms\pom.xml | Select-String -Pattern '<artifactId>' | Measure-Object | Select-Object -ExpandProperty Count
# 输出：1      ← ⚠ 该写法对"整块字符串"只报 1 个匹配，不能当门禁
#              （根因是 `Get-Content -Raw` 产生**单个字符串**对象、Select-String 只回 1 个 MatchInfo，
#                与 §10-0 的 `@()` 陷阱无关；§10-0 讲的是"计数方式"，这里讲的是"被搜索的对象是整块字符串"）
# 正确计数（本次采用，并被契约采纳）：
(Get-Content -Raw ruoyi-vue-oa-master\ruoyi-ctms\pom.xml | Select-String -Pattern '<artifactId>' -AllMatches).Matches.Count
# 输出：7
```

**7 个 `<artifactId>` 逐条（1 个 parent + 1 个自身 + 5 个依赖）与性质：**

| # | groupId | artifactId | scope | 性质 / 用途 |
| --- | --- | --- | --- | --- |
| 1 | `com.ruoyi` | `ruoyi`（parent 3.9.1） | — | 平台父 POM（继承依赖与插件版本） |
| 2 | `com.ruoyi` | `ruoyi-ctms`（自身 1.0.1） | — | 本模块 |
| 3 | `com.ruoyi` | `ruoyi-common` | compile | **平台复用**：BaseController / AjaxResult / SecurityUtils / `@Log` / 异常（它自带 `spring-boot-starter-security`，所以 `@PreAuthorize` 可用） |
| 4 | `com.ruoyi` | `ruoyi-system` | compile | **平台复用**：账号与组织（数据范围读 `sys_user` / `sys_dept.ancestors`） |
| 5 | `com.ruoyi` | `ruoyi-serial` | compile 1.0.1 | **平台复用**：编号，只调 `ICodeGenService`，不重写 |
| 6 | `com.ruoyi` | `ruoyi-file` | compile 1.0.1 | **平台复用**：附件上传/存储，只调 API |
| 7 | `junit` | `junit` | **test** | 单测框架（非运行期依赖） |

**判定**：依赖清单里**不存在**任何认证/组织/令牌自建模块（没有 `ruoyi-auth`、没有第二套 `*security*`/`*jwt*`/`*org*`/`*sso*` 模块，也没有自建 start）；
pom 注释本身也把「禁止第二套账号表/组织树/自研令牌/代码常量权限表 + 关闭认证即可访问的后门」写成了硬约束（`ruoyi-ctms/pom.xml:22~35`）。

## 10-3 判据③：本模块读写的表名集合（无第二套账号表/组织树/权限常量表）

**从源码（82 文件）提取的表名 token 全集 = 24 个**，逐条分类：

| 分类 | 表名 | 性质 |
| --- | --- | --- |
| 业务表（本变更集新建） | `t_ctms_contract`、`t_ctms_contract_item`、`t_ctms_contract_tag`、`t_ctms_tag`、`t_ctms_change_log`、`t_ctms_attachment`、`t_ctms_customer`、`t_ctms_supplier`、`t_ctms_party_draft`、`t_ctms_product_type`、`t_ctms_uom`、`t_ctms_warehouse`、`t_ctms_product`（13 张） | ✅ 全部 `t_ctms_` 前缀 |
| 平台复用（账号/组织/权限/字典/参数） | `sys_user`、`sys_dept`、`sys_role`、`sys_role_dept`、`sys_menu`、`sys_config`、`sys_dict_data`（+ 文档里的 `sys_dict_*`） | ✅ 平台表，**只读/复用**，无新建 |
| 平台复用（编号） | `t_code_config`、`t_code_config_rule` | ✅ `ruoyi-serial` 的表 |
| 元数据 | `information_schema` | ✅ 只读探测（如"合同表是否存在"） |

**非平台、非 `t_ctms_*` 的表名：0 个**（即没有 `ctms_user` / `ctms_org` / `ctms_role` / `ctms_permission` / `ctms_token` 之类）。
其中 `sys_role_dept` 只出现在 `ContractDataScope` 的**注释**里（说明 B3 不展开自定义部门明细），模块并未读写它。

**Mapper XML 的 SQL 目标（`src/main/resources/mapper/ctms/*.xml` 共 11 个 + README）**：

```
INSERT INTO / UPDATE / DELETE FROM / FROM 的目标表（去重）：
  t_ctms_attachment, t_ctms_change_log, t_ctms_contract, t_ctms_contract_item, t_ctms_contract_tag,
  t_ctms_customer, t_ctms_party_draft, t_ctms_product, t_ctms_product_type, t_ctms_supplier,
  t_ctms_tag, t_ctms_uom, t_ctms_warehouse, information_schema
```

- 全部是 13 张业务表 + `information_schema`；**没有**平台账号/组织表的直接 SQL，也没有第二套身份表。
- `sys_dept` 的数据范围片段由 Java 侧的 `ContractDataScope` 生成、XML 以 `${dataScopeSql}` 原样拼接（9.2 已实测该片段只含服务端白名单取值）。

**B3 建表脚本（`ruoyi-vue-oa-master/sql/二开-合同台账.sql`）**：`DROP TABLE IF EXISTS` 13 条 + `CREATE TABLE` 13 条，
**全部是 `t_ctms_*`**（第 76~88 行、94~416 行）；对 `ctms_user|ctms_org|ctms_account|ctms_dept|ctms_role|ctms_permission|ctms_token|sys_user_ctms` 的命中数 = **0**。
模块自身 `src/main/resources` 下**只有** mapper XML 与 README，**没有** `schema.sql`/`data.sql`/任何 DDL。

## 10-4 判据④：无绕过认证的入口（@PreAuthorize 覆盖 + 匿名白名单）

**端点 × 注解覆盖扫描（逐文件，含类级 `@RequestMapping` 前缀）：**

| 控制器 | 映射注解总数 | 其中类级前缀 | 端点方法 | 带 `@PreAuthorize` | 缺 |
| --- | --- | --- | --- | --- | --- |
| `CtmsAttachmentController` | 6 | 1 | 5 | 5 | 0 |
| `CtmsContractController` | 15 | 1 | 14 | 14 | 0 |
| `CtmsMigrationController` | 3 | 1 | 2 | 2 | 0 |
| `CtmsPartnerController` | 15 | 1 | 14 | 14 | 0 |
| `CtmsProductMasterController` | 22 | 1 | 21 | 21 | 0 |
| `CtmsTagController` | 6 | 1 | 5 | 5 | 0 |
| **合计** | **67** | **6** | **61** | **61** | **0** |

- 6 个"缺"全部是**类级 `@RequestMapping("/ctms/…")` 路径前缀**（不是端点），所以 **61/61 个对外端点都有 `@PreAuthorize`**（方法级映射与 `@PreAuthorize` 一一对应）。
- `@PreAuthorize` 行数 63 = 61 个端点 + `controller/package-info.java` 里 2 处**文档示例**（javadoc 里的写法示例，不是代码）。
- 注解值全是 `@ss.hasPermi('ctms:…')` 字面量（无 `hasAnyPermi` / `hasRole` / `hasAuthority` / 变量拼接）。

**平台侧匿名白名单（`ruoyi-framework/.../config/SecurityConfig.java`）**：`permitAll` 的只有
`/login`、`/register`、`/captchaImage`、`/docs/*`、`/oauth/**`、`/app/user/validToken`、`/app/modules/list`、
静态资源（`/`、`/*.html`、`/**/*.css`、`/**/*.js`、`/profile/**`）、swagger/druid、`/ureport/**`，
外加带平台 `@Anonymous` 注解的 URL（`PermitAllUrlProperties`）；**`/ctms/**` 不在其中**，且最后一行是 `.anyRequest().authenticated()`。
模块内 `@Anonymous` / `PermitAllUrl` 命中 **0**，`ruoyi-admin` 的配置文件里也没有出现 `ctms` → 模块**没有**给自己开匿名口子。

**与 9.1 相互印证（交叉核对当前真库）**：菜单侧 `ctms:*` 权限点 **26** 个，后端 `@PreAuthorize` 去重 **21** 个，
差 **5** 个 = 9.1 记录的两项未闭合差异（`ctms:contract-item:{add,edit,remove}` + `ctms:migration:{claim,ignore}`）。
两端数字互相对得上，说明"端点覆盖"与"权限点集合"是同一套事实（闭合由 t23 复核）。

## 10-5 判据⑤：本任务不改实现、不新增审计脚本

- 本任务（t15）**只改** `openspec/changes/oa-contract-ledger/notes/permission-audit.md`（本节）——`git status --porcelain` 在本任务范围内只显示这一个文件。
- `tools/audit/` **没有新增文件**：目录下仍是 `run-all.js` + `_selftest.js` + **9 个** `audit-*.js`
  （`audit-then-loaders` / `audit-async-loaders` / `audit-silent-catch` / `audit-loading-pairs` / `audit-undeclared-writes` /
  `audit-template-refs` / `audit-flow-template-binding` / `audit-template-tabs` / `audit-print-builtin-templates`），
  与 `run-all.js` 的 `AUDITS` 数组 9 条一致（DEV-ENV §7 的「9 个静态审计」现状未被打破）。
- 未改 `ruoyi-ctms/` 任何文件（`pom.xml` 与全部 `.java` 均未 touch）。

## 10-6 复跑方式（可直接复制；把不可靠写法换成了可靠写法）

```powershell
cd F:\dsh\ruoyiOA
# ① 后门关键词（可靠递归，排除编译产物）
$src = Get-ChildItem -Path ruoyi-vue-oa-master\ruoyi-ctms -Recurse -File | Where-Object { $_.FullName -notmatch '\\target\\' }
$src | Select-String -Pattern 'auth_enabled|second.*jwt|ctms_user|ctms_org' -CaseSensitive:$false
# ② 依赖清单（正确计数 = 7）
(Get-Content -Raw ruoyi-vue-oa-master\ruoyi-ctms\pom.xml | Select-String -Pattern '<artifactId>' -AllMatches).Matches.Count
# ③ 端点覆盖：61/61（两种计数法都对：@() 包匹配结果，或直接取 Measure-Object 的 .Count）
$m1 = @(Get-ChildItem ruoyi-vue-oa-master\ruoyi-ctms\src\main\java\com\ruoyi\ctms\controller -Filter *.java |
  Select-String -Pattern '@(Get|Post|Put|Delete|Request)Mapping')
"controller 映射总数 = $($m1.Count)"                        # ✅ 67（含 6 个类级前缀）
(Get-ChildItem ruoyi-vue-oa-master\ruoyi-ctms\src\main\java -Recurse -Filter *.java |
  Select-String -Pattern '@PreAuthorize\("@ss\.hasPermi' | Measure-Object).Count      # ✅ 61
# ⚠ 别写成 @(… | Measure-Object).Count —— 那是单元素数组，恒为 1（详见 §10-0 的三种写法对照表）
# ④ 9 个静态审计（不应因本任务变化）
(Get-ChildItem tools\audit -Filter 'audit-*.js' -File).Count      # → 9
node tools\audit\run-all.js
```

> **计数写法速查（§10-0 有完整对照表）**：`@(匹配结果).Count` ✅ ｜ `(匹配结果 | Measure-Object).Count` ✅ ｜
> `@(匹配结果 | Measure-Object).Count` ❌ 恒为 1（`@()` 包住了统计对象）。跨目录搜索一律 `Get-ChildItem -Recurse -File`
> 或 `git grep --untracked`，**不要** `Select-String -Path <dir>\**\*`。

## 10-7 观察项与后续（只报告）

1. **判据① 的 1 处命中（禁止性注释）—— 已由队长消除（2026-10-05 14:46:12）**：`package-info.java` 那句注释改为不提字面量
   （语义不变），attempt 2 复核时可靠递归扫描已 **零命中**（见 §10-0）。当时的两个可选处置（改注释 / 把注释排除在判据外）里，
   队长选了"改注释"以保持判据机械可判定 —— 这个选择更稳（"注释不算"的例外口径迟早被用来放过真东西）。
2. **判据② 的命令不可靠 —— 已并入契约口径（2026-10-05）**：`Select-String -Path <dir>\**\*` 会把目录喂给 Select-String
   （本次触发 9 条"访问被拒绝"），返回的 0 不能当门禁；`Get-Content -Raw | Select-String '<artifactId>' | Measure-Object` 对整块字符串只报 1。
   队长已在 tasks.md §9.4 的 acceptance 里新增两条「判定命令可靠性」（可靠递归扫描 / `-AllMatches` 取 `.Matches.Count`）；
   若要把 AC-81 落成自动化门禁，**并入既有审计脚本**而不是新增第 10 个 `tools/audit/audit-*.js`（baseline 按 9 个比对）。
   本文件 10-6 的写法与该口径一致，并在 §10-0 补记了计数写法的精确语义（`@()` 包住**统计对象**会得到恒为 1 的单元素数组；
   `Measure-Object` 本身对零输入报 0，是安全的）。队长已把这条（与日期门禁的大小写陷阱合并）写进 `DEV-ENV.md` §6 第 50 条。
3. **`ruoyi-ctms` 的唯一"无登录上下文不加范围条件"兜底**（`ContractDataScope`：无登录上下文 → 不加条件；`hasAllScope()` → true）
   在静态边界上不是越权入口：真实请求必过平台认证链（`anyRequest().authenticated()` + `JwtAuthenticationTokenFilter`），
   该兜底只服务单测/定时任务/迁移脚本（9.2 的四档矩阵已从接口侧证明范围判定对真实登录生效）。

---

# 11. 任务 9.1b（t23）：权限点闭合复核（2026-10-05，qa attempt 1）

> 本轮 = t12（9.1）的**第 2 轮复核**，只读重跑 `tools/ctms-perm-audit.ps1`（**未改脚本判定逻辑**、未改任何 `@PreAuthorize` / 菜单 SQL）。
> **结论：仍未闭合 → 判 `failed`**。方向① 菜单有、后端无 **剩 2 个**：`ctms:migration:claim`、`ctms:migration:ignore`
> —— 这两个点的写端点属 **t22（7.3 草案认领与批量绑定）**，该任务当前仍是 `pending / attempt 0`（assignee=backend），
> 本轮执行时尚未落地。方向② 后端有、菜单无 = **0**，该方向已闭合。
> ⚠ 也就是：契约里「迁移 4 个由 t1 闭合」的前提**只对了一半** —— t1 只闭了 `list`/`scan`，`claim`/`ignore` 要等 t22
> （这与 t5 自己的交付记录「只剩 migration:claim/ignore 给 t22」一致）。t23 的依赖声明写的是 t1+t5，**缺 t22**，
> 所以它在 t22 之前就被调度了 —— 建议把 t22 加进 t23 的依赖（或把 t23 顺延到 t22 completed 之后），不要改判据。
> ⚠ **判据边界**（防误读）见 **§11-8**：本审计只证"源码与菜单两侧对齐"，**不证运行态已生效**（重打包/起服由 10.2 覆盖）。

## 11-1 本轮命令与原始输出（脚本退出码 1 = 有差异）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1
# EXIT=1（脚本按设计用退出码表达断言结果）
```

```
==> 1. 受控自证：hyphen 自检 … [OK]（含 ctms:contract-item:add）
==> 2. 菜单侧集合 A：菜单 SQL 文件 vs 真库 sys_menu
    菜单 SQL 声明权限点：26 个；声明 menu_id：27 行
    真库 ctms 权限点行数：26 行 / 去重 26 个
    [OK] 菜单 SQL 声明的权限点数 = 26（实际 26）
    [OK] 菜单 SQL 声明的菜单行数 = 27（1 目录 + 4 菜单 + 22 按钮，实际 27）
    [OK] 带 ctms 权限点的菜单行数 = 26（=权限点数；目录行 perms 为 NULL 不计数，实际 26）
    [OK] 真库 perms 集合 == 菜单 SQL 声明集合（证明真库执行的就是本文件）
    [OK] 本批 27 个 menu_id 在真库的行数 = 27（SELECT COUNT(*) 与文件自检①一致，实际 27）
    [OK] 本批 menu_id 覆盖的 ctms 权限点数 = 26（实际 26）
    [OK] 第 6 组新增权限点 ctms:attachment:list 已在真库菜单行中（25 → 26 口径生效）
==> 3. 后端侧集合 B：ruoyi-ctms 源码里的 ctms:* 注解
    扫描 .java 文件：58 个；权限点字符串出现 93 次（其中 @PreAuthorize/注解行 66 次）
    后端去重权限点：24 个
    [OK] 后端集合不依赖注释：全部命中集合 == 注解行集合（注释里提过的权限点不算"有实现"）
==> 4. 双向差异清单（两个方向都必须打印）
    方向① 菜单有、后端无（共 2 个）：
      - ctms:migration:claim   [sys_menu.menu_id = 9F2C0000000000000000000000000040]
      - ctms:migration:ignore   [sys_menu.menu_id = 9F2C0000000000000000000000000041]
    方向② 后端有、菜单无（共 0 个）：
          （空）
    [!!] 方向① 菜单有后端无：差异为空（实际 2 个）
    [OK] 方向② 后端有菜单无：差异为空（实际 0 个）
    [!!] 集合 A == 集合 B（两侧各 26 / 24 个）
------------------------------------------------------------------------------
权限点双向核对未通过：13 条通过 / 2 条失败。
  差异方向① 菜单有后端无：2 个 -> ctms:migration:claim, ctms:migration:ignore
  差异方向② 后端有菜单无：0 个 ->
  修法由任务负责人决定：本脚本只报告，不改 @PreAuthorize、不改菜单 SQL。
```

## 11-2 闭合前后对照（t12 attempt 1 的 7 个方向①差异 → 现状）

| # | 权限点 | 菜单行 | 闭合者（现状） | 使用位置 |
| --- | --- | --- | --- | --- |
| 1 | `ctms:migration:list` | `…0014` | ✅ t1（CtmsMigrationController） | `CtmsMigrationController.java:65` |
| 2 | `ctms:migration:scan` | `…0039` | ✅ t1 | `CtmsMigrationController.java:51` |
| 3 | `ctms:migration:claim` | `…0040` | ❌ **仍未闭合**（待 t22） | —（后端无使用） |
| 4 | `ctms:migration:ignore` | `…0041` | ❌ **仍未闭合**（待 t22） | —（后端无使用） |
| 5 | `ctms:contract-item:add` | `…0028` | ✅ t5（行项写端点） | `CtmsContractController.java:304` |
| 6 | `ctms:contract-item:edit` | `…0029` | ✅ t5 | `CtmsContractController.java:315` |
| 7 | `ctms:contract-item:remove` | `…0030` | ✅ t5 | `CtmsContractController.java:329` |

**7 → 5 已闭合 / 2 未闭合**；两个方向里方向② 自 t12 起就是 0（未变）。

## 11-3 26 个权限点各自的使用位置（脚本 §5 原样输出；菜单侧全列）

```
ctms:attachment:list          5  CtmsAttachmentController.java:72, :89, :117, :136, :148
ctms:contract:add             2  CtmsContractController.java:351, :376
ctms:contract:edit            4  CtmsAttachmentController.java:89, :136, CtmsContractController.java:388, :414
ctms:contract:export          1  CtmsContractController.java:106
ctms:contract:list            2  CtmsContractController.java:79, :366
ctms:contract:query           3  CtmsContractController.java:252, :262, :289
ctms:contract:remove          2  CtmsContractController.java:425, :443
ctms:contract:status          1  CtmsContractController.java:403
ctms:contract-item:add        1  CtmsContractController.java:304
ctms:contract-item:edit       1  CtmsContractController.java:315
ctms:contract-item:list       1  CtmsContractController.java:279
ctms:contract-item:remove     1  CtmsContractController.java:329
ctms:migration:claim          0  —（后端无使用）
ctms:migration:ignore         0  —（后端无使用）
ctms:migration:list           1  CtmsMigrationController.java:65
ctms:migration:scan           1  CtmsMigrationController.java:51
ctms:partner:add              6  CtmsPartnerController.java:88, :169, CtmsProductMasterController.java:104, :179, :254, :332
ctms:partner:edit             6  CtmsPartnerController.java:100, :181, CtmsProductMasterController.java:119, :194, :269, :347
ctms:partner:list             7  CtmsPartnerController.java:56, :137, CtmsProductMasterController.java:63, :78, :151, :226, :304
ctms:partner:query            8  CtmsPartnerController.java:68, :78, :149, :159, CtmsProductMasterController.java:91, :166, :241, :319
ctms:partner:remove           6  CtmsPartnerController.java:123, :204, CtmsProductMasterController.java:134, :209, :287, :362
ctms:partner:status           2  CtmsPartnerController.java:112, :193
ctms:tag:add                  1  CtmsTagController.java:69
ctms:tag:edit                 1  CtmsTagController.java:81
ctms:tag:list                 2  CtmsTagController.java:47, :59
ctms:tag:remove               1  CtmsTagController.java:93
```

（26 行里 **24 行有实现、2 行为空**；与 §11-1 的"后端去重 24 个"一致 —— 两个数字互相印证，说明脚本的集合统计没有漏算。）

## 11-4 口径数字与文档漂移（F2）

| 检查 | 期望 | 本轮实测 | 结论 |
| --- | --- | --- | --- |
| 菜单 SQL 声明 / 真库 `ctms:*` 权限点 | 26 | 26 / 去重 26 | ✅ |
| 本批 `menu_id` 行数（含目录行） | 27 | 27（脚本断言 [OK]） | ✅ |
| 带 `ctms:*` 的菜单行数 | 26 | 26 | ✅ |
| 后端 `@PreAuthorize` 去重权限点 | 26 | **24** | ❌ 差 2（`migration:claim`/`ignore`） |
| tasks.md §2.7 旧口径「25 个权限点 / 行数 26」 | 不再作为现行口径 | 残留 3 处均为**显式更正或历史说明**：`:96` 回滚演练记录已注明"当时口径；（第 6 组后为 26）"、`:111` 是更正说明本身、`:401` 是第 6 组交付记录里的 25→26 变更说明 | ✅ F2 已闭合 |

## 11-5 可重复性与只读声明

- 脚本完整性（本轮 check 阶段**之后未修改**）：`tools/ctms-perm-audit.ps1`
  **SHA256 = `48F9C58184C54E82AF5E33DCCD804CBE35635A160F552BC6A1A294D70A23192D`**，mtime `2026-10-05 14:24:55`，12582 bytes
  （即 t12 attempt 1 修好后的那一版；本任务 inScope 不含脚本，未做任何改动）。
- 本轮只做了：跑脚本（只读 SELECT `sys_menu` + 读源码）、跑契约第二条计数命令、查 `tasks.md` 文本、取文件哈希；
  **未改**任何 `@PreAuthorize`、菜单 SQL、脚本判定逻辑或 UI。
- 复跑（闭合后应得退出码 0、两个方向均为 0）：
  ```powershell
  cd F:\dsh\ruoyiOA
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1   # 退出码 0
  cd ruoyi-vue-oa-master
  (Select-String -Path ruoyi-ctms\src\main\java\com\ruoyi\ctms\**\*.java -Pattern 'ctms:[a-z-]+:[a-z-]+' -AllMatches).Matches.Value |
    Sort-Object -Unique | Measure-Object | Select-Object -ExpandProperty Count        # 26
  ```

## 11-6 本轮 finding（如实记，供队长分派）

- **F1（blocker）**：方向① 仍剩 `ctms:migration:claim`（`sys_menu.menu_id=…0040`）与 `ctms:migration:ignore`（`…0041`）
  无后端 `@PreAuthorize` 使用点 → 9.1b 的"差异为空"未达成，`ctms-perm-audit.ps1` 退出码 1。
  **修法不在本任务范围**：由 **t22（7.3 草案认领与批量绑定）** 落地认领/忽略端点时逐字使用这两个权限点（菜单 SQL 已就位，不得改名）。
- **F2（low，流程）**：t23 的依赖声明只有 t1+t5，但 t1 只闭合了 `migration:list/scan`；`claim/ignore` 依赖 t22。
  建议把 **t22 加进 t23 的 dependencies**（或等 t22 completed 再放行复核），否则复核必然再遇同一处失败。
  这与 t5 交付记录里的「只剩 migration:claim/ignore 给 t22」完全一致，不是新发现，只是依赖图漏了这条边。

## 11-7 attempt 2 复核（同一结果）＋ 由 **t24（9.1c）** 接手（队长裁决，2026-10-05）

**attempt 2 的审计结果**（该次运行发生在收到「t23 不要再跑」指示**之前**，仅为如实留档；之后未再复跑）：

```
EXIT=1
权限点双向核对未通过：13 条通过 / 2 条失败。
  [!!] 方向① 菜单有后端无：差异为空（实际 2 个）
  [!!] 集合 A == 集合 B（两侧各 26 / 24 个）
  差异方向① 菜单有后端无：2 个 -> ctms:migration:claim, ctms:migration:ignore
  差异方向② 后端有菜单无：0 个 ->
```

与 attempt 1 **完全同因同结果**（方向① 仍 2 个、方向② 0、集合 26 vs 24；菜单侧 7 条断言仍全 [OK]）。

**当时 t22 的实际状态**（说明本轮属"t22 落地前的重复派发"，不是新缺陷）：

- `team.json`：t22 `status = claimed`、`assignee = backend`、`attempt = 1`（updatedAt = 1791183805079）；
- `CtmsMigrationController.java` mtime `2026-10-05 14:31:30`，仍**只有** `@PreAuthorize(…scan)` + `POST /scan`（:51/:53）与 `@PreAuthorize(…list)` + `GET /drafts`（:65/:66）；
- 脚本未改：SHA256 仍 `48F9C58184C54E82AF5E33DCCD804CBE35635A160F552BC6A1A294D70A23192D`、mtime `14:24:55`、12582 bytes。

**裁决与交接**：队长已按 §11-6 的 F2 处理 —— **t23 不再重跑**，最终判据由新建的 **t24（9.1c 权限点闭合复核，dependencies = t1 + t5 + t22）** 承担；t18 的依赖也已同步指向 t24。
t24 的「闭合前后对照」可直接引用本文件的 **§11-2**（t12 attempt 1 的 7 个差异 → 5 已闭合 / 2 未闭合）与 **§11-3**（26 个权限点各自的使用位置）。

**修正后的闭合映射**（t24 的验收条款已按此写）：`t1 → ctms:migration:{list,scan}`、`t5 → ctms:contract-item:{add,edit,remove}`、`t22 → ctms:migration:{claim,ignore}`。

> ⚠ 判据**不依赖运行中的 jar**：`ctms-perm-audit.ps1` 比对的是"真库 `sys_menu.perms` 集合 vs **源码文本**里的 `@PreAuthorize` 集合"，
> 所以 t24 只需要 **t22 的源码**落地，不必等统一重打包（统一重打包是 t18/t19 的需要）。

## 11-8 本审计的**判据边界**（防误读：源码一致 ≠ 运行态生效）

> 队长 2026-10-05 要求补记；t24（9.1c）执行时请保留本节（可原样引用）。

- **本审计证什么**：`tools/ctms-perm-audit.ps1` 的判据是 **两个集合的文本一致性** ——
  ① 真库 `SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%'`；② `ruoyi-ctms/src/main/java` 里
  `@PreAuthorize("@ss.hasPermi('ctms:…')")` 的**源码文本**。两者相等 ⇒ 权限点定义（菜单）与源码声明**逐字对齐**。
- **本审计不证什么（重要）**：它**证不了运行态生效**。典型反例见 `DEV-ENV.md` §6.46：`@PreAuthorize` 改了但**没重打包**，
  运行中的 jar 还是旧的 —— 这时 `/getInfo` 的 `permissions` 里**明明有**该权限点，同一 token 调接口**仍然 403**
  （判据完全基于源码文本与库，看不见这个差异）。
- **运行态由谁覆盖**：**10.2 交付门禁全绿**（停后端 → `mvn -B -DskipTests -pl ruoyi-ctms clean install` →
  `-pl ruoyi-admin clean package` → **核对 jar 时间戳** → 重启 → 跑接口验收），以及各真实环境脚本
  （`tools/authz-check.ps1`、`tools/ctms-contract-check.ps1`、`tools/ctms-attachment-check.ps1` 等）里的 403/200 断言。
  需要"产物里到底有没有这个权限点"时，按 §6.46 直接在 `.class` 里查字符串作为补充证据。
- **结论口径**：本文件里 9.1 / 9.1b / t24 的"**通过**"应读作"**权限点集合在源码与菜单两侧已对齐**"，
  **不得**读作"权限点已在运行环境生效"。后者以 10.2 的门禁结果为凭。

---

# 12. 任务 9.1c（t24）：权限点闭合复核（**最终：差异为空 → 通过**）

> 执行：2026-10-05（qa attempt 1）｜依赖 t1 + t5 + **t22**（t23 的依赖图漏了 t22，故由本任务取代它作为最终判据）。
> 交付物沿用 `tools/ctms-perm-audit.ps1`（**未改判定逻辑**）与本文件；只读核对，未改任何 `@PreAuthorize` / 菜单 SQL。
> **结论：通过** —— `ctms-perm-audit.ps1` **退出码 0**、**15 条断言全绿**、两个方向差异均为 **0**、
> 集合 A（菜单）**26** == 集合 B（后端）**26**、菜单行 **27**。
> ⚠ 按 §11-8 的判据边界：这只证明"**源码与菜单两侧已对齐**"，运行态生效由 **10.2**（重打包 + 起服 + 接口验收）覆盖。

## 12-1 本轮原始输出（脚本退出码 0）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1
# EXIT=0
```

```
==> 1. 受控自证：hyphen 自检 … [OK]（含 ctms:contract-item:add）
==> 2. 菜单侧集合 A：菜单 SQL 文件 vs 真库 sys_menu
    菜单 SQL 声明权限点：26 个；声明 menu_id：27 行
    真库 ctms 权限点行数：26 行 / 去重 26 个
    [OK] 菜单 SQL 声明的权限点数 = 26（实际 26）
    [OK] 菜单 SQL 声明的菜单行数 = 27（1 目录 + 4 菜单 + 22 按钮，实际 27）
    [OK] 带 ctms 权限点的菜单行数 = 26（=权限点数；目录行 perms 为 NULL 不计数，实际 26）
    [OK] 真库 perms 集合 == 菜单 SQL 声明集合（证明真库执行的就是本文件）
    [OK] 本批 27 个 menu_id 在真库的行数 = 27（SELECT COUNT(*) 与文件自检①一致，实际 27）
    [OK] 本批 menu_id 覆盖的 ctms 权限点数 = 26（实际 26）
    [OK] 第 6 组新增权限点 ctms:attachment:list 已在真库菜单行中（25 → 26 口径生效）
==> 3. 后端侧集合 B：ruoyi-ctms 源码里的 ctms:* 注解
    后端去重权限点：26 个
    [OK] 后端集合不依赖注释：全部命中集合 == 注解行集合
==> 4. 双向差异清单（两个方向都必须打印）
    方向① 菜单有、后端无（共 0 个）：
          （空）
    方向② 后端有、菜单无（共 0 个）：
          （空）
    [OK] 方向① 菜单有后端无：差异为空（实际 0 个）
    [OK] 方向② 后端有菜单无：差异为空（实际 0 个）
    [OK] 集合 A == 集合 B（两侧各 26 / 26 个）
------------------------------------------------------------------------------
权限点双向核对通过：15 条断言全绿，差异为空（菜单 26 / 后端 26，菜单行 27）。
```

契约第二条命令（原样）输出：`(… | -AllMatches).Matches.Value | Sort -Unique | Measure-Object | Count` → **26**。

## 12-2 多轮对照：t12 首轮 7 个方向①差异的闭合去向

| # | 权限点 | 菜单行 | 状态 | 闭合者 | 当前使用位置（t24 实测） |
| --- | --- | --- | --- | --- | --- |
| 1 | `ctms:migration:list` | `…0014` | ✅ | t1 | `CtmsMigrationController.java:69` |
| 2 | `ctms:migration:scan` | `…0039` | ✅ | t1 | `CtmsMigrationController.java:55` |
| 3 | `ctms:migration:claim` | `…0040` | ✅ | **t22**（7.3 认领） | `CtmsMigrationController.java:91` |
| 4 | `ctms:migration:ignore` | `…0041` | ✅ | **t22**（7.3 忽略） | `CtmsMigrationController.java:109` |
| 5 | `ctms:contract-item:add` | `…0028` | ✅ | t5 | `CtmsContractController.java:304` |
| 6 | `ctms:contract-item:edit` | `…0029` | ✅ | t5 | `CtmsContractController.java:315` |
| 7 | `ctms:contract-item:remove` | `…0030` | ✅ | t5 | `CtmsContractController.java:329` |

**7 / 7 已闭合**。轮次轨迹：t12 attempt 1 = 7 个差异（failed）→ t12 attempt 2 = 5 个（t1 闭了 list/scan）→
t23 attempt 1/2 = 5 已闭合 / 2 未闭合（等 t22）→ **t24 = 0 个差异（通过）**。
（`CtmsMigrationController` 的 `list/scan` 行号由 :65/:51 变为 :69/:55，是 t22 在其上方新增认领/忽略端点所致，属正常位移。）

## 12-3 26 个权限点各自的使用位置（脚本 §5 原样输出，26 行全有实现）

```
ctms:attachment:list          5  CtmsAttachmentController.java:72, :89, :117, :136, :148
ctms:contract:add             2  CtmsContractController.java:351, :376
ctms:contract:edit            4  CtmsAttachmentController.java:89, :136, CtmsContractController.java:388, :414
ctms:contract:export          1  CtmsContractController.java:106
ctms:contract:list            2  CtmsContractController.java:79, :366
ctms:contract:query           3  CtmsContractController.java:252, :262, :289
ctms:contract:remove          2  CtmsContractController.java:425, :443
ctms:contract:status          1  CtmsContractController.java:403
ctms:contract-item:add        1  CtmsContractController.java:304
ctms:contract-item:edit       1  CtmsContractController.java:315
ctms:contract-item:list       1  CtmsContractController.java:279
ctms:contract-item:remove     1  CtmsContractController.java:329
ctms:migration:claim          1  CtmsMigrationController.java:91
ctms:migration:ignore         1  CtmsMigrationController.java:109
ctms:migration:list           1  CtmsMigrationController.java:69
ctms:migration:scan           1  CtmsMigrationController.java:55
ctms:partner:add              6  CtmsPartnerController.java:88, :169, CtmsProductMasterController.java:104, :179, :254, :332
ctms:partner:edit             6  CtmsPartnerController.java:100, :181, CtmsProductMasterController.java:119, :194, :269, :347
ctms:partner:list             7  CtmsPartnerController.java:56, :137, CtmsProductMasterController.java:63, :78, :151, :226, :304
ctms:partner:query            8  CtmsPartnerController.java:68, :78, :149, :159, CtmsProductMasterController.java:91, :166, :241, :319
ctms:partner:remove           6  CtmsPartnerController.java:123, :204, CtmsProductMasterController.java:134, :209, :287, :362
ctms:partner:status           2  CtmsPartnerController.java:112, :193
ctms:tag:add                  1  CtmsTagController.java:69
ctms:tag:edit                 1  CtmsTagController.java:81
ctms:tag:list                 2  CtmsTagController.java:47, :59
ctms:tag:remove               1  CtmsTagController.java:93
```

## 12-4 口径数字（真库 + 脚本自检双重印证）

| 项 | 期望 | t24 实测 |
| --- | --- | --- |
| `ctms:*` 权限点（去重，真库） | 26 | **26** |
| 菜单 SQL 声明权限点 / menu_id | 26 / 27 | **26 / 27** |
| 本批 27 个 `menu_id` 在真库的行数 | 27 | **27** |
| 27 行按类型分解 | 1 目录(M) + 4 菜单(C) + 22 按钮(F) | **M=1 / C=4 / F=22（total=27）** |
| 带 `ctms:*` 权限点的菜单行 | 26 | **26**（目录行 `perms` 为 NULL） |
| 4 个 C 类菜单都挂 `*:list` | 4 | **4** |
| 后端 `@PreAuthorize` 去重权限点 | 26 | **26** |
| 方向① / 方向② 差异 | 0 / 0 | **0 / 0** |

> 注：验收原文写「菜单行数 27（含 1 个 C 类父菜单）」——精确分解是 **1 个 M 类目录（`合同管理`，`perms` 为 NULL）+ 4 个 C 类菜单 + 22 个 F 类按钮**；
> 27 行里只有 26 行带权限点。实测数字与菜单 SQL 文件自检①（应为 27）一致。

## 12-5 可重复性、只读与复跑

- 脚本完整性：`tools/ctms-perm-audit.ps1` **SHA256 = `48F9C58184C54E82AF5E33DCCD804CBE35635A160F552BC6A1A294D70A23192D`**，
  mtime `2026-10-05 14:24:55`、12582 bytes —— **与 t23 两轮复核时完全相同**（本次 check 阶段之后未被修改；脚本不在本任务 inScope）。
- 只读声明：本轮只跑脚本（只读 SELECT `sys_menu` + 读源码）、跑契约计数命令、查菜单类型分解（只读 SELECT）、取文件哈希；
  **未改**任何 `@PreAuthorize`、菜单 SQL、脚本判定逻辑或 UI。
- **文档漂移（t12 的 F2）已闭合**：`tasks.md` §2.7 现行口径为 26 个权限点 / 27 行；文中残留的 3 处「25」均为**显式更正或历史说明**
  （`:96` 回滚演练记录标注"当时口径；（第 6 组后为 26）"、`:111` 是 t5 的更正说明本身、`:401` 是第 6 组 25→26 变更记录）。
- 复跑（可复制）：
  ```powershell
  cd F:\dsh\ruoyiOA
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1   # 期望：退出码 0、15 条断言全绿
  cd ruoyi-vue-oa-master
  (Select-String -Path ruoyi-ctms\src\main\java\com\ruoyi\ctms\**\*.java -Pattern 'ctms:[a-z-]+:[a-z-]+' -AllMatches).Matches.Value |
    Sort-Object -Unique | Measure-Object | Select-Object -ExpandProperty Count        # 期望：26
  ```
- ⚠ 仍未覆盖的：**运行态**。8080 上此刻仍是 14:00:06 的旧 jar（认领/忽略与行项写端点尚未生效）→
  按 §11-8，运行态由 **10.2**（重打包 + 起服 + 接口验收）证明；本节的"通过"仅指源码与菜单两侧对齐。



