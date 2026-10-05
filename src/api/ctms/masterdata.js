import request from '@/utils/request'

// ============================================================================
// 物料域主数据（商品类型 / 计量单位 / 仓库 / 物料）—— 2.0 B3 §3.3
// 后端：ruoyi-ctms 的 CtmsProductMasterController
//   路径前缀：/ctms/product-type、/ctms/uom、/ctms/warehouse、/ctms/product
// 权限点：本批**不新增权限点**，复用 `ctms:partner:*`（档案类主数据统一归「往来单位」资源）。
// 约束口径（服务端强制，前端只做体验）：类型树 ≤5 级、同父下同名唯一、仅叶子可挂物料、
//   单位小数位 0~4、被物料引用的单位与挂有物料的类型禁止删除。
// ============================================================================

// ---------------------------------------------------------------- 商品类型

// 类型列表（name 模糊、parentId / enableFlag 精确）
export function listProductType(query) {
  return request({ url: '/ctms/product-type/list', method: 'get', params: query })
}

// 类型树（children 挂在父节点上）
export function treeProductType(query) {
  return request({ url: '/ctms/product-type/tree', method: 'get', params: query })
}

// 类型详细
export function getProductType(id) {
  return request({ url: '/ctms/product-type/' + id, method: 'get' })
}

// 新增类型
export function addProductType(data) {
  return request({ url: '/ctms/product-type', method: 'post', data: data })
}

// 修改类型
export function updateProductType(data) {
  return request({ url: '/ctms/product-type', method: 'put', data: data })
}

// 删除类型（有子类型或有物料时被服务端拒绝）
export function delProductType(id) {
  return request({ url: '/ctms/product-type/' + id, method: 'delete' })
}

// ---------------------------------------------------------------- 计量单位

export function listUom(query) {
  return request({ url: '/ctms/uom/list', method: 'get', params: query })
}

export function getUom(id) {
  return request({ url: '/ctms/uom/' + id, method: 'get' })
}

export function addUom(data) {
  return request({ url: '/ctms/uom', method: 'post', data: data })
}

export function updateUom(data) {
  return request({ url: '/ctms/uom', method: 'put', data: data })
}

// 删除单位（被物料引用时被服务端拒绝）
export function delUom(id) {
  return request({ url: '/ctms/uom/' + id, method: 'delete' })
}

// ---------------------------------------------------------------- 仓库

export function listWarehouse(query) {
  return request({ url: '/ctms/warehouse/list', method: 'get', params: query })
}

export function getWarehouse(id) {
  return request({ url: '/ctms/warehouse/' + id, method: 'get' })
}

export function addWarehouse(data) {
  return request({ url: '/ctms/warehouse', method: 'post', data: data })
}

export function updateWarehouse(data) {
  return request({ url: '/ctms/warehouse', method: 'put', data: data })
}

// 删除仓库（B3 阶段无单据引用统计；B4 接入后由服务端补引用保护）
export function delWarehouse(id) {
  return request({ url: '/ctms/warehouse/' + id, method: 'delete' })
}

// ---------------------------------------------------------------- 物料档案

export function listProduct(query) {
  return request({ url: '/ctms/product/list', method: 'get', params: query })
}

export function getProduct(id) {
  return request({ url: '/ctms/product/' + id, method: 'get' })
}

// 新增物料（code 留空时由服务端按「类型码 + 4 位序号」生成）
export function addProduct(data) {
  return request({ url: '/ctms/product', method: 'post', data: data })
}

export function updateProduct(data) {
  return request({ url: '/ctms/product', method: 'put', data: data })
}

export function delProduct(id) {
  return request({ url: '/ctms/product/' + id, method: 'delete' })
}
