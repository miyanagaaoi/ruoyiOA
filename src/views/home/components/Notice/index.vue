<template>
  <!-- 公司公告 -->
  <div class="notice-container">
    <div class="position-title">
      <span>
        <svg-icon icon-class="notice" class="notice-flag" />公告
      </span>
      <span class="load-more" @click="loadMore">
        更多
        <i class="el-icon-arrow-right" />
      </span>
    </div>
    <!-- 数据区：只有 .notice-body 内部随状态切换，标题栏与"更多"常驻 -->
    <div class="notice-body">
      <StateBlock
        :state="fetchState"
        :compact="true"
        loading-text="正在加载公告…"
        :error-text="errorText"
        :error-cause="errorCause"
        empty-title="暂无公告"
        empty-desc="当前没有已发布的公司公告，发布后会出现在这里。"
        @retry="getList"
      >
        <div v-for="item in noticeList" :key="item.noticeId" @click="goDetail(item)" class="notice-item" :class="item.readFlag == '0' ? 'no-read' : ''">
          <span class="notice-title">
            <span class="notice-type">{{ noticeTypeDesc(item.noticeType) }}</span>
            <el-tooltip v-if="item.noticeTitle.length >= 16" class="item" effect="dark" :content="item.noticeTitle" placement="top">
              <span>{{ item.noticeTitle }}</span>
            </el-tooltip>
            <span v-else>{{ item.noticeTitle }}</span>
          </span>
          <span class="notice-date">{{ parseTime(item.validStartTime, '{y}-{m}-{d}') }}</span>
        </div>
        <!-- 空态必须有标题 + 说明 + 一个主行动 -->
        <template slot="empty-action">
          <el-button type="primary" size="mini" @click="loadMore">查看全部公告</el-button>
        </template>
      </StateBlock>
    </div>
  </div>
</template>

<script>
import { listHomeNotice, readNotice } from "@/api/system/notice";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";

export default {
  name: "NoticeModule",
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
      // 公告数据
      noticeList: [],
      // 默认查20条作为总数据，动态计算实际显示多少
      allNoticeList: [],
      // 总条数
      total: 0,
      // 公告查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 20,
        status: "0",
        noticeType: 2,
      },
      visibleCount: 10,
    };
  },
  computed: {
    noticeTypeDesc() {
      return (val) => {
        if (!val) return "";
        let desc = "";
        switch (val) {
          case "0":
            desc = "【公司】";
            break;
          case "1":
            desc = "【部门】";
            break;
        }
        return desc;
      };
    },
  },
  mounted() {
    /** 动态计算显示数量，需要调整参数：queryParams.pageSize改为20，visibleCount调整为10或者更大即可 */
    this._resizeHandler = this.debounce(() => {
      this.handleResize();
    }, 300);
    window.addEventListener("resize", this._resizeHandler);
    this.getList();
  },
  beforeDestroy() {
    window.removeEventListener("resize", this._resizeHandler);
  },
  methods: {
    /** 获取数据 */
    async getList() {
      // 取数开始：先清掉上一轮结果，接口出错时不会出现"旧公告 + 错误提示"同屏
      this.fetchState = "loading";
      this.errorText = "";
      this.errorCause = "";
      this.noticeList = [];
      this.allNoticeList = [];
      this.total = 0;
      try {
        const response = await listHomeNotice(this.queryParams);
        if (response && response.code === 200) {
          this.allNoticeList = response.rows || [];
          this.total = response.total || 0;
          this.noticeList = this.allNoticeList.slice(0, this.visibleCount);
          // 请求成功但确实没有公告 → empty；有公告 → ready
          this.fetchState = this.allNoticeList.length > 0 ? "ready" : "empty";
          this.$nextTick(() => {
            this.handleResize();
          });
        } else {
          this.applyError({ message: "接口返回了非 200 状态码（code=" + (response && response.code) + "）。" });
        }
      } catch (e) {
        this.applyError(e);
      }
    },
    /** 取数失败：清掉旧数据并进入错误态（文案来自 describeError） */
    applyError(err) {
      const d = describeError(err);
      this.noticeList = [];
      this.allNoticeList = [];
      this.total = 0;
      this.errorText = d.text;
      this.errorCause = d.cause;
      this.fetchState = "error";
    },
    debounce(func, delay) {
      let timer;
      return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => func.apply(this, args), delay);
      };
    },
    /** 根据屏幕大小计算显示数量 */
    handleResize() {
      const container = document.querySelector(".notice-body");
      const item = document.querySelector(".notice-item");
      if (container && item) {
        const containerHeight = container.clientHeight;
        const itemHeight = item.offsetHeight;
        this.visibleCount = Math.floor(containerHeight / itemHeight);
        this.queryParams.pageSize = this.visibleCount;
        this.noticeList = this.allNoticeList.slice(0, this.visibleCount);
      } else {
        this.visibleCount = 5;
        this.queryParams.pageSize = 5;
        this.noticeList = this.allNoticeList.slice(0, 5);
      }
    },
    /** 查看详情 */
    goDetail(row) {
      // 设置为已读：详情已经打开了，标记失败只记日志，不回滚点击（否则会吞掉一次未处理的 Promise 拒绝）
      readNotice(row.noticeId).catch((e) => {
        console.warn("[公告] 标记已读失败", e);
      });
      row.readFlag = "1";
      let params = { id: row.noticeId };
      this.$tab.openPage(row.noticeTitle, "/news/notice/n/detail/:t" + new Date().getTime(), params);
    },
    /** 查看更多 */
    loadMore() {
      this.$router.push("/news/notice/n/list");
    },
  },
};
</script>

<style scoped lang="scss">
.notice-container {
  flex-direction: column;
  display: flex;
  height: calc(36vh - 10px);
  border-radius: 4px;
  border: 1px solid #f5f5f5;
  box-sizing: border-box;
  background-color: #ffffff;
  box-shadow: 0 2px 12px 0 rgb(0 0 0 / 10%);
  margin-bottom: 10px;
}
.position-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  font-weight: bold;
  border-bottom: 1px solid #f1f1f1;
  padding: 12px;
}

.load-more {
  font-size: 12px;
  color: #999;
  cursor: pointer;
  transition: color 0.3s;
  font-weight: 500;
  &:hover {
    color: var(--oa-color-primary);
  }
}

.notice-flag {
  margin-right: 3px;
  width: 15px;
  height: 15px;
}
.notice-body {
  overflow: hidden;
  /* 这里原来有一层无条件的 white-space: nowrap。它是多余的：真正需要单行省略号的
     .notice-title 和 .notice-date 各自都声明了 nowrap。留着它会把状态面板的说明文字
     也压成一行，再被 overflow: hidden 整行裁掉——用户就只看到标题、看不到错误原因。
     单行省略号改由 .notice-title / .notice-date 自己保证，正常态渲染不变。 */
  transition: all 0.3s;
  background-color: #ffffff;
}

/* 第二道保险：万一以后有人把 nowrap 加回 .notice-body，也不让状态面板被裁 */
.notice-body {
  ::v-deep .state-pane {
    white-space: normal;
  }
}

.notice-body:hover {
  white-space: normal;
  overflow: auto;
}

.notice-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  cursor: pointer;
  transition: background-color 0.3s;

  &:hover {
    background-color: #f5f7fa;
  }
  .notice-type {
    color: #1c84c6;
  }
  .notice-title {
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 14px;
    color: #333;
  }

  .notice-date {
    margin-left: 12px;
    font-size: 12px;
    color: #999;
    white-space: nowrap;
    margin-right: 5px;
  }
}
.no-read {
  font-weight: bold;
  position: relative;
  padding-right: 12px;

  &::after {
    content: "";
    position: absolute;
    top: 50%;
    right: 8px;
    transform: translateY(-50%);
    width: 5px;
    height: 5px;
    background-color: #ff4d4f;
    border-radius: 50%;
    box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.3);
  }
}
</style>

