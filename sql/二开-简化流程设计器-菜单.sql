-- ============================================================================
-- 简化流程设计器（二开）—— 菜单与权限
-- 挂在「工作流管理」下；只新增菜单，不改动既有菜单
-- ============================================================================

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`) VALUES
('9F2C00000000000000000000000000A1','流程设计（简化版）','106AAA1FE985D47FDB080A11C05B9001',7,'simple-flow','workflow/simple-flow/index',NULL,'SimpleFlowList',1,0,'C','0','0','workflow:simpleFlow:list','#','superAdmin',sysdate(),'superAdmin',sysdate(),'节点清单式流程设计器，不暴露 BPMN');

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`update_by`,`update_time`,`remark`) VALUES
('9F2C00000000000000000000000000A2','流程设计查询','9F2C00000000000000000000000000A1',1,'#',NULL,NULL,'',1,0,'F','0','0','workflow:simpleFlow:query','#','superAdmin',sysdate(),'superAdmin',sysdate(),''),
('9F2C00000000000000000000000000A3','流程设计编辑','9F2C00000000000000000000000000A1',2,'#',NULL,NULL,'',1,0,'F','0','0','workflow:simpleFlow:edit','#','superAdmin',sysdate(),'superAdmin',sysdate(),''),
('9F2C00000000000000000000000000A4','流程设计发布','9F2C00000000000000000000000000A1',3,'#',NULL,NULL,'',1,0,'F','0','0','workflow:simpleFlow:publish','#','superAdmin',sysdate(),'superAdmin',sysdate(),''),
('9F2C00000000000000000000000000A5','流程设计删除','9F2C00000000000000000000000000A1',4,'#',NULL,NULL,'',1,0,'F','0','0','workflow:simpleFlow:remove','#','superAdmin',sysdate(),'superAdmin',sysdate(),'');
