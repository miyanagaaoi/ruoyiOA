# Proposal — OA 2.0 · B1 表单与流程设计升级（2.1 批次）

> **覆盖需求**：`REQ-FORM-003..011`、`REQ-DESIGN-012..017`、`REQ-PERM-001..005`、`REQ-DATA-004`、`REQ-DATA-006`、`REQ-NFR-009`（部分）
> **验收覆盖**：`AC-45..AC-60`、`AC-68`、`AC-82`
> **依赖**：`oa-v2-overview`（`platform/module-boundary`、`platform/delivery-gate`）；被 `oa-print-builtin-templates` 依赖（共享 `t_template` 增列）

## Why

新增一个可用的审批单据，现在要跑三处：① 模板新增页的 **5 个纵向分段**（`UI/views/workflow/template/add.vue:1-45`，滚很久）；
② 切到**另一个菜单**「流程设计（简化版）」（`UI/router/index.js:161-162`）建流程、发布；
③ 回模板页把刚才的 `defKey` **手工抄进**「关联流程」下拉（`basic-info.vue:22-31`）。

而且模板**没有任何可见性控制**：`selectNewStartTemplateList` 只过滤 `del_flag`/`enable_flag`
（`BE/.../mapper/template/TemplateMapper.xml:148-157`），Service 层不读登录用户，全仓 grep `submitScope` 零命中
——现状等价于"任何登录用户可发起任何启用模板"。模板上也没有"流程管理员"这个概念。

用户原文要求的三件事因此都是**新增/重构**，不是修复：
「默认有 4 个切换页面」「流程设计简易版移动到这里、自动绑定匹配」「谁可以提交该审批 / 流程管理员」，
外加一个新控件「关联审批」。

> 依赖：本变更集依赖 `oa-v2-overview` 的 `platform/module-boundary`（权限判定服务端强制、复用平台能力）
> 与 `platform/delivery-gate`（既有回归资产全绿、V1 的 AC-01..44 不回归、新增能力同批交付回归脚本）。
> 总纲尚未归档，故本变更集**不引用 `MODIFIED`**，以自身新能力承载要求。

## What Changes

- **模板新增/编辑页改为 4 个页签**：基础信息 / 表单设计 / 流程设计 / 更多设置。页签切换不丢内容；
  保存校验改为**逐页签校验**，失败自动切到出错页签（现状 `Promise.all` 一次性校验，隐藏页签里的错误看不见）。
- **基础信息页签新增 4 项**：图标（预设 ≥40 个、默认选第一个、用于发起页卡片）、说明（≤500 字）、
  **谁可以提交该审批**（全员/指定人员/指定角色/指定部门；默认**全员**）、**流程管理员**（可指定账号编辑该模板的流程）；
  同时**移除**「关联流程」手选下拉（改由页签 3 自动回写并只读展示）。「分组」复用现有「模板分类」。
- **页签 3 内嵌简化流程设计器**（不再跳转独立路由）：工具条重复项按模板自动带入并只读；
  `defKey` 由系统按 `tpl_` + 模板 id 前 8 位生成；保存即创建/复用流程草稿（支持 30 秒/失焦自动保存）。
- **流程发布自动回写模板**：发布成功后在同一后端事务里写回 `t_template.def_key` / `simple_flow_id` / `flow_mode`；
  部署成功但业务表写入失败时不得留下静默悬空态。
- **可发起范围生效（服务端强制）**：发起页列表按其过滤；直接构造请求发起无权限模板返回 **403 且不落库**；
  指定部门默认**含下级部门**（可关）。
- **新增表单控件「关联审批」**：管理员指定**已启用**模板为候选；发起者只能在本流程**所属分组**内
  选择**自己已发起**的单据；审批人点击后**浮窗只读**展示该单据的表单数据与审批状态；控件不参与流程条件判断，
  打印件输出为被关联单据号列表。
- **BREAKING（数据语义修正，必须最先做）**：`updateTemplate` 现状是"编辑 = 换新 UUID"
  （`TemplateServiceImpl.java:154-176`），导致 `t_template_print_template`、`t_template_node_field_auth`
  等所有 `template_id` 外键表在模板编辑后**失联**。本变更把它改为**原地 UPDATE（id 不变）**，
  并提供存量归并迁移脚本（含影响行数报告与回滚快照表）。不改这一条，本变更新增的 4 张表会继承同样的病。
- **修复**：无分类（`type` 为空）的模板在发起页被**静默丢弃**（`TemplateServiceImpl.java:233-243`）→ 归入「未分类」组展示。
- **不改动**：简化流程设计器本体（编译器/校验器/版本回滚已交付）、打印子系统、签名组件。

## Capabilities

### New Capabilities

- `workflow/form-definition`: 模板（单据定义）的四页签配置模型、基础信息字段与提交校验行为。
- `workflow/submit-scope`: "谁可以提交该审批"的范围模型、服务端过滤与越权拦截，以及流程管理员对流程编辑的授权。
- `workflow/related-approval`: 「关联审批」表单控件的候选范围、发起期选择约束与审批期只读浮窗。
- `workflow/template-flow-binding`: 模板与简化流程之间的绑定生命周期（内嵌设计、自动回写、草稿与发布失败的处理）。

### Modified Capabilities

无（总纲 `oa-v2-overview` 尚未归档，主规格 `openspec/specs/` 仍为空；避免引用不存在的 `MODIFIED` 目标）。

## Impact

- **规格**：新增 `openspec/specs/workflow/{form-definition,submit-scope,related-approval,template-flow-binding}/spec.md`。
- **后端**：`ruoyi-template`（实体/Mapper 五处同步、范围过滤、流程管理员校验、`updateTemplate` 语义、发起前越权兜底）；
  `ruoyi-workflow`（`simple-flow` 的 `by-template` 与发布回写、关联审批 2 个接口、发布补偿）；
  `ruoyi-biz-sdk`（`ComponentTypeEnum` 与 `BizFormServiceImpl.convertValueToLabel` 补分支）。
- **前端**：`views/workflow/template/{add.vue,basic-info.vue,business-form-info.vue,main-text.vue,attachment-info.vue,message-notice.vue}`
  改为四页签壳 + 逐页签校验；`views/workflow/simple-flow/designer.vue` 抽成可嵌入组件；`views/workflow/newstart/index.vue`（图标 + 未分类组）；
  **新增控件要改 11 处**：新建 `components/form/design/DesignRelatedApproval.vue`、`components/render/render.js`、
  `utils/generator/config.js`、`views/tool/build/RightPanel.vue`、`views/workflow/flow-form/component/view-form.vue`、
  `utils/formSchema.js`、`views/workflow/print/index.vue`（决定其是否排版类控件）、后端两处、以及动态表单的版本化更新链路。
- **数据库**：`t_template` 增 13 列（`icon`、`submit_scope_type`、`include_child_dept`、`simple_flow_id`、`flow_mode`、
  6 个提交/审批策略列、`forward_scope`；`builtin_print_key` 由 B2 同批增列）；新增 4 张表
  （`t_template_submit_scope`、`t_template_flow_admin`、`t_template_related_approval`、`t_workflow_related_approval`）；
  一次**存量归并迁移**（模板重复行 → 启用行）。
- **风险**：① `updateTemplate` 换 ID 的迁移会改动存量数据，必须先出影响行数报告并在副本演练；
  ② 新增控件踩"两条渲染路径 + 动态表单版本化"（`DEV-ENV.md:159-186`），少改一处就会出现"设计器能拖、审批页看不见"；
  ③ 范围过滤若只做前端隐藏，直接构造请求即可越权发起。
