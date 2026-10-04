<template>
  <div class="print-page">
    <!-- 屏幕上的工具条：打印时整条隐藏（.no-print） -->
    <div class="print-toolbar no-print">
      <span class="tb-title">{{ title }}</span>
      <span class="tb-hint">A4 纵向 · 共 {{ pageCount }} 页</span>
      <el-button type="primary" size="mini" icon="el-icon-printer" :loading="printing" @click="doPrint">打印</el-button>
      <el-button size="mini" icon="el-icon-refresh" @click="load">刷新数据</el-button>
      <el-button size="mini" @click="closeWin">关闭</el-button>
    </div>

    <StateBlock
      :state="state"
      loading-text="正在读取打印数据…"
      :error-text="err.text"
      :error-cause="err.cause"
      :error-id="errId"
      empty-title="找不到该单据的打印数据"
      empty-desc="单据可能已被删除，或流程实例尚未建立。"
      @retry="load"
    >
      <div v-if="data" class="print-paper">
        <!-- 水印（打印件防伪） -->
        <div v-if="data.watermarkText" class="p-watermark no-print-none">{{ data.watermarkText }}</div>

        <!-- A. 抬头区 -->
        <div class="p-title">{{ title }}</div>
        <div class="p-headline">
          <div class="p-hl-left">
            <span v-if="data.printTemplate && data.printTemplate.name">模板：{{ data.printTemplate.name }}</span>
          </div>
          <div class="p-hl-right">单据编号：{{ data.businessNo || '—' }}</div>
        </div>

        <!-- B. 基本信息区（由 field_map 决定行列） -->
        <table class="p-base">
          <tbody>
            <tr v-for="(row, ri) in baseRows" :key="'r' + ri">
              <template v-for="(cell, ci) in row.cells">
                <th :key="'h' + ri + '-' + ci">{{ cell.label }}</th>
                <td :key="'d' + ri + '-' + ci" :colspan="cellSpan(cell)">{{ valueOf(cell.field) }}</td>
              </template>
            </tr>
          </tbody>
        </table>

        <!-- C. 签批栏区：按流程节点生成 -->
        <div class="p-section-title">公文接收及处理</div>
        <div v-if="!nodes.length" class="p-empty-tip">（暂无办理记录）</div>
        <div v-for="(n, i) in nodes" :key="'n' + i" class="sign-block">
          <div class="sb-head">{{ cn(i + 1) }} {{ n.nodeName || '（未命名节点）' }}</div>
          <div class="sb-meta">
            <span>接收单位：{{ n.deptName || '—' }}</span>
            <span>接收人：{{ n.assigneeName || '—' }}</span>
            <span>签收时间：{{ fmt(n.receiveTime) }}</span>
            <span v-if="n.finishTime">办结时间：{{ fmt(n.finishTime) }}</span>
          </div>
          <div v-if="showComment" class="sb-comment">{{ n.comment || '' }}</div>
          <div class="sb-sign">
            <img v-if="signUrls[n.taskId]" :src="signUrls[n.taskId]" alt="签名" />
            <span v-else class="sb-sign-empty">（签名）</span>
          </div>
        </div>

        <!-- D. 附件清单区（只打印清单，不打印文件内容） -->
        <template v-if="showAttachment">
          <div class="p-section-title">附件清单</div>
          <div v-if="!attachRows.length" class="p-empty-tip">（无附件）</div>
          <table v-else class="p-attach">
            <thead>
              <tr>
                <th style="width: 12mm">序号</th>
                <th>附件名称</th>
                <th style="width: 18mm">类型</th>
                <th style="width: 22mm">大小</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(a, i) in attachRows" :key="'a' + i">
                <td style="text-align: center">{{ i + 1 }}</td>
                <td>{{ a.fileName }}</td>
                <td style="text-align: center">{{ (a.fileExt || '').toUpperCase() }}</td>
                <td style="text-align: right">{{ fileSize(a.fileSize) }}</td>
              </tr>
            </tbody>
          </table>
        </template>

        <!-- E. 页脚区 -->
        <div class="p-footer">
          <div class="pf-left">
            打印时间：{{ fmt(data.printTime) }} ｜ 打印人：{{ data.printUser || '—' }}
            <span v-if="footerNote" class="pf-note"> ｜ {{ footerNote }}</span>
          </div>
          <div class="pf-right">共 {{ pageCount }} 页</div>
        </div>
      </div>
    </StateBlock>
  </div>
</template>

<script>
import StateBlock from '@/components/StateBlock'
import { getPrintData, addPrintLog, getFileBlob } from '@/api/workflow/print'
import { describeError } from '@/utils/errorMessage'

/** A4 版心高度（297mm - 上下各 12mm）换算成 96dpi 下的像素，用于估算页数 */
const A4_CONTENT_PX = ((297 - 24) / 25.4) * 96

export default {
  name: 'WorkflowPrint',
  components: { StateBlock },
  data() {
    return {
      state: 'loading',
      data: null,
      err: { text: '', cause: '' },
      errId: '',
      pageCount: 1,
      printing: false,
      /** 表单字段值：字段 __vModel__ -> 值 */
      formValues: {},
      /** 表单字段中文标签：字段 __vModel__ -> label */
      formLabels: {},
      /** 表单 schema 的字段顺序（内置系统模板据此自动排版） */
      schemaFields: [],
      /**
       * 选项翻译表：字段 -> { 值: 中文标签 }。
       * 选项**不在** `__config__` 里，而在字段的 `__slot__.options`
       * （实测：el-select / el-radio-group / el-checkbox-group 都是这个位置）。
       */
      optionMap: {},
      /** 开关翻译表：字段 -> { 'true': '是', 'false': '否' }（el-switch 的 active/inactive） */
      switchMap: {},
      /**
       * 签名图片的对象 URL：taskId -> blob URL。
       * 不能直接用 `<img :src="'/file/operate/downloadfile?fileId=' + id">` ——
       * 那个接口是 POST，GET 会返回 "Request method 'GET' not supported"。
       * 所以先取 blob 再转对象 URL，并在组件销毁时释放，避免内存泄漏。
       */
      signUrls: {}
    }
  },
  computed: {
    businessId() {
      return this.$route.query.businessId
    },
    printTplId() {
      return this.$route.query.printTplId
    },
    title() {
      if (this.data && this.data.title) return this.data.title
      return '集团合同类文件流转审批单'
    },
    tpl() {
      return (this.data && this.data.printTemplate) || {}
    },
    showComment() {
      return this.tpl.showComment !== '0'
    },
    showAttachment() {
      return this.tpl.showAttachment !== '0'
    },
    footerNote() {
      return this.tpl.footerNote || ''
    },
    /**
     * 基本信息区的行。优先级：
     *   1. 管理员配置过的打印模板（tpl.id 有值）→ 用它的 field_map，版式完全可控；
     *   2. 内置系统模板（tpl.id 为空）→ **按表单 schema 自动排版**，
     *      这样任意单据都能打印，不必为每张表单单独配一套映射；
     *   3. 连 schema 都没有 → 退回模板里的内置 field_map。
     */
    baseRows() {
      const isBuiltin = !this.tpl.id
      if (isBuiltin && this.schemaFields.length) return this.rowsFromSchema()
      const map = this.parseFieldMap(this.tpl.fieldMap)
      if (!map) return this.rowsFromSchema()
      const sec = (map.sections || []).find(s => !s.type || s.id === 'base')
      const rows = (sec && sec.rows) || []
      return rows.length ? rows : this.rowsFromSchema()
    },
    nodes() {
      return (this.data && this.data.nodes) || []
    },
    attachRows() {
      return (this.data && this.data.attachments) || []
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      if (!this.businessId) {
        this.state = 'error'
        this.err = { text: '缺少 businessId 参数，无法打印。', cause: '请从单据详情页或列表的 [打印] 入口进入。' }
        return
      }
      this.state = 'loading'
      this.releaseSignUrls()
      getPrintData(this.businessId, this.printTplId)
        .then(res => {
          const d = res && res.data
          if (!d) {
            this.state = 'empty'
            return
          }
          this.data = d
          this.applyFormData(d.formData)
          this.loadSignImages(d.nodes)
          this.$nextTick(() => {
            this.measurePages()
            this.state = 'ready'
          })
        })
        .catch(err => {
          const info = describeError(err)
          this.err = { text: info.text, cause: info.cause }
          this.errId = 'PRINT-' + Date.now().toString(36).toUpperCase()
          this.state = 'error'
        })
    },

    /** 打印：先测页数，再调浏览器打印，最后落打印留痕 */
    doPrint() {
      this.measurePages()
      this.printing = true
      // window.print() 在多数浏览器是阻塞的，返回时对话框已关闭
      window.print()
      this.writeLog()
      this.printing = false
    },

    /** 打印留痕：只传业务信息与页数，打印人与时间由服务端决定 */
    writeLog() {
      if (!this.businessId) return
      addPrintLog({
        businessId: this.businessId,
        procInsId: this.data ? this.data.procInsId : null,
        templateId: this.data ? this.data.templateId : null,
        printTplId: this.tpl.id || null,
        pageCount: this.pageCount,
        watermarkText: this.data ? this.data.watermarkText : null
      }).catch(() => {
        // 留痕失败不应影响打印本身：已经打出去了，这里只提示
        this.$modal && this.$modal.msgWarning && this.$modal.msgWarning('打印留痕写入失败，请联系管理员')
      })
    },

    /** 预分页测算（PRD 7.5：不依赖 CSS counter(pages)，避免浏览器差异） */
    measurePages() {
      const el = this.$el && this.$el.querySelector('.p-base')
      if (!el) {
        this.pageCount = 1
        return
      }
      const paper = this.$el.querySelector('.print-paper')
      if (!paper) {
        this.pageCount = 1
        return
      }
      const h = paper.scrollHeight
      this.pageCount = Math.max(1, Math.ceil(h / A4_CONTENT_PX))
    },

    closeWin() {
      window.close()
      // 浏览器可能不允许脚本关闭非脚本打开的窗口，退化为返回
      this.$router.push('/index')
    },

    /**
     * 取签名图片。两种形态都要支持：
     *
     *   1. **可直接访问的相对路径**（经典上传 `/common/upload` 返回的
     *      `/profile/upload/...`）—— 有静态资源映射，加上 API 前缀就能 `<img src>`，
     *      这是本环境里实际可用的那条路；
     *   2. **文件ID**（新文件模块）—— 它的下载接口是 POST 且要求 query 传参，
     *      只能取 blob 再转对象 URL。
     *
     * 签名是只读展示，单张失败不该让整张打印件失败：只记一条告警，该栏留空
     * （正好还是"供手写"的语义）。
     */
    loadSignImages(nodes) {
      const list = (nodes || []).filter(n => n && n.signFileId && n.taskId)
      list.forEach(n => {
        const id = n.signFileId
        if (id.charAt(0) === '/') {
          this.$set(this.signUrls, n.taskId, process.env.VUE_APP_BASE_API + id)
          return
        }
        getFileBlob(id)
          .then(res => {
            const blob = res && res.data ? res.data : res
            if (!blob) return
            this.$set(this.signUrls, n.taskId, window.URL.createObjectURL(blob))
          })
          .catch(() => {
            console.warn('打印：签名图片加载失败 taskId=' + n.taskId + ' fileId=' + n.signFileId)
          })
      })
    },

    /** 释放对象 URL（直接可访问的路径不是对象 URL，不能 revoke） */
    releaseSignUrls() {
      Object.keys(this.signUrls).forEach(k => {
        const u = this.signUrls[k]
        if (typeof u === 'string' && u.indexOf('blob:') === 0) {
          try {
            window.URL.revokeObjectURL(u)
          } catch (e) {
            /* 忽略 */
          }
        }
      })
      this.signUrls = {}
    },

    /* ---------- field_map ---------- */
    parseFieldMap(raw) {
      if (!raw) return null
      if (typeof raw === 'object') return raw
      try {
        return JSON.parse(raw)
      } catch (e) {
        return null
      }
    },

    /** 一个 cell 占 span 个"列单位"，每个单位 = th + td 两列 */
    cellSpan(cell) {
      const span = Number(cell && cell.span)
      if (!span || span < 1) return 1
      return span * 2 - 1
    },

    /* ---------- 取值 ---------- */
    /**
     * 取字段值。
     * `$` 开头的是内置变量；其余从表单数据里按字段名取。
     * 表单数据的结构由 IBizFormService 决定，这里不假设形状 —— 先摊平成索引再查。
     */
    valueOf(field) {
      if (!field) return ''
      if (field.charAt(0) === '$') return this.builtin(field)
      return this.renderValue(field, this.formValues[field])
    },

    /**
     * 把**原始值**渲染成打印件上该有的样子：
     * 选项值转中文标签、开关转是/否、数组用「、」连接、金额加千分位。
     * 翻译表来自表单 schema（见 buildTranslateMaps），不再依赖字典接口。
     */
    renderValue(field, v) {
      if (v === null || v === undefined || v === '') return ''
      const one = x => {
        if (x === null || x === undefined || x === '') return ''
        const key = String(x)
        const opt = this.optionMap[field]
        if (opt && opt[key] !== undefined) return opt[key]
        const sw = this.switchMap[field]
        if (sw && sw[key] !== undefined) return sw[key]
        if (typeof x === 'boolean') return x ? '是' : '否'
        if (typeof x === 'number') return this.fmtNumber(field, x)
        if (typeof x === 'object') {
          // 值是 {label,value} / {dictLabel,dictValue} 这类包装时取可读的那一侧
          if (x.label !== undefined) return String(x.label)
          if (x.dictLabel !== undefined) return String(x.dictLabel)
          if (x.value !== undefined) return String(x.value)
          return ''
        }
        return String(x)
      }
      if (Array.isArray(v)) return v.map(one).filter(s => s !== '').join('、')
      return one(v)
    },

    /** 金额类字段加千分位（el-input-number） */
    fmtNumber(field, n) {
      const f = this.schemaFields.find(x => x.__vModel__ === field)
      const tag = f && f.__config__ ? f.__config__.tag : ''
      if (tag === 'el-input-number' || tag === 'design-amount') {
        return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
      }
      return String(n)
    },

    builtin(name) {
      const d = this.data || {}
      switch (name) {
        case '$submitter': return d.submitter || ''
        case '$submitterDept': return d.submitterDept || ''
        case '$submitTime': return this.fmt(d.submitTime)
        case '$finishTime': return this.fmt(d.finishTime)
        case '$businessNo': return d.businessNo || ''
        case '$templateName': return d.printTemplate ? d.printTemplate.name || '' : ''
        case '$instanceStatus': return d.instanceStatus || ''
        default: return ''
      }
    },

    /**
     * 解析表单数据。
     *
     * 实测形状（`IBizFormService.getBizForm` 的返回）：
     * ```
     * BizForm { id, title, templateId, createTime, ..., formData: "<字符串>" }
     *   字符串 JSON.parse 后 -> {
     *     formData: { formRef, formModel, fields: [ { __config__: {label, span}, __vModel__ } ] },
     *     valData:  { 字段__vModel__ -> 值 }
     *   }
     * ```
     * ⚠ 踩过：`formData` 是**字符串**不是对象，只按对象递归会一个字段都取不到（页面上字段全空）。
     * 这里刻意不假设形状：字符串就解析，取 `valData` 作值、`fields` 作标签与顺序，
     * 并把 BizForm 自身的标量属性（id/title/createTime…）也并进值里当兜底。
     */
    applyFormData(raw) {
      this.formValues = {}
      this.formLabels = {}
      this.schemaFields = []
      if (!raw) return

      let payload = raw
      // ① formData 可能是 JSON 字符串（当前就是）
      if (typeof payload === 'string') {
        try {
          payload = JSON.parse(payload)
        } catch (e) {
          payload = null
        }
      }
      if (payload && typeof payload === 'object' && typeof payload.formData === 'string') {
        try {
          payload = JSON.parse(payload.formData)
        } catch (e) {
          /* 解析失败就退回 BizForm 自身 */
        }
      }

      // ② 值：优先 valData
      const values = {}
      if (payload && typeof payload === 'object') {
        if (payload.valData && typeof payload.valData === 'object') {
          Object.assign(values, payload.valData)
        }
        // 有些形态直接把值平铺在顶层
        Object.keys(payload).forEach(k => {
          const v = payload[k]
          if (v === null || typeof v !== 'object') {
            if (values[k] === undefined) values[k] = v
          }
        })
      }
      // ③ 兜底：BizForm 自身的标量属性
      if (raw && typeof raw === 'object') {
        Object.keys(raw).forEach(k => {
          const v = raw[k]
          if (v === null || typeof v !== 'object') {
            if (values[k] === undefined) values[k] = v
          }
        })
      }
      this.formValues = values

      // ④ schema：字段标签与顺序
      const fields = (payload && payload.formData && payload.formData.fields) || []
      if (Array.isArray(fields)) {
        this.schemaFields = fields.filter(f => f && f.__vModel__ && !this.isLayoutOnly(f))
        const labels = {}
        this.schemaFields.forEach(f => {
          const c = f.__config__ || {}
          labels[f.__vModel__] = c.printLabel || c.label || f.__vModel__
        })
        this.formLabels = labels
        this.buildTranslateMaps(this.schemaFields)
      }
    },

    /**
     * 建"值 → 中文"的翻译表。
     *
     * 表单里存的是**原始值**（`leave` / `normal` / `true`），打印件上必须显示中文。
     * 这些标签就在 schema 自身里，不必再查字典接口：
     *   · 选项类（select / radio / checkbox）→ 字段的 `__slot__.options`
     *   · 开关类（el-switch）             → `active-value/active-text`、`inactive-value/inactive-text`
     */
    buildTranslateMaps(fields) {
      const optMap = {}
      const swMap = {}
      fields.forEach(f => {
        const c = f.__config__ || {}
        const model = f.__vModel__
        const opts = f.__slot__ && f.__slot__.options
        if (Array.isArray(opts) && opts.length) {
          const m = {}
          opts.forEach(o => {
            if (o && o.value !== undefined) m[String(o.value)] = o.label
          })
          optMap[model] = m
        }
        if (c.tag === 'el-switch') {
          const m = {}
          if (f['active-value'] !== undefined) m[String(f['active-value'])] = f['active-text'] || '是'
          if (f['inactive-value'] !== undefined) m[String(f['inactive-value'])] = f['inactive-text'] || '否'
          if (Object.keys(m).length) swMap[model] = m
        }
      })
      this.optionMap = optMap
      this.switchMap = swMap
    },

    /** 纯排版类字段不进基本信息表（分组标题/说明文字/按钮等） */
    isLayoutOnly(f) {
      const tag = (f.__config__ && f.__config__.tag) || ''
      return ['design-section', 'design-text', 'el-button', 'el-divider'].indexOf(tag) >= 0
    },

    /** 内置系统模板：按表单 schema 自动排行（每行 3 个列单位） */
    rowsFromSchema() {
      const rows = []
      let cur = { cells: [], used: 0 }
      this.schemaFields.forEach(f => {
        const span = Number((f.__config__ && f.__config__.span) || 24)
        const units = span >= 24 ? 3 : span >= 12 ? 2 : 1
        if (cur.used + units > 3 && cur.cells.length) {
          rows.push(cur)
          cur = { cells: [], used: 0 }
        }
        cur.cells.push({
          label: this.formLabels[f.__vModel__] || f.__vModel__,
          field: f.__vModel__,
          span: units
        })
        cur.used += units
      })
      if (cur.cells.length) rows.push(cur)
      return rows
    },

    /** 字段中文标签（配置了 field_map 时用 label 已够，这里作为兜底展示用） */
    labelOf(field) {
      return this.formLabels[field] || field
    },

    /* ---------- 展示工具 ---------- */
    cn(n) {
      const s = ['', '①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩',
        '⑪', '⑫', '⑬', '⑭', '⑮', '⑯', '⑰', '⑱', '⑲', '⑳']
      return s[n] || ('(' + n + ')')
    },

    fmt(t) {
      if (!t) return ''
      const d = new Date(t)
      if (isNaN(d.getTime())) return String(t)
      const p = x => (x < 10 ? '0' + x : '' + x)
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
        ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
    },

    fileSize(bytes) {
      const n = Number(bytes)
      if (!n || n < 0) return '—'
      if (n < 1024) return n + ' B'
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
      return (n / 1024 / 1024).toFixed(2) + ' MB'
    }
  },
  beforeDestroy() {
    this.releaseSignUrls()
  }
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/print-a4.scss';
</style>
