<template>
  <master-table-shell :config="config" />
</template>

<script>
/**
 * 物料档案（B4 §8.4；规格 `REQ-STK` 主数据 / AC-72 的行项快照前提）。
 *
 * 页面本体零逻辑：列 / 表单字段 / 接口函数名全部来自 `master-page.js` 的 `product` 配置，
 * 渲染与取数由 `MasterTableShell` 统一负责（D12 的"禁止 N 份拷贝"）。
 *
 * 菜单：`component = 'erp/master/product/index'`，权限点 `ctms:partner:*`（沿用 B3 控制器注解）。
 */
import MasterTableShell from '../components/MasterTableShell'
import masterConfig from '../master-page'

export default {
  name: 'ErpMasterProduct',
  components: { MasterTableShell },
  data() {
    return {
      config: masterConfig.getMaster('product')
    }
  }
}
</script>
