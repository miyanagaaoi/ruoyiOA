# `src/views/ctms/` —— 合同台账前端页面（2.0 B3）

> 变更集：`openspec/changes/oa-contract-ledger`｜技术决策见其 `design.md` D-8

## 约定

- **技术栈**：Vue 2.6 + element-ui 2.15.14（扩展构建）+ Vue CLI 4。不引入新 UI 库；
  `descriptions / empty / result / statistic / timeline / image / drawer` 这套扩展组件已经可用。
- **路由与菜单**：走 `sys_menu` 动态路由（`component = 'ctms/<目录>/index'`），
  在 `sql/二开-合同台账-菜单.sql` 里初始化；**不要**在 `router/index.js` 里写死业务路由。
- **按钮权限**：一律 `v-hasPermi="['ctms:<资源>:<动作>']"`，权限点集合必须与菜单 SQL 完全一致
  （任务 8.6/9.1 有集合核对，双向无遗漏）。
- **日期记号**：一律 **`yyyy-MM-dd`**。
  ⚠ 参考仓库是 Vue3 写法，`value-format` 写成全大写日期记号的有 15 处 —— 直接抄过来是**静默出错**
  （Element UI 2.x 只认小写 `yyyy-MM-dd`，大写年份记号会被当成别的语义，日期可能不显示或不生效）。
  这条有 grep 门禁：日期记号**全大写**的字面量在 `src/views/ctms` 与 `src/components` 下必须零命中
  （命令与口径见 `openspec/changes/oa-contract-ledger/tasks.md` 任务 8.2 的「验证：」——
  本文件刻意不写出该字面量本身，否则这条门禁会被文档自己打红）。
- **金额显示**：走 `@/utils/money.js`（与打印件同一份实现，AC-36 要求"大写与数字一致"），
  不要自己写千分位/大写换算。
- **主题色**：只写 `var(--oa-color-*)` 令牌，不写裸色值（见 `DEV-ENV.md` §6.9/§6.11）。
- **取数纪律**：`.then` 必须有 `.catch`，否则 loading 会永久为 true
  （`tools/audit/run-all.js` 的"类 1/类 2"审计会拦下）；列表筛选、标签交集、框架树、数据范围
  **全部由服务端完成**，前端只渲染。

## 页面清单（B3）

| 页面 | 目录 | 权限点前缀 | 覆盖任务 |
| --- | --- | --- | --- |
| 合同列表（多维筛选 + 标签交集 + 框架树 + 停用开关） | `contract/` | `ctms:contract:*` | 8.1 |
| 合同详情 / 编辑（基础信息 + 行项 + 质保 + 标签 + 附件 + 变更历史 + 只读关联单据） | `contract/` | `ctms:contract:*`、`ctms:contract-item:*` | 8.2 |
| 标签管理 | `tag/` | `ctms:tag:*` | 8.3 |
| 客户档案 / 供应商档案 | `partner/` | `ctms:partner:*` | 8.4 |
| 迁移认领（扫描 → 认领/绑定/忽略） | `migration/` | `ctms:migration:*` | 8.5 |

对外的接口调用一律集中放 `src/api/ctms/**`（与 `src/views/ctms/**` 一一对应），
组件里不直接写 `request({...})`。
