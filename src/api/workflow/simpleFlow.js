import request from '@/utils/request'

// 简化流程：列表
export function listSimpleFlow(query) {
  return request({
    url: '/workflow/simple-flow/list',
    method: 'get',
    params: query
  })
}

// 简化流程：详情
export function getSimpleFlow(id) {
  return request({
    url: '/workflow/simple-flow/' + id,
    method: 'get'
  })
}

// 保存草稿（id 为空 = 新增）
export function saveSimpleFlowDraft(data) {
  return request({
    url: '/workflow/simple-flow/draft',
    method: 'post',
    data: data
  })
}

// 发布前校验（返回问题清单，不抛异常）
export function validateSimpleFlow(data) {
  return request({
    url: '/workflow/simple-flow/validate',
    method: 'post',
    data: data
  })
}

// 编译预览（只编译不部署，返回 BPMN XML）
export function previewSimpleFlow(id) {
  return request({
    url: '/workflow/simple-flow/preview/' + id,
    method: 'get'
  })
}

// 发布
export function publishSimpleFlow(data) {
  return request({
    url: '/workflow/simple-flow/publish',
    method: 'post',
    data: data
  })
}

// 版本历史
export function historySimpleFlow(defKey) {
  return request({
    url: '/workflow/simple-flow/history/' + defKey,
    method: 'get'
  })
}

// 回滚（以历史版本重新发布）
export function rollbackSimpleFlow(data) {
  return request({
    url: '/workflow/simple-flow/rollback',
    method: 'post',
    data: data
  })
}

// 删除
export function delSimpleFlow(id) {
  return request({
    url: '/workflow/simple-flow/' + id,
    method: 'delete'
  })
}

// 2.0（B1 §4.1）：按模板取用或创建流程草稿（已绑定则返回原草稿，幂等）
export function getOrCreateByTemplate(templateId) {
  return request({
    url: '/workflow/simple-flow/by-template/' + templateId,
    method: 'get'
  })
}