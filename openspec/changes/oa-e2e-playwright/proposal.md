# Proposal

> **覆盖需求**：`REQ-NFR-011`（交付门禁，**追加第 16 项**）、拟新增 `REQ-NFR-012`（浏览器端到端回归）
> **验收覆盖**：拟新增 `AC-85`（浏览器端到端回归）；沿用 `AC-82`（零回归）、`AC-83`（新增回归脚本随批交付）
> **依赖**：`oa-v2-overview` 的 `platform/delivery-gate`（本变更把浏览器套件挂到它的门禁上；总纲未归档，故不写 `MODIFIED`）
> **PRD 依据**：`doc/2.0/2.0-PRD-OA升级开发.md:1025`、`doc/2.0/参考仓库-CTMS-移植清单.md:87,1286,1354`
> **批次**：工具链 / 质量基建（**非** PRD 业务批次，可独立于 B4 排期）

## Why

**一、现有 15 项门禁没有一项驱动真实浏览器。** DEV-ENV §7 的资产分三类：静态审计（`tools/audit/*.js`）、接口级脚本（`tools/*-check.ps1`，走 HTTP + SQL 核对）、纯 Node 用例（`npm run test:unit`，明确写着「不依赖浏览器」）。三者都止步于**服务端口径**；「接口全 200、页面上就是错的」这一类回归**没有任何资产能抓到**。而这批回归恰好是本次升级的高发区：移植清单 §7.2 列出 Element Plus → Element UI 的 14 类组件/属性差异，其中 `value-format` 漏改 **15 处会静默产生日期解析错误而不抛异常**（PRD 风险 R-7，原文要求「写成检查清单 + grep 门禁」——grep 只能查字面量，查不出"控件行为不对"）。

**二、PRD 已规划、B3 未承接。** PRD 附录 B（`2.0-PRD-OA升级开发.md:1025`）把参考仓库 `web/tests/e2e/**` 的落点写为「**改写为目标侧 E2E（AC-83 的新增脚本）**」；移植清单 §7.2 给出这批资产的规模与覆盖：**18 个文件 = 16 个 spec + `helpers.ts` + `global-setup.ts`**，覆盖 01 认证 / 02 看板 / 03 主数据客户 / 04 权限 / 05 合同 / 06 采购 / 07 库存 / 08 系统 / 09 销售 / 10 布局 / 10-v2.2-UI / 11 主数据物料 / 12 未保存守卫 / 13 行内校验 / 14 快速新增 / 15 经办人选择。但 AC-83 枚举的「新增的 3 个回归脚本」实际落成了 ① `audit-template-tabs.js` ② `b1-binding-check.ps1` ③ `print-builtin-check.ps1`——**全部是 PowerShell 接口脚本，Playwright 这一项被漏掉了**。本变更是补这个缺口，不是凭空引入新技术栈。

**三、成本已实测为零门槛（本提案的每一项都在当前环境跑过）。** 三个原本最容易卡住的点全部证伪：

| 疑点 | 实测结论 |
| --- | --- |
| 要下 150MB 浏览器 / 国内网络不通？ | `npm i @playwright/test` **2 秒装完 3 个包、不下载浏览器**；配置 `channel: 'chrome'` 直接复用本机已装的 Chrome |
| 图形验证码没法自动登录？ | 验证码答案就在本机 Redis 的 `captcha_codes:<uuid>`（无鉴权、无前缀），**比 `oa-login.ps1` 临时关验证码更干净**——不需要改 `sys_config` 与 Redis 缓存 |
| 密码 RSA 加密要复刻（`tools/rsa-encrypt.js`）？ | 走真实浏览器则加密由应用自己完成，**脚本零复刻** |

4 条探针（登录页加载 / 真实 UI 登录 / 复用登录态访问 `/ctms/contract` 与 `/ctms/partner`）全绿，25 秒跑完，产物为截图 + `storageState`。

## What Changes

- **新增浏览器端到端套件 `ruoyi-vue-oa-ui-master/tests/e2e/`**（该目录的 `.gitignore` 中**已预留** `tests/e2e/reports`，落点不是新拍的）。目录自带 `package.json` 与 `playwright.config.js`，**不修改** UI 仓库的 `ruoyi-vue-oa-ui-master/package.json` 与已入库的 `package-lock.json`——守住 DEV-ENV §7「前端用例：纯 Node，不依赖浏览器；**零新增依赖**」这条既有口径。
- **认证只跑一次**：新增 `auth.setup.js` 走**真实登录页**（账号 `superAdmin` / 口令 `admin123`），验证码从本机 Redis 读答案；登录成功后落 `storageState`，其余用例以 `test.use({ storageState })` 直接进业务页。**不改动 `sys.account.captchaEnabled`**，不引入 OCR。
- **首批用例按参考仓库 spec 清单对照改写**（不是 16 个照搬；逐条决定「改写 / 合并 / 不适用」，依据是本项目 Vue2 侧的实际路由与菜单，见 design 的对照表）。
- **环境噪声白名单**：`VUE_APP_WS_URL = ws://localhost:8544/im` 而 `start-env.ps1` **不启动 8544**，每个页面控制台必有一条 `ERR_CONNECTION_REFUSED`。套件必须把它排除在「控制台零错误」断言之外，否则整批假红。
- **空数据不是失败**：`tools/ctms-*-check.ps1` 收尾按白名单物理删除夹具（实测 `/ctms/contract` 页面正常、接口 200、表格「暂无数据」）。UI 用例**不得**依赖"库里正好有数据"；要么断言空态，要么自造 ASCII 前缀夹具并在收尾清理。
- **产物与门禁**：`trace` / `video` / `screenshot` 落 `tests/e2e/reports`（已 gitignore）；`storageState` 含**真实 superAdmin token**，必须新增 `.auth/` 到 gitignore（**这是安全项，不是整洁项**）。套件以退出码 0/1 进 DEV-ENV §7，作为**第 16 项**追加，原有 15 项一条不改。
- **可重复执行**：沿用现有 `tools/*-check.ps1` 的运行锁纪律（多脚本互斥），避免与接口脚本并发改写同一条数据。
- **不做（明确排除）**：不改任何业务代码与后端接口；不替代或重写现有 15 项门禁资产；**不下载** Playwright 自带 Chromium（复用系统 Chrome）；不引入 UI 组件测试或视觉回归（`toHaveScreenshot` 留待后续）；不做 CI 流水线改造。

## Capabilities

### New Capabilities

- `platform/browser-e2e`: **浏览器端到端回归套件的行为契约**。承载「真实浏览器驱动的登录与登录态复用、不修改共享配置的验证码获取、业务页可回归性断言口径（接口异常 vs 环境噪声）、失败可诊断的产物留存与退出码、与既有门禁资产的隔离（依赖隔离 / 不与接口脚本并发）、空态与自造夹具的数据纪律」这一组行为。

> 路径选择说明：本项目既有能力按 `<域>/<能力>` 组织，`platform/` 域已由 `oa-v2-overview` 的 `platform/module-boundary`、`platform/delivery-gate` 建立。浏览器套件是**门禁能力的扩展**，不是业务能力，故归 `platform/`，与 `delivery-gate` 并列而非混入。

### Modified Capabilities

（空 —— `oa-v2-overview` 尚未 `archive`，主规格 `openspec/specs/` 只有 `.gitkeep`。按 PRD §15.1 的口径，批次变更集对平台能力的依赖写成 proposal 里的显式依赖声明，行为契约由自身新能力承载；待总纲归档后再由后续变更集收敛为 `MODIFIED`。）

## Impact

**规划边界**：本变更集当前**只产出规划件**，不改代码；实现须由用户显式发起 `openspec-apply-change`。

**受影响落点（实现阶段）**：

| 层 | 落点 | 动作 |
| --- | --- | --- |
| 前端仓库（新增，UI 子仓库内提交） | `ruoyi-vue-oa-ui-master/tests/e2e/**` | 套件本体：`package.json`、`playwright.config.js`、`auth.setup.js`、`helpers/captcha.js`（Redis 取值）、首批 spec |
| 前端仓库 | `ruoyi-vue-oa-ui-master/.gitignore` | 增 `.auth/`（`storageState` 含真实 token）；`tests/e2e/reports` 已存在 |
| 根仓库文档 | `DEV-ENV.md` §3 / §4 / §7 | §7 门禁追加**第 16 项**（命令、前置、失败即阻断）；§4 补运行方式与「先 `start-env.ps1`」前置；§3 登记 Node 24 + 系统 Chrome 的既有事实 |
| 根仓库文档 | `HANDOFF.md` | 交付清单与命令表同步第 16 项 |
| 根仓库文档 | `doc/2.0/2.0-PRD-OA升级开发.md` §11.6 / §15.1 | 分配 `REQ-NFR-012` 与 `AC-85`；AC-83 的「新增回归脚本」条目登记本套件；§15.1 变更集表增行 |
| 根仓库规划件 | `openspec/changes/oa-v2-overview/proposal.md` §15.1 能力路径表 | 登记 `platform/browser-e2e`（避免后续出现同名近义能力） |

**需求追溯**：`REQ-NFR-011`（交付门禁，追加项）+ 拟新增 `REQ-NFR-012`；`AC-82`（零回归：既有 15 项仍全绿）、`AC-83`（新增脚本随批交付且可自证）+ 拟新增 `AC-85`。

**风险**：

| # | 风险 | 处置 |
| --- | --- | --- |
| 1 | 运行耗时把门禁拖长（前端 dev server 首次编译实测约 80 秒） | 套件**不托管** dev server，复用 `start-env.ps1` 已起的 80 端口；单条用例不 sleep 等编译 |
| 2 | UI 用例与接口脚本抢同一批数据导致偶发假红 | 沿用运行锁；首批用例优先选**只读**链路（列表 / 详情 / 菜单可见性），写用例单独成组并自造 ASCII 前缀夹具 |
| 3 | 「控制台零错误」被 IM WebSocket 噪声撑爆 | 白名单键（URL 含 `/im?Authorization=` 的 WS 连接失败）在 design 中固化，禁止各用例各写一套 |
| 4 | UI 子仓库当前有**未提交的改动**（`src/views/workflow/simple-flow/**`、`tests/run.js` 等） | 本套件提交时只 `git add tests/e2e` 与 `.gitignore`，**不得**裹挟在途改动 |
| 5 | Vue2 + Element UI 的选择器与参考仓库（Element Plus）不通用 | 不照抄选择器；逐条按本项目路由与 DOM 改写（design 给出对照与取舍） |
