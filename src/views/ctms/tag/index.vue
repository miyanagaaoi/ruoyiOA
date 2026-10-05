<template>
  <div class="app-container">
    <!--
      合同标签字典管理（2.0 B3 任务 8.3）。

      口径（三条都在下面有落点，改动前先看）：
      1. 本页只维护**标签字典**（名称/颜色/是否内置）：接口 /ctms/tag/list|{id}|POST|PUT|DELETE；
         权限点真源 `sql/二开-合同台账-菜单.sql`：页面 ctms:tag:list，
         新增/修改/删除分别是 ctms:tag:add / ctms:tag:edit / ctms:tag:remove。
      2. **自动标签**（与合同类型同名的标签、「框架合同」）由服务端在保存合同时
         `syncAutoTags` 自动附加/替换：本页用「系统自动」标记把它们标出来，
         但**刻意不提供任何"手动同步"按钮** —— 手动同步会把别人手工加的标签一起清掉，
         而规格明确要求「手动添加的标签不得被自动同步移除」。
      3. 删除有引用守卫：被（未停用）合同引用的标签由服务端拒绝，
         返回「标签已被合同引用，无法删除」，这里原样透出服务端文案。
    -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb8"
      title="自动标签（与合同类型同名的标签、「框架合同」）由服务端在保存合同时自动附加/替换，本页只做字典维护，刻意不提供手动同步入口。被未停用合同引用的标签无法删除（服务端拒绝）。"
    />

    <!-- ==================== 查询条件 ==================== -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="标签名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入标签名称"
          clearable
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="是否内置" prop="builtin">
        <el-select v-model="queryParams.builtin" placeholder="全部" clearable style="width: 140px">
          <el-option label="内置" value="1" />
          <el-option label="自定义" value="0" />
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
          v-hasPermi="['ctms:tag:add']"
          >新增标签</el-button
        >
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 取数失败必须显式呈现（审计「类 1」拦的就是"没有 catch → loading 永久 true"） -->
    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="list">
      <el-table-column label="标签名称" align="left" prop="name" min-width="240">
        <template slot-scope="scope">
          <span class="tag-dot" :style="{ backgroundColor: scope.row.color }"></span>
          <span>{{ scope.row.name }}</span>
          <el-tag v-if="isAutoTag(scope.row.name)" size="mini" type="success" class="ml6">系统自动</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="颜色" align="center" prop="color" width="150">
        <template slot-scope="scope">
          <span v-if="scope.row.color" class="color-text">{{ scope.row.color }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="是否内置" align="center" prop="builtin" width="110">
        <template slot-scope="scope">
          <el-tag :type="scope.row.builtin === '1' ? 'warning' : 'info'" size="mini">{{
            scope.row.builtin === '1' ? '内置' : '自定义'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建人" align="center" prop="createBy" width="130">
        <template slot-scope="scope">
          <span v-if="scope.row.createBy">{{ scope.row.createBy }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createTime" width="170">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="150" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['ctms:tag:edit']"
            >修改</el-button
          >
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['ctms:tag:remove']"
            >删除</el-button
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

    <!-- ==================== 新增 / 修改 ==================== -->
    <el-dialog :title="title" :visible.sync="open" width="520px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="标签名称" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="必填；全局唯一" />
        </el-form-item>
        <el-form-item label="颜色" prop="color">
          <el-color-picker v-model="form.color" />
          <span class="form-tip">留空＝新增时由服务端按已有标签数自动选色；修改时保持原色（色板在服务端）</span>
        </el-form-item>
        <el-form-item label="内置标签" prop="builtin">
          <el-switch v-model="form.builtin" active-value="1" inactive-value="0" />
          <span class="form-tip">仅作管理标记；自动标签由服务端维护，与本开关无关</span>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTag, getTag, addTag, updateTag, delTag } from '@/api/ctms/tag'
import DataLoadError from '@/components/DataLoadError'
import { describeError } from '@/utils/errorMessage'

/**
 * 框架合同自动标签名（**只用于界面标记**，不参与任何写入判定）。
 * ⚠ 与后端 `CtmsTagServiceImpl.FRAMEWORK_TAG_NAME` 逐字一致；后端若改名这里要同步改。
 *   除此之外的自动标签名（与合同类型同名的那批）**不写死在前端** —— 它们等于
 *   `contract_types` 字典的取值（后端 `ensureTag(contract.type)`，而 type 存的就是字典值），
 *   所以运行时从字典推导（见 autoTagNames 计算属性），避免两处清单漂移。
 */
const FRAMEWORK_TAG_NAME = '框架合同'

export default {
  name: 'CtmsTag',
  components: { DataLoadError },
  dicts: ['contract_types'],
  data() {
    return {
      loading: false,
      loadError: '',
      loadErrorCause: '',
      showSearch: true,
      list: [],
      total: 0,
      title: '',
      open: false,
      submitLoading: false,
      form: {},
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: undefined,
        builtin: undefined
      }
    }
  },
  computed: {
    /**
     * 系统自动维护的标签名清单：与合同类型同名的那批（取 contract_types 字典的取值）
     * 加上「框架合同」。用来在列表里打「系统自动」标记。
     */
    autoTagNames() {
      const names = [FRAMEWORK_TAG_NAME]
      const types = (this.dict && this.dict.type && this.dict.type.contract_types) || []
      types.forEach((item) => {
        if (item && item.value) {
          names.push(item.value)
        }
      })
      return names
    },
    rules() {
      return {
        name: [{ required: true, message: '标签名称不能为空', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    isAutoTag(name) {
      return !!name && this.autoTagNames.indexOf(name) >= 0
    },
    getList() {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      listTag(this.queryParams)
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
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.queryParams.name = undefined
      this.queryParams.builtin = undefined
      this.handleQuery()
    },
    /**
     * 清空表单并重置校验态。
     * ⚠ 不用 `resetForm('form')`：el-form 的 resetFields() 会回到"首次挂载时"的初值
     *   （上一条标签的名称/颜色），点开第二条却显示第一条的值就是这么来的。
     */
    reset() {
      this.form = { id: undefined, name: undefined, color: undefined, builtin: '0' }
      this.$nextTick(() => {
        if (this.$refs['form']) {
          this.$refs['form'].clearValidate()
        }
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = '新增标签'
    },
    handleUpdate(row) {
      getTag(row.id)
        .then((response) => {
          this.reset()
          this.form = response.data || {}
          this.open = true
          this.title = '修改标签'
        })
        .catch((err) => {
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    submitForm() {
      this.$refs['form'].validate((valid) => {
        if (!valid) {
          return
        }
        const isEdit = !!this.form.id
        this.submitLoading = true
        const job = isEdit ? updateTag(this.form) : addTag(this.form)
        job
          .then(() => {
            this.submitLoading = false
            this.$modal.msgSuccess(isEdit ? '修改成功' : '新增成功')
            this.open = false
            this.getList()
          })
          .catch((err) => {
            // 服务端的「标签名称已存在」要原样透出，不要换成泛泛的"操作失败"
            this.submitLoading = false
            const d = describeError(err)
            this.$modal.msgError(d.text)
          })
      })
    },
    handleDelete(row) {
      // 自动标签的删除提示要说清后果：删掉后被引用会被拒；即使删掉未被引用的，
      // 下次保存该类型/框架合同时服务端也会自动重建（这是 ensureTag 的行为）
      const autoHint = this.isAutoTag(row.name)
        ? '<br/>「' + row.name + '」是系统自动标签：被合同引用时删除会被服务端拒绝；即使删除成功，下次保存相关合同时也会自动重建。'
        : '<br/>被未停用合同引用的标签无法删除（服务端会拒绝）。'
      this.$modal
        .confirm('是否确认删除标签「' + row.name + '」？' + autoHint)
        .then(() => {
          return delTag(row.id)
        })
        .then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
        .catch((err) => {
          this.reportActionError(err)
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
/* 只写令牌，不写裸色值（DEV-ENV §6.9/§6.11；标签颜色是**数据**，通过 :style 绑定） */
.text-muted {
  color: var(--oa-color-ink-subtle);
}
.form-tip {
  margin-left: 8px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
}
.ml6 {
  margin-left: 6px;
}
.color-text {
  color: var(--oa-color-ink-muted);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.tag-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  vertical-align: middle;
}
</style>
