# Design

## Context

动机见 `proposal.md`「Why」。本节只列塑造本设计的现状与约束（均已核对到文件/行，只读，未修改）。

**目标侧现状（RuoYi-OA）**
- 后端 `F:\dsh\ruoyiOA\ruoyi-vue-oa-master`：Java 8 + Spring Boot 2.5 + MyBatis(-Plus)，28 个 Maven 模块，已有 `ruoyi-system` / `ruoyi-workflow` / `ruoyi-serial` / `ruoyi-file` / `ruoyi-todo` 等；**没有合同/台账类业务模块**。
- 前端 `ruoyi-vue-oa-ui-master`：Vue 2.6 + element-ui 2.15.14（扩展构建）+ Vue CLI 4；`views/` 下无 `ctms` 目录。
- 权限点真源是 `sys_menu` 表 + `@PreAuthorize("@ss.hasPermi('a:b:c')")`；命名规范 `<域>:<资源>:<动作>` 全小写（`doc/2.0/2.0-PRD-OA升级开发.md` §7.5），新域前缀 `ctms:*`。
- 数据范围：`@DataScope` AOP 只作用在系统模块 5 处（`SysDeptServiceImpl:67`、`SysUserServiceImpl:81,101,114`、`SysRoleServiceImpl:57`），业务表用不上；业务侧既有先例是 `ruoyi-workflow/src/main/java/com/ruoyi/workflow/guard/DocViewGuard.java` + `AccessCheckMapper.xml:18-29` 的「多路 union 命中即放行」范式。
- SQL 交付惯例：上游基线 `sql/table.sql`（2563 行）/`sql/data.sql` 不含 V1 二开对象；V1 二开的 7 张表与菜单全部走 `sql/二开-*.sql` 增量文件，写法是 `DROP TABLE IF EXISTS` + `CREATE TABLE`、`DELETE FROM sys_menu WHERE menu_id IN (...)` + `INSERT`（`doc/2.0/RuoYi现状勘察.md` §8.1/§8.3）。
- 字段类型惯例：主键 `varchar(64)` 应用侧 UUID、标志位 `char(1)`（'0' 否 / '1' 是）、逻辑删除 `del_flag`、审计列 `create_id/create_by/create_time/update_id/update_by/update_time`、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`（同上 §8.2）。
- 编号能力：`ruoyi-serial` 提供 `ICodeGenService.getNextCode(confId)`；规则表 `t_code_config_rule` 的 `rule_type` 只有 固定值/日期/流水号 三种，`ruleValue` 对日期规则是日期格式串（`CodeGenServiceImpl:101-126`）；流水号按 `confId` 计数、重置方式声明在规则里但由定时任务 `CodeRestTask` 在 1 月 1 日执行（`CodeConfigServiceImpl:156-190`）。
- 附件能力：`ruoyi-file` 已提供上传/存储；缺的是「附件挂到任意业务对象 + 按对象鉴权」这层（`doc/2.0/参考仓库-CTMS-移植清单.md` §9 第 5 条）。
- 回归资产：`tools/audit/run-all.js`（6 个静态审计 + baseline）、`tools/authz-check.ps1`（14 断言）、`tools/flow-regression.ps1`（20 断言）、`tools/sign-feature-check.ps1`（44 断言）；构建/运行命令见 `DEV-ENV.md` §4。

**参考侧（只读基线 `.cache/contract-ref`）**
- 合同域 39 表；B3 直接相关的参考侧对象 13 张：`contracts`、`contract_items`、`tags`、`contract_tag`、`change_logs`、`customers`、`suppliers`、`party_drafts`、`products`、`product_types`、`uoms`、`warehouses`、`attachments`（其中 `kv_settings` 的字典/参数内容不建表，落 `sys_dict_*`/`sys_config`，见 D-9）。
- 全局约定：新增列一律可空无 FK（SQLite `ALTER TABLE ADD COLUMN` 限制的固化），共 17 类「应用层强制、DB 可空」字段（`参考仓库-CTMS-移植清单.md` §2.8）。
- 必须逐字复刻的反直觉口径：C-1 先舍入再汇总（`contracts.py:332,355-358`）、C-2 质保到期算法（`models.py:77-85`）、软删除 30 天整数日差（`contracts.py:834-852`）、编号按年重置与停用占号不复用（`numbering.py`）。
- 参考实现的两处文档/代码不一致（按 PRD §13.2 Q-B10 的「文档口径」处理）：① 新增（POST）路径不重算质保到期日；② 行项改写标的物摘要只写日志未落库（`contracts.py:359-361`）。本设计按下述 D-6 决策显式收敛。

**约束**
- 本变更集规划期只产出规划件，不改任何源码（`proposal.md` 已声明）。
- 不引用 `MODIFIED` 能力：`oa-v2-overview` 的 `platform/module-boundary`、`platform/delivery-gate` 若尚未归档，主规格不一定存在；要求由四个新能力承载（见 `specs/ctms/*/spec.md`）。
- 不改存量权限点名、不改 `t_template`/打印/流程、不引入 Flowable 多级审批（Q-B7）。

## Goals / Non-Goals

**Goals**
- 冻结 B3 的模块落点、数据范围口径、编号落地方案与附件复用方案，使 DDL 与业务实现可以并行推进。
- 把「39 表 DDL」按变更集切成可独立验收的两段：B3 落 13 张合同/主数据域业务表并把约束回补口径固化，其余（8 类单据的表头与行项、结存与流水、库存单据）归 B4；权限域 4 张表（组织/账号/角色/角色权限）不使用 OA 侧对应物，**不建表**。`sql/初始化-全部.sql` 的编排契约在 B3 建立。
- 让每一条反直觉口径（C-1/C-2/软删除边界/编号按年重置）都能直接变成一个可执行的测试用例（见各 spec 的场景）。
- 迁移可回滚：任何 DDL 或数据回填前先有影响行数报告与快照表。

**Non-Goals**
- 不做采购/销售/出入库/库存过账与红冲（B4 = `oa-purchase-sales-stock`）。
- 不做合同审批流改造（保留单级状态机）、不做打印版式新增（B2 已完成）、不改 `t_template` 与流程绑定。
- 不复刻参考仓库的账号/组织/角色/权限常量/JWT/备份/Excel 基础设施（`参考仓库-CTMS-移植清单.md` §9 的 14 项复用结论）。
- 不移植 `CTMS_AUTH_ENABLED=0` 免认证后门（绝对禁止）。
- 不做逐笔付款流水（只有单一「累计已付」字段，口径 D-? 见参考 §3.4）。
- 不做多币种换算（币种仅记录）。
- 不搬运参考仓库的历史附件二进制文件（见 Open Questions Q6）。

## Decisions

### D-1 新建 `ruoyi-ctms` 模块，而不是塞进既有模块

- **选择**：新增 Maven 模块 `ruoyi-ctms`（`com.ruoyi.ctms.*`），B3 只放「合同台账 + 往来单位档案」子包，B4 复用同一模块追加单据域与库存域；在根 `pom.xml` 的 `<modules>` 与 `ruoyi-admin/pom.xml` 登记；Mapper XML 放 `ruoyi-ctms/src/main/resources/mapper/ctms/`。
- **理由**：① 参考侧合同域与主数据域是一个内聚边界，塞进 `ruoyi-system` 会让系统模块承载业务台账；② B4 与 B3 共享 `products`/`uoms`/`warehouses` 主数据与编号/附件复用层，同一模块内共享成本最低；③ 与既有 `ruoyi-kbs`/`ruoyi-schedule` 的分层惯例一致（`DEV-ENV.md` §6.8 的模块建议）。
- **备选与否决**：塞进 `ruoyi-system` → 否决（系统模块被当作通用垃圾桶，权限/数据范围口径会互相污染）；新建两个模块 `ruoyi-ctms` + `ruoyi-erp` → 否决（B4 与 B3 共享主数据，拆开会产生跨模块循环依赖）。
- **落地要点**：模块只声明对 `ruoyi-common`/`ruoyi-system` 的依赖，编号走 `ruoyi-serial` 的 API 接口、附件走 `ruoyi-file` 的能力，**不在 `ruoyi-ctms` 里重写任何平台能力**。

### D-2 B3 与 B4 的 DDL 边界与 39 表编排契约

- **选择**：B3 交付本变更集域表的增量脚本 `sql/二开-合同台账.sql`（含约束回补与索引）与菜单脚本 `sql/二开-合同台账-菜单.sql`；B4 在 `oa-purchase-sales-stock` 中交付单据域与库存域增量脚本。B3 建立 `sql/初始化-全部.sql` 的编排契约（按序 `source` 全部增量文件，含占位的 B4 段），`table.sql`/`data.sql` 保持上游原样不修改（REQ-DATA-005 方案 B）。
- **理由**：PRD §12 把「39 表 DDL」放在 B3 的 S5，但 39 表中有 20 张属于 B4 的单据/库存域，其字段语义（`DocMixin` 的 public 字段、过账标记）由 B4 的规则决定；在 B3 里写 B4 的表会让 B3 的验收面被 B4 的未决口径绑死。编排脚本在 B3 建立可以让新环境初始化一次跑通 B3 段，B4 只需追加段。
- **备选与否决**：B3 一次交付 39 表 → 否决（B3 无法验收 B4 的字段语义，且 B4 变更口径后要回头改 B3 已归档的 DDL）；B3 只交付不带编排的散脚本 → 否决（R-9 的「新环境初始化缺对象」风险会延续）。
- **落地要点**：B3 表名统一用 `t_ctms_` 前缀（如 `t_ctms_contract`、`t_ctms_contract_item`、`t_ctms_change_log`、`t_ctms_customer`、`t_ctms_supplier`、`t_ctms_party_draft`、`t_ctms_tag`、`t_ctms_contract_tag`），主键 `varchar(64)`、标志位 `char(1)`、审计列与 `utf8mb4/InnoDB`（勘察 §8.2）；参考侧的原表名只作为字段对照依据，不沿用。

### D-3 MySQL DDL 恢复 `NOT NULL` + 真实 FK：先体检、再回填、后加约束

- **选择**（对应 F-3 / REQ-DATA-003 / AC-68）：把参考侧 §2.8 的 17 类「应用层强制、DB 可空」字段逐列判定，分三类处理：
  - **加 `NOT NULL` + FK**：行项的物料引用（标识/编码/名称）、合同的客户与供应商档案引用、合同的组织与创建人、变更历史的操作人/对象类型/对象标识、附件的对象类型与对象标识、主数据（客户/供应商/物料）的创建人、供应商简称。
  - **保持可空**：业务上确实允许为空的列——单据侧对象的合同引用（B4）、附件记录的合同引用（单据附件无合同）、资产类记录里仅用于反查名称的引用列。
  - **不变**：DB 层本来非空的列（行项数量/单价/金额、单据的仓库/类型等）保持原状。
  执行顺序固定为 **体检（输出违例清单）→ 回填 → 快照 → 加约束**，每一步独立脚本、独立可重跑。
- **理由**：MySQL 的 InnoDB 默认强制 FK，语义比参考侧（SQLite 仅在 `PRAGMA foreign_keys=ON` 时生效）更强，直接照抄「可空」会丢失本可落库的完整性；但直接加约束会在存量脏数据上失败（参考侧历史上允许写入不完整引用）。体检清单把「约束能不能加」变成可判定的事，而不是靠人猜。
- **备选与否决**：照抄参考侧的可空 → 否决（AC-68 明确要求插入违例数据被数据库拒绝）；全部加 `NOT NULL`+FK → 否决（单据侧对象确实可以没有合同，附件对象类型在迁移期也可能为空，会误伤合法数据）；只加索引不加约束 → 否决（不满足 AC-68 的「约束真实生效」）。
- **快照与回滚**（见 Migration Plan）：受影响表先 `CREATE TABLE <表>_bak_<日期>` 全量快照（含 DDL 与数据），回滚脚本 `sql/回滚-合同台账-<日期>.sql` 负责删 FK/索引、恢复可空、按快照回灌。

### D-4 数据范围自建，不复用 `@DataScope`

- **选择**：在 `ruoyi-ctms` 内自建合同台账数据范围，口径为 `ALL` / `DEPT`（含下级为默认）/ `SELF`，多角色取**并集**；以「命中即放行」的显式 SQL 片段实现，参照既有 `DocViewGuard` + 四路 union 范式（`AccessCheckMapper.xml:18-29`），列表查询、详情、编辑、删除、恢复、导出**全部**走同一处判定。
- **理由**：① `@DataScope` 只覆盖系统模块 5 处，且它读 `sys_role.data_scope` 并拼 `sys_dept` 别名的 SQL 片段，对业务表不可直接用（勘察 §6.3 结论明说「不能直接用在业务表上」）；② `SELF` 在参考侧是 `created_by`、在 RuoYi 默认是 `user_id`，`DEPT` 在参考侧是 `org_id`、RuoYi 是 `dept_id`，两套语义不等价，必须显式映射（PRD §7.3 / 移植清单 §8 第 4 条）；③ PRD 已把「建议统一为创建人所属部门口径，不用 `org_id`」写进技术对齐表。
- **备选与否决**：复用 `@DataScope` → 否决（覆盖面与语义都不匹配，会同时产生越权与「该看的看不到」）；用 `sys_role.data_scope` 单值取最宽 → 否决（现口径要求多角色取并集，且并集语义已写入规格场景）。
- **落地要点**：`SELF` = 创建人本人 **或** 创建人所属部门（含下级）；`DEPT` = 合同 `dept_id` 命中当前用户部门 `sys_dept.ancestors` 链（含下级为默认，可配置关闭，与 PRD Q-B2 的「指定部门含下级」保持一致口径）；`ALL` = 不加条件；超级管理员放行。数据范围判定必须**服务端强制**，且直接按标识访问时同样生效（规格场景已覆盖 403）。

### D-5 附件走 `ruoyi-file` + `object_type`/`object_id` 复用层

- **选择**：上传/存储/下载完全复用 `ruoyi-file`（`/common/upload` 经典链路，`DEV-ENV.md` §6.20），只新建「业务对象挂载」元数据表 `t_ctms_attachment`（对象类型、对象标识、原始文件名、存储相对路径、大小、内容类型、删除标记、上传人/时间），并只保留参考侧的**业务语义**：白名单后缀、单文件上限、随机文件名、按对象鉴权、删除留痕（写变更历史，字段名 `附件`）。
- **理由**：参考侧 `attachments` 表把「元数据 + 存储路径」绑在一起，是它自给自足的产物；目标侧存储已由 `ruoyi-file` 负责，重写一遍会引入两套上传（移植清单 §9 第 5 条明确「只保留挂到任意业务对象 + 按对象鉴权这一层业务语义」）。`object_type`/`object_id` 是参考侧已验证的通用挂载设计（`attachments.py:83-95`），保留它可以一次覆盖合同、标签、迁移草案、以及 B4 单据。
- **备选与否决**：直接把参考 `attachments` 表照搬 → 否决（两套上传/存储能力并存）；用 RuoYi 新文件模块 `/file/operate/**` → 否决（本机 MinIO 未部署、配置的本地路径与实际目录不一致、且分片协议与 multipart 不兼容，见 `DEV-ENV.md` §6.20 的实测结论）。
- **落地要点**：附件与业务对象的关系**不加 FK 到具体业务表**（`object_type` 是多态），改为 `(object_type, object_id)` 复合索引 + 应用层对象存在性校验；附件删除写 `change_logs`（字段名 `附件`），与参考侧一致。

### D-6 编号走 `ruoyi-serial`：S0 验证结论决定「配置表达」还是「扩展序列服务」

- **选择**（对应 Q-B9）：先按「配置表达」尝试，用 `t_code_config_rule` 的三类规则拼 `类型码(FIXED) + 主体码(FIXED) + 年份(DATE, yyyy) + 月份(DATE, MM) + 6 位流水号(SEQ, 按年重置)`；S0 阶段做一次技术验证，判定标准是「能否在不改 `ruoyi-serial` 代码的前提下，同时满足下列 4 条」。任一不满足则扩展序列服务（最小扩展面见下）。
  1. 覆盖 `2025-06-01` 与 `2027-01-01` 两个签订日期，编号分别为 `PURZC202506000001` 与 `PURZC202701000001`；
  2. 跨年重置：次年首个编号的序号为 `000001`（不依赖「1 月 1 日当天恰好有定时任务执行」）；
  3. 序号维度是「类型码 + 主体码 + 年份」，同配置下不同主体必须各有一套独立序号；
  4. 预览接口连续调用两次返回同一编号（预览不占号），保存时由服务端生成并做唯一性冲突处理（冲突返回 409 语义）。
- **已验证的差距（这是 S0 的起点证据）**：① 现实现的日期规则取**当天**日期，不接受「按签订日期」的取值（`CodeGenServiceImpl:112`），无法同时表达「月份码取签订日期」与「年份取签订日期年份」；② 流水号按 `confId` 计数、重置由 `CodeRestTask` 在 1 月 1 日执行（`CodeConfigServiceImpl:162-189`），若 1 月 1 日应用未运行则不会重置；③ 序号维度不含「类型码 + 主体码」，一对配置只能支撑一套序号；④ `CodeConfigRule.ruleValue` 在日期规则里是格式串，**不能承载**主体码这类由业务参数决定的取值。
- **最小扩展面（若验证不通过）**：给序列服务增加「按业务参数（类型码/主体码）分桶 + 惰性跨年重置（取号时比较年份，不一致则清零）+ 支持由调用方传入参考日期」三项能力，扩展必须向后兼容既有 11 类编号（不改存量 `CodeConfig` 语义、不改既有 `getNextCode(confId)` 行为）。扩展的验收 = 上述 4 条判定标准全部通过。
- **备选与否决**：合同编号完全自研（仿参考侧扫库取最大值）→ 否决（引入第二套编号体系，与 REQ-NFR-001 的模块边界冲突，且并发下需要自己解决重号）；把合同编号塞进前端生成 → 否决（服务端无法保证唯一性与停用占号不复用）。

### D-7 反直觉口径的显式收敛（C-1 / C-2 / 软删除边界 / 标的物摘要）

- **C-1 金额**：全部金额/数量字段用 `BigDecimal`，工厂 scale 固定（金额 2、数量 3、单价 4），行项总价 = 数量×单价 `setScale(2, HALF_UP)` 后**再**求和；合同金额 = 已舍入行总价之和。禁止 `double`/`float`（REQ-NFR-007）。
- **C-2 质保到期**：实现 `addMonths`（年月进位、日取 `min(原日, 目标月最大日)`）与 `computeWarrantyEnd`（`max(1, months)` → `addMonths(start, months-1)` → 取该月最后一天）两个纯函数，并做成**表驱动单测**覆盖规格里的 6 个场景（含 2026-01-31 + 2 个月 → 2026-02-28、2026-03-15 + 24 个月 → 2028-02-29）。
- **C-2 路径差异（文档口径收敛）**：参考侧只在编辑路径重算到期日、新增路径依赖前端传值（移植清单附录 A 第 1 条）。本设计**在新增与编辑两条路径都服务端计算**，前端传入值不参与判定；同时保留「与库值不同才写自动来源变更历史」的行为。理由：PRD §13.2 Q-B10 已确认「按文档口径实现」，而参考侧文档 §3.3 的口径就是「系统按算法重算」；且「新增时前端不回传就为空」是已知缺陷，属于「移植缺陷不得一并搬入」（`platform/delivery-gate` 的要求）。
- **软删除边界**：恢复判定用「整数日差 ≤ 30」（等价于参考侧的 `(now - deleted_at).days > 30 → 拒绝`），因此**第 30 天可恢复、第 31 天不可**；规格里的两个场景就是这条边界的可执行定义。实现上不得改用 `deleted_at + 30 天` 的瞬时比较（后者会把边界推到第 30 天的同一时刻）。
- **标的物摘要（文档口径收敛）**：参考侧「行项改写标的物摘要」只写日志未落库（`contracts.py:359-361` 只 `_log` 未 `setattr`）。本设计**要求真正落库**并留自动来源变更历史，摘要格式取「名称(规格)×数量」多条以分号连接。理由同上：按文档口径实现，且「字段不落库但留痕」会直接破坏打印/导出取数的自洽性。

### D-8 前端形态：`views/ctms/**` + element-ui 既有组件 + 动态路由

- **选择**：新增 `src/views/ctms/**`（合同列表/详情/编辑、标签管理、客户档案、供应商档案、迁移认领）与 `src/api/ctms/**`；菜单走 `sys_menu` 动态路由（`component = 'ctms/<目录>/index'`），按钮权限走 `v-hasPermi`；日期控件一律用 `yyyy-MM-dd` 记号。
- **理由**：① element-ui 2.15.14 扩展构建已含 `descriptions/empty/result/statistic/timeline/image/drawer`，无需新增组件库（勘察/移植清单 §5.4 实测结论）；② 复用 `src/layout` 与 `store/modules/permission.js` 动态路由，不新造布局（移植清单 §9 第 11 条）。
- **备选与否决**：引入 Element Plus 或新 UI 库 → 否决（与现有 Vue2 栈冲突）；把参考仓库 Vue3 页面机械转译 → 否决（`<script setup>` 54 处、`value-format="YYYY-MM-DD"` 15 处是**静默出错**点，必须按 Element UI 语义重写，见 F-6）。
- **落地要点**：范围/质保窗口等取数走服务端；列表的标签交集筛选、框架树视图（框架行 → 子行的两级展示）在服务端完成，前端只渲染。

### D-9 字典与权限点落点

- **选择**：`item_types`（行项类型）、`contract_types`（合同类型，含类型码/主体码扩展信息）、`subjects`（我方主体）落 `sys_dict_type`/`sys_dict_data` 的 `dict_label`/`dict_value`/`remark`/`css_class` 复用；`warranty_window_days`（质保提醒窗口）等系统参数落 `sys_config`；权限点按 `<域>:<资源>:<动作>` 生成 `ctms:contract:*`、`ctms:contract-item:*`、`ctms:tag:*`、`ctms:partner:*`、`ctms:migration:*` 的 `sys_menu` 初始化 SQL（幂等：先按 `menu_id` 删除再插入），菜单目录挂在既有管理目录下。
- **理由**：PRD §7.3 把 `kv_settings` 的 5 个键归到 `sys_dict_*`/`sys_config`/`ruoyi-serial`；§7.5 定稿了权限点格式与新域前缀 `ctms:*`。
- **备选与否决**：新建业务配置表存字典 → 否决（与 REQ-NFR-001 的「统一字典」边界冲突）；沿用参考侧代码常量权限点 → 否决（真源必须是 `sys_menu`）。

### D-10 变更历史与操作日志分工

- **选择**：新增 `t_ctms_change_log` 承载**字段级前后值**（操作人标识 + 姓名快照、字段名、旧值、新值、来源手动/自动、对象类型/对象标识）；操作日志继续用 `@Log` + `SysOperLog` 记**动作**（新增/编辑/删除/恢复/认领）。特殊字段名沿用参考侧的 `_summary` / `_items` / `_tags` / `deleted` / `附件` 命名，便于与参考侧测试用例对照。
- **理由**：两者职责不同（参考侧 `audit_service.py:6-7` 明确区分）；`@Log` 是注解式的，覆盖面比手写日志更全，属复用项（移植清单 §9 第 4 条）。

## Risks / Trade-offs

- [数据范围口径差异导致越权或漏看（R-5，本批次最高风险）] → 口径写死在规格场景里（并集、含下级为默认、范围外 403）；判定集中在一处服务端组件；AC-79 用「四档 × 合同/单据」实测矩阵验收；`tools/authz-check.ps1` 扩展合同越权断言。
- [金额精度 1 分钱差异（C-1 / R-6）] → 全部 `BigDecimal` + 固定 scale；「先舍入再汇总」写成表驱动单测（含 3×1.665 → 5.01 的判别用例）；AC-78 用「编辑 → 详情 → 打印 → 导出」四方比对。
- [`ruoyi-serial` 无法表达合同编号（Q-B9）] → S0 先做技术验证，4 条判定标准明确；不通过则走 D-6 的最小扩展面，扩展不得改既有编号行为；无论走哪条分支，规格场景（按年重置、停用占号不复用、预览不占号）不变。
- [加 `NOT NULL`/FK 时被存量脏数据阻塞] → 必须先出体检清单（含违例主键），体检不通过不加约束；受影响表先快照；回滚脚本先在预生产库演练。
- [「先舍入再汇总」与「标的物摘要落库」偏离参考侧实际行为] → 已在 D-7 显式记录为「按文档口径收敛」，并在规格里写成可观察行为；若业务方最终选择与参考侧代码一致，只需改 D-7 的两个决策点，规格无需改（规格只约束已确认口径）。
- [迁移回填 `customer_id`/`supplier_id` 误绑] → 回填只在「人工认领」时发生，且逐份合同写变更历史（可回溯、可按历史批量纠正）；未认领的合同保持文本兜底，不阻塞。
- [前端 Vue3→Vue2 静默错误（F-6 / R-7）] → 日期格式统一 `yyyy-MM-dd` 并纳入 grep 门禁；`tools/audit/run-all.js` 全绿纳入 DoD；先做合同列表/详情两个重头页，再铺档案与迁移页。
- [B3 与 B4 的 DDL 编排耦合] → B3 只建立编排契约与占位段，B4 追加段；两段的验收各自独立（B3 段可单独在空库跑通）。
- [模块边界被破坏（第二套账号/字典/编号）] → 评审门禁：`ruoyi-ctms` 的依赖里必须出现 `ruoyi-serial`/`ruoyi-file`/`ruoyi-common`，且不得出现任何自建账号/组织/权限表；代码评审逐条对照 REQ-NFR-001。

## Migration Plan

**部署顺序（每步独立可执行、可重跑）**
1. **准备**：`sql/体检-合同台账-<日期>.sql` 对每个待加约束列输出违例行数与定位主键；同时输出「表名 / 受影响行数」影响行数报告。体检未通过 → 停止，人工修正数据后重跑。
2. **快照**：对受影响表建快照表 `<表名>_bak_<日期>`（结构与数据全量），保留至迁移验收通过后一个发布周期。
3. **DDL**：执行 `sql/二开-合同台账.sql`（建表 + 索引 + 约束回补段）；约束段内部再分「回填 → 加 NOT NULL → 加 FK」三步。
4. **编排**：维护 `sql/初始化-全部.sql`（按序 `source` 全部增量文件，B3 段在前、B4 段为其后追加）；`table.sql`/`data.sql` 不动。
5. **菜单与权限**：执行 `sql/二开-合同台账-菜单.sql`（幂等）；随后在「系统管理 → 角色管理」给非 `superAdmin` 角色勾选新权限点（没有现成脚本，属人工步骤，需写进发布检查单）。
6. **数据收敛**：先跑历史文本扫描生成草案；再按认领结果批量回填档案引用。
7. **应用**：后端 `mvn -B -DskipTests -pl ruoyi-admin clean package` 后用 JDK 11 的 java 起 jar（打包前必须先停后端，校验 jar 时间戳）；前端 `npm.cmd run dev` 验证。
8. **验收**：`node tools/audit/run-all.js`、`powershell -File tools/authz-check.ps1`、`tools/flow-regression.ps1`、`tools/sign-feature-check.ps1` 全绿，加上本变更集新增的合同台账回归脚本。

**回滚策略**
- **DDL 回滚**：`sql/回滚-合同台账-<日期>.sql`——按「删 FK → 删索引 → 恢复可空 → 删表（仅本变更集新建的表）」的逆序执行；新建表用 `DROP TABLE IF EXISTS`，回滚不触碰任何存量表结构。
- **数据回滚**：合同台账是新能力，回滚只需删新建表；如已执行档案引用回填，用 `t_ctms_change_log` 的 `customer_id`/`supplier_id` 记录 + 快照表反向 UPDATE 还原（先按快照核对行数，再执行）。
- **菜单回滚**：`DELETE FROM sys_menu WHERE menu_id IN (...)`（与增量脚本同一主键集合），并撤销角色授权勾选。
- **应用回滚**：回滚到上一版 `ruoyi-admin.jar`；前端回滚到上一版 `dist/`。因不修改任何存量表结构，应用回滚无需配套数据回滚。
- **不可回滚点**：一旦给存量列加上 `NOT NULL`/FK 且后续写入不再产生空值，回滚到「可空」需要重新跑「恢复可空 → 从快照回灌」两步；该步骤在预生产库先演练并计时。

## Open Questions

（以下均不影响规格与任务拆分，可在实施期确认。）

1. **自动标签色板与内置标签清单**：参考侧新建标签取色板 `["#409eff","#67c23a","#e6a23c","#f56c6c","#909399"]` 按标签总数取模（`contracts.py:381`），内置标签为「采购/销售/项目A/项目B」。目标侧是否沿用同一色板与内置清单，或改为「运营在标签管理页自行维护」？规格只约束「名称唯一、颜色存在、按总数取模选色」，两种做法都满足。
2. **质保提醒窗口默认值**：参考侧默认 30 天、取系统参数 `warranty_window_days`。目标侧参数键名与默认值是否保持 `30`（本设计按 30 落地，键名走 `sys_config`）。
3. **框架树视图的分页语义**：参考侧框架树忽略分页（框架行 + 子行 + 独立合同平铺，`total = 框架数 + 独立合同数`），平铺列表仍分页。目标侧是否保持「树视图不分页」，以及独立合同是否也按 id 倒序（本设计暂按参考侧口径）。
4. **软删除合同在框架树/质保提醒中的可见性**：参考侧框架树的子合同查询固定 `deleted=False`，即使主查询带 `include_deleted`。目标侧是否要在树视图里也尊重「包含停用」参数（本设计暂按参考侧口径：树视图始终排除停用）。
5. **质保释放是否单独授权**：释放质保是「编辑」还是独立动作/独立权限点？本设计暂归入合同的编辑权限，若运营要求分离（如财务专属）则新增 `ctms:contract:warranty-release` 权限点，规格无需改（只影响 D-9 的权限点清单）。
6. **历史附件是否搬运**：参考侧合同附件的二进制文件是否随合同一起迁移到目标侧存储？本设计暂定**不搬运**（迁移只收敛甲乙方档案引用），需要时作为独立的数据搬迁任务追加。
7. **合同编号签月与序号的展示口径**：参考侧月份码取**签订日期**所在月，但序号按「类型码+主体码+年份」全年共享（月份不重置）。目标侧是否接受「序号跨月连续、月份码随签订月」这一形态（本设计按参考侧口径落地，已写入规格场景）。
8. **合同类型与主体字典的扩展字段承载**：`contract_types`/`subjects` 在参考侧是带 `code/label/note/enabled` 的结构。落 `sys_dict_data` 时类型码用 `dict_value`、启用状态用 `status`、备注用 `remark`；是否还需要额外的展示字段（如排序权重）由运营确认，不影响本设计。
