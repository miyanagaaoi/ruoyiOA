/**
 * 模板卡片图标预设集（OA 2.0 · B1 §5.3，REQ-FORM-004 / AC-45）
 *
 * 口径（delta spec `workflow/form-definition`）：
 *   - 页面 SHALL 提供**预设图标集**（不少于 40 个），MUST NOT 让用户手工输入图标标识；
 *   - 新建模板时 MUST 默认选中图标集中的**第一个**；
 *   - 所选图标 SHALL 用于**发起审批列表中的模板卡片**展示。
 *
 * 实现选择：取 Element UI 图标集的一个稳定子集（design 遗留问题 3 的定稿口径）。
 * 为什么不用 `components/IconSelect`（svg-icon 选择器）：那套图标是项目 svg 资源，
 * 数量不足 40 且卡片侧要配 `<svg-icon>` 渲染；这里用 `el-icon-*` 类名，
 * 卡片上 `<i :class="icon">` 即可，前后端口径一致、也便于静态审计核对。
 *
 * ⚠️ 前端**不自己拼**图标标识，只从本清单里选；后端列 `t_template.icon` 存这个字符串。
 */

export const TEMPLATE_ICONS = [
  { value: 'el-icon-document', label: '单据' },
  { value: 'el-icon-document-add', label: '新增单据' },
  { value: 'el-icon-document-copy', label: '单据副本' },
  { value: 'el-icon-document-checked', label: '已核单据' },
  { value: 'el-icon-document-delete', label: '作废单据' },
  { value: 'el-icon-document-remove', label: '移除单据' },
  { value: 'el-icon-tickets', label: '工单' },
  { value: 'el-icon-notebook-1', label: '台账' },
  { value: 'el-icon-notebook-2', label: '台账簿' },
  { value: 'el-icon-edit', label: '编辑' },
  { value: 'el-icon-edit-outline', label: '拟稿' },
  { value: 'el-icon-s-order', label: '订单' },
  { value: 'el-icon-s-check', label: '审核' },
  { value: 'el-icon-s-claim', label: '索赔' },
  { value: 'el-icon-s-finance', label: '财务' },
  { value: 'el-icon-s-goods', label: '货物' },
  { value: 'el-icon-s-management', label: '管理' },
  { value: 'el-icon-s-platform', label: '平台' },
  { value: 'el-icon-s-home', label: '主页' },
  { value: 'el-icon-s-shop', label: '门店' },
  { value: 'el-icon-s-custom', label: '客户' },
  { value: 'el-icon-s-copilot', label: '协作' },
  { value: 'el-icon-s-data', label: '数据' },
  { value: 'el-icon-s-marketing', label: '营销' },
  { value: 'el-icon-s-opportunity', label: '机会' },
  { value: 'el-icon-s-promotion', label: '推广' },
  { value: 'el-icon-s-flag', label: '标记' },
  { value: 'el-icon-s-help', label: '帮助' },
  { value: 'el-icon-s-tools', label: '工具' },
  { value: 'el-icon-s-grid', label: '网格' },
  { value: 'el-icon-s-fold', label: '收起' },
  { value: 'el-icon-s-unfold', label: '展开' },
  { value: 'el-icon-menu', label: '菜单' },
  { value: 'el-icon-files', label: '档案' },
  { value: 'el-icon-folder', label: '文件夹' },
  { value: 'el-icon-folder-opened', label: '打开文件夹' },
  { value: 'el-icon-printer', label: '打印' },
  { value: 'el-icon-message', label: '消息' },
  { value: 'el-icon-bell', label: '提醒' },
  { value: 'el-icon-star-off', label: '关注' },
  { value: 'el-icon-trophy', label: '荣誉' },
  { value: 'el-icon-medal', label: '奖章' },
  { value: 'el-icon-suitcase', label: '公文包' },
  { value: 'el-icon-box', label: '物资' },
  { value: 'el-icon-money', label: '资金' },
  { value: 'el-icon-shopping-cart-full', label: '采购' },
  { value: 'el-icon-office-building', label: '组织' }
]

/** 图标集里的第一个：新建模板时的默认值（delta spec：MUST 默认选中第一个） */
export function defaultTemplateIcon() {
  return TEMPLATE_ICONS[0].value
}

/** 校验一个图标标识是否在预设集内（避免自由文本混进来） */
export function isPresetIcon(icon) {
  return TEMPLATE_ICONS.some((item) => item.value === icon)
}
