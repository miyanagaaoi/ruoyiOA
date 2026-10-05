# 12 · 附件对象类型登记与参考仓库对齐（t19 / backend-posting）

> 变更集：`oa-purchase-sales-stock`｜任务：t19（由 frontend-erp 在 t11 复核时发现，captain 用参考仓库源码确认）
> 交付时间：**2026-10-05**｜后端 jar `ruoyi-admin.jar` = **2026-10-05 23:11:31**（本次重打包并重启）
> 验收资产：`tools\ctms-attachment-check.ps1`（**107 条断言，连跑两次 107/0、残留 0 行**）+
> `CtmsAttachmentServiceImplTest`（**22 条**，原 18 条）｜真源：`openspec\changes\oa-contract-ledger\notes\attachment-notes.md` §2 的"接入必改三处"

---

## 1. 一句话结论

参考仓库 `app/routers/attachments.py:35-44` 的 `OBJECT_PERMS` 覆盖 **`contract` + 7 类单据**；
B4 首轮交付只登记了 `contract` + 6 类单据，**漏了 `purchase_request` / `sales_request`**
（现象：两个申请单上传附件被判"未注册的对象类型"）。
本次按"接入必改三处"补齐，登记清单变为 **`contract` + 8 类单据 = 9 项**（比参考侧多 `stock_transfer`，
那是参考仓库的已知缺陷、我们按 Q-B10 补齐的），`plannedB4` 保持清空。
断言只强化不放宽：单测 18 → **22** 条、脚本 76 → **107** 条，全部通过。

---

## 2. 参考侧口径（证据链）

| 证据 | 内容 |
| --- | --- |
| `app/routers/attachments.py:35-44`（参考仓库，captain 确认行号） | `OBJECT_PERMS` = `contract` + `purchase_request` / `purchase_order` / `sales_request` / `sales_order` / `stock_in` / `stock_out` / `stock_take` |
| `doc/2.0/参考仓库-CTMS-移植清单.md:1005` | "对象→权限映射（`attachments.py:35-44`）覆盖 `contract` + 7 类单据；⚠️ **缺 `stock_transfer`**（V2.1 新增调拨单未登记）" |
| `doc/2.0/参考仓库-CTMS-移植清单.md:1433-1434`（附录 A 第 7 条） | "调拨单附件不支持：`attachments.py:35-44` 的 `OBJECT_PERMS` 缺 `stock_transfer`" |
| `doc/2.0/参考仓库-CTMS-移植清单.md:997` / `:502` | 附件列表"按对象映射 `*_PERM.view`（`attachments.py:35-44`）"；"上传前校验对象存在（合同或 7 类单据）" |
| `doc/2.0/2.0-PRD-OA升级开发.md:567` | `attachments`（`object_type/object_id` + `OBJECT_PERMS`）→ 复用上传、保留"挂到任意业务对象 + 按对象鉴权"、**补 `stock_transfer`** |

> 说明：参考仓库的 Python 源码未 vendor 到本工作区（`doc/` 下只有移植清单与 PRD），
> 因此上表以**移植清单/PRD 的行号**作为可复核的引用；行号 35-44 由 captain 用参考仓库源码确认后下达。

**对齐结果**：

| 侧 | 清单 | 项数 |
| --- | --- | --- |
| 参考仓库 `OBJECT_PERMS` | `contract` + 7 类单据（缺 `stock_transfer`） | 8 |
| B4 补前（t1 交付） | `contract` + `purchase_order` / `sales_order` / `stock_in` / `stock_out` / `stock_take` / `stock_transfer` | 7（**缺两个申请单**） |
| B4 补后（本次） | `contract` + `purchase_request` / `purchase_order` / `sales_request` / `sales_order` / `stock_in` / `stock_out` / `stock_take` / `stock_transfer` | **9** |

**补前 → 补后 REGISTERED（逐项）**

```
补前(7): contract, purchase_order, sales_order, stock_in, stock_out, stock_take, stock_transfer
补后(9): contract, purchase_request, purchase_order, sales_request, sales_order,
         stock_in, stock_out, stock_take, stock_transfer        ← 新增 purchase_request / sales_request
plannedB4: []（补前补后都是空 —— 这正是"B4 待补项已全部交付"的可观察证据）
docObjectTypes: 6 → 8
```

---

## 3. "接入必改三处"的对账

| # | 位置 | 本次动作 | 证据 |
| --- | --- | --- | --- |
| ① | `support/CtmsAttachmentObjectTypes.java` | 新增常量 `PURCHASE_REQUEST` / `SALES_REQUEST`；`REGISTERED` 补入两者（9 项）；`docObjectTypes()` 由 6 → 8；`plannedB4` **仍为空** | 类内已写明恒等式 `registered().size() == docObjectTypes().size() + 1` |
| ② | 服务层存在性校验 | **无需改代码**（本来就没有"6 类"的硬编码）：`CtmsAttachmentServiceImpl.requireObjectAccess` 判合同 → 否则 `IErpDocObjectAccess.supports(objectType)`；而 `ErpDocObjectAccessServiceImpl.supports` = `ErpDocType.ofCode(...) != null`，`ErpDocType` 里 `PURCHASE_REQUEST("purchase_request")` / `SALES_REQUEST("sales_request")` **本来就有**；按类型查表的 `ErpDocLookupMapper.xml` 也**本来就有**两个 `<when>` 分支（`:31/:43`，均带 `del_flag='0'`） | 真机实测：`purchase_request` / `sales_request` 上传全部 200，幽灵 id 报"采购申请单不存在 / 销售申请单不存在" —— 存在性分支确实已被走到 |
| ③ | 文档 | 本文件（B4 侧）；另同步更新**代码注释**里的"6 类"字样（`CtmsAttachmentController.objectTypes()` 与 `CtmsAttachmentServiceImpl` 的两处 javadoc） | 见 §6 的 JSON：`registered`/`docObjectTypes` 的项数由接口直接给出，注释与实现不再分叉 |

⚠ **未改** `openspec/changes/oa-contract-ledger/notes/attachment-notes.md` §2 的清单表（那是 B3 变更集的 notes，简报 §3.7 禁止改别人的 notes）：
其表内 `purchase_order`/`sales_order`/… 仍标"⏳ B4 待补"、且**列的是 6 项**（不含两个申请单与 `stock_transfer`）。
**建议由 captain 指定** B3 notes 的维护者补一版（或接受"B4 侧以 `12-attachment-parity.md` 为准"）。同理由也适用于
`notes/01-base.md`（t1 的 notes，里面写的是"合同 + 6 类单据（7 项）"）。

---

## 4. 单测（`CtmsAttachmentServiceImplTest`，22 条 = 原 18 + 新 4，且 1 条被强化）

**新增 4 条**（任务要求的三件事全覆盖）：

| 用例 | 断言要点 |
| --- | --- |
| `两个申请单对象类型可上传下载删除且不写合同ID` | 对 `purchase_request` / `sales_request` 各走一遍：上传落库（`objectType`/`objectId` 原样、**`contractId` 必须为 null**）、物理文件落盘、列表恰 1 条、`requireDownloadable` + `localPathOf` 指向真实文件、删除为软删除且删除后列表为空；两条删除各写一条字段名`附件`的变更历史且 `contractId` 为空 |
| `申请单对象不存在时被拒且提示单据名` | 幽灵 id → `采购申请单不存在` / `销售申请单不存在`；不落库不落盘 |
| `申请单对象范围外上传列表下载删除均为403且不返回文件内容` | 上传 403（含"无权访问该采购申请单"）；列表 **403 而不是空列表**（返回空会把越权伪装成"没有附件"）；下载 403（鉴权在取字节之前 ⇒ 不会吐文件内容）；删除 403 且不得软删除、不得写变更历史 |
| `未装配B4对象访问实现时单据对象类型被明确拒绝而不是静默放过` | 容器里没有 `IErpDocObjectAccess` 时，已登记类型必须**明确失败**（`未注册的对象类型`）—— 防"登记了却没校验分支"产生孤儿附件 |

**被强化的 1 条**（原 `对象类型大小写不敏感且归一为小写`）：新增
`registered().size() == 9`、`docObjectTypes().size() == 8`、
`registered() 恒等于 [contract] + docObjectTypes()`（逐项比较，锁死"两处清单漂移"）、
`CtmsAttachmentRules.registeredObjectTypes()` 与常量清单**同源**（同一 `LinkedHashSet`）、
两个申请单在册（参考口径）、`plannedB4` 为空、未登记类型仍被拒。

真机/构建输出：

```
mvn -B -pl ruoyi-ctms test -Dtest=CtmsAttachmentServiceImplTest   （走 tools\locked-run.ps1 -LockName build）
  Tests run: 22, Failures: 0, Errors: 0, Skipped: 0 -- CtmsAttachmentServiceImplTest
  BUILD SUCCESS

mvn -B -pl ruoyi-ctms test                                        （全模块，走构建锁）
  BUILD SUCCESS
  surefire 汇总：34 个测试类 / Tests run = 471 / Failures = 0 / Errors = 0
```

---

## 5. 验收脚本（`tools\ctms-attachment-check.ps1`，107 条 = 原 76 + 新增，只强化不放宽）

新增/强化的断言（脚本内 `Assert-That` 的运行时条数由 76 → **107**）：

| 位置 | 新增/强化 | 条数 |
| --- | --- | --- |
| Step 6.1 清单接口 | 逐个单据类型断言由 **6 类扩到 8 类**（+2） | +2 |
| Step 6.1 清单接口 | 新增：`registered` 恰 **9** 项、`docObjectTypes` 恰 **8** 项、恒等式 `registered = docObjectTypes + 1`、`purchase_request` 在册、`sales_request` 在册 | +5 |
| Step 6.1b（新） | 两个申请单**端到端**各 9 条：上传 200 / 返回主键 / 元数据逐列（`object|objectId|NULL|0`）/ 列表 200 / 列表恰 1 条 / 下载 200 且返回字节 / 删除 200 / 软删除 `del_flag=1` / 删除后列表 0 条 | +18 |
| Step 6.1b（新） | 幽灵单据 id：`采购申请单不存在` / `销售申请单不存在` + 各自"不得落库" | +4 |
| 夹具 | 采购申请单 / 销售申请单夹具就位断言 | +2 |
| 收尾 | 新增"申请单夹具已清（残留 0）" | +1 |

夹具与幂等：夹具前缀 `ATTD…`（doc 对象）与 `ATTC…`（合同）区分；`Clear-Fixtures` 增加
`t_ctms_attachment.object_id LIKE 'ATTD%'`、`t_ctms_purchase_request/sales_request` 的清理；
`finally` 的残留断言增加"申请单夹具已清"。脚本本体仍是 UTF-8 **BOM**（改完已复核）。

真机输出（**同一个新 jar**，连跑两次）：

```
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1
  第 1 次：通过 107 / 失败 0       第 2 次：通过 107 / 失败 0
  收尾：合同夹具已清（残留 0）/ 附件夹具已清（残留 0）/ 客户夹具已清（残留 0）/
        申请单夹具已清（残留 0）/ 临时授权已撤销（残留 0）/ 本轮上传的文件已清（残留 0）
```

关键片段（第 6.1 / 6.1b 步，逐条 OK）：

```
[OK] 已注册对象类型含 purchase_request（B4 接入）
[OK] 已注册对象类型含 sales_request（B4 接入）
[OK] 已注册对象类型恰为 9 项 = 合同 + 8 类单据（实测 9 项）
[OK] 接口公布 8 类单据对象类型（实测 8 项）
[OK] 恒等式 registered = [contract] + docObjectTypes（9 vs 8 + 1）
[OK] purchase_request 上传 200（HTTP=200 code=200 …"objectType":"purchase_request","objectId":"ATTD…","contractId":null…）
[OK] purchase_request 元数据逐列正确且不写 contract_id（实测 purchase_request|ATTD231205481|NULL|0）
[OK] purchase_request 下载返回文件字节（HTTP=200 字节=32）
[OK] purchase_request 删除为软删除（del_flag=1）
[OK] 不存在的采购申请单被拒（msg={"msg":"采购申请单不存在：ATTD00000000000000000000000000FF","code":500}）
```

### `/ctms/attachment/object-types` 原始返回形态（真机，`Authorization: Bearer <superAdmin>`）

```json
{"msg":"操作成功","code":200,"maxSizeBytes":20971520,"maxSizeMb":20,
 "extensions":["pdf","doc","docx","xls","xlsx","png","jpg","jpeg","gif","txt"],
 "plannedB4":[],
 "docObjectTypes":["purchase_request","purchase_order","sales_request","sales_order",
                   "stock_in","stock_out","stock_take","stock_transfer"],
 "registered":["contract","purchase_request","purchase_order","sales_request","sales_order",
               "stock_in","stock_out","stock_take","stock_transfer"]}
```

> 前端（`src/views/erp/**` 的 `DocAttachmentPanel`）读的就是 `registered` 判断"该对象能不能挂附件"；
> `plannedB4` 仍返回空数组（键名不变，兼容既有消费者）。**前端本次零改动**。

---

## 6. 环境事实（对 T13/T14 有用，请勿误读为回归）

- 重启前线上跑的是 **17:04 的旧 jar（B3 期，完全没有 B4 代码）**：所以本轮以前任何人跑
  B4 接口脚本都必然红。本次在 `-LockName env` 内**停后端 → `mvn -DskipTests -pl ruoyi-admin clean package`
  → `start-env.ps1 -Only Backend`**，新 jar = `2026-10-05 23:11:31`，`/ctms/attachment/object-types`
  已返回上表 JSON。
- 因此 T13 的统一窗口**必须**沿用（或重新产出）这个新 jar；若仍用 17:04 的 jar，
  B4 的全部断言（含本文件 6.1b）都会红，那是"没部署"而不是"代码坏"。
- 本次没有改 `sql/`（无 DDL/字典/菜单变更）与前端；脚本本身是既有资产，只在断言与夹具上**加强**。

---

## 7. 本次改动的文件清单（共 5 个）

| 文件 | 改动 |
| --- | --- |
| `ruoyi-ctms/.../support/CtmsAttachmentObjectTypes.java` | 加两个常量；`REGISTERED` 9 项；`docObjectTypes()` 8 项；javadoc 同步（含参考侧行号与恒等式） |
| `ruoyi-ctms/.../controller/CtmsAttachmentController.java` | 仅 javadoc：6 类 → 8 类、9 项、恒等式（**无逻辑改动**） |
| `ruoyi-ctms/.../service/impl/CtmsAttachmentServiceImpl.java` | 仅 javadoc：两处"6 类单据"→"8 类单据"（**无逻辑改动**） |
| `ruoyi-ctms/src/test/.../CtmsAttachmentServiceImplTest.java` | +4 用例、1 用例强化、新增 `StubDocObjectAccess` 桩并注入 |
| `tools/ctms-attachment-check.ps1` | 6.1 断言强化 + 新 Step 6.1b（两个申请单端到端 + 幽灵对象）+ 夹具/清理/残留断言（UTF-8 BOM 保留） |

**未触碰**：`sql/**`、前端 `ruoyi-vue-oa-ui-master/**`、`CtmsProductMasterServiceImpl.java` / `ProductMasterRules.java`（t2 的范围）、
其它组的 notes 与源码、`openspec/changes/oa-purchase-sales-stock/tasks.md`。

---

## 8. 已知边界 / 待 captain 决定

1. **文档对账（建议）**：`oa-contract-ledger/notes/attachment-notes.md` §2 的清单表仍写"B3 的 6 项 + ⏳ B4 待补"，
   `notes/01-base.md` 写"合同 + 6 类单据（7 项）"。两处都不是本组的文件（简报 §3.7），故**未改**；
   请指定维护者补一版，或以本文件为准。
2. **范围外 403 的真机用例**：脚本对 `contract` 有完整的"数据范围 → 403"用例；
   单据对象的范围判定属 `ErpDocScope`（B4 §8.2，t10 的范围），本文件只在**单测**里覆盖 403；
   真机四档矩阵由 t10 的 `authz-check.ps1` / 数据范围实测负责。
3. `party_draft`（迁移草案对象类型）仍**未登记**（B3 §9 的既定边界）——本次没有顺手放行它；
   测试里"未登记类型仍被拒"仍以 `ctms_unknown_object` 为判别用例。
