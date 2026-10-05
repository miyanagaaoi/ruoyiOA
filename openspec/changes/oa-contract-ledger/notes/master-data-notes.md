# 主数据档案交付说明（B3 第 3 组）

> 变更集：`openspec/changes/oa-contract-ledger`｜任务：3.1 ~ 3.6
> 规格：`specs/ctms/business-partners/spec.md`（唯一验收真源）
> DDL 真源：`ruoyi-vue-oa-master/sql/二开-合同台账.sql`（第 110~259 行）
> 字段级评审：`ddl-review.md`（§1.7 客户 / §1.8 供应商 / 物料域四张表）
> 运行时验收：`tools/ctms-masterdata-check.ps1`（真实环境、接口级；见第 5 节）

本文件的用途：把"字段清单 / 唯一性口径 / 权限点对应 / 与参考侧差异"一次写清，
**并保证每一条口径都能在规格里找到同名条目、在代码里找到落点、在验收脚本里找到断言**
（任务 3.6 的验证条件）。

---

## 1. 档案清单与字段

### 1.1 客户档案 `t_ctms_customer`（20 列）

| 列 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | varchar(64) | ✅ | 应用侧 UUID（`IdUtils.fastSimpleUUID()`） |
| `code` | varchar(32) | ✅ | 客户编码，**库内唯一**（`uk_customer_code`），停用项也占号 |
| `name` | varchar(128) | ✅ | 客户名称，**库内唯一**（`uk_customer_name`） |
| `short_name` | varchar(64) | ⬜ | **简称选填**（与供应商刻意不对称） |
| `tax_no` | varchar(32) | ⬜ | 纳税人识别号 |
| `contact_name` / `contact_phone` | varchar(64)/(32) | ⬜ | 联系人 / 联系电话 |
| `address` | varchar(255) | ⬜ | 地址 |
| `bank_name` / `bank_account` | varchar(128)/(64) | ⬜ | 开户行 / 银行账号 |
| `credit_limit` | decimal(14,2) | ⬜ | 授信额度（非必填，可空） |
| `level` | varchar(8) | ⬜ | 等级 A/B/C/D（非强约束） |
| `enable_flag` | char(1) | ✅ | 1-启用（默认）/ 0-停用 |
| `remark` | varchar(1000) | ⬜ | 备注（`BaseEntity` 承载） |
| `create_id` | varchar(64) | ✅ | 创建人用户ID（**NOT NULL**；服务端快照，前端传值无效） |
| `create_by` / `create_time` | varchar(64)/datetime | ⬜/✅ | 创建人登录名快照 / 创建时间（`BaseEntity`） |
| `update_id` / `update_by` / `update_time` | — | ⬜/⬜/✅ | 审计列（`update_time` 由 DB 自动更新） |

### 1.2 供应商档案 `t_ctms_supplier`（22 列）

与客户档案**同构**，两处**刻意不同**，其余列完全一致：

| 差异 | 客户 | 供应商 |
| --- | --- | --- |
| `short_name` | 可空（选填） | **NOT NULL（必填）**；新增与修改两条路径都校验，提交空白视同空 |
| 专有列 | — | `supply_scope varchar(255)`（供货范围，可空）、`payment_days int`（账期天数，**非负整数**，0 合法、NULL 与 0 可区分） |

> 参考侧依据：`customers.short_name` 可空、`suppliers.short_name` 非空（移植清单 §1.7/§1.8）。
> 本设计把它从"应用层强制"升级为 **DB 层 NOT NULL**（`REQ-DATA-003` / AC-68），
> 因此"简称必填"有两个独立证据：接口提示 + `information_schema` 的 `is_nullable='NO'`。

### 1.3 物料域四张表（任务 3.3）

| 表 | 列数 | 关键约束（**服务端强制**） |
| --- | --- | --- |
| `t_ctms_product_type` | 15 | 树**最大 5 级**；`path` 物化路径（形如 `/A/B/`，下级查询靠 LIKE 前缀）；**同父下名称唯一**；**仅叶子可挂物料**；有子类型或有物料时**禁止删除** |
| `t_ctms_uom` | 12 | `code` 全局唯一；`decimals` **0~4**；**被物料引用后禁止删除** |
| `t_ctms_warehouse` | 13 | `code` 全局唯一；`keeper_user_id` **仅用于反查名称**（保持可空 + 仅加索引，**不加 FK**） |
| `t_ctms_product` | 18 | `product_type_id` / `uom_id` **DB 层非空 + FK**；`code` 全局唯一（留空时服务端按「类型码 + 4 位序号」生成）；`default_price` 非负；`safety_stock` 非负且 NULL 与 0 可区分 |

> **仓库删除的引用保护属 B4**：B3 阶段没有出入库/库存单据表，`deleteWarehouse` 只做物理删除，
> Javadoc 已注明"引用统计留给 B4"。这不是遗漏，是**边界冻结**（`ddl-scope.md` §2）。

---

## 2. 唯一性与必填口径（逐条对应规格场景）

| 规格场景 | 口径 | 落点 |
| --- | --- | --- |
| 重复编码被拒绝 | `客户编码已存在` / `供应商编码已存在`；**DB 唯一索引兜底** | `PartnerRules` + `uk_customer_code` / `uk_supplier_code` |
| 重复名称被拒绝 | `客户名称已存在` / `供应商名称已存在`；DB 唯一索引兜底 | 同上（`uk_*_name`） |
| 简称可为空（客户） | 客户 `short_name` 留空 → 落库 NULL，保存成功 | `ctms-masterdata-check.ps1` 3.1 段 |
| 供应商简称为必填 | 新增/修改提交空或空白 → 拒绝；**DB NOT NULL** | 3.2 段（含修改路径） |
| 账期天数负数被拒绝 | `账期天数不能为负数`；`0` 与 `NULL` 均可 | `PartnerRules.checkPaymentDays` |
| 账期天数为 0 可保存 | 落库为 `0`（不是被当成空） | 3.2 段 |
| 同名不同父 | 唯一性**只约束"同父"**（不同父下同名允许） | 3.3 段 |

提示文案是**接口契约的一部分**（验收脚本按关键字断言），改动前请同步
`tools/ctms-masterdata-check.ps1` 与 `PartnerRules` / `ProductMasterRules`。

---

## 3. 启停用与引用保护（`REQ-CTMS-*` / 3.4）

1. **档案只做启停用，不提供物理删除能力** —— 规格原文。实现上：
   - `enable_flag='0'` 即停用；停用**不级联**任何数据（"已引用它的历史合同仍正常展示"是自然结果）；
   - `DELETE /ctms/partner/{customer|supplier}/{id}` 仅在**零引用**时放行，
     有引用 → `客户档案已被合同引用，无法删除`（供应商同构）。
2. **"停用后不可新选"落在两个不同的数据源上**，这是本条最容易做错的地方：

   | 数据源 | 接口 | 是否含停用项 | 谁在用 |
   | --- | --- | --- | --- |
   | 合同表单选择器 | `GET /ctms/partner/{customer\|supplier}/options` | ❌ **只返回启用项** | 新增/编辑合同 |
   | 档案管理页 | `GET /ctms/partner/{customer\|supplier}/list`（`enableFlag` 为空 = 不限） | ✅ 含停用项 | 「往来单位」页 |

   若把两者合成一个接口，就会出现"要么停用项还能被选中、要么档案页看不到停用项"的二选一缺陷。
3. **引用统计的表不存在时要能跑通**：`t_ctms_contract` 由第 4 组交付，
   服务层先查 `existsContractTable()`，为 0 时直接把引用数当 0 ——
   避免"模块先上线、合同表后建"的窗口期直接 500。

---

## 4. 权限点与数据范围

### 4.1 权限点对应关系（**本批不新增权限点**）

真源：`ruoyi-vue-oa-master/sql/二开-合同台账-菜单.sql`（25 个 `ctms:*` 权限点，2.7 已交付）。

| 权限点 | 客户/供应商档案 | 物料域四张表 |
| --- | --- | --- |
| `ctms:partner:list` | 列表 / 类型列表 / 类型树 | 列表（四个资源共用） |
| `ctms:partner:query` | 详情 / `options` | 详情 |
| `ctms:partner:add` | 新增 | 新增 |
| `ctms:partner:edit` | 修改 | 修改 |
| `ctms:partner:remove` | 删除（零引用才放行） | 删除 |
| `ctms:partner:status` | 启停用 | —（停用走 `edit`） |

> **为什么物料域复用 `ctms:partner:*`**：物料域四张表同属"档案类主数据"，
> 与往来单位同为 `ctms:partner` 资源的子集；新增一套 `ctms:material:*` 会让
> 第 2 组的菜单 SQL（25 个权限点）与后端注解不一致，直接踩中任务 9.1 的
> 「权限点集合双向核对」门禁。若运营后续要求分离，属**新增权限点**的独立变更，
> 不要在本批偷偷加。（`design.md` D-9 的口径是"权限点按资源-动作生成"。）

### 4.2 不做数据范围隔离（3.5）

- 档案查询**不带**任何创建人/部门条件：具备 `ctms:partner:list` 的账号看到**全部**档案；
- 判定证据（验收脚本 3.5 段）：给低权限角色临时挂上「往来单位」菜单后，
  该账号能看到 `superAdmin` 创建的客户与供应商档案；**摘掉菜单后立即 403**
  （证明这是真权限点门禁，而不是"接口 200 + 前端隐藏"）；
- **合同侧的范围不受影响**：合同的数据范围（D-4 的 `ALL`/`DEPT`/`SELF`）
  只在合同实体上生效，与档案可见性无关 —— 规格场景「档案隔离不影响合同隔离」留给第 4 组（任务 4.8）落地。

---

## 5. 验收资产

| 断言来源 | 覆盖 | 命令 |
| --- | --- | --- |
| 运行时接口验收（**主证据**） | 第 1~4 节全部口径，**79 条断言** | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1` |
| 单元测试（服务层规则，内存桩 Mapper） | 唯一性/必填/引用保护/树与单位约束的编排分支，**61 条** | `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test` |
| 静态审计 | 前端取数纪律（`.then` 无 `.catch`）、模板引用等 | `node tools/audit/run-all.js` |

脚本的两个纪律（本项目踩过的坑，写新脚本时请沿用）：

1. **夹具判据一律用 ASCII 编码/名称**（`C<序号>` / `验收类型L1-<序号>`），中文只用于"提示文案"断言 ——
   中文匹配在本机可靠，但把它当"清干净了没"的唯一判据会引入假失败（`DEV-ENV.md` §6.25）；
2. **收尾必须删干净**：脚本跑前先预清理一次（幂等）、跑后按 code/name 白名单再清一次，
   并且把临时挂给测试角色的 `sys_role_menu` 行摘掉 —— 否则下一轮
   "低权限账号的 403 断言"会因为上一轮残留的授权而失败（看起来像权限门禁坏了）。
   **实测：连跑两次均 79/79，夹具残留 0 行、角色授权残留 0 行。**

### 5.1 本组暴露出来的三条通用经验（已写入 `DEV-ENV.md` §6.37~6.39）

| 现象 | 真因 | 对策 |
| --- | --- | --- |
| 给角色加了菜单授权，接口仍然 403 | 权限集合**登录时算好并缓存在 Redis**（随 `LoginUser`） | 授权后调一次 `GET /getInfo`（会 `refreshToken` 写回），或重新登录 |
| 新增返回 200，按自己传的 id 查库却是 0 行 | 主键**由服务端生成**，请求体的 `id` 被忽略 | POST 后用 code/name **反查真 id**；清理也按业务键 |
| 建第 2 级商品类型直接 500（`Data too long for column 'code'`） | 自动编码 `"PT"+32 位 UUID` = 34 字符 > `varchar(32)`；内存桩**不校验列宽**，33 条单测全绿也漏 | 生成值限长成常量、入口拒绝手工超长值、补一条显式断言长度的回归用例 |

---

## 6. 与参考侧（CTMS）的字段差异

| 差异 | 参考侧 | 本侧 | 依据 |
| --- | --- | --- | --- |
| 主键 | 自增整数 | `varchar(64)` 应用侧 UUID | 勘察 §8.2（全项目惯例） |
| 启停用 | `is_active` 布尔 | `enable_flag char(1)` | ddl-review Q6 裁决（业务表统一 `enable_flag`） |
| 审计块 | 仅 `created_at`/`updated_at` | `create_id/create_by/create_time/update_id/update_by/update_time` | 同上（`create_id` 在客户/供应商/物料上**收紧为 NOT NULL**） |
| 客户简称 | 可空 | 可空（**保持**） | §1.7 评审结论"保持可空" |
| 供应商简称 | 应用层强制 | **DB 层 NOT NULL** | `REQ-DATA-003`（AC-68：约束必须在库侧真实落地） |
| 物料类型层级 | 应用层限制 | 应用层限制 + 物化 `path`/`level` 列 | `ddl-review` §1.9；DB 层不建 CHECK，避免与"≤5 级由业务定义"耦合 |
| 账期天数 | `payment_terms_days` 可空 | `payment_days int` 可空（0 与 NULL 区分） | 规格"账期 0 可保存"要求两者可区分 |

**无字段遗漏也无多余字段**：上表 4 张物料表 + 2 张往来单位表的列清单已与
`ddl-review.md` 的签字版评审表逐列核对（13 张表中属本组的 6 张），
DDL 由 `tools/b3-sql-drill.ps1` 在空库演练过（建表幂等 ×2、13 张表齐备）。
