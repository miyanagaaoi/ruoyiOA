-- ============================================================================
-- OA 2.0 · B1 §2.4 二开增量 SQL 初始化编排
-- 变更集：openspec/changes/oa-form-flow-tabs（任务 2.4）｜需求：REQ-DATA-005｜验收：AC-68
--
-- 口径（PRD 第 8.4 节方案 B）：8 个 `二开-*.sql` 增量文件是**唯一真源**，
--   `table.sql` / `data.sql` 保持上游原样不动；本文件只负责**按序 source 全部增量**。
--
-- ⚠️⚠️ 只用于**空库初始化**。部分 V1 增量脚本用的是 `DROP TABLE IF EXISTS` + `CREATE`
--      （例如 `二开-简化流程设计器.sql` 会 DROP `t_flow_simple`），
--      在**已有数据**的库上执行会丢数据。
--      已上线的库请只跑本文件末尾的 2.0 部分（B1 两个脚本是幂等的：条件增列 +
--      `CREATE TABLE IF NOT EXISTS`，可反复执行）。
--
-- 用法（**必须从 sql 目录执行**，`source` 用的是相对路径）：
--   cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master\sql
--   Get-Content -Raw -Encoding UTF8 .\初始化-全部.sql |
--     mysql --host=127.0.0.1 --user=root --database=<空库名> --default-character-set=utf8mb4
--
-- 顺序理由：先表后菜单（菜单里引用表/权限）、签名表先于签名防篡改触发器、
--           2.0 的 `t_template` 增列先于新增表（新增表要按 t_template.id 的排序规则建）。
-- ============================================================================

-- ---------- V1 二开增量（顺序不可乱） ----------
source 二开-简化流程设计器.sql
source 二开-简化流程设计器-菜单.sql
source 二开-节点字段权限.sql
source 二开-表单打印.sql
source 二开-打印模板菜单.sql
source 二开-签名记录.sql
source 二开-预存签名.sql
source 二开-签名防篡改.sql

-- ---------- 2.0 B1 二开增量（幂等，可单独重跑） ----------
source 二开-2.0-B1-t_template增列.sql
source 二开-2.0-B1-模板新增表.sql

-- ---------- 2.0 B2 二开增量（幂等，可单独重跑） ----------
-- 也含 t_template.builtin_print_key（与 B1 共享的表结构变更，两边都有 information_schema 守卫）
source 二开-2.0-B2-打印模板内置版式.sql

-- ---------- 2.0 B3 二开增量（合同台账；幂等，可单独重跑） ----------
-- 顺序理由（见 notes/ddl-scope.md §6）：
--   ① 表在前、种子在后：字典/菜单都引用表与权限点；
--   ② 字典（contract_types/subjects）必须在编号配置之前 —— 编号配置的"类型码/主体码"
--      语义就来自这两个字典；
--   ③ 菜单最后（它只依赖权限点，与数据结构无关）。
source 二开-合同台账.sql
source 二开-合同台账-字典参数.sql
source 二开-合同台账-编号配置.sql
source 二开-合同台账-菜单.sql

-- ---------- 2.0 B4 二开增量（进销存；尚未交付） ----------
-- ⚠️ 占位段：B4（openspec/changes/oa-purchase-sales-stock）交付后，把下面这行注释替换成
--    source 二开-进销存.sql
--    B4 的 18 张表（8 单据表头 + 8 行项 + 结存 + 流水）由 B4 自己的增量文件负责；
--    它**只引用** B3 交付的 t_ctms_product/t_ctms_uom/t_ctms_warehouse/t_ctms_contract，
--    不得重建（边界见 openspec/changes/oa-contract-ledger/notes/ddl-scope.md §2/§4）。
-- source 二开-进销存.sql

-- ---------- 编排自检（任务 2.4 / B2 任务 1.1 / B3 任务 2.6 完成判定） ----------
SELECT '① t_template 的 13 个 2.0 列齐备（应为13）' AS `检查项`,
       COUNT(*) AS `结果`
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_template'
   AND COLUMN_NAME IN ('icon','submit_scope_type','include_child_dept','simple_flow_id','flow_mode',
                       'builtin_print_key','revoke_limit_minutes','allow_edit_after_submit',
                       'allow_submit_for_other','allow_approver_revoke','allow_batch_approve',
                       'approver_dedup','forward_scope')
UNION ALL
SELECT '② 2.0 新增 4 张表齐备（应为4）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_template_submit_scope','t_template_flow_admin',
                      't_template_related_approval','t_workflow_related_approval')
UNION ALL
SELECT '③ V1 二开表齐备（应为8）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_flow_simple','t_flow_simple_history','t_template_node_field_auth',
                      't_template_print_template','t_print_log','t_user_sign_preset',
                      't_workflow_main_seal','t_workflow_sign_record')
UNION ALL
-- ---- B3（合同台账）：13 张表 + 5 个字典类型 + 1 个系统参数 + 27 行菜单（1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮，其中 26 行带 ctms:* 权限点） ----
SELECT '④ B3 的 13 张业务表齐备（应为13）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse',
                      't_ctms_product')
UNION ALL
SELECT '⑤ B3 字典类型齐备（应为5）', COUNT(*)
  FROM sys_dict_type
 WHERE dict_type IN ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')
UNION ALL
SELECT '⑥ B3 质保窗口参数（应为1）', COUNT(*)
  FROM sys_config WHERE config_key = 'warranty_window_days'
UNION ALL
SELECT '⑦ B3 菜单行数（应为27 = 1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮；其中 26 行带权限点）', COUNT(*)
  FROM sys_menu
 WHERE menu_id = '9F2C0000000000000000000000000001' OR perms LIKE 'ctms:%'
UNION ALL
SELECT '⑧ B3 编号规则配置（应为1）', COUNT(*)
  FROM t_code_config WHERE id LIKE '9F2C%' AND title LIKE '%合同%'
UNION ALL
SELECT '⑨ B4 段是否仍为占位（应为0；交付后应变为18）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_order','t_ctms_sales_request',
                      't_ctms_sales_order','t_ctms_stock_in','t_ctms_stock_out',
                      't_ctms_stocktake','t_ctms_transfer');

-- 收尾提示：本文件末尾的 SELECT 会列出 9 项检查。①~⑧ 必须全部等于期望值；
-- ⑨ 在 B4 交付前应为 0（表示尚未落地，不是错误）。
-- 人工步骤：非 superAdmin 角色需到「系统管理 → 角色管理」勾选合同管理菜单与按钮。
