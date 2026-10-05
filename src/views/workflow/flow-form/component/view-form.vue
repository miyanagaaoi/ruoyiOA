<template>
  <el-row :gutter="24">
    <el-form ref="form" label-position="right" :label-width="labelWidth">
      <el-col v-for="item in formViewList" :key="item.key" :span="item.span">
        <!--
          纯排版控件（PRD 9.3 / AC-37）：不套「标签：值」的壳。
          它们没有值，套上去就会渲染成「分组标题： *」这种既没信息量又难看的行。
        -->
        <div v-if="item.tag === 'design-section'" class="vf-section">
          <span class="vf-section-bar"></span>
          <span class="vf-section-title">{{ item.sectionTitle }}</span>
          <p v-if="item.sectionDesc" class="vf-section-desc">{{ item.sectionDesc }}</p>
        </div>
        <div v-else-if="item.tag === 'design-text'" class="vf-text">{{ item.textContent }}</div>
        <el-form-item v-else :label="item.label" :prop="item.prop">
          <template slot="label">
            <span v-if="item.required" class="required">*</span>
            {{ item.label }}
          </template>
          <span v-if="item.tag === 'el-radio-group'">
            <el-radio-group v-model="item.value">
              <el-radio :label="r.value" v-for="r in item.radioList" :key="r.value" :disabled="item.value === r.value ? false : true">{{ r.label }}</el-radio>
            </el-radio-group>
          </span>
          <span v-else-if="item.tag === 'el-checkbox-group'" class="disabCheck">
            <el-checkbox-group v-model="item.value">
              <el-checkbox :label="c.value" v-for="c in item.checkList" :key="c.value" disabled>{{ c.label }}</el-checkbox>
            </el-checkbox-group>
          </span>
          <span v-else-if="item.tag === 'el-switch'">
            <el-switch v-model="item.value" disabled></el-switch>
          </span>
          <span v-else-if="item.tag === 'el-slider'">
            <el-slider v-model="item.value" disabled></el-slider>
          </span>
          <span v-else-if="item.tag === 'el-rate'">
            <el-rate v-model="item.value" disabled show-score text-color="#ff9900"></el-rate>
          </span>
          <span v-else-if="item.tag === 'el-color-picker'">
            <el-color-picker v-model="item.value" disabled></el-color-picker>
          </span>
          <span v-else-if="item.tag === 'design-user-select'">
            <div class="text-content" v-if="item.value && item.value.length >0">
              <span v-for="(line, index) in item.value" :key="line.userId" class="org-select">
                <!-- 展示头像后，会导致高度不统一，最终将下方的el元素挤到最后去 -->
                <!-- <el-avatar :size="24" :src="avatar(line.avatar)" /> -->
                <span class="ml2">{{ line.nickName }}</span>
                <span v-if="index < item.value.length-1">、</span>
              </span>
            </div>
          </span>
          <span v-else-if="item.tag === 'design-dept-select'">
            <div class="text-content" v-if="item.value && item.value.length >0">
              <span v-for="(line, index) in item.value" :key="line.id" class="org-select">
                <span class="ml2">{{ line.label }}</span>
                <span v-if="index < item.value.length-1">、</span>
              </span>
            </div>
          </span>
          <span v-else-if="item.tag === 'design-related-approval'">
            <!--
              关联审批（2.0 B1 §7.5）：只读展示"当时关联了哪几张单"。
              单据号取的是**落库快照**（related_business_no），不随后续改名变化；
              点开浮窗看详情 —— 越权校验在服务端（无查看权返回 403）。
            -->
            <span v-if="relatedItems(item).length" class="ra-links">
              <el-tag
                v-for="r in relatedItems(item)"
                :key="r.businessId"
                size="small"
                class="ra-link"
                @click="openRelated(r)"
              >
                {{ r.businessNo || r.businessId }}
              </el-tag>
            </span>
            <span v-else class="text-content ra-empty">未关联单据</span>
          </span>
          <span v-else-if="item.tag === 'tinymce'" class="tinymce-content" v-html="item.value"></span>
          <div v-else class="text-content">{{ item.value }}</div>
        </el-form-item>
      </el-col>
    </el-form>

    <!-- 关联单据浮窗：只读详情（表单数据 + 审批状态） -->
    <el-dialog
      :title="relatedDialog.title"
      :visible.sync="relatedDialog.visible"
      width="620px"
      append-to-body
    >
      <div v-loading="relatedDialog.loading">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="单据号">{{ relatedDialog.businessNo || relatedDialog.businessId }}</el-descriptions-item>
          <el-descriptions-item label="单据类型">{{ relatedDialog.templateName || "-" }}</el-descriptions-item>
          <el-descriptions-item label="审批状态">{{ relatedDialog.statusText || "-" }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="relatedDialog.fields" size="mini" border style="margin-top: 10px">
          <el-table-column prop="label" label="字段" width="150" />
          <el-table-column prop="value" label="值" show-overflow-tooltip />
        </el-table>
        <div v-if="relatedDialog.error" class="ra-error">
          <i class="el-icon-warning-outline" /> {{ relatedDialog.error }}
        </div>
      </div>
      <div slot="footer">
        <el-button size="mini" @click="relatedDialog.visible = false">关 闭</el-button>
      </div>
    </el-dialog>
  </el-row>
</template>

<script>
import { deepClone } from "@/utils/index";
import { StrUtil } from "@/utils/StrUtil";
import { amountWithUpper } from "@/utils/money";
import {
  getRelatedApprovalDetail,
  listRelatedApprovalByHost
} from "@/api/workflow/relatedApproval";
import { describeError } from "@/utils/errorMessage";

export default {
  name: "ViewForm",
  data() {
    return {
      // label宽度默认128
      labelWidth: "138px",
      // 表单数据集
      formViewList: [],
      baseApi: process.env.VUE_APP_BASE_API,
      /** 关联审批：宿主单据已落库的关联关系（按控件 __vModel__ 分组） */
      relatedMap: {},
      relatedDialog: {
        visible: false,
        loading: false,
        title: "关联单据",
        businessId: "",
        businessNo: "",
        templateName: "",
        statusText: "",
        fields: [],
        error: ""
      },
    };
  },
  props: {
    // 表单配置
    formConf: {
      type: Object,
      default: () => {},
    },
    // 表单值
    formData: {
      type: Object,
      default: () => {},
    },
    // 宿主单据ID（只读视图取"关联了哪些单"用；不传就不发请求，退化成展示原始值）
    businessId: {
      type: String,
      default: "",
    },
  },
  watch: {
    formConf: {
      handler(val) {
        if (val) {
          this.init();
        }
      },
      immediate: true,
    },
  },
  mounted() {
    this.init();
    this.loadRelatedMap();
  },
  methods: {
    /** 取宿主单据已落库的关联关系（按控件分组）；无 businessId 时不发请求 */
    loadRelatedMap() {
      if (!this.businessId) return;
      listRelatedApprovalByHost(this.businessId)
        .then((res) => {
          this.relatedMap = res.data || {};
        })
        .catch(() => {
          // 拉不到就退回展示原始值，不假装"没有关联"
          this.relatedMap = {};
        });
    },
    /** 某控件下已关联的单据清单（没有关系数据时退回展示原始值里的ID） */
    relatedItems(item) {
      const rows = this.relatedMap[item.vModel];
      if (rows && rows.length) return rows;
      const raw = item.value;
      if (!raw) return [];
      const ids = Array.isArray(raw) ? raw : String(raw).split(",");
      return ids
        .map((id) => String(id).trim())
        .filter((id) => !!id)
        .map((id) => ({ businessId: id, businessNo: "" }));
    },
    /** 打开浮窗（服务端会再判一次查看权，无权限时给出明确提示而不是空弹窗） */
    openRelated(row) {
      if (!row || !row.businessId) return;
      this.relatedDialog = {
        visible: true,
        loading: true,
        title: "关联单据详情",
        businessId: row.businessId,
        businessNo: row.businessNo || "",
        templateName: "",
        statusText: "",
        fields: [],
        error: ""
      };
      getRelatedApprovalDetail(row.businessId)
        .then((res) => {
          const data = res.data || {};
          this.relatedDialog.businessNo = data.businessNo || row.businessNo || row.businessId;
          this.relatedDialog.fields = data.fields || [];
          const approval = data.approval || {};
          this.relatedDialog.templateName = approval.templateName || "";
          this.relatedDialog.statusText = approval.statusText || "";
        })
        .catch((err) => {
          this.relatedDialog.error = describeError(err).text;
        })
        .finally(() => {
          this.relatedDialog.loading = false;
        });
    },
    /** 初始化 */
    init() {
      // 深拷贝
      const formConfCopy = deepClone(this.formConf);
      // 固定138px，如有需要，可使用下面这行代码
      // this.labelWidth = formConfCopy.labelWidth + "px";

      const fields = this.collectFields(formConfCopy.fields);
      if (!fields || fields.length === 0) return;
      const formDataCopy = deepClone(this.formData);
      let fieldList = [];
      fields.map((item) => {
        const _config = item.__config__;
        let field = {
          prop: item.__vModel__,
          // 关联审批控件用它去 relatedMap 里取"已落库的关联关系"（§7.5）
          vModel: item.__vModel__,
          label: _config.label + "：",
          tag: _config.tag,
          key: _config.renderKey,
          span: _config.span || 24,
          value: this.getValue(item, formDataCopy),
          required: _config.required || false,
          radioList: this.getCheckGroup(item),
          checkList: this.getCheckGroup(item),
          // 纯排版控件的内容（分组标题 / 说明文字）
          sectionTitle: item.title,
          sectionDesc: item.desc,
          textContent: item.content,
        };
        fieldList.push(field);
      });
      this.formViewList = fieldList;
    },
    /**
     * 摊平字段清单：行容器（rowFormItem）里的子控件也要显示。
     *
     * ⚠ 原实现只看顶层 fields —— 用行容器排版过的表单，在审批/详情页会**整块消失**
     * （拟稿页由 Parser 渲染是正常的，只有这个只读视图漏了）。
     */
    collectFields(list) {
      const out = [];
      const walk = (arr) => {
        (arr || []).forEach((f) => {
          const cfg = f.__config__ || {};
          if (Array.isArray(cfg.children) && cfg.children.length) {
            walk(cfg.children);
            return;
          }
          // 按钮这类纯交互控件在只读视图里没有意义
          if (["el-button", "el-divider"].indexOf(cfg.tag) >= 0) return;
          out.push(f);
        });
      };
      walk(list);
      return out;
    },
    /** 获取值 */
    getValue(conf, formDataCopy) {
      let value;
      const _config = conf.__config__;
      const prop = conf.__vModel__;
      switch (_config.tag) {
        case "el-select": // 下拉选项
          const slot = conf.__slot__;
          const selectVal = StrUtil.isBlank(_config.defaultValue) ? formDataCopy[prop] : _config.defaultValue;
          if (slot && slot.options) {
            slot.options.map((o) => {
              if (o.value == selectVal || o.label == selectVal) {
                value = o.label;
              }
            });
          }
          break;
        case "el-cascader": // 级联选择
          const separator = StrUtil.isBlank(conf.separator) ? " / " : " " + conf.separator + " ";
          const cascaderVal = _config.defaultValue && _config.defaultValue.length ? _config.defaultValue : formDataCopy[prop];
          const options = conf.options;
          value = this.getCascadeLabel(cascaderVal, options, separator);
          break;
        case "el-date-picker": // 日期选择
          value = this.getDatePickerValue(conf, formDataCopy, prop);
          break;
        case "el-time-picker": // 时间选择
          value = this.getTimePickerValue(conf, formDataCopy, prop);
          break;
        // 金额（PRD 9.3 / AC-36）：只读视图也要带千分位与中文大写，
        // 否则同在审批页，拟稿时看到的「1,234.56（人民币…）」到这里变成「1234.56」。
        case "design-amount": {
          const raw = formDataCopy[prop] !== undefined && formDataCopy[prop] !== null && formDataCopy[prop] !== ""
            ? formDataCopy[prop]
            : _config.defaultValue;
          const decimals = conf.decimals !== undefined && conf.decimals !== null ? Number(conf.decimals) : 2;
          value = amountWithUpper(raw, isFinite(decimals) ? decimals : 2);
          break;
        }
        // 只读计算（AC-38）：值就是算好的结果，补上单位（如「天」）
        case "design-calc": {
          const calcVal = formDataCopy[prop] !== undefined && formDataCopy[prop] !== null
            ? formDataCopy[prop]
            : _config.defaultValue;
          const unit = conf.formula === "dateDiff" && conf.unit ? " " + conf.unit : "";
          value = calcVal === undefined || calcVal === null || calcVal === "" ? "" : String(calcVal) + unit;
          break;
        }
        // case "design-user-select":
        //   value = this.getUserSelectValue(conf, formDataCopy, prop);
        //   break;
        default:
          value = _config.defaultValue ? _config.defaultValue : formDataCopy[prop];
      }
      return value;
    },
    /**
     * 级联标签路径生成方法
     * @param {Array} ids 选中的ID路径，如 [1, 2]
     * @param {Array} options 级联数据源
     * @returns {String} 拼接后的中文路径
     */
    getCascadeLabel(ids, options, separator) {
      const labels = [];
      let currentLevel = options || [];
      for (const id of ids) {
        const foundItem = currentLevel.find((item) => item.id === id);
        if (!foundItem) break;
        labels.push(foundItem.label);
        currentLevel = foundItem.children || [];
      }
      return labels.join(separator);
    },
    /** 获取选择项（单选、多选） */
    getCheckGroup(conf) {
      const slot = conf.__slot__;
      return slot && slot.options ? slot.options : [];
    },
    /** 日期选择值 */
    getDatePickerValue(conf, formDataCopy, prop) {
      const _config = conf.__config__;
      if (conf.type && conf.type === "daterange") {
        const dateScopeList = _config.defaultValue && _config.defaultValue.length ? _config.defaultValue : formDataCopy[prop];
        return dateScopeList[0] + " 至 " + dateScopeList[1];
      }
      return StrUtil.isBlank(_config.defaultValue) ? formDataCopy[prop] : _config.defaultValue;
    },
    /** 获取时间选择值 */
    getTimePickerValue(conf, formDataCopy, prop) {
      const _config = conf.__config__;
      if (conf["is-range"]) {
        const timeScopeList = _config.defaultValue && _config.defaultValue.length ? _config.defaultValue : formDataCopy[prop];
        return timeScopeList[0] + " 至 " + timeScopeList[1];
      }
      return StrUtil.isBlank(_config.defaultValue) ? formDataCopy[prop] : _config.defaultValue;
    },
    /** 获取选人组件值 */
    getUserSelectValue(conf, formDataCopy, prop) {
      const dataArr = formDataCopy[prop]; // 获取表单中的值
      if (Array.isArray(dataArr) && dataArr.length > 0) {
        return dataArr.map((user) => user.nickName || user.name || "未知用户").join("、");
      }
      return "";
    },
    /** 头像处理 */
    avatar(val) {
      if (StrUtil.isBlank(val)) return;
      return this.baseApi + val;
    },
  },
};
</script>

<style scoped type="scss">
::v-deep .el-form-item__content {
  font-size: 14px;
  color: #3f3f3f;
}

.text-content {
  display: block;
  word-wrap: anywhere;
  hyphens: auto; /* 英文单词智能断字 */
  text-align: justify; /* 两端对齐优化 */
}

::v-deep .el-form-item__label {
  font-size: 14px;
  color: var(--oa-color-primary);
  font-weight: 500;
}

.required {
  font-size: 15px;
  color: red;
}

/* 分组标题 / 说明文字（只读视图，与 Parser 里的 DesignSection/DesignText 保持同一观感） */
.vf-section {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin: 6px 0 2px;
}
.vf-section-bar {
  display: inline-block;
  width: 3px;
  height: 14px;
  margin-right: 6px;
  border-radius: 2px;
  background: var(--oa-color-primary);
}
.vf-section-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--oa-color-ink, #303133);
}
.vf-section-desc {
  width: 100%;
  margin: 2px 0 0 9px;
  font-size: 12px;
  color: var(--oa-color-ink-subtle, #909399);
  word-break: break-word;
  overflow-wrap: anywhere;
}
.vf-text {
  padding: 2px 0;
  font-size: 13px;
  color: var(--oa-color-ink-muted, #606266);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.org-select {
  display: inline-flex;
  align-items: center;
  margin-right: 3px;
  /* background: #e9f2ff; */
  position: relative;
}

/* 多选框样式调整 */
.disabCheck >>> .el-checkbox__input.is-disabled.is-checked .el-checkbox__inner::after {
  border-color: #fff;
}
.disabCheck >>> .el-checkbox__input.is-disabled.is-checked .el-checkbox__inner {
  background-color: var(--oa-color-primary);
  border-color: var(--oa-color-primary);
}
.disabCheck >>> .el-checkbox__input.is-disabled + span.el-checkbox__label {
  color: #606266;
}
.disabCheck >>> .el-checkbox__label {
  font-size: 12px;
}
.ra-links {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}
.ra-link {
  cursor: pointer;
}
.ra-empty {
  color: #c0c4cc;
}
.ra-error {
  margin-top: 8px;
  color: #f56c6c;
  font-size: 12px;
}
.tinymce-content >>> p:first-child {
  margin-top: 0;
}
</style>