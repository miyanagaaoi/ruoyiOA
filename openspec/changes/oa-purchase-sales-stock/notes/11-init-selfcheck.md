# 11 · B3 空库初始化自检漂移核对（B4 任务 t17）

> 结论：**五项漂移全部判为 (A)「期望值/判据过时」，不是基线或 DDL 真漂移**。
> 已按对象名单修正，证据来自**空库全链演练 + `information_schema` 对象名单 + git/文件溯源**，
> 没有"把期望值直接改成实测值"：㉓ 改的是**判据**（数字 10 不动），⑲/㉑ 的新值各有 17/5 个
> 具名对象与交付期文档背书，㉜/㉝ 回到上游 `table.sql` 的真值。
> 修正后空库一次性初始化**从头到尾 105 条自检行全部命中期望值（不符 0）**，`b3-sql-drill.ps1` **68/68**。

---

## 1. 结论摘要（位置 → 判定 → 处置）

| # | 自检行 | 文件:行（修正前） | 原期望 | 实测 | 判定 | 处置 |
|---|---|---|---|---|---|---|
| 1 | ⑲ 外键数量 | `sql/二开-合同台账.sql:918` | 14 | **17** | (A) 期望值过时 | 14 → **17**（附 17 条名单） |
| 2 | ㉑ ON DELETE CASCADE 条数 | 同文件 `:926` | 6 | **5** | (A) 行内枚举自相矛盾（1+2+1+1=5） | 6 → **5** |
| 3 | ㉓ 唯一索引数量 | 同文件 `:935` | 10 | **11** | (A) **判据写错**（数的是 STATISTICS 行数） | 判据改 `COUNT(DISTINCT INDEX_NAME)`，**10 不动** |
| 4 | ㉜ sys_user 列数 | 同文件 `:965` | 18 | **21** | (A) 期望值过时（上游真值 21） | 18 → **21** |
| 5 | ㉝ sys_dept 列数 | 同文件 `:967` | 18 | **16** | (A) 期望值过时（上游真值 16；18 疑照抄 ㉜） | 18 → **16** |

注：任务描述写的"这些自检项在 `sql\初始化-全部.sql`"与实际不符——五项都在
**`sql/二开-合同台账.sql` 的第 ⑦ 段自检**里（`初始化-全部.sql` 只 `source` 该文件）。
按 captain 的边界通知，本次**只动 B3 自己的自检段**，`初始化-全部.sql` 一个字没碰。

## 2. 判定方法（为什么不能只看计数）

对每个"多出来"的对象都取了**精确名单**（约束名 / 列名），并与产出它的 DDL 段、交付期文档、
上游基线文件逐条对照；计数只是名单的副产品。三项证据链：

1. **DB ↔ 文件同名比对**：空库实测的 17 条外键与 `二开-合同台账.sql` ⑥-4 段的 17 条
   `ADD CONSTRAINT` **逐条同名**（见 §3.1），不存在"DDL 里没有、库里多出来"的约束。
2. **交付期文档背书**：B3 交付时记录的形态就是 13 表 + **17 条外键**
   （`openspec/changes/oa-contract-ledger/notes/migration-notes.md:268/274/330`、
   `notes/integration-check.md:243`）；本轮 `b3-sql-drill.ps1` 的 I 段也独立打印
   "建表后有外键（**17 个**）"。
3. **git/文件溯源**：`sql/二开-合同台账.sql` 只在 B3 交付提交 `5b06ab8`
   （nested repo `ruoyi-vue-oa-master`）里出现过，此后**工作区无改动** ⇒ 期望值 14/6/18/18
   与同文件 DDL 在交付当刻就互相矛盾，属**从未被执行过的判据**（`b3-sql-drill.ps1` 不含这五项）。

## 3. 逐项依据

### 3.1 ⑲ 外键 17（原 14）

空库 `rad_oa_t17` 实测（限定 B3 的 13 张表）17 条，与文件 ⑥-4 段逐条同名：

```
fk_attachment_contract    t_ctms_attachment    → t_ctms_contract       CASCADE
fk_change_log_contract    t_ctms_change_log    → t_ctms_contract       CASCADE
fk_contract_create_id     t_ctms_contract      → sys_user              NO ACTION
fk_contract_customer      t_ctms_contract      → t_ctms_customer       NO ACTION
fk_contract_dept          t_ctms_contract      → sys_dept              NO ACTION
fk_contract_parent        t_ctms_contract      → t_ctms_contract       SET NULL
fk_contract_supplier      t_ctms_contract      → t_ctms_supplier       NO ACTION
fk_contract_item_contract t_ctms_contract_item → t_ctms_contract       CASCADE
fk_contract_item_product  t_ctms_contract_item → t_ctms_product        NO ACTION
fk_contract_tag_contract  t_ctms_contract_tag  → t_ctms_contract       CASCADE
fk_contract_tag_tag       t_ctms_contract_tag  → t_ctms_tag            CASCADE
fk_customer_create_id     t_ctms_customer      → sys_user              NO ACTION
fk_product_create_id      t_ctms_product       → sys_user              NO ACTION
fk_product_product_type   t_ctms_product       → t_ctms_product_type   NO ACTION
fk_product_uom            t_ctms_product       → t_ctms_uom            NO ACTION
fk_product_type_parent    t_ctms_product_type  → t_ctms_product_type   SET NULL
fk_supplier_create_id     t_ctms_supplier      → sys_user              NO ACTION
```

**差额 3 条的溯源**：`fk_customer_create_id` / `fk_supplier_create_id` / `fk_product_create_id`
= 评审清单 §2.8 **#15**「`customers.created_by` / `suppliers.created_by` / `products.created_by`
→ 加 NOT NULL + FK」那一组（`notes/ddl-review.md` §2 第 15 行、`:251/:282/:401` 三处结论列，
以及文件 ⑥-3 段的 `MODIFY COLUMN ... NOT NULL ...（加 NOT NULL + FK：清单 §2.8 #15）` 注释）。
它们**属于计划内对象**，不是"多出来的约束"；因此 14 是过时/写错的期望，而不是 DDL 漂移。
（⑥-4 各分段自带条数：③-1 5 条 + ③-2 2 条 + ③-3 2 条 + ③-4 1 条 + ③-5 1 条 +
③-6 2 条 + ③-7 1+3 条 = **17**。）

### 3.2 ㉑ CASCADE 5（原 6）

该行标题写 6，但它自己的枚举是"行项1 + 标签关联2 + 变更历史1 + 附件1"，**合计 5**；
实测 5（上表 CASCADE 行）。即原值 6 是笔误/重复计了"合同标签"，DDL 侧无缺失。

### 3.3 ㉓ 唯一索引 10（原判据数出 11）

`information_schema.STATISTICS` 的粒度是「索引 × 列」，而 `uk_party_draft` 是
`(party_type, raw_name)` **两列** ⇒ 10 个唯一索引会数出 11 行。空库实测三口径对照：

```
rows (COUNT(*))                    = 11      ← 原判据
distinct INDEX_NAME                = 10      ← 修改后判据
distinct (TABLE_NAME, INDEX_NAME)  = 10      ← 更严口径，同样 10
```

10 个索引名单：`uk_contract_no` / `uk_tag_name` / `uk_customer_code` / `uk_customer_name` /
`uk_supplier_code` / `uk_supplier_name` / `uk_uom_code` / `uk_warehouse_code` /
`uk_product_code` / `uk_party_draft`（两行）。B3 的 10 个索引名互不相同，且与 B4 的
`uk_*_doc_no` / `uk_stock_product_warehouse` 无重名 ⇒ `COUNT(DISTINCT INDEX_NAME)` 不会丢个数。
**结论：数字 10 是对的，错的是判据**（若把 10 改成 11，等于把"判据 bug"粉饰成"期望值"）。

### 3.4 ㉜/㉝ sys_user=21 / sys_dept=16（原 18/18）

上游 `sql/table.sql`（提交 `23ece02`，文件时间 2026/4/20，未改动）的建表段：
`sys_user` = **21 列**（`table.sql:1581`）、`sys_dept` = **16 列**，与空库实测**逐列同名**
（脚本比对 `setEqual=True`，列表一致）：

```
sys_user(21): user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar,
              password, status, del_flag, login_ip, login_date, create_by, create_time, update_by,
              update_time, pwd_update_date, remark, zh_full_spell
sys_dept(16): dept_id, parent_id, ancestors, dept_name, simple_name, org_type, order_num, leader,
              phone, email, status, del_flag, create_by, create_time, update_by, update_time
```

全仓 `sql/` **无任何** `ALTER TABLE \`sys_user\`|\`sys_dept\`` ⇒ 不存在"被本变更集多改出来的列"，
21/16 就是未改动过的基线；原 18 属自 B3 交付起就写错的期望值。
交叉印证：B4 自检 ㉒/㉓ 独立断言 21/16（本轮 drill 亦全绿）。

## 4. 空库演练（命令 + 前后对照）

```powershell
# 建临时空库
mysql --host=127.0.0.1 --user=root -e "DROP DATABASE IF EXISTS rad_oa_t17; CREATE DATABASE rad_oa_t17 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
# 全链（基线 table.sql + data.sql + 18 个增量 + 编排自检），按协作要求走 env 锁串行
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName env -Command `
  "powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\run-db-sql.ps1 -Database rad_oa_t17 -WithBaseline"
# 收尾
mysql --host=127.0.0.1 --user=root -e "DROP DATABASE IF EXISTS rad_oa_t17; DROP DATABASE IF EXISTS rad_oa_t17b;"
```

| 轮次 | 库 | 退出码 | B3 段五项 | 全部自检行（脚本核对） |
|---|---|---|---|---|
| 修正前 | `rad_oa_t17` | 0（18 增量，5.4s） | ⑲ 17≠14、㉑ 5≠6、㉓ 11≠10、㉜ 21≠18、㉝ 16≠18 → **5 红** | — |
| 修正后 | `rad_oa_t17b` | 0（18 增量，6.0s） | ⑲ **17=17**、㉑ **5=5**、㉓ **10=10**、㉜ **21=21**、㉝ **16=16** | **105 条命中期望 105 / 不符 0** |

核对方式：把 `--table` 输出里被折行的单元格重新拼行后，用正则抽取
`（应为X）… | 值 |` 配对（支持 `8×3=24`、`4+7+2+7+3=23` 这类算式与带说明的期望），逐行比较；
唯一例外是 B1 的"本次新增列数（**重复执行**应为 0）"行（首跑为 14 属正常，脚本已识别跳过）。

原始输出：`.cache/t17/drill-before.txt`（修正前）、`.cache/t17/drill-after.txt`（修正后）。

**范围外观察（未处置，按边界通知记录）**：

- 编排自检 ⑦「B3 菜单行数（应为27）」本轮实测 **27 → 绿**；t10 计划的
  `二开-进销存-菜单.sql`（4 个主数据 C 菜单用 `ctms:partner:list`）**尚未接入**，
  故 back-end-ledger 说的 ⑦→31 现象本轮**未出现**；`初始化-全部.sql` 里也**还没有 ⑮**。
- ⑦-3/⑦-4/⑦-5 的过滤条件是 `TABLE_NAME LIKE 't\_ctms\_%'`（不是 13 表白名单）⇒ 若在
  **B4 已装的库上单独重跑 B3 建表脚本**，会数到 B4 的 43 条外键/9 个唯一索引。实测该场景
  **根本走不到自检**：⑥-1 的 `DROP TABLE \`t_ctms_product\`` 被 B4 的引用外键挡住，
  mysql 报 `ERROR 3730 (HY000) at line 83: Cannot drop table 't_ctms_product' referenced by a
  foreign key constraint`，编排 exit 1（`.cache/t17/b3-standalone-rerun.txt`）。
  即：在支持场景（冷启空库、B3 先于 B4）下不会误报；要支持"单独重跑"是另一个议题
  （该文件头部已警告 ⑥-1 会清空 13 张表数据），**本次不动**，留待 captain 裁决。

## 5. 改动清单（只改 B3 自检段，无 DDL 变更）

`git diff --stat`（nested repo `ruoyi-vue-oa-master`）：`sql/二开-合同台账.sql`
**30 insertions(+), 6 deletions(-)**，全部落在第 ⑦ 段（注释 + 5 条判据/标签）：

| 位置 | 改动 |
|---|---|
| ⑦-3 段头 + ⑲ | `（应为 14 条）`→`（应为 17 条）`；标签 `应为14`→`应为17`；补 17 条名单与 §2.8 #15 溯源注释 |
| ㉑ | 标签 `应为6：…+合同标签…`→`应为5：行项1+标签关联2+变更历史1+附件1=5…` |
| ⑦-4 注释 + ㉓ | 补"STATISTICS 按行"的判据说明；`COUNT(*)` → `COUNT(DISTINCT INDEX_NAME)`（`应为10` 不变） |
| ⑦-7 注释 + ㉜/㉝ | `应为18`→`应为21` / `应为18`→`应为16`；补上游列名清单与 table.sql:1581 出处 |

**未触碰**：`sql/table.sql`、`sql/data.sql`、`sql/初始化-全部.sql`（⑦/⑮ 归 t10）、
B3 的 13 张表结构与其 DDL/约束、任何 B4 文件、任何 Java/前端代码。

## 6. 回归与验证

| 验证 | 命令 | 结果 |
|---|---|---|
| 空库全链自检 | `run-db-sql.ps1 -Database rad_oa_t17b -WithBaseline`（env 锁内） | exit **0**，18 增量，**105/105 命中，0 不符** |
| B3 四件套演练 | `tools\b3-sql-drill.ps1`（env 锁内） | **68 通过 / 0 失败**（含 I 段独立打印"建表后有外键（17 个）"） |
| 收尾 | `DROP DATABASE rad_oa_t17 / rad_oa_t17b` | 已删净（`SCHEMATA` 命中 = 0），未触碰 `rad_oa` |

**判定 (A) 的最终依据**：五项修改都朝向"对象名单 + 同文件 DDL + 上游基线 + 交付期文档"，
没有一项是"照抄实测值"——㉓ 甚至**拒绝了**把 10 改成实测的 11。
