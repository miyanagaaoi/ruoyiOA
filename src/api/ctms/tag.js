import request from '@/utils/request'

// ============================================================================
// 合同标签字典（2.0 B3 第 4 组 / 任务 8.3）
// 后端：ruoyi-ctms 的 CtmsTagController（@RequestMapping("/ctms/tag")）
// 权限点真源：`sql/二开-合同台账-菜单.sql`
//   ctms:tag:list（列表 **与详情** —— 菜单里标签管理没有独立的 query 按钮权限，
//                 后端读取详情也用 list，前端不要凭空造一个 ctms:tag:query）
//   ctms:tag:add / ctms:tag:edit / ctms:tag:remove
//
// 三条服务端口径（前端只做提示，判定一律在服务端）：
//   1. 名称全局唯一（t_ctms_tag 上有 uk_tag_name）→ 重名返回「标签名称已存在」；
//   2. 颜色留空时由服务端按「已有标签总数 % 色板长度」选色（色板在服务端，前端不复制一份）；
//   3. 删除前检查引用：被（未停用）合同引用的标签拒绝删除，返回「标签已被合同引用，无法删除」。
//
// ⚠ 自动标签（与合同类型同名的标签、「框架合同」）由服务端在保存合同时
//   `syncAutoTags` 自动附加/替换，**没有任何"同步"接口** —— 前端不得提供手动同步入口
//   （手动同步会破坏规格里「手动添加的标签不得被自动同步移除」的口径）。
// ============================================================================

/**
 * 查询标签列表（分页）。
 *
 * @param {object} query { name 模糊, builtin 精确（'1'/'0'）, pageNum, pageSize }
 */
export function listTag(query) {
  return request({ url: '/ctms/tag/list', method: 'get', params: query })
}

// 标签详情（权限点同样是 ctms:tag:list）
export function getTag(id) {
  return request({ url: '/ctms/tag/' + id, method: 'get' })
}

// 新增标签（名称唯一；颜色/是否内置留空时由服务端兜底；主键由服务端生成）
export function addTag(data) {
  return request({ url: '/ctms/tag', method: 'post', data: data })
}

// 修改标签（名称唯一排除自身；颜色/是否内置留空时保持库内原值）
export function updateTag(data) {
  return request({ url: '/ctms/tag', method: 'put', data: data })
}

// 删除标签（被合同引用时服务端拒绝：'标签已被合同引用，无法删除'）
export function delTag(id) {
  return request({ url: '/ctms/tag/' + id, method: 'delete' })
}

// ---------------------------------------------------------------------------
// 说明（不是接口，是一条交接备注）：
// 合同列表页与合同表单（任务 8.1/8.2，`src/api/ctms/contract.js`）目前自己有一份只读的
// `listTagOptions()`（同样是 GET /ctms/tag/list，只取标签做筛选与多选）。
// 本文件落地后，那份函数可以收成一行转出口（`export { listTag as listTagOptions } from './tag'`）；
// 但 `api/ctms/contract.js` 属于 8.1/8.2 的 inScope，8.3 不改它 —— 谁的 inScope 谁决定何时收敛。
// 两边返回形态一致（{rows:[{id,name,color,builtin,...}]}），收敛不会影响调用方。
// ---------------------------------------------------------------------------
