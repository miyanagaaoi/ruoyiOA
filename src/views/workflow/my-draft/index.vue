<template>
  <!-- 我起草的 -->
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px" @submit.native.prevent>
      <el-form-item label prop="bizTitle">
        <el-input
          v-model="queryParams.bizTitle"
          prefix-icon="el-icon-search"
          placeholder="请输入标题关键字"
          clearable
          @clear="resetQuery"
          @keyup.enter.native="handleQuery"
          style="width: 300px;"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-divider />

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table
      v-else
      v-loading="loading"
      ref="todoTable"
      :data="tableList"
      stripe
      class="pointer"
      @row-click="handleRowClick"
      @select-all="handleSelectAll"
      @select="handleSelectionChange"
      :element-loading-text="loadingText"
      element-loading-spinner="el-icon-loading"
    >
      <!-- <el-table-column type="selection" width="55" align="center" /> -->
      <el-table-column label="序号" type="index" align="center" width="55">
        <template slot-scope="scope">
          <span>{{(queryParams.pageNum - 1) * queryParams.pageSize + scope.$index + 1}}</span>
        </template>
      </el-table-column>
      <el-table-column label="标题" align="left" prop="bizTitle" show-overflow-tooltip />
      <el-table-column label="类型" align="center" prop="templateName" width="150" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status" width="150" show-overflow-tooltip>
        <template slot-scope="scope">
          <dict-tag :options="dict.type.workflow_biz_status" :value="scope.row.status" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="90" fixed="right">
        <template slot-scope="scope">
          <!-- .stop 必须有：整行有点击进详情的处理，不加会连带跳走 -->
          <el-button size="mini" type="text" icon="el-icon-printer" @click.stop="printRow(scope.row)">打印</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
  </div>
</template>

<script>
import { listDraft } from "@/api/workflow/draft";
import DataLoadError from "@/components/DataLoadError";
import { describeError } from "@/utils/errorMessage";

export default {
  name: "MyDraftWorkflow",
  components: { DataLoadError },
  dicts: ["workflow_biz_status"],
  data() {
    return {
      // 遮罩层
      loading: true,
      /** 列表主数据加载失败：文案与原因（空串 = 没失败） */
      loadError: "",
      loadErrorCause: "",
      // loading文本内容
      loadingText: "正在加载中...",
      // 显示搜索条件
      showSearch: true,
      // 当前选中行
      selectedRow: null,
      // 总条数
      total: 0,
      // 待办表格数据
      tableList: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        bizTitle: null,
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /**
     * 列表行打印（PRD 7.2 入口2）。**页内浮层**打开打印预览。
     * 注意草稿列表的业务ID字段是 bizId（与行点击口径一致）。
     */
    printRow(row) {
      const businessId = row.bizId;
      if (!businessId) {
        this.$modal.msgWarning("该记录没有业务ID，无法打印");
        return;
      }
      // 草稿同样可以打印：打印件只输出表单信息，而草稿的数据在 t_workflow_form 里是齐的。
      // （后端已同步改造：无流程实例时降级为「草稿」状态，不再抛异常；
      //   selectTemplateIdByBusinessId 也补了 t_workflow_form 这一路 union。）
      // 页内浮层打开（不跳转、不开新窗口）
      this.$openPrintPreview(businessId);
    },
    /** 行点击 */
    handleRowClick(row, column, event) {
      this.$router.push({
        path: "/workflow/flowForm/" + new Date().getTime(),
        query: {
          title: row.bizTitle,
          pageType: "2",
          businessId: row.bizId,
          procInsId: row.procInstId,
          templateId: row.templateId,
          taskId: row.taskId,
          userId: row.createId,
        },
      });
    },
    /** 查询待办列表 */
    getList() {
      this.loading = true;
      this.loadError = "";
      this.loadErrorCause = "";
      listDraft(this.queryParams).then((response) => {
        this.tableList = response.rows;
        this.total = response.total;
        this.allTotal = this.total;
        this.loading = false;
      }).catch((err) => {
        // 原来这里没有 catch：失败后 loading 永远为 true，表格永久转圈且残留旧数据
        this.loading = false;
        this.tableList = [];
        this.total = 0;
        const d = describeError(err);
        this.loadError = d.text;
        this.loadErrorCause = d.cause;
      });
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.daterangeCreateTime = [];
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 单选事件 */
    handleSelectionChange(selection, row) {
      if (selection.includes(row)) {
        this.$refs.todoTable.clearSelection();
        this.$nextTick(() => {
          this.$refs.todoTable.toggleRowSelection(row, true);
          this.selectedRow = row;
        });
      } else {
        this.selectedRow = null;
      }
    },
    /** 全选控制 */
    handleSelectAll(selection) {
      this.$refs.todoTable.clearSelection();
    },
  },
};
</script>

<style lang="scss" scoped>
::v-deep .el-table .no-read {
  font-weight: 600;
}
::v-deep .el-table th {
  background-color: #f3f9ff !important;
  color: #5e7fa2;
  font-size: 14px;
}

::v-deep .el-table__row .cell {
  color: #303133;
  font-size: 14px;
}
</style>
