<template>
  <div class="app-container">
    <!--
      「库存流水」独立页（B4 §8.5 / tasks.md 7.3；菜单 component = `erp/stock-ledger/index`）。
      与库存明细页的"流水下钻抽屉"共用 `LedgerTable`（列定义只有一份）。
      **只增不改**：没有修改/删除入口；红冲是追加负数流水（业务类型带 `红冲-` 前缀，标签标红）。
    -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="物料关键字">
        <el-input
          v-model="queryParams.keyword"
          placeholder="单据号 / 来源单号（模糊）"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="仓库">
        <el-select v-model="queryParams.warehouseId" filterable clearable placeholder="全部仓库" style="width: 180px">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="业务类型">
        <el-select v-model="queryParams.bizTypeFilter" filterable clearable placeholder="全部" style="width: 160px">
          <!-- 字典行是 {dictLabel,dictValue}：必须经 dictSelectOptions 转 {value,label}，
               直接循环原始行会渲染成**空条目**（下拉有点选行却没有文字，t46/t51 的同源 P0） -->
          <el-option v-for="opt in dictSelectOptions('stock_biz_types')" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="单据类型">
        <el-select v-model="queryParams.docTypeFilter" filterable clearable placeholder="全部" style="width: 150px">
          <el-option v-for="opt in docTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="记账时间">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          :value-format="dateTimeFormat"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
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
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          v-hasPermi="[perms.ledgerExport]"
          @click="handleExport"
          >导出</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="reload"></right-toolbar>
    </el-row>

    <p class="ledger-subtitle">流水按记账时间倒序；红冲记录以「红冲-」前缀标记，原流水保留可查、不可修改删除。</p>

    <LedgerTable ref="table" :query="submittedQuery" />
  </div>
</template>

<script>
/**
 * 库存流水页（B4 §8.5 / tasks.md 7.3）。
 *
 * 数据源：`GET /stk/ledger/list`（权限 `stk:ledger:list`；导出 `stk:ledger:export`），
 * 筛选真源 = `ErpStockLedgerQueryMapper.xml`：`keyword`（单据号/来源单号）、`warehouseId`、
 * `bizTypeFilter`、`docTypeFilter`、`beginTime`/`endTime`（`datetime`，用日期时间串）、`productId`。
 *
 * 为什么"提交态"与"编辑态"分开：筛选改动只在点「搜索」时生效（与列表页一致），
 * 避免每敲一个字就打一次接口；`submittedQuery` 就是真正发给后端的参数。
 */
import LedgerTable from './components/LedgerTable'
import * as api from '@/api/erp/stock'
import rules from '@/views/erp/stock-balance/stock-balance-rules'
import { listEnabledWarehouses } from '@/api/erp/masterdata'
import { rowsOf } from '../doc/erp-response'
import { dictRowsToOptions } from '../doc/doc-select-shape'
import { getDicts } from '@/api/system/dict/data'
import erpConst from '../erp-const'
import { describeError } from '@/utils/errorMessage'

export default {
  name: 'ErpStockLedger',
  components: { LedgerTable },
  data() {
    return {
      showSearch: true,
      perms: rules.PERMS,
      timeRange: [],
      queryParams: {
        keyword: '',
        warehouseId: undefined,
        bizTypeFilter: undefined,
        docTypeFilter: undefined
      },
      submittedQuery: {},
      warehouses: [],
      dictData: {},
      dictFailed: []
    }
  },
  computed: {
    dateTimeFormat() {
      return erpConst.DATETIME_FORMAT
    },
    docTypeOptions() {
      // 流水 doc_type 的三个取值（与 DDL `t_ctms_stock_ledger.doc_type` 注释一致）
      return [
        { value: 'stock_in', label: '入库单' },
        { value: 'stock_out', label: '出库单' },
        { value: 'stock_transfer', label: '调拨单' }
      ]
    }
  },
  created() {
    this.loadDicts()
    this.loadWarehouses()
    this.handleQuery()
  },
  methods: {
    dictOptions(name) {
      return this.dictData[name] || []
    },
    /** 字典**下拉**选项（`{value,label}`；el-select 用 —— 原始字典行会渲染成空条目）。 */
    dictSelectOptions(name) {
      return dictRowsToOptions(this.dictData[name])
    },
    loadDicts() {
      getDicts('stock_biz_types')
        .then((res) => {
          this.$set(this.dictData, 'stock_biz_types', (res && res.data) || [])
        })
        .catch((err) => {
          const d = describeError(err)
          this.dictFailed.push('stock_biz_types：' + d.text)
        })
    },
    loadWarehouses() {
      listEnabledWarehouses()
        .then((res) => {
          // 仓库是列表类接口（体 `{rows,total}`）⇒ rowsOf；裸读 res.data 会让仓库筛选恒空
          this.warehouses = rowsOf(res)
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('仓库下拉加载失败：' + d.text)
        })
    },
    handleQuery() {
      // 筛选真源 = ErpStockLedgerQueryMapper.xml（keyword/warehouseId/bizTypeFilter/docTypeFilter/beginTime/endTime）
      this.submittedQuery = {
        keyword: this.queryParams.keyword || undefined,
        warehouseId: this.queryParams.warehouseId,
        bizTypeFilter: this.queryParams.bizTypeFilter,
        docTypeFilter: this.queryParams.docTypeFilter,
        beginTime: this.timeRange && this.timeRange.length ? this.timeRange[0] : undefined,
        endTime: this.timeRange && this.timeRange.length ? this.timeRange[1] : undefined
      }
    },
    resetQuery() {
      this.timeRange = []
      this.queryParams = { keyword: '', warehouseId: undefined, bizTypeFilter: undefined, docTypeFilter: undefined }
      this.handleQuery()
    },
    reload() {
      if (this.$refs.table) {
        this.$refs.table.queryParams.pageNum = 1
        this.$refs.table.getList()
      }
    },
    handleExport() {
      const stamp = this.parseTime(new Date(), '{y}{m}{d}{h}{i}{s}')
      api.exportLedger(this.submittedQuery, '库存流水_' + stamp + '.xlsx')
    }
  }
}
</script>

<style lang="scss" scoped>
.ledger-subtitle {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
</style>
