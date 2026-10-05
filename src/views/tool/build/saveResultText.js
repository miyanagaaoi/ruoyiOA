/**
 * 动态表单保存结果 → 界面提示文案（纯函数；Node 单测可直接 require）。
 *
 * 为什么需要它（缺陷背景，见 doc/缺陷-流程条件字段与关联表单错位.md §4.5）：
 *   动态表单保存 = 停用旧版本行 + 插一条新行（**换 id**）。后端会把模板
 *   `t_template.form_id` 改指到新版本，但**流程 `t_flow_simple.content.formId` 不会跟随** ——
 *   于是"模板跟着新版走、流程留在旧版"，运行期条件字段找不到（V-8 误报 / 提交报错）。
 *   保存成功那一刻必须把**两件事**都说清楚：
 *     1) 有多少个模板跟着改指了（`affectedTemplates`）；
 *     2) 还有哪些**流程**留在旧版本表单上（`formFlowWarnings`，后端 PRD V-8 反向校验给出）——
 *        否则管理员会以为"表单改了、流程也跟着改了"，等到发起单据才报错。
 *
 * 口径（与后端 TemplateDynamicFormController#edit 的响应字段一一对应）：
 *   - 无告警  → type='success'：'修改成功' / '修改成功：表单已存为新版本，并已把 N 个模板指向新版本'
 *   - 有告警  → type='warning'：成功文案 + '⚠ 另有 M 个流程仍指向旧版本表单（A、B），…请重新保存发布'
 *   - 名字缺失 → 回退 defKey → 回退 flowId（绝不显示空白，否则用户不知道说的是哪条流程）
 *
 * @author 二开（2.0 B1 §7.10 / PRD V-8）
 */
'use strict'

/** 一条流程告警的展示名（name → defKey → flowId，逐级回退） */
function flowLabel(w) {
  if (!w) return '(未知流程)'
  return w.flowName || w.defKey || w.flowId || '(未知流程)'
}

/** 正文字段里的告警项名（便于在一句话里带上"哪个字段会失效"） */
function fieldSuffix(w) {
  var fields = (w && w.fields) || []
  if (!fields.length) return ''
  return '（' + fields.join('、') + '）'
}

/**
 * @param {object} response 后端 PUT /template/dynamic/form 的响应体
 * @returns {{type:'success'|'warning', message:string, warningCount:number, warnings:Array}}
 */
function buildFormSaveMessage(response) {
  var res = response || {}
  var affected = typeof res.affectedTemplates === 'number' ? res.affectedTemplates : 0
  var warnings = Array.isArray(res.formFlowWarnings) ? res.formFlowWarnings : []

  var base = '修改成功'
  if (affected > 0) {
    base = '修改成功：表单已存为新版本，并已把 ' + affected + ' 个模板指向新版本'
  }
  if (!warnings.length) {
    return { type: 'success', message: base, warningCount: 0, warnings: [] }
  }

  var names = warnings.slice(0, 3).map(function (w) {
    return flowLabel(w) + fieldSuffix(w)
  })
  var more = warnings.length > names.length ? ' 等' : ''
  var message = base +
    '；⚠ 另有 ' + warnings.length + ' 个流程仍指向旧版本表单：' + names.join('、') + more +
    ' —— 请到「流程设计（简化版）」重新保存并发布这些流程，否则条件字段会按旧表单校验'
  return { type: 'warning', message: message, warningCount: warnings.length, warnings: warnings }
}

module.exports = {
  buildFormSaveMessage: buildFormSaveMessage,
  flowLabel: flowLabel,
  fieldSuffix: fieldSuffix
}
