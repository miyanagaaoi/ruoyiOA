# 飞书审批「设计器」功能参考文档

> **用途**：为自研 OA 审批系统的「表单设计器 + 流程设计器」提供功能对标、字段建模与需求清单参考。
> **来源标注**：✅ = 在飞书审批后台页面中**直接实测观察到的原文**；📘 = 官方帮助中心 / 开放平台文档；⚠️ = 官方文档中**未找到依据**，未经验证。
> **实测对象**：`https://www.feishu.cn/approval/admin/createApproval?id=7464107468755484700&definitionCode=ED3C533D-…`（「合同审批（含用印）」草稿，飞书审批管理后台）

---

## 目录

1. [整体结构](#1-整体结构)
2. [基础信息](#2-基础信息)
3. [表单设计](#3-表单设计)
4. [流程设计](#4-流程设计)
5. [更多设置](#5-更多设置)
6. [数据模型（开放平台 approval v4）](#6-数据模型开放平台-approval-v4)
7. [自研落地建议](#7-自研落地建议)
8. [功能需求清单（可直接当需求池）](#8-功能需求清单可直接当需求池)
9. [未验证 / 待补项](#9-未验证--待补项)
10. [参考资料](#10-参考资料)

---

## 1. 整体结构

审批定义编辑器是一个**四步向导**（顶部横向步骤条，可点击切换）：

| 步骤 | 名称 | 作用 |
|---|---|---|
| 1 | 基础信息 | 审批的元数据 + 提交范围 + 管理权限 |
| 2 | 表单设计 | 拖拽式表单搭建 |
| 3 | 流程设计 | 可视化流程编排 |
| 4 | 更多设置 | 全局策略（撤销/修改/去重/打印/转发等） |

创建方式 📘 共 3 种：**使用模板创建 / 创建自定义审批 / 接入三方审批**。

### 1.1 顶部工具条 ✅

- 审批名称（实测：`合同审批（含用印）`）
- 草稿状态：`草稿（保存于 1 分钟前）` —— 点击展开**草稿与版本历史**面板
- `预览`：弹出「XX 预览」对话框，提示 *"打开手机飞书，扫描二维码预览审批流程"* → **移动端扫码预览**（不是页面内预览）
- `发布`
- 流程设计页额外入口：`创建限时审批`、`创建自动化`

### 1.2 草稿与版本管理 ✅

点击草稿下拉后实测到的结构：

| 项 | 状态 | 操作人 | 时间 |
|---|---|---|---|
| 草稿 | 设计中 | 宫鑫 | 保存于 1 分钟前 |
| 9月9日 09:44 版本 | **启用中** | 聂东南 | 发布 |
| 9月9日 09:42 版本 | 历史 | 聂东南 | 发布 |
| 9月7日 19:27 版本 | 历史 | 聂东南 | 发布 |
| …（共 10+ 条） | 历史 | … | 发布 |

进入编辑器时若存在未提交草稿，会弹出：**「是否打开上次编辑的草稿 / 最近保存于 X 分钟前」** + `打开草稿` / `放弃草稿` ✅

> **设计要点**：草稿与已发布版本**分离**；版本有「启用中 / 历史」状态；记录发布人与发布时间；在途单据必须冻结在其发起时的版本上。

### 1.3 流程设计画布 ✅（实测的真实流程）

```
提交（提交人：全员可提交 / 可设置抄送人）
  └─ 审批（审批人：直属部门负责人）
       ├─ 并行分支 1：当 合同类型 等于 经营 → 审批（审批人：经发负责人）
       └─ 其他情况：如存在未满足其他分支条件的情况，则进入此分支
            └─ 审批（行政）→ 审批（法务）→ 审批（行政）→ 审批（财务）→ 审批（副总经理）
                 └─ 抄送（抄送人：提交人自选，王佳琦，李玥洋，樊娟娟）
                      └─ 办理盖章上传备案（办理人：郑妍）
                           └─ 结束（抄送人：提交人自选，王佳琦，李玥洋，樊娟娟）
```

画布交互 ✅：`添加并行分支`、分支卡片序号（`并行分支 1`）、兜底分支 `其他情况`、右下角缩放（`100%` 及缩放按钮）。
📘 插入节点方式：点击节点之间的 `+`，可选 **审批人 / 办理人 / 条件分支 / 并行分支**；流程固定以「发起（提交）节点」开始、「结束节点」结束；未配置流程时系统预置一个审批节点。

---

## 2. 基础信息 ✅

| 配置项 | 说明（页面原文） |
|---|---|
| 图标 | `修改`（可选图标） |
| 名称 | 审批名称（必填） |
| 说明 | 审批说明文案 |
| 分组 | 归属分组（审批列表分类） |
| 谁可以提交该审批 | 实测两种取值：`全员` / `指定人员`（指定人员时提供 `添加` 选择成员） |
| 权限开关 | `禁止企业管理员/审批应用管理员/子管理员 管理流程与数据` |
| 流程管理员 | 单独指定可管理该审批流程与数据的人员 |

📘 API 侧对应：`viewers[]`（`USER` / `DEPARTMENT` / `TENANT` / `NONE`，上限 200）、`process_manager_ids[]`（≤200）、`config{can_update_viewer / can_update_form / can_update_process / can_update_revert / help_url}`。

> **自研提示**：把「提交范围 submit_scope」与「管理范围 admin_scope」拆成两个权限维度；`config.*` 那组开关意味着飞书允许管理员把某些修改能力**下放给使用者**，自研时值得保留这一层。

---

## 3. 表单设计

### 3.1 设计器交互 📘 + ✅

- 左侧面板两个页签：`控件` / `控件组` ✅
- 控件与控件组均支持**点击或拖拽**加入；按添加顺序自上而下排列，**可拖拽改序** 📘
- 画布空态：`点击或拖拽左侧控件至此处` ✅
- 选中控件 → 右侧弹出设置面板（**基础设置 + 显隐设置**）📘
- `配置打印模板` ✅
- **一份表单最多只能添加 1 个控件组** 📘
- 不支持 Markdown 排版（加粗/斜体/下划线均不可）📘
- 修改表单只对**之后发起**的审批生效 📘
- 分栏/多列布局、控件复制粘贴：📘 文档未提及，⚠️ 未验证（实际为单列顺序布局）

### 3.2 控件清单 ✅（页面实测分组）+ 📘（类型与属性）

| 面板分组 ✅ | 控件 ✅ | API type 📘 | 关键配置/限制 📘 |
|---|---|---|---|
| 飞书云文档 | 文档 | `document` | 仅 docx；`value{token,type}` |
| 文本 | 单行文本 | `input` | 标题/提示语/默认值；≤256 字符 |
| 文本 | 多行文本 | `textarea` | 同上 |
| 文本 | 说明 | `text` | 纯展示不可填；**不支持 API 创建实例** |
| 数值 | 数字 | `number` | 可设单位与取值范围（min/max 可只设一端） |
| 数值 | 金额 | `amount` | 17 种币种(默认 CNY)、大写数字、千位分隔符、小数位、金额范围 |
| 数值 | 计算公式 | `formula` | 由 数字/金额 实时计算；**不支持 API 创建** |
| 选项 | 单选 | `radio` / `radioV2` | 手填或批量粘贴(一行一项)、**最多 200 项**、可设默认项、可接外部数据源 |
| 选项 | 多选 | `checkbox` / `checkboxV2` | 同上 |
| 日期 | 日期 | `date` | 格式 `YYYY-MM-DD` / `YYYY-MM-DD a` / `YYYY-MM-DD hh:mm` |
| 日期 | 日期区间 | `dateInterval` | `value{format, intervalAllowModify}`（是否允许修改自动计算时长） |
| 其他 | 明细/表格 | `fieldList` | 见 3.4 |
| 其他 | 引用多维表格 NEW | `mutableGroup` | 仅选自己有「可管理」权限的表；**数据量 ≤1 万条**；可放进明细；**不支持 API 创建** |
| 其他 | 图片/视频 | `image` / `imageV2` | 单张 ≤50MB、最多 20 张 |
| 其他 | 附件 | `attachment` / `attachmentV2` | 单文件 ≤500MB、最多 30 个 |
| 其他 | 部门 | `department` | 多选、显示层级、默认值 |
| 其他 | 联系人 | `contact` | `value{ignore,multi}`；可设「可选自己」 |
| 其他 | 关联审批 | `connect` | 可配可选范围、外显被关联字段、仅可关联「已通过」；**1 个控件最多关联 100 个单据** |
| 其他 | 地址 | `address` | `enableDetailAddress / requiredDetailAddress / preLocating`（自动定位默认开）；非出差场景只支持 1 个地址 |
| 其他 | 定位 | `location` | ⚠️ 面板上列为独立控件；文档口径显示「定位」实为**地址控件的属性**（preLocating），口径不一致 |
| 其他 | 收款账户 | `account` | 个人/对公账户，仅支持部分中国境内银行；账户自动保存(仅自己可见)；**不能放入明细**；**审批人不可编辑**；不支持 API 创建 |
| 其他 | 电话 | `telephone` | `option.availableType` ∈ `MOBILE` / `FIXED_LINE` / `FIXED_LINE_OR_MOBILE`，自动校验格式 |
| 其他 | 流水号 | `serialNumber` | 规则 = 固定字符(≤36 字) + 提交日期(YYYYMMDD / YYMM / YYYY) + 自增序号(默认 6 位、1–9 位；重置周期 不重置/按年/按月/按日)；固定字符+日期规则最多 9 条，序号不可删；生成后不可编辑但可搜索；**不能放入明细**；不支持 API 创建 |
| 控件组 | 请假/加班/出差/外出/换班/补卡/录用/转正/调岗/离职 | `leaveGroupV2` `workGroup` `tripGroup` `outGroup` `shiftGroupV2` `remedyGroupV2` `apaascorehr*Group` | 与假勤/人事联动，数据自动回写；多数**不支持 API 创建** |

### 3.3 控件通用属性 📘

| 属性 | 说明 |
|---|---|
| 标题 `name` | ≤256 字符，支持多语言（`@i18n@` key） |
| 提示语 `placeholder` | ≤256 |
| 默认值 | **仅**单行文本、多行文本、单选、联系人、部门支持 |
| 必填 `required` | 默认勾选 |
| 打印 `printable` | 默认勾选（API 定义侧默认 false） |
| 控件 ID | 全局唯一；开发者模式可设 `custom_id`（跨系统对接推荐） |
| 显隐设置 `display_condition` | **仅 单选、多选、数字、金额、计算公式、日期区间 6 类**可作显示条件；被拖入明细后失效；单选/多选接了外部选项后失效；控件组亦可作条件。**条件组内为「且」，条件组间为「或」** |
| 选项关联（联动） | 仅单选控件；表单至少 2 个单选；只能在前置控件上设置；**1 个控件最多 6 项联动、最多 7 级**；选项建议 ≤30 个 |
| 权限 | 流程节点级 `表单权限`：**可读 / 编辑**（审批人、抄送人、办理人节点都有；API 为 `privilege_field.readable/writable`） |
| 格式校验 | 仅见电话（移动/固话）与数字/金额范围；手机号/邮箱/身份证独立校验、**唯一性校验**：⚠️ 未验证（官方提示：身份证/银行卡号超 15 位请改用文本控件） |

### 3.4 明细/表格控件 `fieldList` 📘

- 填写格式 `inputType`：`LIST` 纵向列表 / `FORM` 横向表格
- 打印格式 `printType`：`LIST` / `FORM`
- 内部结构：`value:[{id,name,type,required}]`（子控件数组）
- **不能内嵌**：明细/表格、收款账户、流水号、所有控件组
- 关联审批放入明细后「选择展示字段」置灰；引用多维表格放入明细后不支持批量复制粘贴
- 填写端（桌面）支持按行 **复制 / 删除 / + 添加**；移动端统一纵向填写
- 汇总：条件分支可用字段中包含「明细（数字、金额、计算公式）」，即明细内数值可被聚合用于条件判断；是否存在独立「汇总行/合计」配置项 ⚠️ 未验证

### 3.5 计算公式 📘

- 作用于 **数字、金额** 字段，实时计算
- 公式**不是自由文本**：在弹窗中点击「控件 + 符号 + 数字」拼装
- 结果格式：大写数字、千位分隔符、自定义小数位
- 无「必填」项（只有打印）
- 可用函数清单（四舍五入/取整/条件函数）⚠️ 未验证

### 3.6 表单与流程的联动 📘

**可作条件分支判断依据**（该字段还必须设为**必填**）：
- 默认字段：**提交人**
- 控件：数字、金额、计算公式、单选、多选、日期、日期区间、部门、联系人、地址、明细（数字/金额/计算公式）
- 控件组字段：请假（假期类型、请假时长）、加班（类型、时长、计算方式）、出差（时长、出发地、目的地、交通工具）、外出（类型、时长）、补卡、录用/转正/调岗/离职的指定字段

**可作审批人/抄送人/办理人来源**：
- `表单内联系人`：联系人本人 / 联系人上级 / 联系人部门负责人
- `表单内部门`：部门负责人（指定层级）；抄送还可选「直属部门成员」

> ⚠️ 口径差异：开放平台 FAQ 称可作条件的仅「单选、多选、数字、金额、计算公式、日期区间、明细汇总 + 发起人」，与帮助中心列表不一致，**以帮助中心为准**并留意版本差异。

### 3.7 本实例的表单（实测）✅

```
合同名称          单行文本（请输入）
合同类型          单选（请选择）
合同描述 *        多行文本（必填）
合同内容          多行文本
合同编号          单行文本
合同有效期        日期区间 / 分组：开始时间、结束时间、时长（天）（自动计算）
我方信息          分组标题
  签订公司        单行文本
对方信息          分组标题
  对方单位名称    单行文本
  对方联系方式    单行文本
附件 *            附件（上传项名"盖章备案上传"，必填）
```

观察到的能力：必填 `*` 标记、占位提示、**分组/分节**、日期区间**自动派生「时长（天）」**（计算公式类）、附件支持**自定义上传项名称**。

---

## 4. 流程设计

### 4.1 节点类型 📘 + ✅

流程 = 有序节点序列，固定 开始(提交) → … → 结束。节点类型：

| 节点 | 说明 | 实测面板 ✅ |
|---|---|---|
| 提交/发起节点 | 流程起点；可配提交范围、表单权限、**抄送人** | `基础设置` / `表单权限`、`谁可以提交该审批`、`添加抄送人` |
| 审批节点 | 决策节点 | `设置审批人` / `表单权限` / `操作权限` 三 Tab |
| 抄送节点 | 仅通知，无操作权限 | `抄送人` / `表单权限` 两 Tab |
| 办理人节点 | 业务办理（打款、盖章、归档） | `设置办理人` / `表单权限` / `操作权限` |
| 条件分支 / 并行分支 | 路由 | 条件面板 |
| 结束节点 | 流程终点；可配**抄送人** | — |

📘 其他约束：不支持批量设置所有审批流程；不支持隐藏节点审批人信息；预览时需填完整测试数据才会按分支显示审批人。

### 4.2 审批节点设置面板 ✅（页面原文）

**审批类型**：`人工审批` / `自动通过` / `自动拒绝`；独立开关 `不计入审批效率统计`。

**审批人来源清单**（页面实际给出）：

| 分类 | 取值 |
|---|---|
| 组织架构类 | `审批人`（指定成员/角色/用户组）、`上级`、`部门负责人`、`角色`、`用户组`、`指定成员`、`提交人的 直属部门负责人` |
| 发起人相关 | `提交人自选`、`提交人本人` |
| 流程相关 | `节点审批人`（引用前序节点实际审批人；📘 **不能用于首个节点**） |
| 多级 | `连续多级上级`、`连续多级部门负责人`、`指定审批层级` |
| 表单联动 | `表单内联系人`、`表单内部门` |

📘 `上级 / 部门负责人 / 角色 / 用户组` 4 类依赖飞书管理后台的组织架构配置。

**多人审批方式** 📘：会签（全部同意）/ 或签（任一同意）/ 依次审批 → API `node_type` = `AND` / `OR` / `SEQUENTAL`（依次审批时审批人必须为「发起人自选」）。

**异常兜底（节点级）** ✅ + 📘：

| 场景 | 可选项 |
|---|---|
| `审批人为空时` ✅ | `自动通过` / `指定人员审批` / `转交给审批管理员`（若节点还有其他审批人则由其他人继续） |
| `审批人与提交人为同一人时` ✅ | `由提交人对自己审批` / `自动跳过` / `转交给直属上级审批`（无上级→自动通过）/ `转交给部门负责人审批`（无负责人→自动通过；若提交人即本部门负责人则上溯上一级） |
| `审批人离职` ✅📘 | 已设「审批交接」→ 按交接规则转给新交接人；未设置 → 转**直属上级** → 无上级转**流程管理员** → 均无则不转交；多位流程管理员则转给最新添加的一位 |

**审批人去重** 📘（更多设置，3 档）：
1. 仅审批一次，后续重复节点**自动同意**（默认）
2. 仅针对**连续**审批的节点自动同意
3. 不自动同意，每个节点都要审批

**去重失效场景**（即使配了自动同意也不生效）：表单内容变动（金额 100→1000）、表单权限变动、加签、回退、手动转交、系统转交、手写签名/审批意见由「不需要」变「需要」（反向变化仍自动同意）。

### 4.3 条件分支 ✅ + 📘

面板 ✅：`1 满足以下条件时进入当前分支`、`如何设置条件 ?`（外链帮助）、`添加条件组`、`取消` / `确定`。

📘 规则：
- 点击节点间 `+` → 条件分支；可「添加条件分支」扩展多条
- **最右侧是系统默认的「其他情况」分支**：无需也不能手动配置，只有无法进入其他分支时才走它（防单据卡死）✅ 页面原文：*"如存在未满足其他分支条件的情况，则进入此分支"*
- 逻辑结构：**条件组内 = 且(AND)；条件组之间 = 或(OR)** → `(A AND B) OR (C AND D)`
- 可作条件的字段见 3.6；「提交人属于指定部门」选多个部门时**满足任一**即进入
- 比较符清单 ⚠️ 未验证；分支优先级/求值顺序 ⚠️ 未验证（推测按分支顺序匹配）
- **并行分支**：可插入多条，各分支同时流转；默认所有表单都可进入，也可设条件；最右同样是系统默认「其他情况」

### 4.4 抄送节点 ✅ + 📘

- 两种形式：**系统自动抄送**（后台配置，对该审批所有单据生效）/ **手动抄送**（发起人或审批人在详情页点「抄送」，仅当前单据，流程结束后也可）
- 自动抄送 **3 个挂载点**：流程发起时（发起节点）、流程中间节点、流程结束时（结束节点）
- **抄送时机**（中间节点）：默认勾选 `仅同意时抄送`；取消勾选则同意/拒绝都通知。发起/结束时抄送无此开关
- 抄送人类型 ✅：`上级`、`部门负责人`、`角色`、`用户组`、`指定成员`、`提交人自选`、`提交人本人`、`节点抄送人`、`表单内联系人`、`表单内部门`、`指定层级`、`提交人的 直属上级`
- 人数上限：页面提示 ✅ `抄送的人数最多支持100人以内`；📘 文档未给出该上限
- 组织架构告警 ✅：*"为避免部分员工未设置上级导致流程错误，可前往飞书管理后台检查"*

### 4.5 办理人节点 ✅ + 📘

页面原文 ✅：
> 当流程中某个节点不需要审批，但需要对审批单进行业务办理时，可设置办理人节点，场景如财务打款、处理盖章等。
> 办理人不涉及审批人去重设置，不同节点相同的办理人仍需要执行。
> 若办理人离职，会自动转交给办理人的上级代为处理。

**与审批节点的差异** 📘：

| 维度 | 审批人节点 | 办理人节点 |
|---|---|---|
| 语义 | 同意/拒绝的决策 | 执行业务动作后「提交」通过 |
| 拒绝 | 可拒绝 | **不能拒绝** |
| 去重 | 受审批人去重约束 | **不涉及去重** |
| 离职 | 按交接规则 / 转直属上级 | **自动转交给办理人的上级** |
| 多人方式 | 会签 / 或签 / 依次审批 | 会签 / 或签 / **依次办理** |
| 为空时 | 自动通过 / 指定人员 / 转审批管理员 | **指定人员办理 / 转审批管理员**（无「自动通过」） |
| 批量处理 | 支持 | **不支持** |
| 操作 | 转交/加签/减签/回退/评论/抄送/建群 | 转交（受操作权限控制）、抄送、评论、建群 |

同样可配 `表单权限`（可读/编辑）与 `操作权限`；允许编辑时可先改内容再提交。

### 4.6 节点级「操作权限」📘

允许转交、允许加/减签、允许回退、手写签名、审批意见必填（各有独立帮助文档）。

### 4.7 限时审批 ✅（实测弹窗原文）

飞书当前有两个相关入口：① 本页流程设计里的 `创建限时审批`（单据/流程级）；② 管理后台 `系统配置 → 审批超时规则`（系统级）📘。

`创建限时审批` 弹窗实测配置项：

| 配置 | 可选项（实测） |
|---|---|
| 规则 | `限时规则 1`：流程到达节点后，超…未处理则发送提醒 |
| 执行动作 | `自动提醒` |
| 时间条件 | `流程到达节点后，超 [N] 小时 未处理则发送提醒`（输入框校验范围 **1 至 720**） |
| 节点范围 | `全部节点` / `全部审批节点` / `全部办理节点` / `指定节点` |
| 被提醒人 | `当前审批人/办理人` / `提交人` / `指定成员` |
| 效果预览 | 三种接收人各自的消息卡片示例，文案示例：*请尽快处理"对公支出申请"，提醒原因：流程到达"直属上级"节点 24 小时未处理，自动提醒你（管理员配置规则触发）*，卡片含 申请人/部门/审批事由 + `同意` / `拒绝` / `查看详情` |
| 操作 | `取消` / `保存` |

系统级「审批超时规则」📘 还有：规则名称（≤180 字符）、生效流程（指定 / 所有流程，一条流程只对应一条规则，仅支持飞书审批内创建的流程）、**超时设置**（只提醒、**单据不关闭**）、**超时关闭** + **恢复有效期**；仅对设置之后新提交的审批生效。

### 4.8 自动化 ✅（实测弹窗原文）

- 规则卡片：`流程 1 —— 当...时，将执行…动作`
- 左：`当触发以下条件时 / 在右侧选择触发条件`
- 右：`将执行以下动作 / 在右侧选择执行动作`
- **触发条件（实测枚举）**：`流程通过` / `流程拒绝` / `流程撤回`
- `添加动作`（未选条件时 disabled）
- 操作：`取消` / `保存`
- 📘 另有独立帮助页《管理员设置审批流程自动化》，具体动作清单 ⚠️ 未验证

---

## 5. 更多设置

页面内可见的交叉引用 ✅：
- 审批人去重：*"若同一审批人在流程中重复出现，默认只审批一次。可前往 **更多设置** 修改"*
- 流程设计页可直接创建：`创建限时审批`、`创建自动化`

📘 官方目录中「更多设置」包含：**审批撤销规则、审批修改规则、代他人提交、审批人批量审批、审批人去重、允许审批人撤回、打印模板、审批转发范围**。
（⚠️ 该步骤页面本次未能逐项实测——步骤条元素无法通过当前浏览器桥接点击，清单来自官方文档目录。）

**打印模板** 📘：
- `系统模板`：免费，样式不可自定义；表单控件需在 **②表单设计** 勾选「打印」才会出现（默认已勾选）
- `自定义配置`：第三方应用提供，付费；支持拖拽排版/单元格/公司 Logo 与抬头；需先安装并授权三方应用；打印预览页可切换两种模板
- 两者均可另存为 PDF

---

## 6. 数据模型（开放平台 approval v4）

> 参考价值：这是飞书把「表单 + 流程」序列化成 JSON 的官方口径，可直接借鉴自研的表单/流程 Schema。

### 6.1 创建审批定义

`POST https://open.feishu.cn/open-apis/approval/v4/approvals`（带 `approval_code` 则为全量覆盖更新）

⚠️ 重要限制：该接口**不支持设置条件分支**（必须去后台，URL 加 `?devMode=on`）；通过 API 创建的审批定义**无法在后台或 API 停用/删除**，官方不建议企业自研应用使用。

**顶层字段**：`approval_name`、`approval_code`、`description`、`viewers[]`、`form`、`node_list[]`、`settings`、`config`、`icon`(0–24)、`i18n_resources[]`、`process_manager_ids[]`
- 节点顺序：**START 必须是 `node_list[0]`，END 必须是最后一个**
- `settings`：如 `revert_interval`（撤销时限）
- `config`：`can_update_viewer` / `can_update_form` / `can_update_process` / `can_update_revert` / `help_url`

**官方 JSON 示例（原文）**：

```json
{
  "viewers": [{"viewer_type": "TENANT", "viewer_user_id": ""}],
  "form": {
    "form_content": "[{\"id\":\"111\",\"name\":\"@i18n@event_name\",\"required\":true,\"type\":\"input\"},{\"id\":\"222\",\"name\":\"@i18n@time_interval\",\"required\":true,\"type\":\"dateInterval\",\"value\":{\"format\":\"YYYY-MM-DD hh:mm\",\"intervalAllowModify\":false}},{\"id\":\"333\",\"name\":\"@i18n@event_type\",\"type\":\"radioV2\",\"value\":[{\"key\":\"1\",\"text\":\"@i18n@recurrence_event\"}]}]"
  },
  "node_list": [
    {"id": "START", "privilege_field": {"writable": ["111","222"], "readable": ["111","222"]}},
    {"id": "7106864726566",
     "privilege_field": {"writable": ["111","222"], "readable": ["111","222"]},
     "name": "@i18n@node_name",
     "node_type": "AND",
     "approver": [{"type": "Personal", "user_id": "59a92c4a"}],
     "ccer": [{"type": "Supervisor", "level": "2"}]},
    {"id": "END"}
  ],
  "settings": {"revert_interval": 0},
  "config": {"can_update_viewer": false, "can_update_form": true, "can_update_process": true, "can_update_revert": true, "help_url": "https://www.baidu.com"},
  "icon": 1
}
```

注意：`form.form_content` 是**字符串化的 JSON 数组**（不是嵌套对象）。

### 6.2 widget（表单控件）字段

`id`（定义内唯一）、`custom_id`、`name`(`@i18n@` key)、`type`、`required`、`printable`(默认 false)、`value`、`option`、`visible`、`enable_default_value`、`widget_default_value`、`default_value_type`、`display_condition`、`conditional`、`conditions[]`、`expressions[]`（含 `source_widget` / `compare_type` / `standard_value`）、`children`（明细/控件组子控件）、`options`。

> **值得抄的结构**：`display_condition` + `conditions[]`（AND 组）+ `expressions[]`（OR/表达式）—— 正是「条件组内 AND、条件组间 OR」的字段级建模。

**type 全量枚举**：
`input` `textarea` `number` `amount` `formula` `radio`/`radioV2` `checkbox`/`checkboxV2` `date` `dateInterval` `fieldList` `mutableGroup` `image`/`imageV2` `attachment`/`attachmentV2` `department` `contact` `connect` `address` `location` `account` `telephone` `serialNumber` `document` `text` `leaveGroupV2` `workGroup` `tripGroup` `outGroup` `shiftGroupV2` `remedyGroupV2` `apaascorehrOnboardingGroup` `apaascorehrRegularateGroup` `apaascorehrJobAdjustGroup` `apaascorehrOffboardingGroup`

**关键控件的 value/option 结构**：
- 单选/多选：`value:[{"key":"1","text":"@i18n@..."}]`；外部数据源用 `externalData{externalUrl, token, key, linkageConfigs[{linkageWidgetID,key,value}], externalDataLinkage}`
- 金额：`value` = 币种（CNY/USD/EUR/JPY/CAD/CHF/SGD/AUD/KBW/INR/TWD/HKD/MOP/THB/IDR/PHP/MYR）+ `option{currencyRange[], isCapital, isThousandSeparator, keepDecimalPlaces, maxValue, minValue}`
- 日期：`YYYY-MM-DD` / `YYYY-MM-DD a` / `YYYY-MM-DD hh:mm`；区间 `value{format, intervalAllowModify}`
- 联系人：`value{ignore, multi}`；地址：`value{enableDetailAddress, requiredDetailAddress, preLocating}`
- 电话：`option.availableType` ∈ `MOBILE`/`FIXED_LINE`/`FIXED_LINE_OR_MOBILE`
- 明细：`value:[{id,name,type,required}]` + `option{inputType: LIST|FORM, printType: LIST|FORM}`
- 控件组：`option` 携带业务开关（加班 `{allowInsteadMultiUser, allowMultiTimeRange, isSetRule}`、外出 `{isSetType, defaultUnit, unitMap}`）

### 6.3 node（流程节点）字段

`id`（START / END / 自定义）、`name`（`@i18n@` key）、`node_type`（`AND` / `OR` / `SEQUENTAL`）、`approver[]`、`ccer[]`、`privilege_field{writable[], readable[]}`。
开始/结束节点不需要 `name`、`node_type`、`approver`。

`ApprovalApproverCcer`：`type`、`level`、`user_id`；`level` 用于 `Supervisor`、`SupervisorTopDown`、`DepartmentManager`、`DepartmentManagerTopDown`（例：由下往上三级主管审批 `level = 3`）。⚠️ `type` 完整枚举未获证（仅确认上述 4 个 + 示例 `Personal`）。

### 6.4 实例侧表单值（提单结构）

每控件为 `{id, type, value}`：

| 控件 | 实例值结构 |
|---|---|
| input / textarea | string |
| date | RFC3339 string |
| dateInterval | `{start, end, interval}` |
| radioV2 | 选项 value 字符串 |
| checkboxV2 | 字符串数组 |
| number / amount | float（amount 另带 `currency`） |
| formula | float |
| contact | `{value:[user_id], open_ids:[open_id]}` |
| department | `[{open_id:"od-xxx"}]` |
| fieldList | 二维数组 |
| image / attachment | 文件 code 数组 |
| telephone | `{countryCode, nationalNumber}` |
| address | `[{id:区划ID, detailAddress}]` |
| document | `{token, type}` |
| connect | 实例 code 数组 |

⚠️ API 不支持提单的控件：`text`、`mutableGroup`、`account`、`serialNumber`、`tripGroup` 及各 HR 控件组。
⚠️ 定义侧 API 不支持创建的控件：`formula`、`mutableGroup`、`serialNumber`、`shiftGroupV2`、`leaveGroupV2`、`workGroup`、`tripGroup`、`outGroup`。

---

## 7. 自研落地建议

### 7.1 数据模型（建议）

```
approval_definition   审批定义：code、name、icon、description、group、status、submit_scope、admin_scope、config
approval_version      版本快照：definition_id、version_no、status(设计中|启用中|历史)、form_schema、flow_schema、
                      publisher、published_at、is_active
approval_draft        草稿：definition_id、editor、saved_at、payload
approval_instance     实例：definition_id、version_id(冻结)、submitter、status、form_data、created_at
node_permission       节点 × 字段 × {readable, writable, required}
```

**推荐 Schema 落地为显式 DAG**（不要照抄飞书的「有序 node_list + 条件表达式」）：

```jsonc
// form_schema
{ "groups": [ { "id": "g1", "title": "我方信息", "children": ["f1","f2"] } ],
  "widgets": [ { "id":"f1", "key":"contractName", "type":"input", "title":"合同名称",
                 "required": true, "placeholder":"请输入", "printable": true,
                 "defaultValue": null,
                 "visibleWhen": { "logic":"AND", "conditions":[ {"field":"f2","op":"eq","value":"经营"} ] },
                 "validate": { "maxLength": 256 } } ] }

// flow_schema
{ "nodes": [ {"id":"start","type":"submit","ccers":[]},
             {"id":"n1","type":"approve","approveType":"manual",
              "approvers":[{"source":"DEPT_MANAGER","level":1}],
              "multiMode":"AND", "emptyPolicy":"AUTO_PASS","selfPolicy":"SKIP",
              "permissions":{"readable":["*"],"writable":["f3"]}},
             {"id":"h1","type":"handle","handlers":[]},
             {"id":"end","type":"end","ccers":[]} ],
  "edges": [ {"from":"n1","to":"n2","priority":1,
              "condition":{"logic":"OR","groups":[ {"logic":"AND","conditions":[]} ]}},
             {"from":"n1","to":"n3","priority":999,"isDefault":true} ] }
```

### 7.2 必须实现的「硬骨头」

1. **条件求值器**：条件组嵌套（组内 AND / 组间 OR）、按字段类型比较、**else 兜底分支必须存在且不可删**、分支优先级。
2. **审批人解析器**：把抽象来源（角色/部门负责人/连续多级上级/表单内联系人…）在**运行时**解析为具体人；配套 4 类兜底策略（为空 / 与提交人同人 / 离职 / 交接）。
3. **字段权限矩阵**（节点 × 字段 × 可见/可编辑/必填）：最容易漏、最影响体验的能力。
4. **草稿与版本冻结**：编辑期 = 草稿，发布 = 不可变版本快照；**在途实例冻结在发起时版本**。
5. **去重 + 失效判定**：必须留存「首次审批时的表单内容与权限快照」，才能判断去重是否失效（表单变动、权限变动、加签、回退、转交…）。
6. **办理人节点**：与审批节点分模型（不参与去重、不能拒绝、不改变审批结论、可写回表单）。
7. **限时/超时 + 自动化**：定时扫描 + 规则引擎（触发条件、动作、作用范围、接收人），规则**只对新建实例生效**。
8. **打印模板**：字段级 `printable`，与表单 Schema 同源。

### 7.3 与 Flowable / 若依的映射（参考）

| 飞书概念 | Flowable / BPMN 对应 |
|---|---|
| 提交/发起节点 | StartEvent（或首个 userTask） |
| 审批节点（多人） | UserTask + 多实例（会签/或签）或串行子流程（依次审批） |
| 条件分支 | ExclusiveGateway + 条件表达式，兜底走 `default flow` |
| 并行分支 | ParallelGateway（注意飞书「并行分支」更接近**分支容器 + 可设条件**，语义需自行定义） |
| 抄送节点 | ServiceTask / 自定义节点 + 消息发送 |
| 办理人节点 | UserTask（无审批语义，只完成任务并写回数据） |
| 结束节点 | EndEvent |
| 版本冻结 | 每个 definitionVersion 部署为独立流程定义 |
| 节点字段权限 | 自定义 `node_permission` 表 + 任务监听器回填 |

---

## 8. 功能需求清单（可直接当需求池）

| # | 功能 | 优先级 | 备注 |
|---|---|---|---|
| 1 | 四步向导（基础信息/表单设计/流程设计/更多设置） | P0 | 步骤可跳转，未完成也可保存草稿 |
| 2 | 草稿自动保存 + 打开/放弃草稿 | P0 | 进入时提示上次草稿 |
| 3 | 版本快照 + 启用中/历史 + 发布人/时间 | P0 | 在途实例冻结版本 |
| 4 | 控件面板（拖拽添加、排序、分组） | P0 | 至少：文本/数字/金额/单选/多选/日期/日期区间/附件/部门/联系人/说明 |
| 5 | 控件通用属性（标题/提示/必填/默认值/打印/显隐条件） | P0 | 显隐条件 = 组内 AND / 组间 OR |
| 6 | 计算公式（数字/金额派生字段） | P1 | 飞书为「控件+符号+数字」拼装；可先做表达式式 |
| 7 | 明细/表格控件（含行内字段、不可嵌套限制） | P1 | 支持 LIST/FORM 两种填写与打印形态 |
| 8 | 流水号控件（固定字符 + 日期 + 自增，重置周期） | P1 | 生成后不可编辑、可搜索 |
| 9 | 附件/图片（大小与数量上限、上传项自定义名） | P0 | 实测本单据用到「盖章备案上传」 |
| 10 | 分组/分节标题 | P1 | 实测「我方信息/对方信息」 |
| 11 | 打印模板配置 + 字段级 printable | P1 | 系统模板 + 自定义模板（可后置） |
| 12 | 流程画布（节点 + 分支 + 缩放 + 兜底分支） | P0 | 需支持「添加并行分支」 |
| 13 | 审批节点：审批类型（人工/自动通过/自动拒绝） | P1 | 自动通过/拒绝可先做 |
| 14 | 审批人来源（指定成员/角色/部门负责人/上级/提交人自选/表单内联系人…） | P0 | 分期：先 指定成员+角色+部门负责人+提交人自选 |
| 15 | 多人审批方式（会签/或签/依次） | P0 | — |
| 16 | 异常兜底（为空 / 与提交人同人 / 离职转交） | P0 | 直接决定线上不出错 |
| 17 | 审批人去重（3 档 + 失效场景） | P1 | 需要表单/权限快照支撑 |
| 18 | 条件分支（条件组、AND/OR、其他情况兜底） | P0 | 条件字段必须必填 |
| 19 | 抄送（发起/中间/结束 + 仅同意时抄送 + 手动抄送） | P0 | 人数上限需自定（飞书页面提示 100） |
| 20 | 办理人节点（不参与去重、不能拒绝、可写回数据） | P1 | 用章/打款场景必备 |
| 21 | 节点级表单权限（可读/可编辑/必填） | P0 | — |
| 22 | 节点级操作权限（转交/加减签/回退/签名/意见必填） | P1 | 可分期 |
| 23 | 限时/超时规则（提醒、超时关闭、恢复有效期） | P2 | 先做「超时提醒」 |
| 24 | 自动化（流程通过/拒绝/撤回 → 动作） | P2 | — |
| 25 | 预览（移动端扫码 / 表单调试预览） | P2 | 自研做 Web 预览即可 |
| 26 | 权限：提交范围（全员/指定人员）、流程管理员、禁止管理员改流程 | P1 | — |

---

## 9. 未验证 / 待补项

**页面未能实测**（步骤条元素无法通过浏览器桥接点击）：
- 「2 表单设计」的**字段属性配置面板**逐项细节（校验、默认值、只读/隐藏、打印、权限）
- 「4 更多设置」的全部开关项与默认值

**官方文档未给出依据**（⚠️）：
1. 条件分支的**可用比较符清单**与**分支求值优先级**
2. 抄送人**人数上限**（页面提示 100 人）
3. `ApprovalApproverCcer.type` 完整英文枚举
4. 「审批流程自动化」的触发/动作完整清单
5. 是否存在独立于「流水号控件」的全局审批单编号
6. 计算公式可用函数清单（四舍五入/取整/条件函数）
7. 手机号/邮箱/身份证等格式校验与唯一性校验
8. 「定位」是独立控件还是地址控件属性（页面与文档口径不一致）

---

## 10. 参考资料

**产品帮助中心**
- [管理员设计审批表单](https://www.feishu.cn/hc/zh-CN/articles/360036162633)
- [管理员设计审批流程](https://www.feishu.cn/hc/zh-CN/articles/360036163653)
- [管理员创建审批](https://www.feishu.cn/hc/zh-CN/articles/360040241113)
- [管理员设置条件分支](https://www.feishu.cn/hc/zh-CN/articles/360045139814)
- [管理员设置审批流程并行分支](https://www.feishu.cn/hc/zh-CN/articles/046280562486)
- [管理员设置抄送人](https://www.feishu.cn/hc/zh-CN/articles/360041749473)
- [管理员设置办理人节点](https://www.feishu.cn/hc/zh-CN/articles/144478657626)
- [管理员设置审批人异常流转规则](https://www.feishu.cn/hc/zh-CN/articles/360049067817)
- [管理员设置审批人去重](https://www.feishu.cn/hc/zh-CN/articles/360042706354)
- [管理员设置转交、加减签、回退审批](https://www.feishu.cn/hc/zh-CN/articles/360049067381)
- [管理员设置审批打印模板](https://www.feishu.cn/hc/zh-CN/articles/694466239802)
- [管理员设置审批超时规则](https://www.feishu.cn/hc/zh-CN/articles/852878870528)
- [管理员设置审批流程自动化](https://www.feishu.cn/hc/zh-CN/articles/838424984875)
- [表单内联系人/部门作为审批人](https://www.feishu.cn/hc/zh-CN/articles/348799828525)
- [明细/表格控件](https://www.feishu.cn/hc/zh-CN/articles/492435392036) · [流水号控件](https://www.feishu.cn/hc/zh-CN/articles/321041478512) · [收款账户控件](https://www.feishu.cn/hc/zh-CN/articles/003904111264) · [引用多维表格控件](https://www.feishu.cn/hc/zh-CN/articles/851182577581) · [关联审批控件](https://www.feishu.cn/hc/zh-CN/articles/896720801778) · [选项关联](https://www.feishu.cn/hc/zh-CN/articles/270021758316)
- [飞书审批常见问题](https://www.feishu.cn/hc/zh-CN/articles/360045410394) · [飞书审批管理员手册](https://www.feishu.cn/hc/en-US/articles/360033971554-use-feishu-approval)

**开放平台**
- [原生审批定义概述](https://open.feishu.cn/document/server-docs/approval-v4/approval/overview-of-approval-resources)
- [创建审批定义](https://open.feishu.cn/document/server-docs/approval-v4/approval/create) · [查看审批定义](https://open.feishu.cn/document/server-docs/approval-v4/approval/get)
- [审批定义表单控件参数](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/approval-v4/approval/approval-definition-form-control-parameters)
- [审批实例表单控件参数](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/approval-v4/instance/approval-instance-form-control-parameters)
- [审批常见问题](https://open.feishu.cn/document/server-docs/approval-v4/approval-related-faqs)

> 抓取提示：`feishu.cn/hc` 是 SPA，直接抓取只有标题，可通过 `https://r.jina.ai/<url>` 取正文；`open.feishu.cn` 文档加 `.md?lang=zh-CN` 可直接取全文。
