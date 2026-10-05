<template>
  <div class="app-container erp-doc-form">
    <!--
      单据表单壳（B4 §8.3；D12）。8 类单据共用：
      表头字段由 `kind.headerFields` 驱动，行项表由 `kind.itemColumns` 驱动，
      保存/提交/附件走同一套代码。专有交互（盘点单的"生成行项 → 录入实盘"）用
      具名插槽 `header-extra` / `items-extra` 扩展，**不要**复制一份表单页。
    -->
    <el-page-header :content="pageTitle" @back="handleBack" />

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="loadDetail" />
    <div v-else v-loading="loading" class="form-body">
      <p class="form-subtitle">{{ kind.subtitle }}</p>
      <p v-if="options.failed.length" class="form-warn">部分下拉未能加载：{{ options.failed.join('；') }}</p>
      <p v-if="readonly" class="form-lock">
        当前状态（{{ statusText }}）不可编辑：只有草稿可改，请先反审核或作废。
      </p>

      <el-form ref="docForm" :model="form" :rules="formRules" label-width="130px" size="small" :disabled="readonly">
        <el-row :gutter="20">
          <el-col v-for="f in kind.headerFields" :key="f.prop" :span="f.span || 12">
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
                :placeholder="f.placeholder || '选填'"
              />
              <el-date-picker
                v-else-if="f.kind === 'date'"
                v-model="form[f.prop]"
                type="date"
                :value-format="dateFormat"
                placeholder="选择日期"
                style="width: 100%"
              />
              <el-select
                v-else-if="f.kind === 'dict'"
                v-model="form[f.prop]"
                filterable
                clearable
                :placeholder="'请选择' + f.label"
                style="width: 100%"
              >
                <!-- 字典行是 {dictLabel,dictValue}，必须经 dictSelectOptions 转成 {value,label}，
                     否则选项渲染成空条目（看起来"下拉是空的"） -->
                <el-option v-for="o in dictSelectOptions(f.dict)" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <el-select
                v-else-if="f.kind === 'supplier'"
                v-model="form[f.prop]"
                filterable
                clearable
                placeholder="请选择供应商"
                style="width: 100%"
                @change="handleSupplierChange(f)"
              >
                <el-option v-for="s in options.suppliers" :key="s.id" :label="partyLabel(s)" :value="s.id" />
              </el-select>
              <el-select
                v-else-if="f.kind === 'customer'"
                v-model="form[f.prop]"
                filterable
                clearable
                placeholder="请选择客户"
                style="width: 100%"
                @change="handleCustomerChange(f)"
              >
                <el-option v-for="c in options.customers" :key="c.id" :label="partyLabel(c)" :value="c.id" />
              </el-select>
              <el-select
                v-else-if="f.kind === 'warehouse'"
                v-model="form[f.prop]"
                filterable
                clearable
                placeholder="请选择仓库"
                style="width: 100%"
                @change="handleWarehouseChange(f)"
              >
                <el-option v-for="w in options.warehouses" :key="w.id" :label="w.name" :value="w.id" />
              </el-select>
              <el-select
                v-else-if="f.kind === 'contract'"
                v-model="form[f.prop]"
                filterable
                clearable
                placeholder="选填：关联合同"
                style="width: 100%"
                @change="handleContractChange(f)"
              >
                <el-option
                  v-for="c in contractOptions(f)"
                  :key="c.id"
                  :label="(c.code || '') + ' ' + (c.name || '')"
                  :value="c.id"
                />
              </el-select>
              <el-tag v-else-if="f.kind === 'readonlyPosted'" :type="form.posted === '1' ? 'success' : 'info'" size="mini">
                {{ form.posted === '1' ? '已过账' : '未过账' }}
              </el-tag>
              <span v-else-if="f.kind === 'readonly'" class="readonly-value">{{ isEmpty(form[f.prop]) ? '—' : form[f.prop] }}</span>
              <el-input v-else v-model="form[f.prop]" :placeholder="'请输入' + f.label" clearable />
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 专有字段插槽（如盘点单的"确认生成行项"按钮与范围选择）；
             槽里同时给出 form/items/readonly，页面无需反向读子组件状态。 -->
        <slot name="header-extra" :form="form" :items="items" :readonly="readonly"></slot>
      </el-form>

      <div class="items-title">
        行项明细
        <span v-if="kind.totalAmountColumn" class="items-total">单据金额：{{ totalAmountText }}</span>
      </div>
      <DocItemsTable :kind="kind" :items="items" :readonly="readonly" :options="options" @change="handleItemsChange" />
      <slot name="items-extra" :form="form" :items="items"></slot>

      <div v-if="form.id" class="form-attachments">
        <div class="items-title">附件</div>
        <DocAttachmentPanel
          :object-type="kind.attachmentObjectType"
          :object-id="form.id"
          :write-perm="permOf('edit')"
        />
      </div>
    </div>

    <div class="form-footer">
      <el-button @click="handleBack">返 回</el-button>
      <el-button
        v-if="!readonly"
        type="primary"
        :loading="saving"
        v-hasPermi="[permOf(form.id ? 'edit' : 'add')]"
        @click="handleSave(false)"
        >保存草稿</el-button
      >
      <el-button
        v-if="!readonly && form.id"
        type="success"
        :loading="saving"
        v-hasPermi="[permOf('submit')]"
        @click="handleSave(true)"
        >保存并提交</el-button
      >
    </div>
  </div>
</template>

<script>
/**
 * 单据表单壳（B4 §8.3；design D12）。
 *
 * 三态：新增（无 id）/ 编辑（草稿）/ 只读（查看，或非草稿）。
 * 提交前的校验顺序刻意与服务端一致（先表头后行项），文案也照抄服务端口径：
 *   1. 调拨两仓不能相同（tasks.md 6.1）；
 *   2. 至少一行、数量 > 0、单价非负、数量精度按单位小数位（tasks.md 3.1）；
 *   3. 必填表头字段（`kind.headerFields` 的 `required`）。
 * 金额口径走 `erp-amounts`（= B3 的定点实现），不做第二份。
 *
 * 扩展点（t9b 的 8 类单据页面只需要写这些，不需要复制本文件）：
 *   · 专有字段：`kind.headerFields`（配置）或 `#header-extra` 插槽（自由内容）；
 *   · 盘点单的独立交互：`#header-extra`（生成行项）+ `#items-extra`（保存实盘数量）；
 *   · 打印入口在列表壳上（`DocListShell`），本壳不重复提供。
 */
// ⚠ 命名空间导入（api/erp/doc 只有命名导出；default import 会拿到 undefined ⇒ 请求发不出去）
import * as api from '@/api/erp/doc'
import rules from './doc-rules'
import erpConst from '../erp-const'
import { moneyText, sumLineTotalsText } from './erp-amounts'
import { describeError } from '@/utils/errorMessage'
import { emptySelectOptions, dictOptionsOf, dictSelectOptions, loadDictsInto, loadSelectsInto } from './doc-selects'
import DataLoadError from '@/components/DataLoadError'
import DocItemsTable from './components/DocItemsTable'
import DocAttachmentPanel from './components/DocAttachmentPanel'

export default {
  name: 'DocFormShell',
  components: { DataLoadError, DocItemsTable, DocAttachmentPanel },
  props: {
    kind: { type: Object, required: true },
    // 单据 ID（新增时为空串/undefined）
    id: { type: String, default: '' },
    // add / edit / view
    mode: { type: String, default: 'add' }
  },
  data() {
    return {
      loading: false,
      saving: false,
      loadError: '',
      loadErrorCause: '',
      // 下拉与字典状态（三个键显式声明，逻辑由 ./doc-selects 提供）
      options: emptySelectOptions(),
      dictData: {},
      dictFailed: [],
      form: this.buildForm({}),
      items: []
    }
  },
  computed: {
    dateFormat() {
      return erpConst.DATE_FORMAT
    },
    readonly() {
      if (this.mode === 'view') {
        return true
      }
      // 新增态没有状态，可编辑；已存在的单据只有草稿可编辑
      return !!this.form.id && !rules.isEditable(this.form.status)
    },
    statusText() {
      return rules.statusLabel(this.form.status)
    },
    pageTitle() {
      const modeText = this.mode === 'view' ? '查看' : this.form.id ? '编辑' : '新增'
      return modeText + this.kind.label + (this.form.docNo ? '（' + this.form.docNo + '）' : '')
    },
    formRules() {
      const out = {}
      ;(this.kind.headerFields || []).forEach((f) => {
        if (!f.required) {
          return
        }
        out[f.prop] = [
          {
            required: true,
            message: f.label + '不能为空',
            trigger: f.kind === 'date' || f.kind === 'supplier' || f.kind === 'customer' || f.kind === 'warehouse' || f.kind === 'dict' ? 'change' : 'blur'
          }
        ]
      })
      return out
    },
    totalAmountText() {
      const amounts = this.items.map((item) => item.amount)
      return moneyText(sumLineTotalsText(amounts))
    }
  },
  watch: {
    // 同一组件实例在 /form?id=A 与 ?id=B 之间切换时要重新取详情（vue-router 复用组件）
    id() {
      this.loadDetail()
    }
  },
  created() {
    this.loadDictsOfKind()
    this.loadSelects()
    if (this.id) {
      this.loadDetail()
    }
  },
  methods: {
    /**
     * **扩展开口**：把服务端返回的单据回填进壳（供盘点单这类"专有交互页"用）。
     *
     * 场景：盘点单页在 `#header-extra` 里调 `generate`/`count`/`approve`，服务端返回**更新后的单据**
     * （含新生成的行项、差异、盘盈/盘亏单号）；页面拿到后调用本方法即可让壳里的表头与行项表同步，
     * 不需要在页面里重写"表单/行项"的渲染（那是壳的职责）。
     */
    applyServerDoc(doc) {
      if (!doc) {
        return
      }
      this.form = this.buildForm(doc)
      this.items = doc.items || []
    },
    /** 重新拉一次详情（盘点单页在审核/生成后刷新盘盈盘亏单号用）。 */
    reloadDoc() {
      this.loadDetail()
    },
    buildForm(extra) {
      const base = {
        id: '',
        docNo: '',
        docDate: '',
        status: '',
        handlerName: '',
        remark: '',
        contractId: '',
        contractNo: '',
        totalAmount: 0,
        posted: '0'
      }
      return Object.assign(base, extra)
    },
    loadDictsOfKind() {
      loadDictsInto(this, this.kind.headerFields.concat(this.kind.filters))
    },
    loadSelects() {
      loadSelectsInto(this)
    },
    /** 字典选项（原始行；表格 `<dict-tag>` 用）。 */
    dictOptions(name) {
      return dictOptionsOf(this, name)
    },
    /** 字典下拉选项（`{value,label}`；el-select 用）。 */
    dictSelectOptions(name) {
      return dictSelectOptions(this, name)
    },
    permOf(action) {
      return rules.permOf(this.kind, action)
    },
    isEmpty(v) {
      return v === null || v === undefined || v === ''
    },
    partyLabel(party) {
      return party.shortName || party.name || party.code
    },
    contractOptions(field) {
      return field.contractDirection === 'sale' ? this.options.saleContracts : this.options.purchaseContracts
    },
    /** 供应商/客户选择后写名称快照（历史单据按快照展示，AC-72）。 */
    handleSupplierChange(field) {
      const hit = this.options.suppliers.filter((s) => s.id === this.form[field.prop])[0]
      this.form.supplierName = hit ? hit.name : ''
    },
    handleCustomerChange(field) {
      const hit = this.options.customers.filter((c) => c.id === this.form[field.prop])[0]
      this.form.customerName = hit ? hit.name : ''
    },
    handleWarehouseChange(field) {
      const name = this.warehouseNameOf(this.form[field.prop])
      if (field.prop === 'warehouseId') {
        this.form.warehouseName = name
      }
      if (field.prop === 'receiptWarehouseId') {
        this.form.receiptWarehouseName = name
      }
      if (field.prop === 'shipWarehouseId') {
        this.form.shipWarehouseName = name
      }
      if (field.prop === 'fromWarehouseId') {
        this.form.fromWarehouseName = name
      }
      if (field.prop === 'toWarehouseId') {
        this.form.toWarehouseName = name
      }
    },
    warehouseNameOf(id) {
      const hit = this.options.warehouses.filter((w) => w.id === id)[0]
      return hit ? hit.name : ''
    },
    /** 合同选择后写合同编号快照（不回写合同表，design D10/REQ-CTMS-008）。 */
    handleContractChange() {
      const all = this.options.purchaseContracts.concat(this.options.saleContracts)
      const hit = all.filter((c) => c.id === this.form.contractId)[0]
      this.form.contractNo = hit ? hit.code : ''
    },
    handleItemsChange() {
      // 行项就地编辑，金额已由行项表按定点口径写回；这里只保留事件挂点（便于 t9b 扩展）
    },
    loadDetail() {
      const docId = this.id || (this.$route && this.$route.query.id) || ''
      if (!docId) {
        return
      }
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      api
        .getDoc(this.kind.code, docId)
        .then((res) => {
          const doc = (res && res.data) || {}
          this.form = this.buildForm(doc)
          this.items = doc.items || []
          this.loading = false
        })
        .catch((err) => {
          const d = describeError(err)
          this.loadError = d.text
          this.loadErrorCause = d.cause
          this.loading = false
        })
    },
    /** 校验 → 保存 → 可选提交（保存与提交是两次请求：提交要求单据已存在且为草稿）。 */
    handleSave(andSubmit) {
      const self = this
      this.$refs.docForm.validate((formOk) => {
        if (!formOk) {
          return
        }
        const transferJudge = rules.validateTransfer(self.kind, self.form)
        if (!transferJudge.ok) {
          self.$modal.msgWarning(transferJudge.message)
          return
        }
        const itemsJudge = rules.validateItems(self.kind, self.items)
        if (!itemsJudge.ok) {
          self.$modal.msgWarning(itemsJudge.message)
          return
        }
        const payload = self.buildPayload()
        self.saving = true
        const request = payload.id ? api.updateDoc(self.kind.code, payload) : api.addDoc(self.kind.code, payload)
        request
          .then((res) => {
            const saved = (res && res.data) || {}
            self.form.id = saved.id || payload.id
            self.form.docNo = saved.docNo || self.form.docNo
            self.form.status = saved.status || self.form.status
            self.saving = false
            self.$modal.msgSuccess('保存成功')
            if (!andSubmit) {
              self.afterSaved()
              return null
            }
            return self.submitAfterSave()
          })
          .catch((err) => {
            self.saving = false
            const d = describeError(err)
            self.$modal.msgError(d.text)
          })
      })
    },
    submitAfterSave() {
      const self = this
      return api
        .docAction(self.kind.code, self.form.id, 'submit', '')
        .then(() => {
          self.form.status = 'submitted'
          self.$modal.msgSuccess('已提交，等待审核')
          self.afterSaved()
        })
        .catch((err) => {
          const d = describeError(err)
          self.$modal.msgError('保存成功但提交失败：' + d.text)
        })
    },
    /** 新增成功后把 id 写进地址栏（刷新/分享仍能回到该单据）。 */
    afterSaved() {
      if (this.$route && this.$route.query.id !== this.form.id) {
        this.$router.replace({ path: this.kind.formPath, query: { id: this.form.id, mode: 'edit' } })
      }
    },
    /** 只提交 DDL 里存在的列（快照列由前端带出；其余字段由服务端决定）。 */
    buildPayload() {
      const writable = [
        'id', 'docNo', 'docDate', 'handlerName', 'remark', 'contractId', 'contractNo',
        'supplierId', 'supplierName', 'customerId', 'customerName', 'customerNameText',
        'needDate', 'purpose', 'suggestSupplierId',
        'purchaseDeptId', 'expectedArrivalDate', 'settleType', 'currency', 'receiptWarehouseId',
        'salesDeptId', 'expectDeliveryDate', 'deliveryDate', 'deliveryAddress', 'contactName',
        'contactPhone', 'shipWarehouseId',
        'warehouseId', 'warehouseName', 'inType', 'outType',
        'takeType', 'scopeNote',
        'fromWarehouseId', 'fromWarehouseName', 'toWarehouseId', 'toWarehouseName'
      ]
      const payload = {}
      writable.forEach((key) => {
        if (this.form[key] !== undefined) {
          payload[key] = this.form[key]
        }
      })
      payload.items = this.items.map((item) => this.buildItem(item))
      return payload
    },
    buildItem(item) {
      const keys = [
        'id', 'productId', 'productCode', 'productName', 'spec', 'uomId', 'uomName', 'uomDecimals',
        'qty', 'unitPrice', 'amount', 'warehouseId', 'warehouseName', 'srcItemId', 'remark',
        'bookQty', 'actualQty', 'diffQty', 'diffReason'
      ]
      const out = {}
      keys.forEach((key) => {
        if (item[key] !== undefined) {
          out[key] = item[key]
        }
      })
      return out
    },
    handleBack() {
      this.$router.push({ path: this.kind.listPath })
    }
  }
}
</script>

<style lang="scss" scoped>
.erp-doc-form .form-body {
  padding: var(--oa-space-sm) 0;
}
.form-subtitle {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.form-warn {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-warning);
}
.form-lock {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-body-sm);
  color: var(--oa-color-warning);
}
.readonly-value {
  font: var(--oa-font-body-sm);
}
.items-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: var(--oa-space-sm) 0 var(--oa-space-xs);
  font: var(--oa-font-title-section);
}
.items-total {
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-muted);
}
.form-attachments {
  margin-top: var(--oa-space-md);
}
.form-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--oa-space-xs);
  padding: var(--oa-space-sm) 0;
}
</style>
