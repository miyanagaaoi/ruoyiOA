<template>
  <div class="app-container simple-flow-designer">
    <!-- ============ 顶部工具条 ============ -->
    <div class="topbar">
      <span class="lbl">流程名称</span>
      <el-input v-model="flow.name" size="small" style="width:190px" placeholder="流程名称" />
      <span class="lbl">流程标识</span>
      <el-input
        v-model="flow.defKey"
        size="small"
        style="width:170px"
        placeholder="如 contractApproval"
        :disabled="flow.status === '1'"
      />
      <span class="lbl">分类</span>
      <el-input v-model="flow.category" size="small" style="width:100px" placeholder="分类" />
      <span class="lbl">关联表单</span>
      <el-select
        v-model="formDef.id"
        size="small"
        style="width:190px"
        placeholder="选择表单（提取字段）"
        filterable
        clearable
        @change="onFormChange"
      >
        <el-option v-for="f in formOptions" :key="f.id" :label="f.name" :value="f.id" />
      </el-select>
      <span v-if="formDef.list.length" class="tip">已提取 {{ formDef.list.length }} 个字段</span>
      <el-tag :type="flow.status === '1' ? 'success' : 'info'" size="small">
        {{ flow.status === '1' ? '已发布 v' + flow.version : '草稿' }}
      </el-tag>
      <span class="spacer" />
      <el-button size="mini" icon="el-icon-view" @click="handlePreview">编译预览</el-button>
      <el-button size="mini" icon="el-icon-time" @click="handleHistory">版本历史</el-button>
      <el-button size="mini" type="primary" plain icon="el-icon-document" :loading="saving" @click="handleSaveDraft">保存草稿</el-button>
      <el-button size="mini" type="primary" icon="el-icon-upload2" :loading="publishing" @click="handlePublish">发布</el-button>
    </div>

    <el-row :gutter="12" style="margin-top:12px">
      <!-- ============ 流程画布（占满主内容区） ============ -->
      <el-col :span="24">
        <el-card shadow="never" class="pane">
          <div slot="header" class="pane-head">
            <span>流程画布</span>
            <span class="tip">点节点配置 · 点 + 插入 · 分支并列</span>
          </div>

          <FlowTree :nodes="flow.nodes" :path-prefix="[]" />
        </el-card>
      </el-col>
    </el-row>

    <!-- ============ 配置抽屉：选中节点/分支后弹出 ============ -->
    <el-drawer
      :visible.sync="drawerVisible"
      direction="rtl"
      size="540px"
      custom-class="flow-drawer"
    >
      <div slot="title" class="pane-head">
        <span>{{ panelTitle }}</span>
        <span class="tip">{{ panelSubtitle }}</span>
      </div>

      <div class="drawer-body">
          <div v-if="currentNode" class="cfg">
            <el-form label-width="96px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="currentNode.name" placeholder="会显示在打印件签批栏标题上" :disabled="isSys(currentNode)" />
              </el-form-item>

              <!-- 审批 / 办理 -->
              <template v-if="isApprovable(currentNode)">
                <el-form-item label="参与人来源">
                  <el-select v-model="currentNode.assignee.source" style="width:100%">
                    <el-option v-for="s in assigneeSources" :key="s.value" :label="s.label" :value="s.value" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="currentNode.assignee.source === 'USER'" label="指定人员">
                  <el-input v-model="userIdsText" placeholder="用户ID，多个用英文逗号分隔" />
                </el-form-item>
                <el-form-item v-if="currentNode.assignee.source === 'ROLE'" label="角色">
                  <el-input v-model="roleIdsText" placeholder="角色ID，多个用英文逗号分隔" />
                </el-form-item>
                <el-form-item v-if="needLevel(currentNode)" label="层级">
                  <el-input-number v-model="currentNode.assignee.level" :min="1" :max="5" />
                </el-form-item>

                <el-form-item label="多人方式">
                  <el-radio-group v-model="currentNode.multiMode">
                    <el-radio label="SINGLE">单人</el-radio>
                    <el-radio label="AND">会签（全部同意）</el-radio>
                    <el-radio label="OR">或签（一人同意）</el-radio>
                    <el-radio label="SEQ">依次审批</el-radio>
                  </el-radio-group>
                </el-form-item>

                <el-form-item label="同一审批人">
                  <el-radio-group v-model="currentNode.sameUserPolicy">
                    <el-radio label="EACH_TIME">每次都审（默认）</el-radio>
                    <el-radio label="AUTO_PASS">同一人自动通过</el-radio>
                  </el-radio-group>
                </el-form-item>

                <el-form-item label="签名要求">
                  <el-radio-group v-model="currentNode.signMode">
                    <el-radio label="NONE">不需要</el-radio>
                    <el-radio label="OPTIONAL">可选</el-radio>
                    <el-radio label="REQUIRED">必需</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item v-if="currentNode.signMode !== 'NONE'" label="允许方式">
                  <el-checkbox-group v-model="currentNode.signTypes">
                    <el-checkbox label="HANDWRITE">手写签名</el-checkbox>
                    <el-checkbox label="PRESET">预存签名</el-checkbox>
                    <el-checkbox label="SEAL">电子印章</el-checkbox>
                  </el-checkbox-group>
                </el-form-item>

                <el-form-item label="可用按钮">
                  <el-checkbox-group v-model="currentNode.buttons">
                    <el-checkbox v-for="b in buttonOptions" :key="b.value" :label="b.value">{{ b.label }}</el-checkbox>
                  </el-checkbox-group>
                </el-form-item>

                <el-form-item label="超时提醒">
                  <el-input-number v-model="currentNode.timeoutHours" :min="0" :max="720" />
                  <span class="tip">小时，0 表示不提醒</span>
                </el-form-item>

                <el-divider content-position="left">异常兜底</el-divider>
                <el-form-item label="审批人为空">
                  <el-select v-model="currentNode.emptyPolicy">
                    <el-option label="转流程管理员（默认）" value="ADMIN" />
                    <el-option label="自动通过" value="AUTO_PASS" />
                    <el-option label="转指定人员" value="USER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="与发起人同人">
                  <el-select v-model="currentNode.selfPolicy">
                    <el-option label="自动跳过（默认）" value="SKIP" />
                    <el-option label="由本人审批" value="SELF" />
                    <el-option label="转直属上级" value="LEADER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="审批人离职">
                  <el-select v-model="currentNode.leftPolicy">
                    <el-option label="转直属上级（默认）" value="LEADER" />
                    <el-option label="转流程管理员" value="ADMIN" />
                  </el-select>
                </el-form-item>
              </template>

              <!-- 条件分支 -->
              <template v-if="currentNode.type === 'condition'">
                <el-alert type="info" :closable="false">
                  <div slot="title">
                    条件分支是<b>排他</b>的：按分支顺序取<b>第一条条件为真</b>的分支执行，其余不执行；
                    全部不满足时进入「其他情况」。分支条件在左侧节点卡片里编辑。
                  </div>
                </el-alert>
              </template>

              <!-- 并行分支 -->
              <template v-if="currentNode.type === 'parallel'">
                <el-form-item label="分支来源">
                  <el-select v-model="currentNode.branchSource">
                    <el-option label="按表单多选字段动态生成" value="FORM_MULTI" />
                  </el-select>
                </el-form-item>
                <el-form-item label="表单字段">
                  <el-select
                    v-model="currentNode.formField"
                    placeholder="选择多选字段"
                    style="width:100%"
                    filterable
                    allow-create
                    default-first-option
                  >
                    <el-option
                      v-for="f in multiFieldOptions"
                      :key="f.vModel"
                      :label="f.label + '（' + f.vModel + '）'"
                      :value="f.vModel"
                    />
                  </el-select>
                  <div v-if="!multiFieldOptions.length" class="tip warn">
                    当前表单里没有「多选」控件，请先在表单设计器里加一个（如"其他会审部门"）
                  </div>
                </el-form-item>
                <el-form-item label="每条分支的参与人">
                  <el-select v-model="currentNode.branchAssignee.source">
                    <el-option label="该分支对应的部门" value="BRANCH_VALUE" />
                  </el-select>
                </el-form-item>
                <el-form-item label="合流规则">
                  <el-radio-group v-model="currentNode.joinMode">
                    <el-radio label="ALL">全部部门批完才继续（默认）</el-radio>
                    <el-radio label="ANY">任一部门批完即继续</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="分支上限">
                  <el-input-number v-model="currentNode.maxBranches" :min="1" :max="20" />
                </el-form-item>
                <el-form-item label="签名要求">
                  <el-radio-group v-model="currentNode.signMode">
                    <el-radio label="NONE">不需要</el-radio>
                    <el-radio label="OPTIONAL">可选</el-radio>
                    <el-radio label="REQUIRED">必需</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-alert type="warning" :closable="false">
                  <div slot="title">
                    已实测：集合为空（一个部门都不勾）时引擎<b>自动跳过</b>本节点；
                    但集合变量<b>必须存在</b>，否则报 <code>Variable … was not found</code>——提交时要把空值写成空数组。
                  </div>
                </el-alert>
              </template>
            </el-form>
          </div>

          <!-- 选中分支（泳道）：分支配置 -->
          <div v-else-if="selectedBranch" class="cfg">
            <el-form label-width="92px" size="small">
              <el-form-item label="分支名称">
                <el-input
                  v-model="selectedBranch.name"
                  :disabled="!!selectedBranch.defaultBranch"
                  placeholder="分支名称"
                />
              </el-form-item>

              <el-form-item label="分支类型">
                <el-tag v-if="selectedBranch.defaultBranch" type="info" size="small">系统兜底</el-tag>
                <el-tag v-else size="small">条件分支</el-tag>
              </el-form-item>

              <el-form-item v-if="!selectedBranch.defaultBranch" label="优先级">
                <div class="row-inline">
                  <span class="prio-badge">优先级 {{ branchPriority }}</span>
                  <el-button
                    type="text"
                    size="mini"
                    icon="el-icon-back"
                    :disabled="branchPriority <= 1"
                    @click="moveBranchAt(selection.path, -1)"
                  >前移</el-button>
                  <el-button
                    type="text"
                    size="mini"
                    icon="el-icon-right"
                    :disabled="isLastConditionalBranch"
                    @click="moveBranchAt(selection.path, 1)"
                  >后移</el-button>
                </div>
                <div class="tip">条件分支自上而下依次判定，命中即进入；兜底分支永远排在最后。</div>
              </el-form-item>

              <el-form-item v-if="!selectedBranch.defaultBranch" label="进入条件">
                <div class="row-inline">
                  <span class="cond-text">{{ condText(selectedBranch) }}</span>
                  <el-button type="text" size="mini" @click="openConditionAt(selection.path)">编辑条件</el-button>
                </div>
              </el-form-item>
              <el-form-item v-else label="进入条件">
                <div class="tip">其余条件都不满足时进入本分支（系统自动生成，不可编辑）</div>
              </el-form-item>

              <el-form-item label="分支内节点">
                <span>{{ (selectedBranch.nodes || []).length }} 个</span>
                <span v-if="!(selectedBranch.nodes || []).length" class="tip warn">　空分支会被发布校验拦截</span>
              </el-form-item>

              <el-form-item v-if="!selectedBranch.defaultBranch" label="操作">
                <el-button
                  type="text"
                  size="mini"
                  icon="el-icon-delete"
                  @click="removeBranchAt(selection.path)"
                >删除本分支</el-button>
              </el-form-item>
            </el-form>
          </div>
          <div v-else class="tip" style="padding:24px 0;text-align:center">从画布上选择节点或分支开始配置</div>
        </el-card>

        <!-- 流程级：表单字段（自动提取，只读） -->
        <el-card shadow="never" class="pane" style="margin-top:12px">
          <div slot="header" class="pane-head">
            <span>表单字段（自动提取）</span>
            <span class="tip">{{ formDef.name || '未关联表单' }}</span>
          </div>
          <div v-if="!formDef.list.length" class="tip" style="padding:6px 0">
            未关联表单 → 发布校验会跳过字段级检查（条件字段是否必填、并行分支是否引用多选字段）
          </div>
          <el-table v-else :data="formDef.list" size="mini" max-height="200">
            <el-table-column label="字段" prop="label" show-overflow-tooltip />
            <el-table-column label="字段名" prop="vModel" width="140" show-overflow-tooltip />
            <el-table-column label="必填" width="60" align="center">
              <template slot-scope="s">
                <el-tag v-if="s.row.required" type="success" size="mini">是</el-tag>
                <span v-else class="tip">否</span>
              </template>
            </el-table-column>
            <el-table-column label="多选" width="60" align="center">
              <template slot-scope="s">
                <el-tag v-if="s.row.multi" size="mini">是</el-tag>
                <span v-else class="tip">否</span>
              </template>
            </el-table-column>
            <el-table-column label="可作条件" width="80" align="center">
              <template slot-scope="s">
                <span v-if="s.row.conditionable && s.row.required" style="color:#2e7d32">可</span>
                <span v-else-if="s.row.conditionable" class="tip warn">需设必填</span>
                <span v-else class="tip">不支持</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>
    </el-drawer>

    <!-- 图形化条件组编辑器（字段清单由表单自动提取） -->
    <ConditionEditor
      :visible.sync="condVisible"
      :branch="condBranch"
      :field-options="conditionFieldOptions"
      @save="onConditionSave"
    />

    <!-- 编译预览 -->
    <el-drawer title="编译预览（BPMN XML）" :visible.sync="previewVisible" size="58%" append-to-body>
      <pre class="xml-pre">{{ previewXml }}</pre>
    </el-drawer>

    <!-- 版本历史 -->
    <el-drawer title="版本历史" :visible.sync="historyVisible" size="45%" append-to-body>
      <el-table :data="historyList" size="mini">
        <el-table-column label="版本" width="70">
          <template slot-scope="s">v{{ s.row.version }}</template>
        </el-table-column>
        <el-table-column label="发布人" prop="publisherName" width="110" />
        <el-table-column label="发布时间" prop="publishTime" width="170" />
        <el-table-column label="流程定义" prop="procDefId" show-overflow-tooltip />
        <el-table-column label="操作" width="90">
          <template slot-scope="s">
            <el-button type="text" size="mini" @click="handleRollback(s.row)">回滚到此版</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script>
import ConditionEditor from './ConditionEditor'
import FlowTree from './FlowTree'
import {
  getSimpleFlow,
  saveSimpleFlowDraft,
  validateSimpleFlow,
  previewSimpleFlow,
  publishSimpleFlow,
  historySimpleFlow,
  rollbackSimpleFlow
} from '@/api/workflow/simpleFlow'
import { listDynamicForm, getDynamicForm } from '@/api/workflow/dynamicForm'
import {
  extractFormFields,
  conditionableFields,
  requiredFieldNames,
  multiFieldNames
} from '@/utils/formSchema'

/**
 * 简化版流程设计器（对应 PRD 第 6 章 / 原型 S1、S17、S18、S14）
 *
 * 设计要点：
 *  1. 节点清单是唯一编辑区，全程不出现 BPMN 词汇；
 *  2. 条件分支在清单内以"内嵌子清单 + 条件标签"呈现，条件是排他路由；
 *  3. 并行分支是"按表单多选字段动态生成"的多实例任务，不画并行网关。
 */
export default {
  name: 'SimpleFlowDesigner',
  components: { ConditionEditor, FlowTree },
  data() {
    return {
      flow: {
        id: null,
        defKey: '',
        name: '',
        category: '',
        status: '0',
        version: 0,
        nodes: []
      },
      /**
       * 当前选中的节点。顶层节点用 { kind:'top', index }，
       * 条件分支内部的节点用 { kind:'branch', index, branchId, subIndex }——
       * 否则分支里的审批节点没法配置（发布时必然被 V-2「未配置参与人」拦下）。
       */
      /**
       * 选中路径：[节点下标, 泳道下标, 节点下标, 泳道下标, …] 交替。
       * 偶数位 = 节点，奇数位 = 泳道；这样任意层嵌套都能寻址。
       * （旧实现用 {kind,index,branchId,subIndex} 只能表达两层，嵌套一深就失效。）
       */
      selection: { path: [0] },
      /** 已折叠的泳道，key = 路径字符串 */
      collapsedLanes: {},
      /** 配置抽屉开关：选中节点/分支时自动弹出 */
      drawerVisible: false,
      saving: false,
      publishing: false,
      condVisible: false,
      condNode: null,
      condBranch: null,
      previewVisible: false,
      previewXml: '',
      historyVisible: false,
      historyList: [],
      formOptions: [],
      /** 关联的表单及其字段清单（从表单 schema 自动提取，不再手敲字段名） */
      formDef: { id: '', name: '', list: [] },
      assigneeSources: [
        { value: 'USER', label: '指定人员' },
        { value: 'ROLE', label: '角色' },
        { value: 'DEPT_LEADER', label: '部门负责人' },
        { value: 'LEADER', label: '直属上级' },
        { value: 'INITIATOR', label: '发起人本人' },
        { value: 'INITIATOR_SELECT', label: '发起人自选' }
      ],
      buttonOptions: [
        { value: 'agree', label: '同意' },
        { value: 'return', label: '退回' },
        { value: 'reject', label: '驳回' },
        { value: 'addSign', label: '加签' },
        { value: 'transfer', label: '转办' },
        { value: 'delegate', label: '委派' },
        { value: 'copy', label: '抄送' },
        { value: 'urge', label: '催办' },
        { value: 'stamp', label: '盖章' },
        { value: 'print', label: '打印' }
      ]
    }
  },
  computed: {
    /** 路径解析器：偶数位=节点下标，奇数位=泳道下标 */
    resolvePath() {
      return path => {
        if (!path || !path.length) return null
        let nodes = this.flow.nodes
        let cur = null
        for (let d = 0; d < path.length; d++) {
          const i = path[d]
          if (d % 2 === 0) {
            cur = nodes[i]
            if (!cur) return null
          } else {
            const br = (cur.branches || [])[i]
            if (!br) return null
            cur = br
            nodes = br.nodes || []
          }
        }
        return cur
      }
    },
    /** 当前选中的是节点（路径长度奇数） */
    selectedNode() {
      const p = this.selection.path || []
      return p.length % 2 === 1 ? this.resolvePath(p) : null
    },
    /** 当前选中的是泳道（路径长度偶数且非空） */
    selectedBranch() {
      const p = this.selection.path || []
      return p.length > 0 && p.length % 2 === 0 ? this.resolvePath(p) : null
    },
    currentNode() {
      return this.selectedNode
    },
    /** 面包屑：如「合同类型路由 / 分支1」 */
    currentBranchPath() {
      const p = this.selection.path || []
      if (p.length < 3) return ''
      const names = []
      let nodes = this.flow.nodes
      let cur = null
      for (let d = 0; d < p.length; d++) {
        const i = p[d]
        if (d % 2 === 0) {
          cur = nodes[i]
          if (!cur) break
          if (d < p.length - 1) names.push(cur.name || '节点')
        } else {
          const br = (cur.branches || [])[i]
          if (!br) break
          names.push(br.name || ('分支' + (i + 1)))
          cur = br
          nodes = br.nodes || []
        }
      }
      return names.join(' / ')
    },
    /** 右侧面板标题 */
    panelTitle() {
      if (this.selectedNode) {
        return '节点配置 — ' + (this.selectedNode.name || this.typeLabel(this.selectedNode.type))
      }
      if (this.selectedBranch) {
        return '分支配置 — ' + (this.selectedBranch.name || '分支')
      }
      return '配置'
    },
    /** 右侧面板副标题 */
    panelSubtitle() {
      if (this.selectedNode) {
        const path = this.currentBranchPath
        return (path ? path + ' · ' : '') + this.typeLabel(this.selectedNode.type)
      }
      if (this.selectedBranch) {
        if (this.selectedBranch.defaultBranch) return '系统兜底分支'
        return '优先级 ' + this.branchPriority + ' · ' + (this.selectedBranch.nodes || []).length + ' 个节点'
      }
      return '从画布上选择节点或分支'
    },
    /** 选中泳道的优先级（= 在该容器内的下标 + 1） */
    branchPriority() {
      const p = this.selection.path || []
      return p.length ? p[p.length - 1] + 1 : 0
    },
    /** 选中泳道所属的容器节点 */
    branchParentNode() {
      const p = this.selection.path || []
      return p.length > 1 ? this.resolvePath(p.slice(0, -1)) : null
    },
    /** 后面还有没有可移动的条件分支（没有则"后移"禁用） */
    isLastConditionalBranch() {
      const parent = this.branchParentNode
      if (!parent || !parent.branches) return true
      const idx = this.branchPriority - 1
      return !parent.branches.slice(idx + 1).some(b => !b.defaultBranch)
    },
    /** 可作为条件的字段（只允许已设为必填的） */
    conditionFieldOptions() {
      return conditionableFields(this.formDef.list)
    },
    /** 多选字段（并行分支的集合来源只能选多选） */
    multiFieldOptions() {
      return this.formDef.list.filter(f => f.multi)
    },
    /** 必填字段名（发布校验时传给后端，校验条件字段是否存在且必填） */
    requiredFields() {
      return requiredFieldNames(this.formDef.list)
    },
    /** 多选字段名（发布校验并行分支的集合来源） */
    multiFields() {
      return multiFieldNames(this.formDef.list)
    },
    userIdsText: {
      get() {
        return (this.currentNode && this.currentNode.assignee.userIds || []).join(',')
      },
      set(v) {
        this.currentNode.assignee.userIds = this.splitIds(v)
      }
    },
    roleIdsText: {
      get() {
        return (this.currentNode && this.currentNode.assignee.roleIds || []).join(',')
      },
      set(v) {
        this.currentNode.assignee.roleIds = this.splitIds(v)
      }
    }
  },
  /**
   * 向递归画布组件（FlowTree）暴露回调。
   * 用箭头函数绑定 this —— 直接传 vm.method 会丢上下文。
   * 递归层不在模板里层层 $emit，避免深层事件穿透（见 doc/飞书流程设计页面.md §11）。
   */
  provide() {
    const vm = this
    return {
      flowDesigner: {
        isActive: p => vm.isActive(p),
        isContainer: n => vm.isContainer(n),
        isCollapsed: p => vm.isCollapsed(p),
        typeLabel: t => vm.typeLabel(t),
        summary: n => vm.summary(n),
        nodeBadge: n => vm.nodeBadge(n),
        kindClass: n => vm.kindClass(n),
        condText: b => vm.condText(b),
        select: p => vm.selectPath(p),
        insertAfter: (p, t) => vm.insertAfter(p, t),
        insertEmpty: p => vm.insertEmpty(p),
        addBranch: p => vm.addBranchAt(p),
        removeBranch: p => vm.removeBranchAt(p),
        editCondition: p => vm.openConditionAt(p),
        toggleLane: p => vm.toggleLane(p),
        isSys: n => vm.isSys(n),
        removeNode: p => vm.removeNodeAt(p),
        moveNode: (p, d) => vm.moveNodeAt(p, d)
      }
    }
  },
  created() {
    this.loadFormOptions()
    const id = this.$route.query.id
    if (id) {
      this.loadFlow(id)
    } else {
      this.initEmptyFlow()
    }
  },
  methods: {
    /* ================= 画布：路径寻址与结构编辑 ================= */
    /** 该路径是否为当前选中项 */
    isActive(path) {
      const cur = this.selection.path || []
      return cur.length === path.length && cur.every((v, i) => v === path[i])
    },
    /** 是否为分支容器（条件 / 并行） */
    isContainer(node) {
      return !!node && (node.type === 'condition' || node.type === 'parallel')
    },
    /** 节点卡片上的单字徽标 */
    nodeBadge(node) {
      if (!node) return '·'
      return {
        start: '起', end: '终', approve: '审', handle: '办',
        cc: '抄', condition: '条', parallel: '并'
      }[node.type] || '·'
    },
    /** 节点卡片的配色类（审批=橙、办理=绿、起止=灰蓝、容器=蓝、抄送=虚线） */
    kindClass(node) {
      if (!node) return ''
      if (node.type === 'start' || node.type === 'end') return 'kind-sys'
      if (node.type === 'condition' || node.type === 'parallel') return 'kind-container'
      return 'kind-' + node.type
    },
    isCollapsed(path) {
      return !!this.collapsedLanes[this.laneKey(path)]
    },
    toggleLane(path) {
      const k = this.laneKey(path)
      this.$set(this.collapsedLanes, k, !this.collapsedLanes[k])
    },
    laneKey(path) {
      return (path || []).join('-')
    },
    selectPath(path) {
      this.selection = { path: (path || []).slice() }
      // 选中即弹出配置抽屉（画布占满主内容区，配置不常驻占位）
      this.drawerVisible = true
    },
    /**
     * 取 path 末位元素所在的同级数组。
     * 例：[2] → 顶层数组；[2,1,3] → 顶层[2].branches[1].nodes
     */
    siblingsOf(path) {
      let nodes = this.flow.nodes
      for (let d = 0; d + 1 < path.length; d += 2) {
        const node = nodes[path[d]]
        if (!node) return null
        const br = (node.branches || [])[path[d + 1]]
        if (!br) return null
        if (!br.nodes) {
          this.$set(br, 'nodes', [])
        }
        nodes = br.nodes
      }
      return nodes
    },
    createNode(type) {
      if (type === 'condition') return this.newCondition()
      if (type === 'parallel') return this.newParallel()
      if (type === 'end') return this.newEnd()
      if (type === 'handle') return Object.assign(this.newApprove('办理节点'), { type: 'handle' })
      if (type === 'cc') {
        return {
          id: 'cc_' + Date.now(),
          type: 'cc',
          name: '抄送节点',
          ccers: [],
          signMode: 'NONE'
        }
      }
      return this.newApprove()
    },
    /** 在 path 指向的节点之后插入 */
    insertAfter(path, type) {
      const nodes = this.siblingsOf(path)
      if (!nodes) return
      const idx = path[path.length - 1]
      nodes.splice(idx + 1, 0, this.createNode(type))
      this.selectPath(path.slice(0, -1).concat([idx + 1]))
    },
    /** 空链（空泳道）里添加第一个节点；path 为泳道路径 */
    insertEmpty(path) {
      const nodes = this.siblingsOf(path)
      if (!nodes) return
      nodes.push(this.createNode('approve'))
      this.selectPath(path.concat([nodes.length - 1]))
    },
    /** 给容器加一条分支（兜底分支永远排最后） */
    addBranchAt(path) {
      const node = this.resolvePath(path)
      if (!this.isContainer(node)) return
      if (!node.branches) {
        this.$set(node, 'branches', [])
      }
      const n = node.branches.filter(b => !b.defaultBranch).length + 1
      const branch = {
        id: 'b_' + Date.now(),
        name: '分支' + n,
        groups: [{ logic: 'AND', rows: [{ field: '', op: 'EQ', value: '' }] }],
        nodes: [this.newApprove('分支内审批')]
      }
      const fallbackIndex = node.branches.findIndex(b => b.defaultBranch)
      if (fallbackIndex >= 0) {
        node.branches.splice(fallbackIndex, 0, branch)
      } else {
        node.branches.push(branch)
      }
      this.selectPath(path.concat([fallbackIndex >= 0 ? fallbackIndex : node.branches.length - 1]))
    },
    /** 删除一条分支；path 为泳道路径 */
    removeBranchAt(path) {
      const laneIdx = path[path.length - 1]
      const node = this.resolvePath(path.slice(0, -1))
      if (!this.isContainer(node)) return
      node.branches.splice(laneIdx, 1)
      this.selectPath(path.slice(0, -1))
    },
    /**
     * 调整分支优先级（前移/后移）。
     * 兜底分支永远排最后 —— 条件分支只能在条件分支之间移动。
     */
    moveBranchAt(path, delta) {
      const parent = this.resolvePath(path.slice(0, -1))
      if (!this.isContainer(parent)) return
      const branches = parent.branches || []
      const idx = path[path.length - 1]
      const target = idx + delta
      if (target < 0 || target >= branches.length) return
      if (branches[idx].defaultBranch || branches[target].defaultBranch) return
      const a = branches[idx]
      const b = branches[target]
      this.$set(branches, idx, b)
      this.$set(branches, target, a)
      this.selectPath(path.slice(0, -1).concat([target]))
    },
    /** 节点排序；起止节点不参与 */
    moveNodeAt(path, delta) {
      const nodes = this.siblingsOf(path)
      if (!nodes) return
      const idx = path[path.length - 1]
      const target = idx + delta
      if (target < 0 || target >= nodes.length) return
      if (this.isSys(nodes[idx]) || this.isSys(nodes[target])) return
      const a = nodes[idx]
      const b = nodes[target]
      this.$set(nodes, idx, b)
      this.$set(nodes, target, a)
      this.selectPath(path.slice(0, -1).concat([target]))
    },
    /** 删除节点；起止节点不可删。删掉最后一个节点时把选中落回所属泳道 */
    removeNodeAt(path) {
      const idx = path[path.length - 1]
      const nodes = this.siblingsOf(path)
      if (!nodes) return
      const node = nodes[idx]
      if (!node || this.isSys(node)) return
      nodes.splice(idx, 1)
      const parent = path.slice(0, -1)
      if (nodes.length) {
        this.selectPath(parent.concat([Math.max(0, idx - 1)]))
      } else if (parent.length) {
        this.selectPath(parent)
      } else {
        this.selection = { path: [0] }
      }
    },
    /** 打开图形化条件编辑器；path 为泳道路径 */
    openConditionAt(path) {
      this.condNode = this.resolvePath(path.slice(0, -1))
      this.condBranch = this.resolvePath(path)
      this.condVisible = true
    },

    /* ---------------- 表单字段（自动提取） ---------------- */
    loadFormOptions() {
      listDynamicForm({ pageNum: 1, pageSize: 200 }).then(res => {
        this.formOptions = (res.rows || []).map(r => ({ id: r.id, name: r.name }))
      }).catch(() => {})
    },
    onFormChange(formId) {
      if (formId) {
        this.loadForm(formId)
      } else {
        this.formDef = { id: '', name: '', list: [] }
      }
    },
    loadForm(formId) {
      getDynamicForm(formId).then(res => {
        const d = res.data || {}
        this.formDef.id = d.id
        this.formDef.name = d.name
        this.formDef.list = extractFormFields(d.content)
      }).catch(() => {})
    },
    /* ---------------- 初始化 ---------------- */
    initEmptyFlow() {
      this.flow.nodes = [
        this.newStart(),
        this.newApprove('责任部门审批'),
        this.newApprove('集团分管领导'),
        this.newEnd()
      ]
      this.selection = { path: [1] }
    },
    loadFlow(id) {
      getSimpleFlow(id).then(res => {
        const d = res.data
        this.flow.id = d.id
        this.flow.defKey = d.defKey
        this.flow.name = d.name
        this.flow.category = d.category
        this.flow.status = d.status
        this.flow.version = d.version
        try {
          const content = JSON.parse(d.content)
          this.flow.nodes = content.nodes || []
          if (content.formId) {
            this.formDef.id = content.formId
            this.loadForm(content.formId)
          }
        } catch (e) {
          this.$modal.msgError('流程内容不是合法 JSON')
          this.flow.nodes = []
        }
        this.selection = { path: [0] }
      })
    },
    /* ---------------- 节点工厂 ---------------- */
    newStart() {
      return { id: 'start', type: 'start', name: '发起' }
    },
    newEnd() {
      return { id: 'end_' + Date.now(), type: 'end', name: '结束' }
    },
    newApprove(name) {
      return {
        id: 'n_' + Date.now() + '_' + Math.floor(Math.random() * 1000),
        type: 'approve',
        name: name || '审批节点',
        assignee: { source: 'USER', userIds: [], roleIds: [], level: 1 },
        multiMode: 'SINGLE',
        sameUserPolicy: 'EACH_TIME',
        signMode: 'OPTIONAL',
        signTypes: ['HANDWRITE', 'PRESET'],
        buttons: ['agree', 'return', 'copy', 'print'],
        fieldReadonly: [],
        timeoutHours: 0,
        emptyPolicy: 'ADMIN',
        selfPolicy: 'SKIP',
        leftPolicy: 'LEADER'
      }
    },
    newCondition() {
      return {
        id: 'gw_' + Date.now(),
        type: 'condition',
        name: '条件分支',
        branches: [
          {
            id: 'b_' + Date.now(),
            name: '分支1',
            groups: [{ logic: 'AND', rows: [{ field: '', op: 'EQ', value: '' }] }],
            nodes: [this.newApprove('分支内审批')]
          },
          {
            id: 'bd_' + Date.now(),
            name: '其他情况',
            defaultBranch: true,
            nodes: [this.newApprove('兜底审批')]
          }
        ]
      }
    },
    newParallel() {
      return {
        id: 'p_' + Date.now(),
        type: 'parallel',
        name: '会审部门（并行）',
        branchSource: 'FORM_MULTI',
        formField: 'jointDepts',
        branchAssignee: { source: 'BRANCH_VALUE' },
        joinMode: 'ALL',
        maxBranches: 5,
        signMode: 'OPTIONAL',
        signTypes: ['HANDWRITE', 'PRESET']
      }
    },
    /* ---------------- 条件分支 ---------------- */

    onConditionSave(groups) {
      if (this.condBranch) {
        this.$set(this.condBranch, 'groups', groups)
        this.$modal.msgSuccess('条件已更新')
      }
    },
    condText(branch) {
      const groups = branch.groups || []
      const parts = []
      groups.forEach(g => {
        const rows = (g.rows || []).filter(r => r.field).map(r => {
          const op = { EQ: '=', NE: '≠', GT: '>', GE: '≥', LT: '<', LE: '≤', CONTAINS: '包含', NOT_CONTAINS: '不包含', EMPTY: '为空', NOT_EMPTY: '不为空' }[r.op] || r.op
          return r.op === 'EMPTY' || r.op === 'NOT_EMPTY' ? `${r.field} ${op}` : `${r.field} ${op} ${r.value}`
        })
        if (rows.length) parts.push(rows.join(' 且 '))
      })
      return parts.length ? ('当 ' + parts.join(' 或 ')) : '未设置条件'
    },
    /* ---------------- 保存 / 发布 ---------------- */
    buildContent() {
      const content = {
        schemaVersion: 1,
        key: this.flow.defKey,
        name: this.flow.name,
        category: this.flow.category,
        nodes: this.flow.nodes,
        // 关联表单：既给设计器回显，也让发布校验能拿到字段级信息
        formId: this.formDef.id || null,
        formFields: {
          required: this.requiredFields,
          multi: this.multiFields
        }
      }
      return JSON.stringify(content)
    },
    handleSaveDraft() {
      if (!this.flow.name) return this.$modal.msgError('请填写流程名称')
      if (!this.flow.defKey) return this.$modal.msgError('请填写流程标识')
      this.saving = true
      saveSimpleFlowDraft({
        id: this.flow.id,
        name: this.flow.name,
        category: this.flow.category,
        content: this.buildContent()
      }).then(res => {
        this.saving = false
        if (res.data && res.data.id) {
          this.flow.id = res.data.id
        }
        this.$modal.msgSuccess('草稿已保存')
      }).catch(() => {
        this.saving = false
      })
    },
    handlePublish() {
      if (!this.flow.id) {
        return this.$modal.msgError('请先保存草稿')
      }
      // 先校验再发布，把问题一次性列出来
      const hasForm = this.formDef.list.length > 0
      validateSimpleFlow({
        id: this.flow.id,
        requiredFields: hasForm ? this.requiredFields : null,
        multiFields: hasForm ? this.multiFields : null
      }).then(res => {
        const issues = res.data || []
        const blocks = issues.filter(i => i.level === 'BLOCK')
        const warns = issues.filter(i => i.level === 'WARN')
        if (blocks.length) {
          this.showIssues(blocks, warns)
          return
        }
        const doPublish = () => {
          this.publishing = true
          publishSimpleFlow({ id: this.flow.id, remark: '设计器发布' }).then(r => {
            this.publishing = false
            this.flow.status = r.data.status
            this.flow.version = r.data.version
            this.flow.deployId = r.data.deployId
            this.$modal.msgSuccess('发布成功 v' + r.data.version)
          }).catch(() => {
            this.publishing = false
          })
        }
        if (warns.length) {
          this.$confirm(warns.map(w => '· ' + w.message).join('<br/>'), '存在提示项，是否继续发布？', {
            dangerouslyUseHTMLString: true,
            type: 'warning'
          }).then(doPublish).catch(() => {})
        } else {
          doPublish()
        }
      })
    },
    showIssues(blocks, warns) {
      const html = []
      if (blocks.length) {
        html.push('<b>阻断项（必须修复）</b>')
        blocks.forEach(b => html.push('· [' + b.rule + '] ' + (b.nodeId ? '节点 ' + b.nodeId + '：' : '') + b.message))
      }
      if (warns.length) {
        html.push('<br/><b>提示项</b>')
        warns.forEach(w => html.push('· [' + w.rule + '] ' + w.message))
      }
      this.$alert(html.join('<br/>'), '发布校验未通过', {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '知道了'
      })
    },
    handlePreview() {
      if (!this.flow.id) return this.$modal.msgError('请先保存草稿')
      previewSimpleFlow(this.flow.id).then(res => {
        this.previewXml = res.xml || res.msg || ''
        this.previewVisible = true
      })
    },
    handleHistory() {
      if (!this.flow.defKey) return this.$modal.msgError('尚未设置流程标识')
      historySimpleFlow(this.flow.defKey).then(res => {
        this.historyList = res.data || []
        this.historyVisible = true
      })
    },
    handleRollback(row) {
      this.$confirm('将以 v' + row.version + ' 的内容重新发布（生成新版本，历史不可改），是否继续？', '提示', {
        type: 'warning'
      }).then(() => {
        rollbackSimpleFlow({ defKey: this.flow.defKey, version: row.version }).then(res => {
          this.$modal.msgSuccess('已回滚并发布为 v' + res.data.version)
          this.historyVisible = false
          this.loadFlow(this.flow.id)
        })
      }).catch(() => {})
    },
    /* ---------------- 工具 ---------------- */
    splitIds(text) {
      if (!text) return []
      return String(text).split(',').map(s => s.trim()).filter(s => s)
    },
    isSys(node) {
      return node.type === 'start' || node.type === 'end'
    },
    isApprovable(node) {
      return node.type === 'approve' || node.type === 'handle'
    },
    needLevel(node) {
      return node.type === 'approve' && (node.assignee.source === 'DEPT_LEADER' || node.assignee.source === 'LEADER')
    },
    typeLabel(type) {
      return {
        start: '发起',
        approve: '审批节点',
        handle: '办理节点',
        condition: '条件分支',
        parallel: '并行分支',
        end: '结束'
      }[type] || type
    },
    summary(node) {
      if (node.type === 'start') return '流程起点'
      if (node.type === 'end') return '流程终点'
      if (node.type === 'condition') return '排他路由 · ' + (node.branches ? node.branches.length : 0) + ' 条分支'
      if (node.type === 'parallel') return '多实例 · ' + (node.joinMode === 'ANY' ? '任一完成' : '全部完成')
      const src = (this.assigneeSources.find(s => s.value === node.assignee.source) || {}).label || ''
      const multi = { SINGLE: '单人', AND: '会签', OR: '或签', SEQ: '依次' }[node.multiMode] || ''
      const sign = node.signMode === 'REQUIRED' ? ' · 需签名' : ''
      return src + ' · ' + multi + sign
    }
  }
}
</script>

<style lang="scss" scoped>
.simple-flow-designer {
  .topbar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    background: #fff;
    border: 1px solid #e4e4e4;
    border-radius: 4px;
    flex-wrap: wrap;
    .lbl {
      color: #666;
      font-size: 13px;
    }
    .spacer {
      flex: 1;
    }
  }
  .pane {
    ::v-deep .el-card__header {
      padding: 10px 14px;
    }
  }
  .pane-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 13px;
    color: #444;
  }
  .tip {
    color: #999;
    font-size: 12px;
  }
  .tip.warn {
    color: #c0392b;
  }
  .node-wrap {
    margin-bottom: 4px;
  }
  .node-card {
    display: flex;
    align-items: center;
    gap: 8px;
    border: 1px solid #d8d8d8;
    background: #fff;
    border-radius: 4px;
    padding: 7px 10px;
    cursor: pointer;
    font-size: 13px;
    &:hover {
      border-color: #b5b5b5;
    }
    &.active {
      border: 2px solid #6b6b6b;
      background: #fbfbfb;
    }
    &.sys {
      background: #f7f7f7;
      color: #666;
    }
    .idx {
      width: 18px;
      height: 18px;
      border-radius: 50%;
      border: 1px solid #999;
      background: #f0f0f0;
      color: #555;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      flex: none;
      &.dot {
        border: none;
        background: transparent;
        font-size: 12px;
      }
    }
    .nm {
      font-weight: 500;
    }
    .summary {
      color: #999;
      font-size: 12px;
      margin-left: auto;
    }
  }
  .insert-line {
    padding: 2px 0 2px 20px;
    color: #999;
  }
  .branch-box {
    margin: 4px 0 4px 20px;
    border-left: 2px dashed #d0b070;
    padding-left: 10px;
  }
  .branch-item {
    border: 1px solid #eee;
    border-radius: 4px;
    padding: 6px 8px;
    margin-bottom: 6px;
    background: #fdfdfd;
  }
  .branch-head {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    font-size: 12px;
    .branch-name {
      color: #8a6d1f;
      font-weight: 500;
    }
    .fr {
      margin-left: auto;
    }
  }
  .cond-chip {
    border: 1px solid #bbb;
    border-radius: 10px;
    padding: 0 8px;
    color: #555;
    background: #fff;
  }
  .tag-default {
    color: #999;
  }
  .branch-body {
    margin-top: 4px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .sub-node {
    border: 1px solid #ddd;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 12px;
    color: #555;
    background: #fff;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    &:hover {
      border-color: #b5b5b5;
    }
    &.active {
      border: 2px solid #6b6b6b;
      background: #fbfbfb;
    }
    .sub-sum {
      color: #b0b0b0;
      font-style: normal;
      font-size: 11px;
    }
    i {
      margin-left: 2px;
      color: #bbb;
      cursor: pointer;
      &:hover {
        color: #666;
      }
    }
  }
  /* 配置抽屉 */
  ::v-deep .flow-drawer {
    .el-drawer__header {
      margin-bottom: 0;
      padding: 14px 20px 12px;
      border-bottom: 1px solid #ebeef5;
      color: #333;
    }
    .el-drawer__body {
      padding: 0;
      overflow-y: auto;
    }
  }
  .drawer-body {
    padding: 14px 20px 20px;
  }
  .cfg {
    max-height: none;
    overflow-y: visible;
    padding-right: 6px;
  }
  /* 分支配置面板 */
  .row-inline {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .prio-badge {
    display: inline-block;
    font-size: 12px;
    line-height: 20px;
    color: #2e7d4f;
    background: #eaf6ef;
    border: 1px solid #cbe7d7;
    border-radius: 10px;
    padding: 0 10px;
  }
  .cond-text {
    display: inline-block;
    max-width: 320px;
    font-size: 12px;
    line-height: 20px;
    color: #666;
    background: #f5f7fa;
    border: 1px solid #e4e8ee;
    border-radius: 4px;
    padding: 0 10px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .xml-pre {
    padding: 12px;
    font-size: 12px;
    line-height: 1.6;
    color: #555;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
