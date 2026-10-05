# 合同台账（2.0 B3）SQL 执行手册

> 变更集：`openspec/changes/oa-contract-ledger`｜需求：REQ-DATA-002/003/005｜验收：AC-68
> 适用：本目录下与合同台账（CTMS）相关的全部增量脚本。
> **先读这份文档再动手**；本目录的脚本会改动业务库结构，多数**不是**可以随手在生产库重跑的。

---

## 1. 文件清单：每个文件干什么、能不能重跑

| 文件 | 作用 | 幂等 | 能在生产库跑 | 备注 |
| --- | --- | --- | --- | --- |
| `二开-合同台账.sql` | **B3 域 13 张业务表**建表 + 索引 + 外键 + 约束回补三段（回填 → NOT NULL → FK） | ✅ 可反复执行 | ⚠ 慎用 | `DROP TABLE IF EXISTS` + `CREATE`，**会清空这 13 张表的数据**；已上线库若表里已有数据，先看 §2.2 的"提数"口径 |
| `二开-合同台账-字典参数.sql` | 5 个字典类型（`item_types`/`contract_types`/`subjects`/`contract_statuses`/`arrival_statuses`）+ 23 条字典数据 + `warranty_window_days=30` | ✅ | ✅ 可跑 | 幂等；但**会覆盖**这 5 个字典与 1 个参数的当前值 —— 生产环境若已人工调过参数，先备份 |
| `二开-合同台账-编号配置.sql` | 合同编号配置（1 条 `t_code_config` + 5 条规则：业务参数类型码/主体码 + 年 + 月 + 6 位按年序号） | ✅ | ✅ 可跑 | **不会重置计数器**（Redis 键不会被清）；要"从 000001 重新开始"需另行删 Redis 键。⚠ 编号配置**只在这一个文件里**（建表脚本内那份已迁出，避免同一件事两处配置） |
| `二开-合同台账-菜单.sql` | 合同管理的 1 目录 + 4 菜单 + 22 按钮，共 27 行 / 26 个 `ctms:*` 权限点 | ✅ | ✅ 可跑 | `menu_id` 用 `9F2C` 前缀（避开 B1 的 `…A*`、B2 的 `…B*`）；**不含角色授权**（见 §5） |
| `体检-合同台账-<日期>.sql` | **只读**体检：对"待加约束"的每一列输出违例行数 + 可定位的主键与违例值 | ✅（只读） | ✅ | 加约束**之前**必须跑；有违例就先回填，别硬加约束 |
| `影响行数报告-合同台账-<日期>.sql` | 输出"表名 / 受影响行数" + 生成 `<表名>_bak_<日期>` 快照（结构 + 数据） | ⚠ 快照表会 `DROP` 重建 | ✅ | 大表上会占空间；快照表名带日期，回滚脚本按同名日期读取 |
| `回滚-合同台账-<日期>.sql` | 逆序回滚：删 FK → 删索引 → 恢复可空 → `DROP` 本变更集新建的表 + 按快照反向回填 | ✅（对本变更集） | ⚠ 慎用 | **只回滚 B3 新建的对象**，不动任何存量表结构 |
| `初始化-全部.sql` | 空库初始化编排（V1 八件 + B1 + B2 + B3 四件；B4 留占位段） | ⚠ 仅空库 | ❌ | 内部的 V1 脚本会 `DROP TABLE`，只用于**空库** |

> ⚠ **`table.sql` / `data.sql` 是上游基线，永远不改**（PRD §8.4 方案 B）。
> 验证方式：`git diff --stat -- sql/table.sql sql/data.sql` 必须为空。

---

## 2. 执行顺序

### 2.1 空库（新环境 / 新同学上手）

```powershell
$mysql = "F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe"
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master\sql

# ① 上游基线（表 + 基础数据）
Get-Content -Raw -Encoding UTF8 .\table.sql | & $mysql --host=127.0.0.1 --user=root --database=<空库> --default-character-set=utf8mb4
Get-Content -Raw -Encoding UTF8 .\data.sql  | & $mysql --host=127.0.0.1 --user=root --database=<空库> --default-character-set=utf8mb4

# ② 全部二开增量（一条龙，含 B3）
Get-Content -Raw -Encoding UTF8 .\初始化-全部.sql | & $mysql --host=127.0.0.1 --user=root --database=<空库> --default-character-set=utf8mb4
```

执行完，`初始化-全部.sql` 末尾会打出 9 项自检（`①~⑧` 应等于期望值；`⑨` 在 B4 交付前应为 0）。

### 2.2 已上线的库（有数据）——**必须按这个顺序**

```text
① 体检      体检-合同台账-<日期>.sql          ← 只读；有违例先回填
② 快照      影响行数报告-合同台账-<日期>.sql   ← 生成 <表名>_bak_<日期>
③ 建表     二开-合同台账.sql
④ 字典     二开-合同台账-字典参数.sql
⑤ 编号     二开-合同台账-编号配置.sql          ← 必须在字典之后（类型码/主体码语义来自字典）
⑥ 菜单     二开-合同台账-菜单.sql
⑦ 人工     角色授权（见 §5）
⑧ 验收     跑 §6 的门禁
```

顺序理由：

1. **`data.sql` 必须先于 `二开-合同台账.sql`** —— 建表脚本的加 FK 段引用 `sys_user(user_id)`/`sys_dept(dept_id)`，
   回填段还要用基线账号兜底创建人/部门；`sys_user` 为空时加 FK 会报 **1452**。
   这是**预期的阻断**（脚本里不允许用 `SET FOREIGN_KEY_CHECKS=0` 掩盖）；
2. **字典必须在编号之前** —— 编号配置的"类型码/主体码"取值就来自 `contract_types` / `subjects`；
3. **表在所有种子之前** —— 字典/菜单都不依赖表，但编号配置与后续业务依赖表；
4. **菜单最后** —— 它只依赖权限点，与结构无关，放最后便于单独重跑；
5. **体检/快照在建表之前** —— 约束回补三段（回填 → NOT NULL → FK）是**建表脚本内部**的一部分，
   一旦有历史数据违例，回填口径要靠体检结果决定，快照则是回滚的唯一凭据。

### 2.3 一次跑完的验证方式（推荐）

```powershell
# 独立空库演练：建表幂等 ×2、体检逐列比对、快照一致、回滚中止守卫、
# 完整回滚（含"装前快照 → 装 → 回滚回到装前"）、CONSTRAINTS_ONLY —— 68 项断言
powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\b3-sql-drill.ps1
```

它会在一次性库 `b3_rollback_drill`（+ `…-migrate`）里演练并在**收尾 DROP DATABASE**，
不动 `rad_oa` 的业务数据。

---

## 3. 回滚

```powershell
Get-Content -Raw -Encoding UTF8 .\回滚-合同台账-<日期>.sql | & $mysql --host=127.0.0.1 --user=root --database=<库> --default-character-set=utf8mb4
```

回滚脚本按"**删 FK → 删索引 → 恢复可空 → `DROP` 本变更集新建的表**"逆序执行，并：

* 只用 `<日期>` 对应的 `*_bak_<日期>` 快照反向回填（**快照不存在则拒绝执行**，宁可失败也不要"回滚一半"）；
* 每一步打印受影响行数，末尾给出"对象是否已全部消失"的核对 `SELECT`；
* **不触碰**任何存量表（`sys_*` 表一行不改；本变更集只往里插了字典/参数/菜单行，
  回滚时按固定 id 白名单删除，见脚本末尾）。

### 数据处置口径（重要）

| 数据 | 回滚后 |
| --- | --- |
| 字典/参数/菜单行（B3 插入的固定 id） | **删除**（回滚脚本带 DELETE 白名单） |
| 编号配置（`9F2C…C001`） | 删除；Redis 计数器键需另行清理（脚本会打印提示） |
| 13 张业务表的数据 | 随 `DROP TABLE` 消失 —— 若需保留，回滚前先导出（快照表只覆盖"受影响行数"报告里的表） |
| 存量表结构 | 不变（本变更集不修改任何存量列） |

---

## 4. 幂等约定

1. **建表**：`DROP TABLE IF EXISTS` + `CREATE TABLE`（同一脚本连续跑两次都成功）；
2. **增列/加约束**：`information_schema` 守卫 + `PREPARE/EXECUTE`（MySQL 8 没有 `ADD COLUMN IF NOT EXISTS`）；
3. **种子数据**：先按**固定 id / 固定键** `DELETE` 再 `INSERT`（`sys_dict_type.dict_type` 与
   `sys_dict_data.dict_code` 都有唯一性，只写 `INSERT` 第二次就会报错）；
4. **命名**：所有索引/约束**显式命名**（`pk_/uk_/idx_/fk_` 前缀 + 表名），因为回滚脚本要按名字删；
5. **每次执行都以自检 `SELECT` 收尾**，每行带中文检查项与期望值，跑完一眼能看出对不对。

---

## 5. 人工步骤（脚本无法代劳）

1. **角色授权**：本批只建权限点，**不给任何角色授权**。superAdmin 天然全有；
   其它角色（采购主管 / 采购员 / 财务 / 只读…）需要到「系统管理 → 角色管理 → 修改 → 菜单权限」
   勾选"合同管理"下的菜单与按钮，否则界面看不到入口、按钮点了会 403。
   实测口径参见 `notes/` 里的说明：未授权角色 `/getRouters` 里**不含**"合同管理"、也不含任何 `ctms` 字样。
2. **B4 段替换**：`初始化-全部.sql` 里 B4 段落是占位注释。
   B4（`oa-purchase-sales-stock`）交付后，把 `-- source 二开-进销存.sql` 的注释去掉即可。
3. **编号从 000001 开始**：新库天然如此；如果这个库已经试跑过编号，
   需要删掉 Redis 键 `code:gen:seq:9F2C0000000000000000000000C001:*` 才会回到 `000001`。

---

## 6. 验收（跑完 SQL 之后）

```powershell
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js                                  # 静态审计
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1          # 越权防护（含 ctms 权限点）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\serial-numbering-check.ps1 # 编号四条判定 + 既有编号兼容
cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-serial test        # 编号单测
```

SQL 侧的人工核对（都在本目录，只读）：

| 检查 | 期望 |
| --- | --- |
| `SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_NAME LIKE 't_ctms_%'` | 13 |
| `SELECT COUNT(*) FROM sys_dict_type WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')` | 5 |
| `SELECT COUNT(*) FROM sys_dict_data WHERE dict_type='contract_types'` | 7（其中 `status='1'` 的 `OTH` 不参与编号） |
| `SELECT config_value FROM sys_config WHERE config_key='warranty_window_days'` | 30 |
| `SELECT COUNT(*) FROM sys_menu WHERE menu_id='9F2C0000000000000000000000000001' OR perms LIKE 'ctms:%'` | 27（菜单行） |
| `SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE perms LIKE 'ctms:%'` | 26（权限点；第 6 组新增 `ctms:attachment:list`） |
| 接口 `GET /system/dict/data/type/contract_types` | 6 条启用类型，`dictValue` = 类型码 |
| 接口 `GET /system/config/configKey/warranty_window_days` | 值 30（⚠ 实现在 `msg` 里，不在 `data` 里） |

---

## 7. 已知坑（与本批 SQL 相关，详见 `DEV-ENV.md`）

| 坑 | 表现 | 规避 |
| --- | --- | --- |
| `collation` 不一致 | 建 FK 或关联查询时报 `ERROR 1267 Illegal mix of collations` | 建表统一显式 `COLLATE`，并与被引用列（`sys_user.user_id` / `sys_dept.dept_id` 是 `utf8mb4_0900_ai_ci`）对齐 |
| `Long` 写进 Redis 计数器 | FastJson2 写成 `0L`，随后 `INCR` 报 `ERR value is not an integer` | 计数器初值必须是 `Integer`（编号服务已加回归守卫） |
| `AjaxResult.success(String)` | 值落在 `msg` 而不是 `data`，容易误判为"接口没返回" | 看 `msg`；或改用 `/system/config/list` 查询 |
| 中文条件在 `mysql.exe` 管道里 | `WHERE title='中文'` 偶发匹配不到 | 清洗/断言尽量用 ASCII 条件（如 `title LIKE 'B3%'`、`code LIKE 'YX%'`） |
