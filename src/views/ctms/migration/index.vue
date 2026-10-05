<template>
  <div class="app-container">
    <!--
      迁移认领页（2.0 B3 任务 8.5）—— 把「历史合同里只有文本、没有档案引用」的甲乙方
      按「档案方向 + 原始文本」聚合成待认领草案，人工认领（新建档案 / 绑定已有）后
      由服务端按原始名称**批量回填**合同的档案引用并逐份写变更历史。

      接口与状态机真源是 `openspec/changes/oa-contract-ledger/notes/migration-notes.md`
      （§1 接口语义、§2 状态机 11 行流转表），三条容易做错的写在这里：
      1. **未认领不阻塞业务**：历史合同照常在合同列表/详情/导出里展示甲乙方**文本**；
         认领只把"文本"升级成"档案引用"，不改写文本、不停用合同（页面顶部常驻提示）。
      2. **不做本地乐观状态更新**：扫描后 `ignored` 不会被复活、`claimed` 可能在出现新的
         未绑定合同时回到 `pending` —— 状态一律以服务端返回为准，前端只重取列表。
      3. **已认领的草案不能再认领或忽略**（服务端拒绝，文案含「已认领」），
         所以这类行不发那两个按钮，只显示"已认领"；已忽略的**可以**重新认领。

      ⚠ 路径里的 `{id:[A-Za-z0-9]+}` 是收窄正则：**非法形态的 id 会返回 404**，
        这与"端点不存在"形状相同（notes §6.1）。本页的 id 全部来自草案列表，
        自检/演练也必须用合法的 32 位 hex 形态去探路。
    -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb8"
      title="未认领/已忽略的草案不会阻塞业务：历史合同照常在合同列表、详情与导出里展示甲乙方文本。认领只是把「文本」升级为「档案引用」（并逐份写变更历史），不改写文本、不停用合同。"
    />

    <!-- ==================== 查询条件 ==================== -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="档案方向" prop="partyType">
        <el-select v-model="queryParams.partyType" placeholder="全部" clearable style="width: 150px">
          <el-option label="客户（甲方）" value="customer" />
          <el-option label="供应商（乙方）" value="supplier" />
        </el-select>
      </el-form-item>
      <el-form-item label="原始名称" prop="rawName">
        <el-input
          v-model="queryParams.rawName"
          placeholder="模糊匹配历史文本"
          clearable
          style="width: 220px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 150px">
          <el-option label="待认领" value="pending" />
          <el-option label="已认领" value="claimed" />
          <el-option label="已忽略" value="ignored" />
          <el-option label="全部状态" value="all" />
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
          icon="el-icon-refresh"
          size="mini"
          :loading="scanLoading"
          @click="handleScan"
          v-hasPermi="['ctms:migration:scan']"
          >扫描历史甲乙方</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>
    <div class="page-tip">
      扫描口径（服务端）：类型映射到的那一侧才算 —— <strong>采购 PUR 看乙方 → 供应商方向</strong>、<strong>销售 SAL 看甲方 → 客户方向</strong>，其余类型跳过（不猜方向）；只收「该方向档案引用为空 + 该方向文本非空」的合同（<strong>含已停用合同</strong>）；按「方向 + 原始文本」聚合（大小写/重音不敏感）。重复扫描幂等：待认领只刷新计数、已忽略不复活、已认领若又出现新的未绑定合同会回到待认领。
    </div>

    <!-- 取数失败必须显式呈现（审计「类 1」拦的就是"没有 catch → loading 永久 true"） -->
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="list">
      <el-table-column label="档案方向" align="center" prop="partyType" width="130">
        <template slot-scope="scope">
          <el-tag :type="scope.row.partyType === 'supplier' ? 'warning' : 'success'" size="mini">{{
            directionLabel(scope.row.partyType)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原始名称（历史文本）" align="left" prop="rawName" min-width="230" show-overflow-tooltip />
      <el-table-column label="关联合同数" align="center" prop="contractCount" width="120">
        <template slot-scope="scope">
          <span v-if="scope.row.contractCount !== null && scope.row.contractCount !== undefined">{{
            scope.row.contractCount
          }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status" width="110">
        <template slot-scope="scope">
          <el-tag :type="statusTagType(scope.row.status)" size="mini">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="候选档案" align="center" width="130">
        <template slot-scope="scope">
          <el-button
            v-if="candidateCount(scope.row)"
            type="text"
            size="mini"
            @click="handleClaim(scope.row, 'bind')"
            v-hasPermi="['ctms:migration:claim']"
            >{{ candidateCount(scope.row) }} 个候选</el-button
          >
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="已匹配档案" align="left" prop="matchedId" min-width="200" show-overflow-tooltip>
        <template slot-scope="scope">{{ matchedLabel(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="170" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <template v-if="isActionable(scope.row)">
            <el-button
              size="mini"
              type="text"
              icon="el-icon-folder-checked"
              @click="handleClaim(scope.row, 'create')"
              v-hasPermi="['ctms:migration:claim']"
              >认领</el-button
            >
            <el-button
              size="mini"
              type="text"
              icon="el-icon-close"
              @click="handleIgnore(scope.row)"
              v-hasPermi="['ctms:migration:ignore']"
              >忽略</el-button
            >
          </template>
          <span v-else class="text-muted">已认领，不可重复认领/忽略</span>
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

    <!-- ==================== 认领 ==================== -->
    <el-dialog :title="claimTitle" :visible.sync="claimOpen" width="760px" append-to-body>
      <el-form ref="claimForm" :model="claimForm" :rules="claimRules" label-width="110px">
        <el-form-item label="草案">
          <span>{{ directionLabel(claimForm.partyType) }}｜{{ claimForm.rawName }}</span>
          <span class="form-tip">认领后将按原始名称批量回填「档案引用为空且文本相同」的历史合同（不改文本、不覆盖已绑定别的档案的合同）</span>
        </el-form-item>
        <el-form-item label="认领方式" prop="mode">
          <el-radio-group v-model="claimForm.mode">
            <el-radio label="create">新建档案后绑定</el-radio>
            <el-radio label="bind" :disabled="!canQueryPartner">绑定已有档案</el-radio>
          </el-radio-group>
          <span v-if="!canQueryPartner" class="form-tip">当前账号无 ctms:partner:query 权限，档案选择器不可用，只能新建档案</span>
        </el-form-item>

        <!-- 绑定已有档案 -->
        <template v-if="claimForm.mode === 'bind'">
          <el-form-item label="选择档案" prop="partyId">
            <el-select
              v-model="claimForm.partyId"
              filterable
              clearable
              placeholder="只列出启用中的档案"
              style="width: 100%"
              :loading="optionLoading"
            >
              <el-option v-for="item in claimOptions" :key="item.id" :label="optionLabel(item)" :value="item.id" />
            </el-select>
            <span class="form-tip">候选 = 档案名与草案原始名称互相包含（忽略大小写/空白）的启用档案，已标「候选」并默认选中第一个；也可以手选其它启用档案</span>
          </el-form-item>
        </template>

        <template v-else>
          <!-- 新建档案后绑定（注释写在分支内部：夹在 v-if 与 v-else 之间的注释会被编译器忽略并告警） -->
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="档案编码" prop="code">
                <el-input v-model="claimForm.code" maxlength="32" placeholder="必填（服务端不自动生成编码）" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="档案名称" prop="name">
                <el-input v-model="claimForm.name" maxlength="128" placeholder="留空＝用草案原始名称" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="简称" prop="shortName">
                <el-input
                  v-model="claimForm.shortName"
                  maxlength="64"
                  :placeholder="isSupplierDraft ? '留空＝用草案原始名称（供应商必填，服务端兜底）' : '选填'"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="联系人" prop="contactName">
                <el-input v-model="claimForm.contactName" maxlength="64" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="联系电话" prop="contactPhone">
                <el-input v-model="claimForm.contactPhone" maxlength="32" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="授信额度" prop="creditLimit">
                <el-input-number v-model="claimForm.creditLimit" :precision="2" :min="0" :controls="false" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="等级" prop="level">
                <el-select v-model="claimForm.level" placeholder="请选择" clearable style="width: 100%">
                  <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
                </el-select>
              </el-form-item>
            </el-col>
            <template v-if="isSupplierDraft">
              <el-col :span="12">
                <el-form-item label="供货范围" prop="supplyScope">
                  <el-input v-model="claimForm.supplyScope" maxlength="255" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="账期天数" prop="paymentDays">
                  <el-input-number v-model="claimForm.paymentDays" :min="0" :step="1" :precision="0" style="width: 100%" />
                  <span class="form-tip">不小于 0 的整数；0 合法（＝现结）</span>
                </el-form-item>
              </el-col>
            </template>
          </el-row>
          <div class="form-tip-block">
            税号/地址/开户行/银行账号等其余字段可以认领完成后到「往来单位」页补齐；名称与（供应商）简称留空时由服务端以草案原始名称兜底。
          </div>
        </template>

        <el-form-item label="认领备注" prop="remark">
          <el-input v-model="claimForm.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitClaim">确 定</el-button>
        <el-button @click="claimOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- ==================== 忽略 ==================== -->
    <el-dialog title="忽略草案" :visible.sync="ignoreOpen" width="560px" append-to-body>
      <el-form ref="ignoreForm" :model="ignoreForm" label-width="90px">
        <el-form-item label="草案">
          <span>{{ ignoreForm.label }}</span>
        </el-form-item>
        <el-form-item label="忽略原因">
          <el-input v-model="ignoreForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="可空；重复忽略会覆盖上一次的原因" />
        </el-form-item>
        <div class="form-tip-block">
          忽略后该草案不会再被扫描复活（已忽略连计数都不刷新），历史合同的文本原样保留、继续正常展示。
          已认领的草案不允许忽略（服务端拒绝，文案「该草案已认领，不能忽略」）。
        </div>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitIgnore">确 定</el-button>
        <el-button @click="ignoreOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { scanPartyDrafts, listPartyDrafts, claimPartyDraft, ignorePartyDraft } from '@/api/ctms/migration'
import { listCustomerOptions, listSupplierOptions } from '@/api/ctms/partner'
import DataLoadError from '@/components/DataLoadError'
import { describeError } from '@/utils/errorMessage'

/** 方向常量（与后端 MigrationRules 的取值一致：customer / supplier） */
const DIRECTION_CUSTOMER = 'customer'
const DIRECTION_SUPPLIER = 'supplier'

/** 草案状态常量（与 MigrationRules.STATUS_* 一致） */
const STATUS_PENDING = 'pending'
const STATUS_CLAIMED = 'claimed'
const STATUS_IGNORED = 'ignored'

/** 认领方式（本页内部的两种形态，不是后端字段） */
const MODE_CREATE = 'create'
const MODE_BIND = 'bind'

/** 档案等级可选值（与「往来单位」页保持同一份口径） */
const LEVELS = ['A', 'B', 'C', 'D']

/**
 * 名称匹配（候选档案的判据）：两边都去空白、忽略大小写，**互相包含**即算候选。
 * 只用于把"最可能对得上的档案"排到前面，不参与任何写入判定。
 */
function isNameCandidate(rawName, archiveName) {
  const left = String(rawName === null || rawName === undefined ? '' : rawName).trim().toLowerCase()
  const right = String(archiveName === null || archiveName === undefined ? '' : archiveName).trim().toLowerCase()
  if (!left || !right) {
    return false
  }
  return left.indexOf(right) >= 0 || right.indexOf(left) >= 0
}

/**
 * 空认领表单（新增态初值）。
 * 做成模块级函数而不是"在 data() 里调 this.emptyClaimForm()"：后者依赖 Vue 的初始化顺序
 * （initMethods 早于 initData）才成立，读代码的人看不出这层依赖。
 */
function emptyClaimFormState() {
  return {
    id: undefined,
    partyType: undefined,
    rawName: undefined,
    mode: MODE_CREATE,
    partyId: undefined,
    code: undefined,
    name: undefined,
    shortName: undefined,
    contactName: undefined,
    contactPhone: undefined,
    creditLimit: undefined,
    level: undefined,
    supplyScope: undefined,
    paymentDays: undefined,
    remark: undefined
  }
}

export default {
  name: 'CtmsMigration',
  components: { DataLoadError },
  data() {
    return {
      // 草案列表
      loading: false,
      loadError: '',
      loadErrorCause: '',
      showSearch: true,
      list: [],
      total: 0,
      // 扫描
      scanLoading: false,
      // 档案选择器（只启用档案；用于「绑定已有」与候选提示）
      customerOptions: [],
      supplierOptions: [],
      optionLoading: false,
      optionError: '',
      levels: LEVELS,
      // 认领
      claimOpen: false,
      submitLoading: false,
      claimForm: emptyClaimFormState(),
      // 忽略
      ignoreOpen: false,
      ignoreForm: { id: undefined, label: '', remark: undefined },
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        partyType: undefined,
        rawName: undefined,
        // 默认只看待认领（这是"待办清单"页）；选「全部状态」会传 all，服务层归一为不过滤
        status: STATUS_PENDING
      }
    }
  },
  computed: {
    /** 档案选择器要 ctms:partner:query（不是 list）；没有该权限只能走"新建档案" */
    canQueryPartner() {
      return this.$auth.hasPermi('ctms:partner:query')
    },
    isSupplierDraft() {
      return this.claimForm.partyType === DIRECTION_SUPPLIER
    },
    claimTitle() {
      return '认领草案（' + this.directionLabel(this.claimForm.partyType) + '）'
    },
    /** 当前草案方向下的**全部**启用档案（下拉数据源） */
    claimOptions() {
      return this.claimForm.partyType === DIRECTION_SUPPLIER ? this.supplierOptions : this.customerOptions
    },
    claimRules() {
      const rules = {
        mode: [{ required: true, message: '请选择认领方式', trigger: 'change' }]
      }
      if (this.claimForm.mode === MODE_BIND) {
        rules.partyId = [{ required: true, message: '请选择要绑定的档案', trigger: 'change' }]
      } else {
        // 服务端口径：新建模式编码必填（不自动生成，避免第二套编码规则）
        rules.code = [{ required: true, message: '新建档案必须提供编码', trigger: 'blur' }]
      }
      return rules
    }
  },
  created() {
    this.getList()
    this.loadArchiveOptions()
  },
  methods: {
    /** 空认领表单（新增态初值）；实现见模块级 emptyClaimFormState() */
    emptyClaimForm() {
      return emptyClaimFormState()
    },
    /* ==================== 展示小工具 ==================== */
    directionLabel(partyType) {
      if (partyType === DIRECTION_SUPPLIER) {
        return '供应商（乙方）'
      }
      return partyType === DIRECTION_CUSTOMER ? '客户（甲方）' : '—'
    },
    statusLabel(status) {
      if (status === STATUS_CLAIMED) {
        return '已认领'
      }
      if (status === STATUS_IGNORED) {
        return '已忽略'
      }
      return status === STATUS_PENDING ? '待认领' : status || '—'
    },
    statusTagType(status) {
      if (status === STATUS_CLAIMED) {
        return 'success'
      }
      return status === STATUS_IGNORED ? 'info' : 'warning'
    },
    /** 只有 pending / ignored 可以认领或忽略（claimed 两个动作服务端都拒，页面就不发按钮） */
    isActionable(row) {
      return row.status === STATUS_PENDING || row.status === STATUS_IGNORED
    },
    optionLabel(item) {
      const base = item.name + (item.code ? '（' + item.code + '）' : '')
      return isNameCandidate(this.claimForm.rawName, item.name) ? base + ' · 候选' : base
    },
    /** 候选档案数：按「方向 + 名称互相包含」在**已加载的启用档案**里数，不发请求 */
    candidateCount(row) {
      const options = row.partyType === DIRECTION_SUPPLIER ? this.supplierOptions : this.customerOptions
      return options.filter((item) => isNameCandidate(row.rawName, item.name)).length
    },
    /** 已匹配档案：用已加载的启用档案把 matchedId 翻成名字；翻不到就显示 ID 片段（不猜） */
    matchedLabel(row) {
      if (!row.matchedId) {
        return '—'
      }
      const options = row.partyType === DIRECTION_SUPPLIER ? this.supplierOptions : this.customerOptions
      const hit = options.find((item) => item.id === row.matchedId)
      if (hit) {
        return hit.name + (hit.code ? '（' + hit.code + '）' : '')
      }
      return '已绑定（ID ' + String(row.matchedId).slice(0, 8) + '…）'
    },
    /* ==================== 取数 ==================== */
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      listPartyDrafts(this.queryParams)
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
    /** 启用档案选项（两个方向都要：草案可能是客户或供应商方向）；失败只降级成提示 */
    loadArchiveOptions() {
      if (!this.canQueryPartner) {
        this.customerOptions = []
        this.supplierOptions = []
        this.optionError = '当前账号无 ctms:partner:query 权限：档案选择器不可用，认领时请用「新建档案」'
        return
      }
      this.optionLoading = true
      this.optionError = ''
      Promise.all([
        listCustomerOptions()
          .then((response) => {
            this.customerOptions = response.data || []
          })
          .catch((err) => {
            this.customerOptions = []
            const d = describeError(err)
            this.optionError = '客户档案选项加载失败：' + d.text
          }),
        listSupplierOptions()
          .then((response) => {
            this.supplierOptions = response.data || []
          })
          .catch((err) => {
            this.supplierOptions = []
            const d = describeError(err)
            this.optionError = '供应商档案选项加载失败：' + d.text
          })
      ])
        .then(() => {
          this.optionLoading = false
        })
        .catch((err) => {
          this.optionLoading = false
          const d = describeError(err)
          this.optionError = '档案选项加载失败：' + d.text
        })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.queryParams.partyType = undefined
      this.queryParams.rawName = undefined
      this.queryParams.status = STATUS_PENDING
      this.handleQuery()
    },
    /* ==================== 扫描 ==================== */
    handleScan() {
      this.$modal
        .confirm('扫描会按历史合同的甲乙方文本新建/刷新待认领草案（只读合同表，不改合同的任何字段）。是否继续？')
        .then(() => {
          this.scanLoading = true
          return scanPartyDrafts()
        })
        .then((response) => {
          this.scanLoading = false
          // count = 本次新建或刷新的条数；数据无变化时为 0（重复扫描幂等的可观察形态）
          const count = response.count === null || response.count === undefined ? 0 : response.count
          this.$modal.msgSuccess(count > 0 ? '扫描完成：本次新建/刷新 ' + count + ' 条草案' : '扫描完成：数据无变化（0 条新建或刷新）')
          this.getList()
        })
        .catch((err) => {
          this.scanLoading = false
          this.reportActionError(err)
        })
    },
    /* ==================== 认领 ==================== */
    /**
     * 打开认领对话框。
     * @param row  草案行
     * @param mode 'create' = 新建档案（默认）｜'bind' = 绑定已有（从「候选档案」列点进来）
     */
    handleClaim(row, mode) {
      const form = this.emptyClaimForm()
      form.id = row.id
      form.partyType = row.partyType
      form.rawName = row.rawName
      form.mode = mode === MODE_BIND && this.canQueryPartner ? MODE_BIND : MODE_CREATE
      // 新建模式下按草案原始名称预填，省一次手敲；服务端对留空也有同样的兜底
      form.name = row.rawName
      form.shortName = row.partyType === DIRECTION_SUPPLIER ? row.rawName : undefined
      // 绑定模式下默认选中第一个候选档案（候选判据只用于排序/预选，不参与写入判定）
      if (form.mode === MODE_BIND) {
        const candidates = this.claimOptionsFor(row.partyType).filter((item) => isNameCandidate(row.rawName, item.name))
        form.partyId = candidates.length ? candidates[0].id : undefined
      }
      this.claimForm = form
      this.claimOpen = true
      this.$nextTick(() => {
        if (this.$refs['claimForm']) {
          this.$refs['claimForm'].clearValidate()
        }
      })
    },
    /** 指定方向下的启用档案（claimOptions 用的是"当前表单"的方向，这里要按行取） */
    claimOptionsFor(partyType) {
      return partyType === DIRECTION_SUPPLIER ? this.supplierOptions : this.customerOptions
    },
    submitClaim() {
      this.$refs['claimForm'].validate((valid) => {
        if (!valid) {
          return
        }
        const payload = { remark: this.claimForm.remark || undefined }
        if (this.claimForm.mode === MODE_BIND) {
          payload.partyId = this.claimForm.partyId
        } else {
          payload.code = this.claimForm.code
          payload.name = this.claimForm.name || undefined
          payload.shortName = this.claimForm.shortName || undefined
          payload.contactName = this.claimForm.contactName || undefined
          payload.contactPhone = this.claimForm.contactPhone || undefined
          payload.creditLimit = this.claimForm.creditLimit
          payload.level = this.claimForm.level || undefined
          payload.supplyScope = this.isSupplierDraft ? this.claimForm.supplyScope || undefined : undefined
          payload.paymentDays = this.isSupplierDraft ? this.claimForm.paymentDays : undefined
        }
        const action = this.claimForm.mode === MODE_BIND ? '绑定已有档案' : '新建档案并绑定'
        this.$modal
          .confirm('是否确认' + action + '？<br/>将按原始名称批量回填「引用为空且文本相同」的历史合同，并逐份写一条变更历史。')
          .then(() => {
            this.submitLoading = true
            return claimPartyDraft(this.claimForm.id, payload)
          })
          .then((response) => {
            this.submitLoading = false
            // 直接用认领返回值：contractCount = 本次**实绑**数、matchedId = 绑定的档案ID
            const draft = response.data || {}
            const bound = draft.contractCount === null || draft.contractCount === undefined ? 0 : draft.contractCount
            this.$modal.msgSuccess(
              '认领成功：本次实绑 ' + bound + ' 份合同' + (draft.matchedId ? '（档案ID ' + String(draft.matchedId).slice(0, 8) + '…）' : '')
            )
            this.claimOpen = false
            this.getList()
          })
          .catch((err) => {
            this.submitLoading = false
            this.reportActionError(err)
          })
      })
    },
    /* ==================== 忽略 ==================== */
    handleIgnore(row) {
      this.ignoreForm = {
        id: row.id,
        label: this.directionLabel(row.partyType) + '｜' + row.rawName + '（关联 ' + (row.contractCount || 0) + ' 份合同）',
        remark: undefined
      }
      this.ignoreOpen = true
    },
    submitIgnore() {
      this.submitLoading = true
      ignorePartyDraft(this.ignoreForm.id, this.ignoreForm.remark)
        .then((response) => {
          this.submitLoading = false
          const draft = response.data || {}
          this.$modal.msgSuccess('已忽略（状态：' + this.statusLabel(draft.status) + '）')
          this.ignoreOpen = false
          this.getList()
        })
        .catch((err) => {
          this.submitLoading = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /**
     * 动作类请求的收尾：`$confirm` 的「取消」reject 与真正的接口失败都落在这里。
     * 不能写成空 catch（静默吞掉失败），也不能把「取消」当成失败弹错。
     */
    reportActionError(err) {
      if (!err) {
        return
      }
      if (typeof err === 'string' && (err === 'cancel' || err === 'close')) {
        return
      }
      this.$modal.msgError(describeError(err).text)
    }
  }
}
</script>

<style scoped>
/* 只写**已声明**的令牌、不加硬编码回退值（audit-theme-tokens 会把孤儿令牌判红） */
.text-muted {
  color: var(--oa-color-ink-subtle);
}
.form-tip {
  margin-left: 8px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
}
.form-tip-block {
  margin: 0 0 12px 110px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
  line-height: 18px;
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
