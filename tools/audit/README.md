# tools/audit —— 前端静默缺陷审计闸门

一组针对 **Vue 2 里"不报错但坏掉"的缺陷**的静态审计。这类问题的共同点是
**控制台一片安静**，只能靠读代码或靠工具发现。

```powershell
cd F:\dsh\ruoyiOA
node tools/audit/run-all.js           # 跑全部：先自检，再对比基线
node tools/audit/run-all.js --update  # 用当前结果重写基线（要有意识地做）
node tools/audit/run-all.js --no-selftest
```

## 四条轴 / 七个脚本

| 脚本 | 查什么 | 门禁 |
| --- | --- | --- |
| `audit-then-loaders.js` | `.then` 取数**没有 catch** → `loading` 永远为 true，表格永久转圈 | `zero` |
| `audit-async-loaders.js` | `async/await` 取数**没有 try/catch** → 同上（`.then` 判据扫不到这一形态） | `zero` |
| `audit-silent-catch.js` | 空 `.catch(() => {})` 全量清点（**多数是 `$confirm` 的取消路径，属正常控制流**） | `baseline`（信息性） |
| `audit-loading-pairs.js` | **AST 强判据**：每个 `loading` 类标志都必须有配对复位，且成功路径的复位必须**无条件** | `baseline` |
| `audit-undeclared-writes.js` | 给**未声明**的 `this.X` 赋值（Vue 2 非响应式）——只门禁"真风险"那一档 | `baseline` |
| `audit-template-refs.js` | 模板绑定里引用了**不存在**的名字（渲染出 `undefined`） | `baseline` |
| `audit-flow-template-binding.js` | **2.0 B1（查后端）**：流程↔模板绑定链路是否完整 —— `by-template` 端点、发布回写调用链、回写必须是原地 `updateTemplate`、`tpl_` 派生规则全仓唯一、反查语句存在 | `zero` |
| `audit-template-tabs.js` | **2.0 B1（查前端）**：模板配置页是否真是四个页签、是否换成逐页签校验、死字段 `activeNames/activeName` 是否删净、设计器是否已内嵌（而不是仍挂独立路由） | `zero` |
| `audit-print-builtin-templates.js` | **2.0 B2（查跨端）**：内置打印版式按单据类型选用、版式常量收口到后端唯一真源、留痕硬门禁 | `zero` |
| `audit-theme-tokens.js` | **`var(--oa-xxx)` 引用的令牌是否都已声明**（用了不存在的令牌名时 CSS 会**静默**落到硬编码回退值、不跟随主题换色；见 `DEV-ENV.md` §6.51）。判据 = used − declared；`declared − used`（预留未用）只打印不门禁。**先剥注释再找使用点**，否则"注释里解释某令牌为什么被换掉"会把门禁自己打红 | `zero` |

> 后 3 个审计扫描的是**跨端/后端**（`audit-flow-template-binding` 与 `audit-print-builtin-templates` 扫
> `ruoyi-vue-oa-master` / 仓库根），其余扫前端 `ruoyi-vue-oa-ui-master/src`；
> `run-all.js` 的 `AUDITS` 表里用 `src` 字段区分（缺省 = 前端）。

`gate` 的三种语义：
- **`zero`** —— 必须为 0。涨了就是真实缺陷回归。
- **`baseline`** —— 必须等于 `baseline.json` 里记录的数。数字变化必须是**有意识的**
  （修好了就 `--update`，否则视为回归）。已判定的误报数也记在这里。
- **`info`** —— 只打印。

## 每个脚本都有 `--selftest`

```powershell
node tools/audit/audit-loading-pairs.js --selftest
```

自检的做法：把**已知阳性 / 阴性样本**写成临时目录里的文件，再把**脚本自己**当子进程跑一遍
（`SRC` 指向那个临时目录），断言它的 JSON 输出。阳性必须抓到、阴性绝不该报，
任一侧不满足就 `exit 1`。

**为什么这条是硬要求**：审计最危险的失败模式不是误报，而是**静默失效** ——
判据写错、解析器挂掉，输出一个漂亮的"0 命中"，看起来像"代码很干净"。
`run-all.js` 会把"没有 `--selftest` 的审计"显式标为**未自证**，而不是当成通过。

## 基线（`baseline.json`）

记录每个审计当前的命中数，用于检测回归。**基线文件存在但读不出来会被判为失败**，
而不是静默退回"首次记录" —— 否则一个带 BOM 或被截断的 JSON 会让闸门变成永远通过的摆设。
（这个坑是被负面对照实验当场抓到的：故意写坏基线，闸门居然报"失败 0 个"。）

## 写这类脚本的四条经验（都是踩出来的）

1. **边界不能靠猜，要探针实测。** Vue 模板里表达式散在七八个字段
   （`el.attrsList` / `el.props` / `el.classBinding` / `el.attrsMap` / `el.directives` / `el.events`…），
   我按"想当然"写就漏了一片、又把静态属性值当表达式炸出 2733 条。
2. **判据要严到 0 误报，否则输出不可用。** 同一条判据我收紧过 6 次（2098 → 43 → 42 → 13 → 1 → 0），
   每版的误报源都不一样。
3. **必须自带阳性/阴性对照。** 否则"0 命中"里看不出失效、"2098 条"里看不出真相。
4. **不要用 PowerShell 做源码的读-改-写。** `Get-Content -Raw` 会用系统 ANSI 码页读无 BOM 的 UTF-8：
   中文变乱码，多字节序列还可能**吃掉引号**，直接把脚本变成语法错误。
   搬运/改写一律用 Node 的 `readFileSync` / `writeFileSync`（默认 UTF-8），或直接编辑。

## 已知误报（会长期留在输出里，看到不是回归）

| 位置 | 为什么不是缺陷 |
| --- | --- |
| `audit-loading-pairs` 报的 `layout/components/InnerLink/index.vue` | 复位在 iframe 的 `onload` 回调里，与 Promise 无关 |
| `audit-loading-pairs` 报的 `views/login.vue`、`register.vue` | 成功路径**故意**不复位（按钮要显示"登 录 中…"直到路由跳走） |
| `audit-template-refs` 报的 `layout/components/Sidebar/SidebarItem.vue` 的 `onlyOneChild` | vue-element-admin 习语：`data()` 里赋 null，渲染期由 `hasOneShowingChild()` 赋值并在同一次渲染读回。**动它反而可能触发无限更新循环** |
| `audit-undeclared-writes` 的"无害暂存"档 | bpmn-js 模型对象、monaco 编辑器实例、遗留死字段 —— 非响应式是**正确**的 |
| `audit-silent-catch` 报的 `views/workflow/flow-form/index.vue` 的 `loadSigned()` | 签名策略查询**故意**静默降级为"未配置"：真实验证在服务端（`TaskSignGuard`），这里失败不该把提交弄坏。**基线因此从 69 → 70**（2026-10-04 有意识更新；该 `.catch` 由打印/签名链路那次提交引入，基线当时没跟着刷） |

> 审计只能标出"值得看一眼"的名单，**最终定性必须人读代码**。
