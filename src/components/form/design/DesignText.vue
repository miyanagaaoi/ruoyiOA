<template>
  <!--
    说明文字（PRD 9.3 / REQ-FORM-001）—— 纯展示文本，不可填写。

    与"分组标题"的区别：分组标题说的是"下面这一节是什么"，说明文字说的是
    "为什么要填/怎么填"（如"请上传加盖公章的扫描件"）。两者都不进打印件的基本信息表
    （见 print/index.vue 的 isLayoutOnly）。
  -->
  <div class="design-text" :style="{ textAlign: align || 'left' }">
    <i v-if="icon" :class="icon" class="dt-icon" />
    <span class="dt-body">{{ content || '说明文字' }}</span>
  </div>
</template>

<script>
export default {
  name: 'DesignText',
  props: {
    /** 说明内容（≤500 字，PRD 9.3） */
    content: { type: String, default: '' },
    /** left | center | right */
    align: { type: String, default: 'left' },
    icon: { type: String, default: '' }
  }
}
</script>

<style lang="scss" scoped>
.design-text {
  width: 100%;
  padding: 2px 0;
  font: var(--oa-font-body-sm, 13px/20px system-ui);
  color: var(--oa-color-ink-muted, #606266);
}
.dt-icon {
  margin-right: 4px;
  color: var(--oa-color-ink-subtle, #909399);
}
.dt-body {
  /* 极端值：长文本（无空格长串）也要能换行 */
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}
</style>
