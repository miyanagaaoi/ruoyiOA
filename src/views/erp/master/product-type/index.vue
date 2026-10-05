<template>
  <master-table-shell :config="config" />
</template>

<script>
/**
 * 物料类型（树形；B4 §8.4 / tasks.md 2.1）。
 *
 * 层级上限 5 层、同父下名称唯一、只有叶子可挂物料、有子或被引用禁止删除 —— 均由**服务端**
 * 判定（本页只做"叶子才可选"的选择器约束与提示）。菜单：`erp/master/product-type/index`。
 */
import MasterTableShell from '../components/MasterTableShell'
import masterConfig from '../master-page'

export default {
  name: 'ErpMasterProductType',
  components: { MasterTableShell },
  data() {
    return {
      config: masterConfig.getMaster('product-type')
    }
  }
}
</script>
