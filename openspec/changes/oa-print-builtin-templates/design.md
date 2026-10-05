# Design

## Context

动机与缺口见 `proposal.md`。本设计只记录**影响实现走向的现状约束**与**技术决策**；需求行为契约见 `specs/workflow/print-templates/spec.md` 与 `specs/workflow/print-rendering/spec.md`。

现状约束（源码级证据见 `doc/2.0/RuoYi现状勘察.md` §5）：

| 约束 | 事实 |
| --- | --- |
| 打印子系统已交付 | 表 `t_template_print_template` / `t_print_log`、聚合接口、A4 预览页、配置页、日志均在。本变更是补差 + 收口 |
| 内置模板全局唯一 | 标题是单个常量 `DEFAULT_TITLE`（`PrintServiceImpl.java:466`）；`builtinTemplate(templateId)` 不接收单据类型（`:391-402`） |
| 内置模板被强制关签批栏 | `print/index.vue:182-200` 的 `isBuiltinTpl = !tpl.id` 把 `showSignature` / `showAttachment` 置 false |
| 优先级链已存在且正确 | `getEffectiveTemplate:311-324`：显式 `printTplId` > 该单据模板下的启用行 > 内置。**本次不动** |
| "同一单据模板只保留一套启用" | 是服务端不变量，在保存末尾无条件 `disableOthers`；**DB 层无唯一约束**（DDL 只有 `KEY idx_template`） |
| 配置项默认值有分歧 | DDL 里 `show_signature` / `show_attachment` 默认 `'1'`；`fillDefaults` 在空值时填 `'0'`。**直插库与走接口两条路径结果不同** |
| `t_template` 增列要五处同步 | `sql/table.sql`、实体、Mapper 的 resultMap / insert 列 / update 列。漏一处**静默丢字段** |
| 字段映射有双份副本 | 后端 `buildDefaultFieldMap():469-501` 与前端 `print-template/index.vue:156-173` 内容逐字一致，无自动化守卫（地雷 D-4） |
| 死字段 | `PrintData.formSchema` 从未赋值（`setFormSchema` 全仓零命中），前端也从不读 |
| 留痕是尽力而为 | `print/index.vue:284-308 doPrint()` 先 `window.print()` 再 `writeLog()`，catch 只 warning |
| 入口 3 个 | 详情页、已办列表行、我起草列表行；待办列表行缺 |

## Goals / Non-Goals

**Goals:**

- 把内置模板从"全局唯一常量"改成"按单据类型选用"，且**在 DB 直接写入与服务端接口写入两条路径下默认值语义一致**。
- 让"是否内置"与"是否出签批栏/附件清单"**彻底解耦**——前者是模板来源，后者是版式配置。
- 让 4 处"存了不用"的配置项（`paper` / `orientation` / `show_cc_node` / `logo_file_id`）真实生效。
- 让打印留痕成为**不可绕过的硬门禁**（先写日志后打印）。
- 消除前后端字段映射双份副本，删除死字段。

**Non-Goals:**

- 不新建打印模板表 / 打印日志表（`platform/module-boundary` 约束）；不改 `t_template_print_template` 的表结构。
- 不重写 A4 预览页骨架、不替换 iframe 浮层机制、不引入可视化拖拽打印模板（`vue-plugin-hiprint` 保持未用）。
- 不做打印件导出 PDF / 服务端渲染（现状是浏览器打印）。
- 不新增"内置模板管理页"菜单（内置版式是代码常量，不是数据行）。
- 不在本次修正 `t_template` 的 `updateTemplate` 换 ID 语义（属 B1 的 `REQ-DATA-004`）；但本变更的 DDL 增量必须与 B1 的列变更**同批次**交付以避免两次五处同步。

## Decisions

### D1. 用 `t_template.builtin_print_key` 承载"按单据类型选内置模板"（采纳）

在 `t_template` 增列 `builtin_print_key varchar(32) DEFAULT 'contract'`。`getEffectiveTemplate` 在回退内置分支时读该列，把 key 传给 `builtinTemplate(key)`。

**备选与否决理由**：

| 备选 | 否决理由 |
| --- | --- |
| 复用 `t_template.type`（模板分类）映射 key | "分组"是用户在页面上自由维护的分类，其语义是**发起页分组展示**；把它耦合到打印版式会让"挪一个分组"意外改掉打印件。且分类数据可被删除/合并，映射无稳定锚点 |
| 复用 `t_template.form_code` | 现状无该列的稳定语义，且一个表单可服务多种单据 |
| **预置数据行**（在 `t_template_print_template` 里 seed N 条内置模板，按 `template_id` 挂） | ① 与"单据模板还没建时没有 `template_id` 可挂"冲突——内置模板的意义正是**兜底**，seed 行做不到"新单据自动有版式"；② 会与"同一单据模板只保留一套启用"的不变量打架（seed 出来的行与管理员自己建的行争启用位）；③ 需要 seed 脚本 + 数据迁移，且删掉 seed 行后行为不可预期 |
| 代码里按模板名/字符串匹配 | 不可接受：改名即改版式，静默出错 |

**好处**：不改 `t_template_print_template`；不引入 seed 数据；优先级链原样保持不变；key 是显式、可校验、可默认的单一真源。

**代价**：要动 `t_template` 表 → 必须走**五处同步**（见 Risks）。

### D2. `builtinTemplate` 接收 key，标题与版式由 key 索引的注册表给出

`builtinTemplate(key)` 返回该 key 对应的**标题 + 版式常量**，`DEFAULT_TITLE` 单常量改为按 key 索引的标题注册表，`buildDefaultFieldMap()` 按 key 拆为 4 份构建方法。**调用点只有一处**（回退内置分支），同步修改即可。

**备选**：让 `builtinTemplate` 自己回去查库拿 key —— 否决：会让 `getEffectiveTemplate` 与 `builtinTemplate` 形成两次查询与隐式耦合；由调用方把 key 显式传入更可测。

**未知 key 的处理**：**不抛异常**，回退 `contract` 并记一条 warning。理由：打印是只读的收尾动作，因为一个历史脏值让整张打印件打不出来，比版式降级更糟；而保存入口已拒绝非法 key（见 `print-templates` spec）。

### D3. 内置版式常量以后端为唯一真源，前端改拉接口（消除 D-4）

新增 `GET /workflow/print/defaultFieldMap/{builtinKey}` 返回该 key 的字段映射，配置页的"填入内置版式"改为调用它，删除前端 `print-template/index.vue:156-173` 的副本。

**备选**：写一个自动化守卫（脚本比对两侧常量）—— 否决：本质是"用测试守住重复"，不如直接消除重复；且门禁脚本增加维护面。

**为什么同时新增 `GET /workflow/print/builtinTemplates`**：配置页需要"内置模板清单"来渲染模式选择器（key + 展示名称），否则前端会把 4 个名称再抄一遍——同一类重复问题。两个接口共用权限点 `workflow:print:template`。

### D4. 签批栏由版式配置决定，与"是否内置"解耦

去掉客户端"内置模板一律 `showSignature=false` / `showAttachment=false`"的判断，改为**一律读生效模板的配置值**；同时让内置版式自身携带 `showSignature` / `showAttachment` 的推荐值（`fund` / `payment` / `contract` 的签批栏开启，`matter` 按版式需要开启）。

**为什么这是第一验收项（AC-62）**：资金审批单的版式核心就是签批栏，"看着配好了、打出来没有签名区"是用户可见的功能空洞（PRD 风险 R-3）。

### D5. 统一 `showSignature` / `showAttachment` 的默认值语义（DDL 与代码对齐）

现状 DDL 默认 `'1'`、`fillDefaults` 空值填 `'0'`，导致"直插库"与"走接口"两条路径行为不同（勘察 §5.2 已标注这是 2.0 必须先统一的语义分歧）。

**决策**：把**服务端**的 `fillDefaults` 语义作为唯一口径（空值 → `'0'`，即"默认只打印表单信息"），并把 DDL 默认值改为 `'0'` 使两条路径一致。**不改已经落库的存量行**（存量行有显式值，不受默认值影响）。

**备选**：把 `fillDefaults` 改成 `'1'` —— 否决：会让所有新建配置模板默认多出签批栏与附件清单，与"只打印表单信息"的既有交互相反，且改变存量交互预期。

### D6. 打印留痕改为"先写日志后打印"的硬门禁

`doPrint()` 改为：`measurePages()` → **`await writeLog()`** → 成功才 `window.print()`；失败则提示并**不打印**。

**备选**：保留"先打印后写日志 + 失败提示"（现状）—— 否决：审计要求"必留痕"，把打印动作先发生的做法在物理上无法回滚。

**代价（风险取舍）**：留痕服务不可用时用户**打不出纸**。这是接受的取舍（审计优先），但提示文案必须明确说明"联系管理员"，避免用户误以为是浏览器问题。

### D7. `paper` / `orientation` 参数化，而非硬编码 A4

读生效模板的 `paper` / `orientation`，把版心高度与页数测算参数化（现状 `A4_CONTENT_PX` 是硬编码常量），并让横向时的分栏与分页规则随之变化。

**为什么不用 CSS `counter(pages)`**：现状注释明确"不依赖 `counter(pages)`，避免浏览器差异"（`print/index.vue:310-324`）——这个理由在 A3/横向下依然成立，因此保留"按像素测算页数"的路线，只是把参数换成变量。

### D8. 删除 `PrintData.formSchema` 死字段

全仓 `setFormSchema` 零命中、前端从不读。删除以消除契约噪声。**注意打印件的字段表来源是单据自身保存的 `formData.fields`（值在 `valData`、标签与顺序在 `fields`）**，删除该字段不影响取数路径。

**备选**：补上赋值 —— 否决：会让"两条取数路径"变成三条，且没有任何调用方需要它。

### D9. 新增一个 SQL 增量文件，承载 `builtin_print_key`

新增独立增量文件（例如 `sql/二开-打印模板内置版式.sql`），只包含 `t_template` 加列，保持"增量可重复执行"特性。**不修改 `sql/table.sql` / `data.sql`**（那是上游原样基线，见 PRD 8.4 方案 B）。

**注意**：`t_template` 的其余新列（`icon` / `simple_flow_id` / `flow_mode` 等）属 B1（`oa-form-flow-tabs`）。两个变更集都要往 `t_template` 加列 —— 若分两次交付，五处同步要做两轮且中间态不可用。**处置：本变更的 DDL 与 B1 同批次执行；若 B1 尚未落地，本变更的增量脚本必须能独立执行且不依赖 B1 的列。**

### D10. 不新增"内置模板管理页"菜单，不新增权限点

内置版式是代码常量，不是数据行 → 无 CRUD 页面。复用一个**已存在**的权限点 `workflow:print:template` 保护两个新增读接口。**不新增权限点、不新增菜单**，避免 2.0 权限点漂移（PRD 7.5）。

## Risks / Trade-offs

| 风险 | 应对 |
| --- | --- |
| **`t_template` 增列五处同步漏一处会静默丢字段**（`table.sql` / 实体 / Mapper 的 resultMap / insert / update） | 列为实现任务的显式验收项：保存一次带 `builtin_print_key` 的模板后**重新读取**必须拿到该值；用一条真实往返（保存 → 查询 → 打印聚合）验证，而不是只看 DDL 已执行 |
| **B1 与本变更同时改 `t_template`** → 两轮五处同步、中间态不可用 | 与 B1 同批次执行增量；本变更的增量脚本独立可执行，不引用 B1 的列 |
| **内置版式常量与 xlsx 参考件不一致** → 验收时不符 | 验收以 `doc/参考文档/请示审批单模版.xlsx` 的逐格解析（PRD §0.3）与附录 A 的字段来源表为唯一依据；`contract` 沿用已实现的 13 行映射**不改**，这是零回归的锚点 |
| **改掉"内置强制关签批栏"后，存量内置打印件多出签名区** → 与升级前不一致 | 这是**有意**的行为修正（AC-62），但必须在 tasks 里明确写进回归项：存量模板若走内置版式，需人工确认新打印件符合预期，并把结论记入验收记录 |
| **留痕硬门禁导致"留痕服务不可用就打不出纸"** | 接受（审计优先）。提示文案必须明确"留痕写入失败，请联系管理员"，不静默放行（AC-67） |
| **A3/横向的分页在浏览器间有差异** | 保留"按像素测算"路线 + `第 X 页 / 共 Y 页` 必须在 A4 纵向与 A3 横向两种组合下各自实测（AC-64） |
| **`showSignature` DDL 默认值改动影响新环境 vs 存量环境不一致** | 只改 DDL 默认值、不动存量行；存量行有显式值不受影响。DDL 与 `fillDefaults` 两条路径在**新写入**上一致即为达标 |
| **`disableOthers` 唯一性只有应用层、DB 无唯一约束** | 本次**不加**DB 唯一约束（属表结构变更，超出本变更边界）；在 design 登记为已知限制，并在配置页保留"生效中"标记与提示文案 |
| **未知 `builtin_print_key` 静默降级为 `contract`** → 脏数据不易被发现 | 降级同时记 warning 日志；保存入口拒绝非法值，使脏值只能来自人工 SQL |

## Migration Plan

1. **DDL 前置**：执行承载 `builtin_print_key` 的增量 SQL。存量行取默认值 `contract`，因此**存量单据打印件行为不变**（零回归锚点）。
2. **后端先行**：标题注册表 + 4 份版式常量 + `builtinTemplate(key)` + 读列 + 两个新读接口 + 删除死字段；此时前端仍按旧逻辑走，行为兼容（内置版式仍是 contract）。
3. **前端切换**：放开签批栏判定 → 拉取内置版式映射并删除本地副本 → 纸向/抄送栏/Logo → 留痕改先写后打 → 补待办入口。
4. **回滚策略**：前端改动可单独回退（旧版前端读不到新接口时需保留降级——**故前端删除本地副本必须与后端接口同批次上线**）；后端读列有默认值，回退后端版本后该列被忽略，无数据损坏。**唯一不可回滚的语义是留痕硬门禁**——回退即恢复"尽力而为"。

**交付门禁**（命令取自 `DEV-ENV.md`，本规划件不执行）：

```powershell
# 后端
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests -pl ruoyi-workflow install
mvn -B -DskipTests -pl ruoyi-admin clean package
(Get-Item ruoyi-admin\target\ruoyi-admin.jar).LastWriteTime   # 必须核对时间戳（DEV-ENV §6.13）

# 前端
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run dev

# 回归与审计
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1
node .\tools\audit\run-all.js
```

外加 AC-83 新增的第 ③ 个回归脚本「**多内置打印模板回归**」（4 套版式 × 标题/栏目/签批栏断言），随本变更交付并纳入门禁。

## Open Questions

1. **`matter` 版式的签批栏默认值**：PRD 附录 A 只给出 `matter` 的栏目清单，未逐项标明哪些栏目属于"签批栏"（`showSignature` 控制的范围）与"抄送节点"（`showCcNode` 控制的范围）。设计上按"公文接收及处理 / 公文关联单位处理 / 集团领导意见 / 印鉴证照管理部门 / 公文回传及归档"归入签批栏、其余归入表单信息区落地；若 UAT 时与实际纸质件不符，调整版式常量即可（**不影响规格与任务拆分**）。
2. **`payment` 的金额大写来源**：附录 A 记为"金额用 `design-amount` 控件，大写由控件提供"。若目标版本该控件不提供大写，则需在版式中自行换算——**属实现细节，不影响行为契约**（规格只要求"输出金额大写"）。
3. **`contract` 版式的 13 行映射是否要随本次新增 `showCcNode` 而调整**：现状 13 行映射已实现且是零回归锚点，本设计**默认不动**；若 UAT 要求合同版式也出抄送栏，通过模板配置开关实现，不改常量。
