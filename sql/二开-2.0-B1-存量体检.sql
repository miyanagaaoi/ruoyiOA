-- ============================================================================
-- OA 2.0 · B1 §1.2 存量体检（**只读**，不修改任何业务数据）
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 1.2）｜需求：REQ-DATA-004｜验收：AC-68
--
-- 背景：`updateTemplate` 的历史实现是"旧行置 enable_flag='0'+del_flag='1'，插入新 UUID"，
--       于是同一条模板在库里会留下多行；**配置类**子表（打印模板、节点字段权限）的
--       `template_id` 可能挂在废弃行上，而界面只认启用行 —— 这就是
--       "编辑一次模板，打印模板配置 / 节点字段权限就失联"。
--       2.0 已把 `updateTemplate` 改为原地 UPDATE（B1 §1.1），本组负责把**存量**归并干净。
--
-- 用途：在跑归并迁移（`二开-2.0-B1-存量归并.sql`）**之前**回答三个问题：
--   ① 哪些废弃行能安全归并、映射关系是什么；
--   ② 迁移会改动每张配置表的多少行（影响行数报告）；
--   ③ 哪些废弃行 / 哪些配置行**不会**被改动（原因）。
--
-- 用法（**在库副本上执行，勿在业务库直接跑**）：
--   $sql = "sql\二开-2.0-B1-存量体检.sql"
--   Get-Content -Raw -Encoding UTF8 $sql | mysql --host=127.0.0.1 --user=root `
--     --database=rad_oa_b1copy --default-character-set=utf8mb4 --table
--   （中文文件名不能直接当命令行参数传给 mysql.exe，必须走 stdin 管道；
--     详见 DEV-ENV.md「2.0 存量归并迁移」一节的固定命令。）
--
-- =========================== 规则（三份脚本必须同规则）===========================
-- A. "版本链" = t_template 中 (name, type, form_id) 三者相同的行。
--    链上 del_flag='0' 的是**启用行**，del_flag='1' 的是**废弃行**。
--    仅当链上**恰好一个启用行**时，链上废弃行才映射到它；0 个或 ≥2 个一律不动
--    （判不准的后继宁可不动，也不能改错数据）。
--
-- B. **只搬"配置类"表，不搬业务/历史表**。白名单：
--      t_template_print_template   打印模板配置
--      t_template_node_field_auth  节点字段权限配置
--      t_template_submit_scope     B1 新增·可发起范围明细
--      t_template_flow_admin       B1 新增·流程管理员
--      t_template_related_approval B1 新增·关联审批候选模板
--    明确**不搬**（原因写在这里，避免后来人"顺手加进去"）：
--      t_workflow_form / _todo / _done / _recycle / _my_draft / _rece_template /
--      _entrust_template           在途与历史单据：必须保持"发起时那一版模板"的关联
--                                  （同 design D10：历史单据不按新表单重渲染）
--      t_print_log                 打印日志（历史事实）
--      t_template_attachment / t_template_main_text / t_template_message_notice
--                                  随模板版本行走的配置，且读取器**返回单对象**：
--                                  并到启用行会与启用行自己的配置行冲突
--                                  （多行 → TooManyResultsException → 编辑页 500）
--
-- C. 即使命中了映射，**只有"启用行在该表里一行都没有"时才搬**，且链上多个废弃行时
--    **只搬最新的一行**（最接近启用行的那一版）。理由：启用行自己的配置才是"当前有效"
--    的那套（历史实现每次编辑都会在**新行**下写配置）；再并一套进去只会产生二义
--    （打印模板出现两套启用、节点字段权限撞唯一键 uk_node_field）。
-- ============================================================================

DROP PROCEDURE IF EXISTS `sp_oa_b1_scope_check`;

DELIMITER $$
CREATE PROCEDURE `sp_oa_b1_scope_check`()
BEGIN
    DECLARE v_done      INT DEFAULT 0;
    DECLARE v_tbl       VARCHAR(64);
    DECLARE v_coll      VARCHAR(64);
    DECLARE v_total_ref BIGINT DEFAULT 0;
    DECLARE v_move_ref  BIGINT DEFAULT 0;

    DECLARE v_abandoned BIGINT DEFAULT 0;
    DECLARE v_mapped    BIGINT DEFAULT 0;
    DECLARE v_targets   BIGINT DEFAULT 0;
    DECLARE v_unmapped  BIGINT DEFAULT 0;
    DECLARE v_move_all  BIGINT DEFAULT 0;
    DECLARE v_keep_all  BIGINT DEFAULT 0;

    -- 临时表的排序规则必须与业务表一致：MySQL 下"列 vs 列"比较要求两侧排序规则相同，
    -- 否则报 ERROR 1267（实测：库默认 utf8mb4_general_ci，t_template.id 却是 utf8mb4_0900_ai_ci）。
    SELECT COLLATION_NAME INTO v_coll
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template' AND COLUMN_NAME = 'id'
     LIMIT 1;
    SET v_coll = IFNULL(v_coll, 'utf8mb4_general_ci');

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map;
    SET @ddl = CONCAT('CREATE TEMPORARY TABLE tmp_oa_tpl_map (',
                      'old_template_id VARCHAR(64) NOT NULL COMMENT ''废弃行'',',
                      'new_template_id VARCHAR(64) NOT NULL COMMENT ''启用行（归并目标）'',',
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
                      'total_ref BIGINT NOT NULL DEFAULT 0,',
                      'move_ref  BIGINT NOT NULL DEFAULT 0,',
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

    -- ---------- 2. 逐配置表统计（规则 B 白名单 + 规则 C 计划） ----------
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

            -- 2.1 挂在废弃行上的总行数
            SET @sql = CONCAT('SELECT COUNT(*) INTO @cnt FROM `', v_tbl,
                              '` x JOIN tmp_oa_tpl_map m ON x.template_id = m.old_template_id');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET v_total_ref = IFNULL(@cnt, 0);

            -- 2.2 本次会搬的行：启用行在该表为空 → 取链上最新的、且该表有行的废弃行
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

            -- 注意：存储过程局部变量的排序规则取自**库默认**（可能是 utf8mb4_general_ci），
            -- 而业务列可能是 utf8mb4_0900_ai_ci —— "变量 vs 列"比较同样报 ERROR 1267。
            -- 因此凡是与列比较的地方一律走动态 SQL 把变量**内联成字面量**（字面量会让位给列的排序规则）。
            SET @sql = CONCAT('DELETE FROM tmp_oa_move_plan WHERE child_table = ''', v_tbl,
                              ''' AND old_template_id IS NULL');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

            SET @sql = CONCAT('SELECT COUNT(*) INTO @cnt FROM `', v_tbl, '` x ',
                              'JOIN tmp_oa_move_plan p ON p.old_template_id = x.template_id ',
                              'WHERE p.child_table = ''', v_tbl, '''');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET v_move_ref = IFNULL(@cnt, 0);

            INSERT INTO tmp_oa_child_stat (child_table, total_ref, move_ref)
            VALUES (v_tbl, v_total_ref, v_move_ref);
        END LOOP;
        CLOSE cur_tbl;
    END;

    -- ---------- 3. 报告 ----------
    SELECT COUNT(*) INTO v_abandoned FROM t_template WHERE del_flag = '1';
    SELECT COUNT(*) INTO v_mapped    FROM tmp_oa_tpl_map;
    SELECT COUNT(*) INTO v_targets   FROM tmp_oa_tpl_map_tgt;
    SET v_unmapped = v_abandoned - v_mapped;
    SELECT IFNULL(SUM(move_ref), 0), IFNULL(SUM(total_ref - move_ref), 0)
      INTO v_move_all, v_keep_all
      FROM tmp_oa_child_stat;

    SELECT '① 模板废弃行（del_flag=1）总数'              AS `项目`, v_abandoned AS `数值`
    UNION ALL SELECT '② 可安全归并的废弃行数',            v_mapped
    UNION ALL SELECT '③ 归并涉及的启用行数（去重）',       v_targets
    UNION ALL SELECT '④ 无法归并的废弃行数（见明细 D）',   v_unmapped
    UNION ALL SELECT '⑤ 本次会搬走的配置行数',            v_move_all
    UNION ALL SELECT '⑥ 保留不动的配置行数（见明细 C）',   v_keep_all;

    -- B. 映射明细（迁移脚本按这张表改数据）
    SELECT m.old_template_id AS `废弃行ID`, m.new_template_id AS `启用行ID(归并目标)`,
           a.name AS `模板名称`, a.type AS `模板分类`, a.create_time AS `废弃行创建时间`
      FROM tmp_oa_tpl_map m
      JOIN t_template a ON a.id = m.old_template_id
     ORDER BY a.name, a.create_time, m.old_template_id;

    -- C. 影响行数报告：每张配置表会被改动多少行
    SELECT child_table AS `配置表`, total_ref AS `挂在废弃行上的行数`,
           move_ref AS `本次会搬走`, total_ref - move_ref AS `保留不动`
      FROM tmp_oa_child_stat
     ORDER BY move_ref DESC, child_table;

    -- D. 无法归并的废弃行 + 原因（迁移脚本不会碰这些行）
    SELECT a.id AS `废弃行ID`, a.name AS `模板名称`, a.type AS `模板分类`,
           CASE
             WHEN NOT EXISTS (SELECT 1 FROM t_template x
                               WHERE x.name = a.name AND x.type = a.type
                                 AND (x.form_id <=> a.form_id) AND x.del_flag = '0')
               THEN '链上没有启用行（该模板已不存在启用版本，无处可归）'
             ELSE '链上有多个启用行（同名同分类同表单，无法判定正确后继）'
           END AS `未归并原因`
      FROM t_template a
     WHERE a.del_flag = '1'
       AND a.id NOT IN (SELECT old_template_id FROM tmp_oa_tpl_map)
     ORDER BY a.name, a.id;

    -- 「保留不动」的行按行定位：
    --   SELECT * FROM <配置表> WHERE template_id IN (<结果集 B 的废弃行ID>);

    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_tpl_map_tgt;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_move_plan;
    DROP TEMPORARY TABLE IF EXISTS tmp_oa_child_stat;
END$$
DELIMITER ;

CALL `sp_oa_b1_scope_check`();
DROP PROCEDURE IF EXISTS `sp_oa_b1_scope_check`;
