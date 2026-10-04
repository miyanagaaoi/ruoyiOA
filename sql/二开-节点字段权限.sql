-- ============================================================================
-- 节点级字段权限（二开）—— 表结构
-- 对应文档：doc/PRD-合同审批二开.md §6.6 / §9.1（REQ-DESIGN-006）、验收项 AC-12
-- 说明：
--   * 只新增表，不改动任何既有表，可安全地在业务库 rad_oa 执行；
--   * 设计器把「本节点只读字段」存进流程 JSON 的 node.fieldReadonly，
--     发布时**同时落库到本表**，用于**服务端强制**：
--     AC-12 要求「该节点表单中指定字段不可编辑，且**直接调接口也改不动**」——
--     仅靠前端置灰不算通过，必须有服务端校验，故需要本表。
-- ============================================================================

DROP TABLE IF EXISTS `t_template_node_field_auth`;
CREATE TABLE `t_template_node_field_auth` (
  `id`           varchar(64)  NOT NULL                COMMENT '主键',
  `template_id`  varchar(64)  NOT NULL                COMMENT '单据模板ID',
  `task_def_key` varchar(200) NOT NULL                COMMENT '节点定义key',
  `field_vmodel` varchar(200) NOT NULL                COMMENT '表单字段 __vModel__',
  `readonly`     char(1)      DEFAULT '0'             COMMENT '只读，0-否，1-是',
  `required`     char(1)      DEFAULT '0'             COMMENT '必填，0-否，1-是',
  `hidden`       char(1)      DEFAULT '0'             COMMENT '隐藏，0-否，1-是',
  `create_time`  datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_time`  datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_field` (`template_id`, `task_def_key`, `field_vmodel`),
  KEY `idx_tpl_node` (`template_id`, `task_def_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点级字段权限表（服务端强制）';
