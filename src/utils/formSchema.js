/**
 * 动态表单 schema 解析（form-generator 结构）
 *
 * 设计器需要"从表单直接提取字段"，而不是让管理员手敲字段名。
 * 表单内容形如：{ formRef, formModel, ..., fields: [ { __config__: {...}, __vModel__ } ] }
 * 行容器（layout=rowFormItem）的子控件在 __config__.children 里，需要递归。
 */

/** 多选控件的 tag */
const MULTI_TAGS = ['el-checkbox-group']

/** 不能作为流程条件的控件（无值 / 值不可比较） */
const NON_CONDITION_TAGS = [
  'el-button',
  'design-section',
  'design-text',
  'design-signature',
  // 关联审批（2.0 B1 §7）：值是"单据ID数组"，不是可比较的标量，
  // 拿来当流程条件只会写出永远不成立的分支
  'design-related-approval',
  'SerialNo' // 流水号：生成后才可搜索，设计期无值
]

/**
 * 从表单 content 中提取字段清单
 *
 * @param {string|object} content 表单 JSON（t_template_dynamic_form.content）
 * @returns {Array<{vModel:string,label:string,tag:string,required:boolean,multi:boolean,conditionable:boolean}>}
 */
export function extractFormFields(content) {
  const out = []
  let schema = content
  if (typeof content === 'string') {
    try {
      schema = JSON.parse(content)
    } catch (e) {
      return out
    }
  }
  if (!schema || !schema.fields) {
    return out
  }

  const walk = arr => {
    (arr || []).forEach(f => {
      const cfg = f.__config__ || {}
      // 行容器：先递归子控件
      if (cfg.children && cfg.children.length) {
        walk(cfg.children)
        return
      }
      const tag = cfg.tag
      const vModel = f.__vModel__
      if (!vModel || !tag) {
        return
      }
      out.push({
        vModel: vModel,
        label: cfg.label || vModel,
        tag: tag,
        required: cfg.required === true,
        multi: MULTI_TAGS.indexOf(tag) >= 0,
        conditionable: NON_CONDITION_TAGS.indexOf(tag) < 0
      })
    })
  }
  walk(schema.fields)
  return out
}

/** 可作为条件的字段（且按 PRD 6.3.1：必须已设为必填） */
export function conditionableFields(fields) {
  return (fields || []).filter(f => f.conditionable).map(f => {
    return Object.assign({}, f, { disabled: !f.required })
  })
}

/** 必填字段的 __vModel__ 列表 */
export function requiredFieldNames(fields) {
  return (fields || []).filter(f => f.required).map(f => f.vModel)
}

/** 多选字段的 __vModel__ 列表 */
export function multiFieldNames(fields) {
  return (fields || []).filter(f => f.multi).map(f => f.vModel)
}
