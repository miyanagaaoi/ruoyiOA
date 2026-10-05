# 合同主体交付说明（B3 第 4 组）

> 变更集：`openspec/changes/oa-contract-ledger`｜任务：4.1 ~ 4.9
> 规格：`specs/ctms/contract-ledger/spec.md`（9 个 Requirement，**唯一验收真源**）
> DDL 真源：`ruoyi-vue-oa-master/sql/二开-合同台账.sql`（`t_ctms_contract` 第 288~344 行、
> `t_ctms_contract_item` 348~372、`t_ctms_contract_tag` 375~387、`t_ctms_change_log` 390~413）
> 设计决策：`design.md` D-4（数据范围）/ D-7（反直觉口径）/ D-10（变更历史与操作日志分工）
> 运行时验收：`tools/ctms-contract-check.ps1`（真实环境、接口级）

本文件的用途（任务 4.9 的验证条件）：把**字段清单 / 状态枚举 / 软删除边界 / 框架绑定守卫 /
数据范围判定**写清，并保证**每一条口径都能在规格里找到同名场景、在代码里找到落点、在验收脚本里找到断言**。

---

## 1. 字段清单（`t_ctms_contract` 41 列）

| 分组 | 列 | 口径 |
| --- | --- | --- |
| 标识 | `id`、`contract_no` | UUID 主键（**服务端生成**）；编号 `uk_contract_no` 唯一，**停用也占号**（软删除行仍在唯一索引里） |
| 基本信息 | `name`、`type`、`party_a`、`party_b`、`subject_matter`、`amount`、`currency` | `type` 取 `sys_dict_data.contract_types` 的 `dict_value`（`SAL`/`PUR`/…，同时是编号的类型码）；`party_a/party_b` 是**文本快照**（不可空，未绑定档案时以文本兜底）；`subject_matter` 由行项覆盖**真正落库**；`amount` 是**已舍入行总价之和** |
| 日期 | `sign_date`、`effective_date`、`expected_arrival_date` | 均可空；`sign_date` 是编号"年月码"的取值来源（为空取当天） |
| 档案引用 | `customer_id`、`supplier_id` | 均可空 + **可空 FK**；不存在的档案被 FK 拒绝，同时服务层先给出可读提示 |
| 我方主体 | `subject_code` | 可空（非编号类合同确无主体码）；编号主体码来自它 |
| 付款 | `paid_amount` | 累计已付；**允许超过合同金额**（DB 不加 `paid<=amount` 约束） |
| 质保 | `has_warranty`、`warranty_amount`、`warranty_rate`、`warranty_start`、`warranty_months`、`warranty_end`、`warranty_released`、`warranty_release_date`、`warranty_note` | 关闭质保时清空 **7** 个字段（`warranty_note` 保留） |
| 框架 | `is_framework`、`parent_id` | 自引用可空（`ON DELETE SET NULL`） |
| 状态 | `status`、`arrival_status` | **互相独立、无联动**；取值必须来自字典 |
| 经办 | `owner_name` | 自由文本（**不是账号引用**，故不参与数据范围） |
| 归属 | `dept_id`、`create_id` | **NOT NULL + FK**；是 D-4 数据范围判定的唯一依据（`DEPT` 命中 `dept_id`，`SELF` 命中 `create_id`/所属部门） |
| 软删除 | `del_flag`、`deleted_at`、`deleted_reason` | 见第 3 节 |
| 审计 | `remark`、`create_time`、`update_time`、`create_by`、`update_id`、`update_by` | `create_by` 可空（SQL 初始化/系统写入） |

子表：`t_ctms_contract_item`（19 列，`product_id/code/name` 由 ⑥-2/⑥-3 收紧为 NOT NULL + FK）、
`t_ctms_contract_tag`（复合主键 `(contract_id, tag_id)` + `auto` 标记）、
`t_ctms_change_log`（17 列，`object_type`/`object_id` 非空、多态故不加 FK）。

---

## 2. 状态枚举与流转

| 字典键 | 取值（`dict_value`，status='0' 的启用项） | 默认 |
| --- | --- | --- |
| `contract_statuses` | 内部审批中、集团审批中、已签订、付款中、发货、到货、已终止（7 项） | `内部审批中` |
| `arrival_statuses` | 未到货、部分到货、已到货（3 项） | `未到货` |
| `contract_types` | `SAL` 销售/收入、`PUR` 采购/支出、`COO` 合作/战略协议、`LAB` 劳动/人事、`FIN` 金融/投融资、`NDA` 保密协议（6 项启用；`OTH` 其他(历史) 为 `status='1'` **不参与自动编号**） | — |
| `item_types` | 采购、销售、服务、其他（4 项） | — |
| `subjects` | `ZC` 智澈公司、`YX` 云羲公司（2 项） | — |

1. **任意状态可互转、不做顺序守卫**：规格明确要求"已签订 → 内部审批中 被接受"，
   因此 DB 层**不建**状态枚举约束（写了反而会拦住合法回退）。
2. **两个状态互不联动**：只改一个字段就只写一个字段（`changeStatus` 不做任何推断）。
3. **改「已终止」必须填终止原因**：合同表**没有**终止原因列，本实现把原因放在请求体的
   `deletedReason` 字段里承载（复用字段名只为传值），**不落库**，而是写一条
   `field_name='status'` 的变更历史（新值里带原因文本）。
   ⚠ 这与软删除的 `deleted_reason` **列**是两回事，别混：软删除原因要落库，终止原因不落库。
4. **状态取值必须命中字典的启用集合**：非法取值 → `无效状态`；到货状态 → `无效到货状态`。
   ⚠ 字典接口对 `status='1'` 的行**不返回**，所以"合法集合"就是它返回的 `dictValue` 集合
   （这是"停用的取值不允许再被写入"的自然实现）。

---

## 3. 软删除与 30 天恢复边界

**判定必须是"整数日差 ≤ 30"**（不是 `deleted_at + 30 天` 的瞬时比较 —— 后者会把边界推到第 30 天的同一时刻）。

| 场景 | 规格期望 | 判定式 |
| --- | --- | --- |
| 停用后第 30 天 08:00 恢复（停用时间 03-01 09:00） | 成功 | `days(2026-03-31) - days(2026-03-01) = 30 ≤ 30` ✅ |
| 第 31 天 09:00 恢复 | 拒绝，`已超过 30 天保留期，无法恢复` | `31 > 30` ❌ |
| 停用原因为空 | 拒绝删除，合同保持未停用 | `停用原因不能为空` |
| 对未停用合同调用恢复 | **幂等成功**，提示 `该合同未停用` | 直接返回，不写库、不报错 |

其余口径：
- 删除是**软删除**：写 `del_flag='1'`、`deleted_at=now`、`deleted_reason=原因`，
  并写一条 `field_name='deleted'` 的变更历史（旧值 `0` → 新值 `1`，note=原因）；
- 操作日志由 `@Log(title="合同台账", businessType=DELETE)` 承担（与变更历史分工，见 D-10）；
- **恢复窗口用可注入的"当前时间"**（服务层收敛成一个 `now()` 方法）：两个边界用例因此
  不依赖系统时间，测试可以稳定复现（验收脚本则用 `UPDATE deleted_at` 制造边界）。

---

## 4. 框架绑定守卫（四条 + 一条取消守卫）

| # | 触发条件 | 期望 | 提示文案（逐字） |
| --- | --- | --- | --- |
| ① | 把 `is_framework='1'` 的合同挂到别的框架下 | 拒绝 | `框架合同不能再挂到其他框架下` |
| ② | 父不存在或父已停用 | 拒绝 | `父合同不存在或已停用` |
| ③ | 父的 `is_framework != '1'` | 拒绝 | `只能挂到框架合同下` |
| ④ | 已是子合同（`parent_id` 非空）再勾选 `is_framework='1'` | 拒绝 | `已是子合同，不能再标记为框架合同` |
| ⑤ | 取消框架标记时仍有**未停用**子合同 | 拒绝 | `框架下仍有子合同，请先解除子合同` |

**自动「框架合同」标签**：自身为框架、**或**自身是某个框架的子合同时附加（`auto='1'`），
两者都不是时移除自动附加的那一个。

**框架详情**：返回 `children[]` + `childrenCount` + `childrenAmountSum`，
且**子合同金额合计与框架自身金额分开呈现、不要求相等**（规格场景：自身 100000.00 / 子合计 90000.00）。

---

## 5. 数据范围判定（D-4，本批次最高风险项 R-5）

**不复用 RuoYi 的 `@DataScope`**（它只覆盖系统模块 5 处，且 `SELF`/`DEPT` 语义与业务口径不等价）。
口径实现集中在 `support/ContractDataScope`，产出**一段 SQL 片段**交给 Mapper 的
`${dataScopeSql}` 原样拼接 —— 多路用 `or` 连接，**命中即放行**（并集语义天然成立）。

| `sys_role.data_scope` | 含义 | 片段 |
| --- | --- | --- |
| `1` | 全部 | 不放条件（`null`） |
| `2` | 自定义部门 | 与 `3` 同口径（B3 不做 `sys_role_dept` 明细，已注明） |
| `3` | 本部门 | `c.dept_id = 本部门` **或** 本部门在合同部门的 `ancestors` 链上（**含下级为默认**） |
| `4` | 本部门及以下 + 本人 | `c.create_id = 本人` **或** 所属部门命中（D-4 的 `SELF` 口径） |
| `5` | 仅本人 | `c.create_id = 本人` |

三条硬约束：
1. **服务端强制**：片段里的 `userId`/`deptId` 只来自 `SecurityUtils`，**绝不来自请求参数**
   （XML 里用 `${}` 拼接，注释已写明"只能由服务端白名单产出"）；
2. **同一个判定入口覆盖全部出口**：列表（写进 `query.dataScopeSql`）、详情、编辑、删除、恢复、导出；
3. **范围外按标识直查返回 403**：服务层抛带 `HttpStatus.FORBIDDEN` 的业务异常，
   由全局异常处理器原样带出 `code=403`（与 `tools/authz-check.ps1` 的断言形态一致）。

超管（`SecurityUtils.isAdmin`）**放行全部**。

---

## 6. 反直觉口径（D-7，逐条落成可执行用例）

| 口径 | 规格/设计依据 | 实现落点 | 验收断言 |
| --- | --- | --- | --- |
| **C-1 先舍入再汇总** | 行总价 = 数量×单价 `setScale(2, HALF_UP)`，合同金额 = 各**已舍入**行总价之和 | `ContractRules.lineTotal` / `sumLineTotals` | 3 行 `0.005`：逐行舍入 → `0.03`（先汇总再舍入会得 `0.02`，**判别力在此**）；`3×1.665 → 5.00`、`3.333×0.03 → 0.10` |
| **C-2 质保到期** | `addMonths`（日取 `min(原日, 目标月最大日)`）+ `computeWarrantyEnd`（`max(1,months)` → `addMonths(start, months-1)` → 该月最后一天） | `ContractRules` | 6 个表驱动场景（含 `2026-01-31 + 2 → 2026-02-28`、`2026-03-15 + 24 → 2028-02-29`） |
| **C-2 两条路径都算** | 新增与编辑都**服务端计算**，前端传值不参与判定（参考侧只在该编辑路径算，属已知缺陷，不搬入） | `normalizeWarranty` 在新增/编辑共用 | 新增时传 `2025-06-01 + 12` → 落库 `2026-05-31` |
| **标的物摘要真正落库** | 参考侧只写日志未落库（`contracts.py:359-361`），本设计按文档口径收敛 | `subjectMatterOf` → `updateContract` | 断言库里 `subject_matter` 的值（不只是日志） |
| **软删除整数日差** | 见第 3 节 | `ContractRules.daysBetween` / `isWithinRestoreWindow` | 30 天通过 / 31 天拒绝 |
| **金额禁 `double`/`float`** | `REQ-NFR-007` | 全链路 `BigDecimal` | `grep -rn "double\|float" ruoyi-ctms/src/main/java` 在合同相关类中零命中 |

---

## 7. 变更历史与操作日志的分工（D-10）

| 能力 | 承载 | 内容 |
| --- | --- | --- |
| **字段级变更历史** | `t_ctms_change_log` | 操作人标识 + **姓名快照**、字段名、旧值、新值、来源（`manual`/`auto`）、`object_type`/`object_id` |
| **操作日志** | `@Log` + `SysOperLog` | 动作级（新增/编辑/删除/恢复/状态变更/质保释放） |

特殊字段名沿用参考侧：`_summary`（标的物摘要）、`_items`（行项整体）、`_tags`（标签集合）、
`deleted`（软删除）、`附件`（附件变更，第 6 组接入）。

两条必须成立的行为：
1. **系统自动计算产生的改写记为 `auto`**（金额、标的物摘要、质保到期、关闭质保时清空的字段）；
2. **操作人为空的历史记录不得报错**：详情与历史列表照常返回，前端展示占位符「—」。

---

## 8. 详情只读「关联单据」区块（4.7 / `REQ-CTMS-008` / `AC-77`）

- 详情返回：合同全字段 + 标签 + 行项 + 变更历史入口 + **只读「关联单据」列表**（单号/类型/状态）；
- **该区块绝不回写合同的任何字段** —— 验收方式是"打开详情前后对合同的
  `amount/status/arrival_status/paid_amount/del_flag` 做前后快照比对"；
- B3 阶段**没有**单据表（属 B4 的采购/销售/出入库），因此 `relatedDocs` 固定返回空列表，
  并在 Javadoc 注明"B4 交付单据域后在此接只读查询"。

---

## 9. 验收资产

| 断言来源 | 覆盖 | 命令 |
| --- | --- | --- |
| 运行时接口验收（**主证据**） | 第 2~8 节全部可观察口径（状态流转/筛选与标签交集/恢复边界/框架守卫/变更历史/详情只读/数据范围 403） | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1` |
| 单元测试（服务层编排，内存桩 Mapper） | 登记校验、档案兜底、金额与质保、状态、恢复边界、框架守卫、自动标签、变更历史、数据范围、详情只读 | `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test` |
| 静态审计 | 前端取数纪律等 | `node tools/audit/run-all.js` |

⚠ **编号生成的分工**：合同编号的**服务端生成**（类型码 + 主体码 + 年月 + 按年重置、
停用占号不复用、预览不占号）是第 5 组任务 **5.8** 的交付面；本组只保证
"编号唯一 + 可落库 + 唯一索引兜底"，验收脚本也按"前端取号 → 随登记提交"的形态断言。
5.8 交付后应把 `tools/ctms-contract-check.ps1` 里对应的两条断言改为"不传编号也能生成"。
