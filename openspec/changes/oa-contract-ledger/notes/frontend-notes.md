# 前端交付说明（2.0 B3 合同台账 / 第 8 组）

> 变更集：`oa-contract-ledger`｜任务：8.1~8.6（前端 5 个页面 + 本笔记）｜本文件由 **t11（8.6）** 交付
> 规格：`specs/ctms/contract-ledger`、`specs/ctms/contract-commercials`、`specs/ctms/business-partners`、`specs/ctms/contract-migration`
> 技术决策：`design.md` D-8（`views/ctms/**` + element-ui 既有组件 + 动态路由）、D-9（字典与权限点落点）
> 权限点真源：`sql/二开-合同台账-菜单.sql`｜后端接口面另见 `notes/migration-notes.md`（迁移四个端点）
> 生成日期：2026-10-05

**一句话**：合同台账的 5 个页面全部落在 `src/views/ctms/**`，**路由由菜单 SQL 动态注册**（不在 `router/index.js` 写死业务路由）；
对外调用全部收敛在 `src/api/ctms/**`；按钮权限点与菜单 SQL 的 26 个 `ctms:*` 权限点**逐项对应**；日期一律 `yyyy-MM-dd`。

---

## 0. 真源清单（先看这张，不要抄数字）

| 关注点 | 真源 | 位置 |
| --- | --- | --- |
| 页面目录与路由约定 | `src/views/ctms/README.md` + 菜单 SQL | `ruoyi-vue-oa-ui-master/src/views/ctms/README.md`；`sql/二开-合同台账-菜单.sql` |
| 权限点集合（26 个 / 菜单行 27） | `sys_menu`（由菜单 SQL 幂等写入） | `sql/二开-合同台账-菜单.sql`：结构注释 19~33 行；INSERT 55 行起；自检 150~186 行 |
| 动态路由解析 | `store/modules/permission.js` | `loadView` 约 113~118 行（`@/views/${component}`） |
| 按钮权限语义 | `src/directive/permission/hasPermi.js` | `inserted` 里 `el.parentNode.removeChild(el)`（**移除节点**，不是置灰） |
| 合同金额/数量/质保到期口径 | 后端 `ContractRules` ↔ 前端 `contract-rules.js` | `ruoyi-ctms/.../support/ContractRules.java`；`src/views/ctms/contract/contract-rules.js` |
| 档案校验口径与文案 | 后端 `PartnerRules` ↔ `partner/index.vue` 的 `MSG_*` | `ruoyi-ctms/.../support/PartnerRules.java` |
| 标签自动同步口径 | 后端 `CtmsTagServiceImpl`（`FRAMEWORK_TAG_NAME`、`syncAutoTags`、`BUILTIN_*`） | `ruoyi-ctms/.../service/impl/CtmsTagServiceImpl.java` |
| 附件限制口径 | **接口** `GET /ctms/attachment/object-types` | `CtmsAttachmentController.objectTypes()`（`maxSizeBytes/maxSizeMb/extensions/registered`） |
| 迁移状态机与端点 | `notes/migration-notes.md` §1/§2 | `openspec/changes/oa-contract-ledger/notes/migration-notes.md` |
| 前端纪律与坑 | `DEV-ENV.md` §6（尤其 9/11 主题令牌、20 上传、33 中文 SQL、50 日期门禁、51 孤儿令牌） | `DEV-ENV.md` |

---

## 1. 目录与文件清单（第 8 组）

### 1.1 接口层 `src/api/ctms/`

| 文件 | 覆盖资源 | 使用页面 |
| --- | --- | --- |
| `contract.js` | `/ctms/contract/**`（list/export/{id}/framework/{id}/next-no/status/{id}/restore/change-logs + 标签只读选项） | 合同列表页、合同详情/编辑页 |
| `tag.js` | `/ctms/tag/**`（list/{id}/POST/PUT/DELETE） | 标签管理页 |
| `partner.js` | `/ctms/partner/**`（customer 与 supplier 各 list/options/{id}/POST/PUT/status/DELETE） | 往来单位页；合同表单的选择器 |
| `masterdata.js` | `/ctms/product-type|uom|warehouse|product`（第 3 组铺的） | 合同表单的物料选择器 |
| `migration.js` | `/ctms/migration/**`（scan/drafts/claim/ignore） | 迁移认领页 |
| `attachment.js` | `/ctms/attachment/**`（list/object-types/upload/{id}/download/{id}） | 合同详情/编辑页的附件区 |

### 1.2 页面 `src/views/ctms/`

| 页面文件 | 菜单 `component` | 菜单权限点 | 覆盖任务 |
| --- | --- | --- | --- |
| `contract/index.vue` | `ctms/contract/index` | `ctms:contract:list` | 8.1 列表（多维筛选/分页/标签交集/框架树/含停用） |
| `contract/detail.vue` | **无独立菜单行**（弹窗组件，挂在列表页） | 复用 `ctms:contract:*` | 8.2 详情与编辑（行项/质保/标签/附件/变更历史/只读关联单据） |
| `contract/contract-rules.js` | 不是页面（纯函数模块） | — | 8.2 的金额/数量/质保到期口径实现 |
| `tag/index.vue` | `ctms/tag/index` | `ctms:tag:list` | 8.3 标签字典管理 + 自动标签标记 |
| `partner/index.vue` | `ctms/partner/index` | `ctms:partner:list` | 8.4 客户/供应商档案（双页签） |
| `migration/index.vue` | `ctms/migration/index` | `ctms:migration:list` | 8.5 迁移认领（扫描/认领/忽略） |

> ⚠ 详情/编辑页**刻意没有独立菜单行**：菜单 SQL 里合同台账只有一个 `ctms/contract/index`，
> 所以 8.2 用 `contract/detail.vue` 弹窗组件承载"新增/编辑/详情"三态，由列表页 `index.vue` 打开
> （`<contract-detail ref="contractForm" @refresh="getList" />`）。
> 这样既不违反"不要在 `router/index.js` 写死业务路由"，也不需要为一个弹窗去改菜单 SQL。

---

## 2. 前端路由与菜单注册方式

1. **没有业务路由文件**：`src/router/index.js` 只放布局与通用页；`ctms/**` 的路由全部来自
   `sys_menu` 的 `component` 字段，经 `store/modules/permission.js` 的 `loadView` 解析为
   `@/views/${component}`（`component = 'ctms/contract/index'` → `src/views/ctms/contract/index.vue`）。
2. **路径规则**：目录菜单 `ctms`（`menu_type='M'`、`path='ctms'`）→ 子菜单
   `path='contract'|'tag'|'partner'|'migration'`，最终路径为 `/ctms/<path>`（例：`/ctms/migration`）。
   四个菜单行的 `component` 分别是 `ctms/contract/index`、`ctms/tag/index`、`ctms/partner/index`、`ctms/migration/index`。
3. **页面级权限**：每个菜单行挂一个 `ctms:<资源>:list` 作为页面权限点；未授权账号连菜单都看不到
   （动态路由按权限过滤），这与"按钮级 `v-hasPermi`"是两道独立的门。
4. **注册一个新的合同域页面**的正确做法：写 `src/views/ctms/<目录>/index.vue` → 在
   `sql/二开-合同台账-菜单.sql` 里按 `menu_id` 前缀白名单追加菜单行（幂等：先 DELETE 再 INSERT）→
   重跑该 SQL 并给角色授权。**不要**在 `router/index.js` 里补业务路由。
5. **⚠ 执行菜单 SQL 的纪律（本次实测到的一个环境问题，已修复）**：含中文的 SQL 必须走 stdin 且开头设
   `$OutputEncoding`（DEV-ENV §6.33）；否则 `sys_menu.menu_name` 会落库成 `????`。
   本次交付期间实测 `rad_oa` 的 4 个 ctms 菜单名**曾经就是 `????`**（`select menu_id,menu_name,component
   from sys_menu where component like 'ctms/%'` 四行均为 `????`；根因是 `tools/run-db-sql.ps1` 漏了
   `$OutputEncoding`，**SQL 执行成功、权限点全对、门禁全绿，只有中文被吃掉**）—— 页面功能不受影响。
   **该问题已由 captain 修复（2026-10-05 16:0x）**：给 `run-db-sql.ps1` 补该行后，用**菜单 SQL 真源**幂等重跑
   （`-Database rad_oa -Files "二开-合同台账-菜单.sql"`），本批 27 行 `menu_name` 已恢复为正确中文
   （`HEX(menu_name)` 为 `E5..`/`E6..` 形态、全表 `menu_name LIKE '%?%'` = 0）。
   ⚠ 走查前若仍看到问号，那是**前端会话缓存**里的旧菜单树（来自登录时的 `getRouters`）→ **重新登录**即可，
   不是数据没修好。判定中文有没有被写坏**一律查 `HEX()`**，不要肉眼看（详见 DEV-ENV §6.33）。

---

## 3. 页面与权限点对应表（与菜单 SQL 的 26 个 `ctms:*` 双向一致）

> 核对方法（本地可复跑）：
> ```powershell
> # ① 数据库里的集合（26 个）
> echo "SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%' ORDER BY perms" | `
>   & F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe --host=127.0.0.1 --user=root --database=rad_oa --batch --skip-column-names
> # ② 菜单 SQL 文件里的集合：grep -o "ctms:[a-z-]*:[a-z-]*" sql/二开-合同台账-菜单.sql | sort -u
> # ③ 本文档 §3 表格里的集合：三者的双向差集都为空（t11 已实测：DB 26 / 文件 26 / 文档 26，差集为空）
> ```

| # | 权限点 | 前端使用点 | 说明 |
| --- | --- | --- | --- |
| 1 | `ctms:contract:list` | 菜单行（`ctms/contract/index`） | 页面级权限（列表取数由后端注解把关，前端不重复引用） |
| 2 | `ctms:contract:query` | `contract/index.vue` 行内「详情」；`detail.vue` 只读态与框架详情取数 | `canQueryContract` 同时决定框架树能否补子行 |
| 3 | `ctms:contract:add` | `contract/index.vue` 工具条「新增合同」；`detail.vue`「取号预览」 | 取号预览返回在 `msg` 上，不是 `data` |
| 4 | `ctms:contract:edit` | `contract/index.vue` 行内「修改」；`detail.vue` 保存/上传附件/删除附件/释放质保 | 与 `ctms:attachment:list` 组成附件写操作的 **AND** |
| 5 | `ctms:contract:remove` | `contract/index.vue` 行内「停用」「恢复」 | 停用=软删除（原因必填，30 天内可恢复） |
| 6 | `ctms:contract:status` | `contract/index.vue` 行内「状态变更」 | 进度状态与到货状态互不联动；改「已终止」必须填原因 |
| 7 | `ctms:contract:export` | `contract/index.vue` 工具条「导出」 | B3 阶段导出转 CSV（后端已改真实 Excel；前端可后续切 `$download`） |
| 8 | `ctms:contract-item:list` | **前端未使用** | 行项随整单详情一起返回；仅 `api/ctms/contract.js` 的文档注释提到 |
| 9 | `ctms:contract-item:add` | **前端未使用** | 行项走整单提交（见 §5.3） |
| 10 | `ctms:contract-item:edit` | **前端未使用** | 同上 |
| 11 | `ctms:contract-item:remove` | **前端未使用** | 同上 |
| 12 | `ctms:tag:list` | `tag/index.vue` 菜单行；`contract/index.vue` 与 `detail.vue` 的标签选项 | **标签详情读取也复用 `ctms:tag:list`**：菜单里标签管理没有独立的 query 按钮权限，不要凭空造一个新的标签查询权限点（9.1 的双向核对会当场发现） |
| 13 | `ctms:tag:add` | `tag/index.vue` 工具条「新增标签」 | |
| 14 | `ctms:tag:edit` | `tag/index.vue` 行内「修改」 | |
| 15 | `ctms:tag:remove` | `tag/index.vue` 行内「删除」 | 被（未停用）合同引用的标签由服务端拒绝删除 |
| 16 | `ctms:partner:list` | 菜单行（`ctms/partner/index`）；`detail.vue` 的**物料选择器** | 物料域接口（`/ctms/product/list`）用的就是 `ctms:partner:list` |
| 17 | `ctms:partner:query` | `partner/index.vue` 行内「查看」；`detail.vue` 客户/供应商选项；`migration/index.vue` 档案选择器 | `/options` 的权限点是 **query 而不是 list**；无此权限时选择器降级为只读提示 |
| 18 | `ctms:partner:add` | `partner/index.vue` 工具条「新增」 | |
| 19 | `ctms:partner:edit` | `partner/index.vue` 工具条与行内「修改」 | |
| 20 | `ctms:partner:remove` | `partner/index.vue` 工具条「删除」 | 零引用才放行 |
| 21 | `ctms:partner:status` | `partner/index.vue` 工具条与行内「启用/停用」 | 停用后从 `/options` 消失（不再出现在合同表单选择器） |
| 22 | `ctms:migration:list` | 菜单行（`ctms/migration/index`） | 草案列表 `GET /ctms/migration/drafts` |
| 23 | `ctms:migration:scan` | `migration/index.vue`「扫描历史甲乙方」 | 会写草案表（含 `@Log` 操作日志） |
| 24 | `ctms:migration:claim` | `migration/index.vue` 行内「认领」+「候选档案」入口 | 新建档案 / 绑定已有两条路径同一端点 |
| 25 | `ctms:migration:ignore` | `migration/index.vue` 行内「忽略」 | 已忽略可重复忽略（幂等）、被拒的只有已认领 |
| 26 | `ctms:attachment:list` | `detail.vue` 附件区（列表/下载/刷新） | 与 `ctms:contract:edit` 组成附件写操作的 **AND** |

**前端侧的权限纪律（三条，都是踩过/明确过的）**：

1. **`v-hasPermi` 是"移除节点"而不是"置灰"**：`src/directive/permission/hasPermi.js` 在权限不匹配时执行
   `el.parentNode.removeChild(el)`（超级管理员 `*:*:*` 放行）。所以"无权限账号看不到按钮"是本项目的默认形态，
   写验收/断言时不要按"按钮 disabled"去判断。
2. **`v-hasPermi` 的数组是"任一命中即放行"（OR）**：要表达**AND**（服务端 `hasPermiA and hasPermiB`）时必须
   用 `$auth.hasPermiAnd([...])` 先算出布尔量再决定渲染。当前唯一一处：附件写操作
   `hasPermiAnd(['ctms:contract:edit', 'ctms:attachment:list'])`（详见 §6.2）。
3. **前端不重复实现服务端判定**：列表数据范围、标签同步、状态机授权都由服务端把关；前端只在
   "提前告诉用户为什么会被拒"时做一次同文案的前置校验（例：`partner/index.vue` 的 `MSG_*` 与
   `PartnerRules` 逐字一致）。

---

## 4. 日期与数值约定

### 4.1 日期：一律 `yyyy-MM-dd`

- 所有日期控件使用 `value-format="yyyy-MM-dd"`（大写年份记号 `YYYY-MM-DD` 在 Element UI 2.x 上是**静默出错**）。
- **门禁命令（区分大小写！）**：
  ```powershell
  # 正确①（区分大小写，且必须带 --untracked 才能搜到尚未提交的新文件）
  git grep --untracked "YYYY-MM-DD" -- src/views/ctms src/components
  # 正确②（PowerShell，显式 -CaseSensitive）
  Select-String -Path src\views\ctms\*\*.vue,src\components\*\*.vue -Pattern 'YYYY-MM-DD' -CaseSensitive | Measure-Object
  ```
- ⚠ **两条反向陷阱**（都在 DEV-ENV §6.50）：
  1. `Select-String` **默认大小写不敏感**，不加 `-CaseSensitive` 会把大量**正确的小写** `value-format="yyyy-MM-dd"`
     算进来（假红）；谁都不许给这条门禁加 `-i` 或改成不敏感检索。
  2. `git grep` 默认**只搜已跟踪文件**，新文件不加 `--untracked` 会假通过。
- 后端 `java.util.Date` 的 JSON 形态**不固定**（本仓库没有配 `spring.jackson.date-format`）：可能是
  ISO 串、`yyyy-MM-dd HH:mm:ss`、或毫秒数。`contract/detail.vue` 的 `dayOnly()` 统一裁成 `yyyy-MM-dd` 再喂给控件
  —— 新页面拿日期喂日期控件时沿用这个思路，不要直接把接口原值塞进去。

### 4.2 金额与数量：不用 JS 浮点

- 行总价 = 数量 × 单价，**先** HALF_UP 舍入到 2 位；合同金额 = 各**已舍入**行总价之和（后端 C-1 口径）。
  判别用例：3 行「数量 1 × 单价 1.665」→ 1.67 × 3 = **5.01**（先汇总再舍入会得 5.00；`Math.round` 那套会得 1.66）。
- 实现只有一份：`src/views/ctms/contract/contract-rules.js`（十进制字符串 → BigInt 定点，与后端 `ContractRules` 对齐）。
  **不要在页面里再写一遍**；提交时数量/单价/金额/比例都归一为**定点字符串**（Jackson 按 `BigDecimal` 精确解析）。
- 金额显示（千分位）走 `@/utils/money.js`，不要自己写千分位/大写换算。

### 4.3 主题令牌

- 页面样式只写 **`src/assets/styles/oa-tokens.scss` 里已声明**的 `var(--oa-*)` 令牌，且**不加硬编码回退值**：
  `var(--t, #fff)` 这种形态本身就是信号 —— 回退值一旦被用到说明令牌名写错或不存在。
- 门禁：`tools/audit/audit-theme-tokens.js`（gate=zero，判据 = 使用集合 − 声明集合 = 0，**先剥注释再找使用点**，
  所以文档/注释里提到令牌名不会把门禁打红）。

---

## 5. 接口层与页面的对应关系（含关键口径）

### 5.1 对应关系

| 页面 | 使用到的 api 模块 | 主要端点 |
| --- | --- | --- |
| `contract/index.vue` | `api/ctms/contract` | `/list`、`/export`、`/framework/{id}`、`/tag/list`（只读选项）、`/status`、`/{id}`（DELETE=停用）、`/{id}/restore` |
| `contract/detail.vue` | `api/ctms/contract` + `attachment` + `partner` + `masterdata` | `/ctms/contract`（POST/PUT 整单）、`/{id}`、`/next-no`、`/warranty/{id}/release`、`/{id}/change-logs`；`/ctms/attachment/*`；`/ctms/partner/*/options`；`/ctms/product/list` |
| `tag/index.vue` | `api/ctms/tag` | `/list`、`/{id}`、POST、PUT、`/{id}`（DELETE） |
| `partner/index.vue` | `api/ctms/partner` | customer/supplier 各 `list`、`options`、`{id}`、POST、PUT、`status`、`{id}`（DELETE） |
| `migration/index.vue` | `api/ctms/migration` + `api/ctms/partner` | `/ctms/migration/scan|drafts|drafts/{id}/claim|drafts/{id}/ignore`；`/ctms/partner/*/options` |

### 5.2 三个 POST 的返回形态**不一样**（别一概而论）

| 端点 | 返回 | 能否拿到新 id |
| --- | --- | --- |
| `POST /ctms/contract`（整单新增） | `toAjax(1)` | ❌ 不带 `data.id` → 要 id 就回查库/列表 |
| `POST /ctms/contract/items`（行项新增） | `success(新行项)` | ✅ 带新行项 id |
| `POST /ctms/migration/drafts/{id}/claim` | 认领后的草案 | ✅ `matchedId`（新建档案时就是新档案 id）+ `contractCount`=本次实绑数 |

> 前端实践：整单提交后**关闭弹窗 + 重取列表**；认领成功后**直接用返回值**报"本次实绑 N 份"，
> 不要再猜/再查一次。

### 5.3 行项：走**整单提交**，不提交行项 id，不做行项级缓存

- 现状（`contract/detail.vue`）：行项的增/删是**纯本地**操作（`handleAddItem` push / `handleRemoveItem` splice，不发请求）；
  保存时以 `items` 数组**全量**提交给 `POST/PUT /ctms/contract`；`buildPayload()` 里的行项对象
  **只映射 itemType/productId/name/spec/qty/unitPrice/remark 七个字段，不带 `id`**。
- 为什么这样定：后端三个行项写端点（`POST/PUT /ctms/contract/items`、`DELETE /ctms/contract/items/{id}`）与
  "编辑整单"走的是**同一条全量替换链路**（归一/校验/物料快照/序号重排/先舍入再汇总/`_items` 变更历史都不分叉）。
- ⚠ **行项主键在每次增/改/删之后都会重新生成**：所以
  1. 前端**不要提交行项 id**，也不要缓存行项 id 去做后续操作；
  2. 保存成功后**必须重新取详情**（或按响应刷新）再允许继续编辑行项 —— 用旧 id 去删会删到别的行或直接 404，
     而这类错误在界面上只表现为"删了没反应/删错了"，很难定位；
  3. 当前实现保存成功后**关闭弹窗**（不存在"保存后继续编辑同一页面里的行项"的窗口）；
     将来若要做"保存并继续"交互，必须先用响应/重取刷新行项 id 再放开编辑。
- `fillForm()` 目前仍把 `id: item.id` 存在本地行对象上（**既不提交也没被任何逻辑使用**，纯冗余）：
  它的作用是提醒后来人**不要**顺手拿它做行项级操作。

### 5.4 导出：前端只触发下载，**列定义的真源在后端**

- `/ctms/contract/export` 由后端 `ExcelUtil` 直接写 **xlsx 二进制响应流**（响应体里**没有** `rows`），
  所以前端**不能**按"分页 JSON"解析，也**不能**自造中文表头拼表格 —— **列名、列顺序、列格式的真源是
  后端 `CtmsContractController.ContractExportRow` 的 `@Excel` 注解**，前端只负责触发下载。
- 前端实现：`api/ctms/contract.js` 的 `exportContract(query, filename)` 委托 `utils/request.js` 的通用
  `download()`（`POST` + `responseType: 'blob'` + `blobValidate` + `saveAs`）；页面 `handleExport()` 只给文件名
  （`合同台账_<yyyyMMdd-HHmm>.xlsx`）与筛选条件。**导出是全量**（不受列表分页限制），数据范围仍由服务端强制。
- 失败面：响应体若是 JSON（例如无权限 403），`blobValidate` 判成"非文件"，`download()` 解析失败体并给出
  **可见的错误提示**（`errorCode['403']` =「当前操作没有权限」），**不会**落一个内容为 JSON 的坏文件。
- ⚠ **历史教训（跨组契约漂移）**：第 7 组把该端点从"分页 JSON"改成 Excel 二进制流后**没有回传前端**，
  于是列表页"点导出"永远提示没有数据、导出功能直接不可用。**凡改动端点响应形态（尤其"JSON ↔ 二进制"），
  必须回传调用方并同步本节**；对应的浏览器级断言与门禁项已登记在**本文件 §8.1 的移交清单**里
  （由 captain 回填至 `tasks.md` 的 10.1/10.2 交付记录）。

---

## 6. 两条前端纪律（必须遵守）

### 6.1 自动标签不可被手动清除（也不要提供"手动同步"入口）

- 口径（真源 `CtmsTagServiceImpl`）：与合同类型同名的标签、`框架合同` 标签由服务端在保存合同时
  `syncAutoTags` **自动附加/替换**；手动关联（`auto='0'`）不受影响 —— 规格明确「手动添加的标签不得被自动同步移除」。
- 前端落点：
  - `contract/detail.vue` 的标签区把 `auto==='1'` 的标签**单独放在只读区**（打「自动」徽标），
    手动多选只承载 `auto==='0'` 的标签 → **表单上无法清除自动标签**；
  - `tag/index.vue` 对"系统自动维护的标签名"（`contract_types` 字典取值 + `框架合同`）打「系统自动」徽标，
    删除确认里说明"被引用会被拒、删掉也会自动重建"；
  - **两个页面都刻意不提供任何"同步标签"按钮**：后端没有同步接口，而且手动同步会把别人手工加的标签一起清掉。
    扫描/认领/保存之后**只重取列表**，不做"把某行状态改回去"的本地乐观更新。

### 6.2 附件限制口径**读接口**，不在前端写第二份常量

- 20MB 与后缀白名单（10 种）都必须来自 `GET /ctms/attachment/object-types`
  （响应体**顶层**字段：`registered/plannedB4/maxSizeBytes/maxSizeMb/extensions`，不在 `data` 上）。
- 前端**不得**再写一份 `20971520` 或后缀清单：后端白名单比平台窄，写死两份必然漂移。
  `detail.vue` 的上传前预检（大小、后缀、对象类型是否注册）全部用接口返回的值；接口没取到时**不放行上传**。
- 写操作是 **AND**：服务端要求 `ctms:contract:edit` **且** `ctms:attachment:list`，所以按钮用
  `v-if="canWriteAttachment"`（`$auth.hasPermiAnd([...])`）而不是把两个权限点塞进一个 `v-hasPermi` 数组
  （那会退化成 OR，放进"能上传却看不见附件"的角色）。
- 下载必须走 `GET /ctms/attachment/{id}/download`（鉴权在返回字节之前），不要拼 `/profile/**` 直链；
  用 `blobValidate()` 判定"后端把失败写成了 JSON（HTTP 仍是 200）"，否则会落一个内容是 JSON 的坏文件。

---

## 7. 前端门禁与自检方式

| 门禁 | 命令 | 覆盖 |
| --- | --- | --- |
| 静态审计（10 个） | `node tools\audit\run-all.js` | `.then` 无 catch、loading 配对复位、非响应式赋值、模板引用了不存在的名字、空 catch 计数（基线 71）、孤儿主题令牌（gate=zero）等 |
| 前端用例 | `cd ruoyi-vue-oa-ui-master ; npm.cmd run test:unit` | 39 条（打印版式相关，纯 Node） |
| 生产构建（可选但推荐） | `npx.cmd vue-cli-service build --dest <临时目录>` | 证明页面在真实 webpack/vue-loader 链路可编译 |

**专项自检的写法（本组各任务都用了，建议沿用）**：把纯函数/校验从 `.vue` 里抠出来在 node 里**直接执行**
（例：`contract-rules.js` 的 26 条断言、`partner/index.vue` 的账期/简称校验 12 条）、用 AST 逐条检查
`then` 同链是否有 `catch`、编译模板查 `errors`、按"used ⊆ declared"检查主题令牌、并做**负面对照**
（故意破坏一处 → 断言变红 → 还原），否则"全绿"可能只是脚本静默失效。

---

## 8. 边界与已知遗留（不要当成 bug）

- **前端不使用行项三个写端点**（`ctms:contract-item:add/edit/remove` 在前端没有使用点）：行项走整单提交，理由见 §5.3。
  9.1 的权限点双向核对是"菜单 SQL ↔ 后端注解"，前端使用与否不影响那条门禁。
- **迁移页的"候选档案"是前端本地启发式**：按「档案名与草案原始名称互相包含（忽略大小写/空白）」在**已加载的启用档案**
  里算并预选，只用于排序/预选，**不参与任何写入判定**；认领的绑定对象始终以提交的 `partyId` 为准。
- **迁移页不做本地乐观状态更新**：`ignored` 不复活、`claimed` 可能在出现新的未绑定合同时回到 `pending`，
  一律以服务端返回为准（`notes/migration-notes.md` §2 的状态机）。
- ~~**`rad_oa` 的 4 个 ctms 菜单名当前是 `????`**~~ → **已于 2026-10-05 16:0x 修复（本条保留作追溯）**：
  根因是 `tools/run-db-sql.ps1` 漏了 `$OutputEncoding`（DEV-ENV §6.33，SQL 执行成功、权限点全对、**只有中文被吃掉**），
  captain 补该行后用**菜单 SQL 真源**幂等重跑 `-Database rad_oa`，本批 27 行 `menu_name` 已恢复正确中文
  （全表 `menu_name LIKE '%?%'` = 0）。**走查前若仍见问号 = 前端会话缓存的旧菜单树 → 重新登录即可**；
  判定中文是否被写坏**一律查 `HEX()`**。详见 §2.5 与 DEV-ENV §6.33。
- **换行符不一致**：本组新文件是 LF（与既有 `views/ctms/partner/index.vue` 一致）；
  `contract/index.vue` 因交付过程中用 PowerShell 做过一次行级拼接而是 CRLF。纯外观问题，不影响构建/审计。
- **不在本组做的事**：B4 单据域页面、迁移工具的后端编排（见 `migration-notes.md`）、打印件版式。


### 8.1 移交 10.1 / 10.2 的浏览器级核对清单（**t28 交付时均未执行，切勿误以为已做**）

| # | 核对项 | 落点 | 证据形式 |
| --- | --- | --- | --- |
| ① | 8.2 质保到期日在表单里随「生效日 + 质保期限」**实时联动**（只读框跟随显示 `yyyy-MM-dd`），且提交体**不含**到期日 | 10.1 端到端联调 | 截图或 DOM 读取日志 |
| ② | 8.3 自动标签在**真实页面**上的表现：详情里能看到自动标识、**手动多选区里没有它**（所以清不掉）；手动勾选同名标签保存后自动标签仍在 | 10.1 端到端联调 | 截图（两次保存前后对比） |
| ③ | 8.4 供应商档案表单的**提交拦截点击**：简称留空 → 点「确 定」显示「供应商简称不能为空」；账期天数控件 `:min="0"` 挡掉负数 | 10.1 端到端联调 | 截图 + 后端同文案对照 |
| ④ | 8.1 导出（**t28/F1 修复后**的断言）：库里有合同时点「导出」必须落地 `合同台账_<yyyyMMdd-HHmm>.xlsx`（`PK` 魔数、表头 9 列、甲乙方列为文本、**数据行数 == 列表 `total`**），且**不得**出现"没有可导出的合同" | 10.1 端到端联调（t28 已执行一次，见交付记录） | 下载文件名/大小 + 解包 `xl/worksheets/sheet1.xml` |
| ⑤ | 前后端**导出契约核对**：`POST /ctms/contract/export` 响应为 xlsx `Content-Type`、首两字节 `PK`、体内无 `rows`；前端仍走 `utils/request.js` 的 `download()` | 10.2 交付门禁 | 原始响应头/字节 + 无权限反面对照（JSON → 可见提示、不落坏文件） |

原始输出（截图或 `Select-String` 日志）统一记入 `openspec/changes/oa-contract-ledger/notes/integration-check.md`。
> 说明：原计划同时把这张清单写进 `tasks.md` 的 10.1/10.2，但 t28 的 inScope 未声明 `tasks.md`（写入会被 harness 以 undeclared 拒收，已还原），故按验收口径「在 `notes/frontend-notes.md` 或交付记录里」记在本节；**本表由 captain 回填进 `tasks.md` 的 10.1/10.2 交付记录**（`tasks.md` 统一由 captain 维护，任何任务都不把它放进 inScope）。
- **导出 xlsx 的列与列表页的列不完全相同，这不是缺陷**：导出列集合 = 后端
  `CtmsContractController.ContractExportRow` 的 9 个 `@Excel(name=...)`（合同编号 / 合同名称 / 合同类型 / 甲方 /
  乙方 / 签订日期 / 合同金额 / 进度状态 / 经办人，顺序即列序），列表页另有「到货状态」等列而导出没有 ——
  列定义的真源在后端，前端只触发下载（见 §5.4）。
- **导出的失败面**：无权限（403）等错误返回的是 JSON，前端 `download()` 会判成"非文件"并给出可见提示
  （`errorCode['403']` =「当前操作没有权限」），**不会**落一个内容为 JSON 的坏文件。