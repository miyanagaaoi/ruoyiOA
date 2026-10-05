-- ============================================================================
-- 合同台账 · 回滚脚本（2.0 B3 / oa-contract-ledger）
-- 变更集任务：§2.5｜需求：REQ-DATA-005｜验收：AC-68
-- 快照日期：20261005（只读 `*_bak_20261005` 快照）
-- ----------------------------------------------------------------------------
-- 本脚本按**逆序**回滚 B3 对数据库做的一切（design.md Migration Plan 的逆序）：
--   ① 删 FK（本变更集加的全部外键，含"别的表引用 B3 表"的反向 FK —— 为 B4 预留）
--   ② 删索引（B3 在**存量表**上建的索引；13 张新建表的索引随 DROP TABLE 一起消失）
--   ③ 恢复可空（把 ⑥-3 收紧的 13 列改回 NULL）
--   ④ DROP TABLE（本变更集新建的 13 张业务表，子表在前）
--   ⑤ 按快照反向回填（先删本变更集插入的白名单行，再从快照整行恢复）
--   ⑥ 核对（对象是否全部消失、存量表是否回到执行前状态）
--
-- 阶段开关：
--   SET @phase = 'BOTH'             → 完整回滚（①~⑥，默认）
--   SET @phase = 'CONSTRAINTS_ONLY' → 只撤约束（①~③），保留 13 张表与数据
--
-- ⚠ 安全设计（宁可失败也不要"回滚一半"）：如果某张表**还有数据**但对应快照不存在，
--   脚本会**中止**（用一个必然失败的语句触发错误），而不是把数据 DROP 掉。
--
-- ⚠ 实现说明：MySQL 的 `WHILE` **只允许出现在存储程序里**，普通脚本里写 `WHILE ... DO`
--   会直接报 `ERROR 1064 near 'WHILE ...'`。所以下面把三处循环各包成一个临时存储过程
--   （`DELIMITER` 是 mysql 客户端指令，用 `mysql < file` 喂进来时生效）。
--
-- ⚠ 本脚本**不删除** `*_bak_20261005` 快照表（回滚后请人工确认数据无误再自行清理）。
-- ⚠ 只回滚 B3 自己的对象；**不触碰**任何存量表的列结构。
-- ============================================================================

SET @bak_date = '20261005';
SET @phase    = 'BOTH';
SET @ctms_tables = 't_ctms_contract,t_ctms_contract_item,t_ctms_tag,t_ctms_contract_tag,t_ctms_change_log,t_ctms_attachment,t_ctms_customer,t_ctms_supplier,t_ctms_party_draft,t_ctms_product_type,t_ctms_uom,t_ctms_warehouse,t_ctms_product';

SELECT CONCAT('回滚阶段：', @phase, '（BOTH = 连表一起删；CONSTRAINTS_ONLY = 只撤约束）') AS `步骤 0 · 阶段`;

-- ---------------------------------------------------------------------------
-- 步骤 0：前置检查（现状 + 快照齐备性）与中止守卫
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_b3_roll;
CREATE TEMPORARY TABLE tmp_b3_roll (
  `表名`     VARCHAR(64)  NOT NULL,
  `表存在`   TINYINT      NOT NULL,
  `当前行数` BIGINT       NULL,
  `快照表`   VARCHAR(160) NULL,
  `快照存在` TINYINT      NOT NULL,
  `快照行数` BIGINT       NULL
) ENGINE = MEMORY;

DROP PROCEDURE IF EXISTS b3_rb_precheck;
DELIMITER //
CREATE PROCEDURE b3_rb_precheck(IN p_list TEXT, IN p_date VARCHAR(16))
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE v_t VARCHAR(64);
  DECLARE v_bak VARCHAR(160);
  DECLARE v_has_t INT DEFAULT 0;
  DECLARE v_has_bak INT DEFAULT 0;
  WHILE i <= 13 DO
    SET v_t   = SUBSTRING_INDEX(SUBSTRING_INDEX(p_list, ',', i), ',', -1);
    SET v_bak = CONCAT(v_t, '_bak_', p_date);
    SET v_has_t   = (SELECT COUNT(*) FROM information_schema.TABLES
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = v_t);
    SET v_has_bak = (SELECT COUNT(*) FROM information_schema.TABLES
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = v_bak);
    SET @ins = IF(v_has_t = 1,
      CONCAT('INSERT INTO tmp_b3_roll SELECT ', QUOTE(v_t), ', 1, COUNT(*), ', QUOTE(v_bak), ', ', v_has_bak, ', NULL FROM ', v_t),
      CONCAT('INSERT INTO tmp_b3_roll VALUES (', QUOTE(v_t), ', 0, NULL, ', QUOTE(v_bak), ', ', v_has_bak, ', NULL)'));
    PREPARE s FROM @ins; EXECUTE s; DEALLOCATE PREPARE s;
    IF v_has_bak = 1 THEN
      SET @cnt = CONCAT('UPDATE tmp_b3_roll SET 快照行数 = (SELECT COUNT(*) FROM ', v_bak, ') WHERE 表名 = ', QUOTE(v_t));
      PREPARE s2 FROM @cnt; EXECUTE s2; DEALLOCATE PREPARE s2;
    END IF;
    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;

CALL b3_rb_precheck(@ctms_tables, @bak_date);

SELECT `表名`, IF(`表存在` = 1, '存在', '不存在') AS `表状态`, `当前行数`,
       IF(`快照存在` = 1, '存在', '**缺失**') AS `快照状态`, `快照行数`
  FROM tmp_b3_roll ORDER BY `表名`;

SET @has_data_no_snap = (SELECT COUNT(*) FROM tmp_b3_roll WHERE 表存在 = 1 AND 当前行数 > 0 AND 快照存在 = 0);
SELECT IF(@has_data_no_snap > 0,
          CONCAT('**回滚被拒绝**：有 ', @has_data_no_snap, ' 张表仍有数据但缺少快照（见上表"快照状态"列）。',
                 '请先人工导出这些表的数据，或补做 `影响行数报告-合同台账-', @bak_date, '.sql`，再重跑本脚本。'),
          '前置检查通过：需要回滚的表都有快照或本身为空。') AS `步骤 0 · 前置检查`;

-- 中止守卫：条件成立时执行一个必然失败的语句（PREPARE 保证只有真需要时才解析它）
SET @sql = IF(@has_data_no_snap > 0, 'SELECT 1 FROM B3_ROLLBACK_ABORTED_MISSING_SNAPSHOT', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 步骤 ①：删 FK（两个方向：B3 表自己加的，以及别的表引用 B3 表的）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS b3_rb_drop_fks;
DELIMITER //
CREATE PROCEDURE b3_rb_drop_fks(IN p_list TEXT)
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_tbl VARCHAR(64);
  DECLARE v_name VARCHAR(64);
  DECLARE cur CURSOR FOR
    SELECT DISTINCT k.TABLE_NAME, k.CONSTRAINT_NAME
      FROM information_schema.KEY_COLUMN_USAGE k
     WHERE k.CONSTRAINT_SCHEMA = DATABASE()
       AND k.REFERENCED_TABLE_NAME IS NOT NULL
       AND (FIND_IN_SET(k.TABLE_NAME, p_list) > 0
            OR FIND_IN_SET(k.REFERENCED_TABLE_NAME, p_list) > 0);
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_tbl, v_name;
    IF done = 1 THEN LEAVE read_loop; END IF;
    SET @ddl = CONCAT('ALTER TABLE ', v_tbl, ' DROP FOREIGN KEY ', v_name);
    PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
    SELECT CONCAT('已删外键 ', v_name, '（表 ', v_tbl, '）') AS `步骤 ① · 删 FK`;
  END LOOP;
  CLOSE cur;
END //
DELIMITER ;

CALL b3_rb_drop_fks(@ctms_tables);

SET @left_fk = (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
                 WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
                   AND (FIND_IN_SET(TABLE_NAME, @ctms_tables) > 0
                        OR FIND_IN_SET(REFERENCED_TABLE_NAME, @ctms_tables) > 0));
SELECT IF(@left_fk = 0, '通过：与本变更集相关的外键已全部删除',
          CONCAT('未通过：还剩 ', @left_fk, ' 个相关外键')) AS `步骤 ① · 结果`;

-- ---------------------------------------------------------------------------
-- 步骤 ②：删索引（只删"存量表上按本变更集命名约定建的索引"；13 张新建表的随 DROP 消失）
--   本变更集当前没有在存量表上建索引 → 这一步是"检查 + 兜底"，不是空转：
--   万一将来有人加了 `uk_ctms%` / `idx_ctms%` / `fk_ctms%` 的存量表索引，这里会自动删掉。
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_b3_idx;
CREATE TEMPORARY TABLE tmp_b3_idx (`表名` VARCHAR(64), `索引名` VARCHAR(64)) ENGINE = MEMORY;

INSERT INTO tmp_b3_idx
SELECT DISTINCT s.TABLE_NAME, s.INDEX_NAME
  FROM information_schema.STATISTICS s
 WHERE s.TABLE_SCHEMA = DATABASE()
   AND s.INDEX_NAME <> 'PRIMARY'
   AND FIND_IN_SET(s.TABLE_NAME, @ctms_tables) = 0
   AND (s.INDEX_NAME LIKE 'uk\_ctms%' OR s.INDEX_NAME LIKE 'idx\_ctms%' OR s.INDEX_NAME LIKE 'fk\_ctms%');

SELECT IF(COUNT(*) = 0, '通过：本变更集未在存量表上建索引（新建表的索引随 DROP TABLE 消失）',
          CONCAT('需删除 ', COUNT(*), ' 个存量表索引，见下')) AS `步骤 ② · 检查` FROM tmp_b3_idx;

DROP PROCEDURE IF EXISTS b3_rb_drop_indexes;
DELIMITER //
CREATE PROCEDURE b3_rb_drop_indexes()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_tbl VARCHAR(64);
  DECLARE v_idx VARCHAR(64);
  DECLARE cur CURSOR FOR SELECT 表名, 索引名 FROM tmp_b3_idx;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_tbl, v_idx;
    IF done = 1 THEN LEAVE read_loop; END IF;
    SET @ddl = CONCAT('ALTER TABLE ', v_tbl, ' DROP INDEX ', v_idx);
    PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
    SELECT CONCAT('已删索引 ', v_idx, '（表 ', v_tbl, '）') AS `步骤 ② · 删索引`;
  END LOOP;
  CLOSE cur;
END //
DELIMITER ;

CALL b3_rb_drop_indexes();

-- ---------------------------------------------------------------------------
-- 步骤 ③：恢复可空（与 `体检-合同台账-20261005.sql` 的 13 列一一对应）
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_b3_cols;
CREATE TEMPORARY TABLE tmp_b3_cols (`表名` VARCHAR(64), `列名` VARCHAR(64)) ENGINE = MEMORY;
INSERT INTO tmp_b3_cols VALUES
 ('t_ctms_contract','dept_id'), ('t_ctms_contract','create_id'),
 ('t_ctms_contract_item','product_id'), ('t_ctms_contract_item','product_code'),
 ('t_ctms_contract_item','product_name'),
 ('t_ctms_change_log','object_type'), ('t_ctms_change_log','object_id'),
 ('t_ctms_attachment','object_type'), ('t_ctms_attachment','object_id'),
 ('t_ctms_customer','create_id'), ('t_ctms_supplier','short_name'),
 ('t_ctms_supplier','create_id'), ('t_ctms_product','create_id');

DROP PROCEDURE IF EXISTS b3_rb_relax_columns;
DELIMITER //
CREATE PROCEDURE b3_rb_relax_columns()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_tbl VARCHAR(64);
  DECLARE v_col VARCHAR(64);
  DECLARE v_type VARCHAR(256);
  DECLARE cur CURSOR FOR SELECT 表名, 列名 FROM tmp_b3_cols;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_tbl, v_col;
    IF done = 1 THEN LEAVE read_loop; END IF;
    -- 取该列当前真实类型（类型不能改错；字符列还要带 CHARSET/COLLATE）
    SET v_type = (SELECT CONCAT(i.COLUMN_TYPE,
                       IF(i.CHARACTER_SET_NAME IS NULL, '', CONCAT(' CHARACTER SET ', i.CHARACTER_SET_NAME)),
                       IF(i.COLLATION_NAME IS NULL, '', CONCAT(' COLLATE ', i.COLLATION_NAME)))
                    FROM information_schema.COLUMNS i
                   WHERE i.TABLE_SCHEMA = DATABASE() AND i.TABLE_NAME = v_tbl AND i.COLUMN_NAME = v_col);
    IF v_type IS NOT NULL THEN
      SET @ddl = CONCAT('ALTER TABLE ', v_tbl, ' MODIFY COLUMN ', v_col, ' ', v_type, ' NULL');
      PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
      SELECT CONCAT('已恢复可空 ', v_tbl, '.', v_col) AS `步骤 ③ · 恢复可空`;
    END IF;
  END LOOP;
  CLOSE cur;
END //
DELIMITER ;

CALL b3_rb_relax_columns();

-- ---------------------------------------------------------------------------
-- 步骤 ④：DROP TABLE（仅 BOTH 阶段；子表在前）
-- ---------------------------------------------------------------------------
SELECT IF(@phase = 'BOTH', '将删除 13 张新建表', '阶段为 CONSTRAINTS_ONLY：保留 13 张表及其数据') AS `步骤 ④ · 说明`;

SET @sql = IF(@phase = 'BOTH', 'DROP TABLE IF EXISTS t_ctms_contract_tag, t_ctms_contract_item, t_ctms_change_log, t_ctms_attachment, t_ctms_party_draft', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(@phase = 'BOTH', 'DROP TABLE IF EXISTS t_ctms_tag, t_ctms_customer, t_ctms_supplier, t_ctms_product, t_ctms_contract', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(@phase = 'BOTH', 'DROP TABLE IF EXISTS t_ctms_uom, t_ctms_warehouse, t_ctms_product_type', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 步骤 ⑤：按快照反向回填（先删本变更集插入的白名单行，再从快照整行恢复）
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_b3_restore;
CREATE TEMPORARY TABLE tmp_b3_restore (
  `表名` VARCHAR(64), `快照表` VARCHAR(160), `快照存在` TINYINT DEFAULT 0, `快照行数` BIGINT NULL
) ENGINE = MEMORY;

INSERT INTO tmp_b3_restore (表名, 快照表) VALUES
 ('sys_dict_data','sys_dict_data_bak_20261005'),
 ('sys_dict_type','sys_dict_type_bak_20261005'),
 ('sys_config','sys_config_bak_20261005'),
 ('sys_menu','sys_menu_bak_20261005'),
 ('t_code_config','t_code_config_bak_20261005'),
 ('t_code_config_rule','t_code_config_rule_bak_20261005');

UPDATE tmp_b3_restore r
   SET r.快照存在 = (SELECT COUNT(*) FROM information_schema.TABLES
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = r.快照表);

DROP PROCEDURE IF EXISTS b3_rb_fill_snapshot_counts;
DELIMITER //
CREATE PROCEDURE b3_rb_fill_snapshot_counts()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_tbl VARCHAR(64);
  DECLARE v_bak VARCHAR(160);
  DECLARE cur CURSOR FOR SELECT 表名, 快照表 FROM tmp_b3_restore WHERE 快照存在 = 1;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_tbl, v_bak;
    IF done = 1 THEN LEAVE read_loop; END IF;
    SET @cnt = CONCAT('UPDATE tmp_b3_restore SET 快照行数 = (SELECT COUNT(*) FROM ', v_bak, ') WHERE 表名 = ', QUOTE(v_tbl));
    PREPARE s FROM @cnt; EXECUTE s; DEALLOCATE PREPARE s;
  END LOOP;
  CLOSE cur;
END //
DELIMITER ;

CALL b3_rb_fill_snapshot_counts();

SELECT `表名`, IF(`快照存在` = 1, '存在', '未生成（只删本变更集插入的行）') AS `快照状态`, `快照行数`
  FROM tmp_b3_restore ORDER BY `表名`;

-- 回填：有快照 → 先删白名单行再整行恢复；无快照 → 只删白名单行（保证回滚彻底）
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_data') = 1,
  'DELETE FROM sys_dict_data WHERE dict_type IN (''item_types'',''contract_types'',''subjects'',''contract_statuses'',''arrival_statuses'')', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_data') = 1,
  'INSERT INTO sys_dict_data SELECT * FROM sys_dict_data_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_type') = 1,
  'DELETE FROM sys_dict_type WHERE dict_type IN (''item_types'',''contract_types'',''subjects'',''contract_statuses'',''arrival_statuses'')', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_type') = 1,
  'INSERT INTO sys_dict_type SELECT * FROM sys_dict_type_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_config') = 1,
  'DELETE FROM sys_config WHERE config_key = ''warranty_window_days''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_config') = 1,
  'INSERT INTO sys_config SELECT * FROM sys_config_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_menu') = 1,
  'DELETE FROM sys_menu WHERE menu_id = ''9F2C0000000000000000000000000001'' OR perms LIKE ''ctms:%''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_menu') = 1,
  'INSERT INTO sys_menu SELECT * FROM sys_menu_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config_rule') = 1,
  'DELETE FROM t_code_config_rule WHERE config_id = ''9F2C0000000000000000000000C001''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config_rule') = 1,
  'INSERT INTO t_code_config_rule SELECT * FROM t_code_config_rule_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config') = 1,
  'DELETE FROM t_code_config WHERE id = ''9F2C0000000000000000000000C001''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config') = 1,
  'INSERT INTO t_code_config SELECT * FROM t_code_config_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 无快照时也必须把"本变更集插入的行"删干净（否则回滚不彻底：字典/菜单/编号配置会残留）
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_data') = 0,
  'DELETE FROM sys_dict_data WHERE dict_type IN (''item_types'',''contract_types'',''subjects'',''contract_statuses'',''arrival_statuses'')', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_dict_type') = 0,
  'DELETE FROM sys_dict_type WHERE dict_type IN (''item_types'',''contract_types'',''subjects'',''contract_statuses'',''arrival_statuses'')', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_config') = 0,
  'DELETE FROM sys_config WHERE config_key = ''warranty_window_days''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='sys_menu') = 0,
  'DELETE FROM sys_menu WHERE menu_id = ''9F2C0000000000000000000000000001'' OR perms LIKE ''ctms:%''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config_rule') = 0,
  'DELETE FROM t_code_config_rule WHERE config_id = ''9F2C0000000000000000000000C001''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF((SELECT 快照存在 FROM tmp_b3_restore WHERE 表名='t_code_config') = 0,
  'DELETE FROM t_code_config WHERE id = ''9F2C0000000000000000000000C001''', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT CONCAT('已处理 ', SUM(快照存在 = 1), ' 张（有快照：删白名单行 + 整行恢复）+ ',
              SUM(快照存在 = 0), ' 张（无快照：只删本变更集插入的行）') AS `步骤 ⑤ · 结果`
  FROM tmp_b3_restore;

-- 清理临时存储过程
DROP PROCEDURE IF EXISTS b3_rb_precheck;
DROP PROCEDURE IF EXISTS b3_rb_drop_fks;
DROP PROCEDURE IF EXISTS b3_rb_drop_indexes;
DROP PROCEDURE IF EXISTS b3_rb_relax_columns;
DROP PROCEDURE IF EXISTS b3_rb_fill_snapshot_counts;

-- ---------------------------------------------------------------------------
-- 步骤 ⑥：核对
-- ---------------------------------------------------------------------------
SELECT '① B3 的 13 张表现在应为 0（CONSTRAINTS_ONLY 阶段应为 13）' AS `核对项`, COUNT(*) AS `结果`
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND FIND_IN_SET(TABLE_NAME, @ctms_tables) > 0
UNION ALL
SELECT '② 相关外键应为 0', COUNT(*)
  FROM information_schema.KEY_COLUMN_USAGE
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
   AND (FIND_IN_SET(TABLE_NAME, @ctms_tables) > 0 OR FIND_IN_SET(REFERENCED_TABLE_NAME, @ctms_tables) > 0)
UNION ALL
SELECT '③ ctms 权限点应为 0', COUNT(*)
  FROM sys_menu WHERE perms LIKE 'ctms:%'
UNION ALL
SELECT '④ B3 字典类型应为 0', COUNT(*)
  FROM sys_dict_type WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')
UNION ALL
SELECT '⑤ B3 系统参数应为 0', COUNT(*)
  FROM sys_config WHERE config_key = 'warranty_window_days'
UNION ALL
SELECT '⑥ B3 编号配置应为 0', COUNT(*)
  FROM t_code_config WHERE id = '9F2C0000000000000000000000C001'
UNION ALL
SELECT '⑦ 残留的临时存储过程应为 0', COUNT(*)
  FROM information_schema.ROUTINES
 WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_NAME LIKE 'b3\_rb\_%';

SELECT '提示：快照表 *_bak_20261005 仍保留（回滚后请人工确认数据无误再自行清理）。' AS `收尾`;
SELECT '提示：编号计数器在 Redis（code:gen:seq:9F2C0000000000000000000000C001:*），回滚后如需彻底清理请另行删除。' AS `收尾`;
