# RuoYi-Vue-OA 现状勘察（2.0 PRD 事实基线）

> **性质**：只读勘察报告。所有"现状"结论均来自源码核对，证据以 `路径:行号` 给出。
> **仓库简称**：`UI` = `ruoyi-vue-oa-ui-master/src`；`BE` = `ruoyi-vue-oa-master`。
> **与 V1 PRD 的关系**：本文件是 `doc/PRD-合同审批二开.md`（V1.3）的**事实校核层**。凡本文件与 V1 PRD 冲突处，以本文件为准并已标注。
> **状态标识**：**已实现** / **部分实现** / **未实现**，逐条显式标注。
> **勘察时间**：基于工作区当前 HEAD（3 个 git 仓库，基线见 `DEV-ENV.md:242-248`）。
>
> **需求编号约定**（沿用 V1 PRD，`doc/PRD-合同审批二开.md:27-36`）：`REQ-<域>-<三位序号>`，域为
> `DESIGN` / `FLOW` / `FORM` / `PRINT` / `SIGN` / `DATA` / `PERM` / `NFR`；验收项为 `AC-<两位序号>`。
> **本文件不新造任何编号**：V1 已分配的直接引用；V1 未覆盖的 2.0 新诉求统一用 **`【2.0-XX】`** 占位，
> 待 2.0 PRD 正式分配后再映射，避免抢占 `REQ-*` 号段。

---

## 0. 阅读导引：本次勘察最重要的 5 个结论

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | **V1 PRD 的三项需求已全部落地代码**（简化流程设计器 / 表单打印 / 签名组件），2.0 不得重复立项 | `BE/ruoyi-workflow/src/main/java/com/ruoyi/workflow/simple/**`、`.../print/**`、`.../sign/**` |
| 2 | **简化流程与单据模板确实没有任何自动绑定**：`def_key` 靠人手填、无外键、无写回；唯一的关联是 `SimpleFlowServiceImpl#syncNodeFieldAuth` 的**反向查询** | `BE/.../simple/service/impl/SimpleFlowServiceImpl.java:225-238` |
| 3 | **"谁可以提交该审批"（submitScope）在代码里完全不存在**：`selectNewStartTemplateList` 只过滤 `del_flag='0' and enable_flag='1'` | `BE/.../mapper/template/TemplateMapper.xml:148-157`；全仓 grep `submitScope` 零命中（仅出现在 `doc/` 设计稿） |
| 4 | **打印有"两条取数路径 + 一份重复常量"**：内置模板按表单 schema 自动排版，配置模板走 `field_map`；且 `DEFAULT_FIELD_MAP` 后端/前端各有一份 | `UI/views/workflow/print/index.vue:211-219`、`BE/.../print/service/impl/PrintServiceImpl.java:469-501`、`UI/views/workflow/print-template/index.vue:156-173` |
| 5 | **内置打印模板是"全局唯一一套"**：`DEFAULT_TITLE` 单常量、`builtinTemplate()` 不区分单据类型 —— 要新增《集团资金审批单》《集团事项类打印审批单》必须先把"按单据类型选内置模板"这条链路补出来 | `BE/.../print/service/impl/PrintServiceImpl.java:391-402,466` |

---

## 1. 模板 / 表单定义

### 1.1 数据模型（后端 `t_template` 及 5 张子表）

| 表 | 定义位置 | 关键列（列名 / 类型 / 语义） |
| --- | --- | --- |
| `t_template` | `BE/sql/table.sql:2146-2168` | `id` varchar(64) PK；`name` varchar(200) NOT NULL；`type` varchar(50) NOT NULL（**模板分类ID**，非字典）；`def_key` varchar(400) NOT NULL（**流程定义key**）；`form_id` varchar(64)；`form_key` varchar(64)；`form_type` varchar(10)（1动态/2业务/3自定义）；`form_code` varchar(200)；`main_text_flag` char(1)='0'；`attach_flag` char(1)='0'；`message_notice_flag` char(1)='0'；`enable_flag` char(1)='0'；`del_flag` char(1)='0'；`sort` int(11)；`create_id/create_by/create_time/update_id/update_by/update_time` |
| `t_template_type` | `BE/sql/table.sql:2232-2244` | `id` varchar(64) PK；`name` varchar(100) NOT NULL；`sort` int(11)；`enable_flag` char(1)='0'；`del_flag` char(1)='0'；`remark` varchar(400) |
| `t_template_dynamic_form` | `BE/sql/table.sql:2193-2208` | `id` varchar(64) PK；`name` varchar(200)；`content` **longtext**（表单设计器 JSON）；`enable_flag` char(1)='1'；`form_key` varchar(64)；`del_flag` char(1)='0'；`remark` varchar(1000)。**无 version 列** |
| `t_template_attachment` | `BE/sql/table.sql:2174-2187` | `id`；`template_id`；`name`；`limit_size` varchar(50)（M）；`limit_type` varchar(200)；`tip_content` varchar(1000) |
| `t_template_main_text` | `BE/sql/table.sql:2214-2228` | `id`；`template_id`；`type` char(1)='0'（**0-上传 / 1-书签替换**）；`file_id` varchar(64)；`limit_size` int(11)；`limit_type` varchar(200)；`tip_content` varchar(500) |
| `t_template_message_notice` | `BE/sql/table.sql:2251-2261` | `id`；`type` varchar(50)（1短信/2邮件/3其他）；`template_id` NOT NULL；`msg_template` varchar(64) |

**实体类**：`BE/ruoyi-template/src/main/java/com/ruoyi/template/domain/Template.java:23-112`（19 个字段，逐字段与 DDL 对应）。
**DTO**：`BE/.../module/TemplateDTO.java:13-33` —— `extends Template`，仅额外聚合 `attachment` / `dynamicForm` / `mainText` / `messageNotice` 四个对象。
**枚举**：`FormTypeEnum`（`BE/.../enums/FormTypeEnum.java`）、`MainTextTypeEnum`（`.../enums/MainTextTypeEnum.java`）、`MessageNoticeTypeEnum`（`.../enums/MessageNoticeTypeEnum.java`）。

### 1.2 前端「新增模板页」现状：**不是页签，是 5 个纵向分段**

**已实现**。`UI/views/workflow/template/add.vue` 单页顺序渲染 5 段，每段一个 `<el-divider />` 分隔，**没有任何 `el-tabs`**：

| 段序 | 段标题 | 子组件 | 证据 |
| --- | --- | --- | --- |
| 1 | 基本信息 | `./basic-info` | `add.vue:4-9` |
| 2 | 表单配置 | `./business-form-info` | `add.vue:10-16` |
| 3 | 正文配置 | `./main-text` | `add.vue:17-23` |
| 4 | 附件配置 | `./attachment-info` | `add.vue:24-30` |
| 5 | 消息通知配置 | `./message-notice.vue` | `add.vue:31-37` |
| — | `[提交] [返回]` | — | `add.vue:38-43` |

`data()` 里 `activeNames: ["1","2","3"]`、`activeName: "basic"` 是**遗留死字段**（曾用于折叠面板，现已无模板引用）—— `add.vue:66-67`。

### 1.3 每段的字段、控件与校验

| 段 | 字段（prop） | 控件 | 校验规则 | 证据 |
| --- | --- | --- | --- | --- |
| 基本信息 | `name` 模板名称 | `el-input` | `required` 请输入模板名称 | `basic-info.vue:6-8,66` |
| | `type` 模板分类 | `el-select`（数据源 `listEnable()` → `/template/type/listEnable`） | `required` | `basic-info.vue:11-19,59-61,67` |
| | `defKey` **关联流程** | `el-select`（数据源 `getFlowOptionSelect()` → `/flowable/definition/optionSelect`），label=`procDefName`，value=`procDefKey` | `required` 请关联流程 | `basic-info.vue:21-31,55-57,68` |
| | `sort` 排序号 | `el-input type=number` | `required` | `basic-info.vue:32-36,69` |
| 表单配置 | `formType` 表单类型 | `el-select`（**字典** `workflow_template_form_type`） | `required` | `business-form-info.vue:6-13,61` |
| | `formCode` 表单编码 | `el-input`，仅 `formType !== '1'` 时显示 | `required` | `business-form-info.vue:14-20,62`；`formType='1'` 时自动置 `"dynamic"`：`:105-111` |
| | `formId` 关联表单 | `el-select`（数据源 `getDynaFormOptionSelect()`） | `required`，仅 `formType !== '3'` 时显示 | `business-form-info.vue:22-34,63` |
| 正文配置 | `mainTextFlag` | radio 0/1 | `required` | `main-text.vue:5-8,86` |
| | `type` 启用方式 | radio 0=用户直接上传 / 1=动态模板替换 | `required`；并驱动 `descText` 说明文案 | `main-text.vue:10-18,87,145-151` |
| | `limitSize` / `limitType` | input（type=0 时显示） | `limitSize` required | `main-text.vue:19-28,85` |
| | `fileId` 上传模板 | `el-upload`（`/file/operate/uploadfile`，`optData.chunkFlag=false`） | `required`（type=1 时） | `main-text.vue:29-46,77-83,88` |
| | `tipContent` | input | — | `main-text.vue:47-49` |
| 附件配置 | `attachFlag` | radio 0/1 | `required` | `attachment-info.vue:4-7,41` |
| | `name` 附件名称 | input | `required` | `attachment-info.vue:9-11,39` |
| | `limitSize` / `limitType` / `tipContent` | input / input / input | 默认 `limitSize=500`（`add.vue:73`） | `attachment-info.vue:12-22` |
| 消息通知 | `messageNoticeFlag` | radio 0/1 | `required` | `message-notice.vue:5-8,38` |
| | `type` 消息类型 | `el-select`（**字典** `workflow_message_type`） | `required` | `message-notice.vue:10-14,39` |
| | `msgTemplate` | `el-select`（**字典** `workflow_message_template_type`），仅 `type==='1'` | `required` | `message-notice.vue:15-19,40` |

> ⚠ **一处静默坑**：`business-form-info.vue` 的 `rules.fileType`（`:40`）与 `attachment-info.vue` 的 `rules.fileType`（`:40`）都是**不存在的 prop 的校验规则**（两个组件里都没有 `fileType` 字段），是死规则；真正生效的是 `limitType`，而 `limitType` **没有**校验。`attachment-info.vue:40` 的 `fileType` required 永远不会触发。

### 1.4 提交时的数据组装

**已实现**。`add.vue:107-148`：

1. 取 5 个子表单实例引用（`add.vue:108-112`）；
2. `Promise.all` + `getFormPromise` 包装 `form.validate(cb)`，全部为真才继续（`add.vue:113-115,150-156`）；
3. `templateTable = Object.assign({}, basicForm.model, businessForm.model)` —— **基本信息与表单配置合并成一个扁平对象**（`add.vue:116`）；
4. 三个开关型子配置按需挂载：`mainTextFlag === '1' ? mainInfoForm.model : null`（`:118-120`）、`attachFlag === '1' ? ... : null`（`:122-124`）、`messageNoticeFlag === '1' ? ... : null`（`:126-128`）；
5. 无 `id` → `addTemplate`（`POST /template/template`）；有 `id` → `updateTemplate`（`PUT /template/template`）（`add.vue:129-143`）。

回显在 `created()`（`add.vue:82-104`）：`getTemplate(id)` 后把 `res.data` 拆给 5 个子组件的 `info`，并把 `res.data.dynamicForm` 直接透传给 `businessForm`（`:100`）。

### 1.5 模板的「更新 = 换 ID」语义（2.0 必须知道）

**已实现，且是 2.0 最大的隐性依赖**。`BE/.../service/impl/TemplateServiceImpl.java:154-176`：

```
updateTemplate():
  template.setEnableFlag(WhetherStatus.NO.getCode());   // 旧行 enable_flag = '0'
  template.setDelFlag(Constants.YES_VALUE);             // 旧行 del_flag   = '1'
  templateMapper.updateTemplate(template);              // 旧行被逻辑删除
  newTemplate.setId(IdUtils.fastSimpleUUID());          // 新行换新 UUID
  ...
  return templateMapper.insertTemplate(newTemplate);    // 插一条全新行
```

**推论（每条都影响 2.0 设计）**：
- 模板**没有稳定的业务主键**，`t_template.id` 每次编辑都变；
- 任何以 `template_id` 为外键的表（`t_template_print_template.template_id`、`t_template_node_field_auth.template_id`）**在模板被编辑后即失联**；
- 前端编辑页拿的 `id` 是**已废弃行**的 id，提交后返回的新 id 前端并不知道（`addTemplate/updateTemplate` 的响应未取新 id，`add.vue:130-142` 只判断 `res.code === 200` 就关页面）；
- `DEV-ENV.md:183-185` 把这个语义记在**动态表单**上（旧行 `enable_flag=0` + 新 id），`t_template` 上是**更强的版本**（还额外 `del_flag='1'`）。

### 1.6 模板分类从哪来

**已实现**。`t_template_type` 表（`BE/sql/table.sql:2232-2244`）；前端下拉 `listEnable()` → `GET /template/type/listEnable`（`UI/api/workflow/template.js:81-86`）；后台在 `GET /template/type/list` 等 CRUD（`UI/api/workflow/template.js:71-129`）+ 独立页面 `UI/views/workflow/template/type/index.vue`（251 行）。后端 `TemplateTypeController`（`@RequestMapping("/template/type")`，`BE/ruoyi-template/.../controller/TemplateTypeController.java:31`）。

`listTemplate` 与 `listNewStartTemplate` 都会用 `t_template.type` 回填 `typeName`：`TemplateServiceImpl.java:117-122`、`:233-243`。

`def_key` 的**手选下拉**（2.0 的关键现状）：`getFlowOptionSelect` → `GET /flowable/definition/optionSelect` → `FlowDefinitionController.java:189-192` → `flowDefinitionService.getOptionSelect()`，即**列出所有已发布流程定义**（含简化设计器发布出来的那些，因为它们走同一条部署链路）。

### 1.7 【2.0】"拆成四个页签"的改动面（已核到文件与行）

**未实现（现状是 5 段纵向）**。

| 改动点 | 文件:行 | 具体动作 | 量 |
| --- | --- | --- | --- |
| 页签容器 | `UI/views/workflow/template/add.vue:1-45` | 把 5 个 `<div>` 段包进 `<el-tabs v-model="activeTab">` + 5 个 `<el-tab-pane>`；把 `activeNames/activeName` 死字段删掉（`:66-67`） | S |
| 校验时机 | `UI/views/workflow/template/add.vue:108-115` | 现在**一次性校验全部 5 个表单**（`Promise.all`），隐藏页签里的字段校验失败会看不到错误源 → 需改成"逐页签校验 + 失败时自动切到该页签" | S |
| 页签 1 基本信息 | `basic-info.vue` 整文件 | 无需拆，直接搬 | S |
| 页签 2 表单配置 | `business-form-info.vue` 整文件 | 无需拆；建议顺手删死规则 `rules.fileCode/fileType`（`:62`） | S |
| 页签 3 流程配置 | **不存在** | 需新建页签组件（承载简化流程设计器嵌入 or `defKey` 下拉） | **L** |
| 页签 4 其他配置（正文/附件/消息） | `main-text.vue` + `attachment-info.vue` + `message-notice.vue` | 三选一：合成一个页签（内部再分段）或拆成 4/5 页签；若合成，需新建一个壳组件承接 3 个子表单的 `validate` | M |
| 返回路由 | `add.vue:158-164` | 保持 `/workflow/template/form/config` 不变即可 | S |

**后端字段缺口（1.7 的核心结论）**——若页签 3 要承载"流程配置"，现有 `t_template` / `Template.java` / `TemplateDTO.java` **没有任何字段**可以表达：

| 缺什么 | 为什么需要 | 落点 |
| --- | --- | --- |
| `flow_mode char(1)`（0-简化 / 1-BPMN 高级） | 页签 3 要知道"这条流程该用哪个设计器打开"；V1 PRD 9.2 已规划该列但**代码里没有** | `BE/sql/table.sql:2146-2168` 增列 + `Template.java:46` 附近增字段 + `TemplateMapper.xml:7-28` resultMap、`:31` select 列、`:57-103` insert、`:105-129` update **五处同步** |
| `simple_flow_id varchar(64)`（指向 `t_flow_simple.id`） | 让"模板 → 简化流程定义"成为**强引用**，而不是靠 `def_key` 字符串巧合；支持"未发布的草稿流程挂在模板上" | 同上表 + 新建关联查询；`def_key` 在草稿态还可能为空，与 `def_key NOT NULL`（`:2150`）冲突 → 需一并放宽或约定草稿期写临时 key |
| `print_tpl_id varchar(64)` | V1 PRD 9.2 已规划"默认打印模板"，代码里**未落地**（现由 `t_template_print_template.enable_flag` 反查，见 §5.3） | 同上表 + `PrintServiceImpl.getEffectiveTemplate`（`:310-324`）改为优先读该列 |
| 模板更新语义 | 1.5 的"换 ID"会让 `simple_flow_id` / `print_tpl_id` 在新行上丢失 | `TemplateServiceImpl.updateTemplate`（`:154-176`）必须在 insert 前把关联字段复制到 `newTemplate` |

---

## 2. 表单设计器

### 2.1 结论：**自研 form-generator 风格，vform 是死代码**

- **设计器本体是自研的**：`UI/views/tool/build/index.vue`（532 行，`vuedraggable` 拖拽 + 右侧属性面板 + JSON/代码抽屉），渲染共用 `UI/components/render/render.js`。
- **vform 是第三方 UMD，被全局注册但全库零使用**：
  - 注册：`UI/main.js:48-50` `import vform from '@/components/vform/VFormDesigner.umd.min.js'` + `import '@/components/vform/VFormDesigner.css'`，`UI/main.js:81` `Vue.use(vform)`；
  - 注释自述"同时注册了 v-form-designer、v-form-render 等组件"（`UI/main.js:80`）；
  - 资产：`UI/components/vform/VFormDesigner.umd.min.js` 1,364,549 字节 + `VFormDesigner.css` 90,147 字节；
  - **全仓 grep `VFormDesigner|v-form-designer|vform-designer` 仅命中该 UMD 自身与 `main.js`**，**没有任何 `.vue` 模板使用 `<v-form-designer>`**。
  - **判定：vform = 死代码（已实现注册、未实现使用）**。2.0 若考虑换设计器，删掉 `main.js:48-50,81` 即可，无功能回归；反过来若要"沿用 vform"，则现有 6 个二开控件与两条渲染路径全部要重做，**不建议**。

### 2.2 控件注册方式：**必须同时改三处（+ 2 处可选）**

**已实现**，且代码里已把这条规矩写成注释：`UI/components/render/render.js:140-144`

```
// ⚠ 新增自定义控件必须同时登记三处，少一处就会出现"设计器里能拖、运行时白屏"：
//   1. 这里（运行时渲染 + 设计器画布共用同一个 render 组件）
//   2. utils/generator/config.js 的组件清单（左侧可拖拽列表）
//   3. 后端 ComponentTypeEnum（值转换时的类型判定，否则落 UNKNOWN 并打告警）
```

**新增一个控件的完整改动清单（列表化，逐条给落点）**：

| # | 动作 | 文件:行 | 必需性 |
| --- | --- | --- | --- |
| 1 | 新建控件组件 `UI/src/components/form/design/DesignXxx.vue` | 参照 `DesignAmount.vue`（140 行） | **必需** |
| 2 | `render.js` 顶部 `import` | `render.js:2-8` | **必需** |
| 3 | `render.js` 的 `components: {}` 登记 | `render.js:138-145`（当前一行 7 个：`DesignDeptSelect, DesignUserSelect, DesignSerialNo, DesignSection, DesignText, DesignAmount, DesignCalc`） | **必需** |
| 4 | `utils/generator/config.js` 组件清单 | `config.js:731-795+` 的 `formOaComponents` 数组（金额 `:735`、时长 `:756`、分组标题 `:775`、说明文字 `:793`） | **必需** |
| 5 | `views/tool/build/index.vue` 左侧分组登记 | `index.vue:125`（import）与 `:202-205`（`{title:"合同二开组件", list: formOaComponents}`） | **必需**（若不新增分组、只往 `formOaComponents` 追加则免） |
| 6 | `views/tool/build/RightPanel.vue` 属性面板 | `RightPanel.vue:43-63`（`design-calc`）、`:66-81`（`design-amount`）、`:82-90`（`design-section`）、`:91-95`（`design-text`）、`:470-490`（`design-user-select`/`design-dept-select`） | **必需**（否则拖进去不能配属性） |
| 7 | `views/workflow/flow-form/component/view-form.vue` 只读视图适配 | `view-form.vue:9-14`（排版类）、`:20-61`（各类值渲染）、`:196-213`（值转换 switch） | **必需**（否则审批页/详情页看不见） |
| 8 | 后端 `ComponentTypeEnum` 补分支 | `BE/ruoyi-biz-sdk/.../enums/ComponentTypeEnum.java:8-27` | **必需**（否则 `BizFormServiceImpl.java:296-298` 落 `default` 并 `log.warn("未支持的组件类型: {}")`） |
| 9 | `BizFormServiceImpl.convertValueToLabel` 的 switch 补 case | `BizFormServiceImpl.java:260-299`（`AMOUNT/CALC/SIGNATURE` 显式透传在 `:291-295`） | 视情况 |
| 10 | 代码生成器（若要用"导出代码"） | `UI/utils/generator/html.js:273-285`（`design-user-select`/`design-dept-select` 的段落生成） | 视情况 |
| 11 | 打印件排版识别 | `UI/views/workflow/print/index.vue:583-587`（`isLayoutOnly`：`['design-section','design-text','el-button','el-divider']`）、`:449`（`design-amount` 大写） | 视情况 |

### 2.3 现有 6 个二开控件的落地完整度（逐处核验）

| 控件 | tag | 组件文件 | render.js | config.js | RightPanel | view-form | ComponentTypeEnum | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 金额 | `design-amount` | `DesignAmount.vue` | `render.js:7,144` | `config.js:735` | `RightPanel.vue:66,69,79` | `view-form.vue:196` | `ComponentTypeEnum.java:18` | **已实现** |
| 只读计算（时长天） | `design-calc` | `DesignCalc.vue` | `render.js:8,144` | `config.js:756` | `RightPanel.vue:43,49,54,59,62` | `view-form.vue:205` | `ComponentTypeEnum.java:20` | **已实现** |
| 分组标题 | `design-section` | `DesignSection.vue` | `render.js:5,144` | `config.js:775` | `RightPanel.vue:33,82,85,88` | `view-form.vue:9` | `ComponentTypeEnum.java:24` | **已实现** |
| 说明文字 | `design-text` | `DesignText.vue` | `render.js:6,144` | `config.js:793` | `RightPanel.vue:33,91,94` | `view-form.vue:14` | `ComponentTypeEnum.java:26` | **已实现** |
| 选部门 | `design-dept-select` | `DesignDeptSelect.vue` | `render.js:3,144` | `config.js:675` | `RightPanel.vue:470,477,485` | `view-form.vue:52` | `ComponentTypeEnum.java:15` | **已实现** |
| 选人 | `design-user-select` | `DesignUserSelect.vue` | `render.js:2,144` | `config.js:652` | `RightPanel.vue:470,485` | `view-form.vue:42` | `ComponentTypeEnum.java:16` | **已实现** |
| （附加）编号 | `design-serial-no` | `components/SerialNo` | `render.js:4,144` | `config.js:701` | 未查 | 未查 | — | **部分实现**（未逐处核验，标"未验证"） |
| （附加）表单内嵌签名 | `design-signature` | **无组件** | **无** | **无** | **无** | **无** | `ComponentTypeEnum.java:22` **已登记** | **未实现**（仅枚举占位，V1 PRD 9.3 列为 P1） |

> **旁证**：`Ui/components/form/FormUserSelect.vue`、`FormDeptSelect.vue` 是**另一套同名能力的旧组件**（与 `components/form/design/DesignUserSelect.vue`、`DesignDeptSelect.vue` **并存**）。这是 §2.5「重复逻辑」的第一例。

### 2.4 表单 schema 的存储结构

- **存哪**：`t_template_dynamic_form.content`，**longtext**（`BE/sql/table.sql:2196`，"表单内容"）。
- **写入口**：`UI/views/tool/build/index.vue:492` `this.form.content = JSON.stringify(this.formData)` → `:516` `updateDynamicForm` / `:520` `addDynamicForm`（`UI/api/workflow/dynamicForm.js`）。
- **形状**（`DEV-ENV.md:342-349`、`UI/views/workflow/print/index.vue:476-486`、`BE/ruoyi-biz-sdk/.../constants/FormConstants.java:8-31`）：

```jsonc
// t_template_dynamic_form.content  ≈ formConf + fields
{ "formRef": "...", "formModel": "formData", "labelWidth": 100, "fields": [
    { "__config__": { "label": "合同金额", "tag": "design-amount", "span": 24,
                      "required": true, "showLabel": true, "defaultValue": null },
      "__vModel__": "amount",
      "decimals": 2, "currency": "CNY", "showUpper": true },
    { "__config__": { "label": "合同类型", "tag": "el-select" },
      "__vModel__": "contractType",
      "__slot__": { "options": [ {"label":"经营","value":"jy"}, {"label":"经济","value":"jj"} ] } }
] }
```

- **`__vModel__` 语义**：字段的**变量名**，是 `valData` / 流程变量 / 打印 `field_map` / 条件表达式 / 节点只读字段配置里**唯一的字段标识**。证据链：
  - 前端 `render.js:84-85` —— `key === '__vModel__'` 时调 `vModel()` 建立 `value`/`on.input` 绑定；
  - 后端 `BizFormServiceImpl.java:251` `String vModel = field.getString(FormConstants.V_MODEL_FIELD)`，随后 `valData.put(vModel, ...)`（`:294,316,425`）；
  - 节点只读字段配置按 `field_vmodel` 存（`BE/sql/二开-节点字段权限.sql:17`）；
  - 打印 `field_map.cells[].field` 用 `__vModel__`（`PrintServiceImpl.java:477-482`）。
- **选项标签位置（重要坑）**：在 `__slot__.options`，**不在 `__config__`**。
  - 后端取值：`BizFormServiceImpl.java:312` `field.getJSONObject(FormConstants.SLOT_FIELD).getJSONArray(FormConstants.OPTIONS_FIELD)`（常量见 `FormConstants.java:15,18`）；
  - 前端打印取值：`print/index.vue:564` `const opts = f.__slot__ && f.__slot__.options`；
  - 打印页注释明确写下这条：`print/index.vue:143-146`。
- **值转换（后端做的）**：提交时 `convertValueToLabel`（`BizFormServiceImpl.java:245-305`）把 `el-select/el-cascader/el-radio-group/el-checkbox-group/el-date-picker(daterange)/el-time-picker(is-range)` 的**选项值翻成中文 label**，再 `runtimeService.setVariables(procInstId, valData)`（`:498`）写成**流程变量**。
  - `AMOUNT/CALC/SIGNATURE` 被**显式列出并透传**（`:291-295`），注释说明理由："金额的千分位与中文大写只留一处实现（前端 `utils/money.js`），否则前后端各算一遍迟早不一致（AC-36）"。

### 2.5 ⚠ 两条渲染路径 + 全部重复逻辑（2.0 的雷区清单）

**DEV-ENV.md §4.1.1（`:159-185`）已定型的「表单控件改动的固定动作」摘要**：

> 表单有**两条渲染路径**，新增/改控件**必须两条都适配**，否则"拟稿时好看、审批时难看或丢字段"：

| 场景 | 渲染器 | 位置 |
| --- | --- | --- |
| 拟稿 / 发起（`pageType=0`） | `Parser` + 自定义控件 | `UI/src/components/parser/Parser.vue`、`components/render/render.js` |
| 审批 / 详情（`pageType!=0`） | 只读 `label:value` 视图 | `UI/src/views/workflow/flow-form/component/view-form.vue` |
| 设计器画布 | 与拟稿页**同一个** `render` 组件 | `UI/src/views/tool/build/*` |
| 打印件 | 按单据快照里的 `formData.fields` 排版 | `UI/src/views/workflow/print/index.vue` |

**我在源码中逐条复核了这段手册，结论：准确，且比手册写的更细。**

**路径切换的确切判据（已核到行）**：`UI/views/workflow/flow-form/index.vue:21-22`

```html
<ViewForm v-if="isDetail" :formConf="formConf" :formData="valData" />
<Parser  v-else :key="parserkey" :form-conf="formConf" :val-data="valData" ... />
```

其中 `isDetail` 的定义在 `:206-207`：`pageType: this.$route.query.pageType`、`isDetail: this.$route.query.pageType === "0" ? false : true`。
即 **`pageType=0`（拟稿）→ `Parser`；`pageType=1`（审批）、`pageType=2`（查看）→ `ViewForm`**。

**路径链**：
- `Parser` 自身**不渲染控件**，它只是外壳 —— `Parser.vue:3` `import render from "../render/render.js"`、`Parser.vue:196-198` `components: { render }`、`Parser.vue:83` 把 `disabled` 传给 `render`。所以**拟稿页与设计器画布共用同一个 render**（设计器侧：`views/tool/build/index.vue:121,150` 与 `DraggableItem.vue:3`）。
- `ViewForm` 是**完全独立的一套手写 `v-if/v-else-if` 链**（`view-form.vue:9-61`），与 `render.js` **没有任何代码复用**。

**全部重复/并行逻辑清单（每条都是 2.0 的独立地雷）**：

| # | 重复点 | A 处 | B 处 | 后果 |
| --- | --- | --- | --- | --- |
| D-1 | **控件渲染** | `render.js:138-145` 组件注册表 | `view-form.vue:9-61` 手写 `v-if` 链 | 新控件只注册 A → 审批页看不见 |
| D-2 | **值→中文翻译** | 后端 `BizFormServiceImpl.convertValueToLabel:245-305`（写流程变量） | 前端打印页 `print/index.vue:558-581 buildTranslateMaps`（读 `__slot__.options` + `el-switch` 的 `active/inactive-text`） | 两处口径漂移 → 打印件与流程变量不一致 |
| D-3 | **金额大写** | 前端 `utils/money.js` 的 `amountWithUpper`（被 `DesignAmount.vue` 与 `print/index.vue:119,451` 共用） | **后端不做**（`BizFormServiceImpl.java:291-295` 刻意透传） | 这是**有意的单点实现**，是正面例子；**2.0 不要在后端补第二份** |
| D-4 | **内置打印版式常量** | 后端 `PrintServiceImpl.DEFAULT_FIELD_MAP`（`:469-501`） | 前端 `print-template/index.vue:156-173` 的同名常量 | 两处注释都写了"改一处要一起改"（`print-template/index.vue:152-154`），**没有自动化守卫** |
| D-5 | **选人/选部门控件** | `components/form/design/DesignUserSelect.vue`、`DesignDeptSelect.vue`（二开） | `components/form/FormUserSelect.vue`、`FormDeptSelect.vue`（旧） | 同名能力两套并存 |
| D-6 | **表单字段元数据提取** | `UI/utils/formSchema.js:27 extractFormFields`（供设计器：必填/多选/可作条件） | `print/index.vue:537-547` 自己再解析一遍 `payload.formData.fields` | 两处对"哪些字段算字段"的判定可能不同 |
| D-7 | **签批栏排序/归并** | 后端 `PrintServiceImpl.buildNodes:225-268`（按 `createTime` 排 + `nodeIndex` 归并） | 前端 `print/index.vue:229-244 signBlocks`（按 `nodeIndex` 分组，缺则退化为"相邻同名合并"） | 双份归并逻辑，注释各自记录了不同的实测坑 |
| D-8 | **图片取值口径** | `print/index.vue:344-362 loadSignImages`（`/` 开头直连、否则 blob） | `SignaturePad/index.vue:321` 附近 + `mySign.vue` | 注释自称"与我的签名页同一套取图口径"，是**复制**而非抽取 |
| D-9 | **`formOaComponents` 的"页签/表单配置"语义** | `config.js:722-730` 注释说"两个都是纯排版控件" | 实际数组里有 **4** 个（金额/时长/分组标题/说明文字） | 注释过期，说明该文件被反复追加而缺少整理 |

### 2.6 动态表单定义的版本化（已实现）

`BE/.../service/impl/TemplateDynamicFormServiceImpl.java:79-96`：

```
updateTemplateDynamicForm():
  templateDynamicForm.setEnableFlag(WhetherStatus.NO.getCode());   // 旧行 enable_flag='0'
  templateDynamicFormMapper.updateTemplateDynamicForm(...);
  newDynamicForm.setId(IdUtils.fastSimpleUUID());                  // 新行新 id
  return templateDynamicFormMapper.insertTemplateDynamicForm(newDynamicForm);
```

列表查询过滤：`TemplateDynamicFormServiceImpl.java:49-53` 强制 `enableFlag='1' and delFlag='0'`。
`BE/.../mapper/template/TemplateDynamicFormMapper.xml` 的 `selectTemplateDynamicFormList` 只按 `name/enableFlag/delFlag` 过滤。

**与 `t_template.form_id` 的关系（重要）**：`t_template.form_id` 指向**某一版**表单行（`TemplateServiceImpl.java:80-83` 只在 `formType=1` 时按该 id 取 `getTemplateDynamicFormById`）。表单被编辑后**模板不会自动跟随**（`DEV-ENV.md:185`）。前端已有一个补丁：`business-form-info.vue:89-103 handleOldDynamicOption` —— 若 `dynamicForm.id` 不在下拉里，就按 `formKey` 找当前版并显示为 **`原名 + "(旧版本)"`**（`:98`）。

---

## 3. 流程设计（简化流程设计器）

### 3.1 现状总览：**已实现**（V1 PRD 第 6 章 P0 的主体）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 数据表 | **已实现** | `BE/sql/二开-简化流程设计器.sql:7-48` |
| 实体/Mapper | **已实现** | `BE/.../domain/FlowSimple.java`、`FlowSimpleHistory.java`、`mapper/FlowSimpleMapper.java`、`FlowSimpleHistoryMapper.java` + XML |
| 设计器 JSON 模型 | **已实现** | `BE/.../simple/model/SimpleFlowDef.java`（207 行） |
| 编译器 JSON→BPMN | **已实现** | `BE/.../simple/compile/SimpleFlowCompiler.java`（761/805 行） |
| 发布校验 V-0..V-12 | **已实现** | `BE/.../simple/validate/SimpleFlowValidator.java`（337 行） |
| 前端设计器（节点清单 + 泳道树） | **已实现** | `UI/views/workflow/simple-flow/{index,designer,FlowTree,ConditionEditor}.vue`（270 / 1367 / 646 / 233 行） |
| 前端 API | **已实现** | `UI/api/workflow/simpleFlow.js`（8 个函数） |
| 与 `t_template.def_key` 自动绑定 | **未实现** | 见 §3.6 |

### 3.2 数据模型：实际表名与字段

**表名就是 V1 PRD 规划的 `t_flow_simple` / `t_flow_simple_history`**，DDL 在 `BE/sql/二开-简化流程设计器.sql`：

`t_flow_simple`（`:7-29`）：

| 列 | 类型 | 注释 |
| --- | --- | --- |
| `id` | varchar(64) PK | 主键 |
| `def_key` | varchar(100) **NOT NULL** | 流程定义key（部署到 Flowable 的 process id） |
| `name` | varchar(200) NOT NULL | 流程名称 |
| `category` | varchar(50) | 流程分类 |
| `content` | **longtext** | 简化流程 JSON（设计器产出） |
| `schema_version` | int(11) DEFAULT 1 | JSON 结构版本 |
| `status` | char(1) DEFAULT '0' | 0-草稿，1-已发布 |
| `version` | int(11) DEFAULT 0 | 已发布版本号（0=从未发布） |
| `deploy_id` | varchar(64) | 最近一次部署ID |
| `proc_def_id` | varchar(64) | 最近一次流程定义ID |
| `remark` | varchar(1000) | 备注 |
| `del_flag` | char(1) DEFAULT '0' | 删除标识 |
| `create_id/create_by/create_time/update_id/update_by/update_time` | | |
| 索引 | | `KEY idx_def_key (def_key, del_flag)` |

`t_flow_simple_history`（`:31-48`）：`id`、`def_key`、`version`、`name`、`category`、`content`(longtext)、`schema_version`、`deploy_id`、`proc_def_id`、`publisher_id`、`publisher_name`、`publish_time`、`remark`；`UNIQUE KEY uk_def_key_version (def_key, version)`；表注释"只追加"。

**✅ 与 V1 PRD 9.1 完全一致**（`doc/PRD-合同审批二开.md:1022-1023`），无缺列、无改名列。

**⚠ 但这两张表不在 `sql/table.sql` 里**：全表扫描 `BE/sql/table.sql` 对 `t_flow_simple` **零命中**。即 2.0 的"新表 DDL 放哪"需先解决 §8 的惯例分歧。

**实体 ↔ 列映射**：`BE/ruoyi-workflow/src/main/resources/mapper/workflow/FlowSimpleMapper.xml:7-33`（resultMap + `selectFlowSimpleVo` 完整列出 17 列）。

### 3.3 简化流程 JSON 模型（`content` 的结构）

**已实现**。模型定义 `BE/.../simple/model/SimpleFlowDef.java`；文档级 JSON 示例写在类注释 `:15-40`。

```
SimpleFlowDef { schemaVersion(int), key, name, category, nodes[] }
Node {
  id, type, name,
  assignee: Assignee, multiMode, sameUserPolicy,
  signMode, signTypes[],
  buttons[], fieldReadonly[], timeoutHours,
  emptyPolicy, selfPolicy, leftPolicy,
  branches[]: Branch,                     // condition
  branchSource, formField, branchAssignee, maxBranches, joinMode,   // parallel
  ccers[]: Assignee                       // cc
}
Assignee { source, userIds[], roleIds[], deptScope, deptId, level }
Branch   { id, name, defaultBranch(bool), groups[]: Group, nodes[]: Node }
Group    { logic="AND", rows[]: Row }
Row      { field, op, value, value2 }
```

**枚举常量（`:67-101`）**：
- 节点类型：`start / approve / handle / cc / condition / parallel / end`（`:67-73`）；
- 参与人来源：`USER / ROLE / DEPT_LEADER / LEADER / INITIATOR / INITIATOR_SELECT / PREV_APPROVER`（`:76-82`）；
- 多人方式：`SINGLE / AND / OR / SEQ`（`:85-88`）；
- 同一人策略：`EACH_TIME / AUTO_PASS`（`:91-92`）；
- 合流：`ALL / ANY`（`:95-96`）；
- 并行来源：`FORM_MULTI`（`:99`）、分支参与人 `BRANCH_VALUE`（`:101`）；
- 比较符（`Row.op` 注释 `:202`）：`EQ NE GT GE LT LE IN NOT_IN CONTAINS NOT_CONTAINS EMPTY NOT_EMPTY BETWEEN`。

**对照 V1 PRD 6.3 的节点配置项清单**：名称/参与人/多人方式/签名策略/可用按钮/字段只读/超时/异常兜底 **全部有字段**。其中 `fieldReadonly` 只实现了 V1 的"P0 只做只读"档（`REQ-DESIGN-006`），`required/hidden` 字段在 `t_template_node_field_auth` 表里**有列但没有写入方**（见 §3.7）。

### 3.4 编译：JSON → BPMN 的映射与硬约束

**已实现**，且**关键硬约束已被正确遵守**。

`SimpleFlowCompiler` 的类注释 `:29-35` 原文：

> - **条件分支一律编译为 `exclusiveGateway`**，条件写在网关的出口顺序流上，「其他情况」编译为该网关的 `default` 流。
> - **禁止把条件直接挂在 `userTask` 的多条出口顺序流上**——BPMN 原生语义下多条条件为真会……

**代码证据**：
- 网关元素生成：`SimpleFlowCompiler.java:287-289` `newElem(..., "gateway", ...)`；
- 分支条件写在**网关出口流**上：`:326` `e = addEdge(gw.id, first, "${" + toExpression(b) + "}")`；
- 兜底分支 = 网关 `default` 属性：`:320-323` `e = addEdge(gw.id, first, null); gw.defaultFlowId = e.id;`；
- XML 输出 `default="..."`：`:464-468`；
- `conditionExpression` 以 `CDATA` 包住：`:486-487`；
- 兜底分支缺失时**编译期直接抛错**（不只靠校验器）：`:282-284`；多兜底也抛：`:276-278`。

**产物元素**：`<process id="{def.key}">`（`:435-436`）、`startEvent`（`:456-458`）、`endEvent`（`:460-462`）、`exclusiveGateway`（`:464`）、`userTask`（`:501`）、`multiInstanceLoopCharacteristics`（`:605-615`）、`extensionElements/flowable:properties`（`:654-660`）、`bpmndi` 图形（`:664-690`）。

**审批人绑定约定**（`:503-567`）：
- `flowable:assignee` 优先；多实例时用元素变量 `flowable:assignee="${assignee}"`（`:557-561`，常量 `MI_ELEMENT_VAR="assignee"` `:71`）；
- 否则 `flowable:candidateUsers`（`:567`）；
- 发起人：`${initiator}`（`:536`，`ProcessConstants.PROCESS_INITIATOR`）；
- 动态选人：`${<节点id>_user}`（`:543`，`PROCESS_HANDLER_SUFFIX`）——**这正是 `DEV-ENV.md:266-269` 记录的"首节点不支持按角色/动态选人"的成因**；
- 并行会审（集合来自表单字段）：`flowable:assignee="${dept}"` + `flowable:userType=...`（`:227`）。

**节点扩展属性**（`:636-660`）：`signMode`（`:636`）、`signTypes`、`buttons`（`:640-642`）、`fieldReadonly`（`:643-645`）、`timeoutHours`（`:646-648`）、`sameUserPolicy`/`emptyPolicy`/`selfPolicy`/`leftPolicy`（`:649-652`）。

**条件表达式生成**（`:694-699` 注释）：组内 `&&`、组间 `||`，**整句必须包进一对 `${}`**，否则 EL 把字面量尾巴当字符串 → 运行期报错。

**分支内节点限制**：只支持审批/办理节点，其它类型抛 `UnsupportedOperationException`（`:305-308`）。

### 3.5 发布链路与 defKey 生成

**已实现**：`SimpleFlowServiceImpl.publish(id, remark)`（`:141-217`），**7 步**：

| 步 | 动作 | 行 |
| --- | --- | --- |
| 1 | `SimpleFlowValidator.validate(def, null, null)`，有 BLOCK 即抛 `ServiceException("发布校验未通过：…")`（最多拼 5 条） | `:147-156` |
| 2 | `SimpleFlowCompiler.compile(def)` → BPMN XML | `:158-164` |
| 3 | **复用现有部署链路** `flowDefinitionService.saveFile(name, category, xml)` | `:166-171` |
| 4 | 反查本次部署的 `ProcessDefinition`（`processDefinitionKey + latestVersion + singleResult`）取 `deployId/procDefId` | `:173-179` |
| 5 | `historyMapper.selectMaxVersion(defKey)` → `nextVersion = max+1`，写 `t_flow_simple_history` 快照 | `:181-198` |
| 6 | 回写主表 `t_flow_simple`：`status='1'`、`version`、`deployId`、`procDefId` | `:200-209` |
| 7 | `syncNodeFieldAuth(def)` 落节点字段权限 | `:211-214` |

- **事务边界**：`publish` 带 `@Transactional(rollbackFor = Exception.class)`（`:142`）。**注意**：Flowable 的部署走的是引擎自身的命令/事务上下文，与 Spring 业务事务**共用一个连接还是各自提交取决于引擎配置**；代码未做补偿。若第 5~7 步失败，可能出现"引擎里已部署、`t_flow_simple` 没更新"的悬空态。**这是 2.0 需要评估的风险点（现状：未做补偿，未实现）**。
- **发布的接口**：`POST /workflow/simple-flow/publish`，权限点 `workflow:simpleFlow:publish`（`SimpleFlowController.java:86-90`）。
- **是否复用 `POST /flowable/definition/save`**：**不是 HTTP 复用，是同 Service 复用** —— 注入 `IFlowDefinitionService` 并调 `saveFile(...)`（`:56,168`）。V1 PRD 6.8 说的"复用 `FlowDefinitionController.save`"在实现上落成了"复用 `IFlowDefinitionService.saveFile`"。**两者是同一段部署逻辑，结论无差异**；`SimpleFlowCompiler.java:21-22` 的注释说底层是 `repositoryService.createDeployment().addInputStream(...)`。
- **`def_key` 怎么来**：**用户手填**，不是自动生成、不是拼音、不是 uuid。
  - 前端新建对话框：`UI/views/workflow/simple-flow/index.vue:80-89`（`el-input v-model="addForm.defKey" placeholder="英文标识，如 contractApproval"`，提示"流程标识会作为 Flowable 的流程定义 key，**发布后不可修改**"）；
  - 前端设计器：`designer.vue:8-14`（`:disabled="flow.status === '1'"` 已发布后禁改）；
  - 组装：`index.vue:200` `key: this.addForm.defKey`；`designer.vue:1096` `key: this.flow.defKey`；
  - 后端唯一性：**应用层校验**（`saveDraft` 里 `selectByDefKey` 判重，`:94-97` 与 `:119-122`），**DB 层不是 UNIQUE**（DDL 只有普通 `KEY idx_def_key`，`二开-简化流程设计器.sql:28`）；
  - 格式约束：`[A-Za-z_][A-Za-z0-9_\-]*`（校验器 `SimpleFlowValidator.java:94-97` 与编译器 `SimpleFlowCompiler.java:147-148` **两处各写一遍**——又一处重复逻辑）；
  - 已发布后禁改 key：`SimpleFlowServiceImpl.java:116-118`。

### 3.6 ⭐ 与 `t_template.def_key` 的关联：**确认当前没有任何自动绑定**

这是本次勘察最需要给准的结论。逐层证据：

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| `t_flow_simple` 有指向 `t_template` 的列吗 | **没有** | DDL 17 列里无 `template_id`（`二开-简化流程设计器.sql:8-29`）；实体 `FlowSimple.java:21-62` 同样没有 |
| `t_template` 有指向简化流程的列吗 | **没有** | `table.sql:2146-2168` 19 列；`Template.java:23-112` 无 `simpleFlowId` / `flowMode` |
| 发布时会写 `t_template.def_key` 吗 | **不会**。`publish` 第 7 步只写 `t_template_node_field_auth` | `SimpleFlowServiceImpl.java:211-214,225-238` |
| 有"发布后自动建模板 / 自动回填模板"的接口吗 | **没有** | `SimpleFlowController.java` 全部 9 个端点（`:37-111`）中无一个接受 `templateId`；`ValidateBody`/`PublishBody`（`:115-131`）只有 `id/remark/defKey/version/requiredFields/multiFields` |
| **有没有任何反向关联** | **有，且只有一处** | `SimpleFlowServiceImpl.java:225-238 syncNodeFieldAuth()`：`query.setDefKey(def.getKey()); templateService.listTemplate(query)` → 找出所有 `def_key` 等于本流程 key 的模板 → 对每个模板 `rebuildByTemplate(template.getId(), rows)` |
| 绑定实际怎么发生 | **人工**：管理员在模板新增页的「关联流程」下拉里手选 `procDefKey`（可以是简化设计器发布的 key，也可以是 BPMN 设计器发布的 key） | `basic-info.vue:21-31` + `FlowDefinitionController.java:189-192` |
| 绑定的方向性 | **单向、弱引用**：`t_template.def_key` → `ACT_RE_PROCDEF.KEY_`（字符串）。模板不知道自己是不是由简化设计器创建的 | 全仓 grep `defKey` 的语义分布：`FlowSimple*`/`FlowSimpleHistory*`/`SimpleFlowController` 一组，`Template`/`TemplateMapper` 一组，**两组之间唯一的桥就是 `syncNodeFieldAuth` 的字符串查询** |

> **`syncNodeFieldAuth` 的注释已经明说这是"人工绑定之后"才生效**（`:220-224`）：
> "一个 defKey 可能被多个模板绑定（模板才是发起入口，字段权限也按 template_id 存），所以对每个绑定模板各建一份"；
> "**还没建模板时发布也是合法流程（模板可后补），此处不算失败**"（`:229-232`）。
>
> **⇒ 结论：当前"简化流程"和"单据模板"是两个独立实体，只靠人工填 `def_key` 字符串关联。发布流程不会创建模板，也不会回写 defKey。要求 2.0 的"自动绑定并回写 defKey"是一个新增能力，不是修复既有能力。**

### 3.7 版本 / 历史 / 回滚

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 版本号 | **已实现**。每次发布 `nextVersion = max(t_flow_simple_history.version)+1` | `SimpleFlowServiceImpl.java:182-183` |
| 历史快照 | **已实现**。整段 `content` 原样存入 history（不可变） | `:185-198`；唯一键 `uk_def_key_version`（`二开-简化流程设计器.sql:47`） |
| 回滚 | **已实现**，"以历史版本重新发布（生成新版本）"，不改旧版本 | `:275-297` 注释 `:295` 明说 |
| 草稿保存 | **已实现**。`POST /draft`，`status='0'`、`version=0` | `:75-130`；`SimpleFlowController.java:51-58` |
| 草稿自动保存（30s/失焦） | **未实现**（V1 PRD 11.5 的要求）。只有显式"保存草稿"按钮 | `designer.vue:41` |
| 状态枚举 | 只有 2 态：`"0"` 草稿 / `"1"` 已发布。**没有"已下架"** | `SimpleFlowServiceImpl.java:44-47` |
| 删除 | 逻辑删除（`del_flag='1'`），**无"是否被模板引用"守卫** | `:299-307`、`FlowSimpleMapper.xml:91-98`；`TemplateMapper` 里也没有反查逻辑 |
| 历史列表接口 | `GET /workflow/simple-flow/history/{defKey}` | `SimpleFlowController.java:92-97` |
| 编译预览 | `GET /workflow/simple-flow/preview/{id}`（只编译不部署，返回 XML） | `:67-83`（注释警告：别写成 `success(xmlString)`，那是 `success(String msg)` 重载） |

### 3.8 发布校验规则全清单（对应 V1 PRD 6.7 的 V-1..V-11，实际实现到 V-12）

`SimpleFlowValidator.validate(def, requiredFormFields, multiFormFields)`（`:86-159`）| 常驻上限 `MAX_GROUPS=5`、`MAX_ROWS_PER_GROUP=5`、`MAX_BRANCHES=5`、`MAX_NAME_LEN=20`（`:37-40`）。

| 规则 | 内容 | 级别 | 行 |
| --- | --- | --- | --- |
| **V-0** | 流程定义为空 / `key` 为空 / `key` 格式非法 / `name` 为空 / 无节点 | BLOCK | `:88-106` |
| **V-1** | 审批节点数 = 0 → BLOCK；= 1 → **WARN**"不会产生待办，建议至少 2 个" | BLOCK / **WARN** | `:136-141` |
| **V-2** | 审批/办理节点未配参与人；选"指定人员"但没选人；选"角色"但没选角色 | BLOCK | `:207-219` |
| **V-3** | **首个审批节点**不得用 `ROLE` 或 `INITIATOR_SELECT`（提示原文："引擎在发起时解析不到该变量"） | BLOCK | `:143-156` |
| **V-4** | 节点缺 `id`；节点 `id` 重复；非起止节点名称空 / >20 字 / **同一条链**内重名 | BLOCK | `:171-188` |
| **V-5** | 条件分支无分支；分支下无节点（"会让单据卡住"）；无兜底分支；兜底分支多于 1 个 | BLOCK | `:229-232,280-284,296-300` |
| **V-6 / V-12** | 并行分支：只支持 `FORM_MULTI`；未指定表单字段 / 字段名非法 / 字段不在多选集合；分支参与人只支持 `BRANCH_VALUE`；`maxBranches<=0` | BLOCK | `:303-329` |
| **V-7** | `signMode=REQUIRED` 但 `signTypes` 为空 | BLOCK | `:220-223` |
| **V-8** | 条件引用的字段不在"已设为必填"的字段集合里 | BLOCK | `:272-275` |
| **V-9** | 分支未设条件（"会导致「其他情况」永不执行"）；空条件组；条件缺 `field`/`op` | BLOCK | `:249-269` |
| **V-10** | 分支数 > 5（不含兜底）；分支名空 / 重名；条件组 > 5；组内条件 > 5 | BLOCK | `:233-236,244-248,253-265` |
| **V-11** | 条件分支嵌套条件分支；条件分支内出现并行分支 | BLOCK | `:285-294` |

**与 V1 PRD 的偏差（3 处，均为实现更细）**：
1. V1 PRD 表里 V-6/V-12 只写了"并行分支单一入口"（`PRD:562`），实现把并行分支的限制扩到了 5 条（`:303-329`），并**新增了 V-12 编号**；
2. V1 PRD 的 V-4 是"节点名称非空且 ≤20 字，同一流程内不重名"（`PRD:560`），实现放宽为"**同一条链**内不重名"（`:185-187`，注释 `:24-25` 说明理由："互斥分支里出现同名节点是合理的（打印件只会出现走过的分支）"）；
3. V1 PRD 的 V-1 写"审批节点 ≥2，否则不通过"（`PRD:557`），实现把"只有 1 个审批节点"降为 **WARN**（`:139-141`），只有 0 个才 BLOCK。

**校验字段集合从哪来**：前端设计器把 `requiredFields` / `multiFields` 传给 `POST /validate`（`designer.vue:1103-1104,1136-1139`），来源是 `UI/utils/formSchema.js` 的 `requiredFieldNames`（`:76-77`，`f.required === true`）与 `multiFieldNames`（`:81`，判据 `MULTI_TAGS = ['el-checkbox-group']`，`:10`）。
→ **⚠ 一处能力缺口**：`multiFields` **只认 `el-checkbox-group`**，`el-select multiple` 不算多选字段，会被并行分支拒掉（V-12）。

### 3.9 设计器的表单字段来源：**绑定的是动态表单，不是单据模板**

这是"嵌入模板页"时的关键错配：

- `designer.vue:17-28` 的「关联表单」下拉，数据源是 **`listDynamicForm({pageNum:1,pageSize:200})`**（`designer.vue:901`）→ **`t_template_dynamic_form`**，不是 `t_template`；
- 选用后 `getDynamicForm(formId)` 取 `content`，用 `extractFormFields` 抽出字段清单（`designer.vue:916-922`）；
- 该 `formDef` 只用于**字段驱动**的功能：条件字段下拉（`:208-210`、`:608 conditionableFields`）、并行分支字段（`:611-613`）、`fieldReadonly` 勾选、发布校验的 `requiredFields/multiFields`。

> **⇒ 现状是"一个简化流程 == 一张动态表单"的隐式约定**，但流程表里**没有字段记住这张表单是哪个** —— 每次进设计器都要**重新手选一次**。这对 2.0 "在模板页里内嵌设计器"是直接的坏消息：嵌入时必须把 `template.form_id` 作为初始值传进去，而现在没有任何参数通道。

### 3.10 【2.0】"内嵌到表单新增页第 3 页签 + 自动绑定 + 回写 defKey"的改动点

**未实现**。逐点列出（这是本报告最有价值的产出之一）：

| # | 改动点 | 文件:行 | 具体动作 | 量 |
| --- | --- | --- | --- | --- |
| A1 | 内嵌设计器组件 | `UI/views/workflow/simple-flow/designer.vue`（1367 行） | 现为**独立路由页** `simple-flow/designer`（`UI/router/index.js:161-162`），顶部有"流程名称/流程标识/分类/关联表单"4 个全局输入（`designer.vue:4-43`）。内嵌需：① 抽出"工具条 + 画布 + 配置抽屉"为可复用组件，或② 用 `props` 注入 `templateId/formId` 并隐藏重复输入项 | **L** |
| A2 | 前端 API 补 templateId 通道 | `UI/api/workflow/simpleFlow.js:21-53` | `saveSimpleFlowDraft` / `publishSimpleFlow` 的 body 需能带 `templateId`；`PublishBody`（`SimpleFlowController.java:124-131`）现在**没有该字段** | S |
| A3 | 后端发布接口接 templateId | `BE/.../simple/controller/SimpleFlowController.java:86-90` | `publish` 签名加 `templateId`；或新增 `publishAndBind` | S |
| A4 | 后端"自动建/绑模板"或"回写 defKey" | `BE/.../simple/service/impl/SimpleFlowServiceImpl.java:141-217` | 二选一方案：<br>**(a) 先有模板再发布**：发布成功后 `template.setDefKey(def.getKey())` 并 `templateService.updateTemplate(...)`——⚠ 但 `updateTemplate` 会**换新 id**（`TemplateServiceImpl.java:154-176`），必须改成"定向更新 def_key 不换 id"的新方法；<br>**(b) 先有流程再建模板**：页签 3 里先建流程草稿，拿到 `defKey` 后作为页签 1 的只读值，模板保存时写入 | **M**（含 1.5 的换 ID 陷阱） |
| A5 | `t_template` 增列 `simple_flow_id` / `flow_mode` | `BE/sql/table.sql:2146-2168` | 见 §1.7 的"五处同步"清单（resultMap/select/insert/update/实体） | M |
| A6 | 保存顺序与失败回滚 | `add.vue:107-148` | 现在只发**一个** `POST/PUT /template/template`。四页签后有两种顺序：① 先发模板（拿 id）→ 再发流程（带 id）→ 再 PUT 回 defKey；② 先发流程（拿 defKey）→ 再发模板。**任一顺序都需要"部分成功"的处理**，现状完全没有（无 `try/catch`，无草稿态） | **M** |
| A7 | 草稿语义冲突 | `simple-flow/index.vue:190,200` | 简化流程**自己**有"草稿"（`status='0'`），模板也有"未发布"（`del_flag` 语义混乱，见 §1.5）。两个草稿态并存需要定义清楚"模板未发布时流程草稿属于谁" | S（设计为主） |
| A8 | 权限点 | `BE/sql/二开-简化流程设计器-菜单.sql:10-13` | 嵌入后建议新增 `workflow:template:flow:publish` 之类的**组合权限点**，否则仅有 `workflow:template:add` 的人仍然发不了流程（现在发布要 `workflow:simpleFlow:publish`） | M |
| A9 | 设计器里的「关联表单」下拉 | `designer.vue:17-28,899-907` | 内嵌后应由 `template.formId` 自动带入并**只读**，不再让管理员手选（避免"流程绑了 A 表单、模板绑了 B 表单"） | S |

---

## 4. 发起审批入口

### 4.1 页面结构与分组

**已实现**。`UI/views/workflow/newstart/index.vue`（239 行）：

- **两个区块**：上部「最近使用」（`:4-16`）、下部「新启流程」（`:17-43`）；
- **分组方式**：按**模板分类（`t_template_type`）**分组展示。`templateArr` 是 `[{type, typeName, templates[]}]`，模板里 `v-for` 渲染 `temp.typeName ( temp.templates.length )` 作为组标题（`:28`），组内每 6 个一行（`:span="4"`）；
- **搜索**：前端本地过滤 `t.name.includes(searchInput)`，过滤后为空的组被丢弃（`:94-105`）；
- **点击跳转**：`goToTemplateDetail(item)` → `$router.push({path: '/workflow/flowForm/' + timestamp, query: {pageType:'0', templateName, templateId}})`（`:107-116`）。

**分组数据来源（后端）**：`TemplateController.getNewStartTemplateList()`（`:79-82`）→ `TemplateServiceImpl.listNewStartTemplate()`（`:206-245`）：
1. `selectNewStartTemplateList`（`TemplateMapper.xml:148-157`）；
2. 用 `Collectors.groupingBy` 按 `type` 分组，**空分类归入同一组并排除出分类查询**（`:218-223`，注释 `:214-217` 记录了"分类键为 null 会抛 `element cannot be mapped to a null key`，整个新启流程列表就 500"的实测坑）；
3. **结果由 `templateTypList` 驱动**（`:233-243`）—— 即**只有能匹配到 `t_template_type` 行的分类才会出现在页面上**；空分类的模板会被**静默丢弃**（`:235-237 continue`）。

### 4.2 可见性 / 可发起范围：**未实现（这是 2.0 的核心缺口）**

**核心结论：「谁可以提交该审批」当前不存在。**

| 检查 | 结论 | 证据 |
| --- | --- | --- |
| 新启列表的过滤条件 | **只有** `del_flag = '0' and enable_flag = '1'`，外加可选的 `name like`。**没有 userId / deptId / roleId 任何条件** | `BE/ruoyi-template/src/main/resources/mapper/template/TemplateMapper.xml:148-157` |
| Service 层有没有二次过滤 | **没有**。`listNewStartTemplate()` 只做分组与排序，完全没读 `SecurityUtils` | `TemplateServiceImpl.java:206-245` |
| Controller 有没有权限点 | **没有** `@PreAuthorize`（对比同类的 `add/edit/remove` 都有） | `TemplateController.java:79-82`（`list`/`getSelectTemplateList` 同样无） |
| `t_template` 有没有范围列 | **没有** 19 列里无任何 scope/creator 过滤列 | `table.sql:2146-2168` |
| 有没有单独的"可发起人/部门/角色"关联表 | **没有**。`ruoyi-template` 模块全部 37 个文件的清单里没有这类 domain/mapper | `BE/ruoyi-template/**` 目录列表（无 `TemplateScope` / `TemplateUser` 等） |
| 全仓 grep `submitScope` / `submit_scope` / `可见范围` / `可发起` / `发起范围` | **代码零命中**；仅命中 `doc/飞书流程设计页面.md:136`、`doc/参考文档/wireframes.html:137`、`doc/参考文档/feishu-approval-designer-reference.md:93,448`（均为**设计参考稿**） | grep 结果 |
| 现有"委托"是不是范围控制 | **不是**。`t_workflow_entrust_template` 是"**已发起之后**把待办委托给别人"，与"谁能发起"无关 | `BE/ruoyi-worksetting/.../domain/WorkflowEntrustTemplate.java:10,32`、`WorkflowSettingService.handleEntrust:41-74` |
| 角色/部门数据范围机制能不能复用 | **不能直接用**。`@DataScope` 只用在 `SysDeptServiceImpl:67`、`SysUserServiceImpl:81,101,114`、`SysRoleServiceImpl:57` 共 5 处，**全部是系统模块**，与业务单表无关 | grep `@DataScope` |

**现状等价于：任何登录用户都能在「新启流程」里看到并发起所有 `enable_flag='1'` 的模板。**

### 4.3 【2.0】新增"可发起范围"的最小落点

| 层 | 落点 | 动作 | 量 |
| --- | --- | --- | --- |
| 表 | 新建 `t_template_submit_scope` | `(id, template_id, scope_type char(1) /*0全员 1指定人员 2指定角色 3指定部门 4指定部门及以下*/, scope_id varchar(64), create_*)`，或简化为 `t_template_submit_scope(template_id, scope_type, scope_id)` 多行 | S |
| 表 | `t_template` 增列 `submit_scope_type char(1) DEFAULT '0'` | 快路径：只做"全员 / 指定人员/角色/部门"四档时，单列存类型 + 子表存明细 | S |
| 后端 | `TemplateMapper.xml:148-157` | `selectNewStartTemplateList` 加 `and (t.submit_scope_type = '0' or exists (select 1 from t_template_submit_scope s where s.template_id = t.id and (...)))`，**或在 Service 层过滤**（`TemplateServiceImpl.java:206-245`）—— 后者更可控，且能保留"分组驱动"语义 | M |
| 后端 | `TemplateServiceImpl.listNewStartTemplate():206` | 注入 `SecurityUtils.getUserId()/getDeptId()/getRoles()`；过滤后再分组 | S |
| 后端 | `TemplateDTO.java:13-33` + `TemplateServiceImpl.saveTemplate/updateTemplate` | DTO 增 `submitScope` 字段，`handleSubmitScope()` 与既有 `handleAttachment/handleMainText/handleMessageNotice`（`:276-329`）并列 | M |
| 前端 | `UI/views/workflow/template/` 新增页签或子组件 | 选人/选角色/选部门控件（**可复用 `components/form/design/DesignUserSelect.vue` / `DesignDeptSelect.vue`**） | M |
| 前端 | `UI/api/workflow/template.js:56-61` | `getNewStartTemplateList` 无需改；后端按 token 过滤即可 | S |
| 附带 | `TemplateController.java:79-82` | 建议**不加**权限点（发起列表是普通用户功能），但要在 Service 里做范围收口。**注意 V1 PRD 11.3 的越权防护口径需同步扩展** | S |
| 界面提示 | `newstart/index.vue:15,42` | 已有 `el-empty`，范围过滤后可自然空态；建议区分"没有模板"与"没有你可发起的模板" | S |

> **⚠ 连带风险**：`listNewStartTemplate` 的"空分类模板被静默丢弃"（`TemplateServiceImpl.java:235-237`）在加了范围过滤后**更容易误伤**——用户会看到"没有可发起的流程"而实际原因是"模板没选分类"。2.0 应一并修掉这个静默丢弃。

### 4.4 发起页已有的三条已知行为（2.0 需保留或修正）

| 行为 | 状态 | 证据 |
| --- | --- | --- |
| `pageType=0` 且无 `todoId` 时**自动 `startFlow`** | **已实现（且是坑）** | `flow-form/index.vue:276-279`；同一事实记在 `DEV-ENV.md:271-272` 与 V1 PRD 的 P-3（`PRD:340`） |
| 首节点不支持按角色/动态选人 | **已实现（限制）** | 编译器 `SimpleFlowCompiler.java:543`；校验器 V-3（`SimpleFlowValidator.java:143-156`）；`DEV-ENV.md:266-269` |
| 单节点流程不产生待办 | **已实现（限制）** | `createNextTodo` 只在完成任务时执行；`DEV-ENV.md:270`；校验器降为 WARN（`:139-141`） |

---

## 5. 打印

### 5.1 结论：**打印子系统已完整落地**（V1 PRD 第 7 章 P0）

后端 4 个文件 + 前端 3 个文件 + 2 张表 + 1 个菜单脚本。

| 资产 | 路径 | 行数 |
| --- | --- | --- |
| Controller | `BE/ruoyi-workflow/src/main/java/com/ruoyi/workflow/print/controller/WorkflowPrintController.java` | 139 |
| Service 接口 | `BE/.../print/service/IPrintService.java` | — |
| Service 实现 | `BE/.../print/service/impl/PrintServiceImpl.java` | 520 |
| 聚合模型 | `BE/.../print/model/PrintData.java` | 145 |
| 打印模板实体 | `BE/.../domain/PrintTemplate.java` | — |
| 打印日志实体 | `BE/.../domain/PrintLog.java` | — |
| Mapper | `BE/.../mapper/PrintTemplateMapper.java`（含 `disableOthers`）、`PrintLogMapper.java` + 2 个 XML | — |
| 前端打印页 | `UI/views/workflow/print/index.vue` | 648 |
| 前端浮层 | `UI/views/workflow/print/PrintDialog.vue` | 110 |
| 前端模板配置页 | `UI/views/workflow/print-template/index.vue` | 410 |
| 前端命令式入口 | `UI/plugins/printPreview.js`（`$openPrintPreview`） | — |

### 5.2 打印模板配置项清单（表 ↔ UI ↔ 生效点）

**表 `t_template_print_template`**（DDL：`BE/sql/二开-表单打印.sql:10-36`）：

| 列 | 类型/默认 | UI 控件 | 实际生效点 | 状态 |
| --- | --- | --- | --- | --- |
| `id` | varchar(64) PK | — | — | 已实现 |
| `template_id` | varchar(64) NOT NULL | 页面顶部「单据模板」下拉（非表单字段，作查询维度） | `PrintServiceImpl.getEffectiveTemplate:317-321` | 已实现 |
| `name` | varchar(200) | `el-input maxlength=50`（必填） | — | 已实现 |
| `paper` | varchar(20)='A4' | `el-select` A4/A3 | **⚠ 只存不用**：前端 `print/index.vue` 未读 `paper`；A4 尺寸硬编码在 `A4_CONTENT_PX`（`:123`）与 `print-a4.scss` | **部分实现** |
| `orientation` | varchar(20)='portrait' | radio 纵向/横向 | **⚠ 只存不用**：同 `paper` | **部分实现** |
| `title` | varchar(200) | `el-input maxlength=100` | `PrintServiceImpl:123`（空则 `DEFAULT_TITLE`） | 已实现 |
| `logo_file_id` | varchar(64) | **UI 无控件** | **代码无引用** | **未实现** |
| `field_map` | longtext | `el-input type=textarea rows=6` + "填入内置版式"/"清空"两个快捷按钮 | `print/index.vue:211-219 baseRows` | 已实现（**但见 §5.5 的双路径**） |
| `show_signature` | char(1)='1'（DDL）/**代码默认 '0'** | checkbox「签批栏（含签名）」 | `print/index.vue:192-194` | 已实现 |
| `show_comment` | char(1)='1' | checkbox「处理意见」 | `print/index.vue:195-197` | 已实现 |
| `show_attachment` | char(1)='1'（DDL）/**代码默认 '0'** | checkbox「附件清单」 | `print/index.vue:198-200` | 已实现 |
| `show_cc_node` | char(1)='0' | checkbox「抄送节点出栏」 | **⚠ 存了但前端未读**（`print/index.vue` 无 `showCcNode` 引用） | **部分实现** |
| `watermark` | char(1)='1' | checkbox「水印」 | `PrintServiceImpl:155-158` 生成 `watermarkText` | 已实现 |
| `footer_note` | varchar(500) | `el-input maxlength=200` | `print/index.vue:201-203,107` | 已实现 |
| `enable_flag` | char(1)='1' | radio 启用/停用 | `PrintServiceImpl.saveTemplate:370-372` | 已实现 |
| `del_flag` | char(1)='0' | 删除按钮 | `deleteTemplate:376-383` 逻辑删 | 已实现 |
| `create_id/create_by/create_time/update_id/update_by/update_time` | | | `fillDefaults` + `saveTemplate` | 已实现 |

> **⚠ DDL 与代码默认值不一致**：DDL 里 `show_signature`/`show_attachment` 默认 `'1'`（`二开-表单打印.sql:20,22`），但 `PrintServiceImpl.fillDefaults` 在**空值**时把它们设成 `'0'`（`:414-422`），注释说明理由："这两项原先默认 '1'，与「只打印表单信息」相反"（`:411-413`）。**库里直接 insert 的行会拿到 DDL 默认（'1'），走接口存的行会拿到 '0'。** 这是 2.0 若要"内置模板"必须先统一的语义分歧。

### 5.3 "同一单据模板只保留一套启用" —— **已实现**

**服务端不变量**，在 `PrintServiceImpl.saveTemplate` 末尾无条件执行（`:333-374`）：

```java
// 「同一单据模板下只有一套启用」是服务端不变量：
// 否则 selectByTemplateId 取的是 update_time 最新的那条 —— 改一下旧模板
// 就会悄悄改变打印结果（实测：新键一套模板后，另一张单据的签批栏没了）。
// 放在最后一律执行：只传 enableFlag='0' 的部分更新不会触发它。
if (WhetherStatus.YES.getCode().equals(printTemplate.getEnableFlag())) {
    printTemplateMapper.disableOthers(printTemplate.getTemplateId(), printTemplate.getId());
}
```

- Mapper 方法：`PrintTemplateMapper.java:54-57` `disableOthers(@Param("templateId"), @Param("keepId"))`；
- 读取侧确定性：`selectByTemplateId`（`PrintServiceImpl.java:318-321` 取 `list.get(0)`）+ `PrintTemplateMapper.java:22`；
- 前端也做了同样的判定：`print-template/index.vue:214-224 effectiveId`（若历史数据有多套启用，取 `updateTime` 最新的一条作"生效中"标记），并在 UI 上把这条规则写成提示文案（`:24-27`）；
- **DDL 层没有唯一约束**（`二开-表单打印.sql:34-35` 只有 `KEY idx_template (template_id, del_flag)`）→ 并发写入理论上仍可破坏该不变量。**状态：应用层已实现，DB 层未实现。**

### 5.4 打印数据聚合接口与权限校验

**接口**：`GET /workflow/print/data/{businessId}?printTplId=`（`WorkflowPrintController.java:59-66`）。

**完整响应体**（`PrintData.java:20-144`）：

| 字段 | 类型 | 来源 | 行 |
| --- | --- | --- | --- |
| `businessId` | String | 入参 | `PrintServiceImpl:95` |
| `procInsId` | String | 历史实例 | `:107` |
| `businessNo` | String | **= businessId**（注意：不是单据编号） | `:128` |
| `templateId` | String | `printTemplateMapper.selectTemplateIdByBusinessId` | `:117,120` |
| `templateName` | String | **未赋值** | — |
| `printTemplate` | PrintTemplate | `getEffectiveTemplate` | `:121-122` |
| `title` | String | `tpl.title` 或 `DEFAULT_TITLE` | `:123` |
| `submitter` | String | `records` 里第一个非空 `startUserName` | `:126` |
| `submitterDept` | String | `startDeptName` | `:127` |
| `submitTime` | Date | `HistoricProcessInstance.getStartTime()` | `:108` |
| `finishTime` | Date | `getEndTime()` | `:109` |
| `instanceStatus` | String | `已终止` / `运行中` / `已结束` / `草稿` | `:110,112,171-176` |
| `formData` | Object | `bizFormService.getBizForm(cf)`（含中文 label） | `:139-152` |
| **`formSchema`** | Object | **永不被赋值**（见 §5.5） | `PrintData.java:67-68` |
| `nodes` | List\<Node\> | `buildNodes` | `:132` |
| `attachments` | List\<Attachment\> | `loadAttachments` | `:135` |
| `watermarkText` | String | `"<打印人> 于 <yyyy-MM-dd HH:mm> 打印，仅供内部使用"` | `:155-158` |
| `printTime` | Date | `new Date()` | `:96` |
| `printUser` | String | `SecurityUtils.getUsername()` | `:97` |

`Node` 子结构（`PrintData.java:88-131`）：`taskId, taskDefKey, nodeName, deptName, assigneeName, receiveTime, finishTime, comment, signFileId, status, nodeIndex`。
`Attachment` 子结构（`:134-144`）：`fileId, fileName, fileExt, fileSize, sort`。

**子查询清单（3 组，不是 5 个 HTTP 请求）**：
1. `historyService.createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessId).orderByProcessInstanceStartTime().desc().list()`（`:163-169`）；
2. `flowTaskService.flowHistoryRecord(procInsId, null, 1, 500)` **+** `flowTaskService.flowCmts(procInsId, null, 1, 500)` —— 注释详述**必须两个都调**的理由（`:178-208`）：前者给全部活动但无意见、后者有意见但只给已办结活动，只用后者会让进行中节点整栏消失；按 `taskId` 合并；
3. `attachmentService.listAttachment(businessId)`（`:270-289`）；
4. `signService.effectiveSignByTask(businessId)`（`:131`）；
5. `bizFormService.getBizForm(cf)`（`:139-152`，**失败只 `log.warn` 不抛**，注释"表单取不到不该让整张打印件失败"）。

**权限校验（越权防护已实现）**：`docViewGuard.requireViewable(businessId)`（`WorkflowPrintController.java:63`）。判定实现 `BE/.../guard/DocViewGuard.java`：

- `canView`：空 businessId → false；`SecurityUtils.isAdmin` → **放行**（`:45-47`）；否则 `accessCheckMapper.countViewerHit(businessId, userId) > 0`（`:49`）；
- 查询异常时**按无权处理**（`:50-54`，注释"失败放开等于没做"）；
- `requireViewable` 抛 `ServiceException(msg, HttpStatus.FORBIDDEN)`（`:62-71`）→ **AC-35 的 403**；
- 四路 union 判定（`BE/.../mapper/workflow/AccessCheckMapper.xml`）：
  1. `t_workflow_todo.cur_handler = userId and del_flag='0'`
  2. `t_workflow_done.handler = userId`
  3. `t_workflow_recycle.create_id = userId and del_flag='0'`
  4. `t_workflow_form.create_id = userId`（**发起人/草稿所有者**）
- **`/log`（写留痕，`WorkflowPrintController.java:117-129`）与 `/log/list`（`:132-138`）也过 `requireViewable`**（注释："否则任何人都能往别人的单据上灌打印记录"）。
- `PrintTemplate` 的**读接口 `/template/{templateId}` 与 `/template/list` 不经 `DocViewGuard`**，`/template/list` 有 `workflow:print:template` 权限点、`/template/{templateId}` **无权限点**（`:68-74`）；写接口有 `:edit` / `:remove`（`:96-109`）。

### 5.5 ⭐ `PrintData.formSchema` 与 `formData` 两条取数路径（DEV-ENV 说法的核验）

**DEV-ENV.md:178-181 的原文**：

> ⚠ 打印件的字段表来自**单据自己保存的 `formData.fields`**（不是 `PrintData.formSchema`，后端那个字段从未赋值、前端也从不读）。

**核验结论：基本成立，但需要更精确的表述。**

| 断言 | 核验结果 | 证据 |
| --- | --- | --- |
| 后端 `PrintData.formSchema` 从未赋值 | **✅ 完全成立** | 全仓 grep `setFormSchema` → **零命中**；全仓 grep `formSchema` → 仅 4 处，均在 `doc/` 与 `DEV-ENV.md`，**代码零命中** |
| 前端从不读 `formSchema` | **✅ 成立** | `UI/views/workflow/print/index.vue` 全文无 `formSchema` |
| 字段表来自单据自己的 `formData.fields` | **⚠ 需区分两种情形** | 见下 |

**前端实际的两条路径**（`print/index.vue:204-219` 的注释与代码）：

```
优先级：
  1. 管理员配置过的打印模板（tpl.id 有值）→ 用它的 field_map，版式完全可控；
  2. 内置系统模板（tpl.id 为空）→ 按表单 schema 自动排版；
  3. 连 schema 都没有 → 退回模板里的内置 field_map。
```

```js
baseRows() {
  const isBuiltin = !this.tpl.id
  if (isBuiltin && this.schemaFields.length) return this.rowsFromSchema()   // ← 情形 2
  const map = this.parseFieldMap(this.tpl.fieldMap)
  if (!map) return this.rowsFromSchema()
  const sec = (map.sections || []).find(s => !s.type || s.id === 'base')
  const rows = (sec && sec.rows) || []
  return rows.length ? rows : this.rowsFromSchema()                          // ← 情形 1
}
```

而 `schemaFields` 的填充来自 `applyFormData(d.formData)`（`:269`）→ `:537` `const fields = (payload && payload.formData && payload.formData.fields) || []`。

**精确表述（2.0 必须按这句理解）**：
1. **只要管理员没建过打印模板（`tpl.id` 为空，即走了 `builtinTemplate`），版式 = 按单据快照里的 `formData.fields` 自动排版**，`DEFAULT_FIELD_MAP` **不参与**（仅当连 `schemaFields` 也为空时才是最后兜底）。→ DEV-ENV 的说法在这条路径上准确。
2. **管理员建过模板且填了 `field_map` 时，版式 = `tpl.field_map`**，字段是**按 `__vModel__` 从 `formValues`（= `valData`）里取值**（`:403-407 valueOf`）。→ 此时**不是**按 `formData.fields` 排版。
3. **`isBuiltinTpl` 的语义**（`:182-184`）：`!this.tpl.id`。内置模板的 `showSignature`/`showAttachment` **一律视为 false**（`:192-200`），注释明确："内置模板**永远只打印表单信息**"。→ 这是 §5.6 的第二个阻碍点。

**手写夹具的形状要求**（DEV-ENV.md:180-181 的坑，已在代码中确认）：
`formData` 是**字符串**，解析后形如 `{ formData: { fields:[{__config__, __vModel__}] }, valData: {字段→值} }`。前端做了**三层兜底**（`:488-534`）：
- ① `raw` 是字符串 → `JSON.parse`；
- ② `payload.formData` 是字符串 → 再 `JSON.parse`；
- ③ 值优先取 `valData`，再摊平 `payload` 顶层标量，最后并入 `BizForm` 自身标量属性。
注释 `:484` 记录了坑："`formData` 是**字符串**不是对象，只按对象递归会一个字段都取不到（页面上字段全空）"。

### 5.6 内置默认模板的标题与版式常量

**后端 `PrintServiceImpl` 的全部相关常量**：

| 常量 | 值 | 行 |
| --- | --- | --- |
| `RECORD_PAGE_SIZE` | `500`（签批栏最多取多少条流转记录，不分页） | `:64` |
| `INSTANCE_STATUS_DRAFT` | `"草稿"` | `:67` |
| **`DEFAULT_TITLE`** | **`"集团合同类文件流转审批单"`** | **`:466`** |
| `DEFAULT_FIELD_MAP` | `= buildDefaultFieldMap()`（静态懒构建） | `:469` |

`builtinTemplate(templateId)`（`:391-402`）—— **内置模板的完整定义，共 8 个赋值**：

```java
t.setId(null);                          // ← 前端据此判 isBuiltinTpl
t.setTemplateId(templateId);
t.setName("系统默认打印模板");
t.setPaper("A4");
t.setOrientation("portrait");
t.setTitle(DEFAULT_TITLE);
t.setFieldMap(DEFAULT_FIELD_MAP);
fillDefaults(t);
```

`DEFAULT_FIELD_MAP` 的字面内容（`buildDefaultFieldMap()`，`:471-501`）—— **7 行 + 2 个特殊 section**：

```jsonc
{ "sections": [
  { "id": "base", "rows": [
      { "cells": [ {"label":"提报单位","field":"$submitterDept","span":1},
                   {"label":"报送人","field":"$submitter","span":1},
                   {"label":"报送时间","field":"$submitTime","span":1} ] },
      { "cells": [ {"label":"合同编号","field":"contractNo","span":3} ] },
      { "cells": [ {"label":"合同全称","field":"contractName","span":3} ] },
      { "cells": [ {"label":"合同签订主体-甲方","field":"ourCompany","span":1},
                   {"label":"合同签订主体-乙方","field":"counterpartyName","span":1},
                   {"label":"合同金额","field":"amount","span":1} ] },
      { "cells": [ {"label":"履约开始","field":"startDate","span":1},
                   {"label":"履约结束","field":"endDate","span":1},
                   {"label":"合同签订时间","field":"signDate","span":1} ] },
      { "cells": [ {"label":"其他会审部门","field":"jointDepts","span":3} ] },
      { "cells": [ {"label":"相关说明","field":"description","span":3} ] } ] },
  { "id": "sign",  "type": "dynamic",        "source": "flowNodes" },
  { "id": "attach","type": "attachmentList" } ] }
```

**辅助工厂**：`cell(label, field, span)`（`:503-509`）与 `row(cells...)`（`:511-519`）。

**`fillDefaults` 的默认值**（`:404-432`）：`paper='A4'`、`orientation='portrait'`、`showSignature='0'`、`showComment='1'`、`showAttachment='0'`、`showCcNode='0'`、`watermark='1'`、`enableFlag='1'`（均为**空值时才设**）。

**前端版式常量**：
- `A4_CONTENT_PX = ((297-24)/25.4)*96`（A4 版心高度，用于估算页数）—— `print/index.vue:122-123`；
- 页数测算 `measurePages()` 用 `paper.scrollHeight / A4_CONTENT_PX`，注释"不依赖 CSS `counter(pages)`，避免浏览器差异"（`:310-324`）；
- 样式：`@import '@/assets/styles/print-a4.scss'`（`print/index.vue:646-648`）；
- 签批栏序号用 Unicode 圈码 `①..⑳`（`:617-621 cn()`）；
- 附件表列宽（`12mm / auto / 18mm / 22mm`）（`:86-89`）。

**前端另有一份 `DEFAULT_FIELD_MAP`**（`print-template/index.vue:156-173`，JSON 字符串，作为"填入内置版式"的起点），与后端同源但**是复制品**：
- 后端 7 行 vs 前端 7 行，内容一致（已逐字比对）；
- 前端注释 `:152-154`："与后端 `PrintServiceImpl.DEFAULT_FIELD_MAP` 同源。这里只是给管理员一个'起点'，不再参与渲染 —— 渲染一律读库里的 field_map。**⚠ 改动 7.8 映射时，两处要一起改。**"
- **⇒ D-4 地雷（§2.5）**：无自动化守卫。

### 5.7 打印留痕

**已实现**。
- 表 `t_print_log`（`二开-表单打印.sql:38-53`）：`id, business_id, proc_ins_id, template_id, print_tpl_id, print_user_id, print_time, print_ip, user_agent, page_count, watermark_text`；`KEY idx_biz (business_id)`；表注释"只追加"。
- 写接口 `POST /workflow/print/log`（`WorkflowPrintController.java:117-129`）：
  - `docViewGuard.requireViewable`（`:120`）；
  - **IP 与 UA 由服务端从 `ServletUtils.getRequest()` 取**（`:121-126`，UA 超 500 截断）；
  - `printUserId` / `printUserName` / `printTime` 在 Service 里**以服务端会话为准覆盖**（`PrintServiceImpl.writeLog:445-452`，注释"打印人一律以服务端会话为准，不接受客户端传入（留痕的可信性）"）。
- 查接口 `GET /workflow/print/log/list?businessId=`（`:132-138`）。
- **前端是否总是调用**：**是**，但**失败只提示不阻断**。`print/index.vue:284-308 doPrint()`：先 `measurePages()` → `window.print()` → `writeLog()`；`writeLog` 的 `.catch` 只 `msgWarning('打印留痕写入失败，请联系管理员')`（`:304-307`，注释"已经打出去了，这里只提示"）。→ **状态：留痕是"尽力而为"，不是硬门禁。**

### 5.8 打印入口（已实现的 3 个，缺 1 个）

| 入口 | 状态 | 证据 |
| --- | --- | --- |
| 单据详情页 `pageType=2` | **已实现** | `flow-form/index.vue:15`（`<el-button v-if="businessId" icon="el-icon-printer" @click="printBtn">`）、`:313-319`；注释 `:10-12` 说明"必须放在右侧操作列之外，因为那一列的条件是 `pageType != '2'`" |
| 已办列表行 | **已实现** | `UI/views/workflow/done/index.vue:64,155` |
| 我起草列表行 | **已实现** | `UI/views/workflow/my-draft/index.vue:58,119`（注释 `:115`："草稿同样可以打印：打印件只输出表单信息，而草稿的数据在 `t_workflow_form` 里是齐的"） |
| 待办列表行 | **未实现** | `todo/todo-list.vue` 无 `icon-printer` / `openPrintPreview` |
| 回收站 | **未实现** | `recycle/index.vue` 无 |
| 直链 `/workflow/print?businessId=&printTplId=` | **已实现** | `UI/router/index.js:170-179`（顶层路由、不套 Layout） |

**浮层机制**：命令式挂载 `this.$openPrintPreview(businessId[, printTplId])`（`plugins/printPreview.js:27,41`），内部用 **iframe** 打开 `/workflow/print?...&embedded=1`（`PrintDialog.vue:29-35,63-76`）。理由写在 `PrintDialog.vue:17-28`：① iframe 的 `window.print()` 天然与应用外壳隔离，不需要 `@media print` 去壳；② 不跳转页面；③ 复用同一独立路由。**`PrintDialog.vue` 与 `print/index.vue` 之间没有版式逻辑重复**（浮层只是 iframe 容器）。

### 5.9 ⭐ 【2.0】新增两套内置模板《集团资金审批单》《集团事项类打印审批单》的改动点

**现状阻碍（这是必须先解决的设计问题）**：

1. **内置模板是全局唯一的一套**：`builtinTemplate(templateId)` 不接收任何"单据类型"参数，`DEFAULT_TITLE` 是**单个 `static final String`**（`PrintServiceImpl.java:466,391-402`）。→ 无法按单据类型给出不同标题。
2. **内置模板永远不出签批栏**：`print/index.vue:182-184 isBuiltinTpl = !tpl.id`，而 `:192-200` 对内置模板强制 `showSignature=false / showAttachment=false`。→ 《集团资金审批单》这类需要签批栏的单据，用内置模板**配不出来**。
3. **`paper` / `orientation` 只存不用**（§5.2）：即使内置模板给出 A3/横向，前端也读不到。
4. **`show_cc_node` 存了没人读**（§5.2）。

**改动点清单（按"最小可行"排序）**：

| # | 类型 | 文件:行 | 动作 | 量 |
| --- | --- | --- | --- | --- |
| B1 | 常量 | `BE/.../print/service/impl/PrintServiceImpl.java:466` | `DEFAULT_TITLE` 改为**按 key 索引的 Map**（如 `DEFAULT_TITLES = {contract: "集团合同类文件流转审批单", fund: "集团资金审批单", matter: "集团事项类打印审批单"}`） | S |
| B2 | 方法签名 | `PrintServiceImpl.java:391-402 builtinTemplate(String templateId)` | 增加第 2 个入参（内置模板 key 或 `templateId` 查出来的类型）；**同步改调用点 `:323`** | S |
| B3 | 内置版式常量 | `PrintServiceImpl.java:469-501 buildDefaultFieldMap()` | 拆成 3 份（或 Map<key, fieldMap>）：`buildContractFieldMap()` / `buildFundFieldMap()` / `buildMatterFieldMap()`；资金审批单的字段名需**新定义**（现状字段名 `contractNo/contractName/amount/...` 是合同专用） | **M** |
| B4 | 选择依据 | `PrintServiceImpl.java:317-323 getEffectiveTemplate` | 需要一条"内置模板怎么选"的规则。可选：① `t_template.type`（模板分类）映射到内置 key；② `t_template.form_code`；③ **新增 `t_template` 列 `builtin_print_key varchar(32)`**（最明确，但要动表，见 §1.7 的五处同步） | **M/L** |
| B5 | DDL / 数据 | `BE/sql/二开-表单打印.sql` 或新增量文件 | 若走"内置 = 代码常量"路线，**不需要 DDL**；若走"内置 = 预置数据行"路线，需要 seed `INSERT INTO t_template_print_template`（3 条，每类单据一条，`field_map` 填对应版式），并处理"单据模板还没建时没有 `template_id` 可挂"的问题 | S / M |
| B6 | 前端开关判定 | `UI/views/workflow/print/index.vue:182-200` | `isBuiltinTpl` 现在会**强制关掉签批栏**。若资金审批单要出签批栏，必须改这条：或让内置模板也能带 `showSignature='1'`（改 `fillDefaults` `:414-416` 的默认 + 前端 `:192-194` 的 `!this.isBuiltinTpl &&` 判断） | **M**（**这是最容易被漏掉的一处**） |
| B7 | 前端版式常量 | `UI/views/workflow/print-template/index.vue:156-173` | 复制的 `DEFAULT_FIELD_MAP` 需同步扩成 3 份，或改为从后端拉取（**建议后者，顺手消除 D-4 地雷**） | S / M |
| B8 | 前端 A4 常量 | `print/index.vue:122-123 A4_CONTENT_PX` | 若支持 A3/横向，需读 `tpl.paper/orientation` 并把页数测算参数化 | S |
| B9 | 菜单/权限 | `BE/sql/二开-打印模板菜单.sql:25-36` | 若新增"内置模板"管理页，需新增菜单 + 权限点；若只是常量，**无需改菜单** | S |
| B10 | 契约 | `PrintData.java:67-68 formSchema` | 顺手决定：把 `formSchema` **删掉**（承认它从不赋值）还是**补上赋值**。现状是"声明了但永远 null 的死字段"，是 2.0 契约文档的噪声 | S |

**推荐路线（基于现有代码的最短路径）**：走 **B1+B2+B3+B4(③)+B6+B7**，即
"`t_template` 增一列 `builtin_print_key`（默认 `contract`）→ `getEffectiveTemplate` 读它 → `builtinTemplate(key)` 返回该 key 对应的标题+版式 → 前端放开内置模板的签批栏开关"。
好处：不动 DDL 里的 `t_template_print_template`（配置模板能力不变）、不引入 seed 数据、内置模板与配置模板的优先级关系（`getEffectiveTemplate:311-324`：显式 `printTplId` > 单据模板下的启用行 > 内置）保持不变。

---

## 6. 权限与菜单

### 6.1 权限点命名规则（现状实测）

**规则**：`<域>:<资源>:<动作>`，全小写，冒号分隔；动作集稳定为 `list / query / add / edit / remove / status`。

**现状的 `workflow:*` 权限点全集**（从 `BE/sql/data.sql` 提取，共 14 个）：

```
workflow:done:list      workflow:draft:list     workflow:recycle:list
workflow:seal:add       workflow:seal:edit      workflow:seal:list
workflow:seal:query     workflow:seal:remove
workflow:template:add   workflow:template:edit  workflow:template:list
workflow:template:query workflow:template:remove workflow:template:status
```

**V1 已新增的命名（沿用同一规则，但**资源段用驼峰**）**——见 §6.2：

```
workflow:simpleFlow:list | query | edit | publish | remove
workflow:print:template  | :edit | :remove
```

> ⚠ **一处命名不一致**：既有资源段是**全小写单词**（`done/draft/recycle/seal/template`），V1 新增的简化流程用了**驼峰** `simpleFlow`（`BE/sql/二开-简化流程设计器-菜单.sql:7,10-13`）。2.0 应二选一并写进 PRD 的命名规范章节。**建议：新权限点一律全小写多段**（如 `workflow:simpleflow:list`），但**不要改存量**（会打断已授权角色）。

**后端强制方式**：`@PreAuthorize("@ss.hasPermi('...')")`。示例：`TemplateController.java:49(add) / :59(edit) / :69(remove) / :87(status)`；**注意 `list`、`getInfo`、`newStart`、`select` 四个查询端点都没有权限点**（`:31-44,79-82,97-100`）。
打印侧对照：`WorkflowPrintController.java:85 / :97 / :105`。

### 6.2 菜单如何注册

**已实现，且有两种并存的注册方式**：

| 方式 | 文件 | 现状 |
| --- | --- | --- |
| 全量基线 | `BE/sql/data.sql`（77,142 字节） | `insert into sys_menu(...)` **单条巨型 INSERT**，含全部存量菜单与权限点（例：`data.sql:39`）。`workflow:template:*` 在这里 |
| 增量脚本 | `BE/sql/二开-*.sql`（7 个） | V1 二开的菜单/权限点**全部走增量**，不进 `data.sql` |

**增量脚本清单（这就是 2.0 该照抄的模板）**：

| 文件 | 内容 | 关键行 |
| --- | --- | --- |
| `BE/sql/二开-简化流程设计器.sql` | 2 张业务表 | `:7-48` |
| `BE/sql/二开-简化流程设计器-菜单.sql` | 1 个菜单 + 4 个按钮 | `:6-13` |
| `BE/sql/二开-表单打印.sql` | 2 张业务表 | `:10-54` |
| `BE/sql/二开-打印模板菜单.sql` | 1 个菜单 + 2 个按钮 | `:15-36` |
| `BE/sql/二开-节点字段权限.sql` | 1 张表 | `:12-26` |
| `BE/sql/二开-签名记录.sql` | 1 张表 | 见文件 |
| `BE/sql/二开-签名防篡改.sql` | 2 个触发器 | `:20-32` |
| `BE/sql/二开-预存签名.sql` | 1 张表 | 见文件 |

**菜单表结构**：`BE/sql/table.sql:1440-1462`；关键列 `menu_id varchar(64) PK`、`parent_id`、`menu_type char(1)`（**M目录 / C菜单 / F按钮**）、`path`、`component`、`perms`、`visible`、`status`、`is_frame`、`is_cache`、`order_num`。

**新增菜单的完整写法（照抄 `二开-打印模板菜单.sql`）**：

```sql
-- 1) 幂等：先删同 id 再插
DELETE FROM sys_menu WHERE menu_id IN ('...B1','...B2','...B3');

-- 2) C 类菜单：挂到流程管理目录 106AAA1FE985D47FDB080A11C05B9001 下
INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
 is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
('9F2C...B1', '打印模板配置', '106AAA1FE985D47FDB080A11C05B9001', 8,
 'print-template', 'workflow/print-template/index', NULL, NULL,
 1, 0, 'C', '0', '0', 'workflow:print:template', 'print', 'admin', sysdate(),
 '合同审批二开：A4 打印件的纸张/标题/各区开关/字段映射'),

-- 3) F 类按钮：parent_id 指向该 C 菜单
('9F2C...B2', '打印模板保存', '9F2C...B1', 1,
 '', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'workflow:print:template:edit', '#', 'admin', sysdate(), '');
```

**要点（都是脚本注释里写明的）**：
- `menu_id` 用 `9F2C` 前缀 + 32 位十六进制人工串（`二开-打印模板菜单.sql:16-18`）——**这是本项目二开的约定前缀**，2.0 应继续用并顺延（`B1..B3` 已用，可起 `C1`）；
- `parent_id = '106AAA1FE985D47FDB080A11C05B9001'` = 流程管理目录（`二开-简化流程设计器-菜单.sql:7` 与 `二开-打印模板菜单.sql:25` 一致）；
- **删除-再插入**，保证脚本幂等（`二开-打印模板菜单.sql:15-19`）；
- `is_frame=1`、`is_cache=0`、`visible='0'`、`status='0'` 是固定套路值；
- 脚本注释显式提醒"**只给 superAdmin 之外的角色授权时，记得到「系统管理 → 角色管理」把这几个勾上**"（`二开-打印模板菜单.sql:12`）。

**前端组件路径约定**：`component = 'workflow/<目录>/index'`（对应 `UI/views/workflow/<目录>/index.vue`）。后端路由由 RuoYi 动态菜单生成。

### 6.3 角色 / 部门数据范围机制

**已实现，但只覆盖系统模块。**

| 机制 | 实现 | 覆盖范围 | 证据 |
| --- | --- | --- | --- |
| `@DataScope` 注解 | AOP 拼 `data_scope` SQL 片段 | **仅 5 处**：`SysDeptServiceImpl.java:67`、`SysUserServiceImpl.java:81,101,114`、`SysRoleServiceImpl.java:57` | grep `@DataScope` |
| `sys_role.data_scope` | 角色级数据范围（全部/自定义/本部门/本部门及以下/仅本人） | 只作用于 `sys_user` / `sys_dept` / `sys_role` 查询 | 同上 |
| 业务侧越权防护 | `DocViewGuard`（四路 union） | **只有打印模块用了** | `WorkflowPrintController.java:63,120,135` |
| 业务侧越权防护（签名） | `TaskOwnershipGuard`（**不做 admin 放行**） | 签名/撤签 | `DocViewGuard.java:23-24` 注释 + `WorkflowSignController.java` |
| 单据列表的数据来源 | **隐式**：待办/已办/回收站各自按 `cur_handler` / `handler` / `create_id` 查 | 与 `AccessCheckMapper` 的四路一致 | `AccessCheckMapper.xml` 注释 |
| **哪些单据能被发起** | **无任何机制** | — | §4.2 |

> **⇒ 2.0 的含义**：RuoYi 的 `data_scope` **不能直接用在业务表上**（它读 `sys_role.data_scope` 并拼 `sys_dept` 别名）。"按部门范围控制可发起模板"必须**自建**，落点见 §4.3。

### 6.4 【2.0】新增菜单 / 权限点的命名样例

遵循 §6.1 的规则，给出可直接抄的样例（**均为建议，2.0 PRD 定稿后分配**）：

| 场景 | 建议权限点 | 类型 | 说明 |
| --- | --- | --- | --- |
| 模板页签 3「流程配置」查看 | `workflow:template:flow` | C（挂在模板菜单下） | 与既有 `workflow:template:list` 同级风格 |
| 模板页签 3 内发布流程 | `workflow:template:flow:publish` | F | **避免复用 `workflow:simpleFlow:publish`**：只有模板管理权的人也应该能发布（否则两个权限点必须同时授予） |
| 模板页签 3 保存流程草稿 | `workflow:template:flow:edit` | F | |
| 可发起范围配置（页签 4） | `workflow:template:scope:edit` | F | |
| 内置打印模板选择 | `workflow:print:builtin` | C 或 F | 若做管理页则 C |
| 资金/事项类打印模板 | `workflow:print:template:fund`、`workflow:print:template:matter` | F | **若只是内置常量则不需要这两个权限点** |
| 2.0 新业务模块（如资金审批） | `workflow:fund:list / query / add / edit / remove / status` | C + F×5 | 照抄 `workflow:template:*` 六件套 |
| 事项类审批模块 | `workflow:matter:list / query / add / edit / remove / status` | C + F×5 | 同上 |

**注意**：权限点**只在后端 `@PreAuthorize` 与前端 `v-hasPermi` 里生效**；新增权限点后必须**同时**：
1. 在增量 SQL 里插 `sys_menu` 行（§6.2 的写法）；
2. 在 `sys_role_menu` 里给角色授权（**没有现成脚本，靠「角色管理」页面勾选**——`二开-打印模板菜单.sql:12` 的提醒）；
3. 后端 `/workflow/**` 的接口**不要漏加**。

---

## 7. 既有二开控件与签名（避免 2.0 重复立项）

### 7.1 已落地的二开控件总表

| 类别 | 控件 | tag | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| **金额** | 金额（千分位/小数位/币种/大写） | `design-amount` | **已实现** | `DesignAmount.vue`；`config.js:731-752`；`RightPanel.vue:66-81`；`ComponentTypeEnum.java:18`；大写实现 `UI/utils/money.js` 的 `amountWithUpper` |
| **计算公式** | 只读计算（日期区间→天数 / 金额小写→大写） | `design-calc` | **已实现** | `DesignCalc.vue`；`config.js:753-771`（`formula: 'dateDiff'` / `amountUpper`，`fromField`/`toField`/`unit`）；`RightPanel.vue:43-63`；`ComponentTypeEnum.java:20` |
| **时长** | 同上（UI 文案"时长（天）"） | `design-calc` | **已实现** | `config.js:755` `label: '时长（天）'` |
| **分组标题** | 分组标题（可折叠/竖条/说明） | `design-section` | **已实现** | `DesignSection.vue`；`config.js:772-789`（`title/desc/showLine/icon`）；`RightPanel.vue:82-90`；`view-form.vue:9-13` |
| **说明文字** | 说明文字（≤500 字、可对齐） | `design-text` | **已实现** | `DesignText.vue`；`config.js:790-795+`；`RightPanel.vue:91-95`；`view-form.vue:14` |
| **部门** | 选部门（单选/多选、默认值） | `design-dept-select` | **已实现** | `DesignDeptSelect.vue`；`config.js:672-694`；`RightPanel.vue:477,485`；`view-form.vue:52-59` |
| **人员** | 选人（单选/多选） | `design-user-select` | **已实现** | `DesignUserSelect.vue`；`config.js:649-671`；`RightPanel.vue:470,485`；`view-form.vue:42-51` |
| **流水号** | 编号（自动生成） | `design-serial-no` | **部分实现** | `render.js:4,144` 有登记；`config.js:697-720` 有清单；**组件本体在 `UI/components/SerialNo`（未逐行核验）** |
| **签名位** | 表单内嵌签名位（值=fileId） | `design-signature` | **未实现** | 仅 `ComponentTypeEnum.java:22` 有枚举占位；组件/config/RightPanel/view-form **全无** |
| 旧版并存 | 选人 / 选部门 | — | **已实现（重复）** | `UI/components/form/FormUserSelect.vue`、`FormDeptSelect.vue` |

### 7.2 签名组件（V1 REQ-SIGN-001..006 全部已落地）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 手写签名采集（PC 鼠标 + H5 触屏） | **已实现** | `UI/components/SignaturePad/canvas.vue`（117 行，含 `isEmptySignError` 非空白校验导出）；`SignaturePad/index.vue:103` `<SignatureCanvas :width="820" :height="300" :hint="hint" />` |
| 双 Tab 弹窗（预存 / 手写） | **已实现** | `SignaturePad/index.vue:32`（`name="preset"`）、`:96`（`name="handwrite"`）、`:168-169`（`activeTab: 'preset'`） |
| 预存签名列表 + 默认标记 | **已实现** | `SignaturePad/index.vue:70-88`（`preset-grid` / `preset-card`）；默认签名提示 `:62-67`（含"还没有默认签名，请先在「个人中心 → 我的签名」里设一枚为默认"） |
| "同时保存为预存" | **已实现** | `SignaturePad/index.vue:105-115`（`save-preset` 勾选 + 名称输入） |
| 节点签名策略（NONE/OPTIONAL/REQUIRED × HANDWRITE/PRESET） | **已实现** | `BE/.../sign/policy/SignPolicyReader.java:48-58`（常量）、`:113,137`（从扩展属性读 `signMode`/`signTypes`）；编译写入点 `SimpleFlowCompiler.java:636`；前端 `SignaturePad/index.vue:178`（`policy: {signMode, signTypes[], signRequired}`）、`:209,216`（`allowHandwrite`/`allowPreset`） |
| 策略查询接口 | **已实现** | `GET /workflow/sign/policy?taskId=`（`WorkflowSignController.java:62-63`）；前端 `flow-form/index.vue:349-357 loadSignPolicy()` |
| "必需签名"提交阻断（前端） | **已实现** | `flow-form/index.vue:772` `if (this.signPolicy && this.signPolicy.signRequired && !this.signedFileId)` |
| "必需签名"提交阻断（**服务端**） | **已实现** | `BE/.../sign/guard/TaskSignGuard.java`（69 行，对应 AC-26 的"伪造请求也被服务端拒绝"） |
| 签名记录表 + 哈希链 | **已实现** | 表 `t_workflow_sign_record`（`BE/sql/二开-签名记录.sql`）：`sign_type char(1)`（**0-手写 / 1-预存 / 2-印章 / 9-撤销**）、`form_data_hash varchar(64)`、`prev_hash`、`record_hash` |
| 哈希算法 | **已实现** | `SignServiceImpl.java:204`（注释给出公式 `id‖businessId‖taskId‖fileId‖signUserId‖signTime‖formDataHash‖prevHash`）、`:224` `MessageDigest.getInstance("SHA-256")` |
| 链序还原（不靠 `sign_time` 排序） | **已实现** | `BE/.../sign/SignChain.java`（134 行）：`order()`（`:44-88`）、`tip()`（`:111-114`）、`latestByTask()`（`:125-133`）；注释 `:18-29` 记录了"同一秒内重签+撤销，uuid 大小决定顺序 → 撤签后该节点仍算已签（**AC-26 的必需签名校验因此被绕过**）"的实测坑 |
| 只追加（DB 层拒绝 UPDATE/DELETE） | **已实现** | `BE/sql/二开-签名防篡改.sql:20-32` 两个 `BEFORE UPDATE/DELETE` 触发器，`SIGNAL SQLSTATE '45000'`；用**单语句 SIGNAL**（不写 `BEGIN...END`），注释说明"这样在 mysql CLI / `mysql -e` 里执行不需要切换 DELIMITER" |
| 撤签 | **已实现** | `POST /workflow/sign/record/revoke`（`WorkflowSignController.java:85-86`）；`SignServiceImpl.java:117` 写 `signType=TYPE_REVOKE`（`=9`） |
| 有效签名判定 | **已实现** | `SignServiceImpl.java:142,162`（"最后一条是撤销则该节点视为未签"）；`GET /workflow/sign/record/effective`（`:106-107`）；打印侧 `signService.effectiveSignByTask(businessId)`（`PrintServiceImpl.java:131`） |
| 签名人/时间由服务端写入 | **已实现** | `mySign` 与 `authz-check.ps1:201` 断言"记录里的签名人就是当前登录用户（服务端写入，客户端改不了）" |
| 任务归属守卫（**无 admin 放行**） | **已实现** | `BE/.../sign/guard/TaskOwnershipGuard.java`（65 行）；`DocViewGuard.java:23-24` 注释明确区分："签名是'人证'，管理员也不能替别人签" |
| 越权防护 | **已实现** | `AccessCheckMapper.xml` 的 `countTaskOwnerHit`（三路：`ACT_RU_TASK.ASSIGNEE_` / `ACT_RU_IDENTITYLINK` / `t_workflow_todo.cur_handler`）；`authz-check.ps1` 14 条断言 |
| 预存签名 CRUD + 设默认（默认唯一） | **已实现** | 表 `t_user_sign_preset`（`BE/sql/二开-预存签名.sql`：`is_default char(1)`、`status char(1)`、`del_flag`、`KEY idx_user (user_id, del_flag)`）；接口 `WorkflowSignController.java:114-150`（list / default / POST / PUT / default/{id} / DELETE）；页面 `UI/views/system/user/profile/mySign.vue`（挂在个人中心 Tab，`profile/index.vue:55`） |
| 签名在打印件的呈现 | **已实现** | `print/index.vue:69-72`（`signUrls[p.taskId]` → `<img>`，无签名显示"（签名）"占位）；`:344-362 loadSignImages`（相对路径直连 / fileId 走 blob）；后端 `PrintServiceImpl.java:263-264` |

### 7.3 2.0 不应重复立项的清单（去重结论）

**V1 已交付 → 2.0 PRD 不应再列**：

- 简化流程设计器（节点清单 / 条件分支 / 并行会审 / 校验 V-0..V-12 / 编译 / 发布 / 版本 / 回滚 / 草稿）；
- 表单打印（聚合接口 / A4 预览页 / 签批栏 / 附件清单 / 页脚 / 打印留痕 / 打印模板配置页 / 字段映射 / 水印）；
- 签名（手写采集 / 预存签名 / 节点策略 / 只追加记录 / 哈希链 / 撤签 / 打印呈现 / 用印盖章）；
- 二开控件 6 个（金额 / 计算 / 分组标题 / 说明文字 / 部门 / 人员）+ 编号 + 节点级字段只读（含服务端强制）。

**仍然缺的（真 2.0 议题）**：

- `design-signature`（表单内嵌签名位，仅枚举占位）；
- 节点字段权限的 `required` / `hidden`（表有列，`SimpleFlowServiceImpl.collectReadonly` 只写 `readonly='1'`，`required`/`hidden` 硬编码 `'0'`，`:253-259`）；
- 草稿自动保存（30s/失焦）；
- `paper` / `orientation` / `show_cc_node` / `logo_file_id` 的实际生效；
- 可发起范围（§4）；
- 简化流程 ↔ 模板的自动绑定（§3.10）；
- 多套内置打印模板（§5.9）；
- 打印模板可视化拖拽（V1 明确后置到 P1/P2）。

---

## 8. 数据库与迁移惯例

### 8.1 SQL 文件组织方式

**现状：双轨制，且已分叉。**

| 轨 | 文件 | 内容 | 是否含 V1 二开对象 |
| --- | --- | --- | --- |
| **全量基线** | `BE/sql/table.sql`（126,249 字节，2563 行） | 所有**存量**表的 `DROP + CREATE` | **❌ 完全没有** |
| **全量数据** | `BE/sql/data.sql`（77,142 字节） | 所有**存量**初始数据（含 `sys_menu` 一条巨型 INSERT） | **❌ 没有 V1 二开菜单** |
| **增量（V1 二开）** | `BE/sql/二开-*.sql`（8 个文件） | 表 / 触发器 / 菜单 | ✅ 全部在这里 |

**逐项核对"V1 二开对象在不在 `table.sql`"**（全表 grep，8 个模式全部零命中）：

| 对象 | 在 `table.sql` | 在增量文件 |
| --- | --- | --- |
| `t_template_print_template` | ❌ | `二开-表单打印.sql:10-36` |
| `t_print_log` | ❌ | `二开-表单打印.sql:38-53` |
| `t_flow_simple` | ❌ | `二开-简化流程设计器.sql:7-29` |
| `t_flow_simple_history` | ❌ | `二开-简化流程设计器.sql:31-48` |
| `t_workflow_sign_record` | ❌ | `二开-签名记录.sql` |
| `t_user_sign_preset` | ❌ | `二开-预存签名.sql` |
| `t_template_node_field_auth` | ❌ | `二开-节点字段权限.sql:12-26` |

> **⇒ 新表 DDL 应放在哪？** **放新的增量文件**，命名沿用 `二开-<功能名>.sql`（证据：上述 8 个文件的一致做法 + 每个文件头部的注释"只新增表，不改动任何既有表，可安全地在业务库 `rad_oa` 执行"）。
> **是否需要在 `table.sql` 里补一份？** 现状**没有**，说明项目选择了"增量即真相"。但这带来一个真实风险：**新环境从 `table.sql` + `data.sql` 初始化会缺 7 张表 + 3 条菜单**。2.0 应显式表态（推荐：**同步进 `table.sql`**，或提供 `sql/init-all.ps1` 之类的串联脚本）。**这是一个需要 2.0 PRD 决策的惯例分歧，不是纯技术细节。**

**触发器惯例**：`二开-签名防篡改.sql` —— 只新增触发器、`DROP TRIGGER IF EXISTS` 后再建、**单语句 `SIGNAL`（不写 `BEGIN...END`）**、并在文件头写明"运维须知：本触发器会让清理测试数据变得不可能；确需清理时先删触发器"（`:14-17`）。

**幂等惯例**：
- 建表用 `DROP TABLE IF EXISTS` + `CREATE TABLE`（全部 8 个文件一致）；
- 建菜单用 `DELETE FROM sys_menu WHERE menu_id IN (...)` + `INSERT`（`二开-打印模板菜单.sql:15-19`）；
- 建触发器用 `DROP TRIGGER IF EXISTS` + `CREATE TRIGGER`。

### 8.2 字段类型惯例（逐条归纳，均来自 `sql/table.sql` 与增量文件）

| 惯例 | 具体 | 证据 |
| --- | --- | --- |
| **主键** | `varchar(64) NOT NULL`，应用侧生成 UUID（`IdUtils.fastSimpleUUID()` / `UUID.randomUUID().toString().replace("-","")`） | `table.sql:2147`；`TemplateServiceImpl.java:136,168`；`SimpleFlowServiceImpl.java:337-339`；`PrintServiceImpl.java:462-464` |
| **标志位** | `char(1)`，**'0'=否 / '1'=是**（与字面相反于常见直觉，注意注释一律写明） | `enable_flag`/`del_flag`/`attach_flag`/`main_text_flag`/`message_notice_flag`（`table.sql:2155-2159`）；`readonly/required/hidden`（`二开-节点字段权限.sql:18-20`） |
| **逻辑删除** | `del_flag char(1) DEFAULT '0'`（**0-未删除 / 1-已删除**） | `table.sql:2159`；`t_template_print_template`（`二开-表单打印.sql:27`）；`t_flow_simple`（`二开-简化流程设计器.sql:20`） |
| **启用标志** | `enable_flag char(1) DEFAULT '1'`（**1=启用**，注意与 `t_template.enable_flag DEFAULT '0'` 的差异） | `t_template_dynamic_form`（`table.sql:2197` DEFAULT '1'）vs `t_template`（`:2158` DEFAULT '0'）；`二开-表单打印.sql:26` DEFAULT '1' |
| **审计列** | `create_id varchar(64)` + `create_by varchar(50)` + `create_time datetime` + `update_id varchar(64)` + `update_by varchar(50)` + `update_time datetime` | 全表一致（`table.sql:2161-2166`、`二开-简化流程设计器.sql:21-26`） |
| **排序** | `sort int(11) DEFAULT NULL`（或 `DEFAULT '0'`） | `t_template.sort`（`table.sql:2160`）、`t_template_type.sort`（`:2235`） |
| **备注** | `remark varchar(1000)`（业务表）/ `varchar(400)`（分类表）/ `varchar(500)`（菜单） | `二开-简化流程设计器.sql:19`、`table.sql:2238`、`:1460` |
| **大文本** | `longtext`（表单 JSON / 流程 JSON / field_map / 历史快照） | `table.sql:2196`（content）、`二开-简化流程设计器.sql:13,38`、`二开-表单打印.sql:19` |
| **时间默认** | 部分表用 `datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP`（旧表），新表统一用 `sysdate()` 在 SQL 里写 | `table.sql:2182,2184`（旧）；`二开-表单打印.sql` 无 ON UPDATE；`FlowSimpleMapper.xml:67,86` 用 `sysdate()` |
| **字符集/引擎** | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` | 全部表一致 |
| **表名前缀** | 业务表 `t_`；系统表 `sys_` | `t_template*`、`t_workflow_*`、`sys_menu` |
| **索引命名** | 无强规则：`temp_id_idx` / `template_id_idx` / `idx_biz` / `idx_template` / `uk_def_key_version` / `uk_node_field` | `table.sql:2186,2227`；`二开-表单打印.sql:35,53`；`二开-简化流程设计器.sql:28,47` |
| **字段命名** | 全小写 + 下划线 | 全表一致（**例外**：`table.sql` 里 `t_code_config_rule` 等历史表混用驼峰，见 V1 PRD `PRD:280`） |

### 8.3 是否需要增量 SQL：**是，且必须有**

**理由**（从现状反推）：
1. V1 二开的 7 张表 + 菜单全部走增量，**没有一条进 `table.sql`/`data.sql`**；
2. 增量脚本头部**统一声明"可安全地在业务库 `rad_oa` 执行"**（`二开-简化流程设计器.sql:4`、`二开-表单打印.sql:5`、`二开-节点字段权限.sql:5`）—— 说明**生产库已存在数据，不能重建**；
3. `DEV-ENV.md:45` 警告 MySQL 与另一个项目共用实例、"不要随意改 root 口令或删库"。

**2.0 交付时的 SQL 交付物清单（建议）**：

| 文件 | 内容 |
| --- | --- |
| `sql/二开-<2.0功能A>.sql` | 新表 DDL + 索引 + `DROP ... IF EXISTS` |
| `sql/二开-<2.0功能A>-菜单.sql` | `DELETE` + `INSERT sys_menu`（菜单 + 按钮） |
| `sql/二开-<2.0功能A>-数据.sql`（如需） | seed 数据（如内置打印模板走"预置数据行"路线时） |
| `sql/table.sql` + `sql/data.sql` | **必须同步补齐**（否则新环境初始化缺对象）—— 现状未做，2.0 应补 |
| 触发器（如需） | 单语句 `SIGNAL` 写法 |

---

## 9. 测试与验证惯例

### 9.1 现有工具清单

**`tools/` 根目录（回归/验收脚本，全部跑在真实环境上）**：

| 脚本 | 用途 | 断言数 | 证据 |
| --- | --- | --- | --- |
| `tools/oa-login.ps1` | 重新登录拿 token（**跑其他脚本前必须先跑**） | — | `DEV-ENV.md:136,213-217` |
| `tools/flow-regression.ps1`（16,127 字节） | 简化流程组合矩阵回归 | **20 条** | `DEV-ENV.md:139`；脚本内部 `Assert-That`（`:63`）、用例 `testCondition`（`:183-188`，断言"两条条件同时为真只走一条"）、`testParallel`（`:191-221`，断言"勾选 4 个部门 → 4 个并行任务""全部会审完成后流程办结"） |
| `tools/sign-feature-check.ps1`（22,592 字节） | 签名组件验收 | **44 条** | `DEV-ENV.md:142`；脚本内 `:198` 起为 AC-29 段（预存签名新增/默认/停用/重启用）；**会写入不可删的签名记录**（触发器挡住清理） |
| `tools/authz-check.ps1`（11,766 字节） | 越权防护验收（PRD 11.3 / AC-35） | **14 条** | `DEV-ENV.md:145`；脚本内 `:151-176`（打印数据/打印留痕/打印记录 的 403 与 200）、`:176-201`（签名越权/撤签越权/本人签名 200） |
| `tools/rsa-encrypt.js` | 取 RSA 加密后的口令（构造登录请求用） | — | `DEV-ENV.md:319` |
| `tools/basic-test-form-content.json` + `tools/build-basic-test-form.js` | 动态表单夹具（构造 schema） | — | 文件存在 |
| `tools/PwCheck.java` | 口令校验小工具 | — | 文件存在 |
| `tools/start-rabbitmq.ps1` / `setup-rabbitmq.ps1` / `run-rabbitmq.cmd` | RabbitMQ 启停/初始化 | — | 文件存在 |

**`tools/audit/`（前端静态审计闸门）** —— 6 个脚本 + 自检 + 基线（`tools/audit/README.md:13-23`）：

| 脚本 | 查什么 | 门禁 |
| --- | --- | --- |
| `audit-then-loaders.js` | `.then` 取数没有 `catch` → loading 永远 true | `zero` |
| `audit-async-loaders.js` | `async/await` 取数没有 `try/catch` | `zero` |
| `audit-silent-catch.js` | 空 `.catch(() => {})` 全量清点 | `baseline`（信息性） |
| `audit-loading-pairs.js` | **AST 强判据**：每个 loading 标志必须有配对复位，成功路径复位必须无条件 | `baseline` |
| `audit-undeclared-writes.js` | 给未声明的 `this.X` 赋值（Vue 2 非响应式） | `baseline` |
| `audit-template-refs.js` | 模板绑定里引用不存在的名字（渲染 `undefined`） | `baseline` |

**运行方式**（`tools/audit/README.md:6-11`）：
```powershell
node tools/audit/run-all.js           # 跑全部：先自检，再对比基线
node tools/audit/run-all.js --update  # 用当前结果重写基线（要有意识地做）
node tools/audit/run-all.js --no-selftest
```

**门禁语义**（`README.md:24-28`）：`zero` 必须为 0；`baseline` 必须等于 `baseline.json` 记录数，变化必须是有意识的；`info` 只打印。

**四条形而上但极实用的约定（都是踩出来的，`README.md:50-60`）**：
1. **边界不能靠猜，要探针实测**（Vue 模板表达式散在 7 个字段里）；
2. **判据要严到 0 误报**（同一条判据收紧过 6 次：2098 → 43 → 42 → 13 → 1 → 0）；
3. **必须自带阳性/阴性对照**（`--selftest` 把已知阳/阴性样本写临时目录再跑自己）；`run-all.js` 会把**没有 `--selftest` 的审计显式标为"未自证"，而不是当成通过**（`:40-42`）；
4. **不要用 PowerShell 做源码的读-改-写**（ANSI 码页会把中文变乱码、多字节序列可能吃掉引号）；一律用 Node 的 `readFileSync`/`writeFileSync`。

**已知误报清单**（看到不是回归）：`README.md:62-70` —— `InnerLink/index.vue`（复位在 iframe onload 里）、`login.vue`/`register.vue`（成功路径故意不复位）、`SidebarItem.vue` 的 `onlyOneChild`（vue-element-admin 习语，**动它反而可能触发无限更新循环**）、`audit-undeclared-writes` 的"无害暂存"档。

### 9.2 DEV-ENV.md 中的固定构建/启动/验证命令（摘要）

**重启设备后先跑两条**（`DEV-ENV.md:8-12,118-136`）：
```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1      # 一键起全部服务（幂等）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1 # 重新登录拿 token
```

**变体**：`-Only Infra`（只起 MySQL/Redis/MQ）、`-SkipFrontend`、`-Rebuild`；停止用 `.\stop-env.ps1`（加 `-IncludeInfra` 连基础设施一起停）。

**后端构建**（`DEV-ENV.md:196-204`）：
```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests install                       # 全量
mvn -B -DskipTests -pl ruoyi-admin clean package # 单模块（**要 clean**）
# 运行必须用 JDK 11 的 java：
& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-admin\target\ruoyi-admin.jar
```

**前端**（`DEV-ENV.md:205-209`）：
```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run dev        # http://localhost/ （必须 npm.cmd）
npm run build:prod     # 产物 dist/
```

**5 条硬约束（每条都有踩坑记录）**：

| # | 约束 | 落点 |
| --- | --- | --- |
| 1 | 本机**没有 `pwsh`**，只有 Windows PowerShell 5.1；`.ps1` 必须存 **UTF-8 with BOM**；不能写 `/** */` 块注释；`"$var:%"` 要写成 `"${var}:%"` | `DEV-ENV.md:14,69,151-155` |
| 2 | **JDK 11 由 Maven toolchains 自动选**，但 `mvn spring-boot:run` 会用运行 Maven 的 JVM（JDK 21）→ **运行必须用 JDK 11 的 java.exe** | `DEV-ENV.md:71-98` |
| 3 | **打 fat jar 之前必须先停后端**，否则 `spring-boot:repackage` 无法替换被占用的文件但**仍打印 BUILD SUCCESS**（已踩过，代价很大）；**打包后必须核对 jar 时间戳** | `DEV-ENV.md:295-308` |
| 4 | **后端必须用 `Start-Process` 脱离进程启动**，不能用"后台任务 + `&`"（任务结束会把 java 一起杀掉，表现为 `exit code: 1` + **没有异常栈**） | `DEV-ENV.md:374-389` |
| 5 | **用 `| Select-Object -First N` 截断脚本输出 = 提前掐断管道**，脚本的 `finally` 清理跑不完，会留下测试数据（踩过：留下几枚预存签名） | `DEV-ENV.md:156-157` |

**另外两条与验证方法学有关（`DEV-ENV.md:309-341`）**：
- `/workflow/**` 下用"未认证请求返回 401"**验证不了路由**（RuoYi 安全过滤器在路由之前拦截，`/workflow/print/data/x` 与 `/workflow/print/__不存在的路径__` 返回完全一样）→ 必须拿 token；
- 用浏览器工具断言页面内容时**必须等终态**（`AppMain.vue` 的 `<transition mode="out-in">` 会让"URL 变了、面包屑变了，但主内容还是上一页"）→ **读该页独有的根元素**（如打印页的 `.print-page`）。

### 9.3 【2.0】交付时"必须跑哪些验证"的候选清单

| # | 验证项 | 命令 / 方式 | 为什么必须 |
| --- | --- | --- | --- |
| V1 | **前端静态审计闸门** | `node tools/audit/run-all.js` | `then-loaders` / `async-loaders` 是 `zero` 门禁；2.0 新增页签会引入大量异步取数 |
| V2 | **流程引擎回归（20 条）** | `powershell -File .\tools\flow-regression.ps1` | 2.0 若动模板页签 3（内嵌设计器），必须确认条件分支/并行会审未回归 |
| V3 | **签名功能验收（44 条）** | `powershell -File .\tools\sign-feature-check.ps1` | ⚠ **会写入不可删的签名记录**（触发器挡住清理）→ **必须在专用夹具单据上跑，且不要用 `| Select-Object -First N` 截断** |
| V4 | **越权防护验收（14 条）** | `powershell -File .\tools\authz-check.ps1` | 2.0 若加"可发起范围"（§4），需要**在本脚本基础上新增断言**：非范围内用户调 `getNewStartTemplateList` 不应看到该模板 |
| V5 | **模板页签回归（新增）** | 手工 + 新增脚本 | 四页签重构（§1.7）属于"改了 5 个组件的挂载方式"，需要断言：① 每页签校验失败能自动切过去；② 提交后 `t_template` + 3 张子表的行数与内容正确；③ **编辑后旧行 `del_flag='1'`、新行 id 变化、关联表（print_template / node_field_auth）是否失联** |
| V6 | **简化流程 ↔ 模板绑定回归（新增）** | 新增脚本 | §3.10 的 A1-A9 落地后，必须断言：发布流程后 `t_template.def_key` 已被回写；`t_flow_simple` 与 `t_template` 的关联可双向查到；**模板被编辑（换 id）后关联不丢** |
| V7 | **打印回归（新增/扩充）** | 新增脚本或扩充 `authz-check.ps1` | §5.9 落地后断言：① 3 种内置模板标题各自正确；② 资金审批单能出签批栏（当前 `isBuiltinTpl` 会强制关掉）；③ 已有配置模板的单据打印结果**不变** |
| V8 | **AC-39 存量不回归** | `flow-regression.ps1` + 手工全链路 | V1 PRD 的 AC-39 明确要求"存量模板与存量流程在升级后功能不回归（发起/审批/盖章/附件全链路回归通过）" |
| V9 | **库对象齐备性** | 对比 `table.sql`+`data.sql` 与增量文件的并集 | §8.1 的分叉问题：新环境初始化会缺对象 |
| V10 | **打包产物校验** | `(Get-Item ruoyi-admin\target\ruoyi-admin.jar).LastWriteTime` | `DEV-ENV.md:306` 明确要求，不要只看 BUILD SUCCESS |
| V11 | **前端构建** | `npm.cmd run build:prod` | 新控件/新页签可能只在 dev 下正常（`public/` 静态资源无 HMR，见 `DEV-ENV.md:287-288`） |
| V12 | **浏览器端终态断言** | 读页独有的根元素（如 `.print-page`） | `DEV-ENV.md:334-341`；2.0 的页签切换本身也是 `router-view` 外的状态，容易出现"URL 变了内容没变"的假象 |
| V13 | **主题令牌不裸写色值** | grep 新增 `.vue` 里的裸色值 | `DEV-ENV.md:289-291`：必须用 `var(--oa-color-primary)`（令牌在 `UI/src/assets/styles/oa-tokens.scss`） |

---

## 10. 2.0 需求落点速查表

> **填表规则**：
> - 「现有落点」给**可直接复用的文件:行**；不存在则写 `无`。
> - 「缺口」写**具体缺什么**，不写"待完善"。
> - 「预估改动量」：**S** ≤ 1 人日 ｜ **M** 2–5 人日 ｜ **L** 6–15 人日 ｜ **XL** > 15 人日。
> - 「需求」列：V1 已分配编号的直接引用；2.0 新诉求用 `【2.0-XX】` 占位（**待 2.0 PRD 正式分配 `REQ-*`**）。

| # | 需求 | 现有落点（文件:行） | 缺口 | 量 |
| --- | --- | --- | --- | --- |
| 1 | 【2.0-01】模板新增页拆为四个页签 | `UI/views/workflow/template/add.vue:1-45`（5 段纵向）；5 个子组件 `basic-info.vue` / `business-form-info.vue` / `main-text.vue` / `attachment-info.vue` / `message-notice.vue` | 无 `el-tabs` 容器；`Promise.all` 一次性校验（`add.vue:108-115`）在隐藏页签上无法定位错误；`activeNames/activeName` 是死字段（`:66-67`） | M |
| 2 | 【2.0-02】页签 3「流程配置」承载简化流程设计器 | 设计器 `UI/views/workflow/simple-flow/designer.vue`（1367 行，独立路由 `/workflow/simple-flow/designer`，`UI/router/index.js:161-162`） | 组件未抽成可嵌入形态；工具条（`designer.vue:4-43`）含 4 个全局输入需按 embedding 隐藏；「关联表单」下拉绑的是 `t_template_dynamic_form`（`:899-907`）而非 `template.formId` | **L** |
| 3 | 【2.0-03】模板与简化流程自动绑定 + 回写 `defKey` | 唯一关联点：`SimpleFlowServiceImpl.syncNodeFieldAuth:225-238`（**反向查询** `templateService.listTemplate(defKey)`） | ① 方向是反的（流程找模板，不是模板找流程）；② `t_template` 无 `simple_flow_id`/`flow_mode` 列；③ 发布接口无 `templateId` 参数（`SimpleFlowController.java:124-131 PublishBody`）；④ **`updateTemplate` 会换新 id**（`TemplateServiceImpl.java:154-176`），直接回写会丢失全部 `template_id` 关联 | **L** |
| 4 | REQ-DESIGN-001/002/003/004/005/007/008/009/010/011（简化设计器全套） | `BE/.../simple/**`（compiler 761 行 / validator 337 行 / service 340 行 / controller 132 行 / model 207 行）；`UI/views/workflow/simple-flow/**`（index 270 / designer 1367 / FlowTree 646 / ConditionEditor 233） | **已交付，2.0 不重复立项**。剩余：草稿自动保存（30s/失焦，V1 PRD 11.5）；`status` 只有 2 态（无"已下架"）；删除无"被模板引用"守卫 | S（补缺） |
| 5 | 【2.0-04】"谁可以提交该审批"（可发起范围） | **无**。`TemplateMapper.xml:148-157` 只过滤 `del_flag='0' and enable_flag='1'`；全仓 grep `submitScope` 零命中 | 需要：新表 `t_template_submit_scope`（或 `t_template` 增 `submit_scope_type`）+ Service 层按 `userId/deptId/roles` 过滤（`TemplateServiceImpl.listNewStartTemplate:206`）+ 模板页签 4 配置 UI（可复用 `DesignUserSelect.vue` / `DesignDeptSelect.vue`）+ DTO/保存链路补 `submitScope` | **L** |
| 6 | 【2.0-05】新增两套内置打印模板《集团资金审批单》《集团事项类打印审批单》 | 内置模板定义：`PrintServiceImpl.builtinTemplate:391-402`；标题常量 `:466 DEFAULT_TITLE`；版式常量 `:469-501 DEFAULT_FIELD_MAP`；前端复制品 `print-template/index.vue:156-173`；内置模板开关强制 `print/index.vue:182-200` | ① 内置模板**全局唯一一套**，无法按单据类型选；② `isBuiltinTpl` **强制关掉签批栏**（`:192-200`），而资金审批单需要签批栏；③ `paper`/`orientation`/`show_cc_node` **只存不用**；④ 资金审批单的字段名与合同不同，需新定义 field_map；⑤ 需要"怎么选内置模板"的规则（建议 `t_template` 增 `builtin_print_key`） | **L** |
| 7 | REQ-PRINT-001..009（打印全套） | `BE/.../print/**`（controller 139 / impl 520 / model 145）；`UI/views/workflow/print/index.vue`（648）；`print-template/index.vue`（410）；`plugins/printPreview.js`；`BE/sql/二开-表单打印.sql`；`BE/sql/二开-打印模板菜单.sql` | **已交付**。剩余：`logo_file_id` 无 UI 无代码；`formSchema` 是永不赋值的死字段（`PrintData.java:67-68`）；待办列表行无打印入口（`todo/todo-list.vue`） | S（补缺/清死字段） |
| 8 | REQ-PRINT-010（PDF 归档 + 可视化模板） | `UI/package.json` 有 `vue-plugin-hiprint@0.0.56`（V1 PRD `:681` 记录"已装未用"） | 未实现；V1 明确后置 P1/P2 | XL（若做） |
| 9 | REQ-SIGN-001..007（签名全套） | `BE/.../sign/**`（controller 145 / service 230 / SignChain 123 / SignPolicyReader 157 / TaskOwnershipGuard 65 / TaskSignGuard 69）；`UI/components/SignaturePad/**`（index 567 / canvas 117）；`mySign.vue`；`BE/sql/二开-签名{记录,防篡改}.sql`、`二开-预存签名.sql` | **已交付**。剩余：`design-signature` 表单内嵌签名位（仅 `ComponentTypeEnum.java:22` 枚举占位） | M（补 1 个控件 × 11 处登记） |
| 10 | REQ-FORM-001（金额/分组标题/说明/只读计算） | `DesignAmount.vue` / `DesignCalc.vue` / `DesignSection.vue` / `DesignText.vue`；登记处见 §2.3 的 11 行矩阵 | **已交付**；建议顺手清 `config.js:722-730` 的过期注释（说"两个都是纯排版控件"，实际 4 个） | S |
| 11 | REQ-FORM-002 / REQ-DESIGN-006 的 `required`/`hidden` 档 | 表有列：`二开-节点字段权限.sql:19-20`；但写入方只写只读 | `SimpleFlowServiceImpl.collectReadonly:253-259` 把 `required`/`hidden` **硬编码为 `'0'`**；`TemplateNodeFieldAuthMapper.xml:24-29 selectReadonlyFields` 只查 `readonly='1'` | M |
| 12 | REQ-DATA-001（新增表） | 7 张表已建：`t_flow_simple`、`t_flow_simple_history`、`t_template_print_template`、`t_print_log`、`t_workflow_sign_record`、`t_user_sign_preset`、`t_template_node_field_auth` | **已交付**。但**全部不在 `sql/table.sql`**（§8.1 分叉） | S（补齐基线） |
| 13 | 【2.0-06】模板版本语义修正（编辑换 ID 导致关联失联） | `TemplateServiceImpl.updateTemplate:154-176`（旧行 `enable_flag='0'`+`del_flag='1'`，新行新 UUID） | 所有 `template_id` 外键表（`t_template_print_template`、`t_template_node_field_auth`）在模板编辑后**失联**；前端编辑页拿的是废弃行 id（`add.vue:82-104`），提交后不更新 id | **L**（涉及表结构与迁移） |
| 14 | REQ-DESIGN-010（图形化条件组） | `UI/views/workflow/simple-flow/ConditionEditor.vue`（233 行）；编译器 `SimpleFlowCompiler.java:694-699 toExpression`；校验 V-8/V-9/V-10（`SimpleFlowValidator.java:249-278`） | **已交付** | — |
| 15 | 【2.0-07】`el-select multiple` 不支持作并行分支来源 | `UI/utils/formSchema.js:10 MULTI_TAGS = ['el-checkbox-group']`（**只此一项**） | `multiFields` 只认 `el-checkbox-group`；`el-select multiple` 会被 V-12 拒掉（`SimpleFlowValidator.java:316-319`） | S |
| 16 | 【2.0-08】发布事务的引擎/业务库一致性 | `SimpleFlowServiceImpl.publish:141-217`（`@Transactional`） | Flowable 部署与业务表写入**无补偿**；第 5–7 步失败可能留"引擎已部署、`t_flow_simple` 未更新"的悬空态。**现状未做** | M |
| 17 | 【2.0-09】`defKey` 格式校验重复实现 | `SimpleFlowValidator.java:94-97` 与 `SimpleFlowCompiler.java:147-148`（两处各写一遍正则） | 抽成一处常量/工具方法（D-类地雷的又一例） | S |
| 18 | 【2.0-10】打印版式常量前后端重复（D-4） | 后端 `PrintServiceImpl.java:469-501`；前端 `print-template/index.vue:156-173` | 两份同源常量，仅靠注释约束；建议改为后端提供接口由前端拉取 | S |
| 19 | 【2.0-11】`data_scope` 不覆盖业务表 | `@DataScope` 仅 5 处（`SysDeptServiceImpl:67`、`SysUserServiceImpl:81,101,114`、`SysRoleServiceImpl:57`） | 若 2.0 要做"按部门看单据/按范围可发起"，**不能复用** RuoYi 的 `data_scope`，需自建（参照 `DocViewGuard` + `AccessCheckMapper.xml` 的四路 union 范式） | M |
| 20 | 【2.0-12】`sql/table.sql` 与增量 SQL 分叉（新环境初始化缺对象） | `BE/sql/table.sql`（2563 行，**无任何 V1 二开对象**）；8 个 `二开-*.sql` | 新环境用 `table.sql`+`data.sql` 初始化会缺 7 张表 + 3 条菜单 + 2 个触发器；**需 2.0 PRD 表态**（推荐补齐 `table.sql`/`data.sql`，或提供串联初始化脚本） | M |
| 21 | 【2.0-13】待办列表行无打印入口 | `todo/todo-list.vue` 无 `icon-printer` / `openPrintPreview`；对照 `done/index.vue:64,155`、`my-draft/index.vue:58,119` | 一行按钮 + 一个方法（照抄 `done/index.vue:143-155`） | S |
| 22 | 【2.0-14】`PrintData.formSchema` 死字段 | `PrintData.java:67-68`（声明）；全仓 `setFormSchema` 零命中 | 要么删，要么赋值；现状是永不赋值的噪声，会误导 2.0 的接口文档 | S |
| 23 | 【2.0-15】权限点命名不一致（`simpleFlow` 驼峰 vs 存量全小写） | 存量 14 个全小写（`data.sql`）；V1 新增用驼峰 `workflow:simpleFlow:*`（`二开-简化流程设计器-菜单.sql:7,10-13`） | 需在 2.0 PRD 的命名规范章节定稿；**存量不改**（会打断已授权角色） | S |
| 24 | 【2.0-16】`vform` 死代码 1.36 MB | `UI/main.js:48-50,81` 注册；`UI/components/vform/VFormDesigner.umd.min.js`（1,364,549 B）+ `.css`（90,147 B）；**全仓无模板使用** | 若无用应删（省 1.45 MB bundle）；若要用则须重做全部 6 个二开控件与两条渲染路径 | S（删）/ XL（用） |
| 25 | 【2.0-17】模板无分类时在新启页被静默丢弃 | `TemplateServiceImpl.listNewStartTemplate:233-243`（结果由 `templateTypList` 驱动，`:235-237 continue` 丢弃空分类模板） | 加"可发起范围"后**更容易误伤**；应改为"未分类"归组展示或给出显式提示 | S |
| 26 | 【2.0-18】资金/事项类单据的**表单模板**本身 | 动态表单设计器 `UI/views/tool/build/index.vue`；6 个二开控件已就绪（§2.3） | 需求侧产物（不是代码缺口）：需在系统里建两张表单 + 两个模板 + 两条流程；`DesignAmount`/`DesignCalc`/`DesignSection` 可直接复用 | S（配置）/ — |
| 27 | 【2.0-19】`design-serial-no` 完整度未核验 | `render.js:4,144`、`config.js:697-720` 有登记；组件本体在 `UI/components/SerialNo`（**本报告未逐行核验**） | **未验证**：需补 `view-form.vue` / `RightPanel.vue` / `ComponentTypeEnum` 三处核验 | S |
| 28 | 【2.0-20】打印留痕是"尽力而为"非门禁 | `print/index.vue:284-308 doPrint()`（`writeLog` 的 catch 只 warning）；后端 `PrintLogMapper.insert` 无重试 | 若审计要求"必留痕"，需改为先写日志后打印（或失败阻断） | S |
| 29 | 【2.0-21】`paper` / `orientation` / `show_cc_node` / `logo_file_id` 未生效 | `paper`/`orientation` 只存不用（前端 `A4_CONTENT_PX` 硬编码 `print/index.vue:122-123`）；`show_cc_node` 存了前端未读；`logo_file_id` 无 UI 无代码 | 四处补链路；`paper/orientation` 需参数化页数与 `print-a4.scss` | M |
| 30 | 【2.0-22】前端静态审计闸门纳入 2.0 交付门禁 | `tools/audit/run-all.js`（6 脚本 + `baseline.json` + `--selftest`） | 现状是**独立工具**，未与 PRD 验收标准挂钩。建议把"`run-all.js` 全绿"写进 2.0 的 DoD | S |

---

## 附录 A：本报告未验证 / 不确定项（诚实清单）

| # | 项 | 为什么未验证 |
| --- | --- | --- |
| A-1 | `design-serial-no` 的完整落地度 | 只核了 `render.js:4,144` 与 `config.js:697-720` 两处登记；未打开 `UI/components/SerialNo` 组件本体，也未核 `view-form.vue` / `RightPanel.vue` / `ComponentTypeEnum` 三处 |
| A-2 | `FlowSimpleHistoryMapper.xml` 的 `publish_time` 是否真的被赋值 | 只读了 `FlowSimpleMapper.xml`（100 行，确认 insert 用 `sysdate()`），未读 history 的 XML |
| A-3 | Flowable 部署与 Spring 业务事务的实际隔离级别 | `publish` 标注了 `@Transactional`，但引擎的事务管理配置在 `ruoyi-flowable` 的配置类里，未查 |
| A-4 | `t_template_print_template.paper/orientation` 是否有 CSS 层的隐性支持 | 只核了 `print/index.vue` 与 `PrintDialog.vue` 的 JS 层；`UI/assets/styles/print-a4.scss` 未逐行读 |
| A-5 | `t_code_config*` 流水号（合同编号 `HT`）与资金审批单的编号规则 | V1 PRD 引用为 `BusinessTypeEnum.java:8` / `CodeConfigRule.java:30`；本报告未逐一打开核对 |
| A-6 | 存量 `sys_role_menu` 里二开权限点的实际授权情况 | 需要查库，本报告只读源码 |
| A-7 | `t_flow_simple` / `t_flow_simple_history` 在现网库里的实际数据量与 defKey 命名实况 | 同上，需要查库 |
| A-8 | `ruoyi-seal` 的盖章链路（多枚章、骑缝章、水印）当前可用度 | V1 PRD 5.5-D14 记为"骑缝章链路未使用 `seamFlag`，实际不可用"；本报告未复核该结论 |
| A-9 | 前端 `UI/views/tool/build/RightPanel.vue`（998 行）的全部属性面板分支 | 只定向核了 6 个二开控件的行号（`:43-95,470-490,759-770`），未通读 |
| A-10 | 打印页 `print-a4.scss` 的分页规则实现度（V1 PRD 7.5 的 5 条规则） | 只核了 JS 层的 `measurePages()`（`:310-324`）与模板里的 `.no-print`、`.sign-block`；CSS 未逐行读 |

---

## 附录 B：本报告的跨章节地雷索引（2.0 设计时逐条过一遍）

| 编号 | 地雷 | 章节 | 一句话 |
| --- | --- | --- | --- |
| L-1 | **两条渲染路径** | §2.5 D-1 | 新控件必须在 `render.js` 与 `view-form.vue` **两处**适配，否则"拟稿好看、审批看不见" |
| L-2 | **模板编辑换 ID** | §1.5 / §3.10 A4 | `t_template.id` 每次编辑都变 → 所有 `template_id` 外键表失联 |
| L-3 | **流程与模板无自动绑定** | §3.6 | 只靠人工填 `def_key` 字符串；发布不回写 |
| L-4 | **内置打印模板全局唯一 + 强制不出签批栏** | §5.9 B1/B6 | 要加第二套内置模板，必须先解决"怎么选"与"内置模板也要能出签批栏" |
| L-5 | **可发起范围完全不存在** | §4.2 | 现状等价于任何登录用户可发起任何启用模板 |
| L-6 | **打印版式常量两份** | §2.5 D-4 / §5.6 | 后端 `DEFAULT_FIELD_MAP` 与前端 `DEFAULT_FIELD_MAP` 只靠注释约束 |
| L-7 | **`sql/table.sql` 与增量 SQL 分叉** | §8.1 | 新环境初始化会缺 7 表 + 3 菜单 + 2 触发器 |
| L-8 | **`updateTemplate` 的事务里 `insert` 而非 `update`** | §1.5 | 任何"编辑模板"的 2.0 功能都要先想清楚新旧 id 的传递 |
| L-9 | **`formData` 是字符串不是对象** | §2.4 / §5.5 | 三层 `JSON.parse` 兜底；手写夹具形状错会**静默**退回内置 field_map |
| L-10 | **`__slot__.options` 不是 `__config__`** | §2.4 | 选项 label 的唯一位置；前后端两处都在这里取 |
| L-11 | **`multiFields` 只认 `el-checkbox-group`** | §3.8 / §9 表 #15 | `el-select multiple` 不能作并行分支来源 |
| L-12 | **首节点不能用角色/动态选人** | §4.4 | 编译器生成 `${<节点id>_user}` 而 `startFlow` 不传该变量 → `Unknown property used in expression` |
| L-13 | **打开拟稿页自动发起流程** | §4.4 | `flow-form/index.vue:276-279`；调试时每次刷新多一个实例 |
| L-14 | **发布可能留下"引擎已部署、业务表未更新"** | §3.5 / §9 表 #16 | `publish` 无补偿 |
| L-15 | **签名记录不可删（触发器）** | §7.2 / §9.2 | 跑 `sign-feature-check.ps1` 会留下永久数据；不要用 `Select-Object -First N` 截断 |
| L-16 | **`vform` 死代码 1.45 MB** | §2.1 / §9 表 #24 | 删无害；用则等于重做 6 个控件 × 11 处登记 |

---

*本文件为 2.0 PRD 的现状勘察层。所有结论均可在源码中按所给 `路径:行号` 复核；未复核项已逐条列入附录 A。*
