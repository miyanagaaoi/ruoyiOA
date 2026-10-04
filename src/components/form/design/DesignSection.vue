<template>
  <!--
    分组标题（PRD 9.3 / AC-37）—— 分节标题，不可填写、只作排版。

    为什么需要它：合同表单是分节的（我方信息 / 对方信息 / 合同有效期 /
    用印材料…），没有这个控件就只能靠"字段名叫『——我方信息——』"来凑，
    打印件上还会多出一行假字段。

    ⚠ 关于 PRD 里的"可折叠"：本控件**不做折叠**。当前渲染器（Parser / DraggableItem）
      是**逐字段独立渲染**的，一个分组不知道"后面哪些兄弟字段属于自己"，
      要折叠就得改渲染器把 fields 按 section 分组，或引入显式的"分组结束"标记 ——
      那是渲染协议级的改动，风险远大于收益。本期先保证"成节显示"这一条验收
      （AC-37 的原文只要求成节显示 + 打印件不出现该行），折叠留待后续。
  -->
  <div class="design-section" :class="{ 'is-line': showLine }">
    <div class="ds-head">
      <i v-if="icon" :class="icon" class="ds-icon" />
      <span class="ds-title">{{ title || '分组标题' }}</span>
    </div>
    <p v-if="desc" class="ds-desc">{{ desc }}</p>
  </div>
</template>

<script>
export default {
  name: 'DesignSection',
  props: {
    /** 分节标题，如"我方信息" */
    title: { type: String, default: '' },
    /** 标题下的补充说明，可空 */
    desc: { type: String, default: '' },
    /** 是否显示标题前的竖条（视觉强调） */
    showLine: { type: Boolean, default: true },
    /** 可选图标类名（el-icon-*） */
    icon: { type: String, default: '' }
  }
}
</script>

<style lang="scss" scoped>
.design-section {
  width: 100%;
  margin: 6px 0 2px;
}
.ds-head {
  display: flex;
  align-items: center;
}
/* 标题前的竖条：不靠加粗/字号堆强调，避免和真正的字段名抢注意力 */
.design-section.is-line .ds-head::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 14px;
  margin-right: 6px;
  border-radius: 2px;
  background: var(--oa-color-primary, #1f5ae0);
}
.ds-icon {
  margin-right: 4px;
  color: var(--oa-color-primary, #1f5ae0);
}
.ds-title {
  font: var(--oa-font-title-section, 600 14px/22px system-ui);
  color: var(--oa-color-ink, #303133);
}
.ds-desc {
  margin: 2px 0 0 9px;
  font: var(--oa-font-caption, 12px/18px system-ui);
  color: var(--oa-color-ink-subtle, #909399);
  /* 极端值：说明文字很长时换行，不撑破布局 */
  word-break: break-word;
  overflow-wrap: anywhere;
}
</style>
