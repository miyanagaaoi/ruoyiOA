<template>
  <!--
    独立路由页（「流程设计（简化版）」菜单，router 里的 simple-flow/designer）。

    2.0（B1 §6.1）把「工具条 + 画布 + 配置抽屉」抽成了可复用组件
    `components/FlowDesigner.vue`；本页只剩**宿主**职责：把路由带过来的流程 id 透传下去。
    模板编辑页的「流程设计」页签用同一个组件（embedded=true，注入 templateId/formId）。
    这样改造后独立路由页的功能与升级前**完全等价**（节点增删改 / 条件编辑 / 保存 /
    发布 / 版本历史），而且后续只需要维护一份设计器代码。
  -->
  <FlowDesigner ref="designer" :flow-id="flowId" />
</template>

<script>
import FlowDesigner from "./components/FlowDesigner";

export default {
  name: "SimpleFlowDesignerPage",
  components: { FlowDesigner },
  data() {
    return {
      flowId: ""
    };
  },
  created() {
    this.flowId = (this.$route.query && this.$route.query.id) || "";
  }
};
</script>
