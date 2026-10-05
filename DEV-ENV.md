# RuoYi-Vue-OA 二次开发环境手册

> 本文件记录**本机实际可用**的配置（而非项目默认值）。
> 体检时间：2026-10-03 18:35 ｜ 修复落地：2026-10-03 18:50
>
> **2026-10-05 迁移**：项目连同整套环境（MySQL/Redis 便携运行时 + **全部数据库数据**）
> 已从 `H:\dsh\ruoyiOA` 搬到 `F:\dsh\ruoyiOA`。基础设施改为本仓库 `env\` 自带
> （见 `env\README.md`），**不再依赖 `H:\dsh\OA`**。H: 上的原件按用户要求保留未删。

## ⚡ 重启设备后，先跑这两条

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1      # 一键起全部服务
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1 # 重新登录拿 token
```

详见 **§4 常用命令**。（本机 `powershell` = Windows PowerShell 5.1；另有 pwsh 7.6.6，见 §3.5。）

---

工程位置：

| 目录 | 说明 |
| --- | --- |
| `F:\dsh\ruoyiOA\start-env.ps1` | **一键启动全部服务（幂等，可反复跑）** |
| `F:\dsh\ruoyiOA\stop-env.ps1` | **停止本项目后端/前端（`-IncludeInfra` 连基础设施一起停）** |
| `F:\dsh\ruoyiOA\ruoyi-vue-oa-master` | 后端（Maven 多模块，28 个模块） |
| `F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | 前端（Vue2 + Element UI + Vue CLI 4） |
| `F:\dsh\ruoyiOA\tools` | 环境辅助脚本（启动 MQ / 登录 / 流程回归 / RSA 加密） |
| `F:\dsh\ruoyiOA\doc\参考文档` | 审批单等需求参考素材 |
| `F:\dsh\ruoyiOA\logs` | 后端 / 前端 / broker / RabbitMQ 运行日志 |

---

## 1. 服务与端口

| 端口 | 服务 | 启动方式 | 数据目录 |
| --- | --- | --- | --- |
| 80 | 前端 dev server | `.\start-env.ps1`（或 `npm.cmd run dev`） | — |
| 8080 | ruoyi-admin 后端 | `.\start-env.ps1`（或 `java -jar ruoyi-admin/target/ruoyi-admin.jar`） | `F:\dsh\ruoyiOA\uploadPath` |
| 3306 | MySQL 8.0.40（本仓库自带便携版） | `.\start-env.ps1`（直接起 `env\mysql\server`） | `F:\dsh\ruoyiOA\env\mysql\data` |
| 6379 | Redis 5.0.14.1（本仓库自带便携版） | 同上 | `F:\dsh\ruoyiOA\env\redis\data` |
| 5672 | RabbitMQ 3.12.14 | `.\start-env.ps1`（或 `tools\start-rabbitmq.ps1`） | `F:\dsh\ruoyiOA\.cache\rabbitmq` |
| 8544 | ruoyi-im-broker（Netty WebSocket `/im`） | `java -jar ruoyi-im-broker/target/ruoyi-im-broker.jar` | — |
| 15672 | RabbitMQ 控制台 ✅ 已启用 | 随 broker 自动启动 | — |
| 8012 | kkFileView 附件在线预览（**未部署**） | 需另行部署 | — |

> ✅ 2026-10-05 起，MySQL / Redis 的便携版、`my.ini`、数据目录都在本仓库 `env\` 下，
> **与 `H:\dsh\OA` 彻底解耦**（那份 H: 原件保留未删）。
> ⚠️ 两套环境端口相同（3306 / 6379 / 5672），**不能同时启动**。判断当前跑的是哪一套：
> `Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | Select-Object CommandLine`
> —— 命令行里出现 `F:\dsh\ruoyiOA\env` 才是本仓库的实例。
> ⚠️ `env\mysql\my.ini` 的 `datadir` / `basedir` 是**绝对路径**，仓库再换盘符或目录时
> 必须同步修改，否则 MySQL 会在空目录上重新初始化（表现为"数据全没了"）。


## 2. 账号与凭据

| 用途 | 账号 | 密码 |
| --- | --- | --- |
| 系统登录（唯一账号） | `superAdmin` | `admin123` |
| MySQL（应用用） | `ruoyi` | `ruoyi`（仅授权 `rad_oa` 库） |
| MySQL（管理用 root） | `root` | **空口令** |
| RabbitMQ AMQP / 控制台 | `admin` | `admin`（`ruoyi-mq-core` 里写死） |
| Redis | 无密码 | — |
| Druid 监控台 `/druid` | `ruoyi` | `123456` |

## 3. 工具链

| 组件 | 项目要求 | 本机情况 | 结论 |
| --- | --- | --- | --- |
| JDK | 源码 `-source/target 1.8`，Spring Boot 2.5.15 | 全局 `JAVA_HOME` = JDK 21；JDK 11 在 `D:\Program Files\Java\jdk-11` | ✅ 已用 **Maven toolchains** 自动选 11 |
| Maven | 3.x | 3.9.16 @ `C:\Tools\apache-maven-3.9.16` | ✅ |
| Node | README 写 14+ | v24.19.0 | ✅ 已由 `.npmrc` 处理 OpenSSL |
| npm | — | 11.17.0 | ✅ |
| Shell | — | **两者都在**：Windows PowerShell **5.1**（`powershell`，系统自带）与 **pwsh 7.6.6**（`C:\Program Files\PowerShell\7\pwsh.exe`，2026-10-05 补装） | **门禁脚本一律继续用 `powershell` 跑**（既有验收结论都在 5.1 上取得）；pwsh 已实测可跑同一批脚本且结果逐条一致，但**换宿主属于环境变更**，见 §3.5 |

### 3.1 JDK：已由 toolchains 自动解决

全局 `JAVA_HOME` 指向 JDK 21，而本项目是 Java 8 目标 + **Lombok 1.18.20（不兼容 JDK 21）**，
直接编译会报：

```
[ERROR] Fatal error compiling: java.lang.NoSuchFieldError:
  Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field '...JCTree qualid'
```

**注意：不要把全局 `JAVA_HOME` 改成 11** —— 同机的 `H:\dsh\OA`（Spring Boot 3.2.12）需要 JDK 21。

已落地的方案（两者配合，`mvn` 无需任何环境变量）：

1. `C:\Users\Administrator\.m2\toolchains.xml` 登记 `version=11` 的 jdk 工具链；
2. 后端根 `pom.xml` 引入 `maven-toolchains-plugin`。

实测（`JAVA_HOME` 仍是 JDK 21 的情况下）：

```
[INFO] Required toolchain: jdk [ version='11' ]
[INFO] Found matching toolchain for type jdk: JDK[D:\Program Files\Java\jdk-11]
[INFO] Toolchain in compiler-plugin: JDK[D:\Program Files\Java\jdk-11]
[INFO] BUILD SUCCESS
```

> ⚠️ toolchains 只管**编译**。`mvn spring-boot:run` 会用运行 Maven 的那个 JVM（JDK 21），
> 所以**运行**请用 `D:\Program Files\Java\jdk-11\bin\java.exe -jar ...`。

### 3.2 Node 24：已由 `.npmrc` 自动解决

`@vue/cli-service` 4.4.6 内部用 webpack 4，会调用 `crypto.createHash('md4')`，在 OpenSSL 3 下失败：

```
md4 FAIL: ERR_OSSL_EVP_UNSUPPORTED / error:0308010C:digital envelope routines::unsupported
```

`ruoyi-vue-oa-ui-master\.npmrc` 已自动注入，无需手动设置环境变量：

```ini
node-options=--openssl-legacy-provider
registry=https://registry.npmmirror.com
legacy-peer-deps=true
```

### 3.3 Maven 代理：**没起来时 `mvn test` 会失败**（2026-10-05 定型）

全局 `~/.m2/settings.xml` 把 http/https 全部代理到 `127.0.0.1:7899`，而那个代理
**不是随机器自启的守护进程**。它没起来时，任何需要**下载依赖**的 Maven 目标都会直接失败：

```
Could not transfer artifact … from/to public (https://maven.aliyun.com/…):
Connect to 127.0.0.1:7899 [/127.0.0.1] failed: Connection refused: getsockopt
```

典型受害者是 **`mvn test`**：surefire 要按需下载 provider（`surefire-junit3` / `surefire-junit4`），
本地 `.m2` 里只有 `.lastUpdated` 标记，于是构建报 `Failed to collect dependencies` ——
**看起来像"测试挂了"，其实是代理没起**。

实测本机到 `maven.aliyun.com` / `repo.maven.apache.org` 的 **443 直连是通的**，所以：

- `tools\maven-settings-direct.xml` —— 一份**不声明任何 proxy** 的 settings（不改全局配置）；
- 后端仓库根的 `.mvn\maven.config` —— Maven 3.3+ 会自动读取的 CLI 选项文件，里面写了
  `-s F:/dsh/ruoyiOA/tools/maven-settings-direct.xml`。于是 `mvn` **不带任何参数**也走直连。

```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -pl ruoyi-workflow test          # 无需 -s，直接可跑（见 §4.4）
```

> ⚠ 需要拉 **GitHub** 资源时（那类地址直连不通，必须走代理）：把 `.mvn\maven.config` 里那一行注释掉，
> 或在命令行显式 `-s "$env:USERPROFILE\.m2\settings.xml"`。全局 `~/.m2/settings.xml` **没有被改动**。
> ⚠ 本模块此前**没有任何测试框架**，`src/test` 下只有 `main()` 形式的冒烟程序（`mvn test` 跑出来是
> `Tests run: 0`）。2.0 B2 为"内置版式常量"引入了 JUnit 4（版本由 `spring-boot-dependencies` 管理）。

### 3.5 pwsh 7 共存：**能跑，但门禁仍推荐用 `powershell`（5.1）**（2026-10-05 实测）

本机现在两个宿主都有：

| 宿主 | 路径 | 版本 |
| --- | --- | --- |
| `powershell` | `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` | **5.1**（系统自带） |
| `pwsh` | `C:\Program Files\PowerShell\7\pwsh.exe`（已在机器 PATH，**新开的终端才生效**） | **7.6.6** |

**关键差异（正是 §6.33 那个"中文变 `?`"坑的根源）**：

| 项 | PowerShell 5.1 | pwsh 7.6.6 |
| --- | --- | --- |
| `$OutputEncoding` 默认 | **`us-ascii`** | **`utf-8`** ✅ |
| `[Console]::OutputEncoding` 默认 | ANSI（GBK） | **`utf-8`** ✅ |
| 管道喂中文 SQL 给 `mysql.exe` | 变 `?`（`SELECT HEX('合同台账')` → `3F3F3F3F`） | **正常**（→ `E59088E5908CE58FB0E8B4A6`） |

> ⚠ **但在脚本里**该坑与宿主无关：`tools\*.ps1` 都已显式设 `$OutputEncoding = UTF8`，
> 所以在两个宿主下结果一致。**不要因为换到 pwsh 就把脚本里那行删掉** —— 它保证的是"换宿主/换机器都不坏"。

**兼容性实测（2026-10-05，pwsh 7.6.6 逐条跑，结果与 5.1 完全一致）**：

| 脚本 | 5.1 | pwsh 7.6.6 |
| --- | --- | --- |
| `ctms-perm-audit.ps1` | 15/15 差异为空 | **一致** |
| `ctms-migration-check.ps1` | 52/0 | **一致** |
| `ctms-contract-check.ps1`（261 条，含 4.8b 矩阵） | 261/0 | **一致** |
| `ctms-commercials-check.ps1` | 116/0 | **一致** |
| `ctms-e2e-check.ps1` | 91/0 | **一致** |

**21 个 `.ps1` 逐一 `Parser::ParseFile` 全部 OK**；静态扫过：无 `Get-WmiObject`、`System.Web`、
`Microsoft.Win32`、`Windows.Forms`、`-AsHashtable` 等 7.x 不兼容/5.1 专有用法。
唯一的 `Add-Type` 在 `ctms-contract-check.ps1:197`，是 `-AssemblyName … -ErrorAction SilentlyContinue`
的**兼容写法**（pwsh 下程序集已内置、该调用报错但被吞掉，实测无影响）。

**规矩**：
1. **交付门禁与验收记录一律继续用 `powershell`（5.1）** —— 既有全部结论都在 5.1 上取得，**换宿主属于环境变更**，会让"这条断言在哪个宿主下通过"变得不可比；
2. 新写的脚本要**同时兼容两个宿主**（别用 `??`、`-Parallel`、`-AsHashtable` 等 7.x 专有语法，除非显式声明只支持 pwsh）；
3. 若某天要正式迁到 pwsh，**必须把 §7 的 16 项门禁在两个宿主下各跑一遍并逐条留档**，不能只凭"都能跑"就切换。

## 4. 常用命令

### 4.1 一键启停（推荐，脚本在仓库根目录）

```powershell
cd F:\dsh\ruoyiOA

# 启动全部：MySQL → Redis → RabbitMQ → 后端 8080 → 前端 80
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1

# 常用变体
powershell ... -File .\start-env.ps1 -Only Infra        # 只起 MySQL/Redis/MQ
powershell ... -File .\start-env.ps1 -SkipFrontend      # 不起前端（后端联调用）
powershell ... -File .\start-env.ps1 -Rebuild           # 先重新打包后端再起

# 停止（默认只停本项目的后端 + 前端，基础设施保留）
powershell -NoProfile -ExecutionPolicy Bypass -File .\stop-env.ps1
powershell ... -File .\stop-env.ps1 -IncludeInfra       # 连 MySQL/Redis/MQ 一起停

# 重新登录拿 token（token 会过期；重启用接口脚本前先跑这个）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1

# 简化流程组合矩阵回归（跑在真实环境上，20 条断言）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1

# 签名组件验收（预存签名/节点策略/只追加/只读字段/重签撤签，44 条断言；会写入不可删的签名记录）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\sign-feature-check.ps1

# 越权防护验收（PRD 11.3 / AC-35：打印数据、打印留痕、签名与撤签的越权，30 条断言；
#              2.0 B3 §9.3 追加合同台账 15 条 → 现共 45 条）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1

# 2.0 B2：多内置打印模板回归（4 套版式的标题/栏目/签批栏、切 key 后打印件随之变化、
#                              builtin_print_key 真实往返、非法 key 被拒、优先级链未变，98 条断言）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\print-builtin-check.ps1

# 2.0 B1：流程↔模板绑定回写链路（真实环境）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b1-binding-check.ps1

# 前端用例（35 条：签批栏与"是否内置"解耦 / 纸张方向页数测算 / Logo / 留痕硬门禁 / 四处入口一致）
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master ; npm.cmd run test:unit

# 后端单测（19 条：内置版式常量逐项对应 PRD 附录 A、优先级链、非法 key 降级）
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master ; mvn -B -pl ruoyi-workflow test
```

两个脚本都是**幂等**的（端口已监听就跳过），可反复执行；所有进程用 WMI 拉起，
完全脱离调用方句柄，不会把终端/CI 挂住。

> ⚠️ 本机 `powershell` = Windows PowerShell 5.1 **（推荐继续用它跑所有验收脚本）**；
> 另已安装 **pwsh 7.6.6**（`C:\Program Files\PowerShell\7\pwsh.exe`），兼容性实测见 §3.5。
> ⚠️ 这些 `.ps1` 都存成 **UTF-8 with BOM** —— 5.1 读无 BOM 的 UTF-8 会把中文注释
> 当 ANSI 解码，直接 `Unexpected token` 解析失败。新增脚本时务必保持带 BOM。
> ⚠️ `.ps1` 里**不能写 `/** … */` 块注释**（那是 JS 的写法，PowerShell 只有 `<# … #>`）；
> 另外双引号串里的 `"$var:%"` 会被当成作用域变量而解析失败，要写 `"${var}:%"`。
> ⚠️ 用 `| Select-Object -First N` 截断脚本输出 = **提前掐断上游管道**，脚本的 `finally`
> 清理段跑不完，会留下测试数据（踩过：留下几枚预存签名）。要看前面几行就跑全量再翻日志。

### 4.1.1 表单控件改动的固定动作（踩过多次，2026-10-04 定型）

> **表单有两条渲染路径，新增/改控件必须两条都适配**，否则"拟稿时好看、审批时难看或丢字段"：
>
> | 场景 | 渲染器 | 位置 |
> | --- | --- | --- |
> | 拟稿 / 发起（`pageType=0`） | `Parser` + 自定义控件 | `UI/src/components/parser/Parser.vue`、`components/render/render.js` |
> | 审批 / 详情（`pageType!=0`） | 只读 `label:value` 视图 | `UI/src/views/workflow/flow-form/component/view-form.vue` |
> | 设计器画布 | 与拟稿页**同一个** `render` 组件 | `UI/src/views/tool/build/*` |
> | 打印件 | 按单据快照里的 `formData.fields` 排版 | `UI/src/views/workflow/print/index.vue` |
>
> 新控件的落地清单（少一处就会"设计器能拖、运行时白屏"或"审批页看不见"）：
> 1. 控件本体 `UI/src/components/form/design/DesignXxx.vue`；
> 2. `components/render/render.js` 注册（拟稿页 + 设计器画布共用）；
> 3. `utils/generator/config.js` 加进左侧组件清单（`formOaComponents` 组）；
> 4. `views/tool/build/RightPanel.vue` 加属性配置项；
> 5. `views/workflow/flow-form/component/view-form.vue` 适配只读视图；
> 6. 需要值转换的话，后端 `ruoyi-biz-sdk/.../enums/ComponentTypeEnum.java` 补分支。
>
> ⚠️ 打印件的字段表来自**单据自己保存的 `formData.fields`**（不是 `PrintData.formSchema`，
> 后端那个字段从未赋值、前端也从不读）。手写夹具时 `form_data` 必须是
> `{"formData":{"fields":[…]},"valData":{…}}` 这种合法 JSON —— 解析失败会**静默**退回
> 模板里的内置 field_map，看起来就像"打印排版没生效"（我为此误判过一次）。
>
> ⚠️ 动态表单的 `PUT` 是**版本化**的：旧行置 `enable_flag=0`，插一条新版本（新 id），
> 列表只返回启用版本。所以：① 更新后要拿新 id（别拿旧 id 再 PUT，会攒出多份启用版本）；
> ② `t_template.form_id` 指向的是**某一版**，改过表单的模板不会自动跟随新版本。

### 4.2 手动命令（脚本出问题时的对照）

```powershell
# ---- 基础设施（按顺序）----
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1 -Only Infra   # MySQL + Redis + RabbitMQ
# 等价的手工写法（脚本出问题时的对照）：
& "F:\dsh\ruoyiOA\env\mysql\server\bin\mysqld.exe" --defaults-file="F:\dsh\ruoyiOA\env\mysql\my.ini"   # MySQL 3306
& "F:\dsh\ruoyiOA\env\redis\server\redis-server.exe" --port 6379 --bind 127.0.0.1 `
      --dir "F:\dsh\ruoyiOA\env\redis\data" --appendonly yes --logfile "F:\dsh\ruoyiOA\env\redis\redis.log"
powershell -NoProfile -ExecutionPolicy Bypass -File "F:\dsh\ruoyiOA\tools\start-rabbitmq.ps1"          # RabbitMQ 5672
# ⚠️ 迁移前的 H: 原件（`H:\dsh\OA` 共享运行时 + `H:\dsh\ruoyiOA` 项目副本）按用户要求保留未删；
#    确认 F: 环境无误后可自行清理。两套环境同端口，**不能同时启动**。

# ---- 后端：构建（无需设置 JAVA_HOME，toolchains 会自动用 JDK 11）----
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests install                       # 全量
mvn -B -DskipTests -pl ruoyi-admin clean package # 单模块（**要 clean**，否则可能把旧子模块打进 jar）

# ---- 后端：运行（必须用 JDK 11 的 java）----
& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-admin\target\ruoyi-admin.jar
& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-im-broker\target\ruoyi-im-broker.jar

# ---- 前端 ----
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm.cmd run dev        # http://localhost/   （必须 npm.cmd：npm.ps1 被执行策略拦下，日志为空）
npm run build:prod     # 产物 dist/
```

### 4.3 重启设备后的恢复顺序

1. `.\start-env.ps1`（一条命令起全部）
2. `.\tools\oa-login.ps1` —— **重新登录**。token 有有效期，不重新登录的话接口脚本会报
   「认证失败，无法访问系统资源」。
   > 判断 token 是否有效要**校验响应里的 `code`**，不能只看某个字段空不空 ——
   > 401 的响应体同样是没有 `total` 字段的错误对象，容易被误读成"调用成功"。
3. `.\tools\flow-regression.ps1` 确认流程引擎无回归


## 5. 体检发现与处理状态

| 级别 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P0 | 全局 `JAVA_HOME`=JDK 21，Lombok 不兼容 | `mvn` 编译直接失败 | ✅ **已修复**：toolchains + 根 pom 插件 |
| P0 | Node 24 + webpack4 的 OpenSSL | 前端起不来 / 打包失败 | ✅ **已修复**：`.npmrc` 注入 |
| P1 | 全工程无 git 仓库 | 无版本控制、改坏无法回滚 | ✅ **已修复**：建了 3 个仓库并提交基线 |
| P1 | `ruoyi-mq` 版本写成 `${ruoyi.mq.version}` | Maven 报 malformed project；`-pl` 不带 `-am` 构建失败 | ✅ **已修复**：改为字面量 `1.0.1`，并清掉 .m2 里的脏产物 |
| P1 | `bin\run.bat` / `package.bat` / `clean.bat` 直接调 `java`/`mvn` | 运行会走 JDK 21 失败 | ⚠️ 未改（保持上游原样），**请勿直接用**，用第 4 节命令 |
| P2 | RabbitMQ 控制台未启用 | 无 Web 管理界面 | ✅ **已启用** → http://localhost:15672 |
| P2 | kkFileView（8012）未部署 | 附件在线预览不可用 | ⬜ 待需要时部署 |
| P2 | `spring-boot-devtools` 已开启 | 从 jar 运行时不生效 | ⬜ 按需（IDE 或 `spring-boot:run`） |
| P2 | 前端 `vue.config.js` 的 `open: true` | 每次 `npm run dev` 弹浏览器 | ⬜ 嫌烦可改 `false` |
| P0 | Maven 全局代理 `127.0.0.1:7899` **不自启** | 代理没起来时 `mvn test` 直接失败（`Failed to collect dependencies`），看起来像测试挂了 | ✅ **已修复**：`tools\maven-settings-direct.xml` + 后端根 `.mvn\maven.config` 直连（见 §3.3），全局 settings 未改动 |

补充说明：

- **`package-lock.json` 已纳入版本控制**（前端 `.gitignore` 原本忽略它）。本机 Node 24 下
  `vue` / `vue-template-compiler` 都被 `^` 解析到 2.7.16，锁文件保证环境可复现。
- Maven 的 `settings.xml` 配了本机代理 `127.0.0.1:7899`，`github.com` 直连不通，
  下载 GitHub 资源时需走代理（如 `Invoke-WebRequest -Proxy http://127.0.0.1:7899`）。

### 5.1 git 基线（已建好）

| 仓库 | 位置 | 基线提交 | 跟踪文件 |
| --- | --- | --- | --- |
| 外层（手册/脚本/参考文档） | `F:\dsh\ruoyiOA` | `2fbfc99` | 15 |
| 后端 | `F:\dsh\ruoyiOA\ruoyi-vue-oa-master` | `23ece02` | 1094 |
| 前端 | `F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | `beadb41` | 675 |

三个仓库均已配置仓库级身份 `OA Dev <oa-dev@local>`，**请按需改成你自己的**：

```powershell
git config user.name "你的名字" ; git config user.email "you@example.com"
```

外层 `.gitignore` 排除两个子工程（各自独立仓库）与 `logs/`、`uploadPath/`、`.cache/`。

## 6. 二次开发要点（本项目特有）

1. **MQ 是硬依赖**：`/biz/form/save` 的「我起草的」「新增待办」都走 RabbitMQ 异步任务。
   关掉 `spring.rabbitmq.enabled` 会让保存表单直接报 `queue对应的exchangeKey为空！`。
2. **`system.store.queue`** 由 `ruoyi-im-broker` 声明，`ruoyi-admin` 监听它。
   不启 broker 时 admin 会因 `missing-queues-fatal` 启动失败。
3. **`ruoyi-mq-core` 里写死了 `spring.rabbitmq.username/password=admin/admin`**，
   以及 `host=127.0.0.1`、`port=5672`。换环境时改这个 `application.properties`。
4. **流程首节点不支持"按角色/动态选人"**：设计器对这两种都生成
   `flowable:candidateUsers="${<节点id>_user}"`，而「新启流程」调用 `startFlow` 时
   只传 `templateId`、不传任何流程变量 → Flowable 求值失败
   `Unknown property used in expression`。首节点请用**指定人员**。
5. **单节点流程不产生待办**：`createNextTodo` 只在完成任务时执行；想看「待办」流程至少两个审批节点。
6. **打开「新启流程」模板页会自动发起一个流程实例**（`pageType=0` 时自动 `startFlow`），
   每进一次页面／刷新一次就多一个实例，调试时注意清理。
7. **动态表单**存于 `t_template_dynamic_form.content`（表单设计器 JSON），
   列表接口过滤 `enable_flag=1 and del_flag=0`；保存入口在「系统工具 → 表单构建」。
8. 新增业务模块建议照 `ruoyi-kbs` / `ruoyi-schedule` 的分层
   （controller/domain/mapper/service + `src/main/resources/mapper/**/XxxMapper.xml`），
   并在根 `pom.xml` 的 `<modules>` 与 `ruoyi-admin/pom.xml` 里登记。
9. **改主题色必须同时改三处，否则会出现"两套蓝"**（已踩过）：
   1. `src/assets/styles/element-variables.scss` 的 `$--color-primary` —— Element UI **编译期**主题；
   2. `public/styles/theme-chalk/index.css` —— `ThemePicker` 运行时拉取并整体重着色的那份静态 CSS，
      必须与 (1) 的主色 + 完整色簇（tint 0.1~0.9 + shade 0.1）一致；
   3. `src/components/ThemePicker/index.vue` 的 `ORIGINAL_THEME` —— 重着色算法的**锚点**，
      它必须等于 (2) 里实际写死的主色，否则换色静默失效或串色。
   另外 `src/store/modules/settings.js` 的默认 `theme` 与旧值迁移也要跟着改：
   `ThemePicker.created()` 发现 `settings.theme !== ORIGINAL_THEME` 就会主动换色，
   老用户 localStorage 里的旧色会把品牌蓝刷回去。
10. **`public/styles/theme-chalk/index.css` 是 `public/` 静态资源**，`npm run dev` 不编译它，
    改完**必须重启 dev server**（或至少让浏览器重新拉取，它没有 HMR）。
11. **运行时换色只对 dev 有效**：`ThemePicker` 是靠改写内存里的 `<style>` 标签实现的，
    生产构建的 CSS 走 `<link>`，改不到。所以页面里**不要写裸色值**，
    用 `var(--oa-color-primary)`（令牌见 `src/assets/styles/oa-tokens.scss`，由 `main.js` 全局引入）。
12. **手工删 Flowable 部署（裸 SQL）之后必须重启后端**：引擎有部署/流程定义缓存，
    只删库不改缓存会留下"缓存里有、库里没有"的悬空定义，之后按 key 启动流程会报错。
    正常途径是用引擎 API 的 `repositoryService.deleteDeployment(id, true)` 级联删除。
13. **打 `ruoyi-admin` 的 fat jar 之前必须先停后端，否则可能打出"旧包"而 Maven 不报错**（已踩过，代价很大）。
    后端进程会一直占着 `ruoyi-admin/target/ruoyi-admin.jar`；Windows 下
    `spring-boot:repackage` 无法替换被占用的文件，但它**仍然打印
    `Replacing main artifact with repackaged archive` 和 `BUILD SUCCESS`** ——
    结果是你以为更新了，实际跑的还是几小时前的旧包，新接口一律 404。
    **可靠做法**：
    ```powershell
    Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force
    mvn -B -DskipTests -pl ruoyi-workflow install          # 变更的子模块先 install
    mvn -B -DskipTests -pl ruoyi-admin clean package       # 再单模块打 fat jar
    # 校验产物真的换了（不要只看 BUILD SUCCESS）
    (Get-Item ruoyi-admin\target\ruoyi-admin.jar).LastWriteTime
    ```
    `clean` 很关键：它保证重新产出；并且**打包后一定要核对 jar 的时间戳**。
14. **`/workflow/**` 下的接口用"未认证请求返回 401"是验证不了路由的**（已踩过）：
    RuoYi 的安全过滤器在**路由之前**就拦掉未认证请求，所以
    `/workflow/print/data/x` 与 `/workflow/print/__不存在的路径__` **返回完全一样**，
    这个测试没有区分力（带 token 再调才会看到 401 与 404 的区别）。
    要真正验证接口，得拿一个登录后的 token：
    ```powershell
    # 验证码默认开启，临时关掉即可（改完记得还原！）
    update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';
    # RuoYi 把配置缓存在 Redis，改库不够，还要删缓存键（没有 redis-cli 时可用 TCP 发 RESP）
    #   DEL sys_config:sys.account.captchaEnabled
    $enc = node tools\rsa-encrypt.js "admin123"      # 取 RSA 加密后的口令
    # POST /login {username, password:$enc, code:"", uuid:""} -> token
    # 之后所有接口带 Authorization: Bearer <token>
    ```
    **验证完必须把 `captchaEnabled` 还原为 `true` 并再删一次缓存键。**
15. **`businessId` 与 Flowable 的 `businessKey` 是同一个值**（证据：
    `TodoAsyncService` 里 `runtimeService.updateBusinessKey(todo.getProcInsId(), todo.getBusinessId())`）。
    所以 `businessId → procInsId` 直接用历史查询反查即可，
    **不需要另建映射表**：`historyService.createHistoricProcessInstanceQuery()
    .processInstanceBusinessKey(businessId)`，一套查询同时覆盖运行中与已结束的实例。
16. **单据模板ID（`t_template.id`）在哪里**：它**不是**流程定义的 key，
    **也不一定**是流程变量（踩过：用 `FlowTaskDto.getProcDefKey()` 当单据模板ID，
    表单服务直接报 `BaseException: 模板ID为空`）。它随业务记录落在
    `t_workflow_todo` / `t_workflow_done` / `t_workflow_recycle` 的 `template_id` 列上，
    按该顺序取第一个命中的。
17. **用浏览器工具断言页面内容时，必须等"终态"，否则会把过渡动画的中间态当成功能坏了**（已踩过）。
    `layout/components/AppMain.vue` 用的是 `<transition mode="out-in">` ——
    **旧视图先离场、新视图才进场**，而面包屑等在 `router-view` **之外**的元素会先更新。
    于是会出现"URL 变了、面包屑变了，但主内容还是上一页"的假象，看起来非常像路由 bug。
    异步 chunk + 接口请求期间都属于这个窗口。
    正确做法：**读取该页独有的根元素**（例如打印页的 `.print-page`）来判定是否已挂载，
    而不是读通用容器，也不要拿面包屑与主内容的一致性当证据。
    同一条主题：**验证手段失效时，结论不可信**（参见第 14 条）。
18. **表单 schema 里，选项标签在 `__slot__.options`，不在 `__config__`**：
    `__config__` 只有 label / showLabel / tag / span / required 这些；
    el-select / el-radio-group / el-checkbox-group 的 `{label,value}` 列表在字段的
    `__slot__.options`；el-switch 的中文在 `active-text` / `inactive-text`。
    打印件上要把 `leave` 显示成"请假"就得从这里取，不必查字典接口。
19. **`IBizFormService.getBizForm()` 返回的 `formData` 是一段 JSON 字符串**（不是对象）：
    解析后形如 `{ formData: { fields:[{__config__,__vModel__}] }, valData: { 字段 → 值 } }`，
    **值在 `valData`、中文标签与顺序在 `fields`**。只按对象递归会一个字段都取不到。
20. **上传/取文件用经典链路 `/common/upload`，不要用新文件模块 `/file/operate/**`**（已踩过）。
    现状：
    * `t_file_storage` 里**只有 1 条**演示记录（`storage_type=minio`），
      但 **MinIO 根本没在跑**（9000/9001 未监听），且它记录的本地路径下**文件也不存在**；
    * 新文件模块配的本地路径是 `E:/ruoyi/upload`（见
      `ruoyi-file/src/main/resources/env/dev/application-file.properties`），
      而真正有文件的目录是 `F:/dsh/ruoyiOA/uploadPath`（经典 RuoYi 的 `RuoYiConfig.getProfile()`）；
    * `/file/operate/downloadfile` 是 **POST**（写成 GET 会收到
      `Request method 'GET' not supported`），且入参 `FileQO` **没有 `@RequestBody`** ——
      参数必须走 query，放 JSON body 会得到"参数错误"；
    * `/file/operate/uploadfile` 走 `UploadFile` **分片上传**协议，简单 multipart 会得到 `上传失败`。
    **可用做法**：
    ```
    POST /common/upload   (multipart, 字段名 file)
      → { "fileName": "/profile/upload/2026/10/04/xxx.png",
          "url": "http://localhost:8080/profile/upload/2026/10/04/xxx.png" }
    ```
    `fileName` 是**相对路径**，通过静态资源映射 `/profile/**` 直接可访问，
    前端加 `process.env.VUE_APP_BASE_API` 前缀即可 `<img src>`：
    - `http://localhost/dev-api/profile/upload/....png` → **200 / image/png / 真实字节**；
    - `http://localhost/profile/upload/....png`（**不加前缀**）→ 200 但返回的是
      **SPA 的 HTML**（`text/html`），图是取不到的。
    落了库的路径字符串（如签名记录的 `file_id`）建议按"以 `/` 开头 = 可直接访问的相对路径"
    来判定，否则再走文件模块的 POST 取 blob。
21. **后端不要用"会话的后台任务"方式启动，要用脱离进程**（已踩过两次）。
    用后台任务（`pwsh ... & java -jar ...`）启动时，任务结束时**进程会被一起杀掉** ——
    表现为任务以 `exit code: 1` 结束、日志停在一条正常的查询上、
    **没有任何异常栈**（Windows 下强杀就是没有信号标记的 exit 1）。
    很容易误判成"后端崩了"，其实是"被清理了"。
    正确做法：
    ```powershell
    $java = "D:\Program Files\Java\jdk-11\bin\java.exe"
    $jar  = "F:\dsh\ruoyiOA\ruoyi-vue-oa-master\ruoyi-admin\target\ruoyi-admin.jar"
    Start-Process -FilePath $java -ArgumentList @('-jar', $jar) `
      -RedirectStandardOutput "F:\dsh\ruoyiOA\logs\backend-out.log" `
      -RedirectStandardError  "F:\dsh\ruoyiOA\logs\backend-err.log" `
      -WindowStyle Hidden -PassThru
    ```
    判断"是崩溃还是被清理"的方法：看日志末尾有没有异常栈。**只有异常的才是崩溃。**
    另外：**杀后端之前别忘了它也占着 `ruoyi-admin.jar`**（见第 13 条）。
22. **PowerShell 双引号串里 `$var?xxx` 会被当成一整个变量名**（2026-10-05 踩过，静默出错）。
    URL 里带 query 时最容易中招：
    ```powershell
    $CloneId='AAA'; $pt='BBB'
    "/workflow/print/template/$CloneId?printTplId=$pt"     # ⇒ /workflow/print/template/=BBB   ← 变量被吃掉了
    "/workflow/print/template/${CloneId}?printTplId=${pt}" # ⇒ /workflow/print/template/AAA?printTplId=BBB  ✅
    ```
    `$CloneId?printTplId` 被解析成**一个变量名**（未定义 → 空串），请求静默发到错误地址，
    脚本只会看到"没取到数据"，**不会报错**。规则：变量后紧跟 `?` `:` `%` 等字符时一律写 `${var}`
    （这条与 §4.1 里 `"$var:%"` 的坑同源）。
23. **`t_workflow_form.template_id` 对动态表单恒为 NULL，"单据模板"要靠 `t_workflow_my_draft`**（2026-10-05 实测）。
    `/biz/form/save` 写 `t_workflow_form` 时**不带 template_id**（那一列是给业务表单用的），
    模板关联是**异步**（走 MQ）落到 `t_workflow_my_draft.template_id` 的。
    后果：`PrintTemplateMapper.selectTemplateIdByBusinessId` 只查 待办/已办/回收站/`t_workflow_form` 四路时，
    **草稿（尚未发起流程）会解析到 templateId=null** → 回退默认内置版式（contract）→
    一张 fund 版式的单据被打成合同版式，且字段表整片为空。
    B2 已补第五路 `t_workflow_my_draft`（放在最后，让"已进入流程"的记录优先）。写打印/草稿相关断言时按这个口径。
24. **Vue 2.7 下 `<template v-if>` 后面跟一个 `v-else` 的普通元素，两个分支会一起渲染**（2026-10-05 浏览器实测抓到）。
    症状很隐蔽：打印模板配置页的实时预览里，"按单据表单字段自动排版"与一堆栏目标签**同时出现**。
    写法上宁可啰嗦：同一元素内用 `v-for` + `v-if="!list.length"` 表达互斥，
    或让两个分支都是 `<template>`（`<template v-if>`/`<template v-else>` 实测正常），
    不要依赖 `<template v-if>` 与普通元素之间的 `v-else` 配对。
25. **写验收脚本的收尾条件不要用中文名做判据**（2026-10-05 踩过，代价是一次假失败）。
    夹具清理/存在性判定一律用 **ASCII 的 id**（如 `template_id='$CloneId'`）：
    中文匹配本身在本机是可靠的（`Get-Content -Raw -Encoding UTF8 | mysql` 走 stdin 没问题），
    但把它当"清干净了没"的**唯一判据**就多了一个失败面 —— 一次异常退出留下的脏行会让下一轮
    出现"内置版式没生效"这类**看不出原因**的假失败。收尾条件只看 id，并在夹具建立前**先预清理一次**（幂等）。
26. **纸样尺寸绑错元素 = 工具条变成"一张纸"盖住纸面**（2026-10-05 实测，**用户截图发现**；
    这是修 AC-64 时自己引入的回归，值得完整记下来）。
    现象：打印预览浮层里纸面几乎全被一块空白盖住，只剩**左边一条**内容（合同金额/签批栏的左侧 label），
    而「打印 / 刷新数据」按钮跑到底部并被顶出视口。
    原因：把"纸样尺寸"对象 `{ width: '210mm', minHeight: '297mm' }` 同时绑给了 `.print-toolbar`。
    工具条是 `position: sticky; top: 0; z-index: 10; background: #fff`，
    被 `min-height: 297mm` 撑成一张纸那么高之后，就**直接盖在纸面上**（同层叠上下文里 z-index 高的在上）。
    规则：**盒模型类样式（尤其 `min-height` / `height`）只绑给它所描述的那个元素**；
    工具条这类悬浮控件不要绑高度，宁可少一分"跟着纸张变宽"的对齐。
    回归守卫：`npm run test:unit` 的「**纸样尺寸只绑给纸面、绝不能绑给工具条**」一条
    （静态读 SFC 源码断言 `.print-paper` 有 `:style="paperBoxStyle"` 且 `.print-toolbar` 无 `:style`）。
    同一轮还修了浮层副标题：`PrintDialog.vue` 里写死的「A4 纵向 · 仅表单信息」在 2.0 之后**已过期**
    （纸张/方向与出栏内容都改成由生效打印模板决定），于是出现"页内工具条说『表单信息 + 签批栏』、
    浮层标题却说『仅表单信息』"的自相矛盾。改为不会过期的一句话，并加了同样的静态断言。
    排查心得：`browser_snapshot` 里 iframe 内的按钮若被标成 **`[outside viewport]`**，
    通常说明该 iframe 的布局被撑坏了（本例正是靠这个信号确认"修好了"：按钮不再带该标记）——
    **纯文本的页面工具也能发现布局级故障**，不要以为只有截图能看出来。
27. **`Long` 会让 Redis 计数器彻底失效：FastJson2 把它写成 `0L`**（2026-10-05 踩过，**最隐蔽的一个**）。
    症状：`/serial/config/genSerialNo/**` 全部 500（`获取编号失败！`），而同一个配置的**预览接口一切正常**。
    根因：计数器初值用 `redisCache.setCacheObject(key, value)` 写入，值序列化器是 FastJson2 ——
    它把 `Integer 0` 写成 `0`（可以被 `INCR`），把 `Long 0` 写成 **`0L`**，于是随后的
    `redisTemplate.opsForValue().increment(key, 1)` 抛：

    ```
    ERR value is not an integer or out of range   （command: INCRBY）
    ```

    实测证据：`redis-cli get code:gen:seq:<confId>` → `0L`。
    为什么只在真实环境暴露：单测用内存桩（不经过序列化）、静态审计只看源码，**都发现不了**。
    规则：**写 Redis 计数器初值时，值必须是 `Integer`**（或直接用 `redis-cli set <key> 0` 那种裸整数形态）；
    改了类型就先跑一次真实取号。另：`RedisCache.getIncr()` 的行为是"读到什么就 INCR 什么"，
    所以任何"顺手把 int 改成 long"的重构都可能踩到这里。
28. **`Set-Content -Encoding UTF8` 会写 BOM，而 `javac` 不接受 BOM**
    （`错误: 非法字符: '\ufeff'` + `class, interface, or enum expected`）。
    PowerShell 5.1 里 `Set-Content`/`Out-File -Encoding UTF8` 一律带 BOM，
    用它改 `.java` / `.js` / `.sql` / `.md` 会直接改坏文件（本仓库只有 `.ps1` 需要 BOM）。
    改文本一律用 `[System.IO.File]::WriteAllText($p, $text, [System.Text.UTF8Encoding]::new($false))`；
    需要给 `.ps1` 加 BOM 时才用 `$true`。已在本次 B3 实施中因此浪费了一轮编译。
29. **Javadoc 里出现 `*/` 会提前结束注释**：写路径通配时最容易中招 ——
    `{@code specs/ctms/*/spec.md}` 里的 `*/` 把注释**就地关闭**，后面全被当成代码，
    `javac` 报 `class, interface, or enum expected` 外加一个"意外的 `：`"（中文全角冒号），
    看起来完全不像注释问题。写成 `{@code specs/ctms/&lt;能力&gt;/spec.md}` 即可。
30. **`AjaxResult.success(String)` 命中的是 `success(String msg)` 重载，值落在 `msg` 而不是 `data`**。
    实测 `GET /system/config/configKey/warranty_window_days` 返回 `{"msg":"30","code":200}` ——
    没有 `data` 字段。用 `$resp.data` 读会得到空值，很容易误判成"接口没返回/配置没生效"。
    判断 RuoYi 接口取值时，先看清它落在 `data` 还是 `msg`（`AjaxResult` 有 `success()`、
    `success(Object data)`、`success(String msg)` 三个重载，字符串会优先绑到 `msg`）。
31. **二开菜单 id 的前缀是共享的**：`9F2C…` 已被 B1（`…A1~A5` 流程设计）与 B2（`…B1~B3` 打印模板）占用，
    B3 用 `9F2C` + 24 个 0 + 4 位序号（`0001~0041`）。**新增批次请继续顺延，不要复用已占用的后缀**；
    统计"本批菜单行数"时**不要**用 `menu_id LIKE '9F2C%'`（会连别的批次一起数进来），
    要用精确 id 白名单或权限点前缀（如 `perms LIKE 'ctms:%'`）。
32. **在 PowerShell 里生成 SQL 时，MySQL 的反引号会被当成 PowerShell 转义符**。
    双引号 here-string（`@"..."@`）里写 `` `$t` ``（想生成 MySQL 的反引号包裹标识符）时，
    `` `$ `` 被解释成"转义后的字面量 `$`"，于是生成出来的 SQL 里是**字面量 `$t`**，
    执行时报 `Table 'db.$t' doesn't exist` —— 表名变量根本没被替换，而且报错位置在几百行之后，
    很难一眼看出是生成器的问题。**两个规避办法**：① 生成 SQL 时不要用反引号包表名
    （本批都是 `t_ctms_*` / `sys_*` 这类安全标识符）；② 需要拼接字符串字面量时用 MySQL 的
    `QUOTE()`，而不是在 PowerShell 里手工拼 `''`。
    同类坑：单引号里再嵌单引号（`''...''`）极易出错，能用 `PREPARE` + `QUOTE()` 就别手拼。
33. **⚠⚠ `powershell -File` 脚本里把含中文的 SQL 管道喂给 `mysql.exe`，中文会变成 `?`**
    （**2026-10-05 实锤修补**：这条此前写「已给 tools/ 下所有相关脚本都补上了这一行」，
    但 **`tools\run-db-sql.ps1` 实际漏了这一行** —— 于是"用该入口重跑任何含中文的 SQL 都会污染数据"，
    而它恰好是 §8.7 推荐的**统一执行入口**）。
    症状（两种，第二种更阴）：① `ERROR 1060 (42S21) Duplicate column name '??'` —— `` `表名` ``/`` `列名` `` 都变成 `??`、成了重名列；
    ② **SQL 全部执行成功、权限点全对、门禁全绿，只有中文字段被写成 `?`**。第二种实测后果：
    业务库 `rad_oa` 的 27 行 ctms 菜单在 `sys_menu.menu_name` 里全是**四个字面问号**
    （`HEX(menu_name) = 3F3F3F3F`），侧边栏/面包屑显示 `????`；因为菜单树、权限点集合、`@PreAuthorize` 全都没问题，
    **任何门禁都不会红**，只有人到浏览器里才会看见 —— 属"验证手段失效导致结论不可信"那一类。
    根因：**控制"字符串 → 原生命令 stdin"的编码的是 `$OutputEncoding`，
    不是 `[Console]::OutputEncoding`**（后者只管"读回原生命令的输出"的解码）。
    PowerShell 5.1 的 `$OutputEncoding` 默认是 **ASCII**，而 `pwsh`(7) 默认 UTF-8 ——
    所以**同一个命令在交互式 pwsh 里正常、被 `-File` 拉起就坏**。
    修法：脚本开头加一行（`run-db-sql.ps1` 已补，见该文件第 37~45 行的注释）
    ```powershell
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    ```
    **判定"有没有被写坏"的方法**：查 `HEX()` 而不是肉眼看中文 ——
    `SELECT menu_id, menu_name, HEX(menu_name) FROM sys_menu WHERE ...`；真中文是 `E5..`/`E6..` 这类 UTF-8 字节，`?` 是 `3F`。
    全表体检：`SELECT COUNT(*) FROM sys_menu WHERE menu_name LIKE '%?%';` 应为 0。
    修数据：**重跑真源 SQL**（如 `tools\run-db-sql.ps1 -Database rad_oa -Files "二开-合同台账-菜单.sql"`，
    该文件是"先按 menu_id 白名单 DELETE 再 INSERT"的幂等写法，且只动 `sys_menu`、不碰 `sys_role_menu`，
    故可在业务库安全执行；菜单主键不变 ⇒ 角色授权不受影响）。
    ⚠ 顺带一个**同源但不同域**的坑（2026-10-05 我自己踩的）：用编辑工具改 `.ps1` 会把**文件 BOM 弄丢**，
    于是 PowerShell 5.1 按 ANSI 解码中文注释 → `Unexpected token` 解析失败，报错位置还指向无关行。
    判定：`[System.IO.File]::ReadAllBytes($f)[0..2]` 必须是 `ef bb bf`；
    修复：`[System.IO.File]::WriteAllText($f, $text, (New-Object System.Text.UTF8Encoding($true)))`。
34. **MySQL 的 `WHILE ... DO ... END WHILE` 只能出现在存储程序里**，普通脚本（`mysql < file`）里写会
    直接报 `ERROR 1064 near 'WHILE ...'`。需要循环时把它包进临时存储过程（`DELIMITER //` …
    `// DELIMITER ;`，`DELIMITER` 是 mysql 客户端指令，喂文件时生效），用完
    `DROP PROCEDURE IF EXISTS`；或把固定清单**展开**成多条 `PREPARE/EXECUTE`。
    实测：`sql/回滚-合同台账-20261005.sql` 的三处循环就是因此改成存储过程的。
35. **统计"本变更集的表还剩几张"时，别用 `table_name LIKE 't_ctms\_%'`** ——
    那会把快照表 `t_ctms_*_bak_<日期>` 一起数进来（实测因此误判"回滚没生效"，白查一轮）。
    要用**精确表名清单**（`table_name IN (...)`）。同理，`*_bak_*` 快照只装"白名单那批行"
    （不是整表），核对时要比"白名单行的行数"而不是整表行数。
36. **同一件事只能有一个真源**：B3 的合同编号配置一度同时在"建表脚本"和"编号配置脚本"里各写了一份，
    实测结果是**同一套编号出现了两条配置**（`t_code_config` ×2），调用方按 id 取号时无法确定用哪条、
    回滚也只能删掉其中一条。已把建表脚本里的那段迁出，只留
    `sql/二开-合同台账-编号配置.sql`（confId = `9F2C0000000000000000000000C001`）。
    排查手法：`SELECT id,title FROM t_code_config WHERE id LIKE '9F2C%'` 看有几条。
37. **RuoYi 的权限集合是"登录时算出来并缓存进 Redis"的，改 `sys_role_menu` 不会立刻生效**（2026-10-05 踩过）。
    权限随 `LoginUser` 一起缓存（`UserDetailsServiceImpl.createLoginUser` →
    `PermissionService.getMenuPermission`），所以"给角色加一行菜单授权"之后，
    **已登录的 token 里还是旧权限**：该 403 的仍然 403，该放行的仍然 403 —— 看起来像"权限门禁坏了"。
    唯一不需要重新登录的生效途径是调一次 `GET /getInfo`：它会重算权限，发现不一致时
    `tokenService.refreshToken(loginUser)` 写回缓存（`SysLoginController:82-86`）。
    实测：给 `common` 角色挂上「往来单位」菜单后，不调 `/getInfo` 时
    `zhangwei` 调 `/ctms/partner/customer/list` 仍是 403；调一次 `getInfo` 后立刻 200。
    写"授权 → 断言"这类脚本时，**每条授权/撤权之后都要补一次 `/getInfo`**；
    或者干脆像 `tools/oa-login.ps1` 那样在授权**之后**重新登录。
38. **B3 业务表的主键由服务端生成，请求体里带 `id` 会被静默忽略**（2026-10-05 踩过）。
    `ruoyi-ctms` 的新增接口在服务层无条件 `setId(IdUtils.fastSimpleUUID())`，
    所以夹具**不能**"先想好一个 id 再 POST 然后用它查库"—— 那会得到
    "接口回 200、按 id 查却是 0 行"的假失败，而且会连带污染后续用例
    （实测第一版验收脚本因此挂了 19 条：客户、供应商、物料全线"查不到行"）。
    正确姿势：**POST 之后用业务唯一键（code / name）反查真正的 id**，再用它做后续的
    编辑/停用/删除/引用断言。清理夹具同理 —— 按 `code LIKE 'C%<序号>'` 清，别按自己编的 id 清。
    同源坑：`"$TypeName1-$S"` 里 `$TypeName1` 是**一个未定义变量**（不是 `$TypeName` 加 1），
    于是"查重"用的名字与已建行根本不是同一个，表现为"重复名居然能建"的假失败 ——
    变量后紧跟数字/字母/`?`/`:`/`%` 一律写 `${var}`（与第 22 条同源）。
39. **服务层自己"编"出来的值，必须对照 DDL 的实际列宽；内存桩单测发现不了列宽问题**（2026-10-05 踩过）。
    B3 物料域首版把留空的类型编码生成为 `"PT" + 32 位 UUID` = **34 字符**，
    而 `t_ctms_product_type.code` 是 `varchar(32)` → 建第 2 级类型直接 500：
    `Data truncation: Data too long for column 'code'`。
    为什么单测没拦住：内存桩 Mapper 只存进 `HashMap`，**不校验列宽**，
    所以 33 条单测全绿也照样漏；只有真库（或显式断言"长度 ≤ 列宽"）能发现。
    规则：① 任何"生成编码/拼接标识"的代码，长度上限写成常量并与 DDL 列宽对齐；
    ② 手工录入的同类值在**入口**就拒绝超长（给可读的业务提示），不要留给 MySQL 报 500；
    ③ 补一条**显式断言长度**的用例当回归守卫（本项目已落在
    `ProductMasterRulesTest.自动类型编码_不得超过列宽且前缀稳定`）。
40. **子表插入必须排在主表之后（非空外键），而内存桩单测发现不了**（2026-10-05 踩过，**登记接口直接 500**）。
    `t_ctms_contract_item.contract_id` 对 `t_ctms_contract` 有 **非空外键**
    （`fk_contract_item_contract`）。B3 合同登记的首版实现里 `insertContract` 先调 `applyItems`
    （它顺手做了行项落库）再插主表 → 真库立刻报：
    ```
    ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails
      (`rad_oa`.`t_ctms_contract_item`, CONSTRAINT `fk_contract_item_contract` ...)
    ```
    修法：把"算"与"落"拆开 —— `applyItems` 只做校验/序号重排/金额汇总，新增 `persistItems`
    排在主表 insert/update **之后**；并给写方法补 `@Transactional(rollbackFor = Exception.class)`，
    让"主表 + 行项 + 标签 + 变更历史"整体成功或整体回滚（否则会留下"有主表没行项"的半成品）。
    **为什么单测没拦住**：内存桩 Mapper 把对象存进 `HashMap`，**不校验外键与列宽**，
    139 条单测全绿也照样漏。规则：凡是"主子表写入"的实现，真库冒烟是**必需**的，不是可选项。
    判定"是不是这类问题"的快捷方式：接口 500 且栈里出现 `SQLIntegrityConstraintViolationException`
    或 `ConstraintViolationException` + 子表名，就先查**写入顺序**而不是查参数。
41. **PowerShell 里 `"/x?k=${([uri]::EscapeDataString('中文'))}"` 会因为嵌套单引号提前结束字符串**（2026-10-05 踩过）。
    `${(...)}` 的子表达式里用单引号包字符串，而这整段又在一个双引号串里 ——
    PowerShell 会把内层单引号当作**结束外层字符串**的引号，于是 URL 被**静默截断**：
    ```
    "/ctms/contract/list?...&status=${([uri]::EscapeDataString('付款中'))}"
      ⇒ 实际发出的是 "...&status="        ← 参数值没了，而且不报任何错
    ```
    后果特别坏：条件不生效时，"排掉了不该出现的行"这类断言可能**假通过或假失败**，
    看起来像"后端筛选坏了"（我为这个假象白查了半小时，真因在前端脚本）。
    规则：**要拼进 URL 的中文先算好存变量**，再在字符串里引用变量：
    ```powershell
    $encPaying = [uri]::EscapeDataString('付款中')
    $url = "/ctms/contract/list?pageNum=1&pageSize=200&status=$encPaying"
    ```
    同源坑还有第 22 条（`$var?x` 被当成一个变量名）与第 38 条（`"$TypeName1-$S"` 里的 `$TypeName1`）。
42. **同一套"建夹具→断言→清夹具"的脚本不可并发执行**（2026-10-05 踩过）。
    验收脚本的清理是按"本轮序号/固定前缀"删的，两轮同时跑时：
    后启动的那轮**预清理会把前一轮的夹具删掉一半**，前一轮随后的断言就会看到
    "不存在的合同 / 列表多出或少了行 / 筛选像没生效"这类**互相矛盾**的现象。
    实测表现：同一份脚本单跑 110/110 全绿，并发时出现"`status=付款中` 的查询返回了两条 `已签订`"
    （其实是那一轮的一半夹具被另一轮删了，同时另一轮把其中一条改成了 `付款中`）。
    规则：**要么在脚本里加运行锁**（本项目 `tools/ctms-contract-check.ps1` 用
    `.cache/<脚本名>.lock`，存在即拒绝启动、`-Force` 可跳过、`finally` 里删除），
    **要么在一个会话里严格串行地跑**。另外清理判据不要只用"本轮序号"，
    固定前缀（如 `验收标签%`）要一并清，否则异常中断留下的孤儿行会累积（实测攒下 16 个）。
43. **MyBatis 动态 SQL 的两个 OGNL/绑定陷阱**（2026-10-05 踩过，都在真库/真绑定才暴露）。
    ① **单引号是 Character，不是 String**：
    ```xml
    <!-- ✗ 恒为 true：OGNL 把 '1' 当 Character，与 String "1" 比较永远不相等 -->
    <if test="includeDeleted == null or includeDeleted != '1'">
    <!-- ✓ 双引号才是字符串（XML 属性用单引号包起来即可） -->
    <if test='includeDeleted == null or includeDeleted != "1"'>
    ```
    症状极隐蔽：「包含停用」的列表查询**永远拿不到已停用行**，而"排除停用"看起来一切正常。
    ② **`#{list.size}` 会在绑定期抛异常**：`size` 不是 bean 属性（MyBatis 的 `CollectionWrapper`
    只支持下标），要用 `<bind>` 先取值：
    ```xml
    <if test="tagIds != null and tagIds.size() > 0">
      <bind name="tagCount" value="tagIds.size()"/>
      ... having count(distinct ct.tag_id) = #{tagCount}
    ```
    规则：动态 SQL 里凡是要参与**比较**的字符串字面量统一用双引号；
    凡是要把**集合长度**当参数用的，先 `<bind>` 成标量。

44. **PowerShell：变量名后面紧跟的都是"变量名的一部分"**（2026-10-05 踩过，排查了很久）。
    ```powershell
    $S = '132312305'
    IdOf "CTMSAL跨年$S"      # ✗ 实际查的是 'CTMSAL跨年'：$S" 里的引号被吃进变量名 → 变量为空
    IdOf "CTMSAL跨年${S}"    # ✓
    IdOf "CTMSAL跨年$($S)"   # ✓
    ```
    症状极具误导性：脚本按**空主键**拼出了 `DELETE /ctms/contract/?reason=…`，
    后端返回 `Request method 'DELETE' not supported`（Spring 看到的是 `DELETE /ctms/contract`，
    而那个路径只注册了 POST），于是现象看起来像"DELETE 接口没实现/环境坏了"。
    **规则**：变量后面只要不是空格/标点（`.`,`:`,`;`,`)`,`]`,`}`,`-`,`_` 例外），就写成 `${X}`；
    中文、双引号、另一个 `$` 都属于"会被吃进去"的情况。同一份脚本里 `NoOf` 用同一种写法能成功、
    `IdOf` 却失败，只是因为**变量名恰好不同**（`$SX` 存在与否），所以别用"别处能跑"来推断这里没问题。

45. **夹具清理的 SQL 顺序：被 FK 引用者必须最后删**（2026-10-05 踩过两次，都在同一个函数里）。
    真库 FK 链：`t_ctms_contract_item.product_id → t_ctms_product`（`fk_contract_item_product`）、
    `t_ctms_contract.customer_id → t_ctms_customer`（`fk_contract_customer`）、
    `t_ctms_contract.parent_id → t_ctms_contract`（`fk_contract_parent`，ON DELETE SET NULL）。
    所以 `Clear-Fixtures` 的顺序**只能**是：
    `关联表 → 变更历史 → 行项 → 合同 → 产品/单位/类型 → 档案`。
    踩坑现象很绕：上一轮脚本异常退出留下带行项的夹具 → 本轮清理在"删产品"处撞 1451 →
    `$ErrorActionPreference='Stop'` 让**整个脚本立刻中止**（连 try/finally 都没进）→
    LockFile 留在盘上 → 下一次运行被自己的锁拦下，报"检测到运行锁（pid=…）"，
    **看起来像并发问题，实际根本没有并发**。
    配套两条纪律：① 清理函数里**不要**只按"本轮序号"清理（异常退出的残留带的是上一轮的序号），
    要同时按固定名字前缀/`customer_id` 反查；② 清理 SQL 的 stderr 要**显式判错并打印**
    （含 FK 名），而不是让它冒成异常。

46. **改了 `@PreAuthorize` 但没重新打 jar ⇒ 运行中的后端仍是旧判定**（2026-10-05 踩过，排查最久的一次）。
    现象：新增权限点后，`/getInfo` 返回的 `permissions` 里**明明有** `ctms:attachment:list`，
    但用**同一个 token** 调 `/ctms/attachment/list` 仍然 403（`Access Denied`）——
    看起来像"权限点没生效 / 权限缓存坏了 / `@PreAuthorize` 语法错"。
    真因：源码里的注解已改成 `ctms:attachment:list`，但**运行中的后端 jar 是改之前打的**，
    里面还是旧的 `ctms:contract:query`。
    **最快的判定方式：直接检查编译产物里的字符串**（不要靠"我记得我改过"）：
    ```powershell
    $c='ruoyi-vue-oa-master\ruoyi-ctms\target\classes\com\ruoyi\ctms\controller\CtmsAttachmentController.class'
    $b=[System.IO.File]::ReadAllBytes($c); $hex=-join ($b|ForEach-Object{$_.ToString('x2')})
    $n='ctms:attachment:list'; $a=(-join ([System.Text.Encoding]::ASCII.GetBytes($n)|ForEach-Object{$_.ToString('x2')}))
    $hex.IndexOf($a)   # -1 ⇒ 这条注解**没编译进产物**，先重新打包再谈别的
    ```
    规则：**改注解/权限点/常量之后必须** `mvn -pl <模块> clean install` →
    `mvn -pl ruoyi-admin clean package` → 停旧进程 → 起新进程（与第 13 条同源，
    但第 13 条讲的是"打进 jar 了没有"，本条讲的是"注解改了但产物没重打"）。

47. **只需要"权限集合变化"时，`GET /getInfo` 不保证写回 Redis**（2026-10-05 踩过）。
    `SysLoginController.getInfo()` 只在 `!loginUser.getPermissions().equals(权限集合)` 时才
    `tokenService.refreshToken(loginUser)` 写回。实测出现过
    "`sys_role_menu` 已加、`/getInfo` 返回的 permissions 也已经是新的，
    但同一个 token 调目标接口仍然 403"（时好时坏、难以复现到具体某一步）。
    规则：**验收脚本不要建立在"调一次 /getInfo 就够"的机制上**；
    凡是切换权限态（加/减 `sys_role_menu`）都做一次**完整重新登录**——
    本项目现成的做法是复用 `tools\oa-login.ps1 -Users <user>`
    （它内部会临时关掉验证码再恢复，`tools\ctms-attachment-check.ps1` 的 `Relogin` 就是这么做的）。

48. **验收脚本临时改共享状态时，必须"入口自愈 + 收尾还原 + 哨兵"三件套**（2026-10-05 踩过）。
    本项目的验收脚本无法新建角色（要造角色/授权/清理，成本高），只能**临时借用**
    `common` 角色改 `sys_role_menu` 与 `sys_role.data_scope`。这里有两个坑：
    ① **还原不彻底会污染下一轮**：`Restore-Role` 若只"把进入时的快照插回去"，
    那么上一轮异常退出时留在库里的**本脚本自己管理的**菜单行会被当成"原始状态"带进下一轮，
    症状是"本该 403 的用例拿到 200"（本轮实际踩到）。
    做法：先把**本脚本管理的 menu_id 集合**整体删掉，再插回快照里不属于该集合的部分。
    ② **改 `data_scope` 后异常退出 = 灾难**：`data_scope='5'`（仅本人）留在库里时，
    受测账号连 `/getInfo` 都 403（角色没有任何菜单），现象是"全面 403"，极难定位。
    做法：改之前在 `sys_role.remark` 写一个**哨兵串**（如 `__B3_ATT_SCOPE_TMP__`），
    脚本开头判断"哨兵在 + 范围是 '5' ⇒ 上一轮没还原 ⇒ 强制还原"，实现**不新增表**的自愈。
    另外：计数型断言（"不得落库"）**先取基线再比增量**，
    别写死 `= 0`——同一夹具上前面步骤可能已经合法写入过同名行（本轮也踩到）。

49. **`sys_role.data_scope` 的角色页标签与 B3 的服务端口径有偏差，给账号配范围时别只看标签**（2026-10-05 由 9.2 矩阵实测暴露）。
    RuoYi 角色页对 `data_scope` 的标签是：`1`=全部、`2`=自定义、`3`=本部门、`4`=本部门及以下、`5`=仅本人。
    而 B3 的 `ContractDataScope.matches` 实际把 `'4'` 判成「**本人创建 或 本人所属部门及以下**」、`'5'` 判成「**仅本人创建**」。
    也就是说：**运营照标签选 `'4'` 会偏宽**（比"本部门及以下"多出"本人创建但不在本部门"的行）。
    另：`DEPT` 与 `DEPT_AND_CHILD` 在实现里都收敛到 `'3'`（含下级是默认行为），
    `design.md:78` 说的"可配置关闭"**未落地**（代码里没有那个开关），
    所以两者在矩阵里要靠**不同夹具**取得判别力。
    证据与逐格矩阵见 `openspec/changes/oa-contract-ledger/notes/permission-audit.md` 的「# 9. 任务 9.2」段（小节 9-1~9-8）；门禁脚本见 §7 第 12 行。
    > 这一条是**只记录不改实现**：把标签与业务口径对齐属于新增变更，本批次（B3）未动。写验收脚本或给账号配范围时按**值语义**而不是按标签来选。

50. **PowerShell 的 `Select-String` 默认「不区分大小写」，把日期格式门禁变成了假失败**（2026-10-05 踩过，B3 §8.2）。
    背景：参考仓库是 Element **Plus** 写法，`value-format` 写成**全大写**日期记号的有 15 处，
    直接抄到 Element UI 2.x 会**静默出错**（日期不显示或不生效），所以本项目对它设了 grep 门禁。
    门禁命令（`tasks.md` 任务 8.2 写的）是：
    ```powershell
    grep -rn "YYYY-MM-DD" src/views/ctms src/components     # 必须零命中
    ```
    ⚠ 在 Windows 上如果用 PowerShell 的等价写法 `Select-String -Pattern 'YYYY-MM-DD'`，
    **它会连小写的 `yyyy-MM-dd` 一起匹配**（默认大小写不敏感）→ 返回 15~23 处
    **全部正确**的小写 `value-format` 命中（还有基线里的第三方打包件 `VFormDesigner.umd.min.js`），
    于是「门禁」看起来永远红。实测：同一批文件，区分大小写 **0 命中**，不区分 **23 命中**。
    **正确做法**（二选一，都要区分大小写）：
    ```powershell
    # ① 推荐：git grep 默认区分大小写，且 --untracked 能覆盖尚未 git add 的新文件
    cd F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
    git grep -n --untracked "YYYY-MM-DD" -- src/views/ctms src/components   # exit 1 = 零命中
    # ② PowerShell 原生写法必须显式加 -CaseSensitive
    Select-String -Path src\views\ctms\**\*.vue -Pattern 'YYYY-MM-DD' -CaseSensitive
    ```
    同类陷阱：`git grep` **默认只搜已跟踪文件**，新写的 `.vue`/`.js` 在 `git add` 之前必须加 `--untracked`，
    否则会得到"零命中"的**假通过**（本条两个方向都踩得到）。
    判定"这条门禁到底红不红"时，先确认命令的大小写语义与是否覆盖未跟踪文件，再看命中数。

51. **`var(--oa-xxx)` 用了不存在的令牌名 ⇒ CSS 静默走硬编码回退值**（2026-10-05 由 B3 §8.4 顺手发现，随后全仓扫出 3 处）。
    写法形如 `color: var(--oa-color-text-secondary, #909399)`：令牌名**不存在**时浏览器不报错，
    直接取括号里的硬编码值 —— 页面看不出异常，但那处颜色**永远不跟随主题令牌**，
    违反「页面里不要写裸色值、只写令牌」（§6.9/§6.11）。症状要到**换主题/换色**时才暴露：
    表现为"别的都变了、这几处没变"的串色，且极难定位（因为源码看起来"用了令牌"）。
    本批实测的 3 处：`ctms/partner/index.vue`（`text-secondary`，已由 8.4 换成 `ink-subtle`）、
    `workflow/print-template/index.vue`（`--oa-color-border` ×4、`--oa-color-fill-light` ×1，
    已换成 `hairline` / `surface-2`）。
    **已加静态门禁**：`tools/audit/audit-theme-tokens.js`（`run-all.js` 的第 10 个审计，gate=zero）。
    判据 = 使用集合 − `oa-tokens.scss` 声明集合，必须为 0（声明了但没用属预留，只打印不门禁）。
    它**自带负面对照**：注入一个不存在的令牌名 → 审计 exit 1、`run-all` 报「失败 1 个」；还原后 exit 0。
    ⚠ 该审计**先剥注释再找使用点**：否则"在样式注释里解释某个令牌为什么被换掉"这句说明文字
    会被当成真实使用点，门禁就被文档自己打红（与第 50 条的日期门禁同源坑）。
    新增页面/改样式后照 `npm run dev` 之外，**先跑一次 `node tools\audit\run-all.js`** 就能拦住这类缺陷。

    **同源的"计数型假值"三条**（2026-10-05 写 9.4 边界审计时集中踩到，都是"看起来有数、其实是假的"）：
    | 写法 | 症状 | 正确写法 |
    | --- | --- | --- |
    | `Select-String -Path <dir>\**\*` | 把**目录**也当文件喂进去 → 报若干条"访问被拒绝"，返回 `Count=0 / Errors=9` 是**假绿**（扫不到≠没命中） | `Get-ChildItem -Recurse -File` 过滤后逐个匹配，或 `git grep -n --untracked` |
    | `Get-Content -Raw \| Select-String '<tag>' \| Measure-Object` | 对**整块字符串**只报 **1**（实际 7 处） | 加 `-AllMatches`，取 `.Matches.Count` |
    | `@($x \| Select-String … \| Measure-Object).Count` | **零命中返回 1**：`@()` 包的是 `Measure-Object` 的**统计对象**（它不是 null），于是 1 个元素 → 计数 1 | 把 `@()` 包在**匹配结果**上：`@($x \| Select-String …).Count`（零命中 = 0、命中 = N）；或 `($x \| Select-String … \| Measure-Object).Count`（去掉 `@()`） |
    规则：任何"计数/零命中"判据，先自证一次——**构造一个已知答案的输入**（1 个命中 / 0 个命中各跑一次），
    确认命令给出的数与你人工数得的一致，再拿它去测真实目标。

    **续条（2026-10-05，由 B3 §8.6 的前端笔记自检抓到）：集合类门禁会被"说明文字"打红。**
    写 `notes/frontend-notes.md` 时，作者在「页面 ↔ 权限点」表下面写了句说明 ——
    "菜单里**没有** `ctms:tag:query` 这个权限点"，本意是解释一处**已知非差异项**；
    但审计是按**字面量**抽 `ctms:[a-z-]+:[a-z-]+` 做集合比对的，于是文档的权限点集合凭空多出 1 个
    **库里不存在**的权限点 → 自检报"文档 27 ≠ 真库 26"。这与"日期门禁被 README 的说明文字打红"同源，
    但**换了一类门禁**（从"命中计数"变成"集合相等"），所以不要以为"只有 grep 门禁会中招"。
    两种正确做法：① 门禁侧**先剥注释/说明文字**再做集合比对（`audit-theme-tokens.js` 就是这么做的，见 §6.51）；
    ② 文档侧**不写出会命中的字面量**（改写成"标签详情复用列表读权限点，菜单里没有独立的 query 权限点"）。
    判定准则：**凡是按字面量做"集合相等 / 零命中 / 计数"的门禁，都要问一句"说明文字会不会也命中？"**。

    **续条 2（2026-10-05，由 B3 第 7 组独立复核发现）：PowerShell 里"量行数/量文本长度"也必须带 `-Encoding UTF8`。**
    复核者指出交付记录写 `notes/migration-notes.md` 是 **282 行**，实测却是 **370 行**；captain 复核确认：
    `Get-Content <f>`（PS 5.1 默认编码）→ **282**，而 `Get-Content <f> -Encoding UTF8` 与
    `[IO.File]::ReadAllLines(<f>, UTF8)` → **370**（`LF` 计数 370，三者里两个一致）。
    根因同 §6.33 一族：默认读法按 ANSI 解码 UTF-8 内容，多字节字符被吞并/重解释，
    **行分隔判定跟着错**，于是"行数"是个看似精确、实则无意义的数字（字节数 31118 是对的）。
    规则：**凡是要写进交付记录的数字（行数、字段数、条目数），一律用带编码的读法量，并且用两种独立写法交叉验证**；
    只写"字节数"或以命令输出为准时，把命令本身也写进记录（否则后人无法判断这个数是怎么来的）。
    另：把带双引号的命令**再套一层** `powershell -Command "…"` 时，内层双引号会被吃掉、`|` 会被当成外层管道
    （实测误报 FAIL + `CommandNotFoundException`）——写成脚本文件用 `-File` 跑，或把模式改用单引号。

52. **"绿"和"对"是两件事：错误的清理判据会让「清理」与「验证清理」同时失效、双双报绿**（2026-10-05，B3 §10.1 端到端联调踩到）。
    现象：收尾脚本的"逐表残留断言"**全绿**，但真库基线却漂了（`t_ctms_customer` 0 → 3、`t_ctms_supplier` 2 → 5）。
    根因：`t_ctms_customer` / `t_ctms_supplier` 的**主键是服务端 UUID、业务身份在 `code` 列**
    （DEV-ENV §6.38 的"主键由服务端生成"同源），脚本却按 `id LIKE 'E2EC%'` 判定 ⇒
    **DELETE 是空操作**，而**残留断言用了同一个错误判据，于是也返回"干净"**。
    这就是"自洽的假绿"：**判据自己错了，它验自己当然永远通过**。
    抓住它的是**与判据无关的外部基准** —— 开工前记录的"零夹具基线"，收尾再对一次。
    规则（写夹具脚本时照做）：
    1. **清理判据必须来自真源**：先确认这张表的**身份列**是谁（主键？业务唯一键？还是两者都要），
       别按"我以为的主键"清理；服务端生成 UUID 的表一律用 `code` / 名称等业务唯一键。
    2. **清理与残留断言必须用同一个真源列** —— 否则两者会一起错、一起绿。
    3. **再加一层与判据无关的兜底网**：开工记录全表基线（行数），收尾逐表比对；
       数字变了就红，不管你的判据自认为多干净。**这一层是唯一能发现"判据本身错了"的手段。**
    4. 同类工具假象：`curl.exe` **不会**对 URL 里的中文做百分号编码（`Invoke-RestMethod` 会自动编码），
       所以"列表筛中文能过、导出却 400/返回 HTML"通常是**工具假象**而非后端缺陷 ——
       与 §6.22 / §6.41 的 PowerShell/URL 编码坑同族，遇到就先手工编码再断言（§6.41 的"中文先算好存变量"同样适用）。

53. **浏览器自动化"能点中什么"是确定的：`<el-button>` / 原生控件 / 带 ARIA role 的元素能点，`<div @click>`、`el-tree` 节点、`el-select` 的 `<li>` 选项点不中**（2026-10-05，流程设计器"参与人选人"改造实测）。
    现象（同一次会话里逐条实测）：
    - **能点**：`el-button`（含 `type="text"` 的小按钮、`<el-tag>` 之外的按钮）、`<input>`、`<el-radio>`、带 `role=menuitem` 的侧边菜单项；
    - **点不中**：① `el-tree` 的节点 —— 它是 `.el-tree-node`（`role="treeitem"`、`tabindex="-1"`），
      `initTabIndex()` 会给第一个节点 `tabindex=0`，但本桥的 `browser_click` **只认快照索引**（该元素不进快照），
      而 `browser_press Tab` **不会让页面元素获得焦点**（实测 `browser_get_text` 查 `:focus` 恒不匹配）⇒ 键盘也走不通；
      ② `el-select` 的选项 —— `.el-select-dropdown__item` 是**无 role 的 `<li>`**，下拉能展开、选项文字可见，但点不到；
      ③ 自绘 `<div @click>` 触发区（同族，见流程设计器旧版「选人」入口）。
    规则：
    1. 凡是要"可自动化验证"的交互，**触发区必须是 `<el-button>` 或原生控件**，不要用 `<div @click>`；
    2. 动手前先**把改造拆成步骤并逐条分类**（哪些能自动点、哪些只能人工），据此**先与用户约定人工验证那一步**
       （HANDOFF §3.3 的选人改造就是这么交付的），不要盲试；
    3. 需要证明"写路径"时，可用**受控组件里可点的按钮**绕开点不中的元素：
       例如共享选人弹窗的「清空 → 确定」，若节点上的人被清掉，即证明"弹窗里确认的列表就是写回父组件的值"
       （父组件状态是唯一真源，不需要真的在组织树里点一个人）。
    4. **弹窗里"取消"和"确定"在快照里长得很像**：`UserAllSelect` 的页脚是「确 定」`[N]` 与「取 消」`[N+1]`，
       页面顶部还有一个 `button "Close"`（`el-dialog` 的关闭叉），而且**索引会随快照重排**。
       实测有一次按"上一次的 N+1"点到了「确 定」⇒ 弹窗把**空列表**写回父组件、把节点上的人清空了。
       稳妥做法：**取消 / 关闭一律优先点 `button "Close"`**（不触发 `confimUser`）。
    5. `el-checkbox` 与它所在的 `.user-card` **同样不进快照**（用户列表里的勾选框点不中），
       因此"在共享弹窗里勾第 2 个人"这类断言**只能人工做** —— 见
       `doc\缺陷-单人节点指定人员可多选.md` §5 的人工确认步骤。
    6. 超管账号打开该弹窗时**用户列表是空的**：`user.dept.parentId === "0"` ⇒ 共享组件算出的初始
       `deptId="0"` ⇒ 按根部门查不到人；**必须先点组织树里的一个部门**才列人，
       而组织树恰恰是点不中的（第 1 条）⇒ 这条路径天然需要人工。

54. **`edit` 工具偶发 `ReplaceFileW EIO (Win32 32)`：同一文件连续多次编辑时，大块改写会被拒**（2026-10-05 实测）。
    现象：对 `FlowDesigner.vue`（约 80KB）连续做多处修改时，**较长的整块替换**报
    `ReplaceFileW EIO (Win32 32)`，而**拆成"先改一行 + 再插入整块"就成功**；
    文件本身没有被破坏（先读回确认内容不变）。触发环境：前端 `npm run dev`（webpack 监听）开着。
    规则：
    1. 报 EIO **不要**判断成"文件系统坏了"，也不要立刻改用整文件 `write` 覆盖（会丢掉未复核的改动）；
    2. 先 `read` 回确认目标文件仍然完好 → **把这次编辑拆成更小的两次**（先改标识性的一行，再插入整块）→ 重试；
    3. 同一文件要连续改多处时，**按"小块多次"推进**比"一次大块"稳（本轮 7 处改动就是这样完成的）；
    4. 用 `pwsh` 兜底改写时**必须显式保住编码与行尾**：本仓库 `.md/.js` 是 **UTF-8 无 BOM + LF**、
       `.vue` 是 **UTF-8 无 BOM + CRLF**、`.ps1` 是 **UTF-8 有 BOM**（见 §6.33）；
       注意 **`Set-Content -Encoding utf8NoBOM` 在 PowerShell 5.1 里不存在**（5.1 会报"无法绑定参数 Encoding"），
       5.1 下用 `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`，
       并把行尾显式拼成 `"`n"`（LF）或 `"`r`n"`（CRLF），写完**回读首字节与 `CRLF/LF` 计数自证**。

55. **前端 `npm run dev` 的 watcher 会"假装在跑"：改了源码但页面还是旧行为**（2026-10-05 实测，代价：用户看到两个已修好的 bug 仍在）。
    症状：同一份源码，`npm run build:prod` 编译进去的是**新**代码，而开发服务器送出的 chunk 是**旧**代码；
    页面表现成"新功能点不开 + 旧 bug 原样出现"（实例：新建流程弹窗的「高级选项」点不开、
    创建时报 `流程基本信息有误：流程 key 不能为空` —— 而新代码里这两种情况都已消除）。
    成因（本机）：**前端 watcher 在多次"大文件连续编辑"后停止拾取变更**，
    与同批出现的 `edit` 工具 `ReplaceFileW EIO`（见第 54 条）很可能是同一批文件句柄/锁问题的两面。
    **判定法（30 秒，不要靠"我觉得应该生效了"）**：
    ```powershell
    # 1) 生产构建里有没有新代码（权威：webpack 重新读盘）
    cd ruoyi-vue-oa-ui-master ; npm run build:prod
    node -e "const fs=require('fs'),d='dist/static/js';const hit=fs.readdirSync(d).filter(f=>f.endsWith('.js')).find(f=>fs.readFileSync(d+'/'+f,'utf8').includes('高级选项（流程标识 / 分类）'));console.log('dist 命中: '+(hit||'(无)'))"
    # 2) 开发服务器送出的 bundle 里有没有（注意它是 chunk，不是 app.js）
    #    直接在页面里看行为；或比对"改一行文案 → 刷新是否变化"
    ```
    **处置**：**重启前端**（`npm run dev`），然后**硬刷新**页面（Ctrl+F5）。
    重启前 `npm run build:prod` 通过 ⇒ 代码本身没问题，别再改代码去"修"一个已经修好的 bug。
    规则：**交付前端改动后，必须"改一行可见文案 → 硬刷新看到变化"来确认 watcher 活着**；
    只看到"编译成功"不算 —— 编译成功说的是**上一次**的源码。

56. **RuoYi 的「认证失败」是 HTTP **200** + body `{"code":401}` —— 脚本"只看字段不判 code"就会把「被拒」当成「成功」**
    （2026-10-05 实测；代价：把"审批人被 401 拒掉"误诊成"多实例不合流、流程卡死"，连续误诊两轮）。
    同秒可复现的证据（`t15` 留档）：
    ```powershell
    # 用"已过期/已登出"的 token 调任意需认证接口
    Invoke-WebRequest 'http://localhost:8080/biz/flow/submit' -Method Post `
      -Headers @{ Authorization = "Bearer $staleToken" } -ContentType 'application/json; charset=utf-8' -Body $bytes
    # → HTTP status = 200
    # → body = {"msg":"请求访问：/biz/flow/submit，认证失败，无法访问系统资源","code":401}
    ```
    成因：`ruoyi-framework/.../security/handle/AuthenticationEntryPointImpl.java:26-33` 只做
    `ServletUtils.renderString(...)`，**不设置 HTTP 状态码**；而 `Invoke-RestMethod` 只对 4xx/5xx 抛异常
    ⇒ 它对这种 401 **一点反应都没有**，`try/catch` 也不会进。
    危害（每一个用 `Invoke-RestMethod` 写验收脚本的人都会中）：
    1. `$null = Post ...` 这类"不判 code"的写法把**审批被拒**记成**审批成功** → 最后只看到
       "还剩活动任务 / 实例不办结 / 流程没往前走"，根因却在完全另一处（`flow-regression.ps1` 的老写法就是这么坑的）；
    2. 请求**根本没进业务代码**（被安全过滤器拦掉）⇒ 业务日志里什么都没有，排查时"日志空白"就是这个原因。
    对策（写脚本照抄 —— 跑前校验 + 每次调用判 code）：
    ```powershell
    # 1) 跑前：逐账号验一次凭据，失效就中止
    $r = Invoke-RestMethod "$BaseUrl/system/user/profile" -Headers @{ Authorization = "Bearer $t" } -TimeoutSec 15
    if ($r.code -ne 200) { throw "凭据失效：$user code=$($r.code)" }
    # 2) 每次提交：code 必须 200，否则把**原始 body** 带进异常
    $r = Invoke-RestMethod $url -Method Post -Headers @{ Authorization = "Bearer $t" } -Body $bytes -ContentType 'application/json; charset=utf-8'
    if ($r.code -ne 200) { throw "被拒：code=$($r.code) msg=$($r.msg) body=$(($r | ConvertTo-Json -Depth 10 -Compress))" }
    ```
    3. **凭据会静默过期**：`token.expireTime: 120`（分钟，`ruoyi-admin/src/main/resources/env/dev/application.yml`），
       而且只有"被用到"的 token 才会被 RuoYi 滑动续期 —— 长期不用的演示账号
       （`zhangwei`/`lina`/`wangqiang`/`zhaomin`）必然先过期，而 `superAdmin` 因为被反复使用一直活着。
       跑任何多账号接口脚本前先跑 `tools\oa-login.ps1`。
       参考实现：`tools\flow-regression.ps1` 的 `Assert-Tokens`（跑前校验）与 `Post-Ok`（判 code + 打印原始 body）。
    4. **反向别踩**：不要顺手把 `AuthenticationEntryPointImpl` 改成回真正的 HTTP 401 —— 前端 `request.js`
       的登录过期分支与所有既有脚本都建立在这个约定上，属共享面改动（要动先问用户）。

57. **"单模块打 fat jar"会让子模块的测试阶段烂掉很久都没人发现**（2026-10-05 实测，`ruoyi-template` 中招）。
    现象：`mvn -B -pl ruoyi-template test` 在 **test-compile** 阶段就失败
    （`StubTemplateMapper 未实现 countByFormKey(String)` —— B1 §7.10 给 `TemplateMapper` 加了方法，
    `src/test` 里的内存桩没跟着补）。
    为什么长期绿：日常打包用的是 `mvn -B -DskipTests -pl ruoyi-admin clean package`（**不带 `-am`**），
    子模块依赖一律取**本地仓库里已 install 的 jar**，从来不编译子模块的 `src/test`
    （`-DskipTests` 只跳过"跑"，真正跳过"编译"的是 `-Dmaven.test.skip=true`，两者别混）。
    规则：
    1. **改了哪个模块，就对那个模块跑一次 `mvn -B -pl <模块> test`**（不是只看 `-pl ruoyi-admin package` 成功）；
    2. 往子模块 `src/test` 加用例前先确认该模块 pom 里有测试框架（`ruoyi-template` 原先没有 junit，
       加一块 `junit` test 依赖即可，版本由 spring-boot-dependencies 管）；
    3. 反过来：如果你**只**改了某个子模块的 `src/test`，别忘了它还可能在别的模块被当依赖用到 ——
       `install` 一次再打 fat jar（§6.13）。
    同族提醒：`ruoyi-template` 里 `*Check.java` 是 **main() 形式的自检程序**（surefire 不跑它），
    要进 `mvn test` 必须命名 `*Test.java` 并用 JUnit 断言。

58. **MyBatis 的"歧义 getter"是惰性抛的，而且测试类路径与打包件的 mybatis 版本不一致时会整体漏判**（2026-10-05，t31 修 C-1，B4 写路径整体 500）。
    现象：8 类单据的列表/新增/审核等**含 `posted` 条件的语句在真机整体 HTTP 500**，而 `mvn -B -pl ruoyi-ctms test` **535 条全绿**：
    ```text
    org.mybatis.spring.MyBatisSystemException: nested exception is
    org.apache.ibatis.reflection.ReflectionException: Illegal overloaded getter method with ambiguous type
    for property 'posted' in class 'com.ruoyi.ctms.erp.base.domain.ErpDocHeader'.
      at org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker.invoke(AmbiguousMethodInvoker.java:34)
      at org.apache.ibatis.reflection.wrapper.BeanWrapper.getBeanProperty(BeanWrapper.java:164)
      at org.apache.ibatis.scripting.xmltags.DynamicContext$ContextAccessor.getProperty(DynamicContext.java:113)
    ```
    根因（两层，缺一层都复现不出来）：
    1. **JavaBeans 口径**：`ErpDocHeader` 同时有 `getPosted()`（`String`，落库列）与 `isPosted()`（`boolean`，便捷判定）
       ⇒ 同一属性 `posted` 的两个 getter 且类型互不兼容。**关键：建 `Reflector` 时不抛**，
       MyBatis 只把该属性包成 **`AmbiguousMethodInvoker`（惰性）**，**真读这个属性**才抛 ——
       触发点是 mapper XML 的 OGNL 动态 SQL（`resources/mapper/erp/ErpPurRequestMapper.xml:116` 的
       `<if test="posted != null and posted != ''">`），调用链 `Controller → Service → 动态 SQL`。
    2. **版本偏斜**：模块测试类路径解析到 mybatis **3.5.13**（**容忍**这对歧义，把 `posted` 解析成 `String`），
       而**打包运行件里是 3.5.7**（**不容忍**，直接包 `AmbiguousMethodInvoker`）——
       该版本取自 `ruoyi-admin.jar` 的 `BOOT-INF/lib/mybatis-3.5.7.jar`（全仓仅此一份）。
       ⚠ **`.cache\t1-cp.txt` 是 t1 当天生成、已过期，别再用它代表当前测试类路径**（它里面写的 3.5.13 会把你带到错误结论）；
       要判定实际版本，从 `ruoyi-admin.jar` 里抽 `BOOT-INF/lib/mybatis-*.jar` 看。
    判据（怎么自检）：
    1. 「守卫测试」**不能只 `new Reflector(clazz)` 断言不抛**（那是装饰性的，修不修都绿）——
       必须**逐个读取全部可读属性**（`reflector.getGetInvoker(prop)` 不能是 `AmbiguousMethodInvoker`，且真读一次），
       并加**与 mybatis 版本无关的静态口径**（同属性名出现两个不同类型的 `getX()`/`isX()` 即失败）；
       参考实现 `ruoyi-ctms/src/test/java/com/ruoyi/ctms/erp/base/ErpDomainReflectorTest.java`
       （扫 `target/classes/com/ruoyi/ctms/erp` 下全部 .class：160 类 / **1306 个属性**；`SCAN_FLOOR=40` 防"扫不到也绿"）。
    2. **判别力必须用反向对照证明**：修复前 `Tests run: 4, Failures: 3`（逐类列出 7 个 `posted` 歧义）→
       修复后 `4/4` 绿。只给"修复后绿"不算证据。
    3. 全仓同族扫描（`.cache\t31\SiblingScan.java`，25 模块 924 个类）：修复前 **7 个歧义属性（全是 `posted`）→ 修复后 0**。
    出处：`erp/base/domain/ErpDocHeader.java:174`（`isPostedFlag`，javadoc 写明"不能叫 isPosted"）、
    `resources/mapper/erp/ErpPurRequestMapper.xml:116`、`notes/01-base.md §8`、
    `.cache/t31/{prefix-test-red,prefix-trigger-3.5.7,postfix-trigger-3.5.7,postfix-full,sibling-scan-postfix}.log`。
    同族（"判据装饰性"/"绿≠对"）：**§6.52**（判据自己错了会双双报绿）与 **§6.56**（认证失败是 HTTP 200 + body code=401，
    只看"抛不抛异常"会把"被拒"当"成功"）——三者是同一个教训的不同侧面：**判据必须能因回归而变红**。

59. **编辑 `.ps1` 时不带 BOM 的写法会**静默剥掉**原文件的 BOM ⇒ PS 5.1 按 ANSI 读 ⇒ 中文乱码并破坏字符串引号**（2026-10-05，t1/t2 踩过，`Missing ] ...`）。
    现象（三种，按隐蔽程度递增）：
    1. 解析期直接炸：`Missing ] at line:NN char:NN`、`& 运算符保留给将来使用`、"字符串缺少终止符" —— 看上去像语法写错了，其实是编码读错；
    2. 中文变 `锟斤拷`/`????`，日志与断言描述读不出来；
    3. **最隐蔽**：脚本能跑，但断言里用中文关键字比对**永远不匹配**（假红/假绿）。
    根因：PS 5.1 对**无 BOM** 的 `.ps1` 按**系统 ANSI（本机 GBK）**解码，而文件其实是 UTF-8；
    而"带不带 BOM"取决于写文件的方式：`write` 工具/`Out-File`/`Set-Content` 默认可能写出无 BOM；
    `-Encoding UTF8` 在 **PS 5.1 是"UTF-8 with BOM"、在 PS 7+ 是"无 BOM"** —— 同一条命令在两个版本下结果相反（§6.33 同族）。
    判据（改完 `.ps1` 必做，两条）：
    1. 复读前 3 字节：`([System.IO.File]::ReadAllBytes($f)[0..2] -join ',') -eq '239,187,191'`；
       需要补 BOM 时用 `[System.IO.File]::WriteAllText($f, $txt, (New-Object System.Text.UTF8Encoding($true)))`；
    2. **解析检查（不执行）**：`[System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$null, [ref]$errs)`，
       `$errs.Count -eq 0` 才算过 —— 这一步能把第 1 种现象在运行前抓住。
    反面（别把规矩搞混）：`.md` **不带** BOM（本文件即无 BOM）、`.vue` 是 UTF-8 无 BOM + CRLF、`.ps1` 是 UTF-8 **有** BOM。
    出处：`§6.33`（同族编码坑）、`tools\erp-check.ps1` 头部 ⚠ 第 1 条、`tools\flow-form-consistency-check.ps1` 头部（同类提醒）、
    t1/t2 实测记录（`.cache\t2-masterdata-drill.ps1` 补 BOM 后解析通过）。

60. **编译红会污染其它组的断言：`erp` 包的"类枚举/源码级"守卫测试在主代码没产出 `.class` 时必然 `ClassNotFoundException`**（2026-10-05，t1/t2/t23/t29 反复踩）。
    现象：你只动了 A 组，B 组的测试却红，报错是
    `ClassNotFoundException: com.ruoyi.ctms.erp.<某类>`（或"找不到 B4 源码目录"），
    看起来像 B 组的代码坏了；实际是**别人的编译错误**让 `target/classes` 缺类/陈旧。
    根因：B4 有两条**跨全包扫描**的守卫测试，它们不依赖业务断言，只依赖"类能被加载"：
    1. `erp/base/ErpPrecisionTest`：扫 `src/main/java/com/ruoyi/ctms/erp` 源码树，把每个 `.java` 映射成类再 `Class.forName`（源码里出现浮点类型即失败）；
    2. `erp/base/ErpDomainReflectorTest`：扫 `target/classes/com/ruoyi/ctms/erp` 下的 `.class` 建 `Reflector` 并读属性。
    ⇒ 主代码**编译失败**时没有新 `.class`，这两条必然在"环境层"红，与它们要守的规则无关。
    判据（排查顺序，先编译后业务）：
    1. 见到一大片 `ClassNotFoundException` / `NoClassDefFoundError`（且伴随 `[ERROR] ... .java:[NN]`），
       **先跑 `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test-compile`**（约 10s）确认编译绿，再去读业务断言失败；
    2. 编译错误行号指到**别人的在途文件**时，按团队纪律"只报文不代改"（简报 §3.1），别顺手改别人文件；
    3. 干净基线：`test-compile` 为 0 时，全模块 `mvn -B -pl ruoyi-ctms test` 的计数才是可信的（见 §7 第 14 条）。
    出处：`.cache\t31\prefix-test-red.log`（修复前红）、`ErpPrecisionTest.java:39,48-64,159-176`（源码→类映射与 `Class.forName`）、
    `ErpDomainReflectorTest.java` 的 `scanErpClasses()`、t1/t2/t23/t29 的过程记录（多次"编译红 → 非本组文件"）。
## 7. 2.0 交付门禁（每次交付前必跑，失败即阻断）

> 来源：`doc/2.0/2.0-PRD-OA升级开发.md` 第 10 章（`REQ-NFR-010`/`REQ-NFR-011`）与第 11.6 节（`AC-83`）；
> 规格侧约束见 `openspec/changes/oa-v2-overview/specs/platform/delivery-gate/spec.md`。
> **任一命令非 0 退出即视为该次交付未完成**，不得以"环境没起"为由记为通过（环境问题先修环境再重跑）。

先起环境并重新登录（token 有有效期，见第 4.3 节）：

```powershell
cd F:\dsh\ruoyiOA
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1
```

然后**按此顺序**执行（先静态、后运行时；静态失败时不必起服务）：

| 顺序 | 命令 | 覆盖 | 说明 |
| --- | --- | --- | --- |
| 1 | `node tools\audit\run-all.js` | **10 个**前端静态审计 + 3 个后端/跨端审计（流程↔模板绑定、模板四页签、内置打印版式）+ `baseline.json` 门禁。第 10 个前端审计是 **`audit-theme-tokens.js`**（`var(--oa-*)` 引用的令牌必须都已声明；用了不存在的令牌名时 CSS 会**静默**落到硬编码回退值、不跟随主题，见 §6.11 与 §6.51） | 内置 `--selftest`（每个审计先自检，证明检查逻辑有效）；调试时可用 `--no-selftest` |
| 2 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1` | 越权防护（**45 条断言** = V1 AC-35 基线 30 + 2.0 B3 §9.3 新增 15；后者覆盖合同范围外详情 403、无权限列表 403、无查看权下载附件 403、有权限 200） | 需要已登录；脚本自带断言总数自检（`总数 = 基线 30 + 新增 15`）；会临时借用受测角色改授权与数据范围，收尾还原且入口有自愈哨兵（见 §6.48） |
| 3 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\sign-feature-check.ps1` | 签名组件（44 条断言） | 需要已登录；⚠ 签名防篡改触发器会让测试数据无法清理 |
| 4 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-regression.ps1` | 流程引擎无回归（20 条断言） | 需要已登录 |
| 5 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\print-builtin-check.ps1` | 多内置打印版式回归（98 条断言，对应 AC-61/AC-62/AC-63/AC-83） | 需要已登录；夹具全部克隆 + 收尾还原 |
| 6 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\serial-numbering-check.ps1` | 编号序列扩展回归（32 条断言，B3 §1.3）：4 条判定 + 既有编号基线逐项比对 | 需要已登录；夹具固定 ASCII id，收尾全清 |
| 7 | `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-workflow test` | 后端单测（19 条：内置打印版式常量与优先级链） | 见 §3.3：必须走直连 settings，否则 surefire provider 下不下来 |
| 8 | `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-serial test` | 后端单测（24 条：编号渲染/分桶/取号编排/预览不占号） | 同上；其中含 **Redis 计数器初值必须是 Integer** 的回归守卫（见 §6.27） |
| 9 | `cd ruoyi-vue-oa-ui-master ; npm.cmd run test:unit` | 前端用例（39 条） | 纯 Node，不依赖浏览器；零新增依赖 |
| 10 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b3-sql-drill.ps1` | **B3 SQL 四件套演练（68 条断言）**：建表幂等 ×2、体检逐列比对 + 数据零变化、快照一致、回滚中止守卫、完整回滚（含"装前快照 → 装 → 回滚回到装前"）、CONSTRAINTS_ONLY | 需要 MySQL 在线；**只在一次性库 `b3_rollback_drill`(+`-migrate`) 里跑并收尾 DROP DATABASE**，不动 rad_oa |
| 11 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1` | **B3 主数据档案接口验收（79 条断言）**：客户/供应商 CRUD 与唯一性、简称必填与账期非负、物料域类型树 5 级/叶子约束/单位小数位/引用保护、启停用与引用保护、不做数据范围隔离 | 需要已登录；夹具 ASCII 前缀 `C…/S…`，收尾全清；**幂等**（连跑两次残留 0 行） |
| 12 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1` | **B3 合同主体接口验收（261 条断言）**：登记/编辑、状态自由流转、多维筛选与标签交集、软删除 30 天边界、框架四条守卫、自动标签、变更历史、详情只读、数据范围 403、编号由服务端生成；**4.8b 数据范围矩阵**（AC-79：7 档 × 4 夹具 × 列表/详情/导出三面 + 范围外编辑/删除/恢复三个旁路，逐格与基线比对、整集逐 id 相等） | 需要已登录；夹具 ASCII 前缀 `CTM*`；**有运行锁**，不可并发（见 §6.42）；**幂等**（连跑两次残留 0）；清理顺序纪律见 §6.45；会临时借用 `common`/`bm` 角色改授权与数据范围，收尾还原且入口有自愈哨兵（见 §6.48）；断言数 **113 → 253**（9.2 矩阵段）**→ 261**（10.2 的 R1 修复：导出面改按 xlsx 判定 + 补"行数 == 列表 total"双证 + 反向越权断言，见 §6.50 与 notes/integration-check.md §7.4-R1）。另：B3 菜单在真库是 **27 行 = 1 个 M 类目录 + 4 个 C 类菜单 + 22 个 F 类按钮**（其中 26 行带 `ctms:*` 权限点），别把 27 与"权限点数 26"混为一谈 |
| 13 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-commercials-check.ps1` | **B3 商务要素/质保/编号接口验收（116 条断言）**：C-1 金额先舍入再汇总、行项校验与物料快照、标的物摘要落库、质保到期算法与金额↔比例互换、关闭质保清空 7 字段、质保提醒窗口边界与释放闭环、付款比例（不扣质保金/金额 0 空值）、编号格式与按「类型码+主体码+年份」分桶/跨年重置/预览不占号/停用占号不复用/类型主体校验 | 需要已登录；夹具 ASCII 前缀 `CTMSAL/CCOM`；**有运行锁**，不可并发；**幂等**（连跑两次残留 0 行）；编号断言全部用"同轮前后对照"，不假设 Redis 计数器起点（见文件头 ⚠） |
| 14 | `cd ruoyi-vue-oa-master ; mvn -B -pl ruoyi-ctms test` | 后端单测（**535 条**：B3 的 174 条 + B4 进销存全量 —— 状态机/精度唯一实现点/取号/过账与红冲/并发不丢更新/采购与销售下推/调拨盘点/库存账与一致性/附件对象对账/元数据守卫 `ErpDomainReflectorTest`；**只增不减**：174 → 531（t29）→ 535（t31）） | 需要直连 settings（仓库根 `.mvn/maven.config` 已配） |
| 15 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1` | **B3 附件接入接口验收（69 条断言）**：对象挂载层与对象存在性、未注册对象类型被拒、`(object_type,object_id)` 复合索引、**白名单比平台窄**（zip/mp4 判别用例）、20MB 双向边界、**HTTP 413** 与半成品清理、随机命名与中文名、按对象查看权（403）与数据范围 403、删除留痕（字段名`附件`）与删除后不可下载 | 需要已登录；夹具 ASCII 前缀 `ATTC*/ATCUS*`；**有运行锁**，不可并发；**幂等**（连跑两次残留 0 行）；会临时借用 `common` 角色授权与数据范围，收尾还原且入口有自愈哨兵（见 §6.47/§6.48） |
| 16 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-scope-check.ps1` | **B4 库存域数据范围四档矩阵（§8.2 / AC-79 库存域部分）**：单据（入库单列表/详情）＋结存（`/stk/stock`）＋流水（`/stk/ledger`）× 四档（`1` 全部 / `3` 本部门 / `4` 本部门及下级 / `5` 本人）逐格实测 **+ 多角色取并集 + 导出与列表同范围同筛选（xlsx 解包数行数）+ 范围外详情/结存/流水越权 403** | 需要已登录；夹具 ASCII 前缀 `T10S*`（单位/仓库/类型/物料/4 张入库单）；**四档必须彼此可判别**：观察者 zhangwei 临时调根部门（区分 `'4'` 与 `'3'`）、zhaomin 临时调入财务部（区分 `'3'` 与 `'5'`，同时验证"归属部门创建时快照"）；借 `common` 角色授权与 `data_scope`，**入口自愈 + 收尾还原 + `sys_role.remark` 哨兵**（§6.48），收尾断言 `data_scope` 不留库且夹具零残留；⚠ **脚本内没有锁实现**（2026-10-05 复核：`grep -i "lock|locked-run"` **0 命中**，而 `ctms-contract-check.ps1:320` / `ctms-attachment-check.ps1:223` / `ctms-commercials-check.ps1:184` / `ctms-e2e-check.ps1` / `ctms-migration-check.ps1` 都有 `.cache\<脚本>.lock` 锁文件）⇒ **必须经 `tools\locked-run.ps1 -LockName env` 串行执行**：它会借 `common` 角色改授权与 `data_scope`，并发跑会互相覆盖并留下残留 |
| 17 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1` | **B4 只读冒烟（27 条探针）**：8 类单据列表、`/stk/stock`（list/detail/recalc 默认不修复）、`/stk/ledger`（list/detail）、3 个主数据 `/options`、附件的 `object-types`（逐项核对 `registered` = 9）、8 类单据的动作路由形态；输出「端点 × HTTP code × body code × 关键字段」清单，404/405/参数名/权限点不一致在这里低成本暴露 | 需要已登录（`tools\oa-login.ps1`）；**只读、不建任何业务数据**（不污染 E2E 基线）；默认有 FAIL 也退出码 0，`-Strict` 时有 FAIL 退出码 1；**不做断言调优**（未打包/404 照样逐条列出）；判 HTTP 与 body.code 双证（§6.56） |
| 18 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-check.ps1` | **B4 接口验收 + 判别力自证 + 夹具自建自清**：P-*（03-posting §8，23 条）A-*（04a §5.1，30 条）B-*（04b，16 条）S-*（05a §5，24 条）T-*（06-stockops §8，23 条）L-*（07-ledger §7，16 条）E2E-*（采购线/调拨/盘点/并发过账） | 需要后端在跑 + token + MySQL；`-Selftest` 自证判别力、`-Only <前缀>` 只跑某段；退出码 0=全绿 / 1=有 FAIL / 2=前置不满足；**未实现的条目一律 SKIP 且单独计数（SKIP 不是 PASS）**；夹具按 ASCII 前缀走业务接口 + SQL 收尾并断言**零残留**；⚠ 脚本内**无锁**（同第 16 条）⇒ 经 `locked-run -LockName env` 串行 |
| 19 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-form-consistency-check.ps1` | **模板 `form_id` ↔ 流程 `content.formId` 一致性巡检 + PRD V-8 反向校验**（t16/HANDOFF §11-8/9）：只读打 `GET /workflow/simple-flow/form-consistency` 并体检 8 类问题（MISMATCH / FLOW_FORM_MISSING·DELETED·DISABLED …）；再用 `T16PROBE-` 探针验证"构造错位→检出""字段失效→阻断且不留半成品""字段仍有效→放行但告警""改指新版→巡检转干净" | 需要已登录 + MySQL(3306)/Redis/RabbitMQ 与后端在线（token 见 `tools\oa-login.ps1`）；`-OnlyReadOnly` 只跑只读巡检、`-KeepProbeFixtures` 留夹具复核；探针**从不发布流程**（不产生 `ACT_*` 行），收尾删夹具并复核巡检回基线；每次调用判 body 的 `code`（§6.56：认证失败 200+401、业务异常 200+500）；⚠ 脚本内**无锁** ⇒ 经 `locked-run -LockName env` 串行 |

**2.0 批次新增回归脚本清单**（AC-83 要求的 3 个，落到对应变更集的 `tasks.md`）：

| 脚本 | 覆盖 | 落在 | 可执行文件 |
| --- | --- | --- | --- |
| 模板四页签重构回归 | 四个页签容器、逐页签校验入口、死字段已删 | `oa-form-flow-tabs` §6.5 | `tools\audit\audit-template-tabs.js` |
| 流程↔模板绑定回归 | `by-template` 取/建草稿、发布回写三列、`defKey` 派生规则 | `oa-form-flow-tabs` §4.7 | `tools\b1-binding-check.ps1`（真实环境）＋ `tools\audit\audit-flow-template-binding.js`（静态） |
| 关联审批四态回归 | 设计器能拖 / 拟稿能填 / 审批能看 / 打印能出、越界选择被拒、浮窗越权 403 | `oa-form-flow-tabs` §7.11 | `tools\related-approval-check.ps1`（真实环境，20 项断言） |
| 多内置打印模板回归 | 4 套内置版式、签批栏不再被强制关闭 | `oa-print-builtin-templates` | `tools\print-builtin-check.ps1`（真实环境，98 条断言）＋ `tools\audit\audit-print-builtin-templates.js`（静态） |

**规格侧硬约束（违反即交付不通过）**：
1. 既有回归资产全绿，且 V1 的 `AC-01..AC-44` 不回归（存量模板、存量流程、在途单据、已产生打印件行为不变）；
2. 新增能力必须**同批交付**它的回归脚本，脚本可重复执行并具备自证能力；
3. **移植缺陷不得一并搬入**：并发写入必须加锁（不丢更新）、金额必须"先舍入再汇总"（无分位差异）、
   原环境靠应用层代替的数据库约束必须在 MySQL 侧真正落地；
4. 2.0 业务模块必须复用 OA 的平台能力（认证/组织/权限/字典/编号/附件/日志/打印），
   **禁止**第二套账号表、组织树、自研令牌、代码常量权限表，**禁止**任何"关闭认证即可访问全部数据"的后门。

## 8. 2.0 存量归并迁移（B1 §1.2–1.4，`REQ-DATA-004` / `AC-68`）

> **背景**：`updateTemplate` 的历史实现是"编辑 = 旧行置 `enable_flag='0'+del_flag='1`、插入新 UUID"，
> 于是所有 `template_id` 外键表（打印模板、节点字段权限）在**每次编辑模板后集体失联**。
> B1 §1.1 已把它改为**原地 UPDATE（id 不变）**（代码在
> `ruoyi-vue-oa-master/ruoyi-template/.../TemplateServiceImpl.java`），本节负责把**存量**数据归并干净。
>
> 三份脚本在 `ruoyi-vue-oa-master/sql/`：
>
> | 脚本 | 作用 | 改数据 |
> | --- | --- | --- |
> | `二开-2.0-B1-存量体检.sql` | 输出映射与**影响行数报告** | 否（只读） |
> | `二开-2.0-B1-存量归并.sql` | 归并 + 写行级快照 `t_template_migrate_backup` | **是** |
> | `二开-2.0-B1-存量归并回滚.sql` | 按快照还原 | **是** |
>
> **归并规则（三份脚本共用，改一处必须同步三处）**：
> **A** "版本链" = `t_template` 中 `(name, type, form_id)` 相同的行；只有链上**恰好一个**
> `del_flag='0'` 行时才把废弃行映射到它（0 个或 ≥2 个一律不动）；
> **B** 只搬**配置类白名单**（`t_template_print_template`、`t_template_node_field_auth`
> + B1 新增的 3 张），**不搬** `t_workflow_*` 在途/历史单据表与 `t_print_log`
> —— 历史单据必须保持"发起时那一版模板"的关联；
> **C** 只在"启用行在该表**一行都没有**"时才搬，且链上有多版废弃行时只搬**最新**那一版
> （否则打印模板会出现两套启用、节点字段权限会撞 `uk_node_field`）。

### 8.1 前置：先做库副本（**不要在业务库 rad_oa 上直接跑**）

```powershell
$bin  = "F:\dsh\ruoyiOA\env\mysql\server\bin"   # 本机 MySQL 客户端（随 MySQL 便携版）
$dump = "F:\dsh\ruoyiOA\.cache\rad_oa-copy.sql"
& "$bin\mysql.exe" --host=127.0.0.1 --user=root -e "DROP DATABASE IF EXISTS rad_oa_b1copy; CREATE DATABASE rad_oa_b1copy DEFAULT CHARSET utf8mb4;"
& "$bin\mysqldump.exe" --host=127.0.0.1 --user=root --single-transaction --routines --triggers --default-character-set=utf8mb4 --result-file=$dump rad_oa
& "$bin\mysql.exe" --host=127.0.0.1 --user=root --database=rad_oa_b1copy --default-character-set=utf8mb4 -e "source $dump"
```

### 8.2 固定执行顺序（四步，顺序不能变）

> ⚠️ **中文文件名不能直接当命令行参数传给 `mysql.exe`**（现象是
> `Failed to open file '...二开-...sql', error: 2`），必须走 stdin 管道
> `Get-Content -Raw | mysql`。下面的命令已按此写好，**可直接复制**。

```powershell
$bin  = "F:\dsh\ruoyiOA\env\mysql\server\bin"
$sql  = "F:\dsh\ruoyiOA\ruoyi-vue-oa-master\sql"
$conn = @("--host=127.0.0.1","--user=root","--database=rad_oa_b1copy","--default-character-set=utf8mb4","--table")

# 步骤 1：体检（只读）—— 影响行数报告就打在这里，同时留档到 logs
Get-Content -Raw -Encoding UTF8 "$sql\二开-2.0-B1-存量体检.sql" |
  & "$bin\mysql.exe" @conn | Tee-Object -FilePath "F:\dsh\ruoyiOA\logs\b1-migrate-check.txt"

# 步骤 2：归并（改数据；先看步骤 1 的报告再执行）
Get-Content -Raw -Encoding UTF8 "$sql\二开-2.0-B1-存量归并.sql" | & "$bin\mysql.exe" @conn

# 步骤 3：复核（再跑一次体检）—— ⑤ 应变为 0；⑥ 是刻意保留的行
Get-Content -Raw -Encoding UTF8 "$sql\二开-2.0-B1-存量体检.sql" | & "$bin\mysql.exe" @conn

# 步骤 4：回滚演练（默认回滚最近一批；指定批次见 8.4）
Get-Content -Raw -Encoding UTF8 "$sql\二开-2.0-B1-存量归并回滚.sql" | & "$bin\mysql.exe" @conn
```

每一步的期望结果：**步骤 1** 打印 ①~⑥ 与三张明细表；**步骤 2** 的"快照行数"必须等于
"实际改写行数"且 > 0（若为 0，说明没有可归并的存量，直接进入步骤 4 即可，不是错误）；
**步骤 3** 的 ⑤ = 0；**步骤 4** 的"已还原行数" = 步骤 2 的"实际改写行数"、"未还原" = 0。

### 8.3 影响行数报告在哪、怎么读

- 报告就是**步骤 1 的输出**（留档：`logs\b1-migrate-check.txt`）：
  ① 废弃行总数 ② 可安全归并数 ③ 涉及启用行数 ④ 无法归并数 ⑤ 本次会搬走的配置行数 ⑥ 保留不动；
- 明细 **B** = "废弃行 → 启用行"映射（迁移脚本按它改数据）；
  明细 **C** = 每张配置表的影响行数；明细 **D** = **不归并**的废弃行与原因。
- ④/⑥ 不为 0 是**正常且刻意**的：二义链（同名同分类同表单存在多个启用行）、孤儿废弃行
  （没有启用版本）、以及"启用行已有同类配置"的行都不动 —— 判不准的后继宁可不动，
  也不能改错数据。
- 想按行定位"保留不动"的行：
  `SELECT * FROM <配置表> WHERE template_id IN (<明细 B 里的废弃行ID>);`

### 8.4 回滚

- **数据**：`二开-2.0-B1-存量归并回滚.sql`（默认回滚最近批次）。指定批次：
  `... -e "CALL sp_oa_b1_rollback_template_versions('B1-20261004202437');"`
  脚本只还原**当前仍处于迁移后状态**的行（`template_id = new_template_id`）；
  迁移后被人工改过的行不会被覆盖，而是计入"未还原"（这些行需要人工确认）。
  连续执行两次安全：第二次"已还原" = 0。
- **代码**：三个仓库各自回退到发布前 commit（基线见 §5.1）。
- **结构**：新增的 4 张表可直接 `DROP`；`t_template` 的增列可保留——默认值就是旧行为。

### 8.5 两个坑（本机实测踩过，脚本已内置规避）

1. **排序规则**：库默认 `utf8mb4_general_ci`，而业务列是 `utf8mb4_0900_ai_ci`。
   MySQL 下"列 vs 列""**存储过程局部变量 vs 列**"比较都要求两侧排序规则相同，
   否则报 `ERROR 1267 Illegal mix of collations`。三份脚本因此把临时表/快照表
   建成 `t_template.id` 的**实际排序规则**，且凡与列比较处一律走动态 SQL
   把变量**内联成字面量**。
2. **中文文件名**：见 8.2 开头（`mysql.exe` 收到的是被转码过的路径，打不开文件）。

### 8.6 服务层自检（B1 §1.5）

`updateTemplate` 的"原地更新"语义有一个不连数据库的自检程序（内存桩 Mapper，
直接跑真实 Service）：

```powershell
cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests -pl ruoyi-template test-compile
mvn -B -q -pl ruoyi-template dependency:build-classpath -Dmdep.outputFile=cp.txt
$deps = (Get-Content "ruoyi-template\cp.txt" -Raw).Trim()
& "D:\Program Files\Java\jdk-11\bin\java.exe" -cp "ruoyi-template\target\test-classes;ruoyi-template\target\classes;$deps" `
  com.ruoyi.template.TemplateUpdateSemanticsCheck
```

判定：14 项断言全过、退出码 0。**自证有效**：把 `updateTemplate` 改回"换 ID"老实现，
该程序会失败 9 项（`insertTemplate` 被调用、模板行数变 2、子表关联读不到）。

### 8.7 增量 SQL 的统一执行入口（`tools\run-db-sql.ps1`）

`sql\初始化-全部.sql` 用 `source <文件名>` 声明**执行顺序**（唯一真源）；但 `mysql.exe`
在本机打不开中文文件名，所以要由本脚本把被引用文件的内容**读进内存再走 stdin**执行
（顺带把编排文件末尾的自检也跑掉）。

```powershell
# 空库初始化：上游基线 table.sql + data.sql + 全部二开增量 + 编排自检
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 -Database <空库名> -WithBaseline

# 已有库只补 2.0 B1 的两个增量（幂等，可重跑）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 -Database rad_oa `
  -Files "二开-2.0-B1-t_template增列.sql,二开-2.0-B1-模板新增表.sql"
```

期望输出末尾的自检（编排自检 + 各增量自带的自检）：
`t_template 的 13 个 2.0 列 = 13`、`2.0 新增 4 张表 = 4`、`V1 二开表 = 8`，
以及 B2 增量自带的 `builtin_print_key 存在且默认 contract = 1`、`存量行的 builtin_print_key 非空 = 0`、
`show_signature 默认 0 = 1`、`show_attachment 默认 0 = 1`（见 `二开-2.0-B2-打印模板内置版式.sql`）。

> ⚠️ `-Files` 只能用**逗号分隔的单字符串**（`powershell -File` 不能给 `[string[]]` 传数组）。
> ⚠️ 本机**命令行参数里的中文会被转码坏**（`mysql -e "…where name='中文'…"` 查不到数据）。
> 用 SQL 查中文值时请换 ASCII 条件（如 `def_key`/`id`），或把 SQL 写进文件再走 stdin。

## 9. 2.0 B1 可发起范围 / 流程管理员（排错手册）

> 变更集 `oa-form-flow-tabs` §3；需求 `REQ-PERM-001`/`REQ-PERM-005`；验收 `AC-48`。
> 一句话：**模板的"谁能发起"与"谁能改流程"都由服务端判定**，前端隐藏只是体验。

### 9.1 数据落在哪

| 位置 | 含义 |
| --- | --- |
| `t_template.submit_scope_type` | `0` 全员（**默认**）/ `1` 指定人员 / `2` 指定角色 / `3` 指定部门 |
| `t_template.include_child_dept` | 部门范围是否含下级，默认 `1`（含） |
| `t_template_submit_scope` | 明细：`scope_type` + `target_id`（人员/角色/部门ID 三选一），一模板一套（全量替换） |
| `t_template_flow_admin` | 这条模板的**流程管理员**账号；为空则回退"创建人 + 有 `workflow:template:edit` 者" |
| `t_template.simple_flow_id` | 模板绑定的简化流程草稿（发布授权按它反查模板） |

### 9.2 没有新增权限点（重要）

- 发起侧**不新增权限点**：范围判定是"模板级"的，登录即可发起（列表自过滤 + 落库前兜底 403）。
- 流程侧**复用平台 RBAC**：`workflow:simpleFlow:{edit,publish}` 仍是设计器接口的声明式门，
  但**已绑定模板的流程**改由模板级授权决定，否则 delta spec 要求的
  「被指定为流程管理员者 SHALL 能发布」无法成立（这几个权限点默认只有超管拥有）：

| 流程是否绑定模板 | 谁能编辑/发布 |
| --- | --- |
| 已绑定（2.0 新建的模板流程） | 系统管理员、该模板的流程管理员、模板创建人、有 `workflow:template:edit` 者 |
| 未绑定（V1 存量流程、设计器里独立新建） | 沿用既有权限点 `workflow:simpleFlow:edit` / `:publish`（**行为与升级前一致**） |

实现见 `ruoyi-workflow` 的 `SimpleFlowAuthz`（声明式入口）+ `TemplateServiceImpl#checkFlowManagePermission`（真正判定）。

### 9.3 排错：发起页看不到某个模板，先分清是"范围"还是"无分类"

按顺序查（**中文别放命令行参数**，见 §8.7 的坑；下面用 id 做条件）：

```sql
-- ① 模板本身是否启用/未删除
SELECT id, name, enable_flag, del_flag, submit_scope_type, include_child_dept, type
  FROM t_template WHERE id='<模板ID>';

-- ② 明细配了什么
SELECT scope_type, target_id FROM t_template_submit_scope WHERE template_id='<模板ID>';

-- ③ 一条判定 SQL：把 <用户ID> 换成 sys_user.user_id，直接告诉我这个人能不能看到
SET @uid = '<用户ID>';
SELECT t.id, t.name,
       CASE
         WHEN t.del_flag <> '0' OR t.enable_flag <> '1' THEN '模板未启用/已删除'
         WHEN IFNULL(t.submit_scope_type,'0') = '0'       THEN '全员可见'
         WHEN EXISTS (SELECT 1 FROM t_template_submit_scope s
                       WHERE s.template_id=t.id AND s.scope_type='1' AND s.target_id=@uid) THEN '人员命中'
         WHEN EXISTS (SELECT 1 FROM t_template_submit_scope s
                       WHERE s.template_id=t.id AND s.scope_type='2'
                         AND s.target_id IN (SELECT r.role_id FROM sys_user_role r WHERE r.user_id=@uid)) THEN '角色命中'
         WHEN EXISTS (SELECT 1 FROM t_template_submit_scope s
                        JOIN sys_user u ON u.user_id=@uid
                       WHERE s.template_id=t.id AND s.scope_type='3'
                         AND (s.target_id=u.dept_id
                              OR (t.include_child_dept='1'
                                  AND FIND_IN_SET(s.target_id, (SELECT d.ancestors FROM sys_dept d WHERE d.dept_id=u.dept_id))))) THEN '部门命中'
         ELSE '范围未命中 → 这个人看不到'
       END AS `可见性判定`
  FROM t_template t WHERE t.id='<模板ID>';
```

判读要点：

1. **`superAdmin` 不受范围限制**（`SecurityUtils.isAdmin`）："超管看得到、别人看不到"是**预期**，不是 bug。
2. **"指定"类但明细为空**：除超管外谁都不放行（保存时也会拦：必须至少选一项）。
3. **无分类模板**（`type` 为空）：升级前会被**静默丢弃**，现在归入「未分类」组返回。
   所以"发起页什么都没有"要分两种空态：**没有模板** vs **没有你可发起的模板**（后者才是范围问题）。
4. 部门范围用 `sys_dept.ancestors` 做**整段**匹配（不是子串），`D-100` 不会被 `D-1000` 误命中。

### 9.4 越权返回形态（写脚本时按这个断言）

- 越权发起：业务码 **403**，`msg` 含 **「发起权限」**；**不落任何库**（`t_workflow_form` 行数不变）。
- 越权发布：业务码 **403**，`msg` 含 **「流程管理权限」**；流程版本与模板绑定都不变。
- ⚠️ 后端在业务异常时**HTTP 仍回 200**，一律看响应体的 `code`（`tools/authz-check.ps1` 的 `IsForbidden` 就是这么判的）。

以上 6 条断言已并入 `tools/authz-check.ps1`（跑 `powershell ... -File .\tools\authz-check.ps1`）。

### 9.5 模板四页签 / 流程页签的两个真坑（2026-10-04 浏览器实测抓到）

这两个都是**编译能过、接口单测能过**，只有真在浏览器里点一遍才会暴露的：

1. **两个页签的 model 必须是同一个对象**。`add.vue` 保存时
   `payload = Object.assign({}, basicForm.model, businessForm.model)`；
   如果接口回显时各给一份浅拷贝，`businessForm.model` 里带着**旧的**回显值，
   会把基础信息页签上的编辑（图标/说明/名称/分组/可发起范围）整片覆盖掉。
   实测症状：图标选择器显示"单据"，保存后库里 `icon` 仍是 `NULL`。
   修法：`const model = {...res.data, ...}` 之后 `baseInfo = formInfo = policyInfo = model`。
2. **`t_template` 根本没有 `remark` 列**（PRD §8.1 写的是"复用现有 remark 列，不新增列"，
   但 `table.sql:2146-2168` 里没有，`TemplateMapper.xml` 里也零命中）。
   不补列的话，页面上填的「说明」会被**静默丢弃**。
   已在 `二开-2.0-B1-t_template增列.sql` 里补上 `remark varchar(500)`（该脚本因此是 **14 列**），
   并在 Mapper 的 resultMap / select / insert / update 四处同步登记。

> 教训：四页签这类"拆结构"的重构，**必须用浏览器把保存链路点一遍并回查数据库** ——
> 只跑静态审计与接口用例，这两个坑都不会报错。

### 9.6 写验收夹具的铁律：**绝不要把发布用例打在共享模板上**（2026-10-04 踩过）

`/workflow/simple-flow/publish` 会做两件**改数据**的事，范围都是"按这个流程找模板"：

1. `syncNodeFieldAuth(def)`：按 `t_template.def_key = 流程key` 找到模板，
   **先删后插**它的「节点只读字段」（`t_template_node_field_auth`）；
2. 发布回写：把 `def_key` / `simple_flow_id` / `flow_mode` 写到模板上。

于是"拿共享测试模板 `1ADB8299342C4D4FA59EC9F38AB5C768`（def_key=testSerial）发布一条
临时流程"会**连锁搞挂另外两个门禁**：

- `sign-feature-check` 的 AC-12 依赖该模板 n1 上的 `amount` 只读配置 → 被清空后 `该节点确实配了 amount 为只读` 失败；
- `flow-regression` 的 `startFlow` 按 `def_key` 起流程 → def_key 被改成临时流程的 key 后，
  起的是另一条定义，`testSerial · 首节点为会签也能发起` 失败（并连带跳过 4 个用例）。

**做法**：夹具一律**克隆**一个模板（`INSERT ... SELECT`），绑定与发布只作用于克隆件，
收尾时把克隆模板连同它的 `t_template_submit_scope` / `t_template_flow_admin` 一起删掉。
`tools/authz-check.ps1` 的 B1 段就是这么改的（`$AuthzCloneId`）。
如果曾经被误伤，恢复办法：`UPDATE t_template SET def_key='testSerial' WHERE id='1ADB82993...'`
＋ 重新插入那两行只读配置（`REPLACE INTO t_template_node_field_auth ...`）。

### 9.7 新增自定义控件必须同时登记**四处**（2026-10-04 踩过「下拉永远无数据」）

`关联审批` 控件（B1 §7）落地时，缺任何一处都会表现成"能用但不对"，很难一眼看出：

1. `components/render/render.js` 的 `components` 登记（否则运行时白屏）；
2. `utils/generator/config.js` 的 `formOaComponents`（左侧可拖拽清单）；
3. 后端 `ComponentTypeEnum`（否则值转换落 `UNKNOWN` 并打"未支持的组件类型"告警）；
4. **`__vModel__` 的透传**：`render.js` 的 `buildDataObject` 会把 `__vModel__` 当作
   "取值绑定"处理掉、**不会进 attrs 也不会进 props**。控件若需要知道自己的字段名
   （关联审批要拿它去服务端查候选），就必须在 `render()` 里显式透传
   （现在是 `dataObject.props.vModel = confClone.__vModel__`）。
   漏了这个的症状是：接口一次都不发、下拉显示"无数据"，而控制台没有任何报错。

另外两条同源经验：
- "是不是设计态"不能只看 props 判断：拟稿页把 `templateId` 放在**路由 query** 上，
  只看 props 会把正常拟稿页误判成设计态，连候选都不去查（已改为计算属性统一口径）。
- 组件里不要同时保留"props"和"同名方法"两套解析逻辑，容易只改一处。

## 10. 结论

- ✅ 运行时：6 个服务全部在线，前后端 HTTP 200，登录 / 流程发起 / 表单保存链路实测通过。
- ✅ 工具链：三处硬阻断（JDK 21、Node OpenSSL、**Maven 代理不自启**）均已消除，
  `mvn`（含 `mvn test`）与 `npm run dev` 开箱即用。
- ✅ 构建：全量构建 + 单模块构建均通过，Maven "malformed project" 告警消失。
- ✅ 版本控制：3 个仓库基线已建立。
- ✅ 2.0 交付门禁：已固化（见第 7 节，`REQ-NFR-010`/`REQ-NFR-011`、`AC-83`）。
- ✅ B1 §1 前置（存量归并）：三份脚本已在库副本演练通过（体检 → 归并 → 复核 → 回滚），
  服务层自检 14/14 且"改回换 ID 实现即失败"（见第 8 节）。
- ✅ B1（`oa-form-flow-tabs`）：34 项任务全部完成，门禁全绿。
- ✅ B2（`oa-print-builtin-templates`）：内置版式按单据类型选用（4 套）、签批栏与"是否内置"解耦、
  版式常量收口到后端唯一真源、纸张方向/Logo/抄送栏生效、留痕改为硬门禁、打印入口补齐 4 处。
  门禁 7/7 全绿（静态审计 9 个、越权 30 条、签名 44 条、流程 20 条、打印版式 98 条、
  后端单测 19 条、前端用例 35 条）；AC-62 已在浏览器实测（fund 版式打出三栏签名区）。
  详见 `doc/2.0/B2-打印模板配置与打印入口说明.md`。
  > ⚠ 上列数字是 **B2 交付当时**的实测值（历史记录，不改写）。其后 B3 期间的变化：
  > 静态审计 9 → **10**（新增 `audit-theme-tokens.js`，见 §6.51）、越权 30 → **45**（B3 §9.3 新增 15）、
  > 前端用例 35 → **39**、后端 `ruoyi-ctms` 单测 → **208**。现行口径以 §7 门禁表为准。
- ⬜ 可选待办：kkFileView（附件在线预览）、IDE 热部署配置、前端依赖版本收紧。

## 11. 2.0 B2 打印（快速排错）

> 完整说明见 `doc/2.0/B2-打印模板配置与打印入口说明.md`。这里只给"症状 → 一条 SQL/命令"。

**先记住优先级链**：`显式 printTplId` > `该单据模板下的启用行` > `内置版式（按 builtin_print_key）`。
任何"打印件版式不对"的问题，都先按这个链定位是哪一段：

```sql
-- ① 这张单据解析到哪个单据模板？（第五路 my_draft 是草稿的唯一来源，见第 23 条）
SELECT template_id FROM (
  SELECT template_id, 1 ord FROM t_workflow_todo    WHERE business_id='<单据ID>' AND template_id IS NOT NULL
  UNION ALL SELECT template_id, 2 FROM t_workflow_done    WHERE business_id='<单据ID>' AND template_id IS NOT NULL
  UNION ALL SELECT template_id, 3 FROM t_workflow_recycle WHERE business_id='<单据ID>' AND template_id IS NOT NULL
  UNION ALL SELECT template_id, 4 FROM t_workflow_form    WHERE id='<单据ID>'          AND template_id IS NOT NULL
  UNION ALL SELECT template_id, 5 FROM t_workflow_my_draft WHERE biz_id='<单据ID>'     AND template_id IS NOT NULL
) t ORDER BY ord LIMIT 1;

-- ② 该单据模板上绑的是哪套内置版式？
SELECT id, name, builtin_print_key FROM t_template WHERE id='<单据模板ID>';

-- ③ 有没有启用的自定义打印模板？（有它内置版式就不参与）
SELECT id, name, enable_flag, show_signature, show_attachment, show_cc_node, paper, orientation, logo_file_id
  FROM t_template_print_template WHERE template_id='<单据模板ID>' AND del_flag='0' ORDER BY update_time DESC;
```

| 症状 | 先看 |
| --- | --- |
| 标题/栏目是合同版式，但单据是资金类 | ③ 是否有启用行；② `builtin_print_key` 是不是 `fund` |
| 签批栏（签名区）不出 | ③/生效模板的 `show_signature`；内置版式自带 `'1'`（AC-62），自定义模板要显式打开 |
| 草稿（我起草的）打印出来是空的 | 第 23 条：`t_workflow_form.template_id` 为 NULL 是正常的，要看 `t_workflow_my_draft` |
| 抄送栏不出 | 生效模板的 `show_cc_node='1'` **且** `t_workflow_todo` 里有 `handle_type='6'` 的记录 |
| 纸张/方向没生效 | 生效模板的 `paper` / `orientation`；取值不认识时按 A4 纵向 |
| 点打印没反应、提示"留痕写入失败" | **这是设计**（留痕硬门禁，AC-67）：先查 `.cache\token-*.txt` 与 `/workflow/print/log` 的返回 |
| 存量单据的打印件**多出了签名区** | **有意变更**（AC-62）：内置版式自带"签批栏开启"，需逐张人工确认（见 B2 说明文档 §5） |
| 列表页看不到「待办」行的打印按钮 | 待办列表的行在 `todo/group-table.vue`（不是 `todo-list.vue`），浏览器要强刷以取到新 chunk |
