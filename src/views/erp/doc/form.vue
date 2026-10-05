<template>
  <div class="app-container">
    <!--
      通用单据表单页（B4 §8.3 的"壳页面"）：`?kind=<单据码>&id=<单据ID>&mode=edit|view`。
      与列表页同一套解析规则；t9b 的 8 类单据页面可直接薄包装本页（或 DocFormShell）。
    -->
    <DocFormShell
      v-if="kind"
      :kind="kind"
      :id="docId"
      :mode="mode"
    >
      <!-- 专有交互插槽（如盘点单的"确认生成行项 / 保存实盘数量"）留给薄包装页填充 -->
      <template slot="header-extra"><slot name="header-extra"></slot></template>
      <template slot="items-extra"><slot name="items-extra"></slot></template>
    </DocFormShell>
    <el-alert v-else type="warning" :closable="false" show-icon title="未指定单据类型">
      请在菜单的查询参数里带上 `kind=<单据码>`（如 `stock_in`），或使用各类单据的专用页面。
    </el-alert>
  </div>
</template>

<script>
import docKinds from './doc-kinds'
import DocFormShell from './DocFormShell'

export default {
  name: 'ErpDocForm',
  components: { DocFormShell },
  props: {
    kindCode: { type: String, default: '' },
    docId: { type: String, default: '' },
    mode: { type: String, default: '' }
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
