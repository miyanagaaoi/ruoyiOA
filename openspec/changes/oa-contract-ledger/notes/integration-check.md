# 10.1 端到端主链路联调（任务 10.1 / t18）

> 执行：2026-10-05（qa attempt 1）｜脚本：`tools/ctms-e2e-check.ps1`（新增，431→517 行，UTF-8 with BOM）
> 判定：**通过** —— `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1`
> **退出码 0、91 条断言全绿**；连续两次运行在归一化运行期 UUID/序号后**逐行一致**（diff 0 行）。
> 本文件即「环节 / 预期 / 实际」联调记录；每一格的"实际"都附**查库证据**（不只看接口 200）。

## 0. 环境、运行态与「零夹具基线」

| 项 | 实测 |
| --- | --- |
| 后端 | 8080 LISTEN；`ruoyi-admin.jar` mtime **2026-10-05 15:52:04**（含第 5/6/7 组端点的新包） |
| 登录 | `tools\oa-login.ps1` 已重跑（5 个账号全成功，token 新鲜） |
| 真库 | `rad_oa`（MySQL 8 便携版，root 空口令） |
| **零夹具基线** | 合同 **23** / 客户 **0** / 供应商 **2** / 草案 **17** / 附件 **0**；其中 **23/23 份合同都是第 7 组迁移验收的遗留夹具**（`迁移验收夹具`/`CTMMIG*`） |
| 并发 | 运行前后 `.cache\*.lock` 均为 **0** —— 本脚本内置锁（`ctms-e2e-check.lock`），且运行期间无其它验收脚本 |

> **基线怎么处理的**：本脚本**所有**断言都按"自己夹具的 id/code"判定（从不依赖列表总行数、可见集合计数），
> 所以那 23 份他人遗留夹具不会造成"筛出 1 条却返回 24 条"的假象；
> 同时脚本在清理后**记录全表基线**，收尾再比一次（见 §3），他人夹具既不影响判定、也不被本脚本触碰。

## 1. 主链路十个环节：环节 / 预期 / 实际

| # | 环节 | 预期（规格口径） | 实际（查库证据） |
| --- | --- | --- | --- |
| P1 | 新增**客户**档案 | 201/200；档案落库、编码唯一、`id` 服务端生成 | 200 `操作成功`；`t_ctms_customer` 命中 `code=E2EC<S>`，`name=E2E客户<S>`，`enable_flag=1`；`id` 为 32 位 UUID（服务端生成）✓ |
| P2 | 新增**供应商**档案 | 200；**简称非空**（`short_name` NOT NULL） | 200；`code=E2ES<S>`，`short_name=E2E供<S>` 落库一致 ✓ |
| P3 | 新增**合同**（行项 2 + 质保 12 个月 + 标签 A/B） | 服务端生成编号；金额=逐行 HALF_UP 后求和；标的物摘要来自行项；质保到期日按算法；标签手动 2 + 自动 1 | ① 编号 **`SALZC202609000101`**（17 位：类型码+主体码+yyyy+MM+6 位序号）✓；② 行总价 `5.00,0.10`，合同金额 **`5.10`**（=已舍入行之和；未舍入求和会得 5.10 的"巧合"被 3.333×0.03 这条用例拆穿）✓；③ 标的物摘要 **`E2E球阀<S>(DN50)×3；E2E球阀<S>(DN50)×3.333`** 真落库 ✓；④ 质保 `2025-06-01 + 12 月 → warranty_end=2026-05-31`、`warranty_months=12` ✓；⑤ `t_ctms_contract_tag` = `E2E标签A<S>\|0`、`E2E标签B<S>\|0`、`SAL\|1`（自动标签与类型同名）✓；⑥ `create_id/dept_id` 均为**服务端快照**（请求体未传）✓；⑦ 行项 2 条随主表落库（FK 顺序正确，无孤儿行）✓ |
| P4 | **列表筛选** | 关键字/类型+状态+日期区间命中；标签取**交集**；状态不匹配时不命中；含停用开关生效 | 关键字命中本夹具；组合筛选命中；`tagIds=A,B`（交集）命中；只带 A 也命中；状态=付款中时**按 id 不命中**（证明条件真的进了 SQL，不是前端过滤）✓。另：`/ctms/contract/export` 返回 **HTTP 200 + `PK` + `Content-Type: …spreadsheetml.sheet` + 体内无 `"rows"/"code"`**（第 5 组的 ExcelUtil 改造生效）✓ |
| P5 | **详情** | 返回行项/标签/变更历史入口/只读「关联单据」；打开**无回写副作用** | 详情 200 且 id 一致；行项 2 条；标签 3 个；变更历史入口存在；`relatedDocs` 空列表（B3 阶段）；打开前后 `amount\|status\|arrival_status\|paid_amount\|del_flag` 指纹 **完全一致**（`5.10\|已签订\|未到货\|0.00\|0`）✓ |
| P6 | **变更历史** | 改名留痕 `source=manual`、含操作人与旧值；自动重算留痕存在 | `total=6` 条；改名记录 `newValue=新名`、`oldValue=原名`、`source=manual`、`operatorId=superAdmin` 的 id ✓；另有 `source=auto` 记录（金额/标的物/质保到期自动重算）✓ |
| P7 | **附件上传/删除** | 元数据落库；**磁盘文件真实存在**；下载字节数一致；删除是**逻辑删除**并留痕 | 上传 200，`id` 服务端 UUID；`t_ctms_attachment` = `contract\|<合同id>\|E2E-att<S>.pdf`；`stored_path=/profile/upload/2026/10/05/…`；**磁盘文件存在且 333 字节 == 上传字节数**；下载 200 且 333 字节一致；DELETE 200 后 **行仍在库 `del_flag=1`**（留痕可审计）、列表不再返回、并新增一条 `field_name='附件'` 的变更历史（`object_type=contract`/`object_id=合同id`，`old_value=E2E-att<S>.pdf（333 B）`，含操作人）✓ |
| P8 | **软删除** | `del_flag=1`、`deleted_at` 落库、`deleted_reason` 落库；默认列表排除、`includeDeleted=1` 出现 | 三者全部落库（日差 0）；默认列表按 id 不返回；`includeDeleted=1` 时出现 ✓ |
| P9 | **30 天内恢复** | `del_flag` 回 0，且 `deleted_at`/`deleted_reason` **清零** | 恢复 200；`del_flag=0`；`deleted_at IS NULL`；`deleted_reason IS NULL`；且不存在"恢复了但时间戳还在"的中间态 ✓ |
| P10 | **30 天边界** | **整数日差**口径：第 30 天可恢复、第 31 天不可 | 见 §2（两侧都留了 SQL 日差证据）✓ |

## 2. 软删除 30 天边界：两侧证据（整数日差口径）

| 边界侧 | 制造方式 | SQL 证据 | 接口证据 | 结论 |
| --- | --- | --- | --- | --- |
| **内侧（第 30 天）** | 停用后 `UPDATE … SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 30 DAY) + INTERVAL 9 HOUR` | `SELECT DATEDIFF(CURDATE(), DATE(deleted_at))` = **30** | `PUT /ctms/contract/{id}/restore` → 200 `操作成功`；随后 `del_flag=0` | **第 30 天可恢复** ✓ |
| **外侧（第 31 天）** | 再次停用后 `UPDATE … SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 31 DAY)` | `SELECT DATEDIFF(CURDATE(), DATE(deleted_at))` = **31** | `PUT /ctms/contract/{id}/restore` → `msg=已超过 30 天保留期，无法恢复`；随后 `del_flag` 仍为 **1** | **第 31 天拒绝且无副作用** ✓ |

口径说明：判据取 `DATE(deleted_at)` 与 `CURDATE()` 的**整数日差**（实现侧 `ContractRules.isWithinRestoreWindow`），
所以"30 天整 + 9 小时"算第 30 天（可恢复），"31 天整"算第 31 天（拒绝）——两侧都用 `deleted_at` 构造，**不改系统时间**。

## 3. 串行执行、夹具清理与「零夹具基线不漂移」

- **串行**：脚本内置运行锁 `.cache\ctms-e2e-check.lock`；本轮运行前后 `.cache\*.lock` 计数 = **0**，
  运行期间没有并发跑其它验收脚本（含借用 `common` 角色的第 6 组脚本）。
- **夹具清理**：清理顺序按 DEV-ENV §6.45（关联表 → 变更历史 → 附件 → 行项 → 合同 → 物料域 → 档案 → 标签），
  物理附件文件也一并删除；收尾 7 条逐表残留断言全部为 **0**（合同/行项/变更历史/附件/标签关联/档案·标签·物料域/物理文件）。
- **基线不漂移**（4 条断言，清理判据的兜底网）：合同 **23 → 23**（他人 23 份 CTMMIG 夹具未被触碰）、
  客户 **0 → 0**、供应商 **2 → 2**、附件 **0 → 0**。

## 4. 本轮发现的三处**脚本自身**缺陷（如实记录，均未改实现、未改预期迁就实际）

按验收条款"任何环节与预期不一致时，记录实际现象与定位结论"：

| # | 现象（实际） | 定位结论（真因） | 修法（只改脚本） |
| --- | --- | --- | --- |
| S1 | 导出断言拿到 **HTTP 400 + `<!doctype html>`** | **curl.exe 不会自动编码 URL 里的中文**：`keyword=E2E1合同…` 裸中文被 Tomcat 拒绝（Invalid character in request target）。同一句用 `Invoke-RestMethod` 是通的（.NET 自动 IRI→URI 转义），所以"列表筛选用中文能过、导出却 400"**不是后端缺陷**（编码后立刻 200 + `PK`） | 导出调用前 `[uri]::EscapeDataString`；并把断言加强为 `HTTP 200 + magic PK + Content-Type spreadsheetml + 体内无 JSON`（错误页无法再伪装成功） |
| S2 | 附件删除留痕断言 **count=0** | 我的查询写了 `object_type='attachment'`，而实现（`CtmsAttachmentServiceImpl.writeDeleteLog`）写的是 `attachment.getObjectType()/getObjectId()` = **合同的对象类型与合同 id** | 查询改为 `object_type='contract' AND object_id=<合同id>`，并补一条"旧值 = 文件名（大小）"断言 |
| S3 | **收尾残留断言全绿，但基线漂移了**：客户 0→3、供应商 2→5 | `t_ctms_customer`/`t_ctms_supplier` 的**主键是服务端 UUID**，业务身份在 `code`；我按 `id LIKE 'E2EC%'` 清理 ⇒ **空操作**，而残留断言用了同一个错判据 ⇒ **假绿**（漏掉 3 个客户 + 3 个供应商，来自 3 次运行）。这正是队长要求"先建立零夹具基线"的价值：**由基线漂移抓到，而不是靠我想到** | ① 清理与残留断言改为按 **code** 判定（`code LIKE 'E2EC%'/'E2ES%'`）；② 新增 **4 条全表基线漂移断言**（合同/客户/供应商/附件），作为"清理判据写错/漏表"的兜底网；③ 文件头补"经验 4：主键是服务端 UUID 的表不能按 id 前缀认夹具" |

> 三处都已修正并复跑：最终 **91/0**，两次运行逐行一致；上面 S3 漏掉的 6 行夹具也已单独清理干净（客户回到 0、供应商回到 2）。

## 5. 复跑方式（可直接复制）

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1        # 起环境（幂等）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1   # 拿/刷新 token（必跑）
# 10.2 走查前必须重新登录一次（菜单树缓存在前端 store；DEV-ENV §6.33 的菜单名修复靠重登才可见）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1
# 期望：退出码 0、91 项通过 / 0 项失败；末 4 行为"零夹具基线未漂移"断言
```

- ⚠ 与第 4/5/6 组的脚本**不可并发**（都会动真库夹具）；本脚本有锁，但请在其运行时不要启动它们。
- ⚠ 断言一律**按 id/code**；若未来要加"列表总行数"类断言，必须先减掉他人遗留夹具的基线。

## 6. 未覆盖 / 移交给 10.2（不谎报）

| 项 | 归属 | 说明 |
| --- | --- | --- |
| 浏览器级 ①质保到期日实时联动（提交体不含到期日）②自动标签在真实页面的表现 ③表单纯前端拦截（简称为空/账期 -1） | **10.2** | 本任务是**接口+落库**联调，未驱动浏览器；这三条需要 DOM/截图级原始证据 |
| 浏览器级 ④导出正向断言 ⑤导出契约（前端仍走 `utils/request.js` 的 `download()`） | 10.2 | 复审者已执行过；本脚本已从**后端侧**复跑契约（`PK` + Content-Type + 无 JSON 体），前端侧仍需在 10.2 复核 |
| 导出**内容**逐单元格核对（表头顺序、甲方/乙方文本列）| 10.2 | 复审者/前端已在真实 UI 做过；本脚本只判"是不是真 xlsx" |
| 空库初始化与回滚演练 | **10.3（t20）** | 见 `tasks.md` 10.3；本任务不涉及 |

---

# 7. 10.2 交付门禁（任务 10.2 / t19）

> **【2026-10-05 复跑更新（t19 attempt 3）】R1 + R2 双双收口后按 §7 清单全量复跑 → 16/16 门禁全绿、全部 exit 0**：
> audit 10/0/0｜authz-check 45/0｜flow-regression 20/0｜sign-feature 44/0（未截断）｜masterdata 79/0｜**contract 261/0（R1 修复后）**｜
> **commercials 116/0**｜attachment 69/0｜b3-sql-drill 68/0｜migration-check 52/0｜perm-audit 15/15 差异为空（26/26/27）｜e2e 91/0｜
> **mvn ruoyi-ctms 217/0/0**（原 212，R2 新增 5 条）｜workflow 19/0/0｜serial 24/0/0｜npm test:unit **39/39**。
> 本次构建（§6.13 顺序）：**jar mtime 2026-10-05 17:04:10 / size 184238547**（旧 16:09:55 / 184237301）；
> 后端 **pid=9324 启动于 17:04:15**（晚于 jar ⇒ 排除旧包形态）；`GET /ctms/contract/list` 探针 200；
> 零夹具基线 **`0/0/0/0/0`**（队长清理 23 份遗留夹具后）、`.cache\*.lock=0`、无演练库残留。
> **口径提醒（重要，防误读）**：`ctms-commercials 116/0` 只证明**既有编号语义未回归**，**不能当作"R2 已生效"的证据** ——
> R2 的现场触发条件（桶内高号来自那批已清夹具）已随夹具清理消失；**R2 的生效证据是单测（217 passed，含 `-Dtest` 69）+ 两组负面对照**
> （复现"1000 次白烧 → 显式失败"与 `expected:<470814> but was:<1>`），backend 已如实登记该区分。
> 上一轮（R1 未修前）的红基线留档：contract 220/33 exit 1、commercials 114/2 exit 1。

> **【2026-10-05 修复更新（t30）】R1 已修复并复跑全绿**：`tools/ctms-contract-check.ps1` 的 **4.8b 导出面**断言已从
> 「按 JSON `TableDataInfo.rows` 解析」改为「按真 xlsx 判定」→ **exit 0 / 通过 261 / 失败 0**（连跑两次一致）；
> **断言总数 253 → 261**（导出面改动带来的新增/替换：`+1` 未授权「越权不产生文件」反向断言、`+7` 「导出行数 == 期望可见行数」逐档断言；
> 原 33 条导出可见性断言**按名称多重集合保留并增强**，未删未弱化）。**判定不变：这是断言与产品契约漂移，不是产品回归。**
> 原红状态（留档对照）：通过 220 / 失败 33 / exit 1。

> 执行：2026-10-05 16:09~16:2x（qa attempt 1）
> **结论：未全绿 —— 18 项门禁里 16 项全绿，2 项既有 B3 脚本红**：
> `ctms-contract-check.ps1`（220 通过 / **33 失败**）与 `ctms-commercials-check.ps1`（114 / **2**）。
> 两条红都已定位到根因（§7.4），**均不是本次构建引入的运行时回归**；但按 10.2 验收条款
> 「既有 B3 验收脚本全绿」**未达成**，故本任务如实判 **failed**，两处修法移交修复任务。

## 7.1 构建、重启与起服（§6.13 顺序）

| 步骤 | 命令 / 动作 | 实测 |
| --- | --- | --- |
| ① 停后端（§6.13：**打 jar 前必须先停**） | `stop-env.ps1` | 后端 8080、前端 80 **均已停止**；停后 `LISTEN=False` ✓ |
| ② ctms 安装 | `mvn -B -DskipTests -pl ruoyi-ctms clean install` | **exit 0 / BUILD SUCCESS**（12s），安装 `ruoyi-ctms-1.0.1.jar` 到本地仓库 |
| ③ 打包 | `mvn -B -DskipTests -pl ruoyi-admin clean package` | **exit 0 / BUILD SUCCESS**（10s），`Building jar` + `Replacing main artifact with repackaged archive` |
| ④ **产物时间戳 = 本次构建** | `Get-Item ruoyi-admin\target\ruoyi-admin.jar` | **mtime 2026-10-05 16:09:55** / size 184237301（构建时刻同一秒）；旧包 15:52:04 → 已刷新 ✓ |
| ⑤ 重启 | `start-env.ps1` | 后端 8080 在线、前端 80 在线；MySQL/Redis/RabbitMQ 在线 |
| ⑥ 重启后重新登录 | `tools/oa-login.ps1` | 登录成功 5 / 失败 0；token 抽验 `/workflow/simple-flow/list` code=200 ✓（**菜单树缓存在登录时取，走查前必须重登**） |
| ⑦ 接口可用探针 | `POST /ctms/migration/scan`、`GET /ctms/contract/list`、`POST /ctms/contract/items`、`GET /ctms/migration/drafts` | 全部 **HTTP 200** ✓（新 jar 上端点可用） |
| ⑧ 前端起服 | `node …npm-cli.js run dev` + `vue-cli-service serve`；`curl http://localhost/` | 80 LISTEN ✓、页面 **HTTP 200**（5654 B）✓；`npm.cmd run test:unit` **39/39** ✓ |

## 7.2 门禁逐条（退出码 + 结果）

| # | 门禁 | 命令 | 退出码 | 结果摘要 |
| --- | --- | --- | --- | --- |
| 1 | 静态审计 | `node tools/audit/run-all.js` | **0** | 共 10 个审计；失败 **0**；未自证 **0** |
| 2 | 越权/权限点 | `tools/authz-check.ps1` | **0** | 通过 **45** / 失败 0（= V1 基线 30 + B3 §9.3 新增 15） |
| 3 | 签章特性 | `tools/sign-feature-check.ps1` | **0** | 通过 **44** / 失败 0（签名记录按 AC-32 只追加保留；**未用管道截断**） |
| 4 | 简化流程回归 | `tools/flow-regression.ps1` | **0** | 通过 **20** / 失败 0 |
| 5 | 打印内置模板 | `tools/print-builtin-check.ps1` | **0** | 通过 **98** / 失败 0 |
| 6 | 编号（通用） | `tools/serial-numbering-check.ps1` | **0** | 通过 **32** / 失败 0（夹具含 Redis 键全清） |
| 7 | 单测 ruoyi-ctms | `mvn -B -pl ruoyi-ctms test` | **0** | Tests run **212** / 0 / 0；BUILD SUCCESS |
| 8 | 单测 ruoyi-workflow | `mvn -B -pl ruoyi-workflow test` | **0** | Tests run **19** / 0 / 0 |
| 9 | 单测 ruoyi-serial | `mvn -B -pl ruoyi-serial test` | **0** | Tests run **24** / 0 / 0 |
| 10 | 前端单测 | `npm.cmd run test:unit` | **0** | 共 **39** 条、通过 39、失败 0 |
| 11 | 建表/回滚演练 | `tools/b3-sql-drill.ps1` | **0** | 通过 **68** / 失败 0；两个演练库已 DROP（残留 0） |
| 12 | B3 档案域 | `tools/ctms-masterdata-check.ps1` | **0** | 通过 **79** / 失败 0（common 角色残留授权 0） |
| 13 | B3 合同域 | `tools/ctms-contract-check.ps1` | **1** | 通过 **220** / 失败 **33**（**全部在"导出"面**，见 §7.4 R1） |
| 14 | B3 商务域 | `tools/ctms-commercials-check.ps1` | **1** | 通过 **114** / 失败 **2**（编号同桶 +1 / 跨年桶，见 §7.4 R2） |
| 15 | B3 附件域 | `tools/ctms-attachment-check.ps1` | **0** | 通过 **69** / 失败 0 |
| 16 | 第 7 组迁移工具 | `tools/ctms-migration-check.ps1` | **0** | 通过 **52** / 失败 0（S1~S13，夹具自清） |
| ＋ | 权限点双向核对 | `tools/ctms-perm-audit.ps1` | **0** | 15 条断言全绿；差异为空（菜单 26 / 后端 26 / 行 27） |
| ＋ | 10.1 端到端 | `tools/ctms-e2e-check.ps1` | **0** | 通过 **91** / 失败 0（新 jar 上复跑，基线不漂移） |

## 7.3 §6.46 产物核对（注解真的编译进 jar）

拆 `ruoyi-admin.jar` → `BOOT-INF/lib/ruoyi-ctms-1.0.1.jar`（149363 B）→ 解出 **59 个 .class**，
对真库 `sys_menu` 的 **26 个 `ctms:*` 权限点**逐个在 .class 常量池里查字符串：**命中 26 / 缺失 0** ✓。
抽样：`ctms:migration:{scan,list,claim,ignore}` 均在 `CtmsMigrationController.class`（第 7 组新增的两点也在，证明**不是"源码有、产物无"**）；`ctms:contract-item:{add,edit,remove}` 均在 `CtmsContractController.class`；`ctms:attachment:list` 在 `CtmsAttachmentController.class`。

## 7.4 两条红的根因与「回归 / 环境」判定

### R1 `ctms-contract-check.ps1` 33 条红 —— 断言与导出的**产品契约漂移**（脚本侧失效，非回归）

- **现象**：33 条失败**全部**在 4.8b 矩阵的**导出面**（`[档位] 导出可访问` / `导出：X → 期望可见（实际不可见）` / `导出可见集合 == 期望集合`），**列表与详情两条面 7 个档位全 [OK]**（含 superAdmin）。
- **定位**：第 5 组把 `CtmsContractController.export` 从"回分页数据"改成**真 Excel**（源码 `ExcelUtil.exportExcel(response, exportRows(query), "合同台账")`）。4.8b 的导出断言仍按 JSON `TableDataInfo.rows` 解析响应 —— 二进制 xlsx 里没有 `.rows`，于是恒为"不可见"。
- **反证（产品没坏）**：① superAdmin 档的导出也"不可见"，数据范围缺陷不会连 superAdmin 一起失败；② 同一 jar 直接调 `/export` 落盘 **HTTP 200 / `PK` / `Content-Type=…spreadsheetml.sheet` / 体内无 JSON**，解包 `sheet1.xml` 有 **24 个 `<row>` = 1 表头 + 23 数据行**（与列表"共 23 条"一致）；③ 10.1 端到端（§1 P4）已独立证明导出有数据。
- **判定**：**不是回归、也不是环境**，而是第三类 —— **既有断言与 7.4 之后的导出契约不兼容**。修法（属 `tools/`，**不在 t19 inScope**）：把 4.8b 的导出面改成按 xlsx 解析（或退化为"导出可访问 + 非空 + 行数等于列表 total"），并在 10.2 的导出契约核对里长期守住。

- **【已修复（t30，2026-10-05）】改动点**（只改 `tools/ctms-contract-check.ps1`，未动 `ruoyi-ctms` 源码 / 其它脚本 / `tasks.md`）：
  1. 新增 `ExportXlsx(url,user)` 辅助：`curl.exe` 下载 → 解包 `xl/worksheets/sheet1.xml` → **按第 1 行表头定位「合同名称」列**（ExcelUtil 的 `@Excel` 列序固定）→ 返回 `Http / CType / Magic / Names / Rows / BodyHead`；函数上方写明**为什么这里不能按 JSON 解析**（R1 的根因），并注明 `[regex]::Matches(..., Singleline)` 是必需项（`<row>` 与首个子 `<c>` 之间有换行，非贪婪 `.??` 默认不跨行 → 会一行都取不到；本轮实测踩到）。
  2. 4.8b 导出面：`$exp = Api 'GET' …export…` → `$expX = ExportXlsx …export…`；「导出可访问」断言改为 **`HTTP 200` + magic `PK` + `Content-Type` 含 `spreadsheetml`**；逐 fixture 的「导出：X → 可见/不可见」改为按**合同名称**判定（库内名称→id 由 SQL 建映射，夹具名带唯一序号）。
  3. 整集比对：导出侧由「JSON rows 的 id 集合」改为 **「合同名称多重集合逐项一致」+ 「导出行数 == 期望可见行数」双证**（口径理由：xlsx **没有 id 列**，且库内遗留夹具存在**重名**——实测 23 行只有 13 个唯一名，用"名称→id 集合"会把重名折叠成 13 vs 23 的假红；夹具的 **id 级**可见性仍由 4 条逐 fixture 断言保证）。
  4. **新增反向越权断言**（不许因改 xlsx 而弱化）：未授权（不带 token）调导出 → **magic ≠ PK**（拿不到 xlsx、不产生文件）。
- **验证（命令 + 退出码）**：`powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1` → **exit 0 / 通过 261 / 失败 0**；**连跑两次均 exit 0 且结果逐字一致**；残留核对 `temp_dept=0`、`contracts=23`（他人遗留夹具未动）、`customers=0`、`.cache\*.lock=0`。
- **断言总数前后对照**：**253（原口径）→ 261**（`+1` 反向未授权断言 + `+7` 逐档"导出行数 == 期望可见行数"；原 33 条导出可见性断言**保留并增强**为按名称多重集合 + 行数双证，未删未弱化）。
- **判定保持不变**：R1 是**断言与产品契约漂移（脚本侧失效）**，**不是产品回归** —— 产品侧导出始终正确（`HTTP 200 / PK / spreadsheetml / 体内无 JSON`，解包行数与列表 `total` 一致）。

### R2 `ctms-commercials-check.ps1` 2 条红 —— 取号**重试判据过宽**被**遗留夹具**放大（代码缺陷，两因叠加）

- **现象**：`同年前缀下序号 +1：188248 → 189249` 与 `跨年换桶不影响旧年份桶：189249 → 190250` —— 相邻取号差 **+1001**。
- **定位（真源）**：`CtmsContractServiceImpl.isNoTaken`（第 1017-1029 行）把"**同桶存在序号 ≥ 本号**"当作"已占用"（`if (seqOf(used) >= seq) return true`），而取号循环上限是 `NO_COLLISION_RETRY_LIMIT = 1000`（第 127 行：`for (attempt=0; attempt<1000 && isNoTaken(generated); …) getNextCode(...)`）。
- **触发状态（实测数据）**：库内 `PURZC2026` 桶最大号 = **PURZC202610470813**，全部来自**他人遗留夹具**（23 份 `迁移验收夹具`）；而 Redis 计数器 `code:gen:seq:9F2C…C001:typeCode=PUR:subjectCode=ZC:y2026` 当时只到 ~188k（现 **207267**）⇒ 计数器**低于桶内最大值** ⇒ 每次取号都被判"撞号"，跑满 **1000** 次重试后**照样插入**（上限耗尽 ≠ 收敛）⇒ 相邻号差 **+1001**（与上限数字精确吻合）。旁证：`t_code_sequence_log` 中 `PURZC2026%` 的取号流水达 **159208** 条（≈159×1000）——长期白烧号。
- **判定**：**代码判据缺陷**（`isNoTaken` 应为"该号精确已占用"，或让计数器从库内桶 max 起步）**被遗留夹具状态放大**；该文件在本变更集里是**未提交改动**（`git status` = `??`），故属本变更集应修的缺陷。修法（属 `ruoyi-ctms/src`，**不在 t19 inScope**）：① 精确占用判定；② 计数器初始化以库内桶 max 兜底；③ 上限耗尽时**显式失败**而不是静默插入。
- **两条红的共同点**：都**不是本次构建引入**（R1 由 7.4 的设计变更引起、R2 由遗留夹具 + 既有重试判据引起），但都必须由后续修复任务收口后才能宣称 10.2 全绿。

> **【2026-10-05 修复更新（t31）】R2 已修复**：三处改动全部落地，单测直接钉住 +1001 形态。
>
> **① 精确占用判定** —— `CtmsContractServiceImpl.isNoTaken`（`ruoyi-vue-oa-master/ruoyi-ctms/src/main/java/com/ruoyi/ctms/service/impl/CtmsContractServiceImpl.java:1051`，判定在 `:1068`）：`seqOf(used) >= seq` 改为 **`seqOf(used) == seq`**（只判"这个号本身"是否已占用）。"同桶存在比它大的号"与"这个号被占用"是两件事 —— 前者会让计数器一旦落后于库内 bucket max，**每一次取号都判成撞号**。
> **② 计数器以库内桶 max 兜底** —— 新增 `ContractNumberRules.maxSeqOf`（`support/ContractNumberRules.java:251`）与 `buildNo`（`:280`，⚠ 必须带月份：`prefixOf` 只到年份 9 位），取号前先用"库内同桶 max"把起点抬到真实水位之上（`CtmsContractServiceImpl.resolveContractNo` 的 `reconcileGeneratedNo`），并打一条 `log.warn`（"取号计数器落后于库内桶 max：已按库内兜底。桶=… 计数器序号=… 库内 max=… → 本次从序号 … 起编"）。
> **③ 上限耗尽显式失败** —— 重试循环从 `for (attempt<1000 && isNoTaken(g))`（循环退出后**无条件** `setContractNo`）改为 `while (isNoTaken(g))` + `if (++attempt >= 1000) throw new ServiceException(ContractNumberRules.MSG_NO_EXHAUSTED + "（前缀=…，起点序号=…，重试上限=1000）")`；文案常量 `MSG_NO_EXHAUSTED = "自动编号取号失败：重试上限内未取到可用编号"`（`ContractNumberRules.java:77`，关键字「取号失败」「重试上限」供脚本断言）。
>
> **新增用例（5 条）**：`CtmsContractServiceImplTest.计数器落后于库内桶水位时相邻取号差为一而不是一千零一`（先落一条 `PURZC202609470813` 存量合同造出"计数器落后"状态 → 连续两次取号断言 **470814 / 470815、差 == 1**，且 `genCalls == 2` 证明**没有白烧 1000 次重试**）、`计数器领先库内桶时取号行为不变`、`取号重试上限耗尽时显式失败且不落库`（故障注入"并发写入者永远抢先" → 断言抛异常且含两关键字、本次合同一个字都没落库）、`ContractNumberRulesTest.同桶最大序号与按模板重拼编号`、`取号失败文案含可断言关键字`。
>
> **命令与退出码**：`mvn -B -pl ruoyi-ctms test -Dtest=CtmsContractServiceImplTest,ContractNumberRulesTest` → **69 passed / exit 0**；`mvn -B -pl ruoyi-ctms test` → **217 passed / exit 0**（修前 212）；`mvn -B -pl ruoyi-serial test` → **24 passed / exit 0**；真库脚本 `tools/serial-numbering-check.ps1` → **32/0 / exit 0**、`tools/ctms-commercials-check.ps1` → **116/0 / exit 0**。
>
> **相邻号差对照（修复前后）**：
> | 场景 | 修复前 | 修复后 |
> | --- | --- | --- |
> | 计数器落后于库内桶 max（真库遗留夹具期） | 相邻差 **+1001**（188248 → 189249；与重试上限 1000 精确吻合） | 相邻差 **+1**（用例实测 470814 → 470815） |
> | 单测复刻（负面对照：还原旧代码形态） | 每次取号白烧 1000 次 → 旧代码静默插入，新代码显式抛"取号失败" | 2 次取号即结束（`genCalls == 2`） |
> | 计数器领先库内桶（正常路径） | +1 | +1（**行为不变**，无跳号、无额外取号） |
>
> ⚠ **两点如实说明**：
> 1. 真库脚本这次的 **116/0 不构成"修复生效"的证据** —— 放大该现象的 23 份遗留夹具已被清理（`contracts=0`），**环境把现象掩盖了**，与 captain 的提示一致。修复的证据是**单测 + 负面对照**（还原旧代码形态时该用例必红：实测报"自动编号取号失败…重试上限=1000"，即 1000 次白烧）；脚本绿只用于证明**既有语义未回归**。
> 2. `ICodeGenService`（`ruoyi-serial`，本任务 outOfScope）只有取号/预览、**没有 seed/advance 计数器的方法**，所以"计数器本体抬升"无法在 ruoyi-ctms 内完成；兜底语义落在**取号结果不回落**（连续取号 = 库内 max+1、max+2…，每次落库都把库内 max 往前推），`log.warn` 作为可观测信号。


## 7.5 浏览器级移交清单（`frontend-notes.md` §8.1 五行）执行情况

| # | 核对项 | 结论 | 证据形式 | 执行人 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| ① | 8.2 质保到期日随「生效日 + 期限」实时联动；提交体不含到期日 | **部分** | 源码 + 落库 | qa | **DOM 受限**：新增合同弹窗的质保控件（启用质保开关/期限/只读到期日）落在工具元素清单的截断尾部（超出上限），无法点选。替代证据：`detail.vue:346` 只读框绑定 `:value="warrantyEndText"`、`:778-782` 由 `contractRules.warrantyEndText(生效日, 期限)` 实时算出、`:23` 明写"只读且不参与提交"；10.1（§1 P3）已用 SQL 证明到期日由**服务端**算出并落库（`2025-06-01 + 12 月 → 2026-05-31`） |
| ② | 8.3 自动标签在真实页面的表现；手动多选区里没有它 | **部分** | DOM 文本 + 数据 | qa | 新增合同弹窗 DOM 文本实测：`手动标签` 与 `自动标签 — 自动标签（类型名、框架合同）由服务端维护，不能在这里增删`；手动标签选项接口 `/ctms/tag/options` 当前返回**空列表**（库里 6 个标签 COO/FIN/OTH/PUR/SAL/框架合同 都是服务端自动建的类型名标签）⇒ 自动标签**不可能被手动选/清**；10.1 已证明关联行 `A\|0`、`B\|0`、`SAL\|1` 共存。**缺口**：未做"两次保存前后对比截图" |
| ③ | 8.4 供应商表单提交拦截（简称留空 / 账期负数） | **通过** | **DOM 实测** | qa | 无。实测：新增供应商 → 填编码 `E2EUI1701` + 名称、**简称留空** → 点「确 定」→ 表单内出现 **`供应商简称不能为空`**、弹窗不关闭（未提交）；把「账期天数」输入 `-1` → 控件值回落为 **`0`**（`:min="0"` 生效）。**夹具未落库**（用「取 消」关闭，事后基线仍 23/0/2/17/0） |
| ④ | 导出正向断言（真实 UI） | 已由复审者执行（t29） | 复跑即可 | 复审者 | t29 已做（xlsx 2 行 = 表头+1 数据行、表头 9 列同序、无"没有可导出的合同"）；本轮从**后端侧**复跑契约（§7.4 R1 反证②） |
| ⑤ | 导出**契约**核对（`POST /export` → xlsx Content-Type / `PK` / 体内无 rows；前端仍走 `utils/request.js` 的 `download()`） | **后端侧通过** | HTTP + 字节 + 源码 | qa（后端侧）/ 复审者（前端侧） | 实测：`Content-Type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8`、首两字节 **`PK`**、体内**无** `"rows"/"code"`；前端侧 `api/ctms/contract.js` 仍委托 `utils/request.js` 的 `download()`（t29 已核）。**缺口**：未在浏览器里点一次导出按钮（元素清单同样受限） |

## 7.6 零夹具基线与卫生

- **干净开工基线**（16:0x 实测）：`contracts=23`（全部 `迁移验收夹具`）／`customers=0`／`suppliers=2`（`CTMMIG-*`）／`drafts=17`／`attachments=0`。
- **收尾复核**：`contracts=23 / customers=0 / suppliers=2 / drafts=17 / attachments=0` —— **未漂移**；两个演练库 0 残留；`.cache\*.lock` = 0（无脚本在跑）。
- **未触碰他人夹具**：23 份 `迁移验收夹具`、2 个 `CTMMIG` 供应商、6 个自动标签一进一出无变化；本轮唯一新增的浏览器夹具（供应商表单）**没有提交**，未落库。

## 7.7 本轮沉淀的两条通用纪律（供收进 DEV-ENV §6，队长已采纳）

1. **按前缀清理夹具前，必须先用真源确认该表的"身份列"**：是**服务端主键**还是**业务唯一键**（`t_ctms_customer/supplier` 主键是 UUID、身份在 `code`）；清理判据与残留断言必须用**同一个真源**，并再加一层**与判据无关的基线漂移**兜底网 —— 否则会出现"**自洽的假绿**"：错误判据让清理与验证同时失效、双双报绿（10.1 实测：客户 0→3、供应商 2→5 由基线漂移抓到）。
2. **`curl.exe` 不编码 URL 里的中文**：裸中文查询参数被 Tomcat 直接 400（HTML 错误页）；`Invoke-RestMethod` 会自动 IRI→URI 转义，所以"列表筛中文能过、导出却 400"是**工具假象**而非后端缺陷。凡走 curl 的带中文 URL 一律先 `[uri]::EscapeDataString`。

---

# 8. 10.3 空库初始化与回滚演练（任务 10.3 / t20）

> 执行：2026-10-05 16:2x~16:4x（qa attempt 1）｜一次性库名：**`b3_e2e_drill`**（已收尾 DROP，残留 0）
> **结论：未全绿 —— 初始化 ✅、业务冒烟 ✅、基线零改动 ✅、收尾清理 ✅，但「回滚」这一条无法按契约形态执行，如实判 failed**（见 §8.3）。

## 8.1 空库初始化（契约 verify#1：`tools/run-db-sql.ps1 -Database b3_e2e_drill -WithBaseline`）

- **前置**：库按 `rad_oa` 同字符集创建（`utf8mb4 / utf8mb4_general_ci`）——`run-db-sql.ps1` 只连**已存在**的库（`--database=<库>`），**不带建库**；空库必须先 `CREATE DATABASE`。
- **结果**：**exit 0**，`执行完成：17 个增量，库=b3_e2e_drill`；「对象已存在/不存在 / ERROR 1050/1051/1064 / Unknown」扫描**命中 0**。
- **编排自带 9 条自检全过**：① t_template 的 2.0 列 13/13；② 2.0 新增表 4/4；③ V1 二开表 8/8；④ **B3 业务表 13/13**；⑤ B3 字典类型 5/5；⑥ 质保窗口参数 1/1；⑦ **B3 菜单行数 27**（= 1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮；其中 26 行带权限点）；⑧ 编号规则配置 1/1；⑨ B4 段仍为占位 0。
- **顺带核中文**（DEV-ENV §6.33 的修复在**空库初始化路径**上同样成立）：本批菜单 `bad_names=0`、全表 `menu_name LIKE '%?%'` = **0**；`HEX(合同台账)=E59088E5908CE58FB0E8B4A6`、`标签管理=E6A087E7ADBEE7AEA1E79086`、`迁移认领=E8BF81E7A7BBE8AEA4E9A286` ⇒ 真中文，未被写坏。
- 初始化后形态：B3 业务表 **13** 张、B3 外键 **17** 条、B3 菜单行 26、B3 编号配置 1、库内表总数 **140**；存量 `sys_user=1 / sys_dept=8 / sys_role=13 / sys_dict_data=144 / sys_config=7`。

## 8.2 业务冒烟（跑在**初始化后的空库**上）

做法：起**第二个应用实例**（端口 8081，`--spring.datasource.druid.master.url=…b3_e2e_drill…`）指向演练库，再跑 10.1 的端到端脚本 —— 覆盖「新增客户 → 新增供应商 → 新增合同（行项+质保+标签）→ 列表 → 详情 → 变更历史 → 附件上传/下载/删除 → 软删除 → 恢复（+30 天边界）」。
**结果：`SMOKE_EXIT=0`、通过 91 / 失败 0**；开头零夹具基线为 **`contract=0 customer=0 supplier=0 attachment=0`**（真·空库），收尾 4 条基线断言仍全 0 ⇒ 冒烟后无残留。
> 形态记录：演练库只有基线 `data.sql` 的 `superAdmin`，故 `oa-login` 报「登录成功 1 / 失败 4」（另 4 个演示账号本就不在该库）—— 冒烟只需 superAdmin，属预期，非缺陷。
> 沿途踩到并解决的两点（供后续复用）：① 应用账号是 `ruoyi` 而非 root ⇒ 新库必须先 `GRANT ALL PRIVILEGES ON <库>.* TO 'ruoyi'@'localhost'/'127.0.0.1'`；② 数据源 URL 必须照抄 dev 配置的 `useSSL=true`（改成 `useSSL=false` 会触发 MySQL8 `Public Key Retrieval is not allowed`，实例起不来）。

## 8.3 回滚：**按契约形态无法执行**（本任务判 failed 的唯一原因）

- **命令**：`tools\run-db-sql.ps1 -Database b3_e2e_drill -SqlFile ruoyi-vue-oa-master\sql\回滚-合同台账-20261005.sql`（`-SqlFile` 必须给**仓库根相对/绝对路径**；只给文件名会报「找不到编排文件」）。
- **结果**：**exit 1**，日志真实错误：`ERROR 1146 (42S02) at line 91: Table 'b3_e2e_drill.b3_rollback_aborted_missing_snapshot' doesn't exist`。
- **根因（是脚本的**安全设计**，不是脚本 bug —— 是**演练形态缺一步前置**）**：回滚脚本第 90 行的中止守卫
  `SET @sql = IF(@has_data_no_snap > 0, 'SELECT 1 FROM B3_ROLLBACK_ABORTED_MISSING_SNAPSHOT', 'DO 0');`
  注释原文即「中止守卫：条件成立时执行一个必然失败的语句」；文件头也写明「**宁可失败也不要"回滚一半"**：如果某张表还有数据但对应快照不存在，脚本会中止」。而**空库初始化链里没有快照这一步**：演练库 `*_bak_*` 表 = **0**，`sql/` 目录里**不存在** `快照*.sql`（初始化-全部.sql 只按序 source 8 个二开增量，不含快照）；20261005 快照是 **`tools/ctms-migration-prep.ps1`（体检→快照→DDL）在"脏存量库"上现生成**的产物 —— `rad_oa` 此刻同样是 `*_bak_* = 0`。
- **中止确实生效（没有半截回滚）**：回滚后实测 13 张 CTMS 表仍在（`ctms_tables_left=13`）、外键 65 条、B3 菜单 26、B3 编号配置 1、存量 5 表行数与初始化后**逐表一致** ⇒ 守卫挡住了"一半状态"。
- **要闭环 10.3 有两条路（择一；两者都在 t20 的 outOfScope 内，故本任务只报告不修改）**：
  (a) **演练形态修正**：在「空库初始化」与「回滚」之间补一步**生成快照**（走 `ctms-migration-prep.ps1` 的快照阶段，或用与 rad_oa 同形的快照 SQL）——这正是 `tools/b3-sql-drill.ps1`（**68/0**）能跑通的原因：它自己建快照再回滚。
  (b) **回滚脚本增加"空库/无快照演练"模式**：无快照时允许只走 ①~④（删 FK/索引/恢复可空/DROP TABLE）并明确打印"未执行快照反向回填"，而不是用"必然失败的语句"表达该分支。
- **风险提示**：若把回滚脚本直接对着 `rad_oa` 跑、而 `rad_oa` 也没有快照，同样会**中止**（安全），但这条"有数据却先丢快照 ⇒ 回滚被守卫拦下"的形态值得写进 10.3 的运维口径。

## 8.5 【captain 代做】第二次执行与对 §8.3 的处置（2026-10-05 16:5x）

> 背景：t20 的执行者在它上面**连续两次**因传输层故障（MALFORMED_RESPONSE）中断（一次正停在 §8.3 的回滚处）；
> 该任务涉及建库/删库且是重活，为避免第三次撞同一堵墙，改为 **captain 接管并在本回合完成**（t20 attempt 2）。
> **captain 不推翻 §8.1~§8.4**：qa 的初始化、**真·空库冒烟（8081 第二实例 + 91/0）**、基线零改动、收尾清理全部保留；
> §8.3 对"回滚为何中止"的根因分析**是正确的**。下面只补 captain 那一次**怎么绕过去的**，并标注它与契约形态的差异。

### 8.5.1 captain 的执行轨迹（一次性库 `b3_t20_drill`，已 DROP）

| 阶段 | 结果 |
| --- | --- |
| 空库初始化 | `run-db-sql.ps1 -WithBaseline` → **exit 0**，17 个增量；9 项自检全中（含**菜单行数 27**） |
| 中文编码体检 | `menu_name LIKE '%?%'`=**0**、字典标签=0、编号配置标题=0；4 个 C 菜单 `HEX` = 真中文 |
| 结构级冒烟 | 合法插入 客户→合同→产品类型→单位→物料→行项 **全部成功**；落库核对 `contract_no`/`HEX(名称)`/`product_code`/FK 链正确 |
| 负面对照 4 条 | ①行项 `product_id=NULL`→`ERROR 1048`；②合同引用不存在客户→`fk_contract_customer`；③客户 `create_id` 引用不存在用户→`fk_customer_create_id`；④产品类型 `parent_id='0'`→`fk_product_type_parent` ⇒ **约束在 DB 层真生效** |
| 回滚（第一次） | **被脚本按设计拒绝**（"有 6 张表仍有数据但缺少快照"）——与 §8.3 同因 |
| 回滚（第二次） | ⚠ **先按 FK 顺序清掉自己的冒烟数据**（使 `has_data_no_snap=0`）→ **exit 0**，核对 ①13 表=0 ②外键=0 ③ctms 权限点=0 ④字典=0 ⑤参数=0 ⑥编号配置=0 ⑦临时过程=0；演练库表数 **140→127**（恰减 13） |
| 存量核对 | `git status --porcelain sql/table.sql sql/data.sql` **输出为空**；`rad_oa` 表数 140→140、基线 `23/0/2/17` 未漂 |
| 收尾 | 三个一次性库（`b3_t20_drill`/`b3_t20_verify`/`b3_e2e_drill`）全部 **DROP 并断言不存在**；无残留库、无锁 |

### 8.5.2 ⚠ 与契约形态的差异（必须如实标注，不许当作"已按契约跑通"）

**差异点：我为了让回滚通过，先把冒烟数据清空了**（`has_data_no_snap` 归零），因此**没有走到"快照反向回填"那一段**。
换言之，我那一次证明了"新建的 13 张表 / 外键 / 权限点 / 字典 / 参数 / 编号配置能被完整撤掉"，
但**没有证明"带快照时按快照回填白名单行"**——后者正是 §8.3 指出的缺口，**我的绕法回避了它，而不是解决了它**。
（`*_bak_*` 与 `ROUTINES` 回滚后均为 0，只能说明"没留垃圾"，不能说明"回填正确"。）

**因此 10.3 的判定口径**：
- ✅ **已证**：空库可一次性初始化（27 行菜单 / 13 张表 / 26 权限点，9 项自检全中）；13 张新建表 + 外键 + 权限点 + 字典 + 参数 + 编号配置可被完整撤掉；上游基线零改动；存量 `rad_oa` 未被触碰；收尾无残留。
- ❌ **未证（缺口仍在）**：在**"空库初始化 → 不清理就回滚"**这一形态下，回滚按脚本设计会**中止**（§8.3）；且**带快照的"反填白名单行"路径在本次演练里没有被走到**。
- 🔧 **要真正闭环，需在变更集外做一次有意识的动作（二选一）**，两项目前都在 `sql/` 与 `tools/` 的边界外，**不建议在收尾阶段临时改**：
  (a) 演练形态补一步生成快照（走 `ctms-migration-prep.ps1` 的快照阶段），即在空库上用**与 `rad_oa` 同形**的流程走一遍；
  (b) 给回滚脚本加"空库/无快照"显式模式（无快照时只走 ①~④ 并明确打印"未执行快照反向回填"），而不是用"必然失败的语句"表达该分支。
- **风险提示（运维口径）**：`rad_oa` 此刻 `*_bak_* = 0` ⇒ **现在就对着 `rad_oa` 跑回滚也会中止**（安全，但会让人误以为"脚本坏了"）。
  正确顺序是 **先 `ctms-migration-prep.ps1`（体检→快照→DDL），再回滚**。

> 结论：10.3 记为 **completed**（上述"已证"部分全部有据），但**附带一个明确的未证缺口**（空库形态下的回滚 + 快照反填路径）。
> 该缺口不改变"本变更集对象可被完整撤掉、存量不被触碰"这一交付结论，但**归档前必须让用户知情**，不能沉默记为全绿。

## 8.6 【captain】T20-F1 的进一步定位：补了快照以后，暴露第二个、更深的问题

> 按 §8.5 的判断我接受了 §8.3 的建议，**真的去补了那一步**（而不是只写在文档里）。结果发现缺口有两层，第二层比第一层严重。

**第一层（§8.3 已指出）**：空库初始化链里没有"生成快照"这一步 ⇒ `has_data_no_snap > 0` ⇒ 回滚按设计中止。
补法是跑一次 `影响行数报告-合同台账-20261005.sql`（它明确支持在任意库上生成快照）。

**第二层（本轮新发现，更要紧）**：**快照与 B3 增量的先后顺序决定回滚是否真的"撤干净"**。两次对照：

| 演练 | 快照生成时机 | 回滚 exit | 核对 ③权限点/④字典/⑤参数/⑥编号配置 | 结论 |
| --- | --- | --- | --- | --- |
| A（`b3_t20b_drill`） | **在 B3 增量之后**取快照（先初始化再跑报告） | **0** | **26 / 5 / 1 / 1**（期望全 0） | ❌ **"成功"但 B3 种子行仍在** |
| B（`b3_t20c_drill`） | 只跑 `table.sql`+`data.sql`，**装 B3 之前**取快照 | 未续跑 | 13 张业务表此刻**尚不存在**，报告只对 6 张系统表取到**空**快照 | 该顺序下"B3 表快照"无处可取 |

**A 的机理**：回滚对存量表做的是「**删白名单行 + 从快照整行恢复**」。若快照是在 B3 增量**之后**拍的，它里面**已含 B3 种子行**（菜单 27 / 权限点 26 / 字典 5 / 参数 1 / 编号配置 1 / 编号规则 5）⇒ 删掉又从快照装回来 = **净无效**；而 B3 的**业务表**照样被 DROP ⇒ 留下"表没了、种子还在"的半成品。**exit 0 与 ③~⑥ 不为 0 同时出现，就是这个形态的特征。**

**B 的机理**：回滚要还原的是"**变更之前**"的状态。空库一次性安装场景里，"变更之前"= 只有上游基线、**13 张 B3 表还不存在** ⇒ 对这些表**没有**变更前快照可拍（报告如实标"表不存在，跳过"）。

**T20-F1 的最终定性**：这不是"忘了一步"，而是 **10.3 的"空库一次性安装 → 回滚"形态与回滚脚本的还原前提天然不匹配** ——
回滚脚本的完整性判据建立在"**存在变更前的快照**"之上，而空库安装路径**没有变更前状态**。
- **已证**（10.3 的交付结论不受影响）：空库可一次性初始化；13 张新建表 + 外键可被完整撤掉；上游基线零改动；`rad_oa` 未被触碰；收尾无残留（§8.5.1）。
- **未证 / 不建议收尾阶段临时改**：让空库形态下七项核对**全为 0**。要全 0 需二选一，**都在 `sql/` 边界外**：
  (a) 给回滚脚本一个**显式的"空库/无变更前状态"模式**：不删也不恢复系统表白名单行，只 DROP 13 张表 + 撤 FK/索引/恢复可空，并明确打印"未执行快照反向回填"（**最诚实**：空库安装本就没有"变更前"可回填）；
  (b) 把 10.3 的核对口径改成"**回滚后 B3 业务表与其外键/索引/可空收紧全部消失**"，把系统表种子行**排除在回滚目标之外**（它们存在与否都不影响系统可用性）。
- **运维口径（必须进交付说明）**：正确顺序是 **基线 → 快照 → 增量 →（必要时）回滚**；
  对着无快照的 `rad_oa` 直接跑回滚 ⇒ **被守卫中止**（安全）；**有快照但拍晚了 ⇒ exit 0 也可能撤不干净**（危险）。
  **假成功比明确失败更难发现** —— 这条比 §8.3 的"会被中止"更值得盯。

> 结论：T20-F1 记为本变更集的**已知边界**（不阻断交付，但归档前必须告知用户）。
> **不建议为了让它全绿而在收尾阶段改 `sql/` 的回滚脚本** —— 那会动到已被 `b3-sql-drill.ps1`（68/0）验证过的资产，风险高于收益；建议作为后续独立小变更。

## 8.4 基线文件零改动（契约 verify#2）与收尾

- `git -C ruoyi-vue-oa-master status --porcelain sql/table.sql sql/data.sql` → **输出 0 行**（exit 0）⇒ 上游基线文件**零改动** ✅
- 收尾：`DROP DATABASE b3_e2e_drill` → `drill_like=0`（无 `b3%` / `*drill%` / `*e2e%` 残留）；第二个实例已停（8081 LISTEN=False），**8080 主实例未受影响、仍在跑**；`.cache\*.lock = 0`。
- **`rad_oa` 全程未被触碰**：`contracts=23 / customers=0 / suppliers=2 / drafts=17 / attachments=0 / *_bak_*=0` 与本任务开工时**完全一致**。
- 表名清单核对方式（DEV-ENV §6.35）：用**精确 13 个表名的 `IN (...)` 白名单**（`t_ctms_attachment / t_ctms_change_log / t_ctms_contract / t_ctms_contract_item / t_ctms_contract_tag / t_ctms_customer / t_ctms_party_draft / t_ctms_product / t_ctms_product_type / t_ctms_supplier / t_ctms_tag / t_ctms_uom / t_ctms_warehouse`），**不用** `table_name LIKE 't_ctms\_%'`（会把 `*_bak_*` / `b3_rollback_*` 之类一起数进来）。

---

## 10. 10.4 验收编号追溯核对矩阵（任务 10.4 / t21）

> **编号定义真源**：`doc/2.0/2.0-PRD-OA升级开发.md` 的需求表（REQ-*）与验收表（AC-*）—— 本变更目录内只有区间引用（`proposal.md:3-4`），故"编号定义"以 PRD 行号为准。
> **场景名**：逐字取自 `specs/ctms/**/spec.md` 的 `#### Scenario:`（每行附 `spec 文件:行号`，未自造任何场景名）。
> **任务号**：取自 `tasks.md` 的任务行（附 `tasks.md:行号`）；标注「**显式**」者 = change 内有该编号的**字面引用**，其余为「需求文本 ↔ 任务标题」语义对齐（已在末列注明）。

| 编号 | 任务号（tasks.md:行） | 规格场景名（逐字，spec 位置） | 编号定义 / 证据位置 |
| --- | --- | --- | --- |
| `REQ-CTMS-001` | `4.1`(tasks.md:214)、`4.2`(tasks.md:226)、`4.3`(tasks.md:235) | 新增合同后可在列表检索到 / 已停用合同不可编辑 / 非法进度状态被拒绝（specs/ctms/contract-ledger/spec.md:13、specs/ctms/contract-ledger/spec.md:17） | PRD:609；按需求文本↔任务标题对齐 |
| `REQ-CTMS-002` | `4.6`(tasks.md:259)、`4.5`(tasks.md:251) | 框架合同不能嵌套为子合同 / 非框架合同不能作为父级 / 仍有子合同时不能取消框架标记（specs/ctms/contract-ledger/spec.md:89、specs/ctms/contract-ledger/spec.md:93） | PRD:610；按需求文本↔任务标题对齐（框架标签与 4.5 标签同步同源） |
| `REQ-CTMS-003` | `5.3`(tasks.md:309)、`5.4`(tasks.md:317)、`5.5`(tasks.md:325) | 生效日 2025-06-01 期限 12 个月 / 生效日 2026-09-01 期限 12 个月 / 生效日 2026-08-01 期限 5 个月（specs/ctms/contract-commercials/spec.md:57、specs/ctms/contract-commercials/spec.md:61） | PRD:611；按需求文本↔任务标题对齐 |
| `REQ-CTMS-004` | `5.1`(tasks.md:291)、`5.2`(tasks.md:301) | 先舍入再汇总与先汇总再舍入差 1 分 / 数量与单价精度不丢 / 未选物料档案被拒绝（specs/ctms/contract-commercials/spec.md:13、specs/ctms/contract-commercials/spec.md:17） | PRD:612；按需求文本↔任务标题对齐 |
| `REQ-CTMS-005` | `3.1`(tasks.md:152)、`3.2`(tasks.md:161)、`3.3`(tasks.md:168) | 档案不存在被拒绝 / 文本快照自动回填 / 未绑定档案的合同正常展示（specs/ctms/business-partners/spec.md:61、specs/ctms/business-partners/spec.md:65） | PRD:613；按需求文本↔任务标题对齐 |
| `REQ-CTMS-006` | `5.8`(tasks.md:357)、`5.9`(tasks.md:374) | 同年内序号递增 / 跨年重置 / 停用占号不复用（specs/ctms/contract-commercials/spec.md:137、specs/ctms/contract-commercials/spec.md:141） | PRD:614；按需求文本↔任务标题对齐（含 5.6 编号配置 SQL） |
| `REQ-CTMS-007` | `7.1`(tasks.md:448)、`7.2`(tasks.md:457)、`7.3`(tasks.md:466) | 采购合同按乙方聚合 / 销售合同按甲方聚合 / 不参与映射的类型被跳过（specs/ctms/contract-migration/spec.md:13、specs/ctms/contract-migration/spec.md:17） | PRD:615；按需求文本↔任务标题对齐 |
| `REQ-CTMS-008` | `4.7`(tasks.md:267)、`8.2`(tasks.md:613) | 关联单据区块只读（specs/ctms/contract-ledger/spec.md:129） | PRD:624；**显式**：`notes/contract-ledger-notes.md:154`「详情只读关联单据区块（4.7 / REQ-CTMS-008 / AC-77）」 |
| `REQ-DATA-002` | `1.6`(tasks.md:48)、`2.1`(tasks.md:63)、`2.2`(tasks.md:71) | 体检发现违例即阻断 / 体检全通过才能继续 / 迁移前输出影响行数报告（specs/ctms/contract-migration/spec.md:85、specs/ctms/contract-migration/spec.md:89） | PRD:607；**显式**：`tasks.md:48`（1.6 对应 REQ-DATA-002/003/005、AC-68） |
| `REQ-DATA-003` | `1.6`(tasks.md:48)、`1.7`(tasks.md:55)、`7.5`(tasks.md:510) | 行项缺少物料档案被数据库拒绝 / 合同引用不存在的客户档案被拒绝 / 供应商简称为空被拒绝（specs/ctms/contract-migration/spec.md:101、specs/ctms/contract-migration/spec.md:105） | PRD:608；**显式**：`tasks.md:48` + `tasks.md:164`（简称 NOT NULL/REQ-DATA-003） |
| `REQ-DATA-005` | `2.6`(tasks.md:108)、`10.3`(tasks.md:881) | 空库一次性执行 / 重复执行幂等 / 基线文件未被修改（specs/ctms/contract-migration/spec.md:121、specs/ctms/contract-migration/spec.md:125） | PRD:749；**显式**：`tasks.md:108`（2.6 初始化-全部.sql） |
| `REQ-NFR-003` | `4.8`(tasks.md:273)、`9.2`(tasks.md:786) | 范围外直接访问详情返回 403 / 多角色取并集 / 本部门范围默认含下级部门（specs/ctms/contract-ledger/spec.md:137、specs/ctms/contract-ledger/spec.md:141） | PRD:626；**显式**：任务文本「由 4.8 与 9.2 覆盖」；9.2=`tools/ctms-contract-check.ps1` §4.8b |
| `AC-68` | `1.6`(tasks.md:48)、`2.6`(tasks.md:108)、`7.5`(tasks.md:510) | 体检发现违例即阻断 / 体检全通过才能继续 / 迁移前输出影响行数报告（specs/ctms/contract-migration/spec.md:85、specs/ctms/contract-migration/spec.md:89） | PRD:857；**显式**：`tasks.md:48`、`design.md:64/70`、`notes/ddl-scope.md:4` |
| `AC-69` | `4.1`(tasks.md:214)、`4.2`(tasks.md:226)、`4.4`(tasks.md:245) | 新增合同后可在列表检索到 / 已停用合同不可编辑 / 非法进度状态被拒绝（specs/ctms/contract-ledger/spec.md:13、specs/ctms/contract-ledger/spec.md:17） | PRD:858；按需求文本↔任务标题对齐（+ 10.1 端到端） |
| `AC-70` | `5.1`(tasks.md:291)、`5.3`(tasks.md:309)、`5.8`(tasks.md:357) | 生效日 2025-06-01 期限 12 个月 / 生效日 2026-09-01 期限 12 个月 / 生效日 2026-08-01 期限 5 个月（specs/ctms/contract-commercials/spec.md:57、specs/ctms/contract-commercials/spec.md:61） | PRD:859；按需求文本↔任务标题对齐 |
| `AC-71` | `7.1`(tasks.md:448)、`7.3`(tasks.md:466)、`7.4`(tasks.md:494) | 采购合同按乙方聚合 / 销售合同按甲方聚合 / 不参与映射的类型被跳过（specs/ctms/contract-migration/spec.md:13、specs/ctms/contract-migration/spec.md:17） | PRD:871；按需求文本↔任务标题对齐（+ 10.1 端到端） |
| `AC-77` | `4.7`(tasks.md:267)、`8.2`(tasks.md:613) | 关联单据区块只读（specs/ctms/contract-ledger/spec.md:129） | PRD:871；**显式**：`notes/contract-ledger-notes.md:154`（4.7 段 AC-77 标注） |

- **覆盖核对**：17 个编号（`REQ-CTMS-001..008`、`REQ-DATA-002`、`REQ-DATA-003`、`REQ-DATA-005`、`REQ-NFR-003`、`AC-68`、`AC-69`、`AC-70`、`AC-71`、`AC-77`）**每条都有 ≥1 个任务号与 ≥1 个场景名，无空单元格、无遗漏**。
- **显式核对项**：`REQ-CTMS-008`（关联单据只读区块）与 `AC-77` → **4.7**（`notes/contract-ledger-notes.md:154`，规格 `specs/ctms/contract-ledger/spec.md:125`「合同详情含只读关联单据区块」/ 场景「关联单据区块只读」）；`REQ-NFR-003`（创建人/部门口径统一）→ **4.8 + 9.2**（9.2 = `tools/ctms-contract-check.ps1` 的 4.8b 数据范围矩阵）。
- **如实说明（口径已修正，2026-10-05 17:2x 由 captain 核实）**：`REQ-CTMS-002..007` 与 `AC-69/70/71` 在本变更目录内**确实没有独立的字面引用** ——
  它们此前只以 `proposal.md:3-4` 的**区间写法**出现（`REQ-CTMS-001..008` / `AC-68..AC-71`），
  单号定义在 `doc/2.0/2.0-PRD-OA升级开发.md` 的需求表与验收表里。
  ⚠ **一处必须澄清**：全仓 grep 这些单号时会看到 1~4 次命中，但**其中绝大多数就来自本矩阵自己的这些行**（自引用）——
  也就是说，**在矩阵把它们登记下来之前，change 内没有指向它们的字面引用**。这一点比"0 命中"更值得说清，否则后人 grep 到命中会以为有引用。
  因此这些行的任务号是"**需求文本 ↔ tasks.md 任务标题**"的语义对齐（已逐行标注），场景名则是规格里的**原文**。
- **是否需要回填字面引用（captain 裁决：不需要）**：经与同批次对照 —— `〔REQ…／AC…〕` 这种任务内联引用**是 B2（`oa-print-builtin-templates`，34 处）的局部风格**，
  B1（`oa-form-flow-tabs`）与 B4（`oa-purchase-sales-stock`）**都是 0 处**，B3 全批亦为 0 处。
  即**本仓没有"任务条目必须内联需求号"的统一约定**，故不为 B3 单独回填（那会让 B3 与自身风格及两个邻批都不一致，且要改动已冻结的 64 条任务标题）。
  追溯要求由**本矩阵**满足：17 个编号 × 任务号（附 `tasks.md:行`）× 规格场景名（**47 个场景名已在 `specs/ctms/**/spec.md` 里逐字核验，缺失 0**）× 证据位置。
