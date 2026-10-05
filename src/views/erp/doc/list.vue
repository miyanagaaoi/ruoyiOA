<template>
  <div class="app-container">
    <!--
      通用单据列表页（B4 §8.3 的"壳页面"）：**一份实现撑 8 个菜单**。
      单据类型来源优先级：props.kindCode → `?kind=` → 路由 meta.kind。
      t9b 也可以为每类单据写一个薄包装页（`<doc-list-shell :kind="kind" />`），
      两种用法都是"壳 + 专有字段"，不存在第 9 份拷贝。
    -->
    <DocListShell v-if="kind" :kind="kind" />
    <el-alert v-else type="warning" :closable="false" show-icon title="未指定单据类型">
      请在菜单的查询参数里带上 `kind=<单据码>`（如 `purchase_request`），或使用各类单据的专用页面。
    </el-alert>
  </div>
</template>

<script>
import docKinds from './doc-kinds'
import DocListShell from './DocListShell'

export default {
  name: 'ErpDocList',
  components: { DocListShell },
  props: {
    // 单据码（Eight kinds: purchase_request / purchase_order / sales_request / ...）
    kindCode: { type: String, default: '' }
  },
  computed: {
    kind() {
      return docKinds.getKind(this.resolveCode())
    }
  },
  methods: {
    resolveCode() {
      if (this.kindCode) {
        return this.kindCode
      }
      const route = this.$route
      if (!route) {
        return ''
      }
      if (route.query && route.query.kind) {
        return route.query.kind
      }
      if (route.meta && route.meta.kind) {
        return route.meta.kind
      }
      return ''
    }
  }
}
</script>
