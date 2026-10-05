# 库存账（明细 / 流水 / 一致性校验）交付说明

任务：`openspec/changes/oa-purchase-sales-stock/tasks.md` §7（7.1~7.6）
交付：**只读**库存账查询 + 结存一致性校验/修复｜需求：`REQ-STK-003`、`REQ-STK-006`｜验收：`AC-73`、`AC-76`、`AC-78`
作者：backend-ledger（t7-ledger / 任务 t6）｜2026-10-05

**不重复造写入侧**：`t_ctms_stock` / `t_ctms_stock_ledger` 的 domain + mapper + 唯一写入口
（`ErpStockJournalServiceImpl`）归 T3（§5）。本组只做只读查询与校验，修复时复用 T3 的
`ErpStockMapper.updateStockQty`（写绝对值）。

---

## 1. 交付清单

| 文件 | 作用 |
| --- | --- |
| `erp/ledger/ErpLedgerRules.java` | 口径常量 + 纯函数（额度谓词 / 逐行舍入 / 展示名 / 低于安全库存） |
| `erp/ledger/ErpProductTypeTree.java` | 商品类型树 → 子树 ID（"选父带子"，纯函数、防成环） |
| `erp/ledger/domain/ErpStockBalance.java` | 库存明细行（查询条件 + 结果 + Java 派生字段） |
| `erp/ledger/domain/ErpStockLedgerRow.java` | 流水行（含下钻条件；`isReversal`/`displayName` 派生） |
| `erp/ledger/domain/ErpStockRecalcResult.java` | 校验结果（被检查行数 / 一致 / 不一致条数与明细 / 修复行数） |
| `erp/ledger/domain/ErpLedgerQtySum.java` | （物料, 仓库）→ 流水累计聚合行 |
| `erp/ledger/mapper/ErpStockBalanceMapper.java` + `mapper/erp/ErpStockBalanceMapper.xml` | 结存明细只读（额度子查询、数据范围 exists 关联） |
| `erp/ledger/mapper/ErpStockLedgerQueryMapper.java` + `mapper/erp/ErpStockLedgerQueryMapper.xml` | 流水列表/下钻/详情 + 累计和聚合（**无 update/delete**） |
| `erp/ledger/service/IErpStockLedgerQueryService.java` + `service/impl/…Impl.java` | 明细 / 流水 / 详情（含 403/404 裁决） |
| `erp/ledger/service/IErpStockRecalcService.java` + `service/impl/…Impl.java` | 一致性校验 / 修复 |
| `erp/ledger/controller/ErpStockBalanceController.java` | `/stk/stock`：list / detail / export / recalc |
| `erp/ledger/controller/ErpStockLedgerController.java` | `/stk/ledger`：list / detail / export |
| `src/test/java/…/erp/ledger/**`（6 个测试类 + 1 个桩） | **47 条**单测，覆盖 7.1~7.5 每条断言 |

---

## 2. §7.1 库存明细（`GET /stk/stock/list`，权限 `stk:stock:list`）

- 一行 = 一个（物料, 仓库）；**没有写入口**（结存只能由过账/红冲维护，D2）。
- 展示列：仓库 / 物料编码 / **「商品类型名称-物料名称」** / 规格 / 单位 / 结存数量 /
  安全库存 / **货品总额度** / 是否低于安全库存 / 最后过账时间。
  「类型-物料」与「低于安全库存」是 **Java 派生字段**（`ErpStockBalance.getDisplayName()`、
  `isBelowSafetyStock()`），不是数据库列 —— 于是它们能脱库单测，也不会出现"SQL 与 Java 两套拼法"。
  任一侧缺失时不产生孤零零的连接符（`五金` / `螺丝` 各自成立）。
- 筛选：`keyword`（物料编码/名称/规格模糊）、`warehouseId`、`productTypeId`
  （**按子树**：选「五金」会带出挂在子类型「紧固件」下的物料）、`beginQty`/`endQty`（含边界）、
  `belowSafetyOnly`（`qty < safety_stock`）、`hideZero`（默认**列出**零结存）。
- **子树为什么在 Java 里展开**（而不是递归 CTE）：可脱库单测 + 防成环 + 类型档案是小表；
  展开结果作为 `in (...)` 进 SQL，执行计划稳定（见 `ErpProductTypeTree` 类注释）。

## 3. §7.2 货品总额度（明细行上的 `goodsQuota` 列）

**口径（唯一真源）**：对业务类型**子串匹配「入库」且排除「调拨入库」**的流水，求
`Σ round(qty_change × unit_price, 2)`（逐行先舍入到 2 位再求和，与 AC-78 同源）。

三条反直觉之处（评审最容易看错的地方）：

1. **红冲自动抵扣**：红冲流水的业务类型是 `红冲-采购入库`（**仍含「入库」**）、数量为负，
   所以计入且贡献负值 ⇒ "采购入库 10×2 后被红冲"的额度是 `20.00 + (-20.00) = 0.00`。
2. **反例已锁死**：若按"数量变动 > 0"筛，同一批数据会得到虚高的 `20.00` ——
   `ErpLedgerRulesTest.反例_按数量变动大于零筛会虚高二十` 把这个错误口径写成断言。
3. **调拨不算货值**：`调拨入库` / `调拨出库` 及其**红冲**（`红冲-调拨入库`）都不计入
   （单价本身也是 0）。没有这条显式排除时，`红冲-调拨入库` 会带着负数量混进「入库」集合把额度算歪。

额度**非 0 时结存仍等于流水累计和**：额度只统计"入库侧子集"的货值，不改写结存；
两者互不干扰（一致性不变式见 §5）。

SQL 与 Java 的关键字一致性由 `ErpLedgerAppendOnlyTest.额度口径的SQL与Java常量一致` 做门禁：
改 `ErpLedgerRules.QUOTA_INCLUDE_KEYWORD`/`QUOTA_EXCLUDE_KEYWORD` 而忘记改 XML 会被打红。

## 4. §7.3 库存流水（`GET /stk/ledger/list`，权限 `stk:ledger:list`）

- **列表与下钻同一处实现**：带 `productId + warehouseId` 即为下钻（明细页的抽屉），
  于是"下钻看到的行"与"列表筛选后的行"不可能漂移；同一处数据范围。
- 每条含：业务类型 / 单据类型 / **单据号** / **来源单号** / **数量变动** / **变动后结存** /
  单价 / 操作人（`create_by` 快照）/ 记账时间。
- 排序 `create_time desc, id desc`：同一事务内写入的多行时间可能相同，用 id 兜底保证分页稳定。
- **只增不改（AC-73）的三层证据**：
  1. 控制层：`/stk/ledger` 只注册 list / detail / export，没有 update/delete 端点；
  2. 服务层：`IErpStockLedgerQueryService` 无写方法；
  3. 数据访问层：`ErpStockLedgerQueryMapper` 与 T3 的 `ErpStockLedgerMapper` 都**没有**
     update/delete 方法，两个 XML 也**没有** `<update>`/`<delete>` 标签 ——
     由 `ErpLedgerAppendOnlyTest`（5 条反射 + 源码级断言）持续守住，不靠一次性 grep。

## 5. §7.4 结存一致性校验 / 修复（`POST /stk/stock/recalc`，权限 `stk:stock:recalc`）

- **不变式**：对每个（物料, 仓库），`t_ctms_stock.qty == Σ t_ctms_stock_ledger.qty_change`。
  口径是"**流水是账，结存是缓存**"。
- 返回：`checkedRows`（被检查的结存行数）/ `consistent` / `inconsistentCount` /
  `mismatches[]`（每条的 `stockQty` / `ledgerQty` / `diff = 结存 − 累计`）/ `repair` / `repairedCount`。
- `repair=false`（默认）：**只读**，绝不写库（单测断言 `updateStockQty` 调用次数为 0）。
- `repair=true`：把不一致行的结存**改写为流水累计**（写绝对值，不做增量累加）；
  一致的数据行**永不被改写**（幂等：第二次调用 `repairedCount = 0`）。
- 取数只两条 SQL（结存全量 + 流水按 key 聚合），修复时才逐行 update，避免 N+1。

> ⚠ **修复是危险操作**：它假设"流水可信"。若流水本身被脏写过，修复会把结存一起带歪。
> 建议只给运维角色 `stk:stock:recalc`；先跑 `repair=false` 看明细，确认后再修复，
> 修复后必须复检（复检一致 = `inconsistentCount == 0`）。

**已知边界（刻意不处理）**：只检查"结存行"。若某（物料, 仓库）**只有流水、没有结存行**
（正常过账路径不可能——两者在同一事务内写入），它既不计为不一致、也不会被修复：
凭空补一行结存超出"改写"的授权范围。这条边界交给 T13 的接口级检查兜住（见 §7 第 9 条）。

## 6. §7.5 精度四方一致（AC-78）

夹具（刻意能区分"逐行舍入"与"先求和再舍入"）：`1×0.125 → 0.13`、`3×3.335 → 10.01`、
`2.5×1.111 → 2.78`，合计 **12.92**。

| 面 | 取数 | 结果 |
| --- | --- | --- |
| 未审核（草稿保存） | `ErpAmounts.applyLineAmount` 逐行写 `amount` | 12.92 |
| 已审核（落库 `total_amount`） | `ErpAmounts.totalOf(items)` | 12.92 |
| 打印（金额文本） | `ErpAmounts.amountTextOf` | `"12.92"` |
| 导出（真实导出行对象） | `ErpStockInController.ErpStockInExportRow`（反射调用，见测试注释） | 12.92 |

反例：`先求和再舍入` = `0.125 + 10.005 + 2.7775 = 12.9075 → 12.91`（差 1 分钱），
断言里显式否定它。定点类型与小数位（金额 2 / 单价 4 / 数量 3）另由断言锁定；
"整个 B4 包不得出现 `double`/`float`"由 T1 的 `ErpPrecisionTest` 源码级扫描覆盖（本包自动纳入）。

## 7. 接口断言清单（交 T10 / T13 复核）

| # | 请求 | 期望 |
| --- | --- | --- |
| 1 | 任一 `/stk/stock/**`、`/stk/ledger/**` 无 token | 401 |
| 2 | 持 `stk:stock:list` 请求 `GET /stk/stock/list` | 200，`total` = 结存行数 |
| 3 | 无 `stk:stock:list` 的角色请求同一 URL | 403 |
| 4 | `GET /stk/stock/list?belowSafetyOnly=true` | 只含 `qty < safety_stock` 的行 |
| 5 | `GET /stk/stock/list?beginQty=&endQty=` | 只在区间内的行（含边界） |
| 6 | `GET /stk/stock/list?productTypeId=<父类型>` | 含子类型下的物料行（选父带子） |
| 7 | 明细行字段 | 有 `displayName`（形如 `五金-螺丝`）与 `goodsQuota`、`belowSafetyStock` |
| 8 | 采购入库 10×2 后反审核红冲 | 该（物料, 仓库）`goodsQuota` 回到 **0.00**；调拨入库及其红冲不改变任一额度 |
| 9 | 未审核 → 已审核 → 打印 → 导出 | 四处金额完全一致（无 1 分钱差异） |
| 10 | `GET /stk/ledger/list?productId=&warehouseId=` | 只含该 key 的流水、时间倒序、含 `qtyAfter`；红冲行仍在且原流水内容不变 |
| 11 | `GET /stk/ledger/detail?id=<不存在>` / `?id=<范围外>` | 404 / 403（两者可区分） |
| 12 | `GET|POST /stk/stock/export` 与列表同筛选 | 200 且 `Content-Type` 为 xlsx；**导出行数 == 列表 `total`**（R1 教训的双证） |
| 13 | `POST /stk/stock/recalc`（默认） | `inconsistentCount = 0`（全库 结存 == Σ流水） |
| 14 | 人为改动某行结存后 `recalc` | 报 1 条不一致并给出 `stockQty`/`ledgerQty`；**结存未被改动** |
| 15 | `POST /stk/stock/recalc?repair=true` 后复检 | 结存被改写为累计、复检 `inconsistentCount = 0`、一致数据未被改 |
| 16 | 四档数据范围（本人/本部门/本部门及下级/全部）对结存与流水 | 返回集合分别等于各档预期；多角色为并集；**范围外 detail 与 export 被拒**（§8.2，归 T10） |

## 8. 单测（47 条，全绿）

| 测试类 | 条数 | 覆盖 |
| --- | --- | --- |
| `ErpLedgerRulesTest` | 7 | 7.2 额度口径（含红冲抵扣、调拨排除、反例、逐行舍入）、展示名、安全库存 |
| `ErpProductTypeTreeTest` | 9 | 子树展开：三层树 / 叶子 / 另一根 / 空根 / 未知根 / 空集合 / 成环 / 自环 |
| `ErpStockLedgerQueryServiceImplTest` | 16 | 7.1 各筛选 + 派生字段 + 7.3 下钻字段与倒序 + 403/404 三分支 + 空参数 |
| `ErpStockRecalcServiceImplTest` | 7 | 7.4 四条验收断言 + 红冲参与不变式 + 空库 + qty 为空 |
| `ErpLedgerPrecisionTest` | 3 | 7.5 四方一致 + 定点类型/小数位 + 额度与金额同源 |
| `ErpLedgerAppendOnlyTest` | 5 | 只增不改（接口 + XML）+ 额度关键字 Java↔SQL 一致 |

## 9. 与 t10（§8.2）的接线点

- 数据范围片段已接好：服务层用 `ErpDocScope.buildDataScopeSql("l")`，别名固定 `l`；
  **结存表的范围按"流水侧"判定**（结存表没有 `dept_id`/`create_id` 列）——
  明细 XML 里用 `exists (... t_ctms_stock_ledger l ... and ( ${dataScopeSql} ))` 实现，
  即"该（物料, 仓库）存在一条我可见的流水"才可见；流水查询直接把片段作用于主表 `l`。
- 因此 **t10 的 §8.2 四档实测不需要改本组代码**，只需造四档账号与夹具；
  范围外详情/导出的 403 分支已由单测覆盖（真实 403 由 T10 的接口脚本断言）。
- 生产实现只有一处 `currentScopeSql()`（`ErpStockLedgerQueryServiceImpl`），
  测试子类覆写它以便脱登录态单测 —— 覆写只允许出现在测试里。

## 10. 待复核 / 已知边界

1. **额度 SQL 的真库验证**：单测覆盖的是 Java 口径 + XML 关键字一致性；`round(...)` 与
   `coalesce(unit_price, 0)` 的实际执行结果由 T10/T13 的接口断言（清单第 8 条）验证。
2. **导出面 xlsx 判定**：清单第 12 条按 R1 教训要求"按 xlsx 判定 + 导出行数 == 列表 total"双证。
3. **recalc 的真库并发/回滚**：本组单测用内存桩（无事务），真库行为归 T10/T13。
4. **孤儿流水**（有流水无结存行）：不在本组修复范围（见 §5 已知边界）。


---

## 11. F-01 修复记录（t54）：按商品类型（含子树）筛选被 PageHelper 分页污染

**根因**：`ErpStockLedgerQueryServiceImpl.expandProductTypes` 读"全表类型清单"的那条查询落在 `startPage()` **之后** ⇒ PageHelper 把 ThreadLocal 里的 Page 交给**下一条** MyBatis 查询（正是这条辅助查询）⇒ 类型清单被截成前 `pageSize` 行 ⇒ 根类型不在前 N 行时 `subtreeIds` 算不出子树（现象：`?productTypeId=父` 查不到子类型物料，且**随页大小翻转**）。

**修复**：把子树展开**移到 `startPage()` 之前** —— 新增 `IErpStockLedgerQueryService.prepareQuery(query)`（javadoc 写明"必须在 `startPage()` 之前调用"及违反后果，即本次 F-01），**两处调用点**：`ErpStockBalanceController.list`（`prepareQuery(query); startPage(); …selectBalanceList(query)`）与 `exportRows`（先 prepare 再取数）；`selectBalanceList` 仅在未被 prepare 时才兜底展开。
**为什么不选另两条**：① `PageHelper.clearPage()` 会把**明细查询自己的分页一起清掉**（静默改页大小）；② `setLocalPage` 在 pagehelper 5.3.3 里**不在 `PageHelper` 上**（实测字节码：`PageHelper` 只有 `getLocalPage/clearPage`，`setLocalPage` 在 `PageMethod`）⇒ 迁移方案脆弱；③"让调用方传大 pageSize"是藏 bug。**另：专用不分页 mapper 也不解决** —— PageHelper 拦的是线程里**下一条**查询，与用哪个 mapper 无关。

**真实 HTTP 复现／验证（独立于 erp-check；夹具前缀 `T54*`，自建自清）**：类型表 **59 行**、`ROOT←LEAF` + 物料挂 LEAF + 审核过账：

| 阶段 | `pageSize=10` | `pageSize=500` |
| --- | --- | --- |
| 修复前（旧 jar） | 父类型 **total=0**（漏子树） | total=1（未被截断 ⇒ 看似正常） |
| 修复后（新 jar） | 父类型 **total=1 命中** | total=1 ⇒ **页大小无关** |

子类型两次均命中 1。修复后已清夹具：残留 product/type/stock **全 0**。

**回归网（服务层，全模块 547 → 550 条）**：`ErpStockBalancePagingTest` 3 条 —— ① 类型 47 行 > pageSize 10 时按父类型筛选**必须**命中子类型物料；② `pageSize=10` 与 `500` 结果一致；③ **反向对照**：把"读全表类型清单"放回分页上下文（旧写法）⇒ 桩按 PageHelper 语义截断前 10 行、`ROOT/LEAF` 不在其中 ⇒ 子树只剩自己（用例变红）—— 证明桩真能复现污染、①② 不是假绿。桩 `StubProductTypeMapper` 新增 `simulatePageHelper`（用真实 `PageHelper.getLocalPage()/clearPage()` 模拟拦截）。
> 测试侧一个坑：单测无登录上下文时 `ErpDocScope` 会返回 `1=0`（"不可见任何行"），桩忠实模拟该片段语义会把夹具行全挡掉、掩盖分页变量 ⇒ 用测试子类 `ScopeFreeService` 覆写 `currentScopeSql()` 返回 null 来隔离变量。

**门禁加固（F-02；经 captain 授权，只动 `erp-check.ps1` 的 L-06 一个 Case）**：判据由"单次命中"改为**双页大小对拍** `total(父,10) == total(父,500) ≥ total(子,10) > 0`，失败时打印两个 total、类型表行数、父/子类型 code；Case 注释写明纪律「**门禁判据不得依赖库规模/夹具位置**」（旧写法等于"根/叶恰好落在前 10 行才绿"）。实测：`tools\erp-check.ps1` 全段 **88 通过 / 0 失败 / 25 跳过**，`L-06` PASS、`CLEAN-01`/`CLEAN-02` PASS。