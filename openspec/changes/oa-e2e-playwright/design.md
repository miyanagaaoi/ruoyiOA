# Design

> 动机与范围见 `proposal.md`；行为契约见 `specs/platform/browser-e2e/spec.md`。本文件只写**怎么做**与**为什么这样选**。
> 本文所有「实测」结论均在当前环境（Node 24.19 / npm 11.17 / 前端 80 / 后端 8080）跑过，探针产物见 `.cache/pw-probe/`。

## Context

**目标前端的既有约束**

| 事实 | 来源 | 对设计的影响 |
| --- | --- | --- |
| Vue 2.6 + Element UI 2.15 + vue-cli 4.4.6，dev server 监听 **80**，`/dev-api` 反代 8080 | `vue.config.js:33-53`、`.env.development` | 套件只面对 `http://localhost/` 一个源；选择器必须是 Element UI 的（不是 Element Plus） |
| 登录密码由前端 RSA 加密后提交（`encrypt(password)`），后端 `RsaUtils.decryptByPrivateKey` 解 | `src/api/login.js:8`、`SysLoginController.java:63` | 接口脚本必须复刻加密（故存在 `tools/rsa-encrypt.js`）；**走真实浏览器则零复刻** |
| 验证码开关 `sys.account.captchaEnabled`，答案存 Redis `captcha_codes:<uuid>` | `CaptchaController.java:49-75`、`CacheConstants.java:23` | 自动登录的可行入口（见 D2） |
| 登录态是 `js-cookie` 写的 `Admin-Token`，请求头 `Authorization: Bearer <token>` | `src/utils/auth.js`、`src/utils/request.js:29-30` | 可用 `storageState` 跨用例复用 |
| `VUE_APP_WS_URL = ws://localhost:8544/im`，而 `start-env.ps1` **不启动 8544** | `.env.development`、`start-env.ps1` | 控制台必然有连接失败记录（见 D5） |
| 路由来自后端菜单（`sys_menu`），业务页为 `/ctms/contract`、`/ctms/partner`、`/ctms/tag`、`/ctms/migration` | 实查 `sys_menu`（`2.0-PRD` 附录能力路径同源） | 用例改用例的路由名而不是拼 URL 猜 |
| 前端已有纯 Node 用例入口 `npm.cmd run test:unit`（`tests/run.js`），门禁第 9 项 | `ruoyi-vue-oa-ui-master/package.json:12`、DEV-ENV §7 | 新套件必须与它**并列**而不是合流 |
| UI 仓库 `.gitignore` 已含 `tests/e2e/reports` | `ruoyi-vue-oa-ui-master/.gitignore` | 落点是既定的，不是新拍 |

**既有 15 项门禁的形态**：静态审计（`tools/audit/*.js` 带 `--selftest`）→ 接口脚本（`tools/*-check.ps1`，HTTP + SQL 双验，自带运行锁）→ 后端 JUnit → 前端纯 Node 用例。**没有任何一项驱动浏览器**，且接口脚本的断言面止于服务端；「接口 200、页面错」是**结构性盲区**。

**参考仓库的既有资产**（`参考仓库-CTMS-移植清单.md:87,1286`）：18 个文件 = 16 个 spec + `helpers.ts` + `global-setup.ts`，覆盖 01 认证 / 02 看板 / 03 主数据客户 / 04 权限 / 05 合同 / 06 采购 / 07 库存 / 08 系统 / 09 销售 / 10 布局 / 10-v2.2-UI / 11 主数据物料 / 12 未保存守卫 / 13 行内校验 / 14 快速新增 / 15 经办人选择。**脚本不可直接跑**（选择器是 Element Plus DOM），只有业务流与本项目的路由比对后可复刻。

## Goals / Non-Goals

**Goals:**

- 补上「真实浏览器驱动」这一层，让页面级回归进 DEV-ENV §7 门禁。
- 让这一层**零门槛落地**：不下载浏览器内核、不改共享配置、不动应用依赖清单与锁文件。
- 让它能**长期活着**：断言口径集中、数据纪律明确、失败现场可留档、可重复执行。

**Non-Goals:**

- 不改任何业务代码、后端接口、数据库。本套件是**观测者**。
- 不追求 16 个 spec 的全量复刻；未交付的业务域（采购/销售/库存对应的 B4）**留空**，不假装覆盖。
- 不做视觉回归（`toHaveScreenshot`）、不做组件级测试（Vue Test Utils）、不做 CI 流水线改造。
- 不把 `tools/*-check.ps1` 的接口级断言「翻译」成浏览器版——那是重复劳动；浏览器套件只做它们做不到的事。

## Decisions

### D1 浏览器来源：复用系统 Chrome，不下载内核

**选择**：`use: { channel: 'chrome' }`。

| 备选 | 实测 / 评估 | 结论 |
| --- | --- | --- |
| 下载 `chromium`（`npx playwright install`） | 约 150MB；`cdn.playwright.dev` 域名与 TLS 可通（HEAD 返回 400 属正常），但**下载带宽未实测**；`cdn.npmmirror.com` 无对应目录 | 不用 |
| `channel: 'msedge'` | 本机 `msedge.exe` 同样存在 | 作为备用（Chrome 不可用时一行切换） |
| **`channel: 'chrome'`** | 本机 `C:\Program Files\Google\Chrome\Application\chrome.exe` 已存在；`npm i @playwright/test` **2 秒装完 3 个包，不触发内核下载**（`%LOCALAPPDATA%\ms-playwright` 不存在） | **采用** |

代价：绑定运行环境的浏览器版本（升级 Chrome 可能影响渲染）。收益：落地门槛归零、断网可执行。此项满足 spec 的「复用环境已有浏览器」。

### D2 验证码来源：读本机 Redis 的答案，不关开关

**选择**：抓取页面 `/captchaImage` 响应的 `uuid`，从 `127.0.0.1:6379` 读 `captcha_codes:<uuid>` 作为答案填入。

| 备选 | 评估 | 结论 |
| --- | --- | --- |
| 复用 `oa-login.ps1` 的做法（DB + Redis 缓存同时置 `false`，登录后恢复） | **改共享配置**：与 spec「不修改共享运行配置」直接冲突；运行期间真实用户看不到验证码；并发执行时相互踩踏 | 不用 |
| 图像识别（OCR） | 验证码是扭曲字符/算式，识别不可靠 | 不用 |
| 直接注入 `.cache/token-*.txt` 的接口 token 为 cookie | 最快，但**跳过了登录页**，认证链路本身失去覆盖；且 token 有效期/失效态无法回归 | 只作为排障手段 |
| **读 Redis 答案** | 答案就在本机、无鉴权、无前缀；零配置改动；认证全程走真实 UI（RSA 加密、表单校验、路由跳转全部被覆盖） | **采用** |

**两个实现陷阱（已实测，写死在 `helpers/captcha.js` 里）**：

1. **值带 JSON 引号**：RuoYi 的 `RedisTemplate` 值序列化器是 FastJson，String 存进去是 `"y422"`（含引号）。不剥引号直接填表必然报「验证码错误」——本探针第一次失败就是这个原因。
2. **Redis 是长连接**：命令回包后服务端**不关连接**，因此不能等 `end` 事件；必须按 RESP 的长度前缀解析（`$<len>\r\n<value>\r\n`）。等 `end` 会稳定超时。

### D3 依赖隔离：套件自带 `package.json`，不碰应用的锁文件

**选择**：`ruoyi-vue-oa-ui-master/tests/e2e/` 下独立 `package.json` + 独立 `node_modules`（`.gitignore` 的 `node_modules/` 已覆盖任意层级）。

| 备选 | 评估 | 结论 |
| --- | --- | --- |
| 加到 `ruoyi-vue-oa-ui-master/devDependencies` | 会重写**已入库**的 `package-lock.json`；直接违背 DEV-ENV §7 明写的「前端用例：纯 Node，不依赖浏览器；**零新增依赖**」；锁文件在本项目是刻意入库的复现基线（`.gitignore` 里有专门注释说明） | 不用 |
| **独立 package.json** | 应用侧 `package.json` / `package-lock.json` 逐字不变；应用构建与既有纯 Node 用例完全不受影响 | **采用** |

代价：多一份依赖清单要维护。收益：满足 spec 的「依赖与前端应用产物隔离」，且不破坏既有复现基线。

### D4 不托管 dev server：复用 `start-env.ps1`

**选择**：套件假定 80 / 8080 已在监听；执行前做**前置就绪检查**，未就绪立即以明确信息失败。

| 备选 | 评估 | 结论 |
| --- | --- | --- |
| `webServer` + `reuseExistingServer: true` | 在无环境时会用 vue-cli 重新拉起前端：首次编译**实测约 80 秒**，且 `vue.config.js:36` 的 `open: true` 会**弹出浏览器窗口**（对无人值守运行是噪声） | 不用 |
| 完全不管前置 | 未就绪时表现为长时间静默 → 违反 spec「环境未就绪时可识别地失败」 | 不用 |
| **复用 + 前置检查** | 与 `start-env.ps1` 是唯一编排入口这一现状一致；前端编译只发生一次 | **采用** |

### D5 断言口径集中在 helpers，噪声白名单只有一份

**选择**：`helpers/assertions.js` 提供统一入口，任何用例不得自建白名单。

- **环境噪声**（不计失败）：URL 匹配 `/im?Authorization=` 的 WebSocket 连接失败。**只匹配连接失败，不匹配业务错误**——IM 自身的回归由上层的专项覆盖，白名单不得放大到掩盖真实故障。
- **业务失败**（计失败）：被覆盖业务接口的 4xx/5xx；Element UI 的错误态提示（`el-message--error`）；未命中白名单的控制台 error；页面渲染错误。
- **已知的空态**：被覆盖实体的列表为空**不是失败**（见 D6），用例显式断言空态文案。

### D6 数据纪律：首批只读，写用例单独成组

**选择**：首批用例优先覆盖只读链路（登录、工作台、菜单/布局、合同台账列表与筛选、详情、往来单位、权限可见性）。需要写数据的用例（新增/编辑/删除）单独成组，使用 ASCII 前缀夹具并在收尾清理，且**沿用现有运行锁**与接口脚本互斥。

依据：实测 `/ctms/contract` 页面正常、列表接口 200，但表格是「暂无数据」——因为 `tools/ctms-*-check.ps1` 收尾会按白名单物理删除夹具。**库里没有常驻测试数据**，任何「依赖库里有数据」的用例都是定时炸弹（spec 的「不依赖库中既有业务数据」）。

### D7 `storageState` 是凭据，必须不入库

登录态文件含**真实 superAdmin token**。在 UI 仓库 `.gitignore` 新增 `.auth/`（与已存在的 `tests/e2e/reports` 并列）。这是**安全项**：泄漏一个 token 等于泄漏一个超级管理员会话。

### D8 首批覆盖范围按「目标侧是否存在」逐条裁剪

不照搬 16 个 spec。对照结论（实现期仍需按实际路由复核）：

| 参考仓库 spec | 目标侧对应 | 首批 |
| --- | --- | --- |
| 01 认证 | 登录页 + 未登录跳转 | ✅ setup + 负向用例 |
| 02 看板 | `/index` 工作台 | ✅ |
| 03 主数据客户 | `/ctms/partner` | ✅ |
| 04 权限 | 菜单可见性（需第二账号，如 `zhangwei`） | ✅ |
| 05 合同 | `/ctms/contract` 列表 + 详情 | ✅ |
| 10 布局 | 侧边栏 / 页签 / 面包屑 | ✅ |
| 10-v2.2-UI | 打印模板配置页（B2 已交付） | ✅ |
| 06 采购 / 07 库存 / 09 销售 / 11 主数据物料 | **B4 未交付**，目标侧页面不存在 | ❌ 留空，待 B4 后追加 |
| 08 系统 | 基础设置 / 编号管理 / 运维管理 | 勘察后定 |
| 12 未保存守卫 / 13 行内校验 / 14 快速新增 / 15 经办人选择 | 目标侧是否存在同等交互**未核实** | 勘察后定（实现期的勘察任务，见 `tasks.md`） |

这条裁剪本身就是**诚实性要求**：假装覆盖一个不存在的页面，比不覆盖更糟。

## Risks / Trade-offs

| 风险 | 处置 |
| --- | --- |
| **选择器脆弱**：Element UI 的 `el-select`/`el-dialog` 是自绘组件，`nth-child` 一改版就碎 | 强制按可见文本 / `placeholder` / ARIA role 定位（探针已用 `getByPlaceholder`、`getByRole` 跑通）；禁用位置索引与绝对 XPath |
| **首次编译 80 秒**造成用例超时 | 前置检查等待页面 HTTP 200 且含 `<title>`；单用例超时 60s；不用固定 `sleep` 等编译 |
| **白名单过宽**掩盖真实回归 | 白名单只允许 `/im?Authorization=` + 连接失败两个条件同时命中，且集中在 `helpers` 一处，用例不得自建（D5） |
| **B4 域留空**被误读为「已覆盖」 | `tasks.md` 明确登记留空项与补入条件；套件入口输出覆盖清单 |
| **与接口脚本抢数据** | 首批只读；写用例单独组 + 沿用运行锁（D6） |
| **在途改动被裹挟提交** | UI 仓库当前有未提交改动（`src/views/workflow/simple-flow/**`、`tests/run.js` 等）。提交边界写进任务：只 `git add tests/e2e` 与 `.gitignore` |
| **Chrome 版本漂移**影响渲染 | 与 D1 的收益权衡后接受；备用 `channel: 'msedge'` |
| **Node 24 + vue-cli 4 的 OpenSSL 问题** | 与本套件无关（应用侧由 `.npmrc` 的 `node-options` 处理）；套件自身不跑 webpack，不做任何构建 |

## Migration Plan

**无数据迁移、无接口变更、无业务代码变更**——本变更只新增一个测试资产与门禁清单的一项。

1. 落地顺序：套件本体（依赖 → 配置 → 认证 setup → helpers → 首批用例）→ 在**当前环境**跑通并记录耗时 → 才追加 DEV-ENV §7 第 16 项（**先证明可重复执行，再进阻断门禁**）。
2. 前置声明：DEV-ENV §7 第 16 项必须写明前置（先 `start-env.ps1`），与其它需登录项的写法一致。
3. **回滚**：删除 `ruoyi-vue-oa-ui-master/tests/e2e/`、还原 UI `.gitignore` 一行、从 DEV-ENV §7 / HANDOFF 移除第 16 项、撤掉 PRD 中新增的 `REQ-NFR-012` / `AC-85` 归属。不涉及任何数据或代码回滚。

## Open Questions

1. 参考仓库 12/13/14/15（未保存守卫 / 行内校验 / 快速新增 / 经办人选择）在目标 Vue2 侧是否存在同等交互——**实现期勘察**决定纳入或标注不适用。不改 spec、不改任务拆分（任务里已含该勘察项）。
2. 第二批写用例要覆盖到哪些业务动作——待首批只读套件稳定后按实际收益排序。
3. 是否引入视觉回归——当前非目标，留待后续变更集。
