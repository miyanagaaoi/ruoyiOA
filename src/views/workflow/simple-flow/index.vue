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
        <!--
          「流程标识」默认收进「高级选项」：
          它在模板链路里本来就不该让用户填（B1 spec「流程标识由系统生成」：`tpl_` + 模板ID前8位），
          而独立新建这条链路又必须有一个**全局唯一**的 key。所以这里改成
          「按流程名称自动生成建议 → 用户可改 → 实时校验格式与撞名」，
          默认折叠，需要时展开（详见 flowKey.js 顶部注释）。
        -->
        <el-form-item>
          <el-button type="text" class="adv-toggle" @click="onAdvancedToggle">
            {{ advancedOpen ? '收起高级选项' : '高级选项（流程标识 / 分类）' }}
            <i :class="advancedOpen ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" />
          </el-button>
        </el-form-item>
        <template v-if="advancedOpen">
          <el-form-item label="流程标识" prop="defKey">
            <el-input v-model="addForm.defKey" placeholder="留空则按流程名称自动生成" @input="onDefKeyInput">
              <template slot="append">
                <el-button :loading="checkingKey" @click="refreshSuggestedKey">重新生成</el-button>
              </template>
            </el-input>
            <div v-if="checkingKey" class="adv-tip">正在检查是否已被占用…</div>
            <div v-else-if="keyTaken" class="adv-warn">
              <i class="el-icon-warning-outline" /> 「{{ addForm.defKey }}」已被占用
              <el-button type="text" size="mini" @click="useAlternativeKey">改用 {{ alternativeKey }}</el-button>
            </div>
            <div v-else-if="keyFormatError" class="adv-warn">
              <i class="el-icon-warning-outline" /> {{ keyFormatError }}
            </div>
            <div v-else class="adv-tip">
              <i class="el-icon-success" /> 可用（按名称生成，可自行修改）
            </div>
            <div class="adv-tip">
              作为 Flowable 流程定义 key 写进 BPMN：只能字母/数字/下划线/中划线、不能数字开头、全局唯一，
              <b>发布后不可修改</b>。
            </div>
          </el-form-item>
          <el-form-item label="分类" prop="category">
            <el-input v-model="addForm.category" placeholder="如：contract" />
          </el-form-item>
        </template>
      </el-form>
      <el-alert type="info" :closable="false">
        <div slot="title">
          新建后进入设计器，可继续配审批链路。流程标识会作为 Flowable 的流程定义 key，<b>发布后不可修改</b>；
          从「模板配置 → 流程设计」进入时由系统自动生成，无需手工填写。
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
import { suggestFlowKey, validateFlowKey, suggestAlternativeKey } from './flowKey'

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
      /** 高级选项（流程标识 / 分类）默认折叠：名称会自动带出标识，多数人不需要填 */
      advancedOpen: false,
      /** 用户是否手工改过流程标识 —— 改过之后就不再被"按名称生成"覆盖 */
      defKeyEdited: false,
      /** 服务端查回来的"已被占用"，以及给出的备选标识 */
      keyTaken: false,
      alternativeKey: '',
      checkingKey: false,
      /** 发起查重时的名称/标识快照，用于丢弃过期响应（快速输入时后发先至会错报） */
      checkedFor: { name: '', key: '' },
      /** 待发起的查重定时器（防抖） */
      keyTimer: null,
      addRules: {
        name: [{ required: true, message: '流程名称不能为空', trigger: 'blur' }],
        defKey: [
          // 只在"展开高级选项并手工清空"时才报错；格式与撞名由下面实时提示 + submitAdd 兜底
          { validator: (rule, value, cb) => (String(value || '').trim() ? cb() : cb(new Error('流程标识不能为空'))), trigger: 'blur' }
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
    },
    /** 流程标识的**格式**问题（空串 = 合法）；唯一性另算（见 `keyTaken`） */
    defKeyFormatError() {
      return validateFlowKey(this.addForm.defKey)
    }
  },
  created() {
    this.getList()
  },
  beforeDestroy() {
    // 防抖定时器必须在销毁时清掉，否则弹窗关掉后仍会发一次查重请求（并可能写已销毁的 vm）
    if (this.keyTimer) {
      clearTimeout(this.keyTimer)
      this.keyTimer = null
    }
  },
  watch: {
    /**
     * 流程名称 → 自动带出流程标识（**只在用户没手工改过时**覆盖）。
     *
     * 为什么不直接放在输入框的 @input 上：名称也可能被"清空重填"，watcher 覆盖这两种路径更省心；
     * 防抖 300ms 是为了不在每个字符上打一次接口。
     */
    'addForm.name'(val) {
      if (!this.advancedOpen) return
      if (!this.defKeyEdited) {
        this.addForm.defKey = suggestFlowKey(val)
      }
      this.scheduleKeyCheck()
    }
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
      this.advancedOpen = false
      this.defKeyEdited = false
      this.keyTaken = false
      this.alternativeKey = ''
      this.checkingKey = false
      this.checkedFor = { name: '', key: '' }
      this.addVisible = true
    },
    /* ---------------- 流程标识：建议生成 + 实时校验 ---------------- */

    /** 展开/收起高级选项；展开时补一个建议标识并查重 */
    onAdvancedToggle() {
      this.advancedOpen = !this.advancedOpen
      if (this.advancedOpen) this.ensureSuggestedKey()
    },

    /** 用户手工改了标识 —— 此后不再被"按名称生成"覆盖 */
    onDefKeyInput() {
      this.defKeyEdited = true
      this.scheduleKeyCheck()
    },

    /** 展开高级选项时若还没有标识，就先生成一个（含"名称已填、直接点高级选项"的路径） */
    ensureSuggestedKey() {
      if (!this.addForm.defKey && !this.defKeyEdited) {
        this.addForm.defKey = suggestFlowKey(this.addForm.name)
      }
      this.scheduleKeyCheck()
    },

    /** 点「重新生成」：按当前名称重算（即使之前手工改过也重算 —— 这是显式动作） */
    refreshSuggestedKey() {
      this.defKeyEdited = false
      this.addForm.defKey = suggestFlowKey(this.addForm.name)
      this.scheduleKeyCheck()
    },

    /** 采用备选标识（如 htsp → htsp_2） */
    useAlternativeKey() {
      if (!this.alternativeKey) return
      this.addForm.defKey = this.alternativeKey
      this.defKeyEdited = true
      // 立刻清掉"已被占用"的结论：否则在重新查重的这段时间里，
      // 界面会挂着「_2 已被占用、改用 _2」这种自相矛盾的话（实测踩到）
      this.keyTaken = false
      this.alternativeKey = ''
      this.scheduleKeyCheck()
    },

    /** 防抖 300ms 后发起查重（格式不合法时不必打接口） */
    scheduleKeyCheck() {
      if (this.keyTimer) clearTimeout(this.keyTimer)
      // 提问期间先把**上一次的结论**清掉并置成"检查中"，避免显示过期结论
      this.keyTaken = false
      this.alternativeKey = ''
      this.checkingKey = true
      this.keyTimer = setTimeout(() => {
        this.keyTimer = null
        this.checkDefKey()
      }, 300)
    },

    /**
     * 查重：**只查"未删除范围内"是否已存在**（服务端 `selectList` 支持 defKey 精确匹配）。
     * 这是**体验优化**，不是权威判定 —— 真正拦截仍在 `saveDraft`；
     * 所以接口失败时**不报错、不阻断**（可能只是没有列表权限），安静地放过让服务端去判。
     */
    checkDefKey() {
      const key = String(this.addForm.defKey || '').trim()
      this.keyTaken = false
      this.alternativeKey = ''
      this.checkingKey = false

      if (!key) return
      // 格式不合法时不必问服务端（提示已经在模板里显示）
      if (this.defKeyFormatError) return

      const token = { name: this.addForm.name, key: key }
      this.checkedFor = token
      this.checkingKey = true
      listSimpleFlow({ pageNum: 1, pageSize: 1, defKey: key }).then(res => {
        // 快速输入时响应可能乱序：**只认最后一次提问的结果**，否则会把"旧 key 的结论"贴到新 key 上
        if (this.checkedFor !== token) return
        this.checkingKey = false
        const total = Number((res && res.total) || 0)
        this.keyTaken = total > 0
        this.alternativeKey = this.keyTaken ? suggestAlternativeKey(key, []) : ''
      }).catch(() => {
        if (this.checkedFor !== token) return
        this.checkingKey = false
        // 静默放过：查重失败不该拦住用户（服务端仍会校验并给出错误）
      })
    },
    submitAdd() {
      // ① 还没生成标识就先按名称生成一次（用户可能全程没展开高级选项）
      if (!this.addForm.defKey) {
        this.addForm.defKey = suggestFlowKey(this.addForm.name)
      }
      // ② 提交前把**格式**问题挡在本地（服务端 V-0 的口径完全相同）
      const fmt = this.defKeyFormatError
      if (fmt) {
        this.advancedOpen = true
        this.$modal.msgError('流程标识不可用：' + fmt)
        return
      }
      // ③ 已知撞名就不再白跑一趟服务端（后端仍会再判一次，这里只是省一次失败往返）
      if (this.keyTaken) {
        this.advancedOpen = true
        this.$modal.msgError('流程标识「' + this.addForm.defKey + '」已被占用，请点「重新生成」或改用 ' + this.alternativeKey)
        return
      }
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

<style lang="scss" scoped>
/* 高级选项里流程标识的实时校验提示（这一页原来没有样式块，为它新增） */
.adv-toggle {
  padding: 0;
  font-size: 12px;
}
.adv-tip {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  .el-icon-success {
    color: #67c23a;
  }
}
.adv-warn {
  margin-top: 4px;
  color: #e6a23c;
  font-size: 12px;
  line-height: 1.6;
}
</style>
