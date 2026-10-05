# `src/views/erp/` —— 进销存前端（2.0 B4 §8）

> 变更集：`openspec/changes/oa-purchase-sales-stock`｜技术决策：`design.md` **D12**（配置驱动壳复用）、**D9**（金额口径）、**D10**（数据范围）
> 交付记录与验证证据：`openspec/changes/oa-purchase-sales-stock/notes/09a-frontend-core.md`

## 目录

```
src/api/erp/                        接口层（视图不直接写 request）
  doc.js                            8 类单据共用的请求模板（URL 前缀取自 doc-kinds）
  masterdata.js                     转出口 B3 档案接口 + "只取启用项"的下拉辅助
  attachment.js                     转出口 B3 通用附件接口 + 单据码 → 附件对象类型

src/views/erp/
  erp-const.js                      冻结常量：日期格式串、主数据权限点前缀、动作→权限段映射
  doc/                              单据壳（8 类单据共用）
    doc-rules.js                    状态机/权限点/下推链/剩余量/前置校验（纯逻辑，单测覆盖）
    doc-kinds.js                    8 类单据的列/筛选/字段/行项/下推配置
    doc-options.js                  下拉选项按需加载
    doc-selects.js                  下拉与字典状态加载（列表壳/表单壳共用一份实现）
    erp-amounts.js                  金额口径转出口（= B3 contract-rules 的定点实现）
    DocListShell.vue                列表壳
    DocFormShell.vue                表单壳
    list.vue / form.vue             通用壳页面（`?kind=<单据码>`）
    components/                     状态标签 / 行项表 / 下推对话框 / 动作弹窗 / 附件区
  master/                           库存主数据四页
    master-page.js                  四页配置
    components/MasterTableShell.vue 列表（含树）+ 弹窗表单通用壳
    product|product-type|uom|warehouse/index.vue
```

## 约定（照抄 B3 `src/views/ctms/README.md` 的口径，别自造）

- **技术栈**：Vue 2.6 + element-ui 2.15.14（扩展构建）。不引入新 UI 库。
- **路由与菜单**：走 `sys_menu` 动态路由；单据页 `component=erp/<doc>/index`、隐藏表单路由 `erp/<doc>/form`；
  主数据 `erp/master/<page>/index`。**不要**在 `router/index.js` 里写业务路由。
- **权限点**（真源 = PRD §7.5 + 后端 `@PreAuthorize`）：
  单据 `pur:request|pur:order|sal:request|sal:order|stk:in-order|stk:out-order|stk:take|stk:transfer` ×
  `list/query/add/edit/remove/submit/approve/void/unapprove/export/print`；
  **驳回 / 置为已完成复用 `:approve`**（没有独立点，写 `:reject` 会永远不显示）；
  下推：申请→订单用目标单据的 `:add`，订单→出入库用来源单据的 `:push`；
  主数据：`ctms:partner:*`（沿用 B3 控制器注解）。
  页面里**只用** `permOf('add')` 这类方法或 `v-hasPermi="[permOf('edit')]"`，不要手写权限点字符串。
- **日期**：`:value-format="dateFormat"`（= `erp-const.DATE_FORMAT` = `yyyy-MM-dd`）。全大写年份记号是静默错误，
  有 `tests/erp-shell.test.js` 的文件级门禁（进销存目录零命中）。
- **消息**：`this.$modal.msgSuccess/msgError/msgWarning/confirm`（或 `this.$message`）；**不要** `element-plus` / `ElMessage`。
- **主题色**：`<style>` 里只写 `var(--oa-*)` 令牌（令牌真源 `src/assets/styles/oa-tokens.scss`），不写裸色值。
- **金额**：一律 `import { moneyText, lineTotalText, sumLineTotalsText } from '@/views/erp/doc/erp-amounts'`
  （行金额先 HALF_UP 到 2 位、合计 = 各行已舍入之和；不要自己写 `qty*price`）。
- **取数纪律**：`.then` 必须配 `.catch`，失败要复位 loading、清空旧数据、渲染 `DataLoadError`
  （`tools/audit/run-all.js` 的类 1/类 2 门禁）。列表筛选/分页/数据范围**全部由服务端完成**。

## 8 类单据页面怎么加（t9b）

**只写"壳 + 专有字段"**，不要复制 `DocListShell` / `DocFormShell`：

```vue
<!-- src/views/erp/purchase-request/index.vue -->
<template><doc-list-shell :kind="kind" /></template>
<script>
import DocListShell from '../doc/DocListShell'
import docKinds from '../doc/doc-kinds'
export default { name: 'ErpPurchaseRequest', components: { DocListShell },
  data() { return { kind: docKinds.getKind('purchase_request') } } }
</script>
```

专有字段优先写进 `doc-kinds.js` 的配置；自由交互（盘点单的"生成行项 / 录制实盘"）用
`DocFormShell` 的 `#header-extra` / `#items-extra` 插槽。扩展点清单见 notes §3。

## 自检

```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
node tests\run.js            # 152/152（含本目录的 41 条口径用例）
cd F:\dsh\ruoyiOA
node tools\audit\run-all.js  # 10/10
```
