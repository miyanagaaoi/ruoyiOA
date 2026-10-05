<template>
  <master-table-shell :config="config" />
</template>

<script>
/**
 * 仓库档案（B4 §8.4 / tasks.md 2.3）。
 *
 * 编码与名称唯一、可指定仓管员、只支持启用/停用；有结存或被单据引用时禁止删除；
 * 停用后不出现在新单据的仓库下拉（出库/入库/调拨/盘点的下拉走 `enableFlag='1'` 的列表查询）。
 * 菜单：`erp/master/warehouse/index`。
 */
import MasterTableShell from '../components/MasterTableShell'
import masterConfig from '../master-page'

export default {
  name: 'ErpMasterWarehouse',
  components: { MasterTableShell },
  data() {
    return {
      config: masterConfig.getMaster('warehouse')
    }
  }
}
</script>
