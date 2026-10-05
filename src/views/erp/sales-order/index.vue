<template>
  <doc-list-shell :kind="kind" />
</template>

<script>
/**
 * 销售订单 列表页（B4 §8.4；菜单 component = `erp/sales-order/index`）。
 *
 * 薄页只做一件事：把单据类型交给壳 —— **不要**在这里复制壳逻辑（design D12：
 * 列/筛选/状态标签/操作列显隐/下推/附件/打印入口全在 `DocListShell` + `doc-kinds` 里）。
 */
import DocListShell from '../doc/DocListShell'
import docKinds from '../doc/doc-kinds'

export default {
  name: 'ErpSalesOrderList',
  components: { DocListShell },
  data() {
    return {
      kind: docKinds.getKind('sales_order')
    }
  }
}
</script>