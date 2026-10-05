<template>
  <div class="app-container simple-flow-designer">
    <!-- ============ 顶部工具条 ============ -->
    <div class="topbar">
      <!--
        内嵌模式（模板编辑页的「流程设计」页签，B1 §6.2）：
        流程名称 / 流程标识 / 关联表单 三个输入**隐藏，改为只读展示** ——
        名称与表单由模板决定，标识由系统按 tpl_+模板ID前8位生成，都不给用户改的入口。
      -->
      <template v-if="embedded">
        <span class="lbl">流程名称</span>
        <span class="ro-text">{{ flow.name || '（未命名）' }}</span>
        <span class="lbl">流程标识</span>
        <span class="ro-text mono">{{ flow.defKey || '（保存后自动生成）' }}</span>
        <span class="lbl">关联表单</span>
        <span class="ro-text">{{ formDef.name || '由模板决定，不可切换' }}</span>
        <span v-if="formDef.list.length" class="tip">已提取 {{ formDef.list.length }} 个字段</span>
        <el-tag :type="flow.status === '1' ? 'success' : 'info'" size="small">
          {{ flow.status === '1' ? '已发布 v' + flow.version : '草稿（未发布）' }}
        </el-tag>
        <span class="spacer" />
        <el-button size="mini" icon="el-icon-view" @click="handlePreview">编译预览</el-button>
        <el-button size="mini" icon="el-icon-time" @click="handleHistory">版本历史</el-button>
        <el-button size="mini" type="primary" plain icon="el-icon-document" :loading="saving" @click="handleSaveDraft">保存草稿</el-button>
        <el-button size="mini" type="primary" icon="el-icon-upload2" :loading="publishing" @click="handlePublish">发布</el-button>
      </template>
      <template v-else>
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
      <!--
        标识格式实时提示（与新建弹窗、后端 V-0 同口径）。
        原来这里**没有任何校验**：敲了中文要等保存/发布被服务端打回来才知道错。
      -->
      <span v-if="keyFormatError" class="tip warn" :title="keyFormatError">
        <i class="el-icon-warning-outline" /> 标识不可用
      </span>
      <span v-else class="tip" title="作为 Flowable 流程定义 key 写进 BPMN；发布后不可修改">
        发布后不可改
      </span>
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
      <!-- 表单下拉本身拉不到时，之前是一个静默的空下拉，用户会以为"系统里没有表单" -->
      <span v-if="formOptionsError" class="tip warn">
        <i class="el-icon-warning-outline" /> 表单列表加载失败：{{ formOptionsError }}
        <el-button type="text" size="mini" @click="loadFormOptions">重试</el-button>
      </span>
      <el-tag :type="flow.status === '1' ? 'success' : 'info'" size="small">
        {{ flow.status === '1' ? '已发布 v' + flow.version : '草稿' }}
      </el-tag>
      <span class="spacer" />
      <el-button size="mini" icon="el-icon-view" @click="handlePreview">编译预览</el-button>
      <el-button size="mini" icon="el-icon-time" @click="handleHistory">版本历史</el-button>
      <el-button size="mini" type="primary" plain icon="el-icon-document" :loading="saving" @click="handleSaveDraft">保存草稿</el-button>
      <el-button size="mini" type="primary" icon="el-icon-upload2" :loading="publishing" @click="handlePublish">发布</el-button>
      </template>
    </div>

    <el-row :gutter="12" style="margin-top:12px">
      <!-- ============ 流程画布（占满主内容区） ============ -->
      <el-col :span="24">
        <el-card shadow="never" class="pane">
          <div slot="header" class="pane-head">
            <span>流程画布</span>
            <span class="tip">拖动卡片排序 · 点节点配置 · 点 + 插入 · 分支并列</span>
          </div>

          <!--
            画布外层只管「加载中 / 加载失败 / 正常」三态。**不用空态**：
            空链的可视空态由 FlowTree 自己的 .ft-empty 负责（每一层嵌套链都要有），
            在这里再放一个空态会重复。
            原先 loadFlow() **完全没有 catch**：详情接口一失败，flow.nodes 就是空数组，
            画布一片空白，用户看到的是"这个流程没有节点"而不是"加载失败了"——必须区分。
          -->
          <StateBlock
            :state="canvasState"
            loading-text="正在加载流程定义…"
            error-title="流程定义加载失败"
            :error-text="loadError"
            :error-cause="loadErrorCause"
            @retry="reloadFlow"
          >
            <FlowTree :nodes="flow.nodes" :path-prefix="[]" />
          </StateBlock>
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
                    <el-option v-for="s in assigneeSources" :key="s.value" :label="s.label" :value="s.value"
                               :disabled="isSourceDisabled(s.value)" />
                  </el-select>
                  <div v-if="isFirstApproveNode" class="first-node-hint">
                    首个审批节点不支持「角色 / 发起人自选」：引擎在发起时解析不到该变量，流程会一发起就报错（校验规则 V-3）
                  </div>
                </el-form-item>
                <!--
                  指定人员：自绘 chip + 受控弹窗（§3.3 方案 A）。
                  为什么不用 `form/FormUserSelect`：它没有 `value` prop（v-model 是空操作），
                  内部 id 数组 / 对象数组两套语义混用，且触发区是 `<div @click>`
                  （既改不动共享契约，也无法自动化验证）。这里父组件的 id 数组是唯一真源，
                  弹窗只负责"选"，触发按钮是 `<el-button>`（可被自动化点击）。
                -->
                <el-form-item v-if="currentNode.assignee.source === 'USER'" label="指定人员">
                  <div class="assignee-picker">
                    <span
                      v-for="u in assigneeUserChips"
                      :key="u.userId"
                      class="assignee-chip"
                      :title="u.userId"
                    >
                      <el-avatar v-if="avatarUrl(u.avatar)" :size="20" :src="avatarUrl(u.avatar)" />
                      <span class="chip-name">{{ u.nickName }}</span>
                      <el-button
                        type="text"
                        size="mini"
                        class="chip-remove"
                        :title="'移除 ' + u.nickName"
                        @click="removeAssigneeUser(u.userId)"
                      >×</el-button>
                    </span>
                    <span v-if="!assigneeUserChips.length" class="assignee-empty">未选择人员</span>
                    <el-button size="mini" icon="el-icon-user" class="assignee-pick" @click="openUserPicker">选择人员</el-button>
                  </div>
                  <div v-if="isSingleMode" class="tip">
                    当前「多人方式」是<b>单人</b>：指定人员只能选 <b>1 个人</b>；在弹窗里多勾的人不会被保存（只留第 1 个）。
                  </div>
                  <div v-else class="tip">当前「多人方式」是<b>会签 / 或签 / 依次</b>：可以指定多人。</div>
                  <div class="tip">
                    弹窗「用户列表」最前面固定有<b>超级管理员</b>（标记「常驻」）——
                    它不在部门树里、平台选人接口也默认排除它，这里专门放出来方便测试。
                  </div>
                  <div v-if="userListLoading" class="tip">正在加载用户清单…</div>
                  <div v-if="userListError" class="tip warn">
                    <i class="el-icon-warning-outline" /> 用户清单加载失败：{{ userListError }}
                    <el-button type="text" size="mini" @click="loadUserList">重试</el-button>
                  </div>
                  <div v-else-if="usersNotFound.length" class="tip warn">
                    <i class="el-icon-warning-outline" /> 这些已选人员查不到（id 原样保留，不会被丢掉）：
                    {{ usersNotFound.join('、') }}
                    <el-button type="text" size="mini" @click="loadUserList">重试</el-button>
                  </div>
                </el-form-item>
                <!--
                  角色：改成**角色列表多选**。存的仍是 `role_id`（不是 roleKey）——
                  实测运行期 `FlowCommonService#getRoleUserIds` 拿编译出的 expId（= roleIds）
                  去 `sys_user_role.role_id` 关联用户，写 roleKey 会一个人都选不出来。
                -->
                <el-form-item v-if="currentNode.assignee.source === 'ROLE'" label="角色">
                  <el-select
                    v-model="assigneeRoleIds"
                    multiple
                    filterable
                    collapse-tags
                    style="width:100%"
                    placeholder="从角色列表中选择（可多选）"
                    :loading="roleListLoading"
                  >
                    <el-option v-for="r in roleOptions" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
                  </el-select>
                  <div v-if="roleListLoading" class="tip">正在加载角色列表…</div>
                  <div v-else-if="roleListError" class="tip warn">
                    <i class="el-icon-warning-outline" /> 角色列表加载失败：{{ roleListError }}
                    <el-button type="text" size="mini" @click="loadRoleList">重试</el-button>
                  </div>
                  <div v-else-if="unknownRoleIds.length" class="tip warn">
                    已存角色在当前系统里查不到（原值保留、不会被丢掉）：{{ unknownRoleIds.join('、') }}
                  </div>
                </el-form-item>
                <el-form-item v-if="needLevel(currentNode)" label="层级">
                  <el-input-number v-model="currentNode.assignee.level" :min="1" :max="5" />
                </el-form-item>

                <el-form-item label="多人方式">
                  <el-radio-group v-model="currentNode.multiMode" @change="onMultiModeChange">
                    <el-radio label="SINGLE">单人</el-radio>
                    <el-radio label="AND">会签（全部同意）</el-radio>
                    <el-radio label="OR">或签（一人同意）</el-radio>
                    <el-radio label="SEQ">依次审批</el-radio>
                  </el-radio-group>
                  <div v-if="isSingleMode" class="tip">
                    单人：任务直接派给<b>一个人</b>；指定人员只能留 1 个（多勾的会被收敛为第 1 个）。
                  </div>
                  <div v-else class="tip">
                    会签 / 或签 / 依次：任务按参与人列表建成多实例；「指定人员」必须<b>显式选人</b>
                    （角色 / 部门负责人等来源发布时会报错）。
                  </div>
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

              <!-- 进入条件：图形化编辑器直接内嵌（原"编辑条件"弹窗已合并到这里） -->
              <el-form-item v-if="!selectedBranch.defaultBranch" label="进入条件">
                <ConditionEditor :branch="selectedBranch" :field-options="conditionFieldOptions"
                  :field-label-map="fieldLabelMap" :field-options-map="fieldOptionsMap" />
              </el-form-item>
              <el-form-item v-else label="进入条件">
                <div class="tip">其余条件都不满足时进入本分支（系统自动生成，不可编辑）</div>
              </el-form-item>

              <el-form-item label="分支内节点">
                <span>{{ (selectedBranch.nodes || []).length }} 个</span>
                <span v-if="!(selectedBranch.nodes || []).length" class="tip warn">　空分支会被发布校验拦截</span>
              </el-form-item>

              <el-form-item label="显示">
                <el-button type="text" size="mini" @click="toggleLane(selection.path)">
                  {{ isCollapsed(selection.path) ? '展开本分支' : '折叠本分支' }}
                </el-button>
                <span class="tip">　泳道头不再放按钮，折叠入口收在这里</span>
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
          <div v-if="formError" class="tip warn" style="padding:6px 0">
            <i class="el-icon-warning-outline" /> 表单字段加载失败：{{ formError }}
            <el-button type="text" size="mini" @click="reloadForm">重试</el-button>
            <br />
            ⚠ 此时字段清单是空的，它会**跳过发布校验里的字段级检查**（条件字段是否必填、并行分支引用的多选字段是否存在）。
            请先恢复字段再发布。
          </div>
          <div v-else-if="!formDef.list.length" class="tip" style="padding:6px 0">
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

    <!-- 配置抽屉内已内嵌 ConditionEditor，独立的编辑条件弹窗已移除 -->

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

    <!--
      选人弹窗（§3.3 方案 A）：只做"选"，不持有最终状态 ——
      确认后由 `onUserPicked` 把 **user_id** 写回打开弹窗时锁定的那个节点，
      显示（chip）再由 id 反查姓名。共享组件 `org/UserAllSelect` 未被改动，
      `form/FormUserSelect` 也一行未动。

      `:type` 随节点的「多人方式」切换（2026-10-05 修复）：
        - 单人（SINGLE）→ `single`：共享组件把 `el-checkbox-group` 的 `:max` 置 1，
          勾第 2 个人会被 element-ui 直接拒掉；「全选 / 清空」按钮也自动隐藏；
        - 会签 / 或签 / 依次 → `multiple`：勾选上限 999999（多实例要的就是多人）。
      此前这里写死 `type="multiple"`，所以"多人方式=单人"的节点**照样能勾一堆人**，
      编译期人数 >1 会退化成 `candidateUsers`（候选人抢单）—— 与"单人"语义矛盾。
    -->
    <user-all-select
      :open.sync="userPickerOpen"
      :type="userPickerType"
      :selected-users="pickerSelectedUsers"
      :pinned-users="pickerPinnedUsers"
      @confimUser="onUserPicked"
    />
  </div>
</template>

<script>
import ConditionEditor from '../ConditionEditor'
import FlowTree from '../FlowTree'
import StateBlock from '@/components/StateBlock'
import { describeError } from '@/utils/errorMessage'
import {
  getSimpleFlow,
  saveSimpleFlowDraft,
  validateSimpleFlow,
  previewSimpleFlow,
  publishSimpleFlow,
  historySimpleFlow,
  rollbackSimpleFlow,
  getOrCreateByTemplate
} from '@/api/workflow/simpleFlow'
import { listDynamicForm, getDynamicForm } from '@/api/workflow/dynamicForm'
import { getUsers, getUser } from '@/api/system/user'
import { listRole } from '@/api/system/role'
import UserAllSelect from '@/components/org/UserAllSelect/index.vue'
import { fieldLabelMap, formatConditionText } from '../conditionText'
import { validateFlowKey } from '../flowKey'
import {
  assigneePatch,
  cleanIds,
  missingRoleIds,
  missingUserIds,
  singleModeUserIds,
  summarizeNames,
  toRoleObjects,
  toUserIds,
  toUserObjects
} from '../assigneeMapping'
import {
  extractFormFields,
  extractFieldOptions,
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
 *
 * 2.0（B1 §6.1/§6.2/§6.3/§6.4）把它改成**可复用组件**：
 *  - 宿主可以是独立路由页（`designer.vue`，行为与升级前一致），
 *    也可以是模板编辑页的「流程设计」页签（`embedded=true`）；
 *  - 内嵌时宿主注入 `templateId`/`formId`/`flowId`：流程名称/流程标识/关联表单
 *    三个输入**隐藏并改为只读展示**（表单来源固定取模板的 formId，不给切换入口）；
 *  - 内嵌时进入本组件即调用 `by-template/{templateId}` 取或建草稿（幂等，不覆盖已有草稿）；
 *  - 草稿自动保存（30 秒定时 + 窗口失焦两个触发，按 id 幂等 upsert）；
 *  - 保存/发布结果通过事件抛给宿主，宿主据此区分
 *    「模板已保存 / 流程未发布 / 发布失败可重试」三种状态。
 */
export default {
  name: 'FlowDesigner',
  components: { ConditionEditor, FlowTree, StateBlock, UserAllSelect },
  props: {
    /** 内嵌模式：隐藏流程名称/流程标识/关联表单三个输入，改为只读展示 */
    embedded: {
      type: Boolean,
      default: false
    },
    /** 宿主模板ID（内嵌时必填）：进入即按它取/建草稿 */
    templateId: {
      type: String,
      default: ''
    },
    /** 表单ID：内嵌时字段来源固定取它（不再手选表单） */
    formId: {
      type: String,
      default: ''
    },
    /** 已绑定的流程ID：宿主若已知可直接传入，省一次取/建 */
    flowId: {
      type: String,
      default: ''
    }
  },
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
      /**
       * 拖拽排序的"身份锚点"：拖动开始时记下当前选中对象与已折叠泳道对象，
       * 拖放结束后按**对象身份**重新解析它们的路径（见 reorderStart / reorderEnd）。
       */
      reorderAnchor: null,
      /** 配置抽屉开关：选中节点/分支时自动弹出 */
      drawerVisible: false,
      saving: false,
      publishing: false,
      /* ---- 2.0（B1 §4.5/§6.3）：草稿自动保存状态 ---- */
      autoSaveTimer: null,
      autoSaving: false,
      lastAutoSaveText: '',
      /* ---- 2.0（B1 §6.4）：宿主用的状态机（模板已保存 / 流程未发布 / 发布失败可重试） ---- */
      flowState: 'draft',
      previewVisible: false,
      previewXml: '',
      historyVisible: false,
      historyList: [],
      formOptions: [],
      /** 关联表单下拉本身加载失败时的原因（此前失败=静默的空下拉） */
      formOptionsError: '',
      /** 字段清单加载失败的原因（此前失败=静默的 0 字段，会让发布校验跳过字段级检查） */
      formError: '',
      /** 流程定义加载中 / 加载失败（此前 loadFlow 没有 catch，失败就是一片空白画布） */
      loadingFlow: false,
      loadError: '',
      loadErrorCause: '',
      /** 关联的表单及其字段清单（从表单 schema 自动提取，不再手敲字段名） */
      formDef: { id: '', name: '', list: [], options: {} },
      /* ---- 选人 / 选角色（§3.3 方案 A：父组件状态是唯一真源） ---- */
      /** 选人弹窗开关（受控：`:open.sync`） */
      userPickerOpen: false,
      /**
       * 弹窗打开时**锁定的目标节点**（对象引用，不是下标）。
       * 确认后只写回它 —— 防止"切节点后回调把人员写到另一个节点上"这类跨节点写入。
       */
      pickerTargetNode: null,
      /** 系统用户清单（chip 显示姓名用；`getUsers` 无权限门槛但不含超管，缺的按 id 补查） */
      allUsers: [],
      userListLoading: false,
      userListError: '',
      /** 按 id 也查不到的已选人员（原值仍保留在流程里，只是显示占位） */
      usersNotFound: [],
      /** 已发出"按 id 补查"请求的 id：清单与流程两条加载路径都会触发补查，靠它去重 */
      usersRequested: [],
      /**
       * 「指定人员」弹窗里**常驻候选**的 userId（2026-10-05）。
       *
       * 超管账号在 `/system/user/getUsers` 与 `/system/user/listDeptUser` 两条链路的 SQL 里
       * 都被**硬排除**（`u.user_id != 'superAdmin'`），而且它挂在公司根节点上 ——
       * 结果是流程设计器**根本选不到超管**，而超管正是最常用的流程测试账号。
       * 这里声明"哪些账号必须常驻候选"，由本组件按 id 取回后交给共享弹窗（`pinnedUsers`），
       * **不改共享 SQL**（那条 SQL 影响日程/知识库授权/转办等所有选人入口）。
       * 以后要再钉别的账号（如某个只做测试的账号），往这个数组里加 id 即可。
       */
      assigneePinnedUserIds: ['superAdmin'],
      /** 角色清单（「角色」多选的候选；来自 `/system/role/list`） */
      roleOptions: [],
      roleListLoading: false,
      roleListError: '',
      /** 角色清单是否已成功加载过（区分"没加载"与"加载到 0 个"） */
      roleListLoaded: false,
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
    /**
     * 当前选中的节点是不是「发起节点之后的第 1 个审批节点」。
     *
     * 为什么要单独判它：该节点上「角色 / 发起人自选」是**引擎层就跑不通**的 ——
     * 它们会编译成 flowable:candidateUsers="${节点id_user}"，而 startFlow 不传这个变量，
     * 结果是流程一发起就报 Unknown property used in expression（PRD 5.5 P-1）。
     * 所以这里置灰给原因，发布校验 V-3 再拦一道。
     */
    isFirstApproveNode() {
      const nodes = (this.flow && this.flow.nodes) || []
      const first = nodes.find(n => this.isApprovable(n))
      return !!first && !!this.currentNode && this.currentNode.id === first.id
    },
    /** 画布三态：加载中 / 加载失败 / 正常（空态交给 FlowTree 自己的 .ft-empty） */
    canvasState() {
      if (this.loadingFlow) return 'loading'
      if (this.loadError) return 'error'
      return 'ready'
    },
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
    /** vModel → label：条件文字只显示 label（查不到回退 vModel） */
    fieldLabelMap() {
      return fieldLabelMap(this.formDef.list)
    },
    /** vModel → 选项清单：把条件里的值显示成选项名（如 1 → 经营） */
    fieldOptionsMap() {
      return this.formDef.options || {}
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
    /** 当前节点已选人员 → chip 显示用的对象数组（查不到也显示占位，不静默丢人） */
    assigneeUserChips() {
      const a = this.currentNode && this.currentNode.assignee
      return toUserObjects((a && a.userIds) || [], this.allUsers)
    },
    /**
     * 当前节点「多人方式」是不是**单人**。
     *
     * 未设置按 `SINGLE` 处理 —— 与编译器的口径一致（`StringUtils.defaultIfBlank(multiMode, M_SINGLE)`），
     * 避免"旧数据没这个字段 ⇒ 界面上当成多人"这种前后端不一致。
     */
    isSingleMode() {
      const n = this.currentNode
      return !n || !n.multiMode || n.multiMode === 'SINGLE'
    },
    /**
     * 流程标识的**格式**问题（空串 = 合法）。
     * 口径与新建弹窗、后端 `SimpleFlowValidator` V-0 完全一致（都来自 `flowKey.js` 的 `validateFlowKey`）。
     * 唯一性这里判不了，仍由服务端 `saveDraft` 拦。
     */
    keyFormatError() {
      return validateFlowKey(this.flow.defKey)
    },
    /**
     * 选人弹窗的选人类型（共享组件 `org/UserAllSelect` 的 `type` prop）：
     * `single` ⇒ 勾选上限 1（`:max="1"`）、隐藏「全选 / 清空」；`multiple` ⇒ 上限 999999。
     * 取的是**打开弹窗时锁定的那个节点**（`pickerTargetNode`）的多人方式，
     * 与写入目标同一个节点，避免"按当前选中节点算、写到另一个节点"。
     */
    userPickerType() {
      const node = this.pickerTargetNode || this.currentNode
      if (!node) return 'multiple'
      return !node.multiMode || node.multiMode === 'SINGLE' ? 'single' : 'multiple'
    },
    /**
     * 要交给共享弹窗的**常驻候选人对象**（`assigneePinnedUserIds` 按 id 取回的那些）。
     * 取不到时返回空数组 —— 弹窗退化成原行为，不会因此报错或白屏。
     */
    pickerPinnedUsers() {
      return toUserObjects(this.assigneePinnedUserIds, this.allUsers)
    },
    /** 弹窗打开时锁定的节点所对应的人员 = 弹窗内的初始勾选态（与写入目标同一个节点） */
    pickerSelectedUsers() {      const node = this.pickerTargetNode || this.currentNode
      const a = node && node.assignee
      const ids = cleanIds((a && a.userIds) || [])
      /* 单人节点上"已选 >1 人"只可能来自旧数据或"先多选、后切单人"。
         整批带进弹窗、确认后却只留第 1 个，界面就成了"勾了 3 个只剩 1 个"。
         所以按多人方式分流：非 SINGLE 全部回显；单人 / 未设置只回显第 1 个。 */
      if (!ids.length) return []
      if (node && node.multiMode && node.multiMode !== 'SINGLE') return toUserObjects(ids, this.allUsers)
      return toUserObjects(ids.slice(0, 1), this.allUsers)
    },
    /**
     * 当前节点已选角色（`el-select multiple` 直接绑 id 数组）。
     * 写回前用 `cleanIds` 去空去重 —— 这些 id 会被引擎直接 `join(",")` 写进 BPMN。
     */
    assigneeRoleIds: {
      get() {
        const a = this.currentNode && this.currentNode.assignee
        return cleanIds((a && a.roleIds) || [])
      },
      set(ids) {
        const node = this.currentNode
        if (!node) return
        this.ensureAssignee(node).roleIds = cleanIds(ids)
      }
    },
    /** 已存但角色清单里查不到的 roleId（原值保留，但要让用户看得见） */
    unknownRoleIds() {
      if (!this.roleListLoaded || this.roleListError) return []
      return missingRoleIds(this.assigneeRoleIds, this.roleOptions)
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
        toggleLane: p => vm.toggleLane(p),
        isSys: n => vm.isSys(n),
        removeNode: p => vm.removeNodeAt(p),
        // 同链拖拽排序：画布在拖前/拖后各回调一次，宿主负责按对象身份重算路径
        reorderStart: () => vm.reorderStart(),
        reorderEnd: () => vm.reorderEnd()
      }
    }
  },
  created() {
    this.loadFormOptions()
    // 选人/选角色的候选清单：进页面就拉（失败只在该来源的表单项下可见，不打扰无关操作）
    this.loadUserList()
    this.loadPinnedUsers()
    this.loadRoleList()
    if (this.embedded) {
      // 内嵌（模板页签，B1 §6.3）：按模板取或建草稿；字段来源固定取 template.formId
      this.initEmbedded()
      return
    }
    const id = this.flowId || this.$route.query.id
    if (id) {
      this.loadFlow(id)
    } else {
      this.initEmptyFlow()
    }
  },
  mounted() {
    if (this.embedded) {
      this.startAutoSave()
      window.addEventListener('blur', this.onWindowBlur)
    }
  },
  beforeDestroy() {
    this.stopAutoSave()
    window.removeEventListener('blur', this.onWindowBlur)
  },
  methods: {
    /* ================= 内嵌（模板页签）生命周期 ================= */

    /**
     * 内嵌模式初始化（B1 §6.3）：进入页签即按模板**取或建**流程草稿。
     *
     * 幂等性由后端保证（`GET/POST /workflow/simple-flow/by-template/{templateId}`：
     * 已绑定就返回原草稿，不覆盖），前端连续进入不会造出第二条草稿。
     */
    initEmbedded() {
      if (!this.templateId) {
        this.flow.nodes = []
        this.$emit('state-change', { state: 'no-template' })
        return
      }
      this.loadingFlow = true
      getOrCreateByTemplate(this.templateId)
        .then(res => {
          const data = (res && res.data) || {}
          this.loadingFlow = false
          this.applyFlow(data)
          this.$emit('bound', { flowId: data.id, defKey: data.defKey, status: data.status, version: data.version })
          this.$emit('state-change', { state: data.status === '1' ? 'published' : 'draft' })
          // 字段来源固定取模板的 formId（§6.2：不给切换表单的入口）
          this.loadEmbeddedForm(data)
        })
        .catch(err => {
          this.loadingFlow = false
          this.loadError = describeError(err).text
          this.loadErrorCause = describeError(err).cause
          this.$emit('state-change', { state: 'load-failed', message: this.loadError })
        })
    },

    /** 内嵌模式下把接口回显的草稿灌进本地模型 */
    applyFlow(data) {
      this.flow.id = data.id || null
      this.flow.defKey = data.defKey || ''
      this.flow.name = data.name || ''
      this.flow.category = data.category || ''
      this.flow.status = data.status || '0'
      this.flow.version = data.version || 0
      this.flow.deployId = data.deployId || null
      const def = this.parseContent(data.content)
      this.flow.nodes = (def && def.nodes) || []
      // 旧内容可能缺 userIds/roleIds（甚至整个 assignee）——进编辑器前补齐成规范形状
      this.normalizeAssignees()
      this.selection = { path: [] }
      this.drawerVisible = false
    },

    /** 内嵌模式：从草稿里还原表单，取不到就退回宿主给的 formId */
    loadEmbeddedForm(data) {
      const def = this.parseContent(data && data.content)
      const fid = (def && (def.formId || (def.formDef && def.formDef.id))) || this.formId
      if (fid) {
        this.loadForm(fid)
      }
    },

    /** 安全解析流程 JSON（内容可能为空/损坏） */
    parseContent(content) {
      if (!content) return null
      try {
        return typeof content === 'string' ? JSON.parse(content) : content
      } catch (e) {
        return null
      }
    },

    /* ================= 草稿自动保存（B1 §4.5 / §6.3） ================= */

    /** 30 秒定时自动保存 */
    startAutoSave() {
      this.stopAutoSave()
      this.autoSaveTimer = setInterval(() => this.autoSaveDraft('定时'), 30000)
    },
    stopAutoSave() {
      if (this.autoSaveTimer) {
        clearInterval(this.autoSaveTimer)
        this.autoSaveTimer = null
      }
    },
    /** 窗口失焦时也存一次（用户切走去做别的事时最可能丢配置） */
    onWindowBlur() {
      this.autoSaveDraft('失焦')
    },
    /**
     * 自动保存：与手动保存走**同一个** `/draft` 端点，按 `flow.id` 幂等 upsert，
     * 不产生第二条草稿；静默失败（不弹错误打断用户，下次触发会再试一次），
     * 但会在顶部状态里留痕，避免"以为存上了"。
     */
    autoSaveDraft(reason) {
      if (!this.flow.id) return
      if (this.saving || this.publishing) return
      if (!this.buildContent) return
      this.autoSaving = true
      saveSimpleFlowDraft({
        id: this.flow.id,
        name: this.flow.name,
        category: this.flow.category,
        content: this.buildContent()
      }).then(() => {
        this.autoSaving = false
        this.lastAutoSaveText = new Date().toLocaleTimeString()
        this.$emit('auto-saved', { reason, at: this.lastAutoSaveText })
      }).catch(err => {
        this.autoSaving = false
        this.lastAutoSaveText = '自动保存失败（' + describeError(err).text + '）'
        this.$emit('auto-save-failed', { reason, message: describeError(err).text })
      })
    },

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
    /* ---------------- 拖拽排序：重排后按对象身份重解析路径 ---------------- */
    /**
     * 拖动开始：把「当前选中对象」和「已折叠的泳道对象」按**引用**记下来。
     * 不能只记下标 —— 重排后下标必然变，过期的 selection.path 会静默打开另一个节点的配置面板。
     */
    reorderStart() {
      this.reorderAnchor = {
        selection: this.resolvePath(this.selection.path || []),
        collapsed: Object.keys(this.collapsedLanes)
          .filter(k => this.collapsedLanes[k])
          .map(k => this.resolvePath(k.split('-').map(Number)))
          .filter(Boolean)
      }
    },
    /**
     * 拖动结束：遍历整棵树，按对象身份把它们的新路径写回。
     * 顺序：先修 selection.path（决定右侧配置面板指向谁），再重建 collapsedLanes
     * （否则泳道折叠标记会留在旧下标上，表现为"另一条泳道莫名其妙是收起的"）。
     */
    reorderEnd() {
      const anchor = this.reorderAnchor
      this.reorderAnchor = null
      if (!anchor) return
      if (anchor.selection) {
        const path = this.findPathOf(anchor.selection)
        this.selection = { path: path || this.safeSelectionPath() }
      }
      if (anchor.collapsed.length) {
        const next = {}
        anchor.collapsed.forEach(br => {
          const path = this.findPathOf(br)
          if (path) next[this.laneKey(path)] = true
        })
        this.collapsedLanes = next
      }
    },
    /**
     * 在整棵树里按**对象身份**找路径。
     * 返回值与 resolvePath 的入参同构（偶数位=节点下标、奇数位=泳道下标）；找不到返回 null。
     */
    findPathOf(target) {
      if (!target) return null
      const walk = (nodes, prefix) => {
        for (let i = 0; i < nodes.length; i++) {
          const node = nodes[i]
          const np = prefix.concat([i])
          if (node === target) return np
          const branches = node.branches || []
          for (let j = 0; j < branches.length; j++) {
            const br = branches[j]
            const bp = np.concat([j])
            if (br === target) return bp
            const hit = walk(br.nodes || [], bp)
            if (hit) return hit
          }
        }
        return null
      }
      return walk(this.flow.nodes || [], [])
    },
    /** 锚点找不到时的安全落点：退回顶层第一个节点；树为空则什么都不选（绝不留在过期下标上） */
    safeSelectionPath() {
      return this.flow.nodes && this.flow.nodes.length ? [0] : []
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
    /* ---------------- 表单字段（自动提取） ---------------- */
    loadFormOptions() {
      this.formOptionsError = ''
      listDynamicForm({ pageNum: 1, pageSize: 200 }).then(res => {
        this.formOptions = (res.rows || []).map(r => ({ id: r.id, name: r.name }))
      }).catch(err => {
        // 关联表单下拉拉不到 = 后面所有字段驱动的功能都不可用，必须说出来
        this.formOptionsError = describeError(err).text
      })
    },
    onFormChange(formId) {
      if (formId) {
        this.loadForm(formId)
      } else {
        this.formDef = { id: '', name: '', list: [] }
        this.formError = ''
      }
    },
    loadForm(formId) {
      this.formError = ''
      getDynamicForm(formId).then(res => {
        const d = res.data || {}
        this.formDef.id = d.id
        this.formDef.name = d.name
        this.formDef.list = extractFormFields(d.content)
        this.formDef.options = extractFieldOptions(d.content)
      }).catch(err => {
        // 关键：不能让"拉字段失败"退化成"这个表单没有字段"。
        // 后者会让发布校验静默跳过字段级检查，等于放行了本该拦下的流程。
        this.formDef.list = []
        this.formError = describeError(err).text
      })
    },
    reloadForm() {
      this.formError = ''
      if (this.formDef.id) {
        this.loadForm(this.formDef.id)
      } else {
        this.loadFormOptions()
      }
    },
    /* ================= 选人 / 选角色（§3.3 方案 A） ================= */
    /**
     * 把节点的 assignee 补齐成规范形状。
     *
     * 为什么必须 `$set`：旧流程内容里可能整个 `assignee` 都没有（或只有 source），
     * 而 Vue2 对"新增属性"不做响应式 —— 直接 `node.assignee.userIds = []` 补出来的字段
     * 在界面上永远不会更新（`audit-undeclared-writes` 门禁盯的就是这类）。
     */
    ensureAssignee(node) {
      const patch = assigneePatch(node.assignee)
      if (patch.assignee) {
        this.$set(node, 'assignee', patch.assignee)
      } else {
        Object.keys(patch).forEach(k => this.$set(node.assignee, k, patch[k]))
      }
      return node.assignee
    },
    /** 全量归一化（加载流程后调用一次）：后续任何写路径都不会踩到"字段不存在" */
    normalizeAssignees() {
      const walk = nodes => {
        (nodes || []).forEach(n => {
          if (n.assignee || this.isApprovable(n)) this.ensureAssignee(n)
          /* 旧数据可能"多人方式=单人"却存了多人（历史上弹窗写死 multiple 造成的）。
             这里只**在内存里**收敛成 1 个：用户看到的就是落库会得到的，
             不静默改库、也不出现"界面上挂着 3 个 chip 的单人节点"。 */
          if (n.assignee && Array.isArray(n.assignee.userIds)) {
            const one = singleModeUserIds(n.multiMode, n.assignee.userIds)
            if (one.length !== cleanIds(n.assignee.userIds).length) {
              this.$set(n.assignee, 'userIds', cleanIds(n.assignee.userIds).slice(0, 1))
            }
          }
          ;(n.branches || []).forEach(br => walk(br.nodes))
        })
      }
      walk(this.flow.nodes)
      // ⚠ 补查必须在这里也做一次：`getUsers` 常常比流程详情先返回，
      // 那一刻 flow.nodes 还是空的（算出来"没有缺人"），只有流程到位后才知道要补谁。
      this.fillMissingUsers()
      // 常驻候选人同理：它可能正是流程里已选的人（超管最常见的用法），流程到位后再确认一次
      this.loadPinnedUsers()
    },
    /** 头像地址（相对路径补 baseApi）；没有头像返回空串，模板就不渲染 el-avatar */
    avatarUrl(avatar) {
      if (!avatar) return ''
      if (/^https?:\/\//.test(avatar)) return avatar
      return (process.env.VUE_APP_BASE_API || '') + avatar
    },
    /**
     * 系统用户清单：chip 要显示**姓名**而不是 id。
     * `/system/user/getUsers` 无权限门槛，但**不含超管**（实测只返回 4 人），
     * 缺的人按 id 用 `GET /system/user/{userId}` 补查；补查失败必须显式提示（不做空 catch）。
     */
    loadUserList() {
      this.userListLoading = true
      this.userListError = ''
      getUsers().then(res => {
        this.userListLoading = false
        this.allUsers = (res && res.data) || []
        this.fillMissingUsers()
      }).catch(err => {
        this.userListLoading = false
        // 拉不到清单 ≠ 没人选：chip 会退化成"id（用户不存在）"，所以原因必须说出来
        this.userListError = describeError(err).text
      })
    },
    /** 已选但不在清单里的 userId：逐个补查；查不到就记下来显式提示 */
    fillMissingUsers() {
      const missing = missingUserIds(this.allAssigneeUserIds(), this.allUsers)
        .filter(id => this.usersRequested.indexOf(id) < 0)
      missing.forEach(id => {
        this.usersRequested.push(id)
        getUser(id).then(res => {
          const u = res && res.data
          if (u && u.userId) {
            this.allUsers.push(u)
          } else {
            this.forgetRequested(id)
            this.noteMissingUser(id)
          }
        }).catch(() => {
          // 失败要把 id 放回"可重试"，否则点「重试」时会被当成"已经查过了"
          this.forgetRequested(id)
          this.noteMissingUser(id)
        })
      })
    },
    forgetRequested(id) {
      const i = this.usersRequested.indexOf(id)
      if (i >= 0) this.usersRequested.splice(i, 1)
    },
    noteMissingUser(id) {
      if (this.usersNotFound.indexOf(id) < 0) this.usersNotFound.push(id)
    },
    /** 取回**常驻候选人**（超管）并入用户清单 —— 否则「指定人员」弹窗里没有这个账号可勾。
     *  走 `GET /system/user/{userId}`（它不排除超管），取不到就静默跳过、弹窗退化为原行为。 */
    loadPinnedUsers() {
      ;(this.assigneePinnedUserIds || []).forEach(id => {
        if (!id || missingUserIds([id], this.allUsers).length === 0) return
        if (this.usersRequested.indexOf(id) >= 0) return
        this.usersRequested.push(id)
        getUser(id).then(res => {
          const u = res && res.data
          if (u && u.userId) {
            this.allUsers.push(u)
            this.fillMissingUsers()
          } else {
            this.forgetRequested(id)
          }
        }).catch(() => {
          this.forgetRequested(id)
        })
      })
    },
    /** 整棵流程树里所有 USER 节点的 userIds（去重） */
    allAssigneeUserIds() {
      const ids = []
      const walk = nodes => {
        (nodes || []).forEach(n => {
          const arr = n.assignee && n.assignee.userIds
          if (Array.isArray(arr)) {
            arr.forEach(id => { if (id && ids.indexOf(id) < 0) ids.push(id) })
          }
          ;(n.branches || []).forEach(br => walk(br.nodes))
        })
      }
      walk(this.flow.nodes)
      return ids
    },
    /** 打开选人弹窗：**锁定当前节点**（确认后只写回它，避免跨节点写入） */
    openUserPicker() {
      if (!this.currentNode) return
      this.pickerTargetNode = this.currentNode
      this.userPickerOpen = true
    },
    /**
     * 切换「多人方式」后的写入口径收敛：单人 ⇒ 指定人员只留 1 个。
     *
     * 为什么要在切换的当下就收敛，而不是等保存：`multiMode` 与 `userIds` 是两个独立字段，
     * "多选 3 人 → 改成单人"如果不收敛，卡片上会一直挂着 3 个 chip（用户以为自己选了 3 个人
     * 还能是"单人"）。切换即收敛，界面与落库立刻一致；被去掉的人明确提示出来。
     */
    onMultiModeChange(mode) {
      const node = this.currentNode
      if (!node || !node.assignee) return
      const before = cleanIds(node.assignee.userIds)
      if (mode === 'SINGLE' && before.length > 1) {
        this.ensureAssignee(node).userIds = [before[0]]
        const kept = toUserObjects([before[0]], this.allUsers)
        const dropped = toUserObjects(before.slice(1), this.allUsers)
        this.$message.warning(
          '「单人」只能指定一个人，已保留「' + summarizeNames(kept) + '」，' +
          '移除「' + summarizeNames(dropped, 99) + '」；如需多人请选择会签 / 或签 / 依次'
        )
      } else {
        this.ensureAssignee(node).userIds = before
      }
    },
    /**
     * 选人确认：只写 **user_id**（引擎只认 id），并把这些用户并进本地清单，
     * 免得刚选完的人因为"不在 getUsers 清单里"瞬间变成"（用户不存在）"。
     *
     * ⚠ 写入**必须按多人方式收敛**：弹窗自身已是单选（`:type="single"` ⇒ `:max="1"`），
     *   但旧数据 / 手工构造的请求仍可能带回多人，而"单人节点存了 2 个人"在编译期会退化成
     *   `candidateUsers`（候选人抢单）—— 所以这里再挡一道，不依赖界面。
     */
    onUserPicked(users) {
      const target = this.pickerTargetNode
      this.userPickerOpen = false
      this.pickerTargetNode = null
      if (!target) return
      this.ensureAssignee(target).userIds = singleModeUserIds(target.multiMode, toUserIds(users))
      this.mergeUsers(users)
    },
    /** 把弹窗回传的用户并入本地清单（含超管等 `getUsers` 不返回的账号） */
    mergeUsers(users) {
      ;(users || []).forEach(u => {
        if (!u || !u.userId) return
        if (missingUserIds([u.userId], this.allUsers).length) this.allUsers.push(u)
      })
    },
    /** 移除一位已选人员（chip 上的 ×） */
    removeAssigneeUser(userId) {
      const node = this.currentNode
      if (!node) return
      const a = this.ensureAssignee(node)
      a.userIds = (a.userIds || []).filter(id => id !== userId)
    },
    /**
     * 角色清单（`/system/role/list`）。
     * 存的必须是 **role_id**：实测运行期 `FlowCommonService#getRoleUserIds` 拿编译出的
     * expId（= roleIds）去 `sys_user_role.role_id` 关联用户，写 roleKey 会一个人都选不出来。
     */
    loadRoleList() {
      this.roleListLoading = true
      this.roleListError = ''
      listRole({ pageNum: 1, pageSize: 200 }).then(res => {
        this.roleListLoading = false
        this.roleListLoaded = true
        this.roleOptions = (res && res.rows) || []
      }).catch(err => {
        this.roleListLoading = false
        this.roleListError = describeError(err).text
      })
    },
    /** 画布节点摘要里的人员/角色名（查不到显示占位，不隐藏） */
    assigneeSummary(node) {
      const a = (node && node.assignee) || {}
      if (a.source === 'USER') return summarizeNames(toUserObjects(a.userIds || [], this.allUsers))
      if (a.source === 'ROLE') return summarizeNames(toRoleObjects(a.roleIds || [], this.roleOptions))
      return ''
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
      this.loadingFlow = true
      this.loadError = ''
      this.loadErrorCause = ''
      getSimpleFlow(id).then(res => {
        const d = res.data || {}
        this.flow.id = d.id
        this.flow.defKey = d.defKey
        this.flow.name = d.name
        this.flow.category = d.category
        this.flow.status = d.status
        this.flow.version = d.version
        try {
          const content = JSON.parse(d.content)
          this.flow.nodes = content.nodes || []
          // 旧内容可能缺 userIds/roleIds（甚至整个 assignee）——补齐后再让配置面板读它
          this.normalizeAssignees()
          if (content.formId) {
            this.formDef.id = content.formId
            this.loadForm(content.formId)
          }
        } catch (e) {
          // 内容不是合法 JSON 属于"数据坏了"，不是"没有节点"——用错误态而不是空态
          this.$modal.msgError('流程内容不是合法 JSON')
          this.flow.nodes = []
          this.loadError = '流程定义内容不是合法 JSON。'
          this.loadErrorCause = '该流程的 content 字段已损坏，请从版本历史回滚到可用版本。'
        }
        this.selection = { path: [0] }
        this.loadingFlow = false
      }).catch(err => {
        this.flow.nodes = []
        const d = describeError(err)
        this.loadError = d.text
        this.loadErrorCause = d.cause
        this.loadingFlow = false
      })
    },
    /** 画布错误态的「重试」：按当前路由重新拉一次 */
    reloadFlow() {
      const id = this.flow.id || this.$route.query.id
      if (!id) {
        this.loadError = ''
        this.initEmptyFlow()
        return
      }
      this.loadFlow(id)
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

    condText(branch) {
      // 显示口径集中在 conditionText.js（只显示字段 label、不显示 vModel；值按选项名显示）
      return formatConditionText(branch, this.fieldLabelMap, this.fieldOptionsMap)
    },
    /* ---------------- 保存 / 发布 ---------------- */
    /**
     * 清理条件里的空行与空组。
     * 条件编辑器改为内嵌即时编辑后不再有"确定"按钮兜底，
     * 用户新加但没填完的行会留在内存树里，提交前必须清掉。
     */
    pruneConditions() {
      const walk = nodes => {
        (nodes || []).forEach(n => {
          if (n.type === 'condition' && n.branches) {
            n.branches.forEach(b => {
              if (!b.groups) return
              b.groups = b.groups
                .map(g => ({ logic: 'AND', rows: (g.rows || []).filter(r => r.field && r.op) }))
                .filter(g => g.rows.length)
            })
          }
          if (n.branches) {
            n.branches.forEach(b => walk(b.nodes))
          }
        })
      }
      walk(this.flow.nodes)
    },
    buildContent() {
      this.pruneConditions()
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
    /**
     * 把**当前画布**存成草稿（不弹成功提示，由调用方决定怎么反馈）。
     *
     * ⚠ 为什么必须抽出来：`/validate` 是按 **id 读库里的 content** 校验的（后端口径），
     * 它**看不到**画布上还没保存的改动。所以"发布"必须先把这个存下去再校验 ——
     * 否则会出现"画布上明明有 4 个审批节点，却报『只有 1 个审批节点』"这种
     * **校验结果与眼前画布对不上**的现象（根因：校验的是上一次保存的旧内容）。
     */
    saveDraftQuietly() {
      return saveSimpleFlowDraft({
        id: this.flow.id,
        name: this.flow.name,
        category: this.flow.category,
        content: this.buildContent()
      }).then(res => {
        if (res.data && res.data.id) {
          this.flow.id = res.data.id
        }
        return res
      })
    },
    handleSaveDraft() {
      if (!this.flow.name) return this.$modal.msgError('请填写流程名称')
      if (!this.flow.defKey) return this.$modal.msgError('请填写流程标识')
      // 格式问题本地先拦一次（口径与后端 V-0 相同），别让用户等到服务端才知道敲错了
      if (this.keyFormatError) return this.$modal.msgError('流程标识不可用：' + this.keyFormatError)
      this.saving = true
      this.saveDraftQuietly().then(() => {
        this.saving = false
        this.$modal.msgSuccess('草稿已保存')
        this.flowState = 'draft'
        this.$emit('saved', { flowId: this.flow.id, defKey: this.flow.defKey })
        this.$emit('state-change', { state: 'draft' })
      }).catch(err => {
        // 原来只把 saving 置回 false —— 保存失败用户看不到任何提示，会以为存上了
        this.saving = false
        this.$modal.msgError('草稿保存失败：' + describeError(err).text)
        this.$emit('save-failed', { message: describeError(err).text })
      })
    },
    handlePublish() {
      if (!this.flow.id) {
        return this.$modal.msgError('请先保存草稿')
      }
      // ① **先存当前画布**：`/validate` 与 `/publish` 都按 id 读库里的内容，
      //    不先落盘就会出现"校验的是旧内容 ⇒ 报的问题在画布上找不到"（本轮修的就是这个）。
      this.publishing = true
      this.saveDraftQuietly().then(() => {
        this.publishing = false
        this.validateThenPublish()
      }).catch(err => {
        this.publishing = false
        this.$modal.msgError('发布前保存草稿失败：' + describeError(err).text)
      })
    },
    /** 校验 → 有阻断就列出来；只有提示项则确认后发布 */
    validateThenPublish() {
      // 把问题一次性列出来
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
          // 内嵌时把 templateId 交给后端：发布成功后**同一事务**回写模板三列（B1 §4.2）
          const payload = { id: this.flow.id, remark: '设计器发布' }
          if (this.embedded && this.templateId) {
            payload.templateId = this.templateId
          }
          publishSimpleFlow(payload).then(r => {
            this.publishing = false
            this.flow.status = r.data.status
            this.flow.version = r.data.version
            this.flow.deployId = r.data.deployId
            this.$modal.msgSuccess('发布成功 v' + r.data.version)
            this.flowState = 'published'
            this.$emit('published', {
              flowId: this.flow.id,
              defKey: r.data.defKey || this.flow.defKey,
              version: r.data.version,
              templateId: this.templateId
            })
            this.$emit('state-change', { state: 'published' })
          }).catch(err => {
            // 原来这里静默重置 publishing：点了发布，没反应也没报错
            this.publishing = false
            this.$modal.msgError('发布失败：' + describeError(err).text)
            // §6.4：发布失败必须留下"可重试"的显式状态，不能是静默悬空态
            this.flowState = 'publish-failed'
            this.$emit('publish-failed', {
              message: describeError(err).text,
              flowId: this.flow.id,
              templateId: this.templateId
            })
            this.$emit('state-change', { state: 'publish-failed', message: describeError(err).text })
          })
        }
        if (warns.length) {
          // 外层 catch 只吃掉"用户点了取消"，发布失败由 doPublish 内部报出来
          this.$confirm(warns.map(w => '· ' + w.message).join('<br/>'), '存在提示项，是否继续发布？', {
            dangerouslyUseHTMLString: true,
            type: 'warning'
          }).then(doPublish).catch(() => {})
        } else {
          doPublish()
        }
      }).catch(err => {
        // 校验接口本身失败：不能当成"校验通过"就往下发布
        this.$modal.msgError('发布前校验未完成：' + describeError(err).text)
      })
    },
    /**
     * 校验问题里的 nodeId → 画布上的节点名（含分支内节点）。
     *
     * 为什么需要：V-2/V-4/V-7 的 message 里**已经带了节点名**，但前面还会拼一个 `节点 n_first：`
     * —— 节点被改过名时（如 `n_first` 现在叫「发起部门审核」），只念 id 会让人在画布上找不到是哪一个。
     * 所以这里按当前画布反查名字，并把"找不到"也显式说出来（那种情况通常意味着内容不同步）。
     */
    nodeNameById(nodeId) {
      let found = ''
      const walk = nodes => {
        (nodes || []).forEach(n => {
          if (!found && n && n.id === nodeId) found = n.name || ''
          ;(n.branches || []).forEach(br => walk(br.nodes))
        })
      }
      walk(this.flow.nodes)
      return found
    },
    showIssues(blocks, warns) {
      const html = []
      if (blocks.length) {
        html.push('<b>阻断项（必须修复）</b>')
        blocks.forEach(b => {
          if (!b.nodeId) {
            html.push('· [' + b.rule + '] ' + b.message)
            return
          }
          // message 里已含「节点名」，这里只补一句"在画布上是哪个节点"，避免只看到 id
          const name = this.nodeNameById(b.nodeId)
          const where = name ? '（画布上叫「' + name + '」）' : '（当前画布上找不到这个节点，请重新保存后再校验）'
          html.push('· [' + b.rule + '] ' + b.message + where)
        })
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
      }).catch(err => {
        this.$modal.msgError('编译预览失败：' + describeError(err).text)
      })
    },
    handleHistory() {
      if (!this.flow.defKey) return this.$modal.msgError('尚未设置流程标识')
      historySimpleFlow(this.flow.defKey).then(res => {
        this.historyList = res.data || []
        this.historyVisible = true
      }).catch(err => {
        this.$modal.msgError('版本历史加载失败：' + describeError(err).text)
      })
    },
    handleRollback(row) {
      this.$confirm('将以 v' + row.version + ' 的内容重新发布（生成新版本，历史不可改），是否继续？', '提示', {
        type: 'warning'
      }).then(() => {
        // 内层 catch 单独接住回滚失败；外层 catch 只负责"用户取消"
        return rollbackSimpleFlow({ defKey: this.flow.defKey, version: row.version }).then(res => {
          this.$modal.msgSuccess('已回滚并发布为 v' + res.data.version)
          this.historyVisible = false
          this.loadFlow(this.flow.id)
        }).catch(err => {
          this.$modal.msgError('回滚失败：' + describeError(err).text)
        })
      }).catch(() => {})
    },
    /* ---------------- 工具 ---------------- */
    isSys(node) {
      return node.type === 'start' || node.type === 'end'
    },
    isApprovable(node) {
      return node.type === 'approve' || node.type === 'handle'
    },
    /**
     * 参与人来源在「首个审批节点」上是否禁用（PRD AC-06）。
     * 与发布校验 V-3 前后呼应：这里置灰并给出原因，V-3 在发布时二次拦截。
     */
    isSourceDisabled(value) {
      if (!this.isFirstApproveNode) return false
      return value === 'ROLE' || value === 'INITIATOR_SELECT'
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
      // 画布上直接显示选了谁（人名字/角色名）——否则"选了人"这件事只能在配置抽屉里看到
      const picked = this.assigneeSummary(node)
      const multi = { SINGLE: '单人', AND: '会签', OR: '或签', SEQ: '依次' }[node.multiMode] || ''
      const sign = node.signMode === 'REQUIRED' ? ' · 需签名' : ''
      return src + (picked ? '（' + picked + '）' : '') + ' · ' + multi + sign
    }
  }
}
</script>

<style lang="scss" scoped>
.simple-flow-designer {
  /* 首个审批节点的限制提示：说明为什么这两项是灰的（不是 bug，是引擎限制） */
  .first-node-hint {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-color-warning, #e6a23c);
  }
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
    /* 内嵌模式（模板页签，B1 §6.2）：流程名称/流程标识/关联表单改为只读展示 */
    .ro-text {
      font-size: 13px;
      color: #303133;
      padding: 0 4px;
      &.mono {
        font-family: Consolas, Monaco, monospace;
        color: #606266;
      }
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
  /*
   * 旧的「缩进清单」布局样式（.node-wrap / .node-card / .insert-line / .branch-box /
   * .branch-item / .branch-head / .cond-chip / .tag-default / .branch-body / .sub-node
   * 以及只在它们下面嵌套的 .idx / .nm / .summary / .sub-sum / .branch-name / .fr）
   * 已全部搬到 FlowTree.vue 的画布样式里，designer.vue 模板中零引用，故删除。
   */
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
  /* 选人 chip（§3.3 方案 A）：父组件自绘，不依赖共享选人组件的内部状态 */
  .assignee-picker {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
  }
  .assignee-chip {
    display: inline-flex;
    align-items: center;
    height: 24px;
    padding: 0 2px 0 6px;
    background: #f5faff;
    border: 1px solid #d6e8ff;
    border-radius: 12px;
    font-size: 12px;
    line-height: 24px;
    .chip-name {
      margin-left: 4px;
      max-width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .chip-remove {
      padding: 0 4px;
      margin-left: 0;
      color: #909399;
      font-size: 13px;
      line-height: 1;
      &:hover {
        color: #f56c6c;
      }
    }
  }
  .assignee-empty {
    color: #c0c4cc;
    font-size: 12px;
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
