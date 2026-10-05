<template>
  <div class="app-container">
    <el-form ref="basicInfoForm" :model="info" :rules="rules" label-width="140px">
      <el-row>
        <el-col :span="12">
          <el-form-item label="模板名称" prop="name">
            <el-input placeholder="请输入模板名称" v-model="info.name" maxlength="200" show-word-limit />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="分组" prop="type">
            <el-select v-model="info.type" clearable filterable placeholder="请选择（来自既有模板分类）">
              <el-option
                v-for="templateType in typeOptions"
                :key="templateType.id"
                :label="templateType.name"
                :value="templateType.id"
              >
                <span style="float: left">{{ templateType.name }}</span>
              </el-option>
            </el-select>
          </el-form-item>
        </el-col>

        <!-- 2.0（B1 §5.3）：图标选择器（预设图标集，默认第一个） -->
        <el-col :span="12">
          <el-form-item label="图标" prop="icon">
            <el-select v-model="info.icon" filterable placeholder="从预设图标集中选择" style="width: 100%">
              <el-option v-for="item in iconOptions" :key="item.value" :label="item.label" :value="item.value">
                <i :class="item.value" style="margin-right: 6px"></i>
                <span>{{ item.label }}</span>
                <span style="float: right; color: #8492a6; font-size: 12px">{{ item.value }}</span>
              </el-option>
              <template slot="prefix">
                <i :class="info.icon" v-if="info.icon"></i>
              </template>
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="排序号" prop="sort">
            <el-input placeholder="请输入数字，值越小越靠前" v-model="info.sort" type="number" style="width: 240px;" />
          </el-form-item>
        </el-col>

        <!-- 2.0（B1 §5.3）：说明（≤500 字；复用 remark 列，不新增列） -->
        <el-col :span="24">
          <el-form-item label="说明" prop="remark">
            <el-input
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="给发起人看的说明（不超过 500 字）"
              v-model="info.remark"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">谁可以提交该审批</el-divider>

      <!-- 2.0（B1 §5.4，REQ-PERM-001）：可发起范围（服务端强制） -->
      <el-row>
        <el-col :span="12">
          <el-form-item label="可发起范围" prop="submitScopeType">
            <el-select v-model="info.submitScopeType" placeholder="请选择" style="width: 100%">
              <el-option v-for="opt in scopeTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12" v-if="isSpecifiedScope">
          <el-form-item label="包含下级部门" prop="includeChildDept">
            <el-switch v-model="info.includeChildDept" active-value="1" inactive-value="0" />
            <span class="form-tip">默认包含：勾选后，指定部门的下级部门成员也可发起</span>
          </el-form-item>
        </el-col>

        <el-col :span="24" v-if="info.submitScopeType === '1'">
          <el-form-item label="指定人员" prop="submitScopeDetail">
            <el-select
              v-model="userIds"
              multiple
              filterable
              placeholder="选择可以发起该审批的人员"
              style="width: 100%"
            >
              <el-option
                v-for="u in userOptions"
                :key="u.userId"
                :label="u.nickName ? u.nickName + '（' + u.userName + '）' : u.userName"
                :value="u.userId"
              />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="24" v-if="info.submitScopeType === '2'">
          <el-form-item label="指定角色" prop="submitScopeDetail">
            <el-select v-model="roleIds" multiple filterable placeholder="选择可以发起该审批的角色" style="width: 100%">
              <el-option v-for="r in roleOptions" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="24" v-if="info.submitScopeType === '3'">
          <el-form-item label="指定部门" prop="submitScopeDetail">
            <el-select v-model="deptIds" multiple filterable placeholder="选择可以发起该审批的部门" style="width: 100%">
              <el-option v-for="d in deptOptions" :key="d.deptId" :label="d.label" :value="d.deptId" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">流程管理员</el-divider>

      <!-- 2.0（B1 §5.5，REQ-PERM-005）：谁可以编辑/发布这条模板的流程 -->
      <el-row>
        <el-col :span="24">
          <el-form-item label="流程管理员" prop="flowAdmins">
            <el-select v-model="adminIds" multiple filterable placeholder="留空则默认由模板创建人与拥有模板编辑权限者管理" style="width: 100%">
              <el-option
                v-for="u in userOptions"
                :key="u.userId"
                :label="u.nickName ? u.nickName + '（' + u.userName + '）' : u.userName"
                :value="u.userId"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="关联流程">
            <!-- 2.0（B1 §5.4）：不再手选 —— 改由「流程设计」页签自动回写，这里只读展示 -->
            <el-input :value="defKeyText" readonly placeholder="在「流程设计」页签内设计并发布后自动绑定" />
            <span class="form-tip">流程标识由系统生成（tpl_ + 模板ID前 8 位），发布后自动回写，无需手工抄写</span>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
  </div>
</template>

<script>
import { listEnable } from "@/api/workflow/template";
import { listUser } from "@/api/system/user";
import { listRole } from "@/api/system/role";
import { listDept } from "@/api/system/dept";
import { TEMPLATE_ICONS, defaultTemplateIcon, isPresetIcon } from "@/utils/templateIcons";

/** 可发起范围枚举（与后端 t_template.submit_scope_type / SubmitScopeMatcher 对齐） */
export const SCOPE_TYPES = {
  ALL: "0",
  USER: "1",
  ROLE: "2",
  DEPT: "3"
};

export default {
  props: {
    info: {
      type: Object,
      default: null,
    },
  },
  created() {
    /** 查询模板分类（既有「模板分类」就是页面上的「分组」，不能在这里新建） */
    listEnable().then((res) => {
      this.typeOptions = res.data || [];
    });
    /** 人员：可发起范围明细 + 流程管理员都要用 */
    listUser({ pageNum: 1, pageSize: 500 }).then((res) => {
      this.userOptions = res.rows || [];
    });
    listRole({ pageNum: 1, pageSize: 500 }).then((res) => {
      this.roleOptions = res.rows || [];
    });
    listDept().then((res) => {
      this.deptOptions = (res.data || []).map((d) => ({
        deptId: d.deptId,
        // 用物化路径算缩进，让下拉在视觉上仍是一棵树（不引 TreeSelect，少一处依赖）
        label: "　".repeat(Math.max(0, (d.ancestors || "0").split(",").length - 1)) + d.deptName
      }));
    });
    // 注意：这里**不**初始化明细与图标 —— 宿主是异步把 baseInfo 整体换掉的
    // （编辑页 created 里 getTemplate 回来才赋值），据此在 created 里初始化会立刻被覆盖。
    // 统一交给下面 watch.info 处理（引用变化即重建，immediate 覆盖首次挂载）。
  },
  data() {
    return {
      iconOptions: TEMPLATE_ICONS,
      scopeTypeOptions: [
        { value: SCOPE_TYPES.ALL, label: "全员（默认）" },
        { value: SCOPE_TYPES.USER, label: "指定人员" },
        { value: SCOPE_TYPES.ROLE, label: "指定角色" },
        { value: SCOPE_TYPES.DEPT, label: "指定部门" }
      ],
      typeOptions: [],
      userOptions: [],
      roleOptions: [],
      deptOptions: [],
      // 明细在界面上按三类分开维护，保存前合成 submitScope（多态表：一条明细一个 targetId）
      userIds: [],
      roleIds: [],
      deptIds: [],
      adminIds: [],
      rules: {
        name: [
          { required: true, message: "请输入模板名称", trigger: "blur" },
          { max: 200, message: "模板名称不能超过 200 字", trigger: "blur" }
        ],
        type: [{ required: true, message: "请选择分组（模板分类）", trigger: "change" }],
        icon: [{ required: true, message: "请选择图标", trigger: "change" }],
        remark: [{ max: 500, message: "说明不能超过 500 字", trigger: "blur" }],
        sort: [{ required: true, message: "请输入排序号", trigger: "blur" }],
        submitScopeType: [{ required: true, message: "请选择可发起范围", trigger: "change" }],
        submitScopeDetail: [{ validator: this.validateScopeDetail, trigger: "change" }]
      },
    };
  },
  computed: {
    isSpecifiedScope() {
      return this.info.submitScopeType && this.info.submitScopeType !== SCOPE_TYPES.ALL;
    },
    defKeyText() {
      return this.info.defKey || "";
    },
  },
  watch: {
    /**
     * 宿主把 `info` 整个换掉时（编辑页接口回显）重建界面状态。
     *
     * 只监听**引用**，不 deep：用户在界面上的编辑不应触发它，
     * `$set` 补默认图标也不会自触发（引用没变）。
     */
    info: {
      handler(val) {
        if (!val) return;
        /** 老数据可能没有图标：补上图标集里的第一个（delta spec：新建 MUST 默认第一个） */
        if (!isPresetIcon(val.icon)) {
          this.$set(val, "icon", defaultTemplateIcon());
        }
        this.initFromModel();
      },
      immediate: true
    },
    userIds: {
      handler() { this.syncScopeToModel(); },
      deep: true
    },
    roleIds: {
      handler() { this.syncScopeToModel(); },
      deep: true
    },
    deptIds: {
      handler() { this.syncScopeToModel(); },
      deep: true
    },
    adminIds: {
      handler() {
        this.$set(this.info, "flowAdmins", (this.adminIds || []).map((userId) => ({ userId })));
      },
      deep: true
    },
    "info.submitScopeType"() {
      this.syncScopeToModel();
      // 切到"全员"时清掉明细，避免保存后残留旧范围（服务端也会清，这里保持界面一致）
      if (this.info.submitScopeType === SCOPE_TYPES.ALL) {
        this.userIds = [];
        this.roleIds = [];
        this.deptIds = [];
      }
    }
  },
  methods: {
    /** 从模型回填界面（编辑既有模板 / 接口回显） */
    initFromModel() {
      const details = this.info.submitScope || [];
      this.userIds = details.filter((d) => d.scopeType === SCOPE_TYPES.USER).map((d) => d.targetId);
      this.roleIds = details.filter((d) => d.scopeType === SCOPE_TYPES.ROLE).map((d) => d.targetId);
      this.deptIds = details.filter((d) => d.scopeType === SCOPE_TYPES.DEPT).map((d) => d.targetId);
      this.adminIds = (this.info.flowAdmins || []).map((a) => a.userId);
    },
    /** 合成多态明细：只保留当前范围类型对应的那一类 */
    syncScopeToModel() {
      const type = this.info.submitScopeType;
      let rows = [];
      if (type === SCOPE_TYPES.USER) {
        rows = this.userIds.map((targetId) => ({ scopeType: SCOPE_TYPES.USER, targetId }));
      } else if (type === SCOPE_TYPES.ROLE) {
        rows = this.roleIds.map((targetId) => ({ scopeType: SCOPE_TYPES.ROLE, targetId }));
      } else if (type === SCOPE_TYPES.DEPT) {
        rows = this.deptIds.map((targetId) => ({ scopeType: SCOPE_TYPES.DEPT, targetId }));
      }
      this.$set(this.info, "submitScope", rows);
    },
    /** 「指定」类但没选明细 → 拦住保存（服务端也会拦，双保险） */
    validateScopeDetail(rule, value, callback) {
      if (!this.isSpecifiedScope) {
        return callback();
      }
      if (!this.userIds.length && !this.roleIds.length && !this.deptIds.length) {
        return callback(new Error("选择了指定范围时，必须至少选择一项"));
      }
      callback();
    }
  }
};
</script>

<style scoped>
.form-tip {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
