# 第 8 组（前端页面）独立质量门禁复核 —— t17 / round 1

> 复核人：reviewer（独立复核，未修改任何实现代码）
> 复核时间：2026-10-05 15:50~16:20（Windows PowerShell 5.1 + node v24 + 真实浏览器 localhost）
> 被审对象：`tasks.md` 第 8 组 6 项任务 + specs `contract-ledger` / `contract-commercials` / `business-partners` / `contract-migration` 中与前端相关的场景
> 被审任务：t4（8.1）/ t7（8.2）/ t8（8.3）/ t9（8.4）/ t10（8.5）/ t11（8.6）
> 证据形态：**全部由本轮亲自复跑 / 亲自读源码 / 亲自点真实 UI / 亲自查库产生**；被审者自述只用于定位"该验什么"。

## 0. 结论

**verdict = needs_revision**

- 两条 verify 命令逐字复跑**全绿**（`node tools\audit\run-all.js` 10 审计 0 失败 exit 0；`npm.cmd run test:unit` 39/39 exit 0）；
- 5 项独立静态核对**全部通过**（权限点 ⊆ 菜单、日期记号零命中、`.then` 全有 `.catch`、附件口径零常量、自动标签不可清除）；
- 6 项任务的主链路大多有可复现证据（金额 5.01、到期日三例、标签交集、含停用开关、框架汇总、迁移页两态渲染、档案选择器只出启用项）。
- **但有一条 high 缺陷（F1）**：合同列表页「导出」仍按"后端返回分页 JSON"写，而第 7 组 t5 已把后端改成 `ExcelUtil` 流式 xlsx —— 真实 UI 上"列表有 1 条合同、点导出"得到 **「当前筛选条件下没有可导出的合同」**，导出功能不可用。按门禁口径不得以"大致符合"放行。

---

## 1. 复核口径与命令

| 项 | 值 / 命令 |
| --- | --- |
| 静态审计 | `cd F:\dsh\ruoyiOA ; node tools\audit\run-all.js` → **exit 0**，共 **10** 个审计 / 失败 **0** / 未自证 0 |
| 前端用例 | `cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master ; npm.cmd run test:unit` → **exit 0**，共 **39** 条 / 通过 **39** / 失败 0 |
| 真实 UI | 浏览器 `http://localhost/ctms/contract`、`/ctms/migration`（新开后台标签页操作，**收尾已关闭，用户原有 4 个标签未动**） |
| 真实库 | `rad_oa`，夹具前缀 `T17C/T17S/T17T/T17F/T17M/T17P`，**用完即撤**（收尾复核 `T17%` 残留 = 0） |
| 后端 | 8080 在跑（`ruoyi-admin.jar` 15:13:30）；token 用 `tools\oa-login.ps1` 重取 |

---

## 2. 映射表：tasks.md 第 8 组 6 项任务 ↔ 页面文件 ↔ 断言/命令 ↔ 证据

### 8.1 合同列表页（t4）

| 验证要求 | 页面文件 | 我的独立证据 |
| --- | --- | --- |
| 多维筛选（关键字/类型/进度/到货/是否框架/经办人/签订日期区间/标签多选） | `src/views/ctms/contract/index.vue`（`queryParams:522`、`buildQuery:587`） | `buildQuery` 字段名与后端查询字段逐字一致；UI 上 8 个筛选项均可渲染（快照 element 31~41） |
| 标签交集（选 N 个只出同时具备者） | 同上（`tagIds:532/600`）+ `src/utils/request.js:33`（GET→`tansParams`） | **真库实测**：插入 T17T1（仅 PUR）/T17T2（PUR+框架合同）→ `tagIds[0]=PUR` 返回 **2**；`tagIds[0]=PUR&tagIds[1]=框架合同`（前端 tansParams 下标键形态）返回 **1（T17T2）**；逗号形态同样返回 **1** → 交集口径与两条 wire 形态都成立（规格 `contract-ledger`「标签筛选取交集」） |
| 默认排除已停用 / 含停用开关 | 同上（`includeDeleted:534`、`v-model` 开关 `107-108`） | **真库 + UI 实测**：3 未停用 + 1 已停用 → UI 默认「共 3 条」；`includeDeleted=1` → total **4**，含 1 条 `delFlag=1`（规格场景「默认排除已停用合同」） |
| 框架树两级展示 | 同上（`loadFrameworkChildren:643`、`buildTreeRows:686`） | `GET /ctms/contract/framework/T17F1` → `childrenCount=2`、子合同金额合计 **90000**、框架自身金额 **100000**（规格 `contract-ledger`「框架详情汇总子合同」，两者分开呈现） |
| `node tools/audit/run-all.js` 全绿 | —— | exit 0 / 10 审计 0 失败（见 §1） |
| （任务原文）`npm.cmd run dev` 后手工核对 | —— | 记录已如实标注"未执行、留给 10.1/10.2"；我用真实 UI 补做了默认/含停用与导出两处（见 §4） |

### 8.2 合同详情与编辑页（t7）

| 验证要求 | 页面文件 | 我的独立证据 |
| --- | --- | --- |
| 行项 3 行 × 单价 1.665 显示合同金额 5.01 | `src/views/ctms/contract/contract-rules.js`（`lineTotalText:120`、`sumLineTotalsText:135`） | **我用 node 直接跑该模块**：`lineTotalText(1,'1.665')` = **1.67**；三行求和 = **5.01**；反面算法（先汇总再舍入）= **5.00**（规格 `contract-commercials` 场景①原文一致） |
| 到期日自动显示（2026-05-31 等） | 同上 `warrantyEndText:194`；`detail.vue:346`（只读 `el-input` + `:value="warrantyEndText"`）、`778-782` | node 实测：`2025-06-01 + 12 → 2026-05-31`、`2026-01-31 + 2 → 2026-02-28`、`2026-03-15 + 24 → 2028-02-29`、`月数空按 1 → 2026-03-31`、非法日期 → 空串（规格 `contract-commercials`「质量保证金到期日算法」5 条场景全部命中） |
| 到期日只读且不提交 | `detail.vue:1301`（注释）+ `buildPayload:1314-1349` | payload 字段逐项核对：**无 `warrantyEnd`**；质保字段仅在 `hasWarranty='1'` 时提交（与后端重算口径一致） |
| 质保金额 ↔ 比例联动 | `contract-rules.js:148/161` | node 实测：合同 100000 + 比例 5 → **5000.00**；合同 30000 + 质保金额 3000 → **10.0000**（规格场景一致） |
| 行项整单提交、不缓存行项 id | `detail.vue:1305-1347` | items 全量数组、**不含 id**；`api/ctms/contract.js` 注释与 `notes/frontend-notes.md:198-210` 一致 |
| 7 个日期控件无 `YYYY-MM-DD` | `detail.vue`（4 个 `el-date-picker` + 5 处 `value-format="yyyy-MM-dd"`，第 5 处在注释里）、`contract/index.vue`（1 个区间控件） | 大小写敏感门禁 **零命中**（见 §3-2）；日期值位 = 签订/生效/预计到货/质保生效/到期日只读/释放日期只读 + 列表区间 = 7，与任务口径一致 |
| 只读「关联单据」区块 | `detail.vue:527-533` | 区块存在、`empty-text` 明示"只读、不会回写合同任何字段"，无任何写调用 |
| 变更历史操作人占位符 | `detail.vue:502` | `operatorName \|\| '—'`（规格 `contract-ledger`「操作人为空时不报错」） |

### 8.3 标签管理与自动标签展示（t8）

| 验证要求 | 页面文件 | 我的独立证据 |
| --- | --- | --- |
| 标签列表 + 增删改 | `src/views/ctms/tag/index.vue` + `src/api/ctms/tag.js` | UI 渲染列（名称/色块/颜色/是否内置/创建人/创建时间）与 5 个接口签名逐字核对 |
| 自动标签有可辨识标记 | `tag/index.vue:71`（「系统自动」徽标）、`194-215`（`autoTagNames` **运行时**由 `contract_types` 字典 + `FRAMEWORK_TAG_NAME` 推导） | 清单不抄后端常量（字典改动不会静默失配）；`isAutoTag` 为纯函数 |
| 自动标签不可被手动同步逻辑清除 | `tag/index.vue` 全页 **无任何 sync 函数/同步按钮**（grep 只命中注释与 `.sync` 修饰符）；`detail.vue:1054`（`tag.auto === '1'` 拆到只读区）、`1096-1097`、`1348` | 表单只维护 `manualTagIds`，自动标签以只读 `el-tag` 渲染（无删除入口），提交只发 `tagIds = manualTagIds` → 手工标签不会被自动同步移除，自动标签也不能从表单清除 |
| `v-hasPermi` 覆盖增删改（无权限看不到按钮） | `tag/index.vue` 3 个权限点 | 见 §3-1；`src/directive/permission/hasPermi.js:22` 实测是 `el.parentNode.removeChild(el)`（**移除节点**，不是置灰） |
| `node tools/audit/run-all.js` 全绿 | —— | exit 0（主题令牌审计含本页） |

### 8.4 客户与供应商档案页（t9）

| 验证要求 | 页面文件 | 我的独立证据 |
| --- | --- | --- |
| 简称为空提交被拦 | `partner/index.vue:365-370`（`supplierShortNameError`） | **把纯函数抠出来在 node 直接跑**：`''`/`'   '`/`undefined`/`null` → 「供应商简称不能为空」；`'阀门厂'` → 放行（**12 条断言全过**，见 §4-6） |
| 账期 -1 被拦 | 同上 `paymentDaysError:379-391` | `-1`/`-100` → 「账期天数不能为负数」；`1.5`/`'abc'` → 「账期天数必须是整数」；`0`（现结）与空值放行 |
| 文案与后端一致 | `PartnerRules.java:81/95` | 后端真源逐字：`供应商简称不能为空`、`账期天数不能为负数` → 与前端常量逐字一致 |
| 停用后新增合同选择器不再出现该档案 | `src/api/ctms/partner.js:36-38`（`/ctms/partner/customer/options`）、`detail.vue:997/1006` | **真库实测**：1 启用 + 1 停用客户 → `/options` 只返回 **1**（启用项），而档案管理列表 `total=2`（含停用项）→ 选择器数据源确实排除了停用档案（规格 `business-partners`「停用后不可新选」） |
| 启停用/删除（不做物理删除的入口） | `partner/index.vue`（`ctms:partner:status`）+ `partner.js:56/93` | 状态切换走 `.../status`，页面无物理删除按钮（列表操作列只有 查看/修改/启停用/删除→后端引用保护） |
| `node tools/audit/run-all.js` 全绿 | —— | exit 0 |

### 8.5 迁移认领页（t10）

| 验证要求 | 页面文件 | 我的独立证据 |
| --- | --- | --- |
| 草案列表 6 列 + 扫描 + 认领（新建/绑定已有）+ 忽略 | `src/views/ctms/migration/index.vue` + `src/api/ctms/migration.js` | **真实 UI 实测**（真库夹具 3 份同文本未绑定 PUR）：页面顶部常驻「未认领/已忽略的草案不会阻塞业务…」提示 + 扫描口径说明；列表列 = 档案方向/原始名称/关联合同数/状态/候选档案/已匹配档案/操作；待认领行渲染 `供应商（乙方）\| T17M待认领阀门有限公司 \| 3 \| 待认领 \| — \| — \| 认领 忽略` |
| 界面显示未认领不影响合同展示的提示 | 同上 `:26` | 常驻提示实测存在（不是弹窗/一次性 toast） |
| 手工走通「扫描 → 认领新建档案 → 合同详情看到档案引用与变更历史」 | 同上：`handleScan:532`、`handleClaim:557`、两模式 `162-186`、`payload.partyId:590` | API 侧：`scan count=1`（3 份聚合成 1 条 `contractCount=3`）→ `claim`（只传 code）→ `code=200 status=claimed matchedId=1ADEF63A… contractCount=3`；随后我在 UI 上刷新并切到「全部状态」实测该行渲染为 `已认领 \| 1 个候选 \| T17M待认领阀门有限公司（T17MSUP1） \| 已认领，不可重复认领/忽略`，**不再有认领/忽略按钮**（与后端两条守卫文案对应） |
| 「认领后再次扫描回到待认领」 | 同上（认领成功后只 `getList()`，不做本地乐观更新） | 默认「待认领」筛选下该草案**已消失**（实测）；底层状态机由第 7 组 t2/t22 的真库验证覆盖（我在 t16 已复现 `claimed→pending` 回归） |
| `node tools/audit/run-all.js` 全绿 | —— | exit 0；`audit-theme-tokens.js` 对本页 0 命中（只 4 个已声明令牌） |

### 8.6 前端交付文档（t11）

| 验证要求 | 文件 | 我的独立证据 |
| --- | --- | --- |
| 文档权限点清单与第 2 组菜单 SQL `perms` 集合双向一致 | `openspec/changes/oa-contract-ledger/notes/frontend-notes.md` | **三方集合比对**：文档 **26** = 菜单 SQL 文件 **26** = 真库 `sys_menu.perms` **26**，**两个方向差集均为空**；文档中 `ctms:tag:query` 出现 **0** 次 |
| 路由与菜单注册方式、页面↔权限点、日期格式约定 | 同上 | §2/§3/§4 章节与四条纪律（`CaseSensitive` ×3、`--untracked` ×3、`removeChild` ×2、`hasPermiAnd` ×3、整单提交 ×4）逐条存在；规模 22782 B / 271 行 / 无 BOM（与记录逐字一致） |
| 契约 verify 命令的"输出 1" | —— | 我复核了其解释成立：`-Raw` 下整文件是一个多行字符串，`Select-String` 只返回 1 条 `MatchInfo`，1 **不代表**权限点个数；真值 26 由集合比对给出 |

---

## 3. 独立静态核对（acceptance 的 5 条，逐条给命令与数字）

1. **`v-hasPermi` 权限点集合 ⊆ 菜单 SQL `perms` 集合** —— 正则抽取 `src/views/ctms` + `src/components` 的 `v-hasPermi`
   → **18 个**权限点，与菜单 SQL 的 **26** 个求差集 = **空**；另核 `$auth.hasPermi(...)`/`hasPermiAnd(...)` 的实参（含 `ctms:tag:list`、`ctms:partner:list` 等）同样 ⊆ 菜单集合。
2. **`YYYY-MM-DD` 零命中（必须区分大小写）** —— `git grep -n --untracked "YYYY-MM-DD" -- src/views/ctms src/components` → **exit 1、零输出**；
   `Select-String -CaseSensitive` 同样 **0 命中**；对照组：正确的 `value-format="yyyy-MM-dd"` 在 `src/views/ctms` 有 **20** 处（说明门禁不是"没扫到"）。
3. **所有 `.then` 都有 `.catch`** —— 用 `@babel/parser` 解析 6 个文件的 `<script>` 块并沿 `.then` 链上溯：
   **49 条 `.then` 链全部以 `.catch` 收尾，缺 catch = 0**（detail 17 / contract-index 10 / migration 9 / partner 8 / tag 5；`contract-rules.js` 0 条）。
   另：`audit-then-loaders.js`（loading 置位后无 catch 的永久转圈形态）报 **0**，且带 4 个夹具自证——但它的判据只覆盖"`this.loading=true` + `.then` + 无 catch"，不能替代上面这条全量断言。
4. **附件限制常量不在前端重复定义** —— `src/views/ctms` + `src/api/ctms` 内 **无** `20MB`/`20971520`/`maxSize` 常量、**无**后缀白名单字面量；
   `api/ctms/attachment.js:12/32-33` 定义 `getAttachmentObjectTypes()` → `GET /ctms/attachment/object-types`，`detail.vue:1142` 读取后只用响应里的 `maxSizeBytes/maxSizeMb/extensions`（`:787/:1142-1148/:1380-1385`）。
5. **自动标签不可被手动清除** —— 见 §2 的 8.3 行：`detail.vue` 的标签多选只绑 `manualTagIds`、自动标签只读渲染、提交 `tagIds` 只含手动项；`tag/index.vue` 无任何同步逻辑。
   附：`hasPermi.js:22` 是"移除节点"（`removeChild`），与 t8 记录的机制描述一致。

---

## 4. 独立运行态 / 浏览器实测（本轮新做，全部有原始输出）

1. **导出（发现 F1）**：真库插入 1 份合同 → 合同列表页 `搜索` 后「共 1 条」且该行甲乙文本正常显示 → 点「导出」→ 页面提示
   **「当前筛选条件下没有可导出的合同」**（`index.vue:891-893` 的分支）。后端侧同一环境实测 `/ctms/contract/export` 返回的是
   **4003 字节的真实 xlsx**（解包 `xl/*.xml` 能命中甲方文本、乙方文本、合同编号）→ 前后端对 `/export` 的返回形态假设不一致。
2. **默认排除停用 / 含停用开关**：见 §2 的 8.1（UI 3 条 / 接口 `includeDeleted=1` 4 条，含 1 条 `delFlag=1`）。
3. **标签交集两种 wire 形态**：见 §2 的 8.1（`tagIds[0]/[1]` 与逗号形态都给 1 条）。
4. **框架详情汇总**：见 §2 的 8.1（`childrenCount=2`、合计 90000、框架自身 100000）。
5. **迁移认领页两态**：见 §2 的 8.5（待认领行有按钮、已认领行无按钮且显示「已认领，不可重复认领/忽略」；默认待认领筛选下认领后消失）。
6. **纯函数自检（我自己的断言，不引用被审者脚本）**：
   - `contract-rules.js` 18 条（含 3 条故意写错期望值的对照，见 F2）→ 15 条通过、3 条为我的期望值写错（已逐条复核，实现无缺陷）；
   - `partner/index.vue` 的 `supplierShortNameError` / `paymentDaysError` **12 条全过**。

---

## 5. Findings

### F1（high）合同列表页「导出」与已交付的后端（真实 Excel）不一致 → 功能不可用

- **problem**：`handleExport()` 仍按"后端返回与列表一致的分页 JSON"写：`const rows = response.rows || []`，空则提示"没有可导出的合同"，
  否则前端自行拼 CSV（`downloadCsv`）。而第 7 组 t5 已把 `CtmsContractController.export`（`CtmsContractController.java:106-113`）
  实现为 `ExcelUtil.exportExcel(response, ...)` —— **直接写 xlsx 二进制流，响应体里没有 `rows`**。
  而 `api/ctms/contract.js:62-64` 的 `exportContract` 用的是普通 `request({method:'get'})`，**没有** `responseType: 'blob'`，
  所以 `request.js` 的响应拦截器（`request.js:75-116`）不会走"二进制直接返回"分支（`81-83` 要求 `responseType` 是 blob/arraybuffer），
  页面拿到的 `response` 里永远没有 `rows`。
- **reproduced evidence（真实 UI + 真实库，2026-10-05 16:0x）**：库里 1 份合同 → 列表「共 1 条」→ 点「导出」→
  **「当前筛选条件下没有可导出的合同」**；后端同一环境返回 4003 字节 xlsx（`xl/*.xml` 内含甲方文本、乙方文本、合同编号）。
  即：用户点导出**永远拿不到数据**，导出功能（规格 `contract-ledger`「未认领草案不阻塞业务」的导出面 + 8.1 的导出按钮）不可用。
- **file / line**：`ruoyi-vue-oa-ui-master/src/views/ctms/contract/index.vue:882-903`（`handleExport`）、`:445-483`（`downloadCsv`）、
  `ruoyi-vue-oa-ui-master/src/api/ctms/contract.js:58-64`（过时注释「尚未落地 ExcelUtil」+ 缺 `responseType`）
- **requiredFix**：前端改成二进制下载——`exportContract` 用 `responseType: 'blob'`（或在页面直接用 `@/utils/request` 的 `download('/ctms/contract/export', params, '合同台账.xlsx')`，它已封装 `blob` + `saveAs` + 失败体解析，见 `request.js:134-152`），
  删除 `downloadCsv` 与"后端返回分页数据"的注释，并把 `EXPORT_FETCH_LIMIT` 相关说明改为"由服务端导全量"；
  补一条浏览器级断言：库里有 1 份合同时点导出 → 落地 `.xlsx`（或在无法落盘时给出可见的错误提示），**不得出现"没有可导出的合同"**。
  ⚠ 该缺陷是第 7 组 t5 改动后端后**未回传前端**造成的跨组契约漂移，属"同一件事两个真源"类问题，建议在 10.2 门禁里加一条前后端导出契约核对。

### F2（low）`contract-rules.js` 注释里的浮点反例不成立（用错值举了例）

- **problem**：文件头注释写"`Math.round(1.665 * 100) / 100` 得到 **1.66**（1.665 的二进制近似是 1.6649999…）"。
  实测（node v24，IEEE754）：`1.665 * 100 === 166.5`（精确），`Math.round(1.665*100)/100 = **1.67**`，
  `Number.prototype.toPrecision` 显示 `1.6650000000000000355`——是"略大于"，不是"略小于"。
  真正的翻车例子是 `1.005 → 1`（`1.005*100 = 100.49999999999999`）与 `8.575 → 8.57`（`857.4999999999999`）——这两个确实能被浮点坑到，
  所以**设计结论（必须走定点）是对的，只是举例举错**。
- **file / line**：`ruoyi-vue-oa-ui-master/src/views/ctms/contract/contract-rules.js:8-9`
- **requiredFix**：把注释里的反例换成 `1.005 → 1.00`（或 `8.575 → 8.57`），或直接删掉具体数字只留结论；
  实现本身（`toScaledInt` 走十进制字符串 + BigInt）无需改动——我另外用 `moneyText(1.005)='1.01'`、`moneyText(2.675)='2.68'` 证明了实现不受影响。

### F3（low）8.1~8.4 的"浏览器级手工核对"至今没有执行记录（记录已如实标注）

- **problem**：tasks.md 这四项的「验证：」原文含浏览器级动作（`npm.cmd run dev` 后手工核对筛选场景、手工核对到期日/7 个日期控件、手工核对自动标签两条行为、手工核对简称/账期拦截与"停用后选择器不再出现"），
  而交付记录**都明确写着"未执行、见 10.1/10.2"**——不是谎报，但意味着第 8 组的验收证据目前主要停在"静态审计 + 单测 + 源码"层。
  本轮我已用自己的 UI/接口复核补上了其中一部分（默认/含停用、标签交集、框架汇总、迁移页两态、选择器只出启用项、两个校验纯函数 12 条），
  但仍有 3 处只有"逻辑层证据"、没有浏览器级证据：① 8.2 的到期日在表单里随"生效日+期限"实时联动显示；② 8.3 的"自动标签有标识、手动同标签不被清除"在真实页面上的表现；
  ③ 8.4 的"简称为空/账期 -1 提交被拦"在真实表单上的表现。
- **file / line**：`openspec/changes/oa-contract-ledger/tasks.md:572-573`、`:600`、`:617`、`:632`（四处"未执行"标注）
- **requiredFix**：把这 3 处浏览器级核对明确挂到 10.1（端到端联调）与 10.2（起服门禁）的检查清单里并留原始输出（截图或 `Select-String` 日志），
  不要让"组内验收"与"集成验收"之间留空档；若 10.1/10.2 已在计划里，则本项仅作登记，不改第 8 组的实现。

---

## 6. 已解释的观察（不计 finding）

- **O1 t11 的规模数字是对的**：`notes/frontend-notes.md` 22782 B / **271 行** / 无 BOM 与记录逐字一致（对照第 7 组 t25 把 370 行写成 282 行的那次伪数——那是 PS 默认 GBK 解码吞 `0x0A` 所致）。
- **O2 `value-format` 的第 5 处命中在注释里**：`detail.vue:617` 的说明文字里出现了小写 `value-format="yyyy-MM-dd"`，属"说明文字也命中字面量"这一族
  （t11 已把该纪律写进 DEV-ENV §6.50 续条）。本例无害（门禁判的是大写 `YYYY-MM-DD`），登记备查。
- **O3 菜单名 `'????'` 已修复**：真库 `select menu_name,... from sys_menu where component like 'ctms/%'` 四行中文名正常，`ctms:*` 权限点 26 个；侧边栏与面包屑显示正常（快照实测「合同管理 / 合同台账」等）。
- **O4 金额有两个"唯一实现"是分工而非漂移**：`contract-rules.js` 负责**计算/提交**（定点四舍五入），`utils/money.js` 负责**展示与中文大写**（PRD 9.3 / AC-36，文件头写明它是全项目唯一的金额显示实现）。两者职责不同，本轮未发现同口径两算。

## 7. 未覆盖边界与范围纪律

- 未做：8.4 档案页的**表单级**提交拦截点击、8.2 的**浏览器级**到期日联动与 8.3 的自动标签徽标点击（见 F3，留给 10.1/10.2）；标签页/档案页的"无权限账号看不到按钮"未用**无权限账号**实跑（机制层已核 `removeChild` + 权限点 ⊆ 菜单）。
- 未改任何实现文件（`git status` 条目数与开工时一致）；未动 `tools/`、`sql/`、后端。
- 夹具卫生：本轮插入的 `T17*` 合同 / 草案 / 档案 / 标签关联全部删除，收尾实测残留 **0**；我新开的两个浏览器标签页已关闭，用户原有 4 个标签未动。
- 本轮临时产物（不属交付物）：`$env:TEMP\ctms-review\t17-then-catch2.js`、`t17-partner-check.js` 及各处原始输出。
