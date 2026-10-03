# RuoYi-Vue-OA 二次开发环境手册

> 本文件由环境体检产出，记录**本机实际可用**的配置（而非项目默认值）。
> 最后核对时间：2026-10-03 18:35

工程位置：

| 目录 | 说明 |
| --- | --- |
| `H:\dsh\ruoyiOA\ruoyi-vue-oa-master` | 后端（Maven 多模块，28 个模块） |
| `H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | 前端（Vue2 + Element UI + Vue CLI 4） |
| `H:\dsh\ruoyiOA\tools` | 本次体检新增的辅助脚本 |
| `H:\dsh\ruoyiOA\logs` | 后端 / 前端 / broker / RabbitMQ 运行日志 |

---

## 1. 服务与端口

| 端口 | 服务 | 启动方式 | 数据目录 |
| --- | --- | --- | --- |
| 80 | 前端 dev server | `npm run dev` | — |
| 8080 | ruoyi-admin 后端 | `java -jar ruoyi-admin/target/ruoyi-admin.jar` | `H:\dsh\ruoyiOA\uploadPath` |
| 3306 | MySQL 8.0.40 | `H:\dsh\OA\oa-deploy\runtime\start-local.ps1 -SkipApp` | `H:\dsh\OA\oa-deploy\runtime\mysql\data` |
| 6379 | Redis 5.0.14.1 | 同上 | `H:\dsh\OA\oa-deploy\runtime\redis` |
| 5672 | RabbitMQ 3.12.14 | `H:\dsh\ruoyiOA\tools\start-rabbitmq.ps1` | `H:\dsh\ruoyiOA\.cache\rabbitmq` |
| 8544 | ruoyi-im-broker（Netty WebSocket `/im`） | `java -jar ruoyi-im-broker/target/ruoyi-im-broker.jar` | — |
| 15672 | RabbitMQ 控制台（**未启用**） | `rabbitmq-plugins enable rabbitmq_management` | — |
| 8012 | kkFileView 附件在线预览（**未部署**） | 需另行部署 | — |

> ⚠️ MySQL / Redis 与同机另一个项目 `H:\dsh\OA` **共用同一套实例**，不要随意改 root 口令或删库。

## 2. 账号与凭据

| 用途 | 账号 | 密码 |
| --- | --- | --- |
| 系统登录（唯一账号） | `superAdmin` | `admin123` |
| MySQL（应用用） | `ruoyi` | `ruoyi`（仅授权 `rad_oa` 库） |
| MySQL（管理用 root） | `root` | **空口令** |
| RabbitMQ | `admin` | `admin`（`ruoyi-mq-core` 里写死） |
| Redis | 无密码 | — |
| Druid 监控台 `/druid` | `ruoyi` | `123456` |

## 3. 工具链要求（本机现状）

| 组件 | 项目要求 | 本机情况 | 结论 |
| --- | --- | --- | --- |
| JDK | 源码 `-source/target 1.8`，Spring Boot 2.5.15 | **JAVA_HOME 指向 JDK 21**；另有 JDK 11 在 `D:\Program Files\Java\jdk-11` | ❌ **必须显式用 JDK 11** |
| Maven | 3.x | 3.9.16 @ `C:\Tools\apache-maven-3.9.16` | ✅ |
| Node | README 写 14+ | v24.19.0 | ⚠️ 需 OpenSSL 兼容开关 |
| npm | — | 11.17.0 | ✅ |
| Shell | — | **Windows PowerShell 5.1（无 pwsh 7）** | ⚠️ 脚本注意 `??`、`-Parallel` 等 7.x 语法不可用 |

### 3.1 JDK：默认会编译失败（已实测）

`JAVA_HOME` 全局指向 JDK 21，直接 `mvn` 编译 `ruoyi-common` 会失败：

```
[ERROR] Fatal error compiling: java.lang.NoSuchFieldError:
  Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field '...JCTree qualid'
```

原因是 **Lombok 1.18.20 不兼容 JDK 21**。注意**不要**把全局 `JAVA_HOME` 改成 11 ——
同机的 `H:\dsh\OA` 项目（Spring Boot 3.2.12）需要 JDK 21。正确做法是**按命令行临时指定**：

```cmd
set "JAVA_HOME=D:\Program Files\Java\jdk-11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
mvn -B -DskipTests install
```

### 3.2 Node 24：必须打开 legacy OpenSSL（已实测）

`@vue/cli-service` 4.4.6 内部用 webpack 4，会调用 `crypto.createHash('md4')`：

```
md4 FAIL: ERR_OSSL_EVP_UNSUPPORTED / error:0308010C:digital envelope routines::unsupported
```

已在本仓库新增 `ruoyi-vue-oa-ui-master\.npmrc` 自动注入，**无需手动设置**：

```ini
node-options=--openssl-legacy-provider
registry=https://registry.npmmirror.com
legacy-peer-deps=true
```

## 4. 常用命令

```powershell
# ---- 基础设施（按顺序）----
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\dsh\OA\oa-deploy\runtime\start-local.ps1" -SkipApp   # MySQL + Redis
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\dsh\ruoyiOA\tools\start-rabbitmq.ps1"                  # RabbitMQ

# ---- 后端：构建（必须 JDK 11）----
$env:JAVA_HOME="D:\Program Files\Java\jdk-11"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests install          # 全量（推荐；单模块构建见「已知坑」）
mvn -B -DskipTests -pl ruoyi-admin -am package   # 只重建 admin 及其依赖

# ---- 后端：运行 ----
java -jar ruoyi-admin\target\ruoyi-admin.jar            # 用 JDK 11 的 java
java -jar ruoyi-im-broker\target\ruoyi-im-broker.jar    # IM broker（可选，但 admin 依赖它声明 system.store.queue）

# ---- 前端 ----
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm run dev            # http://localhost/（.npmrc 已注入 OpenSSL 开关）
npm run build:prod     # 产物 dist/
```

## 5. 已知坑（体检发现）

| 级别 | 问题 | 影响 | 处理 |
| --- | --- | --- | --- |
| **P0** | `JAVA_HOME` 指向 JDK 21，Lombok 不兼容 | `mvn` 编译直接失败 | 每终端先 `set JAVA_HOME=...jdk-11` |
| **P0** | Node 24 + webpack4 的 OpenSSL 问题 | 前端起不来 / 打包失败 | 已由 `.npmrc` 解决 |
| **P1** | 全工程**没有任何 git 仓库** | 二次开发无版本控制、改坏无法回滚 | 建议 `git init`（见下） |
| **P1** | `ruoyi-mq/pom.xml` 的 `<version>${ruoyi.mq.version}</version>` 非字面量，导致本地仓库里装的是 `com.ruoyi:ruoyi-mq:pom:${ruoyi.mq.version}` | Maven 报 "malformed project… threatens the stability of your build"；`mvn -pl ruoyi-admin` **不带 `-am` 时构建失败**（实测 `dependency:resolve` 报 `Failed to read artifact descriptor for com.ruoyi:ruoyi-mq-async:jar:1.0.1`）。带 `-am` 则 BUILD SUCCESS | 临时绕过：始终加 `-am`。彻底修复：把 `ruoyi-mq` 及其两个子模块 `<parent>` 的版本改成字面量 `1.0.1`，再 `mvn -B -DskipTests install` |
| **P1** | `bin\run.bat` / `package.bat` / `clean.bat` 直接调 `java`/`mvn` | 会走 JDK 21，必然失败 | 勿直接用；用上面第 4 节命令 |
| **P2** | `spring-boot-devtools` 已开启（`devtools.restart.enabled=true`） | 从 jar 运行时不生效；热部署需 IDE 或 `mvn spring-boot:run` | 按需 |
| **P2** | 前端 `vue.config.js` 的 `open: true` | 每次 `npm run dev` 自动弹浏览器 | 嫌烦可改 `false` |
| **P2** | kkFileView（8012）未部署 | 附件在线预览不可用 | 需要时另行部署 |
| **P2** | RabbitMQ 控制台未启用 | 无 Web 管理界面 | `rabbitmq-plugins enable rabbitmq_management` |

### 5.1 关于 git

`H:\dsh\ruoyiOA`、后端、前端三处都**不是 git 仓库**（两份源码是解压下来的，没有 `.git`）。
两个工程各自已带 `.gitignore`。开始二次开发前强烈建议：

```powershell
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-master ; git init ; git add -A ; git commit -m "chore: 基线（已可运行）"
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master ; git init ; git add -A ; git commit -m "chore: 基线（已可运行）"
```

> 提交前确认 `.gitignore` 覆盖了 `target/`、`node_modules/`、`dist/`。

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

## 7. 体检结论

- ✅ 全套运行时服务可用，前后端均 HTTP 200，登录与流程发起/表单保存链路实测通过。
- ❌ **两处硬阻断**（JDK 21、Node OpenSSL）已给出确定解法，其中前端已通过 `.npmrc` 消除。
- ⚠️ **无版本控制**，建议动手改代码前先 `git init` 建基线。
