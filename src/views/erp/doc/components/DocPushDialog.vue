<template>
  <el-dialog :title="title" :visible.sync="show" width="820px" append-to-body @closed="handleClosed">
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="load" />
    <div v-else v-loading="loading">
      <div class="push-tip">
        仅「<strong>{{ sourceStatusText }}</strong>」状态可下推；下推量不得超过剩余量（未填即按剩余量全推）。
        生成的是**草稿**下游单据，且会立即累加来源行的已下推数量。
      </div>
      <el-table :data="rows" size="mini" border max-height="360">
        <el-table-column label="物料" min-width="200" show-overflow-tooltip>
          <template slot-scope="scope">
            <span>{{ scope.row.productName }}</span>
            <span class="push-code">{{ scope.row.productCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="规格" prop="spec" width="130" show-overflow-tooltip />
        <el-table-column label="单位" prop="uomName" width="80" align="center" />
        <el-table-column label="原数量" width="110" align="right">
          <template slot-scope="scope">{{ formatQty(scope.row.qty) }}</template>
        </el-table-column>
        <el-table-column :label="remainLabel" width="120" align="right">
          <template slot-scope="scope">{{ formatQty(scope.row.remainQty) }}</template>
        </el-table-column>
        <el-table-column label="本次下推" width="160" align="right">
          <template slot-scope="scope">
            <el-input-number
              v-model="scope.row.pushQty"
              :precision="3"
              :min="0"
              :max="scope.row.remainQty"
              size="mini"
              controls-position="right"
            />
          </template>
        </el-table-column>
      </el-table>

      <!--
        收货仓库（只有"订单→入库单"这条链需要）：`ErpPurchaseOrderController:350-352` 明确
        "warehouseId 不传回落采购单默认收货仓库，两者都空则拒绝下推" —— 这里先让用户选，
        免得提交后被后端拒绝还不知道原因。
      -->
      <el-form v-if="needWarehouse" size="small" :inline="true" class="push-extra">
        <el-form-item label="收货仓库">
          <el-select v-model="warehouseId" filterable clearable placeholder="请选择收货仓库" style="width: 260px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <span class="push-extra-tip">留空时服务端回落采购单的默认收货仓库；都没有会被拒绝。</span>
        </el-form-item>
      </el-form>

      <div class="push-total">
        下推数量合计：<strong>{{ formatQty(totalQty) }}</strong>
      </div>
    </div>
    <div slot="footer" class="dialog-footer">
      <el-button @click="show = false">取 消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确认下推</el-button>
    </div>
  </el-dialog>
</template>

<script>
/**
 * 下推对话框（D12 的"下推对话框"；8 类单据共用）。
 *
 * 语义（tasks.md 3.3 / 3.5 / 4.2 / 4.3）：
 *   · 仅"已审核"（申请→订单）或"已审核/已完成"（订单→出入库）可推；
 *   · 下推量不超过剩余量（剩余 = 数量 − 已下推/已入库/已出库）；不填即全推；
 *   · 生成下游**草稿**并写来源单号与来源行标识；
 *   · 申请→订单**立即**累加已下推量；订单→出入库在**过账时**才回写已入库/已出库量。
 * 前端这里只判"能不能点/填多少"，权限与状态最终由服务端判定（AC-72）。
 */
import * as api from '@/api/erp/doc'
import rules from '../doc-rules'
import { rowsOf } from '../erp-response'
import { listEnabledWarehouses } from '@/api/erp/masterdata'
import { describeError } from '@/utils/errorMessage'
import DataLoadError from '@/components/DataLoadError'

export default {
  name: 'DocPushDialog',
  components: { DataLoadError },
  props: {
    visible: { type: Boolean, default: false },
    // 来源单据类型配置
    kind: { type: Object, required: true },
    // 来源单据行（列表行，至少有 id/status/docNo）
    row: { type: Object, default: null }
  },
  data() {
    return {
      loading: false,
      submitting: false,
      loadError: '',
      loadErrorCause: '',
      rows: [],
      // 「订单→入库单」这条链需要收货仓库（见模板注释）；其余链用不到
      warehouseId: '',
      warehouses: []
    }
  },
  computed: {
    show: {
      get() {
        return this.visible
      },
      set(v) {
        this.$emit('update:visible', v)
      }
    },
    chain() {
      return rules.pushChainOf(this.kind)
    },
    /** 该链的下推端点是否需要收货仓库（采购单→入库单为 true）。 */
    needWarehouse() {
      return !!(this.chain && this.chain.push && this.chain.push.requiresWarehouse)
    },
    title() {
      const target = this.chain ? this.chain.toLabel : ''
      return '下推生成' + target + (this.row && this.row.docNo ? '（来源 ' + this.row.docNo + '）' : '')
    },
    sourceStatusText() {
      return this.chain ? this.chain.sourceStatuses.map(rules.statusLabel).join(' / ') : ''
    },
    remainLabel() {
      if (!this.chain) {
        return '剩余'
      }
      if (this.chain.remainField === 'orderedQty') {
        return '剩余可下推'
      }
      if (this.chain.remainField === 'receivedQty') {
        return '剩余可入库'
      }
      return '剩余可出库'
    },
    totalQty() {
      let sum = 0
      this.rows.forEach((r) => {
        sum += Number(r.pushQty) || 0
      })
      return Math.round(sum * 1000) / 1000
    }
  },
  watch: {
    visible(v) {
      if (v) {
        this.load()
      }
    }
  },
  methods: {
    formatQty(v) {
      return rules.formatQty(v)
    },
    /** 拉来源单详情取行项 → 按剩余量预填（失败必须可见，不能静默空表）。 */
    load() {
      if (!this.row || !this.row.id) {
        return
      }
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      this.loadWarehousesIfNeeded()
      api
        .getDoc(this.kind.code, this.row.id)
        .then((res) => {
          const doc = (res && res.data) || {}
          this.rows = rules.pushRows(this.kind, doc.items || [])
          // 收货仓库默认取采购单的默认收货仓库（服务端在未传时也是这么回落的）
          if (this.needWarehouse && !this.warehouseId) {
            this.warehouseId = doc.receiptWarehouseId || (this.row && this.row.receiptWarehouseId) || ''
          }
          this.loading = false
        })
        .catch((err) => {
          const d = describeError(err)
          this.rows = []
          this.loadError = d.text
          this.loadErrorCause = d.cause
          this.loading = false
        })
    },
    /** 只有需要收货仓库的链才去拉仓库下拉（其余链零额外请求）。 */
    loadWarehousesIfNeeded() {
      if (!this.needWarehouse || this.warehouses.length) {
        return
      }
      listEnabledWarehouses()
        .then((res) => {
          // 列表接口体是 {rows,total}（P-② 教训：读 res.data 会恒空）
          this.warehouses = rowsOf(res)
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('仓库下拉加载失败：' + d.text)
        })
    },
    handleSubmit() {
      const judged = rules.validatePush(this.kind, this.row && this.row.status, this.rows)
      if (!judged.ok) {
        this.$modal.msgWarning(judged.message)
        return
      }
      // 请求形状（URL / body 形态 / query 参数）由 `doc-rules.buildPushRequest` 按链派发：
      //   采购申请 → 裸数组 body + query(supplierId/remark)；
      //   采购单   → 裸数组 body + query(warehouseId/remark)（收货仓库必填，见 validatePushExtra）；
      //   销售申请/销售订单 → {lines:[...]} body。
      const extra = this.buildExtra()
      const extraJudged = rules.validatePushExtra(this.kind, extra)
      if (!extraJudged.ok) {
        this.$modal.msgWarning(extraJudged.message)
        return
      }
      this.submitting = true
      api
        .pushDoc(this.kind.code, this.row.id, this.rows, extra)
        .then((res) => {
          this.submitting = false
          this.$modal.msgSuccess('已生成' + (this.chain ? this.chain.toLabel : '下游单据') + '草稿')
          this.$emit('pushed', (res && res.data) || null)
          this.show = false
        })
        .catch((err) => {
          this.submitting = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /** 端点额外入参（按链给）：采购申请给建议供应商；采购单给收货仓库。 */
    buildExtra() {
      if (this.kind.code === 'purchase_request') {
        return { supplierId: this.row.suggestSupplierId }
      }
      if (this.needWarehouse) {
        return { warehouseId: this.warehouseId }
      }
      return {}
    },
    handleClosed() {
      this.rows = []
      this.loadError = ''
      this.loadErrorCause = ''
      this.submitting = false
      this.warehouseId = ''
    }
  }
}
</script>

<style lang="scss" scoped>
.push-tip {
  margin-bottom: var(--oa-space-xs);
  font: var(--oa-font-body-sm);
  color: var(--oa-color-ink-muted);
}
.push-code {
  margin-left: var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.push-extra {
  margin-top: var(--oa-space-xs);
}
.push-extra-tip {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.push-total {
  margin-top: var(--oa-space-xs);
  text-align: right;
  font: var(--oa-font-body-sm);
}
</style>
