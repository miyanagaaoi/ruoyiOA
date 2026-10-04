-- ============================================================================
-- 用户预存签名（二开）—— 表结构
-- 对应文档：doc/PRD-合同审批二开.md §8.4（REQ-SIGN-003）、验收项 AC-29 / AC-30
-- 说明：
--   * 只新增表，不改动任何既有表，可安全地在业务库 rad_oa 执行；
--   * 本表原计划「随『预存签名管理』一起提交，不留没有代码使用的表」
--     （见 sql/二开-签名记录.sql 头部说明），本次随功能一并补齐；
--   * 约束：一人可多枚，**默认签名唯一** —— 设为默认时在同一事务里
--     把该用户其它记录一并置为非默认（服务端保证，不依赖前端）。
-- ============================================================================

DROP TABLE IF EXISTS `t_user_sign_preset`;
CREATE TABLE `t_user_sign_preset` (
  `id`          varchar(64)  NOT NULL                COMMENT '主键',
  `user_id`     varchar(64)  NOT NULL                COMMENT '用户ID',
  `name`        varchar(100) DEFAULT NULL            COMMENT '签名名称（如"常用签名"）',
  `file_id`     varchar(64)  NOT NULL                COMMENT '签名图片文件ID',
  `is_default`  char(1)      DEFAULT '0'             COMMENT '是否默认，0-否，1-是',
  `status`      char(1)      DEFAULT '1'             COMMENT '状态，0-停用，1-启用',
  `del_flag`    char(1)      DEFAULT '0'             COMMENT '删除标识，0-存在，2-删除',
  `create_id`   varchar(64)  DEFAULT NULL            COMMENT '创建者',
  `create_time` datetime     DEFAULT NULL            COMMENT '创建时间',
  `update_id`   varchar(64)  DEFAULT NULL            COMMENT '更新者',
  `update_time` datetime     DEFAULT NULL            COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户预存签名表（默认唯一）';
