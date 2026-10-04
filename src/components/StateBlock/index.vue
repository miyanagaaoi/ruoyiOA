<template>
  <div class="state-block">
    <!--
      常驻的 live region：必须**先存在于 DOM**，之后再把文本注入进去，
      否则 aria-live 与内容同时插入不会触发朗读（见 open-design/craft/state-coverage.md §ARIA）。
      所以它不在 v-if 里。
    -->
    <div class="state-live" role="status" aria-live="polite">{{ liveText }}</div>

    <!-- ============ 加载态 ============ -->
    <div v-if="state === 'loading'" class="state-pane" :class="{ 'state-pane-compact': compact }" role="status">
      <i class="el-icon-loading state-spin" />
      <p class="state-title">{{ loadingText || '正在加载…' }}</p>
      <!-- 规则：15s 必须出现"比预期更久"的兜底提示，不能让转圈无限转下去 -->
      <p v-if="slow" class="state-desc state-slow">
        比预期更久。可能是网络较慢或服务无响应，可继续等待或
        <el-button type="text" class="state-inline-btn" @click="onRetry">重新加载</el-button>
      </p>
    </div>

    <!-- ============ 错误态 ============ -->
    <!--
      错误态必须回答三问：发生了什么 / 为什么 / 怎么办。
      规则明确「错误不可降级成空态」——空表格会和"确实没有数据"混淆。
    -->
    <div v-else-if="state === 'error'" class="state-pane state-pane-error" :class="{ 'state-pane-compact': compact }" role="alert">
      <i class="el-icon-warning-outline state-icon state-icon-error" />
      <p class="state-title">{{ errorTitle || '加载失败' }}</p>
      <p class="state-desc">{{ errorText }}</p>
      <p v-if="errorCause" class="state-desc state-cause">可能原因：{{ errorCause }}</p>

      <div class="state-actions">
        <template v-if="!exhausted">
          <el-button
            type="primary"
            size="small"
            icon="el-icon-refresh"
            :disabled="coolingDown"
            @click="onRetry"
          >{{ coolingDown ? `请稍候 ${countdown}s` : (attempts ? '重试' : '重新加载') }}</el-button>
        </template>
        <template v-else>
          <span class="state-desc">已重试 {{ attempts }} 次仍未成功，请把下面的错误编号提供给管理员。</span>
        </template>
      </div>

      <p v-if="attempts" class="state-desc state-meta">
        上次尝试：{{ lastAttemptText }}<template v-if="!exhausted">（第 {{ attempts }} 次重试）</template>
      </p>
      <div v-if="errorId || autoErrorId" class="state-errid">
        <span class="state-errid-label">错误编号</span>
        <code class="state-errid-code">{{ errorId || autoErrorId }}</code>
        <el-button type="text" class="state-inline-btn" @click="copyErrorId">复制</el-button>
      </div>
    </div>

    <!-- ============ 空态 ============ -->
    <!-- 空态不是"什么都没有"：要有标题、说明和一个主行动入口 -->
    <div v-else-if="state === 'empty'" class="state-pane" :class="{ 'state-pane-compact': compact }">
      <i class="el-icon-document state-icon" />
      <p class="state-title">{{ emptyTitle || '暂无数据' }}</p>
      <p class="state-desc">{{ emptyDesc }}</p>
      <div class="state-actions">
        <slot name="empty-action" />
      </div>
    </div>

    <!-- ============ 正常态 ============ -->
    <slot v-else />
  </div>
</template>

<script>
/**
 * 状态面板（加载 / 空 / 错误 / 正常）
 *
 * 依据：doc/skills/open-design/craft/state-coverage.md
 *  - 「只画了有数据的状态」是 AI 出 UI 最典型的失败，所以这四态必须成对出现；
 *  - 错误**不能**降级成空态；
 *  - 加载超过 15s 要给「比预期更久」的兜底；
 *  - 重试有纪律：第 1 次立即，第 2/3 次退避 2s/4s/8s，满 3 次后不再给"重试"，
 *    改为让用户把错误编号交给管理员（用户已尽到义务，该由人来接手）；
 *  - 错误文案要能长（极端值）：word-break + 可滚动，不撑破布局。
 *
 * 设计取舍：**重试纪律由本组件自己记账**，页面只需要维护 loading / error 两个字段。
 * 页面重试成功后 state 变回 ready，计数自动清零，页面不必关心退避计时。
 */
export default {
  name: 'StateBlock',
  props: {
    /** loading | error | empty | ready */
    state: { type: String, default: 'ready' },
    loadingText: { type: String, default: '' },
    /** 错误主文案（"发生了什么"），缺省用 errorTitle */
    errorText: { type: String, default: '请求没有成功返回。' },
    errorTitle: { type: String, default: '' },
    /** 可判断的原因（"为什么"） */
    errorCause: { type: String, default: '' },
    /** 便于报障的错误编号；不传则按时间戳生成一个稳定编号 */
    errorId: { type: String, default: '' },
    emptyTitle: { type: String, default: '' },
    emptyDesc: { type: String, default: '' },
    /** 加载多久算"慢"（毫秒） */
    slowAfter: { type: Number, default: 15000 },
    /** 最多允许几次重试，超过后转为"联系管理员" */
    maxAttempts: { type: Number, default: 3 },
    /** 紧凑模式：给首页小卡片这类窄而矮的容器用，去掉大留白与边框 */
    compact: { type: Boolean, default: false }
  },
  data() {
    return {
      attempts: 0,
      slow: false,
      slowTimer: null,
      cooldownTimer: null,
      countdown: 0,
      lastAttemptAt: null,
      nowTick: Date.now(),
      tickTimer: null,
      autoErrorId: ''
    }
  },
  computed: {
    exhausted() {
      return this.attempts >= this.maxAttempts
    },
    coolingDown() {
      return this.countdown > 0
    },
    liveText() {
      if (this.state === 'loading') return this.loadingText || '正在加载'
      if (this.state === 'error') return `加载失败：${this.errorText}`
      if (this.state === 'empty') return this.emptyTitle || '暂无数据'
      return ''
    },
    lastAttemptText() {
      if (!this.lastAttemptAt) return ''
      const sec = Math.max(0, Math.floor((this.nowTick - this.lastAttemptAt) / 1000))
      if (sec < 60) return `${sec} 秒前`
      return `${Math.floor(sec / 60)} 分钟前`
    }
  },
  watch: {
    state: {
      immediate: true,
      handler(val) {
        if (val === 'loading') {
          this.startSlowTimer()
        } else {
          this.clearSlowTimer()
        }
        // 回到正常态 = 这一轮请求成功了，重试纪律复位
        if (val === 'ready') {
          this.attempts = 0
          this.lastAttemptAt = null
          this.autoErrorId = ''
        }
        if (val === 'error') {
          if (!this.autoErrorId) {
            this.autoErrorId = 'ERR-' + Date.now().toString(36).toUpperCase()
          }
          this.startTick()
        } else {
          this.stopTick()
        }
      }
    }
  },
  beforeDestroy() {
    this.clearSlowTimer()
    this.clearCooldown()
    this.stopTick()
  },
  methods: {
    startSlowTimer() {
      this.clearSlowTimer()
      this.slow = false
      this.slowTimer = setTimeout(() => { this.slow = true }, this.slowAfter)
    },
    clearSlowTimer() {
      if (this.slowTimer) { clearTimeout(this.slowTimer); this.slowTimer = null }
      this.slow = false
    },
    /** 退避：第 1 次立即，之后 2s / 4s / 8s（上限 8s） */
    backoffMs(attempt) {
      if (attempt <= 0) return 0
      return Math.min(8000, Math.pow(2, attempt) * 1000)
    },
    onRetry() {
      if (this.coolingDown || this.exhausted) return
      const wait = this.backoffMs(this.attempts)
      this.attempts += 1
      this.lastAttemptAt = Date.now()
      this.nowTick = Date.now()
      if (wait > 0) {
        this.countdown = Math.round(wait / 1000)
        this.clearCooldown()
        this.cooldownTimer = setInterval(() => {
          this.countdown -= 1
          if (this.countdown <= 0) { this.clearCooldown() }
        }, 1000)
      }
      // 退避只是"按钮先冷却"；请求本身立刻发出，避免用户以为点了没反应
      this.$emit('retry', this.attempts)
    },
    clearCooldown() {
      if (this.cooldownTimer) { clearInterval(this.cooldownTimer); this.cooldownTimer = null }
      this.countdown = 0
    },
    /** 让"上次尝试 X 秒前"能自己走字 */
    startTick() {
      this.stopTick()
      this.tickTimer = setInterval(() => { this.nowTick = Date.now() }, 1000)
    },
    stopTick() {
      if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null }
    },
    copyErrorId() {
      const id = this.errorId || this.autoErrorId
      if (!id) return
      const done = () => this.$modal && this.$modal.msgSuccess('错误编号已复制')
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(id).then(done, done)
          return
        }
      } catch (e) { /* 降级到 execCommand */ }
      const ta = document.createElement('textarea')
      ta.value = id
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy') } catch (e) { /* 忽略：用户可手动选中 */ }
      document.body.removeChild(ta)
      done()
    }
  }
}
</script>

<style lang="scss" scoped>
.state-block {
  position: relative;
}
/* live region 不占视觉位置，但必须留在 DOM 里 */
.state-live {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.state-pane {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 32px 16px;
  text-align: center;
  color: var(--oa-color-ink-muted);
  background: var(--oa-color-canvas);
  border: 1px dashed var(--oa-color-hairline-strong);
  border-radius: var(--oa-radius-md);
}
.state-pane-error {
  border-style: solid;
  border-color: var(--oa-color-error-surface);
  background: var(--oa-color-error-surface);
}

/* 紧凑模式：首页卡片这类容器窄且矮，不能沿用 32px 留白和大图标 */
.state-pane-compact {
  padding: 14px 10px;
  gap: 4px;
  border: none;
  background: transparent;
  .state-icon, .state-spin { font-size: 18px; }
  .state-title { font: var(--oa-font-label); }
  .state-desc { font: var(--oa-font-caption); max-width: none; }
  .state-errid { margin-top: 2px; }
}

.state-spin {
  font-size: 22px;
  color: var(--oa-color-primary);
}
.state-icon {
  font-size: 26px;
  color: var(--oa-color-ink-disabled);
}
.state-icon-error {
  color: var(--oa-color-error);
}

.state-title {
  margin: 0;
  font: var(--oa-font-title-section);
  color: var(--oa-color-ink);
}
.state-desc {
  margin: 0;
  max-width: 560px;
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-subtle);
  /* 极端值：超长错误信息 / 无空格长串不能撑破布局 */
  word-break: break-word;
  overflow-wrap: anywhere;
}
.state-cause {
  color: var(--oa-color-ink-muted);
}
.state-slow {
  color: var(--oa-color-warning);
}
.state-meta {
  color: var(--oa-color-ink-disabled);
}

.state-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
  justify-content: center;
}
.state-inline-btn {
  padding: 0;
  font-size: inherit;
}

.state-errid {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 2px 10px;
  border: 1px solid var(--oa-color-hairline);
  border-radius: var(--oa-radius-sm);
  background: var(--oa-color-canvas);
  max-width: 100%;
}
.state-errid-label {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-disabled);
}
.state-errid-code {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: var(--oa-color-ink);
  overflow-wrap: anywhere;
}
</style>
