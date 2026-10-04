<template>
  <!--
    只读计算控件（PRD 9.3 / AC-38）—— 内置公式，值由别的字段算出来，用户不能填。

    本期两个公式（PRD 244 行「计算公式本期只做两个内置计算」）：
      · dateDiff    日期区间 → 天数（结束 − 开始）
      · amountUpper 金额小写 → 中文大写

    为什么需要 Parser 的 provide：一个"计算字段"必须能读到兄弟字段的值，
    而渲染器是逐字段独立渲染的。Parser 注入了一个取值函数（见其 provide 注释），
    这里在 computed 里调用它 → 兄弟字段变化会自动重算。

    计算结果是**写回表单模型**的（$emit('input')），不是只显示：
    否则打印件、流程条件、详情页都看不到这个值（AC-38 要的是"时长（天）"能被打出来）。
  -->
  <div class="design-calc">
    <span v-if="hintText" class="dc-value is-empty">{{ hintText }}</span>
    <span v-else class="dc-value">{{ displayValue }}</span>
    <span v-if="warnText" class="dc-warn">{{ warnText }}</span>
  </div>
</template>

<script>
import { toChineseUpper } from '@/utils/money'
import { daysBetween } from '@/utils/dateRange'

export default {
  name: 'DesignCalc',
  inject: {
    // Parser 注入的"取当前表单模型"函数；脱离 Parser 渲染（如设计器画布）时为 undefined
    oaFormModel: { default: null }
  },
  props: {
    /** 表单模型里的计算值 */
    value: { type: [Number, String], default: null },
    /** dateDiff | amountUpper */
    formula: { type: String, default: 'dateDiff' },
    /** dateDiff：开始日期字段名；amountUpper：被换算的金额字段名 */
    fromField: { type: String, default: '' },
    /** dateDiff：结束日期字段名 */
    toField: { type: String, default: '' },
    /** 结果单位后缀（dateDiff 默认「天」） */
    unit: { type: String, default: '天' },
    /** 源字段还没填时的提示 */
    placeholder: { type: String, default: '' }
  },
  computed: {
    model() {
      return typeof this.oaFormModel === 'function' ? this.oaFormModel() || {} : {}
    },
    /** 读被引用的字段值：在这里读、在 computed 里读，才建立得起响应式依赖 */
    source() {
      return {
        from: this.fromField ? this.model[this.fromField] : null,
        to: this.toField ? this.model[this.toField] : null
      }
    },
    /** 计算结果；算不出来返回 null（**绝不返回 0 冒充结果**） */
    result() {
      if (this.formula === 'amountUpper') {
        const n = this.source.from
        if (n === null || n === undefined || n === '') {
          return null
        }
        const text = toChineseUpper(n)
        return text || null
      }
      // 默认：日期区间 → 天数（结束 − 开始）；算法与边界见 utils/dateRange.js
      return daysBetween(this.source.from, this.source.to)
    },
    /** 结束早于开始：负数天数没有业务含义，判为"算不出来"并提示 */
    reversed() {
      return this.formula !== 'amountUpper' && this.result !== null && this.result < 0
    },
    hintText() {
      if (this.formula === 'amountUpper') {
        return this.source.from === null || this.source.from === undefined || this.source.from === ''
          ? this.placeholder || '请先填写金额'
          : ''
      }
      const missing = []
      if (!this.fromField || this.source.from === null || this.source.from === undefined || this.source.from === '') {
        missing.push('开始日期')
      }
      if (!this.toField || this.source.to === null || this.source.to === undefined || this.source.to === '') {
        missing.push('结束日期')
      }
      if (missing.length) {
        return this.placeholder || `请先选择${missing.join('与')}`
      }
      if (this.reversed) {
        return '结束日期早于开始日期'
      }
      return ''
    },
    displayValue() {
      if (this.result === null) {
        return ''
      }
      if (this.formula === 'amountUpper') {
        return String(this.result)
      }
      return `${this.result} ${this.unit || '天'}`.trim()
    },
    warnText() {
      return this.reversed ? '请检查起止日期' : ''
    }
  },
  watch: {
    /** 算出新结果就写回模型；只有真正变化时才 emit，避免自我循环 */
    result: {
      immediate: true,
      handler(val) {
        const next = this.reversed ? null : val
        const cur = this.value === '' || this.value === undefined ? null : this.value
        if (String(next) !== String(cur)) {
          this.$emit('input', next)
        }
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.design-calc {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px dashed var(--oa-color-hairline-strong, #dcdfe6);
  border-radius: var(--oa-radius-sm, 4px);
  background: var(--oa-color-canvas, #fafafa);
  font: var(--oa-font-body-sm, 13px/20px system-ui);
  color: var(--oa-color-ink, #303133);
}
.dc-value.is-empty {
  color: var(--oa-color-ink-disabled, #c0c4cc);
}
.dc-warn {
  color: var(--oa-color-warning, #e6a23c);
  font-size: 12px;
}
</style>
