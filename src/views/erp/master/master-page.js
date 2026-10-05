/**
 * 库存主数据四页的**配置驱动清单**（B4 §8.4 的物料 / 物料类型 / 计量单位 / 仓库）。
 *
 * 与单据壳同一套思路：四个页面的差异只有"列 / 表单字段 / 接口函数名 / 是否树形"，
 * 写进本文件后，页面侧就只剩 `<master-table-shell :config="cfg" />`。
 *
 * 字段名真源：B3 已交付的 `t_ctms_product_type` / `t_ctms_uom` / `t_ctms_warehouse` /
 * `t_ctms_product`（`sql/二开-合同台账.sql`），字段与实体 `Ctms*` 一一对应（camelCase）。
 *
 * 权限点：**沿用 B3 控制器注解** `ctms:partner:list / query / add / edit / remove`
 * （`CtmsProductMasterController`），启停用走 `edit`（该控制器没有 `/status` 端点）。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

var CONST = require('../erp-const')

/** 权限点：`ctms:partner:<动作>`。 */
function perm(action) {
  return CONST.MASTER_PERM_PREFIX + ':' + action
}

/** 启用状态下拉的可选值（列表筛选用）。 */
var ENABLE_OPTIONS = [
  { value: CONST.ENABLE_FLAG_ON, label: '启用' },
  { value: CONST.ENABLE_FLAG_OFF, label: '停用' }
]

/** 基础字段（四页共用的"启用标志 + 备注"）。 */
function tailFields() {
  return [
    { prop: 'enableFlag', label: '状态', kind: 'enable', span: 12 },
    { prop: 'remark', label: '备注', kind: 'textarea', span: 24, maxlength: 1000 }
  ]
}

var MASTERS = [
  /* ---------------------------------------------------------------- 物料档案 */
  {
    key: 'product',
    title: '物料档案',
    subtitle:
      '编码可由系统按「类型码 + 4 位序号」自动生成（留空即自动）；必须挂**叶子**类型与计量单位；' +
      '默认单价与安全库存不得为负数。停用物料不可用于新行项，历史单据按快照展示。',
    listPath: '/erp/master/product',
    permPrefix: CONST.MASTER_PERM_PREFIX,
    tree: false,
    leafTypeOnly: true,
    optionSources: ['productTypes', 'uoms'],
    api: {
      list: 'listProduct',
      get: 'getProduct',
      add: 'addProduct',
      update: 'updateProduct',
      del: 'delProduct'
    },
    columns: [
      { prop: 'code', label: '物料编码', width: 140, showOverflowTooltip: true },
      { prop: 'name', label: '物料名称', minWidth: 200, showOverflowTooltip: true },
      { prop: 'spec', label: '规格', width: 150, showOverflowTooltip: true },
      { prop: 'productTypeId', label: '物料类型', kind: 'map', map: 'productTypes', width: 140 },
      { prop: 'uomId', label: '计量单位', kind: 'map', map: 'uoms', width: 110, align: 'center' },
      { prop: 'defaultPrice', label: '默认单价', kind: 'money', width: 120, align: 'right' },
      { prop: 'safetyStock', label: '安全库存', kind: 'qty', width: 120, align: 'right' },
      { prop: 'enableFlag', label: '状态', kind: 'enable', width: 90, align: 'center' },
      { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true }
    ],
    filters: [
      { prop: 'code', label: '物料编码', kind: 'input', placeholder: '请输入编码（支持模糊）' },
      { prop: 'name', label: '物料名称', kind: 'input', placeholder: '请输入名称' },
      { prop: 'productTypeId', label: '物料类型', kind: 'productType' },
      { prop: 'uomId', label: '计量单位', kind: 'uom' },
      { prop: 'enableFlag', label: '状态', kind: 'select', options: ENABLE_OPTIONS }
    ],
    fields: [
      { prop: 'code', label: '物料编码', kind: 'input', span: 12, placeholder: '留空则按所选类型自动生成' },
      { prop: 'name', label: '物料名称', kind: 'input', span: 12, required: true },
      { prop: 'spec', label: '规格', kind: 'input', span: 12 },
      { prop: 'brand', label: '品牌', kind: 'input', span: 12 },
      { prop: 'productTypeId', label: '物料类型', kind: 'productType', span: 12, required: true },
      { prop: 'uomId', label: '计量单位', kind: 'uom', span: 12, required: true },
      { prop: 'barcode', label: '条码', kind: 'input', span: 12 },
      { prop: 'defaultPrice', label: '默认单价', kind: 'number', span: 12, precision: 2, min: 0 },
      { prop: 'safetyStock', label: '安全库存', kind: 'number', span: 12, precision: 3, min: 0 }
    ].concat(tailFields())
  },

  /* ---------------------------------------------------------------- 物料类型（树） */
  {
    key: 'product-type',
    title: '物料类型',
    subtitle:
      '树形维护：层级上限 5 层、同一父节点下名称唯一、**只有叶子**可以挂物料；' +
      '有下级或已被物料引用时禁止删除。',
    listPath: '/erp/master/product-type',
    permPrefix: CONST.MASTER_PERM_PREFIX,
    tree: true,
    optionSources: ['productTypes'],
    api: {
      list: 'treeProductType',
      get: 'getProductType',
      add: 'addProductType',
      update: 'updateProductType',
      del: 'delProductType'
    },
    columns: [
      { prop: 'name', label: '类型名称', minWidth: 240, showOverflowTooltip: true },
      { prop: 'code', label: '编码/前缀', width: 140 },
      { prop: 'level', label: '层级', width: 80, align: 'center' },
      { prop: 'sort', label: '排序', width: 80, align: 'center' },
      { prop: 'enableFlag', label: '状态', kind: 'enable', width: 90, align: 'center' },
      { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true }
    ],
    filters: [
      { prop: 'name', label: '类型名称', kind: 'input', placeholder: '请输入名称（支持模糊）' },
      { prop: 'enableFlag', label: '状态', kind: 'select', options: ENABLE_OPTIONS }
    ],
    fields: [
      { prop: 'parentId', label: '上级类型', kind: 'productType', span: 12, placeholder: '不选则为根类型' },
      { prop: 'name', label: '类型名称', kind: 'input', span: 12, required: true },
      { prop: 'code', label: '编码/前缀', kind: 'input', span: 12, placeholder: '用于物料编码前缀' },
      { prop: 'sort', label: '排序', kind: 'number', span: 12, precision: 0, min: 0 }
    ].concat(tailFields())
  },

  /* ---------------------------------------------------------------- 计量单位 */
  {
    key: 'uom',
    title: '计量单位',
    subtitle: '编码全局唯一；小数位限定 0~4（决定引用它的物料在单据行项上允许录入的数量精度）；被物料引用时禁止删除。',
    listPath: '/erp/master/uom',
    permPrefix: CONST.MASTER_PERM_PREFIX,
    tree: false,
    optionSources: [],
    api: {
      list: 'listUom',
      get: 'getUom',
      add: 'addUom',
      update: 'updateUom',
      del: 'delUom'
    },
    columns: [
      { prop: 'code', label: '单位编码', width: 140 },
      { prop: 'name', label: '单位名称', minWidth: 180, showOverflowTooltip: true },
      { prop: 'decimals', label: '小数位', width: 100, align: 'center' },
      { prop: 'enableFlag', label: '状态', kind: 'enable', width: 90, align: 'center' },
      { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true }
    ],
    filters: [
      { prop: 'code', label: '单位编码', kind: 'input', placeholder: '请输入编码' },
      { prop: 'name', label: '单位名称', kind: 'input', placeholder: '请输入名称' },
      { prop: 'enableFlag', label: '状态', kind: 'select', options: ENABLE_OPTIONS }
    ],
    fields: [
      { prop: 'code', label: '单位编码', kind: 'input', span: 12, required: true },
      { prop: 'name', label: '单位名称', kind: 'input', span: 12, required: true },
      { prop: 'decimals', label: '小数位', kind: 'number', span: 12, required: true, precision: 0, min: 0, max: 4 }
    ].concat(tailFields())
  },

  /* ---------------------------------------------------------------- 仓库 */
  {
    key: 'warehouse',
    title: '仓库',
    subtitle: '编码与名称唯一、可指定仓管员；只支持启用/停用；有结存或被单据引用时禁止删除；停用后不出现在新单据的仓库下拉中。',
    listPath: '/erp/master/warehouse',
    permPrefix: CONST.MASTER_PERM_PREFIX,
    tree: false,
    optionSources: [],
    api: {
      list: 'listWarehouse',
      get: 'getWarehouse',
      add: 'addWarehouse',
      update: 'updateWarehouse',
      del: 'delWarehouse'
    },
    columns: [
      { prop: 'code', label: '仓库编码', width: 140 },
      { prop: 'name', label: '仓库名称', minWidth: 200, showOverflowTooltip: true },
      { prop: 'address', label: '地址', minWidth: 200, showOverflowTooltip: true },
      { prop: 'keeperUserId', label: '仓管员ID', width: 130 },
      { prop: 'enableFlag', label: '状态', kind: 'enable', width: 90, align: 'center' },
      { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true }
    ],
    filters: [
      { prop: 'code', label: '仓库编码', kind: 'input', placeholder: '请输入编码' },
      { prop: 'name', label: '仓库名称', kind: 'input', placeholder: '请输入名称' },
      { prop: 'enableFlag', label: '状态', kind: 'select', options: ENABLE_OPTIONS }
    ],
    fields: [
      { prop: 'code', label: '仓库编码', kind: 'input', span: 12, required: true },
      { prop: 'name', label: '仓库名称', kind: 'input', span: 12, required: true },
      { prop: 'address', label: '仓库地址', kind: 'input', span: 24 },
      { prop: 'keeperUserId', label: '仓管员ID', kind: 'input', span: 12, placeholder: '选填：仓管员用户ID' }
    ].concat(tailFields())
  }
]

/** api 里允许出现的函数名（单测按这份清单核对，防止配置写错函数名而静默失败）。 */
var API_FUNCTIONS = [
  'listProduct', 'getProduct', 'addProduct', 'updateProduct', 'delProduct',
  'treeProductType', 'listProductType', 'getProductType', 'addProductType', 'updateProductType', 'delProductType',
  'listUom', 'getUom', 'addUom', 'updateUom', 'delUom',
  'listWarehouse', 'getWarehouse', 'addWarehouse', 'updateWarehouse', 'delWarehouse'
]

/** 字段/筛选允许出现的控件类型（单测核对：没实现的类型不允许出现在配置里）。 */
var FIELD_KINDS = ['input', 'textarea', 'number', 'select', 'enable', 'productType', 'uom']

/** 按 key 取配置。 */
function getMaster(key) {
  for (var i = 0; i < MASTERS.length; i++) {
    if (MASTERS[i].key === key) {
      return MASTERS[i]
    }
  }
  return null
}

/**
 * 校验四页配置（供单测）。
 *
 * @returns {{ok:boolean, problems:string[]}}
 */
function validateMasters(list) {
  var problems = []
  var masters = list || MASTERS
  var keys = []
  if (masters.length !== 4) {
    problems.push('主数据页面应为 4 个，实际 ' + masters.length)
  }
  masters.forEach(function (cfg) {
    if (!cfg.key) {
      problems.push('存在没有 key 的配置')
      return
    }
    keys.push(cfg.key)
    if ((cfg.permPrefix || '') !== CONST.MASTER_PERM_PREFIX) {
      problems.push(cfg.key + ' 的权限点前缀应为 ' + CONST.MASTER_PERM_PREFIX)
    }
    ;['api', 'columns', 'filters', 'fields'].forEach(function (k) {
      if (!cfg[k]) {
        problems.push(cfg.key + ' 缺少 ' + k)
      }
    })
    var api = cfg.api || {}
    ;['list', 'add', 'update', 'del'].forEach(function (k) {
      if (!api[k]) {
        problems.push(cfg.key + ' 的 api 缺少 ' + k)
      } else if (API_FUNCTIONS.indexOf(api[k]) < 0) {
        problems.push(cfg.key + ' 的 api.' + k + ' 不是已知接口函数：' + api[k])
      }
    })
    ;(cfg.fields || []).concat(cfg.filters || []).forEach(function (f) {
      if (FIELD_KINDS.indexOf(f.kind) < 0) {
        problems.push(cfg.key + ' 的字段 ' + f.prop + ' 控件类型未实现：' + f.kind)
      }
    })
    ;(cfg.fields || []).forEach(function (f) {
      if (f.prop === 'code' && f.required && cfg.key === 'product') {
        problems.push('物料编码不应该是必填：留空时由服务端自动生成')
      }
      if (f.prop === 'decimals' && (f.min !== 0 || f.max !== 4)) {
        problems.push('计量单位小数位必须限定 0~4')
      }
    })
    ;(cfg.columns || []).forEach(function (c) {
      if (!c.prop) {
        problems.push(cfg.key + ' 存在没有 prop 的列')
      }
    })
  })
  ;['product', 'product-type', 'uom', 'warehouse'].forEach(function (k) {
    if (keys.indexOf(k) < 0) {
      problems.push('缺少主数据页面配置：' + k)
    }
  })
  return { ok: problems.length === 0, problems: problems }
}

module.exports = {
  MASTERS: MASTERS,
  ENABLE_OPTIONS: ENABLE_OPTIONS,
  API_FUNCTIONS: API_FUNCTIONS,
  FIELD_KINDS: FIELD_KINDS,
  perm: perm,
  getMaster: getMaster,
  validateMasters: validateMasters
}
