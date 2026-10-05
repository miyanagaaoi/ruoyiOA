<template>
  <div class="app-container">
    <!-- OA 2.0 · B1 §5.1（REQ-FORM-003 / AC-45）：模板配置改为**四个页签** -->
    <el-tabs v-model="activeTab" type="border-card">
      <el-tab-pane label="基础信息" name="basic">
        <BasicInfo ref="basicInfoForm" :info="baseInfo" />
      </el-tab-pane>

      <el-tab-pane label="表单设计" name="form">
        <BusinessFormInfo ref="businessForm" :info="formInfo" :formType="formInfo.formType" :dynamicForm="dynamicForm" />
      </el-tab-pane>

      <el-tab-pane label="流程设计" name="flow">
        <!--
          B1 §6.2/§6.3/§6.4：把设计器**内嵌**在本页签内（不再跳独立路由），
          字段来源固定取模板的 formId；进入即按模板取/建草稿（幂等）。
        -->
        <div class="flow-tab">
          <el-alert
            v-if="!baseInfo.id"
            type="warning"
            :closable="false"
            show-icon
            title="请先保存模板"
            description="模板尚未保存（没有 id）。先到「基础信息」页签填写并保存 —— 流程标识由系统按「tpl_ + 模板ID前 8 位」派生，必须先有模板 id。"
          />
          <template v-else>
            <!-- §6.4 三种状态显式区分：模板已保存 / 流程未发布 / 发布失败可重试 -->
            <el-alert
              v-if="flowState === 'publish-failed'"
              type="error"
              :closable="false"
              show-icon
              :title="'流程发布失败：' + flowStateMessage"
            >
              <div class="alert-body">
                本次发布已<span class="em">整体回滚</span>（引擎侧部署与模板绑定都没有变化），
                不会出现「界面说发布了、发起时找不到流程」。
                <el-button size="mini" type="primary" @click="retryPublish">重试发布</el-button>
              </div>
            </el-alert>
            <el-alert
              v-else-if="flowState === 'published'"
              type="success"
              :closable="false"
              show-icon
              title="流程已发布，并已自动绑定到本模板"
              :description="'流程标识 ' + (baseInfo.defKey || '') + '；发起审批时按这条流程流转，无需手工抄写标识。'"
            />
            <el-alert
              v-else
              type="info"
              :closable="false"
              show-icon
              title="流程仍是草稿（未发布）"
              :description="autoSaveTip"
            />
            <FlowDesigner
              ref="flowDesigner"
              embedded
              :template-id="baseInfo.id"
              :form-id="baseInfo.formId"
              @bound="onFlowBound"
              @published="onFlowPublished"
              @state-change="onFlowStateChange"
              @auto-saved="onAutoSaved"
            />
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane label="更多设置" name="more">
        <div class="sub-title"><i class="el-icon-document"></i> 正文配置</div>
        <MainText ref="mainText" :info="mainTextInfo" />

        <div class="sub-title"><i class="el-icon-paperclip"></i> 附件配置</div>
        <AttachmentInfo ref="attachmentInfoForm" :info="attachmentInfo" />

        <div class="sub-title"><i class="el-icon-bell"></i> 消息通知配置</div>
        <MessageNotice ref="messageNotice" :info="messageNoticeInfo" />

        <div class="sub-title"><i class="el-icon-s-check"></i> 提交与审批策略</div>
        <SubmitPolicy ref="submitPolicy" :info="policyInfo" />
      </el-tab-pane>
    </el-tabs>

    <el-form label-width="100px">
      <el-form-item style="text-align: center; margin-left: -100px; margin-top: 14px">
        <el-button type="primary" @click="submitForm">保存</el-button>
        <el-button @click="close()">返回</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script>
import BasicInfo from "./basic-info";
import BusinessFormInfo from "./business-form-info";
import AttachmentInfo from "./attachment-info";
import MainText from "./main-text";
import MessageNotice from "./message-notice.vue";
import SubmitPolicy from "./submit-policy.vue";
import FlowDesigner from "@/views/workflow/simple-flow/components/FlowDesigner";
import { addTemplate, getTemplate, updateTemplate } from "@/api/workflow/template";

export default {
  name: "addTemplate",
  components: {
    BasicInfo,
    BusinessFormInfo,
    AttachmentInfo,
    MainText,
    MessageNotice,
    SubmitPolicy,
    FlowDesigner,
  },
  data() {
    return {
      // 2.0（B1 §5.1）：只有这一个页签状态字段；原 activeNames/activeName 两个死字段已删除
      activeTab: "basic",
      // 基本信息（含 2.0 新增的图标/说明/可发起范围/流程管理员）
      baseInfo: { submitScopeType: "0", includeChildDept: "1", submitScope: [], flowAdmins: [] },
      // 表单信息
      formInfo: {},
      // 附件信息
      attachmentInfo: { attachFlag: "1", limitSize: 500 },
      // 动态表单信息
      dynamicForm: {},
      // 正文信息
      mainTextInfo: { mainTextFlag: "0", mainTextWay: "0", limitSize: 200 },
      // 消息通知信息
      messageNoticeInfo: { messageNoticeFlag: "0", type: "1", msgTemplate: "" },
      // 提交与审批策略（默认值 = 升级前行为，见 submit-policy.vue）
      policyInfo: {},
      /**
       * 流程页签的状态机（B1 §6.4）：
       * draft-模板已保存但流程还是草稿 / published-已发布并绑定 / publish-failed-发布失败可重试
       */
      flowState: "draft",
      flowStateMessage: "",
      autoSaveText: "",
    };
  },
  computed: {
    autoSaveTip() {
      const base = "草稿会自动保存（30 秒一次 + 切走窗口时）；发布后模板才可被发起。";
      return this.autoSaveText ? base + "最近一次自动保存：" + this.autoSaveText : base;
    }
  },
  created() {
    if (this.$route.query && this.$route.query.id) {
      getTemplate(this.$route.query.id).then((res) => {
        if (res.code === 200 && res.data) {
          /**
           * ⚠️ 基础信息 / 表单设计 / 更多设置（策略组）必须**共用同一个 model 对象**
           * （与升级前的写法一致）。
           *
           * 保存时 payload = Object.assign({}, basicForm.model, businessForm.model)：
           * 如果这里各给一份浅拷贝，business 那份带着**接口回显的旧值**，
           * 会把基础信息页签上的编辑（名称/分组/图标/说明/可发起范围…）整片覆盖掉 ——
           * 实测踩过：图标选择器显示"单据"，保存后库里 icon 仍是 NULL。
           */
          const model = {
            ...res.data,
            submitScope: res.data.submitScope || [],
            flowAdmins: res.data.flowAdmins || []
          };
          this.baseInfo = model;
          this.formInfo = model;
          this.mainTextInfo = {
            ...res.data.mainText,
            mainTextFlag: res.data.mainTextFlag,
          };
          this.attachmentInfo = {
            ...res.data.attachment,
            attachFlag: res.data.attachFlag,
          };
          this.messageNoticeInfo = {
            ...res.data.messageNotice,
            messageNoticeFlag: res.data.messageNoticeFlag,
          };
          // 策略项同样落在模板主表的同一个 model 上
          this.policyInfo = model;
          this.dynamicForm = res.data.dynamicForm;
        }
      });
    }
  },
  methods: {
    /**
     * 保存（2.0 B1 §5.2）：**逐页签校验** —— 按页签顺序校验，遇到第一个失败的页签就
     * 切过去并定位/高亮首个错误字段。
     *
     * 升级前是一次性 Promise.all 校验 5 个表单：隐藏页签里的错误看不见，
     * 用户只看到"请完善必填字段"却找不到位置。
     */
    async submitForm() {
      const tabs = this.validatableTabs();
      for (const tab of tabs) {
        const forms = tab.forms().filter((f) => !!f);
        for (const form of forms) {
          const valid = await this.validateForm(form);
          if (!valid) {
            this.activeTab = tab.name;
            this.$nextTick(() => this.highlightFirstError(form));
            this.$modal.msgError(`「${tab.title}」还有未填写或不合法的内容，已为你切换过去`);
            return;
          }
        }
      }
      this.doSave();
    },
    /** 页签 → 需要校验的表单（顺序即校验顺序；流程设计页签没有 el-form，故略过） */
    validatableTabs() {
      return [
        {
          name: "basic",
          title: "基础信息",
          forms: () => [this.$refs.basicInfoForm && this.$refs.basicInfoForm.$refs.basicInfoForm]
        },
        {
          name: "form",
          title: "表单设计",
          forms: () => [this.$refs.businessForm && this.$refs.businessForm.$refs.businessForm]
        },
        {
          name: "more",
          title: "更多设置",
          forms: () => [
            this.$refs.mainText && this.$refs.mainText.$refs.mainInfoForm,
            this.$refs.attachmentInfoForm && this.$refs.attachmentInfoForm.$refs.attachmentInfoForm,
            this.$refs.messageNotice && this.$refs.messageNotice.$refs.messageNoticeInfoForm,
            this.$refs.submitPolicy && this.$refs.submitPolicy.$refs.submitPolicyForm
          ]
        }
      ];
    },
    /** 单个 el-form 的校验 → Promise<boolean> */
    validateForm(form) {
      return new Promise((resolve) => {
        form.validate((valid) => resolve(!!valid));
      });
    },
    /** 切到出错页签后，滚动并短暂高亮首个错误字段（Element UI 在隐藏容器里不会自动滚动） */
    highlightFirstError(form) {
      const root = form && form.$el;
      if (!root) return;
      const first = root.querySelector(".is-error");
      if (!first) return;
      try {
        first.scrollIntoView({ block: "center", behavior: "smooth" });
      } catch (e) {
        first.scrollIntoView();
      }
      first.classList.add("b1-field-error-flash");
      setTimeout(() => first.classList.remove("b1-field-error-flash"), 1600);
    },
    doSave() {
      const basicForm = this.$refs.basicInfoForm.$refs.basicInfoForm;
      const businessForm = this.$refs.businessForm.$refs.businessForm;
      const attachmentForm = this.$refs.attachmentInfoForm.$refs.attachmentInfoForm;
      const mainInfoForm = this.$refs.mainText.$refs.mainInfoForm;
      const messageNoticeForm = this.$refs.messageNotice.$refs.messageNoticeInfoForm;
      // 合并顺序：表单设计在前、基础信息在后。
      // 编辑态两者本来就是同一个对象（见 created 的注释）；新建态基础信息里是用户的输入，
      // 必须优先于表单页签的回显值。表单页签独有的键（formType/formCode/formId）在基础信息里
      // 不存在（新建时）或同值（编辑时），所以不会被覆盖掉。
      const templateTable = Object.assign({}, businessForm.model, basicForm.model);
      // 正文处理
      const mainTextFlag = mainInfoForm.model.mainTextFlag;
      templateTable.mainTextFlag = mainTextFlag;
      templateTable.mainText = mainTextFlag === "1" ? mainInfoForm.model : null;
      // 附件处理
      const attachFlag = attachmentForm.model.attachFlag;
      templateTable.attachFlag = attachFlag;
      templateTable.attachment = attachFlag === "1" ? attachmentForm.model : null;
      // 消息通知
      const messageNoticeFlag = messageNoticeForm.model.messageNoticeFlag;
      templateTable.messageNoticeFlag = messageNoticeFlag;
      templateTable.messageNotice = messageNoticeFlag === "1" ? messageNoticeForm.model : null;
      // 2.0：可提交范围明细、流程管理员（basic-info 已同步进 model）
      templateTable.submitScope = (basicForm.model.submitScope || []).slice();
      templateTable.flowAdmins = (basicForm.model.flowAdmins || []).slice();
      if (!templateTable.id) {
        addTemplate(templateTable).then((res) => {
          this.$modal.msgSuccess(res.msg);
          if (res.code === 200) {
            this.close();
          }
        });
      } else {
        updateTemplate(templateTable).then((res) => {
          this.$modal.msgSuccess(res.msg);
          if (res.code === 200) {
            this.close();
          }
        });
      }
    },
    // 关闭当前标签页并返回上个页面
    close() {
      const obj = {
        path: "/workflow/template/form/config",
        query: { t: Date.now() },
      };
      this.$tab.closeOpenPage(obj);
    },

    /* ============ 流程页签（B1 §6.3/§6.4）============ */

    /** 设计器取/建草稿后：把绑定结果同步进基础信息页签的只读展示 */
    onFlowBound(payload) {
      if (!payload) return;
      this.$set(this.baseInfo, "simpleFlowId", payload.flowId);
      if (payload.status === "1" && payload.defKey) {
        this.$set(this.baseInfo, "defKey", payload.defKey);
      }
      this.$set(this.baseInfo, "flowMode", "0");
      this.flowState = payload.status === "1" ? "published" : "draft";
    },
    /** 发布成功：模板三列已由后端同一事务回写，这里同步界面 */
    onFlowPublished(payload) {
      if (!payload) return;
      this.$set(this.baseInfo, "simpleFlowId", payload.flowId);
      this.$set(this.baseInfo, "defKey", payload.defKey);
      this.$set(this.baseInfo, "flowMode", "0");
      this.flowState = "published";
      this.flowStateMessage = "";
    },
    /** 状态变化（draft / published / publish-failed / load-failed） */
    onFlowStateChange(payload) {
      if (!payload) return;
      if (payload.state === "publish-failed") {
        this.flowState = "publish-failed";
        this.flowStateMessage = payload.message || "未知原因";
      } else if (payload.state === "draft" || payload.state === "published") {
        this.flowState = payload.state;
      }
    },
    onAutoSaved(payload) {
      this.autoSaveText = (payload && payload.at) || "";
    },
    /** 发布失败后的重试入口（§6.4 要求"可重试"而不是静默悬空） */
    retryPublish() {
      const designer = this.$refs.flowDesigner;
      if (designer && designer.handlePublish) {
        designer.handlePublish();
      }
    },
  },
};
</script>

<style scoped>
.sub-title {
  margin: 6px 0 12px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
  font-weight: 600;
  color: #303133;
}
.flow-tab {
  padding: 4px 0;
}
.flow-desc {
  margin-top: 14px;
}
.flow-tip {
  margin-top: 12px;
  color: #e6a23c;
}
.alert-body {
  margin-top: 6px;
  line-height: 1.9;
}
.alert-body .em {
  font-weight: 600;
  color: #f56c6c;
}
</style>

<style>
/* 校验失败时的短暂高亮（非 scoped：要作用到子组件渲染出的元素上） */
.b1-field-error-flash {
  animation: b1-flash 0.4s ease-in-out 3;
  border-radius: 4px;
}
@keyframes b1-flash {
  0%,
  100% {
    background-color: transparent;
  }
  50% {
    background-color: #fde2e2;
  }
}
</style>
