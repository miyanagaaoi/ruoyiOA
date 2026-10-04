# RuoYi-Vue-OA 二次开发环境手册

> 本文件记录**本机实际可用**的配置（而非项目默认值）。
> 体检时间：2026-10-03 18:35 ｜ 修复落地：2026-10-03 18:50

工程位置：

| 目录 | 说明 |
| --- | --- |
| `H:\dsh\ruoyiOA\ruoyi-vue-oa-master` | 后端（Maven 多模块，28 个模块） |
| `H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | 前端（Vue2 + Element UI + Vue CLI 4） |
| `H:\dsh\ruoyiOA\tools` | 环境辅助脚本 |
| `H:\dsh\ruoyiOA\doc\参考文档` | 审批单等需求参考素材 |
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
| 15672 | RabbitMQ 控制台 ✅ 已启用 | 随 broker 自动启动 | — |
| 8012 | kkFileView 附件在线预览（**未部署**） | 需另行部署 | — |

> ⚠️ MySQL / Redis 与同机另一个项目 `H:\dsh\OA` **共用同一套实例**，不要随意改 root 口令或删库。

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
| Shell | — | **Windows PowerShell 5.1（无 pwsh 7）** | ⚠️ 别用 `??`、`-Parallel` 等 7.x 语法 |

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

## 4. 常用命令

```powershell
# ---- 基础设施（按顺序）----
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\dsh\OA\oa-deploy\runtime\start-local.ps1" -SkipApp   # MySQL + Redis
powershell -NoProfile -ExecutionPolicy Bypass -File "H:\dsh\ruoyiOA\tools\start-rabbitmq.ps1"                  # RabbitMQ

# ---- 后端：构建（无需设置 JAVA_HOME，toolchains 会自动用 JDK 11）----
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-master
mvn -B -DskipTests install                       # 全量
mvn -B -DskipTests -pl ruoyi-admin package       # 单模块（已修复，不带 -am 也可用）

# ---- 后端：运行（必须用 JDK 11 的 java）----
& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-admin\target\ruoyi-admin.jar
& "D:\Program Files\Java\jdk-11\bin\java.exe" -jar ruoyi-im-broker\target\ruoyi-im-broker.jar

# ---- 前端 ----
cd H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master
npm run dev            # http://localhost/
npm run build:prod     # 产物 dist/
```

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

补充说明：

- **`package-lock.json` 已纳入版本控制**（前端 `.gitignore` 原本忽略它）。本机 Node 24 下
  `vue` / `vue-template-compiler` 都被 `^` 解析到 2.7.16，锁文件保证环境可复现。
- Maven 的 `settings.xml` 配了本机代理 `127.0.0.1:7899`，`github.com` 直连不通，
  下载 GitHub 资源时需走代理（如 `Invoke-WebRequest -Proxy http://127.0.0.1:7899`）。

### 5.1 git 基线（已建好）

| 仓库 | 位置 | 基线提交 | 跟踪文件 |
| --- | --- | --- | --- |
| 外层（手册/脚本/参考文档） | `H:\dsh\ruoyiOA` | `2fbfc99` | 15 |
| 后端 | `H:\dsh\ruoyiOA\ruoyi-vue-oa-master` | `23ece02` | 1094 |
| 前端 | `H:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master` | `beadb41` | 675 |

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
      而真正有文件的目录是 `H:/dsh/ruoyiOA/uploadPath`（经典 RuoYi 的 `RuoYiConfig.getProfile()`）；
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

## 7. 结论

- ✅ 运行时：6 个服务全部在线，前后端 HTTP 200，登录 / 流程发起 / 表单保存链路实测通过。
- ✅ 工具链：两处硬阻断（JDK 21、Node OpenSSL）均已消除，`mvn` 与 `npm run dev` 开箱即用。
- ✅ 构建：全量构建 + 单模块构建均通过，Maven "malformed project" 告警消失。
- ✅ 版本控制：3 个仓库基线已建立，工作区干净。
- ⬜ 可选待办：kkFileView（附件在线预览）、IDE 热部署配置、前端依赖版本收紧。
