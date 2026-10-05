# Tasks

> 每项任务可映射到 `doc/2.0/2.0-PRD-OA升级开发.md` 的 `REQ-*` / `AC-*`：本体为 `REQ-NFR-011`（交付门禁，追加第 16 项）与拟新增 `REQ-NFR-012`（浏览器端到端回归）、拟新增 `AC-85`；零回归项沿用 `AC-82`，随批交付项沿用 `AC-83`。
> 只产出规划件的边界见 `proposal.md`；**本文件是给 `openspec-apply-change` 用的**。
> 前置：`.\start-env.ps1` 已把前端 80 / 后端 8080 / MySQL / Redis 拉起。

## 1. 套件骨架与依赖隔离

- [ ] 1.1 在 `ruoyi-vue-oa-ui-master/tests/e2e/` 建独立 `package.json`，按 `--registry=https://registry.npmmirror.com` 安装浏览器测试依赖。验证：安装完成后 `git -C ruoyi-vue-oa-ui-master status --short` 中**不出现** `package.json` / `package-lock.json`，且 `%LOCALAPPDATA%\ms-playwright` 仍不存在（未下载内核）。（`REQ-NFR-012` / `AC-85`）
- [ ] 1.2 编写 `playwright.config.js`：复用系统 Chrome、`baseURL = http://localhost`、单 worker、失败留存截图与操作轨迹、报告目录 `tests/e2e/reports`。验证：放一条占位用例执行通过，且 `git -C ruoyi-vue-oa-ui-master status --short` 不含 `tests/e2e/reports`（已被既有规则忽略）。（`REQ-NFR-012` / `AC-85`）
- [ ] 1.3 在 `ruoyi-vue-oa-ui-master/.gitignore` 增 `.auth/`。验证：`git -C ruoyi-vue-oa-ui-master check-ignore -v tests/e2e/.auth/state.json` 命中该规则（凭据不入库）。（`REQ-NFR-012` / `AC-85`）

## 2. 认证：真实登录页 + 不改共享配置

- [ ] 2.1 实现 `helpers/captcha.js`：从页面 `/captchaImage` 响应取 `uuid`，按 RESP 长度前缀从本机 Redis 读 `captcha_codes:<uuid>`，并剥掉 FastJson 序列化产生的 JSON 引号。验证：连续取 3 个不同 `uuid` 的答案，与 `env\redis\server\redis-cli.exe get captcha_codes:<uuid>` 的输出逐一一致，且不含引号。（`REQ-NFR-012` / `AC-85`）
- [ ] 2.2 实现 `auth.setup.js`：走真实登录页完成一次登录（`superAdmin` / `admin123`），落 `.auth/state.json`。验证：用例通过；登录后 URL 不含 `login`；`state.json` 中含 `Admin-Token`；其余用例以 `storageState` 复用后**不再出现登录表单**。（`REQ-NFR-012` / `AC-85`）
- [ ] 2.3 在认证用例前后各读一次验证码开关（`sys_config` 表 + Redis 缓存 `sys_config:sys.account.captchaEnabled`）并断言两次一致。验证：套件全绿时该断言通过；**故意**把 Redis 缓存值改动后再跑，该断言失败（证明这条检查有区分力，不是恒真）。（`REQ-NFR-012` / `AC-85`）
- [ ] 2.4 实现前置就绪检查（前端 80、后端 8080）。验证：把 `baseURL` 指向一个未监听端口执行，套件以非 0 退出码结束并打印**未就绪的目标**，且在 60 秒内结束、不静默挂起。（`REQ-NFR-012` / `AC-85`）

## 3. 断言口径、噪声白名单与运行契约

- [ ] 3.1 实现 `helpers/assertions.js`：集中定义环境噪声白名单（仅匹配 `/im?Authorization=` 的 WebSocket **连接失败**）与业务失败判定（被覆盖接口 4xx/5xx、Element UI 错误提示、未命中白名单的控制台 error）。验证：写两条自检——注入一条 WS 连接失败记录时判定为通过；注入一条业务接口 5xx 时判定为失败。（`REQ-NFR-012` / `AC-85`）
- [ ] 3.2 固化失败现场留存。验证：临时加一条必然失败的用例执行后，`tests/e2e/reports` 中存在该用例的截图与操作轨迹，退出码非 0。（`REQ-NFR-012` / `AC-85`）
- [ ] 3.3 在套件 README 声明并发契约（与 `tools/*-check.ps1` 的运行锁互斥），并在前置检查中检测锁文件。验证：手工创建锁文件后执行，套件拒绝运行并给出明确提示；删除锁文件后可正常运行。（`REQ-NFR-012` / `AC-85`）

## 4. 首批只读用例

> 依据 `design.md` D6 / D8。全部为只读链路，**不得**依赖库中已有业务数据。

- [ ] 4.1 认证与布局用例（参考仓库 01 / 10）：未登录访问业务路由被重定向到登录页；登录后侧边栏、页签、面包屑渲染。验证：用例通过；断言使用可见文本与 ARIA role 定位，不含 `nth-child` 与绝对 XPath。（`REQ-NFR-012` / `AC-85`）
- [ ] 4.2 工作台用例（02）：`/index` 关键区块渲染。验证：用例通过。（`REQ-NFR-012` / `AC-85`）
- [ ] 4.3 合同台账用例（05）：`/ctms/contract` 列表加载、条件筛选触发列表接口 200、进入详情只读。验证：用例通过；列表为空时断言空态文案而非失败。（`REQ-NFR-012` / `AC-85`）
- [ ] 4.4 往来单位用例（03）：`/ctms/partner` 客户/供应商两个页签加载。验证：用例通过，接口无 4xx/5xx。（`REQ-NFR-012` / `AC-85`）
- [ ] 4.5 打印模板配置页用例（10-v2.2-UI，B2 已交付）：页面加载与内置模板切换入口存在。验证：用例通过。（`REQ-NFR-012` / `AC-85`）
- [ ] 4.6 权限可见性负向用例（04）：用无对应权限的第二账号登录，断言其看不到越权菜单，且直接访问该路由时被拦截。验证：用例通过；**换回 `superAdmin` 时该菜单可见**（证明断言有区分力）。（`REQ-NFR-012` / `AC-85`）

## 5. 参考仓库 16 个 spec 的逐条裁剪

- [ ] 5.1 逐条核对参考仓库 16 个 spec（移植清单 §7.2 / `:1286`）在目标 Vue2 侧是否存在同等交互，产出对照表（含「改写 / 合并 / 留空」与理由）写入套件 README。验证：对照表覆盖全部 16 条，无「待定」残留；每条留空项写明补入条件。（`REQ-NFR-012` / `AC-85`）
- [ ] 5.2 对 08 系统、12 未保存守卫、13 行内校验、14 快速新增、15 经办人选择作出纳入或标注不适用的结论，纳入项补齐用例。验证：结论逐条落在对照表中；纳入项用例通过，不适用项给出目标侧不存在的依据（路由或组件实测）。（`REQ-NFR-012` / `AC-85`）
- [ ] 5.3 把 06 采购 / 07 库存 / 09 销售 / 11 主数据物料登记为「B4 未交付，留空」，并在套件入口输出覆盖清单。验证：套件运行时打印的覆盖清单中包含这四项及其留空状态。（`REQ-NFR-012` / `AC-85`）

## 6. 门禁接入与文档同步

- [ ] 6.1 在 `DEV-ENV.md` §7 追加**第 16 项**（命令、前置、失败即阻断），原有 15 项一条不改。验证：按 §7 文档里写的命令**原样复制执行**可跑通；对照 §7 表格确认前 15 项文本未变。（`REQ-NFR-011` / `AC-85`）
- [ ] 6.2 在 `DEV-ENV.md` §3 / §4 登记运行方式与前置（依赖隔离说明、系统浏览器复用、与接口脚本的互斥），同步 `HANDOFF.md` 的命令表与交付清单。验证：文档中的命令逐条执行成功；命令与套件内 README 一致。（`REQ-NFR-011` / `AC-85`）
- [ ] 6.3 在 `doc/2.0/2.0-PRD-OA升级开发.md` §11.6 分配 `REQ-NFR-012` 与 `AC-85` 并在 `AC-83` 的条目中登记本套件；§15.1 变更集表增行；在 `openspec/changes/oa-v2-overview/proposal.md` 的能力路径表登记 `platform/browser-e2e`。验证：四个编号在 PRD 中可检索到且指向同一能力；能力路径表无重复近义名。（`REQ-NFR-011` / `AC-85`）
- [ ] 6.4 编写套件内 `README.md`：运行方式、覆盖清单、留空项、断言口径与噪声白名单、数据纪律。验证：README 中的命令原样可执行；覆盖清单与 5.1 的对照表一致。（`REQ-NFR-012` / `AC-85`）

## 7. 集成验证

- [ ] 7.1 在环境不变的前提下**连续执行两次**套件，两次结果一致，且第二次不产生新增残留数据。验证：两次退出码均为 0；执行前后对 `uploadPath` 与 `.cache` 做一次文件清单比对，无新增残留。（`REQ-NFR-012` / `AC-85`）
- [ ] 7.2 既有 15 项门禁全部重跑全绿，证明本变更零回归。验证：`node tools\audit\run-all.js`、`tools\authz-check.ps1`、`tools\sign-feature-check.ps1`、`tools\flow-regression.ps1`、`tools\print-builtin-check.ps1`、`tools\serial-numbering-check.ps1`、`mvn -B -pl ruoyi-workflow test`、`mvn -B -pl ruoyi-serial test`、`mvn -B -pl ruoyi-ctms test`、`npm.cmd run test:unit`、`tools\b3-sql-drill.ps1`、`tools\ctms-*.ps1` 全部退出码为 0。（`AC-82` / `AC-83`）
- [ ] 7.3 按 `DEV-ENV.md` §7 的**完整顺序**执行 16 项门禁，全绿。验证：16 项逐项退出码为 0；记录本次总耗时（供后续评估门禁时长）。（`REQ-NFR-011` / `AC-85`）
