/**
 * 生成「合同审批（含用印）」动态表单 content JSON。
 *
 * 依据（**不要凭记忆改字段名**）：
 *   · `doc/PRD-合同审批二开.md` §4.2 电子填写页字段与分组（原型 `doc/参考文档/合同审批表单填写页面.jpg`）
 *   · `doc/PRD-合同审批二开.md` §7.8 纸质栏目 ↔ 系统字段映射（**验收依据**，字段名以它为准）
 *   · `doc/PRD-合同审批二开.md` §9.3 表单 Schema 扩展（design-amount / design-calc / design-section / design-text）
 *
 * 结构与系统可视化设计器（系统工具 → 表单构建）保存出来的完全一致：
 *   { ...formConf, fields: [...] }
 * 每个字段：__config__（设计器配置）/ __slot__（选项）/ 组件属性（直接透传）/ __vModel__（表单模型字段名）
 *
 * 两条硬约束（写错了会被运行时挡下）：
 *   1. **必须有 __vModel__ === 'title'** —— 待办/已办/详情页的展示字段
 *      （`src/views/tool/build/index.vue:487` 的 handleForm 校验）；
 *   2. **合同类型必须是单选组 `field101`（经营/经济/人力/行政）**，且 **必填** ——
 *      流程 `testCondition` 的排他分支条件就是 `field101 = 经营 / 经济`（§6.3.1 规则①：
 *      条件字段必须是"表单中存在且必填"的字段，否则 V-8 发布校验会拦）。
 *      ⚠ 这里**故意沿用 `field101` 而不是 `contractType`**：改 vModel 会让现有流程条件指向不存在的字段。
 *
 * 「其他会审部门」（`jointDepts`，复选框组）是**并行会审**的集合来源（§6.3 并行分支）：
 * 集合变量哪怕为空也必须写进流程变量，所以这个字段给 `defaultValue: []`，绝不能不出现。
 *
 * 用法：
 *   node tools/build-contract-approval-form.js [输出路径]
 * 默认输出：tools/contract-approval-form-content.json
 *
 * @author 二开
 */
'use strict';

const fs = require('fs');
const path = require('path');

const OUT = process.argv[2] || path.join(__dirname, 'contract-approval-form-content.json');

/** renderKey 用时间戳 + formId 拼，保证每次生成都唯一（设计器要求 key 唯一） */
const BASE_TS = Date.now();
let seq = 100;

/**
 * 生成字段的 __config__。
 * formId / renderKey 的形态与设计器 `createIdAndKey()` 一致（101、102… + 时间戳后缀）。
 */
function config({ label, tag, tagIcon, document, required = false, defaultValue, showLabel = true }) {
  seq += 1;
  const formId = seq;
  const c = {
    label,
    labelWidth: null,
    showLabel,
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

/* ---------------- 表单级配置（与既有「合同类审批单」同口径：labelWidth 110 / right） ---------------- */
const formConf = {
  formRef: 'elForm',
  formModel: 'formData',
  size: 'medium',
  labelPosition: 'right',
  labelWidth: 110,
  formRules: 'rules',
  gutter: 15,
  disabled: false,
  span: 24,
  formBtns: true,
};

/** 分组标题（纯排版：**没有 __vModel__** → 不进表单值、不可作流程条件、不进打印基本信息表） */
const section = (title, desc) => ({
  __config__: config({
    label: '分组标题',
    tag: 'design-section',
    tagIcon: 'component',
    showLabel: false,
  }),
  title,
  desc: desc || '',
  showLine: true,
  icon: '',
  style: { width: '100%' },
});

/** 说明文字（纯排版） */
const note = (content, align) => ({
  __config__: config({
    label: '说明文字',
    tag: 'design-text',
    tagIcon: 'documentation',
    showLabel: false,
  }),
  content,
  align: align || 'left',
  icon: '',
  style: { width: '100%' },
});

/* ---------------- 字段定义 ---------------- */
const fields = [
  /* ===== §4.2「申请详情」 ===== */
  section('申请详情', '用于合同签订申请；带 * 的字段为必填'),

  // 1. 合同名称 —— **必须是 title**（系统用它做待办/已办/详情页的展示字段）
  {
    __config__: config({
      label: '合同名称',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: true,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '请输入合同名称',
    tooltip: '打印件「合同全称」一栏取本字段（PRD 7.8 第 7 项）',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 200,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'title',
  },

  // 2. 合同类别 —— 原型图里重复的第二个「合同类型」，落地按 PRD 4.2 注释改为「合同类别」
  {
    __config__: config({
      label: '合同类别',
      tag: 'el-select',
      tagIcon: 'select',
      document: 'https://element.eleme.cn/#/zh-CN/component/select',
      required: false,
    }),
    __slot__: {
      options: opts([
        ['采购合同', 'purchase'],
        ['销售合同', 'sale'],
        ['服务合同', 'service'],
        ['工程施工合同', 'construction'],
        ['租赁合同', 'lease'],
        ['其他', 'other'],
      ]),
    },
    placeholder: '请选择合同类别',
    tooltip: '自由分类，**不参与**流程条件（流程按下面「合同类型」路由）',
    style: { width: '100%' },
    clearable: true,
    disabled: false,
    filterable: false,
    multiple: false,
    __vModel__: 'contractCategory',
  },

  // 3. 合同类型 —— 流程排他分支的条件字段（⚠ 沿用 field101，且必须 required）
  {
    __config__: config({
      label: '合同类型',
      tag: 'el-radio-group',
      tagIcon: 'radio',
      document: 'https://element.eleme.cn/#/zh-CN/component/radio',
      required: true,
    }),
    __slot__: {
      options: opts([
        ['经营', 1],
        ['经济', 2],
        ['人力', 3],
        ['行政', 4],
      ]),
    },
    tooltip: '流程按本字段走排他分支（经营 / 经济 / 人力 / 行政）；提交时后端会把它翻译成中文 label 写进流程变量',
    style: {},
    size: 'small',
    disabled: false,
    optionType: 'button',
    border: false,
    __vModel__: 'field101',
  },

  // 4. 关联审批（2.0 B1 控件）—— 值 = 被关联单据的业务ID数组，**不可作条件**
  {
    __config__: config({
      label: '关联审批',
      tag: 'design-related-approval',
      tagIcon: 'link',
      defaultValue: [],
    }),
    allowTemplates: [],
    multiple: true,
    placeholder: '搜索并选择要关联的单据',
    tooltip: '可关联的单据范围由「候选模板 + 同一分组 + 本人发起」共同决定',
    style: { width: '100%' },
    __vModel__: 'field103',
  },

  // 5. 合同描述（§4.2 必填多行文本）
  {
    __config__: config({
      label: '合同描述',
      tag: 'el-input',
      tagIcon: 'textarea',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: true,
    }),
    type: 'textarea',
    placeholder: '请输入合同内容 / 主要条款摘要',
    tooltip: '打印件「相关说明」一栏取本字段（PRD 7.8 第 14 项）',
    autosize: { minRows: 4, maxRows: 8 },
    style: { width: '100%' },
    maxlength: 1000,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'description',
  },

  // 6. 合同金额（金额控件：千分位 / 小数位 / 自动大写）
  {
    __config__: config({
      label: '合同金额',
      tag: 'design-amount',
      tagIcon: 'money',
      defaultValue: null,
      required: true,
    }),
    decimals: 2,
    currency: 'CNY',
    showUpper: true,
    min: 0,
    max: null,
    placeholder: '请输入合同金额',
    tooltip: '打印件「合同金额」= 数字 + 中文大写（PRD 7.8 第 10 项）',
    style: { width: '100%' },
    __vModel__: 'amount',
  },

  // 7. 合同编号（编号控件：运行时选规则生成；编号规则「合同编号配置」已存在）
  {
    __config__: config({
      label: '合同编号',
      tag: 'design-serial-no',
      tagIcon: '',
      required: false,
    }),
    __slot__: { options: [] },
    placeholder: '未填可点「生成编号」按编号规则生成',
    tooltip: '默认由编号规则生成，允许覆盖（PRD 7.8 第 4 项）',
    style: { width: '100%' },
    multiple: false,
    disabled: false,
    filterable: false,
    __vModel__: 'contractNo',
  },

  // 8. 合同扫描件编号（纸质栏目第 5 项，无值该行不打印）
  {
    __config__: config({
      label: '合同扫描件编号',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: false,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '请输入扫描件编号',
    tooltip: '纸质单第 5 栏；无值时打印件不输出该行（AC-16）',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 100,
    'show-word-limit': false,
    readonly: false,
    disabled: false,
    __vModel__: 'scanNo',
  },

  /* ===== §4.2「合同有效期」（分组标题，带折叠） ===== */
  section('合同有效期', '起始日与到期日决定履约期限，时长按自然日自动计算'),

  {
    __config__: config({
      label: '开始时间',
      tag: 'el-date-picker',
      tagIcon: 'date',
      document: 'https://element.eleme.cn/#/zh-CN/component/date-picker',
      required: true,
    }),
    placeholder: '请选择开始时间',
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

  {
    __config__: config({
      label: '结束时间',
      tag: 'el-date-picker',
      tagIcon: 'date',
      document: 'https://element.eleme.cn/#/zh-CN/component/date-picker',
      required: true,
    }),
    placeholder: '请选择结束时间',
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

  // 时长（天）：只读计算控件（值会写回表单模型，所以打印件/流程条件都取得到）
  {
    __config__: config({
      label: '时长（天）',
      tag: 'design-calc',
      tagIcon: 'time',
      defaultValue: null,
      required: false,
    }),
    formula: 'dateDiff',
    fromField: 'startDate',
    toField: 'endDate',
    unit: '天',
    placeholder: '未填（请先选择开始 / 结束时间）',
    style: { width: '100%' },
    __vModel__: 'durationDays',
  },

  // 合同签订时间（纸质栏目第 12 项）
  {
    __config__: config({
      label: '合同签订时间',
      tag: 'el-date-picker',
      tagIcon: 'date',
      document: 'https://element.eleme.cn/#/zh-CN/component/date-picker',
      required: false,
    }),
    placeholder: '请选择合同签订时间',
    tooltip: '打印件「合同签订时间」一栏取本字段（PRD 7.8 第 12 项）',
    type: 'date',
    style: { width: '100%' },
    disabled: false,
    clearable: true,
    format: 'yyyy-MM-dd',
    'value-format': 'yyyy-MM-dd',
    readonly: false,
    __vModel__: 'signDate',
  },

  /* ===== §4.2「我方信息」 ===== */
  section('我方信息', '签约主体与承办部门'),

  // 签订公司：原型图建议改为"组织选择"，本项目已有 design-dept-select 控件
  {
    __config__: config({
      label: '签订公司',
      tag: 'design-dept-select',
      tagIcon: 'icon-user',
      defaultValue: undefined,
      required: true,
    }),
    __slot__: { options: [] },
    placeholder: '请选择签订公司',
    tooltip: '打印件「合同签订主体-甲方」取本字段（PRD 7.8 第 8 项）',
    style: { width: '100%' },
    multiple: false,
    disabled: false,
    filterable: false,
    initDept: 'dept',
    __vModel__: 'ourCompany',
  },

  // 责任部门（纸质栏目第 2 项；未填打印时回退取发起人部门）
  {
    __config__: config({
      label: '责任部门',
      tag: 'design-dept-select',
      tagIcon: 'icon-user',
      defaultValue: undefined,
      required: false,
    }),
    __slot__: { options: [] },
    placeholder: '请选择责任部门（不填则取发起人部门）',
    tooltip: '打印件「责任部门」一栏；留空时打印回退取发起人部门（PRD 7.8 第 2 项）',
    style: { width: '100%' },
    multiple: false,
    disabled: false,
    filterable: false,
    initDept: 'none',
    __vModel__: 'respDept',
  },

  /* ===== §4.2「对方信息」 ===== */
  section('对方信息', '合同相对方的单位名称与联系方式'),

  {
    __config__: config({
      label: '对方单位名称',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: true,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '请输入对方单位全称',
    tooltip: '打印件「合同签订主体-乙方」取本字段（PRD 7.8 第 9 项）',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 200,
    'show-word-limit': false,
    readonly: false,
    disabled: false,
    __vModel__: 'counterpartyName',
  },

  {
    __config__: config({
      label: '对方联系方式',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: false,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '请输入对方联系人 / 手机 / 电话',
    tooltip: '',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 100,
    'show-word-limit': false,
    readonly: false,
    disabled: false,
    __vModel__: 'counterpartyContact',
  },

  /* ===== §4.2 附送材料 / 相关说明 ===== */
  section('附送材料与说明', '纸质单第 6、7 栏'),

  {
    __config__: config({
      label: '附送材料',
      tag: 'el-input',
      tagIcon: 'textarea',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: false,
    }),
    type: 'textarea',
    placeholder: '如：营业执照复印件、资质证明、报价单、对方授权书…（可与附件清单联动）',
    tooltip: '打印件「附送材料」一栏取本字段（PRD 7.8 第 13 项）',
    autosize: { minRows: 3, maxRows: 6 },
    style: { width: '100%' },
    maxlength: 500,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'materials',
  },

  {
    __config__: config({
      label: '相关说明',
      tag: 'el-input',
      tagIcon: 'textarea',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: false,
    }),
    type: 'textarea',
    placeholder: '其他需要说明的情况（如已履行的前置审批、特殊约定）',
    tooltip: '',
    autosize: { minRows: 3, maxRows: 6 },
    style: { width: '100%' },
    maxlength: 500,
    'show-word-limit': true,
    readonly: false,
    disabled: false,
    __vModel__: 'remark',
  },

  /* ===== 其他会审部门（并行会审的集合来源） ===== */
  section('其他会审部门', '勾选后会按部门产生并行会审任务；不勾选则不产生会审'),

  {
    __config__: config({
      label: '其他会审部门',
      tag: 'el-checkbox-group',
      tagIcon: 'checkbox',
      document: 'https://element.eleme.cn/#/zh-CN/component/checkbox',
      required: false,
      defaultValue: [],
    }),
    __slot__: {
      options: opts([
        ['经营', 'business'],
        ['经济', 'economy'],
        ['人力', 'hr'],
        ['行政', 'admin'],
      ]),
    },
    tooltip: '对应纸质单第 9 栏「其他会审部门」（不含法务）；并行会审的集合来源，空数组也必须提交',
    style: {},
    size: 'medium',
    min: null,
    max: null,
    disabled: false,
    __vModel__: 'jointDepts',
  },

  /* ===== 用印（含用印） ===== */
  section('用印信息', '涉密或需用印的合同，由印鉴证照管理部门在审批后用印并登记'),

  {
    __config__: config({
      label: '用印备案编号',
      tag: 'el-input',
      tagIcon: 'input',
      document: 'https://element.eleme.cn/#/zh-CN/component/input',
      required: false,
    }),
    __slot__: { prepend: '', append: '' },
    placeholder: '用印后由印鉴证照管理部门登记',
    tooltip: '用印办理节点填写；无值时不打印该行',
    style: { width: '100%' },
    clearable: true,
    'prefix-icon': '',
    'suffix-icon': '',
    maxlength: 100,
    'show-word-limit': false,
    readonly: false,
    disabled: false,
    __vModel__: 'sealNo',
  },

  note('附件（合同正文扫描件、对方资质等）在提交时由平台附件能力上传；' +
       '用印材料与审批单在同一张单据内，用印办理节点补充上传盖章备案件。'),
];

const content = { ...formConf, fields };

/* ---------------- 自检：写文件前先按前端解析口径验一遍 ---------------- */
const problems = [];
const vModels = fields.filter((f) => f.__vModel__).map((f) => f.__vModel__);

if (vModels.indexOf('title') < 0) {
  problems.push('缺少 __vModel__ === "title"（待办/已办/详情页的展示字段，保存表单时必校验）');
}
if (vModels.indexOf('field101') < 0) {
  problems.push('缺少 field101（流程 testCondition 的排他分支条件字段）');
}
const typeField = fields.find((f) => f.__vModel__ === 'field101');
if (typeField && typeField.__config__.required !== true) {
  problems.push('field101 必须 required=true，否则 §6.3.1 规则① 会让它无法作条件（V-8 拦截）');
}
if (vModels.indexOf('jointDepts') < 0) {
  problems.push('缺少 jointDepts（并行会审的集合来源，集合变量为空也必须存在）');
}
if (vModels.indexOf('amount') < 0) {
  problems.push('缺少 amount（打印「合同金额」+ 大写）');
}
const dupes = vModels.filter((v, i) => vModels.indexOf(v) !== i);
if (dupes.length) {
  problems.push('__vModel__ 重复：' + dupes.join('、'));
}
fields.forEach((f, i) => {
  if (!f.__config__ || !f.__config__.tag) {
    problems.push(`第 ${i + 1} 个字段缺少 __config__.tag`);
  }
});

if (problems.length) {
  console.error('自检未通过，未写出文件：');
  problems.forEach((p) => console.error('  ✗ ' + p));
  process.exit(1);
}

fs.writeFileSync(OUT, JSON.stringify(content), 'utf8');
console.log('written: ' + OUT);
console.log('fields: ' + fields.length + '（含纯排版控件 ' + fields.filter((f) => !f.__vModel__).length + ' 个）');
console.log('vModels: ' + vModels.join(', '));
