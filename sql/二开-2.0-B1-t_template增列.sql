-- ============================================================================
-- OA 2.0 · B1 §2.1 `t_template` 增 14 列（**可重复执行**）
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 2.1）｜需求：REQ-FORM-004/009、REQ-PERM-001、
--         REQ-DESIGN-012/013、REQ-PRINT-011｜验收：AC-45..AC-60
--
-- 说明：任务写的是"13 列"（PRD §8.1 的表格），实测核对后为 **14 列** ——
--       第 14 列是 `remark`（页面上的「说明」）：PRD 说"复用现有 remark 列"，
--       但本库 t_template 并没有这一列，见文件末尾那段的注释。
--
-- 口径：默认值一律取"等于升级前行为"（design D11 / `platform/delivery-gate` 的零回归要求），
--       这样存量模板在升级后**无需回填任何字段**即可继续用。
--
-- 幂等：MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，故逐列查 information_schema 后再加；
--       重复执行不会报错、不会改已有列。
--
-- 用法（先库副本，后业务库）：
--   Get-Content -Raw -Encoding UTF8 "<本文件>" | mysql --host=127.0.0.1 --user=root `
--     --database=rad_oa_b1copy --default-character-set=utf8mb4 --table
--   （中文文件名必须走 stdin 管道，原因见 DEV-ENV.md §8.2）
--
-- ⚠️ 五处同步：本文件只加数据库列，**实体/Mapper 的 5 处**见
--   `Template.java`、`TemplateMapper.xml` 的 resultMap / selectTemplateVo / insertTemplate / updateTemplate，
--   漏一处就"保存成功但字段静默丢失"（任务 2.3）。
--   `table.sql` / `data.sql` 保持上游原样不动（REQ-DATA-005 方案 B：增量文件是唯一真源）。
-- ============================================================================

DROP PROCEDURE IF EXISTS `sp_oa_b1_add_template_columns`;

DELIMITER $$
CREATE PROCEDURE `sp_oa_b1_add_template_columns`()
BEGIN
    DECLARE v_added INT DEFAULT 0;

    -- ---------- 基础信息（REQ-FORM-004 / REQ-PERM-001） ----------
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'icon') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `icon` varchar(64) DEFAULT NULL COMMENT '发起页卡片图标（预设图标集，默认第一个）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'submit_scope_type') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `submit_scope_type` char(1) DEFAULT '0' COMMENT '谁可以提交该审批：0-全员 1-指定人员 2-指定角色 3-指定部门'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'include_child_dept') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `include_child_dept` char(1) DEFAULT '1' COMMENT '部门范围是否包含下级部门：0-否 1-是（默认含）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    -- ---------- 流程绑定（REQ-DESIGN-012 / REQ-DESIGN-013） ----------
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'simple_flow_id') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `simple_flow_id` varchar(64) DEFAULT NULL COMMENT '绑定的简化流程草稿ID（t_flow_simple.id）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'flow_mode') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `flow_mode` char(1) DEFAULT '0' COMMENT '流程模式：0-简化流程 1-BPMN高级'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    -- ---------- 打印（REQ-PRINT-011；机制由 B2 建立，列在 B1 同批加） ----------
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'builtin_print_key') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `builtin_print_key` varchar(32) DEFAULT 'contract' COMMENT '内置打印模板键：contract/fund/matter/payment'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    -- ---------- 提交与审批策略（REQ-FORM-009；默认值 = 升级前行为） ----------
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'revoke_limit_minutes') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `revoke_limit_minutes` int(11) DEFAULT 0 COMMENT '提交后多少分钟内可撤销，0-不限制（沿用现状）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'allow_edit_after_submit') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `allow_edit_after_submit` char(1) DEFAULT '0' COMMENT '允许提交人修改已提交单据：0-否 1-是（默认否）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'allow_submit_for_other') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `allow_submit_for_other` char(1) DEFAULT '0' COMMENT '允许代他人提交：0-否 1-是（默认否）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'allow_approver_revoke') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `allow_approver_revoke` char(1) DEFAULT '1' COMMENT '允许审批人撤回：0-否 1-是（默认是）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'allow_batch_approve') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `allow_batch_approve` char(1) DEFAULT '1' COMMENT '允许批量审批：0-否 1-是（默认是）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'approver_dedup') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `approver_dedup` char(1) DEFAULT '3' COMMENT '审批人去重策略：1-自动同意 2-自动跳过 3-不自动同意（默认3，与现状一致）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'forward_scope') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `forward_scope` varchar(32) DEFAULT NULL COMMENT '审批转发范围（复用可发起范围枚举），NULL-不限制'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    -- ---------- 基础信息补充（页面上的「说明」，REQ-FORM-004） ----------
    -- ⚠️ 与 PRD 第 8.1 节的偏差（实测核对后纠正）：
    --   PRD 写的是"复用现有 remark 列，不新增列"，但**本库的 t_template 根本没有 remark 列**
    --   （table.sql:2146-2168 里没有；`grep remark` 在 TemplateMapper.xml 里也零命中）。
    --   如果不补这一列，页面上填的「说明」会被**静默丢弃** —— 属于本项目最忌讳的那类缺陷。
    --   因此这里补上 `remark varchar(500)`，并在 Mapper 的五处同步里登记（任务 2.3）。
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'remark') = 0 THEN
        SET @ddl = "ALTER TABLE `t_template` ADD COLUMN `remark` varchar(500) DEFAULT NULL COMMENT '说明（发起页卡片副标题，≤500 字）'";
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET v_added = v_added + 1;
    END IF;

    -- ---------- 自检：14 列齐备 + 存量行全部有合法值 ----------
    SELECT v_added AS `本次新增列数（重复执行应为 0）`;

    SELECT c.COLUMN_NAME AS `列`, c.COLUMN_TYPE AS `类型`,
           IFNULL(c.COLUMN_DEFAULT, '(NULL)') AS `默认值`
      FROM information_schema.COLUMNS c
     WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 't_template'
       AND c.COLUMN_NAME IN ('icon','submit_scope_type','include_child_dept','simple_flow_id','flow_mode',
                             'builtin_print_key','revoke_limit_minutes','allow_edit_after_submit',
                             'allow_submit_for_other','allow_approver_revoke','allow_batch_approve',
                             'approver_dedup','forward_scope','remark')
     ORDER BY c.ORDINAL_POSITION;

    SELECT COUNT(*) AS `14列齐备（应为14）`
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template'
       AND COLUMN_NAME IN ('icon','submit_scope_type','include_child_dept','simple_flow_id','flow_mode',
                           'builtin_print_key','revoke_limit_minutes','allow_edit_after_submit',
                           'allow_submit_for_other','allow_approver_revoke','allow_batch_approve',
                           'approver_dedup','forward_scope','remark');

    SELECT COUNT(*) AS `存量行里默认值为空的非法行数（应为0）`
      FROM t_template
     WHERE submit_scope_type IS NULL OR include_child_dept IS NULL OR flow_mode IS NULL
        OR revoke_limit_minutes IS NULL OR allow_edit_after_submit IS NULL
        OR allow_submit_for_other IS NULL OR allow_approver_revoke IS NULL
        OR allow_batch_approve IS NULL OR approver_dedup IS NULL;
END$$
DELIMITER ;

CALL `sp_oa_b1_add_template_columns`();
DROP PROCEDURE IF EXISTS `sp_oa_b1_add_template_columns`;
