# 第 7 组（变更历史与迁移工具）独立质量门禁复核 —— t16 / round 1

> 复核人：reviewer（独立复核，未修改任何实现代码）
> 复核时间：2026-10-05 15:20~15:45（本机 Windows PowerShell 5.1，无 pwsh）
> 被审对象：`specs/ctms/contract-migration/spec.md` 的 6 个 Requirement + `tasks.md` 第 7 组 6 项任务
> 被审任务：t1（7.1）/ t2（7.2）/ t22（7.3）/ t5（7.4）/ t3（7.5）/ t25（7.6b）
> 证据形态：**全部由本轮亲自复跑/亲自查库产生**；被审者的自述只用来定位"该验什么"，不作为结论依据。

## 0. 结论

**verdict = needs_revision（不允许以"大致符合"放行）**

- 规格 6 个 Requirement 的**主链路全部可复现通过**（`mvn` 全量 208 passed；`node tools/audit/run-all.js` 10 审计 0 失败；
  7.5 脚本两条路径退出码 29/0→3 与 32/0→0 与交付记录一致；真库端到端 38 条断言全绿）。
- 但有 **1 条 medium 缺陷**在真库上稳定复现（F1）：**扫描（Java trim 归一）与认领（SQL 精确 `=`）对"同一文本"的口径不一致**，
  导致带首尾空白的合同被计入草案计数却永远绑不上，且**认领返回成功却实绑 0**、草案在 `claimed`↔`pending` 间反复、永无收敛。
- 另有 2 条 low（记录/证据强度）见 §7。

---

## 1. 复核口径（可照抄复现）

| 项 | 值 / 命令 |
| --- | --- |
| 后端 | 8080 有监听；`ruoyi-admin\target\ruoyi-admin.jar` mtime **2026-10-05 15:13:30**、size 184236121（与 t25 自证一致） |
| token | `powershell -File tools\oa-login.ps1 -Users superAdmin`（15:22 重新登录，`.cache\token-superAdmin.txt`） |
| 库 | MySQL 8.0.40 便携版，`rad_oa`（业务库）+ 一次性演练库（复核后一律 DROP） |
| 单测 | `cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test` |
| 静态审计 | `cd F:\dsh\ruoyiOA ; node tools\audit\run-all.js` |
| 迁移编排 | `tools\ctms-migration-prep.ps1`（`-LogDir` 指到 `$env:TEMP`，不污染仓库 `logs\`） |
| 真库端到端 | 夹具 `T16C1..T16C9` / 探针 `P1..P3`（用完即撤，收尾核对计数归零） |
| 突变测试 | 仓库副本 `$env:TEMP\ctms-review\ruoyi-vue-oa-master` 内改坏再跑红（**仓库源码零改动**，收尾逐字节比对已还原） |

---

## 2. Requirement ↔ 实现 ↔ 用例 ↔ 真实验收证据（一一映射）

### R1 历史甲乙方文本扫描生成待认领草案

| 项 | 内容 |
| --- | --- |
| 实现 | `support/MigrationRules.java`（`directionOf:121`、`rawTextOf:172`、`referenceIdOf:196`、`rawNameKey:236`）｜`service/impl/CtmsMigrationServiceImpl.java`（`scanPartyDrafts:148`、`loadContracts:477`、`aggregate:492`、`newDraft:536`）｜`mapper/ctms/CtmsPartyDraftMapper.xml`（`selectDraftByTypeAndName:72`、`insertDraft:85`） |
| 用例 | `采购合同按乙方聚合成一条待认领草案`（5→1）、`销售合同按甲方聚合成一条待认领草案`（3→1）、`不参与映射的类型被跳过`（COO/LAB/FIN/NDA/OTH/其他）、`扫描不改动合同的甲乙方与档案引用`、`同一方向同一文本合并而不同方向不同文本各自独立`、`同一文本的大小写差异按唯一索引进同一条草案`、`已绑定该方向档案的合同不参与扫描`（`CtmsMigrationServiceImplTest`） |
| 复跑 | `mvn -pl ruoyi-ctms test` → **Tests run: 208, Failures: 0, Errors: 0**（其中本类 23 条、`MigrationRulesTest` 6 条） |
| 真库 | 夹具 3 份 `PUR`（乙方同文本）+1 份大小写变体 `PUR`+1 份 `SAL`+1 份 `OTH`：`scan` 返回 `count=2`，落库 `supplier|t16rev阀门有限公司|4|pending`、`customer|T16REV集团|1|pending`，`OTH` **零草案** → 规格"按方向+文本聚合/不参与映射跳过"成立 |
| 真库 | 探针 A：`del_flag='1'` 的合同**确实被扫描纳入**（草案 `pending|1`）→ 与"迁移覆盖全量历史、含已停用"的声明一致（`includeDeleted="1"` 的真实 XML 生效） |
| 真库 | 探针 B：`supplier_id` 已绑别的档案的合同**不进草案**（`touched=0`、无该文本草案）→ 真实 `is null` 条件生效 |

### R2 草案刷新与幂等

| 项 | 内容 |
| --- | --- |
| 实现 | `CtmsMigrationServiceImpl.scanPartyDrafts:148` 的状态分档（`pending:164`／`claimed:177`／`ignored:190`／未知状态 throw `:201`）｜`updateDraft`（XML:96，不写 `party_type/raw_name`）｜`requireClaimable:224` + `MigrationRules.checkClaimable:302` |
| 用例 | `重复扫描只刷新计数不新建草案`、`重复扫描对未变化行不产生无意义更新`、`认领后出现新合同则回到待认领`、`已忽略的草案不被自动复活`、`未知状态的草案让扫描快速失败`、`认领状态机入口拒绝已认领并放行待认领` |
| 真库 | 第二次 `scan` → `count=0`，夹具草案 **MD5 指纹（含 `update_time`）逐字节不变**、行数仍 2 → "数据无变化 0 次写库"成立 |
| 真库 | 认领后再插入 1 份同文本未绑定合同 → `scan` → 该草案 `pending|<null>|1`（已绑的 4 份被聚合排除）→ 规格场景②成立 |
| 真库 | 忽略客户草案后再插入 1 份同文本 `SAL` → `scan` → `count=0`、该草案仍 `ignored|1`（计数**未**变成 2）、未新建第二行 → 规格场景③成立 |

### R3 草案认领与批量绑定

| 项 | 内容 |
| --- | --- |
| 实现 | `claimPartyDraft:242`（状态守卫→定档案→批量回填→逐份历史→落 `claimed`）｜`ignorePartyDraft:308`｜`createSupplierForClaim:333`（简称兜底 `:343`）／`createCustomerForClaim:366`／`requireExistingParty:411`／`buildPartyRefLog:444`｜`CtmsContractMapper.xml`：`selectUnboundPartyCandidates:338`、`bindPartyRef:357`｜`CtmsMigrationController`：`claim:91`、`ignore:109` |
| 用例 | `认领新建供应商档案并批量绑定四份合同`、`认领绑定已有客户档案不新建重复档案`、`已认领草案不可重复认领`、`已忽略草案可重新认领`、`批量绑定不覆盖已绑定别的档案的合同`、`认领方法带事务回滚`、`认领与忽略的守卫齐全` |
| 真库 | 认领（**不传 `shortName`/`name`**）→ `code=200`、`status=claimed`、`contractCount=4`；`t_ctms_supplier` 的 `name=short_name=草案 raw_name`（兜底生效） |
| 真库 | 4 份合同 `supplier_id` 回填、`party_b` 文本**未被改写**；`SAL`/`OTH` 未被误绑；变更历史 7 项聚合 **`4|4|4|4|4|4|4`**（条数｜`source=auto`｜有 `operator_id`｜有 `operator_name`｜`object_type=contract`｜`field_name=供应商档案`｜`new_value=档案ID`） |
| 真库 | 重复认领 → `msg=该草案已认领，不能重复认领`；忽略后的草案重新认领 → `claimed` + 2 份合同绑定 + 2 条「客户档案」历史 |
| 真库 | 探针 B 复证：已绑别的档案的合同**不被覆盖**（真实 SQL 的 `is null` 条件） |

### R4 未认领草案不阻塞业务

| 项 | 内容 |
| --- | --- |
| 实现 | `CtmsContractController.export:108`（`ExcelUtil` + `exportRows:121` + `ContractExportRow:144/160/164`）；取数仍只走 `selectContractList`（唯一数据范围入口） |
| 用例 | `CtmsContractServiceImplTest`（59 passed）中的"10 份未认领合同列表/详情展示""`ExcelUtil` 真实导出并逐行核对甲乙列为文本" |
| 真库 | `GET /ctms/contract/list?includeDeleted=1` 返回 8/8 份夹具合同且 `partyA/partyB` 均为文本（未绑定档案的不被隐藏） |
| 真库 | 探针 F：`POST /ctms/contract/export` 真实下载 xlsx（4003 bytes），解包 `xl/*.xml` 文本中**同时命中甲方文本、乙方文本、合同编号** → 导出路径落的是文本值，不是空列 |

### R5 约束回补前必须产出数据体检清单

| 项 | 内容 |
| --- | --- |
| 实现 | `sql/体检-合同台账-20261005.sql`、`sql/影响行数报告-合同台账-20261005.sql`（第 2 组交付）、`tools/ctms-migration-prep.ps1`（第 7 组 7.5 编排） |
| 复跑① | `-WithViolations`：**断言 29/0、退出码 3**；体检结论"需回填 13 列；违例行数合计 22 行"；清单落盘且含列名 + 可定位主键（示例 `DRLCT1`）；四条"库零动作"硬断言全绿（`*_bak_*` 0 张 / 13 列仍全可空 / 表名集合不变 / 业务表指纹一致） |
| 复跑② | 默认（收尾 DROP）：**断言 32/0、退出码 0**；13 张表逐表"报告受影响行数 == 真库 `COUNT(*)`"、报告自带两条结论"通过"、DDL 连跑两次无 ERROR、**13/13 列收紧**、**17 条外键** |
| 附加 | `-KeepDatabase` 变体：断言 **30/0**（少的是收尾 `DROP DATABASE` + "确认已不存在"两条）→ **t3 记录的 32/0 与 t25 记录的 30/0 不矛盾**，两条都是真实值（见 §8-O1） |
| 附加 | 库名守卫：`-Database rad_oa` → **退出码 2**，拒绝文案明确，rad_oa 表数/快照表数 **140/0 → 140/0 零变化**（证明连接前拒绝、一条 SQL 未执行） |

### R6 数据库约束回补范围与口径

| 项 | 内容 |
| --- | --- |
| 实现 | `sql/二开-合同台账.sql`（13 张表 + 建表 `DROP+CREATE` + 约束回补三段）；本项 SQL 属**第 2 组**交付，第 7 组的贡献是 7.5 编排（能证明 DDL 生效但不重复第 2 组的逐列评审） |
| 复跑（本轮实测，环境 = 7.5 默认路径产出的演练库） | ① 行项 `product_id` 为 NULL → **ERROR 1048 `Column 'product_id' cannot be null`**；② 合同 `customer_id='NO-SUCH-CUSTOMER'` → **ERROR 1452（`fk_contract_customer`）**；③ 供应商 `short_name` 为 NULL → **ERROR 1048**；④ `contract_id` 为空的单据侧日志**可插入**、`operator_id/operator_name` 仍 `YES`（未被误收紧） |
| 未覆盖（**诚实标注**） | "空库一次性执行 / 重复执行幂等 / 基线文件未被修改"三条场景属第 2 组与 10.3 的验收面；本轮只覆盖到"DDL 连跑两次无 ERROR + 13/13 收紧 + 17 FK"这一层 |

---

## 3. tasks.md 第 7 组 6 项任务逐项核对

| 任务 | 依据与结论 |
| --- | --- |
| 7.1 扫描生成草案 | 实现齐备、7 条用例在 208 passed 里、真库 2 条草案与 OTH 跳过复现 → **通过**（证据强度问题见 F3） |
| 7.2 刷新幂等与状态流转 | 3 条规格场景 + "未变化 0 次 update" + 未知状态快速失败，真库全部复现 → **通过** |
| 7.3 认领与批量绑定 | 5 条规格场景 + 2 条附加契约，真库 4 份绑定/4 条历史/兜底/重复拒绝/忽略后重认领全部复现 → **通过（主链路）**，但带空白文本的边界见 F1 |
| 7.4 未认领不阻塞业务 | 列表真库复现 + 导出真实 xlsx 命中甲乙文本 → **通过** |
| 7.5 迁移准备编排 | 两条路径退出码与断言数逐条复现，另复现库名守卫 → **通过** |
| 7.6 本组文档（t25/7.6b） | `notes/migration-notes.md` 存在（31118 字节、无 BOM）、真源清单/接口语义/状态机/编排/演练/回滚/排错/边界八块齐备，所述口径与本轮实测一致 → **通过**（记录行数不实见 F2） |

---

## 4. 独立查库核对（不采信文字说明）

| 核对项 | 实测 |
| --- | --- |
| `t_ctms_party_draft` 列 | 真库 `SHOW CREATE TABLE`：**恰 13 列**，与 DDL（`sql/二开-合同台账.sql:262~282`）、实体（`CtmsPartyDraft`）、`resultMap` 四方一致 |
| 唯一键 | 真库 `UNIQUE KEY uk_party_draft (party_type, raw_name)` 存在；表/列排序规则 = `utf8mb4_0900_ai_ci`，与 `MigrationRules.rawNameKey`（Collator PRIMARY）同口径 |
| 可空口径 | `matched_id/remark/create_id/create_by/update_id/update_by` 均可空；`id/party_type/raw_name/contract_count/status/create_time/update_time` 非空 —— 与实体注释与任务 2.4 明文口径一致 |
| 扫描前后合同零变化 | 9 份夹具 `md5(group_concat(...))`：**扫描前 = 扫描后**（`b201f8f5…`），且逐行 `party_b` 一致、档案引用全为 NULL（用 SQL 指纹，不看文字说明） |
| 幂等的库级证据 | 第二次扫描后夹具草案行的 MD5（含 `update_time`）不变（`cbb0f7db…` → `cbb0f7db…`） |
| 认领后的库级证据 | 4 行 `supplier_id` 写入、4 行 `party_b` 保持不变、4 条变更历史（含操作人、`source=auto`）、草案 `claimed` + `matched_id` + `contract_count=4` |
| 收尾 | 夹具全撤；复核结束时 `rad_oa` = 合同 0 / 草案 0 / 供应商 0 / 客户 0 / 变更历史 0；`b3*` 演练库 0 个（全部 DROP） |

---

## 5. 断言有效性抽查（突变测试：把实现改坏，用例必须变红）

在仓库副本里逐条突变、跑 `-Dtest=CtmsMigrationServiceImplTest,MigrationRulesTest`，**收尾逐字节比对确认副本与仓库原文一致（实现类 True / 规则类 True），仓库源码零改动**。

| 突变 | 改坏什么 | 红条数 | 变红的用例（节选） |
| --- | --- | --- | --- |
| 基线 | —— | **0** | —— |
| M1 | `aggregate` 去掉"该方向已绑定档案 → 跳过"（`referenceIdOf` 判定失效） | **2** | `已绑定该方向档案的合同不参与扫描`、`批量绑定不覆盖已绑定别的档案的合同`（expected 2 but was 3） |
| M2 | `claimed` 回归时不清 `matched_id` | **1** | `认领后出现新合同则回到待认领`（expected null, but was S-001） |
| M3 | `ignored` 跟着 `pending` 一起刷计数 | **1** | `已忽略的草案不被自动复活`（expected 2 but was 4） |
| M4 | `rawNameKey` 退化成大小写敏感（脱开 `uk_party_draft` 的 ai_ci） | **2** | `同一文本的大小写差异按唯一索引进同一条草案`、`MigrationRulesTest`（Acme vs acme） |
| M5 | `directionOf` 把 `PUR` 指到客户方向 | **18** | `采购合同按乙方聚合成一条待认领草案`、`扫描不改动合同的甲乙方与档案引用`、`草案列表按方向与状态过滤…` 等 |

结论：**5 个关键断言（聚合口径 / claimed 回归 / ignored 不复活 / 幂等键口径 / 方向映射）都被用例真实钉住**，不是"看着像对"。
（另：本轮 F1 的发现即来自"把聚合口径与认领口径并排放在同一份真数据上比对"，同样属于口径有效性检查。）

---

## 6. 复跑命令与退出码汇总

| 命令 | 退出码 | 关键数字 |
| --- | --- | --- |
| `mvn -B -pl ruoyi-ctms test` | **0** | Tests run: **208**, Failures: 0, Errors: 0（`CtmsMigrationServiceImplTest` 23、`MigrationRulesTest` 6、`CtmsContractServiceImplTest` 59） |
| `node tools\audit\run-all.js` | **0** | 共 **10** 个审计；失败 **0** 个；未自证 0 个 |
| `ctms-migration-prep.ps1 -WithViolations` | **3** | 断言 **29/0**、违例 13 列 / 22 行、清单含主键、四条"库零动作"硬断言全绿 |
| `ctms-migration-prep.ps1`（默认 DROP） | **0** | 断言 **32/0**、13/13 列收紧、17 FK、DDL×2 无 ERROR |
| `ctms-migration-prep.ps1 -KeepDatabase` | **0** | 断言 **30/0** |
| `ctms-migration-prep.ps1 -Database rad_oa` | **2** | 连接前拒接；rad_oa 表数 140/0 → 140/0 零变化 |
| 真库端到端（`t16-db-e2e.ps1`） | **0** | 断言 **38/0**，收尾计数归零 |
| 探针（`t16-probes.ps1` / `t16-probes2.ps1` / `t16-req6.ps1`） | 0 / 0 / 7-1※ | 探针 A~F 见 §2；※最后一条"演练库已删除"因掉库后连接失效误判，独立复核 `information_schema` 已确认 `b3*` 库为 0 个 |
| 突变矩阵（副本内） | 0 | 基线 0 红；M1=2 / M2=1 / M3=1 / M4=2 / M5=18 红；副本已逐字节还原 |

---

## 7. Findings

### F1（medium）扫描与认领对"同一文本"的比较口径不一致 → 带首尾空白的合同永远绑不上，且认领"成功"却实绑 0

- **problem**：`aggregate()` 用 `MigrationRules.rawTextOf → normalizeRawName`（**Java `trim()` 归一**）决定草案的 `raw_name` 与计数；
  而认领的候选集合 `selectUnboundPartyCandidates` 用 `c.party_b = #{rawName}`（**SQL 精确比较**）。
  `t_ctms_contract.party_a/party_b` 的排序规则是 `utf8mb4_0900_ai_ci`（**NO PAD**：首尾空格都显著），两条链路对同一份数据给出不同答案。
- **reproduced evidence（真库，探针 C，2026-10-05 15:40）**：
  - 夹具 `P3` 的 `party_b = ' T16PRB空白阀门有限公司 '`（`char_length=16`、`hex` 头部 `2054…`，前后各一个半角空格）；
  - `scan` → 生成草案 `raw_name='T16PRB空白阀门有限公司'`（14 字符，已 trim）、**`contract_count=1`**；
  - `claim`（新建供应商）→ **`code=200 msg=操作成功`，`status=claimed`，本次实际绑定 `0`**；`P3.supplier_id` 仍为 `<null>`；
  - 紧接着再 `scan` → **`touched=1`，该草案回到 `pending|<null>|1`** → `claimed` 与 `pending` 之间**永久往复、无法收敛**；
  - 反向验证：把候选条件换成 `trim(party_b) = 草案文本` 时匹配行数 = **1**（差异只在 SQL 比较口径，不在数据）。
- **影响**：历史数据里"文本带首尾空格"（Excel/CSV 导入极常见）的合同**永远拿不到档案引用**——迁移工具的核心目标（文本→档案收敛）在这一类数据上静默失败；
  而运维看到的是"认领成功"，`contract_count` 还被刷成 0，只能靠"忽略"消音。
- **file**：`ruoyi-vue-oa-master/ruoyi-ctms/src/main/resources/mapper/ctms/CtmsContractMapper.xml`（`party_a = #{rawName}` 第 342 行、`party_b = #{rawName}` 第 345 行）；
  `ruoyi-vue-oa-master/ruoyi-ctms/src/main/java/com/ruoyi/ctms/service/impl/CtmsMigrationServiceImpl.java`（`aggregate` 第 508 行取 `rawTextOf` 归一值 + 第 518 行分组键）；
  `ruoyi-vue-oa-master/ruoyi-ctms/src/main/java/com/ruoyi/ctms/support/MigrationRules.java`（`normalizeRawName:220`）
- **requiredFix**：把两条链路的口径统一（**二选一，需与被审者确认哪种是目标语义**）：
  1. 候选集合改成与 Java 同口径的归一比较（如 `trim(c.party_b) = #{rawName}`；若要覆盖 tab/全角空格需与 `ContractRules.trimToNull` 同定义）；或
  2. 扫描写入 `raw_name` 时**不做 trim**（只用 trim 判断"是否空白"），让草案文本 = 库内原文，认领的精确比较自然命中。
  任一改法都**必须补一条带首尾空白的回归用例**（服务层 + 真库各一），断言：认领后该合同被绑定、再 `scan` 后草案**稳定停在 `claimed`**（不再回到 `pending`）。
  另外建议：当"草案计数 > 0 而本次实绑 0"时给出可诊断信号（日志或提示），避免"成功但什么都没做"。

### F2（low）交付记录的行数与实物不符（PowerShell 编码读法导致的假数字）

- **problem**：`tasks.md:529` 与 t25 交付记录写 `notes/migration-notes.md` 为 **282 行**；**UTF-8 实测 370 行**（31118 字节这一项是对的）。
  282 是 `Get-Content`（PS 5.1 默认 GBK 解码）把 UTF-8 双字节序列后的 `0x0A` 一并吞掉后的行数——是编码坑制造的伪数，不是文件真实规模。
- **file**：`openspec/changes/oa-contract-ledger/tasks.md:529`（记录文本）；实测对象 `openspec/changes/oa-contract-ledger/notes/migration-notes.md`
- **requiredFix**：用 `Get-Content -Encoding UTF8`（或 `[IO.File]::ReadAllLines`）重新量一次，把 282 改成 **370**（或只保留字节数），
  并在 `DEV-ENV.md` 的量行数纪律里带上 `-Encoding UTF8`（与 §6.33/§6.44 同族）。

### F3（low）第 7 组最关键的真库语义缺自动化证据，只能靠人工演练取证

- **problem**：`CtmsMigrationServiceImplTest` 的 `CtmsContractMapper` / `CtmsPartyDraftMapper` 都是**内存桩**
  （`StubContractMapper:1076`、`StubPartyDraftMapper:1274`），桩自己复刻了 `includeDeleted`、`is null`、ai_ci 语义；
  `草案表十三列与DDL逐列一致:669` 只解析 DDL/XML 的**列名集合**，`控制器权限点全部来自菜单SQL且未新增:726` 只做权限点正则比对。
  因此 t1 交付记录里"扫描不改动合同""大小写进同一条草案""已停用合同进扫描"这些**决定性语义在 JUnit 里从未被执行**——
  只有 XML 才决定的行为（真实 SQL 的比较规则/`<if>` 双引号 OGNL/`<where>` 拼接）没有回归资产，每次都要靠人工演练重新取证。
- **file**：`ruoyi-vue-oa-master/ruoyi-ctms/src/test/java/com/ruoyi/ctms/service/impl/CtmsMigrationServiceImplTest.java:1076-1267`（桩）
- **requiredFix**：把本轮脚本里的真库断言沉淀为可重复资产（如 `tools/ctms-migration-check.ps1`，或在既有验收脚本加一段），
  至少覆盖 4 条只有真实 SQL 才能证明的语义：① `includeDeleted="1"` 生效（已停用合同进扫描）；② ai_ci 文本匹配；③ `is null` 候选过滤（不覆盖已绑别的档案的合同）；④ `claimed` 回归 / `ignored` 不复活。供 10.2/10.3 复用。

---

## 8. 已解释的观察（不计 finding，避免后人误判）

- **O1 32/0 与 30/0 不是矛盾**：默认模式（收尾 DROP）实测 **32/0**、`-KeepDatabase` 实测 **30/0**，差的正是"已收尾 DROP + 确认已不存在"两条断言。t3 记 32/0、t25 记 30/0，两条都是真值。建议在 `migration-notes.md` §4.2 补一句"30/0 是 `-KeepDatabase` 变体"，省得后人以为记录漂移。
- **O2 供应商"简称空串"能被库接受**：`short_name` 是 `NOT NULL`，NULL 被 **1048** 拒绝，但空串 `''` 可落库（真库实测）。
  规格场景"简称为空被拒绝"按 NULL 计成立；应用层 `PartnerRules` 会拒绝空白简称（t22 的负面对照已证）。属**第 2 组 DDL / 10.3 复核面**，本组只登记。
- **O3 `rad_oa` 是共享库**：复核期间曾被其它脚本并发写入（出现过 2 份 `UI演练阀门有限公司152328` 的遗留合同，`scan` 为它建过草案）。
  本轮断言全部**按自身夹具收敛**，不假设整库为空；结束时整库计数 0/0/0/0/0、无 `T16*`/`P*` 残留、无 `b3*` 演练库残留。
- **O4 运行态自证成立**：jar 时间戳（15:13:30）晚于 t22 完成时间；四端点用合法 32 位 hex id 实调均 HTTP 200（业务性 500 属正常）；`scan`/`drafts`/`claim`/`ignore` 的 200 已在 §5 的真实链路里逐条复现——**t25 的"运行态自证"这一手是新 jar 证据链的关键，值得保留为后续同类任务的模板**。

## 9. 本次复核未覆盖 / 边界

- 未做：Excel 导出的**样式/列序**逐列核对（只验"文本落进 xlsx"）；`sys_oper_log` 操作日志内容核对；菜单/角色授权层面的接口可见性（属第 9 组）。
- 未做：第 2 组的逐列 DDL 评审与 `b3-sql-drill.ps1` 的独立复跑（不属第 7 组交付面，R6 已标注取证边界）。
- 未改：任何实现代码、`tools/`、`sql/`、前端；`logs/` 未写入（`-LogDir` 全部指到 `$env:TEMP`）。
- 本轮产物（临时件，不属交付物）：`$env:TEMP\ctms-review\t16-db-e2e.ps1`、`t16-probes.ps1`、`t16-probes2.ps1`、`t16-req6.ps1`、`t16-mutation.ps1` 与对应 `*-out.txt` 原始输出。
