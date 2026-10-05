<template>
  <el-dialog :title="title" :visible.sync="show" width="520px" append-to-body @closed="handleClosed">
    <div class="action-body">
      <p class="action-doc">
        单据：<strong>{{ row && row.docNo ? row.docNo : '—' }}</strong>
        <span class="action-status">当前状态：{{ statusText }}</span>
      </p>

      <!-- 驳回 / 作废 / 反审核：原因必填（与后端 `reasonRequiredMessage()` 逐字一致的提示） -->
      <el-form v-if="reasonRequired" ref="actionForm" :model="form" :rules="formRules" size="small" label-width="0">
        <el-form-item prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            :maxlength="1000"
            show-word-limit
            :placeholder="reasonPlaceholder"
          />
        </el-form-item>
      </el-form>
      <p v-else class="action-tip">{{ confirmTip }}</p>

      <p v-if="errorText" class="action-error">{{ errorText }}</p>
    </div>
    <div slot="footer" class="dialog-footer">
      <el-button @click="show = false">取 消</el-button>
      <el-button :type="danger ? 'danger' : 'primary'" :loading="submitting" @click="handleSubmit">确 定</el-button>
    </div>
  </el-dialog>
</template>

<script>
/**
 * 单据动作弹窗（D12 的"审核/驳回/作废/反审核原因弹窗"；8 类单据共用）。
 *
 * 为什么不直接 `$modal.confirm`：驳回/作废/反审核**原因必填**，而 `MessageBox` 无法做
 * "必填校验 + 字数上限 + 与后端同一句文案"；理由文案与后端 `ErpDocAction` 保持同一句
 * （`doc-rules.reasonRequiredMessage`），避免前后端两套提示。
 *
 * ⚠ 权限点：驳回 / 置为已完成 在参考仓库与 tasks.md §8.1 里都**复用 approve 权限点**
 *   （见 `erp-const.ACTION_PERM_SEGMENT`），所以操作列的 v-hasPermi 用的是 `:approve`。
 */
import * as api from '@/api/erp/doc'
import rules from '../doc-rules'
import { describeError } from '@/utils/errorMessage'

export default {
  name: 'DocActionDialog',
  props: {
    visible: { type: Boolean, default: false },
    // 单据类型配置
    kind: { type: Object, required: true },
    // 动作定义（rules.visibleActions 的一项）
    action: { type: Object, default: null },
    // 目标行
    row: { type: Object, default: null }
  },
  data() {
    return {
      submitting: false,
      errorText: '',
      form: { reason: '' },
      formRules: {
        reason: [
          {
            validator: (rule, value, callback) => {
              if (!String(value || '').trim()) {
                callback(new Error(rules.reasonRequiredMessage(this.actionCode)))
                return
              }
              callback()
            },
            trigger: 'blur'
          }
        ]
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.visible
      },
      set(v) {
        this.$emit('update:visible', v)
      }
    },
    actionCode() {
      return this.action ? this.action.code : ''
    },
    title() {
      return rules.actionLabel(this.actionCode) + (this.row && this.row.docNo ? ' · ' + this.row.docNo : '')
    },
    reasonRequired() {
      return rules.isReasonRequired(this.actionCode)
    },
    danger() {
      return !!(this.action && this.action.danger)
    },
    reasonPlaceholder() {
      return '请填写' + rules.actionLabel(this.actionCode) + '原因（必填）'
    },
    statusText() {
      return rules.statusLabel(this.row && this.row.status)
    },
    confirmTip() {
      if (this.actionCode === 'approve') {
        const extra = this.kind && this.kind.stockDoc ? '该单据**审核即过账**：通过后结存与流水立刻变化。' : ''
        return '确认审核该单据？审核后不可再编辑。' + extra
      }
      if (this.actionCode === 'submit') {
        return '确认提交该单据？提交后进入待审核，不能再编辑。'
      }
      if (this.actionCode === 'complete') {
        return '确认把该单据置为已完成？'
      }
      return '确认执行「' + rules.actionLabel(this.actionCode) + '」？'
    }
  },
  methods: {
    handleSubmit() {
      if (this.reasonRequired && this.$refs.actionForm) {
        this.$refs.actionForm.validate((ok) => {
          if (ok) {
            this.doSubmit()
          }
        })
        return
      }
      this.doSubmit()
    },
    doSubmit() {
      this.submitting = true
      this.errorText = ''
      api
        .docAction(this.kind.code, this.row.id, this.actionCode, this.form.reason)
        .then(() => {
          this.submitting = false
          this.$modal.msgSuccess('「' + rules.actionLabel(this.actionCode) + '」成功')
          this.$emit('done')
          this.show = false
        })
        .catch((err) => {
          this.submitting = false
          const d = describeError(err)
          this.errorText = d.text
          this.$modal.msgError(d.text)
        })
    },
    handleClosed() {
      this.form.reason = ''
      this.errorText = ''
      this.submitting = false
      if (this.$refs.actionForm) {
        this.$refs.actionForm.clearValidate()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.action-doc {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-body-sm);
}
.action-status {
  margin-left: var(--oa-space-sm);
  color: var(--oa-color-ink-subtle);
}
.action-tip {
  margin: 0;
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-muted);
}
.action-error {
  margin: var(--oa-space-xs) 0 0;
  font: var(--oa-font-body-sm);
  color: var(--oa-color-error);
}
</style>
