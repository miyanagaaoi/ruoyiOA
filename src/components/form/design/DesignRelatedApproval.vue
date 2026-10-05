<template>
  <!--
    关联审批控件（2.0 B1 §7，REQ-FORM-010 / AC-53）

    两态：
      · 可编辑（拟稿/办理）：远程搜索候选单据（服务端只返回"同分组 + 本人发起"的单），
        多选后把**业务ID数组**写进表单值；单据号只是展示。
      · 只读（审批页/详情页/打印前预览）：按宿主单据已落库的关联关系展示单据号快照
        （`displayItems` 由宿主传入；没传就退化成展示ID，绝不假装有单据号）。
  -->
  <div class="related-approval">
    <template v-if="readonly || disabled">
      <div v-if="items.length" class="ra-chips">
        <el-tag
          v-for="item in items"
          :key="item.businessId"
          size="small"
          class="ra-chip"
          :class="{ 'is-clickable': clickable }"
          @click="openDetail(item)"
        >
          {{ item.businessNo || item.businessId }}
        </el-tag>
      </div>
      <span v-else class="ra-placeholder">{{ emptyText }}</span>
    </template>

    <template v-else>
      <el-select
        v-model="selected"
        multiple
        filterable
        remote
        clearable
        reserve-keyword
        :remote-method="searchCandidates"
        :loading="loading"
        :placeholder="placeholder || '搜索并选择要关联的单据'"
        style="width: 100%"
        @change="onChange"
        @visible-change="onVisibleChange"
      >
        <el-option
          v-for="item in candidates"
          :key="item.businessId"
          :label="optionLabel(item)"
          :value="item.businessId"
        >
          <span class="ra-option-no">{{ item.businessNo || shortId(item.businessId) }}</span>
          <span class="ra-option-meta">{{ item.templateName }} · {{ statusText(item.status) }}</span>
        </el-option>
      </el-select>
      <div v-if="!resolvedTemplateId" class="ra-hint">
        <i class="el-icon-info" /> 设计态：候选单据在保存模板后按「候选模板 + 同分组 + 本人发起」实时过滤
      </div>
      <div v-else-if="loaded && !candidates.length" class="ra-hint is-warn">
        <i class="el-icon-warning-outline" /> 当前没有可选单据（候选模板里没有你发起过的单）
      </div>
    </template>
  </div>
</template>

<script>
import { listRelatedApprovalCandidates } from "@/api/workflow/relatedApproval";

export default {
  name: "DesignRelatedApproval",
  props: {
    /** 表单值：被关联单据的业务ID数组（也可能是逗号串，历史数据两种都见过） */
    value: {
      type: [Array, String],
      default: () => []
    },
    /** 控件上配置的候选模板（只用于展示与设计态提示；真正的过滤在服务端） */
    allowTemplates: {
      type: Array,
      default: () => []
    },
    /** 宿主模板ID（拟稿页由 $route.query.templateId 传入） */
    hostTemplateId: {
      type: String,
      default: ""
    },
    /** 控件 __vModel__（取关联关系用） */
    vModel: {
      type: String,
      default: ""
    },
    /** 宿主单据ID（只读态取关联关系用） */
    businessId: {
      type: String,
      default: ""
    },
    /** 只读态直接给单据号快照，避免再查一次 */
    displayItems: {
      type: Array,
      default: null
    },
    disabled: {
      type: Boolean,
      default: false
    },
    readonly: {
      type: Boolean,
      default: false
    },
    clickable: {
      type: Boolean,
      default: true
    },
    placeholder: {
      type: String,
      default: ""
    }
  },
  data() {
    return {
      selected: [],
      candidates: [],
      loading: false,
      loaded: false
    };
  },
  computed: {
    /** 只读展示项：优先用宿主给的快照 */
    items() {
      if (Array.isArray(this.displayItems)) {
        return this.displayItems;
      }
      const ids = this.normalizedValue();
      return ids.map((id) => ({ businessId: id, businessNo: "" }));
    },
    emptyText() {
      return this.readonly ? "未关联单据" : "—";
    },
    /**
     * 真正用于查询的模板ID：props 优先，其次路由（拟稿页把 templateId 放在 query 上）。
     * ⚠ 判断"是不是设计态"必须用它，不能只看 props —— 拟稿页的 templateId 只在路由上，
     * 只看 props 会把正常拟稿页误判成设计态、连候选都不去查（实测踩过）。
     */
    resolvedTemplateId() {
      return (
        this.hostTemplateId ||
        (this.$route && this.$route.query && this.$route.query.templateId) ||
        ""
      );
    }
  },
  watch: {
    value: {
      handler() {
        this.selected = this.normalizedValue();
      },
      immediate: true
    }
  },
  created() {
    // 说明：__vModel__ / templateId 这类上下文按需从 $attrs 与路由里取，
    // 不额外声明 props（渲染器会把字段配置的所有键作为 attrs 传下来）。
  },
  methods: {
    normalizedValue() {
      const v = this.value;
      if (!v) return [];
      if (Array.isArray(v)) return v.filter((x) => !!x);
      return String(v)
        .split(",")
        .map((x) => x.trim())
        .filter((x) => !!x);
    },
    /** 控件 __vModel__：优先用显式 prop，其次从 attrs 取（渲染器原样透传字段配置） */
    fieldVModel() {
      return this.vModel || (this.$attrs && this.$attrs["__vModel__"]) || "";
    },
    /** 真正用于查询的模板ID见计算属性 resolvedTemplateId（props 优先，其次路由） */
    optionLabel(item) {
      return (item.businessNo || this.shortId(item.businessId)) + "（" + item.templateName + "）";
    },
    shortId(id) {
      if (!id) return "";
      return id.length <= 8 ? id : "…" + id.slice(-8);
    },
    statusText(status) {
      return (
        { 0: "草稿", 1: "审批中", 2: "已通过", 3: "已驳回" }[status] || "未知"
      );
    },
    onVisibleChange(visible) {
      if (visible) {
        this.searchCandidates("");
      }
    },
    /** 远程搜索候选单据（服务端已按同分组+本人发起过滤，前端不做二次判断） */
    searchCandidates(keyword) {
      const templateId = this.resolvedTemplateId;
      const vModel = this.fieldVModel();
      if (!templateId || !vModel) {
        // 设计态/缺上下文：不发请求，也不伪造候选
        this.candidates = [];
        this.loaded = false;
        return;
      }
      this.loading = true;
      listRelatedApprovalCandidates({ templateId, fieldVmodel: vModel, keyword })
        .then((res) => {
          this.candidates = res.data || [];
          this.loaded = true;
        })
        .catch(() => {
          this.candidates = [];
          this.loaded = true;
        })
        .finally(() => {
          this.loading = false;
        });
    },
    onChange(val) {
      this.$emit("input", val);
    },
    /** 只读态点单据号 → 抛给宿主去开浮窗（浮窗的越权校验在服务端） */
    openDetail(item) {
      if (!this.clickable) return;
      this.$emit("open-related", item);
    }
  }
};
</script>

<style lang="scss" scoped>
.related-approval {
  width: 100%;

  .ra-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
  .ra-chip.is-clickable {
    cursor: pointer;
  }
  .ra-placeholder {
    color: #c0c4cc;
  }
  .ra-hint {
    margin-top: 4px;
    font-size: 12px;
    color: #909399;
    &.is-warn {
      color: #e6a23c;
    }
  }
  .ra-option-no {
    margin-right: 8px;
  }
  .ra-option-meta {
    color: #909399;
    font-size: 12px;
  }
}
</style>
