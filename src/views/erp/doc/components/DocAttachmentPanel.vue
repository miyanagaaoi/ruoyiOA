<template>
  <div class="doc-attachment-panel">
    <div class="attachment-bar">
      <el-upload
        action="#"
        :http-request="handleUploadRequest"
        :before-upload="beforeUpload"
        :show-file-list="false"
        :disabled="!canUpload"
      >
        <el-button
          size="mini"
          type="primary"
          plain
          icon="el-icon-upload2"
          :disabled="!canUpload || uploading"
          :loading="uploading"
          >上传附件</el-button
        >
      </el-upload>
      <el-button
        size="mini"
        type="text"
        icon="el-icon-refresh"
        :loading="loading"
        v-hasPermi="['ctms:attachment:list']"
        @click="getList"
        >刷新</el-button
      >
      <span class="tip-text">{{ limitText }}</span>
      <span v-if="unregistered" class="tip-warn">{{ unregisteredText }}</span>
    </div>

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else :data="list" size="mini" border v-loading="loading">
      <el-table-column label="文件名" prop="fileName" min-width="240" show-overflow-tooltip />
      <el-table-column label="大小" align="right" width="100">
        <template slot-scope="scope">{{ humanSize(scope.row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column label="上传人" align="center" width="110">
        <template slot-scope="scope">{{ scope.row.createBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="上传时间" align="center" width="160">
        <template slot-scope="scope">{{ parseTime(scope.row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="150">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            icon="el-icon-download"
            :loading="downloadingId === scope.row.id"
            v-hasPermi="['ctms:attachment:list']"
            @click="handleDownload(scope.row)"
            >下载</el-button
          >
          <el-button
            v-if="canUpload"
            type="text"
            size="mini"
            icon="el-icon-delete"
            :loading="deletingId === scope.row.id"
            @click="handleDelete(scope.row)"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
/**
 * 单据附件区（D12 的"附件区"；8 类单据共用，含调拨单 —— tasks.md 6.6 补参考仓库缺失项）。
 *
 * 复用 B3 的通用附件接口与口径：
 *   · 限制（20MB / 后缀白名单 / 已注册对象类型）**从 `/ctms/attachment/object-types` 取**，
 *     前端不写第二份常量（写死必然与后端漂移，B3 已记录该教训）；
 *   · 上传/删除在服务端要求"该业务对象的 edit + 附件读权限"（AND）。`v-hasPermi` 是 OR，
 *     所以这里用 `$auth.hasPermiAnd([...])` 算一个布尔量再决定按钮是否出现；
 *   · 未保存的单据（无 id）不能挂附件 —— 附件表要求 object_id。
 *
 * ⚠ 已知待办（见 notes/09a-frontend-core.md §5）：`purchase_request` / `sales_request`
 *   目前不在后端注册清单里，上传会被服务端 422；这里用 `unregistered` 提示把原因显示出来，
 *   而不是让用户看到一个没头没尾的失败。
 */
import { listAttachment, uploadAttachment, downloadAttachment, delAttachment, getAttachmentObjectTypes } from '@/api/erp/attachment'
import { describeError } from '@/utils/errorMessage'
import { blobValidate } from '@/utils/ruoyi'
import { saveAs } from 'file-saver'
import DataLoadError from '@/components/DataLoadError'

export default {
  name: 'DocAttachmentPanel',
  components: { DataLoadError },
  props: {
    // 附件对象类型（= 单据码）
    objectType: { type: String, required: true },
    // 业务对象 id（未保存时为空串）
    objectId: { type: String, default: '' },
    // 写权限点（该单据的 edit；服务端还要求附件读权限）
    writePerm: { type: String, default: '' }
  },
  data() {
    return {
      list: [],
      loading: false,
      uploading: false,
      downloadingId: '',
      deletingId: '',
      loadError: '',
      loadErrorCause: '',
      limits: null
    }
  },
  computed: {
    canUpload() {
      if (!this.objectId) {
        return false
      }
      const perms = ['ctms:attachment:list']
      if (this.writePerm) {
        perms.push(this.writePerm)
      }
      return this.$auth.hasPermiAnd(perms)
    },
    limitText() {
      if (!this.limits) {
        return '附件限制口径加载中…'
      }
      return '单个附件 ≤ ' + this.limits.maxSizeMb + 'MB；允许后缀：' + (this.limits.extensions || []).join(' / ')
    },
    unregistered() {
      return !!(this.limits && this.limits.registered && this.limits.registered.indexOf(this.objectType) < 0)
    },
    unregisteredText() {
      return '当前后端附件注册表里没有「' + this.objectType + '」对象类型，上传会被拒绝（需后端补登记）'
    }
  },
  watch: {
    objectId(v) {
      if (v) {
        this.getList()
      } else {
        this.list = []
      }
    }
  },
  created() {
    this.loadLimits()
    if (this.objectId) {
      this.getList()
    }
  },
  methods: {
    humanSize(bytes) {
      const n = Number(bytes)
      if (!isFinite(n) || n <= 0) {
        return '—'
      }
      if (n < 1024) {
        return n + ' B'
      }
      if (n < 1024 * 1024) {
        return (n / 1024).toFixed(1) + ' KB'
      }
      return (n / 1024 / 1024).toFixed(2) + ' MB'
    },
    loadLimits() {
      getAttachmentObjectTypes()
        .then((res) => {
          this.limits = res || null
        })
        .catch((err) => {
          const d = describeError(err)
          this.limits = null
          this.$modal.msgWarning('附件限制口径未取到：' + d.text)
        })
    },
    getList() {
      if (!this.objectId) {
        return
      }
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      listAttachment({ objectType: this.objectType, objectId: this.objectId })
        .then((res) => {
          this.list = (res && res.data) || []
          this.loading = false
        })
        .catch((err) => {
          const d = describeError(err)
          this.list = []
          this.loadError = d.text
          this.loadErrorCause = d.cause
          this.loading = false
        })
    },
    beforeUpload(file) {
      const limits = this.limits || {}
      if (!limits.maxSizeBytes) {
        this.$modal.msgError('附件限制口径未取到（/ctms/attachment/object-types），请刷新后重试')
        return false
      }
      if (file.size > limits.maxSizeBytes) {
        this.$modal.msgError('单个附件不能超过 ' + limits.maxSizeMb + 'MB（当前 ' + this.humanSize(file.size) + '）')
        return false
      }
      const extensions = limits.extensions || []
      const name = file.name || ''
      const dot = name.lastIndexOf('.')
      const extension = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
      if (extensions.indexOf(extension) < 0) {
        this.$modal.msgError('不支持的文件后缀「' + (extension || '(无后缀)') + '」；允许：' + extensions.join(' / '))
        return false
      }
      return true
    },
    handleUploadRequest(param) {
      this.uploading = true
      uploadAttachment(this.objectType, this.objectId, param.file)
        .then(() => {
          this.uploading = false
          this.$modal.msgSuccess('上传成功')
          this.getList()
        })
        .catch((err) => {
          this.uploading = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleDownload(row) {
      this.downloadingId = row.id
      downloadAttachment(row.id)
        .then((data) => {
          this.downloadingId = ''
          if (!blobValidate(data)) {
            // 后端把失败写成了 JSON（HTTP 仍是 200）：直接抛错，绝不能把 JSON 当文件存下来
            throw new Error('附件下载失败：服务端返回的是错误信息而不是文件，请重试或联系管理员')
          }
          saveAs(new Blob([data]), row.fileName || 'attachment')
          return null
        })
        .catch((err) => {
          this.downloadingId = ''
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleDelete(row) {
      this.$modal
        .confirm('确认删除附件「' + (row.fileName || '') + '」？')
        .then(() => {
          this.deletingId = row.id
          return delAttachment(row.id)
        })
        .then(() => {
          this.deletingId = ''
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
        .catch(() => {
          this.deletingId = ''
        })
    }
  }
}
</script>

<style lang="scss" scoped>
.doc-attachment-panel {
  margin-top: var(--oa-space-sm);
}
.attachment-bar {
  display: flex;
  align-items: center;
  gap: var(--oa-space-xs);
  margin-bottom: var(--oa-space-xs);
}
.tip-text {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.tip-warn {
  font: var(--oa-font-caption);
  color: var(--oa-color-warning);
}
</style>
