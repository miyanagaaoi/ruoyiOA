<template>
  <!-- 待办组件-collapse风格 -->
  <div class="todo-list-container">
    <div class="position-title">
      <span>
        <svg-icon icon-class="todo1" class="todo1-flag" />我的待办
        <el-button v-if="isSpread" type="text" @click="handleCollapse(0)">
          <i class="el-icon-arrow-up"></i>
        </el-button>
        <el-button v-else type="text" @click="handleCollapse(1)">
          <i class="el-icon-arrow-down"></i>
        </el-button>
      </span>
    </div>
    <div class="todo-body">
      <!-- 数据区：只有 .todo-body 内部随状态切换，标题栏与展开/收起按钮常驻 -->
      <StateBlock
        :state="fetchState"
        :compact="true"
        loading-text="正在加载待办…"
        :error-text="errorText"
        :error-cause="errorCause"
        empty-title="暂无待办"
        empty-desc="当前没有需要你处理的审批任务。"
        @retry="getList"
      >
        <el-collapse v-model="activeNames">
          <el-collapse-item v-for="item in collapseList" :key="item.templateId" :name="item.templateId">
            <template slot="title">
              <i :class="['header-icon', activeNames.includes(item.templateId) ? 'el-icon-caret-bottom' : 'el-icon-caret-top']" class="title-icon"></i>
              <span class="title-text">{{ item.templateName}}</span>
              <span class="count">({{ item.count }})</span>
            </template>
            <el-table
              ref="todoTable"
              :data="item.todoList"
              :row-class-name="tableRowClassName"
              :show-header="false"
              stripe
              class="pointer"
              @row-click="handleRowClick"
            >
              <el-table-column label="标题" align="left" prop="title" show-overflow-tooltip>
                <template slot-scope="scope">
                  <span v-if="scope.row.urgeFlag === '1'" class="urge">【催】</span>
                  <span v-if="scope.row.urgencyStatus && scope.row.urgencyStatus !== '0'" class="urgeStatus">{{ urgency(scope.row.urgencyStatus) }}</span>
                  <span
                    v-else
                    :class="scope.row.handleType === '0' ? 'draft' : scope.row.handleType === '1' ? '' : 'hanleType'"
                  >{{ handleTypeDesc(scope.row.handleType) }}</span>
                  <span>{{ scope.row.title }}</span>
                </template>
              </el-table-column>
              <el-table-column label="发送时间" align="left" prop="sendTime" width="160">
                <template slot-scope="scope">
                  <span>{{ parseTime(scope.row.sendTime, '{y}-{m}-{d} {h}:{i}') }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <!-- 空态必须有标题 + 说明 + 一个主行动 -->
        <template slot="empty-action">
          <el-button type="primary" size="mini" @click="goMyApply">查看我发起的</el-button>
        </template>
      </StateBlock>
    </div>
    <div class="todo-footer"></div>
  </div>
</template>

<script>
import { listTodoCollapse, readTodo, readCopyTodo } from "@/api/workflow/todo";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";

export default {
  name: "TodoCollapseModule",
  components: {
    StateBlock,
  },
  data() {
    return {
      // 加载状态
      activeNames: [],
      // 展开状态
      isSpread: true,
      // 取数状态：loading | error | empty | ready（交给 StateBlock 渲染，错误不降级成空态）
      fetchState: "loading",
      // 错误三问：发生了什么 / 为什么（"怎么办"由 StateBlock 的重试按钮承担）
      errorText: "",
      errorCause: "",
      // 请求是否在途
      fetching: false,
      // 待办总数
      todoTotal: 0,
      // 待办数据
      collapseList: [],
      // 是否有更多
      hasMore: false,
      // 请求参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
      },
    };
  },
  mounted() {
    this.getList();
  },
  computed: {
    // 接收人
    urgency() {
      return (val) => {
        if (!val || val === "0") return "";
        let desc = "";
        switch (val) {
          case "1":
            desc = "【加急】";
            break;
          case "2":
            desc = "【紧急】";
            break;
          case "3":
            desc = "【特急】";
            break;
        }
        return desc;
      };
    },
    // 待办处理类型
    handleTypeDesc() {
      return (val) => {
        if (!val || val === "1") return "";
        let desc = "";
        switch (val) {
          case "0":
            desc = "【草稿】";
            break;
          case "2":
            desc = "【驳回】";
            break;
          case "3":
            desc = "【退回】";
            break;
          case "4":
          case "5":
            desc = "【取回】";
            break;
          case "6":
            desc = "【抄送】";
            break;
          case "7":
            desc = "【委派】";
            break;
          case "8":
            desc = "【转办】";
            break;
          case "9":
            desc = "【加签】";
            break;
          case "10":
            desc = "【委托】";
            break;
          case "11":
            desc = "【秘书办理】";
            break;
        }
        return desc;
      };
    },
  },
  methods: {
    /** 查询待办列表 */
    async getList() {
      if (this.fetching) return;
      this.fetching = true;
      // 已有内容时（socket 推送会调 refleshTodo 再次触发）不闪 loading，直接换数据
      if (this.fetchState !== "ready") {
        this.fetchState = "loading";
        this.errorText = "";
        this.errorCause = "";
      }
      try {
        const response = await listTodoCollapse(this.queryParams);
        if (response && response.code == 200) {
          const list = response.data || [];
          this.collapseList = list;
          // activeNames / todoTotal 都是累加值：必须清零后重建，否则刷新一次就重复计数一次
          this.activeNames = [];
          this.todoTotal = 0;
          list.forEach((item) => {
            this.activeNames.push(item.templateId);
            this.todoTotal += item.count;
          });
          this.errorText = "";
          this.errorCause = "";
          // 请求成功但确实没有待办 → empty；有待办 → ready
          this.fetchState = list.length > 0 ? "ready" : "empty";
        } else {
          this.applyError({ message: "接口返回了非 200 状态码（code=" + (response && response.code) + "）。" });
        }
      } catch (e) {
        this.applyError(e);
      } finally {
        this.fetching = false;
      }
    },
    /** 取数失败：清掉旧数据并进入错误态（文案来自 describeError） */
    applyError(err) {
      const d = describeError(err);
      // 不能出现"旧待办 + 错误提示"同屏
      this.collapseList = [];
      this.activeNames = [];
      this.todoTotal = 0;
      this.errorText = d.text;
      this.errorCause = d.cause;
      this.fetchState = "error";
    },
    /** 空态主行动：没有待办时去看自己发起的流程 */
    goMyApply() {
      this.$router.push({ path: "/my/apply" });
    },
    /** 行样式控制 */
    tableRowClassName({ row, rowIndex }) {
      if (row.readFlag === "0") {
        return "no-read";
      }
      return "";
    },
    /** 行点击 */
    handleRowClick(row, column, event) {
      let pageType = "1";
      if (row.handleType === "0") {
        pageType = "0"; // 草稿
      }
      if (row.handleType === "6") {
        pageType = "2";
        readCopyTodo(row.id)
          .then((res) => {
            if (res.code === 200) {
              this.totalNoRead = this.totalNoRead-- <= 0 ? 0 : this.totalNoRead;
              this.$emit("resetNoReadTotal", this.totalNoRead);
            }
          })
          .catch((e) => {
            // 抄送已读标记失败不回滚跳转，只是未读数会偏大；记日志避免未处理的 Promise 拒绝
            console.warn("[待办] 抄送已读标记失败", e);
          });
      } else if (row.readFlag === "0") {
        row.readFlag = "1";
        readTodo(row.id).catch((e) => {
          console.warn("[待办] 标记已读失败", e);
        });
      }
      this.$router.push({
        path: "/workflow/flowForm/" + new Date().getTime(),
        query: {
          title: row.title,
          pageType: pageType,
          todoId: row.id,
          businessId: row.businessId,
          procInsId: row.procInstId,
          templateId: row.templateId,
          taskId: row.taskId,
          userId: row.curHandler,
          draft: row.handleType === "0" ? "1" : "0",
          type: row.type,
          handleType: row.handleType,
        },
      });
    },
    /** 处理展开、收起 */
    handleCollapse(type) {
      this.isSpread = type == 0 ? false : true;
      if (type == 1) {
        this.collapseAll();
      } else {
        this.activeNames = [];
      }
    },
    /** 展开全部 */
    collapseAll() {
      this.activeNames = [];
      if (this.collapseList && this.collapseList.length > 0) {
        this.collapseList.forEach((item) => {
          this.activeNames.push(item.templateId);
        });
      }
    },
  },
};
</script>

<style scoped lang="scss">
.todo-list-container {
  display: flex;
  flex-direction: column;
  height: calc(50vh - 10px);
  border: 1px solid #f5f5f5;
  box-sizing: border-box;
  border-radius: 4px;
  background-color: #ffffff;
  box-shadow: 0 2px 12px 0 rgb(0 0 0 / 10%);
  margin-bottom: 10px;
}
.position-title {
  font-size: 14px;
  font-weight: bold;
  border-bottom: 1px solid #f1f1f1;
  padding: 12px;
}

.todo1-flag {
  margin-right: 4px;
  width: 14px;
  height: 14px;
}

.header-icon {
  margin-right: 3px;
  font-weight: bold;
}

.todo-body {
  min-height: 42vh;
  flex: 1;
  padding: 0 15px;
  box-sizing: border-box;
  overflow-y: auto;
}

.title-icon {
  color: #c0c4cc;
  font-weight: 500;
  font-size: 12px;
}
.title-text {
  font-size: 14px;
  font-weight: bold;
  margin-left: 3px;
}
.count {
  color: #1c84c6;
  margin-left: 4px;
}
.todo-footer {
  flex: 0 0 12px;
  background-color: #ffffff;
}

.draft {
  font-size: 14px;
  font-weight: 500;
  color: #606266;
  margin-left: -6px;
}
.urge {
  font-size: 14px;
  font-weight: 600;
  color: #f19f00;
  margin-left: -6px;
}
.urgeStatus {
  font-size: 14px;
  font-weight: 600;
  color: #fd0505;
  margin-left: -6px;
}
.hanleType {
  font-size: 14px;
  font-weight: 600;
  color: #fd0505;
  margin-left: -6px;
}

::v-deep .el-collapse {
  border-top: none;
  border-bottom: none;
}
::v-deep .el-collapse-item__header {
  border-bottom: none;
}

::v-deep .el-collapse-item__header .el-icon-arrow-right {
  display: none;
}
::v-deep .el-collapse-item {
  border-top: none !important;
}
::v-deep .el-collapse-item .el-collapse-item__header {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
  border-top: none !important;
  background-color: transparent !important;
}
::v-deep .el-collapse-item__wrap {
  color: #303133;
  border-bottom: none;
}

::v-deep .el-table .no-read {
  font-weight: 600;
}
::v-deep .el-table__row .cell {
  color: #303133;
  font-size: 14px;
  padding-left: 30px;
}
::v-deep .el-table__body tr td,
::v-deep .el-table__body tr th {
  border-bottom: none !important;
}
::v-deep .el-table {
  border: none !important;
}

::v-deep .el-table::before {
  height: 0px !important;
}

::v-deep .el-table__body tr {
  border-bottom: none !important;
}

::v-deep .el-table__body tr:last-child td {
  border-bottom: none !important;
}

::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}
::-webkit-scrollbar-track {
  background-color: #eee;
}
::-webkit-scrollbar-thumb {
  background-color: #ccc;
  border-radius: 4px;
}
::-webkit-scrollbar-thumb:hover {
  background-color: #aaa;
}
</style>

