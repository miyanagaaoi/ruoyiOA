<template>
  <!--
    列表/表格页的「主数据加载失败」横条。
    为什么单独做一个小件，而不是直接用 StateBlock：
     · StateBlock 是"整块区域状态"，会顶掉 el-table；而 RuoYi 列表页的模板高度同构，
       在这里插一条横幅、把 el-table 挂 v-else，改动最小、最不容易碰坏布局；
     · 形态是横条（左图标 + 文案 + 右侧重试），行高固定，不会引起像素跳动。

    依据 open-design/craft/state-coverage.md：错误**不能**降级成空态。
    这张表在请求失败时必须和"确实没有数据"区分开——否则用户看到"暂无数据"就以为库是空的。
  -->
  <div class="data-load-error" role="alert">
    <i class="el-icon-warning-outline dle-icon" />
    <div class="dle-body">
      <span class="dle-text">{{ text || '数据加载失败。' }}</span>
      <span v-if="cause" class="dle-cause">可能原因：{{ cause }}</span>
    </div>
    <el-button
      v-if="retryable"
      type="text"
      size="mini"
      icon="el-icon-refresh"
      class="dle-retry"
      @click="$emit('retry')"
    >重试</el-button>
  </div>
</template>

<script>
/**
 * 用法（配合主数据加载的 catch）：
 *
 *   <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
 *   <el-table v-else v-loading="loading" :data="list" ...>
 *
 * 并且 catch 里要做三件事（缺一不可）：
 *   1. this.loading = false      —— 否则表格永久转圈（这是本次修的主要缺陷）
 *   2. this.list = []; this.total = 0  —— 清掉旧数据，否则"旧数据 + 错误提示"并存
 *   3. const d = describeError(err); this.loadError = d.text; this.loadErrorCause = d.cause
 */
export default {
  name: 'DataLoadError',
  props: {
    text: { type: String, default: '' },
    cause: { type: String, default: '' },
    retryable: { type: Boolean, default: true }
  }
}
</script>

<style lang="scss" scoped>
.data-load-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 12px;
  border: 1px solid var(--oa-color-error-surface);
  border-radius: var(--oa-radius-sm);
  background: var(--oa-color-error-surface);
}
.dle-icon {
  flex: none;
  margin-top: 2px;
  font-size: 15px;
  color: var(--oa-color-error);
}
.dle-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1 1 auto;
}
.dle-text {
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink);
  /* 极端值：超长错误信息不撑破布局 */
  word-break: break-word;
  overflow-wrap: anywhere;
}
.dle-cause {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
  word-break: break-word;
  overflow-wrap: anywhere;
}
.dle-retry {
  flex: none;
  padding: 0;
}
</style>
