<template>
  <master-table-shell :config="config" />
</template>

<script>
/**
 * 计量单位（B4 §8.4 / tasks.md 2.2）。
 *
 * 小数位 0~4（表单里用 input-number 的 min/max 约束 + 服务端拒绝）；被物料引用时禁止删除。
 * 菜单：`erp/master/uom/index`。
 */
import MasterTableShell from '../components/MasterTableShell'
import masterConfig from '../master-page'

export default {
  name: 'ErpMasterUom',
  components: { MasterTableShell },
  data() {
    return {
      config: masterConfig.getMaster('uom')
    }
  }
}
</script>
