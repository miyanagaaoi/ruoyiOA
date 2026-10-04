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

    <el-table v-loading="loading" :data="list">
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

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

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

export default {
  name: 'SimpleFlowList',
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
      historyList: []
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listSimpleFlow(this.queryParams).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(() => {
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
        }).catch(() => {
          this.creating = false
        })
      })
    },
    handleDesign(row) {
      this.$router.push({ path: '/workflow/simple-flow/designer', query: { id: row.id } })
    },
    handlePublish(row) {
      this.$confirm('发布将生成新的流程版本并部署到流程引擎，是否继续？', '提示', { type: 'warning' })
        .then(() => publishSimpleFlow({ id: row.id, remark: '列表发布' }))
        .then(res => {
          this.$modal.msgSuccess('发布成功 v' + res.data.version)
          this.getList()
        })
        .catch(() => {})
    },
    handleHistory(row) {
      historySimpleFlow(row.defKey).then(res => {
        this.historyList = res.data || []
        this.historyVisible = true
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除流程「' + row.name + '」？已部署到引擎的流程定义不会自动删除。', '提示', { type: 'warning' })
        .then(() => delSimpleFlow(row.id))
        .then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
        .catch(() => {})
    }
  }
}
</script>
