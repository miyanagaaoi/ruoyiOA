<template>
  <!-- 公司资讯 -->
  <div class="news-container">
    <div class="position-title">
      <span>
        <svg-icon icon-class="news" class="news-flag" />公司资讯
      </span>
      <span class="load-more" @click="loadMore">
        更多
        <i class="el-icon-arrow-right" />
      </span>
    </div>
    <!-- 数据区：只有 .news-body 内部随状态切换，标题栏与"更多"常驻 -->
    <div class="news-body">
      <StateBlock
        :state="fetchState"
        :compact="true"
        loading-text="正在加载资讯…"
        :error-text="errorText"
        :error-cause="errorCause"
        empty-title="暂无资讯"
        empty-desc="当前没有已发布的公司资讯，发布后会出现在这里。"
        @retry="getList"
      >
        <el-carousel :height="carouselH" :interval="4000" type="card">
          <el-carousel-item v-for="item in newsList" :key="item.id">
            <div class="news-card" @click="goDetail(item)" :style="{ backgroundImage: 'url(' + imgPath(item.imgUrl) + ')' }">
              <div class="news-title">{{ item.title }}</div>
              <div class="info">
                <span>
                  <i class="el-icon-view"></i>
                  {{ item.readTotal }}
                </span>
                <span>
                  <i class="el-icon-time"></i>
                  {{ item.validStartTime }}
                </span>
              </div>
            </div>
          </el-carousel-item>
        </el-carousel>
        <!-- 空态必须有标题 + 说明 + 一个主行动 -->
        <template slot="empty-action">
          <el-button type="primary" size="mini" @click="loadMore">查看全部资讯</el-button>
        </template>
      </StateBlock>
    </div>
  </div>
</template>

<script>
import { listPubInformation, addReadNum } from "@/api/information/information";
import StateBlock from "@/components/StateBlock";
// 错误文案统一由 @/utils/errorMessage 提供（request.js 会把 HTTP 错误的 message 改写成中文、
// 非 200 业务码可能 reject 出字符串，这些坑都在那个文件里处理了）
import { describeError } from "@/utils/errorMessage";

export default {
  name: "NewsModule",
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
      // 数据列表
      newsList: [],
      queryParams: {
        pageNum: 1,
        pageSize: 5,
      },
      // 高度自适应
      carouselH: "calc(24vh - 80px)",
      baseApi: process.env.VUE_APP_BASE_API,
    };
  },
  computed: {
    imgPath() {
      return (val) => {
        if (!val) return "";
        return this.baseApi + val;
      };
    },
  },
  mounted() {
    this.getList();
  },
  methods: {
    /** 获取数据 */
    async getList() {
      // 取数开始：先清掉上一轮结果，接口出错时不会出现"旧资讯 + 错误提示"同屏
      this.fetchState = "loading";
      this.errorText = "";
      this.errorCause = "";
      this.newsList = [];
      try {
        const res = await listPubInformation(this.queryParams);
        if (res && res.code === 200) {
          this.newsList = res.rows || [];
          // 请求成功但确实没有资讯 → empty；有资讯 → ready
          this.fetchState = this.newsList.length > 0 ? "ready" : "empty";
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
      this.newsList = [];
      this.errorText = d.text;
      this.errorCause = d.cause;
      this.fetchState = "error";
    },
    /** 查看详情 */
    goDetail(row) {
      let params = { id: row.id };
      this.$tab.openPage(row.title, "/news/information/i/detail/" + new Date().getTime(), params);
      // 阅读数 +1 只是副作用：失败不该拖住已经打开的详情，但也不能留一个未处理的 Promise 拒绝
      addReadNum(row.id)
        .then((res) => {
          if (res.code === 200) {
            row.readTotal += 1;
          }
        })
        .catch((e) => {
          console.warn("[资讯] 阅读数更新失败", e);
        });
    },
    /** 查看更多 */
    loadMore() {
      this.$router.push("/news/information/i/list");
    },
  },
};
</script>

<style scoped lang="scss">
.news-container {
  /* 原来是固定 height：卡片矮（24vh）时错误面板会被外层 overflow:hidden 裁掉，
     正常态下内容高度小于该值，所以用 min-height 视觉不变，只有状态面板会撑高 */
  min-height: calc(24vh - 10px);
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

.news-flag {
  margin-right: 4px;
  width: 14px;
  height: 14px;
}

.news-body {
  flex: 1;
  overflow: hidden;
  padding: 5px 5px 0 5px;
}

.news-card {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  border-radius: 4px;
  padding-left: 12px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: #fff;
  position: relative;
  cursor: pointer;

  .news-title {
    font-size: 16px;
    font-weight: bold;
    text-shadow: 0 2px 5px rgba(0, 0, 0, 0.9);
    max-width: 280px;
    z-index: 10;
  }

  .info {
    font-size: 12px;
    margin-top: 12px;
    text-shadow: 0 2px 4px rgba(0, 0, 0, 0.9);
    z-index: 10;

    span {
      margin-right: 10px;
    }
  }
}

/* 空态已统一交给 StateBlock，原来的 el-empty 样式一并撤掉，避免两套空态并存 */
</style>

