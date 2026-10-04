-- ============================================================================
-- 打印模板配置菜单（二开，PRD 7.7）
-- ----------------------------------------------------------------------------
-- 说明：
--   * 只新增菜单/按钮，不动任何既有行；可安全地在业务库 rad_oa 执行；
--   * 挂在「流程管理」下（与「流程设计（简化版）」同级），因为打印模板是
--     单据模板的配套配置，入口放在流程管理里最好找；
--   * 三个权限点：
--       workflow:print:template          查看配置页
--       workflow:print:template:edit     新增/修改（后端 @PreAuthorize 同名）
--       workflow:print:template:remove   删除
--     只给 superAdmin 之外的角色授权时，记得到「系统管理 → 角色管理」把这几个勾上。
-- ============================================================================

DELETE FROM sys_menu WHERE menu_id IN (
    '9F2C00000000000000000000000000B1',
    '9F2C00000000000000000000000000B2',
    '9F2C00000000000000000000000000B3'
);

INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
 is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
('9F2C00000000000000000000000000B1', '打印模板配置', '106AAA1FE985D47FDB080A11C05B9001', 8,
 'print-template', 'workflow/print-template/index', NULL, NULL,
 1, 0, 'C', '0', '0', 'workflow:print:template', 'print', 'admin', sysdate(),
 '合同审批二开：A4 打印件的纸张/标题/各区开关/字段映射'),

('9F2C00000000000000000000000000B2', '打印模板保存', '9F2C00000000000000000000000000B1', 1,
 '', NULL, NULL, NULL,
 1, 0, 'F', '0', '0', 'workflow:print:template:edit', '#', 'admin', sysdate(), ''),

('9F2C00000000000000000000000000B3', '打印模板删除', '9F2C00000000000000000000000000B1', 2,
 '', NULL, NULL, NULL,
 1, 0, 'F', '0', '0', 'workflow:print:template:remove', '#', 'admin', sysdate(), '');
