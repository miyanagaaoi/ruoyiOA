-- ============================================================================
-- 签名记录防篡改（二开）—— 数据库触发器
-- 对应文档：doc/PRD-合同审批二开.md §8.5（REQ-SIGN-004）、验收项 AC-32
-- 说明：
--   * 只新增触发器，**不改动表结构与数据**；
--   * 语义：签名记录**只追加**。撤签 = 追加一条 sign_type=9 的撤销记录（PRD 8.5），
--     因此 UPDATE / DELETE 在本表上没有任何合法用途，直接在数据库层拒绝；
--   * 为什么不能只靠"不提供 update/delete 的 Mapper"：
--     应用层约定拦不住直连数据库、运维脚本、其它系统的误操作，
--     AC-32 明确要求「数据库层面拒绝」（触发器验证）。
--   * 触发器体用**单语句 SIGNAL**（不写 BEGIN...END）——
--     这样在 mysql CLI / `mysql -e` 里执行不需要切换 DELIMITER。
--
-- ⚠️ 运维须知：本触发器会让「清理测试数据」变得不可能（那正是它的目的）。
--    确需清理时，先删触发器再删数据，删完重新执行本脚本：
--      DROP TRIGGER IF EXISTS trg_sign_record_no_update;
--      DROP TRIGGER IF EXISTS trg_sign_record_no_delete;
-- ============================================================================

-- 禁止修改
DROP TRIGGER IF EXISTS `trg_sign_record_no_update`;
CREATE TRIGGER `trg_sign_record_no_update`
BEFORE UPDATE ON `t_workflow_sign_record`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '签名记录只追加，禁止修改（PRD AC-32）';

-- 禁止删除
DROP TRIGGER IF EXISTS `trg_sign_record_no_delete`;
CREATE TRIGGER `trg_sign_record_no_delete`
BEFORE DELETE ON `t_workflow_sign_record`
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '签名记录只追加，禁止删除（PRD AC-32）';
