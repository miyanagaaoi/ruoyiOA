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
