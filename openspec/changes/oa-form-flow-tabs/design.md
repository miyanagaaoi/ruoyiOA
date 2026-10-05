# Design — OA 2.0 · B1 表单与流程设计升级

> 动机见 `proposal.md` — Why；行为契约见本变更集 `specs/` 下的四份 delta。
> 事实基线见 `doc/2.0/RuoYi现状勘察.md`（下文引用的行号均出自该报告核过的源码位置）。

## Context

四条约束决定了本设计的形状（其余现状见 `RuoYi现状勘察.md` §1 与 §3）：

1. **模板更新会换新 ID**：`TemplateServiceImpl.java:154-176` 把旧行置 `enable_flag='0' + del_flag='1'`，插入一条新 UUID。
   所有 `template_id` 外键表（现有 `t_template_print_template`、`t_template_node_field_auth`，本变更新增的 4 张表）编辑后失联。
2. **简化流程设计器已交付但是独立页面**：`simple-flow/designer.vue`（1367 行）挂在独立路由 `UI/router/index.js:161-162`，
   顶部有 4 个全局输入（流程名称/流程标识/分类/关联表单），其中「关联表单」绑的是 `t_template_dynamic_form` 而不是 `template.formId`。
3. **模板与流程完全没有绑定**：`t_flow_simple` 无 `template_id`，`t_template` 无 `simple_flow_id`，
   发布接口 `PublishBody` 无 `templateId`；唯一关联是 `SimpleFlowServiceImpl.syncNodeFieldAuth:225-238` 的**反向字符串查询**。
4. **表单有两条渲染路径 + 动态表单是版本化的**：拟稿走 `components/parser/Parser.vue`（内嵌 `render`），
   审批/详情走独立的 `views/workflow/flow-form/component/view-form.vue`；动态表单的 `PUT` 会插入新版本并使旧行 `enable_flag=0`，
   而 `t_template.form_id` 指向的是**某一版**（`DEV-ENV.md:159-186`）。新增一个控件要改 11 处（`RuoYi现状勘察.md` §2.2）。

环境约束：后端必须用 JDK 11 的 `java.exe` 启动；前端必须 `npm.cmd run dev`；SQL 走"上游基线 + 二开增量"双轨（`DEV-ENV.md`）。

## Goals / Non-Goals

**Goals**

- 让"新建一条单据"在一个页面内闭环：四页签 → 内嵌设计流程 → 发布 → 绑定自动完成。
- 让模板的可见/可发起范围**由服务端强制**，且默认行为与升级前一致（存量零回归）。
- 让新增控件在**两条渲染路径 + 打印件 + 条件字段 + 后端值转换**上都成立。

**Non-Goals**

- 不改动简化流程设计器本体（编译器 JSON→BPMN、校验器 V-0..V-12、版本/历史/回滚已交付，本变更只做"嵌入 + 绑定"）。
- 不改动打印子系统与签名组件。
- 不做拖拽式打印模板（`vue-plugin-hiprint` 仍保持"已装未用"）。
- 不引入 vform 设计器（1.36 MB UMD，全仓零使用，属死代码）。

## Decisions

### D1 四页签用 `el-tabs` 而不是四步向导

- **选择**：`activeTab` + 四个 `el-tab-pane`，允许任意顺序切换。
- **理由**：用户改"只调一下打印模板"这类小改时，向导会强制走过前三步；页签允许直达。
- **备选**：四步向导（照 `wireframes.html` 的 S01–S04）——被否，线性流程对"改一个字段"的日常操作过重。
- **代价**：必须把现在的"一次性 `Promise.all` 校验 5 个表单"（`add.vue:108-115`）改成逐页签校验，
  否则隐藏页签里的错误无法定位；同时 `activeNames/activeName` 两个死字段删除（`add.vue:66-67`）。

### D2 逐页签校验用"校验 → 失败即切页签 → 定位首错"

- **选择**：每个页签的子表单各自持有 `validate`；保存时按页签顺序校验，遇到第一个失败即切换到该页签并滚动/高亮首个错误字段。
- **理由**：Element UI 的 `el-form.validate(cb)` 在隐藏容器里不会自动滚动，用户只看到"请完善必填字段"却找不到位置（这正是现状的体验问题）。
- **备选**：(a) 保留一次性校验但加"哪个页签有错"的提示——被否，仍需用户自己找；
  (b) 全部字段平铺校验——被否，页签的意义被抹掉。

### D3 页签 3 的内嵌方式：把设计器"组件化 + 注入宿主"

- **选择**：把 `designer.vue` 的「工具条 + 画布 + 配置抽屉」抽成可复用组件，宿主（模板页）通过 props 注入
  `templateId` / `formId` / `flowId`；内嵌时隐藏流程名称与关联表单两个输入（改为只读展示）。
- **理由**：直接 `import` 原页面会把独立页的全局输入、路由返回、独立保存/发布路径一起带进来，产生两套保存入口。
- **备选**：(a) iframe 内嵌——被否，登录态、样式、事件通信都会成为新问题；
  (b) 复制一份设计器给页签用——被否，1367 行会立刻分叉，后续修一次要改两处。
- **代价**：抽象工作量大（设计器 1367 行 + `FlowTree.vue` 646 行 + `ConditionEditor.vue` 233 行），属本变更最大的 L 项。

### D4 「关联表单」改为由模板决定并只读

- **选择**：内嵌时流程的字段来源固定取 `template.formId` 指向的表单，界面不提供切换入口。
- **理由**：现状设计器让管理员手选表单，会造出"流程绑了 A 表单、模板绑了 B 表单"的错配——流程条件引用的字段在单据里根本不存在。
- **备选**：保留手选并加一致性校验——被否，既然绑定已由模板持有，手选没有存在价值。

### D5 绑定方向：模板持有流程引用（`simple_flow_id`），`defKey` 由模板 id 派生

- **选择**：`t_template` 增 `simple_flow_id` / `flow_mode`；`defKey = 'tpl_' + templateId 前 8 位`；发布时回写 `def_key`。
- **理由**：见总纲 `oa-v2-overview` 的 design D3（本变更沿用，不另立）。
- **备选**：(a) 只回写 `def_key`——被否，无法表达"流程还是草稿、尚未发布"；
  (b) 用模板名派生 `defKey`——被否，中文名不适合做流程标识且会重名。

### D6 保存顺序：模板先落库拿 id → 再取/建流程草稿 → 发布时回写

- **选择**：`① 保存模板（upsert，拿 templateId）` → `② GET/POST by-template/{templateId}` 取或建流程草稿 →
  `③ 发布时后端在同一事务里部署 BPMN、更新流程状态、回写模板三列`。
- **理由**：`defKey` 由模板 id 派生，必须先有模板 id；而流程草稿可以早于发布存在（模板处于"流程未发布"）。
- **备选**：先流程后模板——被否，`t_template.def_key` 是 `NOT NULL`，需要临时 key 或放宽约束。
- **代价**：必须处理"部分成功"。前端要能区分"模板已存、流程未发布"，后端要有补偿或显式可重试状态
  （现状 `SimpleFlowServiceImpl.publish:141-217` 有 `@Transactional` 但 Flowable 部署与业务写入无补偿）。

### D7 先修 `updateTemplate` 的换 ID 语义（本变更的前置条件）

- **选择**：`updateTemplate` 改为**原地 UPDATE 同一行**（`id` 不变）。
- **理由**：本变更自己就要新增 4 张带 `template_id` 的表，还要在发布时回写模板；放在"换 ID"的语义上，
  回写会写到废弃行、新表的关联会立刻失联。这是**前置条件**，不是可选优化。
- **备选**：(a) 保留换 ID、编辑后级联把 5 张子表的 `template_id` 迁到新行——被否，每加一张子表都要记得改，长期必漏；
  (b) 不动——被否，等于把已知缺陷复制到 2.0 的新表上（违反 `platform/delivery-gate` 的"移植缺陷不得搬入"精神）。
- **迁移**：把现存"同一模板的多个版本行"归并到启用行：子表 `template_id` 统一指向 `enable_flag='1'` 的行；
  迁移前**先输出影响行数报告**，并把迁移前映射写入快照表以便回滚。

### D8 可发起范围：Service 层过滤 + 发起前兜底校验（双点强制）

- **选择**：
  1. `TemplateServiceImpl.listNewStartTemplate` 注入 `SecurityUtils` 的 userId/deptId/roleIds，在**分组之前**过滤（保留"分组驱动"语义）；
  2. 发起落库前再校验一次，无权则 403 且不落库。
- **理由**：只做前端隐藏（现状的等价形态）可被直接构造请求绕过；只在列表过滤则"知道模板 id 就能发起"。
- **备选**：(a) 只改 `TemplateMapper.xml:148-157` 的 SQL 加 `exists` 子查询——被否，多态明细表（人员/角色/部门三列语义）
  在 SQL 里拼条件可读性差且难复用；Service 层更可控。
  (b) 复用 RuoYi 的 `@DataScope`——被否，它只覆盖系统模块 5 处，语义按 `user_id`/`dept_id` 展开，与"模板可见范围"不是一回事。
- **部门含下级**：用 `sys_dept.ancestors` 做前缀匹配（RuoYi 既有物化路径），默认包含下级，由 `include_child_dept` 开关控制。

### D9 「关联审批」控件按 11 处清单落地，并单独建关系表

- **选择**：控件 tag 取 `design-related-approval`；值在 `valData` 里存被关联单据的业务ID数组；
  **另外**落一张关系表（宿主单据、控件、被关联单据、单据号快照），用于反查与浮窗鉴权。
- **理由**：只存 JSON 数组无法建索引，也做不到"审批人点开时反查并鉴权"。
- **备选**：只存数组、浮窗时遍历解析——被否，随单据量增长会退化为全表扫描，且无法按被关联单据反查。
- **11 处登记**（少一处就"设计器能拖、运行时白屏"）：控件本体、`render.js`（import + components）、
  `utils/generator/config.js`、`views/tool/build/index.vue` 左侧分组、`RightPanel.vue` 属性面板、
  `view-form.vue` 只读视图、后端 `ComponentTypeEnum`、`BizFormServiceImpl.convertValueToLabel`、
  `utils/formSchema.js` 的 `NON_CONDITION_TAGS`（参与条件判断要否：**不参与**，加入该名单）、
  打印侧 `isLayoutOnly` 判定（**不加入**，关联审批要打印）、以及动态表单的**版本化更新链路**。

### D10 控件改动必须处理动态表单的版本化

- **选择**：改表单后**显式**让模板指向新的表单版本（回写 `t_template.form_id`）。
- **理由**：动态表单 `PUT` 会插入新版本并使旧行 `enable_flag=0`，列表只返回启用版本；而 `t_template.form_id` 指向**某一版**，
  不自动跟随。若不处理，用户会遇到"设计器里有这个控件、发起页却没有"。
- **备选**：把 `t_template.form_id` 改为按 `formKey` 动态解析最新启用版本——被否，会让"在途单据"重新按新表单渲染，
  破坏已经提交的单据形态（历史单据应保持发起时的表单）。

### D11 存量兼容：所有新增策略列取"等于现状"的默认值

- **选择**：`revoke_limit_minutes=0`（不限制）、`allow_edit_after_submit='0'`、`allow_submit_for_other='0'`、
  `allow_approver_revoke='1'`、`allow_batch_approve='1'`、`approver_dedup='3'`（不自动同意）、`forward_scope` 空（不限制）、
  `submit_scope_type='0'`（全员）、`include_child_dept='1'`、`flow_mode='0'`。
- **理由**：`platform/delivery-gate` 要求 V1 的 AC-01..44 不回归，而 V1 的 AC-39 明确要求存量模板与存量流程功能不回归。
  默认值即"未配置时的旧行为"，是达成零回归的最省事也最可靠的方式。
- **备选**：新列一律 `NOT NULL` 且无默认、强制用户回填——被否，会让存量模板在升级后立刻不可用。

### D12 静态审计新增两项检查

- **选择**：在既有 `tools/audit/run-all.js` 的脚本集合中新增两项：
  ① 模板四页签重构回归（校验四个页签容器与逐页签校验入口存在、死字段已删）；
  ② 流程↔模板绑定回归（校验 `by-template` 与发布回写链路存在、`defKey` 派生规则唯一）。
- **理由**：`platform/delivery-gate` 要求"新增能力同批交付回归脚本"，且这套审计已有 `--selftest` 与 baseline 机制可直接挂。
- **备选**：只写集成测试不做静态审计——被否，审计脚本是仓库既有惯例（6 个脚本已沉淀）。

## Risks / Trade-offs

- [换 ID 迁移会改动存量数据] → 迁移前输出**影响行数报告**并在库副本演练；迁移前映射写入快照表；提供按快照回滚的脚本。
- [设计器组件化可能引入回归] → 组件化时保持原有 API 调用与保存/发布语义不变，先在独立路由页验证组件化后功能等价，再切到内嵌。
- [新增控件踩两条渲染路径] → 按 11 处清单逐条落地；门禁加"设计器能拖 / 拟稿能填 / 审批能看 / 打印能出"四态回归。
- [动态表单版本化导致"控件不生效"] → D10 显式回写 `form_id`；在页签 2 保存后给出"表单已更新为第 N 版"的提示。
- [范围过滤使发起页变空] → 一并修掉"无分类模板被静默丢弃"；空态区分"没有模板"与"没有你可发起的模板"。
- [发布部分成功] → 前端区分两态并给出重试入口；后端把回写放在同一事务的靠后位置，优先保证"引擎已部署"可被感知。
- [流程草稿自动保存与手动保存竞争] → 自动保存走同一 upsert 端点并按 `id` 幂等，不新建草稿行。

## Migration Plan

1. **前置（必须先做）**：备份 `rad_oa`；在副本上演练 `updateTemplate` 语义修正与存量归并；
   输出**影响行数报告**并落快照表 `t_template_migrate_backup`。
2. **DDL**：`t_template` 增 13 列（带默认值，保证存量行立即有合法值）；新增 4 张表；补索引。
3. **后端**：范围过滤与发起兜底 → `by-template` 与发布回写 → 关联审批两接口 → 补偿处理。
4. **前端**：四页签壳 + 逐页签校验 → 设计器组件化与内嵌 → 新增控件 11 处 → 发起页图标与未分类组。
5. **回归顺序**：静态审计 → 越权脚本 → 流程回归 → 本变更新增的两项审计 → 人工 UAT。
6. **回滚**：
   - 代码：三个仓库各自回退到发布前 commit（基线见 `DEV-ENV.md`）。
   - 数据：新增 4 张表可 `DROP`；`t_template` 的 13 列可保留（默认值即旧行为）；
     唯一需要反向脚本的是第 1 步的**归并迁移**，按 `t_template_migrate_backup` 还原。

## Open Questions

（均不影响本变更的规格与任务拆分，可在实现期回答。）

1. 关联审批浮窗的实现形态（`el-dialog` vs 抽屉）——取决于表单数据字段数量，实现时按实测定。
2. 四页签是否需要在切换时把未保存状态缓存到 `sessionStorage`——按实测的丢失风险决定。
3. 图标集的具体清单（40 个 vs 更多）——取 Element UI 图标集的一个稳定子集，实现时定稿。
