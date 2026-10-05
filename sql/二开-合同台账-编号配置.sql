-- ============================================================================
-- 合同编号配置（2.0 B3 / oa-contract-ledger）
-- 变更集任务：§1.3 落地的编号方案在此登记配置；§2.6 编排文件引用本文件；§5.8 使用它取号
-- 需求：REQ-CTMS-004｜验收：AC-68、AC-71
-- ----------------------------------------------------------------------------
-- 依据（**先读这两份再改这个文件**）：
--   * openspec/changes/oa-contract-ledger/notes/numbering-verification.md
--     —— §1.2 的真实环境实测结论：**"纯配置表达"4 条判定全部不通过**
--        （日期规则恒取当天；重置改的是 DB 而真正的计数器在 Redis；序号只按 confId 分桶；没有预览端点），
--        因此对 ruoyi-serial 做了最小扩展 —— 新增「业务参数」规则类型（rule_type='3'）+
--        调用方可传参考日期 + 按「业务参数 + 年份」分桶（新年天然从 0 起 = 惰性跨年重置）+ 预览不占号。
--   * 目标格式：**3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号**，例 `PURZC202506000001`。
--
-- 关键设计：**一条配置服务全部类型码 × 主体码组合**
--   * 类型码（PUR/SAL/COO/LAB/FIN/NDA）与主体码（ZC/YX）都是**业务参数**（rule_type='3'，
--     rule_value = 参数名），由调用方按合同实际取值传入（`CodeGenContext.params`）；
--   * 因此 12 种组合**不需要 12 条配置**，且各自独立计数（计数器键含参数，见下）；
--   * 年份/月份码取**签订日期**（调用方传 `referenceDate`），不是服务器当天；
--   * 序号按 `(类型码, 主体码, 年份)` 分桶、按年重置：新桶/新年的第一个号必然是 `000001`，
--     **不依赖任何定时任务**（这与既有实现"必须 1 月 1 日当天跑到 CodeRestTask"完全不同）。
--
-- 调用方式（Java，服务端）：
--   codeGenService.getNextCode(confId, new CodeGenContext(signDate, params))   // 取号
--   codeGenService.previewNextCode(confId, params)                             // 预览不占号
--   params = {"typeCode":"PUR","subjectCode":"ZC"}
--   ⚠ 类型码必须来自 `contract_types` 且 `status='0'`（OTH 停用=不参与编号）；
--     主体码必须来自 `subjects`。取值合法性由合同服务校验（§5.8），编号服务只负责拼装。
--
-- 幂等：先按固定 id 删规则再删配置、最后插入；可反复执行。
-- ⚠ 本文件**不会**重置已有计数器：Redis 键 `code:gen:seq:<confId>:…` 不会被这里清掉。
--   如果确实要"从 000001 重新开始"，删配置后还要删对应的 Redis 键（生产环境请勿随意做）。
-- ============================================================================

SET @conf_id = '9F2C0000000000000000000000C001';

DELETE FROM t_code_config_rule WHERE config_id = @conf_id;
DELETE FROM t_code_config WHERE id = @conf_id;

INSERT INTO t_code_config (id, title, current_seq, enable_flag, del_flag, create_time, remark)
VALUES (@conf_id, '合同编号配置', 0, '1', '0', sysdate(),
        '类型码(业务参数)+主体码(业务参数)+yyyy+MM+6位序号(按年重置)；一条配置服务全部组合');

INSERT INTO t_code_config_rule
(id, config_id, rule_type, rule_value, pad_zero, seq_reset_type, sort, del_flag, create_time)
VALUES
-- 1) 类型码：业务参数 typeCode（例 PUR/SAL/COO/LAB/FIN/NDA）
('9F2C0000000000000000000000C101', @conf_id, '3', 'typeCode',    '0', '0', 1, '0', sysdate()),
-- 2) 主体码：业务参数 subjectCode（例 ZC/YX）
('9F2C0000000000000000000000C102', @conf_id, '3', 'subjectCode', '0', '0', 2, '0', sysdate()),
-- 3) 年份：按**签订日期**（调用方传 referenceDate）；不传则退化为服务器当天（既有行为）
('9F2C0000000000000000000000C103', @conf_id, '1', 'yyyy',        '0', '0', 3, '0', sysdate()),
-- 4) 月份：同上
('9F2C0000000000000000000000C104', @conf_id, '1', 'MM',          '0', '0', 4, '0', sysdate()),
-- 5) 序号：6 位补零、**按年重置**（重置类型 4 = 按年；年份会进入计数器键 → 惰性跨年重置）
('9F2C0000000000000000000000C105', @conf_id, '2', '6',           '1', '4', 5, '0', sysdate());

-- ---------------------------------------------------------------------------
-- 自检
-- ---------------------------------------------------------------------------
SELECT '① 合同编号配置存在（应为1）' AS `检查项`, COUNT(*) AS `结果`
  FROM t_code_config WHERE id = @conf_id AND title = '合同编号配置' AND enable_flag = '1'
UNION ALL
SELECT '② 规则 5 条（应为5）', COUNT(*)
  FROM t_code_config_rule WHERE config_id = @conf_id AND del_flag = '0'
UNION ALL
SELECT '③ 2 条业务参数规则（应为2）', COUNT(*)
  FROM t_code_config_rule WHERE config_id = @conf_id AND rule_type = '3'
UNION ALL
SELECT '④ 流水号按年重置且 6 位补零（应为1）', COUNT(*)
  FROM t_code_config_rule
 WHERE config_id = @conf_id AND rule_type = '2' AND seq_reset_type = '4' AND pad_zero = '1' AND rule_value = '6'
UNION ALL
SELECT '⑤ 规则顺序为 业务参数/业务参数/年/月/序号（应为1）', COUNT(*)
  FROM (SELECT GROUP_CONCAT(rule_type ORDER BY sort) AS seq FROM t_code_config_rule
         WHERE config_id = @conf_id AND del_flag = '0') t
 WHERE t.seq = '3,3,1,1,2';

-- 人工验证（后端已登录时，用真实环境脚本最省事）：
--   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\serial-numbering-check.ps1
-- 该脚本会用**自己的成套夹具**验证上面这套规则的 4 条判定标准（不依赖本文件，也不改动本文件的数据）。
