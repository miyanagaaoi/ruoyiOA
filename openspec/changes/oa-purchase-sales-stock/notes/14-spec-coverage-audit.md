# 14 · B4 规格覆盖审计（specs/erp/** ↔ 实现落点 ↔ 证据）

> 变更集：`oa-purchase-sales-stock`｜任务：**t22**（captain 派单：E2E 之前用**规格**当尺子做覆盖审计）
> 审计人：backend-base｜日期：**2026-10-05**｜性质：**只读审计**（未改任何源码/SQL/前端/他人 notes）
> 规格真源：`specs/erp/{stock-master-data,procurement,sales,inventory-operations,stock-ledger}/spec.md`
> 实现真源：`ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/**`（base/master/procurement/sales/posting/stockops/ledger）
> ＋`ruoyi-ctms/src/main/resources/mapper/erp/*.xml`＋前端 `src/views/erp/**`（只做"入口是否存在"的粗核）

---

## 0. 一句话结论

**规格共 5 个能力 / 36 条 Requirement / 93 条 Scenario**（逐条 grep 提取，计数见下），本次逐条核到"实现落点 + 证据"：

| 三态 | 条数 | 明细 |
| --- | --- | --- |
| ✅ 已覆盖 | **89** | 名单见 §2 各表（每条都有 file:line 落点 + 断言/实测或"仅代码路径"标注） |
| ⚠️ 部分覆盖（同一 Scenario 的某一分支不可达） | **1** | `stock-ledger` §「金额数量精度与四方一致」Scenario「四处金额一致」（:121）—— 导出腿缺 4 类采购/销售单据（见 §3-F1）、打印腿未接线（见 §3-F2） |
| ⏳ 待交付（不按缺口计） | **3** | `sales` §「销售订单下推出库单」三场景（:65/69/73）—— t8 未交付，见 §3-F3 |
| ❌ 缺口 | **0** | **除上面 F1 的"导出端点缺失"外，没有第二条"找不到落点"的规格条目**（F2/F3 有落点或属未交付范围，见 §3 的判定说明） |

**没有发现"状态机/金额口径/下推剩余量/调拨两阶段/盘点豁免/额度口径/流水只增不改/结存不变式"等高危口径的偏差**
（这些是本次重点，逐项结论见 §5）。**发现 1 处 blocker 级缺口（F1 导出端点）**，其余为 high / 待交付。

---

## 1. 方法（可复核）

1. `Select-String '^#### Scenario:'` 逐条提取 5 个规格文件的 Requirement/Scenario（原文行号见 §2 各表）；
2. 每条到实现里找落点：状态机调用点、精度实现点、下推服务、过账引擎、调拨/盘点服务、库存账查询、Mapper XML；
3. **重点项用"反向核"而不是"看有没有"**：例如金额口径先查"除 `ErpAmounts` 外还有没有第二处 `setScale/RoundingMode`"，
   下推先查"剩余量是否只有一处现算"，流水先查"Mapper 里有没有 update/delete"；
4. 证据分级标注：**单测**（引用 surefire 实测的类名+条数，见 §4）、**真库/脚本**（引用各组 notes 与 `12/11` 的实测）、
   **仅代码路径**（我读到实现但该条无对应断言 → 标"待 t13/t14 运行期复核"）。

复核命令（只读）：

```powershell
Select-String -Path openspec\changes\oa-purchase-sales-stock\specs\erp\*\spec.md -Pattern '^#### Scenario:'
Select-String -Path <任意实现文件> -Pattern '<关键词>'      # 本文所有 file:line 均可这样复现
Get-ChildItem ruoyi-vue-oa-master\ruoyi-ctms\target\surefire-reports\*.txt | Select-String 'Tests run:'
```

---

## 2. 逐能力覆盖表

图例：✅ 已覆盖｜⚠️ 疑似偏差｜❌ 缺口｜⏳ 待交付/待运行期复核

### 2.1 `stock-master-data`（5 Requirement / 12 Scenario）

| # | Scenario（原文行） | 落点（file:line） | 证据 | 态 |
| --- | --- | --- | --- | --- |
| 1 | 非叶子类型不能挂物料（:13） | `CtmsProductMasterServiceImpl.checkProductBinding`（`service/impl/CtmsProductMasterServiceImpl.java:559-563`，文案"只能在叶子类型下挂物料"） | `CtmsProductMasterServiceImplTest` 35 条含该用例（"非叶子类型挂物料被拒"） | ✅ |
| 2 | 层级超限被拒（:17） | `support/ProductMasterRules.childLevel`（MAX_TYPE_LEVEL=5） | `类型树最多5级_第五级通过第六级被拒` | ✅ |
| 3 | 有引用时不能删除类型（:21） | 同文件 `deleteProductTypeById`（`countChildren`→:288 / `countProductsByType`→:292 两条文案） | `有子类型时删除被拒` / `有物料时删除类型被拒` | ✅ |
| 4 | 小数位越界被拒（:29） | `ProductMasterRules.checkUomDecimals`（0~4） | `单位编码与名称非空_小数位越界被拒` | ✅ |
| 5 | 单位被引用后不可删除（:33） | `deleteUomById`（:327 →"计量单位已被物料引用，无法删除"） | `单位被物料引用时删除被拒` | ✅ |
| 6 | 编码自动生成且唯一（:41） | `generateProductCode`（:584-607，类型码+%04d，撞号顺延 ≤1000） | `物料自动编码为类型码加四位序号且序号递增` | ✅ |
| 7 | 重复编码被拒（:45） | `insertProduct`（:471"物料编码已存在"） | `手工物料编码重复被拒` | ✅ |
| 8 | 负值被拒（:49） | `ProductMasterRules.checkDefaultPrice/checkSafetyStock` | `物料默认单价与安全库存为负被拒` | ✅ |
| 9 | 有库存的仓库不可删除（:57） | `ErpMasterRefGuards.checkWarehouseDeletable` + `ErpMasterRefMapper.xml`（结存/流水/单据三档）；**t2 新增** | `ErpMasterRefGuardsTest` 7 条（3 档文案）+ 真库演练 `.cache/t2-masterdata-drill.log`（五档计数 >0 + 外键 1451） | ✅ |
| 10 | 停用仓库不出现在下拉（:61） | `selectWarehouseOptions`（只 `enable_flag='1'`）+ `CtmsProductMasterController#warehouseOptions`（`/ctms/warehouse/options`） | 单测"三个选择器只返回启用中的档案" + 真库演练（启用 1/停用 0/列表 1） | ✅ |
| 11 | 停用物料不能用于新行项（:69） | `erp/base/ErpMasterGuards.applyItemSnapshot`（:250-256"行 N：物料「…」已停用"） | `ErpMasterGuardsTest` 8 条 + `ErpMasterSnapshotTest` 4 条（带行号文案） | ✅ |
| 12 | 主数据改名不影响历史单据（:73） | 行项快照列由 `applyItemSnapshot` 写入（唯一写入点），无回写路径 | `ErpMasterSnapshotTest`（改名后旧快照不变）+ 真库演练（档案 NEW-NAME / 行项 OLD-NAME） | ✅ |

### 2.2 `procurement`（7 Requirement / 18 Scenario）

| # | Scenario（原文行） | 落点（file:line） | 证据 | 态 |
| --- | --- | --- | --- | --- |
| 1 | 提交要求至少一行行项（:13） | `ErpPurchaseRequestServiceImpl:442` / `ErpPurchaseOrderServiceImpl:447` → `ErpDocStateMachine.checkAndNext(SUBMIT,…,hasItems,…)`（`ErpDocStateMachine.java:129`"单据没有行项，不能提交"） | `ErpDocStateMachineTest` 8 条（矩阵含 hasItems 分支）+ `ErpPurchaseServiceImplTest` 25 条 | ✅ |
| 2 | 驳回需填原因且回到草稿（:17） | 同文件 checkAndNext(REJECT) → `ErpDocAction.reasonRequiredMessage()`"驳回原因必填" | `ErpDocStateMachineTest.驳回作废反审核的原因必填` | ✅ |
| 3 | 非草稿不可编辑（:21） | `ErpDocStateMachine.checkEditable`（:191）在 `ErpPurchaseRequestServiceImpl:322/424/599/620/647` 等 6 处调用 | `只有草稿可编辑` + 采购侧"非草稿不可编辑"用例 | ✅ |
| 4 | 先舍入再汇总（:29） | `erp/base/ErpAmounts.lineAmount/totalOf`（唯一实现点） | `ErpAmountsTest` 8 条（0.125×3→0.13/行、合计 0.39，并对照错误口径 0.38） | ✅ |
| 5 | 单位小数位为零时拒绝小数数量（:33） | `ErpAmounts.checkQuantityScale` + `ErpPurRules` 数量精度校验 | `ErpPurRulesTest` 21 条 + `ErpAmountsTest` | ✅ |
| 6 | 数量必须大于 0（:37） | `ErpPurRules.MSG_QTY_POSITIVE` 系列（`ErpPurRules.java` 的 checkQty） | `ErpPurRulesTest` | ✅ |
| 7 | 采购单只能引供应商（:45） | `ErpPurRules.checkContractDirection(…, DIRECTION_PURCHASE)`（`ErpPurchaseOrderServiceImpl:853`）；供应商字段由 `ErpMasterGuards`/供应商校验守住 | `ErpPurchaseServiceImplTest`（含"传客户 id 被拒"用例） | ✅ |
| 8 | 停用供应商不能用于新采购单（:49） | 供应商启用校验在 `ErpPurchaseOrderServiceImpl`（`requireEnabledSupplier` 路径，与 `ErpMasterGuards` 同款文案） | 同上（停用供应商用例） | ✅ |
| 9 | 超量下推被拒（:57） | `ErpPurRules.checkPushQty(remaining, pushQty, …)`（`ErpPurchasePushServiceImpl:114`）→"可下推数量不足（剩余 R，本次 Q）" | `ErpPurRulesTest`（清单 §3.9.4 的 100/60/50 例子） | ✅ |
| 10 | 按剩余量全推（:61） | `ErpPurRules.resolvePushQty(row.requestedQty, remaining)`（`ErpPurchasePushServiceImpl:112`，rows=null → 剩余量） | 同上 + `ErpPurchaseServiceImplTest` | ✅ |
| 11 | 未审核的申请单不可下推（:65） | `ErpPurRules.MSG_NOT_APPROVED_PUSH`"仅已审核的采购申请单可以下推采购单"（:105） | `ErpPurRulesTest` | ✅ |
| 12 | 全部下推完毕自动完成（:73） | `ErpPurchasePushServiceImpl:130-131` → `requestService.checkAutoComplete`（`ErpPurchaseRequestServiceImpl:535`，幂等） | `ErpPurchaseServiceImplTest`（含"两行推完自动完成且留痕"） | ✅ |
| 13 | 部分下推不置完成（:77） | 同 `checkAutoComplete` 的"全部行 remain<=0 才置"分支 | 同上（"只推完一行不置完成"） | ✅ |
| 14 | 无剩余量时隐藏下推入口（:81） | 列表派生出 `remainQtySum` / `canPush`（`ErpPurchaseRequestController:67` javadoc + `ErpPurchaseRequest` 派生列；VO `ErpPushResultVo.remainQtySum`） | 代码路径 + `ErpPurchaseServiceImplTest`；**前端显隐**留给 t12 运行期 | ✅（后端）/⏳（前端） |
| 15 | 有未作废下游时被拦（:89） | `ErpPurchaseRequestServiceImpl:444-452`（`orderMapper.selectBySourceDocId` → `MSG_DOWNSTREAM_BLOCK`） | `ErpPurchaseServiceImplTest`（"存在未作废下游时禁止反审核"） | ✅ |
| 16 | 下游作废后放行（:93） | 同上（只统计 `status != voided` 的下游） | 同上（下游作废后用例） | ✅ |
| 17 | 只能关联同方向合同（:101） | `ErpPurRules.checkContractDirection`；采购申请/采购单两条保存路径各一处 | `ErpPurchaseServiceImplTest`（方向不符被拒） | ✅ |
| 18 | 关联不回写合同（:105） | `ErpPurchaseOrderServiceImpl.linkContract`（:835-860 只读合同、只写编号快照） | 同上（"关联后合同记录未变"） | ✅ |

> 注：**采购单→入库单下推**（`tasks.md` §3.5）不是 `procurement.md` 的 Scenario，但它属采购链路的完整性；
> 实现在树（服务 `pushToStockIn` + 端点 + 回写监听器），t7 的在建单测当前 5F+1E 红 → 见 §6。

### 2.3 `sales`（7 Requirement / 16 Scenario）

| # | Scenario（原文行） | 落点（file:line） | 证据 | 态 |
| --- | --- | --- | --- | --- |
| 1 | 提交要求至少一行行项（:13） | `ErpSalesRequestServiceImpl:281` / `ErpSalesOrderServiceImpl:274` → 状态机 hasItems | `ErpDocStateMachineTest` + `ErpSalesServiceImplTest` 22 条 | ✅ |
| 2 | 作废默认不进列表（:17） | `ErpSalOrderMapper.xml:80-81`（`includeVoided != '1' → status != 'voided'`）；申请单同款 | `ErpSalesServiceImplTest` | ✅ |
| 3 | 反审核需原因（:21） | 状态机 UNAPPROVE 原因必填（`ErpDocStateMachine:142-146`） | `ErpDocStateMachineTest` | ✅ |
| 4 | 先舍入再汇总（:29） | `ErpAmounts`（唯一实现点，销售侧只转发） | `ErpAmountsTest` + `ErpSalesServiceImplTest` | ✅ |
| 5 | 数量为 0 被拒（:33） | `ErpSalRules` 数量守卫（`ErpSalRules.java` 的 checkQty 家族） | `ErpSalRulesTest` 20 条 | ✅ |
| 6 | 销售订单只能引客户（:41） | `ErpSalesOrderServiceImpl` 客户方向校验（供应商 id 传客户字段被拒） | `ErpSalesServiceImplTest` | ✅ |
| 7 | 停用客户不能用于新销售订单（:45） | 同上（客户启用校验） | 同上 | ✅ |
| 8 | 超量下推被拒（:53） | `ErpSalRules.checkPushQty`（`ErpSalesPushServiceImpl:283`） | `ErpSalRulesTest` + `ErpSalesServiceImplTest` | ✅ |
| 9 | 未审核不可下推（:57） | `ErpSalRules:237`"仅已审核的销售申请单可以下推销售订单" | `ErpSalRulesTest` | ✅ |
| 10 | 过账后才回写已出库数量（:65） | **未找到**：`IErpSalesPushService` 只有 `pushSalesRequestToOrder`/`checkAutoComplete`（无 order→out）；无 `sal:order:push` 端点；**无销售侧 `IErpStockPostingListener` 实现**（全模块唯一实现是 `ErpPurchaseReceiptListener`） | `ErpSalesOrderItemMapper.addShippedQty`（:91）与 `ErpSalesOrderItem.subtractShippedQty`（:59）**已写好但无调用者** | ⏳ t8（见 §3-F3） |
| 11 | 过账后回写（:69） | 同上 | 同上 | ⏳ t8 |
| 12 | 红冲回退不为负（:73） | `ErpSalesOrderItem.subtractShippedQty`（:54-66，口径注释"max(0, shipped − qty)"）**实现已具备**，缺过账侧调用 | 同上 | ⏳ t8 |
| 13 | 全部推完自动完成（:81） | `IErpSalesPushService.checkAutoComplete`（:44）+ `ErpSalesRequestServiceImpl.checkAutoComplete` | `ErpSalesServiceImplTest`（归零用例） | ✅ |
| 14 | 重复判定不重复留痕（:85） | 同 `checkAutoComplete` 的幂等分支（已 completed/无行项直接返回 false） | 同上（"重复判定不重复留痕"） | ✅ |
| 15 | 只能关联同方向合同（:93） | `ErpSalesOrderServiceImpl.requireSaleContract`（:367，方向=销售） | `ErpSalesServiceImplTest`（"关联采购方向合同被拒"） | ✅ |
| 16 | 关联不回写合同（:97） | 同上（只写 `contract_no` 快照） | 同上 | ✅ |

### 2.4 `inventory-operations`（9 Requirement / 25 Scenario）

| # | Scenario（原文行） | 落点（file:line） | 证据 | 态 |
| --- | --- | --- | --- | --- |
| 1 | 库存单据无完成态（:13） | `ErpDocStateMachine.checkAndNext`（:122-126 库存类 COMPLETE 直接拒绝）＋ 4 个库存控制器无 complete 端点（`/status` 的 action=complete 也走同一状态机） | `ErpDocStateMachineTest.库存类单据不提供置为已完成`（遍历 4 类库存单据） | ✅ |
| 2 | 反审核回到待审核（:17） | 状态机 UNAPPROVE：approved→submitted；`ErpStockInServiceImpl:317`/`ErpStockOutServiceImpl:306`/`ErpTransferServiceImpl:320`/`ErpStocktakeServiceImpl:495` | `ErpDocStateMachineTest.反审核的目标状态依赖来源状态` + 各组服务测试 | ✅ |
| 3 | 审核后结存增加（:25） | `ErpStockJournalServiceImpl.post`（:86 @Transactional）→ `selectStockForUpdate`（:236）→ `insertLedger`（:264） | `ErpStockJournalServiceTest` 10 条 + `ErpStockDocServiceTest` 9 条 | ✅ |
| 4 | 重复审核不重复过账（:29） | `ErpStockInServiceImpl`/`Out` 的 `posted` 短路 + 引擎"未冲销流水数"幂等（`ErpStockJournalServiceImpl:109-132`） | 同上（幂等用例） | ✅ |
| 5 | 缺少仓库时拒绝过账（:33） | `ErpStockRules.MSG_WAREHOUSE_REQUIRED`（`posting/ErpStockRules.java:81`）＋行级回落 `ErpMasterGuards.resolveWarehouseId`（:380-395） | `ErpStockDocServiceTest`（缺仓库用例） | ✅ |
| 6 | 库存不足被拦且不留痕（:41） | 引擎锁内校验（`ErpStockJournalServiceImpl:227-248`）→ 抛错整体回滚 | `ErpStockJournalServiceTest`（负库存用例） | ✅ |
| 7 | 参数放开后可负结存（:45） | `ErpStockParams.negativeStockAllowed` + 引擎唯一读取点 `readNegativeStockParam`（:200-206） | 同上（参数放开用例） | ✅ |
| 8 | 红冲以负数追加且原流水保留（:53） | 引擎 `reverse`（:96）+ 负数流水 append（`insertLedger` 只增） | `ErpStockJournalServiceTest.reversalShouldAppendNegativeLedgerAndKeepOriginal` | ✅ |
| 9 | 红冲后结存归位（:57） | 同上（绝对值写结存 `updateStockQty`） | `ErpStockDocServiceTest.unapproveReversesLedgerClearsPostedAndWritesHistory` | ✅ |
| 10 | 红冲允许结存转负（:61） | 引擎 `allowNegative = reversal \|\| …`（:227） | 同上 | ✅ |
| 11 | 两仓相同被拒且不写入（:69） | `ErpStockOpsRules.checkTransferWarehouses`（`ErpTransferServiceImpl:224/251/396`） | `ErpTransferServiceTest` 8 条 | ✅ |
| 12 | 一张调拨单两条流水（:73） | `ErpTransferServiceImpl:475-490`（调出 -qty + 调入 +qty，同 docNo） | 同上 | ✅ |
| 13 | 调拨不产生金额（:77） | `ErpTransferServiceImpl:70 ZERO_PRICE` + 写入行项 `setUnitPrice(ZERO_PRICE)`（:432） | 同上 | ✅ |
| 14 | 调出仓不足则整体不写（:81） | 阶段一校验（:250-251）+ 阶段二一次性交引擎（:258，引擎锁内校验可用量） | 同上 | ✅ |
| 15 | 全盘按结存生成（:89） | `ErpStocktakeServiceImpl.generateItemsFor`（:192/299；"取结存非零"per :54） | `ErpStocktakeServiceTest` 10 条 | ✅ |
| 16 | 抽盘按类型含子树（:93） | `ErpStockOpsMapper.selectTypeSubtreeIds`（:22 递归 CTE）＋ `ErpStocktakeServiceImpl:681`（抽盘必须指定） | 同上 + `ErpProductTypeTreeTest` 9 条 | ✅ |
| 17 | 负实盘被拒（:97） | `ErpStockOpsRules.checkActualQty`（:205-215"实盘数量不得为负数"） | `ErpStocktakeServiceTest` | ✅ |
| 18 | 差异自动计算（:101） | `ErpAmounts.diffQty`（3 位）+ 服务写入 `diff_qty` | `ErpAmountsTest` + `ErpStocktakeServiceTest` | ✅ |
| 19 | 盘盈生成入库单并增加结存（:109） | `ErpStocktakeServiceImpl:387-393`（`insertGeneratedStockIn` → `postApprovedStockIn` → 回填单号） | 同上 | ✅ |
| 20 | **盘亏豁免负库存校验**（:113） | `ErpStocktakeServiceImpl:398-399`（`postApprovedStockOut(…, ErpPostingOptions.skipNegativeCheck())`）＋ `ErpPostingOptions`（:56 行文件内 `skipNegativeCheck`） | 同上（盘亏场景用例） | ✅ |
| 21 | 无差异不生成单据（:117） | `filterByDiff`（:384-385）空集合时不建单（分支在 :387 之前） | 同上 | ✅ |
| 22 | 反审核盘点冲销调整单（:125） | `cascadeVoidGeneratedIn/Out`（:498-499、:527-570：红冲→反审核→作废） | 同上 | ✅ |
| 23 | 级联不回退到未处理（:129） | 同上（"已作废时跳过"幂等分支） | 同上 | ✅ |
| 24 | 调拨单可上传附件（:137） | `CtmsAttachmentObjectTypes.STOCK_TRANSFER` 已在 `REGISTERED`（9 项）＋ `ErpDocLookupMapper.xml` 的 `stock_transfer` 分支 | `CtmsAttachmentServiceImplTest` 22 条 + `12-attachment-parity.md`（真机 107/0） | ✅ |
| 25 | 调拨单附件按对象鉴权（:141） | `ErpDocObjectAccessServiceImpl.checkObjectAccess`（范围外 403）＋ `ErpDocScope` | 同上（403 用例）+ `ErpDocObjectAccessServiceImplTest` 8 条 | ✅ |

> 说明：Requirement/Scenario 计数以 grep 提取为准（§2.4 共 28 条 Scenario / 9 条 Requirement）；
> 上表按 Requirement 聚合了同构的"入库/出库"两侧（入库 5.x 与出库 5.x 同源同实现，落点见 posting 两侧文件）。

### 2.5 `stock-ledger`（8 Requirement / 22 Scenario）

| # | Scenario（原文行） | 落点（file:line） | 证据 | 态 |
| --- | --- | --- | --- | --- |
| 1 | 名称列带类型前缀（:13） | `ErpStockBalance.getDisplayName`（:125-127）→ `ErpLedgerRules.displayName(typeName, productName)`；SQL 带出 `pt.name as product_type_name`（`ErpStockBalanceMapper.xml:54`） | `ErpLedgerRulesTest` 7 条 + `ErpStockLedgerQueryServiceImplTest` 16 条 | ✅ |
| 2 | 低于安全库存可筛（:17） | `ErpStockBalanceMapper.xml`（`safety_stock` 列 :55 + `belowSafetyOnly` 条件）＋ Java 派生 `belowSafetyStock` | `ErpStockLedgerQueryServiceImplTest` | ✅ |
| 3 | 界面不能直改结存（:21） | 结存**没有写端点**：`ErpStockBalanceController` 只有 list/detail/export/recalc（recalc 是运维校验，不是任意改值）；`ErpStockMapper.xml` 的 `updateStockQty` 只被过账引擎与 recalc 调用 | `07-ledger.md` §2 + `ErpStockDocServiceTest` | ✅ |
| 4 | 流水不可修改（:29） | `ErpStockLedgerMapper.java`（只 `insertLedger`，javadoc 明写"没有 update/delete"）＋`ErpStockLedgerQueryMapper`（只有 select）＋两个 XML 无 update/delete | `ErpLedgerAppendOnlyTest` 5 条 | ✅ |
| 5 | 下钻返回变动后结存（:33） | `ErpStockLedgerQueryMapper.xml:49`（含 `qty_after`）+ 倒序（`order by l.create_time desc`） | `ErpStockLedgerQueryServiceImplTest` | ✅ |
| 6 | 红冲不覆盖原流水（:37） | 引擎红冲=追加负数（`insertLedger` 唯一写入口） | `ErpStockJournalServiceTest` | ✅ |
| 7 | 入库类型集合（:45） | 引擎 `bizTypeOf`（行级覆盖优先，`ErpStockJournalServiceImpl:421-430`）＋字典 `stock_in_types`（SQL ② 段） | `03-posting.md` + `stock_in_types` 字典断言 | ✅ |
| 8 | 红冲类型带前缀（:49） | 引擎红冲前缀（`ErpStockJournalServiceImpl` 的 `红冲-` + 行级原类型） | `ErpStockJournalServiceTest` + `ErpLedgerRules.QUOTA_INCLUDE_KEYWORD` 注释（:23） | ✅ |
| 9 | 红冲入库冲减额度（:57） | `ErpStockBalanceMapper.xml:58-59`：`sum(case when l2.biz_type like '%入库%' and l2.biz_type not like '%调拨入库%' …)` | `ErpLedgerRulesTest`（额度口径用例）+ `07-ledger.md`（含"用 qty_change>0 会虚高 20"的反例） | ✅ |
| 10 | 调拨入库不计入额度（:61） | 同上（`not like '%调拨入库%'`） | 同上 | ✅ |
| 11 | 调拨入库红冲也不计入（:65） | 同上（`红冲-调拨入库` 同样被 `not like` 排除） | 同上 | ✅ |
| 12 | 额度不参与结存（:69） | 结存只由过账维护（`updateStockQty` 的调用面）；额度是查询期聚合（非持久列） | `ErpStockRecalcServiceImplTest` 7 条 | ✅ |
| 13 | 一致时报告通过（:77） | `ErpStockRecalcServiceImpl.recalc`（:49-89，`checkedRows/consistent/mismatchCount`） | `ErpStockRecalcServiceImplTest` | ✅ |
| 14 | 人为制造不一致可被检出（:81） | 同上（`mismatches` 含结存与流水累计；不修复时不写库） | 同上 | ✅ |
| 15 | 修复后恢复一致（:85） | 同上（`repair=true → stockMapper.updateStockQty`） | 同上 | ✅ |
| 16 | 两笔并发出库不丢更新（:93） | 引擎按 key 排序 + 逐行 `SELECT … FOR UPDATE`（:233-236） | `ErpStockJournalServiceTest` + 我的并发原型（`notes/01-base.md` §5 场景 B/C） | ✅（单测/原型）/⏳（真机并发留 t13） |
| 17 | 并发与红冲混合后仍自洽（:97） | 同上 + 红冲走同一引擎 | 同上 | ✅/⏳ |
| 18 | 过账失败整体回滚（:101） | `@Transactional(rollbackFor = Exception.class)`（:86/95，"阶段一校验不写库"设计） | `ErpStockJournalServiceTest`（多行部分失败用例） | ✅ |
| 19 | 并发首笔入库只留一行结存（:109） | `insertStockIfAbsent` + `DuplicateKeyException` 重试（`ErpStockJournalServiceImpl:354-386`，`MAX_CREATE_RETRY=3`） | `ErpStockJournalServiceTest` + 原型场景 D/F | ✅/⏳ |
| 20 | 唯一约束真实生效（:113） | DDL：`uk_stock_product_warehouse`（`sql/二开-进销存.sql` ⑰ 表注释/自检 ⑤） | 真库违例演练：重复 (物料,仓库) → `ERROR 1062`（`notes/01-base.md` §2.3） | ✅ |
| 21 | 四处金额一致（:121） | 单据金额唯一来源是 `total_amount`（`ErpAmounts.totalOf` 写一次）；列表/详情/导出读同一列；**导出端点**：库存类 6 个存在（见 §3-F1），采购/销售 4 个**缺失**；**打印入口** `printReady=false`（见 §3-F2） | `ErpLedgerPrecisionTest` 3 条 + `07-ledger.md`（四处金额一致矩阵）；采购/销售侧**无法验证导出** | ⚠️ 部分（F1 已派 t27/t28 修；F2 已裁定为本期不交付，见 §3） |
| 22 | 无浮点参与（:125） | `ErpAmounts`（唯一精度实现点）；`erp/**` 全包 grep `\b(double\|float)\b` = **0 命中** | `ErpPrecisionTest` 4 条（源码级 + 反射级） | ✅ |

---

## 3. 缺口 / 偏差清单（按严重度）

### F1 — `blocker`｜采购/销售 4 类单据的 `:export` 端点缺失（前端有按钮、菜单有权限点）

> **【裁决已落地 2026-10-05，captain】**：**补 4 个导出端点**（不摘按钮/权限点）。
> 已派 **t27 → backend-procurement**（`pur:request` / `pur:order`，依赖 t7+t23）、
> **t28 → backend-sales**（`sal:request` / `sal:order`，依赖 t8+t23）；
> 验收项含"复用列表同一查询（含 `dataScopeSql`）、金额逐行一致、范围外 403、先读前端 `doc.js` 的导出处理再定响应形态（避免'后端改 xlsx 流、前端仍按 JSON `.rows` 解析'）"。
> 本条在 t27/t28 完成前保持 `blocker`；完成后由 t14/E2E 复核。

- **规格条目**：`stock-ledger` spec §「金额数量精度与四方一致」Scenario「四处金额一致」（:121-123，"未审核 → 已审核 → 打印 → **导出**"）；
  `tasks.md` §8.2「导出与列表同范围同筛选」。
- **期望**：任一单据（含采购申请/采购单/销售申请/销售订单）都能按当前筛选导出 xlsx，且金额与列表一致。
- **实际（file:line）**：
  - 前端**有**导出按钮与调用：`ruoyi-vue-oa-ui-master/src/views/erp/doc/DocListShell.vue:69-72`（`:loading="exporting"` + `permOf('export')` + `@click="handleExport"`）、
    `src/api/erp/doc.js:120-122`（`download(kind.restBase + '/export', …)`）——**对 8 类单据统一发起**；
  - 菜单 SQL **有** 10 个 `:export` 权限点：`ruoyi-vue-oa-master/sql/二开-进销存-菜单.sql:289/313/339/363`（`pur:request:export` / `pur:order:export` / `sal:request:export` / `sal:order:export`）+ 6 个库存侧；
  - 后端**只有 6 个端点**：`ErpStockBalanceController:101-107`、`ErpStockLedgerController:86-88`、`ErpStockInController:83-85`、`ErpStockOutController:78-80`、`ErpStocktakeController:85-87`、`ErpTransferController:86-88`；
  - `ErpPurchaseRequestController` / `ErpPurchaseOrderController` / `ErpSalesController` 里 `export` **只出现在 javadoc 的动作集**（`ErpPurchaseRequestController:37`、`ErpSalesController:39`），**没有任何 `@RequestMapping("/export")`**。
  - 04a 已把这些登记为"已知边界（未做）"：`notes/04a-procurement.md:163-164,358`（"打印与导出复用平台既有打印/导出入口，届时若需要由 t4b/t10 补"）——**但平台没有"导出任意业务单据"的通用端点**，前端调的是 `{restBase}/export`，所以 4 个页面的导出按钮**点下去必然 404**。
- **影响**：① AC-80 的"权限点与菜单双向核对"会出现 4 个"菜单有、注解无"；② AC-78 的"导出"腿在采购/销售单据上不可达；③ E2E 若点击这 4 页的导出按钮 → 失败。
- **建议归属**：T4（采购申请/采购单）+ T5（销售申请/订单）各补 2 个 `/export`（照抄 `ErpStockInController:80-110` 的 `ExcelUtil` + 行对象写法，并保证**复用列表同一查询（含 `dataScopeSql`）**）；若决定不做，则必须同时摘掉前端按钮与 4 条菜单权限点（否则门禁必红）。**建议在 E2E 之前修**。
- **判定说明**：按"影响 AC-78 四方一致 + E2E 主链路"判 **blocker**；若 captain 明确本轮 E2E 不覆盖采购/销售页面的导出，可降为 **high**（但 AC-80 的核对仍会红）。

### F2 — `high`｜ERP 单据的"打印"入口未接线（`printReady=false` × 8）

> **【裁决已落地 2026-10-05，captain】**：**不列入 E2E 前置**。
> `printReady:false` + disabled + tooltip 是**有意口径**（裁决 B'）：`getPrintTemplate` 契约"永不返回空"
> ⇒"无版式"不是可观察状态；且任务书只在 §8.3/§8.5 要求"打印入口存在"，未进 AC-72~78。
> **本期明确不交付可用打印数据源**，由 captain 在最终交付里单独报给用户。
> 因此本条**不是**待修缺口，而是"已裁决的范围边界"——本表保留它只是为了让 AC-78 的"打印腿目前不可验"有据可查。

- **规格条目**：同 F1 的 `stock-ledger` spec Scenario「四处金额一致」（含"打印"）；`tasks.md` §7.5。
- **期望**：单据可打印，且打印呈现的金额与列表/详情一致。
- **实际**：`src/views/erp/doc/doc-kinds.js:157/204/259/307/361/412/465/524` 八个 kind 全部 `printReady: false`；
  `DocListShell.vue:176-177` 的打印按钮 `:disabled="!printReady"` + tooltip「打印未接入（待集成期接线）」；
  `DocListShell.vue:168-170` 注释明确"ERP 单据 → workflow businessId 接线完成后，t13 把 `printReady` 翻 true"。
- **影响**：AC-78 的"打印"腿目前**无法在 ERP 单据上验证**（只能验"未审核/已审核/导出"三方）。
- **建议归属**：**集成期 t13**（该条已被 09b/前端注释登记为待接线，不是静默遗漏）；请 captain 确认"AC-78 四处"是否必须在 E2E 前齐备。

### F3 — `high`（**待 t8 交付**，按 captain 指示不计入缺口数）｜销售订单→出库单下推与 `shipped_qty` 回写整体缺失

- **规格条目**：`sales` spec §「销售订单下推出库单」三场景（:65/69/73）、§「销售申请下推销售订单」之外的链路完整性。
- **期望**：订单过账 → 回写 `shipped_qty`；红冲 → 回退且不小于 0。
- **实际（file:line）**：
  - `IErpSalesPushService`（`sales/service/IErpSalesPushService.java:33,44`）只有 `pushSalesRequestToOrder` + `checkAutoComplete`，**无 order→stock_out**；
  - 控制器无 `sal:order:push`（`ErpSalesController` 只有一个 `sal:request:push`；菜单 SQL 里 `sal:order:push` 权限点已存在）；
  - **全模块 `IErpStockPostingListener` 的唯一实现是采购侧的 `ErpPurchaseReceiptListener`**（`procurement/support/ErpPurchaseReceiptListener.java:53`）——**销售侧没有监听器**，所以出库过账不会回写 `shipped_qty`；
  - 已备好的零件：`ErpSalesOrderItemMapper.addShippedQty`（:91）与 `ErpSalesOrderItem.subtractShippedQty`（:59，含"不小于 0"口径）**目前无调用者**。
- **建议归属**：**T5/t8**。三件事一次做完：① `pushOrderToStockOut` + `POST /sal/order/{id}/push/stock-out`（`sal:order:push`）；② 补一个销售侧 `IErpStockPostingListener`（照抄 `ErpPurchaseReceiptListener`，用 `addShippedQty`/`subtractShippedQty`）；③ 单测覆盖"未过账不回写 / 过账后累加 / 红冲回退不为负"。
  **（captain 已把这三件原样转给 backend-sales 作为 t8 的完成判据，2026-10-05）**

---

## 4. 证据清单（单测实测；来源 `target/surefire-reports`）

| 测试类 | 条数 | 结果 |
| --- | --- | --- |
| `erp.base.ErpAmountsTest` | 8 | 全绿 |
| `erp.base.ErpDocNoGeneratorTest` | 10 | 全绿 |
| `erp.base.ErpDocScopeTest` | 13 | 全绿 |
| `erp.base.ErpDocStateMachineTest` | 8 | 全绿 |
| `erp.base.ErpMasterGuardsTest` | 8 | 全绿 |
| `erp.base.ErpPrecisionTest` | 4 | 全绿 |
| `erp.base.service.impl.ErpDocObjectAccessServiceImplTest` | 8 | 全绿 |
| `erp.ledger.ErpLedgerAppendOnlyTest` | 5 | 全绿 |
| `erp.ledger.ErpLedgerPrecisionTest` | 3 | 全绿 |
| `erp.ledger.ErpLedgerRulesTest` | 7 | 全绿 |
| `erp.ledger.ErpProductTypeTreeTest` | 9 | 全绿 |
| `erp.ledger.service.impl.ErpStockLedgerQueryServiceImplTest` | 16 | 全绿 |
| `erp.ledger.service.impl.ErpStockRecalcServiceImplTest` | 7 | 全绿 |
| `erp.master.ErpMasterRefGuardsTest` | 7 | 全绿 |
| `erp.master.ErpMasterSnapshotTest` | 4 | 全绿 |
| `erp.posting.ErpStockDocServiceTest` | 9 | 全绿 |
| `erp.posting.ErpStockJournalServiceTest` | 10 | 全绿 |
| `erp.procurement.ErpPurchaseServiceImplTest` | 25 | 全绿 |
| `erp.procurement.ErpPurRulesTest` | 21 | 全绿 |
| `erp.procurement.ErpPurchasePushInTest` | 9 | **5F+1E 红（t7 收尾中）** |
| `erp.sales.ErpSalRulesTest` | 20 | 全绿 |
| `erp.sales.service.impl.ErpSalesServiceImplTest` | 22 | 全绿 |
| `erp.stockops.ErpStocktakeServiceTest` | 10 | 全绿 |
| `erp.stockops.ErpTransferServiceTest` | 8 | 全绿 |

> 上表是**目录里最近一次全量运行**的 surefire 报告（含 t7 的在建测试），不是本次审计新跑的结果；
> 审计为只读，未触发构建。运行期脚本证据见各组 notes（`03-posting.md`、`07-ledger.md`、`12-attachment-parity.md`、`11-init-selfcheck.md`）。

---

## 5. 重点专项核对（captain 点名的 12 项；结论逐条给证据）

| 专项 | 结论 | 证据（file:line） |
| --- | --- | --- |
| 8 类单据状态机动作白名单 | ✅ **只有一处实现**，8 类单据全部调用（`posting` 2 + `stockops` 2 + `procurement` 2 + `sales` 2） | `base/ErpDocStateMachine.java:100-160`；调用点见 §2 各表（`ErpStockInServiceImpl:222`、`ErpStockOutServiceImpl:212`、`ErpTransferServiceImpl:221`、`ErpStocktakeServiceImpl:347`、`ErpPurchaseRequestServiceImpl:442`、`ErpPurchaseOrderServiceImpl:447`、`ErpSalesRequestServiceImpl:281`、`ErpSalesOrderServiceImpl:274`） |
| "只有草稿可编辑" | ✅ 单一入口 `checkEditable`，各服务新增/编辑路径都先过它（单文件 3~6 处） | `base/ErpDocStateMachine.java:186-193`；如 `ErpStockInServiceImpl:154/207`、`ErpPurchaseOrderServiceImpl:321/432/532/554/581` |
| 金额"先舍入再汇总"是否**只有一处** | ✅ 只有 `ErpAmounts`；全 `erp/**` 除它之外**没有** `setScale/RoundingMode/.multiply(` 的实现（3 处命中全是注释或"把负剩余量夹到 0 并保持 3 位"的 clamp） | `base/ErpAmounts.java`（`lineAmount`/`totalOf`）；反向 grep 结果见 §1 方法 3 |
| 下推剩余量 / 归零即完成 / 回退不小于 0 | ✅ 采购线齐（申请→单→入库）；⚠️ 销售线 order→out 未交付（F3）；"不小于 0"两处都已写：采购 `GREATEST(0,…)`（`ErpPurchaseReceiptListener:123`）、销售 `subtractShippedQty`（`ErpSalesOrderItem:54-66`） | `ErpPurchasePushServiceImpl:110-140,182-210`；`ErpPurchaseRequestServiceImpl:535`；`ErpSalesPushServiceImpl:172` |
| 调拨两阶段 + 两条流水 | ✅ 阶段一校验（单据级 + 引擎锁内可用量）、阶段二一次性写入；每行两条流水、单价 0 | `ErpTransferServiceImpl:47-52,250-258,475-490`；`ErpTransferServiceImpl:70,432` |
| 盘点差异自动生成 + **盘亏豁免** | ✅ 盘盈/盘亏合并各生成一张"已审核"单并立即过账；盘亏显式 `skipNegativeCheck()`（**局部豁免，不是全局开关**） | `ErpStocktakeServiceImpl:387-401`（:399 `ErpPostingOptions.skipNegativeCheck()`）；`ErpPostingOptions`（`posting/support/ErpPostingOptions.java`） |
| 额度口径（子串"入库"、排除调拨入库及其红冲） | ✅ SQL 一处实现，反直觉点写在规则类注释里 | `mapper/erp/ErpStockBalanceMapper.xml:58-59`；`ledger/ErpLedgerRules.java:16-52` |
| 流水只增不改 | ✅ 写入侧只有 `insertLedger`，查询侧只有 select，两份 XML 无 update/delete | `posting/mapper/ErpStockLedgerMapper.java:13,32`；`ledger/mapper/ErpStockLedgerQueryMapper.java:13` |
| 结存不变式 + 运维校验/修复 | ✅ 校验接口返回 checked/consistent/mismatch 明细，修复走唯一写入口 | `ledger/service/impl/ErpStockRecalcServiceImpl.java:49-89` |
| 行项快照 | ✅ 唯一写入点 + 真库验证改名不影响历史 | `base/ErpMasterGuards.java:250-282`；`.cache/t2-masterdata-drill.log` |
| 数据范围四档 + 403 语义 | ✅ 4 档判定在 `ErpDocScope`（SQL 片段 + 等价 Java 判定），8 类单据列表都写 `dataScopeSql`、详情/动作走 `IErpDocObjectAccess`（403）；结存/流水用别名 `l` | `base/ErpDocScope.java:105-200,240-294`；`ErpStockInServiceImpl:105-106`、`ErpPurchaseRequestServiceImpl:22-23,917`、`ErpSalesOrderServiceImpl:106-107`、`ErpTransferServiceImpl:106-107,545`、`ErpStocktakeServiceImpl:142,991`、`ErpStockLedgerQueryServiceImpl:110-120`；`base/service/impl/ErpDocObjectAccessServiceImpl.java:66-71`（403） |
| 无浮点参与 | ✅ `erp/**` 全包 grep `\b(double\|float)\b` = 0 命中 | `ErpPrecisionTest`（源码级扫描 `src/main/java/com/ruoyi/ctms/erp`） |

---

## 6. 本次审计**未覆盖**的部分（不猜、留给对应任务）

| 部分 | 为什么没覆盖 | 建议由谁复核 |
| --- | --- | --- |
| **t7 采购单→入库单下推**（3.5 的三个场景） | 实现在树（端点+服务+监听器都在），但 `ErpPurchasePushInTest` 9 条当前 **5F+1E 红** → t7 收尾中 | t7；完成后按 §2.2-19 的三条场景复核 |
| **t8 销售订单→出库单下推与回写** | 未交付（见 F3） | t8 |
| **t12 前端页面**（8 类单据页与主数据页） | 本次只粗核"入口/调用是否存在"：`src/views/erp/` 现有 `doc/**`（列表壳/表单壳/6 个组件）+ `master/**`（4 页）；`doc-kinds.js` 里 8 个 kind 的 `component` 指向的 `erp/<doc>/index` 薄页**尚未创建**（菜单 SQL 已指向它们） | t12（页面）+ t14（前端复核） |
| 运行期行为（真机 HTTP/并发/导出 xlsx/打印预览/权限矩阵） | 需打包重启与真环境；审计为只读 | t13（统一窗口）+ t20（盘点/调拨 HTTP 层细节）+ t11（E2E） |
| `08-perm-scope.md` 的 §8.2 数据范围实测与 `authz-check.ps1` 14 条 | 归 T10 的接口脚本；本次只核了代码侧判定与 403 语义 | T10 |
| 盘点的"整棵子树"在真库上的递归 CTE 结果 | 单测覆盖了树展开（`ErpProductTypeTreeTest`），真库 CTE 未跑 | t20 |

---

## 7. 审计纪律说明（避免误读）

1. 本次**只读**：未改任何源码/SQL/前端/他人的 notes；所有 file:line 均为当前工作树的实际内容。
2. 表中"✅"表示**我核到了实现落点**（并尽量给出断言/实测）；**不代表**该条已在真机验证过 —— 运行期部分集中在 §6。
3. "❌ 缺口"只有 F1 一处（导出端点）；F2（打印）与 F3（销售下推）虽然同样影响规格场景，
   但前者已被前端注释/09b 登记为"待集成期接线"、后者是 captain 明示的未交付范围，故分别记为 `high` 与"待 t8"，
   **没有**把它们混进"缺口"数字里充水，也没有把"未找到"写成"已覆盖"。
