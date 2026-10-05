import request, { download } from '@/utils/request'
import docKinds from '@/views/erp/doc/doc-kinds'
import rules from '@/views/erp/doc/doc-rules'

/**
 * 进销存**单据**接口（B4 §8.3 的 API 层，8 类单据共用一份）。
 *
 * 为什么只有一个文件：参考仓库用 `register_doc_routes` 一套代码展开 8 类单据 × 13 端点；
 * 本项目把"单据类型 → URL 前缀"这一件事收在 `doc-kinds.js`（`restBase`），
 * 这里只写一次请求模板。页面侧**不允许**自己拼 `request({...})`。
 *
 * 端点契约（**以已落地 Controller 为准**；三族形状不同，逐个 grep 的结论见 notes/09b §1/§2）：
 *
 *   通用（三族一致）：
 *   GET    {restBase}/list                     列表（分页 + 筛选）
 *   GET    {restBase}/{id}                     详情（含 items）
 *   POST   {restBase}                          新增草稿
 *   PUT    {restBase}                          修改（仅草稿）
 *   POST   {restBase}/export                   导出当前筛选（ExcelUtil 写二进制流）
 *   GET    {restBase}/{id}/change-logs         变更历史（⚠ 入库/出库/采购返回 `AjaxResult` 非分页；销售无此端点）
 *
 *   删除：
 *   DELETE {restBase}/{id}                     采购 / 销售（单 id）
 *   DELETE {restBase}/{ids}                    入库 / 出库（**逗号分隔多 id**）
 *
 *   动作（按 `doc-kinds.actionRequest.mode` 派发，见 `doc-rules.actionRequest`）：
 *   · 入库 / 出库：`POST {restBase}/{action}/{id}`，`reason` 走 query
 *   · 采购 / 销售：`PUT {restBase}/{id}/{action}`，`reason` 走 query（销售走 body `{reason}`）
 *     —— 采购的"驳回"折叠在 `approve` 端点上（`approve?action=reject&reason=`）
 *   · 盘点 / 调拨（端点未落地）：`POST {restBase}/{id}/{action}` + body `{id, reason}`
 *
 *   下推（按 `PUSH_CHAINS[*].push` 派发，见 `doc-rules.buildPushRequest`）：
 *   · 采购申请 → 采购单：`POST /erp/pur/request/{id}/push/purchase-order?supplierId=&remark=`，body 为裸数组 `[{srcItemId, qty}]`
 *   · 销售申请 → 销售订单：`POST /sal/request/{id}/push`，body 为 `{lines:[{docItemId, qty, …}], …}`
 *   · 采购单 → 入库单、销售订单 → 出库单：**端点未落地**（t7/t8），前端不显示下推入口
 *
 *   盘点专用（端点未落地，t9）：`POST {restBase}/{id}/generate`、`PUT {restBase}/{id}/count`
 *
 * 权限点（真源 = 各 Controller 的 `@PreAuthorize`，映射见 `erp-const.ACTION_PERM_SEGMENT`）：
 *   list→:list / 详情→:query / 新增→:add / 修改→:edit / 删除→:remove / 导出→:export
 *   动作：submit→:submit / approve 与 reject→:approve / **complete→:status** / void→:void / unapprove→:unapprove
 *   下推：**来源单据**的 `:push`（`pur:request:push` / `pur:order:push` / `sal:request:push` / `sal:order:push`）
 *
 * 数据范围：一律服务端强制（design D10）；导出与列表**同筛选、同范围**，前端不多带不多筛。
 */

/** 取单据类型配置；未登记的 code 直接抛错（比发一个 `undefined/list` 的请求好定位）。 */
function kindOf(kindCode) {
  const kind = docKinds.getKind(kindCode)
  if (!kind) {
    throw new Error('未登记的单据类型：' + kindCode)
  }
  return kind
}

/** 列表（分页参数与筛选条件原样透传；分页键是 pageNum/pageSize）。 */
export function listDocs(kindCode, query) {
  return request({ url: kindOf(kindCode).restBase + '/list', method: 'get', params: query })
}

/** 详情（超出数据范围由服务端返回业务 403）。 */
export function getDoc(kindCode, id) {
  return request({ url: kindOf(kindCode).restBase + '/' + id, method: 'get' })
}

/** 新增草稿（服务端生成 doc_no，前端不拼单号）。 */
export function addDoc(kindCode, data) {
  return request({ url: kindOf(kindCode).restBase, method: 'post', data: data })
}

/** 修改（仅草稿；非草稿由服务端拒绝）。 */
export function updateDoc(kindCode, data) {
  return request({ url: kindOf(kindCode).restBase, method: 'put', data: data })
}

/** 删除（仅草稿；已产生的单据应作废而非删除）。形状按 kind 派发（入库/出库是逗号分隔多 id）。 */
export function delDoc(kindCode, id) {
  const req = rules.deleteRequest(kindOf(kindCode), id)
  return request({ url: req.url, method: req.method })
}

/**
 * 单据动作（提交 / 审核 / 驳回 / 作废 / 反审核 / 置为已完成）。
 *
 * ⚠ 三族控制器的**动作 URL 形状不同**（入库/出库 `POST {base}/{action}/{id}`；采购/销售
 *   `PUT {base}/{id}/{action}`；reason 走 query 或 body），因此这里**不做任何判断**，
 *   一律交给 `doc-rules.actionRequest()` 这个纯函数按 kind 派发（一处实现、可单测）。
 *
 * @param {string} kindCode 单据码（doc-kinds 的 code）
 * @param {string} id       单据 ID
 * @param {string} action   submit / approve / reject / void / unapprove / complete
 * @param {string} [reason] 驳回 / 作废 / 反审核必填
 */
export function docAction(kindCode, id, action, reason) {
  const req = rules.actionRequest(kindOf(kindCode), action, id, reason)
  return request({ url: req.url, method: req.method, params: req.params, data: req.data })
}

/**
 * 下推（采购申请→采购单、销售申请→销售订单 已落地；订单→出入库待 t7/t8）。
 *
 * 请求形状同样按链派发（采购是裸数组 body + query 参数；销售是 `{lines:[...], ...}` body）：
 * 见 `doc-rules.buildPushRequest()`。端点未落地时返回 `null` ⇒ 这里**直接拒绝**，
 * 不发一个必然 404 的请求（按钮侧由 `canPush()` 提前隐藏）。
 *
 * @param {string} kindCode 来源单据码
 * @param {string} id       来源单据 ID
 * @param {Array}  rows     `doc-rules.pushRows()` 的产物（含 pushQty / srcItemId）
 * @param {object} [extra]  端点额外入参（采购：supplierId/remark；销售：remark/shipWarehouseId）
 */
export function pushDoc(kindCode, id, rows, extra) {
  const req = rules.buildPushRequest(kindOf(kindCode), id, rows, extra)
  if (!req) {
    return Promise.reject(new Error('该单据类型到下游的下推端点尚未落地（待后端交付）'))
  }
  return request({ url: req.url, method: req.method, params: req.params, data: req.data })
}

/** 导出当前筛选（列名与列顺序的真源是服务端 `@Excel` 注解；前端不自造表头）。 */
export function exportDocs(kindCode, query, filename) {
  const kind = kindOf(kindCode)
  return download(kind.restBase + '/export', query, filename || (kind.label + '.xlsx'))
}

/** 单据变更历史（分页）。 */
export function listChangeLogs(kindCode, id, query) {
  return request({
    url: kindOf(kindCode).restBase + '/' + id + '/change-logs',
    method: 'get',
    params: query
  })
}

/* ---------------------------------------------------------------- 盘点专用 */

/** 生成盘点行项（全盘取该仓库结存非零物料；抽盘按物料或商品类型含子树）。 */
export function generateStocktakeItems(kindCode, id, data) {
  return request({ url: kindOf(kindCode).restBase + '/' + id + '/generate', method: 'post', data: data })
}

/** 录入实盘数量（账面数量只读；差异由服务端算）。 */
export function saveStocktakeCount(kindCode, id, items) {
  return request({
    url: kindOf(kindCode).restBase + '/' + id + '/count',
    method: 'put',
    data: { id: id, items: items || [] }
  })
}
