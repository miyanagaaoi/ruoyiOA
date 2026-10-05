<template>
  <span class="doc-status-tag">
    <el-tag :type="tagType" size="mini" disable-transitions>{{ label }}</el-tag>
    <el-tag v-if="postedFlag" type="success" size="mini" class="doc-posted-tag" disable-transitions>已过账</el-tag>
  </span>
</template>

<script>
/**
 * 单据状态标签 + 过账标签（8 类单据共用；D12 的"状态标签"就是这个小件）。
 *
 * 状态文案与配色取自 `doc-rules.js`（镜像后端 `ErpDocStatus`）：**不在页面里各写一份**
 * —— 后端改了状态集合，前端只需要改 doc-rules 一处。
 */
import rules from '../doc-rules'

export default {
  name: 'DocStatusTag',
  props: {
    // 状态值（draft/submitted/approved/completed/voided）
    status: { type: String, default: '' },
    // 过账标记：'1'/'0'（char(1) 列）或布尔
    posted: { type: [String, Boolean], default: '' }
  },
  computed: {
    label() {
      return rules.statusLabel(this.status)
    },
    tagType() {
      return rules.statusTagType(this.status)
    },
    postedFlag() {
      return this.posted === '1' || this.posted === true
    }
  }
}
</script>

<style lang="scss" scoped>
.doc-status-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--oa-space-xxs);
}
.doc-posted-tag {
  margin-left: var(--oa-space-xxs);
}
</style>
