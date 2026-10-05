<template>
  <div class="doc-items-table">
    <el-table :data="items" size="mini" border :cell-class-name="cellClassName">
      <el-table-column
        v-for="col in cols"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.align || 'left'"
      >
        <template slot-scope="scope">
          <!-- 序号（渲染下标；服务端按提交顺序重排 seq） -->
          <span v-if="col.kind === 'seq'">{{ scope.$index + 1 }}</span>

          <!-- 物料选择（选择后带出编码/规格/单位/小数位快照与默认单价） -->
          <template v-else-if="col.kind === 'product'">
            <el-select
              v-if="colEditable(col)"
              v-model="scope.row.productId"
              filterable
              clearable
              placeholder="请选择物料"
              class="erp-cell-control"
              @change="handleProductChange(scope.row)"
            >
              <el-option v-for="p in products" :key="p.id" :label="productLabel(p)" :value="p.id" />
            </el-select>
            <span v-else>{{ scope.row.productName }}</span>
          </template>

          <!-- 数量：精度按单位小数位 -->
          <template v-else-if="col.kind === 'qty'">
            <el-input-number
              v-if="colEditable(col)"
              v-model="scope.row.qty"
              :precision="qtyPrecision(scope.row)"
              :min="0"
              controls-position="right"
              class="erp-cell-control"
              @change="handleQtyChange(scope.row)"
            />
            <span v-else>{{ formatQty(scope.row[col.prop]) }}</span>
          </template>

          <!-- 实盘数量（盘点）：改动后即算差异 -->
          <template v-else-if="col.kind === 'actualQty'">
            <el-input-number
              v-if="colEditable(col)"
              v-model="scope.row.actualQty"
              :precision="qtyPrecision(scope.row)"
              :min="0"
              controls-position="right"
              class="erp-cell-control"
              @change="handleActualQtyChange(scope.row)"
            />
            <span v-else>{{ formatQty(scope.row.actualQty) }}</span>
          </template>

          <!-- 差异（实盘 − 账面，3 位） -->
          <template v-else-if="col.kind === 'diffQty'">
            <span :class="diffClass(scope.row)">{{ diffText(scope.row) }}</span>
          </template>

          <!-- 单价 -->
          <template v-else-if="col.kind === 'price'">
            <el-input-number
              v-if="colEditable(col)"
              v-model="scope.row.unitPrice"
              :precision="4"
              :min="0"
              controls-position="right"
              class="erp-cell-control"
              @change="handlePriceChange(scope.row)"
            />
            <span v-else>{{ formatPrice(scope.row[col.prop]) }}</span>
          </template>

          <!-- 行金额（数量 × 单价先舍入到 2 位；与打印/导出同一份定点实现） -->
          <template v-else-if="col.kind === 'money'">
            <span>{{ moneyText(scope.row[col.prop]) }}</span>
          </template>

          <!-- 行级仓库（未填时服务端回落表头仓库） -->
          <template v-else-if="col.kind === 'warehouse'">
            <el-select
              v-if="colEditable(col)"
              v-model="scope.row.warehouseId"
              filterable
              clearable
              placeholder="默认表头仓库"
              class="erp-cell-control"
              @change="handleWarehouseChange(scope.row)"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <span v-else>{{ scope.row.warehouseName }}</span>
          </template>

          <!-- 普通文本 / 行备注 -->
          <template v-else>
            <el-input
              v-if="colEditable(col)"
              v-model="scope.row[col.prop]"
              size="mini"
              :maxlength="1000"
              placeholder="选填"
            />
            <span v-else>{{ scope.row[col.prop] }}</span>
          </template>

          <!-- 行内校验提示：只在有问题的单元格上出现，不打断编辑 -->
          <i
            v-if="problemOf(scope.row, col).length"
            class="el-icon-warning-outline erp-cell-warn"
            :title="problemOf(scope.row, col).join('；')"
          />
        </template>
      </el-table-column>

      <el-table-column v-if="!readonly" label="操作" width="70" align="center" fixed="right">
        <template slot-scope="scope">
          <el-button type="text" size="mini" class="erp-row-del" @click="removeRow(scope.$index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!readonly" class="doc-items-actions">
      <el-button type="primary" plain size="mini" icon="el-icon-plus" @click="addRow">添加行项</el-button>
      <span class="doc-items-hint">{{ hint }}</span>
    </div>
  </div>
</template>

<script>
/**
 * 单据行项表（8 类单据共用；D12 的"行项表"）。
 *
 * 设计要点：
 *   · 列由 `kind.itemColumns` 配置驱动 —— 调拨隐藏单价/金额、盘点用账面/实盘/差异列、
 *     出入库与采购单有行级仓库列，全部来自配置；组件里没有按单据码分叉的分支树。
 *   · 就地编辑数组元素（参考仓库 `DocItemsTable` 同款）：行项数组由表单壳持有，
 *     这里只改元素字段并发 `change`，避免整数组来回拷贝把用户输入冲掉。
 *   · 行金额走 `erp-amounts`（= B3 的定点实现）：改数量/单价即重算并写回 `row.amount`，
 *     与服务端"先舍入再汇总"口径一致（AC-78 的四方一致）。
 *   · 精度：数量 `precision` 取该行 `uomDecimals`（快照列，0~4）；超位只做行内提示，
 *     提交前的整体校验由表单壳调 `doc-rules.validateItems`，最终由服务端拒绝。
 *   · 物料名/规格/单位名/单位小数位是**快照列**：必须由前端从物料档案带出后落库，
 *     历史单据改名/停用后不再随之变化（AC-72）。
 */
import rules from '../doc-rules'
import { moneyText, lineTotalText } from '../erp-amounts'

export default {
  name: 'DocItemsTable',
  props: {
    // 单据类型配置（doc-kinds 的一项）
    kind: { type: Object, required: true },
    // 行项数组（就地编辑）
    items: { type: Array, required: true },
    // 只读态（查看 / 非草稿）
    readonly: { type: Boolean, default: false },
    // 下拉选项 { products, uoms, warehouses }
    options: { type: Object, default: null }
  },
  computed: {
    cols() {
      return (this.kind && this.kind.itemColumns) || []
    },
    products() {
      return (this.options && this.options.products) || []
    },
    uoms() {
      return (this.options && this.options.uoms) || []
    },
    warehouses() {
      return (this.options && this.options.warehouses) || []
    },
    hint() {
      if (this.kind && this.kind.specialForm === 'stocktake') {
        return '账面数量只读；差异 = 实盘 − 账面（3 位小数）'
      }
      if (this.kind && this.kind.showWarehouse) {
        return '行项未填仓库时回落表头仓库；数量精度按物料单位小数位'
      }
      return '数量精度按物料单位小数位；金额 = 数量 × 单价（先舍入到 2 位）'
    }
  },
  methods: {
    colEditable(col) {
      return !this.readonly && col.editable === true
    },
    productLabel(p) {
      return p.code ? p.code + ' · ' + p.name : p.name
    },
    qtyPrecision(row) {
      const d = Number(row.uomDecimals)
      if (!isFinite(d) || row.uomDecimals === undefined || row.uomDecimals === null) {
        return 3
      }
      return d
    },
    formatQty(v) {
      return rules.formatQty(v)
    },
    formatPrice(v) {
      if (v === null || v === undefined || v === '') {
        return '—'
      }
      return Number(v).toFixed(4)
    },
    moneyText(v) {
      return moneyText(v)
    },
    /**
     * 选物料 → 带出快照。
     *
     * ⚠ 物料列表接口返回的是物料档案列（`CtmsProduct`），**不含**单位名与单位小数位；
     *   所以单位名/小数位从计量单位选项里按 `uomId` 反查（B3 已有的 `/ctms/uom/list`）。
     *   若后端后续在物料列表里带上单位字段，这里的兜底顺序（档案优先、选项兜底）无需改。
     */
    handleProductChange(row) {
      const product = this.products.filter((p) => p.id === row.productId)[0]
      if (product) {
        const uom = this.uoms.filter((u) => u.id === product.uomId)[0]
        row.productCode = product.code
        row.productName = product.name
        row.spec = product.spec
        row.uomId = product.uomId
        row.uomName = product.uomName || (uom ? uom.name : '')
        row.uomDecimals =
          product.decimals === undefined || product.decimals === null ? (uom ? uom.decimals : null) : product.decimals
        if ((row.unitPrice === undefined || row.unitPrice === null || row.unitPrice === '') && product.defaultPrice) {
          row.unitPrice = product.defaultPrice
        }
      } else {
        row.productCode = ''
        row.productName = ''
        row.spec = ''
        row.uomId = ''
        row.uomName = ''
        row.uomDecimals = null
      }
      this.recomputeAmount(row)
      this.$emit('change', this.items)
    },
    handleQtyChange(row) {
      this.recomputeAmount(row)
      this.$emit('change', this.items)
    },
    handlePriceChange(row) {
      this.recomputeAmount(row)
      this.$emit('change', this.items)
    },
    handleWarehouseChange(row) {
      const wh = this.warehouses.filter((w) => w.id === row.warehouseId)[0]
      row.warehouseName = wh ? wh.name : ''
      this.$emit('change', this.items)
    },
    /** 实盘数量变化 → 差异 = 实盘 − 账面（3 位）。 */
    handleActualQtyChange(row) {
      row.diffQty = computeDiff(row.bookQty, row.actualQty)
      this.$emit('change', this.items)
    },
    recomputeAmount(row) {
      if (this.kind && this.kind.showAmount === false) {
        row.amount = 0
        return
      }
      const ok = row.qty !== undefined && row.qty !== null && row.unitPrice !== undefined && row.unitPrice !== null
      row.amount = ok ? lineTotalText(row.qty, row.unitPrice) : 0
    },
    diffValue(row) {
      return computeDiff(row.bookQty, row.actualQty)
    },
    diffText(row) {
      const v = Number(this.diffValue(row))
      if (!isFinite(v) || v === 0) {
        return '0'
      }
      return (v > 0 ? '+' : '') + rules.formatQty(v)
    },
    diffClass(row) {
      const v = Number(this.diffValue(row))
      if (!isFinite(v) || v === 0) {
        return ''
      }
      return v > 0 ? 'erp-diff-up' : 'erp-diff-down'
    },
    /** 行/列级问题（行内提示用；只在**可编辑**的列上判，避免只读快照列被误判）。 */
    problemOf(row, col) {
      const problems = []
      if (!col.editable) {
        return problems
      }
      if (col.kind === 'product' && !row.productId) {
        problems.push('未选择物料')
      }
      if (col.kind === 'qty' && !(Number(row.qty) > 0)) {
        problems.push('数量必须大于 0')
      }
      if (col.kind === 'actualQty' && Number(row.actualQty) < 0) {
        problems.push('实盘数量不能为负数')
      }
      if ((col.kind === 'qty' || col.kind === 'actualQty') && rules.countDecimals(row[col.prop]) > this.qtyPrecision(row)) {
        problems.push('超出单位小数位（' + this.qtyPrecision(row) + ' 位）')
      }
      if (col.kind === 'price' && Number(row.unitPrice) < 0) {
        problems.push('单价不能为负数')
      }
      return problems
    },
    cellClassName({ row, column }) {
      const col = this.cols.filter((c) => c.prop === column.property)[0]
      if (!col) {
        return ''
      }
      return this.problemOf(row, col).length ? 'erp-cell-invalid' : ''
    },
    /** 新增空行。 */
    addRow() {
      const row = {
        productId: '',
        productCode: '',
        productName: '',
        spec: '',
        uomId: '',
        uomName: '',
        uomDecimals: null,
        qty: 1,
        unitPrice: 0,
        amount: '0.00',
        warehouseId: '',
        warehouseName: '',
        remark: ''
      }
      if (this.kind && this.kind.specialForm === 'stocktake') {
        row.bookQty = 0
        row.actualQty = 0
        row.diffQty = '0.000'
        row.diffReason = ''
      }
      this.items.push(row)
      this.$emit('change', this.items)
    },
    removeRow(index) {
      this.items.splice(index, 1)
      this.$emit('change', this.items)
    }
  }
}

/** 差异 = 实盘 − 账面（秒转成千分整数再相减，避免 0.1+0.2 类浮点噪声）。 */
function computeDiff(bookQty, actualQty) {
  return ((toScaled(actualQty) - toScaled(bookQty)) / 1000).toFixed(3)
}

function toScaled(v) {
  const n = Number(v)
  if (!isFinite(n)) {
    return 0
  }
  return Math.round(n * 1000)
}
</script>

<style lang="scss" scoped>
.doc-items-table {
  margin-bottom: var(--oa-space-sm);
}
.erp-cell-control {
  width: 100%;
}
.erp-cell-warn {
  margin-left: var(--oa-space-xxs);
  color: var(--oa-color-warning);
}
.doc-items-actions {
  display: flex;
  align-items: center;
  gap: var(--oa-space-sm);
  margin-top: var(--oa-space-xs);
}
.doc-items-hint {
  font: var(--oa-font-caption);
  color: var(--oa-color-ink-subtle);
}
.erp-diff-up {
  color: var(--oa-color-success);
}
.erp-diff-down {
  color: var(--oa-color-error);
}
</style>

<style lang="scss">
/* 非 scoped：el-table 的单元格类名由 cell-class-name 施加在内部 td 上 */
.el-table .erp-cell-invalid {
  background: var(--oa-color-warning-surface);
}
</style>
