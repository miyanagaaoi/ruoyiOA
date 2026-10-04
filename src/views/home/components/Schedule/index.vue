<template>
  <div class="msg-container">
    <div class="position-title">
      <span>
        <svg-icon icon-class="msg" class="msg-flag" />日程
      </span>
      <span class="load-more" @click="loadMore">
        更多
        <i class="el-icon-arrow-right" />
      </span>
    </div>
    <div class="msg-body">
      <div class="custom-calendar">
        <!-- 日历头部 -->
        <div class="calendar-header">
          <div class="left-section">
            <span class="current-date">{{ formatDate(currentDate) }}</span>
          </div>
          <div class="right-section">
            <el-button size="mini" @click="prevWeek">❮</el-button>
            <el-button size="mini" @click="goToToday">今天</el-button>
            <el-button size="mini" @click="nextWeek">❯</el-button>
          </div>
        </div>

        <!-- 日历主体 -->
        <div class="calendar-body">
          <table>
            <thead>
              <tr>
                <th v-for="day in weekDays" :key="day">{{ day }}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td v-for="(cell, index) in currentWeek" :key="index" :class="{ 'selected': isSelected(cell) }" @click="selectDate(cell)">
                  <span :class="{ 'today-bg': isToday(cell) }">{{ cell.getDate() }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 日程数据展示：只有这块随状态切换，日历头部/主体常驻 -->
        <div class="schedule-data" :class="{ 'is-state': fetchState !== 'ready' }" ref="scheduleData">
          <StateBlock
            :state="fetchState"
            :compact="true"
            loading-text="正在加载日程…"
            :error-text="errorText"
            :error-cause="errorCause"
            empty-title="当天没有日程"
            empty-desc="选中的这一天没有安排，换一天看看，或去日程管理里新建。"
            @retry="getScheduleData"
          >
            <div v-for="item in scheduleList" :key="item.time" @click="goDetail(item)" class="schedule-item">
              <span class="schedule-title">
                <span v-if="item.scheduleType && item.scheduleType.tagName" class="ml5">
                  <el-tag
                    size="mini"
                    :effect="item.scheduleType.tagEffect || 'plain'"
                    :type="item.scheduleType.tagType || ''"
                  >{{ item.scheduleType.tagName }}</el-tag>
                </span>
                <el-tooltip v-if="item.title.length >= 16" class="item" effect="dark" :content="item.title" placement="top">
                  <span class="ml5">{{ item.title }}</span>
                </el-tooltip>
                <span v-else class="ml5">{{ item.title }}</span>
              </span>
              <span class="schedule-date">{{ parseTime(item.startTime, '{h}:{i}') }} - {{ parseTime(item.endTime, '{h}:{i}') }}</span>
            </div>
            <!-- 空态必须有标题 + 说明 + 一个主行动 -->
            <template slot="empty-action">
              <el-button type="primary" size="mini" @click="loadMore">打开日程管理</el-button>
            </template>
          </StateBlock>
        </div>
      </div>
    </div>
    <dialog-detail :open="open" :id="scheduleId" :dateDesc="dateDesc" @close="close" />
  </div>
</template>

<script>
import DialogDetail from "@/views/schedule/components/schedule/detail";
import { listDay } from "@/api/schedule/schedule";
import { parseTime } from "@/utils/ruoyi";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";

export default {
  name: "MessageModule",
  components: {
    DialogDetail,
    StateBlock,
  },
  data() {
    return {
      // 取数状态：loading | error | empty | ready（交给 StateBlock 渲染，错误不降级成空态）
      fetchState: "loading",
      // 错误三问：发生了什么 / 为什么（"怎么办"由 StateBlock 的重试按钮承担）
      errorText: "",
      errorCause: "",
      // 当前时间
      currentDate: new Date(),
      // 选中日期
      selectedDate: new Date(),
      // 当前周
      currentWeek: [],
      // 总数
      total: 0,
      // 日程数据
      scheduleList: [],
      // 日程请求参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
      },
      // 日程详情弹框
      open: false,
      // 日程id
      scheduleId: null,
      // 日期描述
      dateDesc: null,
      weekDays: ["一", "二", "三", "四", "五", "六", "日"],
      weekNames: ["周一", "周二", "周三", "周四", "周五", "周六", "周日"],
      monthNames: ["1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"],
    };
  },
  created() {
    this.handleCurrentWeek(new Date());
    this.getScheduleData();
  },
  methods: {
    // 获取当天日程
    async getScheduleData() {
      // 取数开始：先清掉上一轮结果，接口出错时不会出现"旧日程 + 错误提示"同屏
      this.fetchState = "loading";
      this.errorText = "";
      this.errorCause = "";
      this.total = 0;
      this.scheduleList = [];
      const startTime = parseTime(this.currentDate, "{y}-{m}-{d} {h}:{i}:{s}");
      this.queryParams.startTime = startTime;
      this.queryParams.endTime = startTime;
      try {
        const res = await listDay(this.queryParams);
        if (res && res.code == 200) {
          this.scheduleList = res.rows || [];
          this.total = res.total;
          // 请求成功但当天确实没有日程 → empty；有日程 → ready
          this.fetchState = this.scheduleList.length > 0 ? "ready" : "empty";
        } else {
          this.applyError({ message: "接口返回了非 200 状态码（code=" + (res && res.code) + "）。" });
        }
      } catch (e) {
        this.applyError(e);
      }
    },
    /** 取数失败：清掉旧数据并进入错误态（文案来自 describeError） */
    applyError(err) {
      const d = describeError(err);
      this.scheduleList = [];
      this.total = 0;
      this.errorText = d.text;
      this.errorCause = d.cause;
      this.fetchState = "error";
    },
    /** 处理当前周 */
    handleCurrentWeek(date) {
      this.currentDate = date;
      const startOfWeek = this.getStartOfWeek(date);
      this.currentWeek = [];
      for (let i = 0; i < 7; i++) {
        const day = new Date(startOfWeek);
        day.setDate(startOfWeek.getDate() + i);
        this.currentWeek.push(day);
      }
    },
    /** 获取周的第一天 */
    getStartOfWeek(date) {
      const day = date.getDay();
      const diff = date.getDate() - (day === 0 ? 6 : day - 1);
      return new Date(date.getFullYear(), date.getMonth(), diff);
    },
    /** 前一周 */
    prevWeek() {
      const newDate = new Date(this.currentWeek[0]);
      newDate.setDate(newDate.getDate() - 7);
      this.handleCurrentWeek(newDate);
    },
    /** 下一周 */
    nextWeek() {
      const newDate = new Date(this.currentWeek[0]);
      newDate.setDate(newDate.getDate() + 7);
      this.handleCurrentWeek(newDate);
    },
    /** 今天 */
    goToToday() {
      const today = new Date();
      this.selectedDate = today;
      this.handleCurrentWeek(today);
      this.getScheduleData();
    },
    /** 是否为今天 */
    isToday(date) {
      const today = new Date();
      return date && date.getFullYear() === today.getFullYear() && date.getMonth() === today.getMonth() && date.getDate() === today.getDate();
    },
    /** 是否选中 */
    isSelected(date) {
      if (this.isToday(date)) return false;
      return (
        date &&
        date.getFullYear() === this.selectedDate.getFullYear() &&
        date.getMonth() === this.selectedDate.getMonth() &&
        date.getDate() === this.selectedDate.getDate()
      );
    },
    /** 日期选择事件 */
    selectDate(date) {
      this.currentDate = date;
      this.selectedDate = date;
      this.getScheduleData();
    },
    /** 日期格式化 */
    formatDate(date) {
      const y = date.getFullYear();
      const m = (date.getMonth() + 1).toString().padStart(1, "0");
      const d = date.getDate().toString().padStart(2, "0");
      return `${y}年${m}月${d}日`;
    },
    /** 查看更多 */
    loadMore() {
      this.$router.push("/schedule/plan");
    },
    /** 查看详情 */
    goDetail(item) {
      this.open = true;
      this.scheduleId = item.id;
      let dt = this.selectedDate;
      let m = this.monthNames[dt.getMonth()] + dt.getDate() + "日";
      let w = "（" + (this.isToday(dt) ? "今天" : this.weekNames[dt.getDay() - 1 < 0 ? 6 : dt.getDay() - 1]) + "）";
      this.dateDesc = m + w;
    },
    close() {
      this.scheduleId = null;
      this.open = false;
    },
  },
};
</script>

<style scoped lang="scss">
.msg-container {
  /* 原来是固定 height：错误/空态面板会被外层 overflow:hidden 裁掉重试按钮，
     改成 min-height 后正常态高度不变，只有状态面板需要更多空间时卡片才长高 */
  min-height: calc(38vh - 10px);
  border-radius: 4px;
  border: 1px solid #f5f5f5;
  box-sizing: border-box;
  background-color: #ffffff;
  box-shadow: 0 2px 12px 0 rgb(0 0 0 / 10%);
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

.msg-flag {
  margin-right: 3px;
  width: 14px;
  height: 14px;
}

.msg-body {
  overflow: hidden;
}

.custom-calendar {
  margin: 0 auto;
  border-radius: 4px;
  background-color: #fff;

  .calendar-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 20px;

    .left-section {
      display: flex;
      align-items: center;
    }
    .right-section {
      margin-left: auto;
      display: flex;
    }
    .current-date {
      font-size: 14px;
    }
  }

  .calendar-body {
    padding: 0 10px;
    padding-bottom: 5px;
    font-size: 14px;
    border-bottom: 1px solid #f1f1f1;

    table {
      width: 100%;
      border-collapse: collapse;

      th,
      td {
        width: 14.28%;
        height: 30px;
        text-align: center;
        vertical-align: middle;
        font-size: 12px;
        cursor: pointer;

        &.today {
          background-color: var(--oa-color-primary);
          color: #fff;
        }

        span.today-bg {
          display: inline-block;
          width: 24px;
          height: 24px;
          line-height: 24px;
          border-radius: 50%;
          background-color: var(--oa-color-primary);
          color: white;
        }

        &.selected span {
          background-color: #d3e9ff;
          color: var(--oa-color-primary);
          display: inline-block;
          width: 24px;
          height: 24px;
          line-height: 24px;
          border-radius: 50%;
        }
      }
      th {
        color: #909399;
      }
      td {
        color: #606266;
      }
    }
  }

  .schedule-data {
    height: calc(36vh - 156px);
    overflow-y: auto;
    overflow-x: hidden;
    font-size: 14px;

    /* 非正常态（加载/错误/空）不锁高：锁高会把"重试"按钮压到滚动区下面 */
    &.is-state {
      height: auto;
      min-height: calc(36vh - 156px);
      overflow-y: visible;
    }

    .schedule-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 12px 12px 24px;
      cursor: pointer;
      transition: background-color 0.3s;

      &:hover {
        background-color: #f5f7fa;
      }
      .schedule-title {
        position: relative;
        padding-left: 5px;
        flex: 1;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        font-size: 14px;
        color: #333;
        line-height: 1.2;
        display: inline-block;

        &::before {
          content: "";
          position: absolute;
          left: 0;
          top: 50%;
          transform: translateY(-50%);
          width: 2px;
          height: 12px;
          background-color: var(--oa-color-primary);
          border-radius: 2px;
        }
      }

      .schedule-date {
        margin-left: 12px;
        font-size: 12px;
        color: #999;
        white-space: nowrap;
        margin-right: 5px;
      }
    }
  }
}

.load-more {
  font-size: 12px;
  color: #999;
  cursor: pointer;
  text-align: center;
}

/* 空态已统一交给 StateBlock，原来的 el-empty 样式一并撤掉，避免两套空态并存 */

::v-deep .el-button--mini {
  padding: 5px 8px;
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

