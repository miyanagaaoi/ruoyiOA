import request from '@/utils/request'

// ============================================================================
// 合同附件（2.0 B3 第 6 组；design D-5）
// 后端：ruoyi-ctms 的 CtmsAttachmentController（@RequestMapping("/ctms/attachment")）
// 权限点（真源 `sql/二开-合同台账-菜单.sql`）：
//   读（列表 / 下载 / 口径元数据）= `ctms:attachment:list`
//   写（上传 / 删除）= `ctms:contract:edit` **且** `ctms:attachment:list`
//     ⚠ 两个是 **AND** 关系。vue 的 `v-hasPermi` 是"数组里任一命中即放行"（OR），
//       所以在组件里用 `$auth.hasPermiAnd([...])` 算一个布尔量再决定按钮是否出现。
//
// ⚠ 限制口径（20MB / 后缀白名单）**必须**从 `/ctms/attachment/object-types` 取，
//   前端不得再写一份常量：后端白名单比平台窄（10 种），写死两份必然漂移。
// ============================================================================

/**
 * 某业务对象下的附件列表（只含未删除）。
 * ⚠ 返回体是 `AjaxResult.success(list)`：附件数组在 **data** 上（不是 rows/total）。
 *
 * @param {object} query { objectType, objectId }
 */
export function listAttachment(query) {
  return request({ url: '/ctms/attachment/list', method: 'get', params: query })
}

/**
 * 附件限制与对象类型注册表（口径元数据，无业务数据）：
 * ⚠ 返回体是 `AjaxResult.success()` + `put(...)`，所以
 * `{ registered, plannedB4, maxSizeBytes, maxSizeMb, extensions }` 都在**响应体顶层**，
 * 不在 data 上 —— 与 `/ctms/attachment/list` 的形态不同，别照抄。
 */
export function getAttachmentObjectTypes() {
  return request({ url: '/ctms/attachment/object-types', method: 'get' })
}

/**
 * 上传附件（multipart，字段名 `file`，与平台 `/common/upload` 一致）。
 *
 * ⚠ 刻意**不**手工设置 Content-Type：交给浏览器/axios 依据 FormData 生成带 boundary 的
 *   multipart 头，手写 'multipart/form-data' 反而会缺 boundary（DEV-ENV §6.20 的教训，
 *   同仓库 `api/workflow/sign.js` 也这么做）。
 * ⚠ 超限（> 20MB）时后端返回**真实 HTTP 413**，走的是 axios 的错误分支。
 *
 * @param {string} objectType 对象类型（合同为 `contract`）
 * @param {string} objectId   对象标识（合同ID）
 * @param {File}   file       文件
 */
export function uploadAttachment(objectType, objectId, file) {
  const form = new FormData()
  form.append('objectType', objectType)
  form.append('objectId', objectId)
  form.append('file', file)
  return request({ url: '/ctms/attachment/upload', method: 'post', data: form })
}

/**
 * 下载附件（鉴权发生在返回字节之前，所以必须走接口，不能拼 `/profile/**` 直链）。
 * 用 blob 取回，再由调用方落盘；后端把失败写成 JSON 时（HTTP 仍是 200）要用
 * `blobValidate` 判定，否则会存下一个内容是 JSON 的"坏文件"。
 */
export function downloadAttachment(id) {
  return request({ url: '/ctms/attachment/' + id + '/download', method: 'get', responseType: 'blob' })
}

// 删除附件（软删除 + 写字段名「附件」的变更历史）；删除后下载接口不再返回该文件
export function delAttachment(id) {
  return request({ url: '/ctms/attachment/' + id, method: 'delete' })
}
