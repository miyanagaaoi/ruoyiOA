# 第 8 组（前端页面）复审 —— t29 / round 2（针对 t28 的 F1/F2/F3 修复）

> 复核人：reviewer（独立复核，未修改任何实现代码）
> 复核时间：2026-10-05 15:50~16:05（PS 5.1 + node v24 + 真实浏览器）
> 被审对象：t28（repair round 2，修复 round 1 的 F1/F2/F3）＋ 第 8 组 6 项任务的验收证据是否仍然成立
> 上游：`notes/frontend-review.md`（round 1，verdict = needs_revision）
> 环境：前端 `vue-cli-service serve`（pid 15328，14:01 起，HMR 生效）；后端 jar mtime **2026-10-05 15:52:04**、8080 pid 21180

## 0. 结论

**verdict = pass**（round 1 的三条 findings 已全部修复并经我独立复现；6 项任务的验收证据仍成立）
**另记 1 条 low 文档更正（R2-F1）**：`frontend-notes.md` 里"4 个 ctms 菜单名当前是 `????`"已过时（环境早已修复），不影响任何验收项，建议属主顺手改两行。

| round 1 finding | 处置 | 我的独立复核 |
| --- | --- | --- |
| F1（high）导出功能不可用 | **已修**：`api/ctms/contract.js` 的 `exportContract(query, filename)` 委托 `utils/request.js` 的 `download()`（POST + `responseType:'blob'`）；`index.vue` 的 `handleExport` 只触发下载，客户端 CSV 全套与其过期注释已删除 | **真实 UI 下载 + 解包逐格核对通过**（见 §2.1）；无权限反面也独立复现 |
| F2（low）注释浮点反例不成立 | **已修**：注释改成实测反例 `1.005 → 1.00`、`8.575 → 8.57`，并显式写明"别拿 1.665 当浮点反例" | **12 条断言全过**（见 §2.2） |
| F3（low）浏览器级核对无执行记录 | **已登记**：`frontend-notes.md` §8.1 新增 5 行"移交 10.1/10.2"清单，并明写"t28 交付时均未执行" | 清单存在且字段完整；我**独立执行了其中 ④⑤ 两项**，①②③ 仍归 10.1/10.2（见 §2.3） |

## 1. 复跑（acceptance 第 2 条）

| 命令 | 结果 |
| --- | --- |
| `cd F:\dsh\ruoyiOA ; node tools\audit\run-all.js` | **共 10 个审计 / 失败 0 / 未自证 0**，exit 0 |
| `cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master ; npm.cmd run test:unit` | **共 39 条 / 通过 39 / 失败 0**，exit 0 |

两条与 t28 记录逐字一致。

## 2. 三条 findings 的独立复核

### 2.1 F1 —— 导出（我按"真实 UI + 真库 + 解包"三层复核）

1. **改动面核对（读源码）**：`api/ctms/contract.js:1` 已 `import request, { download } from '@/utils/request'`，`:72-74` `exportContract` → `download('/ctms/contract/export', query, filename || '合同台账.xlsx')`；
   `index.vue:826-836` `handleExport()` 只调 `exportContract(this.buildQuery(), '合同台账_' + stamp(new Date()) + '.xlsx')`，`.then` 复位 `exportLoading`、`.catch` 给可见提示；
   全文件搜 `downloadCsv|csvCell|EXPORT_FETCH_LIMIT` → **0 残留**（`stamp()` 仍定义在 `:414`）。
2. **正向（真实 UI + 真库）**：真库插入 1 份夹具 `T29EXP1`（甲方 `T29复核甲方有限公司` / 乙方 `T29复核乙方有限公司` / 金额 1234.56）→ UI 关键字 `T29EXP` 搜索 → **共 1 条** → 点「导出」→ 页面出现「正在下载数据，请稍候」（= `download()` 的 loading，证明新代码路径已生效）→ Downloads 落地 **`合同台账_20261005-1555.xlsx`（4003 B，首两字节 `PK`）** → 解包核对：
   - `xl/worksheets/sheet1.xml` 的 `<row>` 数 = **2**（1 表头 + 1 数据行，== 列表 `total`）；
   - 表头 A1..I1 = `合同编号 / 合同名称 / 合同类型 / 甲方 / 乙方 / 签订日期 / 合同金额 / 进度状态 / 经办人`，与后端 `ContractExportRow` 的 9 个 `@Excel(name=...)` **逐项一致含顺序**；
   - 数据行逐格：A2=`T29EXP-NO-1`、B2=`t29导出复核合同`、C2=`PUR`、**D2=`T29复核甲方有限公司`**、**E2=`T29复核乙方有限公司`**、F2=空（未填签订日期）、G2=`1234.56`、H2=`内部审批中`、I2=空；
   - **全程没有**「当前筛选条件下没有可导出的合同」。
3. **反面（无权限，独立复现）**：`lina/wangqiang/zhangwei/zhaomin` 只有 `common` 角色（无 `ctms:contract:export`）。用 `zhangwei` 真 token 以与前端 `download()` **同形态**（POST + form-urlencoded）调用 `/ctms/contract/export` →
   **HTTP 200 + `Content-Type: application/json` + `{"msg":"没有权限，请联系管理员授权","code":403}`**（不是二进制）。
   结合未改动的 `request.js:141-151`：`blobValidate(data)=false` → 解析失败体 → `Message.error` → **不执行 `saveAs`**，不会落"内容为 JSON 的坏文件"。
4. **卫生**：我的下载件已删除（Downloads 文件数回到基线 12），夹具 `T29EXP1` 已删除（`T29%` 残留 0），他人 23 份 `CTMMIG` 迁移夹具**未触碰**（复核后仍 23）。

### 2.2 F2 —— 注释里的浮点反例（12/12）

用 node 直接加载 `contract-rules.js`（把 `export` 去掉后 eval，不改文件）跑 12 条：
`moneyText('1.005')='1.01'`、`moneyText('2.675')='2.68'`、`moneyText('8.575')='8.58'`（注释声称的三条自证成立）；
`1.005*100===100.49999999999999`、`Math.round(1.005*100)/100===1`、`8.575*100===857.4999999999999`、`Math.round(8.575*100)/100===8.57`（修正后的反例成立）；
`1.665*100===166.5` 且 `Math.round(1.665*100)/100===1.67`（注释里"别拿 1.665 当反例"的警告成立）；
`lineTotalText(1,'1.665')='1.67'`、三行合计 `'5.01'`、先汇总再舍入 `'5.00'`（舍入顺序仍与规格一致）。实现未被改动（t28 只改注释）。

### 2.3 F3 —— 移交清单

`frontend-notes.md §8.1`（289-300 行）确实新增了 5 行表（①到期日联动 ②自动标签真实页面表现 ③档案表单拦截点击 ④导出断言 ⑤前后端导出契约核对），每行都有「落点 + 证据形式」，并在标题里写明 **"t28 交付时均未执行，切勿误以为已做"**；文末还如实说明为什么没写进 `tasks.md`（inScope 未声明、改会被 harness 拒收）。
- `tasks.md` 复核：**全文搜不到 `t28`**（`Select-String 't28'` 无命中），10.1/10.2 仍是 `- [ ]` → **没有谎报完成**。
- 我已独立执行 ④（§2.1 正向）与 ⑤（§2.1 反面 + 表头真源），①②③ 仍归 10.1/10.2。

## 3. 6 项任务映射表的 round-2 增量

映射表主体见 `notes/frontend-review.md` §2（6 任务 ↔ 页面文件 ↔ 断言 ↔ 证据，本轮结论不变）。本轮相对 round 1 的**变化点**只有 3 处：

| 任务 | round 1 结论 | round 2 增量 |
| --- | --- | --- |
| 8.1 合同列表页 | 导出**不可用**（F1） | **导出已可用**：`api/ctms/contract.js:72-74` + `index.vue:826-836`；真实 UI 下载 4003 B xlsx、2 行、9 列表头与 `@Excel` 同序、甲乙方文本；失败面（403 JSON → 可见提示、不落文件）独立复现；其余（筛选/标签交集/含停用/框架树）本轮静态复查未变（`index.vue` 只删了 CSV 块） |
| 8.2 合同详情与编辑页 | 通过（金额 5.01 / 到期日三例 / 只读区块 / 占位符） | `detail.vue` **未被 t28 动过**（mtime 仍 14:43），结论不变；`contract-rules.js` 仅注释变更，实现同一份（§2.2 12/12 复跑） |
| 8.6 前端交付文档 | 通过（权限点 26=26=26 双向空） | 新增 §5.4（导出真源在后端 `@Excel` + 跨组契约漂移教训）与 §8.1（移交清单）→ 覆盖 round 1 的 F3；权限点集合未变化 |

## 4. 独立静态核对（acceptance 第 3/4 条，全部重跑）

| 核对项 | 命令/口径 | 结果 |
| --- | --- | --- |
| `v-hasPermi` ⊆ 菜单 SQL | 正则抽取 `src/views/ctms` + `src/components` | 用 **18** 个 ⊆ 菜单 **26** 个，**差集为空** |
| `YYYY-MM-DD` 零命中 | `git grep -n --untracked "YYYY-MM-DD" -- src/views/ctms src/components` ；`Select-String -CaseSensitive` | git grep **exit 1、零输出**；Select-String **0 命中**（不区分大小写口径为 26，明细见 §6-O1） |
| 所有 `.then` 都有 `.catch` | `@babel/parser` AST（t28 改过 `index.vue`，本轮**必须重跑**） | 6 文件 **49 条 `.then` 链全部以 `.catch` 收尾，缺 catch = 0** |
| 附件限制常量不在前端重复定义 | 搜 `20971520 / 20*1024*1024 / MAX_SIZE =` | **0 命中**；口径仍全部来自 `GET /ctms/attachment/object-types` |
| 自动标签不可被手动清除 | 读 `detail.vue`（未改动）+ `tag/index.vue` | 结论不变：标签多选只绑 `manualTagIds`、`auto==='1'` 只读渲染、提交 `tagIds=manualTagIds`；标签页无同步逻辑 |

## 5. Findings（round 2）

### R2-F1（low）`frontend-notes.md` 仍称 4 个 ctms 菜单名"当前是 `????`"，与现状不符

- **problem**：`frontend-notes.md:77-78`（§2.5）与 `:282`（§8）写"本次交付期间实测 `rad_oa` 的 4 个 ctms 菜单名就是 `????`"/"当前是 `????`"。
  但我实测真库 `select menu_id,menu_name,component from sys_menu where component like 'ctms/%'` → **四行都是正确中文名**（合同台账 / 标签管理 / 往来单位 / 迁移认领）——
  captain 已按 `stdin + $OutputEncoding` 用菜单 SQL 真源重跑修复（第 8 组 t10 的记录也写了这件事）。该句是**修复前的旧事实**，现在会误导读者（甚至让 10.2 白跑一次"修菜单名"）。
- **file / line**：`openspec/changes/oa-contract-ledger/notes/frontend-notes.md:77-78`、`:282`
- **requiredFix**：把两处改为"**已修复**（captain 于 2026-10-05 用菜单 SQL 真源 + stdin/`$OutputEncoding` 重跑，真库 4 行中文名正常；`sql/` 仍不属第 8 组 inScope）"。
  纯文档更正，不影响任何验收项；可由 `frontend-notes.md` 的属主顺手改，或并入 10.1/10.2 的记录回填。

## 6. 观察（不计 finding）

- **O1 "不敏感口径 14"可复现**：t28 记录的不敏感命中数 14 = `src/views/ctms` 下**仅 `*.vue`** 的命中数（detail.vue 13 + index.vue 1）；
  换范围会得到 17（+`contract-rules.js`）、19（+`README.md`）、26（再 +`src/components`）。数字本身可解释，只是记录里没写范围；**关键门禁（大小写敏感 0）两边一致**。
- **O2 t28 改动面与声明完全一致**：`api/ctms/contract.js`(15:46:43) / `contract-rules.js`(15:46:58) / `index.vue`(15:47:23) / `frontend-notes.md`(15:54:46) —— 4 个文件；
  `src/utils/request.js` mtime 仍是 **2026-10-04 17:42**（未被改），后端/sql/tools/`src/components`/`views/ctms/partner` 均未出现在变更面里；`tasks.md` 无 `t28` 痕迹（t28 声明的"已还原"成立）。
- **O3 `detail.vue` 未被牵连**：mtime 14:43:43，与 round 1 一致 → 附件/标签/到期日/关联单据等口径没有回归风险。
- **O4 导出的"数据行数 == 列表 total"在无筛选场景也成立**：我只做了筛选场景（1 条 → 2 行）；无筛选场景（24 条 → 25 行）t28 在旧 jar 上验过，本轮未复跑，登记为 10.1/10.2 的第 ④ 项的补充断言。

## 7. 未覆盖边界与卫生

- 未复跑：前端生产构建（acceptance 未要求；我用**真实 UI 运行**作为更强证据）、8.2/8.3/8.4 的三处浏览器级点击（§2.3 ①②③，归 10.1/10.2）。
- 未改任何实现文件；本轮唯一写入是这份 `notes/frontend-review-round2.md`。
- 夹具/产物：`T29EXP1` 已删（残留 0）、我下载的 xlsx 已删（Downloads 回基线 12）、他人 23 份 `CTMMIG` 未触碰、我开的浏览器标签已关闭。
- 临时产物（不属交付物）：`$env:TEMP\ctms-review\t29-f2-check.js`、`t17-then-catch2.js`（复跑）及各处原始输出。
