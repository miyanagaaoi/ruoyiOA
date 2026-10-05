<template>
  <div class="app-container erp-doc-list">
    <!--
      单据列表壳（B4 §8.3；D12）。8 类单据**共用**这一个组件：
      列定义、筛选、状态标签、操作列显隐、下推、附件、变更历史、打印入口全部由
      `kind`（doc-kinds 的一项）驱动。页面侧只写 `<doc-list-shell :kind="kind" />`。
      ⚠ 服务端才是权限与数据范围的真防线（AC-79/AC-80）；这里的显隐只改善体验。
    -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item v-for="f in kind.filters" :key="f.prop" :label="f.label">
        <el-input
          v-if="f.kind === 'input'"
          v-model="queryParams[f.prop]"
          :placeholder="f.placeholder || '请输入' + f.label"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
        <el-select
          v-else-if="f.kind === 'select'"
          v-model="queryParams[f.prop]"
          :placeholder="'全部'"
          clearable
          filterable
          style="width: 180px"
        >
          <el-option
            v-for="opt in filterOptions(f)"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-date-picker
          v-else-if="f.kind === 'date-range'"
          v-model="dateRange"
          type="daterange"
          value-format="yyyy-MM-dd"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          v-hasPermi="[permOf('add')]"
          @click="handleAdd"
          >新增{{ kind.label }}</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          :loading="exporting"
          v-hasPermi="[permOf('export')]"
          @click="handleExport"
          >导出</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <p class="doc-subtitle">{{ kind.subtitle }}</p>
    <p v-if="options.failed.length || dictFailed.length" class="doc-option-warn">
      部分下拉/字典未能加载：{{ options.failed.concat(dictFailed).join('；') }}（其余筛选不受影响）
    </p>

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="list" border size="mini">
      <el-table-column
        v-for="col in kind.columns"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
        :show-overflow-tooltip="!!col.showOverflowTooltip"
      >
        <template slot-scope="scope">
          <el-button
            v-if="col.kind === 'link'"
            type="text"
            size="mini"
            v-hasPermi="[permOf('query')]"
            @click="handleDetail(scope.row)"
            >{{ scope.row[col.prop] }}</el-button
          >
          <DocStatusTag v-else-if="col.kind === 'status'" :status="scope.row.status" />
          <el-tag v-else-if="col.kind === 'posted'" :type="scope.row.posted === '1' ? 'success' : 'info'" size="mini">
            {{ scope.row.posted === '1' ? '已过账' : '未过账' }}
          </el-tag>
          <dict-tag v-else-if="col.kind === 'dict'" :options="dictOptions(col.dict)" :value="scope.row[col.prop]" />
          <span v-else-if="col.kind === 'money'">{{ moneyText(scope.row[col.prop]) }}</span>
          <span v-else-if="col.kind === 'qty'">{{ formatQty(scope.row[col.prop]) }}</span>
          <span v-else-if="col.kind === 'date'">{{ scope.row[col.prop] || '—' }}</span>
          <span v-else-if="col.kind === 'datetime'">{{ scope.row[col.prop] || '—' }}</span>
          <span v-else>{{ isEmpty(scope.row[col.prop]) ? '—' : scope.row[col.prop] }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="300" align="center" fixed="right">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            icon="el-icon-view"
            v-hasPermi="[permOf('query')]"
            @click="handleDetail(scope.row)"
            >详情</el-button
          >
          <el-button
            v-if="canEditRow(scope.row)"
            type="text"
            size="mini"
            icon="el-icon-edit"
            v-hasPermi="[permOf('edit')]"
            @click="handleEdit(scope.row)"
            >修改</el-button
          >
          <el-button
            v-for="act in actionsOf(scope.row)"
            :key="act.code"
            type="text"
            size="mini"
            :class="{ 'erp-action-danger': act.danger }"
            :disabled="act.disabled"
            :title="act.disabledReason"
            v-hasPermi="[act.perm]"
            @click="handleAction(scope.row, act)"
            >{{ act.label }}</el-button
          >
          <el-button
            v-if="pushable(scope.row)"
            type="text"
            size="mini"
            icon="el-icon-right"
            v-hasPermi="[pushPerm()]"
            @click="handlePush(scope.row)"
            >下推</el-button
          >
          <el-button type="text" size="mini" icon="el-icon-paperclip" @click="openAttachments(scope.row)">附件</el-button>
          <el-button
            v-if="kind.changeLogs !== false"
            type="text"
            size="mini"
            icon="el-icon-time"
            @click="openLogs(scope.row)"
            >变更历史</el-button
          >
          <!--
            打印入口：**静态**置灰 + 明确提示（队长裁决 B'）。
            为什么不是"按模板有无判断"：`$openPrintPreview` 无返回值/无回调，且模板接口口径是
            "含系统默认、永远不返回空" ⇒ "无可用版式"不是可观察状态（见 notes/09a §9.1）。
            集成期把"ERP 单据 → workflow businessId"接线完成后，t13 把 `printReady` 翻 true 即可。
          -->
          <el-button
            type="text"
            size="mini"
            icon="el-icon-printer"
            :disabled="!printReady"
            :title="printReady ? '' : '打印未接入（待集成期接线）'"
            v-hasPermi="[permOf('print')]"
            @click="handlePrint(scope.row)"
            >打印</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <DocPushDialog :visible.sync="pushVisible" :kind="kind" :row="current" @pushed="handlePushed" />
    <DocActionDialog :visible.sync="actionVisible" :kind="kind" :action="currentAction" :row="current" @done="getList" />

    <el-drawer :title="'附件 · ' + kind.label" :visible.sync="attachVisible" size="45%" append-to-body>
      <div class="drawer-body">
        <DocAttachmentPanel
          v-if="attachVisible"
          :object-type="kind.attachmentObjectType"
          :object-id="current ? current.id : ''"
          :write-perm="permOf('edit')"
        />
      </div>
    </el-drawer>

    <el-drawer :title="'变更历史 · ' + kind.label" :visible.sync="logVisible" size="50%" append-to-body>
      <div class="drawer-body">
        <DataLoadError v-if="logError" :text="logError" :cause="logErrorCause" @retry="loadLogs" />
        <el-timeline v-else v-loading="logLoading">
          <el-timeline-item
            v-for="log in logs"
            :key="log.id"
            :timestamp="log.createTime"
            placement="top"
          >
            <div class="log-line">
              <strong>{{ log.fieldName }}</strong>
              <span class="log-operator">{{ log.operatorName || '—' }}</span>
              <span v-if="log.note" class="log-note">备注：{{ log.note }}</span>
            </div>
            <div class="log-values">
              <span class="log-old">{{ isEmpty(log.oldValue) ? '（空）' : log.oldValue }}</span>
              <span class="log-arrow">→</span>
              <span class="log-new">{{ isEmpty(log.newValue) ? '（空）' : log.newValue }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </div>
</template>

<script>
/**
 * 单据列表壳（B4 §8.3；design D12）。
 *
 * 一个组件撑起 8 个列表页，页面侧只写 `<doc-list-shell :kind="kind" />`：
 *   · 列 / 筛选 / 行项 / 状态标签 / 下推 / 附件 / 打印 / 变更历史全部读 `kind` 配置；
 *   · 操作列的显隐 = 状态机白名单（`doc-rules.visibleActions`）× 权限点（`v-hasPermi`）。
 *
 * 三个必须知道的边界：
 *   ① 权限与数据范围**以服务端为准**（`v-hasPermi` 只决定按不按得动、看不看得见）；
 *   ② 打印入口默认走平台已有的 B2 打印预览（`this.$openPrintPreview(row.id)`），
 *      可用 `printHandler` 覆盖 —— 进销存单据的打印数据源由 t9b/集成接线时决定；
 *   ③ 新增/修改/下推后的跳转用 `kind.formPath`（菜单 `component` 指向 `erp/doc/form` 或
 *      t9b 写的薄页面），菜单未挂上前路由不存在属预期（见 notes §6）。
 */
// ⚠ 必须用**命名空间/具名**导入：`@/api/erp/doc` 只有命名导出，
//   写成 `import api from ...` 会拿到 undefined，调用时同步抛错 ⇒ 请求发不出去、页面静默空表。
import * as api from '@/api/erp/doc'
import rules from './doc-rules'
import docKinds from './doc-kinds'
import { moneyText } from './erp-amounts'
import { describeError } from '@/utils/errorMessage'
import { emptySelectOptions, dictOptionsOf, filterSelectOptions, loadDictsInto, loadSelectsInto } from './doc-selects'
import DataLoadError from '@/components/DataLoadError'
import DocStatusTag from './components/DocStatusTag'
import DocActionDialog from './components/DocActionDialog'
import DocPushDialog from './components/DocPushDialog'
import DocAttachmentPanel from './components/DocAttachmentPanel'

export default {
  name: 'DocListShell',
  components: { DataLoadError, DocStatusTag, DocActionDialog, DocPushDialog, DocAttachmentPanel },
  props: {
    // 单据类型配置（doc-kinds 的一项）
    kind: { type: Object, required: true },
    // 覆盖默认打印入口（如后续接进销存自己的打印数据源）
    printHandler: { type: Function, default: null }
  },
  data() {
    return {
      loading: false,
      exporting: false,
      showSearch: true,
      list: [],
      total: 0,
      dateRange: [],
      queryParams: this.buildQuery({}),
      // 下拉与字典状态（三个键显式声明，逻辑由 ./doc-selects 提供）
      options: emptySelectOptions(),
      dictData: {},
      dictFailed: [],
      loadError: '',
      loadErrorCause: '',
      current: null,
      currentAction: null,
      pushVisible: false,
      actionVisible: false,
      attachVisible: false,
      logVisible: false,
      logs: [],
      logLoading: false,
      logError: '',
      logErrorCause: ''
    }
  },
  computed: {
    /** 打印是否已接入（静态信号，见模板注释与 notes/09a §9.1）。 */
    printReady() {
      return this.kind.printReady === true
    }
  },
  created() {
    this.loadDictsOfKind()
    this.loadSelects()
    this.getList()
  },
  methods: {
    /** 列表查询条件（按 kind 的 filters 建键；`pageNum/pageSize` 由分页组件维护）。 */
    buildQuery(extra) {
      const base = {
        pageNum: 1,
        pageSize: 10,
        keyword: undefined,
        status: undefined,
        beginDocDate: undefined,
        endDocDate: undefined
      }
      // 各单据专有筛选键（warehouseId / inType / customerId / handlerName…）也一并声明，
      // 这样 resetQuery 能真正把「上一次查询」清干净（Vue2 里新增键不是响应式的）。
      ;(this.kind.filters || []).forEach((f) => {
        if (f.kind !== 'date-range') {
          base[f.prop] = undefined
        }
      })
      return Object.assign(base, extra)
    },
    /** 筛选里声明了 `options` 的下拉（供应商/客户/仓库）。 */
    selectOptions(field) {
      const key = field.options
      if (!key) {
        return []
      }
      const list = this.options[key] || []
      return list.map((item) => ({ value: item.id, label: item.shortName || item.name || item.code }))
    },
    /** 筛选下拉的选项（`{value,label}`）：字典 / 状态 / 主数据三类来源收敛在 doc-selects 一处。 */
    filterOptions(field) {
      return filterSelectOptions(this, field)
    },
    /** 字典选项（原始行；表格 `<dict-tag>` 用）。 */
    dictOptions(name) {
      return dictOptionsOf(this, name)
    },
    /** 字典筛选/列（按需加载，避免每次进列表都拉 3 个用不上的字典）。 */
    loadDictsOfKind() {
      loadDictsInto(this, this.kind.filters.concat(this.kind.headerFields, this.kind.columns))
    },
    loadSelects() {
      loadSelectsInto(this)
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      const query = Object.assign({}, this.queryParams, {
        beginDocDate: this.dateRange && this.dateRange.length ? this.dateRange[0] : undefined,
        endDocDate: this.dateRange && this.dateRange.length ? this.dateRange[1] : undefined
      })
      api
        .listDocs(this.kind.code, query)
        .then((res) => {
          this.list = (res && res.rows) || []
          this.total = (res && res.total) || 0
          this.loading = false
        })
        .catch((err) => {
          const d = describeError(err)
          this.list = []
          this.total = 0
          this.loadError = d.text
          this.loadErrorCause = d.cause
          this.loading = false
        })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.dateRange = []
      this.queryParams = this.buildQuery({})
      this.handleQuery()
    },
    permOf(action) {
      return rules.permOf(this.kind, action)
    },
    pushPerm() {
      const chain = rules.pushChainOf(this.kind)
      return chain ? chain.perm : ''
    },
    canEditRow(row) {
      return rules.canEdit(this.kind, row)
    },
    actionsOf(row) {
      return rules.visibleActions(this.kind, row.status, true)
    },
    pushable(row) {
      return rules.canPush(this.kind, row).ok
    },
    isEmpty(v) {
      return v === null || v === undefined || v === ''
    },
    formatQty(v) {
      return rules.formatQty(v)
    },
    moneyText(v) {
      return moneyText(v)
    },
    /**
     * 新增 / 修改 / 详情都走 `kind.formPath`，用 query 区分模式。
     *
     * ⚠ query 里**带上 `kind`**：这样"共用 `erp/doc/form` + 菜单 query 传 kind"与
     *   "t9b 的专用薄页 `erp/<doc>/form`"两种挂法都能工作（薄页忽略该参数）。
     *   不带的话，共用表单页会因为解析不到单据类型而落到"未指定单据类型"提示。
     */
    handleAdd() {
      this.$router.push({ path: this.kind.formPath, query: { kind: this.kind.code, mode: 'add' } })
    },
    handleEdit(row) {
      this.$router.push({ path: this.kind.formPath, query: { kind: this.kind.code, id: row.id, mode: 'edit' } })
    },
    handleDetail(row) {
      this.$router.push({ path: this.kind.formPath, query: { kind: this.kind.code, id: row.id, mode: 'view' } })
    },
    handleAction(row, act) {
      this.current = row
      this.currentAction = act
      this.actionVisible = true
    },
    handlePush(row) {
      this.current = row
      this.pushVisible = true
    },
    /** 下推成功后跳到下游编辑页（把上游带过来的字段留给下游表单自己拉）。 */
    handlePushed(created) {
      const chain = rules.pushChainOf(this.kind)
      if (!created || !chain) {
        this.getList()
        return
      }
      const target = docKinds.getKind(chain.to)
      this.getList()
      if (target) {
        this.$router.push({
          path: target.formPath,
          query: { kind: target.code, id: created.id, mode: 'edit' }
        })
      }
    },
    openAttachments(row) {
      this.current = row
      this.attachVisible = true
    },
    openLogs(row) {
      this.current = row
      this.logVisible = true
      this.loadLogs()
    },
    loadLogs() {
      if (!this.current) {
        return
      }
      this.logLoading = true
      this.logError = ''
      this.logErrorCause = ''
      api
        .listChangeLogs(this.kind.code, this.current.id, { pageNum: 1, pageSize: 200 })
        .then((res) => {
          // 返回形态两种都可能：入库/出库/采购是 AjaxResult（`data` 是数组），B3 合同是分页 TableDataInfo（`rows`）
          this.logs = (res && res.rows) || (res && Array.isArray(res.data) ? res.data : []) || []
          this.logLoading = false
        })
        .catch((err) => {
          const d = describeError(err)
          this.logs = []
          this.logError = d.text
          this.logErrorCause = d.cause
          this.logLoading = false
        })
    },
    handleExport() {
      const query = Object.assign({}, this.queryParams, {
        beginDocDate: this.dateRange && this.dateRange.length ? this.dateRange[0] : undefined,
        endDocDate: this.dateRange && this.dateRange.length ? this.dateRange[1] : undefined
      })
      const stamp = this.parseTime(new Date(), '{y}{m}{d}{h}{i}{s}')
      this.exporting = true
      api
        .exportDocs(this.kind.code, query, this.kind.label + '_' + stamp + '.xlsx')
        .then(() => {
          this.exporting = false
        })
        .catch(() => {
          this.exporting = false
        })
    },
    /** 打印入口：默认复用平台 B2 的打印预览（按 businessId 聚合打印数据）。 */
    handlePrint(row) {
      if (this.printHandler) {
        this.printHandler(row)
        return
      }
      this.$openPrintPreview(row.id)
    }
  }
}
</script>

<style lang="scss" scoped>
.erp-doc-list .doc-subtitle {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.doc-option-warn {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-warning);
}
.erp-action-danger {
  color: var(--oa-color-error);
}
.drawer-body {
  padding: 0 var(--oa-space-md) var(--oa-space-md);
}
.log-line {
  display: flex;
  gap: var(--oa-space-xs);
  font: var(--oa-font-body-sm);
}
.log-operator {
  color: var(--oa-color-ink-subtle);
}
.log-note {
  color: var(--oa-color-ink-muted);
}
.log-values {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-muted);
  word-break: break-all;
}
.log-arrow {
  margin: 0 var(--oa-space-xxs);
}
</style>
