# B4 §2 库存主数据（B4 部分）交付说明（`02-master.md`）

> 变更集：`oa-purchase-sales-stock`（B4）｜任务：`tasks.md` §2.1~§2.5
> 交付人：backend-base（T2 主数据组）｜交付时间：**2026-10-05**｜attempt_id：`47099450-5906-4c16-bd39-f00799ecd20f`
> 前置真源：B3 已交付四张档案表与档案接口（`oa-contract-ledger` §3.3、`notes/master-data-notes.md`、
> `notes/ddl-scope.md` §3/§4「B4 只引用、不重建」）。
> 本文件的用途：**逐条核对 B3 已实现口径是否满足 B4 规格**、登记本组补的三件事、
> 冻结用户可见文案，并给出供 T10 复核的接口断言清单。

---

## 0. 一句话结论

B3 的类型树与计量单位口径**逐条满足** B4 规格（§1 有逐条落点，无缺口、未改代码）；
B4 阶段才能成立的三件事已补齐并带单测：**① 仓库名称唯一 ② 仓库/物料的引用删除守卫
（有结存/流水/单据时拒绝，文案可读）③ 三个"只返回启用项"的选择器接口**；
"停用不可用于新行项"直接复用 T1 公共层的唯一守卫，**没有第二条判定路径**。

---

## 1. §2.1~§2.2 复核结论（有缺口才改；本轮**未**改这两条的任何代码）

### 1.1 物料类型树（B4 规格 4 条场景）

| 规格要求 | 现状 | 落点（B3 交付） | 单测 |
| --- | --- | --- | --- |
| 深度 ≤ 5 层 | ✅ 满足 | `ProductMasterRules.MAX_TYPE_LEVEL=5` + `childLevel()`；`insertProductType/updateProductType` 调它 | `类型树最多5级_第五级通过第六级被拒` |
| 同父下名称唯一 | ✅ 满足 | `productTypeMapper.selectProductTypeByNameAndParent(name,parentId)`；新增与修改两条路径都查（修改排除自身） | `同父下重名类型被拒` / `不同父下同名类型允许` / `修改类型时同父同名要排除自身且重算层级与路径` |
| **仅叶子可挂物料** | ✅ 满足 | `checkProductBinding` → `countChildren(id) > 0` 时 `只能在叶子类型下挂物料` | `非叶子类型挂物料被拒` |
| 有子或有引用禁止删除 | ✅ 满足 | `deleteProductTypeById` → `存在下级商品类型，无法删除` / `该类型下存在物料，无法删除` | `有子类型时删除被拒` / `有物料时删除类型被拒` / `叶子类型无子无物料时可以删除` |
| 物化路径（子树查询） | ✅ 满足 | `ProductMasterRules.childPath/levelOfPath`，`t_ctms_product_type.path` 列 | 同上（断言 path 与 level 一致） |

### 1.2 计量单位（B4 规格 3 条要求）

| 规格要求 | 现状 | 落点 | 单测 |
| --- | --- | --- | --- |
| 编码全局唯一 | ✅ 满足 | `insertUom/updateUom` 先归一（trim）再查重 → `计量单位编码已存在`；DB 侧另有 `uk_uom_code` 兜底 | `单位编码重复被拒且首尾空格归一后仍算同码` |
| 小数位 0~4 | ✅ 满足 | `ProductMasterRules.checkUomDecimals` → `计量单位小数位只能是 0 到 4`；留空默认 2（与 DDL `DEFAULT 2` 对齐） | `单位编码与名称非空_小数位越界被拒` / `单位小数位留空默认2` |
| 被物料引用不可删 | ✅ 满足 | `deleteUomById` → `countProductsByUom(id) > 0` → `计量单位已被物料引用，无法删除` | `单位被物料引用时删除被拒` |

> **结论**：§2.1/§2.2 在 B3 已完整落地，B4 规格没有新增缺口，因此本组**没有修改**
> `ProductMasterRules`，对 `CtmsProductMasterServiceImpl` 的改动全部集中在 §2.3/§2.4（见 §2）。

---

## 2. 本组补的三件事（改动清单）

| # | 内容 | 落点 | 为什么 B4 才能做 |
| --- | --- | --- | --- |
| 1 | **仓库名称唯一**（新增 + 改名，排除自身） | `CtmsProductMasterServiceImpl.insertWarehouse/updateWarehouse` + 私有 `findWarehouseByName` | 规格要求"编码**与名称**都全局唯一"，而 B3 的 DDL 只有 `uk_warehouse_code`（B3 边界：DDL 冻结，B4 只引用）→ 在服务层强制 |
| 2 | **引用删除守卫**（仓库：结存/流水/单据；物料：结存/流水/单据行项） | 规则 `erp/master/ErpMasterRefGuards`（纯函数，含文案）+ 统计 `erp/master/mapper/ErpMasterRefMapper(.xml)` + 服务 `deleteWarehouseById/deleteProductById` | B3 阶段还没有库存结存表与单据表，引用统计只能查空表（B3 自己在 `master-data-notes.md` §1.3 写明"仓库删除的引用保护属 B4"） |
| 3 | **三个选择器接口**（只返回启用项） | `selectWarehouseOptions/selectProductOptions/selectUomOptions` + `CtmsProductMasterController` 的 `/ctms/{warehouse,product,uom}/options` | 规格"停用仓库 MUST NOT 出现在新单据的仓库下拉中"；与 B3 对往来单位的 `options` 同口径（档案列表仍能看到停用项） |

改动文件（全部在 T2 的写范围内）：

```text
ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/master/ErpMasterRefGuards.java            （新增，纯规则+文案）
ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/master/mapper/ErpMasterRefMapper.java     （新增，只读 count）
ruoyi-ctms/src/main/java/com/ruoyi/ctms/erp/master/package-info.java                  （新增）
ruoyi-ctms/src/main/resources/mapper/erp/ErpMasterRefMapper.xml                       （新增，4 条 count SQL）
ruoyi-ctms/src/main/java/com/ruoyi/ctms/service/impl/CtmsProductMasterServiceImpl.java（改：名称唯一/删除守卫/选择器/引用统计回调）
ruoyi-ctms/src/main/java/com/ruoyi/ctms/service/ICtmsProductMasterService.java        （改：+4 个方法声明，与实现同一次落盘）
ruoyi-ctms/src/main/java/com/ruoyi/ctms/controller/CtmsProductMasterController.java   （改：+3 个 options 端点）
ruoyi-ctms/src/test/java/com/ruoyi/ctms/service/impl/CtmsProductMasterServiceImplTest.java（改：+10 个用例、+1 个引用统计桩、1 条用例改名）
ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/master/ErpMasterRefGuardsTest.java        （新增，7 条）
ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/master/ErpMasterSnapshotTest.java         （新增，4 条）
```

> `CtmsProductMasterServiceImplTest` 是 B3 的测试文件，改动**只有三类**：
> ① `setUp` 多注入一个引用统计桩（守卫必需）；
> ② 原用例 `仓库编码名称非空且B3阶段可直接物理删除` 改名为 `仓库编码名称非空且无引用时可物理删除`
>   （名字里的"B3 阶段"在 B4 已过时；断言本身**没变**：无引用时仍然是物理删除）；
> ③ 追加 10 个新用例。**没有修改或删除任何既有断言的期望值。**

---

## 3. 字段说明（四张表；B4 侧只补充"谁在引用它们"）

| 表 | 列数 | 关键字段（口径见 B3 `master-data-notes.md` §1.3 与 `sql/二开-合同台账.sql`） | B4 阶段的引用面 |
| --- | --- | --- | --- |
| `t_ctms_product_type` | 15 | `code`（可空，物料编码前缀来源）、`name`、`parent_id`、`path`（物化路径）、`level`（≤5）、`enable_flag` | `t_ctms_product.product_type_id`（FK） |
| `t_ctms_uom` | 12 | `code`（唯一）、`name`、`decimals`（0~4；决定行项数量精度） | `t_ctms_product.uom_id`（FK） |
| `t_ctms_warehouse` | 13 | `code`（唯一）、`name`（**B4 起服务层唯一**）、`keeper_user_id`（可空，仅反查名称） | 结存/流水/4 张单据表头（FK）+ 8 张行项的 `warehouse_id` |
| `t_ctms_product` | 18 | `code`（唯一；可自动生成）、`name`、`spec`、`product_type_id`（FK 非空）、`uom_id`（FK 非空）、`default_price`（非负）、`safety_stock`（非负、可空）、`enable_flag` | 结存/流水/8 张行项（FK） |

**"停用"的语义**（B4 规格 §「主数据停用与历史快照隔离」）：

| 场景 | 行为 | 落点 |
| --- | --- | --- |
| 停用物料/仓库/单位 | **不级联**任何数据；历史单据、结存、流水照旧 | 只置 `enable_flag='0'` |
| 新单据选择器 | 停用项**不出现** | `/ctms/warehouse/options`、`/ctms/product/options`、`/ctms/uom/options` |
| 写入行项 | 停用物料/仓库/单位**被拒**，文案带行号 | **T1 公共层** `ErpMasterGuards.applyItemSnapshot`（唯一实现点，本组不重复实现） |
| 历史展示 | 行项按**快照列**展示（`product_code/product_name/spec/uom_name/uom_decimals`） | 由各单据行项写入时固化；断言见 §4.3 |

---

## 4. 编码规则、启停用与守卫文案（用户可见文案 = 接口契约）

### 4.1 编码规则

| 档案 | 规则 | 落点 |
| --- | --- | --- |
| 商品类型 | 手工填写（≤32 字符）；留空时自动生成 `"P" + 类型id 前若干位`（**限长 32**，本轮之前就修过的 truncation 缺陷） | `ProductMasterRules.generateTypeCode/isTypeCodeTooLong` |
| 物料 | 留空时 **`类型编码 + 4 位序号`**（类型编码为空则用上一条的自动类型编码）；序号起点 = 该类型下已有物料数 + 1，与手工编码撞号则继续顺延（≤1000 次）；**前缀最多占 28 位**（编码列 `varchar(32)`）；手工填写时查重 | `generateProductCode` + `MAX_CODE_SEQ_RETRY` |
| 计量单位 / 仓库 | 手工填写，编码全局唯一（停用项也占号）；**编码不可改**（修改时服务端用库中原值覆盖） | `insertUom/updateUom`、`insertWarehouse/updateWarehouse` |
| 单据单号 | **不在本组**：由 T1 的 `ErpDocNoGenerator` 统一取号（`PR/PO/SR/SO/IN/OUT/ST/DB` + `yyyyMM` + 6 位） | `notes/01-base.md` §3.7 |

### 4.2 启停用与删除守卫文案（**逐字冻结**，改文案必须同步接口断言与验收脚本）

| 动作 | 触发条件 | 文案 |
| --- | --- | --- |
| 新增/修改仓库 | 编码重复 | `仓库编码已存在` |
| 新增/修改仓库 | **名称重复（新增，或改名撞他人）** | `仓库名称已存在` |
| 删除仓库 | 有库存结存（含 qty=0 的结存行） | `仓库已有库存结存，无法删除` |
| 删除仓库 | 有库存流水 | `仓库已有库存流水，无法删除` |
| 删除仓库 | 被任一单据引用（表头或行项的 `warehouse_id`） | `仓库已被单据引用，无法删除` |
| 删除仓库/物料 | 引用统计未装配（保护性拒绝，正常环境不会出现） | `引用校验未装配，拒绝删除（保护性拒绝）` |
| 删除物料 | 有库存结存 | `物料已有库存结存，无法删除` |
| 删除物料 | 有库存流水 | `物料已有库存流水，无法删除` |
| 删除物料 | 被任一单据行项引用 | `物料已被单据行项引用，无法删除` |
| 删除类型 | 有下级 | `存在下级商品类型，无法删除` |
| 删除类型 | 有物料引用 | `该类型下存在物料，无法删除` |
| 删除单位 | 被物料引用 | `计量单位已被物料引用，无法删除` |
| 新增/修改类型 | 同父重名 | `同级下已存在同名商品类型` |
| 新增/修改单位 | 小数位越界 | `计量单位小数位只能是 0 到 4` |
| 新增/修改物料 | 手工编码重复 | `物料编码已存在` |
| 新增/修改物料 | 默认单价为负 | `默认单价不能为负数` |
| 新增/修改物料 | 安全库存为负 | `安全库存不能为负数` |
| 新增/修改物料 | 类型非叶子 | `只能在叶子类型下挂物料` |
| 行项写入（T1 守卫） | 停用物料/仓库/单位 | `行 N：物料「名称」已停用，不能用于新单据`（仓库/单位同构） |

> 守卫的**判定与文案只有一处**（`ErpMasterRefGuards`）：服务层负责取行与执行删除，
> 文案不散落在 8 个业务服务里 —— 这与 T1 把状态机/精度收口到公共层是同一个理由。

### 4.3 主数据改名与历史快照（AC-72）

- 行项快照在**写入时**固化（T1 的 `ErpMasterGuards.applyItemSnapshot`），此后**没有任何代码路径**回写；
- 实测（真库，见 §5.4）：物料 `name` 从 `OLD-NAME` 改为 `NEW-NAME` 后，
  已保存行项的 `product_name` 仍是 `OLD-NAME`；
- 停用同理：停用不级联，历史行项与结存不受影响。

---

## 5. 验证（真实输出）

### 5.1 编译（共享树）

```text
mvn -B -pl ruoyi-ctms test-compile
```

**收尾轮实测：main 与 test 全部编译通过**（`mvn -B -pl ruoyi-ctms test` 能跑到测试阶段即为证据，见 §5.6）。
中途出现过两次共享构建红，都不是本组：一次是 `erp/ledger/domain/ErpStockBalance.java:[90]`（T7 在建，
已由 T7 修好），另一次是本组"接口已加、实现未落"的**中间态**（captain 已提醒；本组随后一次性落盘，
接口与实现同批完成，此后未再出现）。

### 5.2 单元测试（本组主证据）

**隔离编译 + JUnitCore（在 T7 的文件修复前拿到本组真实结论）**：

```text
JDK11 javac：74 个 main 源文件（domain/mapper/support/erp.base/erp.master/本组改的两个 service 类）→ exit 0
JDK11 javac：5 个测试类 → exit 0
java org.junit.runner.JUnitCore CtmsProductMasterServiceImplTest ErpMasterRefGuardsTest \
     ErpMasterSnapshotTest ProductMasterRulesTest ErpMasterGuardsTest
==> OK (63 tests)      # 34 + 7 + 4 + 9 + 8（其中 CtmsProductMasterServiceImplTest 由 B3 的 25 → 35）
```

**全模块**：见 §5.6 的最终一次 `mvn -B -pl ruoyi-ctms test` 输出（B3 基线只增不减）。

### 5.3 静态审计

```text
node tools/audit/run-all.js  →  共 10 个审计；失败 0 个；未自证 0 个
```

（本组未改前端；本轮 10/10 全绿。）

### 5.4 真库演练（`env\mysql\...\mysql.exe -uruoyi -pruoyi rad_oa`，脚本 `.cache/t2-masterdata-drill.ps1`）

夹具：`B4MUOM/B4MTYPE/B4MWH/B4MPROD/B4MSTOCK/B4MLEDGER/B4MDOC/B4MITEM`（ASCII，收尾残留 0 行）。

| 断言 | 实测 |
| --- | --- |
| 守卫 SQL 五档计数（`ErpMasterRefMapper.xml` 原文 SQL） | 仓库结存 1 / 物料结存 1 / 仓库流水 1 / 仓库单据 1 / 物料行项 1 → **全部 > 0，服务层会拒绝删除** |
| DB 外键兜底：`DELETE FROM t_ctms_warehouse` | `ERROR 1451 … CONSTRAINT fk_stock_in_warehouse FOREIGN KEY (warehouse_id) …` ✅ |
| DB 外键兜底：`DELETE FROM t_ctms_product` | `ERROR 1451 … CONSTRAINT fk_stock_in_item_product FOREIGN KEY (product_id) …` ✅ |
| 改物料名后历史行项快照 | 档案名 `NEW-NAME`，历史行项 `product_name` 仍为 `OLD-NAME` ✅ |
| 选择器（`enable_flag='1'` 条件） | 启用时命中 1；停用后命中 0；**档案列表仍能看到**（1） ✅ |
| 夹具残留 | **0 行** ✅ |

> 这一层专门覆盖"单测桩验证不了的东西"：4 条 count SQL 的**表名/列名**（12 个 subquery）与
> 真库外键的真实行为。日志：`.cache/t2-masterdata-drill.log`。
>
> 连接方式：本机 MySQL 客户端在 `env\mysql\server\bin\mysql.exe`（与 `tools/run-db-sql.ps1`
> 的默认 `-MysqlExe` 一致；`env\mysql\bin\mysql.exe` 不存在）。**所有夹具 SQL 都是纯 ASCII**
> （`B4M*` 前缀），从文件读入后走 stdin —— 因此绕开了 DEV-ENV §6.33 的
> "PowerShell 管道把中文变成 `?`" 陷阱（那条陷阱只在管道里出现非 ASCII 文本时才触发）。

### 5.5 B3 主数据验收脚本（79 条，不得回归）

```text
powershell -File tools\oa-login.ps1 ; powershell -File tools\ctms-masterdata-check.ps1
```

**实测：`通过 79 项，失败 0 项`，退出码 0，夹具残留 0 行、角色授权残留 0 行**
（日志 `.cache/t2-masterdata-check.log`）。

⚠ **口径说明**：这一次是在**当前运行中的后端 jar（未含本组改动）**上跑的，
它证明的是"本组的 DDL/数据层改动没有让 B3 的 79 条主数据断言回归"，
以及"物料/仓库删除在无引用时的既有路径仍然可用"；
**本组新增的 3 个 `options` 端点与 3+3 条守卫文案**未在这次运行中被覆盖 ——
它们已登记在 §6 的接口断言清单里，等 T10/T11 的 env 窗口（重新打包重启后）复核。

### 5.6 最终一次共享构建 + 审计 + 脚本（收尾轮实测）

见本文件末尾 §7「收尾轮实测记录」（命令与结果原文；避免把中途态写成结论）。

---

## 6. 接口断言清单（供 T10 在 env 窗口复核；请求 + 期望）

> 前置：重新打包并重启后端（本组新增了 3 个端点与 2 处校验，**改完必须重新打包**；
> 参考 `DEV-ENV.md` §6.13 与 `notes/01-base.md` §7 的踩坑记录）。
> 权限点沿用 `ctms:partner:*`（本批**不新增**权限点，B3 的口径）。

| # | 请求 | 期望 |
| --- | --- | --- |
| 1 | `POST /ctms/warehouse {code:"W9", name:"验收仓9"}` | 200；库中新增一行 |
| 2 | 再 `POST /ctms/warehouse {code:"W9", name:"验收仓9-别的名字"}` | 拒绝，msg = `仓库编码已存在` |
| 3 | `POST /ctms/warehouse {code:"W10", name:"验收仓9"}` | 拒绝，msg = **`仓库名称已存在`**（本条是 B4 新增） |
| 4 | `PUT /ctms/warehouse {id:W10 的 id, code:"W10", name:"验收仓9"}` | 拒绝，msg = `仓库名称已存在`（改名撞号） |
| 5 | `PUT /ctms/warehouse {id:W10 的 id, name:"验收仓10"}`（换成自己的名字） | 200（排除自身） |
| 6 | `GET /ctms/warehouse/options` | 200；**只含 `enableFlag='1'`** 的仓库；停用的 `W10` 不在其中 |
| 7 | `PUT /ctms/warehouse {id:W10, name:"验收仓10", enableFlag:"0"}` → 再 `GET /ctms/warehouse/options` | 200；`options` 不含 W10；`GET /ctms/warehouse/list` **仍含** W10（档案页口径） |
| 8 | 用 SQL 造一行 `t_ctms_stock(warehouse_id=W10, qty=5)` → `DELETE /ctms/warehouse/{W10}` | 拒绝，msg = `仓库已有库存结存，无法删除` |
| 9 | 删掉结存、造一行 `t_ctms_stock_ledger(warehouse_id=W10)` → 再删仓库 | 拒绝，msg = `仓库已有库存流水，无法删除` |
| 10 | 删掉流水、造一张 `t_ctms_stock_in(warehouse_id=W10)` → 再删仓库 | 拒绝，msg = `仓库已被单据引用，无法删除` |
| 11 | 清干净引用 → `DELETE /ctms/warehouse/{W10}` | 200（无引用时仍可物理删除） |
| 12 | `POST /ctms/product {name, productTypeId:叶子类型, uomId}` **不传 code**，连续两次 | 200 ×2；两个 `code` **不同**，形如 `MAT0001` / `MAT0002`（类型编码 + 4 位序号） |
| 13 | `POST /ctms/product {code: 已存在的 code}` | 拒绝，msg = `物料编码已存在` |
| 14 | `POST /ctms/product {defaultPrice:-1}` / `{safetyStock:-1}` | 拒绝，msg = `默认单价不能为负数` / `安全库存不能为负数` |
| 15 | `POST /ctms/product {productTypeId: 有下级的类型}` | 拒绝，msg = `只能在叶子类型下挂物料` |
| 16 | `GET /ctms/product/options` / `GET /ctms/uom/options` | 只含启用项（停用物料/单位不在其中） |
| 17 | 造一行 `t_ctms_stock(product_id=P)` → `DELETE /ctms/product/{P}` | 拒绝，msg = `物料已有库存结存，无法删除` |
| 18 | 造一行 `t_ctms_stock_in_item(product_id=P)` → `DELETE /ctms/product/{P}` | 拒绝，msg = `物料已被单据行项引用，无法删除` |
| 19 | 清干净引用 → `DELETE /ctms/product/{P}` | 200 |
| 20 | 停用物料 `P` → 新建/编辑一张单据行项引用 `P` 并保存 | 拒绝，msg 含 `行 N：物料「…」已停用，不能用于新单据`（T1 守卫，T4/T5 的写入路径） |
| 21 | `UPDATE t_ctms_product SET name='新名' WHERE id=P` → 查已保存行项的 `product_name` | 仍是**旧名**（快照隔离；SQL 断言，接口无感） |

---

## 7. 收尾轮实测记录（命令与结果原文）

```text
# 1) 本组隔离编译 + 单测（T7 文件修复前的证据）
JDK11 javac 74 main sources → exit 0
JDK11 javac 5 test classes  → exit 0
JUnitCore → OK (63 tests)

# 2) 真库演练
powershell -File .\.cache\t2-masterdata-drill.ps1
  → 五档计数全 > 0；两条 1451 外键拒绝；快照隔离成立；选择器过滤成立；残留 0 行

# 3) 静态审计
node tools\audit\run-all.js
  → 共 10 个审计；失败 0 个；未自证 0 个

# 4) 共享构建（最终一次）
powershell -File tools\locked-run.ps1 -LockName build -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"
  → 见下方「最终结果」行

# 5) B3 主数据验收脚本（79 条）
powershell -File tools\oa-login.ps1 ; powershell -File tools\ctms-masterdata-check.ps1
  → 见下方「最终结果」行
```

**最终结果**（收尾轮实测，原文）：

```text
# 共享构建（最终一次）
mvn -B -pl ruoyi-ctms test
  → Tests run: 403, Failures: 3, Errors: 2, Skipped: 0   （BUILD FAILURE）
  → 5 条红**全部**在 com.ruoyi.ctms.erp.sales.service.impl.ErpSalesServiceImplTest
     （3F+2E：关联合同不存在或已停用 / 已停用的合同不应可关联 / expected:<0> but was:<-1> /
      found=采购/支出）—— T5 的合同关联在建逻辑，与本组无关
  → 本组 5 个测试类全绿：
       CtmsProductMasterServiceImplTest 35（B3 25 → +10）
       ErpMasterRefGuardsTest 7 / ErpMasterSnapshotTest 4 /
       ErpMasterGuardsTest 8 / ProductMasterRulesTest 9
  → B3 基线的 217 条未出现在失败列表中（仍全绿）

# 静态审计
node tools\audit\run-all.js
  → 共 10 个审计；失败 0 个；未自证 0 个

# B3 主数据验收脚本（79 条）
powershell -File tools\oa-login.ps1 ; powershell -File tools\ctms-masterdata-check.ps1
  → 通过 79 项，失败 0 项；夹具残留 0 行；common 角色残留 ctms 授权 0 行
```

---

## 8. 刻意不做 / 已知边界

| 项 | 说明 |
| --- | --- |
| 未给 `t_ctms_warehouse.name` 加 **DB 唯一索引** | B3 的 13 张表是冻结资产（`ddl-scope.md` §4「只引用、不重建」）；加索引会改动 B3 的 DDL 与 B3 自检/回滚演练的对象集合。因此**名称唯一在服务层强制**（新增 + 改名两条路径），并在 §4.2 冻结文案。若后续要 DB 级约束，属独立变更（需同步 B3 的回滚脚本与自检数字）。 |
| 未实现"仓库支持"软删除 | 规格："仓库只支持启用/停用，MUST NOT 支持物理删除——若已有结存或被引用，删除请求 MUST 被拒绝"。落点：**有引用一律拒绝**（§4.2 三条文案）；无引用时保留 B3 的物理删除语义（B3 验收脚本第 3.3 段依赖这一点）。 |
| 未给 `t_ctms_product` 加"停用后仍可编辑"之类的额外约束 | B4 规格只要求"停用不可用于新行项 + 历史按快照展示"，两条都已落地（前者在 T1 守卫，后者在 §4.3）。 |
| 物料删除守卫**不含**"被合同行项引用" | 合同行项的引用保护属 B3（`countProductsByType` 等），本组只统计 B4 的 18 张表；跨域的联合守卫会引入 B3↔B4 双向依赖。 |
| `keeper_user_id` 仍为"可空 + 无 FK" | B3 的 DDL 结论（仅反查名称）；本组未改动。 |
| 未新增权限点 | 沿用 `ctms:partner:*`（B3 的 25+1 个 `ctms:*` 权限点集合不变），否则会踩中任务 9.1 的"权限点集合双向核对"门禁。 |
| 三个 `options` 端点用的是 `ctms:partner:query` | 与 B3 的 `/ctms/{customer,supplier}/options` 逐字一致（选择器属"查询"动作，不是"列表"）。 |
