-- ============================================================================
-- OA 2.0 · B3 合同台账（`oa-contract-ledger`）· 13 张业务表 + 约束回补三段 + 编号配置
-- ----------------------------------------------------------------------------
-- 变更集与任务号：
--   changes = openspec/changes/oa-contract-ledger
--   tasks   = tasks.md §2.1（13 张建表 DDL）、§2.4（约束回补三段：回填 → 加 NOT NULL → 加 FK）
--   design  = design.md D-2（边界 39=13+18+8）、D-3（约束回补三类结论）、D-5（附件）、
--             D-6（编号）、D-9（字典/参数/编号）
--   边界与编排契约 = notes/ddl-scope.md §3（13 张表名冻结）/ §6（初始化-全部.sql 的段顺序）
--   字段级真源     = notes/ddl-review.md（13 张表逐列结论，合计 **224** 列，零 TBD，已签字放行）
--   编号落地口径   = notes/numbering-verification.md（§1.3 扩展分支：业务参数分桶 + 惰性跨年重置）
--
-- 需求与验收：
--   REQ-CTMS-001..008（合同台账域 8 条需求）、REQ-DATA-002（13 张表建表）、
--   REQ-DATA-003（约束回补范围：加 NOT NULL/FK 与「保持可空」的分组）、
--   AC-68（DDL 与评审表逐列一致、空库可执行、幂等）
--
-- ----------------------------------------------------------------------------
-- 【执行顺序】（唯一真源，必须在 `sql/` 目录内执行；详见 ddl-scope.md §6 的 7 段顺序）
--   ① table.sql（上游基线） → ② data.sql（上游基线数据） → ③ V1 二开 8 个文件
--   ④ B1 增量 → ⑤ B2 增量 → ⑥ 本文件（B3 建表 + 约束回补 + 编号配置）
--   ⑦ B3 菜单脚本 → ⑧ B4 占位
--   ⚠ 本文件第 ⑥-4 段的加 FK 引用 `sys_user(user_id)` / `sys_dept(dept_id)`，
--     第 ⑥-2 段还要用基线账号 `superAdmin` 与根部门兜底创建人/部门 ——
--     因此 **`data.sql` 必须先于本文件执行**，否则 `sys_user` 为空、兜底值取不到，
--     加 FK 会报 1452。这是**预期的阻断**（不允许用 `SET FOREIGN_KEY_CHECKS=0` 掩盖）。
--   ⚠ 迁移环境请先按 tasks.md §2.2/§2.3 跑「体检 → 影响行数报告 → 快照」，
--     **快照命令（请人工执行，本文件不代跑）**：
--       CREATE TABLE <表名>_bak_<日期> AS SELECT * FROM <表名>;
--     因为第 ⑥-1 段是 `DROP TABLE IF EXISTS` + `CREATE`，**会清空这 13 张表的存量数据**。
--
-- 【幂等约定】
--   ⑥-1 建表段：`DROP TABLE IF EXISTS` + `CREATE TABLE`（子表在前、主表在后，可重复执行）；
--   ⑥-2 回填段：纯 UPDATE，天然可重复执行（只处理 NULL 行）；
--   ⑥-3 加 NOT NULL 段：`information_schema.COLUMNS.IS_NULLABLE` 守卫 + `PREPARE/EXECUTE`；
--   ⑥-4 加 FK / 唯一键段：`information_schema.TABLE_CONSTRAINTS`（FK）与
--        `information_schema.STATISTICS`（唯一键）守卫 + `PREPARE/EXECUTE`；
--   ⑥-5 编号配置：先按固定主键 `DELETE` 再 `INSERT`。
--   整份脚本**连续执行两次都不得报错**（判读方式见第 ⑦ 段编排自检）。
--   写法惯例分别取自 `二开-2.0-B1-模板新增表.sql`（幂等建表 + 注释风格 + 自检 SELECT）
--   与 `二开-2.0-B2-打印模板内置版式.sql`（information_schema 守卫 + PREPARE/EXECUTE）。
--
-- 【不改上游】`sql/table.sql` 与 `sql/data.sql` 是上游原样基线，本变更集**不修改**
--   （REQ-DATA-005 方案 B / ddl-scope.md §6 约定 1，验证方式是 `git diff` 为空）。
--
-- 【排序规则事实（Q8 裁决的实测依据，2026-10-05 于 MySQL 8.0.40 实测）】
--   在被引用列所在库上执行 `SHOW FULL COLUMNS FROM sys_user LIKE 'user_id';` 等，实测：
--     sys_user.user_id  → varchar(64) NOT NULL，COLLATION_NAME = **utf8mb4_0900_ai_ci**
--     sys_dept.dept_id  → varchar(64) NOT NULL，COLLATION_NAME = **utf8mb4_0900_ai_ci**
--     sys_menu.menu_id  → varchar(64) NOT NULL，COLLATION_NAME = **utf8mb4_0900_ai_ci**
--   而库的 `information_schema.SCHEMATA.DEFAULT_COLLATION_NAME` = `utf8mb4_general_ci`
--   —— 即「库默认 general_ci / 业务列 0900_ai_ci」并存的实况（B1 文件头实测 ERROR 1267 的根因）。
--   结论：被引用列实测是 **`utf8mb4_0900_ai_ci`**，故本文件 13 张表统一
--   `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`（列继承表级 collation），
--   使 FK 参与列与 `t_ctms_*` 内部互相关联列全部与被引用列同 collation。
--   **不采用** `utf8mb4_general_ci`：那会让 FK 列与 `sys_user.user_id` 不一致，直接报 1267/3780。
--
-- 用法（中文文件名不能当命令行参数，走 stdin）：
--   Get-Content -Raw -Encoding UTF8 .\二开-合同台账.sql |
--     mysql --host=127.0.0.1 --user=root --database=<库> --default-character-set=utf8mb4 --table
-- ============================================================================


-- ############################################################################
-- ⑥-1 建表段：13 张业务表（表名与顺序取自 ddl-scope.md §3 / tasks.md §1.7）
-- ############################################################################
-- DROP 顺序 = **子表在前**，且必须同时满足「建表顺序的逆序」与「外键引用方先删」两条：
--   ① 删除引用了 `t_ctms_contract` 的 5 张表（行项/标签关联/变更历史/附件）；
--      ⚠ 合同主表**不能**紧跟其后：它还引用 `t_ctms_customer`/`t_ctms_supplier`，
--        先删合同表 → 行 ⑥ 建档表的 FK 自然解除 → 档案表才可删（否则报 ERROR 3730，
--        「Cannot drop table 't_ctms_customer' referenced by a foreign key constraint」）。
--   ② 再删合同主表（自引用 fk_contract_parent 不阻塞自身的 DROP）；
--   ③ 最后删档案/字典表（被引用方），自引用的商品类型与前向引用的物料一起清掉。
-- 顺序（13 → 空库）实测两次执行均成功，见文件末 ⑦ 段自检。

DROP TABLE IF EXISTS `t_ctms_contract_item`;
DROP TABLE IF EXISTS `t_ctms_contract_tag`;
DROP TABLE IF EXISTS `t_ctms_change_log`;
DROP TABLE IF EXISTS `t_ctms_attachment`;
DROP TABLE IF EXISTS `t_ctms_contract`;
DROP TABLE IF EXISTS `t_ctms_party_draft`;
DROP TABLE IF EXISTS `t_ctms_product`;
DROP TABLE IF EXISTS `t_ctms_tag`;
DROP TABLE IF EXISTS `t_ctms_customer`;
DROP TABLE IF EXISTS `t_ctms_supplier`;
DROP TABLE IF EXISTS `t_ctms_product_type`;
DROP TABLE IF EXISTS `t_ctms_uom`;
DROP TABLE IF EXISTS `t_ctms_warehouse`;


-- ---------- 1/13 业务字典与档案表（无外部引用，先建，供后续表引用） ----------

-- 标签字典（ddl-review §1.3；不设 del_flag：删除标签的引用副作用由 CASCADE 承担，见 ⑥-4）
CREATE TABLE `t_ctms_tag` (
  `id`          varchar(64)  NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `name`        varchar(64)  NOT NULL                     COMMENT '标签名称（全局唯一）',
  `color`       varchar(16)  DEFAULT NULL                 COMMENT '标签颜色（自动创建时按标签总数取模选色）',
  `builtin`     char(1)      NOT NULL DEFAULT '0'         COMMENT '是否内置标签：0-否 1-是',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id`   varchar(64)  DEFAULT NULL                 COMMENT '创建人用户ID（参考侧无对应列，按审计块补齐）',
  `create_by`   varchar(64)  DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`   varchar(64)  DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`   varchar(64)  DEFAULT NULL                 COMMENT '更新人登录名快照',
  `update_time` datetime     DEFAULT NULL                 COMMENT '更新时间（参考侧无 updated_at，按评审表保持可空）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同标签字典（B3）';

-- 客户档案（ddl-review §1.7；不设 del_flag：档案只做启停用，删除仅零引用时物理放行）
CREATE TABLE `t_ctms_customer` (
  `id`            varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `code`          varchar(32)   NOT NULL                     COMMENT '客户编码（全局唯一，停用项也占号）',
  `name`          varchar(128)  NOT NULL                     COMMENT '客户名称（全局唯一）',
  `short_name`    varchar(64)   DEFAULT NULL                 COMMENT '客户简称（**可为空**，与供应商刻意不对称）',
  `tax_no`        varchar(32)   DEFAULT NULL                 COMMENT '纳税人识别号',
  `contact_name`  varchar(64)   DEFAULT NULL                 COMMENT '联系人',
  `contact_phone` varchar(32)   DEFAULT NULL                 COMMENT '联系电话',
  `address`       varchar(255)  DEFAULT NULL                 COMMENT '地址',
  `bank_name`     varchar(128)  DEFAULT NULL                 COMMENT '开户行',
  `bank_account`  varchar(64)   DEFAULT NULL                 COMMENT '银行账号',
  `credit_limit`  decimal(14,2) DEFAULT NULL                 COMMENT '授信额度（非必填，可空）',
  `level`         varchar(8)    DEFAULT NULL                 COMMENT '客户等级 A/B/C/D（可空，非强约束）',
  `enable_flag`   char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用（Q6 裁决：业务表统一 enable_flag）',
  `remark`        varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_id`     varchar(64)   NOT NULL                     COMMENT '创建人用户ID（加 NOT NULL + FK，见 ⑥-3/⑥-4）',
  `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by`     varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`     varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`     varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_code` (`code`),
  UNIQUE KEY `uk_customer_name` (`name`),
  KEY `idx_customer_enable_flag` (`enable_flag`),
  KEY `idx_customer_create_id` (`create_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户档案（B3 主数据）';

-- 供应商档案（ddl-review §1.8；short_name 收紧为 NOT NULL，见 ⑥-2/⑥-3）
CREATE TABLE `t_ctms_supplier` (
  `id`            varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `code`          varchar(32)   NOT NULL                     COMMENT '供应商编码（全局唯一，停用项也占号）',
  `name`          varchar(128)  NOT NULL                     COMMENT '供应商名称（全局唯一）',
  `short_name`    varchar(64)   DEFAULT NULL                 COMMENT '供应商简称（**必填**，见 ⑥-2/⑥-3 收紧为非空）',
  `tax_no`        varchar(32)   DEFAULT NULL                 COMMENT '纳税人识别号',
  `contact_name`  varchar(64)   DEFAULT NULL                 COMMENT '联系人',
  `contact_phone` varchar(32)   DEFAULT NULL                 COMMENT '联系电话',
  `address`       varchar(255)  DEFAULT NULL                 COMMENT '地址',
  `bank_name`     varchar(128)  DEFAULT NULL                 COMMENT '开户行',
  `bank_account`  varchar(64)   DEFAULT NULL                 COMMENT '银行账号',
  `credit_limit`  decimal(14,2) DEFAULT NULL                 COMMENT '授信额度（非必填，可空）',
  `level`         varchar(8)    DEFAULT NULL                 COMMENT '供应商等级 A/B/C/D（可空）',
  `supply_scope`  varchar(255)  DEFAULT NULL                 COMMENT '供货范围（可空）',
  `payment_days`  int(11)       DEFAULT NULL                 COMMENT '账期天数（可空：0 与 NULL 需可区分，非负由应用层校验）',
  `enable_flag`   char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用',
  `remark`        varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_id`     varchar(64)   NOT NULL                     COMMENT '创建人用户ID（加 NOT NULL + FK，见 ⑥-3/⑥-4）',
  `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by`     varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`     varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`     varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_code` (`code`),
  UNIQUE KEY `uk_supplier_name` (`name`),
  KEY `idx_supplier_enable_flag` (`enable_flag`),
  KEY `idx_supplier_create_id` (`create_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商档案（B3 主数据）';

-- 商品类型树（自引用 FK：ON DELETE SET NULL，见 ⑥-4；≤5 级与叶子约束由应用层守卫）
CREATE TABLE `t_ctms_product_type` (
  `id`          varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `parent_id`   varchar(64)   DEFAULT NULL                 COMMENT '上级类型ID（自引用，可空=根；ON DELETE SET NULL）',
  `code`        varchar(32)   DEFAULT NULL                 COMMENT '类型编码（物料编码前缀来源；可空时退化 PT{类型id}）',
  `name`        varchar(64)   NOT NULL                     COMMENT '类型名称（同父下唯一由应用层校验）',
  `path`        varchar(255)  NOT NULL DEFAULT '/'         COMMENT '物化路径，形如 /1/5/（下级查询依赖 LIKE 前缀）',
  `level`       int(11)       NOT NULL DEFAULT 1           COMMENT '层级（最大 5 级由应用层校验）',
  `sort`        int(11)       NOT NULL DEFAULT 0           COMMENT '排序号',
  `enable_flag` char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用',
  `remark`      varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_time` datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`   varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（参考侧无创建人列，保持可空）',
  `create_by`   varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`   varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`   varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  KEY `idx_product_type_parent` (`parent_id`),
  KEY `idx_product_type_path` (`path`),
  KEY `idx_product_type_code` (`code`),
  KEY `idx_product_type_enable_flag` (`enable_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品类型树（B3 主数据，物化路径）';

-- 计量单位（小数位 0~4 由应用层校验，DB 层不建 CHECK）
CREATE TABLE `t_ctms_uom` (
  `id`          varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `code`        varchar(16)   NOT NULL                     COMMENT '单位编码（全局唯一，停用项也占号）',
  `name`        varchar(32)   NOT NULL                     COMMENT '单位名称',
  `decimals`    int(11)       NOT NULL DEFAULT 2           COMMENT '小数位（取值范围 0~4 由应用层校验）',
  `enable_flag` char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用',
  `remark`      varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_time` datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`   varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（参考侧无创建人列，保持可空）',
  `create_by`   varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`   varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`   varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uom_code` (`code`),
  KEY `idx_uom_enable_flag` (`enable_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计量单位（B3 主数据）';

-- 仓库档案（keeper_user_id 仅用于反查名称：**保持可空 + 仅加索引，不加 FK、不加 NOT NULL**）
CREATE TABLE `t_ctms_warehouse` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `code`           varchar(32)   NOT NULL                     COMMENT '仓库编码（全局唯一）',
  `name`           varchar(64)   NOT NULL                     COMMENT '仓库名称',
  `address`        varchar(255)  DEFAULT NULL                 COMMENT '仓库地址（可空）',
  `keeper_user_id` varchar(64)   DEFAULT NULL                 COMMENT '仓管员用户ID（仅反查名称：保持可空、仅加索引、不加 FK）',
  `enable_flag`    char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（参考侧无创建人列，保持可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouse_code` (`code`),
  KEY `idx_warehouse_enable_flag` (`enable_flag`),
  KEY `idx_warehouse_keeper` (`keeper_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库档案（B3 主数据）';

-- 物料档案（product_type_id / uom_id 参考侧本就非空 → 直接建为非空 + FK；create_id 收紧非空）
CREATE TABLE `t_ctms_product` (
  `id`              varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `code`            varchar(32)   NOT NULL                     COMMENT '物料编码（全局唯一；可自动生成「类型码+序号」或手工录入）',
  `name`            varchar(128)  NOT NULL                     COMMENT '物料名称',
  `spec`            varchar(128)  DEFAULT NULL                 COMMENT '规格（可空：行项名称/规格为空时回落本档案）',
  `product_type_id` varchar(64)   NOT NULL                     COMMENT '商品类型ID（参考侧本就非空，DB 层保证物料必挂类型）',
  `uom_id`          varchar(64)   NOT NULL                     COMMENT '计量单位ID（参考侧本就非空）',
  `brand`           varchar(64)   DEFAULT NULL                 COMMENT '品牌（可空）',
  `barcode`         varchar(64)   DEFAULT NULL                 COMMENT '条码（可空）',
  `default_price`   decimal(14,4) NOT NULL DEFAULT 0           COMMENT '默认单价（负数拒绝由应用层校验）',
  `safety_stock`    decimal(14,3) DEFAULT NULL                 COMMENT '安全库存（可空：「未设置」与「0」需可区分）',
  `enable_flag`     char(1)       NOT NULL DEFAULT '1'         COMMENT '启用标志：1-启用 0-停用（停用后历史单据仍展示名称快照）',
  `remark`          varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_id`       varchar(64)   NOT NULL                     COMMENT '创建人用户ID（加 NOT NULL + FK，见 ⑥-3/⑥-4）',
  `create_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by`       varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照',
  `update_id`       varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`       varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_code` (`code`),
  KEY `idx_product_type_id` (`product_type_id`),
  KEY `idx_product_uom_id` (`uom_id`),
  KEY `idx_product_enable_flag` (`enable_flag`),
  KEY `idx_product_create_id` (`create_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物料档案（B3 主数据，B4 只引用不重建）';

-- 历史甲乙方迁移草案（matched_id 多态 → 保持可空、仅加索引、不加 FK；uk_party_draft 为 Q12 裁决新增）
CREATE TABLE `t_ctms_party_draft` (
  `id`             varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `party_type`     varchar(16)   NOT NULL                     COMMENT '档案方向：customer-客户 supplier-供应商（状态枚举，不做 char(1) 映射）',
  `raw_name`       varchar(255)  NOT NULL                     COMMENT '合同甲/乙方原始文本（与 party_type 共同构成幂等键）',
  `contract_count` int(11)       NOT NULL DEFAULT 0           COMMENT '关联合同数量（重复扫描仅刷新计数）',
  `status`         varchar(16)   NOT NULL DEFAULT 'pending'   COMMENT '草案状态：pending-待认领 claimed-已认领 ignored-已忽略',
  `matched_id`     varchar(64)   DEFAULT NULL                 COMMENT '认领后的档案ID（多态：按 party_type 指向客户或供应商；保持可空、仅加索引）',
  `remark`         varchar(1000) DEFAULT NULL                 COMMENT '备注',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_id`      varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（扫描任务生成，可能无登录用户 → 保持可空）',
  `create_by`      varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（同上，保持可空）',
  `update_id`      varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID',
  `update_by`      varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_party_draft` (`party_type`,`raw_name`),
  KEY `idx_party_draft_type` (`party_type`),
  KEY `idx_party_draft_raw` (`raw_name`),
  KEY `idx_party_draft_status` (`status`),
  KEY `idx_party_draft_matched` (`matched_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史甲乙方迁移草案（B3）';


-- ---------- 2/13 合同主表与子表 ----------

-- 合同主表（自引用 parent_id：ON DELETE SET NULL；dept_id/create_id 收紧非空 + FK）
CREATE TABLE `t_ctms_contract` (
  `id`                    varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `contract_no`           varchar(64)   NOT NULL                     COMMENT '合同编号（唯一；停用占号不复用，靠软删除行仍占唯一索引实现）',
  `name`                  varchar(255)  NOT NULL                     COMMENT '合同名称',
  `type`                  varchar(16)   NOT NULL DEFAULT '采购'      COMMENT '合同类型（中文类型名，取值集合落 sys_dict_data.contract_types）',
  `party_a`               varchar(255)  NOT NULL DEFAULT ''          COMMENT '甲方文本（BR-V2-20：未绑定档案时以文本兜底展示，故不可空）',
  `party_b`               varchar(255)  NOT NULL DEFAULT ''          COMMENT '乙方文本（同上，镜像）',
  `sign_date`             date          DEFAULT NULL                 COMMENT '签订日期（**可空**：Q1 裁决以参考代码为准；编号月份码为空时取当天）',
  `effective_date`        date          DEFAULT NULL                 COMMENT '生效日期（可空，参考侧注释即「可选」）',
  `subject_matter`        longtext      NOT NULL                     COMMENT '标的物摘要（由行项覆盖落库，格式「名称(规格)×数量」分号连接）',
  `amount`                decimal(14,2) NOT NULL DEFAULT 0           COMMENT '合同金额（C-1 口径：已舍入行总价之和，禁 double）',
  `currency`              varchar(8)    NOT NULL DEFAULT 'CNY'       COMMENT '币种（仅记录不参与换算）',
  `subject_code`          varchar(8)    DEFAULT NULL                 COMMENT '我方主体码（**保持可空**：非编号类合同确无主体码，非空校验在接口层）',
  `customer_id`           varchar(64)   DEFAULT NULL                 COMMENT '客户档案ID（**保持可空 + 可空 FK**：仅销售方向填；引用不存在档案被 FK 拒绝）',
  `supplier_id`           varchar(64)   DEFAULT NULL                 COMMENT '供应商档案ID（**保持可空 + 可空 FK**：仅采购方向填）',
  `paid_amount`           decimal(14,2) NOT NULL DEFAULT 0           COMMENT '累计已付金额（「已付超出合同金额仍可保存」→ DB 不加 paid<=amount 约束）',
  `has_warranty`          char(1)       NOT NULL DEFAULT '0'         COMMENT '是否启用质保：0-否 1-是',
  `warranty_amount`       decimal(14,2) DEFAULT NULL                 COMMENT '质保金额（与 warranty_rate 二选一互换，可空）',
  `warranty_rate`         decimal(10,4) DEFAULT NULL                 COMMENT '质保比例%（与 warranty_amount 二选一，可空）',
  `warranty_start`        date          DEFAULT NULL                 COMMENT '质保起始日（可空：未启用质保时为空）',
  `warranty_months`       int(11)       DEFAULT NULL                 COMMENT '质保月数（可空）',
  `warranty_end`          date          DEFAULT NULL                 COMMENT '质保到期日（可空：关闭质保必须清空；提醒查询依赖「未释放且非空」）',
  `warranty_released`     char(1)       NOT NULL DEFAULT '0'         COMMENT '质保是否已释放：0-否 1-是（释放后不再提醒）',
  `warranty_release_date` date          DEFAULT NULL                 COMMENT '质保释放日期（可空：未释放时为空）',
  `warranty_note`         longtext      DEFAULT NULL                 COMMENT '质保备注（可空：关闭质保时清空的 7 个字段之一）',
  `is_framework`          char(1)       NOT NULL DEFAULT '0'         COMMENT '是否框架合同：0-否 1-是（框架树视图与绑定守卫按此列筛选）',
  `parent_id`             varchar(64)   DEFAULT NULL                 COMMENT '父框架合同ID（自引用，可空；ON DELETE SET NULL —— 沿用参考侧语义）',
  `arrival_status`        varchar(16)   NOT NULL DEFAULT '未到货'    COMMENT '到货状态（与进度状态互相独立、无联动）',
  `expected_arrival_date` date          DEFAULT NULL                 COMMENT '预计到货日期（可空，与到货状态配套）',
  `status`                varchar(32)   NOT NULL DEFAULT '内部审批中' COMMENT '进度状态（任意状态可互转、无顺序守卫，故 DB 不建枚举约束）',
  `owner_name`            varchar(64)   DEFAULT NULL                 COMMENT '经办人（自由文本非账号引用，可空）',
  `dept_id`               varchar(64)   NOT NULL                     COMMENT '所属部门ID（Q9：收紧非空 + FK；D-4 的 DEPT 数据范围必须命中该列）',
  `create_id`             varchar(64)   NOT NULL                     COMMENT '创建人用户ID（Q9：收紧非空 + FK；DEPT/SELF 数据范围与范围外 403 判定依赖它）',
  `remark`                varchar(1000) DEFAULT NULL                 COMMENT '备注（可空；参考侧为无界 Text，按勘察 §8.2 统一 varchar(1000)）',
  `del_flag`              char(1)       NOT NULL DEFAULT '0'         COMMENT '软删除标志：0-未删除 1-已停用（列表默认排除）',
  `deleted_at`            datetime      DEFAULT NULL                 COMMENT '停用时间（可空；30 天恢复窗口的整数日差判定基准）',
  `deleted_reason`        longtext      DEFAULT NULL                 COMMENT '停用原因（可空：「原因必填」是接口层强制，停用后清空行需容许为空）',
  `create_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by`             varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空：SQL 初始化/系统写入可能为空）',
  `update_id`             varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空，无追溯要求）',
  `update_by`             varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contract_no` (`contract_no`),
  KEY `idx_contract_customer` (`customer_id`),
  KEY `idx_contract_supplier` (`supplier_id`),
  KEY `idx_contract_parent` (`parent_id`),
  KEY `idx_contract_status` (`status`),
  KEY `idx_contract_arrival_status` (`arrival_status`),
  KEY `idx_contract_dept` (`dept_id`),
  KEY `idx_contract_create_id` (`create_id`),
  KEY `idx_contract_del_flag` (`del_flag`),
  KEY `idx_contract_sign_date` (`sign_date`),
  KEY `idx_contract_framework` (`is_framework`),
  KEY `idx_contract_subject_code` (`subject_code`),
  KEY `idx_contract_warranty` (`has_warranty`,`warranty_released`,`warranty_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同台账主表（B3）';

-- 合同行项（product_id/product_code/product_name 由 ⑥-2/⑥-3 收紧为非空；
-- product_id 另加 FK —— 这是「未选物料档案被数据库拒绝」的 DB 层落点）
CREATE TABLE `t_ctms_contract_item` (
  `id`           varchar(64)   NOT NULL                     COMMENT '主键（应用侧 UUID）',
  `contract_id`  varchar(64)   NOT NULL                     COMMENT '合同ID（非空 FK + 级联删除）',
  `seq`          int(11)       NOT NULL DEFAULT 1           COMMENT '序号（按提交顺序从 1 连续重排）',
  `item_type`    varchar(32)   NOT NULL DEFAULT '采购'      COMMENT '行项类型（取值集合落 sys_dict_data.item_types）',
  `name`         varchar(255)  NOT NULL DEFAULT ''          COMMENT '行项名称（为空时回落物料档案名称）',
  `spec`         varchar(255)  NOT NULL DEFAULT ''          COMMENT '行项规格（为空时回落物料档案规格）',
  `qty`          decimal(12,3) NOT NULL DEFAULT 0           COMMENT '数量（Q10 裁决沿用 decimal(12,3)，暂不与 B4 的 (16,3) 统一）',
  `unit_price`   decimal(14,4) NOT NULL DEFAULT 0           COMMENT '单价（scale=4；负数拒绝由应用层校验）',
  `total`        decimal(14,2) NOT NULL DEFAULT 0           COMMENT '行总价（先按 HALF_UP 舍入到 2 位再汇总）',
  `remark`       varchar(1000) DEFAULT NULL                 COMMENT '行备注（可空）',
  `product_id`   varchar(64)   DEFAULT NULL                 COMMENT '物料档案ID（**加 NOT NULL + FK**，见 ⑥-2/⑥-3：DB 层拒绝未选物料）',
  `product_code` varchar(32)   DEFAULT NULL                 COMMENT '物料编码快照（**加 NOT NULL**，与 product_id 同生同灭）',
  `product_name` varchar(128)  DEFAULT NULL                 COMMENT '物料名称快照（**加 NOT NULL**，同上）',
  `create_time`  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_id`    varchar(64)   DEFAULT NULL                 COMMENT '创建人用户ID（可空：行项随主表写入，不单独承担数据范围判定）',
  `create_by`    varchar(64)   DEFAULT NULL                 COMMENT '创建人登录名快照（可空）',
  `update_id`    varchar(64)   DEFAULT NULL                 COMMENT '更新人用户ID（可空：只改物料绑定的编辑也要留痕）',
  `update_by`    varchar(64)   DEFAULT NULL                 COMMENT '更新人登录名快照（可空）',
  `update_time`  datetime      DEFAULT NULL                 COMMENT '更新时间（可空：参考侧行项无 updated_at，不虚构收紧）',
  PRIMARY KEY (`id`),
  KEY `idx_contract_item_contract` (`contract_id`),
  KEY `idx_contract_item_product` (`product_id`),
  KEY `idx_contract_item_seq` (`contract_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同行项（B3）';

-- 合同↔标签关联（复合主键；两个 FK 均 ON DELETE CASCADE，沿用参考侧语义）
CREATE TABLE `t_ctms_contract_tag` (
  `contract_id` varchar(64) NOT NULL                 COMMENT '合同ID（复合主键之一，级联删除）',
  `tag_id`      varchar(64) NOT NULL                 COMMENT '标签ID（复合主键之一，级联删除；删标签会静默解除关联）',
  `auto`        char(1)     NOT NULL DEFAULT '0'     COMMENT '是否自动标签：0-否 1-是（「只清理自动项」的唯一依据）',
  `create_id`   varchar(64) DEFAULT NULL             COMMENT '创建人用户ID（可空：关联行由业务代码写入）',
  `create_by`   varchar(64) DEFAULT NULL             COMMENT '创建人登录名快照（可空）',
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（目标侧全局审计口径，非对参考侧收紧）',
  `update_id`   varchar(64) DEFAULT NULL             COMMENT '更新人用户ID（可空：关联行只插入/删除，无更新语义）',
  `update_by`   varchar(64) DEFAULT NULL             COMMENT '更新人登录名快照（可空）',
  `update_time` datetime    DEFAULT NULL             COMMENT '更新时间（可空：无更新语义，不加非空）',
  PRIMARY KEY (`contract_id`,`tag_id`),
  KEY `idx_contract_tag_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同与标签关联（B3，复合主键、无 id 列）';

-- 字段级变更历史（B3/B4 共用；object_type/object_id 由 ⑥-2/⑥-3 收紧为非空，多态故不加 FK）
CREATE TABLE `t_ctms_change_log` (
  `id`            varchar(64)  NOT NULL                 COMMENT '主键（应用侧 UUID）',
  `contract_id`   varchar(64)  DEFAULT NULL             COMMENT '合同ID（**保持可空**：B4 单据侧日志不写该列，靠 object_type/object_id 定位）',
  `field_name`    varchar(64)  NOT NULL                 COMMENT '字段名（含特殊名 _summary/_items/_tags/_origin/status/附件/deleted，均≤64）',
  `old_value`     longtext     DEFAULT NULL             COMMENT '旧值（可空：新增场景旧值为空）',
  `new_value`     longtext     DEFAULT NULL             COMMENT '新值（可空：删除/清空场景新值为空）',
  `note`          longtext     DEFAULT NULL             COMMENT '备注/原因（可空）',
  `source`        varchar(16)  NOT NULL DEFAULT 'manual' COMMENT '来源：manual-手动 auto-自动',
  `operator_id`   varchar(64)  DEFAULT NULL             COMMENT '操作人用户ID（**保持可空**：Q2 裁决，历史数据操作人为空不得报错）',
  `operator_name` varchar(64)  DEFAULT NULL             COMMENT '操作人姓名快照（可空：历史行为空时前端展示占位符「—」）',
  `object_type`   varchar(32)  DEFAULT NULL             COMMENT '对象类型（**加 NOT NULL**，见 ⑥-2/⑥-3；多态 → 只建复合索引，不加 FK）',
  `object_id`     varchar(64)  DEFAULT NULL             COMMENT '对象标识（**加 NOT NULL**，与 object_type 成对；多态故不加 FK）',
  `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（时间线按此列倒序）',
  `create_id`     varchar(64)  DEFAULT NULL             COMMENT '创建人用户ID（可空：操作人语义已由 operator_id/operator_name 承载）',
  `create_by`     varchar(64)  DEFAULT NULL             COMMENT '创建人登录名快照（可空）',
  `update_id`     varchar(64)  DEFAULT NULL             COMMENT '更新人用户ID（可空：变更历史只追加不更新）',
  `update_by`     varchar(64)  DEFAULT NULL             COMMENT '更新人登录名快照（可空）',
  `update_time`   datetime     DEFAULT NULL             COMMENT '更新时间（可空：只追加不更新）',
  PRIMARY KEY (`id`),
  KEY `idx_change_log_contract` (`contract_id`),
  KEY `idx_object` (`object_type`,`object_id`),
  KEY `idx_change_log_operator` (`operator_id`),
  KEY `idx_change_log_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字段级变更历史（B3/B4 共用）';

-- 附件挂载元数据（多态 object_type/object_id：加 NOT NULL + 复合索引，**不加多态 FK**）
CREATE TABLE `t_ctms_attachment` (
  `id`           varchar(64)  NOT NULL                 COMMENT '主键（应用侧 UUID）',
  `contract_id`  varchar(64)  DEFAULT NULL             COMMENT '合同ID（**保持可空**：Q5 裁决依据 REQ-DATA-003 保留该列；单据附件为 NULL）',
  `file_name`    varchar(255) NOT NULL                 COMMENT '原始文件名（白名单后缀校验的对象）',
  `stored_path`  varchar(512) NOT NULL                 COMMENT '存储相对路径（ruoyi-file 返回的 /profile/upload/... 相对路径）',
  `content_type` varchar(128) DEFAULT NULL             COMMENT '内容类型（可空）',
  `size_bytes`   int(11)      NOT NULL DEFAULT 0       COMMENT '字节数（20MB 上限下 int 足够）',
  `del_flag`     char(1)      NOT NULL DEFAULT '0'     COMMENT '软删除标志：0-未删除 1-已删除（删除后下载接口不再返回）',
  `object_type`  varchar(32)  DEFAULT NULL             COMMENT '对象类型（**加 NOT NULL**，见 ⑥-2/⑥-3；无对象类型无法鉴权）',
  `object_id`    varchar(64)  DEFAULT NULL             COMMENT '对象标识（**加 NOT NULL**，与 object_type 成对；多态故只建复合索引）',
  `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间（映射参考侧 uploaded_at；元数据行创建后不更新）',
  `create_id`    varchar(64)  DEFAULT NULL             COMMENT '上传人用户ID（可空：上传路径多，含 B4 单据附件）',
  `create_by`    varchar(64)  DEFAULT NULL             COMMENT '上传人登录名快照（可空，双写口径）',
  `update_id`    varchar(64)  DEFAULT NULL             COMMENT '更新人用户ID（可空：附件元数据无更新语义，删除用 del_flag）',
  `update_by`    varchar(64)  DEFAULT NULL             COMMENT '更新人登录名快照（可空）',
  `update_time`  datetime     DEFAULT NULL             COMMENT '更新时间（可空：同上）',
  PRIMARY KEY (`id`),
  KEY `idx_object` (`object_type`,`object_id`),
  KEY `idx_attachment_contract` (`contract_id`),
  KEY `idx_attachment_del_flag` (`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='附件挂载元数据（B3，多态对象）';


-- ############################################################################
-- ⑥-2 约束回补 · 第①段：回填（先把 NULL 回填成合理值，再加 NOT NULL）
-- ############################################################################
-- 依据：ddl-review.md §1.1/§1.2/§1.5/§1.6/§1.8 与 §4.1 裁决（Q3/Q9）。
-- 回填语义逐条来自参考侧既有先例：
--   * 附件对象回落：参考侧读接口 `att.object_type or "contract"`（attachments.py:181,257）
--     → 对象类型缺失时归到 'contract'，对象标识取 contract_id（§1.6）。
--   * 变更历史对象回落：参考侧 `db_migrate.py:102-106` 的现成口径
--     → `UPDATE change_logs SET object_type='contract', object_id=contract_id
--        WHERE object_type IS NULL AND contract_id IS NOT NULL`（§1.5、§4.1-Q2）。
--   * 创建人/部门：**不瞎编** —— 以基线数据里的 `superAdmin` 账号与根部门兜底（Q9 要求「体检先行」，
--     真实迁移应先由体检脚本清理违例；此处只保证空库/基线库可执行）。
--   * `t_ctms_supplier.short_name`：以自身 name 兜底（§1.8 迁移场景「简称以原始名称兜底」）。
-- 本段为纯 UPDATE，可重复执行。

-- ①-1 t_ctms_attachment：对象类型/对象标识
UPDATE `t_ctms_attachment`
   SET `object_type` = 'contract'
 WHERE `object_type` IS NULL AND `contract_id` IS NOT NULL;

UPDATE `t_ctms_attachment`
   SET `object_id` = `contract_id`
 WHERE `object_id` IS NULL AND `contract_id` IS NOT NULL;

-- ①-2 t_ctms_change_log：对象类型/对象标识（口径同参考侧 db_migrate.py:102-106）
UPDATE `t_ctms_change_log`
   SET `object_type` = 'contract'
 WHERE `object_type` IS NULL AND `contract_id` IS NOT NULL;

UPDATE `t_ctms_change_log`
   SET `object_id` = `contract_id`
 WHERE `object_id` IS NULL AND `contract_id` IS NOT NULL;

-- ①-3 t_ctms_contract：所属部门/创建人（二者要收紧非空）
UPDATE `t_ctms_contract`
   SET `dept_id` = (SELECT MIN(`dept_id`) FROM `sys_dept`)
 WHERE `dept_id` IS NULL;

UPDATE `t_ctms_contract`
   SET `create_id` = (SELECT MIN(`user_id`) FROM `sys_user` WHERE `user_name` = 'superAdmin')
 WHERE `create_id` IS NULL;

-- ①-4 主数据档案：创建人（customer / supplier / product 三表要收紧非空）
UPDATE `t_ctms_customer`
   SET `create_id` = (SELECT MIN(`user_id`) FROM `sys_user` WHERE `user_name` = 'superAdmin')
 WHERE `create_id` IS NULL;

UPDATE `t_ctms_supplier`
   SET `create_id` = (SELECT MIN(`user_id`) FROM `sys_user` WHERE `user_name` = 'superAdmin')
 WHERE `create_id` IS NULL;

UPDATE `t_ctms_product`
   SET `create_id` = (SELECT MIN(`user_id`) FROM `sys_user` WHERE `user_name` = 'superAdmin')
 WHERE `create_id` IS NULL;

-- ①-5 t_ctms_supplier：简称以原始名称兜底（认领新建时不得留空）
UPDATE `t_ctms_supplier`
   SET `short_name` = `name`
 WHERE `short_name` IS NULL;

-- ①-6 上面「按对象回落」之后仍为 NULL 的行（既无对象类型也无合同引用）：
--      无法判定归属，属体检应阻断的脏数据；此处**不静默删行**，回落为空串（可审计、不丢数据）。
UPDATE `t_ctms_attachment`
   SET `object_type` = ''
 WHERE `object_type` IS NULL;

UPDATE `t_ctms_attachment`
   SET `object_id` = `contract_id`
 WHERE `object_id` IS NULL AND `contract_id` IS NOT NULL;

UPDATE `t_ctms_attachment`
   SET `object_id` = ''
 WHERE `object_id` IS NULL;

UPDATE `t_ctms_change_log`
   SET `object_type` = ''
 WHERE `object_type` IS NULL;

UPDATE `t_ctms_change_log`
   SET `object_id` = `contract_id`
 WHERE `object_id` IS NULL AND `contract_id` IS NOT NULL;

UPDATE `t_ctms_change_log`
   SET `object_id` = ''
 WHERE `object_id` IS NULL;

-- ①-7 t_ctms_contract_item：物料三列
--      优先按物料编码快照回找档案；回找不成功则保持 NULL —— 加 NOT NULL 会失败并报 1048，
--      **这是刻意的**：行动项缺物料属体检违例，不静默编造绑定。
UPDATE `t_ctms_contract_item` i
   SET i.`product_id` = (SELECT MIN(p.`id`) FROM `t_ctms_product` p WHERE p.`code` = i.`product_code`)
 WHERE i.`product_id` IS NULL AND i.`product_code` IS NOT NULL;

UPDATE `t_ctms_contract_item`
   SET `product_code` = ''
 WHERE `product_code` IS NULL;

UPDATE `t_ctms_contract_item`
   SET `product_name` = `name`
 WHERE `product_name` IS NULL;

-- ①-8 空库执行时 13 张表均为空，本段不产生任何行（幂等、无副作用）。


-- ############################################################################
-- ⑥-3 约束回补 · 第②段：加 NOT NULL（information_schema 守卫 + PREPARE/EXECUTE）
-- ############################################################################
-- MySQL 8 不支持 `MODIFY COLUMN IF NOT EXISTS`，故用「列当前是否可空」做守卫，重复执行不报错。
-- ⚠ 本段只收紧评审表判定为「加 NOT NULL」的列；标注「保持可空、仅加索引」的列
--   （t_ctms_warehouse.keeper_user_id、t_ctms_party_draft.matched_id、
--    t_ctms_change_log.operator_id/operator_name、t_ctms_contract.customer_id/supplier_id）
--   **既不在此处收紧，也不在 ⑥-4 加 FK**。

-- ---------- ②-0 汇总自检：待收紧列当前的 NULL 行数（全部应为 0，否则立即人工处理） ----------
SELECT '②-0 t_ctms_attachment.object_type 的 NULL 行数（应为0）' AS `检查项`, COUNT(*) AS `结果`
  FROM `t_ctms_attachment` WHERE `object_type` IS NULL
UNION ALL SELECT '②-0 t_ctms_attachment.object_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_attachment` WHERE `object_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_change_log.object_type 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_change_log` WHERE `object_type` IS NULL
UNION ALL SELECT '②-0 t_ctms_change_log.object_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_change_log` WHERE `object_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_contract.dept_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_contract` WHERE `dept_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_contract.create_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_contract` WHERE `create_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_contract_item.product_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_contract_item` WHERE `product_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_contract_item.product_code 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_contract_item` WHERE `product_code` IS NULL
UNION ALL SELECT '②-0 t_ctms_contract_item.product_name 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_contract_item` WHERE `product_name` IS NULL
UNION ALL SELECT '②-0 t_ctms_supplier.short_name 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_supplier` WHERE `short_name` IS NULL
UNION ALL SELECT '②-0 t_ctms_customer.create_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_customer` WHERE `create_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_supplier.create_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_supplier` WHERE `create_id` IS NULL
UNION ALL SELECT '②-0 t_ctms_product.create_id 的 NULL 行数（应为0）', COUNT(*) FROM `t_ctms_product` WHERE `create_id` IS NULL;

-- ---------- ②-1 逐列守卫（共 13 列） ----------
-- 每一列的 @ddl 取值：
--   * 该列存在且当前可空         → 完整的 MODIFY 语句
--   * 该列不存在（NULL）或已非空 → NULL
-- 再用 `IF(@ddl IS NOT NULL, @ddl, 'SELECT ... AS 提示')` 统一成「一条语句」交给 PREPARE。
-- ⚠ 不用 GROUP_CONCAT 把多条 ALTER 拼成一条：其默认上限 1024 字符，
--   本段 13 条 MODIFY 拼起来会被**静默截断**（后半段 DDL 丢失且不报错）。

-- t_ctms_attachment.object_type
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_attachment` MODIFY COLUMN `object_type` varchar(32) NOT NULL COMMENT '对象类型（加 NOT NULL：无对象类型无法鉴权）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_type');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_attachment.object_type 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_attachment.object_id
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_attachment` MODIFY COLUMN `object_id` varchar(64) NOT NULL COMMENT '对象标识（加 NOT NULL，与 object_type 成对）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment' AND COLUMN_NAME = 'object_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_attachment.object_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_change_log.object_type
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_change_log` MODIFY COLUMN `object_type` varchar(32) NOT NULL COMMENT '对象类型（加 NOT NULL：每条日志都必须能定位对象；多态故不加 FK）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_type');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_change_log.object_type 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_change_log.object_id
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_change_log` MODIFY COLUMN `object_id` varchar(64) NOT NULL COMMENT '对象标识（加 NOT NULL，与 object_type 成对；多态故不加 FK）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log' AND COLUMN_NAME = 'object_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_change_log.object_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_contract.dept_id（Q9：收紧非空）
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_contract` MODIFY COLUMN `dept_id` varchar(64) NOT NULL COMMENT '所属部门ID（Q9 收紧非空 + FK；DEPT 数据范围必须命中该列）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'dept_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_contract.dept_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_contract.create_id（Q9：收紧非空）
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_contract` MODIFY COLUMN `create_id` varchar(64) NOT NULL COMMENT '创建人用户ID（Q9 收紧非空 + FK；DEPT/SELF 数据范围与范围外 403 判定依赖它）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME = 'create_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_contract.create_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_contract_item.product_id（清单 §2.8 #1：DB 层拒绝未选物料）
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_contract_item` MODIFY COLUMN `product_id` varchar(64) NOT NULL COMMENT '物料档案ID（加 NOT NULL + FK：DB 层拒绝「行项未选物料档案」）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_contract_item.product_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_contract_item.product_code
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_contract_item` MODIFY COLUMN `product_code` varchar(32) NOT NULL COMMENT '物料编码快照（加 NOT NULL：与 product_id 同生同灭）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_code');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_contract_item.product_code 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_contract_item.product_name
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_contract_item` MODIFY COLUMN `product_name` varchar(128) NOT NULL COMMENT '物料名称快照（加 NOT NULL：与 product_id 同生同灭）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME = 'product_name');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_contract_item.product_name 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_supplier.short_name（清单 §2.8 #16 / BR-V2.2-01：供应商简称必填）
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_supplier` MODIFY COLUMN `short_name` varchar(64) NOT NULL COMMENT '供应商简称（加 NOT NULL：规格「供应商简称为必填」）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'short_name');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_supplier.short_name 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_customer.create_id（清单 §2.8 #15）
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_customer` MODIFY COLUMN `create_id` varchar(64) NOT NULL COMMENT '创建人用户ID（加 NOT NULL + FK：清单 §2.8 #15）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer' AND COLUMN_NAME = 'create_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_customer.create_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_supplier.create_id
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_supplier` MODIFY COLUMN `create_id` varchar(64) NOT NULL COMMENT '创建人用户ID（加 NOT NULL + FK：清单 §2.8 #15）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier' AND COLUMN_NAME = 'create_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_supplier.create_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_ctms_product.create_id
SET @ddl = (SELECT IF(IS_NULLABLE = 'YES',
    "ALTER TABLE `t_ctms_product` MODIFY COLUMN `create_id` varchar(64) NOT NULL COMMENT '创建人用户ID（加 NOT NULL + FK：清单 §2.8 #15）'", NULL)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product' AND COLUMN_NAME = 'create_id');
SET @ddl = IF(@ddl IS NULL, 'SELECT ''skip: t_ctms_product.create_id 已非空或不存在'' AS `提示`', @ddl);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ############################################################################
-- ⑥-4 约束回补 · 第③段：加外键与唯一键（information_schema 守卫 + PREPARE/EXECUTE）
-- ############################################################################
-- ON DELETE 行为与理由（逐条对齐评审表 §1.x 的结论）：
--   * 行项 / 标签关联            → CASCADE ：关联行随主行消亡（参考侧 models.py:93-94、183 原样语义）
--   * 变更历史 / 附件 → 合同      → CASCADE ：沿用参考侧（合同是软删除，级联实际不触发）
--   * 合同 → 父框架合同           → SET NULL：参考侧 models.py:128-130 原样（父框架删除后子合同留在池里）
--   * 商品类型 → 自身父类型       → SET NULL：参考侧 models_master.py:105-107 原样
--   * 合同 → 客户/供应商档案       → 不写（RESTRICT）：档案有引用时禁止删除；两列均可空，方向映射决定只有一个有值
--   * 合同 → 部门(sys_dept)       → 不写（RESTRICT）：部门被合同引用时不得被物理删除
--   * 创建人 → sys_user           → 不写（RESTRICT）：创建人不可被物理删除（sys_user 的删除实为逻辑删除）
--   * 物料 → 商品类型 / 计量单位   → 不写（RESTRICT）：被物料引用的类型/单位禁止删除（tasks.md 3.3）
--   * 多态列（change_log/attachment 的 object_type+object_id）→ **不加 FK**，只建复合索引：
--     对象类型跨 B3/B4 多张表，加不了单一 FK（design D-5）。
-- 幂等：MySQL 的 FK 约束名是 **schema 级唯一**，故逐条查 TABLE_CONSTRAINTS 守卫，重复执行只跳过。

-- ---------- ③-1 t_ctms_contract（5 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
                  AND CONSTRAINT_NAME = 'fk_contract_customer' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract` ADD CONSTRAINT `fk_contract_customer` FOREIGN KEY (`customer_id`) REFERENCES `t_ctms_customer` (`id`)',
  'SELECT ''skip: fk_contract_customer 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
                  AND CONSTRAINT_NAME = 'fk_contract_supplier' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract` ADD CONSTRAINT `fk_contract_supplier` FOREIGN KEY (`supplier_id`) REFERENCES `t_ctms_supplier` (`id`)',
  'SELECT ''skip: fk_contract_supplier 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
                  AND CONSTRAINT_NAME = 'fk_contract_parent' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract` ADD CONSTRAINT `fk_contract_parent` FOREIGN KEY (`parent_id`) REFERENCES `t_ctms_contract` (`id`) ON DELETE SET NULL',
  'SELECT ''skip: fk_contract_parent 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
                  AND CONSTRAINT_NAME = 'fk_contract_dept' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract` ADD CONSTRAINT `fk_contract_dept` FOREIGN KEY (`dept_id`) REFERENCES `sys_dept` (`dept_id`)',
  'SELECT ''skip: fk_contract_dept 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
                  AND CONSTRAINT_NAME = 'fk_contract_create_id' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract` ADD CONSTRAINT `fk_contract_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)',
  'SELECT ''skip: fk_contract_create_id 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-2 t_ctms_contract_item（2 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item'
                  AND CONSTRAINT_NAME = 'fk_contract_item_contract' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract_item` ADD CONSTRAINT `fk_contract_item_contract` FOREIGN KEY (`contract_id`) REFERENCES `t_ctms_contract` (`id`) ON DELETE CASCADE',
  'SELECT ''skip: fk_contract_item_contract 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item'
                  AND CONSTRAINT_NAME = 'fk_contract_item_product' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract_item` ADD CONSTRAINT `fk_contract_item_product` FOREIGN KEY (`product_id`) REFERENCES `t_ctms_product` (`id`)',
  'SELECT ''skip: fk_contract_item_product 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-3 t_ctms_contract_tag（2 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_tag'
                  AND CONSTRAINT_NAME = 'fk_contract_tag_contract' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract_tag` ADD CONSTRAINT `fk_contract_tag_contract` FOREIGN KEY (`contract_id`) REFERENCES `t_ctms_contract` (`id`) ON DELETE CASCADE',
  'SELECT ''skip: fk_contract_tag_contract 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_tag'
                  AND CONSTRAINT_NAME = 'fk_contract_tag_tag' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_contract_tag` ADD CONSTRAINT `fk_contract_tag_tag` FOREIGN KEY (`tag_id`) REFERENCES `t_ctms_tag` (`id`) ON DELETE CASCADE',
  'SELECT ''skip: fk_contract_tag_tag 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-4 t_ctms_change_log（1 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log'
                  AND CONSTRAINT_NAME = 'fk_change_log_contract' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_change_log` ADD CONSTRAINT `fk_change_log_contract` FOREIGN KEY (`contract_id`) REFERENCES `t_ctms_contract` (`id`) ON DELETE CASCADE',
  'SELECT ''skip: fk_change_log_contract 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-5 t_ctms_attachment（1 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment'
                  AND CONSTRAINT_NAME = 'fk_attachment_contract' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_attachment` ADD CONSTRAINT `fk_attachment_contract` FOREIGN KEY (`contract_id`) REFERENCES `t_ctms_contract` (`id`) ON DELETE CASCADE',
  'SELECT ''skip: fk_attachment_contract 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-6 t_ctms_customer / t_ctms_supplier（各 1 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer'
                  AND CONSTRAINT_NAME = 'fk_customer_create_id' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_customer` ADD CONSTRAINT `fk_customer_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)',
  'SELECT ''skip: fk_customer_create_id 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier'
                  AND CONSTRAINT_NAME = 'fk_supplier_create_id' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_supplier` ADD CONSTRAINT `fk_supplier_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)',
  'SELECT ''skip: fk_supplier_create_id 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-7 t_ctms_product_type / t_ctms_product（1 + 3 条） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product_type'
                  AND CONSTRAINT_NAME = 'fk_product_type_parent' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_product_type` ADD CONSTRAINT `fk_product_type_parent` FOREIGN KEY (`parent_id`) REFERENCES `t_ctms_product_type` (`id`) ON DELETE SET NULL',
  'SELECT ''skip: fk_product_type_parent 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product'
                  AND CONSTRAINT_NAME = 'fk_product_product_type' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_product` ADD CONSTRAINT `fk_product_product_type` FOREIGN KEY (`product_type_id`) REFERENCES `t_ctms_product_type` (`id`)',
  'SELECT ''skip: fk_product_product_type 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product'
                  AND CONSTRAINT_NAME = 'fk_product_uom' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_product` ADD CONSTRAINT `fk_product_uom` FOREIGN KEY (`uom_id`) REFERENCES `t_ctms_uom` (`id`)',
  'SELECT ''skip: fk_product_uom 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product'
                  AND CONSTRAINT_NAME = 'fk_product_create_id' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0,
  'ALTER TABLE `t_ctms_product` ADD CONSTRAINT `fk_product_create_id` FOREIGN KEY (`create_id`) REFERENCES `sys_user` (`user_id`)',
  'SELECT ''skip: fk_product_create_id 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ③-8 唯一键回补（Q12 裁决：t_ctms_party_draft 新增 (party_type, raw_name) 唯一） ----------
-- 该唯一键已在建表段写入；此处按「约束回补三段」的守卫口径再兜一次（缺则补、有则跳过），
-- 使「回滚脚本按名字删 → 正向脚本按名字补」这一对能闭合。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_party_draft'
                  AND INDEX_NAME = 'uk_party_draft') = 0,
  'ALTER TABLE `t_ctms_party_draft` ADD UNIQUE KEY `uk_party_draft` (`party_type`,`raw_name`)',
  'SELECT ''skip: uk_party_draft 已存在'' AS `提示`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ############################################################################
-- ⑥-5 B3 编号配置 —— **已迁出本文件**
-- ############################################################################
-- 2026-10-05 调整（避免"同一件事两处配置"）：
--   合同编号配置原写在本段，现**唯一真源**是 `sql/二开-合同台账-编号配置.sql`
--   （配置 id = `9F2C0000000000000000000000C001`，标题「合同编号配置」）。
--   迁出理由：
--     ① 编排契约（notes/ddl-scope.md §6）里 B3 是**四个文件**，编号配置有自己的文件；
--     ② 本文件是"表结构 + 约束回补"，混入编号配置会让"结构与种子"边界模糊；
--     ③ 两处都写会出现**两条同义配置**（实测：编号配置变成 2 条），
--        调用方按 id 取号时无法确定用哪条，回滚也只能删掉其中一条。
--   ⚠ 取号用的 confId 是 `9F2C0000000000000000000000C001`（合同台账服务把它配在 `sys_config`
--     或常量里）；编号格式与规则见该文件头注释与
--     `notes/numbering-verification.md`（§1.3 扩展：业务参数分桶 + 惰性跨年重置 + 预览不占号）。


-- ############################################################################
-- ⑦ 编排自检（每行 = 中文检查项名 + 期望值；照抄 B1/B2 脚本风格）
-- ############################################################################
-- 判读方式：`结果` 列必须等于「检查项」括号里的期望值。

-- ⑦-1 13 张表齐备 + 每张表列数与 ddl-review.md 附录一致（合计应为 224）
SELECT '① 13 张表齐备（应为13）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse',
                      't_ctms_product')
UNION ALL SELECT '② t_ctms_contract 列数（应为41）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract'
UNION ALL SELECT '③ t_ctms_contract_item 列数（应为19）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_item'
UNION ALL SELECT '④ t_ctms_tag 列数（应为10）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_tag'
UNION ALL SELECT '⑤ t_ctms_contract_tag 列数（应为9，复合主键无 id）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_tag'
UNION ALL SELECT '⑥ t_ctms_change_log 列数（应为17）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_change_log'
UNION ALL SELECT '⑦ t_ctms_attachment 列数（应为15）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_attachment'
UNION ALL SELECT '⑧ t_ctms_customer 列数（应为20）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_customer'
UNION ALL SELECT '⑨ t_ctms_supplier 列数（应为22）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_supplier'
UNION ALL SELECT '⑩ t_ctms_party_draft 列数（应为13）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_party_draft'
UNION ALL SELECT '⑪ t_ctms_product_type 列数（应为15）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product_type'
UNION ALL SELECT '⑫ t_ctms_uom 列数（应为12）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_uom'
UNION ALL SELECT '⑬ t_ctms_warehouse 列数（应为13）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_warehouse'
UNION ALL SELECT '⑭ t_ctms_product 列数（应为18）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_product'
UNION ALL SELECT '⑮ 13 张表合计列数（应为224，= ddl-review.md 附录合计）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse',
                      't_ctms_product');

-- ⑦-2 被约束列的可空性（IS_NULLABLE 必须为 NO；未列入的列保持可空，不在此断言）
SELECT '⑯ 收紧为非空的列齐备（应为13）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND IS_NULLABLE = 'NO'
   AND ((TABLE_NAME = 't_ctms_attachment'    AND COLUMN_NAME IN ('object_type','object_id'))
     OR (TABLE_NAME = 't_ctms_change_log'    AND COLUMN_NAME IN ('object_type','object_id'))
     OR (TABLE_NAME = 't_ctms_contract_item' AND COLUMN_NAME IN ('product_id','product_code','product_name'))
     OR (TABLE_NAME = 't_ctms_contract'      AND COLUMN_NAME IN ('dept_id','create_id'))
     OR (TABLE_NAME = 't_ctms_customer'      AND COLUMN_NAME = 'create_id')
     OR (TABLE_NAME = 't_ctms_supplier'      AND COLUMN_NAME IN ('create_id','short_name'))
     OR (TABLE_NAME = 't_ctms_product'       AND COLUMN_NAME = 'create_id'))
UNION ALL SELECT '⑰ 仅反查名称的引用列仍可空（应为4：warehouse.keeper_user_id、party_draft.matched_id、change_log.operator_id/operator_name）',
       COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND IS_NULLABLE = 'YES'
   AND ((TABLE_NAME = 't_ctms_warehouse'  AND COLUMN_NAME = 'keeper_user_id')
     OR (TABLE_NAME = 't_ctms_party_draft' AND COLUMN_NAME = 'matched_id')
     OR (TABLE_NAME = 't_ctms_change_log'  AND COLUMN_NAME IN ('operator_id','operator_name')))
UNION ALL SELECT '⑱ 合同档案引用（客户/供应商）仍可空（应为2）', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND IS_NULLABLE = 'YES'
   AND TABLE_NAME = 't_ctms_contract' AND COLUMN_NAME IN ('customer_id','supplier_id');

-- ⑦-3 外键数量与清单（应为 14 条）
SELECT '⑲ 外键数量（应为14）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.TABLE_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY'
   AND TABLE_NAME LIKE 't\_ctms\_%'
UNION ALL SELECT '⑳ 外键里指向 sys_user / sys_dept 的条数（应为5）', COUNT(*)
  FROM information_schema.KEY_COLUMN_USAGE
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\_ctms\_%'
   AND REFERENCED_TABLE_NAME IN ('sys_user','sys_dept')
UNION ALL SELECT '㉑ ON DELETE CASCADE 的外键条数（应为6：行项1+标签关联2+变更历史1+附件1+合同标签…见 ⑥-4 清单）', COUNT(*)
  FROM information_schema.REFERENTIAL_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\_ctms\_%' AND DELETE_RULE = 'CASCADE'
UNION ALL SELECT '㉒ ON DELETE SET NULL 的外键条数（应为2：合同→父框架、商品类型→父类型）', COUNT(*)
  FROM information_schema.REFERENTIAL_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\_ctms\_%' AND DELETE_RULE = 'SET NULL';

-- ⑦-4 唯一索引齐备（应 10 个：contract_no、tag_name、customer_code、customer_name、
--      supplier_code、supplier_name、uom_code、warehouse_code、product_code、party_draft）
SELECT '㉓ 唯一索引数量（应为10）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\_ctms\_%' AND NON_UNIQUE = 0
   AND INDEX_NAME <> 'PRIMARY';

-- ⑦-5 关键复合索引、复合主键与排序规则
SELECT '㉔ (object_type, object_id) 复合索引列数齐备（应为4：change_log/attachment 各2列）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\_ctms\_%'
   AND INDEX_NAME = 'idx_object' AND COLUMN_NAME IN ('object_type','object_id')
UNION ALL SELECT '㉕ (contract_id, tag_id) 复合主键列数（应为2）', COUNT(*)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract_tag'
   AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME IN ('contract_id','tag_id')
UNION ALL SELECT '㉖ 质保提醒复合索引列数（应为3）', COUNT(*)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ctms_contract' AND INDEX_NAME = 'idx_contract_warranty'
UNION ALL SELECT '㉗ 13 张表排序规则均为 utf8mb4_0900_ai_ci（应为13）', COUNT(*)
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_COLLATION = 'utf8mb4_0900_ai_ci'
   AND TABLE_NAME IN ('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                      't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                      't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse',
                      't_ctms_product');

-- ⑦-6 编号配置齐备 —— 已迁至 `二开-合同台账-编号配置.sql`，本文件不再断言
--   （取号配置的 confId = 9F2C0000000000000000000000C001；只跑本文件时该配置不存在是**正常的**，
--    所以这里不断言，避免"单独跑建表脚本"被误判为失败。整包验收请跑 初始化-全部.sql 的编排自检。）

-- ⑦-7 上游基线未被本文件改动（列数应与上游 table.sql 定义一致）
SELECT '㉜ sys_user 列数（上游基线，应为18）' AS `检查项`, COUNT(*) AS `结果`
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
UNION ALL SELECT '㉝ sys_dept 列数（上游基线，应为18）', COUNT(*)
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_dept';

-- ============================================================================
-- 列数对照（本文件 vs ddl-review.md 附录；写文件时已逐表核对）
--   contract 41 / contract_item 19 / tag 10 / contract_tag 9 / change_log 17 / attachment 15 /
--   customer 20 / supplier 22 / party_draft 13 / product_type 15 / uom 12 / warehouse 13 /
--   product 18  →  合计 **224**（与评审表附录完全一致；评审表 §1.1 的字段行数量亦为 41）。
--   实现期自查说明：`ddl-review.md` §1.1 的审计列说明里同时出现了 `create_time`/`update_time`
--   （已在字段表中列出）与追加列清单，初读容易少数或多算 1 列；此处以「§1.1 字段表的行数」
--   为唯一口径（41），第 ⑦-1 段的 `②` 检查项即为该结论的直接验证。
-- ============================================================================
