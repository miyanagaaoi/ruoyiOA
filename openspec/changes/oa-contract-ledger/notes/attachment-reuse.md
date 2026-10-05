# B3 §1.5 附件复用方案验证（`/common/upload` 能否满足合同附件的业务语义）

> 变更集：`oa-contract-ledger`｜任务：tasks.md §1.5（对应 design.md D-5，DEV-ENV §6.20）
> 验证时间：**2026-10-05 08:20**｜环境：后端 jar `2026-10-05 08:19:02`，6 服务在线
> 被测入口：`POST /common/upload`（multipart，字段名 `file`）——**未改一行代码**

## 结论

| 判据 | 结果 | 说明 |
| --- | --- | --- |
| ① 返回 `/` 开头的相对路径、加 `VUE_APP_BASE_API` 前缀后可访问 | ✅ 通过 | 见 §1 |
| ① 随机文件名，不覆盖同名文件 | ✅ 通过 | 见 §1 |
| ② 白名单外后缀被拒 | ⚠️ **部分通过（白名单比要求更宽）** | 见 §2.1 |
| ② 超过 20MB 的文件被拒 | ❌ **不通过** | 平台上限是 **50MB**，25MB 文件被**接受**；见 §2.2 |
| ③ 5.2 之外的取回链路（不带前缀会拿到 SPA/404） | ✅ 与既有结论一致 | 见 §1 |

**结论：附件方案可以复用，但两处业务语义必须在 B3 的附件层（任务 6.2）自己补齐** ——
① 用**更窄**的 CTMS 白名单（`.pdf/.doc/.docx/.xls/.xlsx/.png/.jpg/.jpeg/.gif/.txt`）；
② 自己校验 **≤20MB**（不能依赖平台，平台是 50MB）。

## 1. 上传与取回（判据 ① ✅）

**第一次上传** `b3-sample.pdf`（15 字节）：

```json
{"msg":"操作成功","code":200,
 "fileName":"/profile/upload/2026/10/05/b3-sample_20261005082058A001.pdf",
 "newFileName":"b3-sample_20261005082058A001.pdf",
 "url":"http://127.0.0.1:8080/profile/upload/2026/10/05/b3-sample_20261005082058A001.pdf",
 "originalFilename":"b3-sample.pdf"}
```

**同名再传一次** → 得到 **另一个** 存储名（`...A002.pdf`），原文件不被覆盖：

```json
{"code":200,"fileName":"/profile/upload/2026/10/05/b3-sample_20261005082058A002.pdf"}
```

随机命名的实现是 `FileUploadUtils.extractFilename()`：
`StringUtils.format("{}/{}_{}.{}", DateUtils.datePath(), 原文件名, Seq.getId(uploadSeqType), 扩展名)`
（`ruoyi-common/.../FileUploadUtils.java:146`）—— 即「日期目录 + 原名 + 全局序号 + 后缀」，
所以**同名不同次**必然不同名。

取回校验：

| 地址 | 结果 |
| --- | --- |
| `http://localhost/dev-api/profile/upload/2026/10/05/b3-sample_20261005082058A001.pdf` | **HTTP 200 / `application/pdf` / 15 字节真实内容** ✅ |
| `http://localhost/profile/upload/2026/10/05/...pdf`（**不加前缀**） | 取不到（前端 dev server 回 404）❌ |

→ 与 `DEV-ENV.md` §6.20 的口径一致：**落库存相对路径，显示时拼 `VUE_APP_BASE_API`**。
（差别只在于"不加前缀时看到的是 SPA 的 HTML 还是 404"，两者都取不到文件，结论相同。）

## 2. 两项业务限制（判据 ②）

### 2.1 后缀白名单 —— ⚠️ 平台有白名单，但比 CTMS 要求**更宽**

实测 `.exe` 被拒（`code=500`）：

```
文件[b3-sample.exe]后缀[exe]不正确，请上传[bmp, gif, jpg, jpeg, png, doc, docx, xls, xlsx,
ppt, pptx, html, htm, txt, rar, zip, gz, bz2, mp4, avi, rmvb, pdf]格式
```

来源：`FileUploadUtils.assertAllowed()` + `MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION`
（`ruoyi-common/.../MimeTypeUtils.java:29-38`）。

⚠ **平台白名单里包含 CTMS 不允许的外缀**：`ppt/pptx/html/htm/rar/zip/gz/bz2/mp4/avi/rmvb`。
合同附件的要求是 `.pdf/.doc/.docx/.xls/.xlsx/.png/.jpg/.jpeg/.gif/.txt`（任务 6.2），
所以**不能**直接依赖平台白名单，必须在 B3 的附件层做一次**更窄**的校验
（否则合同上能挂 `.exe→改名成 .zip`、`.html`、视频等，与参考侧语义不符）。

### 2.2 单文件 ≤20MB —— ❌ 平台**不**满足，25MB 被接受

| 文件 | 大小 | 实测结果 |
| --- | --- | --- |
| `b3-25mb.pdf` | 25MB | **`code=200` 上传成功**（`...A003.pdf`）← 应被拒 |
| `b3-55mb.pdf` | 55MB | `code=500`：`上传的文件大小超出限制的文件大小！…允许的文件最大大小是：50MB！` |

来源：`FileUploadUtils.DEFAULT_MAX_SIZE = 50 * 1024 * 1024L`（`FileUploadUtils.java:29,190`）；
`application.yml` 的 `spring.servlet.multipart.max-file-size: 500MB` 只是容器层上限，**不参与**这里的判定。

→ 合同附件的 **20MB** 上限必须在 B3 附件层自己校验；并且按任务 6.2 的要求，
超限时**删除半成品文件**并返回 413（平台现在是"先落盘、后校验"的顺序：
`FileUploadUtils.upload()` 内部先 `assertAllowed` 再写盘，所以平台自身不留半成品，
但 B3 自己实现"先传后校验"的话必须显式清理）。

## 3. 对任务 6.2 的输入（必须自己实现的清单）

| 要做的事 | 为什么平台给不了 |
| --- | --- |
| 更窄的白名单（10 种） | 平台白名单含 11 种 CTMS 不允许的外缀 |
| 单文件 ≤20MB | 平台是 50MB，实测 25MB 通过 |
| 超限即删半成品 + 返回 **413** | 平台返回的是 `code=500` + 中文文案，不是 413 语义 |
| 按业务对象鉴权（`object_type` + `object_id`） | 平台上传是"无对象"的通用上传，没有对象概念（这正是 D-5 要新建 `t_ctms_attachment` 的原因） |
| 删除附件写变更历史（字段名 `附件`） | 平台无此概念（任务 6.3） |

> 复用结论：**上传/存储/取回链路（`/common/upload` + `/profile/**` 静态映射）可以整体复用**，
> 不要引第二套上传（`/file/operate/**` 在本机是坏的，见 DEV-ENV §6.20）；
> 但"业务语义层"必须自建，且自建点在 `ruoyi-ctms` 里，不改 `ruoyi-common` 的通用工具
> （它是 11 类既有业务的共用件）。

---

### 附：本次验证用的命令

```powershell
# 上传（字段名必须是 file）
curl.exe -s -X POST "http://127.0.0.1:8080/common/upload" `
  -H "Authorization: Bearer <token>" -F "file=@<路径>\b3-sample.pdf"

# 取回（必须带 VUE_APP_BASE_API 前缀 = /dev-api）
Invoke-WebRequest "http://localhost/dev-api/profile/upload/2026/10/05/....pdf" -UseBasicParsing
```

> 验证用的 4 个测试文件（含 25MB / 55MB）与上传产生的 3 个存储文件在验证结束后**已删除**。
