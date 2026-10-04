<template>
  <!-- 首页 · 工作台（视觉语言取自集团 OA 设计体系，见 oa-home.scss） -->
  <div class="oa-home">
    <header class="oa-page-head">
      <h1>工作台</h1>
      <p class="oa-page-sub">{{ todayText }}</p>
    </header>

    <div class="oa-home-grid">
      <!-- 主区：统计 → 待办 → (快捷入口 + 资讯) -->
      <section class="oa-col-main">
        <static ref="static" />
        <todo ref="todo" />
        <div class="oa-row-2">
          <fast-entrance @reflesh="reflesh" />
          <news />
        </div>
      </section>

      <!-- 侧栏：欢迎 → 公告 → 日程 -->
      <aside class="oa-col-side">
        <welcome />
        <notice />
        <schedule ref="schedule" />
      </aside>
    </div>
  </div>
</template>

<script>
import Todo from "./components/Todo/Collapse.vue";
import FastEntrance from "./components/FastEntrance";
import Welcome from "./components/Welcome";
import Notice from "./components/Notice";
import News from "./components/News";
import Static from "./components/Static";
import Schedule from "./components/Schedule";
import eventType from "@/utils/socket/eventType.js";
// 首页主题：统一接管 8 个子组件的卡片面与分区标题（全局样式 + .oa-home 作用域）
import "./oa-home.scss";

export default {
  name: "HomeIndex",
  components: { Todo, Notice, News, Welcome, FastEntrance, Static, Schedule },
  data() {
    return {};
  },
  computed: {
    /** 页头副标题：今天是 XXXX 年 X 月 X 日 星期X */
    todayText() {
      const d = new Date();
      const week = ["日", "一", "二", "三", "四", "五", "六"][d.getDay()];
      return `今天是 ${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 星期${week}`;
    },
  },
  mounted() {
    this.$eventBus.$on(eventType[1], (payload) => {
      console.log("收到消息:", payload.text);
      this.refleshTodo();
    });
  },
  methods: {
    reflesh() {
      this.$refs.schedule.getScheduleData();
    },
    refleshTodo() {
      this.$refs.static.initData();
      this.$refs.todo.getList();
    },
  },
};
</script>
