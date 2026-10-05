<template>
  <div>
    <el-form ref="submitPolicyForm" :model="info" :rules="rules" label-width="140px">
      <el-row>
        <el-col :span="12">
          <el-form-item label="提交后可撤销" prop="revokeLimitMinutes">
            <el-input-number v-model="info.revokeLimitMinutes" :min="0" :max="10080" controls-position="right" />
            <span class="form-tip">分钟（0 = 不限制，沿用升级前行为）</span>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="审批人去重" prop="approverDedup">
            <el-select v-model="info.approverDedup" style="width: 100%">
              <el-option label="不自动同意（默认，与现状一致）" value="3" />
              <el-option label="同一审批人自动同意" value="1" />
              <el-option label="同一审批人自动跳过" value="2" />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="允许提交人改单" prop="allowEditAfterSubmit">
            <el-radio-group v-model="info.allowEditAfterSubmit">
              <el-radio label="0">否</el-radio>
              <el-radio label="1">是</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="允许代他人提交" prop="allowSubmitForOther">
            <el-radio-group v-model="info.allowSubmitForOther">
              <el-radio label="0">否</el-radio>
              <el-radio label="1">是</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="允许审批人撤回" prop="allowApproverRevoke">
            <el-radio-group v-model="info.allowApproverRevoke">
              <el-radio label="1">是</el-radio>
              <el-radio label="0">否</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="允许批量审批" prop="allowBatchApprove">
            <el-radio-group v-model="info.allowBatchApprove">
              <el-radio label="1">是</el-radio>
              <el-radio label="0">否</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="审批转发范围" prop="forwardScope">
            <el-select v-model="info.forwardScope" clearable placeholder="不限制" style="width: 100%">
              <el-option label="不限制（默认）" value="" />
              <el-option label="仅本部门" value="DEPT" />
              <el-option label="仅本部门及下级" value="DEPT_CHILD" />
              <el-option label="仅本分组" value="GROUP" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="以上策略项默认值均等于「升级前行为」；不修改即保持存量模板零回归（delta spec：workflow/form-definition）。"
      />
    </el-form>
  </div>
</template>

<script>
/**
 * 「更多设置」页签里的**提交与审批策略**组（OA 2.0 · B1 §5.7，REQ-FORM-009 / AC-52）。
 *
 * 7 项与默认值（design D11，口径 = 升级前行为）：
 *   revokeLimitMinutes=0（不限制）、allowEditAfterSubmit='0'、allowSubmitForOther='0'、
 *   allowApproverRevoke='1'、allowBatchApprove='1'、approverDedup='3'（不自动同意）、
 *   forwardScope=空（不限制）。
 * 新建模板不触碰这些项时，落库值必须等于上面这组默认值。
 */
export default {
  props: {
    info: {
      type: Object,
      default: null,
    },
  },
  created() {
    // 新建模板：把默认值写进模型（否则会提交 null，落库虽走列默认值，但界面显示空）
    const defaults = {
      revokeLimitMinutes: 0,
      allowEditAfterSubmit: "0",
      allowSubmitForOther: "0",
      allowApproverRevoke: "1",
      allowBatchApprove: "1",
      approverDedup: "3",
      forwardScope: ""
    };
    Object.keys(defaults).forEach((key) => {
      if (this.info[key] === undefined || this.info[key] === null) {
        this.$set(this.info, key, defaults[key]);
      }
    });
  },
  data() {
    return {
      rules: {
        revokeLimitMinutes: [{ required: true, message: "请填写撤销时限（0 表示不限制）", trigger: "blur" }],
        approverDedup: [{ required: true, message: "请选择审批人去重策略", trigger: "change" }],
        allowEditAfterSubmit: [{ required: true, message: "请选择", trigger: "change" }],
        allowSubmitForOther: [{ required: true, message: "请选择", trigger: "change" }],
        allowApproverRevoke: [{ required: true, message: "请选择", trigger: "change" }],
        allowBatchApprove: [{ required: true, message: "请选择", trigger: "change" }]
      }
    };
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
