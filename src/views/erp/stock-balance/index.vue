<template>
  <div class="app-container">
    <!--
      「库存明细」页（B4 §8.5 / tasks.md 7.1、7.2、7.4；菜单 component = `erp/stock-balance/index`）。
      数据源：`GET /stk/stock/list`（`stk:stock:list`）+ `GET|POST /stk/stock/export`（`stk:stock:export`）
             + `POST /stk/stock/recalc?repair=false|true`（`stk:stock:recalc`）
             + 流水下钻 `GET /stk/ledger/list`（`stk:ledger:list`）。
      **结存没有写入口**（设计 D2）：唯一写动作是运维的「库存重算（修复）」，必须二次确认。
    -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="物料关键字">
        <el-input
          v-model="queryParams.keyword"
          placeholder="物料编码 / 名称 / 规格（模糊）"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="仓库">
        <el-select v-model="queryParams.warehouseId" filterable clearable placeholder="全部仓库" style="width: 170px">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品类型">
        <el-cascader
          v-model="queryParams.productTypeIds"
          :options="typeTree"
          :props="cascaderProps"
          clearable
          collapse-tags
          placeholder="全部类型（含子树）"
          style="width: 240px"
        />
      </el-form-item>
      <el-form-item label="数量区间">
        <el-input-number v-model="queryParams.beginQty" :precision="3" :controls="false" placeholder="下限" style="width: 110px" />
        <span class="range-sep">—</span>
        <el-input-number v-model="queryParams.endQty" :precision="3" :controls="false" placeholder="上限" style="width: 110px" />
      </el-form-item>
      <el-form-item label="低于安全库存">
        <el-switch v-model="queryParams.belowSafetyOnly" />
      </el-form-item>
      <el-form-item label="隐藏零结存">
        <el-switch v-model="queryParams.hideZero" />
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
          v-hasPermi="[perms.balanceExport]"
          @click="handleExport"
          >导出</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-refresh"
          size="mini"
          :loading="recalcLoading"
          v-hasPermi="[perms.balanceRecalc]"
          @click="handleRecalc(false)"
          >库存重算（仅校验）</el-button
        >
      </el-col>
      <el-col :span="1.5" v-if="recalcResult && recalcResult.inconsistentCount > 0">
        <el-button type="danger" size="mini" icon="el-icon-warning-outline" @click="handleRepair">修复结存…</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <p class="balance-subtitle">
      结存数量只能由过账/红冲改写（无手写入口）；「货品总额度」按业务类型子串匹配「入库」且排除「调拨入库」计算，只读展示。
    </p>

    <!-- 重算结果（校验/修复共用一套展示） -->
    <el-alert
      v-if="recalcResult"
      :title="recalcSummary"
      :type="recalcResult.inconsistentCount > 0 ? 'warning' : 'success'"
      :closable="true"
      show-icon
      @close="recalcResult = null"
    />
    <el-table
      v-if="recalcResult && recalcResult.mismatches && recalcResult.mismatches.length"
      :data="recalcResult.mismatches"
      size="mini"
      border
      class="mismatch-table"
    >
      <el-table-column label="物料ID" prop="productId" min-width="220" show-overflow-tooltip />
      <el-table-column label="仓库ID" prop="warehouseId" min-width="220" show-overflow-tooltip />
      <el-table-column label="结存 / 流水累计 / 差值" min-width="220">
        <template slot-scope="scope">{{ mismatchText(scope.row) }}</template>
      </el-table-column>
    </el-table>

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="list" border size="mini">
      <el-table-column label="商品类型-物料名称" min-width="220" show-overflow-tooltip>
        <template slot-scope="scope">{{ displayName(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="物料编码" prop="productCode" width="130" show-overflow-tooltip />
      <el-table-column label="规格" prop="spec" width="140" show-overflow-tooltip />
      <el-table-column label="单位" prop="uomName" width="80" align="center" />
      <el-table-column label="仓库" prop="warehouseName" width="140" show-overflow-tooltip />
      <el-table-column label="结存数量" width="120" align="right">
        <template slot-scope="scope">
          <span :class="{ 'below-safety': isBelowSafety(scope.row) }">{{ formatQty(scope.row.qty) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="安全库存" width="110" align="right">
        <template slot-scope="scope">{{ formatQty(scope.row.safetyStock) }}</template>
      </el-table-column>
      <el-table-column label="货品总额度" width="130" align="right">
        <template slot-scope="scope">{{ moneyText(scope.row.goodsQuota) }}</template>
      </el-table-column>
      <el-table-column label="最后变动时间" prop="updateTime" width="160" align="center" />
      <el-table-column label="操作" width="110" align="center" fixed="right">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            icon="el-icon-download"
            v-hasPermi="[perms.ledgerList]"
            @click="openLedger(scope.row)"
            >流水下钻</el-button
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

    <!-- 流水下钻抽屉：同一个 (物料, 仓库) + 日期区间，列定义与「库存流水」页共用 LedgerTable -->
    <el-drawer :title="drawerTitle" :visible.sync="drawerVisible" size="60%" append-to-body>
      <div class="drawer-body">
        <el-form size="small" :inline="true">
          <el-form-item label="记账时间">
            <el-date-picker
              v-model="drawerRange"
              type="datetimerange"
              :value-format="dateTimeFormat"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              style="width: 340px"
            />
          </el-form-item>
          <el-form-item>
            <span class="drawer-tip">红冲记录以「红冲-」前缀标记；原流水保留可查。</span>
          </el-form-item>
        </el-form>
        <LedgerTable v-if="drawerVisible" :query="drawerQuery" />
      </div>
    </el-drawer>
  </div>
</template>

<script>
/**
 * 库存明细页（B4 §8.5 / tasks.md 7.1、7.2、7.4）。
 *
 * 三个必须知道的点：
 *   ① 名称列显示「商品类型名称-物料名称」（服务端 `ErpStockBalance.getDisplayName()` 已算好，
 *      前端 `displayName()` 只在该字段缺失时兜底拼接）；
 *   ② 「货品总额度」(`goodsQuota`) 是服务端按"子串匹配入库、排除调拨入库"算的，前端**只显示**；
 *   ③ 「库存重算」默认**只校验**（`repair=false`）；点「修复结存…」才发 `repair=true`，
 *      并且必须二次确认 + 展示不可回滚的风险提示（tasks.md 7.4）。
 */
import * as api from '@/api/erp/stock'
import rules from './stock-balance-rules'
import LedgerTable from '../stock-ledger/components/LedgerTable'
import { treeEnabledProductTypes, listEnabledWarehouses } from '@/api/erp/masterdata'
import { rowsOf } from '../doc/erp-response'
import erpConst from '../erp-const'
import { moneyText } from '../doc/erp-amounts'
import { describeError } from '@/utils/errorMessage'
import DataLoadError from '@/components/DataLoadError'

export default {
  name: 'ErpStockBalance',
  components: { LedgerTable, DataLoadError },
  data() {
    return {
      loading: false,
      showSearch: true,
      perms: rules.PERMS,
      list: [],
      total: 0,
      queryParams: this.buildQuery(),
      warehouses: [],
      typeTree: [],
      loadError: '',
      loadErrorCause: '',
      recalcLoading: false,
      recalcResult: null,
      drawerVisible: false,
      drawerTitle: '库存流水',
      drawerRow: null,
      drawerRange: []
    }
  },
  computed: {
    dateTimeFormat() {
      return erpConst.DATETIME_FORMAT
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
    /** 下钻条件：(物料, 仓库) + 抽屉里的时间区间（tasks.md 7.3）。 */
    drawerQuery() {
      const row = this.drawerRow || {}
      return rules.ledgerQuery({ productId: row.productId, warehouseId: row.warehouseId }, this.drawerRange)
    },
    recalcSummary() {
      return rules.recalcSummary(this.recalcResult)
    }
  },
  created() {
    this.loadSelects()
    this.getList()
  },
  methods: {
    buildQuery(extra) {
      return Object.assign(
        {
          pageNum: 1,
          pageSize: 10,
          keyword: undefined,
          warehouseId: undefined,
          productTypeIds: undefined,
          beginQty: undefined,
          endQty: undefined,
          belowSafetyOnly: false,
          hideZero: false
        },
        extra || {}
      )
    },
    formatQty(v) {
      return rules.formatQty(v)
    },
    moneyText(v) {
      return moneyText(v)
    },
    displayName(row) {
      return rules.displayNameOf(row)
    },
    isBelowSafety(row) {
      return rules.isBelowSafety(row)
    },
    mismatchText(row) {
      return rules.mismatchText(row)
    },
    loadSelects() {
      listEnabledWarehouses()
        .then((res) => {
          // 仓库是**列表类接口**（体 `{rows,total}`）⇒ 必须用 rowsOf；
          // 裸读 res.data 会恒得 [] ⇒ 仓库筛选空（t46/t51 的同源 P0）。
          this.warehouses = rowsOf(res)
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('仓库下拉加载失败：' + d.text)
        })
      treeEnabledProductTypes()
        .then((res) => {
          this.typeTree = markDisabled((res && res.data) || [])
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgWarning('商品类型下拉加载失败：' + d.text)
        })
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      api
        .listBalance(rules.balanceQuery(this.queryParams, {
          pageNum: this.queryParams.pageNum,
          pageSize: this.queryParams.pageSize
        }))
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
      this.queryParams = this.buildQuery()
      this.handleQuery()
    },
    handleExport() {
      const stamp = this.parseTime(new Date(), '{y}{m}{d}{h}{i}{s}')
      api.exportBalance(
        rules.balanceQuery(this.queryParams, { pageNum: 1, pageSize: 1 }),
        '库存明细_' + stamp + '.xlsx'
      )
    },
    /**
     * 库存重算：默认只校验（repair=false）。
     * 修复入口单独收在 `handleRepair()` 里（二次确认 + 风险提示）。
     */
    handleRecalc(repair) {
      this.recalcLoading = true
      api
        .recalcStock(repair === true)
        .then((res) => {
          this.recalcLoading = false
          this.recalcResult = (res && res.data) || { checkedRows: 0, consistent: true, inconsistentCount: 0 }
          if (this.recalcResult.inconsistentCount > 0 && repair !== true) {
            this.$modal.msgWarning('发现 ' + this.recalcResult.inconsistentCount + ' 条不一致，当前未做任何改动')
          } else {
            this.$modal.msgSuccess('库存重算完成')
          }
          if (repair === true) {
            this.getList()
          }
        })
        .catch((err) => {
          this.recalcLoading = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /** 修复：二次确认 + 逐字展示风险提示（不可回滚），确认后才发 `repair=true`。 */
    handleRepair() {
      this.$modal
        .confirm(rules.repairWarningText() + ' 确认继续修复？')
        .then(() => this.handleRecalc(true))
        .catch((err) => {
          // confirm 的取消（'cancel'/'close'）不需要提示
          if (err === 'cancel' || err === 'close') {
            return
          }
        })
    },
    openLedger(row) {
      this.drawerRow = row
      this.drawerRange = []
      this.drawerTitle = '库存流水 · ' + rules.displayNameOf(row) + ' @ ' + (row.warehouseName || '')
      this.drawerVisible = true
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
.balance-subtitle {
  margin: 0 0 var(--oa-space-xs);
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.range-sep {
  margin: 0 var(--oa-space-xxs);
  color: var(--oa-color-ink-subtle);
}
.below-safety {
  color: var(--oa-color-warning);
  font-weight: 600;
}
.mismatch-table {
  margin-bottom: var(--oa-space-sm);
}
.drawer-body {
  padding: 0 var(--oa-space-md) var(--oa-space-md);
}
.drawer-tip {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
</style>
