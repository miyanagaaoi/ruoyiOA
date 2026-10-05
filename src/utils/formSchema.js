/**
 * 动态表单 schema 解析（form-generator 结构）
 *
 * 设计器需要"从表单直接提取字段"，而不是让管理员手敲字段名。
 * 表单内容形如：{ formRef, formModel, ..., fields: [ { __config__: {...}, __vModel__ } ] }
 * 行容器（layout=rowFormItem）的子控件在 __config__.children 里，需要递归。
 *
 * 写法：**CommonJS**（`require` / `module.exports`）—— 与 `printLayout.js` 同口径，
 * 这样纯逻辑能被 `tests/*.test.js` 用 Node 直接 require 跑，不必引入 jest。
 *
 * @author 二开
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
function extractFormFields(content) {
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
function conditionableFields(fields) {
  return (fields || []).filter(f => f.conditionable).map(f => {
    return Object.assign({}, f, { disabled: !f.required })
  })
}

/** 必填字段的 __vModel__ 列表 */
function requiredFieldNames(fields) {
  return (fields || []).filter(f => f.required).map(f => f.vModel)
}

/** 多选字段的 __vModel__ 列表 */
function multiFieldNames(fields) {
  return (fields || []).filter(f => f.multi).map(f => f.vModel)
}

/**
 * 提取"字段名 → 选项清单"，用于把条件里的**值**也显示成人话。
 *
 * 表单里单/多选/下拉控件的选项形如 `__slot__.options = [{label:'经营', value:1}]`；
 * 而提交落库写的是 **label**（见 `DynamicFormDataImpl`），所以显示时优先回显 label。
 *
 * @param {string|object} content 表单 JSON
 * @returns {Object} { [vModel]: [{label, value}] }
 */
function extractFieldOptions(content) {
  const out = {}
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
      if (cfg.children && cfg.children.length) {
        walk(cfg.children)
        return
      }
      const vModel = f.__vModel__
      const options = f.__slot__ && f.__slot__.options
      if (!vModel || !options || !options.length) {
        return
      }
      out[vModel] = options
        .filter(o => o && o.label !== undefined)
        .map(o => ({ label: o.label, value: o.value }))
    })
  }
  walk(schema.fields)
  return out
}

module.exports = {
  MULTI_TAGS: MULTI_TAGS,
  NON_CONDITION_TAGS: NON_CONDITION_TAGS,
  extractFormFields: extractFormFields,
  conditionableFields: conditionableFields,
  requiredFieldNames: requiredFieldNames,
  multiFieldNames: multiFieldNames,
  extractFieldOptions: extractFieldOptions
}
