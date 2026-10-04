import request from '@/utils/request'

/**
 * 打印数据聚合（PRD 10.2-B）
 * 一次拿齐 表单 + 节点签批栏 + 附件清单 + 生效的打印模板。
 */
export function getPrintData(businessId, printTplId) {
  return request({
    url: '/workflow/print/data/' + businessId,
    method: 'get',
    params: { printTplId: printTplId }
  })
}

/** 读取生效的打印模板（含系统默认，永远不返回空） */
export function getPrintTemplate(templateId, printTplId) {
  return request({
    url: '/workflow/print/template/' + templateId,
    method: 'get',
    params: { printTplId: printTplId }
  })
}

/**
 * 写打印留痕。
 * 打印人与打印时间由服务端决定，这里只传业务信息与页数。
 */
export function addPrintLog(data) {
  return request({
    url: '/workflow/print/log',
    method: 'post',
    data: data
  })
}

/** 某单据的打印记录 */
export function listPrintLog(businessId) {
  return request({
    url: '/workflow/print/log/list',
    method: 'get',
    params: { businessId: businessId }
  })
}

/**
 * 取文件字节（签名图片等）。
 *
 * ⚠ 两个必须注意的点（都踩过）：
 *   1. `/file/operate/downloadfile` 是 **POST**，不是 GET ——
 *      写成 `<img src="...?fileId=x">` 会直接收到
 *      `Request method 'GET' not supported`。所以这里返回 blob，由调用方转对象 URL；
 *   2. 控制器的入参是 `FileQO`（**没有** `@RequestBody`），所以参数走 query，
 *      不能放在 JSON body 里（放 body 里会得到"参数错误"）。
 */
export function getFileBlob(fileId) {
  return request({
    url: '/file/operate/downloadfile',
    method: 'post',
    params: { fileId: fileId },
    responseType: 'blob'
  })
}

