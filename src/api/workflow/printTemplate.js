import request from '@/utils/request'

/**
 * 打印模板配置（PRD 7.7 / 10.2-B）
 *
 * 权限点：workflow:print:template（查看）、:edit（保存）、:remove（删除）。
 * 后端同名注解在 WorkflowPrintController，改权限口径时两处一起改。
 */

/** 某单据模板下的打印模板列表 */
export function listPrintTemplates(templateId) {
  return request({
    url: '/workflow/print/template/list',
    method: 'get',
    params: { templateId: templateId }
  })
}

/** 新增 / 更新打印模板（有 id 即更新） */
export function savePrintTemplate(data) {
  return request({
    url: '/workflow/print/template',
    method: 'post',
    data: data
  })
}

/** 删除打印模板（逻辑删） */
export function delPrintTemplate(id) {
  return request({
    url: '/workflow/print/template/' + id,
    method: 'delete'
  })
}
