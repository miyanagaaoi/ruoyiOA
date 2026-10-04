-- ============================================================================
-- 表单打印（二开）—— 表结构
-- 对应文档：doc/PRD-合同审批二开.md 第 7 章（REQ-PRINT-006 / REQ-PRINT-007）
-- 说明：
--   * 只新增表，不改动任何既有表，可安全地在业务库 rad_oa 执行；
--   * 签名相关的两张表（t_workflow_sign_record / t_user_sign_preset）不在本脚本，
--     随签名功能一起提交 —— 不想留下"没有代码使用的表"。
-- ============================================================================

DROP TABLE IF EXISTS `t_template_print_template`;
CREATE TABLE `t_template_print_template` (
  `id`             varchar(64)  NOT NULL                COMMENT '主键',
  `template_id`    varchar(64)  NOT NULL                COMMENT '单据模板ID（t_template.id）',
  `name`           varchar(200) DEFAULT NULL            COMMENT '打印模板名称',
  `paper`          varchar(20)  DEFAULT 'A4'            COMMENT '纸张',
  `orientation`    varchar(20)  DEFAULT 'portrait'      COMMENT '方向',
  `title`          varchar(200) DEFAULT NULL            COMMENT '单据标题（为空则取内置默认标题）',
  `logo_file_id`   varchar(64)  DEFAULT NULL            COMMENT 'Logo 文件ID',
  `field_map`      longtext                             COMMENT '版式字段映射 JSON（为空则用代码内置的系统模板）',
  `show_signature` char(1)      DEFAULT '1'             COMMENT '是否打印签名，0-否，1-是',
  `show_comment`   char(1)      DEFAULT '1'             COMMENT '是否打印意见，0-否，1-是',
  `show_attachment` char(1)     DEFAULT '1'             COMMENT '是否打印附件清单，0-否，1-是',
  `show_cc_node`   char(1)      DEFAULT '0'             COMMENT '抄送节点是否出栏，0-否，1-是',
  `watermark`      char(1)      DEFAULT '1'             COMMENT '是否加水印，0-否，1-是',
  `footer_note`    varchar(500) DEFAULT NULL            COMMENT '页脚备注',
  `enable_flag`    char(1)      DEFAULT '1'             COMMENT '是否启用，0-否，1-是',
  `del_flag`       char(1)      DEFAULT '0'             COMMENT '删除标识，0-未删除，1-已删除',
  `create_id`      varchar(64)  DEFAULT NULL            COMMENT '创建人ID',
  `create_by`      varchar(50)  DEFAULT NULL            COMMENT '创建人名称',
  `create_time`    datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_id`      varchar(64)  DEFAULT NULL            COMMENT '更新人ID',
  `update_by`      varchar(50)  DEFAULT NULL            COMMENT '更新人名称',
  `update_time`    datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_template` (`template_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印模板表（打印版式与字段映射）';

DROP TABLE IF EXISTS `t_print_log`;
CREATE TABLE `t_print_log` (
  `id`              varchar(64)  NOT NULL               COMMENT '主键',
  `business_id`     varchar(64)  NOT NULL               COMMENT '业务ID',
  `proc_ins_id`     varchar(64)  DEFAULT NULL           COMMENT '流程实例ID',
  `template_id`     varchar(64)  DEFAULT NULL           COMMENT '单据模板ID',
  `print_tpl_id`    varchar(64)  DEFAULT NULL           COMMENT '打印模板ID',
  `print_user_id`   varchar(64)  NOT NULL               COMMENT '打印人ID',
  `print_user_name` varchar(50)  DEFAULT NULL           COMMENT '打印人姓名',
  `print_time`      datetime     NOT NULL               COMMENT '打印时间',
  `print_ip`        varchar(64)  DEFAULT NULL           COMMENT '打印IP',
  `user_agent`      varchar(500) DEFAULT NULL           COMMENT '浏览器UA',
  `page_count`      int(11)      DEFAULT NULL           COMMENT '打印页数',
  `watermark_text`  varchar(200) DEFAULT NULL           COMMENT '水印文字',
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`business_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印日志表（只追加）';
