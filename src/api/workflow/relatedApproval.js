import request from '@/utils/request'

/**
 * 关联审批控件相关接口（2.0 B1 §7.8）
 *
 * 所有过滤与越权判定都在服务端：
 *   · candidates 只返回"候选模板 ∩ 同分组 ∩ 本人发起"的单据；
 *   · detail / byHost / reverse 都要先过单据查看权（无权 403）。
 */

/** 候选单据（拟稿页下拉，远程搜索） */
export function listRelatedApprovalCandidates(params) {
  return request({
    url: '/workflow/related-approval/candidates',
    method: 'get',
    params
  })
}

/** 浮窗只读详情：表单数据 + 审批状态 */
export function getRelatedApprovalDetail(businessId) {
  return request({
    url: '/workflow/related-approval/detail',
    method: 'get',
    params: { businessId }
  })
}

/** 宿主单据已关联的单据（按控件分组，值是事务里的快照） */
export function listRelatedApprovalByHost(businessId) {
  return request({
    url: '/workflow/related-approval/by-host',
    method: 'get',
    params: { businessId }
  })
}

/** 反查：哪些宿主单据关联了这张单据 */
export function listRelatedApprovalReverse(businessId) {
  return request({
    url: '/workflow/related-approval/reverse',
    method: 'get',
    params: { businessId }
  })
}
