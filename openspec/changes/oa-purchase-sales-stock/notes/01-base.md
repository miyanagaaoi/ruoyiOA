# B4 §1 S0 冻结说明与交付记录（`01-base.md`）

> 变更集：`oa-purchase-sales-stock`（B4）｜任务：`tasks.md` §1.1~§1.6（+ captain 追加的"8 类单据编号配置"）
> 交付人：backend-base（T1 基础组）｜交付时间：**2026-10-05**｜attempt_id：`a07a9de7-428e-42ff-a564-c51e4afd4c84`
> 本文件是 S0 的**冻结件**：状态机矩阵 / 18 张表与字段来源 / 唯一约束与索引 / 精度口径 / 并发原型结论。
> 下游组（T3 过账 / T4 采购 / T5 销售 / T6 调拨盘点 / T7 库存账 / T9 前端）以本文件 + 代码注释为准。

---

## 0. 一句话结论

18 张表（8 表头 + 8 行项 + 结存 + 流水）+ 4 个字典类型 + 1 个系统参数 + 8 条单据编号配置**已在 `rad_oa` 落库**，
`sql/二开-进销存.sql` 连续执行两次 30 项自检全部等于期望值；公共层 `com.ruoyi.ctms.erp.base`
（状态机 / 精度唯一实现点 / 主数据守卫 / 四档数据范围 / 取号入口 / 单据对象访问校验）已交付并带 **77 条单测全绿**；
附件对象登记按 B3 的"接入必改三处"**三处已全部完成**（最终 **9 项** = `contract` + 8 类单据；
计数以 `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` 为准）；**上游基线 `table.sql` / `data.sql` 一字未动**。

---

## 1. 交付物与改动清单

### 1.1 SQL（`ruoyi-vue-oa-master/sql/`）

| 文件 | 改动 | 内容 |
| --- | --- | --- |
| `二开-进销存.sql` | **新增** | ①18 张表（含 43 条外键、唯一键与索引）②4 个字典类型 + 20 条字典数据 + 1 个系统参数 ③8 条单据编号配置 + 24 条规则 ④30 项自检 |
| `初始化-全部.sql` | 修改 | ⑥ 段占位注释 → 真实 `source 二开-进销存.sql`；自检段 ⑨ 由"是否仍为占位"改为 B4 齐备性（⑨~⑭）；**B3 段与 ①~⑧ 一字未改** |

⚠ 禁止项核对：`table.sql` / `data.sql` **未出现在 `git status`**（证据见 §7.1）；`tasks.md` 未改。

### 1.2 公共层（`ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/base/`）

| 文件 | 作用（唯一实现点） |
| --- | --- |
| `ErpDocStatus.java` | 5 个状态常量 + 中文名 + "只有草稿可编辑" |
| `ErpDocAction.java` | 6 个动作（含动作码、原因是否必填、必填文案） |
| `ErpDocStateMachine.java` | **逐动作白名单状态机**（唯一流转判定点）+ 库存类不提供 `complete` |
| `ErpDocType.java` | 8 类单据：单据码 / 中文名 / 表名 / 行项表名 / 是否库存类（**不含权限点**，权限点真源是 `sys_menu`） |
| `ErpAmounts.java` | **金额与数量的唯一精度实现点**（`lineAmount` 先 HALF_UP 到 2 位；`totalOf` = 各行金额之和；数量按单位小数位校验） |
| `ErpMasterGuards.java` | 行项守卫：停用物料/仓库/单位不可用于新行项（文案带行号）+ 快照写入 + 行级仓库回落表头 |
| `ErpDocScope.java` | **四档数据范围**（本人/本部门/本部门及下级/全部；多角色并集；归属部门创建时快照）+ 服务端上下文取值 |
| `ErpStockParams.java` | 系统参数 `stock_allow_negative` 的键名与取值解析（默认关闭） |
| `ErpDocNoGenerator.java` | **8 类单据唯一取号入口**（`nextDocNo` / `nextDocNo(type, docDate)` / `previewDocNo`）+ `ErpDocType→confId` 常量映射 |
| `domain/ErpDocHeader.java` | 表头 28 个公共字段（`protected` + getter/setter）+ `applyCreateSnapshot` |
| `domain/ErpDocItem.java` | 行项 22 个公共字段 + 快照写入 + `recalcAmount` |
| `service/IErpDocObjectAccess.java` | 单据对象"存在性 + 数据范围"校验接口（附件接入点 ②） |
| `service/impl/ErpDocObjectAccessServiceImpl.java` | 8 类单据对象的分派实现（`ErpDocLookupMapper` + `ErpDocScope`，403 语义） |
| `service/impl/ErpMasterLookupImpl.java` | 停用物料/仓库/单位的只读查询实现（复用 B3 的三个档案 Mapper） |
| `mapper/ErpDocLookupMapper.java` + `resources/mapper/erp/ErpDocLookupMapper.xml` | 按单据类型**分支**取表头判定字段（8 个显式 `when`，无 `${}`）+ 部门祖先链 |

### 1.3 附件对象登记（B3 的"接入必改三处"）

> 下表是 **t1 交付时的动作**（当时登记 7 项）；**最终状态是 9 项**（t19 补齐两个申请单），
> 计数与逐项清单以 `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` 为准。

| 处 | 文件 | 改动 |
| --- | --- | --- |
| ① 常量 + 注册清单 | `support/CtmsAttachmentObjectTypes.java` | t1：新增 6 个单据对象常量；`REGISTERED` = 合同 + 6 类单据（7 项）；`plannedB4` **清空**；新增 `docObjectTypes()`。t19：再补 `PURCHASE_REQUEST`/`SALES_REQUEST` → **9 项**（`docObjectTypes()` 8 项，`plannedB4` 仍为空） |
| ② 服务层存在性校验 | `service/impl/CtmsAttachmentServiceImpl.java` | `requireObjectAccess` 增加 `IErpDocObjectAccess` 分派；**数据范围片段只对合同对象加**（单据附件 `contract_id` 为 NULL，套合同片段会把单据附件全部过滤掉 —— 本轮实测发现并修掉） |
| ② 接口面 | `controller/CtmsAttachmentController.java` | `/ctms/attachment/object-types` 新增 `docObjectTypes` 键（`registered`/`plannedB4`/`maxSizeMb`/`extensions` 全部保持不变） |
| ③ 文档 | 本文件 §3.6 + `openspec/changes/oa-contract-ledger/notes/attachment-notes.md`（§2 清单与本表同源，captain 已确认口径） | |

### 1.4 单测（`ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/base/`）

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `ErpDocStateMachineTest.java` | 8 | **5 状态 × 6 动作 × (库存类/非库存类) = 60 格完整矩阵**（期望值独立重抄，非法格必须抛异常、合法格必须返回目标状态），提交需行项、三动作原因必填、库存类不提供 complete、非法状态、只有草稿可编辑 |
| `ErpAmountsTest.java` | 8 | **三行 0.125 → 每行 0.13 / 合计 0.39**，并算出错误口径 0.38 做对照；小数位冻结；数量按单位小数位；盘点差异；空值 |
| `ErpPrecisionTest.java` | 4 | **源码级 + 反射级断言**：B4 的 `erp` 包内不得出现浮点类型（含注释）；金额/数量字段必须是 `BigDecimal`；扫描器自带判别力自证 |
| `ErpDocScopeTest.java` | 13 | 四档的 SQL 片段与 Java 判定**逐档**断言、多角色并集、空档位 = 不可见、非法字面量不进片段、归属部门创建时快照且编辑不重算 |
| `ErpMasterGuardsTest.java` | 8 | 停用物料/单位/仓库被拒（行号 + 名称文案）、快照写入、数量超单位小数位被拒、行级仓库回落表头 |
| `ErpDocNoGeneratorTest.java` | 10 | 8 个 confId/前缀冻结、编号形态 `PR202610000001`、**连续两次取号不同**、**跨月回到 000001**、各单据各自计数、预览不占号、失败文案带单据与配置、**平台渲染器交叉验证（桶键跨月必变）** |
| `ErpDocObjectAccessServiceImplTest.java` | 8 | 全部范围放行、不存在按单据名报错、未注册类型被拒、**范围外 403**（用子类钩子命中该分支）、行创建人/部门传入判定、对象标识为空被拒 |
| `CtmsAttachmentServiceImplTest.java`（改 1 条断言） | 18 | 见 §6 |

合计 **77 条**（59 条属 B4 公共层 + 18 条附件回归）。

---

## 2. 验证证据（真实输出；命令可复跑）

### 2.1 L1 单测

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 -LockName build `
  -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"
```

**实测（2026-10-05 收尾轮，最终一次）：`Tests run: 383, Failures: 5, Errors: 4`，BUILD FAILURE。**

| 归属 | 结果 |
| --- | --- |
| **B3 基线 217 条** | **全绿**（contract 62 / migration 27 / partner 17 / productmaster 25 / attachment 18 / ContractDataScope 14 / NumberRules 7 / ContractRules 22 / MigrationRules 6 / PartnerRules 10 / ProductMasterRules 9 = 217） |
| **B4 公共层 77 条（本任务）** | **全绿**（ErpDocScope 13 / ErpDocStateMachine 8 / ErpMasterGuards 8 / ErpPrecision 4 / ErpDocObjectAccess 8 / ErpAmounts 8 / ErpDocNoGenerator 10 / CtmsAttachmentServiceImpl 18） |
| T3 的 12 条红旗 | **已转绿**（`erp.posting.*` 两套测试本轮通过） |
| T4/T5 的在建测试 | 9 条红（`erp.procurement.ErpPurchaseServiceImplTest` 1F+2E、`ErpPurRulesTest` 1F、`erp.procurement.TmpProbeTest` 1E（探针临时文件）、`erp.sales.service.impl.ErpSalesServiceImplTest` 3F+1E）——**不是本任务的改动**，明细与归因见 §7.2 |

**补充证据（T5 的测试文件一度让 `test-compile` 失败，为拿到"我自己这 77 条全绿"的真实输出，做了一次隔离编译）**：

```text
# 用 JDK 11 + maven 解析出的 test classpath 编译"全部 148 个 main 源文件" → exit 0（166 个 class）
# 再编译本任务 7 个新测试类 + 改过的附件测试类 → exit 0
# JUnitCore 直接跑：
D:\Program Files\Java\jdk-11\bin\java.exe -Dfile.encoding=UTF-8 -cp "<t1-test>;<t1-main>;<deps>" `
  org.junit.runner.JUnitCore com.ruoyi.ctms.erp.base.ErpDocStateMachineTest ... com.ruoyi.ctms.service.impl.CtmsAttachmentServiceImplTest
==> OK (77 tests)
```

（隔离产物在 `.cache/t1-main`、`.cache/t1-test`；`target/` 是共享目录，不能被并发构建的清理动作影响。）

### 2.2 L2 静态审计

```powershell
cd F:\dsh\ruoyiOA ; node tools\audit\run-all.js
```

**实测（收尾轮）：`共 10 个审计；失败 0 个；未自证 0 个` → 10/10 全绿。**

（中途一轮曾出现 `audit-template-refs.js FAIL（3 ≠ 基线 1）`，命中 `views/erp/doc/DocListShell.vue` 的
`{{ options }}` / `{{ dictFailed }}` —— 那是 T9 22:45~22:49 的在建文件；T9 修好后本轮已转绿。
本任务未改任何前端文件。）

### 2.3 `二开-进销存.sql` 幂等 + 违例数据被 DB 拒绝

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 -Database rad_oa -Files "二开-进销存.sql"   # 连跑两次
```

**两次输出逐行相同，30 项自检全部等于期望值**（①18 表 / ②480 列 / ③168 / ④104 / ⑤2 / ⑥3 / ⑦2 / ⑧8 / ⑨8 / ⑩43 /
⑪12 / ⑫8 / ⑬8 / ⑭24 / ⑮18 / ⑯4 / ⑰20 / ⑱3 / ⑲3 / ⑳1 / ㉑13 / ㉒21 / ㉓16 / ㉔18 / ㉕8 / ㉖24 / ㉗8 / ㉘8 / ㉙8 / ㉚8）。

违例数据断言（真实 MySQL 8.0.40，`sql_mode` 含 `STRICT_TRANS_TABLES`；夹具 ASCII 前缀 `B4DRILL*`，收尾残留 = 0）：

| 违例 | 期望 | 实测 |
| --- | --- | --- |
| 重复 `(product_id, warehouse_id)` 结存 | 被拒 | `ERROR 1062 (23000): Duplicate entry 'B4DRILLPROD-B4DRILLWH' for key 't_ctms_stock.uk_stock_product_warehouse'` ✅ |
| 行项 `qty` 为 NULL | 被拒 | `ERROR 1048 (23000): Column 'qty' cannot be null` ✅ |
| 金额超精度（`amount` 超出 `decimal(16,2)`） | 被拒 | `ERROR 1264 (22003): Out of range value for column 'amount' at row 1` ✅ |

### 2.4 空库全量编排演练（证明 ⑥ 段接法正确）

```powershell
# 空库：rad_oa_b4drill（演练后 DROP）
powershell -File .\tools\run-db-sql.ps1 -Database rad_oa_b4drill -WithBaseline
```

**退出码 0；18 个增量（基线 2 + V1 8 + B1 2 + B2 1 + B3 4 + B4 1）全部执行成功**，
编排自检 ①~⑭：⑨=18 ⑩=8 ⑪=4 ⑫=1 ⑬=8 ⑭=24（B4 四项齐备）。日志：`.cache/t1-orchestration-drill.log`。

### 2.5 B3 资产未被破坏

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\b3-sql-drill.ps1
```

**实测：`通过 68 项，失败 0 项`，退出码 0**（日志 `.cache/t1-b3-drill.log`）。

---

## 3. S0 冻结件

### 3.1 状态机：5 状态 × 6 动作（白名单）

状态取值（`t_ctms_*`.status `varchar(16)`，落库为英文码）：

| 码 | 中文 | 说明 |
| --- | --- | --- |
| `draft` | 草稿 | **唯一可编辑状态** |
| `submitted` | 待审核 | |
| `approved` | 已审核 | 库存类单据的**终态**（审核即过账） |
| `completed` | 已完成 | 仅非库存类单据使用；申请单"归零即完成"自动置位 |
| `voided` | 已作废 | 列表默认排除 |

冻结矩阵（行=当前状态，列=动作；`✔` 合法并给出目标状态）：

| 当前 \ 动作 | submit | approve | reject | void | unapprove | complete |
| --- | --- | --- | --- | --- | --- | --- |
| `draft` | ✔→submitted（**需有行项**） | ✘ | ✘ | ✔→voided（**原因必填**） | ✘ | ✘ |
| `submitted` | ✘ | ✔→approved | ✔→draft（**原因必填**） | ✔→voided（**原因必填**） | ✘ | ✘ |
| `approved` | ✘ | ✘ | ✘ | ✘ | ✔→submitted（**原因必填**） | ✔→completed（**库存类不提供**） |
| `completed` | ✘ | ✘ | ✘ | ✘ | ✔→draft（**原因必填**） | ✘ |
| `voided` | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ |

- 出处逐条对照：移植清单 §3.8 的 8 条迁移（`draft→submitted`、`submitted→draft/approved/voided`、
  `draft→voided`、`approved→submitted/completed`、`completed→draft`）与 `doc_service.py:166-170` 的
  `_ensure_status`。**本矩阵与它有且仅有"库存类不注册 complete"这一条差异化**，来自 design D4 的风险条目
  （"状态机动作白名单按单据类别裁剪"）。
- 非法流转文案：`当前状态（<中文>）不可执行「<动作名>」`；非法状态：`无效的单据状态：<原值>`；
  缺行项：`单据没有行项，不能提交`；缺原因：`驳回原因必填` / `作废原因必填` / `反审核原因必填`（参考侧原文）；
  库存类置完成：`<单据名>为库存类单据，不支持「置为已完成」`；非草稿编辑：
  `当前状态（<中文>）不可编辑<单据名>，请先反审核或作废`（参考侧原文）。
- **编辑守卫**：`ErpDocStatus.isEditable` 只有 `draft` 为真（清单 §3.8「可编辑状态只有 draft」）。

### 3.2 18 张表与字段来源（对照移植清单 §2.3）

| # | 目标表名 | 参考侧表 | 来源（清单） |
| --- | --- | --- | --- |
| 1 | `t_ctms_purchase_request` | `purchase_requests` | §2.3.3（特有：`request_dept_id`/`need_date`/`suggest_supplier_id`/`purpose`；**无 `total_amount`**） |
| 2 | `t_ctms_purchase_request_item` | `purchase_request_items` | §2.3.2 + `ordered_qty` |
| 3 | `t_ctms_purchase_order` | `purchase_orders` | §2.3.3（`supplier_id` FK NOT NULL、`supplier_name`、`purchase_dept_id`、`expected_arrival_date`、`settle_type`、`currency`、`total_amount`、`receipt_warehouse_id`） |
| 4 | `t_ctms_purchase_order_item` | `purchase_order_items` | §2.3.2 + `received_qty` |
| 5 | `t_ctms_sales_request` | `sales_requests` | §2.3.4（`customer_id` **可空无 FK**、`customer_name_text`、`sales_dept_id`、`expect_delivery_date`；无 `total_amount`） |
| 6 | `t_ctms_sales_request_item` | `sales_request_items` | §2.3.2 + `ordered_qty` |
| 7 | `t_ctms_sales_order` | `sales_orders` | §2.3.4（`customer_id` FK NOT NULL、`customer_name`、`sales_dept_id`、`delivery_date`、`delivery_address`、`contact_name`、`contact_phone`、`ship_warehouse_id`、`currency`、`total_amount`） |
| 8 | `t_ctms_sales_order_item` | `sales_order_items` | §2.3.2 + `shipped_qty` |
| 9 | `t_ctms_stock_in` | `stock_in_orders` | §2.3.5（`warehouse_id` FK NOT NULL、`warehouse_name`、`in_type` 默认 `采购入库`、`supplier_id/name` 可空、`total_amount`） |
| 10 | `t_ctms_stock_in_item` | `stock_in_order_items` | §2.3.2（无特有列） |
| 11 | `t_ctms_stock_out` | `stock_out_orders` | §2.3.5（`out_type` 默认 `销售出库`、`customer_id/name` 可空、`total_amount`） |
| 12 | `t_ctms_stock_out_item` | `stock_out_order_items` | §2.3.2（无特有列） |
| 13 | `t_ctms_stocktake` | `stock_takes` | §2.3.5（`take_type` 默认 `full`、`scope_note`、`generated_in_id/no`、`generated_out_id/no`；无 `total_amount`） |
| 14 | `t_ctms_stocktake_item` | `stock_take_items` | §2.3.2 + `book_qty`/`actual_qty`/`diff_qty`/`diff_reason` |
| 15 | `t_ctms_transfer` | `stock_transfers` | §2.3.5（`from_warehouse_id/name`、`to_warehouse_id/name`，两个 FK NOT NULL） |
| 16 | `t_ctms_transfer_item` | `stock_transfer_items` | §2.3.2（**不使用 `warehouse_id`**，列保留以复用公共字段） |
| 17 | `t_ctms_stock` | `stocks` | §2.3.6（`product_id`/`warehouse_id`/`qty`；**只记数量不记成本**） |
| 18 | `t_ctms_stock_ledger` | `stock_ledger` | §2.3.7（`biz_type`/`doc_type`/`doc_id`/`doc_no`/`src_doc_no`/`qty_change`/`qty_after`/`unit_price`/`org_id`→`dept_id`/`created_by`→`create_id`/`remark`） |

- 表头公共列 = §2.3.1 `DocMixin` 的 21 列 + 目标侧审计块；行项公共列 = §2.3.2 `DocItemMixin` 的 13 列 + `doc_id` + 审计块。
- 表名冻结对照 `ddl-scope.md` §4 与 `notes/00-team-brief.md` §1.1：8 个表头名逐字一致；行项 = 表头 + `_item`；
  结存 `t_ctms_stock`；流水 `t_ctms_stock_ledger`；**B4 不重建 B3 的 13 张表**（§3.2 的 FK 只引用它们）。
- 列数逐表（自检 ㉔ 锁死）：purchase_request 32 / purchase_request_item 23 / purchase_order 36 / purchase_order_item 23 /
  sales_request 32 / sales_request_item 23 / sales_order 38 / sales_order_item 23 / stock_in 34 / stock_in_item 22 /
  stock_out 34 / stock_out_item 22 / stocktake 36 / stocktake_item 26 / transfer 32 / transfer_item 22 / stock 6 / ledger 16 = **480**。

### 3.3 唯一约束与索引清单

| 对象 | 定义 | 用途 |
| --- | --- | --- |
| `t_ctms_stock.uk_stock_product_warehouse` | UNIQUE(`product_id`,`warehouse_id`) | **F-2**：结存唯一；并发创建靠它兜底（见 §5） |
| `t_ctms_stock_ledger.idx_ledger_product_warehouse_id` | (`product_id`,`warehouse_id`,`id`) | **F-5**：流水下钻 + 不变式校验 |
| `t_ctms_stock_ledger.idx_ledger_doc` | (`doc_type`,`doc_id`) | **F-5**：按单据反查流水（红冲 / 盘点级联） |
| 8 张表头 `uk_*_doc_no` | UNIQUE(`doc_no`) | 单号唯一（取号见 §3.7） |
| 8 张行项 `fk_*_item_doc` | FK(`doc_id`) → 表头 **ON DELETE CASCADE** | 行项随表头消亡（清单 §2.3.2） |
| 8 张行项 `fk_*_item_product` | FK(`product_id`) → `t_ctms_product`（RESTRICT） | DB 层拒绝"行项未选物料"（§2.8 反例） |
| 8 张表头 `fk_*_dept` / `fk_*_create_id` | FK → `sys_dept` / `sys_user` | F-3：归属部门与创建人的库级约束 |
| 采购单 `fk_purchase_order_supplier`、销售订单 `fk_sales_order_customer` | FK NOT NULL | 禁止把客户传到供应商字段（task 3.2/4.1） |
| 入库/出库/盘点/调拨的仓库 FK（6 条） | FK NOT NULL | 表头仓库必填（调拨两条） |
| 结存/流水的物料与仓库 FK（4 条） | FK NOT NULL | 结存/流水只能指向真实档案 |
| 其余业务索引 | 状态 / 单据日期 / 归属部门 / 创建人 / 合同 / del_flag / 来源单（`source_doc_type`,`source_doc_id`）/ 行项 `(doc_id,seq)` / 行级仓库 / `src_item_id` | 列表筛选、下推回写、流水下钻 |
| **B4 外键合计 43 条**（自检 ⑩） | | |

### 3.4 精度口径（design D9 / REQ-NFR-007 / AC-78）

| 项 | 口径 | DB 列 |
| --- | --- | --- |
| 金额 | `BigDecimal`，2 位，**HALF_UP** | `decimal(16,2)`（表头 `total_amount` 与行项 `amount`） |
| 单价 | `BigDecimal`，4 位 | `decimal(14,4)` |
| 数量 | `BigDecimal`，3 位（内部）；录入精度按**单位小数位**校验（0~4） | `decimal(16,3)` |
| 行金额 | `qty × unit_price` **先 HALF_UP 到 2 位** → `ErpAmounts.lineAmount` | — |
| 单据合计 | **各行舍入后金额之和** → `ErpAmounts.totalOf`（不是"先高精度求和再舍入"） | — |
| 反例锁死 | 三行 `1 × 0.125`：每行 0.13、合计 **0.39**（错误口径 0.38 已被单测显式对照） | — |
| 浮点 | B4 的 `erp` 包内**源码级 + 反射级**零浮点（`ErpPrecisionTest`） | — |

### 3.5 数据范围（design D10 / tasks §8.2 / AC-79）

| 档位 | `sys_role.data_scope` | SQL 条件（别名默认 `d`） | Java 判定 |
| --- | --- | --- | --- |
| 本人 | `5` | `d.create_id = '<uid>'` | `create_id == 本人` |
| 本部门 | `3` | `d.dept_id = '<did>'` | `dept_id == 本人部门` |
| 本部门及下级 | `4`（`2` 自定义部门同口径） | `(d.dept_id = '<did>') or (find_in_set('<did>', (select sd.ancestors from sys_dept sd where sd.dept_id = d.dept_id)))` | 本行部门在本部门子树内 |
| 全部 | `1`（超管同） | 不加条件（`null`） | 恒 true |

- **多角色取并集**（任一命中即可见）；只要有一个角色是"全部"就不加条件。
- **归属部门在创建时快照**：`ErpDocScope.applyCreateSnapshot` 只在 insert 路径写一次；编辑/流转不重算（人员调岗不回溯）。
- **无档位 = 不可见**（返回 `1=0`，刻意不返回 `null`，否则会被读成"全部可见"）。
- **无登录上下文**（单测/定时任务）→ 不加条件（与 B3 同款兜底）。
- 片段是白名单拼接：只有服务端 `SecurityContext` 的 uid/did 进 SQL，且各自过字符白名单（`[0-9A-Za-z_-]{1,64}`）。
- ⚠ **与 `ContractDataScope`（B3）刻意分成两个类**：B3 把 `'3'/'2'` 也按"含下级"处理；B4 的四档要求
  "本部门 ≠ 本部门及下级"，两套口径不同就不能共用实现。
- ⚠ 内层 `sys_dept` 别名固定 `sd`（`DEPT_INNER_ALIAS`）：若与外部单据别名同名，
  `where sd.dept_id = sd.dept_id` 会退化成恒真子查询（已在本轮设计时规避，单测锁死）。

### 3.6 附件对象登记（当前状态）

> **计数以 `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` 为准**
> （t19：按参考仓库 `app/routers/attachments.py:35-44` 的 `OBJECT_PERMS` 口径补齐两个申请单；
> 参考侧 `OBJECT_PERMS` **缺 `stock_transfer` 是它的已知缺陷，我们按 Q-B10 补上**）。

- `REGISTERED`（**9 项**）= `contract` + 8 类单据 = `purchase_request` + `purchase_order` +
  `sales_request` + `sales_order` + `stock_in` + `stock_out` + `stock_take` + `stock_transfer`。
  （t1 交付时为 7 项 —— `contract` + 6 类单据，**缺 `purchase_request` / `sales_request`**；
  t19 已按上面那份口径补齐到 9 项。）
- `plannedB4` = **空数组**（原 5 项已全部放行；键名保持不变以兼容既有消费者）。
- 上传限制**不变**：10 种后缀、20MB（`>20MB` 拒绝、`==20MB` 放行）、413、白名单更窄于平台。
- 未注册类型仍**明确拒绝**（`未注册的对象类型：<值>`）；已注册但对象不存在 → `<单据名>不存在：<id>`；
  对象存在但超出数据范围 → `无权访问该<单据名>` + 业务码 **403**。
- **8 类单据**对象的表映射：`purchase_request→t_ctms_purchase_request`、
  `purchase_order→t_ctms_purchase_order`、`sales_request→t_ctms_sales_request`、
  `sales_order→t_ctms_sales_order`、`stock_in→t_ctms_stock_in`、`stock_out→t_ctms_stock_out`、
  `stock_take→t_ctms_stocktake`、`stock_transfer→t_ctms_transfer`
  （`ErpDocLookupMapper.xml` 的 8 个显式分支，无动态表名）。

### 3.7 单据编号（tasks §1.3；captain 追加）

| 单据 | 配置 id | 前缀 |
| --- | --- | --- |
| 采购申请单 | `9F2C0000000000000000000000C101` | `PR` |
| 采购单 | `…C102` | `PO` |
| 销售申请单 | `…C103` | `SR` |
| 销售订单 | `…C104` | `SO` |
| 入库单 | `…C105` | `IN` |
| 出库单 | `…C106` | `OUT` |
| 盘点单 | `…C107` | `ST` |
| 调拨单 | `…C108` | `DB` |

- 格式 `{前缀}{yyyyMM}{6 位序号}`，例 `PR202610000001`；规则 3 条 = 固定值(前缀) + 业务参数(`ym`) + 序号(6 位补零、`seq_reset_type='3'` 按月)。
- **唯一入口** `ErpDocNoGenerator.nextDocNo(type[, docDate])`，只在**创建**路径取一次（编辑不重取，否则单号会变）。
- ⚠ **按月重置的实现口径（重要，勿改）**：平台 `CodeRenderSupport.bucketKeyOf` 的计数器分桶**只对"按年"生效**
  （仅 `seqResetType='4'` 且给了参考日期时追加 `:y<年>`；按日/按周/按月都不进桶键，`t_code_config.current_seq` 是单列、
  真值在 Redis）。所以月份写成**业务参数** `ym=yyyyMM`：桶键变成 `code:gen:seq:<confId>:ym=202610`，
  **跨月天然从 0 起（惰性按月重置，不依赖定时任务）**。若把月份改成日期规则 `yyyyMM`，跨月**不会**重置
  （只有每月 1 号当天跑到 `CodeRestTask` 才重置 DB 单列，而 Redis 计数器不变）。
  该口径被单测锁死：跨月桶键必变 + 平台渲染器交叉验证 + `ErpDocNoGenerator` 必传 `ym`。
- 配置 id 与 Java 常量是**两处真源**（SQL ③ 段 ↔ `ErpDocNoGenerator.CONF_ID_*`），改一处必须改两处。

---

## 4. 与参考侧/冻结件的**刻意差异**（逐条给理由）

| # | 差异 | 理由 |
| --- | --- | --- |
| 1 | 主键统一 `varchar(64)` UUID（参考侧 Integer 自增） | 与 B3 的 13 张表一致；目标侧全局用 UUID |
| 2 | 8 张表头 `org_id`→`dept_id`、`created_by`→`create_id`，并按 **F-3 收紧 NOT NULL + FK** | RuoYi 列名惯例（清单 §6.3 注意②③）+ F-3 的"应用层强制字段改回 NOT NULL"；归属部门还要承担 DEPT 数据范围 |
| 3 | 行项补齐目标侧审计块（`create_time`/`update_id`/…） | 参考侧 `DocItemMixin` 没有时间列；目标侧审计口径统一（B3 同款） |
| 4 | 参考侧 `Text` → `varchar(1000)` | 勘察 §8.2 的既有约定（B3 同款） |
| 5 | 采购申请/销售申请/盘点单**不加** `total_amount` 列 | 口径同参考 §2.3.3/§2.3.5：列表金额由行项现算，唯一实现点 `ErpAmounts.totalOf` |
| 6 | `t_ctms_stock.updated_at` → `update_time`（`ON UPDATE CURRENT_TIMESTAMP`） | 参考侧"NOT NULL 无 server_default、由服务显式赋值"；本侧用列默认兜底，过账服务仍应显式赋值 |
| 7 | 流水**无更新列**（没有 `update_time`/`update_id`/`update_by`） | 口径"只增不改"：红冲是**追加负数**，库级就写不出修改路径 |
| 8 | 状态机 `complete` 对库存类单据**直接拒绝**（不是"入口不注册"） | 入口不注册只是界面不显示；显式拒绝才保证"系统中不存在已完成的库存类单据" |
| 9 | 数据范围四档**不是** B3 的三档映射 | tasks §8.2 明确要求"本部门"与"本部门及下级"两档各自成立 |
| 10 | 编号"按月重置"用业务参数 `ym` 分桶，而不是日期规则 `yyyyMM` | 见 §3.7 的平台事实；否则跨月不重置 |
| 11 | `ErpDocHeader`/`ErpDocItem` 公共字段为 `protected` | 子类（8 类单据实体）直接读写公共字段；T4 的子类已实测因 `private` 编译失败（javadoc 已写明"子类不得重复声明同名字段"） |
| 12 | OpenSpec `tasks.md` §1.2 的"同步补齐到 `sql/table.sql`"**不执行** | 与冻结件 `ddl-scope.md` §6 约定 1（`table.sql`/`data.sql` 不改，`git diff` 为空）冲突，**以冻结件为准**；本轮实测空库全量编排（§2.4）已证明"基线 + 增量"能建全，无需改基线 |

---

## 5. 并发原型结论（tasks §1.6；REQ-NFR-008 / AC-74 前置）

原型脚本与日志：`.cache/t1-concurrency-proto.ps1`、`.cache/t1-concurrency-proto.log`（真 MySQL 8.0.40，
两个 `mysql` 客户端进程并发，夹具 ASCII 前缀 `B4P*`，收尾残留 0）。

### 5.1 实测五个场景

| 场景 | 做法 | 实测结果 | 结论 |
| --- | --- | --- | --- |
| **B** 有锁、行已存在 | 两个会话各 `SELECT qty ... FOR UPDATE` → `SLEEP(2)` → `UPDATE qty = qty + 5` + 追加流水 | 结存 `10 → 20`，流水 3 行、`SUM(qty_change)=20=stock.qty` | **行锁生效，两次变动都落入结存，不变式成立** |
| **C** 无锁、行已存在 | 两个会话各非锁定 `SELECT INTO @old` → `SLEEP(2)` → `SET qty = @old + 5` | 结存 `10 → 15`（丢了 5），流水 2 条 +5（`SUM=20 ≠ 15`） | **丢更新真实存在**（参考侧不 commit 的写法在 MySQL 下必现），F-1 的行锁是必需的 |
| **D** 行不存在 + 非锁定判空 + INSERT | 两个会话都先非锁定查（都判"不存在"）再 INSERT | **恰好一次 `ERROR 1062`**（`Duplicate entry 'B4PS-D' for key 'PRIMARY'`），最终 1 行 | 参考侧"先查后插"在并发下必撞唯一键；**重试上限 ≥1**，最多重试 3 次即足够 |
| **E** 行不存在 + `FOR UPDATE` 再 INSERT | 两个会话都 `SELECT ... FOR UPDATE`（无行）→ `SLEEP` → INSERT | **`ERROR 1213`（Deadlock found）**，两个会话的判空都返回 `row-missing`、都没有被对方阻塞 | **关键发现**：不存在的行上 `FOR UPDATE` 取到的是**间隙锁**，间隙锁彼此兼容 → 两个"创建者"都能通过判空，随后在 INSERT 处撞插入意向锁**死锁**。所以"先加锁再插入"不是安全写法 |
| **F** 推荐配方 | 先 `INSERT ... ON DUPLICATE KEY UPDATE qty = qty`（幂等建行）→ 再 `SELECT ... FOR UPDATE` → UPDATE + 追加流水 | 结存 `0 → 10`（两次 +5 都落地），1 行结存，`SUM(qty_change)=10=stock.qty`，**零报错** | **推荐**：把"创建"交给唯一键幂等语义，把"加锁"放在行一定存在之后 |

### 5.2 冻结结论（T3 过账按此实现）

1. **行锁粒度**：锁的是 `t_ctms_stock` 上 `(product_id, warehouse_id)` **唯一索引的那一条记录**。
   - 不同 `(物料,仓库)` 之间**互不阻塞**（并发吞吐不受影响）；
   - **行已存在**：`SELECT ... FOR UPDATE` 正常串行化（场景 B）；
   - **行不存在**：`FOR UPDATE` 只拿到间隙锁、彼此不互斥（场景 E），因此**不能**靠它防并发创建。
2. **事务边界**：审核 = 状态流转 + 过账在**同一显式事务**内（`@Transactional(rollbackFor = Exception.class)`），
   服务层不提交；任一校验失败整体回滚（结存与单据状态都不变）。负库存校验必须在**锁内**读到的 qty 上判定，
   否则会拿脏值放行（场景 C 的教训）。
3. **结存行不存在时的创建与重试次数**：
   - **推荐（0 次重试）**：`INSERT INTO t_ctms_stock(id, product_id, warehouse_id, qty) VALUES (…, 0) ON DUPLICATE KEY UPDATE qty = qty`
     → 再 `SELECT ... FOR UPDATE` → 读 qty → `UPDATE qty = qty + delta` → 追加流水。实测零报错（场景 F）。
   - **备选（符合 D3 字面写法）**：非锁定判空 → 不存在则 INSERT → 捕获重复键（1062）→ **重试上限 3 次**，
     每次重试重新执行"`SELECT ... FOR UPDATE` + 判定"，实测 2 路并发下只发生 1 次 1062，1 次重试即成功。
   - **禁止写法**：判空与 INSERT 之间用 `FOR UPDATE` 兜底（场景 E 会死锁 1213）；以及不加锁的读改写（场景 C 丢更新）。
   - MySQL/InnoDB 的重复键/死锁**只回滚该语句**，事务本身仍可继续，因此"捕获后重试"在同一事务内成立
     （死锁场景需由上层重试整个事务）。
4. **过账幂等**：`posted='1'` 时重复审核直接短路（不产生流水、不改结存）；红冲按负数追加流水并清 `posted`。

---

## 6. 口径变更记录（附件对象登记的两条断言）

| 文件 | 旧 | 新 | 为什么是"口径变更"而不是"放宽" |
| --- | --- | --- | --- |
| `ruoyi-ctms/src/test/java/com/ruoyi/ctms/service/impl/CtmsAttachmentServiceImplTest.java`（`对象类型大小写不敏感且归一为小写`，原 :451） | `assertFalse(service.isRegisteredObjectType("purchase_order"))` | 逐个断言 `CtmsAttachmentObjectTypes.docObjectTypes()` 的 6 个类型**都在册**且都在 `registered()` 里，并断言 `plannedB4()` **为空**，最后仍断言未注册的 `ctms_unknown_object` 被拒 | B3 的旧断言表达的是"B4 尚未交付、刻意不放行"；B4 交付后该类型**必须**放行，否则单据附件全被拒。新断言把"6 类都在册 + 计划清单清空 + 未知类型仍拒绝"三条一起锁住，**断言数与约束都变多** |
| `tools/ctms-attachment-check.ps1`（`6.1 附件清单接口：已注册 / B4 待补 / 限制口径`，原 :492） | `Assert-That (@($meta.plannedB4).Count -ge 1) "B4 待补对象类型已登记"` | ① `registered` 逐个包含 6 个单据对象类型（循环 6 条断言）② `plannedB4.Count -eq 0` ③ 新增 `docObjectTypes.Count -eq 6`；脚本总断言数由 **69 → 76**（只增不减） | 同上。captain 2026-10-05 明确许可（口径变更 + 变强 + 记录在本节；未动 B3 其它断言的期望值） |

> 该脚本需要后端 + 登录态（L3 窗口），本任务不重跑；**T10/T11 统一窗口请按新断言复跑**。
>
> **后续（t19，2026-10-05）**：单据对象类型由 6 类补到 **8 类**、登记清单 **7 → 9 项**
> （按参考仓库 `app/routers/attachments.py:35-44` 的 `OBJECT_PERMS`；参考侧缺 `stock_transfer`
> 是它的已知缺陷、我们按 Q-B10 补上），单测 18 → 22、脚本断言 76 → 107。
> **本节两条断言的具体条数（6 类 / 76 条）是 t1 当时的事实**，当前计数与取证以
> `openspec/changes/oa-purchase-sales-stock/notes/12-attachment-parity.md` 为准。

---

## 7. 已知边界与遗留（不隐瞒）

### 7.1 上游基线未动（证据）

```text
cd ruoyi-vue-oa-master ; git status --porcelain
 M ruoyi-ctms/src/main/java/com/ruoyi/ctms/controller/CtmsAttachmentController.java
 M ruoyi-ctms/src/main/java/com/ruoyi/ctms/service/impl/CtmsAttachmentServiceImpl.java
 M ruoyi-ctms/src/main/java/com/ruoyi/ctms/support/CtmsAttachmentObjectTypes.java
 M ruoyi-ctms/src/test/java/com/ruoyi/ctms/service/impl/CtmsAttachmentServiceImplTest.java
 M sql/初始化-全部.sql
?? ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/
?? ruoyi-ctms/src/main/resources/mapper/erp/
?? ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/
?? sql/二开-进销存.sql
```

**`sql/table.sql` 与 `sql/data.sql` 均未出现**（禁止项已满足）。

### 7.2 当前模块级红灯的归属（不是本任务的改动）

| 红灯 | 证据 | 归属 |
| --- | --- | --- |
| `mvn -pl ruoyi-ctms test` 9 条红（383 总） | `erp.procurement.ErpPurchaseServiceImplTest` 1F+2E、`ErpPurRulesTest:146 下推数量必须大于 0`、`erp.procurement.TmpProbeTest`（探针临时文件，1E）、`erp.sales.service.impl.ErpSalesServiceImplTest` 3F+1E（`关联合同不存在或已停用`、`已停用的合同不应可关联`） | T4/T5 的在建测试（T3 的 posting 两套本轮已转绿） |
| 其中 **2 条由公共层的规则触发**：`ErpPurchaseServiceImplTest` → `ServiceException: 单据没有行项，不能提交`（`ErpDocStateMachine.java:129`）与 `ServiceException: 当前状态（已审核）不可编辑，请先反审核或作废`（:191） | T4 的用例在"提交"前没有造行项、在"已审核"单据上直接编辑（两条都是规格明文要求的行为：tasks §1.1「提交仅草稿且必须有行项」、§3.8「可编辑状态只有 draft」） | **T4 的用例需补行项 / 先反审核再编辑**；公共层规则不放宽（已报文 captain） |
| `node tools/audit/run-all.js` | 收尾轮 **10/10 全绿**（中途 T9 在建文件曾致 1 个红，已转绿） | — |

> 文案口径调整（收尾轮）：`ErpDocStateMachine.checkEditable` 的错误文案改为与参考侧**逐字一致**的
> `当前状态（<中文>）不可编辑，请先反审核或作废`（原先在句中拼了单据名）。
> 理由：移植清单把提示文案当契约，句中插入单据名会让按参考原文断言的用例失败；
> `docType` 参数保留（8 类单据共用入口，且今后可用于审计/日志）。改后我的 `ErpDocStateMachineTest` 仍全绿。

### 7.3 观察（供 captain 的 t17"空库自检数字"专项参考，**我没有改任何期望值**）

空库全量编排时 B3 段自检的实测值：⑲ 外键 **17**（标签写"应为14"）、㉓ 唯一索引 **11**（标签"应为10"）、
㉜ `sys_user` 列数 **21**（标签"应为18"）、㉝ `sys_dept` 列数 **16**（标签"应为18"）。
另：`tools/b3-sql-drill.ps1` 自证"建表后有外键（**17** 个）"并全绿 —— 即 **17 是 B3 的既有事实**，标签数字是旧口径。
原始输出：`.cache/t1-orchestration-drill.log`、`.cache/t1-b3-drill.log`。

### 7.4 其它边界

- `ErpPrecisionTest` 的源码扫描范围是 **B4 的 `com/ruoyi/ctms/erp` 包**（B4 包内连注释都不含浮点类型词）；
  B3 遗留的 `package-info.java` / `CtmsProduct.java` 有 **3 处** javadoc 里出现"禁止 … 的字样"（历史文本，非类型使用），
  按"不改别人文件"的纪律**未动**；brief §3.5 的"grep 零命中"因此在全模块范围内**当前不为 0**（实测 3 处，全部是注释）。
- `t_ctms_stock_ledger.dept_id` / `create_id` 允许为空（不能反查即空）：数据范围判定依赖过账服务**写入**这两个快照，
  已写入建表注释；T3 若漏写，DEPT/SELF 档会看不到对应流水（§8.2 的验证会抓）。
- 盘亏单豁免负库存（口径"账实不符本身即证据"）**不在本任务**，由 T6 在盘点审核的过账路径上按参数豁免实现；
  公共层只提供 `ErpStockParams` 的取值判定，不做全局开关。

---

## 8. t31 事故修复：`posted` 歧义 getter 导致 8 类单据写路径整体 500（2026-10-05）

### 8.1 症状（真机 HTTP，非推测）

`backend-run.log:203/2090`、`sys-error.log:302863`、t20/t26 的接口脚本（`.cache/t20/check-P-after-package.txt:9`、`.cache/t26/smoke-run1.txt:5`）一致复现：

```text
POST /erp/pur/request/list → 200 之前在 service 层炸
org.mybatis.spring.MyBatisSystemException: nested exception is
org.apache.ibatis.reflection.ReflectionException: Illegal overloaded getter method with
ambiguous type for property 'posted' in class 'com.ruoyi.ctms.erp.base.domain.ErpDocHeader'.
This breaks the JavaBeans specification and can cause unpredictable results.
  at org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker.invoke(AmbiguousMethodInvoker.java:34)
  at org.apache.ibatis.reflection.wrapper.BeanWrapper.getBeanProperty(BeanWrapper.java:164)
  at org.apache.ibatis.scripting.xmltags.DynamicContext$ContextAccessor.getProperty(DynamicContext.java:113)
  at org.apache.ibatis.ognl.ASTNotEq.getValueBody(ASTNotEq.java:50)
```

### 8.2 根因（讲清"为什么 531 条单测全绿也发现不了"）

`ErpDocHeader` 同时有：

| 方法 | 类型 | 用途 |
| --- | --- | --- |
| `getPosted()` | `String` | **落库列**（`'0'/'1'`），resultMap / `#{posted}` 都用它 |
| ~~`isPosted()`~~ | `boolean` | 便捷判定（过账幂等短路） |

JavaBeans 口径下二者是**同一属性 `posted` 的两个 getter**且类型互不兼容，MyBatis 把该属性包成
`AmbiguousMethodInvoker`——注意：**建 `Reflector` 时不抛**（这也是最初"按 `new Reflector()` 写守卫测试"的坑），
**真正读这个属性时才抛**。真实触发点是 mapper XML 的 OGNL 动态 SQL，
例如 `resources/mapper/erp/ErpPurRequestMapper.xml:116`：

```xml
<if test="posted != null and posted != ''"> and d.posted = #{posted} </if>
```

三条并存原因让它在交付前一直藏着：

1. **单测用 stub mapper**，不走 XML/OGNL，因此 531 条全绿；
2. **模块测试类路径解析到 MyBatis 3.5.13**（`.cache/t1-cp.txt`；该文件是 t1 当天生成，已过期），
   而 **打包运行件里是 3.5.7**（`ruoyi-admin.jar` 的 `BOOT-INF/lib/mybatis-3.5.7.jar`）。
   实测：`new Reflector(ErpDocHeader.class).getGetInvoker("posted")` 在 3.5.13 下**不**是
   `AmbiguousMethodInvoker`，在 **3.5.7 下就是**（探针输出见 `.cache/t31/prefix-trigger-3.5.7.log`）；
3. 只有"含 `posted` 条件/写入的语句"才炸，恰好覆盖 8 类单据的列表与写入主链路。

**影响面实测**（全仓静态扫描 `getX()`+`isX()` 同属性名不同类型，脚本 `.cache/t31/SiblingScan.java`）：

```text
修复前：classes scanned = 924, ambiguous properties = 7   （全部是 property 'posted'）
  ErpDocHeader / ErpStockIn / ErpStockOut / ErpPurchaseOrder / ErpPurchaseRequest
  / ErpStocktake / ErpTransfer
修复后：classes scanned = 924, ambiguous properties = 0
```

> ⚠ 修正一处口径：**销售两单（`ErpSalesRequest`/`ErpSalesOrder`）不受本根因影响** ——
> 它们的表头 `extends BaseEntity`（不是 `ErpDocHeader`）并自带 `private String posted`（只有 get/set），
> 所以 `/sal/order` 不会因 `posted` 歧义 500（详见 §8.5）。

### 8.3 修复（最小改动：重命名便捷判定，落库列一字未动）

| 文件 | 改动 |
| --- | --- |
| `erp/base/domain/ErpDocHeader.java:174` | `public boolean isPosted()` → **`isPostedFlag()`**（属性名变成独立的 `postedFlag`），并补 javadoc 写明"方法名不能叫 `isPosted()`"的原因 |
| 其余 9 个文件 | 19 处调用点机械改名（见 §8.4 清单），**行为不变** |

不改：`getPosted()/setPosted()`、`posted` 字段与列类型、所有 resultMap 的 `property="posted"`、
XML 里的 `#{posted}`、`POSTED_NO` 兜底（`applyCreateSnapshot`）。

### 8.4 调用点清单（19 处，逐文件）

| 文件 | 行（改后） | 处数 |
| --- | --- | --- |
| `erp/base/domain/ErpDocHeader.java` | 定义 174 | 1 |
| `erp/posting/controller/ErpStockInController.java` | 405 | 1 |
| `erp/posting/controller/ErpStockOutController.java` | 397 | 1 |
| `erp/posting/service/impl/ErpStockInServiceImpl.java` | 245 / 321 / 351 / 382 | 4 |
| `erp/posting/service/impl/ErpStockOutServiceImpl.java` | 235 / 310 / 340 / 371 | 4 |
| `erp/stockops/controller/ErpStocktakeController.java` | 497 | 1 |
| `erp/stockops/controller/ErpTransferController.java` | 419 | 1 |
| `erp/stockops/service/impl/ErpStocktakeServiceImpl.java` | 367 / 539 / 570 | 3 |
| `erp/stockops/service/impl/ErpTransferServiceImpl.java` | 243 / 321 | 2 |
| `**erp/sales**` 测试 `src/test/java/com/ruoyi/ctms/erp/sales/service/impl/ErpSalesPushOutTest.java` | 240 | 1 |
| 合计 | | **19** |

> 最后一行在派单的 out-of-scope 列表里（`erp/sales/`），但**验收项明确要求"19 处 `isPosted()` 调用点全部更新"**，
> 且不改这一行会让改名后的树在**测试编译期直接红**、阻塞所有人。故只做这一行机械改名，并在交付报文里显式披露；
> 未改 sales 的任何生产代码／逻辑。

### 8.5 新增守卫测试（`src/test/java/com/ruoyi/ctms/erp/base/ErpDomainReflectorTest.java`，4 条）

口径：扫描 `target/classes/com/ruoyi/ctms/erp` 的**全部 .class**（含 8 类单据的 header/item、VO、内部类），
逐个建 `Reflector` 并**读取每个可读属性**（只建不读会漏 —— 见 §8.2 第 2 点），另加与 MyBatis 版本无关的
静态口径（同属性名两个不同类型 getter 即失败）；`SCAN_FLOOR=40` 防"扫描为空也绿"。

**修复前（真实输出，`.cache/t31/prefix-test-red.log`）**：

```text
Tests run: 4, Failures: 3, Errors: 0
ErpDomainReflectorTest.everyErpDomainPropertyIsReadableWithoutAmbiguity:142
  检查了 1306 个属性，发现以下歧义：
  · com.ruoyi.ctms.erp.base.domain.ErpDocHeader.posted 的 getter 是 AmbiguousMethodInvoker（同属性名两个不同类型 getter）
  · com.ruoyi.ctms.erp.posting.domain.ErpStockIn.posted …
  · com.ruoyi.ctms.erp.posting.domain.ErpStockOut.posted …
  · com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseOrder.posted …
  · com.ruoyi.ctms.erp.procurement.domain.ErpPurchaseRequest.posted …
  · com.ruoyi.ctms.erp.stockops.domain.ErpStocktake.posted …
  · com.ruoyi.ctms.erp.stockops.domain.ErpTransfer.posted …
ErpDomainReflectorTest.postedStaysStringAndFlagLivesUnderItsOwnProperty:176 postedFlag 属性应存在
ErpDomainReflectorTest.postedFallbackAndFlagSemanticsUnchanged:201 便捷布尔判定应存在…（isPostedFlag）
```

**修复后**：`Tests run: 4, Failures: 0`（同一命令 `mvn -B -pl ruoyi-ctms test -Dtest=ErpDomainReflectorTest`）。
其中 `postedFallbackAndFlagSemanticsUnchanged` 用**反射**取 `isPostedFlag`（而不是直接写方法名），
这样本测试类在修复前也编译得通、能真复现"修复前红 / 修复后绿"。

### 8.6 运行期复核（用运行期那支 MyBatis 3.5.7）

```text
修复前（旧 target/classes + mybatis-3.5.7.jar）：.cache/t31/prefix-trigger-3.5.7.log
  read posted  -> FAIL ReflectionException: Illegal overloaded getter method with ambiguous type for property 'posted' …
  MetaObject.getValue("posted") -> FAIL 同上（ErpPurchaseRequest / ErpStockIn / ErpTransfer）
修复后（新 target/classes + mybatis-3.5.7.jar）：.cache/t31/postfix-trigger-3.5.7.log
  read posted  -> 不再抛 ReflectionException（探针以 null 为目标，故只剩预期内的 NPE）
  MetaObject.getValue("posted") -> OK -> null
```

### 8.7 回归证据

| 项 | 结果 |
| --- | --- |
| 整模块 `mvn -B -pl ruoyi-ctms test` | **Tests run: 535, Failures: 0, Errors: 0, BUILD SUCCESS**（t29 后基线 531 + 新增 4 条；只增不减） |
| 相关既有测试 | `ErpMasterGuardsTest` 8/8、`ErpMasterGuardsGeneratedPathTest` 4/4、`ErpStockDocServiceTest` 14/14 全绿（行为未变） |
| 全仓同族风险 | 修复前 7 个歧义属性 → **修复后 0**（924 个类，命令见 §8.2） |
| 服务端 HTTP 复核 | 由 **t20 用新 jar 重打包后复跑**（本任务给出 Reflector/真实触发路径级证据与调用链自查） |