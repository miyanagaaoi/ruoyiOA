import request, { download } from '@/utils/request'

// ============================================================================
// 合同台账（合同主体 + 行项）—— 2.0 B3 第 4/5/6 组
// 后端：ruoyi-ctms 的 CtmsContractController（@RequestMapping("/ctms/contract")）
// 权限点真源：`sql/二开-合同台账-菜单.sql`
//   ctms:contract:list / query / add / edit / remove / status / export + ctms:contract-item:list
//
// 本文件当前覆盖**列表页（任务 8.1）用到的端点**：list / export / {id} / framework/{id}
//   以及状态流转、软删除与恢复。新增/编辑表单要用的 add / update / next-no /
//   warranty-release 由任务 8.2 在同一个文件里续写（「一个后端资源一个文件」，见同目录 README）。
//
// ⚠ 判成败一律看响应体的 code（后端业务异常时 HTTP 仍是 200），见同目录 README。
// ============================================================================

/*
 * ⚠ 数组参数（标签多选 tagIds）在这条链路上的**真实形态**，写在这里免得下次重复排查：
 *
 *   `@/utils/request.js` 的请求拦截器对 GET 做了转换 ——
 *     `config.url += '?' + tansParams(config.params)`，然后把 `config.params` 清空。
 *   所以在 api 层写 axios 的 `paramsSerializer` 是**不会被执行**的（那是死代码，本项目不要写）。
 *
 *   `tansParams`（`@/utils/ruoyi.js`）对"对象/数组"走的是**下标键**形态：
 *     `{ tagIds: ['a','b'] }` → `tagIds[0]=a&tagIds[1]=b`（URL 编码后是 tagIds%5B0%5D=...）。
 *   这正是 Spring MVC 命令对象的**索引属性绑定**：`CtmsContract.tagIds` 是 `List<String>`，
 *   `tagIds[0]/tagIds[1]` 会被绑成一个 2 元素 List —— 靠的是 `WebDataBinder` 的
 *   `setAutoGrowNestedPaths(true)` 与 `setAutoGrowCollectionLimit(...)`（列表为空/为 null 时自动长出元素），
 *   属于 Spring MVC 的既有能力，不需要框架侧加转换器。
 *
 *   与验收脚本 `tools/ctms-contract-check.ps1` 的 `tagIds=${a},${b}`（逗号形态）**语义等价**：
 *   那条走的是"单值参数 → StringToCollectionConverter 按逗号切分"，两条都落到同一个 List<String>。
 *
 *   口径不变：选 N 个标签就提交 N 个 id（**交集**，服务端 HAVING COUNT = N），
 *   前端不做任何「或」的兜底展开；空数组/undefined 由 tansParams 自动丢弃，不会发出 `tagIds=`。
 */

// ---------------------------------------------------------------- 列表与详情

/**
 * 查询合同列表（分页）。
 *
 * 筛选口径（与服务端一一对应，前端不做二次过滤）：
 *   keyword       关键字：合同编号/名称/甲方文本/乙方文本 + 行项名称/规格（服务端 EXISTS 子查询）
 *   type          合同类型（字典 contract_types 的 dict_value，如 SAL/PUR）
 *   status        进度状态（字典 contract_statuses 的 dict_value）
 *   arrivalStatus 到货状态（字典 arrival_statuses 的 dict_value）
 *   isFramework   是否框架：'1' / '0'
 *   ownerName     经办人（模糊）
 *   beginSignDate / endSignDate  签订日期区间（yyyy-MM-dd 字符串，闭区间）
 *   tagIds        标签集合 —— **交集**：只返回同时具备全部标签的合同（数组按 tansParams 的
 *                 下标键形态发出去，见文件头 ⚠；前端不做「或」兜底）
 *   includeDeleted 是否包含已停用：仅 **"1"** 才包含（其它值/不传都只返回未停用）
 */
export function listContract(query) {
  return request({ url: '/ctms/contract/list', method: 'get', params: query })
}

/**
 * 导出合同台账 xlsx（**由服务端导全量**）。
 *
 * 后端 `/ctms/contract/export`（`CtmsContractController.export`）由 `ExcelUtil` 直接写二进制响应流
 * （响应体里**没有** `rows`）；列名与列顺序的真源是后端 `ContractExportRow` 的 `@Excel` 注解 ——
 * 前端只触发下载、不自造表头。
 *
 * 委托 `utils/request.js` 的通用 `download()`：POST + `responseType: 'blob'` + `blobValidate`
 * + `saveAs` + 失败体（JSON）解析，并自带下载 loading；失败时给出可见的错误提示
 * （不会把错误体当成文件落盘）。
 *
 * @param {object} query    与列表一致的筛选条件（数据范围仍由服务端强制）
 * @param {string} filename 落地文件名，如 `合同台账_20261005-1530.xlsx`
 */
export function exportContract(query, filename) {
  return download('/ctms/contract/export', query, filename || '合同台账.xlsx')
}

// 合同详情（含标签、行项、变更历史、只读关联单据）；已停用的合同同样可读
export function getContract(id) {
  return request({ url: '/ctms/contract/' + id, method: 'get' })
}

// 框架详情：合同本身 + 子合同清单（children）+ 子合同数量 + 子合同金额合计
export function getFrameworkInfo(id) {
  return request({ url: '/ctms/contract/framework/' + id, method: 'get' })
}

// ---------------------------------------------------------------- 标签（只读，供列表筛选）

/**
 * 标签选项（只读）：列表页的「标签交集」筛选需要标签字典做多选。
 *
 * ⚠ 标签自身的增删改属于 `src/api/ctms/tag.js`（任务 8.3 的交付物），这里**只**放读选项，
 *   免得 8.1 为了一次下拉去越界改别的资源文件；8.3 落地 tag.js 之后，本函数可以改成
 *   一行转出口（`export { listTag as listTagOptions } from './tag'`），调用方（视图）不用动。
 * ⚠ 必须显式带上分页参数：`BaseController.startPage()` 在**缺参时默认 pageNum=1 / pageSize=10**
 *   （`TableSupport` 里 Convert.toInt 的默认值），不传就只能拿到前 10 个标签。
 */
export function listTagOptions() {
  return request({ url: '/ctms/tag/list', method: 'get', params: { pageNum: 1, pageSize: 500 } })
}

// ---------------------------------------------------------------- 状态流转 / 软删除 / 恢复

/**
 * 进度状态与到货状态流转（任意状态互转，不做顺序守卫，两者不联动）。
 * 改为「已终止」时**终止原因必填**，原因由请求体的 `deletedReason` 字段承载
 * （字段名复用只为承载该必填项，与软删除无关；服务端把它写进 status 的变更历史新值里）。
 *
 * @param {object} data { id, status, arrivalStatus, deletedReason }
 */
export function changeContractStatus(data) {
  return request({ url: '/ctms/contract/status', method: 'put', data: data })
}

// 停用（软删除）：停用原因必填；停用后编号仍占号不复用，30 天内可恢复
export function delContract(id, reason) {
  return request({ url: '/ctms/contract/' + id, method: 'delete', params: { reason: reason } })
}

// 恢复（停用时间起算整数日差 ≤ 30 天）；对未停用合同调用是幂等成功
export function restoreContract(id) {
  return request({ url: '/ctms/contract/' + id + '/restore', method: 'put' })
}

// ---------------------------------------------------------------- 登记 / 编辑 / 编号 / 质保 / 历史

/**
 * 登记合同（新增）。
 *
 * ⚠ 主键由服务端生成：请求体里的 `id` 一律**忽略**（DEV-ENV §6.38），
 *   所以"保存后再拿 id 去挂附件"这条路要**先保存再上传**。
 * ⚠ 合同金额与服务端的行项口径绑定：提交了 `items` 时服务端会按
 *   「行总价先舍入到 2 位再求和」**覆盖** `amount`，前端传的金额不参与。
 */
export function addContract(data) {
  return request({ url: '/ctms/contract', method: 'post', data: data })
}

/**
 * 编辑合同。
 *
 * ⚠ 合并语义（后端 `mergeForUpdate`）：字段为 `null` = 本次未提交（保持库内原值），
 *   空串 = 显式清空。`parentId` 尤其要知道：传 `''` 才是"解绑父框架"。
 * ⚠ 已停用（软删除）的合同不可编辑，服务端返回「合同已停用，请先恢复」。
 */
export function updateContract(data) {
  return request({ url: '/ctms/contract', method: 'put', data: data })
}

/**
 * 合同编号预览（**不占号**，连续两次调用返回同一编号）。
 *
 * ⚠ 返回值在响应体的 **`msg`** 上，不在 `data` 上 —— `AjaxResult.success(String)` 命中的是
 *   `success(String msg)` 重载（与 `/system/config/configKey` 同款形态，见 HANDOFF §4 的提醒）。
 * ⚠ 权限点用 `ctms:contract:add`（能登记才需要预览）。
 *
 * @param {object} params { type, subjectCode, referenceDay }（referenceDay 为 yyyy-MM-dd）
 */
export function nextContractNo(params) {
  return request({ url: '/ctms/contract/next-no', method: 'get', params: params })
}

/**
 * 释放质保（权限点 `ctms:contract:edit`，design Q5：暂归入编辑权限）。
 * 未启用质保时服务端返回「该合同未启用质保」。
 */
export function releaseWarranty(id) {
  return request({ url: '/ctms/contract/warranty/' + id + '/release', method: 'put' })
}

/**
 * 某合同的字段级变更历史（分页，按 create_time 倒序）。
 *
 * 字段：`fieldName`（多数已是中文列名，另有 `_summary`/`_items`/`_tags`/`deleted`/`status` 等特殊名）、
 * `oldValue`/`newValue`、`note`、`source`（manual-手动 / auto-自动）、
 * `operatorName`（可空，前端展示占位符「—」）、`createTime`。
 *
 * @param {string} id    合同ID
 * @param {object} query { pageNum, pageSize }
 */
export function listContractChangeLogs(id, query) {
  return request({ url: '/ctms/contract/' + id + '/change-logs', method: 'get', params: query })
}
