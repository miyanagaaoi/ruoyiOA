# B3 / B4 DDL 边界与编排契约（冻结件）

> 变更集：`oa-contract-ledger`（B3）←→ `oa-purchase-sales-stock`（B4）
> 任务：tasks.md §1.7（对应 design.md D-2、REQ-DATA-002/003/005、AC-68）
> 冻结时间：2026-10-05｜冻结人：二开

## 1. 一句话边界

**B3 交付 13 张业务表 + 承载字典/参数的 `sys_dict_*` / `sys_config` 存量表行；
其余归 B4；参考侧有 8 个对象在目标侧「不建表」（复用 OA 既有实现）。**
`sql/初始化-全部.sql` 的编排契约（段顺序 + B4 占位段）在 B3 建立。

## 2. 数量对账（39 = 13 + 18 + 8，与 design D-2 的口径一致）

| 归属 | 张数 | 说明 |
| --- | --- | --- |
| **B3**（本变更集） | **13** | 合同域 5 + 主数据域 5 + 附件 1 + 往来单位 2（见 §3） |
| **B4**（`oa-purchase-sales-stock`） | **18** | 8 类单据表头 + 8 张行项表 + 库存结存 1 + 库存流水 1 |
| **不建表**（复用 OA 既有实现） | **8** | 见 §5 |
| 参考侧 ORM 表合计 | **39** | 清单 §1.5「39（37 个 `__tablename__` + 2 个关联 `Table`）」 |

## 3. B3 的 13 张表（**本清单冻结其目标侧表名**）

| # | 目标侧表名 | 参考侧表名 | 承载 | 覆盖任务 |
| --- | --- | --- | --- | --- |
| 1 | `t_ctms_contract` | `contracts` | 合同主体（含质保区、框架父子、软删除、数据范围列） | 2.1 / 4.x / 5.x |
| 2 | `t_ctms_contract_item` | `contract_items` | 合同行项（含物料快照与金额） | 2.1 / 5.1 / 5.2 |
| 3 | `t_ctms_tag` | `tags` | 标签字典（名称唯一、颜色、auto 标记） | 2.1 / 4.5 |
| 4 | `t_ctms_contract_tag` | `contract_tag` | 合同↔标签 关联（**复合主键**） | 2.1 / 4.5 |
| 5 | `t_ctms_change_log` | `change_logs` | 字段级变更历史（含姓名快照、来源、对象类型/标识） | 2.1 / 4.6 |
| 6 | `t_ctms_attachment` | `attachments` | 附件挂载元数据（`object_type` + `object_id` 多态） | 2.1 / 6.1 |
| 7 | `t_ctms_customer` | `customers` | 客户档案 | 2.1 / 3.1 |
| 8 | `t_ctms_supplier` | `suppliers` | 供应商档案（**简称必填**） | 2.1 / 3.2 |
| 9 | `t_ctms_party_draft` | `party_drafts` | 历史甲乙方待认领草案 | 2.1 / 7.x |
| 10 | `t_ctms_product_type` | `product_types` | 商品类型树（≤5 级、物化路径） | 2.1 / 3.3 |
| 11 | `t_ctms_uom` | `uoms` | 计量单位（小数位 0~4） | 2.1 / 3.3 |
| 12 | `t_ctms_warehouse` | `warehouses` | 仓库档案 | 2.1 / 3.3 |
| 13 | `t_ctms_product` | `products` | 物料档案 | 2.1 / 3.3 |

> 后 4 张（10~13）虽然服务 B4 的单据，但**表结构与档案接口由 B3 交付**：
> B4 只引用、不重建（design D-1 的理由②：同一模块内共享主数据的成本最低）。
> 这也是 B4 依赖 B3 的具体内容之一（`proposal.md` 的依赖声明）。

**不建表但落在存量表上的内容**（随 §2.8 一起进 `sql/二开-合同台账.sql` / 菜单脚本）：

| 内容 | 落点 | 覆盖任务 |
| --- | --- | --- |
| 字典：`item_types`、`contract_types`、`subjects` | `sys_dict_type` / `sys_dict_data` | 2.8 |
| 参数：`warranty_window_days`（默认 30） | `sys_config` | 2.8 |
| 权限点：`ctms:contract:*`、`ctms:contract-item:*`、`ctms:tag:*`、`ctms:partner:*`、`ctms:migration:*` | `sys_menu` | 2.7 |

## 4. B4 的 18 张表（**本清单只冻结归属与数量，表名由 B4 在其变更集内冻结**）

| 类别 | 张数 | 内容（B4 `tasks.md` §1.2 的口径） |
| --- | --- | --- |
| 单据表头 | 8 | 采购申请单、采购单、销售申请单、销售订单、入库单、出库单、盘点单、调拨单 |
| 单据行项 | 8 | 与上表一一对应 |
| 库存结存 | 1 | `(物料, 仓库)` 唯一约束 + 定点小数列 |
| 库存流水 | 1 | 按 `(物料, 仓库, id)` 与 `(单据类型, 单据 id)` 建索引；只增不改 |

> ⚠ **B3 不得**在 `二开-合同台账.sql` 里写任何 B4 的对象（design D-2 的否决理由：
> B3 的验收面不能被 B4 尚未冻结的字段语义绑死）。
> ⚠ B4 也**不得**重建 B3 的 13 张表与 `t_ctms_product*` / `t_ctms_uom` / `t_ctms_warehouse` /
> `t_ctms_contract`（只引用）。

## 5. 目标侧「不建表」的 8 个参考对象（复用 OA 既有实现）

| 参考侧对象 | 目标侧复用物 | 依据 |
| --- | --- | --- |
| `org_units` | `sys_dept`（+ `ancestors` 物化路径） | 清单 §9；`platform/module-boundary` |
| `users` | `sys_user` | 同上 |
| `roles` | `sys_role`（`data_scope` 仅作参考，业务范围自建，见 D-4） | 同上 |
| `user_roles` | `sys_user_role` | 同上 |
| `role_permissions` | `sys_menu` + `@PreAuthorize` | 清单 §9；权限点真源是 `sys_menu` |
| `operation_logs` | `@Log` + `sys_oper_log` | D-10 |
| `kv_settings` | `sys_dict_*`（字典）/ `sys_config`（参数）/ `ruoyi-serial`（编号） | D-9 |
| `print_templates` | `t_template_print_template`（V1 已交付，B2 已补全 4 套内置版式） | B2 |

> **绝对禁止**：任何形式的 `CTMS_AUTH_ENABLED` 类免认证后门、第二套账号表/组织树/自研令牌。
> 这条有静态门禁（B3 任务 9.4：`grep -rni "auth_enabled|second.*jwt|ctms_user|ctms_org" ruoyi-ctms` 零命中）。

## 6. `sql/初始化-全部.sql` 的编排契约（B3 建立，B4 追加段）

段顺序（**唯一真源**，`source` 用的是相对路径，必须从 `sql/` 目录执行）：

```text
① V1 二开增量（8 个文件，顺序不可乱）
② 2.0 B1 增量：二开-2.0-B1-t_template增列.sql、二开-2.0-B1-模板新增表.sql
③ 2.0 B2 增量：二开-2.0-B2-打印模板内置版式.sql
④ 2.0 B3 增量：二开-合同台账.sql          ← 本变更集新增
⑤ 2.0 B3 菜单：二开-合同台账-菜单.sql      ← 本变更集新增（幂等，可单独重跑）
⑥ 2.0 B4 增量：二开-进销存.sql            ← 占位注释，由 B4 追加
⑦ 编排自检 SELECT（列齐备性 / 表齐备性 / 存量未被改）
```

约定：

1. **`table.sql` / `data.sql` 保持上游原样不修改**（REQ-DATA-005 方案 B）——
   验证方式是 `git diff` 为空（B3 任务 2.6）；
2. B3 的增量脚本必须**幂等**（`DROP TABLE IF EXISTS` + `CREATE`；约束回补段带 `information_schema` 守卫）；
3. ⑥ 段以**注释占位**形式存在（写明"由 B4 追加 `source 二开-进销存.sql`"），
   保证 B3 段在空库能**单独跑通**，而 B4 只需把占位替换成真实 `source` 行；
4. 编排文件末尾的自检要分别报告 B3 与 B4 的齐备性（B4 未交付时其行数允许为 0，但必须打印出来）。

**回滚**（design.md Migration Plan 的逆序，B3 任务 2.5）：
`sql/回滚-合同台账-<日期>.sql` = 删 FK → 删索引 → 恢复可空 → `DROP TABLE IF EXISTS`（仅本变更集新建的表）
＋ 按快照表 `*_bak_<日期>` 反向回填；**不触碰任何存量表结构**。

## 7. 书面确认（评审签字）

> 冻结件生效需要 B3 与 B4 两队各确认一次；本清单未确认前，**第 2 组（建表 DDL）不得启动**。

| 角色 | 姓名 | 日期 | 结论 |
| --- | --- | --- | --- |
| B3（`oa-contract-ledger`）实现方 | 二开 | 2026-10-05 | 已确认（本文件即 B3 侧输出） |
| B4（`oa-purchase-sales-stock`）实现方 | 二开（同一实现方，B4 尚未开工） | 2026-10-05 | **已确认**：接受「18 张归 B4、B3 的 4 张主数据表只引用不重建」与 §6 的编排方式（B4 开工时如与自身 DDL 口径冲突，回到本文件改这一段而不是各自另立编排） |
| 交付评审（用户/业务） | 交付评审方（用户，通过 DSH Web 会话答复） | 2026-10-05 | **已签字**：接受 B3 的 13 张表边界与 `table.sql` 不修改的口径（原话「确认，按此冻结」） |

> **放行状态（2026-10-05）**：三行确认齐备，**第 2 组（建表 DDL）已具备开工条件**。
> ⚠ 后续若 B4 的实际 DDL 与本清单的归属冲突，改**本清单**并重新确认，不要在两处各留一份口径。
