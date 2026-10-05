<template>
  <doc-list-shell :kind="kind" />
</template>

<script>
/**
 * 盘点单 列表页（B4 §8.4；菜单 component = `erp/stock-take/index`）。
 *
 * 列表侧与其它 7 类单据完全一样（列/筛选/状态标签/操作列显隐/附件/变更历史全在壳里）；
 * **专有交互在表单页**（`erp/stock-take/form`：生成行项 → 录入实盘 → 审核）。
 */
import DocListShell from '../doc/DocListShell'
import docKinds from '../doc/doc-kinds'

export default {
  name: 'ErpStockTakeList',
  components: { DocListShell },
  data() {
    return {
      kind: docKinds.getKind('stock_take')
    }
  }
}
</script>
