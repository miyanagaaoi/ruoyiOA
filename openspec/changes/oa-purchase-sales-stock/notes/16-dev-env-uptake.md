# 16 · DEV-ENV 收编记录（t35）

> 任务：`t35`｜执行：backend-base｜日期：**2026-10-05**｜范围：仅 `DEV-ENV.md` + 本 notes 文件
> 目的：把本轮交付里最有复用价值的**三条新坑**写进 `DEV-ENV.md §6`（第 ④ 条按验收要求只做交叉引用、不重复写），
> 并修正 `§7` 门禁表的**过时计数**、**错误的运行锁说明**，补登记**三个新脚本**。

---

## 1. diff 摘要（`DEV-ENV.md`）

`git diff --stat DEV-ENV.md` → **1 file changed, 182 insertions(+), 1 deletion(-)**；其中：

| 来源 | 规模 | 位置（改后行号） |
| --- | --- | --- |
| backend-ledger（t10，本任务之前写入、我**未动**） | +110 | `§6` 第 52/53 条一带（行 868 起）+ `§7` 第 16 条 |
| **本任务（t35）** | **+72 / −1** | 见下表 |

| 位置 | 动作 | 行数 | 内容 |
| --- | --- | --- | --- |
| `§6` 第 **58** 条 | 新增 | 36（977-1012） | MyBatis **惰性 `AmbiguousMethodInvoker`** + **版本偏斜**（3.5.13 容忍 vs 3.5.7 不容忍）+ "守卫测试必须读遍全部属性" + 反向对照 |
| `§6` 第 **59** 条 | 新增 | 17（1013-1029） | 编辑 `.ps1` 不带 BOM 会**静默剥掉** BOM ⇒ PS 5.1 按 ANSI 读 ⇒ 中文乱码/引号破坏 |
| `§6` 第 **60** 条 | 新增 | 15（1030-1044） | **编译红污染其它组断言**（`erp` 包的类枚举/源码级守卫测试 `Class.forName` 必炸）⇒ 先确认 `test-compile` 绿 |
| `§7` 第 **14** 条 | 修改 | 1 行 | 后端单测计数 **174 → 535**（并写明只增不减的 174 → 531（t29）→ 535（t31）） |
| `§7` 第 **16** 条 | 修改 | 1 行内嵌更正 | 删掉不成立的"**有运行锁**，不可并发"，改为"**脚本内没有锁实现**（grep 0 命中）⇒ 必须经 `locked-run -LockName env` 串行" |
| `§7` 第 **17/18/19** 条 | 新增 | 3 行 | 登记 `tools\erp-smoke.ps1`、`tools\erp-check.ps1`、`tools\flow-form-consistency-check.ps1` |

**未改**：`§1`–`§5`、`§6` 第 1–57 条（含 backend-ledger 刚加的 52/53 条）、`§7` 第 1–13/15 条、`§8`–`§11`。
`§6` 编号顺延（57 → 58/59/60），`§7` 表格仍是「顺序｜命令｜覆盖｜说明」四列。

**文件编码**：`DEV-ENV.md` 落盘为 **UTF-8 无 BOM**（`.md` 规矩）。⚠ 见 §4「BOM 口径与一次并发写」。

---

## 2. 四条要求的落点与出处（逐条可复核）

### ① MyBatis 惰性歧义 + 版本偏斜（§6.58，最重要）

- 异常**惰性抛**：建 `Reflector` 不抛（属性被包成 `AmbiguousMethodInvoker`），**真读属性**才抛；
  触发点 `ruoyi-vue-oa-master/ruoyi-ctms/src/main/resources/mapper/erp/ErpPurRequestMapper.xml:116`
  的 `<if test="posted != null and posted != ''">`（OGNL）→ `BeanWrapper.getBeanProperty`。
- **版本偏斜**：模块测试类路径解析到 mybatis **3.5.13**（容忍，`posted` 解析成 `String`）、
  打包件 `ruoyi-admin.jar` 的 `BOOT-INF/lib/mybatis-3.5.7.jar` 是 **3.5.7**（不容忍）。
  `.cache\t1-cp.txt` 是 t1 当天生成、**已过期**，不能代表当前测试类路径（写出时已注明）。
- **守卫测试口径**：`ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/base/ErpDomainReflectorTest.java`
  扫 `target/classes/com/ruoyi/ctms/erp` 全部 `.class`（160 类 / **1306 个属性**），
  逐个读取 + 与版本无关的静态口径；`SCAN_FLOOR=40` 防"扫不到也绿"。
  反向对照：修复前 `Tests run: 4, Failures: 3` → 修复后 `4/4`。
- 出处：`notes/01-base.md §8`、`.cache/t31/prefix-test-red.log`、`.cache/t31/prefix-trigger-3.5.7.log`、
  `.cache/t31/postfix-trigger-3.5.7.log`、`.cache/t31/sibling-scan-all.log`、`.cache/t31/sibling-scan-postfix.log`。
- **第 ④ 条（交叉引用，不重复写）**：§6.58 正文里已引用 **§6.52**（判据自己错了会双双报绿）与
  **§6.56**（认证失败 HTTP 200 + body code=401）——三条是"判据必须能因回归而变红"的同一教训；
  本次**没有**新增 401 的重复条目。

### ② `.ps1` 的 BOM（§6.59）

- 判据两条：① 复读前 3 字节必须 `239,187,191`；② `[Parser]::ParseFile` 的 `$errs.Count -eq 0`（不执行即可抓解析错）。
- 反面写清：`.md` 无 BOM、`.vue` UTF-8 无 BOM + CRLF、`.ps1` UTF-8 **有** BOM；`-Encoding UTF8` 在 PS 5.1 与 7+ 语义相反。
- 出处：`§6.33`（同族）、`tools\erp-check.ps1` 头部 ⚠ 第 1 条、
  `tools\flow-form-consistency-check.ps1` 头部同类提醒、`.cache\t2-masterdata-drill.ps1`（补 BOM 后解析通过）。

### ③ 编译红污染其它组断言（§6.60）

- 两条跨全包守卫测试会让"别人的编译错误"表现为"我这组的断言红"：
  1. `ErpPrecisionTest`（`src/main/java/com/ruoyi/ctms/erp` 源码树 → 映射类名 → `Class.forName`，见该类 `:39,48-64,159-176`）；
  2. `ErpDomainReflectorTest`（扫 `target/classes/com/ruoyi/ctms/erp`）。
- 判据：先 `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test-compile`（**实测 9.8s，退出码 0**）再读业务断言；
  错误行号落在**别人的在途文件**时按简报 §3.1 只报文不代改。
- 出处：`.cache\t31\prefix-test-red.log`、t1/t2/t23/t29 的过程记录（多次"编译红 → 非本组文件"）。

### ④ 交叉引用（见上 ① 末段）

验收原文：「与既有 `§6.56` … 交叉引用即可，不要重复写」⇒ 已在 §6.58 正文引用 §6.52/§6.56，**未新增重复条目**。

---

## 3. `§7` 门禁表的三处更新（含一处**既有错误**更正）

1. **第 14 条计数**：`**174 条**` → `**535 条**`（实测来源：t29 报告 531 → t31 报告 **Tests run: 535, Failures: 0, BUILD SUCCESS**，日志 `.cache\t31\postfix-full.log`）。
2. **第 16 条运行锁**：原文写"**有运行锁**，不可并发"，**与脚本实现不符** ——
   2026-10-05 复核 `tools\erp-scope-check.ps1`（391 行）内 `grep -i "lock|locked-run"` **0 命中**；
   而同类脚本确实有 `.cache\<脚本>.lock` 锁文件：
   `ctms-contract-check.ps1:320`、`ctms-attachment-check.ps1:223`、`ctms-commercials-check.ps1:184`、
   `ctms-e2e-check.ps1`、`ctms-migration-check.ps1`。
   ⇒ 已改为"脚本内没有锁实现，必须经 `tools\locked-run.ps1 -LockName env` 串行执行"（该脚本会借 `common` 角色改授权与 `data_scope`，并发会互相覆盖并留残留）。
3. **新增第 17/18/19 条**（语义按各自文件头注释核对，非推测）：

| 条 | 脚本 | 关键口径（核对来源） |
| --- | --- | --- |
| 17 | `tools\erp-smoke.ps1`（273 行） | **只读** 27 条探针，不建业务数据；默认有 FAIL 也退出码 0、`-Strict` 时 1；判 HTTP + body.code 双证（文件头纪律 ①②③） |
| 18 | `tools\erp-check.ps1`（913 行） | P-/A-/B-/S-/T-/L-/E2E- 分段；`-Selftest` 自证判别力、`-Only <前缀>`；退出码 0/1/2；**SKIP 单独计数不算 PASS**；夹具自建自清 + 零残留断言 |
| 19 | `tools\flow-form-consistency-check.ps1`（298 行） | 只读巡检 `GET /workflow/simple-flow/form-consistency` + `T16PROBE-` 探针（不发布流程、不产生 `ACT_*`）；`-OnlyReadOnly`/`-KeepProbeFixtures`；判 body.code（§6.56） |

---

## 4. 两条过程事实（不隐瞒）

1. **BOM 口径与一次并发写**：任务派单的验收里同时写着"§6 新增条目（保留 UTF-8 BOM）"与"`DEV-ENV.md` 保持**无 BOM**（.md 规矩）"——
   二者冲突。我的处置：文件在**首次字节检查**时是无 BOM（前 4 字节 `23 20 52 75`），
   而在**写入前的那次读取**时字符串以 `U+FEFF` 开头（说明期间**另有写入**把它变成了带 BOM 的状态，可能是重复 BOM）；
   我按 `.md` 规矩落盘为**无 BOM**（剥离一个前导 `U+FEFF`，无内容丢失：diff 行数与自身改动量吻合 +72/−1）。
   ⇒ 若 captain 确认要"保留 BOM"，一条命令即可回退（`UTF8Encoding($true)` 重写）。
2. **并发写风险已发生**：`DEV-ENV.md` 在我开工前 4.5 分钟（23:49:36）被 **backend-ledger**（t10，当时 idle）写入 +110 行
   （`§6` 两条 + `§7` 第 16 条）；team 快照确认其当时**已 idle**、无在跑写手，因此我按"本任务独占"继续。
   其 110 行**原样保留**（我只在其后追加/更正）。
   ⇒ 建议 captain 在 t13 之后**明确 DEV-ENV.md 的单一写者**（避免 t13/t10/t35 再撞）。

---

## 5. 复核命令（可复跑）

```powershell
cd F:\dsh\ruoyiOA
git diff --stat -- DEV-ENV.md                      # 182 insertions / 1 deletion（含 backend-ledger 的 110 行）
([System.IO.File]::ReadAllBytes('DEV-ENV.md')[0..2] -join ',') -eq '239,187,191'   # 预期 False（无 BOM）
Select-String -Path DEV-ENV.md -Pattern '^58\. \*\*|^59\. \*\*|^60\. \*\*|^\| 1[4-9] \|'
(Get-Content DEV-ENV.md -Encoding UTF8)[1075]      # 第 14 条：535 条
(Get-Content DEV-ENV.md -Encoding UTF8)[1077]      # 第 16 条：脚本内没有锁实现
```

**实测结果**：`58/59/60` 在第 977/1013/1030 行、`## 7` 在第 1045 行；`§7` 第 14–19 条在 1076–1081 行；
文件 1356 → **1427 行**（+71 = 68 行 §6 + 3 行 §7 新增，另有 2 行内嵌更正）；**无 BOM**。
