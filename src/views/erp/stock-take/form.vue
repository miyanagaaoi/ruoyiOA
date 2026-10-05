<template>
  <doc-form-shell ref="shell" :kind="kind" :id="docId" :mode="mode">
    <!--
      盘点单的**独立交互**（B4 §8.4 / tasks.md 6.3、6.4）：生成行项 → 录入实盘 → 审核。
      行项表与状态标签仍复用壳（D12）；这里只放"盘点专有"的三块：
        ① 生成条件 + 「确认生成行项」；
        ② 「保存实盘数量」（行内实盘由壳的行项表编辑）；
        ③ 审核动作按钮 + 生成的盘盈/盘亏单号展示。
    -->
    <template slot="header-extra" slot-scope="ctx">
      <el-divider content-position="left">盘点操作（生成行项 → 录入实盘 → 审核）</el-divider>

      <el-alert
        v-if="ctx.form.status === 'approved'"
        :title="resultText(ctx.form)"
        type="success"
        :closable="false"
        show-icon
        class="take-result"
      />
      <el-alert
        v-else-if="ctx.form.id"
        :title="'当前状态：' + statusLabel(ctx.form.status) + '（生成行项只在草稿态可用）'"
        type="info"
        :closable="false"
        show-icon
        class="take-result"
      />

      <el-form :inline="true" size="small" class="take-bar">
        <el-form-item label="盘点范围">
          <el-select v-model="form.takeType" :disabled="ctx.readonly || !ctx.form.id" style="width: 120px" @change="handleTakeTypeChange">
            <el-option v-for="opt in takeTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <template v-if="isPartial">
          <el-form-item label="按类型（含子树）">
            <el-cascader
              v-model="form.productTypeIds"
              :options="typeTree"
              :props="cascaderProps"
              :disabled="ctx.readonly || !ctx.form.id"
              clearable
              collapse-tags
              placeholder="可选：选商品类型子树"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="按物料">
            <el-select
              v-model="form.productIds"
              multiple
              filterable
              collapse-tags
              :disabled="ctx.readonly || !ctx.form.id"
              placeholder="可选：指定物料（优先于类型）"
              style="width: 260px"
            >
              <el-option v-for="p in products" :key="p.id" :label="p.code ? p.code + ' · ' + p.name : p.name" :value="p.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item>
          <el-button
            type="primary"
            plain
            size="mini"
            icon="el-icon-refresh"
            :loading="generating"
            :disabled="ctx.readonly || !ctx.form.id"
            v-hasPermi="[permOf('add')]"
            @click="handleGenerate"
            >确认生成行项</el-button
          >
          <el-button
            type="success"
            plain
            size="mini"
            icon="el-icon-edit-outline"
            :loading="counting"
            :disabled="ctx.readonly || !ctx.form.id || !ctx.items.length"
            v-hasPermi="[permOf('edit')]"
            @click="handleSaveCount(ctx.items)"
            >保存实盘数量</el-button
          >
          <el-button
            size="mini"
            icon="el-icon-refresh"
            :disabled="!ctx.form.id"
            @click="handleReload"
            >刷新</el-button
          >
        </el-form-item>
      </el-form>

      <p class="take-hint">{{ hint }}</p>

      <!-- 审核动作（复用壳里的动作弹窗组件与状态机口径；提交由壳底部「保存并提交」承担，这里不重复） -->
      <div v-if="ctx.form.id" class="take-actions">
        <el-button
          v-for="act in actionsOf(ctx.form.status)"
          :key="act.code"
          size="mini"
          :type="act.danger ? 'danger' : 'primary'"
          plain
          :disabled="act.disabled"
          :title="act.disabledReason"
          v-hasPermi="[act.perm]"
          @click="handleAction(act, ctx.form)"
          >{{ act.label }}</el-button
        >
      </div>

      <DocActionDialog
        :visible.sync="actionVisible"
        :kind="kind"
        :action="currentAction"
        :row="actionRow"
        @done="handleActionDone"
      />
    </template>
  </doc-form-shell>
</template>

<script>
/**
 * 盘点单 表单页（**独立交互**；B4 §8.4 / design D12 的"盘点单独实现但复用行项表与状态标签"）。
 *
 * 三步（与服务端口径一致，真源见 `stocktake-rules.js` 的头注释）：
 *   ① 生成行项：`POST /stk/take/{id}/generate`（权限 `stk:take:add`）——全盘取该仓库结存非零物料；
 *      抽盘按物料（优先）或按商品类型**含子树**；返回更新后的单据（行项带**只读**账面数量）。
 *   ② 录入实盘：行内输入由壳的 `DocItemsTable` 承担（实盘列可编辑、差异自动算）；
 *      点「保存实盘数量」→ `PUT /stk/take/{id}/count`（权限 `stk:take:edit`）。
 *   ③ 审核：`POST /stk/take/approve/{id}`（权限 `stk:take:approve`）——服务端按差异正负**自动生成**
 *      盘盈入库单 / 盘亏出库单，返回体回填 `generatedInNo`/`generatedOutNo`；本页把它们显示出来。
 *
 * 合规点：生成/实盘/审核三段都**只调用已落地端点**，不本地造数据；行项表与状态标签**复用壳组件**。
 */
import DocFormShell from '../doc/DocFormShell'
import DocActionDialog from '../doc/components/DocActionDialog'
import docKinds from '../doc/doc-kinds'
import rules from '../doc/doc-rules'
import takeRules from './stocktake-rules'
import * as api from '@/api/erp/doc'
import { treeEnabledProductTypes, listEnabledProducts } from '@/api/erp/masterdata'
import { describeError } from '@/utils/errorMessage'

export default {
  name: 'ErpStockTakeForm',
  components: { DocFormShell, DocActionDialog },
  data() {
    return {
      kind: docKinds.getKind('stock_take'),
      generating: false,
      counting: false,
      actionVisible: false,
      currentAction: null,
      actionRow: null,
      typeTree: [],
      products: [],
      form: {
        takeType: 'full',
        productTypeIds: [],
        productIds: []
      }
    }
  },
  computed: {
    docId() {
      return String((this.$route && this.$route.query.id) || '')
    },
    mode() {
      const q = (this.$route && this.$route.query) || {}
      if (q.mode === 'view' || q.mode === 'edit' || q.mode === 'add') {
        return q.mode
      }
      return q.id ? 'edit' : 'add'
    },
    takeTypeOptions() {
      // 字典 `stock_take_types` 的两个取值（full/partial），label 与服务端一致
      return [
        { value: takeRules.TAKE_TYPE_FULL, label: takeRules.takeTypeLabel(takeRules.TAKE_TYPE_FULL) },
        { value: takeRules.TAKE_TYPE_PARTIAL, label: takeRules.takeTypeLabel(takeRules.TAKE_TYPE_PARTIAL) }
      ]
    },
    isPartial() {
      return takeRules.normalizeTakeType(this.form.takeType) === takeRules.TAKE_TYPE_PARTIAL
    },
    cascaderProps() {
      return {
        value: 'id',
        label: 'name',
        children: 'children',
        multiple: true,
        emitPath: false,
        checkStrictly: true,
        disabled: 'disabledFlag'
      }
    },
    hint() {
      if (!this.docId) {
        return '请先「保存草稿」生成单据，再生成行项（行项依赖单据 ID 与所选仓库）'
      }
      return '全盘 = 该仓库结存非零的全部物料；抽盘 = 指定物料（优先）或商品类型整棵子树。账面数量只读，实盘与差异原因可填。'
    }
  },
  created() {
    this.loadPickers()
  },
  methods: {
    permOf(action) {
      return rules.permOf(this.kind, action)
    },
    statusLabel(status) {
      return rules.statusLabel(status)
    },
    resultText(doc) {
      return takeRules.generatedDocText(doc)
    },
    /**
     * 页面上的动作按钮 —— 用**槽里传下来的** `ctx.form.status` 计算（槽 prop 是响应式的，
     * 而 `$refs` 不是，所以在页面 computed 里读子组件状态不会有更新）。
     * 去掉 `submit`：壳底部已有「保存并提交」，不重复。
     */
    actionsOf(status) {
      return rules.visibleActions(this.kind, status, true).filter((act) => act.code !== 'submit')
    },
    loadPickers() {
      treeEnabledProductTypes()
        .then((res) => {
          this.typeTree = markDisabled((res && res.data) || [])
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('商品类型下拉加载失败：' + d.text)
        })
      listEnabledProducts()
        .then((res) => {
          this.products = (res && res.rows) || []
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('物料下拉加载失败：' + d.text)
        })
    },
    handleTakeTypeChange() {
      if (!this.isPartial) {
        this.form.productTypeIds = []
        this.form.productIds = []
      }
    },
    /** 从壳里取当前单据（表头 + 行项），供本页发起 generate/count/approve。 */
    shell() {
      return this.$refs.shell
    },
    currentDoc() {
      const shell = this.shell()
      return shell ? shell.form : null
    },
    handleGenerate() {
      const shell = this.shell()
      const doc = this.currentDoc()
      if (!shell || !doc || !doc.id) {
        this.$modal.msgWarning('请先保存草稿生成单据')
        return
      }
      const judged = takeRules.validateGenerate({
        takeType: this.form.takeType,
        productIds: this.form.productIds,
        productTypeIds: this.form.productTypeIds
      })
      if (!judged.ok) {
        this.$modal.msgWarning(judged.message)
        return
      }
      this.generating = true
      const criteria = takeRules.buildCriteria({
        takeType: this.form.takeType,
        productIds: this.form.productIds,
        productTypeIds: this.form.productTypeIds,
        scopeNote: doc.scopeNote
      })
      api
        .generateStocktakeItems(this.kind.code, doc.id, criteria)
        .then((res) => {
          this.generating = false
          shell.applyServerDoc((res && res.data) || null)
          this.$modal.msgSuccess('已生成盘点行项')
        })
        .catch((err) => {
          this.generating = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleSaveCount(items) {
      const shell = this.shell()
      const doc = this.currentDoc()
      if (!shell || !doc || !doc.id) {
        return
      }
      const judged = takeRules.validateCount(items)
      if (!judged.ok) {
        this.$modal.msgWarning(judged.message)
        return
      }
      this.counting = true
      api
        .saveStocktakeCount(this.kind.code, doc.id, takeRules.buildCountItems(items))
        .then((res) => {
          this.counting = false
          shell.applyServerDoc((res && res.data) || null)
          this.$modal.msgSuccess('实盘数量已保存（差异由服务端计算）')
        })
        .catch((err) => {
          this.counting = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleAction(act, doc) {
      this.currentAction = act
      this.actionRow = doc
      this.actionVisible = true
    },
    /** 审核后会回填盘盈/盘亏单号 → 重新拉详情把结果展示出来。 */
    handleActionDone() {
      const shell = this.shell()
      if (shell) {
        shell.reloadDoc()
      }
    },
    handleReload() {
      const shell = this.shell()
      if (shell) {
        shell.reloadDoc()
      }
    }
  }
}

/** 类型树标记停用节点（不可选）。 */
function markDisabled(nodes) {
  return (nodes || []).map(function (node) {
    const copy = Object.assign({}, node)
    copy.disabledFlag = node.enableFlag !== '1'
    copy.children = markDisabled(node.children)
    if (!copy.children.length) {
      delete copy.children
    }
    return copy
  })
}
</script>

<style lang="scss" scoped>
.take-result {
  margin-bottom: var(--oa-space-xs);
}
.take-bar {
  margin-top: var(--oa-space-xs);
}
.take-hint {
  margin: var(--oa-space-xxs) 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.take-actions {
  display: flex;
  gap: var(--oa-space-xs);
  margin-bottom: var(--oa-space-sm);
}
</style>
