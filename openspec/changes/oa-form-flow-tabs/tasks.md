# Tasks

> 批次 B1（2.1 交付）。每个任务后的括号内是**完成判定**：具体命令或可观察行为。
> 命令约定：后端构建 `mvn -B -DskipTests -pl ruoyi-admin clean package`；后端运行必须用
> `& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-admin\target\ruoyi-admin.jar`；
> 前端 `npm.cmd run dev`；静态审计 `node tools/audit/run-all.js`；越权 `tools/authz-check.ps1`；
> 流程回归 `tools/flow-regression.ps1`（见 `DEV-ENV.md` §4）。

## 1. 前置：模板更新语义修正与数据迁移（必须先完成，阻塞其余各组）

- [x] 1.1 把 `TemplateServiceImpl.updateTemplate` 改为原地 UPDATE 同一行（`id` 不变），不再"旧行置停用+删除、插入新 UUID"。完成判定：编辑一个模板后，其 `id` 与编辑前一致；`t_template` 中不新增行。
- [x] 1.2 写存量体检 SQL：找出同一模板的多个版本行（旧行 `del_flag='1'`/`enable_flag='0'` 与新启用行的对应关系），并输出**影响行数报告**（涉及多少模板、多少子表引用行）。完成判定：报告脚本对库副本执行后产出可读的行数统计，且不修改任何数据。
- [x] 1.3 写存量归并迁移脚本：把 5 张 `template_id` 外键表（`t_template_print_template`、`t_template_node_field_auth`，以及本组后续新增的 4 张表预留位置）的引用统一到启用行；迁移前把映射写入快照表 `t_template_migrate_backup`。完成判定：在库副本上执行后，任一模板的打印模板配置与节点字段权限在编辑前后仍可读到（不再失联）。
- [x] 1.4 写按快照表回滚的脚本并实测一次。完成判定：执行回滚后 5 张子表的引用恢复到迁移前状态（用 1.2 的体检 SQL 比对为 0 处异常）。
- [x] 1.5 为该语义补一条服务层测试：编辑模板后断言 `id` 不变、且既有子表关联仍指向它。完成判定：测试通过，且故意改回"换 ID"实现时该测试失败（自证有效）。
- [x] 1.6 在 `DEV-ENV.md` 记录本次迁移的执行顺序、影响行数报告位置与回滚步骤。完成判定：按文档步骤能在库副本上完整走通迁移与回滚。

## 2. 数据模型：`t_template` 增列与 4 张新表

- [x] 2.1 写增量 SQL：`t_template` 增 13 列（`icon`、`submit_scope_type`、`include_child_dept`、`simple_flow_id`、`flow_mode`、`builtin_print_key`、`revoke_limit_minutes`、`allow_edit_after_submit`、`allow_submit_for_other`、`allow_approver_revoke`、`allow_batch_approve`、`approver_dedup`、`forward_scope`），全部带默认值（默认值见 PRD 第 8.1 节）。完成判定：在库副本上可重复执行；执行后存量行全部有合法值。
- [x] 2.2 写增量 SQL：新增 `t_template_submit_scope`、`t_template_flow_admin`、`t_template_related_approval`、`t_workflow_related_approval` 四张表（字段与索引见 PRD 第 8.2 节）。完成判定：可重复执行；`t_workflow_related_approval` 的 `business_id` / `related_business_id` 两个索引存在（`SHOW INDEX` 可见）。
- [x] 2.3 同步 `t_template` 增列的**五处**：`Template.java` 实体、`TemplateMapper.xml` 的 resultMap、select 列、insert 列、update 列。完成判定：新增模板并填写 `icon`/`submit_scope_type`/`approver_dedup` 后查库可见非默认值；漏掉任一处时该断言失败。
- [x] 2.4 把二开增量 SQL 编排进 `sql/初始化-全部.sql`（按序执行全部二开增量）。完成判定：在空库上按该脚本顺序执行后，`t_template` 的 13 列与四张新表均存在。

## 3. 后端：可发起范围与流程管理员（对齐 `workflow/submit-scope`）

- [x] 3.1 `TemplateDTO` 增 `submitScope`/`flowAdmins` 字段，并在保存链路里与既有 `handleAttachment`/`handleMainText`/`handleMessageNotice` 并列实现写入（全量替换语义）。完成判定：提交含两类明细的模板后，查 `t_template_submit_scope` 与 `t_template_flow_admin` 得到与提交一致的记录数。
- [x] 3.2 实现范围过滤：`TemplateServiceImpl.listNewStartTemplate` 注入 `SecurityUtils` 的 userId/deptId/roleIds，在**分组之前**过滤；部门范围用 `sys_dept.ancestors` 前缀匹配并受 `include_child_dept` 控制；`superAdmin` 不受范围限制。完成判定：单元测试覆盖"全员可见 / 角色命中 / 部门命中 / 部门下级命中（默认）/ 关掉下级后不命中 / 管理员全可见"六种情形，全部通过。
- [x] 3.3 实现发起前兜底校验：发起落库前校验当前用户对该模板的可发起权限，无权返回 403 且不落库。完成判定：以无权限账号直接 POST 发起接口，返回 403；查库确认无新增 `t_workflow_form` 与流程实例记录。
- [x] 3.4 实现流程管理员授权校验：被指定者可编辑/发布该模板的流程；未指定时回退到创建人 + 拥有模板编辑权限者；无授权者调用发布接口返回 403。完成判定：三种身份的接口返回分别符合预期（成功/成功/403），且失败时流程定义与模板绑定均未变化。
- [x] 3.5 修掉"无分类模板被静默丢弃"：`type` 为空的模板归入「未分类」组返回。完成判定：建一个无分类的启用模板，调用 `newStart` 接口，该模板出现在返回结果中。
- [x] 3.6 在 `tools/authz-check.ps1` 补三条断言（越权发起 403、越权发布 403、浮窗越权 403）。完成判定：脚本执行通过；把服务端校验注释掉后脚本失败（自证有效）。
- [x] 3.7 在 `DEV-ENV.md` 记录新增权限点与范围过滤的排错方法（如何确认"模板没出现是因为范围还是因为无分类"）。完成判定：按文档能在本地复现并区分两种原因。

## 4. 后端：流程绑定与发布回写（对齐 `workflow/template-flow-binding`）

- [x] 4.1 新增 `GET/POST /workflow/simple-flow/by-template/{templateId}`：有绑定则返回其流程草稿，无则按 `tpl_` + 模板 id 前 8 位生成 `defKey` 并创建草稿、回写 `simple_flow_id`/`flow_mode`。完成判定：对同一模板连续调用两次，返回同一个流程 id（不重复创建）。
- [x] 4.2 发布接口接 `templateId`，发布成功后在同一事务内回写 `t_template.def_key` / `simple_flow_id` / `flow_mode`。完成判定：在模板内发布流程后，查库三个字段均已写入；随后用该模板发起一张单据，流程按刚发布的定义流转。
- [x] 4.3 回写必须落在当前启用行上（与 1.1 的原地更新语义一致）。完成判定：先编辑模板再发布流程，回写后 `t_template_print_template` 仍能按该模板查到配置（未被切到废弃行）。
- [x] 4.4 实现发布失败的显式处理：部署成功但业务写入失败时回滚部署，或留下可重试的显式状态；不得出现静默悬空态。完成判定：注入一次业务写入异常，观察结果为"要么已回滚、要么界面给出可重试状态"，且不会出现"界面说发布了但发起时报找不到流程"。
- [x] 4.5 实现流程草稿自动保存（30 秒与失焦两个触发），按 `id` 幂等 upsert。完成判定：修改节点后不点保存离开再返回，配置仍在；连续两次自动保存不产生第二行流程草稿。
- [x] 4.6 在 `tools/audit/` 新增静态审计脚本「流程↔模板绑定」，检查 `by-template` 与发布回写链路存在、`defKey` 派生规则只在唯一处定义。完成判定：`node tools/audit/run-all.js` 全绿；含 `--selftest` 自检通过。
- [x] 4.7 在 `tools/` 新增回写链路回归脚本（真实环境：建模板 → 取流程草稿 → 发布 → 校验三列回写 → 发起一张单据）。完成判定：脚本对运行中的环境执行通过，且断言覆盖 4.1–4.4。

## 5. 前端：四页签壳、基础信息与更多设置（对齐 `workflow/form-definition`）

- [x] 5.1 把 `views/workflow/template/add.vue` 改为四页签容器（`el-tabs` + 四个 `el-tab-pane`），删除 `activeNames`/`activeName` 死字段。完成判定：页面出现四个页签，切换任意顺序后可保存；`grep -n "activeNames" src/views/workflow/template/add.vue` 无结果。
- [x] 5.2 实现逐页签校验：按页签顺序校验，失败即切换到该页签并高亮首个错误字段。完成判定：故意让「更多设置」的必填项为空后点保存，界面自动切到该页签并高亮该字段。
- [x] 5.3 基础信息页签增「图标」选择器（预设图标集，默认第一个）与「说明」输入（≤500 字）；「分组」保持复用既有模板分类。完成判定：新建模板不选图标时保存后图标为第一个；发起页卡片显示该图标。
- [x] 5.4 基础信息页签增「谁可以提交该审批」（全员/指定人员/指定角色/指定部门 + 明细）+「包含下级部门」开关；移除「关联流程」手选下拉，改为只读展示绑定结果。完成判定：四个类型均可保存并回显；`grep -n "关联流程" src/views/workflow/template/basic-info.vue` 不再出现可编辑下拉。
- [x] 5.5 基础信息页签增「流程管理员」多选。完成判定：保存后重新打开该模板，流程管理员回显一致。
- [x] 5.6 更多设置页签承载正文/附件/消息通知三组既有配置（原样搬入，字段与校验不变）。完成判定：三组字段与升级前一致，且保存后 `t_template_main_text`/`t_template_attachment`/`t_template_message_notice` 的写入结果与升级前相同。
- [x] 5.7 更多设置页签增提交与审批策略组（7 项，默认值见 design D11）。完成判定：新建模板不触碰策略项时，保存后的库值与默认值一致；把"提交后 30 分钟可撤销"配置生效后用超时请求验证被拒。
- [x] 5.8 发起页 `newstart/index.vue` 支持「未分类」分组展示，并区分"没有模板"与"没有你可发起的模板"两种空态。完成判定：在只有无分类模板时页面显示「未分类」组；在范围过滤后为空时显示第二种空态文案。

## 6. 前端：设计器组件化与内嵌（对齐 `workflow/template-flow-binding`）

- [x] 6.1 把 `simple-flow/designer.vue` 的「工具条 + 画布 + 配置抽屉」抽成可复用组件，宿主以 props 注入 `templateId`/`formId`/`flowId`。完成判定：独立路由页在改造后功能与改造前等价（节点增删改、条件编辑、保存、发布、版本历史全部可用）。
- [x] 6.2 在四页签的「流程设计」页签内嵌该组件，隐藏流程名称与关联表单两个输入（改为只读展示），字段来源固定取 `template.formId`。完成判定：在页签内完成一次"加节点 → 保存 → 发布"，全程无页面跳转；界面无切换表单的入口。
- [x] 6.3 接入 `by-template` 接口（进入页签时取或建草稿）与自动保存。完成判定：首次进入自动创建草稿并展示自动生成的流程标识；重新进入载入同一草稿。
- [x] 6.4 接入发布与回写结果的界面反馈，区分"模板已保存/流程未发布/发布失败可重试"三种状态。完成判定：分别在三种情形下观察界面提示与可用的下一步操作。
- [x] 6.5 在 `tools/audit/` 新增静态审计脚本「模板四页签重构」（校验四个页签容器与逐页签校验入口存在；校验设计器不再以独立路由作为唯一入口）。完成判定：`node tools/audit/run-all.js` 全绿；`--selftest` 通过。

## 7. 前端 + 后端：「关联审批」控件十一处落地（对齐 `workflow/related-approval`）

- [x] 7.1 新建 `components/form/design/DesignRelatedApproval.vue`：可远程搜索候选单据、可只读展示已选单据号列表。完成判定：在拟稿页可选、在只读视图可展示，空值时显示占位而不报错。
- [x] 7.2 在 `components/render/render.js` 完成 import 与 `components` 登记。完成判定：设计器画布中拖入该控件后正常渲染，控制台无"未知组件"警告。
- [x] 7.3 在 `utils/generator/config.js` 的 `formOaComponents` 中加入该控件定义。完成判定：设计器左侧「合同二开组件」分组中出现「关联审批」。
- [x] 7.4 在 `views/tool/build/RightPanel.vue` 增加属性配置：候选模板多选（只列已启用模板）、是否允许多条、突出展示字段。完成判定：选中控件后右侧出现三项配置；候选下拉中停用模板不出现。
- [x] 7.5 在 `views/workflow/flow-form/component/view-form.vue` 适配只读视图（渲染成可点击的单据号列表）。完成判定：审批页与详情页能看到已关联单据，点击可打开浮窗。
- [x] 7.6 后端 `ComponentTypeEnum` 补枚举、`BizFormServiceImpl.convertValueToLabel` 补分支（值 → 单据号文本）。完成判定：日志中不再出现"未支持的组件类型"告警；列表与打印取到的是单据号而不是标识串。
- [x] 7.7 在 `utils/formSchema.js` 的 `NON_CONDITION_TAGS` 加入该控件 tag；确认打印侧 `isLayoutOnly` **不**包含它。完成判定：流程条件字段下拉中不出现该控件；打印件对应栏目有内容。
- [x] 7.8 新增两个后端接口：候选单据列表（本分组 + 本人已发起 + 命中候选模板）与浮窗只读详情（表单数据 + 审批状态，含越权校验）。完成判定：他人发起的单据、跨分组单据、无权限单据三种请求分别不出现在候选或在详情接口返回 403。
- [x] 7.9 关联关系落库与反查接口（含单据号快照）。完成判定：发起时选择两条单据后，`t_workflow_related_approval` 新增两行；按被关联单据可反查到宿主单据。
- [x] 7.10 处理动态表单版本化：页签 2 保存表单后，把 `t_template.form_id` 显式指向新的启用版本并给出提示。完成判定：在既有模板上新增该控件并保存后，发起页能看到该控件（若未回写则该断言失败）。
- [x] 7.11 在 `tools/` 新增控件四态回归脚本（设计器能拖 / 拟稿能填 / 审批能看 / 打印能出）。完成判定：脚本对运行中的环境执行通过，四项断言齐全。

## 8. 集成检查（只做跨组确认，不补前面各组欠下的测试与文档）

- [x] 8.1 端到端走通"新建单据"闭环：新建模板 → 填基础信息 → 设计表单（含关联审批）→ 页签 3 设计并发布流程 → 保存 → 在发起页按范围看到它 → 发起 → 审批人点开关联审批浮窗 → 打印。完成判定：全链路无手工抄写流程标识，无 500/白屏，打印件含被关联单据号。
- [x] 8.2 存量回归：对一个升级前创建的模板执行发起 → 审批 → 打印全链路，确认可见范围、审批链路与打印件版式与升级前一致。完成判定：无差异；该模板未被要求回填任何新字段。
- [x] 8.3 越权矩阵实测：无权限发起、无权限发布、浮窗越权、跨分组关联四种越权路径全部返回 403 或不返回数据。完成判定：`tools/authz-check.ps1` 与新增断言全部通过。
- [x] 8.4 门禁全绿：`node tools/audit/run-all.js`、`tools/authz-check.ps1`、`tools/sign-feature-check.ps1`、`tools/flow-regression.ps1` 依次执行通过。完成判定：四个命令退出码均为 0（环境性失败须先修复环境再重跑，不得记为通过）。
- [x] 8.5 回滚演练：按 `DEV-ENV.md` 记录的顺序执行一次数据回滚（快照表还原）+ 代码回退，确认系统回到升级前可用状态。完成判定：回滚后能正常发起并审批一张存量模板单据。
