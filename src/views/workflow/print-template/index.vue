<template>
  <div class="app-container">
    <!--
      打印模板配置（PRD 7.7 / AC-17、AC-24、AC-33）

      这张页面的存在理由很具体：打印件的"长什么样"（纸张、标题、签批栏/意见/附件/抄送
      各区开关、水印、字段映射）此前只能改库 —— 内置系统模板恒定不出签批栏，
      于是 AC-17（签批栏顺序 = 节点顺序）与 AC-33（签名落在签批栏里）**配不出来**。
    -->
    <el-card shadow="never" class="pt-card">
      <div class="pt-bar">
        <span class="pt-bar-label">单据模板</span>
        <el-select
          v-model="queryTemplateId"
          filterable
          clearable
          placeholder="请选择单据模板"
          class="pt-select"
          :loading="tplLoading"
          @change="loadList"
        >
          <el-option v-for="t in templateOptions" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <span class="pt-bar-hint">
          同一单据模板下**只保留一套启用**（启用新的会自动停用旧的）——"哪套生效"必须是确定的，
          不能取决于谁最近被改过。没配任何模板时用内置系统模板。
        </span>
        <div class="pt-bar-actions">
          <el-button size="mini" icon="el-icon-refresh" :disabled="listState === 'loading'" @click="loadList">刷新</el-button>
          <el-button v-if="queryTemplateId" size="mini" type="primary" icon="el-icon-plus" v-hasPermi="['workflow:print:template:edit']" @click="openAdd">
            新增打印模板
          </el-button>
        </div>
      </div>

      <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="loadList" />

      <el-table v-else v-loading="listState === 'loading'" :data="list" size="mini" border :empty-text="emptyText">
        <el-table-column label="名称" prop="name" min-width="150" show-overflow-tooltip>
          <template slot-scope="s">
            {{ s.row.name }}
            <el-tag v-if="s.row.id === effectiveId" type="success" size="mini" class="pt-eff">生效中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="纸张" width="90" align="center">
          <template slot-scope="s">{{ s.row.paper || 'A4' }} / {{ s.row.orientation === 'landscape' ? '横向' : '纵向' }}</template>
        </el-table-column>
        <el-table-column label="单据标题" prop="title" min-width="160" show-overflow-tooltip />
        <el-table-column label="签批栏" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.showSignature === '1' ? 'success' : 'info'" size="mini">{{ s.row.showSignature === '1' ? '打印' : '不打印' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="意见" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.showComment === '1' ? 'success' : 'info'" size="mini">{{ s.row.showComment === '1' ? '打印' : '不打印' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="附件清单" width="80" align="center">
          <template slot-scope="s"><el-tag :type="s.row.showAttachment === '1' ? 'success' : 'info'" size="mini">{{ s.row.showAttachment === '1' ? '打印' : '不打印' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="水印" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.watermark === '1' ? 'success' : 'info'" size="mini">{{ s.row.watermark === '1' ? '开' : '关' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="字段映射" width="90" align="center">
          <template slot-scope="s">
            <span v-if="s.row.fieldMap">已配置</span>
            <span v-else class="pt-muted">按表单排版</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.enableFlag === '1' ? '' : 'info'" size="mini">{{ s.row.enableFlag === '1' ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template slot-scope="s">
            <el-button size="mini" type="text" v-hasPermi="['workflow:print:template:edit']" @click="openEdit(s.row)">修改</el-button>
            <el-button size="mini" type="text" v-hasPermi="['workflow:print:template:edit']" @click="toggleEnable(s.row)">
              {{ s.row.enableFlag === '1' ? '停用' : '启用' }}
            </el-button>
            <el-button size="mini" type="text" class="pt-danger" v-hasPermi="['workflow:print:template:remove']" @click="handleDelete(s.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog :title="form.id ? '修改打印模板' : '新增打印模板'" :visible.sync="open" width="760px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="110px" size="small">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="50" placeholder="如：合同审批 A4 正式版" />
        </el-form-item>
        <el-form-item label="单据标题" prop="title">
          <el-input v-model="form.title" maxlength="100" placeholder="留空则用内置标题「集团合同类文件流转审批单」" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="纸张">
              <el-select v-model="form.paper" :style="{ width: '100%' }">
                <el-option label="A4" value="A4" />
                <el-option label="A3" value="A3" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="方向">
              <el-radio-group v-model="form.orientation">
                <el-radio-button label="portrait">纵向</el-radio-button>
                <el-radio-button label="landscape">横向</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="打印内容">
          <el-checkbox v-model="form.showSignature" true-label="1" false-label="0">签批栏（含签名）</el-checkbox>
          <el-checkbox v-model="form.showComment" true-label="1" false-label="0">处理意见</el-checkbox>
          <el-checkbox v-model="form.showAttachment" true-label="1" false-label="0">附件清单</el-checkbox>
          <el-checkbox v-model="form.showCcNode" true-label="1" false-label="0">抄送节点出栏</el-checkbox>
          <el-checkbox v-model="form.watermark" true-label="1" false-label="0">水印</el-checkbox>
          <div class="pt-hint">
            签批栏是 AC-17/AC-33 的落点：勾上才会按流程节点顺序出签批栏，签名图片也才有地方落。
            内置系统模板**恒定不出签批栏**，要出就必须在这里建一套模板。
          </div>
        </el-form-item>
        <el-form-item label="页脚备注">
          <el-input v-model="form.footerNote" maxlength="200" placeholder="如：本件仅供内部使用" />
        </el-form-item>
        <el-form-item label="字段映射">
          <el-input v-model="form.fieldMap" type="textarea" :rows="6" placeholder="留空 = 按表单 schema 自动排版（推荐）；填了则按这里的行/列输出" />
          <div class="pt-hint">
            <el-button type="text" size="mini" @click="fillDefaultMap">填入内置版式（PRD 7.8 映射）</el-button>
            <el-button type="text" size="mini" @click="form.fieldMap = ''">清空（回到按表单排版）</el-button>
            保存前会校验 JSON 合法性；字段用表单的 <code>__vModel__</code>，<code>$submitter/$submitTime</code> 等是内置值。
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.enableFlag">
            <el-radio-button label="1">启用</el-radio-button>
            <el-radio-button label="0">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button size="mini" @click="open = false">取 消</el-button>
        <el-button size="mini" type="primary" :loading="saving" @click="submitForm">保 存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import DataLoadError from '@/components/DataLoadError'
import { listTemplate } from '@/api/workflow/template'
import { listPrintTemplates, savePrintTemplate, delPrintTemplate } from '@/api/workflow/printTemplate'
import { describeError } from '@/utils/errorMessage'

/**
 * 内置版式（PRD 7.8 的 13 行映射），与后端 `PrintServiceImpl.DEFAULT_FIELD_MAP` 同源。
 * 这里只是给管理员一个"起点"，不再参与渲染 —— 渲染一律读库里的 field_map。
 * ⚠ 改动 7.8 映射时，两处要一起改。
 */
const DEFAULT_FIELD_MAP = JSON.stringify({
  sections: [
    {
      id: 'base',
      rows: [
        { cells: [{ label: '提报单位', field: '$submitterDept', span: 1 }, { label: '报送人', field: '$submitter', span: 1 }, { label: '报送时间', field: '$submitTime', span: 1 }] },
        { cells: [{ label: '合同编号', field: 'contractNo', span: 3 }] },
        { cells: [{ label: '合同全称', field: 'contractName', span: 3 }] },
        { cells: [{ label: '合同签订主体-甲方', field: 'ourCompany', span: 1 }, { label: '合同签订主体-乙方', field: 'counterpartyName', span: 1 }, { label: '合同金额', field: 'amount', span: 1 }] },
        { cells: [{ label: '履约开始', field: 'startDate', span: 1 }, { label: '履约结束', field: 'endDate', span: 1 }, { label: '合同签订时间', field: 'signDate', span: 1 }] },
        { cells: [{ label: '其他会审部门', field: 'jointDepts', span: 3 }] },
        { cells: [{ label: '相关说明', field: 'description', span: 3 }] }
      ]
    },
    { id: 'sign', type: 'dynamic', source: 'flowNodes' },
    { id: 'attach', type: 'attachmentList' }
  ]
}, null, 2)

const EMPTY_FORM = {
  id: null,
  templateId: '',
  name: '',
  title: '',
  paper: 'A4',
  orientation: 'portrait',
  fieldMap: '',
  showSignature: '0',
  showComment: '1',
  showAttachment: '0',
  showCcNode: '0',
  watermark: '1',
  footerNote: '',
  enableFlag: '1'
}

export default {
  name: 'PrintTemplateConfig',
  components: { DataLoadError },
  data() {
    return {
      queryTemplateId: '',
      templateOptions: [],
      tplLoading: false,

      listState: 'ready',
      list: [],
      loadError: '',
      loadErrorCause: '',

      open: false,
      saving: false,
      form: { ...EMPTY_FORM },
      rules: {
        name: [{ required: true, message: '请填写模板名称', trigger: 'blur' }]
      }
    }
  },
  computed: {
    /** 当前生效的那一套：唯一启用的一套；历史数据里若有多套启用，服务端取 update_time 最新的那条 */
    effectiveId() {
      const enabled = this.list.filter(r => r.enableFlag === '1')
      if (enabled.length <= 1) {
        return enabled.length ? enabled[0].id : ''
      }
      return enabled
        .slice()
        .sort((a, b) => String(b.updateTime || '').localeCompare(String(a.updateTime || '')))[0].id
    },
    emptyText() {
      if (!this.queryTemplateId) {
        return '请先选择单据模板'
      }
      return '该单据模板下还没有打印模板 —— 不配也能打印（走内置系统模板），只是内置模板不出签批栏'
    }
  },
  created() {
    this.loadTemplates()
  },
  methods: {
    /** 单据模板下拉：配置页的入口维度 */
    loadTemplates() {
      this.tplLoading = true
      listTemplate({ pageNum: 1, pageSize: 200 })
        .then(res => {
          this.templateOptions = (res && res.rows) || []
        })
        .catch(err => {
          const info = describeError(err)
          this.loadError = '单据模板列表加载失败：' + info.text
          this.loadErrorCause = info.cause
        })
        .finally(() => {
          this.tplLoading = false
        })
    },

    loadList() {
      if (!this.queryTemplateId) {
        this.list = []
        this.listState = 'ready'
        this.loadError = ''
        return
      }
      this.listState = 'loading'
      this.loadError = ''
      listPrintTemplates(this.queryTemplateId)
        .then(res => {
          this.list = (res && res.data) || []
          this.listState = 'ready'
        })
        .catch(err => {
          // 失败必须与"确实没有"区分开：清空旧数据 + 给出可判断的原因
          const info = describeError(err)
          this.list = []
          this.loadError = info.text
          this.loadErrorCause = info.cause
          this.listState = 'ready'
        })
    },

    openAdd() {
      this.form = { ...EMPTY_FORM, templateId: this.queryTemplateId }
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },

    openEdit(row) {
      this.form = Object.assign({ ...EMPTY_FORM }, row)
      // 后端用 null 表示"没配"，界面统一用空串
      ;['paper', 'orientation', 'title', 'fieldMap', 'footerNote', 'showSignature', 'showComment',
        'showAttachment', 'showCcNode', 'watermark', 'enableFlag'].forEach(k => {
        if (this.form[k] === null || this.form[k] === undefined) this.form[k] = ''
      })
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },

    fillDefaultMap() {
      this.form.fieldMap = DEFAULT_FIELD_MAP
    },

    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        if (!this.form.templateId) {
          this.$modal.msgError('缺少单据模板，无法保存')
          return
        }
        // 前端先校验一次 JSON：与后端同一口径，但能省一次往返、提示也更即时
        if (this.form.fieldMap) {
          try {
            JSON.parse(this.form.fieldMap)
          } catch (e) {
            this.$modal.msgError('字段映射不是合法 JSON：' + e.message)
            return
          }
        }
        this.saving = true
        savePrintTemplate(this.form)
          .then(() => {
            this.$modal.msgSuccess('保存成功')
            this.open = false
            return this.loadList()
          })
          .catch(err => this.$modal.msgError('保存失败：' + describeError(err).text))
          .finally(() => {
            this.saving = false
          })
      })
    },

    toggleEnable(row) {
      const disable = row.enableFlag === '1'
      this.$confirm(
        disable
          ? '停用后该模板不再参与打印（同单据模板下若还有其它启用模板，会改用那一套；都没有则用内置系统模板）。确定停用？'
          : '确定启用该打印模板？',
        disable ? '停用打印模板' : '启用打印模板',
        { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
      )
        .then(() => savePrintTemplate({ id: row.id, templateId: row.templateId, enableFlag: disable ? '0' : '1' }))
        .then(() => {
          this.$modal.msgSuccess(disable ? '已停用' : '已启用')
          return this.loadList()
        })
        .catch(err => {
          if (err === 'cancel' || err === 'close') return
          this.$modal.msgError('操作失败：' + describeError(err).text)
        })
    },

    handleDelete(row) {
      this.$confirm('删除后该模板不再参与打印（已打印过的单据不受影响）。确定删除？', '删除打印模板', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
        .then(() => delPrintTemplate(row.id))
        .then(() => {
          this.$modal.msgSuccess('已删除')
          return this.loadList()
        })
        .catch(err => {
          if (err === 'cancel' || err === 'close') return
          this.$modal.msgError('删除失败：' + describeError(err).text)
        })
    }
  }
}
</script>

<style lang="scss" scoped>
.pt-card {
  min-height: 320px;
}
.pt-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.pt-bar-label {
  font-size: 13px;
  color: var(--oa-color-ink-muted, #606266);
}
.pt-select {
  width: 280px;
}
.pt-bar-hint {
  flex: 1 1 320px;
  min-width: 240px;
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
}
.pt-bar-actions {
  flex: none;
}
.pt-muted {
  color: var(--oa-color-ink-disabled, #c0c4cc);
}
.pt-eff {
  margin-left: 4px;
}
.pt-danger {
  color: #f56c6c;
}
.pt-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 20px;
  color: var(--oa-color-ink-subtle, #909399);
}
</style>
