# 15 · B4 进销存 E2E 用例编写（t24 = t14 前置）

> **本任务只写用例，不要求跑通**：后端要等 t20 的规范打包才有完整 B4 接口，页面细节要等 t12 收尾。
> 交付的是"能直接跑、失败信息能指路"的用例集 + 夹具/清理基建 + 一份断点清单。
> 边界：**只改 `ruoyi-vue-oa-ui-master/tests/e2e/**`**；没动应用源码、没动 e2e 的 `package.json`、
> 没动别人 notes、**没动既有 `specs/contract-approval.spec.js`**（t14 要用它做回归）。

---

## 1. 交付物

| 类型 | 文件 | 说明 |
| --- | --- | --- |
| 新增 | `tests/e2e/helpers/api.js` | HTTP 层：从 storageState 取 token（cookie `Admin-Token`）、`okData`/`okList`/`bizError` 三种断言、请求轨迹留痕 |
| 新增 | `tests/e2e/helpers/erp-fixtures.js` | 夹具（单位/商品类型/物料/仓库/供应商/客户，**按 code 幂等复用**）+ 8 类单据动作封装 + **清理与残差报告** |
| 新增 | `tests/e2e/specs/erp-purchase-chain.spec.js` | 采购主链路 + 超量下推 + 金额舍入口径 + 守卫 + 作废语义（5 条） |
| 新增 | `tests/e2e/specs/erp-sales-chain.spec.js` | 销售链路（备货→申请→订单→出库过账→结存减少）+ 负库存拦截 + 订单→出库下推（依赖 t8）（4 条） |
| 新增 | `tests/e2e/specs/erp-stockops.spec.js` | 调拨（双向/单价0/额度不变/幂等/红冲）+ 调拨守卫 + 盘点（生成→实盘→盘盈过账→反审核级联）+ 盘点守卫 + 盘亏豁免（5 条） |
| 新增 | `tests/e2e/specs/erp-posting-concurrency.spec.js` | 并发过账（无更新丢失 + 不变式）+ recalc 只读 + 审核幂等（3 条） |
| 新增 | `tests/e2e/specs/erp-ui-skeleton.spec.js` | UI 骨架：8 类单据列表/表单页各 1 条 + 库存账两页 + 原生点击行（19 条） |

合计新增 **36 条**用例（`--list` 总数 39 = 既有 setup 2 + 既有 contract-approval 1 + 本轮 36）。

---

## 2. 断言三层（任务要求的落法）

| 层 | 落点 | 例 |
| --- | --- | --- |
| ① UI 可观察 | `erp-ui-skeleton.spec.js` | 页面可达（不被路由到 /404）、`el-table` 表头含 `doc-kinds.js` 声明的列名、「新增<单据名>」入口、表单页表头字段标签、**原生 click 行/复选框** |
| ② 接口/DB 终态 | 三个 HTTP spec | 单据 `status`/`posted`/`generatedInNo`；**`结存 == Σ流水数量变动`**（逐条用例都算一次）；`POST /stk/stock/recalc` 的 `inconsistentCount == 0`（该数字**在 SQL 里**算出，等价 DB 终态不变式）；**全程不碰 `ACT_*`**（不进流程引擎） |
| ③ 关键口径 | 采购/调拨/流水用例 | 金额**先逐行舍入再汇总**（3×1×0.125 → `0.39`，显式否定 `0.38`）；货品总额度只认**含「入库」子串且排除「调拨入库」**（调拨及其红冲都不改额度；采购入库红冲按 `-20.00` 抵扣 ⇒ 回到 `0.00`）；流水"只增不改"（红冲是追加相反数） |

---

## 3. 用例清单（逐条：覆盖什么 + 口径出处）

> 口径出处都在别人已交付的 notes 里，**直接复用、没有自己另立一套**：
> `03-posting.md §2/§5/§8`、`04a-procurement.md §3/§4/§5.1`、`04b-procurement-push-in.md §3/§6/§8`、
> `05a-sales.md §4/§5`、`06-stockops.md §2~§5/§7/§8`、`07-ledger.md §2~§7`。

### 3.1 `erp-purchase-chain.spec.js`

| # | 用例 | 覆盖 | 关键断言 |
| --- | --- | --- | --- |
| P1 | 申请→提交→审核→下推采购单→审核→下推入库单→过账→结存/流水/额度一致 | tasks 3.1/3.2/3.3/3.5/3.6 + AC-73/AC-78 | 单号形状 `^[A-Z]{2}\d{12}$`；`totalAmount=4.75`；下推回 `sourceDocNo`+`autoCompleted`；**未过账不回写已入库量**；过账后 `posted='1'`、结存 3、`displayName='<类型>-<物料>'`、`goodsQuota=4.50`、流水 `bizType=采购入库 qtyChange=+3 qtyAfter=3`、`receivedQty=3`；**结存==Σ流水** + `recalc.inconsistentCount=0`；反审核后结存回 0、额度回 `0.00`、流水 2 条含 `红冲-`、`receivedQty` 回退不为负 |
| P2 | 下推超量被拒 | 04a §3 ③/§4 | 首次推 60 后剩余 40，再推 50 ⇒ 失败文案带 `剩余 40`；**已下推量仍 60** |
| P3 | 金额先舍入再汇总 | 07-ledger §6 / AC-78 | 3 行 `1×0.125` ⇒ 各行 `amount=0.13`、合计 `0.39` |
| P4 | 守卫：空行项/数量 0/精度超限 | 04a §4 | `没有行项`、`数量必须大于 0`、`0 位小数` |
| P5 | 作废语义 + `includeVoided` | 03-posting §8.6 #22 | 默认列表查不到、`includeVoided=1` 能查到 |

### 3.2 `erp-sales-chain.spec.js`

| # | 用例 | 覆盖 | 关键断言 |
| --- | --- | --- | --- |
| S1 | 备货（采购入库 20） | 03-posting §8.1 | 结存 20 |
| S2 | 申请→审核→下推订单→审核→出库过账→结存减少 | tasks 4.1/4.2 + 03-posting §8.3 | 下推返回 `orderId`、`requestCompleted=true`、订单带过 `shipWarehouseId`；出库过账后结存 `-5`；流水 `bizType=销售出库 qtyChange=-5`；**结存==Σ流水**；**出库不改额度**（额度仍 = 备货 20×2） |
| S3 | 出库负库存被拦 | 03-posting §8.3 #9 | 失败文案含 `可用量`；状态仍 `submitted`、`posted='0'`、结存不变 |
| S4 | 订单→出库单下推（**依赖 t8**） | tasks 4.3 | 端点未落地时**明确抛错并解释**（不 skip）；落地后断言草稿出库单带过发货仓库+来源单号、过账回写 `shippedQty` |

### 3.3 `erp-stockops.spec.js`

| # | 用例 | 覆盖 | 关键断言 |
| --- | --- | --- | --- |
| K1 | 调拨全流程 | tasks 6.1/6.2 + 06-stockops §2 | 单号 `^DB\d{12}$`；**行项 `warehouseId` 被清空**、单价/金额恒 0；审核后两仓双向变化；**同一 doc_no 两条流水**（`-4` 调拨出库 / `+4` 调拨入库，`unitPrice=0`）；**额度不变**（调出仓不变、调入仓为 0）；重复审核幂等；反审核后 4 条流水（含 `红冲-调拨出库`）、结存回位 |
| K2 | 调拨守卫 | 06-stockops §8 #2/#9 | 同仓 ⇒ `不能相同`；两行（3/1003）时第 2 行不足 ⇒ 失败、**第 1 行也没写**、无本单流水 |
| K3 | 盘点全盘→盘盈 | tasks 6.3/6.4 + 06-stockops §3/§4 | 单号 `^ST\d{12}$`；行项 `bookQty` 固化、`actualQty` 默认=账面、`diffQty=0`；录入实盘（请求里塞 `bookQty` 被忽略）后 `diffQty=+2`；审核后 `posted='1'`、`generatedInNo` 非空、结存 +2、生成单 `inType=盘盈入库`、`totalAmount=0`、`sourceDocType=stock_take`；反审核级联后结存回位、出现 `红冲-` 流水；**重复反审核幂等不报错** |
| K4 | 盘点守卫 | 06-stockops §8 #12/#14 | `抽盘必须指定物料或商品类型`；`实盘数量不得为负数` |
| K5 | 盘亏豁免负库存 | 06-stockops §4.1 + §8 #16 | 账面固化 2 → 用出库单把结存清零 → 实盘 0 ⇒ 审核后 `generatedOutNo` 非空、**结存 -2**（`stock_allow_negative` 仍为 false，证明是显式传参豁免） |

### 3.4 `erp-posting-concurrency.spec.js`

| # | 用例 | 覆盖 | 关键断言 |
| --- | --- | --- | --- |
| C1 | 并发过账 | tasks 5.5 / AC-74；03-posting §5/§8.5 | 备货 100 → 两张 30 的出库单 `Promise.all` 同时审核 ⇒ **两次都 200**、结存 **40**（不是 70）、`结存 == Σ流水`、`recalc.inconsistentCount=0`；反审核一张 ⇒ 70 且仍等于 Σ流水 |
| C2 | recalc 只读 | 07-ledger §5/§7 #13 | `repair=false`、`repairedCount=0`、`inconsistentCount=0`（SQL 算）、结存未被改动 |
| C3 | 审核幂等 | 03-posting §2.3 | 重复审核：结存不变、流水条数不变 |

### 3.5 `erp-ui-skeleton.spec.js`

| 组 | 覆盖 | 断言方式 |
| --- | --- | --- |
| 8 类单据 × (列表页 + 表单页) 共 16 条 | 菜单 `sys_menu` 的 8 条列表 + 8 条表单路径 | **`page.goto`**（D-6：点侧边栏兄弟页不刷新）+ URL 不被改到 `/404` + `.el-table` 表头含 `doc-kinds.js` 的前 4 个列名 + 「新增<单据名>」按钮 + 表单页前 3 个表头字段标签 |
| 库存明细 / 库存流水 2 条 | `erp/stock-balance`、`erp/stock-ledger` | 表头列：`商品类型-物料名称 / 结存数量 / 货品总额度`；`业务类型 / 数量变动 / 变动后结存 / 单据号`（列名真源：`stock-balance/index.vue`、`stock-ledger/components/LedgerTable.vue`） |
| 原生点击 1 条 | el-table 交互 | 经接口建一张草稿单 → 页面按单号过滤 → **Playwright 原生 click** 该行（有选择列点 `.el-checkbox` 并断言 `.is-checked`，无选择列点「单号」链接进详情）；**不用浏览器桥**（桥点不动 el-table，DEV-ENV §6.53） |

---

## 4. 端点/字段的真源（逐个 grep 过，没有猜）

| 类别 | 真源 | 采到的关键事实 |
| --- | --- | --- |
| 8 类单据 base | `erp/**/controller/*.java` 的 `@RequestMapping` | `/erp/pur/request`、`/erp/pur/order`、`/sal/request`、`/sal/order`、`/stk/in-order`、`/stk/out-order`、`/stk/transfer`、`/stk/take` |
| 动作形态（**三族不同**） | 同上 + `doc-kinds.js#actionShape` | 采购/销售：`PUT {base}/{id}/{action}`（审核带 `?action=approve\|reject`）；入库/出库：`POST {base}/{action}/{id}`；调拨/盘点：`POST {base}/{id}/{action}`（同时注册了另一形态，属冗余路由 D-7） |
| 下推 | `ErpPurchaseRequestController:385`、`ErpPurchaseOrderController:363-371`、`ErpSalesController:269-274` | 采购申请→采购单 `POST {id}/push/purchase-order?supplierId=&remark=`（body 裸数组 `[{srcItemId,qty}]`）；采购单→入库单 `POST {id}/push/stock-in?warehouseId=&remark=`；销售申请→订单 `POST /sal/request/{id}/push`（body `ErpSalesPushRequest`） |
| 单据/行项字段 | `erp/base/domain/{ErpDocHeader,ErpDocItem}.java` + 各 `domain/*.java` | 行项公共列 `productId/qty/unitPrice/amount/srcItemId/warehouseId`；追加列 `orderedQty`（采购/销售申请）、`receivedQty`（采购单）、`shippedQty`（销售订单）、盘点 `bookQty/actualQty/diffQty/diffReason` |
| 结存/流水/重算 | `ErpStockBalanceController`、`ErpStockLedgerController` | `GET /stk/stock/list`（`keyword/warehouseId/productTypeId/beginQty/endQty/belowSafetyOnly/hideZero`）、`GET /stk/ledger/list`（`productId+warehouseId` 即下钻、`bizTypeFilter/docTypeFilter/beginTime/endTime`）、`POST /stk/stock/recalc?repair=` → `{checkedRows,consistent,inconsistentCount,repair,repairedCount,mismatches[]}` |
| 主数据夹具 | `CtmsProductMasterController`、`CtmsPartnerController` | `POST /ctms/uom|product-type|product|warehouse`、`GET .../list`；`POST /ctms/partner/{customer,supplier}`。**供应商简称必填**（`CtmsPartnerServiceImpl:212`），客户只要 编码+名称 |
| 字典值 | `sys_dict_data` | `stock_in_types`=采购入库/退货入库/其他入库/盘盈入库；`stock_out_types`=销售出库/领用出库/其他出库/盘亏出库；`stock_take_types`=full/partial |
| 页面路径 | `sys_menu`（component like `erp/%`） | 列表 `/erp/<kind>`、表单 `/erp/<kind>/form`、`/erp/stock-balance`、`/erp/stock-ledger` |

---

## 5. 数据清理设计（硬要求）+ 实测输出

**原则**：夹具全部带 ASCII 前缀 `E2E4B/E2E4S/E2E4K/E2E4C/E2E4U`，**记账方式由被测系统自己完成**：

| 对象 | 清理动作 | 依据 |
| --- | --- | --- |
| 草稿单据 | `DELETE {base}/{id}` | 状态机：删除只允许 `draft` |
| 已提交/已审核单据 | 先 `unapprove`（红冲）→ 再 `void`（reason 走 query，调拨/盘点额外容忍 body） | `void` 只允许 `draft|submitted`，所以必须先反审核；逆序清理天然满足"先清下游再清上游"（采购单→入库单、申请单→采购单） |
| 主数据（物料/仓库/单位/类型/客户/供应商） | 尝试 `DELETE .../{id}`；**被引用守卫拒绝的如实记录并停用**（`enableFlag=0`） | `ErpMasterRefGuards`：结存/流水/单据引用都会拒删；流水按设计**只增不改**（07-ledger §4），所以"账留下"是预期行为，不能假装清干净 |
| 复核 | 按 tag 查 8 类单据列表，断言**活跃单据 = 0**（作废的默认不返回） | `ErpDocScope`/列表默认过滤 `voided` |

**实测（单条只读探针的真实输出，跑完复核零残留）**：

```
[E2E4C] 夹具就绪 tag=E2E4CMUVESL8T 物料=E2E4CMUVESL8T-P3 仓库=E2E4CMUVESL8T-WA

[E2E4B 清理报告] tag=E2E4CMUVESL8T
  删除：9 条；作废：0 条
  仍活跃的单据：0
  未删除的主数据（引用守卫拒绝，已停用）：0
```

DB 复核（探针跑完同一时刻）：`t_ctms_uom/t_ctms_product_type/t_ctms_product/t_ctms_warehouse` 中 `code LIKE 'E2E4%'` = **0**；
`t_ctms_stock`/`t_ctms_stock_ledger` 未被本探针写入；单据表按备注/用途查 `E2E4%` = 0。

---

## 6. 自检证据

```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master\tests\e2e
npx playwright test --list
```

```
  [setup] › auth.setup.js:13:3 › 登录并保存登录态：superAdmin
  [setup] › auth.setup.js:13:3 › 登录并保存登录态：zhangwei
  [e2e] › specs\contract-approval.spec.js:129:3 › 合同审批 · 端到端主链路 › 登录 → 发起 → 填表 → 条件分支 → 逐级审批 → 办结
  [e2e] › specs\erp-posting-concurrency.spec.js:… （3 条）
  [e2e] › specs\erp-purchase-chain.spec.js:… （5 条）
  [e2e] › specs\erp-sales-chain.spec.js:… （4 条）
  [e2e] › specs\erp-stockops.spec.js:… （5 条）
  [e2e] › specs\erp-ui-skeleton.spec.js:… （19 条）
Total: 39 tests in 7 files
```

**另外跑了一条"只读探针"验证基建本身可用**（不是全链路，符合任务"后端未打包前不要跑全链路"的要求）：

```powershell
npx playwright test --project=e2e --grep "recalc 默认只读" --reporter=list
# → 3 passed（setup 2 + 本用例 1），并输出上面的清理报告
```

它证明：token 从 storageState 取得到、`okData/okList` 的 code/rows 口径与真实响应一致、
夹具幂等创建与清理可用、`POST /stk/stock/recalc` 在当前构建里**已可用**（`repair=false`、`repairedCount=0`、`inconsistentCount=0`）。

---

## 7. 交给 t14 的断点清单（不掩盖、不 skip）

| # | 断点 | 现状 | t14 要做什么 |
| --- | --- | --- | --- |
| 1 | **销售订单 → 出库单 下推未落地**（tasks 4.3 / t8） | `ErpSalesController` 只有 `sal:request:push`；前端 `doc-rules.js:154` 标 `pending:true` | t8 落地后按实际签名收紧 `S4`（现按契约预期 `/sal/order/{id}/push` 写死断言：**未落地时必须红**） |
| 2 | **前端漏更新采购单下推**（真发现，不属本任务修） | 后端 `ErpPurchaseOrderController:363-371` 已实现 `POST /erp/pur/order/{id}/push/stock-in`，但 `doc-rules.js:129` 仍 `pending:true` ⇒ `canPush()` 不通过 ⇒ **界面不显示「下推入库单」按钮**（后端就绪、前端没跟上） | 转 t12/t14 确认并改前端（本任务只报告，不改应用源码） |
| 3 | UI 细节 | 本轮的 UI 用例只到"可达 + 表头列 + 新增入口 + 原生点击行" | t12 收尾后补：动作按钮的权限可见性、下推弹窗、库存明细下钻抽屉、导出、附件面板 |
| 4 | 并发用例的覆盖面 | 只证明"两次并发都成功且不变式成立"；不构造"死锁/唯一键重试"的极端时序 | 需要时再加 `workers>1` 的外部脚本并发（本套件 `workers:1` 是刻意的） |
| 5 | UI 用例的前置 | 需要前端 dev(80) + setup 登录态；`.auth/` 已 gitignore | 跑 UI 前先 `npx playwright test --project=setup` |
| 6 | 数据依赖 | HTTP 用例自建夹具，**不依赖库内既有主数据**；但库里若已被别的脚本写脏结存，`recalc` 的 `inconsistentCount` 可能是**别人**造成的 | 若 C2 红，先看 `mismatches[]` 里的 (物料, 仓库) 是否属本套夹具 |

---

## 8. 复现命令

```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master\tests\e2e
npx playwright test --list                      # 只列用例（不跑）
npx playwright test --project=setup             # 刷新 .auth（登录页 + Redis 取验证码）
npx playwright test --project=e2e --grep "采购"  # 单文件/单用例
npx playwright test                             # 全量（含既有合同审批回归）
# 只读探针（无副作用，验证基建）
npx playwright test --project=e2e --grep "recalc 默认只读"
```

> ⚠ 全量跑会真实写单据（这是 E2E 的本义）；清理由各 spec 的 `afterAll` 负责，
> 报告会打印"删除/作废/仍活跃/未删除主数据"四项，**活跃单据必须为 0** 才算过。

---

## 9. 与其它任务的关系

- **t12（前端）**：本轮的 UI 用例就是给它留的验收位；上面 §7 的第 2、3 条需要它接手。
- **t13（集成）**：HTTP 层的 8 类单据断言可直接当接口验收的骨架（也可搬进 `tools/*.ps1`）。
- **t14（E2E 验收）**：跑 + 修 + 报告；本任务的用例集与清理基建已完成，t14 不必从零写。
- **t20（最终打包）**：t14 必须跑在 t20 的产物上（否则会看到一片"未部署"）。
