/**
 * 分支条件文字的**唯一格式化真源**（纯函数、零依赖、可被 Node 直接单测）。
 *
 * 为什么要有它：同一句话在三个地方显示，此前各写一份 → 口径必然漂移：
 *   1. 画布分支行（FlowTree 经 FlowDesigner#condText）
 *   2. 配置抽屉里的"当前条件读作"（ConditionEditor#sentence）
 *   3. 节点卡片摘要（FlowDesigner#summary 内的条件文本）
 *
 * 显示口径（2026-10-05 定）：**只显示表单字段的 label（中文名），不显示 vModel**。
 * - 表单字段的 label 就是管理员在设计器里填的"字段名称"（如"合同类型"）；
 * - 未关联表单 / 字段取自旧版本表单时，label 表里查不到 → **回退显示 vModel**（总比显示空白强）；
 * - 比较值同时匹配 label 与 value 时按 label 显示（表单选项 `{label:'经营', value:1}` 落库的是 label，
 *   见 `DynamicFormDataImpl#translate...` 的取值口径）。
 *
 * @author 二开
 */

/** 比较符 → 显示符号 */
var OP_TEXT = {
  EQ: '=',
  NE: '≠',
  GT: '>',
  GE: '≥',
  LT: '<',
  LE: '≤',
  CONTAINS: '包含',
  NOT_CONTAINS: '不包含',
  EMPTY: '为空',
  NOT_EMPTY: '不为空'
}

/** 只比"有没有值"的比较符：后面不接值 */
var NO_VALUE_OPS = { EMPTY: 1, NOT_EMPTY: 1 }

/** 有"候选值"的控件：值不该手打，应该让用户从选项里选 */
var ENUM_TAGS = { 'el-radio-group': 1, 'el-select': 1 }

var UNKNOWN_FIELD = '未设置条件'

/** 单条算式的操作符文本（未知 op 原样回显） */
function opText(op) {
  return OP_TEXT[op] || op
}

/** 只有"有值可比"的比较符才需要值控件 */
function needsValue(op) {
  return !NO_VALUE_OPS[op]
}

/**
 * 该条件的值是否应当用**选择器**而不是自由输入框。
 *
 * 判据：① 该字段有候选选项；② 该字段的控件是"单选型"（单选组 / 下拉）。
 * 多选控件（复选组）**不**列入：条件对多选字段不能表达"等于某一个"，V-8 也只认必填字段。
 *
 * @param {string} field 字段 __vModel__
 * @param {Array} fields 表单字段清单（含 tag）
 * @param {Object} optionsMap vModel → [{label,value}]
 */
function pickerOptions(field, fields, optionsMap) {
  if (!field) {
    return []
  }
  var options = (optionsMap && optionsMap[field]) || null
  if (!options || !options.length) {
    return []
  }
  var fieldDef = null
  ;(fields || []).forEach(function (f) {
    if (f && f.vModel === field) {
      fieldDef = f
    }
  })
  if (!fieldDef || !ENUM_TAGS[fieldDef.tag]) {
    return []
  }
  return options
}

/** 该字段是否应当用选择器取值 */
function useValuePicker(field, fields, optionsMap) {
  return pickerOptions(field, fields, optionsMap).length > 0
}

/**
 * 由字段清单建立 `vModel → label` 映射。
 * 字段缺 vModel 时跳过；label 缺失或与 vModel 相同时不登记（让显示自然回退到 vModel）。
 *
 * @param {Array<{vModel:string,label:string}>} fields
 * @returns {Object}
 */
function fieldLabelMap(fields) {
  var map = {}
  ;(fields || []).forEach(function (f) {
    if (!f || !f.vModel) {
      return
    }
    var label = f.label ? String(f.label) : ''
    if (label && label !== f.vModel) {
      map[f.vModel] = label
    }
  })
  return map
}

/** 字段显示名：label 优先，查不到回退 vModel（绝不返回空串） */
function fieldText(vModel, labelMap) {
  if (vModel === undefined || vModel === null || vModel === '') {
    return ''
  }
  var mapped = labelMap ? labelMap[vModel] : null
  return mapped || String(vModel)
}

/**
 * 把一个值渲染成"人读"文本。
 *
 * ⚠ **口径真源（2026-10-05 实测更正）**：流程变量里存的是**选项码值（value）**，不是 label。
 * 证据：合同审批实例的 `ACT_HI_VARINST` 与 `t_workflow_form.val_data` 里
 *   `field101 = 1`（选项 经营=1）、`contractCategory = "purchase"`、`jointDepts = "business"`；
 * 而 `BizFormServiceImpl#convertValueToLabel` 的结果只用于**表单落库与正文书签**，
 * `setFormDataVariable()` 取的是转换前的快照（`commonForm.setValData(sourceValDataJson)`）。
 * 所以：
 *   - **条件里存的值必须是码值**（`1` / `purchase`），由 `ConditionEditor` 从候选项的 value 取；
 *   - 显示层负责把码值翻成中文 label —— 这正是本函数的职责，不要再反过来理解。
 *
 * 历史坑：此处与 `ConditionEditor` 的提示原本都写成"条件存 label"，
 * 导致设计器生成的 `${field101 == '经营'}` 在运行期拿 Integer 1 去比 String，直接抛
 * `Error while evaluating expression`。改口径时两处必须同步。
 *
 * @param {*} raw      条件里存的值
 * @param {string} vModel  值所属字段
 * @param {Object} optionsMap vModel → 选项数组（[{label,value}]）
 */
function valueText(raw, vModel, optionsMap) {
  if (raw === undefined || raw === null || raw === '') {
    return '？'
  }
  var options = (optionsMap && optionsMap[vModel]) || null
  if (options && options.length) {
    for (var i = 0; i < options.length; i++) {
      var o = options[i]
      if (!o) {
        continue
      }
      if (String(o.label) === String(raw)) {
        return String(o.label)
      }
      if (o.value !== undefined && o.value !== null && String(o.value) === String(raw)) {
        return String(o.label)
      }
    }
  }
  return String(raw)
}

/** 单条条件：`字段 符号 [值]`；EMPTY / NOT_EMPTY 不接值 */
function formatRow(row, labelMap, optionsMap) {
  if (!row || !row.field) {
    return ''
  }
  var name = fieldText(row.field, labelMap)
  var op = opText(row.op)
  if (NO_VALUE_OPS[row.op]) {
    return name + ' ' + op
  }
  return name + ' ' + op + ' ' + valueText(row.value, row.field, optionsMap)
}

/** 条件组内为「且」，组之间为「或」 */
function formatGroups(branch, labelMap, optionsMap) {
  var groups = (branch && branch.groups) || []
  var parts = []
  groups.forEach(function (g) {
    var rows = ((g && g.rows) || [])
      .map(function (r) {
        return formatRow(r, labelMap, optionsMap)
      })
      .filter(function (s) {
        return s !== ''
      })
    if (rows.length) {
      parts.push(rows.join(' 且 '))
    }
  })
  return parts.join(' 或 ')
}

/**
 * 条件文字。
 *
 * @param {Object} branch 分支对象（{groups:[{logic:'AND', rows:[{field,op,value}]}]}）
 * @param {Object} [labelMap]   vModel → label（缺省则显示 vModel）
 * @param {Object} [optionsMap] vModel → [{label,value}]（用于把值显示成选项名）
 * @param {Object} [options]    { inline: true } → 不带"当"，用于嵌在别的句子里
 * @returns {string}
 */
function formatConditionText(branch, labelMap, optionsMap, options) {
  var body = formatGroups(branch, labelMap, optionsMap)
  if (!body) {
    return UNKNOWN_FIELD
  }
  return (options && options.inline) ? body : ('当 ' + body + ' 时')
}

module.exports = {
  OP_TEXT: OP_TEXT,
  ENUM_TAGS: ENUM_TAGS,
  fieldLabelMap: fieldLabelMap,
  fieldText: fieldText,
  opText: opText,
  needsValue: needsValue,
  pickerOptions: pickerOptions,
  useValuePicker: useValuePicker,
  valueText: valueText,
  formatRow: formatRow,
  formatGroups: formatGroups,
  formatConditionText: formatConditionText
}
