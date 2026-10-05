<template>
  <div class="print-page" :class="{ 'is-embedded': embedded }">
    <!-- 屏幕上的工具条：打印时整条隐藏（.no-print）。
         在浮层 iframe 里打开（embedded=1）时隐藏「关闭」——关闭由浮层的 × 负责。 -->
    <!-- 屏幕上的工具条：打印时整条隐藏（.no-print）。
         在浮层 iframe 里打开（embedded=1）时隐藏「关闭」——关闭由浮层的 × 负责。
         ⚠ 这里**刻意不绑定 `paperBoxStyle`**：那个对象带 `minHeight: 297mm`（纸样高度），
           绑到工具条上会让工具条变成一个 297mm 高的白色 sticky 块，
           z-index 10 直接盖住纸面 —— 现象是"打印预览只剩左边一条、大片空白、按钮跑到底部"（踩过）。
           纸样尺寸只给 `.print-paper`。 -->
    <div class="print-toolbar no-print">
      <span class="tb-title">{{ title }}</span>
      <span class="tb-hint">{{ paperHint }} · {{ contentHint }} · 共 {{ pageCount }} 页</span>
      <el-button type="primary" size="mini" icon="el-icon-printer" :loading="printing" @click="doPrint">打印</el-button>
      <el-button size="mini" icon="el-icon-refresh" @click="load">刷新数据</el-button>
      <el-button v-if="!embedded" size="mini" @click="closeWin">关闭</el-button>
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
      <div v-if="data" class="print-paper" :style="paperBoxStyle">
        <!-- 水印（打印件防伪） -->
        <div v-if="data.watermarkText" class="p-watermark no-print-none">{{ data.watermarkText }}</div>

        <!-- A. 抬头区 -->
        <div class="p-title">{{ title }}</div>
        <!-- 抬头：只保留表单侧信息（不打印模板名等内部元数据）
             Logo 在左侧；**未配置时整个元素不渲染**（不出空白占位、不出破图图标） -->
        <div class="p-headline">
          <div class="p-hl-left">
            <img v-if="logoUrl" class="p-logo" :src="logoUrl" alt="Logo" />
          </div>
          <div class="p-hl-right">单据编号：{{ data.businessNo || '—' }}</div>
        </div>

        <!-- B. 字段区（由 field_map 决定行列；带标题的区输出区标题） -->
        <div v-for="(sec, si) in fieldSections" :key="'sec' + si" class="p-field-section">
          <div v-if="sec.title" class="p-section-title">{{ sec.title }}</div>
          <table class="p-base">
            <tbody>
              <tr v-for="(row, ri) in sec.rows" :key="'r' + si + '-' + ri">
                <template v-for="(cell, ci) in row.cells">
                  <th :key="'h' + si + '-' + ri + '-' + ci">{{ cell.label }}</th>
                  <td :key="'d' + si + '-' + ri + '-' + ci" :colspan="cellSpan(cell)">{{ cellValue(cell) }}</td>
                </template>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- C. 签批栏区（模板 showSignature='1' 才输出；与"是否内置"无关 —— AC-62） -->
        <!--
          AC-17：签批栏数量与顺序 == 流程节点顺序
          AC-18：会签节点多人时，**同栏内按人分行**展示，不串行

          两条渲染路径：
            · 版式里声明了固定栏目（`sign.columns`，fund / matter / payment 三套内置版式）
              → 一栏一个签名区，按**节点名**匹配流程节点；匹配不到也出栏（留空供手写）。
              纸质表单上那几栏是印好的，不出栏才是错的。
            · 没有固定栏目（contract 与自定义模板）
              → 按 `nodeIndex`（后端给的"节点序号"，同节点多人共用一个序号）分组，
                一个节点一栏、栏内每人一行 —— 与升级前逐字一致。
        -->
        <template v-if="showSignature">
          <template v-if="signColumnList.length">
            <div class="p-section-title">{{ signTitle }}</div>
            <div v-for="(col, i) in signColumnList" :key="'sc' + i" class="sign-block">
              <div class="sb-head">{{ cn(i + 1) }} {{ col.label }}</div>
              <table class="sb-table">
                <tbody>
                  <!-- 由内置变量直接取值的栏（如 payment 的「制单人 = 发起人」） -->
                  <tr v-if="col.field">
                    <td class="sb-c-user" colspan="4">{{ valueOf(col.field) || '—' }}</td>
                  </tr>
                  <tr v-for="(p, pi) in col.nodes" :key="'p' + pi">
                    <td class="sb-c-dept"><span class="sb-label">接收单位：</span>{{ p.deptName || '—' }}</td>
                    <td class="sb-c-user"><span class="sb-label">接收人：</span>{{ p.assigneeName || '—' }}</td>
                    <td class="sb-c-time"><span class="sb-label">签收时间：</span>{{ fmt(p.receiveTime) }}</td>
                    <td v-if="showComment" class="sb-c-cmt">
                      <span class="sb-label">处理意见：</span>{{ p.comment || '' }}
                    </td>
                    <td class="sb-c-sign">
                      <img v-if="signUrls[p.taskId]" :src="signUrls[p.taskId]" alt="签名" />
                      <span v-else class="sb-sign-empty">（签名）</span>
                    </td>
                  </tr>
                  <tr v-if="!col.field && !col.nodes.length">
                    <td colspan="4" class="sb-empty">（本栏暂无办理记录，供手写签名）</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
          <template v-else>
            <div class="p-section-title">{{ signTitle }}</div>
            <div v-if="!signBlocks.length" class="p-empty-tip">（暂无办理记录）</div>
            <div v-for="(blk, i) in signBlocks" :key="'n' + i" class="sign-block">
              <div class="sb-head">{{ cn(i + 1) }} {{ blk.nodeName || '（未命名节点）' }}</div>
              <table class="sb-table">
                <tbody>
                  <tr v-for="(p, pi) in blk.people" :key="'p' + pi">
                    <td class="sb-c-dept"><span class="sb-label">接收单位：</span>{{ p.deptName || '—' }}</td>
                    <td class="sb-c-user"><span class="sb-label">接收人：</span>{{ p.assigneeName || '—' }}</td>
                    <td class="sb-c-time"><span class="sb-label">签收时间：</span>{{ fmt(p.receiveTime) }}</td>
                    <td v-if="showComment" class="sb-c-cmt">
                      <span class="sb-label">处理意见：</span>{{ p.comment || '' }}
                    </td>
                    <td class="sb-c-sign">
                      <img v-if="signUrls[p.taskId]" :src="signUrls[p.taskId]" alt="签名" />
                      <span v-else class="sb-sign-empty">（签名）</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </template>

        <!-- C2. 抄送栏（模板 showCcNode='1' 且流程存在抄送节点才输出 —— REQ-PRINT-014） -->
        <template v-if="showCcNode && ccRows.length">
          <div class="p-section-title">抄送</div>
          <table class="p-base p-cc">
            <thead>
              <tr>
                <th style="width: 40mm">抄送节点</th>
                <th style="width: 30mm">抄送人</th>
                <th style="width: 40mm">抄送时间</th>
                <th>阅办状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(cc, i) in ccRows" :key="'cc' + i">
                <td>{{ cc.nodeName || '—' }}</td>
                <td>{{ cc.handlerName || '—' }}</td>
                <td>{{ fmt(cc.sendTime) }}</td>
                <td>{{ cc.readFlag === '1' ? '已阅' : '未阅' }}</td>
              </tr>
            </tbody>
          </table>
        </template>

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
import { amountWithUpper, formatAmount, toChineseUpper } from '@/utils/money'
import { describeError } from '@/utils/errorMessage'
import printLayout from './printLayout'
import printGate from './printGate'

/** 运行时注入的 @page 规则的元素 id（见 applyPageRule） */
const PRINT_PAGE_RULE_ID = 'oa-print-page-rule'

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
      /** 表单 schema 的字段顺序（内置 contract 版式据此自动排版） */
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
    /** 是否在打印浮层的 iframe 中打开（浮层自带关闭，故页内不再显示「关闭」） */
    embedded() {
      return this.$route.query.embedded === '1'
    },
    title() {
      if (this.data && this.data.title) return this.data.title
      return '集团合同类文件流转审批单'
    },
    tpl() {
      return (this.data && this.data.printTemplate) || {}
    },
    /**
     * 是否为**内置版式**（库中无对应记录时后端返回 id=null 的兜底模板）。
     * 注意：它只用于"排版路径"的判断（见 useBuiltinLabelLayout），
     * **不再**用于决定签批栏/附件清单是否出栏 —— 那两件事只看配置值（AC-62）。
     */
    isBuiltinTpl() {
      return !this.tpl.id
    },
    /**
     * 签批栏 / 附件清单 / 抄送栏：**一律读生效模板的配置值**，与"是否内置"解耦。
     * （升级前是 `!isBuiltinTpl && showSignature === '1'`：内置模板被强制关掉签批栏，
     *   而《集团资金审批单》的版式核心恰恰就是签批栏 —— PRD 风险 R-3。）
     */
    showSignature() {
      return printLayout.shouldShowSignature(this.tpl)
    },
    showComment() {
      return printLayout.shouldShowComment(this.tpl)
    },
    showAttachment() {
      return printLayout.shouldShowAttachment(this.tpl)
    },
    showCcNode() {
      return printLayout.shouldShowCcNode(this.tpl)
    },
    footerNote() {
      return this.tpl.footerNote || ''
    },
    /** 排版参数（纸张 / 方向 / 版心高度），页数测算与提示都用它 */
    metrics() {
      return printLayout.layoutMetrics(this.tpl)
    },
    /** 工具条提示：纸张与方向（原先硬编码 "A4 纵向"） */
    paperHint() {
      return this.metrics.paper + (this.metrics.landscape ? ' 横向' : ' 纵向')
    },
    /** 工具条提示：这一张实际会打出哪些区（让"配置是否生效"一眼可见） */
    contentHint() {
      const parts = ['表单信息']
      if (this.showSignature) parts.push('签批栏')
      if (this.showCcNode && this.ccRows.length) parts.push('抄送栏')
      if (this.showAttachment) parts.push('附件清单')
      return parts.join(' + ')
    },
    /**
     * 屏幕上的纸样尺寸（宽 × 最小高）。**跟着生效模板的纸张与方向走**（REQ-PRINT-014）：
     * 升级前 `.print-paper` 的 `width: 210mm; min-height: 297mm` 是写死的 A4 纵向，
     * 于是"配了 A3 横向"只改了页数测算、纸样还是 A4 —— 打印出来仍被 @page 裁成 A4。
     * 这里用行内样式覆盖，`print-a4.scss` 里的那两个值退化为"未配置时的默认"。
     *
     * ⚠ **只绑给 `.print-paper`**：里面的 `minHeight` 是纸样高度，
     *   绑到工具条上会把工具条撑成一张纸那么高并盖住纸面（踩过，见模板里的注释）。
     */
    paperBoxStyle() {
      return printLayout.paperBoxStyle(this.tpl)
    },
    /** 抬头左侧的 Logo（未配置为空串 → 元素不渲染） */
    logoUrl() {
      return printLayout.logoUrl(this.tpl.logoFileId, process.env.VUE_APP_BASE_API)
    },
    /**
     * 字段区。三条路径（顺序即优先级）：
     *   1. 新增的 fund / matter / payment 内置版式 → **以版式常量为准**（为固定纸质表单定制的栏目）；
     *   2. 内置 contract 版式（存量模板的默认值）→ 保持升级前的行为：
     *      有表单 schema 就**按 schema 自动排版**（否则自定义字段会整片消失），否则用版式常量；
     *   3. 自定义模板 → 用它的 field_map，没有就按 schema 排版。
     * 全部走 `printLayout.fieldSections`，与前端用例共用同一份判定。
     */
    fieldSections() {
      const layoutSections = printLayout.fieldSections(this.tpl.fieldMap)
      if (printLayout.useBuiltinLabelLayout(this.tpl)) {
        return layoutSections.length ? layoutSections : this.schemaSection()
      }
      if (this.isBuiltinTpl && this.schemaFields.length) {
        return this.schemaSection()
      }
      return layoutSections.length ? layoutSections : this.schemaSection()
    },
    /** 签批栏的固定栏目（含按节点名匹配出的节点）；没有固定栏目时为空表 → 走动态出栏 */
    signColumnList() {
      return printLayout.signColumns(this.tpl.fieldMap).map(col => {
        return Object.assign({}, col, { nodes: printLayout.matchNodesForColumn(this.nodes, col) })
      })
    },
    /** 签批栏区标题（版式没写就用升级前的固定标题，保持零回归） */
    signTitle() {
      const sec = printLayout.signSection(this.tpl.fieldMap)
      return (sec && sec.title) || '公文接收及处理'
    },
    nodes() {
      return (this.data && this.data.nodes) || []
    },
    /**
     * 签批栏（动态出栏）：一个**流程节点**一栏，栏内每人一行（AC-17 / AC-18）。
     *
     * 后端给每个任务记录都带 `nodeIndex`（同节点的多人共用一个序号），这里按它分组。
     * 兼容没有 nodeIndex 的旧数据：退化为"相邻同名节点合并"。
     */
    signBlocks() {
      const list = this.nodes
      const blocks = []
      let cur = null
      list.forEach(n => {
        const key = n.nodeIndex !== undefined && n.nodeIndex !== null
          ? 'i' + n.nodeIndex
          : 'k' + (n.taskDefKey || '') + (n.nodeName || '')
        if (!cur || cur.key !== key) {
          cur = { key: key, nodeName: n.nodeName, people: [] }
          blocks.push(cur)
        }
        cur.people.push(n)
      })
      return blocks
    },
    ccRows() {
      return (this.data && this.data.ccNodes) || []
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
            this.applyPageRule()
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

    /**
     * 打印：先测页数 → **先写留痕** → 成功才调起打印（AC-67 的硬门禁）。
     *
     * 升级前是先 `window.print()` 再补写留痕、写失败只 warning 一下 ——
     * 打印动作一旦发生就物理上无法回滚，审计要求的"必留痕"因此形同虚设。
     * 现在留痕失败时**不打印**并给出明确提示（提示里说明联系管理员）。
     * 门禁逻辑抽在 `printGate.js` 里，因此"失败路径不得调起打印"是可断言的。
     */
    doPrint() {
      this.measurePages()
      this.printing = true
      printGate.printAfterLogging({
        writeLog: () => this.writeLog(),
        // window.print() 在多数浏览器是阻塞的，返回时对话框已关闭
        print: () => window.print()
      }).then(r => {
        this.printing = false
        if (!r.printed) {
          const msg = printGate.logFailureMessage()
          if (this.$modal && this.$modal.msgError) {
            this.$modal.msgError(msg)
          } else {
            window.alert(msg)
          }
        }
      })
    },

    /**
     * 打印留痕：只传业务信息与页数，打印人与时间由服务端决定。
     *
     * ⚠ **必须把失败抛出去**：这个 Promise 的 reject 是"阻断打印"的唯一信号，
     * 在这里 catch 掉就等于把硬门禁改回"尽力而为"了（升级前的问题就在这里）。
     */
    writeLog() {
      if (!this.businessId) return Promise.resolve()
      return addPrintLog({
        businessId: this.businessId,
        procInsId: this.data ? this.data.procInsId : null,
        templateId: this.data ? this.data.templateId : null,
        printTplId: this.tpl.id || null,
        pageCount: this.pageCount,
        watermarkText: this.data ? this.data.watermarkText : null
      })
    },

    /**
     * 预分页测算（PRD 7.5：不依赖 CSS counter(pages)，避免浏览器差异）。
     *
     * 沿用"按像素测算"的既有路线，只把原先硬编码的 A4 版心高度换成按**生效模板的
     * 纸张与方向**算出来的值（REQ-PRINT-014）—— 所以 A3 横向的页数不会再按 A4 算。
     */
    measurePages() {
      const paper = this.$el && this.$el.querySelector('.print-paper')
      if (!paper) {
        this.pageCount = 1
        return
      }
      this.pageCount = printLayout.measurePageCount(paper.scrollHeight, this.tpl)
    },

    /**
     * 把生效模板的**纸张与方向**真正交给浏览器（REQ-PRINT-014 / AC-64）。
     *
     * SFC 里 `print-a4.scss` 的 `@page { size: A4 portrait }` 是**静态**的（编译期），
     * 而 `scoped` 样式也没法按运行时取值改写 —— 所以在 head 里维护一个专用 `<style>`，
     * 每次打印都按生效模板重写它。销毁时移除，避免这条全局 @page 影响别的页面。
     */
    applyPageRule() {
      const rule = printLayout.pageRuleCss(this.tpl)
      let el = document.getElementById(PRINT_PAGE_RULE_ID)
      if (!el) {
        el = document.createElement('style')
        el.id = PRINT_PAGE_RULE_ID
        document.head.appendChild(el)
      }
      el.textContent = rule
    },

    removePageRule() {
      const el = document.getElementById(PRINT_PAGE_RULE_ID)
      if (el && el.parentNode) {
        el.parentNode.removeChild(el)
      }
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
      return printLayout.parseFieldMap(raw)
    },

    /** 一个 cell 占 span 个"列单位"，每个单位 = th + td 两列 */
    cellSpan(cell) {
      const span = Number(cell && cell.span)
      if (!span || span < 1) return 1
      return span * 2 - 1
    },

    /* ---------- 取值 ---------- */
    /**
     * 一个单元格的显示值。
     *
     * `format: 'amountUpper'` 的单元格只输出**金额大写**（付款申请单的「金额大写」是独立栏目，
     * 而 design-amount 的常规渲染是"数字（人民币大写）"两者合一）。两者用同一个
     * `utils/money.toChineseUpper`，所以打印件上的数字与大写永远一致（AC-36）。
     */
    cellValue(cell) {
      if (cell && cell.format === 'amountUpper') {
        const raw = this.formValues[cell.field]
        if (raw === null || raw === undefined || raw === '') return ''
        return toChineseUpper(raw) || ''
      }
      return this.valueOf(cell && cell.field)
    },

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

    /**
     * 金额类字段的打印形态。
     *
     * design-amount（AC-36）：千分位 + 小数位 + **中文大写** ——
     *   大写与数字必须一致，所以这里用的是与表单控件同一份实现（utils/money.js），
     *   而不是在这里再写一遍换算。
     * el-input-number：只加千分位（它没有"金额"语义，不该硬塞大写）。
     */
    fmtNumber(field, n) {
      const f = this.schemaFields.find(x => x.__vModel__ === field)
      const tag = f && f.__config__ ? f.__config__.tag : ''
      if (tag === 'design-amount') {
        const decimals = f && f.decimals !== undefined && f.decimals !== null ? Number(f.decimals) : 2
        return amountWithUpper(n, isFinite(decimals) ? decimals : 2)
      }
      if (tag === 'el-input-number') {
        return formatAmount(n, { decimals: 2 })
      }
      return formatAmount(n, { decimals: 2 }) || String(n)
    },

    /** 内置变量：口径统一在 printLayout.builtinValue（含新增的 $submitterCompany） */
    builtin(name) {
      return printLayout.builtinValue(name, this.data, t => this.fmt(t))
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

    /** 按表单 schema 自动排版（每行 3 个列单位）—— 内置 contract 版式与"没配版式"时的路径 */
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

    /** 自动排版结果包装成一个无标题的字段区（与 field_map 的 sections 同构） */
    schemaSection() {
      const rows = this.rowsFromSchema()
      return rows.length ? [{ id: 'schema', title: '', rows: rows }] : []
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
    this.removePageRule()
  }
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/print-a4.scss';
</style>
