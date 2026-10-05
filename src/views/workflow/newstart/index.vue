<template>
  <!-- 新启页面 -->
  <div class="main">
    <div class="curr">
      <p>
        <span>最近使用</span>
      </p>
      <el-row class="tip-group" :gutter="10">
        <el-col :span="4" v-for="(item, index) in recentlyused" :key="index">
          <div class="tip" @click="goToTemplateDetail(item)" :title="item.name">
            <span>{{ item.name }}</span>
          </div>
        </el-col>
      </el-row>
      <el-empty v-if="emptyRecently" :image-size="50"></el-empty>
    </div>
    <div class="new">
      <p>
        <span>新启流程</span>
        <el-input size="medium" placeholder="请输入关键字搜索" v-model="searchInput" @input="searchFn">
          <i slot="suffix" @click="searchFn" class="el-input__icon el-icon-search"></i>
        </el-input>
      </p>
      <div v-for="(temp, index) in templateArr" :key="index">
        <div class="tem">
          <div>
            <i class="el-icon-caret-bottom"></i>
            <span>{{ groupTitle(temp) }} ( {{ temp.templates.length }} )</span>
            <!-- 2.0（B1 §3.5/§5.8）：无分类模板不再被丢弃，归入「未分类」组并显式标注 -->
            <el-tag v-if="!temp.type" size="mini" type="info" class="uncat-tag">未分类</el-tag>
          </div>
          <div class="line"></div>
        </div>
        <FlowTemplate :key="key">
          <el-row class="tip-group" :gutter="10">
            <el-col :span="4" v-for="(item, index) in temp.templates" :key="index">
              <div class="tip" @click="goToTemplateDetail(item)" :title="item.remark ? item.name + '：' + item.remark : item.name">
                <!-- 2.0（B1 §5.3）：卡片展示模板图标（图标来自预设集，空值兜底一个默认图标） -->
                <i :class="item.icon || 'el-icon-document'" class="tip-icon"></i>
                <div class="tip-text">
                  <span class="tip-name">{{ item.name }}</span>
                  <!-- 2.0（B1 §5.3）：「说明」作为卡片副标题（PRD 第 5 章） -->
                  <span v-if="item.remark" class="tip-sub">{{ item.remark }}</span>
                </div>
              </div>
            </el-col>
          </el-row>
        </FlowTemplate>
      </div>
      <!-- 2.0（B1 §5.8）：区分「没有模板」与「没有你可发起的模板」两种空态 -->
      <el-empty v-if="emptyTemplate" :image-size="50" :description="emptyTemplateText"></el-empty>
    </div>
  </div>
</template>

<script>
import FlowTemplate from "./flow-template.vue";
import { getNewStartTemplateList, listTemplate } from "@/api/workflow/template";
import { getReceTemplateList } from "@/api/workflow/receTemplate";

export default {
  components: {
    FlowTemplate,
  },
  created() {
    this.getRecentlyUsedList();
    this.getList();
  },
  data() {
    return {
      emptyRecently: true,
      emptyTemplate: true,
      /** 空态原因：none-库里没有启用的模板 / scope-范围过滤后为空 / search-搜索无结果 */
      emptyReason: "none",
      enabledTemplateCount: null,
      recentlyused: [], // 最近使用数据数组
      searchInput: "",
      templateArr: [], // 新启数组数据
      templateSearchArr: [], // 临时新启数组数据
      key: 0,
      showItem: "",
    };
  },
  computed: {
    /**
     * 两种空态文案要分开（B1 §5.8 完成判定）：
     * 「没有模板」是管理员没配；「没有你可发起的模板」是范围过滤把当前账号挡住了 ——
     * 后者用户自己折腾不出来，需要找管理员把自己加进可发起范围。
     */
    emptyTemplateText() {
      if (this.emptyReason === "search") {
        return "没有匹配的模板，换个关键字试试";
      }
      if (this.emptyReason === "scope") {
        return "没有你可发起的模板：当前账号不在任何模板的可发起范围内，请联系管理员在「模板配置 - 谁可以提交该审批」里添加";
      }
      return "还没有可用的单据模板：请管理员在「模板配置」中新建并启用模板";
    },
  },

  methods: {
    // 查询最近使用模板列表
    getRecentlyUsedList() {
      getReceTemplateList().then((res) => {
        if (res.code == 200 && res.data) {
          this.recentlyused = res.data;
          this.emptyRecently = res.data && res.data.length > 0 ? false : true;
        }
      });
    },
    // 查询模板列表
    getList() {
      getNewStartTemplateList().then((res) => {
        if (res.code == 200 && res.data) {
          this.templateArr = res.data;
          this.templateSearchArr = res.data;
          this.emptyTemplate = res.data && res.data.length > 0 ? false : true;
          if (this.emptyTemplate) {
            this.resolveEmptyReason();
          }
        }
      });
    },
    /**
     * 列表为空时，判断到底是"库里没有启用的模板"还是"范围把当前账号挡住了"。
     *
     * 为什么不在列表接口里加标志位：那会改动既有响应结构（别的调用方也在用）。
     * 这里复用 `/template/template/list` 只取 total —— 它按 enableFlag 过滤、
     * **不带可发起范围过滤**，所以 total > 0 就说明是范围问题。
     */
    resolveEmptyReason() {
      this.emptyReason = "none";
      listTemplate({ pageNum: 1, pageSize: 1, enableFlag: "1" })
        .then((res) => {
          this.enabledTemplateCount = Number(res.total || 0);
          this.emptyReason = this.enabledTemplateCount > 0 ? "scope" : "none";
        })
        .catch(() => {
          this.emptyReason = "none";
        });
    },
    /** 分组标题：空分类的组显示「未分类」（后端也会给 typeName，但空值要兜底） */
    groupTitle(temp) {
      if (!temp) return "未分类";
      if (!temp.type) return "未分类";
      return temp.typeName || temp.type;
    },
    // 查找模板
    searchFn() {
      this.templateArr = this.searchInput
        ? this.templateSearchArr
            .map((group) => ({
              ...group,
              templates: group.templates.filter((t) => t.name.includes(this.searchInput)),
            }))
            .filter((group) => group.templates.length > 0)
        : this.templateSearchArr;

      this.emptyTemplate = this.templateArr.length === 0;
      if (this.emptyTemplate) {
        // 搜索无结果 ≠ 真的没有模板：文案要分开
        this.emptyReason = this.searchInput ? "search" : this.emptyReason;
        if (!this.searchInput && this.enabledTemplateCount === null) {
          this.resolveEmptyReason();
        }
      }
      this.key++;
    },
    // 跳转模板表单
    async goToTemplateDetail(item) {
      this.$router.push({
        path: "/workflow/flowForm/" + new Date().getTime(),
        query: {
          pageType: "0",
          templateName: item.name,
          templateId: item.id,
        },
      });
    },
  },
};
</script>
 
<style rel="stylesheet/scss" lang = "scss" scoped>
.main {
  display: flex;
  flex-direction: column;
}

.curr,
.new {
  margin-top: 24px;
  padding: 13px 30px 20px 30px;
  background: #ffffff;
  box-shadow: 0px 22px 24px -20px rgba(238, 238, 243, 1);
  border-radius: 4px;
}
.new {
  flex: 1;
}

p {
  height: 25px;
  display: flex;
  justify-content: space-between;
  align-items: center;

  span {
    width: 80px;
    font-size: 16px;
    color: #262626;
    letter-spacing: 0;
    font-weight: 600;
  }

  ::v-deep .el-input__inner {
    width: 166px;
    &::placeholder {
      color: #bcbec3;
      font-size: 14px;
    }
  }

  .el-input {
    width: 166px;
  }
}
.tip-group {
  margin-top: 17px;

  &.col {
    display: none;
  }
}
::v-deep .el-col:hover .tip {
  background: url("../../../assets/images/flow2.png") center no-repeat content-box;
  background-origin: border-box;
  background-size: cover;
  color: #ffffff;
  font-weight: 600;
  box-shadow: 0 4px 12px 0 rgba(71, 139, 239, 0.4);
  top: -5px;
}
.tip {
  height: 60px;
  background: url("../../../assets/images/flow1.png") center no-repeat;
  background-origin: border-box;
  background-size: cover;
  border: 1px solid rgba(244, 245, 247, 1);
  border-radius: 5px;
  color: #636363;
  font-weight: 600;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  margin-bottom: 10px;
  /* 2.0（B1 §5.3）：卡片图标 */
  .tip-icon {
    margin-right: 6px;
    font-size: 16px;
    flex: 0 0 auto;
  }
  .tip-text {
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .tip-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* 「说明」作为卡片副标题（PRD 第 5 章：发起页卡片副标题展示） */
  .tip-sub {
    font-size: 12px;
    font-weight: 400;
    color: #909399;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    padding: 0 !important;
  }
  span {
    padding: 0 20px 0 20px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  transition: all 0.3s;
  position: relative;
  box-shadow: none;
}

.uncat-tag {
  margin-left: 8px;
}

.tem {
  display: flex;
  font-weight: 600;
  font-size: 14px;
  align-items: center;
  color: #38699e;
  padding: 0 0 10px 0;
  span {
    padding-left: 10px;
    padding-right: 20px;
  }

  & span,
  i {
    cursor: pointer;
    user-select: none;
    color: #1c84c6;
  }

  .line {
    height: 1px;
    flex: 1;
    background-color: #f4f5f7;
  }
}

::v-deep .el-empty {
  padding: 0;
}
::v-deep .el-empty__description {
  margin-top: 10px;
  font-size: 12px;
}
</style>