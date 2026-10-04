<template>
  <!-- 统计 -->
  <div class="sum-container">
    <StateBlock
      :state="fetchState"
      :compact="true"
      loading-text="正在加载统计…"
      :error-text="errorText"
      :error-cause="errorCause"
      empty-title="暂无统计数据"
      empty-desc="接口调用成功但没有返回统计值，稍后可重试。"
      @retry="initData"
    >
      <div class="sum-items">
        <div class="sum-item pending-approval pointer" @click="goTodo">
          <div class="content">
            <div class="count">{{ todoTotal }}</div>
            <div class="desc">待审批</div>
          </div>
          <div>
            <svg-icon icon-class="todo" class="todo-flag" />
          </div>
        </div>
        <div class="sum-item pending-review pointer" @click="goRead">
          <div class="content">
            <div class="count">{{ toReadTotal }}</div>
            <div class="desc">待查阅</div>
          </div>
          <div>
            <svg-icon icon-class="toRead" class="toread-flag" />
          </div>
        </div>
        <div class="sum-item my-apply pointer" @click="goMyDraft">
          <div class="content">
            <div class="count">{{ myDraftTotal }}</div>
            <div class="desc">我发起的</div>
          </div>
          <div>
            <svg-icon icon-class="add" class="add-flag" />
          </div>
        </div>
      </div>
      <!-- 空态必须有标题 + 说明 + 一个主行动 -->
      <template slot="empty-action">
        <el-button type="primary" size="mini" @click="goTodo">去我的待办</el-button>
      </template>
    </StateBlock>
  </div>
</template>

<script>
import { stat } from "@/api/workflow/todo";
import { statMyDraft } from "@/api/workflow/draft";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";

export default {
  name: "StaticTodoModule",
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
      // 待办总数
      todoTotal: "0",
      // 待阅总数
      toReadTotal: "0",
      // 我起草的总数
      myDraftTotal: "0",
    };
  },
  mounted() {
    this.initData();
  },
  methods: {
    initData() {
      // 三个数字是一张卡：任一失败就整体进错误态，不允许"一半新数字 + 一半错误提示"
      this.errorText = "";
      this.errorCause = "";
      // 已有数据时的静默刷新（socket 推送会反复调 initData）不闪 loading
      if (this.fetchState !== "ready") {
        this.fetchState = "loading";
      }
      return Promise.all([
        this.fetchStat("待审批", stat("1")),
        this.fetchStat("待查阅", stat("2")),
        this.fetchStat("我发起的", statMyDraft()),
      ]).then((results) => {
        const failed = results.filter((r) => r.error);
        if (failed.length > 0) {
          this.applyError(failed);
          return;
        }
        const values = results.map((r) => r.data);
        // 请求成功但三个接口都没给出统计值 → empty。
        // 注意：0 是有效数据（"没有待办"），不能当成空态。
        if (!values.some((v) => v !== null && v !== undefined && v !== "")) {
          this.todoTotal = "-";
          this.toReadTotal = "-";
          this.myDraftTotal = "-";
          this.fetchState = "empty";
          return;
        }
        this.todoTotal = this.statText(values[0]);
        this.toReadTotal = this.statText(values[1]);
        this.myDraftTotal = this.statText(values[2]);
        this.fetchState = "ready";
      });
    },
    /** 单个统计接口：失败包成 { label, error } 返回，不抛出，由 initData 统一决策 */
    async fetchStat(label, request) {
      try {
        const res = await request;
        if (!res || res.code != 200) {
          // request.js 不会把非 200 的响应 resolve 出来，这里只兜底"响应形状不对"的情况；
          // 文案仍然走同一个 describeError，避免出现第二处错误文案真源
          return {
            label: label,
            error: describeError({ message: "接口返回了非 200 状态码（code=" + (res && res.code) + "）。" }),
          };
        }
        return { label: label, data: res.data };
      } catch (e) {
        return { label: label, error: describeError(e) };
      }
    },
    /** 任一统计失败：清掉三个数字再进错误态，避免"新错误 + 旧数字"同屏 */
    applyError(failed) {
      const first = failed[0];
      this.todoTotal = "-";
      this.toReadTotal = "-";
      this.myDraftTotal = "-";
      this.errorText = first.label + "统计加载失败：" + first.error.text;
      if (failed.length > 1) {
        this.errorText += "（另有 " + (failed.length - 1) + " 项同样失败）";
      }
      this.errorCause = first.error.cause;
      this.fetchState = "error";
    },
    /** 统计值展示：满 999 收敛为 999+，没有值显示 - */
    statText(value) {
      if (value === null || value === undefined || value === "") return "-";
      return value >= 999 ? "999+" : value;
    },
    /** 待办 */
    goTodo() {
      this.$router.push("/my/todo");
    },
    /** 待阅 */
    goRead() {
      this.$router.push({ path: "/my/todo", query: { type: "2" } });
    },
    /** 我起草的 */
    goMyDraft() {
      this.$router.push({ path: "/my/apply" });
    },
  },
};
</script>

<style scoped lang="scss">
.sum-container {
  /* 原来是固定 height，装不下错误面板（会被首页 oa-home.scss 的 overflow:hidden 裁掉）：
     改成 min-height 后正常态高度不变，状态面板才能完整露出"重试"按钮 */
  min-height: calc(12vh - 10px);
  /* 原来是 row：现在里面是 StateBlock，改成列方向让面板占满整条；
     正常态的 3 张数字卡由内层 .sum-items 保持原来的横排 */
  display: flex;
  flex-direction: column;
  padding: 10px;
  border: 1px solid #f5f5f5;
  box-shadow: 0 2px 12px 0 rgb(0 0 0 / 10%);
  margin-bottom: 10px;
  border-radius: 4px;
}
.sum-items {
  display: flex;
}
.sum-item {
  width: 180px;
  padding: 24px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-right: 24px;
  text-align: center;
}
.todo-flag,
.toread-flag,
.add-flag {
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
.add-flag {
  margin-right: 12px;
  color: #e8f4e6;
}
.pending-approval {
  background: linear-gradient(90deg, #e8f4ff, #f1f7ff, #f7faff);
}
.pending-review {
  background: linear-gradient(90deg, #fff6ec, #fffcf7);
}
.my-apply {
  background: linear-gradient(90deg, #f0ffec, #fafff7);
}
.content {
  text-align: center;
}
.count {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}
.desc {
  margin-top: 3px;
  font-size: 12px;
  color: #909399;
}
</style>

