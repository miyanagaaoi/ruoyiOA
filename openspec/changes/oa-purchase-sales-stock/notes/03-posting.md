# 03 · 库存过账与红冲（t3-posting）交付记录

> 组：T3（backend-posting）｜范围：`tasks.md` §5（5.1~5.6）｜实现口径：design D2/D3/D5/D9/D10
> 交付时间：2026-10-05｜状态：L1 + L2 已跑（真实输出见 §7）｜待 T10/T11/T13 复核项见 §8

---

## 1. 交付物清单（全部在 `ruoyi-ctms` 模块内，包 `com.ruoyi.ctms.erp.posting`）

### 1.1 域对象（domain）

| 文件 | 表 | 说明 |
| --- | --- | --- |
| `posting/domain/ErpStockIn.java` | `t_ctms_stock_in` | 入库单表头（继承 base 的 `ErpDocHeader`，特有列 4 组 + 查询条件） |
| `posting/domain/ErpStockInItem.java` | `t_ctms_stock_in_item` | 入库单行项（无特有列，继承 `ErpDocItem`） |
| `posting/domain/ErpStockOut.java` | `t_ctms_stock_out` | 出库单表头 |
| `posting/domain/ErpStockOutItem.java` | `t_ctms_stock_out_item` | 出库单行项 |
| `posting/domain/ErpStock.java` | `t_ctms_stock` | **结存**（只记数量；无金额列，也没有 dept_id/create_id） |
| `posting/domain/ErpStockLedger.java` | `t_ctms_stock_ledger` | **流水**（只增不改；红冲前缀常量 + `isReversal()`） |

> 结存与流水的 domain 归本组（简报 §1.1）；`backend-ledger`（T7）只做只读查询，读这两个对象即可，**不要**再写一份。

### 1.2 Mapper（接口 + XML，XML 在 `resources/mapper/erp/`）

| 接口 | XML | 关键点 |
| --- | --- | --- |
| `ErpStockMapper` | `ErpStockMapper.xml` | `selectStockForUpdate` = `SELECT ... FOR UPDATE`；`updateStockQty` 写**绝对值** |
| `ErpStockLedgerMapper` | `ErpStockLedgerMapper.xml` | **只有 insert 与查询，没有 update/delete**（"只增不改"在数据访问层成立） |
| `ErpStockInMapper` / `ErpStockInItemMapper` | 同名 XML | 表头全列覆盖式 update（`dept_id`/`create_id`/`doc_no` 不可变）；行项先删后插 |
| `ErpStockOutMapper` / `ErpStockOutItemMapper` | 同名 XML | 与入库对称 |
| （复用 B3）`CtmsChangeLogMapper` | `mapper/ctms/CtmsChangeLogMapper.xml` | 变更历史；本组只读它的接口、不改它 |

### 1.3 服务

| 文件 | 说明 |
| --- | --- |
| `posting/service/IErpStockJournalService` + `impl/ErpStockJournalServiceImpl` | **过账引擎**：结存与流水的唯一写入服务（行锁 / 负库存 / 幂等 / 红冲 / 重复键重试 / 监听器回调） |
| `posting/service/IErpStockPostingListener` | 过账/红冲扩展点（采购线、销售线"过账时回写已入库量/已出库量"用；同一事务） |
| `posting/service/IErpStockInService` + `impl/ErpStockInServiceImpl` | 入库单 CRUD + 6 个状态动作 + 生成单路径 + 变更历史 |
| `posting/service/IErpStockOutService` + `impl/ErpStockOutServiceImpl` | 出库单（同上；审核受负库存校验） |
| `posting/support/ErpPostingLine/Request/Options/Outcome/ErpPostedLine` | 过账入参/出参（引擎只认这些对象，不认实体） |
| `posting/support/ErpStockChangeLogWriter` | `t_ctms_change_log` 的写入点（status / posted / _items） |
| `posting/ErpStockRules` | 库存作业单据的口径与文案（行号前缀、金额转发 `ErpAmounts`、留痕字段名） |

### 1.4 Controller（URL 按 PRD §9.4 的单数资源；权限点按 captain §9.2 冻结表）

`posting/controller/ErpStockInController.java`（`/stk/in-order`）、`ErpStockOutController.java`（`/stk/out-order`）。

### 1.5 单测（`src/test/java/com/ruoyi/ctms/erp/posting/`）

`ErpPostingTestSupport.java`（内存桩 + 行锁模拟）、`ErpStockJournalServiceTest.java`（10 条）、`ErpStockDocServiceTest.java`（9 条）。

---

## 2. 过账口径（审核即过账）

### 2.1 事务与顺序

- `ErpStockInServiceImpl.approveStockIn` / `ErpStockOutServiceImpl.approveStockOut` 与引擎的 `post/reverse`
  都是 `@Transactional(rollbackFor = Exception.class)`：**状态流转与过账在同一事务内**。
- 服务里刻意**先过账、后写状态与 `posted` 标记**：
  过账失败时状态一个字节都不会变（"库存不足被拒 → 结存与状态都不变、不产生流水"），
  这一点在**没有事务回滚能力的内存桩单测**里也成立（真实库里两步同事务，同样整体回滚）。

### 2.2 引擎的两阶段（`ErpStockJournalServiceImpl.apply`）

1. **阶段一（加锁 + 校验，不写库）**：把行指令按（物料, 仓库）**分组并按 key 排序**，
   逐个 `SELECT ... FOR UPDATE`；在锁内逐行累加数量并做负库存校验（报错带行号、可用量、需求量）。
   排序是为了让并发过账以一致顺序取锁（避免死锁）；"校验不写库"是为了"某一行不足 → 整单不写"。
2. **阶段二（写入）**：逐行 `insert t_ctms_stock_ledger`（含 `qty_after` 变动后结存快照），
   最后 `update t_ctms_stock set qty = 绝对值`。结存行不存在时插入；撞唯一键
   `uk_stock_product_warehouse` 捕获 `DuplicateKeyException` 后**重试（上限 3 次）**（design D3）。

### 2.3 幂等

- 已过账（`posted = '1'`）重复审核：**直接返回，不产生流水、不改结存、不报错**。
- 幂等标记由调用方（`ErpStock*ServiceImpl`）读取；`posted` 也是"反审核需红冲"的判定依据。
- `postApprovedStockIn/Out`、`reversePostedStockIn/Out` 同样幂等（已过账/未过账时返回空结果）。

### 2.4 仓库口径（5.1，**2026-10-05 t33 已更正**）

| 位置 | 口径 |
| --- | --- |
| **表头仓库** | **必填**：`t_ctms_stock_in.warehouse_id` / `t_ctms_stock_out.warehouse_id` 是 **NOT NULL + FK**（captain 已用 `information_schema` 复核；冻结 DDL，**不改表**——18 张表是 `初始化-全部.sql` 自检的冻结件，B3 的 `ddl-review.md` 就是有意加 NOT NULL/FK）⇒ 缺仓库在**创建/编辑期**即由**应用层**拒绝：`表头仓库不能为空：请选择入库仓库`（出库侧同构），**不再让用户看到数据库原生 `1048`/`1452`** |
| **表头仓库名快照** | **调用方不必传**（t33-r1）：`warehouse_name` 也是 `NOT NULL`（`DEFAULT ''`，但显式写 `null` 不走默认值 ⇒ 真库报 `Column 'warehouse_name' cannot be null`）⇒ 服务端**从仓库档案回填**显示名（与行项快照同一 `MasterLookup` 真源）；只给 `warehouseId` 即可创建成功；档案不可用时回落空串而**绝不写 null**（详见 §13.4） |
| **行级仓库** | **可空**（DDL 里行级 `..._item.warehouse_id` 是 `YES`）：行项未填 → 写入时**回落表头仓库**（含仓库名快照）；行项填了 → 按启用状态校验并写行级仓库名快照（`ErpMasterGuards.applyItemWarehouseSnapshot`） |
| **审核期守卫** | **保留为防御路径**（不是主拦截点）：`ErpMasterGuards.resolveWarehouseId` 再解析一次，仍为 null → 抛 `行 N：缺少仓库：请填写表头仓库或在行项上指定仓库`，结存与状态都不变。真库主链路已走不到这里，但**历史脏数据/其它写入路径**仍需要它 |

> **历史口径（已作废，勿再引用）**：本节原先写"两者都空时**草稿允许暂缺**、审核阶段才报行号" ——
> 该路径在真库**不可达**（缺表头仓库的 `POST` 会撞 `1048`），t33 已按上面的表格更正（详见 **§13**）。
> 更正理由：错误的口径比缺失的口径更危险 —— 它会让人以为有一条不存在的路径。

### 2.5 流水字段

| 列 | 取值 |
| --- | --- |
| `biz_type` | 入库单取 `in_type`（采购入库/退货入库/其他入库/盘盈入库…），出库单取 `out_type`；红冲加 `红冲-` 前缀 |
| `doc_type` / `doc_id` / `doc_no` | 单据身份（调拨的两条流水共用同一 `doc_no`） |
| `qty_change` | 正入负出（入库 `+qty`，出库 `-qty`；调拨一负一正） |
| `qty_after` | 该行写完后的结存快照（同一单据同一组合的多行**逐行累加**） |
| `unit_price` | 记录性字段（行单价），不参与结存计算（design D5） |
| `dept_id` / `create_id` / `create_by` | 单据归属部门快照 + 操作人（数据范围与审计用） |
| `create_time` | 由 SQL `sysdate()` 写入（记账时间 = 创建时间；表上没有 update 列） |

---

## 3. 红冲规则（5.4）

1. 触发点：`unapproveStockIn/Out`（已过账才红冲）；`reversePostedStockIn/Out` 供盘点级联。
2. 行为：取该单据**非红冲**的历史流水，逐条**追加其相反数**；原流水行保留、内容不变（"只增不改"）。
3. 红冲流水：`biz_type = 红冲-<原业务类型>`；`remark = 反审核红冲：<反审核原因>`；操作人为反审核人。
4. **不受负库存校验拦截**：红冲允许结存转负（spec 场景"红冲允许结存转负"）。
5. 幂等：引擎按"待冲销条数 = 非红冲流水数 − 红冲流水数"计算（下限 0），
   重复反审核不会把原流水冲两遍；调用方的 `posted` 短路是快路径，引擎的计数是兜底。
6. 反审核同时：`status` 回 `submitted`、`posted` 清 0、`approved_by/approved_at` 置空；**每次留痕**（§6）。

---

## 4. 负库存参数（5.3）

- 参数键：`stock_allow_negative`（`sys_config`，默认 `'false'`；base 的 `ErpStockParams` 冻结键名与真值口径）。
- 真值判定：`true / 1 / Y / yes`（大小写不敏感）视为放开；其余（含读不到、空、无法识别）一律**按不允许处理**。
- 校验位置：引擎阶段一（锁内）；`ErpDocScope`/`sys_config` 读不到的异常被吞掉并回落"不允许"——
  宁可拦住，也不因为读配置失败而放开校验。
- 豁免只有两处且都是**显式传参**（`ErpPostingOptions.skipNegativeCheck()`）：
  ① 红冲（引擎内部固定使用）；② 盘亏出库单过账（`tasks.md` §6.4，由盘点审核路径传入，**不是**系统参数，
  否则任何人都能改配置绕过全部出库校验）。
- HTTP 层**不接受**任何"跳过负库存"的请求参数（Controller 只传 `ErpPostingOptions.defaults()`）。

---

## 5. 并发保护（5.5）

1. **行锁**：每个（物料, 仓库）先 `SELECT ... FOR UPDATE` 再加锁读写；
   同一次过账内按 key 排序取锁（避免死锁）。
2. **显式事务**：见 §2.1；任一步失败整体回滚（结存、流水、状态三者一致）。
3. **结存行并发创建**：不存在则插入；捕获唯一键冲突（1062 → Spring `DuplicateKeyException`）
   后重新加锁读取，最多重试 3 次；仍冲突则明确报错（不产生第二行结存）。
4. **单测方式（必须写明）**：并发用例是**多线程 + 内存桩**（`ErpPostingTestSupport.StubStockMapper`），
   桩把 `selectStockForUpdate → updateStockQty` 之间的区间当作"行锁持有区间"
   （每个（物料, 仓库）一把 `ReentrantLock`），因此丢更新会真实地表现为 70。
   **真实 MySQL 的行锁、gap 锁与事务回滚**由 T10/T13 的接口级并发用例覆盖（§8 第 1 条）。

---

## 6. 审计留痕与变更历史（5.6）

| 动作 | `@Log`（`sys_oper_log`） | `t_ctms_change_log` |
| --- | --- | --- |
| 新建 | `业务类型=INSERT` | `field_name=status`，`old=null,new=draft` |
| 提交 | `UPDATE` | `status: draft → submitted` |
| **审核** | `UPDATE` | `status: submitted → approved` **+** `posted: 0 → 1` |
| **反审核** | `UPDATE` | `status: approved → submitted` **+** `posted: 1 → 0`（备注"反审核红冲：原因"） |
| 编辑 | `UPDATE` | `_items`：行数与合计 |
| 驳回 / 作废 | `UPDATE` | `status`（备注含原因） |

- `@Log` 由 Controller 承担（接口级断言）；`t_ctms_change_log` 由服务在**同一事务**内写，
  写失败则整笔动作回滚（不会出现"状态变了但没人知道是谁改的"）。
- 日志用多态定位：`object_type = stock_in / stock_out`，`object_id = 单据ID`，`contract_id` 留空。

---

## 7. L1 / L2 真实输出

```
# L1：本组单测（走构建锁：tools\locked-run.ps1 -LockName build）
mvn -B -pl ruoyi-ctms test -Dtest=ErpStock*Test
  Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- ErpStockDocServiceTest
  Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- ErpStockJournalServiceTest
  Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS

# L1：全模块（同时确认没有把 B3 基线打红）
mvn -B -pl ruoyi-ctms test
  Tests run: 383, Failures: 5, Errors: 4
  其中「本组 19 条全绿」；B3 的 217 条（Ctms*）全绿；
  失败/报错 9 条全部落在 procurement / sales（ErpPurchaseServiceImplTest、ErpPurRulesTest、TmpProbeTest、
  ErpSalesServiceImplTest）—— 那是 T4/T5 正在施工的包，非本组文件，本组未触碰。

# L2：静态审计
node tools\audit\run-all.js
  共 10 个审计；失败 0 个；未自证 0 个（全部 自检 ✅）
```

单测覆盖的场景（与 tasks.md §5 逐条对应）：

| 用例 | 断言 |
| --- | --- |
| `inboundShouldIncreaseStockAndAppendOneLedgerRow` | 结存 10 + 入库 5 → 15，流水 1 条 `+5`，`qty_after=15`，`biz_type=采购入库`，`doc_type=stock_in`，操作人落库 |
| `approvePostsStockAndRepeatedApproveIsIdempotent` | 审核后 15 且 1 条流水；重复审核仍 15、流水仍 1 条、状态仍已审核 |
| `approveWithoutAnyWarehouseIsRejectedWithRowNo` | 表头+行项都无仓库 → 审核被拒且文案以 `行 1：` 开头含 `缺少仓库`；状态仍待审核、无流水、无结存行 |
| `rowWarehouseFallsBackToHeaderAndSnapshotIsWritten` | 行项未填仓库 → 落库为表头仓库（含仓库名），物料/单位快照齐备，合计 10.00 |
| `outboundBeyondStockKeepsStatusStockAndLedger` | 结存 3 + 出库 5 被拒（含"可用量 3"/"需求量 5"）；结存 3、状态待审核、无流水；参数开启后成功且结存 -2 |
| `unapproveReversesLedgerClearsPostedAndWritesHistory` | 反审核后：2 条流水（`+5`/`-5`）、`-5` 带 `红冲-` 前缀、结存回 10、`posted=0`、状态待审核、留痕 4+2 条 |
| `reversalShouldAppendNegativeLedgerAndKeepOriginal` | 原流水内容不变；红冲备注含原因；`结存 = 初值 + 流水累计`；**重复红冲幂等**（仍 2 条） |
| `reversalShouldBeAllowedToDriveStockNegative` | 5 已被出库占用后再红冲 → 结存 -5（不被负库存拦截） |
| `concurrentOutboundShouldNotLoseUpdate` | 两线程各出库 30、结存 100 → **40**（不是 70）且等于"初值 + 流水累计"；红冲一张 → 70 且仍自洽 |
| `concurrentFirstInboundShouldKeepSingleStockRow` | 并发首次入库 → 只有 1 行结存、数量 = 两单之和（唯一约束 + 重试） |
| `shortageOnAnyRowShouldWriteNothingAtAll` | 两行单据第 2 行不足 → 报 `行 2：`，**第 1 行也没有写入** |
| `totalAmountRoundsEachLineBeforeSumming` | 三行 `1 × 0.125` → 行金额 0.13、合计 **0.39**（走 base 的"先舍入再汇总"） |
| `quantityBeyondUomDecimalsIsRejectedWithRowNo` | 单位 0 位小数录 `1.5` → `行 1：数量的最小单位是 0 位小数` |
| `outOfScopeDetailIsRejectedWith403` | 数据范围外读详情 → 业务码 403 |
| `generatedInboundIsApprovedAndPostsOnDemand` | 生成单直接已审核、`posted=0`；`postApproved*` 后 `posted=1` 且幂等 |

> 内存桩与真实库的差别、以及"整单不写"为何不依赖数据库回滚，见 `ErpPostingTestSupport` 的类注释。

---

## 8. 接口断言清单（交 T10 复核；L3 统一窗口执行）

前置：后端按 DEV-ENV 打包重启一次；`powershell -File tools\oa-login.ps1` 取 token；
`masterData` 需有 1 个启用物料（带启用单位）与 1 个启用仓库（记 `P`/`W`）。全部为 JSON 接口（`application/json`）。

### 8.1 入库单：审核即过账 + 幂等（AC-73 / 5.2）

| # | 请求 | 期望 |
| --- | --- | --- |
| 1 | `POST /stk/in-order` body `{warehouseId:"W",inType:"采购入库",docDate:"2026-10-05",items:[{productId:"P",qty:5,unitPrice:2}]}` | 200；`data.docNo` 形如 `IN2026 10 000001`（前缀 IN + yyyyMM + 6 位）；`data.status="draft"`、`data.posted="0"`、`data.totalAmount=10.00` |
| 2 | 同上但行项不带 `warehouseId` | 200；`data.items[0].warehouseId = "W"` 且 `warehouseName` 为仓库名（**行级回落表头**） |
| 3 | `POST /stk/in-order/submit/{id}` | 200；`status="submitted"` |
| 4 | 预置结存（DB）：`INSERT INTO t_ctms_stock(id,product_id,warehouse_id,qty) VALUES(uuid(),'P','W',10)` | — |
| 5 | `POST /stk/in-order/approve/{id}` | 200；`status="approved"`、`posted="1"`；`t_ctms_stock.qty = 15`；`t_ctms_stock_ledger` 新增 1 条：`qty_change=5, qty_after=15, biz_type=采购入库, doc_type=stock_in, unit_price=2, create_id=当前用户` |
| 6 | 再 `POST /stk/in-order/approve/{id}` | 200（幂等，无错误）；结存仍 15；流水**条数不变** |
| 7 | `GET /stk/in-order/{id}/change-logs` | 含 2 条（status→approved、posted 0→1）；`sys_oper_log` 新增 1 条"入库单"UPDATE |

### 8.2 缺仓库被拒（5.1）

| # | 请求 | 期望 |
| --- | --- | --- |
| 8 | **（t33 更正后的断言）** `POST /stk/in-order` body 无 `warehouseId` | 非 200（业务异常）；`msg` **恰为** `表头仓库不能为空：请选择入库仓库`（应用层主动抛，**不是** `1048`/`1452`、也不是未处理异常堆栈）；库内无该单据行、无行项、无流水<br>出库单同构：`POST /stk/out-order` → `表头仓库不能为空：请选择出库仓库` |
| 8b | **（t33 更正后的断言）** 行级仓库仍可空 | `POST /stk/in-order` 传表头 `warehouseId` 但不传行项 `warehouseId` → 200；回查 `items[0].warehouseId` = 表头仓库、`warehouseName` = 仓库名快照（行级回落不变） |

### 8.3 出库：负库存校验（5.3 / AC-73）

| # | 请求 | 期望 |
| --- | --- | --- |
| 9 | 预置 `t_ctms_stock(P,W)=3`；`POST /stk/out-order`（qty 5）→ submit → `POST /stk/out-order/approve/{id}` | 非 200；`msg` 含 `可用量 3` 与 `需求量 5`；结存仍 3；`status` 仍 `submitted`；`posted="0"`；无新流水 |
| 10 | `UPDATE sys_config SET config_value='true' WHERE config_key='stock_allow_negative'` 后 `POST /stk/out-order/approve/{id}` | 200；`status="approved"`、`posted="1"`；结存 **-2**；流水 1 条 `qty_change=-5` |
| 11 | 复原参数为 `'false'` | — |

### 8.4 反审核红冲（5.4）

| # | 请求 | 期望 |
| --- | --- | --- |
| 12 | 承 §8.1 的单据：`POST /stk/in-order/unapprove/{id}?reason=录错数量` | 200；`status="submitted"`、`posted="0"`、`approvedBy=null`；结存回 10；流水共 2 条：`+5`（原样）与 `-5`（`biz_type=红冲-采购入库`、`remark` 含 `反审核红冲` 与 `录错数量`） |
| 13 | 再次 `POST /stk/in-order/unapprove/{id}?reason=重复` | 非 200（状态非已审核）；流水仍 2 条、结存仍 10 |
| 14 | 红冲后结存转负：结存 0 → 入库 5 → 出库 5 → 反审核入库单 | 200；结存 **-5**（红冲不受负库存拦截） |

### 8.5 并发过账（5.5 / AC-74）

| # | 请求 | 期望 |
| --- | --- | --- |
| 15 | 结存 100，两个终端**并发** `POST /stk/out-order/approve/{id1}` 与 `{id2}`（各 30） | 两个都 200；结存 **40**（不是 70）；`SELECT SUM(qty_change) FROM t_ctms_stock_ledger WHERE product_id='P' AND warehouse_id='W'` + 初值 100 = 40 |
| 16 | 对其中一张 `POST /stk/out-order/unapprove/{id1}?reason=并发用例` | 200；结存 **70**，且仍等于"初值 + 流水累计" |
| 17 | 全库不变式：`SELECT COUNT(*) FROM t_ctms_stock s WHERE s.qty <> (SELECT COALESCE(SUM(l.qty_change),0) FROM t_ctms_stock_ledger l WHERE l.product_id=s.product_id AND l.warehouse_id=s.warehouse_id)` | `0`（T7 的 `stk:stock:recalc` 接口同样应报告一致） |

### 8.6 唯一约束与状态机（AC-73 存储前提）

| # | 请求 | 期望 |
| --- | --- | --- |
| 18 | 直接 `INSERT INTO t_ctms_stock(...)` 再插一行相同（product_id, warehouse_id） | 第二条被 `uk_stock_product_warehouse` 拒绝（1062） |
| 19 | 对已审核入库单 `PUT /stk/in-order/status` body `{id:"…",action:"complete"}` | 非 200；`msg` 含 `库存类单据，不支持「置为已完成」` |
| 20 | 对已提交/已审核单据 `PUT /stk/in-order`（编辑） | 非 200；`msg` 含 `不可编辑`；行项未变 |
| 21 | 无 `stk:in-order:approve` 权限的账号调 `POST /stk/in-order/approve/{id}` | 业务码/HTTP 403（`authz-check.ps1` 的 `IsForbidden` 形态） |
| 22 | 列表：`GET /stk/in-order/list?status=voided` vs 不传 | 不传时**不含**已作废；`includeVoided=1` 才包含 |
| 23 | `GET /stk/in-order/export?...` | 返回 xlsx；列 = 单号/单据日期/状态/仓库/入库类型/金额合计/过账；行集合与列表同筛选同范围 |

> 以上 1~23 条即可复跑；T10/T13 可直接落成 `tools/erp-stock-posting-check.ps1`（本组未新增 `tools/*` 脚本，
> 按简报 §6 的"改共享文件先报文 captain"约定）。

---

## 9. 给其他组的接口（跨组使用，请勿复刻）

| 谁 | 用什么 | 用途 |
| --- | --- | --- |
| T4/T5（采购/销售下推，§3.5/§4.3） | 实现 `IErpStockPostingListener`（`supports(ErpDocType)` + `afterPosted` + `afterReversed`），Spring 自动注入 | **过账时**累加已入库量/已出库量；红冲回退。回调在**同一事务**内，抛错则整笔过账回滚。`ErpPostedLine` 提供 `productId/warehouseId/qtyChange/originalQtyChange/srcItemId` |
| T6（调拨/盘点，§6.2/§6.4/§6.5） | `IErpStockJournalService.post(...)`（一张调拨单每行两条 `ErpPostingLine`，`bizType` 分别 `调拨出库/调拨入库`，`unitPrice=0`）<br>`IErpStockInService.insertGeneratedStockIn` + `postApprovedStockIn(id, ErpPostingOptions.skipNegativeCheck())`<br>`ErpStockOutServiceImpl` 同名方法（**盘亏豁免**）<br>`reversePostedStockIn/Out(id, reason)`（反审核级联，幂等） | 两阶段写入、盘盈/盘亏单生成与过账、级联红冲。调拨的"两仓同单两条流水、单价 0、不产生金额"由调用方组装行指令，引擎只负责锁与落库 |
| T7（库存账，§7.x） | `ErpStock` / `ErpStockLedger` 域对象 + 自己的只读 Mapper（`ErpLedger*Mapper.xml`） | 明细/流水/额度/一致性校验；**不要**再写一份结存或流水的写入或 domain |
| T10（权限与数据范围，§8.2） | 列表已写 `dataScopeSql = ErpDocScope.buildDataScopeSql("d")`；详情/动作/删除已过 `IErpDocObjectAccess` | 四档范围只需实测与补菜单权限点（`stk:in-order:*` / `stk:out-order:*`） |
| T12（前端页面） | 端点见 §8；通用动作入口 `PUT /stk/{in,out}-order/status` body `{id,action,reason}` | 操作列只需一个入口；`action` 取 `submit/approve/reject/void/unapprove` |

---

## 10. 已知边界与待复核项

1. **真实库并发/回滚未在本组执行**：本组证据是"多线程 + 内存桩"（§5.4）。真实 MySQL 的行锁、
   并发首次建行的重复键重试、以及"多行单据整单回滚"由 T10/T13 按 §8.5 实测（L3）。
2. **编号**：按 captain §9.1，单号只走 base 的 `ErpDocNoGenerator.nextDocNo(ErpDocType[, Date])`
   （前缀 `IN`/`OUT`，配置 id `…C105`/`…C106`）。Bean 未装配时创建会**明确报错**，本组**没有**自算兜底分支。
   前置：T1 的 8 条 `t_code_config` 种子与 `ruoyi-serial` 可用（L3 首次创建单据时验证）。
3. **数据范围四档**：SQL 片段与 403 判定已接线（复用 base 的单点实现），但"四档账号可见集合矩阵"
   属 T10（§8.2）的实测范围；`t_ctms_stock` 没有 dept/create 列，结存的可见性口径应由流水的
   数据范围决定（T7/T10 定稿），本组未在结存侧另造一套范围规则。
4. **打印**：不新增端点。8 类单据的打印走平台打印模块（B2 内置版式按类型选用）。
5. **导出**：本组实现了简易 Excel（列 = 单号/单据日期/状态/仓库/类型/客户或供应商/金额/过账）。
   若 T12 的页面需要"记住上次列选择"或异步导出，属新增需求，请报文 captain。
6. **附件**：`stock_in` / `stock_out` 的对象类型已由 T1 的附件对象登记覆盖，本组不开端点。
7. **变更历史写入器的重复**：`posting.support.ErpStockChangeLogWriter` 与
   `procurement.support.ErpPurChangeLogWriter` 是同一张表的两个薄封装（字段名口径一致）。
   建议由 base 收口为唯一实现；在那之前本组不跨包引用别组的 support 类（已报文 captain）。
8. **`stopNegativeStock` 类请求参数**：接口层刻意不存在"跳过负库存校验"的参数；
   若联调时有人通过改系统参数来放宽出库，属绕过口径，请报文 captain。
9. **状态动作 `push`**：入库单/出库单是**下推目标**而非源单（源单是采购单/销售订单/盘点单），
   因此本组不提供 `stk:in-order:push` / `stk:out-order:push` 端点；
   T8 的权限点清单里若含这两个动作，应为空菜单项（已报文 captain）。

---

## 11. t23 修复记录：盘点生成的盘盈/盘亏单遇**停用物料**会生成失败

> 任务：**t23**（kind=repair，sourceTaskId=t3）｜缺陷由 stockops 在 `notes/06-stockops.md` §10.2 报出、
> captain 裁决"生成路径豁免 + 必须显式参数/专用方法 + 不得全局开关 + 补反向断言"。
> 修复时间：2026-10-05｜影响面：`base/ErpMasterGuards`（新增方法）+ `posting` 两个单据服务（生成路径接线）

### 11.1 缺陷与根因（前后对照）

| | 内容 |
| --- | --- |
| 触发链 | 盘点单审核 → `ErpStocktakeServiceImpl` 生成调整单 → **T3 的** `insertGeneratedStockIn/Out` → `normalizeItems` → `ErpMasterGuards.applyItemSnapshot` → `requireEnabledProduct` 抛错 |
| **修复前**（调用点：`ErpStockInServiceImpl:463`、出库侧 `:447`，另见 `notes/06-stockops.md` §10.2 的原文记录） | 生成路径与用户录入路径**共用同一个守卫调用**，含停用物料的盘点差异在"生成"这一步失败：<br>业务异常原文 = **`行 1：物料「停用螺丝」已停用，不能用于新单据`**（带行号的文案）<br>⇒ 该盘点单**永远无法平账**（AC-75 要求盘盈/盘亏必须过账成功） |
| 触发前提 | 全盘行项来自结存表、快照**刻意允许停用物料**（06-stockops §5.3）：物料可能在建结存之后被停用 |
| **修复后** | 生成路径改走**专用方法** `ErpMasterGuards.applyGeneratedItemSnapshot(...)`：放过"物料已停用"，其余校验与快照照旧；<br>生成单成功（已审核未过账）→ `postApprovedStockIn/Out` 过账成功 → 结存按差异变化 |

> **"修复前异常原文"的取证口径（诚实说明）**：本次**没有**回滚代码去跑一遍旧行为（那会牵动全队工作树）。
> 该原文由两处相互独立、且本次**已执行**的证据共同锁定：
> ① 调用方 stockops 的缺陷记录（`06-stockops.md` §10.2，含修复前的两个行号）；
> ② 修复前的生成路径调的**就是**严格方法，因此"严格方法在同一行项上的文案"
> 与"修复前生成路径的文案"逐字相同 —— 由本次单测 `strictGuardStillRejectsDisabledProductWithRowNo`
> 断言（`行 3：物料「停用螺丝」已停用，不能用于新单据`）与 `userCreateStillRejectsDisabledProductWithRowNo`
> （`行 1：…已停用…`）执行给出。
> 真机 before/after（盘点单 approve 端到端）留给 T20/T13 的统一窗口用修复后的 jar 复核。

### 11.2 修复实现（三处，全部是"新增/显式传参"，语义向后兼容）

| 文件 | 改动 |
| --- | --- |
| `erp/base/ErpMasterGuards.java` | 新增 `applyGeneratedItemSnapshot(lookup, item, rowNo)`（专用方法）；内部把守卫抽成私有 `requireProduct(..., boolean requireEnabled)` 与 `applySnapshot(..., boolean requireEnabledProduct)`；`applyItemSnapshot` / `requireEnabledProduct` 的**签名与行为一字未改**（仍严格） |
| `erp/posting/service/impl/ErpStockInServiceImpl.java` | `normalizeItems(doc)` → `normalizeItems(doc, boolean generated)`；`insertStockIn`（新建）与 `updateStockIn`（编辑）传 `false`（严格），`insertGeneratedStockIn` 传 `true`（豁免） |
| `erp/posting/service/impl/ErpStockOutServiceImpl.java` | 同上（`insertGeneratedStockOut` 豁免；新建/编辑严格） |

**豁免面（刻意最小）**：只放过"物料已停用"。以下在生成路径上**照旧生效**（既有断言 + 新增断言双重锁定）：
未选物料 / 物料档案不存在、计量单位不存在、**计量单位已停用**、数量精度（不得超过单位小数位）、
行项快照（编码/名称/规格/单位名/单位小数位）与行金额"先舍入到 2 位"。

**不是全局开关**：无系统参数、无静态标志位；只有显式调用 `applyGeneratedItemSnapshot` 才放宽
（与 `ErpPostingOptions.skipNegativeCheck()` 同一形态）。单测用"先调生成路径 → 再调严格路径仍被拒"的顺序无关性
+ 反射断言"类里没有静态布尔字段"把这条钉住。

### 11.3 新增回归断言（9 条；基线 473 → 本次运行 = 全模块只增不减）

| 测试类 / 用例 | 断言要点 |
| --- | --- |
| `ErpMasterGuardsGeneratedPathTest`（新文件，4 条） | ① 严格路径仍拒停用物料且文案逐字 `行 3：物料「停用螺丝」已停用，不能用于新单据`；② 生成路径放过停用物料并写入快照（名称/编码/单位/小数位）与行金额（0.1875→**0.19**）；③ 生成路径仍拦"物料不存在 / 单位不存在 / **单位已停用** / 数量精度"；④ 豁免是**每次调用**而非全局状态（先生成后严格仍拒；并反射断言无静态布尔开关） |
| `ErpStockDocServiceTest`（我的 t3 用例类，9 → **14** 条，+5） | ① `generatedInboundAllowsDisabledProductAndPostsAndChangesStock`：停用物料、结存 8 → 生成盘盈单（状态已审核、未过账、**快照仍写入**）→ 过账成功 → 结存 **10**，流水 1 条 `+2`/`盘盈入库`/`qty_after=10`；② `generatedOutboundAllowsDisabledProductEvenWhenAvailableIsInsufficient`：停用物料、可用量 **1** 而盘亏 **2** → 生成成功 + `skipNegativeCheck()` 过账成功 → 结存 **-1**（AC-75 的盘亏同族路径）；③ `userCreateStillRejectsDisabledProductWithRowNo`：用户新建入库/出库用停用物料仍拒（`行 1：` + `已停用` + `不能用于新单据`），不落库；④ `userEditStillRejectsDisabledProductWithRowNo`：用户编辑（草稿改行项为停用物料）仍拒，且被拒的编辑**不得改动原行项**；⑤ `generatedPathStillEnforcesMissingProductMissingUomAndQtyPrecision`：生成路径仍拦"物料不存在 / 单位不存在 / 单位停用 / 数量精度超限"，且全部被拒时零落库零流水 |

**"校验与文案不变"的证据**：这些文案的唯一实现点在 `ErpMasterGuards`（T1 公共层），本次只新增分支、
未改既有分支；全模块测试一次性覆盖采购线/销售线/出入库/调拨盘点的既有停用物料断言
（`ErpPurchaseServiceImplTest`、`ErpSalesServiceImplTest`、`ErpMasterRefGuardsTest`、
`ErpMasterSnapshotTest`、`ErpStockDocServiceTest` 的反向断言等）——见 §11.4 的整模块结果。

### 11.4 本次验证输出

```
# 定向（修复后、编译正常时）
mvn -B -pl ruoyi-ctms test -Dtest=Erp*Test     （tools\locked-run.ps1 -LockName build）
  Tests run: 260, Failures: 0, Errors: 0, Skipped: 0
  其中：ErpMasterGuardsGeneratedPathTest 4/4、ErpStockDocServiceTest 14/14、ErpStockJournalServiceTest 10/10
  BUILD SUCCESS

# 任务 verify（整模块）
mvn -B -pl ruoyi-ctms test                      （tools\locked-run.ps1 -LockName build）
  Tests run: 491, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
  （t23 前基线 473 条 → 491 条 = 只增不减；本组新增 9 条：ErpMasterGuardsGeneratedPathTest 4 条、
    ErpStockDocServiceTest 9→14 条）
```

> 期间遇到两次**模块级编译失败**（都不在本组文件上，按简报 §3.1 只报文不代改、修复后立即重跑）：
> ① `stockops` 的 `ErpStocktakeServiceImpl` 调用了不存在的 `getQtyOrZero()`（t19 期间）；
> ② `sales` 的 `ErpSalesPushServiceImpl:645` 两处 `matchSourceItem` 重载歧义（t23 期间，
> 由 backend-sales 改名为 `matchOrderSourceItem` 修复）。两次都在对方回报后重跑通过。

### 11.5 环境备注（与 t19 同源）

- 线上后端 jar（23:11:31）是**修复前**的产物；本修复要生效需重新打包（T20 会做统一重建）。
- 本次修复**未改** `sql/`、前端、stockops 的控制器/服务（其 §10.2 已确认生成路径调用点无需改动）；
  也**未改**采购/销售/调拨/盘点的任何守卫与文案。

---

## 12. t29 新增：`ErpStockOutMapper.selectBySourceDocId`（按来源单只读查下游出库单）

> 任务：**t29**（kind=implementation，sourceTaskId=t8）｜缺口由 backend-sales 在 t8 报出：
> 销售**订单**反审核的"上游守卫"要判断"这张订单是否已被下游出库单引用"，而 `t_ctms_stock_out.source_doc_id`
> 是 T3 的表；为避免销售包跨包直读别人的表，由 T3 提供一个**只读**窄接口。
> 交付时间：2026-10-05

### 12.1 新增签名（**冻结**，销售侧按此调用；不得改名/改参数）

```java
// ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/posting/mapper/ErpStockOutMapper.java
List<ErpStockOut> selectBySourceDocId(@Param("sourceDocId") String sourceDocId);
```

语义（写进接口 javadoc）：

| 项 | 口径 |
| --- | --- |
| 过滤 | `source_doc_id = 入参` **且** `del_flag = '0'` |
| 状态 | **不过滤**：本方法是"原始事实查询"，"已作废的下游单算不算引用"由调用方判定（销售侧在 Java 侧过滤作废，与采购线 `assert_no_downstream` 同口径） |
| 排序 | `create_time, id`（create_time 可能同秒，用 id 兜底）—— **稳定** |
| 空结果 | 无命中返回**空集合**（不是 `null`） |
| 只读 | 无任何写入语义；**不参与**过账/红冲 |

### 12.2 XML 语句（`resources/mapper/erp/ErpStockOutMapper.xml`，复用既有 resultMap 与片段）

```xml
<!-- t29：按来源单据查下游出库单（销售订单反审核的"上游守卫"用；**只读**）。 -->
<select id="selectBySourceDocId" resultMap="ErpStockOutResult">
    <include refid="selectStockOutVo"/>
     where d.source_doc_id = #{sourceDocId}
       and d.del_flag = '0'
     order by d.create_time, d.id
</select>
```

**列名对 `information_schema` 的核对（真实输出）**：

```
XML 选择列数=34  表实际列数=34
XML 里但表里没有（应为空）:            ← 空
表里有但 XML 未选（允许的省略）:      ← 空（列片段覆盖表内全部 34 列）
t29 依赖的列核实: create_time, del_flag, id, source_doc_id
--- 关键列定义 ---
create_time:datetime:NO
del_flag:char(1):NO
id:varchar(64):NO
source_doc_id:varchar(64):YES
```

（核对方式：从 `selectStockOutVo` 片段抽出全部 `d.<列>` 与
`SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_stock_out'`
逐项求差集，两个方向都为空。）

### 12.3 单测（新文件 `ErpStockOutSourceDocQueryTest`，**6 条**；沿用内存桩风格）

| 用例 | 断言要点 |
| --- | --- |
| `mapperSignatureIsFrozenForSalesSide` | 反射锁签名：返回 `List`、单参数 `String`、参数带 `@Param("sourceDocId")`；并断言本方法族**没有**按来源单的 insert/update/delete（只读） |
| `xmlStatementUsesSourceDocIdAndDelFlagAndReusesFragments` | XML 里 `selectBySourceDocId` 必须是 `<select>`、复用 `resultMap="ErpStockOutResult"` 与 `<include refid="selectStockOutVo"/>`、含 `d.source_doc_id = #{sourceDocId}`、`d.del_flag = '0'`、`order by d.create_time, d.id`；且既有 5 个语句 id **一个不少**（`selectStockOutList/selectStockOutById/insertStockOut/updateStockOut/deleteStockOutByIds`）；列片段去重后 = **34 列**（与 information_schema 一致） |
| `multipleMatchesAreAllReturnedAndDeletedRowsExcluded` | 同一来源单 3 条（其中 1 条 `del_flag='1'`）+ 别的来源单 1 条 → 返回 **2 条**，按 `(create_time,id)` 顺序，且只含未删除 |
| `noMatchReturnsEmptyListNotNull` | 无命中 → `assertNotNull` + `isEmpty`（**不是 null**） |
| `rowsAreOrderedByCreateTimeThenId` | 乱序插入 4 条（3 条同秒）→ 先按 create_time、同秒按 id |
| `nullSourceDocIdMatchesNothing` | 入参为 null → 空集合（不抛异常、不返回 null） |

> 桩（`ErpPostingTestSupport.StubStockOutMapper.selectBySourceDocId`）与 XML 是"同一口径的两处实现"：
> 桩按 `(create_time, id)` 排序是为了让排序口径在单测里可观察；**真库上的 SQL 行为**由 T13/T20 的
> 接口级用例覆盖（本组单测不连库）。

### 12.4 验证输出

```
# 定向（新用例）
mvn -B -pl ruoyi-ctms test -Dtest=ErpStock*Test      （tools\locked-run.ps1 -LockName build）
  ErpStockOutSourceDocQueryTest 6/6、ErpStockDocServiceTest 14/14、ErpStockJournalServiceTest 10/10 …
  Tests run: 63, Failures: 0, Errors: 0, Skipped: 0      BUILD SUCCESS

# 任务 verify（整模块）
mvn -B -pl ruoyi-ctms test
  Tests run: 531, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
  （t29 前基线 491 → 531：本组 +6（新文件 6 条），其余为各组在同批内的新增；对同一份源码的
    更早一次整模块绿跑为 510/0/0，两次都 BUILD SUCCESS）
```

### 12.5 边界与过程备注

1. **销售侧接手点（已交付并被消费）**：`ErpStockOutMapper.selectBySourceDocId(String)`，签名冻结
   （`List<ErpStockOut>`，参数 `@Param("sourceDocId")`）。backend-sales 已在 t8 的订单反审核守卫
   `checkNoDownstreamStockOuts` 里注入它并**在 Java 侧过滤作废**（与采购线 `assert_no_downstream` 同口径）；
   本方法的"不过滤状态"正好把该口径留给调用方。
2. **未改**：`sql/`、前端、销售包（`erp/sales/**`）、别人的 notes、写入路径（本组只新增一个 `<select>`
   与一个接口方法；既有 5 个语句 id 一字未动，新测试类对此有静态断言）。
3. **过程**：本次 verify 期间连续遇到两次**他组在途**导致的失败，均按简报 §3.1 只报文、不代改：
   ① 测试编译错误 `ErpSalesPushOutTest:470/:475` 调用了本组桩上不存在的 `qtyOf(...)`（等价方法名是
   `qty(productId, warehouseId)`）+ `:642` 的 `nextOrderDocNo` 覆盖不匹配；② 同一测试类的端到端用例
   `salesLineEndToEndFromRequestToPostedStockOut` 因**它自己的内存桩重复计数**而红（backend-sales 已定位
   为桩缺陷并修好，未放宽断言）。两次修复后重跑 → 510/0/0 BUILD SUCCESS。

---

## 13. 跨组事实核对（t29 期间由 backend-procurement 提出）：`t_ctms_stock_in.warehouse_id` 是 NOT NULL

**事实**（procurement 用 `information_schema` 复核、本组复核一致）：

| 列 | 定义 |
| --- | --- |
| `t_ctms_stock_in.warehouse_id` | `varchar(64) NOT NULL` + FK → `t_ctms_warehouse(id)` |
| `t_ctms_stock_in.warehouse_name` | `varchar(64) NOT NULL DEFAULT ''` |
| `t_ctms_stock_out` 同名列 | 同上（NOT NULL + FK） |

**与本文件 §5.1 / §8.2#8 的冲突**：那两处按 tasks.md §5.1 写的是"行项未填仓库回落表头；
**两者都空时草稿允许暂缺、审核阶段报 `行 N：缺少仓库`**"。但真库把表头 `warehouse_id` 钉成 NOT NULL，
⇒ 缺表头仓库的 `POST /stk/in-order` 会在 **insert** 阶段就因 1048（NULL）或 FK 1452（空串）失败，
**永远到不了审核**；也就是说"审核报行号"这条分支在**真库路径上不可达**。

**本组已执行的事实澄清（t29 期间只读核对；t33 已按方案 A 落地）**：
- 服务的**代码与单测**覆盖该守卫（内存桩断言"行 1：缺少仓库"），但**内存桩不模拟 NOT NULL**，
  所以这条断言证明的是"守卫逻辑正确"，不能证明"真库能走到审核再报错"。
- 本组的 §8.2#8 接口断言（"表头与行项都不带仓库 → submit → approve → 报行号"）在真机上**不可达**，
  已按下面的**方案 A** 改成"创建期被拒 + 可读文案"（见 §13.3 与 §8.2#8 的更正）。

**两个处置方案（captain 2026-10-05 裁决 = A）**：

| 方案 | 做法 | 影响 |
| --- | --- | --- |
| **A（✅ captain 已采纳，t33 已落地）** | 在 `insertStockIn/insertStockOut`（以及编辑路径）把"表头仓库必填"前移：缺则抛可读中文业务异常；审核期守卫保留为**防御性兜底**；**不改 DDL** | 只改 posting 与单测；`POST` 的拒绝从 1048/FK 变成中文业务提示；§8.2#8 的断言同步改成"创建期被拒"（已在本文件更正） |
| B（未采纳） | 把 `warehouse_id` 放开为可空（DDL 变更 + 审核期守卫生效） | 触碰 T1 的冻结 DDL 与 F-3 的"NOT NULL + FK"收紧口径，且与"库存单据必须有仓库"的业务事实相悖 |

> 采购线的处置（procurement 已在 t7 完成）：在下推阶段就拒绝无仓库的采购单，避免把 1048 抛给用户 ——
> 与本组方案 A 同向，只是拦截点在下推侧。

### 13.3 t33 落地记录（方案 A，2026-10-05）

| 位置 | 改动 |
| --- | --- |
| `posting/.../ErpStockInServiceImpl.java` | 私有 `applyHeaderWarehouse(doc)` 在 `insert(...)`（新建，含生成路径）与 `updateStockIn`（编辑）各调一次，**位置在写库之前**（含创建快照/取号/行项快照之前）⇒ 拒绝时零落库、零流水；内含私有文案常量 `MSG_HEADER_WAREHOUSE_REQUIRED = "表头仓库不能为空"` |
| `posting/.../ErpStockOutServiceImpl.java` | 同构（标签"出库"，同名私有常量——t33 的写入范围限定在 `posting/service/impl`，故两侧各一份前缀，双方文案由 `ErpStockDocServiceTest` 逐字锁定） |
| `posting/.../ErpStockDocServiceTest.java` | 原 `approveWithoutAnyWarehouseIsRejectedWithRowNo` 改为 `approveGuardStillRejectsMissingWarehouseAsDefensivePath`（**改为直塞内存桩**、断言行号文案与零流水/零结存 ⇒ 覆盖"防御路径"）；新增 `createWithoutHeaderWarehouseIsRejectedWithReadableMessage`（入库/出库/生成路径三条 + 断言无数据库错误码 + 零落库零行项零流水）、`editWithoutHeaderWarehouseIsRejectedAndKeepsOriginalWarehouse`、以及 r1 的两条（见下） |

### 13.4 t33 **revision 1**：`warehouse_name` 快照回填（只给 id 也必须能创建）

**触发**：t34 用新 jar 跑写路径探针时，`POST /stk/in-order` 紧接着暴露第二个真缺口（captain 原文）：

```
java.sql.SQLIntegrityConstraintViolationException: Column 'warehouse_name' cannot be null
```

**精确 DDL 事实（本组用 `information_schema` 逐列复核；比"NOT NULL+FK"更精确）**：

| 表 | 列 | 定义 | FK |
| --- | --- | --- | --- |
| `t_ctms_stock_in` / `t_ctms_stock_out` | `warehouse_id` | `varchar(64) NOT NULL`（无默认值） | ✅ `→ t_ctms_warehouse.id` |
| 同上 | `warehouse_name` | `varchar(64) NOT NULL DEFAULT ''`（**是快照列，没有 FK**） | ✗ |
| `t_ctms_stock_in_item` / `t_ctms_stock_out_item` | `warehouse_id` / `warehouse_name` | `varchar(64) NULL`（行级可空） | ✗ |

⇒ `warehouse_name` 虽有 `DEFAULT ''`，但 MyBatis 显式写 `#{warehouseName}` = `null` 时**不会**走默认值，
所以真库报 `cannot be null`。修复口径（按 captain 的 amended 契约）：

| 调用方入参 | 行为 |
| --- | --- |
| 只给 `warehouseId`（**不传显示名**） | **创建成功**；服务端从仓库档案回填 `warehouseName`（与行项快照同一个 `MasterLookup` 真源） |
| `warehouseId` + 显示名 | 创建成功；**档案名优先**（仓库改名后新单按新名；调用方给的名字只作为档案不可用时的兜底） |
| 完全不给 `warehouseId` | 可读中文业务错误（见 §13.3） |
| 档案查不到该 id 且调用方也没给名 | 回落 `""`（DDL 默认值）——**绝不写 `null`**；真实运行期 `ErpMasterLookupImpl` 查真表，正常路径总有档案名；伪造 id 由 FK 挡下 |

**实现**：`ErpStockInServiceImpl` / `ErpStockOutServiceImpl` 的私有 `applyHeaderWarehouse(doc)` 做两件事 ——
必填校验（缺 ⇒ 抛 `表头仓库不能为空：请选择入库/出库仓库`，文案前缀是两侧各一份的私有常量，
由单测逐字锁定）+ 名称回填（档案 → 调用方 → `""`）；**未改** `base/ErpMasterGuards`
（t33 的 inScope 不含 base 包），因此这里直接消费服务里已注入的 `masterLookup` 回调（与
`ErpMasterGuards.applyItemSnapshot` 同一真源）。

**r1 新增断言（2 条）**：`createWithOnlyWarehouseIdFillsNameSnapshotFromArchive`（入库/出库只给 id ⇒ 成功且
名称 = `一号仓`；调用方给过期名 ⇒ 仍以档案为准）与
`headerWarehouseNameIsNeverNullEvenWhenArchiveIsUnavailable`（档案不可用 ⇒ 回落 `""` 而**不是 null**，
锁死"不再出现 `Column 'warehouse_name' cannot be null`"）。

**单测真实输出（r1 后）**：

```
mvn -B -pl ruoyi-ctms test -Dtest=ErpStock*Test     （tools\locked-run.ps1 -LockName build）
  ErpStockDocServiceTest 18/18（t33 前 14 → −1改写 +5新增 = 18）
  ErpStockJournalServiceTest 10/10、ErpStockOutSourceDocQueryTest 6/6 …
  Tests run: 67, Failures: 0, Errors: 0, Skipped: 0      BUILD SUCCESS

mvn -B -pl ruoyi-ctms test                          （整模块 verify）
  Tests run: 539, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
  （amended 契约给的基线 535（t31 修复后的全绿数）→ 本组 +4，只增不减）
```

**未改**：`sql/`（DDL 一字未动，18 张表仍是冻结件）、procurement/sales/stockops 的任何文件、前端、
`base/ErpMasterGuards`、行级回落逻辑（`normalizeItems` 不变）、审核期守卫
（`buildPostingLines` 的 `resolveWarehouseId` + 行号文案不变）。

**需 t13 调整的 HTTP 断言**（原"缺仓库 → 草稿 → 审核被拒"已不可达）：

| 原断言 | 更正后 |
| --- | --- |
| `POST /stk/in-order` 不带 `warehouseId` → 200 建草稿；`submit` → `approve` → 报 `行 1：缺少仓库` | `POST /stk/in-order` 不带 `warehouseId` → 非 200，`msg` **恰为** `表头仓库不能为空：请选择入库仓库`；库内无单据/行项/流水 |
| （无） | `POST /stk/out-order` 不带 `warehouseId` → `表头仓库不能为空：请选择出库仓库` |
| （无） | **（r1 新增）** `POST /stk/in-order` **只给 `warehouseId`**（不传 `warehouseName`）→ 200；回查 `warehouseName` = 该仓库档案名快照（t34 的 P 段主用例） |
| （无） | `POST /stk/in-order` 带头仓库、行项不带 → 200，回查行项 `warehouseId` = 表头仓库（**行级回落不变**，新增正向用例 8b） |
| 审核期守卫的覆盖 | 真机已不可达；覆盖方式改为**单测直塞内存桩**（`approveGuardStillRejectsMissingWarehouseAsDefensivePath`），接口脚本不再断言这一条 |
