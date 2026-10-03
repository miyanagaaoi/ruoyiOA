/**
 * 生成「基础测试表单」的动态表单 content JSON。
 *
 * 结构与系统可视化设计器（系统工具 -> 表单构建，src/views/tool/build/index.vue）
 * 保存时完全一致：{ ...formConf, fields: [...] }。
 *
 * 每个字段包含：
 *   __config__  : 设计器配置（tag/label/span/layout/required/regList + formId + renderKey）
 *   __slot__    : 选项类组件的子元素配置（el-select / el-radio-group / el-checkbox-group 需要）
 *   组件属性     : 直接透传到 Element 组件的 attrs
 *   __vModel__  : 表单模型字段名（运行时 Parser 用它取值/校验）
 *
 * 注意：系统强制要求表单中必须存在 __vModel__ === 'title' 的字段，
 * 它作为待办/已办/详情页的展示字段（见 tool/build/index.vue handleForm 校验）。
 */
const fs = require('fs');

const OUT = process.argv[2] || 'H:/dsh/ruoyiOA/tools/basic-test-form-content.json';

const BASE_TS = Date.now();
let seq = 100;

// 生成 __config__，formId/renderKey 与设计器 createIdAndKey() 的规则保持一致
function config({ label, tag, tagIcon, document, required = false, defaultValue }) {
  seq += 1;
  const formId = seq;
  const c = {
    label,
    labelWidth: null,
    showLabel: true,
    changeTag: true,
    tag,
    tagIcon,
    required,
    layout: 'colFormItem',
    span: 24,
    document,
    regList: [],
    formId,
    renderKey: `${formId}${BASE_TS}${seq}`,
  };
  if (defaultValue !== undefined) c.defaultValue = defaultValue;
  return c;
}

const opts = (pairs) => pairs.map(([label, value]) => ({ label, value }));

// ---------------- 表单级配置（与设计器 formConf 一致） ----------------
const formConf = {
  formRef: 'elForm',
  formModel: 'formData',
  size: 'medium',
  labelPosition: 'right',
  labelWidth: 128,
  formRules: 'rules',
  gutter: 15,
  disabled: false,
  span: 24,
  formBtns: true,
};

// ---------------- 字段定义 ----------------
const fields = [
  // 1. 单行文本 —— 必须是 title，系统用它做待办/已办展示字段
  {
    __config__: config({
      label: '申请标题',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: true,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '请输入申请标题',
    tooltip: '',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 100,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'title',
  },

  // 2. 下拉选择
  {
    __config__: config({
      label: '申请类型',
      tag: 'el-select',
      tagIcon: 'select',
      document: 'https://element.eleme.cn/#/zh-CN/component/select',
      required: true,
    }),
    __slot__: {
      options: opts([
        ['请假申请', 'leave'],
        ['费用报销', 'expense'],
        ['加班申请', 'overtime'],
        ['出差申请', 'travel'],
        ['其他', 'other'],
      ]),
    },
    placeholder: '请选择申请类型',
    tooltip: '',
    style: { width: '100%' },
    clearable: true,
    disabled: false,
    filterable: false,
    multiple: false,
    __vModel__: 'applyType',
  },

  // 3. 单选框组
  {
    __config__: config({
      label: '紧急程度',
      tag: 'el-radio-group',
      tagIcon: 'radio',
      document: 'https://element.eleme.cn/#/zh-CN/component/radio',
      required: true,
    }),
    __slot__: {
      options: opts([
        ['普通', 'normal'],
        ['紧急', 'urgent'],
        ['特急', 'critical'],
      ]),
    },
    tooltip: '',
    style: {},
    size: 'medium',
    disabled: false,
    __vModel__: 'urgency',
  },

  // 4. 多选框组
  {
    __config__: config({
      label: '通知方式',
      tag: 'el-checkbox-group',
      tagIcon: 'checkbox',
      document: 'https://element.eleme.cn/#/zh-CN/component/checkbox',
      required: false,
      defaultValue: [],
    }),
    __slot__: {
      options: opts([
        ['站内通知', 'inner'],
        ['短信', 'sms'],
        ['邮件', 'email'],
      ]),
    },
    tooltip: '',
    style: {},
    size: 'medium',
    min: null,
    max: null,
    disabled: false,
    __vModel__: 'notifyWays',
  },

  // 5. 计数器（数字）
  {
    __config__: config({
      label: '申请金额（元）',
      tag: 'el-input-number',
      tagIcon: 'number',
      document: 'https://element.eleme.cn/#/zh-CN/component/input-number',
      required: false,
    }),
    placeholder: '请输入申请金额',
    tooltip: '',
    min: 0,
    max: 1000000,
    step: 100,
    'step-strictly': false,
    precision: 2,
    'controls-position': '',
    disabled: false,
    __vModel__: 'amount',
  },

  // 6. 日期选择
  {
    __config__: config({
      label: '开始日期',
      tag: 'el-date-picker',
      tagIcon: 'date',
      document: 'https://element.eleme.cn/#/zh-CN/component/date-picker',
      required: true,
    }),
    placeholder: '请选择开始日期',
    tooltip: '',
    type: 'date',
    style: { width: '100%' },
    disabled: false,
    clearable: true,
    format: 'yyyy-MM-dd',
    'value-format': 'yyyy-MM-dd',
    readonly: false,
    __vModel__: 'startDate',
  },

  // 7. 日期选择
  {
    __config__: config({
      label: '结束日期',
      tag: 'el-date-picker',
      tagIcon: 'date',
      document: 'https://element.eleme.cn/#/zh-CN/component/date-picker',
      required: false,
    }),
    placeholder: '请选择结束日期',
    tooltip: '',
    type: 'date',
    style: { width: '100%' },
    disabled: false,
    clearable: true,
    format: 'yyyy-MM-dd',
    'value-format': 'yyyy-MM-dd',
    readonly: false,
    __vModel__: 'endDate',
  },

  // 8. 选人（OA 自定义组件，运行时可用）
  {
    __config__: config({
      label: '指定处理人',
      tag: 'design-user-select',
      tagIcon: 'icon-user',
      document: '',
      required: false,
    }),
    __slot__: { options: [] },
    placeholder: '请选择处理人',
    tooltip: '',
    style: { width: '100%' },
    multiple: true,
    disabled: false,
    filterable: false,
    initUser: 'none',
    __vModel__: 'handler',
  },

  // 9. 开关
  {
    __config__: config({
      label: '需要盖章',
      tag: 'el-switch',
      tagIcon: 'switch',
      document: 'https://element.eleme.cn/#/zh-CN/component/switch',
      required: false,
      defaultValue: false,
    }),
    tooltip: '',
    style: {},
    disabled: false,
    'active-text': '是',
    'inactive-text': '否',
    'active-color': null,
    'inactive-color': null,
    'active-value': true,
    'inactive-value': false,
    __vModel__: 'needSeal',
  },

  // 10. 多行文本
  {
    __config__: config({
      label: '申请事由',
      tag: 'el-input',
      tagIcon: 'textarea',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: true,
    }),
    type: 'textarea',
    placeholder: '请输入申请事由',
    tooltip: '',
    autosize: { minRows: 4, maxRows: 6 },
    style: { width: '100%' },
    maxlength: 500,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'reason',
  },
];

const content = { ...formConf, fields };

fs.writeFileSync(OUT, JSON.stringify(content), 'utf8');
console.log('written: ' + OUT);
console.log('fields: ' + fields.length);
console.log('vModels: ' + fields.map((f) => f.__vModel__).join(', '));
