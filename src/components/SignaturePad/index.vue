<template>
  <el-dialog
    title="签名"
    :visible="visible"
    width="860px"
    append-to-body
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <div class="sign-pad-tip">
      请在上方区域内手写签名（PC 用鼠标按住书写，移动端用手指）。
      签名与本次审批动作绑定并留痕，提交后不可修改。
    </div>

    <div class="sign-pad-wrap">
      <vue-esign
        ref="esign"
        :width="820"
        :height="300"
        :is-crop="true"
        :line-width="4"
        line-color="#101c4a"
        bg-color=""
      />
    </div>

    <div slot="footer" class="sign-pad-footer">
      <span class="sign-pad-hint">{{ hint }}</span>
      <el-button size="mini" icon="el-icon-delete" @click="handleReset">清空</el-button>
      <el-button size="mini" @click="handleClose">取消</el-button>
      <el-button type="primary" size="mini" :loading="submitting" @click="handleConfirm">确认签名</el-button>
    </div>
  </el-dialog>
</template>

<script>
import vueEsign from 'vue-esign'
import { uploadSignatureFile, addSignRecord } from '@/api/workflow/sign'
import { describeError } from '@/utils/errorMessage'

/**
 * 手写签名采集（PRD 8.3 / REQ-SIGN-002）
 *
 * 封装 `vue-esign`（依赖里已有，此前全库零引用）。流程：
 *   手写 → `generate()` 得到透明底 PNG 的 base64
 *        → `POST /common/upload` 拿到可直接访问的相对路径
 *        → `POST /workflow/sign/record` 落签名记录（服务端算哈希链）
 *
 * **未签名不得提交**由使用方（审批页）依据 `@signed` 事件控制，
 * 本组件只负责"采到一张有效的签名图并落库"。
 *
 * 已知取舍：`vue-esign@1.1.4` **没有撤销能力**（只有 reset），
 * 所以这里提供"清空"。PRD 8.3 要求的"撤销"需要一次一笔的笔画历史，
 * 该库未暴露，见组件说明与提交记录。
 */
export default {
  name: 'SignaturePad',
  components: { vueEsign },
  props: {
    visible: { type: Boolean, default: false },
    businessId: { type: String, default: '' },
    taskId: { type: String, default: '' },
    taskDefKey: { type: String, default: '' },
    nodeName: { type: String, default: '' },
    /** 默认手写；预存签名接入后由调用方传入 */
    signType: { type: String, default: '0' }
  },
  data() {
    return {
      submitting: false,
      hint: ''
    }
  },
  watch: {
    visible(v) {
      if (v) {
        this.hint = ''
        this.$nextTick(() => {
          if (this.$refs.esign) {
            this.$refs.esign.reset()
          }
        })
      }
    }
  },
  methods: {
    handleReset() {
      if (this.$refs.esign) {
        this.$refs.esign.reset()
      }
      this.hint = ''
    },

    handleClose() {
      this.$emit('update:visible', false)
      this.$emit('close')
    },

    /**
     * 确认签名。
     *
     * `generate()` 在画布为空时会 **reject**，这里据此做"非空白校验"，
     * 不必自己去数像素 —— 库已经做了判断，重复实现只会多一处可能出错的地方。
     */
    handleConfirm() {
      if (!this.businessId || !this.taskId) {
        this.$modal.msgError('缺少业务或任务信息，无法签名')
        return
      }
      this.submitting = true
      this.$refs.esign
        .generate()
        .then(dataURL => {
          if (!dataURL) {
            throw new Error('EMPTY')
          }
          this.hint = '正在上传签名…'
          const name = 'sign_' + (this.taskId || 'task')
          return uploadSignatureFile(dataURL, name)
        })
        .then(res => {
          // /common/upload 返回 { fileName: '/profile/upload/...', url: '...' }
          const body = res && res.data ? res.data : res
          const fileId = body && (body.fileName || body.url)
          if (!fileId) {
            throw new Error('上传未返回文件路径')
          }
          this.hint = '正在落签名记录…'
          return addSignRecord({
            businessId: this.businessId,
            taskId: this.taskId,
            taskDefKey: this.taskDefKey,
            nodeName: this.nodeName,
            signType: this.signType,
            fileId: fileId
          })
        })
        .then(res => {
          const rec = res && res.data ? res.data : res
          this.$modal.msgSuccess('签名成功')
          this.$emit('signed', rec)
          this.handleClose()
        })
        .catch(err => {
          // 空画布是"用户还没写"，不是错误，不该弹红字
          if (err && err.message === 'EMPTY') {
            this.hint = ''
            this.$modal.msgWarning('请先手写签名')
            return
          }
          const info = describeError(err)
          this.hint = ''
          this.$modal.msgError('签名失败：' + info.text)
        })
        .finally(() => {
          this.submitting = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
.sign-pad-tip {
  color: #606266;
  font-size: 12px;
  margin-bottom: 8px;
}
.sign-pad-wrap {
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  /* 移动端竖屏书写区要够高（PRD 8.3：≥240px） */
  min-height: 240px;
  overflow: hidden;
  background: #fff;
}
.sign-pad-footer {
  display: flex;
  align-items: center;
}
.sign-pad-hint {
  margin-right: auto;
  color: #909399;
  font-size: 12px;
}
</style>
