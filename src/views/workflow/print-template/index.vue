<template>
  <div class="app-container">
    <!--
      打印模板配置（PRD 7.7 / AC-17、AC-24、AC-33；2.0 B2 的 REQ-PRINT-018）

      这张页面的存在理由很具体：打印件的"长什么样"（纸张、标题、签批栏/意见/附件/抄送
      各区开关、水印、字段映射）此前只能改库。

      2.0 B2 在这里加了两件事：
        ① **两种模式**：「选择内置模板」（4 套内置版式，按单据类型选）与
           「选择自定义模板」（自己编字段映射）。内置版式的**栏目常量由后端提供**，
           本页不再持有副本（REQ-PRINT-013 / AC-63）。
        ② **实时预览**：把当前生效的标题、栏目、签批栏、纸向直接列出来，
           不用先存再打一张才知道配成了什么样。
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
          @change="onTemplateChange"
        >
          <el-option v-for="t in templateOptions" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <span class="pt-bar-hint">
          同一单据模板下**只保留一套启用**（启用新的会自动停用旧的）——"哪套生效"必须是确定的，
          不能取决于谁最近被改过。没配任何模板时用**内置版式**。
        </span>
        <div class="pt-bar-actions">
          <el-button size="mini" icon="el-icon-refresh" :disabled="listState === 'loading'" @click="loadList">刷新</el-button>
        </div>
      </div>

      <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="loadList" />

      <!-- ============ 版式来源：内置 / 自定义 ============ -->
      <div v-if="queryTemplateId" class="pt-source">
        <el-radio-group v-model="mode" size="mini" @change="onModeChange">
          <el-radio-button label="builtin">选择内置模板</el-radio-button>
          <el-radio-button label="custom">选择自定义模板</el-radio-button>
        </el-radio-group>
        <el-tag size="mini" :type="effectiveSource === 'builtin' ? 'success' : ''" class="pt-src-tag">
          当前生效：{{ effectiveSource === 'builtin' ? '内置版式' : '自定义模板' }}
        </el-tag>

        <!-- 内置模式 -->
        <template v-if="mode === 'builtin'">
          <span class="pt-inline-label">内置版式</span>
          <el-select v-model="builtinKey" size="mini" class="pt-key-select" :loading="builtinLoading">
            <el-option v-for="b in builtinTemplates" :key="b.key" :label="b.name" :value="b.key" />
          </el-select>
          <el-button size="mini" type="primary" :loading="savingKey" v-hasPermi="['workflow:print:template:edit']" @click="saveBuiltinKey">
            保存版式选择
          </el-button>
          <el-button size="mini" @click="previewBuiltin">预览该版式栏目</el-button>
        </template>

        <!-- 自定义模式 -->
        <template v-else>
          <el-button size="mini" type="primary" icon="el-icon-plus" v-hasPermi="['workflow:print:template:edit']" @click="openAdd">
            新增打印模板
          </el-button>
          <span class="pt-inline-hint">自定义模板一旦启用，内置版式就不再参与该单据的打印。</span>
        </template>
      </div>

      <el-alert
        v-if="mode === 'builtin' && hasEnabledCustom"
        type="warning"
        :closable="false"
        show-icon
        class="pt-alert"
        title="该单据模板下已有启用的自定义打印模板：内置版式目前**不参与**打印，保存版式选择也不会改变打印结果。要改用内置版式，请先停用/删除那套自定义模板。"
      />

      <!-- ============ 实时预览 ============ -->
      <div v-if="queryTemplateId" class="pt-preview">
        <div class="pt-preview-head">实时预览</div>
        <div class="pt-preview-body">
          <div class="pt-pv-row"><span class="pt-pv-k">标题</span><span class="pt-pv-v">{{ preview.title || '（未设置，将用版式内置标题）' }}</span></div>
          <div class="pt-pv-row"><span class="pt-pv-k">纸张 / 方向</span><span class="pt-pv-v">{{ preview.paper }} / {{ preview.orientation === 'landscape' ? '横向' : '纵向' }}</span></div>
          <div class="pt-pv-row"><span class="pt-pv-k">签批栏</span><span class="pt-pv-v">{{ preview.showSignature === '1' ? '打印' : '不打印' }}</span></div>
          <div class="pt-pv-row"><span class="pt-pv-k">附件清单</span><span class="pt-pv-v">{{ preview.showAttachment === '1' ? '打印' : '不打印' }}</span></div>
          <div class="pt-pv-row">
            <span class="pt-pv-k">栏目</span>
            <span class="pt-pv-v">
              <!--
                ⚠ 这里刻意写成 v-for + `v-if="!length"`，而不是 `<template v-if>` + `<span v-else>`：
                实测本工程（Vue 2.7）下后者**两个分支会一起渲染**
                （页面上同时出现"按单据表单字段自动排版"与一堆栏目标签，浏览器实测抓到）。
                同一元素内的互斥用"条件取反"，不要依赖 template/普通元素之间的 v-else 配对。
              -->
              <el-tag v-for="(c, i) in preview.columns" :key="'c' + i" size="mini" class="pt-pv-tag">{{ c }}</el-tag>
              <span v-if="!preview.columns.length" class="pt-muted">按单据表单字段自动排版</span>
            </span>
          </div>
          <div class="pt-pv-row">
            <span class="pt-pv-k">签批栏栏目</span>
            <span class="pt-pv-v">
              <el-tag v-for="(c, i) in preview.signColumns" :key="'s' + i" size="mini" type="info" class="pt-pv-tag">{{ c }}</el-tag>
              <span v-if="!preview.signColumns.length" class="pt-muted">按流程节点动态出栏</span>
            </span>
          </div>
        </div>
        <div class="pt-preview-hint">{{ preview.hint }}</div>
      </div>

      <!-- ============ 自定义模板列表 ============ -->
      <el-table
        v-if="queryTemplateId && mode === 'custom'"
        v-loading="listState === 'loading'"
        :data="list"
        size="mini"
        border
        :empty-text="emptyText"
      >
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
        <el-table-column label="Logo" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.logoFileId ? 'success' : 'info'" size="mini">{{ s.row.logoFileId ? '有' : '无' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="抄送栏" width="70" align="center">
          <template slot-scope="s"><el-tag :type="s.row.showCcNode === '1' ? 'success' : 'info'" size="mini">{{ s.row.showCcNode === '1' ? '打印' : '不打印' }}</el-tag></template>
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
          <el-input v-model="form.title" maxlength="100" placeholder="留空则用内置版式的标题" />
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
            <b>是否出栏由这里的配置值决定，与"内置 / 自定义"无关</b>（2.0 B2 修正了此前
            "内置模板一律不出签批栏"的行为）。
          </div>
        </el-form-item>
        <el-form-item label="Logo">
          <!--
            走经典上传链路 POST /common/upload（字段名 file）。
            ⚠ 不要用 /file/operate/**：那个模块的本地路径是 E:/ruoyi/upload，
              与本机真正有文件的 F:/dsh/ruoyiOA/uploadPath 不是一回事（DEV-ENV §6.20）。
            同款组件（ImageUpload）在头像/签名上传处已在用，返回的是**相对路径**。
          -->
          <image-upload v-model="form.logoFileId" :limit="1" :file-size="2" :file-type="['png', 'jpg', 'jpeg']" />
          <div class="pt-hint">
            抬头左侧输出；不配置时不会出现空白占位。上传成功后落库的是相对路径
            （如 <code>/profile/upload/...</code>），打印件上会用 <code>VUE_APP_BASE_API</code> 前缀取图。
          </div>
        </el-form-item>
        <el-form-item label="页脚备注">
          <el-input v-model="form.footerNote" maxlength="200" placeholder="如：本件仅供内部使用" />
        </el-form-item>
        <el-form-item label="字段映射">
          <el-input v-model="form.fieldMap" type="textarea" :rows="6" placeholder="留空 = 按表单 schema 自动排版（推荐）；填了则按这里的行/列输出" />
          <div class="pt-hint">
            <span class="pt-inline-label">填入内置版式</span>
            <el-select v-model="fillKey" size="mini" class="pt-fill-select" @change="fillDefaultMap">
              <el-option v-for="b in builtinTemplates" :key="b.key" :label="b.name" :value="b.key" />
            </el-select>
            <el-button type="text" size="mini" @click="form.fieldMap = ''">清空（回到按表单排版）</el-button>
            <div>
              内容**由后端接口按版式键返回**（<code>GET /workflow/print/defaultFieldMap/{key}</code>）——
              内置版式常量以后端为唯一真源，本页不再持有副本（REQ-PRINT-013）。
              调整版式只改后端一处，**无需重新构建前端**。
            </div>
            保存前会校验 JSON 合法性；字段用表单的 <code>__vModel__</code>，<code>$submitter/$submitTime/$submitterCompany</code> 等是内置值。
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
import ImageUpload from '@/components/ImageUpload'
import { listTemplate } from '@/api/workflow/template'
import { listPrintTemplates, savePrintTemplate, delPrintTemplate } from '@/api/workflow/printTemplate'
import { listBuiltinPrintTemplates, getBuiltinFieldMap, saveBuiltinPrintKey } from '@/api/workflow/print'
import { describeError } from '@/utils/errorMessage'

/**
 * ⚠ 2.0 B2（REQ-PRINT-013 / AC-63）：这里**曾经**有一份复制自后端
 * `PrintServiceImpl.DEFAULT_FIELD_MAP` 的常量（"填入内置版式"的起点内容）。
 * 它与后端靠注释约定同步、没有任何自动化守卫（勘察报告的地雷 D-4），
 * 现在已删除 —— 改为按版式键调 `GET /workflow/print/defaultFieldMap/{key}` 拉取。
 * 请**不要**再在客户端硬编码任何内置版式的栏目。
 */

const EMPTY_FORM = {
  id: null,
  templateId: '',
  name: '',
  title: '',
  paper: 'A4',
  orientation: 'portrait',
  logoFileId: '',
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
  components: { DataLoadError, ImageUpload },
  data() {
    return {
      queryTemplateId: '',
      templateOptions: [],
      tplLoading: false,

      /** 版式来源：builtin=选择内置模板 / custom=选择自定义模板 */
      mode: 'builtin',
      /** 当前单据模板绑定的内置版式键（t_template.builtin_print_key） */
      builtinKey: 'contract',
      /** 内置版式清单（后端唯一真源：key + 名称 + 签批栏/附件清单推荐取值） */
      builtinTemplates: [],
      builtinLoading: false,
      savingKey: false,
      /** 「填入内置版式」下拉当前选中的键 */
      fillKey: 'contract',
      /** 预览用的内置版式字段映射（按需拉取，不在客户端留副本） */
      builtinPreviewMap: null,

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
    hasEnabledCustom() {
      return !!this.effectiveId
    },
    /**
     * 当前**实际生效**的版式来源。
     * 优先级链是服务端的既定口径：显式 printTplId > 启用行 > 内置版式，
     * 所以只要有一套启用的自定义模板，内置版式就不参与打印 —— 界面上必须如实显示，
     * 否则管理员会以为"选了内置版式就生效了"。
     */
    effectiveSource() {
      return this.hasEnabledCustom ? 'custom' : 'builtin'
    },
    emptyText() {
      return '该单据模板下还没有自定义打印模板 —— 不配也能打印（走内置版式）'
    },
    /** 当前预览对应的模板对象 */
    previewTemplate() {
      if (this.mode === 'builtin') {
        const b = this.builtinTemplates.find(x => x.key === this.builtinKey)
        return {
          title: b ? b.name : '',
          paper: 'A4',
          orientation: 'portrait',
          showSignature: b ? b.showSignature : '1',
          showAttachment: b ? b.showAttachment : '0',
          fieldMap: this.builtinPreviewMap
        }
      }
      const row = this.list.find(r => r.id === this.effectiveId) || this.list[0]
      return row || { title: '', paper: 'A4', orientation: 'portrait', showSignature: '0', showAttachment: '0', fieldMap: '' }
    },
    /** 实时预览：标题 / 纸向 / 各区开关 / 栏目清单（从 field_map 里解出来，不猜） */
    preview() {
      const t = this.previewTemplate || {}
      const map = this.parseMap(t.fieldMap)
      const sections = ((map && map.sections) || [])
      const columns = []
      const signColumns = []
      sections.forEach(sec => {
        if (!sec) return
        if (Array.isArray(sec.rows)) {
          sec.rows.forEach(r => (r.cells || []).forEach(c => c && c.label && columns.push(c.label)))
        }
        if (sec.id === 'sign' && Array.isArray(sec.columns)) {
          sec.columns.forEach(c => c && c.label && signColumns.push(c.label))
        }
      })
      let hint = ''
      if (this.mode === 'builtin') {
        hint = this.hasEnabledCustom
          ? '注意：该单据模板下已有启用的自定义模板，内置版式**不会**生效（见上方提示）。'
          : '内置版式的栏目由后端接口提供（唯一真源）；调整版式只改后端一处即可，无需重新构建前端。'
      } else {
        hint = this.list.length
          ? '自定义模板的版式来自它自己的「字段映射」；留空则按单据表单字段自动排版。'
          : '还没有自定义模板：点「新增打印模板」建一套，或切回「选择内置模板」。'
      }
      return {
        title: t.title || '',
        paper: t.paper || 'A4',
        orientation: t.orientation === 'landscape' ? 'landscape' : 'portrait',
        showSignature: t.showSignature === '1' ? '1' : '0',
        showAttachment: t.showAttachment === '1' ? '1' : '0',
        columns: columns,
        signColumns: signColumns,
        hint: hint
      }
    }
  },
  created() {
    this.loadTemplates()
    this.loadBuiltinTemplates()
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

    /** 内置版式清单（4 条）——一次性拉取，客户端不再抄名称与推荐取值 */
    loadBuiltinTemplates() {
      this.builtinLoading = true
      listBuiltinPrintTemplates()
        .then(res => {
          this.builtinTemplates = (res && res.data) || []
        })
        .catch(err => {
          this.$modal && this.$modal.msgError && this.$modal.msgError('内置版式清单加载失败：' + describeError(err).text)
        })
        .finally(() => {
          this.builtinLoading = false
        })
    },

    onTemplateChange() {
      this.list = []
      this.loadError = ''
      // 内置版式键取自单据模板列表（后端已带该列），不必再请求一次详情
      const t = this.templateOptions.find(x => x.id === this.queryTemplateId)
      this.builtinKey = (t && t.builtinPrintKey) || 'contract'
      this.fillKey = this.builtinKey
      this.builtinPreviewMap = null
      this.loadList()
      if (this.mode === 'builtin') {
        this.previewBuiltin()
      }
    },

    onModeChange(mode) {
      if (mode === 'builtin' && !this.builtinPreviewMap) {
        this.previewBuiltin()
      }
    },

    /** 拉取内置版式的字段映射（"填入内置版式"与预览共用同一份，且不在客户端留副本） */
    previewBuiltin() {
      const key = this.builtinKey || 'contract'
      return getBuiltinFieldMap(key)
        .then(res => {
          this.builtinPreviewMap = res && res.data ? res.data : null
          return this.builtinPreviewMap
        })
        .catch(err => {
          this.builtinPreviewMap = null
          this.$modal && this.$modal.msgError && this.$modal.msgError('内置版式读取失败：' + describeError(err).text)
          return null
        })
    },

    /** 保存"该单据模板用哪套内置版式" */
    saveBuiltinKey() {
      if (!this.queryTemplateId) return
      this.savingKey = true
      saveBuiltinPrintKey(this.queryTemplateId, this.builtinKey)
        .then(() => {
          this.$modal.msgSuccess('已保存该单据模板的内置版式：' + this.builtinKey)
          // 同步本地下拉里的值，避免"已保存但列表还显示旧值"
          const t = this.templateOptions.find(x => x.id === this.queryTemplateId)
          if (t) t.builtinPrintKey = this.builtinKey
          return this.previewBuiltin()
        })
        .catch(err => this.$modal.msgError('保存失败：' + describeError(err).text))
        .finally(() => {
          this.savingKey = false
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
          // 有启用行 → 实际生效的是自定义模板，默认把模式切到"自定义"，
          // 否则界面会停在"内置版式"，与实际生效的东西不一致
          if (this.hasEnabledCustom) {
            this.mode = 'custom'
          }
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
      this.fillKey = this.builtinKey || 'contract'
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },

    openEdit(row) {
      this.form = Object.assign({ ...EMPTY_FORM }, row)
      // 后端用 null 表示"没配"，界面统一用空串
      ;['paper', 'orientation', 'title', 'fieldMap', 'footerNote', 'logoFileId', 'showSignature', 'showComment',
        'showAttachment', 'showCcNode', 'watermark', 'enableFlag'].forEach(k => {
        if (this.form[k] === null || this.form[k] === undefined) this.form[k] = ''
      })
      this.fillKey = this.builtinKey || 'contract'
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },

    /**
     * 「填入内置版式」：内容由**后端接口**按版式键返回（不再用客户端副本）。
     * 后端返回的是对象，这里序列化成可编辑的 JSON 文本。
     */
    fillDefaultMap(key) {
      const k = key || this.fillKey || 'contract'
      getBuiltinFieldMap(k)
        .then(res => {
          const data = res && res.data
          if (!data) {
            this.$modal.msgError('该版式没有返回字段映射')
            return
          }
          this.form.fieldMap = JSON.stringify(data, null, 2)
          this.$modal.msgSuccess('已填入内置版式：' + k)
        })
        .catch(err => this.$modal.msgError('读取内置版式失败：' + describeError(err).text))
    },

    parseMap(raw) {
      if (!raw) return null
      if (typeof raw === 'object') return raw
      try {
        return JSON.parse(raw)
      } catch (e) {
        return null
      }
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
          ? '停用后该模板不再参与打印（同单据模板下若还有其它启用模板，会改用那一套；都没有则用内置版式）。确定停用？'
          : '确定启用该打印模板？启用后该单据模板的内置版式将不再参与打印。',
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
.pt-source {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0 4px;
  border-top: 1px dashed var(--oa-color-hairline);
}
.pt-src-tag {
  margin-left: 4px;
}
.pt-inline-label {
  font-size: 12px;
  color: var(--oa-color-ink-muted, #606266);
  margin-left: 8px;
}
.pt-inline-hint {
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
  margin-left: 8px;
}
.pt-key-select {
  width: 220px;
}
.pt-fill-select {
  width: 190px;
  margin-right: 8px;
}
.pt-alert {
  margin: 8px 0;
}
.pt-preview {
  margin: 10px 0 14px;
  border: 1px solid var(--oa-color-hairline);
  border-radius: 4px;
}
.pt-preview-head {
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 600;
  background: var(--oa-color-surface-2);
  border-bottom: 1px solid var(--oa-color-hairline);
}
.pt-preview-body {
  padding: 8px 10px;
}
.pt-pv-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  line-height: 22px;
}
.pt-pv-k {
  flex: none;
  width: 80px;
  color: var(--oa-color-ink-subtle, #909399);
}
.pt-pv-v {
  flex: 1;
  color: var(--oa-color-ink, #303133);
  word-break: break-all;
}
.pt-pv-tag {
  margin: 0 4px 4px 0;
}
.pt-preview-hint {
  padding: 6px 10px;
  font-size: 12px;
  line-height: 18px;
  color: var(--oa-color-ink-subtle, #909399);
  border-top: 1px dashed var(--oa-color-hairline);
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
