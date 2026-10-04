<template>
  <!--
    金额控件（PRD 9.3 / AC-36）—— 千分位、小数位、币种、**自动中文大写**、可设范围。

    两个刻意的设计：
      1. **编辑中显示原样、失焦后才格式化**：边输边插千分位会把光标顶到别处，
         用户没法正常改数字（"1,234" 改成 "1,2345" 这种）。
      2. **值是数字，不是带千分位的字符串**：vaData 里存 number，
         打印/条件/大写都由这一个数字再算一次，避免"存的串和显示不一致"。
         空值存 null —— 空金额与 0 元在单据上是两件事。
  -->
  <div class="design-amount">
    <el-input
      class="da-input"
      :value="display"
      :placeholder="placeholder || '请输入金额'"
      :disabled="disabled"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
    >
      <template slot="prepend">{{ currencySymbol }}</template>
    </el-input>
    <div v-if="showUpper" class="da-upper">
      <span class="da-upper-label">大写</span>
      <span class="da-upper-text" :class="{ 'is-empty': !upperText }">{{ upperText || '—' }}</span>
    </div>
  </div>
</template>

<script>
import { formatAmount, toChineseUpper } from '@/utils/money'

/** 币种符号；表里没有就原样显示币种代码（不猜） */
const CURRENCY_SYMBOL = { CNY: '¥', USD: '$', EUR: '€', HKD: 'HK$', JPY: '¥', GBP: '£' }

export default {
  name: 'DesignAmount',
  props: {
    /** 表单模型里的值：number 或 null */
    value: { type: [Number, String], default: null },
    /** 小数位，默认 2（PRD 9.3） */
    decimals: { type: [Number, String], default: 2 },
    /** 币种，默认 CNY */
    currency: { type: String, default: 'CNY' },
    /** 可设范围（PRD：可设范围） */
    min: { type: [Number, String], default: null },
    max: { type: [Number, String], default: null },
    /** 是否展示中文大写 */
    showUpper: { type: Boolean, default: true },
    placeholder: { type: String, default: '' },
    disabled: { type: Boolean, default: false }
  },
  data() {
    return {
      /** 是否处于编辑态：编辑态显示 raw，失焦后显示格式化结果 */
      editing: false,
      inputText: ''
    }
  },
  computed: {
    decimalsNum() {
      const n = Number(this.decimals)
      return isFinite(n) && n >= 0 ? Math.min(6, Math.floor(n)) : 2
    },
    currencySymbol() {
      const code = String(this.currency || 'CNY').toUpperCase()
      return CURRENCY_SYMBOL[code] || code
    },
    display() {
      if (this.editing) {
        return this.inputText
      }
      return formatAmount(this.value, { decimals: this.decimalsNum })
    },
    upperText() {
      const n = this.toNumber(this.value)
      return n === null ? '' : toChineseUpper(n)
    }
  },
  methods: {
    /** 文本 → 数字；去千分位/币种符号/空白，不可解析返回 null */
    toNumber(text) {
      if (text === null || text === undefined || text === '') {
        return null
      }
      if (typeof text === 'number') {
        return isFinite(text) ? text : null
      }
      const cleaned = String(text).replace(/[,\s¥￥$€]/g, '')
      if (cleaned === '' || cleaned === '-' || cleaned === '.') {
        return null
      }
      const n = Number(cleaned)
      return isFinite(n) ? n : null
    },
    /** 按小数位四舍五入并夹到 [min, max] */
    normalize(n) {
      if (n === null) {
        return null
      }
      const p = Math.pow(10, this.decimalsNum)
      let v = Math.round(n * p) / p
      const min = this.toNumber(this.min)
      const max = this.toNumber(this.max)
      if (min !== null && v < min) {
        v = min
      }
      if (max !== null && v > max) {
        v = max
      }
      return v
    },
    onFocus() {
      this.editing = true
      this.inputText = this.value === null || this.value === undefined ? '' : String(this.value)
    },
    onInput(v) {
      this.inputText = v
      const next = this.normalize(this.toNumber(v))
      // 只在"规整后的值"真的变了时才 emit，避免出现 "12." / "12.0" 这类中间态反复写模型
      if (next !== this.toNumber(this.value)) {
        this.$emit('input', next)
      }
    },
    onBlur() {
      // 失焦时把输入框里的内容按规则规整回模型（超范围的在这里被夹住）
      const next = this.normalize(this.toNumber(this.editing ? this.inputText : this.value))
      this.editing = false
      this.inputText = ''
      if (next !== this.toNumber(this.value)) {
        this.$emit('input', next)
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.design-amount {
  width: 100%;
}
.da-upper {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 2px;
  font: var(--oa-font-caption, 12px/18px system-ui);
  color: var(--oa-color-ink-subtle, #909399);
}
.da-upper-label {
  flex: none;
  padding: 0 4px;
  border: 1px solid var(--oa-color-hairline, #ebeef5);
  border-radius: 2px;
  font-size: 11px;
  color: var(--oa-color-ink-muted, #606266);
}
.da-upper-text {
  color: var(--oa-color-ink, #303133);
  /* 极端值：金额很大时大写很长，要能换行 */
  word-break: break-word;
  overflow-wrap: anywhere;
}
.da-upper-text.is-empty {
  color: var(--oa-color-ink-disabled, #c0c4cc);
}
</style>
