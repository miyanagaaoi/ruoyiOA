<template>
  <div class="ledger-table">
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="list" size="mini" border max-height="420">
      <el-table-column label="记账时间" width="160" align="center">
        <template slot-scope="scope">{{ scope.row.ledgerTime || '—' }}</template>
      </el-table-column>
      <el-table-column label="业务类型" width="130" align="center">
        <template slot-scope="scope">
          <el-tag :type="scope.row.reversal ? 'danger' : 'info'" size="mini" disable-transitions>
            {{ scope.row.bizType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="单据类型" prop="docType" width="120" align="center" />
      <el-table-column label="单据号" prop="docNo" min-width="160" show-overflow-tooltip />
      <el-table-column label="来源单号" prop="srcDocNo" width="150" show-overflow-tooltip>
        <template slot-scope="scope">
          <span v-if="scope.row.srcDocNo">{{ scope.row.srcDocNo }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="数量变动" width="110" align="right">
        <template slot-scope="scope">
          <span :class="changeClass(scope.row)">{{ signed(scope.row.qtyChange) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="变动后结存" width="120" align="right">
        <template slot-scope="scope">{{ formatQty(scope.row.qtyAfter) }}</template>
      </el-table-column>
      <el-table-column label="单价" width="110" align="right">
        <template slot-scope="scope">{{ priceText(scope.row.unitPrice) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="110" align="center">
        <template slot-scope="scope">{{ scope.row.createBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />
  </div>
</template>

<script>
/**
 * 库存流水表（**共用件**）：库存明细页的"流水下钻抽屉"与「库存流水」独立页都用它。
 *
 * 为什么抽出来：tasks.md 7.3 要求"按（物料, 仓库）与日期区间下钻、按时间倒序、每条含业务类型/
 * 单据类型/单据号/来源单号/数量变动/变动后结存/单价/操作人/时间"；两处渲染同一份列定义，
 * 抄两遍必然漂移（D12 的复用原则同样适用于"账"页面）。
 *
 * 流水**只增不改**：这里没有任何编辑/删除入口（服务端也没有），红冲是追加负数流水（`bizType` 带前缀）。
 */
import * as api from '@/api/erp/stock'
import rules from '@/views/erp/stock-balance/stock-balance-rules'
import { describeError } from '@/utils/errorMessage'
import DataLoadError from '@/components/DataLoadError'

export default {
  name: 'LedgerTable',
  components: { DataLoadError },
  props: {
    // (物料, 仓库) 下钻条件；作为独立页面使用时由外层筛选决定。传 null 表示"先别查"
    query: { type: Object, default: null }
  },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 50 },
      loadError: '',
      loadErrorCause: ''
    }
  },
  watch: {
    query: {
      deep: true,
      handler() {
        this.queryParams.pageNum = 1
        this.getList()
      }
    }
  },
  created() {
    // 父组件的 created 先于子组件执行 ⇒ 首次挂载时 `query` 已就绪就查一次；之后由 watcher 驱动。
    if (this.query) {
      this.getList()
    }
  },
  methods: {
    formatQty(v) {
      return rules.formatQty(v)
    },
    priceText(v) {
      if (v === null || v === undefined || v === '') {
        return '—'
      }
      return Number(v).toFixed(4)
    },
    signed(v) {
      const n = Number(v)
      if (!isFinite(n) || n === 0) {
        return '0'
      }
      return (n > 0 ? '+' : '') + rules.formatQty(n)
    },
    changeClass(row) {
      const n = Number(row && row.qtyChange)
      if (!isFinite(n) || n === 0) {
        return ''
      }
      return n > 0 ? 'ledger-in' : 'ledger-out'
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      const base = this.query || {}
      api
        .listLedger(Object.assign({}, base, this.queryParams))
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
    }
  }
}
</script>

<style lang="scss" scoped>
.ledger-in {
  color: var(--oa-color-success);
}
.ledger-out {
  color: var(--oa-color-error);
}
</style>
