<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" label-width="80px">
      <el-form-item label="流程名称" prop="name">
        <el-input v-model="queryParams.name" placeholder="请输入流程名称" clearable size="small" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部" clearable size="small" style="width:110px">
          <el-option label="草稿" value="0" />
          <el-option label="已发布" value="1" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新建流程</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>

    <el-table v-if="listState === 'ready'" v-loading="loading" :data="list">
      <el-table-column label="流程名称" prop="name" min-width="160" show-overflow-tooltip />
      <el-table-column label="流程标识" prop="defKey" width="180" show-overflow-tooltip />
      <el-table-column label="分类" prop="category" width="100" />
      <el-table-column label="状态" width="100" align="center">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === '1' ? 'success' : 'info'" size="mini">
            {{ scope.row.status === '1' ? '已发布' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="70" align="center">
        <template slot-scope="scope">v{{ scope.row.version || 0 }}</template>
      </el-table-column>
      <el-table-column label="更新时间" prop="updateTime" width="160" />
      <el-table-column label="操作" width="230" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleDesign(scope.row)">设计</el-button>
          <el-button size="mini" type="text" icon="el-icon-upload2" @click="handlePublish(scope.row)">发布</el-button>
          <el-button size="mini" type="text" icon="el-icon-time" @click="handleHistory(scope.row)">历史</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="listState === 'ready' && total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!--
      列表的四种状态。这里带 `v-if="listState !== 'ready'"` 是刻意的：
      表格已在上面渲染，此处只负责加载/空/错误三态，避免正常态下多套一层容器。
      重点是把「请求失败」和「确实没有数据」区分开 —— 失败时绝不能只给一张空表。
    -->
    <StateBlock
      v-if="listState !== 'ready'"
      :state="listState"
      loading-text="正在加载流程列表…"
      error-title="流程列表加载失败"
      :error-text="listError"
      :error-cause="listErrorCause"
      empty-title="还没有流程"
      empty-desc="新建一个流程，在画布上配置审批链路后发布即可生效。"
      @retry="getList"
    >
      <template slot="empty-action">
        <el-button type="primary" size="small" icon="el-icon-plus" @click="handleAdd">新建流程</el-button>
      </template>
    </StateBlock>

    <!-- 新建流程 -->
    <el-dialog title="新建流程" :visible.sync="addVisible" width="520px" append-to-body>
      <el-form ref="addForm" :model="addForm" :rules="addRules" label-width="96px" size="small">
        <el-form-item label="流程名称" prop="name">
          <el-input v-model="addForm.name" placeholder="如：合同审批（含用印）" />
        </el-form-item>
        <el-form-item label="流程标识" prop="defKey">
          <el-input v-model="addForm.defKey" placeholder="英文标识，如 contractApproval" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-input v-model="addForm.category" placeholder="如：contract" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false">
        <div slot="title">
          新建后进入设计器；流程标识会作为 Flowable 的流程定义 key，<b>发布后不可修改</b>。
        </div>
      </el-alert>
      <div slot="footer" class="dialog-footer">
        <el-button @click="addVisible = false">取 消</el-button>
        <el-button type="primary" :loading="creating" @click="submitAdd">创建并设计</el-button>
      </div>
    </el-dialog>

    <!-- 版本历史 -->
    <el-drawer title="版本历史" :visible.sync="historyVisible" size="45%" append-to-body>
      <el-table :data="historyList" size="mini">
        <el-table-column label="版本" width="70">
          <template slot-scope="s">v{{ s.row.version }}</template>
        </el-table-column>
        <el-table-column label="发布人" prop="publisherName" width="100" />
        <el-table-column label="发布时间" prop="publishTime" width="165" />
        <el-table-column label="流程定义" prop="procDefId" show-overflow-tooltip />
      </el-table>
    </el-drawer>
  </div>
</template>

<script>
import {
  listSimpleFlow,
  saveSimpleFlowDraft,
  publishSimpleFlow,
  historySimpleFlow,
  delSimpleFlow
} from '@/api/workflow/simpleFlow'
import StateBlock from '@/components/StateBlock'
import { describeError } from '@/utils/errorMessage'

export default {
  name: 'SimpleFlowList',
  components: { StateBlock },
  data() {
    return {
      loading: false,
      showSearch: true,
      list: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, name: null, status: null },
      addVisible: false,
      creating: false,
      addForm: { name: '', defKey: '', category: '' },
      addRules: {
        name: [{ required: true, message: '流程名称不能为空', trigger: 'blur' }],
        defKey: [
          { required: true, message: '流程标识不能为空', trigger: 'blur' },
          { pattern: /^[A-Za-z_][A-Za-z0-9_-]*$/, message: '仅允许字母/数字/下划线/中划线，且不以数字开头', trigger: 'blur' }
        ]
      },
      historyVisible: false,
      historyList: [],
      /** 列表加载失败的信息（null 表示没失败）—— 用于把"失败"和"没数据"分开 */
      listError: null,
      listErrorCause: ''
    }
  },
  computed: {
    /** 列表的四态之一：loading / error / empty / ready */
    listState() {
      if (this.loading) return 'loading'
      if (this.listError) return 'error'
      return this.list.length ? 'ready' : 'empty'
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      this.listError = null
      this.listErrorCause = ''
      listSimpleFlow(this.queryParams).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(err => {
        // 关键：失败时**清空列表**，否则会残留上一次的数据 +
        // 一个错误提示同时出现，用户没法判断看到的到底是不是最新结果
        this.list = []
        this.total = 0
        const d = describeError(err)
        this.listError = d.text
        this.listErrorCause = d.cause
        this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    handleAdd() {
      this.addForm = { name: '', defKey: '', category: '' }
      this.addVisible = true
    },
    submitAdd() {
      this.$refs.addForm.validate(valid => {
        if (!valid) return
        this.creating = true
        // 新建时先落一个最小骨架（发起 → 审批 → 结束），再进设计器继续配
        const skeleton = {
          schemaVersion: 1,
          key: this.addForm.defKey,
          name: this.addForm.name,
          category: this.addForm.category,
          nodes: [
            { id: 'start', type: 'start', name: '发起' },
            {
              id: 'n_first',
              type: 'approve',
              name: '审批节点',
              assignee: { source: 'USER', userIds: [] },
              multiMode: 'SINGLE',
              sameUserPolicy: 'EACH_TIME',
              signMode: 'OPTIONAL',
              signTypes: ['HANDWRITE', 'PRESET'],
              buttons: ['agree', 'return', 'print'],
              emptyPolicy: 'ADMIN',
              selfPolicy: 'SKIP',
              leftPolicy: 'LEADER'
            },
            { id: 'end', type: 'end', name: '结束' }
          ]
        }
        saveSimpleFlowDraft({
          name: this.addForm.name,
          category: this.addForm.category,
          content: JSON.stringify(skeleton)
        }).then(res => {
          this.creating = false
          this.addVisible = false
          this.$modal.msgSuccess('已创建，进入设计器')
          this.$router.push({ path: '/workflow/simple-flow/designer', query: { id: res.data.id } })
        }).catch(err => {
          // 创建失败：**保留弹窗与已填内容**，让用户改完直接重试，不要逼他重敲
          this.creating = false
          this.$modal.msgError('创建失败：' + describeError(err).text)
        })
      })
    },
    handleDesign(row) {
      this.$router.push({ path: '/workflow/simple-flow/designer', query: { id: row.id } })
    },
    /**
     * 发布。
     * ⚠ 这里原来是 `$confirm(...).then(请求).catch(() => {})` —— 一个 catch 同时吞掉了
     * 「用户点了取消」和「发布请求失败」，后者会导致点了发布没反应也没报错。
     * 必须把两条路径拆开：外层 catch 只负责取消，请求失败在内层报出来。
     */
    handlePublish(row) {
      this.$confirm('发布将生成新的流程版本并部署到流程引擎，是否继续？', '提示', { type: 'warning' })
        .then(() => {
          return publishSimpleFlow({ id: row.id, remark: '列表发布' }).then(res => {
            this.$modal.msgSuccess('发布成功 v' + res.data.version)
            this.getList()
          }).catch(err => {
            this.$modal.msgError('发布失败：' + describeError(err).text)
          })
        })
        .catch(() => {}) // 仅：用户取消确认框
    },
    handleHistory(row) {
      historySimpleFlow(row.defKey).then(res => {
        this.historyList = res.data || []
        this.historyVisible = true
      }).catch(err => {
        // 原来这里完全没有 catch —— 失败会变成未处理的 Promise 拒绝，界面毫无反馈
        this.$modal.msgError('版本历史加载失败：' + describeError(err).text)
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除流程「' + row.name + '」？已部署到引擎的流程定义不会自动删除。', '提示', { type: 'warning' })
        .then(() => {
          return delSimpleFlow(row.id).then(() => {
            this.$modal.msgSuccess('删除成功')
            this.getList()
          }).catch(err => {
            this.$modal.msgError('删除失败：' + describeError(err).text)
          })
        })
        .catch(() => {}) // 仅：用户取消确认框
    }
  }
}
</script>
