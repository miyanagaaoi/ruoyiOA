<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="92px">
      <el-form-item label="编号规则" prop="title">
        <el-input v-model="queryParams.title" placeholder="请输入编号规则" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="生成的编号" prop="code">
        <el-input v-model="queryParams.code" placeholder="请输入生成的编号" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-divider />

    <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="getList" />
    <el-table v-else v-loading="loading" :data="logList">
      <el-table-column label="编号规则" align="center" prop="title" />
      <el-table-column label="生成的编号" align="center" prop="code" />
      <el-table-column label="编号对应的序号" align="center" prop="codeSeq" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="160">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <!-- <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['serial:log:remove']">删除</el-button> -->
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
  </div>
</template>

<script>
import { listLog, delLog } from "@/api/serial/log";
import DataLoadError from "@/components/DataLoadError";
import { describeError } from "@/utils/errorMessage";

export default {
  name: "Log",
  components: { DataLoadError },
  data() {
    return {
      // 遮罩层
      loading: true,
      /** 列表主数据加载失败：文案与原因（空串 = 没失败） */
      loadError: "",
      loadErrorCause: "",
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 编号生成日志表格数据
      logList: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        title: null,
        code: null,
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    /** 查询编号生成日志列表 */
    getList() {
      this.loading = true;
      this.loadError = "";
      this.loadErrorCause = "";
      listLog(this.queryParams).then((response) => {
        this.logList = response.rows;
        this.total = response.total;
        this.loading = false;
      }).catch((err) => {
        // 原来这里没有 catch：失败后 loading 永远为 true，表格永久转圈且残留旧数据
        this.loading = false;
        this.logList = [];
        this.total = 0;
        const d = describeError(err);
        this.loadError = d.text;
        this.loadErrorCause = d.cause;
      });
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id;
      this.$modal
        .confirm('是否确认删除编号生成日志编号为"' + ids + '"的数据项？')
        .then(function () {
          return delLog(ids);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess("删除成功");
        })
        .catch(() => {});
    },
  },
};
</script>
