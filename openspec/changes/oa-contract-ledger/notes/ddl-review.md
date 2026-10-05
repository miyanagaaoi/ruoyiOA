# B3 域 DDL 字段级评审表（`oa-contract-ledger` · 任务 1.6）

> **本文件是唯一交付物**，对应 `openspec/changes/oa-contract-ledger/tasks.md` 任务 1.6；
> 上游依据：`design.md` D-2 / D-3 / D-5 / D-9、`specs/ctms/*/spec.md`（4 份 delta spec）、
> `doc/2.0/参考仓库-CTMS-移植清单.md` §2.0 / §2.1 / §2.2 / §2.8、`doc/2.0/RuoYi现状勘察.md` §8.2。
> **读法**：本文件只评审**字段、可空性、默认值、索引、外键**，不产出 DDL 语句本身；
> 建表 DDL 属第 2 组（任务 2.1）。字段清单**以参考仓库代码为准**（`F:\dsh\ruoyiOA\.cache\contract-ref\app\`），
> 清单文档（`参考仓库-CTMS-移植清单.md`）与参考代码不一致处一律以代码为准并在 §4 列出差异。
> 表数：**13 张**（与 `tasks.md` §1.7 的表名逐字一致）。表名一律 `t_ctms_` 前缀、单数（参考侧为复数，属命名映射）。

---

## 0. 口径与全局约定

### 0.1 结论取值（三类，逐列必选其一）

| 结论 | 判定 | 依据 |
|---|---|---|
| **加 NOT NULL/FK** | 参考侧**可空**，但目标侧收紧为 `NOT NULL`（并按「FK」列加外键） | `design.md` D-3 第一类；`contract-migration/spec.md`「数据库约束回补范围与口径」；`tasks.md` 2.4 |
| **保持可空** | 参考侧**可空**，目标侧仍可空（可另加索引或**可空外键**，见「FK」列） | `design.md` D-3 第二类 |
| **不变** | 参考侧**DB 层本就非空**，目标侧保持非空；或参考侧已有的约束原样沿用 | `design.md` D-3 第三类（「DB 层本来非空的列保持原状」） |

「参考侧原样」列中的行号一律指 `F:\dsh\ruoyiOA\.cache\contract-ref\` 下的文件；`models*.py` = `app/models*.py`，
`contracts.py` = `app/routers/contracts.py`，`master_service.py` = `app/services/master_service.py`。

### 0.2 目标侧全局约定与类型映射（逐列适用，不再逐表重复理由）

| 参考侧 | 目标侧 | 依据 |
|---|---|---|
| `Integer` 自增主键 | `id varchar(64) NOT NULL`，应用侧 UUID（`IdUtils.fastSimpleUUID()`） | `RuoYi现状勘察.md:1082`；`design.md` D-2 |
| `Boolean` | `char(1)`（**'0'=否 / '1'=是**，注释必须写明） | `RuoYi现状勘察.md:1083` |
| `String(n)` | `varchar(n)` | — |
| `Numeric(14,2)` / `(10,4)` / `(12,3)` / `(14,4)` | `decimal(14,2)` / `(10,4)` / `(12,3)` / `(14,4)`（Java `BigDecimal` + `HALF_UP`，禁 `double`） | `参考仓库-CTMS-移植清单.md` §8 #17；`design.md` D-7 C-1 |
| `Text` | `longtext` | `RuoYi现状勘察.md:1089` |
| `Text`（**仅**业务表 `remark`） | `varchar(1000)` | `RuoYi现状勘察.md:1088` |
| `Date` | `date`；`DateTime` | `datetime` | — |
| `status varchar(16)`（`enabled`/`disabled`） | `enable_flag char(1) NOT NULL DEFAULT '1'`（1=启用／0=停用） | `RuoYi现状勘察.md:1085`；口径二选一见 §4.1-6 |
| `deleted Boolean` | `del_flag char(1) NOT NULL DEFAULT '0'`（0=未删除／1=已删除） | `RuoYi现状勘察.md:1084` |
| `Integer` 引用列 | `varchar(64)` 引用列（被引用侧同为 `varchar(64)`） | 勘察 §8.2 + B1 排序规则提醒（§4.1-8） |
| 无对应 | 审计列 `create_id` / `create_by` / `create_time` / `update_id` / `update_by` / `update_time` | `RuoYi现状勘察.md:1086`；`create_time`←`created_at`、`update_time`←`updated_at`、`create_id`←`created_by` |
| 表属性 | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`（**显式 `COLLATE`**，与被引用表实际排序规则一致） | `RuoYi现状勘察.md:1091`；`sql/二开-2.0-B1-模板新增表.sql:9-12` 的 ERROR 1267 实测 |
| 索引命名 | `uk_<语义>(列…)` / `idx_<语义>(列…)` / `pk(列…)`；外键 `fk_<表语义>_<列语义>(列) -> <被引用表>(列)` | `tasks.md` §1.7 / 2.1 给定的示例命名 |

**审计列块（每张表末尾统一 6 列）**：

| 列 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `create_id` | `varchar(64)` | 参考侧有 `created_by` 的表：**否**；其余表：是 | NULL | 创建人用户 ID（`sys_user.user_id`）。**`BaseEntity` 不含 `createId`**，需业务代码显式 `SecurityUtils.getUserId()`（§4.1-9） |
| `create_by` | `varchar(64)` | 是 | NULL | 创建人登录名快照（`BaseEntity.createBy`） |
| `create_time` | `datetime` | 否 | `CURRENT_TIMESTAMP` | ← `created_at` |
| `update_id` | `varchar(64)` | 是 | NULL | 更新人用户 ID（无追溯要求，不加约束） |
| `update_by` | `varchar(64)` | 是 | NULL | 更新人登录名快照 |
| `update_time` | `datetime` | 否 | `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | ← `updated_at` |

### 0.3 各表「无该列」的口径

参考侧**没有**软删除列的 6 张表（`tags` / `product_types` / `uoms` / `warehouses` / `products` / `party_drafts`）
以及纯关联表 `contract_tag`，目标侧**不新造 `del_flag`**：主数据靠 `enable_flag` 启停用（清单 §2.0-4、§2.2 域级约定），
`contract_tag` 靠外键级联删除。有软删除语义的只有 `t_ctms_contract`（`del_flag/deleted_at/deleted_reason`）
与 `t_ctms_attachment`（`del_flag`）。

---

## 1. 13 张业务表字段级评审

### 1.1 `t_ctms_contract` — 合同主表

参考出处：`models.py:99-162`（`class Contract`，表名 `contracts`）。用途：合同台账主体（登记/编辑/筛选/软删除/框架/质保）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models.py:102` Integer PK 自增、非空 | **不变** —— 参考侧主键本就非空；目标侧按勘察 §8.2 改 `varchar(64)` 应用侧 UUID，非空口径不放宽 |
| `contract_no` | varchar(64) | 否 | NULL | `uk_contract_no(contract_no)` | — | `models.py:103` String(64) 非空、unique+index | **不变** —— BR1 编号唯一；停用占号不复用靠「软删除行仍占唯一索引」实现，无需额外约束 |
| `name` | varchar(255) | 否 | NULL | — | — | `models.py:104` String(255) 非空 | **不变** —— 参考侧非空；规格「新增合同后可在列表检索到」以名称为检索入口 |
| `type` | varchar(16) | 否 | '采购' | — | — | `models.py:105` String(16) 非空、默认 '采购' | **不变** —— 参考侧非空；取值集合落 `sys_dict_data`（D-9），DB 层不建枚举约束 |
| `party_a` | varchar(255) | 否 | '' | — | — | `models.py:106` String(255) 非空、默认 '' | **不变** —— BR-V2-20 要求未绑定档案时以文本兜底展示，故不能改可空 |
| `party_b` | varchar(255) | 否 | '' | — | — | `models.py:107` String(255) 非空、默认 '' | **不变** —— 同上（镜像） |
| `sign_date` | date | 是 | NULL | `idx_contract_sign_date(sign_date)` | — | `models.py:108` Date **可空** | **保持可空** —— BR2 文档（`docs/01-requirements.md:72`）列「签订日期」为必填，但参考模型可空、`contracts.py:69` 明确把它列入「允许显式置空」、编号规则又允许空值取当天月份 → 以参考代码为准（差异见 §4.1-1）；补索引供签订日期区间筛选 |
| `effective_date` | date | 是 | NULL | — | — | `models.py:109` Date 可空（注释「可选」） | **保持可空** —— 参考侧即「可选」 |
| `subject_matter` | longtext | 否 | NULL | — | — | `models.py:110` Text 非空、默认 '' | **不变** —— 参考侧非空；D-7 要求行项摘要真正落库，写入前须保证非空 |
| `amount` | decimal(14,2) | 否 | 0 | — | — | `models.py:111` Numeric(14,2) 非空、默认 0 | **不变** —— C-1 金额口径；参考侧非空 |
| `currency` | varchar(8) | 否 | 'CNY' | — | — | `models.py:112` String(8) 非空、默认 CNY | **不变** —— 币种仅记录不参与换算（BR-V2-14） |
| `subject_code` | varchar(8) | 是 | NULL | `idx_contract_subject_code(subject_code)` | — | `models.py:113` String(8) 可空、无 FK（`db_migrate.py:18` 加列而来） | **保持可空** —— 「主体为空被拒绝」是编号接口层校验（contract-commercials 规格），历史/其他类型合同确无主体码，DB 不设非空 |
| `customer_id` | varchar(64) | 是 | NULL | `idx_contract_customer(customer_id)` | `fk_contract_customer(customer_id) -> t_ctms_customer(id)` | `models.py:115` Integer 可空、无 FK；应用层校验档案存在（`contracts.py:124-134` 422「客户档案不存在」） | **保持可空** —— 采购合同只填 `supplier_id`、销售合同只填 `customer_id`、其他类型两者皆空（business-partners 规格「档案方向按合同类型映射」），**不可能收紧为 NOT NULL**；加**可空 FK** + 索引即可满足「合同引用不存在的客户档案被拒绝」（§4.1-4） |
| `supplier_id` | varchar(64) | 是 | NULL | `idx_contract_supplier(supplier_id)` | `fk_contract_supplier(supplier_id) -> t_ctms_supplier(id)` | `models.py:116` Integer 可空、无 FK；应用层校验（`contracts.py:135-145`） | **保持可空** —— 同上（镜像） |
| `paid_amount` | decimal(14,2) | 否 | 0 | — | — | `models.py:117` Numeric(14,2) 非空、默认 0 | **不变** —— BR3；「已付超出合同金额仍可保存」禁止在 DB 层加 `paid_amount <= amount` 类约束 |
| `has_warranty` | char(1) | 否 | '0' | `idx_contract_warranty(has_warranty, warranty_released, warranty_end)` | — | `models.py:118` Boolean 非空、默认 False | **不变** —— Boolean→char(1)；提醒查询按三列组合过滤，补复合索引 |
| `warranty_amount` | decimal(14,2) | 是 | NULL | — | — | `models.py:119` Numeric(14,2) 可空 | **保持可空** —— 与 `warranty_rate` 二选一互换、关闭质保时清空（`tasks.md` 5.5） |
| `warranty_rate` | decimal(10,4) | 是 | NULL | — | — | `models.py:120` Numeric(10,4) 可空 | **保持可空** —— 同上 |
| `warranty_start` | date | 是 | NULL | — | — | `models.py:121` Date 可空 | **保持可空** —— 未启用质保时为空 |
| `warranty_months` | int(11) | 是 | NULL | — | — | `models.py:122` Integer 可空 | **保持可空** —— 同上 |
| `warranty_end` | date | 是 | NULL | `idx_contract_warranty(has_warranty, warranty_released, warranty_end)` | — | `models.py:123` Date 可空 | **保持可空** —— 关闭质保必须清空（规格三条场景）；提醒查询依赖「未释放且非空」过滤 |
| `warranty_released` | char(1) | 否 | '0' | `idx_contract_warranty(has_warranty, warranty_released, warranty_end)` | — | `models.py:124` Boolean 非空、默认 False | **不变** —— 释放后不再提醒（`contract-commercials` 规格） |
| `warranty_release_date` | date | 是 | NULL | — | — | `models.py:125` Date 可空 | **保持可空** —— 未释放时为空 |
| `warranty_note` | longtext | 是 | NULL | — | — | `models.py:126` Text 可空 | **保持可空** —— 关闭质保时清空的 7 个字段之一 |
| `is_framework` | char(1) | 否 | '0' | `idx_contract_framework(is_framework)` | — | `models.py:127` Boolean 非空、默认 False | **不变** —— 框架树视图与绑定守卫按该列筛选，补单列索引 |
| `parent_id` | varchar(64) | 是 | NULL | `idx_contract_parent(parent_id)` | `fk_contract_parent(parent_id) -> t_ctms_contract(id) ON DELETE SET NULL` | `models.py:128-130` Integer 可空、FK→`contracts.id` ON DELETE SET NULL、index | **保持可空** —— 参考侧已是可空自引用 FK，目标侧原样沿用（含 `SET NULL` 语义）；「一份子合同只能归属一个框架」由应用层守卫（`tasks.md` 4.5），不另加 DB 唯一约束 |
| `arrival_status` | varchar(16) | 否 | '未到货' | `idx_contract_arrival_status(arrival_status)` | — | `models.py:131` String(16) 非空、默认 '未到货' | **不变** —— 与 `status` 互相独立、无联动（`contract-ledger` 规格）；补索引供到货状态筛选 |
| `expected_arrival_date` | date | 是 | NULL | — | — | `models.py:132` Date 可空 | **保持可空** —— 与到货状态配套的可选字段（§3.2） |
| `status` | varchar(32) | 否 | '内部审批中' | `idx_contract_status(status)` | — | `models.py:133` String(32) 非空、默认 '内部审批中'、index | **不变** —— 任意状态可互转、无顺序守卫，故 DB 层不建枚举/顺序约束；状态集合落字典（§4.1-11） |
| `owner_name` | varchar(64) | 是 | NULL | — | — | `models.py:134` String(64) 可空 | **保持可空** —— BR9/Q8：经办人是**自由文本**（非账号引用），允许为空 |
| `dept_id` | varchar(64) | 否 | NULL | `idx_contract_dept(dept_id)` | `fk_contract_dept(dept_id) -> sys_dept(dept_id)` | `models.py:136` `org_id` Integer 可空、无 FK；创建时按当前用户 `org_id` 快照（`contracts.py:545`） | **加 NOT NULL/FK** —— 参考侧可空是 SQLite 加列限制的副作用；创建路径无条件写当前用户组织，且 D-4 的 `DEPT` 档必须命中该列（`contract-ctms` 数据范围规格）→ 收紧为 NOT NULL + FK；残余空值由体检脚本先行阻断（§4.1-9）。列名按目标侧口径改为 `dept_id` |
| `create_id` | varchar(64) | 否 | NULL | `idx_contract_create_id(create_id)` | `fk_contract_create_id(create_id) -> sys_user(user_id)` | `models.py:137` `created_by` Integer 可空、无 FK；创建时快照当前用户 id（`contracts.py:545`） | **加 NOT NULL/FK** —— 同 `dept_id`；`DEPT`/`SELF` 数据范围与「范围外 403」判定都依赖它（§4.1-9） |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models.py:138` Text 可空 | **保持可空** —— 参考侧为无界 Text；按勘察 §8.2 业务表备注统一 `varchar(1000)`（若确认需要超长备注再改 `longtext`） |
| `del_flag` | char(1) | 否 | '0' | `idx_contract_del_flag(del_flag)` | — | `models.py:139` `deleted` Boolean 非空、默认 False、index | **不变** —— 列表默认排除停用（规格三条场景）依赖该列；全表只有一个软删除标记，不引入第二套 |
| `deleted_at` | datetime | 是 | NULL | — | — | `models.py:140` DateTime 可空 | **保持可空** —— 30 天恢复窗口的整数日差判定基准（D-7） |
| `deleted_reason` | longtext | 是 | NULL | — | — | `models.py:141` Text 可空 | **保持可空** —— 「停用原因必填」是**接口层**强制（`contracts.py:817` `min_length=1`），历史/停用后清空行需容许为空 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models.py:142` `created_at` DateTime 非空、`server_default=now()` | **不变** —— 参考侧非空 |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models.py:143-145` `updated_at` 非空、`server_default=now()` + `onupdate=now()` | **不变** —— 参考侧非空 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（目标侧审计惯例） | **保持可空** —— 创建人登录名快照；SQL 初始化/系统写入可能为空，故不加 NOT NULL |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（目标侧审计惯例） | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（目标侧审计惯例） | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_contract_no(contract_no)`；KEY `idx_contract_customer`、`idx_contract_supplier`、`idx_contract_parent`、`idx_contract_status`、`idx_contract_arrival_status`、`idx_contract_dept`、`idx_contract_create_id`、`idx_contract_del_flag`、`idx_contract_sign_date`、`idx_contract_framework`、`idx_contract_subject_code`、`idx_contract_warranty(has_warranty, warranty_released, warranty_end)`；FK `fk_contract_customer`、`fk_contract_supplier`、`fk_contract_parent`（SET NULL）、`fk_contract_dept`、`fk_contract_create_id`。

### 1.2 `t_ctms_contract_item` — 合同行项

参考出处：`models.py:177-203`（`class ContractItem`，表名 `contract_items`）。用途：合同行项明细（MVP2 需求①/BR-V2.1-02）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models.py:182` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `contract_id` | varchar(64) | 否 | NULL | `idx_contract_item_contract(contract_id)` | `fk_contract_item_contract(contract_id) -> t_ctms_contract(id) ON DELETE CASCADE` | `models.py:183` Integer 非空、FK CASCADE、index | **不变** —— 参考侧已是非空 FK + 级联删除，目标侧原样保留 |
| `seq` | int(11) | 否 | 1 | `idx_contract_item_seq(contract_id, seq)` | — | `models.py:184` Integer 非空、默认 1 | **不变** —— 规格「序号按提交顺序重排」；补 `(contract_id, seq)` 复合索引供按序读取 |
| `item_type` | varchar(32) | 否 | '采购' | — | — | `models.py:185` String(32) 非空、默认 `DEFAULT_ITEM_TYPES[0]`='采购' | **不变** —— 参考侧非空；取值集合落 `sys_dict_data.item_types`（D-9） |
| `name` | varchar(255) | 否 | '' | — | — | `models.py:186` String(255) 非空、默认 '' | **不变** —— 参考侧非空；为空时由物料档案名称回落（规格「名称规格回落物料档案」） |
| `spec` | varchar(255) | 否 | '' | — | — | `models.py:187` String(255) 非空、默认 '' | **不变** —— 同上 |
| `qty` | decimal(12,3) | 否 | 0 | — | — | `models.py:188` Numeric(12,3) 非空、默认 0 | **不变** —— 参考侧非空；跨域精度差异见 §4.1-10 |
| `unit_price` | decimal(14,4) | 否 | 0 | — | — | `models.py:189` Numeric(14,4) 非空、默认 0 | **不变** —— 参考侧非空；「单价为负被拒绝」由应用层校验 |
| `total` | decimal(14,2) | 否 | 0 | — | — | `models.py:190` Numeric(14,2) 非空、默认 0 | **不变** —— C-1「先舍入再汇总」的行级结果，参考侧非空 |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models.py:191` Text 可空 | **保持可空** —— 行备注确可为空；长度按勘察 §8.2 备注惯例 |
| `product_id` | varchar(64) | 否 | NULL | `idx_contract_item_product(product_id)` | `fk_contract_item_product(product_id) -> t_ctms_product(id)` | `models.py:196-198` Integer **可空**（虽有 FK 但允许 NULL）、index；应用层三处强制（`contracts.py:283/320/337`、BR-V2.1-02） | **加 NOT NULL/FK** —— 清单 §2.8 #1；规格「未选物料档案被拒绝」与场景「行项缺少物料档案被数据库拒绝」要求在 **DB 层**拒绝空值 |
| `product_code` | varchar(32) | 否 | NULL | — | — | `models.py:199` String(32) 可空；应用层强制 | **加 NOT NULL/FK** —— 清单 §2.8 #1；物料编码快照与 `product_id` 同生同灭，收紧为非空（无 FK，非引用列） |
| `product_name` | varchar(128) | 否 | NULL | — | — | `models.py:200` String(128) 可空；应用层强制 | **加 NOT NULL/FK** —— 清单 §2.8 #1；同 `product_code` |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models.py:201` `created_at` 非空、`server_default=now()` | **不变** —— 参考侧非空 |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（目标侧审计惯例） | **保持可空** —— 行项随主表写入，不单独承担数据范围判定 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 「只改物料绑定」的编辑也要留痕，但列本身可空 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_time` | datetime | 是 | NULL | — | — | 参考侧无对应列（参考侧行项无 `updated_at`） | **保持可空** —— 参考侧未定义行项更新时间，目标侧保留列但不加非空（避免既有无更新时间语义被收紧） |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；KEY `idx_contract_item_contract(contract_id)`、`idx_contract_item_product(product_id)`、`idx_contract_item_seq(contract_id, seq)`；FK `fk_contract_item_contract`（CASCADE）、`fk_contract_item_product`。

### 1.3 `t_ctms_tag` — 标签字典

参考出处：`models.py:165-174`（`class Tag`，表名 `tags`）。用途：全局标签字典（BR7）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models.py:168` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `name` | varchar(64) | 否 | NULL | `uk_tag_name(name)` | — | `models.py:169` String(64) 非空、unique+index | **不变** —— BR7 全局去重；唯一约束是「手动附加的同名标签不被移除」与自动附加共用同一行的前提 |
| `color` | varchar(16) | 是 | NULL | — | — | `models.py:170` String(16) 可空 | **保持可空** —— 参考侧可空（自动创建时按标签总数取模选色，色板见 Open Question 1） |
| `builtin` | char(1) | 否 | '0' | — | — | `models.py:171` Boolean 非空、默认 False | **不变** —— 参考侧非空；内置标签是否沿用参考清单见 design Open Question 1 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models.py:172` `created_at` 非空 | **不变** —— 参考侧非空 |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 标签由运营维护，不承担数据范围判定 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 改名场景需留痕，列可空 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_time` | datetime | 是 | NULL | — | — | 参考侧无对应列（参考侧无 `updated_at`） | **保持可空** —— 参考侧未定义更新时间，不加非空以免虚构约束 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_tag_name(name)`。本表**不设 `del_flag`**（§0.3）；删除标签的引用副作用见 §4.2-4。

### 1.4 `t_ctms_contract_tag` — 合同↔标签关联

参考出处：`models.py:88-96`（`Table("contract_tag")`）。用途：多对多关联 + 自动/手动标记（MVP2 需求④）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `contract_id` | varchar(64) | 否 | NULL | `pk(contract_id, tag_id)` | `fk_contract_tag_contract(contract_id) -> t_ctms_contract(id) ON DELETE CASCADE` | `models.py:93` FK CASCADE、复合 PK 的一部分（非空） | **不变** —— 参考侧非空 FK CASCADE，目标侧原样保留 |
| `tag_id` | varchar(64) | 否 | NULL | `pk(contract_id, tag_id)`、`idx_contract_tag_tag(tag_id)` | `fk_contract_tag_tag(tag_id) -> t_ctms_tag(id) ON DELETE CASCADE` | `models.py:94` FK CASCADE、复合 PK 的一部分（非空） | **不变** —— 同上；补 `tag_id` 单列索引供「标签挂载计数」与标签删除的反查 |
| `auto` | char(1) | 否 | '0' | — | — | `models.py:95` Boolean 非空、`server_default=text("0")` | **不变** —— 参考侧非空且带默认值；`auto` 是「只清理自动项」的唯一依据（`contract-ledger` 规格） |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 关联行为由业务代码写入；`BaseEntity` 无 `createId`（§4.1-9） |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | 参考侧无对应列（参考侧关联表无时间列） | **不变** —— 目标侧按全局审计块建为 `datetime NOT NULL DEFAULT CURRENT_TIMESTAMP`（非空口径与其余 12 张表一致，不涉及对参考侧收紧）；B1 先例 `t_template_submit_scope` 只保留 `create_id`+`create_time`，此处按全局口径补齐 6 列 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 关联行只插入/删除、无更新语义，列为口径一致性保留并留空 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_time` | datetime | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上（关联行无更新语义，不加非空） |

**本表约束/索引汇总**：`PRIMARY KEY(contract_id, tag_id)`；KEY `idx_contract_tag_tag(tag_id)`；FK `fk_contract_tag_contract`、`fk_contract_tag_tag`（均 CASCADE）。本表**无 `id` 列**（复合主键，与参考侧一致）。

### 1.5 `t_ctms_change_log` — 字段级变更历史

参考出处：`models.py:241-267`（`class ChangeLog`，表名 `change_logs`）。用途：字段级前后值（D-10），B3 与 B4 共用。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models.py:251` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `contract_id` | varchar(64) | 是 | NULL | `idx_change_log_contract(contract_id)` | `fk_change_log_contract(contract_id) -> t_ctms_contract(id) ON DELETE CASCADE` | `models.py:252-254` Integer **可空**（由 NOT NULL 放宽，`db_migrate.py:139-143`）、FK CASCADE、index | **保持可空** —— 清单 §2.8 #10：单据侧日志（B4）不写 `contract_id`，靠 `object_type/object_id` 定位；沿用参考侧可空 FK（`SET NULL` 会丢历史，故保留 CASCADE → 目标侧合同为软删除，级联实际不触发） |
| `field_name` | varchar(64) | 否 | NULL | — | — | `models.py:255` String(64) 非空 | **不变** —— 参考侧非空；特殊字段名 `_summary`/`_items`/`_tags`/`_origin`/`status`/`附件`/`deleted` 全部 ≤64 字符 |
| `old_value` | longtext | 是 | NULL | — | — | `models.py:256` Text 可空 | **保持可空** —— 新增场景旧值为空（参考侧统一转字符串后写入） |
| `new_value` | longtext | 是 | NULL | — | — | `models.py:257` Text 可空 | **保持可空** —— 删除/清空场景新值为空（7 个质保字段清空留痕） |
| `note` | longtext | 是 | NULL | — | — | `models.py:258` Text 可空 | **保持可空** —— 备注/原因可选 |
| `source` | varchar(16) | 否 | 'manual' | — | — | `models.py:259` String(16) 非空、默认 'manual' | **不变** —— 参考侧非空；取值 `manual`/`auto` |
| `operator_id` | varchar(64) | 是 | NULL | `idx_change_log_operator(operator_id)` | — | `models.py:261` Integer 可空、无 FK；写日志时从 `db.info["user"]` 取（清单 §2.8 #9） | **保持可空** —— 规格场景「操作人为空时不报错（历史数据）」+ 参考侧 V1.0 历史记录 `operator_*` 为空（`models.py:242-247` 注释）→ **不能**收紧为非空（§4.1-2）；补索引供按操作人检索 |
| `operator_name` | varchar(64) | 是 | NULL | — | — | `models.py:262` String(64) 可空 | **保持可空** —— 姓名快照，与 `operator_id` 同生同灭；历史行为空时前端展示占位符「—」 |
| `object_type` | varchar(32) | 否 | NULL | `idx_object(object_type, object_id)` | — | `models.py:263` String(32) 可空（M2 加列，`db_migrate.py:26`） | **加 NOT NULL/FK** —— 每条日志都必须能定位对象（`tasks.md` 2.4）；参考侧已有回填先例 `UPDATE change_logs SET object_type='contract', object_id=contract_id WHERE object_type IS NULL AND contract_id IS NOT NULL`（`db_migrate.py:102-106`），可作 B3 回填脚本依据；**不加 FK**（多态：合同 `contract`、单据 `purchase_order` 等） |
| `object_id` | varchar(64) | 否 | NULL | `idx_object(object_type, object_id)` | — | `models.py:264` Integer 可空 | **加 NOT NULL/FK** —— 与 `object_type` 成对（同上）；多态引用故不加 FK，仅复合索引 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | `idx_change_log_create_time(create_time)` | — | `models.py:265` `created_at` 非空、`server_default=now()` | **不变** —— 参考侧非空；变更历史时间线按时间倒序，补索引 |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 操作人语义已由 `operator_id`/`operator_name` 承载，审计列不重复承担非空约束 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 变更历史只追加不更新（`t_print_log` 同类的「只追加」惯例） |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_time` | datetime | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；KEY `idx_change_log_contract(contract_id)`、`idx_object(object_type, object_id)`、`idx_change_log_operator(operator_id)`、`idx_change_log_create_time(create_time)`；FK `fk_change_log_contract`（CASCADE）。**注意**：`idx_object` 是 B4 单据变更历史的主查询路径，B3 建成后 B4 无需再改表。

### 1.6 `t_ctms_attachment` — 附件挂载元数据

参考出处：`models.py:215-238`（`class Attachment`，表名 `attachments`）+ `design.md` D-5（目标侧只保留「挂到任意业务对象 + 按对象鉴权」这一层）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models.py:224` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `contract_id` | varchar(64) | 是 | NULL | `idx_attachment_contract(contract_id)` | `fk_attachment_contract(contract_id) -> t_ctms_contract(id) ON DELETE CASCADE` | `models.py:225-227` Integer **可空**（由 NOT NULL 放宽，`db_migrate.py:146-150`）、FK CASCADE、index | **保持可空** —— `contract-migration` 规格 REQ-DATA-003 明确把「附件记录的合同引用」列入必须保持可空；参考侧上传时也只有合同附件才写该列（`attachments.py:158`：`contract_id=object_id if object_type=="contract" else None`）。是否保留该列见 §4.1-5 |
| `file_name` | varchar(255) | 否 | NULL | — | — | `models.py:228` String(255) 非空 | **不变** —— 参考侧非空；原始文件名（白名单后缀校验的对象） |
| `stored_path` | varchar(512) | 否 | NULL | — | — | `models.py:229` String(512) 非空 | **不变** —— 参考侧非空；目标侧走 `ruoyi-file` 的返回相对路径（D-5、`tasks.md` 1.5） |
| `content_type` | varchar(128) | 是 | NULL | — | — | `models.py:230` String(128) 可空 | **保持可空** —— 参考侧可空 |
| `size_bytes` | int(11) | 否 | 0 | — | — | `models.py:231` Integer 非空、默认 0 | **不变** —— 参考侧非空；20MB 上限下 `int` 足够 |
| `del_flag` | char(1) | 否 | '0' | `idx_attachment_del_flag(del_flag)` | — | `models.py:233` `deleted` Boolean 非空、默认 False | **不变** —— 删除后下载接口不再返回该文件、且写 `change_logs`（字段名 `附件`），必须有软删除标记（§2.0-4 三处软删除之一） |
| `object_type` | varchar(32) | 否 | NULL | `idx_object(object_type, object_id)` | — | `models.py:235` String(32) 可空、无索引（M2 加列，`db_migrate.py:28`） | **加 NOT NULL/FK** —— 上传前必做对象白名单与存在性校验（`attachments.py:68-73`、`:83-95`），无对象类型无法鉴权；回填依据是参考侧读接口的回落语义 `att.object_type or "contract"`（`attachments.py:181,257`）→ `UPDATE ... SET object_type='contract', object_id=contract_id WHERE object_type IS NULL AND contract_id IS NOT NULL`（§4.1-3） |
| `object_id` | varchar(64) | 否 | NULL | `idx_object(object_type, object_id)` | — | `models.py:236` Integer 可空、无索引 | **加 NOT NULL/FK** —— 与 `object_type` 成对；多态引用故不加 FK，仅 `(object_type, object_id)` 复合索引（`tasks.md` 2.1） |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models.py:232` `uploaded_at` 非空、`server_default=now()` | **不变** —— 映射为创建时间即「上传时间」（附件元数据行创建后不更新，`uploaded_at` 与 `created_at` 同义）；按 D-5「上传人/时间」由审计列承载，不另造 `upload_*` 列 |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（D-5 要求「上传人」） | **保持可空** —— 即 D-5 的「上传人」；由服务层写入 `SecurityUtils.getUserId()`，不加非空（上传路径多、含 B4 单据附件） |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 上传人姓名快照（双写口径，清单 §2.0-3） |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 附件元数据无更新语义（删除用 `del_flag`） |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_time` | datetime | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；KEY `idx_object(object_type, object_id)`、`idx_attachment_contract(contract_id)`、`idx_attachment_del_flag(del_flag)`；FK `fk_attachment_contract`（CASCADE）。**注意**：附件表**不加**到具体业务表的「多态 FK」（D-5：`object_type` 是多态，改为复合索引 + 应用层存在性校验）。

### 1.7 `t_ctms_customer` — 客户档案

参考出处：`models_master.py:45-68`（`class Customer`，表名 `customers`）。用途：销售方向甲方档案（business-partners 规格）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:50` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `code` | varchar(32) | 否 | NULL | `uk_customer_code(code)` | — | `models_master.py:51` String(32) 非空、unique+index | **不变** —— 规格「重复编码被拒绝」；BR-V2-09 全局唯一（停用项也占用） |
| `name` | varchar(128) | 否 | NULL | `uk_customer_name(name)` | — | `models_master.py:52` String(128) 非空、unique+index | **不变** —— 规格「重复名称被拒绝」 |
| `short_name` | varchar(64) | 是 | NULL | — | — | `models_master.py:53` String(64) 可空 | **保持可空** —— 规格场景「客户简称可为空」（与供应商**刻意不对称**） |
| `tax_no` | varchar(32) | 是 | NULL | — | — | `models_master.py:54` String(32) 可空 | **保持可空** —— 参考侧可空，规格未要求必填 |
| `contact_name` | varchar(64) | 是 | NULL | — | — | `models_master.py:55` String(64) 可空 | **保持可空** —— 同上 |
| `contact_phone` | varchar(32) | 是 | NULL | — | — | `models_master.py:56` String(32) 可空 | **保持可空** —— 同上 |
| `address` | varchar(255) | 是 | NULL | — | — | `models_master.py:57` String(255) 可空 | **保持可空** —— 同上 |
| `bank_name` | varchar(128) | 是 | NULL | — | — | `models_master.py:58` String(128) 可空 | **保持可空** —— 同上 |
| `bank_account` | varchar(64) | 是 | NULL | — | — | `models_master.py:59` String(64) 可空 | **保持可空** —— 同上 |
| `credit_limit` | decimal(14,2) | 是 | NULL | — | — | `models_master.py:60` Numeric(14,2) 可空 | **保持可空** —— 授信额度非必填 |
| `level` | varchar(8) | 是 | NULL | — | — | `models_master.py:61` String(8) 可空（A/B/C/D，非强约束） | **保持可空** —— 规格未要求必填 |
| `enable_flag` | char(1) | 否 | '1' | `idx_customer_enable_flag(enable_flag)` | — | `models_master.py:62` `status` String(16) 非空、默认 'enabled'、index | **不变** —— 参考侧非空；Boolean/枚举→`char(1)` 标志位（§0.2）；档案只做启停用、不做物理删除（域级约定），故不设 `del_flag` |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:63` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_id` | varchar(64) | 否 | NULL | `idx_customer_create_id(create_id)` | `fk_customer_create_id(create_id) -> sys_user(user_id)` | `models_master.py:64` `created_by` Integer 可空、无 FK；创建时写当前用户（`master_service.py:250`） | **加 NOT NULL/FK** —— 清单 §2.8 #15 + `tasks.md` 2.4；主数据不参与数据范围隔离（business-partners 规格「往来单位档案不做数据范围隔离」），故 FK 只做完整性、不用于过滤。**注意**：SQL 初始化的种子档案也必须给 `create_id`（§4.1-9） |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:65` `created_at` 非空 | **不变** —— 参考侧非空 |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:66-68` `updated_at` 非空、`server_default`+`onupdate` | **不变** —— 参考侧非空 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 创建人登录名快照 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_customer_code(code)`、`UNIQUE KEY uk_customer_name(name)`；KEY `idx_customer_enable_flag`、`idx_customer_create_id`；FK `fk_customer_create_id`。本表**不设 `del_flag`**（删除仅在零引用时物理放行，见 §4.2-4 的同类说明）。

### 1.8 `t_ctms_supplier` — 供应商档案

参考出处：`models_master.py:71-96`（`class Supplier`，表名 `suppliers`）。用途：采购方向乙方档案。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:76` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `code` | varchar(32) | 否 | NULL | `uk_supplier_code(code)` | — | `models_master.py:77` String(32) 非空、unique+index | **不变** —— BR-V2-09 |
| `name` | varchar(128) | 否 | NULL | `uk_supplier_name(name)` | — | `models_master.py:78` String(128) 非空、unique+index | **不变** —— BR-V2-09 |
| `short_name` | varchar(64) | 否 | NULL | — | — | `models_master.py:79` String(64) **可空**；应用层必填（`master_service.py:214-220` 修改路径、`:243-245` 新增路径） | **加 NOT NULL/FK** —— 清单 §2.8 #16 + 规格「供应商简称为必填」+ 场景「供应商简称为空被拒绝（数据库直接拒绝）」；迁移场景「简称以原始名称兜底」要求认领新建时不得留空 |
| `tax_no` | varchar(32) | 是 | NULL | — | — | `models_master.py:80` 可空 | **保持可空** —— 参考侧可空 |
| `contact_name` | varchar(64) | 是 | NULL | — | — | `models_master.py:81` 可空 | **保持可空** —— 同上 |
| `contact_phone` | varchar(32) | 是 | NULL | — | — | `models_master.py:82` 可空 | **保持可空** —— 同上 |
| `address` | varchar(255) | 是 | NULL | — | — | `models_master.py:83` 可空 | **保持可空** —— 同上 |
| `bank_name` | varchar(128) | 是 | NULL | — | — | `models_master.py:84` 可空 | **保持可空** —— 同上 |
| `bank_account` | varchar(64) | 是 | NULL | — | — | `models_master.py:85` 可空 | **保持可空** —— 同上 |
| `credit_limit` | decimal(14,2) | 是 | NULL | — | — | `models_master.py:86` 可空 | **保持可空** —— 同上 |
| `level` | varchar(8) | 是 | NULL | — | — | `models_master.py:87` 可空 | **保持可空** —— 同上 |
| `supply_scope` | varchar(255) | 是 | NULL | — | — | `models_master.py:88` String(255) 可空（供货范围） | **保持可空** —— 参考侧可空，规格未要求必填 |
| `payment_days` | int(11) | 是 | NULL | — | — | `models_master.py:89` Integer 可空；应用层禁止负数（`master_service.py:230-234`） | **保持可空** —— 「账期 0 可保存」要求 0 与 NULL 可区分，故不加非空；非负由应用层校验（DB 层不引入第 4 类结论） |
| `enable_flag` | char(1) | 否 | '1' | `idx_supplier_enable_flag(enable_flag)` | — | `models_master.py:90` `status` String(16) 非空、默认 'enabled'、index | **不变** —— 参考侧非空；档案只做启停用 |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:91` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_id` | varchar(64) | 否 | NULL | `idx_supplier_create_id(create_id)` | `fk_supplier_create_id(create_id) -> sys_user(user_id)` | `models_master.py:92` `created_by` 可空、无 FK（`master_service.py:250`） | **加 NOT NULL/FK** —— 清单 §2.8 #15 + `tasks.md` 2.4；种子档案同样必须给 `create_id`（§4.1-9） |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:93` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:94-96` `updated_at` 非空 | **不变** |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 创建人登录名快照 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_supplier_code(code)`、`UNIQUE KEY uk_supplier_name(name)`；KEY `idx_supplier_enable_flag`、`idx_supplier_create_id`；FK `fk_supplier_create_id`。本表**不设 `del_flag`**。

### 1.9 `t_ctms_party_draft` — 历史甲乙方迁移草案

参考出处：`models_master.py:194-213`（`class PartyDraft`，表名 `party_drafts`）。用途：T-V2-14 迁移草案（`tasks.md` 第 7 组）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:203` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `party_type` | varchar(16) | 否 | NULL | `idx_party_draft_type(party_type)` | — | `models_master.py:204` String(16) 非空、index（`customer`/`supplier`） | **不变** —— 参考侧非空；扫描按「档案方向 + 原始文本」聚合的前提 |
| `raw_name` | varchar(255) | 否 | NULL | `idx_party_draft_raw(raw_name)` | — | `models_master.py:205` String(255) 非空、index | **不变** —— 参考侧非空；幂等键的一半（`(party_type, raw_name)`） |
| `contract_count` | int(11) | 否 | 0 | — | — | `models_master.py:206` Integer 非空、默认 0 | **不变** —— 参考侧非空；重复扫描仅刷新计数 |
| `status` | varchar(16) | 否 | 'pending' | `idx_party_draft_status(status)` | — | `models_master.py:207` String(16) 非空、默认 'pending'、index | **不变** —— 参考侧非空；取值 `pending`/`claimed`/`ignored`。**本列是状态枚举，不是启用标志**，故不做 `char(1)` 映射（与主数据 `enable_flag` 语义不同，不冲突） |
| `matched_id` | varchar(64) | 是 | NULL | `idx_party_draft_matched(matched_id)` | — | `models_master.py:208` Integer 可空、**无 FK**（认领后的档案 id） | **保持可空** —— 清单 §2.8 #17 + `tasks.md` 2.4 明确「`t_ctms_party_draft.matched_id` 保持可空（仅加索引）」；且该列**多态**（按 `party_type` 指向客户或供应商），无法加单一 FK（§4.2-7） |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:209` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:210` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:211-213` `updated_at` 非空 | **不变** |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 草案由扫描任务生成（可能无登录用户），**不能**加非空 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上（系统任务写入） |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 状态流转由人工认领触发，但列本身可空 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；KEY `idx_party_draft_type(party_type)`、`idx_party_draft_raw(raw_name)`、`idx_party_draft_status(status)`、`idx_party_draft_matched(matched_id)`。**建议加** `UNIQUE KEY uk_party_draft(party_type, raw_name)` 以在 DB 层兜住「同一方向 + 同一原始文本不得重复建草案」——⚠️ 参考侧该唯一性**只在应用层**（`models_master.py` 无唯一声明），属**新增约束**，列入 §4.1-12 待确认。本表**不设 `del_flag`**（草案靠 `status` 流转，参考侧无删除列）。

### 1.10 `t_ctms_product_type` — 商品类型树

参考出处：`models_master.py:99-125`（`class ProductType`，表名 `product_types`）。用途：物料分类树（物化路径，叶子才可挂物料）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:104` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `parent_id` | varchar(64) | 是 | NULL | `idx_product_type_parent(parent_id)` | `fk_product_type_parent(parent_id) -> t_ctms_product_type(id) ON DELETE SET NULL` | `models_master.py:105-107` Integer 可空、FK→自引用 ON DELETE SET NULL、index | **保持可空** —— 参考侧已是可空自引用 FK，目标侧原样沿用；「有子类型时禁止删除」由应用层守卫（`master_service.py`，`tasks.md` 3.3），`SET NULL` 实际只在兜底路径触发 |
| `code` | varchar(32) | 是 | NULL | `idx_product_type_code(code)` | — | `models_master.py:108` String(32) 可空（物料编码前缀来源） | **保持可空** —— 参考侧可空；无 code 时物料编码退化 `PT{类型id}`（清单 §3.6 B） |
| `name` | varchar(64) | 否 | NULL | — | — | `models_master.py:109` String(64) 非空 | **不变** —— 参考侧非空；「同父下名称唯一」由应用层校验（DB 层无复合唯一） |
| `path` | varchar(255) | 否 | '/' | `idx_product_type_path(path)` | — | `models_master.py:110` String(255) 非空、默认 '/'、index | **不变** —— 物化路径（形如 `/1/5/`），下级查询依赖 `LIKE` 前缀与索引 |
| `level` | int(11) | 否 | 1 | — | — | `models_master.py:111` Integer 非空、默认 1 | **不变** —— 最大 5 级由应用层校验（`MAX_TYPE_LEVEL=5`，`master_service.py:36`） |
| `sort` | int(11) | 否 | 0 | — | — | `models_master.py:112` Integer 非空、默认 0 | **不变** —— 参考侧非空 |
| `enable_flag` | char(1) | 否 | '1' | `idx_product_type_enable_flag(enable_flag)` | — | `models_master.py:113` `enabled` Boolean 非空、默认 True | **不变** —— Boolean→char(1)（§0.2）；参考侧非空 |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:114` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:115` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:116-118` `updated_at` 非空 | **不变** |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列（参考侧 `product_types` 无 `created_by`） | **保持可空** —— 参考侧本身没有创建人列，按全局审计块补齐但不加非空 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

> **审计块口径（本表及 §1.11/§1.12 同理）**：`create_time`/`update_time` 已作为「参考侧 `created_at`/`updated_at` 的 1:1 映射」在本表上方列出，
> 审计块中**不再重复建列**；本表「目标侧追加列」只有 `create_id`/`create_by`/`update_id`/`update_by` 四列。

**本表约束/索引汇总**：`PRIMARY KEY(id)`；KEY `idx_product_type_parent`、`idx_product_type_path`、`idx_product_type_code`、`idx_product_type_enable_flag`；FK `fk_product_type_parent`（SET NULL）。本表**不设 `del_flag`**。

### 1.11 `t_ctms_uom` — 计量单位

参考出处：`models_master.py:128-142`（`class Uom`，表名 `uoms`）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:133` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `code` | varchar(16) | 否 | NULL | `uk_uom_code(code)` | — | `models_master.py:134` String(16) 非空、unique+index | **不变** —— BR-V2-09 单位编码全局唯一（停用项也占用） |
| `name` | varchar(32) | 否 | NULL | — | — | `models_master.py:135` String(32) 非空 | **不变** —— 参考侧非空 |
| `decimals` | int(11) | 否 | 2 | — | — | `models_master.py:136` Integer 非空、默认 2；取值范围 0~4（`master_service.py:37` `UOM_DECIMALS_RANGE=(0,4)`） | **不变** —— 参考侧非空；越界拒绝由应用层校验（规格 AC-V2-11 口径），DB 层不引入 CHECK |
| `enable_flag` | char(1) | 否 | '1' | `idx_uom_enable_flag(enable_flag)` | — | `models_master.py:137` `enabled` Boolean 非空、默认 True | **不变** —— Boolean→char(1) |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:138` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:139` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:140-142` `updated_at` 非空 | **不变** |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 参考侧无创建人列 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_uom_code(code)`；KEY `idx_uom_enable_flag`。本表**不设 `del_flag`**；「被物料引用后禁止删除」由应用层零引用校验（`tasks.md` 3.3）。

### 1.12 `t_ctms_warehouse` — 仓库

参考出处：`models_master.py:145-160`（`class Warehouse`，表名 `warehouses`）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:150` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `code` | varchar(32) | 否 | NULL | `uk_warehouse_code(code)` | — | `models_master.py:151` String(32) 非空、unique+index | **不变** —— BR-V2-09 仓库编码全局唯一 |
| `name` | varchar(64) | 否 | NULL | — | — | `models_master.py:152` String(64) 非空 | **不变** —— 参考侧非空 |
| `address` | varchar(255) | 是 | NULL | — | — | `models_master.py:153` String(255) 可空 | **保持可空** —— 参考侧可空 |
| `keeper_user_id` | varchar(64) | 是 | NULL | `idx_warehouse_keeper(keeper_user_id)` | — | `models_master.py:154` Integer 可空、**无 FK**（清单 §2.8 #17：仅存 id、应用层反查名称） | **保持可空** —— `tasks.md` 2.4 明确「各类仅用于反查名称的引用列保持可空（**仅加索引**）」；不加 FK（仓管员停用/换岗不得阻断历史仓库） |
| `enable_flag` | char(1) | 否 | '1' | `idx_warehouse_enable_flag(enable_flag)` | — | `models_master.py:155` `enabled` Boolean 非空、默认 True | **不变** —— Boolean→char(1) |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:156` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:157` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:158-160` `updated_at` 非空 | **不变** |
| `create_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 参考侧无创建人列 |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_warehouse_code(code)`；KEY `idx_warehouse_enable_flag`、`idx_warehouse_keeper(keeper_user_id)`。本表**不设 `del_flag`**；「有库存或单据记录时禁止删除」属 B4 的引用校验。

### 1.13 `t_ctms_product` — 物料档案

参考出处：`models_master.py:163-191`（`class Product`，表名 `products`）。

| 字段名 | 类型 | 可空 | 默认 | 索引 | FK | 参考侧原样 | 目标侧结论 |
|---|---|---|---|---|---|---|---|
| `id` | varchar(64) | 否 | 无（应用侧 UUID） | `pk(id)` | — | `models_master.py:168` Integer PK 非空 | **不变** —— 主键非空口径不变 |
| `code` | varchar(32) | 否 | NULL | `uk_product_code(code)` | — | `models_master.py:169` String(32) 非空、unique+index | **不变** —— BR-V2-09 物料编码全局唯一；可自动生成（类型码 + 序号）或手工 |
| `name` | varchar(128) | 否 | NULL | — | — | `models_master.py:170` String(128) 非空 | **不变** —— 参考侧非空 |
| `spec` | varchar(128) | 是 | NULL | — | — | `models_master.py:171` String(128) 可空 | **保持可空** —— 行项名称/规格为空时回落物料档案，档案本身规格可空（规格退化处理已定义） |
| `product_type_id` | varchar(64) | 否 | NULL | `idx_product_type_id(product_type_id)` | `fk_product_product_type(product_type_id) -> t_ctms_product_type(id)` | `models_master.py:172-174` Integer **非空**、FK→`product_types.id`、index | **不变** —— 参考侧已是非空 FK + 索引，目标侧原样保留（DB 层即保证「物料必须挂商品类型」） |
| `uom_id` | varchar(64) | 否 | NULL | `idx_product_uom_id(uom_id)` | `fk_product_uom(uom_id) -> t_ctms_uom(id)` | `models_master.py:175` Integer **非空**、FK→`uoms.id`、index | **不变** —— 参考侧已是非空 FK + 索引 |
| `brand` | varchar(64) | 是 | NULL | — | — | `models_master.py:176` String(64) 可空 | **保持可空** —— 参考侧可空 |
| `barcode` | varchar(64) | 是 | NULL | — | — | `models_master.py:177` String(64) 可空 | **保持可空** —— 参考侧可空 |
| `default_price` | decimal(14,4) | 否 | 0 | — | — | `models_master.py:178-180` Numeric(14,4) 非空、默认 0；应用层禁止负数 | **不变** —— 参考侧非空；负数拒绝由应用层校验 |
| `safety_stock` | decimal(14,3) | 是 | NULL | — | — | `models_master.py:181` Numeric(14,3) 可空；应用层禁止负数 | **保持可空** —— 参考侧可空；「未设置安全库存」与「安全库存 0」需可区分 |
| `enable_flag` | char(1) | 否 | '1' | `idx_product_enable_flag(enable_flag)` | — | `models_master.py:182` `status` String(16) 非空、默认 'enabled'、index | **不变** —— 参考侧非空；停用物料的历史单据仍展示名称快照（BR-V2-19） |
| `remark` | varchar(1000) | 是 | NULL | — | — | `models_master.py:183` Text 可空 | **保持可空** —— 参考侧可空 |
| `create_id` | varchar(64) | 否 | NULL | `idx_product_create_id(create_id)` | `fk_product_create_id(create_id) -> sys_user(user_id)` | `models_master.py:184` `created_by` Integer 可空、无 FK（`master_service.py:821` 写入） | **加 NOT NULL/FK** —— 清单 §2.8 #15 + `tasks.md` 2.4；种子物料同样必须给 `create_id`（§4.1-9） |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP | — | — | `models_master.py:185` `created_at` 非空 | **不变** |
| `update_time` | datetime | 否 | CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | — | — | `models_master.py:186-188` `updated_at` 非空 | **不变** |
| `create_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 创建人登录名快照 |
| `update_id` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 无追溯要求 |
| `update_by` | varchar(64) | 是 | NULL | — | — | 参考侧无对应列 | **保持可空** —— 同上 |

**本表约束/索引汇总**：`PRIMARY KEY(id)`；`UNIQUE KEY uk_product_code(code)`；KEY `idx_product_type_id`、`idx_product_uom_id`、`idx_product_enable_flag`、`idx_product_create_id`；FK `fk_product_product_type`、`fk_product_uom`、`fk_product_create_id`。本表**不设 `del_flag`**。

---

## 2. §2.8「应用层强制、DB 层可空」17 类字段——落到 B3 的条目

> 逐条对应 `参考仓库-CTMS-移植清单.md` §2.8 的 17 行（编号保持一致）。结论口径见 §0.1。

### 2.1 落到 B3 的条目（**10 类**）

| # | 参考侧字段 | 目标侧表.列 | 结论 | 理由 |
|---|---|---|---|---|
| 1 | `contract_items.product_id` / `product_code` / `product_name` | `t_ctms_contract_item.product_id` / `.product_code` / `.product_name` | **加 NOT NULL**（`product_id` 另加 FK） | 清单 §2.8 #1；规格「未选物料档案被拒绝」+ 场景「行项缺少物料档案被数据库拒绝」；参考侧应用层三处强制（`contracts.py:283/320/337`、BR-V2.1-02） |
| 2 | `contracts.customer_id` / `supplier_id` | `t_ctms_contract.customer_id` / `.supplier_id` | **保持可空** + 加可空 FK + 索引 | 方向映射（PUR→乙方=供应商、SAL→甲方=客户、其他类型两者皆空）使二者**不可能同时非空**；可空 FK 已能满足「引用不存在的档案被数据库拒绝」（§4.1-4） |
| 3 | `contracts.org_id` / `created_by` | `t_ctms_contract.dept_id` / `.create_id` | **加 NOT NULL + FK** | 清单 §2.8 #3；创建路径无条件写当前用户组织与 id（`contracts.py:545`），D-4 的数据范围判定必须命中这两列（§4.1-9） |
| 9 | `change_logs.operator_id` / `operator_name` / `object_type` / `object_id` | `t_ctms_change_log.operator_id` / `.operator_name` → **保持可空**；`.object_type` / `.object_id` → **加 NOT NULL**（无 FK，多态） | 混合结论（见「结论」列） | 规格场景「操作人为空时不报错（历史数据）」+ 参考侧 V1.0 历史行 `operator_*` 为空 → 操作人不可收紧；而对象定位列可回填（`db_migrate.py:102-106` 是现成回填口径）且每条日志都必须能定位对象（§4.1-2） |
| 10 | `change_logs.contract_id` | `t_ctms_change_log.contract_id` | **保持可空** + 可空 FK + 索引 | 清单 §2.8 #10 / AC-V2-37：单据侧日志不写 `contract_id`，靠 `object_type`/`object_id` 定位；B4 复用同一张表，不能把它锁成非空 |
| 11 | `attachments.object_type` / `object_id` | `t_ctms_attachment.object_type` / `.object_id` | **加 NOT NULL**（无 FK，多态）+ `idx_object(object_type, object_id)` | 上传前必做对象白名单与存在性校验（`attachments.py:68-73`、`:83-95`），无对象类型无法鉴权；历史空值按参考侧回落语义回填（§4.1-3）。⚠️ 规格 REQ-DATA-003 同一段又把这两列列入「保持可空」组（§4.1-3） |
| 12 | `attachments.contract_id` | `t_ctms_attachment.contract_id` | **保持可空** + 可空 FK + 索引 | 清单 §2.8 #12；单据附件为 NULL（`attachments.py:158`）；规格 REQ-DATA-003 明确「附件记录的合同引用保持可空」 |
| 15 | `customers.created_by` / `suppliers.created_by` / `products.created_by` | `t_ctms_customer.create_id` / `t_ctms_supplier.create_id` / `t_ctms_product.create_id` | **加 NOT NULL + FK** | 清单 §2.8 #15 + `tasks.md` 2.4；主数据**不做数据范围隔离**（business-partners 规格），FK 只承担完整性；种子档案须补 `create_id`（§4.1-9） |
| 16 | `suppliers.short_name` | `t_ctms_supplier.short_name` | **加 NOT NULL** | 清单 §2.8 #16 / BR-V2.2-01；规格「供应商简称为必填」+ 场景「供应商简称为空被拒绝」；认领新建时以草案原始名称兜底，不得留空 |
| 17 | `warehouses.keeper_user_id`、`party_drafts.matched_id`（**本类中落入 B3 的两列**；`org_units.leader_user_id` 不建表；其余列属 B4） | `t_ctms_warehouse.keeper_user_id`、`t_ctms_party_draft.matched_id` | **保持可空**（**仅加索引、不加 FK**） | `tasks.md` 2.4 明文：「各类仅用于反查名称的引用列保持可空（仅加索引）」；`matched_id` 还是多态列（按 `party_type` 指向客户或供应商），无法加单一 FK（§4.2-7） |

**落到 B3 的条目数：10 类**（其中 #9 为混合结论、#17 为部分落入）。

### 2.2 明确**不属于本次范围**的条目（7 类 + #17 的剩余列）

| # | 参考侧字段 | 归属 | 说明 |
|---|---|---|---|
| 4 | 8 类单据 `contract_id` | **B4**（`oa-purchase-sales-stock`） | `models_doc.py:76-78`；保持可空（关联非强制，BR-V2-07） |
| 5 | 8 类单据 `org_id` / `created_by` / `created_by_name` | **B4** | `doc_routes.py:57-59`；BR-V2-18 快照口径 |
| 6 | 8 类单据 `handler_name` | **B4** | V2.1/N1 加列，`db_migrate.py:34-41` |
| 7 | 行项 `warehouse_id` / `warehouse_name` | **B4** | `models_doc.py:115-116`；行级仓库回落表头 |
| 8 | 行项 `src_item_id` | **B4** | `models_doc.py:117`；下推来源行 |
| 13 | `stock_ledger.org_id` / `created_by` / `unit_price` | **B4**（库存域） | `models_stock.py:71-73` |
| 14 | `stocks.updated_at` | **B4**（库存域） | `models_stock.py:48-50`；无 `server_default`，由过账服务显式赋值 |
| 17（部分） | `org_units.leader_user_id` | **不建表** | 权限域 4 张表（`org_units`/`users`/`roles`/`role_permissions`）按 `design.md` D-2 与 Goals 复用 OA 侧 `sys_dept`/`sys_user`，**不使用 OA 侧对应物、不建表**；该列无目标侧落点 |
| 17（部分） | `*_dept_id`、`receipt_warehouse_id`、`ship_warehouse_id`、`generated_in_id`、`generated_out_id` | **B4** | `models_doc.py:130/165/172/204/240/344-347` |

> 交叉核对：#1~#17 共 17 类 = B3 全落 9 类 + B3 部分落 1 类（#17）+ B4 7 类，无遗漏、无重复。

---

## 3. 评审记录（**签字区**）

> ⚠️ **本表未经签字前不得进入第 2 组（建表 DDL）**。
> 依据：`tasks.md` 1.6「DDL 草案经评审签字（记录评审人与日期）后方可进入第 2 组」；
> 以及 `design.md` D-3「体检（输出违例清单）→ 回填 → 快照 → 加约束」的执行前置关系。

| 项 | 内容（由人类签字填写） |
|---|---|
| **评审人** | 交付评审方（用户，通过 DSH Web 会话答复） |
| **日期** | 2026-10-05 |
| **结论** | ☑ **通过（可进入第 2 组建表 DDL）**　☐ 有条件通过　☐ 退回修改 |
| 评审意见（可选） | 用户裁决原话：**「全部按文档建议采纳」** —— §4.1 的 12 条待确认项一律采用本表「本表临时结论」列作为定稿口径。 |
| §4.1 待确认项裁决记录（逐条写编号 + 裁决） | **Q1 保持可空**；**Q2 操作人保持可空 + 对象列收紧非空**；**Q3 加 NOT NULL（含历史空值回填口径）**；**Q4 保持可空 + 可空 FK**；**Q5 保留 `contract_id`（可空 + 可空 FK + 索引）**；**Q6 业务表统一 `enable_flag char(1) NOT NULL DEFAULT '1'`**；**Q7 加真实 FK**（接受与既有业务表零 FK 惯例的偏离；已核实 `sys_user`/`sys_dept` 删除为逻辑删除，不阻断流程）；**Q8 建表统一显式 `COLLATE`**（与 `sys_user.user_id`/`sys_dept.dept_id`/`t_ctms_*` 一致，规避 `ERROR 1267`）；**Q9 `create_id`/`dept_id` 收紧 NOT NULL + FK，但体检先行**；**Q10 行项数量沿用 `decimal(12,3)`**（暂不与 B4 的 16,3 统一）；**Q11 补 `contract_statuses`/`arrival_statuses` 两个 `sys_dict_type`**（归第 2 组 2.8 的字典 SQL）；**Q12 新增 `UNIQUE KEY uk_party_draft(party_type, raw_name)`**。 |
| 第 2 组放行确认（签字/日期） | 交付评审方（用户）／2026-10-05 —— 12 条全部裁决完毕，第 2 组（建表 DDL）**已放行**。 |

**放行规则**：§4.1 的 12 条待确认项中，凡标注「影响列结构」的条目必须全部裁决完毕；
未裁决前第 2 组（`tasks.md` 2.1 建表 DDL）不得开工。§4.2 的勘误/观察项不阻塞放行，但需一并确认无误。

> **放行状态（2026-10-05）**：12 条已全部裁决（见上表），**第 2 组已具备开工条件**。
> 口径以本表「本表临时结论」列为准；实现时若发现新冲突，回到本表补充而不是就地改口径。

---

## 4. 一致性自检：待确认项

> 核对基线：参考仓库代码（`.cache/contract-ref/app/`）+ `参考仓库-CTMS-移植清单.md` + `design.md` + 4 份 delta spec + `tasks.md`。
> 本节**只列冲突事实与我的临时结论**，最终裁决权交人类（`tasks.md` 1.6 的签字环节）。

### 4.1 待确认项（12 条：真实冲突，需裁决）

| # | 冲突 | 冲突双方（出处） | 本表临时结论 | 影响列结构？ |
|---|---|---|---|---|
| 1 | **合同签订日期是否必填** | BR2 文档把「签订日期」列为必填（`docs/01-requirements.md:72`，转引清单 §3.12）；参考模型为可空（`models.py:108`），`contracts.py:69` 明确把 `sign_date` 列入「允许显式置空」的字段，编号规则又规定「签订日期为空取当天月份」（`contract-commercials` 规格） | 以参考代码为准：**保持可空** | 是（§1.1 `sign_date`） |
| 2 | **变更历史操作人能否非空** | `design.md` D-3 把「变更历史的操作人」列入「加 NOT NULL + FK」；`contract-ledger` 规格场景「操作人为空时不报错」与参考侧 V1.0 历史数据（`models.py:242-247` 注释）要求可空 | 以规格场景为准：**保持可空**（`operator_id`/`operator_name`）；对象列 `object_type`/`object_id` 收紧为非空 | 是（§1.5） |
| 3 | **附件对象类型/对象标识是否非空（规格内部自相矛盾）** | `contract-migration` 规格 REQ-DATA-003 **同一段**既把「附件记录的对象类型与对象标识」列入「回补数据库约束」组，又列入「保持可空」组；`design.md` D-3 的否决理由也以「附件对象类型在迁移期可能为空」主张可空 | 以应用层真实校验为准：**加 NOT NULL**（历史空值按 `attachments.py:181,257` 的回落语义回填） | 是（§1.6） |
| 4 | **合同客户/供应商档案引用能否非空** | `design.md` D-3 把「合同的客户与供应商档案引用」列入「加 NOT NULL + FK」；但采购合同只有 `supplier_id`、销售合同只有 `customer_id`、其他类型两者皆空（`models.py:114-116`、`contracts.py:117-145`、`business-partners` 规格「档案方向按合同类型映射」） | **保持可空** + 加可空 FK + 索引（`tasks.md` 2.4 已授权按评审表逐列区分） | 是（§1.1） |
| 5 | **`t_ctms_attachment` 是否保留 `contract_id`** | `design.md` D-5 的字段清单只列「对象类型/对象标识/文件名/存储路径/大小/内容类型/删除标记/上传人/时间」**未含 `contract_id`**；但 `contract-migration` 规格 REQ-DATA-003 把「附件记录的合同引用」列入必须保持可空的分组 | 依据规格**保留**该列（可空 + 可空 FK + 索引）；并注明它可由 `(object_type, object_id)` 推导（`attachments.py:158`），若确认冗余可删 | 是（§1.6） |
| 6 | **「启用/停用」列的目标侧口径二选一** | 目标仓库 `sys_*` 表用 `status char(1) DEFAULT '0'`（0 正常 / 1 停用，`table.sql:1319` 起 `sys_dept`）；业务 `t_*` 表用 `enable_flag char(1) DEFAULT '1'`（1 启用，`二开-表单打印.sql:26`、`RuoYi现状勘察.md:1085`）；参考侧用 `status varchar(16) 'enabled'/'disabled'` | 业务表统一 `enable_flag char(1) NOT NULL DEFAULT '1'`（1=启用/0=停用，注释写明） | 是（6 张主数据/树表：客户、供应商、商品类型、单位、仓库、物料） |
| 7 | **业务表加真实 FK 与目标仓库既有惯例冲突** | `design.md` D-3 / REQ-DATA-003 要求真实 FK；但目标仓库业务表（含 V1 二开 7 张）**零 FK**，`table.sql` 中只有 Flowable/Quartz 系统表有 `CONSTRAINT ... FOREIGN KEY` | 按 `design.md` D-3 加 FK；确认业务方接受该惯例偏离（`sys_user`/`sys_dept` 的「删除」实为逻辑删除：`SysUserMapper.xml:280,284`、`SysDeptMapper.xml:159`，故 FK 不会阻断标准删人/删部门流程） | 是（全表） |
| 8 | **FK 与关联查询的排序规则必须显式对齐** | B1 已实测：业务列 `utf8mb4_0900_ai_ci` vs 库默认 `utf8mb4_general_ci`，混用会 `ERROR 1267 Illegal mix of collations`（`sql/二开-2.0-B1-模板新增表.sql:9-12`） | B3 建表统一显式 `COLLATE`，与被引用列（`sys_user.user_id`/`sys_dept.dept_id`/`t_ctms_*`）一致 | 是（全表 DDL 写法） |
| 9 | **`create_id`/`dept_id` 收紧为非空的写入责任** | `createId` **不在** `BaseEntity`（`BaseEntity.java:24-39` 只有 createBy/createTime/updateBy/updateTime/remark/save），需业务代码逐个显式 `SecurityUtils.getUserId()`（如 `TemplateServiceImpl.java:219`）；SQL 种子数据也必须给值 | 按 D-3 收紧为 NOT NULL + FK，但**体检脚本必须先跑**（规格「体检发现违例即阻断」） | 是（合同/客户/供应商/物料 4 表） |
| 10 | **行项数量精度跨域不一致** | B3 合同行项 `contract_items.qty` 为 `Numeric(12,3)`（`models.py:188`）；B4 单据行项为 `Numeric(16,3)`（清单 §2.3.2） | 以参考代码为准：`decimal(12,3)`；是否与 B4 统一为 `decimal(16,3)` 请裁决 | 是（§1.2 `qty`） |
| 11 | **合同进度状态/到货状态是否入字典** | `design.md` D-9 只登记 `item_types`/`contract_types`/`subjects`/`warranty_window_days` 四个键；但 `tasks.md` 4.2 要求「接口返回的字典项与 `sys_dict_data` 一致」，4.3 要求按进度状态与到货状态筛选 | 列结构不受影响（`status varchar(32)`、`arrival_status varchar(16)` 保持）；**建议**补 `contract_statuses`/`arrival_statuses` 两个 `sys_dict_type`（属第 2 组 2.8 的字典 SQL 范围） | 否 |
| 12 | **`t_ctms_party_draft` 是否加 `(party_type, raw_name)` 唯一约束** | 参考侧「同一方向 + 同一原始文本不得重复建草案」**只有应用层保证**（`models_master.py:203-208` 无唯一声明）；规格「重复扫描不重复建草案」是可观察行为 | 本表**建议新增** `UNIQUE KEY uk_party_draft(party_type, raw_name)`；这是参考侧没有的**新增约束**，需裁决 | 是（§1.9，新增索引） |

### 4.2 勘误与非冲突观察（不阻塞放行，已按参考代码处理）

1. **清单 §2.8 #3 的代码引用勘误**：清单记「创建时按当前用户 `org_id`/`id` 快照」的位置为 `contracts.py:831`，
   实测 `contracts.py:831` 是停用接口的 `return`，**真实位置是 `contracts.py:545`**（`org_id=getattr(user,"org_id",None), created_by=getattr(user,"id",None)`）。
   本表引用已按实测行号（`t_ctms_contract` 的 `dept_id`/`create_id` 行）。
2. **表名单复数映射**：参考侧 `contracts`/`contract_items`/`change_logs`/`attachments`/`customers`/`suppliers`/`product_types`/`uoms`/`warehouses`/`products`/`party_drafts`（复数）→
   目标侧按 `tasks.md` §1.7 一律单数 + `t_ctms_` 前缀。属命名映射，非冲突。
3. **`contracts.type` 存中文类型名而非类型码**：参考侧默认值 `'采购'`（`models.py:105`），类型码只在 KV `contract_types`（清单 §3.6 A）；
   目标侧 `varchar(16)` 足够承载中文名，类型码落 `sys_dict_data.dict_value`（D-9）。
4. **删除标签的引用副作用**：BR7 允许删标签；`t_ctms_contract_tag` 两个 FK 均 `ON DELETE CASCADE`，
   删除标签会**静默解除**合同标签关联且**不写变更历史**（参考侧同为 CASCADE，`models.py:93-94`）。
   沿用参考侧语义，实施期注意这属于「可接受但需知晓」的行为。
5. **参考侧「域级约定」中的两处非模型事实**：主数据不做数据范围隔离（`permission_service.py:119-124`，转引清单 §2.2）、
   操作人/姓名双写快照（清单 §2.0-3）——前者影响 FK 的用途（只做完整性不做过滤），后者决定 `create_id`+`create_by`、`operator_id`+`operator_name` 成对存在。
6. **`kv_settings` 不建表**：字典与参数落 `sys_dict_type`/`sys_dict_data`/`sys_config`/`ruoyi-serial`（D-9、`tasks.md` 1.7），
   故 13 张表里没有 `kv_settings` 的目标侧对应物。
7. **`party_drafts.matched_id` 的多态性**：按 `party_type` 指向 `t_ctms_customer` 或 `t_ctms_supplier`，
   无法加单一 FK（这也是 B3 明确「保持可空 + 仅加索引」的原因，`tasks.md` 2.4）。
8. **`attachments.object_id` 与参考侧 `Integer` 的类型变化**：目标侧对象主键统一为 `varchar(64)`，
   故 `object_id` 也必须 `varchar(64)`；参考侧为 `Integer`（`models.py:236`）。同上，`change_logs.object_id` 亦然（`models.py:264`）。

---

## 附录：字段数速查（与 §1 逐表行数一致，行数 = 目标侧最终列数）

| 表 | 目标侧列数 | 参考侧列数 | 目标侧追加列 |
|---|---|---|---|
| `t_ctms_contract` | 41 | 38 | `create_by`、`update_id`、`update_by`（3） |
| `t_ctms_contract_item` | 19 | 14 | `create_id`、`create_by`、`update_id`、`update_by`、`update_time`（5） |
| `t_ctms_tag` | 10 | 5 | 同上（5） |
| `t_ctms_contract_tag` | 9 | 3 | 审计块 6 列 |
| `t_ctms_change_log` | 17 | 12 | 同上（5） |
| `t_ctms_attachment` | 15 | 10 | 同上（5） |
| `t_ctms_customer` | 20 | 17 | `create_by`、`update_id`、`update_by`（3） |
| `t_ctms_supplier` | 22 | 19 | 同上（3） |
| `t_ctms_party_draft` | 13 | 9 | `create_id`、`create_by`、`update_id`、`update_by`（4） |
| `t_ctms_product_type` | 15 | 11 | 同上（4） |
| `t_ctms_uom` | 12 | 8 | 同上（4） |
| `t_ctms_warehouse` | 13 | 9 | 同上（4） |
| `t_ctms_product` | 18 | 15 | `create_by`、`update_id`、`update_by`（3） |
| **合计** | **224** | **170** | **54** |

> 「参考侧列数」= 参考模型里真实存在的列数（`models.py` / `models_master.py`）；
> 「追加列」= 目标侧为审计惯例新增的列（`create_id`/`create_by`/`update_id`/`update_by`/`update_time` 中参考侧没有的那些）。
> 参考侧的 `created_at`/`updated_at`/`created_by` 是**改名映射**为 `create_time`/`update_time`/`create_id`，不计入「追加」。

（完）
