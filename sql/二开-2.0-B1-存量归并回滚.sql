-- ============================================================================
-- OA 2.0 · B1 §1.4 存量归并**回滚**（按快照表还原）
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 1.4）｜需求：REQ-DATA-004｜验收：AC-68
--
-- 做什么：按 `t_template_migrate_backup` 里记录的**行级**快照，把配置类子表的
--         `template_id` 还原成迁移前的值，使子表引用回到迁移前状态。
-- 数据来源：`二开-2.0-B1-存量归并.sql` 写入的快照（batch_id 一次迁移一个批号）。
--
-- 用法（见 DEV-ENV.md「2.0 存量归并迁移」一节的固定命令）：
--   默认回滚**最近一次**迁移：
--     ... -e "source sql/二开-2.0-B1-存量归并回滚.sql"
--   指定批次回滚：
--     CALL sp_oa_b1_rollback_template_versions('B1-20261004202000');
--
-- 安全设计：只还原**当前仍处于迁移后状态**的行（`x.template_id = b.new_template_id`）。
--   迁移后被人工改过的行不会被覆盖，而是计入「未还原」并列出来。
--   连续执行两次是安全的：第二次匹配 0 行（第一段预览也会显示没有新批次可回滚）。
-- ============================================================================

-- ---------- 预览：库里有哪些批次、各自影响多少行（先看清再回滚） ----------
SELECT batch_id AS `迁移批次`, COUNT(*) AS `影响行数`,
       MIN(migrate_time) AS `开始时间`, MAX(migrate_time) AS `结束时间`
  FROM t_template_migrate_backup
 GROUP BY batch_id
 ORDER BY batch_id DESC;

DROP PROCEDURE IF EXISTS `sp_oa_b1_rollback_template_versions`;

DELIMITER $$
CREATE PROCEDURE `sp_oa_b1_rollback_template_versions`(IN p_batch VARCHAR(64))
BEGIN
    DECLARE v_done       INT DEFAULT 0;
    DECLARE v_has        INT DEFAULT 0;
    DECLARE v_tbl        VARCHAR(64);
    DECLARE v_coll       VARCHAR(64);
    DECLARE v_batch      VARCHAR(64);
    DECLARE v_restored   BIGINT DEFAULT 0;
    DECLARE v_skipped    BIGINT DEFAULT 0;
    DECLARE v_total_res  BIGINT DEFAULT 0;
    DECLARE v_total_skip BIGINT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SELECT COUNT(*) INTO v_has
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_migrate_backup';
    IF v_has = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '快照表 t_template_migrate_backup 不存在：没有可回滚的迁移记录';
    END IF;

    SET v_batch = IFNULL(p_batch, (SELECT MAX(batch_id) FROM t_template_migrate_backup));
    IF v_batch IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '快照表为空：没有可回滚的迁移记录';
    END IF;

    -- 排序规则取自业务表本身，保证后续"列 vs 列"比较不报 ERROR 1267
    SELECT COLLATION_NAME INTO v_coll
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'id'
     LIMIT 1;
    SET v_coll = IFNULL(v_coll, 'utf8mb4_general_ci');

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_rollback_stat;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_rollback_stat (',
                      'child_table VARCHAR(64) NOT NULL,',
                      'restored BIGINT NOT NULL DEFAULT 0,',
                      'skipped  BIGINT NOT NULL DEFAULT 0,',
                      'PRIMARY KEY (child_table)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE ', v_coll);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

    START TRANSACTION;

    BEGIN
        -- 走同一份白名单（规则 B）；快照里没有的表自然得到 0 行，不出现在报告里
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

            -- 先统计"迁移后被人工改过（或行已不存在）"的行 —— 必须在还原**之前**数，
            -- 否则刚被还原的行会被误报成"未还原"。
            SET @sql = CONCAT(
                'SELECT COUNT(*) INTO @cnt FROM t_template_migrate_backup b ',
                ' LEFT JOIN `', v_tbl, '` x ON x.id = b.child_row_id ',
                ' WHERE b.batch_id = ''', v_batch, ''' AND b.child_table = ''', v_tbl, ''' ',
                '   AND (x.id IS NULL OR x.template_id <> b.new_template_id)');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET v_skipped = IFNULL(@cnt, 0);

            -- 仍处于迁移后状态的行 → 还原
            SET @sql = CONCAT(
                'UPDATE `', v_tbl, '` x ',
                '  JOIN t_template_migrate_backup b ',
                '    ON b.child_row_id = x.id AND b.child_table = ''', v_tbl, ''' ',
                '   SET x.template_id = b.old_template_id ',
                ' WHERE b.batch_id = ''', v_batch, ''' ',
                '   AND x.template_id = b.new_template_id');
            PREPARE stmt FROM @sql; EXECUTE stmt;
            SET v_restored = ROW_COUNT();
            DEALLOCATE PREPARE stmt;

            IF v_restored > 0 OR v_skipped > 0 THEN
                INSERT INTO tmp_oa_rollback_stat (child_table, restored, skipped)
                VALUES (v_tbl, v_restored, v_skipped);
            END IF;
        END LOOP;
        CLOSE cur_tbl;
    END;

    COMMIT;

    SELECT IFNULL(SUM(restored), 0), IFNULL(SUM(skipped), 0)
      INTO v_total_res, v_total_skip
      FROM tmp_oa_rollback_stat;

    SELECT v_batch AS `回滚批次`, v_total_res AS `已还原行数`,
           v_total_skip AS `未还原行数（迁移后又被改过）`;

    SELECT child_table AS `配置表`, restored AS `已还原`, skipped AS `未还原`
      FROM tmp_oa_rollback_stat
     ORDER BY child_table;

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_rollback_stat;
END$$
DELIMITER ;

CALL `sp_oa_b1_rollback_template_versions`(NULL);
DROP PROCEDURE IF EXISTS `sp_oa_b1_rollback_template_versions`;
