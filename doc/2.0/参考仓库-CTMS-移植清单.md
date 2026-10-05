# 参考仓库（CTMS）→ RuoYi-OA 移植清单

> **参考基线**：`F:\dsh\ruoyiOA\.cache\contract-ref`（只读），App 版本号 `2.0.0-dev`（`app/config.py:39`），
> 文档版本已推进到 V2.2（`docs/25-v2.2-adjustments.md`）、V2.1（`docs/22-v2.1-requirements.md`）。
> **目标平台**：`F:\dsh\ruoyiOA\ruoyi-vue-oa-master`（Java 8 / Spring Boot 2.5 / MyBatis-Plus / Flowable / MySQL，多模块 Maven）
> 与 `F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master`（Vue 2.6.14 + element-ui 2.15.14 + vue-router 3.4.9 + axios 0.28.1）。
> **本文用途**：给 Java/Vue2 开发者做工作量与风险排期；所有"现状"论断均给 `path:line` 或 `docs/xx-...§n` 引用。
> **引用约定**：`app/...`、`web/...`、`docs/...` 均相对参考仓库根；`ruoyi-*/...` 相对目标仓库根。
> **未验证项**统一放在文末 `## 附录 A：待确认`，正文不臆测。

---

## 1. 系统概览

### 1.1 业务范围

两条业务线合并在一个系统里，**合同管理是 V1.0 存量，ERP 进销存是 V2.0 新增**：

| 业务线 | 范围 | 权威需求文档 |
|---|---|---|
| 合同管理（F1~F10） | 合同台账登记、标签、组合检索、框架合同一对多、进度状态与变更历史、质保金到期跟踪、首页看板提醒、附件、Excel 台账导出、系统日志与备份 | `docs/01-requirements.md` §3、§4（BR1~BR13）、§7（AC-01~AC-15） |
| ERP 进销存（F-V2-1~F-V2-16） | 组织/账号/角色/权限、客户/供应商/商品类型/物料/单位/仓库主数据、采购申请→采购单→入库单、销售申请→销售订单→出库单、库存结存与流水、盘点（全盘/抽盘）、调拨（V2.1/N13）、单据打印模板（V2.2） | `docs/11-erp-requirements.md` §5~§10、§12（BR-V2-01~20）；增量见 `docs/22-v2.1-requirements.md` §3（BR-V2.1-01~11） |

明确**不做**（`docs/11-erp-requirements.md` §14）：财务凭证/总账、成本核算（库存只记数量不记成本，口径 D4/C-05，`app/models_stock.py:1-8`）、BOM/生产、电商对接、移动 App、OCR、多币种换算（币种仅记录不参与计算，BR-V2-14）。

### 1.2 使用角色

**V1.0 无账号体系**（`docs/01-requirements.md` §2，决策"不做登录/不区分角色"）→ **V2.0 起正式解冻**，引入账号+角色+菜单/按钮权限+数据范围（`docs/01-requirements.md:9`、`docs/11-erp-requirements.md` §2.2）。

V2.0 预置 8 个角色（`app/permissions.py:210-270`）：

| 角色 code | 名称 | 数据范围 | 权限点数（`docs/16-permission-matrix.md:10-17`） | 说明 |
|---|---|---|---|---|
| `sysadmin` | 系统管理员 | ALL | 97（V2.2 后 99） | 全部权限；内置不可删 |
| `purchase_manager` | 采购主管 | DEPT_SUB | 29 | 采购域审核 + 只读库存 |
| `buyer` | 采购员 | SELF | 21 | 只能录入/提交，**不可审核** |
| `sales_manager` | 销售主管 | DEPT_SUB | 29 | 销售域审核 |
| `seller` | 销售员 | SELF | 21 | 只能录入/提交 |
| `keeper` | 仓管员 | ALL | 38 | 出入库/盘点/调拨录入+审核（触发过账） |
| `finance` | 财务 | ALL | 22 | 全量只读 + 导出（V2.0 无财务模块） |
| `viewer` | 只读/管理层 | ALL | 19 | 全量只读 |

V1.0 的现实分工（采购/财务/项目管理三类人，`docs/01-requirements.md` §2）映射到角色后即上表。

### 1.3 技术栈对照

| 层 | 参考仓库（CTMS） | 目标平台（RuoYi-OA） | 差异性质 |
|---|---|---|---|
| 语言/运行时 | Python 3.x + FastAPI（`app/main.py:64`） | Java 8 + Spring Boot 2.5 | **完全重写** |
| ORM | SQLAlchemy 2.x Declarative（`app/database.py:8-16`、`app/models.py:34`） | MyBatis-Plus + MyBatis XML（`ruoyi-system/src/main/resources/mapper/system/*.xml`） | **完全重写** |
| 数据库 | SQLite（默认，WAL + `PRAGMA foreign_keys=ON`，`app/database.py:19-31`）；可选 PostgreSQL（`app/requirements-pg.txt`） | MySQL | DDL/类型/自增策略改写 |
| 迁移 | 自研幂等 MySQL-less 增量器 `app/db_migrate.py`（`ALTER TABLE ADD COLUMN` 补列+补索引+整表重建放约束） | 手写/`sql/` 目录 DDL | **不能复用**，需重写为 MySQL DDL |
| 认证 | 自签 HS256 JWT（无第三方库，`app/security.py:1-9`）+ `HTTPBearer` | Spring Security + Token/JWT（RuoYi `ruoyi-framework`） | 复用 OA 侧 |
| 权限 | 代码常量权限点 + `Depends(require_perm(...))`（`app/permissions.py`、`app/services/permission_service.py:81-94`） | `@PreAuthorize("@ss.hasPermi('a:b:c')")` + `v-hasPermi` 指令 | 命名分隔符不同（`.` vs `:`） |
| 数据范围 | 应用层 `apply_data_scope` 拼 `org_id` 条件（`app/services/permission_service.py:119-146`） | `@DataScope` AOP + `params.dataScope` 拼 SQL（`ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/DataScopeAspect.java`） | 机制不同，需改判定口径 |
| 前端 | Vue 3 `<script setup>` + Vite + Element Plus + Pinia（`web/src/router/index.ts:1`、`web/package.json`） | Vue 2.6.14 Options API + Vue CLI 4 + element-ui 2.15.14 + Vuex | **完全重写** |
| 测试 | pytest（`app/tests/test_*.py`）+ Playwright E2E（`web/tests/e2e/*.spec.ts`）+ 手写 smoke（`app/tests/smoke_*.py`） | 无既有等价物 | **不能直接复用**，只能当验收清单 |
| 审批流 | 自研单级 approve/reject 状态机（`app/services/doc_service.py:173-259`） | Flowable（`ruoyi-flowable` / `ruoyi-workflow`） | 可升级为流程引擎，也可保留单级 |

### 1.4 模块清单

**后端（`app/`，48 个非测试 .py 文件）**

| 类别 | 文件 | 行数 | 职责 |
|---|---|---|---|
| 入口/基础设施 | `main.py` / `config.py` / `database.py` / `db_migrate.py` / `init_db.py` / `security.py` | 108 / ~90 / 43 / 166 / ~300 / ~120 | 应用装配、配置、引擎、增量迁移、种子、JWT+口令 |
| 字典与权限真源 | `dicts.py`（374 行）/ `permissions.py`（325 行） | — | KV 字典与编号规则、99 个权限点+菜单树+8 预置角色 |
| 合同编号 | `numbering.py`（52 行） | — | V1.0 合同编号（类型码+主体码+年月+序号） |
| ORM | `models.py`(267) / `models_auth.py`(164) / `models_master.py`(213) / `models_doc.py`(463) / `models_stock.py`(80) | 1187 | 39 张表 |
| 路由 | `routers/` 17 个模块（含工厂 `doc_routes.py`） | ~2,000 | 约 231 个端点 |
| 服务 | `services/` 15 个模块 | ~3,000 | 见下表 |

服务层模块（行数为实测）：`master_service.py` 800、`doc_service.py` 353、`print_service.py` 336、`print_template_service.py` 330、`posting_service.py` 326、`push_service.py` 315、`org_service.py` 191、`migrate_service.py` 164、`user_service.py` 159、`role_service.py` 142、`permission_service.py` 118、`numbering_service.py` 100、`backup_service.py` 93、`audit_service.py` 42。
（`posting_service`、`push_service`、`doc_service` 是移植风险最集中的三个，见 §8。）

**前端（`web/src`，61 个 .vue/.ts 文件）**：`layout/AppLayout.vue` + `components/`（15 个：6 个通用/主数据壳 + 9 个单据组件）+ `views/`（35 个页面）+ `router/index.ts`（50 条具名路由）+ `stores/auth.ts` + `api.ts`。

### 1.5 代码量级粗略统计

| 指标 | 数值 | 依据 |
|---|---|---|
| Python 文件数 / 行数 | 76 个 / 16,058 行 | `app/**/*.py`（含 tests、tools） |
| ├ 后端业务代码 | 48 个 / 9,385 行 | 排除 `tests/`、`tools/` |
| ├ 测试代码 | 24 个 / 6,034 行 | `app/tests/*.py` |
| └ 工具脚本 | 4 个 / 639 行 | `app/tools/*.py` |
| 前端文件数 / 行数 | 61 个 / 10,358 行 | `web/src/**/*.{vue,ts}` |
| Playwright E2E | 18 个文件（16 个 spec + `helpers.ts` + `global-setup.ts`） | `web/tests/e2e/` |
| SDD 文档 | 29 个 / 7,293 行 | `docs/*.md` |
| **ORM 表数** | **39**（37 个 `__tablename__` + 2 个关联 `Table`） | `app/models*.py` |
| **权限点数** | **99** | `app/permissions.py:13-128`（逐行计数） |
| **HTTP 端点（展开后）** | **约 231** | 见 §4 末尾的推导说明（115 个路由装饰器声明位；`doc_routes`/`master` 工厂按参数展开） |
| 预置角色数 | 8 | `app/permissions.py:210-270` |

---

## 2. 数据模型清单

### 2.0 全局约定（**移植时最关键的一节**）

1. **新增列一律可空、无外键**。这是 SQLite `ALTER TABLE ADD COLUMN` 的硬限制被反向固化成的项目约定：
   `app/db_migrate.py:5-7`、`app/models.py:8-12`、`docs/12-erp-system-design.md` §4.5（line 597）。
   后果：**MySQL DDL 里可以也应该补上真实 FK 与非空约束**，但必须先按"应用层已强制的语义"逐列确认，不能照抄 SQLite 的可空。
2. **审计字段口径**：`created_at` 用 `server_default=func.now()`；`updated_at` 用 `server_default=func.now(), onupdate=func.now()`。
   注意：`stocks.updated_at`（`app/models_stock.py:48-50`）**没有 `server_default`**，由过账服务显式赋值（`app/services/posting_service.py:80`）。
3. **操作人/姓名双写快照**：`created_by`(id) + `created_by_name`(姓名文本)、`handler_user_id` + `handler_name`、`operator_id` + `operator_name`。
   姓名快照是为了"账号停用后历史单据仍能显示姓名"（`app/services/doc_service.py:350-363`、`app/models_doc.py:74`）。
4. **软删除只存在于 3 处**：`contracts.deleted/deleted_at/deleted_reason`（`app/models.py:139-141`）、
   `attachments.deleted`（`app/models.py:233`）、8 类单据的 `deleted`（`app/models_doc.py:93`，注意**没有 `deleted_at`/`deleted_reason`**）。
   其余主数据靠 `status`/`enabled` 停用，不做物理删除（`docs/11-erp-requirements.md` BR-V2-15，line 576）。
5. **FK 实际覆盖度低**：真实 `ForeignKey` 只出现在少数列（合同行项、单据行项、库存、组织/账号主数据）；
   **大量引用列是裸 `Integer`**，例如 `contracts.customer_id`/`supplier_id`（`app/models.py:115-116`）、
   `DocumentMixin.contract_id`（`app/models_doc.py:77`）、`StockInOrder.supplier_id`（`app/models_doc.py:275`）、
   `StockOutOrder.customer_id`（`:309`）、`StockTake.generated_in_id/out_id`（`:344-347`）、
   `StockLedger.org_id/created_by`（`app/models_stock.py:72-73`）、`OrgUnit.leader_user_id`（`app/models_auth.py:53`）、
   `Warehouse.keeper_user_id`（`app/models_master.py:154`）、`PartyDraft.matched_id`（`app/models_master.py:208`）。
   → MySQL 侧建议**全部补 FK 或至少补索引**（否则数据范围/关联查询会全表扫）。
6. SQLAlchemy 的 `ondelete` 声明只在开 `PRAGMA foreign_keys=ON` 的 SQLite 下真正生效（`app/database.py:23-26`）。
   → MySQL 侧 InnoDB 默认强制，语义会**变强**，需确认业务上没有"故意留孤儿"的写法。
7. 复合/唯一约束一共只有 3 个显式声明：`uq_stock_prod_wh(product_id,warehouse_id)`、`contract_tag` 复合 PK、
   `role_permissions` 复合 PK；其余唯一性靠 `unique=True` 单列索引或应用层校验。

---

### 2.1 合同域（`app/models.py`）

#### 2.1.1 `contracts` — 合同（`app/models.py:99-162`）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | 自增 | 主键 |
| `contract_no` | String(64) | 否 | — | 合同编号（**唯一**，BR1） |
| `name` | String(255) | 否 | — | 合同名称 |
| `type` | String(16) | 否 | `"采购"` | 类型标签（MVP3 起存**标准类型名**，代码在 KV `contract_types`） |
| `party_a` | String(255) | 否 | `""` | 甲方（文本快照，历史兼容 BR2/Q6） |
| `party_b` | String(255) | 否 | `""` | 乙方（文本快照） |
| `sign_date` | Date | **是** | — | 签订日期 |
| `effective_date` | Date | 是 | — | 生效日期（可选） |
| `subject_matter` | Text | 否 | `""` | 标的物（有行项时自动改写为行项摘要，`contracts.py:359-361`） |
| `amount` | Numeric(14,2) | 否 | 0 | 合同金额（有行项时被 Σ行项覆盖，`contracts.py:355-358`） |
| `currency` | String(8) | 否 | `CNY` | 币种（不参与换算） |
| `subject_code` | String(8) | 是 | — | 我方主体码 ZC/YX（MVP3 编号用；**加列而来，无 FK**） |
| `customer_id` | Integer | 是 | — | 客户档案 id（销售合同甲方；**无 FK**，应用层校验 `contracts.py:124-134`） |
| `supplier_id` | Integer | 是 | — | 供应商档案 id（采购合同乙方；无 FK） |
| `paid_amount` | Numeric(14,2) | 否 | 0 | 累计已付（手工维护单一字段，BR3） |
| `has_warranty` | Boolean | 否 | False | 是否有质保金 |
| `warranty_amount` | Numeric(14,2) | 是 | — | 质保金金额（与比例二选一，自动互换） |
| `warranty_rate` | Numeric(10,4) | 是 | — | 质保金比例 % |
| `warranty_start` | Date | 是 | — | 质保生效日期 |
| `warranty_months` | Integer | 是 | — | 质保期限（月） |
| `warranty_end` | Date | 是 | — | 质保到期日（Q2 算法自动重算，`contracts.py:172-176`） |
| `warranty_released` | Boolean | 否 | False | 是否已释放 |
| `warranty_release_date` | Date | 是 | — | 释放日期 |
| `warranty_note` | Text | 是 | — | 质保备注 |
| `is_framework` | Boolean | 否 | False | 是否框架合同（BR6） |
| `parent_id` | Integer FK→`contracts.id` ON DELETE SET NULL | 是 | — | 所属框架合同（自引用） |
| `arrival_status` | String(16) | 否 | `"未到货"` | 到货状态（BR3/Q3） |
| `expected_arrival_date` | Date | 是 | — | 预计到货日期 |
| `status` | String(32) | 否 | `"内部审批中"` | 进度状态（BR4） |
| `owner_name` | String(64) | 是 | — | 经办人（**自由文本**，BR9/Q8） |
| `org_id` | Integer | 是 | — | 归属组织节点（数据范围；**无 FK**） |
| `created_by` | Integer | 是 | — | 创建人账号 id（无 FK） |
| `remark` | Text | 是 | — | 备注 |
| `deleted` | Boolean | 否 | False | 软删除标记（BR10/Q7） |
| `deleted_at` | DateTime | 是 | — | 删除时间（用于 30 天判定） |
| `deleted_reason` | Text | 是 | — | 删除原因（必填，接口层强制） |
| `created_at` / `updated_at` | DateTime | 否 | now() | 审计 |

约束与索引：`contract_no` UNIQUE + INDEX；`customer_id`/`supplier_id`/`parent_id`/`status`/`org_id`/`created_by`/`deleted` 各单列 INDEX（`app/models.py:103-140`）。
关系：`parent`/`children`（自引用）、`tags`（经 `contract_tag`）、`attachments`、`logs`、`items`（`cascade="all, delete-orphan"`，`lazy="selectin"`，按 `seq` 排序）。
派生属性：`payment_ratio = paid_amount / amount × 100`，`amount` 为 0 或 None 返回 `None`（`app/models.py:157-162`）。

#### 2.1.2 `contract_items` — 合同行项（`app/models.py:177-203`，MVP2 需求①）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | 自增 | — |
| `contract_id` | Integer FK→`contracts.id` ON DELETE CASCADE | 否 | — | 父合同（INDEX） |
| `seq` | Integer | 否 | 1 | 序号 |
| `item_type` | String(32) | 否 | `"采购"` | 行项类型（KV `item_types` 可配） |
| `name` | String(255) | 否 | `""` | 名称 |
| `spec` | String(255) | 否 | `""` | 规格型号 |
| `qty` | Numeric(12,3) | 否 | 0 | 数量 |
| `unit_price` | Numeric(14,4) | 否 | 0 | 单价 |
| `total` | Numeric(14,2) | 否 | 0 | 总价 = 数量×单价，**先 ROUND_HALF_UP 到 2 位**（`contracts.py:332`） |
| `remark` | Text | 是 | — | — |
| **`product_id`** | Integer FK→`products.id` | **是（DB）** | — | **应用层强制必填** ⚠️ 见 §2.8 |
| **`product_code`** | String(32) | 是（DB） | — | 物料编码快照，应用层强制 |
| **`product_name`** | String(128) | 是（DB） | — | 物料名称快照，应用层强制 |
| `created_at` | DateTime | 否 | now() | — |

索引：`contract_id`、`product_id`（`ix_contract_items_product_id`，`app/db_migrate.py:50`）。

#### 2.1.3 `tags` — 标签字典（`app/models.py:165-174`）

`id` PK；`name` String(64) **UNIQUE + INDEX**（全局去重 BR7）；`color` String(16) 可空；`builtin` Boolean 默认 False；`created_at`。
新建标签时按 `Tag` 总数取色板 `["#409eff","#67c23a","#e6a23c","#f56c6c","#909399"][count % 5]`（`contracts.py:381`）。

#### 2.1.4 `contract_tag` — 合同↔标签 关联（`app/models.py:90-96`）

`contract_id` FK→`contracts.id` CASCADE（复合 PK 的一部分）；`tag_id` FK→`tags.id` CASCADE（复合 PK 的一部分）；
`auto` Boolean NOT NULL DEFAULT 0（`server_default=text("0")`）—— 区分"自动附加"（框架标签/类型标签）与手工标签（MVP2 需求④，`contracts.py:412-449`）。

#### 2.1.5 `change_logs` — 字段级变更历史（`app/models.py:241-267`）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | — | — |
| `contract_id` | Integer FK→`contracts.id` CASCADE | **是**（V1.0 为 NOT NULL，M2 起放宽，`db_migrate._relax_change_logs`） | — | INDEX |
| `field_name` | String(64) | 否 | — | 字段名；特殊值 `_summary`/`_items`/`_tags`/`_origin`/`status`/`附件`/`deleted` |
| `old_value` / `new_value` | Text | 是 | — | 前后值（统一转字符串） |
| `note` | Text | 是 | — | 备注/原因 |
| `source` | String(16) | 否 | `"manual"` | manual / auto（自动计算的字段如 `warranty_end`、`amount`、`subject_matter` 记 auto） |
| `operator_id` | Integer | 是 | — | 操作人 id（**V1.0 期间为空，前端显示"—"**，AC-V2-37） |
| `operator_name` | String(64) | 是 | — | 操作人姓名快照 |
| `object_type` | String(32) | 是 | — | 对象类型（合同写 `contract`，单据写 `purchase_order` 等） |
| `object_id` | Integer | 是 | — | 对象 id |
| `created_at` | DateTime | 否 | now() | — |

索引：`contract_id`、`ix_change_logs_object(object_type, object_id)`（`app/db_migrate.py:49`）。
迁移补齐：`UPDATE change_logs SET object_type='contract', object_id=contract_id WHERE object_type IS NULL AND contract_id IS NOT NULL`（`app/db_migrate.py:102-106`）。

---

### 2.2 主数据域（`app/models_master.py`）

> 域级约定：**主数据不参与数据范围隔离**（`app/services/permission_service.py:119-124` 显式说明"主数据不做隔离，调用方不要对它使用本函数"）；
> 档案只做启停用，删除接口仅"无任何引用"时放行（`app/models_master.py:11-14`）。

#### 2.2.1 `customers` — 客户（`app/models_master.py:45-68`）

`id` PK；`code` String(32) **UNIQUE+INDEX**；`name` String(128) **UNIQUE+INDEX**；`short_name` String(64) 可空；
`tax_no` String(32)；`contact_name` String(64)；`contact_phone` String(32)；`address` String(255)；
`bank_name` String(128)；`bank_account` String(64)；`credit_limit` Numeric(14,2) 可空；`level` String(8) 可空（A/B/C/D）；
`status` String(16) 默认 `"enabled"`（INDEX）；`remark` Text；`created_by` Integer 可空；`created_at`/`updated_at`。

#### 2.2.2 `suppliers` — 供应商（`app/models_master.py:71-96`）

同 `customers`，另加 `supply_scope` String(255)（供货范围）、`payment_days` Integer（账期天数，**应用层禁止负数**，`master_service.py:233`）。
**`short_name` 对供应商是应用层必填**（BR-V2.2-01，`master_service.py:208-219, 243-245`），客户仍选填。

#### 2.2.3 `product_types` — 商品类型树（`app/models_master.py:99-125`）

`id` PK；`parent_id` FK→自引用 SET NULL（INDEX）；`code` String(32) 可空（**物料编码前缀来源**）；
`name` String(64) 非空；`path` String(255) 默认 `"/"`（**物化路径**，INDEX）；`level` Integer 默认 1；
`sort` Integer 默认 0；`enabled` Boolean 默认 True；`remark`；`created_at`/`updated_at`。
规则：`path` 形如 `/1/5/`；**只有叶子节点可挂物料**（`master_service.py:390-401`）；最大层级 `MAX_TYPE_LEVEL = 5`（`master_service.py:36`）；
同父下名称唯一（`master_service.py:403-410`）；有子类型或有物料时禁止删除（`master_service.py:502-506`）。

#### 2.2.4 `uoms` — 计量单位（`app/models_master.py:128-142`）

`id` PK；`code` String(16) **UNIQUE+INDEX**；`name` String(32) 非空；`decimals` Integer 默认 2（**取值范围 0~4**，`UOM_DECIMALS_RANGE=(0,4)`，`master_service.py:37, 558-567`）；
`enabled` Boolean 默认 True；`remark`；`created_at`/`updated_at`。被物料引用后禁止删除（`master_service.py:617-619`）。

#### 2.2.5 `warehouses` — 仓库（`app/models_master.py:145-160`）

`id` PK；`code` String(32) **UNIQUE+INDEX**；`name` String(64) 非空；`address` String(255)；
`keeper_user_id` Integer 可空（**无 FK**）；`enabled` Boolean 默认 True；`remark`；`created_at`/`updated_at`。
有库存或单据记录时禁止删除（`master_service.py:710-712`）。

#### 2.2.6 `products` — 物料（`app/models_master.py:163-191`）

`id` PK；`code` String(32) **UNIQUE+INDEX**（可自动生成或手工）；`name` String(128) 非空；
`spec` String(128)；`product_type_id` Integer **FK→`product_types.id` NOT NULL + INDEX**；
`uom_id` Integer **FK→`uoms.id` NOT NULL + INDEX**；`brand` String(64)；`barcode` String(64)；
`default_price` Numeric(14,4) 默认 0（禁止负数）；`safety_stock` Numeric(14,3) 可空（禁止负数，看板预警用）；
`status` String(16) 默认 `"enabled"`（INDEX）；`remark`；`created_by`；`created_at`/`updated_at`。
关系：`product_type`、`uom`（都 `lazy="joined"`）。有库存或单据记录时禁止删除（`master_service.py:851-853`）。

#### 2.2.7 `party_drafts` — 历史甲乙方迁移草案（`app/models_master.py:194-213`，T-V2-14）

`id` PK；`party_type` String(16) 非空 INDEX（`customer`/`supplier`）；`raw_name` String(255) 非空 INDEX；
`contract_count` Integer 默认 0；`status` String(16) 默认 `"pending"` INDEX（pending/claimed/ignored）；
`matched_id` Integer 可空（**无 FK**，认领后的档案 id）；`remark`；`created_at`/`updated_at`。

---

### 2.3 采购域 / 2.4 销售域 / 2.5 库存域 — 单据公共字段

> 8 类单据（采购申请/采购单/销售申请/销售订单/入库单/出库单/盘点单/调拨单）由
> `DocMixin`（`app/models_doc.py:62-97`）+ `DocItemMixin`（`app/models_doc.py:100-118`）承载公共字段，各表只声明特有字段。
> **移植建议**：Java 侧用 `@MappedSuperclass` 抽象基类 + 每表独立实体，或直接展开（MyBatis-Plus 对继承支持需谨慎）。

#### 2.3.1 `DocMixin` 表头公共字段（8 张主表都有）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | 自增 | — |
| `doc_no` | String(32) | 否 | — | 单号（**UNIQUE + INDEX**） |
| `doc_date` | Date | 否 | `date.today` | 单据日期 |
| `status` | String(16) | 否 | `"draft"` | draft/submitted/approved/completed/voided（INDEX） |
| `org_id` | Integer | 是 | — | 归属组织（数据范围，**无 FK**，INDEX） |
| `created_by` | Integer | 是 | — | 创建人 id（无 FK，INDEX） |
| `created_by_name` | String(64) | 是 | — | 创建人姓名快照 |
| `handler_user_id` | Integer | 是 | — | 经办人 id（无 FK） |
| `handler_name` | String(64) | 是 | — | 经办人姓名快照（V2.1/N1 加列，`db_migrate.py:35-41`） |
| `remark` | Text | 是 | — | 备注 |
| `contract_id` | Integer | 是 | — | 关联合同（**无 FK**，INDEX；可空非强制 BR-V2-07） |
| `contract_no` | String(64) | 是 | — | 合同编号快照 |
| `source_doc_type` | String(24) | 是 | — | 下推来源类型 |
| `source_doc_id` | Integer | 是 | — | 下推来源 id |
| `source_doc_no` | String(32) | 是 | — | 下推来源单号 |
| `submitted_by` / `submitted_at` | Integer / DateTime | 是 | — | 提交痕迹 |
| `approved_by` / `approved_at` | Integer / DateTime | 是 | — | 审核痕迹 |
| `voided_by` / `voided_at` / `void_reason` | Integer / DateTime / Text | 是 | — | 作废痕迹 |
| `posted` | Boolean | 否 | False | 库存过账标记（**幂等依据**） |
| `deleted` | Boolean | 否 | False | 软删除（**无 deleted_at/deleted_reason**，与合同不一致） |
| `created_at` / `updated_at` | DateTime | 否 | now() | 审计 |

#### 2.3.2 `DocItemMixin` 行项公共字段（8 张行项表都有）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | 自增 | — |
| `seq` | Integer | 否 | 1 | 序号 |
| `product_id` | Integer **FK→`products.id` NOT NULL + INDEX** | 否 | — | 物料（**与合同行项相反：这里是 DB 级非空**） |
| `product_code` | String(32) | 否 | `""` | 物料编码快照 |
| `product_name` | String(128) | 否 | `""` | 物料名称快照 |
| `spec` | String(128) | 是 | — | 规格快照 |
| `uom_name` | String(32) | 是 | — | 单位名快照 |
| `uom_decimals` | Integer | 是 | — | 单位小数位快照 |
| `qty` | Numeric(16,3) | 否 | 0 | 数量 |
| `unit_price` | Numeric(14,4) | 否 | 0 | 单价 |
| `amount` | Numeric(16,2) | 否 | 0 | 金额 = qty × unit_price，`quantize(0.01, ROUND_HALF_UP)`（`doc_service.py:135`） |
| `warehouse_id` | Integer | 是 | — | 行级仓库（覆盖表头；**无 FK**） |
| `warehouse_name` | String(64) | 是 | — | 仓库名快照 |
| `src_item_id` | Integer | 是 | — | 下推来源行项 id（**无 FK**） |
| `remark` | Text | 是 | — | — |

每张行项表另有 `doc_id`：`Integer FK→父表.id ON DELETE CASCADE` + INDEX（各表内声明，如 `app/models_doc.py:144-146`）。

#### 2.3.3 采购域

| 表 | 类 | 特有字段（`app/models_doc.py`） |
|---|---|---|
| `purchase_requests` | `PurchaseRequest`（:123-138，`doc_type="purchase_request"`，`direction=0`，`label="采购申请单"`） | `request_dept_id` Integer 可空；`need_date` Date 可空；`suggest_supplier_id` Integer 可空（无 FK）；`purpose` Text 可空。**无 `total_amount` 列**，列表金额由 `total_amount_of(items)` 现算（`doc_service.py:382-383`） |
| `purchase_request_items` | `PurchaseRequestItem`（:141-151） | `ordered_qty` Numeric(16,3) 默认 0 —— 已下推数量（下推量校验基准） |
| `purchase_orders` | `PurchaseOrder`（:156-177，`direction=0`，`label="采购单"`） | `supplier_id` Integer **FK→`suppliers.id` NOT NULL + INDEX**；`supplier_name` String(128) NOT NULL 默认 `""`（快照）；`purchase_dept_id` Integer 可空；`expected_arrival_date` Date 可空；`settle_type` String(24) 可空；`currency` String(8) 默认 CNY；`total_amount` Numeric(16,2) 默认 0；`receipt_warehouse_id` Integer 可空（无 FK，默认收货仓） |
| `purchase_order_items` | `PurchaseOrderItem`（:180-190） | `received_qty` Numeric(16,3) 默认 0 —— 已入库数量（过账回写） |

#### 2.3.4 销售域

| 表 | 类 | 特有字段（`app/models_doc.py`） |
|---|---|---|
| `sales_requests` | `SalesRequest`（:195-210，`direction=0`，`label="销售申请单"`） | `customer_id` Integer 可空（**无 FK**，与采购单不对称）；`customer_name_text` String(128) 可空；`sales_dept_id` Integer 可空；`expect_delivery_date` Date 可空。**无 `total_amount` 列** |
| `sales_request_items` | `SalesRequestItem`（:213-221） | `ordered_qty` Numeric(16,3) 默认 0 |
| `sales_orders` | `SalesOrder`（:226-249，`label="销售订单"`） | `customer_id` Integer **FK→`customers.id` NOT NULL + INDEX**；`customer_name` String(128) 快照；`sales_dept_id`；`delivery_date`；`delivery_address` String(255)；`contact_name` String(64)；`contact_phone` String(32)；`ship_warehouse_id` Integer 可空（无 FK）；`currency` String(8) 默认 CNY；`total_amount` Numeric(16,2) |
| `sales_order_items` | `SalesOrderItem`（:252-260） | `shipped_qty` Numeric(16,3) 默认 0 —— 已出库数量（过账回写） |

#### 2.3.5 库存域 — 单据

| 表 | 类 | 特有字段 |
|---|---|---|
| `stock_in_orders` | `StockInOrder`（:265-284，`direction=1`，`label="入库单"`） | `warehouse_id` FK→`warehouses.id` NOT NULL + INDEX；`warehouse_name` String(64) NOT NULL 快照；`in_type` String(24) NOT NULL 默认 `"采购入库"`（取值 `["采购入库","退货入库","其他入库"]`，`models_doc.py:57`）；`supplier_id` Integer 可空（无 FK）；`supplier_name` String(128) 可空；`total_amount` Numeric(16,2) |
| `stock_in_order_items` | `StockInOrderItem`（:287-294） | 仅 `doc_id`，字段全来自 `DocItemMixin` |
| `stock_out_orders` | `StockOutOrder`（:299-318，`direction=-1`，`label="出库单"`） | `warehouse_id` FK NOT NULL+INDEX；`warehouse_name` 快照；`out_type` String(24) NOT NULL 默认 `"销售出库"`（`["销售出库","领用出库","其他出库"]`，`:58`）；`customer_id`/`customer_name` 可空（无 FK）；`total_amount` |
| `stock_out_order_items` | `StockOutOrderItem`（:321-328） | 仅 `doc_id` |
| `stock_takes` | `StockTake`（:333-352，`direction=0`，`label="盘点单"`） | `warehouse_id` FK NOT NULL+INDEX；`warehouse_name` 快照；`take_type` String(16) NOT NULL 默认 `"full"`（full/partial，`:59`）；`scope_note` Text 可空；`generated_in_id`/`generated_in_no`（盘盈入库单）；`generated_out_id`/`generated_out_no`（盘亏出库单）。**无 `total_amount` 列**（`stock.py:204` 写的是瞬时属性，不落库） |
| `stock_take_items` | `StockTakeItem`（:355-366） | `book_qty` Numeric(16,3) 默认 0（账面，生成时固定，只读）；`actual_qty` Numeric(16,3) 默认 0（实盘）；`diff_qty` Numeric(16,3) 默认 0（= 实盘 − 账面）；`diff_reason` Text |
| `stock_transfers` | `StockTransfer`（:371-399，`direction=0`，`label="调拨单"`，**V2.1/N13**） | `from_warehouse_id` FK→`warehouses.id` NOT NULL+INDEX；`from_warehouse_name` 快照；`to_warehouse_id` FK NOT NULL+INDEX；`to_warehouse_name` 快照 |
| `stock_transfer_items` | `StockTransferItem`（:402-411） | 仅 `doc_id`；**不使用** `warehouse_id`（仓库由表头两仓决定，`models_doc.py:403`） |

#### 2.3.6 `stocks` — 库存结存（`app/models_stock.py:38-53`）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | — | — |
| `product_id` | Integer FK→`products.id` NOT NULL + INDEX | 否 | — | — |
| `warehouse_id` | Integer FK→`warehouses.id` NOT NULL + INDEX | 否 | — | — |
| `qty` | Numeric(16,3) | 否 | 0 | 结存数量（**只记数量不记成本**，口径 D4） |
| `updated_at` | DateTime | 否 | **无 server_default** | 过账服务显式赋值（`posting_service.py:80`） |

**唯一约束**：`UniqueConstraint("product_id","warehouse_id", name="uq_stock_prod_wh")`（`app/models_stock.py:42`）。
一致性不变式见 §3.10。

#### 2.3.7 `stock_ledger` — 库存流水（`app/models_stock.py:56-80`）

`id` PK；`product_id` FK NOT NULL+INDEX；`warehouse_id` FK NOT NULL+INDEX；
`biz_type` String(24) NOT NULL（取值 `["采购入库","退货入库","盘盈入库","其他入库","销售出库","领用出库","盘亏出库","其他出库","调拨出库","调拨入库"]`，红冲加前缀 `红冲-`，`models_stock.py:29-35, 84`）；
`doc_type` String(24) NOT NULL；`doc_id` Integer NOT NULL（**无 FK**）；`doc_no` String(32) NOT NULL；
`src_doc_no` String(32) 可空；`qty_change` Numeric(16,3) NOT NULL（**正入负出**）；
`qty_after` Numeric(16,3) NOT NULL（变动后结存快照，便于对账/下钻）；
`unit_price` Numeric(14,4) 可空（**预留成本层，本期只用于"货品总额度"聚合**）；
`org_id` Integer 可空（无 FK）；`created_by` Integer 可空（无 FK）；`remark` Text 可空；
`created_at` DateTime NOT NULL + INDEX。
设计文档另列了两个索引名 `idx_ledger_prod_wh(product_id,warehouse_id,id)`、`idx_ledger_doc(doc_type,doc_id)`（`docs/12-erp-system-design.md:494`）——
⚠️ **但在 `models_stock.py` 里没有对应的 `Index`/`__table_args__` 声明**，属文档与模型不一致（见附录 A）。

---

### 2.6 权限域（`app/models_auth.py`）

#### 2.6.1 `org_units` — 组织架构（`app/models_auth.py:41-69`）

`id` PK；`parent_id` FK→自引用 SET NULL（INDEX）；`name` String(64) 非空；`code` String(32) 可空；
`unit_type` String(16) 非空默认 `"部门"`（取值 `["公司","部门","岗位"]`，`org_service.py:19`）；
`leader_user_id` Integer 可空（**无 FK**）；`phone` String(32)；
`path` String(255) 非空默认 `"/"`（**物化路径**，INDEX）；`level` Integer 默认 1；`sort` Integer 默认 0；
`enabled` Boolean 默认 True；`created_at`/`updated_at`。
规则：最大层级 `MAX_LEVEL = 5`（`org_service.py:20`）；同级同名禁止（`org_service.py:123-129`）；
删除需"无子节点且无归属账号"（`org_service.py:236-240`，AC-V2-07）。

#### 2.6.2 `users` — 账号（`app/models_auth.py:72-100`）

| 字段 | 类型 | 空 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | — | — |
| `username` | String(64) | 否 | — | 登录名（**UNIQUE+INDEX**） |
| `password_hash` | String(255) | 否 | — | 格式 `pbkdf2_sha256$120000$salt_b64$hash_b64`（`app/security.py:20-21, 45-46`） |
| `real_name` | String(64) | 否 | `""` | 真实姓名 |
| `org_id` | Integer FK→`org_units.id` SET NULL | 是 | — | 所属组织（INDEX） |
| `phone` / `email` | String(32) / String(128) | 是 | — | — |
| `status` | String(16) | 否 | `"enabled"` | enabled/disabled（INDEX） |
| `is_superadmin` | Boolean | 否 | False | 绕过权限与数据范围校验 |
| `must_change_pwd` | Boolean | 否 | False | 首登强制改密（AC-V2-08） |
| `last_login_at` | DateTime | 是 | — | — |
| `remark` | Text | 是 | — | — |
| `created_by` | Integer | 是 | — | 无 FK |
| `created_at`/`updated_at` | DateTime | 否 | now() | — |

关系：`roles`（经 `user_roles`，`lazy="selectin"`）、`org`（`lazy="joined"`）。
规则（`app/services/user_service.py`）：登录名/姓名必填且 ≤64（:127-129）；登录名唯一（:131）；初始密码必填（:135）；
不能停用当前登录账号（:117）；不能停用最后一个超管（:119）；密码强度取自系统参数 `pwd_min_length`，且须含字母与数字（:92-99，`security.py:62-...`）。

#### 2.6.3 `roles` — 角色（`app/models_auth.py:103-127`）

`id` PK；`code` String(32) **UNIQUE+INDEX**；`name` String(64) 非空；
`data_scope` String(16) 非空默认 `"SELF"`（SELF/DEPT/DEPT_SUB/ALL）；`remark` Text 可空；
`builtin` Boolean 默认 False；`enabled` Boolean 默认 True；`created_at`/`updated_at`。
派生属性 `perm_codes`（取 `role_permissions` 的 `perm_code` 列表）。
规则（`app/services/role_service.py`）：编码必填 ≤32、字母开头、仅字母数字下划线（:82-87）；编码唯一（:117）；
`sysadmin` 不可停用（:143, :161）、权限不可全部清空（:153）；内置角色不可删（:169）；有账号绑定时不可删（:171）。

#### 2.6.4 `user_roles` — 账号↔角色（`app/models_auth.py:30-35`）

`user_id` FK→`users.id` CASCADE（复合 PK）；`role_id` FK→`roles.id` CASCADE（复合 PK）。

#### 2.6.5 `role_permissions` — 角色↔权限点（`app/models_auth.py:130-140`）

`role_id` FK→`roles.id` CASCADE（复合 PK）；`perm_code` String(64)（复合 PK）。
**权限点的定义真源是代码常量 `app/permissions.py`，不在库里**（`app/models_auth.py:131`、`permissions.py:1-9`）。

#### 2.6.6 `operation_logs` — 操作日志（`app/models_auth.py:143-164`）

| 字段 | 类型 | 空 | 默认 | 索引 |
|---|---|---|---|---|
| `id` | Integer PK | 否 | — | — |
| `user_id` | Integer FK→`users.id` SET NULL | 是 | — | INDEX |
| `username` / `real_name` | String(64) | 是 | — | — |
| `module` | String(32) | 否 | `""` | INDEX |
| `action` | String(32) | 否 | `""` | INDEX |
| `object_type` / `object_id` / `object_no` | String(32) / Integer / String(64) | 是 | — | — |
| `result` | String(16) | 否 | `"success"` | — |
| `detail` | Text | 是 | — | — |
| `ip` | String(64) | 是 | — | 取 `X-Forwarded-For` 首段，回落直连（`audit_service.py:17-27`） |
| `created_at` | DateTime | 否 | now() | INDEX |

与 `change_logs` 的职责区分：**操作日志记"动作"，变更历史记"字段级前后值"**（`audit_service.py:6-7`）。

---

### 2.7 文档/附件/模板/配置域

#### 2.7.1 `attachments` — 附件元数据（`app/models.py:215-238`）

`id` PK；`contract_id` Integer FK→`contracts.id` CASCADE，**可空**（V2.0/T-V2-27 起由 NOT NULL 放宽，`db_migrate._relax_attachments`），INDEX；
`file_name` String(255) 非空（原始名）；`stored_path` String(512) 非空（磁盘相对路径 `{object_type}/{object_id}/{uuid}.{ext}`）；
`content_type` String(128)；`size_bytes` Integer 默认 0；`uploaded_at` DateTime 默认 now()；
`deleted` Boolean 默认 False；`object_type` String(32) 可空（无 INDEX）；`object_id` Integer 可空（无 INDEX）。
上传约束（`app/routers/attachments.py`）：白名单 `{.pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg,.gif,.txt}`、单文件 ≤20MB（`app/config.py:42-43`）、
文件名 `uuid4().hex + 后缀`（防路径穿越/覆盖，`:126-127`）、超限 413 且删除半成品。

#### 2.7.2 `print_templates` — 单据打印模板（`app/models_doc.py:437-463`，V2.2/BR-V2.2-02）

`id` PK；`kind` String(32) **UNIQUE+INDEX**（一种单据类型一条记录）；`config` Text 非空默认 `"{}"`（**JSON 文本**）；
`updated_by` Integer 可空；`updated_by_name` String(64) 可空；`created_at`/`updated_at`。
用 JSON 而非逐列的理由：模板是排版数据、字段集合会随单据类型演进（`app/models_doc.py:446-450`）。

#### 2.7.3 `kv_settings` — 系统字典/配置（`app/models.py:206-212`）

`key` String(64) **PK**；`value` Text 非空默认 `""`（JSON 文本）。已使用的键（`app/dicts.py`）：
`item_types`、`contract_types`、`subjects`、`sys_params`、`number_rules`。

---

### 2.8 ⚠️ 应用层强制而 DB 层可空的字段（汇总）

> 这一节是 SQLite 迁移限制的**副作用清单**。移植物目标是 MySQL，**可空性可以改回 NOT NULL**，
> 但改之前必须确认应用层每一条写入路径都真的会填值，否则会出现运行期约束报错。

| # | 字段 | DB 现状 | 应用层强制点 | 依据 |
|---|---|---|---|---|
| 1 | `contract_items.product_id` / `product_code` / `product_name` | 可空、`product_id` 有 FK 但**允许 NULL** | 新建/编辑/导入**三处**强制；未选物料 422「行项第 N 行必须选择物料档案」 | `app/models.py:192-200`；`app/routers/contracts.py:283`、`:320`、`:337`；`docs/06-mvp2-adjustments.md:19,27`；BR-V2.1-02（`docs/22-v2.1-requirements.md:106`） |
| 2 | `contracts.customer_id` / `supplier_id` | 可空、**无外键** | 传了就校验档案存在（422「客户档案不存在/供应商档案不存在」）；按类型映射（PUR→乙方=供应商，SAL→甲方=客户） | `app/models.py:114-116`；`contracts.py:117-145`；`docs/11-erp-requirements.md:475-482` |
| 3 | `contracts.org_id` / `created_by` | 可空、无外键 | 创建时按当前用户 `org_id`/`id` 快照 | `app/models.py:135-137`；`contracts.py:831`（另一处为 doc 工厂 `doc_routes.py:57-58`） |
| 4 | 8 类单据 `contract_id` | 可空、无外键 | 关联时校验合同未删除并写 `contract_no` 快照（422「关联合同不存在或已停用」） | `app/models_doc.py:76-78`；`doc_service.py:264-280` |
| 5 | 8 类单据 `org_id` / `created_by` / `created_by_name` | 可空、无外键 | 创建时快照当前用户 | `doc_routes.py:57-59`；BR-V2-18（`docs/11-erp-requirements.md:802`） |
| 6 | 8 类单据 `handler_name` | 可空（V2.1 加列） | `handler_snapshot()` 由 `handler_user_id` 解析姓名；未指定则双空 | `db_migrate.py:34-41`；`doc_service.py:350-363`；BR-V2.1-01 |
| 7 | 行项 `warehouse_id` / `warehouse_name` | 可空、无外键 | 行级为空时回落表头仓库；两者都空且要过账则 422「行 N：缺少仓库」 | `app/models_doc.py:115-116`；`posting_service.py:64-66`；`doc_service.py:127-130` |
| 8 | 行项 `src_item_id` | 可空、无外键 | 下推时写入来源行 id，过账回写时据此匹配 | `app/models_doc.py:117`；`push_service.py:310-320` |
| 9 | `change_logs.operator_id` / `operator_name` / `object_type` / `object_id` | 可空、无外键 | 写日志时从 `db.info["user"]` 取操作人 | `db_migrate.py:24-29`；`doc_service.py:50-62` |
| 10 | `change_logs.contract_id` | **由 NOT NULL 放宽为可空** | 单据日志不填 contract_id，靠 object_type/object_id | `db_migrate.py:139-143`；AC-V2-37 |
| 11 | `attachments.object_type` / `object_id` | 可空、无索引 | 上传前校验对象存在（合同或 7 类单据） | `app/models.py:234-236`；`attachments.py:83-95` |
| 12 | `attachments.contract_id` | **由 NOT NULL 放宽为可空** | 合同附件才写，单据附件为 NULL | `db_migrate.py:146-150` |
| 13 | `stock_ledger.org_id` / `created_by` / `unit_price` | 可空、无外键 | 过账时写入 doc.org_id / 当前用户 / 行单价 | `app/models_stock.py:71-73`；`posting_service.py:87-88` |
| 14 | `stocks.updated_at` | NOT NULL 但**无 server_default** | 过账服务显式 `datetime.now()` | `app/models_stock.py:48-50`；`posting_service.py:80` |
| 15 | `customers.created_by` / `suppliers.created_by` / `products.created_by` | 可空、无外键 | 创建时写当前用户 | `app/models_master.py:64, 92, 184` |
| 16 | `suppliers.short_name` | DB 可空 | **应用层必填**（新增缺失即 422；修改提交空值即 422；长度 ≤64）；客户不受影响 | `app/services/master_service.py:208-219, 243-245`；BR-V2.2-01（`docs/25-v2.2-adjustments.md` §3.3） |
| 17 | `org_units.leader_user_id` / `warehouses.keeper_user_id` / `party_drafts.matched_id` / 各类 `*_dept_id` / `receipt_warehouse_id` / `ship_warehouse_id` / `generated_in_id` / `generated_out_id` | 可空、无外键 | 仅存 id，应用层反查名称；`generated_*_no` 与之成对写入 | `app/models_auth.py:53`；`app/models_master.py:154, 208`；`app/models_doc.py:130, 165, 172, 204, 240, 344-347` |

**反例（DB 层真的非空）**：8 类单据行项的 `product_id` / `product_code` / `product_name` / `qty` / `unit_price` / `amount`；
采购单 `supplier_id`（FK 非空）、销售订单 `customer_id`（FK 非空）；入库/出库/盘点/调拨的 `warehouse_id`、`in_type`/`out_type`、`take_type`。
→ 这几处是**业务硬约束**，MySQL 侧必须保留 NOT NULL。

---

## 3. 状态机与业务规则

> 编号体系：`BR1~BR13`（V1.0 合同域，`docs/01-requirements.md` §4）、`BR-V2-01~20`（V2.0 单据通用，`docs/11-erp-requirements.md` §12 line 785-804）、
> `BR-V2.1-01~11`（V2.1 增量，`docs/22-v2.1-requirements.md:105-115`）、`BR-V2.2-01~02`（V2.2，`docs/25-v2.2-adjustments.md`）。
> 每条给"参考仓库位置 → 目标侧落点"。

### 3.1 合同进度状态流转（BR4）

- **状态集合**（顺序仅为默认流转参考，**不强制按序**）：
  `["内部审批中","集团审批中","已签订","付款中","发货","到货","已终止"]`，默认 `"内部审批中"`（`app/models.py:44-45`）。
- **任何状态下经办人都可把状态改成其他状态**（存在退回/跳步/回退的真实场景）；每次变更写 `change_logs`（时间、原状态→新状态、备注可选）——`docs/01-requirements.md:90-91`。
- **Q4 决策**：新增 `已终止/作废`，标记时**必填终止原因**；状态字典可增删自定义状态（`docs/01-requirements.md:89, 268`）。
- 服务端校验：`PUT /api/contracts/{id}` 时 `status` 必须 ∈ `STATUSES`，否则 422「无效状态: X」（`app/routers/contracts.py:786-787`）。
- 状态与到货**互相独立**（`docs/01-requirements.md:84`）——不要把它写成"发货→到货"的强约束。
- 已到货 ≠ 合同结束：质保仍在跟踪，须标记 `质保已释放` 才闭环（`docs/01-requirements.md:92`）。
- **无状态机守卫**：与单据不同，合同状态没有 `_ensure_status` 式的前置校验，只有枚举校验。移植时不要"顺手加严"。

### 3.2 到货状态（BR3/Q3）

- 状态集 `["未到货","部分到货","已到货"]`，默认 `"未到货"`（`app/models.py:48-49`）。
- 配套字段：`expected_arrival_date`（预计到货日期）+ 备注（`remark`）。
- 到货状态与 `status` 字段**无联动逻辑**（全库无代码把两者同步，`app/routers/contracts.py` 未出现任何 `arrival_status` 联动）。

### 3.3 质保金到期算法（BR5/Q2）—— **必须逐字复刻**

**状态集**：`未处理 / 已释放`（`warranty_released` + `warranty_release_date` + `warranty_note`）。

**`compute_warranty_end(start, months)` 精确口径**（`app/models.py:77-85`）：

```text
months = max(1, months)                       # 期限至少 1 个月
target = add_months(start, months - 1)        # 生效日 + (期限-1) 个月，日归一到当月有效范围
warranty_end = 该 target 年月的最后一天        # calendar.monthrange(y, m)[1]
```

`add_months`（`app/models.py:68-74`）：按"年-月进位、日取 `min(原日, 目标月最大日)`"。

**示例（可直接做单测）**：

| 生效日 | 期限 | 结果 | 说明 |
|---|---|---|---|
| 2025-06-01 | 12 | **2026-05-31** | 官方示例（`app/models.py:80`、`docs/01-requirements.md:96, 209`） |
| 2026-09-01 | 12 | 2027-08-31 | 月份进位 |
| 2026-08-01 | 5 | 2026-12-31 | — |
| 2026-01-31 | 1 | 2026-01-31 | months=1 → target=start → 取 1 月最后一天 |

**触发时机**：仅在 `PUT /api/contracts/{id}`（编辑）时重算，条件 `contract.has_warranty and warranty_start and warranty_months`；
重算时若与库值不同，**先写 `change_logs`（source="auto"）再改值**（`app/routers/contracts.py:171-176`）。
⚠️ 新增（POST）路径**不调用**该重算——前端必须自己带 `warranty_end`（附录 A 记为待确认）。

**金额/比例互换**（BR5、AC-14，`app/routers/contracts.py:177-182`）：
- 有 `warranty_rate` 无 `warranty_amount` → `amount × rate / 100`，`quantize(0.01)`；
- 有 `warranty_amount` 无 `warranty_rate` → `amount_amount / amount × 100`，`quantize(0.0001)`；
- 前提都是 `has_warranty and amount`；关闭 `has_warranty` 时清空 7 个质保字段并逐个留痕（`:157-163`）。

**预警规则**（BR5 + 看板）：
- `即将到期`：`warranty_end >= today AND warranty_end <= today + window`（默认 `window=30`，取系统参数 `warranty_window_days`）；
- `已到期`：`warranty_end < today`；
- 两者都要求 `has_warranty=True AND warranty_released=False AND warranty_end IS NOT NULL AND deleted=False`；
- 看板每区最多 100 条，按 `warranty_end` 升序（`app/routers/dashboard.py:192-216`）。
- 已释放后不再进入提醒（`docs/01-requirements.md:97`、AC-08）。

### 3.4 付款比例（BR3/Q1）

- 口径：**付款比例 = 累计已付 ÷ 合同金额 × 100%**，系统自动计算显示；**质保金不从此口径扣除**（`docs/01-requirements.md:80`、Q1 line 265）。
- 实现：`Contract.payment_ratio` 属性，`amount` 为 `None` 或 0 时返回 `None`（`app/models.py:157-162`）；导出时 `round(..., 2)`（`app/routers/export.py:81`）。
- **不做逐笔付款**：只有一个手工字段 `paid_amount`。
- 校验口径：`paid_amount <= amount` 时正常录入；**超出时给警告但允许保存**（防"质保金另计、总额超出"），并在变更历史留痕（`docs/01-requirements.md:83`）。
  ⚠️ 参考仓库代码中**未见**该"警告"的服务端实现（`contracts.py` 无 amount/paid 大小比较），属前端提示或未实现 → 附录 A。
- 有质保金时另给提示行：`应付中仍含未付质保金 ¥X（占合同金额 Y%），如按约定到期后支付，请注意与已付口径区分。`（`docs/01-requirements.md:82`）。
- 看板的汇总口径：`paid_sum / amount_sum × 100`（`quantize(0.01)`），`amount_sum=0` 返回 `None`（`dashboard.py:168-171`）。

### 3.5 框架合同一对多（BR6 + MVP2 需求④⑤）

- `is_framework` 标记；子合同记 `parent_id`；**一个子合同只能归属一个框架**（`docs/01-requirements.md:100-105`）。
- 绑定守卫（`app/routers/contracts.py:463-474` `_assert_parent_allowed`）：
  - 自己 `is_framework=True` 时**不能再挂到别的框架下** → 422「框架合同不能再挂到其他框架下」；
  - 父不存在或已停用 → 422；
  - 父 `is_framework=False` → 422「只能挂到框架合同（is_framework=是）下」。
- 取消框架标记时若仍有未删除子合同 → 422「框架下仍有子合同，请先解除子合同」（`:780-785`）。
- 已成为子合同后再勾选框架 → 422「请先解除与上级框架的绑定，再标记为框架合同」（`:778-779`）。
- **自动标签**（MVP2 需求④，`contracts.py:451-461` `_finalize_framework`）：
  - 自身为框架（`is_framework=True and parent_id is None`）→ 自动附加【框架合同】标签（色 `#409eff`）；
  - 不是框架 → 移除**自动附加**的【框架合同】（手动保留）；
  - 是子合同（`parent_id is not None and not is_framework`）→ **同步标识**，即也附加【框架合同】。
  - 靠 `contract_tag.auto` 区分自动/手动（`:412-439`）。
- **类型自动标签**（MVP3 §4）：保存时自动附加【类型名】标签；类型变更时旧类型自动标签移除、新的加入，手动标签不动（`contracts.py:441-449`）。
- **框架详情**返回 `children[]`（id/编号/名称/金额/状态/经办人）、`children_count`、`children_amount_sum`；
  `children_amount_sum` 是子合同 `amount` 之和，**与框架自身金额分开展示、不强制相等**（`contracts.py:752-761`，`docs/01-requirements.md:105`）。
- **框架树视图**（MVP2 需求⑤）：`GET /api/contracts?tree=true` 时**忽略分页**，输出
  ①框架行（`tree="f"` + `children_count`）+ 其子行（`tree="c"`），②其余独立合同（`tree="s"`，按 id 倒序）；
  `total = 框架数 + 独立合同数`。**树只是展示层，筛选/导出仍按平铺语义**（`contracts.py:637-675`，`docs/06-mvp2-adjustments.md:14`）。

### 3.6 编号规则（含两套并存）

**A. 合同编号（V1.0/MVP3，`app/numbering.py`）——不走统一编号服务**

- 格式：`类型码(3) + 主体码(2) + 年份(4) + 月份(2) + 6位序号`，紧凑式，例 `PURZC202609000001`（`app/numbering.py:3-6, 42-44`）。
- 校验正则：`^([A-Z]{3})([A-Z]{2})(\d{4})(\d{2})(\d{6})$`（`app/numbering.py:17`）。
- 序号规则：按「类型码+主体码+**年份**」递增，**次年重置**从 `000001`；月份码取**签订日期**所在月（未填按当天）；**已停用合同占号不复用**（`docs/07-mvp3-adjustments.md:8`）。
- 实现：扫描库内**同前缀**编号，取尾部 6 位最大值 +1（`numbering.py:20-39`）——不用序列表，避免预占跳号。
- 预览接口 `GET /api/contracts/next-no` **不占号**（`contracts.py:476-501`）；保存时服务端生成，撞号返回 **409**「合同编号已存在（自动编号被占用，请重新生成）」（`contracts.py:535-538`）。
- 类型/主体取值真源：KV `contract_types`（6 个 enabled 内置 + `OTH` enabled=false **不参与自动编号**）与 `subjects`（默认 `ZC 智澈公司`、`YX 云羲公司`）；
  旧标签映射 `{"采购":"PUR","销售":"SAL","其他":"OTH"}`、旧主体名 → 代码（`app/dicts.py:22-41`）。
- 类型为 `OTH` 或未知时 `preview_number` 抛 422「该类型暂不支持自动编号」（`contracts.py:489`）；主体为空时 422「请选择有效主体」（`:493`）。
- 手填编号仅保留给历史/导入通道（`docs/07-mvp3-adjustments.md:10`）。

**B. 统一编号服务（V2.0，`app/services/numbering_service.py`）——8 类单据 + 主数据**

- 规则来源 KV `number_rules`，可在系统管理页调前缀与序号长度（`dicts.py:338-374`）。
- 两种模式：`reset="month"` → `{prefix}{YYYYMM}{seq:0N}`；`reset="never"` → `{prefix}{seq:0N}`（`numbering_service.py:5-9`）。
- 取号算法：按 `prefix(+YYYYMM)` LIKE 前缀取**最大编号**（`ORDER BY code DESC`），从**前缀之后**的部分提取尾部数字 +1；
  ⚠️ 注释明确警告：**必须从前缀之后提取**，否则前缀含数字（如商品类型码 `L476`）会生成 `L4764760002`（`numbering_service.py:48-64`）。
  据此，`ORDER BY code DESC` 的字典序等于数值序，依赖**零填充等宽**。
- 默认规则表（`dicts.py:252-264`）：单据 `PR/PO/SR/SO/IN/OUT/ST/DB` 前缀 + `month` + 6 位；
  主数据 `CUS`/`SUP` 前缀 + `never` + 4 位；**物料 `product` 前缀为空** → 由商品类型码决定（`next_product_code`，`numbering_service.py:86-102`，类型无 code 时退化 `PT{类型id}`）。
- 并发兜底：`retry_on_conflict(factory, attempts=3)` —— 捕获 `IntegrityError` → 回滚 → 重新取号（`numbering_service.py:112-125`）。
- 单据编号**不可修改**；主数据编码创建后不可修改（BR-V2-01，`docs/11-erp-requirements.md:523`）。
- 校验：前缀只能**大写 ASCII 字母**、≤4 位（显式用 `isascii()` 防中文/数字被 `isalpha()` 放过）；`seq_len` 限 3~8（`dicts.py:358-372`）。
- ⚠️ 两种编号体系**并存**：合同走 A，其余走 B（`numbering_service.py:11` 明确"V1.0 合同编号保持原样，不走本服务"）。

**C. 目标侧对应物**：`ruoyi-serial` 模块已提供 `ICodeGenService.getNextCode(confId)` + `SeqResetTypeEnum(NONE/DAY/WEEK/MONTH/YEAR)` + `CodeSequenceLog`
（`ruoyi-serial/src/main/java/com/ruoyi/serial/api/ICodeGenService.java`、`.../enums/SeqResetTypeEnum.java`）→ 数量级 **S**，见 §8。

### 3.7 软删除与 30 天恢复（BR10/Q7、AC-15）

- 合同**不做物理删除**：`DELETE /api/contracts/{id}?reason=...`，`reason` 必填 `min_length=1`；
  置 `deleted=True`、`deleted_at=now()`、`deleted_reason=reason`，并写 `change_logs`（field=`deleted`）+ 操作日志（action=`disable`）（`app/routers/contracts.py:815-831`）。
- **恢复窗口**：`PUT /api/contracts/{id}/restore`，判定 `c.deleted_at is None or (now() - c.deleted_at).days > RESTORE_DAYS` → 400「已超过 30 天保留期，无法恢复」；
  `RESTORE_DAYS = 30`（`app/models.py:65`、`contracts.py:834-852`）。
  ⚠️ 用的是 `.days`（整日差），即 `deleted_at + 30 天` 之后**次日零点起**才拒绝；边界行为需在移植时明确。
- 未停用时调恢复接口返回 `{ok:true, message:"该合同未停用"}`（幂等，不报错）。
- 已停用的合同**禁止编辑** → 400「合同已停用，请先恢复」（`contracts.py:769-770`）。
- 列表默认 `deleted=False`；`include_deleted=true` 才含停用（`contracts.py:584-585, 625`）。
- 附件删除**也留痕**（写 `change_logs` 的 field=`附件`）（`attachments.py:61-63`）。
- 单据侧口径不同（BR-V2-15）：**草稿可直接物理删除**；已提交/已审核只能"作废"（软处理，必填原因并写变更历史）；
  已过账单据作废 = 先反审核（红冲库存）再作废（`docs/11-erp-requirements.md:571-576`）。
  ⚠️ 参考仓库代码里**没有"草稿物理删除"端点**（8 类单据路由无 DELETE），只有作废 `void`（`doc_routes.py:239-248`）→ 附录 A。

### 3.8 单据状态机（BR-V2-02）

```
[*] --> draft
draft --> submitted   : 提交（要求至少有 1 行行项）
submitted --> draft   : 驳回（原因必填）
submitted --> approved: 审核通过
draft --> voided      : 作废（原因必填）
submitted --> voided  : 作废（原因必填）
approved --> submitted: 反审核（原因必填；已过账则红冲库存）
completed --> draft   : 反审核（原因必填）
approved --> completed: 置为已完成（手工）
```

- 状态字典 `{"draft":"草稿","submitted":"待审核","approved":"已审核","completed":"已完成","voided":"已作废"}`（`app/models_doc.py:34-40`）。
- **可编辑状态只有 `draft`**（`EDITABLE_STATUSES = {"draft"}`，`models_doc.py:43`）；非草稿编辑 → 422「当前状态（X）不可编辑，请先反审核或作废」（`doc_service.py:39-41`）。
- **申请单/采购单/销售订单**：五态齐全；**入库/出库/盘点/调拨**：`approved` 即终态，**不使用 `completed`**（`docs/11-erp-requirements.md:542-543`，`12` §4.4 line 540）。
- 守卫实现：`_ensure_status(doc, allowed, action)` 逐动作白名单（`doc_service.py:166-170`）：
  - `submit` 仅 `draft`，且**必须有行项**否则 422「单据没有行项，不能提交」；
  - `approve` 仅 `submitted`；`reject` 仅 `submitted` 且原因必填（422「驳回原因必填」）；
  - `complete` 仅 `approved`；`void` 仅 `draft`/`submitted` 且原因必填；
  - `unapprove` 仅 `approved`/`completed` 且原因必填（`approved→submitted`，`completed→draft`），并把 `approved_by`/`approved_at` 置空。
- 每次流转都写 `change_logs`（field=`status`）+ 操作日志（action = submit/approve/reject/complete/void/unapprove）。
- **自审禁止**（AC-V2-15，V2.1 修订）：`created_by == 当前用户` 时，**管理员例外**——判据为
  `is_superadmin=True` 或持有角色 code ∈ `{admin, superadmin, sysadmin}`；否则需系统参数 `allow_self_approve=True`；
  自审通过时变更历史/操作日志显式记「审核通过（管理员自审）」（`doc_service.py:183-218`，BR-V2.1-05）。
  注意 `admin` 是**账号名**而非角色 code，不能靠用户名判断（`doc_service.py:190`）。
- 列表默认**排除已作废**；`include_voided=true` 或 `status=voided` 才出现（AC-V2-17，`doc_service.py:411-424`）。

### 3.9 过账 / 红冲 / 下推 / 盘点

#### 3.9.1 过账（入库/出库，BR-V2-03、AC-V2-19/20/22/23）

- **审核即过账**，与状态变更**同一数据库事务**；服务层**不 commit**，由路由层统一提交，任一步抛错整体回滚（`posting_service.py:11`、`doc_routes.py:27-33`）。
- 符号：`sign = (1 if direction > 0 else -1) * (-1 if reverse else 1)`（`posting_service.py:61`）；
  入库单 `direction=1`、出库单 `direction=-1`（`models_doc.py:269, 303`）。
- 逐行：`delta = sign * qty`，`new_qty = stock.qty + delta`，更新 `stocks.qty`，
  追加 `stock_ledger`（含 `qty_after=new_qty`、`biz_type` 取自 `in_type`/`out_type`、红冲时前缀 `红冲-` 且 `remark="反审核红冲"`）（`:63-91`）。
- **幂等**（AC-V2-23）：`if not reverse and doc.posted: return 0`（`:58-59`）；`approve_stock_doc` 另有一层 `if doc.posted and doc.status=="approved": return 0`（`:205-206`）。
- **负库存校验**（AC-V2-21）：`new_qty < 0 and not reverse and not skip_negative_check and not params.allow_negative_stock` → 抛
  `BusinessError("<仓库> 物料「<名称>」库存不足（可用 X，需要 Y）")`；继承 `ValueError` 以便统一转 HTTP 422；
  抛错后事务回滚 → **库存与单据状态都不变**。
- **不 commit** 但会写操作日志（`audit_service.log`，由调用方提交）。
- 过账后回写来源数量：`backfill_received_qty`/`backfill_shipped_qty`（`:94-103`）。
- 红冲（AC-V2-22）：**追加负向流水，原流水保留可查**；`doc.posted = not reverse`；红冲**允许仓库变负**（不校验）（`:93`）。

#### 3.9.2 调拨过账（V2.1/N13、BR-V2.1-07/08、AC-V2.1-13~18）—— **必须复用"两阶段"写法**

`post_transfer`（`posting_service.py:112-180`）：
1. **阶段一 · 全量校验（先算后写）**：校验两仓非空、**两仓不得相同**（422「调出仓库与调入仓库不能相同」）、必须有行项、每行 `qty > 0`，
   并按 `allow_negative_stock` 校验**调出仓**可用量；任一失败即整体报错，**不留"写了一半"的中间态**。
2. **阶段二 · 统一写入**：每行产生**两条流水** —— 调出仓 `调拨出库`（`-qty`）、调入仓 `调拨入库`（`+qty`），
   **同一凭证号（同一调拨单号）**，`unit_price=0`（调拨不产生金额）。
- 幂等同上（`doc.posted` 短路）；反审核红冲两条（`红冲-调拨出库` / `红冲-调拨入库`）。
- `direction=0` 使调拨**不参与** `direction` 驱动的通用过账（`models_doc.py:376-379`）。
- 调拨**不计入「货品总额度」**，也**不参与金额/合同金额汇总**。

#### 3.9.3 盘点（BR-V2-06、AC-V2-27~30）

- **生成行项** `POST /api/stock/takes/{id}/generate`（`stock.py:164-179` → `posting_service.generate_take_items:219-272`）：
  - 取该仓库 `Stock.qty != 0` 的行；`全盘` = 全部；`抽盘` = 按 `product_ids` 或按商品类型（用 `path LIKE` 取整棵子树）；
  - 写入时即固定 `book_qty`（账面，界面只读）；`actual_qty` 默认 = `book_qty`，`diff_qty=0`；已存在的行按 `product_id` 更新 `seq` 与 `book_qty`；
  - 物料快照（code/name/spec/uom_name/uom_decimals）当次固化。
- **录入实盘** `PUT /api/stock/takes/{id}/count`（`stock.py:181-209`）：`actual_qty` 必须 ≥0，
  `diff_qty = round(actual - book, 3)`；可带 `diff_reason`。
- **审核**（`approve_stock_take`，`:275-301`）：
  - 差异行分 `盘盈`（diff>0）/`盘亏`（diff<0）；
  - 盘盈 → 自动建 **盘盈入库单**（`in_type="盘盈入库"`，status 直接 `approved`，`source_doc_type="stock_take"`）并过账；
  - 盘亏 → 自动建 **盘亏出库单**（`out_type="盘亏出库"`）并过账，**豁免负库存校验**（`skip_negative_check=True`）——
    理由："账实不符本身即差异证据，若被拦截则盘点永远无法平账"（`:289`）；
  - 生成单 `total_amount=0`、`unit_price=0`、行 `qty=abs(diff_qty)`；
  - 回填 `generated_in_id/no`、`generated_out_id/no`；盘点单状态置 `approved`；写变更历史（含差异行数与两个生成单号）。
- 审核后不可修改；反审核需先处理生成的盘盈/盘亏单（**注意：代码中 `StockTake` 未注册 `unapprove_handler`**，
  `stock.py:124-127` 只传了 `approve_handler=_approve_take` 与 `export_perm` → 反审核走通用 `doc_service.unapprove`，
  **但 `push_service._DOWNSTREAM` 中没有 `stock_take → stock_in/stock_out` 映射**，故通用反审核**不会**拦下游 → 附录 A 记为风险）。

#### 3.9.4 下推（BR-V2-17、AC-V2-16/18/24、BR-V2.1-06）

- 链路：`采购申请 → 采购单 → 入库单`；`销售申请 → 销售订单 → 出库单`；`盘点单 → 盘盈/盘亏单`（系统自动）（`push_service.py:33-39`、`docs/11-erp-requirements.md:566`）。
- **剩余量**：`remaining_qty = qty - used`，`used` 字段按阶段取 `ordered_qty`（申请→订单）/ `received_qty`（订单→入库）/ `shipped_qty`（订单→出库）（`push_service.py:42-45`）。
- **超量拦截**：`qty <= 0` → 「下推数量必须大于 0」；`qty > remain` → 「<标签>「<物料>」可下推数量不足（剩余 R，本次 Q）」（`:48-54`）。
- **前置换态**：采购申请下推要求 `status == "approved"`（422「仅已审核的采购申请单可以下推采购单」）；
  采购单下推入库要求 `status in ("approved","completed")`；销售线同构（`:110-111, 184-185, 227-228, 271-272`）。
- **`rows=None` 表示按剩余量全推**；行项按 `src_item_id`（缺省 `id`）匹配来源行，未指定时按索引顺序对应（`:69-101`）。
- 生成的是**草稿**单据，写 `source_doc_type/id/no`，行项写 `src_item_id`；采购单默认带过 `contract_id/contract_no`、`purchase_dept_id`、`expected_arrival_date`（来自申请单的 `request_dept_id`/`need_date`）（`:116-144`）。
- **归零即完成**（BR-V2.1-06/N7/N8）：下推后调 `complete_if_fully_ordered`，当**全部行项 `remain <= 0`** 时把申请单置 `completed` 并留痕；
  幂等（已 completed 或无行项直接返回 False）（`:152-169`）。列表接口为采购申请附带 `remain_qty_sum` 供按钮显隐（N7，`doc_routes.py:112-118`）。
- **上游守卫**（AC-V2-24）：`assert_no_downstream` 反审核/作废前检查下游是否存在 `status != "voided"` 的单据，有则 422「已存在下游X，请先处理下游单据（反审核或作废）后再操作」（`:57-66`）。
  ⚠️ 该检查在 `doc_service.unapprove` 内被调用（`doc_service.py:254`），但**不在 `void` 里**——作废上游不会被拦（附录 A）。
- **回写/回退**：入库过账 → 采购单行 `received_qty += line.qty`；红冲 → `max(0, received_qty - qty)`；
  出库同构写 `shipped_qty`（`:323-363`）。**申请→订单阶段的下推是即时累加 `ordered_qty`**（下推时即写，不等过账）。

### 3.10 库存一致性不变式（AC-V2-26、BR-V2-04）

> **对任一 `(product_id, warehouse_id)`：`stocks.qty == SUM(stock_ledger.qty_change)`**（`app/models_stock.py:6-7`、`docs/12-erp-system-design.md:496-497`）。

- 校验/修复接口：`POST /api/stock/recalc?fix=false|true`（运维类，权限 `stock.balance.view`）；
  返回 `{checked, consistent, mismatch_count, mismatches[:50], fixed}`（`posting_service.py:343-372`）；`fix=true` 时把 `stocks.qty` 改成流水累计并 commit。
- 结存**只能由过账服务维护**，界面禁止直接改库存（`docs/11-erp-requirements.md:448`）。
- **「货品总额度」口径（BR-V2.1-09，易争议）**：`= SUM(qty_change × COALESCE(unit_price,0))`，筛选
  `biz_type LIKE '%入库%' AND biz_type NOT LIKE '%调拨入库%'`，按 `(product_id, warehouse_id)` 分组（`app/routers/stock.py:214-230`）。
  ⚠️ 注释明确：**不能用 `qty_change > 0` 判"入库方向"** —— 红冲入库 `qty_change` 为负会被漏掉导致无法冲减；
  必须**按 `biz_type` 判方向再求和**，让负向自然冲减；同时排除调拨入库（含其红冲），否则同一批货在仓库间搬运会反复放大额度。
  只读展示，**不参与结存与过账**。
- `biz_type` 的 `contains("入库")` 语义同时覆盖 `采购入库/退货入库/盘盈入库/其他入库`，`红冲-*` 也含"入库"，
  所以红冲流水**会被计入**（这正是"冲减"的实现方式）——移植时务必保留这条 LIKE 口径，不要改成枚举白名单。

### 3.11 打印模板版面模型（V2.2/BR-V2.2-02）

**存储**：`print_templates(kind UNIQUE, config JSON)`，**不改动任何业务表**（`docs/25-v2.2-adjustments.md` §5.3）。

**配置结构（`app/services/print_template_service.py`）**：
```json
{
  "version": 1,
  "title": "<单据类型名>", "subtitle": "CTMS · ERP 进销存", "show_printed_at": true,
  "footer": "本单据由系统生成，签字后作为业务凭证留存。",
  "head_columns": 2,                                  // ∈ {1,2,3}
  "blocks": ["title","head","items","sign"],          // 四区块，顺序即版面顺序
  "head_fields": [{"key","label","enabled","full"}],  // full=true 独占整行
  "item_columns": [{"key","label","enabled"}],        // 列表即顺序与可见性
  "sign_labels": ["制单","审核","仓管","领料/收货"]     // 盘点单默认 ["制单","盘点人","审核","仓管"]
}
```
- **区块目录（4 个）**：`title` 标题区 / `head` 表头信息 / `items` 行项明细 / `sign` 签字与备注（`:46-51`）。
- **表头字段目录（18 个）**：`doc_no, doc_date, status, supplier, customer, warehouse, from_warehouse, to_warehouse, in_type, out_type, take_type, expected_arrival_date, delivery_date, contract_no, handler, source_doc_no, generated_in_no, generated_out_no`（`:55-74`）。
- **行项列目录（13 个）**：`seq, product_code, product_name, spec, uom_name, qty, unit_price, amount, remark`（默认开）
  + `book_qty, actual_qty, diff_qty, diff_reason`（**默认关**，仅盘点单打开）（`:79-96`）。
  金额列 `{unit_price, amount}` 对盘点单**默认关**；差异列仅盘点单**默认开**（`default_config`，`:121-145`）。
- **读取即合并**：`get_config` 把库里的与 `default_config(kind)` 合并，缺项自动回落默认，**老模板不会 KeyError**。
- **写入即归一**：未知字段/列 key 丢弃、非法表头列数回落、签字栏为空回落默认、行项列全关时至少恢复一列；
  提交的列表**即为最终顺序与可见性**（不在列表里 = 被移除）（`:164-278`）。
- **字段自适应**：单据上没有的字段渲染时自动跳过，不会印出"—"。
- **出厂值即代码**：`factory_config(kind)`，库里无记录时直接用默认值，**不需要先"初始化模板"**；
  `is_customized()` 判断是否与出厂模板不同（前端显示"已自定义"）。
- **预览**：`POST /api/system/print-templates/{kind}/preview` 用**提交的（尚未保存）**配置渲染一张**内置样例单据**，
  前端写进 `iframe.srcdoc`，700ms 去抖自动刷新；**预览不读业务数据、不落库**。
- **输出**：`print_service.build_doc_print_html` 生成**可直接打印的 A4 HTML**（内联 CSS，`@page { size: A4; margin: 14mm 12mm }`），
  页面顶部自带"打印/另存为 PDF"按钮（`window.print()`）；前端以 **blob** 方式打开（令牌在请求头，不能放 URL）（`doc_routes.py:269-281`、`print_service.py:1-19`）。
- **权限/菜单**：`system.print.view`（menu）+ `system.print.edit`（button）；菜单「资料库 → 打印模板」`/system/print-templates`
  （`permissions.py:126-127, 194-195`）。
- 升级陷阱（已修复，移植时要复现）：权限点是代码常量而角色-权限落库，**新增权限点后已部署库的 `sysadmin` 不会自动拥有**；
  `init_db.seed_auth` 每次启动给 `sysadmin` **补齐**缺失权限（只做加法）（`docs/25-v2.2-adjustments.md` §5.4）。

### 3.12 其它业务规则速查

| 规则 | 口径 | 位置 |
|---|---|---|
| BR2 必填项 | 合同编号、名称、类型、甲方、乙方、签订日期、标的物、合同金额 | `docs/01-requirements.md:72` |
| BR2 金额 | 默认人民币、2 位小数；币种下拉预留但**不影响计算** | 同上 :73 |
| BR7 标签 | 自由文本、全局去重、多对多、可改名可删（删除仅从字典移除，历史按名称匹配） | `docs/01-requirements.md:107-110` |
| BR8 搜索 | 组合条件 AND；关键词命中 编号/名称/甲方/乙方 **+ 行项名称/规格**；标签多选取**交集**；支持排序与导出当前筛选 | `docs/01-requirements.md:112-118`、`contracts.py:586-613` |
| BR11 附件 | PDF/Word/Excel/图片，单文件 ≤20MB，类型白名单，删除留痕，不做 OCR | `docs/01-requirements.md:131-135`、`config.py:42-43` |
| BR-V2-07 | 单据与合同**可关联非强制**；关联时按类型限定（PUR 合同↔采购单据，SAL 合同↔销售单据）；合同执行数据**只读汇总，不回写合同** | `docs/11-erp-requirements.md:791`、`contracts.py:876-921` |
| BR-V2-08 | 权限三层（菜单控可见/按钮控动作/数据范围控数据）；多角色**权限并集、数据范围取最宽** | `docs/11-erp-requirements.md:792`、`permissions.py:288-294` |
| BR-V2-09 | 唯一性：客户名称、供应商名称、物料编码、单位编码、仓库编码、账号登录名、角色编码**全局唯一**（停用项也占用） | `docs/11-erp-requirements.md:793` |
| BR-V2-10 | 组织节点删除需无下级且无归属账号；账号不可删只能停用；不可停用最后一个超管与自己 | `docs/11-erp-requirements.md:794`、`org_service.py:236-240`、`user_service.py:117-119` |
| BR-V2-11 | 密码 ≥8 位含字母与数字；pbkdf2_hmac 加盐 120000 轮；管理员重置后首登强制改密 | `docs/11-erp-requirements.md:795`、`security.py:20-21,62-` |
| BR-V2-12 | 审计：登录/审核/反审核/作废/权限变更/主数据变更/导出/备份均写操作日志；业务变更写变更历史（含操作人） | `docs/11-erp-requirements.md:796` |
| BR-V2-13 | 行项：至少 1 行、数量>0、物料必选档案、**金额先舍入再汇总**、快照存储 | `docs/11-erp-requirements.md:556-562`、`doc_service.py:92-156` |
| BR-V2-14 | 数量精度=物料基本单位小数位；单价 4 位；金额 2 位；币种不换算 | `docs/11-erp-requirements.md:798` |
| BR-V2-18 | 单据 `org_id` 在创建时按创建人组织节点**快照**（可改，仅限有权限者） | `docs/11-erp-requirements.md:802`、`doc_routes.py:57` |
| BR-V2-19 | 物料必须挂商品类型**叶子节点**；停用类型/物料不影响历史单据展示 | `docs/11-erp-requirements.md:803`、`master_service.py:390-401` |
| BR-V2-20 | 历史兼容：合同甲乙方保留文本；档案未认领时以文本展示；旧变更历史操作人显示"—" | `docs/11-erp-requirements.md:804` |
| BR-V2.1-03 | 明细提取：选关联合同后一键把**该合同全部行项**提取为本单行项（数量/单价作草稿值可改）；已有行项时二次确认覆盖 | `docs/22-v2.1-requirements.md:107` |
| BR-V2.1-04 | 行项金额 = 数量×单价（2 位），合计 = Σ行项金额；**前后端同一口径** | `docs/22-v2.1-requirements.md:108` |
| BR-V2.1-10 | 现场快建：物料/商品类型/计量单位在当前页弹窗完成并**自动回填**，不得跳走丢失未保存单据 | `docs/22-v2.1-requirements.md:114` |
| 数量精度校验 | 单位 `decimals=0` 时录 `1.5` 必须被拒（AC-V2-11）；小数位超出也拒 | `doc_service.py:120-125` |
| 物料停用拦截 | 行项引用已停用物料 → 422「第 N 行：物料「X」已停用」 | `doc_service.py:114-115` |
| 供应商/客户/仓库停用拦截 | 下推/建单时校验 `status == enabled` / `enabled == True`，否则 422 | `doc_service.py:283-313` |
| 同单据同物料+仓库重复 | **提示合并（不强制）** | `docs/11-erp-requirements.md:562`（代码未实现强制校验） |
| 盘点不锁库存 | 接受"账面快照与实盘时点差异"，在盘点单上记录盘点时点 | `docs/11-erp-requirements.md:467` |

---

## 4. 接口清单

> 权限点写法为**参考仓库的**（点分隔）；目标侧需转为 **`模块:实体:动作`** 冒号分隔（见 §6）。
> `无（仅登录）` = 只依赖 `get_current_user`，不校验权限点。所有接口前缀见 `app/routers/*` 的 `APIRouter(prefix=...)`。

### 4.1 `app/routers/auth.py` — 前缀 `/api/auth`（`app/routers/auth.py:28`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| POST | `/api/auth/login` | 登录（失败也写操作日志） | 无（匿名） |
| POST | `/api/auth/logout` | 登出 | 仅登录 |
| GET | `/api/auth/me` | 当前用户 + 组织 + 角色 + `perms[]` + 裁剪后 `menus[]` | 仅登录 |
| POST | `/api/auth/change-password` | 改密（清除 `must_change_pwd`） | 仅登录 |

### 4.2 `app/routers/system.py` — 前缀 `/api/system`（`system.py:37`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/org-units` | 组织树列表 | `master.org.view` |
| POST | `/org-units` | 新增组织节点 | `master.org.edit` |
| GET | `/org-units/{unit_id}` | 组织详情 | `master.org.view` |
| PUT | `/org-units/{unit_id}` | 修改组织 | `master.org.edit` |
| PUT | `/org-units/{unit_id}/status` | 启停用 | `master.org.edit` |
| DELETE | `/org-units/{unit_id}` | 删除（需无子且无账号） | `master.org.edit` |
| GET | `/permissions` | 权限点分组清单（角色配置界面用） | `master.role.view` |
| GET/POST | `/roles`, `/roles` | 角色列表 / 新增 | `master.role.view` / `master.role.edit` |
| GET/PUT | `/roles/{role_id}` | 角色详情 / 修改 | `master.role.view` / `master.role.edit` |
| PUT | `/roles/{role_id}/permissions` | 配置角色权限点 | `master.role.edit` |
| PUT | `/roles/{role_id}/status` | 启停用 | `master.role.edit` |
| DELETE | `/roles/{role_id}` | 删除 | `master.role.edit` |
| GET/POST | `/users`, `/users` | 账号列表 / 新增 | `master.user.view` / `master.user.edit` |
| GET/PUT | `/users/{user_id}` | 账号详情 / 修改 | `master.user.view` / `master.user.edit` |
| PUT | `/users/{user_id}/status` | 启停用 | `master.user.edit` |
| POST | `/users/{user_id}/reset-password` | 重置密码 | `master.user.resetpwd` |
| GET/PUT | `/params` | 系统参数读写 | `system.param.view` / `system.param.edit` |
| GET | `/number-rules` | 编号规则查看 | `system.number.view` |
| PUT | `/number-rules` | 编号规则修改 | `system.param.edit` |
| GET | `/logs` | 操作日志（按动作/时间筛） | `system.log.view` |
| GET | `/changelogs` | 变更历史（全局） | `system.changelog.view` |
| GET/POST | `/backup`, `/backup` | 备份列表 / 生成备份 | `system.backup.download` / `system.backup.create` |
| GET | `/backup/{name}` | 下载备份（白名单+目录约束防穿越） | `system.backup.download` |
| DELETE | `/backup/{name}` | 删除备份 | `system.backup.create` |
| GET | `/about` | 关于页（版本/统计） | `system.about.view` |

**类型标注**：`/logs`、`/changelogs` = 审计类；`/backup*` = **运维类**；`/params`、`/number-rules` = 配置类。

### 4.3 `app/routers/print_templates.py` — 前缀 `/api/system/print-templates`（`print_templates.py:27`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `""` | 全部单据类型的模板概览 | `system.print.view` | — |
| GET | `/{kind}` | 单个类型的模板明细 | `system.print.view` | — |
| PUT | `/{kind}` | 保存模板 | `system.print.edit` | — |
| POST | `/{kind}/reset` | 恢复出厂模板 | `system.print.edit` | — |
| POST | `/{kind}/preview` | 用未保存的配置渲染样例（返回 HTML） | `system.print.view` | **打印类** |

### 4.4 `app/routers/master.py` — 前缀 `/api/master`（`master.py:27`）

工厂 `_register(...)`（`master.py:47-104`）对 6 个实体各注册 5 个端点，权限点由 `view_perm`/`edit_perm` 传入：

| 方法 | 路径模板 | 用途 |
|---|---|---|
| POST | `{path}` | 新增 |
| GET | `{path}/{item_id}` | 详情 |
| PUT | `{path}/{item_id}` | 修改 |
| PUT | `{path}/{item_id}/status` | 启用/停用 |
| DELETE | `{path}/{item_id}` | 删除（无引用才放行） |

6 个实体的路径与权限点（`master.py:132, 156, 178, 200, 222, 247`）：

| `path` | 实体 | view_perm / edit_perm |
|---|---|---|
| `/customers` | 客户 | `master.customer.view` / `master.customer.edit` |
| `/suppliers` | 供应商 | `master.supplier.view` / `master.supplier.edit` |
| `/product-types` | 商品类型 | `master.ptype.view` / `master.ptype.edit` |
| `/uoms` | 计量单位 | `master.uom.view` / `master.uom.edit` |
| `/warehouses` | 仓库 | `master.wh.view` / `master.wh.edit` |
| `/products` | 物料 | `master.product.view` / `master.product.edit` |

另加独立端点：

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/meta` | 主数据枚举（表单下拉用） | 仅登录 |
| GET | `/options/{kind}` | 主数据轻量下拉（kind ∈ customer/supplier/product/product-type/uom/warehouse/org/user） | **仅登录** |
| GET | `/customers` `/suppliers` `/product-types` `/uoms` `/warehouses` `/products` | 各实体列表 | 各 `*.view` |

> 注意不对称：`POST/PUT/DELETE /customers` 在 `/customers/{item_id}` 路径下，而列表是 `GET /customers`；`GET /master/customers` 与工厂的 `POST /master/customers` 是**同一个路径不同方法**，可共存。

### 4.5 `app/routers/contracts.py` — 前缀 `/api/contracts`（`contracts.py:46`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/next-no` | 编号预览（**不占号**） | `contract.create` | — |
| POST | `""` | 新增合同 | `contract.create` | — |
| GET | `""` | 列表（`tree=true` 走框架树；支持 keyword/status/type/is_framework/include_deleted/owner/tags/sign_from/sign_to） | `contract.view` | — |
| POST | `/migrate-parties` | 扫描历史甲乙方文本生成档案草案 | `contract.edit` | **运维/迁移** |
| GET | `/party-drafts` | 草案列表（status=pending/claimed/ignored/all） | `contract.edit` | 迁移 |
| POST | `/party-drafts/{id}/claim` | 认领并批量绑定 | `contract.edit` | 迁移 |
| POST | `/party-drafts/{id}/ignore` | 忽略草案 | `contract.edit` | 迁移 |
| GET | `/{contract_id}` | 详情（框架附 `children[]`/`children_count`/`children_amount_sum`） | `contract.view` | — |
| PUT | `/{contract_id}` | 修改（含质保重算、行项、标签、框架联动） | `contract.edit` | — |
| DELETE | `/{contract_id}` | 停用（软删除，`reason` 必填 Query 参数） | `contract.delete` | — |
| PUT | `/{contract_id}/restore` | 恢复（30 天内） | `contract.delete` | — |
| GET | `/{contract_id}/logs` | 合同变更历史 | `contract.log.view` | 审计 |
| GET | `/{contract_id}/related-docs` | 关联单据**只读汇总**（AC-V2-31/32） | `contract.view` | — |

### 4.6 `app/routers/export.py` — 前缀 `/api/export`（`export.py:26`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/contracts.xlsx` | 合同台账导出；`cols=key1,key2`，缺省=14 个重要列；列规格见 `export.py:29-57` | `contract.export` | **导出** |

14 个重要列：合同编号/名称/类型/甲方/乙方/签订日期/标的物(行项摘要)/合同金额/累计已付/付款比例%/状态/经办人/标签/质保到期日；
10 个可选列：币种/到货状态/预计到货日期/是否框架/所属框架编号/质保金金额/质保金比例%/质保生效日期/质保期限(月)/质保状态/备注（`export.py:29-57`）。

### 4.7 `app/routers/imports.py` — 前缀 `/api/import`（`imports.py:33`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/template.xlsx` | 下载导入模板（3 个 sheet：`合同(主档)`/`行项明细`/`填写说明`） | `contract.import` | **导入/模板** |
| POST | `/contracts` | 上传模板，**仅新建**；错误行整条跳过并出报告；主档上限 2000 行 | `contract.import` | **导入** |

主档列（`imports.py:35-37`）：序号/合同编号/合同名称/类型/主体码/甲方/乙方/签订日期/金额/已付金额/经办人/状态/所属框架编号/是否框架/标签(逗号分隔)/备注/质保金额/质保比例%/质保生效日期/质保期限(月)。
行项列（`imports.py:38`）：主档序号/序号/类型/物料编码/名称/规格型号/数量/单价/备注。
兼容：编号留空=自动生成；类型列接受新类型名/类型代码/旧名；行项关联键由旧的"合同编号"改为"主档序号"（`docs/07-mvp3-adjustments.md` §8）。

### 4.8 `app/routers/meta.py` — 前缀 `/api/meta`（`meta.py:20`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `""` | 全局元数据：合同类型字典（含历史遗留值回填）、状态集、导出列规格等 | **仅登录** |

### 4.9 `app/routers/settings.py` — 前缀 `/api/settings`（`settings.py:22`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/item-types` | 行项类型字典 | **仅登录** | — |
| PUT | `/item-types` | 保存行项类型 | `system.dict.edit` | — |
| GET | `/contract-types` | 合同类型字典 | **仅登录** | — |
| PUT | `/contract-types` | 保存合同类型（至少保留一个 enabled） | `system.dict.edit` | — |
| GET | `/subjects` | 我方主体码 | **仅登录** | — |
| PUT | `/subjects` | 保存主体（至少保留一个） | `system.dict.edit` | — |

> **读写权限不对称是刻意的**：读接口只要登录（表单要拉字典），写接口要 `system.dict.edit`（`test_v2_api_gate.py` 有「字典读接口登录即可、写接口需 `system.dict.edit`」的断言）。

### 4.10 `app/routers/dashboard.py` — 前缀 `/api/dashboard`（`dashboard.py:30`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `""` | 看板：`stats`+`expiring`+`expired`（质保，各 ≤100 条）+`todo`（待我审核/我提交的/我的草稿，按 kind）+`stock_alerts`（低于安全库存，≤20）+`contract_overview`（受数据范围限制） | `dashboard.view` |

**区块级权限裁剪**：`stock_alerts` 需 `stock.balance.view`，否则返回 `{available:false,total:0,items:[]}`；待办按 `_VIEW_PERM`/`_APPROVE_PERM` 映射逐 kind 判定（`dashboard.py:35-54, 87-160`）。

### 4.11 `app/routers/tags.py` — 前缀 `/api/tags`（`tags.py:19`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `""` | 标签列表（含每个标签的挂载计数） | 仅登录 |
| POST | `""` | 新增标签 | `system.dict.edit` |
| PUT | `/{tag_id}` | 改名 | `system.dict.edit` |
| DELETE | `/{tag_id}` | 删除 | `system.dict.edit` |

### 4.12 `app/routers/attachments.py` — 前缀 `/api`（`attachments.py:32`）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/attachments/list?object_type=&object_id=` | 通用附件列表 | 按对象映射 `*_PERM.view`（`attachments.py:35-44`） | — |
| POST | `/attachments/upload?object_type=&object_id=` | 通用附件上传（multipart） | 按对象映射 `*_PERM.edit` | — |
| DELETE | `/attachments/{attachment_id}?reason=` | 通用附件删除（软删+留痕） | 按对象映射 `*_PERM.edit` | — |
| GET | `/attachments/{attachment_id}/download?inline=0\|1` | 下载/预览（按所属对象鉴权） | 按对象映射 `*_PERM.view` | — |
| GET | `/contracts/{contract_id}/attachments` | 合同附件列表（V1.0 兼容） | `contract.view` | — |
| POST | `/contracts/{contract_id}/attachments` | 合同附件上传 | `contract.edit` | — |
| DELETE | `/contracts/{contract_id}/attachments/{attachment_id}` | 合同附件删除 | `contract.edit` | — |

对象→权限映射（`attachments.py:35-44`）覆盖 `contract` + 7 类单据；⚠️ **缺 `stock_transfer`**（V2.1 新增调拨单未登记），
调拨单附件会 422「不支持的附件对象类型」→ 附录 A 记为需补。

### 4.13 `app/routers/health.py` — 无前缀（`health.py:9`）

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| GET | `/api/health` | 健康检查 | 无（匿名） |

### 4.14 `app/main.py` — 根

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/` | 未开静态托管时返回 `{app,version,docs,health}`；开了则返回前端 index.html |
| GET | `/{full_path:path}` | **仅当 `CTMS_SERVE_STATIC=1`** 时的 SPA fallback（`api/` 前缀返回 404） |

### 4.15 单据工厂端点（8 类单据 × 13 条，`app/routers/doc_routes.py:36-281`）

权限点 = `{perm_prefix}.{action}`（`doc_routes.py:40-46`），`kind`/`perm_prefix`/`label`/`export_perm` 由各路由器传入。

| 方法 | 路径模板 | 用途 | 权限点后缀 |
|---|---|---|---|
| GET | `{prefix}` | 列表（分页+筛选；采购申请附 `remain_qty_sum`） | `.view` |
| GET | `{prefix}/export.xlsx` | **导出当前筛选**（条数与列同列表，同数据范围） | `.export` |
| POST | `{prefix}` | 新增草稿 | `.create` |
| GET | `{prefix}/{doc_id}` | 详情（超出数据范围 403） | `.view` |
| PUT | `{prefix}/{doc_id}` | 修改（仅草稿） | `.edit` |
| POST | `{prefix}/{doc_id}/submit` | 提交 | `.submit` |
| POST | `{prefix}/{doc_id}/approve` | 审核（库存类含过账） | `.approve` |
| POST | `{prefix}/{doc_id}/reject` | 驳回（原因必填） | `.approve` |
| POST | `{prefix}/{doc_id}/complete` | 置为已完成 | `.approve` |
| POST | `{prefix}/{doc_id}/void` | 作废（原因必填） | `.void` |
| POST | `{prefix}/{doc_id}/unapprove` | 反审核（库存类红冲） | `.approve` |
| GET | `{prefix}/{doc_id}/changelogs` | 单据变更历史 | `.view` |
| GET | `{prefix}/{doc_id}/print` | **A4 打印 HTML** | `.view` |

8 个 `prefix` × `perm_prefix`（`purchase.py:25,41-44,67,91-94`；`sales.py:25,46-49,72,97-100`；`stock.py:38,60-65,70,92-97,102,124-127,132,156-161`）：

| `prefix` | `perm_prefix` | label |
|---|---|---|
| `/api/purchase/requests` | `purchase.request` | 采购申请单 |
| `/api/purchase/orders` | `purchase.order` | 采购单 |
| `/api/sales/requests` | `sales.request` | 销售申请单 |
| `/api/sales/orders` | `sales.order` | 销售订单 |
| `/api/stock/in-orders` | `stock.in` | 入库单 |
| `/api/stock/out-orders` | `stock.out` | 出库单 |
| `/api/stock/takes` | `stock.take` | 盘点单 |
| `/api/stock/transfers` | `stock.transfer` | 调拨单 |

### 4.16 下推与单据特有端点

| 方法 | 路径 | 用途 | 权限点 |
|---|---|---|---|
| POST | `/api/purchase/requests/{doc_id}/push` | 采购申请 → 采购单（`purchase.py:47`） | `purchase.order.create`（`:49`） |
| POST | `/api/purchase/orders/{doc_id}/push` | 采购单 → 入库单（`purchase.py:96`） | `purchase.order.push`（`:98`） |
| GET | `/api/purchase/contract-options` | 可关联**采购方向**合同下拉（`purchase.py:115`） | **仅登录** |
| POST | `/api/sales/requests/{doc_id}/push` | 销售申请 → 销售订单（`sales.py:52`） | `sales.order.create`（`:54`） |
| POST | `/api/sales/orders/{doc_id}/push` | 销售订单 → 出库单（`sales.py:103`） | `sales.order.push`（`:105`） |
| GET | `/api/sales/contract-options` | 可关联**销售方向**合同下拉（`sales.py:122`） | **仅登录** |
| POST | `/api/stock/takes/{doc_id}/generate` | 生成盘点行项（`stock.py:164`） | `stock.take.edit`（`:166`） |
| PUT | `/api/stock/takes/{doc_id}/count` | 录入实盘数量（`stock.py:181`） | `stock.take.edit`（`:183`） |

### 4.17 库存专用端点（`stock.py:33` 无 prefix）

| 方法 | 路径 | 用途 | 权限点 | 类型 |
|---|---|---|---|---|
| GET | `/api/stock/balances` | 库存结存列表（含「货品总额度」、预警、数量区间） | `stock.balance.view`（`:275`） | — |
| GET | `/api/stock/ledger` | 库存流水（按物料+仓库下钻，含变动后结存） | `stock.ledger.view`（`:312`） | — |
| POST | `/api/stock/recalc` | 结存重算校验/修复 | `stock.balance.view`（`:341`） | **运维** |
| GET | `/api/stock/balances/export.xlsx` | 结存导出 | `stock.balance.view`（`:362`） | **导出** |
| GET | `/api/stock/ledger/export.xlsx` | 流水导出 | `stock.ledger.view`（`:398`） | **导出** |

### 4.18 端点总数推导

- 路由**装饰器声明位**：115 处（`@router.(get|post|put|delete|patch)` 逐文件计数）。
- 其中工厂声明位会按参数展开：`doc_routes.register_doc_routes` 被调用 **8** 次（每次 13 条 → 104）；
  `master._register` 被调用 **6** 次（每次 5 条 → 30）。
- 故展开后端点 ≈ 115 − 13 − 5（工厂声明位） + 104 + 30 = **231**。
- 另有 `app/main.py` 的 `GET /`（+ 可选 SPA fallback），以及 FastAPI 自带 `/docs`/`/openapi.json`（不计入业务）。
- 权限点：**99** 个（`app/permissions.py:13-128`）。`docs/16-permission-matrix.md:10` 记 `sysadmin` 97 个 —— 该文档生成于 V2.2 之前，加上 V2.2 的 `system.print.view`/`system.print.edit` 后正好 99。

---

## 5. 页面清单

### 5.0 前端结构与菜单机制

- **布局**：`web/src/layout/AppLayout.vue`（侧栏 + 顶栏 + 主区）；顶栏 `components/TopBar.vue`；侧栏菜单由 `components/MenuTree.vue` 渲染**后端返回的 menus 树**（`docs/12-erp-system-design.md` §3.6 line 350："避免前后端两套菜单定义不一致"）。
- **路由表**：`web/src/router/index.ts`（**53 条路由记录 / 50 条具名路由**：3 条独立全屏页 `login`/`change-password`/`403` + 1 条 `AppLayout` 容器（路径 `/`）承载 **48 条主区子路由**（含 `settings → /system` 重定向）+ 1 条 catch-all）。
- **路由守卫**（`router/index.ts:306-347`）：① 未保存离开确认（`@/utils/unsaved`）→ ② `meta.public` 放行 → ③ 未登录跳 `/login` → ④ 拉 `/auth/me`（**只有 401 才清登录态**，断网/5xx 保留）→ ⑤ `must_change_pwd` 强制 `/change-password` → ⑥ `meta.perm` 不在权限集跳 `/403?perm=x`。
- **菜单归属**：以后端 `permissions.py:146-196` 的 `MENUS` 为准（7 个一级：首页看板/合同管理/采购管理/销售管理/库存管理/资料库/系统管理）。
  **`资料库` 内含"系统管理"与"打印模板"两个入口**（`permissions.py:193-195`），而顶层又无独立"系统管理"菜单 —— 移植时注意不要照抄成两个一级菜单。

### 5.1 独立全屏页（3 条）+ 首页看板

| 文件 | 路由 | 标题 | 核心交互 | Element Plus 特有/需降级点 |
|---|---|---|---|---|
| `views/LoginView.vue` | `/login`（name=login，`meta.public`） | 登录 | 用户名+密码表单，回车提交，错误内联提示 | `ElMessage` 用法 → 改 `this.$message` |
| `views/ChangePasswordView.vue` | `/change-password` | 修改密码 | 旧密码/新密码/确认新密码；强度校验（≥`pwd_min_length` 且含字母数字） | 无特殊组件 |
| `views/ForbiddenView.vue` | `/403`（name=forbidden，`meta.public`） | 无权限 | 显示缺失的权限点（来自 `query.perm`），"返回首页" | — |
| `views/DashboardView.vue` | `/`（name=dashboard，`meta.perm=dashboard.view`） | 首页看板 | 统计卡（总数/框架数/含质保/即将到期/已到期）+ 即将到期表 + 已到期表 + 待我审核/我提交的/我的草稿 + 库存预警（"查看库存明细"跳转）+ 合同执行概览 | `el-statistic`/`el-progress` 用例少；`el-descriptions` 在本目标 UI 版中**可用**（见 §5.4） |

### 5.2 主区页面（挂在 `AppLayout` 下）

| # | 文件 | 路由 | 菜单归属 | 核心交互 | 需降级的 Element Plus 点 |
|---|---|---|---|---|---|
| 1 | `views/ContractsView.vue`（63,995 B，**最大单页**） | `contracts`（`contract.view`） | 合同管理 | 组合筛选（关键词/标签多选交集/类型/状态/是否框架/含质保/经办人/签订日期区间）+ 平铺/框架树视图切换（框架行可折叠，子合同缩进）+ 新增/详情抽屉/编辑/停用（必填原因）/恢复 + 批量停用/批量恢复 + 导出列配置弹窗（全选/清空/恢复默认，`localStorage` 记住上次选择）+ 导入入口 + 系统设置入口 + 质保与付款比例展示 + 关联单据只读汇总 + 变更历史时间线 + 标签管理弹窗 + 附件上传/下载/删除 | `<el-button link>`（**Element Plus 独有**，Element UI 须改 `type="text"`）、`value-format="YYYY-MM-DD"`（须改 `yyyy-MM-dd`）、`el-dialog v-model`（须改 `:visible.sync`）、`el-drawer v-model`（须改 `:visible.sync`）、`ElMessageBox`/`ElMessage` |
| 2 | `views/SettingsView.vue` | `settings` → **重定向 `/system`**（`router/index.ts:108`） | （保留兼容入口） | 4 个页签：合同类型（可改名称/说明/启用，OTH 禁启用）、我方公司、标签管理、行项类型字典 | `el-tabs`；`ElMessageBox` 确认删除 |
| 3 | `views/master/CustomerView.vue` | `master/customers`（`master.customer.view`） | 资料库 → 客户信息 | 走 `MasterTablePage` 壳：列（编码/名称/简称/等级/联系人/电话/授信额度(money)/状态(status)）、表单字段、启停用 | 壳内组件见 §5.3 |
| 4 | `views/master/SupplierView.vue` | `master/suppliers`（`master.supplier.view`） | 资料库 → 供应商信息 | 同上 + **简称必填**（BR-V2.2-01）；新增/编辑提交空简称由后端 422 | 同上 |
| 5 | `views/master/OrgView.vue` | `master/orgs`（`master.org.view`） | 资料库 → 组织架构 | 走 `TreeMasterPage` 壳：树形维护、最大 5 级、删除需无子节点且无账号 | `el-tree` |
| 6 | `views/master/ProductTypeView.vue` | `master/product-types`（`master.ptype.view`） | 资料库 → 基础信息 → 商品类型 | `TreeMasterPage`；只有叶子可挂物料（提示文案） | `el-tree` |
| 7 | `views/master/ProductView.vue` | `master/products`（`master.product.view`） | 资料库 → 基础信息 → 物料档案 | `MasterTablePage`；额外筛选 `product_type_id`；商品类型**树状选择**（V2.1/N14）；默认单价(money)/状态(status) | `el-tree-select` 或 `el-cascader`（V2.1 树状展开）→ Element UI 用 `el-cascader`/`el-select`+`el-tree` |
| 8 | `views/master/UomView.vue` | `master/uoms`（`master.uom.view`） | 资料库 → 基础信息 → 计量单位 | `MasterTablePage`，`status-mode="bool"`；小数位 0~4 | — |
| 9 | `views/master/WarehouseView.vue` | `master/warehouses`（`master.wh.view`） | 资料库 → 基础信息 → 仓库 | `MasterTablePage`，`status-mode="bool"` | — |
| 10 | `views/master/RoleView.vue` | `master/roles`（`master.role.view`） | 资料库 → 角色管理 | 角色列表 + 新增/编辑（编码/名称/数据范围）+ **权限点勾选树**（按模块分组，来自 `GET /api/system/permissions`）+ 删除（内置不可删） | `el-tree`（`show-checkbox`）、`el-checkbox-group` |
| 11 | `views/master/UserView.vue` | `master/users`（`master.user.view`） | 资料库 → 账号管理 | 账号列表（登录名/姓名/组织/角色/状态/最近登录）+ 新增（初始密码、`must_change_pwd=true`）+ 编辑 + 启停用 + **重置密码** | `el-select` 多选角色、`el-tree` 组织选择 |
| 12 | `views/master/PartyDraftView.vue` | `master/party-drafts`（`meta.perm=contract.edit`） | **不在 MENUS 中**（仅路由可达） | "扫描历史文本" → 草案列表 → 「认领」（选/建档案 + 批量绑定）/「忽略」；认领时用历史名称兜底补简称 | `el-dialog v-model`、`ElMessageBox` |
| 13 | `views/system/SystemView.vue` | `system`（`system.dict.view`） | 资料库 → 系统管理 | 页签：系统参数（7 项，带元数据/类型/范围校验）+ 编号规则（前缀/序号长度，3~8）+ 操作日志（动作+时间筛选）+ 变更历史 + 备份（生成/刷新列表/下载/删除）+ 关于 | `el-tabs`、`el-descriptions`、`el-collapse`、`el-timeline` |
| 14 | `views/system/PrintTemplateView.vue`（21,516 B） | `system/print-templates`（`system.print.view`） | 资料库 → 打印模板 | 左侧 8 类单据列表（标注"已自定义"）→ 右侧编辑：文字信息（标题/副标题/打印时间开关/页脚）+ 区块顺序（上移/下移）+ 表头（每行 1/2/3 字段、显示开关、顺序、标签文字、独占整行）+ 行项列（显示/顺序/列名）+ 签字栏（文字增删排序，≤8）；按钮：载入出厂版面/恢复默认/保存模板/刷新预览；`iframe.srcdoc` + 700ms 去抖 | `el-switch`、`el-input-number`、`el-collapse`、`el-tabs` |
| 15 | `views/purchase/RequestList.vue` | `purchase/requests`（`purchase.request.view`） | 采购管理 → 采购申请单 | 走 `DocListPage`；`export-path="/api/purchase/requests/export.xlsx"`；下推按钮按 `remain_qty_sum>0` 显隐；下推成功跳 `purchase-order-edit` | 壳内组件见 §5.3 |
| 16 | `views/purchase/RequestForm.vue`（8,615 B） | `purchase/requests/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + 特有字段（申请部门/需求日期/建议供应商/用途）；**"提取合同明细"**（BR-V2.1-03）；"查看合同详情"抽屉；物料选择框右侧快速新增按钮（BR-V2.1-10） | `el-drawer`、`el-dialog`、`el-date-picker` |
| 17 | `views/purchase/OrderList.vue` | `purchase/orders`（`purchase.order.view`） | 采购管理 → 采购单 | `DocListPage`；下推入库跳 `stock-in-edit` | 同上 |
| 18 | `views/purchase/OrderForm.vue` | `purchase/orders/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + 供应商选择器（选项主文字=**简称**，全称作次要说明，搜索匹配 name/code/short_name）+ 右侧「供货商详情」按钮弹只读档案 + 默认收货仓库 + 预计到货 + 结算方式 | `el-drawer`、`el-select` 自定义选项模板 |
| 19 | `views/sales/RequestList.vue` | `sales/requests`（`sales.request.view`） | 销售管理 → 销售申请单 | `DocListPage` | 同上 |
| 20 | `views/sales/RequestForm.vue` | `sales/requests/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + 客户/销售部门/期望交货日期 + "查看合同详情" | 同上 |
| 21 | `views/sales/OrderList.vue` | `sales/orders`（`sales.order.view`） | 销售管理 → 销售订单 | `DocListPage`；下推出库跳 `stock-out-edit` | 同上 |
| 22 | `views/sales/OrderForm.vue` | `sales/orders/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + 客户/交货日期/收货地址/联系人/电话/默认发货仓库 | 同上 |
| 23 | `views/stock/InList.vue` | `stock/in-orders`（`stock.in.view`） | 库存管理 → 入库单 | `DocListPage`；副标题说明"审核即过账"；`show-warehouse` | 同上 |
| 24 | `views/stock/InForm.vue` | `stock/in-orders/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + `show-warehouse` + `:show-price="false"`（入库下推时单价只读/隐藏金额） | 同上 |
| 25 | `views/stock/OutList.vue` | `stock/out-orders`（`stock.out.view`） | 库存管理 → 出库单 | `DocListPage`；副标题含"负库存拦截" | 同上 |
| 26 | `views/stock/OutForm.vue` | `stock/out-orders/new` / `:id/edit` / `:id` | 同上 | 同 InForm | 同上 |
| 27 | `views/stock/TakeList.vue` | `stock/takes`（`stock.take.view`） | 库存管理 → 盘点单 | `DocListPage` + `show-warehouse`；副标题描述"选仓库与全盘/抽盘 → 生成行项 → 录入实盘 → 审核自动生成盘盈/盘亏单" | 同上 |
| 28 | `views/stock/TakeForm.vue`（16,812 B） | `stock/takes/new` / `:id/edit` / `:id` | 同上 | 独立实现（不套 `DocFormPage`）：选仓库/全盘或抽盘/范围说明 → **"确认生成"** 调 `/generate` → 行项表（账面只读、实盘可填、差异自动算、差异原因）→ **"保存实盘数量"** 调 `/count` → 提交/审核；审核结果显示生成的盘盈/盘亏单号 | `el-input-number`、`el-table` 可编辑单元格、`el-dialog v-model` |
| 29 | `views/stock/TransferList.vue` | `stock/transfers`（`stock.transfer.view`） | 库存管理 → 调拨单 | `DocListPage`；副标题"同一事务内调出仓减少、调入仓增加（仓库间搬运，不产生金额）" | — |
| 30 | `views/stock/TransferForm.vue` | `stock/transfers/new` / `:id/edit` / `:id` | 同上 | `DocFormPage` + `:show-price="false"` + `:show-amount="false"` + 调出/调入两仓（前端也拦两仓相同） | — |
| 31 | `views/stock/BalanceView.vue`（19,199 B） | `stock/balances`（`stock.balance.view`） | 库存管理 → 库存明细 | 结存列表（**物料名称列显示 `类型-名称`**，V2.2 需求③；类型前缀弱化色）+ 筛选（物料关键字/仓库/商品类型/数量区间/低于安全库存）+ **「货品总额度」列** + **"流水下钻"抽屉**（按物料+仓库倒序，含变动后结存）+ **"库存重算"** 运维按钮 | `el-drawer`、`el-table`、`el-descriptions` |

### 5.3 共享页面壳与单据组件（**移植时优先做的抽象层**）

| 组件 | 大小 | 作用 | 关键 Props | 移植要点 |
|---|---|---|---|---|
| `components/MasterTablePage.vue` | 15,452 B | 主数据"列表+弹窗表单"通用壳 | `title`/`subtitle`/`api`/`permEdit`/`columns[]`/`fields[]`/`statusMode('status'\|'bool')`/`extraFilters[]` | 6 个主数据页共用；`permView` 已移除（查看权限只由路由 `meta.perm` 守，`MasterTablePage.vue` 注释说明是"冗余死代码"，**服务端才是真防线**） |
| `components/TreeMasterPage.vue` | 10,324 B | 树形主数据壳 | `title`/`subtitle`/`api`/`permEdit`/`nodeLabel`/`fields[]`/`metaOptions` | 组织/商品类型共用 |
| `components/doc/DocListPage.vue` | 33,362 B | 单据列表通用壳（8 类单据共用） | `title`/`subtitle`/`api`/`formRoute`/`editRoute`/`listRoute`/`permPrefix`/`kindLabel`/`exportPath`/`showWarehouse`/… | **一个组件撑起 8 个列表页**；含筛选、状态标签、操作列（查看/编辑/提交/审核/驳回/完成/作废/反审核/下推/打印/变更历史）、导出、附件。移植到 Vue2 时建议保留同样的"配置驱动"结构，避免 8 份拷贝 |
| `components/doc/DocFormPage.vue` | 19,719 B | 单据表单通用壳 | `title`/`api`/`permPrefix`/`listRoute`/`kindLabel`/`itemsEditable`/`showWarehouse`/`showPrice`/`showAmount`/`extra`（`v-model`） | 用 `v-model="extra"` 传特有字段；`itemsEditable`/`showPrice` 的语义区别有注释（"只读但列仍显示" vs "不显示列"） |
| `components/doc/DocItemsTable.vue` | 14,247 B | 行项明细表（可编辑/只读） | `items[]`/`readonly`/`showWarehouse`/`showPrice`/`showAmount` | 就地编辑数组元素；含物料选择、快速新增、单价/金额联动 |
| `components/doc/PushDialog.vue` | 12,049 B | 下推对话框 | `api`/`destType('order'\|'stock')`/`destLabel`/`partyKind`/`usedField`/`stockTypeField` | 按剩余量预填、可按行改量、超量拦截；生成草稿后跳下游编辑页 |
| `components/doc/ApproveDialog.vue` | 3,308 B | 审核/驳回/作废/反审核原因弹窗 | — | 原因必填校验 |
| `components/doc/ContractDetailDrawer.vue` | 5,405 B | 合同只读详情抽屉 | — | 供单据页"查看合同详情" |
| `components/doc/RelatedDocs.vue` | 5,634 B | 合同详情"关联单据"区块 | `contractId` | 只读汇总；接口 404 时显示"—" |
| `components/SupplierDetailDialog.vue` | 5,623 B | 供应商只读档案弹窗 | `ref.open(supplierId)` | 标题 `供货商详情 · {简称}`，**简称优先**、无简称回落全称；无 `master.supplier.view` 时给明确 403 文案而非空白；`supplier_id` 缺失时保持纯文本（不做假按钮） |
| `components/QuickCreateDialog.vue` | 6,556 B | 物料/商品类型/计量单位"现场快建" | `kind('product'\|'product-type'\|'uom')` + `@created` | BR-V2.1-10：不跳页；⚠️ 注释记录过一个真实缺陷——接口路径必须带 `/master` 前缀，曾因写 `/products` 拼成 `/api/products` 而**必然 404**（`QuickCreateDialog.vue`） |
| `components/doc/DocStatusTag.vue` / `PostStatusTag.vue` | 1,215 / 645 B | 状态标签 / 过账标签 | — | 纯展示 |
| `components/MenuTree.vue` / `components/TopBar.vue` / `layout/AppLayout.vue` | 954 / 3,002 / 3,412 B | 侧栏菜单 / 顶栏 / 布局 | — | 菜单树来自后端 `menus[]` |

### 5.4 Element Plus → Element UI 降级点（**实测结论**）

先用事实消除一个常见误判：**目标仓库的 `element-ui@2.15.14` 是包含 `descriptions/empty/result/statistic/skeleton/page-header/link/image/avatar` 的扩展构建**
（实测 `ruoyi-vue-oa-ui-master/node_modules/element-ui/packages/` 目录存在这些组件），
且目标 UI 源码中已在用 `el-descriptions`(55 处)、`el-empty`(13)、`el-skeleton`(2)、`el-drawer`(32)、`el-timeline`(4)、`el-image`(8)。
→ **参考仓库用到的 Element Plus 组件里，只有 `el-segmented` 与 `el-space` 在目标侧不存在；而参考仓库这两个都没用（实测 0 处）**。

因此真正必须改的是**语法/API**，不是组件集合：

| 类别 | 参考仓库（Element Plus / Vue3） | 目标侧（Element UI / Vue2） | 实测影响面 |
|---|---|---|---|
| 组件写法 | `<script setup>` + `defineProps` + `withDefaults` + `defineEmits` | Options API：`props` / `emits` / 普通 `data`/`methods` | **54 个 `.vue` 用 `<script setup>`**、12 处 `defineProps`、6 处 `defineEmits` → 全量重写 |
| 组合式 API | `ref`/`reactive`/`computed(...)`/`watchEffect`/`onMounted`/`useRouter`/`useRoute` | `data`/`computed`/`watch`/`mounted`/`this.$router`/`this.$route` | `computed(` 59 处、`onMounted` 38、`useRoute` 34、`useRouter` 26 |
| 弹层可见性 | `v-model` 绑 `visible` | `:visible.sync` + `@close` | `<el-dialog v-model>`、`<el-drawer v-model>` 共 20+ 处 |
| 日期格式串 | `value-format="YYYY-MM-DD"`（dayjs） | `value-format="yyyy-MM-dd"`（Element UI） | **15 处**，漏改会静默拿到错误格式/空值 |
| 链接按钮 | `<el-button link type="primary">`（Element Plus 独有布尔属性） | `<el-button type="text">` | 参考仓库多处（如 `DocItemsTable`、`DocListPage` 操作列） |
| 消息 API | `import { ElMessage, ElMessageBox } from 'element-plus'` | `this.$message` / `this.$confirm`（或 `import { Message, MessageBox } from 'element-ui'`） | `ElMessage` 187 处、`ElMessageBox` 42 处、`from 'element-plus'` 23 处 |
| 表格/插槽 | `<template #default="scope">` / `#header`（Vue 2.6 也支持 `#` 语法） | 同名（`v-slot` 在 2.6 可用） | `#default` 135 处、`#header` 28 处 → 大多可保留 |
| 分页 | `v-model:current-page` / `v-model:page-size` | `:current-page.sync` / `:page-size.sync` | `el-pagination` 8 处 |
| 全局状态 | Pinia `defineStore`（`web/src/stores/auth.ts`） | Vuex（目标已有 `src/store/modules/*`） | 1 个 store，但影响路由守卫 |
| 路由 | `createRouter` + `createWebHistory` + 返回式守卫 | `new Router` + `mode: 'history'` + `next()` 回调式守卫 | 需重写 `beforeEach` |
| 样式穿刺 | `:deep()` | `/deep/` 或 `::v-deep` | 实测 `:deep(` **0 处** → 无负担 |
| 其他 | `Teleport`/`el-segmented`/`el-space`/`el-table-v2`/`el-select-v2` | 不存在或不同 | 实测全部 **0 处** → **无负担** |

`v-hasPermi`（目标侧 161 处）是**目标已有**的按钮权限指令，参考仓库对应的是自研 `v-perm` + `ElMessage` 隐藏逻辑。
移植时应统一用 `v-hasPermi="['模块:实体:动作']"`，并保留"服务端才是真防线"的注释语义（`docs/12-erp-system-design.md` §3.6 line 349）。

---

## 6. 权限模型

### 6.1 三层结构（BR-V2-08）

| 层 | 控制对象 | 参考仓库实现 | 目标侧对应物 |
|---|---|---|---|
| 菜单权限 | 导航可见性 | `permissions.py:297-314` `_filter_menu`/`menu_tree_for`，登录时下发裁剪后 `menus[]` | RuoYi `sys_menu` + `SysMenuServiceImpl.getMenuTreeByUserId` |
| 按钮权限 | 动作可点 | `type=button` 的权限点；服务端 `require_perm`；前端 `v-perm` 仅改善体验 | `sys_menu.menu_type='F'` + `@PreAuthorize("@ss.hasPermi('...')")` + `v-hasPermi` |
| 数据范围 | 能看到哪些数据 | `apply_data_scope` 在查询上拼 `org_id`/`created_by` 条件 | `@DataScope(deptAlias=..., userAlias=...)` AOP 拼进 `params.dataScope` |

**权威声明**：**权限判定只在服务端执行**，前端隐藏按钮仅改善体验（`app/services/permission_service.py:9`、AC-V2-41、`docs/16-permission-matrix.md:40`）。

### 6.2 权限点命名规则

- 参考仓库：**点分隔**，`<模块>.<实体>.<动作>`，如 `purchase.order.approve`、`master.customer.view`、`stock.balance.view`、`system.print.edit`；
  动作集合：`view`/`create`/`edit`/`delete`/`submit`/`approve`/`void`/`push`/`export`/`import`/`log.view`/`resetpwd`/`create`/`download` 等（`app/permissions.py:13-128`）。
- **目标侧必须转换为冒号分隔** `<模块>:<实体>:<动作>`，如 `purchase:order:approve`（`ruoyi-admin/.../SysDictTypeController.java:37`：`@PreAuthorize("@ss.hasPermi('system:dict:list')")`）。
- 权限点类型只有两种：`menu` / `button`（`app/permissions.py:6`、`perm_tree()` 的 `ptype`）。
- **真源是代码常量**而不是数据库：避免"权限点被误删导致系统不可用"（`app/permissions.py:4-6`）。
  → 目标侧 RuoYi 的真源是 `sys_menu` 表 + `sys_role_menu`；移植时需要一次性 SQL 把 99 个权限点灌进 `sys_menu`（menu 型进菜单树，button 型挂为子节点）。
- 通配展开：`expand_perms(["purchase.*"])` 供预置角色用（`permissions.py:273-285`）；目标侧无等价物，建议在 SQL 初始化时展开为显式行。

### 6.3 数据范围如何生效

- 枚举与顺序：`SELF(本人) < DEPT(本部门) < DEPT_SUB(本部门及下级) < ALL(全部)`（`permissions.py:199-207`）。
- **多角色取最宽**：`widest_scope()` 按 `_SCOPE_RANK` 取最大（`permissions.py:288-294`、BR-V2-08）；
  superadmin 直接 `ALL`（`permission_service.py:114-116`）。
- 生效条件（`apply_data_scope`，`permission_service.py:119-146`）：
  - `ALL` → 不过滤；
  - `SELF` → `model.created_by == user.id`；
  - `DEPT` → `model.org_id == user.org_id`（`user.org_id` 为空时回落 SELF）；
  - `DEPT_SUB` → `model.org_id IN descendant_org_ids(user.org_id)`，**下级用物化路径 LIKE 前缀**匹配
    （`descendant_org_ids`，`:99-109`：`WHERE path LIKE '<node.path>%'`，含自身）。
- **适用范围**：模型须有 `org_id` + `created_by`；**主数据不做隔离**（`permission_service.py:123-124`）。
- **导出也必须同数据范围**：导出与列表共用 `_build_query`，防越权导出（`doc_routes.py:77`、AC-V2-40）。
- 详情/打印越权 → 403「无权查看该单据（超出数据范围）」（`doc_routes.py:160-162`、`:278-280`）。
- ⚠️ **目标侧差异**：RuoYi 的 `DataScopeAspect` 用**部门树 `ancestors`** + 5 个枚举值
  （`DATA_SCOPE_ALL="1"`、`CUSTOM="2"`、`DEPT="3"`、`DEPT_AND_CHILD="4"`、`SELF="5"`，
  `ruoyi-framework/.../DataScopeAspect.java`），且它默认按 `dept_id`/`user_id` 列名拼 SQL。
  → 移植时需要：① 把 `SELF/DEPT/DEPT_SUB/ALL` 映射到 `5/3/4/1`；② 确认单据/合同表的"归属组织"列名（参考仓库叫 `org_id`，RuoYi 惯例是 `dept_id`），
  ③ 因为参考仓库的 `SELF` 用的是 `created_by` 而非 `user_id`，要么改列名要么改 AOP 的 alias。

### 6.4 菜单裁剪方式

- 后端 `menu_tree_for(perms, is_superadmin)`：递归过滤 —— 有子节点的节点只要"任一子节点可见"就保留（父节点自身 perm 不参与判定）；
  叶子节点需 `n["perm"] in perms`（`permissions.py:297-307`）。
- `MENUS` 结构：`{key, title, path, icon, perm, children[]}`；图标名是 **Element Plus 图标名**（`Odometer`/`Files`/`ShoppingCart`/`Sell`/`Box`/`Folder`），
  目标侧 RuoYi 用的是 `svg-icon` 名（`dashboard`/`user`/`system` 等），**必须重新映射**（`permissions.py:146-196` vs `ruoyi-vue-oa-ui-master/src/assets/icons/svg/`）。
- 前端直接渲染后端 `menus`，不在前端重定义菜单（`docs/12-erp-system-design.md` §3.6 line 350）。
- 路由守卫兜底：`meta.perm` 不在 `perms` 即跳 `/403?perm=x`（`router/index.ts:342-345`）。
- **实测复核记录**：`docs/16-permission-matrix.md` 由 `app/tools/audit_role_matrix.py` 自动生成，
  对 8 个角色 × 26 个接口维度做了 200/403 实测，结论"全部通过"（`docs/16-permission-matrix.md:34-36`）。
  **这份矩阵可以直接改写成移植后的验收用例**（把 26 列换成 99 个权限点的抽样）。

### 6.5 认证与口令

- JWT：自签 HS256，`header.payload.signature`，base64url 无填充；有效期 `CTMS_JWT_HOURS` 默认 8 小时；
  密钥自动生成并落盘 `app/data/.jwt_secret`（`app/config.py:46-49`、`app/security.py:1-9`）。
- **无状态令牌无法服务端吊销**，靠**每次请求回查 `users.status`** 实现"停用立即失效"（`permission_service.py:74-77`）。
- 口令：`pbkdf2_sha256$120000$salt$hash`，`hmac.compare_digest` 比对，解析异常一律视为不匹配（防账号枚举）（`security.py:42-58`）。
- 调试开关 `CTMS_AUTH_ENABLED=0` 时跳过认证，用第一个 `is_superadmin` 账号兜底（`permission_service.py:39-44, 62-63`、`config.py:46`）。
  → **移植时不要把这个开关带过去**（生产环境的安全隐患）。
- `must_change_pwd`：新账号/重置密码后为 True，登录后被前端强制跳 `/change-password`（路由守卫第 ⑤ 步）。

---

## 7. 测试与验收基线

### 7.1 测试文件清单与覆盖范围

| 文件 | 行数 | 测试数 | 覆盖范围 | 可复用为移植验收清单？ |
|---|---|---|---|---|
| `app/tests/conftest.py` | 57 | — | 全局夹具：把测试隔离到**临时 SQLite 库**（原先直接连真实业务库） | 否（基础设施） |
| `app/tests/test_unit.py` | 34 | 9 | BR 纯函数单测：MVP3 编号格式、**质保到期算法（BR5/Q2）**、编号正则 | **是**（算法口径，最高价值） |
| `app/tests/test_v2_auth.py` | 253 | 34 | 安全基础、权限清单、系统参数、权限种子；BR-V2-08/09/10/11 | 部分 |
| `app/tests/test_v2_auth_api.py` | 193 | 17 | 认证/授权/改密接口；AC-V2-01/02/08/41 | 是 |
| `app/tests/test_v2_api_gate.py` | 206 | 14 | 接口鉴权收口：未登录拦截；**字典读接口登录即可、写接口需 `system.dict.edit`** | 是 |
| `app/tests/test_v2_contract_perm.py` | 254 | 14 | 合同模块权限与数据范围；AC-V2-03/04/05 | 是 |
| `app/tests/test_v2_org.py` | 223 | 18 | 组织树、路径物化、删除校验、防环、权限拦截；AC-V2-07 | 是 |
| `app/tests/test_v2_role_user.py` | 298 | 23 | 角色与账号管理；AC-V2-03/04~06/08/41 | 是 |
| `app/tests/test_v2_master.py` | 518 | 42 | 客户/供应商/商品类型树/单位/仓库/物料；AC-V2-09/10/11/12；含 `TestMasterPermissions` | **是**（主数据规则最全） |
| `app/tests/test_v2_parties.py` | 283 | 15 | 合同甲乙方档案化 + 历史文本迁移；AC-V2-33/34/35 | **是** |
| `app/tests/test_v2_docs.py` | 694 | 32 | 单据公共层、采购线、库存过账、盘点；AC-V2-13~32、AC-V2-40（导出） | **是**（单据规则最全） |
| `app/tests/test_v2_sales.py` | 208 | 3 | 销售申请→销售订单→出库单；AC-V2-16/20/21/31 同构 | 是 |
| `app/tests/test_v2_dashboard.py` | 185 | 5 | 看板按角色重规划（待办/库存预警/合同概览） | 是 |
| `app/tests/test_v2_system.py` | 290 | 22 | 系统参数/编号规则/操作日志/变更历史/备份/关于；AC-V2-36/38/39 | 是 |
| `app/tests/test_v2_1_contract_items.py` | 135 | 7 | 合同行项绑定物料（AC-V2.1-04、BR-V2.1-02） | **是** |
| `app/tests/test_v2_1_approve_push.py` | 248 | 16 | 自审例外（修订 AC-V2-15/BR-V2.1-05）、**下推归零即完成**（BR-V2.1-06） | **是** |
| `app/tests/test_v2_1_stock.py` | 180 | 10 | 库存「货品总额度」、数量/物料类型筛选（BR-V2.1-09、AC-V2.1-11/12） | **是**（易错口径） |
| `app/tests/test_v2_1_transfer.py` | 196 | 9 | 调拨单：创建/两仓不可相同/审核同事务过账/幂等/红冲（AC-V2.1-13~18） | **是** |
| `app/tests/test_v2_2_print_template.py` | 240 | 15 | 打印模板：列表 8 类 + 编辑元数据、未自定义=出厂、保存回读、字段/列/签字栏生效、预览（BR-V2.2-02） | **是** |
| `app/tests/smoke_p0.py` | 198 | 12 checks | 原型 P0 全套（AC-01~AC-11，含 V2.0 认证回归）；**HTTP 黑盒** | 是（端到端） |
| `app/tests/smoke_m1.py` | 387 | 8 | M1 验收冒烟（AC-V2-01~12 + 系统管理/迁移） | 是 |
| `app/tests/smoke_m2.py` | 326 | — | M2 采购线与库存过账（AC-V2-13~26 + 附件/合同互动） | 是 |
| `app/tests/smoke_m3.py` | 292 | — | M3 销售线 + 盘点 + 导出 + 打印 | 是 |
| `app/tests/drill_backup_restore.py` | 136 | — | 备份/恢复演练（T-V2-40/AC-V2-39）：登录→生成备份→下载 zip→校验内容→`PRAGMA integrity_check` | 部分（运维） |
| `web/tests/e2e/*.spec.ts` | 16 个 spec | — | Playwright E2E：01 认证、02 看板、03 主数据客户、04 权限、05 合同、06 采购、07 库存、08 系统、09 销售、10 布局、10-v2.2-UI（6 条对应用户反馈）、11 主数据物料、12 未保存守卫、13 行内校验、14 快速新增、15 经办人选择 | 部分（**业务流可复刻，脚本不能直接跑**） |

### 7.2 AC 编号体系

| 体系 | 数量 | 权威文档 | 测试映射 |
|---|---|---|---|
| `AC-01 ~ AC-15` | 15 | `docs/01-requirements.md` §7（P0/P1 标注） | `smoke_p0.py` |
| `AC-16 ~ AC-19` | 4 | `docs/06-mvp2-adjustments.md` §2（行项/导入/导出配置/框架标签树） | MVP2 冒烟 |
| `AC-V2-01 ~ AC-V2-42` | 42 | `docs/11-erp-requirements.md` §16（line 860-1089） | 见 `docs/20-v2-acceptance-summary.md:59-100` 的逐条映射表 |
| `AC-V2.1-01 ~ AC-V2.1-21` | 21 | `docs/22-v2.1-requirements.md` §5（line 188-303） | `test_v2_1_*.py` |
| `AC-V2.2-P1 ~ P4` | 4 | `docs/25-v2.2-adjustments.md` §5（对应 4 条用户反馈） | `test_v2_2_print_template.py`、`web/tests/e2e/10-v2.2-ui.spec.ts` |

**可直接当移植验收清单的条目**（`docs/20-v2-acceptance-summary.md:59-100`，全部 ✅）：

| AC | 内容 | 建议在目标侧的验法 |
|---|---|---|
| AC-V2-01/02 | 登录鉴权（错密码 401 + 写日志）；未登录拦截（接口 401） | OA 侧已具备，做冒烟 |
| AC-V2-03 | 越权调接口返回 403 | 逐权限点抽样（用 `docs/16-permission-matrix.md` 矩阵） |
| AC-V2-04/05/06 | 数据范围 本人 / 本部门及下级 / 多角色并集取最宽 | **必测**（口径差异最大，见 §6.3） |
| AC-V2-07 | 组织节点删除校验（有子/有账号拒绝） | RuoYi `SysDept` 已有类似校验 |
| AC-V2-08 | 首登强制改密 + 管理员重置 | OA 侧机制不同，需单独设计 |
| AC-V2-09/10/11 | 商品类型树（非叶子拒绝）/ 物料编码自动生成唯一 / 计量单位小数位 0~4 | **必测** |
| AC-V2-12 | 客户/供应商停用（下拉隐藏、历史显示名称快照） | **必测**（快照口径） |
| AC-V2-13/14/17 | 提交与审核 / 驳回回草稿记原因 / 作废留痕且默认不进列表 | **必测** |
| AC-V2-15 | 创建人不可自审（V2.1 修订为**管理员例外**） | **必测**（修订后口径容易做错） |
| AC-V2-16/18 | 下推按剩余量约束（100→60，剩余 40，填 50 被拦）/ 已入库数量回写 | **必测** |
| AC-V2-19~23 | 入库加库存、出库减库存、负库存拦截（库存与状态均不变）、反审核红冲（追加负向流水、原流水保留）、过账幂等 | **必测（最高风险）** |
| AC-V2-24 | 有下游单据时禁止反审核 | **必测**（注意 `void` 未拦，见 §3.9.4） |
| AC-V2-25/26 | 流水下钻含变动后结存 / **结存 == 流水累计（recalc）** | **必测（不变式）** |
| AC-V2-27~30 | 全盘按结存生成且账面只读 / 盘亏自动生成盘亏出库并过账 / 盘盈生成盘盈入库 / 抽盘按类型或指定物料 | **必测** |
| AC-V2-31/32 | 单据关联合同（下拉仅同方向）/ 合同详情关联单据**只读**汇总（不改合同金额） | **必测** |
| AC-V2-33/34/35 | 甲乙方档案化（id + 名称快照）/ 历史文本兼容 / 历史档案认领三步 | 迁移相关，按需 |
| AC-V2-36/37 | 操作日志（动作+时间筛选）/ 变更历史带操作人（V1.0 显示"—"） | 可复用 OA 操作日志 |
| AC-V2-38/39 | 系统管理整合 / 手动备份下载（zip 含库与附件） | 备份口径需换（MySQL） |
| AC-V2-40 | **导出与筛选一致**（7 类单据 + 库存；条数与列同列表、同数据范围） | **必测（易越权）** |
| AC-V2-41/42 | 权限后端强校验 / 数据范围与主数据边界 | **必测** |
| AC-V2.1-04 | 合同行项必须绑定物料（未选即保存被拒） | **必测** |
| AC-V2.1-06~08 | 管理员可自审 / 无剩余可下推量时隐藏按钮 / 下推完毕自动置已完成 | **必测** |
| AC-V2.1-11/12 | 货品总额度口径（含红冲刺减、排除调拨入库）/ 数量与物料类型筛选 | **必测（口径最易写错）** |
| AC-V2.1-13~18 | 调拨单全链路（两仓不同、同事务过账、不足拦截、幂等、红冲） | **必测** |
| AC-V2.2-P2/P3/P4 | 未自定义=出厂且与旧硬编码版面逐项一致 / 保存后回读生效 / 预览用未保存配置 | **必测** |

### 7.3 验收文档索引

`docs/13-m1-acceptance.md`、`docs/14-m2-acceptance.md`、`docs/18-m3-acceptance.md`、`docs/20-v2-acceptance-summary.md`、`docs/24-v2.1-acceptance.md` 是逐条 AC 的实证记录（含 PASS 与执行脚本名），
`docs/16-permission-matrix.md` 是自动生成的权限实测矩阵，`docs/17-uat-plan.md`、`docs/19-go-live-checklist.md` 是 UAT 与上线门禁。
**移植时建议直接把这些文档当作"需求可追溯性矩阵"使用**，逐条勾选新实现。

---

## 8. 移植难点与风险清单

量级口径：**S** ≈ ≤1 人日；**M** ≈ 2~5 人日；**L** ≈ 1~3 人周；**XL** ≈ ≥1 人月。

| # | 难点 | 参考仓库位置 | 目标侧对应物 | 量级 | 风险说明 |
|---|---|---|---|---|---|
| 1 | **SQLAlchemy → MyBatis-Plus**：声明式关系、`lazy="joined"/"selectin"`、`cascade="all, delete-orphan"`、`@declarative_mixin`、自引用关系全部要手工改写 | `app/models.py:147-155`、`app/models_doc.py:62-118`、`app/models_auth.py:64-69` | `ruoyi-system` 的 `domain/` + `mapper/*.java` + `resources/mapper/*.xml` 三层模式 | **XL** | 39 张表；`DocMixin`/`DocItemMixin` 的继承要用 `@MappedSuperclass` 或直接展开；8 类单据 × 2 张表；`cascade` 语义要换成 `ON DELETE CASCADE` + 手工级联 |
| 2 | **DML 语义迁移**：`db.query(...).filter(...)`、`or_`、`group_by/having`、`func.coalesce(func.sum(...))`、`subquery()`、`isnot`、`like`、`.any()` 子查询 | `app/routers/stock.py:214-244`、`app/routers/contracts.py:586-613`、`docs/16` 相关统计 | MyBatis XML 动态 SQL（`<if>`/`<foreach>`/`<choose>`） | **L~XL** | 尤其 `doc_service.apply_doc_filters`（关键词跨 5~8 个字段 OR）、标签**交集**查询（`GROUP BY ... HAVING COUNT = n`）、货品总额度子查询 |
| 3 | **权限注解化**：`Depends(require_perm("a.b.c"))` → `@PreAuthorize("@ss.hasPermi('a:b:c')")`；且**权限点真源从代码常量搬到 `sys_menu` 表** | `app/permissions.py:13-128`、`app/services/permission_service.py:81-94`、`doc_routes.py:40-46` | RuoYi `@PreAuthorize` + `sys_menu`/`sys_role_menu` | **M~L** | 99 个权限点需生成初始化 SQL；分隔符 `.`→`:`；`doc_routes` 工厂的"权限点由参数拼出"在 Java 里要么写 8 个 Controller，要么用 SpEL 动态表达式（**建议显式写，可读性与可审计性更好**） |
| 4 | **数据范围口径差异** | `app/services/permission_service.py:99-146`、`app/permissions.py:199-207` | `@DataScope` + `DataScopeAspect`（`DATA_SCOPE_ALL="1"/CUSTOM="2"/DEPT="3"/DEPT_AND_CHILD="4"/SELF="5"`） | **M** | ① 参考仓库 `SELF` 用 `created_by`，RuoYi 默认 `user_id`；② 参考仓库 `DEPT` 用 `org_id`，RuoYi 默认 `dept_id`；③ 参考仓库多角色**取最宽**，RuoYi 是**多角色 OR 叠加**（语义接近但不等价，需实测）；④ 参考仓库对**主数据不做隔离**，RuoYi 若给主数据加了 `@DataScope` 会误隔离 |
| 5 | **库存过账的事务与并发**：参考仓库靠"服务层不 commit + 路由层一次提交" + SQLite WAL 串行写；MySQL 下要显式事务 + 行锁（`SELECT ... FOR UPDATE`） | `app/services/posting_service.py:11, 33-42`、`app/database.py:27-30` | Spring `@Transactional(rollbackFor=Exception.class)` + MyBatis-Plus 悲观锁/乐观锁 | **L** | 参考仓库 `_get_stock` 的注释就写了"抽为独立函数便于将来切 PostgreSQL 行锁"（`posting_service.py:34`）→ 移植时必须补锁，否则并发审核会丢更新；另 `stocks.qty == SUM(ledger)` 不变式必须保留 `recalc` 运维接口 |
| 6 | **单据工厂 → 8 套 Controller/Service/Mapper**：`register_doc_routes` 一套代码撑 8 类单据 13 个端点 | `app/routers/doc_routes.py:36-281` | Java 侧建议抽 `AbstractDocController<T>`/`AbstractDocService<T>` 泛型基类 + 8 个薄子类 | **L** | 不抽基类会 8 倍拷贝；抽得太狠又会在 MyBatis-Plus 泛型 Mapper 上踩坑（`BaseMapper<T>` 可用，但自定义 XML 需每表一份） |
| 7 | **编号规则**：两套并存（合同 `numbering.py`、单据/主数据 `numbering_service.py`）；"取库内最大值 +1"需要并发保护 | `app/numbering.py:20-39`、`app/services/numbering_service.py:44-64, 112-125` | `ruoyi-serial` 的 `ICodeGenService.getNextCode(confId)` + `SeqResetTypeEnum` | **S~M** | 目标已有序列服务，主要是把 11 类编号配置进 `code_config`；但**合同编号的"类型码+主体码+年度重置"** 需确认 `ruoyi-serial` 的 `RuleTypeEnum` 能表达（`ruoyi-serial/src/main/java/com/ruoyi/serial/enums/RuleTypeEnum.java`） |
| 8 | **打印模板 + A4 HTML 渲染** | `app/services/print_template_service.py`（330 行）、`print_service.py`（336 行，内联 CSS 生成 HTML） | `ruoyi-workflow/print`（`PrintTemplate`/`PrintLog`/`WorkflowPrintController`/`PrintService`） | **M~L** | 目标已有打印模板与打印日志；但参考仓库的**模板 JSON 结构与 4 区块/18 表头字段/13 行项列的目录**是业务资产，需要把 `print_templates.config` 的结构映射到目标表；另"预览用未保存配置渲染内置样例"这一交互目标侧没有，要新做 |
| 9 | **Vue3 → Vue2 全量重写** | `web/src`（54 个 `<script setup>`；`computed` 59、`onMounted` 38、`useRoute` 34、`useRouter` 26） | `ruoyi-vue-oa-ui-master/src/views` + `src/api`（Options API） | **XL** | **35 个页面 + 15 个共享组件**；`ContractsView.vue`(64 KB)、`DocListPage.vue`(33 KB)、`PrintTemplateView.vue`(21.5 KB)、`DocFormPage.vue`(19.7 KB)、`BalanceView.vue`(19 KB)、`TakeForm.vue`(16.8 KB)、`DocItemsTable.vue`(14.2 KB) 是 7 个重头 |
| 10 | **Element Plus → Element UI 语法改写** | 见 §5.4 | element-ui 2.15.14（扩展构建，组件集合基本够用） | **M** | 实测必须改：`<script setup>`(54)、`v-model` 弹层(20+)、`value-format="YYYY-MM-DD"`(15，**漏改会静默出错**)、`<el-button link>`、`ElMessage`(187)/`ElMessageBox`(42)；`el-descriptions`/`el-empty`/`el-drawer`/`el-timeline`/`el-image`/`el-skeleton` **目标侧都有**，无需替换 |
| 11 | **审批流**：参考仓库是**单级**状态机；RuoYi-OA 有 Flowable | `app/services/doc_service.py:166-259` | `ruoyi-flowable`/`ruoyi-workflow` | **M（若保留单级）/ XL（若改多级流程）** | 参考仓库的口径是"单级审核"（`docs/11-erp-requirements.md` §14 / C-05），**业务上没有多级需求**。若强行上 Flowable 会引入非常大的返工（状态机 + 待办来源 + 权限判定全变）。建议**保留单级状态机**，只把它挂在 Flowable 之外 |
| 12 | **附件** | `app/routers/attachments.py`（268 行，白名单/20MB/uuid 文件名/object_type+object_id 通用挂载/按对象鉴权） | `ruoyi-file`（`FileOperateController`/`FileStorageController`）+ 前端 `src/api/file` | **S~M** | 目标已有上传/存储；需要补的是"附件挂到任意业务对象（object_type/object_id）+ 按对象权限鉴权"这层，以及 `stock_transfer` 的映射缺失 |
| 13 | **测试无法直接复用** | `app/tests/*.py`（24 个文件 6,034 行）、`web/tests/e2e/*.spec.ts`（16 个 spec） | JUnit + MockMvc / 前端 E2E（若目标有） | **L** | pytest 的 fixture/临时库机制与 Java 无关；Playwright 脚本里的选择器是 Element Plus DOM；**但 AC 条目本身可以逐条转成 JUnit 用例**（见 §7.2） |
| 14 | **数据库迁移** | `app/db_migrate.py`（自研幂等增量器：补列/补索引/整表重建放约束/回填） | MySQL DDL（`ruoyi-vue-oa-master/sql/`） | **M** | 不能复用；需要**一次性 DDL 脚本**（不是增量器）。好处：MySQL 下可以把 §2.8 的 17 类"应用层强制"字段**改回 NOT NULL 并补 FK**，把约束真正落到 DB |
| 15 | **字典体系** | KV `kv_settings`（`item_types`/`contract_types`/`subjects`/`sys_params`/`number_rules`，`app/dicts.py`） | RuoYi `sys_dict_type`/`sys_dict_data` + 前端 `dict.type.xxx`（实测目标 UI 用 103 处） | **S~M** | 5 个 KV 键要拆成字典类型；`contract_types`/`subjects` 是**带扩展字段的 list[dict]**（code/label/note/enabled），比 RuoYi 字典多两个字段 → 要么加 `remark`/`css_class` 复用，要么建业务配置表 |
| 16 | **文件/编码/时区**：`server_default=func.now()`、`date.today()`、`datetime.now()` 全用**服务器本地时间且 naive** | `app/models.py:142-145`、`app/services/doc_service.py:316-324`、`posting_service.py:80` | Java 侧 `LocalDate`/`LocalDateTime`（RuoYi 惯用 `DateUtils.getNowDate()`） | **S** | 需明确定"业务日期"用哪个时区；`parse_doc_date` 只取前 10 字符按 `YYYY-MM-DD` 解析（`doc_service.py:316-324`） |
| 17 | **金额/数量精度** | `Numeric(14,2)`/`Numeric(16,3)`/`Numeric(14,4)`；`ROUND_HALF_UP` → `quantize(0.01)` | Java 必须用 `BigDecimal` + `RoundingMode.HALF_UP` + 明确 scale | **M** | ⚠️ **绝不能用 `double`/`float`**。参考仓库在 `_apply_items` 里是"**先对行金额舍入到 2 位、再求和**"（BR-V2-13"先舍入再汇总"，`doc_service.py:135, 153-155`）；如果 Java 侧先求和再舍入，会在多行小数单价场景产生 1 分钱差异，导致导出/打印/对账不一致 |
| 18 | **`stocks` 唯一约束 + 并发插入** | `uq_stock_prod_wh`（`app/models_stock.py:42`）+ `_get_stock` 存在则取、不存在则建 | MySQL 唯一索引 + `INSERT ... ON DUPLICATE KEY UPDATE` 或先查后插入配合唯一索引重试 | **S** | 参考仓库靠 SQLite 写串行掩盖了竞态；MySQL 下两个并发审核可能同时判定"不存在"而双双 insert → 需要唯一索引 + 捕获重复键重试 |
| 19 | **`ORDER BY code DESC` 取最大编号的隐含假设** | `numbering_service.py:52-64` | 同 | **S** | 依赖"零填充等宽 → 字典序=数值序"。若 MySQL 排序规则/字符集不同（如 `utf8mb4_general_ci`），结果一致；但若被改成 `ORDER BY id DESC` 或前缀长度变化，会取错号 |
| 20 | **看板按权限裁剪区块** | `app/routers/dashboard.py:87-160`（`_VIEW_PERM`/`_APPROVE_PERM` 映射 + `available:false`） | RuoYi 无对应模式 | **M** | 不能简单照抄成"一个 SQL 全查"。`_VIEW_PERM` 未登记的 kind 会**被跳过**（注释写明是"防新增类型 KeyError 500"）——移植时要保留这个防御，否则新增第 9 类单据会让看板 500 |

**风险最高的三处**（建议先做原型验证再全面铺开）：
1. **过账 + 红冲 + 下推回写的事务一致性**（#5、#18）—— 数据正确性问题，出错会污染库存。
2. **数据范围口径差异**（#4）—— 权限问题，出错会越权或"该看的看不到"。
3. **金额"先舍入再汇总"**（#17）—— 精度问题，出错金额对不上且很难回溯。

---

## 9. 不可直接移植清单（建议复用 OA 侧既有能力）

> 判定原则：参考仓库的这些模块是"为了在没有 OA 平台的情况下自给自足"而写的；RuoYi-OA 已有成熟对应物，
> **照搬会引入两套并行体系**，后续维护成本远大于复用。

| # | 参考仓库模块 | 位置 | 为什么不要照搬 | 建议复用的 OA 侧能力 |
|---|---|---|---|---|
| 1 | **登录 / 账号 / 会话 / JWT** | `app/routers/auth.py`（4 端点）、`app/security.py`（48 行自签 JWT）、`app/services/user_service.py`（159 行） | RuoYi 的认证是平台级能力（Spring Security + 验证码 + 在线用户 + 强退 + 登录日志 + 密码策略 + 多端登录），自研 JWT 还带着"无状态令牌无法吊销"的已知取舍（`permission_service.py:76`）和 `CTMS_AUTH_ENABLED=0` 调试后门 | `ruoyi-framework` 认证链 + `sys_user`/`sys_user_role` + `SysLogininfor` + `SysUserOnline`；前端 `src/api/login.js`、`src/store/modules/user.js` |
| 2 | **角色 / 菜单 / 权限点 / 数据范围** | `app/permissions.py`（325 行）、`app/services/role_service.py`、`app/services/permission_service.py` | 见 §6；参考仓库的权限点真源在**代码常量**（刻意设计），而 RuoYi 的真源是**数据库菜单表**；两套并行会导致"角色配置页有两个" | `sys_role`/`sys_menu`/`sys_role_menu`/`sys_role_dept` + `@PreAuthorize` + `@DataScope` + 前端 `v-hasPermi`（目标 UI 已用 161 处） |
| 3 | **组织架构树** | `app/services/org_service.py`（191 行）、`org_units` 表（物化路径 `path`） | RuoYi 的 `sys_dept` 已有 `ancestors` 物化路径 + `parent_id` + 层级校验 + 数据范围联动；再造一棵树会与 `@DataScope` 的部门口径冲突 | `sys_dept` + `SysDeptServiceImpl` + 前端 `src/api/system/dept.js`、`views/system/dept/index.vue` |
| 4 | **操作日志 / 审计** | `app/services/audit_service.py`（42 行）、`operation_logs` 表、`app/routers/system.py` 的 `/logs` | RuoYi 的 `@Log(title=..., businessType=...)` + `SysOperLog` 是**注解式**的，覆盖面比手工 `audit_service.log(...)` 更全且不会漏 | `ruoyi-common` 的 `@Log` + `LogAspect` + `SysOperLog` + `views/monitor/operlog` |
| 5 | **附件上传/下载/存储** | `app/routers/attachments.py`（268 行）、`app/config.py:42-43` 的白名单/大小限制 | 白名单、大小限制、随机文件名、存储路径管理都是通用能力，参考仓库是手写实现 | `ruoyi-file`（`FileOperateController`/`FileStorageController`）+ 前端 `src/api/file/index.js`；**只保留"附件挂到任意业务对象 + 按对象权限鉴权"这一层业务语义**（`object_type`+`object_id`+`OBJECT_PERMS`） |
| 6 | **打印模板与打印日志** | `app/services/print_template_service.py`、`print_service.py`、`app/routers/print_templates.py`、`print_templates` 表 | 目标已有 `PrintTemplate`/`PrintLog`/`WorkflowPrintController`/`PrintService`（`ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/`） | `ruoyi-workflow/print` + 前端 `src/api/workflow/printTemplate.js`；**只迁模板 JSON 的内容结构**（4 区块 / 18 表头字段 / 13 行项列 / 签字栏）作为配置字典，不要迁表 |
| 7 | **数据字典 KV** | `app/models.py:206-212`（`kv_settings`）、`app/dicts.py`（374 行） | RuoYi 有完整的 `sys_dict_type`/`sys_dict_data` + 缓存 + 前端 `dict.type.xxx` 混入 | `sys_dict_*` + 前端 `src/api/system/dict/*`；`item_types` 直接做成一个字典类型；`sys_params` 用 `sys_config`（`sys_config` 就是 KV 参数表）；`number_rules` 交给 `ruoyi-serial` |
| 8 | **系统参数 / 编号规则配置页** | `app/routers/system.py` 的 `/params`、`/number-rules`；`app/routers/settings.py` | RuoYi 有「参数设置」(`views/system/config`) 与 `ruoyi-serial` 的「编码配置」(`views/serial/config`) | `sys_config` 页面 + `ruoyi-serial` 的 `CodeConfigController`（`ruoyi-serial/src/main/java/com/ruoyi/serial/controller/CodeConfigController.java`）+ 前端 `src/api/serial/config.js` |
| 9 | **数据库备份/恢复** | `app/services/backup_service.py`（114 行：`sqlite3` 在线 backup API + zip + 文件名白名单防穿越） | 它**只对 SQLite 有意义**（代码里已明确：非 SQLite 部署时退化为"仅附件打包"，`backup_service.py:9-10, 59-60`） | MySQL 侧用 `mysqldump`/运维平台/`ruoyi-quartz` 定时任务 + OA 既有备份方案；**文件名白名单防路径穿越**这条安全约定可以保留（对应 `resolve_backup`，`:99-110`） |
| 10 | **审批工作流** | 自研单级状态机（`doc_service.py:166-259`） | ⚠️ **这一条要反过来看**：参考仓库是单级审核（业务确认过，`docs/11-erp-requirements.md` §14），**不要为了"用上 Flowable"而升级成多级流程** —— 那会把状态机、待办、权限判定、办结口径全部推倒重来 | 若未来确需多级：`ruoyi-flowable`/`ruoyi-workflow`；**本期建议保留单级状态机**，只把它接入 OA 的待办（`ruoyi-todo`）以便统一入口 |
| 11 | **前端布局 / 侧栏 / 顶栏 / 面包屑 / 菜单渲染** | `web/src/layout/AppLayout.vue`、`components/TopBar.vue`、`components/MenuTree.vue`、`stores/auth.ts` | RuoYi 的 `src/layout` 已含标签页、面包屑、全屏、主题、多页签缓存等 | `ruoyi-vue-oa-ui-master/src/layout` + `src/store/modules/permission.js`（动态路由）+ `src/store/modules/tagsView.js` |
| 12 | **Excel 导出的基础设施** | `app/routers/export.py` 用 `openpyxl` 手工建表头/列宽/样式（232 行）、`app/routers/imports.py` 手工建 3 个 sheet 模板（375 行） | RuoYi 有 `ExcelUtil<T>` 注解式导入导出（`@Excel` 注解 + `ExcelUtil.exportExcel`） | `ruoyi-common` 的 `ExcelUtil` + `@Excel` 注解（`SysDictTypeController.java:48-53` 是范例）；**保留参考仓库的"导出列可配置 + 记住上次选择 + 导出=当前筛选 + 同数据范围"业务语义**（AC-11/AC-V2-40，`export.py:29-63`） |
| 13 | **文件上传前的类型/大小校验** | `attachments.py:117-143`（后缀白名单 + 逐块累计大小 + 超限删除半成品） | 通用能力 | `ruoyi-file` 的存储配置；但"逐块累计并清理半成品"这个细节值得保留（防超大文件占满磁盘） |
| 14 | **`CTMS_AUTH_ENABLED=0` 调试后门** | `app/config.py:46`、`permission_service.py:62-63` | 生产环境任何人可无认证访问全部数据 | **绝对不要移植** |

**反过来，以下这些是参考仓库独有、OA 侧没有、必须完整移植的业务资产**（不要因为"OA 有类似的"就简化）：

- `compute_warranty_end` 的精确口径与 4 个示例（§3.3）—— OA 侧没有任何质保算法。
- 「货品总额度」按 `biz_type LIKE '%入库%' AND NOT LIKE '%调拨入库%'` 求和的**反直觉口径**（§3.10）—— 写成枚举白名单就会错的。
- 「先舍入再汇总」的行项金额口径（§3.12 BR-V2-13）。
- 自审例外判据 `{admin, superadmin, sysadmin}` 的角色集合（§3.8）—— 容易漏掉 `sysadmin`。
- 下推的"剩余量"三字段分阶段语义（`ordered_qty`/`received_qty`/`shipped_qty`）与"归零即完成"（§3.9.4）。
- 调拨的"两阶段先校验后写入"写法（§3.9.2）。
- 盘点盘亏**豁免负库存校验**的理由与实现（§3.9.3）。
- 软删除 30 天窗口的 `.days` 边界语义（§3.7）。
- 编号服务"必须从前缀之后提取数字"的坑（§3.6 B）。
- 合同编号"类型码+主体码+**按年**重置（而非按月）"与"停用占号不复用"（§3.6 A）。

---

## 附录 A：待确认

以下条目**我在参考仓库中未能找到实现、或发现文档与代码不一致**，列出所查位置，移植前需与业务方确认（不臆测结论）。

1. **合同新增时不重算 `warranty_end`**：`compute_warranty_end` 只在 `_apply_updates`（`PUT` 路径）被调用（`app/routers/contracts.py:171-176`），
   `create_contract`（`:504-559`）内未见调用。所查位置：`contracts.py` 全文、`_apply_updates`、`create_contract`、前端 `ContractsView.vue`。
   → 待确认：新建合同是否依赖前端计算并回传 `warranty_end`；若是，Java 侧必须保证前端不回传时也能算。
2. **"累计已付超出合同金额时给警告但允许保存"未见服务端实现**：`docs/01-requirements.md:83` 有此口径，但
   `app/routers/contracts.py` 全文无 `paid_amount`/`amount` 的比较逻辑。所查位置：`contracts.py` 全文、`_apply_updates`。
   → 待确认：是前端提示、还是该规则实际未实现。
3. **"草稿单据可物理删除"未见端点**：`docs/11-erp-requirements.md:573` 有此口径，但 8 类单据路由（`doc_routes.py`）**没有 DELETE**，
   只有 `void`（作废）。所查位置：`doc_routes.py` 全部装饰器、`doc_service.py` 无 `delete` 函数。
   → 待确认：是"作废即等价于删除"，还是物理删除功能被裁掉了。
4. **作废上游单据未拦下游**：`push_service.assert_no_downstream` 只在 `doc_service.unapprove` 中被调用（`doc_service.py:254`），
   `void`（`:235-244`）没有调用。与 AC-V2-24「有下游单据时禁止反审核」及 BR-V2-17「上游单据被作废/反审核时，若已存在下游单据则拦截并提示」
   （`docs/11-erp-requirements.md:569`）不完全一致。所查位置：`doc_service.py` 的 `void`/`unapprove`、`push_service.py:57-66`。
   → 待确认：作废是否**故意**豁免该检查。
5. **`stock_ledger` 的 `idx_ledger_prod_wh` / `idx_ledger_doc` 索引未在模型中声明**：
   `docs/12-erp-system-design.md:494` 列了这两个索引，但 `app/models_stock.py:56-80` 的 `StockLedger` 只有 `created_at` 的
   `index=True`，没有 `__table_args__`。所查位置：`models_stock.py` 全文、`db_migrate.py` 的 `_ADD_INDEXES`。
   → 待确认：这两个索引是否真的建过（若未建，MySQL 侧**应该建**，否则流水下钻查询会全表扫）。
6. **盘点单反审核未级联处理生成的盘盈/盘亏单**：`stock.py:124-127` 注册 `StockTake` 时只传了 `approve_handler` 与 `export_perm`，
   **没有 `unapprove_handler`**；`push_service._DOWNSTREAM`（`:34-39`）也没有 `stock_take` 的映射。
   而 `docs/11-erp-requirements.md:466` 说"反审核需先反审核其生成的盘盈/盘亏单（或由反审核级联处理，见 BR-V2-06）"。
   所查位置：`stock.py:102-131`、`push_service.py:34-39`、`doc_service.py:247-259`。
   → 待确认：盘点反审核是"无保护直接回到待审核"，还是另有前端拦截。
7. **调拨单附件不支持**：`attachments.py:35-44` 的 `OBJECT_PERMS` 缺 `stock_transfer`；`stock.py:132-161` 已注册调拨单路由与
   `DOC_MODELS`（`models_doc.py:423`）。所查位置：`attachments.py:35-44`、`models_doc.py:415-424`。
   → 待确认：是 V2.1 遗漏，还是调拨单明确不要附件。
8. **`StockTake` 无 `total_amount` 列但代码给它赋值**：`app/routers/stock.py:204` 执行 `doc.total_amount = doc_service.total_amount_of(doc)`，
   而 `StockTake`（`models_doc.py:333-352`）与 `DocMixin` 都没有 `total_amount` 列；`PurchaseRequest`/`SalesRequest` 同样没有该列。
   所查位置：`models_doc.py:62-118, 123-138, 195-210, 333-352`。
   → 待确认：这是"设了瞬时属性不落库"（可接受）还是遗漏建列（若导出/打印依赖它则需补）。
9. **历史甲乙方"生成档案草案时状态=停用 + 名称加标记（待确认）"未在代码中确认**：`docs/11-erp-requirements.md:487` 有此口径；
   对应实现应在 `app/services/migrate_service.py`（164 行）。所查位置：仅读了 `migrate_service.py` 的方法清单与 `party_drafts` 模型，
   **未逐行核对草案的 `status` 与命名规则**。
   → 待确认：草案是否真的落为 `status=disabled` 与名称后缀。
10. **目标侧 element-ui 扩展构建的来源与升级策略**：实测 `ruoyi-vue-oa-ui-master/node_modules/element-ui@2.15.14` 包含
    `descriptions/empty/result/statistic/skeleton/page-header/link/image/avatar`（官方 2.15.14 是否原生包含这些，我**未做外部核对**，
    仅以本地实际安装的包内容为准）。所查位置：`node_modules/element-ui/packages/` 目录枚举、`src/views/system/config/index.vue`。
    → 待确认：若这是被本地 patch/私有源的版本，升级或重装 `node_modules` 后这些组件可能消失，需锁定版本或改依赖来源。

---

## 附录 B：一页速览（给排期用）

| 维度 | 数量 | 移植量级 |
|---|---|---|
| ORM 表 | 39 | XL（MyBatis-Plus + XML） |
| 权限点 | 99 | M~L（生成 `sys_menu` SQL + 注解改写） |
| HTTP 端点 | ~231（8 类单据各 13 条 + 6 个主数据实体各 5 条为工厂展开） | XL（建议泛型基类 + 8 薄子类） |
| 后端业务代码 | 9,385 行 / 48 文件 | XL |
| 前端页面 | **35 视图 + 15 共享组件** / 10,358 行 / 53 条路由记录 | XL（Vue3→Vue2 全量重写） |
| 测试基线 | 24 个 pytest 文件（6,034 行）+ 16 个 E2E spec | L（AC 可复用，脚本不可复用） |
| 最高风险三处 | 过账/红冲/下推事务一致性；数据范围口径差异；金额"先舍入再汇总" | 先做原型验证 |
| 建议复用的 OA 能力 | 认证/角色菜单权限/数据范围/组织树/操作日志/附件/打印模板/字典/参数/编号序列/Excel 导入导出/布局 | 见 §9，共 14 项 |

（全文完）
