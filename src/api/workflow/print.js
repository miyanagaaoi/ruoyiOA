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
 * 内置打印版式清单（4 条：key + 展示名称 + 签批栏/附件清单的推荐取值）。
 *
 * 2.0 B2：内置版式由"全局唯一一套"改为**按单据类型选用**（REQ-PRINT-011）。
 * 客户端**不抄**这份清单 —— 抄一份就又是"前后端各一份"的重复（REQ-PRINT-013）。
 */
export function listBuiltinPrintTemplates() {
  return request({
    url: '/workflow/print/builtinTemplates',
    method: 'get'
  })
}

/**
 * 某个内置版式的字段映射（版式常量本身）。
 *
 * 配置页的"填入内置版式"用它；内置版式常量以**后端为唯一真源**（REQ-PRINT-013）。
 * 未登记的 key 由服务端回退 contract 并记 warning（不报错）。
 */
export function getBuiltinFieldMap(builtinKey) {
  return request({
    url: '/workflow/print/defaultFieldMap/' + builtinKey,
    method: 'get'
  })
}

/**
 * 保存单据模板绑定的内置版式键（配置页"选择内置模板"模式）。
 *
 * ⚠ 只改这一列。不走 `PUT /template/template`（那是全量 DTO 回写，
 * 未提交的字段会被置空，且要求的是另一个权限点）。
 */
export function saveBuiltinPrintKey(templateId, builtinKey) {
  return request({
    url: '/workflow/print/builtinKey',
    method: 'post',
    data: { templateId: templateId, builtinKey: builtinKey }
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

