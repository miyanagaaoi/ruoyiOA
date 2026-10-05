-- ============================================================================
-- 合同台账字典与系统参数（2.0 B3 / oa-contract-ledger）
-- 变更集任务：§2.8｜需求：REQ-CTMS-003、REQ-CTMS-005｜验收：AC-68、AC-69
-- ----------------------------------------------------------------------------
-- 口径与依据：
--   * 参考实现在 SQLite 里用 KV 表（`kv_settings`）存这些"系统级可配项"；
--     目标侧**不建表**，改用 OA 既有的 `sys_dict_type`/`sys_dict_data`（字典）与
--     `sys_config`（参数）——见 design.md D-9 与 notes/ddl-scope.md §5（不建表的 8 个对象之一）。
--   * 取值**逐条取自参考仓库 `app/dicts.py` 与 `app/models.py`**（不是自己编的）：
--       - DEFAULT_CONTRACT_TYPES（dicts.py:23-31）：6 个启用 + OTH（历史，enabled=false）
--       - DEFAULT_SUBJECTS（dicts.py:33-36）：ZC 智澈公司 / YX 云羲公司
--       - DEFAULT_ITEM_TYPES（models.py:59）：采购/销售/服务/其他
--       - STATUSES（models.py:44）：内部审批中/集团审批中/已签订/付款中/发货/到货/已终止
--       - ARRIVAL_STATUSES（models.py:48）：未到货/部分到货/已到货
--       - 质保提醒窗口默认 30 天（models.py:65 RESTORE_DAYS 同源口径；dashboard.py:194 读参数）
--   * 字段映射（design D-9 的裁定）：
--       类型码/取值 → `dict_value`；展示名 → `dict_label`；备注/说明 → `remark`；
--       **启用状态 → `status`（'0' 正常 / '1' 停用）**。
--       ⚠ "是否参与自动编号"就落在 `status` 上：`status='1'` 的类型不参与编号
--         （参考侧 `OTH` 的 enabled=false 即此语义）——合同保存时按启用类型校验。
--   * 幂等：先按 `dict_type` / `dict_code` / `config_key` 删除本批行再插入；
--     连续执行两次结果一致（`sys_dict_type.dict_type` 本身有唯一约束，重复插入会报错，
--     所以绝不能只写 INSERT）。
--   * ⚠ **不建议在生产库直接乱改这些行**：字典改错了会让"合同类型/行项类型"对不上历史数据。
--     调整请走「系统管理 → 字典管理」，本文件只负责**初始种子**。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 字典类型（5 个）
-- ---------------------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type IN
    ('item_types', 'contract_types', 'subjects', 'contract_statuses', 'arrival_statuses');
DELETE FROM sys_dict_type WHERE dict_type IN
    ('item_types', 'contract_types', 'subjects', 'contract_statuses', 'arrival_statuses');

INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, status, create_by, create_time, remark) VALUES
('9F2C0000000000000000000000D100', '行项类型',   'item_types',        '0', 'admin', sysdate(),
 '合同行项的 item_type 取值（参考侧 DEFAULT_ITEM_TYPES）'),
('9F2C0000000000000000000000D101', '合同类型',   'contract_types',    '0', 'admin', sysdate(),
 'dict_value=类型码（参与编号）、dict_label=类型名、status=1 表示不参与自动编号（OTH）'),
('9F2C0000000000000000000000D102', '我方主体',   'subjects',          '0', 'admin', sysdate(),
 'dict_value=主体码（参与编号）、dict_label=主体名称'),
('9F2C0000000000000000000000D103', '合同进度状态', 'contract_statuses', '0', 'admin', sysdate(),
 '合同进度状态（参考侧 STATUSES；允许任意互切，不做顺序守卫）'),
('9F2C0000000000000000000000D104', '到货状态',   'arrival_statuses',  '0', 'admin', sysdate(),
 '合同到货状态（参考侧 ARRIVAL_STATUSES；与进度状态无联动）');

-- ---------------------------------------------------------------------------
-- 2) 字典数据
--    ⚠ `contract_types` 的 OTH 用 status='1' 表达"不参与自动编号"（历史数据专用）
-- ---------------------------------------------------------------------------
INSERT INTO sys_dict_data
(dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
-- 行项类型：值就是中文（参考侧直接存中文，历史数据也是中文）
('9F2C0000000000000000000000E001', 1, '采购', '采购', 'item_types', NULL, 'default', 'Y', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E002', 2, '销售', '销售', 'item_types', NULL, 'default', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E003', 3, '服务', '服务', 'item_types', NULL, 'default', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E004', 4, '其他', '其他', 'item_types', NULL, 'default', 'N', '0', 'admin', sysdate(), NULL),

-- 合同类型：dict_value = 类型码（3 位），dict_label = 类型名
('9F2C0000000000000000000000E011', 1, '销售/收入',      'SAL', 'contract_types', NULL, 'success', 'N', '0', 'admin', sysdate(), '面向客户收款；编号类型码 SAL'),
('9F2C0000000000000000000000E012', 2, '采购/支出',      'PUR', 'contract_types', NULL, 'primary', 'N', '0', 'admin', sysdate(), '面向供应商付款；编号类型码 PUR'),
('9F2C0000000000000000000000E013', 3, '合作/战略协议',  'COO', 'contract_types', NULL, 'default', 'N', '0', 'admin', sysdate(), '不涉及直接金钱往来的框架协议'),
('9F2C0000000000000000000000E014', 4, '劳动/人事',      'LAB', 'contract_types', NULL, 'default', 'N', '0', 'admin', sysdate(), '员工劳动合同'),
('9F2C0000000000000000000000E015', 5, '金融/投融资',    'FIN', 'contract_types', NULL, 'default', 'N', '0', 'admin', sysdate(), '贷款、股权等'),
('9F2C0000000000000000000000E016', 6, '保密协议',        'NDA', 'contract_types', NULL, 'default', 'N', '0', 'admin', sysdate(), '单签或互签保密文件'),
('9F2C0000000000000000000000E017', 7, '其他(历史)',      'OTH', 'contract_types', NULL, 'info',    'N', '1', 'admin', sysdate(),
 '旧版"其他"历史数据；**不参与自动编号**（status=1）'),

-- 我方主体：dict_value = 主体码（2 位）
('9F2C0000000000000000000000E021', 1, '智澈公司', 'ZC', 'subjects', NULL, 'default', 'Y', '0', 'admin', sysdate(), '编号主体码 ZC'),
('9F2C0000000000000000000000E022', 2, '云羲公司', 'YX', 'subjects', NULL, 'default', 'N', '0', 'admin', sysdate(), '编号主体码 YX'),

-- 合同进度状态：值就是中文（参考侧 Contract.status 存中文）
('9F2C0000000000000000000000E031', 1, '内部审批中', '内部审批中', 'contract_statuses', NULL, 'info',    'Y', '0', 'admin', sysdate(), '默认值（DEFAULT_STATUS）'),
('9F2C0000000000000000000000E032', 2, '集团审批中', '集团审批中', 'contract_statuses', NULL, 'info',    'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E033', 3, '已签订',     '已签订',     'contract_statuses', NULL, 'primary', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E034', 4, '付款中',     '付款中',     'contract_statuses', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E035', 5, '发货',       '发货',       'contract_statuses', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E036', 6, '到货',       '到货',       'contract_statuses', NULL, 'success', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E037', 7, '已终止',     '已终止',     'contract_statuses', NULL, 'danger',  'N', '0', 'admin', sysdate(), '改为该状态时"终止原因"必填'),

-- 到货状态
('9F2C0000000000000000000000E041', 1, '未到货',   '未到货',   'arrival_statuses', NULL, 'info',    'Y', '0', 'admin', sysdate(), '默认值（DEFAULT_ARRIVAL）'),
('9F2C0000000000000000000000E042', 2, '部分到货', '部分到货', 'arrival_statuses', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E043', 3, '已到货',   '已到货',   'arrival_statuses', NULL, 'success', 'N', '0', 'admin', sysdate(), NULL);

-- ---------------------------------------------------------------------------
-- 3) 系统参数：质保到期提醒窗口（天）
--    config_type='Y' 表示系统内置（界面上不可删、可改值）
-- ---------------------------------------------------------------------------
DELETE FROM sys_config WHERE config_key = 'warranty_window_days';
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_by, create_time, remark)
VALUES ('9F2C0000000000000000000000F001', '质保到期提醒窗口（天）', 'warranty_window_days', '30', 'Y', 'admin', sysdate(),
        '参考侧默认 30 天（dashboard.py 的 expiring 用它算提醒区间）；改小=更早提醒');

-- ---------------------------------------------------------------------------
-- 编排自检（重复执行两次，本组结果必须一致）
-- ---------------------------------------------------------------------------
SELECT '① 字典类型个数（应为 5）' AS `检查项`, COUNT(*) AS `结果`
  FROM sys_dict_type WHERE dict_type IN
    ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')
UNION ALL
SELECT '② 字典数据条数（应为 4+7+2+7+3=23）', COUNT(*)
  FROM sys_dict_data WHERE dict_type IN
    ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')
UNION ALL
SELECT '③ 参与编号的合同类型（status=0，应为 6）', COUNT(*)
  FROM sys_dict_data WHERE dict_type='contract_types' AND status='0'
UNION ALL
SELECT '④ OTH 不参与编号（status=1，应为 1）', COUNT(*)
  FROM sys_dict_data WHERE dict_type='contract_types' AND dict_value='OTH' AND status='1'
UNION ALL
SELECT '⑤ 主体码（应为 2）', COUNT(*)
  FROM sys_dict_data WHERE dict_type='subjects'
UNION ALL
SELECT '⑥ 质保窗口参数（应为 1 且值为 30）', COUNT(*)
  FROM sys_config WHERE config_key='warranty_window_days' AND config_value='30';
