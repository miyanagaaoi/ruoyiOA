-- ============================================================================
-- OA 2.0 · B1 §2.2 新增 4 张表（**可重复执行**）
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 2.2）｜需求：REQ-PERM-001、REQ-DESIGN-013、
--         REQ-FORM-010、REQ-DATA-006｜验收：AC-48、AC-53、AC-68
--
-- 字段与索引口径见 doc/2.0/2.0-PRD-OA升级开发.md 第 8.2 节。
-- 幂等：`CREATE TABLE IF NOT EXISTS`；重复执行不改已有表结构。
--
-- 排序规则：显式取 `t_template.id` 的**实际**排序规则。
--   本机业务列是 utf8mb4_0900_ai_ci，而库默认是 utf8mb4_general_ci；新表若随库默认建，
--   之后与 t_template 做关联（如"按模板查可发起范围明细"）会报
--   ERROR 1267 Illegal mix of collations。
--
-- 用法：
--   Get-Content -Raw -Encoding UTF8 "<本文件>" | mysql --host=127.0.0.1 --user=root `
--     --database=rad_oa_b1copy --default-character-set=utf8mb4 --table
-- ============================================================================

DROP PROCEDURE IF EXISTS `sp_oa_b1_add_scope_tables`;

DELIMITER $$
CREATE PROCEDURE `sp_oa_b1_add_scope_tables`()
BEGIN
    DECLARE v_coll VARCHAR(64);

    SELECT COLLATION_NAME INTO v_coll
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'id'
     LIMIT 1;
    SET v_coll = IFNULL(v_coll, 'utf8mb4_general_ci');

    -- ---------- 1. 可发起范围明细（多态：按范围类型只有一列有值） ----------
    SET @ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `t_template_submit_scope` (',
        '  `id`          varchar(64) NOT NULL COMMENT ''主键'',',
        '  `template_id` varchar(64) NOT NULL COMMENT ''模板ID'',',
        '  `scope_type`  char(1)     NOT NULL COMMENT ''1-人员 2-角色 3-部门'',',
        '  `target_id`   varchar(64) NOT NULL COMMENT ''人员ID/角色ID/部门ID'',',
        '  `create_id`   varchar(64) DEFAULT NULL,',
        '  `create_time` datetime    DEFAULT NULL,',
        '  PRIMARY KEY (`id`),',
        '  UNIQUE KEY `uk_tpl_scope_target` (`template_id`,`scope_type`,`target_id`),',
        '  KEY `idx_tpl` (`template_id`)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll, ' COMMENT=''模板可发起范围明细''');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ---------- 2. 流程管理员（谁可以编辑这条模板的流程） ----------
    SET @ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `t_template_flow_admin` (',
        '  `id`          varchar(64) NOT NULL COMMENT ''主键'',',
        '  `template_id` varchar(64) NOT NULL COMMENT ''模板ID'',',
        '  `user_id`     varchar(64) NOT NULL COMMENT ''被指定的账号ID'',',
        '  `create_id`   varchar(64) DEFAULT NULL,',
        '  `create_time` datetime    DEFAULT NULL,',
        '  PRIMARY KEY (`id`),',
        '  UNIQUE KEY `uk_tpl_user` (`template_id`,`user_id`),',
        '  KEY `idx_user` (`user_id`)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll, ' COMMENT=''模板流程管理员''');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ---------- 3. 关联审批控件的候选模板（管理员指定） ----------
    SET @ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `t_template_related_approval` (',
        '  `id`                varchar(64) NOT NULL COMMENT ''主键'',',
        '  `template_id`       varchar(64) NOT NULL COMMENT ''宿主模板ID'',',
        '  `field_vmodel`      varchar(64) NOT NULL COMMENT ''控件在表单里的 __vModel__'',',
        '  `allow_template_id` varchar(64) NOT NULL COMMENT ''允许被关联的模板ID（必须 enable_flag=1）'',',
        '  `create_time`       datetime    DEFAULT NULL,',
        '  PRIMARY KEY (`id`),',
        '  UNIQUE KEY `uk_host_field_allow` (`template_id`,`field_vmodel`,`allow_template_id`)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll, ' COMMENT=''关联审批控件候选模板''');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ---------- 4. 关联关系（发起者实际选了哪些单据） ----------
    SET @ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `t_workflow_related_approval` (',
        '  `id`                  varchar(64) NOT NULL COMMENT ''主键'',',
        '  `business_id`         varchar(64) NOT NULL COMMENT ''宿主单据业务ID'',',
        '  `field_vmodel`        varchar(64) NOT NULL COMMENT ''控件 __vModel__'',',
        '  `related_business_id` varchar(64) NOT NULL COMMENT ''被关联单据业务ID'',',
        '  `related_template_id` varchar(64) DEFAULT NULL COMMENT ''被关联单据的模板ID'',',
        '  `related_business_no` varchar(64) DEFAULT NULL COMMENT ''被关联单据号（快照，便于列表展示）'',',
        '  `create_id`           varchar(64) DEFAULT NULL COMMENT ''发起者'',',
        '  `create_time`         datetime    DEFAULT NULL,',
        '  PRIMARY KEY (`id`),',
        '  KEY `idx_biz` (`business_id`),',
        '  KEY `idx_related` (`related_business_id`)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll, ' COMMENT=''关联审批关系（运行期）''');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ---------- 自检 ----------
    SELECT t.TABLE_NAME AS `表`, t.TABLE_COLLATION AS `排序规则`,
           (SELECT COUNT(*) FROM information_schema.STATISTICS s
             WHERE s.TABLE_SCHEMA = t.TABLE_SCHEMA AND s.TABLE_NAME = t.TABLE_NAME) AS `索引数`
      FROM information_schema.TABLES t
     WHERE t.TABLE_SCHEMA = DATABASE()
       AND t.TABLE_NAME IN ('t_template_submit_scope','t_template_flow_admin',
                            't_template_related_approval','t_workflow_related_approval')
     ORDER BY t.TABLE_NAME;

    -- 「4 张表齐备（应为4）」
    SELECT COUNT(*) AS `4张表齐备（应为4）`
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME IN ('t_template_submit_scope','t_template_flow_admin',
                          't_template_related_approval','t_workflow_related_approval');

    -- t_workflow_related_approval 的两个必须索引（任务 2.2 完成判定）
    SELECT INDEX_NAME AS `索引`, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS `列`
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_workflow_related_approval'
       AND INDEX_NAME IN ('idx_biz','idx_related')
     GROUP BY INDEX_NAME
     ORDER BY INDEX_NAME;
END$$
DELIMITER ;

CALL `sp_oa_b1_add_scope_tables`();
DROP PROCEDURE IF EXISTS `sp_oa_b1_add_scope_tables`;
