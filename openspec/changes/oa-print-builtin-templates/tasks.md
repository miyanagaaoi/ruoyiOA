# Tasks

> 追溯：本清单每项任务标注其覆盖的 `REQ-PRINT-*` 与 `AC-*`。行为契约见 `specs/workflow/print-templates/spec.md` 与 `specs/workflow/print-rendering/spec.md`；技术决策见 `design.md`。
> 命令取自 `DEV-ENV.md`（本机只有 Windows PowerShell 5.1，用 `powershell`；前端必须用 `npm.cmd`）。
> **前置约束**：`t_template` 增列属 B1（`oa-form-flow-tabs`）与本变更共享的表结构变更，两批次的列变更必须同批次执行；本清单的增量脚本须能独立执行且不依赖 B1 的列（见 `design.md` D9）。

## 1. 内置版式常量与数据链路

- [x] 1.1 新增 SQL 增量文件承载 `t_template.builtin_print_key varchar(32) DEFAULT 'contract'`（不修改 `sql/table.sql` / `data.sql` 基线），并在空库与存量库各执行一次验证幂等：`cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master` 后对 `rad_oa` 库执行该脚本两遍均无报错，且 `SHOW COLUMNS FROM t_template LIKE 'builtin_print_key'` 一次命中、存量行为 `contract`。〔REQ-PRINT-011、AC-61〕
- [x] 1.2 完成 `t_template.builtin_print_key` 的**五处同步**：`sql/table.sql`、实体字段、Mapper 的 resultMap / insert 列 / update 列；验证方式是真实往返而非只看 DDL——保存一次带非默认 key 的模板后重新查询该模板，返回值等于保存值（漏任一处会表现为字段被静默丢弃）。〔REQ-PRINT-011、AC-61〕
  > **交付记录（与原文的一处偏离及理由）**：本条列的"五处"里 **没有改 `sql/table.sql`**。
  > ① 同文件 1.1 与 `design.md` D9 都明确写了"**不修改** `sql/table.sql` / `data.sql`"（PRD §8.4 方案 B：`table.sql`/`data.sql` 是上游原样基线，增量脚本才是唯一真源）；
  > ② B1 已按同一口径落地（14 列全在增量脚本里，`table.sql` 零命中）。
  > 故沿用 **B1 的"五处"口径**：`Template.java` 实体 + `TemplateMapper.xml` 的 resultMap / **select 列** / insert 列 / update 列（五处齐备，B1 已登记，本次以真实往返复核）。
  > 真实往返证据：`POST /workflow/print/builtinKey` 写 `fund` → 查库 `=fund` → `GET /template/template/list` 回读 `builtinPrintKey=fund`（`tools\print-builtin-check.ps1` §1.2）。
- [x] 1.3 后端建立按 key 索引的**标题注册表**（4 个 key），顶替原先的单一标题常量；验证：请求内置模板清单接口返回 4 条，且 `contract` 的标题与升级前逐字一致（零回归锚点）。〔REQ-PRINT-011、AC-61〕
- [x] 1.4 后端按 key 拆出 4 份版式字段映射（`contract` 沿用已实现的 13 行映射不改、`fund` / `matter` / `payment` 新增），并编写覆盖 4 套版式的**后端单元测试**：断言每套的标题、`sections` 结构与 PRD 附录 A 的栏目逐项对应。验证：`mvn -B -pl ruoyi-workflow test` 通过，测试用例数 ≥ 4（每套版式至少 1 条）。〔REQ-PRINT-011、AC-61〕
- [x] 1.5 内置模板构造改为接收版式 key 并返回该 key 的标题与版式；对未登记的 key **回退 `contract` 并记 warning**（不抛异常，理由见 `design.md` D2）；验证：单元测试覆盖 4 个合法 key 与 1 个非法 key，非法 key 的返回标题等于 `contract` 且日志出现 warning。〔REQ-PRINT-011、AC-61〕
- [x] 1.6 有效模板判定读取 `t_template.builtin_print_key` 并传入版式 key；补测试断言**优先级链未变**：显式 `printTplId` > 该单据模板下的启用行 > 内置，三种输入组合各一条用例。验证：`mvn -B -pl ruoyi-workflow test` 通过。〔REQ-PRINT-011、AC-61〕
- [x] 1.7 把 `builtinTemplate(key)` 的入参变更与读列逻辑同步到其**唯一调用点**并全仓确认无残留旧签名调用；验证：`cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master` 执行 `mvn -B -DskipTests -pl ruoyi-workflow install` 构建通过，且构建日志中无 `builtinTemplate` 相关的编译告警/错误。〔REQ-PRINT-011、AC-61〕
- [x] 1.8 更新打印模块的接口文档（新增/变更的 `builtin_print_key` 列语义、内置模板清单与内置版式映射两个读接口的请求与响应、以及“未知 key 回退 contract”的口径）；验证：文档中给出的示例请求可在带 token 的环境中原样执行成功（先跑 `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1` 拿 token；注意 `/workflow/**` 未认证请求一律 401，不能作为路由验证）。〔REQ-PRINT-011、REQ-PRINT-013、AC-61、AC-63〕

## 2. 内置版式真源收口（后端接口 + 前端去重）

- [x] 2.1 新增 `GET /workflow/print/builtinTemplates`（返回 4 个 key + 展示名称），权限点复用已存在的 `workflow:print:template`，**不新增权限点、不新增菜单**；验证：带 token 请求返回恰好 4 条且 key 集合为 `contract/fund/matter/payment`；无权限账号请求返回 403。〔REQ-PRINT-018、AC-61、AC-63〕
- [x] 2.2 新增 `GET /workflow/print/defaultFieldMap/{builtinKey}`（返回该 key 的字段映射），权限点同上；验证：对 4 个 key 各请求一次，返回的映射与 1.4 的单元测试断言逐项一致（同一真源、非第二份副本）。〔REQ-PRINT-013、AC-63〕
- [x] 2.3 删除前端 `print-template/index.vue` 中复制的内置字段映射常量，改为调用 2.2 的接口；验证：`cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` 后 `npm.cmd run dev` 启动，配置页“填入内置版式”按钮对 4 个 key 均能填入内容且与后端返回一致；同时用源码检索确认客户端不再存在等价的内置映射常量。〔REQ-PRINT-013、AC-63〕
- [x] 2.4 删除打印聚合响应中从未赋值的死字段 `formSchema`，并确认打印件字段表仍取单据自身保存的表单快照与生效模板的字段映射；验证：带 token 请求 `GET /workflow/print/data/{businessId}`，响应体不含 `formSchema`，且打印件字段非空（`formData` 是**字符串**，解析后值在 `valData`、标签与顺序在 `fields`）。〔REQ-PRINT-013、AC-63〕
- [x] 2.5 在配置页文档/页内说明中写明“内置版式常量以后端为唯一真源，前端不再持有副本；调整版式只改后端一处”；验证：按文档描述只改后端一处映射并重启后端，配置页“填入内置版式”与打印件同时反映该改动，且**无需重新构建前端**。〔REQ-PRINT-013、AC-63〕

## 3. 前端放开签批栏（本变更第一验收项）

- [x] 3.1 去掉客户端“内置模板一律关闭签批栏与附件清单”的判定，改为**一律读生效模板的配置值**（与“是否内置”解耦）；验证：`npm.cmd run dev` 后打开一张绑定 `fund` 版式、签批栏为开启的单据打印预览，`.print-page` 根元素内出现「集团职能部门」「集团分管领导」「集团董事长」三栏签名区（读取该页独有根元素判定挂载完成，不要用面包屑或通用容器）。〔REQ-PRINT-012、AC-62〕
- [x] 3.2 让 4 套内置版式各自携带签批栏与附件清单的推荐取值（`fund` / `payment` / `contract` 签批栏开启；`matter` 按版式需要开启），并补**前端测试**覆盖“内置模板 + 签批栏开启 → 输出签名区 / 内置模板 + 签批栏关闭 → 无签名区”两条用例。验证：前端测试命令全绿。〔REQ-PRINT-012、AC-62〕
- [x] 3.3 统一签批栏与附件清单的默认值语义：服务端的空值填充口径为唯一口径（空值 → 关闭），并把 DDL 默认值对齐到相同取值，**不改动已落库的存量行**（见 `design.md` D5）；验证：一条走接口写入、一条直接 SQL 写入（二者均不显式给值）的记录，读取到的签批栏与附件清单取值相同。〔REQ-PRINT-012、AC-62〕
- [x] 3.4 回归确认**显式关闭签批栏仍然生效**：对同一单据模板显式关闭签批栏后打印件不出现任何签名区；验证：打印预览内不出现签名区，且该结论写入 3.5 的文档记录。〔REQ-PRINT-012、AC-62〕
- [x] 3.5 更新打印模板配置说明文档：签批栏与附件清单由版式配置决定、与模板来源（内置/自定义）无关；并**明确记录**“升级后走内置版式的存量打印件可能新增签名区”这一有意行为变更及逐张人工确认的要求（见 `design.md` Risks）；验证：按文档步骤对至少一张存量内置版式单据完成确认，结论记入验收记录。〔REQ-PRINT-012、AC-62、AC-82〕

## 4. 纸张方向、Logo 与抄送栏链路补全

- [x] 4.1 打印件读取生效模板的纸张与方向，把原先硬编码的 A4 版心高度与页数测算参数化（保留“按像素测算、不依赖 CSS `counter(pages)`”的既有路线）；验证：A4 纵向打印件的页脚「第 X 页 / 共 Y 页」正确。〔REQ-PRINT-014、AC-64〕
- [x] 4.2 支持 A3 与横向：分栏与分页规则随纸张/方向变化；未配置纸张或方向时按 A4 纵向渲染；补**前端测试**覆盖 A4 纵向、A3 横向、未配置三种输入的排版参数与页数测算；验证：前端测试全绿，且 A3 横向实机打印预览页脚「第 X 页 / 共 Y 页」正确。〔REQ-PRINT-014、AC-64〕
- [x] 4.3 配置页新增 Logo 上传控件（走经典上传链路 `POST /common/upload`，字段名 `file`；**不要用** `/file/operate/**`），把返回的相对路径落为模板的 Logo 文件标识；验证：上传后配置文件标识非空，且 `<img>` 使用 `VUE_APP_BASE_API` 前缀能取到真实图片字节（不加前缀会返回 SPA 的 HTML）。〔REQ-PRINT-015、AC-65〕
- [x] 4.4 打印件抬头左侧输出 Logo；**未配置时不出现空白占位或破图图标，且其余抬头内容位置与已配置时一致**；验证：分别打印配置了 Logo 与未配置的两张单据比对抬头区域。〔REQ-PRINT-015、AC-65〕
- [x] 4.5 打印件读取抄送节点出栏配置：开启且流程存在抄送节点时输出抄送栏及对应人员与时间，关闭时不输出；验证：同一单据在开关两种取值下各打印一次比对签批栏。〔REQ-PRINT-014、AC-64〕
- [x] 4.6 更新打印模板配置说明文档的“纸张与方向 / Logo / 抄送节点”三节，写明 4.1–4.5 的可观察行为、配置页“选择内置模板 / 选择自定义模板”两种模式与实时预览的使用方式，以及上述上传链路的注意事项；验证：按文档步骤配置 → 打印，逐项与文档描述一致。〔REQ-PRINT-014、REQ-PRINT-015、REQ-PRINT-018、AC-64、AC-65〕

## 5. 留痕硬门禁与打印入口补全

- [x] 5.1 前端打印流程改为**先写留痕后打印**：留痕写入成功后调起打印；写入失败则**不打印**并提示“留痕写入失败，请联系管理员”（不静默放行）；补**前端测试**覆盖成功/失败两条路径（失败路径断言未调起打印）。验证：`npm.cmd run dev`，在留痕接口人为失败的情况下点击打印，页面不弹出打印、出现明确提示，且 `t_print_log` 无新增行。〔REQ-PRINT-017、AC-67〕
- [x] 5.2 回归确认留痕内容口径未被削弱：打印人取服务端会话身份（不接受客户端传入）、打印 IP 与 User-Agent 由服务端从请求提取、越权请求返回 403 且不落库；验证：`cd F:\dsh\ruoyiOA` 后 `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1` 全绿。〔REQ-PRINT-017、AC-67〕
- [x] 5.3 在待办列表行补打印入口（照已办列表行的行内按钮写法），入口总数达到 4；验证：`npm.cmd run dev` 后在待办列表某行点击打印，打开打印预览浮层且**不跳转离开当前页面**（浮层为 iframe，用该行单据的 `.print-page` 判定加载完成）。〔REQ-PRINT-016、AC-66〕
- [x] 5.4 补**前端测试**断言四处入口对同一张单据得到标题、版式与栏目一致的打印件（详情页 / 待办列表行 / 已办列表行 / 我起草列表行）；验证：前端测试全绿，逐张比对四次打印件的标题与栏目清单。〔REQ-PRINT-016、AC-66〕
- [x] 5.5 更新打印入口与留痕的用户文档：四处入口的位置说明、留痕为硬门禁（留痕失败即无法打印）的口径与用户可见提示文案；验证：按文档指引逐处入口各打印一次成功，并在留痕服务不可用时复现文档描述的提示。〔REQ-PRINT-016、REQ-PRINT-017、AC-66、AC-67〕

## 6. 集成检查（跨任务）

- [x] 6.1 后端全量构建并核对产物真的更新：`cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master`，先 `Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force`（打 fat jar 前必须先停后端，否则可能打出旧包而 Maven 仍报 BUILD SUCCESS），再 `mvn -B -DskipTests -pl ruoyi-workflow install` 与 `mvn -B -DskipTests -pl ruoyi-admin clean package`；验证：`(Get-Item ruoyi-admin\target\ruoyi-admin.jar).LastWriteTime` 为本次构建时间（不要只看 BUILD SUCCESS）。〔AC-61、AC-63〕
- [x] 6.2 前后端联调启动：`cd F:\dsh\ruoyiOA` 后 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1`（幂等），再 `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1` 重新登录；验证：前端 80 与后端 8080 均在线，且带 token 能成功请求打印聚合接口（未认证一律 401，无区分力）。〔AC-61～AC-67〕
- [x] 6.3 **新增 AC-83 第 ③ 个回归脚本「多内置打印模板回归」**并纳入交付门禁：对 4 个 key 各断言标题、栏目清单、签批栏出栏与否，以及“切换 key 后打印件随之变化”；验证：`node .\tools\audit\run-all.js` 保持全绿，且该脚本单独执行时 4 套版式的断言全部通过（脚本存为 **UTF-8 with BOM**，`.ps1` 里不用 `/** */` 块注释、不用 `| Select-Object -First N` 截断输出）。〔REQ-PRINT-011、REQ-PRINT-012、AC-61、AC-62、AC-83〕
- [x] 6.4 跑既有门禁确认无回归：`cd F:\dsh\ruoyiOA` 后依次 `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1`、`.\tools\authz-check.ps1`、`node .\tools\audit\run-all.js`；验证：三者全绿（`flow-regression.ps1` 20 条断言、`authz-check.ps1` 14 条断言、`run-all.js` 6 个脚本 + baseline）。〔AC-82、AC-83〕
- [x] 6.5 逐项闭环验收 AC-61～AC-67 并产出验收记录（含 3.5 的存量内置打印件人工确认结论、6.3 的脚本输出）；验证：7 条 AC 各有可指认的证据（命令输出 / 打印件截图 / 断言脚本输出），无一条以“已实现”代替证据；同时确认未新建任何打印模板表或打印日志表（`platform/module-boundary` 约束）。〔AC-61～AC-67〕
