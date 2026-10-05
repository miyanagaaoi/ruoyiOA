import request from '@/utils/request'

// ============================================================================
// 往来单位档案（客户 / 供应商）—— 2.0 B3 `ctms:partner:*`
// 后端：ruoyi-ctms 的 CtmsPartnerController（@RequestMapping("/ctms/partner")）
//      物料域主数据（/ctms/product-type|uom|warehouse|product）在 **masterdata.js**，不在本文件。
//
// 权限点真源：`sql/二开-合同台账-菜单.sql`
//   档案列表与管理入口 = `ctms:partner:list`
//   **选择器 /options 要 `ctms:partner:query`**（不是 list）—— 合同表单按这个权限点决定
//     是否加载客户/供应商下拉（见 views/ctms/contract/detail.vue 的 canQueryPartner）
//   新增 / 编辑 / 启停用 / 删除 = `ctms:partner:add` / `edit` / `status` / `remove`
//
// 口径（与后端 `com.ruoyi.ctms.support.PartnerRules` 一致，**文案逐字对齐**，
// 因为提示文案是接口契约的一部分、验收脚本按关键字断言）：
//   · 客户简称选填、**供应商简称必填**（纯空白也算空）→ '供应商简称不能为空'；
//   · 账期天数只属于供应商：null＝未填、0＝现结（两者都合法），负数拒绝 → '账期天数不能为负数'；
//   · 启用标志只能是 '1'/'0'（null 视同启用）→ '启用标志只能是 0 或 1'；
//   · 编码/名称全局唯一 → '客户编码已存在' / '供应商名称已存在' 等；
//   · 档案被合同引用时不允许删除 → '客户档案已被合同引用，无法删除'。
//   ⚠ 以上判定与文案都在**服务端**；前端只做同文案的前置提示，并把服务端文案原样透出。
// ============================================================================

// ---------------------------------------------------------------- 客户档案

// 查询客户档案列表（enableFlag 为 null 时含停用项；档案管理页要看得到停用项）
export function listCustomer(query) {
  return request({ url: '/ctms/partner/customer/list', method: 'get', params: query })
}

/**
 * 客户选择器数据源：**只返回启用中**的档案（停用的不进新合同可选列表）。
 * ⚠ 权限点是 `ctms:partner:query`（不是 `ctms:partner:list`）；
 *   这就是规格「停用后新增合同的选择器不再出现该档案」的落点。
 */
export function listCustomerOptions() {
  return request({ url: '/ctms/partner/customer/options', method: 'get' })
}

// 查询客户档案详细
export function getCustomer(id) {
  return request({ url: '/ctms/partner/customer/' + id, method: 'get' })
}

// 新增客户档案
export function addCustomer(data) {
  return request({ url: '/ctms/partner/customer', method: 'post', data: data })
}

// 修改客户档案
export function updateCustomer(data) {
  return request({ url: '/ctms/partner/customer', method: 'put', data: data })
}

// 启停用客户档案（0-停用 1-启用）
export function changeCustomerStatus(id, enableFlag) {
  return request({ url: '/ctms/partner/customer/status', method: 'put', data: { id: id, enableFlag: enableFlag } })
}

// 删除客户档案（服务端仅在该档案零引用时放行）
export function delCustomer(id) {
  return request({ url: '/ctms/partner/customer/' + id, method: 'delete' })
}

// ---------------------------------------------------------------- 供应商档案

// 查询供应商档案列表
export function listSupplier(query) {
  return request({ url: '/ctms/partner/supplier/list', method: 'get', params: query })
}

// 供应商选择器数据源：只返回启用中的档案（权限点同样是 `ctms:partner:query`）
export function listSupplierOptions() {
  return request({ url: '/ctms/partner/supplier/options', method: 'get' })
}

// 查询供应商档案详细
export function getSupplier(id) {
  return request({ url: '/ctms/partner/supplier/' + id, method: 'get' })
}

// 新增供应商档案（简称必填）
export function addSupplier(data) {
  return request({ url: '/ctms/partner/supplier', method: 'post', data: data })
}

// 修改供应商档案（简称必填；账期天数非负）
export function updateSupplier(data) {
  return request({ url: '/ctms/partner/supplier', method: 'put', data: data })
}

// 启停用供应商档案
export function changeSupplierStatus(id, enableFlag) {
  return request({ url: '/ctms/partner/supplier/status', method: 'put', data: { id: id, enableFlag: enableFlag } })
}

// 删除供应商档案（服务端仅在该档案零引用时放行）
export function delSupplier(id) {
  return request({ url: '/ctms/partner/supplier/' + id, method: 'delete' })
}
