-- ============================================================================
-- OA 2.0 · B4 进销存（`oa-purchase-sales-stock`）· 18 张业务表 + 字典/参数 + 编排自检
-- ----------------------------------------------------------------------------
-- 变更集与任务号：
--   changes = openspec/changes/oa-purchase-sales-stock
--   tasks   = tasks.md §1.1（状态机冻结）、§1.2（18 张表 DDL）、§1.3（字典与参数）
--   design  = design.md D1（泛型公共层 + 每表独立实体）、D2（显式事务 + 行锁）、
--             D3（结存唯一约束 + 重复键重试）、D4（单级状态机）、D7（下推累计字段）、
--             D8（调拨一单两条流水）、D9（定点小数 + 先舍入再汇总）、D10（数据范围显式映射）
--   边界冻结 = openspec/changes/oa-contract-ledger/notes/ddl-scope.md §2/§4（B4 = 18 张）
--   字段级真源 = doc/2.0/参考仓库-CTMS-移植清单.md §2.3（DocMixin / DocItemMixin /
--             采购域 / 销售域 / 库存域字段级清单）、§2.3.6/§2.3.7（stocks / stock_ledger）
--   表名冻结 = openspec/changes/oa-purchase-sales-stock/notes/00-team-brief.md §1.1
--             （8 个表头名已写死在 `初始化-全部.sql` 的自检段 ⑥，不得改名）
--
-- 需求与验收：
--   REQ-PUR-001 / REQ-SAL-001 / REQ-STK-001~006 / REQ-NFR-007（精度）/ REQ-NFR-008（并发）
--   AC-72（下推）/ AC-73（状态底线）/ AC-74（并发不丢更新）/ AC-75（调拨盘点）/
--   AC-76（存储前提 + 一致性）/ AC-78（精度四方一致）
--   PRD §7.2 的 F-1（行锁 + 事务）、F-2（唯一索引 + 重复键重试）、F-3（应用层强制字段改回
--   NOT NULL + 补 FK）、F-5（流水两个索引）。
--
-- ----------------------------------------------------------------------------
-- 【本文件为什么把"建表 + 字典参数"放在一起】
--   ddl-scope.md §6 的编排契约里 ⑥ 段是**一个** B4 增量文件（`source 二开-进销存.sql`），
--   所以本文件同时承载 18 张表、4 个字典类型、1 个系统参数与末尾自检；
--   段内用 `##` 分隔（① 建表 / ② 字典参数 / ③ 自检），语义不混。
--
-- 【执行顺序】（唯一真源；必须在 `sql/` 目录内执行，见 ddl-scope.md §6）
--   ① table.sql（上游基线）→ ② data.sql（上游基线数据）→ ③ V1 二开 8 个文件
--   → ④ B1 增量 → ⑤ B2 增量 → ⑥ B3 四件套 → ⑦ 本文件 → ⑧ 编排自检
--   ⚠ 本文件的 FK 引用 `sys_user(user_id)` / `sys_dept(dept_id)`（8 张表头各 2 条）与
--     `t_ctms_supplier` / `t_ctms_customer` / `t_ctms_product` / `t_ctms_warehouse`（B3 交付），
--     因此 **`data.sql` 与 B3 的建表脚本必须先于本文件执行**，否则报 1452/1146。
--     这是**预期的阻断**，不允许用 `SET FOREIGN_KEY_CHECKS=0` 掩盖。
--
-- 【幂等约定】
--   ① 建表段：`DROP TABLE IF EXISTS`（子表 → 结存/流水 → 表头）+ `CREATE TABLE`，
--      连续执行两次结果一致；所有外键/唯一键**内联在建表语句里**（不是 B3 的
--      "约束回补三段"那种写法）——理由：B4 的 18 张表是**纯新增**，不存在"先有数据后加约束"
--      的迁移场景，内联即可保证"建出来就带约束"，也少一类"约束名冲突"的失败面。
--   ② 字典/参数段：按 `dict_type` / `dict_code` / `config_key` 先 DELETE 再 INSERT。
--   ③ 末尾自检 SELECT 的 `结果` 列必须等于「检查项」括号里的期望值。
--
-- 【不改上游】`sql/table.sql` 与 `sql/data.sql` 是上游原样基线，本变更集**不修改**
--   （REQ-DATA-005 方案 B / ddl-scope.md §6 约定 1，验证方式是 `git diff` 为空）。
--   ⚠ OpenSpec `tasks.md` §1.2 的验证方式里有一句"把同一批对象的定义同步补齐到
--     `sql/table.sql`"，与本冻结件冲突 —— **以冻结件为准**（tasks.md §1.2 的这句是
--     B3 §2.6 之前的旧口径）；理由与后果见 notes/01-base.md §1.4。
--
-- 【排序规则事实】被引用列实测为 `utf8mb4_0900_ai_ci`（`sys_user.user_id` /
--   `sys_dept.dept_id` / B3 的 `t_ctms_*`），故 18 张表统一
--   `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`（列继承表级 collation），
--   使 FK 参与列与被引用列同 collation（B1 文件头实测 ERROR 1267 的根因）。
--
-- 用法（中文文件名不能当命令行参数，走 stdin）：
--   Get-Content -Raw -Encoding UTF8 .\二开-进销存.sql |
--     mysql --host=127.0.0.1 --user=root --database=rad_oa --default-character-set=utf8mb4 --table
--   （或 `powershell -File .\tools\run-db-sql.ps1 -Database rad_oa -Files "二开-进销存.sql"`）
-- ============================================================================


-- ############################################################################
-- ① 建表段：18 张业务表（8 表头 + 8 行项 + 结存 + 流水）
-- ############################################################################
-- DROP 顺序 = 子表在前 + 建表顺序逆序：
--   ① 8 张行项表（引用各自表头 + 引用 t_ctms_product）；
--   ② 结存 / 流水（引用 t_ctms_product / t_ctms_warehouse）；
--   ③ 8 张表头（被行项引用；自身引用 sys_user/sys_dept 与 B3 的档案表）。
--   ⚠ 绝不 DROP B3 的 13 张表（t_ctms_product* / t_ctms_uom / t_ctms_warehouse /
--     t_ctms_contract* / t_ctms_* 档案）——边界见 ddl-scope.md §4。

DROP TABLE IF EXISTS `t_ctms_purchase_request_item`;
DROP TABLE IF EXISTS `t_ctms_purchase_order_item`;
DROP TABLE IF EXISTS `t_ctms_sales_request_item`;
DROP TABLE IF EXISTS `t_ctms_sales_order_item`;
DROP TABLE IF EXISTS `t_ctms_stock_in_item`;
DROP TABLE IF EXISTS `t_ctms_stock_out_item`;
DROP TABLE IF EXISTS `t_ctms_stocktake_item`;
DROP TABLE IF EXISTS `t_ctms_transfer_item`;
DROP TABLE IF EXISTS `t_ctms_stock_ledger`;
DROP TABLE IF EXISTS `t_ctms_stock`;
DROP TABLE IF EXISTS `t_ctms_purchase_request`;
DROP TABLE IF EXISTS `t_ctms_purchase_order`;
DROP TABLE IF EXISTS `t_ctms_sales_request`;
DROP TABLE IF EXISTS `t_ctms_sales_order`;
DROP TABLE IF EXISTS `t_ctms_stock_in`;
DROP TABLE IF EXISTS `t_ctms_stock_out`;
DROP TABLE IF EXISTS `t_ctms_stocktake`;
DROP TABLE IF EXISTS `t_ctms_transfer`;


-- ---------- 1/18 ~ 8/18 单据表头（8 类） ----------
-- 公共列（= 参考侧 `DocMixin`，`models_doc.py:62-97`）在 8 张表里逐字重复（不用外键/继承），
-- 因为 MyBatis 侧是"每表独立实体"（design D1 的否决理由：单表多态会退化成大量稀疏可空列）。
--
-- 公共列的口径与来源：
--   doc_no/doc_date/status/posted/remark/来源三列/痕迹列 → `DocMixin` 原样；
--   dept_id + create_id = 参考侧 `org_id` + `created_by`（§2.8 #5「创建时快照当前用户」），
--     目标侧列名按 RuoYi 惯例改为 dept_id/create_id（清单 §6.3 的移植注意第 ②③ 条），
--     并按 F-3 收紧为 NOT NULL + FK（B3 对 t_ctms_contract.dept_id/create_id 同款处理）；
--   handler_user_id/handler_name → `DocMixin`（V2.1/N1 加列，`db_migrate.py:35-41`）；
--   del_flag → 参考侧 `deleted`（**刻意不加 deleted_at/deleted_reason**，与合同不一致）；
--   create_time/update_time/update_id/update_by → 目标侧全局审计块（与 B3 一致）。

-- 1/18 采购申请单（`purchase_requests`，`models_doc.py:123-138`；**无 total_amount 列**）
CREATE TABLE `t_ctms_purchase_request` (
  `id`                  varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`              varchar(64)   NOT NULL                     COMMENT '单号（唯一；作废/删除占号不复用）',
  `doc_date`            date          NOT NULL                     COMMENT '单据日期（参考侧默认 date.today，由应用层赋值）',
  `status`              varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态：draft/submitted/approved/completed/voided（逐动作白名单见 ErpDocStateMachine）',
  `dept_id`             varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时按创建人部门**快照**，之后不随调岗回溯；数据范围 DEPT 判定依据）',
  `create_id`           varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照；数据范围 SELF 判定依据）',
  `create_by`           varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`     varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（仅反查姓名：可空、仅索引、不加 FK）',
  `handler_name`        varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（未指定经办人时为空）',
  `remark`              varchar(1000) DEFAULT NULL                 COMMENT '备注（参考侧 Text → 勘察 §8.2 统一 varchar(1000)）',
  `contract_id`         varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅采购方向合同，引用 t_ctms_contract）',
  `contract_no`         varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照（关联时写入）',
  `source_doc_type`     varchar(24)   DEFAULT NULL                 COMMENT '下推来源单据类型（本表恒为空：申请单是链路源头）',
  `source_doc_id`       varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`       varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`        varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID（提交痕迹）',
  `submitted_at`        datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`         varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`         datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`           varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`           datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`         varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（作废必填由应用层校验）',
  `posted`              char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0-未过账 1-已过账（过账幂等依据；申请单无库存动作，恒为 0）',
  `del_flag`            char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除（列表默认排除）',
  `create_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`           varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`           varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 采购申请单特有列（`models_doc.py:127-129`）：
  `request_dept_id`     varchar(64)   DEFAULT NULL                 COMMENT '需求部门ID（可空：仅反查名称，§2.8 #17）',
  `need_date`           date          DEFAULT NULL                 COMMENT '需求日期（可空）',
  `suggest_supplier_id` varchar(64)   DEFAULT NULL                 COMMENT '建议供应商ID（可空、无 FK：§2.8 #17）',
  `purpose`             varchar(1000) DEFAULT NULL                 COMMENT '用途说明（可空）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_request_doc_no` (`doc_no`),
  KEY `idx_purchase_request_status` (`status`),
  KEY `idx_purchase_request_doc_date` (`doc_date`),
  KEY `idx_purchase_request_dept` (`dept_id`),
  KEY `idx_purchase_request_create_id` (`create_id`),
  KEY `idx_purchase_request_contract` (`contract_id`),
  KEY `idx_purchase_request_del_flag` (`del_flag`),
  CONSTRAINT `fk_purchase_request_dept`      FOREIGN KEY (`dept_id`)   REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_purchase_request_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购申请单（B4）';

-- 2/18 采购单（`purchase_orders`，`models_doc.py:156-177`；supplier_id 为 DB 级 NOT NULL + FK）
CREATE TABLE `t_ctms_purchase_order` (
  `id`                    varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`                varchar(64)   NOT NULL                     COMMENT '单号（唯一）',
  `doc_date`              date          NOT NULL                     COMMENT '单据日期',
  `status`                varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态（draft/submitted/approved/completed/voided）',
  `dept_id`               varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`             varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`             varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`       varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`          varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`                varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`           varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅采购方向，下推时带过）',
  `contract_no`           varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type`       varchar(24)   DEFAULT NULL                 COMMENT '下推来源类型（purchase_request）',
  `source_doc_id`         varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`         varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`          varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`          datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`           varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`           datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`             varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`             datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`           varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`                char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0/1（采购单无库存动作，恒为 0）',
  `del_flag`              char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`             varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`             varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 采购单特有列（`models_doc.py:160-176`）：
  `supplier_id`           varchar(64)   NOT NULL                     COMMENT '供应商档案ID（DB 级 NOT NULL + FK：禁止引用客户，§2.8 反例）',
  `supplier_name`         varchar(128)  NOT NULL DEFAULT ''          COMMENT '供应商名称快照（改名/停用后历史单据仍显示旧名）',
  `purchase_dept_id`      varchar(64)   DEFAULT NULL                 COMMENT '采购部门ID（可空，仅反查名称）',
  `expected_arrival_date` date          DEFAULT NULL                 COMMENT '预计到货日期（可空）',
  `settle_type`           varchar(24)   DEFAULT NULL                 COMMENT '结算方式（可空）',
  `currency`              varchar(8)    NOT NULL DEFAULT 'CNY'       COMMENT '币种（仅记录不换算）',
  `total_amount`          decimal(16,2) NOT NULL DEFAULT 0           COMMENT '单据金额合计 = 各行**先 HALF_UP 到 2 位**后之和（ErpAmounts.totalOf 是唯一实现点）',
  `receipt_warehouse_id`  varchar(64)   DEFAULT NULL                 COMMENT '默认收货仓库ID（可空、无 FK：§2.8 #17）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_order_doc_no` (`doc_no`),
  KEY `idx_purchase_order_status` (`status`),
  KEY `idx_purchase_order_doc_date` (`doc_date`),
  KEY `idx_purchase_order_dept` (`dept_id`),
  KEY `idx_purchase_order_create_id` (`create_id`),
  KEY `idx_purchase_order_contract` (`contract_id`),
  KEY `idx_purchase_order_del_flag` (`del_flag`),
  KEY `idx_purchase_order_supplier` (`supplier_id`),
  KEY `idx_purchase_order_source_doc` (`source_doc_type`,`source_doc_id`),
  CONSTRAINT `fk_purchase_order_dept`      FOREIGN KEY (`dept_id`)     REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_purchase_order_create_id` FOREIGN KEY (`create_id`)   REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_purchase_order_supplier`  FOREIGN KEY (`supplier_id`) REFERENCES `t_ctms_supplier` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购单（B4）';

-- 3/18 销售申请单（`sales_requests`，`models_doc.py:195-210`；**无 total_amount 列**；
--      客户是可空引用、与采购单刻意不对称）
CREATE TABLE `t_ctms_sales_request` (
  `id`                   varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`               varchar(64)   NOT NULL                     COMMENT '单号（唯一）',
  `doc_date`             date          NOT NULL                     COMMENT '单据日期',
  `status`               varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态（draft/submitted/approved/completed/voided）',
  `dept_id`              varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`            varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`            varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`      varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`         varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`               varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`          varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅销售方向）',
  `contract_no`          varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type`      varchar(24)   DEFAULT NULL                 COMMENT '下推来源类型（本表恒为空）',
  `source_doc_id`        varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`        varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`         varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`         datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`          varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`          datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`            varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`            datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`          varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`               char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0/1（申请单无库存动作，恒为 0）',
  `del_flag`             char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`            varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`            varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 销售申请单特有列（`models_doc.py:198-203`）：
  `customer_id`          varchar(64)   DEFAULT NULL                 COMMENT '客户档案ID（**可空、无 FK**：与采购单 asymmetry，§2.3.4）',
  `customer_name_text`   varchar(128)  DEFAULT NULL                 COMMENT '客户名称文本（未绑定档案时的兜底展示，可空）',
  `sales_dept_id`        varchar(64)   DEFAULT NULL                 COMMENT '销售部门ID（可空，仅反查名称）',
  `expect_delivery_date` date          DEFAULT NULL                 COMMENT '期望交货日期（可空）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_request_doc_no` (`doc_no`),
  KEY `idx_sales_request_status` (`status`),
  KEY `idx_sales_request_doc_date` (`doc_date`),
  KEY `idx_sales_request_dept` (`dept_id`),
  KEY `idx_sales_request_create_id` (`create_id`),
  KEY `idx_sales_request_contract` (`contract_id`),
  KEY `idx_sales_request_del_flag` (`del_flag`),
  KEY `idx_sales_request_customer` (`customer_id`),
  CONSTRAINT `fk_sales_request_dept`      FOREIGN KEY (`dept_id`)   REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_sales_request_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售申请单（B4）';

-- 4/18 销售订单（`sales_orders`，`models_doc.py:226-249`；customer_id 为 DB 级 NOT NULL + FK）
CREATE TABLE `t_ctms_sales_order` (
  `id`                varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`            varchar(64)   NOT NULL                     COMMENT '单号（唯一）',
  `doc_date`          date          NOT NULL                     COMMENT '单据日期',
  `status`            varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态（draft/submitted/approved/completed/voided）',
  `dept_id`           varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`         varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`         varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`   varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`      varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`            varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`       varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅销售方向）',
  `contract_no`       varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type`   varchar(24)   DEFAULT NULL                 COMMENT '下推来源类型（sales_request）',
  `source_doc_id`     varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`     varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`      varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`      datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`       varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`       datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`         varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`         datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`       varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`            char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0/1（销售订单无库存动作，恒为 0）',
  `del_flag`          char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`         varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`         varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 销售订单特有列（`models_doc.py:230-246`）：
  `customer_id`       varchar(64)   NOT NULL                     COMMENT '客户档案ID（DB 级 NOT NULL + FK：禁止引用供应商）',
  `customer_name`     varchar(128)  NOT NULL DEFAULT ''          COMMENT '客户名称快照',
  `sales_dept_id`     varchar(64)   DEFAULT NULL                 COMMENT '销售部门ID（可空）',
  `delivery_date`     date          DEFAULT NULL                 COMMENT '交货日期（可空）',
  `delivery_address`  varchar(255)  DEFAULT NULL                 COMMENT '收货地址（可空）',
  `contact_name`      varchar(64)   DEFAULT NULL                 COMMENT '联系人（可空）',
  `contact_phone`     varchar(32)   DEFAULT NULL                 COMMENT '联系电话（可空）',
  `ship_warehouse_id` varchar(64)   DEFAULT NULL                 COMMENT '默认发货仓库ID（可空、无 FK：§2.8 #17）',
  `currency`          varchar(8)    NOT NULL DEFAULT 'CNY'       COMMENT '币种（仅记录不换算）',
  `total_amount`      decimal(16,2) NOT NULL DEFAULT 0           COMMENT '单据金额合计 = 各行先 HALF_UP 到 2 位后之和',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_order_doc_no` (`doc_no`),
  KEY `idx_sales_order_status` (`status`),
  KEY `idx_sales_order_doc_date` (`doc_date`),
  KEY `idx_sales_order_dept` (`dept_id`),
  KEY `idx_sales_order_create_id` (`create_id`),
  KEY `idx_sales_order_contract` (`contract_id`),
  KEY `idx_sales_order_del_flag` (`del_flag`),
  KEY `idx_sales_order_customer` (`customer_id`),
  KEY `idx_sales_order_source_doc` (`source_doc_type`,`source_doc_id`),
  CONSTRAINT `fk_sales_order_dept`      FOREIGN KEY (`dept_id`)     REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_sales_order_create_id` FOREIGN KEY (`create_id`)   REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_sales_order_customer`  FOREIGN KEY (`customer_id`) REFERENCES `t_ctms_customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单（B4）';

-- 5/18 入库单（`stock_in_orders`，`models_doc.py:265-284`；direction=1）
CREATE TABLE `t_ctms_stock_in` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`         varchar(64)   NOT NULL                     COMMENT '单号（唯一）',
  `doc_date`       date          NOT NULL                     COMMENT '单据日期',
  `status`         varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态：**库存类单据 approved 即终态，不使用 completed**（design D4）',
  `dept_id`        varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`      varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id` varchar(64)  DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`   varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`    varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅采购方向）',
  `contract_no`    varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type` varchar(24)  DEFAULT NULL                 COMMENT '下推来源类型（purchase_order / stock_take）',
  `source_doc_id`  varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`  varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`   varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`   datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`    varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`    datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`      varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`      datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`    varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`         char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0-未过账 1-已过账（**过账幂等依据**：重复审核直接短路）',
  `del_flag`       char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 入库单特有列（`models_doc.py:268-283`）：
  `warehouse_id`   varchar(64)   NOT NULL                     COMMENT '表头仓库ID（DB 级 NOT NULL + FK；行项未填仓库时回落本列）',
  `warehouse_name` varchar(64)   NOT NULL DEFAULT ''          COMMENT '仓库名快照',
  `in_type`        varchar(24)   NOT NULL DEFAULT '采购入库'  COMMENT '入库类型（取值落 sys_dict_data.stock_in_types；盘盈入库仅由盘点审核生成）',
  `supplier_id`    varchar(64)   DEFAULT NULL                 COMMENT '供应商ID（可空、无 FK：§2.8 #17）',
  `supplier_name`  varchar(128)  DEFAULT NULL                 COMMENT '供应商名称快照（可空）',
  `total_amount`   decimal(16,2) NOT NULL DEFAULT 0           COMMENT '单据金额合计 = 各行先 HALF_UP 到 2 位后之和',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_in_doc_no` (`doc_no`),
  KEY `idx_stock_in_status` (`status`),
  KEY `idx_stock_in_doc_date` (`doc_date`),
  KEY `idx_stock_in_dept` (`dept_id`),
  KEY `idx_stock_in_create_id` (`create_id`),
  KEY `idx_stock_in_contract` (`contract_id`),
  KEY `idx_stock_in_del_flag` (`del_flag`),
  KEY `idx_stock_in_warehouse` (`warehouse_id`),
  KEY `idx_stock_in_source_doc` (`source_doc_type`,`source_doc_id`),
  CONSTRAINT `fk_stock_in_dept`       FOREIGN KEY (`dept_id`)      REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_stock_in_create_id`  FOREIGN KEY (`create_id`)    REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_stock_in_warehouse`  FOREIGN KEY (`warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单（B4，审核即过账）';

-- 6/18 出库单（`stock_out_orders`，`models_doc.py:299-318`；direction=-1）
CREATE TABLE `t_ctms_stock_out` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`         varchar(64)   NOT NULL                     COMMENT '单号（唯一）',
  `doc_date`       date          NOT NULL                     COMMENT '单据日期',
  `status`         varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态：**库存类单据 approved 即终态，不使用 completed**',
  `dept_id`        varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`      varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id` varchar(64)  DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`   varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`    varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；仅销售方向）',
  `contract_no`    varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type` varchar(24)  DEFAULT NULL                 COMMENT '下推来源类型（sales_order / stock_take）',
  `source_doc_id`  varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`  varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`   varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`   datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`    varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`    datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`      varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`      datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`    varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`         char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0-未过账 1-已过账（过账幂等依据）',
  `del_flag`       char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 出库单特有列（`models_doc.py:302-317`）：
  `warehouse_id`   varchar(64)   NOT NULL                     COMMENT '表头仓库ID（DB 级 NOT NULL + FK；行项未填仓库时回落本列）',
  `warehouse_name` varchar(64)   NOT NULL DEFAULT ''          COMMENT '仓库名快照',
  `out_type`       varchar(24)   NOT NULL DEFAULT '销售出库'  COMMENT '出库类型（取值落 sys_dict_data.stock_out_types；盘亏出库仅由盘点审核生成）',
  `customer_id`    varchar(64)   DEFAULT NULL                 COMMENT '客户ID（可空、无 FK：§2.8 #17）',
  `customer_name`  varchar(128)  DEFAULT NULL                 COMMENT '客户名称快照（可空）',
  `total_amount`   decimal(16,2) NOT NULL DEFAULT 0           COMMENT '单据金额合计 = 各行先 HALF_UP 到 2 位后之和',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_out_doc_no` (`doc_no`),
  KEY `idx_stock_out_status` (`status`),
  KEY `idx_stock_out_doc_date` (`doc_date`),
  KEY `idx_stock_out_dept` (`dept_id`),
  KEY `idx_stock_out_create_id` (`create_id`),
  KEY `idx_stock_out_contract` (`contract_id`),
  KEY `idx_stock_out_del_flag` (`del_flag`),
  KEY `idx_stock_out_warehouse` (`warehouse_id`),
  KEY `idx_stock_out_source_doc` (`source_doc_type`,`source_doc_id`),
  CONSTRAINT `fk_stock_out_dept`      FOREIGN KEY (`dept_id`)      REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_stock_out_create_id` FOREIGN KEY (`create_id`)    REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_stock_out_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单（B4，审核即过账）';

-- 7/18 盘点单（`stock_takes`，`models_doc.py:333-352`；**无 total_amount 列**）
CREATE TABLE `t_ctms_stocktake` (
  `id`                varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`            varchar(64)   NOT NULL                     COMMENT '单号（唯一，盘点单号）',
  `doc_date`          date          NOT NULL                     COMMENT '单据日期',
  `status`            varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态：库存类单据 approved 即终态；反审核 → submitted（级联红冲生成单）',
  `dept_id`           varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`         varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`         varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`   varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`      varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`            varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`       varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；盘点单通常为空）',
  `contract_no`       varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type`   varchar(24)   DEFAULT NULL                 COMMENT '下推来源类型（本表恒为空）',
  `source_doc_id`     varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`     varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`      varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`      datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`       varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID',
  `approved_at`       datetime      DEFAULT NULL                 COMMENT '审核时间',
  `voided_by`         varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`         datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`       varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`            char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0/1（盘点单自身不写结存，标记表示"生成单已过账"）',
  `del_flag`          char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`         varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`         varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 盘点单特有列（`models_doc.py:337-347`）：
  `warehouse_id`      varchar(64)   NOT NULL                     COMMENT '盘点仓库ID（DB 级 NOT NULL + FK）',
  `warehouse_name`    varchar(64)   NOT NULL DEFAULT ''          COMMENT '仓库名快照',
  `take_type`         varchar(16)   NOT NULL DEFAULT 'full'      COMMENT '盘点范围：full-全盘 partial-抽盘（取值落 sys_dict_data.stock_take_types）',
  `scope_note`        varchar(1000) DEFAULT NULL                 COMMENT '抽盘范围说明（可空）',
  `generated_in_id`   varchar(64)   DEFAULT NULL                 COMMENT '生成的盘盈入库单ID（可空、无 FK：§2.8 #17）',
  `generated_in_no`   varchar(64)   DEFAULT NULL                 COMMENT '生成的盘盈入库单号（与 id 成对写入）',
  `generated_out_id`  varchar(64)   DEFAULT NULL                 COMMENT '生成的盘亏出库单ID（可空）',
  `generated_out_no`  varchar(64)   DEFAULT NULL                 COMMENT '生成的盘亏出库单号（与 id 成对写入）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stocktake_doc_no` (`doc_no`),
  KEY `idx_stocktake_status` (`status`),
  KEY `idx_stocktake_doc_date` (`doc_date`),
  KEY `idx_stocktake_dept` (`dept_id`),
  KEY `idx_stocktake_create_id` (`create_id`),
  KEY `idx_stocktake_del_flag` (`del_flag`),
  KEY `idx_stocktake_warehouse` (`warehouse_id`),
  CONSTRAINT `fk_stocktake_dept`      FOREIGN KEY (`dept_id`)      REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_stocktake_create_id` FOREIGN KEY (`create_id`)    REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_stocktake_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单（B4，审核自动生成盘盈/盘亏单）';

-- 8/18 调拨单（`stock_transfers`，`models_doc.py:371-399`；V2.1/N13；direction=0）
CREATE TABLE `t_ctms_transfer` (
  `id`                   varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_no`               varchar(64)   NOT NULL                     COMMENT '单号（唯一；**一单两条流水共用本单号**）',
  `doc_date`             date          NOT NULL                     COMMENT '单据日期',
  `status`               varchar(16)   NOT NULL DEFAULT 'draft'     COMMENT '状态：库存类单据 approved 即终态，不使用 completed',
  `dept_id`              varchar(64)   NOT NULL                     COMMENT '归属部门ID（创建时快照）',
  `create_id`            varchar(64)   NOT NULL                     COMMENT '创建人用户ID（创建时快照）',
  `create_by`            varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `handler_user_id`      varchar(64)   DEFAULT NULL                 COMMENT '经办人用户ID（可空）',
  `handler_name`         varchar(64)   DEFAULT NULL                 COMMENT '经办人姓名快照（可空）',
  `remark`               varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `contract_id`          varchar(64)   DEFAULT NULL                 COMMENT '关联合同ID（可空；调拨不参与合同金额汇总）',
  `contract_no`          varchar(64)   DEFAULT NULL                 COMMENT '合同编号快照',
  `source_doc_type`      varchar(24)   DEFAULT NULL                 COMMENT '下推来源类型（本表恒为空）',
  `source_doc_id`        varchar(64)   DEFAULT NULL                 COMMENT '下推来源单据ID',
  `source_doc_no`        varchar(64)   DEFAULT NULL                 COMMENT '下推来源单号快照',
  `submitted_by`         varchar(64)   DEFAULT NULL                 COMMENT '提交人用户ID',
  `submitted_at`         datetime      DEFAULT NULL                 COMMENT '提交时间',
  `approved_by`          varchar(64)   DEFAULT NULL                 COMMENT '审核人用户ID（反审核时置空）',
  `approved_at`          datetime      DEFAULT NULL                 COMMENT '审核时间（反审核时置空）',
  `voided_by`            varchar(64)   DEFAULT NULL                 COMMENT '作废人用户ID',
  `voided_at`            datetime      DEFAULT NULL                 COMMENT '作废时间',
  `void_reason`          varchar(1000) DEFAULT NULL                 COMMENT '作废/驳回原因（必填由应用层校验）',
  `posted`               char(1)       NOT NULL DEFAULT '0'         COMMENT '过账标记：0-未过账 1-已过账（两阶段写入的幂等依据）',
  `del_flag`             char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已删除',
  `create_time`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_id`            varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`            varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  -- 调拨单特有列（`models_doc.py:375-398`）：
  `from_warehouse_id`    varchar(64)   NOT NULL                     COMMENT '调出仓库ID（DB 级 NOT NULL + FK；前端也拦截两仓相同）',
  `from_warehouse_name`  varchar(64)   NOT NULL DEFAULT ''          COMMENT '调出仓库名快照',
  `to_warehouse_id`      varchar(64)   NOT NULL                     COMMENT '调入仓库ID（DB 级 NOT NULL + FK）',
  `to_warehouse_name`    varchar(64)   NOT NULL DEFAULT ''          COMMENT '调入仓库名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_doc_no` (`doc_no`),
  KEY `idx_transfer_status` (`status`),
  KEY `idx_transfer_doc_date` (`doc_date`),
  KEY `idx_transfer_dept` (`dept_id`),
  KEY `idx_transfer_create_id` (`create_id`),
  KEY `idx_transfer_del_flag` (`del_flag`),
  KEY `idx_transfer_from_warehouse` (`from_warehouse_id`),
  KEY `idx_transfer_to_warehouse` (`to_warehouse_id`),
  CONSTRAINT `fk_transfer_dept`           FOREIGN KEY (`dept_id`)           REFERENCES `sys_dept` (`dept_id`),
  CONSTRAINT `fk_transfer_create_id`      FOREIGN KEY (`create_id`)         REFERENCES `sys_user` (`user_id`),
  CONSTRAINT `fk_transfer_from_warehouse` FOREIGN KEY (`from_warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`),
  CONSTRAINT `fk_transfer_to_warehouse`   FOREIGN KEY (`to_warehouse_id`)   REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单（B4，两阶段过账、一单两条流水）';


-- ---------- 9/18 ~ 16/18 单据行项（8 张，表头表名 + `_item`） ----------
-- 公共列（= 参考侧 `DocItemMixin`，`models_doc.py:100-118`）+ `doc_id`（各表内声明，§2.3.2 末段）。
-- 口径与来源：
--   product_id/product_code/product_name → **DB 级 NOT NULL**（§2.8 反例：行项物料是业务硬约束），
--     product_id 另加 FK → t_ctms_product（DB 层拒绝"未选物料"）；
--   spec/uom_name/uom_decimals → 物料快照列（**停用物料/改名后历史单据仍按快照展示**，AC-72）；
--   qty decimal(16,3) / unit_price decimal(14,4) / amount decimal(16,2) → §2.3.2 与 D9 的定点口径；
--   warehouse_id/warehouse_name → 行级仓库（回落表头；**调拨行项不使用**）；
--   src_item_id → 下推来源行项标识（无 FK）。
-- 所有行项表的 `doc_id` 外键一律 `ON DELETE CASCADE`（§2.3.2：行项随表头消亡）。

-- 9/18 采购申请行项（+ ordered_qty：已下推数量，申请→采购单时**即时累加**）
CREATE TABLE `t_ctms_purchase_request_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '采购申请单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号（按提交顺序从 1 连续重排）',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK：拒绝未选物料）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照（可空：物料档案规格为空即为空）',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照（0~4，数量录入精度校验依据）',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '数量（定点 3 位小数；精度按单位小数位校验）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位小数；负数拒绝由应用层校验）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额 = qty×unit_price 先 HALF_UP 到 2 位（ErpAmounts.lineAmount）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（可空；申请单不使用）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注（可空）',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空：行项随表头写入）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `ordered_qty`    decimal(16,3) NOT NULL DEFAULT 0           COMMENT '已下推数量（申请→采购单**即时累加**，剩余量 = qty - ordered_qty）',
  PRIMARY KEY (`id`),
  KEY `idx_purchase_request_item_doc` (`doc_id`),
  KEY `idx_purchase_request_item_product` (`product_id`),
  KEY `idx_purchase_request_item_seq` (`doc_id`,`seq`),
  KEY `idx_purchase_request_item_src` (`src_item_id`),
  CONSTRAINT `fk_purchase_request_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_purchase_request` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_purchase_request_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购申请单行项（B4）';

-- 10/18 采购单行项（+ received_qty：已入库数量，**过账时累加、红冲回退且不小于 0**）
CREATE TABLE `t_ctms_purchase_order_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '采购单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '数量（定点 3 位）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（先舍入到 2 位）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级收货仓库ID（可空；回落表头 receipt_warehouse_id）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（采购申请行项，可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `received_qty`   decimal(16,3) NOT NULL DEFAULT 0           COMMENT '已入库数量（订单→入库单**过账时累加**，红冲回退且不小于 0）',
  PRIMARY KEY (`id`),
  KEY `idx_purchase_order_item_doc` (`doc_id`),
  KEY `idx_purchase_order_item_product` (`product_id`),
  KEY `idx_purchase_order_item_seq` (`doc_id`,`seq`),
  KEY `idx_purchase_order_item_src` (`src_item_id`),
  CONSTRAINT `fk_purchase_order_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_purchase_order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_purchase_order_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购单行项（B4）';

-- 11/18 销售申请行项（+ ordered_qty）
CREATE TABLE `t_ctms_sales_request_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '销售申请单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '数量（定点 3 位）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（先舍入到 2 位）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（可空；申请单不使用）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `ordered_qty`    decimal(16,3) NOT NULL DEFAULT 0           COMMENT '已下推数量（申请→销售订单即时累加）',
  PRIMARY KEY (`id`),
  KEY `idx_sales_request_item_doc` (`doc_id`),
  KEY `idx_sales_request_item_product` (`product_id`),
  KEY `idx_sales_request_item_seq` (`doc_id`,`seq`),
  KEY `idx_sales_request_item_src` (`src_item_id`),
  CONSTRAINT `fk_sales_request_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_sales_request` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sales_request_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售申请单行项（B4）';

-- 12/18 销售订单行项（+ shipped_qty）
CREATE TABLE `t_ctms_sales_order_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '销售订单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '数量（定点 3 位）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（先舍入到 2 位）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级发货仓库ID（可空；回落表头 ship_warehouse_id）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（销售申请行项，可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `shipped_qty`    decimal(16,3) NOT NULL DEFAULT 0           COMMENT '已出库数量（订单→出库单过账时累加，红冲回退且不小于 0）',
  PRIMARY KEY (`id`),
  KEY `idx_sales_order_item_doc` (`doc_id`),
  KEY `idx_sales_order_item_product` (`product_id`),
  KEY `idx_sales_order_item_seq` (`doc_id`,`seq`),
  KEY `idx_sales_order_item_src` (`src_item_id`),
  CONSTRAINT `fk_sales_order_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_sales_order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sales_order_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单行项（B4）';

-- 13/18 入库单行项（无特有列：参考侧字段全部来自 DocItemMixin）
CREATE TABLE `t_ctms_stock_in_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '入库单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '入库数量（定点 3 位；过账按 sign=+1 累加结存）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（先舍入到 2 位）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（可空；未填时**回落表头仓库**）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（采购单行项，可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  PRIMARY KEY (`id`),
  KEY `idx_stock_in_item_doc` (`doc_id`),
  KEY `idx_stock_in_item_product` (`product_id`),
  KEY `idx_stock_in_item_seq` (`doc_id`,`seq`),
  KEY `idx_stock_in_item_warehouse` (`warehouse_id`),
  KEY `idx_stock_in_item_src` (`src_item_id`),
  CONSTRAINT `fk_stock_in_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_stock_in` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_stock_in_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单行项（B4）';

-- 14/18 出库单行项（无特有列）
CREATE TABLE `t_ctms_stock_out_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '出库单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '出库数量（定点 3 位；过账按 sign=-1 扣减结存）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（定点 4 位）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（先舍入到 2 位）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（可空；未填时**回落表头仓库**）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（可空）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（销售订单行项，可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  PRIMARY KEY (`id`),
  KEY `idx_stock_out_item_doc` (`doc_id`),
  KEY `idx_stock_out_item_product` (`product_id`),
  KEY `idx_stock_out_item_seq` (`doc_id`,`seq`),
  KEY `idx_stock_out_item_warehouse` (`warehouse_id`),
  KEY `idx_stock_out_item_src` (`src_item_id`),
  CONSTRAINT `fk_stock_out_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_stock_out` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_stock_out_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单行项（B4）';

-- 15/18 盘点单行项（+ book_qty/actual_qty/diff_qty/diff_reason：账面固定只读、实盘、差异）
CREATE TABLE `t_ctms_stocktake_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '盘点单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '数量（盘点单不使用；保留列传 0，避免公共层字段漂移）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（盘点不使用，恒为 0）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（盘点不使用，恒为 0）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（盘点单不使用：仓库由表头决定）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（盘点单不使用）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `book_qty`       decimal(16,3) NOT NULL DEFAULT 0           COMMENT '账面数量（生成行项时固定、界面只读；等于生成时该（物料,仓库）结存）',
  `actual_qty`     decimal(16,3) NOT NULL DEFAULT 0           COMMENT '实盘数量（非负由应用层校验）',
  `diff_qty`       decimal(16,3) NOT NULL DEFAULT 0           COMMENT '差异 = 实盘 − 账面（定点 3 位；正=盘盈 负=盘亏）',
  `diff_reason`    varchar(1000) DEFAULT NULL                 COMMENT '差异原因（可空）',
  PRIMARY KEY (`id`),
  KEY `idx_stocktake_item_doc` (`doc_id`),
  KEY `idx_stocktake_item_product` (`product_id`),
  KEY `idx_stocktake_item_seq` (`doc_id`,`seq`),
  CONSTRAINT `fk_stocktake_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_stocktake` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_stocktake_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单行项（B4）';

-- 16/18 调拨单行项（无特有列；**不使用** warehouse_id —— 仓库由表头两仓决定，`models_doc.py:403`）
CREATE TABLE `t_ctms_transfer_item` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `doc_id`         varchar(64)   NOT NULL                     COMMENT '调拨单ID（FK + 级联删除）',
  `seq`            int(11)       NOT NULL DEFAULT 1           COMMENT '序号',
  `product_id`     varchar(64)   NOT NULL                     COMMENT '物料档案ID（DB 级 NOT NULL + FK）',
  `product_code`   varchar(32)   NOT NULL                     COMMENT '物料编码快照',
  `product_name`   varchar(128)  NOT NULL                     COMMENT '物料名称快照',
  `spec`           varchar(128)  DEFAULT NULL                 COMMENT '规格快照',
  `uom_name`       varchar(32)   DEFAULT NULL                 COMMENT '单位名快照',
  `uom_decimals`   int(11)       DEFAULT NULL                 COMMENT '单位小数位快照',
  `qty`            decimal(16,3) NOT NULL DEFAULT 0           COMMENT '调拨数量（定点 3 位；每行写调出/调入两条流水）',
  `unit_price`     decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（调拨不使用，恒为 0；流水单价也写 0）',
  `amount`         decimal(16,2) NOT NULL DEFAULT 0           COMMENT '行金额（调拨不使用，恒为 0）',
  `warehouse_id`   varchar(64)   DEFAULT NULL                 COMMENT '行级仓库ID（**调拨刻意不使用**：仓库由表头两仓决定，保留列以复用公共字段）',
  `warehouse_name` varchar(64)   DEFAULT NULL                 COMMENT '行级仓库名快照（调拨刻意不使用）',
  `src_item_id`    varchar(64)   DEFAULT NULL                 COMMENT '下推来源行项ID（可空、无 FK）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '行备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空）',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  PRIMARY KEY (`id`),
  KEY `idx_transfer_item_doc` (`doc_id`),
  KEY `idx_transfer_item_product` (`product_id`),
  KEY `idx_transfer_item_seq` (`doc_id`,`seq`),
  KEY `idx_transfer_item_src` (`src_item_id`),
  CONSTRAINT `fk_transfer_item_doc`     FOREIGN KEY (`doc_id`)     REFERENCES `t_ctms_transfer` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_transfer_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单行项（B4）';


-- ---------- 17/18 库存结存（`stocks`，`models_stock.py:38-53`） ----------
-- 唯一约束 `uk_stock_product_warehouse(product_id, warehouse_id)` = 参考侧 `uq_stock_prod_wh`（F-2）：
--   * 过账取结存行时先按唯一键 `SELECT ... FOR UPDATE` 加行锁（F-1）；
--   * 行不存在则插入，捕获重复键（1062）后**回滚该分支并重试**（有限次，见 notes/01-base.md §6）。
-- 口径 D4/D5：**只记数量不记成本**，故本表没有金额/单价列。
CREATE TABLE `t_ctms_stock` (
  `id`           varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `product_id`   varchar(64)   NOT NULL                     COMMENT '物料档案ID（FK；与 warehouse_id 构成唯一键）',
  `warehouse_id` varchar(64)   NOT NULL                     COMMENT '仓库ID（FK）',
  `qty`          decimal(16,3) NOT NULL DEFAULT 0           COMMENT '结存数量（定点 3 位；**只能由过账服务维护**，界面无写入口）',
  `create_time`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（首次过账时建行）',
  `update_time`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后过账时间（参考侧由过账服务显式赋值，本侧用 ON UPDATE 兜底）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_product_warehouse` (`product_id`,`warehouse_id`),
  KEY `idx_stock_warehouse` (`warehouse_id`),
  KEY `idx_stock_product` (`product_id`),
  CONSTRAINT `fk_stock_product`   FOREIGN KEY (`product_id`)   REFERENCES `t_ctms_product` (`id`),
  CONSTRAINT `fk_stock_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存结存（B4；(物料,仓库) 唯一）';

-- ---------- 18/18 库存流水（`stock_ledger`，`models_stock.py:56-80`） ----------
-- **只增不改**：本表刻意没有 update_time/update_id/update_by 列，也没有删除入口 ——
--   红冲是**追加负数流水**（原流水保留可查），所以"不可修改/删除"在库级就成立。
-- 两个索引来自 F-5（`docs/12-erp-system-design.md:494` 有、参考 ORM 未声明）：
--   idx_ledger_product_warehouse_id (product_id, warehouse_id, id) —— 流水下钻 + 不变式校验；
--   idx_ledger_doc (doc_type, doc_id) —— 按单据反查流水（红冲与级联盘点用）。
CREATE TABLE `t_ctms_stock_ledger` (
  `id`           varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `product_id`   varchar(64)   NOT NULL                     COMMENT '物料档案ID（FK）',
  `warehouse_id` varchar(64)   NOT NULL                     COMMENT '仓库ID（FK）',
  `biz_type`     varchar(24)   NOT NULL                     COMMENT '业务类型：采购入库/退货入库/盘盈入库/其他入库/销售出库/领用出库/盘亏出库/其他出库/调拨出库/调拨入库；红冲加前缀 红冲-',
  `doc_type`     varchar(24)   NOT NULL                     COMMENT '单据类型（stock_in/stock_out/stock_transfer）',
  `doc_id`       varchar(64)   NOT NULL                     COMMENT '单据ID（多态，无 FK；与 doc_type 成对）',
  `doc_no`       varchar(64)   NOT NULL                     COMMENT '单据号（调拨的两条流水共用同一单号）',
  `src_doc_no`   varchar(64)   DEFAULT NULL                 COMMENT '来源单号快照（下推链路的源单，可空）',
  `qty_change`   decimal(16,3) NOT NULL                     COMMENT '数量变动（**正入负出**；红冲为反向）',
  `qty_after`    decimal(16,3) NOT NULL                     COMMENT '变动后结存快照（下钻/对账用）',
  `unit_price`   decimal(14,4) DEFAULT NULL                 COMMENT '单价（**记录性**：只用于「货品总额度」与单据展示，不参与结存计算；调拨为 0）',
  `dept_id`      varchar(64)   DEFAULT NULL                 COMMENT '归属部门快照（过账时取单据 dept_id；数据范围判定用，可空、无 FK）',
  `create_id`    varchar(64)   DEFAULT NULL                 COMMENT '操作人用户ID（可空、无 FK：§2.8 #13）',
  `create_by`    varchar(64)   DEFAULT NULL                 COMMENT '操作人登录名快照（可空）',
  `remark`       varchar(1000) DEFAULT NULL                 COMMENT '备注（红冲写「反审核红冲」）',
  `create_time`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记账时间（流水下钻按此列倒序）',
  PRIMARY KEY (`id`),
  KEY `idx_ledger_product_warehouse_id` (`product_id`,`warehouse_id`,`id`),
  KEY `idx_ledger_doc` (`doc_type`,`doc_id`),
  KEY `idx_ledger_create_time` (`create_time`),
  KEY `idx_ledger_biz_type` (`biz_type`),
  KEY `idx_ledger_dept` (`dept_id`),
  KEY `idx_ledger_create_id` (`create_id`),
  CONSTRAINT `fk_stock_ledger_product`   FOREIGN KEY (`product_id`)   REFERENCES `t_ctms_product` (`id`),
  CONSTRAINT `fk_stock_ledger_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `t_ctms_warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水（B4；只增不改）';


-- ############################################################################
-- ② 字典与系统参数（tasks.md §1.3）
-- ############################################################################
-- 口径与依据（**取值逐条取自参考仓库，不是自己编的**）：
--   * `stock_in_types`  ← `models_doc.py:57` IN_TYPES = ["采购入库","退货入库","其他入库"]
--       + `盘盈入库`（status='1' 停用：**只由盘点审核自动生成**，不在下拉里让用户手选）
--   * `stock_out_types` ← `models_doc.py:58` OUT_TYPES = ["销售出库","领用出库","其他出库"]
--       + `盘亏出库`（status='1' 停用：只由盘点审核自动生成）
--   * `stock_take_types` ← `models_doc.py:59` TAKE_TYPES = full/partial（dict_value=英文码，dict_label=中文）
--   * `stock_biz_types` ← `models_stock.py:29-35, 84` 的 10 个 biz_type（红冲为前缀 `红冲-`，
--       故用 `备注` 说明而**不**把 20 个带前缀的值都铺一行）
--   * 系统参数 `stock_allow_negative` ← Q-B8「默认不允许负库存」；参考侧参数名
--       `allow_negative_stock`（posting_service.py:63-70），目标侧带域前缀避免与平台参数撞名。
-- 口径说明（与 B3 一致的部分）：
--   * `status`：'0' 正常（前端能取到）、'1' 停用（`SysDictDataMapper.selectDictDataByType`
--      硬过滤 `status='0'`，故停用值不会出现在下拉里）；
--   * 幂等：先按 dict_type / dict_code / config_key 删本批行再插（`sys_dict_type.dict_type`
--      与 `sys_dict_data.dict_type+dict_value` 有唯一约束，绝不能只写 INSERT）。

DELETE FROM `sys_dict_data` WHERE `dict_type` IN
    ('stock_in_types', 'stock_out_types', 'stock_take_types', 'stock_biz_types');
DELETE FROM `sys_dict_type` WHERE `dict_type` IN
    ('stock_in_types', 'stock_out_types', 'stock_take_types', 'stock_biz_types');
DELETE FROM `sys_config` WHERE `config_key` = 'stock_allow_negative';

INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `status`, `create_by`, `create_time`, `remark`) VALUES
('9F2C0000000000000000000000D200', '入库类型',     'stock_in_types',   '0', 'admin', sysdate(),
 '入库单 in_type 取值（参考侧 IN_TYPES）；盘盈入库仅由盘点审核生成，停用不供手选'),
('9F2C0000000000000000000000D201', '出库类型',     'stock_out_types',  '0', 'admin', sysdate(),
 '出库单 out_type 取值（参考侧 OUT_TYPES）；盘亏出库仅由盘点审核生成，停用不供手选'),
('9F2C0000000000000000000000D202', '盘点范围',     'stock_take_types', '0', 'admin', sysdate(),
 '盘点单 take_type 取值（参考侧 TAKE_TYPES：full/partial）'),
('9F2C0000000000000000000000D203', '库存业务类型', 'stock_biz_types',  '0', 'admin', sysdate(),
 '库存流水 biz_type 取值（参考侧 BIZ_TYPES）；红冲记录为前缀「红冲-」+ 本表取值');

INSERT INTO `sys_dict_data`
(`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `remark`) VALUES
-- 入库类型
('9F2C0000000000000000000000E201',  1, '采购入库', '采购入库', 'stock_in_types',  NULL, 'primary', 'Y', '0', 'admin', sysdate(), '参考侧 IN_TYPES[0]（默认值）'),
('9F2C0000000000000000000000E202',  2, '退货入库', '退货入库', 'stock_in_types',  NULL, 'warning', 'N', '0', 'admin', sysdate(), '参考侧 IN_TYPES[1]'),
('9F2C0000000000000000000000E203',  3, '其他入库', '其他入库', 'stock_in_types',  NULL, 'info',    'N', '0', 'admin', sysdate(), '参考侧 IN_TYPES[2]'),
('9F2C0000000000000000000000E204',  4, '盘盈入库', '盘盈入库', 'stock_in_types',  NULL, 'success', 'N', '1', 'admin', sysdate(), '**系统生成**（盘点审核自动建盘盈入库单），停用：不出现在手工下拉'),
-- 出库类型
('9F2C0000000000000000000000E211',  1, '销售出库', '销售出库', 'stock_out_types', NULL, 'primary', 'Y', '0', 'admin', sysdate(), '参考侧 OUT_TYPES[0]（默认值）'),
('9F2C0000000000000000000000E212',  2, '领用出库', '领用出库', 'stock_out_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), '参考侧 OUT_TYPES[1]'),
('9F2C0000000000000000000000E213',  3, '其他出库', '其他出库', 'stock_out_types', NULL, 'info',    'N', '0', 'admin', sysdate(), '参考侧 OUT_TYPES[2]'),
('9F2C0000000000000000000000E214',  4, '盘亏出库', '盘亏出库', 'stock_out_types', NULL, 'danger',  'N', '1', 'admin', sysdate(), '**系统生成**（盘点审核自动建盘亏出库单），停用：不出现在手工下拉'),
-- 盘点范围
('9F2C0000000000000000000000E221',  1, '全盘', 'full',    'stock_take_types', NULL, 'primary', 'Y', '0', 'admin', sysdate(), '参考侧 TAKE_TYPES[0]（默认值）：取该仓库结存非零的全部物料'),
('9F2C0000000000000000000000E222',  2, '抽盘', 'partial', 'stock_take_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), '参考侧 TAKE_TYPES[1]：按指定物料或商品类型（含整棵子树）'),
-- 库存业务类型（流水展示口径）
('9F2C0000000000000000000000E231',  1, '采购入库', '采购入库', 'stock_biz_types', NULL, 'success', 'N', '0', 'admin', sysdate(), '入库单 in_type 带入；「货品总额度」按 LIKE %入库% 计入'),
('9F2C0000000000000000000000E232',  2, '退货入库', '退货入库', 'stock_biz_types', NULL, 'success', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E233',  3, '盘盈入库', '盘盈入库', 'stock_biz_types', NULL, 'success', 'N', '0', 'admin', sysdate(), '盘点审核生成的盘盈单带入'),
('9F2C0000000000000000000000E234',  4, '其他入库', '其他入库', 'stock_biz_types', NULL, 'success', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E235',  5, '销售出库', '销售出库', 'stock_biz_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E236',  6, '领用出库', '领用出库', 'stock_biz_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E237',  7, '盘亏出库', '盘亏出库', 'stock_biz_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), '盘点审核生成的盘亏单带入（豁免负库存校验）'),
('9F2C0000000000000000000000E238',  8, '其他出库', '其他出库', 'stock_biz_types', NULL, 'warning', 'N', '0', 'admin', sysdate(), NULL),
('9F2C0000000000000000000000E239',  9, '调拨出库', '调拨出库', 'stock_biz_types', NULL, 'info',    'N', '0', 'admin', sysdate(), '调拨单调出仓（负数量）；**不计入货品总额度**'),
('9F2C0000000000000000000000E23A', 10, '调拨入库', '调拨入库', 'stock_biz_types', NULL, 'info',    'N', '0', 'admin', sysdate(), '调拨单调入仓（正数量）；**不计入货品总额度**（含其红冲）');

-- 系统参数：是否允许负库存（**默认关闭**；config_type='Y' 表示系统内置：界面不可删、可改值）
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`) VALUES
('9F2C0000000000000000000000F100', '是否允许负库存', 'stock_allow_negative', 'false', 'Y', 'admin', sysdate(),
 '默认 false（关闭）：会使结存小于 0 的出库/调拨出库审核被拒且不留痕。true/1/Y 表示放开（Q-B8）。盘亏单过账按口径单独豁免，不受本参数影响');


-- ############################################################################
-- ③ 单据编号配置（tasks.md §1.3；8 类单据各一条，格式 {前缀}{yyyyMM}{6 位序号}，按月重置）
-- ############################################################################
-- 【为什么编号必须走平台服务】单号是单证的对外标识，取号要并发安全、要能审计；
--   平台 `ruoyi-serial` 已经提供（Redis 计数器 + 分布式锁 + `t_code_sequence_log` 流水），
--   B4 **不自算号**（否则 8 类单据会各写一份取号逻辑，正是"公共层唯一实现点"要防的）。
--   公共层入口：`com.ruoyi.ctms.erp.base.ErpDocNoGenerator#nextDocNo(ErpDocType)`。
--
-- 【规则设计（与 B3 的合同编号同一套机制）】每条配置 3 条规则：
--   ① `rule_type='0'` 固定值 = 单据前缀（PR/PO/SR/SO/IN/OUT/ST/DB）；
--   ② `rule_type='3'` 业务参数 `ym` = **yyyyMM**（B3 §1.3 给 ruoyi-serial 加的规则类型）；
--   ③ `rule_type='2'` 流水号 6 位补零、`seq_reset_type='3'`（按月）。
--   ⚠ **为什么月份用"业务参数"而不是"日期规则"**：平台的重置类型里只有"按年"会进入
--      计数器分桶键（`CodeRenderSupport.bucketKeyOf`：仅 `seqResetType=4` 且给了参考日期时
--      追加 `:y<年>`；按日/按周/按月都**不参与分桶**，`t_code_config.current_seq` 是单列、
--      真正的计数器在 Redis）。若把月份写成日期规则 `yyyyMM`，则跨月时序号**不会**从 000001
--      重新开始（只有 `CodeRestTask` 在每月 1 号当天跑到才重置 DB 单列，而 Redis 计数器不变）。
--      用业务参数 `ym=yyyyMM` 分桶后，计数器键变成
--      `code:gen:seq:<confId>:ym=202610` → **新月份天然从 0 起**（惰性按月重置、不依赖定时任务，
--      与 B3 的"惰性跨年重置"同一机制）。调用方（`ErpDocNoGenerator`）必须传 `ym`。
--
-- 【id 约定】配置 id = `9F2C0000000000000000000000C101` ~ `C108`（顺序即下表 8 类单据）；
--   规则 id 用 `C201` ~ `C218` —— 刻意避开 B3 已占用的 `C101`~`C105`（那是
--   `t_code_config_rule` 的行 id，与 `t_code_config.id` 不同表，但保持全局不重名更省心）。
--   ⚠ 配置 id 与 Java 常量 `ErpDocNoGenerator.CONF_ID_*` 必须逐字一致（两处真源，改一处必须改两处）。
--
-- 幂等：先删本批规则再删配置，最后插入；可反复执行。
-- ⚠ 本文件**不会**清 Redis 计数器：确实要"从 000001 重新开始"时必须同时删
--   `code:gen:seq:<confId>*` 对应的 Redis 键（生产禁止随意做）。

SET @b4_conf_pr  = '9F2C0000000000000000000000C101';
SET @b4_conf_po  = '9F2C0000000000000000000000C102';
SET @b4_conf_sr  = '9F2C0000000000000000000000C103';
SET @b4_conf_so  = '9F2C0000000000000000000000C104';
SET @b4_conf_in  = '9F2C0000000000000000000000C105';
SET @b4_conf_out = '9F2C0000000000000000000000C106';
SET @b4_conf_st  = '9F2C0000000000000000000000C107';
SET @b4_conf_db  = '9F2C0000000000000000000000C108';

DELETE FROM `t_code_config_rule` WHERE `config_id` IN
    (@b4_conf_pr, @b4_conf_po, @b4_conf_sr, @b4_conf_so, @b4_conf_in, @b4_conf_out, @b4_conf_st, @b4_conf_db);
DELETE FROM `t_code_config` WHERE `id` IN
    (@b4_conf_pr, @b4_conf_po, @b4_conf_sr, @b4_conf_so, @b4_conf_in, @b4_conf_out, @b4_conf_st, @b4_conf_db);

INSERT INTO `t_code_config` (`id`, `title`, `current_seq`, `enable_flag`, `del_flag`, `create_time`, `remark`) VALUES
(@b4_conf_pr,  '采购申请单编号配置', 0, '1', '0', sysdate(), 'PR + yyyyMM + 6 位序号；按 ym 分桶=按月重置（见本段说明）'),
(@b4_conf_po,  '采购单编号配置',     0, '1', '0', sysdate(), 'PO + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_sr,  '销售申请单编号配置', 0, '1', '0', sysdate(), 'SR + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_so,  '销售订单编号配置',   0, '1', '0', sysdate(), 'SO + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_in,  '入库单编号配置',     0, '1', '0', sysdate(), 'IN + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_out, '出库单编号配置',     0, '1', '0', sysdate(), 'OUT + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_st,  '盘点单编号配置',     0, '1', '0', sysdate(), 'ST + yyyyMM + 6 位序号；按 ym 分桶=按月重置'),
(@b4_conf_db,  '调拨单编号配置',     0, '1', '0', sysdate(), 'DB + yyyyMM + 6 位序号；按 ym 分桶=按月重置');

INSERT INTO `t_code_config_rule`
(`id`, `config_id`, `rule_type`, `rule_value`, `pad_zero`, `seq_reset_type`, `sort`, `del_flag`, `create_time`) VALUES
-- 采购申请单：PR
('9F2C0000000000000000000000C201', @b4_conf_pr,  '0', 'PR',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C202', @b4_conf_pr,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C203', @b4_conf_pr,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 采购单：PO
('9F2C0000000000000000000000C204', @b4_conf_po,  '0', 'PO',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C205', @b4_conf_po,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C206', @b4_conf_po,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 销售申请单：SR
('9F2C0000000000000000000000C207', @b4_conf_sr,  '0', 'SR',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C208', @b4_conf_sr,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C209', @b4_conf_sr,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 销售订单：SO
('9F2C0000000000000000000000C20A', @b4_conf_so,  '0', 'SO',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C20B', @b4_conf_so,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C20C', @b4_conf_so,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 入库单：IN
('9F2C0000000000000000000000C20D', @b4_conf_in,  '0', 'IN',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C20E', @b4_conf_in,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C20F', @b4_conf_in,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 出库单：OUT
('9F2C0000000000000000000000C210', @b4_conf_out, '0', 'OUT', '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C211', @b4_conf_out, '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C212', @b4_conf_out, '2', '6',   '1', '3', 3, '0', sysdate()),
-- 盘点单：ST
('9F2C0000000000000000000000C213', @b4_conf_st,  '0', 'ST',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C214', @b4_conf_st,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C215', @b4_conf_st,  '2', '6',   '1', '3', 3, '0', sysdate()),
-- 调拨单：DB
('9F2C0000000000000000000000C216', @b4_conf_db,  '0', 'DB',  '0', '0', 1, '0', sysdate()),
('9F2C0000000000000000000000C217', @b4_conf_db,  '3', 'ym',  '0', '0', 2, '0', sysdate()),
('9F2C0000000000000000000000C218', @b4_conf_db,  '2', '6',   '1', '3', 3, '0', sysdate());


-- ############################################################################
-- ④ 编排自检（每行 = 中文检查项名 + 期望值；照抄 B3 的判读方式）
-- ############################################################################
-- 判读方式：`结果` 列必须等于「检查项」括号里的期望值；本文件连续执行两次结果一致。

SELECT '① B4 的 18 张表齐备（应为18）' AS `检查项`, COUNT(*) AS `结果`
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
UNION ALL SELECT '② B4 18 张表合计列数（应为480）', COUNT(*) FROM information_schema.COLUMNS
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
UNION ALL SELECT '③ 8 张表头的公共列齐备（应为8×21=168，21 个公共列名）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN
       ('doc_no','doc_date','status','dept_id','create_id','handler_user_id','handler_name','contract_id',
        'contract_no','source_doc_type','source_doc_id','source_doc_no','submitted_by','submitted_at',
        'approved_by','approved_at','voided_by','voided_at','void_reason','posted','del_flag')
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_order','t_ctms_sales_request',
                      't_ctms_sales_order','t_ctms_stock_in','t_ctms_stock_out',
                      't_ctms_stocktake','t_ctms_transfer')
UNION ALL SELECT '④ 8 张行项的公共列齐备（产品/快照/精度/来源）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN
       ('doc_id','seq','product_id','product_code','product_name','spec','uom_name','uom_decimals',
        'qty','unit_price','amount','warehouse_id','src_item_id')
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑤ 结存唯一约束 (product_id, warehouse_id) 列数（应为2）', COUNT(*)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_stock'
   AND INDEX_NAME = 'uk_stock_product_warehouse' AND NON_UNIQUE = 0
UNION ALL SELECT '⑥ 流水索引 (product_id, warehouse_id, id) 列数（应为3）', COUNT(*)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_stock_ledger'
   AND INDEX_NAME = 'idx_ledger_product_warehouse_id'
UNION ALL SELECT '⑦ 流水索引 (doc_type, doc_id) 列数（应为2）', COUNT(*)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_stock_ledger'
   AND INDEX_NAME = 'idx_ledger_doc'
UNION ALL SELECT '⑧ 行项→表头的级联删除外键条数（应为8）', COUNT(*)
  FROM information_schema.REFERENTIAL_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND DELETE_RULE = 'CASCADE'
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑨ 行项→物料的外键条数（应为8）', COUNT(*)
  FROM information_schema.KEY_COLUMN_USAGE
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 't_ctms_product'
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑩ B4 表的外键总数（应为43）', COUNT(*)
  FROM information_schema.TABLE_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY'
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_request_item',
                      't_ctms_purchase_order','t_ctms_purchase_order_item',
                      't_ctms_sales_request','t_ctms_sales_request_item',
                      't_ctms_sales_order','t_ctms_sales_order_item',
                      't_ctms_stock_in','t_ctms_stock_in_item',
                      't_ctms_stock_out','t_ctms_stock_out_item',
                      't_ctms_stocktake','t_ctms_stocktake_item',
                      't_ctms_transfer','t_ctms_transfer_item',
                      't_ctms_stock','t_ctms_stock_ledger')
UNION ALL SELECT '⑪ 金额列 decimal(16,2) 的表数（应为12：4 表头 + 8 行项）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_TYPE = 'decimal(16,2)'
   AND ((COLUMN_NAME = 'total_amount')
     OR (COLUMN_NAME = 'amount'
         AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                            't_ctms_sales_request_item','t_ctms_sales_order_item',
                            't_ctms_stock_in_item','t_ctms_stock_out_item',
                            't_ctms_stocktake_item','t_ctms_transfer_item')))
UNION ALL SELECT '⑫ 单价列 decimal(14,4) 的行项表数（应为8）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'unit_price' AND COLUMN_TYPE = 'decimal(14,4)'
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑬ 数量列 decimal(16,3) 的行项表数（应为8）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'qty' AND COLUMN_TYPE = 'decimal(16,3)'
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑭ 行项 product_id / qty / amount 均非空（应为3×8=24）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND IS_NULLABLE = 'NO'
   AND COLUMN_NAME IN ('product_id','qty','amount')
   AND TABLE_NAME IN ('t_ctms_purchase_request_item','t_ctms_purchase_order_item',
                      't_ctms_sales_request_item','t_ctms_sales_order_item',
                      't_ctms_stock_in_item','t_ctms_stock_out_item',
                      't_ctms_stocktake_item','t_ctms_transfer_item')
UNION ALL SELECT '⑮ 18 张表排序规则均为 utf8mb4_0900_ai_ci（应为18）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
   AND TABLE_NAME IN ('t_ctms_purchase_request','t_ctms_purchase_request_item',
                      't_ctms_purchase_order','t_ctms_purchase_order_item',
                      't_ctms_sales_request','t_ctms_sales_request_item',
                      't_ctms_sales_order','t_ctms_sales_order_item',
                      't_ctms_stock_in','t_ctms_stock_in_item',
                      't_ctms_stock_out','t_ctms_stock_out_item',
                      't_ctms_stocktake','t_ctms_stocktake_item',
                      't_ctms_transfer','t_ctms_transfer_item',
                      't_ctms_stock','t_ctms_stock_ledger')
UNION ALL SELECT '⑯ B4 字典类型齐备（应为4）', COUNT(*)
  FROM sys_dict_type WHERE dict_type IN ('stock_in_types','stock_out_types','stock_take_types','stock_biz_types')
UNION ALL SELECT '⑰ B4 字典数据条数（应为 4+4+2+10=20）', COUNT(*)
  FROM sys_dict_data WHERE dict_type IN ('stock_in_types','stock_out_types','stock_take_types','stock_biz_types')
UNION ALL SELECT '⑱ 手工可选入库类型（status=0，应为3）', COUNT(*)
  FROM sys_dict_data WHERE dict_type='stock_in_types' AND status='0'
UNION ALL SELECT '⑲ 手工可选出库类型（status=0，应为3）', COUNT(*)
  FROM sys_dict_data WHERE dict_type='stock_out_types' AND status='0'
UNION ALL SELECT '⑳ 负库存参数默认关闭（应为1）', COUNT(*)
  FROM sys_config WHERE config_key='stock_allow_negative' AND config_value='false'
UNION ALL SELECT '㉑ B3 的 13 张表未被本文件破坏（应为13）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse',
                      't_ctms_product')
UNION ALL SELECT '㉒ 上游基线 sys_user 未改（列数应为21）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
UNION ALL SELECT '㉓ 上游基线 sys_dept 未改（列数应为16）', COUNT(*)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_dept'
UNION ALL SELECT '㉔ 每张表的列数逐表核对（应为18 张表命中 18 条期望值）', COUNT(*)
  FROM (
    SELECT TABLE_NAME, COUNT(*) AS c FROM information_schema.COLUMNS
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
     GROUP BY TABLE_NAME
  ) t
 WHERE (t.TABLE_NAME = 't_ctms_purchase_request'      AND t.c = 32)
    OR (t.TABLE_NAME = 't_ctms_purchase_request_item' AND t.c = 23)
    OR (t.TABLE_NAME = 't_ctms_purchase_order'        AND t.c = 36)
    OR (t.TABLE_NAME = 't_ctms_purchase_order_item'   AND t.c = 23)
    OR (t.TABLE_NAME = 't_ctms_sales_request'         AND t.c = 32)
    OR (t.TABLE_NAME = 't_ctms_sales_request_item'    AND t.c = 23)
    OR (t.TABLE_NAME = 't_ctms_sales_order'           AND t.c = 38)
    OR (t.TABLE_NAME = 't_ctms_sales_order_item'      AND t.c = 23)
    OR (t.TABLE_NAME = 't_ctms_stock_in'              AND t.c = 34)
    OR (t.TABLE_NAME = 't_ctms_stock_in_item'         AND t.c = 22)
    OR (t.TABLE_NAME = 't_ctms_stock_out'             AND t.c = 34)
    OR (t.TABLE_NAME = 't_ctms_stock_out_item'        AND t.c = 22)
    OR (t.TABLE_NAME = 't_ctms_stocktake'             AND t.c = 36)
    OR (t.TABLE_NAME = 't_ctms_stocktake_item'        AND t.c = 26)
    OR (t.TABLE_NAME = 't_ctms_transfer'              AND t.c = 32)
    OR (t.TABLE_NAME = 't_ctms_transfer_item'         AND t.c = 22)
    OR (t.TABLE_NAME = 't_ctms_stock'                 AND t.c = 6)
    OR (t.TABLE_NAME = 't_ctms_stock_ledger'          AND t.c = 16)
UNION ALL SELECT '㉕ B4 单据编号配置齐备（应为8：C101~C108）', COUNT(*)
  FROM t_code_config
 WHERE id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
              '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
              '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
              '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND enable_flag = '1' AND del_flag = '0'
UNION ALL SELECT '㉖ B4 编号规则条数（应为 8×3=24）', COUNT(*)
  FROM t_code_config_rule
 WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                     '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                     '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                     '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND del_flag = '0'
UNION ALL SELECT '㉗ 每条配置都有 ym 业务参数规则（应为8；ErpDocNoGenerator 必须传 ym=yyyyMM）', COUNT(DISTINCT config_id)
  FROM t_code_config_rule
 WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                     '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                     '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                     '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND rule_type = '3' AND rule_value = 'ym' AND del_flag = '0'
UNION ALL SELECT '㉘ 每条配置都有 6 位补零 + 按月重置的序号规则（应为8）', COUNT(DISTINCT config_id)
  FROM t_code_config_rule
 WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                     '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                     '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                     '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND rule_type = '2' AND rule_value = '6' AND pad_zero = '1' AND seq_reset_type = '3' AND del_flag = '0'
UNION ALL SELECT '㉙ 规则顺序均为 固定值/业务参数/序号（应为8）', COUNT(*)
  FROM (SELECT config_id, GROUP_CONCAT(rule_type ORDER BY sort) AS seq
          FROM t_code_config_rule
         WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                             '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                             '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                             '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
           AND del_flag = '0'
         GROUP BY config_id) r
 WHERE r.seq = '0,3,2'
UNION ALL SELECT '㉚ 8 个单据前缀齐备（应为8：PR/PO/SR/SO/IN/OUT/ST/DB）', COUNT(DISTINCT rule_value)
  FROM t_code_config_rule
 WHERE config_id IN ('9F2C0000000000000000000000C101','9F2C0000000000000000000000C102',
                     '9F2C0000000000000000000000C103','9F2C0000000000000000000000C104',
                     '9F2C0000000000000000000000C105','9F2C0000000000000000000000C106',
                     '9F2C0000000000000000000000C107','9F2C0000000000000000000000C108')
   AND rule_type = '0'
   AND rule_value IN ('PR','PO','SR','SO','IN','OUT','ST','DB');

-- ============================================================================
-- 列数对照（本文件 vs 参考仓库 §2.3 的字段级清单；写文件时已逐表核对，第 ③ 段自检可复核）
--   表头：purchase_request 32 / purchase_order 36 / sales_request 32 / sales_order 38 /
--         stock_in 34 / stock_out 34 / stocktake 36 / transfer 32   → 274
--   行项：pr_item 23 / po_item 23 / sr_item 23 / so_item 23 /
--         stock_in_item 22 / stock_out_item 22 / stocktake_item 26 / transfer_item 22 → 184
--   结存 t_ctms_stock 6 / 流水 t_ctms_stock_ledger 16 → 22
--   合计 **480**
-- 与参考侧的**刻意差异**（逐条理由见 notes/01-base.md §4）：
--   ① 主键/外键列统一 varchar(64)（应用侧 UUID）而不是 Integer 自增 —— 与 B3 的 13 张表一致；
--   ② 8 张表头的 org_id/created_by → dept_id/create_id 且收紧为 NOT NULL + FK（F-3）；
--   ③ 行项补齐目标侧审计块（create_time/update_id/...）—— 参考侧 DocItemMixin 没有时间列；
--   ④ 2.6 的 LONGTEXT 一律 varchar(1000)（勘察 §8.2 的既有约定，B3 同款）；
--   ⑤ 采购申请/销售申请/盘点单**不加** total_amount（口径同参考 §2.3.3/§2.3.5：
--      列表金额由行项现算，唯一实现点是 ErpAmounts.totalOf）。
-- ============================================================================
