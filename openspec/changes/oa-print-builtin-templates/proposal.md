# Proposal

> **覆盖需求**：`REQ-PRINT-011..018`、`REQ-NFR-009`（部分：消除前后端重复常量）、`REQ-NFR-010`
> **验收覆盖**：`AC-61..AC-67`、`AC-82`、`AC-83`（其中的「多内置打印模板回归」脚本）
> **依赖**：`oa-v2-overview`（`platform/module-boundary`、`platform/delivery-gate`）；`oa-form-flow-tabs`（共享 `t_template` 增列，由 B1 一次性完成）
> **批次**：B2，随 2.1 交付

## Why

打印子系统在 V1 已**完整交付**（`t_template_print_template` 表、聚合接口 `GET /workflow/print/data/{businessId}`、A4 预览页、打印模板配置页、打印日志 `t_print_log`），本变更是**补差 + 收口**，不是从零新建。

现状留存四处缺口，导致《集团资金审批单》这类单据「看着配好了，打出来没有签名区」：

1. **内置模板全局只有一套**：标题常量 `DEFAULT_TITLE`（`PrintServiceImpl.java:466`）是单个 `static final String`；`builtinTemplate()` 不接收单据类型（`:391-402`），无法按单据类型给出不同标题与版式。
2. **内置模板被前端强制关闭签批栏**：`print/index.vue:182-200` 的 `isBuiltinTpl` 把 `showSignature` / `showAttachment` 置 false，而资金审批单的版式核心恰恰就是签批栏（PRD 风险 R-3）。
3. **配置项"存了不用"**：`paper` / `orientation` 只落库不生效（A4 版心硬编码在 `A4_CONTENT_PX`），`show_cc_node` 存了前端不读，`logo_file_id` 无 UI 无代码引用。
4. **留痕是"尽力而为"**：`print/index.vue:284-308 doPrint()` 的 `writeLog` catch 只 `warning`；且打印入口只有 3 个（详情页、已办列表行、我起草列表行），**待办列表行缺失**。

同时存在两处技术债：前后端各有一份重复的 `DEFAULT_FIELD_MAP`（后端 `PrintServiceImpl.java:469-501`、前端 `print-template/index.vue:156-173`，地雷 D-4，无自动化守卫）；`PrintData.formSchema` 是永不赋值的死字段（`setFormSchema` 全仓零命中）。

## What Changes

- **新增 `t_template.builtin_print_key varchar(32) DEFAULT 'contract'`**：内置模板由"全局唯一常量"改为**按单据类型选用**。`getEffectiveTemplate` 读该列，`builtinTemplate(key)` 返回该 key 对应的**标题 + 版式常量**。优先级链 **保持"显式 `printTplId` > 单据模板下的启用行 > 内置"不变**。
- **内置版式从 1 套扩展为 4 套**（已确认 Q-B1）：`contract`（集团合同类文件流转审批单，沿用已实现的 13 行映射）、`fund`（集团资金审批单）、`matter`（集团事项类打印审批单，默认取"集团请示文件流转审批单"版式）、`payment`（付款申请单）。栏目与系统字段来源逐项对齐 PRD 附录 A。
- **修正签批栏策略（本变更第一验收项，AC-62）**：内置模板不再强制关闭签批栏；`fund` 版式用内置模板即可打印出「集团职能部门 / 集团分管领导 / 集团董事长」三栏签名区。同步修正 `fillDefaults` 的默认值语义分歧（DDL 默认 `'1'` vs 代码空值填 `'0'`）。
- **补全四处"存了不用"的链路**：`paper` / `orientation` 生效（A4/A3、纵向/横向，分页与页数测算随之参数化）、`show_cc_node` 在签批栏出栏、`logo_file_id` 提供配置 UI 并在打印件抬头左侧输出（未配置时不出现空白占位）。
- **内置版式以后端为唯一真源**：前端不再复制 `DEFAULT_FIELD_MAP`，改为从后端接口拉取；删除 `PrintData.formSchema` 死字段。
- **留痕改为硬门禁**：由"先打印后写日志"改为**先写日志后打印**；写日志失败时**阻断打印并给出明确提示**。
- **补第 4 个打印入口**：待办列表行（`todo/todo-list.vue`，照抄 `done/index.vue` 的行内打印按钮）。
- **范围外（明确不做）**：不重写打印子系统与 A4 预览页骨架；不引入可视化拖拽打印模板（`vue-plugin-hiprint` 保持未用，PRD N-5）；不改 `t_template_print_template` 的表结构（配置模板能力不变）；**禁止新建打印模板表 / 打印日志表**。

## Capabilities

### New Capabilities

- `workflow/print-templates`: 内置打印模板的**按单据类型选用**与版式常量。承载"内置模板注册表（4 个 key）→ 单据模板绑定 key（`builtin_print_key`）→ 有效模板判定与优先级链 → 版式常量唯一真源 → 配置页内置/自定义两种模式"这一组行为。
- `workflow/print-rendering`: 打印件的**渲染与生效项**。承载"纸张与方向生效、签章区出栏策略、Logo 抬头、抄送节点留痕、打印留痕硬门禁、打印入口覆盖"这一组行为。

### Modified Capabilities

（空 —— 见下方 Impact 的依赖说明。）

## Impact

**依赖声明（本变更为 B2 批次，属于 `oa-v2-overview` 2.0 总纲的第 2 个子变更集）**：

本变更集依赖 `oa-v2-overview` 的 `platform/module-boundary` 能力（**禁止新建打印模板表 / 打印日志表**）与 `platform/delivery-gate`；若总纲尚未归档，本变更集的 delta 不引用 `MODIFIED`，而是以本变更集自身的新能力承载要求，待 `oa-v2-overview` 归档后再由后续变更集收敛。

**受影响代码（实现阶段，本规划件不修改）**：

| 层 | 落点 | 动作 |
| --- | --- | --- |
| DDL / 增量 SQL | `BE/sql/二开-打印模板.sql`（新增量） | `t_template` 增 `builtin_print_key varchar(32) DEFAULT 'contract'`；注意 `t_template` 增列要**五处同步**（`sql/table.sql`、`Template.java`、`TemplateMapper.xml` 的 resultMap / insert / update 列） |
| 后端 | `PrintServiceImpl.java` | `DEFAULT_TITLE` → 4 个 key 的标题注册表；`builtinTemplate(key)` 增入参并同步改调用点；`buildDefaultFieldMap()` 拆为 4 份；`getEffectiveTemplate` 读 `builtin_print_key`；修正 `fillDefaults` 的 `showSignature`/`showAttachment` 默认语义 |
| 后端 | `WorkflowPrintController.java` / `IPrintService.java` | 新增 `GET /workflow/print/builtinTemplates`（内置模板清单）与 `GET /workflow/print/defaultFieldMap/{builtinKey}`（内置版式字段映射），权限点 `workflow:print:template`；`/workflow/print/data/{businessId}` 契约不变 |
| 后端 | `PrintData.java` | 删除死字段 `formSchema`（`setFormSchema` 全仓零命中） |
| 前端 | `print/index.vue` | 放开内置模板签批栏判定（`:182-200`）；读 `tpl.paper` / `tpl.orientation`，参数化 `A4_CONTENT_PX` 与页数测算；读 `show_cc_node` 输出抄送栏；抬头左侧输出 Logo；`doPrint()` 改"先写日志后打印" |
| 前端 | `print-template/index.vue` | 删除复制的 `DEFAULT_FIELD_MAP`（`:156-173`），改从后端拉取；新增"选择内置模板 / 选择自定义模板"模式与实时预览；新增 Logo 上传控件 |
| 前端 | `todo/todo-list.vue` | 补行内打印按钮，入口总数达 4 |
| 门禁 | `tools/audit/run-all.js`、`tools/flow-regression.ps1`、`tools/authz-check.ps1` | 全绿；新增"**多内置打印模板回归**"脚本（AC-83 三个新增脚本之一） |

**需求追溯**：`REQ-PRINT-011..018`、`AC-61..AC-67`、`AC-83`（多内置打印模板回归脚本）。

**风险**：R-3（内置模板强制关签批栏）列为本变更首要验收项（AC-62）；`t_template` 增列的五处同步若漏一处会**静默丢字段**；`disableOthers` 唯一性不变量目前只在应用层，DB 层无唯一约束（本变更不改该约束，仅在 design 中登记）。
