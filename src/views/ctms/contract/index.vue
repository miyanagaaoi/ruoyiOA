<template>
  <div class="app-container">
    <!--
      合同台账列表（2.0 B3 任务 8.1）。

      三条边界写在这里，避免下一位改代码时踩回去：
      1. 筛选、标签交集、数据范围**全部由服务端完成**（/ctms/contract/list），前端只渲染。
         尤其标签：选 N 个就是「同时具备全部 N 个」（服务端 HAVING COUNT = N），
         前端**不做**任何「或」的兜底展开（选了 a、b 却把只有 a 的合同也列出来就是缺陷）。
      2. 平铺视图走服务端分页；框架树视图按设计口径**不分页**：一次取全量（上限 TREE_FETCH_LIMIT），
         顶层行 = 框架合同 + 未挂框架的合同，子行 = 框架下的子合同。子合同清单来自
         /ctms/contract/framework/{id}（子行可能不在当前结果集里），结果集里 parentId 命中的行
         用于补齐与去重；渲染用 el-table 的 row-key + tree-props 两级结构。
      3. 停用是**软删除**：默认不返回，只有「含停用」开关打开（includeDeleted = 1）才返回；
         停用原因必填，停用时间起算 30 天内可恢复。
    -->

    <!-- ==================== 查询条件 ==================== -->
    <el-form
      :model="queryParams"
      ref="queryForm"
      size="small"
      :inline="true"
      v-show="showSearch"
      label-width="80px"
    >
      <el-form-item label="关键字" prop="keyword">
        <el-input
          v-model="queryParams.keyword"
          placeholder="编号/名称/甲方/乙方/行项名称规格"
          clearable
          style="width: 230px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-select v-model="queryParams.type" placeholder="全部" clearable style="width: 140px">
          <el-option
            v-for="dict in dict.type.contract_types"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="进度状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 140px">
          <el-option
            v-for="dict in dict.type.contract_statuses"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="到货状态" prop="arrivalStatus">
        <el-select v-model="queryParams.arrivalStatus" placeholder="全部" clearable style="width: 140px">
          <el-option
            v-for="dict in dict.type.arrival_statuses"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="是否框架" prop="isFramework">
        <el-select v-model="queryParams.isFramework" placeholder="全部" clearable style="width: 140px">
          <el-option label="框架合同" value="1" />
          <el-option label="非框架合同" value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="经办人" prop="ownerName">
        <el-input
          v-model="queryParams.ownerName"
          placeholder="请输入经办人"
          clearable
          style="width: 140px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="签订日期" prop="signDateRange">
        <el-date-picker
          v-model="signDateRange"
          type="daterange"
          value-format="yyyy-MM-dd"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
          style="width: 240px"
        />
      </el-form-item>
      <el-form-item label="标签" prop="tagIds">
        <el-select
          v-model="queryParams.tagIds"
          multiple
          collapse-tags
          clearable
          placeholder="多选＝取交集"
          style="width: 220px"
          :loading="tagLoading"
        >
          <el-option v-for="tag in tagOptions" :key="tag.id" :label="tag.name" :value="tag.id" />
        </el-select>
        <span v-if="tagLoadError" class="text-danger">{{ tagLoadError }}</span>
      </el-form-item>
      <el-form-item label="含停用" prop="includeDeleted">
        <el-switch v-model="queryParams.includeDeleted" active-value="1" inactive-value="0" />
        <span class="form-tip">打开＝包含已停用合同（默认不含）</span>
      </el-form-item>
      <el-form-item label="视图" prop="viewMode">
        <el-radio-group v-model="viewMode" size="mini" @change="handleViewModeChange">
          <el-radio-button label="flat">平铺</el-radio-button>
          <el-radio-button label="tree">框架树</el-radio-button>
        </el-radio-group>
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
          v-hasPermi="['ctms:contract:add']"
          >新增合同</el-button
        >
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          :loading="exportLoading"
          @click="handleExport"
          v-hasPermi="['ctms:contract:export']"
          >导出</el-button
        >
      </el-col>
      <el-col :span="1.5" v-if="viewMode === 'tree'">
        <el-button type="info" plain icon="el-icon-sort" size="mini" @click="toggleExpandAll">展开/折叠</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-alert
      v-if="viewMode === 'tree'"
      type="info"
      :closable="false"
      show-icon
      class="mb8"
      title="框架树视图：顶层为框架合同与未挂框架的合同，子行为该框架下的子合同；该视图一次取全量（不分页）。平铺视图仍按服务端分页。"
    />
    <el-alert
      v-if="frameworkErrors.length"
      type="warning"
      :closable="false"
      show-icon
      class="mb8"
      :title="frameworkErrorText"
    />

    <!--
      ⚠ 取数失败必须显式呈现（tools/audit 的「类 1」拦的就是"没有 catch → loading 永久 true"）：
        这里沿用全项目的 DataLoadError 范式。
      ⚠ refreshTable 是 el-table 的强制重挂载开关：default-expand-all 只在渲染时生效，
        所以「展开/折叠」要先摘掉表格再挂回来（与 system/dept/index.vue 同款）。
    -->
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table
      v-else-if="refreshTable"
      v-loading="loading"
      :key="viewMode"
      :data="list"
      row-key="id"
      :default-expand-all="isExpandAll"
      :tree-props="{ children: 'children' }"
    >
      <el-table-column label="合同编号" align="center" prop="contractNo" width="165" show-overflow-tooltip />
      <el-table-column label="合同名称" align="left" prop="name" min-width="220" show-overflow-tooltip>
        <template slot-scope="scope">
          <span>{{ scope.row.name }}</span>
          <el-tag v-if="scope.row.isFramework === '1'" size="mini" type="warning" class="row-tag">框架</el-tag>
          <el-tag v-else-if="scope.row.parentId" size="mini" type="info" class="row-tag">子合同</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" align="center" prop="type" width="110">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.contract_types" :value="scope.row.type" />
        </template>
      </el-table-column>
      <el-table-column label="我方主体" align="center" prop="subjectCode" width="100">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.subjects" :value="scope.row.subjectCode" />
        </template>
      </el-table-column>
      <el-table-column label="甲方" align="left" prop="partyA" min-width="150" show-overflow-tooltip>
        <template slot-scope="scope">
          <span v-if="scope.row.partyA">{{ scope.row.partyA }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="乙方" align="left" prop="partyB" min-width="150" show-overflow-tooltip>
        <template slot-scope="scope">
          <span v-if="scope.row.partyB">{{ scope.row.partyB }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="合同金额" align="right" prop="amount" width="130">
        <template slot-scope="scope">
          <span v-if="scope.row.amount !== null && scope.row.amount !== undefined">{{
            moneyText(scope.row.amount)
          }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="进度状态" align="center" prop="status" width="110">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.contract_statuses" :value="scope.row.status" />
        </template>
      </el-table-column>
      <el-table-column label="到货状态" align="center" prop="arrivalStatus" width="100">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.arrival_statuses" :value="scope.row.arrivalStatus" />
        </template>
      </el-table-column>
      <el-table-column label="签订日期" align="center" prop="signDate" width="110">
        <template slot-scope="scope">
          <span v-if="scope.row.signDate">{{ parseTime(scope.row.signDate, '{y}-{m}-{d}') }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="经办人" align="center" prop="ownerName" width="100">
        <template slot-scope="scope">
          <span v-if="scope.row.ownerName">{{ scope.row.ownerName }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="includeDeletedOn" label="停用标记" align="center" prop="delFlag" width="100">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.delFlag === '1'" type="info" size="mini">已停用</el-tag>
          <el-tag v-else type="success" size="mini">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="320" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-view"
            @click="handleView(scope.row)"
            v-hasPermi="['ctms:contract:query']"
            >详情</el-button
          >
          <el-button
            v-if="scope.row.delFlag !== '1'"
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleEdit(scope.row)"
            v-hasPermi="['ctms:contract:edit']"
            >修改</el-button
          >
          <el-button
            v-if="scope.row.delFlag === '1'"
            size="mini"
            type="text"
            icon="el-icon-refresh-left"
            @click="handleRestore(scope.row)"
            v-hasPermi="['ctms:contract:remove']"
            >恢复</el-button
          >
          <el-button
            v-else
            size="mini"
            type="text"
            icon="el-icon-s-operation"
            @click="handleStatus(scope.row)"
            v-hasPermi="['ctms:contract:status']"
            >状态变更</el-button
          >
          <el-button
            v-if="scope.row.delFlag !== '1'"
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['ctms:contract:remove']"
            >停用</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <!-- 平铺视图分页：平台分页组件（树视图不分页，见页头口径 2） -->
    <pagination
      v-show="viewMode === 'flat' && total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!--
      详情 / 编辑 / 新增都在同目录的 <contract-detail> 组件里（el-dialog）：
      菜单里只有 `ctms/contract/index` 一个合同菜单，详情没有独立路由，所以不做路由跳转。
      该组件同时承担只读详情（含标签、子合同汇总、附件、变更历史、只读关联单据）。
    -->
    <contract-detail ref="contractForm" @refresh="getList" />

    <!-- ==================== 状态变更 ==================== -->
    <el-dialog title="状态变更" :visible.sync="statusOpen" width="520px" append-to-body>
      <el-form ref="statusForm" :model="statusForm" :rules="statusRules" label-width="90px">
        <el-form-item label="合同">
          <span>{{ statusForm.name }}</span>
        </el-form-item>
        <el-form-item label="进度状态" prop="status">
          <el-select v-model="statusForm.status" placeholder="请选择" style="width: 100%">
            <el-option
              v-for="dict in dict.type.contract_statuses"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="到货状态" prop="arrivalStatus">
          <el-select v-model="statusForm.arrivalStatus" placeholder="请选择" style="width: 100%">
            <el-option
              v-for="dict in dict.type.arrival_statuses"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needTerminateReason" label="终止原因" prop="deletedReason">
          <el-input
            v-model="statusForm.deletedReason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="改为「已终止」时必填（写入变更历史）"
          />
        </el-form-item>
        <el-form-item>
          <span class="form-tip">两个状态互相独立：可任意互转、不做顺序守卫，改一个不会自动改写另一个。</span>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitStatus">确 定</el-button>
        <el-button @click="statusOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- ==================== 停用（软删除） ==================== -->
    <el-dialog title="停用合同" :visible.sync="deleteOpen" width="520px" append-to-body>
      <el-form ref="deleteForm" :model="deleteForm" :rules="deleteRules" label-width="90px">
        <el-form-item label="合同">
          <span>{{ deleteForm.name }}</span>
        </el-form-item>
        <el-form-item label="停用原因" prop="reason">
          <el-input
            v-model="deleteForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="必填；会写入字段级变更历史"
          />
        </el-form-item>
        <el-form-item>
          <span class="form-tip">停用是软删除：合同仍占号、可查（打开「含停用」即可看到），30 天内可恢复。</span>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitDelete">确 定</el-button>
        <el-button @click="deleteOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listContract, exportContract, getFrameworkInfo, listTagOptions, changeContractStatus, delContract, restoreContract } from '@/api/ctms/contract'
import DataLoadError from '@/components/DataLoadError'
import ContractDetail from './detail'
import { describeError } from '@/utils/errorMessage'
import { formatAmount } from '@/utils/money'

/**
 * 树视图一次取全量的上限。
 * 为什么不"不传分页参数"：后端 `BaseController.startPage()` 在缺参时默认 pageNum=1/pageSize=10
 * （`TableSupport` 里 Convert.toInt 的默认值），不传参只会拿到 10 条 —— 看起来像"树只出一屏"。
 */
const TREE_FETCH_LIMIT = 500

/**
 * 导出文件名里的时间戳（本地时间，到分钟），形如 `20261005-1530`。
 * 只用来拼文件名 —— 导出已改为**由服务端导全量**的 xlsx（后端 `ExcelUtil` 直接写二进制响应流，
 * 响应体里没有 `rows`），前端不再取数、也不再自拼表头：列名与列顺序的真源是后端
 * `CtmsContractController.ContractExportRow` 的 `@Excel` 注解。旧的客户端取数上限已随
 * "前端拼 CSV"那一版实现一起删除，见 `handleExport`。
 */
function stamp(date) {
  const pad = (n) => (n < 10 ? '0' + n : String(n))
  return (
    String(date.getFullYear()) + pad(date.getMonth() + 1) + pad(date.getDate()) + '-' + pad(date.getHours()) + pad(date.getMinutes())
  )
}

export default {
  name: 'CtmsContract',
  components: { DataLoadError, ContractDetail },
  dicts: ['contract_types', 'contract_statuses', 'arrival_statuses', 'subjects'],
  data() {
    return {
      // 主数据加载态与错误态（三者必须成对，见 DataLoadError 的用法说明）
      loading: false,
      loadError: '',
      loadErrorCause: '',
      showSearch: true,
      list: [],
      total: 0,
      // 视图：flat 平铺（服务端分页）/ tree 框架树（一次取全量，两级展开）
      viewMode: 'flat',
      isExpandAll: true,
      refreshTable: true,
      // 签订日期区间（两个字段在后端是 beginSignDate / endSignDate，界面上是一个区间控件）
      signDateRange: [],
      // 标签选项（只读，供交集筛选）；失败只降级成提示，不顶掉整页
      tagOptions: [],
      tagLoading: false,
      tagLoadError: '',
      // 框架行 → 子合同（树视图用）
      frameworkChildren: {},
      frameworkErrors: [],
      // 新增 / 编辑 / 详情统一交给子组件（任务 8.2 的 detail.vue），这里只持有它的 ref
      // 状态变更
      statusOpen: false,
      statusForm: { id: undefined, name: '', status: undefined, arrivalStatus: undefined, deletedReason: undefined },
      // 停用（软删除）
      deleteOpen: false,
      deleteForm: { id: undefined, name: '', reason: '' },
      // 两个弹窗共用的提交态 / 导出的取数态
      submitLoading: false,
      exportLoading: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        keyword: undefined,
        type: undefined,
        status: undefined,
        arrivalStatus: undefined,
        isFramework: undefined,
        ownerName: undefined,
        // 标签集合：**交集**语义，原样提交数组，前端不做任何「或」兜底
        tagIds: [],
        // 后端口径：仅 "1" 包含已停用，其它值（含 null）都只返回未停用
        includeDeleted: '0'
      }
    }
  },
  computed: {
    /** 标签选项读取要 ctms:tag:list；没有该权限就不发请求（否则只会白弹一个 403 提示） */
    canReadTags() {
      return this.$auth.hasPermi('ctms:tag:list')
    },
    /** 框架详情走 ctms:contract:query；没有该权限时树视图退化成"只用当前结果集归并" */
    canQueryContract() {
      return this.$auth.hasPermi('ctms:contract:query')
    },
    includeDeletedOn() {
      return this.queryParams.includeDeleted === '1'
    },
    frameworkErrorText() {
      return this.frameworkErrors.join('；')
    },
    needTerminateReason() {
      return this.statusForm.status === '已终止'
    },
    deleteRules() {
      return {
        reason: [{ required: true, message: '停用原因不能为空', trigger: 'blur' }]
      }
    },
    statusRules() {
      const rules = {
        status: [{ required: true, message: '进度状态不能为空', trigger: 'change' }],
        arrivalStatus: [{ required: true, message: '到货状态不能为空', trigger: 'change' }]
      }
      // 服务端口径：改为「已终止」时终止原因必填（写进 status 的变更历史新值）
      if (this.needTerminateReason) {
        rules.deletedReason = [{ required: true, message: '已终止必须填写终止原因', trigger: 'blur' }]
      }
      return rules
    }
  },
  created() {
    this.getList()
    this.loadTagOptions()
  },
  methods: {
    /** 金额显示：全项目唯一实现在 @/utils/money.js，这里只是模板可用的入口 */
    moneyText(value) {
      return formatAmount(value)
    },
    /**
     * 组装查询参数：字段名与后端 `CtmsContract` 的查询字段**逐字一致**
     * （keyword / type / status / arrivalStatus / isFramework / ownerName /
     *  beginSignDate / endSignDate / tagIds / includeDeleted）。
     */
    buildQuery() {
      const range = this.signDateRange || []
      const params = {
        pageNum: this.queryParams.pageNum,
        pageSize: this.queryParams.pageSize,
        keyword: this.queryParams.keyword,
        type: this.queryParams.type,
        status: this.queryParams.status,
        arrivalStatus: this.queryParams.arrivalStatus,
        isFramework: this.queryParams.isFramework,
        ownerName: this.queryParams.ownerName,
        beginSignDate: range.length === 2 ? range[0] : undefined,
        endSignDate: range.length === 2 ? range[1] : undefined,
        tagIds: this.queryParams.tagIds && this.queryParams.tagIds.length ? this.queryParams.tagIds.slice() : undefined,
        includeDeleted: this.queryParams.includeDeleted
      }
      if (this.viewMode === 'tree') {
        // 树视图按设计口径不分页：不分页用"上限兜底"实现（缺参会被后端默认成 10 条）
        params.pageNum = 1
        params.pageSize = TREE_FETCH_LIMIT
      }
      return params
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      this.frameworkChildren = {}
      this.frameworkErrors = []
      listContract(this.buildQuery())
        .then((response) => {
          this.total = response.total || 0
          const rows = response.rows || []
          return this.viewMode === 'tree' ? this.loadFrameworkChildren(rows) : rows
        })
        .then((rows) => {
          this.list = this.viewMode === 'tree' ? this.buildTreeRows(rows) : rows
          // 树视图的"总数"按顶层行数算（框架数 + 未挂框架的合同数），与参考侧口径一致
          this.total = this.viewMode === 'tree' ? this.list.length : this.total
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
    /**
     * 为每个框架行补齐子合同清单（树视图的数据来源）。
     * ⚠ 不能用"本页 parentId 归并"代替：平铺分页时子行可能根本不在当前结果集里，
     *   只有 /ctms/contract/framework/{id} 才返回**完整**子清单。
     */
    loadFrameworkChildren(rows) {
      const frameworkIds = rows.filter((row) => row.isFramework === '1').map((row) => row.id)
      if (!this.canQueryContract || !frameworkIds.length) {
        return Promise.resolve(rows)
      }
      return Promise.all(frameworkIds.map((id) => this.fetchFrameworkChildren(id)))
        .then((groups) => {
          const map = {}
          groups.forEach((group) => {
            map[group.id] = group.children
          })
          this.frameworkChildren = map
          return rows
        })
        .catch((err) => {
          // 单个框架详情失败已在 fetchFrameworkChildren 里降级；这里只兜底"降级本身抛错"的极端情况：
          // 退回"本页 parentId 归并"的树，并把原因显示出来（不静默吞掉，也不打死整页）
          const d = describeError(err)
          this.frameworkErrors.push('框架子合同清单读取失败：' + d.text)
          return rows
        })
    },
    fetchFrameworkChildren(id) {
      return getFrameworkInfo(id)
        .then((response) => {
          const data = response.data || {}
          return { id: id, children: data.children || [] }
        })
        .catch((err) => {
          // 单个框架详情失败不把整页打成错误态：降级成"该行暂无子行"，并把原因显示出来（不静默）
          const d = describeError(err)
          this.frameworkErrors.push('框架 ' + id + ' 的子合同清单读取失败：' + d.text)
          return { id: id, children: [] }
        })
    },
    /**
     * 组装两级树：
     *   顶层 = 未挂框架的行 ∪ 父框架不在结果集里的行（后者保留为顶层，避免"父不在本页就把子丢了"）
     *   框架行的 children = 框架详情返回的完整子清单（优先取结果集里的行对象）∪ 结果集里 parentId 命中的行
     * ⚠ 只在有子行时才写 children：空数组会让 el-table 画出一个展开后什么都没有的箭头。
     * ⚠ 这里在 `this.list = ...` 之前对**普通对象**赋 children，所以 Vue 仍能在赋值时递归做响应式；
     *   若改成先赋值再补 children，必须用 this.$set。
     */
    buildTreeRows(rows) {
      const byId = {}
      rows.forEach((row) => {
        byId[row.id] = row
      })
      const top = rows.filter((row) => !row.parentId || !byId[row.parentId])
      top.forEach((row) => {
        if (row.isFramework !== '1') {
          return
        }
        const seen = {}
        const children = []
        const fromDetail = this.frameworkChildren[row.id] || []
        fromDetail.forEach((child) => {
          if (!seen[child.id]) {
            seen[child.id] = true
            children.push(byId[child.id] || child)
          }
        })
        rows.forEach((child) => {
          if (child.parentId === row.id && !seen[child.id]) {
            seen[child.id] = true
            children.push(child)
          }
        })
        row.children = children.length ? children : undefined
      })
      return top
    },
    /** 标签选项（次要加载）：失败只降级成提示，不影响合同列表本身 */
    loadTagOptions() {
      if (!this.canReadTags) {
        this.tagOptions = []
        this.tagLoadError = '当前账号无标签读取权限（ctms:tag:list），标签筛选不可用'
        return
      }
      this.tagLoading = true
      this.tagLoadError = ''
      listTagOptions()
        .then((response) => {
          this.tagOptions = response.rows || []
          this.tagLoading = false
        })
        .catch((err) => {
          this.tagLoading = false
          this.tagOptions = []
          const d = describeError(err)
          this.tagLoadError = '标签选项加载失败：' + d.text
        })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      // resetForm 只能恢复"挂载时的初值"，下面这几项要显式复位（含数组与开关的字符串值）
      this.queryParams.keyword = undefined
      this.queryParams.type = undefined
      this.queryParams.status = undefined
      this.queryParams.arrivalStatus = undefined
      this.queryParams.isFramework = undefined
      this.queryParams.ownerName = undefined
      this.queryParams.tagIds = []
      this.queryParams.includeDeleted = '0'
      this.signDateRange = []
      this.handleQuery()
    },
    /** 切换视图：清掉选中态与旧的框架缓存，重新取数 */
    handleViewModeChange() {
      this.queryParams.pageNum = 1
      this.list = []
      this.total = 0
      this.isExpandAll = true
      this.refreshTable = false
      this.$nextTick(() => {
        this.refreshTable = true
      })
      this.getList()
    },
    /**
     * 展开/折叠：default-expand-all 只在表格渲染时生效，所以先摘掉表格再挂回来
     * （与 system/dept/index.vue 同款；`git grep refreshTable` 可对照）。
     */
    toggleExpandAll() {
      this.refreshTable = false
      this.isExpandAll = !this.isExpandAll
      this.$nextTick(() => {
        this.refreshTable = true
      })
    },
    /**
     * 新增 / 编辑 / 详情都交给子组件（`./detail.vue`，任务 8.2）：
     * 它自己取数、自己校验、保存成功后 `@refresh` 回来重载列表。
     */
    handleAdd() {
      this.$refs['contractForm'].openAdd()
    },
    handleEdit(row) {
      this.$refs['contractForm'].openEdit(row.id)
    },
    handleView(row) {
      this.$refs['contractForm'].openView(row.id)
    },
    handleStatus(row) {
      this.statusForm = {
        id: row.id,
        name: row.name,
        status: row.status,
        arrivalStatus: row.arrivalStatus,
        deletedReason: undefined
      }
      this.statusOpen = true
      // ⚠ 弹窗复用同一个表单实例：这里**不调 resetForm** —— el-form 的 resetFields() 会回到
      //   "首次挂载时"的初值（上一次打开那行合同的状态/原因），看起来像"点开第二行却显示第一行的值"。
      //   只清校验态（打开第二次时不要遗传上一次的红字）。
      this.$nextTick(() => {
        if (this.$refs['statusForm']) {
          this.$refs['statusForm'].clearValidate()
        }
      })
    },
    submitStatus() {
      this.$refs['statusForm'].validate((valid) => {
        if (!valid) {
          return
        }
        this.submitLoading = true
        changeContractStatus({
          id: this.statusForm.id,
          status: this.statusForm.status,
          arrivalStatus: this.statusForm.arrivalStatus,
          // 终止原因复用 deletedReason 字段承载（与软删除无关，服务端把它写进 status 的历史新值）
          deletedReason: this.needTerminateReason ? this.statusForm.deletedReason : undefined
        })
          .then(() => {
            this.submitLoading = false
            this.statusOpen = false
            this.$modal.msgSuccess('状态变更成功')
            this.getList()
          })
          .catch((err) => {
            this.submitLoading = false
            const d = describeError(err)
            this.$modal.msgError(d.text)
          })
      })
    },
    handleDelete(row) {
      this.deleteForm = { id: row.id, name: row.name, reason: '' }
      this.deleteOpen = true
      // 同 handleStatus：只清校验态，不用 resetForm（否则会带出上一次的停用原因）
      this.$nextTick(() => {
        if (this.$refs['deleteForm']) {
          this.$refs['deleteForm'].clearValidate()
        }
      })
    },
    submitDelete() {
      this.$refs['deleteForm'].validate((valid) => {
        if (!valid) {
          return
        }
        this.submitLoading = true
        delContract(this.deleteForm.id, this.deleteForm.reason)
          .then(() => {
            this.submitLoading = false
            this.deleteOpen = false
            this.$modal.msgSuccess('已停用')
            this.getList()
          })
          .catch((err) => {
            this.submitLoading = false
            const d = describeError(err)
            this.$modal.msgError(d.text)
          })
      })
    },
    handleRestore(row) {
      this.$modal
        .confirm('是否确认恢复合同「' + row.name + '」？<br/>停用时间起算 30 天内的合同才可恢复。')
        .then(() => {
          return restoreContract(row.id)
        })
        .then(() => {
          this.$modal.msgSuccess('恢复成功')
          this.getList()
        })
        .catch((err) => {
          this.reportActionError(err)
        })
    },
    /**
     * 导出：**由服务端导全量**。
     *
     * 后端 `/ctms/contract/export` 由 `ExcelUtil` 直接写 xlsx 二进制响应流（响应体里**没有** `rows`），
     * 列名与列顺序的真源是后端 `CtmsContractController.ContractExportRow` 的 `@Excel` 注解 ——
     * 前端只负责触发下载，不自造表头；筛选口径与数据范围仍与列表共用同一处（服务端强制）。
     * 所以这里走 `exportContract()` → `utils/request.js` 的 `download()`：POST + `responseType: 'blob'`
     * + `blobValidate` + `saveAs`，失败体（JSON）会被解析成**可见的错误提示**，
     * 不会"无声地下载一个坏文件"。
     *
     * ⚠ 不要再退回"前端取分页数据、自己拼表格"的写法：响应体里没有 `rows`，
     * 那样写会把每次导出都判成"没有数据"并提示一句警告，导出功能等于不可用。
     */
    handleExport() {
      this.exportLoading = true
      exportContract(this.buildQuery(), '合同台账_' + stamp(new Date()) + '.xlsx')
        .then(() => {
          this.exportLoading = false
        })
        .catch((err) => {
          this.exportLoading = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    /**
     * 动作类请求的收尾：`$confirm` 的「取消」reject 与真正的接口失败都落在这里。
     * 不能写成空 catch（tools/audit 的「静默 catch」会清点它，且用户将看不到失败原因），
     * 也不能把「取消」当成失败弹错：判据是 MessageBox 的 cancel / close。
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
/* 只写令牌，不写裸色值（DEV-ENV §6.9/§6.11：运行时换色靠令牌，硬编码会串色） */
.text-muted {
  color: var(--oa-color-ink-subtle);
}
.text-danger {
  margin-left: 6px;
  color: var(--oa-color-error);
  font-size: 12px;
}
.form-tip {
  margin-left: 8px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
}
.row-tag {
  margin-left: 6px;
}
</style>
