-- ============================================================================
-- 合同台账 · 影响行数报告与快照（2.0 B3 / oa-contract-ledger）
-- 变更集任务：§2.3｜需求：REQ-DATA-005｜验收：AC-68
-- 生成日期：20261005（快照表名 = <表名>_bak_20261005）
-- ----------------------------------------------------------------------------
-- 为什么需要它（design.md D-3 的执行前置）：
--   `二开-合同台账.sql` 对 13 张业务表用的是 DROP TABLE IF EXISTS + CREATE，
--   并且内含「约束回补三段」（回填 → NOT NULL → FK）。**在执行之前**必须先有：
--     ① 一张「我会动到多少行」的清单（让执行人心里有数，也让回滚有据可查）；
--     ② 一份「动之前」的数据快照（`回滚-合同台账-20261005.sql` 按它反向回填）。
--
-- 本脚本（可反复执行；快照表每次 DROP 重建）：
--   第一~二部分：填「受影响行数」（新建表按整表；存量表按本变更集会删/改的那批行）
--   第三部分   ：生成快照（新建表 = 整表；存量表 = 仅命中白名单的行）
--   第四部分   ：填「快照行数」并输出**一张**总报告 + 两项一致性核对
--
-- ⚠ 表/快照不存在时输出「表不存在，跳过」而不是报错 —— 尚未建表的库上也能跑出报告。
-- ⚠ 快照是**回滚的唯一凭据**：回滚脚本找不到对应日期的快照会拒绝执行（宁可失败也不要「回滚一半」）。
-- ⚠ 除了自己的 `*_bak_20261005` 快照表，本脚本不修改任何数据。
-- ============================================================================

SET @bak_date = '20261005';

DROP TEMPORARY TABLE IF EXISTS tmp_b3_report;
CREATE TEMPORARY TABLE tmp_b3_report (
  `类别`       VARCHAR(64)  NOT NULL,
  `表名`       VARCHAR(64)  NOT NULL,
  `受影响行数` BIGINT       NULL COMMENT 'NULL = 表不存在，未统计',
  `快照表`     VARCHAR(160) NULL,
  `快照行数`   BIGINT       NULL COMMENT 'NULL = 未生成快照',
  `说明`       VARCHAR(64)  NULL
) ENGINE = MEMORY;

-- ---------------------------------------------------------------------------
-- 第一部分：新建的 13 张业务表（整表受影响；表不存在则标记跳过）
-- ---------------------------------------------------------------------------

SET @t = 't_ctms_contract';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_contract'', COUNT(*), CONCAT(''t_ctms_contract'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_contract', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_contract'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_contract_item';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_contract_item'', COUNT(*), CONCAT(''t_ctms_contract_item'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_contract_item', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_contract_item'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_tag';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_tag'', COUNT(*), CONCAT(''t_ctms_tag'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_tag', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_tag'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_contract_tag';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_contract_tag'', COUNT(*), CONCAT(''t_ctms_contract_tag'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_contract_tag', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_contract_tag'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_change_log';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_change_log'', COUNT(*), CONCAT(''t_ctms_change_log'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_change_log', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_change_log'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_attachment';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_attachment'', COUNT(*), CONCAT(''t_ctms_attachment'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_attachment', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_attachment'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_customer';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_customer'', COUNT(*), CONCAT(''t_ctms_customer'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_customer', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_customer'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_supplier';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_supplier'', COUNT(*), CONCAT(''t_ctms_supplier'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_supplier', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_supplier'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_party_draft';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_party_draft'', COUNT(*), CONCAT(''t_ctms_party_draft'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_party_draft', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_party_draft'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_product_type';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_product_type'', COUNT(*), CONCAT(''t_ctms_product_type'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_product_type', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_product_type'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_uom';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_uom'', COUNT(*), CONCAT(''t_ctms_uom'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_uom', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_uom'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_warehouse';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_warehouse'', COUNT(*), CONCAT(''t_ctms_warehouse'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_warehouse', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_warehouse'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @t = 't_ctms_product';
SET @bak = CONCAT(@t, '_bak_', @bak_date);
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @t),
  'INSERT INTO tmp_b3_report SELECT ''业务表（本变更集新建）'', ''t_ctms_product'', COUNT(*), CONCAT(''t_ctms_product'', ''_bak_'', @bak_date), NULL, NULL FROM t_ctms_product', 'INSERT INTO tmp_b3_report VALUES (''业务表（本变更集新建）'', ''t_ctms_product'', NULL, NULL, NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 第二部分：存量表中「本变更集会删/改」的行（这些表一定存在）
-- ---------------------------------------------------------------------------

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 'sys_dict_type', COUNT(*), CONCAT('sys_dict_type', '_bak_', @bak_date), NULL, NULL
  FROM sys_dict_type WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses');

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 'sys_dict_data', COUNT(*), CONCAT('sys_dict_data', '_bak_', @bak_date), NULL, NULL
  FROM sys_dict_data WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses');

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 'sys_config', COUNT(*), CONCAT('sys_config', '_bak_', @bak_date), NULL, NULL
  FROM sys_config WHERE config_key = 'warranty_window_days';

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 'sys_menu', COUNT(*), CONCAT('sys_menu', '_bak_', @bak_date), NULL, NULL
  FROM sys_menu WHERE menu_id = '9F2C0000000000000000000000000001' OR perms LIKE 'ctms:%';

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 't_code_config', COUNT(*), CONCAT('t_code_config', '_bak_', @bak_date), NULL, NULL
  FROM t_code_config WHERE id = '9F2C0000000000000000000000C001';

INSERT INTO tmp_b3_report
SELECT '存量表（仅命中白名单的行）', 't_code_config_rule', COUNT(*), CONCAT('t_code_config_rule', '_bak_', @bak_date), NULL, NULL
  FROM t_code_config_rule WHERE config_id = '9F2C0000000000000000000000C001';

-- ---------------------------------------------------------------------------
-- 第三部分：生成快照（结构 + 数据）
--   * 新建表：整表快照（整表都可能被 DROP 掉）
--   * 存量表：仅快照命中白名单的那批行（本变更集只碰这些行）
-- ---------------------------------------------------------------------------

-- ---- t_ctms_contract（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract'),
  'DROP TABLE IF EXISTS t_ctms_contract_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract'),
  'CREATE TABLE t_ctms_contract_bak_20261005 LIKE t_ctms_contract', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_bak_20261005'),
  'INSERT INTO t_ctms_contract_bak_20261005 SELECT * FROM t_ctms_contract', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_item'),
  'DROP TABLE IF EXISTS t_ctms_contract_item_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_item'),
  'CREATE TABLE t_ctms_contract_item_bak_20261005 LIKE t_ctms_contract_item', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_item_bak_20261005'),
  'INSERT INTO t_ctms_contract_item_bak_20261005 SELECT * FROM t_ctms_contract_item', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_tag（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_tag'),
  'DROP TABLE IF EXISTS t_ctms_tag_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_tag'),
  'CREATE TABLE t_ctms_tag_bak_20261005 LIKE t_ctms_tag', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_tag_bak_20261005'),
  'INSERT INTO t_ctms_tag_bak_20261005 SELECT * FROM t_ctms_tag', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_tag（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_tag'),
  'DROP TABLE IF EXISTS t_ctms_contract_tag_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_tag'),
  'CREATE TABLE t_ctms_contract_tag_bak_20261005 LIKE t_ctms_contract_tag', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_tag_bak_20261005'),
  'INSERT INTO t_ctms_contract_tag_bak_20261005 SELECT * FROM t_ctms_contract_tag', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_change_log（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_change_log'),
  'DROP TABLE IF EXISTS t_ctms_change_log_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_change_log'),
  'CREATE TABLE t_ctms_change_log_bak_20261005 LIKE t_ctms_change_log', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_change_log_bak_20261005'),
  'INSERT INTO t_ctms_change_log_bak_20261005 SELECT * FROM t_ctms_change_log', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_attachment（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment'),
  'DROP TABLE IF EXISTS t_ctms_attachment_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment'),
  'CREATE TABLE t_ctms_attachment_bak_20261005 LIKE t_ctms_attachment', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment_bak_20261005'),
  'INSERT INTO t_ctms_attachment_bak_20261005 SELECT * FROM t_ctms_attachment', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_customer（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_customer'),
  'DROP TABLE IF EXISTS t_ctms_customer_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_customer'),
  'CREATE TABLE t_ctms_customer_bak_20261005 LIKE t_ctms_customer', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_customer_bak_20261005'),
  'INSERT INTO t_ctms_customer_bak_20261005 SELECT * FROM t_ctms_customer', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_supplier（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_supplier'),
  'DROP TABLE IF EXISTS t_ctms_supplier_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_supplier'),
  'CREATE TABLE t_ctms_supplier_bak_20261005 LIKE t_ctms_supplier', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_supplier_bak_20261005'),
  'INSERT INTO t_ctms_supplier_bak_20261005 SELECT * FROM t_ctms_supplier', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_party_draft（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_party_draft'),
  'DROP TABLE IF EXISTS t_ctms_party_draft_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_party_draft'),
  'CREATE TABLE t_ctms_party_draft_bak_20261005 LIKE t_ctms_party_draft', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_party_draft_bak_20261005'),
  'INSERT INTO t_ctms_party_draft_bak_20261005 SELECT * FROM t_ctms_party_draft', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_product_type（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_type'),
  'DROP TABLE IF EXISTS t_ctms_product_type_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_type'),
  'CREATE TABLE t_ctms_product_type_bak_20261005 LIKE t_ctms_product_type', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_type_bak_20261005'),
  'INSERT INTO t_ctms_product_type_bak_20261005 SELECT * FROM t_ctms_product_type', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_uom（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_uom'),
  'DROP TABLE IF EXISTS t_ctms_uom_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_uom'),
  'CREATE TABLE t_ctms_uom_bak_20261005 LIKE t_ctms_uom', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_uom_bak_20261005'),
  'INSERT INTO t_ctms_uom_bak_20261005 SELECT * FROM t_ctms_uom', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_warehouse（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_warehouse'),
  'DROP TABLE IF EXISTS t_ctms_warehouse_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_warehouse'),
  'CREATE TABLE t_ctms_warehouse_bak_20261005 LIKE t_ctms_warehouse', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_warehouse_bak_20261005'),
  'INSERT INTO t_ctms_warehouse_bak_20261005 SELECT * FROM t_ctms_warehouse', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_product（新建表：整表快照） ----
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product'),
  'DROP TABLE IF EXISTS t_ctms_product_bak_20261005', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product'),
  'CREATE TABLE t_ctms_product_bak_20261005 LIKE t_ctms_product', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_bak_20261005'),
  'INSERT INTO t_ctms_product_bak_20261005 SELECT * FROM t_ctms_product', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- sys_dict_type（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS sys_dict_type_bak_20261005;
CREATE TABLE sys_dict_type_bak_20261005 LIKE sys_dict_type;
INSERT INTO sys_dict_type_bak_20261005 SELECT * FROM sys_dict_type WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses');

-- ---- sys_dict_data（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS sys_dict_data_bak_20261005;
CREATE TABLE sys_dict_data_bak_20261005 LIKE sys_dict_data;
INSERT INTO sys_dict_data_bak_20261005 SELECT * FROM sys_dict_data WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses');

-- ---- sys_config（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS sys_config_bak_20261005;
CREATE TABLE sys_config_bak_20261005 LIKE sys_config;
INSERT INTO sys_config_bak_20261005 SELECT * FROM sys_config WHERE config_key = 'warranty_window_days';

-- ---- sys_menu（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS sys_menu_bak_20261005;
CREATE TABLE sys_menu_bak_20261005 LIKE sys_menu;
INSERT INTO sys_menu_bak_20261005 SELECT * FROM sys_menu WHERE menu_id = '9F2C0000000000000000000000000001' OR perms LIKE 'ctms:%';

-- ---- t_code_config（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS t_code_config_bak_20261005;
CREATE TABLE t_code_config_bak_20261005 LIKE t_code_config;
INSERT INTO t_code_config_bak_20261005 SELECT * FROM t_code_config WHERE id = '9F2C0000000000000000000000C001';

-- ---- t_code_config_rule（存量表：仅快照命中白名单的行） ----
DROP TABLE IF EXISTS t_code_config_rule_bak_20261005;
CREATE TABLE t_code_config_rule_bak_20261005 LIKE t_code_config_rule;
INSERT INTO t_code_config_rule_bak_20261005 SELECT * FROM t_code_config_rule WHERE config_id = '9F2C0000000000000000000000C001';

-- ---------------------------------------------------------------------------
-- 第四部分：填「快照行数」并输出总报告
-- ---------------------------------------------------------------------------

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_contract_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_contract''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_contract''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_item_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_contract_item_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_contract_item''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_contract_item''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_tag_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_tag_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_tag''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_tag''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_contract_tag_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_contract_tag_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_contract_tag''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_contract_tag''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_change_log_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_change_log_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_change_log''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_change_log''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_attachment_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_attachment''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_attachment''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_customer_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_customer_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_customer''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_customer''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_supplier_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_supplier_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_supplier''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_supplier''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_party_draft_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_party_draft_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_party_draft''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_party_draft''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_type_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_product_type_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_product_type''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_product_type''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_uom_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_uom_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_uom''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_uom''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_warehouse_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_warehouse_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_warehouse''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_warehouse''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_product_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_ctms_product_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_ctms_product''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_ctms_product''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_dict_type_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM sys_dict_type_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''sys_dict_type''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''sys_dict_type''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_dict_data_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM sys_dict_data_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''sys_dict_data''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''sys_dict_data''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_config_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM sys_config_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''sys_config''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''sys_config''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_menu_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM sys_menu_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''sys_menu''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''sys_menu''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_code_config_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_code_config_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_code_config''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_code_config''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_code_config_rule_bak_20261005'),
  'UPDATE tmp_b3_report SET 快照行数 = (SELECT COUNT(*) FROM t_code_config_rule_bak_20261005), 说明 = ''快照已生成'' WHERE 表名 = ''t_code_config_rule''', 'UPDATE tmp_b3_report SET 快照行数 = NULL, 说明 = CONCAT(IFNULL(说明, ''''), '' / 未生成快照'') WHERE 表名 = ''t_code_config_rule''');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT `类别`, `表名`, `受影响行数`, `快照表`, `快照行数`, IFNULL(`说明`, '') AS `说明`
  FROM tmp_b3_report
 ORDER BY `类别` DESC, `表名`;

-- 一致性核对：有快照的行，「快照行数」必须等于「受影响行数」
SELECT CASE WHEN COUNT(*) = 0 THEN '通过：所有已生成快照的行数都与受影响行数一致'
            ELSE CONCAT('不通过：有 ', COUNT(*), ' 行的快照行数与受影响行数不一致，请勿继续') END AS `核对结果`
  FROM tmp_b3_report
 WHERE 快照行数 IS NOT NULL AND 受影响行数 IS NOT NULL AND 快照行数 <> 受影响行数;

SELECT CASE WHEN COUNT(*) = 0 THEN '通过：需要快照的行都已生成快照'
            ELSE CONCAT('注意：有 ', COUNT(*), ' 行没有快照（表不存在或跳过），回滚脚本会在缺快照时拒绝执行') END AS `快照完整性`
  FROM tmp_b3_report WHERE 快照行数 IS NULL;