# B3 §1.2 合同编号技术验证（`ruoyi-serial` 能否表达合同编号）

> 变更集：`oa-contract-ledger`｜任务：tasks.md §1.2（判定标准即 design.md D-6 的 4 条）
> 验证时间：**2026-10-05 08:2x**｜验证人：二开｜环境：6 服务在线，后端 jar `2026-10-05 08:19:02`
> 被测版本：`ruoyi-serial`（`CodeGenServiceImpl` / `CodeConfigServiceImpl` / `CodeRestTask`），**未改一行代码**

## 结论（先说结果）

**「配置表达」不通过——4 条判定标准 4 条全部不通过。** 必须走 tasks.md §1.3 的**扩展分支**
（design.md D-6 的「最小扩展面」）。

编号格式目标：`3 位类型码 + 2 位主体码 + 4 位年份 + 2 位月份 + 6 位序号`，例 `PURZC202506000001`。

## 验证方法（可复现）

夹具：`t_code_config` 2 条（ZC / XS 两个主体码）+ `t_code_config_rule` 各 5 条规则，
按任务要求拼「固定值(PUR) + 固定值(ZC) + 日期(yyyy) + 日期(MM) + 流水号(6 位、按年重置)」。
取号入口：`GET /serial/config/genSerialNo/{confId}`（`CodeConfigController:106`）。

## ① 能否按**签订日期**产出编号 —— ❌ 不通过

| 期望（按签订日期） | 实际（今天 = 2026-10-05） |
| --- | --- |
| 签订 2025-06-01 → `PURZC202506000001` | `PURZC202610000001` |
| 签订 2027-01-01 → `PURZC202701000001` | `PURZC202610000002` |

原因（代码级证据）：`CodeGenServiceImpl.java:112`

```java
case DATE:
    nextCode.append(DateUtils.parseDateToStr(ruleValue, DateUtils.getNowDate()));
```

日期规则的取值**恒为 `getNowDate()`**，`ICodeGenService.getNextCode(String confId)` 也**没有**任何
可传入参考日期的入口。因此月份码与年份码只能取"服务器当天"，无法表达"按签订日期"。
（本次实测 4 次取号：`PURZC202610000001/2`、`PURXS202610000001/2`，年份月份都是 2026-10。）

## ② 跨年重置不依赖「1 月 1 日当天有定时任务」 —— ❌ 不通过（且比预期更糟）

**三重问题，逐条实测：**

1. **重置动作本身是"1 月 1 日定时任务"**：`CodeRestTask.restSeq()` → `CodeConfigServiceImpl.restSeq():156-190`
   里 `isFirstDayOfYear = (月==1 && 日==1)`，即**必须**在 1 月 1 日当天有任务执行；应用当天没运行就永不重置。
2. **重置改的是 DB，而真正的计数器在 Redis**：`updateCurrentSeq` 的 SQL 是
   `update t_code_config set current_seq = 1 where id in (...)`，但 `getNextCode` 用的是
   Redis 键 `code:gen:seq:<confId>`，且**只在键不存在时**才用 DB 值初始化
   （`CodeGenServiceImpl.java:65-68`）。实测：

   | 步骤 | 结果 |
   | --- | --- |
   | ZC 取 2 次 | `...000001`、`...000002`（Redis `code:gen:seq:B3NUMVERIFY…01` = 2） |
   | 模拟定时任务：`UPDATE t_code_config SET current_seq=1` | —— |
   | 再取号 | `PURZC202610000003` ← **序号没有回到 000001** |
   | 再取一次 | `PURZC202610000004` |

   即：**即使定时任务在 1 月 1 日跑了，编号也不会重置**，除非把 Redis 键删掉
   （实测 `DEL code:gen:seq:<confId>` 后取号才会回到 DB `current_seq` 的下一值）。
3. 所以"跨年重置"在当前实现下是**配置表里的一个声明**，与运行时行为不一致。

## ③ 序号维度是「类型码 + 主体码 + 年份」 —— ❌ 不通过

序号维度实际是**一条配置一个计数器**（Redis 键 = `code:gen:seq:<confId>`），
主体码只能作为固定值写死在规则里（实测 `rule_value='ZC'`）。

- 想让两个主体码各自计数，只能**建两条配置**（本次夹具就是 ZC / XS 各一条）——
  这既不是"同一配置内按主体分桶"，也会让"类型码"与"主体码"的组合数等于配置数（N×M 条配置）；
- 且 `CodeConfigRule.ruleValue` 是 `varchar(200)`，在日期规则里承载的是**日期格式串**
  （`yyyy` / `MM`），**不能承载**由业务参数决定的取值（主体码、类型码）。

## ④ 预览不占号 + 冲突处理 —— ❌ 不通过

- `CodeConfigController` 只有 `GET /genSerialNo/{confId}`，**没有 preview/peek 端点**；
- 该端点每次调用都**占号**：连续两次返回 `PURZC202610000006` / `PURZC202610000007`
  （不相同），并各写一条 `t_code_sequence_log`（实测流水表累计 8 行）；
- 也没有"预览"与"保存时生成"的分离，无法满足"预览不占号、保存时才生成并处理冲突"。

## 对 §1.3 的输入（已验证的差距 → 最小扩展清单）

| # | 差距（本节证据） | 最小扩展 |
| --- | --- | --- |
| 1 | 日期规则恒取当天（`CodeGenServiceImpl:112`） | 新增「调用方可传**参考日期**」的取号入口：日期规则按参考日期格式化，不传则维持当天 |
| 2 | 计数器键只含 `confId`；重置靠 1 月 1 日任务且改的是 DB | 新增「**按业务参数分桶** + **按年惰性重置**」：计数器键含参数与年份，新桶/新年**天然从 0 起**，不依赖任何定时任务 |
| 3 | `ruleValue` 无法承载业务参数取值 | 新增一种规则类型（业务参数，`ruleValue` = 参数名），取值来自调用方传入的参数表 |
| 4 | 无预览、取号即占号 | 新增**预览**入口（只算不落号：不 INCR、不写流水、不改 DB） |

**向后兼容的硬要求**（§1.3 验收的一部分）：既有 `getNextCode(String confId)` 的**行为与键格式一字不变**
（仍用 `code:gen:seq:<confId>` + DB `current_seq` 初始化 + 写流水），
即"不传参考日期/参数"的老调用方完全不受影响。

---

### 附：本次验证用到的命令要点

```powershell
# 夹具（.cache\b3-numbering-fixture.sql）：2 条配置 + 10 条规则，FIXED P U R / ZC + DATE yyyy / MM + SEQ 6 位按年重置
Get-Content -Raw -Encoding UTF8 .cache\b3-numbering-fixture.sql | mysql --host=127.0.0.1 --user=root -D rad_oa --default-character-set=utf8mb4

# 取号（带 token）
Invoke-RestMethod "http://127.0.0.1:8080/serial/config/genSerialNo/<confId>" -Headers @{ Authorization = "Bearer <token>" }

# 看真正的计数器
redis-cli keys "code:gen:seq:*" ; redis-cli get "code:gen:seq:<confId>"
```

> 夹具与验证产生的编号流水（`code LIKE 'PURZC%'`）在验证结束后**已全部清理**，库内无残留。

---

# §1.3 扩展实施与验收记录（2026-10-05）

依据上一节的结论，对 `ruoyi-serial` 做了**最小扩展**（design.md D-6 的扩展分支），并已通过验收。

## 1. 改了什么（面很小，全部是**加法**）

| 文件 | 动作 |
| --- | --- |
| `enums/RuleTypeEnum.java` | 新增枚举值 `PARAM("3","业务参数")`（`ruleValue` = 参数名） |
| `module/CodeGenContext.java` | **新增**：取号上下文（`referenceDate` 参考日期 + `params` 业务参数） |
| `module/CodeGenRequest.java` | **新增**：上下文的 HTTP 入参（参考日期用字符串承接，避开全局日期格式） |
| `support/CodeRenderSupport.java` | **新增**：渲染与分桶的**纯函数**（可表驱动单测） |
| `api/ICodeGenService.java` | 新增 `getNextCode(confId, ctx)` 与 `previewNextCode(confId, ctx)`；**老方法签名与语义不变** |
| `api/impl/CodeGenServiceImpl.java` | 取号编排改为走纯函数；新增预览；补日志（见 §3 的坑 3） |
| `controller/CodeConfigController.java` | 新增 `POST /genSerialNo/{confId}`（带上下文）与 `POST /previewSerialNo/{confId}`；老 GET 原样保留 |
| 前端 `views/serial/config/index.vue` | 规则类型下拉新增「业务参数」+ 提示/示例（否则管理员配不出来） |

**向后兼容的做法**（这是本扩展的硬约束）：
- `context == null`（或没有业务参数）时，计数器键仍是 `code:gen:seq:<confId>`、初值仍取 `t_code_config.current_seq`、
  仍回写 `current_seq`、日期规则仍取当天 —— 与扩展前**逐字一致**；
- 只有**传了业务参数**才启用分桶键 `code:gen:seq:<confId>:<k=v…>:y<年>`，
  且分桶路径**不回写** `current_seq`（单列无法表达多桶）；
- 新增枚举值不影响老配置（它们只用 0/1/2）。

## 2. 验收结果

**① 单元测试**（新增，`mvn -B -pl ruoyi-serial test` → **24 条全绿**）：

| 测试类 | 条数 | 覆盖 |
| --- | --- | --- |
| `support/CodeRenderSupportTest` | 13 | 判定 ①（参考日期）、②（年份进键/不进键）、③（类型码/主体码分桶、传参顺序无关）、业务参数缺失报错、**向后兼容**、既有日期格式串、不补零 |
| `api/impl/CodeGenServiceImplTest` | 11 | 老入口整条编排（形态/键/回写/留痕）、新入口（不回写）、跨年换桶、预览不占号不取锁不写流水、**计数器初值必须是 Integer**、配置缺失/空 confId 报错 |

**② 真实环境回归**（新增脚本 `tools/serial-numbering-check.ps1` → **32 条全绿**）：

```
① 签订 2025-06-01 → PURZC202506000001；同桶再取 → …000002；签订 2027-01-01 → PURZC202701000001
② 2027 桶首号 = 000001（该桶从未被取过号）；取过 2027 的号后 2025 桶不受影响（→ …000003）
   Redis 里出现 2 个带 :y2025 / :y2027 的桶；分桶路径不回写 current_seq
③ 只换主体码 → PURXS202506000001；只换类型码 → SALZC202506000001；回到 PUR/ZC → …000004（共 4 个桶）
④ 连续两次预览同为 PURPAY202506000001；未创建计数器、未写流水；随后取号正是该值
⑤ 既有形态：YX<yyyyMMdd>01/02/03、三次后 current_seq=3、删 Redis 键后从 DB 继续得 …04、
   4 条流水、老 GET 入口返回 …05 —— 与「改动前基线」（.cache\b3-legacy-baseline.txt）逐项一致
⑥ 缺业务参数 → 「编号规则缺少业务参数：subjectCode」；非法参考日期 → 「参考日期格式错误，应为 yyyy-MM-dd」
```

**③ 构建**：`mvn -B -DskipTests -pl ruoyi-serial install` 与 `-pl ruoyi-admin clean package` 均 BUILD SUCCESS，
fat jar 时间戳 `2026-10-05 08:33:28`（打包前已停后端）。

## 3. 实施期踩到的三个坑（已写进 DEV-ENV §6）

1. **`Long` 会被 FastJson2 写成 `0L`，Redis 的 `INCR` 直接失败**
   （`ERR value is not an integer or out of range`）——
   计数器初值从 `getCurrentSeq()`（Integer）被"顺手"改成 `long` 之后，**所有取号 500、而预览却完全正常**
   （预览不 INCR）。这是本次最隐蔽的一个：单测全绿（桩里不涉及序列化）、静态审计全绿，只有真实环境才暴露。
   已加两条回归守卫（纯函数层 + 服务层各一条，断言初值装箱类型必须是 `Integer`）。
2. **Javadoc 里出现 `*/` 会提前结束注释**：写 `{@code specs/ctms/*/spec.md}` 时，
   其中的 `*/` 把注释就地关闭，后面全被当成代码 → `javac` 报
   `class, interface, or enum expected` + 一个"意外的 `：`"。改成 `&lt;能力&gt;` 即可。
3. **原实现把异常链丢了**：`catch (Exception e) { throw new BaseException("获取编号失败！") }`，
   排查时只能看到这句没有信息量的话。已补 `log.error(..., e)` —— 正是它让上面第 1 个坑当场定位
   （日志里直接看到 `INCRBY ... ERR value is not an integer`）。

