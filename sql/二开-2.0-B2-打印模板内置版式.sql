-- ============================================================================
-- OA 2.0 · B2 打印模板内置版式（t_template.builtin_print_key + 默认值语义统一）
-- 变更集：openspec/changes/oa-print-builtin-templates（任务 1.1、3.3）
-- 需求：REQ-PRINT-011、REQ-PRINT-012｜验收：AC-61、AC-62
--
-- 口径（PRD 第 8.4 节方案 B / design.md D9）：
--   * `table.sql` / `data.sql` 是**上游原样基线**，不改；
--   * 二开增量文件是唯一真源，本文件**幂等**、**可反复执行**；
--   * 本文件**自包含**：不依赖 B1（oa-form-flow-tabs）的任何列，
--     即使 B1 未执行过也能独立跑通（design.md D9 的硬要求）。
--
-- 两件事：
--   ① `t_template` 增列 `builtin_print_key varchar(32) DEFAULT 'contract'`
--      —— 内置打印版式由"全局唯一常量"改为"按单据类型选用"（REQ-PRINT-011）。
--      ⚠ B1 的 `二开-2.0-B1-t_template增列.sql` 里**也**有这一列（两批次共享表结构变更，
--        要求同批次交付）。两边都带 `information_schema` 守卫，先跑谁都不会报错。
--   ② `t_template_print_template` 的 `show_signature` / `show_attachment` **默认值对齐为 '0'**
--      —— 原 DDL 默认 '1'，而服务端 `PrintServiceImpl.fillDefaults` 空值填 '0'，
--        导致"直插库"与"走接口"两条路径行为不同（勘察 §5.2 的语义分歧）。
--        以**服务端口径为唯一口径**（空值 → 关闭 = 只打印表单信息）。
--      ⚠ `ALTER ... SET DEFAULT` **只改默认值，不动任何已落库的存量行**（design D5）。
--
-- 用法（中文文件名不能当命令行参数，走 stdin）：
--   Get-Content -Raw -Encoding UTF8 .\二开-2.0-B2-打印模板内置版式.sql |
--     mysql --host=127.0.0.1 --user=root --database=rad_oa --default-character-set=utf8mb4
-- ============================================================================

-- ---------- ① t_template.builtin_print_key ----------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template'
        AND COLUMN_NAME = 'builtin_print_key') = 0,
    "ALTER TABLE `t_template` ADD COLUMN `builtin_print_key` varchar(32) DEFAULT 'contract' COMMENT '内置打印版式键：contract/fund/matter/payment（B2）'",
    'SELECT ''skip: t_template.builtin_print_key 已存在'' AS `提示`'
));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ② 打印模板的默认值语义统一（只改默认值，不改存量行） ----------
-- 幂等判据：information_schema.COLUMNS.COLUMN_DEFAULT 当前不是 '0' 才改。
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_print_template'
        AND COLUMN_NAME = 'show_signature' AND COLUMN_DEFAULT <> '0') > 0,
    "ALTER TABLE `t_template_print_template` MODIFY COLUMN `show_signature` char(1) DEFAULT '0' COMMENT '是否打印签批栏（含签名），0-否，1-是；空值口径=关闭'",
    'SELECT ''skip: show_signature 默认值已是 0'' AS `提示`'
));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_print_template'
        AND COLUMN_NAME = 'show_attachment' AND COLUMN_DEFAULT <> '0') > 0,
    "ALTER TABLE `t_template_print_template` MODIFY COLUMN `show_attachment` char(1) DEFAULT '0' COMMENT '是否打印附件清单，0-否，1-是；空值口径=关闭'",
    'SELECT ''skip: show_attachment 默认值已是 0'' AS `提示`'
));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③ 编排自检（任务 1.1 / 3.3 的完成判定） ----------
SELECT '① t_template.builtin_print_key 存在且默认 contract（应为1）' AS `检查项`,
       COUNT(*) AS `结果`
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template'
   AND COLUMN_NAME = 'builtin_print_key' AND COLUMN_DEFAULT = 'contract'
UNION ALL
SELECT '② 存量行的 builtin_print_key 非空（应为0）', COUNT(*)
  FROM t_template WHERE builtin_print_key IS NULL
UNION ALL
SELECT '③ show_signature 默认 0（应为1）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_print_template'
   AND COLUMN_NAME = 'show_signature' AND COLUMN_DEFAULT = '0'
UNION ALL
SELECT '④ show_attachment 默认 0（应为1）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template_print_template'
   AND COLUMN_NAME = 'show_attachment' AND COLUMN_DEFAULT = '0';
