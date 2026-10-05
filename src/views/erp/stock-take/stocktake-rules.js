/**
 * 盘点单**独立交互**的前端口径（B4 §8.4 的"生成行项 → 录入实盘 → 审核"；tasks.md 6.3/6.4）。
 *
 * 为什么单独一个纯函数模块：盘点单是 8 类单据里唯一"交互与其它 7 类不同"的页面
 * （design D12 明确：盘点单独实现但复用行项表与状态标签）。把生成条件、实盘校验、
 * 生成单展示这三件事抽成纯函数，页面只做渲染，且可被单测钉住。
 *
 * 真源（已落地 `ErpStocktakeController`，URL 前缀 `/stk/take`）：
 *   · `POST /{id}/generate`（权限 `stk:take:add`）body = `ErpStocktake` 条件
 *     （`takeType` / `productTypeIds` / `items`（按物料抽盘，取 `productId`）/ `scopeNote`），
 *     返回**更新后的单据**（含新生成的行项）；
 *   · `PUT /{id}/count`（权限 `stk:take:edit`）body = `{ id, items: [{ id|productId, actualQty, diffReason }] }`，
 *     返回**更新后的单据**（差异由服务端算）；
 *   · `POST /approve/{id}`（权限 `stk:take:approve`）返回的单据带 `generatedInNo` / `generatedOutNo`。
 * 服务端的生成规则（`ErpStocktakeServiceImpl.generateItemsFor`）：
 *   · `full`：取该仓库**结存非零**的物料；`partial`：按 `items[].productId`（优先）或按
 *     `productTypeIds` 展开**整棵子树**。
 *
 * 写法：CommonJS（可被 Node 直接 require 做单测）。
 *
 * @author 二开
 */

'use strict'

var TAKE_TYPE_FULL = 'full'
var TAKE_TYPE_PARTIAL = 'partial'

/** 生成行项的 URL 与权限（与落地控制器逐字一致）。 */
function generateRequest(id, criteria) {
  return {
    method: 'post',
    url: '/stk/take/' + id + '/generate',
    data: buildCriteria(criteria)
  }
}

/** 保存实盘数量的 URL 与权限（`CountRequest`：`id` + `items`）。 */
function countRequest(id, items) {
  return {
    method: 'put',
    url: '/stk/take/' + id + '/count',
    data: { id: id, items: buildCountItems(items) }
  }
}

/**
 * 生成条件（镜像服务端读的字段）：
 *   · 全盘：只传 `takeType`；
 *   · 抽盘：传 `items[{productId}]`（按物料，优先）与/或 `productTypeIds`（按类型含子树）。
 */
function buildCriteria(criteria) {
  var c = criteria || {}
  var takeType = normalizeTakeType(c.takeType)
  var out = { takeType: takeType }
  if (takeType === TAKE_TYPE_PARTIAL) {
    var productIds = asArray(c.productIds)
    var typeIds = asArray(c.productTypeIds)
    if (productIds.length) {
      out.items = productIds.map(function (productId) {
        return { productId: productId }
      })
    }
    if (typeIds.length) {
      out.productTypeIds = typeIds
    }
    if (c.scopeNote) {
      out.scopeNote = c.scopeNote
    }
  } else if (c.scopeNote) {
    out.scopeNote = c.scopeNote
  }
  return out
}

/**
 * 抽盘生成前的**前端校验**（服务端也会判；这里只为"立刻看到原因"）。
 *
 * 全盘无需条件；抽盘必须至少给一个物料或一个商品类型（否则服务端只能生成 0 行）。
 *
 * @returns {{ok:boolean, message:string}}
 */
function validateGenerate(criteria) {
  var c = criteria || {}
  var takeType = normalizeTakeType(c.takeType)
  if (takeType === TAKE_TYPE_FULL) {
    return { ok: true, message: '' }
  }
  var productIds = asArray(c.productIds)
  var typeIds = asArray(c.productTypeIds)
  if (!productIds.length && !typeIds.length) {
    return { ok: false, message: '抽盘必须至少选择一种物料或一个商品类型' }
  }
  return { ok: true, message: '' }
}

/** 实盘数量载荷：只带服务端要的三个键（行 id 或物料 id + 实盘 + 差异原因）。 */
function buildCountItems(items) {
  return (items || []).map(function (item) {
    var row = item || {}
    var out = {
      actualQty: row.actualQty === undefined || row.actualQty === null ? 0 : row.actualQty,
      diffReason: row.diffReason === undefined ? null : row.diffReason
    }
    if (row.id) {
      out.id = row.id
    }
    if (row.productId) {
      out.productId = row.productId
    }
    return out
  })
}

/** 实盘录入前的校验：非负（服务端也判；tasks.md 6.3 要求"实盘数量非负"）。 */
function validateCount(items) {
  var list = items || []
  if (!list.length) {
    return { ok: false, message: '还没有盘点行项，请先「确认生成行项」' }
  }
  for (var i = 0; i < list.length; i++) {
    var v = list[i] ? list[i].actualQty : null
    if (v !== null && v !== undefined && v !== '' && Number(v) < 0) {
      return { ok: false, message: '第 ' + (i + 1) + ' 行实盘数量不能为负数' }
    }
  }
  return { ok: true, message: '' }
}

/**
 * 审核后生成的调整单提示（tasks.md 6.4：盘盈合并生成入库单、盘亏合并生成出库单、无差异不生成）。
 *
 * @returns {string} 形如「已生成盘盈入库单 RK001、盘亏出库单 CK002」；都没有时给"无差异，未生成调整单"
 */
function generatedDocText(doc) {
  var d = doc || {}
  var parts = []
  if (d.generatedInNo) {
    parts.push('盘盈入库单 ' + d.generatedInNo)
  }
  if (d.generatedOutNo) {
    parts.push('盘亏出库单 ' + d.generatedOutNo)
  }
  if (!parts.length) {
    return '本次无差异，未生成调整单'
  }
  return '已生成' + parts.join('、')
}

/** 盘点范围中文（与字典 `stock_take_types` 的 label 一致）。 */
function takeTypeLabel(takeType) {
  return normalizeTakeType(takeType) === TAKE_TYPE_PARTIAL ? '抽盘' : '全盘'
}

/** 归一化盘点范围（未知值按全盘处理，与服务端 `normalizeTakeType` 同口径）。 */
function normalizeTakeType(takeType) {
  return String(takeType || '').trim().toLowerCase() === TAKE_TYPE_PARTIAL ? TAKE_TYPE_PARTIAL : TAKE_TYPE_FULL
}

function asArray(v) {
  if (v === null || v === undefined || v === '') {
    return []
  }
  return Array.isArray(v) ? v.filter(function (x) { return x !== null && x !== undefined && x !== '' }) : [v]
}

module.exports = {
  TAKE_TYPE_FULL: TAKE_TYPE_FULL,
  TAKE_TYPE_PARTIAL: TAKE_TYPE_PARTIAL,
  generateRequest: generateRequest,
  countRequest: countRequest,
  buildCriteria: buildCriteria,
  validateGenerate: validateGenerate,
  buildCountItems: buildCountItems,
  validateCount: validateCount,
  generatedDocText: generatedDocText,
  takeTypeLabel: takeTypeLabel,
  normalizeTakeType: normalizeTakeType
}
