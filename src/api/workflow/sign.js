import request from '@/utils/request'

/**
 * 签名与撤销（PRD 第 8 章 / 10.2-B）
 *
 * 请求体只需 businessId / taskId / taskDefKey / nodeName / signType / fileId；
 * 签名人、签名时间、IP、UA、表单快照哈希、上一条哈希**全部由服务端生成** ——
 * 客户端传什么都不采信，否则哈希链就没有意义。
 */
export function addSignRecord(data) {
  return request({
    url: '/workflow/sign/record',
    method: 'post',
    data: data
  })
}

/** 撤销某任务的签名（服务端是"追加一条撤销记录"，不会改掉原记录） */
export function revokeSignRecord(businessId, taskId, reason) {
  return request({
    url: '/workflow/sign/record/revoke',
    method: 'post',
    params: { businessId: businessId, taskId: taskId, reason: reason }
  })
}

/** 某单据的全部签名记录 */
export function listSignRecords(businessId) {
  return request({
    url: '/workflow/sign/record/list',
    method: 'get',
    params: { businessId: businessId }
  })
}

/** 每个节点当前有效的签名：taskId -> fileId */
export function listEffectiveSigns(businessId) {
  return request({
    url: '/workflow/sign/record/effective',
    method: 'get',
    params: { businessId: businessId }
  })
}

/* ==================== 节点签名策略（PRD 8.2 / AC-25） ==================== */

/**
 * 当前任务节点的签名策略：`{ signMode, signTypes[], signRequired }`。
 *
 * 签名弹窗靠它决定"这个节点允许手写还是预存"—— PRD 8.2 的「允许方式」生效点。
 * `signTypes` 缺省为 `['HANDWRITE', 'PRESET']`（PRD 默认值），服务端已把默认值补好，
 * 前端不要再自己补一遍，否则两处默认值迟早会不一致。
 *
 * ⚠ 这个接口**失败不该挡住签名**：拿不到策略时按"两种都允许"处理（fail-open）。
 */
export function getSignPolicy(taskId) {
  return request({
    url: '/workflow/sign/policy',
    method: 'get',
    params: { taskId: taskId }
  })
}

/* ==================== 预存签名（PRD 8.4 / AC-29、AC-30） ==================== */

/**
 * 我的预存签名列表（服务端按当前登录用户过滤，默认签名排最前）。
 *
 * 归属校验在服务端做 —— 前端拿到的天生只有自己的，不需要也不应该再筛一遍。
 */
export function listMySignPresets() {
  return request({
    url: '/workflow/sign/preset/list',
    method: 'get'
  })
}

/**
 * 我的默认预存签名；**没有配过时 data 为 null**。
 *
 * "没有默认签名"是正常状态而不是错误，调用方据此决定「一键使用默认签名」
 * 是否可用即可，不要去猜、也不要弹错误提示。
 */
export function getDefaultSignPreset() {
  return request({
    url: '/workflow/sign/preset/default',
    method: 'get'
  })
}

/**
 * 新增预存签名。
 *
 * 只传 `{name, fileId}`：user_id / 默认标记 / 状态 / 时间全部由服务端决定。
 * **第一枚自动成为默认**；显式传 `isDefault: '1'` 也会在同一事务内先清后置。
 */
export function addSignPreset(data) {
  return request({
    url: '/workflow/sign/preset',
    method: 'post',
    data: data
  })
}

/**
 * 改名（`name`）或停用启用（`status`：'0' 停用 / '1' 启用）。
 *
 * 服务端只接受这两个字段，传别的一律忽略 —— 签名图不可替换，
 * 换图应该新增一枚，否则已引用它的历史签名记录会被动改观。
 */
export function updateSignPreset(data) {
  return request({
    url: '/workflow/sign/preset',
    method: 'put',
    data: data
  })
}

/** 设为默认（服务端同一事务内先清后置，保证同一用户只有一个默认） */
export function setDefaultSignPreset(id) {
  return request({
    url: '/workflow/sign/preset/default/' + id,
    method: 'put'
  })
}

/** 删除（逻辑删：签名图可能已被历史签名记录引用） */
export function delSignPreset(id) {
  return request({
    url: '/workflow/sign/preset/' + id,
    method: 'delete'
  })
}

/** dataURL(base64) -> Blob */
export function dataURLtoBlob(dataURL) {
  const arr = String(dataURL).split(',')
  const mime = (arr[0].match(/:(.*?);/) || [null, 'image/png'])[1]
  const bstr = window.atob(arr[1])
  let n = bstr.length
  const u8 = new Uint8Array(n)
  while (n--) {
    u8[n] = bstr.charCodeAt(n)
  }
  return new Blob([u8], { type: mime })
}

/**
 * 上传签名图片，返回**可直接访问的相对路径**（如 `/profile/upload/2026/10/04/xxx.png`）。
 *
 * ⚠ 走的是经典上传链路 `/common/upload`，**不是**新文件模块的
 * `/file/operate/uploadfile` —— 后者在本环境不可用（MinIO 未运行、
 * 本地路径配置与实际目录不一致，且它是分片上传协议）。
 * 详见 DEV-ENV §6 第 20 条。
 *
 * 这里不手工设置 Content-Type：交给浏览器/axios 依据 FormData 自动生成
 * 带 boundary 的 multipart 头，手写 'multipart/form-data' 反而会缺 boundary。
 */
export function uploadSignatureFile(dataURL, fileName) {
  const blob = dataURLtoBlob(dataURL)
  const fd = new FormData()
  fd.append('file', blob, (fileName || 'signature') + '.png')
  return request({
    url: '/common/upload',
    method: 'post',
    data: fd
  })
}
