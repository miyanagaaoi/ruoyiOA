-- ============================================================================
-- 签名记录（二开）—— 表结构
-- 对应文档：doc/PRD-合同审批二开.md 第 8 章（REQ-SIGN-004）
-- 说明：
--   * 只新增表，不改动任何既有表，可安全地在业务库 rad_oa 执行；
--   * 本表**只追加**：不提供 update / delete 的 Mapper 方法，
--     撤签 = 追加一条撤销记录（PRD 8.5）；
--   * 预存签名表 `t_user_sign_preset`（REQ-SIGN-003）不在本脚本，
--     随"预存签名管理"一起提交 —— 不留没有代码使用的表。
-- ============================================================================

DROP TABLE IF EXISTS `t_workflow_sign_record`;
CREATE TABLE `t_workflow_sign_record` (
  `id`             varchar(64)  NOT NULL                COMMENT '主键',
  `business_id`    varchar(64)  NOT NULL                COMMENT '业务ID',
  `proc_ins_id`    varchar(64)  DEFAULT NULL            COMMENT '流程实例ID',
  `task_id`        varchar(64)  DEFAULT NULL            COMMENT '任务ID',
  `task_def_key`   varchar(64)  DEFAULT NULL            COMMENT '节点定义key',
  `node_name`      varchar(200) DEFAULT NULL            COMMENT '节点名称（=打印件签批栏标题）',
  `sign_type`      char(1)      NOT NULL                COMMENT '签名方式，0-手写，1-预存，2-印章，9-撤销',
  `file_id`        varchar(64)  DEFAULT NULL            COMMENT '签名图片文件ID',
  `sign_user_id`   varchar(64)  NOT NULL                COMMENT '签名人ID',
  `sign_user_name` varchar(50)  DEFAULT NULL            COMMENT '签名人姓名',
  `sign_time`      datetime     NOT NULL                COMMENT '签名时间',
  `sign_ip`        varchar(64)  DEFAULT NULL            COMMENT '签名IP',
  `user_agent`     varchar(500) DEFAULT NULL            COMMENT '浏览器UA',
  `form_data_hash` varchar(64)  DEFAULT NULL            COMMENT '签名时表单数据 SHA-256',
  `prev_hash`      varchar(64)  DEFAULT NULL            COMMENT '上一条记录哈希',
  `record_hash`    varchar(64)  NOT NULL                COMMENT '本条记录哈希（哈希链）',
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`business_id`),
  KEY `idx_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签名记录表（只追加，哈希链防篡改）';
