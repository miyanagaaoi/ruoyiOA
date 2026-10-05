-- ============================================================================
-- 合同台账 · 约束回补前体检（2.0 B3 / oa-contract-ledger）
-- 变更集任务：§2.2｜需求：REQ-DATA-003｜验收：AC-68
-- 生成日期：20261005
-- ----------------------------------------------------------------------------
-- 用途：`二开-合同台账.sql` 的「约束回补三段」（回填 → 加 NOT NULL → 加 FK）会因为
--   历史数据里的 NULL 直接失败（MySQL 报 1048/1138）。**加约束之前**先跑本脚本：
--   它逐列告诉你这一列有多少行是 NULL、这些行是哪几条（附主键与违例值）。
--
-- 覆盖范围：**待收紧为非空的 13 列**（结论来自 `notes/ddl-review.md` 签字版）。
--   「保持可空、仅加索引」的那些引用列（如 warehouse.keeper_user_id、
--   party_draft.matched_id、contract.parent_id）**不在**范围内，不需要回填。
--
-- 只读承诺：本脚本只有 SELECT 与对**临时表**（tmp_b3_health / tmp_b3_before）的写入，
--   不修改任何业务表，也不创建任何 *_bak_* 快照表。表/列不存在时输出「不适用」而不是报错。
--
-- 判读：
--   * 「可加约束」→ 该列可以直接加 NOT NULL；
--   * 「需回填」  → 先按第三部分明细回填（口径见 ddl-review.md 对应表结论），再跑一遍直到全 0；
--   * 「已收紧（无需回填）」→ 已是 NOT NULL，跳过；
--   * 「不适用（表或列不存在）」→ 本变更集还没落地，跳过。
-- ============================================================================

DROP TEMPORARY TABLE IF EXISTS tmp_b3_health;
CREATE TEMPORARY TABLE tmp_b3_health (
  `表名`     VARCHAR(64)  NOT NULL,
  `列名`     VARCHAR(64)  NOT NULL,
  `违例行数` BIGINT       NULL COMMENT 'NULL = 不适用',
  `状态`     VARCHAR(32)  NOT NULL,
  `收紧目标` VARCHAR(64)  NOT NULL
) ENGINE = MEMORY;

-- ---------------------------------------------------------------------------
-- 第一部分：逐列统计违例（NULL 行数）
-- ---------------------------------------------------------------------------

-- ---- t_ctms_contract.dept_id → NOT NULL + FK(sys_dept.dept_id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'dept_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract'', ''dept_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(sys_dept.dept_id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_contract'', ''dept_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(sys_dept.dept_id)'' FROM t_ctms_contract WHERE dept_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract'', ''dept_id'', 0, ''可加约束'', ''NOT NULL + FK(sys_dept.dept_id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract.create_id → NOT NULL + FK(sys_user.user_id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'create_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract'', ''create_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(sys_user.user_id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_contract'', ''create_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(sys_user.user_id)'' FROM t_ctms_contract WHERE create_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract'', ''create_id'', 0, ''可加约束'', ''NOT NULL + FK(sys_user.user_id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_id → NOT NULL + FK(t_ctms_product.id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(t_ctms_product.id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_contract_item'', ''product_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(t_ctms_product.id)'' FROM t_ctms_contract_item WHERE product_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_id'', 0, ''可加约束'', ''NOT NULL + FK(t_ctms_product.id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_code → NOT NULL（无 FK，快照列）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_code');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_code'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（无 FK，快照列）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_contract_item'', ''product_code'', COUNT(*), ''需回填'', ''NOT NULL（无 FK，快照列）'' FROM t_ctms_contract_item WHERE product_code IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_code'', 0, ''可加约束'', ''NOT NULL（无 FK，快照列）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_name → NOT NULL（无 FK，快照列）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_name');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_name'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（无 FK，快照列）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_contract_item'', ''product_name'', COUNT(*), ''需回填'', ''NOT NULL（无 FK，快照列）'' FROM t_ctms_contract_item WHERE product_name IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_contract_item'', ''product_name'', 0, ''可加约束'', ''NOT NULL（无 FK，快照列）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_change_log.object_type → NOT NULL（多态，无 FK）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_type');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_change_log'', ''object_type'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（多态，无 FK）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_change_log'', ''object_type'', COUNT(*), ''需回填'', ''NOT NULL（多态，无 FK）'' FROM t_ctms_change_log WHERE object_type IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_change_log'', ''object_type'', 0, ''可加约束'', ''NOT NULL（多态，无 FK）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_change_log.object_id → NOT NULL（多态，无 FK）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_change_log'', ''object_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（多态，无 FK）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_change_log'', ''object_id'', COUNT(*), ''需回填'', ''NOT NULL（多态，无 FK）'' FROM t_ctms_change_log WHERE object_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_change_log'', ''object_id'', 0, ''可加约束'', ''NOT NULL（多态，无 FK）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_attachment.object_type → NOT NULL（多态，无 FK）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_type');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_attachment'', ''object_type'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（多态，无 FK）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_attachment'', ''object_type'', COUNT(*), ''需回填'', ''NOT NULL（多态，无 FK）'' FROM t_ctms_attachment WHERE object_type IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_attachment'', ''object_type'', 0, ''可加约束'', ''NOT NULL（多态，无 FK）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_attachment.object_id → NOT NULL（多态，无 FK）
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_attachment'', ''object_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL（多态，无 FK）'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_attachment'', ''object_id'', COUNT(*), ''需回填'', ''NOT NULL（多态，无 FK）'' FROM t_ctms_attachment WHERE object_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_attachment'', ''object_id'', 0, ''可加约束'', ''NOT NULL（多态，无 FK）'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_customer.create_id → NOT NULL + FK(sys_user.user_id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer' AND COLUMN_NAME = 'create_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_customer'', ''create_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(sys_user.user_id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_customer'', ''create_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(sys_user.user_id)'' FROM t_ctms_customer WHERE create_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_customer'', ''create_id'', 0, ''可加约束'', ''NOT NULL + FK(sys_user.user_id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_supplier.short_name → NOT NULL
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'short_name');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_supplier'', ''short_name'', NULL, ''不适用（表或列不存在）'', ''NOT NULL'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_supplier'', ''short_name'', COUNT(*), ''需回填'', ''NOT NULL'' FROM t_ctms_supplier WHERE short_name IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_supplier'', ''short_name'', 0, ''可加约束'', ''NOT NULL'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_supplier.create_id → NOT NULL + FK(sys_user.user_id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'create_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_supplier'', ''create_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(sys_user.user_id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_supplier'', ''create_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(sys_user.user_id)'' FROM t_ctms_supplier WHERE create_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_supplier'', ''create_id'', 0, ''可加约束'', ''NOT NULL + FK(sys_user.user_id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_product.create_id → NOT NULL + FK(sys_user.user_id)
SET @nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product' AND COLUMN_NAME = 'create_id');
SET @sql = IF(@nullable IS NULL,
  'INSERT INTO tmp_b3_health VALUES (''t_ctms_product'', ''create_id'', NULL, ''不适用（表或列不存在）'', ''NOT NULL + FK(sys_user.user_id)'')',
  IF(@nullable = 'YES', 'INSERT INTO tmp_b3_health SELECT ''t_ctms_product'', ''create_id'', COUNT(*), ''需回填'', ''NOT NULL + FK(sys_user.user_id)'' FROM t_ctms_product WHERE create_id IS NULL', 'INSERT INTO tmp_b3_health VALUES (''t_ctms_product'', ''create_id'', 0, ''可加约束'', ''NOT NULL + FK(sys_user.user_id)'')'));
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 第二部分：体检结论（先看这张）
-- ---------------------------------------------------------------------------
SELECT `表名`, `列名`, `违例行数`, `状态`, `收紧目标`
  FROM tmp_b3_health
 ORDER BY FIELD(`状态`, '需回填', '不适用（表或列不存在）', '可加约束', '已收紧（无需回填）'), `表名`, `列名`;

SELECT CONCAT('需回填 ', SUM(`状态` = '需回填'), ' 列；可直接加约束 ',
              SUM(`状态` = '可加约束'), ' 列；不适用 ', SUM(`状态` = '不适用（表或列不存在）'),
              ' 列；共核对 ', COUNT(*), ' 列') AS `体检结论`
  FROM tmp_b3_health;

-- ---------------------------------------------------------------------------
-- 第三部分：违例明细（附主键与违例值；每列最多 20 行，便于直接照着回填）
--   没有违例的列不会输出任何行。
-- ---------------------------------------------------------------------------

-- ---- t_ctms_contract.dept_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'dept_id'),
  'SELECT ''t_ctms_contract'' AS 表名, id AS 主键, ''dept_id'' AS 列名, dept_id AS 违例值 FROM t_ctms_contract WHERE dept_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract.create_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'create_id'),
  'SELECT ''t_ctms_contract'' AS 表名, id AS 主键, ''create_id'' AS 列名, create_id AS 违例值 FROM t_ctms_contract WHERE create_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_id'),
  'SELECT ''t_ctms_contract_item'' AS 表名, id AS 主键, ''product_id'' AS 列名, product_id AS 违例值 FROM t_ctms_contract_item WHERE product_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_code 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_code'),
  'SELECT ''t_ctms_contract_item'' AS 表名, id AS 主键, ''product_code'' AS 列名, product_code AS 违例值 FROM t_ctms_contract_item WHERE product_code IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_contract_item.product_name 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_name'),
  'SELECT ''t_ctms_contract_item'' AS 表名, id AS 主键, ''product_name'' AS 列名, product_name AS 违例值 FROM t_ctms_contract_item WHERE product_name IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_change_log.object_type 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_type'),
  'SELECT ''t_ctms_change_log'' AS 表名, id AS 主键, ''object_type'' AS 列名, object_type AS 违例值 FROM t_ctms_change_log WHERE object_type IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_change_log.object_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_id'),
  'SELECT ''t_ctms_change_log'' AS 表名, id AS 主键, ''object_id'' AS 列名, object_id AS 违例值 FROM t_ctms_change_log WHERE object_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_attachment.object_type 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_type'),
  'SELECT ''t_ctms_attachment'' AS 表名, id AS 主键, ''object_type'' AS 列名, object_type AS 违例值 FROM t_ctms_attachment WHERE object_type IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_attachment.object_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_id'),
  'SELECT ''t_ctms_attachment'' AS 表名, id AS 主键, ''object_id'' AS 列名, object_id AS 违例值 FROM t_ctms_attachment WHERE object_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_customer.create_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer' AND COLUMN_NAME = 'create_id'),
  'SELECT ''t_ctms_customer'' AS 表名, id AS 主键, ''create_id'' AS 列名, create_id AS 违例值 FROM t_ctms_customer WHERE create_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_supplier.short_name 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'short_name'),
  'SELECT ''t_ctms_supplier'' AS 表名, id AS 主键, ''short_name'' AS 列名, short_name AS 违例值 FROM t_ctms_supplier WHERE short_name IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_supplier.create_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'create_id'),
  'SELECT ''t_ctms_supplier'' AS 表名, id AS 主键, ''create_id'' AS 列名, create_id AS 违例值 FROM t_ctms_supplier WHERE create_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- t_ctms_product.create_id 明细
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product' AND COLUMN_NAME = 'create_id'),
  'SELECT ''t_ctms_product'' AS 表名, id AS 主键, ''create_id'' AS 列名, create_id AS 违例值 FROM t_ctms_product WHERE create_id IS NULL LIMIT 20', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------------
-- 第四部分：只读自证（13 张业务表的行数；表不存在时跳过）
--   把这一部分在执行前后各跑一次，行数必须完全一致。
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_b3_before;
CREATE TEMPORARY TABLE tmp_b3_before (
  `表名` VARCHAR(64) NOT NULL, `行数` BIGINT NULL, `说明` VARCHAR(64) NULL
) ENGINE = MEMORY;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_contract'', COUNT(*), ''已统计'' FROM t_ctms_contract', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_contract'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_contract_item'', COUNT(*), ''已统计'' FROM t_ctms_contract_item', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_contract_item'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_tag'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_tag'', COUNT(*), ''已统计'' FROM t_ctms_tag', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_tag'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_tag'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_contract_tag'', COUNT(*), ''已统计'' FROM t_ctms_contract_tag', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_contract_tag'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_change_log'', COUNT(*), ''已统计'' FROM t_ctms_change_log', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_change_log'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_attachment'', COUNT(*), ''已统计'' FROM t_ctms_attachment', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_attachment'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_customer'', COUNT(*), ''已统计'' FROM t_ctms_customer', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_customer'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_supplier'', COUNT(*), ''已统计'' FROM t_ctms_supplier', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_supplier'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_party_draft'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_party_draft'', COUNT(*), ''已统计'' FROM t_ctms_party_draft', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_party_draft'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product_type'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_product_type'', COUNT(*), ''已统计'' FROM t_ctms_product_type', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_product_type'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_uom'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_uom'', COUNT(*), ''已统计'' FROM t_ctms_uom', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_uom'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_warehouse'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_warehouse'', COUNT(*), ''已统计'' FROM t_ctms_warehouse', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_warehouse'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product'),
  'INSERT INTO tmp_b3_before SELECT ''t_ctms_product'', COUNT(*), ''已统计'' FROM t_ctms_product', 'INSERT INTO tmp_b3_before VALUES (''t_ctms_product'', NULL, ''表不存在，跳过'')');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT `表名`, `行数`, IFNULL(`说明`, '') AS `说明` FROM tmp_b3_before ORDER BY `表名`;
SELECT '只读自证：上面这张表在执行前后必须完全一致（本脚本不改业务表数据）。' AS `说明`;