<template>
  <!--
    个人中心 →「我的签名」（PRD 8.4 / AC-29）

    这一页负责**预存签名本身**的生命周期：手写或上传新增、设默认、改名、停用、删除。
    审批时"一键用掉它"是另一处（`components/SignaturePad/index.vue`），
    两边不共享状态，只共享服务端接口 —— 否则在个人中心改完还要通知审批页刷新。

    服务端已经把两条硬约束做掉了，前端不重复实现：
      · 归属：只能看/改自己的；
      · 默认唯一：置默认时同事务先清后置，把默认停用时会一并摘掉默认标记。
  -->
  <div class="my-sign">
    <el-alert type="info" :closable="false" class="ms-alert">
      <div slot="title">
        预存签名<b>仅本人可见、仅本人可用</b>。审批时可直接一键调用，不必每次重写；
        打印件的签批栏里贴的就是它。每位用户只有一枚「默认」签名。
      </div>
    </el-alert>

    <div class="ms-head">
      <span class="ms-count">
        <template v-if="state === 'ready'">
          共 {{ list.length }} 枚
          <span v-if="!hasDefault" class="ms-warn">·  还没有默认签名，审批时无法"一键使用默认签名"</span>
        </template>
      </span>
      <div class="ms-actions">
        <el-button size="mini" icon="el-icon-refresh" :disabled="state === 'loading'" @click="load">刷新</el-button>
        <el-button size="mini" type="primary" icon="el-icon-plus" @click="openAdd">新增签名</el-button>
      </div>
    </div>

    <!-- 三态与列表互斥：失败时给横条而不是一张空表（不然和"确实没有"分不清） -->
    <StateBlock
      v-if="state !== 'ready'"
      :state="state"
      loading-text="正在加载我的签名…"
      error-title="签名列表加载失败"
      :error-text="loadError"
      :error-cause="loadErrorCause"
      empty-title="还没有预存签名"
      empty-desc="新增一枚之后，审批时就能一键使用，不必每次现场手写。"
      @retry="load"
    >
      <template slot="empty-action">
        <el-button type="primary" size="small" icon="el-icon-plus" @click="openAdd">新增签名</el-button>
      </template>
    </StateBlock>

    <el-table v-else :data="list" size="mini" border>
      <el-table-column label="签名图" width="200">
        <template slot-scope="s">
          <img
            v-if="thumbUrl(s.row) && !thumbErrors[s.row.id]"
            class="ms-thumb"
            :src="thumbUrl(s.row)"
            alt="签名图片"
            @error="onThumbError(s.row)"
          />
          <span v-else class="ms-muted">图片不可用</span>
        </template>
      </el-table-column>
      <el-table-column label="名称" min-width="140" show-overflow-tooltip>
        <template slot-scope="s">{{ s.row.name || '（未命名）' }}</template>
      </el-table-column>
      <el-table-column label="默认" width="70" align="center">
        <template slot-scope="s">
          <el-tag v-if="isDefault(s.row)" type="success" size="mini">默认</el-tag>
          <span v-else class="ms-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="70" align="center">
        <template slot-scope="s">
          <el-tag :type="s.row.status === '1' ? '' : 'info'" size="mini">{{ s.row.status === '1' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="160" />
      <el-table-column label="操作" width="250">
        <template slot-scope="s">
          <el-button size="mini" type="text" :disabled="isDefault(s.row)" @click="handleSetDefault(s.row)">设为默认</el-button>
          <el-button size="mini" type="text" @click="handleRename(s.row)">重命名</el-button>
          <el-button size="mini" type="text" @click="handleToggleStatus(s.row)">
            {{ s.row.status === '1' ? '停用' : '启用' }}
          </el-button>
          <el-button size="mini" type="text" class="ms-danger" @click="handleDelete(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增：手写 或 上传图片 -->
    <el-dialog
      title="新增预存签名"
      :visible.sync="addVisible"
      width="720px"
      append-to-body
      :close-on-click-modal="false"
      @closed="resetAdd"
    >
      <el-form ref="addForm" :model="addForm" :rules="addRules" label-width="90px" size="small">
        <el-form-item label="名称" prop="name">
          <el-input v-model="addForm.name" maxlength="30" show-word-limit placeholder="如：常用签名" />
        </el-form-item>

        <el-form-item label="采集方式">
          <el-radio-group v-model="addForm.mode">
            <el-radio-button label="handwrite">手写采集</el-radio-button>
            <el-radio-button label="upload">上传图片</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="addForm.mode === 'handwrite'" label="手写区">
          <SignatureCanvas ref="canvas" :width="640" :height="260" :hint="addHint" />
        </el-form-item>

        <el-form-item v-else label="图片">
          <input ref="fileInput" type="file" accept="image/png,image/jpeg" class="ms-file" @change="onPickFile" />
          <el-button size="mini" icon="el-icon-upload2" @click="pickFile">选择图片</el-button>
          <span class="ms-hint">PNG / JPG，≤2MB；白底会自动转成透明底</span>
          <div class="ms-preview">
            <img v-if="addPreview" :src="addPreview" alt="待保存的签名预览" />
            <span v-else class="ms-muted">尚未选择图片</span>
          </div>
          <p class="ms-hint">{{ pickHint }}</p>
        </el-form-item>
      </el-form>

      <div slot="footer" class="dialog-footer">
        <el-button size="mini" @click="addVisible = false">取 消</el-button>
        <el-button size="mini" type="primary" :loading="saving" @click="submitAdd">保 存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import StateBlock from '@/components/StateBlock'
import SignatureCanvas, { isEmptySignError } from '@/components/SignaturePad/canvas'
import {
  listMySignPresets,
  addSignPreset,
  updateSignPreset,
  setDefaultSignPreset,
  delSignPreset,
  uploadSignatureFile
} from '@/api/workflow/sign'
import { getFileBlob } from '@/api/workflow/print'
import { describeError } from '@/utils/errorMessage'
import { prepareSignImageFile } from '@/utils/signImage'

const EMPTY_FORM = { name: '', mode: 'handwrite' }

export default {
  name: 'MySign',
  components: { StateBlock, SignatureCanvas },
  data() {
    return {
      /** loading | error | empty | ready */
      state: 'loading',
      list: [],
      loadError: '',
      loadErrorCause: '',

      /** 图片取不到的记录 id → true（`<img>` 的 onerror，避免破图图标） */
      thumbErrors: {},
      /** fileId 不是相对路径时取回的 blob 对象 URL */
      blobUrls: {},

      addVisible: false,
      addForm: { ...EMPTY_FORM },
      addRules: {
        name: [
          { required: true, message: '请给签名起个名字，便于审批时区分', trigger: 'blur' },
          { max: 30, message: '名称不超过 30 个字', trigger: 'blur' }
        ]
      },
      /** 上传图片 → 已去白底的 dataURL */
      addPreview: '',
      pickHint: '',
      addHint: '',
      saving: false
    }
  },
  computed: {
    hasDefault() {
      return this.list.some(r => this.isDefault(r))
    }
  },
  created() {
    this.load()
  },
  beforeDestroy() {
    Object.keys(this.blobUrls).forEach(k => {
      try {
        window.URL.revokeObjectURL(this.blobUrls[k])
      } catch (e) {
        /* 忽略：释放失败不影响功能 */
      }
    })
  },
  methods: {
    isDefault(row) {
      return !!row && row.isDefault === '1'
    },

    load() {
      this.state = 'loading'
      this.loadError = ''
      this.loadErrorCause = ''
      return listMySignPresets()
        .then(res => {
          const rows = (res && res.data) || []
          this.list = rows
          this.state = rows.length ? 'ready' : 'empty'
        })
        .catch(err => {
          // 失败必须与"没有数据"区分开：清空旧数据 + 给出可判断的原因
          const info = describeError(err)
          this.list = []
          this.loadError = info.text
          this.loadErrorCause = info.cause
          this.state = 'error'
        })
    },

    /**
     * 缩略图地址。
     *
     * 预存签名存的是 `/common/upload` 返回的相对路径（`/profile/upload/...`），
     * 这类路径有静态资源映射，拼上 API 前缀就能直接 `<img src>`；
     * 只有"文件ID"形态才需要走下载接口取 blob（与打印件的取图口径一致）。
     */
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
        .catch(() => {
          // 取不到就落到"图片不可用"，不弹错：这一列不影响用户管理签名
          this.$set(this.thumbErrors, row.id, true)
        })
      return ''
    },

    onThumbError(row) {
      this.$set(this.thumbErrors, row.id, true)
    },

    /* ---------------- 行操作 ---------------- */

    handleSetDefault(row) {
      setDefaultSignPreset(row.id)
        .then(() => {
          this.$modal.msgSuccess('已设为默认签名')
          return this.load()
        })
        .catch(err => this.$modal.msgError('设置失败：' + describeError(err).text))
    },

    handleRename(row) {
      this.$prompt('请输入新的签名名称', '重命名', {
        inputValue: row.name || '',
        inputValidator: value => (value && value.trim() ? true : '名称不能为空'),
        confirmButtonText: '保存',
        cancelButtonText: '取消'
      })
        .then(({ value }) => updateSignPreset({ id: row.id, name: value.trim() }))
        .then(() => {
          this.$modal.msgSuccess('已重命名')
          return this.load()
        })
        .catch(err => {
          if (err === 'cancel' || err === 'close') return
          this.$modal.msgError('重命名失败：' + describeError(err).text)
        })
    },

    handleToggleStatus(row) {
      const disable = row.status === '1'
      const tip = disable && this.isDefault(row)
        ? '这是当前的默认签名，停用后默认标记会被同时摘掉（服务端处理），审批时将无法再"一键使用默认签名"。确定停用？'
        : (disable ? '停用后审批时不再出现在可选列表里，可随时重新启用。确定停用？' : '确定启用这枚签名？')
      this.$confirm(tip, disable ? '停用签名' : '启用签名', {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      })
        .then(() => updateSignPreset({ id: row.id, status: disable ? '0' : '1' }))
        .then(() => {
          this.$modal.msgSuccess(disable ? '已停用' : '已启用')
          return this.load()
        })
        .catch(err => {
          if (err === 'cancel' || err === 'close') return
          this.$modal.msgError('操作失败：' + describeError(err).text)
        })
    },

    handleDelete(row) {
      this.$confirm(
        '删除后这枚签名不再出现在列表里（历史签名记录不受影响，图片会保留）。确定删除？',
        '删除签名',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
        .then(() => delSignPreset(row.id))
        .then(() => {
          this.$modal.msgSuccess('已删除')
          return this.load()
        })
        .catch(err => {
          if (err === 'cancel' || err === 'close') return
          this.$modal.msgError('删除失败：' + describeError(err).text)
        })
    },

    /* ---------------- 新增 ---------------- */

    openAdd() {
      this.resetAdd()
      this.addVisible = true
    },

    resetAdd() {
      this.addForm = { ...EMPTY_FORM }
      this.addPreview = ''
      this.pickHint = ''
      this.addHint = ''
      this.saving = false
      const input = this.$refs.fileInput
      if (input) {
        input.value = ''
      }
      // 画板在弹窗里刚被销毁过，清空要等下一个 tick
      this.$nextTick(() => {
        if (this.$refs.canvas) {
          this.$refs.canvas.reset()
        }
      })
    },

    pickFile() {
      if (this.$refs.fileInput) {
        this.$refs.fileInput.click()
      }
    },

    onPickFile(e) {
      const file = e.target.files && e.target.files[0]
      if (!file) {
        return
      }
      this.pickHint = '正在处理图片（去白底、缩放）…'
      prepareSignImageFile(file)
        .then(dataURL => {
          this.addPreview = dataURL
          this.pickHint = '白底已转为透明底，保存前可再确认一次效果。'
          if (!this.addForm.name) {
            // 用文件名当默认名称，省一次输入；用户可改
            this.addForm.name = (file.name || '').replace(/\.[^.]+$/, '').slice(0, 30)
          }
        })
        .catch(err => {
          this.addPreview = ''
          const info = describeError(err)
          this.pickHint = ''
          this.$modal.msgError(info.text)
        })
    },

    /** 取出待上传的透明底 PNG（手写现采 / 上传件的预览图） */
    captureDataURL() {
      if (this.addForm.mode === 'upload') {
        if (!this.addPreview) {
          return Promise.reject(new Error('请先选择一张签名图片'))
        }
        return Promise.resolve(this.addPreview)
      }
      return this.$refs.canvas.capture()
    },

    submitAdd() {
      this.$refs.addForm.validate(valid => {
        if (!valid) {
          return
        }
        this.saving = true
        this.addHint = ''
        this.captureDataURL()
          .then(dataURL => {
            this.addHint = '正在上传签名图片…'
            return uploadSignatureFile(dataURL, 'preset_' + Date.now())
          })
          .then(res => {
            // /common/upload 返回 { fileName: '/profile/upload/...', url: '...' }
            const body = res && res.data ? res.data : res
            const fileId = body && (body.fileName || body.url)
            if (!fileId) {
              throw new Error('上传未返回文件路径')
            }
            this.addHint = '正在保存…'
            return addSignPreset({ name: this.addForm.name, fileId: fileId })
          })
          .then(() => {
            this.addHint = ''
            this.$modal.msgSuccess('已保存，审批时即可一键使用')
            this.addVisible = false
            return this.load()
          })
          .catch(err => {
            this.addHint = ''
            // 空画板是"用户还没写"，不是错误，不该弹红字
            if (isEmptySignError(err)) {
              this.$modal.msgWarning('请先手写签名')
              return
            }
            this.$modal.msgError('保存失败：' + describeError(err).text)
          })
          .finally(() => {
            this.saving = false
          })
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.ms-alert {
  margin-bottom: 12px;
}
.ms-head {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.ms-count {
  margin-right: auto;
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
}
.ms-warn {
  color: var(--oa-color-warning, #e6a23c);
}
.ms-thumb {
  max-width: 180px;
  max-height: 90px;
  /* 签名是透明底 PNG，给个浅色底才看得见笔迹 */
  background: #fff;
  border: 1px solid var(--oa-color-hairline, #ebeef5);
  border-radius: 4px;
}
.ms-muted {
  color: var(--oa-color-ink-disabled, #c0c4cc);
  font-size: 12px;
}
.ms-danger {
  color: #f56c6c;
}
.ms-hint {
  margin: 0 0 0 8px;
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
}
.ms-file {
  display: none;
}
.ms-preview {
  margin-top: 8px;
  min-height: 60px;
  padding: 8px;
  border: 1px dashed var(--oa-color-hairline-strong, #dcdfe6);
  border-radius: 4px;
  background: #fff;

  img {
    max-width: 100%;
    max-height: 160px;
  }
}
</style>
