# 06 · 调拨与盘点（t6-stockops）交付记录

> 组：stockops-integrator｜范围：`tasks.md` §6（6.1~6.7）｜实现口径：design D8/D11、Q-B10
> 包：`com.ruoyi.ctms.erp.stockops`（域对象 / Mapper / 服务 / 控制器 / 口径类）
> REST base：**`/stk/transfer`**（调拨）、**`/stk/take`**（盘点）｜权限点前缀：`stk:transfer:*` / `stk:take:*`

---

## 1. 交付物清单

| 类别 | 文件 | 说明 |
| --- | --- | --- |
| 口径 | `ErpStockOpsRules` | 两仓校验、盘点范围归一、实盘校验、调拨/盘点文案、盘点快照（含与公共层的唯一刻意差异，见 §5.3） |
| 域对象 | `domain/ErpTransfer.java`、`domain/ErpTransferItem.java` | 表头两仓（from/to）＋行项（无特有列、不使用行级仓库） |
| 域对象 | `domain/ErpStocktake.java`、`domain/ErpStocktakeItem.java` | 表头（仓库/盘点范围/生成单回填两对列）＋行项（账面/实盘/差异/差异原因） |
| Mapper | `mapper/ErpTransferMapper`、`ErpTransferItemMapper` | 表头 CRUD（全列覆盖式 update）＋行项全量替换 |
| Mapper | `mapper/ErpStocktakeMapper`、`ErpStocktakeItemMapper` | 同上，含生成单两对列 |
| Mapper | `mapper/ErpStockOpsMapper`（+ XML） | **只读**辅助：商品类型子树（递归 CTE）、按类型取启用物料 |
| 服务 | `service/IErpTransferService` + `impl/ErpTransferServiceImpl` | 调拨 CRUD + 6 个状态动作 + 两阶段过账 |
| 服务 | `service/IErpStocktakeService` + `impl/ErpStocktakeServiceImpl` | 盘点 CRUD + 行项生成 + 实盘录入 + 审核生成调整单 + 反审核级联 |
| 控制器 | `controller/ErpTransferController`、`controller/ErpStocktakeController` | 端点见 §7（t12/t13 直接消费） |
| Mapper XML | `resources/mapper/erp/ErpTransfer*.xml`、`ErpStocktake*.xml`、`ErpStockOpsMapper.xml` | 命名与 namespace 硬规则同其它 Mapper |
| 单测 | `src/test/.../erp/stockops/ErpStockOpsTestSupport.java`、`ErpTransferServiceTest`、`ErpStocktakeServiceTest` | 结存/流水/变更历史/数据范围/主数据桩复用 T3 的 `ErpPostingTestSupport` |

**没有新增**结存/流水的 domain 或写入路径：写入一律走 T3 的 `IErpStockJournalService` 与
`IErpStockInService`/`IErpStockOutService`（简报 §1.1 的边界）。

---

## 2. 调拨（6.1 / 6.2）

### 2.1 两阶段过账

| 阶段 | 做什么 | 在哪 |
| --- | --- | --- |
| 阶段一（校验，不写库） | ① 两仓非空且**不同**；② 至少一行行项；③ 每行数量 > 0；④（引擎内、**锁内**）按（物料, 仓库）排序后校验**调出仓可用量足够** | `ErpTransferServiceImpl#approveTransfer` + `ErpStockJournalServiceImpl.apply` 的校验段 |
| 阶段二（写入） | 每行展开成 **两条行指令**，一次性交引擎落库（流水 + 结存） | `#buildPostingLines` → `IErpStockJournalService#post` |

- **任一校验失败整体不写入**：引擎的"先全量校验、再统一写入"保证"第 2 行不足时第 1 行也没写"；
  服务层在过账**之前**先过状态机（只有 `submitted` 可审核），所以"状态不对却过了账"不会发生。
- **取锁顺序沿用 T3 的约定**：行指令交给引擎，引擎按 `(productId, warehouseId)` 排序分组取锁
  （调出仓与调入仓是两个不同的锁键，天然按序），本组**没有**自己写取锁逻辑，因此不会破坏该顺序。
- **幂等**：`posted='1'` 时重复审核直接返回（不产生流水、不改结存）；红冲走引擎的计数兜底。

### 2.2 两条流水（同一单据号）

| 流水 | 仓库 | qty_change | biz_type | unit_price |
| --- | --- | --- | --- | --- |
| 调出 | `from_warehouse_id` | `-qty` | `调拨出库` | `0` |
| 调入 | `to_warehouse_id` | `+qty` | `调拨入库` | `0` |

- `doc_type='stock_transfer'`、`doc_id`/`doc_no` 两条**完全相同**（一张调拨单两条流水）。
- 行级 `biz_type` 用 `ErpPostingLine#setBizType` 覆盖请求级类型 —— 因为同一单据的两个方向业务类型不同，
  且红冲时引擎要按**每条原流水**加 `红冲-` 前缀（得到 `红冲-调拨出库` / `红冲-调拨入库`）。
- **行项不使用行级仓库**：归一化时把 `warehouse_id/warehouse_name` 强制清空（请求里塞了也落 `null`）。
- **不产生金额**：行项 `unit_price/amount` 恒 0，表头没有金额列。

---

## 3. 盘点行项生成与实盘录入（6.3）

### 3.1 两个入口（"生成行项"不另立动作名）

| 入口 | 权限点 | 用途 |
| --- | --- | --- |
| `POST /stk/take`（新增） | `stk:take:add` | 建单时**自动生成行项**（按 `takeType` 与生成参数） |
| `POST /stk/take/{id}/generate` | `stk:take:add` | 对已有草稿**按 id 重新生成行项**（窄入口，不新增动作名） |
| `PUT /stk/take`（修改） | `stk:take:edit` | 录入实盘（`items` 携带实盘数量） |
| `PUT /stk/take/{id}/count` | `stk:take:edit` | 同上，按 id 的窄入口（前端 `doc.js#saveStocktakeCount` 已在用） |

> captain §9.2 冻结的动作集里没有"生成行项"这个动作，因此两个窄入口**沿用 add/edit 的权限点**，
> 不新增 `stk:take:generate` / `stk:take:count` 这类点；前端 `doc-kinds.js` 的 `permPrefix` 也不需要加动作段。

### 3.2 全盘 / 抽盘

| 范围 | 取数规则 |
| --- | --- |
| `full`（全盘） | 该仓库 `t_ctms_stock` 中**结存非零**的物料（结存表是唯一依据） |
| `partial` + `items`（按物料） | 请求里指定的物料（去重保序）；账面取该仓库结存行，无行则 0 |
| `partial` + `productTypeIds`（按商品类型） | 类型 id 先经 `ErpStockOpsMapper#selectTypeSubtreeIds` **递归展开整棵子树**，再取该子树下**启用**物料中**该仓库有结存行**的（没有结存行就没有"账面"可比对） |
| `partial` 但两者都没给 | 报错「抽盘必须指定物料或商品类型」 |
| 范围内没有可盘点物料 | 报错「盘点范围内没有可盘点的物料…」（不让用户提交一张永远提交不了的空单） |

- **账面数量（`book_qty`）在生成时固化**：等于生成那一刻该（物料, 仓库）的结存；
  之后无论结存怎么变，本次盘点的账面**不会**跟着漂移。
- **实盘默认等于账面**：避免"还没录入就被算成全亏"；差异初始为 0。
- **账面只读**：实盘录入时服务端**忽略**请求里的 `bookQty`，一律取库内固化值；
  请求里出现不属于本次盘点的物料 → 报「该物料不在本次盘点行项范围内」。
- **实盘非负 + 精度**：`actual_qty ≥ 0`，小数位不得超过物料单位小数位（`ErpAmounts.checkQuantityScale`）。
- **差异**：`diff_qty = 实盘 − 账面`（定点 3 位，唯一实现点 `ErpAmounts.diffQty`）。

---

## 4. 审核自动生成调整单（6.4）

| 差异 | 生成什么 | 生成单字段 |
| --- | --- | --- |
| `diff > 0`（盘盈） | 一张**盘盈入库单**（`t_ctms_stock_in`，`in_type='盘盈入库'`） | 状态**直接 `approved`**、`posted=0`（随后立即过账）、`total_amount=0`、行数量 = `|diff|`、`source_doc_type='stock_take'`、`source_doc_id/no` = 盘点单 |
| `diff < 0`（盘亏） | 一张**盘亏出库单**（`t_ctms_stock_out`，`out_type='盘亏出库'`） | 同上 |
| 全部为 0 | **不生成任何单据** | 盘点单 `posted` 保持 `'0'` |

- 生成路径用 T3 的窄接口：`insertGeneratedStockIn/Out` → `postApprovedStockIn/Out(id, options)`；
  生成单号回填到 `generated_in_no` / `generated_out_no`（再次审核时先清空再重写）。
- 盘点单自身**不写结存**：`posted` 表示"其生成单已过账"；无差异时为 `'0'`。

### 4.1 盘亏豁免负库存校验的理由（重要）

盘亏单过账**显式**传 `ErpPostingOptions.skipNegativeCheck()`：

1. 盘点的语义就是"账实不符"——`账面 2 / 实盘 0` 说明这 2 个已经没了，若被负库存拦住，
   盘点**永远无法平账**，系统里会长期留着一个"账面有、实际无"的假结存；
2. 豁免**只在这一条路径**上生效：`ErpPostingOptions` 是每次调用的显式参数，
   **不是**系统参数、HTTP 层也不接受任何"跳过校验"的入参（§2.3 的反例）；
3. 与"红冲不受负库存拦截"是同一类例外（`ErpPostingOptions` 只有这两个使用者），
   单测 `approvePostsLossEvenWhenStockIsInsufficient` 在 `negativeParam='false'`（参数关闭）的前提下
   证明盘亏仍能过账 ⇒ 豁免确由传参触发。

---

## 5. 反审核级联与两个刻意口径（6.5 / Q-B10）

### 5.1 级联顺序

对生成的盘盈单 / 盘亏单各做一遍（**入库侧与出库侧对称**）：

```
读生成单（Mapper 只读）
  ├─ 不存在 或 已作废        → 幂等跳过（重复反审核/重复处理不报错）
  ├─ 已过账                  → reversePostedStockIn/Out(id, reason)  ← 追加相反数流水，幂等
  ├─ 状态仍 approved         → unapproveStockIn/Out(id, reason)      ← approved → submitted
  └─ voidStockIn/Out(id, reason)                                     ← submitted → voided
```

然后盘点单：`status → submitted`、`posted → '0'`、清空审核人/审核时间。

- **结存必然回到审核前**：红冲是"追加相反数流水"，而结存 = 初值 + 流水累计 ⇒ 级联后逐（物料, 仓库）归位。
- 生成单号**保留**（作废凭据）；**再次审核**时会重新生成新的调整单并覆盖这两对列（旧号仍留在变更历史里）。
- **状态机白名单决定了顺序**：`void` 只允许 `draft|submitted` 出发，所以 `approved` 的生成单
  必须先 `unapprove` 回 `submitted`，不能直接作废。

### 5.2 重复反审核不报错（与单张单据的差异）

盘点反审核是**多单据级联**，中途失败需要能安全重放。因此当盘点单**已经是"待审核"**时，
`unapproveStocktake` 走"幂等重放"分支：只重跑一遍上面的幂等清理（不动状态、不报错、写一条
`反审核重放（幂等）` 留痕），而不是像 T3 的单张单据那样报"当前状态不可执行反审核"。
其余状态的流转仍严格走 `ErpDocStateMachine`。

### 5.3 盘点行项快照与公共层的**唯一刻意差异**

`ErpStockOpsRules.applyStocktakeItemSnapshot` 与 `ErpMasterGuards.applyItemSnapshot` 只差一点：
**不校验物料的启用状态**。理由：盘点的对象是"仓库里实际躺着的东西"——一个已停用的物料只要还有结存，
就必须能盘到，否则盘点恰恰无法清理这种历史。手工新增行项（非盘点路径）仍走公共层守卫，
"停用物料不能用于新单据"没有被绕过。其余口径（快照六列、精度校验）完全一致。

---

## 6. 附件（6.6）

调拨单的附件**复用 B3 的附件模块**，本组不新增端点：

- 对象类型登记：`CtmsAttachmentObjectTypes.REGISTERED` 已含 `stock_transfer`（T1 补齐的 5 类 + 1）；
- 业务对象鉴权：`ErpDocLookupMapper.xml` 的 `stock_transfer` / `stock_take` 分支已存在，
  上传/下载/删除都会按业务对象做存在性 + 四档数据范围校验（范围外业务码 403、不返回文件内容）；
- 因此 6.6 在**接口层**的验证点是"调拨单可上传附件 + 无权限用户请求其附件返回 403"，
  归入 t13 的门禁/接口验收（本组不新增 `tools/*` 脚本，按简报 §6 的约定）。

---

## 7. 端点清单（t12 前端 / t13 集成直接消费）

### 7.1 调拨单 `/stk/transfer`（权限点 `stk:transfer:<动作>`）

| 方法 | 路径 | 权限点 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `:list` | 列表（`docNo/status/fromWarehouseId/toWarehouseId/handlerName/beginDocDate/endDocDate/includeVoided`） |
| GET | `/{id}` | `:query` | 详情（含行项） |
| GET | `/{id}/change-logs` | `:query` | 变更历史（分页） |
| POST | `/` | `:add` | 新增草稿（单号 `DB` 前缀，服务端取） |
| PUT | `/` | `:edit` | 修改（仅草稿；两仓相同被拒） |
| DELETE | `/{ids}` | `:remove` | 删除（仅草稿） |
| POST | `/submit/{id}` 或 `/{id}/submit` | `:submit` | 提交 |
| POST | `/approve/{id}` 或 `/{id}/approve` | `:approve` | 审核即两阶段过账 |
| POST | `/reject/{id}` 或 `/{id}/reject` | `:approve` | 驳回（**与审核共用**；原因放请求体或 `?reason=`） |
| POST | `/void/{id}` 或 `/{id}/void` | `:void` | 作废 |
| POST | `/unapprove/{id}` 或 `/{id}/unapprove` | `:unapprove` | 反审核红冲 |
| PUT | `/status` | `:status` | 通用动作入口 `{id,action,reason}`（`complete` 被显式拒绝） |
| GET/POST | `/export` | `:export` | 导出（与列表同范围同筛选） |
| — | — | `:print` | **不由此端点提供**：打印走平台打印模块（B2 内置版式） |

### 7.2 盘点单 `/stk/take`（权限点 `stk:take:<动作>`）

与 §7.1 同结构（`list/query/change-logs/add/edit/remove/submit/approve/reject/void/unapprove/status/export`），
另加两个**盘点特有窄入口**：

| 方法 | 路径 | 权限点 | 说明 |
| --- | --- | --- | --- |
| POST | `/{id}/generate` | `stk:take:add` | 按 id 重新生成行项（`{takeType, productTypeIds[], items[]}`） |
| PUT | `/{id}/count` | `stk:take:edit` | 按 id 录入实盘（`{id, items:[{id 或 productId, actualQty, diffReason}]}`） |

### 7.3 与前端契约的关系

`ruoyi-vue-oa-ui-master/src/api/erp/doc.js` 的两套调用形态**都被支持**：

- 通用动作：`POST {restBase}/{id}/{action}`（`docAction`）✓；
- T3 形态：`POST {restBase}/{action}/{id}` 与 `PUT {restBase}/status` ✓（便于接口脚本逐条断言）；
- 盘点专用：`POST /stk/take/{id}/generate`、`PUT /stk/take/{id}/count` ✓。

> ✅ **已由 captain 复核（2026-10-05 深夜，前后端契约对照）**：前端 `doc-kinds.js` 已按 kind 逐类冻结
> `actionRequest()` 三模式分流 —— 采购/销售走 `put-id-action`、**入库单/出库单走 `postActionId()`
> = `POST {base}/{action}/{id}`**（与 T3 控制器一致）、**盘点/调拨走 `post-id-action` = `POST {base}/{id}/{action}`**
> （与本组控制器一致）。因此入库/出库的动作按钮**不会** 404，无需给 T3 加别名。
> 本组两个控制器**同时注册两种形态**属冗余路由（不影响验收），captain 已记为技术债 **D-7**，保留不改。

---

## 8. 接口断言清单（交 t13 复核；统一窗口执行）

前置：后端按 DEV-ENV 打包重启；`powershell -File tools/oa-login.ps1` 取 token；
主数据需 ≥2 个启用仓库（`WF`/`WT`）、≥2 个启用物料（`P1`/`P2`，带启用单位），并预置结存：

```sql
INSERT INTO t_ctms_stock(id, product_id, warehouse_id, qty) VALUES (uuid(),'P1','WF',10), (uuid(),'P2','WF',4);
```

| # | 请求 | 期望 |
| --- | --- | --- |
| 1 | `POST /stk/transfer` `{fromWarehouseId:"WF",toWarehouseId:"WT",docDate:"2026-10-05",items:[{productId:"P1",qty:4}]}` | 200；`docNo` 形如 `DB202610000001`；`status=draft`、`posted="0"`；行项 `warehouseId=null`；`unitPrice=0`、`amount=0` |
| 2 | 同上但 `toWarehouseId="WF"` | 非 200；`msg` 含「调出仓库与调入仓库不能相同」；库里无该单据 |
| 3 | 同上但 `toWarehouseId=null` | 非 200；`msg` 含「必须选择调入仓库」 |
| 4 | `POST /stk/transfer/{id}/submit` | 200；`status=submitted` |
| 5 | `POST /stk/transfer/{id}/approve` | 200；`status=approved`、`posted="1"`；`t_ctms_stock`：WF 由 10 → 6、WT 由 0/无行 → 4 |
| 6 | `SELECT * FROM t_ctms_stock_ledger WHERE doc_id={id}` | **2 条**：`qty_change=-4/+4`、`doc_no` 相同、`biz_type=调拨出库/调拨入库`、`unit_price=0` |
| 7 | 再 `POST /stk/transfer/{id}/approve` | 200（幂等）；流水条数仍 2、结存仍 6/4 |
| 8 | `POST /stk/transfer/{id}/unapprove` body `{reason:"调错"}` | 200；`status=submitted`、`posted="0"`；流水 4 条（后两条 `红冲-调拨出库/红冲-调拨入库`）；WF 回 10、WT 回 0 |
| 9 | 调出仓不足：`WF` 只有 3 时建单 2 行（3 与 20）→ submit → approve | 非 200；`msg` 含「可用量 3」；`t_ctms_stock_ledger` **无新增**（第 1 行也没写）；单据仍 `submitted` |
| 10 | `POST /stk/take` `{warehouseId:"WF",takeType:"full"}` | 200；`docNo` 形如 `ST202610000001`；`status=draft`；行项 = WF 中结存非零的物料（P1 账面 10 / P2 账面 4），`actualQty` 默认 = 账面、`diffQty=0` |
| 11 | `POST /stk/take` `{warehouseId:"WF",takeType:"partial",productTypeIds:["<类型A>"]}` | 200；行项 = 该类型**整棵子树**下该仓库有结存的启用物料 |
| 12 | `POST /stk/take` `{warehouseId:"WF",takeType:"partial"}` | 非 200；`msg` 含「抽盘必须指定物料或商品类型」 |
| 13 | `PUT /stk/take/{id}/count` `{items:[{productId:"P1",actualQty:12,diffReason:"漏记"}]}` | 200；P1 行：`bookQty` 仍 10（请求里传别的账面也被忽略）、`actualQty=12`、`diffQty=2.000`；`posted="0"`、无生成单 |
| 14 | `PUT /stk/take/{id}/count` `{items:[{productId:"P1",actualQty:-1}]}` | 非 200；`msg` 以「行 1：」开头且含「实盘数量不得为负数」 |
| 15 | `POST /stk/take/{id}/submit` → `POST /stk/take/{id}/approve`（P1 账面 10 / 实盘 12） | 200；`status=approved`、`posted="1"`、`generatedInNo` 非空；`t_ctms_stock(P1,WF)` 10 → **12**；生成单 `status=approved`、`posted="1"`、`in_type=盘盈入库`、`total_amount=0`、行数量 2 |
| 16 | 盘亏（账面 2 / 实盘 0）并先把 WF 的 P1 结存改成 0 → approve | 200；`generatedOutNo` 非空；结存 **0 → -2**（负库存参数仍为 `false` ⇒ 证明是盘亏显式豁免） |
| 17 | 无差异（账面 5 / 实盘 5）→ approve | 200；`generatedInNo`/`generatedOutNo` 均为空、`posted="0"`、无新流水与生成单 |
| 18 | `POST /stk/take/{id}/unapprove` body `{reason:"实盘录错"}` | 200；`status=submitted`、`posted="0"`；生成单 `status=voided`、`posted="0"`；结存回到审核前（12 → 10）；新增 `红冲-盘盈入库` 流水 |
| 19 | 再 `POST /stk/take/{id}/unapprove`（重复） | 200（不报错）；状态与结存不变；生成单仍只有一次作废 |
| 20 | `GET /stk/take/list?status=voided` vs 不传 | 不传时**不含**已作废；`includeVoided=1` 才包含 |
| 21 | `GET /stk/take/export?...` / `GET /stk/transfer/export?...` | 返回 xlsx；列与笔记 §7 一致；行集合与列表同筛选同范围 |
| 22 | 无 `stk:take:approve` 权限的账号调 `POST /stk/take/{id}/approve` | 403（`authz-check.ps1` 的 `IsForbidden` 形态） |
| 23 | 调拨附件：`POST /ctms/attachment/upload`（`objectType=stock_transfer, objectId={调拨单ID}`）→ `GET /ctms/attachment/list?...` → `DELETE` | 上传/列表/删除均 200；无权限用户取该调拨单附件 → 403 且不返回内容 |

---

## 9. 验证（真实输出）

```
# L1：本组单测（build 锁内；-f 指聚合根，避免子进程 Set-Location 不生效）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\locked-run.ps1 -LockName build `
  -Command "powershell -NoProfile -ExecutionPolicy Bypass -File .\.cache\t9\run-stockops-tests.ps1"
mvn -B -pl ruoyi-ctms test "-Dtest=ErpTransferServiceTest,ErpStocktakeServiceTest"
  Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  -- ErpStocktakeServiceTest
  Tests run:  8, Failures: 0, Errors: 0, Skipped: 0  -- ErpTransferServiceTest
  Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS

# L1：全模块（确认没有把别人的测试打红）
mvn -B -f F:\dsh\ruoyiOA\ruoyi-vue-oa-master\pom.xml -pl ruoyi-ctms test
  Tests run: 473, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS

# L2：静态审计
node tools\audit\run-all.js
  共 10 个审计；失败 0 个；未自证 0 个（全部 自检 ✅）
```

单测覆盖的场景（与 tasks.md §6.1~6.5 逐条对应）：

| 用例 | 断言 |
| --- | --- |
| `sameWarehouseIsRejectedOnSaveAndNothingIsWritten` | 两仓相同 → 保存被拒；无单据、无流水、结存不变 |
| `missingWarehouseIsRejected` | 缺调入仓 → 「必须选择调入仓库」 |
| `rowWarehouseIsClearedBecauseTransferUsesHeaderWarehouses` | 请求里塞行级仓库也落 `null`；两仓名快照写入；单号 `DB` 前缀、草稿、未过账 |
| `approveWritesTwoLedgerRowsWithSameDocNoAndZeroPrice` | 结存 10 → 6/4；**2 条流水**同 `doc_no`、`-4/+4`、`调拨出库/调拨入库`、单价 0；重复审核幂等 |
| `shortageOnLaterRowWritesNothingAtAll` | 两行单据第 2 行不足 → 报 `行 2：`＋`需求量 20`；**无任何流水**、结存仍 10、状态仍待审核 |
| `unapproveReversesBothRowsAndRestoresStock` | 红冲后 4 条流水（`红冲-调拨出库/红冲-调拨入库`）；两仓回 10/0；状态待审核、`posted=0` |
| `fullCountTakesOnlyNonZeroStockOfTheWarehouse` | 10/0/5 三个结存 → 只生成 10 与 5 两行；账面=生成时结存、实盘默认=账面、差异 0；快照齐备 |
| `partialCountByProductTypeExpandsWholeSubtree` | 按 `T-ROOT` 生成 → 命中子树里的 P1/P2，排除 `T-OTHER` 的 P3；只向 DB 问了根类型一次（递归 CTE 在库内展开） |
| `partialCountWithoutScopeIsRejected` | 抽盘未给物料也没给类型 → 「抽盘必须指定物料或商品类型」 |
| `bookQtyIsImmutableOnEditAndDiffIsActualMinusBook` | 请求篡改账面 999 被忽略（仍 10）；实盘 7 → 差异 **-3.000**；差异原因落库 |
| `negativeActualQtyIsRejectedWithRowNo` | 实盘 -1 → `行 1：`＋`实盘数量不得为负数` |
| `approvePostsGainAndFillsGeneratedInNo` | 结存 10 → **12**；`generatedInNo` 非空；生成单 `approved`/`posted=1`/`盘盈入库`/金额 0/数量 2/来源单号=盘点单号；重复审核幂等 |
| `approvePostsLossEvenWhenStockIsInsufficient` | `negativeParam='false'` 下结存 0 → **-2**（盘亏豁免生效）；生成单 `盘亏出库` |
| `noDifferenceGeneratesNoDocumentAtAll` | 无差异 → 两个生成单号均空、`posted=0`、无生成单、无流水、结存不变 |
| `unapproveCascadeVoidsGeneratedDocAndRestoresStock` | 生成单 `voided`、结存 12 → **10**、盘点单回待审核、新增 `红冲-盘盈入库` |
| `repeatedUnapproveIsIdempotentAndDoesNotThrow` | 重复反审核不报错、状态/结存不变；再次审核会生成**新的**盘盈单 |
| `transferAndStocktakeObjectTypesAreRegisteredForAttachments`（6.6） | `CtmsAttachmentObjectTypes.registered()` 含 `stock_transfer` 与 `stock_take`（附件对象登记已由 T1 完成，本组只断言） |
| `outOfScopeTransferDetailIsRejectedWith403`（6.6） | 数据范围外的调拨单详情 → 业务码 403（`IErpDocObjectAccess` 的业务对象鉴权） |

> 过程中的两次整模块 main 编译中断都**不是本组文件**：`ErpStock.getQtyOrZero()`（我用错方法名，已修）、
> `ErpSalesRequestServiceImpl`（T5 在建，backend-sales 已修）。两者均已恢复，上面的 471 条是全绿输出。

---

## 10. 已知边界与待办

1. ~~前端通用动作路径与 T3 控制器不一致~~ → **不成立，已撤销（captain 2026-10-05 复核）**：
   前端 `doc-kinds.js` 的 `actionRequest()` 按 kind 三模式分流（库存出入库走 `postActionId()`，
   盘点/调拨走 `post-id-action`），入库/出库动作按钮不会 404。本组两个控制器的双形态路由属**冗余**，
   captain 记为**技术债 D-7**，**不要**改回去（也不影响验收）。
2. **盘点范围里的停用物料**（captain 已裁决并派 **t23 / kind=repair / sourceTaskId=t3**）：
   全盘行项来自结存表，快照刻意允许停用物料（§5.3）；但**审核生成调整单**时走 T3 的
   `insertGeneratedStockIn/Out` → `normalizeItems` → `ErpMasterGuards.applyItemSnapshot`
   （`ErpStockInServiceImpl:463`、出库侧 `:447` 同构），该守卫会抛"物料已停用，不能用于新单据" ⇒
   含停用物料的盘点差异在生成那一步失败。**裁决口径**：生成路径豁免（系统生成的账实调整不是
   "用户新录行项"），但必须是**显式参数/专用方法**（同 `skipNegativeCheck()` 的形态），
   **不做全局开关**；"物料/单位不存在、数量精度"等其余校验在生成路径上仍然生效，并要求补
   **反向断言**（用户新建/编辑单据用停用物料仍被拒）证明豁免没有放宽。
   本组未擅改公共层——修复归 t23（backend-posting），本组两个生成路径调用点无需改动。
3. **级联作废的权限面**：生成单的归属部门是**审核人**当时的部门（`insertGeneratedStockIn` 写入创建快照），
   因此"跨部门反审核盘点单"可能因数据范围被拒（403）。这是数据范围的一致性表现，不是缺陷；
   若业务要求"谁建的盘点谁都能反审核"，需要改的是生成单的归属口径。
4. **`ErpStockOpsMapper` 的递归 CTE 依赖 MySQL 8**（本机 8.0）；若将来降级到 5.7 需改成物化路径
   （B3 的 `t_ctms_product_type` 有 `path` 列，可作为替代实现）。
5. **本组不新增 `tools/*` 脚本**（简报 §6：改共享脚本先报文 captain）；§8 的 23 条交给 t13 落成
   `tools/erp-stockops-check.ps1`。
