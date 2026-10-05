import { listProduct, listUom, listWarehouse, treeProductType } from '@/api/ctms/masterdata'
import erpConst from '@/views/erp/erp-const'

/**
 * 库存主数据接口（B4 §8.4 的"物料 / 物料类型 / 计量单位 / 仓库"四页）。
 *
 * 口径：**复用 B3 已交付的档案接口**（`CtmsProductMasterController`，前缀 `/ctms`），
 * 本文件**不重写**任何请求模板，只做两件事：
 *   ① 把 B3 的四个资源原样转出口，让 `src/views/erp/**` 只依赖 `src/api/erp/**`
 *      （B3 README 的约定：视图不直接 `request({...})`）；
 *   ② 补"只取启用项"的下拉辅助函数 —— 规格要求"停用仓库不出现在新单据的仓库下拉"
 *      （tasks.md 2.3 / REQ-STK 主数据），实现方式是列表查询直接带 `enableFlag='1'`，
 *      不为下拉另造一个端点（B3 没有 `/options` 形态的物料/仓库接口）。
 *
 * 权限点：与 B3 控制器注解逐字一致 —— `ctms:partner:list / query / add / edit / remove`
 * （真源是后端的 `@PreAuthorize`；菜单侧必须用同一批点，见 `erp-const.MASTER_PERM_PREFIX`）。
 *
 * ⚠ 启停用走 `PUT`（update 里带 `enableFlag`）：B3 的物料/类型/单位/仓库**没有** `/status` 端点
 *   （只有往来单位档案有），所以前端不调 `/status`，否则必然 404。
 */

export * from '@/api/ctms/masterdata'

/** 只取启用的仓库（新单据的仓库下拉用）。 */
export function listEnabledWarehouses() {
  return listWarehouse({
    enableFlag: erpConst.ENABLE_FLAG_ON,
    pageNum: 1,
    pageSize: erpConst.OPTION_PAGE_SIZE
  })
}

/** 只取启用的计量单位（物料表单与行项单位下拉用）。 */
export function listEnabledUoms() {
  return listUom({
    enableFlag: erpConst.ENABLE_FLAG_ON,
    pageNum: 1,
    pageSize: erpConst.OPTION_PAGE_SIZE
  })
}

/** 只取启用的物料（单据行项选择器用；停用物料不得用于新行项）。 */
export function listEnabledProducts(keyword) {
  return listProduct({
    name: keyword || undefined,
    enableFlag: erpConst.ENABLE_FLAG_ON,
    pageNum: 1,
    pageSize: erpConst.OPTION_PAGE_SIZE
  })
}

/**
 * 物料类型**启用子树**。
 *
 * 服务端只按 `enableFlag` 过滤节点本身（不会级联剪枝），所以这里在客户端剔除停用节点；
 * 这只是显示口径，"只有叶子可挂物料"与"停用类型不可用"最终由服务端强制。
 */
export function treeEnabledProductTypes() {
  return treeProductType({ enableFlag: erpConst.ENABLE_FLAG_ON }).then(function (res) {
    return Object.assign({}, res, { data: pruneDisabled(res.data) })
  })
}

function pruneDisabled(nodes) {
  if (!nodes || !nodes.length) {
    return []
  }
  return nodes
    .filter(function (node) {
      return node.enableFlag === erpConst.ENABLE_FLAG_ON || node.enableFlag === undefined
    })
    .map(function (node) {
      var copy = Object.assign({}, node)
      copy.children = pruneDisabled(node.children)
      return copy
    })
}
