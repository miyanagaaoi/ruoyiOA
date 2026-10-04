<template>
  <!-- 待办组件-table风格 -->
  <div class="todo-list-container">
    <div class="sum-container">
      <div class="sum-item pending-approval">
        <div class="content">
          <div class="count">{{ todoTotal }}</div>
          <div class="desc">待审批</div>
        </div>
        <div>
          <svg-icon icon-class="todo" class="todo-flag" />
        </div>
      </div>
      <div class="sum-item pending-review">
        <div class="content">
          <div class="count">1</div>
          <div class="desc">待查阅</div>
        </div>
        <div>
          <svg-icon icon-class="toRead" class="toread-flag" />
        </div>
      </div>
    </div>
    <div class="todo-body">
      <!-- 数据区：只有 .todo-body 内部随状态切换（.todo-body 自己要 flex:1，所以留在外面） -->
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
        <el-table
          ref="todoTable"
          :data="todoList"
          :row-class-name="tableRowClassName"
          stripe
          class="pointer"
          height="60vh"
          @row-click="handleRowClick"
        >
          <el-table-column label="序号" type="index" align="center" width="55">
            <template slot-scope="scope">
              <span>{{scope.$index + 1}}</span>
            </template>
          </el-table-column>
          <el-table-column label="标题" align="left" prop="title" show-overflow-tooltip>
            <template slot-scope="scope">
              <span v-if="scope.row.urgeFlag === '1'" class="urge">【催】</span>
              <span v-if="scope.row.urgencyStatus !== '0'" class="urgeStatus">{{ urgency(scope.row.urgencyStatus) }}</span>
              <span v-if="scope.row.type === '0'" class="draft">【草稿】</span>
              <span>{{ scope.row.title }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" align="center" prop="templateName" width="150" show-overflow-tooltip />
          <el-table-column label="当前环节" align="center" prop="curNode" width="150" show-overflow-tooltip />
          <el-table-column label="发送人" align="center" prop="senderName" width="120" show-overflow-tooltip />
          <el-table-column label="发送时间" align="center" prop="sendTime" width="180">
            <template slot-scope="scope">
              <span>{{ parseTime(scope.row.sendTime, '{y}-{m}-{d} {h}:{i}:{s}') }}</span>
            </template>
          </el-table-column>
        </el-table>
        <!-- 空态必须有标题 + 说明 + 一个主行动 -->
        <template slot="empty-action">
          <el-button type="primary" size="mini" @click="goMyApply">查看我发起的</el-button>
        </template>
      </StateBlock>
    </div>
    <div class="todo-footer" v-if="fetchState === 'ready'">
      <div v-if="hasMore" @click="loadMore" class="load-more">
        加载更多
        <i class="el-icon-arrow-down" />
      </div>
      <div v-else class="no-more">已加载全部数据！</div>
    </div>
  </div>
</template>

<script>
import { listTodoTable, readTodo } from "@/api/workflow/todo";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";
export default {
  name: "TodoTableModule",
  components: {
    StateBlock,
  },
  data() {
    return {
      // 取数状态：loading | error | empty | ready（交给 StateBlock 渲染，错误不降级成空态）
      fetchState: "loading",
      // 错误三问：发生了什么 / 为什么（"怎么办"由 StateBlock 的重试按钮承担）
      errorText: "",
      errorCause: "",
      // 请求是否在途（防止"加载更多"被重复点击）
      fetching: false,
      // 待办总数
      todoTotal: 0,
      // 待办列表
      todoList: [],
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
  },
  methods: {
    /** 查询待办列表（首次加载与"加载更多"共用） */
    async getList() {
      if (this.fetching) return;
      this.fetching = true;
      // 已有列表时是"加载更多"，保留旧内容避免整块闪白；首次加载/重试才进加载态
      if (!this.todoList.length) {
        this.fetchState = "loading";
        this.errorText = "";
        this.errorCause = "";
      }
      try {
        const response = await listTodoTable(this.queryParams);
        if (response && response.code == 200) {
          this.todoList = [...this.todoList, ...(response.rows || [])];
          this.todoTotal = response.total;
          this.queryParams.pageNum += 1;
          this.hasMore = this.todoTotal > this.todoList.length ? true : false;
          this.errorText = "";
          this.errorCause = "";
          // 请求成功但确实没有待办 → empty；有待办 → ready
          this.fetchState = this.todoList.length > 0 ? "ready" : "empty";
        } else {
          this.hasMore = false;
          this.applyError({ message: "接口返回了非 200 状态码（code=" + (response && response.code) + "）。" });
        }
      } catch (e) {
        this.hasMore = false;
        this.applyError(e);
      } finally {
        this.fetching = false;
      }
    },
    /** 加载更多 */
    async loadMore() {
      await this.getList();
    },
    /** 取数失败：清掉旧数据并进入错误态（文案来自 describeError） */
    applyError(err) {
      const d = describeError(err);
      // 不能出现"旧列表 + 错误提示"同屏：列表清空
      this.todoList = [];
      // 列表已清空，页码必须回到第一页，否则重试会从第 N 页开始拼数据
      this.queryParams.pageNum = 1;
      this.hasMore = false;
      this.todoTotal = "-";
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
      if (row.readFlag === "0") {
        row.readFlag = "1";
        // 标记已读失败不回滚跳转，但也不能留一个未处理的 Promise 拒绝
        readTodo(row.id).catch((e) => {
          console.warn("[待办] 标记已读失败", e);
        });
      }
      this.$router.push({
        path: "/workflow/flowForm/" + new Date().getTime(),
        query: {
          title: row.title,
          pageType: "1",
          todoId: row.id,
          businessId: row.businessId,
          procInsId: row.procInstId,
          templateId: row.templateId,
          taskId: row.taskId,
          userId: row.curHandler,
          draft: row.type === "0" ? "1" : "0",
          type: row.type,
        },
      });
    },
  },
};
</script>

<style scoped lang="scss">
.sum-container {
  height: 52px;
  display: flex;
  margin: 10px 10px 0px 10px;
}

.sum-item {
  width: 200px;
  padding: 24px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-right: 24px;
  text-align: center;
}

.todo-flag,
.toread-flag {
  width: 32px;
  height: 32px;
}
.todo-flag {
  margin-right: 12px;
  color: #e1f0ff;
}

.toread-flag {
  margin-right: 12px;
  color: #ffefdc;
}
.pending-approval {
  background: linear-gradient(90deg, #e8f4ff, #f1f7ff, #f7faff);
}

.pending-review {
  background: linear-gradient(90deg, #fff6ec, #fffcf7);
}

.content {
  text-align: center;
}

.count {
  font-size: 16px;
  font-weight: bold;
  color: #606266;
}

.desc {
  font-size: 13px;
  color: #909399;
}

.todo-list-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 73vh;
  border: 1px solid #e8e8e8;
  box-sizing: border-box;
  background-color: #ffffff;
  box-shadow: 0 2px 12px 0 rgb(0 0 0 / 10%);
}

.todo-body {
  flex: 1;
  padding: 10px 10px 0 10px;
  box-sizing: border-box;
}

.title {
  font-size: 14px;
  font-weight: bold;
}

.todo-item {
  padding: 10px;
  border-bottom: 1px solid #ddd;
}

.todo-footer {
  flex: 0 0 40px; // 固定高度
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #ffffff;
}

.load-more,
.no-more {
  font-size: 13px;
  color: #909399;
  cursor: pointer;
  text-align: center;
}

::v-deep .el-table .no-read {
  font-weight: 600;
}
::v-deep .el-table th {
  background-color: #f3f9ff !important;
  color: #5e7fa2;
  font-size: 14px;
}

::v-deep .el-table__row .cell {
  color: #606266;
  font-size: 14px;
}

.draft {
  font-size: 14px;
  font-weight: 600;
  color: #606266;
}

.urge {
  font-size: 14px;
  font-weight: 600;
  color: #f19f00;
}

.urgeStatus {
  font-size: 14px;
  font-weight: 600;
  color: #fd0505;
}
</style>

