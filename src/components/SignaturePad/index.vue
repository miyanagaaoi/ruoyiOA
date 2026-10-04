<template>
  <!--
    审批动作里的签名弹窗（PRD 8.2 / 8.3 / 8.4 / 8.6、AC-28 ~ AC-30）

    两条路都从这里走：
      ·「使用预存签名」—— 一键用掉事先存好的签名（AC-30 前半）；
      ·「重新手写」  —— 现场写，并可勾选"同时保存为预存"（AC-30 后半）。

    本组件只负责"采到一张有效的签名图 / 选中一枚已有的，并落成签名记录"。
    「未签名不得提交」由使用方（审批页）依据 `@signed` 事件控制，
    "必需签名"的硬约束在服务端（`TaskSignGuard`），前端置灰只是 UI 约定。
  -->
  <el-dialog
    title="签名"
    :visible="visible"
    width="880px"
    append-to-body
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <p class="sign-tip">
      签名与本次审批动作绑定并留痕，提交后不可修改；重签会<b>新增</b>一条记录，旧记录保留。
      <span v-if="policy && policy.signRequired" class="sign-tip-required">
        本节点要求<b>必须签名</b>后才能提交（服务端同样会校验，绕过前端也会被拒）。
      </span>
    </p>

    <el-tabs v-model="activeTab">
      <!-- ================= 预存签名 ================= -->
      <el-tab-pane
        :label="allowPreset ? '使用预存签名' : '使用预存签名（本节点不允许）'"
        name="preset"
        :disabled="!allowPreset"
      >
        <StateBlock
          v-if="presetState !== 'ready'"
          :state="presetState"
          compact
          loading-text="正在加载我的预存签名…"
          error-title="预存签名加载失败"
          :error-text="presetError"
          :error-cause="presetErrorCause"
          empty-title="还没有预存签名"
          empty-desc="可以先在「个人中心 → 我的签名」里存好，审批时一键调用；也可以切到「重新手写」，签完勾选「同时保存为预存」。"
          @retry="loadContext"
        >
          <template slot="empty-action">
            <el-button size="mini" icon="el-icon-refresh" @click="loadContext">刷新列表</el-button>
            <el-button size="mini" type="text" @click="activeTab = 'handwrite'">改用重新手写</el-button>
          </template>
        </StateBlock>

        <template v-else>
          <div class="preset-bar">
            <el-button
              type="primary"
              size="mini"
              icon="el-icon-magic-stick"
              :disabled="!defaultPreset || submitting"
              @click="useDefault"
            >一键使用默认签名</el-button>
            <span v-if="defaultPreset" class="preset-bar-hint">
              默认签名：{{ defaultPreset.name || '（未命名）' }}
            </span>
            <span v-else class="preset-bar-hint preset-bar-warn">
              还没有默认签名，请先在「个人中心 → 我的签名」里设一枚为默认。
            </span>
          </div>

          <div class="preset-grid">
            <div
              v-for="p in presets"
              :key="p.id"
              class="preset-card"
              :class="{ 'is-selected': selectedId === p.id, 'is-disabled': p.status !== '1' }"
              :title="p.status !== '1' ? '这枚签名已停用，不可选用' : '点击选用这枚签名'"
              @click="selectPreset(p)"
            >
              <img v-if="thumbUrl(p) && !thumbErrors[p.id]" :src="thumbUrl(p)" :alt="p.name || '签名'" @error="onThumbError(p)" />
              <span v-else class="preset-card-empty">图片不可用</span>
              <div class="preset-card-meta">
                <span class="preset-card-name">{{ p.name || '（未命名）' }}</span>
                <span class="preset-card-tags">
                  <el-tag v-if="p.isDefault === '1'" type="success" size="mini">默认</el-tag>
                  <el-tag v-if="p.status !== '1'" type="info" size="mini">已停用</el-tag>
                </span>
              </div>
            </div>
          </div>
        </template>
      </el-tab-pane>

      <!-- ================= 重新手写 ================= -->
      <el-tab-pane
        :label="allowHandwrite ? '重新手写' : '重新手写（本节点不允许）'"
        name="handwrite"
        :disabled="!allowHandwrite"
      >
        <div class="sign-pad-tip">
          请在上方区域内手写签名（PC 用鼠标按住书写，移动端用手指）。
        </div>

        <SignatureCanvas ref="canvas" :width="820" :height="300" :hint="hint" />

        <div class="save-preset">
          <el-checkbox v-model="saveAsPreset">同时保存为预存签名</el-checkbox>
          <el-input
            v-if="saveAsPreset"
            v-model="presetName"
            size="mini"
            maxlength="30"
            show-word-limit
            class="save-preset-name"
            placeholder="给这枚签名起个名字（如：常用签名）"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <div slot="footer" class="sign-pad-footer">
      <span class="sign-pad-hint">{{ footerHint }}</span>
      <el-button size="mini" @click="handleClose">取 消</el-button>
      <el-button
        v-if="activeTab === 'preset'"
        type="primary"
        size="mini"
        :loading="submitting"
        :disabled="!selectedPreset"
        @click="submitPresetSign"
      >使用该签名</el-button>
      <el-button v-else type="primary" size="mini" :loading="submitting" @click="submitHandwrite">
        确认签名
      </el-button>
    </div>
  </el-dialog>
</template>

<script>
import StateBlock from '@/components/StateBlock'
import SignatureCanvas, { isEmptySignError } from './canvas'
import {
  uploadSignatureFile,
  addSignRecord,
  addSignPreset,
  listMySignPresets,
  getDefaultSignPreset,
  getSignPolicy
} from '@/api/workflow/sign'
import { getFileBlob } from '@/api/workflow/print'
import { describeError } from '@/utils/errorMessage'

/** 签名方式（与 t_workflow_sign_record.sign_type 对齐）：0-手写，1-预存，2-印章 */
const SIGN_TYPE_HANDWRITE = '0'
const SIGN_TYPE_PRESET = '1'

export default {
  name: 'SignaturePad',
  components: { StateBlock, SignatureCanvas },
  props: {
    visible: { type: Boolean, default: false },
    businessId: { type: String, default: '' },
    taskId: { type: String, default: '' },
    taskDefKey: { type: String, default: '' },
    nodeName: { type: String, default: '' }
  },
  data() {
    return {
      /** preset | handwrite */
      activeTab: 'preset',
      /** loading | error | empty | ready（只描述"我的预存签名"这一块） */
      presetState: 'loading',
      presets: [],
      presetError: '',
      presetErrorCause: '',
      /** 服务端认定的默认签名（停用的不算默认，口径在服务端 selectDefault） */
      defaultPreset: null,
      /**
       * 节点签名策略 `{signMode, signTypes[], signRequired}`。
       * null = 还没拿到（加载中/接口失败）——此时**两种方式都允许**，
       * 因为拿不到策略不该让用户签不了名。
       */
      policy: null,
      selectedId: '',
      thumbErrors: {},
      blobUrls: {},

      saveAsPreset: false,
      presetName: '',
      hint: '',
      footerHint: '',
      submitting: false
    }
  },
  computed: {
    selectedPreset() {
      return this.presets.find(p => p.id === this.selectedId && p.status === '1') || null
    },

    /**
     * 本节点允许的签名方式（PRD 8.2）。
     *
     * 策略没拿到（`policy === null`）时两种都允许 —— **fail-open**：
     * 一个辅助信息读不到，不该把签名入口整个关掉。
     */
    allowHandwrite() {
      if (!this.policy) {
        return true
      }
      return (this.policy.signTypes || []).indexOf('HANDWRITE') >= 0
    },

    allowPreset() {
      if (!this.policy) {
        return true
      }
      return (this.policy.signTypes || []).indexOf('PRESET') >= 0
    }
  },
  watch: {
    visible(v) {
      if (v) {
        this.footerHint = ''
        this.hint = ''
        this.loadContext()
      }
    }
  },
  beforeDestroy() {
    this.releaseBlobUrls()
  },
  methods: {
    /* ==================== 预存签名列表 ==================== */

    /**
     * 打开弹窗时把需要的东西一次取齐：我的预存签名 + 默认签名 + 本节点签名策略。
     *
     * 三个请求里**只有策略是可失败的**：它失败时按"两种方式都允许"降级（fail-open），
     * 不把弹窗推进错误态 —— 预存签名加载失败才是真的没法用这条路。
     */
    loadContext() {
      if (!this.businessId || !this.taskId) {
        // 缺上下文时不去打接口：审批页在任务就绪前会先把组件挂上
        this.presetState = 'empty'
        return Promise.resolve()
      }
      this.presetState = 'loading'
      this.presetError = ''
      this.presetErrorCause = ''
      this.policy = null

      // 策略：失败只记一条告警，返回 null
      const policyPromise = getSignPolicy(this.taskId)
        .then(res => (res && res.data) || null)
        .catch(err => {
          console.warn('签名：节点签名策略读取失败，按"手写 + 预存都允许"处理（signMode 仍由服务端把关）', err)
          return null
        })

      return Promise.all([listMySignPresets(), getDefaultSignPreset(), policyPromise])
        .then(([listRes, defaultRes, policy]) => {
          this.policy = policy
          const rows = (listRes && listRes.data) || []
          this.presets = rows
          this.defaultPreset = (defaultRes && defaultRes.data) || null
          this.presetState = rows.length ? 'ready' : 'empty'
          this.applyInitialSelection()
        })
        .catch(err => {
          const info = describeError(err)
          this.presets = []
          this.defaultPreset = null
          this.selectedId = ''
          this.presetError = info.text
          this.presetErrorCause = info.cause
          this.presetState = 'error'
          // 预存签名这条路走不通时，若节点允许手写就直接切过去，别让用户停在错误页
          if (this.allowHandwrite) {
            this.activeTab = 'handwrite'
          }
        })
    },

    /** 打开时替用户选好：有默认用默认，否则选第一枚启用的；预存不可用时切到手写 */
    applyInitialSelection() {
      const usable = this.allowPreset ? this.presets.filter(p => p.status === '1') : []
      if (!usable.length) {
        if (this.allowHandwrite) {
          this.activeTab = 'handwrite'
        }
        this.selectedId = ''
        return
      }
      const preferred = this.defaultPreset && usable.find(p => p.id === this.defaultPreset.id)
      this.selectedId = (preferred || usable[0]).id
      this.activeTab = 'preset'
    },

    /**
     * 「一键使用默认签名」（AC-30 前半）。
     *
     * 是**真的"一键"**：选中默认签名并立刻提交，不再要求用户再点一次底部按钮 ——
     * 否则这个按钮的文案就是在骗人。想选别的签名仍然可以在下面的卡片里点选，
     * 再用底部的「使用该签名」提交。
     */
    useDefault() {
      if (!this.defaultPreset) {
        return
      }
      this.selectedId = this.defaultPreset.id
      this.submitPresetSign()
    },

    selectPreset(p) {
      if (p.status !== '1') {
        this.$modal.msgWarning('这枚签名已停用，请先在「个人中心 → 我的签名」里启用')
        return
      }
      this.selectedId = p.id
    },

    /** 与"我的签名"页同一套取图口径：相对路径直连，文件ID走下载取 blob */
    thumbUrl(row) {
      if (!row || !row.fileId) {
        return ''
      }
      const id = String(row.fileId)
      if (id.charAt(0) === '/') {
        return process.env.VUE_APP_BASE_API + id
      }
      if (this.blobUrls[row.id]) {
        return this.blobUrls[row.id]
      }
      getFileBlob(id)
        .then(res => {
          const blob = res && res.data ? res.data : res
          if (blob) {
            this.$set(this.blobUrls, row.id, window.URL.createObjectURL(blob))
          }
        })
        .catch(() => this.$set(this.thumbErrors, row.id, true))
      return ''
    },

    onThumbError(row) {
      this.$set(this.thumbErrors, row.id, true)
    },

    releaseBlobUrls() {
      Object.keys(this.blobUrls).forEach(k => {
        try {
          window.URL.revokeObjectURL(this.blobUrls[k])
        } catch (e) {
          /* 忽略 */
        }
      })
      this.blobUrls = {}
    },

    /* ==================== 落签名记录 ==================== */

    /** 用选中的预存签名落一条记录（sign_type=1） */
    submitPresetSign() {
      const preset = this.selectedPreset
      if (!preset) {
        this.$modal.msgWarning('请先选择一枚可用的预存签名')
        return
      }
      this.submitting = true
      this.footerHint = '正在提交签名…'
      addSignRecord({
        businessId: this.businessId,
        taskId: this.taskId,
        taskDefKey: this.taskDefKey,
        nodeName: this.nodeName,
        signType: SIGN_TYPE_PRESET,
        fileId: preset.fileId
      })
        .then(res => this.afterSigned(res, ''))
        .catch(err => this.failSign(err))
        .finally(() => {
          this.submitting = false
        })
    },

    /**
     * 手写签名：取图 → 上传 → 落签名记录 →（勾选时）另存为预存。
     *
     * 顺序是刻意的：**先把签名落下来**。若"同时保存为预存"失败，
     * 用户要的是签名，不能因为附带动作失败而丢掉主结果，所以它放在最后、
     * 失败只警告不回滚。
     */
    submitHandwrite() {
      if (!this.businessId || !this.taskId) {
        this.$modal.msgError('缺少业务或任务信息，无法签名')
        return
      }
      if (this.saveAsPreset && !String(this.presetName || '').trim()) {
        this.$modal.msgWarning('勾选了"同时保存为预存"，请填写签名名称')
        return
      }
      this.submitting = true
      this.footerHint = ''
      this.hint = ''
      let uploadedFileId = ''
      this.$refs.canvas
        .capture()
        .then(dataURL => {
          this.hint = '正在上传签名…'
          return uploadSignatureFile(dataURL, 'sign_' + (this.taskId || 'task'))
        })
        .then(res => {
          // /common/upload 返回 { fileName: '/profile/upload/...', url: '...' }
          const body = res && res.data ? res.data : res
          const fileId = body && (body.fileName || body.url)
          if (!fileId) {
            throw new Error('上传未返回文件路径')
          }
          uploadedFileId = fileId
          this.hint = '正在落签名记录…'
          return addSignRecord({
            businessId: this.businessId,
            taskId: this.taskId,
            taskDefKey: this.taskDefKey,
            nodeName: this.nodeName,
            signType: SIGN_TYPE_HANDWRITE,
            fileId: fileId
          })
        })
        .then(res => {
          if (!this.saveAsPreset) {
            return this.afterSigned(res, '')
          }
          return addSignPreset({
            name: String(this.presetName).trim(),
            fileId: uploadedFileId
          })
            .then(() => this.afterSigned(res, '，并已保存为预存签名'))
            .catch(err => {
              // 签名已经成功，这里的失败只提示、不改变结果
              this.afterSigned(res, '')
              this.$modal.msgWarning('签名成功，但保存为预存失败：' + describeError(err).text)
            })
        })
        .catch(err => this.failSign(err))
        .finally(() => {
          this.submitting = false
        })
    },

    afterSigned(res, suffix) {
      const rec = res && res.data ? res.data : res
      this.hint = ''
      this.footerHint = ''
      this.$modal.msgSuccess('签名成功' + (suffix || ''))
      this.$emit('signed', rec)
      this.handleClose()
    },

    failSign(err) {
      this.hint = ''
      this.footerHint = ''
      // 空画布是"用户还没写"，不是错误，不该弹红字
      if (isEmptySignError(err)) {
        this.$modal.msgWarning('请先手写签名')
        return
      }
      this.$modal.msgError('签名失败：' + describeError(err).text)
    },

    handleClose() {
      this.$emit('update:visible', false)
      this.$emit('close')
    }
  }
}
</script>

<style lang="scss" scoped>
.sign-tip {
  margin: 0 0 8px;
  color: var(--oa-color-ink-subtle, #606266);
  font-size: 12px;
}
.sign-tip-required {
  margin-left: 6px;
  color: var(--oa-color-error, #f56c6c);
}
.sign-pad-tip {
  margin-bottom: 8px;
  color: var(--oa-color-ink-subtle, #606266);
  font-size: 12px;
}
.sign-pad-footer {
  display: flex;
  align-items: center;
}
.sign-pad-hint {
  margin-right: auto;
  color: var(--oa-color-ink-subtle, #909399);
  font-size: 12px;
}

/* ---------- 预存签名区 ---------- */
.preset-bar {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}
.preset-bar-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
}
.preset-bar-warn {
  color: var(--oa-color-warning, #e6a23c);
}
.preset-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  /* 极端值：签名多到十几枚时这一区可滚动，不把弹窗顶出屏幕 */
  max-height: 320px;
  overflow-y: auto;
  padding: 2px;
}
.preset-card {
  width: 190px;
  padding: 8px;
  border: 1px solid var(--oa-color-hairline, #ebeef5);
  border-radius: var(--oa-radius-sm, 4px);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:hover {
    border-color: var(--oa-color-primary, #409eff);
  }
  &.is-selected {
    border-color: var(--oa-color-primary, #409eff);
    box-shadow: 0 0 0 1px var(--oa-color-primary, #409eff) inset;
  }
  &.is-disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }

  img {
    display: block;
    width: 100%;
    height: 80px;
    object-fit: contain;
    /* 透明底签名要浅色底才看得见笔迹 */
    background: #fff;
    border: 1px dashed var(--oa-color-hairline, #ebeef5);
    border-radius: 3px;
  }
}
.preset-card-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 80px;
  font-size: 12px;
  color: var(--oa-color-ink-disabled, #c0c4cc);
  background: var(--oa-color-canvas, #fafafa);
  border-radius: 3px;
}
.preset-card-meta {
  display: flex;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
}
.preset-card-name {
  margin-right: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--oa-color-ink, #303133);
}
.preset-card-tags {
  flex: none;
}

/* ---------- 手写区的"存为预存" ---------- */
.save-preset {
  display: flex;
  align-items: center;
  margin-top: 10px;
}
.save-preset-name {
  width: 320px;
  margin-left: 12px;
}
</style>
