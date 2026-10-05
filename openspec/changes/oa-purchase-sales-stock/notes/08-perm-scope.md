# B4 §8 权限点与菜单（t8-perm-scope）落地记录

任务：`openspec/changes/oa-purchase-sales-stock/tasks.md` §8.1（后端部分）+ §8.2 的准备与偏离登记
交付物：`ruoyi-vue-oa-master/sql/二开-进销存-菜单.sql`（幂等增量脚本，111 行）
作者：backend-ledger｜2026-10-05

---

## 1. 落地结果（8.1）

| 项 | 值 |
| --- | --- |
| 新增菜单行 | **111** = 2 目录（M）+ 22 菜单（C）+ 87 按钮（F） |
| 新增权限点 | **97**（+ 复用 B3 的 5 个 `ctms:partner:*`） |
| menu_id 段 | 目录 `…0002`/`…0003`；菜单 `…0101-010A`（单据+库存账）、`…0111-0118`（隐藏表单路由）、`…0131-0134`（主数据）；按钮 `…0201-0292` |
| 执行脚本 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 -Database rad_oa -Files "二开-进销存-菜单.sql"` |

**幂等实测（连跑 3 次，第 3 次后取值）**：

| 指标 | 落库前 | 3 次执行后 |
| --- | --- | --- |
| `sys_menu` 总行数 | 210 | **321**（= 210 + 111，未翻倍） |
| `menu_id LIKE '9F2C%'` | 35 | **146**（= 35 + 111，B3 的 27 行 + workflow 8 行原样保留） |
| `component LIKE 'erp/%'` | 0 | 22（10 单据/库存账 + 8 隐藏表单 + 4 主数据） |
| `perms LIKE 'pur:%'` / `'sal:%'` / `'stk:%'` | 0 / 0 / 0 | 23 / 23 / 51 |
| `perms LIKE 'ctms:%'` | 26 | 30（+4 = 主数据 4 个 C 菜单复用 `ctms:partner:list`） |

**中文完整性**：脚本走 `tools/run-db-sql.ps1`（该文件已含 DEV-ENV §6.33 的 `$OutputEncoding` 修复）。
抽查 `HEX(menu_name)`：进销存 = `E8BF9BE99480E5AD98`、盘点单 = `E79B98E782B9E58D95`、资料库-库存主数据 = `E8B584E69699E5BA932DE5BA93E5AD98E4B8BBE695B0E68DAE` —— 均为真 UTF-8，无 `3F`（`?`）污染。

**待补的最后一步**：在 `ruoyi-vue-oa-master/sql/初始化-全部.sql` 的 B4 段（现 `source 二开-进销存.sql` 之后）追加一行 `source 二开-进销存-菜单.sql`（仿 B3 的 `source 二开-合同台账-菜单.sql`）。**须等 backend-base 的 t1 完成**（他与该文件同期在改，captain 2026-10-05 裁决 ③）。

---

## 2. 偏离登记：tasks.md 8.1「四组菜单」→ 实际「1 个 erp 根 + 1 个 master 目录 + 平级 C 菜单按 order_num 分组」

**tasks.md 8.1 原话**：生成"采购管理/销售管理/库存管理/资料库-库存主数据**四组菜单**"。

**实际落地**：

```
进销存（M …0002 path=erp）
 ├─ 采购申请单 / 采购单                 order_num 1-2      ← 采购组
 ├─ 销售申请单 / 销售订单               order_num 3-4      ← 销售组
 ├─ 入库单/出库单/调拨单/盘点单         order_num 5-8      ← 库存组
 ├─ 库存明细 / 库存流水                 order_num 9-10     ← 库存账
 └─ 资料库-库存主数据（M …0003 path=master）  order_num 11
      └─ 物料/物料类型/计量单位/仓库      ← 真目录，URL /erp/master/<page>
```

即：**采购/销售/库存三组没有做成 M 目录**，改为 `order_num` 相邻表达分组语义；只有"资料库-库存主数据"是真目录。

**理由（两条平台/前端既成事实，均非本项目新增约定）**：

1. sys_menu 的路由路径是**逐级拼接**的：前端 `ruoyi-vue-oa-ui-master/src/store/modules/permission.js:86`
   （`el.path = lastRouter ? lastRouter.path + '/' + el.path : el.path`，并把 `component === 'ParentView'` 的
   M 目录子级**拍平**）——父目录的 `path` 会成为子路由的一段。
2. 前端 URL 已冻结：`ruoyi-vue-oa-ui-master/src/views/erp/doc/doc-kinds.js:110,156,206,250,301,347,393,448`
   的 `listPath` 是 `/erp/<doc>`（**平级**，无组段），列表壳用
   `DocListShell.vue:372,375,378,399` 的 `$router.push({ path: kind.formPath })` 跳转。

于是若插入 `path=pur` 的采购组目录，动态路由会变成 `/erp/pur/purchase-request`，与已冻结的
`/erp/purchase-request` 不匹配 → 404。captain 2026-10-05 裁决：**不改前端 8 个 `listPath`**
（"拿 8 处返工换 1 个措辞"），本处如实登记偏离。

**同一裁决衍生的第二条结构决定**：8 条 `/erp/<doc>/form` 做成**与列表平级的隐藏路由**
（`visible='1'`、`path=<doc>/form`），不做列表菜单的子路由 —— 因为 `views/erp/doc/list.vue` 里没有
`<router-view>`，嵌套会白屏。

**证据行号汇总**：

| 事实 | 证据 |
| --- | --- |
| 父级路径拼接 + ParentView 拍平 | `ruoyi-vue-oa-ui-master/src/store/modules/permission.js:56-94`（关键 `:86`） |
| 前端冻结的列表/表单 URL | `ruoyi-vue-oa-ui-master/src/views/erp/doc/doc-kinds.js:110-449` |
| 前端冻结的主数据 URL | `ruoyi-vue-oa-ui-master/src/views/erp/master/master-page.js:49,99,135,170` |
| 菜单 `query` 只进侧边栏链接（**没有 `meta.kind`**） | `ruoyi-vue-oa-ui-master/src/layout/components/Sidebar/SidebarItem.vue:92`；`ruoyi-vue-oa-master/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysMenuServiceImpl.java:178`（MetaVo 只有 title/icon/noCache/link） |
| 隐藏路由的先例 | `sys_menu` 既有行 `107AAA1FE985D47FDB080A11C05B9C04`（`path=n/detail/:t`、`visible=1`） |

---

## 3. 冻结口径的落库映射（与 `notes/00-team-brief.md` §9.2 + captain 23:0x 两处修正一致）

| 对象 | 权限点 | 菜单行 |
| --- | --- | --- |
| 采购申请单 | `pur:request:list/query/add/edit/remove/submit/approve/void/unapprove/export/print` | C `…0101` + F `…0201-020A` |
| 采购单 | `pur:order:*` 同上 + **`push`** | C `…0102` + F `…0211-021B` |
| 销售申请单 | `sal:request:*`（11） | C `…0103` + F `…0221-022A` |
| 销售订单 | `sal:order:*`（11）+ **`push`** | C `…0104` + F `…0231-023B` |
| 入库单/出库单/调拨单/盘点单 | `stk:in-order:` / `stk:out-order:` / `stk:transfer:` / `stk:take:` × 11 | C `…0105-0108` + F `…0241-027A` |
| 库存明细 | `stk:stock:list/query/export` + `stk:stock:recalc`（运维） | C `…0109` + F `…0281-0283` |
| 库存流水 | `stk:ledger:list/query/export` | C `…010A` + F `…0291-0292` |
| 库存主数据 | **复用 B3 的 `ctms:partner:list/query/add/edit/remove`（不新增）** | M `…0003` + C `…0131-0134`（F 复用 `…0034-…0038`） |

**三处口径要点（易错，已写进 SQL 文件头）**：

1. **主数据不新增 `stk:product*`**（captain 裁决 a，2026-10-05）：权限点真源是注解 ——
   `ruoyi-vue-oa-master/ruoyi-ctms/src/main/java/com/ruoyi/ctms/controller/CtmsProductMasterController.java:49`
   是 `@RequestMapping("/ctms")`，20 个端点全部 `@PreAuthorize("@ss.hasPermi('ctms:partner:...')")`
   （`:63-365`）。若另起 `stk:product*` 菜单，只拿新菜单的角色访问这 4 页会**全部 403**，
   且会与前端 `erp-const.js:36`、`api/erp/masterdata.js:15` 已按注解冻结的口径相反。
   ⇒ **不存在"菜单已建 / 后端注解缺失"的差异**（这条差异在裁决 a 之后已消解）。
2. **盘点单用 `stk:take`**（captain 裁决 ②，2026-10-05；原冻结表写 `stk:stocktake`）：
   与参考仓库 `stock.take` / `/api/stock/takes` 及前端 `doc-kinds.js:391` 的 `permPrefix` 一致，
   REST base 也定 `/stk/take`（`doc-kinds.js:390`）。
3. **`reject` / `complete` 无独立权限点**，与 `approve` 共用（`erp-const.js:46-60` 的
   `ACTION_PERM_SEGMENT`）；**单据不设 `:status`**（状态流转由 submit/approve/void/unapprove 覆盖）。
   下推点只有 `pur:order:push`、`sal:order:push`：申请单→目标单的下推按参考仓库查**目标单的 `:add`**
   （`doc-rules.js:101-128`）。**用到本表以外的点必须先报文 captain。**

---

## 4. 前端契约（t12 页面落点）

`component` 的取值来自 frontend-erp 的对照表（2026-10-05）：

- 8 类单据列表：`erp/<doc>/index`（`purchase-request|purchase-order|sales-request|sales-order|stock-in|stock-out|stock-transfer|stock-take`）
- 8 条隐藏表单：`erp/<doc>/form`
- 库存明细/流水：`erp/stock-balance/index`、`erp/stock-ledger/index`（URL `/erp/stock-balance`、`/erp/stock-ledger`）
- 主数据四页：`erp/master/{product,product-type,uom,warehouse}/index`（**已交付并构建通过**）

**结论（captain 要求抄送）**：选**专用薄页**，不用共用页 `erp/doc/list|form` + `?kind=`。
理由：共用页要求 `DocListShell` 的 push 带 `?kind=`（`doc/form.vue:41-56` 的解析顺序为
`props.kindCode → route.query.kind → route.meta.kind`，而 `meta.kind` 平台**不提供**，见 §2 证据表），
专用薄页把 kind 写在页面自身，链路上少一个隐式约定；代价是 t12 多 16 个薄文件（每个一行壳调用）。
若 t12 改走共用页，只需改本 SQL 中 16 个 `component` 字符串并重跑（幂等）。

---

## 4.5 空库初始化自检 ⑦ 的漂移与修正（captain 裁决 2026-10-05，归本任务）

### 漂移现象（改前实测，现网 `rad_oa`）

`sql/初始化-全部.sql:101-103` 的 ⑦ 原文：

```sql
SELECT '⑦ B3 菜单行数（应为27 = 1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮；其中 26 行带权限点）', COUNT(*)
  FROM sys_menu
 WHERE menu_id = '9F2C0000000000000000000000000001' OR perms LIKE 'ctms:%'
```

| 查询 | 结果 |
| --- | --- |
| ⑦ 原口径（`menu_id = …0001 OR perms LIKE 'ctms:%'`） | **31**（期望 27） |
| 其中 `perms LIKE 'ctms:%'` 行数 | 30（= B3 的 26 + 本批主数据 4 行 `…0131-…0134`） |

原因：按 captain 裁决 (a)，B4 主数据 4 个 C 菜单的 `perms` 就是 B3 的 `ctms:partner:list`，
于是被 ⑦ 的 `perms LIKE 'ctms:%'` 网住（+4）。文件末尾 `:151-152` 要求"①~⑭ 必须全部等于期望值"，
所以**空库一次性初始化会显示红灯**。

### 裁决与改法（(a) 收窄范围，数字 27 不变）

```sql
 WHERE menu_id = '9F2C0000000000000000000000000001'
    OR (perms LIKE 'ctms:%' AND menu_id <= '9F2C0000000000000000000000000041')
```

**括号必须显式**（OR/AND 优先级）。`…0100` 是天然分界：B3 的 F 段 `…0021-…0041` 全在内、
B4 的 4 行 `…0131-…0134` 全在外。

### 谓词预验证（改前、现网 rad_oa、纯只读）

| 查询 | 期望 | 实测 |
| --- | --- | --- |
| 收窄后 ⑦ | 27 | **27** |
| 收窄后 ⑦ **再剔除本批 111 行** | 27（本批贡献 0） | **27** |
| 本批 menu_id 行数（`…0002`,`…0003`,`…0101-…0292`） | 111 | **111** |
| `COUNT(DISTINCT perms) WHERE perms REGEXP '^(pur\|sal\|stk):'` | 97 | **97** |
| 边界 `…0041` 是否在 ⑦ 内 | 1 | **1** |
| 边界 `…0131` 是否在 ⑦ 外 | 0 | **0** |

"剔除本批后仍为 27"这一条就是 captain 要的证明：**单独跑 B3 段**（库里没有 B4 菜单）时
⑦ 同样自洽 —— 这正是否决 (b) 方案（把标签改成 31）的原因：(b) 在"只跑 B3"的场景会反向不匹配。

### §8.1-A/B 新增断言（2026-10-05 落地；B4 菜单段独立成条，不替代 ⑦）

口径（captain 指定）：**精确 id 白名单计数** + `perms REGEXP '^(pur|sal|stk):'` 的
`COUNT(DISTINCT perms)`。**编号从 ⑮/⑯ 改成无歧义前缀 `§8.1-A`/`§8.1-B`**，原因见 §4.6。

### 待办 → 已完成（2026-10-05）

`sql/初始化-全部.sql` 的三处改动已**一次性**完成（t1 完成后落地，未并发写同一文件）：
① 收窄 ⑦（见上）；② 追加 `§8.1-A`/`§8.1-B` 两条断言；③ B4 段追加 `source 二开-进销存-菜单.sql`。

**改后实测（现网 `rad_oa` 与空库 drill 两次都跑）**：

| 检查项 | 期望 | 现网 rad_oa | 空库 drill（`rad_oa_b4empty`，`-WithBaseline`） |
| --- | --- | --- | --- |
| ①~⑥（上游/B3 基线） | 各自期望 | — | 全绿 |
| ⑦ B3 菜单行数 | 27 | **27** | **27** |
| ⑨~⑭ B4 表/字典/参数/编号配置 | 18/8/4/1/8/24 | **全绿** | **全绿** |
| §8.1-A B4 菜单段行数 | 121 | **121** | **121** |
| §8.1-B 新建权限点个数 | 107 | **107** | **107** |

- 空库 drill 命令：`tools\run-db-sql.ps1 -Database rad_oa_b4empty -WithBaseline` → **exit 0**，
  日志 `logs\b4-empty-drill-final.txt`（含 `source 二开-进销存-菜单.sql` 被编排执行的第 19 步进度标记）。
- **"只跑 B3 段"自洽性**：仍由 §4.5 的"剔除本批后 ⑦ 恒 27"证明；收窄后 ⑦ 的范围只含 ≤ `…0041` 的 B3 行。

---

## 4.6 补充块：`:status` ×8 + 来源单据 `:push` ×2（2026-10-05，captain 裁决 ②）

**背景**：§9.2 的冻结动作集含 `status`，而首版菜单脚本写了"不含 `:status`"（当时只有前端映射可参照）。
落地代码出来后按**注解真源**复核，`status` 与 `push` 的挂法如下（逐条附注解位置）：

| 权限点 | 落地注解位置 | 说明 |
| --- | --- | --- |
| `pur:request:status` / `pur:order:status` | `ErpPurchaseRequestController:347,362` / `ErpPurchaseOrderController:330` | 「置为已完成」（非库存类） |
| `sal:request:status` / `sal:order:status` | `ErpSalesController:236` / `:449` | 同上 |
| `stk:in-order:status` / `stk:out-order:status` | T3 的 `ErpStockIn/OutController`（PUT `/status` 分发入口） | **库存类也注解了** |
| `stk:transfer:status` / `stk:take:status` | T9 的调拨/盘点单据服务与控制器 | **库存类也注解了** |
| `pur:request:push` / `sal:request:push` | `ErpPurchaseRequestController:383` / `ErpSalesController:269` | 下推挂**来源单据**（申请→单） |
| `pur:order:push` / `sal:order:push` | 由 T4b/T5b 的下推端点落地（单→出入库） | 本批已有这两行，保持不变 |

**✅ 偏离登记（2026-10-05 裁决闭环：captain 采纳 8 行的实现）**：captain 裁决 ② 原话是
"4 个非库存类单据各补 1 条 `:status`，库存类不加"。**关键在于他原话指的是业务动作
`complete`（置为已完成）—— 状态机里库存类确实没有该动作；而 `:status` 是权限点**，
库存类控制器有 `PUT /status` 的**分发入口**并注解了它（上表第 3、4 行，T3 的出入库 + T9 的调拨/盘点，
证据是各自的 `@PreAuthorize`）。**"业务动作没有 complete" ≠ "权限点不需要 `:status`"**，两者是不同的东西。
按项目既定口径"**权限点真源是注解**"（同 §9.2 的主数据裁决 a），本批按 **8 个一起建**是正确的：
若只建 4 个，被授权的角色调那 4 个库存类状态端点会 403，双向核对也会缺 4 个点。
⇒ captain 2026-10-05 确认：**8 行正确**；他报文里的 `321 + Δ5 = 326` **作废**，以本文件实测的
**331（210+121）/ 本批 121 行 / 新建权限点 107 个** 为准。

**幂等与计数（补充块落地后）**：连跑两次 `tools\run-db-sql.ps1 -Database rad_oa -Files "二开-进销存-菜单.sql"`
（第二次前先修掉一处"补充块 id 未进 DELETE 白名单"造成的 1062，见下），行数恒为：
`sys_menu` 总 **331**（= 210 + 121）、`9F2C%` **156**（= 35 + 121）、本批 **121** 行
（2 目录 + 22 菜单 + 97 按钮）、新建权限点 **107** 个（`COUNT(DISTINCT perms)`）。

> 踩坑记录：补充块的 10 个 id 第一次**漏进了 DELETE 白名单**（编辑被"先读后改"策略挡下，
> 我只补了另外两处），第二次执行立刻报 `ERROR 1062 Duplicate entry '…020B'`。
> 这正是"幂等必须真跑两次"的价值 —— 只看脚本像是幂等的。已修并连跑两次验证。

**编号冲突处理**：原想用 ⑮/⑯ 标我的两条断言，但 `初始化-全部.sql` 里 backend-base 已用了 ⑮~㉚、
`二开-合同台账.sql` 用了 ㉜/㉝ ⇒ 改用无歧义前缀 `§8.1-A`/`§8.1-B`（空库 drill 日志里各出现 1 次，无重号）。

---

## 5. 尚未完成（t8-perm-scope 的剩余部分）

1. ~~`初始化-全部.sql` 追加 `source 二开-进销存-菜单.sql`~~ ✅ 已完成（见 §4.5 的待办闭环）。
2. **§8.2 数据范围四档实测**（本人/本部门/本部门及下级/全部）：依赖 t3/t7/t8/t9/t6 的接口落地，
   且**必须在 t20 打包冒烟之后**（8080 上跑的必须是含 B4 的 jar，否则 `/stk/*` 直接 404，实测无意义）；
   借 `common` 角色做四档实测 + 多角色取并集 + 范围外详情/导出越权被拒；
   严格按 DEV-ENV §6.48 的"入口自愈 + 收尾还原 + 哨兵"，**`data_scope` 不得留库**。
   实测环境基线是干净的：`sys_role` 13 行 `data_scope` 全为 `'1'`、无 `'5'` 与哨兵残留。
3. 权限点集合双向核对（菜单 perms ↔ 各控制器 `@PreAuthorize`）随 t10-integration 复核；
   本文件末尾的"权限点全集"（SQL 文件尾部同名注释，107 个点）是核对基准。
   **当前源码实测**：`(pur|sal|stk)` 域的 `@PreAuthorize` 去重后 **93 个点**，与本批 107 个的差集 =
   菜单侧多出的 `pur/sal` 的 `:export`/`:print`（4+4）与 `stk:in-order/out-order/transfer/take:print`（4）
   以及 `pur:order:push`/`sal:order:push`（2，待 T4b/T5b 落地）；
   方向是"菜单先行、端点在途"，**不缺注解所需的点**（这也是补 `:status` 8 个的动机）。

---

## 6. §8.2 四档数据范围实测：设计、阻塞与门禁结果（t10，2026-10-05）

### 6.1 阻塞：C-1（公共层 ambiguous `posted`）—— **夹具造不出来**

`erp/base/domain/ErpDocHeader.java` 同时有 `isPosted()`（boolean，`:164`）与
`getPosted()`（String，`:399`）⇒ JavaBeans 属性 `posted` 有歧义 ⇒ MyBatis 反射抛
`Illegal overloaded getter method with ambiguous type for property 'posted'` ⇒ **8 类单据的所有写路径 500**。

复现命令与原始输出（2026-10-05 实测，非旧快照）：
```
locked-run -LockName env -Command "… tools\erp-check.ps1 -Only P-"
[FAIL] P-01 入库单新增（单号 IN 前缀 / draft / posted=0 / 金额 10.00）
  实际：code=500 msg=nested exception is org.apache.ibatis.reflection.ReflectionException:
       Illegal overloaded getter method with ambiguous type for property 'posted' in class
       'com.ruoyi.ctms.erp.base.domain.ErpDocHeader'.
通过 0 / 失败 2
```
影响链：写路径 500 ⇒ 无法经业务接口造单据夹具 ⇒ 结存/流水（由过账产生）也没有
⇒ **§8.2 的"同一批单据与结存数据"无从产生**（实测库内 `t_ctms_stock_in/stock_out/sales_order/
t_ctms_stock/t_ctms_stock_ledger` 全为 0 行）。已报文 captain 派单修 base（**我不改他人文件**）。

调用点清单（供修者一次改净，37 处）：`ErpStockInController:405`、`ErpStockOutController:397`、
`ErpStocktakeController:497`、`ErpTransferController:419` 用 `doc.isPosted()` 装配导出行；
`ErpStockInServiceImpl:245/321/351/382`、`ErpStockOutServiceImpl:235/310/340/371`、
`ErpStocktakeServiceImpl:367/539/570`、`ErpTransferServiceImpl:243/321` 用 boolean 判定；
`*ServiceImpl` 多处用 `getPosted()`（String，映射用）+ 测试断言。

### 6.2 四档矩阵的设计（C-1 修好后即执行）

**账号/部门基线（实测 `sys_user × sys_dept`）**：4 个测试账号**同属 `common` 角色**、
分属 4 个平级部门，父部门都是根 `000AAA111DAE452F9408183D75290000`（测试科技有限公司）：

| 账号 | 部门 | dept.ancestors |
| --- | --- | --- |
| lina | 财务部 `420A091E…` | `0,000AAA…0000` |
| wangqiang | 研发部 `501C3447…` | 同上 |
| zhangwei | 行政部 `A070B84D…` | 同上 |
| zhaomin | 市场部 `CA8DBECE…` | 同上 |

**四档如何彼此可判别**（这是本矩阵的关键设计，不是"随便挑几个部门"）：
- 夹具：4 个账号**各自创建 1 张单据**（登记其 `dept_id`/`create_id` 快照 —— 同时验证"归属部门创建时快照"）。
- `data_scope='1'`（全部）→ 看得到 4 张；`'3'`（本部门）→ 只看得到本部门的 1 张；
  `'5'`（本人）→ 只看得到自己创建的 1 张。
- `'4'`（本部门及下级）**必须有一个"本部门是别人的祖先"的观察者**才能与 `'3'` 区分：
  仓库里唯一的祖先关系是"根部门 → 4 个子部门"，因此把观察者**临时**移到根部门
  （`sys_user.dept_id` 快照/还原 + 哨兵）：根部门 + `'4'` ⇒ 看得到 4 张（含所有下级），
  同一个观察者 + `'3'` ⇒ 看得到 **0** 张（没有行的部门就是根部门）⇒ 两档集合不同，能判别。
- **多角色并集**：给一个账号**临时**再加一个 `data_scope='1'` 的角色（`common` 保持 `'5'`），
  并集 ⇒ 看得到全部（任一档命中即可见）。
- **导出与列表同范围同筛选**：同一筛选下 `export` 的行数 == `list` 的 `total`（R1 教训的双证）。
- **越权被拒**：用范围外单据 id 请求详情/导出 ⇒ 业务码 **403**（不是 500）。

**结存/流水侧（§7 已接好的接线点）**：`/stk/stock/list` 与 `/stk/ledger/list` 的范围片段
由 `ErpStockLedgerQueryServiceImpl.currentScopeSql()`（`ErpDocScope.buildDataScopeSql("l")`）注入；
结存没有 `dept_id/create_id` 列 ⇒ 明细 XML 用
`exists (select 1 from t_ctms_stock_ledger l where … and ( ${dataScopeSql} ))`
即"该（物料, 仓库）存在一条我可见的流水"才可见 ⇒ 四档对结存同样成立（同一批过账数据）。
`/stk/stock/detail`、`/stk/ledger/detail` 范围外 ⇒ 403、不存在 ⇒ 404（T3/T20 的冒烟已证可区分）。

**共享状态纪律（DEV-ENV §6.48 三件套）**：只借 `common` 角色的 `data_scope` + 按需临时
`sys_role_menu` 授权（用于让 4 个账号拿到 B4 的 list/export 权限，因为 `sys_role_menu` 基线为空）；
进入时快照、收尾按快照还原、入口按 `sys_role.remark` 哨兵自愈；**`data_scope` 不得留库**；
夹具按 ASCII 前缀自建自清，收尾断言零残留（结存/流水"只增不改"，只能按前缀 SQL 收）。

### 6.3 已跑完的四道门（真实输出）

| 门 | 命令 | 结果 |
| --- | --- | --- |
| 单测 | `locked-run -LockName build → mvn -B -pl ruoyi-ctms test` | **Tests run: 531, Failures: 0, Errors: 0 / BUILD SUCCESS** |
| 静态审计 | `node tools/audit/run-all.js` | **10 个审计 / 失败 0 / 未自证 0** |
| 越权基线 | `locked-run -LockName env → tools\authz-check.ps1` | **通过 45 项 / 失败 0 项**（自检 45 = 基线 30 + B3 新增 15）⇒ 无回归 |
| 权限点双向 | `tools\ctms-perm-audit.ps1` | **14 通过 / 1 失败（exit 1）** —— 见 6.4 |

### 6.4 `ctms-perm-audit.ps1` 的红项：一条"行数 == 点数"便利恒等式过时（同 ⑦ 类）

```
[!!] 带 ctms 权限点的菜单行数 = 26（=权限点数；目录行 perms 为 NULL 不计数，实际 30）
```
- 位置 `tools/ctms-perm-audit.ps1:127`：`Assert-That ($dbRows.Count -eq 26) …`。
- **实质检查全过**：同一次输出里 `方向① 菜单有后端无：0 个`、`方向② 后端有菜单无：0 个`、
  `集合 A == 集合 B（两侧各 26/26）` 均为 `[OK]`；`:125`（菜单 SQL 声明 26 个点）与
  `:132`（本批 menu_id 覆盖 26 个点）也过。
- 唯一红的是"**行数 == 点数**"：裁决 (a) 让 B4 的 4 个主数据 C 菜单**复用** `ctms:partner:list`
  ⇒ 带 ctms 权限点的**行** 26 → 30，而**点**仍 26。与 `初始化-全部.sql` 的 ⑦（31≠27）**同类**。
- 修法：**(b) 改成 `COUNT(DISTINCT perms)`**（captain 2026-10-05 批准）。理由：`:125`/`:132` 本就是
  "点数"语义，改后三条自洽；且天然容忍"一个权限点挂多行"。**这不是放宽** —— 是把"行数"改回"点数"，
  数字 26 不变、断言条数不变（仍 15 条）、实质检查一行未动。

**改前 → 改后（真实输出留档）**

| | 改前（`logs/t10-perm-audit.txt`） | 改后（`logs/t10-perm-audit-after.txt`） |
| --- | --- | --- |
| `:127` 断言 | `[!!] 带 ctms 权限点的菜单行数 = 26（…实际 30）` | `[OK] 带 ctms 权限点的菜单**点数** = 26（COUNT(DISTINCT perms)；菜单行数 30 属预期 —— B4 主数据 4 个 C 菜单复用 ctms:partner:list，实际去重 26）` |
| 实质检查 | `方向① 0 个` / `方向② 0 个` / `集合 26==26` 全 `[OK]` | 同样全 `[OK]`（未被修改） |
| 汇总 | `权限点双向核对未通过：14 条通过 / 1 条失败`（exit 1） | **`权限点双向核对通过：15 条断言全绿，差异为空（菜单 26 / 后端 26，菜单行 27）`（exit 0）** |

改动仅 2 行（`:119` 的 Info 补一句说明 + `:127` 改判据），`git status` 仅 `M tools/ctms-perm-audit.ps1`。

> ⚠ **踩坑（值得写进 DEV-ENV）**：用不带 BOM 的写法编辑 `.ps1` 会**剥掉 UTF-8 BOM**，
> PS 5.1 随即按 ANSI 读该文件 → 中文变乱码并**破坏字符串引号**，报
> `Missing ] at end of attribute or type literal`（本次改完第一次跑就踩到）。
> 修法：改完立即用 `[System.IO.File]::WriteAllText($p, $txt, (New-Object System.Text.UTF8Encoding($true)))`
> 把 BOM 加回去；同理 `tools/erp-scope-check.ps1` 也必须带 BOM。

### 6.5 新增验收脚本 `tools/erp-scope-check.ps1`（captain 批准独立脚本）

- **落点**：独立脚本（**不并进** `authz-check.ps1` 的 45 条基线、也不塞进 t13 的 `erp-check.ps1`）——
  它自带"借 `common` 改授权 + 收尾还原 + 哨兵"的重纪律，混进别人的基线反而危险（captain 2026-10-05）。
- **登记**：已写进 `DEV-ENV.md` §7 门禁表的 **第 16 条**（t13 复核门禁全表时应能查到）。
- **覆盖**：单据（`/stk/in-order/list` + 详情）＋结存（`/stk/stock/list` + `detail`）＋流水（`/stk/ledger/list`）
  × 四档（`1`/`3`/`4`/`5`）逐格实测 **+ 多角色取并集**（`common`('5') ∪ 第二角色('1')）
  **+ 导出与列表同范围**（解包 xlsx 数数据行 == 列表 total）**+ 范围外 403**（详情/结存/流水三面）。
- **"四档彼此可判别"的实现**（避免假绿，见脚本注释）：夹具 = 4 个账号各建 1 张入库单并审核过账
  （同时产出结存与流水，且登记 dept_id/create_id 快照）；观察者 **zhangwei 临时调根部门**
  → `'4'` 看到 4 张、`'3'` 看到 0 张；**zhaomin 临时调入财务部** → `'3'` 看到 lina 那张、
  `'5'` 看到 zhaomin 自己那张（这条同时证明"归属部门创建时快照"）。
- **纪律**：入口自愈（哨兵 `T10-SCOPE-SENTINEL` 残留则先还原）＋收尾还原（`data_scope`/`remark`/
  `sys_role_menu`/两个账号的 `dept_id`）＋收尾断言"全库无 `data_scope='5'` 残留 + 夹具零残留"。
- **执行结果（2026-10-05 夜间，写路径修复后）：`通过 59 项 / 失败 0 项`，exit 0**。
  命令（**必须经 env 锁**，脚本内无锁）：`locked-run.ps1 -LockName env -Command "… tools\erp-scope-check.ps1"`；
  原始输出 `logs/t10-scope-final.txt`。

**四档矩阵实测（夹具作用域断言：只看本脚本的 4 张夹具单 / 4 个夹具物料，忽略库里他人数据）**

| 档位 / 观察者 | 单据（夹具命中） | 结存（夹具物料） | 流水（夹具物料） | 关键判别性证据 |
| --- | --- | --- | --- | --- |
| `'1'` 全部 @ lina | **4 / 4** | **4 / 4** | **4 / 4** | — |
| `'3'` 本部门 @ lina（财务部） | **1**（lina 那张） | **1** | — | 看不到研发部（wangqiang）那张 ⇒ 跨部门被拦 |
| `'5'` 本人 @ zhaomin | **1**（自己那张） | **1** | — | 看不到**同部门**的 lina 那张 ⇒ `'5'` ≠ `'3'` |
| `'3'` 本部门 @ zhaomin（临时调入财务部） | **1**（lina 那张） | — | — | 仍看不到**自己**那张（dept 是创建时市场部快照）⇒ **快照不回溯** |
| `'4'` 本部门及下级 @ zhangwei（临时在根部门） | **4 / 4** | **4 / 4** | **4 / 4** | 同观察者切 `'3'` = **0** ⇒ `'4'` ≠ `'3'` 可判别 |
| `'5'` ∪ 第二角色(`'1'`) @ zhaomin | **4**（并集） | — | — | 多角色取并集成立；用后即摘除第二角色 |
| 导出（`'3'` @ lina） | xlsx：HTTP 200 + 首两字节 `PK` | — | — | **导出行数 1 == 列表 total 1**（同范围同筛选） |
| 范围外 403 | 单据详情 **403**（`无权访问该入库单`） | 结存详情 **403** | 流水下钻 **0 行** | 三面越权都被拒（不是 500） |

**收尾还原证据**：`common` 授权行 → 0；`data_scope` → 基线 `'1'`；哨兵 `remark` → 清空；
zhangwei/zhaomin 的 `dept_id` → 基线；夹具残留 **结存/流水/单据/物料/仓库/单位/类型 全 0**；
**全库 `data_scope='5'` 计数 = 0**（§6.48 硬要求）。

**本轮修掉的 6 个脚本自身缺陷（都不改动产品代码，供以后改本脚本时避坑）**：
① `$pid` 是 PowerShell 只读自动变量 → 改名 `$prodId`；
② 夹具物料码用首字母 → zhangwei/zhaomin 都是 `Z` 撞号 → 改用全名；
③ **先建单后借授权** → 建单 403 → 改为"先借授权 + 完整重新登录，再建单"；
④ `Relogin` 把 `"a,b,c"` 当单个用户名传给 `[string[]]`（`powershell -File` 的老坑）→ 改为逐个账号起子进程；
⑤ 清理 SQL 自引用 `t_ctms_stock_in_item` → MySQL **1093**；改为从**表头仓库**定位夹具单据（子查询与目标表不同名）；
⑥ **观察者调岗必须在建单之后** —— 否则 zhaomin 的单会带上财务部，`'3'` 与 `'5'` 就不可判别（run4 实测踩到）；
另外 3 处 `$arr[0]` 在单元素数组上被 PowerShell 解包成"首字符"，断言假红 → 改用 `-join ','` 比较。

> **脚本自愈能力**：入口会按 `.cache/t10-scope-baseline.json`（落盘基线）+ 哨兵 + 授权行数 + 调岗偏差
> 四条件任一命中即强制还原，并清掉 `T10S*` 前缀的历史夹具 ⇒ **中途崩溃可重跑**（本脚本三次崩溃后
> 都靠这条恢复到干净态）。


