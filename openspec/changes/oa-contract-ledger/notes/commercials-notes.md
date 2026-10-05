# B3 第 5 组交付说明：商务要素、质保与编号（任务 5.1~5.9）

> 变更集：`oa-contract-ledger`｜规格真源：`openspec/changes/oa-contract-ledger/specs/ctms/contract-commercials/spec.md`
> 交付时间：**2026-10-05**｜任务：5.1 ~ 5.9（第 5 组全部）
> 验收资产：`tools/ctms-commercials-check.ps1`（**116 条真实环境断言**）、
> `CtmsContractServiceImplTest`（新增 10 条）、`ContractNumberRulesTest`（5 条）、`ContractRulesTest`（+2 条）

本文件给下一个接手的人：**这一组的每条口径落在哪个可执行测试里**、以及**哪些是反直觉的、改之前必须知道**。

---

## 1. 一句话结论

第 5 组的 8 个 Requirement 全部落地并通过真实验收。
本组**真正新增的能力**只有三块：**合同编号服务端生成（5.8）**、**质保提醒看板（5.6）**、**付款比例外露（5.7）**；
其余（行项、金额口径、标的物摘要、质保算法与互换、关闭清空）在第 4 组已经落库，本组是把它们**补上缺口的断言**并逐条锁定。

---

## 2. 口径 → 规格场景 → 可执行测试（逐条对照）

| # | 口径 | 规格场景名 / Requirement | 落点（可执行） |
| --- | --- | --- | --- |
| 5.1 | 行项序号按提交顺序从 1 连续 | 行项明细与必填校验 / Scenario: 序号按提交顺序重排 | `ctms-commercials-check.ps1`「5.1 行项」（`GROUP_CONCAT(CONCAT(seq,':',name,...))` 与 `1:…\|2:…` 逐字比对）；`CtmsContractServiceImplTest.未选物料档案的行项被拒且行号取重排后的序号` |
| 5.1 | 未选物料报「行项第 N 行必须选择物料档案」 | 同上 / Scenario: 未选物料档案被拒绝 | 脚本断言文案含「行项第 2 行必须选择物料档案」＋「整单不落库」；单测同名用例 |
| 5.1 | 数量/单价为负被拒 | 同上 / Scenario: 负数被拒绝 | 脚本两条（`qty=-1`、`unitPrice=-1.0000`，文案 `不能为负数`）；单测 `数量或单价为负数的行项被拒` |
| 5.1 | 名称/规格留空回落物料档案 + 物料编码快照 | 同上 / Scenario: 名称规格回落物料档案 | 脚本断言 `name/spec/product_code` 三列拼接；`ContractRulesTest`（`itemNameOf`/`itemSpecOf`） |
| 5.1 | 只改物料绑定的编辑也要留痕 | 同上（任务补充） | 脚本「5.1 只改物料绑定的编辑」断言 `_items` 历史 ≥1 条且金额按新行项重算；`itemSignature` 含 `productId`（见实现类注释） |
| 5.2 | 行总价先 HALF_UP 到分，合同金额 = 各已舍入行总价之和 | 行项总价先舍入再汇总 / 两个 Scenario | `ContractRulesTest.三行1_665…`（5.01 判别）＋ `已舍入行总价之和`；脚本「5.2 金额口径」（`5.00,0.10` → `5.10`、`0.005×3` → `0.03`） |
| 5.2 | 全链路无 `double`/`float` | 同上（MUST NOT 二进制浮点） | `grep -rn "double\|float" ruoyi-ctms/src/main/java` 零命中（铁律 3；`ContractRules` 类注释亦声明） |
| 5.3 | 标的物摘要**真正落库**（不只写日志） | 合同金额与标的物摘要由行项覆盖 / Scenario: 标的物摘要自动改写 | 脚本断言数据库 `subject_matter` 列值 = `验收螺栓…(M12)×10；验收球阀…(DN50)×2`；`CtmsContractServiceImplTest.标的物摘要真正落库为球阀与法兰` |
| 5.3 | 金额改写留痕来源为 auto | 同上 / Scenario: 行项汇总覆盖合同金额并留痕 | 脚本断言 `field_name='合同金额' AND source='auto'` ≥1；`markItemOverriddenFields` 的「强制留痕」集合 |
| 5.4 | 到期日 = 生效日 + (max(1,月数) − 1) 月 → 该月最后一天 | 质量保证金到期日算法 / 6 个 Scenario | `ContractRulesTest` 的 6 条示例逐条断言（含 `2026-01-31+1 → 2026-01-31`、`+2 → 2026-02-28`、`2026-03-15+24 → 2028-02-29`） |
| 5.4 | **新增路径**服务端算，前端传值不参与 | 同上 / Scenario: 新增合同即服务端计算到期日 | 脚本「5.4」**故意**传 `warrantyEnd=2099-12-31`，断言落库仍是 `2026-05-31`（这条是本组新增的最强判别） |
| 5.4 | 到期日留痕来源为 auto | 同上 | 脚本断言 `field_name='质量保证金到期日' AND source='auto'` ≥1 |
| 5.5 | 仅填比例补金额 / 仅填金额补比例 | 质保金额与比例互换及关闭清空 / 2 个 Scenario | 脚本断言 `100000.00 × 5% = 5000.00` 与 `3000.00 ÷ 30000.00 = 10.0000`；`ContractRulesTest`（`warrantyAmountOfRate`/`warrantyRateOfAmount`） |
| 5.5 | 关闭质保清空 7 个字段并逐个留痕 | 同上 / Scenario: 关闭质保清空并留痕 | 脚本断言清空后拼串为 `null/null/null/null/null/0/null` 且历史新增 ≥7 条；`CtmsContractServiceImplTest.关闭质保清空七个字段并逐个留痕为auto` |
| 5.6 | 即将到期 = 到期日 ∈ [今天, 今天+窗口]；已到期 = 到期日 < 今天 | 质保到期提醒与释放闭环 / 4 个 Scenario | 脚本「5.6」用 `DATE_ADD(CURDATE(), INTERVAL N DAY)` 精确定位边界（含 `+30` 当天必须落在「即将到期」）；`CtmsContractServiceImplTest.质保提醒按窗口边界切分…` |
| 5.6 | 两个列表只覆盖「启用质保 + 未释放 + 到期日非空 + 未停用」 | 同上 | 脚本四条否定断言（已释放不在两个列表、已停用不在两个列表）；SQL 侧四条硬条件见 `CtmsContractMapper.selectWarrantyReminderCandidates` |
| 5.6 | 释放后立刻从两个列表消失并记释放日期 | 同上 / Scenario: 释放后不再提醒 | 脚本调 `PUT /ctms/contract/warranty/{id}/release` 后重新拉两个列表断言其消失；`releaseWarranty` 写 `warranty_release_date = now()` |
| 5.6 | 看板最多 100 条、按到期日升序 | 同上（任务 5.6 补充） | 脚本断言两个列表长度 ≤100 且「即将到期」的日期序列与排序后一致 |
| 5.6 | 窗口天数取自 `sys_config.warranty_window_days`，改参数行为随之变化 | 同上（任务 5.6 补充） | 脚本把参数改成 1 再还原（**必须走 `PUT /system/config`**，理由见 §5 坑 2） |
| 5.7 | 付款比例 = 累计已付 ÷ 合同金额 × 100，**不扣质保金** | 付款比例与超出金额口径 / Scenario: 付款比例计算 | `ContractRulesTest.付款比例`（`paidRate`）；脚本断言 `200000/50000 → 25.0000`、加 1000 质保金后仍 `50.0000` |
| 5.7 | 合同金额为空或 0 返回空值不报错 | 同上 / Scenario: 合同金额为 0 时返回空值 | 脚本断言详情 `paidRate` 为 `null` 且接口 code=200；`ContractRulesTest` 断言 `paidRate` 返回 null |
| 5.7 | 累计已付超出合同金额允许保存并留痕 | 同上 / Scenario: 已付超出合同金额仍可保存 | 脚本断言保存成功、`paid_amount` 落库、`累计已付金额` 历史 ≥1、比例 `125.0000` |
| 5.8 | 格式 3+2+4+2+6、示例 `PURZC202609000001` | 合同编号格式与按年重置 / 4 个 Scenario | `ContractNumberRulesTest.编号格式…`；脚本按正则 `^${type}${subject}yyyyMM\d{6}$` 校验并断言长度 17 |
| 5.8 | 序号在「类型码 + 主体码 + 年份」维度共享递增 | 同上 / Scenario: 同年内序号递增 | 脚本「同桶续号」断言 `seq` +1；`StubCodeGenService` 按 `type+subject+year` 分桶 |
| 5.8 | 跨年从 `000001` 重来，**不依赖 1 月 1 日定时任务** | 同上 / Scenario: 跨年重置 | 脚本断言次年桶按自身递增且序号 **远小于** 旧年份桶（Redis 计数器跨轮持久，所以不写死 `000001`）；`ruoyi-serial` 侧见 1.2/1.3 的结论 |
| 5.8 | 月份码取签订日期且**不参与**序号 | 同上 / Scenario: 月份不重置序号 | 脚本断言两份合同的月份码分别为 `09`/`10` 而序号连续；`ContractNumberRules.prefixOf` 只含年份不含月份 |
| 5.8 | 停用占号不复用 | 同上 / Scenario: 停用占号不复用 | 脚本先停用占号合同，再用同一编号登记（两条路径：手工提交 + 自动跳过），断言被拒/跳过 |
| 5.8 | 预览不占号、连续两次相同 | 编号预览与占用冲突处理 / Scenario: 预览不占号 | 脚本在同一桶上连续预览两次 + 预览后真正取号拿到预览值；`ctms-serial` 的预览实现见 §1.3 |
| 5.8 | 保存时服务端生成、冲突返回冲突错误 | 同上 / Scenario: 保存时编号冲突 | 脚本断言被占用编号返回 `编号已存在`；服务端抛 `ServiceException(msg, 409)`（design D-6 的"冲突语义"） |
| 5.8 | 历史导入允许手工编号 | 同上 / Scenario: 历史导入允许手工编号 | 脚本用手工编号 + **已停用类型 `OTH`** 登记成功（证明手工通道不要求类型参与编号） |
| 5.8 | 停用类型拒绝自动编号、主体为空拒绝 | 编号类型与主体取值校验 / 2 个 Scenario | 脚本 4 条（类型 `OTH`、主体空、主体不在清单、参考日期格式非法）＋登记路径 2 条；`ContractNumberRulesTest` 逐条 |
| 5.9 | 本文件 | — | 本节 |

---

## 3. 本组的三个"行为变化"（改代码前必须知道）

### 3.1 登记时**编号可以留空**了（4.1 的契约变了）

第 4 组的口径是「编号必填 + 唯一」，第 5 组改成**两条通道**（见实现类 `resolveContractNo` 的方法注释）：

| 提交的 `contractNo` | 走哪条通道 | 校验 |
| --- | --- | --- |
| **留空/null** | 服务端生成（用户登记的默认路径） | 类型必须在 `contract_types` 字典内且可映射为 3 位大写码；主体码必须在 `subjects` 字典内；序号按「类型码+主体码+年份」分桶 |
| **非空** | 手工/历史编号（迁移导入通道） | 只校验**格式**（`ContractNumberRules.checkNoFormat`）+ **唯一性**；**不**要求类型/主体 —— 历史数据里存在已停用类型（`OTH`）与缺主体码的合同，强制校验会让"导入"变成不可能 |

**为什么手工通道不做类型校验**：规格写的是「迁移通道提交一份编号为 `PURZC202501000123` 的历史合同，格式合法且库内不存在 → 接受」，
并没有要求那份历史合同的主体码非空。如果两条通道共用校验，"导入历史合同"就会因为历史脏数据被拒。

⚠ 由此产生一条**兼容性提醒**：非空编号现在必须满足 17 位格式。第 4 组验收期造的 `CTMCP…` 这类测试编号
**不再是合法输入**（会报「合同编号格式不正确」）。两个验收脚本已经同步改成合法形态或留空编号。
如果将来要支持"任意文本编号"的历史数据，正确做法是**给导入通道单独开一个入口**，
而不是把格式校验放开（放开就等于让唯一索引与占号扫描失效）。

### 3.2 自动编号带"撞号即换下一个"的有界重试（不是失败）

`t_code_config.current_seq` 在**分桶路径下不回写**（单列表达不了多桶），序号的权威计数只在 Redis。
所以只要 Redis 被清空 / 库从快照恢复 / 编号配置被重建，取号结果就可能与库内已有编号重合。
唯一索引会拒绝那次写入（1062），但那时错误形态就变成数据库异常了。
实现的做法（`NO_COLLISION_RETRY_LIMIT = 1000`）：生成 → 若同桶里已有 ≥ 该序号的编号 → 再取一次，直到拿到空位。
**代价是那几个号被跳过**（规格只要求"不得复用已占用编号"，跳号是允许的）。

### 3.3 付款比例是**派生列，不落库**

`CtmsContract.paidRate` 明确标注为非持久化字段，由 `selectContractList` / `selectContractDetail` 现算（`fillDerivedFields`）。
**改代码时的三条禁令**：不得进 Mapper XML 的 insert/update 列清单、不得进 `DIFF_FIELDS`（逐字段比对）、不得作为查询条件。
理由：它是 `amount` 与 `paid_amount` 的派生值，存一列会立刻产生"金额改了、比例忘了改"的不一致。

---

## 4. 接口面（本组新增）

| 方法 | 路径 | 权限点 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/ctms/contract/next-no?type=&subjectCode=&referenceDay=` | `ctms:contract:add` | 编号预览（不占号）。**返回值在 `msg` 上**，见 §5 坑 3 |
| `GET` | `/ctms/contract/warranty-reminders` | `ctms:contract:list` | 质保提醒：`{expiring[], expired[], windowDays, today}` |

**本组不新增权限点**（交付当时仍是 25 个 `ctms:*`），因此 `sql/二开-合同台账-菜单.sql` 不需要改，9.1 的双向核对口径不变。
> ⚠ 后续更新（2026-10-05，第 6 组）：**第 6 组新增了 1 个权限点 `ctms:attachment:list`**，
> 菜单 SQL 现在是 **26 个** `ctms:*` / **27 行**。本节说的"25 个"只描述第 5 组交付当时的状态；
> 任务 9.1 的双向核对口径请按 **26** 执行（见 `notes/attachment-notes.md` §4）。
理由：预览是"登记前先拿号"，能登记才需要预览 → 用 `add`；提醒是"合同台账上再读一次" → 用 `list`。
两个路由都是**无路径变量**的单段字面量（`/next-no`、`/warranty-reminders`），从根上不与 `/{id:[A-Za-z0-9]+}` 竞争。

---

## 5. 本组踩到的坑（已写入 `DEV-ENV.md` §6，编号 44/45）

1. **PowerShell 变量名会把后面的引号"吃进去"**（§6.44）：`"…$S"` 实际解析成变量 `S"` → 空值。
   症状极具误导性：脚本拼出 `DELETE /ctms/contract/?reason=…`，后端回
   `Request method 'DELETE' not supported`（Spring 看到的是 `DELETE /ctms/contract`），
   看起来像"DELETE 接口没实现"。规则：变量后面紧跟中文/引号/`$` 时一律写 `${X}`。
2. **参数缓存必须走接口刷新**：`SysConfigServiceImpl` 把参数值缓存在 Redis
   （`sys_config:warranty_window_days`）。脚本里直接 `UPDATE sys_config` 会得到
   "改了参数却没生效"的**假失败**；正确做法是 `PUT /system/config`。
3. **`AjaxResult.success(String)` 把值放进 `msg`**：`/ctms/contract/next-no` 成功返回
   `{"msg":"PURZC202609000001","code":200}`（与 2.8 的 `/system/config/configKey` 同一个坑）。
   脚本断言必须读 `msg`，否则会得到"返回体是空的"的假失败。
4. **夹具清理的 FK 顺序**（§6.45）：`行项 → 产品`、`合同 → 客户` 两条 FK 决定了清理顺序；
   顺序错会让脚本在 `$ErrorActionPreference='Stop'` 下**整脚本中止**并留下 LockFile，
   下一次运行被自己的锁拦下（看起来像并发冲突）。
5. **编号断言不能假设计数器起点**：Redis 计数器跨轮持久，写死 `PURZC202609000001` 会"复跑即假失败"。
   本脚本一律用"同一轮内前后对照"（同桶 +1、跨年桶与旧年份桶比较、预览值 = 计数器 + 1）。

---

## 6. 验收资产自查

| 资产 | 条数 | 覆盖 |
| --- | --- | --- |
| `tools/ctms-commercials-check.ps1` | **116** | 5.1~5.8 全部（真实环境、接口 + 落库值） |
| `tools/ctms-contract-check.ps1` | **113**（原 110 + 3） | 第 4 组全部 + 本组"编号由服务端生成"的跨组契约 |
| `CtmsContractServiceImplTest` | 54（原 44 + 10） | 编号全链路（含类型/主体校验、预览、停用占号）、付款比例、质保提醒与释放、窗口参数 |
| `ContractNumberRulesTest` | 5（新增） | 类型码/主体码校验、编号格式、各段解析与占号扫描 |
| `ContractRulesTest` | 22（原 20 + 2） | 质保提醒的日期工具（归零/加天）、参考日期与系统参数解析 |
| `mvn -B -pl ruoyi-ctms test` 合计 | **156**（原 139） | 全模块 |
| 静态审计 | 9 / 9 全绿 | `node tools/audit/run-all.js` |

**幂等性**：`ctms-commercials-check.ps1` 连跑两次均 116/116 通过、夹具残留 0 行；
`ctms-contract-check.ps1` 与 `ctms-masterdata-check.ps1` 同样连跑两次全绿。

---

## 7. 与前后组的边界

- **1.4（数据范围四档矩阵实测）仍待补**：`ContractDataScope` 已是唯一判定入口，
  本组新增的两个接口（提醒）也接入了它；矩阵实测可直接扩展 `ctms-contract-check.ps1` 的 4.8 段。
- **第 6 组（附件）不受本组影响**：附件挂载走 `/common/upload` + `t_ctms_attachment`，与编号/质保无耦合。
- **第 7 组（迁移工具）会用本组的"手工编号通道"**：7.x 认领后回填的是**档案引用**，
  不改编号；若将来要"导入历史合同"，直接复用 `contractNo` 非空的那条通道即可（已通过 `OTH` 类型验证）。
- **第 8 组（前端）要用的两个新接口**：`GET /ctms/contract/next-no`（登记页"取号"按钮，注意读 `msg`）
  与 `GET /ctms/contract/warranty-reminders`（质保看板，`expiring`/`expired` 两个列表 + `windowDays`/`today`）。
