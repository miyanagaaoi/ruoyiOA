-- ============================================================================
-- 简化流程设计器（二开）—— 表结构
-- 对应文档：doc/PRD-合同审批二开.md 第 6 章、第 9 章
-- 说明：只新增表，不改动任何既有表，可安全地在业务库 rad_oa 执行
-- ============================================================================

DROP TABLE IF EXISTS `t_flow_simple`;
CREATE TABLE `t_flow_simple` (
  `id`             varchar(64)  NOT NULL                COMMENT '主键',
  `def_key`        varchar(100) NOT NULL                COMMENT '流程定义key（部署到 Flowable 的 process id）',
  `name`           varchar(200) NOT NULL                COMMENT '流程名称',
  `category`       varchar(50)  DEFAULT NULL            COMMENT '流程分类',
  `content`        longtext                             COMMENT '简化流程 JSON（设计器产出）',
  `schema_version` int(11)      DEFAULT 1               COMMENT 'JSON 结构版本',
  `status`         char(1)      DEFAULT '0'             COMMENT '状态，0-草稿，1-已发布',
  `version`        int(11)      DEFAULT 0               COMMENT '已发布版本号（0 表示从未发布）',
  `deploy_id`      varchar(64)  DEFAULT NULL            COMMENT '最近一次部署ID',
  `proc_def_id`    varchar(64)  DEFAULT NULL            COMMENT '最近一次流程定义ID',
  `remark`         varchar(1000) DEFAULT NULL           COMMENT '备注',
  `del_flag`       char(1)      DEFAULT '0'             COMMENT '删除标识，0-未删除，1-已删除',
  `create_id`      varchar(64)  DEFAULT NULL            COMMENT '创建人ID',
  `create_by`      varchar(50)  DEFAULT NULL            COMMENT '创建人名称',
  `create_time`    datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_id`      varchar(64)  DEFAULT NULL            COMMENT '更新人ID',
  `update_by`      varchar(50)  DEFAULT NULL            COMMENT '更新人名称',
  `update_time`    datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_def_key` (`def_key`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简化流程定义表（设计器草稿/已发布）';

DROP TABLE IF EXISTS `t_flow_simple_history`;
CREATE TABLE `t_flow_simple_history` (
  `id`             varchar(64)  NOT NULL                COMMENT '主键',
  `def_key`        varchar(100) NOT NULL                COMMENT '流程定义key',
  `version`        int(11)      NOT NULL                COMMENT '版本号（自增，从 1 开始）',
  `name`           varchar(200) DEFAULT NULL            COMMENT '流程名称（发布时快照）',
  `category`       varchar(50)  DEFAULT NULL            COMMENT '分类（快照）',
  `content`        longtext                             COMMENT '简化流程 JSON（发布时快照，不可变）',
  `schema_version` int(11)      DEFAULT 1               COMMENT 'JSON 结构版本',
  `deploy_id`      varchar(64)  DEFAULT NULL            COMMENT '本次部署ID',
  `proc_def_id`    varchar(64)  DEFAULT NULL            COMMENT '本次流程定义ID',
  `publisher_id`   varchar(64)  DEFAULT NULL            COMMENT '发布人ID',
  `publisher_name` varchar(50)  DEFAULT NULL            COMMENT '发布人名称',
  `publish_time`   datetime     DEFAULT NULL            COMMENT '发布时间',
  `remark`         varchar(1000) DEFAULT NULL           COMMENT '发布备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_def_key_version` (`def_key`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简化流程发布历史（版本快照，只追加）';
