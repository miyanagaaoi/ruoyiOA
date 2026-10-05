# 13a · 打包冒烟与接口验收脚本（t26 写脚本 · t20 跑打包冒烟 · t13 跑验收）

> 本文件是**脚本的使用说明 + 真实输出 + 契约清单 + 待补段落**。
> t26 落地脚本（不要求当时全绿）；**t20 已完成统一打包重启 + 只读冒烟**（见 §7）；t13 继续跑 `erp-check.ps1`。

---

## 0. 本轮最重要的结论（阻塞项，请先看这条）

**`erp-smoke.ps1` 与 `erp-check.ps1` 都指向同一个后端缺陷，且它是"B4 写路径整体不可用"级别的：**

```
POST /stk/in-order           → HTTP 200 / body code=500
msg = nested exception is org.apache.ibatis.reflection.ReflectionException:
      Illegal overloaded getter method with ambiguous type for property 'posted'
      in class com.ruoyi.ctms.erp.base.domain.ErpDocHeader.
      This breaks the JavaBeans specification and can cause unpredictable results.

GET /erp/pur/request/list    → 同一条异常（erp-smoke 的 S01）
```

> ✅ **t20 复核（2026-10-05 23:37 重新打包 + 重启后复跑）：仍然复现，不是旧 jar**。
> 新 jar 时间戳 `2026/10/05 23:37:57`（`mvn -B -pl ruoyi-ctms clean install` 510/510 绿 → `mvn -B -DskipTests -pl ruoyi-admin clean package`）；
> 重启后 `GET /erp/pur/request/list` 依旧 code=500（S01），`POST /stk/in-order` 单跑 P 段依旧 code=500（同一异常）。
> ⚠ **为什么 510 条单测全绿却仍在线挂**：单测用的是**内存桩**（不经过 MyBatis），
> 这条异常只出现在 **MyBatis 参数/结果反射**这一步 ⇒ 单测**结构性地看不到**它。
> 这正是 t20「真实接口冒烟」不可被单测替代的原因，也是 t13 必须先把 P 段打绿再谈门禁全表的原因。

**判据与影响面（已实测，不是推断）**

| 证据 | 内容 |
| --- | --- |
| 触发点 | `POST /stk/in-order`（erp-check `P-01`）、`POST /erp/pur/request`（`A-A1`）、`POST /stk/transfer`（`T-EXC` 前的 T-01）、`POST /sal/order`（`S-B3`）**全部 code=500**；`GET /erp/pur/request/list` 同样 500 |
| 未触发 | `GET /stk/in-order/list`、`/stk/take/list`、`/stk/transfer/list`、`/sal/*/list`、`/erp/pur/order/list`、`/ctms/*/options`、`/ctms/attachment/object-types` 都 200（列表里**不引用** `posted` 属性的那些查询没被反射到） |
| 根因（MyBatis 反射口径） | `ErpDocHeader` 同时存在 `getPosted()`（String，`t_ctms_*.posted char(1)`）与 `isPosted()`（boolean，便捷判定）。JavaBeans 里这是**同一个属性 `posted` 的两种类型**：MyBatis 建 `Reflector` 时只要有人按属性名取 `posted`（insert 的 `#{posted}`、列表 XML 的 `posted` 条件）就抛异常 |
| 建议修法（属公共层，由 captain 派单） | ① 把 boolean 改名为非 bean 形式（如 `postedIsYes()` / `isPostedFlag()`）并改调用点；或 ② 去掉 `getPosted()` 的 bean 语义（`@JsonIgnore` 不能解决 MyBatis 侧）；③ 只保留其一。**无论哪种，改的是 `base` 包 → 影响 8 类单据，需一次改净** |
| 影响 | 8 类单据的**新增/编辑/审核等所有写路径**（凡参数里带 `posted` 的 XML）不可用 ⇒ E2E 无法开始。当前 `erp-check.ps1` 的 16 条失败里 **3 条段落异常 + 大部分 FAIL 都是这一条的派生**（没有单据就造不出结存/流水/回写） |

> 这条是 t20 的「契约不一致清单」第 1 条，也是 t13 需要先看到的前置；`notes/13a` 记在此处便于派单时直接引用。

---

## 1. 两个脚本的用法

```powershell
# t20：只读冒烟（不建任何业务数据；未打包/404 属预期）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1 -Strict    # 有 FAIL 时退出码 1

# t13：接口验收（自建夹具 + 收尾清理 + 自证）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1 -Selftest   # 证明脚本有判别力
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1 -Only T-    # 只跑某前缀
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1 -KeepFixtures  # 排障：保留夹具
```

前置（两个脚本相同）：`start-env.ps1`（后端 8080）+ `tools\oa-login.ps1`（`.cache\token-<user>.txt`）；
`erp-check.ps1` 还需要 `superAdmin` 与 `zhangwei` 两个 token（403 用例）与 mysql 客户端。

退出码：`erp-smoke.ps1` → 0 = 跑完（默认）、1 = `-Strict` 且有 FAIL、2 = 后端未在线；
`erp-check.ps1` → 0 = 全绿或自证通过、1 = 有 FAIL、2 = 前置不满足。

**只读纪律**：`erp-smoke.ps1` 只发 GET 与"默认不修复"的 `POST /stk/stock/recalc`（`repair` 缺省 false）；
它**不建任何业务数据**，可在 E2E 之前随便跑。

---

## 2. 探针 / 断言编号与前缀约定

### 2.1 `erp-smoke.ps1`：`S01` ~ `S27`

| 段 | 编号 | 覆盖 |
| --- | --- | --- |
| A 8 类单据列表 | S01~S08 | `/erp/pur/request/list`、`/erp/pur/order/list`、`/sal/request/list`、`/sal/order/list`、`/stk/in-order/list`、`/stk/out-order/list`、`/stk/take/list`、`/stk/transfer/list` |
| B 库存账 | S09~S15 | `/stk/stock/list`（含筛选参数名）、`/stk/stock/detail?productId&warehouseId`、`POST /stk/stock/recalc`、`/stk/ledger/list`（含筛选）、`/stk/ledger/detail?id` |
| C 主数据选择器 | S16~S18 | `/ctms/product/options`、`/ctms/warehouse/options`、`/ctms/uom/options` |
| D 附件注册表 | S19 | `/ctms/attachment/object-types`：`registered` **恰好 9 项**（contract + 8 类单据，逐项核对） |
| E 动作路由形态 | S20~S27 | 每类单据各一条：采购/销售 `PUT {base}/{id}/submit`；入库/出库 `POST {base}/submit/{id}`；盘点/调拨 `POST {base}/{id}/submit`。传不存在的 id，**期望"业务层拒绝"**；HTTP 404/405 才算契约问题 |

> 探针的 `{id}` **必须只含 `[A-Za-z0-9]`**：8 类单据的动作路由把 id 约束成了 `[A-Za-z0-9]+`，
> 用 `__probe__` 这类带下划线的 id 会匹配不上路由从而 404 —— 那是探针的假失败（第一版踩过）。

### 2.2 `erp-check.ps1`：段落前缀 ↔ 来源 notes

| 前缀 | 条数 | 来源 | 说明 |
| --- | --- | --- | --- |
| `FIX-` | 10 | 本脚本 | 夹具自建（单位/仓库/类型/物料/供应商/客户） |
| `P-` | 23 | `notes/03-posting.md §8` | 审核即过账、幂等、缺仓库、负库存、红冲、并发（P-15/P-16）、不变式、1062、complete 拒绝、不可编辑、列表口径、导出（P-23 待补） |
| `A-` | 32 | `notes/04a-procurement.md §5.1` | 采购线 A/B/C/D/E 段（C 段下推、D 段合同本轮 SKIP，理由见 §4） |
| `B-` | 16 | `notes/04b-procurement-push-in.md §8` | 采购→入库下推与过账回写（由 `E2E-PUR-*` 覆盖主链路，编号级待补） |
| `S-` | 24 | `notes/05a-sales.md §5` | 销售线 A/B 段已落地；C（下推，t8）、D（合同）SKIP |
| `T-` | 23 | `notes/06-stockops.md §8` | 调拨两流水/单价 0/排序取锁/盘点生成/盘盈回填/级联（条目与编号逐条对应） |
| `L-` | 16 | `notes/07-ledger.md §7` | 结存明细/筛选/子树/流水/`qtyAfter`/recalc 三态/不变式 |
| `E2E-` | 6 | `tasks.md §9` + 各组 §端到端 | `E2E-PUR-1..4`（申请→采购单→入库单→回写→红冲）、`E2E-TR`、`E2E-ST`、`E2E-SAL`（t8 后补）、`E2E-CONC` |
| `SELF-` | 3 | `-Selftest` | 判别力自证：2 真 1 假，假的必须被计入 FAIL |
| `CLEAN-01` | 1 | 本脚本 | 夹具零残留（档案按 `code` 前缀 + 单据按**被跟踪 id**） |

**打印纪律**：每条都打 `[PASS]/[FAIL]/[SKIP] + 编号 + 名称`，并紧跟 `期望：… ｜ 实际：…`。
`SKIP` **不是通过**：单独计数、单独列清单（`SkipCase` 必须给理由）。
失败时退出码 1；`-Selftest` 通过时退出码 0（自证目标是"证明能变红"，不是"跑出红"）。

---

## 3. 本轮真实输出（后端在联调，未打包完整）

### 3.1 `erp-smoke.ps1`

```
==> 只读冒烟：http://localhost:8080（user=superAdmin）
    共 27 条探针；不建任何业务数据
    [FAIL] [S01] GET /erp/pur/request/list?pageNum=1&pageSize=1 采购申请单列表 → HTTP 200 / code=500（posted 反射异常，见 §0）
    …（S02~S27 全 OK：8 类列表 7/8、库存账 7/7、主数据 3/3、附件 9 项 ✓、8 条动作路由全部"业务层拒绝"而非 404）
==> 冒烟结论
    探针 27 条：通过 26 / 失败 1 / 跳过 0
```

首行结论（t20 可直接抄）：**探针 27 条 → 26 通过 / 1 失败（S01 采购申请单列表因 `posted` 反射异常 500）**。

### 3.2 `erp-check.ps1`

```
==> 验收结论
    通过 25 / 失败 16 / 跳过 20
    （失败 = §0 的阻塞项 + 它的派生：P-01/A-A1/T-01/S-B3 建单 500 → 结存/流水/回写/额度类断言无数据可查）
```

### 3.3 `erp-check.ps1 -Selftest`

```
    [PASS] SELF-01 必然成立（1 = 1）
    [PASS] SELF-02 必然成立（code=401 被识别为失败）
    [FAIL] SELF-03 故意构造的必失败断言（期望它被计入 FAIL）
==> SELFTEST OK：人为失败被计入 FAIL（1 条），框架有判别力          （exit 0）
```

原始输出：`.cache/t26/smoke-run2.txt`、`.cache/t26/check-full2.txt`、`.cache/t26/selftest1.txt`。

---

## 4. 待补段落（明确列出；t20/t13 按此收口）

| 段落 | 待补什么 | 等谁 |
| --- | --- | --- |
| `S-C1..C7`（销售下推与归零 7 条） | 销售申请→订单下推端点与"归零即完成"的接口断言 | **t8（05b）交付后** |
| `E2E-SAL` | 销售链路（申请→订单→出库单→过账→红冲） | 同上 |
| `A-C1..C8` / `B-1..B-14`（编号级） | 采购下推的逐条编号断言（主链路已由 `E2E-PUR-1..4` 覆盖） | t13（现可做） |
| `A-D1..D4` / `S-D1..D5`（合同） | 需要 B3 合同夹具（采购方向 / 销售方向 / 已停用）。脚本**不擅造合同**（避免污染 B3 基线）：t13 用现有合同，或先按 B3 接口建临时合同并在收尾删除 | t13 |
| `S-B2` | 需要"停用客户"夹具（可建后停用） | t13 |
| `P-23` / `L-12` | 导出 xlsx：需要下载二进制 + 解析行数（两处共用同一辅助函数） | t13 |
| `L-01` | 无 token 访问（401）需要裸 HTTP 客户端 | t13 |
| `L-04` / `L-05` / `L-07` / `L-08` | 安全库存夹具、区间边界夹具、`displayName` 格式、额度口径（红冲归零/调拨不计额度） | t13 |
| `L-09` | 未审核→已审核→打印→导出的金额一致 | t14（打印走浏览器） |
| `L-11` / `L-16` | 流水 detail 403；四档数据范围矩阵 | t13 / t10 |
| `T-16` / `T-17` | 盘亏豁免与"无差异不生成"的**编号级**断言（`E2E-ST` 已覆盖盘亏豁免主路径） | t13 可补 |
| `T-21..T-23` | 盘点/调拨导出、权限 403、调拨附件上传/下载/删除（multipart 夹具） | t13 |
| `B-15` / `B-16` | 入库缺仓库的拒绝形态（依 T3 实现现状）；`received_qty` 合计恒等式 | t13 |

---

## 5. 本轮踩到并已处理的四个工具坑（写新脚本时别再踩）

1. **`.ps1` 必须带 UTF-8 BOM**：PowerShell 5.1 对无 BOM 的脚本按 ANSI 读，中文注释/字符串变乱码并**破坏引号**
   （症状是"`&` 运算符保留给将来使用"这类**解析错误**）。本仓既有 `tools\ctms-*.ps1` 都带 BOM。
   ⚠ 用编辑器/工具改写后要**重新写回 BOM**（本脚本上方注释已写明）。
2. **参数模式里的 `+` 不是加法**：`Api 'GET' '/x?' + $Id $null` 会被解析成 5 个参数
   （`'+'`、`$Id` 都成了位置参数），表现为"token 文件找不到：token-<某个 UUID>.txt"。
   一律用字符串插值 `Api 'GET' "/x?p=$Id" $null`。
3. **`{id}` 路由约束是 `[A-Za-z0-9]+`**：探针用带下划线的假 id 会 404（假失败）。
4. **认证失败是 HTTP 200 + body `code=401`**（`DEV-ENV §6.56`）：判据一律看 **body 的 code**，
   不能只看 `Invoke-RestMethod` 抛不抛异常；失败时脚本**原样打印 body**。

另外两条本轮确认的**接口事实**（写断言时用）：
- `/stk/stock/detail` 的真实参数是 **`productId` + `warehouseId`**（不是 `id`）；
- `/ctms/attachment/object-types` 的注册项在响应**根**上的 `registered`（不在 `data` 里），当前 **9 项**。

---

## 6. 给 t20 / t13 的收口动作

- **t20**：✅ 已完成（见 §7）；本节的收口动作转为 **t13** 的输入。
- **t13**：先修 §0 阻塞项（属公共层，等派单），再按 §4 逐条补齐 SKIP 段落，最后把 `erp-check.ps1`
  的「通过/失败/跳过」与 `CLEAN-01` 零残留一起写进交付评审。

---

# 7. t20：统一打包重启 + B4 只读冒烟（真实输出）

> 结论先行：**打包重启成功、B4 端点全部在线（无 404/405）**；
> 唯一失败项 `S01` 是 §0 的真缺陷（且已复核为新包复现）。契约不一致清单见 §7.4。

## 7.1 打包与重启（`DEV-ENV §6.13` 口径）

| 步骤 | 命令 / 动作 | 结果 |
| --- | --- | --- |
| 停后端 | 只停 8080 的 java（**不动** MySQL/Redis/RabbitMQ/前端） | 原 PID 19700 终止，`PORT-8080-FREE` |
| ① 装依赖 | `mvn -B -pl ruoyi-ctms clean install`（build 锁内） | **BUILD SUCCESS**，`Tests run: 510, Failures: 0, Errors: 0`，20.4s |
| ② 打 fat jar | `mvn -B -DskipTests -pl ruoyi-admin clean package`（build 锁内） | **BUILD SUCCESS**，9.0s |
| ③ 核对 jar | `ruoyi-admin\target\ruoyi-admin.jar` | **LastWriteTime = 2026/10/05 23:37:57**（本次构建；打包前是 23:15:07）、176.1 MB |
| ④ 脱离进程启动 | `start-env.ps1 -Only Backend`（内部经 cmd 包装 + 日志重定向，避免继承句柄） | 8080 就绪；**新 PID 26280**（`java.exe`，启动时刻 23:38:09），日志 `logs\backend-run.log` |
| ⑤ 登录 | `tools\oa-login.ps1` | **登录成功 5 个 / 失败 0**；抽验 `/workflow/simple-flow/list` code=200 |

> 锁与并发的说明（写给 t13）：本仓另一个成员当时正在跑 maven，所以我把 **maven 两步放在 `-LockName build`**、
> **停/启后端与接口脚本放在 `-LockName env`**（而不是把全部塞进 env）—— 目的是既不互相踩 `target/`，
> 也不让两个 env 操作重叠。两次锁定均无超时。

## 7.2 逐端点冒烟（`tools\erp-smoke.ps1 -Strict`，只读、不建业务数据）

**探针 27 条 → 通过 26 / 失败 1 / 跳过 0**（首行结论可读；原始输出 `.cache/t20/smoke-after-package.txt`）

| 组 | 编号 | 结果 | 备注（body code / 关键字段） |
| --- | --- | --- | --- |
| 8 类单据列表 | S01 采购申请单 | ❌ **code=500** | §0 的 `posted` 反射异常（唯一失败） |
| | S02 采购单 / S03 销售申请 / S04 销售订单 / S05 入库 / S06 出库 / S07 盘点 / S08 调拨 | ✅ | 全部 `code=200 total=0 rows=0` |
| 库存账 | S09 `/stk/stock/list`、S10 带筛选、S12 `/stk/stock/recalc` | ✅ | S12 返回 `inconsistentCount=0`（空库自洽） |
| | S11 `/stk/stock/detail?productId&warehouseId` | ✅ | `code=404 库存记录不存在`（**参数名是 productId+warehouseId，不是 id**） |
| | S13 `/stk/ledger/list`、S14 带筛选、S15 `/stk/ledger/detail?id` | ✅ | S15 `code=404 库存流水不存在` |
| 主数据选择器 | S16 `/ctms/product/options`、S17 `/ctms/warehouse/options`、S18 `/ctms/uom/options` | ✅ | 均 `code=200`，`data=0 项`（库中暂无启用档案） |
| 附件注册表 | S19 `/ctms/attachment/object-types` | ✅ | 响应根 `registered` = **9 项**（contract + 8 类单据，逐项核对） |
| 动作路由形态 | S20~S27 | ✅ | 8 类单据的动作路由**都存在**且由业务层拒绝（`code=500 单据不存在`），**无 404/405**：采购/销售 `PUT {id}/action`、出入库 `POST {action}/{id}`、盘点/调拨 `POST {id}/action` ✓ 与前端的 `actionRequest()` 三模式一致 |

## 7.3 写路径的复核探针（超出"只读"范围，但**零残留**）

只读冒烟证明不了"写路径"，所以补跑了一次 `erp-check.ps1 -Only P-`（该脚本**自建夹具 + 收尾清理 + 零残留断言**）：

| 项 | 结果 |
| --- | --- |
| `P-01` `POST /stk/in-order`（合法仓库/物料夹具） | ❌ `code=500`，**同一 `posted` ReflectionException** ⇒ 新包上写路径仍不可用 |
| 收尾 | 夹具全部删除；SQL 核对 `product/warehouse/uom/product_type/supplier/customer = 0`、`t_ctms_stock_in = 0`、`t_ctms_stock = 0` ⇒ **零残留** |

## 7.4 契约不一致清单（交 captain 派单）

| # | 级别 | 现象（期望 / 实际） | 位置（file:line） | 建议 |
| --- | --- | --- | --- | --- |
| C-1 | **blocker** | 期望：8 类单据能建单 / 实际：`code=500` `Illegal overloaded getter method with ambiguous type for property 'posted'`；影响 **所有写路径** + `/erp/pur/request/list` | `ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/base/domain/ErpDocHeader.java`：`isPosted()`（约 :164）与 `getPosted()`（约 :399）同属性两类型；被 `ErpPurRequestMapper.xml` 的 `posted` 条件与各 `insert*` 的 `#{posted}` 反射到 | 公共层一次改净：boolean 改名为非 bean 形式（或去掉其 bean 语义）并改调用点；**改动会影响 8 类单据**，建议单任务 + 全模块回归 |
| C-2 | info（非缺陷） | 期望（我最初的假设）：`/stk/stock/detail?id=` / 实际：真实参数是 **`productId` + `warehouseId`**（缺参会 500「Required request parameter 'productId'」） | `erp/ledger/controller/ErpStockBalanceController.java`（`/detail` 参数） | 给 t12 的库存明细页对齐参数名；`erp-check.ps1` 的 `L-*` 已按真实参数写 |
| C-3 | info（非缺陷） | `/ctms/attachment/object-types` 的注册表在响应**根**的 `registered`（不在 `data` 里），当前 9 项 | `ctms/controller/CtmsAttachmentController.java`（`/object-types`） | 前端取 `res.registered`；t26 的 S19 已容错两种形状 |
| C-4 | ✅ 已核对无问题 | 8 类单据 + 库存账两页的**动作/列表路由形态**与前端 `actionRequest()` 三模式**逐条一致**（无 404/405） | `src/api/erp/doc.js`、`views/erp/doc/doc-kinds.js` | 无需动作 |
| C-5 | ✅ 已核对无问题 | 低权账号 `zhangwei` 访问 8 类列表 + `/stk/stock/list` + `/stk/ledger/list` **全部 `code=403 没有权限，请联系管理员授权`** ⇒ `@PreAuthorize` 权限点已生效（菜单 SQL 是否已建由 t10 核对） | 各 Controller 的 `@PreAuthorize` | 无需动作 |

## 7.5 给下游的可联调结论

| 谁 | 现在可以做什么 | 阻塞点 |
| --- | --- | --- |
| t12（前端联调） | 8 类列表/详情/选择器/附件注册表/库存账两页**都可对真实接口联调**（全 200）；动作路由形态已核对一致 | **建单/编辑/审核（写路径）会 500**（C-1）→ 只能先联列表与只读页 |
| t10（§8.2 数据范围实测） | 权限点 403 已可用；四档账号矩阵可开跑（只读面齐全） | 造数（单据/结存/流水）受 C-1 阻塞 |
| t14（E2E） | —— | **必须先修 C-1**，否则任何"建单"用例都跑不到 |

---

# 8. t34：C-1 修复后的重建与写路径复证（真实输出，2026-10-06 00:0x）

> 结论三段：**① C-1 确实消除了**（`erp-smoke -Strict` 27/27，S01 由 500 → 200）；
> **② 写路径仍被第二个缺陷挡住**（`warehouse_name` 未回填，已归口 **t33**）；
> **③ 夹具清理顺序与"清理失败要判红"的不变量已修好**（并已手工清掉先前残留）。

## 8.1 重建（`DEV-ENV §6.13`）

| 步骤 | 结果 |
| --- | --- |
| 停 8080 的 java | 旧 PID 26280 终止 → `PORT-8080-FREE`（不动基础设施） |
| `mvn -B -pl ruoyi-ctms clean install`（build 锁） | **BUILD SUCCESS**；`Tests run: 535, Failures: 0, Errors: 0` |
| `mvn -B -DskipTests -pl ruoyi-admin clean package`（build 锁） | **BUILD SUCCESS** |
| jar | **LastWriteTime = 2026/10/05 23:53:15**（t20 那次是 23:37:57 ⇒ 本次构建） |
| `start-env.ps1 -Only Backend` + `oa-login.ps1` | 8080 就绪、登录成功 |

## 8.2 `tools\erp-smoke.ps1 -Strict` → **27 / 27 全通过**

| 编号 | t20（C-1 修复前） | t34（修复后） |
| --- | --- | --- |
| **S01** `/erp/pur/request/list` | ❌ `code=500`：`ReflectionException: Illegal overloaded getter method with ambiguous type for property 'posted' in class ...ErpDocHeader` | ✅ `code=200`；`total=0 rows=0 msg=查询成功` |
| 其余 26 条 | ✅ | ✅（逐条同前，无回归） |
| 合计 | 通过 26 / 失败 1 | **通过 27 / 失败 0** |

**前后对照（原始 body）**
```
修复前：{"msg":"nested exception is org.apache.ibatis.reflection.ReflectionException: Illegal overloaded
        getter method with ambiguous type for property 'posted' in class
        com.ruoyi.ctms.erp.base.domain.ErpDocHeader. This breaks the JavaBeans specification and can
        cause unpredictable results.","code":500}
修复后：{"total":0,"rows":[],"code":200,"msg":"查询成功"}
```

## 8.3 `tools\erp-check.ps1 -Only P-` → 旧异常消失、暴露第二个缺陷

| 编号 | t26/t20（C-1 修复前） | t34（修复后） |
| --- | --- | --- |
| **P-01 入库单新增** | ❌ `code=500` = `posted` 歧义异常 | ❌ `code=500`，但**异常换了**：`java.sql.SQLIntegrityConstraintViolationException: Column 'warehouse_name' cannot be null`（`ErpStockInMapper.insertStockIn`） |
| P-EXC | ❌ 段落异常（空引用） | ❌ 同（P-01 失败后的派生） |
| CLEAN-01 | （此前被过滤/未打印） | ✅ **残留=0**（`-Always`，`-Only` 下也打印） |

> ⚠ 关键：**"旧异常消失"本身就是一条前后对照证据** —— `P-01` 不再报 `posted`，
> 说明 `ErpDocHeader` 的歧义 getter 已消除（t31）；现在的 500 是**另一个原因**。

**第二个缺陷的隔离证据**（`.cache/t34/isolate.txt`，自建夹具 + 自清，残留=0）：

| 探针 | 结果 |
| --- | --- |
| **A** 只传 `warehouseId`（notes §8.1 的冻结 payload） | `code=500` `Column 'warehouse_name' cannot be null`（表头快照没回填） |
| **B** 同传 `warehouseId` + `warehouseName` | `code=200`，`docNo=IN202610000018`、`status=draft`、`posted=0`、`totalAmount=10.00`（探针单据随后删除，code=200） |

- 归口：**t33**（已由 captain 用 `amend_task` 补进契约："只给 id ⇒ 成功且 name 由服务端从档案回填"）。
- 影响面：**脚本/契约面**（notes §8.1 的 payload、t13 的断言）会 500；
  **前端不受影响** —— `DocFormShell.vue:327-341` 在选仓库时会回填 `warehouseName`/`fromWarehouseName`/`toWarehouseName` 等。
- t34 的 `-Only P-` 要等 t33 落地后重跑（届时期望 **通过 2 / 失败 0**）。

## 8.4 夹具清理：顺序修复 + "清理失败要判红"的不变量

**先手工清掉的残留**（captain 指出的 8 行：3 张采购申请单 + 5 行行项，均引用上一轮夹具物料 `PT26235359`）：
按 **子表 → 父表** 顺序清理（行项 9 张（含 B3 合同行项）→ 结存/流水 → 单据表头 → 主数据档案），
另清掉夹具主数据（1 物料 / 2 仓库 / 1 单位 / 2 类型）；**复核 18 项计数全为 0**。

**脚本侧改动**（`tools\erp-check.ps1`，in-scope）：
1. `Clear-Fixtures` 重写为**严格子表→父表**，作用域用"夹具 id + code 前缀"（不再依赖含前缀的单号）；
2. 新增 `IdListCsv`（空集占位，避免 `IN ()`）；比较一律显式 `COLLATE utf8mb4_0900_ai_ci`
   —— 本轮实测踩过 `ERROR 1267 Illegal mix of collations`（自建临时表默认 `utf8mb4_general_ci`）；
3. `Clear-Fixtures` 局部把 `$ErrorActionPreference` 置 `Continue`，SQL 报错只 **warn**，**不再中断脚本**；
4. `FixtureResidue` 扩到 **主数据 + 8 类单据 + 9 张行项 + 结存/流水**（按夹具 id 与 code 前缀），
   并把明细写进 `$script:ResidueDetail`；
5. `Case` 增加 `-Always`，`CLEAN-01` 用它 ⇒ **即使 `-Only <前缀>` 也会打印并判红**（本不变量已实测生效）。

**一条边界（写给 t13/t14）**：`CLEAN-01` 只对本脚本**自己的夹具**判零残留（它不会去删别人的数据）——
captain 已裁决**这个口径正确，不要扩到全库**（去删别人的在飞数据会制造假红/假绿）。

收尾时库里另有他人数据的**归属已由 captain 查清**（2026-10-06）：

| 数据 | 判定 | 处置 |
| --- | --- | --- |
| `code='1'` 的「测试客户1 / 测试供货商1」 | 创建于 **21:38/21:39**，**早于本批团队启动（22:32）** ⇒ 历史遗留 | 本批**不动**，也不算残留 |
| 6 张采购申请单草稿（`23:56:40~23:57:17`、`_item` 均为 0 行） | **正在飞的探针夹具**（最可能 owner = backend-ledger，在调 `erp-scope-check.ps1` 四档矩阵） | **我不动**（captain 已去对接 owner） |
| **全库基线归零** | 不由本脚本/本次负责 | 写进 **t13 的集成窗口口径**（"全库口径"的正确落点在那里） |

---

# 9. t42：P 段残余红项的定性与修绿（含"全库不变式"补缺）

> 结论：**P 段全绿**；5 条红项**全部归因为"脚本问题"**（产品侧零缺陷）；顺带补上了 captain 指出的
> 不变量缺口（`CLEAN-01` 只看自家夹具，看不住"历史遗留的账不平"）。

## 9.1 三份原始输出（跑前先做 D-14 自证）

| 自证 / 运行 | 结果 |
| --- | --- |
| `tools\erp-smoke.ps1` | BOM 前三字节 = **239,187,191**；`[Parser]::ParseFile` 解析错误 **0** |
| `tools\erp-check.ps1` | BOM 前三字节 = **239,187,191**；`[Parser]::ParseFile` 解析错误 **0** |
| `erp-smoke.ps1 -Strict` | 探针 27 条 → **通过 27 / 失败 0 / 跳过 0**（`SMOKE_EXIT=0`） |
| `erp-check.ps1 -Only P-` | **通过 20 / 失败 0 / 跳过 1**（`CHECK_P_EXIT=0`）；`CLEAN-01` **[PASS]**、`CLEAN-02` **[PASS]** |
| `erp-check.ps1 -Only P-01` | **通过 3 / 失败 0**（`CHECK_P01_EXIT=0`：P-01 + CLEAN-01 + CLEAN-02） |

P 段逐条：`P-01/02/03/05/06/07/08/09/10/12/13/15/16/17/18/19/20/22` 全 **[PASS]**；
唯一 `[SKIP]` 是 `P-23 导出 xlsx`（需下载二进制并核对列/行数 ⇒ 归 t13 补 `Download-Excel`，**SKIP ≠ PASS**）。

## 9.2 逐条定性（脚本造数问题 / 产品缺陷 / 环境并发）

| 编号 | 归类 | 根因（真实原因） | 可复核证据 | 修法 |
| --- | --- | --- | --- | --- |
| **P-12 / P-13** | **脚本** | **不是**"中文未编码"（已用 `urlprobe` 证伪：ASCII/裸中文/`EscapeDataString`/不带 reason 四种都得到 `HTTP=200 {"code":500,"msg":"单据不存在"}`）。真因是 **PowerShell 把 `?reason` 吃进了变量名**：`".../unapprove/$inId?reason=..."` ⇒ 变量名被解析成 `inId?reason`（未定义 ⇒ 空）⇒ URL 变成 `/stk/in-order/unapprove/=%E9%87%8D%E5%A4%8D` ⇒ **404** ⇒ helper 按"非 JSON"记成 `code=-1`、`status/posted` 全空 | `.cache/t34/acceptance6.txt`：`HTTP=404 url=POST http://localhost:8080/stk/in-order/unapprove/=%E9%87%8D%E5%A4%8D body={"timestamp":"…","status":404,"error":"Not Found","path":"/stk/in-order/unapprove/=%E9%87%8D%E5%A4%8D"}`；对照 `.cache/t34/urlprobe.txt`（同一端点 + 合法 id ⇒ 业务响应） | 改为**字符串拼接**：`('/stk/in-order/unapprove/' + $inId + '?reason=' + [uri]::EscapeDataString('…'))`；`Api()` 现在**永久记录**「方法 + 全路径 URL + HTTP 状态码 + 原始 body」 |
| **P-EXC** | **脚本** | 真因**不是** `HttpClient`（那条也一并修了），而是 **P-18 故意制造的 `ERROR 1062` 把段落打断了**：`SqlFile` 里 `mysql` 的错误走 stderr，在 `$ErrorActionPreference='Stop'` 下被转成 NativeCommandError ⇒ 终止 ⇒ 段内异常 | `.cache/t34/acceptance7.txt`：`P-EXC … 实际：ERROR 1062 (23000) at line 1: Duplicate entry '…' for key 't_ctms_stock.uk_stock_product_warehouse'` | `SqlFile` 局部 `$ErrorActionPreference='Continue'`（SQL 报错变成**可断言的返回值**）；P-18 把 `ERROR 1062` 当**期望值**断言；并发段加 `Add-Type -AssemblyName System.Net.Http`，并把 `HTTP 码 + 响应体` 打进入 actual |
| **P-16** | **脚本（派生）** | 依赖 P-12 的"反审核"成功；P-12 因空 id 404 ⇒ 结存停在 40 而非 70 | 修 P-12 后自动 **[PASS]**（`qty=70.000`） | 无需单独修（同 P-12） |
| **P-17** | **脚本造数** | 脚本用 **SQL 直接种结存**（10/3/100）而**没有对应流水** ⇒ 该 key 天然 `结存 ≠ Σ流水`；原断言用"全库口径"衡量，等于拿自己的种子当缺陷 | `.cache/t34/acceptance4.txt`：`不一致=3`（正是 3 个被种过结存的 key） | 改为**夹具口径**、并把种子算进去：`每个夹具 key：结存 == 种子初值 + Σ流水`；同时把**全库口径**升级为独立的不变量 `CLEAN-02`（见 §9.4） |
| **`CHECK_P01_EXIT=1` 回归** | **脚本（我引入）** | `acceptance5` 那一轮我在 P-17 里写了 `"$k: …"`，PowerShell 把 `$k:` 当**作用域限定变量** ⇒ **1 个 ParseError** ⇒ 该轮 `-Only P-01` 是"脚本加载失败"（通过 1/失败 1），不是断言失败 | `acceptance5.txt` 里 `ParseErrors=1`；改成 `${k}:` 后 `acceptance6.txt` 起 `ParseErrors=0`、`-Only P-01` 回绿 | 已修（`${k}:`）；并把"跑前两行自证（BOM + ParseErrors）"变成固定前置 |

**产品侧结论**：本轮 5 条**没有一条**是 B4 业务缺陷；反审核/红冲、并发不丢更新、唯一约束、库存类单据禁用 `complete`、非草稿编辑拒绝等
都由 P 段的绿项**正面证明**（P-12/P-13/P-15/P-16/P-18/P-19/P-20）。

## 9.3 "证据形态"决定"判定速度"（本轮最有价值的方法论）

- 之前 P-12 的失败详情只有 `code=-1 msg= status= posted=`：**无法判定**是脚本还是产品，来回三轮都在猜（"编码？""id 空？"）。
- 给 `Api()` 加上「**HTTP 状态码 + 完整 URL + 原始 body**」后，**一次运行**就把 404 与"`?reason` 被吃进变量名"钉死。
- ⇒ 纪律：**任何接口断言失败的 actual 必须包含 `请求方法与全路径 URL + HTTP 状态码 + 原始响应体`**（`ApiDiag` 已内建，长期保留）。

## 9.4 全库不变式补缺（captain 指出的缺口）

**现象**（qa-hardening 只读核查发现）：`POST /stk/stock/recalc?repair=false` 报 `consistent=false, inconsistent=1`，
`PT26000813 / WAT26000813：stock=15、ledger=5、diff=+10`。

**成因（两条，都是脚本侧）**：
1. **种子不平衡**：脚本为"负库存/并发"用例用 SQL 种结存（10/3/100），种子**没有流水** ⇒ 该 key 天然不平；
2. **死在收尾前的运行**：00:02/00:03（脚本解析错误轮）与 00:09（工具调用中断轮）**没跑到收尾** ⇒ 夹具 + 不平的结存/流水留在库里。

**修法**：
- `Clear-Fixtures` 新增 **⑤ 历史同类夹具"自愈"回收**：按 erp-check 的命名约定 `*T26######`（`$S = T26+HHMMSS`），
  严格 **行项 → 结存/流水 → 单据表头 → `change_log` → 主数据** 全部回收。效果：**任何一轮死了，下一轮开跑/收尾都会把它收干净**。
  作用域只认该命名约定（他人夹具 `T10S*`/`FIX*`/`E2E*` 都不匹配，不会误删）。
- 新增 **`CLEAN-02`（`-Always`，`-Only` 下也打印）**：**全库 `结存 == Σ流水`**，不平则**判红**并打印不平明细（code/结存/流水累计）。
  这补上了"`CLEAN-01` 只看自家夹具残留"的缺口。
- **收口核对**：`recalc` → `consistent=True, inconsistentCount=0`；主数据（`T26######` 约定）6 表 = 0、`t_ctms_stock`/`t_ctms_stock_ledger` = 0、
  8 类单据 = 0、`purchase_request` 行项 = 0。

## 9.5 留档发现（建议进 DEV-ENV）

| 坑 | 现象 | 处置 |
| --- | --- | --- |
| **RuoYi `sys_config` 缓存进 Redis** | 只 `UPDATE sys_config SET config_value=…` **运行期不生效**（读到旧值），`stock_allow_negative` 用例因此假红 | 改库后必须 `DEL sys_config:<configKey>`（本机 `env\redis\server\redis-cli.exe`，键名实测 `sys_config:stock_allow_negative`）；**用后必须还原**（脚本已还原为 `false` 并再次 `DEL`） |
| **`change_log` 是跨轮累积** | 现 687 行（含历史审计行）；`object_type='purchase_request'` 已 0 | 非夹具残留；`CLEAN-01/02` 都**不**把它算残留（清理按被跟踪 `object_id` 删，历史行保留无害） |
| **PowerShell 5.1：无 BOM** | 中文按 ANSI 读 ⇒ 引号错位 ⇒ `Missing closing ')'`、`&` 保留字报错（**D-14**） | `.ps1` 必须 UTF-8 **with BOM**；`edit` 工具改写后要重新补 BOM；跑前自证前三字节 |
| **PowerShell 5.1：`"$id?k=v"`** | `?` 之后的字符被当作变量名的一部分 ⇒ 变量为空、URL 残缺（**本轮 P-12/P-13 真因**） | 查询串一律**拼接**并 `[uri]::EscapeDataString` |
| **MySQL：临时表同语句只能引用一次** | `ERROR 1137 (HY000) Can't reopen table` | 同语句只引用一次（`IN (SELECT …)`），或改 `IdListCsv` 拼 `IN` 列表 |
| **MySQL：自建临时表 collation** | 默认 `utf8mb4_general_ci` 与 B4 表 `utf8mb4_0900_ai_ci` 比较 ⇒ `ERROR 1267 Illegal mix of collations` | 临时表列显式 `COLLATE utf8mb4_0900_ai_ci` |

## 9.6 D-16（"半截单据/非单事务"疑点）的定性：**不成立**

captain 要求"最后一击"= 让**行项 INSERT** 撞 DB 约束，再看表头是否回滚（证据 `.cache/t34/probe2.txt`、`.cache/t34/d16.txt`）：

| 探针 | 结果 | 说明 |
| --- | --- | --- |
| 带行项建单（正常） | `code=200`；库内**表头=1、行项=1** | 表头与行项一起落 |
| `items=[]` | `code=500 单据至少需要一行行项`；库内**表头=0** | 业务层**先校验后写入** |
| **失败注入**：行项 `qty=999999999999999999.999`（超出 `decimal(16,3)` 上限，业务层只校验小数位、不校验量级） | `code=500`；库内**表头=0、行项=0** | **表头随行项 INSERT 失败整体回滚 ⇒ 建单是单事务** |
| 行项 `productId` 不存在 | `code=500 行 1：物料档案不存在：NOT-EXIST-PRODUCT`；库内表头=0 | 业务层先拒（走不到 DB） |

⇒ 那 6 张"0 行项 + purpose 非空"的历史草稿**不是**失败事务留下的半截单据（单事务会整体回滚），
  也已按 **子表→父表**（行项 → `change_log` → 表头）清理并复核归零。D-16 闭环。**

## 9.7 更正记录（避免后来者按错口径排查）

1. **`GET /erp/pur/request/detail/{id}` 不存在**（早前把 404 误记为"详情 500"）：详情映射是 **`@GetMapping("/{id}")`**；
   且**必须传 `id`（32 位 hex），传 `doc_no` 会得到业务错误 `单据不存在`**（`code=500`）。更正后的对照证据：
   `GET /erp/pur/request/{id}` → `code=200 msg=操作成功 status=draft items=1`；`GET /erp/pur/request/{docNo}` → `code=500 单据不存在`。
2. `P-08` 的口径按 **t33 §13.4** 更正：缺表头仓库现在是**创建期可读拒绝**、`msg` 恰为
   `表头仓库不能为空：请选择入库仓库`（实测：创建与审核两层都返回该文案），**不是**"草稿允许缺仓库、审核报 `行 1：`"。

---

# 10. t13 门禁全表（真实输出 + 退出码；2026-10-06 00:2x）

> ⚠ 口径：本表只写**实际跑出来的**结果。跑不完的项一律标「未执行 + 原因」，**不用旧结果冒名顶替**。
> 全部命令经 `tools\locked-run.ps1`（maven 走 `-LockName build`，接口/DB 脚本走 `-LockName env`）。
> 日志：`.cache/t13/{mvn-test2,audit-run-all,ui-tests,ui-build,erp-gates,b-gates}.log`

## 10.1 构建与环境

| # | 门禁 | 命令 | 真实结果 | 退出码 |
| --- | --- | --- | --- | --- |
| G-00 | **统一打包重启** | 见 §9.1（t34） | `ruoyi-admin.jar` **LastWriteTime = 2026/10/06 00:00:25**、176.1MB、**MD5 = 0873927F0AD6768743D2CC9191F6295A**（fat jar 内 `ruoyi-ctms-1.0.1.jar` = 00:00:02）；后端 PID 19912 启动于 **00:00:40**；`oa-login.ps1` 成功 | 0 |
| G-01 | **后端单测** | `mvn -B -pl ruoyi-ctms test`（build 锁） | `Tests run: 543, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（与 captain 给的基线 **543/0 一致**） | **0** ✅ |
| G-02 | **前端单测** | `node tests/run.js`（`ruoyi-vue-oa-ui-master`） | `共 196 条；通过 196 条；失败 0 条` | **0** ✅ |
| G-03 | **前端生产构建** | `npm run build:prod` | 构建成功（无 error） | **0** ✅ |
| G-04 | **仓库审计套件** | `node tools/audit/run-all.js` | `共 10 个审计；失败 0 个；未自证 0 个` | **0** ✅ |

## 10.2 B4 接口门禁

| # | 门禁 | 真实结果 | 退出码 |
| --- | --- | --- | --- |
| G-10 | `tools\erp-smoke.ps1 -Strict` | 探针 27 条 → **通过 27 / 失败 0 / 跳过 0** | **0** ✅ |
| G-11 | `tools\erp-check.ps1`（**全段**） | **通过 54 / 失败 13 / 跳过 21**（`CLEAN-01` [PASS]、`CLEAN-02` [PASS]） | **1** ❌（13 条见 §12） |
| G-12 | `tools\erp-scope-check.ps1`（t10 交付物） | 脚本存在且**通过**（当前库为空基线） | **0** ✅ |

> G-11 的 21 条 SKIP **逐条理由**在日志里（例：B-1..B-14「由 E2E-PUR 覆盖」、L-16「归 t10 §8.2 四档矩阵」、
> P-23/L-12「需二进制响应 + Excel 解析」、S-C1..C7/E2E-SAL「t8 交付后补」）。**SKIP ≠ PASS**。

## 10.3 既有 B 系列门禁（本轮实测）

| 门禁 | 真实结果 | 退出码 |
| --- | --- | --- |
| `ctms-perm-audit.ps1` | 权限点双向核对：**15 条断言全绿**，菜单 26 / 后端 26，差异为空 | **0** ✅ |
| `authz-check.ps1` | **通过 45 / 失败 0**（= V1 AC-35 基线 30 + B3 §9.3 新增 15，含断言总数自检） | **0** ✅ |
| `flow-form-consistency-check.ps1` | **通过 27 / 失败 0**；收尾回到基线（真实数据未污染） | **0** ✅ |
| `b3-sql-drill.ps1` | **通过 68 / 失败 0**；演练库已删除、列已恢复可空 | **0** ✅ |
| `ctms-attachment-check.ps1` | **通过 107 / 失败 0**；授权与上传文件残留 0 | **0** ✅ |
| `ctms-contract-check.ps1` | **通过 261 / 失败 0**；`data_scope` 与临时授权已还原、夹具残留 0 | **0** ✅ |
| `ctms-masterdata-check.ps1` | **通过 79 / 失败 0** | **0** ✅ |
| `serial-numbering-check.ps1` | **通过 32 / 失败 0**；老入口行为不变 | **0** ✅ |
| `print-builtin-check.ps1` | **通过 98 / 失败 0** | **0** ✅ |
| `b1-binding-check.ps1` | **通过 25 / 失败 0** | **0** ✅ |
| `sign-feature-check.ps1` | **通过 42 / 失败 1**；失败项 = `AC-26 未验证：找不到 signMode=REQUIRED 的在办任务` | **1** ⚠ |
| `related-approval-check.ps1` | 未通过（原始行：`落库 2 行关联关系（实际 0）`、`落库了单据号快照（）`、`按控件分组返回 2 条（实际 1）`、`反查：按被关联单据能查到宿主单据`） | **1** ⚠ |
| `ctms-e2e-check.ps1` / `b1-e2e-check.ps1` / `flow-regression.ps1` / `ctms-commercials-check.ps1` / `ctms-migration-check.ps1` / `b1-*`（其余） | **未执行**（原因：与 qa-hardening 的浏览器 E2E 共用库与环境、且本轮轮次预算用尽；**不以旧结果冒名**） | — |

**两条非零门禁的初判**（不做产品定性，交 owner 复核）：
- `sign-feature-check` 的 1 条失败是**夹具性未验证**（库里没有 `signMode=REQUIRED` 的在办任务），不是断言被推翻；
- `related-approval-check` 属 **B 系列（V1/关联审批）**，不在本轮 B4 改动面内；其原始行已留档，需 B 系列 owner 复核。

# 11. 验收追溯矩阵（工作流级；逐条 52 项细化待 t13 正式轮）

> ⚠ 诚实声明：**工作流级**矩阵已可对账；**52 条 tasks.md 的逐条映射尚未完成**（轮次预算），下表明确标出
> 「缺证据 / 未验证」的格子，**不填推测值**。逐条细化 + 补证是 t13 正式轮的第一件事。

| 工作流（tasks.md 域） | 实现落点（代码/脚本） | 证据（脚本断言 / notes） | 状态 |
| --- | --- | --- | --- |
| 主数据（物料/仓库/单位/类型/往来） | `ctms` 主数据服务 + `erp` 快照守卫 | `ctms-masterdata-check.ps1` **79/0**；`erp-check` FIX 段；`ctms_per__` 权限门禁 | ✅ 有证据 |
| 8 类单据 CRUD + 状态机 | `erp/{purchase,sales,posting,stockops}` 服务 + 控制器 | `erp-check` P-01/02/03/05/06/07/08/12/13/18/19/20 全 [PASS]；`erp-smoke` S20–S27 动作路由一致 | ✅ 有证据 |
| 下推（申请→单、单→出入库、销售下推） | 各服务 `push*` + 回写 | `E2E-PUR-1/2/3/4`、`S-C1..C7`、`E2E-SAL` — **本轮 FAIL/SKIP**（见 §12） | ⚠ **缺证据（未走通）** |
| 过账 / 红冲 / 幂等 / 并发 | `posting` 过账引擎 + 两阶段行锁 | `erp-check` P-05/06/12/15/16 全 [PASS]（含并发不丢更新、红冲行 `红冲-采购入库`） | ✅ 有证据 |
| 调拨 / 盘点 | `stockops`（t9 交付） | `erp-check` T-05/08/09 — **本轮 FAIL**（初判见 §12）；`erp-smoke` S07/S08/S26/S27 路由在线 | ⚠ **待定性** |
| 库存账（结存/流水/额度/recalc） | `ledger` 服务 + `recalc` | `erp-smoke` S09–S15 全 200；`erp-check` L-13 **FAIL**（初判：脚本种子口径，见 §12）；`CLEAN-02` 全库不变式 **[PASS]** | ⚠ 部分 |
| 权限与数据范围 | `@PreAuthorize` + 菜单 SQL + 数据范围 | `ctms-perm-audit.ps1` **15 断言全绿**、`authz-check.ps1` **45/0**；`erp-smoke` 低权 10/10 403；**§8.2 四档矩阵 = t10 未完 → 未验证** | ⚠ **§8.2 缺证据** |
| 前端（列表/表单/详情/许可/库存账页） | `views/erp/**`、`api/erp/doc.js`、`doc-kinds.js` | 前端 `tests/run.js` **196/196** + `build:prod` **0**；`audit-run-all` **10/10**（含模板/主题/静默 catch） | ✅ 有证据 |
| 集成（E2E / 门禁 / 打包重启） | 本文件 §7–§10 | `erp-smoke` **27/27**；打包重启 jar **00:00:25 / MD5 0873…**；§12 的 13 条红项待定性 | ⚠ 待收口 |
| 附带项：B3 自检漂移（t17） | `sql/二开-合同台账.sql` | `b3-sql-drill.ps1` **68/0**；§11 的 drill 记录 | ✅ 有证据 |
| 附带项：ALL 合流卡死 / 模板 form_id↔formId | `flow-form-consistency-check.ps1`、`b1-binding-check.ps1` | **27/0**、**25/0**（V-8 可阻断可告警、失败回滚） | ✅ 有证据 |
| 附带项：PRD V-8 表单保存反向校验 | 同上 | `flow-form-consistency-check.ps1` 内 V-8 断言全绿 | ✅ 有证据 |

# 12. `erp-check.ps1` 全段 13 条红项（真实 actual + 初判，**待 t13 正式轮定性**）

> 这 13 条是**首次被跑到**的段落（此前 P-01 一挂就把整段中断），因此**不代表回归**。初判仅供派单参考，
> **未定性的不写成产品缺陷**；A-A1/E2E-PUR-1/E2E-TR/S-A6/S-B3 需要原始 body（下一步补 `ApiDiag` 到这些断言）。

| 编号 | 真实 actual | 初判 | 依据 |
| --- | --- | --- | --- |
| A-A1 | `code=200 total= status=` | **脚本**（响应体形状/字段名与断言不一致——`code=200` 说明建单本身成功） | 缺 `data.*` 字段 ⇒ 断言取空 |
| A-EXC | `Cannot index into a null array.` | **脚本（派生）** | A-A1 的 null 数据 |
| S-A6 | `code=500 total=` | **待定性**（可能产品：销售申请列表 500；也可能缺夹具） | 需原始 body |
| S-B3 | `code=500 total= customerName=` | **待定性**（销售订单三行 1×0.125 建单 500） | 需原始 body |
| S-B4 / S-B5 | 字段全空 | **脚本（派生）** | 依赖 S-B3 |
| T-05 | `qtyA=6.000 qtyB=74.000 status=approved posted=1` | **脚本（期望口径）**：过账本身正确（A 10→6、B +4），但断言假设 B 初值 0 而实际 70 | 需在 T 段固定两仓初值 |
| T-08 | `status=submitted qtyA=10.000 qtyB=70.000 红冲=2` | **脚本（同上）** | 红冲两行都写了、状态也对 |
| T-09 | `code=200 msg=操作成功 流水 10→14` | **脚本（夹具）或产品**：'调出仓不足应整单不写入'未触发，需打印请求量 vs 可用量 | 同 T-05 的初值假设 |
| T-EXC | `Cannot index into a null array.` | **脚本（派生）** | T-09 之后 |
| L-13 | `code=200 inconsistentCount=3` | **脚本（种子口径）**：与 P-17 同类——脚本 SQL 种结存无流水 | 收尾 `CLEAN-02` 为 [PASS] 可证 |
| E2E-PUR-1 | `code=-1 orderId= msg=` | **脚本（派生 + 需 ApiDiag）**：A-A1 未拿到 id ⇒ 后续链路断 | 同 A-A1 |
| E2E-TR | `approve=500 流水 0→0 unapprove=500` | **待定性**（调拨 approve 500；需原始 body） | 需补 ApiDiag |

**归口建议（供 captain 派单）**：`S-A6/S-B3/E2E-TR` 需**补原始 body 后才能定性**（同类脚本问题：断言缺 `ApiDiag`）；
`A-A1/E2E-PUR-1` 属 A 段夹具/字段口径；`T-05/08/09` + `L-13` 属**脚本夹具与期望口径**（T 段两仓初值、L 段种子口径）。
21 条 SKIP 的补法已在脚本注释与日志里逐条写明。
