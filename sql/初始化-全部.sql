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

-- ---------- 2.0 B4 二开增量（进销存；幂等，可单独重跑） ----------
-- 顺序理由（见 notes/ddl-scope.md §6 的 ⑥ 段）：B4 只加自己的 18 张表
--   （8 单据表头 + 8 行项 + 结存 t_ctms_stock + 流水 t_ctms_stock_ledger）与 4 个字典类型
--   + 1 个系统参数（stock_allow_negative）；它**只引用** B3 交付的
--   t_ctms_product/t_ctms_uom/t_ctms_warehouse/t_ctms_contract/t_ctms_customer/t_ctms_supplier，
--   不得重建（边界见 openspec/changes/oa-contract-ledger/notes/ddl-scope.md §2/§4）。
--   故**必须晚于 ④ B3 段**：B4 的外键引用 B3 的档案表与 t_ctms_contract。
--   本段在 B3 交付前是注释占位；B4 交付后（2026-10-05）为下面这行真实 source。
source 二开-进销存.sql
source 二开-进销存-菜单.sql

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
-- ⑦ 的口径（2026-10-05 修正，captain 裁决）：B4 的库存主数据 4 个 C 菜单
--    （…0131-…0134）按裁决 (a) 挂的正是 B3 的 ctms:partner:list，会被 `perms LIKE 'ctms:%'`
--    网住（27 → 31）。因此这里**收窄范围**而不是改期望值：只认 B3 自己的 id 段
--    （根 …0001 与 ≤ …0041 的 C 段 / F 段）。括号必须显式写 —— OR/AND 优先级不靠记忆。
--    收窄后：现网（含 B4 菜单）与"只跑 B3 段"两种场景都恒为 27。
SELECT '⑦ B3 菜单行数（应为27 = 1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮；其中 26 行带权限点）', COUNT(*)
  FROM sys_menu
 WHERE menu_id = '9F2C0000000000000000000000000001'
    OR (perms LIKE 'ctms:%' AND menu_id <= '9F2C0000000000000000000000000041')
UNION ALL
SELECT '⑧ B3 编号规则配置（应为1）', COUNT(*)
  FROM t_code_config WHERE id LIKE '9F2C%' AND title LIKE '%合同%'
UNION ALL
SELECT '⑨ B4 的 18 张表齐备（应为18）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_request_item',
                      't_ctms_purchase_order','t_ctms_purchase_order_item',
                      't_ctms_sales_request','t_ctms_sales_request_item',
                      't_ctms_sales_order','t_ctms_sales_order_item',
                      't_ctms_stock_in','t_ctms_stock_in_item',
                      't_ctms_stock_out','t_ctms_stock_out_item',
                      't_ctms_stocktake','t_ctms_stocktake_item',
                      't_ctms_transfer','t_ctms_transfer_item',
                      't_ctms_stock','t_ctms_stock_ledger')
UNION ALL
SELECT '⑩ B4 冻结的 8 个表头名齐备（应为8）', COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_order','t_ctms_sales_request',
                      't_ctms_sales_order','t_ctms_stock_in','t_ctms_stock_out',
                      't_ctms_stocktake','t_ctms_transfer')
UNION ALL
SELECT '⑪ B4 字典类型齐备（应为4：入库类型/出库类型/盘点范围/库存业务类型）', COUNT(*)
  FROM sys_dict_type
 WHERE dict_type IN ('stock_in_types','stock_out_types','stock_take_types','stock_biz_types')
UNION ALL
SELECT '⑫ B4 负库存参数（应为1 且默认关闭）', COUNT(*)
  FROM sys_config WHERE config_key = 'stock_allow_negative' AND config_value = 'false'
UNION ALL
SELECT '⑬ B4 单据编号配置齐备（应为8：PR/PO/SR/SO/IN/OUT/ST/DB）', COUNT(*)
  FROM t_code_config
 WHERE id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
              '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
              '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
              '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND enable_flag = '1' AND del_flag = '0'
UNION ALL
SELECT '⑭ B4 单据编号规则条数（应为 8×3=24）', COUNT(*)
  FROM t_code_config_rule
 WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                     '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                     '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                     '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND del_flag = '0'
UNION ALL
-- §8.1-A 本批菜单段用**精确 id 白名单**计数（不用区间：区间会把将来别人插进同一 id 段的行也算进来）。
--    ⚠ 编号说明：原 ①~⑭ 与 backend-base 追加的 ⑮~㉚ 已占用圆号，`二开-合同台账.sql` 又用了 ㉜/㉝，
--    故本条与下条改用**无歧义前缀** `§8.1-A` / `§8.1-B`，不再抢圆号。
--    121 行 = 2 目录（…0002/…0003）+ 22 菜单（…0101-010A、…0111-0118、…0131-0134）+ 97 按钮（…0201-…0292）。
SELECT '§8.1-A B4 菜单段行数（应为 121 = 2 目录 + 22 菜单 + 97 按钮；精确 id 白名单）', COUNT(*)
  FROM sys_menu
 WHERE menu_id IN (
     '9F2C0000000000000000000000000002', '9F2C0000000000000000000000000003',
     '9F2C0000000000000000000000000101', '9F2C0000000000000000000000000102',
     '9F2C0000000000000000000000000103', '9F2C0000000000000000000000000104',
     '9F2C0000000000000000000000000105', '9F2C0000000000000000000000000106',
     '9F2C0000000000000000000000000107', '9F2C0000000000000000000000000108',
     '9F2C0000000000000000000000000109', '9F2C000000000000000000000000010A',
     '9F2C0000000000000000000000000111', '9F2C0000000000000000000000000112',
     '9F2C0000000000000000000000000113', '9F2C0000000000000000000000000114',
     '9F2C0000000000000000000000000115', '9F2C0000000000000000000000000116',
     '9F2C0000000000000000000000000117', '9F2C0000000000000000000000000118',
     '9F2C0000000000000000000000000131', '9F2C0000000000000000000000000132',
     '9F2C0000000000000000000000000133', '9F2C0000000000000000000000000134',
     '9F2C0000000000000000000000000201', '9F2C0000000000000000000000000202',
     '9F2C0000000000000000000000000203', '9F2C0000000000000000000000000204',
     '9F2C0000000000000000000000000205', '9F2C0000000000000000000000000206',
     '9F2C0000000000000000000000000207', '9F2C0000000000000000000000000208',
     '9F2C0000000000000000000000000209', '9F2C000000000000000000000000020A',
     '9F2C000000000000000000000000020B', '9F2C000000000000000000000000020C',
     '9F2C0000000000000000000000000211', '9F2C0000000000000000000000000212',
     '9F2C0000000000000000000000000213', '9F2C0000000000000000000000000214',
     '9F2C0000000000000000000000000215', '9F2C0000000000000000000000000216',
     '9F2C0000000000000000000000000217', '9F2C0000000000000000000000000218',
     '9F2C0000000000000000000000000219', '9F2C000000000000000000000000021A',
     '9F2C000000000000000000000000021B', '9F2C000000000000000000000000021C',
     '9F2C0000000000000000000000000221', '9F2C0000000000000000000000000222',
     '9F2C0000000000000000000000000223', '9F2C0000000000000000000000000224',
     '9F2C0000000000000000000000000225', '9F2C0000000000000000000000000226',
     '9F2C0000000000000000000000000227', '9F2C0000000000000000000000000228',
     '9F2C0000000000000000000000000229', '9F2C000000000000000000000000022A',
     '9F2C000000000000000000000000022B', '9F2C000000000000000000000000022C',
     '9F2C0000000000000000000000000231', '9F2C0000000000000000000000000232',
     '9F2C0000000000000000000000000233', '9F2C0000000000000000000000000234',
     '9F2C0000000000000000000000000235', '9F2C0000000000000000000000000236',
     '9F2C0000000000000000000000000237', '9F2C0000000000000000000000000238',
     '9F2C0000000000000000000000000239', '9F2C000000000000000000000000023A',
     '9F2C000000000000000000000000023B', '9F2C000000000000000000000000023C',
     '9F2C0000000000000000000000000241', '9F2C0000000000000000000000000242',
     '9F2C0000000000000000000000000243', '9F2C0000000000000000000000000244',
     '9F2C0000000000000000000000000245', '9F2C0000000000000000000000000246',
     '9F2C0000000000000000000000000247', '9F2C0000000000000000000000000248',
     '9F2C0000000000000000000000000249', '9F2C000000000000000000000000024A',
     '9F2C000000000000000000000000024B',
     '9F2C0000000000000000000000000251', '9F2C0000000000000000000000000252',
     '9F2C0000000000000000000000000253', '9F2C0000000000000000000000000254',
     '9F2C0000000000000000000000000255', '9F2C0000000000000000000000000256',
     '9F2C0000000000000000000000000257', '9F2C0000000000000000000000000258',
     '9F2C0000000000000000000000000259', '9F2C000000000000000000000000025A',
     '9F2C000000000000000000000000025B',
     '9F2C0000000000000000000000000261', '9F2C0000000000000000000000000262',
     '9F2C0000000000000000000000000263', '9F2C0000000000000000000000000264',
     '9F2C0000000000000000000000000265', '9F2C0000000000000000000000000266',
     '9F2C0000000000000000000000000267', '9F2C0000000000000000000000000268',
     '9F2C0000000000000000000000000269', '9F2C000000000000000000000000026A',
     '9F2C000000000000000000000000026B',
     '9F2C0000000000000000000000000271', '9F2C0000000000000000000000000272',
     '9F2C0000000000000000000000000273', '9F2C0000000000000000000000000274',
     '9F2C0000000000000000000000000275', '9F2C0000000000000000000000000276',
     '9F2C0000000000000000000000000277', '9F2C0000000000000000000000000278',
     '9F2C0000000000000000000000000279', '9F2C000000000000000000000000027A',
     '9F2C000000000000000000000000027B',
     '9F2C0000000000000000000000000281', '9F2C0000000000000000000000000282',
     '9F2C0000000000000000000000000283',
     '9F2C0000000000000000000000000291', '9F2C0000000000000000000000000292'
 )
UNION ALL
-- §8.1-B 新建权限点个数：8 类单据 × 12（list + 11 个动作，其中 `:status` 8 个）
--    + 4 个来源单据 push + stk:stock 4（list/query/export/recalc）+ stk:ledger 3 = 107。
--    复用 B3 的 ctms:partner:* 5 个不计入本项（它们不在 pur/sal/stk 域内）。
SELECT '§8.1-B B4 新建权限点个数（应为 107 = 96 单据 + 4 push + 4 stk:stock + 3 stk:ledger）', COUNT(DISTINCT perms)
  FROM sys_menu
 WHERE perms REGEXP '^(pur|sal|stk):';

-- 收尾提示：本文件末尾的 SELECT 会列出 16 项检查（①~⑯）。①~⑭ 必须全部等于期望值；
--   本批新增的两条用无歧义前缀，见 `§8.1-A`（菜单段 121 行）与 `§8.1-B`（新建权限点 107 个）。
-- （B4 交付前 ⑨ 为 0 表示"尚未落地"；B4 交付后必须为 18。§8.1-A/B 由 B4 菜单脚本负责，
--   因此它们只在 source 二开-进销存-菜单.sql 已执行时非 0 —— 见上面 B4 段。）
-- 人工步骤：非 superAdmin 角色需到「系统管理 → 角色管理」勾选合同管理菜单与按钮
--   （B4 库存域的菜单与权限点已由 B4 段的 source 二开-进销存-菜单.sql 建好；
--     脚本只建权限点、不授权，勾选仍需人工完成）。
