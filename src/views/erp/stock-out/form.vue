<template>
  <doc-form-shell :kind="kind" :id="docId" :mode="mode" />
</template>

<script>
/**
 * 出库单 表单页（隐藏路由，菜单 component = `erp/stock-out/form`）。
 *
 * 薄页只做两件事：把单据类型交给壳、从 query 读 `id`/`mode` —— **不要**在这里
 * 复制"表头字段/行项表/保存与提交/附件区"（那是 `DocFormShell` 的职责，D12）。
 */
import DocFormShell from '../doc/DocFormShell'
import docKinds from '../doc/doc-kinds'

export default {
  name: 'ErpStockOutForm',
  components: { DocFormShell },
  computed: {
    docId() {
      return String((this.$route && this.$route.query.id) || '')
    },
    mode() {
      const q = (this.$route && this.$route.query) || {}
      if (q.mode === 'view' || q.mode === 'edit' || q.mode === 'add') {
        return q.mode
      }
      return q.id ? 'edit' : 'add'
    }
  },
  data() {
    return {
      kind: docKinds.getKind('stock_out')
    }
  }
}
</script>