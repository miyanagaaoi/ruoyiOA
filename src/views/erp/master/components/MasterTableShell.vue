<template>
  <div class="app-container erp-master-page">
    <!--
      主数据"列表 + 弹窗表单"通用壳（物料 / 计量单位 / 仓库三页共用；
      物料类型那页是树形，见 MasterTreeShell）。配置见 `master-page.js`。
      ⚠ 权限点沿用 B3 控制器注解 `ctms:partner:*`；启停用走 `edit`（该控制器没有 /status 端点）。
    -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item v-for="f in config.filters" :key="f.prop" :label="f.label">
        <el-input
          v-if="f.kind === 'input'"
          v-model="queryParams[f.prop]"
          :placeholder="f.placeholder || '请输入' + f.label"
          clearable
          style="width: 190px"
          @keyup.enter.native="handleQuery"
        />
        <el-select
          v-else-if="f.kind === 'select'"
          v-model="queryParams[f.prop]"
          placeholder="全部"
          clearable
          style="width: 140px"
        >
          <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-cascader
          v-else-if="f.kind === 'productType'"
          v-model="queryParams[f.prop]"
          :options="typeTree"
          :props="cascaderProps"
          clearable
          placeholder="全部类型"
          style="width: 220px"
        />
        <el-select
          v-else-if="f.kind === 'uom'"
          v-model="queryParams[f.prop]"
          filterable
          clearable
          placeholder="全部单位"
          style="width: 160px"
        >
          <el-option v-for="u in uoms" :key="u.id" :label="u.name" :value="u.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" v-hasPermi="[permOf('add')]" @click="handleAdd"
          >新增</el-button
        >
      </el-col>
      <el-col v-if="config.tree" :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-circle-plus-outline"
          size="mini"
          :disabled="single"
          v-hasPermi="[permOf('add')]"
          @click="handleAddChild"
          >新增下级</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          v-hasPermi="[permOf('edit')]"
          @click="handleUpdate"
          >修改</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          v-hasPermi="[permOf('remove')]"
          @click="handleDelete"
          >删除</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-s-operation"
          size="mini"
          :disabled="single"
          v-hasPermi="[permOf('edit')]"
          @click="handleToggleEnable"
          >{{ statusButtonText }}</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <p class="master-subtitle">{{ config.subtitle }}</p>

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table
      v-else
      v-loading="loading"
      :data="list"
      border
      size="mini"
      :row-key="config.tree ? 'id' : undefined"
      :tree-props="treeProps"
      :default-expand-all="!!config.tree"
      @selection-change="handleSelectionChange"
    >
      <el-table-column v-if="!config.tree" type="selection" width="50" align="center" />
      <el-table-column
        v-for="col in config.columns"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
        :show-overflow-tooltip="!!col.showOverflowTooltip"
      >
        <template slot-scope="scope">
          <el-tag v-if="col.kind === 'enable'" :type="scope.row.enableFlag === '1' ? 'success' : 'info'" size="mini">
            {{ scope.row.enableFlag === '1' ? '启用' : '停用' }}
          </el-tag>
          <span v-else-if="col.kind === 'money'">{{ moneyText(scope.row[col.prop]) }}</span>
          <span v-else-if="col.kind === 'qty'">{{ formatQty(scope.row[col.prop]) }}</span>
          <span v-else-if="col.kind === 'map'">{{ mapName(col, scope.row[col.prop]) }}</span>
          <span v-else>{{ isEmpty(scope.row[col.prop]) ? '—' : scope.row[col.prop] }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="config.tree" type="selection" width="50" align="center" />
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="640px" append-to-body @closed="handleDialogClosed">
      <el-form ref="masterForm" :model="form" :rules="formRules" label-width="120px" size="small">
        <el-row :gutter="20">
          <el-col v-for="f in config.fields" :key="f.prop" :span="f.span || 12">
            <el-form-item :label="f.label" :prop="f.prop">
              <el-input
                v-if="f.kind === 'input'"
                v-model="form[f.prop]"
                :placeholder="f.placeholder || '请输入' + f.label"
                clearable
              />
              <el-input
                v-else-if="f.kind === 'textarea'"
                v-model="form[f.prop]"
                type="textarea"
                :rows="2"
                :maxlength="f.maxlength || 1000"
                show-word-limit
                placeholder="选填"
              />
              <el-input-number
                v-else-if="f.kind === 'number'"
                v-model="form[f.prop]"
                :precision="f.precision"
                :min="f.min"
                :max="f.max"
                controls-position="right"
                style="width: 100%"
              />
              <el-select
                v-else-if="f.kind === 'select'"
                v-model="form[f.prop]"
                filterable
                clearable
                style="width: 100%"
              >
                <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <el-switch
                v-else-if="f.kind === 'enable'"
                v-model="form[f.prop]"
                active-value="1"
                inactive-value="0"
                active-text="启用"
                inactive-text="停用"
              />
              <el-cascader
                v-else-if="f.kind === 'productType'"
                v-model="form[f.prop]"
                :options="typeTree"
                :props="cascaderProps"
                clearable
                :placeholder="f.placeholder || '请选择类型'"
                style="width: 100%"
              />
              <el-select
                v-else-if="f.kind === 'uom'"
                v-model="form[f.prop]"
                filterable
                clearable
                placeholder="请选择计量单位"
                style="width: 100%"
              >
                <el-option
                  v-for="u in uoms"
                  :key="u.id"
                  :label="u.name + '（' + u.decimals + ' 位小数）'"
                  :value="u.id"
                  :disabled="u.enableFlag !== '1'"
                />
              </el-select>
              <el-input v-else v-model="form[f.prop]" placeholder="请输入" clearable />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确 定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 主数据列表壳（物料 / 计量单位 / 仓库）。
 *
 * 取数纪律（`tools/audit` 的类 1/类 2 门禁）：所有 `.then` 都配 `.catch`，
 * 失败时复位 `loading`、清空列表并渲染 `DataLoadError`（不能让页面停在空表上，
 * 那和"确实没有数据"无法区分）。
 *
 * 启停用：该控制器**没有** `/status` 端点，所以把整行回传（`Object.assign({}, row, {enableFlag})`）
 * 走 `PUT` —— 只传 id+enableFlag 会被服务端的"编码/名称不能为空"拦下。
 */
import * as masterApi from '@/api/erp/masterdata'
import rules from '../../doc/doc-rules'
import { moneyText } from '../../doc/erp-amounts'
import erpConst from '../../erp-const'
import masterConfig from '../master-page'
import { describeError } from '@/utils/errorMessage'
import DataLoadError from '@/components/DataLoadError'

export default {
  name: 'MasterTableShell',
  components: { DataLoadError },
  props: {
    config: { type: Object, required: true }
  },
  data() {
    return {
      loading: false,
      submitting: false,
      showSearch: true,
      list: [],
      total: 0,
      queryParams: this.buildQuery(),
      ids: [],
      single: true,
      multiple: true,
      loadError: '',
      loadErrorCause: '',
      dialogVisible: false,
      dialogTitle: '',
      isEdit: false,
      form: {},
      typeTree: [],
      typeList: [],
      uoms: []
    }
  },
  computed: {
    cascaderProps() {
      return {
        value: 'id',
        label: 'name',
        children: 'children',
        emitPath: false,
        // 物料必须挂**叶子**类型（服务端也强制）；其它页面选上级类型时任意节点都可选
        checkStrictly: !this.config.leafTypeOnly,
        disabled: 'disabledFlag'
      }
    },
    formRules() {
      const out = {}
      ;(this.config.fields || []).forEach((f) => {
        if (!f.required) {
          return
        }
        out[f.prop] = [
          { required: true, message: f.label + '不能为空', trigger: f.kind === 'number' || f.kind === 'productType' || f.kind === 'uom' ? 'change' : 'blur' }
        ]
      })
      return out
    },
    statusButtonText() {
      const row = this.selectedRow()
      if (!row) {
        return '启用/停用'
      }
      return row.enableFlag === '1' ? '停用' : '启用'
    },
    needsProductTypes() {
      return (this.config.optionSources || []).indexOf('productTypes') >= 0
    },
    treeProps() {
      return this.config.tree ? { children: 'children', hasChildren: 'hasChildren' } : {}
    },
    needsUoms() {
      return (this.config.optionSources || []).indexOf('uoms') >= 0
    }
  },
  created() {
    this.loadOptions()
    this.getList()
  },
  methods: {
    buildQuery(extra) {
      const base = { pageNum: 1, pageSize: erpConst.PAGE_SIZE }
      ;(this.config.filters || []).forEach((f) => {
        base[f.prop] = undefined
      })
      return Object.assign(base, extra)
    },
    permOf(action) {
      return masterConfig.perm(action)
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
    /** `map` 列：把外键 id 显示成名称（列表接口只回 id，不回名称）。 */
    mapName(col, id) {
      if (!id) {
        return '—'
      }
      const source = col.map === 'uoms' ? this.uoms : this.typeList
      const hit = source.filter((item) => item.id === id)[0]
      return hit ? hit.name : id
    },
    selectedRow() {
      return this.ids.length === 1 ? this.list.filter((row) => row.id === this.ids[0])[0] : null
    },
    /** 下拉选项：类型树（含停用标记）与计量单位（停用项在表单里禁用）。 */
    loadOptions() {
      if (this.needsProductTypes) {
        masterApi
          .treeProductType({})
          .then((res) => {
            const tree = (res && res.data) || []
            this.typeTree = markDisabled(tree)
            this.typeList = flatten(tree)
          })
          .catch((err) => {
            const d = describeError(err)
            this.typeTree = []
            this.typeList = []
            this.$modal.msgWarning('物料类型下拉加载失败：' + d.text)
          })
      }
      if (this.needsUoms) {
        masterApi
          .listUom({ pageNum: 1, pageSize: erpConst.OPTION_PAGE_SIZE })
          .then((res) => {
            this.uoms = (res && res.rows) || []
          })
          .catch((err) => {
            const d = describeError(err)
            this.uoms = []
            this.$modal.msgWarning('计量单位下拉加载失败：' + d.text)
          })
      }
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      masterApi[this.config.api.list](this.queryParams)
        .then((res) => {
          if (this.config.tree) {
            this.list = (res && res.data) || []
            this.total = this.list.length
          } else {
            this.list = (res && res.rows) || []
            this.total = (res && res.total) || 0
          }
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
      this.queryParams = this.buildQuery()
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.isEdit = false
      this.form = this.buildForm({})
      this.dialogTitle = '新增' + this.config.title
      this.dialogVisible = true
    },
    handleUpdate() {
      const row = this.selectedRow()
      if (!row) {
        return
      }
      this.isEdit = true
      this.form = this.buildForm(row)
      this.dialogTitle = '修改' + this.config.title
      this.dialogVisible = true
    },
    /** 树形页：以选中节点为上级新增下级（层级上限 5 层由服务端拒绝）。 */
    handleAddChild() {
      const row = this.selectedRow()
      if (!row) {
        return
      }
      this.isEdit = false
      this.form = this.buildForm({ parentId: row.id, enableFlag: erpConst.ENABLE_FLAG_ON })
      this.dialogTitle = '新增下级' + this.config.title
      this.dialogVisible = true
    },
    /** 表单默认值（启用标志默认启用；其它字段留空由用户填）。 */
    buildForm(source) {
      const form = { id: '', enableFlag: erpConst.ENABLE_FLAG_ON }
      ;(this.config.fields || []).forEach((f) => {
        if (source && source[f.prop] !== undefined) {
          form[f.prop] = source[f.prop]
        }
      })
      return Object.assign(form, source && source.id ? { id: source.id } : {})
    },
    handleSubmit() {
      const self = this
      this.$refs.masterForm.validate((ok) => {
        if (!ok) {
          return
        }
        const payload = Object.assign({}, self.form)
        if (!self.isEdit) {
          delete payload.id
        }
        self.submitting = true
        const request = self.isEdit
          ? masterApi[self.config.api.update](payload)
          : masterApi[self.config.api.add](payload)
        request
          .then(() => {
            self.submitting = false
            self.$modal.msgSuccess(self.isEdit ? '修改成功' : '新增成功')
            self.dialogVisible = false
            self.getList()
          })
          .catch((err) => {
            self.submitting = false
            const d = describeError(err)
            self.$modal.msgError(d.text)
          })
      })
    },
    handleDelete() {
      const rows = this.list.filter((row) => this.ids.indexOf(row.id) >= 0)
      if (!rows.length) {
        return
      }
      const names = rows.map((row) => row.name || row.code).join('、')
      this.$modal
        .confirm('确认删除「' + names + '」？有引用（结存 / 单据 / 子节点 / 物料）时服务端会拒绝。')
        .then(() => this.doDelete(rows))
        .catch((err) => {
          // confirm 的取消（'cancel'/'close'）不需要提示；接口失败已在 doDelete 里提示
          if (err === 'cancel' || err === 'close') {
            return
          }
        })
    },
    doDelete(rows) {
      this.submitting = true
      return Promise.all(
        rows.map((row) => masterApi[this.config.api.del](row.id))
      )
        .then(() => {
          this.submitting = false
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
        .catch((err) => {
          this.submitting = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /** 启停用：整行回传 + 翻转 enableFlag（服务端只在 update 里接受该列）。 */
    handleToggleEnable() {
      const row = this.selectedRow()
      if (!row) {
        return
      }
      const next = row.enableFlag === '1' ? erpConst.ENABLE_FLAG_OFF : erpConst.ENABLE_FLAG_ON
      const actionText = next === '1' ? '启用' : '停用'
      this.$modal
        .confirm('确认' + actionText + '「' + (row.name || row.code) + '」？')
        .then(() => this.doToggleEnable(row, next, actionText))
        .catch((err) => {
          if (err === 'cancel' || err === 'close') {
            return
          }
        })
    },
    doToggleEnable(row, next, actionText) {
      this.submitting = true
      return masterApi[this.config.api.update](Object.assign({}, row, { enableFlag: next }))
        .then(() => {
          this.submitting = false
          this.$modal.msgSuccess(actionText + '成功')
          this.getList()
        })
        .catch((err) => {
          this.submitting = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleDialogClosed() {
      if (this.$refs.masterForm) {
        this.$refs.masterForm.clearValidate()
      }
    }
  }
}

/** 给类型树打"停用/非叶子"标记：停用节点在级联选择器里不可选。 */
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

/** 类型树拍平（id → 名称映射用）。 */
function flatten(nodes) {
  let out = []
  ;(nodes || []).forEach(function (node) {
    out.push({ id: node.id, name: node.name, enableFlag: node.enableFlag, level: node.level })
    if (node.children && node.children.length) {
      out = out.concat(flatten(node.children))
    }
  })
  return out
}
</script>

<style lang="scss" scoped>
.master-subtitle {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
</style>
