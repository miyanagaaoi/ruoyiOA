-- ============================================================================
-- OA 2.0 · B1 §1.3 存量归并迁移（**会改数据**）
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 1.3）｜需求：REQ-DATA-004｜验收：AC-68
--
-- 做什么：把挂在**废弃行**（del_flag='1'）上的**配置类**子表 `template_id`，统一改到
--         该模板当前**启用行**（del_flag='0'）上，让"编辑模板后配置失联"的存量数据恢复可读。
-- 改哪些表：**白名单**（不是"所有含 template_id 的表"——在途/历史单据表必须保持
--         发起时那一版模板的关联；完整理由见 `二开-2.0-B1-存量体检.sql` 规则 B）。
-- 回滚：迁移前把**每一行**的改动写进快照表 `t_template_migrate_backup`，
--       回滚执行 `二开-2.0-B1-存量归并回滚.sql`。
--
-- 使用顺序（**必须在库副本上先演练**）：
--   1) ... -e "source ..."  见 DEV-ENV.md「2.0 存量归并迁移」一节的固定命令
--      a. 跑 `二开-2.0-B1-存量体检.sql`  → 看影响行数报告
--      b. 跑 `二开-2.0-B1-存量归并.sql`  → 归并
--      c. 再跑一次体检：④⑤⑥ 应为 0       → 复核
--      d. 跑 `二开-2.0-B1-存量归并回滚.sql` → 演练回滚
--
-- 幂等性：可重复执行。第二次执行时启用行已有配置（或废弃行已无配置行），
--         匹配 0 行 → 快照 0 行 → 改写 0 行。
-- 规则：与 `二开-2.0-B1-存量体检.sql` 的规则 A/B/C 完全一致，改动必须同步。
--
-- 实现注意（踩过的坑）：MySQL 下"列 vs 列""变量 vs 列"比较都要求两侧排序规则相同，
--   否则报 ERROR 1267。本脚本因此：(1) 临时表与快照表都用 t_template.id 的实际排序规则；
--   (2) 与列比较的一律走动态 SQL 把变量内联成字面量。
-- ============================================================================

DROP PROCEDURE IF EXISTS `sp_oa_b1_merge_template_versions`;

DELIMITER $$
CREATE PROCEDURE `sp_oa_b1_merge_template_versions`()
BEGIN
    DECLARE v_done       INT DEFAULT 0;
    DECLARE v_has        INT DEFAULT 0;
    DECLARE v_tbl        VARCHAR(64);
    DECLARE v_coll       VARCHAR(64);
    DECLARE v_snapshot   BIGINT DEFAULT 0;
    DECLARE v_updated    BIGINT DEFAULT 0;
    DECLARE v_batch      VARCHAR(64);
    DECLARE v_mapped     BIGINT DEFAULT 0;
    DECLARE v_targets    BIGINT DEFAULT 0;
    DECLARE v_total_snap BIGINT DEFAULT 0;
    DECLARE v_total_upd  BIGINT DEFAULT 0;

    -- 任何 SQL 异常：整体回滚（快照与改动一起回退，不留半成品）
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET v_batch = CONCAT('B1-', DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'));

    -- 排序规则取自业务表本身，保证后续"列 vs 列"比较不报 ERROR 1267
    SELECT COLLATION_NAME INTO v_coll
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'id'
     LIMIT 1;
    SET v_coll = IFNULL(v_coll, 'utf8mb4_general_ci');

    -- ---------- 0. 快照表（迁移前建；回滚按它还原） ----------
    SELECT COUNT(*) INTO v_has
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_migrate_backup';
    IF v_has = 0 THEN
        SET @ddl = CONCAT('CREATE TABLE t_template_migrate_backup (',
            'id varchar(64) NOT NULL COMMENT ''主键'', ',
            'batch_id varchar(64) NOT NULL COMMENT ''迁移批次号'', ',
            'child_table varchar(64) NOT NULL COMMENT ''被改动的子表'', ',
            'child_row_id varchar(64) NOT NULL COMMENT ''被改动的子表行主键'', ',
            'old_template_id varchar(64) NOT NULL COMMENT ''改动前的 template_id'', ',
            'new_template_id varchar(64) NOT NULL COMMENT ''改动后的 template_id'', ',
            'migrate_time datetime DEFAULT NULL COMMENT ''迁移时间'', ',
            'PRIMARY KEY (id), KEY idx_batch (batch_id), KEY idx_old (old_template_id), ',
            'KEY idx_child (child_table, child_row_id)) ',
            'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll,
            ' COMMENT=''OA2.0 B1 模板存量归并快照（按此表回滚）''');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_tpl_map (',
                      'old_template_id VARCHAR(64) NOT NULL,',
                      'new_template_id VARCHAR(64) NOT NULL,',
                      'PRIMARY KEY (old_template_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map_tgt;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_tpl_map_tgt (',
                      'new_template_id VARCHAR(64) NOT NULL,',
                      'PRIMARY KEY (new_template_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_move_plan;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_move_plan (',
                      'child_table VARCHAR(64) NOT NULL,',
                      'old_template_id VARCHAR(64) NULL,',
                      'new_template_id VARCHAR(64) NOT NULL,',
                      'KEY idx_tbl (child_table)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_child_stat;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_child_stat (',
                      'child_table VARCHAR(64) NOT NULL,',
                      'snapshot BIGINT NOT NULL DEFAULT 0,',
                      'updated  BIGINT NOT NULL DEFAULT 0,',
                      'PRIMARY KEY (child_table)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    -- ---------- 1. 建映射（规则 A） ----------
    INSERT INTO tmp_oa_tpl_map (old_template_id, new_template_id)
    SELECT a.id, e.id
      FROM t_template a
      JOIN (SELECT name, type, form_id, COUNT(*) AS enabled_cnt
              FROM t_template
             WHERE del_flag = '0'
             GROUP BY name, type, form_id) g
        ON g.name = a.name AND g.type = a.type AND (g.form_id <=> a.form_id)
      JOIN t_template e
        ON e.name = a.name AND e.type = a.type AND (e.form_id <=> a.form_id)
       AND e.del_flag = '0'
     WHERE a.del_flag = '1'
       AND g.enabled_cnt = 1;

    INSERT INTO tmp_oa_tpl_map_tgt (new_template_id)
    SELECT DISTINCT new_template_id FROM tmp_oa_tpl_map;

    SELECT COUNT(*) INTO v_mapped  FROM tmp_oa_tpl_map;
    SELECT COUNT(*) INTO v_targets FROM tmp_oa_tpl_map_tgt;

    -- ---------- 2. 逐表：先定计划（规则 C），再快照，最后改引用 ----------
    START TRANSACTION;

    BEGIN
        DECLARE cur_tbl CURSOR FOR
            SELECT t.TABLE_NAME
              FROM information_schema.TABLES t
             WHERE t.TABLE_SCHEMA = DATABASE()
               AND t.TABLE_TYPE   = 'BASE TABLE'
               AND t.TABLE_NAME IN ('t_template_print_template',
                                    't_template_node_field_auth',
                                    't_template_submit_scope',
                                    't_template_flow_admin',
                                    't_template_related_approval')
             ORDER BY t.TABLE_NAME;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

        OPEN cur_tbl;
        read_tbl: LOOP
            FETCH cur_tbl INTO v_tbl;
            IF v_done = 1 THEN
                LEAVE read_tbl;
            END IF;

            -- 2.1 定计划：只在"启用行该表为空"时搬，且取链上最新的一版废弃行
            SET @sql = CONCAT(
                'INSERT INTO tmp_oa_move_plan (child_table, old_template_id, new_template_id) ',
                'SELECT ''', v_tbl, ''', ',
                '       (SELECT a.old_template_id FROM tmp_oa_tpl_map a ',
                '          JOIN t_template ta ON ta.id = a.old_template_id ',
                '         WHERE a.new_template_id = g.new_template_id ',
                '           AND EXISTS (SELECT 1 FROM `', v_tbl, '` c WHERE c.template_id = a.old_template_id) ',
                '         ORDER BY ta.create_time DESC, a.old_template_id DESC LIMIT 1), ',
                '       g.new_template_id ',
                '  FROM tmp_oa_tpl_map_tgt g ',
                ' WHERE NOT EXISTS (SELECT 1 FROM `', v_tbl, '` y WHERE y.template_id = g.new_template_id)');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

            SET @sql = CONCAT('DELETE FROM tmp_oa_move_plan WHERE child_table = ''', v_tbl,
                              ''' AND old_template_id IS NULL');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

            -- 2.2 快照：把"这一行原本挂在哪"逐行记下来（回滚按 子表+行主键 还原）
            SET @sql = CONCAT(
                'INSERT INTO t_template_migrate_backup ',
                '(id, batch_id, child_table, child_row_id, old_template_id, new_template_id, migrate_time) ',
                'SELECT REPLACE(UUID(), ''-'', ''''), ''', v_batch, ''', ''', v_tbl, ''', ',
                '       x.id, x.template_id, p.new_template_id, NOW() ',
                '  FROM `', v_tbl, '` x ',
                '  JOIN tmp_oa_move_plan p ',
                '    ON p.old_template_id = x.template_id AND p.child_table = ''', v_tbl, '''');
            PREPARE stmt FROM @sql; EXECUTE stmt;
            SET v_snapshot = ROW_COUNT();
            DEALLOCATE PREPARE stmt;

            -- 2.3 改引用
            SET @sql = CONCAT(
                'UPDATE `', v_tbl, '` x ',
                '  JOIN tmp_oa_move_plan p ',
                '    ON p.old_template_id = x.template_id AND p.child_table = ''', v_tbl, ''' ',
                '   SET x.template_id = p.new_template_id');
            PREPARE stmt FROM @sql; EXECUTE stmt;
            SET v_updated = ROW_COUNT();
            DEALLOCATE PREPARE stmt;

            INSERT INTO tmp_oa_child_stat (child_table, snapshot, updated)
            VALUES (v_tbl, v_snapshot, v_updated);
        END LOOP;
        CLOSE cur_tbl;
    END;

    COMMIT;

    -- ---------- 3. 报告 ----------
    SELECT IFNULL(SUM(snapshot), 0), IFNULL(SUM(updated), 0)
      INTO v_total_snap, v_total_upd
      FROM tmp_oa_child_stat;

    SELECT v_batch AS `迁移批次号`, v_mapped AS `归并的废弃行数`, v_targets AS `涉及启用行数`,
           v_total_snap AS `快照行数`, v_total_upd AS `实际改写行数`,
           '回滚见 sql/二开-2.0-B1-存量归并回滚.sql' AS `下一步`;

    SELECT child_table AS `配置表`, snapshot AS `快照行数`, updated AS `改写行数`
      FROM tmp_oa_child_stat
     ORDER BY updated DESC, child_table;

    SELECT m.old_template_id AS `废弃行ID`, m.new_template_id AS `启用行ID(归并目标)`,
           a.name AS `模板名称`, a.create_time AS `废弃行创建时间`
      FROM tmp_oa_tpl_map m
      JOIN t_template a ON a.id = m.old_template_id
     ORDER BY a.name, a.create_time, m.old_template_id;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map_tgt;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_move_plan;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_child_stat;
END$$
DELIMITER ;

CALL `sp_oa_b1_merge_template_versions`();
DROP PROCEDURE IF EXISTS `sp_oa_b1_merge_template_versions`;
