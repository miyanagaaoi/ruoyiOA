<template>
  <div class="app-container">
    <!--
      「往来单位」页 = 客户档案 + 供应商档案两个页签。
      对齐 `sql/二开-合同台账-菜单.sql` 的菜单 `ctms/partner/index`（权限点 `ctms:partner:list`）。

      ⚠ 两个页签共用一个 `types` 配置，**不要在模板里复制两遍结构** ——
        上一批的教训是"两处结构改一处"（模板四页签的 model 共享问题，DEV-ENV §9.5）。
      ⚠ 「停用」只做软停用：停用后从 `/options`（合同表单选择器）消失，但管理列表仍能看到；
        表单里不提供物理删除的替代品，删除按钮只在零引用时才会被服务端放行。
      ⚠ 前端校验文案与后端 `PartnerRules` **逐字一致**（见脚本区的 MSG_* 常量）：
        供应商简称必填（纯空白也算空，与后端 isBlank 同口径）、账期天数不能为负数；
        最终判定仍在服务端，前端只做"同样的判定、同样的文案"以便用户立刻看到原因。
    -->
    <el-tabs v-model="activeType" @tab-click="handleTypeChange">
      <el-tab-pane :label="types.customer.label" name="customer" />
      <el-tab-pane :label="types.supplier.label" name="supplier" />
    </el-tabs>

    <!-- 选择器口径常驻提示：这是"停用后不在新增合同里出现"的落点，不藏在确认弹窗里 -->
    <div class="page-tip">
      选择器口径：合同表单的客户/供应商下拉走 <code>/options</code>，<strong>只返回启用中的档案</strong>；
      停用档案仍可在这里查到（历史合同照常显示其名称）。删除则只在"没有任何合同引用"时才会被服务端放行。
    </div>

    <!-- ==================== 查询条件 ==================== -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item :label="current.codeLabel" prop="code">
        <el-input
          v-model="queryParams.code"
          :placeholder="'请输入' + current.codeLabel"
          clearable
          style="width: 180px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入名称"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="enableFlag">
        <el-select v-model="queryParams.enableFlag" placeholder="全部" clearable style="width: 120px">
          <el-option label="启用" value="1" />
          <el-option label="停用" value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- ==================== 工具条 ==================== -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['ctms:partner:add']"
          >新增{{ current.shortLabel }}</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate()"
          v-hasPermi="['ctms:partner:edit']"
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
          @click="handleDelete()"
          v-hasPermi="['ctms:partner:remove']"
          >删除</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          :disabled="single"
          @click="handleToggleStatus()"
          v-hasPermi="['ctms:partner:status']"
          >{{ statusButtonText }}</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!--
      ⚠ 取数失败必须显式呈现（`tools/audit/run-all.js` 的"类 1/类 2"审计拦的就是
        "没有 .catch → loading 永久 true"）：这里沿用全项目的 DataLoadError 范式。
    -->
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table
      v-else
      v-loading="loading"
      :data="list"
      @selection-change="handleSelectionChange"
      :row-class-name="rowClassName"
    >
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column :label="current.codeLabel" align="center" prop="code" width="140" />
      <el-table-column label="名称" align="center" prop="name" min-width="200" show-overflow-tooltip />
      <el-table-column
        :label="current.shortLabel"
        align="center"
        prop="shortName"
        width="140"
        show-overflow-tooltip
      >
        <template slot-scope="scope">
          <span v-if="scope.row.shortName">{{ scope.row.shortName }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="联系人" align="center" prop="contactName" width="100">
        <template slot-scope="scope">
          <span v-if="scope.row.contactName">{{ scope.row.contactName }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="联系电话" align="center" prop="contactPhone" width="130">
        <template slot-scope="scope">
          <span v-if="scope.row.contactPhone">{{ scope.row.contactPhone }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="isSupplier" label="账期(天)" align="center" prop="paymentDays" width="90">
        <template slot-scope="scope">
          <span v-if="scope.row.paymentDays !== null && scope.row.paymentDays !== undefined">{{
            scope.row.paymentDays
          }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="启用状态" align="center" prop="enableFlag" width="110">
        <template slot-scope="scope">
          <el-tag :type="scope.row.enableFlag === '1' ? 'success' : 'info'" size="mini">
            {{ scope.row.enableFlag === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createTime" width="160">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="240" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="handleView(scope.row)"
            v-hasPermi="['ctms:partner:query']"
            >查看</el-button
          >
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['ctms:partner:edit']"
            >修改</el-button
          >
          <el-button
            size="mini"
            type="text"
            :icon="scope.row.enableFlag === '1' ? 'el-icon-remove-outline' : 'el-icon-circle-check'"
            @click="handleToggleStatus(scope.row)"
            v-hasPermi="['ctms:partner:status']"
            >{{ scope.row.enableFlag === '1' ? '停用' : '启用' }}</el-button
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

    <!-- ==================== 新增 / 修改 / 查看（只读） ==================== -->
    <el-dialog :title="title" :visible.sync="open" width="760px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px" :disabled="readonly">
        <el-row>
          <el-col :span="12">
            <el-form-item :label="current.codeLabel" prop="code">
              <el-input v-model="form.code" :placeholder="'请输入' + current.codeLabel" maxlength="32" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" placeholder="请输入名称" maxlength="128" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <!-- ⚠ 简称对两个方向的口径**刻意不对称**：客户选填、供应商必填（规格硬约束） -->
            <el-form-item :label="current.shortLabel" prop="shortName">
              <el-input
                v-model="form.shortName"
                :placeholder="isSupplier ? '必填（供应商简称）' : '选填'"
                maxlength="64"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="税号" prop="taxNo">
              <el-input v-model="form.taxNo" maxlength="32" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="联系人" prop="contactName">
              <el-input v-model="form.contactName" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="联系电话" prop="contactPhone">
              <el-input v-model="form.contactPhone" maxlength="32" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="255" />
        </el-form-item>
        <el-row>
          <el-col :span="12">
            <el-form-item label="开户行" prop="bankName">
              <el-input v-model="form.bankName" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="银行账号" prop="bankAccount">
              <el-input v-model="form.bankAccount" maxlength="64" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="授信额度" prop="creditLimit">
              <el-input-number
                v-model="form.creditLimit"
                :precision="2"
                :min="0"
                :controls="false"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="等级" prop="level">
              <el-select v-model="form.level" placeholder="请选择" clearable style="width: 100%">
                <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <!-- 供应商专有字段 -->
        <template v-if="isSupplier">
          <el-form-item label="供货范围" prop="supplyScope">
            <el-input v-model="form.supplyScope" maxlength="255" />
          </el-form-item>
          <el-form-item label="账期天数" prop="paymentDays">
            <el-input-number v-model="form.paymentDays" :min="0" :step="1" :precision="0" style="width: 200px" />
            <span class="form-tip">
              不小于 0 的整数：0 合法（＝现结）、空＝未填也合法；负数前端会拦下（提示「账期天数不能为负数」），
              后端同口径同样拒绝；小数会被前端拦下（后端按整数绑定）
            </span>
          </el-form-item>
        </template>
        <el-row>
          <el-col :span="12">
            <el-form-item label="状态" prop="enableFlag">
              <el-radio-group v-model="form.enableFlag">
                <el-radio label="1">启用</el-radio>
                <el-radio label="0">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button v-if="!readonly" type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">{{ readonly ? '关 闭' : '取 消' }}</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listCustomer, getCustomer, addCustomer, updateCustomer, delCustomer, changeCustomerStatus,
  listSupplier, getSupplier, addSupplier, updateSupplier, delSupplier, changeSupplierStatus
} from '@/api/ctms/partner'
import DataLoadError from '@/components/DataLoadError'
import { describeError } from '@/utils/errorMessage'

/** 客户 / 供应商两个方向的差异**全部**收在这张表里，避免在模板与脚本里各写一遍 */
const TYPES = {
  customer: {
    key: 'customer',
    label: '客户档案',
    shortLabel: '客户',
    codeLabel: '客户编码',
    api: { list: listCustomer, get: getCustomer, add: addCustomer, update: updateCustomer, del: delCustomer, status: changeCustomerStatus }
  },
  supplier: {
    key: 'supplier',
    label: '供应商档案',
    shortLabel: '供应商',
    codeLabel: '供应商编码',
    api: { list: listSupplier, get: getSupplier, add: addSupplier, update: updateSupplier, del: delSupplier, status: changeSupplierStatus }
  }
}

/**
 * 前端校验文案 —— **与后端 `com.ruoyi.ctms.support.PartnerRules` 逐字一致**。
 * 文案被接口验收脚本按关键字断言（PartnerRules 的类注释：文案是接口契约的一部分，改动即破坏性变更），
 * 所以前端不要"换个更顺口的说法"。
 */
const MSG_SUPPLIER_SHORT_NAME_REQUIRED = '供应商简称不能为空'
const MSG_PAYMENT_DAYS_NEGATIVE = '账期天数不能为负数'

/** 非整数是**前端专有**的拦截：后端 paymentDays 绑 Integer，传小数只会得到 400 */
const MSG_PAYMENT_DAYS_NOT_INTEGER = '账期天数必须是整数'

/**
 * 供应商简称的判定（纯函数，便于自检脚本直接跑）：**纯空白也算空**，
 * 与后端 `PartnerRules.isBlank` 同口径（el-form 的 required 判定不了 "   "，所以必须有这一段）。
 *
 * @param {*} value 简称
 * @returns {string} 错误文案；合法返回空串
 */
function supplierShortNameError(value) {
  if (value === undefined || value === null || String(value).trim() === '') {
    return MSG_SUPPLIER_SHORT_NAME_REQUIRED
  }
  return ''
}

/**
 * 账期天数的判定（纯函数）：空值合法（"未填"）、0 合法（现结，与 null 语义不同）、
 * 负数拒绝（与后端 `PartnerRules.checkPaymentDays` 同文案）、非整数拒绝。
 *
 * @param {*} value 账期天数
 * @returns {string} 错误文案；合法返回空串
 */
function paymentDaysError(value) {
  if (value === undefined || value === null || value === '') {
    return ''
  }
  const days = Number(value)
  if (!isFinite(days) || !Number.isInteger(days)) {
    return MSG_PAYMENT_DAYS_NOT_INTEGER
  }
  if (days < 0) {
    return MSG_PAYMENT_DAYS_NEGATIVE
  }
  return ''
}

export default {
  name: 'CtmsPartner',
  components: { DataLoadError },
  data() {
    return {
      activeType: 'customer',
      types: TYPES,
      levels: ['A', 'B', 'C', 'D'],
      // 遮罩层
      loading: false,
      // 取数失败（必须有，否则 loading 会永久为 true）
      loadError: '',
      loadErrorCause: '',
      // 选中数组
      ids: [],
      names: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      list: [],
      title: '',
      open: false,
      // 只读态（「查看」按钮）：表单整体 disabled，底部不出「确 定」
      readonly: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        code: undefined,
        name: undefined,
        enableFlag: undefined
      },
      form: {}
    }
  },
  computed: {
    current() {
      return this.types[this.activeType]
    },
    isSupplier() {
      return this.activeType === 'supplier'
    },
    /**
     * 工具条按钮文案：勾选的都是启用项 → 动作是"批量停用"，否则是"批量启用"。
     * 单条与批量的唯一差异就是这个文案，语义统一由 handleToggleStatus 判定。
     */
    statusButtonText() {
      if (this.multiple) {
        return '启停用'
      }
      const picked = this.list.filter((item) => this.ids.indexOf(item.id) >= 0)
      const anyEnabled = picked.some((r) => r.enableFlag === '1')
      return anyEnabled ? '批量停用' : '批量启用'
    },
    rules() {
      const supplier = this.isSupplier
      return {
        // 编码/名称的文案与后端逐字一致（后端：客户编码不能为空 / 供应商编码不能为空 …）
        code: [{ required: true, message: this.current.codeLabel + '不能为空', trigger: 'blur' }],
        name: [{ required: true, message: this.current.shortLabel + '名称不能为空', trigger: 'blur' }],
        // 供应商简称必填、客户简称选填 —— 这条不对称是规格硬约束
        shortName: supplier
          ? [
              // required 负责空串与红点，validator 负责"纯空白"（后端 isBlank 也把纯空白当空）
              { required: true, message: MSG_SUPPLIER_SHORT_NAME_REQUIRED, trigger: 'blur' },
              {
                validator: (rule, value, callback) => {
                  const message = supplierShortNameError(value)
                  if (message) {
                    callback(new Error(message))
                    return
                  }
                  callback()
                },
                trigger: 'blur'
              }
            ]
          : [],
        paymentDays: supplier
          ? [
              {
                validator: (rule, value, callback) => {
                  const message = paymentDaysError(value)
                  if (message) {
                    callback(new Error(message))
                    return
                  }
                  callback()
                },
                trigger: ['blur', 'change']
              }
            ]
          : []
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    /** 切换页签：把上一页签的筛选与选中状态清干净，避免"看着是客户、筛的是供应商" */
    handleTypeChange() {
      this.ids = []
      this.names = []
      this.single = true
      this.multiple = true
      this.resetQuery()
    },
    /** 只读展示：停用行走灰色，便于一眼区分（仍可见，符合"停用后管理页仍可查"） */
    rowClassName({ row }) {
      return row.enableFlag === '1' ? '' : 'ctms-row-disabled'
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      const api = this.current.api
      api
        .list(this.queryParams)
        .then((response) => {
          this.list = response.rows || []
          this.total = response.total || 0
          this.loading = false
        })
        .catch((err) => {
          // 没有这一段的话失败后 loading 永远为 true，表格一直转圈且残留旧数据
          this.loading = false
          this.list = []
          this.total = 0
          const d = describeError(err)
          this.loadError = d.text
          this.loadErrorCause = d.cause
        })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    /**
     * 清空表单并重置只读态/校验态。
     *
     * ⚠ 刻意**不**调 `resetForm('form')`：el-form 的 `resetFields()` 会回到"该字段首次挂载时"的初值，
     *   而弹窗内容是在第一次打开时才挂载的 —— 先看过/改过 A 档案、再点「新增」，
     *   会把 A 的简称/账期带进新表单（本页此前真实存在的问题）。这里用显式赋值 + clearValidate。
     */
    reset() {
      this.readonly = false
      this.form = {
        id: undefined,
        code: undefined,
        name: undefined,
        shortName: undefined,
        taxNo: undefined,
        contactName: undefined,
        contactPhone: undefined,
        address: undefined,
        bankName: undefined,
        bankAccount: undefined,
        creditLimit: undefined,
        level: undefined,
        supplyScope: undefined,
        paymentDays: undefined,
        enableFlag: '1',
        remark: undefined
      }
      // 只清校验态，不用 resetForm（理由见 reset() 的注释）
      this.$nextTick(() => {
        if (this.$refs['form']) {
          this.$refs['form'].clearValidate()
        }
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams.enableFlag = undefined
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.id)
      this.names = selection.map((item) => (item.shortName ? item.name + '（' + item.shortName + '）' : item.name))
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = '新增' + this.current.shortLabel + '档案'
    },
    handleUpdate(row) {
      this.reset()
      const id = row ? row.id : this.ids[0]
      if (!id) {
        return
      }
      this.current.api
        .get(id)
        .then((response) => {
          this.form = response.data
          this.open = true
          this.title = '修改' + this.current.shortLabel + '档案'
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /**
     * 查看（只读）：入口用 `ctms:partner:query`（菜单 SQL 里的「档案查询」），
     * 与"能改"的 `ctms:partner:edit` 分开 —— 只有查询权的账号也能看全字段。
     */
    handleView(row) {
      this.reset()
      this.readonly = true
      this.current.api
        .get(row.id)
        .then((response) => {
          this.form = response.data || {}
          this.open = true
          this.title = '查看' + this.current.shortLabel + '档案'
        })
        .catch((err) => {
          // 取详情失败要退回可编辑态，否则下一次「新增」会停在只读
          this.readonly = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    submitForm() {
      this.$refs['form'].validate((valid) => {
        if (!valid) {
          return
        }
        const api = this.current.api
        const isEdit = !!this.form.id
        // code 不可改（档案编码是唯一标识）：编辑时服务端也会忽略对 code 的改动
        const req = isEdit ? api.update(this.form) : api.add(this.form)
        req
          .then(() => {
            this.$modal.msgSuccess(isEdit ? '修改成功' : '新增成功')
            this.open = false
            this.getList()
          })
          .catch((err) => {
            // 服务端的唯一性/必填提示（如"客户编码已存在"）要原样透出，不要换成泛泛的"操作失败"
            const d = describeError(err)
            this.$modal.msgError(d.text)
          })
      })
    },
    /** 启停用：单行或批量；停用后该档案不再出现在 /options（合同表单选择器） */
    handleToggleStatus(row) {
      const rows = row ? [row] : this.list.filter((item) => this.ids.indexOf(item.id) >= 0)
      if (!rows.length) {
        return
      }
      const turnOff = rows.filter((r) => r.enableFlag === '1')
      const targetFlag = turnOff.length ? '0' : '1'
      const word = targetFlag === '1' ? '启用' : '停用'
      const detail = targetFlag === '0'
        ? '停用后该档案不再出现在合同表单的选择器中，但已引用它的历史合同仍会正常展示名称。'
        : '启用后该档案重新出现在合同表单的选择器中。'
      this.$modal
        .confirm('是否确认' + word + this.current.shortLabel + '「' + rows.map((r) => r.name).join('、') + '」？<br/>' + detail)
        .then(() => {
          return Promise.all(rows.map((r) => this.current.api.status(r.id, targetFlag)))
        })
        .then(() => {
          this.$modal.msgSuccess(word + '成功')
          this.getList()
        })
        .catch(() => {})
    },
    handleDelete(row) {
      const ids = row ? [row.id] : this.ids
      const names = row ? [row.name] : this.names
      if (!ids.length) {
        return
      }
      this.$modal
        .confirm('是否确认删除' + this.current.shortLabel + '档案「' + names.join('、') + '」？<br/>仅在没有任何合同引用时才会被放行。')
        .then(() => {
          return Promise.all(ids.map((id) => this.current.api.del(id)))
        })
        .then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
        .catch((err) => {
          if (err && err.msg) {
            this.$modal.msgError(describeError(err).text)
          }
        })
    }
  }
}
</script>

<style scoped>
/*
  只写**存在**的令牌（DEV-ENV §6.11：页面里不要写裸色值，运行时换色靠令牌）。
  ⚠ 之前这里用的是 `var(--oa-color-text-secondary, …)` —— 该令牌在 oa-tokens.scss 里
    并不存在（只有 ink-muted / ink-subtle / ink-disabled），于是每次渲染都落到括号里的
    硬编码灰色回退值上；停用行的背景也同样硬编码了一个浅灰。本页已改成真实令牌：
    ink-subtle（次要文字）+ surface-1（浅底）。
*/
.text-muted {
  color: var(--oa-color-ink-subtle);
}
/* 停用行整行变灰：仍可见（历史合同还要展示名称），但一眼能区分 */
::v-deep .ctms-row-disabled {
  color: var(--oa-color-ink-subtle);
  background-color: var(--oa-color-surface-1);
}
.form-tip {
  margin-left: 8px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
}
.page-tip {
  margin: 0 0 10px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 18px;
  color: var(--oa-color-ink-muted);
  background: var(--oa-color-surface-1);
  border-radius: var(--oa-radius-sm);
}
</style>
